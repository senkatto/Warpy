[CmdletBinding()]
param(
    [string]$Adb = "C:\adb\adb.exe",
    [Parameter(Mandatory)]
    [string]$Serial,
    [ValidateRange(5, 120)]
    [int]$RecoverySeconds = 20
)

$ErrorActionPreference = "Stop"
$Package = "com.warpy.app"
$WifiDisabled = $false
$AirplaneEnabled = $false

if ($Serial.Contains(":")) {
    throw "Network handoff testing requires USB ADB; wireless ADB would disconnect when Wi-Fi is disabled."
}
if (-not (Test-Path -LiteralPath $Adb)) {
    throw "ADB was not found at $Adb"
}

function Invoke-Adb {
    param([Parameter(Mandatory)][string[]]$Arguments)

    $output = & $Adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "ADB failed: adb $($Arguments -join ' ')`n$($output -join [Environment]::NewLine)"
    }
    return ($output | Out-String).TrimEnd()
}

function Assert-VpnConnected {
    $connectivity = Invoke-Adb -Arguments @("shell", "dumpsys", "connectivity")
    $vpnMarker = "ni{VPN CONNECTED extra: VPN:$Package}"
    $vpnStart = $connectivity.IndexOf($vpnMarker, [System.StringComparison]::Ordinal)
    if ($vpnStart -lt 0) {
        throw "Warpy VPN did not recover"
    }
    $nextNetwork = $connectivity.IndexOf("NetworkAgentInfo{", $vpnStart + $vpnMarker.Length, [System.StringComparison]::Ordinal)
    $vpnBlock = if ($nextNetwork -ge 0) {
        $connectivity.Substring($vpnStart, $nextNetwork - $vpnStart)
    } else {
        $connectivity.Substring($vpnStart)
    }
    if ($vpnBlock -notmatch "InterfaceName: tun0" -or $vpnBlock -notmatch "IS_VALIDATED|&VALIDATED") {
        throw "Warpy VPN recovered without a validated tun0 network"
    }
}

try {
    Assert-VpnConnected

    Write-Host "Testing Wi-Fi to cellular handoff"
    Invoke-Adb -Arguments @("shell", "svc", "wifi", "disable") | Out-Null
    $WifiDisabled = $true
    Start-Sleep -Seconds $RecoverySeconds
    Assert-VpnConnected

    Write-Host "Testing cellular to Wi-Fi handoff"
    Invoke-Adb -Arguments @("shell", "svc", "wifi", "enable") | Out-Null
    $WifiDisabled = $false
    Start-Sleep -Seconds $RecoverySeconds
    Assert-VpnConnected

    Write-Host "Testing airplane-mode recovery"
    Invoke-Adb -Arguments @("shell", "cmd", "connectivity", "airplane-mode", "enable") | Out-Null
    $AirplaneEnabled = $true
    Start-Sleep -Seconds 10
    Invoke-Adb -Arguments @("shell", "cmd", "connectivity", "airplane-mode", "disable") | Out-Null
    $AirplaneEnabled = $false
    Start-Sleep -Seconds $RecoverySeconds
    Assert-VpnConnected

    Write-Host "PASS: all network handoffs recovered"
} finally {
    if ($AirplaneEnabled) {
        try { Invoke-Adb -Arguments @("shell", "cmd", "connectivity", "airplane-mode", "disable") | Out-Null } catch { }
    }
    if ($WifiDisabled) {
        try { Invoke-Adb -Arguments @("shell", "svc", "wifi", "enable") | Out-Null } catch { }
    }
}
