# Operations Notes

Use this on a test server first. Keep a copy of the server logs from startup through the first few plugin actions so failures can be traced to either the transformer or the runtime bridge.

## Install

1. Build or use `target/FoliaBytecodeBridge.jar`.
2. Put the jar in `plugins/FoliaBytecodeBridge.jar`.
3. Start Folia with:

```text
java -javaagent:plugins/FoliaBytecodeBridge.jar -jar folia.jar
```

The same jar must be both the Java agent and the Bukkit plugin.

Experimental mode: if the server is started without `-javaagent`, the plugin tries
to self-attach during Bukkit `onLoad` by spawning a helper JVM from the same jar.
This is best-effort evidence gathering, not the recommended production path.
Disable it with:

```text
-Dfoliabytecodebridge.selfAttach=false
```

For debugging, keep the default file-first diagnostics:

```text
-Dfoliabytecodebridge.debugFile=true
-Dfoliabytecodebridge.debugFileVerbose=true
-Dfoliabytecodebridge.consoleVerbose=false
```

By default, detailed `[FBB ...]` evidence is written to
`plugins/FoliaBytecodeBridge/debug.log` while the console keeps startup,
failure, model-summary, and repeat-summary lines readable. Use
`-Dfoliabytecodebridge.consoleVerbose=true` when you intentionally want the old
noisy console behavior. The debug file is the main laboratory notebook; keep it
when investigating route behavior.

To let every legacy plugin pass Folia's `folia-supported` metadata gate on an experimental test server, add this to `plugins/FoliaBytecodeBridge/config.properties` or as a JVM property:

```properties
metadataOverlay=all
```

If the default config file is missing, FBB regenerates
`plugins/FoliaBytecodeBridge/config.properties` during early startup and writes
the experimental defaults used by the live test harness, including
`metadataOverlay=all`, `debugFile=true`, `debugFileVerbose=true`, and
`consoleVerbose=false`. JVM properties still win over the regenerated file.

This overlay requires the `-javaagent` startup path for plugins that would otherwise be rejected before Bukkit loads them. It logs `[FBB metadata]` once for the patched metadata class and scans plugin jars up front with `result=already-supported` or `result=overlay-will-force-true`. Treat that as "the transformer got a chance to work," not as proof that the plugin is thread-safe.

## Expected Startup

Healthy startup should include the Bukkit plugin loading and should not print:

```text
The plugin loaded, but the bytecode transformer is not installed.
```

If that warning appears, the jar was loaded only as a plugin. Add the `-javaagent` flag.

With experimental self-attach, healthy startup can also include:

```text
[FBB attach] mode=SELF_ATTACH installed=true phase=onLoad
[FBB attach] mode=SELF_ATTACH retransformCandidates=90 retransformAttempted=90 retransformFailed=0
```

If self-attach fails, preserve the `[FBB attach-warning]` line. It records whether
the helper could not start, the JDK attach API was unavailable, or the helper
exited before the `foliabytecodebridge.agentInstalled` property was set.

If self-attach succeeds after some plugin classes were already loaded, look for
raw scheduler patch lines before the target plugins enable:

```text
[FBB transform] class=pk.ajneb97.tasks.InventoryUpdateTaskManager loader=org.bukkit.plugin.java.PluginClassLoader path=raw-scheduler result=patched
```

Those lines prove the late attach path reprocessed already-loaded plugin jar
classes instead of only waiting for future class loads.

The plugin checks the `foliabytecodebridge.agentInstalled` JVM property because Java agents and Bukkit plugins can be loaded by separate classloaders on Paperclip/Folia.

With `-Dfoliabytecodebridge.traceTransformSkips=true`, early Paperclip classes can be reported as:

```text
[FBB transform-skip] ... reason=bukkit-api-not-visible-yet
```

That is normal during the launcher phase. The bridge waits until Bukkit API classes are visible before building the Bukkit method substitutions, which avoids noisy bootstrap errors that are not caused by the target plugins.

The raw fallback transformer is the preferred real-server route because it does not need to load Bukkit API classes into the agent classloader. `foliabytecodebridge.appendServerLibraries=true` exists only for controlled Byte Buddy diagnostics and can cause classpath conflicts on Paperclip/Folia.

## First Tests

Start with one target plugin at a time.

Recommended order:

1. Boot Folia with only this bridge and the target plugin.
2. Confirm the target plugin enables.
3. Trigger one simple command from the target plugin.
4. Trigger one delayed or repeating task feature.
5. Watch for Folia region ownership errors.

## Evidence Planner

Use the evidence planner before or after a live run when you want a cleaner
picture of what the bridge should see.

```powershell
powershell -ExecutionPolicy Bypass -File tools\fbb-evidence.ps1
```

By default the planner skips `FoliaBytecodeBridge.jar`, `FBBProbe.jar`, and
`FBBProbeControl.jar` so the expected-vs-observed report focuses on target
plugins. Add `-IncludeToolingJars` when debugging the bridge/probe jars
themselves.

The planner does two separate things:

1. Scans jars in the live `plugins` folder for bytecode shapes already known to
   `RouteRuleRegistry` / `InstructionRouteScanner`.
2. Summarizes the current `logs/latest.log` for `[FBB scheduler]`,
   `[FBB unsafe-call]`, `[FBB unsafe-failure]`, `[FBB task-failure]`, model,
   transform, guard, teleport, and metadata evidence.

The output is deliberately phrased as evidence, not a safety verdict:

