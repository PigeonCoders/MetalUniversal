package com.metallum.client.metal.render.sodium;

import com.metallum.Metallum;
import com.metallum.client.metal.render.DiagLog;
import com.metallum.client.metal.render.Diagnostics;
import com.metallum.client.metal.render.MetalCommandEncoder;
import com.metallum.client.metal.render.MetalGpuBuffer;
import com.metallum.client.metal.render.MetalGpuSampler;
import com.metallum.client.metal.render.MetalGpuTextureView;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLIndexType;
import com.metallum.client.metal.render.mtl.MTLPixelFormat;
import com.metallum.client.metal.render.mtl.MTLPrimitiveType;
import com.metallum.client.metal.render.mtl.MTLRenderCommandEncoder;
import com.metallum.client.metal.render.mtl.MTLWinding;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.caffeinemc.mods.sodium.client.gl.device.DrawCommandList;
import net.caffeinemc.mods.sodium.client.gl.device.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.gl.tessellation.GlIndexType;
import net.caffeinemc.mods.sodium.client.gl.tessellation.GlPrimitiveType;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;

import java.lang.foreign.MemorySegment;

/**
 * SODIUM-ADAPT：Metal 版 DrawCommandList（阶段 3，核心映射）。
 *
 * <p>{@link DrawCommandList#multiDrawElementsBaseVertex} 与 Metal 一一映射：
 * glMultiDrawElementsBaseVertex 的三个数组（pElementCount / pElementPointer 字节偏移 /
 * pBaseVertex）正是 {@code MTLRenderCommandEncoder.drawIndexedPrimitives} 的
 * (indexCount, offset, baseVertex) 参数——indexBuffer 作为 draw 参数直传，
 * baseVertex 原生支持，无需 GL 的"顶点缓冲偏移绑定"技巧。每 draw 一次调用，
 * 每 region 一批（batch.size 通常几十~几百）。
 *
 * <p>进入前提（fail-fast）：必须处于 MC 主 RenderPass 流程内（renderGroup 调用点），
 * activeRenderEncoder() 非 null；且 renderer.begin() 已注册 active state。
 * MTLPSO 的 attachment 配置必须与当前 encoder 严格一致——colorFormat/useDepth
 * 一律从 encoder 侧读取（唯一正确源）。
 *
 * <p>状态恢复：绘制结束后不动 encoder 状态——MC 后续绘制全部走独立
 * setPipeline/新 pass（pipelineDirty 机制自动重绑），无需显式恢复（addMainPass
 * 流程实证）。
 */
@Environment(EnvType.CLIENT)
public final class MetalSodiumDrawCommandList implements DrawCommandList {
    private static final int STAGE_VERTEX = MetalSodiumCompiledPipeline.STAGE_VERTEX;

    /** ChunkData 缺失警告只打一次（每 region 都会触发，避免刷屏）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean ChunkDataMissingWarned = new java.util.concurrent.atomic.AtomicBoolean(false);

    private final MetalCommandEncoder encoder;
    private final MetalSodiumTessellation tessellation;
    private final MetalSodiumActiveState state;
    private final MetalSodiumUniformBuffers.RegionUniformSlices regionUniforms;
    private boolean closed;
    /** 诊断：5s 窗口内的 draw 数（DiagLog 节流输出）。 */
    private long sodiumDrawCount;

    public MetalSodiumDrawCommandList(
            final MetalCommandEncoder encoder,
            final MetalSodiumTessellation tessellation,
            final MetalSodiumActiveState state,
            final MetalSodiumUniformBuffers.RegionUniformSlices regionUniforms
    ) {
        this.encoder = encoder;
        this.tessellation = tessellation;
        this.state = state;
        this.regionUniforms = regionUniforms;
    }

    /** batch 的读取逻辑抽成纯函数（Linux JUnit 可测）。 */
    public record DrawCommand(int elementCount, int baseVertex, long indexOffsetBytes) {
    }

