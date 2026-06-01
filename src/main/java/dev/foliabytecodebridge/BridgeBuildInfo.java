package dev.foliabytecodebridge;

/**
 * Human-visible build marker for live-server evidence.
 *
 * <p>The plugin jar name often stays the same while experiments move quickly.
 * Keep this marker loud in startup logs so a `latest.log` can prove which
 * bridge build was actually loaded before we trust route evidence.</p>
 */
final class BridgeBuildInfo {

    static final String VERSION = "0.1.1-experimental.3";
    static final String BUILD_ID = "2026-05-31-debug-file-organization";

    private BridgeBuildInfo() {
    }
}
