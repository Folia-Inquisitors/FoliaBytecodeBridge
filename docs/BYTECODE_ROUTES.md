# Bytecode Routes

The bridge matches JVM bytecode call shapes, not source-code text. If two plugins compile to the same owner/name/descriptor, they enter the same bridge route even if their Java source looks different.

Example:

```text
owner=org/bukkit/World
name=dropItemNaturally
descriptor=(Lorg/bukkit/Location;Lorg/bukkit/inventory/ItemStack;)Lorg/bukkit/entity/Item;
route=B_REGION_LOCATION
```

Any plugin with that exact bytecode shape is handled by the same diagnostic wrapper and produces the same route hint.

## Official Route-Family Map

`RouteFamily` is the central registry for these labels. Logs, smoke tests, and
documentation should use these names exactly; new bytecode shapes should be
added under one of these families before a new family is invented.

| Route | Bytecode family | Current behavior | Next stronger translation |
| --- | --- | --- | --- |
| `S_GLOBAL` | Legacy sync `BukkitScheduler` / `BukkitRunnable` calls and server-global guard paths | Translates schedulers to Folia global scheduler; routes command dispatch by sender | Only safe for global work; player/entity/block work still needs a narrower route |
| `S_ASYNC` | Legacy async scheduler calls | Translates to Folia async scheduler | Keep only for async-safe work |
| `A_ENTITY` | Entity/player reads and mutations | Teleport sync calls shim to `teleportAsync`; other calls are diagnostic wrappers | Route remaining entity/player mutations through the entity/player scheduler when the receiver entity is known |
| `B_REGION_LOCATION` | World calls with a `Location` target, or world/chunk coordinates where no `Location` object exists | Diagnostic wrapper only | Route through region scheduler using `location.getWorld()` and chunk coordinates |
| `C_REGION_BLOCK` | Block-owned calls | Diagnostic wrapper only | Route through region scheduler using the block location |
| `D_PLAYER_UI` | Inventory open/close, player scoreboard access, and player UI/model mutation | Inventory paths use owner diagnostics; bridge-owned scoreboard paths use a detached/player scoreboard model | Route through player/entity scheduler or a proven packet/model shim |
| `F_PLAYER_VISIBILITY` | `Player#hidePlayer/showPlayer` | Diagnostic wrapper only | Route through player/entity scheduler |
| `G_WORLD_SCAN_SPLIT` | Whole-world, nearby-world, or nearby-entity scans | Bounded location scans retry on the candidate region; whole-world entity scans use the experimental loaded-chunk split | Prefer a source-level API when available; otherwise split by chunk/region and keep failures loud |

Scheduler book-keeping calls such as `cancelTask`, `cancelTasks`, and
`BukkitRunnable#isCancelled` stay in `S_GLOBAL` because they are part of the
legacy scheduler fallback path. Player audio currently maps to `A_ENTITY`
because it is an entity/player-owned operation. Chunk-coordinate world calls
currently map to `B_REGION_LOCATION` because they still need a region scheduler
route derived from world and chunk context.

## Folia Reference

The `Folia-ver-1.20.zip` reference confirms the same scheduler split used by newer Folia builds:

- region work is scheduled by world + chunk coordinates through `RegionScheduler` / `FoliaRegionScheduler`
- entity/player work belongs on the entity scheduler
- async work belongs on the async scheduler
- legacy no-context sync work can only be approximated by the global region scheduler

That is why the bridge currently auto-translates scheduler calls, but only logs direct player/entity/world/block calls. Those direct calls need runtime context before a safe route can be selected.

## Same Bytecode, Same Support

This is the important reuse rule:

