# Local voice integration

Alphy supports T-one Russian streaming recognition on ARM64 Android devices.
The packaged model bundle is `alpha-voice-1`.

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
The microphone stays off after a one-shot recording ends.
Microphone denial shows a retry action and an Android settings link.

«Настройки» also give an explicit continuous voice session.
This session starts only with an empty draft and no active cloud request.
Both modes use the packaged T-one model.
Alphy verifies and copies its files into the private no-backup directory.
The copy requires about 144 MB of device storage.
Each continuous transcript requires review and Send.
Audio remains on the device.
The reply appears as text in the chat.
After a successful reply, the continuous session listens for the next utterance.
Stop ends the continuous session.
Lifecycle and interruption safeguards apply to both modes.

## Sources and licenses

| Asset | Pinned source | Size | License |
| --- | --- | ---: | --- |
| T-one model | [Sherpa-compatible export](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-t-one-russian-2025-09-08/tree/9d63341e0f2d8a8b2edaa825aa1fcfe3c5283b47) | 144,193,702 bytes | Apache-2.0, T-Software DC |
| Sherpa-ONNX | Local static ONNX AAR, 1.13.4 | 37,631,864 bytes | Apache-2.0 |

T-one SHA-256: `5ded080e2a6c86ecc11bcb0902d77524eb3e8b0844cb0c0754347f5aafb4dabc`.
The exact byte sizes and hashes for all packaged model files are in `app/src/main/assets/voice/manifest.json`.
Model installation uses temporary files, checks size and SHA-256, and renames each valid file into place.
Alphy rejects a partial or corrupt copy.
The APK contains the T-one model and the native Sherpa-ONNX library.

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
The lifecycle cases start the real microphone in both modes, pause the activity, and check that recording stops.
The shell workflow checks that a text reply resumes continuous capture and that each new transcript requires Send.
The test requests microphone permission through the Android permission dialog.
OPPO blocks shell permission grants, so this workflow does not use a shell grant.

Run the voice tests on an owned ARM64 emulator. Replace the example serial with the owned device serial.

```sh
python3 tools/compose_acceptance/run.py suite --serial emulator-5556 --label local-voice \
  com.alpha.assistant.voice.LocalVoiceWorkflowTest \
  com.alpha.assistant.voice.InlineVoiceWorkflowTest \
  com.alpha.assistant.ui.ShellVoiceWorkflowTest
```

## Limits

The APK supports ARM64 only.
T-one emits partial transcripts.
A recording ends after detected trailing silence, eight seconds without speech, or 25 seconds total.
Voice capture runs only in the foreground on an unlocked device.
The test uses a local provider fixture for chat replies.
