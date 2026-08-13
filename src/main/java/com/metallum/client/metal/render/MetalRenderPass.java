package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.*;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.SharedConstants;
import org.joml.Vector4fc;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.util.Collection;
import java.util.HashMap;
import java.util.function.Supplier;

@Environment(EnvType.CLIENT)
public final class MetalRenderPass implements RenderPass {
    static final boolean VALIDATION = SharedConstants.IS_RUNNING_IN_IDE;
    // 1.21.11 的 RenderPass 接口无 MAX_VERTEX_BUFFERS 常量（26.2 有）：按 26.2 值保持
    static final int MAX_VERTEX_BUFFERS = 16;
    private final MetalDevice device;
    private final MetalCommandEncoder commandEncoder;
    @Nullable
    private final String label;
    private final GpuTextureView colorTexture;
    @Nullable
    private final GpuTextureView depthTexture;
    @Nullable
    private Vector4fc clearColor;
    private boolean clearDepthEnabled;
    /** P10：-Dmetallum.nocull=true 判别实验——云 pass 强制 cullMode=None（验证 cull/winding
     * 镜像导致"云只显示部分象限"：fancy 云 UP/DOWN/NSWE 六朝向面，winding 不匹配 → Back-cull
     * 剔除朝上面/部分侧面）。地形等其它 pass 不受影响（只覆盖 CloudFaces 绑定后）。 */
    private static final boolean NO_CULL_DIAG = Boolean.parseBoolean(System.getProperty("metallum.nocull", "false"));
    /** P10：本 pass 最近一次是否绑定了 CloudFaces（drawIndexed 用 indexCount=6×quadCount 限定扫描）。 */
    private boolean boundCloudFaces;
    @Nullable
    private GpuBufferSlice cloudFacesSlice;
    private final double clearDepthValue;
    private final ScissorState scissorState = new ScissorState();
    private final GpuBufferSlice[] vertexBuffers = new GpuBufferSlice[MAX_VERTEX_BUFFERS];
    private final HashMap<String, GpuBufferSlice> uniforms = new HashMap<>();
    private final HashMap<String, TextureViewAndSampler> samplers = new HashMap<>();
    private long dirtyDescriptorMask;
    @Nullable
    private MetalCompiledRenderPipeline compiledPipeline;
    @Nullable
    private GpuBuffer indexBuffer;
    private MTLIndexType indexType = MTLIndexType.UInt16;
    private int pushedDebugGroups = 0;
    private boolean scissorDirty = true;
    private boolean vertexBuffersDirty = true;
    private boolean pipelineDirty = true;

    MetalRenderPass(
            final MetalDevice device,
            final MetalCommandEncoder encoder,
            final Supplier<String> label,
            final GpuTextureView colorTexture,
            @Nullable final GpuTextureView depthTexture,
            @Nullable final Vector4fc clearColor,
            final boolean clearDepthEnabled,
            final double clearDepthValue
    ) {
        this.device = device;
        this.commandEncoder = encoder;
        this.label = device.useLabels() ? label.get() : null;
        this.colorTexture = colorTexture;
        this.depthTexture = depthTexture;
        this.clearColor = clearColor;
        this.clearDepthEnabled = clearDepthEnabled;
        this.clearDepthValue = clearDepthValue;
    }

    @Override
    public void pushDebugGroup(final @NonNull Supplier<String> label) {
        pushedDebugGroups++;
        if (device.useLabels()) {
            commandEncoder.commandBuffer().pushDebugGroup(label.get());
        }
    }

    @Override
    public void popDebugGroup() {
        if (pushedDebugGroups == 0) {
            throw new IllegalStateException("Can't pop more debug groups than was pushed!");
        }
        pushedDebugGroups--;
        if (device.useLabels()) {
            commandEncoder.commandBuffer().popDebugGroup();
        }
    }

    @Override
    public void setPipeline(final @NonNull RenderPipeline pipeline) {
        MetalCompiledRenderPipeline compiled = device.getOrCompilePipeline(pipeline);
        if (this.compiledPipeline != compiled) {
            this.compiledPipeline = compiled;
            vertexBuffersDirty = true;
            pipelineDirty = true;
        }
    }

    @Override
    public void bindTexture(final @NonNull String name, @Nullable final GpuTextureView textureView, @Nullable final GpuSampler sampler) {
        if (textureView != null && sampler != null) {
            samplers.put(name, new TextureViewAndSampler(textureView, sampler));
            commandEncoder.flushPendingClear((MetalGpuTexture) textureView.texture());
            markDescriptorDirty(name);
        } else if (textureView == null && sampler == null) {
            samplers.remove(name);
        } else {
            throw new IllegalArgumentException();
        }
    }

