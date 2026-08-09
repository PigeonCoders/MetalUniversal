package com.metallum.mixin.sodium;

import com.metallum.client.metal.render.sodium.MetalSodiumShaderChunkRenderer;
import com.mojang.blaze3d.textures.GpuSampler;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 拦截 ShaderChunkRenderer.begin/end 方法本体（Metal 主机 + 实例是
 * MetalSodiumShaderChunkRenderer 时转走金属逻辑）。
 *
 * <p>背景（实测修正链）：DefaultChunkRenderer.render() 里的 {@code super.begin(...)}
 * 是 Java super 静态分发（字节码 invokespecial 直调 ShaderChunkRenderer.begin 实现，
 * 不经过子类覆写）——覆写 begin/end 无法生效（曾踩：GL 版 begin 的
 * ((GlTexture) target.getColorTexture()) cast 崩）。@Inject 到方法本体 HEAD 后，
 * **无论虚调用 / super 直调 / 直接调用都会命中注入**（此前 @Redirect +
 * @Shadow 方案因 Sponge 的 @Shadow 只在 target 类自身方法集查找（classNode.methods
 * 不查父类——javap 实证 findAliasedMethod），begin 声明在 ShaderChunkRenderer 而
 * mixin target 是 DefaultChunkRenderer → "was not located in the target class" 崩）。
 *
 * <p>GL 主机不 cancel 走原体（plugin 门控 .mixin.sodium. 本就只在 Metal 主机 +
 * sodium 加载时应用）；无递归（metalBegin/metalEnd 不调 ShaderChunkRenderer.begin）。
 */
@Mixin(value = ShaderChunkRenderer.class, remap = false)
public abstract class ShaderChunkRendererMixin {
    @Inject(method = "begin", at = @At("HEAD"), cancellable = true)
    private void metallum$metalBegin(
            final TerrainRenderPass pass,
            final FogParameters parameters,
            final GpuSampler terrainSampler,
            final CallbackInfo ci
    ) {
        if ((Object) this instanceof MetalSodiumShaderChunkRenderer metalRenderer) {
            metalRenderer.metalBegin(pass, parameters, terrainSampler);
            ci.cancel();
        }
    }

    @Inject(method = "end", at = @At("HEAD"), cancellable = true)
    private void metallum$metalEnd(final TerrainRenderPass pass, final CallbackInfo ci) {
        if ((Object) this instanceof MetalSodiumShaderChunkRenderer metalRenderer) {
            metalRenderer.metalEnd(pass);
            ci.cancel();
        }
    }
}
