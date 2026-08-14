package com.metallum.mixin.iris;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Iris 适配阶段 0（启动存活）：Metal 主机无 GL context——IrisRenderSystem.initRenderer
 * 的 GL 能力探测（GL.getCapabilities）、GL 投影矩阵缓冲创建
 * （PerspectiveProjectionMatrixBuffer）、SamplerLimits（glGetInteger）全部会崩。
 *
 * <p>中和方式：整方法跳过。无 shader pack 时（VanillaRenderingPipeline）上述静态
 * 字段（dsaState/supportsCompute/samplers 等）无任何访问点（源码核验）——null
 * 值安全。有 pack 的渲染路径由后续阶段（阶段 5 的 shadow 投影 UBO 适配）提供
 * 等价物后再放开。
 */
@Mixin(targets = "net.irisshaders.iris.gl.IrisRenderSystem", remap = false)
public abstract class IrisRenderSystemInitMixin {
    @Inject(method = "initRenderer", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metallum$skipInitRenderer(CallbackInfo ci) {
        ci.cancel();
    }
}
