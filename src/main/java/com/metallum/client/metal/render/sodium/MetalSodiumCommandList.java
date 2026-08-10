package com.metallum.client.metal.render.sodium;

import com.metallum.Metallum;
import com.metallum.client.metal.render.MetalBackend;
import com.metallum.client.metal.render.MetalCommandEncoder;
import com.metallum.client.metal.render.MetalGpuBuffer;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import net.caffeinemc.mods.sodium.client.gl.array.GlVertexArray;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBuffer;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBufferMapFlags;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBufferMapping;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBufferStorageFlags;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBufferTarget;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBufferUsage;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlImmutableBuffer;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlMutableBuffer;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.gl.device.DrawCommandList;
import net.caffeinemc.mods.sodium.client.gl.sync.GlFence;
import net.caffeinemc.mods.sodium.client.gl.tessellation.GlPrimitiveType;
import net.caffeinemc.mods.sodium.client.gl.tessellation.GlTessellation;
import net.caffeinemc.mods.sodium.client.gl.tessellation.TessellationBinding;
import net.caffeinemc.mods.sodium.client.gl.util.EnumBitField;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.NonNull;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

/**
 * Sodium CommandList 的 Metal 实现（阶段 1：Buffer 层）。
 *
 * <p>所有 GPU 操作复用 Metal 主命令编码器（MetalBackend.activeDevice()
 * 的单例 encoder，与 MC 渲染流程共用 commandBuffer/fence/transientMemory），
 * 上传/拷贝走现成的 transient staging + blit 路径（与 writeToBuffer 同构）。
 *
 * <p>tessellation 三方法（createTessellation/beginTessellating/deleteTessellation）
 * 阶段 3 已实现：MetalSodiumTessellation 引用容器 + MetalSodiumDrawCommandList
 * 状态应用与 multiDraw 映射；激活状态由 MetalSodiumShaderChunkRenderer.begin()
 * 注册（activeSodiumState）。
 */
@Environment(EnvType.CLIENT)
public final class MetalSodiumCommandList implements CommandList {

    @Override
    public GlMutableBuffer createMutableBuffer() {
        // 构造经 GlBufferMixin 拦截 glGenBuffers，句柄由 MetalGlBufferRegistry 分配
        return new GlMutableBuffer();
    }

    @Override
    public GlImmutableBuffer createImmutableBuffer(final long bufferSize, final EnumBitField<GlBufferStorageFlags> flags) {
        GlImmutableBuffer buffer = new GlImmutableBuffer(flags);
        MetalGlBufferRegistry.MetalGlBufferEntry entry = entryOf(buffer);
        entry.markImmutable();
        if (bufferSize > 0L) {
            // GL 语义：glBufferStorage 立即分配；Metal 无 immutable 概念，立即落地为 Shared/Private
            entry.ensureAllocated(bufferSize, SodiumUsageMapper.toStorageUsage(flags));
        }
        return buffer;
    }

    @Override
    public void allocateStorage(final GlMutableBuffer buffer, final long bufferSize, final GlBufferUsage usage) {
        entryOf(buffer).ensureAllocated(bufferSize, SodiumUsageMapper.toMinecraftUsage(usage));
        buffer.setSize(bufferSize);
    }

    @Override
    public void uploadData(final GlMutableBuffer glBuffer, final ByteBuffer byteBuffer, final GlBufferUsage usage) {
        MetalGlBufferRegistry.MetalGlBufferEntry entry = entryOf(glBuffer);
        int length = byteBuffer.remaining();
        entry.ensureAllocated(length, SodiumUsageMapper.toMinecraftUsage(usage));
        glBuffer.setSize(length);
        writeBuffer(entry, 0L, byteBuffer.duplicate());
    }

    @Override
    public void uploadDataToOffset(final GlMutableBuffer glBuffer, final int offset, final long pointer, final int size) {
        MetalGlBufferRegistry.MetalGlBufferEntry entry = entryOf(glBuffer);
        // glBufferSubData 语义：仅当目标区域超出已分配范围时扩（不缩小现有分配）
        if (entry.buffer() == null || entry.size() < (long) offset + size) {
            int usage = entry.usage() != 0 ? entry.usage() : (GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST);
            entry.ensureAllocated((long) offset + size, usage);
        }
        ByteBuffer src = MemorySegment.ofAddress(pointer).reinterpret(size).asByteBuffer();
        writeBuffer(entry, offset, src);
    }

    @Override
    public void copyBufferSubData(final GlBuffer src, final GlBuffer dst, final long readOffset, final long writeOffset, final long bytes) {
        MetalGpuBuffer srcBuffer = requireBuffer(src);
        MetalGpuBuffer dstBuffer = requireBuffer(dst);
        encoder().copyToBuffer(srcBuffer.slice(readOffset, bytes), dstBuffer.slice(writeOffset, bytes));
    }

