package com.metallum.client.metal.render.sodium;

import net.caffeinemc.mods.sodium.client.gl.device.MultiDrawBatch;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SODIUM-ADAPT：MultiDrawBatch native 数组读取（阶段 3）。
 *
 * <p>MetalSodiumDrawCommandList 的 multiDrawElementsBaseVertex 循环从
 * MultiDrawBatch 的三个 native 数组读取 draw 命令（pElementCount /
 * pBaseVertex / pElementPointer 字节偏移）——读取逻辑抽成纯函数
 * readBatchEntry，本测试在 Linux 上直接验证（nmemAlignedAlloc 无 GL 依赖）。
 */
class MetalSodiumDrawCommandListBatchTest {

    @Test
    void readBatchEntryMatchesWrittenValues() {
        MultiDrawBatch batch = new MultiDrawBatch(2);
        try {
            // 模拟 DefaultChunkRenderer.addSharedIndexedDrawCommands 的写入方式
            MemoryUtil.memPutInt(batch.pElementCount, 36);
            MemoryUtil.memPutInt(batch.pBaseVertex, 128);
            MemoryUtil.memPutAddress(batch.pElementPointer, 1024L);

            MemoryUtil.memPutInt(batch.pElementCount + 4L, 6);
            MemoryUtil.memPutInt(batch.pBaseVertex + 4L, 164);
            MemoryUtil.memPutAddress(batch.pElementPointer + (1L << Pointer.POINTER_SHIFT), 512L);
            batch.size = 2;

            MetalSodiumDrawCommandList.DrawCommand first = MetalSodiumDrawCommandList.readBatchEntry(batch, 0);
            assertEquals(36, first.elementCount());
            assertEquals(128, first.baseVertex());
            assertEquals(1024L, first.indexOffsetBytes());

            MetalSodiumDrawCommandList.DrawCommand second = MetalSodiumDrawCommandList.readBatchEntry(batch, 1);
            assertEquals(6, second.elementCount());
            assertEquals(164, second.baseVertex());
            assertEquals(512L, second.indexOffsetBytes());
        } finally {
            batch.delete();
        }
    }

    @Test
    void readBatchEntryPreservesByteOffsetSemantics() {
        // pElementPointer 的语义是字节偏移（GL 版 *4 转字节后写入）——直接透传
        MultiDrawBatch batch = new MultiDrawBatch(1);
        try {
            MemoryUtil.memPutInt(batch.pElementCount, 42);
            MemoryUtil.memPutInt(batch.pBaseVertex, 0);
            MemoryUtil.memPutAddress(batch.pElementPointer, 4096L);
            batch.size = 1;

            MetalSodiumDrawCommandList.DrawCommand command = MetalSodiumDrawCommandList.readBatchEntry(batch, 0);
            assertEquals(42, command.elementCount());
            assertEquals(0, command.baseVertex());
            assertEquals(4096L, command.indexOffsetBytes());
        } finally {
            batch.delete();
        }
    }
}
