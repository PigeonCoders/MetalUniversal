package com.metallum.client.metal.render.sodium;

import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.caffeinemc.mods.sodium.client.gl.attribute.GlVertexAttributeFormat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.lwjgl.opengl.GL33C;

/**
 * Sodium GlVertexAttributeFormat（typeId = GL 常量）→ MC VertexFormatElement.Type
 * 映射（MTLVertexFormat.from/fromInteger 的输入类型）。
 */
@Environment(EnvType.CLIENT)
public final class SodiumGlAttributeFormatMapper {
    private SodiumGlAttributeFormatMapper() {
    }

    public static VertexFormatElement.Type toMinecraftType(final GlVertexAttributeFormat format) {
        return toMinecraftType(format.typeId());
    }

    /** GlVertexAttribute.getFormat() 返回的即 typeId（GL 常量）。 */
    public static VertexFormatElement.Type toMinecraftType(final int typeId) {
        return switch (typeId) {
            case GL33C.GL_FLOAT -> VertexFormatElement.Type.FLOAT;
            case GL33C.GL_INT -> VertexFormatElement.Type.INT;
            case GL33C.GL_SHORT -> VertexFormatElement.Type.SHORT;
            case GL33C.GL_BYTE -> VertexFormatElement.Type.BYTE;
            case GL33C.GL_UNSIGNED_SHORT -> VertexFormatElement.Type.USHORT;
            case GL33C.GL_UNSIGNED_BYTE -> VertexFormatElement.Type.UBYTE;
            case GL33C.GL_UNSIGNED_INT -> VertexFormatElement.Type.UINT;
            default -> throw new IllegalArgumentException("Unsupported Sodium attribute format typeId=" + typeId);
        };
    }
}
