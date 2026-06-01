# Diagnostics

Diagnostics are controlled with JVM system properties or `plugins/FoliaBytecodeBridge/config.properties`.
System properties win when both are present.

## NMS Version Compatibility

`NMS_VERSION_COMPAT` is intentionally separate from the route families. It is
for crashes where a plugin expects a server-internal class, field, or method
shape that the running Folia/Paper jar does not expose.

Evidence line:

```text
[FBB compat] category=NMS_VERSION_COMPAT throwable=NoSuchFieldError owner=net.minecraft.server.MinecraftServer name=currentTick descriptor=I kind=field pluginJar=FastAsyncWorldEdit-Paper-2.15.1.jar caller=com.sk89q.worldedit.bukkit.adapter.impl.fawe.v26_1.PaperweightFaweWorldNativeAccess#<init>(PaperweightFaweWorldNativeAccess.java:65) action=diagnostic-only reason=server-internal-field-missing next=inspect-running-server-member-map-before-bytecode-adapter
```

This is not a scheduler or ownership rewrite. Before adding a bytecode adapter,
the next pass needs the expected owner/name/descriptor from the plugin crash,
the caller class/method and plugin jar, the equivalent member on the running
server jar if one exists, and a decision about whether the replacement preserves
behavior or only hides the crash.

The plugin installs a diagnostic-only runtime log handler after FBB enables, so
later Bukkit/Paper log records with linkage throwables can emit this breadcrumb
directly in the server console. The evidence tool also emits
`[FBB preflight-nms]` rows for plugin jars that reference server internals.
Those rows are an inventory, not a failure verdict. Only a matching linkage
error in the live log proves a real mismatch.

When the evidence tool is given `--server-root <server>`, it also emits
`[FBB member-map]` for each compatibility failure. This scans the extracted
runtime server jars, such as `versions/<version>/folia-<version>.jar`, and
reports whether the expected owner/name/descriptor exists. Candidate members
are research pointers only. A nearby name with a different descriptor, such as
`currentTickStart:J` for an expected `currentTick:I`, is not automatically a
safe adapter target.

When the evidence tool is also given `--paper-root <source-or-zip>`, it compares
plugin server-internal references against both the live Folia server jars and a
Paper reference tree or zip. This emits synthetic-candidate evidence:

```text
[FBB synthetic-candidate] category=NMS_VERSION_COMPAT owner=net.minecraft.server.MinecraftServer kind=field expected=currentTick:I liveClassFound=true liveExact=false paperClassFound=true paperMemberFound=true classification=synthetic-field-candidate-map-equivalent-first next=inspect-paper-source-and-folia-equivalent-before-synthetic-field ...
[FBB synthetic-summary] category=NMS_VERSION_COMPAT candidates=948 printedGaps=367 liveExactSuppressed=581 classifications="..."
```

These rows answer "what could we grab from Paper?" without applying a patch.
`synthetic-field-candidate-map-equivalent-first` means a missing field has a
matching Paper shape, so the next pass should find the Folia-owned equivalent
and prove behavior before adding a synthetic field. `synthetic-method-*` rows
are riskier because a method body has behavior, side effects, ownership rules,
and return semantics; prefer a behavior adapter or normal route-family rewrite
before synthesizing methods. `owner-shape-mismatch-*` usually means the plugin
references a versioned or renamed owner and needs owner mapping research first.

## Synthetic NMS Members

The `SERVER_TICK_COUNTER` adapter is the first promoted `NMS_VERSION_COMPAT`
path. Paper exposes `MinecraftServer.currentTick:I` and increments it in the
main tick loop. Folia 26.1.2 does not expose that field, while FAWE-style
adapters may still read it directly. FBB now adds the field while
`net.minecraft.server.MinecraftServer` is first being defined and updates it
from `TickRegions.TickRegionData#getCurrentTick()` at the start of
`MinecraftServer#tickServer(JJJ, TickRegionData)`.

```text
[FBB nms-compat] category=NMS_VERSION_COMPAT model=SERVER_TICK_COUNTER owner=net.minecraft.server.MinecraftServer member=currentTick descriptor=I result=patched action=synthetic-field-added hook=tickServer-region-currentTick route=none note=server-internal-shape-adapter-not-folia-route
```

