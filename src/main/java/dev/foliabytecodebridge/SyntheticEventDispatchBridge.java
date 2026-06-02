package dev.foliabytecodebridge;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredListener;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Experimental dispatcher for legacy custom events that are sync-only but may
 * be fired from a Folia context where Bukkit's normal event manager refuses
 * the call.
 *
 * <p>This deliberately does not model built-in Bukkit/Paper events. Server
 * events often have side effects outside the listener list, so those remain
 * pass-through until a specific event contract is modeled. Custom plugin events
 * are the first narrow target because their visible shared state is normally
 * the event object plus the registered listener chain.</p>
 */
public final class SyntheticEventDispatchBridge {

    private SyntheticEventDispatchBridge() {
    }

    public static void callEvent(PluginManager pluginManager, Event event) {
        if (pluginManager == null || event == null) {
            BridgeDiagnostics.syntheticEventDispatch("invalid", eventName(event), 0,
                    "result=blocked reason=null-plugin-manager-or-event");
            return;
        }

        String eventName = event.getClass().getName();
        RegisteredListener[] listeners = event.getHandlers().getRegisteredListeners();
        SyntheticEventPathState state = SyntheticEventPathState.scanning(eventName, listeners.length);
        if (shouldPassThrough(event)) {
            // Built-in server events may have hidden server-side contracts
            // beyond the visible listener list. Leave them untouched until a
            // specific event model is proven.
            BridgeDiagnostics.syntheticEventDispatch("pass-through", eventName, listeners.length,
                    "result=original reason=not-custom-sync-event async=" + event.isAsynchronous());
            pluginManager.callEvent(event);
            return;
        }

        BridgeDiagnostics.syntheticEventPathState("scan-start", state, "result=scanning");
        SyntheticEventOwnerExtractor.OwnerScan ownerScan = SyntheticEventOwnerExtractor.scan(event);
        // Known owners are route exits: the listener chain runs on the owner
        // scheduler, while unclear listener internals still remain observable
        // through the compatibility context.
        if (ownerScan.entityOwner() != null) {
            SyntheticEventOwnerExtractor.EntityOwner entityOwner = ownerScan.entityOwner();
            BridgeDiagnostics.syntheticEventPathState("route-exit",
                    state.routeExit(RouteFamily.A_ENTITY, entityOwner.methodName(), entityType(entityOwner.entity()),
                            isOwnedByCurrentRegion(entityOwner.entity()) ? "direct-current-owner" : "entity-scheduler"),
                    "result=owner-found");
            dispatchEntityOwnedEvent(event, listeners, entityOwner);
            return;
        }

        if (ownerScan.blockOwner() != null) {
            SyntheticEventOwnerExtractor.BlockOwner blockOwner = ownerScan.blockOwner();
            BridgeDiagnostics.syntheticEventPathState("route-exit",
                    state.routeExit(RouteFamily.C_REGION_BLOCK, blockOwner.methodName(),
                            blockDetail(blockOwner.block()) + " blockCount=" + blockOwner.blockCount(),
                            isOwnedByCurrentRegion(blockOwner.block()) ? "direct-current-owner" : "region-scheduler"),
                    "result=owner-found");
            dispatchBlockOwnedEvent(event, listeners, blockOwner);
            return;
        }

        if (ownerScan.locationOwner() != null) {
            SyntheticEventOwnerExtractor.LocationOwner locationOwner = ownerScan.locationOwner();
            BridgeDiagnostics.syntheticEventPathState("route-exit",
                    state.routeExit(RouteFamily.B_REGION_LOCATION, locationOwner.methodName(),
                            locationDetail(locationOwner.location()),
                            isOwnedByCurrentRegion(locationOwner.location()) ? "direct-current-owner" : "region-scheduler"),
                    "result=owner-found");
            dispatchLocationOwnedEvent(event, listeners, locationOwner);
            return;
        }

        BridgeDiagnostics.syntheticMultiRegionDetect(eventName, listeners.length, ownerScan,
                "action=stay-serialized reason=multi-owner-event-path");
        SyntheticMultiRegionReadBridge.tryReadOnlySplit(eventName, listeners.length, ownerScan);
        SyntheticMultiRegionMutationPlanner.tryPlan(eventName, listeners.length, event, ownerScan);
        BridgeDiagnostics.syntheticEventOwnerMiss(eventName, listeners.length, ownerScan,
                "result=stay-serialized");
        BridgeDiagnostics.syntheticEventPathState("serialized", state.serialized(ownerScan.missSummary()),
                "result=compatibility-lane");
        // No owner was proven, so do not guess global/region/entity. Preserve
        // listener order in the serialized lane and keep the miss evidence loud.
        SyntheticEventPathBridge.run(eventName, listeners.length > 1, listeners.length,
                "synthetic-dispatch-custom-sync-event", () -> {
                    BridgeDiagnostics.syntheticEventDispatch("synthetic-start", eventName, listeners.length,
                            "result=dispatching");
                    for (RegisteredListener listener : listeners) {
                        dispatchListener(event, listener);
                    }
                    BridgeDiagnostics.syntheticEventDispatch("synthetic-finish", eventName, listeners.length,
                            "result=completed");
                });
    }

