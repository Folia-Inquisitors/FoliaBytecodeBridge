package dev.foliabytecodebridge;

import org.bukkit.event.Event;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;

/**
 * Phase 3/4 planner for synthetic multi-owner mutation events.
 *
 * <p>This class intentionally does not mutate blocks, replay listeners, or
 * freeze regions. Its job is to turn an explicit multi-region mutation shape
 * into an auditable plan, then check whether the event exposes a clear
 * prepare/apply/verify contract for a future exact model.</p>
 */
final class SyntheticMultiRegionMutationPlanner {

    private static final Set<String> MUTATION_INTENT_GETTER_NAMES = Set.of(
            "isMutation",
            "isMutationEvent",
            "hasMutations",
            "hasMutation",
            "willMutate",
            "mutatesBlocks",
            "isBlockMutation"
    );

    private static final Set<String> MUTATION_KIND_GETTER_NAMES = Set.of(
            "getMutationKind",
            "getMutationType",
            "getOperation",
            "getAction"
    );

    private static final Set<String> PREPARE_CONTRACT_GETTER_NAMES = Set.of(
            "supportsPreparePhase",
            "hasPreparePhase",
            "canPrepareMutation"
    );

    private static final Set<String> OWNER_APPLY_CONTRACT_GETTER_NAMES = Set.of(
            "supportsOwnerApplyPhase",
            "hasOwnerApplyPhase",
            "canApplyPerOwner"
    );

    private static final Set<String> VERIFY_CONTRACT_GETTER_NAMES = Set.of(
            "supportsAggregateVerifyPhase",
            "hasAggregateVerifyPhase",
            "canVerifyAggregate"
    );

    private SyntheticMultiRegionMutationPlanner() {
    }

    static void tryPlan(String eventName, int listenerCount, Event event,
                        SyntheticEventOwnerExtractor.OwnerScan scan) {
        if (event == null || scan == null || !scan.hasMultiRegionObservation()) return;
        MutationIntent intent = mutationIntent(event);
        for (SyntheticEventOwnerExtractor.MultiRegionObservation observation : scan.multiRegionObservations()) {
            if (observation.readOnly()) continue;
            if (!intent.explicit()) {
                BridgeDiagnostics.syntheticMultiRegionMutationPlan(eventName, listenerCount, observation,
                        "result=blocked reason=no-explicit-mutation-intent action=stay-serialized"
                                + " intentGetter=none");
                continue;
            }
            BridgeDiagnostics.syntheticMultiRegionMutationPlan(eventName, listenerCount, observation,
                    "result=planned-not-executed action=two-phase-mutation-plan-required"
                            + " phases=prepare,owner-apply,aggregate-verify"
                            + " intentGetter=" + intent.getterName()
                            + " mutationKind=" + intent.kind()
                            + " evidence=explicit-mutation-intent-detected");
            logContract(eventName, listenerCount, event, observation, intent);
        }
    }

    private static void logContract(String eventName, int listenerCount, Event event,
                                    SyntheticEventOwnerExtractor.MultiRegionObservation observation,
                                    MutationIntent intent) {
        MutationContract contract = mutationContract(event);
        if (!contract.ready()) {
            BridgeDiagnostics.syntheticMultiRegionMutationContract(eventName, listenerCount, observation,
                    "result=blocked reason=missing-two-phase-contract"
                            + " prepare=" + contract.prepare()
                            + " ownerApply=" + contract.ownerApply()
                            + " aggregateVerify=" + contract.aggregateVerify()
                            + " mutationKind=" + intent.kind()
                            + " action=stay-serialized");
            return;
        }
        BridgeDiagnostics.syntheticMultiRegionMutationContract(eventName, listenerCount, observation,
                "result=ready-not-executed"
                        + " contract=prepare,owner-apply,aggregate-verify"
                        + " mutationKind=" + intent.kind()
                        + " action=contract-ready-no-execute");
        SyntheticMultiRegionMutationExecutor.tryExecute(eventName, listenerCount, event, observation, intent.kind());
    }

    private static MutationIntent mutationIntent(Event event) {
        for (String name : MUTATION_INTENT_GETTER_NAMES) {
            for (Method method : event.getClass().getMethods()) {
                if (!name.equals(method.getName())) continue;
                if (method.getParameterCount() != 0 || Modifier.isStatic(method.getModifiers())) continue;
                if (method.getReturnType() != boolean.class && method.getReturnType() != Boolean.class) continue;
                try {
                    if (Boolean.TRUE.equals(method.invoke(event))) {
                        return new MutationIntent(true, name, mutationKind(event));
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    return new MutationIntent(false, name, "getter-failed");
                }
            }
        }
        return new MutationIntent(false, "none", "unknown");
    }

    private static String mutationKind(Event event) {
        for (String name : MUTATION_KIND_GETTER_NAMES) {
            for (Method method : event.getClass().getMethods()) {
                if (!name.equals(method.getName())) continue;
                if (method.getParameterCount() != 0 || Modifier.isStatic(method.getModifiers())) continue;
                try {
                    Object value = method.invoke(event);
                    if (value != null) {
                        return sanitize(String.valueOf(value));
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    return "kind-getter-failed";
                }
            }
        }
        return "unspecified";
    }

    private static MutationContract mutationContract(Event event) {
        return new MutationContract(
                booleanMarker(event, PREPARE_CONTRACT_GETTER_NAMES),
                booleanMarker(event, OWNER_APPLY_CONTRACT_GETTER_NAMES),
                booleanMarker(event, VERIFY_CONTRACT_GETTER_NAMES)
        );
    }

    private static boolean booleanMarker(Event event, Set<String> names) {
        for (String name : names) {
            for (Method method : event.getClass().getMethods()) {
                if (!name.equals(method.getName())) continue;
                if (method.getParameterCount() != 0 || Modifier.isStatic(method.getModifiers())) continue;
                if (method.getReturnType() != boolean.class && method.getReturnType() != Boolean.class) continue;
                try {
                    return Boolean.TRUE.equals(method.invoke(event));
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    return false;
                }
            }
        }
        return false;
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) return "unspecified";
        return value.replace('\n', ' ').replace('\r', ' ').replace(' ', '_');
    }

    private record MutationIntent(boolean explicit, String getterName, String kind) {
    }

    private record MutationContract(boolean prepare, boolean ownerApply, boolean aggregateVerify) {
        boolean ready() {
            return prepare && ownerApply && aggregateVerify;
        }
    }
}
