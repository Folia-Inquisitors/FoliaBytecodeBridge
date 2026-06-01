# Live Probe Triage

Last reviewed log:

```text
<server-root>\logs\latest.log
```

This document separates "the probe completed" from "the call is universally
thread-safe." A completed probe only proves that the method worked from the
current test context, usually the joining player's owning region thread.

## Current Evidence

| Evidence | APIs | Meaning |
| --- | --- | --- |
| Route rule registry | exact owner/name/descriptor entries in `RouteRuleRegistry` | The route-family architecture map is now a code registry, not scattered strings. Exact rules carry owner, route, bridge method, return policy, status, and note; dynamic shapes still log as `dynamic-or-unregistered` until promoted. |
| Startup probe | `mode=startup context=global,async,region` | The probe now emits boot-time evidence without waiting for player join. It covers server/world/chunk/unowned-scoreboard shapes plus no-player recovery candidates, and clearly blocks player-only paths with `reason=use first-join...`. |
| First-join matrix | `safe,scan,ui,visibility,entity,world,paper` across `current,entity,async,global,region,foreign-region` | Once a real player exists, the probe now gathers player/entity/UI/world evidence from the major Folia execution contexts automatically. This is intentionally noisy and can be reduced with `-Dfbbprobe.firstJoinContexts=current`. |
| Rewritten | `BukkitScheduler#scheduleAsyncRepeatingTask(Plugin,Runnable,long,long): int` | FAWE exposed this legacy async repeating scheduler shape. It is now a general `S_ASYNC` raw scheduler route to `ObjectSchedulerBridge#scheduleAsyncRepeatingTask`, with `[FBB bytecode-path]` and `[FBB scheduler]` evidence from `BukkitTaskManager#repeatAsync`. |
| Rewritten | `Player#teleport(Location)`, `Player#teleport(Location,TeleportCause)` | Folia patch makes sync teleport throw; bridge rewrote these to the `A_ENTITY` teleportAsync shim. |
| Rewritten | `Bukkit#dispatchCommand(CommandSender,String)`, `Server#dispatchCommand(CommandSender,String)` | Folia requires entity senders on the entity scheduler and console/global senders on the global region. The bridge now schedules that route and returns `scheduled-true` because the old boolean result is not synchronously knowable. |
| Failed guard | `ScoreboardManager#getNewScoreboard()` | Folia marks scoreboard creation unsupported today. Do not hide this; use the probe fingerprint to decide whether a future bytecode route can preserve semantics. |
| Completed guard | `World#loadChunk(int,int)` | Completed because the test chunk was in the active region. This is not proof that arbitrary chunk loads are safe. |
| Completed context reads | `getLocation`, `getWorld`, `getBlockAt`, `getChunkAt`, `getEntities`, `getNearbyEntities` | Useful route evidence, but not automatic rewrite candidates. They may still fail off-region or from async code. |
| Completed context mutations | `setVelocity`, `setGameMode`, `addPotionEffect`, `Block#setType`, sound/effect calls | Worked from the current owning region. Promote only when receiver/location context can be preserved. |
| Plugin binary failure | FAWE `PaperweightFaweWorldNativeAccess` -> `MinecraftServer.currentTick` | `NoSuchFieldError`, not a Folia thread guard. The evidence tool now classifies this as `NMS_VERSION_COMPAT` and emits `[FBB compat]` with owner/name/descriptor, plugin jar, caller, and the next adapter-research step. |
| Server member map | `MinecraftServer.currentTick:I` | `[FBB member-map]` finds `net.minecraft.server.MinecraftServer` in `versions\26.1.2\folia-26.1.2.jar`, but `exactMatch=false`. Nearby candidates include `currentTickStart:J` and `tickTaskTickCount:AtomicInteger`, which require adapter research before any rewrite. |

## 2026-05-29 01:03 Startup Probe Pass

The live boot reached `Done` and `trigger=startup-auto` fired before any player
joined. Useful lines included:

- `World#getChunkAt(Location)` from the target probe failed Folia's async chunk
  check from a global context, then completed through the bridge's
  `region-scheduler-by-location` fallback.
- `World#getNearbyEntities(Location,double,double,double)` failed with
  `classification=thread-guard` and `route=G_WORLD_SCAN_SPLIT`. This now uses
  the bounded-scan model: retry on the candidate location region, but keep
  `[FBB unsafe-failure]` loud if Folia rejects a cross-region AABB.
