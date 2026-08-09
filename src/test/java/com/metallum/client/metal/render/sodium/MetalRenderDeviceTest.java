package com.metallum.client.metal.render.sodium;

import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.gl.functions.BufferStorageFunctions;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GLCapabilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * SODIUM-ADAPT：MetalRenderDevice 无 GL 验证（阶段 3）。
 *
 * <p>核心断言：反射构造 GLCapabilities 不触发任何 GL 调用（空扩展集短路），
 * OpenGL44 / GL_ARB_buffer_storage 全 false → BufferStorageFunctions.pickBest
 * 得 NONE → Sodium 的 GL 能力探测走保守路径（FallbackStagingBuffer / 短路
 * GlBufferStreamer 的 GL.getCapabilities() 求值）。
 */
class MetalRenderDeviceTest {

    @Test
    void capabilitiesConstructWithoutGLAndAllFalse() {
        // 若构造过程触碰 GL context 会抛异常/崩溃——测试能跑完即证明零 GL 调用
        GLCapabilities caps = MetalRenderDevice.INSTANCE.getCapabilities();
        assertNotNull(caps);
        assertFalse(caps.OpenGL44);
        assertFalse(caps.GL_ARB_buffer_storage);
    }

    @Test
    void deviceFunctionsPicksNone() {
        // pickBest → NONE：MappedStagingBuffer.isSupported 恒 false，Sodium 走 Fallback
        assertEquals(
                BufferStorageFunctions.NONE,
                MetalRenderDevice.INSTANCE.getDeviceFunctions().getBufferStorageFunctions()
        );
    }

    @Test
    void subTexelPrecisionIsFour() {
        assertEquals(4, MetalRenderDevice.INSTANCE.getSubTexelPrecisionBits());
    }

    @Test
    void commandListRequiresActive() {
        assertThrows(IllegalStateException.class, () -> MetalRenderDevice.INSTANCE.createCommandList());
    }

    @Test
    void commandListSingletonAfterMakeActive() {
        MetalRenderDevice.INSTANCE.makeActive();
        try {
            CommandList first = MetalRenderDevice.INSTANCE.createCommandList();
            CommandList second = MetalRenderDevice.INSTANCE.createCommandList();
            assertSame(first, second);
        } finally {
            MetalRenderDevice.INSTANCE.makeInactive();
        }
    }
}
