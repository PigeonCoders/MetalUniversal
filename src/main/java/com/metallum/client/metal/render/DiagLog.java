package com.metallum.client.metal.render;

import com.metallum.Metallum;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 诊断日志独立文件输出：写入与 latest.log 同目录的 metallum-diag.log，
 * 主日志（log4j）保持干净，避免 iOS 上 error 级日志刷屏（卡死先例，见 AGENTS §12）。
 *
 * <p>窗口控制（-Dmetallum.diag.window=秒，默认 120）：启动后仅窗口期内写文件，
 * 测试"进世界看 2 分钟"即可产出全部诊断；0 表示不限。
 * 总开关 -Dmetallum.diag=false 可关闭（与 Diagnostics 一致）。
 *
 * <p>核心层不依赖 mod loader：日志目录由胶水层（Metallum.onInitialize）注入。
 */
public final class DiagLog {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("metallum.diag", "true"));
    private static final long WINDOW_SECONDS = Long.parseLong(System.getProperty("metallum.diag.window", "120"));
    private static final long START_NANOS = System.nanoTime();
    private static final Object LOCK = new Object();
    private static Path logPath;

    private DiagLog() {
    }

    /**
     * 胶水层调用：指定日志目录（logs/），写入 metallum-diag.log。
     */
    public static void init(final Path logsDir) {
        if (!ENABLED) {
            return;
        }
        try {
            Files.createDirectories(logsDir);
            logPath = logsDir.resolve("metallum-diag.log");
            writeRaw("==== Metallum diag start ====");
        } catch (IOException e) {
            logPath = null;
            Metallum.LOGGER.error("[diag] DiagLog init failed (fallback: no diag file): {}", e.toString());
        }
    }

    /**
     * 写一条诊断日志；窗口期结束后静默（无需清理）。
     */
    public static void log(final String format, final Object... args) {
        if (!ENABLED || logPath == null) {
            return;
        }
        if (WINDOW_SECONDS > 0L && System.nanoTime() - START_NANOS > WINDOW_SECONDS * 1_000_000_000L) {
            return;
        }
        String message = args.length == 0 ? format : String.format(format, args);
        writeRaw(LocalTime.now().format(TIME) + " " + message);
    }

    private static void writeRaw(final String line) {
        try {
            synchronized (LOCK) {
                Files.writeString(logPath, line + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
            }
        } catch (IOException e) {
            logPath = null;
            Metallum.LOGGER.error("[diag] DiagLog write failed (disabled): {}", e.toString());
        }
    }
}
