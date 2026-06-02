# FBB Probe Plugin

`FBBProbe` is a controlled live-server test target for the bridge. It is not a
compatibility layer and it is not required in production. Its job is to trigger
known Bukkit API shapes on Folia so the bridge can prove whether each path is
rewritten, traced, missed, safe only in a specific context, or still escaping
into a Folia thread exception.

The target probe intentionally lives outside the bridge's internal Java package.
The agent skips `dev.foliabytecodebridge.*` so it does not rewrite its own
runtime classes; placing `FBBProbe` in `dev.fbbprobe.*` makes it behave
like a normal third-party plugin during bytecode transformation. The separate
`FBBProbeControl` jar uses the ignored package on purpose so the same direct
call shapes can show raw Folia failures.

Control failures are evidence, not a quiet pass. `FBBProbeControl` logs
`bridgeRole=control-untransformed controlExpected=true` when it hits raw Folia
guards, and the offline evidence tool prints those lines as
`[FBB control-evidence]` instead of mixing them with real plugin failures. This
keeps the baseline visible while making actual bridge misses easier to spot.

## Why It Exists

The home plugin reference `/home` failure taught us that a method can look covered in
documentation while the raw bytecode path is only `trace-only`. The probe plugin
turns that lesson into repeatable evidence:

```text
[FBB probe] root=/fbbprobe bridgeRole=target-transformed mode=safe context=current intent=rewrite-candidate route=A_ENTITY api=Player#teleport(Location) action=invoke
[FBB teleport-path] ... route=A_ENTITY rule=bukkit-api-owner action=rewritten ...
[FBB unsafe-call] api=Player#teleport(Location) route=A_ENTITY ...
```

If the bridge misses a method, the probe keeps the failure visible:

```text
[FBB probe] root=/fbbprobe bridgeRole=<role> mode=<mode> context=<context> intent=<intent> route=<family> api=<method> owner=<expected bytecode owner> name=<method name> descriptor=<JVM descriptor> result=failed throwable=<exception> classification=<classification> thread=<thread> pluginFrame=<frame> guardFrame=<frame> schedulerFrame=<frame>
[FBB unsafe-failure] ...
```

The `owner`, `name`, and `descriptor` fields are intentionally repeated on
`action=invoke`, `result=completed`, and `result=failed` lines. That way a live
failure still tells the next bridge pass which bytecode family was meant to be
exercised, even if the bridge did not rewrite or trace it yet.

Failure fingerprints are diagnostic, not suppression. `classification=` names
the first useful bucket such as `unsupported-operation`, `thread-guard`, or
`region-guard`. `pluginFrame=` points back into the probe or target plugin,
`guardFrame=` points at the Folia/CraftBukkit guard that rejected the call, and
`schedulerFrame=` points at the scheduler/region machinery when one is present.
For `UnsupportedOperationException`, the probe also appends
`action=trace-only reason=unsupported-operation-route-not-proven` so the line is
clearly evidence for a future bytecode rule, not a completed route.

## Command

`FBBProbe` and `FBBProbeControl` each register one Bukkit command:

```text
/fbbprobe <mode>
/fbbprobecontrol <mode>
```

Run `/fbbprobe help` or `/fbbprobecontrol help` to print the available modes and
contexts in game. Each probe log includes
`root=<command> bridgeRole=<role> mode=<mode> context=<context> intent=<intent>`
so terminal evidence points back to the exact bucket and reason that produced
it.

`result=completed` means the call worked from the probe's current execution
context. It does not prove the API is universally thread-safe from async threads
or unrelated regions. Use `intent=` to read the line correctly:

| Intent | Meaning |
| --- | --- |
| `context-read` | A read-style call that completed from the logged context. |
| `context-mutation` | A mutation that completed from the logged context; still needs route review before promotion. |
| `scan-context-check` | A scan-style call that may need split-by-region handling if used broadly. |
| `rewrite-candidate` | A known unsafe Bukkit shape that should be owned by the bridge when the bytecode route is proven. |
| `guard-probe` | A Paper/Folia guard-backed path; failures are expected evidence until a safe route is designed. |
| `global-thread-check` | A global server-state path where a global scheduler may or may not preserve semantics. |

`UnsupportedOperationException` deserves extra review before it is marked
untranslatable. It can mean "Folia has no supported route", but it can also mean
the call belongs to a route family we have not modeled yet. Treat the
`owner/name/descriptor`, `guard=`, `guardFrame=`, and `pluginFrame=` fields as
the evidence bundle for the next design pass.