    /** 从 MultiDrawBatch 的 native 数组读取第 i 条 draw 命令。
     *  ⚠️ 偏移语义：pElementPointer 是指针数组（元素 = i << POINTER_SHIFT 字节，
     *  即 i * POINTER_SIZE），pElementCount/pBaseVertex 是 int 数组（i * Integer.BYTES）
     *  ——与 Sodium 写入端（size << Pointer.POINTER_SHIFT / size << 2）严格对应。 */
    public static DrawCommand readBatchEntry(final MultiDrawBatch batch, final int index) {
        int elementCount = MemoryUtil.memGetInt(batch.pElementCount + (long) index * Integer.BYTES);
        int baseVertex = MemoryUtil.memGetInt(batch.pBaseVertex + (long) index * Integer.BYTES);
        long indexOffsetBytes = MemoryUtil.memGetAddress(batch.pElementPointer + ((long) index << Pointer.POINTER_SHIFT));
        return new DrawCommand(elementCount, baseVertex, indexOffsetBytes);
    }

    @Override
    public void multiDrawElementsBaseVertex(final MultiDrawBatch batch, final GlIndexType indexType) {
        if (this.closed) {
            throw new IllegalStateException("DrawCommandList already closed");
        }

        // SODIUM-ADAPT（fix9）：Sodium 绘制段内的 blit 上传（chunkFades 首帧 flush 经
        // writeToBuffer）会 endEncoder 结束 render encoder——此处主动重建（attachment
        // 取自 currentRenderPass），不再假设 encoder 恰好活跃。仍无则属接线错误。
        MTLRenderCommandEncoder enc = this.encoder.ensureActiveRenderEncoder();
        if (enc == null) {
            throw new IllegalStateException(
                    "Sodium draw outside of active Metal render pass (ensureActiveRenderEncoder() == null)");
        }
        if (this.tessellation.indexBuffer() == null) {
            throw new IllegalStateException("Sodium tessellation missing index buffer");
        }

        this.applyPipelineState(enc);
        this.applyVertexBuffer(enc);
        this.applyResources(enc);

        MetalGpuBuffer indexBuffer = this.tessellation.indexBuffer();
        MTLPrimitiveType primitiveType = toMetalPrimitive(this.tessellation.getPrimitiveType());
        MTLIndexType metalIndexType = toMetalIndex(indexType);

        // 诊断（diag）：draw 统计 + batch 全量摘要（P5 水面判别）——排查 per-section
        // local index buffer 段错位：io（indexOffset 字节）越界/相邻重复（双重绘制）/
        // 回退；bv（baseVertex）越界；e（elementCount）异常。
        sodiumDrawCount += batch.size;
        if (Diagnostics.shouldRun("sodium-draw", 5_000L)) {
            StringBuilder sb = new StringBuilder("[diag] sodium draws(last5s)=")
                    .append(sodiumDrawCount).append(" batch=").append(batch.size);
            if (batch.size > 0) {
                DrawCommand first = readBatchEntry(batch, 0);
                sb.append(" first=").append(first.elementCount()).append('/').append(first.baseVertex())
                        .append('/').append(first.indexOffsetBytes());
                long minIo = Long.MAX_VALUE;
                long maxIo = -1L;
                int minBv = Integer.MAX_VALUE;
                int maxBv = Integer.MIN_VALUE;
                int minE = Integer.MAX_VALUE;
                int maxE = -1;
                int nonZeroIo = 0;
                int dupIoPairs = 0;
                long prevIo = -1L;
                for (int i = 0; i < batch.size; i++) {
                    DrawCommand c = readBatchEntry(batch, i);
                    minIo = Math.min(minIo, c.indexOffsetBytes());
                    maxIo = Math.max(maxIo, c.indexOffsetBytes());
                    minBv = Math.min(minBv, c.baseVertex());
                    maxBv = Math.max(maxBv, c.baseVertex());
                    minE = Math.min(minE, c.elementCount());
                    maxE = Math.max(maxE, c.elementCount());
                    if (c.indexOffsetBytes() != 0L) {
                        nonZeroIo++;
                        if (c.indexOffsetBytes() == prevIo) {
                            dupIoPairs++;
                        }
                    }
                    prevIo = c.indexOffsetBytes();
                }
                sb.append(" ioR=[").append(minIo).append(',').append(maxIo)
                        .append("] bvR=[").append(minBv).append(',').append(maxBv)
                        .append("] eR=[").append(minE).append(',').append(maxE)
                        .append(" ioNz=").append(nonZeroIo).append(" dupIo=").append(dupIoPairs);
            } else {
                sb.append(" (empty)");
            }
            DiagLog.log("%s", sb);
            sodiumDrawCount = 0;
        }

        for (int i = 0; i < batch.size; i++) {
            DrawCommand command = readBatchEntry(batch, i);
            // glMultiDrawElementsBaseVertex 等价：indexBuffer 直传 + 字节偏移 + baseVertex
            enc.drawIndexedPrimitives(
                    primitiveType,
                    command.elementCount(),
                    metalIndexType,
                    indexBuffer.nativeHandle(),
                    command.indexOffsetBytes(),
                    1,
                    command.baseVertex(),
                    0
            );
        }
    }

