package com.metallum.client.metal.render.sodium;

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
 *   <li><b>per-pass 固定 buffer（9 个）</b>：值 pass 内不变（projection/modelView/
 *       texCoordShrink/fadePeriodInv/fog×3/texelSize/useRGSS），pass 内写一次即可
 *       （覆写同值无影响）。</li>
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

    private final MetalGpuBuffer projection;     // u_ProjectionMatrix float4x4 (64B)
    private final MetalGpuBuffer modelView;      // u_ModelViewMatrix  float4x4 (64B)
    private final MetalGpuBuffer texCoordShrink; // u_TexCoordShrink   float2  (8B)
    private final MetalGpuBuffer fadePeriodInv;  // u_FadePeriodInv    float   (4B)
    private final MetalGpuBuffer fogColor;       // u_FogColor         float4  (16B)
    private final MetalGpuBuffer environmentFog; // u_EnvironmentFog   float2  (8B)
    private final MetalGpuBuffer renderFog;      // u_RenderFog        float2  (8B)
    private final MetalGpuBuffer texelSize;      // u_TexelSize        float2  (8B)
    private final MetalGpuBuffer useRGSS;        // u_UseRGSS          bool    (4B)

    private boolean passUniformsWritten;

    public MetalSodiumUniformBuffers(final com.metallum.client.metal.render.MetalDevice device) {
        this.projection = new MetalGpuBuffer(device, USAGE, 64L);
        this.modelView = new MetalGpuBuffer(device, USAGE, 64L);
        this.texCoordShrink = new MetalGpuBuffer(device, USAGE, 8L);
        this.fadePeriodInv = new MetalGpuBuffer(device, USAGE, 4L);
        this.fogColor = new MetalGpuBuffer(device, USAGE, 16L);
        this.environmentFog = new MetalGpuBuffer(device, USAGE, 8L);
        this.renderFog = new MetalGpuBuffer(device, USAGE, 8L);
        this.texelSize = new MetalGpuBuffer(device, USAGE, 8L);
        this.useRGSS = new MetalGpuBuffer(device, USAGE, 4L);
    }

    /** 按 MSL buffer 参数名取 per-pass 固定 buffer；per-region（u_RegionOffset/u_CurrentTime）
     * 与外部 buffer（ChunkData 等）返回 null（调用方特殊处理）。 */
    @org.jspecify.annotations.Nullable
    public MetalGpuBuffer forBinding(final String name) {
        return switch (name) {
            case "u_ProjectionMatrix" -> this.projection;
            case "u_ModelViewMatrix" -> this.modelView;
            case "u_TexCoordShrink" -> this.texCoordShrink;
            case "u_FadePeriodInv" -> this.fadePeriodInv;
            case "u_FogColor" -> this.fogColor;
            case "u_EnvironmentFog" -> this.environmentFog;
            case "u_RenderFog" -> this.renderFog;
            case "u_TexelSize" -> this.texelSize;
            case "u_UseRGSS" -> this.useRGSS;
            default -> null;
        };
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
        Matrix4fc projection = shaderInterface.projectionMatrix();
        Matrix4fc modelView = shaderInterface.modelViewMatrix();
        if (projection != null) {
            writeMatrix(this.projection, projection);
        }
        if (modelView != null) {
            writeMatrix(this.modelView, modelView);
        }
        writeFloats(this.texCoordShrink, 8, shaderInterface.texCoordShrinkX(), shaderInterface.texCoordShrinkY());
        writeFloat(this.fadePeriodInv, shaderInterface.fadePeriodInv());
        writeFloats(this.fogColor, 16,
                shaderInterface.fogColorR(), shaderInterface.fogColorG(), shaderInterface.fogColorB(), shaderInterface.fogColorA());
        writeFloats(this.environmentFog, 8, shaderInterface.environmentFogStart(), shaderInterface.environmentFogEnd());
        writeFloats(this.renderFog, 8, shaderInterface.renderFogStart(), shaderInterface.renderFogEnd());
        writeFloats(this.texelSize, 8, shaderInterface.texelSizeX(), shaderInterface.texelSizeY());
        writeInt(this.useRGSS, shaderInterface.useRGSS() ? 1 : 0);
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
        this.projection.close();
        this.modelView.close();
        this.texCoordShrink.close();
        this.fadePeriodInv.close();
        this.fogColor.close();
        this.environmentFog.close();
        this.renderFog.close();
        this.texelSize.close();
        this.useRGSS.close();
    }
}
