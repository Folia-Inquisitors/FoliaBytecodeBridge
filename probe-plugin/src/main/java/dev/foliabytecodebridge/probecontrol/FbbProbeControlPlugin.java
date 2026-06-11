package dev.foliabytecodebridge.probecontrol;

import dev.fbbprobe.harness.AbstractFbbProbePlugin;
import dev.fbbprobe.harness.ProbeActions;

public final class FbbProbeControlPlugin extends AbstractFbbProbePlugin {
    private final ProbeActions actions = new FbbProbeControlActions();

    @Override
    protected ProbeActions actions() {
        return actions;
    }

    @Override
    protected String rootCommand() {
        return "fbbprobecontrol";
    }

    @Override
    protected String bridgeRole() {
        return "control-untransformed";
    }

    @Override
    protected String defaultFirstJoinModes() {
        return "off";
    }

    @Override
    protected String defaultStartupModes() {
        // Keep the raw Folia baseline available, but do not auto-run it during
        // normal bridge startup tests. Control probes intentionally trigger
        // Folia guard stack traces, so run them explicitly when comparing
        // transformed vs untransformed behavior.
        return "off";
    }
}
