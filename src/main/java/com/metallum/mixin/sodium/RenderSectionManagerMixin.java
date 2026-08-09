package com.metallum.mixin.sodium;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.metallum.client.metal.render.MetalBackend;
import com.metallum.client.metal.render.sodium.MetalSodiumShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * RenderSectionManager.<init> 里硬编码 new DefaultChunkRenderer(RenderDevice.INSTANCE,
 * ChunkMeshFormats.COMPACT) 赋给 private final chunkRenderer 字段（javap 实证
 * putfield chunkRenderer）——Sodium 用 concrete class，无法接口替换，只能替换构造结果。
 *
 * <p>@ModifyExpressionValue（Mixinextras）直接替换 new 表达式的结果，绕过
 * "mixin 禁止 @Redirect 构造器调用"的限制（本地先例：InvalidInjectionException:
 * Illegal @Redirect of constructor）。原 GL 版对象构造后即被丢弃——其构造路径
 * 无 GL 调用（SharedQuadIndexBuffer 经 GlBufferMixin 注册句柄），丢弃安全。
 *
 * <p>运行时依赖 mixinextras（sodium 不内嵌不列 depends）：缺失时本注入静默失效，
 * chunkRenderer 保持 GL 版 → begin 时 GL 崩（部署面见计划文件 D7）。
 */
@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class RenderSectionManagerMixin {
    @ModifyExpressionValue(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/DefaultChunkRenderer;<init>(Lnet/caffeinemc/mods/sodium/client/gl/device/RenderDevice;Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexType;)V",
                    remap = false
            ),
            remap = false
    )
    private ChunkRenderer metallum$replaceChunkRenderer(final DefaultChunkRenderer original) {
        if (!MetalBackend.isMetalHost()) {
            return original;
        }
        return new MetalSodiumShaderChunkRenderer(MetalBackend.activeDevice(), ChunkMeshFormats.COMPACT);
    }
}
