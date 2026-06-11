# Architecture Notes

FoliaBytecodeBridge has two separate responsibilities:

- `FoliaBytecodeBridgeAgent` installs a Java instrumentation transformer and rewrites selected bytecode call sites.
- `SchedulerBridge` is the runtime target for those rewritten calls.
- `UnsafeCallBridge` is a diagnostic-only target for selected direct Bukkit calls.
- `RouteFamily` is the central architecture map for every emitted `route=<...>` label.

The plugin class only reports whether the agent is installed. It is not responsible for transforming classes. The intended startup path is always `-javaagent:plugins/FoliaBytecodeBridge.jar`.

## Load Order

The agent must start before Bukkit plugins load. If it starts after a target plugin has already loaded, that plugin's scheduler call sites may already be missed.

The jar is also a Bukkit plugin so the server can load `plugin.yml`, expose logs, and keep the bridge classes available in the plugin environment.

## Runtime Helper Visibility

Rewritten plugin bytecode calls FBB runtime helpers such as
`UnsafeCallBridge`. Spigot-style plugin classloaders can normally see those
classes through the plugin environment, but Paper's modern plugin loader can
isolate plugin classes behind a `PaperPluginClassLoader` and a separate
library loader.

When a transform is applied, `BridgeRuntimeVisibility` checks whether the target
plugin loader can resolve the bridge helper. If not, and the loader is Paper's
modern plugin loader, FBB adds its own jar to that plugin library loader. This
is infrastructure for rewritten callsites, not a route-family rewrite and not a
plugin-specific patch.

The adapter emits `[FBB helper-visibility]` diagnostics so a failed visibility
path is visible before it turns into `NoClassDefFoundError` inside a rewritten
plugin class.

## Compatibility Context

Some compatibility problems are not a single unsafe Bukkit method. Legacy code
may enter a shared synchronous event chain or an unknown internal path before a
known route family appears. `CompatibilityContext` records that boundary without
pretending it is a Folia owner.

`CompatibilityLane` is the first serialized lane for compatibility-sensitive
unknown behavior. It is intentionally not a Folia owner thread. Code inside the
lane may preserve ordering for a modeled legacy path, but Bukkit/world/entity
access still has to exit through a known route family before it is considered a
Folia-safe operation.

`SyntheticEventPathBridge` is the first clean hook for future event-path
transformers. It enters the compatibility lane, opens a compatibility context,
preserves evidence about listener order and event state, and lets normal
bytecode routes log `[FBB promotion-candidate]` when a known API edge appears
inside the modeled path.

`SyntheticEventDispatchBridge` is the first real entry point into that model.
The raw transformer rewrites the exact
`PluginManager#callEvent(Event)` bytecode shape through `UnsafeCallBridge`.
Built-in Bukkit/Paper events pass through to the original plugin manager.
Custom sync plugin events are dispatched through the compatibility lane so the
shared listener chain can preserve ordering while known API calls inside
listeners still exit through normal route families.

Unknown does not route to global by default. `S_GLOBAL` is for server/global
ownership. Unknown paths stay in compatibility evidence until an owner can be
proven.

## Transform Scope

The transformer rewrites known Bukkit scheduler API calls:

- `BukkitScheduler` sync, async, delayed, repeating, cancellation, and `callSyncMethod`
- `BukkitRunnable` sync, async, delayed, repeating, cancellation, and cancellation checks

It intentionally does not safety-translate:

- arbitrary executor or thread calls
- plugin-specific scheduler wrappers unless they eventually call Bukkit scheduler methods

Selected direct `Player`, `Entity`, `World`, `Block`, scoreboard, and command
calls now have exact route rules or diagnostic wrappers. Some are real rewrites,
some are shim/model routes, and some remain trace-only. `RouteRuleRegistry` is
the source of truth for exact owner/name/descriptor routes; intentionally
ownerless helper shapes stay documented as generic model policies.

Runtime model evidence is also mapped back to `RouteRuleRegistry` when the
short API label matches a known owner/method/signature. This keeps live
`[FBB model]` lines aligned with the same architecture map as bytecode
transformers without adding duplicate route definitions.

## Runtime Policy

On Folia:

- Legacy sync tasks are sent to the global region scheduler.
- Legacy async tasks are sent to the async scheduler.
- Bukkit tick delays are preserved for global tasks.
- Async delays are converted from ticks to milliseconds.

On non-Folia servers, the bridge calls the original Bukkit scheduler method and tries to be invisible.

