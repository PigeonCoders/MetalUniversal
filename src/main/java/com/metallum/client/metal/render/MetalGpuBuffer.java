package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLHazardTrackingMode;
import com.metallum.client.metal.render.mtl.MTLResourceOptions;
import com.metallum.client.metal.render.mtl.MTLStorageMode;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@Environment(EnvType.CLIENT)
public class MetalGpuBuffer extends GpuBuffer {
    private final MetalDevice device;
    private final boolean cpuAccessible;
    private final boolean dynamic;
    private final boolean shadowUpload;
    private final long resourceOptions;
    private final long allocationSize;
    @Nullable
    private MemorySegment nativeHandle;
    @Nullable
    private ByteBuffer storage;
    private boolean closed;

    public MetalGpuBuffer(final MetalDevice device, final int usage, final long size) {
        super(usage, size);
        this.device = device;

        this.dynamic = isDynamic(usage);
        this.shadowUpload = isShadowUploaded(usage);
        this.cpuAccessible = (isCpuAccessible(usage) || this.dynamic) && !this.shadowUpload;
        this.resourceOptions = toMtlResourceOptions(usage, this.shadowUpload);
        if (size <= 0L) {
            throw new IllegalArgumentException("Metal buffer size must be > 0 (got " + size + ")");
        }
        long aligned = (size + 15L) & ~15L;
        if (aligned <= 0L) {
            throw new IllegalArgumentException("Metal buffer size overflow after alignment: " + size);
        }
        this.allocationSize = aligned;

        MemorySegment pooled = device.tryAcquirePooledBuffer(this.allocationSize, this.resourceOptions);
        if (!MetalNativeBridge.isNullHandle(pooled)) {
            this.nativeHandle = pooled;
            if (this.cpuAccessible) {
                MemorySegment contents = MetalNativeBridge.metallum_get_buffer_contents(pooled);
                if (MetalNativeBridge.isNullHandle(contents)) {
                    MetalNativeBridge.metallum_release_object(pooled);
                    this.nativeHandle = null;
                    throw new IllegalStateException("MTLBuffer.contents returned null for pooled buffer (size=" + this.allocationSize + ", resourceOptions=" + this.resourceOptions + ")");
                }
                this.storage = MetalNativeBridge.nativeByteBufferView(contents, this.allocationSize).order(ByteOrder.nativeOrder());
            }
            return;
        }

        long max = device.maxBufferAllocationSize();
        if (max > 0L && this.allocationSize > max) {
            throw new IllegalArgumentException("Metal buffer size " + this.allocationSize + " exceeds device max " + max);
        }
        this.nativeHandle = MetalNativeBridge.metallum_create_buffer(device.metalDeviceHandle(), this.allocationSize, this.resourceOptions);
        if (MetalNativeBridge.isNullHandle(this.nativeHandle)) {
            throw new IllegalStateException("Failed to create Metal buffer (size=" + this.allocationSize + ", resourceOptions=" + this.resourceOptions + ", device=" + this.device.getClass().getSimpleName() + ")");
        }
        Stats.recordUsage(usage, size, this.allocationSize);

        if (this.cpuAccessible) {
            MemorySegment contents = MetalNativeBridge.metallum_get_buffer_contents(this.nativeHandle);
            if (MetalNativeBridge.isNullHandle(contents)) {
                MetalNativeBridge.metallum_release_object(this.nativeHandle);
                this.nativeHandle = null;
                throw new IllegalStateException("MTLBuffer.contents returned null (size=" + this.allocationSize + ", resourceOptions=" + this.resourceOptions + ")");
            }

            this.storage = MetalNativeBridge.nativeByteBufferView(contents, this.allocationSize).order(ByteOrder.nativeOrder());
        }
    }

    MetalGpuBuffer(final MetalDevice device, final int usage, final long size, final @Nullable MemorySegment wrappedHandle) {
        super(usage, size);
        this.device = device;
        this.cpuAccessible = false;
        this.dynamic = false;
        this.shadowUpload = false;
        this.resourceOptions = 0L;
        this.allocationSize = size;
        this.nativeHandle = wrappedHandle;
        this.storage = null;
    }