    @Override
    public void setUniform(final @NonNull String name, final GpuBuffer value) {
        setUniform(name, value.slice());
    }

    @Override
    public void setUniform(final @NonNull String name, final @NonNull GpuBufferSlice value) {
        uniforms.put(name, value);
        markDescriptorDirty(name);
    }

    @Override
    public void enableScissor(final int x, final int y, final int width, final int height) {
        if (scissorState.enabled()
                && scissorState.x() == x
                && scissorState.y() == y
                && scissorState.width() == width
                && scissorState.height() == height) {
            return;
        }
        scissorState.enable(x, y, width, height);
        scissorDirty = true;
    }

    @Override
    public void disableScissor() {
        if (!scissorState.enabled()) {
            return;
        }
        scissorState.disable();
        scissorDirty = true;
    }

    @Override
    public void setVertexBuffer(final int slot, @Nullable final GpuBuffer vertexBuffer) {
        if (slot < 0 || slot >= MAX_VERTEX_BUFFERS) {
            throw new IllegalArgumentException("Unsupported Metal vertex buffer slot: " + slot);
        }
        // 1.21.11 传 GpuBuffer（26.2 为 GpuBufferSlice）：内部按整缓冲 slice 存储
        GpuBufferSlice slice = vertexBuffer == null ? null : vertexBuffer.slice();
        if (!sameSlice(vertexBuffers[slot], slice)) {
            vertexBuffers[slot] = slice;
            vertexBuffersDirty = true;
        }
    }

    @Override
    public void setIndexBuffer(@Nullable final GpuBuffer indexBuffer, final VertexFormat.IndexType indexType) {
        setIndexBuffer(indexBuffer, MTLIndexType.from(indexType));
    }

    private void setIndexBuffer(@Nullable final GpuBuffer indexBuffer, final MTLIndexType indexType) {
        if (this.indexBuffer != indexBuffer || this.indexType != indexType) {
            this.indexBuffer = indexBuffer;
            this.indexType = indexType;
        }
    }

    @Override
    public void drawIndexed(final int baseVertex, final int indexOffset, final int indexCount, final int instanceCount) {
        // 1.21.11 语义（GlCommandEncoder GL 实参反汇编实证）：
        // (baseVertex, indexBufferOffset, indexCount, instanceCount)
        // ——MCP 参数名（firstIndex, index, indexCount, primCount）有误导性
        if (this.indexBuffer == null) {
            Metallum.LOGGER.warn("[metallum] drawIndexed called with null index buffer, skipping draw");
            return;
        }
        MetalGpuBuffer nativeIndexBuffer = (MetalGpuBuffer) indexBuffer;
        MTLRenderCommandEncoder enc = renderEncoder();

        bindDrawState(enc);
        // P37 判别（Sodium 设置屏后段丢失）：本 pass 的 draw 统计（5s 节流）——
        // 打开设置屏时 draw 总数与 baseVertex 最大值揭示后段 draw 是否执行/顶点偏移。
        guiDrawCount++;
        if (baseVertex > guiMaxBaseVertex) {
            guiMaxBaseVertex = baseVertex;
        }
        if (Diagnostics.shouldRun("gui-draw", 5_000L)) {
            DiagLog.logAlways("[diag] gui-draw pass draws=%d maxBaseVertex=%d (last base=%d idx=%d)", guiDrawCount, guiMaxBaseVertex, baseVertex, indexCount);
            guiDrawCount = 0;
            guiMaxBaseVertex = 0;
        }
        // E13：云 draw 执行层观测——indexCount + index buffer 前 8 项（顺序索引应 0,1,2,3...）
        if (this.boundCloudFaces) {
            scanCloudFaces(indexCount);
            if (Diagnostics.shouldRun("cloud-idx", 5_000L)) {
                try {
                    java.nio.ByteBuffer ib = ((MetalGpuBuffer) nativeIndexBuffer)
                            .sliceStorage(0L, Math.min(64L, nativeIndexBuffer.allocationSize()));
                    StringBuilder sb = new StringBuilder("[diag] cloud idx count=").append(indexCount);
                    for (int k = 0; k < Math.min(ib.remaining() / 4, 8); k++) {
                        sb.append(' ').append(ib.getInt(k * 4));
                    }
                    DiagLog.log("%s", sb);
                } catch (IllegalStateException e) {
                    DiagLog.log("[diag] cloud idx not CPU-readable");
                }
            }
        }
        drawIndexedNative(enc, nativeIndexBuffer, indexOffset, indexCount, baseVertex, instanceCount, indexType, 0);
    }

