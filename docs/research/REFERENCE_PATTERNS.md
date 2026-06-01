# Reference Patterns

This note preserves reusable bytecode and runtime lessons learned from real
plugin references without naming the source plugins. It is intentionally about
patterns, not plugin support. When a new failure appears, use this file to ask:

- what bytecode owner/name/descriptor shape is present?
- what object owns the state being touched?
- does the bridge have enough context to route safely?
- does the old API expect a synchronous return value?
- should this become a rewrite, a model/shim, or diagnostic-only evidence?

## Development Loop

Pattern:
An unsafe call appears in live logs, probes, smoke tests, or bytecode inventory.

Why it matters:
Folia compatibility is about ownership, not simply async versus sync. A method
is only safe when it runs on the scheduler that owns the state it touches.

Route family:
Use the official route families: `S_GLOBAL`, `S_ASYNC`, `A_ENTITY`,
`B_REGION_LOCATION`, `C_REGION_BLOCK`, `D_PLAYER_UI`,
`F_PLAYER_VISIBILITY`, and `G_WORLD_SCAN_SPLIT`.

Status:
Unknown shapes start as evidence. Proven shapes can become route rules or
runtime models. Some shapes must remain diagnostic-only.

Evidence:
Look for `[FBB bytecode-path]`, `[FBB teleport-path]`, `[FBB guard-path]`,
`[FBB unsafe-call]`, `[FBB unsafe-failure]`, `[FBB task-failure]`, and
`[FBB model]`.

Next:
Promote evidence only when the owner context and return behavior are understood.

## Anonymous BukkitRunnable Owner

Pattern:
A plugin creates an anonymous `BukkitRunnable` subclass and calls
`runTask*` on it. JVM bytecode may resolve the call owner as the anonymous class
instead of `org/bukkit/scheduler/BukkitRunnable`.

Why it matters:
A transformer that only matches the Bukkit API owner can miss these calls.

Route family:
`S_GLOBAL` for legacy sync scheduler calls, `S_ASYNC` for legacy async calls.

Status:
Rewritten by the raw scheduler fallback and anonymous override bridge.

Evidence:
`[FBB bytecode-path] ... source=<anonymous-owner>#runTaskAsynchronously(...) route=S_ASYNC ...`

Next:
If a new anonymous owner is missed, inspect whether it directly extends
`BukkitRunnable` before widening the override rule.

## Scheduler Wrapper Methods

Pattern:
A plugin exposes helper methods such as `runTaskAsynchronously(Runnable)` or
`scheduleSyncDelayedTask(Runnable)` and calls Bukkit's scheduler internally.

Why it matters:
The helper method name can look like a scheduler method, but the receiver shape
is not `BukkitScheduler` or `BukkitRunnable`. Rewriting the helper call itself
can misclassify the route.

Route family:
The internal Bukkit scheduler call determines the route: usually `S_GLOBAL` or
`S_ASYNC`.

Status:
Do not rewrite by helper method name alone. Rewrite the internal exact
`BukkitScheduler` bytecode call.

Evidence:
Correct evidence should show `source=org.bukkit.scheduler.BukkitScheduler#...`,
not the plugin helper method as the scheduler source.

Next:
When a helper-like method fails, inspect the bytecode receiver and descriptor
before adding a route.

## Legacy Int Scheduler APIs

Pattern:
Old scheduler APIs return `int` task ids, such as
`scheduleAsyncRepeatingTask(...)`.

Why it matters:
Folia scheduler APIs return scheduled task handles, not legacy integer ids. The
bridge must preserve the old return shape without pretending Folia has the same
task registry.

Route family:
`S_GLOBAL` or `S_ASYNC`, depending on the original method.

Status:
Rewritten to object scheduler bridge methods that maintain synthetic task ids.

Evidence:
`[FBB bytecode-path] ... scheduleAsyncRepeatingTask ... route=S_ASYNC ...`

Next:
Any new int-returning scheduler overload needs an explicit bridge method and
cancel/status behavior.

## Direct Teleport Calls

Pattern:
Legacy code calls `Entity#teleport(Location)` or
`Player#teleport(Location, TeleportCause)`.

Why it matters:
On Folia, sync teleport is not valid from normal region threading. The safe
direction is `teleportAsync`, owned by the entity.

Route family:
`A_ENTITY`

Status:
Rewritten by exact Bukkit API owner/name/descriptor shapes.