    /** MTLPSO + 深度/剔除/填充状态（照抄 MetalRenderPass.bindDrawState 的 pipelineDirty 分支）。 */
    private void applyPipelineState(final MTLRenderCommandEncoder enc) {
        MetalSodiumCompiledPipeline pipeline = this.state.pipeline();
        boolean useDepth = this.encoder.activeHasDepth();
        MTLPixelFormat colorFormat = this.encoder.activeColorFormat();

        MemorySegment pipelineHandle = pipeline.getNativePipeline(useDepth, colorFormat);
        if (MetalNativeBridge.isNullHandle(pipelineHandle)) {
            throw new IllegalStateException("Sodium native pipeline unavailable (useDepth=" + useDepth + ", color=" + colorFormat + ")");
        }
        enc.setRenderPipelineState(pipelineHandle);

        if (useDepth) {
            MemorySegment depthState = pipeline.getDepthStencilState();
            if (MetalNativeBridge.isNullHandle(depthState)) {
                throw new IllegalStateException("Sodium native depth state unavailable");
            }
            enc.setDepthStencilState(depthState);
            // 判别实验（fix13）：Depth32Float 在深度 1.0 附近 ULP≈6e-8——共享边两侧的
            // 深度插值微差在 LEQUAL 边缘像素判定随旋转抖动（GL 固定点深度会抹平微差）。
            // constant 加 ~3 ULP 试探：缝隙消失 → 深度边缘竞争确认；仍在 → 排除。
            float biasConstant = pipeline.depthBiasConstant() + 2.0e-7f;
            enc.setDepthBias(biasConstant, pipeline.depthBiasScaleFactor(), 0.0f);
        }

        enc.setFrontFacingWinding(MTLWinding.Clockwise);
        enc.setCullMode(pipeline.cullMode());
        enc.setTriangleFillMode(pipeline.fillMode());
    }

    /** region geometry buffer → vertex buffer slot（实测键表：vertex uniform 占 0..7，slot=8）。 */
    private void applyVertexBuffer(final MTLRenderCommandEncoder enc) {
        MetalGpuBuffer vertexBuffer = this.tessellation.vertexBuffer();
        enc.setBuffer(vertexBuffer.nativeHandle(), 0, this.state.pipeline().firstAvailableVertexBufferSlot(), STAGE_VERTEX);
    }

