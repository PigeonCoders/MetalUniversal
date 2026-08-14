package com.metallum.mixin.iris;

import net.irisshaders.iris.gl.GLDebug;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Iris 适配阶段 1：GLDebug.debugState 的初始化依赖 Iris 的 MixinRenderSystem
 * 对 RenderSystem.initRenderer 的 RETURN 注入——而 Metallum 的
 * PreferredGraphicsApiMixin 在 HEAD 处 cancel 了 initRenderer（Metal 后端
 * 替换），RETURN 注入点不执行 → reloadDebugState() 从未调用 → debugState
 * 恒 null。Iris 的 MixinGui.handleHudHidingScreens 无条件调
 * GLDebug.pushGroup(1000, "GUI")（每帧游戏内 GUI 渲染）→ 世界加载第一帧
 * HUD 即 NPE 崩溃。
 *
 * <p>修复：三个调试组方法 HEAD 守卫——首次调用尝试 GLDebug.reloadDebugState()
 * （public 方法，无需 @Shadow 私有 debugState 字段——避开私有嵌套类型
 * DebugState 的编译期可见性墙）：
 * <ul>
 *   <li>成功：debugState = UnsupportedDebugState（无 KHR_debug 时的 no-op
 *       实现），放行——Iris 语义完整；</li>
 *   <li>失败（Metal 主机无 GL context → GL.getCapabilities() 抛
 *       IllegalStateException；或 IrisConfig 未就绪）：catch → cancel（no-op）——
 *       零 GL 调用，且失败状态缓存避免每帧重复抛异常。</li>
 * </ul>
 *
 * <p>require = 0：Iris 未来重构（方法改名/移除）时静默失效，不崩（保险丝）。
 */
@Mixin(value = GLDebug.class, remap = false)
public abstract class GLDebugMixin {
    @Unique
    private static boolean metallum$debugStateReady = false;
    @Unique
    private static boolean metallum$debugStateFailed = false;

    @Unique
    private static boolean metallum$isUsable() {
        if (metallum$debugStateFailed) {
            return false;
        }
        if (!metallum$debugStateReady) {
            try {
                GLDebug.reloadDebugState();
                metallum$debugStateReady = true;
            } catch (Throwable t) {
                metallum$debugStateFailed = true;
                return false;
            }
        }
        return true;
    }

    @Inject(method = "pushGroup", at = @At("HEAD"), cancellable = true, require = 0)
    private static void metallum$guardPushGroup(CallbackInfo ci) {
        if (!metallum$isUsable()) {
            ci.cancel();
        }
    }

    @Inject(method = "popGroup", at = @At("HEAD"), cancellable = true, require = 0)
    private static void metallum$guardPopGroup(CallbackInfo ci) {
        if (!metallum$isUsable()) {
            ci.cancel();
        }
    }

    @Inject(method = "nameObject", at = @At("HEAD"), cancellable = true, require = 0)
    private static void metallum$guardNameObject(CallbackInfo ci) {
        if (!metallum$isUsable()) {
            ci.cancel();
        }
    }
}