This is intentionally not a route-family rewrite. If this line is missing and a
live log still shows `NoSuchFieldError: MinecraftServer.currentTick:I`, the
agent probably attached after `MinecraftServer` was already defined or the
runtime method descriptor changed.

## Recommended Debug Startup

```text
java -javaagent:plugins/FoliaBytecodeBridge.jar -jar folia.jar
```

By default, FBB keeps full transform, bytecode-path, scheduler-call, unsafe-call,
probe, and model evidence in `plugins/FoliaBytecodeBridge/debug.log`. Console
output stays readable unless `consoleVerbose=true` is set.

For an unmodified `run.bat`, use a config file instead:

```properties
debugFile=true
debugFileVerbose=true
debugFilePath=plugins/FoliaBytecodeBridge/debug.log
consoleVerbose=false
modelReports=true
metadataOverlay=all
repeatDiagnosticFirstLines=3
repeatDiagnosticEvery=100
```

## Flags

| Flag | Default | Effect |
| --- | --- | --- |
| `foliabytecodebridge.debug` | `false` | Enables debug trace categories, but does not force console spam. Use `consoleVerbose=true` to mirror all detail to console. |
| `foliabytecodebridge.traceTransforms` | `false` | Logs every class transformed by the agent. Also enabled for the debug file by `debugFileVerbose=true`. |
| `foliabytecodebridge.traceBytecodePaths` | `false` | Logs every raw scheduler bytecode rewrite as owner/name/descriptor -> bridge method with a route family. Also enabled for the debug file by `debugFileVerbose=true`. |
| `foliabytecodebridge.traceTransformSkips` | `false` | Logs bootstrap classes skipped because Bukkit API is not visible yet. Also enabled for the debug file by `debugFileVerbose=true`. |
| `foliabytecodebridge.traceSchedulerCalls` | `false` | Logs every rewritten scheduler call routed through `SchedulerBridge`. Also enabled for the debug file by `debugFileVerbose=true`. |
| `foliabytecodebridge.traceUnsafeCalls` | `false` | Logs every traced direct player/entity/world/block call routed through `UnsafeCallBridge`. Also enabled for the debug file by `debugFileVerbose=true`. |
| `foliabytecodebridge.modelReports` | `true` | Emits grouped `[FBB model]` and periodic `[FBB model-summary]` lines that turn transform/runtime evidence into an architecture map. |
| `foliabytecodebridge.debugFile` | `true` | Writes full-fidelity bridge/probe diagnostics to `plugins/FoliaBytecodeBridge/debug.log` before console filtering. This is the noisy research log. |
| `foliabytecodebridge.debugFileVerbose` | `true` | Enables the noisy transform, bytecode-path, guard-path, scheduler, unsafe-call, and skip traces for the debug file by default. Set false only for a tiny debug file. |
| `foliabytecodebridge.debugFilePath` | `plugins/FoliaBytecodeBridge/debug.log` | Overrides the debug-file location. Keep this local; it may contain plugin stack details even when paths are redacted. |
| `foliabytecodebridge.consoleVerbose` | `false` | Prints full diagnostic detail to console. Leave false for readable live runs; the debug file still receives the full stream. |
| `foliabytecodebridge.modelSummaryIntervalSeconds` | `30` | Emits a fresh `[FBB model-summary]` at least this often while new evidence is still arriving. Set `0` to keep only first/size-threshold summaries. |
| `foliabytecodebridge.repeatDiagnosticFirstLines` | `3` | Full `[FBB scheduler]` and `[FBB unsafe-call]` lines to print for each repeated hot path before summaries begin. Failures are not throttled. |
| `foliabytecodebridge.repeatDiagnosticEvery` | `100` | Emits `[FBB repeat-summary]` for repeated scheduler/unsafe-call paths every N calls after the first full lines. Set `0` to keep only the first full lines. |
| `foliabytecodebridge.appendServerLibraries` | `false` | Experimental: adds selected server API jars to the system classloader. Leave off unless investigating Byte Buddy typed substitution on a controlled test server. |
| `foliabytecodebridge.appendServerLibraryPattern` | API/support jars | Regex matching jar filenames safe to append to the agent/system loader. Keep this narrow to avoid classpath conflicts. |
| `foliabytecodebridge.classpathRoots` | `libraries;versions;cache` | Semicolon-separated roots scanned for Folia/Paper API jars and server libraries. |
| `foliabytecodebridge.selfAttach` | `true` | Experimental: when `-javaagent` was not used, try to attach from plugin `onLoad` using a helper JVM and the same jar. |
| `foliabytecodebridge.metadataOverlay` | `off` | Experimental load-gate overlay. `all` rewrites Folia's plugin metadata check so every plugin reports `folia-supported` at load time. Requires `-javaagent` to help plugins that would otherwise be rejected before class loading. |
| `foliabytecodebridge.forceNonFolia` | `false` | Test-only flag that forces non-Folia pass-through mode. Used by the smoke test. |

