package com.metallum.mixin.sodium;

import com.metallum.client.metal.render.RotationThrottle;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * P25：相机快照——GameRenderer.render HEAD 每帧写入当前相机旋转/位置
 * （先于 Sodium setupTerrain 的重剔判断）。remap 默认（loom 自动把
 * Camera/GameRenderer/DeltaTracker 引用重映射到 intermediary——运行时
 * class_4184 等匹配）。
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererCameraMixin {

    @Shadow
    private Camera mainCamera;

    @Inject(method = "render", remap = false, at = @At("HEAD"))
    private void metallum$snapshotCamera(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        Camera camera = this.mainCamera;
        RotationThrottle.snapshot(camera.xRot(), camera.yRot(), camera.position().x, camera.position().z);
    }
}
