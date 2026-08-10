package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLSamplerAddressMode;
import com.metallum.client.metal.render.mtl.MTLSamplerMinMagFilter;
import com.metallum.client.metal.render.mtl.MTLSamplerMipFilter;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.NonNull;

import java.lang.foreign.MemorySegment;
import java.util.OptionalDouble;

@Environment(EnvType.CLIENT)
public final class MetalGpuSampler extends GpuSampler {
    private final MetalDevice device;
    private final MemorySegment nativeHandle;
    private final AddressMode addressModeU;
    private final AddressMode addressModeV;
    private final FilterMode minFilter;
    private final FilterMode magFilter;
    private final int maxAnisotropy;
    private final OptionalDouble maxLod;
    private boolean closed;

    MetalGpuSampler(
            final MetalDevice device,
            final AddressMode addressModeU,
            final AddressMode addressModeV,
            final FilterMode minFilter,
            final FilterMode magFilter,
            final int maxAnisotropy,
            final OptionalDouble maxLod
    ) {
        this.device = device;
        this.nativeHandle = MetalNativeBridge.metallum_create_sampler(
                device.metalDeviceHandle(),
                MTLSamplerAddressMode.from(addressModeU),
                MTLSamplerAddressMode.from(addressModeV),
                MTLSamplerMinMagFilter.from(minFilter),
                MTLSamplerMinMagFilter.from(magFilter),
                toMtlMipFilter(maxLod),
                Math.max(1, maxAnisotropy),
                toMtlMaxLodClamp(maxLod)
        );
        this.addressModeU = addressModeU;
        this.addressModeV = addressModeV;
        this.minFilter = minFilter;
        this.magFilter = magFilter;
        this.maxAnisotropy = maxAnisotropy;
        this.maxLod = maxLod;
    }

    @Override
    public @NonNull AddressMode getAddressModeU() {
        return this.addressModeU;
    }

    @Override
    public @NonNull AddressMode getAddressModeV() {
        return this.addressModeV;
    }

    @Override
    public @NonNull FilterMode getMinFilter() {
        return this.minFilter;
    }

    @Override
    public @NonNull FilterMode getMagFilter() {
        return this.magFilter;
    }

    @Override
    public int getMaxAnisotropy() {
        return this.maxAnisotropy;
    }

    @Override
    public @NonNull OptionalDouble getMaxLod() {
        return this.maxLod;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.device.queueResourceRelease(this.nativeHandle);
    }

    boolean isClosed() {
        return this.closed;
    }

    public MemorySegment nativeHandle() {
        return this.nativeHandle;
    }

    /**
     * iOS 环境规避（fix12）：MC 的 mip 数据由 CPU 生成（STBImageResize——iOS 上
     * Amethyst 的 liblwjgl_stb.dylib 与 Java 绑定不匹配，NoSuchMethodError 反复出现，
     * §12 已知环境问题）→ 纹理 mip>0 层未初始化（Metal 新纹理内容为零）→ 三线性
     * 采样 LOD>0 读到 alpha=0（透明间隙）+ 移动视角 LOD 切换（抖动）。
     *
     * <p>maxLod 未指定（MC 默认 OptionalDouble.empty()——terrain/lightTex 等全部默认
     * sampler）时强制无 mip 采样（只用 mip 0——原始纹理数据正常）：mipFilter=Nearest
     * + LOD 钳制 0.25。显式指定 maxLod 的纹理（如 MC 显式 mip 场景）保持原逻辑。
     */
    private static MTLSamplerMipFilter toMtlMipFilter(final OptionalDouble maxLod) {
        if (maxLod.isEmpty()) {
            return MTLSamplerMipFilter.Nearest;
        }
        return maxLod.getAsDouble() > 0.25 ? MTLSamplerMipFilter.Linear : MTLSamplerMipFilter.Nearest;
    }

    private static double toMtlMaxLodClamp(final OptionalDouble maxLod) {
        return Math.max(0.25, maxLod.orElse(0.25));
    }
}
