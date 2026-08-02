[CmdletBinding()]
param(
    [string]$Adb = "C:\adb\adb.exe",
    [string]$Serial = "",
    [ValidateRange(1, 1000)]
    [int]$Cycles = 3,
    [ValidateRange(1, 86400)]
    [int]$BackgroundSeconds = 3,
    [ValidateRange(1, 86400)]
    [int]$LockSeconds = 5,
    [switch]$ForceDoze,
    [ValidateRange(1, 86400)]
    [int]$DozeSeconds = 15,
    [bool]$TestProfileSwitch = $true,
    [bool]$EnsureConnected = $true
)

$ErrorActionPreference = "Stop"
$Package = "com.warpy.app"
$Activity = "$Package/.MainActivity"
$UiDumpPath = "/sdcard/warpy-device-test.xml"
$VpnOn = "on"
$VpnOff = "off"
$ReportRoot = Join-Path $PSScriptRoot "..\..\build\device-tests"
$RunName = Get-Date -Format "yyyyMMdd-HHmmss"
$ReportDirectory = Join-Path $ReportRoot $RunName
$LogFile = Join-Path $ReportDirectory "test.log"
$DozeForced = $false

function Resolve-DeviceSerial {
    if (-not (Test-Path -LiteralPath $Adb)) {
        throw "ADB was not found at $Adb"
    }
    if ($Serial) {
        return $Serial
    }

    $devices = & $Adb devices | Select-Object -Skip 1 | ForEach-Object {
        $parts = ($_ -split "\s+")
        if ($parts.Count -ge 2 -and $parts[1] -eq "device") { $parts[0] }
    }
    $devices = @($devices | Where-Object { $_ })
    if ($devices.Count -ne 1) {
        throw "Expected exactly one authorized device, found $($devices.Count). Pass -Serial explicitly."
    }
    return $devices[0]
}

function Invoke-Adb {
    param([Parameter(Mandatory)][string[]]$Arguments)

    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & $Adb -s $script:DeviceSerial @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorAction
    }
    if ($exitCode -ne 0) {
        throw "ADB failed: adb $($Arguments -join ' ')`n$($output -join [Environment]::NewLine)"
    }
    return ($output | Out-String).TrimEnd()
}

function Write-Step {
    param([Parameter(Mandatory)][string]$Message)

    $line = "[$(Get-Date -Format 'HH:mm:ss')] $Message"
    Write-Host $line
    Add-Content -LiteralPath $LogFile -Value $line -Encoding utf8
}

function Get-WarpyUi {
    Invoke-Adb -Arguments @("shell", "uiautomator", "dump", $UiDumpPath) | Out-Null
    $localPath = Join-Path $ReportDirectory "ui-current.xml"
    Invoke-Adb -Arguments @("pull", $UiDumpPath, $localPath) | Out-Null
    $raw = Get-Content -LiteralPath $localPath -Raw -Encoding utf8
    $xmlStart = $raw.IndexOf("<?xml")
    if ($xmlStart -lt 0) {
        throw "UI Automator did not return XML"
    }
    return [xml]$raw.Substring($xmlStart)
}

function Find-ConnectionNode {
    param(
        [Parameter(Mandatory)][xml]$Ui,
        [Parameter(Mandatory)][ValidateSet("on", "off")][string]$State
    )

    return $Ui.SelectNodes("//node") | Where-Object {
        $description = [string]$_.'content-desc'
        $isConnectionControl = $description.EndsWith("VPN")
        $isOnControl = $description.Length -gt 12
        $isConnectionControl -and (($State -eq "on" -and $isOnControl) -or ($State -eq "off" -and -not $isOnControl))
    } | Select-Object -First 1
}

function Find-ProfileSummaryNode {
    param([Parameter(Mandatory)][xml]$Ui)

    $protocols = @("VLESS", "Hysteria2", "Trojan")
    return $Ui.SelectNodes("//node[@clickable='true']") | Where-Object {
        $texts = @($_.SelectNodes(".//node") | ForEach-Object { [string]$_.text } | Where-Object { $_ })
        ($texts | Where-Object { $_ -in $protocols }).Count -gt 0 -and
        ($texts | Where-Object { $_ -match ':\d+$' }).Count -eq 0
    } | Select-Object -First 1
}

