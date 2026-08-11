package com.metallum.client.metal.render.sodium;

import com.metallum.client.metal.render.MetalCrossShaderCompiler;
import com.metallum.client.metal.render.MetalDevice;
import com.metallum.client.metal.render.MetalPipelineSupport;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLCullMode;
import com.metallum.client.metal.render.mtl.MTLCompareFunction;
import com.metallum.client.metal.render.mtl.MTLColorWriteMask;
import com.metallum.client.metal.render.mtl.MTLPixelFormat;
import com.metallum.client.metal.render.mtl.MTLPrimitiveType;
import com.metallum.client.metal.render.mtl.MTLTriangleFillMode;
import com.metallum.client.metal.render.mtl.MTLVertexDescriptor;
import com.metallum.client.metal.render.mtl.MTLVertexFormat;
import com.metallum.client.metal.render.mtl.MTLVertexStepFunction;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.caffeinemc.mods.sodium.client.gl.attribute.GlVertexAttribute;
import net.caffeinemc.mods.sodium.client.gl.attribute.GlVertexAttributeBinding;
import net.caffeinemc.mods.sodium.client.gl.attribute.GlVertexFormat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sodium terrain shader 的 Metal 编译管线（MetalCompiledRenderPipeline 的 Sodium 版）。
 *
 * <p>差异：顶点描述符按 Sodium 的 GlVertexFormat（CompactChunkVertex，stride 20）构建；
 * 资源绑定不经 BindingPlan，而是从 MSL 文本按名提取（shaderc auto_bind 编号，
 * 实测 Sodium 0.8.13 普通 uniform 各自独立 constant T&amp; buffer 参数、ChunkData
 * block 保留类型名、texture/sampler 同 index 配对）；状态源 = TerrainRenderPass 的
 * MC RenderPipeline（cull/depth/blend 与 MetalCompiledRenderPipeline 同源读取）。
 *
 * <p>MTLPSO 按 (stateSource pipeline, colorFormat, useDepth) 惰性缓存。
 */
@Environment(EnvType.CLIENT)
public final class MetalSodiumCompiledPipeline implements AutoCloseable {
    public enum ResourceKind {
        UNIFORM_BUFFER,
        SAMPLED_IMAGE
    }

    public record ResourceBinding(ResourceKind kind, String name, int bindingIndex, int stageMask) {
    }

    public static final int STAGE_VERTEX = 1;
    public static final int STAGE_FRAGMENT = 2;

    /** 普通 uniform / UBO 的 buffer 参数：constant <type>& <var> [[buffer(N)]] */
    private static final Pattern BUFFER_PARAM_PATTERN =
            Pattern.compile("constant\\s+([\\w<>]+)&\\s+(\\w+)\\s+\\[\\[buffer\\((\\d+)\\)\\]\\]");
    /** 纹理参数：texture2d<float> <var> [[texture(N)]] */
    private static final Pattern TEXTURE_PARAM_PATTERN =
            Pattern.compile("texture2d<float>\\s+(\\w+)\\s+\\[\\[texture\\((\\d+)\\)\\]\\]");

    private final MetalDevice device;
    private final RenderPipeline stateSource;
    private final String vertexMsl;
    private final String fragmentMsl;
    private final GlVertexFormat vertexFormat;
    private final int firstVertexBufferSlot;
    private final List<ResourceBinding> resources;
    private final Map<String, ResourceBinding> resourcesByName;
    private final long allResourceMask;
    private final MTLCullMode cullMode;
    private final MTLTriangleFillMode fillMode;
    private final float depthBiasScaleFactor;
    private final float depthBiasConstant;
    private final MTLPrimitiveType topology;
    private final MTLCompareFunction depthCompareOp;
    private final int depthWrite;
    private final MemorySegment depthStencilState;
    private final MemorySegment vertexFunction;
    private final MemorySegment fragmentFunction;
    private final Map<MtlPipelineKey, MemorySegment> pipelineCache = new LinkedHashMap<>();

