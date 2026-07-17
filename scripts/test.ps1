[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot 'build.ps1')

$JavaHome = $env:JAVA_HOME
if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    $JavaPath = (Get-Command java -ErrorAction Stop).Source
} else {
    $JavaPath = Join-Path $JavaHome 'bin\java.exe'
}

& $JavaPath -ea -cp "$(Join-Path $ProjectRoot 'build\main');$(Join-Path $ProjectRoot 'build\test')" `
    com.delrisco.trap21.Trap21IntegrationTest
if ($LASTEXITCODE -ne 0) { throw 'TRAP21 integration tests failed.' }
