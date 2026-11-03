# Models and third-party components

Everything here runs **on the phone**. Models are not committed to the repository (`.gitignore` excludes `*.onnx`);
install them with [`scripts/push-models.ps1`](scripts/push-models.ps1). Licenses were checked on 2026-11-02/03: re-read them
before shipping anything beyond personal use.

## Runtime

| Component | Version | License | Notes |
|---|---|---|---|
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) Android AAR | 2.0.1 (2026-10-26) | Apache-2.0 | SHA-256 `a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7` (49,441,341 bytes). Not on Maven Central: `scripts/fetch-deps.ps1` downloads and verifies it into `app/libs/`. |
| [ML Kit Translate](https://developers.google.com/ml-kit/language/translation) | `com.google.mlkit:translate:17.0.3` | Google ML Kit terms | Russian, English and Indonesian are supported. ~30 MB per language, downloaded once over the network, then offline. Non-English pairs pivot through English (Russian → Indonesian goes Russian → English → Indonesian). Google positions it for casual text. |

## Speech models (Russian)

Installed on the phone under `/sdcard/Android/data/app.arislushai/files/models/<folder>/`.

| Folder | Source | Size | License | Notes |
|---|---|---|---|---|
| `vad/silero_vad_v5.onnx` | sherpa-onnx `asr-models` release | 2.3 MB | Silero VAD is MIT upstream | **Required.** The older `silero_vad.onnx` missed quiet speech in our test. |
| `giga-v3-transducer/` | Hugging Face `csukuangfj/sherpa-onnx-nemo-transducer-giga-am-v3-russian-2025-12-16` | encoder.int8 214 MB, decoder 3 MB, joiner 1 MB | MIT (GigaChat Team; `LICENSE` ships with the model) | Default. Best agreement in our comparison. |
| `giga-v3-ctc/` | Hugging Face `csukuangfj/sherpa-onnx-nemo-ctc-giga-am-v3-russian-2025-12-16` | 214 MB | MIT | Within 3% of the transducer on our clip. |
| `zipformer-ru-int8/` | sherpa-onnx `asr-models` release, `sherpa-onnx-zipformer-ru-int8-2025-04-20` | 57 MB archive | **Not yet verified**: read its README (Vosk-derived) before relying on it | About 2.7× faster than GigaAM on a PC; 6% different from it. |

GigaAM **v1** models (2024-10-24) were published under a non-commercial license and are not used.

### Comparison we ran (PC, one 99 s lecture recording, 8 s chunks)

"Differs from GigaAM" is the word-level difference from the GigaAM v3 transducer output after lower-casing and
removing punctuation. **It measures agreement, not accuracy**: there is no human reference transcript yet.

| Model | Differs from GigaAM | Notes |
|---|---|---|
| GigaAM v3 CTC | 3% | |
| zipformer-ru-int8 | 6% | |
| vosk small streaming (25 MB, Apache-2.0) | 8–9% | True streaming; first text after 0.7 s. Candidate for a live-preview layer. |
| NeMo FastConformer multilingual (int8) | 13–17% | Not used. |
| T-one streaming (Apache-2.0) | 16–18% | Telephony model that resamples to 8 kHz. Not used. |

Boosting the quiet recording's volume before recognition did not help.

## Not usable on this phone

* **ML Kit GenAI Speech Translation** (alpha): needs Gemini Nano V4, the AICore app and a supported NPU
  (Tensor G5+, Snapdragon 8-series, Dimensity 9300/9400+). The Galaxy A54 (Exynos 1380) is not on the list and the
  AICore app is not installed.

## Installing models manually

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$dst = "/sdcard/Android/data/app.arislushai/files/models"
& $adb shell mkdir -p $dst
& $adb push <local>\giga-v3-transducer "$dst/"
& $adb push <local>\vad "$dst/"
& $adb shell "chmod -R a+rwX $dst"   # files pushed by the adb shell user are otherwise unreadable by the app
```
