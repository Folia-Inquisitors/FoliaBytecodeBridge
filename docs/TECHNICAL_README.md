# FoliaBytecodeBridge

Experimental Bukkit/Folia plugin plus Java agent that rewrites common legacy Bukkit bytecode shapes into Folia ownership routes.

This is intentionally risky. It is useful for testing whether old plugins can get past scheduler API failures on Folia, but it does not prove those plugins are region-thread safe.

## What it rewrites

The transformer targets plugin bytecode that calls:

- `BukkitScheduler#runTask`
- `BukkitScheduler#runTaskLater`
- `BukkitScheduler#runTaskTimer`
- `BukkitScheduler#runTaskAsynchronously`
- `BukkitScheduler#runTaskLaterAsynchronously`
- `BukkitScheduler#runTaskTimerAsynchronously`
- `BukkitScheduler#scheduleSyncDelayedTask`
- `BukkitScheduler#scheduleSyncRepeatingTask`
- `BukkitScheduler#scheduleAsyncDelayedTask`
- `BukkitScheduler#scheduleAsyncRepeatingTask`
- `BukkitScheduler#callSyncMethod`
- `BukkitScheduler#cancelTask`
- `BukkitScheduler#cancelTasks`
- `BukkitRunnable#runTask*`
- `BukkitRunnable#cancel`
- `BukkitRunnable#isCancelled`

On Folia:

- Legacy sync scheduler calls are redirected to the global region scheduler.
- Legacy async scheduler calls are redirected to Folia's async scheduler.
- Returned Folia scheduled tasks are wrapped as fake `BukkitTask` handles so `getTaskId()` and `cancelTask(id)` can keep working.

On normal Paper/Spigot, calls pass through to the original Bukkit scheduler.

## Project Notes

- [Architecture notes](ARCHITECTURE.md) explain the agent/runtime split and extension rules.
- [Bytecode routes](BYTECODE_ROUTES.md) explains the official `RouteFamily` architecture map and when another plugin with the same bytecode is covered.
- [Diagnostics](DIAGNOSTICS.md) explains debug flags and failure-path logs.
- [Stabilization audit](STABILIZATION_AUDIT.md) separates transformer targeting, shutdown, and lifecycle evidence from normal route failures.
- [Direct unsafe trace matrix](DIRECT_UNSAFE_TRACE_MATRIX.md) lists high-risk player/world/block/entity probes.
- [Probe audit](PROBE_AUDIT.md) records how external adapter examples and uploaded plugin references informed the probe coverage.
- [Probe plugin notes](PROBE_PLUGIN.md) describe the optional `FBBProbe` live-server test jar for intentionally triggering route families.
- [Live probe triage](LIVE_PROBE_TRIAGE.md) separates current-context completions from true Folia guard failures.
- [Solution model](SOLUTION_MODEL.md) explains which evidence can become a rewrite and which evidence must stay diagnostic.
- [Paper guard audit](PAPER_GUARD_AUDIT.md) maps Folia/Paper guard sites to route families before any new rewrite is added.
- [Operations notes](OPERATIONS.md) cover startup, testing, and failure reading.
- [Smoke test result](SMOKE_RESULT.md) records the local Java-agent smoke test.
- [Transform matrix](TRANSFORM_MATRIX.md) lists every supported rewrite.
- [Folia 1.20 reference notes](target-audits/Folia-1.20-reference.md) record the historical scheduler-route evidence from the older Folia zip.

## What it cannot guarantee

This does not prove arbitrary plugins are region-thread safe. The bridge tries
to translate legacy single-main-thread assumptions into Folia ownership routes:
entity/player, region/location, block/chunk, global, async, split world scans,
and player UI/model state.

Some routes are real rewrites, such as scheduler calls, teleports, block
material access, chunk reads, selected world scans, command dispatch, and
scoreboard model paths. Other shapes are still diagnostic-only or dynamic
evidence. Return-value routes are the hardest category because the bridge must
preserve old synchronous Bukkit behavior without blocking the wrong Folia owner
thread. It does not handle arbitrary calls such as direct NMS access.

Server-internal binary mismatches are tracked separately as
`NMS_VERSION_COMPAT`. That model reports the expected owner/name/descriptor,
plugin jar, and caller when a live log contains linkage errors such as
`NoSuchFieldError`, but it does not pretend those are ordinary Folia ownership
routes.

## Route Families

The official translation categories are centralized in `RouteFamily`:

```text
S_GLOBAL = scheduler/global fallback
S_ASYNC = async scheduler
A_ENTITY = entity/player calls
B_REGION_LOCATION = world calls with location
C_REGION_BLOCK = block-owned calls
D_PLAYER_UI = inventory/menu/player UI
F_PLAYER_VISIBILITY = hide/show player
G_WORLD_SCAN_SPLIT = world/entity scans
```

Diagnostics emit these labels as `route=<family>` in `[FBB scheduler]`,
`[FBB unsafe-call]`, `[FBB unsafe-failure]`, `[FBB task-failure]`, and
`[FBB model]` lines.

`NMS_VERSION_COMPAT` is not a `RouteFamily` label. The evidence tool prints it
as `[FBB compat] category=NMS_VERSION_COMPAT` so internal server-shape failures
stay visible without polluting the ownership map.

When run with `--server-root`, the evidence tool also prints `[FBB member-map]`
rows that inspect the actual extracted server jars and report whether the
missing NMS/Craft member exists. Candidate fields or methods are research
pointers, not automatic adapter targets.

The first promoted NMS adapter is `SERVER_TICK_COUNTER`. It adds
`MinecraftServer.currentTick:I` during first class definition when the running
Folia jar lacks that Paper field, then updates it from Folia's region tick data
inside `MinecraftServer#tickServer(...)`. Its runtime log line is
`[FBB nms-compat]`, not `[FBB unsafe-call]`, because this is a server-internal
shape adapter rather than a Bukkit ownership route.

Exact bytecode routes are centralized in `RouteRuleRegistry`. Each route entry
declares the owner/name/descriptor shape, route family, bridge method, return
policy, translation status, and a note explaining why the route exists.
`InstructionRouteRegistry`, the raw transformer, diagnostics, and smoke tests
all read from that same map before falling back to dynamic detections. This is
the main organizational rule for future work: adding a proven exact method
should become one route model entry, not scattered transformer/probe/doc edits.

## Recommended startup

Put the built jar in `plugins/`, then start the server with the same jar as a Java agent:

```text
java -javaagent:plugins/FoliaBytecodeBridge.jar -jar folia.jar
```

If the jar is installed only as a plugin and not as a Java agent, it will load the runtime bridge classes but will not rewrite other plugins.

## Experimental metadata overlay

For smoke servers where every legacy plugin should get a chance to load, set:

```properties
metadataOverlay=all
```

in `plugins/FoliaBytecodeBridge/config.properties`, or pass `-Dfoliabytecodebridge.metadataOverlay=all` before `-javaagent`. This rewrites Folia's plugin metadata check so plugin descriptions report Folia support at load time. It is deliberately logged as `[FBB metadata]` evidence and does not mean the plugin is thread-safe.

## Experimental self-attach startup

Because this project is experimental, the plugin also tries a best-effort self-attach from Bukkit `onLoad` when `-javaagent` was not used. This starts a tiny helper JVM from the same jar, asks the JDK attach API to load `FoliaBytecodeBridge.jar` into the running server process, then installs the same transformer path used by `-javaagent`.

Self-attach is useful for evidence gathering, but it is not as deterministic as `-javaagent`. It can fail if the runtime lacks `jdk.attach`, if process attach is blocked, or if another plugin loads and runs unsafe bytecode before the attach finishes.

Disable it with:

```text
-Dfoliabytecodebridge.selfAttach=false
```

Useful startup evidence:

```text
[FBB attach] mode=SELF_ATTACH installed=true phase=onLoad
[FBB attach-warning] mode=SELF_ATTACH installed=false ...
```

## Build

```text
mvn package
```

The Maven output jar is in `target/folia-bytecode-bridge-0.1.0-SNAPSHOT.jar`.

This workspace also includes a manually assembled test jar at `target/FoliaBytecodeBridge.jar` because Maven was not installed locally.

The optional live probe jars are built separately as `target/FBBProbe.jar` and
`target/FBBProbeControl.jar`. Put them in `plugins/` only on throwaway test
servers. `FBBProbe` is transformed by the bridge; `FBBProbeControl` uses an
ignored package so it shows raw Folia behavior. Run `/fbbprobe matrix safe` and
`/fbbprobecontrol matrix safe` as an operator player to compare bridge behavior
against the control across current, entity, async, global, region, and
foreign-region contexts. The transformed probe auto-runs startup evidence by
default; the control probe defaults to `startupModes=off` so raw baseline guard
failures do not drown normal bridge startup evidence.

## Status

Prototype. Test on a throwaway server first.
