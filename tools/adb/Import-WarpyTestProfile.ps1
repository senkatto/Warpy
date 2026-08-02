[CmdletBinding()]
param(
    [string]$Adb = "C:\adb\adb.exe",
    [Parameter(Mandatory)]
    [string]$Serial,
    [Parameter(Mandatory)]
    [string]$Profile,
    [Parameter(Mandatory)]
    [string]$ExpectedName
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path -LiteralPath $Adb)) { throw "ADB was not found at $Adb" }

$profileBase64 = ([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Profile))).TrimEnd("=").Replace("+", "-").Replace("/", "_")

$output = & $Adb -s $Serial shell am instrument -w -r `
    -e class 'com.warpy.app.WarpyUiSmokeTest#importProfileFromInstrumentationArgument' `
    -e profileBase64 $profileBase64 `
    -e profileName $ExpectedName `
    com.warpy.app.test/androidx.test.runner.AndroidJUnitRunner 2>&1
$output | Write-Host

if ($LASTEXITCODE -ne 0 -or
    ($output -join "`n") -match "Process crashed|FAILURES!!!|shortMsg=" -or
    ($output -join "`n") -notmatch "OK \(") {
    throw "Warpy profile import test failed"
}
