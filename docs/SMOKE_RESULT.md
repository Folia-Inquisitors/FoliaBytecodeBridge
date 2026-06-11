# Smoke Test Result

## 2026-06-11 - NMS Owner Candidate Trace

Build marker:

```text
2026-06-11-nms-owner-candidate-trace
```

The NMS executor lane now records candidate owner clues while scanning
server-internal runnables. In addition to direct `Location`, `Chunk`, `Entity`,
`BlockPos`, and `ChunkPos` clues, the extractor can promote a runnable-local
captured world plus exactly two captured integer fields into a chunk owner. This
targets generic executor-lambda bytecode shapes where the runnable carries the
world and chunk coordinates together.

Owner misses now include `clueTrail=...` so a future pass can see which fields
were inspected before the route stayed `no-owner-contract`. Repeated task
failures remain fully preserved in `debug.log`, while console output is
repeat-aware.

Smoke evidence:

```text
SMOKE_OK ... routeRules=132 ... nmsCompatibilityLaneEvidence=owner-found,captured-world-int-pair,owner-miss,lane-context ...
```

## 2026-06-10 - Contract Language Cleanup

Build marker:

```text
2026-06-10-contract-language-cleanup
```

Clarified pathfinding language without changing route behavior. Unknown shared
event paths now report `no-owner-contract` and `serialized-unproven` instead of
older broad wording. Synthetic multi-region contract probes report
`contract-missing`, `contract-disabled`, or `contract-rejected` when the bridge
does not execute/promote a contract path. Hot entity/player read routes report
`policy=entity-owner-read-return`.

## 2026-06-10 - Architecture Decision Markers

Build marker:

```text
2026-06-10-architecture-decision-markers
```

Added stable `marker=FBB_ARCH_..._V1` IDs to
`[FBB architecture-decision]` lines. The human `path=...` value remains, while
the marker gives log tools an exact string to filter across runs.

Smoke verified the requested markers:

```text
FBB_ARCH_DECISION_SUMMARY_V1
FBB_ARCH_OWNER_EXTRACT_V1
FBB_ARCH_RETURN_RISK_V1
FBB_ARCH_ROUTE_EXIT_V1
FBB_ARCH_STAY_SERIALIZED_V1
FBB_ARCH_POLICY_BLOCKED_V1
FBB_ARCH_PROMOTION_EVIDENCE_V1
FBB_ARCH_HELPER_VISIBILITY_V1
```

## 2026-06-10 - Architecture Decision Traces

Build marker:

```text
2026-06-10-architecture-decision-traces
```

Expanded `architecture-pathfinding.debug` with compact
`[FBB architecture-decision]` summaries. These are trace-only breadcrumbs and
do not change route behavior.

Smoke verified all requested decision paths:

```text
stage=decision/summary
stage=decision/owner/extract
stage=decision/return/sync-risk
stage=decision/route/exit
stage=decision/route/stay-serialized
stage=decision/policy/blocked
stage=decision/promotion/evidence
stage=decision/helper/visibility
```

Smoke result:

```text
SMOKE_OK routeRules=132 asmRouteHits=143 listenerConcurrencyEvidence=synthetic-listener-concurrency-reentry
```

## 2026-06-10 - Architecture Pathfinding Debug

Build marker:

```text
2026-06-10-architecture-pathfinding-debug
```

Added `plugins/FoliaBytecodeBridge/architecture-pathfinding.debug` as a
focused decision-path timeline. It does not replace `debug.log`; it copies
relevant architecture evidence and tags each line with a stage such as
`boot/agent-attach`, `bytecode/prescan`, `bytecode/rewrite-result`,
`model/route-rule`, `runtime/unsafe-call-route`, `synthetic/event-state`,
`synthetic/multi-region`, or `compat/nms-shape`.

Smoke verified that the file is created and contains staged route-thinking
lines:

```text
stage=bytecode/rewrite-result [FBB transform] ...
stage=model/route-rule [FBB model] ...
```

Smoke result:

```text
SMOKE_OK routeRules=132 asmRouteHits=143 listenerConcurrencyEvidence=synthetic-listener-concurrency-reentry
```

## 2026-06-05 - Foundation Route Registry Cleanup

Build marker:

```text
2026-06-05-foundation-route-registry
```

This pass promoted common scheduler, runnable, entity, player visibility,
location, block, sound, and world-location shapes into `RouteRuleRegistry` so
runtime evidence can normalize back to the central route model instead of
falling through as `dynamic-or-unregistered`.

It also tightened the diagnostics policy:

- debug file keeps every route decision, transform skip, candidate scan,
  repeat summary, synthetic event line, and failure marker
- console keeps boot markers, important failures, and compact summaries unless
  `consoleVerbose=true`
- deliberate synthetic listener probe failures are debug-file evidence by
  default, not console spam

Smoke result:

```text
SMOKE_OK routeRules=132 asmRouteHits=143 listenerConcurrencyEvidence=synthetic-listener-concurrency-reentry multiRegionMutationContractEvidence=multi-region-mutation-contract-ready-and-executed
```

Remaining dynamic evidence is intentional or still research-shaped: shaded
helper runtime aliases, task-failure labels, repeat diagnostics, and legacy
server-shape compatibility probes.

Packaging follow-up:

```text
java -javaagent:target\folia-bytecode-bridge-0.1.1-experimental.3.jar -version
```

reaches `[FBB attach]` after ensuring Byte Buddy is bundled under
`net/bytebuddy/...` inside the agent jar. The smoke rerun after that packaging
fix still reports:

```text
SMOKE_OK routeRules=132 asmRouteHits=143 listenerConcurrencyEvidence=synthetic-listener-concurrency-reentry
```

## 2026-06-02 - Synthetic Multi-Region Mutation Executor Phase 5B

Added a guarded executor for exact synthetic multi-region mutation contracts.
The executor is disabled by default on live servers with
`syntheticMutationExecutor=false`; disabled paths stay serialized and emit
`reason=executor-disabled` instead of pretending the mutation is safe.

Smoke enables only the deterministic inline test property:

```text
foliabytecodebridge.smokeSyntheticMutationExecutor=true
```

Expected evidence:

```text
[FBB synthetic-multi-region] phase=execute-mutation event=smoketest.SmokeTarget$SmokeContractMultiBlockCollectionOwnedEvent route=C_REGION_BLOCK result=completed reason=verified scheduledOwners=2 completedOwners=2 prepareHook=prepareMutation applyHook=applyOwnerMutation verifyHook=verifyAggregateMutation mode=smoke-inline
```

The smoke event records hook effects so the test proves prepare/apply/apply/
verify ordering:

```text
multiRegionMutationContractEvidence=multi-region-mutation-contract-ready-and-executed effects=prepare,apply:12,12,apply:48,12,verify:2/2
```

Smoke also covers negative exact-contract outcomes:

```text
[FBB synthetic-multi-region] phase=execute-mutation result=contract-rejected reason=prepare-returned-false prepareHook=prepareMutation
[FBB synthetic-multi-region] phase=execute-mutation result=contract-rejected reason=verify-returned-false scheduledOwners=2 completedOwners=2 verifyHook=verifyAggregateMutation mode=smoke-inline
```

Those checks prove the executor does not silently continue after a failed
prepare or failed aggregate verify.

This is still not a generic multi-region write solution. Missing hooks, false
verification, absent owner anchors, live scheduling failures, and timeouts
remain visible under `[FBB synthetic-multi-region] phase=execute-mutation`.

Live throwaway-server test with `syntheticMutationExecutor=true`:

```text
[FBB synthetic-multi-region] phase=execute-mutation event=dev.fbbprobe.harness.SharedBlockCollectionProbeEvent route=C_REGION_BLOCK result=scheduled scheduledOwners=2 prepareHook=prepareMutation applyHook=applyOwnerMutation verifyHook=verifyAggregateMutation action=owner-apply-tasks mode=nonblocking
[FBB synthetic-multi-region] phase=execute-mutation event=dev.fbbprobe.harness.SharedBlockCollectionProbeEvent route=C_REGION_BLOCK result=completed reason=verified scheduledOwners=2 completedOwners=2 prepareHook=prepareMutation applyHook=applyOwnerMutation verifyHook=verifyAggregateMutation action=two-phase-mutation-executor mode=nonblocking
```

The live path proved the guarded executor can leave the serialized synthetic
model for an exact contract and schedule per-owner region work without blocking
the caller. The noisy raw Folia failures in the same `latest.log` were from
`FBBProbeControl`, which is the expected untransformed baseline.

## 2026-06-02 - Synthetic Multi-Region Detection Phase 1

Added detection-only evidence for synthetic custom events that expose more than
one block/chunk owner through a block collection. The route is not promoted yet:
the event remains serialized, and the new line records owner-set evidence for
future split/read or two-phase mutation modeling.

Expected evidence:

```text
[FBB synthetic-multi-region] phase=detect event=smoketest.SmokeTarget$SmokeMultiBlockCollectionOwnedEvent route=C_REGION_BLOCK owners=2 result=observed-not-promoted
[FBB synthetic-event-state] action=serialized event=smoketest.SmokeTarget$SmokeMultiBlockCollectionOwnedEvent
[FBB synthetic-owner-miss] event=smoketest.SmokeTarget$SmokeMultiBlockCollectionOwnedEvent ... getBlocks:multi-region-collection
```

This is phase 1 only: no region freeze, no split/aggregate, and no multi-region
mutation routing.

## 2026-06-02 - Synthetic Listener Concurrency Phase 5A

The synthetic listener dispatcher now wraps listener execution with a re-entry
detector. Smoke overlaps the same synthetic event/listener key on two threads
and expects:

```text
[FBB synthetic-concurrency] phase=5A action=reentered event=smoketest.SyntheticConcurrencyEvent route=none routeFamily=UNKNOWN result=compatibility-sensitive
```

This is detection only. It marks the path as compatibility-sensitive evidence;
it does not promote a route and does not silence listener failures.

Follow-up hardening on 2026-06-02 moved smoke through
`SyntheticEventPathBridge#probeListenerReentry(...)`, the same diagnostic-only
hook used by the live target probe. Smoke now also checks:

```text
activePath=diagnostic-probe:smoke-phase-5a
currentPath=diagnostic-probe:smoke-phase-5a
```

That proves the log points at both the already-active listener path and the
current re-entry path.

## 2026-06-02 - Synthetic Multi-Region Read Split Phase 2

Read-only synthetic multi-region events now get a narrow owner read pass. Smoke
uses a multi-block collection event with `isReadOnly() == true` and expects:

```text
[FBB synthetic-multi-region] phase=split-read event=smoketest.SmokeTarget$SmokeReadOnlyMultiBlockCollectionOwnedEvent route=C_REGION_BLOCK operation=read-only readOnly=true result=aggregated completedOwners=2
```

The listener chain is not replayed per region. Unknown multi-block events still
emit `operation=read-or-write-unknown` and remain serialized.

## 2026-06-02 - Delegated Original Event Owner Route

Custom wrapper events can now borrow an owner from `getOriginalEvent()` when the
original event exposes a clear entity, block, block collection, or location
owner. This is intended for generic wrapper/delegate event shapes. `getWorld()`
alone is still not promoted because it does not identify a Folia region owner.

Expected evidence:

```text
[FBB synthetic-event-state] action=route-exit event=smoketest.SmokeTarget$SmokeDelegatedBlockOwnedEvent route=C_REGION_BLOCK ownerMethod=delegate:getOriginalEvent.getBlock
[FBB synthetic-event-route-exit] action=current-owner event=smoketest.SmokeTarget$SmokeDelegatedBlockOwnedEvent route=C_REGION_BLOCK ownerMethod=delegate:getOriginalEvent.getBlock
[FBB synthetic-listener-route-exit] event=smoketest.SmokeTarget$SmokeDelegatedBlockOwnedEvent route=C_REGION_BLOCK
```

Smoke verification now appends:

```text
delegatedBlockRouteExitEvidence=delegated-block-owned-synthetic-route-exit
```

## 2026-06-02 - Synthetic Wrapper State And Owner Miss Evidence

Added `SyntheticEventPathState` evidence so custom sync event dispatch reports
whether the wrapper is scanning, exiting through a known owner route, or keeping
the path serialized. Unknown/unproven shapes now use `route=none` plus
`routeFamily=UNKNOWN` so they do not pollute the official `RouteFamily` map.

Expected evidence:

