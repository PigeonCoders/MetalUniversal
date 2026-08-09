package com.metallum.mixin.sodium;

import com.mojang.blaze3d.textures.GpuSampler;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * DefaultChunkRenderer.render() 里的 super.begin(...)/super.end(...) 是 Java
 * super 静态分发（字节码 invokespecial 直调 ShaderChunkRenderer 的实现，
 * 不经过子类覆写）——MetalSodiumShaderChunkRenderer 覆写的 begin/end 从未
 * 被调用，GL 版 begin 照常执行（MetalGpuTexture → GlTexture cast 崩，iOS
 * 实测 ClassCastException at ShaderChunkRenderer.begin:91）。
 *
 * <p>本 mixin 把两处 super 调用重定向为 this.begin()/this.end() 虚调用：
 * <ul>
 *   <li>Metal 主机：this 是 MetalSodiumShaderChunkRenderer → 走 Metal 版 begin/end</li>
 *   <li>GL 主机：this 是原版 DefaultChunkRenderer → 走 ShaderChunkRenderer 原实现（回归天然正确）</li>
 * </ul>
 * render() 其余逻辑（sharedIndexBuffer / private static 批处理方法 /
 * executeDrawBatch → beginTessellating）都在原方法体内正常执行，无需复制。
 */
@Mixin(value = DefaultChunkRenderer.class, remap = false)
public abstract class DefaultChunkRendererMixin {
    @Shadow(remap = false)
    protected abstract void begin(TerrainRenderPass pass, FogParameters parameters, GpuSampler terrainSampler);

    @Shadow(remap = false)
    protected abstract void end(TerrainRenderPass pass);

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/ShaderChunkRenderer;begin(Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;Lnet/caffeinemc/mods/sodium/client/util/FogParameters;Lcom/mojang/blaze3d/textures/GpuSampler;)V",
                    remap = false
            ),
            remap = false
    )
    private void metallum$redirectBegin(final TerrainRenderPass pass, final FogParameters parameters, final GpuSampler terrainSampler) {
        this.begin(pass, parameters, terrainSampler);
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/ShaderChunkRenderer;end(Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)V",
                    remap = false
            ),
            remap = false
    )
    private void metallum$redirectEnd(final TerrainRenderPass pass) {
        this.end(pass);
    }
}
