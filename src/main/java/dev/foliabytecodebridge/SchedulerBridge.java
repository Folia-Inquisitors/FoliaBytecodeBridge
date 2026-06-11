package dev.foliabytecodebridge;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Logger;

public final class SchedulerBridge {

    private static final AtomicInteger NEXT_TASK_ID = new AtomicInteger(1_000_000);
    private static final AtomicInteger BRIDGE_CALLS = new AtomicInteger();
    // Synthetic ids live far away from Bukkit's normal low task ids to reduce accidental collisions.
    private static final Map<Integer, BridgeBukkitTask> TASKS = new ConcurrentHashMap<>();
    private static final Map<BukkitRunnable, BridgeBukkitTask> RUNNABLE_TASKS = new ConcurrentHashMap<>();

    private SchedulerBridge() {
    }

    /**
     * Public bootstrap boundary.
     *
     * <p>The plugin main class may be loaded by Bukkit's plugin classloader while
     * helper classes are visible from the Java-agent helper jar. Public access is
     * intentional here so startup logging survives that classloader split.</p>
     */
    public static void setLogger(Logger logger) {
        BridgeDiagnostics.setLogger(logger);
    }

    static void forget(int taskId) {
        TASKS.remove(taskId);
    }

    public static BukkitTask runTask(BukkitScheduler scheduler, Plugin plugin, Runnable runnable) {
        RouteFamily route = markBridgeCall("BukkitScheduler#runTask", "global", plugin);
        if (smokeNoPassthrough()) {
            runSmokeTask(plugin, route, runnable);
            return wrap(plugin, true, smokeHandle("BukkitScheduler#runTask"));
        }
        if (!isFolia()) return scheduler.runTask(plugin, runnable);
        return wrap(plugin, true, scheduleGlobal(plugin, guarded(plugin, route, runnable), 0L, 0L));
    }

    public static BukkitTask runTaskLater(BukkitScheduler scheduler, Plugin plugin, Runnable runnable, long delay) {
        RouteFamily route = markBridgeCall("BukkitScheduler#runTaskLater", "global-delayed", plugin);
        if (smokeNoPassthrough()) return wrap(plugin, true, smokeHandle("BukkitScheduler#runTaskLater"));
        if (!isFolia()) return scheduler.runTaskLater(plugin, runnable, delay);
        return wrap(plugin, true, scheduleGlobal(plugin, guarded(plugin, route, runnable), delay, 0L));
    }

    public static BukkitTask runTaskTimer(BukkitScheduler scheduler, Plugin plugin, Runnable runnable,
                                          long delay, long period) {
        RouteFamily route = markBridgeCall("BukkitScheduler#runTaskTimer", "global-repeating", plugin);
        if (smokeNoPassthrough()) return wrap(plugin, true, smokeHandle("BukkitScheduler#runTaskTimer"));
        if (!isFolia()) return scheduler.runTaskTimer(plugin, runnable, delay, period);
        return wrap(plugin, true, scheduleGlobal(plugin, guarded(plugin, route, runnable), delay, period));
    }

    public static BukkitTask runTaskAsynchronously(BukkitScheduler scheduler, Plugin plugin, Runnable runnable) {
        RouteFamily route = markBridgeCall("BukkitScheduler#runTaskAsynchronously", "async", plugin);
        if (smokeNoPassthrough()) {
            runSmokeTask(plugin, route, runnable);
            return wrap(plugin, false, smokeHandle("BukkitScheduler#runTaskAsynchronously"));
        }
        if (!isFolia()) return scheduler.runTaskAsynchronously(plugin, runnable);
        return wrap(plugin, false, scheduleAsync(plugin, guarded(plugin, route, runnable), 0L, 0L));
    }

    public static BukkitTask runTaskLaterAsynchronously(BukkitScheduler scheduler, Plugin plugin, Runnable runnable,
                                                        long delay) {
        RouteFamily route = markBridgeCall("BukkitScheduler#runTaskLaterAsynchronously", "async-delayed", plugin);
        if (smokeNoPassthrough()) return wrap(plugin, false, smokeHandle("BukkitScheduler#runTaskLaterAsynchronously"));
        if (!isFolia()) return scheduler.runTaskLaterAsynchronously(plugin, runnable, delay);
        return wrap(plugin, false, scheduleAsync(plugin, guarded(plugin, route, runnable), delay, 0L));
    }

