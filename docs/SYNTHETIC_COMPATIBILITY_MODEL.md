# Synthetic Compatibility Model

FoliaBytecodeBridge is not only a scheduler rewrite table. The route families
are the proven exits into Folia ownership, but legacy plugins can also fail
before a known Bukkit/Paper API edge is visible. The synthetic compatibility
model is the research layer for those paths.

## Layer Roles

```text
bytecode route family
  detects known Bukkit/Paper API shapes and exits to a Folia owner path

single-thread compatibility lane
  serialized execution tool for unknown or unproven legacy behavior;
  not a Folia entity, region, global, or async owner thread

synthetic compatibility model
  models Paper-like contracts that Folia does not naturally provide

shared event path
  synchronous event chain where multiple listeners share event state, ordering,
  cancellation, and mutation effects
```

Synthetic does not mean "safe by itself." Serialization is one possible tool
inside the model when unknown behavior or shared event ordering cannot be
proven safe. Any Bukkit/world/entity access that appears inside the lane must
still exit through a known route family before it is treated as Folia-safe.

## Intended Flow

```text
legacy or unknown plugin path
  -> compatibility context marks the path as unproven
  -> single-thread lane may protect internal plugin state
  -> shared event path may be wrapped by a synthetic model
  -> known route family appears
  -> bridge exits to entity, region, global, async, split-scan, or UI model
  -> debug evidence decides whether that path can be promoted later
```

Unknown behavior must not default to `S_GLOBAL`. Global is only for work whose
owner is actually server/global state. Unknown behavior stays in the
compatibility context, is traced, or is refused until a better owner is proven.

## Shared Event Path

Shared synchronous events are compatibility-sensitive because plugins can
communicate through one event object without calling each other directly:

```text
listener A reads event state
listener B mutates or cancels the event
listener C observes the final cancellation/result
server applies the final event result
```

The first rewrite target is the exact bytecode shape:

```text
org.bukkit.plugin.PluginManager#callEvent(Lorg/bukkit/event/Event;)V
```

`SyntheticEventDispatchBridge` keeps built-in Bukkit/Paper events as
pass-through because those event contracts can include server-side behavior
outside the visible listener list. Custom non-async plugin events enter the
single-thread compatibility lane and dispatch the registered listener list in
order. That is the first concrete version of the shared-event model.

The implementation emits:

- `[FBB compatibility-context]`
- `[FBB compatibility-lane]`
- `[FBB event-listener]`
- `[FBB synthetic-event-dispatch]`
- `[FBB promotion-candidate]`
- `[FBB synthetic-event-probe]`

`[FBB compatibility-lane]` reports `submit`, `start`, `finish`, `inline`, and
`failure` actions with a sequence number. The sequence proves ordering inside
the serialized lane. It is deliberately labeled as a compatibility model, not a
Folia owner route.

## Listener Route Exits

The compatibility lane is not an entity, region, global, async, or UI owner
thread. If listener code touches Folia-owned state while the synthetic event
model is dispatching it, the failure is preserved and classified:

```text
[FBB synthetic-event-dispatch] action=listener-failure route=A_ENTITY
```

The route label is diagnostic evidence. It means the listener failure points to
that owner family as the next route exit to investigate. It does not mean the
bridge silently rerouted the whole listener, and it does not mean unknown
listener code defaults to global.

Common listener failure classifications:

- `A_ENTITY`: entity/player state was touched from the compatibility lane.
- `B_REGION_LOCATION`: world or chunk state needs a location/region owner.
- `C_REGION_BLOCK`: block-owned state needs the block region owner.
- `G_WORLD_SCAN_SPLIT`: scan-style access needs split/aggregate handling.
- `D_PLAYER_UI`: scoreboard, inventory, or player UI needs a model/owner path.
- `UNKNOWN`: the failure did not contain enough evidence for a route family.

## Entity-Owned Custom Events

Some custom events expose an obvious entity owner, usually through a zero-arg
getter such as `getEntity()` or `getPlayer()`. Those events can leave the
unknown compatibility lane through the `A_ENTITY` family before the listener
chain runs.

`SyntheticEventOwnerExtractor` is intentionally conservative. It only accepts
simple zero-argument getters whose return value is assignable to
`org.bukkit.entity.Entity`. If no clear owner is found, the event remains in the
serialized synthetic lane and the bridge keeps producing diagnostic evidence.
This avoids treating an unknown shared event as global just because it was
custom.

