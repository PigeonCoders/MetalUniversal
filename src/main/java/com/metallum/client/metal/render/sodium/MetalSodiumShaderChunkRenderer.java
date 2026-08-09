package com.metallum.client.metal.render.sodium;

import com.metallum.client.metal.render.MetalDevice;
import com.mojang.blaze3d.textures.GpuSampler;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkFogMode;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * SODIUM-ADAPT：Metal 版 chunk renderer（阶段 3）。
 *
 * <p>继承 {@link DefaultChunkRenderer} 而非 {@link ShaderChunkRenderer}：render 循环
 * 与全部 private static 方法（fillCommandBuffer / addSharedIndexedDrawCommands /
 * executeDrawBatch 等）零复制复用；render 里 {@code super.begin(...)} 是虚调用，
 * 动态分发到本类的 Metal 覆写。
 *
 * <p>覆写 begin/end 绕开原版的全部 GL 耦合（GlStateManager._viewport /
 * _glBindFramebuffer / GlCommandEncoderAccessor cast / GlProgram.bind——Metal 模式
 * 必崩）。原版 begin 的职责在此替换为：编译/取 pipeline（MetalSodiumShaderCache）
 * + interface 值计算（setupState）+ 注册"激活状态"供
 * MetalSodiumCommandList.beginTessellating 构建 DrawCommandList。
 *
 * <p>DefaultChunkRenderer.render 内 {@code activeProgram.getInterface()} 返回的
 * MetalSodiumShaderInterface 由假 GlProgram 容器（MetalFakeGlProgram）提供——
 * render 只调 getInterface()，不触发任何 GL 调用。
 *
 * <p>⚠️ 生命周期：cache 与 uniformBuffers 由本类持有并负责 close()（reload/销毁
 * 时随 renderer 释放——阶段 5 接线时 RenderSectionManager 重建 chunkRenderer 即触发）。
 */
@Environment(EnvType.CLIENT)
public final class MetalSodiumShaderChunkRenderer extends DefaultChunkRenderer {
    private final MetalSodiumShaderCache shaderCache;
    private final MetalSodiumUniformBuffers uniformBuffers;
    private final MetalSodiumShaderInterface shaderInterface = new MetalSodiumShaderInterface();

    private MetalSodiumCompiledPipeline activePipeline;

    public MetalSodiumShaderChunkRenderer(final MetalDevice device, final ChunkVertexType vertexType) {
        super(com.metallum.client.metal.render.sodium.MetalRenderDevice.INSTANCE, vertexType);
        this.shaderCache = new MetalSodiumShaderCache(device);
        this.uniformBuffers = new MetalSodiumUniformBuffers(device);
    }

    @Override
    protected void begin(final TerrainRenderPass pass, final FogParameters parameters, final GpuSampler terrainSampler) {
        // 与 GL 版 begin 相同的变体选择：fog 恒 SMOOTH（GL 版硬编码 ChunkFogMode.SMOOTH）
        ChunkShaderOptions options = new ChunkShaderOptions(ChunkFogMode.SMOOTH, pass, this.vertexType);

        this.activePipeline = this.shaderCache.get(options);
        if (!this.activePipeline.isValid()) {
            throw new IllegalStateException("Sodium Metal pipeline invalid for " + options);
        }

        this.shaderInterface.setupState(pass, parameters, terrainSampler);

        // 假 GlProgram 容器：render 循环经 activeProgram.getInterface() 取 interface
        this.activeProgram = new MetalFakeGlProgram(this.shaderInterface);

        // 注册激活状态：beginTessellating（每 region executeDrawBatch）时读取
        MetalSodiumCommandList.setActiveSodiumState(new MetalSodiumActiveState(
                this.activePipeline, this.shaderInterface, this.uniformBuffers
        ));
    }

    @Override
    protected void end(final TerrainRenderPass pass) {
        MetalSodiumCommandList.clearActiveSodiumState();
        this.shaderInterface.resetState();
        this.activePipeline = null;
        this.activeProgram = null;
    }

    @Override
    public void delete(final CommandList commandList) {
        // 先清激活状态再释放资源（防止销毁期残留引用被 beginTessellating 读到）
        MetalSodiumCommandList.clearActiveSodiumState();
        this.shaderCache.close();
        this.uniformBuffers.close();
        super.delete(commandList);
    }
}
