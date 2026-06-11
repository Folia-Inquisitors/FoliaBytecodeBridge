package smoketest;

import dev.foliabytecodebridge.InstructionRouteScanner;
import dev.foliabytecodebridge.NmsCompatModel;
import dev.foliabytecodebridge.NmsCompatibilityLaneSmoke;
import dev.foliabytecodebridge.NmsSyntheticMemberTransformerSmoke;
import dev.foliabytecodebridge.ObjectSchedulerBridge;
import dev.foliabytecodebridge.RawLegacyMainThreadTransformerSmoke;
import dev.foliabytecodebridge.RawMcUtilExecutorTransformerSmoke;
import dev.foliabytecodebridge.RawNmsServerExecutorTransformerSmoke;
import dev.foliabytecodebridge.SchedulerBridge;
import dev.foliabytecodebridge.RouteFamily;
import dev.foliabytecodebridge.RawServerCommandTransformerSmoke;
import dev.foliabytecodebridge.RawSchedulerTransformerSmoke;
import dev.foliabytecodebridge.RepeatDiagnosticsSmoke;
import dev.foliabytecodebridge.ServerExecutorBridge;
import dev.foliabytecodebridge.ServerMemberMap;
import dev.foliabytecodebridge.SyntheticEventDispatchBridge;
import dev.foliabytecodebridge.SyntheticEventPathBridge;
import dev.foliabytecodebridge.SyntheticListenerConcurrencySmoke;
import dev.foliabytecodebridge.UnsafeCallBridge;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LightningStrike;
import org.bukkit.entity.Player;
import org.bukkit.event.EventException;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SmokeMain {

    private SmokeMain() {
    }

    public static void main(String[] args) {
        System.setProperty("foliabytecodebridge.debug", "true");
        // Production defaults keep noisy diagnostics in debug.log. The smoke
        // harness mirrors them to the logger so route assertions can inspect
        // the same evidence without parsing a server-side file.
        System.setProperty("foliabytecodebridge.consoleVerbose", "true");
        System.setProperty("foliabytecodebridge.traceSchedulerCalls", "true");
        System.setProperty("foliabytecodebridge.traceUnsafeCalls", "true");
        System.setProperty("foliabytecodebridge.metadataOverlay", "all");
        System.setProperty("foliabytecodebridge.smokeNoPassthrough", "true");
        LogCapture logs = LogCapture.install();

        SchedulerBridge.resetBridgeCallCount();
        UnsafeCallBridge.resetUnsafeCallCount();
        runMetadataGateProbe();
        Plugin plugin = fakePlugin();
        World world = fakeWorld();
        Player player = fakePlayer(world);

        SmokeTarget.runSchedulerCalls(fakeScheduler(), plugin);
        SmokeTarget.runBukkitRunnableCalls(plugin);
        SmokeTarget.runUnsafeCalls(null, plugin, null, null);
        SmokeTarget.runUnsafeCalls(player, plugin, world, fakeBlock(world));
        SmokeTarget.runMethodReferenceUnsafeCalls(world);
        SmokeTarget.runPluginShapeUnsafeCalls(player, plugin);
        SmokeTarget.runScoreboardManagerShapeCalls(fakeScoreboardManager(), player);
        SmokeTarget.runCustomEventDispatch(fakePluginManager());
        runTaskFailureProbe(plugin);
        Path[] pluginJars = pluginJars();
        BytecodeInventorySmoke.Result inventory = BytecodeInventorySmoke.scan(pluginJars);
        int rawInheritedOwnerHits = RawSchedulerTransformerSmoke.assertInheritedBukkitRunnableAsync(pluginJars);
        int rawAnonymousOverrideHits = RawSchedulerTransformerSmoke.assertAnonymousRunnableOverride(pluginJars);
        int rawWrapperGuardHits = RawSchedulerTransformerSmoke.assertPluginSchedulerHelperNotMisclassified(pluginJars);
        int rawLegacyAsyncRepeatingHits = RawSchedulerTransformerSmoke.assertLegacyAsyncRepeatingScheduler(pluginJars);
        int rawCommandDispatchHits = RawServerCommandTransformerSmoke.assertSmokeTargetCommandDispatch();
        InstructionRouteScanner.RouteReport routeReport = runInstructionRouteScannerSmoke();
        int routeRuleCount = assertGeneratedRouteRuleRegistrySmoke();
        String nmsCompatEvidence = runNmsCompatModelSmoke();
        String memberMapEvidence = runServerMemberMapSmoke();
        String nmsSyntheticMemberEvidence = NmsSyntheticMemberTransformerSmoke.assertMinecraftServerCurrentTickShim();
        String legacyMainThreadEvidence = RawLegacyMainThreadTransformerSmoke.assertLegacyMainThreadFallback();
        String mcUtilExecutorEvidence = RawMcUtilExecutorTransformerSmoke.assertMcUtilMainExecutorRewrite();
        String nmsServerExecutorEvidence = RawNmsServerExecutorTransformerSmoke.assertMinecraftServerExecuteRewrite();
        String nmsCompatibilityLaneEvidence = NmsCompatibilityLaneSmoke.assertNmsCompatibilityLaneAndOwnerModel(world);
        RepeatDiagnosticsSmoke.emitRepeatSummaryEvidence();
        String compatibilityLaneEvidence = runCompatibilityLaneSmoke();
        String listenerRouteExitEvidence = runSyntheticListenerFailureClassifierSmoke(plugin);
        String entityOwnedRouteExitEvidence = runSyntheticEntityOwnedRouteExitSmoke(plugin, player);
        String blockOwnedRouteExitEvidence = runSyntheticBlockOwnedRouteExitSmoke(plugin, fakeBlock(world));
        String delegatedBlockRouteExitEvidence = runSyntheticDelegatedBlockRouteExitSmoke(plugin, fakeBlock(world));
        String locationOwnedRouteExitEvidence = runSyntheticLocationOwnedRouteExitSmoke(plugin,
                new Location(world, 12, 64, 12));
        String noOwnerSerializedEvidence = runSyntheticNoOwnerSerializedSmoke();
        String multiRegionSerializedEvidence = runSyntheticMultiRegionSerializedSmoke(world);
        String multiRegionReadSplitEvidence = runSyntheticMultiRegionReadSplitSmoke(world);
        String multiRegionMutationPlanEvidence = runSyntheticMultiRegionMutationPlanSmoke(world);
        String multiRegionMutationContractEvidence = runSyntheticMultiRegionMutationContractSmoke(world);
        String multiRegionMutationPrepareFailureEvidence = runSyntheticMultiRegionMutationPrepareFailureSmoke(world);
        String multiRegionMutationVerifyFailureEvidence = runSyntheticMultiRegionMutationVerifyFailureSmoke(world);
        String listenerConcurrencyEvidence = SyntheticListenerConcurrencySmoke.run();

        logs.assertOnlyOfficialRoutes();
        logs.require("[FBB scheduler]", RouteFamily.S_GLOBAL);
        logs.require("[FBB scheduler]", RouteFamily.S_ASYNC);
        logs.require("[FBB metadata]", RouteFamily.S_GLOBAL);
        logs.requireContains("[FBB metadata]", "class=org.bukkit.plugin.PluginDescriptionFile",
                "action=metadata-transform", "mode=all", "result=patched-return-true");
        logs.require("[FBB unsafe-call]", RouteFamily.A_ENTITY, RouteFamily.B_REGION_LOCATION,
                RouteFamily.C_REGION_BLOCK, RouteFamily.D_PLAYER_UI, RouteFamily.F_PLAYER_VISIBILITY,
                RouteFamily.G_WORLD_SCAN_SPLIT);
        logs.require("[FBB unsafe-failure]", RouteFamily.A_ENTITY, RouteFamily.B_REGION_LOCATION,
                RouteFamily.C_REGION_BLOCK, RouteFamily.D_PLAYER_UI, RouteFamily.F_PLAYER_VISIBILITY,
                RouteFamily.G_WORLD_SCAN_SPLIT);
        logs.require("[FBB task-failure]", RouteFamily.S_GLOBAL);
        logs.require("[FBB model]", RouteFamily.S_GLOBAL, RouteFamily.S_ASYNC, RouteFamily.A_ENTITY,
                RouteFamily.B_REGION_LOCATION, RouteFamily.C_REGION_BLOCK, RouteFamily.D_PLAYER_UI,
                RouteFamily.F_PLAYER_VISIBILITY, RouteFamily.G_WORLD_SCAN_SPLIT);
        logs.requireContains("[FBB model-summary]", "methods=", "routes=", "knownRules=", "blockedSyncReturn=");
        logs.requireContains("[FBB model]", "route=D_PLAYER_UI", "api=org.bukkit.entity.Player#openInventory",
                "owner=org.bukkit.entity.Player",
                "descriptor=(Lorg/bukkit/inventory/Inventory;)Lorg/bukkit/inventory/InventoryView;",
                "status=rewritten", "routeRulePolicy=SYNC_RETURN_DIRECT_OR_OWNER",
                "routeRuleStatus=EXPERIMENTAL_REWRITE");
        logs.requireContains("[FBB model]", "route=D_PLAYER_UI", "api=Player#openInventory",
                "routeRulePolicy=SYNC_RETURN_DIRECT_OR_OWNER",
                "routeRuleStatus=EXPERIMENTAL_REWRITE");
        logs.requireContains("[FBB model]", "route=D_PLAYER_UI", "api=Player#closeInventory",
                "routeRulePolicy=VOID_FIRE_AND_FORGET",
                "routeRuleStatus=EXPERIMENTAL_REWRITE");
        logs.requireContains("[FBB model]", "route=D_PLAYER_UI", "api=HumanEntity#openInventory",
                "routeRulePolicy=SYNC_RETURN_DIRECT_OR_OWNER",
                "routeRuleStatus=EXPERIMENTAL_REWRITE");
        logs.requireContains("[FBB model]", "route=D_PLAYER_UI", "api=HumanEntity#closeInventory",
                "routeRulePolicy=VOID_FIRE_AND_FORGET",
                "routeRuleStatus=EXPERIMENTAL_REWRITE");
        logs.requireContains("[FBB model]", "route=G_WORLD_SCAN_SPLIT", "api=org.bukkit.World#getEntities",
                "owner=org.bukkit.World", "descriptor=()Ljava/util/List;", "next=split-or-return-model",
                "routeRulePolicy=SPLIT_AGGREGATE_RETURN");
        logs.requireContains("[FBB model]", "route=A_ENTITY", "api=org.bukkit.entity.Player#addPotionEffect",
                "owner=org.bukkit.entity.Player", "descriptor=(Lorg/bukkit/potion/PotionEffect;)Z",
                "routeRulePolicy=ACCEPTED_BOOLEAN");
        logs.requireContains("[FBB model]", "route=S_GLOBAL", "api=BukkitScheduler#runTask",
                "owner=global", "status=scheduler");
        logs.requireContains("[FBB model]", "route=S_GLOBAL",
                "api=io.papermc.paper.util.MCUtil#MAIN_EXECUTOR.execute",
                "routeRulePolicy=VOID_FIRE_AND_FORGET",
                "routeRuleStatus=EXPERIMENTAL_REWRITE");
        logs.requireContains("[FBB model]", "route=S_GLOBAL",
                "api=net.minecraft.server.MinecraftServer#execute",
                "routeRulePolicy=VOID_FIRE_AND_FORGET",
                "routeRuleStatus=EXPERIMENTAL_REWRITE");
        logs.requireContains("[FBB model]", "route=S_GLOBAL",
                "api=org.bukkit.plugin.PluginManager#callEvent",
                "routeRuleStatus=EXPERIMENTAL_REWRITE");
        logs.requireContains("[FBB repeat-summary]", "channel=unsafe-call",
                "suppressedSinceLast=", "latest=[FBB unsafe-call]");
        logs.requireContains("[FBB teleport-path]", "owner=org.bukkit.entity.Entity",
                "name=teleportAsync", "rule=bukkit-api-owner", "action=trace-only",
                "outcome=typed-transform-expected", "route=A_ENTITY");
        logs.requireContains("[FBB teleport-path]", "name=teleport",
                "descriptor=(Lorg/bukkit/Location;)Z", "rule=bukkit-api-owner", "action=rewritten",
                "outcome=rewritten-direct-teleport-async-shim", "route=A_ENTITY");
        logs.requireContains("[FBB teleport-path]", "name=teleport",
                "descriptor=(Lorg/bukkit/Location;Lorg/bukkit/event/player/PlayerTeleportEvent$TeleportCause;)Z",
                "rule=bukkit-api-owner", "action=rewritten",
                "outcome=rewritten-direct-teleport-async-shim", "route=A_ENTITY");
        logs.requireContains("[FBB teleport-path]", "owner=smoketest.shaded.PaperLibLike",
                "descriptor=(Lorg/bukkit/entity/Player;Lorg/bukkit/Location;Lorg/bukkit/event/player/PlayerTeleportEvent$TeleportCause;)Ljava/util/concurrent/CompletableFuture;",
                "rule=generic-shape", "action=rewritten", "outcome=rewritten-generic-static-shape",
                "route=A_ENTITY");
        logs.requireContains("[FBB teleport-path]", "owner=smoketest.shaded.PaperLibLike",
                "rule=generic-shape-probe", "action=missed", "outcome=missed-unsupported-static-shape",
                "route=A_ENTITY");
        logs.requireContains("[FBB teleport-path]", "owner=smoketest.shaded.GenericTeleportHelperLike",
                "descriptor=(Lorg/bukkit/entity/Player;Lorg/bukkit/Location;)Ljava/lang/Object;",
                "rule=generic-helper-shape", "action=trace-only", "outcome=observed-entity-location-helper",
                "route=A_ENTITY");
        logs.requireContains("[FBB teleport-path]", "owner=smoketest.shaded.GenericTeleportSchedulerLike",
                "descriptor=(Lorg/bukkit/entity/Entity;Lorg/bukkit/Location;)Ljava/util/concurrent/CompletableFuture;",
                "rule=generic-helper-shape", "action=trace-only", "outcome=observed-entity-location-helper",
                "route=A_ENTITY");
        logs.requireContains("[FBB teleport-path]", "owner=smoketest.shaded.GenericTeleportSchedulerLike",
                "descriptor=(Lorg/bukkit/entity/Entity;Lorg/bukkit/Location;Lorg/bukkit/event/player/PlayerTeleportEvent$TeleportCause;)Ljava/util/concurrent/CompletableFuture;",
                "rule=generic-helper-shape", "action=trace-only", "outcome=observed-entity-location-helper",
                "route=A_ENTITY");
        logs.requireContains("[FBB guard-path]", "owner=org.bukkit.Bukkit", "name=dispatchCommand",
                "descriptor=(Lorg/bukkit/command/CommandSender;Ljava/lang/String;)Z",
                "route=S_GLOBAL", "action=rewritten");
        logs.requireContains("[FBB guard-path]", "owner=org.bukkit.Server", "name=dispatchCommand",
                "descriptor=(Lorg/bukkit/command/CommandSender;Ljava/lang/String;)Z",
                "route=S_GLOBAL", "action=rewritten");
        logs.requireContains("[FBB guard-path]", "owner=org.bukkit.entity.Player", "name=getScoreboard",
                "descriptor=()Lorg/bukkit/scoreboard/Scoreboard;",
                "route=D_PLAYER_UI", "action=rewritten");
        logs.requireContains("[FBB guard-path]", "owner=org.bukkit.entity.Player", "name=openInventory",
                "descriptor=(Lorg/bukkit/inventory/Inventory;)Lorg/bukkit/inventory/InventoryView;",
                "route=D_PLAYER_UI", "action=rewritten");
        logs.requireContains("[FBB guard-path]", "owner=org.bukkit.entity.HumanEntity", "name=closeInventory",
                "descriptor=()V", "route=D_PLAYER_UI", "action=rewritten");
        logs.requireContains("[FBB guard-path]", "owner=org.bukkit.scoreboard.Scoreboard", "name=registerNewTeam",
                "descriptor=(Ljava/lang/String;)Lorg/bukkit/scoreboard/Team;",
                "route=D_PLAYER_UI", "action=rewritten");
        logs.requireContains("[FBB guard-path]", "owner=org.bukkit.scoreboard.Scoreboard", "name=registerNewObjective",
                "descriptor=(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Lorg/bukkit/scoreboard/Objective;",
                "route=D_PLAYER_UI", "action=rewritten");
        logs.requireContains("[FBB guard-path]", "owner=org.bukkit.scoreboard.Objective", "name=setDisplaySlot",
                "descriptor=(Lorg/bukkit/scoreboard/DisplaySlot;)V",
                "route=D_PLAYER_UI", "action=rewritten");
        logs.requireContains("[FBB guard-path]", "owner=org.bukkit.scoreboard.Objective", "name=getScore",
                "descriptor=(Ljava/lang/String;)Lorg/bukkit/scoreboard/Score;",
                "route=D_PLAYER_UI", "action=rewritten");
        logs.requireContains("[FBB guard-path]", "owner=org.bukkit.scoreboard.Score", "name=setScore",
                "descriptor=(I)V", "route=D_PLAYER_UI", "action=rewritten");
        logs.requireContains("[FBB guard-path]", "owner=org.bukkit.scoreboard.Team", "name=setPrefix",
                "descriptor=(Ljava/lang/String;)V", "route=D_PLAYER_UI", "action=rewritten");
        logs.requireContains("[FBB guard-path]", "owner=org.bukkit.scoreboard.Team", "name=setSuffix",
                "descriptor=(Ljava/lang/String;)V", "route=D_PLAYER_UI", "action=rewritten");
        logs.requireContains("[FBB guard-path]", "owner=org.bukkit.scoreboard.Team", "name=setColor",
                "descriptor=(Lorg/bukkit/ChatColor;)V", "route=D_PLAYER_UI", "action=rewritten");
        logs.requireContains("[FBB guard-path]", "owner=org.bukkit.scoreboard.Objective", "name=displayName",
                "descriptor=(Lnet/kyori/adventure/text/Component;)V", "route=D_PLAYER_UI", "action=rewritten");
        logs.requireContains("[FBB guard-path]", "owner=org.bukkit.scoreboard.Score", "name=customName",
                "descriptor=(Lnet/kyori/adventure/text/Component;)V", "route=D_PLAYER_UI", "action=rewritten");
        logs.requireContains("[FBB guard-path]", "owner=org.bukkit.scoreboard.ScoreboardManager", "name=getNewScoreboard",
                "descriptor=()Lorg/bukkit/scoreboard/Scoreboard;",
                "route=D_PLAYER_UI", "action=rewritten");
        logs.requireContains("[FBB guard-path]", "owner=org.bukkit.scoreboard.Team", "name=setOption",
                "descriptor=(Lorg/bukkit/scoreboard/Team$Option;Lorg/bukkit/scoreboard/Team$OptionStatus;)V",
                "route=D_PLAYER_UI", "action=rewritten");
        logs.requireContains("[FBB guard-path]", "owner=org.bukkit.World", "name=refreshChunk",
                "descriptor=(II)Z", "route=B_REGION_LOCATION", "action=rewritten");
        logs.requireContains("[FBB guard-path]", "owner=org.bukkit.World", "name=getEntities",
                "descriptor=()Ljava/util/List;", "route=G_WORLD_SCAN_SPLIT", "action=rewritten",
                "invokedynamic method reference handle routed through bridge");
        logs.requireContains("[FBB legacy-main-thread]",
                "name=isMainThread", "descriptor=()Z", "route=S_GLOBAL",
                "rule=exact-owner-method-body", "action=rewritten");
        logs.requireContains("[FBB unsafe-call]", "api=World#getEntities",
                "route=G_WORLD_SCAN_SPLIT", "next=split-scan-by-loaded-chunks",
                "model=split-by-loaded-chunks");
        logs.requireContains("[FBB unsafe-call]", "api=World#getEntities",
                "route=G_WORLD_SCAN_SPLIT", "next=split-scan-by-loaded-chunks",
                "reason=proven-live-route");
        logs.requireContains("[FBB unsafe-call]", "api=World#getLoadedChunks",
                "route=G_WORLD_SCAN_SPLIT", "next=world-loaded-chunk-index",
                "model=loaded-chunk-index");
        logs.requireContains("[FBB unsafe-call]", "api=World#getEntitiesByClass(Class)",
                "route=G_WORLD_SCAN_SPLIT", "next=split-scan-by-loaded-chunks",
                "model=split-by-loaded-chunks");
        logs.requireContains("[FBB unsafe-call]", "api=World#getNearbyEntities(Location,double,double,double)",
                "route=G_WORLD_SCAN_SPLIT", "next=region-scheduler-by-location-bounded-scan",
                "model=bounded-split-by-loaded-chunks");
        logs.requireContains("[FBB unsafe-call]", "api=ScoreboardManager#getNewScoreboard",
                "route=D_PLAYER_UI", "next=scoreboard-detached-model-create",
                "policy=shim-model");
        logs.requireContains("[FBB unsafe-call]", "api=Scoreboard#registerNewTeam(String)",
                "route=D_PLAYER_UI", "next=scoreboard-model-team-create",
                "policy=shim-model");
        logs.requireContains("[FBB unsafe-call]", "api=Scoreboard#registerNewObjective(String,String,String)",
                "route=D_PLAYER_UI", "next=scoreboard-model-objective-create",
                "policy=shim-model");
        logs.requireContains("[FBB unsafe-call]", "api=Objective#setDisplaySlot(DisplaySlot)",
                "route=D_PLAYER_UI", "next=scoreboard-model-objective-mutation");
        logs.requireContains("[FBB unsafe-call]", "api=Objective#getScore(String)",
                "route=D_PLAYER_UI", "next=scoreboard-model-score-read");
        logs.requireContains("[FBB unsafe-call]", "api=Score#setScore(int)",
                "route=D_PLAYER_UI", "next=scoreboard-model-score-mutation");
        logs.requireContains("[FBB unsafe-call]", "api=Team#setPrefix(String)",
                "route=D_PLAYER_UI", "next=scoreboard-model-team-mutation");
        logs.requireContains("[FBB unsafe-call]", "api=Team#setSuffix(String)",
                "route=D_PLAYER_UI", "next=scoreboard-model-team-mutation");
        logs.requireContains("[FBB unsafe-call]", "api=Team#setColor(ChatColor)",
                "route=D_PLAYER_UI", "next=scoreboard-model-team-mutation");
        logs.requireContains("[FBB unsafe-call]", "api=Objective#displayName(Component)",
                "route=D_PLAYER_UI", "next=scoreboard-model-objective-mutation");
        logs.requireContains("[FBB unsafe-call]", "api=Score#customName(Component)",
                "route=D_PLAYER_UI", "next=scoreboard-model-score-mutation");
        logs.requireContains("[FBB transform]", "class=smoketest.SmokeTarget",
                "path=raw-command-dispatch", "result=patched", "replacements=2");
        logs.requireContains("[FBB guard-path]", "owner=org.bukkit.plugin.PluginManager",
                "name=callEvent", "descriptor=(Lorg/bukkit/event/Event;)V",
                "route=S_GLOBAL", "action=rewritten");
        logs.requireContains("[FBB synthetic-event-dispatch]", "action=synthetic-start",
                "event=smoketest.SmokeTarget$SmokeSharedEvent", "result=dispatching");
        logs.requireContains("[FBB synthetic-event-dispatch]", "action=synthetic-finish",
                "event=smoketest.SmokeTarget$SmokeSharedEvent", "result=completed");
        logs.requireContains("[FBB compatibility-lane]", "action=submit",
                "source=synthetic-event-path:smoketest.SharedEvent", "result=queued");
        logs.requireContains("[FBB compatibility-lane]", "action=start",
                "source=synthetic-event-path:smoketest.SharedEvent", "result=running");
        logs.requireContains("[FBB compatibility-lane]", "action=finish",
                "source=synthetic-event-path:smoketest.SharedEvent", "result=completed");
        logs.requireContains("[FBB compatibility-context]", "action=enter",
                "kind=synthetic-event-path", "source=smoketest.SharedEvent",
                "policy=synthetic-model");
        logs.requireContains("[FBB event-listener]", "event=smoketest.SharedEvent",
                "phase=MONITOR", "effect=read-final-state", "laneActive=true");
        logs.requireContains("[FBB synthetic-event-dispatch]", "action=listener-failure",
                "event=smoketest.SmokeTarget$SmokeSharedEvent",
                "route=A_ENTITY", "family=entity",
                "next=listener-entity-owner-exit-needed",
                "evidence=entity-state-guard",
                "note=listener-failure-preserved-during-synthetic-dispatch-owner-exit-needed");
        logs.requireContains("[FBB synthetic-event-route-exit]", "action=current-owner",
                "event=smoketest.SmokeTarget$SmokeEntityOwnedEvent",
                "route=A_ENTITY", "family=entity",
                "next=listener-entity-owner-exit",
                "ownerMethod=getEntity", "path=direct-current-owner");
        logs.requireContains("[FBB synthetic-event-dispatch]", "action=synthetic-start",
                "event=smoketest.SmokeTarget$SmokeEntityOwnedEvent",
                "route=A_ENTITY", "path=direct-current-owner");
        logs.requireContains("[FBB synthetic-event-route-exit]", "action=current-owner",
                "event=smoketest.SmokeTarget$SmokeBlockCollectionOwnedEvent",
                "route=C_REGION_BLOCK", "family=region",
                "next=listener-block-owner-exit",
                "ownerMethod=getBlocks", "path=direct-current-owner");
        logs.requireContains("[FBB synthetic-event-dispatch]", "action=synthetic-start",
                "event=smoketest.SmokeTarget$SmokeBlockCollectionOwnedEvent",
                "route=C_REGION_BLOCK", "path=direct-current-owner");
        logs.requireContains("[FBB synthetic-event-route-exit]", "action=current-owner",
                "event=smoketest.SmokeTarget$SmokeDelegatedBlockOwnedEvent",
                "route=C_REGION_BLOCK", "family=region",
                "ownerMethod=delegate:getOriginalEvent.getBlock", "path=direct-current-owner");
        logs.requireContains("[FBB synthetic-event-dispatch]", "action=synthetic-start",
                "event=smoketest.SmokeTarget$SmokeDelegatedBlockOwnedEvent",
                "route=C_REGION_BLOCK", "path=direct-current-owner");
        logs.requireContains("[FBB synthetic-event-route-exit]", "action=current-owner",
                "event=smoketest.SmokeTarget$SmokeLocationOwnedEvent",
                "route=B_REGION_LOCATION", "family=region",
                "next=listener-location-owner-exit",
                "ownerMethod=getLocation", "path=direct-current-owner");
        logs.requireContains("[FBB synthetic-event-dispatch]", "action=synthetic-start",
                "event=smoketest.SmokeTarget$SmokeLocationOwnedEvent",
                "route=B_REGION_LOCATION", "path=direct-current-owner");
        logs.requireContains("[FBB synthetic-event-state]", "action=serialized",
                "event=smoketest.SmokeTarget$SmokeNoOwnerEvent",
                "ownerStatus=no-owner-contract", "laneStatus=serialized-compatibility-lane");
        logs.requireContains("[FBB synthetic-owner-miss]",
                "event=smoketest.SmokeTarget$SmokeNoOwnerEvent",
                "marker=FBB_SYNTHETIC_OWNER_MISS_SERIALIZED_V1",
                "no-compatible-owner-getter");
        logs.requireContains("[FBB synthetic-event-state]", "action=serialized",
                "event=smoketest.SmokeTarget$SmokeMultiBlockCollectionOwnedEvent",
                "ownerStatus=no-owner-contract", "laneStatus=serialized-compatibility-lane");
        logs.requireContains("[FBB synthetic-owner-miss]",
                "event=smoketest.SmokeTarget$SmokeMultiBlockCollectionOwnedEvent",
                "getBlocks:multi-region-collection");
        logs.requireContains("[FBB synthetic-multi-region]",
                "phase=detect",
                "event=smoketest.SmokeTarget$SmokeMultiBlockCollectionOwnedEvent",
                "route=C_REGION_BLOCK",
                "marker=FBB_SYNTHETIC_MULTI_REGION_DETECTED_V1",
                "owners=2",
                "result=observed-not-promoted");
        logs.requireContains("[FBB synthetic-multi-region]",
                "phase=split-read",
                "event=smoketest.SmokeTarget$SmokeReadOnlyMultiBlockCollectionOwnedEvent",
                "route=C_REGION_BLOCK",
                "marker=FBB_SYNTHETIC_READ_SPLIT_V1",
                "operation=read-only",
                "readOnly=true",
                "result=aggregated",
                "completedOwners=2");
        logs.requireContains("[FBB synthetic-multi-region]",
                "phase=plan-mutation",
                "event=smoketest.SmokeTarget$SmokeMultiBlockCollectionOwnedEvent",
                "route=C_REGION_BLOCK",
                "marker=FBB_SYNTHETIC_MUTATION_PLAN_V1",
                "operation=read-or-write-unknown",
                "result=serialized-unproven",
                "reason=no-explicit-mutation-intent");
        logs.requireContains("[FBB synthetic-multi-region]",
                "phase=plan-mutation",
                "event=smoketest.SmokeTarget$SmokeMutationMultiBlockCollectionOwnedEvent",
                "route=C_REGION_BLOCK",
                "marker=FBB_SYNTHETIC_MUTATION_PLAN_V1",
                "operation=read-or-write-unknown",
                "result=planned-not-executed",
                "phases=prepare,owner-apply,aggregate-verify",
                "intentGetter=isMutation",
                "mutationKind=block-set-type");
        logs.requireContains("[FBB synthetic-multi-region]",
                "phase=contract-mutation",
                "event=smoketest.SmokeTarget$SmokeMutationMultiBlockCollectionOwnedEvent",
                "route=C_REGION_BLOCK",
                "marker=FBB_SYNTHETIC_MUTATION_CONTRACT_V1",
                "result=contract-missing",
                "reason=missing-two-phase-contract",
                "prepare=false",
                "ownerApply=false",
                "aggregateVerify=false");
        logs.requireContains("[FBB synthetic-multi-region]",
                "phase=contract-mutation",
                "event=smoketest.SmokeTarget$SmokeContractMultiBlockCollectionOwnedEvent",
                "route=C_REGION_BLOCK",
                "marker=FBB_SYNTHETIC_MUTATION_CONTRACT_V1",
                "result=ready-not-executed",
                "contract=prepare,owner-apply,aggregate-verify",
                "mutationKind=block-set-type");
        logs.requireContains("[FBB synthetic-multi-region]",
                "phase=execute-mutation",
                "event=smoketest.SmokeTarget$SmokeContractMultiBlockCollectionOwnedEvent",
                "route=C_REGION_BLOCK",
                "result=completed",
                "reason=verified",
                "marker=FBB_SYNTHETIC_MUTATION_COMPLETED_VERIFIED_V1",
                "scheduledOwners=2",
                "completedOwners=2",
                "prepareHook=prepareMutation",
                "applyHook=applyOwnerMutation",
                "verifyHook=verifyAggregateMutation",
                "mode=smoke-inline");
        logs.requireContains("[FBB synthetic-multi-region]",
                "phase=execute-mutation",
                "event=smoketest.SmokeTarget$SmokeContractMultiBlockCollectionOwnedEvent",
                "route=C_REGION_BLOCK",
                "result=contract-rejected",
                "reason=prepare-returned-false",
                "marker=FBB_SYNTHETIC_MUTATION_PREPARE_BLOCKED_V1",
                "prepareHook=prepareMutation",
                "mutationKind=block-set-type");
        logs.requireContains("[FBB synthetic-multi-region]",
                "phase=execute-mutation",
                "event=smoketest.SmokeTarget$SmokeContractMultiBlockCollectionOwnedEvent",
                "route=C_REGION_BLOCK",
                "result=contract-rejected",
                "reason=verify-returned-false",
                "marker=FBB_SYNTHETIC_MUTATION_VERIFY_BLOCKED_V1",
                "scheduledOwners=2",
                "completedOwners=2",
                "verifyHook=verifyAggregateMutation",
                "mode=smoke-inline");
        logs.requireContains("[FBB synthetic-concurrency]",
                "phase=5A",
                "action=reentered",
                "event=smoketest.SyntheticConcurrencyEvent",
                "listener=SmokePlugin/smoketest.SyntheticConcurrencyListener",
                "route=none",
                "routeFamily=UNKNOWN",
                "activePath=diagnostic-probe:smoke-phase-5a",
                "currentPath=diagnostic-probe:smoke-phase-5a",
                "result=compatibility-sensitive");
        logs.requireContains("[FBB synthetic-listener-route-exit]",
                "event=smoketest.SmokeTarget$SmokeEntityOwnedEvent",
                "route=A_ENTITY", "listener=SmokePlugin/");

        int calls = SchedulerBridge.bridgeCallCount();
        int unsafeCalls = UnsafeCallBridge.unsafeCallCount();
        if (calls < 22) {
            throw new IllegalStateException("Expected at least 22 bridge calls, got " + calls);
        }
        if (unsafeCalls < 130) {
            throw new IllegalStateException("Expected at least 130 unsafe calls, got " + unsafeCalls);
        }
        System.out.println("SMOKE_OK bridgeCalls=" + calls
                + " unsafeCalls=" + unsafeCalls
                + inventory.summarySuffix()
                + " rawInheritedOwnerHits=" + rawInheritedOwnerHits
                + " rawAnonymousOverrideHits=" + rawAnonymousOverrideHits
                + " rawWrapperGuardHits=" + rawWrapperGuardHits
                + " rawLegacyAsyncRepeatingHits=" + rawLegacyAsyncRepeatingHits
                + " rawCommandDispatchHits=" + rawCommandDispatchHits
                + " asmRouteHits=" + routeReport.hits().size()
                + " routeRules=" + routeRuleCount
                + " nmsCompatEvidence=" + nmsCompatEvidence
                + " memberMapEvidence=" + memberMapEvidence
                + " nmsSyntheticMemberEvidence=" + nmsSyntheticMemberEvidence
                + " legacyMainThreadEvidence=" + legacyMainThreadEvidence
                + " mcUtilExecutorEvidence=" + mcUtilExecutorEvidence
                + " nmsServerExecutorEvidence=" + nmsServerExecutorEvidence
                + " nmsCompatibilityLaneEvidence=" + nmsCompatibilityLaneEvidence
                + " compatibilityLaneEvidence=" + compatibilityLaneEvidence
                + " listenerRouteExitEvidence=" + listenerRouteExitEvidence
                + " entityOwnedRouteExitEvidence=" + entityOwnedRouteExitEvidence
                + " blockOwnedRouteExitEvidence=" + blockOwnedRouteExitEvidence
                + " delegatedBlockRouteExitEvidence=" + delegatedBlockRouteExitEvidence
                + " locationOwnedRouteExitEvidence=" + locationOwnedRouteExitEvidence
                + " noOwnerSerializedEvidence=" + noOwnerSerializedEvidence
                + " multiRegionSerializedEvidence=" + multiRegionSerializedEvidence
                + " multiRegionReadSplitEvidence=" + multiRegionReadSplitEvidence
                + " multiRegionMutationPlanEvidence=" + multiRegionMutationPlanEvidence
                + " multiRegionMutationContractEvidence=" + multiRegionMutationContractEvidence
                + " multiRegionMutationPrepareFailureEvidence=" + multiRegionMutationPrepareFailureEvidence
                + " multiRegionMutationVerifyFailureEvidence=" + multiRegionMutationVerifyFailureEvidence
                + " listenerConcurrencyEvidence=" + listenerConcurrencyEvidence);
    }

    private static String runCompatibilityLaneSmoke() {
        return SyntheticEventPathBridge.call("smoketest.SharedEvent", true, 3,
                "smoke-modeled-shared-event-path", () -> {
                    List<String> effects = new ArrayList<>();
                    if (!SyntheticEventPathBridge.isCompatibilityLaneThread()) {
                        throw new IllegalStateException("Synthetic event path did not enter compatibility lane");
                    }
                    SyntheticEventPathBridge.observeListener("smoketest.SharedEvent",
                            "smoketest.ListenerA", "LOWEST", "observe-before-mutation", false);
                    effects.add("begin-observed");
                    SyntheticEventPathBridge.observeListener("smoketest.SharedEvent",
                            "smoketest.ListenerB", "NORMAL", "mutate-cancelled", true);
                    effects.add("normal-mutated-cancelled");
                    SyntheticEventPathBridge.observeListener("smoketest.SharedEvent",
                            "smoketest.ListenerC", "MONITOR", "read-final-state", true);
                    effects.add("monitor-read");
                    if (!effects.equals(List.of("begin-observed", "normal-mutated-cancelled", "monitor-read"))) {
                        throw new IllegalStateException("Synthetic event lane ordering changed: " + effects);
                    }
                    return "synthetic-event-lane-sequence";
                });
    }

    private static String runSyntheticListenerFailureClassifierSmoke(Plugin plugin) {
        Listener listener = new Listener() {
        };
        EventExecutor executor = (ignored, event) -> {
            throw new EventException(new IllegalStateException(
                    "Thread failed main thread check: Accessing entity state off owning region's thread, "
                            + "context=[thread=Thread[#1,FBB-compatibility-lane,8,Folia Region Scheduler ThreadGroup],"
                            + "class=java.lang.Thread], entity={root=[{type=Arrow,id=1}]}"));
        };
        RegisteredListener registered = new RegisteredListener(
                listener, executor, EventPriority.NORMAL, plugin, false);
        HandlerList handlers = SmokeTarget.SmokeSharedEvent.getHandlerList();
        handlers.register(registered);
        try {
            SyntheticEventDispatchBridge.callEvent(fakePluginManager(), new SmokeTarget.SmokeSharedEvent());
            return "listener-failure-route-exit-classified";
        } finally {
            handlers.unregister(registered);
        }
    }

    private static String runSyntheticEntityOwnedRouteExitSmoke(Plugin plugin, Entity entity) {
        Listener listener = new Listener() {
        };
        List<String> effects = new ArrayList<>();
        EventExecutor executor = (ignored, event) -> {
            if (event instanceof SmokeTarget.SmokeEntityOwnedEvent ownedEvent
                    && ownedEvent.getEntity() == entity) {
                effects.add("entity-owned-listener");
            }
        };
        RegisteredListener registered = new RegisteredListener(
                listener, executor, EventPriority.NORMAL, plugin, false);
        HandlerList handlers = SmokeTarget.SmokeEntityOwnedEvent.getHandlerList();
        handlers.register(registered);
        System.setProperty("foliabytecodebridge.smokeCurrentEntityOwner", "true");
        try {
            SyntheticEventDispatchBridge.callEvent(fakePluginManager(), new SmokeTarget.SmokeEntityOwnedEvent(entity));
            if (!effects.equals(List.of("entity-owned-listener"))) {
                throw new IllegalStateException("Synthetic entity route-exit listener did not run: " + effects);
            }
            return "entity-owned-synthetic-route-exit";
        } finally {
            System.clearProperty("foliabytecodebridge.smokeCurrentEntityOwner");
            handlers.unregister(registered);
        }
    }

    private static String runSyntheticBlockOwnedRouteExitSmoke(Plugin plugin, Block block) {
        Listener listener = new Listener() {
        };
        List<String> effects = new ArrayList<>();
        EventExecutor executor = (ignored, event) -> {
            if (event instanceof SmokeTarget.SmokeBlockCollectionOwnedEvent ownedEvent
                    && ownedEvent.getBlocks().contains(block)) {
                effects.add("block-owned-listener");
            }
        };
        RegisteredListener registered = new RegisteredListener(
                listener, executor, EventPriority.NORMAL, plugin, false);
        HandlerList handlers = SmokeTarget.SmokeBlockCollectionOwnedEvent.getHandlerList();
        handlers.register(registered);
        System.setProperty("foliabytecodebridge.smokeCurrentBlockOwner", "true");
        try {
            SyntheticEventDispatchBridge.callEvent(fakePluginManager(),
                    new SmokeTarget.SmokeBlockCollectionOwnedEvent(List.of(block)));
            if (!effects.equals(List.of("block-owned-listener"))) {
                throw new IllegalStateException("Synthetic block route-exit listener did not run: " + effects);
            }
            return "block-owned-synthetic-route-exit";
        } finally {
            System.clearProperty("foliabytecodebridge.smokeCurrentBlockOwner");
            handlers.unregister(registered);
        }
    }

    private static String runSyntheticDelegatedBlockRouteExitSmoke(Plugin plugin, Block block) {
        Listener listener = new Listener() {
        };
        List<String> effects = new ArrayList<>();
        EventExecutor executor = (ignored, event) -> {
            if (event instanceof SmokeTarget.SmokeDelegatedBlockOwnedEvent delegated
                    && delegated.getOriginalEvent() instanceof SmokeTarget.SmokeSingleBlockOwnedEvent original
                    && original.getBlock() == block) {
                effects.add("delegated-block-owned-listener");
            }
        };
        RegisteredListener registered = new RegisteredListener(
                listener, executor, EventPriority.NORMAL, plugin, false);
        HandlerList handlers = SmokeTarget.SmokeDelegatedBlockOwnedEvent.getHandlerList();
        handlers.register(registered);
        System.setProperty("foliabytecodebridge.smokeCurrentBlockOwner", "true");
        try {
            SyntheticEventDispatchBridge.callEvent(fakePluginManager(),
                    new SmokeTarget.SmokeDelegatedBlockOwnedEvent(
                            new SmokeTarget.SmokeSingleBlockOwnedEvent(block)));
            if (!effects.equals(List.of("delegated-block-owned-listener"))) {
                throw new IllegalStateException("Synthetic delegated block route-exit listener did not run: " + effects);
            }
            return "delegated-block-owned-synthetic-route-exit";
        } finally {
            System.clearProperty("foliabytecodebridge.smokeCurrentBlockOwner");
            handlers.unregister(registered);
        }
    }

    private static String runSyntheticLocationOwnedRouteExitSmoke(Plugin plugin, Location location) {
        Listener listener = new Listener() {
        };
        List<String> effects = new ArrayList<>();
        EventExecutor executor = (ignored, event) -> {
            if (event instanceof SmokeTarget.SmokeLocationOwnedEvent ownedEvent
                    && ownedEvent.getLocation() == location) {
                effects.add("location-owned-listener");
            }
        };
        RegisteredListener registered = new RegisteredListener(
                listener, executor, EventPriority.NORMAL, plugin, false);
        HandlerList handlers = SmokeTarget.SmokeLocationOwnedEvent.getHandlerList();
        handlers.register(registered);
        System.setProperty("foliabytecodebridge.smokeCurrentLocationOwner", "true");
        try {
            SyntheticEventDispatchBridge.callEvent(fakePluginManager(),
                    new SmokeTarget.SmokeLocationOwnedEvent(location));
            if (!effects.equals(List.of("location-owned-listener"))) {
                throw new IllegalStateException("Synthetic location route-exit listener did not run: " + effects);
            }
            return "location-owned-synthetic-route-exit";
        } finally {
            System.clearProperty("foliabytecodebridge.smokeCurrentLocationOwner");
            handlers.unregister(registered);
        }
    }

    private static String runSyntheticNoOwnerSerializedSmoke() {
        SyntheticEventDispatchBridge.callEvent(fakePluginManager(), new SmokeTarget.SmokeNoOwnerEvent());
        return "no-owner-stayed-serialized";
    }

    private static String runSyntheticMultiRegionSerializedSmoke(World world) {
        Block first = fakeBlock(world, 12, 64, 12);
        Block second = fakeBlock(world, 48, 64, 12);
        SyntheticEventDispatchBridge.callEvent(fakePluginManager(),
                new SmokeTarget.SmokeMultiBlockCollectionOwnedEvent(List.of(first, second)));
        return "multi-region-blocks-stayed-serialized";
    }

    private static String runSyntheticMultiRegionReadSplitSmoke(World world) {
        Block first = fakeBlock(world, 12, 64, 12);
        Block second = fakeBlock(world, 48, 64, 12);
        System.setProperty("foliabytecodebridge.smokeSyntheticReadSplit", "true");
        try {
            SyntheticEventDispatchBridge.callEvent(fakePluginManager(),
                    new SmokeTarget.SmokeReadOnlyMultiBlockCollectionOwnedEvent(List.of(first, second)));
            return "multi-region-read-only-split-aggregated";
        } finally {
            System.clearProperty("foliabytecodebridge.smokeSyntheticReadSplit");
        }
    }

    private static String runSyntheticMultiRegionMutationPlanSmoke(World world) {
        Block first = fakeBlock(world, 12, 64, 12);
        Block second = fakeBlock(world, 48, 64, 12);
        SyntheticEventDispatchBridge.callEvent(fakePluginManager(),
                new SmokeTarget.SmokeMutationMultiBlockCollectionOwnedEvent(List.of(first, second)));
        return "multi-region-mutation-planned-not-executed";
    }

    private static String runSyntheticMultiRegionMutationContractSmoke(World world) {
        Block first = fakeBlock(world, 12, 64, 12);
        Block second = fakeBlock(world, 48, 64, 12);
        SmokeTarget.SmokeContractMultiBlockCollectionOwnedEvent event =
                new SmokeTarget.SmokeContractMultiBlockCollectionOwnedEvent(List.of(first, second));
        System.setProperty("foliabytecodebridge.smokeSyntheticMutationExecutor", "true");
        try {
            SyntheticEventDispatchBridge.callEvent(fakePluginManager(), event);
            if (event.mutationEffects().size() != 4) {
                throw new IllegalStateException("Expected prepare/apply/apply/verify effects, got "
                        + event.mutationEffects());
            }
            return "multi-region-mutation-contract-ready-and-executed effects="
                    + String.join(",", event.mutationEffects());
        } finally {
            System.clearProperty("foliabytecodebridge.smokeSyntheticMutationExecutor");
        }
    }

    private static String runSyntheticMultiRegionMutationPrepareFailureSmoke(World world) {
        Block first = fakeBlock(world, 12, 64, 12);
        Block second = fakeBlock(world, 48, 64, 12);
        SmokeTarget.SmokeContractMultiBlockCollectionOwnedEvent event =
                new SmokeTarget.SmokeContractMultiBlockCollectionOwnedEvent(List.of(first, second), true, false);
        System.setProperty("foliabytecodebridge.smokeSyntheticMutationExecutor", "true");
        try {
            SyntheticEventDispatchBridge.callEvent(fakePluginManager(), event);
            if (!event.mutationEffects().equals(List.of("prepare"))) {
                throw new IllegalStateException("Prepare-failure path should stop after prepare, got "
                        + event.mutationEffects());
            }
            return "multi-region-mutation-prepare-failure-blocked effects="
                    + String.join(",", event.mutationEffects());
        } finally {
            System.clearProperty("foliabytecodebridge.smokeSyntheticMutationExecutor");
        }
    }

    private static String runSyntheticMultiRegionMutationVerifyFailureSmoke(World world) {
        Block first = fakeBlock(world, 12, 64, 12);
        Block second = fakeBlock(world, 48, 64, 12);
        SmokeTarget.SmokeContractMultiBlockCollectionOwnedEvent event =
                new SmokeTarget.SmokeContractMultiBlockCollectionOwnedEvent(List.of(first, second), false, true);
        System.setProperty("foliabytecodebridge.smokeSyntheticMutationExecutor", "true");
        try {
            SyntheticEventDispatchBridge.callEvent(fakePluginManager(), event);
            if (event.mutationEffects().size() != 4) {
                throw new IllegalStateException("Verify-failure path should prepare/apply/apply/verify, got "
                        + event.mutationEffects());
            }
            return "multi-region-mutation-verify-failure-blocked effects="
                    + String.join(",", event.mutationEffects());
        } finally {
            System.clearProperty("foliabytecodebridge.smokeSyntheticMutationExecutor");
        }
    }

    private static String runServerMemberMapSmoke() {
        NmsCompatModel.Failure exact = new NmsCompatModel.Failure("NoSuchMethodError",
                "smoketest.SmokeMain", "main", "([Ljava/lang/String;)V",
                "method", "smoke");
        ServerMemberMap.Result exactResult = ServerMemberMap.inspectClassBytes(
                "smoke-test", classBytes("smoketest/SmokeMain.class"), exact, 4);
        if (!exactResult.classFound() || !exactResult.exactMatch()) {
            throw new IllegalStateException("Server member map did not find exact smoke method: "
                    + exactResult.toEvidenceLine());
        }

        NmsCompatModel.Failure missing = new NmsCompatModel.Failure("NoSuchFieldError",
                "smoketest.SmokeMain", "currentTick", "I",
                "field", "smoke");
        ServerMemberMap.Result missingResult = ServerMemberMap.inspectClassBytes(
                "smoke-test", classBytes("smoketest/SmokeMain.class"), missing, 4);
        requireFragment(missingResult.toEvidenceLine(), "exactMatch=false");
        requireFragment(missingResult.toEvidenceLine(), "next=map-safe-equivalent-before-bytecode-adapter");
        return "2";
    }

    private static String runNmsCompatModelSmoke() {
        List<String> lines = List.of(
                "[Server thread/ERROR]: Error occurred while enabling protection plugin reference v7.0.16+2355-f7fded2 (Is it up to date?)",
                "java.lang.NoSuchFieldError: Class net.minecraft.server.MinecraftServer does not have member field 'int currentTick'",
                "\tat world-editing-plugin-reference.jar//com.example.serveradapter.NativeWorldAccess.<init>(NativeWorldAccess.java:65) ~[?:?]"
        );
        List<NmsCompatModel.Report> reports = NmsCompatModel.fromLogLines(lines);
        if (reports.size() != 1) {
            throw new IllegalStateException("Expected one NMS compatibility report, got " + reports.size());
        }
        String evidence = reports.get(0).toEvidenceLine();
        requireFragment(evidence, "category=NMS_VERSION_COMPAT");
        requireFragment(evidence, "throwable=NoSuchFieldError");
        requireFragment(evidence, "owner=net.minecraft.server.MinecraftServer");
        requireFragment(evidence, "name=currentTick");
        requireFragment(evidence, "descriptor=I");
        requireFragment(evidence, "pluginJar=world-editing-plugin-reference.jar");
        requireFragment(evidence, "caller=com.example.serveradapter.NativeWorldAccess#<init>");

        NullPointerException executorFailure = new NullPointerException(
                "Cannot read field \"world\" because the return value of "
                        + "\"io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegionizedWorldData()\" is null");
        executorFailure.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("net.minecraft.server.level.ServerChunkCache$MainThreadExecutor",
                        "pollTask", "ServerChunkCache.java", 918),
                new StackTraceElement("smoketest.ServerExecutorFixture", "run", "ServerExecutorFixture.java", 42)
        });
        NmsCompatModel.ExecutorContextReport executorReport = NmsCompatModel
                .executorContextFromThrowable("MCUtil.MAIN_EXECUTOR#execute",
                        "smoketest.ServerExecutorFixture#submit(ServerExecutorFixture.java:12)",
                        executorFailure)
                .orElseThrow(() -> new IllegalStateException("Expected NMS executor context report"));
        String executorEvidence = executorReport.toEvidenceLine();
        requireFragment(executorEvidence, "category=NMS_EXECUTOR_CONTEXT");
        requireFragment(executorEvidence, "model=SERVER_EXECUTOR_CONTEXT");
        requireFragment(executorEvidence, "previousRoute=S_GLOBAL");
        requireFragment(executorEvidence, "result=owner-context-missing");
        requireFragment(executorEvidence, "next=derive-world-or-chunk-owner-before-promoting-executor-route");
        return "version=1,executor=1";
    }

    private static void requireFragment(String value, String fragment) {
        if (!value.contains(fragment)) {
            throw new IllegalStateException("Missing fragment " + fragment + " in " + value);
        }
    }

    private static int assertGeneratedRouteRuleRegistrySmoke() {
        try {
            Class<?> registry = Class.forName("dev.foliabytecodebridge.RouteRuleRegistry");
            Method rulesMethod = registry.getDeclaredMethod("rules");
            rulesMethod.setAccessible(true);
            Collection<?> rules = (Collection<?>) rulesMethod.invoke(null);
            if (rules.size() < 50) {
                throw new IllegalStateException("Route rule registry lost coverage; rules=" + rules.size());
            }

            for (Object rule : rules) {
                Class<?> ruleClass = rule.getClass();
                String owner = invokeString(ruleClass, rule, "owner");
                String name = invokeString(ruleClass, rule, "name");
                String descriptor = invokeString(ruleClass, rule, "descriptor");
                String bridgeMethod = invokeString(ruleClass, rule, "bridgeMethod");
                String bridgeDescriptor = invokeString(ruleClass, rule, "bridgeDescriptor");
                Object routeFamily = invoke(ruleClass, rule, "routeFamily");
                Object returnPolicy = invoke(ruleClass, rule, "returnPolicy");
                Object status = invoke(ruleClass, rule, "status");
                boolean rewrites = (Boolean) invoke(ruleClass, rule, "rewrites");
                if (owner.isBlank() || name.isBlank() || descriptor.isBlank()
                        || routeFamily == null || returnPolicy == null || status == null) {
                    throw new IllegalStateException("Incomplete route rule: " + rule);
                }
                if (rewrites) {
                    if (bridgeMethod.isBlank() || bridgeDescriptor.isBlank()) {
                        throw new IllegalStateException("Rewriting route rule lacks bridge target: " + rule);
                    }
                    requireBridgeMethodName(bridgeMethod);
                }
            }
            return rules.size();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Route rule registry smoke failed", e);
        }
    }

    private static Object invoke(Class<?> type, Object target, String methodName) throws ReflectiveOperationException {
        Method method = type.getDeclaredMethod(methodName);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static String invokeString(Class<?> type, Object target, String methodName) throws ReflectiveOperationException {
        Object value = invoke(type, target, methodName);
        return value == null ? "" : value.toString();
    }

    private static void requireBridgeMethodName(String methodName) {
        Class<?>[] bridgeTypes = { UnsafeCallBridge.class, ServerExecutorBridge.class, ObjectSchedulerBridge.class };
        for (Class<?> bridgeType : bridgeTypes) {
            for (Method method : bridgeType.getDeclaredMethods()) {
                if (method.getName().equals(methodName)) return;
            }
        }
        throw new IllegalStateException("Route rule bridge method not found on known bridge classes: " + methodName);
    }

    private static InstructionRouteScanner.RouteReport runInstructionRouteScannerSmoke() {
        byte[] bytes = classBytes("smoketest/SmokeTarget.class");
        InstructionRouteScanner.RouteReport report = InstructionRouteScanner.scan(bytes, "smoketest/SmokeTarget.class");
        requireRoute(report, RouteFamily.A_ENTITY, "org/bukkit/entity/Player", "addPotionEffect",
                "(Lorg/bukkit/potion/PotionEffect;)Z");
        requireRoute(report, RouteFamily.A_ENTITY, "org/bukkit/entity/Player", "removePotionEffect",
                "(Lorg/bukkit/potion/PotionEffectType;)V");
        requireRoute(report, RouteFamily.C_REGION_BLOCK, "org/bukkit/block/Block", "getType",
                "()Lorg/bukkit/Material;");
        requireRoute(report, RouteFamily.C_REGION_BLOCK, "org/bukkit/block/Block", "getBlockData",
                "()Lorg/bukkit/block/data/BlockData;");
        requireRoute(report, RouteFamily.C_REGION_BLOCK, "org/bukkit/block/Block", "setType",
                "(Lorg/bukkit/Material;)V");
        requireRoute(report, RouteFamily.G_WORLD_SCAN_SPLIT, "org/bukkit/World", "getEntities",
                "()Ljava/util/List;");
        requireRoute(report, RouteFamily.D_PLAYER_UI, "org/bukkit/scoreboard/ScoreboardManager", "getNewScoreboard",
                "()Lorg/bukkit/scoreboard/Scoreboard;");
        requireRoute(report, RouteFamily.S_GLOBAL, "org/bukkit/Bukkit", "dispatchCommand",
                "(Lorg/bukkit/command/CommandSender;Ljava/lang/String;)Z");
        if (report.count(RouteFamily.A_ENTITY) == 0 || report.count(RouteFamily.D_PLAYER_UI) == 0) {
            throw new IllegalStateException("ASM route scanner did not produce useful route coverage: " + report.summary());
        }
        return report;
    }

    private static void requireRoute(InstructionRouteScanner.RouteReport report, RouteFamily routeFamily,
                                     String owner, String name, String descriptor) {
        if (!report.contains(routeFamily, owner, name, descriptor)) {
            throw new IllegalStateException("ASM route scanner missed " + routeFamily.label()
                    + " " + owner + "#" + name + descriptor + " in " + report.summary());
        }
    }

    private static byte[] classBytes(String resourceName) {
        try (InputStream input = SmokeMain.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IllegalStateException("Missing smoke class resource: " + resourceName);
            }
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read smoke class resource: " + resourceName, exception);
        }
    }

    private static void runMetadataGateProbe() {
        PluginDescriptionFile description = new PluginDescriptionFile(
                "SmokeMetadataPlugin", "1.0.0", "smoketest.PluginMain");
        if (!description.isFoliaSupported()) {
            throw new IllegalStateException("metadataOverlay=all did not patch PluginDescriptionFile#isFoliaSupported");
        }
    }

    private static void runTaskFailureProbe(Plugin plugin) {
        System.setProperty("foliabytecodebridge.smokeRunTasks", "true");
        try {
            SchedulerBridge.runTask(fakeScheduler(), plugin, () -> {
                throw new IllegalStateException("smoke task failure");
            });
        } catch (IllegalStateException expected) {
        } finally {
            System.clearProperty("foliabytecodebridge.smokeRunTasks");
        }
    }

    private static BukkitScheduler fakeScheduler() {
        return (BukkitScheduler) Proxy.newProxyInstance(
                SmokeMain.class.getClassLoader(),
                new Class<?>[]{BukkitScheduler.class},
                schedulerHandler());
    }

    private static Plugin fakePlugin() {
        return (Plugin) Proxy.newProxyInstance(
                SmokeMain.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> {
                    if ("getName".equals(method.getName())) return "SmokePlugin";
                    return defaultValue(method.getReturnType());
                });
    }

    private static PluginManager fakePluginManager() {
        return proxy(PluginManager.class, (proxy, method, args) -> defaultValue(method.getReturnType()));
    }

    private static ScoreboardManager fakeScoreboardManager() {
        return (ScoreboardManager) Proxy.newProxyInstance(SmokeMain.class.getClassLoader(),
                new Class<?>[]{ScoreboardManager.class},
                (proxy, method, args) -> {
                    if ("getNewScoreboard".equals(method.getName())) {
                        return proxy(org.bukkit.scoreboard.Scoreboard.class, SmokeMain::scoreboardHandler);
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Object scoreboardHandler(Object proxy, Method method, Object[] args) {
        if ("registerNewTeam".equals(method.getName())) {
            return proxy(org.bukkit.scoreboard.Team.class,
                    (teamProxy, teamMethod, teamArgs) -> defaultValue(teamMethod.getReturnType()));
        }
        if ("registerNewObjective".equals(method.getName())) {
            return proxy(org.bukkit.scoreboard.Objective.class,
                    (objectiveProxy, objectiveMethod, objectiveArgs) -> {
                        if ("getScore".equals(objectiveMethod.getName())) {
                            return proxy(org.bukkit.scoreboard.Score.class,
                                    (scoreProxy, scoreMethod, scoreArgs) -> defaultValue(scoreMethod.getReturnType()));
                        }
                        return defaultValue(objectiveMethod.getReturnType());
                    });
        }
        return defaultValue(method.getReturnType());
    }

    private static Player fakePlayer(World world) {
        return proxy(Player.class, (proxy, method, args) -> {
            if ("getName".equals(method.getName())) return "SmokePlayer";
            if ("getDisplayName".equals(method.getName())) return "SmokePlayer";
            if ("getUniqueId".equals(method.getName())) return UUID.nameUUIDFromBytes("SmokePlayer".getBytes());
            if ("getWorld".equals(method.getName())) return world;
            if ("getLocation".equals(method.getName())) return new Location(world, 10, 64, 10);
            return defaultValue(method.getReturnType());
        });
    }

    private static World fakeWorld() {
        return proxy(World.class, (proxy, method, args) -> {
            if ("getName".equals(method.getName())) return "smoke_world";
            return defaultValue(method.getReturnType());
        });
    }

    private static Block fakeBlock(World world) {
        return fakeBlock(world, 12, 64, 12);
    }

    private static Block fakeBlock(World world, int x, int y, int z) {
        return proxy(Block.class, (proxy, method, args) -> {
            if ("getWorld".equals(method.getName())) return world;
            if ("getLocation".equals(method.getName())) return new Location(world, x, y, z);
            if ("getX".equals(method.getName())) return x;
            if ("getY".equals(method.getName())) return y;
            if ("getZ".equals(method.getName())) return z;
            return defaultValue(method.getReturnType());
        });
    }

    private static InvocationHandler schedulerHandler() {
        return (proxy, method, args) -> {
            Class<?> returnType = method.getReturnType();
            if (returnType == BukkitTask.class) return fakeTask();
            if (returnType == Future.class) return CompletableFuture.completedFuture("ok");
            return defaultValue(returnType);
        };
    }

    private static BukkitTask fakeTask() {
        return (BukkitTask) Proxy.newProxyInstance(
                SmokeMain.class.getClassLoader(),
                new Class<?>[]{BukkitTask.class},
                (proxy, method, args) -> defaultValue(method.getReturnType()));
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(
                SmokeMain.class.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> {
                    if ("toString".equals(method.getName())) return "SmokeProxy(" + type.getSimpleName() + ")";
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return proxy == args[0];
                    return handler.invoke(proxy, method, args);
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == Void.TYPE) return null;
        if (type == Boolean.TYPE) return false;
        if (type == Byte.TYPE) return (byte) 0;
        if (type == Short.TYPE) return (short) 0;
        if (type == Integer.TYPE) return 0;
        if (type == Long.TYPE) return 0L;
        if (type == Float.TYPE) return 0F;
        if (type == Double.TYPE) return 0D;
        if (type == Character.TYPE) return (char) 0;
        if (type == String.class) return "";
        if (type == UUID.class) return UUID.nameUUIDFromBytes("SmokeDefault".getBytes());
        if (type == Location.class) return new Location(fakeWorld(), 0, 64, 0);
        if (type == World.class) return fakeWorld();
        if (type == Chunk.class) return proxy(Chunk.class, (proxy, method, args) -> defaultValue(method.getReturnType()));
        if (type == Item.class) return proxy(Item.class, (proxy, method, args) -> defaultValue(method.getReturnType()));
        if (type == LightningStrike.class) return proxy(LightningStrike.class, (proxy, method, args) -> defaultValue(method.getReturnType()));
        if (type == Player.class) return fakePlayer(fakeWorld());
        if (type == java.util.Collection.class || type == java.util.List.class) return Collections.emptyList();
        if (type == java.util.Map.class) return Collections.emptyMap();
        if (type.isInterface()) return proxy(type, (proxy, method, args) -> defaultValue(method.getReturnType()));
        return null;
    }

    private static Path[] pluginJars() {
        String configured = System.getProperty("foliabytecodebridge.deepPluginJars", "").trim();
        if (configured.isEmpty()) return new Path[0];
        return Arrays.stream(configured.split(";"))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(Path::of)
                .toArray(Path[]::new);
    }

    private static final class LogCapture extends Handler {
        private static final Pattern ROUTE_PATTERN = Pattern.compile("\\broute=([A-Z_]+)\\b");

        private final List<String> messages = new ArrayList<>();

        static LogCapture install() {
            Logger logger = Logger.getLogger("FoliaBytecodeBridge");
            LogCapture capture = new LogCapture();
            capture.setLevel(Level.ALL);
            logger.addHandler(capture);
            logger.setLevel(Level.ALL);
            return capture;
        }

        @Override
        public void publish(LogRecord record) {
            messages.add(record.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        void require(String tag, RouteFamily... routes) {
            EnumSet<RouteFamily> missing = EnumSet.noneOf(RouteFamily.class);
            Collections.addAll(missing, routes);
            for (String message : messages) {
                if (!message.contains(tag)) continue;
                Matcher matcher = ROUTE_PATTERN.matcher(message);
                if (matcher.find()) {
                    missing.remove(RouteFamily.valueOf(matcher.group(1)));
                }
            }
            if (!missing.isEmpty()) {
                throw new IllegalStateException("Missing log routes for " + tag + ": " + missing);
            }
        }

        void assertOnlyOfficialRoutes() {
            for (String message : messages) {
                if (!message.contains("[FBB ")) continue;
                Matcher matcher = ROUTE_PATTERN.matcher(message);
                while (matcher.find()) {
                    RouteFamily.valueOf(matcher.group(1));
                }
            }
        }

        void requireContains(String tag, String... fragments) {
            for (String message : messages) {
                if (!message.contains(tag)) continue;
                boolean matches = true;
                for (String fragment : fragments) {
                    if (!message.contains(fragment)) {
                        matches = false;
                        break;
                    }
                }
                if (matches) return;
            }
            throw new IllegalStateException("Missing log line for " + tag + " containing " + Arrays.toString(fragments));
        }
    }
}
