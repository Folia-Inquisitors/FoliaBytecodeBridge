# EssentialsX Audit

Source reviewed from `Essentials-2.x.zip`.

## Build Check

`:EssentialsX:build -x test` passes with the included Gradle wrapper after Gradle 9.1.0 is downloaded.

| Item | Result |
| --- | --- |
| Gradle module | `:EssentialsX` |
| Output jar | `Essentials-2.x/Essentials-2.x/Essentials/build/libs/EssentialsX-2.22.0-dev+null-unknown.jar` |
| Remaining notes | build succeeds with warnings from Java 8 source/target compatibility and dependency annotation metadata |

## Scheduler Shapes

Essentials centralizes many scheduler calls through `Essentials` / `IEssentials` wrapper methods:

| Area | Observed shape | Bridge status |
| --- | --- | --- |
| `Essentials.java` | wrapper methods call `getScheduler().runTaskAsynchronously`, `runTaskLaterAsynchronously`, `runTaskTimerAsynchronously`, `scheduleSyncDelayedTask`, and `scheduleSyncRepeatingTask` | covered at the wrapper implementation because it reaches `BukkitScheduler` |
| `AsyncTeleport.java` | `Bukkit.getScheduler().runTask(ess, ...)` callback | covered |
| `Backup.java`, `BalanceTopImpl.java`, `EssentialsPlayerListener.java`, command utilities | calls through `ess.runTaskAsynchronously` / `ess.scheduleSyncDelayedTask` | covered when wrapper class is transformed |
| `EssentialsDiscord*` modules | direct `Bukkit.getScheduler().runTask*` and `cancelTask` | covered |
| `EssentialsSpawn` | async join work and sync delayed teleport work through `ess` wrappers | covered when wrapper class is transformed |

The important caveat is startup order: the Java agent must be active before Essentials classes load, or the wrapper methods may be missed.

## Direct Bukkit Risks

Essentials is much broader than scheduler compatibility. The bridge now adds diagnostic probes for the high-risk families seen in the source scan:

| Area | Observed shape | Probe family |
| --- | --- | --- |
| Teleport and command logic | many `User#getLocation`, `User#getWorld`, and underlying `Player`/`Entity` location reads | `entity`, `entity-scheduler-read` when the underlying Bukkit call is direct |
| Item/kit/give commands | `World#dropItemNaturally` | `region`, `region-scheduler-by-location` |
| Fun commands | `Player#playSound`, `World#strikeLightning`, `World#strikeLightningEffect`, `World#createExplosion` | `player` audio or `region` location effects |
| Tree/firework style effects | `World#generateTree`; older source references to `World#spawnEntity` | `generateTree` probed; `spawnEntity` is not available in the local Paper 26.1.2 API jar |
| Entity searches | `Entity#getNearbyEntities`, `World#getNearbyEntities`, `World#getEntitiesByClass`, `World#getEntitiesByClasses` | `entity-scan` or `world-scan` |

## Expected Runtime Behavior

The bridge should translate Essentials' legacy scheduler entry points when the agent starts early enough. It should not be considered a full Folia port. Essentials uses many world/player/entity effects whose correct Folia target depends on the exact player, entity, world, and location at runtime. Those direct calls are logged so the next bytecode translation can be chosen from real failure evidence.
