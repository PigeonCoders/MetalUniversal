package com.metallum.client.metal.render.sodium;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MetalSodiumCompiledPipeline MSL 资源提取测试。
 *
 * <p>输入为实测产出（shaderc GL 方言 + spvc MSL 4.0，Sodium 0.8.13
 * block_layer_opaque）：普通 uniform 各自独立 constant T&amp; buffer 参数、
 * ChunkData block 变量名被重命名（_197）但类型名保留、texture/sampler 同 index。
 * 该测试锁定提取键表——Sodium 升级导致索引变化时，仅当 MSL 形态不变才继续成立。
 */
class MetalSodiumCompiledPipelineMslExtractTest {

    private static final String VERTEX_MSL = """
            struct ChunkData
            {
                int4 u_chunkFades[64];
            };
            struct main0_out
            {
                float4 gl_Position [[position]];
                float4 v_Color;
                float2 v_TexCoord;
                uint v_Material;
                float2 v_FragDistance;
                float fadeFactor;
            };
            struct main0_in
            {
                uint2 a_Position [[attribute(0)]];
                float4 a_Color [[attribute(1)]];
                uint2 a_TexCoord [[attribute(2)]];
                uint4 a_LightAndData [[attribute(3)]];
            };
            vertex main0_out main0(main0_in in [[stage_in]], constant int& u_CurrentTime [[buffer(5)]], constant float4x4& u_ProjectionMatrix [[buffer(0)]], constant float4x4& u_ModelViewMatrix [[buffer(1)]], constant float3& u_RegionOffset [[buffer(2)]], constant float2& u_TexCoordShrink [[buffer(3)]], constant float& u_FadePeriodInv [[buffer(6)]], constant ChunkData& _197 [[buffer(7)]], texture2d<float> u_LightTex [[texture(4)]], sampler u_LightTexSmplr [[sampler(4)]])
            {
            }
            """;

    private static final String FRAGMENT_MSL = """
            fragment main0_out main0(main0_in in [[stage_in]], constant bool& u_UseRGSS [[buffer(5)]], constant float4& u_FogColor [[buffer(1)]], constant float2& u_EnvironmentFog [[buffer(2)]], constant float2& u_RenderFog [[buffer(3)]], constant float2& u_TexelSize [[buffer(4)]], texture2d<float> u_BlockTex [[texture(0)]], sampler u_BlockTexSmplr [[sampler(0)]])
            {
            }
            """;

    @Test
    void extractsVertexBuffers() {
        Map<String, Integer> buffers = MetalSodiumCompiledPipeline.extractBuffers(VERTEX_MSL, "ChunkData");
        assertEquals(7, buffers.size());
        assertEquals(0, buffers.get("u_ProjectionMatrix"));
        assertEquals(1, buffers.get("u_ModelViewMatrix"));
        assertEquals(2, buffers.get("u_RegionOffset"));
        assertEquals(3, buffers.get("u_TexCoordShrink"));
        assertEquals(5, buffers.get("u_CurrentTime"));
        assertEquals(6, buffers.get("u_FadePeriodInv"));
        // block 变量名被 spvc 重命名 → 按类型名提取
        assertEquals(7, buffers.get("ChunkData"));
    }

    @Test
    void extractsVertexTextures() {
        Map<String, Integer> textures = MetalSodiumCompiledPipeline.extractTextures(VERTEX_MSL);
        assertEquals(1, textures.size());
        assertEquals(4, textures.get("u_LightTex"));
    }

    @Test
    void extractsFragmentBuffers() {
        Map<String, Integer> buffers = MetalSodiumCompiledPipeline.extractBuffers(FRAGMENT_MSL, "ChunkData");
        assertEquals(5, buffers.size());
        assertEquals(1, buffers.get("u_FogColor"));
        assertEquals(2, buffers.get("u_EnvironmentFog"));
        assertEquals(3, buffers.get("u_RenderFog"));
        assertEquals(4, buffers.get("u_TexelSize"));
        assertEquals(5, buffers.get("u_UseRGSS"));
    }

    @Test
    void extractsFragmentTextures() {
        Map<String, Integer> textures = MetalSodiumCompiledPipeline.extractTextures(FRAGMENT_MSL);
        assertEquals(1, textures.size());
        assertEquals(0, textures.get("u_BlockTex"));
    }
}
