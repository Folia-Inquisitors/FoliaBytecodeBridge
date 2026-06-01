param(
    [string]$ServerRoot = "<server-root>",
    [string]$BridgeJar = "",
    [string]$PaperRoot = "",
    [int]$MaxExamples = 3,
    [int]$MaxLogLines = 80,
    [switch]$IncludeToolingJars
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
if ([string]::IsNullOrWhiteSpace($BridgeJar)) {
    $BridgeJar = Join-Path $projectRoot "target\FoliaBytecodeBridge.jar"
}

$pluginsDir = Join-Path $ServerRoot "plugins"
$latestLog = Join-Path $ServerRoot "logs\latest.log"
if ([string]::IsNullOrWhiteSpace($PaperRoot)) {
    $workspaceRoot = Split-Path -Parent $projectRoot
    $candidates = @(
        (Join-Path $workspaceRoot "Paper-main"),
        "<downloads>\Paper-main",
        "<downloads>\Paper-main.zip"
    )
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate) {
            $PaperRoot = $candidate
            break
        }
    }
}

if (-not (Test-Path -LiteralPath $BridgeJar)) {
    throw "Bridge jar not found: $BridgeJar. Build first with: mvn package"
}

$projectClasses = Join-Path $projectRoot "target\classes"
$classpath = $BridgeJar
if (Test-Path -LiteralPath $projectClasses) {
    $classpath = "$projectClasses;$BridgeJar"
}
$byteBuddyJar = Join-Path $env:USERPROFILE ".m2\repository\net\bytebuddy\byte-buddy\1.17.5\byte-buddy-1.17.5.jar"
if (Test-Path -LiteralPath $byteBuddyJar) {
    $classpath = "$classpath;$byteBuddyJar"
}

$toolArgs = @(
    "-cp", $classpath,
    "dev.foliabytecodebridge.FbbEvidenceTool",
    "--plugins", $pluginsDir,
    "--log", $latestLog,
    "--server-root", $ServerRoot,
    "--max-examples", "$MaxExamples",
    "--max-log-lines", "$MaxLogLines"
)

if (-not [string]::IsNullOrWhiteSpace($PaperRoot)) {
    $toolArgs += @("--paper-root", $PaperRoot)
}

if ($IncludeToolingJars) {
    $toolArgs += "--include-tooling-jars"
}

# This is an evidence planner, not a safety verdict. It predicts route families
# from plugin bytecode and then summarizes the server log that proves what
# actually happened on Folia. The Paper root is only used to identify possible
# synthetic-member research targets; it does not make a member safe by itself.
& java @toolArgs