    @Override
    public <T> void drawMultipleIndexed(
            final Collection<RenderPass.Draw<T>> draws,
            @Nullable final GpuBuffer defaultIndexBuffer,
            final VertexFormat.IndexType defaultIndexType,
            final @NonNull Collection<String> dynamicUniforms,
            final @NonNull T uniformArgument
    ) {
        VertexFormat.IndexType fallbackIndexType = defaultIndexType == null ? VertexFormat.IndexType.SHORT : defaultIndexType;

        int i = 0;
        for (RenderPass.Draw<T> draw : draws) {
            i++;
            MTLIndexType drawIndexType = MTLIndexType.from(draw.indexType() == null ? fallbackIndexType : draw.indexType());
            GpuBuffer currentIndexBuffer = draw.indexBuffer() == null ? defaultIndexBuffer : draw.indexBuffer();

            setIndexBuffer(currentIndexBuffer, drawIndexType);
            setVertexBuffer(draw.slot(), draw.vertexBuffer());

            if (draw.uniformUploaderConsumer() != null) {
                draw.uniformUploaderConsumer().accept(uniformArgument, this::setUniform);
            }

            MTLRenderCommandEncoder enc = renderEncoder();
            if (scissorDirty || vertexBuffersDirty || dirtyDescriptorMask != 0L || pipelineDirty) {
                bindDrawState(enc);
            }
            MetalGpuBuffer nativeIndexBuffer = (MetalGpuBuffer) indexBuffer;
            // 1.21.11 的 Draw 无 baseVertex 字段：顶点偏移恒为 0
            drawIndexedNative(enc, nativeIndexBuffer, draw.firstIndex(), draw.indexCount(), 0, 1, drawIndexType, 0);
        }
    }

    @Override
    public void draw(final int firstVertex, final int vertexCount) {
        // 1.21.11 语义（GlCommandEncoder GL 实参反汇编实证）：(firstVertex, vertexCount)
        MTLPrimitiveType primitiveType = primitiveTopology();
        MTLRenderCommandEncoder enc = renderEncoder();

        bindDrawState(enc);

        if (primitiveType == MTLPrimitiveType.TriangleFan) {
            drawTriangleFan(enc, firstVertex, vertexCount, 1, 0);
        } else {
            enc.drawPrimitives(primitiveType, firstVertex, vertexCount, 1, 0);
        }
    }

    @Override
    public void close() {
        materializePendingClear();
    }

    MTLPixelFormat colorAttachmentFormat() {
        return ((MetalGpuTexture) colorTexture.texture()).mtlPixelFormat();
    }

    // SODIUM-ADAPT（fix9）：Sodium 绘制段内的 blit 上传（chunkFades 首帧 flush）会经
    // endEncoder 结束 render encoder；MetalCommandEncoder.ensureActiveRenderEncoder()
    // 据此重建。colorTexture/depthTexture 是 private final——同包也需访问器。
    MetalGpuTextureView sodiumColorTextureView() {
        return (MetalGpuTextureView) colorTexture;
    }

    @Nullable
    MetalGpuTextureView sodiumDepthTextureView() {
        return depthTexture == null ? null : (MetalGpuTextureView) depthTexture;
    }

    int sodiumWidth() {
        return colorTexture.getWidth(0);
    }

    int sodiumHeight() {
        return colorTexture.getHeight(0);
    }

    MTLPixelFormat depthAttachmentFormat() {
        if (depthTexture == null) {
            return MTLPixelFormat.Invalid;
        }
        return ((MetalGpuTexture) depthTexture.texture()).mtlPixelFormat();
    }

    MTLPixelFormat stencilAttachmentFormat() {
        if (depthTexture == null) {
            return MTLPixelFormat.Invalid;
        }
        return ((MetalGpuTexture) depthTexture.texture()).mtlStencilPixelFormat();
    }

    void materializePendingClear() {
        if (clearColor != null || clearDepthEnabled) {
            renderEncoder();
        }
    }

    private MTLRenderCommandEncoder renderEncoder() {
        MetalGpuTextureView colorTextureView = (MetalGpuTextureView) colorTexture;
        MetalGpuTextureView depthTextureView = depthTexture == null ? null : (MetalGpuTextureView) depthTexture;
        boolean clearColorNow = clearColor != null;
        boolean clearDepthNow = clearDepthEnabled;
        MTLRenderCommandEncoder encoder = commandEncoder.renderCommandEncoder(
                colorTextureView,
                depthTextureView,
                colorTexture.getWidth(0),
                colorTexture.getHeight(0),
                clearColorNow,
                clearColorNow ? clearColor.x() : 0.0F,
                clearColorNow ? clearColor.y() : 0.0F,
                clearColorNow ? clearColor.z() : 0.0F,
                clearColorNow ? clearColor.w() : 0.0F,
                clearDepthNow,
                clearDepthValue
        );
        clearColor = null;
        clearDepthEnabled = false;
        return encoder;
    }

