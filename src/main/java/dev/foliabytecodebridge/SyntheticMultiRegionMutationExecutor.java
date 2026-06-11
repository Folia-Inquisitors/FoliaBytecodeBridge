package dev.foliabytecodebridge;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.event.Event;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Phase 5B guarded executor for exact synthetic multi-region mutation models.
 *
 * <p>This is not a generic "freeze the world" mechanism. It only runs when an
 * event already passed Phase 4 contract readiness and also exposes exact
 * prepare/apply/verify hooks. Unknown shapes remain serialized and logged.</p>
 */
final class SyntheticMultiRegionMutationExecutor {

    private static final String MARKER_EXECUTOR_DISABLED = "FBB_SYNTHETIC_MUTATION_EXECUTOR_DISABLED_V1";
    private static final String MARKER_MISSING_HOOKS = "FBB_SYNTHETIC_MUTATION_MISSING_HOOKS_V1";
    private static final String MARKER_NO_OWNER_ANCHORS = "FBB_SYNTHETIC_MUTATION_NO_OWNER_ANCHORS_V1";
    private static final String MARKER_PREPARE_BLOCKED = "FBB_SYNTHETIC_MUTATION_PREPARE_BLOCKED_V1";
    private static final String MARKER_OWNER_APPLY_SCHEDULED = "FBB_SYNTHETIC_MUTATION_OWNER_APPLY_SCHEDULED_V1";
    private static final String MARKER_COMPLETED_VERIFIED = "FBB_SYNTHETIC_MUTATION_COMPLETED_VERIFIED_V1";
    private static final String MARKER_VERIFY_BLOCKED = "FBB_SYNTHETIC_MUTATION_VERIFY_BLOCKED_V1";

    private static final Set<String> PREPARE_METHOD_NAMES = Set.of(
            "prepareMutation",
            "prepareSyntheticMutation"
    );

    private static final Set<String> APPLY_METHOD_NAMES = Set.of(
            "applyOwnerMutation",
            "applyMutationOwner",
            "applyOwner"
    );

    private static final Set<String> VERIFY_METHOD_NAMES = Set.of(
            "verifyAggregateMutation",
            "verifyMutationAggregate",
            "verifyAggregate"
    );

    private SyntheticMultiRegionMutationExecutor() {
    }

    static void tryExecute(String eventName, int listenerCount, Event event,
                           SyntheticEventOwnerExtractor.MultiRegionObservation observation,
                           String mutationKind) {
        if (event == null || observation == null || observation.readOnly()) return;
        if (!enabled()) {
            BridgeDiagnostics.syntheticMultiRegionMutationExecute(eventName, listenerCount, observation,
                    "result=contract-disabled reason=executor-disabled"
                            + " marker=" + MARKER_EXECUTOR_DISABLED
                            + " mutationKind=" + safe(mutationKind)
                            + " enable=-Dfoliabytecodebridge.syntheticMutationExecutor=true"
                            + " action=stay-serialized");
            return;
        }

        Hooks hooks = hooks(event);
        if (!hooks.ready()) {
            BridgeDiagnostics.syntheticMultiRegionMutationExecute(eventName, listenerCount, observation,
                    "result=contract-missing reason=missing-executor-hooks"
                            + " marker=" + MARKER_MISSING_HOOKS
                            + " prepareHook=" + hookName(hooks.prepare())
                            + " applyHook=" + hookName(hooks.apply())
                            + " verifyHook=" + hookName(hooks.verify())
                            + " mutationKind=" + safe(mutationKind)
                            + " action=stay-serialized");
            return;
        }

        List<Block> anchors = observation.ownerAnchors();
        if (anchors == null || anchors.isEmpty()) {
            BridgeDiagnostics.syntheticMultiRegionMutationExecute(eventName, listenerCount, observation,
                    "result=contract-missing reason=no-owner-anchors"
                            + " marker=" + MARKER_NO_OWNER_ANCHORS
                            + " mutationKind=" + safe(mutationKind)
                            + " action=stay-serialized");
            return;
        }

        try {
            if (!invokeBoolean(event, hooks.prepare())) {
                BridgeDiagnostics.syntheticMultiRegionMutationExecute(eventName, listenerCount, observation,
                        "result=contract-rejected reason=prepare-returned-false"
                                + " marker=" + MARKER_PREPARE_BLOCKED
                                + " prepareHook=" + hooks.prepare().getName()
                                + " mutationKind=" + safe(mutationKind)
                                + " action=stay-serialized");
                return;
            }
            scheduleApply(eventName, listenerCount, event, observation, hooks, anchors, mutationKind);
        } catch (Throwable throwable) {
            BridgeDiagnostics.syntheticMultiRegionMutationExecuteFailure(eventName, listenerCount,
                    observation, throwable);
        }
    }