Evidence:
`[FBB teleport-path] ... name=teleport ... rule=bukkit-api-owner action=rewritten route=A_ENTITY`

Next:
Completion failures must remain visible through unsafe/task failure evidence.

## Static Teleport Helper Shapes

Pattern:
A shaded or helper API exposes a static method named `teleportAsync` with
`(Entity|Player, Location, TeleportCause) -> CompletableFuture`.

Why it matters:
Package names vary, but the descriptor can describe the same reusable ownership
route.

Route family:
`A_ENTITY`

Status:
Generic descriptor-shape rewrite when the signature is known safe. Related
unsupported helper shapes are trace-only.

Evidence:
`[FBB teleport-path] ... rule=generic-shape action=rewritten ...`
or
`[FBB teleport-path] ... rule=generic-helper-shape action=trace-only ...`

Next:
Do not add exact-owner rules unless the descriptor cannot be generalized.

## Player-Owned Calls

Pattern:
Player methods mutate or read player-owned state: game mode, velocity, potion
effects, audio, inventory, scoreboard assignment, and similar calls.

Why it matters:
The player/entity scheduler is the likely owner, but return values and current
thread context still matter.

Route family:
Usually `A_ENTITY`; UI-facing calls usually belong to `D_PLAYER_UI`.

Status:
Some calls are rewritten, some are modeled, and some remain diagnostic.

Evidence:
`[FBB unsafe-call] ... route=A_ENTITY ... next=entity-scheduler-...`

Next:
For new player calls, decide whether the call can be fire-and-forget, must
return a value, or needs a model.

## Block-Owned Calls

Pattern:
Code reads or mutates state through a `Block` receiver.

Why it matters:
The block's location identifies the owning region. These calls should not be
guessed as global just because they happen during an event.

Route family:
`C_REGION_BLOCK`

Status:
Selected high-volume reads and mutations are routed or guarded by block-owned
region evidence.

Evidence:
`[FBB unsafe-call] api=Block#... route=C_REGION_BLOCK ... ownerCheck=...`

Next:
For new block calls, keep the block location in debug evidence and preserve
failures when ownership cannot be proven.

## Location-Owned World Calls

Pattern:
World methods receive a `Location`, `Block`, or chunk coordinate that identifies
a specific region.

Why it matters:
The location is the owner hint. Scheduling globally can hide the real ownership
problem and may still be wrong.

Route family:
`B_REGION_LOCATION`

Status:
Selected world effects, drops, chunk reads, chunk refresh/load paths, and
bounded nearby scans are routed by location or kept as evidence.

Evidence:
`[FBB unsafe-call] api=World#... route=B_REGION_LOCATION ... location=...`

Next:
If a call returns a value, verify whether direct current-region execution is
safe before adding blocking or async aggregation behavior.

## Broad World Scans

Pattern:
Calls such as `World#getEntities`, `World#getEntitiesByClass`, and broad
nearby scans can touch multiple regions.

Why it matters:
There may be no single owning region. A correct route may need split-by-region
or split-by-loaded-chunk aggregation.

Route family:
`G_WORLD_SCAN_SPLIT`

Status:
Some scans have preemptive or fallback split models. Some remain experimental
because synchronous return aggregation can block or deadlock if done carelessly.

Evidence:
`[FBB unsafe-call] ... route=G_WORLD_SCAN_SPLIT ... model=split-by-loaded-chunks`

Next:
Prefer bounded scans with clear location context. For whole-world scans, keep
debug evidence about chunk count, aggregation, timeout, and caller.

## Player UI And Scoreboard Models

Pattern:
Inventory and scoreboard APIs touch player-visible UI state. Detached
scoreboard creation has no obvious player owner yet.

Why it matters:
Some UI calls are player-owned. Detached scoreboard objects may need a bridge
model until they are attached to a player.

Route family:
`D_PLAYER_UI`

Status:
Selected scoreboard/team/objective/score paths are modeled. Player-visible
application is still conservative.

Evidence:
`[FBB unsafe-call] api=ScoreboardManager#getNewScoreboard route=D_PLAYER_UI ... policy=shim-model`

Next:
Do not map unowned scoreboard creation to `S_GLOBAL` without a model. Keep
native scoreboard owners loud when the bridge does not own the object.

## Visibility Calls

Pattern:
Player visibility helpers call hide/show overloads with or without a plugin
argument.

