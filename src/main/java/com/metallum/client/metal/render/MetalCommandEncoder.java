package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.*;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuQuery;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.Supplier;

@Environment(EnvType.CLIENT)
public final class MetalCommandEncoder implements CommandEncoder {
    public static final int MAX_SUBMITS_IN_FLIGHT = 3;
    /**
     * 跨帧竞争判别（P1 方案 B）：submit 后等待「提前 SYNC_MODE 帧」的提交完成。
     * 1=等刚提交帧（GPU 完全串行，exp3 验证无闪烁基线）/ 2=等上一帧（CPU 超前 ≤1 帧，
     * 方案 B 判别）/ 3=等两帧前（原 3 帧滑动窗口）。-Dmetallum.sync=N，非法值回退 1。
     */
    private static final int SYNC_MODE = parseSyncMode();

    private static int parseSyncMode() {
        // P36（Sodium 收尾）：默认 3（满流水线）。P2/P2.5 staging+uniform ring 已根治
        // 跨帧竞争（iOS 实测 sync=3 无闪烁），1 的完全串行基线不再需要。
        String raw = System.getProperty("metallum.sync", "3");
        try {
            int v = Integer.parseInt(raw.trim());
            return (v >= 1 && v <= 3) ? v : 3;
        } catch (NumberFormatException e) {
            return 3;
        }
    }

    /** P2 staging ring：单例 encoder 引用（currentFrameIndex 静态代理用，MetalDevice 构造时赋值）。 */
    private static volatile MetalCommandEncoder singleton;
    private final MetalDevice device;
    private long currentSubmitIndex = MAX_SUBMITS_IN_FLIGHT;
    /** SODIUM-ADAPT 诊断：ensureActiveRenderEncoder 重建计数（DiagLog 节流输出）。 */
    private long sodiumEncoderRebuilds;
    private final InFlight[] inFlight = new InFlight[MAX_SUBMITS_IN_FLIGHT];
    private final MemorySegment[] submitSemaphores = new MemorySegment[MAX_SUBMITS_IN_FLIGHT];
    private final MetalDestructionQueue destroyQueue = new MetalDestructionQueue(MAX_SUBMITS_IN_FLIGHT);
    final MetalTransientMemory transientMemory;
    private final Map<MetalGpuTexture, Vector4fc> pendingColorClears = new IdentityHashMap<>();
    private final Map<MetalGpuTexture, Double> pendingDepthClears = new IdentityHashMap<>();
    private final MemorySegment fence;
    @Nullable
    private MetalRenderPass currentRenderPass;
    @Nullable
    private MTLCommandBuffer commandBuffer;
    @Nullable
    private MTLCommandEncoder currentEncoder;
    private MemorySegment renderColorAttachment = MemorySegment.NULL;
    private MemorySegment renderDepthAttachment = MemorySegment.NULL;
    /** 当前活跃 render encoder 的 color attachment 格式（renderCommandEncoder 时更新，Sodium 绘制用）。 */
    private MTLPixelFormat currentColorFormat = MTLPixelFormat.Invalid;
    private double lastLayerWidth = -1;
    private double lastLayerHeight = -1;
    /**
     * 单帧耗时采样（P3）：submit() 相邻两次提交间隔视为一帧（间隔 >500ms 视为跳帧，
     * 不计入）。gpuBehind 观测 3 帧前提交是否未完成（GPU 落后检测）。5s 窗口聚合
     * 输出 fps/avg/max，走 DiagLog（iOS 上每帧日志会卡死，必须节流）。
     */
    private static final long MAX_FRAME_SAMPLE_US = 500_000L;
    private long lastSubmitNanos = 0L;
    private boolean skipNextFrameSample;
    private long frameTimeSamples = 0;
    private long frameTimeSumUs = 0;
    private long frameTimeMaxUs = 0;
    private long frameTimeNotDone = 0;
    /**
     * P0 帧分类：本帧是否"移动"（Sodium 层 metalBegin 每帧经 markFrameMoving 标记，
     * submit() 采样后重置）。用于区分「常态低帧率 vs 移动尖峰」——frame_time 行按
     * 移动/静止帧分开统计 avg，观察尖峰是否只在移动窗口出现。
     */
    private static volatile boolean frameMoving;
    private long movingSamples = 0;
    private long movingSumUs = 0;
    /** P6：await 阻塞时长统计（GPU 重帧判别——阻塞量 = max(0, T_g - 33.3ms)）。 */
    private long lastAwaitUs = 0L;
    private long awaitSamples = 0;
    private long awaitSumUs = 0;
    private long awaitMaxUs = 0;
    /** P6：尖峰帧（帧间隔 >30ms）判别阈值（µs）。 */
    private static final long SPIKE_THRESHOLD_US = 30_000L;
    private final Long2ObjectOpenHashMap<java.util.ArrayDeque<MemorySegment>> dynamicBackingPool = new Long2ObjectOpenHashMap<>();
    private static final int MAX_POOLED_DYNAMIC_BACKINGS_PER_SIZE = 8;
    @Nullable

    MetalCommandEncoder(final MetalDevice device) {
        this.device = device;
        this.transientMemory = new MetalTransientMemory(device, this);
        fence = MetalNativeBridge.metallum_create_fence(device.metalDeviceHandle());
        if (MetalNativeBridge.isNullHandle(fence)) {
            throw new IllegalStateException("Failed to allocate MTLFence");
        }
        for (int slot = 0; slot < MAX_SUBMITS_IN_FLIGHT; slot++) {
            submitSemaphores[slot] = MetalNativeBridge.metallum_create_semaphore();
            if (MetalNativeBridge.isNullHandle(submitSemaphores[slot])) {
                throw new IllegalStateException("Failed to allocate submit semaphore");
            }
        }
        // P1：启动首行确认判别开关生效（Amethyst 参数注入失败时静默回退默认 1——
        // 日志可发现，防实验跑错模式）
        DiagLog.log("sync_mode=%d (metallum.sync: 1=serial 2=one-ahead 3=sliding)", SYNC_MODE);
        singleton = this;
    }

