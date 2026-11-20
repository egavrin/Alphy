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

## Voice mode («Голосовой режим»)

The voice mode is a full-duplex spoken dialog with OpenAI GPT-Live. GPT-Live talks with the user and hands each task to the chat agent of Alphy.
The spec is `docs/superpowers/specs/2026-11-18-live-voice-dialog-design.md`.

### Availability

- The voice mode exists only when the account provider is OpenAI.
- In the full chat, the send button of an empty composer becomes «Голосовой режим». With text in the field, the button sends the text as before.
- With an OpenAI account, a press of the assistant button opens the compact assistant with the voice view and starts the voice mode at once. With another provider, the sheet opens as before, and «Непрерывный голосовой сеанс» stays in its menu.
- The course composers have no voice mode.
- The start needs the microphone permission. The voice mode and the continuous voice session never run at the same time.
- The voice screen is a stage above a low strip (`docs/superpowers/specs/2026-11-20-dialogue-stage-design.md`). The stage shows the app of the latest hand-off that made or changed an app. Before the first app, it shows the Alphy character.
- The stage shows a chat card only when the card waits for a tap, or when a finished answer has a generated image. At a smallest width of 600 dp or more, the stage has two columns: the main visual with its controls at the left, and the other parts at the right.
- The strip shows the status («Слушаю», «Alphy говорит», or «Alphy выполняет запрос»), the microphone state («Микрофон включён» or «Микрофон выключен»), and the current line of the speech of GPT-Live.
- The microphone button mutes the capture and sends `session.input_audio.mute`. ✕ ends the voice mode.

### Session and hand-offs

`voice/LiveVoiceSession.kt` opens one WebSocket to `wss://api.openai.com/v1/live/sessions` with `Authorization: Bearer <key>`.
The model is `gpt-live-1`, and the voice is `marin`. The audio is `audio/pcm` at 16,000 Hz, mono, in both directions.
The session omits `delegation`, so GPT-Live runs in client mode. GPT-Live sends `session.delegation.created`, and Alphy answers with `session.commentary.append`.

- The request of a delegation is the last user turn of the input transcript. Words that the user says while GPT-Live speaks stay in the request, unless the user pauses for more than 1 s (spec 5.5.2). Alphy submits it as a normal chat message, with web search allowed.
- When the reply is complete, Alphy sends «Ответ Alphy: » and the speakable reply, cut at a sentence end to at most 1,200 characters.
- A mini-app request first sends «Alphy готовит мини-приложение, оно появится на экране.». The ready mini-app sends «Мини-приложение готово. Ответ Alphy: » and the reply.
- A failed request sends «Alphy не смог выполнить запрос: » and the error text.
- A phone-action plan shows its plan card on the stage. Each end phase of the plan sends one more commentary: «Готово.», «Не получилось.», or «Действие отменено.».
- A spoken request that a picture or a model makes clearer can get the routing kind `screen`. The voice says «Ответ Alphy: » and the answer at once, and the screen comes later on the stage. When the person asked in words to see a thing and no screen came, the voice says «Картинку показать не получилось.».
- With an app on the stage, the routing call gets the shown screen (its title and its controls) as untrusted data, and it can answer with the kind `press`. A press runs the named control as a tap, and then the voice says the answer. A label that names no control gives «На экране нет такой кнопки.». A tap that does not run in 2 s gives «Нажать не получилось.».
- A spoken change of the app on the stage runs the change by lines. The voice stays silent until the changed screen shows, and then it says the answer. A change that the kit cannot make becomes a new screen, and the old app stays. When the new screen also fails, the voice says «Изменить экран не получилось.».
- A memory fact, an automation draft, or a course topic ends the voice mode with «Продолжите на экране».
- A hand-off request runs to its end, also after the voice mode ends. Its result stays in the chat.
- Text that the user types on the voice screen goes to Alphy. The reply goes to GPT-Live as quiet context (`session.thinking.append`).

### Data sent to OpenAI

- The microphone audio, continuously while the microphone is not muted.
- Fixed Russian instructions (`LiveProtocol.INSTRUCTIONS` in `voice/LiveProtocol.kt`).
- A history preload: the latest text messages of the selected conversation, at most 128 messages and 8,192 tokens. Attachments, images, and mini-app content are not sent.
- The commentary and thinking texts: the replies of Alphy and short status lines.

Each hand-off request also goes to the chat model of the account, as a typed request does.
The voice mode adds no consent line. The OpenAI consent already names voice recordings for speech recognition, and chat text already goes to OpenAI.
Alphy does not store the audio. The transcripts stay in memory for the session. Only the hand-off messages are saved in the chat.

