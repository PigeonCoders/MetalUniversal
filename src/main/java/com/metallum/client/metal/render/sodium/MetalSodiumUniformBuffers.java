package com.metallum.client.metal.render.sodium;

import com.metallum.client.metal.render.DiagLog;
import com.metallum.client.metal.render.Diagnostics;
import com.metallum.client.metal.render.MetalCommandEncoder;
import com.metallum.client.metal.render.MetalGpuBuffer;
import com.metallum.client.metal.render.MetalTransientMemory;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Matrix4fc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * SODIUM-ADAPT：Sodium terrain shader 的 uniform 值存储（阶段 3，fix11 重构）。
 *
 * <p>实测 MSL 里普通 uniform 是独立 buffer 参数（{@code constant T& u_X [[buffer(n)]]}，
 * 非合成 UBO）。
 *
 * <p>⚠️ fix11（uniform 覆写竞争修复）：Metal 的 setBuffer 绑定的是 buffer 对象引用，
 * GPU 执行时读 buffer 当前内存——若所有 region 共用固定 buffer 且每 region 覆写，
 * GPU 执行任意 draw 时读到的是「最后一个 region」写入的值（GL 的 glUniform 是
 * per-draw 快照，Metal 不是——iOS 实测：地形整体上下偏移、随视角闪现）。
 * 因此拆成两类：
 * <ul>
 *   <li><b>per-pass 固定 buffer（9 个 × 3 组 ring）</b>：值 pass 内不变（projection/
 *       modelView/texCoordShrink/fadePeriodInv/fog×3/texelSize/useRGSS），pass 内写一次
 *       （覆写同值无影响）。<b>P2.5</b>：每组按帧轮转（frame % 3）——fix11 只解决帧内
 *       多 region 覆写，帧间 CPU 覆写 vs GPU 跨帧读（与 staging 同构，穷尽审计唯一
 *       实质残留竞争源）由 ring 消除：帧 N 用组 k，帧 N+3 复用组 k 时 GPU 至多执行
 *       帧 N+1/N+2（SYNC_MODE=3 落后 ≤2 帧 < 3 组）。</li>
 *   <li><b>per-region transient 块（regionOffset/currentTime）</b>：每 region 从
 *       MetalTransientMemory 分配独立块（帧内不回收、块内偏移互不重叠），
 *       写入后 GPU 读各自块——互不覆写。</li>
 * </ul>
 *
 * <p>ChunkData（GlBufferStreamer buffer）每 region 独立，不走本类。
 */
@Environment(EnvType.CLIENT)
public final class MetalSodiumUniformBuffers implements AutoCloseable {
    private static final int USAGE = GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST;

    /** per-region transient 分配：regionOffset(float3, 16B 对齐) + currentTime(int, 4B) */
    private static final long REGION_UNIFORM_SIZE = 32L;
    private static final long REGION_UNIFORM_ALIGNMENT = 16L;
    /** currentTime 在块内的偏移（regionOffset 之后，16B 对齐） */
    private static final int CURRENT_TIME_OFFSET = 16;

    /** per-region uniform 的两个 buffer slice（GPU 绑定用 buffer + offset）。 */
    public record RegionUniformSlices(GpuBufferSlice regionOffset, GpuBufferSlice currentTime) {
    }

    /** per-pass ring 组数：SYNC_MODE=3 下 GPU 落后 ≤2 帧，3 组轮转安全（与 MAX_SUBMITS_IN_FLIGHT 对齐）。 */
    private static final int RING_GROUPS = 3;
    private static final int UNIFORM_COUNT = 9;

    /** MSL buffer 参数名 → 组内下标。 */
    private static final int IDX_PROJECTION = 0;
    private static final int IDX_MODEL_VIEW = 1;
    private static final int IDX_TEX_COORD_SHRINK = 2;
    private static final int IDX_FADE_PERIOD_INV = 3;
    private static final int IDX_FOG_COLOR = 4;
    private static final int IDX_ENVIRONMENT_FOG = 5;
    private static final int IDX_RENDER_FOG = 6;
    private static final int IDX_TEXEL_SIZE = 7;
    private static final int IDX_USE_RGSS = 8;
    private static final int[] UNIFORM_SIZES = {64, 64, 8, 4, 16, 8, 8, 8, 4};

