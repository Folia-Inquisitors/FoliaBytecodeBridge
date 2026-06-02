package dev.foliabytecodebridge;

import java.util.concurrent.Callable;

/**
 * Public runtime hook for future event-dispatch transformers.
 *
 * <p>The bridge should not rewrite all Bukkit events yet. This hook gives the
 * next iteration a clean place to wrap a proven shared synchronous event path
 * and collect promotion evidence when known bytecode route exits occur inside
 * that path.</p>
 */
public final class SyntheticEventPathBridge {

    private SyntheticEventPathBridge() {
    }

    public static void run(String eventName, boolean shared, int listenerCount,
                           String reason, Runnable runnable) {
        call(eventName, shared, listenerCount, reason, () -> {
            runnable.run();
            return null;
        });
    }

    public static <T> T call(String eventName, boolean shared, int listenerCount,
                             String reason, Callable<T> callable) {
        String source = "synthetic-event-path:" + safe(eventName);
        return CompatibilityLane.call(source, reason, () -> {
            try (CompatibilityContext.Scope ignored = CompatibilityContext.enterSyntheticEventPath(
                    eventName, shared, listenerCount, reason)) {
                return callable.call();
            }
        });
    }

    public static void observeListener(String eventName, String listenerOwner,
                                       String phase, String effect, boolean cancelled) {
        BridgeDiagnostics.eventListener(eventName, listenerOwner, phase, effect, cancelled,
                CompatibilityContext.current());
    }

    public static boolean isCompatibilityLaneThread() {
        return CompatibilityLane.active();
    }

    public static long compatibilityLaneSequence() {
        return CompatibilityLane.currentSequence();
    }

    public static String probeListenerReentry(String eventName, String listenerOwner, String reason) {
        String safeEvent = safe(eventName);
        String safeListener = safe(listenerOwner);
        String safeReason = safe(reason);
        Thread first = new Thread(() -> {
            try (SyntheticListenerConcurrencyTracker.Invocation ignored =
                         SyntheticListenerConcurrencyTracker.enter(safeEvent, safeListener, null,
                                 "none", "diagnostic-probe:" + safeReason)) {
                sleep(250L);
            }
        }, "FBB-synthetic-5A-active");
        first.start();
        sleep(75L);
        try (SyntheticListenerConcurrencyTracker.Invocation ignored =
                     SyntheticListenerConcurrencyTracker.enter(safeEvent, safeListener, null,
                             "none", "diagnostic-probe:" + safeReason)) {
            // This public hook is intentionally diagnostic-only. It lets the
            // probe prove Phase 5A without manufacturing real unsafe Bukkit
            // state or weakening the serialized compatibility lane.
        }
        join(first);
        return "synthetic-listener-concurrency-reentry";
    }

    public static void shutdownLane() {
        CompatibilityLane.shutdown();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void join(Thread thread) {
        try {
            thread.join(1000L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.replace('\n', ' ').replace('\r', ' ');
    }
}
