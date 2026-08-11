package com.metallum.client.metal.render.sodium;

import com.mojang.blaze3d.buffers.GpuBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MetalGlBufferRegistry 句柄表纯逻辑测试（无 Metal 原生依赖）。
 * 覆盖：句柄分配起点/递增、注册-查找-移除闭环、P2 staging ring 槽位决策。
 */
class MetalGlBufferRegistryTest {

    @Test
    void nextHandleStartsAtOneAndIncrements() {
        int first = MetalGlBufferRegistry.nextHandle();
        assertEquals(1, first);
        assertEquals(2, MetalGlBufferRegistry.nextHandle());
        assertEquals(3, MetalGlBufferRegistry.nextHandle());
    }

    @Test
    void putGetRemoveRoundTrip() {
        int handle = MetalGlBufferRegistry.nextHandle();
        MetalGlBufferRegistry.MetalGlBufferEntry entry = MetalGlBufferRegistry.MetalGlBufferEntry.create(handle);
        assertNull(MetalGlBufferRegistry.get(handle));

        MetalGlBufferRegistry.put(handle, entry);
        assertSame(entry, MetalGlBufferRegistry.get(handle));
        assertEquals(handle, entry.handle());

        assertSame(entry, MetalGlBufferRegistry.remove(handle));
        assertNull(MetalGlBufferRegistry.get(handle));
    }

    @Test
    void entryDefaultsAreUnallocated() {
        MetalGlBufferRegistry.MetalGlBufferEntry entry = MetalGlBufferRegistry.MetalGlBufferEntry.create(42);
        assertNull(entry.buffer());
        assertEquals(0L, entry.size());
        assertEquals(0, entry.usage());
        org.junit.jupiter.api.Assertions.assertFalse(entry.isImmutable());
    }

    @Test
    void removeOfMissingHandleIsNull() {
        assertNull(MetalGlBufferRegistry.remove(999_999));
    }

    // ---- P2 staging ring 槽位决策（纯函数，无 Metal 依赖）----

    @Test
    void stagingUsageDetection() {
        int staging = GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_HINT_CLIENT_STORAGE;
        assertTrue(MetalGlBufferRegistry.MetalGlBufferEntry.isStagingUsage(staging));
        assertFalse(MetalGlBufferRegistry.MetalGlBufferEntry.isStagingUsage(GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST));
        assertFalse(MetalGlBufferRegistry.MetalGlBufferEntry.isStagingUsage(0));
    }

    @Test
    void nextRingSlotStaysWithinSameFrame() {
        assertEquals(1, MetalGlBufferRegistry.MetalGlBufferEntry.nextRingSlot(1, 7L, 7L));
        assertEquals(2, MetalGlBufferRegistry.MetalGlBufferEntry.nextRingSlot(2, 42L, 42L));
    }

    @Test
    void nextRingSlotRotatesAcrossFrames() {
        assertEquals(1, MetalGlBufferRegistry.MetalGlBufferEntry.nextRingSlot(0, 7L, 8L));
        assertEquals(2, MetalGlBufferRegistry.MetalGlBufferEntry.nextRingSlot(1, 8L, 9L));
    }

    @Test
    void nextRingSlotWraps() {
        assertEquals(0, MetalGlBufferRegistry.MetalGlBufferEntry.nextRingSlot(2, 9L, 10L));
    }

    @Test
    void nextRingSlotTreatsFirstFrameAsRotation() {
        assertEquals(1, MetalGlBufferRegistry.MetalGlBufferEntry.nextRingSlot(0, -1L, 0L));
    }
}
