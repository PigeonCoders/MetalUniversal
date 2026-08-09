package com.metallum.client.metal.render.sodium;

/**
 * SODIUM-ADAPT：Sodium 绘制期激活状态（阶段 3）。
 *
 * <p>MetalSodiumShaderChunkRenderer.begin() 时注册、end() 时清除，供
 * MetalSodiumCommandList.beginTessellating 构建 MetalSodiumDrawCommandList 使用
 * （Sodium 的 GlTessellation 本身不携带 shader 信息——pipeline/interface/uniform
 * 来自"当前激活的 renderer 状态"，与 GL 版 activeProgram 的语义对应）。
 */
public record MetalSodiumActiveState(
        MetalSodiumCompiledPipeline pipeline,
        MetalSodiumShaderInterface shaderInterface,
        MetalSodiumUniformBuffers uniformBuffers
) {
}
