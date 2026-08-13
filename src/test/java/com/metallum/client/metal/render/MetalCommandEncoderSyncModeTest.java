package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P1 判别开关读取逻辑测试：-Dmetallum.sync 三态解析（1=串行 / 2=超前1帧 / 3=滑动窗口），
 * 非法值/缺失回退 1。防止 Amethyst 注入失败时静默跑错模式。
 */
class MetalCommandEncoderSyncModeTest {

    private static int parse() throws Exception {
        Method m = MetalCommandEncoder.class.getDeclaredMethod("parseSyncMode");
        m.setAccessible(true);
        return (Integer) m.invoke(null);
    }

    @Test
    void readsExplicitMode() throws Exception {
        System.setProperty("metallum.sync", "2");
        assertEquals(2, parse());
    }

    @Test
    void readsAllModes() throws Exception {
        System.setProperty("metallum.sync", "1");
        assertEquals(1, parse());
        System.setProperty("metallum.sync", "3");
        assertEquals(3, parse());
    }

    @Test
    void fallsBackToDefaultWhenMissing() throws Exception {
        System.clearProperty("metallum.sync");
        assertEquals(3, parse());
    }

    @Test
    void fallsBackToDefaultWhenOutOfRange() throws Exception {
        System.setProperty("metallum.sync", "0");
        assertEquals(3, parse());
        System.setProperty("metallum.sync", "99");
        assertEquals(3, parse());
    }

    @Test
    void fallsBackToDefaultWhenMalformed() throws Exception {
        System.setProperty("metallum.sync", "abc");
        assertEquals(3, parse());
        System.setProperty("metallum.sync", " 2 ");
        assertEquals(2, parse());
    }
}
