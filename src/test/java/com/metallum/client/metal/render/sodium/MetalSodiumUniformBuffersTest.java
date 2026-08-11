package com.metallum.client.metal.render.sodium;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P2.5 per-pass uniform ring 组决策纯函数测试（无 Metal 原生依赖）。
 * 覆盖：帧号 → 组映射、3 组回绕、负值防御。
 */
class MetalSodiumUniformBuffersTest {

    @Test
    void mapsFrameToGroup() {
        assertEquals(0, MetalSodiumUniformBuffers.groupForFrame(0L));
        assertEquals(1, MetalSodiumUniformBuffers.groupForFrame(1L));
        assertEquals(2, MetalSodiumUniformBuffers.groupForFrame(2L));
        assertEquals(2, MetalSodiumUniformBuffers.groupForFrame(5L));
    }

    @Test
    void wrapsEveryThreeFrames() {
        assertEquals(0, MetalSodiumUniformBuffers.groupForFrame(3L));
        assertEquals(1, MetalSodiumUniformBuffers.groupForFrame(4L));
        assertEquals(0, MetalSodiumUniformBuffers.groupForFrame(9L));
        assertEquals(1, MetalSodiumUniformBuffers.groupForFrame(15L + 1L));
    }

    @Test
    void negativeFrameFallsBackToGroupZero() {
        assertEquals(0, MetalSodiumUniformBuffers.groupForFrame(-1L));
        assertEquals(0, MetalSodiumUniformBuffers.groupForFrame(Long.MIN_VALUE));
    }
}