The same diagnostic keys can be written without the `foliabytecodebridge.` prefix in `plugins/FoliaBytecodeBridge/config.properties`; for example `traceSchedulerCalls=true`.

## Attach Logs

Shape:

```text
[FBB attach] mode=<JAVA_AGENT|SELF_ATTACH> installed=<true|false> phase=<onLoad|onEnable> ...
[FBB attach-warning] mode=SELF_ATTACH installed=false phase=onLoad ...
[FBB attach] mode=SELF_ATTACH retransformCandidates=<count> retransformAttempted=<count> retransformFailed=<count>
```

`JAVA_AGENT` means the transformer was installed before Bukkit plugin loading.
`SELF_ATTACH` means the plugin attempted to install the transformer from `onLoad`.
If self-attach succeeds but a target plugin still fails in startup code, compare
the first target-plugin load line with the attach line. That tells us whether this
experimental mode attached too late for that bytecode path.

When self-attach succeeds after Paper has already created plugin instances, the
agent runs one focused retransformation pass over already-loaded classes whose
code source is a jar under `plugins/`. This gives the raw scheduler transformer
a second chance at startup classes such as plugin mains and task managers. A
successful raw patch looks like:

```text
[FBB transform] class=<plugin class> loader=<plugin classloader> path=raw-scheduler result=patched
```

## Transform Logs

Shape:

```text
[FBB transform] class=<class> loader=<classloader>
```

Use these lines to confirm the target plugin class was actually touched by the Java agent. If a plugin class never appears here, it may have loaded before the agent or may not contain a supported scheduler call shape.

On a real Paperclip/Folia server, debug mode also reports:

```text
[FBB agent-classpath] server-jars-appended=<count>
```

If `<count>` is `0`, the agent probably started before Paperclip had created `libraries/` and `versions/`. Start the server once to let Paperclip lay down its files, then restart with the bridge enabled.

During Paperclip/Folia bootstrap, `foliabytecodebridge.traceTransformSkips=true` may also print:

```text
[FBB transform-skip] class=<class> loader=<classloader> reason=bukkit-api-not-visible-yet
```

That skip is expected before the real Bukkit API is visible to the Java agent. It is not the same as a transformer failure. A useful failure signal is a later `[FBB transform-error]` on a plugin-owned class after Bukkit has started loading plugins.

Optional soft-dependency misses are treated as transform skips instead of
transform errors. This covers the general Byte Buddy resolution shape:

```text
[FBB transform-skip] class=<plugin integration class> loader=<classloader> reason=optional-dependency-missing missing=<missing api class> action=skip-typed-transform-no-fake-adapter asm=<route counts or scan status>
```

This is not an adapter and it does not pretend PlotSquared, Vault,
PermissionsEx, GroupManager, PlaceholderAPI, or another plugin exists. It only
marks the typed substitution pass as inapplicable because a referenced optional
API is absent. The raw transformers can still cover exact bytecode shapes that
do not need type resolution, and the ASM scanner records route-family evidence
from the class bytes when they are readable.

