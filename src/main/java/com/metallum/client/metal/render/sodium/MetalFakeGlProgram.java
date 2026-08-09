package com.metallum.client.metal.render.sodium;

import net.caffeinemc.mods.sodium.client.gl.shader.GlProgram;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * SODIUM-ADAPT：假 GlProgram 容器（阶段 3）。
 *
 * <p>DefaultChunkRenderer.render 循环硬依赖父类字段 {@code activeProgram.getInterface()}
 * 取 ChunkShaderInterface。原版 begin() 里 activeProgram 是 GL program（bind/delete
 * 全是 GL 调用，Metal 模式必崩）——本类提供一个零 GL 的容器：GlProgram 的 ctor 纯
 * Java（setHandle + interfaceFactory），仅因 protected 需要子类。render 循环只调用
 * getInterface()，bind/unbind/delete 均覆写为 no-op（父类 programs map 恒空，delete
 * 永远不会走到这里，覆写仅为防御）。
 */
@Environment(EnvType.CLIENT)
final class MetalFakeGlProgram extends GlProgram<ChunkShaderInterface> {

    MetalFakeGlProgram(final ChunkShaderInterface shaderInterface) {
        super(1, ctx -> shaderInterface);
    }

    @Override
    public void bind() {
        // NO-OP：Metal 无 GL program 概念，pipeline 状态由 MetalSodiumDrawCommandList 应用
    }

    @Override
    public void unbind() {
        // NO-OP
    }

    @Override
    public void delete() {
        // NO-OP：无底层 GL 对象
    }
}
