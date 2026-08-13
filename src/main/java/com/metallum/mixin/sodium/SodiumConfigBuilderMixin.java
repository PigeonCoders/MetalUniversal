package com.metallum.mixin.sodium;

import com.metallum.client.metal.render.sodium.MetalRenderDevice;
import net.caffeinemc.mods.sodium.client.gui.SodiumConfigBuilder;
import org.lwjgl.opengl.GLCapabilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * P36（Sodium 收尾）：Sodium 视频设置 → Performance 页的 "Use No Error Context"
 * 选项 enabledProvider 直调 LWJGL GL.getCapabilities()——Metal 主机无 GL context
 * （macOS 抛 IllegalStateException 必崩；iOS 落 MobileGlues 上下文语义分裂）。
 * 重定向为假 capabilities（与 GLRenderDeviceMixin 同一来源——空扩展集），
 * 打开 Performance 页不再崩（选项显示禁用，与实际一致）。
 * <p>调用点在 setEnabledProvider 的合成 lambda（lambda$buildNoErrorContextOption$49）
 * 内——故 @Redirect 目标为该合成方法（remap=false，Sodium 侧）。
 */
@Mixin(value = SodiumConfigBuilder.class, remap = false)
public abstract class SodiumConfigBuilderMixin {
    @Redirect(
            method = "lambda$buildNoErrorContextOption$49",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/opengl/GL;getCapabilities()Lorg/lwjgl/opengl/GLCapabilities;"
            ),
            require = 1
    )
    private static GLCapabilities metallum$safeCapabilities() {
        // 真 capabilities 优先（iOS 的 MobileGlues context 真实存在——与 mixin 前
        // 行为一致零回归）；macOS 无 GL context 时 GL.getCapabilities() 抛
        // IllegalStateException → 回退假 capabilities 防崩。
        try {
            GLCapabilities real = org.lwjgl.opengl.GL.getCapabilities();
            if (real != null) {
                return real;
            }
        } catch (RuntimeException ignored) {
            // 无 GL context——走假 capabilities
        }
        return MetalRenderDevice.getFakeCapabilities();
    }
}