function Find-ProfileRows {
    param([Parameter(Mandatory)][xml]$Ui)

    $protocols = @("VLESS", "Hysteria2", "Trojan")
    return @($Ui.SelectNodes("//node[@clickable='true']") | Where-Object {
        $texts = @($_.SelectNodes(".//node") | ForEach-Object { [string]$_.text } | Where-Object { $_ })
        ($texts | Where-Object { $_ -in $protocols }).Count -gt 0 -and
        ($texts | Where-Object { $_ -match ':\d+$' }).Count -gt 0
    })
}

function Get-ProfileNodeName {
    param([Parameter(Mandatory)]$Node)

    $protocols = @("VLESS", "Hysteria2", "Trojan")
    return $Node.SelectNodes(".//node") | ForEach-Object { [string]$_.text } | Where-Object {
        $_ -and $_ -notin $protocols -and $_ -notmatch ':\d+$'
    } | Select-Object -First 1
}

function Invoke-NodeTap {
    param([Parameter(Mandatory)]$Node)

    $bounds = [string]$Node.bounds
    if ($bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') {
        throw "Cannot parse node bounds: $bounds"
    }
    $x = [int]((($matches[1] -as [int]) + ($matches[3] -as [int])) / 2)
    $y = [int]((($matches[2] -as [int]) + ($matches[4] -as [int])) / 2)
    Invoke-Adb -Arguments @("shell", "input", "tap", [string]$x, [string]$y) | Out-Null
}

function Start-Warpy {
    Invoke-Adb -Arguments @("shell", "input", "keyevent", "KEYCODE_WAKEUP") | Out-Null
    Invoke-Adb -Arguments @("shell", "wm", "dismiss-keyguard") | Out-Null
    Invoke-Adb -Arguments @("shell", "am", "start", "-W", "-n", $Activity) | Out-Null
    Start-Sleep -Milliseconds 500
}

function Wait-WarpyState {
    param(
        [Parameter(Mandatory)][ValidateSet("on", "off")][string]$State,
        [int]$TimeoutSeconds = 25
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try {
            $ui = Get-WarpyUi
            if (Find-ConnectionNode -Ui $ui -State $State) {
                return $ui
            }
        } catch {
            Start-Sleep -Milliseconds 300
        }
        Start-Sleep -Milliseconds 400
    } while ((Get-Date) -lt $deadline)
    throw "Warpy did not reach VPN state '$State' in $TimeoutSeconds seconds"
}

function Save-Screenshot {
    param([Parameter(Mandatory)][string]$Name)

    $path = Join-Path $ReportDirectory "$Name.png"
    $devicePath = "/sdcard/warpy-$Name.png"
    Invoke-Adb -Arguments @("shell", "screencap", "-p", $devicePath) | Out-Null
    Invoke-Adb -Arguments @("pull", $devicePath, $path) | Out-Null
    Invoke-Adb -Arguments @("shell", "rm", $devicePath) | Out-Null
}

function Try-AcceptVpnPermission {
    $deadline = (Get-Date).AddSeconds(5)
    do {
        try {
            $ui = Get-WarpyUi
            $button = $ui.SelectNodes("//node") | Where-Object {
                $_.clickable -eq "true" -and
                $_.package -ne $Package -and
                ($_.text -in @("OK", "Allow") -or $_.'resource-id' -eq "android:id/button1")
            } | Select-Object -First 1
            if ($button) {
                Write-Step "Accepting the Android VPN permission dialog"
                Invoke-NodeTap -Node $button
                return
            }
        } catch { }
        Start-Sleep -Milliseconds 300
    } while ((Get-Date) -lt $deadline)
}