    @Override
    public void deleteBuffer(final GlBuffer buffer) {
        MetalGlBufferRegistry.MetalGlBufferEntry entry = MetalGlBufferRegistry.remove(buffer.handle());
        if (entry == null) {
            return;
        }
        if (buffer.getActiveMapping() != null) {
            buffer.getActiveMapping().dispose();
            buffer.setActiveMapping(null);
        }
        entry.dispose();
        buffer.invalidateHandle();
    }

    @Override
    public GlBufferMapping mapBuffer(final GlBuffer buffer, final long offset, final long length, final EnumBitField<GlBufferMapFlags> flags) {
        if (buffer.getActiveMapping() != null) {
            throw new IllegalStateException("Buffer is already mapped");
        }
        if (flags.contains(GlBufferMapFlags.PERSISTENT) && !(buffer instanceof GlImmutableBuffer)) {
            throw new IllegalStateException("Tried to map mutable buffer as persistent");
        }
        if (buffer instanceof GlImmutableBuffer) {
            EnumBitField<GlBufferStorageFlags> storageFlags = ((GlImmutableBuffer) buffer).getFlags();
            if (flags.contains(GlBufferMapFlags.PERSISTENT) && !storageFlags.contains(GlBufferStorageFlags.PERSISTENT)) {
                throw new IllegalArgumentException("Tried to map non-persistent buffer as persistent");
            }
            if (flags.contains(GlBufferMapFlags.WRITE) && !storageFlags.contains(GlBufferStorageFlags.MAP_WRITE)) {
                throw new IllegalStateException("Tried to map non-writable buffer as writable");
            }
            if (flags.contains(GlBufferMapFlags.READ) && !storageFlags.contains(GlBufferStorageFlags.MAP_READ)) {
                throw new IllegalStateException("Tried to map non-readable buffer as readable");
            }
        }

        MetalGlBufferRegistry.MetalGlBufferEntry entry = entryOf(buffer);
        // Metal 映射 = Shared 缓冲的 CPU 直写。未分配 / 非 CPU 可访问（Private，如
        // SharedQuadIndexBuffer 先 allocateStorage 后 map）→ 重建为 Shared。
        // 数据丢弃仅在 INVALIDATE_BUFFER 下合法；Sodium 0.8.13 的 Private→map 调用点
        // 均带 INVALIDATE_BUFFER（SharedQuadIndexBuffer.grow），PERSISTENT 路径
        // （GlBufferStreamer）恒经 createImmutableBuffer(MAP_WRITE) 直接建 Shared，
        // 无"无 INVALIDATE 的已分配 Private 映射"，防御性记录日志。
        boolean needRebuild = entry.buffer() == null
                || !entry.buffer().isCpuAccessible()
                || entry.size() < offset + length;
        if (needRebuild && entry.buffer() != null
                && !flags.contains(GlBufferMapFlags.INVALIDATE_BUFFER)
                && !flags.contains(GlBufferMapFlags.PERSISTENT)) {
            Metallum.LOGGER.warn("[metallum] mapBuffer on non-CPU-accessible buffer without INVALIDATE_BUFFER (handle={}, size={})", buffer.handle(), entry.size());
        }
        if (needRebuild) {
            entry.ensureAllocated(offset + length, SodiumUsageMapper.toMapUsage(flags));
        }

        ByteBuffer map = entry.buffer().sliceStorage(offset, length);
        GlBufferMapping mapping = new GlBufferMapping(buffer, map);
        buffer.setActiveMapping(mapping);
        return mapping;
    }

    @Override
    public void unmap(final GlBufferMapping map) {
        this.checkMapDisposed(map);
        // Metal Shared 内存无 unmap 语义：CPU 写入即时可见，仅清状态
        map.getBufferObject().setActiveMapping(null);
        map.dispose();
    }

    @Override
    public void flushMappedRange(final GlBufferMapping map, final int offset, final int length) {
        this.checkMapDisposed(map);
        // NO-OP：Shared 缓冲无需显式 flush
    }

    @Override
    public GlFence createFence() {
        // 防御性假实现：Metal 版 MappedStagingBuffer 未启用（DeviceFunctions→NONE），
        // Sodium 不会走持久映射路径；接口面先行返回不崩的实现。
        return new MetalSodiumFence();
    }

    @Override
    public void bindBuffer(final GlBufferTarget target, final GlBuffer buffer) {
        // NO-OP：Metal 无 GL 目标绑定状态
    }

    @Override
    public void bindVertexArray(final GlVertexArray array) {
        // NO-OP：Metal 无 VAO（阶段 3 顶点描述符在管线级声明）
    }

