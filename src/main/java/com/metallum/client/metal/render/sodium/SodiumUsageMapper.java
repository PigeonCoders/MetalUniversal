package com.metallum.client.metal.render.sodium;

import com.mojang.blaze3d.buffers.GpuBuffer;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBufferMapFlags;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBufferStorageFlags;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBufferUsage;
import net.caffeinemc.mods.sodium.client.gl.util.EnumBitField;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Sodium GL 缓冲语义 → Metal（MC GpuBuffer.Usage 位）映射。
 *
 * <p>映射原则：MetalGpuBuffer 的 storage mode（Shared/Private）由 usage 位推导——
 * 含 MAP_READ/MAP_WRITE/HINT_CLIENT_STORAGE 之一 → Shared（CPU 可直达），否则
 * Private（GPU 专用，上传必须 staging + blit）。因此：
 * <ul>
 *   <li>STREAM_COPY（FallbackStagingBuffer 中转缓冲）→ Shared，CPU 直写</li>
 *   <li>STATIC_DRAW 等顶点/索引数据 → Private + COPY_DST（staging+blit 上传）</li>
 *   <li>STREAM_DRAW（GlBufferStreamer 目标）→ Private + COPY_DST（uploadDataToOffset blit）</li>
 *   <li>MAP_WRITE/MAP_READ 标志 → Shared（mapBuffer 路径）</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public final class SodiumUsageMapper {
    private SodiumUsageMapper() {
    }

    /** GlBufferUsage → MC GpuBuffer.Usage 位。 */
    public static int toMinecraftUsage(final GlBufferUsage usage) {
        return switch (usage) {
            case STREAM_DRAW, DYNAMIC_DRAW -> GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST;
            case STREAM_COPY -> GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_HINT_CLIENT_STORAGE;
            case STREAM_READ -> GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST;
            case STATIC_DRAW, DYNAMIC_COPY -> GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST;
            case STATIC_READ -> GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST;
            case STATIC_COPY -> GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC;
            case DYNAMIC_READ -> GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST;
        };
    }

    /** GlBufferMapFlags → MC usage 位（用于 mapBuffer 时按需分配 Shared 缓冲）。 */
    public static int toMapUsage(final EnumBitField<GlBufferMapFlags> flags) {
        int usage = 0;
        if (flags.contains(GlBufferMapFlags.READ)) {
            usage |= GpuBuffer.USAGE_MAP_READ;
        }
        if (flags.contains(GlBufferMapFlags.WRITE)) {
            usage |= GpuBuffer.USAGE_MAP_WRITE;
        }
        return usage;
    }

    /** GlBufferStorageFlags → MC usage 位（createImmutableBuffer 分配语义）。 */
    public static int toStorageUsage(final EnumBitField<GlBufferStorageFlags> flags) {
        int usage = 0;
        if (flags.contains(GlBufferStorageFlags.MAP_READ)) {
            usage |= GpuBuffer.USAGE_MAP_READ;
        }
        if (flags.contains(GlBufferStorageFlags.MAP_WRITE)) {
            usage |= GpuBuffer.USAGE_MAP_WRITE;
        }
        if (flags.contains(GlBufferStorageFlags.CLIENT_STORAGE)) {
            usage |= GpuBuffer.USAGE_HINT_CLIENT_STORAGE;
        }
        return usage;
    }
}
