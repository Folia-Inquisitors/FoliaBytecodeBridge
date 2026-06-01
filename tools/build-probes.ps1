param(
    [string]$ServerRoot = "",
    [string]$BridgeJar = "target\folia-bytecode-bridge-0.1.1-experimental.3.jar",
    [string]$OutputDir = "target"
)

$ErrorActionPreference = "Stop"

function Resolve-Tool {
    param(
        [string]$Name,
        [string]$JavaHome
    )

    if ($JavaHome) {
        $candidate = Join-Path $JavaHome "bin\$Name.exe"
        if (Test-Path -LiteralPath $candidate) {
            return $candidate
        }
    }

    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    $javaCommand = Get-Command java -ErrorAction SilentlyContinue
    if ($javaCommand) {
        $javaPath = Resolve-Path -LiteralPath $javaCommand.Source
        $candidate = Join-Path (Split-Path -Parent $javaPath) "$Name.exe"
        if (Test-Path -LiteralPath $candidate) {
            return $candidate
        }
    }

    $roots = @(
        (Join-Path $env:ProgramFiles "Java"),
        (Join-Path $env:ProgramFiles "Eclipse Adoptium"),
        (Join-Path $env:ProgramFiles "Microsoft")
    ) | Where-Object { $_ -and (Test-Path -LiteralPath $_) }
    foreach ($root in $roots) {
        $candidate = Get-ChildItem -Path $root -Recurse -Filter "$Name.exe" -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($candidate) {
            return $candidate.FullName
        }
    }

    throw "Could not locate $Name.exe. Install a JDK or set JAVA_HOME."
}

if (-not $ServerRoot) {
    $ServerRoot = $env:FBB_SERVER_ROOT
}
if (-not $ServerRoot) {
    throw "Pass -ServerRoot <path> or set FBB_SERVER_ROOT so probe compilation can find Folia libraries."
}
if (-not (Test-Path -LiteralPath $BridgeJar)) {
    throw "Bridge jar not found: $BridgeJar. Run Maven package first."
}

$javaHome = $env:JAVA_HOME
$javac = Resolve-Tool "javac" $javaHome
$jar = Resolve-Tool "jar" $javaHome

$libraries = Join-Path $ServerRoot "libraries"
if (-not (Test-Path -LiteralPath $libraries)) {
    throw "Server libraries directory not found: $libraries"
}

$stamp = Get-Date -Format "yyyyMMddHHmmss"
$classes = Join-Path $OutputDir "probe-classes-$stamp"
$mainStage = Join-Path $OutputDir "probe-stage-main-$stamp"
$controlStage = Join-Path $OutputDir "probe-stage-control-$stamp"

New-Item -ItemType Directory -Force -Path $classes, $mainStage, $controlStage | Out-Null

$sources = Get-ChildItem -Recurse -File -LiteralPath "probe-plugin\src\main\java" -Filter "*.java" |
    ForEach-Object { $_.FullName }
$libJars = Get-ChildItem -Recurse -File -LiteralPath $libraries -Filter "*.jar" |
    ForEach-Object { $_.FullName }

$classpath = @($BridgeJar) + $libJars
& $javac -cp ($classpath -join ";") -d $classes $sources

Copy-Item -Path (Join-Path $classes "*") -Destination $mainStage -Recurse -Force
Copy-Item -Path (Join-Path $classes "*") -Destination $controlStage -Recurse -Force

Copy-Item -LiteralPath "probe-plugin\src\main\resources\plugin.yml" `
    -Destination (Join-Path $mainStage "plugin.yml") -Force
Copy-Item -LiteralPath "probe-plugin\src\control\resources\plugin.yml" `
    -Destination (Join-Path $controlStage "plugin.yml") -Force

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
& $jar --create --file (Join-Path $OutputDir "FBBProbe.jar") -C $mainStage .
& $jar --create --file (Join-Path $OutputDir "FBBProbeControl.jar") -C $controlStage .

Write-Host "Built $(Join-Path $OutputDir 'FBBProbe.jar')"
Write-Host "Built $(Join-Path $OutputDir 'FBBProbeControl.jar')"