    @Override
    public void unbindVertexArray() {
        // NO-OP
    }

    @Override
    public void deleteVertexArray(final GlVertexArray vertexArray) {
        // NO-OP：GlVertexArray 无底层资源
    }

    @Override
    public void flush() {
        // NO-OP：上传即提交到主命令缓冲，无需显式 flush
    }

    @Override
    public GlTessellation createTessellation(final GlPrimitiveType primitiveType, final TessellationBinding[] bindings) {
        return new MetalSodiumTessellation(primitiveType, bindings);
    }

    @Override
    public void deleteTessellation(final GlTessellation tessellation) {
        // NO-OP：MetalSodiumTessellation 只是引用容器，buffer 归 arena 释放
    }

    @Override
    public DrawCommandList beginTessellating(final GlTessellation tessellation) {
        MetalSodiumActiveState state = activeSodiumState();
        if (state == null) {
            // SODIUM-ADAPT 时序守卫：begin() 注册激活状态、end() 清除；
            // 未注册即到达 = renderer 接线错误（fail-fast）
            throw new IllegalStateException("Sodium beginTessellating without active renderer state (renderer.begin() not called?)");
        }
        if (!(tessellation instanceof MetalSodiumTessellation metalTessellation)) {
            throw new IllegalStateException("Tessellation is not Metal-backed: " + tessellation.getClass().getName());
        }
        // fix11（uniform 覆写竞争）：9 个 per-pass uniform pass 内写一次（幂等）；
        // regionOffset/currentTime 每 region 从 transient 分配独立块（GPU 读各自块，
        // 不再被后续 region 覆写——iOS 实测曾整体上下偏移/随视角闪现）。
        state.uniformBuffers().uploadPassUniforms(state.shaderInterface());
        MetalSodiumUniformBuffers.RegionUniformSlices regionUniforms =
                state.uniformBuffers().allocateRegionUniforms(state.shaderInterface());
        return new MetalSodiumDrawCommandList((MetalCommandEncoder) encoder(), metalTessellation, state, regionUniforms);
    }

    // ---- SODIUM-ADAPT：激活状态注册（阶段 3） ----
    // MetalSodiumShaderChunkRenderer.begin()/end() 维护；渲染线程单线程访问，
    // volatile 仅为防御（严格讲无需同步）。

    private static volatile MetalSodiumActiveState activeSodiumState;

    public static void setActiveSodiumState(final MetalSodiumActiveState state) {
        activeSodiumState = state;
    }

    public static void clearActiveSodiumState() {
        activeSodiumState = null;
    }

    @org.jspecify.annotations.Nullable
    public static MetalSodiumActiveState activeSodiumState() {
        return activeSodiumState;
    }

    private void checkMapDisposed(final GlBufferMapping map) {
        if (map.isDisposed()) {
            throw new IllegalStateException("Buffer mapping is already disposed");
        }
    }

    private static MetalGlBufferRegistry.MetalGlBufferEntry entryOf(final GlBuffer buffer) {
        MetalGlBufferRegistry.MetalGlBufferEntry entry = MetalGlBufferRegistry.get(buffer.handle());
        if (entry == null) {
            throw new IllegalStateException("Sodium GlBuffer not registered (handle=" + buffer.handle() + ")");
        }
        return entry;
    }

    /** SODIUM-ADAPT：包可见供 MetalSodiumTessellation 复用（同包）。 */
    static MetalGpuBuffer requireBuffer(final GlBuffer buffer) {
        MetalGlBufferRegistry.MetalGlBufferEntry entry = entryOf(buffer);
        MetalGpuBuffer metal = entry.buffer();
        if (metal == null) {
            throw new IllegalStateException("Sodium GlBuffer not allocated (handle=" + buffer.handle() + ")");
        }
        return metal;
    }

    @NonNull
    private static CommandEncoder encoder() {
        return MetalBackend.activeDevice().createCommandEncoder();
    }

    /** 数据写入：CPU 可访问（Shared）直接写内存，否则 transient staging + blit。 */
    private static void writeBuffer(final MetalGlBufferRegistry.MetalGlBufferEntry entry, final long offset, final ByteBuffer data) {
        MetalGpuBuffer metal = entry.buffer();
        if (metal == null) {
            throw new IllegalStateException("Sodium GlBuffer not allocated (handle=" + entry.handle() + ")");
        }
        if (metal.isCpuAccessible()) {
            ByteBuffer dst = metal.currentStorage();
            dst.position(Math.toIntExact(offset));
            dst.put(data);
        } else {
            encoder().writeToBuffer(metal.slice(offset, data.remaining()), data);
        }
    }
}