```text
[FBB preflight-plugin] ... routeHits=...
[FBB preflight-route] ... route=G_WORLD_SCAN_SPLIT ... confidence=exact-route-rule ... policy=SPLIT_AGGREGATE_RETURN ...
[FBB log-summary] ... tags="scheduler=..." routes="A_ENTITY=..."
[FBB log-evidence] ...
[FBB expected-observed] ... observed=observed-exact|observed-plugin-family|observed-family-only|not-exercised|server-start-blocked ...
[FBB expected-summary] ... observedExact=... notExercised=...
```

This is the useful part of a "sham terminal" workflow for this project: it tells
us which route families should be exercised, then lets the real Folia log prove
which paths actually ran or failed. If the preflight report predicts a route but
the log never shows it, trigger the plugin feature or add a focused probe. If the
log shows an unsupported path that preflight did not predict, add a route-model
entry or a scanner diagnostic before promoting a transformer rule.

Prediction confidence is intentionally explicit:

* `exact-route-rule` means the owner/name/descriptor exists in the central route
  registry.
* `scanner-observed-shape` means the read-only scanner classified the bytecode
  shape, but it is not yet a central exact rule.
* `dynamic-teleport-shape` means the call looks like a direct or shaded
  teleport-family path; the raw teleport transformer still decides whether the
  live class is rewritten, trace-only, or missed.

Expected-vs-observed matching is conservative. `observed-exact` requires route
and method/owner evidence in the log. `observed-plugin-family` means the same
route family ran for that plugin, but the report needs a narrower trigger before
we call the exact bytecode path proven. `server-start-blocked` usually means
Folia failed before runtime bridge evidence, such as a locked world directory.

`NMS_VERSION_COMPAT` evidence is separate from route-family evidence.
`[FBB preflight-nms]` lists server-internal members referenced by plugin
bytecode. `[FBB compat]` appears when the live log contains a linkage failure
such as `NoSuchFieldError`, `NoSuchMethodError`, `NoClassDefFoundError`, or
`ClassNotFoundException`. Use those rows to compare expected
owner/name/descriptor against the running server jar before adding any adapter;
do not fold those failures into `A_ENTITY`, `B_REGION_LOCATION`, or the other
ownership routes unless the failing call is also a normal Bukkit API guard.

Pass `--server-root <server>` to add `[FBB member-map]` rows. The tool scans
the extracted runtime server jars first, then cache/libraries/launcher jars, and
reports whether the expected member exists plus nearby field or method
candidates. Use this to decide what information an adapter still needs; a
candidate is not a rewrite approval.

Pass `--paper-root <Paper source folder or Paper-main.zip>` when researching
synthetic NMS compatibility. The planner compares plugin references against the
live Folia jars and Paper reference source, then emits
`[FBB synthetic-candidate]` and `[FBB synthetic-summary]` rows. The default
PowerShell helper looks for `Paper-main`, `<downloads>\Paper-main`,
and `<downloads>\Paper-main.zip`.

Treat this as an adapter research map:

* fields with matching Paper source are candidates for a Folia-equivalent map,
  then a synthetic field only if behavior can be preserved
* methods are behavior adapters first, not blind synthetic methods
* owner-shape mismatches are version/package mapping work before any adapter
* live exact matches are suppressed from candidate output so the report focuses
  on real gaps

## Local Smoke Test

The repository includes `smoke-test/src/smoketest`. It does not start a server; it proves the Java agent rewrites synthetic Bukkit scheduler calls, `BukkitRunnable` calls, high-risk direct Bukkit calls, and scans built target plugin jars for matching reusable bytecode shapes.

Expected output:

```text
SMOKE_OK bridgeCalls=22 unsafeCalls=95 bytecodeJars=2 bytecodeClasses=2394 bytecodeRequiredHits=79 knownGapHits={world-spawnEntity=2} rawInheritedOwnerHits=1 rawAnonymousOverrideHits=1 rawWrapperGuardHits=1
```

This means the Java agent transformed the fixture, routed scheduler calls through `SchedulerBridge`, routed high-risk direct calls through `UnsafeCallBridge`, proved the metadata overlay gate when `metadataOverlay=all` is supplied before `-javaagent`, and found the expected kit plugin reference/server-utility plugin reference bytecode call families in the built jars.

The local smoke command uses `-Dfoliabytecodebridge.forceNonFolia=true` because it is not running inside a real Bukkit/Folia server. It also uses `-Dfoliabytecodebridge.smokeNoPassthrough=true` so inherited `BukkitRunnable` calls can be tested without Bukkit scheduler state.

## Reading Failures

Transformer failures usually happen during startup and mention Byte Buddy or instrumentation.

Runtime bridge failures usually happen when a scheduled task runs and include:

```text
failed after bytecode scheduling bridge
```

Folia region ownership failures mean the bridge successfully moved the scheduler call, but the plugin still touched region-owned data from the wrong scheduler.

## Metadata Overlay

For legacy plugins that Folia rejects before class loading, prefer `metadataOverlay=all` on a controlled smoke server instead of editing plugin jars. The old metadata patch script is kept only as a manual debug tool for comparing jar contents. The overlay keeps the evidence in server logs and avoids maintaining patched plugin copies.

## Known Good Target Pattern

This bridge is most likely to help plugins that use Bukkit scheduler calls for simple global bookkeeping, chat messages, update checks, command follow-ups, or async network/database checks.

## Known Bad Target Pattern

This bridge is least likely to help plugins that schedule a global task and then edit blocks, teleport players, mutate entities, regenerate chunks, or call NMS internals without a player/entity/location context.
