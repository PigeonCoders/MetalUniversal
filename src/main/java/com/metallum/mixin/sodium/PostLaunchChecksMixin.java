package com.metallum.mixin.sodium;

import net.caffeinemc.mods.sodium.client.compatibility.checks.PostLaunchChecks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 拦截 Sodium 的 PojavLauncher 检测（PostLaunchChecks.isUsingPojavLauncher）——
 * iOS 实测触发（RuntimeException: "It appears that you are using PojavLauncher,
 * which is not supported when using Sodium" 拒绝启动）。
 *
 * <p>Sodium 的检测针对 Android Pojav（POJAV_RENDERER env / java.library.path 与
 * user.home 匹配 /data/user/[0-9]+/net.kdt.pojavlaunch），iOS Amethyst 宿主
 * 的环境/路径状态可能误命中——计划文件「阶段 5 遗留验证点 #1」实测暴露。
 * 直接放行（false）；驱动校验链（GraphicsDriverChecks/NvidiaWorkarounds）保留
 * 原样——vendor 假值 "Apple" 已使其短路，无副作用。
 */
@Mixin(value = PostLaunchChecks.class, remap = false)
public abstract class PostLaunchChecksMixin {
    @Inject(method = "isUsingPojavLauncher", at = @At("HEAD"), cancellable = true)
    private static void metallum$allowPojav(final CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }
}
