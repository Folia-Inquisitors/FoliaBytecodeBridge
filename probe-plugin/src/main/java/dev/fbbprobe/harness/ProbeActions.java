package dev.fbbprobe.harness;

import org.bukkit.entity.Player;

public interface ProbeActions {
    void runStartupProbes(ProbeRuntime runtime);

    void runSafeProbes(ProbeRuntime runtime, Player player);

    void runScanProbes(ProbeRuntime runtime, Player player);

    void runUiProbes(ProbeRuntime runtime, Player player);

    void runVisibilityProbes(ProbeRuntime runtime, Player player);

    void runEntityMutationProbes(ProbeRuntime runtime, Player player);

    void runWorldEffectProbes(ProbeRuntime runtime, Player player);

    void runChunkGuardProbes(ProbeRuntime runtime, Player player);

    void runServerGuardProbes(ProbeRuntime runtime, Player player);

    void runScoreboardGuardProbes(ProbeRuntime runtime, Player player);

    void runRecoveryPathProbes(ProbeRuntime runtime, Player player);

    void runDestructiveProbes(ProbeRuntime runtime, Player player);
}
