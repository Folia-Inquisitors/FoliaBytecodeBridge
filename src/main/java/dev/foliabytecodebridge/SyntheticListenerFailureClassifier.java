package dev.foliabytecodebridge;

import org.bukkit.event.EventException;

import java.util.Locale;

/**
 * Classifies failures that occur while a synthetic shared-event listener is
 * running inside the compatibility lane.
 *
 * <p>This is diagnostic evidence, not a rewrite. The lane preserves ordering
 * for unknown/shared event behavior, but a listener can still touch
 * Folia-owned state. These classifications point to the route exit that should
 * be investigated next without silently moving arbitrary listener code.</p>
 */
final class SyntheticListenerFailureClassifier {

    private SyntheticListenerFailureClassifier() {
    }

    static Result classify(Throwable throwable) {
        Throwable root = rootCause(throwable);
        String text = ((root.getClass().getName() + " " + root.getMessage())
                + " " + stackText(root)).toLowerCase(Locale.ROOT);

        if (text.contains("accessing entity state off owning region")
                || text.contains("owning region's thread")
                || text.contains("org.bukkit.entity.")
                || text.contains("craftentity")
                || text.contains("craftlivingentity")
                || text.contains("craftplayer")) {
            return new Result(RouteFamily.A_ENTITY, "entity",
                    "listener-entity-owner-exit-needed", "entity-state-guard");
        }
        if (text.contains("cannot read world asynchronously")
                || text.contains("may not sync load chunks asynchronously")
                || text.contains("cannot refresh chunk asynchronously")
                || text.contains("cannot create explosion asynchronously")) {
            RouteFamily route = text.contains("block_pos")
                    ? RouteFamily.C_REGION_BLOCK
                    : RouteFamily.B_REGION_LOCATION;
            return new Result(route, "region",
                    "listener-region-owner-exit-needed", "world-region-guard");
        }
        if (text.contains("async chunk retrieval")
                || text.contains("getentities")
                || text.contains("entity scan")) {
            return new Result(RouteFamily.G_WORLD_SCAN_SPLIT, "scan",
                    "listener-split-scan-exit-needed", "world-scan-guard");
        }
        if (text.contains("scoreboard")) {
            return new Result(RouteFamily.D_PLAYER_UI, "scoreboard",
                    "listener-player-ui-model-exit-needed", "scoreboard-guard");
        }
        return new Result(null, "unknown",
                "listener-route-evidence-needed", "unclassified");
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof EventException && current.getCause() != null) {
            current = current.getCause();
        }
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static String stackText(Throwable throwable) {
        StringBuilder builder = new StringBuilder();
        for (StackTraceElement frame : throwable.getStackTrace()) {
            builder.append(' ')
                    .append(frame.getClassName())
                    .append('#')
                    .append(frame.getMethodName());
        }
        return builder.toString();
    }

    record Result(RouteFamily routeFamily, String family, String nextAction, String evidence) {
        String routeLabel() {
            return routeFamily == null ? "UNKNOWN" : routeFamily.label();
        }
    }
}
