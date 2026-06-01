# Paper Guard Audit

This document turns the `Paper-main.zip` source reference into an evidence map.
It is not a patch list. A Paper/Folia guard means the server has a check that
protects shared state from the wrong thread. The bridge uses those guards to
find bytecode families worth probing, then only promotes a path to a rewrite
after the owner/name/descriptor and Folia route are clear.

## Guard Matrix

| Paper guard source | Bukkit API surface | Route | Current bridge status | Next evidence |
| --- | --- | --- | --- | --- |
| `CraftScheduler#handle` | `BukkitScheduler#runTask*`, `scheduleSync*`, `BukkitRunnable#runTask*` | `S_GLOBAL`, `S_ASYNC` | Rewritten through `ObjectSchedulerBridge` | Keep smoke coverage for anonymous `BukkitRunnable` owner shapes |
| `CraftEntity/CraftPlayer#teleport0` | `Entity#teleport`, `Player#teleport` | `A_ENTITY` | Rewritten to `UnsafeCallBridge` and `teleportAsync` shim | Watch async completion failures |
| `CraftEntity#getNearbyEntities` | `Entity#getNearbyEntities` | `G_WORLD_SCAN_SPLIT` | Diagnostic wrapper/probe | Decide whether entity scheduler scan is enough or needs split logic |
| `CraftWorld#getNearbyEntities` | `World#getNearbyEntities`, entity class scans | `G_WORLD_SCAN_SPLIT` | Preemptive bounded/split wrapper | Bounded scans now split across loaded candidate chunks and filter to the original AABB; failures stay loud per chunk/route |
| `CraftWorld#chunk load/unload` | `World#loadChunk`, `World#refreshChunk` | `B_REGION_LOCATION` | `loadChunk` and `refreshChunk` promoted to scheduler retry | `unloadChunk` and `regenerateChunk` are intentionally not invoked by default because they mutate world/chunk lifecycle state. |
| `CraftWorld#world save` | `World#save` | `S_GLOBAL` | Guard trace only | Keep trace-only unless a safe server-global policy is proven |
| `CraftWorld#sound` | `World#playSound`, `World#stopSound` | `B_REGION_LOCATION` | Guard trace/probe only | Route by sound location or emitter when possible |
| `CraftServer#dispatchCommand` | `Bukkit#dispatchCommand`, `Server#dispatchCommand` | `S_GLOBAL` | Rewritten through `UnsafeCallBridge` command dispatch shim | Returns `scheduled-true` on Folia; task failures remain logged |
| `CraftServer#sound` | `Server#playSound`, `Server#stopSound` | `S_GLOBAL` | Guard trace/probe only | Server-wide sound has no single obvious region owner |
| `CraftHumanEntity#open-container` | `HumanEntity#openWorkbench`, `openEnchanting`, similar UI opens | `D_PLAYER_UI` | Guard trace only | Add probe only when the UI side effect is acceptable |
| `CraftLivingEntity#effect add` | `LivingEntity#addPotionEffect`, `Player#addPotionEffect`, matching remove-effect descriptors | `A_ENTITY` | Preemptive entity-scheduler route | Live target-probe evidence showed async/global failures. The bridge now checks receiver ownership and schedules on the entity scheduler before calling Bukkit when unowned. |
| `CraftPlayer#kick` | `Player#kickPlayer`, `Player#kick` | `A_ENTITY` | Guard trace only | Probe manually; do not first-join kick players |
| `CraftPlayer#sent-chunks` | `Player#getSentChunkKeys`, `getSentChunks`, `isChunkSent` | `A_ENTITY` | Guard trace only | Add a probe once API descriptors are verified on the target Folia build |
| `CraftScoreboardManager` | `Bukkit#getScoreboardManager`, `ScoreboardManager#getNewScoreboard` | `D_PLAYER_UI` | Detached shim model for new-scoreboard shape | Unowned scoreboard creation is modeled, not scheduler-hopped; player-visible application remains deferred |
| `CraftScoreboard/Team model` | `Player#getScoreboard`, `Player#setScoreboard`, `Scoreboard#getTeam`, `Scoreboard#registerNewTeam`, `Team#setOption/addEntry/removeEntry` | `D_PLAYER_UI` | Diagnostic wrapper/probe | Compare player-owned Bukkit model calls against packet-style plugins before promoting |

## Trace Policy

`RawGuardTraceTransformer` emits `[FBB guard-path]` lines for guard-backed
families that are not ready for rewrite. These lines include:

```text
class=
jar=
owner=
name=
descriptor=
route=
guard=
action=trace-only
reason=paper-guard-audit
```

That trace is intentionally separate from `[FBB unsafe-call]`. A guard path says
"this bytecode shape is worth investigating." An unsafe call says "the bridge
actually owns this call now."

`Bukkit/Server#dispatchCommand(CommandSender,String)` is the first promoted
server guard. Its `[FBB guard-path]` line uses `action=rewritten`, and the later
runtime line uses `[FBB unsafe-call] route=S_GLOBAL next=global-or-entity-command-dispatch`.
If the sender is an entity, the bridge submits through the entity scheduler; all
other senders use the global region scheduler. The old boolean return cannot be
known without blocking, so Folia returns `true` after scheduling and logs
`return=scheduled-true`.

## Promotion Rule

Before a guard path becomes a rewrite, record:

```text
1. The exact JVM owner/name/descriptor.
2. The route family.
3. The Folia API or scheduler that preserves the old return shape.
4. Whether the call has enough context: entity, player, block, location, chunk,
   or only global server state.
5. A smoke/probe line proving the path.
```

If any item is unclear, keep the path as `trace-only`.