    public static BukkitTask runTaskTimerAsynchronously(BukkitScheduler scheduler, Plugin plugin, Runnable runnable,
                                                        long delay, long period) {
        RouteFamily route = markBridgeCall("BukkitScheduler#runTaskTimerAsynchronously", "async-repeating", plugin);
        if (smokeNoPassthrough()) return wrap(plugin, false, smokeHandle("BukkitScheduler#runTaskTimerAsynchronously"));
        if (!isFolia()) return scheduler.runTaskTimerAsynchronously(plugin, runnable, delay, period);
        return wrap(plugin, false, scheduleAsync(plugin, guarded(plugin, route, runnable), delay, period));
    }

    public static int scheduleSyncRepeatingTask(BukkitScheduler scheduler, Plugin plugin, Runnable runnable,
                                                long delay, long period) {
        markBridgeCall("BukkitScheduler#scheduleSyncRepeatingTask", "global-repeating", plugin);
        if (smokeNoPassthrough()) return wrap(plugin, true, smokeHandle("BukkitScheduler#scheduleSyncRepeatingTask")).getTaskId();
        if (!isFolia()) return scheduler.scheduleSyncRepeatingTask(plugin, runnable, delay, period);
        return runTaskTimer(scheduler, plugin, runnable, delay, period).getTaskId();
    }

    public static int scheduleSyncDelayedTask(BukkitScheduler scheduler, Plugin plugin, Runnable runnable) {
        markBridgeCall("BukkitScheduler#scheduleSyncDelayedTask", "global", plugin);
        if (smokeNoPassthrough()) return wrap(plugin, true, smokeHandle("BukkitScheduler#scheduleSyncDelayedTask")).getTaskId();
        if (!isFolia()) return scheduler.scheduleSyncDelayedTask(plugin, runnable);
        return runTask(scheduler, plugin, runnable).getTaskId();
    }

    public static int scheduleSyncDelayedTask(BukkitScheduler scheduler, Plugin plugin, Runnable runnable, long delay) {
        markBridgeCall("BukkitScheduler#scheduleSyncDelayedTask", "global-delayed", plugin);
        if (smokeNoPassthrough()) return wrap(plugin, true, smokeHandle("BukkitScheduler#scheduleSyncDelayedTask(long)")).getTaskId();
        if (!isFolia()) return scheduler.scheduleSyncDelayedTask(plugin, runnable, delay);
        return runTaskLater(scheduler, plugin, runnable, delay).getTaskId();
    }

    public static int scheduleAsyncDelayedTask(BukkitScheduler scheduler, Plugin plugin, Runnable runnable) {
        markBridgeCall("BukkitScheduler#scheduleAsyncDelayedTask", "async", plugin);
        if (smokeNoPassthrough()) return wrap(plugin, false, smokeHandle("BukkitScheduler#scheduleAsyncDelayedTask")).getTaskId();
        if (!isFolia()) return scheduler.scheduleAsyncDelayedTask(plugin, runnable);
        return runTaskAsynchronously(scheduler, plugin, runnable).getTaskId();
    }

    public static int scheduleAsyncDelayedTask(BukkitScheduler scheduler, Plugin plugin, Runnable runnable, long delay) {
        markBridgeCall("BukkitScheduler#scheduleAsyncDelayedTask", "async-delayed", plugin);
        if (smokeNoPassthrough()) return wrap(plugin, false, smokeHandle("BukkitScheduler#scheduleAsyncDelayedTask(long)")).getTaskId();
        if (!isFolia()) return scheduler.scheduleAsyncDelayedTask(plugin, runnable, delay);
        return runTaskLaterAsynchronously(scheduler, plugin, runnable, delay).getTaskId();
    }