## Route-Family Architecture Map

All route labels come from `RouteFamily`:

| Route | Translation category |
| --- | --- |
| `S_GLOBAL` | scheduler/global fallback |
| `S_ASYNC` | async scheduler |
| `A_ENTITY` | entity/player calls |
| `B_REGION_LOCATION` | world calls with location |
| `C_REGION_BLOCK` | block-owned calls |
| `D_PLAYER_UI` | inventory/menu/player UI |
| `F_PLAYER_VISIBILITY` | hide/show player |
| `G_WORLD_SCAN_SPLIT` | world/entity scans |

When a new bytecode shape is discovered, first map it to one of these families.
Only add a new family when the smoke evidence shows an operation cannot be
honestly grouped into the existing architecture.

## NMS Compatibility Families

Server-internal/NMS failures use a parallel map named `NmsCompatFamily`, not
`RouteFamily`. This keeps Bukkit ownership routes separate from binary
server-shape adapters.

| NMS family | Meaning |
| --- | --- |
| `NMS_VERSION_COMPAT` | missing or renamed server-internal fields, methods, or classes |
| `SERVER_TICK_COUNTER` | synthetic `MinecraftServer.currentTick:I` compatibility |
| `NMS_EXECUTOR_CONTEXT` | server/chunk executor paths that may require regionized world data |
| `NMS_CHUNK_ACCESS` | server-internal chunk/world access that needs an owner context |
| `NMS_TICK_STATE` | server-internal tick state assumptions |
| `NMS_UNSUPPORTED` | detected NMS shape without a compatibility contract yet |

The current server-executor shim still uses the global scheduler as a bridge,
but it now logs `NMS_EXECUTOR_CONTEXT` when runtime evidence shows the runnable
expected regionized world/chunk state. That evidence means "derive an owner
context before promoting this route," not "pretend this is just `S_GLOBAL`."

Unknown NMS paths use the NMS compatibility model:

```text
unknown/server-internal NMS shape
-> NmsCompatibilityContext
-> owner-preserving NMS compatibility lane
-> NmsOwnerExtractor scans for Location/Chunk/Entity/ServerLevel+BlockPos clues
-> captured ServerLevel/Bukkit World + two local ints may become a chunk owner
-> owner found: route-exit to the matching Folia owner before running
-> owner missing: stay serialized on the current executor shim and preserve failures
```

The NMS lane is not the same thread as the plugin-event compatibility lane. It
serializes with a lock while preserving the Folia owner thread that the runnable
is already scheduled on. This matters because moving NMS internals to a random
single thread can preserve ordering while still losing Folia's current
regionized world context.

For executor lambdas, `NmsOwnerExtractor` may promote a captured world plus two
integer fields only when those clues live on the same runnable object. This is a
generic bytecode-shape rule for server-internal chunk work, not a plugin-specific
branch. If the extractor sees a world but cannot find coordinates, it leaves the
path as `no-owner-contract` and records the candidate clue trail for the next
pass.

## Task Handles

Folia scheduler methods do not return Bukkit task ids. `BridgeBukkitTask` wraps Folia scheduled task handles and provides synthetic ids starting at `1_000_000`.

This supports common legacy patterns:

- `BukkitTask#getTaskId()`
- `BukkitTask#cancel()`
- `BukkitScheduler#cancelTask(id)`
- `BukkitScheduler#cancelTasks(plugin)`

## Main Risk

The global region scheduler is not equivalent to Paper's old main thread. It is only safe for global-region work. A plugin task that touches a player, entity, chunk, block, or world data from the wrong region can still break on Folia.

This project is a compatibility experiment, not a formal proof of Folia safety.

## Extension Points

Add new rewrites in `FoliaBytecodeBridgeAgent` only when the bytecode shape is
stable and the runtime behavior can be represented by a bridge helper such as
`SchedulerBridge`, `UnsafeCallBridge`, `ServerExecutorBridge`, or
`SyntheticEventDispatchBridge`.

Keep runtime behavior in bridge helpers. Do not put scheduling policy in the
agent; the agent should only substitute calls.

When adding a rewrite:

1. Add the `MemberSubstitution` entry.
2. Add the exact replacement method signature to the appropriate bridge helper.
3. Document the call in `README.md`.
4. Map diagnostics through `RouteFamily` and add a smoke assertion for the emitted route label.
5. Add a note here if the rewrite has unusual behavior.
