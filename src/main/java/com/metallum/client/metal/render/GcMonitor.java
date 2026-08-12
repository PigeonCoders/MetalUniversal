package com.metallum.client.metal.render;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * P30（判别）：GC 暂停差分（GarbageCollectorMXBean——核心 SE API，零风险；
 * try/catch 防御——JRE 缺模块时降级为 0）。
 *
 * <p>spike 行加 gcPause 字段：转动期尖峰与 GC 对齐（gcPause ≈ spike 时长）→
 * GC 致因；gcPause≈0 → 转动重算/上传/其他 CPU。G1 的 collectionTime 含并发
 * 标记时间——判别时以 gcCount 差分（每次 STW +1）交叉验证。
 */
public final class GcMonitor {
    private static final List<GarbageCollectorMXBean> BEANS;
    private static long lastCount;
    private static long lastTimeMs;

    static {
        List<GarbageCollectorMXBean> beans;
        try {
            beans = ManagementFactory.getGarbageCollectorMXBeans();
        } catch (Throwable t) {
            beans = List.of();
        }
        BEANS = beans;
        for (GarbageCollectorMXBean bean : BEANS) {
            try {
                lastCount += bean.getCollectionCount();
                lastTimeMs += bean.getCollectionTime();
            } catch (Throwable ignored) {
                // 防御：bean 不可用时跳过
            }
        }
    }

    private GcMonitor() {
    }

    /** 自上次调用以来的 GC 暂停毫秒（差分——渲染线程单线程调用）。 */
    public static long pauseDeltaMs() {
        long count = 0;
        long timeMs = 0;
        for (GarbageCollectorMXBean bean : BEANS) {
            try {
                count += bean.getCollectionCount();
                timeMs += bean.getCollectionTime();
            } catch (Throwable ignored) {
                // 防御
            }
        }
        long deltaCount = count - lastCount;
        long deltaMs = timeMs - lastTimeMs;
        lastCount = count;
        lastTimeMs = timeMs;
        // G1 collectionTime 含并发标记——STW 暂停估算：并发标记占比小（~10-20%）；
        // 判别以数量级对齐为准（gcPause ≈ spike 时长 vs ≈0）。
        return deltaCount > 0 ? deltaMs : 0L;
    }
}
