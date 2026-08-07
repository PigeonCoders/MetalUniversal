package com.metallum.mixin.render;

import com.metallum.client.metal.render.DiagLog;
import com.metallum.client.metal.render.Diagnostics;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * S0 可见性量化诊断：每帧 cullTerrain 完成后输出可见区块数与 smartCull 状态，
 * 用于评估原版 SectionOcclusionGraph 的剔除效果（阶段 2 决策依据）。
 * 节流输出到 metallum-diag.log（DiagLog），不污染主日志。
 */
@Mixin(LevelRenderer.class)
public class LevelRendererDiagMixin {
    @Inject(method = "cullTerrain", remap = false, at = @At("TAIL"))
    private void metallum$diagVisibleSections(Camera camera, Frustum frustum, boolean spectator, CallbackInfo ci) {
        if (Diagnostics.shouldRun("visible", 5_000L)) {
            LevelRenderer self = (LevelRenderer) (Object) this;
            DiagLog.log("visibleSections=%d smartCull=%b",
                    self.getVisibleSections().size(),
                    Minecraft.getInstance().smartCull);
        }
    }
}
