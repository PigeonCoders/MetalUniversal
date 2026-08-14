package com.metallum.mixin.iris;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Iris 适配阶段 0（启动存活）：VanillaRenderingPipeline.beginLevelRendering 的
 * 唯一逻辑是 GlStateManager._glUseProgram(0)（每帧一次裸 GL——Metal 主机崩）。
 * 无 pack 时方法体无其他逻辑（源码核验：仅此一行）——整方法跳过。
 */
@Mixin(targets = "net.irisshaders.iris.pipeline.VanillaRenderingPipeline", remap = false)
public abstract class VanillaRenderingPipelineMixin {
    @Inject(method = "beginLevelRendering", at = @At("HEAD"), cancellable = true, remap = false)
    private void metallum$skipGlUseProgram(CallbackInfo ci) {
        ci.cancel();
    }
}
