# Direct Unsafe Trace Matrix

Most bytecode substitutions in this matrix are diagnostic wrappers around high-risk direct Bukkit calls: they execute the original call and log caller/failure details. Sync teleport and proven owner routes such as inventory UI, potion effects, block state, chunk/location reads, and split world scans are exceptions because Folia gives us enough ownership evidence to route before the unsafe call throws.

Exact owner/name/descriptor substitutions should be added first to
`RouteRuleRegistry`. The matrix below is the human-readable view; the registry
is the code source of truth for route family, guard label, bridge method, return
policy, status, and note. Dynamic/generalized routes, such as shaded teleport
helper detection, stay outside the exact registry until their bytecode shape is
safe enough to promote.

Enable with:

```text
-Dfoliabytecodebridge.traceUnsafeCalls=true
```

or the broader:

```text
-Dfoliabytecodebridge.debug=true
```

| Original call | Diagnostic wrapper | Route | Family | Next hint | Why traced |
| --- | --- | --- | --- | --- | --- |
| `Entity#getLocation()` | `UnsafeCallBridge#entityGetLocation` | `A_ENTITY` | `entity` | `entity-scheduler-read` | Region-owned entity read. |
| `Entity#getWorld()` | `UnsafeCallBridge#entityGetWorld` | `A_ENTITY` | `entity` | `entity-scheduler-read` | Entity world read can reveal wrong-region access. |
| `Entity#teleport(Location)` | `UnsafeCallBridge#entityTeleport` | `A_ENTITY` | `entity` | `entity-scheduler-teleport-async` | Sync teleport shimmed to `teleportAsync` on Folia; returns `true` after submission. |
| `Entity#teleport(Location,TeleportCause)` | `UnsafeCallBridge#entityTeleport` | `A_ENTITY` | `entity` | `entity-scheduler-teleport-async` | Cause-aware sync teleport shimmed to `teleportAsync` on Folia. |
| `Entity#teleportAsync(Location,TeleportCause)` | `UnsafeCallBridge#entityTeleportAsync` | `A_ENTITY` | `entity` | `entity-scheduler-teleport-async` | Async entity teleport. Failures are logged when the returned future completes exceptionally. |
| `Entity#setVelocity(Vector)` | `UnsafeCallBridge#entitySetVelocity` | `A_ENTITY` | `entity` | `entity-scheduler-mutation` | Entity mutation. |
| `Entity#getNearbyEntities(...)` | `UnsafeCallBridge#entityGetNearbyEntities` | `G_WORLD_SCAN_SPLIT` | `entity-scan` | `entity-scheduler-scan` / `entity-location-bounded-split` | Nearby entity scan from an entity-owned point. On Folia this is preemptive: owned calls stay direct, async callers can wait for the entity scheduler, and owner/global/foreign-region threads use the bounded split-scan model to preserve the synchronous list return without throwing first. |
| `Entity#remove()` | `UnsafeCallBridge#entityRemove` | `A_ENTITY` | `entity` | `entity-scheduler-mutation` | Entity removal mutation. |
| `Player#getLocation()` | `UnsafeCallBridge#playerGetLocation` | `A_ENTITY` | `player` | `entity-scheduler-read` | Player-owned read. |
| `Player#getWorld()` | `UnsafeCallBridge#playerGetWorld` | `A_ENTITY` | `player` | `entity-scheduler-read` | Player-owned read. |
| `Player#teleport(Location)` | `UnsafeCallBridge#playerTeleport` | `A_ENTITY` | `player` | `entity-scheduler-teleport-async` | Sync player teleport shimmed to `teleportAsync` on Folia; covers home/warp plugins that build a `Location` and call the legacy API. |
| `Player#teleport(Location,TeleportCause)` | `UnsafeCallBridge#playerTeleport` | `A_ENTITY` | `player` | `entity-scheduler-teleport-async` | Cause-aware player teleport shimmed to `teleportAsync` on Folia. |
| `Player#setGameMode(GameMode)` | `UnsafeCallBridge#playerSetGameMode` | `A_ENTITY` | `player` | `entity-scheduler-mutation` | Player mutation. |
| `Player#setVelocity(Vector)` | `UnsafeCallBridge#playerSetVelocity` | `A_ENTITY` | `player` | `entity-scheduler-mutation` | Player mutation. |
| `Player#playSound(...)` | `UnsafeCallBridge#playerPlaySound` | `A_ENTITY` | `player` | `entity-scheduler-audio` | Player audio effect; seen in kit plugin reference and Essentials command effects. |
| `Player#openInventory(Inventory)` | `UnsafeCallBridge#playerOpenInventory` | `D_PLAYER_UI` | `player` | `entity-scheduler-ui` | Player UI mutation. On Folia this is preemptive: if the current thread does not own the player, the bridge schedules through the player's entity scheduler before opening the inventory. |
| `Player#closeInventory()` | `UnsafeCallBridge#playerCloseInventory` | `D_PLAYER_UI` | `player` | `entity-scheduler-ui` | Player UI mutation. On Folia this is preemptive and prevents async/global close-event failures. |
| `HumanEntity#openInventory(Inventory)` | `UnsafeCallBridge#humanOpenInventory` | `D_PLAYER_UI` | `player` | `entity-scheduler-ui` | Player/human UI mutation when bytecode owner is the inherited interface. On Folia this routes through the receiver's entity scheduler. |
| `HumanEntity#closeInventory()` | `UnsafeCallBridge#humanCloseInventory` | `D_PLAYER_UI` | `player` | `entity-scheduler-ui` | Player/human UI mutation when bytecode owner is the inherited interface. On Folia this routes through the receiver's entity scheduler. |
| `Player#getScoreboard()` | `UnsafeCallBridge#playerGetScoreboard` | `D_PLAYER_UI` | `scoreboard` | `scoreboard-player-owned-read` | Player-owned scoreboard model read; added after TAB/BetterBoard/visibility plugin reference reference audit. |
| `Player#setScoreboard(Scoreboard)` | `UnsafeCallBridge#playerSetScoreboard` | `D_PLAYER_UI` | `scoreboard` | `scoreboard-player-owned-assign` | Player-owned scoreboard assignment. |
| `Scoreboard#getTeam(String)` | `UnsafeCallBridge#scoreboardGetTeam` | `D_PLAYER_UI` | `scoreboard` | `scoreboard-model-team-read` | Team lookup on a scoreboard model; diagnostic only until the owning player/display is known. |
| `Scoreboard#registerNewTeam(String)` | `UnsafeCallBridge#scoreboardRegisterNewTeam` | `D_PLAYER_UI` | `scoreboard` | `scoreboard-model-team-create` | Team creation on a scoreboard model; diagnostic only until packet/model shim evidence is stronger. |
| `Scoreboard#registerNewObjective(...)` | `UnsafeCallBridge#scoreboardRegisterNewObjective*` | `D_PLAYER_UI` | `scoreboard` | `scoreboard-model-objective-create` | Objective creation on detached/player-owned bridge scoreboards. Exact descriptor overloads are routed by bytecode shape, not plugin name. |
| `Scoreboard#getObjective(...)` / `getObjectives()` | `UnsafeCallBridge#scoreboardGetObjective*` / `scoreboardGetObjectives` | `D_PLAYER_UI` | `scoreboard` | `scoreboard-model-objective-read` | Objective reads from the scoreboard model, including display-slot lookup. |
| `Objective#setDisplaySlot(DisplaySlot)` / `setDisplayName(String)` / `displayName(Component)` / `numberFormat(NumberFormat)` | `UnsafeCallBridge#objectiveSetDisplaySlot` / `objectiveSetDisplayName` / `objectiveDisplayName` / `objectiveNumberFormat` | `D_PLAYER_UI` | `scoreboard` | `scoreboard-model-objective-mutation` | Objective state mutation remains in the shim model; visible client rendering is a later packet/apply layer. |
| `Objective#getScore(String)` / `getScore(OfflinePlayer)` | `UnsafeCallBridge#objectiveGetScore*` | `D_PLAYER_UI` | `scoreboard` | `scoreboard-model-score-read` | Score model lookup under a bridge objective. |
| `Score#setScore(int)` / `getScore()` / `resetScore()` / `customName(Component)` / `numberFormat(NumberFormat)` | `UnsafeCallBridge#scoreSetScore` / `scoreGetScore` / `scoreResetScore` / `scoreCustomName` / `scoreNumberFormat` | `D_PLAYER_UI` | `scoreboard` | `scoreboard-model-score-mutation/read` | Score value and display-state model mutation/read. |
| `Team#setDisplayName(String)` / `displayName(Component)` / `setPrefix(String)` / `prefix(Component)` / `setSuffix(String)` / `suffix(Component)` / `setColor(ChatColor)` / `color(NamedTextColor)` / friendly flags | `UnsafeCallBridge#teamSetDisplayName` / `teamDisplayName` / `teamSetPrefix` / `teamPrefix` / `teamSetSuffix` / `teamSuffix` / `teamSetColor` / `teamColor` / flag wrappers | `D_PLAYER_UI` | `scoreboard` | `scoreboard-model-team-mutation` | Team display/style/friendly-fire state is retained in the bridge model. Native visible apply remains deferred. |
| `Team#setOption(Option,OptionStatus)` | `UnsafeCallBridge#teamSetOption` | `D_PLAYER_UI` | `scoreboard` | `scoreboard-model-team-mutation` | Team option mutation, seen in Folia-compatible visibility plugin reference no-push shape. |
| `Team#addEntry/removeEntry(String)` | `UnsafeCallBridge#teamAddEntry/teamRemoveEntry` | `D_PLAYER_UI` | `scoreboard` | `scoreboard-model-team-mutation` | Team membership mutation. |
| `HumanEntity#setGameMode(GameMode)` | `UnsafeCallBridge#humanSetGameMode` | `A_ENTITY` | `player` | `entity-scheduler-mutation` | Source often looks like `Player#setGameMode`, but Paper declares it on `HumanEntity`. |
| `Player#addPotionEffect(PotionEffect)` | `UnsafeCallBridge#playerAddPotionEffect` | `A_ENTITY` | `player` | `entity-scheduler-mutation` | Player mutation. On Folia this is preemptive: current-owner calls stay direct; wrong-owner calls schedule through the player's entity scheduler and return `scheduled-true` with `policy=deferred-accepted-boolean`. |
| `Player#removePotionEffect(PotionEffectType)` | `UnsafeCallBridge#playerRemovePotionEffect` | `A_ENTITY` | `player` | `entity-scheduler-mutation` | Player mutation. On Folia this is fire-and-forget when the caller does not own the player because the legacy method has no return value. |
| `LivingEntity#addPotionEffect(PotionEffect)` | `UnsafeCallBridge#livingAddPotionEffect` | `A_ENTITY` | `entity` | `entity-scheduler-mutation` | Source often looks like `Player#addPotionEffect`, but Paper declares it on `LivingEntity`. On Folia this uses the same accepted-boolean entity-owner route as the player overload. |
| `LivingEntity#removePotionEffect(PotionEffectType)` | `UnsafeCallBridge#livingRemovePotionEffect` | `A_ENTITY` | `entity` | `entity-scheduler-mutation` | Source often looks like `Player#removePotionEffect`, but Paper declares it on `LivingEntity`. On Folia this schedules fire-and-forget when unowned. |
| `Player#hidePlayer(...)` | `UnsafeCallBridge#playerHidePlayer*` | `F_PLAYER_VISIBILITY` | `player` | `entity-scheduler-visibility` | Player visibility mutation. |
| `Player#showPlayer(...)` | `UnsafeCallBridge#playerShowPlayer*` | `F_PLAYER_VISIBILITY` | `player` | `entity-scheduler-visibility` | Player visibility mutation. |
| `World#getBlockAt(...)` | `UnsafeCallBridge#worldGetBlockAt` | `B_REGION_LOCATION` or `C_REGION_BLOCK` | `region` | `region-scheduler-by-block/location` | Region-owned block access. |
| `World#getEntities()` | `UnsafeCallBridge#worldGetEntities` | `G_WORLD_SCAN_SPLIT` | `world-scan` | `split-scan-by-loaded-chunks` | Cross-region entity scan. On Folia this is preemptive: the bridge enumerates loaded chunks and reads each chunk through its owning region scheduler instead of triggering the unsafe whole-world call first. |
| `World#getLoadedChunks()` | `UnsafeCallBridge#worldGetLoadedChunks` | `G_WORLD_SCAN_SPLIT` | `world-scan` | `world-loaded-chunk-index` | Cross-world chunk enumeration. Direct call is tried first; Folia thread guards retry on the global scheduler so split scans have a chunk index, with failures still logged. |
| `Chunk#getEntities()` | `UnsafeCallBridge#chunkGetEntities` | `G_WORLD_SCAN_SPLIT` | `chunk-scan` | `region-scheduler-by-chunk` | Chunk-owned entity scan. The bridge can retry through the chunk's owning region scheduler. |
| `World#getNearbyEntities(...)` | `UnsafeCallBridge#worldGetNearbyEntities` | `G_WORLD_SCAN_SPLIT` | `world-scan` | `region-scheduler-by-location-bounded-scan` | Bounded location scan. On Folia this is now preemptive: enumerate loaded chunks, read candidate chunks through their owning region scheduler, then filter entities to the original AABB. Logs `fallback=preemptive-bounded-split-scan`. |
| `World#getEntitiesByClass(...)` | `UnsafeCallBridge#worldGetEntitiesByClass` | `G_WORLD_SCAN_SPLIT` | `world-scan` | `split-scan-by-loaded-chunks` | Cross-region typed entity scan. On Folia this uses the preemptive chunk split, then filters by class shape. |
| `World#getEntitiesByClasses(...)` | `UnsafeCallBridge#worldGetEntitiesByClasses` | `G_WORLD_SCAN_SPLIT` | `world-scan` | `split-scan-by-loaded-chunks` | Cross-region typed entity scan. On Folia this uses the preemptive chunk split, then filters by class shape. |
| `World#getChunkAt(int,int)` | `UnsafeCallBridge#worldGetChunkAt` | `B_REGION_LOCATION` | `region` | `region-scheduler-by-chunk` | Chunk/region access. On Folia this is preemptive: owner check first, direct when currently owned, scheduler when not owned, loaded-chunk-index return when available, and a deferred chunk proxy plus async preload when a Folia owner thread cannot safely wait and the chunk is not already loaded. |
| `World#loadChunk(int,int)` | `UnsafeCallBridge#worldLoadChunk` | `B_REGION_LOCATION` | `region` | `region-scheduler-by-chunk` | Void chunk-owned load request. On Folia this can use the fire-and-forget owner-region route because no synchronous return value has to be preserved. |
| `World#refreshChunk(int,int)` | `UnsafeCallBridge#worldRefreshChunk` | `B_REGION_LOCATION` | `region` | `region-scheduler-by-chunk` | Chunk-owned refresh with a boolean return. When a safe synchronous wait is impossible, the bridge schedules the refresh on the owner region and returns `scheduled-true` with `policy=deferred-accepted-boolean`. |
| `World#dropItem(...)` | `UnsafeCallBridge#worldDropItem` | `B_REGION_LOCATION` | `region` | `region-scheduler-by-location` | Location-owned item spawn. Uses `ownerCheck=current-region-owned|schedule-owner-region|failed-direct-preserve-original`. |
| `World#dropItemNaturally(...)` | `UnsafeCallBridge#worldDropItemNaturally` | `B_REGION_LOCATION` | `region` | `region-scheduler-by-location` | Location-owned item spawn. Uses the preemptive location-owner route. |
| `World#spawnEntity(Location,EntityType)` | `UnsafeCallBridge#worldSpawnEntity` | `B_REGION_LOCATION` | `region` | `region-scheduler-by-location` | Location-owned entity spawn. Uses the preemptive location-owner route. |
| `World#generateTree(...)` | `UnsafeCallBridge#worldGenerateTree` | `B_REGION_LOCATION` | `region` | `region-scheduler-by-location` | Location-owned block mutation. Uses the preemptive location-owner route. |
| `World#strikeLightning(...)` | `UnsafeCallBridge#worldStrikeLightning` | `B_REGION_LOCATION` | `region` | `region-scheduler-by-location` | Location-owned world effect. Owned calls stay direct, async callers can wait for the region scheduler, and foreign/global owner threads return a deferred `LightningStrike` proxy so the bridge does not retry the unsafe direct call. |
| `World#strikeLightningEffect(...)` | `UnsafeCallBridge#worldStrikeLightningEffect` | `B_REGION_LOCATION` | `region` | `region-scheduler-by-location` | Location-owned visual world effect. Uses the same deferred lightning proxy policy when a return object is required from a foreign owner thread. |
| `World#createExplosion(Location,float)` | `UnsafeCallBridge#worldCreateExplosion` | `B_REGION_LOCATION` | `region` | `region-scheduler-by-location` | Location-owned world mutation/effect with a boolean return. When a safe synchronous wait is impossible, the bridge schedules the effect and returns `scheduled-true`; task failures remain loud. |
| `World#playSound(Location,...)` | `UnsafeCallBridge#worldPlaySound` | `B_REGION_LOCATION` | `region` | `region-scheduler-by-location` | Void location-owned audio effect. On Folia this uses the fire-and-forget owner-region route and keeps task failures loud. |
| `Block#getType()` | `UnsafeCallBridge#blockGetType` | `C_REGION_BLOCK` | `region` | `region-scheduler-by-block` | Block-owned material read. Uses preemptive owner check on Folia, schedules on the owning region when a safe wait is possible, and falls back to the block-material model only from foreign owner threads where waiting would stall Folia. |
| `Block#setType(Material)` | `UnsafeCallBridge#blockSetType` | `C_REGION_BLOCK` | `region` | `region-scheduler-by-block` | Void block mutation. On Folia this uses the fire-and-forget owner-region route when the current thread does not own the block. |
| `Block#getLocation()` | `UnsafeCallBridge#blockGetLocation` | `C_REGION_BLOCK` | `region` | `region-scheduler-by-block` | Block position read, useful as context for failures. |
| Static `teleportAsync(Entity|Player,Location,TeleportCause)` helper | `UnsafeCallBridge#staticTeleportAsync` | `A_ENTITY` | `entity` | `entity-scheduler-teleport-async` | PaperLib-style shaded helper path used by teleport commands. The shim logs the original bytecode owner/descriptor, delegates to `Entity#teleportAsync`, and logs completion failures. |
| Helper `teleportAsync(Entity|Player,Location,...)` | trace-only `[FBB teleport-path]` | `A_ENTITY` | entity/player helper | observed-entity-location-helper | Scheduler-helper path used by home/warp plugins. The bridge logs owner/name/descriptor but does not rewrite because the helper may already choose the correct threaded route. |

