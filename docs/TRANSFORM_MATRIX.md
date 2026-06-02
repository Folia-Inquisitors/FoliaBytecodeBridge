# Transform Matrix

This file tracks every supported bytecode rewrite. Keep it updated before adding more substitutions.

| Original call | Folia replacement policy | Notes |
| --- | --- | --- |
| `BukkitScheduler#runTask(plugin, runnable)` | global scheduler `run` | Does not imply region ownership. |
| `BukkitScheduler#runTaskLater(plugin, runnable, delay)` | global scheduler `runDelayed` | Delay is in ticks. |
| `BukkitScheduler#runTaskTimer(plugin, runnable, delay, period)` | global scheduler `runAtFixedRate` | Delay and period are in ticks. |
| `BukkitScheduler#runTaskAsynchronously(plugin, runnable)` | async scheduler `runNow` | Preserves async behavior. |
| `BukkitScheduler#runTaskLaterAsynchronously(plugin, runnable, delay)` | async scheduler `runDelayed` | Tick delay converts to milliseconds. |
| `BukkitScheduler#runTaskTimerAsynchronously(plugin, runnable, delay, period)` | async scheduler `runAtFixedRate` | Tick values convert to milliseconds. |
| `BukkitScheduler#scheduleSyncDelayedTask(plugin, runnable)` | global scheduler `run` | Returns synthetic task id. |
| `BukkitScheduler#scheduleSyncDelayedTask(plugin, runnable, delay)` | global scheduler `runDelayed` | Returns synthetic task id. |
| `BukkitScheduler#scheduleSyncRepeatingTask(plugin, runnable, delay, period)` | global scheduler `runAtFixedRate` | Returns synthetic task id. |
| `BukkitScheduler#scheduleAsyncDelayedTask(plugin, runnable)` | async scheduler `runNow` | Returns synthetic task id. |
| `BukkitScheduler#scheduleAsyncDelayedTask(plugin, runnable, delay)` | async scheduler `runDelayed` | Returns synthetic task id. |
| `BukkitScheduler#scheduleAsyncRepeatingTask(plugin, runnable, delay, period)` | async scheduler `runAtFixedRate` | Returns synthetic task id. |
| `BukkitScheduler#callSyncMethod(plugin, callable)` | global scheduler `run` completing `CompletableFuture` | Callable still runs globally, not on an owned entity/region. |
| `BukkitScheduler#cancelTask(id)` | cancels synthetic task if known | Unknown ids are ignored on Folia. |
| `BukkitScheduler#cancelTasks(plugin)` | cancels synthetic, global, and async tasks for plugin | Mirrors Folia scheduler cancellation APIs. |
| `BukkitRunnable#runTask*` | same policy as matching scheduler method | Tracks runnable to support `cancel` and `isCancelled`. |
| `BukkitRunnable#cancel()` | cancels tracked synthetic task | Falls back on non-Folia. |
| `BukkitRunnable#isCancelled()` | checks tracked synthetic task | Falls back on non-Folia. |
| `Entity|Player#teleport(Location)` and `Entity|Player#teleport(Location, TeleportCause)` | `A_ENTITY` direct Bukkit-owner shim to `teleportAsync` on Folia | Covers legacy home/warp teleports by owner/name/descriptor. Returns `true` after async submission because the old boolean return cannot represent a future without blocking the region thread. |
| Static `teleportAsync(Entity|Player, Location, TeleportCause)` returning `CompletableFuture<Boolean>` | `A_ENTITY` generic static-shape shim to `Entity#teleportAsync` | Covers shaded PaperLib-style helper owners without checking plugin or command names. Logs owner/name/descriptor via `[FBB teleport-path]`. |
| Helper `teleportAsync(Entity|Player, Location, ...)` with any return type | `A_ENTITY` trace-only helper-shape evidence | Covers scheduler-helper owners seen in home/warp plugins. It is not rewritten because those helpers may already choose the correct threaded route. |
| `Bukkit#dispatchCommand(CommandSender,String)` and `Server#dispatchCommand(CommandSender,String)` | `S_GLOBAL` command dispatch shim | Console/global senders are scheduled on the global region scheduler. Entity senders are scheduled on their entity scheduler. On Folia this returns `true` after scheduling, with `[FBB task-failure]` and `[FBB unsafe-failure]` if the dispatched task fails. |
| `Player#openInventory(Inventory)`, `Player#closeInventory()`, `HumanEntity#openInventory(Inventory)`, `HumanEntity#closeInventory()` | `D_PLAYER_UI` preemptive entity-owner route | Player inventory/menu state is player-owned UI. The bridge now routes through the receiver's entity scheduler before Folia throws from async/global contexts. `HumanEntity` is handled because many compiled plugins use the inherited interface owner even when source code says `Player`. |
| `Entity/Player#getNearbyEntities(double,double,double)` | `G_WORLD_SCAN_SPLIT` entity scheduler retry with sync-return guard | Raw bytecode rewrite covers owner shapes that the typed pass can miss. The bridge tries the direct call first; if Folia rejects the current thread, it retries through the entity scheduler only when waiting for the return value will not park a Folia owner thread. Otherwise it logs `fallback=blocked-sync-return-avoided` and preserves the original failure. |
| `Chunk#getEntities()` | `G_WORLD_SCAN_SPLIT` chunk scheduler retry | Chunk-owned scan. The bridge tries the direct call first and retries on the owning chunk region if Folia rejects the current thread. |
| `World#getLoadedChunks()` | `G_WORLD_SCAN_SPLIT` diagnostic wrapper | Whole-world scan. The bridge logs caller/failure evidence but does not scheduler-hop because bytecode has no single region owner. |
| `World#getNearbyEntities(Location,double,double,double)` | `G_WORLD_SCAN_SPLIT` preemptive bounded split scan | On Folia the bridge skips the unsafe direct world scan, enumerates loaded chunks, scans candidate chunks on their owning region scheduler, filters to the original AABB, and logs `fallback=preemptive-bounded-split-scan`. |
| `ScoreboardManager#getNewScoreboard()` | `D_PLAYER_UI` detached scoreboard shim model | Exact Bukkit owner/descriptor rewrites to a detached model. This is not a scheduler retry; logs use `policy=shim-model`. Team, objective, and score operations on the model are modeled, while visible client application remains deferred until a packet/player-owned apply path exists. |
| `Scoreboard#registerNewObjective(...)`, `Objective#getScore(...)`, `Objective#setDisplayName(String)`, `Objective#displayName(Component)`, `Objective#numberFormat(NumberFormat)` | `D_PLAYER_UI` scoreboard objective model | Exact Bukkit owner/name/descriptor rewrites for common string, component, criteria, render-type, display-name, and number-format shapes. This is universal bytecode-shape routing, not a plugin-specific scoreboard patch. |
| `Score#setScore(int)`, `Score#getScore()`, `Score#resetScore()`, `Score#customName(Component)`, `Score#numberFormat(NumberFormat)` | `D_PLAYER_UI` scoreboard score model | Score value and display state are retained in the bridge model so legacy code can continue mutating scoreboard state without touching Folia's hard-disabled native scoreboard implementation. |
| `Team#setDisplayName(String)`, `Team#displayName(Component)`, `Team#setPrefix(String)`, `Team#prefix(Component)`, `Team#setSuffix(String)`, `Team#suffix(Component)`, `Team#setColor(ChatColor)`, `Team#color(NamedTextColor)`, `Team#setAllowFriendlyFire(boolean)`, `Team#setCanSeeFriendlyInvisibles(boolean)` | `D_PLAYER_UI` scoreboard team display-state model | Team display/style/friendly-fire state is modeled for bridge-owned scoreboards. This prepares a later visible apply/packet layer; it does not pretend native Bukkit scoreboard assignment is safe on Folia. |
| `World#spawnEntity(Location,EntityType)` | `B_REGION_LOCATION` preemptive owner-check route | Location-owned entity spawn. On Folia the bridge asks whether the current thread owns the target location; owned calls stay direct, unowned calls go to the region scheduler, and owner-probe failures preserve the original call with `ownerCheck=failed-direct-preserve-original`. |
| `World#getChunkAt(int,int)`, `World#getChunkAt(Location)`, `World#getChunkAt(Block)`, `World#loadChunk(int,int)`, `World#refreshChunk(int,int)` | `B_REGION_LOCATION` / `C_REGION_BLOCK` preemptive owner-check route with sync-return guard | Raw bytecode rewrite covers exact chunk read/load/refresh descriptors. On Folia the bridge routes before triggering the known guard: direct for current-region ownership, region scheduler for unowned chunk/location when a synchronous wait is safe, loud direct preservation if the owner probe itself fails or the call would block a Folia owner thread waiting for a return value. |
| `Block#getType()`, `Block#getBlockData()`, `Block#setType(Material)` | `C_REGION_BLOCK` preemptive owner-check route | Block-owned material/data read and material mutation. The bridge asks whether the current thread owns the block, keeps owned calls direct, schedules unowned reads when waiting is safe, and uses logged block-material/block-data models for foreign owner threads where a synchronous region wait would stall Folia. |
| `World#dropItem(Location,ItemStack)`, `World#dropItemNaturally(Location,ItemStack)`, `World#generateTree(Location,TreeType)` | `B_REGION_LOCATION` preemptive owner-check route | Raw bytecode rewrite covers location-owned world mutations. Logs include `policy=preemptive-safe`, `ownerCheck=current-region-owned|schedule-owner-region|failed-direct-preserve-original`, and `fallback=preemptive-region-scheduler` when scheduling is selected. |
| `World#strikeLightning(Location)`, `World#strikeLightningEffect(Location)`, `World#createExplosion(Location,float)`, `World#createExplosion(double,double,double,float)` | `B_REGION_LOCATION` preemptive owner-check route | Raw bytecode rewrite covers location-owned effects. Lightning uses a deferred `LightningStrike` proxy for foreign owner-thread return values; boolean explosion routes return scheduled acceptance. Coordinate explosions create a `Location` from the receiving `World` and coordinates for scheduler ownership. |
| `World#playSound(Location,Sound,float,float)`, `World#playSound(Location,String,float,float)`, plus `SoundCategory` overloads | `B_REGION_LOCATION` preemptive owner-check route | Raw bytecode rewrite covers location-owned sound effects. Server-wide sound APIs remain trace-only because they do not expose one obvious region owner. |

