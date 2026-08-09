package com.metallum.mixin;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MetallumMixinConfigPlugin implements IMixinConfigPlugin {
    private static final String RENDER_SYSTEM_DEVICE_MIXIN = "com.metallum.mixin.render.RenderSystemDeviceMixin";
    private static final String GLFW_SWAP_BUFFERS_MIXIN = "com.metallum.mixin.render.GLFWSwapBuffersMixin";
    private static final String GLFW_TERMINATE_MIXIN = "com.metallum.mixin.render.GLFWTerminateMixin";
    private static final String RENDER_SYSTEM_GLOBALS_MIXIN = "com.metallum.mixin.render.RenderSystemGlobalsMixin";
    private static final String LEVEL_RENDERER_DIAG_MIXIN = "com.metallum.mixin.render.LevelRendererDiagMixin";

    private boolean isMetalHost;

    @Override
    public void onLoad(String mixinPackage) {
        String osName = System.getProperty("os.name", "");
        // 26.2 时代按 os.name 含 mac 判定；1.21.11 无 PreferredGraphicsApi 后端选择，
        // mixin 恒启用（Metal 后端总是接管），平台判定复用 MetalNativeBridge.isIOS 的完整链路
        // （iOS JVM 可能谎报 os.name，必须走沙箱路径等信号）。
        this.isMetalHost = osName.toLowerCase(Locale.ROOT).contains("mac")
                || osName.toLowerCase(Locale.ROOT).contains("ios")
                || MetalNativeBridge.isIOS();
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!this.isMetalHost) {
            return false;
        }
        if (mixinClassName.contains(".mixin.sodium.")) {
            // sodium 适配 mixin 仅 Metal 主机 + sodium 已加载时应用（GL 主机回归原行为）
            return this.isMetalHost && FabricLoader.getInstance().isModLoaded("sodium");
        }
        // 阶段 5：LevelRendererDiagMixin 与 Sodium LevelRendererMixin 的
        // @Overwrite cullTerrain 硬冲突（同一方法不能同时 @Overwrite + @Inject，
        // 应用器报错 → 游戏启动失败）——sodium 加载时禁用（代价：失去
        // visibleSections 诊断输出，后续可做 Sodium 版诊断 mixin）。
        boolean sodiumLoaded = FabricLoader.getInstance().isModLoaded("sodium");
        return RENDER_SYSTEM_DEVICE_MIXIN.equals(mixinClassName) || GLFW_SWAP_BUFFERS_MIXIN.equals(mixinClassName)
                || GLFW_TERMINATE_MIXIN.equals(mixinClassName) || RENDER_SYSTEM_GLOBALS_MIXIN.equals(mixinClassName)
                || (LEVEL_RENDERER_DIAG_MIXIN.equals(mixinClassName) && !sodiumLoaded);
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