    /**
     * P2：当前提交帧号（staging ring 轮转的帧边界信号）。单例 encoder 的
     * currentSubmitIndex 静态代理；未初始化（JUnit 等无 Metal 环境）返回 0。
     */
    public static long currentFrameIndex() {
        MetalCommandEncoder enc = singleton;
        return enc == null ? 0L : enc.currentSubmitIndex;
    }

    /**
     * P0：标记本帧是否移动（由渲染路径调用方——Sodium metalBegin——每帧设置一次；
     * submit() 采样后自动重置）。与渲染层解耦：encoder 不感知 game 状态。
     */
    public static void markFrameMoving(final boolean moving) {
        frameMoving = moving;
    }

    MTLCommandBuffer commandBuffer() {
        if (commandBuffer != null) {
            return commandBuffer;
        }
        return commandBuffer = device.commandQueue.makeCommandBuffer(
                device.useLabels() ? "Metallum frame " + currentSubmitIndex : null
        );
    }

    MTLBlitCommandEncoder blitCommandEncoder() {
        // P24-1（上传合并）：挂起 blit 复用——帧内多次 blit 共享一个编码器
        // （一次创建/一次 end/fence 两次）。任何 render encoder 创建前必先
        // endEncoder 杀挂起 blit（renderCommandEncoder/flushPendingClear/
        // submit/present 兜底）。
        if (currentEncoder instanceof MTLBlitCommandEncoder blit) {
            return blit;
        }
        endEncoder();
        MTLBlitCommandEncoder encoder = commandBuffer().makeBlitCommandEncoder();
        encoder.waitForFence(fence);
        currentEncoder = encoder;
        return encoder;
    }

    void endEncoder() {
        if (currentEncoder != null) {
            if (currentEncoder instanceof MTLRenderCommandEncoder renderEncoder) {
                renderEncoder.updateFence(fence, MTLRenderStages.VertexAndFragment);
                if (currentRenderPass != null) {
                    currentRenderPass.invalidateEncoderState();
                }
            } else if (currentEncoder instanceof MTLBlitCommandEncoder blitEncoder) {
                blitEncoder.updateFence(fence);
            }
            currentEncoder.endEncoding();
            currentEncoder = null;
        }
        renderColorAttachment = MemorySegment.NULL;
        renderDepthAttachment = MemorySegment.NULL;
        bumpEncoderEpoch();
    }

    // P29-2：编码器世代计数——任何编码器结束/重建时递增。
    // MetalSodiumDrawCommandList 的状态去重缓存依赖它失效（编码器状态全丢后
    // 必须全量重绑——Metal 编码器状态不跨 endEncoder 保持）。渲染线程单线程。
    private static long encoderEpoch;

    private static void bumpEncoderEpoch() {
        encoderEpoch++;
    }

    /** 编码器世代（适配层状态缓存失效信号）。 */
    public static long encoderEpoch() {
        return encoderEpoch;
    }

    /**
     * 1.21.11 的 CommandEncoder 无 submit() 接口方法：提交时机由 presentTexture
     * （每帧末）与 waitForSubmittedGpuWork（资源释放前）驱动。
     */
    void submit() {
        InFlight toClose = null;
        if (commandBuffer != null) {
            submitRenderPass();
            endEncoder();

            int slot = (int) (currentSubmitIndex % MAX_SUBMITS_IN_FLIGHT);
            MemorySegment completedSemaphore = submitSemaphores[slot];
            commandBuffer.commitWithSignal(completedSemaphore);

            long now = System.nanoTime();
            if (lastSubmitNanos != 0L) {
                if (!skipNextFrameSample) {
                    long frameUs = Math.max(0L, (now - lastSubmitNanos) / 1000L);
                    if (frameUs <= MAX_FRAME_SAMPLE_US) {
                        frameTimeSamples++;
                        frameTimeSumUs += frameUs;
                        frameTimeMaxUs = Math.max(frameTimeMaxUs, frameUs);
                        // P0：按移动/静止分类统计（金属移动判定在 metalBegin 已标记本帧）
                        if (frameMoving) {
                            movingSamples++;
                            movingSumUs += frameUs;
                        }
                        // P6：await 阻塞归因——帧间隔 [prev,now] 含 prev 处 submit 的 await。
                        // 尖峰帧（>30ms）逐条记录：awaitPrev 大 → GPU 重帧坐实；小 → CPU 编码突发。
                        if (lastAwaitUs > 0L) {
                            awaitSamples++;
                            awaitSumUs += lastAwaitUs;
                            awaitMaxUs = Math.max(awaitMaxUs, lastAwaitUs);
                        }
                        if (frameUs > SPIKE_THRESHOLD_US) {
                            DiagLog.log("[diag] spike frame=%dus awaitPrev=%dus moving=%b gcPause=%dms",
                                    frameUs, lastAwaitUs, frameMoving, GcMonitor.pauseDeltaMs());
                        }
                    }
                }
            }
            lastSubmitNanos = now;
            skipNextFrameSample = false;

            toClose = inFlight[slot];
            inFlight[slot] = new InFlight(currentSubmitIndex, commandBuffer, completedSemaphore);
            commandBuffer = null;
        } else {
            // 空帧（无命令缓冲）：不推进时间基准，下次提交的间隔若跨越空帧则跳过采样
            skipNextFrameSample = true;
        }
        currentSubmitIndex++;

        // P6 卡顿判别：await 阻塞时长观测 + gpuBehind 修复。模型：SYNC_MODE=3 时
        // 阻塞量 = max(0, T_g - 33.3ms)（GPU 重帧 → CPU 等在 submit）。isCompleted 检查
        // 移到 await 之前（toClose=N-3，await 前未完成 = GPU 落后 ≥3 帧，真 gpuBehind——
        // 原位置恒 false 是结构性盲）。
        boolean behindBeforeAwait = toClose != null && !toClose.buffer.isCompleted();
        long awaitStartNanos = System.nanoTime();
        awaitSubmitCompletion(currentSubmitIndex - SYNC_MODE, 5000L);
        lastAwaitUs = (System.nanoTime() - awaitStartNanos) / 1000L;
        if (behindBeforeAwait) {
            frameTimeNotDone++;
        }

        if (toClose != null) {
            toClose.buffer.close();
        }

        transientMemory.rotate();
        destroyQueue.rotate();

        if (Diagnostics.shouldRun("frame_time", 5_000L) && frameTimeSamples > 0) {
            long avgUs = frameTimeSumUs / frameTimeSamples;
            long movingAvgUs = movingSamples == 0 ? 0 : movingSumUs / movingSamples;
            long awaitAvgUs = awaitSamples == 0 ? 0 : awaitSumUs / awaitSamples;
            // P0：moving=N/M 为 5s 窗口内移动帧占比，avgMoving 为移动帧平均耗时（静止帧 avg 即总 avg）
            // P6：await 阻塞统计（GPU 重帧判别）
            DiagLog.log("frame_time fps=%.1f avg=%.2fms max=%.2fms gpuBehind=%d moving=%d/%d avgMoving=%.2fms awaitAvg=%.2fms awaitMax=%.2fms",
                    1_000_000.0 / avgUs,
                    avgUs / 1000.0,
                    frameTimeMaxUs / 1000.0,
                    frameTimeNotDone,
                    movingSamples, frameTimeSamples,
                    movingAvgUs / 1000.0,
                    awaitAvgUs / 1000.0,
                    awaitMaxUs / 1000.0);
            frameTimeSamples = 0;
            frameTimeSumUs = 0;
            frameTimeMaxUs = 0;
            frameTimeNotDone = 0;
            movingSamples = 0;
            movingSumUs = 0;
            awaitSamples = 0;
            awaitSumUs = 0;
            awaitMaxUs = 0;
        }

        if (Diagnostics.shouldRun("stats", 60_000L)) {
            DiagLog.log("%s", Stats.snapshot());
        }

        // P8 卡顿判别：JVM 堆观测——周期性大 spike（静止时 182ms）与堆曲线阶梯回落
        // 关联 = GC 暂停候选（上传风暴/transient 大块分配后的回收）。
        if (Diagnostics.shouldRun("heap", 5_000L)) {
            Runtime rt = Runtime.getRuntime();
            long used = rt.totalMemory() - rt.freeMemory();
            DiagLog.log("[diag] heap used=%dMB total=%dMB",
                    used / 1048576L, rt.totalMemory() / 1048576L);
        }

        // P0：帧末重置移动标记（下一帧由渲染路径重新标记）
        frameMoving = false;
    }

