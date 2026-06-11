# Stabilization Audit

This note records the first cleanup pass after broad live-server testing. The
goal is to keep evidence precise without weakening the experimental bridge.

## Transformer Target Audit

FBB has two transform layers:

- raw ASM transformers
- typed Byte Buddy substitutions

Raw ASM transformers inspect exact owner/name/descriptor bytecode and remain the
primary path for route-shaped calls. They still see plugin classes before any
typed decision is made.

The typed Byte Buddy pass is narrower. It now uses the read-only
`typed-route-prescan-v1` inventory before attempting typed substitutions:

```text
[FBB candidate-scan] ... typedTransform=<attempted|skipped-no-registered-route-candidate>
```

If the prescan finds no registered route candidate, the typed pass is skipped:

```text
[FBB transform-skip] reason=typed-prescan-no-registered-route-candidate
```

This avoids noisy metadata failures from unrelated helper/event classes while
preserving raw bytecode route coverage. If Byte Buddy still rejects a route
candidate because of unusual type-use metadata, the bridge records:

```text
[FBB transform-skip] reason=bytebuddy-type-metadata-shape action=skip-typed-transform-preserve-raw-routes
```

That is a typed-transform boundary, not proof that the class is Folia-safe and
not proof that a route was missed.

Class-transform targeting is not the same layer as the synthetic compatibility
lane. A class file with no registered route fingerprint is not executing plugin
logic yet, so it is skipped by the typed transformer rather than serialized.
Runtime event, command, and listener paths are where unknown behavior can enter
the compatibility lane.

## Route Registry Audit

Exact bytecode shapes with stable owner/name/descriptor contracts belong in
`RouteRuleRegistry`. Current promoted examples include scheduler/global command
dispatch, Paper/NMS server executors, direct teleports, inventory/UI routes,
scoreboard model routes, block/chunk/world reads, world scans, and selected
location-owned effects.

Some evidence is intentionally not an exact registry entry. Shaded helper
owners and ownerless generic method shapes can vary by package, so the model
labels them as generic policies:

```text
routeRulePolicy=GENERIC_SHAPE
routeRulePolicy=GENERIC_HELPER_SHAPE
routeRulePolicy=GENERIC_SHAPE_PROBE
```

These labels mean "handled by a documented generic detector," not "missing
from the registry by accident." A generic shape should become an exact
`RouteRuleRegistry` entry only when the owner/name/descriptor contract is
stable enough that future plugins with the same exact bytecode should all share
the same behavior.

Runtime model evidence can use shorter API names than bytecode, for example
`World#getEntities` instead of `org/bukkit/World#getEntities()Ljava/util/List;`.
The registry now normalizes those runtime API names back to existing exact
route rules when the owner, method, and optional simple signature match. This
keeps recurring runtime evidence out of the `dynamic-or-unregistered` bucket
without duplicating the same route in multiple places.

`dynamic-or-unregistered` should now mean one of three things:

- the runtime evidence has no stable bytecode/owner shape yet
- the method is intentionally generic and should use a generic policy label
- a new exact rule or runtime normalization case is genuinely missing

## Lifecycle Boundary Audit

Live reload and shutdown tests mix normal route evidence with plugin lifecycle
state. During shutdown, Folia may stop ticking owner schedulers while probe or
bridge return-value fallbacks are still waiting.

Direct return-value fallbacks now classify this separately:

```text
[FBB unsafe-failure] ... classification=server-stopping action=abandon-scheduled-fallback
```

Synthetic event owner exits do the same:

```text
[FBB synthetic-event-route-exit] action=abandon classification=server-stopping
```

Probe work also stops cleanly:

```text
[FBB probe] ... result=abandoned classification=server-stopping action=skip-remaining-probe-work
```

These lines mean "the server lifecycle ended the experiment." They do not mark
the route safe, and they should not be triaged as normal Folia ownership
failures.

## Reading The Buckets

- `transform-skip`: transformer boundary or optional dependency issue.
- `unsafe-failure`: direct bridge route failed during normal execution unless
  `classification=server-stopping` is present.
- `synthetic-event-route-exit action=abandon`: synthetic owner route was queued
  but lifecycle shutdown interrupted it.
- `probe result=abandoned`: test harness stopped because the plugin/server was
  shutting down.
- raw control probe failures remain expected baseline evidence when
  `bridgeRole=control-untransformed controlExpected=true` is present.
- normal startup auto-runs the transformed probe by default; the raw control
  probe defaults to `startupModes=off` so expected baseline guard failures do
  not drown out bridge route evidence unless explicitly requested.
- deliberate synthetic listener route-exit failures stay in `debug.log` by
  default and do not print to console unless `consoleVerbose=true` is enabled.

This keeps the project organized around evidence buckets instead of treating
every stack trace as the same kind of Folia route bug.
