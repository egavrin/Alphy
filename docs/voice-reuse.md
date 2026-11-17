Current implementation: [voice integration](voice-integration.md). The current build has no local speech model, no sherpa-onnx library, and no native speech engine. Voice input uses the provider of the AI key or the phone recognizer. Voice output uses the phone voice or a cloud voice.

# Historical speech reuse evidence

This section records what existed at an earlier foundation stage, before the unified AI provider change of 2026-11-16. It does not describe the current build.

Source revision: `/Users/egavrin/Documents/deal-studio` commit `feea3d8a0e13`.
The reference tree was a different product revision. No reference files were changed.

| Component | Historical source and contract | Outcome |
| --- | --- | --- |
| Local Russian streaming recognition | `app/src/main/java/com/offlineassistant/app/asr/` used a streaming recognizer with a Git LFS model of 144,193,702 bytes (Apache-2.0, T-Software DC) | Packaged for a time, then removed from Alphy (spec 12A.9). |
| Local Whisper recognition | `app/src/main/java/com/offlineassistant/app/asr/WhisperTranscriber.kt` called a native Whisper engine through JNI | Never packaged. Removed with the other local speech assets. |
| Local Silero speech output | `app/src/main/java/com/offlineassistant/app/speech/SileroOnnxSpeechSynthesizer.kt` needed an export step | Never packaged. Its public weight permits non-commercial use only. |

Alphy now keeps microphone capture in the application process, outside the JavaScript process, and sends no speech binary or model in the APK.
Alphy does not download native code at runtime.
