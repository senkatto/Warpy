[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$version = $env:RELEASE_VERSION
$repository = if ($env:GITHUB_REPOSITORY) { $env:GITHUB_REPOSITORY } else { 'senkatto/Warpy' }
$tag = if ($env:GITHUB_REF_NAME) { $env:GITHUB_REF_NAME } else { "v$version" }

if ([string]::IsNullOrWhiteSpace($version)) { throw 'RELEASE_VERSION is required.' }
if ($tag -ne "v$version") { throw "Release tag $tag does not match version $version." }
$packageVersion = (Get-Content -Raw -LiteralPath (Join-Path $projectRoot 'package.json') | ConvertFrom-Json).version
if ($packageVersion -ne $version) {
    throw "Release version $version does not match package version $packageVersion."
}

function Write-JsonWithoutBom {
    param(
        [Parameter(Mandatory)]$Value,
        [Parameter(Mandatory)][string]$Path
    )

    $json = $Value | ConvertTo-Json -Depth 6
    [IO.File]::WriteAllText($Path, $json, [Text.UTF8Encoding]::new($false))
}

& (Join-Path $PSScriptRoot 'verify-core-provenance.ps1') | Write-Output

$bundleDirectory = Join-Path $projectRoot 'src-tauri\target\release\bundle\nsis'
$installers = @(Get-ChildItem -LiteralPath $bundleDirectory -Filter "Warpy_${version}_x64-setup.exe")
if ($installers.Count -ne 1) { throw "Expected one NSIS installer, found $($installers.Count)." }
$installer = $installers[0]
$signaturePath = "$($installer.FullName).sig"
if (-not (Test-Path -LiteralPath $signaturePath -PathType Leaf)) {
    throw 'Tauri updater signature was not produced.'
}

$outputDirectory = Join-Path $projectRoot 'release-assets'
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
Get-ChildItem -LiteralPath $outputDirectory -File | Remove-Item -Force

$assetName = 'Warpy-Windows.exe'
$assetPath = Join-Path $outputDirectory $assetName
Copy-Item -LiteralPath $installer.FullName -Destination $assetPath
& (Join-Path $PSScriptRoot 'verify-updater-signature.ps1') -Artifact $assetPath -Signature $signaturePath

$signature = (Get-Content -Raw -LiteralPath $signaturePath).Trim()
$downloadUrl = "https://github.com/$repository/releases/download/$tag/$assetName"
$publishedAt = [DateTimeOffset]::UtcNow.ToString('o')

$latest = [ordered]@{
    version = $version
    notes = "Warpy desktop $version"
    pub_date = $publishedAt
    platforms = [ordered]@{
        'windows-x86_64' = [ordered]@{
            signature = $signature
            url = $downloadUrl
        }
    }
}
Write-JsonWithoutBom -Value $latest -Path (Join-Path $outputDirectory 'latest.json')

Write-Output "Prepared signed stable release assets for Warpy $version."
