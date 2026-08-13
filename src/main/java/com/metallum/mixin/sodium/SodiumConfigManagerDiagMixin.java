package com.metallum.mixin.sodium;

import com.metallum.client.metal.render.DiagLog;
import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * P36.3（页面缺失判别）：Sodium 配置页构建失败的唯一出口是
 * ConfigManager.crashWithMessage → Minecraft.crash → System.exit——Amethyst 可能
 * 拦截 exit（进程不死但 CONFIG 构建中断 → 页面缺失、无 log4j 输出、crash 报告
 * 在 iOS 沙箱难取）。此诊断把异常打进 latestlog（System.err 经 Pojav 重定向
 * 进日志 + DiagLog 独立日志）——复现后直接看 cause 定位真凶。
 */
@Mixin(value = ConfigManager.class, remap = false)
public abstract class SodiumConfigManagerDiagMixin {
    @Inject(method = "crashWithMessage", at = @At("HEAD"), remap = false)
    private static void metallum$logConfigCrash(String message, Exception e, CallbackInfo ci) {
        System.err.println("[Metallum][diag] Sodium config crash: " + message);
        e.printStackTrace(System.err);
        DiagLog.log("[diag] Sodium config crash: %s - %s", message, e);
    }
}
