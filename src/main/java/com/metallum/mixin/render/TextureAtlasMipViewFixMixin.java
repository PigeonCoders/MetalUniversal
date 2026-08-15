package com.metallum.mixin.render;

import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 修 MC 1.21.11 上游 bug：TextureAtlas.createTexture 每次资源包 reload 替换
 * mipViews 数组但不 close 旧 view（Metal 后端引用计数永不归还 → 旧 atlas
 * GPU 内存跨 reload 泄漏 → 多次切换后纹理创建失败 → 品红）。
 *
 * <p>两段式 @Inject（@Redirect 对数组字段 PUTFIELD 不被 mixin 0.8.7 支持）：
 * HEAD 捕获旧 mipViews 引用 → RETURN 时 close 旧的（view.close →
 * texture.removeView → 计数归零 → 3 帧延迟释放）。RETURN 时 createTexture
 * 已完成 this.close()（旧 texture/textureView 已关）与 mipViews 数组替换，
 * 旧 mipViews 不再被引用，close 安全。
 *
 * <p>remap=false（与项目其余 MC 目标 mixin 一致——运行时为 mojmap 命名）。
 */
@Mixin(TextureAtlas.class)
public abstract class TextureAtlasMipViewFixMixin {
    @Shadow(remap = false)
    private GpuTextureView[] mipViews;

    @Unique
    private GpuTextureView[] metallum$oldMipViews;

    @Inject(method = "createTexture", at = @At("HEAD"), remap = false)
    private void metallum$captureOldMipViews(final CallbackInfo ci) {
        this.metallum$oldMipViews = this.mipViews;
    }

    @Inject(method = "createTexture", at = @At("RETURN"), remap = false)
    private void metallum$closeOldMipViews(final CallbackInfo ci) {
        GpuTextureView[] old = this.metallum$oldMipViews;
        this.metallum$oldMipViews = null;
        if (old != null) {
            for (GpuTextureView view : old) {
                if (view != null) {
                    view.close();
                }
            }
        }
    }
}
