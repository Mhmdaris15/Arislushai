# Offline Lecture Transcription & Translation

An Android app for following Russian lectures and seminars through live Russian captions and English or Indonesian translations, including when internet access is unavailable.

**Status:** implementation brief; no application has been built or benchmarked. The stack below is a recommendation, not a validated performance claim. Start with the feasibility milestone before building the full product.

## 1. User context and purpose

The user is a student living in Russia. Following lectures and seminars is the first priority; everyday face-to-face conversations and desktop meetings are later goals.

- Available phones: Samsung Galaxy A54 and iPhone 14 Pro.
- Development computer: Windows only.
- Internet availability: inconsistent. Offline operation is preferred and is the baseline for the first version.
- Source language: Russian.
- Translation languages: English and Indonesian, both supported as selectable targets.
- Initial implementation proposal: native Android for the Galaxy A54. iPhone and desktop implementations are deferred.

The app should help the user understand speech while it happens and revisit missed material afterward. Do not expand this into a messaging platform.

## 2. Product scope

### First usable version

1. Download required speech and translation models during setup.
2. Start and stop a lecture session explicitly.
3. Capture microphone audio continuously and preserve it locally.
4. Display Russian captions alongside the selected translation.
5. Switch the translation target between English and Indonesian.
6. Scroll back without interrupting recording; provide a clear return-to-live action.
7. Save sessions and reopen their transcript and available translations offline.
8. Show recording state, model readiness, processing delay/backlog, and actionable errors.
9. Export transcripts as UTF-8 text and JSON, with session-relative segment times.
10. Delete a session and its associated audio and transcript data.

Displaying Russian plus one translation is the proposed default. A three-language display can follow if useful. Supporting both targets does not require translating every segment into both languages continuously.

### Later features

- Summaries, assignments, deadlines, and vocabulary explanations, linked to supporting transcript passages.
- Tap a segment to replay its audio.
- Face-to-face translation in both directions.
- Optional cloud enhancement when requested and connectivity permits.
- Windows meeting mode with microphone and system-audio capture.
- iPhone support.

### Outside the first version

- Accounts, contacts, social chat, subscriptions, and cloud synchronization.
- Voice cloning and translated speech playback.
- Automatic identification of named speakers.
- A large local general-purpose LLM running alongside live speech recognition.
- An exact clone of another product.

## 3. Proposed technology stack

| Layer | Initial choice | Reason |
| --- | --- | --- |
| Language | Kotlin | Native Android integration |
| UI | Jetpack Compose + Material 3 | Captions, session list, settings, and recording controls |
| Audio | Android AudioRecord | Direct microphone audio access |
| Session lifecycle | Microphone foreground service | Ongoing recording with a persistent notification |
| Speech runtime | sherpa-onnx | On-device inference with Android integration |
| Russian speech model | Benchmark a compatible Russian GigaAM model first | Russian-focused candidate; exact model remains unselected |
| Speech segmentation | Compatible voice activity detection plus bounded chunks | Avoid processing silence and prevent unbounded utterances |
| Translation | Google ML Kit on-device translation | Initial offline baseline for Russian, English, and Indonesian |
| Concurrency | Kotlin Coroutines + Flow | Separate audio capture, inference, storage, and UI work |
| Structured storage | Room | Sessions, segments, processing states, translations |
| Settings | DataStore | Translation preference and recording settings |
| Audio storage | App-private files | Durable local recordings independent of caption processing |
| Build environment | Android Studio, Gradle wrapper, Android SDK | Build on Windows and install on the real A54 |

Use supported stable dependency versions verified against current upstream documentation. Pin versions in the project. Verify native ABI compatibility, model licenses, runtime licenses, and model distribution terms before choosing downloadable artifacts. Do not assume a runtime's license covers its models.

Do not add a backend, Firebase, or an API key requirement to the offline baseline.

## 4. Important technical limits

### Offline includes translation

Audio capture alone is not offline functionality. With the required models installed, recognition, translation, saving, and reopening sessions must work in airplane mode.

First-time model downloads require connectivity. Explain this during setup, expose download progress and failures, and prevent starting an unsupported offline mode with missing models.

