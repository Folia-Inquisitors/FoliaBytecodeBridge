param(
    [string] $LivePluginsPath = "",
    [switch] $SkipTests
)

$ErrorActionPreference = "Stop"

Set-Location (Resolve-Path (Join-Path $PSScriptRoot ".."))

$buildInfoPath = "src/main/java/dev/foliabytecodebridge/BridgeBuildInfo.java"
$buildInfoText = Get-Content -LiteralPath $buildInfoPath -Raw
$markerMatch = [regex]::Match($buildInfoText, 'BUILD_ID\s*=\s*"([^"]+)"')
if (-not $markerMatch.Success) {
    throw "Could not read BridgeBuildInfo.BUILD_ID from $buildInfoPath"
}
$expectedMarker = $markerMatch.Groups[1].Value

$maven = Get-Command mvn -ErrorAction SilentlyContinue
if ($null -eq $maven) {
    throw "Maven was not found on PATH. Install Maven or add mvn.cmd to PATH before building the release jar."
}

$arguments = @("clean", "package")
if ($SkipTests) {
    $arguments += "-DskipTests"
}

& $maven.Source @arguments
if ($LASTEXITCODE -ne 0) {
    throw "Maven build failed with exit code $LASTEXITCODE"
}

$jar = Get-ChildItem -Path "target" -Filter "folia-bytecode-bridge-*.jar" |
        Where-Object { $_.Name -notmatch "original|sources|javadoc" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
if ($null -eq $jar) {
    throw "Could not find built FoliaBytecodeBridge jar under target/"
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($jar.FullName)
try {
    $entry = $zip.GetEntry("dev/foliabytecodebridge/BridgeBuildInfo.class")
    if ($null -eq $entry) {
        throw "Built jar does not contain BridgeBuildInfo.class"
    }
    $stream = $entry.Open()
    try {
        $memory = New-Object System.IO.MemoryStream
        $stream.CopyTo($memory)
        $text = [System.Text.Encoding]::GetEncoding("ISO-8859-1").GetString($memory.ToArray())
        if (-not $text.Contains($expectedMarker)) {
            throw "Built jar does not contain the expected build marker: $expectedMarker"
        }
    } finally {
        $stream.Dispose()
    }
} finally {
    $zip.Dispose()
}

Write-Host "Built $($jar.FullName)"

if ($LivePluginsPath -ne "") {
    New-Item -ItemType Directory -Force -Path $LivePluginsPath | Out-Null
    Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $LivePluginsPath "FoliaBytecodeBridge.jar") -Force
    Write-Host "Copied to $(Join-Path $LivePluginsPath "FoliaBytecodeBridge.jar")"
}