    MTLRenderCommandEncoder renderCommandEncoder(
            final MetalGpuTextureView colorTextureView,
            @Nullable final MetalGpuTextureView depthTextureView,
            final int viewportWidth,
            final int viewportHeight,
            final boolean clearColorEnabled,
            final float clearColorRed,
            final float clearColorGreen,
            final float clearColorBlue,
            final float clearColorAlpha,
            final boolean clearDepthEnabled,
            final double clearDepthValue
    ) {
        MemorySegment colorAttachment = colorTextureView.nativeHandle();
        MemorySegment depthAttachment = depthTextureView == null ? MemorySegment.NULL : depthTextureView.nativeHandle();
        // SODIUM-ADAPT：Sodium 绘制需与当前 MC pass 的 MTLPSO attachment 严格一致，
        // 格式必须以 encoder 侧为准（唯一正确源）
        this.currentColorFormat = ((MetalGpuTexture) colorTextureView.texture()).mtlPixelFormat();
        if (currentEncoder instanceof MTLRenderCommandEncoder enc
                && MetalPipelineSupport.sameHandle(renderColorAttachment, colorAttachment)
                && MetalPipelineSupport.sameHandle(renderDepthAttachment, depthAttachment)) {
            if (clearColorEnabled || clearDepthEnabled) {
                enc.clearDraw(
                        colorAttachment,
                        depthAttachment,
                        viewportWidth,
                        viewportHeight,
                        clearColorEnabled,
                        clearColorRed,
                        clearColorGreen,
                        clearColorBlue,
                        clearColorAlpha,
                        clearDepthEnabled,
                        clearDepthValue
                );
            }
            return enc;
        }

        endEncoder();
        MTLRenderCommandEncoder encoder = commandBuffer().makeRenderCommandEncoder(
                colorAttachment,
                depthAttachment,
                viewportWidth,
                viewportHeight,
                clearColorEnabled ? 1 : 0,
                clearColorRed,
                clearColorGreen,
                clearColorBlue,
                clearColorAlpha,
                clearDepthEnabled ? 1 : 0,
                clearDepthValue
        );
        encoder.waitForFence(fence, MTLRenderStages.VertexAndFragment);
        currentEncoder = encoder;
        renderColorAttachment = colorAttachment;
        renderDepthAttachment = depthAttachment;
        return encoder;
    }

    // ---- SODIUM-ADAPT：Sodium 绘制路径访问器（阶段 3） ----
    // Sodium 的绘制发生在 MC 主 RenderPass 流程内（renderGroup 调用点），必须复用
    // 当前活跃 render encoder（attachment 相同），并在其上覆盖 MTLPSO/状态/资源。
    // currentEncoder 非 render 类型（如 blit 打断期间）返回 null → 调用方 fail-fast。

    /** 当前活跃的 MTLRenderCommandEncoder（非 render encoder 时返回 null）。 */
    @Nullable
    public MTLRenderCommandEncoder activeRenderEncoder() {
        return this.currentEncoder instanceof MTLRenderCommandEncoder renderEncoder ? renderEncoder : null;
    }

    /** 当前 render encoder 的 color attachment 格式（MTLPSO 构建必须与 encoder 一致）。 */
    public MTLPixelFormat activeColorFormat() {
        return this.currentColorFormat;
    }

    /** 当前 render encoder 是否带 depth attachment（决定 MTLPSO 的 depthFormat）。 */
    public boolean activeHasDepth() {
        return this.renderDepthAttachment != MemorySegment.NULL && this.currentEncoder instanceof MTLRenderCommandEncoder;
    }

