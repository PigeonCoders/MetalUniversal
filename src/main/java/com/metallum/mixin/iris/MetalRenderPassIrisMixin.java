package com.metallum.mixin.iris;

import net.irisshaders.iris.mixinterface.CustomPass;
import net.irisshaders.iris.mixinterface.RenderPassInterface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Iris 适配阶段 1：MetalRenderPass 实现 RenderPassInterface（customPass 纯 Java
 * 字段存取）。Iris 的 MixinRenderPass_Stub 在 MC 的 RenderPass 接口上注入
 * default 方法抛 UnsupportedOperationException——有 pack 时 composite/final/
 * shadowcomp/CenterDepth/ColorSpace 5 个调用方会触发。Metallum 的类级 mixin
 * 覆盖接口 stub（与 MetalGpuTextureIrisMixin 同机制）。
 *
 * <p>真正语义（CustomPass 劫持 = 离屏 FBO 绑定 + pipeline 状态重放）属阶段
 * 3/6——本阶段只保证不崩：get 返回 null（= 无 custom pass，trySetup 走默认
 * 路径）。
 */
@Mixin(targets = "com.metallum.client.metal.render.MetalRenderPass", remap = false)
public abstract class MetalRenderPassIrisMixin implements RenderPassInterface {
    @Unique
    private CustomPass irisCustomPass;

    @Override
    public void iris$setCustomPass(CustomPass pass) {
        this.irisCustomPass = pass;
    }

    @Override
    public CustomPass iris$getCustomPass() {
        return this.irisCustomPass;
    }
}
