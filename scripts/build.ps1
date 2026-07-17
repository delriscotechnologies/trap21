[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$BuildRoot = Join-Path $ProjectRoot 'build'
$MainOutput = Join-Path $BuildRoot 'main'
$TestOutput = Join-Path $BuildRoot 'test'

$JavaHome = $env:JAVA_HOME
if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    $Javac = Get-Command javac -ErrorAction SilentlyContinue
    if ($null -eq $Javac) {
        throw 'JDK 21 was not found. Configure a Java runtime in VS Code or set JAVA_HOME.'
    }
    $JavacPath = $Javac.Source
    $JarPath = Join-Path (Split-Path -Parent $JavacPath) 'jar.exe'
} else {
    $JavacPath = Join-Path $JavaHome 'bin\javac.exe'
    $JarPath = Join-Path $JavaHome 'bin\jar.exe'
}

if (Test-Path -LiteralPath $BuildRoot) {
    Remove-Item -LiteralPath $BuildRoot -Recurse -Force
}
New-Item -ItemType Directory -Path $MainOutput, $TestOutput | Out-Null

$MainSources = Get-ChildItem -LiteralPath (Join-Path $ProjectRoot 'src\main\java') -Recurse -Filter '*.java' |
    Select-Object -ExpandProperty FullName
$TestSources = Get-ChildItem -LiteralPath (Join-Path $ProjectRoot 'src\test\java') -Recurse -Filter '*.java' |
    Select-Object -ExpandProperty FullName

$MainArguments = @('--release', '21', '-encoding', 'UTF-8', '-Xlint:all', '-d', $MainOutput) + $MainSources
& $JavacPath @MainArguments
if ($LASTEXITCODE -ne 0) { throw 'Main compilation failed.' }

$TestArguments = @(
    '--release', '21',
    '-encoding', 'UTF-8',
    '-Xlint:all',
    '-cp', $MainOutput,
    '-d', $TestOutput
) + $TestSources
& $JavacPath @TestArguments
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed.' }

$JarFile = Join-Path $BuildRoot 'trap21.jar'
& $JarPath --create --file $JarFile --main-class com.delrisco.trap21.Trap21Application -C $MainOutput .
if ($LASTEXITCODE -ne 0) { throw 'JAR creation failed.' }

Write-Host "Built $JarFile"
