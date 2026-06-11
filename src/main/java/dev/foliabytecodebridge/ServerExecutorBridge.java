package dev.foliabytecodebridge;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public final class ServerExecutorBridge {

    private static final String MCUTIL_MAIN_EXECUTOR_API = "MCUtil.MAIN_EXECUTOR#execute";
    private static final String MINECRAFT_SERVER_EXECUTE_API = "MinecraftServer#execute";

    private ServerExecutorBridge() {
    }

    public static void mcUtilMainExecutorExecute(Executor executor, Runnable runnable) {
        BridgeDiagnostics.schedulerCall(MCUTIL_MAIN_EXECUTOR_API, RouteFamily.S_GLOBAL,
                "global-server-executor", bridgePluginOrNull());
        if (!isFolia()) {
            executor.execute(runnable);
            return;
        }
        routeExecutorRunnable(MCUTIL_MAIN_EXECUTOR_API, "MCUTIL_MAIN_EXECUTOR_CONTEXT", runnable);
    }

    public static void minecraftServerExecute(Object server, Runnable runnable) {
        BridgeDiagnostics.schedulerCall(MINECRAFT_SERVER_EXECUTE_API, RouteFamily.S_GLOBAL,
                "global-server-executor", bridgePluginOrNull());
        if (!isFolia()) {
            executeDirect(server, runnable);
            return;
        }
        routeExecutorRunnable(MINECRAFT_SERVER_EXECUTE_API, "MINECRAFT_SERVER_EXECUTOR_CONTEXT", runnable);
    }

    private static void executeDirect(Object server, Runnable runnable) {
        if (server instanceof Executor executor) {
            executor.execute(runnable);
            return;
        }
        try {
            Method execute = server.getClass().getMethod("execute", Runnable.class);
            execute.invoke(server, runnable);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to invoke MinecraftServer#execute on non-Folia server", exception);
        }
    }

    private static void scheduleGlobal(Plugin plugin, Runnable runnable) {
        try {
            Object scheduler = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
            Consumer<Object> task = ignored -> runnable.run();
            Method run = scheduler.getClass().getMethod("run", Plugin.class, Consumer.class);
            run.invoke(scheduler, plugin, task);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to schedule MCUtil main executor runnable on Folia global scheduler", exception);
        }
    }

    private static void routeExecutorRunnable(String sourceApi, String model, Runnable runnable) {
        Plugin plugin = bridgePlugin();
        String scheduledFrom = BridgeDiagnostics.captureCaller();
        NmsCompatibilityState scanning = NmsCompatibilityState.scanning(
                sourceApi, NmsCompatFamily.NMS_EXECUTOR_CONTEXT, model);
        NmsOwnerExtractor.Scan scan = NmsOwnerExtractor.scan(runnable);
        BridgeDiagnostics.nmsOwnerExtract("scan-runnable", scanning, scan,
                "runnable=" + runnable.getClass().getName() + " scheduledFrom=" + scheduledFrom);

        if (scan.found()) {
            NmsOwnerExtractor.Owner owner = scan.owner();
            NmsCompatibilityState state = scanning.ownerFound(owner, "owner-preserving-nms-lane");
            BridgeDiagnostics.nmsRouteExit(state, owner, scheduleRoute(owner),
                    "scheduledFrom=" + scheduledFrom);
            scheduleOwner(plugin, owner, () -> runInNmsLane(sourceApi, scheduledFrom, plugin, state, runnable));
            return;
        }

        NmsCompatibilityState state = scanning.ownerMiss(scan.missReason(), "global-owner-preserving-nms-lane");
        BridgeDiagnostics.nmsOwnerMiss(state, scan.missReason());
        scheduleGlobal(plugin, () -> runInNmsLane(sourceApi, scheduledFrom, plugin, state, runnable));
    }

    private static void runInNmsLane(String sourceApi, String scheduledFrom, Plugin plugin,
                                     NmsCompatibilityState state, Runnable runnable) {
        NmsCompatibilityLane.runOwnerPreserving("nms-executor:" + sourceApi,
                "server-internal-executor-context", state, () -> {
                    try {
                        runnable.run();
                    } catch (Throwable throwable) {
                        BridgeDiagnostics.nmsCompatExecutorFailure(sourceApi, scheduledFrom, throwable);
                        BridgeDiagnostics.taskFailure(plugin, RouteFamily.S_GLOBAL, scheduledFrom, throwable);
                        throw throwable;
                    }
                });
    }

    private static void scheduleOwner(Plugin plugin, NmsOwnerExtractor.Owner owner, Runnable runnable) {
        if ("entity".equals(owner.kind()) && owner.location() != null) {
            scheduleRegion(plugin, owner.location(), runnable);
            return;
        }
        if (owner.hasLocation()) {
            scheduleRegion(plugin, owner.location(), runnable);
            return;
        }
        if (owner.hasChunk()) {
            scheduleRegion(plugin, owner.world(), owner.chunkX(), owner.chunkZ(), runnable);
            return;
        }
        scheduleGlobal(plugin, runnable);
    }

    private static String scheduleRoute(NmsOwnerExtractor.Owner owner) {
        if (owner == null) return "global-fallback";
        return switch (owner.kind()) {
            case "entity" -> "entity-location-region";
            case "location" -> "region-location";
            case "chunk" -> "region-chunk";
            default -> "global-fallback";
        };
    }

    private static void scheduleRegion(Plugin plugin, Location location, Runnable runnable) {
        try {
            Object scheduler = Bukkit.class.getMethod("getRegionScheduler").invoke(null);
            Consumer<Object> task = ignored -> runnable.run();
            Method run = scheduler.getClass().getMethod("run", Plugin.class, Location.class, Consumer.class);
            run.invoke(scheduler, plugin, location, task);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            throw new IllegalStateException("Unable to schedule NMS executor runnable on Folia region scheduler", exception);
        }
    }

    private static void scheduleRegion(Plugin plugin, World world, int chunkX, int chunkZ, Runnable runnable) {
        try {
            Object scheduler = Bukkit.class.getMethod("getRegionScheduler").invoke(null);
            Consumer<Object> task = ignored -> runnable.run();
            Method run = scheduler.getClass().getMethod("run",
                    Plugin.class, World.class, int.class, int.class, Consumer.class);
            run.invoke(scheduler, plugin, world, chunkX, chunkZ, task);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            throw new IllegalStateException("Unable to schedule NMS executor runnable on Folia chunk-region scheduler", exception);
        }
    }

    private static boolean isFolia() {
        if (Boolean.getBoolean("foliabytecodebridge.forceNonFolia")) return false;
        try {
            Bukkit.class.getMethod("getGlobalRegionScheduler");
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }

    private static Plugin bridgePlugin() {
        return BridgePluginResolver.requirePlugin("server executor scheduling");
    }

    private static Plugin bridgePluginOrNull() {
        return BridgePluginResolver.pluginOrNull("server executor scheduler log");
    }
}
