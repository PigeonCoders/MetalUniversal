package com.metallum.mixin.sodium;

import com.metallum.client.metal.render.RotationThrottle;
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
 * <p>节流：pitch/yaw 2° 量化桶内转动（<2° 且未跨块——RotationThrottle 快照，
 * GameRendererCameraMixin 每帧写入）→ @Shadow 同步 lastCameraPitch/Yaw 为
 * 当前真实值（Sodium setupTerrain 的旋转比较读到"没变"）→ markGraphDirty
 * 不触发 → 重剔/列表/batch 重建全部跳过（纯绘制帧——帧时间稳，锁 vsync
 * 不掉帧）。跨桶（≥2°）或跨块（移动）不拦截（照常重算——快转/移动无滞后）；
 * 区块事件/投影变化走 Sodium 自己的比较（lastCameraPitch 未同步——不受影响）。
 * 可见集滞后 ≤2° = 原版本就有的行为（可接受）。
 *
 * <p>⚠️ handler 只声明 CallbackInfo（零目标参数——mixin 前缀匹配规则）——
 * 绕开 setupTerrain 的 Camera 参数（mojmap vs intermediary 描述符墙——
 * remap=false 下无法精确匹配；Camera 数据经 RotationThrottle 静态快照获取，
 * 无需注入参数）。
 */
@Mixin(value = SodiumWorldRenderer.class, remap = false)
public abstract class SodiumWorldRendererRotationMixin {

    @Shadow
    private double lastCameraPitch;

    @Shadow
    private double lastCameraYaw;

    @Inject(method = "setupTerrain", at = @At("HEAD"))
    private void metallum$throttleRotation(CallbackInfo ci) {
        if (RotationThrottle.shouldThrottle()) {
            this.lastCameraPitch = RotationThrottle.currentPitch();
            this.lastCameraYaw = RotationThrottle.currentYaw();
        } else {
            RotationThrottle.record();
        }
    }
}
