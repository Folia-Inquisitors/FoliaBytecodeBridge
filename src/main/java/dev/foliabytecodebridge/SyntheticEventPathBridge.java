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

    public static void shutdownLane() {
        CompatibilityLane.shutdown();
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.replace('\n', ' ').replace('\r', ' ');
    }
}
