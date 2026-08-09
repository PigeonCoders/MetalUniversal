package com.metallum.mixin.sodium;

import com.metallum.client.metal.render.MetalBackend;
import com.metallum.client.metal.render.sodium.MetalGlBufferRegistry;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 拦截 GlBuffer 构造内的 GL20C.glGenBuffers()：Metal 模式无 GL context，
 * 改用 MetalGlBufferRegistry 分配整数句柄（句柄仅作 Sodium 侧标识，底层
 * MetalGpuBuffer 经 MetalSodiumCommandList 懒创建）。
 *
 * <p>@Redirect 方案（非 @Shadow）：直接替换构造体内 glGenBuffers() 静态调用，
 * setHandle 原样执行（handler 返回值顶替 glGenBuffers 返回值），不依赖父类
 * GlObject 的成员解析（@Shadow 对父类方法会报 "Cannot find target" 警告，
 * 有运行时失败风险）。目标字节码：GlBuffer.<init> 内
 * invokestatic GL20C.glGenBuffers:()I（javap 实证）。
 */
@Mixin(value = GlBuffer.class, remap = false)
public abstract class GlBufferMixin {
    @Redirect(
            method = "<init>",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL20C;glGenBuffers()I", remap = false),
            remap = false
    )
    private int metallum$genBufferHandle() {
        if (!MetalBackend.isMetalHost()) {
            return 0;
        }
        int handle = MetalGlBufferRegistry.nextHandle();
        MetalGlBufferRegistry.put(handle, MetalGlBufferRegistry.MetalGlBufferEntry.create(handle));
        return handle;
    }
}
