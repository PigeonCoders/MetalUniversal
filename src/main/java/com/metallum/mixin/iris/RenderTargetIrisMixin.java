package com.metallum.mixin.iris;

import net.irisshaders.iris.mixinterface.RenderTargetInterface;
import com.mojang.blaze3d.pipeline.RenderTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Iris 适配阶段 1：MC RenderTarget 的 iris$bindFramebuffer 在 Metal 下走 Iris
 * 的 MixinRenderTarget 会 cast GlTexture（Metal 的 colorTexture 是
 * MetalGpuTextureView → CCE）。Metallum mixin 以更高 priority（2000 &gt; Iris
 * 默认 1000）@Overwrite 为 no-op——Metal 无 GL FBO 概念，绑定语义由
 * MetalRenderPass 的 attachment 管理（真正语义属阶段 3）。
 *
 * <p>@Overwrite 必需：两个 mixin 同时向 class_276 注入同名接口方法，后应用者
 * （高 priority）必须以 overwrite 语义替换，否则 InvalidMixinException。
 *
 * <p>调用方：FinalPassRenderer（有 pack 时）——无 pack 不触发，防御性覆盖。
 */
@Mixin(value = RenderTarget.class, priority = 2000)
public abstract class RenderTargetIrisMixin implements RenderTargetInterface {
    @Override
    @Overwrite(remap = false)
    public void iris$bindFramebuffer() {
    }
}