- `Chunk#getEntities` emitted `route=G_WORLD_SCAN_SPLIT family=chunk-scan
  next=region-scheduler-by-chunk` and completed through the owner-aware route.
- `World#loadChunk(int,int)` emitted the original Folia guard and then the
  scheduler fallback evidence.

## 2026-05-29 01:49 Invokedynamic Method References

FAWE exposed a generic Java method-reference shape:

```text
com.fastasyncworldedit.bukkit.adapter.IBukkitAdapter#getEntities(World)
owner=org.bukkit.World name=getEntities descriptor=()Ljava/util/List;
```

The source shape is `world::getEntities`, which compiles to an `invokedynamic`
bootstrap handle instead of a normal `invokeinterface` instruction. The bridge
now rewrites matching method-reference handles for known unsafe
owner/name/descriptor pairs:

```text
[FBB guard-path] ... route=G_WORLD_SCAN_SPLIT guard=CraftWorld#entity-scan action=rewritten reason=rewritten: invokedynamic method reference handle routed through bridge
```

This is not FAWE-specific support. Any plugin with the same method-reference
bytecode shape should route through the same bridge method. For whole-world
scans such as `World#getEntities`, the bridge keeps the result under
`G_WORLD_SCAN_SPLIT` and now uses `next=split-scan-by-loaded-chunks`.
After startup evidence proved the direct-first call only raised a Folia guard
before this same split, the proven whole-world scan shapes now use
`policy=preemptive-safe` on Folia.

## 2026-05-29 02:00 Whole-World Split Scan

The `G_WORLD_SCAN_SPLIT` route now has an experimental runtime translation for
whole-world entity scans:

- `World#getEntities()` -> preemptive loaded-chunk split on Folia
- `World#getEntitiesByClass(...)` -> same split, then type filtering
- `World#getEntitiesByClasses(...)` -> same split, then type filtering
- `World#getLoadedChunks()` -> direct first, global scheduler retry if Folia
  rejects the loaded-chunk index
- `Chunk#getEntities()` -> direct first, region scheduler retry for the chunk

The important evidence lines are:

```text
[FBB unsafe-call] api=World#getEntities route=G_WORLD_SCAN_SPLIT next=split-scan-by-loaded-chunks
[FBB unsafe-call] api=World#getEntities route=G_WORLD_SCAN_SPLIT next=split-scan-by-loaded-chunks policy=preemptive-safe
[FBB unsafe-call] api=World#getEntities route=G_WORLD_SCAN_SPLIT next=split-scan-by-loaded-chunks fallback=preemptive-split-scan
[FBB unsafe-call] api=World#getLoadedChunks route=G_WORLD_SCAN_SPLIT next=world-loaded-chunk-index
[FBB unsafe-call] api=Chunk#getEntities route=G_WORLD_SCAN_SPLIT next=region-scheduler-by-chunk fallback=split-scan-region-scheduler
[FBB unsafe-call] api=World#getEntities route=G_WORLD_SCAN_SPLIT next=split-scan-by-loaded-chunks fallback=split-scan-complete
```

If any step still fails, the bridge logs `[FBB unsafe-failure]` for the exact
API and route instead of swallowing the problem.

## 2026-05-28 22:31 Probe Pass

The target/control split worked:

- Both `FBBProbe` and `FBBProbeControl` loaded.
- `Bukkit#dispatchCommand(CommandSender,String)` failed in the control probe but
  not in the transformed target, so the `S_GLOBAL` command route is still
  confirmed.
- The transformed target still exposed escaped bytecode for
  `Entity/Player#getNearbyEntities` and `World#getChunkAt`/`loadChunk` from
  async/global contexts. Those are now promoted to a raw direct-unsafe rewrite
  with scheduler retry evidence.
- `ScoreboardManager#getNewScoreboard()` still fails in every context with
  `classification=unsupported-operation`; keep it trace-only until a real
  scoreboard route is proven.

## Paper vs Folia Comparison

Paper generally uses Spigot's `AsyncCatcher` or ordinary synchronous code for
these paths. Folia adds stricter region/global guards:

| Bukkit surface | Paper shape | Folia shape | Route family |
| --- | --- | --- | --- |
| `Entity/Player#teleport` | Calls `teleport0` synchronously | `teleport0` throws "Must use teleportAsync while in region threading" | `A_ENTITY` |
| `Bukkit/Server#dispatchCommand` | `AsyncCatcher.catchOp` only | Checks entity owner thread or global tick thread; also adds `dispatchCmdAsync` helper | `S_GLOBAL` or entity sender route |
| `World#loadChunk` | Sync chunk load guarded by AsyncCatcher | Requires the correct chunk/region tick thread | `B_REGION_LOCATION` |
| `World#refreshChunk` | Chunk refresh guarded by tick-thread checks | Requires the owning chunk/region tick thread | `B_REGION_LOCATION` |
| `ScoreboardManager#getNewScoreboard` | Creates/registers scoreboard | Immediately throws `UnsupportedOperationException` | `D_PLAYER_UI` |
| `World/Server#playSound` | Sync send with AsyncCatcher around random seed/global state | Guarded by sound location, emitter, or global state | `B_REGION_LOCATION` / `S_GLOBAL` |

## Probe Logging Policy

Future probe lines include `bridgeRole=`, `context=`, and `intent=` fields:

```text
bridgeRole=target-transformed
bridgeRole=control-untransformed
context=current
context=async
context=global
context=entity
context=region
context=foreign-region
intent=context-read
intent=context-mutation
intent=scan-context-check
intent=rewrite-candidate
intent=guard-probe
intent=global-thread-check
```

This keeps safe-looking completions from being treated as proof that the method
needs, or already has, a bridge rewrite. The bridge should promote only paths
that have exact owner/name/descriptor evidence and a Folia route that preserves
the old method's return behavior.

## Route Model Promotion Loop

The current development loop is:

1. Analyzer pass records owner/name/descriptor evidence and route family.
2. Probe/control runs show whether Folia rejects the raw call and whether the
   bridge route changes behavior.
3. A route that has enough evidence becomes one `RouteRuleRegistry` entry with a
   return policy.
4. The transformer uses that registry entry for bytecode replacement.
5. Runtime logs keep `[FBB unsafe-call]`, `[FBB unsafe-failure]`, and
   `[FBB task-failure]` visible so failures stay classified instead of hidden.

This is still experimental. "Promoted" means evidence-backed and organized, not
universally safe. Return-value routes remain the dangerous part because the
bridge has to preserve old synchronous Bukkit expectations without blocking the
wrong Folia owner thread.

## Target vs Control

Use `FBBProbe` as the transformed target and `FBBProbeControl` as the raw Folia
control:

```text
/fbbprobe matrix safe
/fbbprobecontrol matrix safe
```

If the target completes and the control fails for the same owner/name/descriptor
and context, the bridge probably owns that bytecode path. If both fail, the path
is still missing or intentionally trace-only. If both complete only in
`context=current` or `context=entity`, that method may be context-safe but still
not safe from async/global/foreign-region execution.

## Current Action Items

- Restart with the latest probe jars and read startup-auto lines first. The
  target/control pair should now expose the broad no-player route set without
  requiring a join. Player/entity/UI receiver paths still require first-join
  because they need an actual player owner. The first-join pass now includes
  inherited owner shapes such as `HumanEntity`, `LivingEntity`, and `Entity`, so
  misses identify the exact bytecode owner used by a plugin.
- Keep `ScoreboardManager#getNewScoreboard()` loud. The probe now fingerprints
  `UnsupportedOperationException` with the current thread, plugin frame,
  Folia/CraftBukkit guard frame, and scheduler frame. If that evidence later
  shows a routeable bytecode shape, promote it deliberately; do not quiet it by
  default.

## 2026-05-28 23:00 Scheduler Shape Fix

The live probe confirmed that the raw direct-unsafe route was selected correctly
for `World#getChunkAt(Location)`, but the fallback failed with:

```text
throwable=java.lang.IllegalArgumentException: wrong number of arguments: 2 expected: 3
```

This was not a bad bytecode category. It was a bridge invocation-shape bug: the
region scheduler reflection signature was `Plugin, Location, Consumer`, but the
bridge passed only `Plugin, Consumer`.

Fix applied:

- `UnsafeCallBridge#callRegionScheduler(... Location ...)` now passes the
  `Location` argument to Folia's region scheduler.
- Keep this as evidence that route selection and scheduler invocation shape are
  separate debugging layers.
- If a future path logs `[FBB unsafe-failure]` after
  `fallback=scheduler-after-thread-guard`, first verify the scheduler argument
  shape before changing the route family.

