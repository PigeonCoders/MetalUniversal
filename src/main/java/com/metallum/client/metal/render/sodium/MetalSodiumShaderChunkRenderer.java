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
 * executeDrawBatch 等）零复制复用。
 *
 * <p>⚠️ 重要（实测修正）：DefaultChunkRenderer.render 里的 {@code super.begin(...)}
 * 是 **Java super 静态分发**（字节码 invokespecial 直调 ShaderChunkRenderer.begin 实现，
 * 不经过子类覆写）——本类的 begin/end 覆写不会被 render() 调用。真正拦截点在
 * ShaderChunkRendererMixin：@Inject 到 ShaderChunkRenderer.begin/end 方法本体 HEAD
 * （super 直调同样命中），Metal 主机经 instanceof 转调本类的 public metalBegin/metalEnd。
 *
 * <p>metalBegin/metalEnd 公开（非 protected）：mixin 跨包调用需要（begin/end 覆写
 * 调它们作为虚调用路径的双保险）。原版 begin 的职责在此替换为：编译/取 pipeline
 * （MetalSodiumShaderCache）+ interface 值计算（setupState）+ 注册"激活状态"供
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
        // 激活状态统一走 Sodium 的 RenderDevice.INSTANCE（= GLRenderDevice，其
        // getCapabilities/createCommandList 已被 GLRenderDeviceMixin Metal 化）：
        // Sodium 的 enterManagedCode 激活的是它，若用 MetalRenderDevice.INSTANCE
        // 会因 checkDeviceActive 未激活而抛错（接口 mixin 不可行后改从实现类入手）。
        super(net.caffeinemc.mods.sodium.client.gl.device.RenderDevice.INSTANCE, vertexType);
        this.shaderCache = new MetalSodiumShaderCache(device);
        this.uniformBuffers = new MetalSodiumUniformBuffers(device);
    }

    @Override
    protected void begin(final TerrainRenderPass pass, final FogParameters parameters, final GpuSampler terrainSampler) {
        this.metalBegin(pass, parameters, terrainSampler);
    }

    /** 金属 begin 逻辑（public：ShaderChunkRendererMixin 跨包经 instanceof 转调）。 */
    public void metalBegin(final TerrainRenderPass pass, final FogParameters parameters, final GpuSampler terrainSampler) {
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
        this.metalEnd(pass);
    }

    /** 金属 end 逻辑（public：ShaderChunkRendererMixin 跨包经 instanceof 转调）。 */
    public void metalEnd(final TerrainRenderPass pass) {
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