    void invalidateEncoderState() {
        pipelineDirty = true;
        scissorDirty = true;
        vertexBuffersDirty = true;
    }

    MetalTransientMemory.MappedView allocateTransient(final long size, final long alignment, final int usage) {
        return commandEncoder.transientMemory.allocateGpuMapped(size, alignment, usage, 0L, 1L);
    }

    private void pushVertexBuffers(final MTLRenderCommandEncoder enc) {
        int firstSlot = compiledPipeline.firstAvailableVertexBufferSlot();
        int count = compiledPipeline.vertexBufferCount();
        for (int slot = 0; slot < count; slot++) {
            GpuBufferSlice vertexBuffer = vertexBuffers[slot];
            if (vertexBuffer == null) {
                continue;
            }
            if (VALIDATION && vertexBuffer.buffer().isClosed()) {
                throw new IllegalStateException("Vertex buffer at slot " + slot + " has been closed");
            }

            MetalGpuBuffer nativeVertexBuffer = (MetalGpuBuffer) vertexBuffer.buffer();
            int metalSlot = firstSlot + slot;
            enc.setBuffer(nativeVertexBuffer.nativeHandle(), vertexBuffer.offset(), metalSlot, MetalCompiledRenderPipeline.STAGE_VERTEX);
        }
    }

    private void drawTriangleFan(MTLRenderCommandEncoder encoder, final int firstVertex, final int vertexCount, final int instanceCount, final int baseInstance) {
        int triangleCount = vertexCount - 2;
        int indexCount = triangleCount * 3;
        MTLIndexType fanIndexType = vertexCount - 1 <= 0xFFFF ? MTLIndexType.UInt16 : MTLIndexType.UInt32;

        try (MetalTransientMemory.MappedView mapped = commandEncoder.transientMemory.allocateGpuMapped((long) indexCount * fanIndexType.bytes, fanIndexType.bytes, GpuBuffer.USAGE_INDEX, 0L, 1L)) {
            if (fanIndexType == MTLIndexType.UInt16) {
                java.nio.ShortBuffer indices = mapped.data().asShortBuffer();
                for (int i = 0; i < triangleCount; i++) {
                    indices.put((short) 0);
                    indices.put((short) (i + 1));
                    indices.put((short) (i + 2));
                }
            } else {
                java.nio.IntBuffer indices = mapped.data().asIntBuffer();
                for (int i = 0; i < triangleCount; i++) {
                    indices.put(0);
                    indices.put(i + 1);
                    indices.put(i + 2);
                }
            }
            GpuBufferSlice slice = mapped.slice();
            encoder.drawIndexedPrimitives(MTLPrimitiveType.Triangle, indexCount, fanIndexType, ((MetalGpuBuffer) slice.buffer()).nativeHandle(), slice.offset(), Math.max(1, instanceCount), firstVertex, baseInstance);
        }
    }

    /**
     * P10 E8：扫描云 UTB 已写区域（draw indexCount×2 字节 = quadCount×12 = 4 顶点/quad×3 字节）
     * 的 cellX>>1/cellZ>>1 符号分布。quadCount 限定排除未初始化零（分配 710178B 但实际写入
     * 仅部分——E7 全量扫描被零污染）。分布单侧集中 → 数据错位；对称 → 渲染侧（cull/winding）。
     */
    private void scanCloudFaces(final int indexCount) {
        GpuBufferSlice slice = this.cloudFacesSlice;
        this.boundCloudFaces = false;
        this.cloudFacesSlice = null;
        if (slice == null || !Diagnostics.shouldRun("cloud-faces-q", 5_000L)) {
            return;
        }
        long scanBytes = Math.min(slice.length(), (long) indexCount * 2L);
        try {
            java.nio.ByteBuffer data = ((MetalGpuBuffer) slice.buffer()).sliceStorage(slice.offset(), scanBytes);
            long vertices = data.remaining() / 3L;
            long cellXNeg = 0L;
            long cellXZero = 0L;
            long cellXPos = 0L;
            long cellZNeg = 0L;
            long cellZZero = 0L;
            long cellZPos = 0L;
            for (long v = 0L; v < vertices; v++) {
                int base = (int) v * 3;
                byte cx = data.get(base);
                byte cz = data.get(base + 1);
                if (cx < 0) {
                    cellXNeg++;
                } else if (cx == 0) {
                    cellXZero++;
                } else {
                    cellXPos++;
                }
                if (cz < 0) {
                    cellZNeg++;
                } else if (cz == 0) {
                    cellZZero++;
                } else {
                    cellZPos++;
                }
            }
            DiagLog.log("[diag] cloud faces quadCount=%d verts=%d cellX=(-%d,0:%d,+)%d cellZ=(-%d,0:%d,+)%d first=%d,%d,%d",
                    indexCount / 6, vertices, cellXNeg, cellXZero, cellXPos, cellZNeg, cellZZero, cellZPos,
                    data.get(0), data.get(1), data.get(2));
        } catch (IllegalStateException e) {
            DiagLog.log("[diag] cloud faces scan failed");
        }
    }

