package dev.foliabytecodebridge;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public final class ServerExecutorBridge {

    private ServerExecutorBridge() {
    }

    public static void mcUtilMainExecutorExecute(Executor executor, Runnable runnable) {
        BridgeDiagnostics.schedulerCall("MCUtil.MAIN_EXECUTOR#execute", RouteFamily.S_GLOBAL,
                "global-server-executor", bridgePluginOrNull());
        if (!isFolia()) {
            executor.execute(runnable);
            return;
        }
        Plugin plugin = bridgePlugin();
        String scheduledFrom = BridgeDiagnostics.captureCaller();
        scheduleGlobal(plugin, () -> {
            try {
                runnable.run();
            } catch (Throwable throwable) {
                BridgeDiagnostics.taskFailure(plugin, RouteFamily.S_GLOBAL, scheduledFrom, throwable);
                throw throwable;
            }
        });
    }

    public static void minecraftServerExecute(Object server, Runnable runnable) {
        BridgeDiagnostics.schedulerCall("MinecraftServer#execute", RouteFamily.S_GLOBAL,
                "global-server-executor", bridgePluginOrNull());
        if (!isFolia()) {
            executeDirect(server, runnable);
            return;
        }
        Plugin plugin = bridgePlugin();
        String scheduledFrom = BridgeDiagnostics.captureCaller();
        scheduleGlobal(plugin, () -> {
            try {
                runnable.run();
            } catch (Throwable throwable) {
                BridgeDiagnostics.taskFailure(plugin, RouteFamily.S_GLOBAL, scheduledFrom, throwable);
                throw throwable;
            }
        });
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
        Plugin plugin = bridgePluginOrNull();
        if (plugin == null) {
            throw new IllegalStateException("FoliaBytecodeBridge plugin is not enabled yet");
        }
        return plugin;
    }

    private static Plugin bridgePluginOrNull() {
        try {
            return Bukkit.getPluginManager().getPlugin("FoliaBytecodeBridge");
        } catch (Throwable ignored) {
            return null;
        }
    }
}
