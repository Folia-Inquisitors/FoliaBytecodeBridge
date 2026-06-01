package dev.foliabytecodebridge;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class ObjectSchedulerBridge {

    private static final AtomicInteger IDS = new AtomicInteger(10_000);
    private static final Map<Integer, Object> TASKS = new ConcurrentHashMap<>();

    private ObjectSchedulerBridge() {
    }

    public static Object runTask(Object scheduler, Object plugin, Runnable runnable) {
        RouteFamily route = schedulerCall("BukkitScheduler#runTask", "global", plugin);
        return schedule(plugin, route, runnable, 0L, 0L);
    }

    public static Object runTaskLater(Object scheduler, Object plugin, Runnable runnable, long delay) {
        RouteFamily route = schedulerCall("BukkitScheduler#runTaskLater", "global-delayed", plugin);
        return schedule(plugin, route, runnable, delay, 0L);
    }

    public static Object runTaskTimer(Object scheduler, Object plugin, Runnable runnable, long delay, long period) {
        RouteFamily route = schedulerCall("BukkitScheduler#runTaskTimer", "global-repeating", plugin);
        return schedule(plugin, route, runnable, delay, period);
    }

    public static Object runTaskAsynchronously(Object scheduler, Object plugin, Runnable runnable) {
        RouteFamily route = schedulerCall("BukkitScheduler#runTaskAsynchronously", "async", plugin);
        return scheduleAsync(plugin, route, runnable, 0L, 0L);
    }

    public static Object runTaskLaterAsynchronously(Object scheduler, Object plugin, Runnable runnable, long delay) {
        RouteFamily route = schedulerCall("BukkitScheduler#runTaskLaterAsynchronously", "async-delayed", plugin);
        return scheduleAsync(plugin, route, runnable, delay, 0L);
    }

    public static Object runTaskTimerAsynchronously(Object scheduler, Object plugin, Runnable runnable, long delay, long period) {
        RouteFamily route = schedulerCall("BukkitScheduler#runTaskTimerAsynchronously", "async-repeating", plugin);
        return scheduleAsync(plugin, route, runnable, delay, period);
    }

    public static int scheduleSyncDelayedTask(Object scheduler, Object plugin, Runnable runnable) {
        RouteFamily route = schedulerCall("BukkitScheduler#scheduleSyncDelayedTask", "global", plugin);
        return remember(schedule(plugin, route, runnable, 0L, 0L));
    }

    public static int scheduleSyncDelayedTaskLater(Object scheduler, Object plugin, Runnable runnable, long delay) {
        RouteFamily route = schedulerCall("BukkitScheduler#scheduleSyncDelayedTask", "global-delayed", plugin);
        return remember(schedule(plugin, route, runnable, delay, 0L));
    }

    public static int scheduleSyncRepeatingTask(Object scheduler, Object plugin, Runnable runnable, long delay, long period) {
        RouteFamily route = schedulerCall("BukkitScheduler#scheduleSyncRepeatingTask", "global-repeating", plugin);
        return remember(schedule(plugin, route, runnable, delay, period));
    }

    public static int scheduleAsyncDelayedTask(Object scheduler, Object plugin, Runnable runnable) {
        RouteFamily route = schedulerCall("BukkitScheduler#scheduleAsyncDelayedTask", "async", plugin);
        return remember(scheduleAsync(plugin, route, runnable, 0L, 0L));
    }

    public static int scheduleAsyncDelayedTaskLater(Object scheduler, Object plugin, Runnable runnable, long delay) {
        RouteFamily route = schedulerCall("BukkitScheduler#scheduleAsyncDelayedTask", "async-delayed", plugin);
        return remember(scheduleAsync(plugin, route, runnable, delay, 0L));
    }

    public static int scheduleAsyncRepeatingTask(Object scheduler, Object plugin, Runnable runnable, long delay, long period) {
        RouteFamily route = schedulerCall("BukkitScheduler#scheduleAsyncRepeatingTask", "async-repeating", plugin);
        return remember(scheduleAsync(plugin, route, runnable, delay, period));
    }

    public static void cancelTask(Object scheduler, int taskId) {
        BridgeDiagnostics.schedulerObjectCall("BukkitScheduler#cancelTask", "cancel", null);
        cancel(TASKS.remove(taskId));
    }

    public static void cancelTasks(Object scheduler, Object plugin) {
        BridgeDiagnostics.schedulerObjectCall("BukkitScheduler#cancelTasks", "cancel-plugin", plugin);
    }

    public static Object bukkitRunnableRunTask(Object runnable, Object plugin) {
        RouteFamily route = schedulerCall("BukkitRunnable#runTask", "global", plugin);
        return schedule(plugin, route, asRunnable(runnable), 0L, 0L);
    }

    public static Object bukkitRunnableRunTaskLater(Object runnable, Object plugin, long delay) {
        RouteFamily route = schedulerCall("BukkitRunnable#runTaskLater", "global-delayed", plugin);
        return schedule(plugin, route, asRunnable(runnable), delay, 0L);
    }

    public static Object bukkitRunnableRunTaskTimer(Object runnable, Object plugin, long delay, long period) {
        RouteFamily route = schedulerCall("BukkitRunnable#runTaskTimer", "global-repeating", plugin);
        return schedule(plugin, route, asRunnable(runnable), delay, period);
    }

    public static Object bukkitRunnableRunTaskAsynchronously(Object runnable, Object plugin) {
        RouteFamily route = schedulerCall("BukkitRunnable#runTaskAsynchronously", "async", plugin);
        return scheduleAsync(plugin, route, asRunnable(runnable), 0L, 0L);
    }

    public static Object bukkitRunnableRunTaskLaterAsynchronously(Object runnable, Object plugin, long delay) {
        RouteFamily route = schedulerCall("BukkitRunnable#runTaskLaterAsynchronously", "async-delayed", plugin);
        return scheduleAsync(plugin, route, asRunnable(runnable), delay, 0L);
    }

    public static Object bukkitRunnableRunTaskTimerAsynchronously(Object runnable, Object plugin, long delay, long period) {
        RouteFamily route = schedulerCall("BukkitRunnable#runTaskTimerAsynchronously", "async-repeating", plugin);
        return scheduleAsync(plugin, route, asRunnable(runnable), delay, period);
    }

    private static Object schedule(Object plugin, RouteFamily routeFamily, Runnable runnable, long delay, long period) {
        try {
            Object server = plugin.getClass().getMethod("getServer").invoke(plugin);
            Object global = server.getClass().getMethod("getGlobalRegionScheduler").invoke(server);
            Consumer<Object> task = ignored -> guarded(plugin, routeFamily, runnable).run();
            Object scheduled;
            if (period > 0L) {
                scheduled = global.getClass().getMethod("runAtFixedRate", pluginClass(plugin), Consumer.class, long.class, long.class)
                        .invoke(global, plugin, task, Math.max(1L, delay), Math.max(1L, period));
            } else if (delay > 0L) {
                scheduled = global.getClass().getMethod("runDelayed", pluginClass(plugin), Consumer.class, long.class)
                        .invoke(global, plugin, task, delay);
            } else {
                scheduled = global.getClass().getMethod("run", pluginClass(plugin), Consumer.class)
                        .invoke(global, plugin, task);
            }
            return taskProxy(plugin, scheduled, rememberRaw(scheduled));
        } catch (Throwable throwable) {
            throw new IllegalStateException("FBB object scheduler route failed at " + BridgeDiagnostics.captureCaller(), throwable);
        }
    }

    private static Object scheduleAsync(Object plugin, RouteFamily routeFamily, Runnable runnable, long delay, long period) {
        try {
            Object server = plugin.getClass().getMethod("getServer").invoke(plugin);
            Object async = server.getClass().getMethod("getAsyncScheduler").invoke(server);
            Consumer<Object> task = ignored -> guarded(plugin, routeFamily, runnable).run();
            Object scheduled;
            if (period > 0L) {
                scheduled = async.getClass().getMethod("runAtFixedRate", pluginClass(plugin), Consumer.class, long.class, long.class, TimeUnit.class)
                        .invoke(async, plugin, task, ticksToMillis(Math.max(1L, delay)), ticksToMillis(Math.max(1L, period)), TimeUnit.MILLISECONDS);
            } else if (delay > 0L) {
                scheduled = async.getClass().getMethod("runDelayed", pluginClass(plugin), Consumer.class, long.class, TimeUnit.class)
                        .invoke(async, plugin, task, ticksToMillis(delay), TimeUnit.MILLISECONDS);
            } else {
                scheduled = async.getClass().getMethod("runNow", pluginClass(plugin), Consumer.class)
                        .invoke(async, plugin, task);
            }
            return taskProxy(plugin, scheduled, rememberRaw(scheduled));
        } catch (Throwable throwable) {
            throw new IllegalStateException("FBB object async scheduler route failed at " + BridgeDiagnostics.captureCaller(), throwable);
        }
    }

    private static long ticksToMillis(long ticks) {
        return Math.max(1L, ticks) * 50L;
    }

    private static RouteFamily schedulerCall(String sourceApi, String policy, Object plugin) {
        SchedulerBridge.recordBridgeCall();
        RouteFamily route = RouteFamily.forSchedulerPolicy(policy);
        BridgeDiagnostics.schedulerObjectCall(sourceApi, route, policy, plugin);
        return route;
    }

    private static Runnable guarded(Object plugin, RouteFamily routeFamily, Runnable runnable) {
        String scheduledFrom = BridgeDiagnostics.captureCaller();
        return () -> {
            try {
                runnable.run();
            } catch (Throwable throwable) {
                BridgeDiagnostics.taskFailureObject(plugin, routeFamily, scheduledFrom, throwable);
                throw throwable;
            }
        };
    }

    private static Class<?> pluginClass(Object plugin) throws ClassNotFoundException {
        return Class.forName("org.bukkit.plugin.Plugin", false, plugin.getClass().getClassLoader());
    }

    private static Runnable asRunnable(Object object) {
        return (Runnable) object;
    }

    private static int remember(Object task) {
        if (task == null) return -1;
        if (Proxy.isProxyClass(task.getClass())) {
            try {
                return (int) task.getClass().getMethod("getTaskId").invoke(task);
            } catch (Throwable ignored) {
            }
        }
        return rememberRaw(task);
    }

    private static int rememberRaw(Object task) {
        int id = IDS.incrementAndGet();
        TASKS.put(id, task);
        return id;
    }

    private static Object taskProxy(Object plugin, Object scheduled, int taskId) throws ClassNotFoundException {
        Class<?> bukkitTask = Class.forName("org.bukkit.scheduler.BukkitTask", false, plugin.getClass().getClassLoader());
        InvocationHandler handler = (proxy, method, args) -> taskMethod(plugin, scheduled, taskId, method);
        return Proxy.newProxyInstance(bukkitTask.getClassLoader(), new Class<?>[]{bukkitTask}, handler);
    }

    private static Object taskMethod(Object plugin, Object scheduled, int taskId, Method method) {
        return switch (method.getName()) {
            case "getTaskId" -> taskId;
            case "getOwner" -> plugin;
            case "isSync" -> true;
            case "isCancelled" -> false;
            case "cancel" -> {
                cancel(scheduled);
                yield null;
            }
            default -> defaultValue(method.getReturnType());
        };
    }

    private static void cancel(Object scheduled) {
        if (scheduled == null) return;
        try {
            scheduled.getClass().getMethod("cancel").invoke(scheduled);
        } catch (Throwable ignored) {
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0D;
        if (type == float.class) return 0F;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        return null;
    }
}
