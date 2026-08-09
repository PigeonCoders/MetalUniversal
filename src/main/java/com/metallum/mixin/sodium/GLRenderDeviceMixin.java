package com.metallum.mixin.sodium;

import com.metallum.client.metal.render.MetalBackend;
import com.metallum.client.metal.render.sodium.MetalRenderDevice;
import com.metallum.client.metal.render.sodium.MetalSodiumCommandList;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.gl.device.GLRenderDevice;
import org.lwjgl.opengl.GLCapabilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 把 GLRenderDevice（RenderDevice.INSTANCE 的实际对象，Sodium <clinit> 里
 * new GLRenderDevice → putstatic INSTANCE）的行为 Metal 化。
 *
 * <p>为什么不做接口 mixin：RenderDevice 是接口，Sponge 的 variant 判定
 * （MixinInfo.getVariant 字节码实证）——mixin 类非接口 → 恒 STANDARD →
 * target 必须是 class，接口 target 抛 "target type mismatch: ... is an
 * interface"（iOS 实测崩溃）；接口 mixin（INTERFACE variant）需 mixin 类
 * 自身声明为 interface，且只做 default 方法合并，不支持 <clinit> 注入。
 * 故改从实现类 GLRenderDevice 入手。
 *
 * <p>拦截点：
 * <ul>
 *   <li>getCapabilities：GLRenderDevice 构造期 DeviceFunctions→pickBest
 *       调用它，无 GL context 时 GL.getCapabilities() 返回 null → NPE
 *       （<clinit> 崩）。返回假 GLCapabilities（空扩展集）→ pickBest 得
 *       NONE → Sodium 自动走 FallbackStagingBuffer。</li>
 *   <li>createCommandList：SodiumWorldRenderer.reload/setupTerrain 的
 *       RenderDevice.INSTANCE.createCommandList() 若返回 GL 版命令列表
 *       则所有 buffer/tessellation 操作走 GL 崩；替换为 Metal 版。</li>
 * </ul>
 *
 * <p>其余接口方法天然安全：makeActive/makeInactive（isActive 标志）、
 * getSubTexelPrecisionBits（mac→4）、getDeviceFunctions（构造期已定 NONE）。
 */
@Mixin(value = GLRenderDevice.class, remap = false)
public abstract class GLRenderDeviceMixin {
    @Inject(method = "getCapabilities", at = @At("HEAD"), cancellable = true)
    private void metallum$fakeCapabilities(final CallbackInfoReturnable<GLCapabilities> cir) {
        if (!MetalBackend.isMetalHost()) {
            return;
        }
        cir.setReturnValue(MetalRenderDevice.getFakeCapabilities());
    }

    @Inject(method = "createCommandList", at = @At("HEAD"), cancellable = true)
    private void metallum$metalCommandList(final CallbackInfoReturnable<CommandList> cir) {
        if (!MetalBackend.isMetalHost()) {
            return;
        }
        // MetalSodiumCommandList 无实例状态（encoder() 经 MetalBackend.activeDevice()
        // 取单例 encoder），每次 new 安全（Sodium 的 try-with-resources 语义）
        cir.setReturnValue(new MetalSodiumCommandList());
    }
}
