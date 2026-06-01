# ASM Route Scanner

`InstructionRouteScanner` is a read-only proof layer for exact bytecode route
evidence. It uses ASM visitors to inspect method calls and method-reference
bootstrap handles, then classifies owner/name/descriptor shapes with
`InstructionRouteRegistry`.

This is not a runtime transformer yet. The scanner exists to answer one
question before we add more risky rewrite rules:

```text
Can ASM see the exact bytecode shape and assign the expected route family?
```

## Current Smoke Result

```text
SMOKE_OK ... asmRouteHits=52
```

The smoke test scans `smoketest/SmokeTarget.class` and asserts representative
hits for:

- `A_ENTITY`: player potion effect add/remove.
- `C_REGION_BLOCK`: `Block#getType()` and `Block#setType(Material)`.
- `G_WORLD_SCAN_SPLIT`: whole-world entity scan.
- `D_PLAYER_UI`: detached scoreboard creation.
- `S_GLOBAL`: command dispatch.

## Optional Dependency Boundary

The Java-agent listener also uses the scanner when Byte Buddy reports:

```text
Cannot resolve type description for <missing optional api class>
```

That shape usually means a plugin bundled an integration class for a soft
dependency that is not installed on the test server. The bridge logs a concise
`[FBB transform-skip] reason=optional-dependency-missing` line and, when the
class bytes can still be read, appends the ASM route-family counts. This keeps
evidence visible without creating fake adapters for missing protection,
permissions, placeholder, or region plugins.

## Policy

Keep the scanner read-only until live logs prove a route is ready for a
preemptive bridge path. Once a route is proven, the registry entry can be shared
with a transformer rule or used to generate a safer bytecode inventory report
for uploaded plugin jars.
