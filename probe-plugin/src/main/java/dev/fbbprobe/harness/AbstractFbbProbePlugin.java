package dev.fbbprobe.harness;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public abstract class AbstractFbbProbePlugin extends JavaPlugin implements Listener, ProbeRuntime {

    private static final String CORE_FIRST_JOIN_MODES = "safe,scan,ui,visibility,entity,world";
    private static final String PAPER_GUARD_MODES = "paper";
    private static final String DEFAULT_FIRST_JOIN_MODES = CORE_FIRST_JOIN_MODES + "," + PAPER_GUARD_MODES;
    private static final String DEFAULT_FIRST_JOIN_CONTEXTS = "current,entity,async,global,region,foreign-region";
    private static final String DEFAULT_STARTUP_MODES = "startup";
    private static final String DEFAULT_STARTUP_CONTEXTS = "global,async,region";
    private static final String MODE_LIST = "startup, safe, scan, ui, visibility, entity, world, chunk, server, scoreboard, recovery, paper, all, destructive";
    private static final String CONTEXT_LIST = "current, startup, entity, async, global, region, foreign-region, all";

    private final Set<UUID> firstJoinPlayers = new HashSet<>();
    private final ThreadLocal<String> activeProbeMode = ThreadLocal.withInitial(() -> "unknown");
    private final ThreadLocal<String> activeProbeContext = ThreadLocal.withInitial(() -> "current");

    protected abstract ProbeActions actions();

    protected abstract String rootCommand();

    protected abstract String bridgeRole();

    protected final boolean controlBaseline() {
        return "control-untransformed".equals(bridgeRole());
    }

    private void probeInfo(String line) {
        probeLog(Level.INFO, line, null);
    }

    private void probeWarning(String line) {
        probeLog(Level.WARNING, line, null);
    }

    private void probeLog(Level level, String line, Throwable throwable) {
        ProbeDebugFileSink.write(level, line, throwable);
        if (probeConsoleVerbose()
                || level.intValue() >= Level.WARNING.intValue()
                || line.contains(" enabled ")
                || line.contains(" startupModes=")
                || line.contains(" firstJoinModes=")) {
            if (throwable == null) {
                getLogger().log(level, line);
            } else {
                getLogger().log(level, line, throwable);
            }
        }
    }

    private static boolean probeConsoleVerbose() {
        return Boolean.parseBoolean(System.getProperty("foliabytecodebridge.consoleVerbose", "false"))
                || Boolean.parseBoolean(System.getProperty("foliabytecodebridge.debug", "false"));
    }

    protected String defaultFirstJoinModes() {
        return DEFAULT_FIRST_JOIN_MODES;
    }

    protected String firstJoinPropertyName() {
        return rootCommand() + ".firstJoinModes";
    }

    @Override
    public final JavaPlugin plugin() {
        return this;
    }

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        probeInfo("[FBB probe] enabled root=/" + rootCommand()
                + " bridgeRole=" + bridgeRole()
                + " modes=" + MODE_LIST
                + " contexts=" + CONTEXT_LIST);
        probeInfo("[FBB probe] firstJoinModes=" + firstJoinModes()
                + " firstJoinContexts=" + firstJoinContexts()
                + " property=" + firstJoinPropertyName()
                + "," + firstJoinContextsPropertyName()
                + " destructiveRequires=-D" + rootCommand() + ".firstJoinDestructive=true");
        probeInfo("[FBB probe] startupModes=" + startupModes()
                + " startupContexts=" + startupContexts()
                + " properties=" + startupModesPropertyName() + "," + startupContextsPropertyName());
        scheduleStartupProbes();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        String modes = firstJoinModes();
        if (modes.isBlank() || "off".equalsIgnoreCase(modes)) return;
        Player player = event.getPlayer();
        if (!firstJoinPlayers.add(player.getUniqueId())) return;
        probeInfo("[FBB probe] first-join root=/" + rootCommand()
                + " bridgeRole=" + bridgeRole()
                + " contexts=" + firstJoinContexts()
                + " player=" + player.getName() + " modes=" + modes);
        runFirstJoinModes(player, firstJoinContexts(), modes);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!rootCommand().equalsIgnoreCase(command.getName())) return false;
        if (args.length > 0 && isHelpMode(args[0])) {
            sendHelp(sender);
            return true;
        }
        if (!(sender instanceof Player player)) {
            String mode = args.length == 0 ? "startup" : args[0].toLowerCase(Locale.ROOT);
            if ("startup".equals(mode)) {
                runStartupModesInContexts("command-console", startupContexts(), mode);
                sender.sendMessage("FBB startup probe scheduled from console. Check console for [FBB probe] lines.");
                return true;
            }
            sender.sendMessage("FBB probe modes live under /" + rootCommand() + ": " + MODE_LIST);
            sender.sendMessage("Most probe modes must be run by a player so entity and region context exists.");
            sender.sendMessage("Console can run /" + rootCommand() + " startup for world/chunk/server probes.");
            return true;
        }

        if (args.length > 0 && "context".equalsIgnoreCase(args[0])) {
            String context = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "current";
            String mode = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "safe";
            runModesInContext(player, context, mode, "command-context");
            sender.sendMessage("FBB probe scheduled: /" + rootCommand() + " context " + context + " " + mode
                    + ". Check console for [FBB probe] lines.");
            return true;
        }

        if (args.length > 0 && "matrix".equalsIgnoreCase(args[0])) {
            String mode = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "safe";
            runModesInContext(player, "all", mode, "command-matrix");
            sender.sendMessage("FBB probe matrix scheduled: /" + rootCommand() + " matrix " + mode
                    + ". Check console for [FBB probe] lines.");
            return true;
        }

        String mode = args.length == 0 ? "safe" : args[0].toLowerCase(Locale.ROOT);
        runModesInContext(player, "current", mode, "command");
        sender.sendMessage("FBB probe finished: " + mode + ". Check console for [FBB probe] and [FBB ...] lines.");
        return true;
    }

    private void runModesInContext(Player player, String context, String modes, String trigger) {
        if ("all".equals(context)) {
            for (String next : new String[]{"current", "entity", "async", "global", "region", "foreign-region"}) {
                runModesInContext(player, next, modes, trigger + "-all");
            }
            return;
        }
        if ("startup".equals(context)) {
            runStartupModesInContexts(trigger, startupContexts(), modes);
            return;
        }

        Runnable task = () -> runModes(player, modes, trigger, context);
        try {
            switch (context) {
                case "current" -> task.run();
                case "entity" -> player.getScheduler().run(this, ignored -> task.run(),
                        () -> logContextRetired(player, context, modes, trigger));
                case "async" -> Bukkit.getAsyncScheduler().runNow(this, ignored -> task.run());
                case "global" -> Bukkit.getGlobalRegionScheduler().run(this, ignored -> task.run());
                case "region" -> Bukkit.getRegionScheduler().run(this, player.getLocation(), ignored -> task.run());
                case "foreign-region" -> {
                    Location foreign = player.getLocation().clone().add(1024.0D, 0.0D, 1024.0D);
                    Bukkit.getRegionScheduler().run(this, foreign, ignored -> task.run());
                }
                default -> probeWarning("[FBB probe] root=/" + rootCommand()
                        + " bridgeRole=" + bridgeRole()
                        + " context=" + context
                        + " mode=" + modes
                        + " route=S_GLOBAL api=unknown-context owner=FBBProbe name=runModesInContext"
                        + " descriptor=(Ljava/lang/String;)V action=skipped");
            }
        } catch (Throwable throwable) {
            String failure = failureFingerprint(throwable);
            String control = controlBaseline()
                    ? " controlExpected=true controlPurpose=raw-folia-baseline"
                    : "";
            probeLog(Level.WARNING, "[FBB probe] root=/" + rootCommand()
                    + " bridgeRole=" + bridgeRole()
                    + " context=" + context
                    + " mode=" + modes
                    + " route=S_GLOBAL api=context-schedule owner=FBBProbe name=runModesInContext"
                    + " descriptor=(Ljava/lang/String;)V result=failed throwable="
                    + throwable.getClass().getName() + ": " + throwable.getMessage()
                    + " " + failure + control, throwable);
        }
    }

    private void runFirstJoinModes(Player player, String contexts, String modes) {
        if (contexts.equalsIgnoreCase("all")) {
            runModesInContext(player, "all", modes, "first-join");
            return;
        }
        for (String context : contexts.split(",")) {
            runModesInContext(player, context.trim().toLowerCase(Locale.ROOT), modes, "first-join");
        }
    }

    private void runModes(Player player, String modes, String trigger, String context) {
        for (String mode : modes.split(",")) {
            runMode(player, mode.trim().toLowerCase(Locale.ROOT), trigger, context);
        }
    }

    private void runMode(Player player, String mode, String trigger, String context) {
        if (mode.isBlank() || "off".equals(mode)) return;
        if ("destructive".equals(mode) && !firstJoinDestructiveAllowed() && "first-join".equals(trigger)) {
            probeWarning("[FBB probe] root=/" + rootCommand()
                    + " bridgeRole=" + bridgeRole()
                    + " context=" + context
                    + " mode=destructive route=S_GLOBAL api=first-join-destructive owner=FBBProbe name=firstJoin"
                    + " descriptor=destructive action=skipped reason=set-D" + rootCommand() + ".firstJoinDestructive=true");
            return;
        }
        activeProbeMode.set(mode);
        activeProbeContext.set(context);
        probeInfo("[FBB probe] begin root=/" + rootCommand()
                + " bridgeRole=" + bridgeRole()
                + " context=" + context
                + " mode=" + mode
                + " expands=" + modeSummary(mode)
                + " trigger=" + trigger + " player=" + player.getName());
        try {
            switch (mode) {
                case "startup" -> actions().runStartupProbes(this);
                case "safe" -> actions().runSafeProbes(this, player);
                case "scan" -> actions().runScanProbes(this, player);
                case "ui" -> actions().runUiProbes(this, player);
                case "visibility" -> actions().runVisibilityProbes(this, player);
                case "entity" -> actions().runEntityMutationProbes(this, player);
                case "world" -> actions().runWorldEffectProbes(this, player);
                case "chunk" -> actions().runChunkGuardProbes(this, player);
                case "server" -> actions().runServerGuardProbes(this, player);
                case "scoreboard" -> actions().runScoreboardGuardProbes(this, player);
                case "recovery" -> actions().runRecoveryPathProbes(this, player);
                case "paper" -> {
                    actions().runChunkGuardProbes(this, player);
                    actions().runServerGuardProbes(this, player);
                    actions().runScoreboardGuardProbes(this, player);
                    actions().runRecoveryPathProbes(this, player);
                }
                case "all" -> {
                    actions().runSafeProbes(this, player);
                    actions().runScanProbes(this, player);
                    actions().runUiProbes(this, player);
                    actions().runVisibilityProbes(this, player);
                    actions().runEntityMutationProbes(this, player);
                    actions().runWorldEffectProbes(this, player);
                    actions().runChunkGuardProbes(this, player);
                    actions().runServerGuardProbes(this, player);
                    actions().runScoreboardGuardProbes(this, player);
                    actions().runRecoveryPathProbes(this, player);
                }
                case "destructive" -> {
                    actions().runEntityMutationProbes(this, player);
                    actions().runWorldEffectProbes(this, player);
                    actions().runDestructiveProbes(this, player);
                }
                default -> probeWarning("[FBB probe] root=/" + rootCommand()
                        + " bridgeRole=" + bridgeRole()
                        + " context=" + context
                        + " mode=" + mode
                        + " route=S_GLOBAL api=unknown-mode owner=FBBProbe name=runMode"
                        + " descriptor=(Ljava/lang/String;)V action=skipped");
            }
        } finally {
            probeInfo("[FBB probe] end root=/" + rootCommand()
                    + " bridgeRole=" + bridgeRole()
                    + " context=" + context
                    + " mode=" + mode
                    + " trigger=" + trigger + " player=" + player.getName());
            activeProbeMode.remove();
            activeProbeContext.remove();
        }
    }

    private void scheduleStartupProbes() {
        String modes = startupModes();
        if (modes.isBlank() || "off".equalsIgnoreCase(modes)) return;
        try {
            Bukkit.getGlobalRegionScheduler().runDelayed(this,
                    ignored -> runStartupModesInContexts("startup-auto", startupContexts(), modes),
                    startupDelayTicks());
        } catch (Throwable throwable) {
            String control = controlBaseline()
                    ? " controlExpected=true controlPurpose=raw-folia-baseline"
                    : "";
            probeLog(Level.WARNING, "[FBB probe] root=/" + rootCommand()
                    + " bridgeRole=" + bridgeRole()
                    + " context=global mode=" + modes
                    + " route=S_GLOBAL api=startup-schedule owner=FBBProbe name=scheduleStartupProbes"
                    + " descriptor=()V result=failed throwable="
                    + throwable.getClass().getName() + ": " + throwable.getMessage()
                    + " " + failureFingerprint(throwable) + control, throwable);
        }
    }

    private void runStartupModesInContexts(String trigger, String contexts, String modes) {
        for (String context : contexts.split(",")) {
            runStartupModesInContext(context.trim().toLowerCase(Locale.ROOT), modes, trigger);
        }
    }

    private void runStartupModesInContext(String context, String modes, String trigger) {
        if (context.isBlank() || "off".equals(context)) return;
        Runnable task = () -> runStartupModes(modes, trigger, context);
        try {
            switch (context) {
                case "current", "global" -> task.run();
                case "async" -> Bukkit.getAsyncScheduler().runNow(this, ignored -> task.run());
                case "region" -> {
                    Location location = startupLocation();
                    if (location == null) {
                        startupBlocked(context, modes, trigger, "no-world-loaded");
                        return;
                    }
                    Bukkit.getRegionScheduler().run(this, location, ignored -> task.run());
                }
                default -> probeWarning("[FBB probe] root=/" + rootCommand()
                        + " bridgeRole=" + bridgeRole()
                        + " context=" + context
                        + " mode=" + modes
                        + " route=S_GLOBAL api=unknown-startup-context owner=FBBProbe name=runStartupModesInContext"
                        + " descriptor=(Ljava/lang/String;)V action=skipped");
            }
        } catch (Throwable throwable) {
            String control = controlBaseline()
                    ? " controlExpected=true controlPurpose=raw-folia-baseline"
                    : "";
            probeLog(Level.WARNING, "[FBB probe] root=/" + rootCommand()
                    + " bridgeRole=" + bridgeRole()
                    + " context=" + context
                    + " mode=" + modes
                    + " route=S_GLOBAL api=startup-context-schedule owner=FBBProbe name=runStartupModesInContext"
                    + " descriptor=(Ljava/lang/String;)V result=failed throwable="
                    + throwable.getClass().getName() + ": " + throwable.getMessage()
                    + " " + failureFingerprint(throwable) + control, throwable);
        }
    }

    private void runStartupModes(String modes, String trigger, String context) {
        for (String mode : modes.split(",")) {
            runStartupMode(mode.trim().toLowerCase(Locale.ROOT), trigger, context);
        }
    }

    private void runStartupMode(String mode, String trigger, String context) {
        if (mode.isBlank() || "off".equals(mode)) return;
        activeProbeMode.set(mode);
        activeProbeContext.set(context);
        probeInfo("[FBB probe] begin root=/" + rootCommand()
                + " bridgeRole=" + bridgeRole()
                + " context=" + context
                + " mode=" + mode
                + " expands=" + modeSummary(mode)
                + " trigger=" + trigger + " player=none");
        try {
            if ("startup".equals(mode)) {
                actions().runStartupProbes(this);
            } else if ("paper".equals(mode) || "recovery".equals(mode) || "all".equals(mode)) {
                // Startup has no Player receiver. Reuse the broad no-player startup bucket so
                // configured boot probes stay useful without adding command modes or fake players.
                actions().runStartupProbes(this);
            } else {
                probeWarning("[FBB probe] root=/" + rootCommand()
                        + " bridgeRole=" + bridgeRole()
                        + " context=" + context
                        + " mode=" + mode
                        + " route=S_GLOBAL api=startup-player-required owner=FBBProbe name=runStartupMode"
                        + " descriptor=(Ljava/lang/String;)V action=skipped reason=mode-requires-player");
            }
        } finally {
            probeInfo("[FBB probe] end root=/" + rootCommand()
                    + " bridgeRole=" + bridgeRole()
                    + " context=" + context
                    + " mode=" + mode
                    + " trigger=" + trigger + " player=none");
            activeProbeMode.remove();
            activeProbeContext.remove();
        }
    }

    private void startupBlocked(String context, String modes, String trigger, String reason) {
        probeWarning("[FBB probe] root=/" + rootCommand()
                + " bridgeRole=" + bridgeRole()
                + " context=" + context
                + " mode=" + modes
                + " trigger=" + trigger
                + " route=S_GLOBAL api=startup-world-context owner=FBBProbe name=startupLocation"
                + " descriptor=()Lorg/bukkit/Location; result=blocked action=trace-only reason=" + reason);
    }

    @Override
    public final void probe(String route, String api, String owner, String name, String descriptor, ProbeCall call) {
        probe(new ProbePath(rootCommand(), bridgeRole(), activeProbeMode.get(), activeProbeContext.get(),
                route, api, owner, name, descriptor, null), call);
    }

    @Override
    public final void probeGuard(String route, String api, String owner, String name, String descriptor,
                                 String guard, ProbeCall call) {
        probe(new ProbePath(rootCommand(), bridgeRole(), activeProbeMode.get(), activeProbeContext.get(),
                route, api, owner, name, descriptor, guard), call);
    }

    @Override
    public final void probeBlocked(String route, String api, String owner, String name, String descriptor,
                                   String blockedBy, String reason) {
        ProbePath path = new ProbePath(rootCommand(), bridgeRole(), activeProbeMode.get(), activeProbeContext.get(),
                route, api, owner, name, descriptor, null);
        probeWarning("[FBB probe] " + path.format()
                + " result=blocked blockedBy=" + blockedBy
                + " action=trace-only reason=" + reason);
    }

    private void probe(ProbePath path, ProbeCall call) {
        probeInfo("[FBB probe] " + path.format() + " action=invoke");
        try {
            call.run();
            probeInfo("[FBB probe] " + path.format() + " result=completed");
        } catch (Throwable throwable) {
            String failure = failureFingerprint(throwable);
            String action = unsupportedOperation(throwable)
                    ? unsupportedAction()
                    : "";
            probeLog(Level.WARNING,
                    "[FBB probe] " + path.format() + " result=failed throwable="
                            + throwable.getClass().getName() + ": " + throwable.getMessage()
                            + " " + failure + action + controlEvidence(),
                    throwable);
        }
    }

    private String unsupportedAction() {
        if (controlBaseline()) {
            return " action=trace-only reason=control-baseline-raw-folia-failure";
        }
        return " action=trace-only reason=unsupported-operation-route-not-proven";
    }

    private String controlEvidence() {
        if (!controlBaseline()) return "";
        return " controlExpected=true controlPurpose=raw-folia-baseline";
    }

    private void logContextRetired(Player player, String context, String modes, String trigger) {
        probeWarning("[FBB probe] root=/" + rootCommand()
                + " bridgeRole=" + bridgeRole()
                + " context=" + context
                + " mode=" + modes
                + " trigger=" + trigger
                + " player=" + player.getName()
                + " route=A_ENTITY api=context-retired owner=EntityScheduler name=run"
                + " descriptor=(Plugin,Consumer,Runnable)V result=skipped");
    }

    private record ProbePath(String rootCommand, String bridgeRole, String mode, String context, String route,
                             String api, String owner, String name, String descriptor, String guard) {
        String format() {
            return "root=/" + rootCommand
                    + " bridgeRole=" + bridgeRole
                    + " mode=" + mode
                    + " context=" + context
                    + " intent=" + intent()
                    + " route=" + route
                    + " api=" + api
                    + " owner=" + owner
                    + " name=" + name
                    + " descriptor=" + descriptor
                    + (guard == null ? "" : " guard=" + guard);
        }

        private String intent() {
            if (guard != null) return "guard-probe";
            if (api.contains("#teleport")) return "rewrite-candidate";
            if (api.contains("#get") || api.contains("#is") || api.contains("#has")) return "context-read";
            if ("G_WORLD_SCAN_SPLIT".equals(route)) return "scan-context-check";
            if ("S_GLOBAL".equals(route)) return "global-thread-check";
            return "context-mutation";
        }
    }

    private static String failureFingerprint(Throwable throwable) {
        StackTraceElement pluginFrame = firstPluginFrame(throwable);
        StackTraceElement guardFrame = firstGuardFrame(throwable);
        StackTraceElement schedulerFrame = firstSchedulerFrame(throwable);
        return "classification=" + classification(throwable)
                + " thread=\"" + Thread.currentThread().getName() + "\""
                + " pluginFrame=" + formatFrame(pluginFrame)
                + " guardFrame=" + formatFrame(guardFrame)
                + " schedulerFrame=" + formatFrame(schedulerFrame);
    }

    private static String classification(Throwable throwable) {
        if (unsupportedOperation(throwable)) return "unsupported-operation";
        String message = throwable.getMessage();
        if (message != null) {
            String normalized = message.toLowerCase(Locale.ROOT);
            if (normalized.contains("async") || normalized.contains("thread")) return "thread-guard";
            if (normalized.contains("region")) return "region-guard";
        }
        if (throwable instanceof LinkageError) return "linkage-error";
        return throwable.getClass().getName();
    }

    private static boolean unsupportedOperation(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof UnsupportedOperationException) return true;
        }
        return false;
    }

    private static StackTraceElement firstPluginFrame(Throwable throwable) {
        return firstFrame(throwable, frame -> {
            String className = frame.getClassName();
            return !className.startsWith("java.")
                    && !className.startsWith("jdk.")
                    && !className.startsWith("sun.")
                    && !className.startsWith("org.bukkit.")
                    && !className.startsWith("io.papermc.")
                    && !className.startsWith("ca.spottedleaf.")
                    && !className.startsWith("net.minecraft.");
        });
    }

    private static StackTraceElement firstGuardFrame(Throwable throwable) {
        return firstFrame(throwable, frame -> {
            String className = frame.getClassName();
            return className.startsWith("org.bukkit.craftbukkit.")
                    || className.startsWith("io.papermc.paper.")
                    || className.startsWith("ca.spottedleaf.");
        });
    }

    private static StackTraceElement firstSchedulerFrame(Throwable throwable) {
        return firstFrame(throwable, frame -> {
            String className = frame.getClassName();
            return className.contains("Scheduler")
                    || className.contains("TickThread")
                    || className.contains("RegionizedTaskQueue")
                    || className.contains("CraftScheduler");
        });
    }

    private static StackTraceElement firstFrame(Throwable throwable, FramePredicate predicate) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            for (StackTraceElement frame : current.getStackTrace()) {
                if (predicate.matches(frame)) return frame;
            }
        }
        return null;
    }

    private static String formatFrame(StackTraceElement frame) {
        if (frame == null) return "none";
        return frame.getClassName() + "#" + frame.getMethodName()
                + "(" + frame.getFileName() + ":" + frame.getLineNumber() + ")";
    }

    @FunctionalInterface
    private interface FramePredicate {
        boolean matches(StackTraceElement frame);
    }

    private String firstJoinModes() {
        String configured = System.getProperty(firstJoinPropertyName(), defaultFirstJoinModes()).trim();
        if (configured.equalsIgnoreCase("all")) return DEFAULT_FIRST_JOIN_MODES;
        if (configured.equalsIgnoreCase("core")) return CORE_FIRST_JOIN_MODES;
        if (configured.equalsIgnoreCase("full") || configured.equalsIgnoreCase("everything")) return DEFAULT_FIRST_JOIN_MODES;
        return Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .reduce((left, right) -> left + "," + right)
                .orElse("off");
    }

    private String firstJoinContexts() {
        return normalizedCsv(System.getProperty(firstJoinContextsPropertyName(), DEFAULT_FIRST_JOIN_CONTEXTS).trim());
    }

    private String firstJoinContextsPropertyName() {
        return rootCommand() + ".firstJoinContexts";
    }

    private String startupModes() {
        return normalizedCsv(System.getProperty(startupModesPropertyName(), DEFAULT_STARTUP_MODES).trim());
    }

    private String startupContexts() {
        return normalizedCsv(System.getProperty(startupContextsPropertyName(), DEFAULT_STARTUP_CONTEXTS).trim());
    }

    private String startupModesPropertyName() {
        return rootCommand() + ".startupModes";
    }

    private String startupContextsPropertyName() {
        return rootCommand() + ".startupContexts";
    }

    private long startupDelayTicks() {
        return Long.getLong(rootCommand() + ".startupDelayTicks", 60L);
    }

    private static String normalizedCsv(String configured) {
        if (configured.equalsIgnoreCase("off")) return "off";
        return Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .reduce((left, right) -> left + "," + right)
                .orElse("off");
    }

    private static Location startupLocation() {
        if (Bukkit.getWorlds().isEmpty()) return null;
        World world = Bukkit.getWorlds().get(0);
        return world.getSpawnLocation();
    }

    private static boolean isHelpMode(String value) {
        String mode = value.toLowerCase(Locale.ROOT);
        return "help".equals(mode) || "list".equals(mode) || "modes".equals(mode);
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("FBB probe uses one command: /" + rootCommand() + " <mode>");
        sender.sendMessage("Modes: " + MODE_LIST);
        sender.sendMessage("Contexts: " + CONTEXT_LIST);
        sender.sendMessage("/" + rootCommand() + " all runs every non-destructive probe bucket in the current context.");
        sender.sendMessage("/" + rootCommand() + " recovery replays proven fallback paths before they become preemptive.");
        sender.sendMessage("/" + rootCommand() + " startup runs boot-safe world/chunk/server probes without a player.");
        sender.sendMessage("/" + rootCommand() + " context async safe runs one bucket from Folia async scheduler.");
        sender.sendMessage("/" + rootCommand() + " matrix safe runs one bucket across all contexts.");
        sender.sendMessage("First join auto-runs modes across: " + DEFAULT_FIRST_JOIN_CONTEXTS + ".");
        sender.sendMessage("/" + rootCommand() + " destructive is separate and should only be used on throwaway worlds.");
    }

    private static String modeSummary(String mode) {
        return switch (mode) {
            case "all" -> "safe+scan+ui+visibility+entity+world+paper";
            case "startup" -> "world+chunk+scan+server+unowned-ui+recovery-candidates(no-player)";
            case "paper" -> "chunk+server+scoreboard+recovery";
            case "recovery" -> "proven-fallback-candidates";
            case "destructive" -> "entity+world+destructive";
            default -> mode;
        };
    }

    private boolean firstJoinDestructiveAllowed() {
        return Boolean.getBoolean(rootCommand() + ".firstJoinDestructive");
    }
}

