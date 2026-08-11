package com.metallum.client.metal.render.sodium;

import com.metallum.client.metal.render.MetalCrossShaderCompiler;
import com.metallum.client.metal.render.MetalDevice;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderConstants;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderLoader;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderParser;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import net.minecraft.resources.Identifier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.HashMap;
import java.util.Map;

/**
 * Sodium terrain shader 编译缓存：按 ChunkShaderOptions（fog 恒 SMOOTH ×
 * USE_FRAGMENT_DISCARD 有/无 = 2 变体）编译 GLSL → MSL → MetalSodiumCompiledPipeline。
 *
 * <p>GLSL 源读取与 #import 展开复用 Sodium 的纯 Java 类（ShaderLoader /
 * ShaderParser——从 sodium jar classpath 资源读取，无 GL 调用）。
 */
@Environment(EnvType.CLIENT)
public final class MetalSodiumShaderCache {
    /**
     * 判别实验（纯色）：-Dmetallum.solidcolor=true 开启（fragment 强制纯红 alpha=1，
     * 跳过采样/光照）。水面透明度判别：纯红下透明缺口仍在 → 几何/段问题；恢复 →
     * 纹理/alpha/discard 链。默认 false（真实渲染）。
     */
    private static final boolean SOLID_COLOR_DIAG =
            Boolean.parseBoolean(System.getProperty("metallum.solidcolor", "false"));
    private static final String SHADER_PATH = "blocks/block_layer_opaque";

    private static final Map<String, VertexFormatElement.Type> ATTRIBUTE_TYPES = Map.of(
            "a_Position", VertexFormatElement.Type.UINT,
            "a_TexCoord", VertexFormatElement.Type.UINT,
            "a_LightAndData", VertexFormatElement.Type.UINT
    );
    private static final Map<String, Integer> ATTRIBUTE_LOCATIONS = Map.of(
            "a_Position", 0,
            "a_Color", 1,
            "a_TexCoord", 2,
            "a_LightAndData", 3
    );

    private final MetalDevice device;
    private final Map<ChunkShaderOptions, MetalSodiumCompiledPipeline> pipelines = new HashMap<>();

    public MetalSodiumShaderCache(final MetalDevice device) {
        this.device = device;
    }

    public MetalSodiumCompiledPipeline get(final ChunkShaderOptions options) {
        return this.pipelines.computeIfAbsent(options, this::compile);
    }

    private MetalSodiumCompiledPipeline compile(final ChunkShaderOptions options) {
        ShaderConstants constants = options.constants();

        ShaderParser.ParsedShader vertex = ShaderParser.parseShader(
                ShaderLoader.getShaderSource(Identifier.fromNamespaceAndPath("sodium", SHADER_PATH + ".vsh")),
                constants
        );
        ShaderParser.ParsedShader fragment = ShaderParser.parseShader(
                ShaderLoader.getShaderSource(Identifier.fromNamespaceAndPath("sodium", SHADER_PATH + ".fsh")),
                constants
        );

        // 判别实验（纯色）：fragment main 开头强制输出纯红并 return——跳过全部采样/光照。
        // iOS 复测判读：侧面消失仍在（红色侧面也消失）→ 几何覆盖空洞（meshing/顶点/索引）；
        // 红色侧面正常显示 → 问题在纹理/光照链（采样/过滤/光贴图）。
        String fragmentSrc = fragment.src();
        if (SOLID_COLOR_DIAG) {
            fragmentSrc = fragmentSrc.replace(
                    "void main() {",
                    "void main() { fragColor = vec4(1.0, 0.0, 0.0, 1.0); return;"
            );
        }

        MetalCrossShaderCompiler.CompiledGlsl compiled = MetalCrossShaderCompiler.compileGlsl(
                vertex.src(),
                fragmentSrc,
                ATTRIBUTE_TYPES,
                ATTRIBUTE_LOCATIONS,
                "sodium/" + SHADER_PATH
        );

        Map<String, Integer> vertexBuffers = MetalSodiumCompiledPipeline.extractBuffers(compiled.vertexMsl(), "ChunkData");
        Map<String, Integer> fragmentBuffers = MetalSodiumCompiledPipeline.extractBuffers(compiled.fragmentMsl(), "ChunkData");
        Map<String, Integer> vertexTextures = MetalSodiumCompiledPipeline.extractTextures(compiled.vertexMsl());
        Map<String, Integer> fragmentTextures = MetalSodiumCompiledPipeline.extractTextures(compiled.fragmentMsl());

        return new MetalSodiumCompiledPipeline(
                this.device,
                options.pass().getPipeline(),
                compiled,
                options.vertexType().getVertexFormat(),
                vertexBuffers,
                fragmentBuffers,
                vertexTextures,
                fragmentTextures
        );
    }

    public void close() {
        for (MetalSodiumCompiledPipeline pipeline : this.pipelines.values()) {
            pipeline.close();
        }
        this.pipelines.clear();
    }
}