## 2026-05-28 23:12 World Location Effects

The live log had no remaining bridge failures, but it still showed
`World#playSound(Location,...)` as a `B_REGION_LOCATION` trace-only guard path.
That call has a clear location owner, so it is now promoted into the same
evidence-preserving flow as chunk reads:

```text
original call -> Folia thread guard -> [FBB unsafe-call] fallback=scheduler-after-thread-guard -> region scheduler
```

Promoted raw direct-unsafe paths:

- `World#playSound(Location,Sound,float,float)`
- `World#playSound(Location,String,float,float)`
- `World#playSound(Location,Sound,SoundCategory,float,float)`
- `World#playSound(Location,String,SoundCategory,float,float)`
- `World#dropItem(Location,ItemStack)`
- `World#dropItemNaturally(Location,ItemStack)`
- `World#generateTree(Location,TreeType)`
- `World#strikeLightning(Location)`
- `World#strikeLightningEffect(Location)`
- `World#createExplosion(Location,float)`
- `World#createExplosion(double,double,double,float)`

`ScoreboardManager#getNewScoreboard()` remains trace-only under `D_PLAYER_UI`.
The evidence still shows Folia rejecting it from every tested scheduler
context, and the Folia-compatible reference plugins generally avoid that Bukkit
factory path. Player-owned scoreboard calls now have their own diagnostics:
`Player#getScoreboard`, `Player#setScoreboard`, `Scoreboard#getTeam`,
`Scoreboard#registerNewTeam`, and `Team#setOption/addEntry/removeEntry`.

## 2026-05-29 00:19 Scoreboard Hard-Unsupported Evidence

The live run moved scoreboard evidence to `D_PLAYER_UI`, which confirms the
route-family cleanup landed on the server. It also proved that several Bukkit
scoreboard model mutations are not scheduler problems in Folia 26.1.x:

- `ScoreboardManager#getNewScoreboard()` fails at
  `CraftScoreboardManager#getNewScoreboard`.
- `Player#setScoreboard(Scoreboard)` fails at
  `CraftScoreboardManager#setPlayerBoard`.
- `Scoreboard#registerNewTeam(String)` fails at
  `CraftScoreboard#registerNewTeam`.

Folia's `Region-Threading-Base` patch marks these with
`UnsupportedOperationException // Folia - not supported yet`, so the bridge
must not retry them through region/global/entity schedulers. The probe now
keeps the first failing owner/name/descriptor loud and reports dependent
`Team#setOption/addEntry/removeEntry` probes as `result=blocked
blockedBy=Scoreboard#registerNewTeam` when no team can be created. That keeps
the evidence precise instead of producing repeated stack traces for the same
root blocker under different Team method names.

## 2026-05-29 12:57 Bounded Scan And Scoreboard Model Promotion

Smoke promoted two evidence-backed paths without adding plugin-specific rules:

- `World#getNearbyEntities(Location,double,double,double)` stays in
  `G_WORLD_SCAN_SPLIT`, but now uses `policy=preemptive-safe` on Folia and
  `fallback=preemptive-bounded-split-scan`. The bridge enumerates loaded chunks,
  scans only chunks intersecting the requested bounds through the owning region
  scheduler, then filters entities to the original AABB before returning.
- `ScoreboardManager#getNewScoreboard()` now rewrites under `D_PLAYER_UI` to a
  detached scoreboard shim model. Logs show `policy=shim-model
  result=detached-scoreboard`; `Scoreboard#registerNewTeam` and
  `Team#setOption/addEntry/removeEntry` are modeled when their receiver is that
  detached board.
- `Player#setScoreboard(detachedBoard)` is intentionally `action=deferred-apply`
  because Folia still hard-disables Bukkit scoreboard board assignment. This is
  evidence-preserving model state, not a hidden scheduler retry.

Smoke evidence to look for:

```text
[FBB unsafe-call] api=World#getNearbyEntities(Location,double,double,double) route=G_WORLD_SCAN_SPLIT ... policy=preemptive-safe
[FBB unsafe-call] api=World#getNearbyEntities(Location,double,double,double) route=G_WORLD_SCAN_SPLIT ... fallback=preemptive-bounded-split-scan
[FBB unsafe-call] api=ScoreboardManager#getNewScoreboard route=D_PLAYER_UI ... policy=shim-model ... result=detached-scoreboard
```

## 2026-05-29 14:41 Region Owner-Check Promotion

