package dev.foliabytecodebridge;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Official route-family registry for FoliaBytecodeBridge translation paths.
 *
 * <p>Keep log labels, docs, and smoke assertions tied to this enum so the
 * bridge does not grow a second hidden map of raw route strings.</p>
 */
public enum RouteFamily {
    S_GLOBAL("scheduler/global fallback"),
    S_ASYNC("async scheduler"),
    A_ENTITY("entity/player calls"),
    B_REGION_LOCATION("world calls with location"),
    C_REGION_BLOCK("block-owned calls"),
    D_PLAYER_UI("inventory/menu/player UI"),
    F_PLAYER_VISIBILITY("hide/show player"),
    G_WORLD_SCAN_SPLIT("world/entity scans");

    private final String description;

    RouteFamily(String description) {
        this.description = description;
    }

    public String label() {
        return name();
    }

    public String description() {
        return description;
    }

    public static RouteFamily forSchedulerPolicy(String policy) {
        if (policy == null) return S_GLOBAL;
        return policy.toLowerCase(Locale.ROOT).startsWith("async") ? S_ASYNC : S_GLOBAL;
    }

    public static RouteFamily forUnsafeCall(String family, String nextAction) {
        String normalizedFamily = normalize(family);
        String normalizedNext = normalize(nextAction);

        if (normalizedFamily.contains("scan") || normalizedNext.contains("source-level-split-by-region")
                || normalizedNext.contains("entity-scheduler-scan")) {
            return G_WORLD_SCAN_SPLIT;
        }
        if ("global".equals(normalizedFamily) || normalizedNext.contains("command-dispatch")) {
            return S_GLOBAL;
        }
        if (normalizedFamily.contains("ui") || normalizedFamily.contains("scoreboard")
                || normalizedNext.contains("entity-scheduler-ui")
                || normalizedNext.contains("scoreboard")) {
            return D_PLAYER_UI;
        }
        if (normalizedNext.contains("entity-scheduler-visibility")) return F_PLAYER_VISIBILITY;
        if (normalizedNext.contains("region-scheduler-by-block")) return C_REGION_BLOCK;
        if (normalizedNext.contains("region-scheduler-by-location")
                || normalizedNext.contains("region-scheduler-by-chunk")) {
            return B_REGION_LOCATION;
        }
        if (normalizedNext.contains("entity-scheduler")) return A_ENTITY;

        if ("region".equals(normalizedFamily)) return B_REGION_LOCATION;
        if ("player".equals(normalizedFamily) || "entity".equals(normalizedFamily)) return A_ENTITY;
        return A_ENTITY;
    }

    public static String labels() {
        return Arrays.stream(values())
                .map(RouteFamily::label)
                .collect(Collectors.joining("|"));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