Entity-owned dispatch emits:

```text
[FBB synthetic-event-route-exit] action=current-owner route=A_ENTITY
```

or, when the current thread does not own the entity:

```text
[FBB synthetic-event-route-exit] action=schedule route=A_ENTITY
```

`current-owner` means `Bukkit.isOwnedByCurrentRegion(entity)` already proved the
current thread owns the entity, so the listener chain is dispatched directly in
that owner context. `schedule` means the chain is sent to the entity scheduler
and waited for with a bounded timeout so the old synchronous event expectation
can still be observed. If the scheduled listener chain fails or times out, the
bridge logs `[FBB synthetic-event-route-exit-failure]` instead of hiding the
failure.

This is still not a blanket listener rewrite. It is a narrow route exit for
custom events with an obvious entity owner. Known bytecode calls inside those
listeners can still use the normal route families, and unclear behavior remains
visible in the synthetic model.

## Block And Location-Owned Custom Events

Custom block/location events follow the same rule: only obvious owners become
route exits. `SyntheticEventOwnerExtractor` accepts simple zero-argument block
getters such as `getBlock()` and collection getters such as `getBlocks()` when
the collection is non-empty and all blocks share the same chunk anchor. That
keeps multi-region block collections in the synthetic lane until a split/event
model is proven.

Block-owned dispatch emits:

```text
[FBB synthetic-event-route-exit] action=current-owner route=C_REGION_BLOCK
```

or:

```text
[FBB synthetic-event-route-exit] action=schedule route=C_REGION_BLOCK
```

Location-owned dispatch emits the same evidence with:

```text
route=B_REGION_LOCATION
```

`current-owner` means the current Folia thread already owns the block/location
region. `schedule` means the listener chain is sent to Folia's region scheduler
and waited for with a bounded timeout to preserve the old synchronous event
contract. Failures are logged as route-exit evidence, not hidden.

This turns the synthetic model into an ownership map:

```text
custom event with getEntity()/getPlayer()
  -> A_ENTITY route exit

custom event with getBlock()/getBlocks()
  -> C_REGION_BLOCK route exit when the block owner is clear

custom event with getLocation()
  -> B_REGION_LOCATION route exit

unknown or multi-region event shape
  -> remain serialized/modelled, keep diagnostics
```

## Synthetic Multi-Region Detection

Phase 1 of the multi-region model is detection only. When a custom event
exposes a block collection that crosses more than one chunk/owner anchor, the
bridge records the owner set and keeps the event in the serialized synthetic
lane:

```text
[FBB synthetic-multi-region] phase=detect route=C_REGION_BLOCK
result=observed-not-promoted
```

This does not split the event, run a multi-region mutation, or freeze regions.
It answers one narrow question: "did this synthetic path expose more than one
owner?" The current owner key is chunk-based evidence, not a final Folia region
lock model. It is good enough for phase 1 because it tells the next pass where
`G_WORLD_SCAN_SPLIT`, a synthetic event model, or a future two-phase mutation
model may be needed.

Current phase-1 behavior:

```text
multi-owner read-like evidence
  -> log owner count and owner set
  -> stay serialized

multi-owner mutation evidence
  -> log as unknown read-or-write evidence
  -> stay serialized
  -> do not promote until the exact mutation shape is proven
```

## Phase 2 Read-Only Split/Aggregate

Phase 2 is deliberately narrower than "run the event once per region." Replaying
a listener chain across multiple regions could duplicate arbitrary listener
side effects, so the bridge only performs an owner read pass when the event
shape explicitly says it is read-only through a zero-argument boolean method
such as `isReadOnly()`.

The flow is:

```text
multi-owner block collection
  -> event exposes isReadOnly() == true
  -> schedule one block-owned read snapshot per owner anchor
  -> log scheduled split-read evidence
  -> aggregate completed owner reads from a completion callback
  -> keep normal listener dispatch serialized
```

Expected evidence:

```text
[FBB synthetic-multi-region] phase=split-read route=C_REGION_BLOCK operation=read-only readOnly=true result=aggregated
```