The remaining location/chunk-owned world routes now use a preemptive
`B_REGION_LOCATION` owner-check path instead of direct-first thread-guard retry:

- chunk access/load/refresh: `World#getChunkAt(...)`, `World#loadChunk(int,int)`,
  `World#refreshChunk(int,int)`
- location-owned mutations/effects: drop item, spawn entity, generate tree,
  lightning, zero-power explosion probes, and location sound overloads

Expected diagnostics:

```text
[FBB unsafe-call] api=World#getChunkAt(int,int) route=B_REGION_LOCATION ... policy=preemptive-safe ... ownerCheck=current-region-owned
[FBB unsafe-call] api=World#getChunkAt(int,int) route=B_REGION_LOCATION ... policy=preemptive-safe ... ownerCheck=schedule-owner-region
[FBB unsafe-call] api=World#getChunkAt(int,int) route=B_REGION_LOCATION ... fallback=preemptive-region-scheduler
```

If the owner check itself fails, the bridge logs
`ownerCheck=failed-direct-preserve-original` and runs the original call. That is
intentional evidence preservation: a failed ownership probe should not hide the
real bytecode path behind a bridge scheduler/runtime failure.

## 2026-05-29 15:35 First-Join Player Owner Matrix

The no-player startup pass cannot exercise APIs that require a live entity
owner. The first-join pass now expands the player-owned matrix without adding
new commands:

- `D_PLAYER_UI`: `HumanEntity#openInventory` and `HumanEntity#closeInventory`
  beside the existing `Player` inventory probes.
- `A_ENTITY`: `HumanEntity#setGameMode`, `Entity#setVelocity`,
  `LivingEntity#addPotionEffect`, `LivingEntity#removePotionEffect`, and
  location-owned `Player#playSound` overloads.
- `S_GLOBAL`: `Bukkit#getOnlinePlayers` and `Bukkit#getPlayer(UUID)` so global
  player lookup behavior is logged near the player-owned probes.

These are bytecode-owner probes, not plugin-specific support. They exist because
real plugins often call inherited Bukkit methods through a parent interface or
class, and the bridge transformer must see that exact owner/name/descriptor to
prove a universal rule.

## 2026-05-29 15:40 Player Scoreboard Model Registry

The first-join probe proved that player/entity/UI owner shapes complete, but
native Bukkit scoreboard calls still hard-fail inside Folia's CraftBukkit
scoreboard implementation. The next `D_PLAYER_UI` step is now promoted:

- `Player#getScoreboard()` returns a player-owned detached scoreboard model on
  Folia instead of exposing the hard-disabled native board to transformed
  plugin bytecode.
- `Scoreboard#registerNewTeam`, `Scoreboard#getTeam`, `Team#setOption`,
  `Team#addEntry`, and `Team#removeEntry` operate on that model when the
  receiver is bridge-owned.
- `Player#setScoreboard(model)` records the modeled board as the player's active
  bridge board and logs `action=model-retained result=assigned`. It does not
  call `CraftScoreboardManager#setPlayerBoard`.
- Native/non-modeled scoreboards still fall through the loud
  `scoreboard-hard-unsupported-*` diagnostics so unsupported owners remain
  visible instead of being silently accepted.

Expected live evidence after the updated bridge jar:

```text
[FBB unsafe-call] api=Player#getScoreboard route=D_PLAYER_UI ... next=scoreboard-player-owned-model-read ... policy=shim-model ... result=player-scoreboard
[FBB unsafe-call] api=Scoreboard#registerNewTeam(String) route=D_PLAYER_UI ... policy=shim-model action=modeled result=team
[FBB unsafe-call] api=Player#setScoreboard route=D_PLAYER_UI ... policy=shim-model action=model-retained result=assigned
```

## 2026-05-29 16:00 Scoreboard Objective/Score Model

The next `D_PLAYER_UI` model pass promotes objective and score bytecode shapes
into the same bridge-owned scoreboard model. This is still not a scheduler hop:
Folia rejects native Bukkit scoreboard mutation because the model is UI/global
state, so the bridge keeps the state in a detached/player-owned model instead.

- `Scoreboard#registerNewObjective(...)` now routes by exact Bukkit
  owner/name/descriptor for the common string, component, criteria, and
  render-type overloads.
- `Scoreboard#getObjective(String)`, `Scoreboard#getObjective(DisplaySlot)`,
  and `Scoreboard#getObjectives()` read from the model.