    private void drawIndexedNative(
            final MTLRenderCommandEncoder enc,
            final MetalGpuBuffer nativeIndexBuffer,
            final int firstIndex,
            final int indexCount,
            final int baseVertex,
            final int instanceCount,
            final MTLIndexType indexType,
            final int baseInstance
    ) {
        MTLPrimitiveType primitiveType = primitiveTopology();
        long indexOffsetBytes = (long) firstIndex * indexType.bytes;
        if (primitiveType == MTLPrimitiveType.TriangleFan) {
            long fanSize = Math.multiplyExact(Math.multiplyExact((long) indexCount - 2L, 3L), Integer.BYTES);
            try (MetalTransientMemory.MappedView mapped = commandEncoder.transientMemory.allocateGpuMapped(fanSize, Integer.BYTES, GpuBuffer.USAGE_INDEX, 0L, 1L)) {
                GpuBufferSlice slice = mapped.slice();
                enc.drawIndexedPrimitivesTriangleFan(
                        nativeIndexBuffer.nativeHandle(),
                        ((MetalGpuBuffer) slice.buffer()).nativeHandle(),
                        slice.offset(),
                        indexType.value,
                        indexOffsetBytes,
                        indexCount,
                        baseVertex,
                        instanceCount,
                        baseInstance
                );
            }
        } else {
            enc.drawIndexedPrimitives(primitiveType, indexCount, indexType, nativeIndexBuffer.nativeHandle(), indexOffsetBytes, instanceCount, baseVertex, baseInstance);
        }
    }

    private void bindDrawState(final MTLRenderCommandEncoder enc) {
        if (compiledPipeline == null) {
            throw new IllegalStateException("Pipeline is missing");
        }

        if (pipelineDirty) {
            boolean useDepth = depthAttachmentFormat().value != MTLPixelFormat.Invalid.value;
            // 1.21.11 的 attachment 格式来自渲染目标纹理（懒创建 pipeline）
            MemorySegment pipelineHandle = compiledPipeline.getNativePipeline(useDepth, colorAttachmentFormat());
            if (MetalNativeBridge.isNullHandle(pipelineHandle)) {
                throw new IllegalStateException("Native pipeline is unavailable");
            }
            enc.setRenderPipelineState(pipelineHandle);
            pipelineDirty = false;

            if (useDepth) {
                MemorySegment depthState = compiledPipeline.getDepthStencilState();
                if (MetalNativeBridge.isNullHandle(depthState)) {
                    throw new IllegalStateException("Native depth state is unavailable");
                }
                enc.setDepthStencilState(depthState);
                enc.setDepthBias(
                        compiledPipeline.depthBiasConstant(),
                        compiledPipeline.depthBiasScaleFactor(),
                        0.0f
                );
            }

            enc.setFrontFacingWinding(MTLWinding.Clockwise);
            // 按管线配置剔除背面（GL Y 镜像后 front=Clockwise 与原版 CCW 语义等价）；v9 二分的强制不剔除已移除
            enc.setCullMode(compiledPipeline.cullMode());
            enc.setTriangleFillMode(compiledPipeline.fillMode());

            dirtyDescriptorMask |= compiledPipeline.allResourceMask();
        }

        if (scissorDirty) {
            pushEffectiveScissor(enc);
            scissorDirty = false;
        }

        if (vertexBuffersDirty) {
            pushVertexBuffers(enc);
            vertexBuffersDirty = false;
        }

        if (dirtyDescriptorMask != 0) {
            for (MetalCompiledRenderPipeline.ResourceBinding binding : compiledPipeline.resources()) {
                if ((dirtyDescriptorMask & (1L << binding.bindingIndex())) != 0L) {
                    pushDescriptor(enc, binding);
                }
            }
        }

        // Globals UBO 独立绑定：MC 经 RenderSystem.setGlobalSettingsUniform 传入（不走
        // setUniform），terrain.vsh 的 pos 计算（CameraBlockPos/CameraOffset）依赖它——
        // 未绑定 → 顶点变换到绝对世界坐标 → 视锥外裁剪 → 方块不可见。
        com.mojang.blaze3d.buffers.GpuBuffer globalsBuffer = MetalBackend.getGlobalSettingsBuffer();
        if (globalsBuffer != null) {
            Integer vertexGlobals = compiledPipeline.getGlobalsBinding("vertex");
            if (vertexGlobals != null) {
                enc.setBuffer(((MetalGpuBuffer) globalsBuffer).nativeHandle(), 0, vertexGlobals, MetalCompiledRenderPipeline.STAGE_VERTEX);
            }
            Integer fragmentGlobals = compiledPipeline.getGlobalsBinding("fragment");
            if (fragmentGlobals != null) {
                enc.setBuffer(((MetalGpuBuffer) globalsBuffer).nativeHandle(), 0, fragmentGlobals, MetalCompiledRenderPipeline.STAGE_FRAGMENT);
            }
        }

        dirtyDescriptorMask = 0L;
    }