Method-reference rewrites also have a narrow safety boundary. If an
`invokedynamic` method reference captures a receiver as one Bukkit interface
while the declared owner is another, the raw transformer logs:

```text
[FBB guard-path] ... action=trace-only reason=trace-only: invokedynamic receiver mismatch; ordinary invoke rewrite required
```

This prevents JVM `BootstrapMethodError` from a mismatched lambda bootstrap.
The bridge still rewrites ordinary invoke instructions for that same
owner/name/descriptor shape, and the trace line tells us when a future adapter
needs an exact receiver bridge method.

## Model Report Logs

`modelReports=true` is on by default so live tests print a compact architecture
map while preserving the original evidence lines. The model report does not make
routing decisions and does not silence failures. It groups bytecode and runtime
evidence by API shape:

```text
[FBB model] route=<family> api=<owner-or-runtime-api> owner=<owner model> descriptor=<JVM descriptor|runtime> return=<type> status=<rewritten|routed|modeled|trace-only|missed|blocked-sync-return|failure|scheduler> next=<translation hint> syncReturnRisk=<true|false> routeRulePolicy=<policy|dynamic-or-unregistered> routeRuleStatus=<status|dynamic-or-unregistered> routeRuleNote=<short note> evidence=<source summary>
[FBB model-summary] methods=<count> routes=S_GLOBAL=<n>,S_ASYNC=<n>,A_ENTITY=<n>,B_REGION_LOCATION=<n>,C_REGION_BLOCK=<n>,D_PLAYER_UI=<n>,F_PLAYER_VISIBILITY=<n>,G_WORLD_SCAN_SPLIT=<n> knownRules=<count> rewritten=<n> modeled=<n> traceOnly=<n> missed=<n> blockedSyncReturn=<n> failures=<n> schedulers=<n>
```

Exact bytecode routes are backed by `RouteRuleRegistry`, the central route-rule
registry. It is the ownership-map contract between analyzer, transformer,
diagnostics, and smoke tests. Each registered rule carries:

- owner/name/descriptor bytecode shape
- route family
- guard label
- bridge method and descriptor
- return policy, such as `VOID_FIRE_AND_FORGET`, `ACCEPTED_BOOLEAN`,
  `SYNC_RETURN_DIRECT_OR_OWNER`, `SPLIT_AGGREGATE_RETURN`,
  `DEFERRED_PROXY_RETURN`, `SHIM_MODEL_RETURN`, or `ASYNC_FUTURE`
- translation status, such as `EXPERIMENTAL_REWRITE`, `TRACE_ONLY`, or `MISSED`
- a short note explaining why the route exists

Dynamic/generalized detections still exist for shapes that cannot safely be
represented as a single exact owner, such as shaded teleport helpers. Those
lines print `routeRulePolicy=dynamic-or-unregistered` so future passes can tell
the difference between a central rule and an observed dynamic shape.

Read `[FBB model]` as the current route-family model for a method, not as proof
that the method is fully safe. For example, `status=blocked-sync-return` means
the bridge found an owner route but refused to block a Folia owner thread while
waiting for a legacy synchronous return value. `status=modeled` means a bridge
shim/model handled the call, such as detached scoreboard state. `status=missed`
keeps a bytecode shape visible so the next pass can decide whether it is safe to
translate.

## Metadata Overlay Logs

`metadataOverlay=all` is intentionally loud because it only opens the Folia load gate. It does not prove thread safety and it does not replace the scheduler, teleport, unsafe-call, or task-failure evidence lines.

Shape:

```text
[FBB metadata] class=org.bukkit.plugin.PluginDescriptionFile loader=<classloader> jar=<api jar> route=S_GLOBAL action=metadata-transform mode=all result=patched-return-true
[FBB metadata] plugin=<plugin name> jar=<plugin jar> route=S_GLOBAL action=jar-scan mode=all result=<already-supported|overlay-will-force-true> note=experimental-load-gate-only-not-thread-safety
```

