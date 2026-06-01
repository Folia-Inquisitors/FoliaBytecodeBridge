# Smoke Test

This fixture proves the Java agent rewrites a normal class containing Bukkit scheduler calls and selected high-risk direct Bukkit calls.

The test does not boot a Minecraft server. It loads Paper API interfaces, starts the bridge as `-javaagent`, forces the runtime bridge into non-Folia pass-through mode with `-Dfoliabytecodebridge.forceNonFolia=true`, runs `SmokeTarget`, and checks that `SchedulerBridge` and `UnsafeCallBridge` saw the rewritten calls.

Expected output:

```text
SMOKE_OK bridgeCalls=22 unsafeCalls=95 bytecodeJars=2 bytecodeClasses=2394 bytecodeRequiredHits=79 knownGapHits={world-spawnEntity=2}
```

If either count is too low, the target class was not transformed for that call family. With debug enabled, the expected logs include scheduler callers, A/B/C route hints, unsafe-call family hints, unsafe-failure probable frames, and metadata overlay lines when `metadataOverlay=all` is supplied before the Java agent.

The teleport-family fixture also asserts `[FBB teleport-path]` lines for direct
Bukkit teleport calls, generic shaded `teleportAsync(Entity|Player,Location,TeleportCause)`
helpers, scheduler-helper `teleportAsync(Entity|Player,Location,...)` calls reported as
`rule=generic-helper-shape action=trace-only`, and an unsupported helper descriptor
reported as `rule=generic-shape-probe action=missed`.
