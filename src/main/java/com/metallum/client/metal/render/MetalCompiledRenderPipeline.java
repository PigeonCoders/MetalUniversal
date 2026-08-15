package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.*;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Environment(EnvType.CLIENT)
final class MetalCompiledRenderPipeline implements CompiledRenderPipeline, AutoCloseable {
    enum ResourceKind {
        UNIFORM_BUFFER,
        SAMPLED_IMAGE,
        TEXEL_BUFFER
    }

    static final int STAGE_VERTEX = 1;
    static final int STAGE_FRAGMENT = 2;
    static final int STAGE_ALL = STAGE_VERTEX | STAGE_FRAGMENT;

    record ResourceBinding(ResourceKind kind, String name, int bindingIndex, int stageMask,
                           @Nullable TextureFormat texelBufferFormat) {
    }

    private final List<ResourceBinding> resources;
    private final Map<String, ResourceBinding> resourcesByName;
    private final long allResourceMask;
    private final int firstAvailableVertexBufferSlot;
    private final MTLCullMode cullMode;
    private final MTLTriangleFillMode fillMode;
    private final float depthBiasScaleFactor;
    private final float depthBiasConstant;
    private final MTLPrimitiveType topology;
    private final int vertexBufferCount;
    private final MTLCompareFunction depthCompareOp;
    private final int depthWrite;

    private final MemorySegment depthStencilState;
    // 按 (useDepth, colorFormat) 惰性缓存 MTLPSO——旧双字段只按 useDepth 区分，
    // 同一 pipeline 跨 colorFormat 复用错误 PSO（主目标切格式后渲染错）。
    // ConcurrentHashMap 读免锁（保留原 volatile 快路径语义），创建在
    // synchronized 内去重；值可为 NULL segment（创建失败），调用方守卫。
    private final Map<MTLPixelFormat, MemorySegment> withDepthPipelines = new ConcurrentHashMap<>();
    private final Map<MTLPixelFormat, MemorySegment> withoutDepthPipelines = new ConcurrentHashMap<>();
    private final MetalDevice device;
    private final RenderPipeline pipeline;
    private final MemorySegment vertexFunction;
    private final MemorySegment fragmentFunction;
    private final Set<String> integerInputs;
    private final java.util.Map<String, Integer> globalsBindings;

    MetalCompiledRenderPipeline(
            final MetalDevice device,
            final RenderPipeline info,
            final String vertexMsl,
            final String fragmentMsl,
            final String vertexEntryPoint,
            final String fragmentEntryPoint,
            final List<ResourceBinding> resources,
            final Set<String> integerInputs,
            final java.util.Map<String, Integer> globalsBindings
    ) {
        this.globalsBindings = globalsBindings;
        this.resources = resources;
        this.resourcesByName = resources.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(ResourceBinding::name, binding -> binding));

        int maxBindingIndex = -1;
        long resourceMask = 0L;
        for (ResourceBinding binding : resources) {
            maxBindingIndex = Math.max(maxBindingIndex, binding.bindingIndex());
            resourceMask |= 1L << binding.bindingIndex();
        }
        if (maxBindingIndex >= Long.SIZE) {
            throw new IllegalStateException("Pipeline " + info.getLocation() + " has binding index " + maxBindingIndex + ", limit is " + (Long.SIZE - 1));
        }
        this.allResourceMask = resourceMask;

        this.firstAvailableVertexBufferSlot = firstAvailableVertexBufferSlot(resources);
        this.cullMode = info.isCull() ? MTLCullMode.Back : MTLCullMode.None;
        this.fillMode = info.getPolygonMode() == PolygonMode.WIREFRAME ? MTLTriangleFillMode.Lines : MTLTriangleFillMode.Fill;
        this.topology = MTLPrimitiveType.from(info.getVertexFormatMode());
        // 1.21.11 单 VertexFormat（26.2 的 getVertexFormatBindings 为多绑定列表）
        this.vertexBufferCount = 1;
        this.device = device;
        this.pipeline = info;
        this.integerInputs = integerInputs;

        // 1.21.11 无 DepthStencilState 对象：深度状态由 DepthTestFunction + writeDepth 布尔推导
        DepthTestFunction depthTest = info.getDepthTestFunction();
        boolean hasDepthTest = depthTest != DepthTestFunction.NO_DEPTH_TEST;
        MTLCompareFunction depthCompareOp = MTLCompareFunction.from(depthTest);
        int depthWrite = info.isWriteDepth() ? 1 : 0;
        this.depthCompareOp = depthCompareOp;
        this.depthWrite = depthWrite;
        this.depthBiasScaleFactor = info.getDepthBiasScaleFactor();
        this.depthBiasConstant = info.getDepthBiasConstant();

        this.depthStencilState = MetalNativeBridge.MTLDevice_makeDepthStencilState(
                device.metalDeviceHandle(),
                depthCompareOp,
                depthWrite
        );