| If another plugin calls... | It is covered when bytecode resolves to... |
| --- | --- |
| `new BukkitRunnable(){...}.runTaskLater(plugin, 1)` | an inherited `runTaskLater(Plugin,long)` call compatible with `BukkitRunnable` |
| `new BukkitRunnable(){...}.runTaskAsynchronously(plugin)` where bytecode owner is the anonymous subclass | an inherited `runTaskAsynchronously(Plugin)` call compatible with `BukkitRunnable`; observed in `PlayerKits2` `PlayersConfigManager#loadConfig` |
| `scheduler.runTaskAsynchronously(plugin, task)` | `org/bukkit/scheduler/BukkitScheduler#runTaskAsynchronously(Plugin,Runnable)` |
| `player.openInventory(inv)` | `org/bukkit/entity/Player#openInventory(Inventory)` or inherited `HumanEntity#openInventory(Inventory)` |
| `player.getScoreboard()` / `player.setScoreboard(board)` | `org/bukkit/entity/Player#getScoreboard()` / `setScoreboard(Scoreboard)`; classified as player-owned `D_PLAYER_UI` |
| `board.getTeam(name)` / `board.registerNewTeam(name)` / `team.setOption(...)` / team display, prefix, suffix, color, and friendly flags | `org/bukkit/scoreboard/...` team model calls; classified as `D_PLAYER_UI` and modeled when the board is bridge-owned |
| `board.registerNewObjective(...)` / `objective.getScore(entry)` / objective display/number-format methods / score custom-name/number-format methods | `org/bukkit/scoreboard/...` objective and score model calls; classified as `D_PLAYER_UI` and modeled by exact owner/name/descriptor |
| `PaperLib.teleportAsync(player, location, cause)` or a shaded helper with the same static descriptor | any owner named `teleportAsync` with `(Entity|Player,Location,TeleportCause) -> CompletableFuture` and routes through `A_ENTITY` |
| `helper.teleportAsync(player, location)` or a shaded scheduler-helper call | any owner named `teleportAsync` with `(Entity|Player,Location,...)` and routes through `A_ENTITY` as trace-only evidence |
| `player.teleport(location)` or `player.teleport(location, cause)` | `org/bukkit/entity/Entity|Player#teleport(Location...)` and routes through `A_ENTITY` with a Folia `teleportAsync` shim |
| `Bukkit.dispatchCommand(sender, command)` or `server.dispatchCommand(sender, command)` | exact `org/bukkit/Bukkit` or `org/bukkit/Server` owner with `(CommandSender,String)Z`, routed through `S_GLOBAL` command dispatch shim |
| `io.papermc.paper.util.MCUtil.MAIN_EXECUTOR.execute(task)` | exact Paper `MCUtil.MAIN_EXECUTOR` field followed by `java/util/concurrent/Executor#execute(Runnable)V`, routed through `S_GLOBAL` global scheduler bridge |
| `player.getWorld().dropItemNaturally(player.getLocation(), item)` | `Player#getWorld`, `Player#getLocation`, and `World#dropItemNaturally(Location,ItemStack)` |
| `world.strikeLightning(location)` | `org/bukkit/World#strikeLightning(Location)` |

If a plugin uses reflection, direct NMS, shaded server APIs, or a different method descriptor, it will not be covered until that exact bytecode shape is added.

## A_ENTITY Teleport Family

Teleport routing is not command-specific and not plugin-name-specific.

The generic static helper rule exists because shaded PaperLib-style utilities
commonly compile to the same shape while changing package names:

```text
name=teleportAsync
descriptor=(Lorg/bukkit/entity/Entity;Lorg/bukkit/Location;Lorg/bukkit/event/player/PlayerTeleportEvent$TeleportCause;)Ljava/util/concurrent/CompletableFuture;
```

or the same descriptor with `Player` as the first argument. These are rewritten
to `UnsafeCallBridge#staticTeleportAsync`, which logs the original owner/name/
descriptor and delegates to `Entity#teleportAsync`.

If a helper has a related but unsupported shape, the bridge emits:

```text
[FBB teleport-path] ... route=A_ENTITY outcome=missed-unsupported-static-shape bridge=none
```

That is the evidence to decide whether a new generic descriptor is safe. Exact
owner rules should only be added when a descriptor cannot be generalized without
changing behavior.

Teleport evidence separates the matching reason from the action:

```text
rule=generic-shape action=rewritten
rule=bukkit-api-owner action=rewritten
rule=generic-helper-shape action=trace-only
rule=generic-shape-probe action=missed
```

Direct sync Bukkit teleport descriptors are now rewritten by the raw transformer:

```text
org/bukkit/entity/Entity#teleport(Location)Z
org/bukkit/entity/Entity#teleport(Location,TeleportCause)Z
org/bukkit/entity/Player#teleport(Location)Z
org/bukkit/entity/Player#teleport(Location,TeleportCause)Z
```

