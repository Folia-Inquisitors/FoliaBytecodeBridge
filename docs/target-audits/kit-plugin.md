# kit plugin reference Audit

Source reviewed from `kit-plugin-reference.zip`.

## Build Check

`mvn package -DskipTests` now passes in the extracted source tree after one dependency hygiene fix:

| Item | Result |
| --- | --- |
| Maven runtime | portable Maven 3.9.9 under `tools/apache-maven-3.9.9` |
| POM adjustment | `me.clip:placeholderapi` changed from unavailable `2.11.1` to available `2.11.6` |
| Output jar | `kit plugin reference-main/kit plugin reference-main/target/kit-plugin-reference.jar` |
| Remaining notes | compile emits deprecation/removal warnings for older Bukkit potion, enchantment, trim, and attribute APIs |

## Scheduler Shapes

kit plugin reference uses Bukkit scheduler APIs directly through `BukkitRunnable` and `Bukkit.getScheduler()` patterns already covered by the bridge:

| Area | Observed shape | Bridge status |
| --- | --- | --- |
| `database/MySQLConnection.java` | async database work with sync callbacks via `BukkitRunnable#runTaskAsynchronously` and `BukkitRunnable#runTask` | covered |
| `configs/PlayersConfigManager.java` | async player-data load with sync callback | covered |
| `tasks/PlayerDataSaveTask.java` | repeating async save task via `BukkitRunnable#runTaskTimerAsynchronously` | covered |
| `tasks/InventoryUpdateTaskManager.java` | repeating sync inventory update via `BukkitRunnable#runTaskTimer` | covered as global scheduler |
| `libs/actionbar/ActionBarAPI.java` | delayed restore/clear with `BukkitRunnable#runTaskLater` | covered |
| `listeners/InventoryEditListener.java` and `managers/edit/InventoryEditPositionManager.java` | one-tick delayed inventory reopen | covered |
| `managers/dependencies/Metrics.java` | `Bukkit.getScheduler().runTask(plugin, submitDataTask)` callback | covered |

## Direct Bukkit Risks

The plugin is GUI-heavy. The bridge now adds diagnostic probes for these kit plugin reference-relevant calls:

| Area | Observed shape | Probe family |
| --- | --- | --- |
| Inventory managers | `Player#openInventory` / `HumanEntity#openInventory` | `player`, `entity-scheduler-ui` |
| Inventory edit flows | `Player#closeInventory` / `HumanEntity#closeInventory` | `player`, `entity-scheduler-ui` |
| `ActionUtils#playSound` | `Player#playSound(...)` and `Player#getLocation()` | `player`, `entity-scheduler-audio` plus `entity-scheduler-read` |
| `ActionUtils` firework action | `Location#getWorld().spawnEntity(...)` in source | not covered on the current Paper API because `World#spawnEntity` is not present in the local 26.1.2 API jar |
| `KitsManager` overflow handling | `Player#getWorld().dropItemNaturally(Player#getLocation(), item)` | `region`, `region-scheduler-by-location` |

## Expected Runtime Behavior

The bridge should get kit plugin reference past legacy Bukkit scheduler failures. It does not make GUI work or item drops intrinsically Folia-safe; those are currently diagnostic probes. If Folia reports a region ownership error, use `[FBB unsafe-call]` and `[FBB unsafe-failure]` lines to identify whether the next translation should target player UI, player audio, or region item drops.