Context commands:

```text
/fbbprobe context current safe
/fbbprobe context entity safe
/fbbprobe context async safe
/fbbprobe context global safe
/fbbprobe context region safe
/fbbprobe context foreign-region safe
/fbbprobe matrix safe

/fbbprobecontrol context async safe
/fbbprobecontrol matrix safe
```

`matrix` runs a mode across `current`, `entity`, `async`, `global`, `region`,
and `foreign-region`. Use the target command to see what the bridge rewrites;
use the control command to see the raw Folia behavior for the same bytecode
family.

| Mode | Purpose |
| --- | --- |
| `startup` | Comprehensive no-player probes for boot-time evidence: world/chunk/server/unowned-scoreboard plus proven recovery candidates. Auto-runs after startup in global, async, and region contexts. |
| `safe` | Low-impact reads plus direct teleport back to the player's current location. |
| `scan` | World/chunk/entity scan shapes seen in map and edit plugins. |
| `ui` | Opens and closes a small inventory. |
| `visibility` | Hide/show player calls. |
| `entity` | Player/entity mutations such as velocity, game mode, potion effects, and audio. |
| `world` | Low-impact region/world effects such as same-material block set, lightning, zero-power explosions, and world sound calls. |
| `chunk` | Paper guard probes for chunk load/unload paths from `docs/PAPER_GUARD_AUDIT.md`. |
| `server` | Paper guard probes for server dispatch/update paths from `docs/PAPER_GUARD_AUDIT.md`. |
| `scoreboard` | Paper guard probes for scoreboard paths from `docs/PAPER_GUARD_AUDIT.md`. |
| `paper` | Expands to `chunk,server,scoreboard`. |
| `all` | Expands to every non-destructive bucket: `safe,scan,ui,visibility,entity,world,paper`. |
| `destructive` | Adds world artifacts such as item drops, entity spawns, and tree generation; use only on throwaway worlds. |

## Startup Auto Probe

For test servers, `FBBProbe` also runs a boot-time evidence pass without waiting
for a player. The default startup pass is:

```text
mode=startup contexts=global,async,region
```

This intentionally covers every current probe that can be called without a
player: global/server model candidates such as `Bukkit#getOnlinePlayers`,
`Server#getWorlds`, player/profile lookups, plugin/command/ban/item registries,
`PluginManager#callEvent`, `Bukkit#dispatchCommand`, unowned scoreboard manager
paths, detached scoreboard model paths, objective/score display-state mutations,
team display/style/friendly-flag mutations, main-scoreboard lookup, world/chunk
reads, typed world scans, chunk scans, chunk load/refresh probes, sound
overloads, zero-power explosions, and synthetic shared-event-path evidence.
Player/entity methods still need first-join or manual commands because bytecode
needs an actual entity owner. The startup bucket is the place to add future
no-player evidence instead of adding more commands.

Unproven global/server candidates use `[FBB probe-model]` instead of normal
rewrite probes:

```text
[FBB probe-model] group=global-model route=S_GLOBAL api=Bukkit#getOnlinePlayers status=probe-only rewrite=false ownerHint=server-global return=Collection<Player> syncReturnRisk=true result=completed size=<n>
[FBB probe-model] group=event-model route=S_GLOBAL api=PluginManager#callEvent(Event) status=probe-only rewrite=false ownerHint=server-global-event-dispatch return=void syncReturnRisk=false result=completed event=dev.fbbprobe.harness.GlobalModelProbeEvent ...
[FBB synthetic-event-dispatch] action=<synthetic-start|synthetic-finish> event=... listeners=<n> note=custom-sync-event-compatibility-model
[FBB probe-model] group=synthetic-event-path route=S_GLOBAL api=SyntheticEventPathBridge#call(...) status=probe-only rewrite=false ownerHint=shared-synchronous-event-chain ...
[FBB compatibility-lane] action=<submit|start|finish> sequence=<n> source=synthetic-event-path:... note=serialized-compatibility-model-not-a-folia-owner-thread
[FBB synthetic-event-probe] phase=MONITOR ... model=synthetic-event-path shared=true action=read-final-shared-event-state cancelled=true effectsList=...
```

