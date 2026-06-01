package dev.foliabytecodebridge;

import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

final class BridgeDiagnostics {

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

    static void setLogger(Logger logger) {
        BridgeDiagnostics.logger = logger;
    }

    static void buildMarker(String phase, String pluginVersion, java.io.File jarFile) {
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

    static void bytecodePath(String className, String methodName, String methodDescriptor,
                             String sourceOwner, String sourceMethod, String sourceDescriptor,
                             String bridgeMethod, String bridgeDescriptor, RouteFamily routeFamily) {
        if (!traceBytecodePaths()) return;
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
    }

    static void transformSkipped(String className, ClassLoader classLoader, String reason) {
        SKIPPED_TRANSFORMS.add(className);
        if (!traceTransformSkips()) return;
        info("[FBB transform-skip] class=" + className
                + " loader=" + loaderName(classLoader)
                + " reason=" + reason);
    }

    static void optionalDependencyTransformSkipped(String className, ClassLoader classLoader,
                                                   String missingType, String asmRouteSummary) {
        SKIPPED_TRANSFORMS.add(className);
        warning("[FBB transform-skip] class=" + className
                + " loader=" + loaderName(classLoader)
                + " reason=optional-dependency-missing"
                + " missing=" + missingType
                + " action=skip-typed-transform-no-fake-adapter"
                + " asm=" + asmRouteSummary);
    }

    static void typedRouteCandidateScan(String className, ClassLoader classLoader,
                                        TypedRouteCandidateReporter.CandidateReport report) {
        info("[FBB candidate-scan] marker=" + report.marker()
                + " class=" + className
                + " loader=" + loaderName(classLoader)
                + " action=" + report.action()
                + " category=" + report.category()
                + " typedTransform=still-attempted"
                + " reason=" + report.reason()
                + " note=diagnostic-bytecode-prescan-before-typed-transform");
    }

    static void transformError(String className, Throwable throwable) {
        log(Level.WARNING, "[FBB transform-error] class=" + className + " message="
                + throwable.getMessage(), throwable);
    }

    static void compatibilityFailure(Throwable throwable) {
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
        warning("[FBB nms-compat] category=" + NmsCompatModel.CATEGORY
                + " model=SERVER_TICK_COUNTER"
                + " owner=" + className.replace('/', '.')
                + " member=" + memberName
                + " descriptor=" + descriptor
                + " result=" + result
                + " action=" + action
                + " hook=" + hook
                + " route=none"
                + " note=server-internal-shape-adapter-not-folia-route");
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
        info("[FBB metadata] class=" + className.replace('/', '.')
                + " loader=" + loaderName(classLoader)
                + " jar=" + codeSource(protectionDomain)
                + " route=S_GLOBAL"
                + " action=metadata-transform"
                + " mode=" + BridgeConfig.metadataOverlay()
                + " result=" + result);
    }

    static void metadataJar(String pluginName, String jar, String action, String mode, String result) {
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
        if (traceModelReports()) {
            emitModel(RouteModelRegistry.recordRuntime("scheduled-task", routeFamily, "scheduler", "task-failure",
                    "failure=" + throwable.getClass().getName() + ":" + throwable.getMessage(),
                    format(frame), throwable));
        }
        log(Level.SEVERE, "[FBB task-failure] plugin=" + pluginName(plugin)
                + " route=" + routeFamily.label()
                + " scheduledFrom=" + scheduledFrom
                + " probableFrame=" + format(frame)
                + " throwable=" + throwable.getClass().getName()
                + ": " + throwable.getMessage(), throwable);
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
        if (shouldPrintToConsole(level, sanitized)) {
            if (throwable == null) {
                logger.log(level, sanitized);
            } else {
                logger.log(level, sanitized, throwable);
            }
        }
    }

    private static boolean shouldPrintToConsole(Level level, String line) {
        if (BridgeConfig.consoleVerbose()) return true;

        // These are high-volume evidence channels. They are intentionally kept
        // in debug.log so console output stays readable during live testing.
        if (line.startsWith("[FBB transform-skip]")
                || line.startsWith("[FBB model-summary]")
                || line.startsWith("[FBB repeat-summary]")) {
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

    private static final class RepeatState {
        private final java.util.concurrent.atomic.AtomicLong total = new java.util.concurrent.atomic.AtomicLong();
        private volatile long lastEmitted;
    }

}