```text
[FBB synthetic-event-state] action=scan-start route=none ownerStatus=scanning
[FBB synthetic-event-state] action=route-exit route=A_ENTITY ownerStatus=owner-found
[FBB synthetic-event-state] action=serialized route=none ownerStatus=no-owner-contract
[FBB synthetic-owner-miss] route=none routeFamily=UNKNOWN ... no-compatible-owner-getter
[FBB synthetic-owner-miss] route=none routeFamily=UNKNOWN ... getBlocks:multi-region-collection
[FBB synthetic-listener-route-exit] route=A_ENTITY ... listener=SmokePlugin/...
```

Smoke command:

```text
java -Dfoliabytecodebridge.forceNonFolia=true -Dfoliabytecodebridge.metadataOverlay=all -Dfoliabytecodebridge.smokeNoPassthrough=true -Dfoliabytecodebridge.debug=true -Dfoliabytecodebridge.deepPluginJars=target\FBBProbe.jar;target\FBBProbeControl.jar -javaagent:target\FoliaBytecodeBridge.jar -cp <smoke classpath> smoketest.SmokeMain
```

Result:

```text
SMOKE_OK bridgeCalls=22 unsafeCalls=176 ... entityOwnedRouteExitEvidence=entity-owned-synthetic-route-exit blockOwnedRouteExitEvidence=block-owned-synthetic-route-exit locationOwnedRouteExitEvidence=location-owned-synthetic-route-exit noOwnerSerializedEvidence=no-owner-stayed-serialized multiRegionSerializedEvidence=multi-region-blocks-stayed-serialized
```

## 2026-06-02 - Block And Location Synthetic Event Route Exits

Custom sync events with clear block or location owners can now leave the
synthetic compatibility lane through region ownership before their listener
chain runs. The block smoke uses a `getBlocks()` collection shape because live
custom block events commonly expose a block list rather than a single
`getBlock()` method. The extractor only promotes a block collection when all
blocks share the same chunk anchor; otherwise the event remains in the
synthetic lane.

Expected route-exit evidence:

```text
[FBB synthetic-event-route-exit] action=current-owner event=smoketest.SmokeTarget$SmokeBlockCollectionOwnedEvent route=C_REGION_BLOCK family=region next=listener-block-owner-exit ownerMethod=getBlocks path=direct-current-owner
[FBB synthetic-event-route-exit] action=current-owner event=smoketest.SmokeTarget$SmokeLocationOwnedEvent route=B_REGION_LOCATION family=region next=listener-location-owner-exit ownerMethod=getLocation path=direct-current-owner
```

Smoke verification now appends:

```text
blockOwnedRouteExitEvidence=block-owned-synthetic-route-exit locationOwnedRouteExitEvidence=location-owned-synthetic-route-exit
```

## 2026-06-02 - Entity-Owned Synthetic Event Route Exit

Custom sync events with a clear entity owner can now leave the synthetic
compatibility lane through `A_ENTITY` before their listener chain runs. The
smoke fixture uses a harmless custom event with `getEntity()` and forces the
local owner check with `foliabytecodebridge.smokeCurrentEntityOwner=true`, so the
test proves the direct current-owner route without needing a live Folia entity
thread.

Expected route-exit evidence:

```text
[FBB synthetic-event-route-exit] action=current-owner event=smoketest.SmokeTarget$SmokeEntityOwnedEvent route=A_ENTITY family=entity next=listener-entity-owner-exit ownerMethod=getEntity path=direct-current-owner
[FBB synthetic-event-dispatch] action=synthetic-start event=smoketest.SmokeTarget$SmokeEntityOwnedEvent route=A_ENTITY path=direct-current-owner
```

Smoke verification:

```text
SMOKE_OK bridgeCalls=22 unsafeCalls=176 bytecodeJars=2 bytecodeClasses=28 bytecodeRequiredHits=58 bytecodeMissing=[bukkitrunnable-runTaskAsync, bukkitrunnable-runTaskTimerAsync, scheduler-runTaskAsync, scheduler-runTaskLaterAsync, scheduler-runTaskTimerAsync, scheduler-scheduleSyncDelayed, scheduler-scheduleSyncDelayedLong, scheduler-scheduleSyncRepeating, scheduler-scheduleAsyncDelayed, scheduler-scheduleAsyncDelayedLong, scheduler-scheduleAsyncRepeating, shaded-paperlib-teleportAsync-fixture] knownGapHits={world-spawnEntity=4} rawInheritedOwnerHits=0 rawAnonymousOverrideHits=0 rawWrapperGuardHits=0 rawLegacyAsyncRepeatingHits=0 rawCommandDispatchHits=2 asmRouteHits=103 routeRules=88 nmsCompatEvidence=1 memberMapEvidence=2 nmsSyntheticMemberEvidence=synthetic-currentTick-field+tickServer-hook legacyMainThreadEvidence=legacy-isMainThread-original-check+folia-fallback mcUtilExecutorEvidence=mcutil-main-executor-global-route nmsServerExecutorEvidence=nms-minecraftserver-execute-global-route compatibilityLaneEvidence=synthetic-event-lane-sequence listenerRouteExitEvidence=listener-failure-route-exit-classified entityOwnedRouteExitEvidence=entity-owned-synthetic-route-exit
```

## 2026-06-02 - Startup Listener Route-Exit Probe

Added a target-only startup probe that dispatches the harmless
`SharedEventPathProbeEvent` through `SyntheticEventDispatchBridge` with an
entity-owner-exit flag. One probe listener deliberately throws a Folia-shaped
entity guard without touching a real entity. The expected diagnostic proves the
synthetic shared-event lane can classify the listener failure as the next
`A_ENTITY` route exit:

```text
[FBB synthetic-event-dispatch] action=listener-failure
route=A_ENTITY family=entity next=listener-entity-owner-exit-needed
```

This is still evidence, not a broad listener rewrite. Unknown listener code
stays in the synthetic/compatibility model, and only known bytecode routes are
eligible to exit to Folia owners.

Smoke command was run with the refreshed Maven-built shaded jar and live Folia
compile classpath:

```text
SMOKE_OK bridgeCalls=22 unsafeCalls=176 bytecodeJars=2 bytecodeClasses=28 bytecodeRequiredHits=58 bytecodeMissing=[bukkitrunnable-runTaskAsync, bukkitrunnable-runTaskTimerAsync, scheduler-runTaskAsync, scheduler-runTaskLaterAsync, scheduler-runTaskTimerAsync, scheduler-scheduleSyncDelayed, scheduler-scheduleSyncDelayedLong, scheduler-scheduleSyncRepeating, scheduler-scheduleAsyncDelayed, scheduler-scheduleAsyncDelayedLong, scheduler-scheduleAsyncRepeating, shaded-paperlib-teleportAsync-fixture] knownGapHits={world-spawnEntity=4} rawInheritedOwnerHits=0 rawAnonymousOverrideHits=0 rawWrapperGuardHits=0 rawLegacyAsyncRepeatingHits=0 rawCommandDispatchHits=2 asmRouteHits=103 routeRules=88 nmsCompatEvidence=1 memberMapEvidence=2 nmsSyntheticMemberEvidence=synthetic-currentTick-field+tickServer-hook legacyMainThreadEvidence=legacy-isMainThread-original-check+folia-fallback mcUtilExecutorEvidence=mcutil-main-executor-global-route nmsServerExecutorEvidence=nms-minecraftserver-execute-global-route compatibilityLaneEvidence=synthetic-event-lane-sequence listenerRouteExitEvidence=listener-failure-route-exit-classified
```

The first smoke attempt used a stale `target/classpath.txt` with old Paper API
classes and failed before the probe with `PluginDescriptionFile#isFoliaSupported`
missing. The rerun used `target/smoke-javac.args`, which points at the live
Folia server libraries.

## 2026-06-02 - Synthetic Event Dispatch Rewrite

The exact bytecode shape `PluginManager#callEvent(Event)` is now routed through
`SyntheticEventDispatchBridge`. Built-in Bukkit/Paper events remain
pass-through. Custom non-async plugin events enter the compatibility lane and
dispatch the registered listener list as the first concrete shared-event model.

Smoke now asserts:

```text
[FBB guard-path] owner=org.bukkit.plugin.PluginManager name=callEvent ... action=rewritten
[FBB synthetic-event-dispatch] action=synthetic-start ... result=dispatching
[FBB synthetic-event-dispatch] action=synthetic-finish ... result=completed
```

Smoke verification:

```text
SMOKE_OK bridgeCalls=22 unsafeCalls=176 rawInheritedOwnerHits=0 rawAnonymousOverrideHits=0 rawWrapperGuardHits=0 rawLegacyAsyncRepeatingHits=0 rawCommandDispatchHits=2 asmRouteHits=103 routeRules=88 nmsCompatEvidence=1 memberMapEvidence=2 nmsSyntheticMemberEvidence=synthetic-currentTick-field+tickServer-hook legacyMainThreadEvidence=legacy-isMainThread-original-check+folia-fallback mcUtilExecutorEvidence=mcutil-main-executor-global-route nmsServerExecutorEvidence=nms-minecraftserver-execute-global-route compatibilityLaneEvidence=synthetic-event-lane-sequence
```

## 2026-06-02 - Compatibility Lane Implementation

The synthetic compatibility model now has a concrete single-thread
`CompatibilityLane`. This is a serialized compatibility lane for unknown/shared
legacy behavior, not a Folia owner thread. Known Bukkit API edges inside the
lane still need to exit through normal route families before they are treated as
safe.

New smoke assertions require:

```text
[FBB compatibility-lane] action=submit ... result=queued
[FBB compatibility-lane] action=start ... result=running thread=FBB-compatibility-lane
[FBB compatibility-context] action=enter kind=synthetic-event-path ...
[FBB event-listener] ... phase=MONITOR ... laneActive=true
[FBB compatibility-lane] action=finish ... result=completed
```

Smoke verification:

```text
SMOKE_OK bridgeCalls=22 unsafeCalls=176 rawInheritedOwnerHits=0 rawAnonymousOverrideHits=0 rawWrapperGuardHits=0 rawLegacyAsyncRepeatingHits=0 rawCommandDispatchHits=2 asmRouteHits=102 routeRules=88 nmsCompatEvidence=1 memberMapEvidence=2 nmsSyntheticMemberEvidence=synthetic-currentTick-field+tickServer-hook legacyMainThreadEvidence=legacy-isMainThread-original-check+folia-fallback mcUtilExecutorEvidence=mcutil-main-executor-global-route nmsServerExecutorEvidence=nms-minecraftserver-execute-global-route compatibilityLaneEvidence=synthetic-event-lane-sequence
```

## 2026-06-01 - Synthetic Compatibility Context

The bridge now has a first-pass compatibility context for unknown or shared
legacy paths. This is not an event-dispatch rewrite. It adds a clean runtime
hook and evidence lines for future synthetic event-path work:

```text
[FBB compatibility-context]
[FBB event-listener]
[FBB promotion-candidate]
[FBB synthetic-event-probe]
```

`PluginManager#callEvent(Event)` is registered as a trace-only `S_GLOBAL`
architecture entry so event dispatch appears in route evidence without claiming
a safe rewrite. The probe startup bucket now calls a harmless cancellable event
with ordered listeners and records cancellation/state sharing under
`synthetic-event-path`.

Smoke verification:

```text
SMOKE_OK bridgeCalls=22 unsafeCalls=176 rawInheritedOwnerHits=0 rawAnonymousOverrideHits=0 rawWrapperGuardHits=0 rawLegacyAsyncRepeatingHits=0 rawCommandDispatchHits=2 asmRouteHits=102 routeRules=88 nmsCompatEvidence=1 memberMapEvidence=2 nmsSyntheticMemberEvidence=synthetic-currentTick-field+tickServer-hook legacyMainThreadEvidence=legacy-isMainThread-original-check+folia-fallback mcUtilExecutorEvidence=mcutil-main-executor-global-route nmsServerExecutorEvidence=nms-minecraftserver-execute-global-route
```

## 2026-06-01 - C_REGION_BLOCK BlockData Read

Live claim-style visualization evidence moved from the already-routed
`Block#getType()` path to the neighboring generic bytecode shape:

```text
BukkitScheduler#scheduleSyncDelayedTask -> S_GLOBAL
Block#getBlockData -> C_REGION_BLOCK
```

The bridge now registers and rewrites `Block#getBlockData()` as a block-owned
sync-return route. Its diagnostics use `fallback=block-data-cache` and
`reason=global-scheduler-block-data-cache-miss` so this path can be separated
from the older material-read route in debug logs.

Smoke verification:

