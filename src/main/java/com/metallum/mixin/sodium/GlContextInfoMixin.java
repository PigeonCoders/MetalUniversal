package com.metallum.mixin.sodium;

import com.metallum.client.metal.render.MetalBackend;
import net.caffeinemc.mods.sodium.client.compatibility.environment.GlContextInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 拦截 GlContextInfo.create()（GL_VENDOR/RENDERER/VERSION 三次 glGetString，
 * 无 GL context 必崩）。返回假 record，vendor 必须选 "Apple"——
 * GraphicsAdapterVendor.fromContext 只匹配 NVIDIA/Intel/AMD 五个精确字符串，
 * "Apple" → UNKNOWN → GraphicsDriverChecks / NvidiaWorkarounds / AmdWorkarounds
 * 全链短路（源码实证）；ModuleScanner 非 Windows 返回空列表同样短路。
 *
 * <p>触发链：Sodium workarounds/context_creation/RenderSystemMixin 在
 * initRenderer RETURN 处调用 create()——本地 RenderSystemDeviceMixin 在
 * HEAD cancel 后 RETURN 注入点仍会执行（cancellable 也是正常返回路径）。
 */
@Mixin(value = GlContextInfo.class, remap = false)
public abstract class GlContextInfoMixin {
    @Inject(method = "create", at = @At("HEAD"), cancellable = true)
    private static void metallum$fakeContextInfo(final CallbackInfoReturnable<GlContextInfo> cir) {
        if (!MetalBackend.isMetalHost()) {
            return;
        }
        cir.setReturnValue(new GlContextInfo("Apple", "Apple M-series", "4.6 Metal"));
    }
}
