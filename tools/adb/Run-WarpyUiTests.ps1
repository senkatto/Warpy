[CmdletBinding()]
param(
    [string]$Adb = "C:\adb\adb.exe",
    [string]$Serial = "",
    [string]$AppApk = "",
    [string]$TestApk = "",
    [string]$ProductionApk = ""
)

$ErrorActionPreference = "Stop"
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
if (-not $AppApk) {
    $AppApk = Join-Path $ProjectRoot "app\build\outputs\apk\deviceTest\app-deviceTest.apk"
}
if (-not $TestApk) {
    $TestApk = Join-Path $ProjectRoot "app\build\outputs\apk\androidTest\deviceTest\app-deviceTest-androidTest.apk"
}
if (-not $ProductionApk) {
    $ProductionApk = Join-Path $ProjectRoot "app\build\outputs\apk\production\app-production.apk"
}
if (-not (Test-Path -LiteralPath $Adb)) { throw "ADB was not found at $Adb" }
if (-not (Test-Path -LiteralPath $AppApk)) { throw "App APK was not found at $AppApk" }
if (-not (Test-Path -LiteralPath $TestApk)) { throw "Test APK was not found at $TestApk" }
if (-not (Test-Path -LiteralPath $ProductionApk)) { throw "Production APK was not found at $ProductionApk" }

if (-not $Serial) {
    $devices = & $Adb devices | Select-Object -Skip 1 | ForEach-Object {
        $parts = ($_ -split "\s+")
        if ($parts.Count -ge 2 -and $parts[1] -eq "device") { $parts[0] }
    }
    $devices = @($devices | Where-Object { $_ })
    if ($devices.Count -ne 1) {
        throw "Expected exactly one authorized device, found $($devices.Count). Pass -Serial explicitly."
    }
    $Serial = $devices[0]
}

try {
    Write-Host "Installing the release-equivalent deviceTest APK without clearing app data"
    & $Adb -s $Serial install -r -g $AppApk
    if ($LASTEXITCODE -ne 0) { throw "Device-test APK installation failed" }

    Write-Host "Installing instrumentation APK without Gradle uninstall hooks"
    & $Adb -s $Serial install -r -t $TestApk
    if ($LASTEXITCODE -ne 0) { throw "Instrumentation APK installation failed" }

    $smokeTests = @(
        "com.warpy.app.WarpyUiSmokeTest#topLevelSurfacesReturnToMainScreen"
        "com.warpy.app.WarpyUiSmokeTest#backgroundRoundTripKeepsRenderedConnectionState"
        "com.warpy.app.WarpyUiSmokeTest#connectedVpnServiceSurvivesBackground"
    ) -join ","

    Write-Host "Running Warpy UI smoke tests"
    $testOutput = & $Adb -s $Serial shell am instrument -w -r `
        -e class $smokeTests `
        com.warpy.app.test/androidx.test.runner.AndroidJUnitRunner 2>&1
    $testExitCode = $LASTEXITCODE
    $testOutput | Write-Host
    if ($testExitCode -ne 0 -or
        ($testOutput -join "`n") -match "Process crashed|FAILURES!!!|shortMsg=" -or
        ($testOutput -join "`n") -notmatch "OK \(") {
        throw "Warpy UI smoke tests failed"
    }
} finally {
    Write-Host "Removing the instrumentation package"
    & $Adb -s $Serial uninstall com.warpy.app.test | Out-Host

    Write-Host "Restoring the production APK without clearing app data"
    & $Adb -s $Serial install -r -g $ProductionApk
    if ($LASTEXITCODE -ne 0) { throw "Production APK restoration failed" }
}
