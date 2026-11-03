<#
.SYNOPSIS
  Downloads the sherpa-onnx Android library (AAR) that the app builds against, and verifies its SHA-256.

.DESCRIPTION
  The AAR is ~49 MB and is not published on Maven Central, so it is not committed. Run this once after cloning.
  Idempotent: if app/libs/sherpa-onnx-<version>.aar already matches the pinned hash, nothing is downloaded.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$version = '2.0.1'
$sha256  = 'a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7'
$url     = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$version/sherpa-onnx-$version.aar"
$dest    = Join-Path $PSScriptRoot "..\app\libs\sherpa-onnx-$version.aar"

New-Item -ItemType Directory -Force (Split-Path $dest) | Out-Null

if ((Test-Path $dest) -and ((Get-FileHash $dest -Algorithm SHA256).Hash.ToLower() -eq $sha256)) {
    Write-Host "sherpa-onnx $version already present and verified."
    return
}

Write-Host "Downloading sherpa-onnx $version ..."
Invoke-WebRequest $url -OutFile $dest -UseBasicParsing
$actual = (Get-FileHash $dest -Algorithm SHA256).Hash.ToLower()
if ($actual -ne $sha256) {
    Remove-Item $dest -Force
    throw "SHA-256 mismatch for sherpa-onnx $version. Expected $sha256 but got $actual. The download was deleted."
}
Write-Host "OK: $dest"