    /** 按 pipeline 资源表逐资源绑定（uniform buffers + textures）。 */
    private void applyResources(final MTLRenderCommandEncoder enc) {
        MetalSodiumActiveState state = this.state;
        for (MetalSodiumCompiledPipeline.ResourceBinding binding : state.pipeline().resources()) {
            if (binding.kind() == MetalSodiumCompiledPipeline.ResourceKind.UNIFORM_BUFFER) {
                // fix11：per-region uniform（regionOffset/currentTime）用每 region 独立
                // transient 块（buffer + 块内偏移）——固定 buffer 覆写竞争已消除
                MetalGpuBuffer buffer;
                long bufferOffset = 0L;
                if ("u_RegionOffset".equals(binding.name())) {
                    buffer = (MetalGpuBuffer) this.regionUniforms.regionOffset().buffer();
                    bufferOffset = this.regionUniforms.regionOffset().offset();
                } else if ("u_CurrentTime".equals(binding.name())) {
                    buffer = (MetalGpuBuffer) this.regionUniforms.currentTime().buffer();
                    bufferOffset = this.regionUniforms.currentTime().offset();
                } else {
                    buffer = state.uniformBuffers().forBinding(binding.name());
                    if (buffer == null && "ChunkData".equals(binding.name())) {
                        // ChunkData 是 GlBufferStreamer 的 buffer（Sodium 侧上传），经 interface 取
                        buffer = state.shaderInterface().chunkDataBuffer();
                        // P5 水面判别：fade 值抽查（P3 后 ChunkData 为 Shared 可 CPU 读回）——
                        // fade=0/异常值 → 雾色/透明异常。256 项中统计 -1（恒不透明）、
                        // 0（异常：应写 build time 或 -1）、正值（淡入时间戳）计数。
                        if (buffer != null && buffer.isCpuAccessible()
                                && Diagnostics.shouldRun("sodium-chunkdata", 5_000L)) {
                            java.nio.ByteBuffer data = buffer.currentStorage();
                            int ints = data.remaining() / 4;
                            int countNegOne = 0;
                            int countZero = 0;
                            int countPositive = 0;
                            for (int k = 0; k < ints; k++) {
                                int v = data.getInt(k * 4);
                                if (v == -1) {
                                    countNegOne++;
                                } else if (v == 0) {
                                    countZero++;
                                } else {
                                    countPositive++;
                                }
                            }
                            DiagLog.log("[diag] sodium chunkFades total=%d negOne=%d zero=%d positive=%d first=%d,%d,%d,%d",
                                    ints, countNegOne, countZero, countPositive,
                                    ints > 0 ? data.getInt(0) : 0,
                                    ints > 1 ? data.getInt(4) : 0,
                                    ints > 2 ? data.getInt(8) : 0,
                                    ints > 3 ? data.getInt(12) : 0);
                        }
                    }
                }
                if (buffer == null) {
                    if (ChunkDataMissingWarned.compareAndSet(false, true)) {
                        Metallum.LOGGER.error("[metallum][sodium] ChunkData uniform buffer missing, skipping binding");
                    }
                    continue;
                }
                enc.setBuffer(buffer.nativeHandle(), bufferOffset, binding.bindingIndex(), binding.stageMask());
                continue;
            }

            if (binding.kind() == MetalSodiumCompiledPipeline.ResourceKind.SAMPLED_IMAGE) {
                applyTextureBinding(enc, binding);
            }
        }
    }

    /** texture 绑定：u_BlockTex（fragment stage 0）/ u_LightTex（vertex stage 4）。
     *  MSL 的 sampler 是独立参数，实测 texture/sampler index 恰好相等（texture0/sampler0、
     *  texture4/sampler4）→ 复用 setTextureAndSampler 桥（Swift 内部已是
     *  setVertexTexture+setVertexSamplerState 分步调用）。若未来 shader 的
     *  texture/sampler index 不等，需新增独立 setSamplerState 桥。 */
    private void applyTextureBinding(final MTLRenderCommandEncoder enc, final MetalSodiumCompiledPipeline.ResourceBinding binding) {
        MetalSodiumShaderInterface shaderInterface = this.state.shaderInterface();
        GpuTextureView textureView = switch (binding.name()) {
            case "u_BlockTex" -> shaderInterface.blockTex();
            case "u_LightTex" -> shaderInterface.lightTex();
            default -> null;
        };
        GpuSampler sampler = switch (binding.name()) {
            case "u_BlockTex" -> shaderInterface.terrainSampler();
            case "u_LightTex" -> shaderInterface.lightSampler();
            default -> null;
        };
        if (!(textureView instanceof MetalGpuTextureView metalTexture) || !(sampler instanceof MetalGpuSampler metalSampler)) {
            throw new IllegalStateException("Sodium texture/sampler for " + binding.name() + " not Metal-backed or missing");
        }
        enc.setTextureAndSampler(metalTexture.nativeHandle(), metalSampler.nativeHandle(), binding.bindingIndex(), binding.stageMask());
    }

    private static MTLPrimitiveType toMetalPrimitive(final GlPrimitiveType type) {
        return switch (type) {
            case TRIANGLES -> MTLPrimitiveType.Triangle;
            default -> throw new IllegalStateException("Unsupported Sodium primitive type: " + type);
        };
    }

    private static MTLIndexType toMetalIndex(final GlIndexType type) {
        return switch (type) {
            case UNSIGNED_INT -> MTLIndexType.UInt32;
            case UNSIGNED_SHORT -> MTLIndexType.UInt16;
            case UNSIGNED_BYTE -> throw new IllegalStateException("Unsupported Sodium index type: UNSIGNED_BYTE");
        };
    }

    @Override
    public void endTessellating() {
        // NO-OP：状态恢复由 MC 后续 setPipeline 机制保证
    }

    @Override
    public void flush() {
        // NO-OP
    }

    @Override
    public void close() {
        this.closed = true;
    }
}