    public MetalSodiumCompiledPipeline(
            final MetalDevice device,
            final RenderPipeline stateSource,
            final MetalCrossShaderCompiler.CompiledGlsl compiled,
            final GlVertexFormat vertexFormat,
            final Map<String, Integer> vertexBufferBindings,
            final Map<String, Integer> fragmentBufferBindings,
            final Map<String, Integer> vertexTextureBindings,
            final Map<String, Integer> fragmentTextureBindings
    ) {
        this.device = device;
        this.stateSource = stateSource;
        this.vertexMsl = compiled.vertexMsl();
        this.fragmentMsl = compiled.fragmentMsl();
        this.vertexFormat = vertexFormat;

        this.resources = new ArrayList<>();
        addResources(vertexBufferBindings, STAGE_VERTEX, ResourceKind.UNIFORM_BUFFER);
        addResources(fragmentBufferBindings, STAGE_FRAGMENT, ResourceKind.UNIFORM_BUFFER);
        addResources(vertexTextureBindings, STAGE_VERTEX, ResourceKind.SAMPLED_IMAGE);
        addResources(fragmentTextureBindings, STAGE_FRAGMENT, ResourceKind.SAMPLED_IMAGE);
        Map<String, ResourceBinding> byName = new LinkedHashMap<>();
        int maxBinding = -1;
        long mask = 0L;
        for (ResourceBinding binding : this.resources) {
            byName.put(binding.name(), binding);
            maxBinding = Math.max(maxBinding, binding.bindingIndex());
            mask |= 1L << binding.bindingIndex();
        }
        this.resourcesByName = Map.copyOf(byName);
        this.allResourceMask = mask;

        // vertex stage 的 buffer 参数占据 [0..n]，顶点 buffer 从 n+1 起
        int maxVertexBuffer = -1;
        for (Map.Entry<String, Integer> e : vertexBufferBindings.entrySet()) {
            maxVertexBuffer = Math.max(maxVertexBuffer, e.getValue());
        }
        this.firstVertexBufferSlot = maxVertexBuffer + 1;

        // 实验 1 结论：cullMode=None 无变化（cull/winding 排除）——恢复 stateSource 驱动
        this.cullMode = stateSource.isCull() ? MTLCullMode.Back : MTLCullMode.None;
        this.fillMode = stateSource.getPolygonMode() == PolygonMode.WIREFRAME ? MTLTriangleFillMode.Lines : MTLTriangleFillMode.Fill;
        this.topology = MTLPrimitiveType.from(stateSource.getVertexFormatMode());
        this.depthBiasScaleFactor = stateSource.getDepthBiasScaleFactor();
        this.depthBiasConstant = stateSource.getDepthBiasConstant();
        DepthTestFunction depthTest = stateSource.getDepthTestFunction();
        // 判别实验（fix14）：LEQUAL → LESS——相邻 section 重合面的深度竞争（ULP 边界
        // 帧间翻转 → 侧面交替/消失）；LESS 使「相等」恒不通过（先画者胜，稳定一侧）。
        // 若 iOS 侧面消失/闪烁停止 → 确认深度竞争；仍闪 → 排除深度（恢复 LEQUAL）。
        this.depthCompareOp = MTLCompareFunction.Less; // MTLCompareFunction.from(depthTest);
        this.depthWrite = stateSource.isWriteDepth() ? 1 : 0;
        this.depthStencilState = MetalNativeBridge.MTLDevice_makeDepthStencilState(
                device.metalDeviceHandle(), this.depthCompareOp, this.depthWrite
        );

        this.vertexFunction = device.getOrCompileFunction(compiled.vertexMsl(), compiled.vertexEntryPoint());
        this.fragmentFunction = device.getOrCompileFunction(compiled.fragmentMsl(), compiled.fragmentEntryPoint());

        // 预构建顶点描述符并校验（layout 尺寸/属性格式），失败即抛——编译期暴露而非首帧
        try (MTLVertexDescriptor ignored = buildVertexDescriptor(vertexFormat, this.firstVertexBufferSlot)) {
        }
    }

    private void addResources(final Map<String, Integer> bindings, final int stageMask, final ResourceKind kind) {
        for (Map.Entry<String, Integer> e : bindings.entrySet()) {
            this.resources.add(new ResourceBinding(kind, e.getKey(), e.getValue(), stageMask));
        }
    }

    // ---- MSL 提取（静态工具，供编译缓存使用） ----

    public static Map<String, Integer> extractBuffers(final String msl, final String uboTypeName) {
        Map<String, Integer> out = new LinkedHashMap<>();
        Matcher matcher = BUFFER_PARAM_PATTERN.matcher(msl);
        while (matcher.find()) {
            String typeName = matcher.group(1);
            String varName = matcher.group(2);
            int index = Integer.parseInt(matcher.group(3));
            if (varName.startsWith("_")) {
                // SPIRV-Cross 重命名的 block 变量（实测 ChunkData → _197）：按类型名特判
                if (uboTypeName.equals(typeName)) {
                    out.put(uboTypeName, index);
                }
            } else {
                out.put(varName, index);
            }
        }
        return out;
    }

    public static Map<String, Integer> extractTextures(final String msl) {
        Map<String, Integer> out = new LinkedHashMap<>();
        Matcher matcher = TEXTURE_PARAM_PATTERN.matcher(msl);
        while (matcher.find()) {
            out.put(matcher.group(1), Integer.parseInt(matcher.group(2)));
        }
        return out;
    }

    // ---- 顶点描述符（Sodium GlVertexFormat → MTLVertexDescriptor） ----

