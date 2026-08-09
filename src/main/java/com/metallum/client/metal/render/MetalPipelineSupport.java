package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLBlendFactor;
import com.metallum.client.metal.render.mtl.MTLBlendOperation;
import com.metallum.client.metal.render.mtl.MTLColorWriteMask;
import com.metallum.client.metal.render.mtl.MTLPixelFormat;
import com.metallum.client.metal.render.mtl.MTLRenderPipelineDescriptor;
import com.metallum.client.metal.render.mtl.MTLVertexDescriptor;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Environment(EnvType.CLIENT)
public final class MetalPipelineSupport {
    private MetalPipelineSupport() {
    }

    public static boolean sameHandle(@Nullable final MemorySegment left, @Nullable final MemorySegment right) {
        long leftValue = left == null ? 0L : left.address();
        long rightValue = right == null ? 0L : right.address();
        return leftValue == rightValue;
    }

    static List<String> vertexAttributeNames(final RenderPipeline pipeline) {
        List<String> names = new ArrayList<>();
        // 1.21.11 单 VertexFormat（26.2 的 getVertexFormatBindings 为多绑定列表）
        VertexFormat binding = pipeline.getVertexFormat();
        for (VertexFormatElement element : binding.getElements()) {
            names.add(binding.getElementName(element));
        }
        return names;
    }

    /**
     * 构建 MTLRenderPipelineState（公共入口：MetalCompiledRenderPipeline 与
     * Sodium 适配层（MetalSodiumCompiledPipeline）共用）。
     *
     * <p>1.21.11 无 ColorTargetState：blend/writeMask 直接来自 RenderPipeline，
     * attachment 格式由渲染目标纹理推导（按 color/depth 格式组合惰性缓存由调用方负责）。
     */
    @Nullable
    public static MemorySegment makeRenderPipelineState(
            final MetalDevice device,
            final MemorySegment vertexFunction,
            final MemorySegment fragmentFunction,
            final MTLVertexDescriptor vertexDescriptor,
            final MTLPixelFormat colorFormat,
            final MTLPixelFormat depthFormat,
            final Optional<BlendFunction> blendFunction,
            final long writeMask,
            final String label
    ) {
        if (MetalNativeBridge.isNullHandle(vertexFunction) || MetalNativeBridge.isNullHandle(fragmentFunction)) {
            return MemorySegment.NULL;
        }

        try (MTLRenderPipelineDescriptor pipelineDesc = new MTLRenderPipelineDescriptor()) {
            pipelineDesc.setCompiledFunctions(vertexFunction, fragmentFunction);
            pipelineDesc.setVertexDescriptor(vertexDescriptor);
            pipelineDesc.setAttachmentFormats(colorFormat, depthFormat, MTLPixelFormat.Invalid);

            if (blendFunction.isPresent()) {
                var function = blendFunction.get();
                pipelineDesc.setBlendState(
                        MTLBlendFactor.from(function.sourceColor()),
                        MTLBlendFactor.from(function.destColor()),
                        MTLBlendOperation.from(),
                        MTLBlendFactor.from(function.sourceAlpha()),
                        MTLBlendFactor.from(function.destAlpha()),
                        MTLBlendOperation.from(),
                        writeMask
                );
            } else {
                pipelineDesc.disableBlending(writeMask);
            }

            MemorySegment pipeline = MetalNativeBridge.metallum_MTLDevice_makeRenderPipelineState(
                    device.metalDeviceHandle(),
                    pipelineDesc.handle()
            );
            if (MetalNativeBridge.isNullHandle(pipeline)) {
                Metallum.LOGGER.error("[metallum] Pipeline {} failed to build with depth format {}", label, depthFormat);
            }
            return pipeline;
        }
    }
}
