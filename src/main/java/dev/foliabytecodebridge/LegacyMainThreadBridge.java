package dev.foliabytecodebridge;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compatibility answers for legacy "main thread" predicates.
 *
 * <p>Paper-era plugins often use "main thread" as a proxy for "safe to run
 * server tick work now." Folia does not have one world-wide main thread, so the
 * bridge only treats a false legacy predicate as compatible when the current
 * thread is a Folia tick/region thread. Async worker pools must still receive
 * {@code false}; otherwise unsafe world state could be misclassified as safe.</p>
 */
public final class LegacyMainThreadBridge {

    private static final Set<String> REPORTED_CONTEXTS = ConcurrentHashMap.newKeySet();

    private LegacyMainThreadBridge() {
    }

    public static boolean compatibleWhenLegacyMainThreadFalse(String owner, String methodName) {
        boolean tickThread = isMoonriseTickThread();
        boolean primaryThread = isBukkitPrimaryThread();
        boolean compatible = tickThread || primaryThread;
        String threadName = Thread.currentThread().getName();
        String key = owner + "#" + methodName + "|" + compatible + "|" + tickThread + "|" + primaryThread + "|" + threadName;
        if (REPORTED_CONTEXTS.add(key)) {
            BridgeDiagnostics.legacyMainThreadRuntime(owner, methodName, RouteFamily.S_GLOBAL,
                    compatible, tickThread, primaryThread, threadName);
        }
        return compatible;
    }

    private static boolean isMoonriseTickThread() {
        if (isTickThreadClass(Thread.currentThread().getClass())) {
            return true;
        }
        try {
            Class<?> tickThread = Class.forName("ca.spottedleaf.moonrise.common.util.TickThread", false,
                    LegacyMainThreadBridge.class.getClassLoader());
            Method method = tickThread.getMethod("isTickThread");
            return Boolean.TRUE.equals(method.invoke(null));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isTickThreadClass(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if ("ca.spottedleaf.moonrise.common.util.TickThread".equals(current.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBukkitPrimaryThread() {
        try {
            Class<?> bukkit = Class.forName("org.bukkit.Bukkit", false,
                    LegacyMainThreadBridge.class.getClassLoader());
            Method method = bukkit.getMethod("isPrimaryThread");
            return Boolean.TRUE.equals(method.invoke(null));
        } catch (Throwable ignored) {
            return false;
        }
    }
}