    /**
     * SODIUM-ADAPT（fix9）：确保当前有活跃的 render encoder 可绘制。
     *
     * <p>Sodium 的 chunkFades（GlBufferStreamer）首帧 flush 走 blit 上传
     * （writeToBuffer → blitCommandEncoder → endEncoder），把 metalBegin
     * 建立的 render encoder 结束（currentEncoder=null）；随后 executeDrawBatch
     * 到达时若无此重建则 activeRenderEncoder() 为 null。
     *
     * <p>currentRenderPass 由 createRenderPass 设置（submitRenderPass/close 才清），
     * 其 color/depth attachment 与 viewport 尺寸足够重建 encoder（clear 全关——
     * 清屏由 MC pending-clear 机制负责；重建的 encoder 是全新状态，Sodium 的
     * applyPipelineState 会全量设置，无残留污染）。
     */
    public @Nullable MTLRenderCommandEncoder ensureActiveRenderEncoder() {
        if (this.currentRenderPass == null) {
            return null;
        }
        if (this.currentEncoder instanceof MTLRenderCommandEncoder enc) {
            // P35（维度高频闪烁修复）：活跃编码器的 attachment 必须与当前 pass 匹配。
            // 下界/末地龙战（无天空 pass）的 tick 帧："Update light" pass 关闭后其
            // 16×16 lightmap 编码器保持活跃（MetalRenderPass.close 不 end encoder），
            // 天空 pass 缺失 → 无主目标 encoder 替换 → 此前第一分支直接返回 lightmap
            // encoder → chunk draw 全部录进 16×16 lightmap 纹理（颜色格式相同，Metal
            // 校验通过无报错）→ lightmap 被垃圾覆盖 + tick 帧主目标只剩清屏（地形
            // 闪没）= 20Hz 高频闪烁。主世界有天空 pass 先建主目标 encoder（attachment
            // 比对替换）故正常。attachment 不符 → 结束残留 encoder 走重建。
            boolean colorMatches = MetalPipelineSupport.sameHandle(
                    this.renderColorAttachment,
                    this.currentRenderPass.sodiumColorTextureView().nativeHandle());
            boolean depthMatches = this.currentRenderPass.sodiumDepthTextureView() == null
                    ? this.renderDepthAttachment == MemorySegment.NULL
                    : MetalPipelineSupport.sameHandle(
                            this.renderDepthAttachment,
                            this.currentRenderPass.sodiumDepthTextureView().nativeHandle());
            if (colorMatches && depthMatches) {
                return enc;
            }
            this.endEncoder();
        }
        // 诊断（diag）：重建频率——每 region 的 fade blit 都会打断；若每帧重建次数
        // 异常高（> region 数×pass 数）说明有额外的 endEncoder 路径（排查抖动用）。
        sodiumEncoderRebuilds++;
        if (Diagnostics.shouldRun("sodium-enc", 5_000L)) {
            DiagLog.log("[diag] sodium encoder rebuilds(last5s)=%d", sodiumEncoderRebuilds);
            sodiumEncoderRebuilds = 0L;
        }
        // P34（维度切换渲染修复）：清屏必须发生在第一次 draw 之前。materializePendingClear
        // → MetalRenderPass.renderEncoder 用 clearColor/clearDepth 参数创建编码器
        // （loadAction=Clear——与天空 pass 路径一致）并清字段（防 close 时重复 clear）。
        // 此前硬编码 clear=false → 编码器不清屏 → terrain draw 先执行（对上一帧残影渲染）
        // → metalEnd/close 时 materializePendingClear 的 clearDraw 把地形整体抹掉——
        // 下界（Skybox.NONE 无天空 pass）/末地龙战（shouldCreateWorldFog 无天空 pass）
        // 暴露：方块完全不渲染 + 实体可见 + 闪烁 + 深度残影黑边。主世界有天空 pass
        // 先消费 pending（正确时序）故正常。
        this.currentRenderPass.materializePendingClear();
        if (this.currentEncoder instanceof MTLRenderCommandEncoder enc) {
            return enc;
        }
        return this.renderCommandEncoder(
                this.currentRenderPass.sodiumColorTextureView(),
                this.currentRenderPass.sodiumDepthTextureView(),
                this.currentRenderPass.sodiumWidth(),
                this.currentRenderPass.sodiumHeight(),
                false, 0.0F, 0.0F, 0.0F, 0.0F,
                false, 0.0
        );
    }

    /** SODIUM-ADAPT（fix11）：per-region uniform 的 transient 块分配（帧内安全、帧末回收）。 */
    public MetalTransientMemory.MappedView allocateTransientUniform(final long size, final long alignment) {
        return this.transientMemory.allocateUniformSlice(size, alignment);
    }

    /**
     * P7：staging 上传独立块分配（MAP_READ|MAP_WRITE Shared 块）。帧内每份数据独立偏移
     * （互不覆写），帧末 rotate + destroyQueue 3 帧延迟回收——替代固定 staging buffer
     * 的帧内覆写竞争（FallbackStagingBuffer 成对 upload+copy，blit 异步读最后写入内容）。
     */
    public MetalTransientMemory.MappedView allocateTransientStaging(final long size, final long alignment) {
        return this.transientMemory.allocateStaging(size, alignment, GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_MAP_WRITE, 0L, 1L);
    }

    @Override
    public @NonNull RenderPass createRenderPass(
            final @NonNull Supplier<String> debugGroup,
            final @NonNull GpuTextureView colorTexture,
            final @NonNull OptionalInt clearColor
    ) {
        return this.createRenderPass(debugGroup, colorTexture, clearColor, null, OptionalDouble.empty());
    }

