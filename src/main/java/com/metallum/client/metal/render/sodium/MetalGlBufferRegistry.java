package com.metallum.client.metal.render.sodium;

import com.metallum.client.metal.render.MetalBackend;
import com.metallum.client.metal.render.MetalCommandEncoder;
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
     *
     * <p>P2 staging ring：FallbackStagingBuffer 的中转缓冲（STREAM_COPY → Shared
     * CPU 直写）是唯一不受队列序保护的跨帧竞争点——帧 N+1 编码期 CPU 覆写 vs
     * 帧 N GPU 执行期 blit 读（sync=2 实测闪烁回归实锤）。对该 usage 的底层缓冲
     * 改为 3 槽按帧轮转（ring buffer 标准做法）：帧 N 写槽 A 且 blit 读槽 A，
     * 帧 N+1 写槽 B……SYNC_MODE=3 下 GPU 落后 ≤2 帧 < 3 槽，槽复用前必已读完，
     * 零等待消除竞争。非 staging（顶点/索引/arena/chunkFades，被 tessellation
     * 持引用）保持单一缓冲语义不变——换槽会错位。
     */
    @Environment(EnvType.CLIENT)
    public static final class MetalGlBufferEntry {
        /** staging ring 深度（须 > SYNC_MODE 最大超前帧数 2）。 */
        private static final int STAGING_RINGS = 3;
        private final int handle;
        @Nullable
        private MetalGpuBuffer buffer;
        private long size;
        private int usage;
        private boolean immutable;
        // ---- staging ring 状态 ----
        @Nullable
        private MetalGpuBuffer[] ring;
        @Nullable
        private long[] ringSizes;
        @Nullable
        private int[] ringUsages;
        private long lastRingFrame = -1L;
        private int ringSlot;

        MetalGlBufferEntry(final int handle) {
            this.handle = handle;
        }

        public static MetalGlBufferEntry create(final int handle) {
            return new MetalGlBufferEntry(handle);
        }

        /** STREAM_COPY（FallbackStagingBuffer 中转缓冲）的 Minecraft usage 组合（SodiumUsageMapper 实证）。 */
        public static boolean isStagingUsage(final int usage) {
            return usage == (GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_HINT_CLIENT_STORAGE);
        }

        /** 槽位决策纯函数：同帧保持当前槽，跨帧轮转。 */
        static int nextRingSlot(final int slot, final long lastFrame, final long frame) {
            return frame == lastFrame ? slot : (slot + 1) % STAGING_RINGS;
        }

        public int handle() {
            return this.handle;
        }

        @Nullable
        public MetalGpuBuffer buffer() {
            return this.ring != null ? this.ring[this.ringSlot] : this.buffer;
        }

        public long size() {
            return this.ring != null ? this.ringSizes[this.ringSlot] : this.size;
        }

        public int usage() {
            return this.ring != null ? this.ringUsages[this.ringSlot] : this.usage;
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
            if (isStagingUsage(usage)) {
                this.ensureStagingSlot();
                MetalGpuBuffer cur = this.ring[this.ringSlot];
                // P4：grow-only 复用（staging 上传是整写语义——大 buffer 复用安全，尺寸只增不减）。
                // 精确尺寸匹配曾致 6498 次重分配/60s（section mesh 尺寸离散，pool 仅 36% hit）——
                // 上传风暴期渲染线程 CPU 突发 + 分配 churn。复用时不重写旧内容（整写覆盖）。
                if (cur != null && cur.size() >= size && this.ringUsages[this.ringSlot] == usage) {
                    this.ringSizes[this.ringSlot] = cur.size();
                    return;
                }
                if (cur != null) {
                    cur.close();
                }
                this.ring[this.ringSlot] = new MetalGpuBuffer(MetalBackend.activeDevice(), usage, size);
                this.ringSizes[this.ringSlot] = size;
                this.ringUsages[this.ringSlot] = usage;
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

        /** staging 模式：帧边界信号（currentFrameIndex 变化）→ 轮转槽位，惰性建 ring 数组。 */
        private void ensureStagingSlot() {
            long frame = MetalCommandEncoder.currentFrameIndex();
            if (frame != this.lastRingFrame) {
                long oldFrame = this.lastRingFrame;
                this.lastRingFrame = frame;
                this.ringSlot = nextRingSlot(this.ringSlot, oldFrame, frame);
                if (this.ring == null) {
                    this.ring = new MetalGpuBuffer[STAGING_RINGS];
                    this.ringSizes = new long[STAGING_RINGS];
                    this.ringUsages = new int[STAGING_RINGS];
                }
            }
        }

        /**
         * 覆写式上传时按需重建（Sodium uploadData 语义 = glBufferData 重新分配）。
         * 与 ensureAllocated 的区别：强制丢弃旧内容（避免同 size 复用时的
         * 陈旧数据残留语义依赖）。
         */
        public void reallocate(final long size, final int usage) {
            if (isStagingUsage(usage)) {
                // staging：强制丢弃当前槽后重建（旧槽已入队/待读的由 ring 语义保护）
                if (this.ring != null && this.ring[this.ringSlot] != null) {
                    this.ring[this.ringSlot].close();
                    this.ring[this.ringSlot] = null;
                }
                this.ensureAllocated(size, usage);
                return;
            }
            this.dispose();
            this.size = 0L;
            this.ensureAllocated(size, usage);
        }

        /** 释放底层缓冲（帧末销毁队列回收），保留句柄注册（deleteBuffer 才 remove）。 */
        public void dispose() {
            if (this.ring != null) {
                for (int i = 0; i < STAGING_RINGS; i++) {
                    if (this.ring[i] != null) {
                        this.ring[i].close();
                    }
                }
                this.ring = null;
                this.ringSizes = null;
                this.ringUsages = null;
                this.lastRingFrame = -1L;
                return;
            }
            if (this.buffer != null) {
                this.buffer.close();
                this.buffer = null;
            }
        }
    }
}
