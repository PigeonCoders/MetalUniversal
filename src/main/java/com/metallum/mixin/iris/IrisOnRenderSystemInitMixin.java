package com.metallum.mixin.iris;

import com.metallum.client.metal.render.sodium.MetalRenderDevice;
import org.lwjgl.opengl.GLCapabilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Iris 适配阶段 0（启动存活）：Iris.onRenderSystemInit 的 GL.getCapabilities()
 * （GL_KHR/ARB_parallel_shader_compile 探测）在 Metal 主机无 GL context 时抛
 * IllegalStateException。@Redirect 返回假 capabilities（扩展集为空 → 两处
 * parallel-compile 分支均 false → 跳过 glMaxShaderCompilerThreads）。
 * 方法体其余部分（PBRTextureManager + VertexSerializerRegistry 注册 +
 * loadShaderpack）是纯 Java，保留执行。
 */
@Mixin(targets = "net.irisshaders.iris.Iris", remap = false)
public abstract class IrisOnRenderSystemInitMixin {
    @Redirect(
            method = "onRenderSystemInit",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL;getCapabilities()Lorg/lwjgl/opengl/GLCapabilities;", ordinal = 0),
            remap = false
    )
    private static GLCapabilities metallum$fakeCapabilities0() {
        return MetalRenderDevice.getFakeCapabilities();
    }

    @Redirect(
            method = "onRenderSystemInit",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL;getCapabilities()Lorg/lwjgl/opengl/GLCapabilities;", ordinal = 1),
            remap = false
    )
    private static GLCapabilities metallum$fakeCapabilities1() {
        return MetalRenderDevice.getFakeCapabilities();
    }
}
