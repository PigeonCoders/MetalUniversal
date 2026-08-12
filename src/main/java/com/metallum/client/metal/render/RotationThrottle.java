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
    private static double curPitch;
    private static double curYaw;
    private static long curBlockX;
    private static long curBlockZ;
    private static int lastBucketX = Integer.MIN_VALUE;
    private static int lastBucketY = Integer.MIN_VALUE;
    private static long lastBlockX;
    private static long lastBlockZ;

    private RotationThrottle() {
    }

    /** 每帧相机快照（GameRenderer.render HEAD——先于 Sodium setupTerrain）。 */
    public static void snapshot(final float pitch, final float yaw, final double posX, final double posZ) {
        curPitch = pitch;
        curYaw = yaw;
        curBlockX = (long) Math.floor(posX);
        curBlockZ = (long) Math.floor(posZ);
    }

    /** 桶内转动且未跨块 → true（应节流——纯小角度转动）。 */
    public static boolean shouldThrottle() {
        int bucketX = (int) Math.floor(curPitch / 2.0);
        int bucketY = (int) Math.floor(curYaw / 2.0);
        return bucketX == lastBucketX && bucketY == lastBucketY
                && curBlockX == lastBlockX && curBlockZ == lastBlockZ;
    }

    /** 跨桶/跨块时记录当前快照（节流窗口对齐上次重算状态）。 */
    public static void record() {
        lastBucketX = (int) Math.floor(curPitch / 2.0);
        lastBucketY = (int) Math.floor(curYaw / 2.0);
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