    /** P37 判别：drawIndexed 统计（5s 节流——GUI 后段丢失判别）。 */
    private long guiDrawCount;
    private int guiMaxBaseVertex;

    /** P37 判别：scissor 打点（去重——同 rect 只打一次；零面积无条件）。 */
    private int lastScissorX = Integer.MIN_VALUE, lastScissorY, lastScissorW, lastScissorH;
    private boolean lastScissorEnabled;

    private MTLPrimitiveType primitiveTopology() {
        if (compiledPipeline == null) {
            throw new IllegalStateException("Pipeline is missing");
        }
        return compiledPipeline.topology();
    }

    private void pushEffectiveScissor(final MTLRenderCommandEncoder enc) {
        // 1.21.11 无 renderArea 概念：渲染区域即全纹理
        int width = colorTexture.getWidth(0);
        int height = colorTexture.getHeight(0);
        if (!scissorState.enabled()) {
            enc.setScissorRect(0L, 0L, width, height);
            diagScissor(0, 0, width, height, false);
            return;
        }

        int left = Math.max(0, scissorState.x());
        int top = Math.max(0, scissorState.y());
        int right = Math.min(width, scissorState.x() + scissorState.width());
        int bottom = Math.min(height, scissorState.y() + scissorState.height());
        if (right <= left || bottom <= top) {
            enc.setScissorRect(0, 0, 0, 0);
            diagScissor(left, top, right - left, bottom - top, true);
        } else {
            enc.setScissorRect(left, top, right - left, bottom - top);
            diagScissor(left, top, right - left, bottom - top, true);
        }
    }

    /** P37 判别：scissor 打点（同 rect 去重——零面积/新矩形才输出）。 */
    private void diagScissor(final int x, final int y, final int w, final int h, final boolean enabled) {
        if (x == lastScissorX && y == lastScissorY && w == lastScissorW && h == lastScissorH
                && enabled == lastScissorEnabled) {
            return;
        }
        lastScissorX = x;
        lastScissorY = y;
        lastScissorW = w;
        lastScissorH = h;
        lastScissorEnabled = enabled;
        if (w <= 0 || h <= 0 || Diagnostics.shouldRun("scissor-diag", 5_000L)) {
            DiagLog.logAlways("[diag] scissor enabled=%b rect=(%d,%d,%d,%d) passSize=(%d,%d)",
                    enabled, x, y, w, h, colorTexture.getWidth(0), colorTexture.getHeight(0));
        }
    }

    private void markDescriptorDirty(final String name) {
        if (compiledPipeline != null) {
            MetalCompiledRenderPipeline.ResourceBinding binding = compiledPipeline.resource(name);
            if (binding != null) {
                dirtyDescriptorMask |= 1L << binding.bindingIndex();
            }
        }
    }

