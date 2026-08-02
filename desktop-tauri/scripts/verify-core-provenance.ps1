[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$binaryDirectory = Join-Path $projectRoot 'src-tauri\bin'
$provenancePath = Join-Path $binaryDirectory 'CORE_PROVENANCE.md'
$provenance = (Get-Content -Raw -Encoding UTF8 -LiteralPath $provenancePath).Replace("`r`n", "`n")

function Read-ProvenanceValue {
    param([Parameter(Mandatory)][string]$Pattern)

    $match = [regex]::Match($provenance, $Pattern, [Text.RegularExpressions.RegexOptions]::Multiline)
    if (-not $match.Success) {
        throw "Invalid core provenance: $Pattern"
    }
    $match.Groups[1].Value
}

$binaryName = Read-ProvenanceValue '^- Binary: `([^`]+)`$'
$expectedSize = [int64](Read-ProvenanceValue '^- Size: `([0-9]+)` bytes$')
$expectedHash = (Read-ProvenanceValue '^- SHA-256: `([0-9A-Fa-f]{64})`$').ToUpperInvariant()
$binaryPath = Join-Path $binaryDirectory $binaryName

if (-not (Test-Path -LiteralPath $binaryPath -PathType Leaf)) {
    throw "Bundled core is missing: $binaryName"
}

$binary = Get-Item -LiteralPath $binaryPath
$actualHash = (Get-FileHash -LiteralPath $binaryPath -Algorithm SHA256).Hash.ToUpperInvariant()
if ($binary.Length -ne $expectedSize) {
    throw "Bundled core size mismatch: expected $expectedSize, got $($binary.Length)."
}
if ($actualHash -ne $expectedHash) {
    throw "Bundled core SHA-256 mismatch: expected $expectedHash, got $actualHash."
}

Write-Output "Bundled core verified: $binaryName ($actualHash)"
