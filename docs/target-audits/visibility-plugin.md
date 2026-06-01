# visibility plugin reference Audit Notes

Source reference: `<downloads>\visibility-plugin-reference.zip`

## Scheduler Calls Covered By The Bridge

These are the obvious legacy scheduler call sites found in `visibility plugin reference-master/src/main/java`:

| File | Pattern | Bridge result |
| --- | --- | --- |
| `visibility plugin reference.java` | `getServer().getScheduler().cancelTasks(this)` | synthetic/global/async tasks cancelled for plugin |
| `features/Feature.java` | `runTaskLater(plugin, runnable, 1)` | global delayed task |
| `features/NightVision.java` | `scheduleSyncRepeatingTask(plugin, this, ...)` | global repeating task |
| `features/NoPush.java` | `BukkitRunnable#runTaskLater` | global delayed task |
| `features/SilentOpenChest.java` | `BukkitRunnable#runTaskLater` | global delayed task |
| `hooks/EssentialsHook.java` | `BukkitRunnable#runTaskTimer`, `runTaskLater` | global repeating/delayed task |
| `listeners/JoinListener.java` | `BukkitRunnable#runTaskLater` | global delayed task |
| `net/UpdateNotifier.java` | `BukkitRunnable#runTask`, `runTaskLater`, `runTaskTimerAsynchronously` | global sync callback, async repeating checker |
| `visibility/ActionBarMgr.java` | `BukkitRunnable#runTaskTimer` | global repeating task |
| `visibility/hiders/PreventionHider.java` | `runTaskTimer`, `cancelTask` | global repeating task and synthetic cancellation |
| `visibility/hiders/PlayerHider.java` | `runTaskLater` | global delayed task |

## Direct Bukkit Actions Still Risky

The bridge can move scheduler calls, but it cannot infer entity ownership for direct API calls. visibility plugin reference still contains direct player/entity operations such as:

- `Player#addPotionEffect` and `Player#removePotionEffect`
- `Player#setGameMode`, `Player#setVelocity`, `Player#teleport`
- `Player#openInventory` and `Player#closeInventory`
- `Player#hidePlayer` and `Player#showPlayer`
- `Bukkit#getOnlinePlayers`
- `Player#getWorld`, `Player#getLocation`, and world entity scans

These are usually safe when they happen inside the player's own event callback on Folia, but can still be unsafe when reached from a global repeating task.

## Expected Bridge Help

The bridge should get visibility plugin reference past most legacy scheduler API failures. It is most likely to help:

- delayed join/recreate messages
- update-check callbacks
- action bar timer scheduling
- visibility prevention timer scheduling

## Remaining Manual Folia Work

For true Folia safety, visibility plugin reference source should eventually use player/entity schedulers for player-specific actions. The bytecode bridge cannot safely choose the correct player scheduler when a task loops over multiple online players.