```text
SMOKE_OK bridgeCalls=22 unsafeCalls=207 rawInheritedOwnerHits=0 rawAnonymousOverrideHits=0 rawWrapperGuardHits=0 rawLegacyAsyncRepeatingHits=0 rawCommandDispatchHits=2 asmRouteHits=102 routeRules=87
```

## 2026-06-01 - C_REGION_BLOCK Global-Scheduler Sync Return

Live claim-style visualization evidence showed this generic bytecode path:

```text
BukkitScheduler#scheduleSyncDelayedTask -> S_GLOBAL
Block#getType -> C_REGION_BLOCK
```

The delayed task ran on Folia's global scheduler and then performed a
block-owned synchronous material read. The old `Block#getType` cache-miss branch
preserved the original direct call, which kept the Folia owner failure visible
but did not translate the return path.

The bridge now treats a `Block#getType` cache miss from a detected Folia global
scheduler context as `fallback=region-owned-sync-return
policy=bounded-region-wait`, then reads on the block owner region. Other Folia
owner threads keep the old loud direct-preserve behavior to avoid guessing a
cross-owner wait.

Smoke verification:

```text
SMOKE_OK bridgeCalls=22 unsafeCalls=205 rawInheritedOwnerHits=0 rawAnonymousOverrideHits=0 rawWrapperGuardHits=0 rawLegacyAsyncRepeatingHits=0 rawCommandDispatchHits=2 asmRouteHits=101 routeRules=86
```

## 2026-05-31 - Direct NMS Server Executor Global Route

The latest live trace showed `MinecraftServer#execute(Runnable)` being called
directly from a plugin adapter after the earlier `MCUtil.MAIN_EXECUTOR` route
had already worked. FBB now treats that direct NMS owner/name/descriptor as the
same general `S_GLOBAL` server-executor family and rewrites it to
`ServerExecutorBridge#minecraftServerExecute(Object,Runnable)`.

This is not a plugin-specific patch. The rule is keyed to:

```text
owner=net/minecraft/server/MinecraftServer
name=execute
descriptor=(Ljava/lang/Runnable;)V
```

Smoke verification:

```text
[FBB model] route=S_GLOBAL api=net.minecraft.server.MinecraftServer#execute ... routeRulePolicy=VOID_FIRE_AND_FORGET routeRuleStatus=EXPERIMENTAL_REWRITE
[FBB transform] class=smoketest.NmsServerExecutorShape ... path=raw-nms-server-executor result=patched replacements=1
SMOKE_OK ... routeRules=57 ... nmsServerExecutorEvidence=nms-minecraftserver-execute-global-route
```

## 2026-05-31 - MCUtil Route Registry And Repeat Diagnostics

The MCUtil main-executor route is now registered in the central route-rule
registry so model lines report a concrete policy instead of
`dynamic-or-unregistered`:

```text
[FBB model] route=S_GLOBAL api=io.papermc.paper.util.MCUtil#MAIN_EXECUTOR.execute ... routeRulePolicy=VOID_FIRE_AND_FORGET routeRuleStatus=EXPERIMENTAL_REWRITE
```

High-volume `[FBB scheduler]` and `[FBB unsafe-call]` lines now emit the first
few full examples, then switch to `[FBB repeat-summary]` for the same API,
route, policy/next action, plugin, and caller. Failure lines are not throttled.

```text
[FBB repeat-summary] channel=unsafe-call total=100 suppressedSinceLast=96 key=Smoke#repeatDiagnostic|C_REGION_BLOCK|region|repeat-summary-smoke|...
```

Smoke verification:

```text
SMOKE_OK ... routeRules=56 ... mcUtilExecutorEvidence=mcutil-main-executor-global-route
```

## 2026-05-31 - MCUtil Main Executor Global Route

The live world-editing reference `//copy` trace exposed a Paper main-executor assumption:
`MCUtil.MAIN_EXECUTOR#execute(Runnable)` eventually called
`MinecraftServer#execute(Runnable)`, which Folia rejects. FBB now rewrites the
specific bytecode shape `GETSTATIC MCUtil.MAIN_EXECUTOR` followed by
`Executor#execute(Runnable)` into `ServerExecutorBridge`, which schedules the
runnable through Folia's global scheduler and keeps `[FBB scheduler]` /
`[FBB task-failure]` evidence.

This is a general `S_GLOBAL` server-executor route, not a world-editing reference command patch.

```text
SMOKE_OK ... mcUtilExecutorEvidence=mcutil-main-executor-global-route
```

## 2026-05-31 - Legacy Main-Thread Frame Verification

The live server log exposed a `VerifyError` after the
`raw-legacy-main-thread` transformer rewrote
`com.worldeditingreference.core.LegacyMainThreadOwner#isMainThread()Z`. The route decision was
still correct, but the replaced Java 17 method needed recomputed stack-map
frames. A whole-class frame recompute also proved unsafe for world-editing reference because it
could widen unrelated locals to `Object`. The transformer now preserves the
rest of the class frames and emits explicit frames only for the tiny replacement
method.

Smoke now defines and resolves the transformed fake world-editing reference class, so invalid
stack-map frames fail locally before a server boot:

```text
SMOKE_OK ... legacyMainThreadEvidence=fawe-isMainThread-original-check+folia-fallback
```

## 2026-05-31 - Paper/Folia Synthetic Candidate Scanner

The evidence planner now accepts `--paper-root <Paper source folder or zip>` and
prints `[FBB synthetic-candidate]` / `[FBB synthetic-summary]` rows. This turns
"can we grab something from Paper?" into a repeatable comparison between plugin
server-internal references, the live Folia server jar, and the Paper reference
source. It is research evidence only; the scanner does not add synthetic
members or silence linkage failures.

The current live plugin set plus `Paper-main.zip` produced:

```text
[FBB synthetic-summary] category=NMS_VERSION_COMPAT candidates=948 printedGaps=367 liveExactSuppressed=581 classifications="member-not-in-paper-reference-map-equivalent-first=170,no-synthetic-needed-live-member-exists=581,owner-not-found-in-paper-reference=66,owner-shape-mismatch-research-before-adapter=47,synthetic-field-candidate-map-equivalent-first=7,synthetic-method-risky-behavior-adapter-first=77"
```

The already-promoted `MinecraftServer.currentTick:I` path appears as a
`synthetic-field-candidate-map-equivalent-first`, which confirms the scanner is
finding the same class of gap that the current tick adapter solved. New field
candidates, such as `ServerLevel#captureBlockStates`, are left as research
targets until a Folia-owned equivalent and behavior match are proven.

## 2026-05-31 - NMS Compatibility Model

`NMS_VERSION_COMPAT` is now modeled as diagnostic evidence for
server-internal binary shape failures such as world-editing reference expecting
`MinecraftServer.currentTick` on a Folia jar that does not expose that field.
This is not an ownership route and does not silence the crash. The evidence
tool emits `[FBB preflight-nms]` for server-internal member references in plugin
bytecode and `[FBB compat]` for live linkage errors, including
owner/name/descriptor, plugin jar, caller, reason, and the next
adapter-research step. FBB also installs a de-duplicated runtime log handler so
linkage throwables logged after FBB enables get a direct `[FBB compat]`
breadcrumb in the server console.

Smoke coverage includes a synthetic protection plugin reference/world-editing reference-style stack trace and
asserts the model produces:

```text
[FBB compat] category=NMS_VERSION_COMPAT throwable=NoSuchFieldError owner=net.minecraft.server.MinecraftServer name=currentTick descriptor=I ... action=diagnostic-only
```

## 2026-05-31 - Server Member Map

The evidence tool now accepts `--server-root <server>` and emits
`[FBB member-map]` for live compatibility failures. For the current
world-editing reference/protection plugin reference failure, the map proves the running Folia jar contains
`net.minecraft.server.MinecraftServer` but does not contain the expected
`currentTick:I` field:

```text
[FBB member-map] category=NMS_VERSION_COMPAT owner=net.minecraft.server.MinecraftServer kind=field expected=currentTick:I classFound=true exactMatch=false source="...\versions\26.1.2\folia-26.1.2.jar" candidates="currentTickStart:J|...|tickTaskTickCount:Ljava/util/concurrent/atomic/AtomicInteger;|..."
```

The closest names are research pointers, not a safe adapter yet:
`currentTickStart:J` is a long timestamp-like field, not the missing integer
tick counter. Smoke now checks both exact-member and missing-member map paths
with `memberMapEvidence=2`.

## 2026-05-31 - Synthetic Current Tick Adapter

Paper's source patch shows `public static int MinecraftServer.currentTick` and
increments it in the server tick loop. world-editing reference's v26.1 native access reads that
field to decide when to flush cached async block changes. Folia 26.1.2 exposes
region tick data instead, so FBB now adds `currentTick:I` during initial
`MinecraftServer` class definition and writes `TickRegionData#getCurrentTick()`
into it at the start of `tickServer(JJJ, TickRegionData)`.

```text
SMOKE_OK ... nmsSyntheticMemberEvidence=synthetic-currentTick-field+tickServer-hook
```

Runtime evidence should include:

```text
[FBB nms-compat] category=NMS_VERSION_COMPAT model=SERVER_TICK_COUNTER owner=net.minecraft.server.MinecraftServer member=currentTick descriptor=I result=patched action=synthetic-field-added hook=tickServer-region-currentTick route=none
```

This is a server-internal compatibility adapter, not an `A_ENTITY` /
`B_REGION_LOCATION` ownership route.

## 2026-05-31 - Foreign Owner Return Models

```text
SMOKE_OK bridgeCalls=22 unsafeCalls=205 bytecodeJars=2 bytecodeClasses=265 bytecodeRequiredHits=39 bytecodeMissing=[scheduler-runTaskAsync, scheduler-runTaskLaterAsync, scheduler-runTaskTimerAsync, scheduler-scheduleSyncDelayed, scheduler-scheduleSyncRepeating, scheduler-scheduleAsyncDelayed, scheduler-scheduleAsyncDelayedLong, scheduler-scheduleAsyncRepeating, player-teleport-cause, world-strikeLightning, world-generateTree, shaded-paperlib-teleportAsync-fixture] knownGapHits={world-spawnEntity=1} rawInheritedOwnerHits=1 rawAnonymousOverrideHits=1 rawWrapperGuardHits=0 rawLegacyMainThreadOwnerAsyncRepeatingHits=0 rawCommandDispatchHits=2 asmRouteHits=85 routeRules=55
```

The live startup probe showed the route family was correct for `Block#getType`
and lightning, but the old sync-return fallback still retried directly from a
foreign/global Folia owner thread. `Block#getType()` now records owned
reads/writes in a block-material model and uses that model only when a foreign
owner thread cannot safely wait for the target region. Lightning creation now
uses `DEFERRED_PROXY_RETURN`: async callers can wait for the region scheduler,
while foreign owner threads receive a deferred `LightningStrike` proxy and the
scheduled task still logs `[FBB task-failure]` / `[FBB unsafe-failure]` if the
region call fails.

## 2026-05-31 - Entity Effect Accepted Boolean Route

```text
SMOKE_OK bridgeCalls=22 unsafeCalls=205 bytecodeJars=2 bytecodeClasses=265 bytecodeRequiredHits=39 bytecodeMissing=[scheduler-runTaskAsync, scheduler-runTaskLaterAsync, scheduler-runTaskTimerAsync, scheduler-scheduleSyncDelayed, scheduler-scheduleSyncRepeating, scheduler-scheduleAsyncDelayed, scheduler-scheduleAsyncDelayedLong, scheduler-scheduleAsyncRepeating, player-teleport-cause, world-strikeLightning, world-generateTree, shaded-paperlib-teleportAsync-fixture] knownGapHits={world-spawnEntity=1} rawInheritedOwnerHits=1 rawAnonymousOverrideHits=1 rawWrapperGuardHits=0 rawLegacyMainThreadOwnerAsyncRepeatingHits=0 rawCommandDispatchHits=2 asmRouteHits=85 routeRules=55
```

The live log showed wrong-owner `Player#addPotionEffect` and
`LivingEntity#addPotionEffect` falling back to direct execution after the bridge
refused to block a Folia owner thread for the real boolean return. Those routes
now use `ACCEPTED_BOOLEAN`: direct when the current thread owns the entity,
otherwise schedule the mutation on the entity scheduler and return
`scheduled-true` while preserving task-failure evidence. Potion removals are now
explicit fire-and-forget entity routes.

## 2026-05-31 - Route Rule Registry Architecture Map