This covers home/warp bytecode that builds a stored `Location`, applies block
centering math such as `Location#add(0.5, 0.0, 0.5)`, then calls
`Player#teleport(Location)`. The rule is still owner/descriptor based, not tied
to a plugin name, command name, or source line. On Folia, the bridge submits
`teleportAsync` and returns `true` for the old boolean return shape; async
completion failures remain logged as unsafe failures.

`generic-helper-shape` exists because home/warp plugins often call shaded
scheduler-helper APIs such as `getScheduler().teleportAsync(player, location)`
or `getImpl().teleportAsync(player, location)`. Those helper interfaces commonly
expose `(Entity,Location)` and `(Entity,Location,TeleportCause)` overloads
returning `CompletableFuture<Boolean>`. This shape is not rewritten by the bridge
because the helper may already choose the correct threaded route. The log is
still valuable: it records the owner/name/descriptor so a live failure can prove
whether the helper itself, or code around it, needs the next rule.

If a future shaded helper truly needs an exact owner, it should log as
`rule=exact-owner` and the owner/descriptor pair must be documented here as a
bytecode-shape exception.

## Raw Scheduler Fallback

On real Paperclip/Folia servers, the raw scheduler transformer is the important fallback. It rewrites exact scheduler call descriptors to `ObjectSchedulerBridge`, then returns a proxy shaped like `BukkitTask` when the original bytecode expects one. This avoids requiring the agent classloader to resolve every Bukkit/Paper API type before plugin classes load.

The inherited `BukkitRunnable` fallback is deliberately narrower than a plain method-name match. It accepts `org/bukkit/scheduler/BukkitRunnable` directly and javac anonymous-owner names such as `PlayersConfigManager$1`, but it does not rewrite named plugin helper methods such as `Essentials#runTaskAsynchronously(Runnable)`. The live smoke test proved those helpers can look scheduler-like while using a different receiver shape, so they must be routed through their internal `BukkitScheduler` call instead.

Self-attach can miss an already-loaded caller class. For anonymous `BukkitRunnable` subclasses loaded after the agent is installed, the raw transformer also adds narrow override methods such as `runTaskAsynchronously(Plugin)` directly on the anonymous receiver. That catches virtual calls like `PlayersConfigManager$1#runTaskAsynchronously(Plugin)` even when `PlayersConfigManager#loadConfig` itself was loaded before its call instruction could be rewritten. This is still route-family based: the override emits `S_GLOBAL` or `S_ASYNC` through `ObjectSchedulerBridge`, and it is limited to javac anonymous classes that directly extend `BukkitRunnable`.

For proof-level diagnostics, enable `traceBytecodePaths=true`. The bridge will emit `[FBB bytecode-path]` lines that show the original JVM owner/name/descriptor, the selected `RouteFamily`, and the `ObjectSchedulerBridge` method. Use those lines to decide whether the next rule belongs in an existing route family or needs a new documented bytecode shape.

## S_GLOBAL Command Dispatch

Command dispatch is not plugin-specific. It is promoted only for these exact
Bukkit API bytecode shapes:

```text
org/bukkit/Bukkit#dispatchCommand(CommandSender,String)Z
org/bukkit/Server#dispatchCommand(CommandSender,String)Z
```

The transformer logs `[FBB guard-path] action=rewritten` because the original
failure comes from the `CraftServer#dispatchCommand` guard. At runtime,
`UnsafeCallBridge` uses the sender to choose the Folia path:

- entity senders use the sender's entity scheduler
- console and other non-entity senders use the global region scheduler

The old method returns a synchronous boolean. On Folia, the bridge returns
`true` after the task is accepted and logs `return=scheduled-true`. That keeps
the server thread unblocked while preserving failure evidence through
`[FBB task-failure]` and `[FBB unsafe-failure]`.

## S_GLOBAL Paper Main Executor

Paper's `io.papermc.paper.util.MCUtil.MAIN_EXECUTOR` represents the legacy
single-main-thread executor. On Folia, bytecode that submits work directly to
that executor can still reach `MinecraftServer#execute(Runnable)` and throw an
`UnsupportedOperationException` because there is no single safe main thread.

The bridge treats this as a general `S_GLOBAL` bytecode shape, not as support
for one plugin. `RawMcUtilExecutorTransformer` looks for the exact field owner
and descriptor:

```text
GETSTATIC io/papermc/paper/util/MCUtil.MAIN_EXECUTOR:Ljava/util/concurrent/Executor;
INVOKEINTERFACE java/util/concurrent/Executor.execute:(Ljava/lang/Runnable;)V
```

