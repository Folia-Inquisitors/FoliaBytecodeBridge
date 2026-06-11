package dev.foliabytecodebridge;

import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class BridgeDiagnostics {

    private static final String PACKAGE_NAME = "dev.foliabytecodebridge";
    private static final String[] INTERNAL_PREFIXES = {
            PACKAGE_NAME,
            "java.",
            "jdk.",
            "sun.",
            "net.bytebuddy.",
            "org.bukkit.scheduler.",
            "org.bukkit.plugin."
    };

    private static volatile Logger logger = Logger.getLogger("FoliaBytecodeBridge");
    private static final Set<String> SKIPPED_TRANSFORMS = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Set<String> COMPAT_FAILURES = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final ConcurrentHashMap<String, RepeatState> REPEAT_STATES = new ConcurrentHashMap<>();

    private BridgeDiagnostics() {
    }

    /**
     * Public bootstrap boundary for the Bukkit plugin entrypoint.
     *
     * <p>Most diagnostics remain internal, but startup wiring crosses the
     * plugin/helper classloader boundary when the agent appends its helper jar.</p>
     */
    public static void setLogger(Logger logger) {
        BridgeDiagnostics.logger = logger;
    }

    public static void buildMarker(String phase, String pluginVersion, java.io.File jarFile) {
        info("[FBB build] phase=" + phase
                + " bridgeVersion=" + BridgeBuildInfo.VERSION
                + " pluginVersion=" + pluginVersion
                + " buildId=" + BridgeBuildInfo.BUILD_ID
                + " routeRules=" + RouteRuleRegistry.rules().size()
                + " jar=" + PrivacySanitizer.path(jarFile)
                + " note=use-this-line-to-confirm-the-live-jar-before-reading-route-evidence");
    }

    static boolean debugEnabled() {
        return Boolean.getBoolean("foliabytecodebridge.debug") || BridgeConfig.debug();
    }

    private static boolean debugFileVerbose() {
        // The default research posture is to collect full evidence in debug.log
        // while keeping latest.log readable. consoleVerbose is the separate
        // switch for intentionally mirroring the laboratory notebook to console.
        String property = System.getProperty("foliabytecodebridge.debugFileVerbose");
        return BridgeConfig.debugFile()
                && (property == null ? BridgeConfig.debugFileVerbose() : Boolean.parseBoolean(property));
    }

    static boolean traceTransforms() {
        return debugFileVerbose()
                || debugEnabled()
                || Boolean.getBoolean("foliabytecodebridge.traceTransforms")
                || BridgeConfig.traceTransforms();
    }

    static boolean traceTransformSkips() {
        return debugFileVerbose()
                || Boolean.getBoolean("foliabytecodebridge.traceTransformSkips")
                || BridgeConfig.traceTransformSkips();
    }

    static boolean traceBytecodePaths() {
        return debugFileVerbose()
                || Boolean.getBoolean("foliabytecodebridge.traceBytecodePaths")
                || BridgeConfig.traceBytecodePaths();
    }

    static boolean traceGuardPaths() {
        return debugFileVerbose()
                || debugEnabled()
                || Boolean.getBoolean("foliabytecodebridge.traceGuardPaths")
                || BridgeConfig.traceGuardPaths()
                || traceBytecodePaths()
                || traceUnsafeCalls();
    }

    static boolean traceSchedulerCalls() {
        return debugFileVerbose()
                || debugEnabled()
                || Boolean.getBoolean("foliabytecodebridge.traceSchedulerCalls")
                || BridgeConfig.traceSchedulerCalls();
    }

    static boolean traceTaskFailures() {
        return true;
    }

    static boolean traceUnsafeCalls() {
        return debugFileVerbose()
                || debugEnabled()
                || Boolean.getBoolean("foliabytecodebridge.traceUnsafeCalls")
                || BridgeConfig.traceUnsafeCalls();
    }

    static boolean traceModelReports() {
        return Boolean.getBoolean("foliabytecodebridge.modelReports")
                || BridgeConfig.modelReports()
                || debugEnabled();
    }

    static void transformed(String className, ClassLoader classLoader) {
        if (!traceTransforms()) return;
        if (SKIPPED_TRANSFORMS.remove(className)) return;
        info("[FBB transform] class=" + className + " loader=" + loaderName(classLoader));
    }

    static void rawSchedulerTransformed(String className, ClassLoader classLoader) {
        rawSchedulerTransformed(className, classLoader, -1);
    }

    static void rawSchedulerTransformed(String className, ClassLoader classLoader, int replacementCount) {
        if (!traceTransforms() && !traceSchedulerCalls()) return;
        info("[FBB transform] class=" + className.replace('/', '.')
                + " loader=" + loaderName(classLoader)
                + " path=raw-scheduler result=patched"
                + (replacementCount >= 0 ? " replacements=" + replacementCount : ""));
    }

    static void rawTeleportTransformed(String className, ClassLoader classLoader, int replacementCount) {
        if (!traceTransforms() && !traceUnsafeCalls() && !traceBytecodePaths()) return;
        info("[FBB transform] class=" + className.replace('/', '.')
                + " loader=" + loaderName(classLoader)
                + " path=raw-teleport result=patched"
                + (replacementCount >= 0 ? " replacements=" + replacementCount : ""));
    }

    static void rawCommandTransformed(String className, ClassLoader classLoader, int replacementCount) {
        if (!traceTransforms() && !traceUnsafeCalls() && !traceGuardPaths()) return;
        info("[FBB transform] class=" + className.replace('/', '.')
                + " loader=" + loaderName(classLoader)
                + " path=raw-command-dispatch result=patched"
                + (replacementCount >= 0 ? " replacements=" + replacementCount : ""));
    }

    static void rawMcUtilExecutorTransformed(String className, ClassLoader classLoader, int replacementCount) {
        if (!traceTransforms() && !traceSchedulerCalls() && !traceGuardPaths()) return;
        info("[FBB transform] class=" + className.replace('/', '.')
                + " loader=" + loaderName(classLoader)
                + " path=raw-mcutil-executor result=patched"
                + (replacementCount >= 0 ? " replacements=" + replacementCount : ""));
    }

    static void rawNmsServerExecutorTransformed(String className, ClassLoader classLoader, int replacementCount) {
        if (!traceTransforms() && !traceSchedulerCalls() && !traceGuardPaths()) return;
        info("[FBB transform] class=" + className.replace('/', '.')
                + " loader=" + loaderName(classLoader)
                + " path=raw-nms-server-executor result=patched"
                + (replacementCount >= 0 ? " replacements=" + replacementCount : ""));
    }

    static void rawDirectUnsafeTransformed(String className, ClassLoader classLoader, int replacementCount) {
        if (!traceTransforms() && !traceUnsafeCalls() && !traceBytecodePaths()) return;
        info("[FBB transform] class=" + className.replace('/', '.')
                + " loader=" + loaderName(classLoader)
                + " path=raw-direct-unsafe result=patched"
                + (replacementCount >= 0 ? " replacements=" + replacementCount : ""));
    }

    static void rawLegacyMainThreadTransformed(String className, ClassLoader classLoader, int evidenceCount) {
        info("[FBB transform] class=" + className.replace('/', '.')
                + " loader=" + loaderName(classLoader)
                + " path=raw-legacy-main-thread result=patched"
                + " evidence=" + evidenceCount);
    }

    static void rawRegisteredListenerTransformed(String className, ClassLoader classLoader) {
        info("[FBB transform] class=" + className.replace('/', '.')
                + " loader=" + loaderName(classLoader)
                + " path=raw-registered-listener-boundary result=patched"
                + " replacements=1"
                + " note=server-fired-listener-callbacks-enter-synthetic-boundary");
    }

    static void bytecodePath(String className, String methodName, String methodDescriptor,
                             String sourceOwner, String sourceMethod, String sourceDescriptor,
                             String bridgeMethod, String bridgeDescriptor, RouteFamily routeFamily) {
        if (!traceBytecodePaths()) return;
        architectureDecision("summary", sourceOwner.replace('/', '.') + "#" + sourceMethod,
                sourceOwner.replace('/', '.'), routeFamily, "rewrite", "patched",
                "bridge=ObjectSchedulerBridge#" + bridgeMethod,
                "class=" + className.replace('/', '.')
                        + " method=" + methodName + methodDescriptor);
        architectureDecision("return/sync-risk", sourceOwner.replace('/', '.') + "#" + sourceMethod,
                sourceOwner.replace('/', '.'), routeFamily, "classify-return",
                returnRiskResult(sourceDescriptor), "rewrite-policy=" + returnPolicyName(sourceDescriptor),
                "descriptor=" + sourceDescriptor);
        info("[FBB bytecode-path] class=" + className.replace('/', '.')
                + " in=" + methodName + methodDescriptor
                + " source=" + sourceOwner.replace('/', '.') + "#" + sourceMethod + sourceDescriptor
                + " route=" + routeFamily.label()
                + " bridge=ObjectSchedulerBridge#" + bridgeMethod + bridgeDescriptor);
    }

    static void teleportPath(String className, ClassLoader classLoader, java.security.ProtectionDomain protectionDomain,
                             String methodName, String methodDescriptor,
                             String sourceOwner, String sourceMethod, String sourceDescriptor,
                             RouteFamily routeFamily, String rule, String action, String outcome, String bridge) {
        if (!traceBytecodePaths() && !traceUnsafeCalls() && !debugEnabled() && !traceModelReports()) return;
        String owner = sourceOwner.replace('/', '.');
        String jar = codeSource(protectionDomain);
        if (traceModelReports()) {
            emitModel(RouteModelRegistry.recordBytecode(routeFamily, owner + "#" + sourceMethod,
                    owner, sourceMethod, sourceDescriptor, action, rule, outcome, bridge,
                    className.replace('/', '.'), jar));
        }
        architectureDecision("summary", owner + "#" + sourceMethod,
                owner, routeFamily, action, outcome, bridge,
                "rule=" + rule + " class=" + className.replace('/', '.') + " jar=" + jar);
        architectureDecision("return/sync-risk", owner + "#" + sourceMethod,
                owner, routeFamily, "classify-return", returnRiskResult(sourceDescriptor),
                returnPolicyName(sourceDescriptor), "descriptor=" + sourceDescriptor);
        if (traceBytecodePaths() || traceUnsafeCalls() || debugEnabled()) {
            info("[FBB teleport-path] class=" + className.replace('/', '.')
                    + " loader=" + loaderName(classLoader)
                    + " jar=" + jar
                    + " in=" + methodName + methodDescriptor
                    + " owner=" + owner
                    + " name=" + sourceMethod
                    + " descriptor=" + sourceDescriptor
                    + " route=" + routeFamily.label()
                    + " rule=" + rule
                    + " action=" + action
                    + " outcome=" + outcome
                    + " bridge=" + bridge);
        }
    }

    static void guardPath(String className, ClassLoader classLoader, java.security.ProtectionDomain protectionDomain,
                          String methodName, String methodDescriptor,
                          String sourceOwner, String sourceMethod, String sourceDescriptor,
                          RouteFamily routeFamily, String guard, String action, String reason) {
        if (!traceGuardPaths() && !traceModelReports()) return;
        String owner = sourceOwner.replace('/', '.');
        String jar = codeSource(protectionDomain);
        if (traceModelReports()) {
            emitModel(RouteModelRegistry.recordBytecode(routeFamily, owner + "#" + sourceMethod,
                    owner, sourceMethod, sourceDescriptor, action, guard, reason, "raw-direct",
                    className.replace('/', '.'), jar));
        }
        architectureDecision("policy/blocked", owner + "#" + sourceMethod,
                owner, routeFamily, action, "guarded", reason,
                "guard=" + guard + " class=" + className.replace('/', '.'));
        if (traceGuardPaths()) {
            info("[FBB guard-path] class=" + className.replace('/', '.')
                    + " loader=" + loaderName(classLoader)
                    + " jar=" + jar
                    + " in=" + methodName + methodDescriptor
                    + " owner=" + owner
                    + " name=" + sourceMethod
                    + " descriptor=" + sourceDescriptor
                    + " route=" + routeFamily.label()
                    + " guard=" + guard
                    + " action=" + action
                    + " reason=" + reason);
        }
    }

    static void legacyMainThreadPath(String className, ClassLoader classLoader,
                                     java.security.ProtectionDomain protectionDomain,
                                     String methodName, String methodDescriptor,
                                     String sourceOwner, String sourceMethod, String sourceDescriptor,
                                     RouteFamily routeFamily, String rule, String action, String reason) {
        if (!traceGuardPaths() && !traceModelReports() && !traceTaskFailures()) return;
        String owner = sourceOwner.replace('/', '.');
        String jar = codeSource(protectionDomain);
        if (traceModelReports()) {
            emitModel(RouteModelRegistry.recordBytecode(routeFamily, owner + "#" + sourceMethod,
                    owner, sourceMethod, sourceDescriptor, action, rule, reason,
                    "LegacyMainThreadBridge", className.replace('/', '.'), jar));
        }
        architectureDecision("summary", owner + "#" + sourceMethod,
                owner, routeFamily, action, "legacy-main-thread-compatible",
                reason, "rule=" + rule + " class=" + className.replace('/', '.'));
        info("[FBB legacy-main-thread] class=" + className.replace('/', '.')
                + " loader=" + loaderName(classLoader)
                + " jar=" + jar
                + " in=" + methodName + methodDescriptor
                + " owner=" + owner
                + " name=" + sourceMethod
                + " descriptor=" + sourceDescriptor
                + " route=" + routeFamily.label()
                + " rule=" + rule
                + " action=" + action
                + " reason=" + reason);
    }

    static void legacyMainThreadRuntime(String owner, String methodName, RouteFamily routeFamily,
                                        boolean compatible, boolean tickThread, boolean primaryThread,
                                        String threadName) {
        info("[FBB legacy-main-thread] owner=" + owner
                + " name=" + methodName
                + " descriptor=()Z"
                + " route=" + routeFamily.label()
                + " action=runtime-fallback"
                + " result=" + (compatible ? "compatible" : "legacy-false")
                + " tickThread=" + tickThread
                + " bukkitPrimaryThread=" + primaryThread
                + " thread=\"" + threadName + "\""
                + " note=false-legacy-main-thread-predicate-mapped-to-folia-context-only");
        architectureDecision("summary", owner + "#" + methodName,
                owner, routeFamily, "runtime-fallback",
                compatible ? "compatible" : "legacy-false",
                "legacy-main-thread-fallback",
                "tickThread=" + tickThread + " bukkitPrimaryThread=" + primaryThread);
    }

    static void transformSkipped(String className, ClassLoader classLoader, String reason) {
        SKIPPED_TRANSFORMS.add(className);
        if (!traceTransformSkips()) return;
        architectureDecision("policy/blocked", className, "classloader=" + loaderName(classLoader),
                null, "skip-typed-transform", "skipped", "reason=" + reason,
                "raw-asm-may-still-inspect-exact-routes");
        info("[FBB transform-skip] class=" + className
                + " loader=" + loaderName(classLoader)
                + " reason=" + reason);
    }

    static void optionalDependencyTransformSkipped(String className, ClassLoader classLoader,
                                                   String missingType, String asmRouteSummary) {
        SKIPPED_TRANSFORMS.add(className);
        architectureDecision("policy/blocked", className, "classloader=" + loaderName(classLoader),
                null, "skip-typed-transform", "optional-dependency-missing",
                "no-fake-adapter", "missing=" + missingType + " asm=" + asmRouteSummary);
        warning("[FBB transform-skip] class=" + className
                + " loader=" + loaderName(classLoader)
                + " reason=optional-dependency-missing"
                + " missing=" + missingType
                + " action=skip-typed-transform-no-fake-adapter"
                + " asm=" + asmRouteSummary);
    }

    static void typedRouteCandidateScan(String className, ClassLoader classLoader,
                                        TypedRouteCandidateReporter.CandidateReport report,
                                        String typedTransform) {
        architectureDecision("summary", className, "bytecode-prescan", null,
                report.action(), report.category(), typedTransform,
                "reason=" + report.reason());
        info("[FBB candidate-scan] marker=" + report.marker()
                + " class=" + className
                + " loader=" + loaderName(classLoader)
                + " action=" + report.action()
                + " category=" + report.category()
                + " typedTransform=" + typedTransform
                + " reason=" + report.reason()
                + " note=diagnostic-bytecode-prescan-before-typed-transform");
    }

    static void transformError(String className, Throwable throwable) {
        log(Level.WARNING, "[FBB transform-error] class=" + className + " message="
                + throwable.getMessage(), throwable);
    }

    static void transformShapeSkipped(String className, ClassLoader classLoader, Throwable throwable) {
        SKIPPED_TRANSFORMS.add(className);
        architectureDecision("policy/blocked", className, "classloader=" + loaderName(classLoader),
                null, "skip-typed-transform", "bytebuddy-type-metadata-shape",
                "preserve-raw-routes", "throwable=" + throwable.getClass().getName());
        warning("[FBB transform-skip] class=" + className
                + " loader=" + loaderName(classLoader)
                + " reason=bytebuddy-type-metadata-shape"
                + " action=skip-typed-transform-preserve-raw-routes"
                + " throwable=" + throwable.getClass().getName()
                + ": " + throwable.getMessage()
                + " note=raw-asm-transformers-still-own-exact-bytecode-routes");
    }

    static void helperVisibility(String className, ClassLoader classLoader, String result, String action) {
        helperVisibility(className, classLoader, "UnsafeCallBridge", result, action);
    }

    static void helperVisibility(String className, ClassLoader classLoader, String helper, String result, String action) {
        architectureDecision("helper/visibility", className, "classloader=" + loaderName(classLoader),
                null, action, result, helper, "rewritten-bytecode-helper-resolution");
        info("[FBB helper-visibility] class=" + className.replace('/', '.')
                + " loader=" + loaderName(classLoader)
                + " helper=" + helper
                + " result=" + result
                + " action=" + action
                + " note=rewritten-plugin-bytecode-must-resolve-bridge-runtime");
    }

    static void helperVisibilityFailure(String className, ClassLoader classLoader, Throwable throwable) {
        helperVisibilityFailure(className, classLoader, "UnsafeCallBridge", throwable);
    }

    static void helperVisibilityFailure(String className, ClassLoader classLoader, String helper, Throwable throwable) {
        architectureDecision("helper/visibility", className, "classloader=" + loaderName(classLoader),
                null, "loader-add-url", "failed", helper,
                "throwable=" + throwable.getClass().getName() + ":" + safe(throwable.getMessage()));
        log(Level.WARNING, "[FBB helper-visibility] class=" + className.replace('/', '.')
                + " loader=" + loaderName(classLoader)
                + " helper=" + helper
                + " result=failed"
                + " action=loader-add-url"
                + " throwable=" + throwable.getClass().getName()
                + ": " + throwable.getMessage()
                + " note=rewritten-callsite-may-fail-with-NoClassDefFoundError", throwable);
    }

    static void helperState(String helper, ClassLoader classLoader, String result, String action, String detail) {
        architectureDecision("helper/state", helper, "classloader=" + loaderName(classLoader),
                null, action, result, "bridge-plugin-owner",
                PrivacySanitizer.text(detail));
        info("[FBB helper-state] helper=" + helper
                + " loader=" + loaderName(classLoader)
                + " action=" + action
                + " result=" + result
                + " detail=" + PrivacySanitizer.text(detail)
                + " note=bridge-helper-runtime-owner-state");
    }

    static void compatibilityContext(String action, CompatibilityContext.Frame frame, String detail) {
        if (frame == null) return;
        architectureDecision("summary", frame.source(), "compatibility-context",
                null, action, "context-" + frame.state(), frame.policy(),
                frame.detail() + " " + PrivacySanitizer.text(detail));
        info("[FBB compatibility-context] action=" + action
                + " " + frame.detail()
                + " " + PrivacySanitizer.text(detail)
                + " note=unknown-legacy-execution-context-not-a-route-family");
    }

    static void compatibilityLane(String action, long sequence, String source, String reason, String detail) {
        architectureDecision("route/stay-serialized", source, "single-thread-compatibility-lane",
                null, action, "serialized-unproven", reason,
                "sequence=" + sequence + " active=" + CompatibilityLane.active()
                        + " " + PrivacySanitizer.text(detail));
        info("[FBB compatibility-lane] action=" + safe(action)
                + " sequence=" + sequence
                + " source=" + safe(source)
                + " reason=" + safe(reason)
                + " active=" + CompatibilityLane.active()
                + " " + PrivacySanitizer.text(detail)
                + " note=serialized-compatibility-model-not-a-folia-owner-thread");
    }

    static void compatibilityLaneFailure(long sequence, String source, String reason, Throwable throwable) {
        architectureDecision("policy/blocked", source, "single-thread-compatibility-lane",
                null, "failure", "preserved", reason,
                "sequence=" + sequence + " throwable=" + throwable.getClass().getName());
        log(Level.WARNING, "[FBB compatibility-lane] action=failure"
                + " sequence=" + sequence
                + " source=" + safe(source)
                + " reason=" + safe(reason)
                + " throwable=" + throwable.getClass().getName()
                + ": " + safe(throwable.getMessage())
                + " note=lane-preserved-failure-evidence-no-silent-bypass", throwable);
    }

    static void eventListener(String eventName, String listenerOwner, String phase,
                              String effect, boolean cancelled, CompatibilityContext.Frame frame) {
        info("[FBB event-listener] event=" + safe(eventName)
                + " listener=" + safe(listenerOwner)
                + " phase=" + safe(phase)
                + " effect=" + safe(effect)
                + " cancelled=" + cancelled
                + " laneActive=" + CompatibilityLane.active()
                + " laneSequence=" + CompatibilityLane.currentSequence()
                + " context=" + (frame == null ? "none" : frame.kind())
                + (frame == null ? "" : " " + frame.detail())
                + " note=synthetic-event-path-observation");
    }

    static void syntheticEventDispatch(String action, String eventName, int listenerCount, String detail) {
        info("[FBB synthetic-event-dispatch] action=" + safe(action)
                + " event=" + safe(eventName)
                + " listeners=" + listenerCount
                + " laneActive=" + CompatibilityLane.active()
                + " laneSequence=" + CompatibilityLane.currentSequence()
                + " " + PrivacySanitizer.text(detail)
                + " note=custom-sync-event-compatibility-model");
    }

    static void syntheticListenerBoundary(String action, String eventName, String listenerOwner,
                                          int listenerCount, RouteFamily routeFamily,
                                          String ownerMethod, String detail) {
        String routeLabel = routeFamily == null ? "none" : routeFamily.label();
        String familyLabel = routeFamily == null ? "UNKNOWN" : routeFamily.label();
        architectureDecision("summary", eventName, "registered-listener-boundary",
                routeFamily, action, fieldValue(detail, "result", "observed"),
                fieldValue(detail, "next", "synthetic-listener-boundary"),
                "listener=" + listenerOwner
                        + " listeners=" + listenerCount
                        + " ownerMethod=" + ownerMethod
                        + " " + PrivacySanitizer.text(detail));
        info("[FBB synthetic-listener-boundary]"
                + " marker=FBB_SYNTHETIC_LISTENER_BOUNDARY_V1"
                + " action=" + safe(action)
                + " event=" + safe(eventName)
                + " listener=" + safe(listenerOwner)
                + " listeners=" + listenerCount
                + " route=" + routeLabel
                + " routeFamily=" + familyLabel
                + " ownerMethod=" + safe(ownerMethod)
                + " laneActive=" + CompatibilityLane.active()
                + " laneSequence=" + CompatibilityLane.currentSequence()
                + " " + PrivacySanitizer.text(detail)
                + " note=server-fired-listener-callback-synthetic-boundary");
    }

    static void syntheticEventPathState(String action, SyntheticEventPathState state, String detail) {
        if (state == null) return;
        architectureDecision("owner/extract", state.eventName(), state.ownerType(),
                state.routeFamily(), action, state.ownerStatus(), state.ownerMethod(),
                "listeners=" + state.listenerCount()
                        + " shared=" + state.shared()
                        + " laneStatus=" + state.laneStatus()
                        + " routeExit=" + state.routeExit()
                        + " missReason=" + state.missReason());
        if (state.routeExit()) {
            architectureDecision("route/exit", state.eventName(), state.ownerType(),
                    state.routeFamily(), "exit-synthetic-lane", "owner-found",
                    state.ownerMethod(), "laneStatus=" + state.laneStatus());
        } else if ("no-owner-contract".equals(state.ownerStatus())) {
            architectureDecision("route/stay-serialized", state.eventName(), "synthetic-event-path",
                    null, "stay-serialized", "serialized-unproven", "no-owner-contract",
                    "missReason=" + state.missReason());
        }
        info("[FBB synthetic-event-state] action=" + safe(action)
                + " " + state.detail()
                + " laneActive=" + CompatibilityLane.active()
                + " laneSequence=" + CompatibilityLane.currentSequence()
                + " " + PrivacySanitizer.text(detail)
                + " note=synthetic-wrapper-state-owner-route-or-serialized-lane");
    }

    static void syntheticEventOwnerMiss(String eventName, int listenerCount,
                                        SyntheticEventOwnerExtractor.OwnerScan scan, String detail) {
        String missSummary = scan == null ? "scan=null" : scan.missSummary();
        architectureDecision("owner/miss", eventName, "synthetic-event-owner-scan",
                null, "extract-owner", "no-owner-contract", "serialized-unproven",
                "listeners=" + listenerCount + " missReason=" + missSummary);
        architectureDecision("route/stay-serialized", eventName, "single-thread-compatibility-lane",
                null, "unknown-unproven-event-path", "serialized-unproven", "no-owner-contract",
                PrivacySanitizer.text(detail));
        info("[FBB synthetic-owner-miss] event=" + safe(eventName)
                + " listeners=" + listenerCount
                + " route=none"
                + " routeFamily=UNKNOWN"
                + " ownerStatus=no-owner-contract"
                + " marker=FBB_SYNTHETIC_OWNER_MISS_SERIALIZED_V1"
                + " lane=single-thread-compatibility"
                + " missReason=" + safe(missSummary)
                + " laneActive=" + CompatibilityLane.active()
                + " laneSequence=" + CompatibilityLane.currentSequence()
                + " " + PrivacySanitizer.text(detail)
                + " note=unknown-or-unproven-shared-event-serialized-no-owner-contract");
    }

    static void syntheticMultiRegionDetect(String eventName, int listenerCount,
                                           SyntheticEventOwnerExtractor.OwnerScan scan,
                                           String detail) {
        if (scan == null || !scan.hasMultiRegionObservation()) return;
        for (SyntheticEventOwnerExtractor.MultiRegionObservation observation : scan.multiRegionObservations()) {
            architectureDecision("owner/extract", eventName, "multi-region-owner-set",
                    RouteFamily.C_REGION_BLOCK, "detect", "multi-owner", "stay-serialized",
                    observation.detail());
            architectureDecision("return/sync-risk", eventName, "multi-region-owner-set",
                    RouteFamily.C_REGION_BLOCK, "classify-return", "multi-region-sync-risk",
                    "split-or-contract-required", observation.detail());
            info("[FBB synthetic-multi-region] phase=detect"
                    + " event=" + safe(eventName)
                    + " listeners=" + listenerCount
                    + " " + observation.detail()
                    + " marker=FBB_SYNTHETIC_MULTI_REGION_DETECTED_V1"
                    + " lane=single-thread-compatibility"
                    + " result=observed-not-promoted"
                    + " " + PrivacySanitizer.text(detail)
                    + " note=phase1-owner-set-detection-before-optional-read-split");
        }
    }

    static void syntheticMultiRegionReadSplit(String eventName, int listenerCount,
                                              SyntheticEventOwnerExtractor.MultiRegionObservation observation,
                                              String detail) {
        if (observation == null) return;
        architectureDecision("summary", eventName, "multi-region-owner-set",
                RouteFamily.G_WORLD_SCAN_SPLIT, "split-read", "aggregated-read-model",
                "read-only-owner-split", observation.detail() + " " + PrivacySanitizer.text(detail));
        info("[FBB synthetic-multi-region] phase=split-read"
                + " event=" + safe(eventName)
                + " listeners=" + listenerCount
                + " " + observation.detail()
                + " marker=FBB_SYNTHETIC_READ_SPLIT_V1"
                + " lane=single-thread-compatibility"
                + " " + PrivacySanitizer.text(detail)
                + " note=phase2-read-only-owner-split-no-listener-replay");
    }

    static void syntheticMultiRegionReadSplitFailure(String eventName, int listenerCount,
                                                     SyntheticEventOwnerExtractor.MultiRegionObservation observation,
                                                     Throwable throwable) {
        if (observation == null) return;
        architectureDecision("policy/blocked", eventName, "multi-region-owner-set",
                RouteFamily.G_WORLD_SCAN_SPLIT, "split-read", "failed",
                "preserve-evidence", "throwable=" + throwable.getClass().getName());
        log(Level.WARNING, "[FBB synthetic-multi-region] phase=split-read"
                + " event=" + safe(eventName)
                + " listeners=" + listenerCount
                + " " + observation.detail()
                + " lane=single-thread-compatibility"
                + " result=failed"
                + " marker=FBB_SYNTHETIC_READ_SPLIT_FAILED_V1"
                + " throwable=" + throwable.getClass().getName()
                + ": " + safe(throwable.getMessage())
                + " note=phase2-read-only-owner-split-failed-preserve-evidence", throwable);
    }

    static void syntheticMultiRegionMutationPlan(String eventName, int listenerCount,
                                                 SyntheticEventOwnerExtractor.MultiRegionObservation observation,
                                                 String detail) {
        if (observation == null) return;
        architectureDecision("summary", eventName, "multi-region-owner-set",
                RouteFamily.C_REGION_BLOCK, "plan-mutation", "planned-not-executed",
                "prepare-owner-apply-aggregate-verify", observation.detail() + " " + PrivacySanitizer.text(detail));
        info("[FBB synthetic-multi-region] phase=plan-mutation"
                + " event=" + safe(eventName)
                + " listeners=" + listenerCount
                + " " + observation.detail()
                + " marker=FBB_SYNTHETIC_MUTATION_PLAN_V1"
                + " lane=single-thread-compatibility"
                + " " + PrivacySanitizer.text(detail)
                + " note=phase3-mutation-plan-only-no-region-freeze-no-listener-replay");
    }

    static void syntheticMultiRegionMutationContract(String eventName, int listenerCount,
                                                     SyntheticEventOwnerExtractor.MultiRegionObservation observation,
                                                     String detail) {
        if (observation == null) return;
        architectureDecision("summary", eventName, "multi-region-owner-set",
                RouteFamily.C_REGION_BLOCK, "contract-mutation", "ready-not-executed",
                "exact-contract-required", observation.detail() + " " + PrivacySanitizer.text(detail));
        info("[FBB synthetic-multi-region] phase=contract-mutation"
                + " event=" + safe(eventName)
                + " listeners=" + listenerCount
                + " " + observation.detail()
                + " marker=FBB_SYNTHETIC_MUTATION_CONTRACT_V1"
                + " lane=single-thread-compatibility"
                + " " + PrivacySanitizer.text(detail)
                + " note=phase4-contract-readiness-only-no-region-freeze-no-listener-replay");
    }

    static void syntheticMultiRegionMutationExecute(String eventName, int listenerCount,
                                                    SyntheticEventOwnerExtractor.MultiRegionObservation observation,
                                                    String detail) {
        if (observation == null) return;
        architectureDecision("summary", eventName, "multi-region-owner-set",
                RouteFamily.C_REGION_BLOCK, "execute-mutation",
                detail.contains("result=completed") ? "completed" : fieldValue(detail, "result", "observed"),
                "guarded-exact-contract-only", observation.detail() + " " + PrivacySanitizer.text(detail));
        info("[FBB synthetic-multi-region] phase=execute-mutation"
                + " event=" + safe(eventName)
                + " listeners=" + listenerCount
                + " " + observation.detail()
                + " lane=single-thread-compatibility"
                + " " + PrivacySanitizer.text(detail)
                + " note=phase5b-guarded-executor-exact-contract-only");
    }

    static void syntheticMultiRegionMutationExecuteFailure(String eventName, int listenerCount,
                                                           SyntheticEventOwnerExtractor.MultiRegionObservation observation,
                                                           Throwable throwable) {
        if (observation == null) return;
        architectureDecision("policy/blocked", eventName, "multi-region-owner-set",
                RouteFamily.C_REGION_BLOCK, "execute-mutation", "failed",
                "preserve-evidence", "throwable=" + throwable.getClass().getName());
        log(Level.WARNING, "[FBB synthetic-multi-region] phase=execute-mutation"
                + " event=" + safe(eventName)
                + " listeners=" + listenerCount
                + " " + observation.detail()
                + " lane=single-thread-compatibility"
                + " result=failed"
                + " marker=FBB_SYNTHETIC_MUTATION_EXECUTOR_FAILED_V1"
                + " throwable=" + throwable.getClass().getName()
                + ": " + safe(throwable.getMessage())
                + " note=phase5b-executor-failed-preserve-evidence", throwable);
    }

    static void syntheticEventRouteExit(String action, String eventName, RouteFamily routeFamily,
                                        String ownerMethod, String ownerType,
                                        int listenerCount, String detail) {
        architectureDecision("route/exit", eventName, ownerType, routeFamily,
                action, "owner-found", syntheticRouteExitNext(routeFamily),
                "ownerMethod=" + ownerMethod + " listeners=" + listenerCount
                        + " " + PrivacySanitizer.text(detail));
        architectureDecision("promotion/evidence", eventName, ownerType, routeFamily,
                "known-owner-route-inside-synthetic-event", "candidate",
                syntheticRouteExitNext(routeFamily),
                "ownerMethod=" + ownerMethod + " listeners=" + listenerCount
                        + " " + PrivacySanitizer.text(detail));
        info("[FBB synthetic-event-route-exit] action=" + safe(action)
                + " event=" + safe(eventName)
                + " route=" + routeFamily.label()
                + " family=" + syntheticRouteExitFamily(routeFamily)
                + " next=" + syntheticRouteExitNext(routeFamily)
                + " ownerMethod=" + safe(ownerMethod)
                + " ownerType=" + safe(ownerType)
                + " listeners=" + listenerCount
                + " laneActive=" + CompatibilityLane.active()
                + " laneSequence=" + CompatibilityLane.currentSequence()
                + " " + PrivacySanitizer.text(detail)
                + " note=synthetic-event-known-owner-route-exit");
    }

    static void syntheticEventRouteExitFailure(String eventName, RouteFamily routeFamily,
                                               String ownerMethod, String ownerType,
                                               Throwable throwable) {
        architectureDecision("policy/blocked", eventName, ownerType, routeFamily,
                "route-exit", "failed", syntheticRouteExitNext(routeFamily),
                "ownerMethod=" + ownerMethod + " throwable=" + throwable.getClass().getName());
        log(Level.WARNING, "[FBB synthetic-event-route-exit] action=failure"
                + " event=" + safe(eventName)
                + " route=" + routeFamily.label()
                + " family=" + syntheticRouteExitFamily(routeFamily)
                + " next=" + syntheticRouteExitNext(routeFamily)
                + " ownerMethod=" + safe(ownerMethod)
                + " ownerType=" + safe(ownerType)
                + " throwable=" + throwable.getClass().getName()
                + ": " + safe(throwable.getMessage())
                + " note=route-exit-failed-preserve-evidence", throwable);
    }

    static void syntheticEventRouteExitAbandoned(String eventName, RouteFamily routeFamily,
                                                 String ownerMethod, String ownerType,
                                                 Throwable throwable) {
        architectureDecision("policy/blocked", eventName, ownerType, routeFamily,
                "route-exit", "abandoned-server-stopping", syntheticRouteExitNext(routeFamily),
                "ownerMethod=" + ownerMethod + " throwable=" + throwable.getClass().getName());
        warning("[FBB synthetic-event-route-exit] action=abandon"
                + " event=" + safe(eventName)
                + " route=" + routeFamily.label()
                + " family=" + syntheticRouteExitFamily(routeFamily)
                + " next=" + syntheticRouteExitNext(routeFamily)
                + " ownerMethod=" + safe(ownerMethod)
                + " ownerType=" + safe(ownerType)
                + " classification=server-stopping"
                + " throwable=" + throwable.getClass().getName()
                + ": " + safe(throwable.getMessage())
                + " note=route-exit-abandoned-during-lifecycle-stop");
    }

    static void syntheticEventListenerRouteExit(String eventName, String listenerOwner,
                                                RouteFamily routeFamily, String ownerMethod,
                                                String ownerType, String path, String detail) {
        architectureDecision("route/exit", eventName + " listener=" + listenerOwner,
                ownerType, routeFamily, "listener-route-exit", "owner-found",
                syntheticRouteExitNext(routeFamily),
                "ownerMethod=" + ownerMethod + " path=" + path + " " + PrivacySanitizer.text(detail));
        architectureDecision("promotion/evidence", eventName + " listener=" + listenerOwner,
                ownerType, routeFamily, "known-route-inside-serialized-listener", "candidate",
                syntheticRouteExitNext(routeFamily),
                "ownerMethod=" + ownerMethod + " path=" + path + " " + PrivacySanitizer.text(detail));
        info("[FBB synthetic-listener-route-exit] event=" + safe(eventName)
                + " listener=" + safe(listenerOwner)
                + " route=" + routeFamily.label()
                + " family=" + syntheticRouteExitFamily(routeFamily)
                + " next=" + syntheticRouteExitNext(routeFamily)
                + " ownerMethod=" + safe(ownerMethod)
                + " ownerType=" + safe(ownerType)
                + " path=" + safe(path)
                + " laneActive=" + CompatibilityLane.active()
                + " laneSequence=" + CompatibilityLane.currentSequence()
                + " " + PrivacySanitizer.text(detail)
                + " note=listener-dispatched-inside-known-owner-route-exit");
    }

    static void syntheticConcurrency(String action, String eventName, String listenerOwner,
                                     RouteFamily routeFamily, String ownerMethod,
                                     String path, String detail) {
        String route = routeFamily == null ? "none" : routeFamily.label();
        architectureDecision("policy/blocked", eventName + " listener=" + listenerOwner,
                "synthetic-concurrency", routeFamily, action, "compatibility-sensitive",
                "no-route-promotion", "ownerMethod=" + ownerMethod + " path=" + path
                        + " " + PrivacySanitizer.text(detail));
        info("[FBB synthetic-concurrency] phase=5A"
                + " action=" + safe(action)
                + " event=" + safe(eventName)
                + " listener=" + safe(listenerOwner)
                + " route=" + route
                + (routeFamily == null ? " routeFamily=UNKNOWN" : "")
                + " ownerMethod=" + safe(ownerMethod)
                + " path=" + safe(path)
                + " laneActive=" + CompatibilityLane.active()
                + " laneSequence=" + CompatibilityLane.currentSequence()
                + " " + PrivacySanitizer.text(detail)
                + " note=listener-reentry-detection-only-no-route-promotion");
    }

    private static String syntheticRouteExitFamily(RouteFamily routeFamily) {
        if (routeFamily == RouteFamily.A_ENTITY) return "entity";
        if (routeFamily == RouteFamily.C_REGION_BLOCK) return "region";
        if (routeFamily == RouteFamily.B_REGION_LOCATION) return "region";
        return "owner";
    }

    private static String syntheticRouteExitNext(RouteFamily routeFamily) {
        if (routeFamily == RouteFamily.A_ENTITY) return "listener-entity-owner-exit";
        if (routeFamily == RouteFamily.C_REGION_BLOCK) return "listener-block-owner-exit";
        if (routeFamily == RouteFamily.B_REGION_LOCATION) return "listener-location-owner-exit";
        return "listener-owner-route-exit";
    }

    static void syntheticEventDispatchFailure(String eventName, String listenerOwner, Throwable throwable) {
        SyntheticListenerFailureClassifier.Result classification =
                SyntheticListenerFailureClassifier.classify(throwable);
        architectureDecision("policy/blocked", eventName + " listener=" + listenerOwner,
                "synthetic-event-dispatch", classification.routeFamily(),
                "listener-failure", "preserved-failure", classification.nextAction(),
                "family=" + classification.family() + " evidence=" + classification.evidence());
        log(Level.WARNING, "[FBB synthetic-event-dispatch] action=listener-failure"
                + " event=" + safe(eventName)
                + " listener=" + safe(listenerOwner)
                + " route=" + classification.routeLabel()
                + " family=" + classification.family()
                + " next=" + classification.nextAction()
                + " evidence=" + classification.evidence()
                + " laneActive=" + CompatibilityLane.active()
                + " laneSequence=" + CompatibilityLane.currentSequence()
                + " throwable=" + throwable.getClass().getName()
                + ": " + safe(throwable.getMessage())
                + " note=listener-failure-preserved-during-synthetic-dispatch-owner-exit-needed", throwable);
    }

    static void promotionCandidate(String sourceApi, RouteFamily routeFamily,
                                   String family, String nextAction, String detail,
                                   CompatibilityContext.Frame frame) {
        if (frame == null) return;
        architectureDecision("promotion/evidence", sourceApi,
                "compatibility-context=" + frame.kind(), routeFamily,
                "observed-known-route-inside-unknown-context", "candidate",
                nextAction, "family=" + family + " detail=" + detail + " " + frame.detail());
        info("[FBB promotion-candidate] source=" + frame.kind()
                + " state=" + frame.state()
                + " shared=" + frame.shared()
                + " api=" + safe(sourceApi)
                + " route=" + routeFamily.label()
                + " family=" + safe(family)
                + " next=" + safe(nextAction)
                + " status=observed-not-promoted"
                + " detail=" + safe(detail)
                + " note=known-route-exit-seen-inside-compatibility-context");
    }

    /**
     * Public plugin-entrypoint boundary for the root logger compatibility handler.
     *
     * <p>The handler is an anonymous class owned by the Bukkit plugin classloader,
     * while diagnostics may resolve from the helper-runtime loader. Keep this
     * boundary public so failure recording never becomes the failure itself.</p>
     */
    public static void compatibilityFailure(Throwable throwable) {
        if (throwable == null) return;
        NmsCompatModel.fromThrowable(throwable).ifPresent(report -> {
            String key = report.failure().throwable() + "|"
                    + report.failure().owner() + "|"
                    + report.failure().name() + "|"
                    + report.frame().className() + "|"
                    + report.frame().methodName();
            if (!COMPAT_FAILURES.add(key)) return;
            warning(report.toEvidenceLine()
                    + " source=runtime-log-handler"
                    + " note=diagnostic-only-no-route-rewrite");
        });
    }

    static void nmsCompatSyntheticMember(String className, String memberName, String descriptor,
                                         String result, String action, String hook) {
        architectureDecision("summary", className.replace('/', '.') + "#" + memberName,
                className.replace('/', '.'), null, action, result,
                NmsCompatFamily.SERVER_TICK_COUNTER.label(),
                "descriptor=" + descriptor + " hook=" + hook);
        warning("[FBB nms-compat] category=" + NmsCompatFamily.SERVER_TICK_COUNTER.label()
                + " model=SERVER_TICK_COUNTER"
                + " parentCategory=" + NmsCompatFamily.NMS_VERSION_COMPAT.label()
                + " owner=" + className.replace('/', '.')
                + " member=" + memberName
                + " descriptor=" + descriptor
                + " result=" + result
                + " action=" + action
                + " hook=" + hook
                + " route=none"
                + " note=server-internal-shape-adapter-not-folia-route");
    }

    static void nmsCompatExecutorPath(String className, ClassLoader classLoader,
                                      java.security.ProtectionDomain protectionDomain,
                                      String methodName, String methodDescriptor,
                                      String sourceOwner, String sourceMethod, String sourceDescriptor,
                                      String model, String action, String reason) {
        if (!traceGuardPaths() && !traceModelReports() && !traceTaskFailures()) return;
        String owner = sourceOwner.replace('/', '.');
        String jar = codeSource(protectionDomain);
        architectureDecision("summary", owner + "#" + sourceMethod, owner, null,
                action, "nms-executor-context", NmsCompatFamily.NMS_EXECUTOR_CONTEXT.label(),
                "model=" + model + " class=" + className.replace('/', '.'));
        info("[FBB nms-compat] category=" + NmsCompatFamily.NMS_EXECUTOR_CONTEXT.label()
                + " model=" + model
                + " class=" + className.replace('/', '.')
                + " loader=" + loaderName(classLoader)
                + " jar=" + jar
                + " in=" + methodName + methodDescriptor
                + " owner=" + owner
                + " name=" + sourceMethod
                + " descriptor=" + sourceDescriptor
                + " route=none"
                + " previousRoute=S_GLOBAL"
                + " action=" + action
                + " result=rewritten-through-current-executor-shim"
                + " reason=" + reason
                + " next=watch-runtime-for-owner-context-missing-before-promoting-route");
    }

    static void nmsCompatExecutorFailure(String sourceApi, String scheduledFrom, Throwable throwable) {
        NmsCompatModel.executorContextFromThrowable(sourceApi, scheduledFrom, throwable).ifPresent(report -> {
            String key = "executor-context|" + sourceApi + "|" + report.owner()
                    + "|" + throwable.getClass().getName();
            if (!COMPAT_FAILURES.add(key)) return;
            architectureDecision("owner/miss", sourceApi, report.owner(), null,
                    "task-failure", "owner-context-missing",
                    NmsCompatFamily.NMS_EXECUTOR_CONTEXT.label(),
                    "scheduledFrom=" + scheduledFrom + " throwable=" + throwable.getClass().getName());
            warning(report.toEvidenceLine());
        });
    }

    static void nmsContext(String action, NmsCompatibilityState state, String detail) {
        if (state == null) return;
        architectureDecision("summary", state.sourceApi(), "nms-compatibility-context",
                null, action, state.result(), state.family().label(),
                state.detail() + " " + PrivacySanitizer.text(detail));
        info("[FBB nms-context] action=" + safe(action)
                + " " + state.detail()
                + " laneActive=" + NmsCompatibilityLane.active()
                + " laneSequence=" + NmsCompatibilityLane.currentSequence()
                + " " + PrivacySanitizer.text(detail)
                + " note=server-internal-compatibility-context-not-bukkit-route-family");
    }

    static void nmsLane(String action, long sequence, String source, String reason,
                        NmsCompatibilityState state, String detail) {
        architectureDecision("route/stay-serialized", source, "nms-compatibility-lane",
                null, action, state == null ? "serialized-unproven" : state.result(),
                reason, "sequence=" + sequence + " active=" + NmsCompatibilityLane.active()
                        + " family=" + (state == null ? "UNKNOWN" : state.family().label())
                        + " " + PrivacySanitizer.text(detail));
        info("[FBB nms-lane] action=" + safe(action)
                + " sequence=" + sequence
                + " source=" + safe(source)
                + " reason=" + safe(reason)
                + " family=" + (state == null ? "UNKNOWN" : state.family().label())
                + " active=" + NmsCompatibilityLane.active()
                + " " + PrivacySanitizer.text(detail)
                + " note=owner-preserving-serialized-nms-compatibility-boundary");
    }

    static void nmsLaneFailure(long sequence, String source, String reason,
                               NmsCompatibilityState state, Throwable throwable) {
        architectureDecision("policy/blocked", source, "nms-compatibility-lane",
                null, "failure", "failure-preserved", reason,
                "sequence=" + sequence + " family="
                        + (state == null ? "UNKNOWN" : state.family().label())
                        + " throwable=" + throwable.getClass().getName());
        log(Level.WARNING, "[FBB nms-lane] action=failure"
                + " sequence=" + sequence
                + " source=" + safe(source)
                + " reason=" + safe(reason)
                + " family=" + (state == null ? "UNKNOWN" : state.family().label())
                + " throwable=" + throwable.getClass().getName()
                + ": " + safe(throwable.getMessage())
                + " note=nms-compatibility-lane-preserved-failure", throwable);
    }

    static void nmsOwnerExtract(String action, NmsCompatibilityState state,
                                NmsOwnerExtractor.Scan scan, String detail) {
        if (state == null || scan == null) return;
        String owner = scan.found() ? scan.owner().detail() : "none";
        architectureDecision("owner/extract", state.sourceApi(), owner,
                null, action, scan.found() ? "owner-found" : "no-owner-contract",
                state.family().label(), PrivacySanitizer.text(detail));
        info("[FBB nms-owner-extract] action=" + safe(action)
                + " api=" + safe(state.sourceApi())
                + " family=" + state.family().label()
                + " model=" + safe(state.model())
                + " ownerFound=" + scan.found()
                + (scan.found()
                ? " ownerKind=" + safe(scan.owner().kind()) + " ownerDetail=" + safe(scan.owner().detail())
                : " missReason=" + safe(scan.missReason()))
                + " clueTrail=" + safe(scan.clueTrail())
                + " " + PrivacySanitizer.text(detail)
                + " note=nms-owner-clue-scan");
    }

    static void nmsOwnerMiss(NmsCompatibilityState state, String reason) {
        if (state == null) return;
        architectureDecision("owner/miss", state.sourceApi(), "nms-owner-scan",
                null, "extract-owner", "no-owner-contract", state.family().label(),
                "reason=" + reason);
        info("[FBB nms-owner-miss] api=" + safe(state.sourceApi())
                + " family=" + state.family().label()
                + " model=" + safe(state.model())
                + " ownerStatus=no-owner-contract"
                + " laneStatus=" + safe(state.laneStatus())
                + " missReason=" + safe(reason)
                + " result=stay-serialized"
                + " note=unknown-or-unproven-nms-path-serialized-no-owner-contract");
    }

    static void nmsRouteExit(NmsCompatibilityState state, NmsOwnerExtractor.Owner owner,
                             String route, String detail) {
        if (state == null || owner == null) return;
        architectureDecision("route/exit", state.sourceApi(), owner.detail(),
                null, "nms-owner-route-exit", "owner-context-found",
                state.family().label(), "route=" + route + " " + PrivacySanitizer.text(detail));
        info("[FBB nms-route-exit] api=" + safe(state.sourceApi())
                + " family=" + state.family().label()
                + " model=" + safe(state.model())
                + " ownerKind=" + safe(owner.kind())
                + " ownerDetail=" + safe(owner.detail())
                + " route=" + safe(route)
                + " result=owner-context-found"
                + " " + PrivacySanitizer.text(detail)
                + " note=known-nms-owner-clue-exits-to-folia-owner-before-running");
    }

    static void agentClasspath(String detail) {
        if (!debugEnabled()) return;
        info("[FBB agent-classpath] " + PrivacySanitizer.text(detail));
    }

    static void attach(String detail) {
        info("[FBB attach] " + PrivacySanitizer.text(detail));
    }

    static void attachWarning(String detail) {
        warning("[FBB attach-warning] " + PrivacySanitizer.text(detail));
    }

    static void metadataTransform(String className, ClassLoader classLoader,
                                  java.security.ProtectionDomain protectionDomain, String result) {
        architectureDecision("summary", className.replace('/', '.'),
                "metadata-load-gate", RouteFamily.S_GLOBAL,
                "metadata-transform", result, "allow-plugin-load-for-transformer",
                "mode=" + BridgeConfig.metadataOverlay() + " jar=" + codeSource(protectionDomain));
        info("[FBB metadata] class=" + className.replace('/', '.')
                + " loader=" + loaderName(classLoader)
                + " jar=" + codeSource(protectionDomain)
                + " route=S_GLOBAL"
                + " action=metadata-transform"
                + " mode=" + BridgeConfig.metadataOverlay()
                + " result=" + result);
    }

    static void metadataJar(String pluginName, String jar, String action, String mode, String result) {
        architectureDecision("summary", pluginName, "metadata-load-gate", RouteFamily.S_GLOBAL,
                action, result, "not-thread-safety-proof", "mode=" + mode + " jar=" + jar);
        warning("[FBB metadata] plugin=" + pluginName
                + " jar=" + PrivacySanitizer.text(jar)
                + " route=S_GLOBAL"
                + " action=" + action
                + " mode=" + mode
                + " result=" + result
                + " note=experimental-load-gate-only-not-thread-safety");
    }

    static void schedulerCall(String sourceApi, String policy, Plugin plugin) {
        schedulerCall(sourceApi, RouteFamily.forSchedulerPolicy(policy), policy, plugin);
    }

    static void schedulerCall(String sourceApi, RouteFamily routeFamily, String policy, Plugin plugin) {
        if (!traceSchedulerCalls() && !traceModelReports()) return;
        String caller = captureCaller();
        architectureDecision("summary", sourceApi, "scheduler", routeFamily,
                "runtime-scheduler-call", "routed", "policy=" + policy,
                "plugin=" + pluginName(plugin) + " caller=" + caller);
        if (traceModelReports()) {
            emitModel(RouteModelRegistry.recordRuntime(sourceApi, routeFamily, "scheduler", policy,
                    "policy=" + policy, caller, null));
        }
        if (traceSchedulerCalls()) {
            String pluginName = pluginName(plugin);
            String line = "[FBB scheduler] api=" + sourceApi
                    + " route=" + routeFamily.label()
                    + " policy=" + policy
                    + " plugin=" + pluginName
                    + " caller=" + caller;
            logRepeated("scheduler", sourceApi + "|" + routeFamily.label() + "|" + policy
                    + "|" + pluginName + "|" + caller, line);
        }
    }

    static void schedulerObjectCall(String sourceApi, String policy, Object plugin) {
        schedulerObjectCall(sourceApi, RouteFamily.forSchedulerPolicy(policy), policy, plugin);
    }

    static void schedulerObjectCall(String sourceApi, RouteFamily routeFamily, String policy, Object plugin) {
        if (!traceSchedulerCalls() && !traceModelReports()) return;
        String caller = captureCaller();
        architectureDecision("summary", sourceApi, "scheduler", routeFamily,
                "runtime-scheduler-call", "routed", "policy=" + policy,
                "plugin=" + pluginName(plugin) + " caller=" + caller);
        if (traceModelReports()) {
            emitModel(RouteModelRegistry.recordRuntime(sourceApi, routeFamily, "scheduler", policy,
                    "policy=" + policy, caller, null));
        }
        if (traceSchedulerCalls()) {
            String pluginName = pluginName(plugin);
            String line = "[FBB scheduler] api=" + sourceApi
                    + " route=" + routeFamily.label()
                    + " policy=" + policy
                    + " plugin=" + pluginName
                    + " caller=" + caller;
            logRepeated("scheduler", sourceApi + "|" + routeFamily.label() + "|" + policy
                    + "|" + pluginName + "|" + caller, line);
        }
    }

    static void unsafeCall(String sourceApi, String family, String nextAction, String detail) {
        unsafeCall(sourceApi, RouteFamily.forUnsafeCall(family, nextAction), family, nextAction, detail);
    }

    static void unsafeCall(String sourceApi, RouteFamily routeFamily, String family, String nextAction, String detail) {
        if (!traceUnsafeCalls() && !traceModelReports()) return;
        String caller = captureCaller();
        architectureDecision("summary", sourceApi, family, routeFamily,
                "runtime-unsafe-call", runtimeResultFromDetail(detail),
                nextAction, "caller=" + caller + " " + detail);
        architectureDecision("return/sync-risk", sourceApi, family, routeFamily,
                "classify-return", runtimeReturnRiskResult(detail, sourceApi),
                policyFromDetail(detail), "next=" + nextAction + " caller=" + caller);
        if (traceModelReports()) {
            emitModel(RouteModelRegistry.recordRuntime(sourceApi, routeFamily, family, nextAction,
                    detail, caller, null));
        }
        if (traceUnsafeCalls()) {
            String line = "[FBB unsafe-call] api=" + sourceApi
                    + " route=" + routeFamily.label()
                    + " family=" + family
                    + " next=" + nextAction
                    + " detail=" + detail
                    + " caller=" + caller;
            // Detail often contains coordinates, so it stays in the full line
            // but not the repeat key. That keeps hot proven routes readable
            // while still preserving representative examples and failures.
            logRepeated("unsafe-call", sourceApi + "|" + routeFamily.label() + "|" + family
                    + "|" + nextAction + "|" + caller, line);
        }
    }

    static void unsafeFailure(String sourceApi, String family, String nextAction, Throwable throwable) {
        unsafeFailure(sourceApi, RouteFamily.forUnsafeCall(family, nextAction), family, nextAction, throwable);
    }

    static void unsafeFailure(String sourceApi, RouteFamily routeFamily, String family, String nextAction, Throwable throwable) {
        StackTraceElement frame = probableExternalFrame(throwable.getStackTrace());
        if (!traceUnsafeCalls() && !traceModelReports()) return;
        architectureDecision("policy/blocked", sourceApi, family, routeFamily,
                "runtime-unsafe-call", "failure-preserved", nextAction,
                "probableFrame=" + format(frame) + " throwable=" + throwable.getClass().getName()
                        + ":" + safe(throwable.getMessage()));
        if (traceModelReports()) {
            emitModel(RouteModelRegistry.recordRuntime(sourceApi, routeFamily, family, nextAction,
                    "failure=" + throwable.getClass().getName() + ":" + throwable.getMessage(),
                    format(frame), throwable));
        }
        if (traceUnsafeCalls()) {
            log(Level.WARNING, "[FBB unsafe-failure] api=" + sourceApi
                    + " route=" + routeFamily.label()
                    + " family=" + family
                    + " next=" + nextAction
                    + " probableFrame=" + format(frame)
                    + " throwable=" + throwable.getClass().getName()
                    + ": " + throwable.getMessage(), throwable);
        }
    }

    static void scheduledFallbackAbandoned(String sourceApi, RouteFamily routeFamily,
                                           String family, String nextAction,
                                           String detail, Throwable throwable) {
        StackTraceElement frame = probableExternalFrame(throwable.getStackTrace());
        architectureDecision("policy/blocked", sourceApi, family, routeFamily,
                "scheduled-fallback", "abandoned-server-stopping", nextAction,
                "probableFrame=" + format(frame) + " detail=" + detail);
        if (traceModelReports()) {
            emitModel(RouteModelRegistry.recordRuntime(sourceApi, routeFamily, family, nextAction,
                    "abandoned=server-stopping:" + throwable.getClass().getName()
                            + ":" + throwable.getMessage(),
                    format(frame), throwable));
        }
        warning("[FBB unsafe-failure] api=" + sourceApi
                + " route=" + routeFamily.label()
                + " family=" + family
                + " next=" + nextAction
                + " probableFrame=" + format(frame)
                + " classification=server-stopping"
                + " action=abandon-scheduled-fallback"
                + " detail=\"" + PrivacySanitizer.text(detail) + "\""
                + " throwable=" + throwable.getClass().getName()
                + ": " + throwable.getMessage());
    }

    static void taskFailure(Plugin plugin, String scheduledFrom, Throwable throwable) {
        taskFailure(plugin, RouteFamily.S_GLOBAL, scheduledFrom, throwable);
    }

    static void taskFailure(Plugin plugin, RouteFamily routeFamily, String scheduledFrom, Throwable throwable) {
        taskFailureInternal(plugin, routeFamily, scheduledFrom, throwable);
    }

    static void taskFailureObject(Object plugin, RouteFamily routeFamily, String scheduledFrom, Throwable throwable) {
        taskFailureInternal(plugin, routeFamily, scheduledFrom, throwable);
    }

    private static void taskFailureInternal(Object plugin, RouteFamily routeFamily, String scheduledFrom, Throwable throwable) {
        if (!traceTaskFailures()) return;
        StackTraceElement frame = probableExternalFrame(throwable.getStackTrace());
        architectureDecision("policy/blocked", "scheduled-task", "scheduler", routeFamily,
                "task-failure", "failure-preserved", scheduledFrom,
                "plugin=" + pluginName(plugin) + " probableFrame=" + format(frame)
                        + " throwable=" + throwable.getClass().getName());
        if (traceModelReports()) {
            emitModel(RouteModelRegistry.recordRuntime("scheduled-task", routeFamily, "scheduler", "task-failure",
                    "failure=" + throwable.getClass().getName() + ":" + throwable.getMessage(),
                    format(frame), throwable));
        }
        String line = "[FBB task-failure] plugin=" + pluginName(plugin)
                + " route=" + routeFamily.label()
                + " scheduledFrom=" + scheduledFrom
                + " probableFrame=" + format(frame)
                + " throwable=" + throwable.getClass().getName()
                + ": " + throwable.getMessage();
        logRepeatedFailure("task-failure",
                pluginName(plugin) + "|" + routeFamily.label() + "|" + scheduledFrom
                        + "|" + format(frame) + "|" + throwable.getClass().getName()
                        + "|" + safe(throwable.getMessage()),
                line, throwable);
    }

    private static void emitModel(RouteModelRegistry.Report report) {
        if (report == null) return;
        for (String line : report.lines()) {
            info(line);
        }
    }

    private static void info(String line) {
        emit(Level.INFO, line, null);
    }

    private static void warning(String line) {
        emit(Level.WARNING, line, null);
    }

    private static void log(Level level, String line, Throwable throwable) {
        emit(level, line, throwable);
    }

    private static void emit(Level level, String line, Throwable throwable) {
        String sanitized = PrivacySanitizer.text(line);
        DebugFileSink.write(level, sanitized, throwable);
        String architectureStage = architectureStage(sanitized);
        if (architectureStage != null) {
            ArchitecturePathDebugSink.write(level, architectureStage, sanitized, throwable);
        }
        if (shouldPrintToConsole(level, sanitized)) {
            if (throwable == null) {
                logger.log(level, sanitized);
            } else {
                logger.log(level, sanitized, throwable);
            }
        }
    }

    private static void architectureDecision(String path, String input, String owner, RouteFamily routeFamily,
                                             String action, String result, String next, String detail) {
        String safePath = safe(path);
        String safeInput = compact(safe(input), 180);
        String safeOwner = compact(safe(owner), 160);
        String routeLabel = routeFamily == null ? "none routeFamily=UNKNOWN" : routeFamily.label();
        String safeAction = safe(action);
        String safeResult = safe(result);
        String safeNext = compact(safe(next), 160);
        String safeDetail = compact(PrivacySanitizer.text(detail), 260);
        String line = "[FBB architecture-decision]"
                + " path=" + safe(path)
                + " marker=" + architectureDecisionMarker(path)
                + " input=" + safeInput
                + " owner=" + safeOwner
                + " route=" + routeLabel
                + " action=" + safeAction
                + " result=" + safeResult
                + " next=" + safeNext
                + " detail=" + safeDetail
                + " note=architecture-pathfinding-summary-no-behavior-change";

        // Architecture pathfinding is intentionally verbose, but hot routes
        // like packet-time Player#getWorld can repeat thousands of times per
        // second. Keep the first/every-N evidence pattern instead of letting
        // duplicate pathfinding lines starve the debug queues.
        logRepeated("architecture-decision",
                safePath + "|" + safeInput + "|" + safeOwner + "|" + routeLabel
                        + "|" + safeAction + "|" + safeResult + "|" + safeNext
                        + "|" + safeDetail,
                line);
    }

    private static String architectureDecisionMarker(String path) {
        // Stable marker IDs make the architecture trace searchable even if the
        // surrounding diagnostic wording gets refined in later experiments.
        return switch (safe(path)) {
            case "summary" -> "FBB_ARCH_DECISION_SUMMARY_V1";
            case "owner/extract" -> "FBB_ARCH_OWNER_EXTRACT_V1";
            case "owner/miss" -> "FBB_ARCH_OWNER_MISS_V1";
            case "return/sync-risk" -> "FBB_ARCH_RETURN_RISK_V1";
            case "route/exit" -> "FBB_ARCH_ROUTE_EXIT_V1";
            case "route/stay-serialized" -> "FBB_ARCH_STAY_SERIALIZED_V1";
            case "policy/blocked" -> "FBB_ARCH_POLICY_BLOCKED_V1";
            case "promotion/evidence" -> "FBB_ARCH_PROMOTION_EVIDENCE_V1";
            case "helper/visibility" -> "FBB_ARCH_HELPER_VISIBILITY_V1";
            case "helper/state" -> "FBB_ARCH_HELPER_STATE_V1";
            default -> "FBB_ARCH_DECISION_V1";
        };
    }

    private static String architectureStage(String line) {
        if (line.startsWith("[FBB architecture-decision]")) {
            return "decision/" + architectureDecisionPath(line);
        }
        if (line.startsWith("[FBB build]")) return "boot/build-marker";
        if (line.startsWith("[FBB attach]") || line.startsWith("[FBB attach-warning]")) return "boot/agent-attach";
        if (line.startsWith("[FBB metadata]")) return "boot/metadata-load-gate";
        if (line.startsWith("[FBB agent-classpath]")) return "boot/agent-classpath";

        if (line.startsWith("[FBB candidate-scan]")) return "bytecode/prescan";
        if (line.startsWith("[FBB transform-skip]")) return "bytecode/typed-transform-skip";
        if (line.startsWith("[FBB transform-error]")) return "bytecode/transform-error";
        if (line.startsWith("[FBB transform]")) return "bytecode/rewrite-result";
        if (line.startsWith("[FBB bytecode-path]")) return "bytecode/registered-route-path";
        if (line.startsWith("[FBB teleport-path]")) return "bytecode/teleport-route-path";
        if (line.startsWith("[FBB guard-path]")) return "bytecode/guarded-route-path";
        if (line.startsWith("[FBB legacy-main-thread]")) return "bytecode/legacy-main-thread-compat";
        if (line.startsWith("[FBB helper-visibility]")) return "bytecode/helper-visibility";
        if (line.startsWith("[FBB helper-state]")) return "runtime/helper-state";

        if (line.startsWith("[FBB model-summary]")) return "model/summary";
        if (line.startsWith("[FBB model]")) return "model/route-rule";
        if (line.startsWith("[FBB scheduler]")) return "runtime/scheduler-route";
        if (line.startsWith("[FBB unsafe-call]")) return "runtime/unsafe-call-route";
        if (line.startsWith("[FBB unsafe-failure]")) return "runtime/unsafe-call-failure";
        if (line.startsWith("[FBB task-failure]")) return "runtime/scheduled-task-failure";
        if (line.startsWith("[FBB repeat-summary]")) return "runtime/repeat-summary";

        if (line.startsWith("[FBB compatibility-context]")) return "synthetic/context";
        if (line.startsWith("[FBB compatibility-lane]")) return "synthetic/serialized-lane";
        if (line.startsWith("[FBB event-listener]")) return "synthetic/listener-observation";
        if (line.startsWith("[FBB synthetic-event-state]")) return "synthetic/event-state";
        if (line.startsWith("[FBB synthetic-event-dispatch]")) return "synthetic/event-dispatch";
        if (line.startsWith("[FBB synthetic-owner-miss]")) return "synthetic/owner-miss";
        if (line.startsWith("[FBB synthetic-event-route-exit]")) return "synthetic/route-exit";
        if (line.startsWith("[FBB synthetic-listener-route-exit]")) return "synthetic/route-exit";
        if (line.startsWith("[FBB synthetic-multi-region]")) return "synthetic/multi-region";
        if (line.startsWith("[FBB synthetic-concurrency]")) return "synthetic/concurrency";
        if (line.startsWith("[FBB promotion-candidate]")) return "synthetic/promotion-candidate";

        if (line.startsWith("[FBB nms-compat]")) return "compat/nms-shape";
        if (line.startsWith("[FBB nms-context]")) return "compat/nms-context";
        if (line.startsWith("[FBB nms-lane]")) return "compat/nms-lane";
        if (line.startsWith("[FBB nms-owner-extract]")) return "compat/nms-owner-extract";
        if (line.startsWith("[FBB nms-owner-miss]")) return "compat/nms-owner-miss";
        if (line.startsWith("[FBB nms-route-exit]")) return "compat/nms-route-exit";
        if (line.startsWith("[FBB member-map]")) return "compat/member-map";

        return null;
    }

    private static String architectureDecisionPath(String line) {
        String path = fieldValue(line, "path", "summary");
        StringBuilder sanitized = new StringBuilder();
        for (int index = 0; index < path.length(); index++) {
            char ch = path.charAt(index);
            if (Character.isLetterOrDigit(ch) || ch == '/' || ch == '-' || ch == '_') {
                sanitized.append(ch);
            } else {
                sanitized.append('-');
            }
        }
        return sanitized.isEmpty() ? "summary" : sanitized.toString();
    }

    private static boolean shouldPrintToConsole(Level level, String line) {
        if (BridgeConfig.consoleVerbose()) return true;

        // These are high-volume evidence channels. They are intentionally kept
        // in debug.log so console output stays readable during live testing.
        if (line.startsWith("[FBB transform-skip]")
                || line.startsWith("[FBB model-summary]")
                || line.startsWith("[FBB repeat-summary]")
                || line.startsWith("[FBB candidate-scan]")
                || line.startsWith("[FBB architecture-decision]")) {
            return false;
        }

        // Synthetic event probes deliberately exercise unsafe owner failures so
        // the debug file can prove where a listener needs an owner-route exit.
        // Keep that evidence in debug.log by default; consoleVerbose=true can
        // still mirror it during a focused capture.
        if (line.startsWith("[FBB synthetic-event-dispatch]")
                && line.contains("action=listener-failure")
                && line.contains("next=listener-entity-owner-exit-needed")) {
            return false;
        }

        if (level.intValue() >= Level.WARNING.intValue()) return true;
        return line.startsWith("[FBB build]")
                || line.startsWith("[FBB attach]")
                || line.startsWith("[FBB agent-classpath]")
                || line.startsWith("[FBB metadata]");
    }

    private static void logRepeated(String channel, String key, String line) {
        RepeatState state = REPEAT_STATES.computeIfAbsent(channel + "|" + key, ignored -> new RepeatState());
        long total = state.total.incrementAndGet();
        int firstLines = Math.max(0, BridgeConfig.repeatDiagnosticFirstLines());
        int every = Math.max(0, BridgeConfig.repeatDiagnosticEvery());
        if (total <= firstLines) {
            state.lastEmitted = total;
            info(line);
            return;
        }
        if (every > 0 && total % every == 0) {
            long suppressed = Math.max(0, total - state.lastEmitted - 1);
            state.lastEmitted = total;
            info("[FBB repeat-summary] channel=" + channel
                    + " total=" + total
                    + " suppressedSinceLast=" + suppressed
                    + " key=" + compact(key, 220)
                    + " latest=" + compact(line, 260));
        }
    }

    private static void logRepeatedFailure(String channel, String key, String line, Throwable throwable) {
        String sanitized = PrivacySanitizer.text(line);
        DebugFileSink.write(Level.SEVERE, sanitized, throwable);
        String architectureStage = architectureStage(sanitized);
        if (architectureStage != null) {
            ArchitecturePathDebugSink.write(Level.SEVERE, architectureStage, sanitized, throwable);
        }

        if (BridgeConfig.consoleVerbose()) {
            logger.log(Level.SEVERE, sanitized, throwable);
            return;
        }

        RepeatState state = REPEAT_STATES.computeIfAbsent(channel + "|" + key, ignored -> new RepeatState());
        long total = state.total.incrementAndGet();
        int firstLines = Math.max(0, BridgeConfig.repeatDiagnosticFirstLines());
        int every = Math.max(0, BridgeConfig.repeatDiagnosticEvery());
        if (total <= firstLines) {
            state.lastEmitted = total;
            logger.log(Level.SEVERE, sanitized, throwable);
            return;
        }
        if (every > 0 && total % every == 0) {
            long suppressed = Math.max(0, total - state.lastEmitted - 1);
            state.lastEmitted = total;
            logger.log(Level.WARNING, "[FBB repeat-summary] channel=" + channel
                    + " total=" + total
                    + " suppressedSinceLast=" + suppressed
                    + " key=" + compact(key, 220)
                    + " latest=" + compact(sanitized, 260)
                    + " note=full-failures-remain-in-debug-log");
        }
    }

    static String captureCaller() {
        return format(probableExternalFrame(Thread.currentThread().getStackTrace()));
    }

    static String probablePath(Throwable throwable) {
        StackTraceElement frame = probableExternalFrame(throwable.getStackTrace());
        return format(frame);
    }

    private static StackTraceElement probableExternalFrame(StackTraceElement[] stackTrace) {
        return Arrays.stream(stackTrace)
                .filter(BridgeDiagnostics::isExternalFrame)
                .findFirst()
                .orElse(stackTrace.length == 0 ? null : stackTrace[0]);
    }

    private static boolean isExternalFrame(StackTraceElement frame) {
        String className = frame.getClassName();
        for (String prefix : INTERNAL_PREFIXES) {
            if (className.startsWith(prefix)) return false;
        }
        return true;
    }

    private static String format(StackTraceElement frame) {
        if (frame == null) return "unknown";
        return frame.getClassName() + "#" + frame.getMethodName()
                + "(" + frame.getFileName() + ":" + frame.getLineNumber() + ")";
    }

    private static String pluginName(Plugin plugin) {
        if (plugin == null) return "unknown";
        try {
            return plugin.getName();
        } catch (Throwable ignored) {
            return plugin.getClass().getName();
        }
    }

    private static String pluginName(Object plugin) {
        if (plugin == null) return "unknown";
        if (plugin instanceof Plugin) return pluginName((Plugin) plugin);
        try {
            return String.valueOf(plugin.getClass().getMethod("getName").invoke(plugin));
        } catch (Throwable ignored) {
            return plugin.getClass().getName();
        }
    }

    private static String loaderName(ClassLoader classLoader) {
        if (classLoader == null) return "bootstrap";
        return classLoader.getClass().getName();
    }

    private static String codeSource(java.security.ProtectionDomain protectionDomain) {
        if (protectionDomain == null
                || protectionDomain.getCodeSource() == null
                || protectionDomain.getCodeSource().getLocation() == null) {
            return "unknown";
        }
        return PrivacySanitizer.text(protectionDomain.getCodeSource().getLocation().toString());
    }

    private static String compact(String value, int max) {
        if (value == null) return "unknown";
        String compact = value.replace('\n', ' ').replace('\r', ' ');
        return compact.length() <= max ? compact : compact.substring(0, max) + "...";
    }

    private static String returnRiskResult(String descriptor) {
        String type = returnType(descriptor);
        if ("void".equals(type)) return "void-fire-and-forget";
        if ("boolean".equals(type) || "int".equals(type) || "long".equals(type)
                || "double".equals(type) || "float".equals(type)) {
            return "sync-return-primitive";
        }
        if ("runtime".equals(type) || "unknown".equals(type)) return "sync-return-unknown";
        return "sync-return-object";
    }

    private static String returnPolicyName(String descriptor) {
        String type = returnType(descriptor);
        if ("void".equals(type)) return "return=void";
        return "return=" + type + " syncReturnRisk=true";
    }

    private static String runtimeReturnRiskResult(String detail, String sourceApi) {
        String lower = (safe(detail) + " " + safe(sourceApi)).toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("policy=fire-and-forget-void")) return "void-fire-and-forget";
        if (lower.contains("policy=entity-owner-read-return")) return "sync-return-owner-read";
        if (lower.contains("policy=sync-return-model")) return "sync-return-modeled";
        if (lower.contains("policy=bounded-region-wait")) return "sync-return-bounded-region-wait";
        if (lower.contains("policy=deferred-proxy-return")) return "sync-return-deferred-proxy";
        if (lower.contains("policy=deferred-accepted-boolean")) return "sync-return-accepted-boolean";
        if (lower.contains("get") || lower.contains("score") || lower.contains("nearby")
                || lower.contains("entities")) {
            return "sync-return-no-contract-yet";
        }
        return "runtime-return-not-obvious";
    }

    private static String runtimeResultFromDetail(String detail) {
        String result = fieldValue(detail, "result", null);
        if (result != null) return result;
        String action = fieldValue(detail, "action", null);
        if (action != null) return action;
        String policy = fieldValue(detail, "policy", null);
        if (policy != null) return policy;
        return "observed";
    }

    private static String policyFromDetail(String detail) {
        return fieldValue(detail, "policy", "policy=no-contract-yet");
    }

    private static String returnType(String descriptor) {
        if (descriptor == null || descriptor.isBlank() || "runtime".equals(descriptor)) return "runtime";
        int close = descriptor.lastIndexOf(')');
        if (close < 0 || close + 1 >= descriptor.length()) return "unknown";
        return descriptorTypeName(descriptor.substring(close + 1));
    }

    private static String descriptorTypeName(String descriptor) {
        if ("V".equals(descriptor)) return "void";
        if ("Z".equals(descriptor)) return "boolean";
        if ("I".equals(descriptor)) return "int";
        if ("J".equals(descriptor)) return "long";
        if ("F".equals(descriptor)) return "float";
        if ("D".equals(descriptor)) return "double";
        if (descriptor.startsWith("[")) return descriptorTypeName(descriptor.substring(1)) + "[]";
        if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
            return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
        }
        return descriptor;
    }

    private static String fieldValue(String text, String key, String defaultValue) {
        if (text == null || key == null || key.isBlank()) return defaultValue;
        String prefix = key + "=";
        int start = text.indexOf(prefix);
        if (start < 0) return defaultValue;
        int valueStart = start + prefix.length();
        int end = text.indexOf(' ', valueStart);
        if (end < 0) end = text.length();
        if (valueStart >= end) return defaultValue;
        return text.substring(valueStart, end);
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return PrivacySanitizer.text(value.replace('\n', ' ').replace('\r', ' '));
    }

    private static final class RepeatState {
        private final java.util.concurrent.atomic.AtomicLong total = new java.util.concurrent.atomic.AtomicLong();
        private volatile long lastEmitted;
    }

}