- `Objective#setDisplaySlot`, `Objective#setDisplayName`, `Objective#getScore`,
  `Score#setScore`, `Score#getScore`, and `Score#resetScore` are modeled under
  `D_PLAYER_UI`.
- Startup probes now include objective/score calls under the existing
  `/fbbprobe` startup path, so a server restart produces evidence without
  requiring a player join.

Expected bridge evidence:

```text
[FBB unsafe-call] api=Scoreboard#registerNewObjective(String,String,String) route=D_PLAYER_UI ... next=scoreboard-model-objective-create ... policy=shim-model ... result=objective
[FBB unsafe-call] api=Objective#setDisplaySlot(DisplaySlot) route=D_PLAYER_UI ... next=scoreboard-model-objective-mutation ... policy=shim-model ... result=display-slot-set
[FBB unsafe-call] api=Objective#getScore(String) route=D_PLAYER_UI ... next=scoreboard-model-score-read ... policy=shim-model ... result=score
[FBB unsafe-call] api=Score#setScore(int) route=D_PLAYER_UI ... next=scoreboard-model-score-mutation ... policy=shim-model ... result=score-set
```

Remaining note: model-retained scoreboards are not yet packet-rendered to the
client. This pass preserves legacy scoreboard state without touching Folia's
hard-disabled `CraftScoreboardManager#setPlayerBoard` path.

## 2026-05-29 16:20 Scoreboard Display State Model

The latest `D_PLAYER_UI` pass expands the scoreboard shim from structural
objective/team/score operations into display-state operations that plugins use
for tab lists, boards, and nametag teams:

- `Objective#displayName(Component)` and
  `Objective#numberFormat(NumberFormat)` now mutate the bridge objective model.
- `Score#customName(Component)` and `Score#numberFormat(NumberFormat)` now
  mutate the bridge score model.
- `Team#setDisplayName`, `Team#displayName(Component)`, `Team#setPrefix`,
  `Team#prefix(Component)`, `Team#setSuffix`, `Team#suffix(Component)`,
  `Team#setColor(ChatColor)`, `Team#color(NamedTextColor)`,
  `Team#setAllowFriendlyFire`, and `Team#setCanSeeFriendlyInvisibles` now mutate
  the bridge team model.

This is still model state, not visible packet rendering. The bridge intentionally
keeps native/non-bridge scoreboard receivers loud under `D_PLAYER_UI`, because
Folia hard-disables the CraftBukkit scoreboard manager path and a scheduler hop
would only hide the real incompatibility.

Expected smoke/live evidence:

```text
[FBB unsafe-call] api=Team#setPrefix(String) route=D_PLAYER_UI ... next=scoreboard-model-team-mutation ... policy=shim-model ... result=team-prefix
[FBB unsafe-call] api=Objective#displayName(Component) route=D_PLAYER_UI ... next=scoreboard-model-objective-mutation ... policy=shim-model ... result=objective-display-name
[FBB unsafe-call] api=Score#customName(Component) route=D_PLAYER_UI ... next=scoreboard-model-score-mutation ... policy=shim-model ... result=score-custom-name
```

## 2026-05-29 17:20 Scan Probe Setup Attribution

The live first-join matrix showed two unclassified target-probe task exceptions
before the probe could emit its normal owner/name/descriptor failure line:

```text
Location#getChunk -> World#getChunkAt(Location) -> Async chunk retrieval
```

That was a probe setup bug, not a new bridge route. `runScanProbes` was
resolving the `Chunk` before entering the `Chunk#getEntities` probe wrapper. The
target probe now resolves the chunk inside the probe body with
`World#getChunkAt(Location)`, so async/global failures are attributed to the
intended `B_REGION_LOCATION -> G_WORLD_SCAN_SPLIT` bytecode path. The control
probe keeps its raw behavior so Folia's baseline failure remains visible.

## 2026-05-29 17:31 Block-Owned Material Read/Mutation

The next live log showed the target probe escaping before `Block#setType` could
run because the setup line called `Block#getType()` from async/global contexts:

```text
CraftBlock#getType -> Cannot read world asynchronously
```

This is a real `C_REGION_BLOCK` owner path. The bridge now rewrites
`Block#getType()` and promotes `Block#setType(Material)` to the same preemptive
owner-check route used by other proven region operations. Owned block calls stay
direct; unowned block calls schedule on the block's owning region and keep
`[FBB unsafe-call]` evidence with `ownerCheck=` and
`fallback=preemptive-region-scheduler`.

