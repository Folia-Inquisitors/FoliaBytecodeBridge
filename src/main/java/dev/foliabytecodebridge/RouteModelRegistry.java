package dev.foliabytecodebridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime architecture map for route-family evidence.
 *
 * <p>This class does not decide whether a call is safe. It summarizes the
 * evidence already produced by transformers and bridge wrappers so live logs can
 * answer: which bytecode/API shape, which route family, what owner model, what
 * rewrite status, and what needs work next.</p>
 */
final class RouteModelRegistry {

    private static final ConcurrentMap<String, Model> MODELS = new ConcurrentHashMap<>();
    private static final Set<String> EMITTED_LINES = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final AtomicLong LAST_SUMMARY_NANOS = new AtomicLong();

    private RouteModelRegistry() {
    }

    static Report recordBytecode(RouteFamily route, String api, String owner, String name, String descriptor,
                                 String action, String rule, String outcome, String bridge,
                                 String className, String jar) {
        String status = normalizeStatus(action, outcome);
        String next = nextHint(route, rule, outcome, bridge);
        RouteRuleRegistry.RouteRule routeRule = RouteRuleRegistry.matchExact(owner.replace('.', '/'), name, descriptor)
                .orElse(null);
        String key = key(route, api, owner, descriptor);
        Model model = MODELS.computeIfAbsent(key, ignored -> new Model(route, api, owner, descriptor));
        boolean changed = model.update(status, next,
                evidence("bytecode", rule, outcome, bridge, className, jar),
                routeRule);
        return changed ? report(model) : null;
    }

    static Report recordRuntime(String api, RouteFamily route, String family, String nextAction,
                                String detail, String caller, Throwable throwable) {
        String owner = ownerModel(route, family, nextAction, detail);
        String descriptor = "runtime";
        String status = runtimeStatus(detail, throwable);
        String key = key(route, api, owner, descriptor);
        RouteRuleRegistry.RouteRule routeRule = RouteRuleRegistry.matchRuntimeApi(api).orElse(null);
        Model model = MODELS.computeIfAbsent(key, ignored -> new Model(route, api, owner, descriptor));
        boolean changed = model.update(status, safe(nextAction),
                evidence("runtime", family, detail, caller, null, null), routeRule);
        return changed ? report(model) : null;
    }

    private static Report report(Model model) {
        List<String> lines = new ArrayList<>();
        String line = model.line();
        if (EMITTED_LINES.add(line)) {
            lines.add(line);
        }
        String summary = shouldEmitSummary() ? summaryLine() : null;
        if (summary != null && EMITTED_LINES.add(summary)) {
            lines.add(summary);
        }
        return lines.isEmpty() ? null : new Report(lines);
    }

    private static boolean shouldEmitSummary() {
        int size = MODELS.size();
        if (size == 1 || size % 25 == 0) return true;
        int intervalSeconds = Math.max(0, BridgeConfig.modelSummaryIntervalSeconds());
        if (intervalSeconds == 0) return false;
        long now = System.nanoTime();
        long intervalNanos = TimeUnit.SECONDS.toNanos(intervalSeconds);
        long previous = LAST_SUMMARY_NANOS.get();
        return now - previous >= intervalNanos && LAST_SUMMARY_NANOS.compareAndSet(previous, now);
    }

    private static String summaryLine() {
        Map<RouteFamily, Integer> routeCounts = new EnumMap<>(RouteFamily.class);
        int rewritten = 0;
        int modeled = 0;
        int traceOnly = 0;
        int missed = 0;
        int blocked = 0;
        int failures = 0;
        int schedulers = 0;
        for (Model model : MODELS.values()) {
            routeCounts.merge(model.route, 1, Integer::sum);
            if (model.statuses.contains("rewritten") || model.statuses.contains("routed")) rewritten++;
            if (model.statuses.contains("modeled")) modeled++;
            if (model.statuses.contains("trace-only")) traceOnly++;
            if (model.statuses.contains("missed")) missed++;
            if (model.statuses.contains("blocked-sync-return")) blocked++;
            if (model.statuses.contains("failure")) failures++;
            if (model.statuses.contains("scheduler")) schedulers++;
        }
        StringBuilder routes = new StringBuilder();
        for (RouteFamily route : RouteFamily.values()) {
            if (routes.length() > 0) routes.append(',');
            routes.append(route.label()).append('=').append(routeCounts.getOrDefault(route, 0));
        }
        return "[FBB model-summary] methods=" + MODELS.size()
                + " routes=" + routes
                + " knownRules=" + RouteRuleRegistry.rules().size()
                + " rewritten=" + rewritten
                + " modeled=" + modeled
                + " traceOnly=" + traceOnly
                + " missed=" + missed
                + " blockedSyncReturn=" + blocked
                + " failures=" + failures
                + " schedulers=" + schedulers;
    }

