# Probe Audit

This note records why `FBBProbe` contains the method families it does. The probe
is evidence tooling: it should trigger reusable Bukkit API shapes, not encode
support for one plugin name or one command.

## Inputs

Audited references:

- `dynmap-3.0.zip`
- `visibility-plugin-reference.zip`
- `Essentials-2.x.zip`
- `world-editing-plugin-reference.zip`
- live `UltimateHomes-3.1.jar`

The source zips were extracted under `target/audit-inputs-tar/` for local grep
only. That folder is not part of the runtime bridge.

## External Adapter Reference

External Folia adapter examples were useful as behavior maps, not as
dependencies. They show the same routing ideas we are using as route families:

- global work goes to the global scheduler
- async work goes to the async scheduler
- location work goes to the region scheduler
- entity/player work goes to the entity scheduler
- teleport uses `Entity#teleportAsync(...)`

The bridge should keep using direct bytecode evidence from target plugins before
adding a rewrite. Adapter examples can suggest the likely Folia-safe direction;
plugin bytecode tells us the owner/name/descriptor shape.

The probe now follows that same behavior-map idea with context runs:

- `current` proves the call from the command/player's current region context
- `entity` proves the call after scheduling on the player's entity scheduler
- `async` proves whether a call fails from Folia async scheduler execution
- `global` proves whether a call belongs on global server state
- `region` proves the player's current location region route
- `foreign-region` intentionally runs from a different region to expose
  ownership mistakes

`FBBProbe` is the transformed target. `FBBProbeControl` is the raw control in an
ignored package. The direct call bodies are intentionally mirrored so the only
major difference is whether FoliaBytecodeBridge rewrote the bytecode.

## Evidence Clusters

| Route | Seen in references | Probe coverage |
| --- | --- | --- |
| `S_GLOBAL` / `S_ASYNC` | visibility plugin reference `BukkitRunnable`, Essentials scheduler wrappers, dynmap sync delayed/repeating tasks, world-editing reference task manager | Main smoke fixture already covers scheduler overloads; probe focuses on direct unsafe API families. |
| `A_ENTITY` | UltimateHomes and world-editing reference direct `Player#teleport(Location)`, adapter-style `teleportAsync`, visibility plugin reference velocity/game mode/potion calls, Essentials projectile/entity commands | `Player#getLocation`, `Player#getWorld`, direct teleport overloads, velocity, game mode, potion add/remove, audio. |
| `B_REGION_LOCATION` | dynmap block/chunk reads, Essentials drops/explosions/lightning/spawn/tree, world-editing reference world/drop/tree access | world block/chunk lookup, loaded chunks, lightning, zero-power explosions, drops, spawn, tree generation. |
| `C_REGION_BLOCK` | Essentials sign/block commands, world-editing reference block access, dynmap block reads | block location, same-material block set, block-owned chunk lookup. |
| `D_PLAYER_UI` | visibility plugin reference silent chest, Essentials disposal/enderchest/invsee/sign inventories | open/close inventory. |
| `F_PLAYER_VISIBILITY` | visibility plugin reference hide/show utility, Essentials vanish interactions | plugin-aware and legacy hide/show overloads. |
| `G_WORLD_SCAN_SPLIT` | dynmap and world-editing reference world/entity scans, Essentials `/gc` and remove scans | player nearby scan, world nearby scan, world entity scans, chunk entity scan. |

## Promotion Rule

The probe should not make every failure disappear. A method family can move from
diagnostic to a real bridge rewrite only when all of these are true:

- the bytecode owner/name/descriptor is known
- the route family has enough runtime context for Folia ownership
- the replacement can preserve the original return shape without blocking a
  region thread, or the compatibility tradeoff is documented
- smoke/probe logs prove the new path emits useful `[FBB ...]` evidence

Teleport passed this rule because direct sync teleport has a clear Folia
replacement: `teleportAsync`. Broad world scans and chunk/entity scans usually
do not pass yet because they need split-by-region behavior, not a single global
fallback.
