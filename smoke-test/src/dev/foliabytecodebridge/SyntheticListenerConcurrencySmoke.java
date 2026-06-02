package dev.foliabytecodebridge;

/**
 * Smoke-only entry point for Phase 5A listener re-entry evidence.
 *
 * <p>The live tracker is normally driven by Bukkit listener dispatch. This
 * fixture overlaps two invocations of the same event/listener key so smoke can
 * prove the diagnostic path without depending on server timing.</p>
 */
public final class SyntheticListenerConcurrencySmoke {

    private SyntheticListenerConcurrencySmoke() {
    }

    public static String run() {
        return SyntheticEventPathBridge.probeListenerReentry(
                "smoketest.SyntheticConcurrencyEvent",
                "SmokePlugin/smoketest.SyntheticConcurrencyListener",
                "smoke-phase-5a");
    }
}