function Assert-VpnRuntime {
    $services = Invoke-Adb -Arguments @("shell", "dumpsys", "activity", "services", "$Package/.vpn.WarpyService")
    if ($services -notmatch "WarpyService" -or $services -notmatch "isForeground=true") {
        throw "WarpyService is not running as a foreground service"
    }

    $connectivity = Invoke-Adb -Arguments @("shell", "dumpsys", "connectivity")
    $vpnMarker = "ni{VPN CONNECTED extra: VPN:$Package}"
    $vpnStart = $connectivity.IndexOf($vpnMarker, [System.StringComparison]::Ordinal)
    if ($vpnStart -lt 0) {
        throw "Android does not report an active Warpy VPN network"
    }
    $nextNetwork = $connectivity.IndexOf("NetworkAgentInfo{", $vpnStart + $vpnMarker.Length, [System.StringComparison]::Ordinal)
    $vpnBlock = if ($nextNetwork -ge 0) {
        $connectivity.Substring($vpnStart, $nextNetwork - $vpnStart)
    } else {
        $connectivity.Substring($vpnStart)
    }
    if ($vpnBlock -notmatch "InterfaceName: tun0") {
        throw "The Warpy VPN network has no tun0 interface"
    }
    if ($vpnBlock -notmatch "IS_VALIDATED|&VALIDATED") {
        throw "Android reports the Warpy VPN network, but it is not validated"
    }
}

function Ensure-WarpyConnected {
    Start-Warpy
    $ui = Get-WarpyUi
    if (Find-ConnectionNode -Ui $ui -State $VpnOn) {
        Assert-VpnRuntime
        return
    }

    $connect = Find-ConnectionNode -Ui $ui -State $VpnOff
    if (-not $connect) {
        throw "The main connection control is not visible"
    }
    Write-Step "VPN is stopped; starting the configured profile"
    Invoke-NodeTap -Node $connect
    Try-AcceptVpnPermission
    Wait-WarpyState -State $VpnOn -TimeoutSeconds 30 | Out-Null
    Assert-VpnRuntime
}

function Invoke-BackgroundCycle {
    param([int]$Index)

    Write-Step "Background cycle $Index/$Cycles"
    Invoke-Adb -Arguments @("shell", "input", "keyevent", "KEYCODE_HOME") | Out-Null
    Start-Sleep -Seconds $BackgroundSeconds
    Assert-VpnRuntime
    Start-Warpy
    Wait-WarpyState -State $VpnOn | Out-Null
}

function Invoke-LockCycle {
    param([int]$Index)

    Write-Step "Lock cycle $Index/$Cycles"
    Invoke-Adb -Arguments @("shell", "input", "keyevent", "KEYCODE_SLEEP") | Out-Null
    Start-Sleep -Seconds $LockSeconds
    Assert-VpnRuntime
    Start-Warpy
    Start-Sleep -Seconds 2
    Assert-VpnRuntime
}

function Invoke-DozeTest {
    Write-Step "Entering forced Doze for $DozeSeconds seconds"
    Invoke-Adb -Arguments @("shell", "dumpsys", "battery", "unplug") | Out-Null
    Invoke-Adb -Arguments @("shell", "dumpsys", "deviceidle", "force-idle") | Out-Null
    $script:DozeForced = $true
    Start-Sleep -Seconds $DozeSeconds
    Assert-VpnRuntime
    Invoke-Adb -Arguments @("shell", "dumpsys", "deviceidle", "unforce") | Out-Null
    Invoke-Adb -Arguments @("shell", "dumpsys", "battery", "reset") | Out-Null
    $script:DozeForced = $false
    Start-Warpy
    Start-Sleep -Seconds 2
    Assert-VpnRuntime
}

