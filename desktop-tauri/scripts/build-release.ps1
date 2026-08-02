[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$repositoryRoot = Split-Path -Parent $projectRoot
$loadedLocalKey = $false
$passwordPointer = [IntPtr]::Zero

try {
    & npm ci --ignore-scripts --dry-run --no-audit --no-fund
    if ($LASTEXITCODE -ne 0) {
        throw "Dependency lock validation failed with exit code $LASTEXITCODE."
    }

    & npm test
    if ($LASTEXITCODE -ne 0) {
        throw "JavaScript tests failed with exit code $LASTEXITCODE."
    }

    if ([string]::IsNullOrWhiteSpace($env:TAURI_SIGNING_PRIVATE_KEY)) {
        $keyDirectory = Join-Path $repositoryRoot 'warpy-keys'
        $keyPath = Join-Path $keyDirectory 'warpy-updater.key'
        $passwordPath = Join-Path $keyDirectory 'warpy-updater.password.dpapi'
        if (-not (Test-Path -LiteralPath $keyPath) -or -not (Test-Path -LiteralPath $passwordPath)) {
            throw 'Local updater signing key is not configured.'
        }

        $protectedPassword = (Get-Content -Raw -LiteralPath $passwordPath).Trim()
        $securePassword = ConvertTo-SecureString $protectedPassword
        $passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
        $env:TAURI_SIGNING_PRIVATE_KEY_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
        $env:TAURI_SIGNING_PRIVATE_KEY = (Get-Content -Raw -LiteralPath $keyPath).Trim()
        $loadedLocalKey = $true
    }

    & npx tauri build --config src-tauri/tauri.updater.conf.json
    if ($LASTEXITCODE -ne 0) {
        throw "Tauri release build failed with exit code $LASTEXITCODE."
    }
}
finally {
    if ($passwordPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    }
    if ($loadedLocalKey) {
        Remove-Item Env:TAURI_SIGNING_PRIVATE_KEY -ErrorAction SilentlyContinue
        Remove-Item Env:TAURI_SIGNING_PRIVATE_KEY_PASSWORD -ErrorAction SilentlyContinue
    }
}