    private static String key(RouteFamily route, String api, String owner, String descriptor) {
        return route.label() + "|" + safe(api) + "|" + safe(owner) + "|" + safe(descriptor);
    }

    private static String normalizeStatus(String action, String outcome) {
        String value = (safe(action) + " " + safe(outcome)).toLowerCase(java.util.Locale.ROOT);
        if (value.contains("missed")) return "missed";
        if (value.contains("trace-only")) return "trace-only";
        if (value.contains("modeled")) return "modeled";
        if (value.contains("rewritten")) return "rewritten";
        return safe(action).isBlank() ? "observed" : safe(action);
    }

    private static String runtimeStatus(String detail, Throwable throwable) {
        String value = safe(detail).toLowerCase(java.util.Locale.ROOT);
        if (throwable != null || value.contains("failure=")) return "failure";
        if (value.contains("blocked-sync-return-avoided")) return "blocked-sync-return";
        if (value.contains("policy=shim-model") || value.contains("action=modeled")) return "modeled";
        if (value.contains("policy=preemptive-safe") || value.contains("fallback=preemptive")
                || value.contains("fallback=scheduler") || value.contains("fallback=split")) return "routed";
        if (value.contains("policy=async") || value.contains("policy=global")) return "scheduler";
        if (value.contains("policy=")) return "observed";
        return "observed";
    }

    private static String ownerModel(RouteFamily route, String family, String nextAction, String detail) {
        String combined = (safe(family) + " " + safe(nextAction) + " " + safe(detail)).toLowerCase(java.util.Locale.ROOT);
        if (route == RouteFamily.A_ENTITY || combined.contains("entity-scheduler")) return "entity";
        if (route == RouteFamily.D_PLAYER_UI && combined.contains("scoreboard")) return "scoreboard-model";
        if (route == RouteFamily.D_PLAYER_UI) return "player-ui";
        if (route == RouteFamily.C_REGION_BLOCK) return "block-region";
        if (route == RouteFamily.B_REGION_LOCATION) return "location-region";
        if (route == RouteFamily.G_WORLD_SCAN_SPLIT) return "split-world-scan";
        if (route == RouteFamily.S_ASYNC) return "async";
        if (route == RouteFamily.S_GLOBAL) return "global";
        if (route == RouteFamily.F_PLAYER_VISIBILITY) return "player-visibility";
        if (combined.contains("block")) return "block-region";
        if (combined.contains("location") || combined.contains("chunk")) return "location-region";
        return route.label().toLowerCase(java.util.Locale.ROOT);
    }

    private static String nextHint(RouteFamily route, String rule, String outcome, String bridge) {
        String combined = (safe(rule) + " " + safe(outcome) + " " + safe(bridge)).toLowerCase(java.util.Locale.ROOT);
        if (combined.contains("shim") || combined.contains("model")) return "shim-model";
        if (combined.contains("teleport")) return "entity-scheduler-teleport-async";
        if (combined.contains("command")) return "global-or-entity-command-dispatch";
        if (route == RouteFamily.D_PLAYER_UI) return "player-ui-or-model";
        if (route == RouteFamily.G_WORLD_SCAN_SPLIT) return "split-or-return-model";
        return route.description().replace(' ', '-');
    }

    private static String evidence(String type, String a, String b, String c, String d, String e) {
        StringBuilder builder = new StringBuilder(type);
        append(builder, "rule", a);
        append(builder, "outcome", b);
        append(builder, "bridge", c);
        append(builder, "class", d);
        append(builder, "jar", e);
        return builder.toString();
    }

