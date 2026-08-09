package com.metallum.client.metal.render.sodium;

import com.metallum.client.metal.render.MetalGpuBuffer;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBufferTarget;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.gl.tessellation.GlPrimitiveType;
import net.caffeinemc.mods.sodium.client.gl.tessellation.GlTessellation;
import net.caffeinemc.mods.sodium.client.gl.tessellation.TessellationBinding;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

/**
 * SODIUM-ADAPT：Metal 版 tessellation（阶段 3）。
 *
 * <p>GL 版（GlVertexArrayTessellation）把顶点属性布局编码进 VAO（glVertexAttribPointer
 * 直调，Metal 模式必崩）。Metal 无 VAO 概念：属性布局已静态化进 MTLVertexDescriptor
 * （MetalSodiumCompiledPipeline.buildVertexDescriptor，stride 20 / slot 8），运行期只
 * 需要 vertex buffer 与 index buffer 两个引用。bind/unbind/delete 全 no-op——
 * buffer 生命周期归 arena（CommandList.deleteBuffer），tessellation 只是引用容器。
 */
@Environment(EnvType.CLIENT)
public final class MetalSodiumTessellation implements GlTessellation {
    private final GlPrimitiveType primitiveType;
    /** ARRAY_BUFFER binding（region geometry buffer，slot = firstVertexBufferSlot）。 */
    private final MetalGpuBuffer vertexBuffer;
    /** ELEMENT_BUFFER binding（共享 quad index buffer 或 region 本地 index buffer），可空防御。 */
    @Nullable
    private final MetalGpuBuffer indexBuffer;

    MetalSodiumTessellation(final GlPrimitiveType primitiveType, final TessellationBinding[] bindings) {
        this.primitiveType = primitiveType;
        MetalGpuBuffer vertex = null;
        MetalGpuBuffer index = null;
        for (TessellationBinding binding : bindings) {
            MetalGpuBuffer metal = MetalSodiumCommandList.requireBuffer(binding.buffer());
            if (binding.target() == GlBufferTarget.ARRAY_BUFFER) {
                vertex = metal;
            } else if (binding.target() == GlBufferTarget.ELEMENT_BUFFER) {
                index = metal;
            }
        }
        if (vertex == null) {
            throw new IllegalStateException("Sodium tessellation missing vertex buffer binding");
        }
        this.vertexBuffer = vertex;
        this.indexBuffer = index;
    }

    public MetalGpuBuffer vertexBuffer() {
        return this.vertexBuffer;
    }

    @Nullable
    public MetalGpuBuffer indexBuffer() {
        return this.indexBuffer;
    }

    @Override
    public GlPrimitiveType getPrimitiveType() {
        return this.primitiveType;
    }

    @Override
    public void bind(final CommandList commandList) {
        // NO-OP：Metal 无 VAO
    }

    @Override
    public void unbind(final CommandList commandList) {
        // NO-OP
    }

    @Override
    public void delete(final CommandList commandList) {
        // NO-OP：buffer 生命周期归 arena（CommandList.deleteBuffer），此处仅解引用
    }
}
