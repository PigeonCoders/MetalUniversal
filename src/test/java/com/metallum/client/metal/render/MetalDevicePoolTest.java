package com.metallum.client.metal.render;

import com.metallum.client.metal.render.MetalDevice.PooledBuffer;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * P21 pool 分桶：512KB 粒度桶号 + 桶内"≥ 请求的最小可用"选择逻辑。
 * Sodium arena resize 序列（×1.5）多数落同桶 → 复用、新建风暴下降。
 */
class MetalDevicePoolTest {

    private static MemorySegment seg(final long size) {
        return Arena.ofShared().allocate(size);
    }

    @Test
    void bucketForArenaResizeSequenceSharesBuckets() {
        // Sodium estimateNewCapacity ≈ ×1.5 序列（初始 193536）
        long[] seq = {193536L, 290304L, 435456L, 653184L, 979776L, 1469664L};
        long[] buckets = {0, 0, 0, 1, 1, 2};
        for (int i = 0; i < seq.length; i++) {
            assertEquals(buckets[i], MetalDevice.bucketFor(seq[i]),
                    "size " + seq[i] + " bucket mismatch");
        }
    }

    @Test
    void bucketBoundaryAt512K() {
        assertEquals(0, MetalDevice.bucketFor(512L * 1024 - 1));
        assertEquals(1, MetalDevice.bucketFor(512L * 1024));
        assertEquals(1, MetalDevice.bucketFor(1024L * 1024 - 1));
        assertEquals(2, MetalDevice.bucketFor(1024L * 1024));
    }

    @Test
    void bestFitSelectsSmallestSufficient() {
        MemorySegment s290 = seg(290304L);
        MemorySegment s435 = seg(435456L);
        MemorySegment s500 = seg(500000L);
        Deque<PooledBuffer> bucket = new ArrayDeque<>();
        bucket.push(new PooledBuffer(s290, 290304L));
        bucket.push(new PooledBuffer(s435, 435456L));
        bucket.push(new PooledBuffer(s500, 500000L));

        PooledBuffer best = MetalDevice.chooseBestFit(bucket, 435456L);
        assertNotNull(best);
        assertEquals(435456L, best.size(), "should pick exactly 435456, not 500000");
    }

    @Test
    void bestFitRejectsTooSmall() {
        MemorySegment s189 = seg(193536L);
        Deque<PooledBuffer> bucket = new ArrayDeque<>();
        bucket.push(new PooledBuffer(s189, 193536L));
        // 435K 请求：桶内只有 189K（不足）→ 无可用（不误用越界）
        assertNull(MetalDevice.chooseBestFit(bucket, 435456L));
    }

    @Test
    void bestFitEmptyBucketReturnsNull() {
        assertNull(MetalDevice.chooseBestFit(new ArrayDeque<>(), 1000L));
    }
}
