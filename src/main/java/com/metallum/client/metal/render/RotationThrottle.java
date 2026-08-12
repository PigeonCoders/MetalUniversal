package com.metallum.client.metal.render;

/**
 * P25 转动节流快照（静态——渲染线程单线程，无并发）。
 *
 * <p>GameRenderer.render HEAD 每帧写入当前相机（pitch/yaw 2° 量化桶 + 块级位置）；
 * SodiumWorldRendererRotationMixin 在 setupTerrain HEAD 判断"桶内转动"（<2°
 * 且未跨块）→ 同步 Sodium 的 lastCameraPitch/Yaw 让 setupTerrain 的旋转比较
 * 认为"没转"→ markGraphDirty 不触发 → findVisible 全量 BFS/渲染列表重建/
 * batch 失效重建全部跳过（纯绘制帧——帧时间稳，锁 60Hz vsync 不掉帧）。
 *
 * <p>位置用块级比较（跨 1 块才算变化）——partialTick 插值的亚块微动不触发
 * 节流失效；玩家跨块移动/跨 2° 桶时立即恢复重算（不滞后）。
 */
public final class RotationThrottle {
    // P28：固定 10° → 角速度自适应桶（慢转大桶重算稀——丝滑；快转小桶——低滞后）。
    // 档位：<30°/s → 30° 桶（慢转每 ~1s 重算一次）；30-120°/s → 10° 桶；
    // >120°/s → 5° 桶（甩视角低 pop-in）。角速度 EMA 估计（帧差/帧间隔）。
    private static final double BUCKET_SLOW = 30.0;
    private static final double BUCKET_MEDIUM = 10.0;
    private static final double BUCKET_FAST = 5.0;
    private static double curPitch;
    private static double curYaw;
    private static long curBlockX;
    private static long curBlockZ;
    private static long curNanos;
    private static double prevYaw;
    private static long prevNanos;
    private static boolean hasPrev;
    private static double speedEstimate; // °/s（EMA）
    private static int lastBucketX = Integer.MIN_VALUE;
    private static int lastBucketY = Integer.MIN_VALUE;
    private static double lastBucketDegrees;
    private static long lastBlockX;
    private static long lastBlockZ;
    // P30（判别）：节流命中计数（5s 节流输出）——纯转动帧 hits 高 = 节流生效；
    // hits≈0 = 节流失效（快照时序/比较链问题）。
    private static long throttledHits;
    private static long throttledSkips;
    private static long throttledReportNanos;

    private RotationThrottle() {
    }

    /** 每帧相机快照（GameRenderer.render HEAD——先于 Sodium setupTerrain）。 */
    public static void snapshot(final float pitch, final float yaw, final double posX, final double posZ) {
        long now = System.nanoTime();
        if (hasPrev) {
            double elapsed = (now - prevNanos) / 1_000_000_000.0;
            if (elapsed > 0.0 && elapsed < 0.5) {
                double degPerSec = Math.abs(yaw - prevYaw) / elapsed;
                speedEstimate = speedEstimate * 0.9 + degPerSec * 0.1;
            }
        } else {
            hasPrev = true;
        }
        prevYaw = yaw;
        prevNanos = now;
        curPitch = pitch;
        curYaw = yaw;
        curBlockX = (long) Math.floor(posX);
        curBlockZ = (long) Math.floor(posZ);
        curNanos = now;
    }

    /** 当前档位桶（°）。 */
    private static double bucketDegrees() {
        double s = speedEstimate;
        if (s < 30.0) {
            return BUCKET_SLOW;
        }
        if (s < 120.0) {
            return BUCKET_MEDIUM;
        }
        return BUCKET_FAST;
    }

    /** 桶内转动且未跨块 → true（应节流——纯小角度转动）。 */
    public static boolean shouldThrottle() {
        double bucket = bucketDegrees();
        int bucketX = (int) Math.floor(curPitch / bucket);
        int bucketY = (int) Math.floor(curYaw / bucket);
        boolean throttled = bucket == lastBucketDegrees && bucketX == lastBucketX && bucketY == lastBucketY
                && curBlockX == lastBlockX && curBlockZ == lastBlockZ;
        if (throttled) {
            throttledHits++;
        } else {
            throttledSkips++;
        }
        long now = System.nanoTime();
        if (throttledHits + throttledSkips >= 300 && now - throttledReportNanos > 5_000_000_000L) {
            throttledReportNanos = now;
            DiagLog.log("[diag] rot-throttle hits=%d skip=%d", throttledHits, throttledSkips);
            throttledHits = 0;
            throttledSkips = 0;
        }
        return throttled;
    }

    /** 跨桶/跨块时记录当前快照（节流窗口对齐上次重算状态）。 */
    public static void record() {
        double bucket = bucketDegrees();
        lastBucketDegrees = bucket;
        lastBucketX = (int) Math.floor(curPitch / bucket);
        lastBucketY = (int) Math.floor(curYaw / bucket);
        lastBlockX = curBlockX;
        lastBlockZ = curBlockZ;
    }

    public static double currentPitch() {
        return curPitch;
    }

    public static double currentYaw() {
        return curYaw;
    }
}
