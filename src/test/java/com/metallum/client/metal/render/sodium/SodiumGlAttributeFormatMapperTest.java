package com.metallum.client.metal.render.sodium;

import com.mojang.blaze3d.vertex.VertexFormatElement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * SodiumGlAttributeFormatMapper 映射测试（GL typeId → MC VertexFormatElement.Type）。
 */
class SodiumGlAttributeFormatMapperTest {

    @Test
    void mapsCompactChunkVertexFormats() {
        assertEquals(VertexFormatElement.Type.UINT, SodiumGlAttributeFormatMapper.toMinecraftType(0x1405));
        assertEquals(VertexFormatElement.Type.UBYTE, SodiumGlAttributeFormatMapper.toMinecraftType(0x1401));
        assertEquals(VertexFormatElement.Type.USHORT, SodiumGlAttributeFormatMapper.toMinecraftType(0x1403));
        assertEquals(VertexFormatElement.Type.FLOAT, SodiumGlAttributeFormatMapper.toMinecraftType(0x1406));
        assertEquals(VertexFormatElement.Type.INT, SodiumGlAttributeFormatMapper.toMinecraftType(0x1404));
        assertEquals(VertexFormatElement.Type.SHORT, SodiumGlAttributeFormatMapper.toMinecraftType(0x1402));
        assertEquals(VertexFormatElement.Type.BYTE, SodiumGlAttributeFormatMapper.toMinecraftType(0x1400));
    }

    @Test
    void rejectsUnknownTypeId() {
        assertThrows(IllegalArgumentException.class, () -> SodiumGlAttributeFormatMapper.toMinecraftType(0xFFFF));
    }
}