Live Folia scheduler contexts do not block while waiting for other owner
regions. The live path first emits `result=scheduled mode=nonblocking`, then a
later completion callback emits `result=aggregated` or a preserved failure. The
smoke harness uses `mode=smoke-inline` so the deterministic smoke assertion can
still prove the aggregate path without needing a live scheduler.

Unknown or mutation-shaped collections still use:

```text
operation=read-or-write-unknown readOnly=false result=observed-not-promoted
```

This phase is useful because it proves the owner set can be decomposed and read
without treating the entire listener path as concurrently safe. Later phases
can decide whether a specific listener/model shape is safe to replay, shim, or
promote.

## Phase 3 Mutation Planning

Phase 3 is still not a multi-region write executor. It is a planning and
evidence layer for explicit mutation-shaped synthetic events.

The bridge looks for zero-argument boolean marker methods such as:

```text
isMutation()
isMutationEvent()
hasMutations()
willMutate()
mutatesBlocks()
isBlockMutation()
```

If a multi-owner event does not expose one of those markers, it remains
serialized and the plan log says:

```text
[FBB synthetic-multi-region] phase=plan-mutation result=blocked reason=no-explicit-mutation-intent
```

If the event explicitly says it is a mutation, the bridge records the intended
two-phase shape:

```text
[FBB synthetic-multi-region] phase=plan-mutation result=planned-not-executed phases=prepare,owner-apply,aggregate-verify
```

That evidence means: "we have enough owner and mutation intent to discuss a
future exact model." It does not execute region writes, replay listeners, freeze
regions, or mark the path safe. Mutation kind getters such as
`getMutationKind()`, `getMutationType()`, `getOperation()`, and `getAction()`
are recorded when present so a future pass can decide whether the mutation has
safe prepare/apply/verify semantics.

## Phase 4 Mutation Contract Readiness

Phase 4 asks the next question after Phase 3: does this explicit multi-region
mutation event expose a clean two-phase-style contract?

The bridge looks for zero-argument boolean markers:

```text
supportsPreparePhase()
supportsOwnerApplyPhase()
supportsAggregateVerifyPhase()
```

If mutation intent exists but those contract markers are missing, diagnostics
stay blocked:

```text
[FBB synthetic-multi-region] phase=contract-mutation result=blocked reason=missing-two-phase-contract
```

If all three markers are present, the bridge logs readiness only:

```text
[FBB synthetic-multi-region] phase=contract-mutation result=ready-not-executed contract=prepare,owner-apply,aggregate-verify
```

This phase still does not perform multi-region writes, freeze regions, replay
listener chains, or claim the event is safe. It only separates "explicit
mutation but no contract" from "explicit mutation with a contract shape that a
future exact synthetic model could implement."

## Phase 5A Listener Re-Entry Detection

Unknown listener code can still be unsafe even when no Bukkit route is visible.
For example, a listener may mutate a normal Java collection, cache, cooldown
map, or shared model while the same listener path is entered from another Folia
region thread.

`SyntheticListenerConcurrencyTracker` records active synthetic listener paths by
event class and listener owner. If the same event/listener path is entered again
while the first invocation is still active on another thread, diagnostics emit:

```text
[FBB synthetic-concurrency] phase=5A action=reentered result=compatibility-sensitive
```

The diagnostic includes both sides of the overlap:

```text
activeRoute=UNKNOWN activeOwnerMethod=none activePath=diagnostic-probe:startup-probe-phase-5a
currentRoute=UNKNOWN currentOwnerMethod=none currentPath=diagnostic-probe:startup-probe-phase-5a
```

This is detection only. It does not prove the listener is unsafe, does not
promote a route, and does not hide listener failures. It gives us a precise
signal that the path should remain compatibility-sensitive while known Bukkit
API calls inside that listener can still exit to `A_ENTITY`, `C_REGION_BLOCK`,
`B_REGION_LOCATION`, or another proven owner route.

`SyntheticEventPathBridge#probeListenerReentry(...)` exists only as a probe and
smoke hook. It deliberately overlaps one synthetic event/listener key without
touching real Bukkit entity, world, or block state, so Phase 5A can be tested
without weakening the serialized compatibility lane.

The live probe also includes two removable stress markers:

```text
FBB_REMOVE_ME_UNKNOWN_OVERLAP_PROBE_V1
FBB_REMOVE_ME_INTERNAL_STATE_PROBE_V1
```

