# Local voice integration

The public Alphy repository excludes the Silero model bundle. The historical build and verification below used a local bundle. A fresh clone cannot run the complete voice workflow without the exact files listed in the voice manifest.

Alpha supports T-one Russian streaming recognition on ARM64 Android devices.
Silero v5.5 Russian Xenia produces local spoken replies.
The personal-use model bundle is `alpha-voice-1`.

## User flow

The composer microphone starts one recording in both the full chat and the compact assistant.
Grant Android microphone permission when requested.
The inline waveform shows bounded root mean square (RMS) levels from microphone samples.
Stop closes capture and finishes local transcription.
Review or edit the transcript, then press Send.
Send appends the reviewed transcript to the current draft and submits text with the explicitly selected attachments once.
Cancel discards the recording or review and preserves the original draft and attachments.
A draft edit, conversation change, pause, screen lock, interruption, or error cancels the session.
A late result cannot replace a changed draft.
One-shot recognition neither initializes Silero nor restarts the microphone after a reply.
Microphone denial shows a retry action and an Android settings link.

The menu also provides an explicit continuous voice session.
This session starts only with an empty draft and no active cloud request.
Alpha verifies and copies the packaged T-one and Silero models into its private no-backup directory.
The complete bundle requires about 281 MB of additional device storage.
One-shot recognition verifies and copies only the T-one files.
Each continuous transcript requires review and Send.
Audio remains on the device.
After a complete reply, Silero speaks locally.
After playback and a 350 ms guard interval, the continuous session listens for the next utterance.
Stop ends the continuous session.
Lifecycle and interruption safeguards apply to both modes.

## Sources and licenses

| Asset | Pinned source | Size | License |
| --- | --- | ---: | --- |
| T-one model | [Sherpa-compatible export](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-t-one-russian-2025-09-08/tree/9d63341e0f2d8a8b2edaa825aa1fcfe3c5283b47) | 144,193,702 bytes | Apache-2.0, T-Software DC |
| Silero Xenia export | Local historical export, source SHA-256 `50081637b602126ee06cb3bc8a744d25651d2da149ee8864b9a379bfdd934437` | 136,721,465 bytes | Export manifest: CC-BY-NC. Current upstream repository: CC-BY-NC-SA 4.0. Personal noncommercial use only. |
| Sherpa-ONNX | Local static ONNX AAR, 1.13.4 | 37,631,864 bytes | Apache-2.0 |

T-one SHA-256: `5ded080e2a6c86ecc11bcb0902d77524eb3e8b0844cb0c0754347f5aafb4dabc`.
The exact byte sizes and hashes for all packaged model files are in `app/src/main/assets/voice/manifest.json`.
The original Silero export manifest remains in `voice/silero/manifest.json`.
Model installation uses temporary files, checks size and SHA-256, and renames each valid file into place.
A partial or corrupt copy does not become ready.
The personal-use APK contains T-one and Silero model data and their native engines.
The build excludes the unused Whisper model and license assets. Its adapter and native build are removed.
Existing installed model directories remain unchanged.
There is no runtime model download or executable download.

The historical Android source comes from `deal-studio` revision `feea3d8a0e13`.
The adapted files retain their namespaces.
Alpha omits the historical telemetry subsystem and uses its no-op telemetry interface.
The reference checkouts remain unchanged.
The packaged `licenses/VOICE-NOTICE.txt` records attribution and license locations.

## Device verification

The fixture uses separate voice objects and a debug-only activity.
It never constructs chat storage or reads API credentials.
It uses a historical Russian PCM16 recording for T-one.
T-one receives incremental PCM blocks.
Silero must produce finite, non-silent PCM and play it through AudioTrack.
A separate lifecycle workflow starts the real microphone, pauses the activity, and checks that recording stops.
The test requests microphone permission through the Android permission dialog.
OPPO blocks shell permission grants, so this workflow does not use a shell grant.

Build command:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

Safe device commands:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class com.alpha.assistant.voice.LocalVoiceWorkflowTest com.alpha.assistant.test/androidx.test.runner.AndroidJUnitRunner
```

The evidence directory is `docs/evidence/voice`.
It includes build logs, signature comparison, install results, device workflow output, engine transcripts, and a source hash manifest.
No Gradle connected test task, uninstall, data clear, conversation deletion, or credential replacement is required.

## Limits

The APK supports ARM64 only.
T-one emits partial transcripts.
A recording ends after detected trailing silence, eight seconds without speech, or 25 seconds total.
A synthesis error stops the voice session and leaves the text reply intact.
The local voice path does not provide background service recording or lock-screen recording.
No live DeepSeek request forms part of the local voice fixture.
Commercial model distribution requires a separate license decision.

## Question and prosody compatibility

The author’s [question-intonation article](https://habr.com/ru/articles/1015942/) describes Russian v5.4, starred focus, and an `intensity` argument.
Alpha uses the historical v5.5 split ONNX export, not the article’s Torch `apply_tts` API.
A newer model version alone does not establish equivalent frontend behavior.

The restored frontend preserves `?` in the model symbol sequence.
It classifies question text and supplies `type_ids` to the predictor graph.
The bundled metadata defines `wh_q`, `general_q`, `alternative_q`, and `tag_q`.
The predictor export adds the type embedding to its duration and pitch predictors.
This source evidence establishes that punctuation and sentence-type conditioning reach the graph.
The current device fixture proves synthesis and playback, but it does not establish perceptual question-intonation quality or parity with the article.

Starred focus does not pass through this frontend.
Its symbol filter removes `*`, and its synthesis input has no focus-position field.
The frontend fixes `durationRate` and `pitchCoefficients` to `1f`.
The exported graph has no `intensity` input.
Alpha does not expose an intensity control or an SSML parser.
These are explicit limits. Alpha does not claim the full prosody API from the article.
No model replacement or new prosody control forms part of this package.