### Translation quality is an open question

ML Kit supports the three requested languages, but Google positions its on-device translation for casual use. Translation between non-English languages uses English as an intermediary. Russian-to-Indonesian technical lecture quality must therefore be evaluated explicitly.

Treat ML Kit as a replaceable baseline. If it fails the evaluation, investigate other local translation engines or models and report their quality, memory, storage, and latency tradeoffs before integrating them.

### Streaming is model-dependent

Some Russian model examples use simulated streaming: recognize successive audio chunks rather than run a genuinely streaming recognizer. Label this accurately. Do not advertise zero delay or assume that all sherpa-onnx models stream.

Keep tentative captions distinct from finalized text. Translate finalized segments first to reduce flicker and repeated computation. If chunk overlap is used, reconcile repeated text without deleting legitimate repeated phrases.

### Real hardware decides

There are no verified Galaxy A54 performance results for this pipeline yet. Do not promise sustained real-time recognition, a particular battery life, or a fixed accuracy level. Record the actual phone RAM configuration and Android version during testing.

Microphone foreground-service permissions and background-start restrictions must follow current Android documentation. Starting a recording while the app is visible and continuing it after switching apps is a required test scenario; silent background starts are not required.

## 5. Processing design

Conceptual flow:

**Microphone → durable local audio → bounded speech segments → Russian recognition → target translation → saved transcript + live UI**

Audio preservation must not depend on inference keeping up. Avoid storing an entire lecture in RAM. Use a bounded processing queue backed by recoverable audio on disk.

- Keep recording work off the UI thread and separate from recognition and translation.
- Track audio time independently of wall-clock changes.
- Associate every segment with a stable identifier and session-relative start/end times.
- Expose processing lag. Never silently drop lecture sections to make captions appear current.
- If processing falls behind, preserve audio and show a catching-up state. Support deferred processing of unfinished segments.
- Persist finalized results incrementally so a crash does not erase the whole session.
- Reopening after process termination should identify interrupted sessions; do not imply recording continued while the process was dead.
- Handle audio-focus changes, microphone interruption, low storage, and inference failures explicitly.

Proposed boundaries, which the implementation agent may simplify:

- `AudioCapture`: microphone buffers and recording lifecycle.
- `AudioStore`: recoverable audio files and segment references.
- `SpeechRecognizer`: engine-independent Russian recognition results.
- `Translator`: engine-independent translation by target language.
- `SessionRepository`: saved sessions and segment states.
- `LectureViewModel`: observable screen state and user commands.

Keep native inference dependencies behind adapters. Replacing a model should not require rewriting screens or changing the saved transcript format.

## 6. Minimal data model

**Session:** ID, title, creation time, source language, selected target, recording/processing status, audio location, model identifiers.

**Transcript segment:** ID, session ID, sequence, audio start/end offsets, Russian text, finalization state, recognition processing status.

**Translation:** segment ID, target language, translated text, translator/model identifier, processing status, source-text revision.

Translations must remain associated with the exact transcript revision used to produce them. Changing targets must not overwrite the other language's saved translation. Do not invent confidence scores or word-level timestamps when the chosen model does not provide them; chunk times are acceptable if labeled as such.

## 7. Build order and acceptance checks

### Milestone 0 — Prove the offline pipeline

Before polishing the UI, make a minimal Android harness that can process a recorded Russian sample and capture live microphone input on the A54.

Use a repeatable Russian lecture/seminar sample, ideally with technical terms, numbers, distant speech, and speaker changes. Compare at least one selected GigaAM candidate with an alternative compatible recognizer if the initial result is poor; do not build an expansive benchmark suite before obtaining the first result.

Measure:

- Recognition errors on a manually checked excerpt, especially negation, numbers, dates, and terminology.
- English and Indonesian translation adequacy, assessed separately.
- End-to-end caption/translation delay and whether backlog grows.
- Memory, battery change, and thermal behavior over a sustained run.
- Model download/storage footprint.

