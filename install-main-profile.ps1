$ErrorActionPreference = "Stop"

$minecraftProcess = Get-CimInstance Win32_Process |
    Where-Object {
        ($_.Name -in @("javaw.exe", "Minecraft.exe")) -or
        (($_.Name -eq "java.exe") -and ($_.CommandLine -match "net\.fabricmc|minecraft|knot|ModrinthApp\\profiles\\main"))
    } |
    Where-Object { $_.CommandLine -notmatch "GradleDaemon|gradle" }

if ($minecraftProcess) {
    Write-Error "Minecraft appears to be running. Close the game before installing the Seed Explorer jar."
}

$projectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$jarName = "meteor-seed-explorer-1.0.0.jar"
$builtJar = Join-Path $projectDir "build\libs\$jarName"
$targetJar = Join-Path $env:APPDATA "ModrinthApp\profiles\main\mods\$jarName"
$tempJar = "$targetJar.tmp-$PID"
$backupJar = "$targetJar.bak-$PID"

Push-Location $projectDir
try {
    & .\gradlew.bat --no-daemon jar
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle jar task failed with exit code $LASTEXITCODE."
    }

    if (!(Test-Path $builtJar)) {
        throw "Built jar missing: $builtJar"
    }

    jar tf $builtJar | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Built jar is not a valid zip/jar: $builtJar"
    }

    $hasRenderer = jar tf $builtJar | Select-String "me/seedexplorer/addon/render/SeedRenderer.class"
    if (!$hasRenderer) {
        throw "Built jar is missing SeedRenderer.class."
    }

    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $targetJar) | Out-Null
    Copy-Item -LiteralPath $builtJar -Destination $tempJar -Force

    jar tf $tempJar | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Temporary copied jar is not valid: $tempJar"
    }

    if (Test-Path $targetJar) {
        [System.IO.File]::Replace($tempJar, $targetJar, $backupJar, $true)
        Remove-Item -LiteralPath $backupJar -Force -ErrorAction SilentlyContinue
    } else {
        Move-Item -LiteralPath $tempJar -Destination $targetJar -Force
    }

    jar tf $targetJar | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Installed jar failed validation: $targetJar"
    }

    $builtHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $builtJar).Hash
    $installedHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $targetJar).Hash
    if ($builtHash -ne $installedHash) {
        throw "Installed jar hash does not match built jar."
    }

    Write-Output "Installed $jarName to Modrinth main profile."
} finally {
    Pop-Location
    Remove-Item -LiteralPath $tempJar -Force -ErrorAction SilentlyContinue
}
