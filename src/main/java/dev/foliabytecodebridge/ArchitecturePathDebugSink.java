package dev.foliabytecodebridge;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Focused architecture-pathfinding timeline.
 *
 * <p>{@link DebugFileSink} is the full lab notebook. This sink is the readable
 * companion file for "how did FBB think about this path?" It records route
 * discovery, owner/lane/synthetic decisions, rewrite outcomes, and failure
 * direction without requiring console spam.</p>
 */
final class ArchitecturePathDebugSink {
    private static final String JVM_LOCK_KEY = "dev.foliabytecodebridge.architecturePathLock";
    private static final int QUEUE_CAPACITY = 8192;
    private static final int WRITE_ATTEMPTS = 5;
    private static final long RETRY_DELAY_MILLIS = 25L;
    private static final DateTimeFormatter ARCHIVE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSSxx");
    private static final BlockingQueue<WriteRecord> QUEUE = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private static final AtomicBoolean HEADER_WRITTEN = new AtomicBoolean();
    private static final AtomicBoolean WRITE_WARNING_PRINTED = new AtomicBoolean();
    private static final AtomicBoolean QUEUE_WARNING_PRINTED = new AtomicBoolean();
    private static final AtomicBoolean WRITER_STARTED = new AtomicBoolean();
    private static final AtomicLong DROPPED_RECORDS = new AtomicLong();

    private ArchitecturePathDebugSink() {
    }

    static void write(Level level, String stage, String message, Throwable throwable) {
        if (!BridgeConfig.architecturePathfindingDebug()) return;

        startWriter();

        List<String> lines = new ArrayList<>(throwable == null ? 1 : 2);
        lines.add(OffsetDateTime.now()
                + " [" + level.getName() + "]"
                + " stage=" + safe(stage)
                + " " + PrivacySanitizer.text(message));
        if (throwable != null && level.intValue() >= Level.WARNING.intValue()) {
            lines.add(PrivacySanitizer.text(stackTrace(throwable)));
        }

        // Folia owner threads must never wait on diagnostic file I/O. If the
        // evidence firehose overwhelms this queue, we drop pathfinding records
        // and preserve that fact instead of stalling a region thread.
        if (!QUEUE.offer(new WriteRecord(lines))) {
            long dropped = DROPPED_RECORDS.incrementAndGet();
            if (QUEUE_WARNING_PRINTED.compareAndSet(false, true)) {
                System.err.println("[FoliaBytecodeBridge] Architecture pathfinding queue is full; "
                        + "dropping diagnostic records instead of blocking Folia owner threads"
                        + " dropped=" + dropped
                        + " (further queue warnings suppressed)");
            }
        }
    }

    private static void startWriter() {
        if (!WRITER_STARTED.compareAndSet(false, true)) return;

        Thread writer = new Thread(ArchitecturePathDebugSink::writerLoop, "FBB-architecture-path-writer");
        writer.setDaemon(true);
        writer.start();
    }

    private static void writerLoop() {
        List<WriteRecord> batch = new ArrayList<>(256);
        while (true) {
            try {
                WriteRecord first = QUEUE.poll(1L, TimeUnit.SECONDS);
                if (first == null) {
                    flushDroppedSummary();
                    continue;
                }

                batch.add(first);
                QUEUE.drainTo(batch, 255);
                flushDroppedSummary();
                for (WriteRecord record : batch) {
                    writeNow(record);
                }
                batch.clear();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void flushDroppedSummary() {
        long dropped = DROPPED_RECORDS.getAndSet(0L);
        if (dropped <= 0L) return;
        writeNow(new WriteRecord(List.of(OffsetDateTime.now()
                + " [WARNING] stage=diagnostics/backpressure"
                + " marker=FBB_ARCH_DIAGNOSTIC_BACKPRESSURE_V1"
                + " result=dropped-records"
                + " dropped=" + dropped
                + " reason=architecture-pathfinding-queue-full"
                + " action=preserve-server-thread-over-diagnostic-completeness")));
    }

    private static void writeNow(WriteRecord record) {
        Path path = Path.of(BridgeConfig.architecturePathfindingDebugPath());
        synchronized (jvmLock()) {
            try {
                Path parent = path.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                rotateIfNeeded(path);
                if (HEADER_WRITTEN.compareAndSet(false, true)) {
                    append(path, sessionHeader());
                }
                for (String line : record.lines()) {
                    append(path, line);
                }
            } catch (IOException exception) {
                if (WRITE_WARNING_PRINTED.compareAndSet(false, true)) {
                    System.err.println("[FoliaBytecodeBridge] Could not write architecture pathfinding file "
                            + PrivacySanitizer.text(path.toString()) + ": " + exception.getMessage()
                            + " (further architecture-pathfinding write warnings suppressed)");
                }
            }
        }
    }

    private static String sessionHeader() {
        return "==== FoliaBytecodeBridge architecture pathfinding session "
                + OffsetDateTime.now()
                + " version=" + BridgeBuildInfo.VERSION
                + " buildId=" + BridgeBuildInfo.BUILD_ID
                + " routeRules=" + RouteRuleRegistry.rules().size()
                + " filePurpose=route-thinking-timeline"
                + " ====";
    }

    private static void rotateIfNeeded(Path path) throws IOException {
        long maxBytes = BridgeConfig.architecturePathfindingDebugMaxBytes();
        if (maxBytes <= 0L || !Files.isRegularFile(path) || Files.size(path) < maxBytes) return;

        Files.move(path, archivePath(path), StandardCopyOption.REPLACE_EXISTING);
        HEADER_WRITTEN.set(false);
    }

    private static Path archivePath(Path path) {
        Path fileName = path.getFileName();
        String name = fileName == null ? "architecture-pathfinding.debug" : fileName.toString();
        String timestamp = OffsetDateTime.now().format(ARCHIVE_TIMESTAMP);
        int dot = name.lastIndexOf('.');
        String archivedName = dot <= 0
                ? name + "-" + timestamp
                : name.substring(0, dot) + "-" + timestamp + name.substring(dot);
        Path parent = path.getParent();
        return parent == null ? Path.of(archivedName) : parent.resolve(archivedName);
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

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return PrivacySanitizer.text(value.replace('\n', ' ').replace('\r', ' '));
    }

    private static final class WriteRecord {
        private final List<String> lines;

        private WriteRecord(List<String> lines) {
            this.lines = lines;
        }

        private List<String> lines() {
            return lines;
        }
    }
}