The `PluginManager#callEvent(Event)` shape is now an experimental rewrite for
custom sync plugin events. Built-in Bukkit/Paper events still pass through to
the original plugin manager. Other global/server candidates remain
classification evidence only; they do not mean the bridge has promoted a
rewrite for sync-return APIs such as `getOnlinePlayers`, `getWorlds`, or
`getPlayer`.

Startup probing can be changed with JVM properties:

```text
-Dfbbprobe.startupModes=off
-Dfbbprobe.startupModes=startup
-Dfbbprobe.startupContexts=global,async,region
-Dfbbprobe.startupDelayTicks=60
```

Console can manually rerun the boot-safe set:

```text
/fbbprobe startup
/fbbprobecontrol startup
```

## Building Probe Jars

Build the bridge jar first, then build the optional probe jars with the JDK jar
tool:

```powershell
powershell -ExecutionPolicy Bypass -File tools\build-probes.ps1 -ServerRoot "<throwaway Folia server root>"
```

Do not package probe jars with generic zip tooling such as PowerShell
`Compress-Archive`. A live smoke run showed those archives can list the expected
class entries but still fail Folia's plugin classloader with `Cannot find main
class`. The helper compiles against the server libraries and packages
`FBBProbe.jar` / `FBBProbeControl.jar` with `jar.exe`, then the main classes can
be verified with `javap` if a load issue appears again.

The control jar runs the same calls from an ignored package so raw Folia
failures can be compared against the transformed probe. In evidence summaries,
these expected raw failures appear under `[FBB control-summary]` and
`[FBB control-evidence]`; remaining `[FBB log-evidence]` lines are the ones to
triage first.

## Synthetic Event Path Probe

Startup probes include a harmless modeled cancellable event path. The target
probe enters `SyntheticEventPathBridge`, runs through the single-thread
compatibility lane, records ordered listener-like observations, cancellation
mutation, final state, and the shared effects list under
`[FBB synthetic-event-probe]`.

The control probe keeps raw `PluginManager#callEvent(Event)` behavior so the
logs still show what Folia accepts or rejects without the modeled lane.

The target startup probe also sends two negative wrapper cases through
`SyntheticEventDispatchBridge`:

- a no-owner custom event, expected to emit `[FBB synthetic-owner-miss]` with
  `no-compatible-owner-getter`;
- a two-thread no-owner overlap experiment marked
  `FBB_REMOVE_ME_UNKNOWN_OVERLAP_PROBE_V1`, expected to keep unknown listener
  execution serialized with `maxActiveListeners=1` even though two dispatch
  threads start together. This is probe-only and intentionally labeled
  `removable=true` so it can be deleted when the synthetic event model is
  stable.
- a two-thread unknown internal-state/cache experiment marked
  `FBB_REMOVE_ME_INTERNAL_STATE_PROBE_V1`, expected to keep arbitrary
  listener-chain internals serialized with `maxActiveInternalPaths=1` while
  both dispatch threads start together. This models ordinary plugin logic such
  as collections, caches, and cooldown maps without touching real Bukkit state.
- a multi-region block collection event, expected to emit
  `[FBB synthetic-multi-region] phase=detect`, `multi-region-collection`, and
  remain in the serialized compatibility lane.
- a read-only multi-region block collection event, expected to emit
  `[FBB synthetic-multi-region] phase=split-read`, `operation=read-only`, and
  `result=aggregated` after one owner read pass per anchor.
- an explicit mutation multi-region block collection event, expected to emit
  `[FBB synthetic-multi-region] phase=plan-mutation`,
  `result=planned-not-executed`, and
  `phases=prepare,owner-apply,aggregate-verify`. This is plan evidence only;
  it does not run a multi-region write.
- an explicit mutation multi-region block collection event with all Phase 4
  contract markers, expected to emit
  `[FBB synthetic-multi-region] phase=contract-mutation`,
  `result=ready-not-executed`, and
  `contract=prepare,owner-apply,aggregate-verify`. This is readiness evidence
  only unless the guarded Phase 5B executor is enabled.
- the same contract-ready event also reaches
  `[FBB synthetic-multi-region] phase=execute-mutation`. With the default
  `syntheticMutationExecutor=false`, the expected result is
  `result=blocked reason=executor-disabled action=stay-serialized`. When
  `syntheticMutationExecutor=true` is set on a throwaway server, the expected
  live path is `result=scheduled`, followed by `result=completed
  reason=verified` or a preserved failure. This is an exact hook-contract test,
  not a broad multi-region write unlock.
