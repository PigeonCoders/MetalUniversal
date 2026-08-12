package com.metallum.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * P25：转动重算 2° 量化节流——对齐原版行为（原版 cullTerrain 的
 * floor(xRot/2) 量化桶；Sodium 亚度旋转即全量重剔：findVisible BFS +
 * render lists 重建 + batch 失效重建 ≈0.9-3.5ms/帧）→ 帧时间波动超 16.67ms
 * vsync 周期 → 相位滑动 → 每 ~1.4s 掉一帧 → 转动不丝滑（实测 fps 59.2、
 * 静止 60.0）。
 *
 * <p>节流：pitch/yaw 2° 量化桶内转动（<2°）→ @Shadow 同步 lastCameraPitch/Yaw
 * 为真实值（Sodium setupTerrain 的旋转比较读到"没变"）→ markGraphDirty 不触发
 * → 重剔/列表/batch 重建全部跳过（纯绘制帧——帧时间稳，锁 vsync 不掉帧）。
 * 跨桶（≥2°）不拦截（照常重算——快转无额外滞后）；位置/投影变化独立比较
 * （不受影响）。可见集滞后 ≤2° = 原版本就有的行为（可接受）。
 *
 * <p>相机旋转值经反射读取（intermediary 运行时：Camera.xRot/yRot =
 * method_19329/method_19330，1.21.11 tiny 实证）；反射失败静默退化
 * （不节流——原 Sodium 行为）。
 */
@Mixin(value = SodiumWorldRenderer.class, remap = false)
public abstract class SodiumWorldRendererRotationMixin {

    @Shadow
    private double lastCameraPitch;

    @Shadow
    private double lastCameraYaw;

    private static int metallum$lastRotBucketX = Integer.MIN_VALUE;
    private static int metallum$lastRotBucketY = Integer.MIN_VALUE;

    @Inject(method = "setupTerrain", at = @At("HEAD"))
    private void metallum$throttleRotation(Object camera, Object viewport, Object fog, boolean hidden, boolean cull, Object matrices, CallbackInfo ci) {
        double pitch;
        double yaw;
        try {
            pitch = ((Number) camera.getClass().getMethod("method_19329").invoke(camera)).doubleValue();
            yaw = ((Number) camera.getClass().getMethod("method_19330").invoke(camera)).doubleValue();
        } catch (Exception e) {
            return;
        }
        int bucketX = (int) Math.floor(pitch / 2.0);
        int bucketY = (int) Math.floor(yaw / 2.0);
        if (bucketX == metallum$lastRotBucketX && bucketY == metallum$lastRotBucketY) {
            this.lastCameraPitch = pitch;
            this.lastCameraYaw = yaw;
        }
        metallum$lastRotBucketX = bucketX;
        metallum$lastRotBucketY = bucketY;
    }
}
