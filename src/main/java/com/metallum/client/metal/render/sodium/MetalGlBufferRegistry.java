package com.metallum.client.metal.render.sodium;

import com.metallum.client.metal.render.MetalBackend;
import com.metallum.client.metal.render.MetalDevice;
import com.metallum.client.metal.render.MetalGpuBuffer;
import com.mojang.blaze3d.buffers.GpuBuffer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sodium GlObject 整数句柄 ↔ Metal 缓冲状态 注册表（Sodium 适配层核心）。
 *
 * <p>背景：Sodium 的 {@code GlBuffer} 族在构造时直接调用
 * {@code GL20C.glGenBuffers()} 拿整数句柄（Metal 模式无 GL context 必崩），由
 * GlBufferMixin 拦截该调用改为本类分配。句柄仅作 Sodium 侧标识；底层
 * {@code MetalGpuBuffer} 因构造时无 size/usage（GL 语义延迟到 glBufferData），
 * 采用懒创建——首次 allocateStorage/uploadData/mapBuffer 时经 ensureAllocated 落地。
 *
 * <p>线程安全：Sodium 的 CommandList 调用集中于渲染线程，但句柄表用
 * ConcurrentHashMap 保守（chunk mesh 构建线程可能触碰 arena 元数据）。
 */
@Environment(EnvType.CLIENT)
public final class MetalGlBufferRegistry {
    /** GL 句柄从 1 起（0 在 GL 语义中是"未绑定"，保留不用）。 */
    private static final AtomicInteger NEXT_HANDLE = new AtomicInteger(1);
    private static final ConcurrentHashMap<Integer, MetalGlBufferEntry> BUFFERS = new ConcurrentHashMap<>();

    private MetalGlBufferRegistry() {
    }

    /** 分配一个 Sodium 侧整数句柄（替代 glGenBuffers）。 */
    public static int nextHandle() {
        return NEXT_HANDLE.getAndIncrement();
    }

    public static void put(final int handle, final MetalGlBufferEntry entry) {
        BUFFERS.put(handle, entry);
    }

    @Nullable
    public static MetalGlBufferEntry get(final int handle) {
        return BUFFERS.get(handle);
    }

    /** 移除并返回条目（deleteBuffer 路径，必须成对使用防泄漏）。 */
    @Nullable
    public static MetalGlBufferEntry remove(final int handle) {
        return BUFFERS.remove(handle);
    }

    /**
     * 单个 Sodium GlBuffer 的 Metal 侧状态。底层 MetalGpuBuffer 懒创建，
     * 同 size + 同 usage 复用（覆盖式更新），变化时重建（旧缓冲经
     * MetalGpuBuffer.close() → 帧末销毁队列回收/池化）。
     */
    @Environment(EnvType.CLIENT)
    public static final class MetalGlBufferEntry {
        private final int handle;
        @Nullable
        private MetalGpuBuffer buffer;
        private long size;
        private int usage;
        private boolean immutable;

        MetalGlBufferEntry(final int handle) {
            this.handle = handle;
        }

        public static MetalGlBufferEntry create(final int handle) {
            return new MetalGlBufferEntry(handle);
        }

        public int handle() {
            return this.handle;
        }

        @Nullable
        public MetalGpuBuffer buffer() {
            return this.buffer;
        }

        public long size() {
            return this.size;
        }

        public int usage() {
            return this.usage;
        }

        public void markImmutable() {
            this.immutable = true;
        }

        public boolean isImmutable() {
            return this.immutable;
        }

        /**
         * 确保底层 MetalGpuBuffer 已按 (size, usage) 分配。size=0 表示释放
         * （Sodium FallbackStagingBuffer.flush 的 allocateStorage(0) 语义）。
         */
        public void ensureAllocated(final long size, final int usage) {
            if (size <= 0L) {
                this.dispose();
                this.size = 0L;
                return;
            }
            if (this.buffer != null && this.buffer.size() == size && this.usage == usage) {
                return;
            }
            this.dispose();
            MetalDevice device = MetalBackend.activeDevice();
            this.buffer = new MetalGpuBuffer(device, usage, size);
            this.size = size;
            this.usage = usage;
        }

        /**
         * 覆写式上传时按需重建（Sodium uploadData 语义 = glBufferData 重新分配）。
         * 与 ensureAllocated 的区别：强制丢弃旧内容（避免同 size 复用时的
         * 陈旧数据残留语义依赖）。
         */
        public void reallocate(final long size, final int usage) {
            this.dispose();
            this.size = 0L;
            this.ensureAllocated(size, usage);
        }

        /** 释放底层缓冲（帧末销毁队列回收），保留句柄注册（deleteBuffer 才 remove）。 */
        public void dispose() {
            if (this.buffer != null) {
                this.buffer.close();
                this.buffer = null;
            }
        }
    }
}
