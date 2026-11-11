param(
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    throw 'JAVA_HOME is not set. Pass -JavaHome <path-to-jdk25> or set JAVA_HOME.'
}

$javac = Join-Path $JavaHome 'bin\javac.exe'
if (-not (Test-Path $javac)) {
    throw "javac not found under JAVA_HOME: $javac"
}

$sourceRoot = Join-Path $PSScriptRoot 'src\main\java'
$outputRoot = Join-Path $PSScriptRoot 'build\classes'

if (-not (Test-Path $sourceRoot)) {
    throw "Source root not found: $sourceRoot"
}

if (Test-Path $outputRoot) {
    Remove-Item -Recurse -Force $outputRoot
}
New-Item -ItemType Directory -Force $outputRoot | Out-Null

$sources = @(Get-ChildItem -Path $sourceRoot -Recurse -File -Filter '*.java' |
    ForEach-Object { $_.FullName })

if ($sources.Count -eq 0) {
    throw "No Java sources found under $sourceRoot"
}

Write-Host "Compiling $($sources.Count) source file(s) with $javac"
& $javac -encoding UTF-8 --release 25 '-Xlint:all' -d $outputRoot @sources
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Write-Host "Compiled classes: $outputRoot"
