package com.metallum.mixin.render;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * P29-3：单机自动截图短路——STBImageResize NoSuchMethodError（Amethyst 的
 * liblwjgl_stb.dylib 与 MC 的 lwjgl-stb 3.3.3 Java 绑定不匹配）不是
 * IOException → GameRenderer.takeAutoScreenshot 的 catch 捕不到 →
 * hasWorldScreenshot 永不置位 → tryTakeScreenshotIfNeeded 每 1 秒无限重试
 * （每次全屏 GPU 读回 + 日志噪音——整个会话持续）。
 *
 * <p>HEAD 置 hasWorldScreenshot=true → tryTakeScreenshotIfNeeded 内
 * !hasWorldScreenshot 为 false → 直接 return（自动截图彻底停用）。
 * 自动截图仅用于单机世界列表缩略图——禁用无玩法影响（mipmap 质量不受影响，
 * fix12 的 Metal sampler 无 mip 是独立问题）。
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererAutoScreenshotMixin {

    @Shadow
    private boolean hasWorldScreenshot;

    @Inject(method = "tryTakeScreenshotIfNeeded", remap = false, at = @At("HEAD"))
    private void metallum$disableAutoScreenshot(CallbackInfo ci) {
        this.hasWorldScreenshot = true;
    }
}
