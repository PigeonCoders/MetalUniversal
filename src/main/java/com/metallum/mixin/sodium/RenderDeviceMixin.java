package com.metallum.mixin.sodium;

import com.metallum.client.metal.render.MetalBackend;
import com.metallum.client.metal.render.sodium.MetalRenderDevice;
import net.caffeinemc.mods.sodium.client.gl.device.RenderDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把 Sodium 的 RenderDevice.INSTANCE（static final，<clinit> 内 new GLRenderDevice）
 * 替换为 MetalRenderDevice.INSTANCE。
 *
 * <p>必须 HEAD cancel：GLRenderDevice 构造会走 DeviceFunctions→pickBest→
 * GL.getCapabilities()，无 GL context 时返回 null → NPE（javap 实证 <clinit>：
 * new GLRenderDevice → invokespecial <init> → putstatic INSTANCE）。
 *
 * <p>@Shadow 静态字段赋值：注入的 putstatic 落在 <clinit> 内，对 static final
 * 字段赋值在字节码层面合法（JVMS 只约束位置不约束次数）。若 Sponge 校验器对
 * final 修饰符报错，备选方案是 Unsafe 修改（见计划文件 D1）。
 */
@Mixin(value = RenderDevice.class, remap = false)
public abstract class RenderDeviceMixin {
    @Shadow(remap = false)
    private static RenderDevice INSTANCE;

    @Inject(method = "<clinit>", at = @At("HEAD"), cancellable = true)
    private static void metallum$replaceInstance(final CallbackInfo ci) {
        if (!MetalBackend.isMetalHost()) {
            return;
        }
        INSTANCE = MetalRenderDevice.INSTANCE;
        ci.cancel();
    }
}
