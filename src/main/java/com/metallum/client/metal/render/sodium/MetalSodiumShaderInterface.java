package com.metallum.client.metal.render.sodium;

import com.metallum.Metallum;
import com.metallum.client.metal.render.MetalGpuBuffer;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBuffer;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderTextureSlot;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.impl.CompactChunkVertex;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.caffeinemc.mods.sodium.mixin.core.render.texture.TextureAtlasAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * Sodium ChunkShaderInterface 的 Metal 实现（阶段 2 骨架：uniform 值缓存 + setter；
 * 实际 encoder 绑定在阶段 4 的 MetalSodiumShaderChunkRenderer 内完成）。
 *
 * <p>与 GL 版 DefaultShaderInterface 的差异：uniform 不绑定 GL location，而是
 * 缓存为 Java 字段；绑定阶段按 MetalSodiumCompiledPipeline 的按名资源表
 * （buffer/texture index）写入 encoder。值语义与 GL 版完全一致
 * （subTexelPrecision 固定 4——mac GL 与 Metal 的采样精度语义一致）。
 */
public final class MetalSodiumShaderInterface implements ChunkShaderInterface {
    private static final int SUB_TEXEL_PRECISION_BITS = 4;

    private final Matrix4f projectionMatrix = new Matrix4f();
    private final Matrix4f modelViewMatrix = new Matrix4f();
    private float regionOffsetX;
    private float regionOffsetY;
    private float regionOffsetZ;
    private float texCoordShrinkX;
    private float texCoordShrinkY;
    private float texelSizeX;
    private float texelSizeY;
    private boolean useRGSS;
    private int currentTime;
    private float fadePeriodInv;
    private float fogColorR;
    private float fogColorG;
    private float fogColorB;
    private float fogColorA;
    private float environmentFogStart;
    private float environmentFogEnd;
    private float renderFogStart;
    private float renderFogEnd;

    @Nullable
    private MetalGpuBuffer chunkDataBuffer;
    @Nullable
    private GpuTextureView blockTex;
    @Nullable
    private GpuTextureView lightTex;
    @Nullable
    private GpuSampler terrainSampler;

    @Override
    public void setupState(final TerrainRenderPass pass, final FogParameters parameters, final GpuSampler sampler) {
        this.blockTex = pass.getAtlas();
        this.lightTex = Minecraft.getInstance().gameRenderer.lightTexture().getTextureView();
        this.terrainSampler = sampler;

        var textureAtlas = (TextureAtlasAccessor) Minecraft.getInstance()
                .getTextureManager()
                .getTexture(TextureAtlas.LOCATION_BLOCKS);

        double subTexelPrecision = (1 << SUB_TEXEL_PRECISION_BITS);
        double subTexelOffset = 1.0f / CompactChunkVertex.TEXTURE_MAX_VALUE;

        this.texCoordShrinkX = (float) (subTexelOffset - (((1.0D / textureAtlas.sodium$getWidth()) / subTexelPrecision)));
        this.texCoordShrinkY = (float) (subTexelOffset - (((1.0D / textureAtlas.sodium$getHeight()) / subTexelPrecision)));

        this.texelSizeX = 1.0f / textureAtlas.sodium$getWidth();
        this.texelSizeY = 1.0f / textureAtlas.sodium$getHeight();

        this.fadePeriodInv = (float) (1.0 / (Minecraft.getInstance().options.chunkSectionFadeInTime().get() * 1000.0));

        this.useRGSS = Minecraft.getInstance().options.textureFiltering().get() == TextureFilteringMethod.RGSS;

        this.fogColorR = parameters.red();
        this.fogColorG = parameters.green();
        this.fogColorB = parameters.blue();
        this.fogColorA = parameters.alpha();
        this.environmentFogStart = parameters.environmentalStart();
        this.environmentFogEnd = parameters.environmentalEnd();
        this.renderFogStart = parameters.renderStart();
        this.renderFogEnd = parameters.renderEnd();
    }

    @Override
    public void resetState() {
        this.blockTex = null;
        this.lightTex = null;
        this.terrainSampler = null;
        this.chunkDataBuffer = null;
    }

    @Override
    public void setProjectionMatrix(final Matrix4fc matrix) {
        this.projectionMatrix.set(matrix);
    }

    @Override
    public void setModelViewMatrix(final Matrix4fc matrix) {
        this.modelViewMatrix.set(matrix);
    }

    @Override
    public void setRegionOffset(final float x, final float y, final float z) {
        this.regionOffsetX = x;
        this.regionOffsetY = y;
        this.regionOffsetZ = z;
    }

    @Override
    public void setChunkData(final GlBuffer data, final int time) {
        MetalGlBufferRegistry.MetalGlBufferEntry entry = MetalGlBufferRegistry.get(data.handle());
        this.chunkDataBuffer = entry == null ? null : entry.buffer();
        this.currentTime = time;
    }

    // ---- 阶段 4 绑定点（MetalSodiumShaderChunkRenderer 读取） ----

    public Matrix4fc projectionMatrix() {
        return this.projectionMatrix;
    }

    public Matrix4fc modelViewMatrix() {
        return this.modelViewMatrix;
    }

    public float regionOffsetX() {
        return this.regionOffsetX;
    }

    public float regionOffsetY() {
        return this.regionOffsetY;
    }

    public float regionOffsetZ() {
        return this.regionOffsetZ;
    }

    public float texCoordShrinkX() {
        return this.texCoordShrinkX;
    }

    public float texCoordShrinkY() {
        return this.texCoordShrinkY;
    }

    public float texelSizeX() {
        return this.texelSizeX;
    }

    public float texelSizeY() {
        return this.texelSizeY;
    }

    public boolean useRGSS() {
        return this.useRGSS;
    }

    public int currentTime() {
        return this.currentTime;
    }

    public float fadePeriodInv() {
        return this.fadePeriodInv;
    }

    public float fogColorR() {
        return this.fogColorR;
    }

    public float fogColorG() {
        return this.fogColorG;
    }

    public float fogColorB() {
        return this.fogColorB;
    }

    public float fogColorA() {
        return this.fogColorA;
    }

    public float environmentFogStart() {
        return this.environmentFogStart;
    }

    public float environmentFogEnd() {
        return this.environmentFogEnd;
    }

    public float renderFogStart() {
        return this.renderFogStart;
    }

    public float renderFogEnd() {
        return this.renderFogEnd;
    }

    @Nullable
    public MetalGpuBuffer chunkDataBuffer() {
        return this.chunkDataBuffer;
    }

    @Nullable
    public GpuTextureView blockTex() {
        return this.blockTex;
    }

    @Nullable
    public GpuTextureView lightTex() {
        return this.lightTex;
    }

    @Nullable
    public GpuSampler terrainSampler() {
        return this.terrainSampler;
    }
}