- negative exact-contract mutation probes for prepare and verify failure. With
  the executor enabled, these should emit `reason=prepare-returned-false` or
  `reason=verify-returned-false`; with the executor disabled, they remain
  blocked at `reason=executor-disabled`. These probes prove the executor fails
  loudly instead of silently treating a bad contract as safe.

The synthetic probe lines now include stable `marker=` identifiers for the
important outcomes. Grep these before reading the full surrounding stack:

```text
FBB_SYNTHETIC_OWNER_MISS_SERIALIZED_V1
FBB_SYNTHETIC_MULTI_REGION_DETECTED_V1
FBB_SYNTHETIC_READ_SPLIT_V1
FBB_SYNTHETIC_MUTATION_PLAN_V1
FBB_SYNTHETIC_MUTATION_CONTRACT_V1
FBB_SYNTHETIC_MUTATION_EXECUTOR_DISABLED_V1
FBB_SYNTHETIC_MUTATION_PREPARE_BLOCKED_V1
FBB_SYNTHETIC_MUTATION_OWNER_APPLY_SCHEDULED_V1
FBB_SYNTHETIC_MUTATION_COMPLETED_VERIFIED_V1
FBB_SYNTHETIC_MUTATION_VERIFY_BLOCKED_V1
```

If the same synthetic event/listener path is observed while an earlier
invocation is still active on another thread, the bridge emits
`[FBB synthetic-concurrency] phase=5A`. That is re-entry/concurrency evidence
for unknown listener internals; it does not promote a route and does not silence
the listener failure. The target probe includes
`synthetic-listener-concurrency`, which calls the bridge's diagnostic-only
`SyntheticEventPathBridge#probeListenerReentry(...)` hook and should log
`activePath=diagnostic-probe:startup-probe-phase-5a` plus
`currentPath=diagnostic-probe:startup-probe-phase-5a`.

This is compatibility-model evidence only. It does not rewrite Bukkit event
dispatch yet, and it does not claim that shared event paths are safe to split.
It exists so future synthetic event-path work has a stable probe and clear log
shape before any broad event transformer is attempted.

## First-Join Auto Probe

For test servers, `FBBProbe` automatically runs all non-destructive probe groups
the first time each player joins, across the player contexts that usually expose
thread/region mistakes:

```text
modes=safe,scan,ui,visibility,entity,world,paper
contexts=current,entity,async,global,region,foreign-region
```

`paper` expands internally to `chunk,server,scoreboard`. This gives a noisy
one-join evidence matrix without manually typing every command or repeatedly
leaving/rejoining. First join can be changed or disabled with JVM properties:

```text
-Dfbbprobe.firstJoinModes=off
-Dfbbprobe.firstJoinModes=safe
-Dfbbprobe.firstJoinModes=core
-Dfbbprobe.firstJoinModes=full
-Dfbbprobe.firstJoinModes=safe,scan,entity,world
-Dfbbprobe.firstJoinContexts=current
-Dfbbprobe.firstJoinContexts=current,async,global
-Dfbbprobe.firstJoinContexts=all
```

`core` expands to `safe,scan,ui,visibility,entity,world`. `full` is the default
full non-destructive set. `destructive` is still never run from first join
unless explicitly enabled:

```text
-Dfbbprobe.firstJoinModes=destructive -Dfbbprobe.firstJoinDestructive=true
```

## Route Coverage

| Route | Probe examples |
| --- | --- |
| `A_ENTITY` | `Player#getLocation`, `Player#getWorld`, `Player#teleport(Location)`, `Player#teleport(Location,TeleportCause)`, `Player#setVelocity`, `Player#setGameMode`, `Entity#setVelocity`, `HumanEntity#setGameMode`, `LivingEntity#addPotionEffect`, `LivingEntity#removePotionEffect`, `Player#playSound` |
| `B_REGION_LOCATION` | `World#getBlockAt(Location)`, `World#getChunkAt(int,int)`, `World#getChunkAt(Location)`, `World#strikeLightning`, `World#strikeLightningEffect`, `World#createExplosion`, `World#dropItem`, `World#spawnEntity`, `World#generateTree` |
| `C_REGION_BLOCK` | `World#getBlockAt(int,int,int)`, `World#getChunkAt(Block)`, `Block#getLocation`, `Block#getType`, `Block#getBlockData`, `Block#setType(Material)` |
| `D_PLAYER_UI` | Startup: scoreboard manager/main-scoreboard factory paths. Player-owned: `Player#openInventory`, `Player#closeInventory`, `HumanEntity#openInventory`, `HumanEntity#closeInventory`, player scoreboard calls. |
| `F_PLAYER_VISIBILITY` | `Player#hidePlayer`, `Player#showPlayer` |
| `G_WORLD_SCAN_SPLIT` | `Player#getNearbyEntities`, `World#getEntities`, `World#getLoadedChunks`, `World#getNearbyEntities`, `World#getEntitiesByClass`, `World#getEntitiesByClasses`, `Chunk#getEntities` |

