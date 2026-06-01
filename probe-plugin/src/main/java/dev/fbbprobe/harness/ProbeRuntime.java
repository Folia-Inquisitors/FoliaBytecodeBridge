package dev.fbbprobe.harness;

import org.bukkit.plugin.java.JavaPlugin;

public interface ProbeRuntime {
    JavaPlugin plugin();

    void probe(String route, String api, String owner, String name, String descriptor, ProbeCall call);

    void probeGuard(String route, String api, String owner, String name, String descriptor,
                    String guard, ProbeCall call);

    void probeModel(String group, String route, String api, String owner, String name, String descriptor,
                    String ownerHint, String returnType, boolean syncReturnRisk, ProbeValueCall call);

    void probeBlocked(String route, String api, String owner, String name, String descriptor,
                      String blockedBy, String reason);
}
