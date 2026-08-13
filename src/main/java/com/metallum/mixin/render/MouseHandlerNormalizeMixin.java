package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalBackend;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * P31-5（T1）：转动抖动的直接修复——第一人称旋转零插值
 * （LocalPlayer.getViewYRot 非骑乘直接返回 getYRot——绕过 partialTick），而
 * turnPlayer 每帧把帧内累积增量（accumulatedDX × 8 × sens³）一次性打进
 * Entity.turn——掉帧帧（33ms）累积 2 倍鼠标量 → 相机单帧跳 2 倍角度 →
 * 画面"跳/抖"（位置移动有 partialTick 端点更新补偿故不抖）。
 *
 * <p>修复：turnPlayer 入参（handleAccumulatedMovement 以 Blaze3D.getTime() 测得
 * 的帧间隔，秒）超过 1/60s 时，按 base/interval 缩放 accumulatedDX/DY——掉帧帧
 * 只应用基准帧长的输入量，超出部分丢弃。三个分支对 accumulatedDX 线性（含开镜
 * 分支与 tutorial.onMouse 参数）——缩放输入 ≡ 精确缩放所有分支输出。
 *
 * <p>smoothCamera 分支跳过：其 SmoothDouble 第二参数是帧率无关追赶率而非输入
 * 归一化（平衡态下掉帧帧追赶量同样 2 倍——输入侧缩放约束不住它，且该模式用户
 * 本就接受平滑滞后）。病理帧（interval ≥ 1s：后台恢复）跳过——不吞输入。
 *
 * <p>边界（已文档化的设计取舍）：均匀持续 <60fps 渲染（每帧都是 33ms）会系统性
 * 降敏（每帧 scale=0.5——转动速度永久减半）；比原版抖动更可接受。GL 主机不生效
 * （isMetalHost 守卫——原版行为保持）。
 */
@Mixin(MouseHandler.class)
public class MouseHandlerNormalizeMixin {
    /** 基准帧长（秒）。120Hz 满速帧 8.3ms、均匀 60fps 帧 16.7ms 均不触发；
     *  仅帧长 >16.7ms（掉帧）才丢弃超出部分。 */
    private static final double BASE_FRAME_SECONDS = 1.0 / 60.0;

    @Shadow(remap = false)
    private double accumulatedDX;

    @Shadow(remap = false)
    private double accumulatedDY;

    @Inject(method = "turnPlayer", remap = false, at = @At("HEAD"))
    private void metallum$normalizeTurnInput(double frameInterval, CallbackInfo ci) {
        if (!MetalBackend.isMetalHost()) {
            return;
        }
        if (Minecraft.getInstance().options.smoothCamera) {
            return;
        }
        if (frameInterval <= BASE_FRAME_SECONDS || frameInterval >= 1.0) {
            return;
        }
        double scale = BASE_FRAME_SECONDS / frameInterval;
        this.accumulatedDX *= scale;
        this.accumulatedDY *= scale;
    }
}
