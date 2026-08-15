package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;

@Environment(EnvType.CLIENT)
public final class MetalGpuTextureView extends GpuTextureView {
    private boolean closed;
    @Nullable
    private MemorySegment nativeHandle;

    MetalGpuTextureView(final GpuTexture texture, final int baseMipLevel, final int mipLevels) {
        super(texture, baseMipLevel, mipLevels);
        ((MetalGpuTexture) texture).addView();
    }

    public MemorySegment nativeHandle() {
        if (this.closed) {
            throw new IllegalStateException("Texture view is closed");
        }

        MetalGpuTexture texture = (MetalGpuTexture) this.texture();
        if (this.baseMipLevel() == 0 && this.mipLevels() >= texture.getMipLevels()) {
            return texture.nativeHandle();
        }
        if (this.nativeHandle == null) {
            MemorySegment viewHandle = MetalNativeBridge.metallum_create_texture_view(
                    texture.nativeHandle(),
                    this.baseMipLevel(),
                    this.mipLevels()
            );
            if (MetalNativeBridge.isNullHandle(viewHandle)) {
                throw new IllegalStateException(
                        "Failed to create Metal texture view for mip range " + this.baseMipLevel() + "+" + this.mipLevels()
                );
            }
            this.nativeHandle = viewHandle;
            // P-hang 诊断：view handle 创建日志（仅 sub-mip view 有独立 handle；
            // full-view 优化路径直接返回 texture handle，不会到达此处）——与
            // queueRelease 按 handle 交叉比对，判定地址复用。
            DiagLog.log("[diag] createTextureView handle=%s baseMip=%d mips=%d",
                    "0x" + Long.toHexString(viewHandle.address()), this.baseMipLevel(), this.mipLevels());
        }
        return this.nativeHandle;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        if (this.nativeHandle != null) {
            MemorySegment handle = this.nativeHandle;
            this.nativeHandle = null;
            ((MetalGpuTexture) this.texture()).queueNativeRelease(handle);
        }
        this.closed = true;
        ((MetalGpuTexture) this.texture()).removeView();
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }
}