The first marker fires two unknown shared event paths at the same time and
expects `maxActiveListeners=1`. The second models ordinary unknown plugin
internals, such as collection/cache/cooldown mutation across the listener
chain, and expects `maxActiveInternalPaths=1`. Passing those probes means the
synthetic lane preserved serialized execution for those shapes; it does not
prove arbitrary plugin internals are permanently safe or promote the listener
body to concurrent execution.

## Synthetic Diagnostic Markers

Synthetic diagnostics include stable `marker=` tokens so large debug logs can be
searched by outcome instead of by prose:

```text
FBB_SYNTHETIC_OWNER_MISS_SERIALIZED_V1
FBB_SYNTHETIC_MULTI_REGION_DETECTED_V1
FBB_SYNTHETIC_READ_SPLIT_V1
FBB_SYNTHETIC_READ_SPLIT_FAILED_V1
FBB_SYNTHETIC_MUTATION_PLAN_V1
FBB_SYNTHETIC_MUTATION_CONTRACT_V1
FBB_SYNTHETIC_MUTATION_EXECUTOR_DISABLED_V1
FBB_SYNTHETIC_MUTATION_MISSING_HOOKS_V1
FBB_SYNTHETIC_MUTATION_NO_OWNER_ANCHORS_V1
FBB_SYNTHETIC_MUTATION_PREPARE_BLOCKED_V1
FBB_SYNTHETIC_MUTATION_OWNER_APPLY_SCHEDULED_V1
FBB_SYNTHETIC_MUTATION_COMPLETED_VERIFIED_V1
FBB_SYNTHETIC_MUTATION_VERIFY_BLOCKED_V1
FBB_SYNTHETIC_MUTATION_EXECUTOR_FAILED_V1
```

These markers are identifiers for evidence paths, not safety claims. For
example, `FBB_SYNTHETIC_MUTATION_COMPLETED_VERIFIED_V1` means the exact
prepare/apply/verify contract completed for the observed event shape; it does
not mean arbitrary multi-region mutations are globally safe.

## Phase 5B Guarded Multi-Region Mutation Executor

Phase 5B is the first executor for synthetic multi-region mutation events, but
it is deliberately guarded and exact-contract only. It does not freeze the
world, replay arbitrary listener chains, or treat unknown mutation events as
safe.

The executor can run only when all earlier evidence is present:

```text
multi-owner block collection
  -> explicit mutation intent
  -> prepare/owner-apply/aggregate-verify contract markers
  -> exact prepare/apply/verify hook methods
  -> syntheticMutationExecutor=true
```

Accepted hook shapes are intentionally small:

```text
prepareMutation() -> void|boolean|Boolean
applyOwnerMutation(Block) -> void|boolean|Boolean
verifyAggregateMutation(int scheduledOwners, int completedOwners) -> void|boolean|Boolean
```

The default config keeps this disabled:

```properties
syntheticMutationExecutor=false
```

When disabled, the bridge emits:

```text
[FBB synthetic-multi-region] phase=execute-mutation result=blocked reason=executor-disabled action=stay-serialized
```

When enabled on a live server, the executor prepares once, schedules one
owner-apply task for each block owner through Folia's region scheduler, and
verifies the aggregate result from the completion callback:

```text
[FBB synthetic-multi-region] phase=execute-mutation result=scheduled action=owner-apply-tasks mode=nonblocking
[FBB synthetic-multi-region] phase=execute-mutation result=completed reason=verified action=two-phase-mutation-executor mode=nonblocking
```

The smoke harness uses `foliabytecodebridge.smokeSyntheticMutationExecutor=true`
to run the same hook contract inline. That gives deterministic local proof of
the phase without needing a live Folia region scheduler:

```text
[FBB synthetic-multi-region] phase=execute-mutation result=completed reason=verified mode=smoke-inline
```

Any missing hook, failed hook, false verify result, missing owner anchor, or
timeout remains visible as `[FBB synthetic-multi-region] phase=execute-mutation`
evidence. This keeps unknown behavior inside the synthetic/serialized model
instead of silently promoting it.

Negative contract probes are part of this phase. A prepare hook returning false
must log:

```text
[FBB synthetic-multi-region] phase=execute-mutation result=blocked reason=prepare-returned-false
```

A verify hook returning false after owner apply must log:

