package com.metallum.client.metal.render;

import java.util.regex.Pattern;

/**
 * MSL 后处理 sanitize：处理 SPIRV-Cross 生成的 MSL 中与 MSL 内建类型冲突的标识符。
 *
 * <p>独立工具类（无静态依赖）：MetalCrossShaderCompiler 的静态初始化需要 Metal
 * native bridge（Linux 测试环境无 dylib 无法加载该类），抽离后纯字符串转换可本地 JUnit。
 *
 * <p>⚠️ sampler 标识符冲突（fix7：iOS 实测崩溃 "'sampler' does not refer to a value"，
 * MTLLibraryErrorDomain Code=3）：SPIRV-Cross 保留 GLSL 参数名——Sodium fsh 的
 * sampleNearest(sampler2D sampler, ...) 参数名恰为 sampler（GLSL 合法）→ spvc 生成
 * `texture2d<float> sampler` 参数声明 + 使用点 `sampler.sample(samplerSmplr, uv,
 * gradient2d(du, dv))`（textureGrad 的 MSL 形态）→ 声明遮蔽 MSL 内建 struct sampler
 * 类型。仅改声明不改使用点（旧实现）时，残留的 sampler 会被 clang 解析为内建类型
 * → 编译失败 → MTLFunction 为空 → pipeline invalid。本地 shaderc/spvc 只生成不编译
 * MSL，语法校验只有 iOS MTLCompiler 能做。
 *
 * <p>两个 pattern 互不重叠，精确替换：
 * <ul>
 *   <li>声明：texture2d&lt;...&gt; sampler → samplerTex（类型名位置的 sampler——
 *       `sampler samplerSmplr` 中前者——前缀非 texture2d&lt;...&gt; 不被匹配；
 *       sampler2D/Sampler0 因 \b 边界不受影响）</li>
 *   <li>使用点：sampler.sample( → samplerTex.sample(（MSL texture 方法调用形态，
 *       与类型声明不重叠）</li>
 * </ul>
 */
public final class MetalMslSanitizer {
    private static final Pattern MSL_SAMPLER_PARAM_PATTERN = Pattern.compile("(\\btexture2d\\s*<[^>]+>\\s+)sampler\\b");
    private static final Pattern MSL_SAMPLER_USE_PATTERN = Pattern.compile("\\bsampler\\.sample\\b");

    private MetalMslSanitizer() {
    }

    public static String sanitizeMsl(final String msl) {
        String out = MSL_SAMPLER_PARAM_PATTERN.matcher(msl).replaceAll("$1samplerTex");
        return MSL_SAMPLER_USE_PATTERN.matcher(out).replaceAll("samplerTex.sample");
    }
}
