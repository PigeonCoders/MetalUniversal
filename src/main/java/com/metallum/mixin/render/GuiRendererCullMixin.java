package com.metallum.mixin.render;

import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * P39（Sodium 设置屏后两页不渲染修复）：GUI 元素可见性剔除。
 *
 * <p>MC 的 GuiRenderer 对滚动容器内元素**无剔除**（GL 时代模式——scissor 只裁
 * 像素、顶点/命令照常提交）。Sodium 设置屏每帧全量提交 ~1300 个元素（原版列表
 * 只 ~20 个可见行）——在 iOS 驱动隐式命令/资源限制处耗尽，按提交顺序牺牲
 * 渲染顺序最后的 Performance/Advanced 页（确定性、不崩溃、前两页完好）。
 *
 * <p>修复：每个元素加入 mesh 前检查其 scissorArea（元素 bounds ∩ 滚动容器
 * scissor 的交集）——零面积 = 元素完全在视口外 = GL 下也被 scissor 全裁（零
 * 像素）——跳过顶点构建与提交。语义零差异，渲染量 ~1300 → ~30。
 *
 * <p>注入目标用 intermediary 名 + remap=false（与 LevelRendererDiagMixin 先例
 * 一致）：loom 的 mixin AP 0.8.5 对 1.21.11 新 render/state 类的 mojmap 名映射
 * 查找失败（"Unable to locate obfuscation mapping"——clean 后复现），
 * method_71287 = addElementToMesh（minecraft-dev find_mapping 实证 1.21.11）。
 */
@Mixin(GuiRenderer.class)
public abstract class GuiRendererCullMixin {
    @Inject(method = "method_71287", at = @At("HEAD"), cancellable = true, remap = false)
    private void metallum$skipOffscreenElements(GuiElementRenderState state, CallbackInfo ci) {
        ScreenRectangle rect = state.scissorArea();
        if (rect != null && (rect.width() <= 0 || rect.height() <= 0)) {
            ci.cancel();
        }
    }
}