    private static void append(StringBuilder builder, String key, String value) {
        if (value == null || value.isBlank()) return;
        builder.append(';').append(key).append('=').append(compact(value, 160));
    }

    private static String compact(String value, int max) {
        String compact = safe(value).replace('\n', ' ').replace('\r', ' ').replace(' ', '_');
        return compact.length() <= max ? compact : compact.substring(0, max) + "...";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    static final class Report {
        private final List<String> lines;

        private Report(List<String> lines) {
            this.lines = lines;
        }

        List<String> lines() {
            return lines;
        }
    }

    private static final class Model {
        private final RouteFamily route;
        private final String api;
        private final String owner;
        private final String descriptor;
        private final Set<String> statuses = new LinkedHashSet<>();
        private final Set<String> nextHints = new LinkedHashSet<>();
        private volatile String evidence = "unknown";
        private volatile String routeRulePolicy = "dynamic-or-unregistered";
        private volatile String routeRuleStatus = "dynamic-or-unregistered";
        private volatile String routeRuleNote = "dynamic-or-unregistered";

        private Model(RouteFamily route, String api, String owner, String descriptor) {
            this.route = route;
            this.api = safe(api);
            this.owner = safe(owner);
            this.descriptor = safe(descriptor);
        }

        synchronized boolean update(String status, String next, String evidence, RouteRuleRegistry.RouteRule routeRule) {
            boolean changed = statuses.add(status);
            changed |= nextHints.add(next);
            if (evidence != null && !evidence.isBlank() && !evidence.equals(this.evidence)) {
                this.evidence = evidence;
            }
            if (routeRule != null) {
                String policy = routeRule.returnPolicy().name();
                String ruleStatus = routeRule.status().name();
                String note = routeRule.note();
                if (!policy.equals(routeRulePolicy) || !ruleStatus.equals(routeRuleStatus)
                        || !note.equals(routeRuleNote)) {
                    routeRulePolicy = policy;
                    routeRuleStatus = ruleStatus;
                    routeRuleNote = note;
                    changed = true;
                }
            }
            return changed;
        }

        String line() {
            return "[FBB model] route=" + route.label()
                    + " api=" + compact(api, 120)
                    + " owner=" + compact(owner, 100)
                    + " descriptor=" + compact(descriptor, 140)
                    + " return=" + compact(returnType(descriptor), 80)
                    + " status=" + String.join("+", statuses)
                    + " next=" + compact(String.join("+", nextHints), 120)
                    + " syncReturnRisk=" + syncReturnRisk()
                    + " routeRulePolicy=" + routeRulePolicy
                    + " routeRuleStatus=" + routeRuleStatus
                    + " routeRuleNote=" + compact(routeRuleNote, 120)
                    + " evidence=" + evidence;
        }

        private boolean syncReturnRisk() {
            String returnType = returnType(descriptor);
            if ("void".equals(returnType) || "runtime".equals(descriptor)) {
                String lowerApi = api.toLowerCase(java.util.Locale.ROOT);
                return lowerApi.contains("get") || lowerApi.contains("score") || lowerApi.contains("teleport");
            }
            return true;
        }
    }

    private static String returnType(String descriptor) {
        if (descriptor == null || descriptor.isBlank() || "runtime".equals(descriptor)) return "runtime";
        int close = descriptor.lastIndexOf(')');
        if (close < 0 || close + 1 >= descriptor.length()) return "unknown";
        return typeName(descriptor.substring(close + 1));
    }

    private static String typeName(String descriptor) {
        if ("V".equals(descriptor)) return "void";
        if ("Z".equals(descriptor)) return "boolean";
        if ("I".equals(descriptor)) return "int";
        if ("J".equals(descriptor)) return "long";
        if ("F".equals(descriptor)) return "float";
        if ("D".equals(descriptor)) return "double";
        if (descriptor.startsWith("[")) return typeName(descriptor.substring(1)) + "[]";
        if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
            return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
        }
        return descriptor;
    }
}
