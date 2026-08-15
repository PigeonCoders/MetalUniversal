package com.metallum.mixin.render;

import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 修 MC 1.21.11 上游 bug：TextureAtlas.createTexture 每次资源包 reload 替换
 * mipViews 数组但不 close 旧 view（Metal 后端引用计数永不归还 → 旧 atlas
 * GPU 内存跨 reload 泄漏 → 多次切换后纹理创建失败 → 品红）。
 *
 * <p>@Redirect PUTFIELD mipViews：mixin 0.8.7 对 PUTFIELD 的 @Redirect
 * handler 必须返回 void（写入值不可替换），在写入新数组的同时 close 旧的
 * （view.close → texture.removeView → 计数归零 → 3 帧延迟释放）。
 *
 * <p>remap=false（与项目其余 MC 目标 mixin 一致——运行时为 mojmap 命名的
 * MC，loom refmap 无官方映射则 AP 报错）。
 */
@Mixin(TextureAtlas.class)
public abstract class TextureAtlasMipViewFixMixin {
    @Shadow(remap = false)
    private GpuTextureView[] mipViews;

    @Redirect(
            method = "createTexture",
            remap = false,
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/texture/TextureAtlas;mipViews:[Lcom/mojang/blaze3d/textures/GpuTextureView;")
    )
    private void metallum$closeOldMipViews(final GpuTextureView[] fresh) {
        GpuTextureView[] old = this.mipViews;
        if (old != null) {
            for (GpuTextureView view : old) {
                if (view != null) {
                    view.close();
                }
            }
        }
    }
}
