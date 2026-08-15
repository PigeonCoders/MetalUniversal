package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLCommandQueue;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;

@Environment(EnvType.CLIENT)
public final class MetalDevice implements GpuDevice {
    private static final Pattern BLOCK_COMMENTS = Pattern.compile("(?s)/\\*.*?\\*/");
    private static final Pattern LINE_COMMENTS = Pattern.compile("(?m)//[^\\n]*");
    private static final Pattern SAMPLER_IDENT_PATTERN = Pattern.compile("\\bsampler\\b");
    private final MemorySegment metalDeviceHandle;
    private final MemorySegment metalLayer;
    private final MemorySegment cocoaView;
    private final String deviceName;
    private final MetalCommandEncoder commandEncoder;
    public final MTLCommandQueue commandQueue;
    private final Map<RenderPipeline, MetalCompiledRenderPipeline> compiledPipelines = new IdentityHashMap<>();
    private final Map<ShaderCompilationKey, String> shaderSourceCache = new HashMap<>();
    private final Map<MslFunctionKey, MemorySegment> functionCache = new HashMap<>();
    /**
     * 跨 reload 的编译产物缓存（key = pipeline 配置摘要 + 预处理后 GLSL 源文本）。
     * clearPipelineCache（资源包重载时 MC 的 ShaderManager.apply 调用）不清此缓存：
     * 源未变的 pipeline 直接复用 MTLRenderPipelineState，避免 reload 时 ~100+ 静态
     * pipeline 全量重编译（主线程 10-20 秒"卡加载页"）。key 含源文本 → 资源包
     * 改 shader 时必然重编译，无陈旧风险。
     */
    private final Map<String, MetalCompiledRenderPipeline> sourcePipelineCache = new HashMap<>();
    private static final int MAX_POOLED_BUFFER_BUCKETS = 32;
    private static final int MAX_POOLED_BUFFERS_PER_SIZE = 8;
    private final Map<Long, Deque<MemorySegment>> bufferPool = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<Long, Deque<MemorySegment>> eldest) {
            if (size() <= MAX_POOLED_BUFFER_BUCKETS) {
                return false;
            }
            for (MemorySegment handle : eldest.getValue()) {
                MetalNativeBridge.metallum_release_object(handle);
            }
            return true;
        }
    };
    @Nullable
    private final ShaderSource defaultShaderSource;

    MetalDevice(
            final MemorySegment metalDeviceHandle,
            final MemorySegment metalLayer,
            final String deviceName,
            final MemorySegment cocoaView,
            @Nullable final ShaderSource defaultShaderSource
    ) {
        this.metalDeviceHandle = metalDeviceHandle;
        this.metalLayer = metalLayer;
        this.cocoaView = cocoaView;
        this.deviceName = deviceName;
        this.defaultShaderSource = defaultShaderSource;
        MetalNativeBridge.metallum_set_debug_labels_enabled(false);
        this.commandQueue = MTLCommandQueue.create(metalDeviceHandle);
        MetalNativeBridge.metallum_init_pipelines(metalDeviceHandle);
        this.commandEncoder = new MetalCommandEncoder(this);
    }

    @Override
    public @NonNull CommandEncoder createCommandEncoder() {
        return this.commandEncoder;
    }

    @Override
    public @NonNull GpuSampler createSampler(
            final @NonNull AddressMode addressModeU,
            final @NonNull AddressMode addressModeV,
            final @NonNull FilterMode minFilter,
            final @NonNull FilterMode magFilter,
            final int maxAnisotropy,
            final @NonNull OptionalDouble maxLod
    ) {
        return new MetalGpuSampler(this, addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod);
    }

    @Override
    public @NonNull GpuTexture createTexture(
            @Nullable final Supplier<String> label,
            @GpuTexture.Usage final int usage,
            final @NonNull TextureFormat format,
            final int width,
            final int height,
            final int depthOrLayers,
            final int mipLevels
    ) {
        return this.createTexture(this.resolveDebugLabel(label), usage, format, width, height, depthOrLayers, mipLevels);
    }

    @Override
    public @NonNull GpuTexture createTexture(
            @Nullable final String label,
            @GpuTexture.Usage final int usage,
            final @NonNull TextureFormat format,
            final int width,
            final int height,
            final int depthOrLayers,
            final int mipLevels
    ) {
        return new MetalGpuTexture(this, usage, label == null ? "" : label, format, width, height, depthOrLayers, mipLevels);
    }

    @Override
    public @NonNull GpuTextureView createTextureView(final @NonNull GpuTexture texture) {
        return this.createTextureView(texture, 0, texture.getMipLevels());
    }

    @Override
    public @NonNull GpuTextureView createTextureView(final @NonNull GpuTexture texture, final int baseMipLevel, final int mipLevels) {
        return new MetalGpuTextureView(texture, baseMipLevel, mipLevels);
    }

    @Override
    public @NonNull GpuBuffer createBuffer(@Nullable final Supplier<String> label, @GpuBuffer.Usage final int usage, final long size) {
        if (size <= 0L) {
            throw new IllegalArgumentException("Metal buffer size must be > 0 (got " + size + ")");
        }
        sampleCreateBufferStack();
        return new MetalGpuBuffer(this, usage, size);
    }

    @Override
    public @NonNull GpuBuffer createBuffer(@Nullable final Supplier<String> label, @GpuBuffer.Usage final int usage, final ByteBuffer data) {
        if (data == null || data.remaining() <= 0) {
            throw new IllegalArgumentException("Cannot create buffer from empty ByteBuffer");
        }
        MetalGpuBuffer buffer = (MetalGpuBuffer) this.createBuffer(label, usage | GpuBuffer.USAGE_COPY_DST, data.remaining());
        this.commandEncoder.writeToBuffer(buffer.slice(), data.duplicate());
        return buffer;
    }

    @Override
    public @NonNull List<String> getLastDebugMessages() {
        return List.of();
    }

    @Override
    public boolean isDebuggingEnabled() {
        return false;
    }

    /**
     * createBuffer 调用栈采样（前 5 次，节流）：定位高频 GPU 缓冲创建的 MC 侧来源
     * （如 CompiledSectionMesh.uploadMeshLayer 的每帧重建 / 实体与 UI 上传路径）。
     */
    private static final java.util.concurrent.atomic.AtomicInteger CREATE_BUFFER_STACK_SAMPLES =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * 运行期标志：首次 present（第一帧渲染提交）后置位。
     * 启动期（Minecraft.<init> → RenderSystem.initRenderer）的一次性缓冲会消耗前几次采样，
     * 故采样只在运行期生效（AGENTS §11 已知缺陷修复）。
     */
    private volatile boolean runtimeStarted;

    void markRuntimeStarted() {
        this.runtimeStarted = true;
    }

    private void sampleCreateBufferStack() {
        if (!this.runtimeStarted || CREATE_BUFFER_STACK_SAMPLES.getAndIncrement() >= 5) {
            return;
        }
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        StringBuilder sb = new StringBuilder("createBuffer stack sample #")
                .append(CREATE_BUFFER_STACK_SAMPLES.get()).append(':');
        int frames = 0;
        for (int i = 3; i < stack.length && frames < 12; i++, frames++) {
            sb.append("\n  ").append(stack[i]);
        }
        DiagLog.log("%s", sb);
    }

    boolean useLabels() {
        return false;
    }

    @Override
    public @NonNull CompiledRenderPipeline precompilePipeline(final @NonNull RenderPipeline pipeline, final @Nullable ShaderSource shaderSource) {
        // 惰性占位：MC 的 ShaderManager.apply 只检查 isValid()（CompilationCache
        // 不缓存 precompile 结果——渲染路径走 getOrCompilePipeline，已实证）。
        // 不在此编译的原因：precompile 传入的是 ShaderManager 源（MC
        // GlslPreprocessor.process 展开），与本项目渲染路径使用的直读源
        // （readShaderFromResources + expandMojImports）展开结果不同——生成的
        // MSL 的 vertex attribute 布局与 MTLVertexDescriptor 不兼容，AGX
        // makeRenderPipelineState 报 "Compiler encountered an internal error"
        // （gui_textured 启动崩溃）。且 reload 时全量编译 ~100+ 静态 pipeline
        // 造成主线程 10-20 秒"卡加载页"。零编译占位同时根治两者。
        return PipelinePlaceholder.INSTANCE;
    }

    /**
     * 编译（带跨 reload 缓存）。key 含预处理后源文本——源变必重编译；源不变
     * （纯纹理资源包 reload）命中缓存秒过。
     */
    private MetalCompiledRenderPipeline compilePipelineCached(final RenderPipeline pipeline, final ShaderSource source) {
        String key = pipelineCacheKey(pipeline, source);
        MetalCompiledRenderPipeline cached = this.sourcePipelineCache.get(key);
        if (cached != null) {
            return cached;
        }
        MetalCompiledRenderPipeline compiled = MetalCrossShaderCompiler.compile(this, pipeline, source);
        this.sourcePipelineCache.putIfAbsent(key, compiled);
        return compiled;
    }

    private String pipelineCacheKey(final RenderPipeline pipeline, final ShaderSource source) {
        String vertex = getOrCompileShaderSource(pipeline.getVertexShader(), ShaderType.VERTEX, pipeline.getShaderDefines(), source);
        String fragment = getOrCompileShaderSource(pipeline.getFragmentShader(), ShaderType.FRAGMENT, pipeline.getShaderDefines(), source);
        if (vertex == null || fragment == null) {
            return pipeline.getLocation() + "|(no-source)";
        }
        StringBuilder key = new StringBuilder(vertex.length() + fragment.length() + 128);
        key.append(pipeline.getLocation()).append('|');
        key.append(pipeline.getVertexFormatMode()).append('|');
        key.append(pipeline.getDepthTestFunction()).append('|');
        key.append(pipeline.isCull()).append('|');
        key.append(pipeline.isWriteColor()).append('|');
        key.append(pipeline.isWriteAlpha()).append('|');
        key.append(pipeline.isWriteDepth()).append('|');
        key.append(pipeline.getBlendFunction()).append('|');
        key.append(vertex).append('\n');
        key.append(fragment);
        return key.toString();
    }

    @Override
    public void clearPipelineCache() {
        this.waitForSubmittedGpuWork();
        this.compiledPipelines.values().forEach(MetalCompiledRenderPipeline::close);
        this.compiledPipelines.clear();
        this.shaderSourceCache.clear();
        for (MemorySegment function : this.functionCache.values()) {
            if (!MetalNativeBridge.isNullHandle(function)) {
                MetalNativeBridge.metallum_release_object(function);
            }
        }
        this.functionCache.clear();
    }

    @Override
    public void close() {
        this.waitForSubmittedGpuWork();
        this.commandEncoder.close();
        this.clearPipelineCache();
        this.drainBufferPool();
        try {
            MetalNativeBridge.metallum_NSView_clearLayer(this.cocoaView);
        } catch (Throwable ignored) {
        }
        this.commandQueue.close();
        MetalNativeBridge.metallum_release_object(this.metalDeviceHandle);
    }

    @Override
    public @NonNull String getImplementationInformation() {
        return this.deviceName + " (" + this.getVersion() + ")";
    }

    @Override
    public @NonNull String getVendor() {
        return "Apple";
    }

    @Override
    public @NonNull String getBackendName() {
        return "Metal";
    }

    @Override
    public @NonNull String getVersion() {
        String osVersion = System.getProperty("os.version", "").trim();
        String platformName = MetalNativeBridge.isIOS() ? "iOS" : "macOS";
        return platformName + " " + osVersion;
    }

    @Override
    public @NonNull String getRenderer() {
        return this.deviceName;
    }

    @Override
    public int getMaxTextureSize() {
        return 16384;
    }

    @Override
    public int getUniformOffsetAlignment() {
        return 256;
    }

    @Override
    public @NonNull List<String> getEnabledExtensions() {
        return List.of("CAMetalLayer", "MTLDevice");
    }

    @Override
    public int getMaxSupportedAnisotropy() {
        return 16;
    }

    public MemorySegment metalDeviceHandle() {
        return this.metalDeviceHandle;
    }

    MemorySegment metalLayer() {
        return this.metalLayer;
    }

    long maxBufferAllocationSize() {
        return MetalNativeBridge.MTLDevice_maxMemoryAllocationSize(metalDeviceHandle);
    }

    void waitForSubmittedGpuWork() {
        this.commandEncoder.waitForSubmittedGpuWork();
    }

    void queueResourceRelease(final MemorySegment handle) {
        this.commandEncoder.queueForDestroy(() -> MetalNativeBridge.metallum_release_object(handle));
    }

    MemorySegment tryAcquirePooledBuffer(final long size, final long resourceOptions) {
        long key = composePoolKey(size, resourceOptions);
        Deque<MemorySegment> bucket = bufferPool.get(key);
        if (bucket != null && !bucket.isEmpty()) {
            Stats.recordPoolHit();
            return bucket.pop();
        }
        Stats.recordPoolMiss();
        return MemorySegment.NULL;
    }

    void queueBufferRelease(final MemorySegment handle, final long size, final long resourceOptions) {
        this.commandEncoder.queueForDestroy(() -> {
            long key = composePoolKey(size, resourceOptions);
            Deque<MemorySegment> bucket = bufferPool.computeIfAbsent(key, k -> new ArrayDeque<>());
            if (bucket.size() < MAX_POOLED_BUFFERS_PER_SIZE) {
                bucket.push(handle);
                Stats.recordPoolReturn();
            } else {
                MetalNativeBridge.metallum_release_object(handle);
            }
        });
    }

    static long composePoolKey(final long size, final long resourceOptions) {
        return (size << 12) | (resourceOptions & 0xFFFL);
    }

    private void drainBufferPool() {
        for (Deque<MemorySegment> bucket : bufferPool.values()) {
            for (MemorySegment handle : bucket) {
                MetalNativeBridge.metallum_release_object(handle);
            }
        }
        bufferPool.clear();
    }

    MetalCompiledRenderPipeline getOrCompilePipeline(final RenderPipeline pipeline) {
        return this.compiledPipelines.computeIfAbsent(pipeline, p -> {
            try {
                return this.compilePipelineCached(p, this.defaultShaderSource);
            } catch (Throwable t) {
                // 运行期编译失败（坏 shader 资源包）——留明确日志后重抛。
                // 正常启动/正常资源包永不触发（precompile 已是零编译占位，
                // 运行期用直读源——与改动前一致且已被验证可渲染）。
                Metallum.LOGGER.error("[Metal] Failed to compile pipeline {}: {}", pipeline.getLocation(), t.toString());
                throw t instanceof RuntimeException re ? re : new IllegalStateException(t);
            }
        });
    }

    /**
     * 1.21.11 的 ShaderSource 为接口（get(id, type) 返回 GLSL 源），编译结果以
     * 字符串缓存，实际 GLSL → SPIR-V → MSL 转换在 MetalCrossShaderCompiler 内完成。
     *
     * <p>ShaderSource 实例来自 Minecraft 构造内的 capturing lambda（initRenderer
     * 参数），其 get() 对未预编译的 pipeline 可能返回 null——此时回退到资源直读
     * （assets/minecraft/shaders/<path>.vsh/.fsh）。
     */
    String getOrCompileShaderSource(final Identifier id, final ShaderType type, final ShaderDefines defines, final ShaderSource shaderSource) {
        ShaderCompilationKey key = new ShaderCompilationKey(id, type, defines);
        return this.shaderSourceCache.computeIfAbsent(key, k -> {
            String source = shaderSource != null ? shaderSource.get(k.id(), k.type()) : null;
            if (source == null) {
                source = readShaderFromResources(k.id(), k.type());
            }
            if (source == null) {
                return null;
            }
            return prepareShaderSource(source, k.defines());
        });
    }

    /**
     * 从资源包直读 pipeline shader 源：1.21.11 的 shader 文件位于
     * assets/minecraft/shaders/core/*.vsh/.fsh（pipeline.getVertexShader() 的
     * Identifier 如 minecraft:core/gui 拼接 shaders/<path>.vsh/.fsh 即命中）。
     */
    @Nullable
    private static String readShaderFromResources(final Identifier id, final ShaderType type) {
        String suffix = type == ShaderType.VERTEX ? ".vsh" : ".fsh";
        // 1.21.11 的 Identifier 无 of(ns, path) 两参工厂，用 parse 拼接完整 "ns:path"
        Identifier resourceId = Identifier.parse(id.getNamespace() + ":shaders/" + id.getPath() + suffix);
        try {
            String source = org.apache.commons.io.IOUtils.toString(
                    net.minecraft.client.Minecraft.getInstance().getResourceManager().openAsReader(resourceId)
            );
            // MC 的 ShaderSource.get 会在内部展开 #moj_import；我们直读原始文件，
            // 需自行展开（import 资源位于 shaders/include/<path>.glsl）
            return expandMojImports(source, new HashSet<>());
        } catch (java.io.IOException | IllegalStateException e) {
            return null;
        }
    }

    private static final java.util.regex.Pattern MOJ_IMPORT_PATTERN =
            java.util.regex.Pattern.compile("#moj_import\\s*<([a-z0-9_]+):([a-z0-9_./]+)>");

    /**
     * 展开 #moj_import 指令：读取 ns:shaders/include/<path>.glsl，删除其 #version 行
     * （防多版本冲突）后内联，递归展开（include 内可能再 import），visited 防环。
     */
    private static String expandMojImports(final String source, final Set<String> visited) {
        StringBuilder out = new StringBuilder(source.length() + 512);
        for (String line : source.split("\n", -1)) {
            java.util.regex.Matcher matcher = MOJ_IMPORT_PATTERN.matcher(line.trim());
            if (!matcher.matches()) {
                out.append(line).append('\n');
                continue;
            }
            String namespace = matcher.group(1);
            String path = matcher.group(2);
            String key = namespace + ":" + path;
            if (!visited.add(key)) {
                continue;
            }
            try {
                Identifier includeId = Identifier.parse(namespace + ":shaders/include/" + path + ".glsl");
                String include = org.apache.commons.io.IOUtils.toString(
                        net.minecraft.client.Minecraft.getInstance().getResourceManager().openAsReader(includeId)
                );
                // 删除 include 自身的 #version 指令（版本由主源声明）
                include = include.replaceFirst("(?m)^\\s*#version\\s+\\d+.*$", "");
                out.append(expandMojImports(include, visited));
            } catch (java.io.IOException | IllegalStateException e) {
                Metallum.LOGGER.warn("[metallum] Failed to expand moj_import <{}>: {}", key, e.toString());
            } finally {
                visited.remove(key);
            }
        }
        return out.toString();
    }

    private static String prepareShaderSource(final String source, final ShaderDefines defines) {
        String stripped = BLOCK_COMMENTS.matcher(source).replaceAll("");
        stripped = LINE_COMMENTS.matcher(stripped).replaceAll("").stripLeading();
        stripped = GlslPreprocessor.injectDefines(stripped, defines);
        // MSL 中 sampler 是内置类型名：GLSL 的 sampler 标识符（非保留字，\b 边界不会命中
        // sampler2D/samplerCube，Sampler0 大写不受影响）统一改名，避免 SPIRV-Cross 生成
        // texture2d<float> sampler 声明遮蔽类型（1.21.11 terrain.fsh 的 sampleNearest）
        return SAMPLER_IDENT_PATTERN.matcher(stripped).replaceAll("samplerTex");
    }

    public MemorySegment getOrCompileFunction(final String msl, final String entryPoint) {
        return this.functionCache.computeIfAbsent(
                new MslFunctionKey(msl, entryPoint),
                key -> MetalNativeBridge.metallum_create_shader_function(this.metalDeviceHandle, key.msl(), key.entryPoint())
        );
    }

    private record ShaderCompilationKey(Identifier id, ShaderType type, ShaderDefines defines) {
    }

    private record MslFunctionKey(String msl, String entryPoint) {
    }

    /**
     * 编译失败占位——isValid=false。MC 的 ShaderManager.apply 据此收集失败
     * pipeline 并抛明确错误（显示资源包错误界面），而非半状态渲染花屏。
     */
    static final class PipelinePlaceholder implements CompiledRenderPipeline {
        static final PipelinePlaceholder INSTANCE = new PipelinePlaceholder();

        private PipelinePlaceholder() {
        }

        @Override
        public boolean isValid() {
            return true;
        }
    }

    @Nullable
    private String resolveDebugLabel(@Nullable final Supplier<String> label) {
        return this.useLabels() && label != null ? label.get() : null;
    }
}