The metadata transform is classloader-safe and self-contained: `PluginDescriptionFile#isFoliaSupported()` returns `true` directly under `metadataOverlay=all` instead of calling back into bridge classes that Folia's early launcher classloader cannot see. The jar-scan line is the per-plugin evidence that a jar either already had `folia-supported: true` or will be forced open by the overlay.

Use this only on controlled test servers. If a plugin loads because of this line and then fails later, keep the later `[FBB scheduler]`, `[FBB teleport-path]`, `[FBB unsafe-call]`, `[FBB unsafe-failure]`, or `[FBB task-failure]` evidence instead of hiding the failure.

## Bytecode Path Logs

When `traceBytecodePaths=true`, every raw scheduler rewrite explains the exact bytecode path selected:

```text
[FBB bytecode-path] class=<class> in=<method><descriptor> source=<owner>#<name><descriptor> route=<S_GLOBAL|S_ASYNC> bridge=ObjectSchedulerBridge#<method><descriptor>
```

This is the main evidence line for "did we route the right bytecode path?" A correct PlayerKits2 anonymous `BukkitRunnable` route should show an owner like `pk.ajneb97.configs.PlayersConfigManager$1`, while a correct Essentials helper route should show `source=org.bukkit.scheduler.BukkitScheduler#runTaskAsynchronously(...)`, not `source=com.earth2me.essentials.Essentials#runTaskAsynchronously(...)`.

## Teleport Path Logs

Teleport family detection has its own bytecode evidence line:

```text
[FBB teleport-path] class=<plugin class> loader=<classloader> jar=<plugin jar or classes dir> in=<method><descriptor> owner=<bytecode owner> name=<method> descriptor=<descriptor> route=A_ENTITY rule=<bukkit-api-owner|generic-shape|generic-shape-probe|generic-helper-shape|exact-owner> action=<rewritten|trace-only|missed> outcome=<detail> bridge=<bridge or none>
```

Use this for Essentials `/home`-style failures and other teleport helpers. The bridge is not checking command names or plugin names. It groups calls by bytecode shape:

- `rule=bukkit-api-owner action=rewritten outcome=rewritten-direct-teleport-async-shim`: direct sync Bukkit API calls such as `Entity#teleport(Location)` or `Player#teleport(Location,TeleportCause)` are rewritten to `UnsafeCallBridge`, which uses `teleportAsync` on Folia.
- `rule=bukkit-api-owner action=trace-only outcome=typed-transform-expected`: direct Bukkit API calls that are already async, such as `Entity#teleportAsync(Location,TeleportCause)`, are observed by the raw scanner and left on their original async path.
- `rule=generic-shape action=rewritten outcome=rewritten-generic-static-shape`: static helper calls named `teleportAsync` with `(Entity|Player, Location, TeleportCause) -> CompletableFuture` are rewritten by descriptor shape, no matter which package shaded the helper.
- `rule=generic-shape-probe action=missed outcome=missed-unsupported-static-shape`: a static `teleportAsync` looked related, but its descriptor did not match the safe generic shape. The log preserves owner/name/descriptor so a later pass can decide whether to support it.
- `rule=generic-helper-shape action=trace-only outcome=observed-entity-location-helper`: an instance or interface helper named `teleportAsync` accepted `(Entity|Player, Location, ...)`. This shape is common in scheduler-helper layers, including shaded copies, and is logged as evidence only because the helper may already choose the correct threaded route.
- `rule=exact-owner` is reserved for future bytecode-shape exceptions where a descriptor cannot be generalized safely. It must be documented as an owner/descriptor exception, not as plugin or command support.

## Server Command Dispatch Logs

`Bukkit#dispatchCommand(CommandSender,String)` and
`Server#dispatchCommand(CommandSender,String)` are rewritten by exact Bukkit API
owner/name/descriptor. The bytecode evidence is emitted as a guard path because
the source guard is `CraftServer#dispatchCommand`:

```text
[FBB guard-path] class=<plugin class> loader=<classloader> jar=<plugin jar> in=<method><descriptor> owner=<org.bukkit.Bukkit|org.bukkit.Server> name=dispatchCommand descriptor=(Lorg/bukkit/command/CommandSender;Ljava/lang/String;)Z route=S_GLOBAL guard=CraftServer#dispatchCommand action=rewritten reason=...
[FBB transform] class=<plugin class> loader=<classloader> path=raw-command-dispatch result=patched replacements=<count>
```