    public static int scheduleAsyncRepeatingTask(BukkitScheduler scheduler, Plugin plugin, Runnable runnable,
                                                 long delay, long period) {
        markBridgeCall("BukkitScheduler#scheduleAsyncRepeatingTask", "async-repeating", plugin);
        if (smokeNoPassthrough()) return wrap(plugin, false, smokeHandle("BukkitScheduler#scheduleAsyncRepeatingTask")).getTaskId();
        if (!isFolia()) return scheduler.scheduleAsyncRepeatingTask(plugin, runnable, delay, period);
        return runTaskTimerAsynchronously(scheduler, plugin, runnable, delay, period).getTaskId();
    }

    public static void cancelTask(BukkitScheduler scheduler, int taskId) {
        markBridgeCall("BukkitScheduler#cancelTask", "cancel", null);
        BridgeBukkitTask task = TASKS.remove(taskId);
        if (task != null) {
            task.cancel();
            return;
        }
        if (smokeNoPassthrough()) return;
        if (!isFolia()) scheduler.cancelTask(taskId);
    }

    public static void cancelTasks(BukkitScheduler scheduler, Plugin plugin) {
        markBridgeCall("BukkitScheduler#cancelTasks", "cancel-plugin", plugin);
        if (smokeNoPassthrough()) {
            TASKS.values().removeIf(task -> {
                boolean owned = plugin != null && plugin.equals(task.getOwner());
                if (owned) task.cancel();
                return owned;
            });
            return;
        }
        if (!isFolia()) {
            scheduler.cancelTasks(plugin);
            return;
        }
        for (BridgeBukkitTask task : TASKS.values()) {
            if (task.getOwner().equals(plugin)) task.cancel();
        }
        cancelSchedulerTasks("getGlobalRegionScheduler", plugin);
        cancelSchedulerTasks("getAsyncScheduler", plugin);
    }

