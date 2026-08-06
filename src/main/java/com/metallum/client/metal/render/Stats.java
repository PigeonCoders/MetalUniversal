package com.metallum.client.metal.render;

import com.mojang.blaze3d.buffers.GpuBuffer;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class Stats {
    private static final AtomicLong CREATED_BUFFERS = new AtomicLong();

    private static final ConcurrentHashMap<Integer, UsageStats> USAGE_STATS = new ConcurrentHashMap<>();

    private static final class UsageStats {
        final AtomicLong count = new AtomicLong();
        final AtomicLong requestedBytes = new AtomicLong();
        final AtomicLong allocatedBytes = new AtomicLong();
    }

    public static void recordUsage(int usage, long requestedSize, long allocatedSize) {
        UsageStats stats = USAGE_STATS.computeIfAbsent(usage, k -> new UsageStats());

        stats.count.incrementAndGet();
        stats.requestedBytes.addAndGet(requestedSize);
        stats.allocatedBytes.addAndGet(allocatedSize);

        CREATED_BUFFERS.incrementAndGet();
    }

    /**
     * 汇总当前分配统计（total 与 per-usage 明细），供节流式诊断日志输出。
     * 仅在新建分配时记录（pooled 复用与外部 wrapped handle 不计入）。
     */
    public static String snapshot() {
        long totalCount = 0L;
        long totalRequested = 0L;
        long totalAllocated = 0L;
        StringBuilder sb = new StringBuilder(256);
        sb.append("buffers total=").append(CREATED_BUFFERS.get());

        for (java.util.Map.Entry<Integer, UsageStats> entry : USAGE_STATS.entrySet()) {
            int usage = entry.getKey();
            UsageStats stats = entry.getValue();
            long count = stats.count.get();
            long requested = stats.requestedBytes.get();
            long allocated = stats.allocatedBytes.get();
            totalCount += count;
            totalRequested += requested;
            totalAllocated += allocated;
            sb.append(String.format(" | usage=0x%x count=%d req=%.1fMB alloc=%.1fMB",
                    usage, count, requested / 1048576.0, allocated / 1048576.0));
        }

        sb.append(" | sum count=").append(totalCount)
                .append(String.format(" req=%.1fMB alloc=%.1fMB", totalRequested / 1048576.0, totalAllocated / 1048576.0));
        return sb.toString();
    }
}