```text
SMOKE_OK bridgeCalls=22 unsafeCalls=205 bytecodeJars=2 bytecodeClasses=265 bytecodeRequiredHits=39 bytecodeMissing=[scheduler-runTaskAsync, scheduler-runTaskLaterAsync, scheduler-runTaskTimerAsync, scheduler-scheduleSyncDelayed, scheduler-scheduleSyncRepeating, scheduler-scheduleAsyncDelayed, scheduler-scheduleAsyncDelayedLong, scheduler-scheduleAsyncRepeating, player-teleport-cause, world-strikeLightning, world-generateTree, shaded-paperlib-teleportAsync-fixture] knownGapHits={world-spawnEntity=1} rawInheritedOwnerHits=1 rawAnonymousOverrideHits=1 rawWrapperGuardHits=0 rawLegacyMainThreadOwnerAsyncRepeatingHits=0 rawCommandDispatchHits=2 asmRouteHits=85 routeRules=55
```

Exact raw-direct unsafe routes now flow through `RouteRuleRegistry` before the
legacy fallback matcher. The same central entries feed the ASM analyzer,
transformer replacement choice, `[FBB guard-path]` evidence, and `[FBB model]`
lines. Smoke now asserts stable top-level route model fields such as
`routeRulePolicy=SYNC_RETURN_DIRECT_OR_OWNER`,
`routeRulePolicy=SPLIT_AGGREGATE_RETURN`, and
`routeRuleStatus=EXPERIMENTAL_REWRITE`. `[FBB model-summary]` also reports
`knownRules=<count>` so live logs show how much of the current evidence is backed
by the central route map.
The smoke also walks the generated registry list and checks that rewriting
entries have valid bridge method targets, so a broken route model fails before a
live server run.

## 2026-05-31 - Entity Nearby Split And Invokedynamic Guard

```text
SMOKE_OK bridgeCalls=22 unsafeCalls=205 bytecodeJars=2 bytecodeClasses=265 bytecodeRequiredHits=39 bytecodeMissing=[scheduler-runTaskAsync, scheduler-runTaskLaterAsync, scheduler-runTaskTimerAsync, scheduler-scheduleSyncDelayed, scheduler-scheduleSyncRepeating, scheduler-scheduleAsyncDelayed, scheduler-scheduleAsyncDelayedLong, scheduler-scheduleAsyncRepeating, player-teleport-cause, world-strikeLightning, world-generateTree, shaded-paperlib-teleportAsync-fixture] knownGapHits={world-spawnEntity=1} rawInheritedOwnerHits=1 rawAnonymousOverrideHits=1 rawWrapperGuardHits=0 rawLegacyMainThreadOwnerAsyncRepeatingHits=0 rawCommandDispatchHits=2 asmRouteHits=52
```

The live probe exposed `Player#getNearbyEntities(DDD)` as a return-value
`G_WORLD_SCAN_SPLIT` route that still failed from Folia owner/global contexts.
The bridge now uses an entity-location bounded split model for those contexts,
while async callers can still wait for the entity scheduler. The same pass added
an invokedynamic receiver check so method-reference rewrites become trace-only
when the captured receiver descriptor would break `LambdaMetafactory`.

## 2026-05-31 - Deferred Chunk Return Model

```text
SMOKE_OK bridgeCalls=22 unsafeCalls=205 bytecodeJars=2 bytecodeClasses=265 bytecodeRequiredHits=39 bytecodeMissing=[scheduler-runTaskAsync, scheduler-runTaskLaterAsync, scheduler-runTaskTimerAsync, scheduler-scheduleSyncDelayed, scheduler-scheduleSyncRepeating, scheduler-scheduleAsyncDelayed, scheduler-scheduleAsyncDelayedLong, scheduler-scheduleAsyncRepeating, player-teleport-cause, world-strikeLightning, world-generateTree, shaded-paperlib-teleportAsync-fixture] knownGapHits={world-spawnEntity=1} rawInheritedOwnerHits=1 rawAnonymousOverrideHits=1 rawWrapperGuardHits=0 rawLegacyMainThreadOwnerAsyncRepeatingHits=0 rawCommandDispatchHits=2 asmRouteHits=52
```

`World#getChunkAt(...)` now has a second return model for Folia owner-thread
misses. If the loaded-chunk index has the requested chunk, the bridge returns
the real `Chunk`. If not, it returns a deferred `Chunk` proxy, starts async
preload with `World#getChunkAtAsync(x,z,true)`, and logs `pending-result-default`
for any chunk method used before the real chunk resolves. This keeps the route
under `B_REGION_LOCATION` and preserves evidence instead of falling back into
the original unsafe synchronous chunk retrieval.

## 2026-05-29 - Optional Dependency Transform Boundary

```text
SMOKE_OK bridgeCalls=22 unsafeCalls=205 bytecodeJars=2 bytecodeClasses=265 bytecodeRequiredHits=39 bytecodeMissing=[scheduler-runTaskAsync, scheduler-runTaskLaterAsync, scheduler-runTaskTimerAsync, scheduler-scheduleSyncDelayed, scheduler-scheduleSyncRepeating, scheduler-scheduleAsyncDelayed, scheduler-scheduleAsyncDelayedLong, scheduler-scheduleAsyncRepeating, player-teleport-cause, world-strikeLightning, world-generateTree, shaded-paperlib-teleportAsync-fixture] knownGapHits={world-spawnEntity=1} rawInheritedOwnerHits=1 rawAnonymousOverrideHits=1 rawWrapperGuardHits=0 rawLegacyMainThreadOwnerAsyncRepeatingHits=0 rawCommandDispatchHits=2 asmRouteHits=52
```

Byte Buddy typed-transform failures caused by a missing optional integration API
now log as `[FBB transform-skip] reason=optional-dependency-missing` with the
missing type and an ASM route summary when class bytes are readable. This keeps
PlotSquared/permissions-style soft dependency evidence visible without faking
an adapter or hiding actual unsafe-call failures.

## 2026-05-29 - ASM Route Scanner Proof

```text
SMOKE_OK bridgeCalls=22 unsafeCalls=205 bytecodeJars=2 bytecodeClasses=265 bytecodeRequiredHits=39 bytecodeMissing=[scheduler-runTaskAsync, scheduler-runTaskLaterAsync, scheduler-runTaskTimerAsync, scheduler-scheduleSyncDelayed, scheduler-scheduleSyncRepeating, scheduler-scheduleAsyncDelayed, scheduler-scheduleAsyncDelayedLong, scheduler-scheduleAsyncRepeating, player-teleport-cause, world-strikeLightning, world-generateTree, shaded-paperlib-teleportAsync-fixture] knownGapHits={world-spawnEntity=1} rawInheritedOwnerHits=1 rawAnonymousOverrideHits=1 rawWrapperGuardHits=0 rawLegacyMainThreadOwnerAsyncRepeatingHits=0 rawCommandDispatchHits=2 asmRouteHits=52
```

The ASM experiment is useful enough to keep as a read-only evidence layer.
`InstructionRouteScanner` scans raw class bytes and `InstructionRouteRegistry`
maps exact owner/name/descriptor shapes to route families. The smoke test now
asserts that ASM sees representative `A_ENTITY`, `C_REGION_BLOCK`,
`G_WORLD_SCAN_SPLIT`, `D_PLAYER_UI`, and `S_GLOBAL` shapes in
`smoketest/SmokeTarget.class`. This does not promote any new runtime rewrite by
itself; it gives us a cleaner inventory tool before risky bytecode changes.

## 2026-05-29 - Entity Potion Effect Route

```text
SMOKE_OK bridgeCalls=22 unsafeCalls=205 bytecodeJars=2 bytecodeClasses=265 bytecodeRequiredHits=39 bytecodeMissing=[scheduler-runTaskAsync, scheduler-runTaskLaterAsync, scheduler-runTaskTimerAsync, scheduler-scheduleSyncDelayed, scheduler-scheduleSyncRepeating, scheduler-scheduleAsyncDelayed, scheduler-scheduleAsyncDelayedLong, scheduler-scheduleAsyncRepeating, player-teleport-cause, world-strikeLightning, world-generateTree, shaded-paperlib-teleportAsync-fixture] knownGapHits={world-spawnEntity=1} rawInheritedOwnerHits=1 rawAnonymousOverrideHits=1 rawWrapperGuardHits=0 rawLegacyMainThreadOwnerAsyncRepeatingHits=0 rawCommandDispatchHits=2
```

The live first-join matrix exposed `Player#addPotionEffect(PotionEffect)` and
`LivingEntity#addPotionEffect(PotionEffect)` as `A_ENTITY` failures from
async/global contexts. The raw transformer now rewrites player and living-entity
effect add/remove descriptors, and the bridge uses a preemptive entity-owner
check: direct when the current Folia thread owns the receiver, otherwise schedule
through that entity's scheduler before calling Bukkit.

## 2026-05-29 - Block Material Read/Mutation Route

```text
SMOKE_OK bridgeCalls=22 unsafeCalls=205 bytecodeJars=2 bytecodeClasses=265 bytecodeRequiredHits=39 bytecodeMissing=[scheduler-runTaskAsync, scheduler-runTaskLaterAsync, scheduler-runTaskTimerAsync, scheduler-scheduleSyncDelayed, scheduler-scheduleSyncRepeating, scheduler-scheduleAsyncDelayed, scheduler-scheduleAsyncDelayedLong, scheduler-scheduleAsyncRepeating, player-teleport-cause, world-strikeLightning, world-generateTree, shaded-paperlib-teleportAsync-fixture] knownGapHits={world-spawnEntity=1} rawInheritedOwnerHits=1 rawAnonymousOverrideHits=1 rawWrapperGuardHits=0 rawLegacyMainThreadOwnerAsyncRepeatingHits=0 rawCommandDispatchHits=2
```

The live probe exposed `Block#getType()` as a real `C_REGION_BLOCK` owner path:
the target probe reached `CraftBlock#getType` from global/async contexts before
the `Block#setType(Material)` body could run. The bridge now rewrites
`Block#getType()` and promotes `Block#setType(Material)` into preemptive
region-owned routes so block material reads and writes run through the block's
owning region scheduler instead of throwing first and recovering later.

## 2026-05-29 - Scan Probe Setup Attribution

```text
SMOKE_OK bridgeCalls=22 unsafeCalls=203 bytecodeJars=2 bytecodeClasses=265 bytecodeRequiredHits=39 bytecodeMissing=[scheduler-runTaskAsync, scheduler-runTaskLaterAsync, scheduler-runTaskTimerAsync, scheduler-scheduleSyncDelayed, scheduler-scheduleSyncRepeating, scheduler-scheduleAsyncDelayed, scheduler-scheduleAsyncDelayedLong, scheduler-scheduleAsyncRepeating, player-teleport-cause, world-strikeLightning, world-generateTree, shaded-paperlib-teleportAsync-fixture] knownGapHits={world-spawnEntity=1} rawInheritedOwnerHits=1 rawAnonymousOverrideHits=1 rawWrapperGuardHits=0 rawLegacyMainThreadOwnerAsyncRepeatingHits=0 rawCommandDispatchHits=2
```

The live log showed `Location#getChunk()` escaping the target probe wrapper
during first-join `scan` mode from global/async contexts. The probe now resolves
that chunk inside the `Chunk#getEntities` probe body with
`World#getChunkAt(Location)`, preserving the intended
`B_REGION_LOCATION -> G_WORLD_SCAN_SPLIT` attribution.

## 2026-05-29 - Scoreboard Display State Model

```text
SMOKE_OK bridgeCalls=22 unsafeCalls=203 bytecodeJars=2 bytecodeClasses=265 bytecodeRequiredHits=39 bytecodeMissing=[scheduler-runTaskAsync, scheduler-runTaskLaterAsync, scheduler-runTaskTimerAsync, scheduler-scheduleSyncDelayed, scheduler-scheduleSyncRepeating, scheduler-scheduleAsyncDelayed, scheduler-scheduleAsyncDelayedLong, scheduler-scheduleAsyncRepeating, player-teleport-cause, world-strikeLightning, world-generateTree, shaded-paperlib-teleportAsync-fixture] knownGapHits={world-spawnEntity=1} rawInheritedOwnerHits=1 rawAnonymousOverrideHits=1 rawWrapperGuardHits=0 rawLegacyMainThreadOwnerAsyncRepeatingHits=0 rawCommandDispatchHits=2
```

The `D_PLAYER_UI` scoreboard shim now covers team/objective/score display-state
mutations in addition to structural model operations. New smoke assertions cover
`Team#setPrefix`, `Team#setSuffix`, `Team#setColor`,
`Objective#displayName(Component)`, and `Score#customName(Component)` through
both `[FBB guard-path]` bytecode evidence and `[FBB unsafe-call]
policy=shim-model` runtime evidence. This is still a model layer; visible client
application remains a future packet/player-owned apply route.

