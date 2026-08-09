package com.metallum.mixin.sodium;

import com.metallum.client.metal.render.MetalBackend;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL32C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 拦截 Sodium core/MinecraftMixin 在 runTick 注入的 GPU fence 同步
 * （HEAD 处 glClientWaitSync/glDeleteSync、RETURN 处每帧无条件 glFenceSync）——
 * 三处 GL32C 静态调用在无 GL context 时第一帧即崩。
 *
 * <p>Metal 下 fence 语义无意义（MappedStagingBuffer 未被启用，DeviceFunctions=NONE），
 * 队列平衡不受影响（每帧 enqueue 1 个、每帧 dequeue 到 cpuRenderAheadLimit），
 * 等价替换为假值即可。非 Metal 主机回退调用原 GL32C 方法（GL 语义不变）。
 */
@Mixin(Minecraft.class)
public abstract class Gl32FenceMixin {
    @Redirect(
            method = "runTick",
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
            method = "runTick",
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
            method = "runTick",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL32C;glDeleteSync(J)V", remap = false),
            remap = false
    )
    private static void metallum$fakeDeleteSync(final long sync) {
        if (!MetalBackend.isMetalHost()) {
            GL32C.glDeleteSync(sync);
        }
    }
}