When that shape is found, the execute call is rewritten to
`ServerExecutorBridge#mcUtilMainExecutorExecute(Executor,Runnable)`, which
schedules the runnable through Folia's global scheduler and keeps task failures
loud through `[FBB task-failure]`.

Some adapters bypass `MCUtil.MAIN_EXECUTOR` and call the NMS server executor
directly:

```text
INVOKEVIRTUAL net/minecraft/server/MinecraftServer.execute:(Ljava/lang/Runnable;)V
```

That is the same legacy server-executor assumption, so
`RawNmsServerExecutorTransformer` rewrites it to
`ServerExecutorBridge#minecraftServerExecute(Object,Runnable)`. This rule is
intentionally keyed to the NMS owner/name/descriptor, not to a plugin name or
command path. If this path fails again, look near the `raw-nms-server-executor`
diagnostic and decide whether the new bytecode owner is another server-executor
shape or a different route family.

Expected diagnostics:

```text
[FBB guard-path] ... owner=io.papermc.paper.util.MCUtil name=MAIN_EXECUTOR.execute descriptor=(Ljava/lang/Runnable;)V route=S_GLOBAL guard=MCUtil#main-executor action=rewritten
[FBB scheduler] api=MCUtil.MAIN_EXECUTOR#execute route=S_GLOBAL policy=global-server-executor
[FBB guard-path] ... owner=net.minecraft.server.MinecraftServer name=execute descriptor=(Ljava/lang/Runnable;)V route=S_GLOBAL guard=MinecraftServer#server-executor action=rewritten
[FBB transform] ... path=raw-nms-server-executor result=patched replacements=1
```

## S_GLOBAL Legacy Main-Thread Predicate

`S_GLOBAL` also owns the current `LEGACY_MAIN_THREAD_EXPECTATION` compatibility
model. This is not a broad rewrite of every `isMainThread()` method. The bridge
currently has one exact method-body rule for
`com.fastasyncworldedit.core.Fawe#isMainThread()Z` because live logs proved that
`QueueHandler#run` failed with `IllegalStateException: Not main thread` after
the task had already been routed through the Folia global scheduler.

The rewrite preserves FAWE's original captured-thread check and then calls
`LegacyMainThreadBridge` only when the original predicate would return `false`.
The fallback accepts Folia tick/region or Bukkit primary-thread contexts and
keeps async worker threads as `false`. Other static no-arg boolean
`isMainThread()` callsites are logged with `[FBB legacy-main-thread]` as
`trace-only` until a specific owner model is proven.

## Paper Guard Trace

`RawGuardTraceTransformer` is evidence-only for paths that are not yet promoted.
It logs `[FBB guard-path]` for Paper/Folia guard-backed calls discovered from
`docs/PAPER_GUARD_AUDIT.md`, but it does not rewrite them. Promoted guard paths,
such as command dispatch, are owned by a dedicated raw transformer and log
`action=rewritten`.

Example:

```text
[FBB guard-path] class=some.plugin.Menu owner=org.bukkit.scoreboard.ScoreboardManager name=getNewScoreboard descriptor=()Lorg/bukkit/scoreboard/Scoreboard; route=D_PLAYER_UI guard=CraftScoreboardManager action=trace-only reason=paper-guard-audit: unowned scoreboard creation has no player/entity context; no safe bridge route yet
```

Treat `guard-path` as a pointer, not a fix. Once a path has enough context and a
safe Folia replacement, add a real rewrite and keep the guard trace as audit
evidence.

The TAB/BetterBoard/SuperVanish reference audit split scoreboard bytecode into
two shapes. Player-owned calls such as `Player#getScoreboard`,
`Player#setScoreboard`, `Scoreboard#registerNewTeam`, team display/style
mutations, `Scoreboard#registerNewObjective`, objective display/number-format
mutations, `Objective#getScore`, and score value/display mutations now emit
`D_PLAYER_UI` diagnostics and model state when the receiver is bridge-owned.
`ScoreboardManager#getNewScoreboard()` now has a detached `D_PLAYER_UI` shim
model path for the exact Bukkit owner/descriptor. Player-visible application is
still deferred until a packet/player-owned apply route exists; native scoreboard
owners that are not bridge-owned remain loud diagnostics instead of being guessed
as `S_GLOBAL`.
