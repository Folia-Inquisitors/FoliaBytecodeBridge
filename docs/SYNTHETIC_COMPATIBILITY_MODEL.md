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