    public ByteBuffer sliceStorage(final long offset, final long length) {
        if (this.storage == null) {
            throw new IllegalStateException("Buffer is not CPU-accessible");
        }

        ByteBuffer duplicate = this.storage.duplicate().order(this.storage.order());
        duplicate.position(Math.toIntExact(offset));
        duplicate.limit(Math.toIntExact(offset + length));
        return duplicate.slice().order(this.storage.order());
    }

    public MemorySegment nativeHandle() {
        if (this.nativeHandle == null || this.nativeHandle.address() == 0L) {
            throw new IllegalStateException("Native Metal buffer is closed or null");
        }
        return this.nativeHandle;
    }

    public boolean isDynamic() {
        return this.dynamic;
    }

    /**
     * CPU 可访问（Metal Shared storage）：可直接经 currentStorage()/sliceStorage()
     * 读写，无需 GPU staging + blit 往返。
     */
    public boolean isCpuAccessible() {
        return this.cpuAccessible;
    }

    /** P38（GUI 后段丢失修复）：本缓冲是否走"影子缓冲 + blit 上传"路径（见 isShadowUploaded）。 */
    public boolean isShadowUploaded() {
        return this.shadowUpload;
    }

    public long allocationSize() {
        return this.allocationSize;
    }

    public long resourceOptions() {
        return this.resourceOptions;
    }

    public ByteBuffer currentStorage() {
        if (this.storage == null) {
            throw new IllegalStateException("Buffer is not CPU-accessible");
        }
        return this.storage.duplicate().order(this.storage.order());
    }

    void swapBacking(final MemorySegment handle, final ByteBuffer storage) {
        this.nativeHandle = handle;
        this.storage = storage;
    }

    @Override
    public boolean isClosed() {
        return this.closed || this.nativeHandle == null;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.storage = null;
        if (this.nativeHandle != null) {
            MemorySegment handle = this.nativeHandle;
            this.nativeHandle = null;
            this.device.queueBufferRelease(handle, this.allocationSize, this.resourceOptions);
        }
    }

    public int getUsage() {
        return this.usage();
    }

    private static boolean isCpuAccessible(final int usage) {
        return (usage & GpuBuffer.USAGE_MAP_READ) != 0
                || (usage & GpuBuffer.USAGE_MAP_WRITE) != 0
                || (usage & GpuBuffer.USAGE_HINT_CLIENT_STORAGE) != 0;
    }

    /**
     * P38（GUI 后段丢失修复）：MC 1.21.11 中 usage=MAP_WRITE|VERTEX（34）仅 GuiRenderer
     * 的 GUI 顶点环形缓冲一处使用——它是以 Shared 存储被 GPU 顶点级直读的后端唯一
     * 路径（世界网格/实体全部 Private+blit，uniform Shared 但极小）。iOS TBDR 上该
     * 路径从未在规模（Sodium 设置屏每帧 ~1300 元素）下实证，怀疑驱动级顶点取数边角
     * 导致后段 mesh 丢失。改为 Private + 影子上传（mapBuffer 写影子、close 时
     * staging+blit——与世界渲染已实证的同路径）。开关
     * -Dmetallum.gui.privateVertexBuffer=false 可回退旧行为（分离家族用）。
     */
    private static final boolean SHADOW_UPLOAD_ENABLED =
            Boolean.parseBoolean(System.getProperty("metallum.gui.privateVertexBuffer", "true"));

    private static boolean isShadowUploaded(final int usage) {
        return SHADOW_UPLOAD_ENABLED
                && (usage & GpuBuffer.USAGE_VERTEX) != 0
                && (usage & GpuBuffer.USAGE_MAP_WRITE) != 0
                && (usage & GpuBuffer.USAGE_MAP_READ) == 0;
    }

    private static boolean isDynamic(final int usage) {
        return (usage & GpuBuffer.USAGE_UNIFORM) != 0 && (usage & GpuBuffer.USAGE_COPY_DST) != 0;
    }

    private static long toMtlResourceOptions(final int usage, final boolean shadowUpload) {
        MTLStorageMode storageMode;
        if (shadowUpload) {
            storageMode = MTLStorageMode.Private;
        } else {
            storageMode = isCpuAccessible(usage) || isDynamic(usage) ? MTLStorageMode.Shared : MTLStorageMode.Private;
        }
        return MTLResourceOptions.of(storageMode, MTLHazardTrackingMode.Untracked);
    }
}