## 2026-05-29 - Scoreboard Objective/Score Model

```text
SMOKE_OK bridgeCalls=22 unsafeCalls=175 bytecodeJars=2 bytecodeClasses=265 bytecodeRequiredHits=39 bytecodeMissing=[scheduler-runTaskAsync, scheduler-runTaskLaterAsync, scheduler-runTaskTimerAsync, scheduler-scheduleSyncDelayed, scheduler-scheduleSyncRepeating, scheduler-scheduleAsyncDelayed, scheduler-scheduleAsyncDelayedLong, scheduler-scheduleAsyncRepeating, player-teleport-cause, world-strikeLightning, world-generateTree, shaded-paperlib-teleportAsync-fixture] knownGapHits={world-spawnEntity=1} rawInheritedOwnerHits=1 rawAnonymousOverrideHits=1 rawWrapperGuardHits=0 rawLegacyMainThreadOwnerAsyncRepeatingHits=0 rawCommandDispatchHits=2
```

The `D_PLAYER_UI` scoreboard shim now covers objective and score model paths:
`Scoreboard#registerNewObjective(...)`, objective lookup, display slot changes,
`Objective#getScore`, and `Score#setScore/getScore/resetScore`. The smoke test
asserts both `[FBB guard-path]` rewrite evidence and `[FBB unsafe-call]`
`policy=shim-model` evidence for these bytecode shapes.

## 2026-05-29 - Player Scoreboard Model Registry

```text
SMOKE_OK bridgeCalls=22 unsafeCalls=157 bytecodeJars=2 bytecodeClasses=265 bytecodeRequiredHits=39 bytecodeMissing=[scheduler-runTaskAsync, scheduler-runTaskLaterAsync, scheduler-runTaskTimerAsync, scheduler-scheduleSyncDelayed, scheduler-scheduleSyncRepeating, scheduler-scheduleAsyncDelayed, scheduler-scheduleAsyncDelayedLong, scheduler-scheduleAsyncRepeating, player-teleport-cause, world-strikeLightning, world-generateTree, shaded-paperlib-teleportAsync-fixture] knownGapHits={world-spawnEntity=1} rawInheritedOwnerHits=1 rawAnonymousOverrideHits=1 rawWrapperGuardHits=0 rawLegacyMainThreadOwnerAsyncRepeatingHits=0 rawCommandDispatchHits=2
```

The `D_PLAYER_UI` scoreboard model now covers player-owned boards as well as
detached `ScoreboardManager#getNewScoreboard()` boards. `Player#getScoreboard()`
returns a bridge-owned player model on Folia, `Scoreboard#registerNewTeam` and
team mutations are modeled for that receiver, and `Player#setScoreboard(model)`
retains the model instead of calling Folia's hard-disabled native setter.

## 2026-05-29 - First-Join Probe Owner Matrix

```text
SMOKE_OK bridgeCalls=22 unsafeCalls=148 bytecodeJars=2 bytecodeClasses=265 bytecodeRequiredHits=39 bytecodeMissing=[scheduler-runTaskAsync, scheduler-runTaskLaterAsync, scheduler-runTaskTimerAsync, scheduler-scheduleSyncDelayed, scheduler-scheduleSyncRepeating, scheduler-scheduleAsyncDelayed, scheduler-scheduleAsyncDelayedLong, scheduler-scheduleAsyncRepeating, player-teleport-cause, world-strikeLightning, world-generateTree, shaded-paperlib-teleportAsync-fixture] knownGapHits={world-spawnEntity=1} rawInheritedOwnerHits=1 rawAnonymousOverrideHits=1 rawWrapperGuardHits=0 rawLegacyMainThreadOwnerAsyncRepeatingHits=0 rawCommandDispatchHits=2
```

This pass follows the probe expansion for first-join player-owned owner shapes.
The probe jars now include explicit `HumanEntity`, `LivingEntity`, and `Entity`
calls beside the existing `Player` calls so live logs can tell whether a miss is
an owner/name/descriptor gap or a route-family mistake. The smoke harness still
emits the intentional `[FBB task-failure]` line for route/caller diagnostics.

## 2026-05-29 - world-editing reference World/Chunk Route Expansion

```text
SMOKE_OK bridgeCalls=22 unsafeCalls=139 bytecodeJars=3 bytecodeClasses=4075 bytecodeRequiredHits=53 bytecodeMissing=[scheduler-runTaskTimerAsync, scheduler-scheduleSyncDelayed, scheduler-scheduleAsyncDelayed, scheduler-scheduleAsyncDelayedLong, player-teleport-cause, world-strikeLightning, shaded-paperlib-teleportAsync-fixture] knownGapHits={world-spawnEntity=1} rawInheritedOwnerHits=1 rawAnonymousOverrideHits=1 rawWrapperGuardHits=0 rawLegacyMainThreadOwnerAsyncRepeatingHits=1 rawCommandDispatchHits=2
```

This pass included `kit-plugin-reference.jar`, `home plugin reference-3.1.jar`, and
`world-editing-plugin-reference.jar`. It verifies the world-editing reference legacy async
scheduler shape still rewrites, and the probe smoke emits route evidence for
`World#getLoadedChunks`, `Chunk#getEntities`, and `World#spawnEntity(Location,EntityType)`.
`World#getLoadedChunks` remains diagnostic-only because it is a whole-world scan.

## 2026-05-29 - world-editing reference Legacy Async Repeating Scheduler

```text
SMOKE_OK bridgeCalls=22 unsafeCalls=131 bytecodeJars=3 bytecodeClasses=4075 bytecodeRequiredHits=53 bytecodeMissing=[scheduler-runTaskTimerAsync, scheduler-scheduleSyncDelayed, scheduler-scheduleAsyncDelayed, scheduler-scheduleAsyncDelayedLong, player-teleport-cause, world-strikeLightning, shaded-paperlib-teleportAsync-fixture] knownGapHits={world-spawnEntity=1} rawInheritedOwnerHits=1 rawAnonymousOverrideHits=1 rawWrapperGuardHits=0 rawLegacyMainThreadOwnerAsyncRepeatingHits=1 rawCommandDispatchHits=2
```

Live-server evidence showed world-editing reference calling `BukkitScheduler#scheduleAsyncRepeatingTask(Plugin,Runnable,long,long): int` from `BukkitTaskManager#repeatAsync`. The raw scheduler transformer now maps that legacy int-returning call to `ObjectSchedulerBridge#scheduleAsyncRepeatingTask` under `route=S_ASYNC`, using Folia's async scheduler `runAtFixedRate` and returning a synthetic task id.

Last local run: 2026-05-28

Command shape:

```text
java -Dfoliabytecodebridge.forceNonFolia=true -Dfoliabytecodebridge.metadataOverlay=all -Dfoliabytecodebridge.smokeNoPassthrough=true -Dfoliabytecodebridge.debug=true -Dfoliabytecodebridge.deepPluginJars=<kit plugin reference.jar;server-utility plugin reference.jar> -javaagent:FoliaBytecodeBridge.jar -cp <smoke classes + bridge + Paper API deps> smoketest.SmokeMain
```

Result:

```text
SMOKE_OK bridgeCalls=22 unsafeCalls=95 bytecodeJars=2 bytecodeClasses=2394 bytecodeRequiredHits=79 knownGapHits={world-spawnEntity=2} rawInheritedOwnerHits=1 rawAnonymousOverrideHits=1 rawWrapperGuardHits=1
```

## Synthetic Phase 4 Contract Readiness Smoke

Last local smoke run: 2026-06-02

The smoke suite now covers both sides of the multi-region mutation contract
model:

```text
[FBB synthetic-multi-region] phase=contract-mutation result=contract-missing reason=missing-two-phase-contract
[FBB synthetic-multi-region] phase=contract-mutation result=ready-not-executed contract=prepare,owner-apply,aggregate-verify
```

This verifies that explicit mutation events without prepare/owner-apply/verify
markers remain serialized, while events that expose all three markers are only
classified as ready for a future exact synthetic model. No multi-region write,
region freeze, or listener replay is executed by this smoke path.

## Synthetic Multi-Region Read Split Smoke

Last local smoke run: 2026-06-02

The synthetic read-only event path now proves a narrow phase-2 split/aggregate
model:

```text
multiRegionReadSplitEvidence=multi-region-read-only-split-aggregated
```

The local smoke harness uses `mode=smoke-inline` for deterministic assertion.
Live Folia runs use `mode=nonblocking`: the bridge logs `result=scheduled`
first, then records `result=aggregated` or a preserved failure from the
completion callback. This avoids blocking a scheduler or owner thread while
waiting on other region-owned reads.

## Synthetic Multi-Region Mutation Plan Smoke

Last local smoke run: 2026-06-02

Phase 3 records explicit mutation intent without executing a multi-region
write:

```text
multiRegionMutationPlanEvidence=multi-region-mutation-planned-not-executed
```

Smoke verifies both sides:

```text
[FBB synthetic-multi-region] phase=plan-mutation result=serialized-unproven reason=no-explicit-mutation-intent
[FBB synthetic-multi-region] phase=plan-mutation result=planned-not-executed phases=prepare,owner-apply,aggregate-verify
```

This is intentionally conservative. It does not freeze regions, replay
listeners, or mutate blocks; it only records enough owner and mutation-intent
evidence for a future exact model.

## Control Baseline Evidence Split

Last local smoke run: 2026-05-31

This pass did not change a route. It made the evidence cleaner: raw
`FBBProbeControl` failures are now marked as the untransformed Folia baseline
and printed separately from real plugin failures by the evidence tool.

Verification:

```text
SMOKE_OK bridgeCalls=22 unsafeCalls=205 bytecodeJars=2 bytecodeClasses=265 routeRules=57 nmsServerExecutorEvidence=nms-minecraftserver-execute-global-route
[FBB log-summary] ... realErrorLines=1 expectedControlErrorLines=45
[FBB control-summary] expectedBaselineLines=45 expectedErrorLines=45 note="FBBProbeControl is deliberately untransformed; these lines are raw Folia baseline evidence, not bridge rewrites"
```

Live jar hashes after replacement:

```text
FoliaBytecodeBridge.jar SHA256=D055690ED465332EFE995312F7D0BA2FCBF659AB008EE5BC812F0C3676F9B73E
FBBProbe.jar SHA256=8773308425D0B804E6277894F83C38152FEE5A5B4C4F8093D6A9CFB4C1FF8AD9
FBBProbeControl.jar SHA256=96F0605E1E83B75237C077E400C298AC110CC67985A6BD9C88B8CE91306DFA83
```

## D_PLAYER_UI Inventory Runtime Route Registry

Last local smoke run: 2026-05-31

The exact inventory bytecode paths were already registered as `D_PLAYER_UI`.
Live evidence also arrives from bridge runtime names such as
`Player#openInventory` and `HumanEntity#closeInventory`. Those runtime names now
alias back to the same official route rules instead of reporting
`dynamic-or-unregistered`.

Expected evidence:

```text
[FBB model] route=D_PLAYER_UI api=Player#openInventory ... routeRulePolicy=SYNC_RETURN_DIRECT_OR_OWNER routeRuleStatus=EXPERIMENTAL_REWRITE
[FBB model] route=D_PLAYER_UI api=Player#closeInventory ... routeRulePolicy=VOID_FIRE_AND_FORGET routeRuleStatus=EXPERIMENTAL_REWRITE
[FBB model] route=D_PLAYER_UI api=HumanEntity#openInventory ... routeRulePolicy=SYNC_RETURN_DIRECT_OR_OWNER routeRuleStatus=EXPERIMENTAL_REWRITE
[FBB model] route=D_PLAYER_UI api=HumanEntity#closeInventory ... routeRulePolicy=VOID_FIRE_AND_FORGET routeRuleStatus=EXPERIMENTAL_REWRITE
```

Result:

```text
SMOKE_OK bridgeCalls=22 unsafeCalls=205 ... routeRules=56 ... mcUtilExecutorEvidence=mcutil-main-executor-global-route
```

## Legacy Main-Thread Predicate Smoke

Last local smoke run: 2026-05-31

world-editing reference's `QueueHandler#run` exposed a legacy main-thread predicate after the task
had already been routed through `S_GLOBAL`. The new transformer rewrites the
exact `com.worldeditingreference.core.LegacyMainThreadOwner#isMainThread()Z` method body, preserving
the original captured-thread check and adding a Folia tick/Bukkit primary-thread
fallback only for the original false path.

