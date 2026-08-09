package com.metallum.mixin.render;

import com.metallum.Metallum;
import com.metallum.client.metal.render.MetalBackend;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.SamplerCache;
import net.minecraft.client.renderer.DynamicUniforms;
import org.lwjgl.opengl.GL;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.21.11 无 GpuBackend 体系（26.2 的多后端抽象）：GpuDevice 由
 * RenderSystem.initRenderer 内直接 new GlDevice(...) 创建并存入静态 DEVICE 字段。
 * 本 mixin 在方法头部取消原实现（避免创建 GlDevice/初始化 GL），改用
 * MetalBackend.createDevice 创建 MetalDevice，并补全原方法被跳过的副作用
 * （apiDescription / dynamicUniforms / samplerCache 初始化，字节码实证）。
 *
 * <p>Mixin 不支持 @Redirect 构造器（InvalidInjectionException: Illegal @Redirect
 * of constructor），故采用 HEAD + cancellable 方案。
 *
 * <p>flipFrame 的 glfwSwapBuffers 拦截已移至 GLFWSwapBuffersMixin（mixin 0.8.7 的
 * @Redirect handler 参数必须覆盖目标方法全部参数，此处无法写出 fyk/fwf 类型）。
 *
 * <p>取消原实现意味着 LWJGL 的 GL capabilities 从未创建（GL.createCapabilities
 * 是原方法内部步骤）——此后所有 LWJGL GL 调用走空壳：不真执行、静默返回 0/空
 * （实测 glFenceSync 返回 0 → sodium 抛 "Failed to create fence object"）。
 * 且 mixin 无法拦截 sodium 注入的 GL 调用（InjectionInfo 扫描发生在 prepare
 * 阶段，sodium 的 handler$ 方法那时尚未生成——实测 0/1 InjectionError）。
 * 故在此手动创建 capabilities：MobileGlues 是真实 GL context（iOS 上
 * lwjgl-opengl natives 存在，纯 GL + Sodium 实测正常），创建成功后 sodium 的
 * 残留 GL 调用（每帧 fence 同步）真实执行不再返回 0；失败则 try/catch 兜底
 * （代价是 fence 崩回原样，MobileGlues 下应成功）。
 */
@Mixin(RenderSystem.class)
public class RenderSystemDeviceMixin {
    @Shadow(remap = false)
    private static GpuDevice DEVICE;
    @Shadow(remap = false)
    private static String apiDescription;
    @Shadow(remap = false)
    private static DynamicUniforms dynamicUniforms;
    @Shadow(remap = false)
    private static SamplerCache samplerCache;

    @Inject(method = "initRenderer", remap = false, at = @At("HEAD"), cancellable = true)
    private static void metallum$createMetalDevice(
            final long window,
            final int rendererType,
            final boolean debug,
            final ShaderSource shaderSource,
            final boolean isIntegrated,
            final CallbackInfo ci
    ) {
        if (!MetalBackend.isMetalHost()) {
            return;
        }
        // MobileGlues 下 GL context 已 current：手动创建 capabilities 使 LWJGL
        // GL 调用真实执行（sodium 每帧 fence 依赖；stub 会返回 0 致崩）。
        try {
            GL.createCapabilities();
        } catch (Throwable t) {
            Metallum.LOGGER.warn("[metallum] GL.createCapabilities() failed, LWJGL GL calls will be stubs: {}", t.toString());
        }
        DEVICE = MetalBackend.createDevice(window, shaderSource);
        apiDescription = DEVICE.getImplementationInformation();
        dynamicUniforms = new DynamicUniforms();
        samplerCache.initialize();
        ci.cancel();
    }
}