    @Override
    public @NonNull RenderPass createRenderPass(
            final @NonNull Supplier<String> debugGroup,
            final @NonNull GpuTextureView colorTexture,
            final @NonNull OptionalInt clearColor,
            @Nullable final GpuTextureView depthTexture,
            final @NonNull OptionalDouble clearDepth
    ) {
        MetalGpuTexture colorTex = (MetalGpuTexture) colorTexture.texture();
        // 承接状态快照（pending 分支内会被 remove，先存）
        boolean hadPendingColor = pendingColorClears.containsKey(colorTex);
        boolean hadPendingDepth = depthTexture != null
                && pendingDepthClears.containsKey(((MetalGpuTexture) depthTexture.texture()));
        Vector4fc pendingColor = pendingColorClears.get(colorTex);
        Vector4fc colorClear;
        if (pendingColor != null && isFullTextureView(colorTexture) && clearColor.isEmpty()) {
            // MC 1.21.11 的主目标清屏走 clearColorTexture()（pending 路径）而非 clearColor
            // 参数：pending 必须承接为本 pass 的 clear（26.2 抄写时漏了 color 分支，
            // 导致主目标 color 永不 clear → 未初始化纹理 → iOS 显示品红）
            pendingColorClears.remove(colorTex);
            colorClear = pendingColor;
        } else if (pendingColor != null && clearColor.isEmpty()) {
            flushPendingClear(colorTex);
            colorClear = null;
        } else {
            pendingColorClears.remove(colorTex);
            colorClear = clearColor.isPresent() ? fromArgbInt(clearColor.getAsInt()) : null;
        }
        colorTex.markContentsDirty();

        OptionalDouble effectiveDepthClear = clearDepth;
        if (depthTexture != null) {
            MetalGpuTexture metalDepth = (MetalGpuTexture) depthTexture.texture();
            Double pendingDepth = pendingDepthClears.get(metalDepth);
            if (pendingDepth != null && isFullTextureView(depthTexture) && effectiveDepthClear.isEmpty()) {
                pendingDepthClears.remove(metalDepth);
                effectiveDepthClear = OptionalDouble.of(pendingDepth);
            } else if (pendingDepth != null && effectiveDepthClear.isEmpty()) {
                flushPendingClear(metalDepth);
            } else {
                pendingDepthClears.remove(metalDepth);
            }
            metalDepth.markContentsDirty();
        }


        MetalRenderPass renderPass = new MetalRenderPass(
                device,
                this,
                debugGroup,
                colorTexture,
                depthTexture,
                colorClear,
                effectiveDepthClear.isPresent(),
                effectiveDepthClear.orElse(0.0)
        );
        currentRenderPass = renderPass;
        renderPass.pushDebugGroup(debugGroup);
        return renderPass;
    }

    /**
     * 1.21.11 的 clearColor 为 ARGB int（0xAARRGGBB）。格式约定以运行时验证为准。
     */
    private static Vector4f fromArgbInt(final int color) {
        float a = ((color >> 24) & 0xFF) / 255.0F;
        float r = ((color >> 16) & 0xFF) / 255.0F;
        float g = ((color >> 8) & 0xFF) / 255.0F;
        float b = (color & 0xFF) / 255.0F;
        return new Vector4f(r, g, b, a);
    }

    void submitRenderPass() {
        if (currentRenderPass != null) {
            currentRenderPass.materializePendingClear();
            currentRenderPass.popDebugGroup();
            currentRenderPass = null;
        }
    }

    /**
     * 按呈现纹理的像素尺寸配置 CAMetalLayer（drawableSize / present 模式）。
     * 首次 present 与窗口 resize 时各调用一次（尺寸变化才调用，避免每帧 FFM 开销）。
     * 若 drawableSize 与 render target 不匹配，呈现会缩放/模糊；iOS 上 present 模式
     * 由 Swift 侧按平台分支处理（macOS displaySyncEnabled，iOS allowsNextDrawableTimeout）。
     */
    private void configureLayerIfNeeded(final MetalGpuTexture source) {
        int width = source.getWidth(0);
        int height = source.getHeight(0);
        if (width == lastLayerWidth && height == lastLayerHeight) {
            return;
        }
        MemorySegment layer = device.metalLayer();
        if (!MetalNativeBridge.isNullHandle(layer)) {
            MetalNativeBridge.metallum_configure_layer(layer, width, height, 0);
        }
        lastLayerWidth = width;
        lastLayerHeight = height;
    }

    void presentTextureToDrawable(final MemorySegment drawable, final GpuTextureView textureView) {
        device.markRuntimeStarted();
        MetalGpuTexture source = (MetalGpuTexture) textureView.texture();
        configureLayerIfNeeded(source);
        flushPendingClear(source);
        submitRenderPass();
        endEncoder();
        MTLCommandBuffer commandBuffer = commandBuffer();
        commandBuffer.encodePresentTextureToDrawable(drawable, source.nativeHandle(), fence);
    }

    @Override
    public void presentTexture(final @NonNull GpuTextureView textureView) {
        // 1.21.11 无 GpuSurface：present 由 CommandEncoder 直接承担，每帧末提交
        MetalGpuTexture src = (MetalGpuTexture) textureView.texture();
        presentTextureToDrawable(device.metalLayer(), textureView);
        submit();
    }


    @Override
    public void clearColorTexture(final @NonNull GpuTexture colorTexture, final int clearColor) {
        pendingColorClears.put((MetalGpuTexture) colorTexture, fromArgbInt(clearColor));
    }

    @Override
    public void clearColorAndDepthTextures(final @NonNull GpuTexture colorTexture, final int clearColor, final @NonNull GpuTexture depthTexture, final double clearDepth) {
        MetalGpuTexture color = (MetalGpuTexture) colorTexture;
        MetalGpuTexture depth = (MetalGpuTexture) depthTexture;
        pendingColorClears.put(color, fromArgbInt(clearColor));
        pendingDepthClears.put(depth, clearDepth);
    }

    @Override
    public void clearColorAndDepthTextures(
            final @NonNull GpuTexture colorTexture,
            final int clearColor,
            final @NonNull GpuTexture depthTexture,
            final double clearDepth,
            final int regionX,
            final int regionY,
            final int regionWidth,
            final int regionHeight
    ) {
        MetalGpuTexture color = (MetalGpuTexture) colorTexture;
        MetalGpuTexture depth = (MetalGpuTexture) depthTexture;
        Vector4fc clearColorCopy = fromArgbInt(clearColor);
        if (isFullTextureRegion(color, depth, regionX, regionY, regionWidth, regionHeight)) {
            pendingColorClears.put(color, clearColorCopy);
            pendingDepthClears.put(depth, clearDepth);
            return;
        }
        color.markContentsDirty();
        depth.markContentsDirty();
        submitRenderPass();
        endEncoder();
        commandBuffer().clearColorDepthTexturesRegion(
                color.nativeHandle(),
                clearColorCopy.x(),
                clearColorCopy.y(),
                clearColorCopy.z(),
                clearColorCopy.w(),
                depth.nativeHandle(),
                clearDepth,
                regionX,
                regionY,
                regionWidth,
                regionHeight,
                fence
        );
    }

