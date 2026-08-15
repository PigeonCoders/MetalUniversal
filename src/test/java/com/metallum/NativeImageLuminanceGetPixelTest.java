package com.metallum;

import com.mojang.blaze3d.platform.NativeImage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 实证 1.21.11 的 NativeImage.getPixel 对 LUMINANCE 图是否抛异常——
 * 决定 MetalCommandEncoder.writeToTexture 的 LUMINANCE 分支是否需要修复
 * （字体 glyph 上传走 LUMINANCE 图 + 10 参 writeToTexture）。
 */
public class NativeImageLuminanceGetPixelTest {

    @Test
    public void lumImageGetPixelThrows() {
        try (NativeImage img = new NativeImage(NativeImage.Format.LUMINANCE, 2, 2, false)) {
            assertThrows(IllegalArgumentException.class, () -> img.getPixel(0, 0));
        }
    }

    @Test
    public void rgbaImageGetPixelWorks() {
        try (NativeImage img = new NativeImage(NativeImage.Format.RGBA, 2, 2, false)) {
            // 不抛即通过
            img.getPixel(0, 0);
        }
    }
}
