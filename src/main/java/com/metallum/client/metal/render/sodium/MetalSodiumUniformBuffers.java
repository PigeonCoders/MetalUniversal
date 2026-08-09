package com.metallum.client.metal.render.sodium;

import com.metallum.client.metal.render.MetalGpuBuffer;
import com.mojang.blaze3d.buffers.GpuBuffer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Matrix4fc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * SODIUM-ADAPT：Sodium terrain shader 的 uniform 值存储（阶段 3）。
 *
 * <p>实测 MSL 里普通 uniform 是独立 buffer 参数（{@code constant T& u_X [[buffer(n)]]}，
 * 非合成 UBO）——每个 uniform 一个 Shared MetalGpuBuffer，CPU 直接写入
 * sliceStorage，GPU 经 setBuffer 直读（Shared memory 免 staging）。
 *
 * <p>⚠️ 已知取舍（计划文件"阶段 3 遗留"）：CPU 覆写与 GPU 读取无 fence 同步
 * （Untracked hazard）——uniform 数据量小（<200B/region/帧）、帧序风险低，第一版
 * 接受；若实测出现闪烁，阶段 6 改 ring buffer。
 *
 * <p>布局对齐：MSL 的 float3 对齐 16B（buffer 按 16 分配，数据写前 12B）；
 * float2 8B / float 4B / float4 16B / float4x4 64B；bool 按 4B 写 0/1。
 */
@Environment(EnvType.CLIENT)
public final class MetalSodiumUniformBuffers implements AutoCloseable {
    private static final int USAGE = GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST;

    private final MetalGpuBuffer projection;     // u_ProjectionMatrix float4x4 (64B)
    private final MetalGpuBuffer modelView;      // u_ModelViewMatrix  float4x4 (64B)
    private final MetalGpuBuffer regionOffset;   // u_RegionOffset     float3  (16B)
    private final MetalGpuBuffer texCoordShrink; // u_TexCoordShrink   float2  (8B)
    private final MetalGpuBuffer currentTime;    // u_CurrentTime      int     (4B)
    private final MetalGpuBuffer fadePeriodInv;  // u_FadePeriodInv    float   (4B)
    private final MetalGpuBuffer fogColor;       // u_FogColor         float4  (16B)
    private final MetalGpuBuffer environmentFog; // u_EnvironmentFog   float2  (8B)
    private final MetalGpuBuffer renderFog;      // u_RenderFog        float2  (8B)
    private final MetalGpuBuffer texelSize;      // u_TexelSize        float2  (8B)
    private final MetalGpuBuffer useRGSS;        // u_UseRGSS          bool    (4B)

    public MetalSodiumUniformBuffers(final com.metallum.client.metal.render.MetalDevice device) {
        this.projection = new MetalGpuBuffer(device, USAGE, 64L);
        this.modelView = new MetalGpuBuffer(device, USAGE, 64L);
        this.regionOffset = new MetalGpuBuffer(device, USAGE, 16L);
        this.texCoordShrink = new MetalGpuBuffer(device, USAGE, 8L);
        this.currentTime = new MetalGpuBuffer(device, USAGE, 4L);
        this.fadePeriodInv = new MetalGpuBuffer(device, USAGE, 4L);
        this.fogColor = new MetalGpuBuffer(device, USAGE, 16L);
        this.environmentFog = new MetalGpuBuffer(device, USAGE, 8L);
        this.renderFog = new MetalGpuBuffer(device, USAGE, 8L);
        this.texelSize = new MetalGpuBuffer(device, USAGE, 8L);
        this.useRGSS = new MetalGpuBuffer(device, USAGE, 4L);
    }

    /** 按 MSL buffer 参数名取 buffer；ChunkData 等外部 buffer 返回 null（调用方特殊处理）。 */
    @org.jspecify.annotations.Nullable
    public MetalGpuBuffer forBinding(final String name) {
        return switch (name) {
            case "u_ProjectionMatrix" -> this.projection;
            case "u_ModelViewMatrix" -> this.modelView;
            case "u_RegionOffset" -> this.regionOffset;
            case "u_TexCoordShrink" -> this.texCoordShrink;
            case "u_CurrentTime" -> this.currentTime;
            case "u_FadePeriodInv" -> this.fadePeriodInv;
            case "u_FogColor" -> this.fogColor;
            case "u_EnvironmentFog" -> this.environmentFog;
            case "u_RenderFog" -> this.renderFog;
            case "u_TexelSize" -> this.texelSize;
            case "u_UseRGSS" -> this.useRGSS;
            default -> null;
        };
    }

    /** 把 interface 缓存的全部 uniform 值写入各 Shared buffer（每 region 一次，开销可忽略）。 */
    public void uploadAll(final MetalSodiumShaderInterface shaderInterface) {
        Matrix4fc projection = shaderInterface.projectionMatrix();
        Matrix4fc modelView = shaderInterface.modelViewMatrix();
        if (projection != null) {
            writeMatrix(this.projection, projection);
        }
        if (modelView != null) {
            writeMatrix(this.modelView, modelView);
        }
        writeFloats(this.regionOffset, 16,
                shaderInterface.regionOffsetX(), shaderInterface.regionOffsetY(), shaderInterface.regionOffsetZ());
        writeFloats(this.texCoordShrink, 8, shaderInterface.texCoordShrinkX(), shaderInterface.texCoordShrinkY());
        writeInt(this.currentTime, shaderInterface.currentTime());
        writeFloat(this.fadePeriodInv, shaderInterface.fadePeriodInv());
        writeFloats(this.fogColor, 16,
                shaderInterface.fogColorR(), shaderInterface.fogColorG(), shaderInterface.fogColorB(), shaderInterface.fogColorA());
        writeFloats(this.environmentFog, 8, shaderInterface.environmentFogStart(), shaderInterface.environmentFogEnd());
        writeFloats(this.renderFog, 8, shaderInterface.renderFogStart(), shaderInterface.renderFogEnd());
        writeFloats(this.texelSize, 8, shaderInterface.texelSizeX(), shaderInterface.texelSizeY());
        writeInt(this.useRGSS, shaderInterface.useRGSS() ? 1 : 0);
    }

    private static void writeMatrix(final MetalGpuBuffer buffer, final Matrix4fc matrix) {
        // JOML 列主序布局与 MSL float4x4（column-major）一致
        float[] values = new float[16];
        matrix.get(values);
        ByteBuffer dst = buffer.currentStorage().order(ByteOrder.nativeOrder());
        dst.rewind();
        dst.asFloatBuffer().put(values);
    }

    private static void writeFloats(final MetalGpuBuffer buffer, final int bufferSize, final float... values) {
        ByteBuffer dst = buffer.currentStorage().order(ByteOrder.nativeOrder());
        dst.rewind();
        dst.limit(bufferSize);
        dst.asFloatBuffer().put(values);
    }

    private static void writeFloat(final MetalGpuBuffer buffer, final float value) {
        buffer.currentStorage().order(ByteOrder.nativeOrder()).rewind().putFloat(value);
    }

    private static void writeInt(final MetalGpuBuffer buffer, final int value) {
        buffer.currentStorage().order(ByteOrder.nativeOrder()).rewind().putInt(value);
    }

    @Override
    public void close() {
        this.projection.close();
        this.modelView.close();
        this.regionOffset.close();
        this.texCoordShrink.close();
        this.currentTime.close();
        this.fadePeriodInv.close();
        this.fogColor.close();
        this.environmentFog.close();
        this.renderFog.close();
        this.texelSize.close();
        this.useRGSS.close();
    }
}
