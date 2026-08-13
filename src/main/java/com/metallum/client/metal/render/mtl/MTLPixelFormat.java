package com.metallum.client.metal.render.mtl;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public enum MTLPixelFormat {
    R8Unorm(10L),
    R8Snorm(12L),
    R8Uint(13L),
    R8Sint(14L),

    R16Unorm(20L),
    R16Snorm(22L),
    R16Uint(23L),
    R16Sint(24L),
    R16Float(25L),

    RG8Unorm(30L),
    RG8Snorm(32L),
    RG8Uint(33L),
    RG8Sint(34L),

    R32Uint(53L),
    R32Sint(54L),
    R32Float(55L),

    RG16Unorm(60L),
    RG16Snorm(62L),
    RG16Uint(63L),
    RG16Sint(64L),
    RG16Float(65L),

    RGBA8Unorm(70L),
    BGRA8Unorm(80L),
    RGBA8Snorm(72L),
    RGBA8Uint(73L),
    RGBA8Sint(74L),

    RGB10A2Unorm(90L),
    RG11B10Float(92L),

    RG32Uint(103L),
    RG32Sint(104L),
    RG32Float(105L),

    RGBA16Unorm(110L),
    RGBA16Snorm(112L),
    RGBA16Uint(113L),
    RGBA16Sint(114L),
    RGBA16Float(115L),

    RGBA32Uint(123L),
    RGBA32Sint(124L),
    RGBA32Float(125L),

    Depth16Unorm(250L),
    Depth32Float(252L),
    Stencil8(253L),
    Depth24Unorm_Stencil8(255L),
    Depth32Float_Stencil8(260L),

    Invalid(0L);

    public final long value;

    MTLPixelFormat(final long value) {
        this.value = value;
    }

    public boolean hasStencil() {
        return this == Depth24Unorm_Stencil8 || this == Depth32Float_Stencil8;
    }

    public static MTLPixelFormat from(final com.mojang.blaze3d.textures.TextureFormat format) {
        // 1.21.11 的 TextureFormat 仅 4 值（26.2 的 GpuFormat 有数十种）。
        // RED8I = GL_R8I（有符号字节 GL_BYTE=5121——CloudRenderer 编码负 cellX/cellZ
        // 为 0x80-0xFF；vsh isamplerBuffer 按有符号读，spvc 生成 texture_buffer<int>）
        // → 必须 R8Sint（P33 云层修复：曾误映射 R8Uint——负字节读回 255 → 云被推到
        // 6120 块外 → 超 FogCloudsEnd → fog 全杀 → 世界负半区云消失 → 屏幕固定象限）。
        // 上游 metallum 26.2 正确区分（R8_SINT -> R8Sint）。
        return switch (format) {
            case RGBA8 -> RGBA8Unorm;
            case RED8 -> R8Unorm;
            case RED8I -> R8Sint;
            case DEPTH32 -> Depth32Float;
        };
    }
}
