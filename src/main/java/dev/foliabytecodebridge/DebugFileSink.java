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
    private static final String JVM_LOCK_KEY = "dev.foliabytecodebridge.debugFileLock";
    private static final int WRITE_ATTEMPTS = 5;
    private static final long RETRY_DELAY_MILLIS = 25L;
    private static final AtomicBoolean HEADER_WRITTEN = new AtomicBoolean();
    private static final AtomicBoolean WRITE_WARNING_PRINTED = new AtomicBoolean();

    private DebugFileSink() {
    }

    static void write(Level level, String message, Throwable throwable) {
        if (!BridgeConfig.debugFile()) return;
        String sanitized = PrivacySanitizer.text(message);
        Path path = Path.of(BridgeConfig.debugFilePath());
        synchronized (jvmLock()) {
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
                if (WRITE_WARNING_PRINTED.compareAndSet(false, true)) {
                    System.err.println("[FoliaBytecodeBridge] Could not write debug file "
                            + PrivacySanitizer.text(path.toString()) + ": " + exception.getMessage()
                            + " (further debug-file write warnings suppressed)");
                }
            }
        }
    }

    private static Object jvmLock() {
        synchronized (System.getProperties()) {
            Object lock = System.getProperties().get(JVM_LOCK_KEY);
            if (lock != null) return lock;
            Object created = new Object();
            System.getProperties().put(JVM_LOCK_KEY, created);
            return created;
        }
    }

    private static void append(Path path, String line) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= WRITE_ATTEMPTS; attempt++) {
            try {
                Files.writeString(path, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                return;
            } catch (IOException exception) {
                last = exception;
                sleepBeforeRetry(attempt);
            }
        }
        throw last;
    }

    private static void sleepBeforeRetry(int attempt) {
        if (attempt >= WRITE_ATTEMPTS) return;
        try {
            Thread.sleep(RETRY_DELAY_MILLIS * attempt);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static String stackTrace(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
