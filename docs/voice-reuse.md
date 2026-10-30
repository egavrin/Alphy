Current implementation: [local voice integration](voice-integration.md). The evidence below describes the earlier foundation stage.

# Historical speech reuse evidence

Source revision: `/Users/egavrin/Documents/deal-studio` commit `feea3d8a0e13`.
The current reference tree is a different product revision. No reference files were changed.

| Component | Historical source and contract | Availability and next requirement |
| --- | --- | --- |
| T-one ASR | `app/src/main/java/com/offlineassistant/app/asr/TOneStreamingTranscriber.kt` uses Sherpa `OnlineRecognizer`, streaming PCM, endpoint callbacks, and model readiness | `app/libs/sherpa-onnx-static-link-onnxruntime-1.13.4.aar` exists locally. Restore the historical model and token files before integration. |
| Whisper ASR | `app/src/main/java/com/offlineassistant/app/asr/WhisperTranscriber.kt` calls `offlineassistant_whisper` through JNI | Historical submodule commit is `080bbbe85230f624f0b52127f1ae1218247989f9`. The current tree lacks its `include/whisper.h`. Restore the pinned native sources before compilation. |
| Silero TTS | `app/src/main/java/com/offlineassistant/app/speech/SileroOnnxSpeechSynthesizer.kt` requires predictors, acoustic model, and ISTFT window | `models/external/silero-v5_5-ru-xenia/android-bundle/` is absent locally. The historical README requires an export step. |

The local Sherpa AAR contains `libsherpa-onnx-jni.so` for ARM64, ARMv7, x86, and x86_64.
The historical application supports ARM64 only. Its CMake file uses C++17 and 16 KiB linker alignment.
The host has NDK `27.0.12077973` and CMake `3.22.1`, as required by that revision.
The historical Kotlin speech implementation also requires ONNX Runtime Android and JTransforms.
No speech binary or model is packaged into Alpha at this stage.

The historical T-one model is a Git LFS object with SHA-256 `5ded080e2a6c86ecc11bcb0902d77524eb3e8b0844cb0c0754347f5aafb4dabc` and size 144193702 bytes.
Its adjacent license is Apache-2.0, copyright T-Software DC.
The historical Whisper model is a Git LFS object with SHA-256 `422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898` and size 59707625 bytes.
The current reference tree lacks the historical packaged model directory.

The historical README states that the selected Silero public weight permits non-commercial use only.
Commercial distribution requires another license or an approved model replacement.
Silero expects `predictors.onnx`, `acoustic.onnx`, and `window.f32` plus its linguistic frontend resources.
The export tool is `tools/tts/silero_xenia_export_probe.py` at the historical revision.

## Open integration criteria

Restore exact sources into Alpha with their licenses and model checksums.
Compile native code into the APK. Do not download native code at runtime.
Keep microphone and speech runtimes in the application process, outside the JavaScript process.
Prove transcription and synthesis on the connected ARM64 device with recorded audio fixtures.
Add explicit readiness and failure states before any speech control appears.
Speech is not operational in this package.
