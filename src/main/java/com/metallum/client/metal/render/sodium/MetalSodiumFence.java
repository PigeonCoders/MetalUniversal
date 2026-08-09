package com.metallum.client.metal.render.sodium;

import net.caffeinemc.mods.sodium.client.gl.sync.GlFence;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Sodium GlFence 的 Metal 防御性实现。
 *
 * <p>Metal 版 MappedStagingBuffer 未启用（DeviceFunctions 返回 NONE，Sodium 自动走
 * FallbackStagingBuffer），MappedStagingBuffer 使用的 fence 路径不会被执行；本类
 * 仅保证 CommandList.createFence() 的接口面不崩。阶段 6 若启用持久映射路径，
 * 再接入本地 MetalFence（semaphore/awaitSubmitCompletion 语义）。
 */
@Environment(EnvType.CLIENT)
public final class MetalSodiumFence extends GlFence {
    private boolean disposed;

    public MetalSodiumFence() {
        super(0L);
    }

    @Override
    public boolean isCompleted() {
        this.checkDisposed();
        return true;
    }

    @Override
    public void sync() {
        this.checkDisposed();
    }

    @Override
    public void sync(final long timeout) {
        this.checkDisposed();
    }

    @Override
    public void delete() {
        this.disposed = true;
    }

    private void checkDisposed() {
        if (this.disposed) {
            throw new IllegalStateException("Fence object has been disposed");
        }
    }
}