The target probe keeps setup calls inside the probe wrapper when the setup is
itself a Folia-sensitive Bukkit call. For example, the `Chunk#getEntities` scan
probe resolves the target chunk with `World#getChunkAt(Location)` inside the
same probe body. That way async/global failures point to the intended
`B_REGION_LOCATION -> G_WORLD_SCAN_SPLIT` route evidence instead of escaping as
an unclassified setup exception. The control jar may still show the raw Folia
failure for comparison.

The `recovery` bucket is the planning bucket for routes that already produced a
successful fallback in live logs and are candidates for preemptive translation.
Those no-player recovery candidates are now also part of the startup pass so a
restart produces evidence without joining the server. Player-owned recovery
coverage still needs a real player receiver.

The first-join player matrix intentionally includes inherited bytecode owner
shapes. Many plugins compile calls through `HumanEntity`, `LivingEntity`, or
`Entity` even when the runtime receiver is a player. Keep those probes in the
same route families as their player-owned equivalents so a missed rewrite points
at the exact owner/name/descriptor, not just the high-level Bukkit method.

`/fbbprobe scoreboard` is the focused `D_PLAYER_UI` model bucket. It now checks
the unowned manager factory, the main scoreboard, player-owned scoreboard
assignment, scoreboard read surfaces, team creation/mutation, and objective/score
model paths. Objective creation is now in the direct-bytecode probe so startup
and first-join logs show `Scoreboard#registerNewObjective`, `Objective#getScore`,
and `Score#setScore` under one route family.

Unsafe Bukkit calls in the target probe are intentionally written as explicit
lambdas. Java method references can compile to `invokedynamic` bootstrap handles
instead of direct invoke instructions, which is useful evidence for real
plugins but not the bytecode path this probe is meant to exercise. The bridge
rewrites known unsafe method-reference handles and logs them as
`[FBB guard-path] action=rewritten`.

## Paper Guard Probes

Guard probes include a `guard=` field that names the Paper/Folia guard family
that motivated the test:

```text
[FBB probe] root=/fbbprobe bridgeRole=target-transformed mode=chunk context=current intent=guard-probe route=B_REGION_LOCATION api=World#loadChunk(int,int) ... guard=CraftWorld#chunk-load-unload action=invoke
[FBB guard-path] ... route=B_REGION_LOCATION guard=CraftWorld#chunk-load-unload action=trace-only
[FBB probe] root=/fbbprobe bridgeRole=target-transformed mode=chunk context=current intent=guard-probe route=B_REGION_LOCATION api=World#refreshChunk(int,int) ... guard=CraftWorld#chunk-load-unload action=invoke
[FBB unsafe-call] api=World#refreshChunk(int,int) route=B_REGION_LOCATION family=region next=region-scheduler-by-chunk ... fallback=scheduler-after-thread-guard
```

The `server` bucket now has one promoted guard path:
`Bukkit/Server#dispatchCommand(CommandSender,String)`. In the target probe, it
should log `[FBB guard-path] action=rewritten` plus an `[FBB unsafe-call]
route=S_GLOBAL next=global-or-entity-command-dispatch`. In the control probe, it
should still show the raw Folia failure when run from the wrong context.

Use these as pointers for the next bytecode rule. They are not proof that a
rewrite is safe; they prove the owner/name/descriptor and route family that need
the next decision.

## Reading Results

The probe is deliberately small and loud. Do not quiet errors just to make the
probe pass. For each failure, decide whether the matching method has enough
runtime context and return-shape compatibility to become a real Folia-safe
rewrite. If not, keep it as diagnostic evidence until a safer route is clear.