    private void pushDescriptor(
            final MTLRenderCommandEncoder enc,
            final MetalCompiledRenderPipeline.ResourceBinding binding
    ) {
        if (binding.kind() == MetalCompiledRenderPipeline.ResourceKind.SAMPLED_IMAGE) {
            TextureViewAndSampler textureBinding = samplers.get(binding.name());
            if (textureBinding == null) {
                throw new IllegalStateException("Missing sampler " + binding.name());
            }

            if (VALIDATION && textureBinding.textureView().isClosed()) {
                throw new IllegalStateException("Sampler " + binding.name() + " texture view has been closed");
            }

            MetalGpuTextureView textureView = (MetalGpuTextureView) textureBinding.textureView();
            MetalGpuSampler sampler = (MetalGpuSampler) textureBinding.sampler();
            enc.setTextureAndSampler(textureView.nativeHandle(), sampler.nativeHandle(), binding.bindingIndex(), binding.stageMask());
            return;
        }

        if (binding.kind() == MetalCompiledRenderPipeline.ResourceKind.TEXEL_BUFFER) {
            pushTexelBufferDescriptor(enc, binding);
            return;
        }

        GpuBufferSlice uniformSlice = uniforms.get(binding.name());
        if (uniformSlice == null) {
            throw new IllegalStateException("Missing uniform " + binding.name());
        }
        if (VALIDATION && uniformSlice.buffer().isClosed()) {
            throw new IllegalStateException("Uniform " + binding.name() + " buffer has been closed");
        }

        MetalGpuBuffer uniformBuffer = (MetalGpuBuffer) uniformSlice.buffer();
        // P12 E12（云层判别）：云 pass 矩阵快照——投影固定错 → NDC 错 → 屏幕固定象限。
        // 与主 pass 投影对比（方块正常则主 pass 投影正确）：云 pass 矩阵不一致 → 绑定错。
        if ("Projection".equals(binding.name()) && !"Clouds".equals(this.label)
                && Diagnostics.shouldRun("main-matrix", 5_000L)) {
            // 对照：非云 pass 的 Projection（方块/实体 pass 的相机投影）
            try {
                java.nio.ByteBuffer data = uniformBuffer.sliceStorage(uniformSlice.offset(), Math.min(uniformSlice.length(), 64L));
                java.nio.FloatBuffer f = data.order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
                DiagLog.log("[diag] main %s m00=%.4f m10=%.4f m20=%.4f m30=%.4f m01=%.4f m11=%.4f",
                        binding.name(), f.get(0), f.get(1), f.get(2), f.get(3), f.get(4), f.get(5));
            } catch (IllegalStateException e) {
                DiagLog.log("[diag] main %s not CPU-readable", binding.name());
            }
        }
        // P13 修正 E12：label 恒 null（MetalDevice.useLabels() 硬编码 false）——改由
        // CloudInfo（云 pass 独有 binding）触发。核心判别：云 shader 引用 ProjMat/ModelViewMat
        // （rendertype_clouds.vsh 源码实证）——若 uniforms map 缺 Projection/DT/Fog =
        // MC 端从未 setUniform = 云读 buffer 0 默认值 = 云位置/投影错乱（象限截断根因候选）。
        if ("CloudInfo".equals(binding.name()) && Diagnostics.shouldRun("cloud-matrix", 5_000L)) {
            StringBuilder sb = new StringBuilder("[diag] cloud matrix");
            for (String name : new String[]{"Projection", "DynamicTransforms", "Fog"}) {
                GpuBufferSlice slice = uniforms.get(name);
                if (slice == null) {
                    sb.append(' ').append(name).append("=MISSING");
                    continue;
                }
                try {
                    java.nio.ByteBuffer data = ((MetalGpuBuffer) slice.buffer()).sliceStorage(slice.offset(), Math.min(slice.length(), 64L));
                    java.nio.FloatBuffer f = data.order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
                    if ("Fog".equals(name)) {
                        // E14：Fog 全字段（std140：vec4 FogColor + 6 float：
                        // EnvironmentalStart/End, RenderDistanceStart/End, SkyEnd, CloudsEnd——
                        // FogCloudsEnd 决定云 alpha 衰减距离（fsh 实证 color.a *= 1-fog(d,0,CloudsEnd)））
                        sb.append(' ').append(name).append("=(")
                                .append(String.format("%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f",
                                        f.get(0), f.get(1), f.get(2), f.get(3), f.get(4), f.get(5), f.get(6), f.get(7), f.get(8), f.get(9)))
                                .append(')');
                    } else {
                        sb.append(' ').append(name).append("=(")
                                .append(String.format("%.4f,%.4f,%.4f,%.4f,%.4f,%.4f",
                                        f.get(0), f.get(1), f.get(2), f.get(3), f.get(4), f.get(5)))
                                .append(')');
                    }
                } catch (IllegalStateException e) {
                    sb.append(' ').append(name).append("=UNREADABLE");
                }
            }
            DiagLog.log("%s", sb);
        }
        // P8 E1（云层判别）：CloudInfo UBO 的 CloudOffset 每帧值观测——"云固定世界位置 +
        // 象限限制"症状 = offset 恒 0/恒定（相机偏移未随玩家更新）。std140 布局：
        // vec4(16B) + vec3 offset(-cellX, height, -cellZ)：x 在字节 16、z 在字节 24。
        if ("CloudInfo".equals(binding.name()) && Diagnostics.shouldRun("cloud-info", 5_000L)) {
            try {
                java.nio.ByteBuffer data = uniformBuffer.sliceStorage(uniformSlice.offset(), Math.min(uniformSlice.length(), 48L));
                if (data.remaining() >= 28) {
                    DiagLog.log("[diag] cloud CloudInfo offset=(%.2f,%.2f) len=%d",
                            data.getFloat(16), data.getFloat(24), uniformSlice.length());
                }
            } catch (IllegalStateException e) {
                DiagLog.log("[diag] cloud CloudInfo not CPU-readable");
            }
        }
        enc.setBuffer(uniformBuffer.nativeHandle(), uniformSlice.offset(), binding.bindingIndex(), binding.stageMask());
    }