```text
[FBB legacy-main-thread] class=com.worldeditingreference.core.LegacyMainThreadOwner ... route=S_GLOBAL rule=exact-owner-method-body action=rewritten
SMOKE_OK ... legacyMainThreadEvidence=fawe-isMainThread-original-check+folia-fallback
```

## Return-Value Region Route Smoke

Last local smoke run: 2026-05-29

The bridge now has explicit return models for `B_REGION_LOCATION` paths that
cannot safely block a Folia owner thread: loaded-chunk-index return for
`World#getChunkAt(...)`, and deferred accepted booleans for
`World#refreshChunk(...)` / `World#createExplosion(...)`.

Result:

```text
SMOKE_OK bridgeCalls=22 unsafeCalls=205 bytecodeJars=2 bytecodeClasses=265 bytecodeRequiredHits=39 bytecodeMissing=[scheduler-runTaskAsync, scheduler-runTaskLaterAsync, scheduler-runTaskTimerAsync, scheduler-scheduleSyncDelayed, scheduler-scheduleSyncRepeating, scheduler-scheduleAsyncDelayed, scheduler-scheduleAsyncDelayedLong, scheduler-scheduleAsyncRepeating, player-teleport-cause, world-strikeLightning, world-generateTree, shaded-paperlib-teleportAsync-fixture] knownGapHits={world-spawnEntity=1} rawInheritedOwnerHits=1 rawAnonymousOverrideHits=1 rawWrapperGuardHits=0 rawLegacyMainThreadOwnerAsyncRepeatingHits=0 rawCommandDispatchHits=2 asmRouteHits=52
```

## Void Region Route Smoke

Last local smoke run: 2026-05-29

The route-model log showed `World#playSound(Location,...)` was a void
`B_REGION_LOCATION` call, not a sync-return route. The bridge now keeps
return-value routes guarded while letting void region calls schedule
fire-and-forget on the owning region.

Result:

```text
SMOKE_OK bridgeCalls=22 unsafeCalls=205 bytecodeJars=2 bytecodeClasses=265 bytecodeRequiredHits=39 bytecodeMissing=[scheduler-runTaskAsync, scheduler-runTaskLaterAsync, scheduler-runTaskTimerAsync, scheduler-scheduleSyncDelayed, scheduler-scheduleSyncRepeating, scheduler-scheduleAsyncDelayed, scheduler-scheduleAsyncDelayedLong, scheduler-scheduleAsyncRepeating, player-teleport-cause, world-strikeLightning, world-generateTree, shaded-paperlib-teleportAsync-fixture] knownGapHits={world-spawnEntity=1} rawInheritedOwnerHits=1 rawAnonymousOverrideHits=1 rawWrapperGuardHits=0 rawLegacyMainThreadOwnerAsyncRepeatingHits=0 rawCommandDispatchHits=2 asmRouteHits=52
```

## Route Model Report Smoke

Last local smoke run: 2026-05-29

The diagnostics layer now emits `[FBB model]` lines that group bytecode and
runtime evidence into route-family method models, plus periodic
`[FBB model-summary]` lines. The smoke test asserts that model output uses only
official `RouteFamily` labels and includes representative scheduler, player UI,
and world-scan models:

```text
[FBB model] route=D_PLAYER_UI api=org.bukkit.entity.Player#openInventory owner=org.bukkit.entity.Player descriptor=(Lorg/bukkit/inventory/Inventory;)Lorg/bukkit/inventory/InventoryView; ... status=rewritten
[FBB model] route=G_WORLD_SCAN_SPLIT api=org.bukkit.World#getEntities owner=org.bukkit.World descriptor=()Ljava/util/List; ... syncReturnRisk=true
[FBB model-summary] methods=<count> routes=S_GLOBAL=<n>,S_ASYNC=<n>,A_ENTITY=<n>,B_REGION_LOCATION=<n>,C_REGION_BLOCK=<n>,D_PLAYER_UI=<n>,F_PLAYER_VISIBILITY=<n>,G_WORLD_SCAN_SPLIT=<n> ... blockedSyncReturn=<n>
```

Result:

```text
SMOKE_OK bridgeCalls=22 unsafeCalls=205 bytecodeJars=2 bytecodeClasses=265 bytecodeRequiredHits=39 bytecodeMissing=[scheduler-runTaskAsync, scheduler-runTaskLaterAsync, scheduler-runTaskTimerAsync, scheduler-scheduleSyncDelayed, scheduler-scheduleSyncRepeating, scheduler-scheduleAsyncDelayed, scheduler-scheduleAsyncDelayedLong, scheduler-scheduleAsyncRepeating, player-teleport-cause, world-strikeLightning, world-generateTree, shaded-paperlib-teleportAsync-fixture] knownGapHits={world-spawnEntity=1} rawInheritedOwnerHits=1 rawAnonymousOverrideHits=1 rawWrapperGuardHits=0 rawLegacyMainThreadOwnerAsyncRepeatingHits=0 rawCommandDispatchHits=2 asmRouteHits=52
```

## D_PLAYER_UI Inventory Owner Smoke

Last local smoke run: 2026-05-29

The live probe exposed inventory open/close failures from async/global contexts.
The raw transformer now proves these owner/name/descriptor shapes are rewritten
before runtime:

```text
[FBB guard-path] ... owner=org.bukkit.entity.Player name=openInventory descriptor=(Lorg/bukkit/inventory/Inventory;)Lorg/bukkit/inventory/InventoryView; route=D_PLAYER_UI action=rewritten
[FBB guard-path] ... owner=org.bukkit.entity.HumanEntity name=closeInventory descriptor=()V route=D_PLAYER_UI action=rewritten
```

Result:

```text
SMOKE_OK bridgeCalls=22 unsafeCalls=205 bytecodeJars=2 bytecodeClasses=265 bytecodeRequiredHits=39 bytecodeMissing=[scheduler-runTaskAsync, scheduler-runTaskLaterAsync, scheduler-runTaskTimerAsync, scheduler-scheduleSyncDelayed, scheduler-scheduleSyncRepeating, scheduler-scheduleAsyncDelayed, scheduler-scheduleAsyncDelayedLong, scheduler-scheduleAsyncRepeating, player-teleport-cause, world-strikeLightning, world-generateTree, shaded-paperlib-teleportAsync-fixture] knownGapHits={world-spawnEntity=1} rawInheritedOwnerHits=1 rawAnonymousOverrideHits=1 rawWrapperGuardHits=0 rawLegacyMainThreadOwnerAsyncRepeatingHits=0 rawCommandDispatchHits=2 asmRouteHits=52
```

## Sync-Return Wait Guard Smoke

Last local smoke run: 2026-05-29

The bridge now guards scheduler fallback paths that must return a value. If the
current thread is a Folia owner/tick thread, the bridge avoids parking that
thread while waiting for another scheduler and emits
`fallback=blocked-sync-return-avoided` in live diagnostics. The static smoke test
cannot simulate Folia's live thread ownership, but it verifies the transformed
jar still passes the full bytecode inventory and route smoke after the guard was
added.

Result:

```text
SMOKE_OK bridgeCalls=22 unsafeCalls=205 bytecodeJars=2 bytecodeClasses=265 bytecodeRequiredHits=39 bytecodeMissing=[scheduler-runTaskAsync, scheduler-runTaskLaterAsync, scheduler-runTaskTimerAsync, scheduler-scheduleSyncDelayed, scheduler-scheduleSyncRepeating, scheduler-scheduleAsyncDelayed, scheduler-scheduleAsyncDelayedLong, scheduler-scheduleAsyncRepeating, player-teleport-cause, world-strikeLightning, world-generateTree, shaded-paperlib-teleportAsync-fixture] knownGapHits={world-spawnEntity=1} rawInheritedOwnerHits=1 rawAnonymousOverrideHits=1 rawWrapperGuardHits=0 rawLegacyMainThreadOwnerAsyncRepeatingHits=0 rawCommandDispatchHits=2 asmRouteHits=52
```

## S_GLOBAL Command Dispatch Smoke

Last local smoke run: 2026-05-28

The probe log showed `Bukkit#dispatchCommand(CommandSender,String)` failing
from a region thread. That bytecode path is now promoted from guard trace to an
exact `S_GLOBAL` rewrite:

```text
[FBB guard-path] class=smoketest.SmokeTarget ... owner=org.bukkit.Bukkit name=dispatchCommand descriptor=(Lorg/bukkit/command/CommandSender;Ljava/lang/String;)Z route=S_GLOBAL guard=CraftServer#dispatchCommand action=rewritten reason=rewritten: command dispatch scheduled through Folia global/entity route; return=scheduled-true
[FBB guard-path] class=smoketest.SmokeTarget ... owner=org.bukkit.Server name=dispatchCommand descriptor=(Lorg/bukkit/command/CommandSender;Ljava/lang/String;)Z route=S_GLOBAL guard=CraftServer#dispatchCommand action=rewritten reason=rewritten: server command dispatch scheduled through Folia global/entity route; return=scheduled-true
[FBB transform] class=smoketest.SmokeTarget ... path=raw-command-dispatch result=patched replacements=2
```

Result:

```text
SMOKE_OK bridgeCalls=22 unsafeCalls=95 rawInheritedOwnerHits=0 rawAnonymousOverrideHits=0 rawWrapperGuardHits=0 rawCommandDispatchHits=2
```

Live boot with the updated jar reached `Done` and showed the transformed probe
class using the same path:

```text
[FBB guard-path] class=dev.fbbprobe.FbbProbeActions ... owner=org.bukkit.Bukkit name=dispatchCommand descriptor=(Lorg/bukkit/command/CommandSender;Ljava/lang/String;)Z route=S_GLOBAL guard=CraftServer#dispatchCommand action=rewritten reason=rewritten: command dispatch scheduled through Folia global/entity route; return=scheduled-true
[FBB transform] class=dev.fbbprobe.FbbProbeActions ... path=raw-command-dispatch result=patched replacements=1
```

`ScoreboardManager#getNewScoreboard()` now has a detached `D_PLAYER_UI`
shim-model route. Older smoke notes below may still show the previous
trace-only state for historical comparison:
Folia currently throws `UnsupportedOperationException` for that API, so the
bridge keeps the evidence loud instead of returning a fake scoreboard or hiding
the failure.

## FBBProbe Package Smoke

Last live-log review: 2026-05-28

`latest.log` proved the first-join probe runs and emits the expected
route/owner/name/descriptor evidence, but it also exposed that the probe itself
was under the bridge's internal package:

```text
FBBProbe.jar//dev.foliabytecodebridge.probe.FbbProbePlugin
java.lang.UnsupportedOperationException: Must use teleportAsync while in region threading
```

That package is intentionally ignored by the raw transformers so the agent does
not rewrite its own runtime classes. The fix was to move only the probe plugin
to `dev.fbbprobe.*`, leaving the bridge self-protection rule intact. This
keeps the probe behavior closer to a normal third-party plugin and should let
the direct `Player#teleport(Location)` probes emit `[FBB teleport-path]` rewrite
evidence on the next live join or `/fbbprobe safe` run.

Verification:

```text
target/FBBProbe.jar contains dev/fbbprobe/FbbProbePlugin.class
SMOKE_OK bridgeCalls=22 unsafeCalls=95 rawInheritedOwnerHits=0 rawAnonymousOverrideHits=0 rawWrapperGuardHits=0
```

## Paper Guard Trace Smoke

Last local smoke run: 2026-05-28

`Paper-main.zip` was audited for Folia/Paper guard sources, then the bridge
added `RawGuardTraceTransformer` as a trace-only path. This does not rewrite new
guards yet; it emits `[FBB guard-path]` evidence with the original
owner/name/descriptor, route family, and guard source.

Probe additions:

```text
/fbbprobe <mode>
```

The Paper guard modes are `chunk`, `server`, `scoreboard`, and grouped `paper`.
The first-join default runs all non-destructive probe groups:

```text
safe,scan,ui,visibility,entity,world,paper
```

`paper` expands to `chunk,server,scoreboard`.

Build and smoke result:

```text
target/FoliaBytecodeBridge.jar contains RawGuardTraceTransformer.class
target/FBBProbe.jar contains dev/fbbprobe/FbbProbePlugin.class
SMOKE_OK bridgeCalls=22 unsafeCalls=95 rawInheritedOwnerHits=0 rawAnonymousOverrideHits=0 rawWrapperGuardHits=0
```