    private MTLVertexDescriptor buildVertexDescriptor(final GlVertexFormat vertexFormat, final int metalSlot) {
        MTLVertexDescriptor vertexDesc = new MTLVertexDescriptor();
        long stride = vertexFormat.getStride();
        vertexDesc.setLayout(metalSlot, stride, MTLVertexStepFunction.PerVertex, 1);

        for (GlVertexAttributeBinding binding : vertexFormat.getShaderBindings()) {
            int attrIndex = binding.getIndex();
            GlVertexAttribute attribute = binding;
            // attribute.getFormat() 即 GlVertexAttributeFormat.typeId（GL 常量）
            VertexFormatElement.Type type = SodiumGlAttributeFormatMapper.toMinecraftType(attribute.getFormat());
            // 整型属性（uvec2/uvec4 输入）必须非 normalized：统一按 intType 判定
            // （SPIRV-Cross 对 int/uint 输入默认生成 uint 形态，descriptor 需匹配）
            MTLVertexFormat format = attribute.isIntType()
                    ? MTLVertexFormat.fromInteger(type, attribute.getCount())
                    : MTLVertexFormat.from(type, attribute.getCount());
            if (format == MTLVertexFormat.Invalid) {
                throw new IllegalStateException("Unsupported Sodium vertex attribute format: " + type + "x" + attribute.getCount());
            }
            vertexDesc.setAttribute(attrIndex, format.value, attribute.getPointer(), metalSlot);
        }
        return vertexDesc;
    }

    private static String attributeNameForIndex(final int index) {
        return switch (index) {
            case 0 -> "a_Position";
            case 1 -> "a_Color";
            case 2 -> "a_TexCoord";
            case 3 -> "a_LightAndData";
            default -> "a_Attr" + index;
        };
    }

    // ---- MTLPSO（按 stateSource × colorFormat × useDepth 惰性缓存） ----

    @Nullable
    public MemorySegment getNativePipeline(final boolean useDepth, final MTLPixelFormat colorFormat) {
        MtlPipelineKey key = new MtlPipelineKey(this.stateSource, colorFormat, useDepth);
        MemorySegment cached = this.pipelineCache.get(key);
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            cached = this.pipelineCache.get(key);
            if (cached == null) {
                MTLPixelFormat depthFormat = useDepth ? MTLPixelFormat.Depth32Float : MTLPixelFormat.Invalid;
                long writeMask = MTLColorWriteMask.from(this.stateSource.isWriteColor(), this.stateSource.isWriteAlpha());
                try (MTLVertexDescriptor vertexDescriptor = this.buildVertexDescriptorForPipeline()) {
                    cached = MetalPipelineSupport.makeRenderPipelineState(
                            this.device,
                            this.vertexFunction,
                            this.fragmentFunction,
                            vertexDescriptor,
                            colorFormat,
                            depthFormat,
                            this.stateSource.getBlendFunction(),
                            writeMask,
                            this.stateSource.getLocation().toString()
                    );
                }
                this.pipelineCache.put(key, cached);
            }
            return cached;
        }
    }

    /** 顶点描述符构建（Sodium 布局：stride 20，slot = firstAvailableVertexBufferSlot）。 */
    private MTLVertexDescriptor buildVertexDescriptorForPipeline() {
        return this.buildVertexDescriptor(this.vertexFormat, this.firstVertexBufferSlot);
    }

    public List<ResourceBinding> resources() {
        return this.resources;
    }

    public long allResourceMask() {
        return this.allResourceMask;
    }

    @Nullable
    public ResourceBinding resource(final String name) {
        return this.resourcesByName.get(name);
    }

    public int firstAvailableVertexBufferSlot() {
        return this.firstVertexBufferSlot;
    }

    public int vertexBufferCount() {
        return 1;
    }

    public MTLCullMode cullMode() {
        return this.cullMode;
    }

    public MTLTriangleFillMode fillMode() {
        return this.fillMode;
    }

    public MTLPrimitiveType topology() {
        return this.topology;
    }

    public float depthBiasScaleFactor() {
        return this.depthBiasScaleFactor;
    }

    public float depthBiasConstant() {
        return this.depthBiasConstant;
    }

    public MTLCompareFunction depthCompareOp() {
        return this.depthCompareOp;
    }

    public int depthWrite() {
        return this.depthWrite;
    }

    public MemorySegment getDepthStencilState() {
        return this.depthStencilState;
    }

    public Set<String> vertexInputNames() {
        return Set.of("a_Position", "a_Color", "a_TexCoord", "a_LightAndData");
    }

    public boolean isValid() {
        return !MetalNativeBridge.isNullHandle(this.vertexFunction) && !MetalNativeBridge.isNullHandle(this.fragmentFunction);
    }

    @Override
    public void close() {
        for (MemorySegment pipeline : this.pipelineCache.values()) {
            if (!MetalNativeBridge.isNullHandle(pipeline)) {
                MetalNativeBridge.metallum_release_object(pipeline);
            }
        }
        this.pipelineCache.clear();
    }

    private record MtlPipelineKey(RenderPipeline pipeline, MTLPixelFormat colorFormat, boolean useDepth) {
    }
}
