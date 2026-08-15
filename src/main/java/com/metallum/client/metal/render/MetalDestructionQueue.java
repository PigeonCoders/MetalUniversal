package com.metallum.client.metal.render;

import com.metallum.Metallum;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.ArrayList;
import java.util.List;

@Environment(EnvType.CLIENT)
final class MetalDestructionQueue {
    private final List<Runnable>[] queues;
    private int currentQueueIndex;
    /** P-hang 诊断：rotate 调用计数（无帧上下文，用作释放时序日志的近似帧号）。 */
    private long rotateCount;

    @SuppressWarnings("unchecked")
    MetalDestructionQueue(final int queueCount) {
        this.queues = (List<Runnable>[]) new List<?>[queueCount];
        for (int i = 0; i < queueCount; i++) {
            this.queues[i] = new ArrayList<>();
        }
    }

    void add(final Runnable destroyAction) {
        if (destroyAction == null) {
            return;
        }
        this.queues[this.currentQueueIndex].add(destroyAction);
    }

    void rotate() {
        this.rotateCount++;
        this.currentQueueIndex = (this.currentQueueIndex + 1) % this.queues.length;
        List<Runnable> toDestroy = this.queues[this.currentQueueIndex];
        this.queues[this.currentQueueIndex] = new ArrayList<>();
        // P-hang 诊断：真正的 native 释放时刻（rotate 轮转 3 帧后才执行）。
        // 与 queueRelease 入队日志按 handle 交叉比对：入队帧号 vs 释放帧号。
        if (!toDestroy.isEmpty()) {
            DiagLog.log("[diag] destroyQueue release frame=%d slot=%d actions=%d",
                    this.rotateCount, this.currentQueueIndex, toDestroy.size());
        }
        for (Runnable destroyAction : toDestroy) {
            try {
                destroyAction.run();
            } catch (Exception e) {
                Metallum.LOGGER.error("[metallum] Destroy action threw an exception; resource may have leaked", e);
            }
        }
    }

    void close() {
        for (int i = 0; i < this.queues.length; i++) {
            this.rotate();
        }
    }
}