    /** 3 组 per-pass 固定 buffer（按帧轮转，P2.5）。 */
    private final MetalGpuBuffer[][] groups = new MetalGpuBuffer[RING_GROUPS][UNIFORM_COUNT];

    private boolean passUniformsWritten;

    public MetalSodiumUniformBuffers(final com.metallum.client.metal.render.MetalDevice device) {
        for (int g = 0; g < RING_GROUPS; g++) {
            for (int i = 0; i < UNIFORM_COUNT; i++) {
                this.groups[g][i] = new MetalGpuBuffer(device, USAGE, UNIFORM_SIZES[i]);
            }
        }
    }

    /** 组决策纯函数：帧号 → 组（帧 N 用组 N%3，同帧内所有调用稳定一致；负值防御取 0）。 */
    static int groupForFrame(final long frame) {
        if (frame < 0L) {
            return 0;
        }
        return (int) (frame % RING_GROUPS);
    }

    /** 按 MSL buffer 参数名取 per-pass 固定 buffer（当前帧组）；per-region（u_RegionOffset/u_CurrentTime）
     * 与外部 buffer（ChunkData 等）返回 null（调用方特殊处理）。 */
    @org.jspecify.annotations.Nullable
    public MetalGpuBuffer forBinding(final String name) {
        int idx = switch (name) {
            case "u_ProjectionMatrix" -> IDX_PROJECTION;
            case "u_ModelViewMatrix" -> IDX_MODEL_VIEW;
            case "u_TexCoordShrink" -> IDX_TEX_COORD_SHRINK;
            case "u_FadePeriodInv" -> IDX_FADE_PERIOD_INV;
            case "u_FogColor" -> IDX_FOG_COLOR;
            case "u_EnvironmentFog" -> IDX_ENVIRONMENT_FOG;
            case "u_RenderFog" -> IDX_RENDER_FOG;
            case "u_TexelSize" -> IDX_TEXEL_SIZE;
            case "u_UseRGSS" -> IDX_USE_RGSS;
            default -> -1;
        };
        if (idx < 0) {
            return null;
        }
        return this.groups[groupForFrame(MetalCommandEncoder.currentFrameIndex())][idx];
    }

    /** 每个 pass 开始（metalBegin）时调用：重置 per-pass 写入标志。 */
    public void markPassStart() {
        this.passUniformsWritten = false;
    }

    /**
     * 写入 9 个 per-pass uniform（pass 内第一次调用生效，幂等）。
     * 值来自 interface（setupState 在 metalBegin 计算 fog/texelSize 等，
     * setProjectionMatrix/setModelViewMatrix 在 render 开头——第一次
     * beginTessellating 时均已就绪）。
     */
    public void uploadPassUniforms(final MetalSodiumShaderInterface shaderInterface) {
        if (this.passUniformsWritten) {
            return;
        }
        // P2.5：写入当前帧组（与 forBinding 同帧一致——同帧内所有调用取同一组）
        MetalGpuBuffer[] group = this.groups[groupForFrame(MetalCommandEncoder.currentFrameIndex())];
        Matrix4fc projection = shaderInterface.projectionMatrix();
        Matrix4fc modelView = shaderInterface.modelViewMatrix();
        if (projection != null) {
            writeMatrix(group[IDX_PROJECTION], projection);
        }
        if (modelView != null) {
            writeMatrix(group[IDX_MODEL_VIEW], modelView);
        }
        writeFloats(group[IDX_TEX_COORD_SHRINK], 8, shaderInterface.texCoordShrinkX(), shaderInterface.texCoordShrinkY());
        writeFloat(group[IDX_FADE_PERIOD_INV], shaderInterface.fadePeriodInv());
        writeFloats(group[IDX_FOG_COLOR], 16,
                shaderInterface.fogColorR(), shaderInterface.fogColorG(), shaderInterface.fogColorB(), shaderInterface.fogColorA());
        writeFloats(group[IDX_ENVIRONMENT_FOG], 8, shaderInterface.environmentFogStart(), shaderInterface.environmentFogEnd());
        writeFloats(group[IDX_RENDER_FOG], 8, shaderInterface.renderFogStart(), shaderInterface.renderFogEnd());
        writeFloats(group[IDX_TEXEL_SIZE], 8, shaderInterface.texelSizeX(), shaderInterface.texelSizeY());
        writeInt(group[IDX_USE_RGSS], shaderInterface.useRGSS() ? 1 : 0);
        this.passUniformsWritten = true;
    }

