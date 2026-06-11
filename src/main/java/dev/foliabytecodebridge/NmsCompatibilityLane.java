package dev.foliabytecodebridge;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Serialized NMS compatibility boundary.
 *
 * <p>Unlike the plugin-event compatibility lane, this usually preserves the
 * current Folia owner thread instead of moving work to a separate executor.
 * NMS internals often need the active region/global context; serializing on a
 * separate thread would make the code ordered but still owner-wrong.</p>
 */
final class NmsCompatibilityLane {

    private static final Object OWNER_PRESERVING_LOCK = new Object();
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final ThreadLocal<Long> ACTIVE_SEQUENCE = new ThreadLocal<>();

    private NmsCompatibilityLane() {
    }

    static void runOwnerPreserving(String source, String reason,
                                   NmsCompatibilityState state, Runnable runnable) {
        callOwnerPreserving(source, reason, state, () -> {
            runnable.run();
            return null;
        });
    }

    static <T> T callOwnerPreserving(String source, String reason,
                                     NmsCompatibilityState state, Callable<T> callable) {
        Long active = ACTIVE_SEQUENCE.get();
        if (active != null) {
            BridgeDiagnostics.nmsLane("inline", active, source, reason, state,
                    "result=already-on-nms-lane");
            return callInline(callable);
        }

        long sequence = SEQUENCE.incrementAndGet();
        BridgeDiagnostics.nmsLane("submit", sequence, source, reason, state,
                "result=owner-preserving-queued");
        synchronized (OWNER_PRESERVING_LOCK) {
            ACTIVE_SEQUENCE.set(sequence);
            BridgeDiagnostics.nmsLane("start", sequence, source, reason, state,
                    "result=running thread=" + Thread.currentThread().getName());
            try (NmsCompatibilityContext.Scope ignored =
                         NmsCompatibilityContext.enter(state, "sequence=" + sequence)) {
                T result = callInline(callable);
                BridgeDiagnostics.nmsLane("finish", sequence, source, reason, state,
                        "result=completed");
                return result;
            } catch (RuntimeException | Error throwable) {
                BridgeDiagnostics.nmsLaneFailure(sequence, source, reason, state, throwable);
                throw throwable;
            } finally {
                ACTIVE_SEQUENCE.remove();
            }
        }
    }

    static boolean active() {
        return ACTIVE_SEQUENCE.get() != null;
    }

    static long currentSequence() {
        Long sequence = ACTIVE_SEQUENCE.get();
        return sequence == null ? -1L : sequence;
    }

    private static <T> T callInline(Callable<T> callable) {
        try {
            return callable.call();
        } catch (RuntimeException runtimeException) {
            throw runtimeException;
        } catch (Error error) {
            throw error;
        } catch (Exception exception) {
            throw new IllegalStateException("FBB NMS compatibility lane task failed", exception);
        }
    }
}
