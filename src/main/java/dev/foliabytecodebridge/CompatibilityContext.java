package dev.foliabytecodebridge;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Thread-local compatibility context for paths that are not yet normal Folia
 * ownership routes.
 *
 * <p>This is intentionally small. Bytecode route families remain the exits to
 * entity, region, global, async, or model paths. The compatibility context is
 * the evidence layer around unknown/legacy execution, especially future shared
 * event paths where Paper-like ordering and event state may matter.</p>
 */
final class CompatibilityContext {

    private static final ThreadLocal<Deque<Frame>> FRAMES = ThreadLocal.withInitial(ArrayDeque::new);

    private CompatibilityContext() {
    }

    static Scope enterLegacyLane(String source, String reason) {
        return enter(new Frame("legacy-lane", source, "UNKNOWN", false,
                "legacy-single-thread", reason));
    }

    static Scope enterSyntheticEventPath(String eventName, boolean shared, int listenerCount, String reason) {
        String state = shared ? "shared-event" : "single-event";
        return enter(new Frame("synthetic-event-path", eventName, state, shared,
                "synthetic-model", reason + " listenerCount=" + listenerCount));
    }

    static Frame current() {
        Deque<Frame> frames = FRAMES.get();
        return frames.peek();
    }

    static boolean active() {
        return current() != null;
    }

    private static Scope enter(Frame frame) {
        Deque<Frame> frames = FRAMES.get();
        frames.push(frame);
        BridgeDiagnostics.compatibilityContext("enter", frame, "result=active");
        return () -> {
            Frame popped = frames.poll();
            BridgeDiagnostics.compatibilityContext("exit", popped == null ? frame : popped, "result=closed");
            if (frames.isEmpty()) {
                FRAMES.remove();
            }
        };
    }

    record Frame(String kind, String source, String state, boolean shared, String policy, String reason) {
        String detail() {
            return "kind=" + kind
                    + " source=" + safe(source)
                    + " state=" + state
                    + " shared=" + shared
                    + " policy=" + policy
                    + " reason=" + safe(reason);
        }
    }

    interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.replace('\n', ' ').replace('\r', ' ');
    }
}
