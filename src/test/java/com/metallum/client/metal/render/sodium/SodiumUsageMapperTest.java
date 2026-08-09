package com.metallum.client.metal.render.sodium;

import com.mojang.blaze3d.buffers.GpuBuffer;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBufferMapFlags;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBufferStorageFlags;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBufferUsage;
import net.caffeinemc.mods.sodium.client.gl.util.EnumBitField;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SodiumUsageMapper 映射逻辑测试（纯 Java，无 GL/Metal 依赖）。
 * 语义契约：含 MAP/HINT_CLIENT_STORAGE 位 → Shared（CPU 可直达）；
 * 纯 VERTEX/COPY → Private（staging+blit 上传）。
 */
class SodiumUsageMapperTest {

    @Test
    void staticDrawMapsToPrivateVertexBuffer() {
        int usage = SodiumUsageMapper.toMinecraftUsage(GlBufferUsage.STATIC_DRAW);
        assertEquals(GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, usage);
    }

    @Test
    void streamCopyMapsToSharedStaging() {
        int usage = SodiumUsageMapper.toMinecraftUsage(GlBufferUsage.STREAM_COPY);
        assertEquals(GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_HINT_CLIENT_STORAGE, usage);
        assertTrue((usage & GpuBuffer.USAGE_HINT_CLIENT_STORAGE) != 0);
    }

    @Test
    void allUsagesCovered() {
        for (GlBufferUsage usage : GlBufferUsage.values()) {
            int mapped = SodiumUsageMapper.toMinecraftUsage(usage);
            assertTrue(mapped != 0, "usage " + usage + " mapped to zero");
        }
    }

    @Test
    void mapFlagsToUsage() {
        int usage = SodiumUsageMapper.toMapUsage(
                EnumBitField.of(GlBufferMapFlags.WRITE, GlBufferMapFlags.READ));
        assertEquals(GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_MAP_READ, usage);
    }

    @Test
    void writeOnlyMapFlagsToUsage() {
        int usage = SodiumUsageMapper.toMapUsage(EnumBitField.of(GlBufferMapFlags.WRITE));
        assertEquals(GpuBuffer.USAGE_MAP_WRITE, usage);
    }

    @Test
    void storageFlagsToUsage() {
        int usage = SodiumUsageMapper.toStorageUsage(
                EnumBitField.of(GlBufferStorageFlags.PERSISTENT,
                        GlBufferStorageFlags.MAP_WRITE,
                        GlBufferStorageFlags.CLIENT_STORAGE));
        assertEquals(GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_HINT_CLIENT_STORAGE, usage);
    }
}
