param(
    [string]$ProfileName = "Fabric 26.3",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

# Avoid querying every Windows process through WMI; that can stall under memory
# pressure. Replacing a loaded mod jar is unsafe, so require Minecraft to be closed.
$minecraftProcess = Get-Process -Name "java", "javaw", "Minecraft" -ErrorAction SilentlyContinue |
    Where-Object { $_.ProcessName -eq "Minecraft" -or $_.MainWindowTitle -match "Minecraft|Seed Explorer" }
if ($minecraftProcess) {
    Write-Error "Minecraft appears to be running. Close the game before installing Seed Explorer."
}

$projectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$profileDir = Join-Path $env:APPDATA "ModrinthApp\profiles\$ProfileName"
$modsDir = Join-Path $profileDir "mods"
$jarName = "seed-explorer-1.0.0.jar"
$builtJar = Join-Path $projectDir "standalone\build\libs\$jarName"
$targetJar = Join-Path $modsDir $jarName
$legacyJar = Join-Path $modsDir "meteor-seed-explorer-1.0.0.jar"
$tempJar = "$targetJar.tmp-$PID"
$backupJar = "$targetJar.bak-$PID"

Add-Type -AssemblyName System.IO.Compression.FileSystem

function Get-JarEntries([string]$Path) {
    $archive = [System.IO.Compression.ZipFile]::OpenRead($Path)
    try {
        return @($archive.Entries | ForEach-Object { $_.FullName })
    } finally {
        $archive.Dispose()
    }
}

if (!(Test-Path -LiteralPath $profileDir -PathType Container)) {
    throw "Modrinth profile does not exist: $profileDir"
}

if (Test-Path -LiteralPath $legacyJar) {
    throw "The legacy Meteor addon is still installed: $legacyJar. Remove it before installing the standalone mod."
}

Push-Location $projectDir
try {
    if (!$SkipBuild) {
        & .\gradlew.bat -p standalone build --console=plain
        if ($LASTEXITCODE -ne 0) {
            throw "Standalone Gradle build failed with exit code $LASTEXITCODE."
        }
    }

    if (!(Test-Path -LiteralPath $builtJar -PathType Leaf)) {
        throw "Verified standalone jar is missing: $builtJar"
    }

    try {
        $entries = Get-JarEntries $builtJar
    } catch {
        throw "Built standalone jar is not a valid jar: $builtJar"
    }
    if ($entries -notcontains "me/seedexplorer/standalone/SeedExplorerFabricClient.class") {
        throw "Built jar is missing the standalone Fabric entrypoint."
    }
    if ($entries -contains "me/seedexplorer/addon/MeteorSeedExplorerAddon.class") {
        throw "Refusing to install a legacy Meteor addon as the standalone mod."
    }

    New-Item -ItemType Directory -Force -Path $modsDir | Out-Null
    Copy-Item -LiteralPath $builtJar -Destination $tempJar -Force

    try {
        Get-JarEntries $tempJar | Out-Null
    } catch {
        throw "Temporary copied jar is invalid: $tempJar"
    }

    if (Test-Path -LiteralPath $targetJar) {
        [System.IO.File]::Replace($tempJar, $targetJar, $backupJar, $true)
        Remove-Item -LiteralPath $backupJar -Force -ErrorAction SilentlyContinue
    } else {
        Move-Item -LiteralPath $tempJar -Destination $targetJar
    }

    $builtHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $builtJar).Hash
    $installedHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $targetJar).Hash
    if ($builtHash -ne $installedHash) {
        throw "Installed jar hash does not match the verified standalone build."
    }

    Write-Output "Installed standalone Seed Explorer to '$ProfileName': $targetJar"
} finally {
    Pop-Location
    Remove-Item -LiteralPath $tempJar -Force -ErrorAction SilentlyContinue
}
