# FastAsyncWorldEdit Audit Notes

Source reference: `<downloads>\FastAsyncWorldEdit-main (1).zip`

The source was extracted to a short path for Windows path-length reasons:

```text
<workspace>\FastAsyncWorldEdit-main
```

## Scheduler Calls Covered By The Bridge

FAWE mostly funnels Bukkit scheduling through central platform classes. That is good for the bytecode bridge because the transformer only needs to catch a small number of stable Bukkit scheduler call shapes.

Live evidence on 2026-05-29 showed `BukkitTaskManager#repeatAsync` reaching the legacy
`BukkitScheduler#scheduleAsyncRepeatingTask(Plugin,Runnable,long,long): int` API. That path is now
covered in the raw scheduler transformer as a general `S_ASYNC` bytecode shape, not as a FAWE-only
branch. The smoke assertion `rawFaweAsyncRepeatingHits=1` verifies the actual FAWE class rewrites to
`ObjectSchedulerBridge#scheduleAsyncRepeatingTask`.

| File | Pattern | Bridge result |
| --- | --- | --- |
| `worldedit-bukkit/src/main/java/com/fastasyncworldedit/bukkit/util/BukkitTaskManager.java` | `scheduleSyncRepeatingTask` | global repeating task |
| `worldedit-bukkit/src/main/java/com/fastasyncworldedit/bukkit/util/BukkitTaskManager.java` | `scheduleAsyncRepeatingTask` | async repeating task |
| `worldedit-bukkit/src/main/java/com/fastasyncworldedit/bukkit/util/BukkitTaskManager.java` | `runTaskAsynchronously` | async task |
| `worldedit-bukkit/src/main/java/com/fastasyncworldedit/bukkit/util/BukkitTaskManager.java` | `runTask` | global task |
| `worldedit-bukkit/src/main/java/com/fastasyncworldedit/bukkit/util/BukkitTaskManager.java` | `runTaskLater` | global delayed task |
| `worldedit-bukkit/src/main/java/com/fastasyncworldedit/bukkit/util/BukkitTaskManager.java` | `runTaskLaterAsynchronously` | async delayed task |
| `worldedit-bukkit/src/main/java/com/fastasyncworldedit/bukkit/util/BukkitTaskManager.java` | `cancelTask` | synthetic task cancellation when known |
| `worldedit-bukkit/src/main/java/com/sk89q/worldedit/bukkit/BukkitServerInterface.java` | `scheduleSyncRepeatingTask` | global repeating task |
| `worldedit-bukkit/src/main/java/com/sk89q/worldedit/bukkit/BukkitBlockCommandSender.java` | `callSyncMethod` | global scheduler future |
| `worldedit-bukkit/src/main/java/com/sk89q/worldedit/bukkit/WorldEditPlugin.java` | `cancelTasks` | synthetic/global/async tasks cancelled for plugin |

## Direct Bukkit/NMS Actions Still Risky

FAWE has many direct world, chunk, entity, and block operations. Examples found during the scan include:

- `BukkitWorld#getWorld().getBlockAt(...)`
- `BukkitWorld#getWorld().getChunkAt(...)`
- `BukkitWorld#getWorld().setBiome(...)`
- `BukkitWorld#getWorld().refreshChunk(...)` maps to `B_REGION_LOCATION` as a chunk-owned scheduler retry.
- `BukkitWorld#getWorld().getEntities()`
- `World#getLoadedChunks()` and `Chunk#getEntities()` style scan paths
- `World#spawnEntity(Location,EntityType)` style location-owned entity spawn paths
- `BukkitPlayer#player.teleport(...)`
- `BukkitPlayer#player.setGameMode(...)`
- `BukkitPlayer#player.getWorld().dropItem(...)`
- `BukkitEntity#entity.teleport(...)`
- chunk/entity scans in `ChunkListener`
- multiple region integrations reading `Player#getLocation` or `Player#getWorld`

The bridge now routes reusable Bukkit API shapes where the bytecode supplies a clear owner:
`Chunk#getEntities()` retries on the chunk's region scheduler, and `World#spawnEntity(Location,EntityType)`
retries on the location's region scheduler. Broad whole-world scans such as `World#getEntities()` and
`World#getLoadedChunks()` are intentionally diagnostic-only because bytecode cannot infer how the caller
intends to split work across many regions.

Live evidence from `latest.log` also showed:

```text
java.lang.NoSuchFieldError: Class net.minecraft.server.MinecraftServer does not have member field 'int currentTick'
```

That failure occurs in FAWE's `PaperweightFaweWorldNativeAccess` adapter before a Bukkit
region/thread guard is reached. It is a version/NMS linkage problem, not an unsafe Bukkit
API shape that the bridge can safely translate by scheduler routing. Keep it as compatibility
evidence, but do not hide it under a bytecode scheduler rule.

## Expected Bridge Help

The bridge should help FAWE only with legacy Bukkit scheduler entry points. It may reduce immediate scheduler API errors, especially where FAWE's `TaskManager` reaches Bukkit's old scheduler.

## Remaining Manual Folia Work

FAWE is much deeper than a scheduler compatibility problem. True Folia compatibility needs source-level work around:

- world edit operations grouped by region/chunk ownership
- player/entity operations routed through entity schedulers
- block/chunk operations routed through region schedulers
- NMS adapter behavior checked against Folia region rules

This bridge is useful as an experiment, but FAWE should not be considered Folia-safe just because scheduler bytecode rewrites pass.
