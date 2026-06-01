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
}