function Invoke-ProfileSwitchTest {
    Write-Step "Testing active profile switching"
    Start-Warpy
    $ui = Wait-WarpyState -State $VpnOn
    $summary = Find-ProfileSummaryNode -Ui $ui
    if (-not $summary) {
        throw "Active profile summary is unavailable"
    }
    $initialName = Get-ProfileNodeName -Node $summary
    if (-not $initialName) {
        throw "Cannot determine the active profile name"
    }

    Invoke-NodeTap -Node $summary
    Start-Sleep -Milliseconds 700
    $rows = Find-ProfileRows -Ui (Get-WarpyUi)
    if ($rows.Count -lt 2) {
        Write-Step "Profile switch skipped: fewer than two profiles"
        Invoke-Adb -Arguments @("shell", "input", "keyevent", "KEYCODE_BACK") | Out-Null
        return
    }

    $target = $rows | Where-Object { (Get-ProfileNodeName -Node $_) -ne $initialName } | Select-Object -First 1
    if (-not $target) {
        throw "No alternate profile is available"
    }
    $targetName = Get-ProfileNodeName -Node $target
    Invoke-NodeTap -Node $target
    Start-Sleep -Seconds 1
    Wait-WarpyState -State $VpnOn -TimeoutSeconds 30 | Out-Null
    Assert-VpnRuntime
    Write-Step "Switched to profile '$targetName'"

    $ui = Get-WarpyUi
    $summary = Find-ProfileSummaryNode -Ui $ui
    Invoke-NodeTap -Node $summary
    Start-Sleep -Milliseconds 700
    $restore = Find-ProfileRows -Ui (Get-WarpyUi) | Where-Object {
        (Get-ProfileNodeName -Node $_) -eq $initialName
    } | Select-Object -First 1
    if (-not $restore) {
        throw "Cannot restore the initial profile '$initialName'"
    }
    Invoke-NodeTap -Node $restore
    Start-Sleep -Seconds 1
    Wait-WarpyState -State $VpnOn -TimeoutSeconds 30 | Out-Null
    Assert-VpnRuntime
    Write-Step "Restored profile '$initialName'"
}

function Save-Diagnostics {
    $logcat = Invoke-Adb -Arguments @("logcat", "-d", "-v", "threadtime")
    Set-Content -LiteralPath (Join-Path $ReportDirectory "logcat.txt") -Value $logcat -Encoding utf8
    Set-Content -LiteralPath (Join-Path $ReportDirectory "connectivity.txt") -Value (
        Invoke-Adb -Arguments @("shell", "dumpsys", "connectivity")
    ) -Encoding utf8
    Set-Content -LiteralPath (Join-Path $ReportDirectory "service.txt") -Value (
        Invoke-Adb -Arguments @("shell", "dumpsys", "activity", "services", "$Package/.vpn.WarpyService")
    ) -Encoding utf8
    Save-Screenshot -Name "final"

    $fatalPattern = "FATAL EXCEPTION|ANR in $([regex]::Escape($Package))|address already in use"
    if ($logcat -match $fatalPattern) {
        throw "Fatal app event was found in logcat; see $ReportDirectory\logcat.txt"
    }
}

$DeviceSerial = Resolve-DeviceSerial
New-Item -ItemType Directory -Path $ReportDirectory -Force | Out-Null
Set-Content -LiteralPath $LogFile -Value "Warpy device test $RunName" -Encoding utf8

try {
    Write-Step "Device: $DeviceSerial"
    Invoke-Adb -Arguments @("logcat", "-c") | Out-Null
    Start-Warpy
    if ($EnsureConnected) {
        Ensure-WarpyConnected
    }

    if ($EnsureConnected) {
        if ($TestProfileSwitch) {
            Invoke-ProfileSwitchTest
        }
        1..$Cycles | ForEach-Object { Invoke-BackgroundCycle -Index $_ }
        1..$Cycles | ForEach-Object { Invoke-LockCycle -Index $_ }
        if ($ForceDoze) {
            Invoke-DozeTest
        }
    }

    Save-Diagnostics
    Write-Step "PASS. Report: $ReportDirectory"
} catch {
    try { Save-Screenshot -Name "failure" } catch { }
    try { Save-Diagnostics } catch { }
    Write-Step "FAIL: $($_.Exception.Message)"
    throw
} finally {
    if ($DozeForced) {
        try { Invoke-Adb -Arguments @("shell", "dumpsys", "deviceidle", "unforce") | Out-Null } catch { }
        try { Invoke-Adb -Arguments @("shell", "dumpsys", "battery", "reset") | Out-Null } catch { }
    }
    try { Invoke-Adb -Arguments @("shell", "input", "keyevent", "KEYCODE_WAKEUP") | Out-Null } catch { }
}
