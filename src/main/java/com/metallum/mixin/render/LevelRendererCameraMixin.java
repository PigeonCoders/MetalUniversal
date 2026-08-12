package com.metallum.mixin.render;

import com.metallum.client.metal.render.RotationThrottle;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * P29-1：相机快照时序修复——注入 renderLevel HEAD（Camera.setup 已在
 * GameRenderer.render 内执行完毕——当帧值），替代旧 GameRendererCameraMixin
 * （render HEAD 早于 Camera.setup——快照恒为上一帧——导致
 * SodiumWorldRendererRotationMixin 写回 lastCameraPitch/Yaw 的是旧值 →
 * Sodium 当帧精确比较每帧失配 → 节流 100% 失效——转动重算照跑）。
 *
 * <p>renderLevel 在 GameRenderer.render 的 updateCamera 之后调用（当帧相机
 * 已 setup）且先于 renderLevel 内的 cullTerrain（Sodium setupTerrain）——
 * 快照时序正确（当帧值）。
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererCameraMixin {

    @Inject(method = "renderLevel", remap = false, at = @At("HEAD"))
    private void metallum$snapshotCamera(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        GameRenderer gameRenderer = Minecraft.getInstance().gameRenderer;
        if (gameRenderer == null) {
            return;
        }
        net.minecraft.client.Camera camera = gameRenderer.getMainCamera();
        RotationThrottle.snapshot(camera.xRot(), camera.yRot(), camera.position().x, camera.position().z);
    }
}