    public static <T> Future<T> callSyncMethod(BukkitScheduler scheduler, Plugin plugin, Callable<T> callable) {
        RouteFamily route = markBridgeCall("BukkitScheduler#callSyncMethod", "global-future", plugin);
        if (smokeNoPassthrough()) {
            CompletableFuture<T> future = new CompletableFuture<>();
            try {
                future.complete(callable.call());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
            return future;
        }
        if (!isFolia()) return scheduler.callSyncMethod(plugin, callable);
        CompletableFuture<T> future = new CompletableFuture<>();
        // Folia has no true "main thread"; global is the closest legacy scheduler target this bridge can infer.
        scheduleGlobal(plugin, () -> {
            try {
                future.complete(callable.call());
            } catch (Throwable throwable) {
                BridgeDiagnostics.taskFailure(plugin, route, BridgeDiagnostics.captureCaller(), throwable);
                future.completeExceptionally(throwable);
            }
        }, 0L, 0L);
        return future;
    }

    public static BukkitTask runTask(BukkitRunnable runnable, Plugin plugin) {
        RouteFamily route = markBridgeCall("BukkitRunnable#runTask", "global", plugin);
        if (smokeNoPassthrough()) {
            runSmokeTask(plugin, route, runnable);
            return remember(runnable, wrap(plugin, true, smokeHandle("BukkitRunnable#runTask")));
        }
        if (!isFolia()) return runnable.runTask(plugin);
        return remember(runnable, wrap(plugin, true, scheduleGlobal(plugin, guarded(plugin, route, runnable), 0L, 0L)));
    }

    public static BukkitTask runTaskLater(BukkitRunnable runnable, Plugin plugin, long delay) {
        RouteFamily route = markBridgeCall("BukkitRunnable#runTaskLater", "global-delayed", plugin);
        if (smokeNoPassthrough()) return remember(runnable, wrap(plugin, true, smokeHandle("BukkitRunnable#runTaskLater")));
        if (!isFolia()) return runnable.runTaskLater(plugin, delay);
        return remember(runnable, wrap(plugin, true, scheduleGlobal(plugin, guarded(plugin, route, runnable), delay, 0L)));
    }

    public static BukkitTask runTaskTimer(BukkitRunnable runnable, Plugin plugin, long delay, long period) {
        RouteFamily route = markBridgeCall("BukkitRunnable#runTaskTimer", "global-repeating", plugin);
        if (smokeNoPassthrough()) return remember(runnable, wrap(plugin, true, smokeHandle("BukkitRunnable#runTaskTimer")));
        if (!isFolia()) return runnable.runTaskTimer(plugin, delay, period);
        return remember(runnable, wrap(plugin, true, scheduleGlobal(plugin, guarded(plugin, route, runnable), delay, period)));
    }

    public static BukkitTask runTaskAsynchronously(BukkitRunnable runnable, Plugin plugin) {
        RouteFamily route = markBridgeCall("BukkitRunnable#runTaskAsynchronously", "async", plugin);
        if (smokeNoPassthrough()) {
            runSmokeTask(plugin, route, runnable);
            return remember(runnable, wrap(plugin, false, smokeHandle("BukkitRunnable#runTaskAsynchronously")));
        }
        if (!isFolia()) return runnable.runTaskAsynchronously(plugin);
        return remember(runnable, wrap(plugin, false, scheduleAsync(plugin, guarded(plugin, route, runnable), 0L, 0L)));
    }

    public static BukkitTask runTaskLaterAsynchronously(BukkitRunnable runnable, Plugin plugin, long delay) {
        RouteFamily route = markBridgeCall("BukkitRunnable#runTaskLaterAsynchronously", "async-delayed", plugin);
        if (smokeNoPassthrough()) return remember(runnable, wrap(plugin, false, smokeHandle("BukkitRunnable#runTaskLaterAsynchronously")));
        if (!isFolia()) return runnable.runTaskLaterAsynchronously(plugin, delay);
        return remember(runnable, wrap(plugin, false, scheduleAsync(plugin, guarded(plugin, route, runnable), delay, 0L)));
    }

    public static BukkitTask runTaskTimerAsynchronously(BukkitRunnable runnable, Plugin plugin, long delay, long period) {
        RouteFamily route = markBridgeCall("BukkitRunnable#runTaskTimerAsynchronously", "async-repeating", plugin);
        if (smokeNoPassthrough()) return remember(runnable, wrap(plugin, false, smokeHandle("BukkitRunnable#runTaskTimerAsynchronously")));
        if (!isFolia()) return runnable.runTaskTimerAsynchronously(plugin, delay, period);
        return remember(runnable, wrap(plugin, false, scheduleAsync(plugin, guarded(plugin, route, runnable), delay, period)));
    }

    public static void cancel(BukkitRunnable runnable) {
        markBridgeCall("BukkitRunnable#cancel", "cancel", null);
        BridgeBukkitTask task = RUNNABLE_TASKS.remove(runnable);
        if (task != null) {
            task.cancel();
            return;
        }
        if (smokeNoPassthrough()) return;
        if (!isFolia()) runnable.cancel();
    }

    public static boolean isCancelled(BukkitRunnable runnable) {
        markBridgeCall("BukkitRunnable#isCancelled", "status", null);
        BridgeBukkitTask task = RUNNABLE_TASKS.get(runnable);
        if (task != null) return task.isBridgeCancelled();
        if (smokeNoPassthrough()) return false;
        return !isFolia() && runnable.isCancelled();
    }

    public static int bridgeCallCount() {
        return BRIDGE_CALLS.get();
    }

    public static void resetBridgeCallCount() {
        BRIDGE_CALLS.set(0);
    }

    static void recordBridgeCall() {
        BRIDGE_CALLS.incrementAndGet();
    }

    private static RouteFamily markBridgeCall(String sourceApi, String policy, Plugin plugin) {
        recordBridgeCall();
        RouteFamily route = RouteFamily.forSchedulerPolicy(policy);
        BridgeDiagnostics.schedulerCall(sourceApi, route, policy, plugin);
        return route;
    }

    private static BukkitTask remember(BukkitRunnable runnable, BukkitTask task) {
        if (task instanceof BridgeBukkitTask) RUNNABLE_TASKS.put(runnable, (BridgeBukkitTask) task);
        return task;
    }

    private static BridgeBukkitTask wrap(Plugin plugin, boolean sync, Object handle) {
        int taskId = NEXT_TASK_ID.incrementAndGet();
        BridgeBukkitTask task = new BridgeBukkitTask(taskId, plugin, sync, handle);
        TASKS.put(taskId, task);
        return task;
    }

    private static Runnable guarded(Plugin plugin, RouteFamily routeFamily, Runnable runnable) {
        String scheduledFrom = BridgeDiagnostics.captureCaller();
        return () -> {
            try {
                runnable.run();
            } catch (Throwable throwable) {
                BridgeDiagnostics.taskFailure(plugin, routeFamily, scheduledFrom, throwable);
                throw throwable;
            }
        };
    }

    private static void runSmokeTask(Plugin plugin, RouteFamily routeFamily, Runnable runnable) {
        if (!Boolean.getBoolean("foliabytecodebridge.smokeRunTasks")) return;
        guarded(plugin, routeFamily, runnable).run();
    }

    private static Object scheduleGlobal(Plugin plugin, Runnable runnable, long delayTicks, long periodTicks) {
        Object scheduler = scheduler("getGlobalRegionScheduler");
        Consumer<Object> task = ignored -> runnable.run();
        if (periodTicks > 0L) {
            return invoke(scheduler, "runAtFixedRate",
                    new Class<?>[]{Plugin.class, Consumer.class, long.class, long.class},
                    plugin, task, ticksAtLeastOne(delayTicks), ticksAtLeastOne(periodTicks));
        }
        if (delayTicks > 0L) {
            return invoke(scheduler, "runDelayed",
                    new Class<?>[]{Plugin.class, Consumer.class, long.class},
                    plugin, task, ticksAtLeastOne(delayTicks));
        }
        return invoke(scheduler, "run",
                new Class<?>[]{Plugin.class, Consumer.class},
                plugin, task);
    }

    private static Object scheduleAsync(Plugin plugin, Runnable runnable, long delayTicks, long periodTicks) {
        Object scheduler = scheduler("getAsyncScheduler");
        Consumer<Object> task = ignored -> runnable.run();
        if (periodTicks > 0L) {
            return invoke(scheduler, "runAtFixedRate",
                    new Class<?>[]{Plugin.class, Consumer.class, long.class, long.class, TimeUnit.class},
                    plugin, task, ticksToMillis(delayTicks), ticksToMillis(periodTicks), TimeUnit.MILLISECONDS);
        }
        if (delayTicks > 0L) {
            return invoke(scheduler, "runDelayed",
                    new Class<?>[]{Plugin.class, Consumer.class, long.class, TimeUnit.class},
                    plugin, task, ticksToMillis(delayTicks), TimeUnit.MILLISECONDS);
        }
        return invoke(scheduler, "runNow",
                new Class<?>[]{Plugin.class, Consumer.class},
                plugin, task);
    }

    private static void cancelSchedulerTasks(String schedulerGetter, Plugin plugin) {
        try {
            Object scheduler = scheduler(schedulerGetter);
            Method cancelTasks = scheduler.getClass().getMethod("cancelTasks", Plugin.class);
            cancelTasks.invoke(scheduler, plugin);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static Object scheduler(String getterName) {
        try {
            // Reflection keeps this jar loadable on non-Folia API jars and lets the pass-through path work on Paper.
            return Bukkit.class.getMethod(getterName).invoke(null);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Folia scheduler is not available: " + getterName, exception);
        }
    }

    private static Object invoke(Object target, String methodName, Class<?>[] parameterTypes, Object... args) {
        try {
            Method method = target.getClass().getMethod(methodName, parameterTypes);
            return method.invoke(target, args);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to call Folia scheduler method " + methodName, exception);
        }
    }

    private static boolean isFolia() {
        if (Boolean.getBoolean("foliabytecodebridge.forceNonFolia")) return false;
        try {
            Bukkit.class.getMethod("getGlobalRegionScheduler");
            Bukkit.class.getMethod("getAsyncScheduler");
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }

    private static long ticksAtLeastOne(long ticks) {
        return Math.max(1L, ticks);
    }

    private static long ticksToMillis(long ticks) {
        // Bukkit async scheduler delays are tick-based; Folia async scheduler delays are time-based.
        return ticksAtLeastOne(ticks) * 50L;
    }

    private static boolean smokeNoPassthrough() {
        return Boolean.getBoolean("foliabytecodebridge.smokeNoPassthrough");
    }

    private static Object smokeHandle(String sourceApi) {
        return "smoke:" + sourceApi;
    }
}
