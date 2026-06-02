package dev.foliabytecodebridge;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 5A detector for unknown listener concurrency.
 *
 * <p>This does not prove a listener is unsafe and it does not promote a route.
 * It only records when the same synthetic event/listener path is entered again
 * while an earlier invocation is still active. That is the compatibility risk
 * we care about for hidden plugin state such as normal maps, lists, caches, or
 * cooldown tables.</p>
 */
final class SyntheticListenerConcurrencyTracker {

    private static final ConcurrentMap<String, ActivePath> ACTIVE = new ConcurrentHashMap<>();
    private static final AtomicLong SEQUENCES = new AtomicLong();

    private SyntheticListenerConcurrencyTracker() {
    }

    static Invocation enter(String eventName, String listenerOwner, RouteFamily routeFamily,
                            String ownerMethod, String path) {
        String key = key(eventName, listenerOwner);
        Thread currentThread = Thread.currentThread();
        EnterResult result = ACTIVE.compute(key, (ignored, active) -> {
            if (active == null) {
                ActivePath created = new ActivePath(SEQUENCES.incrementAndGet(),
                        currentThread.getId(), currentThread.getName(),
                        routeLabel(routeFamily), safe(ownerMethod), safe(path));
                created.depth.incrementAndGet();
                return created;
            }
            active.depth.incrementAndGet();
            return active;
        }).enterResult(currentThread);

        if (result.reentered()) {
            BridgeDiagnostics.syntheticConcurrency("reentered", eventName, listenerOwner,
                    routeFamily, ownerMethod, path,
                    "activeSequence=" + result.sequence()
                            + " activeThread=\"" + result.activeThread() + "\""
                            + " currentThread=\"" + currentThread.getName() + "\""
                            + " activeDepth=" + result.depth()
                            + " activeRoute=" + result.activeRoute()
                            + " activeOwnerMethod=" + result.activeOwnerMethod()
                            + " activePath=" + result.activePath()
                            + " currentRoute=" + routeLabel(routeFamily)
                            + " currentOwnerMethod=" + safe(ownerMethod)
                            + " currentPath=" + safe(path)
                            + " result=compatibility-sensitive");
        }
        return new Invocation(key, result.sequence(), result.reentered());
    }

    private static void exit(Invocation invocation) {
        ACTIVE.computeIfPresent(invocation.key, (ignored, active) -> {
            int remaining = active.depth.decrementAndGet();
            if (remaining <= 0) return null;
            return active;
        });
    }

    private static String key(String eventName, String listenerOwner) {
        return safe(eventName) + "|" + safe(listenerOwner);
    }

    private static String routeLabel(RouteFamily routeFamily) {
        return routeFamily == null ? "UNKNOWN" : routeFamily.label();
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.replace('\n', ' ').replace('\r', ' ');
    }

    static final class Invocation implements AutoCloseable {
        private final String key;
        private final long sequence;
        private final boolean reentered;
        private boolean closed;

        private Invocation(String key, long sequence, boolean reentered) {
            this.key = key;
            this.sequence = sequence;
            this.reentered = reentered;
        }

        long sequence() {
            return sequence;
        }

        boolean reentered() {
            return reentered;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            SyntheticListenerConcurrencyTracker.exit(this);
        }
    }

    private static final class ActivePath {
        private final long sequence;
        private final long firstThreadId;
        private final String firstThread;
        private final String firstRoute;
        private final String firstOwnerMethod;
        private final String firstPath;
        private final AtomicInteger depth = new AtomicInteger();

        private ActivePath(long sequence, long firstThreadId, String firstThread, String firstRoute,
                           String firstOwnerMethod, String firstPath) {
            this.sequence = sequence;
            this.firstThreadId = firstThreadId;
            this.firstThread = firstThread;
            this.firstRoute = firstRoute;
            this.firstOwnerMethod = firstOwnerMethod;
            this.firstPath = firstPath;
        }

        private EnterResult enterResult(Thread currentThread) {
            int currentDepth = depth.get();
            boolean sameThread = firstThreadId == currentThread.getId();
            boolean reentered = currentDepth > 1 && !sameThread;
            return new EnterResult(sequence, currentDepth, firstThread,
                    firstRoute, firstOwnerMethod, firstPath, reentered);
        }
    }

    private record EnterResult(long sequence, int depth, String activeThread,
                               String activeRoute, String activeOwnerMethod,
                               String activePath, boolean reentered) {
    }
}