    @Override
    public void clearDepthTexture(final @NonNull GpuTexture depthTexture, final double clearDepth) {
        pendingDepthClears.put((MetalGpuTexture) depthTexture, clearDepth);
    }

    @Override
    public void writeToBuffer(final GpuBufferSlice destination, final ByteBuffer data) {
        MetalGpuBuffer buffer = (MetalGpuBuffer) destination.buffer();
        int length = data.remaining();

        if (buffer.isDynamic()) {
            orphanWrite(buffer, destination.offset(), data);
            return;
        }

        GpuBufferSlice staging = transientMemory.uploadStaging(data, 4L, GpuBuffer.USAGE_COPY_SRC, 0L, 1L);
        MetalGpuBuffer stagingBuffer = (MetalGpuBuffer) staging.buffer();

        MTLBlitCommandEncoder blit = blitCommandEncoder();
        blit.copyFromBufferToBuffer(
                stagingBuffer.nativeHandle(),
                staging.offset(),
                buffer.nativeHandle(),
                destination.offset(),
                length
        );
        // P24-1：blit 挂起（帧内合并——endEncoder 由 render/submit 路径统一兜底）
    }

    private void orphanWrite(final MetalGpuBuffer buffer, final long offset, final ByteBuffer data) {
        long size = buffer.allocationSize();
        MemorySegment old = buffer.nativeHandle();
        MemorySegment fresh = acquireDynamicBacking(size, buffer.resourceOptions());
        if (fresh.address() == 0L) {
            return;
        }
        ByteBuffer freshStorage = MetalNativeBridge.nativeByteBufferView(
                MetalNativeBridge.metallum_get_buffer_contents(fresh), size).order(ByteOrder.nativeOrder());

        if (offset != 0 || data.remaining() != buffer.size()) {
            ByteBuffer previous = buffer.currentStorage();
            previous.clear();
            freshStorage.duplicate().put(previous);
        }

        ByteBuffer dst = freshStorage.duplicate().order(ByteOrder.nativeOrder());
        dst.position(Math.toIntExact(offset));
        dst.put(data.duplicate());

        buffer.swapBacking(fresh, freshStorage);
        recycleDynamicBacking(old, size, buffer.resourceOptions());
    }

    private MemorySegment acquireDynamicBacking(final long size, final long resourceOptions) {
        final long key = MetalDevice.composePoolKey(size, resourceOptions);
        final java.util.ArrayDeque<MemorySegment> bucket = dynamicBackingPool.get(key);
        if (bucket != null && !bucket.isEmpty()) {
            return bucket.pop();
        }
        final MemorySegment handle = MetalNativeBridge.metallum_create_buffer(device.metalDeviceHandle(), size, resourceOptions);
        if (MetalNativeBridge.isNullHandle(handle)) {
            Metallum.LOGGER.warn("dynamic backing OOM, skipping uniform update this frame");
            return MemorySegment.NULL;
        }
        return handle;
    }

    private void recycleDynamicBacking(final MemorySegment handle, final long size, final long resourceOptions) {
        queueForDestroy(() -> {
            final long key = MetalDevice.composePoolKey(size, resourceOptions);
            java.util.ArrayDeque<MemorySegment> bucket = dynamicBackingPool.computeIfAbsent(key, k -> new java.util.ArrayDeque<>());
            if (bucket.size() < MAX_POOLED_DYNAMIC_BACKINGS_PER_SIZE) {
                bucket.push(handle);
            } else {
                MetalNativeBridge.metallum_release_object(handle);
            }
        });
    }

    @Override
    public void copyToBuffer(final GpuBufferSlice source, final GpuBufferSlice target) {
        MetalGpuBuffer sourceBuffer = (MetalGpuBuffer) source.buffer();
        MetalGpuBuffer targetBuffer = (MetalGpuBuffer) target.buffer();
        MTLBlitCommandEncoder blit = blitCommandEncoder();
        blit.copyFromBufferToBuffer(
                sourceBuffer.nativeHandle(),
                source.offset(),
                targetBuffer.nativeHandle(),
                target.offset(),
                source.length()
        );
        // P24-1：blit 挂起（帧内合并——endEncoder 由 render/submit 路径统一兜底）
    }

    @Override
    public void writeToTexture(
            final @NonNull GpuTexture destination,
            final @NonNull ByteBuffer source,
            final NativeImage.Format format,
            final int mipLevel,
            final int depthOrLayer,
            final int destX,
            final int destY,
            final int width,
            final int height
    ) {
        MetalGpuTexture metalDst = (MetalGpuTexture) destination;
        flushPendingClearForWrite(metalDst);

        // 1.21.11 的 NativeImage.Format 无 componentCount 映射：MC 纹理统一 RGBA8
        int pixelSize = 4;
        int rowBytes = width * pixelSize;
        int bytesPerImage = rowBytes * height;
        GpuBufferSlice slice = transientMemory.uploadStaging(source.duplicate().limit(bytesPerImage), pixelSize, GpuBuffer.USAGE_COPY_SRC, 0L, 1L);

        MTLBlitCommandEncoder blit = blitCommandEncoder();
        blit.copyFromBufferToTexture(
                ((MetalGpuBuffer) slice.buffer()).nativeHandle(),
                slice.offset(),
                metalDst.nativeHandle(),
                mipLevel,
                depthOrLayer,
                destX,
                destY,
                 width,
                height,
                rowBytes,
                bytesPerImage
        );
        // P24-1：blit 挂起（帧内合并——endEncoder 由 render/submit 路径统一兜底）
    }