    private static void scheduleApply(String eventName, int listenerCount, Event event,
                                      SyntheticEventOwnerExtractor.MultiRegionObservation observation,
                                      Hooks hooks, List<Block> anchors, String mutationKind) {
        if (Boolean.getBoolean("foliabytecodebridge.smokeSyntheticMutationExecutor")) {
            int completed = 0;
            for (Block anchor : anchors) {
                if (invokeApply(event, hooks.apply(), anchor)) completed++;
            }
            boolean verified = invokeVerify(event, hooks.verify(), anchors.size(), completed);
            BridgeDiagnostics.syntheticMultiRegionMutationExecute(eventName, listenerCount, observation,
                    "result=" + (verified ? "completed" : "contract-rejected")
                            + " reason=" + (verified ? "verified" : "verify-returned-false")
                            + " marker=" + (verified ? MARKER_COMPLETED_VERIFIED : MARKER_VERIFY_BLOCKED)
                            + " scheduledOwners=" + anchors.size()
                            + " completedOwners=" + completed
                            + " prepareHook=" + hooks.prepare().getName()
                            + " applyHook=" + hooks.apply().getName()
                            + " verifyHook=" + hooks.verify().getName()
                            + " mutationKind=" + safe(mutationKind)
                            + " action=two-phase-mutation-executor"
                            + " mode=smoke-inline");
            return;
        }

        List<CompletableFuture<Boolean>> futures = new ArrayList<>();
        for (Block anchor : anchors) {
            if (anchor == null) continue;
            futures.add(scheduleOwnerApply(event, hooks.apply(), anchor));
        }
        if (futures.isEmpty()) {
            BridgeDiagnostics.syntheticMultiRegionMutationExecute(eventName, listenerCount, observation,
                    "result=contract-missing reason=no-applicable-owner-anchors"
                            + " marker=" + MARKER_NO_OWNER_ANCHORS
                            + " mutationKind=" + safe(mutationKind)
                            + " action=stay-serialized");
            return;
        }

        BridgeDiagnostics.syntheticMultiRegionMutationExecute(eventName, listenerCount, observation,
                "result=scheduled scheduledOwners=" + futures.size()
                        + " marker=" + MARKER_OWNER_APPLY_SCHEDULED
                        + " prepareHook=" + hooks.prepare().getName()
                        + " applyHook=" + hooks.apply().getName()
                        + " verifyHook=" + hooks.verify().getName()
                        + " mutationKind=" + safe(mutationKind)
                        + " action=owner-apply-tasks"
                        + " mode=nonblocking");

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .orTimeout(5L, TimeUnit.SECONDS)
                .whenComplete((ignored, throwable) -> {
                    if (throwable != null) {
                        BridgeDiagnostics.syntheticMultiRegionMutationExecuteFailure(eventName, listenerCount,
                                observation, throwable);
                        return;
                    }
                    int completed = 0;
                    for (CompletableFuture<Boolean> future : futures) {
                        if (future.isDone() && !future.isCompletedExceptionally() && Boolean.TRUE.equals(future.join())) {
                            completed++;
                        }
                    }
                    boolean verified = invokeVerify(event, hooks.verify(), futures.size(), completed);
                    BridgeDiagnostics.syntheticMultiRegionMutationExecute(eventName, listenerCount, observation,
                            "result=" + (verified ? "completed" : "contract-rejected")
                                    + " reason=" + (verified ? "verified" : "verify-returned-false")
                                    + " marker=" + (verified ? MARKER_COMPLETED_VERIFIED : MARKER_VERIFY_BLOCKED)
                                    + " scheduledOwners=" + futures.size()
                                    + " completedOwners=" + completed
                                    + " prepareHook=" + hooks.prepare().getName()
                                    + " applyHook=" + hooks.apply().getName()
                                    + " verifyHook=" + hooks.verify().getName()
                                    + " mutationKind=" + safe(mutationKind)
                                    + " action=two-phase-mutation-executor"
                                    + " mode=nonblocking");
                });
    }

