[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Artifact,
    [Parameter(Mandatory)][string]$Signature
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$artifactPath = (Resolve-Path -LiteralPath $Artifact).Path
$signaturePath = (Resolve-Path -LiteralPath $Signature).Path
$config = Get-Content -Raw -LiteralPath (Join-Path $projectRoot 'src-tauri\tauri.conf.json') | ConvertFrom-Json
$encodedPublicKey = [string]$config.plugins.updater.pubkey
$encodedSignature = (Get-Content -Raw -LiteralPath $signaturePath).Trim()

if ([string]::IsNullOrWhiteSpace($encodedPublicKey) -or [string]::IsNullOrWhiteSpace($encodedSignature)) {
    throw 'Updater public key or signature is empty.'
}

$temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$temporaryDirectory = [IO.Path]::GetFullPath((Join-Path $temporaryRoot "warpy-updater-verify-$PID-$([Guid]::NewGuid().ToString('N'))"))
if (-not $temporaryDirectory.StartsWith($temporaryRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Unsafe updater verification temporary path.'
}

try {
    New-Item -ItemType Directory -Path $temporaryDirectory | Out-Null
    $publicKeyPath = Join-Path $temporaryDirectory 'updater.pub'
    $decodedSignaturePath = Join-Path $temporaryDirectory 'artifact.sig'
    [IO.File]::WriteAllBytes($publicKeyPath, [Convert]::FromBase64String($encodedPublicKey))
    [IO.File]::WriteAllBytes($decodedSignaturePath, [Convert]::FromBase64String($encodedSignature))

    & cargo run --quiet --manifest-path (Join-Path $projectRoot 'src-tauri\Cargo.toml') --example verify_updater_signature -- $publicKeyPath $decodedSignaturePath $artifactPath
    if ($LASTEXITCODE -ne 0) {
        throw "Updater signature verification failed with exit code $LASTEXITCODE."
    }
}
finally {
    if (Test-Path -LiteralPath $temporaryDirectory) {
        Remove-Item -LiteralPath $temporaryDirectory -Recurse -Force
    }
}