    /**
     * per-region：从 transient 分配独立块并写入 regionOffset/currentTime。
     * 每 region 调用一次（beginTessellating）；块帧内不回收（帧末 rotate），
     * GPU 读各自块——不再有「最后 region 覆写」竞争。
     */
    public RegionUniformSlices allocateRegionUniforms(final MetalSodiumShaderInterface shaderInterface) {
        MetalCommandEncoder encoder = (MetalCommandEncoder) com.metallum.client.metal.render.MetalBackend.activeDevice().createCommandEncoder();
        MetalTransientMemory.MappedView view = encoder.allocateTransientUniform(REGION_UNIFORM_SIZE, REGION_UNIFORM_ALIGNMENT);
        GpuBufferSlice block = view.slice();

        ByteBuffer data = view.data().order(ByteOrder.nativeOrder());
        data.rewind();
        data.asFloatBuffer()
                .put(shaderInterface.regionOffsetX())
                .put(shaderInterface.regionOffsetY())
                .put(shaderInterface.regionOffsetZ());
        data.position(CURRENT_TIME_OFFSET);
        data.putInt(shaderInterface.currentTime());

        // 诊断（diag）：fade/regionOffset 值抽查——fade 计算异常（值 0/巨大/NaN）
        // 会使方块呈雾色（=天空色，间隙）或抖动；regionOffset 异常 → 位置错。
        if (Diagnostics.shouldRun("sodium-fade", 5_000L)) {
            float rx = shaderInterface.regionOffsetX();
            float ry = shaderInterface.regionOffsetY();
            float rz = shaderInterface.regionOffsetZ();
            int ct = shaderInterface.currentTime();
            float fpi = shaderInterface.fadePeriodInv();
            boolean nan = Float.isNaN(rx) || Float.isNaN(ry) || Float.isNaN(rz) || Float.isNaN(fpi);
            DiagLog.log("[diag] sodium regionUniform regionOffset=(%.2f,%.2f,%.2f) currentTime=%d fadePeriodInv=%.6f%s",
                    rx, ry, rz, ct, fpi, nan ? " NAN-DETECTED" : "");
        }

        return new RegionUniformSlices(
                new GpuBufferSlice(block.buffer(), block.offset(), 16L),
                new GpuBufferSlice(block.buffer(), block.offset() + CURRENT_TIME_OFFSET, 4L)
        );
    }

    private static void writeMatrix(final MetalGpuBuffer buffer, final Matrix4fc matrix) {
        // JOML 列主序布局与 MSL float4x4（column-major）一致
        float[] values = new float[16];
        matrix.get(values);
        ByteBuffer dst = buffer.currentStorage().order(ByteOrder.nativeOrder());
        dst.rewind();
        dst.asFloatBuffer().put(values);
    }

    private static void writeFloats(final MetalGpuBuffer buffer, final int bufferSize, final float... values) {
        ByteBuffer dst = buffer.currentStorage().order(ByteOrder.nativeOrder());
        dst.rewind();
        dst.limit(bufferSize);
        dst.asFloatBuffer().put(values);
    }

    private static void writeFloat(final MetalGpuBuffer buffer, final float value) {
        buffer.currentStorage().order(ByteOrder.nativeOrder()).rewind().putFloat(value);
    }

    private static void writeInt(final MetalGpuBuffer buffer, final int value) {
        buffer.currentStorage().order(ByteOrder.nativeOrder()).rewind().putInt(value);
    }

    @Override
    public void close() {
        for (int g = 0; g < RING_GROUPS; g++) {
            for (int i = 0; i < UNIFORM_COUNT; i++) {
                this.groups[g][i].close();
            }
        }
    }
}
