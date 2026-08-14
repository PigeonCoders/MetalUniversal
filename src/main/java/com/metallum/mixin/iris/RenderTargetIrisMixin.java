package com.metallum.mixin.iris;

import com.mojang.blaze3d.pipeline.RenderTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Iris 适配阶段 1：MC RenderTarget 的 iris$bindFramebuffer 在 Metal 下走 Iris
 * 的 MixinRenderTarget 会 cast GlTexture（Metal 的 colorTexture 是
 * MetalGpuTextureView → CCE）。Metallum mixin 以更高 priority（2000 &gt; Iris
 * 默认 1000）@Overwrite 方法体为 no-op——Metal 无 GL FBO 概念，绑定语义由
 * MetalRenderPass 的 attachment 管理（真正语义属阶段 3）。
 *
 * <p>注意：不能 implements RenderTargetInterface + @Override——mixin 0.8.7 的
 * 接口 merge 路径忽略 @Overwrite 注解，两 mixin 同接口同方法直接
 * InvalidMixinException。标准形式 = 本 mixin 只 @Overwrite（接口实现仍由
 * Iris 的 MixinRenderTarget 声明）。
 *
 * <p>应用顺序：priority 低者先应用（Iris 1000 先注入方法，metallum 2000
 * 后 overwrite 替换）。编译期 AP warning（目标方法运行期才存在）属预期。
 *
 * <p>调用方：FinalPassRenderer（有 pack 时）——无 pack 不触发，防御性覆盖。
 */
@Mixin(value = RenderTarget.class, priority = 2000)
public abstract class RenderTargetIrisMixin {
    @Overwrite(remap = false)
    public void iris$bindFramebuffer() {
    }
}