Why it matters:
Visibility is player-related, but may affect lists, UI, packets, and other
players. It should stay separate from generic entity mutation.

Route family:
`F_PLAYER_VISIBILITY`

Status:
Tracked as its own route family so failures do not get mixed into generic
entity calls.

Evidence:
`[FBB unsafe-call] ... route=F_PLAYER_VISIBILITY ...`

Next:
When adding rules, preserve whether the API is plugin-aware or legacy.

## Command Dispatch

Pattern:
Code calls `Bukkit#dispatchCommand(CommandSender, String)` or
`Server#dispatchCommand(CommandSender, String)`.

Why it matters:
The sender determines the best owner. Entity senders can use entity scheduling;
console-like senders use global scheduling.

Route family:
`S_GLOBAL` with entity-sender specialization.

Status:
Rewritten by exact Bukkit API owner/name/descriptor.

Evidence:
`[FBB guard-path] ... name=dispatchCommand ... route=S_GLOBAL action=rewritten`

Next:
The old boolean return is accepted scheduling, not command completion. Task
failures must remain visible.

## Server Executor Shapes

Pattern:
Code submits work to legacy main-thread executor shapes such as Paper main
executor fields or NMS server `execute(Runnable)`.

Why it matters:
Folia does not have one safe global main thread. These shapes usually represent
legacy global scheduler assumptions.

Route family:
`S_GLOBAL`

Status:
Known exact executor shapes are rewritten to the global scheduler bridge.

Evidence:
`[FBB guard-path] ... route=S_GLOBAL guard=... action=rewritten`

Next:
New executor owners need exact bytecode evidence and a clear reason they are
global server work rather than region/entity work.

## Legacy Main-Thread Predicate

Pattern:
Some code asks a static `isMainThread()` predicate before deciding whether work
can run immediately.

Why it matters:
Folia has no single main thread. Returning `true` too broadly can hide real
ownership bugs.

Route family:
`S_GLOBAL`

Status:
Only exact-owner exceptions should be rewritten. Generic `isMainThread()` is
trace-only until the protected state is understood.

Evidence:
`[FBB legacy-main-thread] ... rule=exact-owner-method-body action=rewritten`

Next:
Do not widen this to every method named `isMainThread`. Document why each exact
owner cannot be generalized.

## NMS Version Compatibility

Pattern:
A plugin expects a server field or method that does not exist in the running
Folia jar.

Why it matters:
This is not a Bukkit ownership route. It is a binary compatibility problem.

Route family:
None. Use `NMS_VERSION_COMPAT`.

Status:
Only proven server-internal shape adapters belong here. They must not be logged
as ordinary `[FBB unsafe-call]` ownership routes.

Evidence:
`[FBB compat] category=NMS_VERSION_COMPAT ...`
`[FBB nms-compat] ... result=patched ...`

Next:
Inspect the running server member map before adding any synthetic field or
method. Candidate members are research pointers, not automatic adapters.

## Exact-Owner Exceptions

Pattern:
A generic descriptor rule would be unsafe, but one exact owner has enough
evidence to rewrite.

Why it matters:
Exact owners can make the bridge look plugin-specific. They are acceptable only
when documented as bytecode-shape exceptions.

Route family:
Whichever ownership route the exact owner proves, often `S_GLOBAL`.

Status:
Allowed sparingly.

Evidence:
The log should say `rule=exact-owner...` and include owner/name/descriptor.

Next:
Each exact-owner exception must explain why it is not safely generalizable.

## Hard Limit: Shared Mutable State

Pattern:
A plugin uses normal collections, caches, maps, fields, or mutable models from
multiple Folia region/entity callbacks.

Why it matters:
Bytecode scheduler rerouting cannot automatically make arbitrary plugin state
thread-safe. A plugin may still crash after weeks if unlucky interleavings hit a
plain collection or an unsynchronized invariant.

Route family:
None by default. This is plugin architecture, not one Bukkit API route.

Status:
Not automatically fixed by FBB.

Evidence:
Look for `ConcurrentModificationException`, data races, plugin task failures,
or inconsistent state after routed calls.

Next:
This needs source-level synchronization, owner-bound state models, immutable
snapshots, message passing, or a much deeper plugin-specific compatibility
layer. Do not present `metadataOverlay=all` or scheduler rewrites as proof of
thread safety.