The live `del/plugins` copies were replaced with these rebuilt jars.

## Probe Plugin Artifact

Last probe build: 2026-05-28

Artifact:

```text
target/FBBProbe.jar
target/FBBProbeControl.jar
```

Boot smoke:

```text
[PluginInitializerManager] Initialized 5 plugins
 - FBBProbe, FBBProbeControl, FoliaBytecodeBridge, kit plugin reference, home plugin reference
[FBBProbeControl] [FBB probe] enabled root=/fbbprobecontrol bridgeRole=control-untransformed ...
[FBBProbe] [FBB probe] enabled root=/fbbprobe bridgeRole=target-transformed ...
Done (20.507s)! For help, type "help"
```

The boot also proved the target/control split: `FBBProbe.jar` emitted
`[FBB teleport-path]` rewrites for `dev.fbbprobe.FbbProbeActions`, while
`FBBProbeControl.jar` loaded from the ignored
`dev.foliabytecodebridge.probecontrol.*` package.

Installed test copy:

```text
<server-root>\plugins\FBBProbe.jar
<server-root>\plugins\FBBProbeControl.jar
```

Bytecode inspection confirmed the probe jar contains direct calls for the
families it is meant to exercise, including:

```text
org/bukkit/entity/Player#teleport(Location)Z
org/bukkit/entity/Player#teleport(Location,TeleportCause)Z
org/bukkit/entity/Player#setGameMode(GameMode)V
org/bukkit/entity/Player#setVelocity(Vector)V
org/bukkit/entity/Player#addPotionEffect(PotionEffect)Z
org/bukkit/entity/Player#removePotionEffect(PotionEffectType)V
org/bukkit/World#getBlockAt(Location)
org/bukkit/World#getBlockAt(int,int,int)
org/bukkit/World#getChunkAt(int,int)
org/bukkit/World#getChunkAt(Location)
org/bukkit/World#getChunkAt(Block)
org/bukkit/World#getEntities()
org/bukkit/World#getNearbyEntities(Location,double,double,double)
org/bukkit/World#getEntitiesByClass(Class)
org/bukkit/World#getEntitiesByClass(Class...)
org/bukkit/World#getEntitiesByClasses(Class...)
org/bukkit/World#getLoadedChunks()
org/bukkit/Chunk#getEntities()
org/bukkit/block/Block#setType(Material)V
org/bukkit/entity/Player#openInventory(Inventory)
org/bukkit/entity/Player#hidePlayer(...)
org/bukkit/World#strikeLightning(Location)
org/bukkit/World#strikeLightningEffect(Location)
org/bukkit/World#createExplosion(Location,float)
org/bukkit/World#createExplosion(double,double,double,float)
org/bukkit/World#dropItem(Location,ItemStack)
org/bukkit/World#dropItemNaturally(Location,ItemStack)
org/bukkit/World#spawnEntity(Location,EntityType)
org/bukkit/entity/Player#getNearbyEntities(DDD)
org/bukkit/World#generateTree(Location,TreeType)
```

Use `/fbbprobe <mode>` as the transformed target entry point and
`/fbbprobecontrol <mode>` as the raw Folia control. Start with `safe`, then
rerun targeted buckets such as `scan`, `entity`, `world`, or `paper` when we
want focused evidence. Use `matrix safe` to compare current, entity, async,
global, region, and foreign-region contexts. Use `all` for every
non-destructive bucket. Keep `destructive` for worlds where item drops,
entity spawns, and tree generation are acceptable. The goal is not to make every
probe quiet; it is to separate methods that have a safe rewrite from methods
that should remain diagnostic until the right Folia route is proven.

## FBBProbe Live Boot Smoke

Last live boot smoke: 2026-05-28

Startup shape matched `run.bat`:

```text
java -Xmx16G -javaagent:plugins\FoliaBytecodeBridge.jar -jar folia.jar nogui
```

Result:

```text
[PluginInitializerManager] Initialized 4 plugins
 - FBBProbe (0.1.0-SNAPSHOT), FoliaBytecodeBridge (0.1.0-SNAPSHOT), kit plugin reference (1.22.1), home plugin reference (3.1)
[FoliaBytecodeBridge] Bytecode transformer is installed. mode=JAVA_AGENT
[FBBProbe] [FBB probe] enabled root=/fbbprobe modes=safe, scan, ui, visibility, entity, world, chunk, server, scoreboard, paper, all, destructive
Done (18.527s)! For help, type "help"
exit=0
```

The same boot also reconfirmed the home plugin reference direct teleport rewrite:

```text
[FBB teleport-path] class=com.kixmc.uh.command.HomeCommand ... owner=org.bukkit.entity.Player name=teleport descriptor=(Lorg/bukkit/Location;)Z route=A_ENTITY rule=bukkit-api-owner action=rewritten outcome=rewritten-direct-teleport-async-shim bridge=UnsafeCallBridge#playerTeleport
```

This boot smoke proves the probe jar loads and the bridge is active. The actual
probe command smoke still requires an in-game player because `/fbbprobe` uses
the executing player as the entity/region context.

Follow-up run through `run.bat`: 2026-05-28

Result:

```text
[PluginInitializerManager] Initialized 4 plugins
[FBBProbe] [FBB probe] enabled root=/fbbprobe modes=safe, scan, ui, visibility, entity, world, chunk, server, scoreboard, paper, all, destructive
Done (18.639s)! For help, type "help"
Stopping the server
[FBBProbe] Disabling FBBProbe v0.1.0-SNAPSHOT
```

The command runner timed out after Java had exited because `run.bat` ends with
`pause`. The server itself stopped cleanly and no Java process remained.

The rebuilt probe now emits expected bytecode fields on every probe line:

```text
[FBB probe] root=/fbbprobe bridgeRole=target-transformed mode=<mode> context=<context> intent=<intent> route=<family> api=<method> owner=<expected owner> name=<method> descriptor=<JVM descriptor> action=invoke
[FBB probe] root=/fbbprobe bridgeRole=target-transformed mode=<mode> context=<context> intent=<intent> route=<family> api=<method> owner=<expected owner> name=<method> descriptor=<JVM descriptor> result=failed throwable=<exception>
```

First-join auto probe was added after this smoke. On test servers the first join
per player now runs the non-destructive probe groups:

```text
safe,scan,ui,visibility,entity,world
```

Disable with `-Dfbbprobe.firstJoinModes=off`, or narrow it with a comma-separated
mode list. Destructive probes remain opt-in with
`-Dfbbprobe.firstJoinDestructive=true`.

Follow-up first-join build boot smoke: 2026-05-28

```text
[FBBProbe] [FBB probe] firstJoinModes=safe,scan,ui,visibility,entity,world,paper destructiveRequires=-Dfbbprobe.firstJoinDestructive=true
Done (18.814s)! For help, type "help"
[FBBProbe] Disabling FBBProbe v0.1.0-SNAPSHOT
```

As before, `run.bat` can leave the command wrapper waiting at `pause` after Java
exits. The log confirmed clean server startup and shutdown, and no Java server
process remained.

Meaning:

- The Java agent loaded.
- `smoketest.SmokeTarget` was transformed.
- `org.bukkit.plugin.PluginDescriptionFile#isFoliaSupported()` was transformed under `metadataOverlay=all`, and smoke asserts `[FBB metadata] action=metadata-transform result=patched-return-true`.
- Twenty-one scheduler call sites were routed through `SchedulerBridge`, including `BukkitScheduler` and `BukkitRunnable` overloads.
- One extra smoke-only task-failure probe ran through `SchedulerBridge#runTask`, bringing the total bridge call count to 22 and proving `[FBB task-failure] route=S_GLOBAL` logging.
- Ninety-five high-risk direct Bukkit call sites were routed through `UnsafeCallBridge`, including null-receiver failure probes, proxy-backed plugin-style chains, and generic static teleport helper shims.
- The direct-call smoke now covers `Entity#teleport(Location,TeleportCause)` and `Entity#teleportAsync(Location,TeleportCause)` under `A_ENTITY`.
- The teleport-family smoke asserts `[FBB teleport-path]` evidence for direct Bukkit API rewrites, generic shaded static helper rewrites, and unsupported static helper misses.
- Debug output included transform lines, scheduler breadcrumbs, unsafe-call breadcrumbs, and unsafe-failure breadcrumbs with caller pointers like `SmokeTarget.java:33`.
- Scheduler, unsafe direct-call, unsafe-failure, and task-failure logs now include an official `RouteFamily` label such as `route=S_GLOBAL`, `route=S_ASYNC`, `route=A_ENTITY`, or `route=B_REGION_LOCATION`.
- The smoke test asserts every emitted `route=<...>` value belongs to `RouteFamily` and specifically checks `[FBB scheduler]`, `[FBB unsafe-call]`, `[FBB unsafe-failure]`, and `[FBB task-failure]`.
- Optional transform-skip logs separate expected bootstrap skips from real transform failures with `[FBB transform-skip] reason=bukkit-api-not-visible-yet` when `foliabytecodebridge.traceTransformSkips=true`.
- The smoke fixture now covers declared-owner surprises from Paper's API: player source calls can resolve through `Entity`, `HumanEntity`, or `LivingEntity`.
- The deep smoke fixture covers scheduler overloads, `BukkitRunnable` overloads, null-receiver failure logging, proxy-backed plugin-style chains, and bytecode inventory scanning of the built kit plugin reference and server-utility plugin reference jars.
- The raw-transform smoke fixture verifies the inherited-owner kit plugin reference join path: `PlayersConfigManager#loadConfig` compiles to `PlayersConfigManager$1#runTaskAsynchronously(Plugin)` and must rewrite to `ObjectSchedulerBridge#bukkitRunnableRunTaskAsynchronously`.
- The raw-transform smoke fixture also guards the server-utility plugin reference helper path: `server-utility plugin reference#runTaskAsynchronously(Runnable)` must not be misclassified as a `BukkitRunnable` receiver. Its internal `getScheduler().runTaskAsynchronously(this, run)` call is the bytecode path that belongs in `S_ASYNC`.

## Live `run.bat` Smoke, 2026-05-28 17:06

Live target:

```text
<server-root>
```

The rebuilt jar was copied to `plugins\FoliaBytecodeBridge.jar`, diagnostics were enabled with `plugins\FoliaBytecodeBridge\config.properties`, and the server was started through the existing `run.bat`.

Result:

```text
doneSeen=true
errorSeen=false
attachLines=3
transformLines=12
schedulerLines=7
enableErrors=0
classCastErrors=0
unsupportedErrors=0
```

Important evidence:

```text
[FBB attach] mode=SELF_ATTACH installed=true phase=onLoad exit=0
[FBB transform] class=example.plugin.tasks.InventoryUpdateTaskManager path=raw-scheduler result=patched replacements=1
[FBB scheduler] api=BukkitRunnable#runTaskTimer route=S_GLOBAL policy=global-repeating plugin=kit plugin reference caller=example.plugin.tasks.InventoryUpdateTaskManager#start(InventoryUpdateTaskManager.java:28)
[FBB scheduler] api=BukkitRunnable#runTaskTimerAsynchronously route=S_ASYNC policy=async-repeating plugin=kit plugin reference caller=example.plugin.tasks.PlayerDataSaveTask#start(PlayerDataSaveTask.java:32)
[FBB scheduler] api=BukkitScheduler#runTaskAsynchronously route=S_ASYNC policy=async plugin=server-utility plugin reference caller=example.serverutility.PluginHelper#runTaskAsynchronously(PluginHelper.java:1237)
Done (30.074s)! For help, type "help"
```

This confirms the server-utility plugin reference helper guard: the live call now routes through `BukkitScheduler#runTaskAsynchronously` instead of the incorrect `BukkitRunnable#runTaskAsynchronously` path.

## Live Bytecode-Path Smoke, 2026-05-28 17:12

`traceBytecodePaths=true` was enabled to prove the exact owner/name/descriptor selected by the raw transformer.

Result:

```text
doneSeen=true
errorSeen=false
bytecodePathLines=27
schedulerLines=7
enableErrors=0
classCastErrors=0
unsupportedErrors=0
```

Important evidence:

