param(
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    throw 'JAVA_HOME is not set. Pass -JavaHome <path-to-jdk25> or set JAVA_HOME.'
}

$javac = Join-Path $JavaHome 'bin\javac.exe'
$java = Join-Path $JavaHome 'bin\java.exe'
if (-not (Test-Path $javac) -or -not (Test-Path $java)) {
    throw "JDK tools not found under JAVA_HOME: $JavaHome"
}

$mainRoot = Join-Path $PSScriptRoot 'src\main\java'
$testRoot = Join-Path $PSScriptRoot 'src\test\java'
$mainOutput = Join-Path $PSScriptRoot 'build\test-main-classes'
$testOutput = Join-Path $PSScriptRoot 'build\test-classes'

foreach ($output in @($mainOutput, $testOutput)) {
    if (Test-Path $output) { Remove-Item -Recurse -Force $output }
    New-Item -ItemType Directory -Force $output | Out-Null
}

$mainSources = @(Get-ChildItem $mainRoot -Recurse -File -Filter '*.java' |
    ForEach-Object { $_.FullName })
$testSources = @(Get-ChildItem $testRoot -Recurse -File -Filter '*.java' |
    ForEach-Object { $_.FullName })

& $javac -encoding UTF-8 --release 25 '-Xlint:all' -d $mainOutput @mainSources
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $javac -encoding UTF-8 --release 25 '-Xlint:all' -classpath $mainOutput -d $testOutput @testSources
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $java -ea -classpath "$mainOutput;$testOutput" me.seedexplorer.seedcracking.SeedCrackingSelfTest
exit $LASTEXITCODE
