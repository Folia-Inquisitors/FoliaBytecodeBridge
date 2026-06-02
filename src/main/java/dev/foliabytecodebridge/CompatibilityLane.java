package dev.foliabytecodebridge;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Serialized compatibility lane for unknown or shared legacy paths.
 *
 * <p>This is not a Folia owner thread. It does not make Bukkit/world/entity
 * access safe by itself. Its job is to preserve Paper-like ordering for an
 * unproven compatibility-sensitive path until a known route-family call can
 * exit to the correct Folia scheduler.</p>
 */
final class CompatibilityLane {

    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final ThreadLocal<Long> ACTIVE_SEQUENCE = new ThreadLocal<>();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(new LaneThreadFactory());

    private CompatibilityLane() {
    }

    static <T> T call(String source, String reason, Callable<T> callable) {
        if (ACTIVE_SEQUENCE.get() != null) {
            long sequence = ACTIVE_SEQUENCE.get();
            BridgeDiagnostics.compatibilityLane("inline", sequence, source, reason,
                    "result=already-on-lane");
            // Re-entering the lane from lane-owned work should stay inline;
            // queueing here would deadlock the single compatibility thread.
            return callInline(callable);
        }

        long sequence = SEQUENCE.incrementAndGet();
        BridgeDiagnostics.compatibilityLane("submit", sequence, source, reason,
                "result=queued");
        Future<T> future = EXECUTOR.submit(() -> {
            ACTIVE_SEQUENCE.set(sequence);
            BridgeDiagnostics.compatibilityLane("start", sequence, source, reason,
                    "result=running thread=" + Thread.currentThread().getName());
            try {
                T result = callable.call();
                BridgeDiagnostics.compatibilityLane("finish", sequence, source, reason,
                        "result=completed");
                return result;
            } catch (Throwable throwable) {
                BridgeDiagnostics.compatibilityLaneFailure(sequence, source, reason, throwable);
                throw throwable;
            } finally {
                ACTIVE_SEQUENCE.remove();
            }
        });

        try {
            // The lane preserves old synchronous ordering for unknown custom
            // paths. Known Bukkit owner calls should still exit through their
            // own route bridges rather than relying on this wait for safety.
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for FBB compatibility lane", interrupted);
        } catch (ExecutionException execution) {
            Throwable cause = execution.getCause();
            if (cause instanceof RuntimeException runtimeException) throw runtimeException;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("FBB compatibility lane task failed", cause);
        }
    }

    static void run(String source, String reason, Runnable runnable) {
        call(source, reason, () -> {
            runnable.run();
            return null;
        });
    }

    static boolean active() {
        return ACTIVE_SEQUENCE.get() != null;
    }

    static long currentSequence() {
        Long sequence = ACTIVE_SEQUENCE.get();
        return sequence == null ? -1L : sequence;
    }

    static void shutdown() {
        EXECUTOR.shutdownNow();
    }

    private static <T> T callInline(Callable<T> callable) {
        try {
            return callable.call();
        } catch (RuntimeException runtimeException) {
            throw runtimeException;
        } catch (Error error) {
            throw error;
        } catch (Exception exception) {
            throw new IllegalStateException("FBB compatibility lane inline task failed", exception);
        }
    }

    private static final class LaneThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "FBB-compatibility-lane");
            thread.setDaemon(true);
            return thread;
        }
    }
}