At runtime the bridge logs:

```text
[FBB unsafe-call] api=<Bukkit#dispatchCommand|Server#dispatchCommand> route=S_GLOBAL family=global next=global-or-entity-command-dispatch detail=sender=<name> scheduler=<entity-scheduler|global-region-scheduler> return=scheduled-true caller=<class#method(file:line)>
```

The `scheduled-true` return is explicit evidence, not error silencing. Folia
requires a scheduler hop, so the original synchronous boolean command result is
not available without blocking the region thread. If the scheduled dispatch
throws, the bridge logs `[FBB task-failure]` and `[FBB unsafe-failure]`.

## Raw Direct Unsafe Logs

Some plugin classloaders expose bytecode to ASM before Byte Buddy can resolve
enough Bukkit types for the typed substitution pass. `RawDirectUnsafeTransformer`
handles the exact JVM descriptors that live probes proved were escaping:

```text
[FBB guard-path] class=<plugin class> ... owner=<owner> name=<method> descriptor=<descriptor> route=<family> guard=<guard> action=rewritten reason=<why>
[FBB transform] class=<plugin class> loader=<classloader> path=raw-direct-unsafe result=patched replacements=<count>
```

For chunk reads/loads and entity nearby scans, the runtime wrapper first tries
the original call. If Folia rejects the current thread, it logs another
`[FBB unsafe-call]` with `fallback=scheduler-after-thread-guard` and retries on
the entity or region scheduler. If the scheduled retry fails or times out, the
bridge logs `[FBB unsafe-failure]`; this path is not a quiet bypass.

## Scheduler Logs

Shape:

```text
[FBB scheduler] api=<source api> route=<S_GLOBAL|S_ASYNC> policy=<bridge policy> plugin=<plugin> caller=<class#method(file:line)>
```

The `caller` value is the first non-bridge stack frame. It is a best-effort pointer to the general source section that made the legacy scheduler call.
The `route` value comes from `RouteFamily`; legacy scheduler book-keeping policies such as `cancel`, `cancel-plugin`, and `status` are logged as `S_GLOBAL` because they belong to the scheduler/global fallback path.
Repeated scheduler paths print the first `repeatDiagnosticFirstLines` full
examples, then emit `[FBB repeat-summary]` every `repeatDiagnosticEvery` calls.
The repeat key includes API, route, policy, plugin, and caller, so different
bytecode owners or call sites still get separate evidence.

Example:

```text
[FBB scheduler] api=BukkitRunnable#runTaskTimer route=S_GLOBAL policy=global-repeating plugin=SuperVanish caller=de.myzelyam.supervanish.visibility.ActionBarMgr#startTask(ActionBarMgr.java:58)
```

## Task Failure Logs

Task failures are always logged because they are high-value and low-volume.

Shape:

```text
[FBB task-failure] plugin=<plugin> route=<S_GLOBAL|S_ASYNC> scheduledFrom=<class#method(file:line)> probableFrame=<class#method(file:line)> throwable=<exception>
```

The `route` value records which scheduler family ran the task. The `scheduledFrom` value points to the legacy scheduler call site. The `probableFrame` is not guaranteed to be the root cause, but it usually points near the plugin code section that failed after the scheduler bridge ran it.

## Legacy Main-Thread Predicate Logs

Some Paper-era libraries use a boolean `isMainThread()` predicate to decide
whether queued server work can run immediately. Folia has no single global main
thread, so FBB treats this as a scheduler/global compatibility model rather than
an NMS synthetic member.

The first promoted rule is an exact method-body rewrite for
`com.fastasyncworldedit.core.Fawe#isMainThread()Z`. It keeps FAWE's original
captured-thread check and only falls back to a Folia-aware answer when the
legacy predicate would have returned `false`. The fallback returns `true` only
from known Folia/Bukkit tick contexts; async worker pools still return `false`.