### Cost

GPT-Live costs $0.05 per minute. OpenAI bills each second of the open session, silence included.
Hand-offs also cost the normal chat requests of the account. The voice screen shows no price or timer.

### End of the voice mode

Each end closes the capture and the playback, sends `session.close`, restores the audio mode and focus, and closes the voice screen.
The chat shows the status in the composer status line.

| Event | Status |
| --- | --- |
| ✕ | none |
| The screen goes off, or the activity stops while no screen or app that Alphy opened is in front | none |
| 60 s without user speech while no hand-off runs. A running hand-off holds this end off for at most 180 s after the last user speech or Alphy output. | «Голосовой режим завершён: нет речи 60 с» |
| 10 s without speech after a press of the assistant button | «Голосовой режим завершён: нет речи» |
| Audio focus loss | «Голосовой режим прерван другим звуком» |
| The provider is no longer OpenAI, or the key is removed | «Голосовой режим доступен только с ключом OpenAI» |
| `session.closed` with `expired` | «Сеанс голосового режима истёк. Начните заново.» |
| `session.closed` with `content` | «OpenAI прервал голосовой режим по правилам содержания» |
| A connection loss, or no `session.started` within 10 s | «Связь с OpenAI прервалась» |
| An HTTP refusal of the upgrade | The provider error text. A key error opens «Сервис ИИ». |
| An `error` event that is not for a sent commentary, thinking text, mute, or unmute | «OpenAI: ошибка голосового режима. Начните заново.» |

A permission dialog, a picker of «+», or an app that Alphy opens mutes the microphone and keeps the session. The microphone unmutes when Alphy is in front again.
In the compact assistant, the voice mode also ends when the sheet closes, at `onHide`, at `onLockscreenShown`, and when the sheet opens the full app.

### Voice approval

GPT-Live reads a phone-action plan aloud and asks for «да» or «нет». The code of Alphy decides the approval, never GPT-Live.
The window opens when GPT-Live ends the proposal, and it stays open for 30 s. Only a whole user turn from these lists counts, without case and punctuation:

- Approval: «да», «да, запускай», «запускай», «выполняй», «подтверждаю», «давай», «yes», «go ahead».
- Rejection: «нет», «не надо», «отмена», «отмени», «стоп», «no», «cancel».

A turn that starts while Alphy speaks never approves. The plan runs only when its id and revision are unchanged and it is still in review.
A tap on the plan card works at all times.

Voice approval is on only for the phone models in `VOICE_APPROVAL_MODELS` (`voice/LiveAudio.kt`).
The list is empty, so voice approval is off on every device until a phone model passes the echo check.
While voice approval is off, the plan card needs a tap, and GPT-Live says «Нужно подтверждение на экране.».

### Echo check

The echo check tests that the loudspeaker echo does not reach GPT-Live (spec 6.4). It needs a real phone with the loudspeaker, no headset, and a quiet room.

1. Install the debug and test APKs only on a phone without the release app. A signature mismatch uninstalls the release app and deletes its data.
2. Run `adb shell am instrument -w -e class com.alpha.assistant.voice.EchoCheckDeviceTest com.alpha.assistant.test/androidx.test.runner.AndroidJUnitRunner` on the phone.
3. Pull `<Build.MODEL>-reply.wav` and `<Build.MODEL>-proposal.wav` from `/sdcard/Android/data/com.alpha.assistant/files/echo-check/`.
4. Copy both files to `app/src/androidTest/assets/voice/echo/`.
5. Run `run.py suite` with `com.alpha.assistant.voice.LiveEchoTranscriptTest` on an owned emulator, because `live-check` uses the installed build.
6. Run the paid half: `ALPHY_AI_PROVIDER=openai python3 tools/compose_acceptance/run.py live-check --serial emulator-5590 --label live-echo --test com.alpha.assistant.voice.LiveEchoTranscriptTest#echoIsNotTranscribed`.
7. When both halves pass, add the `Build.MODEL` value to `VOICE_APPROVAL_MODELS`.
8. Record the revision, the device, the steps, and the results in `evidence/live-voice/echo-check.md`.

### Device check

After the echo check, do these checks on the same phone, in this order:

1. Measure the silent gap between an interrupted reply and the next reply. If it is near 800 ms, reset `dropping` at the end of the user's turn (review I3).
2. Listen for clipped word ends and for stutter inside words (review m1 and m2).
3. Let Alphy open Clock from the compact assistant, and check that `onHide` does not end the voice mode (Task 9 device risk).
4. Connect a headset during the session, and record which device plays the audio. The session selects the route only at its start (review m12).
5. Run the live check, and check that GPT-Live accepts its mute and unmute steps (review I2).

### Voice mode tests

The workflow tests use a loopback GPT-Live server and scripted audio:

```sh
python3 tools/compose_acceptance/run.py suite --serial emulator-5590 --label live-voice-all \
  com.alpha.assistant.voice.BargeInDetectorTest \
  com.alpha.assistant.voice.VoiceModeProtocolTest \
  com.alpha.assistant.voice.VoiceModeTurnsTest \
  com.alpha.assistant.voice.VoiceModeTransportTest \
  com.alpha.assistant.voice.VoiceModeSessionTest \
  com.alpha.assistant.ui.ShellLiveVoiceWorkflowTest \
  com.alpha.assistant.ui.VoiceModeScreenWorkflowTest \
  com.alpha.assistant.surfaces.AssistantLiveVoiceWorkflowTest \
  com.alpha.assistant.ui.ShellVoiceWorkflowTest
```

`AssistantLiveVoiceWorkflowTest#noPermissionNoSession` skips while the app has the microphone permission.

The live check runs one GPT-Live session of about 65 s with the OpenAI key from `~/.env`. It costs about $0.05.
Run `run.py suite` with `com.alpha.assistant.voice.LiveVoiceDialogTest` first, because `live-check` uses the installed build:

```sh
ALPHY_AI_PROVIDER=openai python3 tools/compose_acceptance/run.py live-check --serial emulator-5590 --label live-voice \
  --test com.alpha.assistant.voice.LiveVoiceDialogTest#russianDialogWithHandOffs
```

The check writes `events.log` next to `report.json`. The log holds the event types, the ids, the timeline values, and the transcripts, never the key.
`LiveVoiceDialogTest#dialogScriptPassesOnAScriptedServer` runs the same script on a scripted server without cost.

### Live check results of 2026-11-18

Run 1 (`live-0ac2b42f003f`) tested the first build. These results held:

- GPT-Live accepted the history preload.
- The input transcript of the request was complete when `session.delegation.created` arrived.
- GPT-Live claimed no action before the commentary, and it spoke a commentary that came 25 s after its delegation.
- GPT-Live spoke a second commentary for the same delegation.
- `session.thinking.append` without a delegation gave no error.
- The input and output transcripts use one session clock.
- GPT-Live opened a delegation for the spoken «да».

These results of run 1 differ from the spec:

- The output audio is one continuous stream of 100 ms chunks, silence included. The end of a reply is not a pause in the arrival of chunks.
- The output transcript times lead the output audio. The received audio of a reply ends 1.7 to 2.4 s after its last `end_ms` on the input clock, before the playback delay of the phone.
- GPT-Live did not stop a commentary when the user spoke over it. It opened no delegation for the request that interrupted it.
- GPT-Live spoke the thinking text aloud.
- GPT-Live changed «Нужно подтверждение на экране.» into a request to say «да» or «нет».

After run 1, the session finds replies by the sample level (rulings R24 to R30).
A chunk is voiced when its RMS level is above 0.01 (`LIVE_VOICED_RMS`).
A reply ends after 800 ms of silent stream audio after its last voiced chunk (`replyGapMs`), or after 800 ms without any chunk.
An instruction line tells GPT-Live to send the user to the button on the card for «Нужно подтверждение на экране.».

Run 2 (`live-2a3dd7bb5762`) tested that build with a real-time playback fake:

- The stream had 8 replies. Each reply had one reply end, and the output transcript had 8 reply groups.
- The phase returned to listening after each reply, and no error ended the voice mode.
- Pauses inside a reply were at most 500 ms. Silence between replies was at least 1,000 ms.
- GPT-Live stopped the reply that the user interrupted after its first sentence, and it opened a delegation for the request. Alphy flushed its playback, and every later reply played.
- The request text of that delegation was «пять минут», not «Поставь таймер на пять минут». The first rule dropped the input deltas that start before the last `end_ms` of the interrupted reply. Rulings R31 and R32 fixed this in the client (spec 5.5.2). The fix is tested on the recorded run 2 timeline, not in a new paid run.
- GPT-Live said «Пожалуйста, подтвердите действие на экране.». It did not ask for «да» or «нет», and it did not name the tap.
- GPT-Live spoke the answer from the thinking text («Четыре.») without a user turn.

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