## 2026-05-29 17:53 Entity Potion Effect Mutation

The latest first-join target probe still failed for potion-effect mutations from
async/global contexts:

```text
Player#addPotionEffect(PotionEffect) -> Asynchronous effect add
LivingEntity#addPotionEffect(PotionEffect) -> Cannot add effects to entities asynchronously
```

This belongs to `A_ENTITY`, not `D_PLAYER_UI` or region ownership. The bridge now
rewrites both player-owned and inherited `LivingEntity` bytecode owners for
effect add/remove descriptors. On Folia, the wrapper checks whether the current
thread owns the receiver entity; owned calls stay direct, unowned calls run
through the receiver's entity scheduler before invoking Bukkit. Logs include
`ownerCheck=current-entity-owned|schedule-owner-entity` and
`fallback=preemptive-entity-scheduler`.

## 2026-05-29 18:22 Player Inventory UI Ownership

The latest log showed transformed target probes still failing inventory/menu
operations when the caller ran from async/global contexts:

```text
Player#openInventory(Inventory) -> RunningOnDifferentThreadException
Player#closeInventory() -> InventoryCloseEvent may only be triggered synchronously
HumanEntity#openInventory(Inventory) -> InventoryOpenEvent may only be triggered synchronously
HumanEntity#closeInventory() -> InventoryCloseEvent may only be triggered synchronously
```

This is a real `D_PLAYER_UI` owner route, not a scoreboard-model problem and not
a plugin-specific command issue. Inventory windows are player-owned UI, so the
bridge now rewrites both direct `Player` bytecode owners and inherited
`HumanEntity` bytecode owners to preemptively run on the receiver's entity
scheduler. The direct call is still preserved when the current Folia thread
already owns the player/human entity.

Expected evidence:

```text
[FBB guard-path] ... owner=org.bukkit.entity.Player name=openInventory descriptor=(Lorg/bukkit/inventory/Inventory;)Lorg/bukkit/inventory/InventoryView; route=D_PLAYER_UI action=rewritten
[FBB unsafe-call] api=Player#openInventory route=D_PLAYER_UI family=player next=entity-scheduler-ui ... policy=preemptive-safe
```

If a future UI failure remains, it should be grouped under `D_PLAYER_UI` with the
exact owner/name/descriptor. That keeps menu/inventory ownership separate from
the detached scoreboard shim path.

## 2026-05-29 19:14 Return-Value Scheduler Wait Boundary

The next startup probe run showed a different class of issue: not a missing
bytecode route, but an unsafe return-value wait. Calls such as
`World#getChunkAt(...)` and `Entity#getNearbyEntities(...)` were correctly
classified under `B_REGION_LOCATION`, `C_REGION_BLOCK`, or `G_WORLD_SCAN_SPLIT`,
but the bridge then scheduled the work to another owner and waited synchronously
while already running on a Folia region/global owner thread.

That can turn one legacy unsafe call into a server stall:

```text
UnsafeCallBridge#waitForScheduled
UnsafeCallBridge#callRegionScheduler
FoliaWatchdogThread: Global region has not responded
```

The bridge now has a sync-return boundary. If a return-value route would need to
hop to another scheduler and block from a Folia owner thread, it logs:

```text
fallback=blocked-sync-return-avoided action=direct-preserve-original reason=return-value-scheduler-wait-from-folia-thread
```

Then it preserves the original Bukkit/Folia failure instead of parking the tick
thread. This is not a rejection layer and not a quiet bypass; it is evidence that
the route needs a safer return model, such as precomputed/cache-backed state,
async API adaptation, or a split/aggregate path that never waits from an owner
thread.

## 2026-05-29 19:35 Void Region Routes Are Not Sync Returns

The `[FBB model]` output made one bad route decision visible: void
region-owned calls such as `World#playSound(Location,...)`, `World#loadChunk`,
and `Block#setType(Material)` were being grouped with return-value calls and
logged as `blocked-sync-return-avoided`.

That was too conservative. These calls do not need to return a legacy value to
the plugin, so the bridge now routes them through a fire-and-forget owner-region
path on Folia:

```text
fallback=preemptive-region-scheduler policy=fire-and-forget-void reason=void-route-no-sync-return
```

