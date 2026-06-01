# Solution Model

This document turns live probe evidence into promotion rules. The bridge should
not treat every Folia failure as the same problem. A bytecode path is promoted
only when the original call exposes enough ownership context to select a Folia
scheduler without changing the method's meaning.

## Decision Model

| Evidence bucket | Required bytecode context | Bridge action |
| --- | --- | --- |
| Single entity/player owner | Receiver is `Entity`, `Player`, or `HumanEntity` | Route through `A_ENTITY` using the entity scheduler or a proven async API such as `teleportAsync`. |
| Single location/chunk owner | Call has `Location`, `Block`, `Chunk`, or world+chunk coordinates | Route through `B_REGION_LOCATION` or `C_REGION_BLOCK` with a preemptive owner check: direct if already owned, region scheduler if not owned, and loud direct preservation if the owner probe itself fails. |
| Bounded location scan | Call has `World`, `Location`, and finite scan radii | Route through `G_WORLD_SCAN_SPLIT` with the preemptive bounded loaded-chunk split. Scan candidate chunks through their owning region scheduler, filter to the original bounds, and keep failed chunks loud. |
| Whole-world scan | Call has only `World` or class filters | Route under `G_WORLD_SCAN_SPLIT` with the experimental loaded-chunk split. Proven world-scan shapes now go preemptive on Folia: per-chunk region reads are used first, then merged and filtered. |
| Unowned UI/model | Call creates or mutates Bukkit UI/model state without a player/entity owner | Route only through an explicit `D_PLAYER_UI` shim/model when the bytecode shape is known. Otherwise keep diagnostics loud until a packet/model route is proven. |
| Player-only probe | Startup has no real `Player` receiver | Block with a clear probe line; run first-join or command-triggered probes for player evidence. |

## Bytecode Shape Notes

### Java Method References

Route family: depends on the referenced method.

Solution path: rewrite only known unsafe method handles.

An expression such as `world::getEntities` may compile to an `invokedynamic`
bootstrap handle instead of a normal `invokeinterface World#getEntities`
instruction. The direct-call transformer now rewrites matching bootstrap handles
for known owner/name/descriptor pairs and logs `[FBB guard-path]
action=rewritten` with the owner, method name, descriptor, route family, and
guard. Unsupported method-reference shapes should stay loud as `action=missed`
until a specific bridge method exists. The probe still uses explicit lambdas for
startup unsafe calls so the normal direct-invoke path remains covered too.

## Current Remaining Evidence

### `World#getNearbyEntities(Location,double,double,double)`

Route family: `G_WORLD_SCAN_SPLIT`

Solution path: bounded location scan.

The bytecode has a `Location`, but the AABB may include entities outside the
center region. This remains `G_WORLD_SCAN_SPLIT`, not a simple
`B_REGION_LOCATION` read. On Folia the bridge now logs
`policy=preemptive-safe`, enumerates loaded chunks, schedules candidate
chunk reads on the owning region scheduler, filters entities back to the exact
box, and logs `fallback=preemptive-bounded-split-scan` with `chunks`,
`candidateChunks`, and `resultSize`.

### `World#getEntitiesByClass(...)`

Route family: `G_WORLD_SCAN_SPLIT`

Solution path: experimental loaded-chunk split.

The bytecode has a `World` and class filters, but no player, entity, location,
block, chunk, or bounding box owner. A bytecode-only scheduler hop would still
guess one region and return incomplete data, so the bridge now decomposes this
shape into `World#getLoadedChunks` plus `Chunk#getEntities` reads on each chunk's
owning region scheduler. The typed variants filter the merged entity list after
the split. After live startup evidence proved the direct-first call only raised
a Folia guard before this same split, these world-scan entrypoints now use
`policy=preemptive-safe` on Folia. Failures remain visible as
`[FBB unsafe-failure]` and the logs include `next=split-scan-by-loaded-chunks`,
`fallback=preemptive-split-scan`, `fallback=split-scan-region-scheduler`, and
`fallback=split-scan-complete` when that path runs.

### `ScoreboardManager#getNewScoreboard()`

Route family: `D_PLAYER_UI`

Solution path: detached scoreboard model, not a scheduler hop.

Folia currently throws `UnsupportedOperationException` at the Bukkit scoreboard
factory itself. This is not a wrong-thread problem, so global/entity/region
scheduler retries would only make the logs quieter. The bridge now rewrites the
known `ScoreboardManager#getNewScoreboard()` bytecode shape to a detached
`D_PLAYER_UI` model and logs `policy=shim-model result=detached-scoreboard`.
Team create/mutate calls on that detached model are modeled and logged. Applying
the model to a player remains `action=deferred-apply` because
`Player#setScoreboard` is still hard-unsupported by Folia's Bukkit scoreboard
path; a later packet/player-owned apply layer is needed before this becomes a
full visible scoreboard implementation.

### First-Join Probe Count Is Zero

Route family: not a bridge failure.

Startup probes cannot exercise player/entity/UI calls that need a real online
player receiver. The first-join matrix is still the right evidence path; it
will run when a player joins. Startup logs should continue to show
`blockedBy=startup-no-player` for those paths.

## Promotion Checklist

Before promoting a diagnostic route to a rewrite:

1. Confirm the log has owner, method name, descriptor, plugin jar/class, route
   family, and failing guard frame.
2. Identify the scheduler owner from bytecode, not from plugin name or command.
3. Preserve the original failure evidence with `[FBB unsafe-call]` before any
   fallback retry.
4. Keep `[FBB unsafe-failure]` loud when the fallback cannot preserve behavior.
5. Add smoke coverage for the owner/name/descriptor and emitted route label.
6. Update `TRANSFORM_MATRIX.md` and `DIRECT_UNSAFE_TRACE_MATRIX.md`.
