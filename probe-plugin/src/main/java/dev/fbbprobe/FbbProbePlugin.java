package dev.fbbprobe;

import dev.fbbprobe.harness.AbstractFbbProbePlugin;
import dev.fbbprobe.harness.ProbeActions;

public final class FbbProbePlugin extends AbstractFbbProbePlugin {
    private final ProbeActions actions = new FbbProbeActions();

    @Override
    protected ProbeActions actions() {
        return actions;
    }

    @Override
    protected String rootCommand() {
        return "fbbprobe";
    }

    @Override
    protected String bridgeRole() {
        return "target-transformed";
    }
}
