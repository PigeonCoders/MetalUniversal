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
    /** P37 判别：init 前环形缓冲（mixin 应用阶段早于 onInitialize——日志不丢）。 */
    private static final java.util.ArrayList<String> PRE_INIT_BUFFER = new java.util.ArrayList<>();
    private static final int PRE_INIT_BUFFER_MAX = 512;

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
            synchronized (LOCK) {
                for (String line : PRE_INIT_BUFFER) {
                    writeRawLocked(line);
                }
                PRE_INIT_BUFFER.clear();
            }
        } catch (IOException e) {
            logPath = null;
            Metallum.LOGGER.error("[diag] DiagLog init failed (fallback: no diag file): {}", e.toString());
        }
    }

    /**
     * P37：不受窗口期限制的日志（判别轮关键事件——打开设置屏可能在窗口后）。
     * 仍受 -Dmetallum.diag=false 总开关控制。
     */
    public static void logAlways(final String format, final Object... args) {
        if (!ENABLED) {
            return;
        }
        String message;
        try {
            message = args.length == 0 ? format : String.format(format, args);
        } catch (RuntimeException e) {
            message = format + " [diag-format-error: " + e + "]";
        }
        writeRawBuffered(LocalTime.now().format(TIME) + " " + message);
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
        String message;
        try {
            message = args.length == 0 ? format : String.format(format, args);
        } catch (RuntimeException e) {
            // 防御：诊断日志绝不能因格式错误崩溃游戏（P10 E9 曾以
            // IllegalFormatConversionException 崩渲染线程）
            message = format + " [diag-format-error: " + e + "]";
        }
        writeRawBuffered(LocalTime.now().format(TIME) + " " + message);
    }

    /** P37：logPath 未初始化时入 PRE_INIT_BUFFER（init 后冲刷）。 */
    private static void writeRawBuffered(final String line) {
        synchronized (LOCK) {
            if (logPath == null) {
                if (PRE_INIT_BUFFER.size() < PRE_INIT_BUFFER_MAX) {
                    PRE_INIT_BUFFER.add(line);
                }
                return;
            }
            writeRawLocked(line);
        }
    }

    private static void writeRaw(final String line) {
        writeRawBuffered(line);
    }

    private static void writeRawLocked(final String line) {
        try {
            Files.writeString(logPath, line + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } catch (IOException e) {
            logPath = null;
            Metallum.LOGGER.error("[diag] DiagLog write failed (disabled): {}", e.toString());
        }
    }
}
