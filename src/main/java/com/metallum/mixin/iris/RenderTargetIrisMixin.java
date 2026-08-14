package com.metallum.mixin.iris;

import net.irisshaders.iris.mixinterface.RenderTargetInterface;
import com.mojang.blaze3d.pipeline.RenderTarget;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Iris 适配阶段 1：MC RenderTarget 的 iris$bindFramebuffer 在 Metal 下走 Iris
 * 的 MixinRenderTarget 会 cast GlTexture（Metal 的 colorTexture 是
 * MetalGpuTextureView → CCE）。Metallum mixin 以更高 priority（2000 &gt; Iris
 * 默认 1000）覆盖为 no-op——Metal 无 GL FBO 概念，绑定语义由 MetalRenderPass
 * 的 attachment 管理（真正语义属阶段 3）。
 *
 * <p>调用方：FinalPassRenderer（有 pack 时）——无 pack 不触发，防御性覆盖。
 */
@Mixin(value = RenderTarget.class, priority = 2000)
public abstract class RenderTargetIrisMixin implements RenderTargetInterface {
    @Override
    public void iris$bindFramebuffer() {
    }
}
