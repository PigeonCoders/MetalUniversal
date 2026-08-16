package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLPixelFormat;
import com.metallum.client.metal.render.mtl.MTLStorageMode;
import com.metallum.client.metal.render.mtl.MTLTextureUsage;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;

@Environment(EnvType.CLIENT)
final class MetalGpuTexture extends GpuTexture {
    private final MetalDevice device;
    private final MTLPixelFormat mtlPixelFormat;
    private boolean closed;
    @Nullable
    private Vector4fc materializedColorClear;
    @Nullable
    private Double materializedDepthClear;
    private int views = 1;
    @Nullable
    private MemorySegment nativeHandle;

    MetalGpuTexture(
            final MetalDevice device,
            final int usage,
            final String label,
            final TextureFormat format,
            final int width,
            final int height,
            final int depthOrLayers,
            final int mipLevels
    ) {
        super(usage, label, format, width, height, depthOrLayers, mipLevels);
        this.device = device;
        this.mtlPixelFormat = MTLPixelFormat.from(format);

        this.nativeHandle = MetalNativeBridge.metallum_create_texture_2d(
                device.metalDeviceHandle(),
                this.mtlPixelFormat,
                width,
                height,
                depthOrLayers,
                mipLevels,
                (usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0 ? 1L : 0L,
                toMtlTextureUsage(usage),
                MTLStorageMode.Private,
                label
        );
        if (MetalNativeBridge.isNullHandle(this.nativeHandle)) {
            throw new IllegalStateException(
                    "Failed to create Metal texture " + width + "x" + height
                            + " (" + format + ", mipLevels=" + mipLevels + ")");
        }
        // P-hang 诊断：创建日志——与 queueRelease/destroyQueue release 按 handle
        // 交叉比对，判定「释放后新对象是否复用同一地址」（两次 reload 风暴
        // handle 列表完全相同的解释：地址确定性复用 vs 旧对象未释放）。
        DiagLog.log("[diag] createTexture handle=%s w=%d h=%d fmt=%s",
                "0x" + Long.toHexString(this.nativeHandle.address()), width, height, format);
    }

    int pixelSize() {
        // 1.21.11 的 TextureFormat 无 blockSize()，用 pixelSize()
        return this.getFormat().pixelSize();
    }

    void recordMaterializedClear(@Nullable final Vector4fc color, @Nullable final Double depth) {
        if (color != null) {
            this.materializedColorClear = color;
        }
        if (depth != null) {
            this.materializedDepthClear = depth;
        }
    }

    boolean clearIsRedundant(@Nullable final Vector4fc color, @Nullable final Double depth) {
        return (color == null || color.equals(this.materializedColorClear))
                && (depth == null || depth.equals(this.materializedDepthClear));
    }

    void markContentsDirty() {
        this.materializedColorClear = null;
        this.materializedDepthClear = null;
    }

    MemorySegment nativeHandle() {
        // close() 时若还有 view 存活，nativeHandle 会暂时保留到最后一个 view 关闭；
        // 但 close 语义下纹理已不可用，必须一并拦截（否则 reload 窗口期可能把已
        // close 的纹理继续编码进新 CB，为悬垂释放创造条件）。
        if (this.closed || this.nativeHandle == null) {
            throw new IllegalStateException("Native Metal texture is closed");
        }
        return this.nativeHandle;
    }

    void queueNativeRelease(final MemorySegment handle) {
        // P-hang 诊断：本方法唯一调用方是 MetalGpuTextureView.close（view 自身
        // handle 的释放漏斗）——故 type=textureView。texture 本身的释放走
        // removeView（type=texture）。与 destroyQueue rotate 日志按 handle 比对时序。
        DiagLog.log("[diag] queueRelease type=textureView handle=%s", "0x" + Long.toHexString(handle.address()));
        this.device.queueResourceRelease(handle);
    }

    void addView() {
        this.views++;
    }

    void removeView() {
        this.views--;
        if (this.views < 0) {
            throw new IllegalStateException("Too many views removed from texture");
        }
        if (this.closed && this.views == 0 && this.nativeHandle != null) {
            MemorySegment handle = this.nativeHandle;
            this.nativeHandle = null;
            // P-hang 诊断：texture 本体 native 释放入队（与 textureView 释放区分）。
            DiagLog.log("[diag] queueRelease type=texture handle=%s", "0x" + Long.toHexString(handle.address()));
            this.device.queueResourceRelease(handle);
        }
    }

    MTLPixelFormat mtlPixelFormat() {
        return this.mtlPixelFormat;
    }

    MTLPixelFormat mtlStencilPixelFormat() {
        return this.mtlPixelFormat.hasStencil() ? this.mtlPixelFormat : MTLPixelFormat.Invalid;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.removeView();
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    private static long toMtlTextureUsage(final int usage) {
        long result = 0L;
        if ((usage & GpuTexture.USAGE_TEXTURE_BINDING) != 0 || (usage & GpuTexture.USAGE_COPY_DST) != 0 || (usage & GpuTexture.USAGE_COPY_SRC) != 0) {
            result |= MTLTextureUsage.ShaderRead.value;
        }
        if ((usage & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0) {
            result |= MTLTextureUsage.RenderTarget.value;
            result |= MTLTextureUsage.ShaderRead.value;
        }
        return result == 0L ? MTLTextureUsage.ShaderRead.value : result;
    }
}