```text
[FBB bytecode-path] class=example.serverutility.PluginHelper in=runTaskAsynchronously(Ljava/lang/Runnable;)Lorg/bukkit/scheduler/BukkitTask; source=org.bukkit.scheduler.BukkitScheduler#runTaskAsynchronously(Lorg/bukkit/plugin/Plugin;Ljava/lang/Runnable;)Lorg/bukkit/scheduler/BukkitTask; route=S_ASYNC bridge=ObjectSchedulerBridge#runTaskAsynchronously(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Runnable;)Ljava/lang/Object;
[FBB bytecode-path] class=example.plugin.database.MySQLConnection in=getPlayer(Ljava/lang/String;Lexample/plugin/model/GenericCallback;)V source=example.plugin.database.MySQLConnection$1#runTaskAsynchronously(Lorg/bukkit/plugin/Plugin;)Lorg/bukkit/scheduler/BukkitTask; route=S_ASYNC bridge=ObjectSchedulerBridge#bukkitRunnableRunTaskAsynchronously(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
[FBB bytecode-path] class=example.plugin.tasks.InventoryUpdateTaskManager in=start()V source=example.plugin.tasks.InventoryUpdateTaskManager$1#runTaskTimer(Lorg/bukkit/plugin/Plugin;JJ)Lorg/bukkit/scheduler/BukkitTask; route=S_GLOBAL bridge=ObjectSchedulerBridge#bukkitRunnableRunTaskTimer(Ljava/lang/Object;Ljava/lang/Object;JJ)Ljava/lang/Object;
```

The boot-only live run did not trigger the player data load path. The deep bytecode smoke still verifies that exact anonymous-runnable class shape:

```text
[FBB bytecode-path] class=example.plugin.config.PlayersConfigManager in=loadConfig(Ljava/util/UUID;Lexample/plugin/model/GenericCallback;)V source=example.plugin.config.PlayersConfigManager$1#runTaskAsynchronously(Lorg/bukkit/plugin/Plugin;)Lorg/bukkit/scheduler/BukkitTask; route=S_ASYNC bridge=ObjectSchedulerBridge#bukkitRunnableRunTaskAsynchronously(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
```

## Player Join Follow-Up, 2026-05-28 17:18

The player-join retest still failed at the plugin's player config loader. The useful evidence was:

```text
[FBB transform] class=example.plugin.config.PlayersConfigManager$1 path=raw-scheduler result=patched replacements=1
java.lang.UnsupportedOperationException
at org.bukkit.scheduler.BukkitRunnable.runTaskAsynchronously(BukkitRunnable.java:63)
at example.plugin.config.PlayersConfigManager.loadConfig(PlayersConfigManager.java:71)
```

That means the anonymous task class loaded and was transformed, but the outer caller instruction was still invoking the inherited BukkitRunnable method. The next bridge rule adds narrow synthetic overrides to anonymous BukkitRunnable subclasses, so virtual dispatch can route `ExampleTask$1#runTaskAsynchronously(Plugin)` through `ObjectSchedulerBridge` even if the caller class was loaded before self-attach could rewrite it.
- The bytecode inventory scanner found 79 required reusable call-shape hits across kit and server-utility plugin references, including a shaded `PaperLib#teleportAsync(Entity,Location,TeleportCause)` home-command path.
- It records two `World#spawnEntity` hits as known gaps because the local Paper 26.1.2 API jar used to compile the bridge does not expose that method.

## Server-Utility Home Teleport Follow-Up, 2026-05-28

The `/home` path goes through `AsyncTeleport#nowAsync`, then one of these bytecode shapes:

```text
example/serverutility/paperlib/PaperLib#teleportAsync(Entity,Location,TeleportCause)
org/bukkit/entity/Player#teleport(Location,TeleportCause)
org/bukkit/entity/Entity#teleportAsync(Location,TeleportCause)
```

Those now route to `UnsafeCallBridge` under `A_ENTITY`. Static helper matching is
generic by descriptor, not by server-utility plugin reference package name. The shim delegates to
`Entity#teleportAsync` and logs future completion failures instead of swallowing them.
If `/home` still fails live, the important evidence should now be an `[FBB unsafe-call]`
or `[FBB unsafe-failure]` line naming one of those exact APIs, plus an `[FBB teleport-path]`
line showing the owner/name/descriptor and whether it was rewritten or missed.

The JDK also printed warnings about Byte Buddy using `sun.misc.Unsafe` on Java 25. Those warnings did not fail the smoke test.

## Folia Debug Server Smoke

Last server run: 2026-05-28

Setup:

- `debug-server/eula.txt` was set to `eula=true` after explicit user approval.
- Current test servers can use `metadataOverlay=all` to open Folia's plugin metadata gate without editing plugin jars. Older patched kit plugin reference and server-utility plugin reference copies may still exist in `debug-server/original-plugins/` for comparison, but new smoke evidence should prefer the overlay logs.
- The bridge ran as both `-javaagent:plugins/FoliaBytecodeBridge.jar` and a Bukkit plugin.

Result:

```text
SUMMARY notInstalledWarnings=0 transformErrors=0 schedulerLogs=7 enableErrors=0 watchdog=0 exit=0
```

## Live Option 1 Metadata Smoke, 2026-05-28 19:13

Live target:

```text
<server-root>
```

Startup was changed to the intended option-1 path:

```bat
java -Xmx16G -javaagent:plugins\FoliaBytecodeBridge.jar -jar folia.jar nogui
```

`home plugin reference-3.1.jar` was intentionally left without `folia-supported: true` in
its `plugin.yml`. The bridge jar-scan logged:

```text
[FBB metadata] plugin=home plugin reference jar=plugins\home plugin reference-3.1.jar route=S_GLOBAL action=jar-scan mode=all result=overlay-will-force-true note=experimental-load-gate-only-not-thread-safety
[FBB metadata] class=org.bukkit.plugin.PluginDescriptionFile ... action=metadata-transform mode=all result=patched-return-true
```

Result:

```text
LIVE_SMOKE_RESULT sawDone=True sawReject=False sawhome plugin reference=True sawNoClassDef=False
```

Folia initialized all three plugins and did not reject home plugin reference for missing
Folia metadata:

```text
[PluginInitializerManager] Initialized 3 plugins
FoliaBytecodeBridge, kit plugin reference, home plugin reference
```

The same run showed useful scheduler evidence for the newly loaded home plugin:

```text
[FBB scheduler] api=BukkitScheduler#runTaskLater route=S_GLOBAL policy=global-delayed plugin=home plugin reference caller=com.kixmc.uh.core.Main#onEnable(Main.java:99)
```

Observed routes:

- `BukkitRunnable#runTaskTimer` from `kit plugin reference` inventory update startup.
- `BukkitRunnable#runTaskTimerAsynchronously` from `kit plugin reference` player data save startup.
- `BukkitScheduler#scheduleSyncDelayedTask` and `scheduleSyncRepeatingTask` through server-utility plugin reference' helper wrappers.
- `BukkitScheduler#runTaskAsynchronously` through server-utility plugin reference' helper wrapper.

The raw scheduler transformer caught real plugin bytecode where Byte Buddy's typed substitution cannot safely resolve Paperclip plugin classloaders.

## Live Server Target Smoke

Last live target run: 2026-05-28

Target:

```text
<server-root>
```

Startup shape:

```text
java -Xms2G -Xmx4G -Dfoliabytecodebridge.traceSchedulerCalls=true -Dfoliabytecodebridge.traceUnsafeCalls=true -javaagent:plugins\FoliaBytecodeBridge.jar -jar folia.jar nogui
```

The live folder also has `RUN-FBB.bat` with the same important bridge flags so the agent is not accidentally skipped.

Result:

```text
SUMMARY freshLog=True done=1 schedulerLogs=7 unsafeCalls=0 unsafeFailures=0 taskFailures=0 transformErrors=0 enableErrors=0 watchdog=0 unsupported=0 exit=0 stopped=True
```

Observed route-family evidence:

- `S_GLOBAL`: kit plugin reference `BukkitRunnable#runTaskTimer`; server-utility plugin reference `scheduleSyncDelayedTask` and `scheduleSyncRepeatingTask`.
- `S_ASYNC`: kit plugin reference `BukkitRunnable#runTaskTimerAsynchronously`; server-utility plugin reference `runTaskAsynchronously`.
- `A_ENTITY`, `B_REGION_LOCATION`, `C_REGION_BLOCK`, `D_PLAYER_UI`, `F_PLAYER_VISIBILITY`, `G_WORLD_SCAN_SPLIT`: no startup-time unsafe direct calls were executed during this smoke. This is useful negative evidence, not a reason to add broader logging.

The Java-agent live run confirms the most deterministic startup path. A later
self-attach smoke, recorded below, proves the plugin-only startup can now attach
and retransform already-loaded plugin classes on this test server, but it remains
experimental because attach support and plugin load order can vary.

## Live Self-Attach Smoke

Last live self-attach run: 2026-05-28

Startup shape:

```text
java -Xmx16G -Dfoliabytecodebridge.traceSchedulerCalls=true -Dfoliabytecodebridge.traceUnsafeCalls=true -jar folia.jar nogui
```

Result:

```text
[FBB attach] mode=SELF_ATTACH installed=true phase=onLoad exit=0 elapsedMs=10642
[FBB attach] mode=SELF_ATTACH retransformCandidates=90 retransformAttempted=90 retransformFailed=0
```

The late self-attach path patched already-loaded plugin classes before enable,
including:

```text
[FBB transform] class=example.serverutility.PluginHelper loader=org.bukkit.plugin.java.PluginClassLoader path=raw-scheduler result=patched
[FBB transform] class=example.plugin.tasks.PlayerDataSaveTask loader=org.bukkit.plugin.java.PluginClassLoader path=raw-scheduler result=patched
[FBB transform] class=example.plugin.tasks.InventoryUpdateTaskManager loader=org.bukkit.plugin.java.PluginClassLoader path=raw-scheduler result=patched
```

Startup then emitted scheduler evidence and reached `Done` without the previous
Folia `UnsupportedOperationException` enable failures:

```text
[FBB scheduler] api=BukkitRunnable#runTaskTimer route=S_GLOBAL policy=global-repeating plugin=kit plugin reference caller=example.plugin.tasks.InventoryUpdateTaskManager#start(InventoryUpdateTaskManager.java:28)
[FBB scheduler] api=BukkitRunnable#runTaskTimerAsynchronously route=S_ASYNC policy=async-repeating plugin=kit plugin reference caller=example.plugin.tasks.PlayerDataSaveTask#start(PlayerDataSaveTask.java:32)
[FBB scheduler] api=BukkitScheduler#scheduleSyncDelayedTask route=S_GLOBAL policy=global plugin=server-utility plugin reference caller=example.serverutility.PluginHelper#scheduleSyncDelayedTask(PluginHelper.java:1252)
```

An earlier self-attach pass logged one typed-transform diagnostic on a server
support library:

```text
[FBB transform-error] class=io.leangen.geantyref.TypeToken$2 message=Cannot resolve T from io.leangen.geantyref.TypeToken$2(?)
```

That did not block the raw scheduler patch path. The typed transformer now skips
`io.leangen.*` as a narrow server-library ignore so future live evidence stays
focused on plugin-owned classes. The follow-up live run reported:

```text
attachWarnings=0
enableErrors=0
rawPatched=11
scheduler=7
transformErrors=0
unsupported=0
```

## A_ENTITY Direct Teleport Smoke

Last local smoke run: 2026-05-28

home-command-style home commands exposed this generic bytecode shape:

```text
build Location from stored x/y/z/yaw/pitch
Location#add(0.5, 0.0, 0.5)
org/bukkit/entity/Player#teleport(Lorg/bukkit/Location;)Z
```

The bridge now treats that as a direct Bukkit-owner `A_ENTITY` path, not as
home plugin reference support. The smoke fixture emitted:

```text
[FBB teleport-path] class=smoketest.SmokeTarget ... owner=org.bukkit.entity.Player name=teleport descriptor=(Lorg/bukkit/Location;)Z route=A_ENTITY rule=bukkit-api-owner action=rewritten outcome=rewritten-direct-teleport-async-shim bridge=UnsafeCallBridge#playerTeleport
[FBB teleport-path] class=smoketest.SmokeTarget ... owner=org.bukkit.entity.Player name=teleport descriptor=(Lorg/bukkit/Location;Lorg/bukkit/event/player/PlayerTeleportEvent$TeleportCause;)Z route=A_ENTITY rule=bukkit-api-owner action=rewritten outcome=rewritten-direct-teleport-async-shim bridge=UnsafeCallBridge#playerTeleport
```

Result:

```text
SMOKE_OK bridgeCalls=22 unsafeCalls=95 bytecodeJars=2 bytecodeClasses=2394 bytecodeRequiredHits=79 knownGapHits={world-spawnEntity=2} rawInheritedOwnerHits=1 rawAnonymousOverrideHits=1 rawWrapperGuardHits=1
```
