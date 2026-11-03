<#
.SYNOPSIS
  Downloads the speech models (once, cached on the PC) and installs them on a USB-connected Android phone.

.DESCRIPTION
  The app looks for models in /sdcard/Android/data/app.arislushai/files/models/<id>/. Without at least the VAD and
  one speech model, recording cannot start. Sources and licenses: see MODELS.md.

.PARAMETER Models
  Which speech models to install. Default: giga-v3-transducer (best accuracy in our tests, ~219 MB).
  Options: giga-v3-transducer, giga-v3-ctc, zipformer-ru-int8.

.PARAMETER CacheDir
  Where downloads are kept on the PC. Default: %LOCALAPPDATA%\Arislushai\models

.EXAMPLE
  .\scripts\push-models.ps1
  .\scripts\push-models.ps1 -Models giga-v3-transducer,zipformer-ru-int8
#>
[CmdletBinding()]
param(
    [ValidateSet('giga-v3-transducer', 'giga-v3-ctc', 'zipformer-ru-int8')]
    [string[]]$Models = @('giga-v3-transducer'),
    [string]$CacheDir = (Join-Path $env:LOCALAPPDATA 'Arislushai\models')
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
if (-not (Test-Path $adb)) { $adb = (Get-Command adb -ErrorAction Stop).Source }
$pkg = 'app.arislushai'
$dst = "/sdcard/Android/data/$pkg/files/models"
$hf  = 'https://huggingface.co/csukuangfj'

$sets = @{
    'vad'                = @{ files = @('silero_vad_v5.onnx'); url = { param($f) "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$f" } }
    'giga-v3-transducer' = @{ files = @('encoder.int8.onnx', 'decoder.onnx', 'joiner.onnx', 'tokens.txt', 'LICENSE'); url = { param($f) "$hf/sherpa-onnx-nemo-transducer-giga-am-v3-russian-2025-12-16/resolve/main/$f" } }
    'giga-v3-ctc'        = @{ files = @('model.int8.onnx', 'tokens.txt', 'LICENSE'); url = { param($f) "$hf/sherpa-onnx-nemo-ctc-giga-am-v3-russian-2025-12-16/resolve/main/$f" } }
}

function Get-ModelFiles($id) {
    $dir = Join-Path $CacheDir $id
    New-Item -ItemType Directory -Force $dir | Out-Null
    foreach ($f in $sets[$id].files) {
        $p = Join-Path $dir $f
        if (-not (Test-Path $p)) {
            Write-Host "  downloading $id/$f"
            Invoke-WebRequest (& $sets[$id].url $f) -OutFile $p -UseBasicParsing
        }
    }
    return $dir
}

function Get-Zipformer {
    $dir = Join-Path $CacheDir 'zipformer-ru-int8'
    if (-not (Test-Path (Join-Path $dir 'tokens.txt'))) {
        Write-Host '  downloading zipformer-ru-int8 (57 MB archive)'
        $tmp = Join-Path $CacheDir '_zip'; New-Item -ItemType Directory -Force $tmp | Out-Null
        $tar = Join-Path $tmp 'z.tar.bz2'
        Invoke-WebRequest 'https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-zipformer-ru-int8-2025-04-20.tar.bz2' -OutFile $tar -UseBasicParsing
        tar -xjf $tar -C $tmp
        New-Item -ItemType Directory -Force $dir | Out-Null
        Copy-Item (Join-Path $tmp 'sherpa-onnx-zipformer-ru-int8-2025-04-20\*') $dir -Recurse -Exclude 'test_wavs'
        Remove-Item $tmp -Recurse -Force
    }
    return $dir
}

$devices = & $adb devices | Select-String '\tdevice$'
if (-not $devices) { throw 'No authorized Android device. Enable USB debugging, connect the phone and accept the RSA prompt.' }

Write-Host 'Preparing models on the PC ...'
$jobs = @(@{ id = 'vad'; dir = (Get-ModelFiles 'vad') })
foreach ($m in $Models) {
    $dir = if ($m -eq 'zipformer-ru-int8') { Get-Zipformer } else { Get-ModelFiles $m }
    $jobs += @{ id = $m; dir = $dir }
}

Write-Host 'Pushing to the phone ...'
& $adb shell mkdir -p $dst | Out-Null
foreach ($j in $jobs) {
    & $adb push $j.dir "$dst/" | Select-Object -Last 1
}
# Files pushed by the adb shell user are not readable by the app unless opened up.
& $adb shell "chmod -R a+rwX $dst"
Write-Host "Done. Installed: vad, $($Models -join ', ')"