## Not Supported Yet

These are intentionally not rewritten:

- `BukkitScheduler#runTask(Plugin, Consumer)`
- `BukkitScheduler#runTaskLater(Plugin, Consumer, long)`
- `BukkitScheduler#runTaskTimer(Plugin, Consumer, long, long)`
- direct calls to Paper/Folia scheduler classes
- unsupported scoreboard model operations outside the detached shim path. These must keep `D_PLAYER_UI` diagnostics until a packet/player-owned model route exists.

## Diagnostic Direct-Call Probes

These calls are rewritten to `UnsafeCallBridge` for logging and then passed through to the original API. They are not safety translations yet. The logs include a `family` and `next` hint so failures can be grouped into entity, player, region, or cross-region scan work.

| Original call family | Probe purpose |
| --- | --- |
| `Entity#getLocation`, `Entity#getWorld`, `Entity#teleport`, `Entity#teleportAsync`, `Entity#setVelocity` | Identify entity-owned operations that may need entity scheduler routing. Future failures from async futures are logged when the future completes exceptionally. |
| `Player#getLocation`, `Player#getWorld`, `Player#teleport`, `Player#setGameMode`, `Player#setVelocity` | Identify player-owned operations that may need entity scheduler routing. Cause-aware teleport overloads are included for server-utility plugin reference `/home` evidence. |
| `Player#openInventory`, `Player#closeInventory`, `HumanEntity#openInventory`, `HumanEntity#closeInventory` | `D_PLAYER_UI` preemptive entity-owner route for player inventory/menu open and close. |
| `HumanEntity#setGameMode` | Identify player state mutations that may need player/entity scheduler routing. |
| `Player#addPotionEffect`, `Player#removePotionEffect`, `LivingEntity#addPotionEffect`, `LivingEntity#removePotionEffect` | `A_ENTITY` preemptive owner-check route. If the current Folia thread does not own the receiver, the bridge schedules the mutation on that entity's scheduler before calling Bukkit. |
| `Player#hidePlayer`, `Player#showPlayer` | Identify visibility mutations that may need player/viewer scheduler routing. |
| `World#getBlockAt`, `World#getEntities`, `World#getChunkAt` | Identify world/chunk/entity scans that may need region scheduler routing. |
| `Block#setType`, `Block#getLocation` | Identify block-owned operations that may need region scheduler routing. |
| `Entity#getNearbyEntities`, `Entity#remove` | Identify entity scans and entity removal that may need entity scheduler routing. |
| `Player#playSound` | Identify player audio effects that may need player/entity scheduler routing. |
| `World#getNearbyEntities`, `World#getEntitiesByClass`, `World#getEntitiesByClasses` | Identify broad world scans that cannot be made safe without source-level region splitting. |
| `World#dropItem`, `World#dropItemNaturally`, `World#generateTree`, `World#strikeLightning`, `World#strikeLightningEffect`, `World#createExplosion`, `World#playSound(Location,...)` | Location-owned world mutations/effects now route through region ownership before Folia rejects the current thread. Return-object routes either wait from safe threads or use explicit logged models/proxies when a Folia owner thread cannot wait. |
| Static `teleportAsync(Entity|Player,Location,TeleportCause)` helper owners | Identify and route PaperLib-style shaded async teleport paths through `Entity#teleportAsync` while preserving `[FBB teleport-path]`, `[FBB unsafe-call]`, and future failure evidence. |
| Helper `teleportAsync(Entity|Player,Location,...)` owners | Preserve evidence for home/warp teleport helpers without overriding helper-library behavior. |

## Teleport Family Ownership

The teleport family is intentionally a mix:

- Direct Bukkit sync teleport calls are tied to Bukkit API owners because those owners define the API contract: `org.bukkit.entity.Entity` and `org.bukkit.entity.Player`. The raw transformer rewrites `(Location)Z` and `(Location,TeleportCause)Z` descriptors to the bridge, which uses `teleportAsync` on Folia.
- Static PaperLib-style helper calls are generic by method shape: method name `teleportAsync`, first argument `Entity` or `Player`, second `Location`, third `TeleportCause`, return `CompletableFuture`.
- Scheduler-helper calls are trace-only by method shape: method name `teleportAsync`, first argument `Entity` or `Player`, second `Location`, optional later arguments such as `TeleportCause`.
- Shaded package names are not used for the generic static helper rewrite.
- Unsupported static helper descriptors are traced as `missed-unsupported-static-shape` so the next rule can be evidence-based.