## How To Use The Output

Unsafe-call logs point to the source section:

```text
[FBB unsafe-call] api=Player#teleport(Location) route=A_ENTITY family=player next=entity-scheduler-teleport-async detail=player=Raj target=world,10,64,10 cause=PLUGIN shim=teleportAsync caller=com.example.PluginClass#method(PluginClass.java:123)
```

Unsafe-failure logs point to where the direct call failed:

```text
[FBB unsafe-failure] api=World#getBlockAt(int,int,int) route=C_REGION_BLOCK family=region next=region-scheduler-by-block probableFrame=com.example.PluginClass#method(PluginClass.java:123) throwable=...
```

Once the failing shape is known, a future bridge version can add a more opinionated translation for that exact call family. For example, entity mutations can potentially be scheduled through `Entity#getScheduler()`, while location/block operations need a world and chunk or block location.

## Bytecode Owner Notes

The smoke test intentionally calls methods through a `Player` variable. Some calls still resolve to parent interfaces when Byte Buddy checks declaration ownership:

- `player.getLocation()`, `player.getWorld()`, `player.teleport(...)`, and `player.setVelocity(...)` are caught as `Entity`.
- `player.openInventory(...)`, `player.closeInventory()`, and `player.setGameMode(...)` are caught as `HumanEntity`.
- `player.addPotionEffect(...)` and `player.removePotionEffect(...)` are caught as `LivingEntity`.
- `player.hidePlayer(...)` and `player.showPlayer(...)` are caught as `Player`.
- `ScoreboardManager#getNewScoreboard()` is an unowned `D_PLAYER_UI` factory path. The bridge now rewrites that exact bytecode shape to a detached scoreboard shim model and logs `policy=shim-model`; player-visible application is still deferred because Folia hard-disables the Bukkit board assignment path.
- Team/objective/score display-state methods are modeled only when their receiver is bridge-owned. Native/non-modeled scoreboard receivers keep loud `D_PLAYER_UI` diagnostics so unsupported paths do not get quietly accepted.

When adding a new translation, match the real declared owner from the API, not only the apparent source type.

For method references, also check the captured receiver descriptor. The raw
transformer intentionally leaves mismatched `invokedynamic` handles as
`trace-only` evidence because `LambdaMetafactory` requires exact captured
receiver types and will throw before plugin code runs if the bridge handle is
too broad or too narrow.