This still preserves evidence. If the scheduled call fails, the task reports
`[FBB task-failure]` and `[FBB unsafe-failure]` with the same route family and
source API. Return-value calls such as `World#createExplosion(...)`,
`World#refreshChunk(...)`, and `World#getChunkAt(...)` remain behind the
sync-return boundary until they have a safe return model.

## 2026-05-29 19:51 Return-Value Region Models

The next pass promotes two narrow return models instead of removing the
sync-return guard globally:

- `World#getChunkAt(...)` now tries `fallback=loaded-chunk-index-return
  policy=sync-return-model` when a Folia owner thread cannot safely wait for a
  region scheduler result. If the requested chunk is already loaded, the bridge
  returns that real `Chunk`; if it is not in the loaded index, the original
  Bukkit/Folia failure is preserved.
- `World#refreshChunk(...)` and `World#createExplosion(...)` now use
  `policy=deferred-accepted-boolean return=scheduled-true` when they must hop to
  another owner from a Folia owner thread. This means "the bridge accepted and
  scheduled the region-owned operation," not "the later Bukkit event result is
  already known." Scheduled failures still report `[FBB task-failure]` and
  `[FBB unsafe-failure]`.

This keeps the route universal by bytecode/API shape while documenting the
semantic compromise for old synchronous boolean calls.

## 2026-05-31 01:20 Deferred Chunk Return Model

The live log still showed `World#getChunkAt(...)` misses on Folia owner threads:
the bridge correctly avoided a scheduler wait, but then preserved the original
unsafe call and produced `[FBB unsafe-failure]`.

The `B_REGION_LOCATION` chunk return model now has a second stage:

- loaded index hit -> return the real `Chunk`
- loaded index miss -> return a deferred `Chunk` proxy and start async preload
  through Paper/Folia `World#getChunkAtAsync(x,z,true)`
- proxy coordinate/identity reads (`getWorld`, `getX`, `getZ`, `isLoaded`) are
  answered immediately
- proxy data reads before preload complete log `pending-result-default` and
  return conservative empty/default values
- after preload, proxy calls delegate to the real chunk, with `Chunk#getEntities`
  still routed through `G_WORLD_SCAN_SPLIT`

Expected evidence:

```text
[FBB unsafe-call] api=World#getChunkAt(int,int) route=B_REGION_LOCATION ... fallback=loaded-chunk-index-return policy=sync-return-model result=miss action=deferred-chunk-model
[FBB unsafe-call] api=World#getChunkAt(int,int) route=B_REGION_LOCATION ... policy=deferred-chunk-model action=async-preload-return-proxy result=proxy
[FBB unsafe-call] api=World#getChunkAt(int,int) route=B_REGION_LOCATION ... policy=deferred-chunk-model action=async-preload-complete result=chunk
```

If plugin code uses the proxy too early, keep those diagnostics. They identify
which `Chunk` method needs a stronger synchronous model instead of hiding the
fact that the chunk was not available yet.

## 2026-05-31 01:34 Entity Nearby Split And Method-Reference Guard

The next live first-join log had no `[FBB unsafe-failure]` or
`[FBB task-failure]` lines, but it exposed two useful probe failures:

- `Player#getNearbyEntities(DDD)` still threw from global/foreign-region
  contexts because the bridge fell back to the entity scheduler and then refused
  to block a Folia owner thread.
- `HumanEntity#closeInventory` in the probe used a method reference whose
  captured receiver descriptor did not match the bridge handle selected by the
  raw invokedynamic rewrite, producing `BootstrapMethodError`.

Fixes:

- `Entity#getNearbyEntities(...)` now uses `fallback=entity-location-bounded-split`
  from Folia owner/global/foreign-region threads. This reuses the proven bounded
  split-scan model and removes the owner entity from the returned list.
- Raw invokedynamic rewrites now check captured receiver descriptors. Mismatched
  method references are logged as `action=trace-only` instead of being rewritten
  into a JVM lambda-bootstrap failure.
- The probe close-inventory calls were changed from method references to normal
  lambdas so the probe still exercises the ordinary HumanEntity/Player invoke
  bytecode paths.

Expected evidence:

```text
[FBB unsafe-call] api=Entity#getNearbyEntities route=G_WORLD_SCAN_SPLIT ... fallback=entity-location-bounded-split
[FBB guard-path] ... action=trace-only reason=trace-only: invokedynamic receiver mismatch; ordinary invoke rewrite required
```
