package com.metallum.client.metal.render.sodium;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * MetalGlBufferRegistry 句柄表纯逻辑测试（无 Metal 原生依赖）。
 * 覆盖：句柄分配起点/递增、注册-查找-移除闭环。
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
}