```text
[FBB legacy-main-thread] class=<class> loader=<loader> jar=<jar> in=<method><descriptor> owner=<owner> name=isMainThread descriptor=()Z route=S_GLOBAL rule=<exact-owner-method-body|exact-owner-callsite|generic-name-descriptor> action=<rewritten|trace-only> reason=<why>
[FBB legacy-main-thread] owner=<owner> name=isMainThread descriptor=()Z route=S_GLOBAL action=runtime-fallback result=<compatible|legacy-false> tickThread=<true|false> bukkitPrimaryThread=<true|false> thread="<thread name>" note=false-legacy-main-thread-predicate-mapped-to-folia-context-only
```

Generic static `isMainThread()Z` owners are trace-only until a real owner model
is known. Do not widen this into "all methods named isMainThread are safe"; the
bridge needs evidence about what state that predicate protects.

## Reading Folia Thread Errors

If a Folia region ownership error appears after a scheduler log, compare:

- the scheduler `caller`
- the task-failure `probableFrame`
- the first plugin-owned frame in the original stack trace

If the scheduler policy is `global-*` and the failing frame touches a player/entity/block/world, that code likely needs source-level player/entity/region scheduling. The bytecode bridge cannot infer that safely.

## Probe Failure Fingerprints

`FBBProbe` and `FBBProbeControl` add a failure fingerprint to every failed probe
call:

```text
[FBB probe] ... result=failed throwable=<exception> classification=<unsupported-operation|thread-guard|region-guard|exception class> thread=<thread> pluginFrame=<frame> guardFrame=<frame> schedulerFrame=<frame>
```

These fields are meant to answer "what path actually triggered the guard?"
without hiding the exception. `pluginFrame` points at the probe or plugin call
site, `guardFrame` points at the Folia/CraftBukkit guard that rejected the
operation, and `schedulerFrame` records the scheduling or region machinery when
the failure passed through one. An `UnsupportedOperationException` also appends
`action=trace-only reason=unsupported-operation-route-not-proven`; that means
the bridge has evidence but does not yet have a safe translation.

## Unsafe Direct Call Logs

Shape:

```text
[FBB unsafe-call] api=<direct Bukkit api> route=<S_GLOBAL|A_ENTITY|B_REGION_LOCATION|C_REGION_BLOCK|D_PLAYER_UI|F_PLAYER_VISIBILITY|G_WORLD_SCAN_SPLIT> family=<global|entity|player|entity-scan|region|world-scan> next=<suggested scheduler family> detail=<target detail> caller=<class#method(file:line)>
```

These logs come from `UnsafeCallBridge`. Most wrappers are probes around high-risk direct calls such as `World#getBlockAt` and `Block#setType`, then execute the original call.
Repeated unsafe-call paths print the first `repeatDiagnosticFirstLines` full
examples, then emit `[FBB repeat-summary]` every `repeatDiagnosticEvery` calls.
Coordinates and target detail stay in the full lines and latest summary, but
the repeat key groups by API, route family, next action, and caller so hot
proven paths such as `Block#getType` do not drown out new failures.
The sync teleport wrappers are stronger: on Folia, `Entity#teleport(Location...)` and `Player#teleport(Location...)` submit `teleportAsync` and return `true` as a legacy boolean compatibility shim. Future completion failures are still logged as `[FBB unsafe-failure]`, so this preserves evidence instead of hiding failed teleports.
Player audio maps to `A_ENTITY`, and chunk-coordinate world calls map to `B_REGION_LOCATION`, so the emitted labels stay inside the official route-family set.
Async teleport probes such as `Entity#teleportAsync(Location,TeleportCause)` and generic static `teleportAsync(Entity|Player,Location,TeleportCause)` helpers also log a later `[FBB unsafe-failure]` if their returned future completes exceptionally. That is not error silencing; the same exceptional future is returned to the caller.

`Entity#getNearbyEntities(...)` and `Player#getNearbyEntities(...)` are
`G_WORLD_SCAN_SPLIT` return-value routes. Owned entity calls stay direct, async
callers can wait for the entity scheduler, and Folia owner/global/foreign-region
threads use `fallback=entity-location-bounded-split` so the bridge does not park
one owner thread while another computes the return value.

