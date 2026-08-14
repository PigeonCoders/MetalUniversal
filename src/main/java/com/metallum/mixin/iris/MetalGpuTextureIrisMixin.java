package com.metallum.mixin.iris;

import net.irisshaders.iris.mixinterface.GpuTextureInterface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Iris 适配：MetalGpuTexture 实现 GpuTextureInterface。
 *
 * <p>Iris 的 MixinGpuTexture2 在 MC 的 GpuTexture 接口上注入了 stub
 * （iris$getGlId 抛 AssertionError("Why.")），期望 GL 实现类（GlTexture 上的
 * MixinGpuTexture）覆盖。Metal 后端的 MetalGpuTexture 无覆盖 → 纹理创建期
 * （AbstractTexture.getTexture 的 afterGenerateId → TextureTracker.trackTexture）
 * 直接崩。
 *
 * <p>语义：iris$getGlId 返回的 GL 句柄仅被 TextureTracker 用作追踪 key
 * （PBR 贴图关联——Metal 下 PBR 装载路径本身走 GL，不生效）——虚拟自增 id
 * 唯一即可；markMipmapNonLinear 无 Metal 对应语义，no-op。
 * 类级 mixin 优先于接口 mixin（与 Iris 自身 GlTexture 的覆盖机制一致）。
 */
@Mixin(targets = "com.metallum.client.metal.render.MetalGpuTexture", remap = false)
public abstract class MetalGpuTextureIrisMixin implements GpuTextureInterface {
    @Unique
    private static final AtomicInteger NEXT_VIRTUAL_GL_ID = new AtomicInteger(1);

    @Unique
    private int irisVirtualGlId = -1;

    @Override
    public int iris$getGlId() {
        if (this.irisVirtualGlId < 0) {
            this.irisVirtualGlId = NEXT_VIRTUAL_GL_ID.getAndIncrement();
        }
        return this.irisVirtualGlId;
    }

    @Override
    public void iris$markMipmapNonLinear() {
    }
}