Proposed initial evaluation: a 10-minute quality sample followed by a 45–60-minute sustained run. A provisional usability target is finalized translated segments within roughly five seconds of a speech chunk ending, without continuously increasing backlog. This is a proposed benchmark target, not an agreed guarantee; report actual measurements and remaining tradeoffs.

If the pipeline cannot keep up, report the bottleneck and propose smaller models, different segmentation, or a different translation engine. Laptop assistance is a fallback requiring a product decision, not something to substitute silently for phone-only operation.

### Milestone 1 — Reliable lecture sessions

Implement recording, bilingual captions, scrollback, target switching, session persistence, and error states. Verify airplane-mode operation after model setup, app switching, screen lock, microphone interruption, and low-storage handling.

### Milestone 2 — Recovery and export

Implement interrupted-session recovery, deferred processing, transcript export, and complete local deletion. Verify that exported segment order and time ranges correspond to the recorded audio and that resuming does not duplicate finalized results.

### Milestone 3 — Study assistance

Only after the core pipeline is useful, revisit summaries, vocabulary, assignments, and optional online enhancement. Do not assume these must use an on-device LLM.

## 8. Windows development handoff

The implementation agent should:

1. Check the existing repository and instructions before scaffolding. If there is no project, create a minimal native Android project for this brief.
2. Verify the current Android Studio/SDK/JDK requirements; prefer the compatible JDK supplied with Android Studio rather than guessing a version.
3. Pin dependencies and commit the Gradle wrapper.
4. Document required models, their sources, licenses, download sizes, and initial setup.
5. Build a debug APK on Windows and install it on the A54 through USB debugging.
6. Keep setup commands compatible with PowerShell and document actual paths and prerequisites.
7. Run appropriate build checks and focused tests for segment ordering, translation revision matching, persistence, and recovery.
8. Report which checks ran on a real phone versus an emulator or desktop. A successful build does not establish live performance.

Expected project commands after scaffolding are `./gradlew.bat assembleDebug` and `./gradlew.bat testDebugUnitTest` in PowerShell. Verify these against the created project; they are not evidence that any build or test has already passed.

Deliver source code, reproducible build instructions, model setup instructions, a debug APK when the environment permits, and a concise report of real-device results and unresolved issues.

## 9. Role of REA and reference products

Reference product: [Intent](https://intent.app/), particularly its face-to-face translation experience. The user proposed [REA / Reverse Engineer Anything](https://github.com/morluto/rea) as an investigation tool.

REA is optional development tooling, not a runtime dependency. Use it only for a specific investigation that improves this product, such as understanding caption updates or a recording interaction. Start with public behavior and available documentation. Keep observations separate from inference.

Inspecting a client does not recover a remote speech model or server implementation. Build independently with our chosen engines; do not rely on Intent's private services, credentials, subscription access, or proprietary assets. Do not spend the initial feasibility milestone installing REA when existing speech demos can answer the immediate performance question.

## 10. Implementation guidance for coding agents

- Treat user requirements as fixed and the proposed stack as replaceable when evidence justifies a change.
- Begin with Milestone 0, then progress through the milestones when results support the next step.
- Make routine reversible implementation decisions without repeatedly asking the user.
- Escalate decisions that change offline operation, phone-only use, supported languages, recurring costs, or upload behavior.
- Keep audio and transcripts local by default. Do not add automatic uploads or analytics containing conversation content.
- Surface visible recording state and use the app only where recording is permitted.
- Clearly distinguish implemented, tested, proposed, and deferred behavior.
- Never claim accuracy, latency, battery performance, or completion without evidence.

## 11. Primary references

Technical references checked during planning on 2026-11-02; verify current details before implementation:

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)
- [sherpa-onnx Android examples](https://k2-fsa.github.io/sherpa/onnx/android/index.html)
- [NeMo/GigaAM model documentation](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-transducer/nemo-transducer-models.html)
- [ML Kit on-device translation and limitations](https://developers.google.com/ml-kit/language/translation)
- [ML Kit supported translation languages](https://developers.google.com/ml-kit/language/translation/translation-language-support)
- [ML Kit Android integration](https://developers.google.com/ml-kit/language/translation/android)
- [Android foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)