If the original call throws, the bridge also logs:

```text
[FBB unsafe-failure] api=<direct Bukkit api> route=<S_GLOBAL|A_ENTITY|B_REGION_LOCATION|C_REGION_BLOCK|D_PLAYER_UI|F_PLAYER_VISIBILITY|G_WORLD_SCAN_SPLIT> family=<global|entity|player|entity-scan|region|world-scan> next=<suggested scheduler family> probableFrame=<class#method(file:line)> throwable=<exception>
```

Return-value routes may also emit:

```text
[FBB unsafe-call] api=<direct Bukkit api> route=<family> ... fallback=blocked-sync-return-avoided action=<direct-preserve-original|preserve-original-throw> reason=return-value-scheduler-wait-from-folia-thread thread="<thread name>"
```

That means the bridge found an owner route, but using it would require blocking a
Folia owner/tick thread while another scheduler computes the return value. The
original failure is preserved so the log stays useful and the server does not
stall behind the bridge.

Some return-value routes have a narrower model and avoid the blocked path:

```text
[FBB unsafe-call] api=World#getChunkAt(...) route=B_REGION_LOCATION ... fallback=loaded-chunk-index-return policy=sync-return-model result=<hit|miss>
[FBB unsafe-call] api=World#getChunkAt(...) route=B_REGION_LOCATION ... policy=deferred-chunk-model action=async-preload-return-proxy result=proxy
[FBB unsafe-call] api=Block#getType route=C_REGION_BLOCK ... fallback=block-material-cache policy=sync-return-model result=<hit|miss>
[FBB unsafe-call] api=World#strikeLightning(Location) route=B_REGION_LOCATION ... fallback=deferred-lightning-proxy policy=deferred-proxy-return result=proxy
[FBB unsafe-call] api=<boolean world effect> route=B_REGION_LOCATION ... fallback=preemptive-region-scheduler policy=deferred-accepted-boolean return=scheduled-true reason=boolean-route-no-safe-sync-return
```

`loaded-chunk-index-return` is used only when the requested chunk is already in
the loaded chunk index, so the bridge can return a real `Chunk` without parking a
Folia owner thread. When that index misses, `deferred-chunk-model` returns a
bridge-owned `Chunk` proxy and starts Paper/Folia async chunk preload. The proxy
preserves simple identity/coordinate reads immediately, delegates to the real
chunk after preload, and logs `pending-result-default` if plugin code asks for
chunk data before the preload completes. This is intentionally loud model
evidence, not a claim that every `Chunk` method is safe while pending.
`block-material-cache` is used only for `Block#getType()` after a prior
owner-safe read or bridge-owned `Block#setType(Material)` has recorded the
material for that block. A cache miss preserves the original direct failure, so
new bytecode shapes still produce evidence instead of being guessed as `AIR`.
`deferred-lightning-proxy` is used for entity-returning lightning effects when a
foreign/global Folia owner thread cannot safely wait for the target region. The
actual region task still logs task/unsafe failures, and proxy method calls log
`method-default` if plugin code touches the proxy before the real entity exists.
`deferred-accepted-boolean` means the region-owned action was submitted and the
legacy boolean reports scheduling acceptance; later Bukkit failures are still
emitted as `[FBB task-failure]` and `[FBB unsafe-failure]`.

Void owner-routed calls may instead emit:

```text
[FBB unsafe-call] api=<direct Bukkit api> route=<family> ... fallback=preemptive-region-scheduler policy=fire-and-forget-void reason=void-route-no-sync-return
```

That means the call has no legacy return value to preserve, so the bridge can
submit it to the owning region and return immediately. The scheduled task still
logs `[FBB task-failure]` and `[FBB unsafe-failure]` if Bukkit/Folia rejects the
operation later; this is a real route, not an error-silencing path.

Use the `route`, `family`, `next`, `caller`, and `probableFrame` values to decide which exact direct-call family deserves a real translation next. See `docs/BYTECODE_ROUTES.md` for the A/B/C route map.
