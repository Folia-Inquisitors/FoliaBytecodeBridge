package dev.foliabytecodebridge;

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
 * Full-fidelity diagnostics file for the experimental bridge.
 *
 * <p>The console is allowed to stay readable; this file is the noisy lab
 * notebook. Every bridge route decision should be written here before any
 * console filtering is applied.</p>
 */
final class DebugFileSink {
    private static final Object LOCK = new Object();
    private static final AtomicBoolean HEADER_WRITTEN = new AtomicBoolean();

    private DebugFileSink() {
    }

    static void write(Level level, String message, Throwable throwable) {
        if (!BridgeConfig.debugFile()) return;
        String sanitized = PrivacySanitizer.text(message);
        Path path = Path.of(BridgeConfig.debugFilePath());
        synchronized (LOCK) {
            try {
                Path parent = path.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                if (HEADER_WRITTEN.compareAndSet(false, true)) {
                    append(path, "==== FoliaBytecodeBridge debug session "
                            + OffsetDateTime.now() + " ====");
                }
                append(path, "[" + level.getName() + "] " + sanitized);
                if (throwable != null) {
                    append(path, PrivacySanitizer.text(stackTrace(throwable)));
                }
            } catch (IOException exception) {
                System.err.println("[FoliaBytecodeBridge] Could not write debug file "
                        + PrivacySanitizer.text(path.toString()) + ": " + exception.getMessage());
            }
        }
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
