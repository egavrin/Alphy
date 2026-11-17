# Voice integration

Alphy has no speech model in its APK. Voice input and voice output use the AI key of the user, or the speech services of the phone.
The spec is `docs/superpowers/specs/2026-11-16-unified-ai-provider-design.md` (sections 12 and 12A).

## Voice input

The composer microphone starts one recording in both the full chat and the compact assistant.
Grant Android microphone permission when requested.
The inline waveform shows bounded root mean square (RMS) levels.
Text appears only after the user stops speaking. No partial text shows while the user speaks.
Review or edit the transcript, then press Send.
Cancel discards the recording or review and preserves the original draft and attachments.
A draft edit, conversation change, pause, screen lock, interruption, or error cancels the session.
A late result cannot replace a changed draft.
Microphone denial shows a retry action and an Android settings link.

Without an account, voice input does not start. The status asks for a key and shows «Открыть настройки», which opens «Сервис ИИ».

### Provider transcription

OpenAI, OpenRouter, and DeepSeek recognize the speech. `voice/CloudSpeechInput.kt` sends the recording through the key of the account.

| Provider | Request | Model |
| --- | --- | --- |
| OpenAI | `POST /v1/audio/transcriptions` | `gpt-transcribe` |
| OpenRouter | `POST /api/v1/audio/transcriptions` | `openai/whisper-large-v3` |
| DeepSeek | `POST /v1/tools/asr` | none (the request has no `model` part) |

Alphy records with `AudioRecord` at 16 kHz, mono, 16-bit PCM. It keeps the samples in memory, for at most 60 s.
It sends one WAV file in one multipart request. The `file` part is `voice.wav` with type `audio/wav`.
Alphy sends no `language` part, so the provider detects the language. The `text` field of the JSON response is the transcript.
Alphy does not keep the recording. A failed request stops the session with «Не удалось распознать речь:» and the provider error text.
The microphone note says «Запись уходит в <provider> для распознавания · проверяйте текст перед отправкой».

### Phone recognizer

Anthropic offers no transcription, so `voice/PhoneSpeechInput.kt` uses `SpeechRecognizer.createOnDeviceSpeechRecognizer` with language `ru-RU` and no partial results.
The audio stays on the phone. The microphone note says «Речь распознаёт телефон · проверяйте текст перед отправкой».
If the phone cannot recognize Russian on the device, the status says so and the microphone does not start.
If `ru-RU` is supported but not installed, the status offers «Скачать русский язык».

### End of an utterance

- One-shot recording ends at «Завершить запись», at «Отправить голосовой текст», or at 60 s.
- In the continuous session, the utterance ends after 1.2 s of silence that follows speech. The threshold adapts to the background noise.
- Without speech in the first 8 s, the session stops with «Не удалось распознать речь».
- The phone recognizer ends the utterance by its own end of speech in both modes.

## Continuous voice session

«Настройки» → «Голос» give an explicit continuous voice session.
The session starts only with an empty draft and no active cloud request.
Each continuous transcript requires review and Send.
After a successful reply, the session listens for the next utterance. Stop ends the session.
The status «Микрофон выключен · дождитесь ответа сервиса ИИ» shows while a reply runs.

## Voice output

The switch «Читать ответы вслух» is off by default. When it is on, Alphy reads each accepted reply of the continuous session aloud and then listens again.
One-shot recording does not read replies aloud.
Alphy reads the text without Markdown marks, source chips, and code blocks. Stop ends speech and the session.
Voice output runs under the one audio focus request of the voice session and requests no focus of its own, because a second request stops the session (Task 19 decision 4).

| Source | Providers | Detail |
| --- | --- | --- |
| «Телефон» (default) | all four | Android `TextToSpeech` with the Russian voice of the system engine. Without that voice, «Голос» shows a status and a link to the system speech settings. |
| «Облако» | OpenRouter | OpenRouter: `POST /api/v1/audio/speech`, MP3, model `elevenlabs/eleven-v4-turbo` (voice `george`). |

The reply text goes to the provider for cloud voice. The consent text names it.
A failed cloud voice request falls back to the phone voice for that reply and shows a status.
OpenAI offers no cloud voice. Its `/v1/audio/speech` models shut down on 2027-01-06.
Anthropic offers no speech endpoint.
DeepSeek has no cloud voice (user decision, 2026-11-17). An account with a stored «Облако» choice reads replies with the phone voice, and the stored choice stays.

## Removed local speech

Earlier builds packaged the T-one model, the Whisper model, and the sherpa-onnx library, and copied the model into `noBackupFilesDir/voice/alpha-voice-1`.
This build removes them. The debug APK is at least 144,000,000 bytes smaller.
Alphy does not delete the old model copy. A reinstall frees that space.

## Device verification

The fixtures use a loopback server for provider requests, separate voice objects, and a debug-only activity.
The tests request microphone permission through the Android permission dialog. OPPO blocks shell permission grants.

Run the voice tests on an owned ARM64 emulator. Replace the example serial with the owned device serial.

```sh
python3 tools/compose_acceptance/run.py suite --serial emulator-5556 --label voice \
  com.alpha.assistant.voice.CloudSpeechInputTest \
  com.alpha.assistant.voice.CloudSpeechTest \
  com.alpha.assistant.voice.PhoneSpeechInputTest \
  com.alpha.assistant.voice.VoiceOutputWorkflowTest \
  com.alpha.assistant.ui.ShellVoiceWorkflowTest
```

## Limits

Voice capture runs only in the foreground on an unlocked device.
Cloud voice and provider transcription send audio or reply text to the provider, and can incur charges.
Assumption: `gpt-transcribe`, `openai/whisper-large-v3`, and the OpenRouter voice `elevenlabs/eleven-v4-turbo` handle Russian. No checked provider page lists their languages. The live voice check tests this assumption.
