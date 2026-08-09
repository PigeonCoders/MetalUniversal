package com.metallum.mixin.sodium;

import com.metallum.client.metal.render.MetalBackend;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL32C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 拦截 Sodium core/MinecraftMixin 在 runTick 注入的 GPU fence 同步
 * （HEAD 处 glClientWaitSync/glDeleteSync、RETURN 处每帧无条件 glFenceSync）。
 *
 * <p><b>iOS 崩溃实证（v1.1.0-sodium-metal 首测）</b>：Metal 接管后 initRenderer 被
 * HEAD cancel，LWJGL 的 GL.createCapabilities() 从未执行 → 无 current context →
 * 所有 GL 调用走 LWJGL stub（"No context is current" 警告 + 返回 0/空）→ Sodium
 * 的 glFenceSync 返回 0 → 抛 "Failed to create fence object"。MobileGlues 本身
 * 支持 glFenceSync（GLES 3.0 核心），失败在 LWJGL capabilities 未初始化。
 *
 * <p><b>注入点机制（字节码实证）</b>：Sodium 的 @Inject runTick RETURN 没有内联，
 * 而是生成独立方法 handler$bbm000$sodium$postRender（glFenceSync 的 INVOKE 在该
 * 方法体内，不在 runTick 里）→ 原 @Redirect(method="runTick") 找不到注入点而失效。
 * 修复：① method="*" 搜全部方法（命中 handler 方法，不依赖 sodium 生成的 hash 名）；
 * ② @Mixin priority=0 保证最后应用（priority 降序，先看到 sodium 注入的代码）。
 *
 * <p>Metal 下 fence 语义无意义（MappedStagingBuffer 未被启用，DeviceFunctions=NONE），
 * 队列平衡不受影响（每帧 enqueue 1 个、每帧 dequeue 到 cpuRenderAheadLimit），
 * 等价替换为假值即可。非 Metal 主机回退调用原 GL32C 方法（GL 语义不变）。
 */
@Mixin(value = Minecraft.class, priority = 0)
public abstract class Gl32FenceMixin {
    @Redirect(
            method = "*",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL32C;glFenceSync(II)J", remap = false),
            remap = false
    )
    private static long metallum$fakeFenceSync(final int condition, final int flags) {
        if (MetalBackend.isMetalHost()) {
            return 1L;
        }
        return GL32C.glFenceSync(condition, flags);
    }

    @Redirect(
            method = "*",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL32C;glClientWaitSync(JIJ)I", remap = false),
            remap = false
    )
    private static int metallum$fakeClientWaitSync(final long sync, final int flags, final long timeout) {
        if (MetalBackend.isMetalHost()) {
            return 0;
        }
        return GL32C.glClientWaitSync(sync, flags, timeout);
    }

    @Redirect(
            method = "*",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL32C;glDeleteSync(J)V", remap = false),
            remap = false
    )
    private static void metallum$fakeDeleteSync(final long sync) {
        if (!MetalBackend.isMetalHost()) {
            GL32C.glDeleteSync(sync);
        }
    }
}