    @Override
    public void writeToTexture(
            final @NonNull GpuTexture destination,
            final @NonNull NativeImage image,
            final int mipLevel,
            final int depthOrLayer,
            final int x,
            final int y,
            final int width,
            final int height,
            final int sourceX,
            final int sourceY
    ) {
        MetalGpuTexture metalDst = (MetalGpuTexture) destination;
        flushPendingClearForWrite(metalDst);

        // 1.21.11 的 NativeImage 无 getPixels()：逐像素 getPixel（javap 实证返回 ARGB 0xAARRGGBB）
        int rowBytes = width * 4;
        int bytesPerImage = rowBytes * height;
        ByteBuffer region = MemoryUtil.memAlloc(bytesPerImage);
        try {
            for (int row = 0; row < height; row++) {
                // region 是目标区域缓冲（height*width*4），行从 0 排布；
                // 源坐标 (sourceX/sourceY) 仅用于 getPixel 取样，不得乘进行距
                int rowStart = row * rowBytes;
                for (int col = 0; col < width; col++) {
                    int argb = image.getPixel(sourceX + col, sourceY + row);
                    int pos = rowStart + col * 4;
                    // ARGB → RGBA 字节序（Metal RGBA8Unorm 期望 R,G,B,A）
                    region.put(pos, (byte) ((argb >> 16) & 0xFF));
                    region.put(pos + 1, (byte) ((argb >> 8) & 0xFF));
                    region.put(pos + 2, (byte) (argb & 0xFF));
                    region.put(pos + 3, (byte) ((argb >> 24) & 0xFF));
                }
            }
            region.position(0).limit(bytesPerImage);
            GpuBufferSlice slice = transientMemory.uploadStaging(region, 4L, GpuBuffer.USAGE_COPY_SRC, 0L, 1L);
            MTLBlitCommandEncoder blit = blitCommandEncoder();
            blit.copyFromBufferToTexture(
                    ((MetalGpuBuffer) slice.buffer()).nativeHandle(),
                    slice.offset(),
                    metalDst.nativeHandle(),
                    mipLevel,
                    depthOrLayer,
                    x,
                    y,
                    width,
                    height,
                    rowBytes,
                    bytesPerImage
            );
            // P24-1：blit 挂起（帧内合并——endEncoder 由 render/submit 路径统一兜底）
        } finally {
            MemoryUtil.memFree(region);
        }
    }

    @Override
    public void copyTextureToBuffer(final @NonNull GpuTexture source, final @NonNull GpuBuffer destination, final long offset, final @NonNull Runnable callback, final int mipLevel) {
        copyTextureToBuffer(source, destination, offset, callback, mipLevel, 0, 0, source.getWidth(mipLevel), source.getHeight(mipLevel));
    }

    @Override
    public void writeToTexture(final @NonNull GpuTexture destination, final @NonNull NativeImage image) {
        // 2 参重载：整图上传（1.21.11 的 writeToTexture(NativeImage) 无区域参数）
        writeToTexture(destination, image, 0, 0, 0, 0, image.getWidth(), image.getHeight(), 0, 0);
    }

    @Override
    public void copyTextureToBuffer(
            final @NonNull GpuTexture source,
            final @NonNull GpuBuffer destination,
            final long offset,
            final @NonNull Runnable callback,
            final int mipLevel,
            final int x,
            final int y,
            final int width,
            final int height
    ) {
        MetalGpuTexture texture = (MetalGpuTexture) source;
        flushPendingClear(texture);
        MetalGpuBuffer buffer = (MetalGpuBuffer) destination;
        int bytesPerPixel = texture.pixelSize();
        int rowBytes = width * bytesPerPixel;
        int bytesPerImage = rowBytes * height;

        MTLBlitCommandEncoder blit = blitCommandEncoder();
        blit.copyFromTextureToBuffer(
                texture.nativeHandle(),
                buffer.nativeHandle(),
                offset,
                mipLevel,
                0,
                x,
                y,
                width,
                height,
                rowBytes,
                bytesPerImage
        );
        // P24-1：blit 挂起（帧内合并——endEncoder 由 render/submit 路径统一兜底）
        queueForDestroy(callback);
    }

    @Override
    public void copyTextureToTexture(
            final @NonNull GpuTexture source,
            final @NonNull GpuTexture destination,
            final int mipLevel,
            final int destX,
            final int destY,
            final int sourceX,
            final int sourceY,
            final int width,
            final int height
    ) {
        MetalGpuTexture srcTexture = (MetalGpuTexture) source;
        MetalGpuTexture dstTexture = (MetalGpuTexture) destination;
        flushPendingClear(srcTexture);
        flushPendingClearForWrite(dstTexture);
        MTLBlitCommandEncoder blit = blitCommandEncoder();
        blit.copyFromTextureToTexture(
                srcTexture.nativeHandle(),
                dstTexture.nativeHandle(),
                mipLevel,
                sourceX,
                sourceY,
                destX,
                destY,
                width,
                height
        );
        // P24-1：blit 挂起（帧内合并——endEncoder 由 render/submit 路径统一兜底）
    }

    @Override
    public GpuBuffer.MappedView mapBuffer(final GpuBuffer buffer, final boolean read, final boolean write) {
        return mapBuffer(buffer.slice(0L, buffer.size()), read, write);
    }

