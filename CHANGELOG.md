# Changelog

All notable changes are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/) and the
project uses [Semantic Versioning](https://semver.org/).

## [1.0.0] - 2026-11-03

First public release. Android (arm64) only.

### Added
- Offline Russian speech recognition on the phone with sherpa-onnx and GigaAM v3 (transducer or CTC) or a Russian zipformer.
- Voice-activity detection (Silero v5) with padded chunks and a forced cut at the quietest moment after about 6 s of
  continuous speech, so a caption never waits for a lecturer to pause.
- Tentative (dimmed, italic) captions and translations for speech still in progress; final captions replace them.
- On-device translation from Russian to English and Indonesian with ML Kit.
- Unlimited live feed that follows the newest caption, stops following when you scroll back, and has a "↓ Live" button.
- History: one expandable section per recording, saved after every caption so a crash loses nothing; delete removes
  the transcript, the audio and the summary.
- Optional AI summaries and meaningful recording titles with a Gemini or OpenRouter API key you enter yourself:
  a manual ✦ Summary button while recording and an optional automatic summary when a recording ends. Only transcript
  text is sent. The key is encrypted with an Android Keystore key.
- Foreground microphone service with a CPU wake lock; captions can show over the lock screen so Android keeps the app
  in its fast CPU group.
- Benchmark tools (Lab tab): replay a WAV file at full speed or at real-time pace and write a report with real-time
  factor, delay percentiles, queue depth, memory, battery and thermal readings.
- Helper scripts: `scripts/fetch-deps.ps1` (verified download of the sherpa-onnx library) and `scripts/push-models.ps1`.

### Known limitations
- Android only. iOS is being evaluated (Flutter).
- Models must be installed with `scripts/push-models.ps1`; there is no in-app model download yet.
- A 45-60 minute sustained run (battery, heat, memory growth) has not been measured.
- Recognition accuracy has not been measured against a human transcript.
- AI summaries are only partly verified. With a real Gemini key the model browser and Test connection worked, but a
  full summary-and-title round trip was not completed (Gemini returned 503 and timeouts during testing). OpenRouter
  has not been tried. The reply parser and the retry rule are covered by unit tests.
- Provider errors are shown as received; transient ones (429, 5xx) are retried up to 3 times.
- Release builds are not minified (R8 off).