        this.vertexFunction = device.getOrCompileFunction(vertexMsl, vertexEntryPoint);
        this.fragmentFunction = device.getOrCompileFunction(fragmentMsl, fragmentEntryPoint);
    }

    /**
     * 1.21.11 的 RenderPipeline 无 ColorTargetState：attachment 格式由 RenderPass
     * 从渲染目标纹理推导，pipeline 状态因此按需创建并缓存（每种 colorFormat 一份）。
     */
    MemorySegment getNativePipeline(final boolean useDepth, final MTLPixelFormat colorFormat) {
        Map<MTLPixelFormat, MemorySegment> cache = useDepth ? this.withDepthPipelines : this.withoutDepthPipelines;
        MemorySegment cached = cache.get(colorFormat);
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            cached = cache.get(colorFormat);
            if (cached == null) {
                cached = createPipeline(colorFormat, useDepth ? MTLPixelFormat.Depth32Float : MTLPixelFormat.Invalid);
                if (cached != null) {
                    cache.put(colorFormat, cached);
                }
            }
            return cached;
        }
    }

    private MemorySegment createPipeline(
            final MTLPixelFormat colorFormat,
            final MTLPixelFormat depthFormat
    ) {
        // 1.21.11 无 ColorTargetState：blend/writeMask 直接来自 RenderPipeline
        long writeMask = MTLColorWriteMask.from(this.pipeline.isWriteColor(), this.pipeline.isWriteAlpha());

        try (MTLVertexDescriptor vertexDescriptor = buildVertexDescriptor(this.pipeline, this.firstAvailableVertexBufferSlot)) {
            return MetalPipelineSupport.makeRenderPipelineState(
                    this.device,
                    this.vertexFunction,
                    this.fragmentFunction,
                    vertexDescriptor,
                    colorFormat,
                    depthFormat,
                    this.pipeline.getBlendFunction(),
                    writeMask,
                    this.pipeline.getLocation().toString()
            );
        }
    }

    @Override
    public boolean isValid() {
        return !MetalNativeBridge.isNullHandle(this.vertexFunction) && !MetalNativeBridge.isNullHandle(this.fragmentFunction);
    }

    List<ResourceBinding> resources() {
        return this.resources;
    }

    long allResourceMask() {
        return this.allResourceMask;
    }

    @Nullable
    ResourceBinding resource(final String name) {
        return this.resourcesByName.get(name);
    }

    int firstAvailableVertexBufferSlot() {
        return this.firstAvailableVertexBufferSlot;
    }

    float depthBiasScaleFactor() {
        return this.depthBiasScaleFactor;
    }

    float depthBiasConstant() {
        return this.depthBiasConstant;
    }

    MTLCompareFunction depthCompareOp() {
        return this.depthCompareOp;
    }

    int depthWrite() {
        return this.depthWrite;
    }

    MemorySegment getDepthStencilState() {
        return this.depthStencilState;
    }

    MTLCullMode cullMode() {
        return this.cullMode;
    }

    MTLTriangleFillMode fillMode() {
        return this.fillMode;
    }

    MTLPrimitiveType topology() {
        return this.topology;
    }

    /**
     * 原始顶点拓扑模式（1.21.11 的 VertexFormat.Mode）——诊断日志用。
     */
    com.mojang.blaze3d.vertex.VertexFormat.Mode getVertexFormatMode() {
        return this.pipeline.getVertexFormatMode();
    }

    com.mojang.blaze3d.vertex.VertexFormat getVertexFormat() {
        return this.pipeline.getVertexFormat();
    }

    /**
     * Globals UBO 的 buffer index（按 stage：vertex/fragment）——独立绑定路径，
     * 由 MetalRenderPass.bindDrawState 每帧绑定 MetalBackend 捕获的 buffer。
     */
    @Nullable
    Integer getGlobalsBinding(final String stage) {
        return this.globalsBindings.get(stage);
    }

    int vertexBufferCount() {
        return this.vertexBufferCount;
    }

    private MTLVertexDescriptor buildVertexDescriptor(
            final RenderPipeline pipeline,
            final int firstMetalVertexBufferSlot
    ) {
        VertexFormat binding = pipeline.getVertexFormat();
        MTLVertexDescriptor vertexDesc = new MTLVertexDescriptor();
        if (binding.getElements().isEmpty()) {
            return vertexDesc;
        }

        int metalSlot = firstMetalVertexBufferSlot;
        long stride = binding.getVertexSize();
        // 1.21.11 无实例步进（getStepRate 不存在）：统一 PerVertex
        vertexDesc.setLayout(metalSlot, stride, MTLVertexStepFunction.PerVertex, 1);

        long attrIndex = 0;
        for (VertexFormatElement element : binding.getElements()) {
            // shader 声明为 int/uint 的输入（如 ivec2 UV2）用非 normalized 格式，
            // 否则 Metal 报 "Cannot convert attribute from *Normalized to int2 or uint2"
            String attributeName = binding.getElementName(element);
            MTLVertexFormat format = integerInputs.contains(attributeName)
                    ? MTLVertexFormat.fromInteger(element.type(), element.count())
                    : MTLVertexFormat.from(element.type(), element.count());
            if (format == MTLVertexFormat.Invalid) {
                throw new IllegalStateException("Unsupported vertex attribute format: " + element.type() + "x" + element.count());
            }
            vertexDesc.setAttribute(attrIndex, format.value, binding.getOffset(element), metalSlot);
            attrIndex++;
        }

        return vertexDesc;
    }

    private static int firstAvailableVertexBufferSlot(final List<ResourceBinding> resources) {
        int maxVertexBufferBinding = -1;
        for (ResourceBinding resource : resources) {
            if (resource.kind() == ResourceKind.UNIFORM_BUFFER && (resource.stageMask() & STAGE_VERTEX) != 0) {
                maxVertexBufferBinding = Math.max(maxVertexBufferBinding, resource.bindingIndex());
            }
        }
        return maxVertexBufferBinding + 1;
    }

    @Override
    public void close() {
        releaseAll(this.withDepthPipelines);
        releaseAll(this.withoutDepthPipelines);
    }

    private static void releaseAll(final Map<MTLPixelFormat, MemorySegment> cache) {
        for (MemorySegment handle : cache.values()) {
            if (handle != null && !MetalNativeBridge.isNullHandle(handle)) {
                MetalNativeBridge.metallum_release_object(handle);
            }
        }
        // 幂等化：clear——compiledPipelines 与 sourcePipelineCache 共享对象，
        // MetalDevice.close 时两处都 close（双 release 会崩溃）。
        cache.clear();
    }
}
