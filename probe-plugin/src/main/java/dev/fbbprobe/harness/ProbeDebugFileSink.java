package dev.fbbprobe.harness;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Probe-side copy of the bridge debug file sink.
 *
 * <p>The probe jars are intentionally independent test tools, so this tiny
 * helper mirrors the bridge sink instead of requiring the bridge plugin as a
 * hard dependency. Probe references stay visible in the shared debug log.</p>
 */
final class ProbeDebugFileSink {
    private static final Object LOCK = new Object();
    private static final AtomicBoolean HEADER_WRITTEN = new AtomicBoolean();

    private ProbeDebugFileSink() {
    }

    static void write(Level level, String message, Throwable throwable) {
        if (!debugFileEnabled()) return;
        Path path = Path.of(debugFilePath());
        synchronized (LOCK) {
            try {
                Path parent = path.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                if (HEADER_WRITTEN.compareAndSet(false, true)) {
                    append(path, "==== FBB probe debug session " + OffsetDateTime.now() + " ====");
                }
                append(path, "[" + level.getName() + "] " + message);
                if (throwable != null) {
                    append(path, stackTrace(throwable));
                }
            } catch (IOException exception) {
                System.err.println("[FBBProbe] Could not write debug file " + path + ": " + exception.getMessage());
            }
        }
    }

    private static boolean debugFileEnabled() {
        return Boolean.parseBoolean(System.getProperty("foliabytecodebridge.debugFile", "true"));
    }

    private static String debugFilePath() {
        return System.getProperty("foliabytecodebridge.debugFilePath", "plugins/FoliaBytecodeBridge/debug.log");
    }

    private static void append(Path path, String line) throws IOException {
        Files.writeString(path, line + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String stackTrace(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