    @Override
    public GpuBuffer.MappedView mapBuffer(final GpuBufferSlice slice, final boolean read, final boolean write) {
        // 1.21.11 的 GpuBuffer 无 map()：直接经 sliceStorage 映射（CPU 可访问缓冲）
        MetalGpuBuffer buffer = (MetalGpuBuffer) slice.buffer();
        if (buffer.isClosed()) {
            throw new IllegalStateException("Buffer already closed");
        }
        if (!read && !write) {
            throw new IllegalArgumentException("At least read or write must be true");
        }
        if (read && (buffer.usage() & GpuBuffer.USAGE_MAP_READ) == 0) {
            throw new IllegalStateException("Buffer is not readable");
        }
        if (write && (buffer.usage() & GpuBuffer.USAGE_MAP_WRITE) == 0) {
            throw new IllegalStateException("Buffer is not writable");
        }
        if (buffer.isShadowUploaded() && write && !read) {
            // P38（GUI 后段丢失修复）：Private 缓冲无 contents——CPU 写影子缓冲，
            // close 时经 writeToBuffer（staging+blit）上传——与世界渲染已实证的
            // 每帧上传路径一致（blit 在 render encoder 前被 fence 排序）。
            int len = Math.toIntExact(slice.length());
            ByteBuffer shadow = MemoryUtil.memAlloc(len);
            return new GpuBuffer.MappedView() {
                private boolean closed;

                @Override
                public ByteBuffer data() {
                    return shadow;
                }

                @Override
                public void close() {
                    if (closed) {
                        return;
                    }
                    closed = true;
                    try {
                        shadow.clear();
                        writeToBuffer(slice, shadow);
                    } finally {
                        MemoryUtil.memFree(shadow);
                    }
                }
            };
        }
        ByteBuffer mapped = buffer.sliceStorage(slice.offset(), slice.length());
        return new GpuBuffer.MappedView() {
            @Override
            public ByteBuffer data() {
                return mapped;
            }

            @Override
            public void close() {
            }
        };
    }

    @Override
    public @NonNull GpuFence createFence() {
        return new MetalFence(this, currentSubmitIndex);
    }

    @Override
    public @NonNull GpuQuery timerQueryBegin() {
        // 时间戳查询为空实现：返回无值 query
        return new GpuQuery() {
            @Override
            public OptionalLong getValue() {
                return OptionalLong.empty();
            }

            @Override
            public void close() {
            }
        };
    }

    @Override
    public void timerQueryEnd(final @NonNull GpuQuery query) {
        // 1.21.11 无 GpuQueryPool：时间戳查询为空实现
    }

    void queueForDestroy(final Runnable destroyAction) {
        destroyQueue.add(destroyAction);
    }

    boolean awaitSubmitCompletion(final long submitIndex, final long timeoutMs) {
        if (submitIndex == currentSubmitIndex) {
            if (timeoutMs == 0L) {
                return false;
            }
            throw new IllegalStateException("Cannot wait on a fence for the current submit");
        }
        for (InFlight f : inFlight) {
            if (f != null && f.index == submitIndex) {
                return MetalNativeBridge.metallum_semaphore_wait(f.completedSemaphore, Math.max(timeoutMs, 0L)) == 0;
            }
        }
        return true;
    }

    void close() {
        submitRenderPass();
        endEncoder();
        for (int slot = 0; slot < inFlight.length; slot++) {
            InFlight f = inFlight[slot];
            if (f != null) {
                f.buffer.close();
                inFlight[slot] = null;
            }
        }
        for (int slot = 0; slot < submitSemaphores.length; slot++) {
            if (!MetalNativeBridge.isNullHandle(submitSemaphores[slot])) {
                MetalNativeBridge.metallum_release_object(submitSemaphores[slot]);
                submitSemaphores[slot] = MemorySegment.NULL;
            }
        }
        if (commandBuffer != null) {
            commandBuffer.close();
            commandBuffer = null;
        }
        transientMemory.close();
        device.queueResourceRelease(fence);
        destroyQueue.close();
        for (java.util.ArrayDeque<MemorySegment> bucket : dynamicBackingPool.values()) {
            for (MemorySegment handle : bucket) {
                MetalNativeBridge.metallum_release_object(handle);
            }
        }
        dynamicBackingPool.clear();
    }

    void waitForSubmittedGpuWork() {
        if (commandBuffer != null || currentRenderPass != null || currentEncoder != null) {
            submit();
        } else {
            endEncoder();
        }
        long latestSubmit = currentSubmitIndex - 1L;
        if (latestSubmit >= MAX_SUBMITS_IN_FLIGHT) {
            awaitSubmitCompletion(latestSubmit, Long.MAX_VALUE);
        }
    }

    private void flushPendingClearForWrite(final MetalGpuTexture texture) {
        flushPendingClear(texture);
        texture.markContentsDirty();
    }

    void flushPendingClear(final MetalGpuTexture texture) {
        Vector4fc colorClear = pendingColorClears.remove(texture);
        Double depthClear = pendingDepthClears.remove(texture);
        if (colorClear == null && depthClear == null) {
            return;
        }

        if (texture.clearIsRedundant(colorClear, depthClear)) {
            return;
        }

        endEncoder();
        MTLRenderCommandEncoder encoder = commandBuffer().makeRenderCommandEncoder(
                colorClear != null ? texture.nativeHandle() : null,
                depthClear != null ? texture.nativeHandle() : null,
                1.0, 1.0,
                colorClear != null ? 1 : 0,
                colorClear != null ? colorClear.x() : 0.0F,
                colorClear != null ? colorClear.y() : 0.0F,
                colorClear != null ? colorClear.z() : 0.0F,
                colorClear != null ? colorClear.w() : 0.0F,
                depthClear != null ? 1 : 0,
                depthClear != null ? depthClear : 1.0
        );
        encoder.waitForFence(fence, MTLRenderStages.VertexAndFragment);
        currentEncoder = encoder;
        texture.recordMaterializedClear(colorClear, depthClear);
    }

    /**
     * 深度纹理读回（Depth32Float）：主 pass 后读 4×4 采样，看 clear 值与是否被
     * 方块写入。均匀=只有 clear（方块未写深度）；非均匀=方块渲染了。
     */
    private static boolean isFullTextureView(final GpuTextureView textureView) {
        return textureView.baseMipLevel() == 0
                && textureView.mipLevels() >= textureView.texture().getMipLevels()
                && textureView.texture().getDepthOrLayers() == 1;
    }

    private static boolean isFullTextureRegion(
            final MetalGpuTexture color,
            final MetalGpuTexture depth,
            final int x,
            final int y,
            final int width,
            final int height
    ) {
        return x == 0
                && y == 0
                && width == color.getWidth(0)
                && height == color.getHeight(0)
                && width == depth.getWidth(0)
                && height == depth.getHeight(0);
    }

    private record InFlight(long index, MTLCommandBuffer buffer, MemorySegment completedSemaphore) {
    }
}
