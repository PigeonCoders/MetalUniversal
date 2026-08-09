package com.metallum.mixin.sodium;

import com.metallum.client.metal.render.MetalBackend;
import com.metallum.client.metal.render.sodium.MetalSodiumShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

/**
 * RenderSectionManager.<init> 里硬编码 new DefaultChunkRenderer(RenderDevice.INSTANCE,
 * ChunkMeshFormats.COMPACT) 赋给 private final chunkRenderer 字段（javap 实证
 * putfield chunkRenderer）——Sodium 用 concrete class，无法接口替换。
 *
 * <p><b>为什么不用 @ModifyExpressionValue 替换构造结果（实测失败）</b>：
 * 注入点挂在 DefaultChunkRenderer.<init> 的 INVOKE 上，但构造器 INVOKESPECIAL
 * 的返回类型是 void——mixinextras 运行时 checkTargetReturnsAValue 硬校验失败
 * （InvalidInjectionException: targeting an instruction with a return type of 'void'，
 * iOS 实测；本地 Linux build 不校验此点——LateInjectionApplicatorExtension.postApply
 * 运行时才查，编译期 AP 不查）。
 *
 * <p><b>为什么不能改挂 @At(NEW)</b>：NEW 后栈顶是对象引用（有返回值），但替换对象后
 * 字节码里的 INVOKESPECIAL 仍是 DefaultChunkRenderer.<init>(RenderDevice, ChunkVertexType)，
 * 会对 MetalSodiumShaderChunkRenderer 执行错误签名构造 → VerifyError。
 *
 * <p><b>方案（@Inject TAIL + 反射改 final 实例字段）</b>：构造完成后把字段整个换掉。
 * 实例 final 字段反射修改 Java 17+ 允许（禁止的只有 static final）；sodium 类由
 * KnotClassLoader 加载（unnamed module），setAccessible 无模块限制；构造期一次反射
 * 无性能影响。原 GL 版对象构造路径无 GL 调用（SharedQuadIndexBuffer 经 GlBufferMixin
 * 注册句柄），丢弃安全。
 */
@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class RenderSectionManagerMixin {
    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void metallum$replaceChunkRenderer(final CallbackInfo ci) {
        if (!MetalBackend.isMetalHost()) {
            return;
        }
        try {
            Field field = RenderSectionManager.class.getDeclaredField("chunkRenderer");
            field.setAccessible(true);
            field.set(this, new MetalSodiumShaderChunkRenderer(MetalBackend.activeDevice(), ChunkMeshFormats.COMPACT));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("[metallum] Failed to replace RenderSectionManager.chunkRenderer with Metal renderer", e);
        }
    }
}