    private static CompletableFuture<Boolean> scheduleOwnerApply(Event event, Method apply, Block block) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        try {
            Object scheduler = Bukkit.class.getMethod("getRegionScheduler").invoke(null);
            Method run = scheduler.getClass().getMethod("run", Plugin.class, Location.class, Consumer.class);
            Consumer<Object> task = ignored -> {
                try {
                    future.complete(invokeApply(event, apply, block));
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            };
            run.invoke(scheduler, bridgePlugin(), block.getLocation(), task);
        } catch (Throwable throwable) {
            future.completeExceptionally(new IllegalStateException(
                    "Unable to schedule synthetic multi-region mutation owner apply", throwable));
        }
        return future;
    }

    private static Hooks hooks(Event event) {
        return new Hooks(findPrepare(event), findApply(event), findVerify(event));
    }

    private static Method findPrepare(Event event) {
        for (String name : PREPARE_METHOD_NAMES) {
            Method method = zeroArg(event, name);
            if (method != null && supportedReturn(method)) return method;
        }
        return null;
    }

    private static Method findApply(Event event) {
        for (String name : APPLY_METHOD_NAMES) {
            for (Method method : event.getClass().getMethods()) {
                if (!name.equals(method.getName())) continue;
                if (Modifier.isStatic(method.getModifiers())) continue;
                if (method.getParameterCount() != 1) continue;
                if (!method.getParameterTypes()[0].isAssignableFrom(Block.class)) continue;
                if (supportedReturn(method)) return method;
            }
        }
        return null;
    }

    private static Method findVerify(Event event) {
        for (String name : VERIFY_METHOD_NAMES) {
            for (Method method : event.getClass().getMethods()) {
                if (!name.equals(method.getName())) continue;
                if (Modifier.isStatic(method.getModifiers())) continue;
                if (method.getParameterCount() == 0 && supportedReturn(method)) return method;
                if (method.getParameterCount() == 2
                        && method.getParameterTypes()[0] == int.class
                        && method.getParameterTypes()[1] == int.class
                        && supportedReturn(method)) {
                    return method;
                }
            }
        }
        return null;
    }

    private static Method zeroArg(Event event, String name) {
        for (Method method : event.getClass().getMethods()) {
            if (!name.equals(method.getName())) continue;
            if (Modifier.isStatic(method.getModifiers())) continue;
            if (method.getParameterCount() == 0) return method;
        }
        return null;
    }

    private static boolean supportedReturn(Method method) {
        Class<?> type = method.getReturnType();
        return type == void.class || type == boolean.class || type == Boolean.class;
    }

    private static boolean invokeBoolean(Event event, Method method) {
        try {
            Object result = method.invoke(event);
            return result == null || Boolean.TRUE.equals(result);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Synthetic mutation hook failed: " + method.getName(), exception);
        }
    }

    private static boolean invokeApply(Event event, Method method, Block block) {
        try {
            Object result = method.invoke(event, block);
            return result == null || Boolean.TRUE.equals(result);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Synthetic mutation apply hook failed: " + method.getName(), exception);
        }
    }

    private static boolean invokeVerify(Event event, Method method, int scheduledOwners, int completedOwners) {
        try {
            Object result = method.getParameterCount() == 2
                    ? method.invoke(event, scheduledOwners, completedOwners)
                    : method.invoke(event);
            return result == null || Boolean.TRUE.equals(result);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Synthetic mutation verify hook failed: " + method.getName(), exception);
        }
    }

    private static boolean enabled() {
        return BridgeConfig.syntheticMutationExecutor()
                || Boolean.getBoolean("foliabytecodebridge.smokeSyntheticMutationExecutor");
    }

    private static Plugin bridgePlugin() {
        return BridgePluginResolver.requirePlugin("synthetic mutation executor");
    }

    private static String hookName(Method method) {
        return method == null ? "missing" : method.getName();
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "unspecified";
        return value.replace('\n', ' ').replace('\r', ' ').replace(' ', '_');
    }

    private record Hooks(Method prepare, Method apply, Method verify) {
        boolean ready() {
            return prepare != null && apply != null && verify != null;
        }
    }
}
