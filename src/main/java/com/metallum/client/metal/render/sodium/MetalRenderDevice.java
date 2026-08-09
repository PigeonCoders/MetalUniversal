package com.metallum.client.metal.render.sodium;

import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.gl.device.RenderDevice;
import net.caffeinemc.mods.sodium.client.gl.functions.DeviceFunctions;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.FunctionProvider;

import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * SODIUM-ADAPT：Metal 版 RenderDevice（阶段 3，纯新增；mixin 接线在阶段 5）。
 *
 * <p>Sodium 的 {@link RenderDevice#INSTANCE} 是 static final 硬编码
 * {@code new GLRenderDevice()}——阶段 5 用 mixin 替换 <clinit>。本类实现其接口面：
 *
 * <ul>
 *   <li>createCommandList → 单例 MetalSodiumCommandList（与 GL 版同构；Sodium 的
 *       renderLayer 只 flush() 不 close()，单例安全——实证）</li>
 *   <li>getCapabilities → {@code new GLCapabilities()}：LWJGL 无参构造全字段 false，
 *       <b>不读当前 GL context</b>（JUnit 验证）→ BufferStorageFunctions.pickBest
 *       自然得 NONE → Sodium 自动走 FallbackStagingBuffer（GL 耦合短路）</li>
 *   <li>getDeviceFunctions → DeviceFunctions（内部 pickBest → NONE）</li>
 *   <li>getSubTexelPrecisionBits → 4（与 macOS GL 语义一致，Metal 无此概念）</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public final class MetalRenderDevice implements RenderDevice {
    // ⚠️ 静态初始化顺序：FAKE_CAPABILITIES 必须声明在 INSTANCE 之前——
    // INSTANCE 构造时字段初始化会调 getCapabilities()（pickBest），
    // 若 FAKE_CAPABILITIES 尚未初始化则读到 null（曾踩坑：NPE "capabilities is null"）
    private static final GLCapabilities FAKE_CAPABILITIES = createFakeCapabilities();

    /** 单例（阶段 5 mixin 替换 RenderDevice.INSTANCE 的目标）。 */
    public static final RenderDevice INSTANCE = new MetalRenderDevice();

    private final CommandList commandList = new MetalSodiumCommandList();
    private final DeviceFunctions functions = new DeviceFunctions(this);

    private boolean isActive;

    @Override
    public CommandList createCommandList() {
        this.checkDeviceActive();
        return this.commandList;
    }

    @Override
    public void makeActive() {
        this.isActive = true;
    }

    @Override
    public void makeInactive() {
        this.isActive = false;
    }

    /**
     * 假 capabilities：LWJGL 的 GLCapabilities 无无参构造（4 参：
     * FunctionProvider / 扩展集 / forwardCompatible / PointerBuffer 回调）。
     * 传入空扩展集 → 各 check_GLXX 首行 {@code set.contains("OpenGLXX")} 短路返回
     * false，provider/回调全程不被调用（字节码实证）——零 GL 调用。
     * OpenGL44 / GL_ARB_buffer_storage 均 false → BufferStorageFunctions.pickBest
     * 得 NONE → Sodium 的 GL 能力探测全部走保守路径（FallbackStagingBuffer）。
     */
    private static GLCapabilities createFakeCapabilities() {
        FunctionProvider provider = new FunctionProvider() {
            @Override
            public long getFunctionAddress(final ByteBuffer name) {
                return 0L;
            }
        };
        try {
            // GLCapabilities 的 4 参构造是 package-private（lwjgl-opengl 包内），包外只能反射。
            // 空扩展集 → 各 check_GLXX 首行短路，provider/回调全程不被调用（字节码实证）。
            Constructor<GLCapabilities> ctor = GLCapabilities.class.getDeclaredConstructor(
                    FunctionProvider.class, Set.class, boolean.class, IntFunction.class
            );
            ctor.setAccessible(true);
            // 2228 = lwjgl GLCapabilities.ADDRESS_BUFFER_SIZE（包内私有常量，无法外部读取）：
            // 构造体按此容量访问 PointerBuffer 槽位，容量不足抛 IndexOutOfBoundsException
            // （曾踩坑：allocateDirect(0)/(1) 分别 Index 0/1 越界）。空扩展集下各
            // check_GLXX 首行短路，槽内容不会被真实使用，但容量必须给足。
            return ctor.newInstance(provider, Set.of(), false, (IntFunction<PointerBuffer>) i -> PointerBuffer.allocateDirect(2228));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to create fake GLCapabilities", e);
        }
    }

    @Override
    public GLCapabilities getCapabilities() {
        return FAKE_CAPABILITIES;
    }

    @Override
    public DeviceFunctions getDeviceFunctions() {
        return this.functions;
    }

    @Override
    public int getSubTexelPrecisionBits() {
        // Metal 无 sub-texel 概念；与 macOS GL 的 4bit 对齐（GLRenderDevice MAC 分支同值）
        return 4;
    }

    private void checkDeviceActive() {
        if (!this.isActive) {
            throw new IllegalStateException("Tried to access device from unmanaged context");
        }
    }
}