```text
[FBB synthetic-multi-region] phase=execute-mutation result=blocked reason=verify-returned-false
```

These cases are just as important as the successful executor path because they
prove the synthetic model preserves failure evidence instead of making a bad
multi-region contract look safe.

## Delegated Original Event Owners

Some compatibility events are wrappers around another Bukkit event. In that
shape, the wrapper itself may not expose `getBlock()` or `getEntity()`, but it
can expose the original event through a zero-argument `getOriginalEvent()`
method.

`SyntheticEventOwnerExtractor` handles that as a conservative delegate scan:

```text
custom wrapper event
  -> getOriginalEvent()
  -> scan the original event for getEntity(), getPlayer(), getBlock(),
     getBlocks(), or getLocation()
  -> use the discovered owner route when the owner is clear
```

The emitted owner method includes the delegate path so debug evidence shows why
the route was selected:

```text
ownerMethod=delegate:getOriginalEvent.getBlock
```

This is a general wrapper-event shape, not support for one plugin. The extractor
does not treat `getWorld()` as a region owner because a world alone does not
identify the owning Folia region. If the original event is missing, points back
to the same wrapper, returns the wrong type, or exposes no clear owner, the
event remains serialized and the owner miss is logged.

## Wrapper State And Owner Misses

Every custom sync event that enters `SyntheticEventDispatchBridge` now gets a
small `SyntheticEventPathState` evidence record. This is not a route by itself;
it explains whether the wrapper is scanning, exiting to a known owner, or
keeping the event serialized.

Useful evidence lines:

```text
[FBB synthetic-event-state] action=scan-start route=none ownerStatus=scanning
[FBB synthetic-event-state] action=route-exit route=A_ENTITY ownerStatus=owner-found
[FBB synthetic-event-state] action=serialized route=none ownerStatus=owner-missed
[FBB synthetic-owner-miss] route=none routeFamily=UNKNOWN missReason=...
```

`route=none` is deliberate for unknown/unproven shapes. `UNKNOWN` is evidence,
not an official `RouteFamily`, so it is emitted as `routeFamily=UNKNOWN` and the
event stays in the single-thread compatibility lane.

Owner miss reasons are kept precise so the next pass knows what to improve:

- `no-compatible-owner-getter`: no accepted zero-argument getter shape was found.
- `getter-returned-null-or-wrong-type`: the getter existed but did not provide
  the expected owner type.
- `getter-failed`: invoking the getter threw.
- `empty-collection`: a block collection owner had no blocks.
- `multi-region-collection`: a block collection crossed chunk/region ownership
  and needs a split/model pass before promotion.
- `unsupported-owner-shape`: a same-named method exists but has parameters,
  static shape, or an unsupported return type.

When a listener chain exits through a known owner route, the wrapper also emits:

```text
[FBB synthetic-listener-route-exit] route=<family> listener=<plugin/class>
```

That line connects the shared event wrapper to the exact listener currently
being dispatched inside the owner route. It is evidence that the listener path
left the serialized wrapper only through a known owner family.

## Promotion Evidence

When a known route is observed inside a compatibility context, it is logged as a
promotion candidate. That means:

```text
observed-not-promoted
```

It does not mean the route is safe to split yet. It means the bridge has found a
specific API edge that can be studied and potentially promoted into a real route
after live evidence and smoke tests agree.

## Probe Coverage

The target probe plugin includes a harmless modeled cancellable event path. It
records:

- entry through `SyntheticEventPathBridge`
- single-thread lane sequence and active-lane state
- listener order
- cancellation mutation
- final event state
- shared event effects list
- target/control comparison against raw event dispatch evidence

It also includes a target-only startup classifier proof for listener route
exits. That probe dispatches the same harmless custom event through
`SyntheticEventDispatchBridge`, marks it as an entity-owner-exit probe, and lets
one probe listener throw a Folia-shaped entity guard. No real entity is touched.
The expected debug evidence is:

```text
[FBB synthetic-event-dispatch] action=listener-failure route=A_ENTITY
family=entity next=listener-entity-owner-exit-needed
```

This line proves the synthetic listener model can point from a shared event lane
failure to the next route family without moving the whole listener chain to
global and without silencing the failure.

This probe runs from the existing startup model bucket, so it does not add a new
command and does not require a player join.