    private void pushTexelBufferDescriptor(final MTLRenderCommandEncoder enc, final MetalCompiledRenderPipeline.ResourceBinding binding) {
        GpuBufferSlice texelSlice = uniforms.get(binding.name());
        if (texelSlice == null) {
            throw new IllegalStateException("Missing texel buffer " + binding.name());
        }
        if (VALIDATION && texelSlice.buffer().isClosed()) {
            throw new IllegalStateException("Texel buffer " + binding.name() + " has been closed");
        }

        TextureFormat texelFormat = binding.texelBufferFormat();
        if (texelFormat == null) {
            throw new IllegalStateException("Texel buffer " + binding.name() + " is missing a format");
        }

        MetalGpuBuffer texelBuffer = (MetalGpuBuffer) texelSlice.buffer();
        if ("CloudFaces".equals(binding.name())) {
            // P10：云 pass 标记（drawIndexed 用 indexCount 限定扫描已写区域——排除 UTB
            // 未初始化零对坐标分布统计的污染，E7 曾误判）+ cull/winding 判别。
            this.boundCloudFaces = true;
            this.cloudFacesSlice = texelSlice;
            if (Diagnostics.shouldRun("cloud-cull", 5_000L)) {
                DiagLog.log("[diag] cloud pipeline cullMode=%d nocull=%b", this.compiledPipeline.cullMode().value, NO_CULL_DIAG);
            }
            // E13（云层判别）：texel view 参数观测——offset 非 0/texelCount 与 quadCount×3
            // 不匹配 → cellX/cellZ 读取错位（数据读取层最后的候选）。
            if (Diagnostics.shouldRun("cloud-texel", 5_000L)) {
                DiagLog.log("[diag] cloud texel offset=%d count=%d bytes=%d",
                        texelSlice.offset(), texelSlice.length() / texelFormat.pixelSize(), texelSlice.length());
            }
            if (NO_CULL_DIAG) {
                // E10 判别：CloudFaces 绑定后覆盖 cull（bindDrawState 的 setCullMode 在其之前执行）
                enc.setCullMode(com.metallum.client.metal.render.mtl.MTLCullMode.None);
            }
        }
        long pixelFormat = MTLPixelFormat.from(texelFormat).value;
        int pixelSize = texelFormat.pixelSize();
        long texelByteLength = texelSlice.length();
        if (texelByteLength <= 0L || texelByteLength % pixelSize != 0L) {
            throw new IllegalStateException("Texel buffer " + binding.name() + " length " + texelByteLength + " is not a valid " + texelFormat + " range");
        }
        long texelCount = texelByteLength / pixelSize;
        MemorySegment texelTexture = MetalNativeBridge.metallum_create_buffer_texture_view(
                texelBuffer.nativeHandle(),
                pixelFormat,
                texelSlice.offset(),
                texelCount,
                1L,
                texelByteLength
        );
        if (MetalNativeBridge.isNullHandle(texelTexture)) {
            throw new IllegalStateException("Failed to create Metal texel buffer texture for " + binding.name());
        }

        enc.setTexture(texelTexture, binding.bindingIndex(), binding.stageMask());
        commandEncoder.queueForDestroy(() -> MetalNativeBridge.metallum_release_object(texelTexture));
    }

    record TextureViewAndSampler(GpuTextureView textureView, GpuSampler sampler) {
    }

    private static boolean sameSlice(@Nullable final GpuBufferSlice left, @Nullable final GpuBufferSlice right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.buffer() == right.buffer()
                && left.offset() == right.offset()
                && left.length() == right.length();
    }
}
