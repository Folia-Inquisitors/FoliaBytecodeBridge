package dev.foliabytecodebridge;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Thread-local NMS compatibility context.
 *
 * <p>This is intentionally not a Bukkit route-family context. It marks that
 * the current code is inside a server-internal compatibility assumption, such
 * as a legacy main executor runnable, so known NMS clues can be promoted later
 * without losing the evidence for unknown/unproven work.</p>
 */
final class NmsCompatibilityContext {

    private static final ThreadLocal<Deque<NmsCompatibilityState>> STACK =
            ThreadLocal.withInitial(ArrayDeque::new);

    private NmsCompatibilityContext() {
    }

    static Scope enter(NmsCompatibilityState state, String detail) {
        STACK.get().push(state);
        BridgeDiagnostics.nmsContext("enter", state, detail);
        return new Scope(state);
    }

    static NmsCompatibilityState current() {
        Deque<NmsCompatibilityState> stack = STACK.get();
        return stack.isEmpty() ? null : stack.peek();
    }

    static final class Scope implements AutoCloseable {
        private final NmsCompatibilityState state;
        private boolean closed;

        private Scope(NmsCompatibilityState state) {
            this.state = state;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            Deque<NmsCompatibilityState> stack = STACK.get();
            NmsCompatibilityState popped = stack.isEmpty() ? state : stack.pop();
            BridgeDiagnostics.nmsContext("exit", popped, "result=closed");
            if (stack.isEmpty()) {
                STACK.remove();
            }
        }
    }
}
