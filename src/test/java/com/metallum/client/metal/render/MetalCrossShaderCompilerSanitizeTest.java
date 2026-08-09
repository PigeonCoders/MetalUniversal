package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MetalMslSanitizer 的 sampler 标识符冲突处理（fix7：iOS 实测崩溃
 * "'sampler' does not refer to a value"——声明改名后使用点残留）。
 *
 * <p>形态来源：Sodium fsh 的 sampleNearest(sampler2D sampler, ...) 参数名恰为
 * sampler（GLSL 合法）→ spvc 生成 texture2d<float> sampler 参数 + 使用点
 * sampler.sample(...) → 与 MSL 内建 struct sampler 类型冲突。
 */
class MetalCrossShaderCompilerSanitizeTest {

    @Test
    void sanitizeRenamesDeclarationAndUsePoint() {
        // sampleNearest 形态（Sodium fsh 转换）：声明 + 使用点都必须改名
        String msl = """
                float4 sampleNearest(texture2d<float> sampler, sampler samplerSmplr, float2 uv, float2 du, float2 dv) {
                    return sampler.sample(samplerSmplr, uv, gradient2d(du, dv));
                }""";
        String out = MetalMslSanitizer.sanitizeMsl(msl);

        assertTrue(out.contains("texture2d<float> samplerTex,"), "声明应改名为 samplerTex: " + out);
        assertTrue(out.contains("samplerTex.sample(samplerSmplr, uv, gradient2d(du, dv))"), "使用点应同步改名: " + out);
        // 类型声明位置的 sampler 必须保留（sampler samplerSmplr）
        assertTrue(out.contains("sampler samplerSmplr"), "类型声明位置的 sampler 不能改: " + out);
        assertFalse(out.contains("sampler.sample"), "不得残留未改名的使用点: " + out);
    }

    @Test
    void sanitizeLeavesNonSamplerNamesUntouched() {
        // MC 主管线形态：纹理参数名 u_BlockTex/u_BlockTexSmplr——零影响
        String msl = """
                fragment main0_out main0(main0_in in [[stage_in]], texture2d<float> u_BlockTex [[texture(0)]], sampler u_BlockTexSmplr [[sampler(0)]]) {
                    return u_BlockTex.sample(u_BlockTexSmplr, uv);
                }""";
        String out = MetalMslSanitizer.sanitizeMsl(msl);

        assertEquals(msl, out, "无 sampler 变量时应原样返回");
    }

    @Test
    void sanitizeRenamesPlainSampleUsePoint() {
        // 非梯度采样形态（无 gradient2d）的使用点同样要改
        String msl = """
                texture2d<float> sampler [[texture(0)]]
                float4 main() {
                    return sampler.sample(samplerSmplr, uv);
                }""";
        String out = MetalMslSanitizer.sanitizeMsl(msl);

        assertTrue(out.contains("texture2d<float> samplerTex [[texture(0)]]"), "声明改名: " + out);
        assertTrue(out.contains("samplerTex.sample(samplerSmplr, uv)"), "使用点改名: " + out);
    }

    @Test
    void sanitizeKeepsSamplerTypeDeclaration() {
        // 只含类型声明（sampler samplerX）无 texture2d 参数——必须原样（不能被误改）
        String msl = """
                texture2d<float> u_BlockTex [[texture(0)]]
                sampler u_BlockTexSmplr [[sampler(0)]]
                float4 main() {
                    return u_BlockTex.sample(u_BlockTexSmplr, uv);
                }""";
        String out = MetalMslSanitizer.sanitizeMsl(msl);

        assertEquals(msl, out);
    }
}