    private static void dispatchEntityOwnedEvent(Event event, RegisteredListener[] listeners,
                                                 SyntheticEventOwnerExtractor.EntityOwner entityOwner) {
        String eventName = event.getClass().getName();
        Entity entity = entityOwner.entity();
        String ownerType = entityType(entity);
        if (isOwnedByCurrentRegion(entity)) {
            BridgeDiagnostics.syntheticEventRouteExit("current-owner", eventName, RouteFamily.A_ENTITY,
                    entityOwner.methodName(), ownerType, listeners.length,
                    "result=dispatching path=direct-current-owner");
            dispatchEntityOwnedEventBody(event, listeners, entityOwner, "direct-current-owner");
            return;
        }

        BridgeDiagnostics.syntheticEventRouteExit("schedule", eventName, RouteFamily.A_ENTITY,
                entityOwner.methodName(), ownerType, listeners.length,
                "result=queued path=entity-scheduler");
        CompletableFuture<Void> future = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                dispatchEntityOwnedEventBody(event, listeners, entityOwner, "entity-scheduler");
                future.complete(null);
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        };
        Runnable retired = () -> future.completeExceptionally(
                new IllegalStateException("entity scheduler retired before synthetic event dispatch"));
        try {
            if (!executeEntity(entity, bridgePlugin(), task, retired)) {
                throw new IllegalStateException("entity scheduler rejected synthetic event dispatch");
            }
            // Custom events are synchronous from the caller's perspective. The
            // bounded wait preserves that contract without hiding timeout or
            // scheduler failures.
            future.get(5L, TimeUnit.SECONDS);
        } catch (Throwable throwable) {
            BridgeDiagnostics.syntheticEventRouteExitFailure(eventName, RouteFamily.A_ENTITY,
                    entityOwner.methodName(), ownerType, throwable);
        }
    }

    private static void dispatchBlockOwnedEvent(Event event, RegisteredListener[] listeners,
                                                SyntheticEventOwnerExtractor.BlockOwner blockOwner) {
        String eventName = event.getClass().getName();
        Block block = blockOwner.block();
        String ownerType = blockDetail(block) + " blockCount=" + blockOwner.blockCount();
        if (isOwnedByCurrentRegion(block)) {
            BridgeDiagnostics.syntheticEventRouteExit("current-owner", eventName, RouteFamily.C_REGION_BLOCK,
                    blockOwner.methodName(), ownerType, listeners.length,
                    "result=dispatching path=direct-current-owner");
            dispatchRegionOwnedEventBody(event, listeners, RouteFamily.C_REGION_BLOCK,
                    blockOwner.methodName(), ownerType, "direct-current-owner",
                    "synthetic-dispatch-custom-sync-event-block-owner");
            return;
        }

        BridgeDiagnostics.syntheticEventRouteExit("schedule", eventName, RouteFamily.C_REGION_BLOCK,
                blockOwner.methodName(), ownerType, listeners.length,
                "result=queued path=region-scheduler");
        scheduleRegionEvent(event, listeners, RouteFamily.C_REGION_BLOCK,
                blockOwner.methodName(), ownerType, block.getLocation(),
                "synthetic-dispatch-custom-sync-event-block-owner");
    }

    private static void dispatchLocationOwnedEvent(Event event, RegisteredListener[] listeners,
                                                   SyntheticEventOwnerExtractor.LocationOwner locationOwner) {
        String eventName = event.getClass().getName();
        Location location = locationOwner.location();
        String ownerType = locationDetail(location);
        if (isOwnedByCurrentRegion(location)) {
            BridgeDiagnostics.syntheticEventRouteExit("current-owner", eventName, RouteFamily.B_REGION_LOCATION,
                    locationOwner.methodName(), ownerType, listeners.length,
                    "result=dispatching path=direct-current-owner");
            dispatchRegionOwnedEventBody(event, listeners, RouteFamily.B_REGION_LOCATION,
                    locationOwner.methodName(), ownerType, "direct-current-owner",
                    "synthetic-dispatch-custom-sync-event-location-owner");
            return;
        }

        BridgeDiagnostics.syntheticEventRouteExit("schedule", eventName, RouteFamily.B_REGION_LOCATION,
                locationOwner.methodName(), ownerType, listeners.length,
                "result=queued path=region-scheduler");
        scheduleRegionEvent(event, listeners, RouteFamily.B_REGION_LOCATION,
                locationOwner.methodName(), ownerType, location,
                "synthetic-dispatch-custom-sync-event-location-owner");
    }

    private static void scheduleRegionEvent(Event event, RegisteredListener[] listeners, RouteFamily routeFamily,
                                            String ownerMethod, String ownerType, Location location,
                                            String reason) {
        String eventName = event.getClass().getName();
        CompletableFuture<Void> future = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                dispatchRegionOwnedEventBody(event, listeners, routeFamily, ownerMethod, ownerType,
                        "region-scheduler", reason);
                future.complete(null);
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        };
        try {
            executeRegion(location, bridgePlugin(), task);
            // Same synchronous custom-event contract as entity dispatch: wait
            // briefly for the owner route and report failures as route evidence.
            future.get(5L, TimeUnit.SECONDS);
        } catch (Throwable throwable) {
            BridgeDiagnostics.syntheticEventRouteExitFailure(eventName, routeFamily,
                    ownerMethod, ownerType, throwable);
        }
    }

    private static void dispatchEntityOwnedEventBody(Event event, RegisteredListener[] listeners,
                                                     SyntheticEventOwnerExtractor.EntityOwner entityOwner,
                                                     String path) {
        String eventName = event.getClass().getName();
        try (CompatibilityContext.Scope ignored = CompatibilityContext.enterSyntheticEventPath(
                eventName, listeners.length > 1, listeners.length,
                "synthetic-dispatch-custom-sync-event-entity-owner")) {
            BridgeDiagnostics.syntheticEventDispatch("synthetic-start", eventName, listeners.length,
                    "result=dispatching route=A_ENTITY path=" + path
                            + " ownerMethod=" + entityOwner.methodName()
                            + " ownerType=" + entityType(entityOwner.entity()));
            for (RegisteredListener listener : listeners) {
                dispatchListener(event, listener, RouteFamily.A_ENTITY, entityOwner.methodName(),
                        entityType(entityOwner.entity()), path);
            }
            BridgeDiagnostics.syntheticEventDispatch("synthetic-finish", eventName, listeners.length,
                    "result=completed route=A_ENTITY path=" + path
                            + " ownerMethod=" + entityOwner.methodName()
                            + " ownerType=" + entityType(entityOwner.entity()));
        }
    }

    private static void dispatchRegionOwnedEventBody(Event event, RegisteredListener[] listeners,
                                                     RouteFamily routeFamily, String ownerMethod,
                                                     String ownerType, String path, String reason) {
        String eventName = event.getClass().getName();
        try (CompatibilityContext.Scope ignored = CompatibilityContext.enterSyntheticEventPath(
                eventName, listeners.length > 1, listeners.length, reason)) {
            BridgeDiagnostics.syntheticEventDispatch("synthetic-start", eventName, listeners.length,
                    "result=dispatching route=" + routeFamily.label() + " path=" + path
                            + " ownerMethod=" + ownerMethod
                            + " ownerType=" + ownerType);
            for (RegisteredListener listener : listeners) {
                dispatchListener(event, listener, routeFamily, ownerMethod, ownerType, path);
            }
            BridgeDiagnostics.syntheticEventDispatch("synthetic-finish", eventName, listeners.length,
                    "result=completed route=" + routeFamily.label() + " path=" + path
                            + " ownerMethod=" + ownerMethod
                            + " ownerType=" + ownerType);
        }
    }

    private static void dispatchListener(Event event, RegisteredListener listener) {
        dispatchListener(event, listener, null, "none", "none", "serialized-compatibility-lane");
    }

    private static void dispatchListener(Event event, RegisteredListener listener, RouteFamily routeFamily,
                                         String ownerMethod, String ownerType, String path) {
        String eventName = event.getClass().getName();
        String owner = listenerOwner(listener);
        if (routeFamily != null) {
            BridgeDiagnostics.syntheticEventListenerRouteExit(eventName, owner, routeFamily,
                    ownerMethod, ownerType, path, "result=dispatching");
        }
        SyntheticEventPathBridge.observeListener(eventName, owner, "DISPATCH",
                "call-registered-listener", cancellationState(event));
        try (SyntheticListenerConcurrencyTracker.Invocation ignored =
                     SyntheticListenerConcurrencyTracker.enter(eventName, owner, routeFamily, ownerMethod, path)) {
            listener.callEvent(event);
        } catch (EventException exception) {
            BridgeDiagnostics.syntheticEventDispatchFailure(eventName, owner, exception);
        } catch (RuntimeException exception) {
            BridgeDiagnostics.syntheticEventDispatchFailure(eventName, owner, exception);
        }
    }

    private static boolean shouldPassThrough(Event event) {
        if (event.isAsynchronous()) return true;
        String name = event.getClass().getName();
        return name.startsWith("org.bukkit.")
                || name.startsWith("io.papermc.")
                || name.startsWith("com.destroystokyo.paper.");
    }

    private static boolean isOwnedByCurrentRegion(Entity entity) {
        if (Boolean.getBoolean("foliabytecodebridge.smokeCurrentEntityOwner")) return true;
        try {
            Method method = Bukkit.class.getMethod("isOwnedByCurrentRegion", Entity.class);
            Object result = method.invoke(null, entity);
            return Boolean.TRUE.equals(result);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isOwnedByCurrentRegion(Block block) {
        if (Boolean.getBoolean("foliabytecodebridge.smokeCurrentBlockOwner")) return true;
        if (block == null) return true;
        try {
            Method method = Bukkit.class.getMethod("isOwnedByCurrentRegion", Block.class);
            Object result = method.invoke(null, block);
            return Boolean.TRUE.equals(result);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isOwnedByCurrentRegion(Location location) {
        if (Boolean.getBoolean("foliabytecodebridge.smokeCurrentLocationOwner")) return true;
        if (location == null || location.getWorld() == null) return true;
        try {
            Method method = Bukkit.class.getMethod("isOwnedByCurrentRegion", Location.class);
            Object result = method.invoke(null, location);
            return Boolean.TRUE.equals(result);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private static boolean executeEntity(Entity entity, Plugin plugin, Runnable task, Runnable retired) {
        try {
            Object scheduler = entity.getClass().getMethod("getScheduler").invoke(entity);
            Method execute = scheduler.getClass().getMethod("execute",
                    Plugin.class, Runnable.class, Runnable.class, long.class);
            Object result = execute.invoke(scheduler, plugin, task, retired, 0L);
            return Boolean.TRUE.equals(result);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            throw new IllegalStateException("Unable to call entity scheduler for synthetic event dispatch", exception);
        }
    }

    private static void executeRegion(Location location, Plugin plugin, Runnable task) {
        try {
            Object scheduler = Bukkit.class.getMethod("getRegionScheduler").invoke(null);
            Method run = scheduler.getClass().getMethod("run", Plugin.class, Location.class, Consumer.class);
            Consumer<Object> consumer = ignored -> task.run();
            run.invoke(scheduler, plugin, location, consumer);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            throw new IllegalStateException("Unable to call region scheduler for synthetic event dispatch", exception);
        }
    }

    private static Plugin bridgePlugin() {
        try {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("FoliaBytecodeBridge");
            if (plugin != null) return plugin;
        } catch (Throwable ignored) {
        }
        throw new IllegalStateException("FoliaBytecodeBridge plugin is not enabled for synthetic event route exit");
    }

    private static String entityType(Entity entity) {
        if (entity == null) return "unknown";
        try {
            Object type = entity.getClass().getMethod("getType").invoke(entity);
            if (type != null) return String.valueOf(type);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return entity.getClass().getName();
    }

    private static String blockDetail(Block block) {
        if (block == null) return "unknown-block";
        try {
            World world = block.getWorld();
            String worldName = world == null ? "unknown-world" : world.getName();
            return "block=" + worldName + "," + block.getX() + "," + block.getY() + "," + block.getZ();
        } catch (RuntimeException exception) {
            return "block=" + block.getClass().getName();
        }
    }

    private static String locationDetail(Location location) {
        if (location == null) return "unknown-location";
        try {
            World world = location.getWorld();
            String worldName = world == null ? "unknown-world" : world.getName();
            return "location=" + worldName + ","
                    + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ();
        } catch (RuntimeException exception) {
            return "location=" + location.getClass().getName();
        }
    }

    private static boolean cancellationState(Event event) {
        if (!(event instanceof org.bukkit.event.Cancellable cancellable)) return false;
        return cancellable.isCancelled();
    }

    private static String listenerOwner(RegisteredListener listener) {
        try {
            String plugin = listener.getPlugin() == null ? "unknown-plugin" : listener.getPlugin().getName();
            String owner = listener.getListener() == null
                    ? "unknown-listener"
                    : listener.getListener().getClass().getName();
            return plugin + "/" + owner;
        } catch (Throwable throwable) {
            return "unknown-listener";
        }
    }

    private static String eventName(Event event) {
        return event == null ? "unknown-event" : event.getClass().getName();
    }
}
