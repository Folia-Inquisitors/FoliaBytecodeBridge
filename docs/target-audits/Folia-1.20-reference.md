# Folia 1.20 Reference Notes

Source reviewed from `Folia-ver-1.20.zip`.

This zip is a Folia/DirtyFolia patch tree, not a plugin target. It is useful as historical scheduler reference for the bridge route labels.

## Relevant Scheduler Evidence

| Reference area | Observed shape | Bridge route impact |
| --- | --- | --- |
| `patches/api/0002-Region-scheduler-API.patch` | Folia separates legacy Bukkit scheduler usage from `RegionScheduler`, `AsyncScheduler`, `EntityScheduler`, and `GlobalRegionScheduler` | Confirms the bridge should not pretend one scheduler fits all plugin work |
| `patches/server/0003-Threaded-Regions.patch` | `FoliaRegionScheduler` methods use `Plugin`, `World`, `chunkX`, `chunkZ`, and task/delay/period data | Confirms `B_REGION_LOCATION` and `C_REGION_BLOCK` need world/chunk/block context |
| `patches/server/0040-Fix-BukkitScheduler-runTaskTimer.patch` | Legacy repeating tasks are mapped onto async or global repeating scheduler methods depending on sync/async status | Confirms `S_GLOBAL` and `S_ASYNC` route labels for legacy scheduler calls |

## Bridge Takeaway

The old Folia tree supports the same conclusion as the newer local Folia reference:

- no-context sync scheduler calls can only be approximated with the global scheduler
- async scheduler calls can use the async scheduler
- player/entity work needs an entity route
- world/block/location work needs a region route

That is why the bridge auto-translates legacy scheduler bytecode but only logs direct unsafe player/entity/world/block calls for now.
