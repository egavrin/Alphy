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
- The voice screen is a stage above a low strip (`docs/superpowers/specs/2026-11-20-dialogue-stage-design.md`). The stage shows the app of the latest hand-off that made or changed an app, or a stage preview while Alphy makes a screen (section "Template screens and the stage preview"). Before the first app or preview, it shows the Alphy character.
- The stage shows a chat card only when the card waits for a tap, or when a finished answer has a generated image. On the card of a failed request, the stage shows the fixed text of its error group in place of the error text of the message (`stageError` in `ui/DialogueStage.kt`). Each text says what the person can say next. `docs/tool-integration.md` lists the groups.
- At a smallest width of 600 dp or more, the stage has two columns: the main visual with its controls at the left, and the other parts at the right. At that width, the stage keeps 276 dp for the padding, the kind label, the title, the first control, and the gaps around the canvas (`STAGE_TEXT_HEIGHT` in `ui/DialogueStage.kt`).
- The strip shows the status («Слушаю», «Alphy говорит», or «Alphy выполняет запрос»), the microphone state («Микрофон включён» or «Микрофон выключен»), and the current line of the speech of GPT-Live.
- The microphone button mutes the capture and sends `session.input_audio.mute`. ✕ ends the voice mode.

### Session and hand-offs

`voice/LiveVoiceSession.kt` opens one WebSocket to `wss://api.openai.com/v1/live/sessions` with `Authorization: Bearer <key>`.
The model is `gpt-live-1`, and the voice is `marin`. The audio is `audio/pcm` at 16,000 Hz, mono, in both directions.
The session omits `delegation`, so GPT-Live runs in client mode. GPT-Live sends `session.delegation.created`, and Alphy answers with `session.commentary.append`.

- The request of a delegation is the last user turn of the input transcript. Words that the user says while GPT-Live speaks stay in the request, unless the user pauses for more than 1 s (spec 5.5.2). Alphy submits it as a normal chat message, with web search allowed.
- When the reply is complete, Alphy sends the reply after the mark (section "Exact speech and host lines").
- A mini-app request first sends the host line `MINI_APP_STARTED` («Это появится на экране через несколько секунд.»). The ready mini-app sends its reply after the mark.
- A failed request sends the host line `FAILED` («Не получилось: ») and the error text of the message.
- A phone-action plan shows its plan card on the stage. Each end phase of the plan sends one more commentary: «Готово.», «Не получилось.», or «Действие отменено.».
- A spoken request that a picture or a model makes clearer can get the routing kind `screen`. The voice gets the answer after the mark at once (`LiveVoiceBridge.screenStarted`), and the screen comes later on the stage. This opener is one short spoken sentence, the short answer, and it names no part, no step, and no control (`ChatToolReply`), because the scene says the rest (section "The narration of a scene"). When the person asked in words to see a thing and the fast path gives no screen, the voice gets the host line `FULL_WAIT` («Быстро не получилось. Экран соберётся заново, это займёт около минуты.»), and the full generation builds a new app. A screen that the person did not ask to see ends with no more call and no more line. The fast path of a new screen is the template call (section "Template screens and the stage preview") and the plan call. The plan call has the section "System" of `kit/prompt.md` as its instructions.
- With an app on the stage, the routing call gets the shown screen (its title, its app file when the line fits 8,000 characters, its controls, and the present value of each control) as untrusted data (`docs/tool-integration.md`), and it can answer with the kind `press`. A press runs the named control as a tap, and then the voice says the answer. A label that names no control gives «На экране нет такой кнопки.». A tap that does not run in 2 s gives «Нажать не получилось.».
  A question about what the screen shows, or about a control or a value on it, gets the kind `answer` with a text from the shown screen. The routing call and the answer step never say that they cannot see the screen and never ask for a screenshot (`ChatToolReply.SHOWN_SCREEN_ANSWER`).
- The routing call of a spoken request also gets the last turns of the chat as untrusted turns before the shown screen and the request: the newest three request and reply pairs, at most 2,000 characters, and a long text keeps its start and its end. A text goes only when its message has clean provenance and no web sources. Each other text is a host note with none of its text. A reply that ended with an error, or a change or screen that gave no app, carries a host note that no new screen and no change came.
- A retry, a «да», or a short follow-up takes its meaning from these turns, and the decision brief states the whole request. A «да» to an offered phone action, automation, memory write, or app test is not a request for it. Earlier turns never authorize an operation that the latest request does not ask for. When a spoken request runs a web search, its grounded decision gets the same turns and the shown screen. The search query still comes from the latest request only.
- The answer step of a spoken request knows that screens exist in the talk. It never says that it cannot show or change a screen, and it claims no action as done. After a «да» to an offered phone action, automation, memory write, or app test, it says that nothing started and asks the person to say that request in full.
- A spoken change of the app on the stage runs the change by lines. The voice stays silent until the changed app is stored, and then it says the answer. A change that the kit cannot make becomes a new screen, which gets the plan of the old screen as untrusted data and sends only the plan call. The old app stays. When the new screen also fails, the voice says the waiting line, and the full generation builds a new app from the request, the brief, and the plan of the old screen.
- While a change of the app on the stage runs, the stage dims that app to alpha 0.45 (`CHANGING_ALPHA` in `ui/DialogueStage.kt`) and turns its controls off. Its drawing takes no touch, and its playback buttons are off (the `input` parameter of `NativeToolSurface`). A drawing session that stops during the dim keeps its last drawing and shows no error and no «Повторить». When the dim ends and the same revision still has the error, they show. Its frames and timers go on. The dim lasts while `ToolController.drafts` holds a draft of the stage app and the message of that draft is `generating` (`changing` in `ui/DialogueStage.kt`). The saved receipt of a change is not in that list. It applies to each change of the stage app by a request of the chat, spoken or typed.
- No frame of a change draws over the dimmed app, so the old scene and the parts of its change never mix. A frame of another operation, such as a new screen, still takes the place of the app.
- After a change, each new part of the stage app scrolls into view and gets an accent mark for 1 s (`NEW_PART_MS` in `ui/DialogueStage.kt`). The mark has the state description «Новое» (`stage_new_part`). A new part is a topmost node of the new UI that holds no node of the revision before. So a changed text is new, and a new row around an old part is not. The first revision of an app on the stage, and a UI that is new as a whole, mark nothing. The stage adds the mark through `LocalNodeMark` of `tools/NativeToolUi.kt`.
- The save of the change takes the newest state of the app: the kit computes the state of the changed plan again from that state, with no model call. A new revision from a different writer still stops the change.
- A chat that the voice layer covers shows its mini-app cards without their live app, so the stage app gets the one drawing slot. The cards run their apps again when the voice mode ends. When the stage app waits for the slot, the stage shows «Запустится, когда другое мини-приложение остановится.» above the app, or below a drawing that is not part of the app.
- When the full generation fails, the voice says the reason and a hint: «Экран не собран: сервис ИИ не ответил.», «Экран не собран: это не помещается на один экран.», or «Экран не собран: построить его не получилось.» (`FULL_NO_ANSWER`, `FULL_TOO_BIG`, `FULL_NOT_BUILT`), each followed by «Скажи всё одной фразой.» (`FULL_HINT`). The message keeps the failure code. When the voice mode ends during the full generation, the hand-off ends with no more line, and the request runs to its end.
- A build that falls back or fails writes a release log line with the tag `AlphyGeneration`. A line of the chat request has the operation, the decision kind, the step, the outcome or the fallback reason, the failure code, and the exception class. Where a build goes on to the loop, or where a typed change step falls back, the route writes a line with `kind=none` and the id of the app or the operation. A build that falls back and then fails has two lines. No line holds text of the person.
- A memory fact, an automation draft, or a course topic ends the voice mode with «Продолжите на экране».
- A hand-off request runs to its end, also after the voice mode ends. Its result stays in the chat.
- Text that the user types on the voice screen goes to Alphy. The reply goes to GPT-Live as quiet context (`session.thinking.append`).
  The thinking text is «Пользователь написал: », the typed text (at most 400 characters, `TYPED_MAX`), the mark, and the reply. The typed words come before the mark, so the voice says only the reply.

### Exact speech and host lines

The voice must say each text of Alphy as Alphy wrote it, with no control, offer, or inner word of its own. So each text of Alphy goes to GPT-Live after a mark, and the instructions tell GPT-Live to say the text after the mark word for word.

- The mark is `LiveProtocol.MARK` («Дословно: ») in `voice/LiveProtocol.kt`. Only the text of Alphy follows the mark.
- These appends hold the mark: the answer of a screen at its start, the reply of each other complete hand-off (an answer, a press, or a mini-app), the reply of a complete change, and the reply of a typed turn.
- A refinement choice and a press that did not run get a host line in place of the reply. A plan card gets the lines of its plan phases.
- `LiveProtocol.spoken` cuts the text at a sentence end, so that the mark and the text fit one append of at most 1,200 characters (`LiveVoiceBridge.MAX`).

The instructions (`LiveProtocol.INSTRUCTIONS`) have three lines for these texts:

- The mark line, which starts with «Если в тексте есть метка «Дословно:»». GPT-Live says only the text after the mark, word for word and once. It adds no introduction, no words of its own, no repeat, no question, and no offer.
- The stop line after it, which starts with «После этого текста замолчи и жди». After that text, GPT-Live stops and waits. It does not explain or go on, also when the answer seems incomplete.
- The line for a text with no mark, which starts with «Текст без метки скажи коротко на языке разговора». GPT-Live says that text briefly, in the language of the talk. It adds no button, action, offer, or question that the text does not have.

The instructions also set the language of the latest request and the form «ты». The routing call, the answer step, and the grounded decision of a spoken request also tell the chat model to address the person with «ты» (`ChatToolReply.SPOKEN_ADDRESS`), so the voice can say each text word for word. A typed request does not get this line. The wait phrase names no worker, no delegation, no request, and no mini-app.

A host line is a fixed line of the bridge that holds no text of Alphy. A host line goes with no mark, so GPT-Live can say it in the language of the talk. The host lines exist only in Russian.

- The constants of the companion object of `LiveVoiceBridge` (`ui/LiveVoiceBridge.kt`): `NOT_HEARD`, `BUSY`, `MINI_APP_STARTED`, `PRESS_MISSING`, `PRESS_FAILED`, `FULL_WAIT`, and `FULL_NO_ANSWER`, `FULL_TOO_BIG`, or `FULL_NOT_BUILT` with `FULL_HINT`.
- The approval lines: `PROPOSE`, `ASK`, `STARTED`, `CANCELLED`, `DONE`, and `NOT_DONE`.
- `FAILED` and the error text of the message.
- The line of a card that waits for a tap, from `LiveVoiceBridge.onScreenLine(context, phase, refinement)`. It takes the label of the button from the resources:

| Case | Line |
| --- | --- |
| A refinement choice | «Выбери на экране, что изменить.» |
| `NEEDS_PERMISSION` | «Нажми «%s» на экране.» with `agent_permission_allow` («Запросить доступ») |
| `WAITING_FOR_CAMERA` | «Нажми «%s» на экране.» with `agent_continue_camera` («Продолжить к камере») |
| `NEEDS_SELECTION` | «Выбери вариант на экране.» |
| `REVIEW` | «Подтверди на экране: нажми «%s».» with `agent_execute` («Выполнить») |
| Each other phase, or no plan | «Подробности на экране.» |

When a turn of the approval window cannot decide the plan, the bridge gives `onScreenLine` the plan phase only while the agent is not busy. So a busy plan in `REVIEW` gets «Подробности на экране.», because the card turns its execute button off while the agent is busy.

### A touch on the stage

The sound of a tap in the microphone must not stop the voice of Alphy as speech. So a touch on the stage closes the touch gate of the session for a short time. While the gate is closed, no microphone sound leaves the phone.

- The stage reports each touch (`reportTouches` in `ui/DialogueStage.kt`). The report goes through `LiveVoiceBridge.stageTouch` to `LiveVoiceSession.touch(down)`.
- A touch is down while any pointer is down. A cancel ends it, and so does the end of the stage. The report watches the first pass of each event and takes no event, so each control, scroll, and drawing keeps its input.
- While the gate is closed, GPT-Live and the barge-in detector get zero samples in place of each microphone block. The stream keeps its cadence. The mute state stays, and no mute event goes out.
- The gate closes at the down. While the touch stays down, the gate opens 1,000 ms after the down (`TOUCH_HOLD_MS` in `voice/LiveVoiceSession.kt`). So the person can speak while a finger moves a slider, and a lost end opens the gate too.
- The end of the touch closes the gate for 300 ms (`TOUCH_TAIL_MS`), because a tap also makes a sound at its end.
- The zero blocks do not teach the barge-in detector the echo level. So the echo of Alphy after a touch does not count as speech.
- Only a touch on the stage counts. A touch on the strip closes nothing, and a press by voice is no touch.

The gate has these limits:

- Each down closes the gate again and starts a new limit of 1,000 ms. So fast taps, each less than 300 ms after the end of the tap before, keep the gate closed for the whole series. GPT-Live hears no speech in that time.
- The stage reports one touch while any pointer is down. A tap of a second finger during a touch that stays down longer than 1,000 ms does not close the gate, so its sound reaches GPT-Live.

### The narration of a scene

A template file can hold narration lines (`kit/README.md`, section "Template files"): `say` for a scene with one sentence, or `say1` to `sayN` for the steps of a steps control.
The host turns on the controls of a new scene when its narration starts, and saves the narration later (`docs/tool-integration.md`). The voice speaks it as follows (`ui/LiveVoiceBridge.kt`, `ui/StageNarrator.kt`):

- A new scene with `say` sends `MARK + say` as thinking (`session.thinking.append`) when the narration is saved. A change, or a scene that opens again, says nothing.
- A new steps scene shows its answer step during the opener. Then the narrator walks the steps: it sends the sentence of step 1 after the mark, and at most one sentence waits at the voice.
- The narrator arms when the output transcript starts the waiting sentence. A piece that goes another way before the end of the sentence disarms it. So the opener, own words of the voice, and a repeat of a sentence move no step.
- The transcript leads the audio, and a reply ends by its audio, so a reply end can fall inside a sentence. An armed sentence stays armed across a reply end and the next reply start. After a reply boundary, a piece that does not go on with the armed words is tried alone, so older words block no later arm. The skip of the session uses the same rule. A started skip ends at a reply end only after its whole sentence came, or when a barge-in cut the reply. Only such a cut reply end, or a reply end at a sentence end, counts for a pending skip.
- The sync rule (`LiveVoiceSession.onsetAfter`): the target is the first voiced chunk after the arm with 200 ms of quiet before it, silent audio or a gap in arrival (`ONSET_QUIET_MS`). With no such chunk in 1.5 s (`ONSET_WAIT_MS`), the target is the frame received 0.45 s after the arm (`ONSET_FALLBACK_MS`). When the speaker plays the target (`LiveAudio.playedFrames`), the narrator presses the step and sends the next sentence.
- A step with no sentence is pressed at the next reply end, and its card text goes to the voice in place of the sentence. A scene with no narration walks with its card texts.
- Speech, a tap on the stage, a new hand-off, and the end of the mode stop the walk. The sentence that plays ends. A press of the walk that still runs is canceled, so the stage never runs it after the stop. The waiting sentence gets a skip (`LiveVoiceSession.skip`):
  - From the first transcript piece that starts the sentence, the session drops its transcript. From its next voiced start, the session holds its audio.
  - When the whole sentence came, the held audio is dropped, and so is the audio until a new text comes. The new text goes to the host, and its audio plays from the next voiced start.
  - When the transcript goes another way before the end of the sentence, the skip ends. The dropped text goes to the host, and the held audio plays.
  - A skip acts once and sends no event. A later send of the same sentence clears a skip that has not started, and so does the end of the first reply after the reply that was open at the skip.
- A tap on a step while the voice does not speak sends `MARK +` the sentence of that step as thinking, also during a walk. The sentence comes from the stored file of the present revision. A tap while the voice speaks, and a tap on a slider or a choice, send nothing. The touch gate holds only the microphone, so it does not hold this text.
- «Дальше» is a routing press of the next step. The host presses it, and the voice says the sentence of that step, not the routing text.
- While a steps scene is before its last step, the shown screen lists the host control «Продолжить по шагам» (`stage_continue_steps`). «Продолжай» presses it: the voice says the sentence of the next step, and the walk goes on from there to the last step.
- Between two sentences of a walk, the strip keeps the status «Alphy говорит», and a reply end opens no approval window.
- The bridge keeps the last three actions on the stage app for the shown screen, each with its source: `tap` (the person), `voice` (a routing press or «Дальше»), or `narrator` (a press of the walk). Taps on one control less than 1 s apart are one action with the last value. A new app on the stage starts with no action.

### Template screens and the stage preview

A new screen of a spoken request is a template file when a template can hold it: one picture, one control, and one card (`kit/README.md`, section "Template files").
A free app file is an app file that is not a template file.
The host check is the checks that `kitCheck` in `tools/agent/AppAgent.kt` runs on the compiled app before Alphy stores it.
They are the validation and the smoke run of a candidate (`docs/tool-integration.md`), and a search for a long decimal number on the screen.

- A new screen sends a template call first (`CloudGenerationStage.APP_FILE_TEMPLATE`), with the section "Template" of `kit/prompt.md` as its instructions.
  Its input is the task text of the plan call with no Dialogue line (the section "Dialogue" of `kit/prompt.md`). That text holds the request, the decision brief, the reply excerpt, and the source excerpts.
- When the stage shows no app, a new screen does not wait for the routing call. Its early template call starts at the hand-off, with the request alone.
  Alphy cancels that call when the routing call gives no screen, starts a web search, or prepares an action. After a web search, the new screen sends its own template call with the brief and the source excerpts.
- An `outside` reply sends the plan call with its Dialogue line, and the app gets a free app file.
- A reply with faults gets one repair call that names each faulty line. A reply that is no template file also gets the repair call.
  The kit puts each line of the repair reply in its place by its name. It leaves out each line that still has a fault, and the screen keeps each other line (`kit/README.md`, section "Faults by line").
  The app stores the template file with no faulty line in `appFile` (`docs/tool-integration.md`).
- A template call that ends early after its first complete line gets one call for the missing lines. The kit merges the reply of that call by name.
- A new screen fails as a whole in these cases, and then the fast path gives no screen (section "Session and hand-offs"):
  - After the repair, the first line has a fault, or no shape, item, or `cells` control is valid.
  - After the repair, the reply is no template file, or the kit finds a fault that no line owns.
  - The template call ends early with no complete first line.
  - The repair call or the call for the missing lines fails or replies `outside`.
  - The host check of the complete screen finds a fault. This fault gets no repair call.
- A spoken change of a template app sends a template change call with the request and the stored template file. The reply holds only the changed lines and `delete NAME`, and the kit merges them by name (`kit/README.md`, section "Merge by name").
  A reply with faults gets one repair call. `kit/README.md` (section "Faults by line") gives what the kit does with a changed line that still has a fault. A template change call that ends early gets no call for the missing lines.
- An `outside` reply to a template change call runs the change by lines on the expanded app file. The app then has a free app file. A typed change of a template app runs that change too.
  A template change that gives no app falls back as a change that the kit cannot make (section "Session and hand-offs").

The stage preview:

- While the reply of the first template call arrives, the stage draws a stage preview of its complete lines. The first preview shows at the first complete line: the title and the picture at its final size.
  Then the control, each part, and the card draw, each when its line is complete. An unfinished last line does not draw.
- A line with a fault does not draw before its repair, and each other line stays.
- A preview has the layout of the live stage app, with its control off. It takes the place of the shown app, so a later new screen replaces the shown app at its first line.
- A live input of a preview, such as a slider, shows no input note (`tool_live_input_*`) and no «Применить» button, also after a drag (`NativeLiveActions` in `tools/NativeToolUi.kt`). The live app on the stage before its first snapshot does the same. So the screen does not move when the live app comes. Outside the stage, the note and the button stay.
- The kit preview step makes each preview (`kit/README.md`, section "The preview step"). `docs/tool-integration.md` gives the rules of each update.
- While `ToolController.previewBeforeRouting` is true (the default), the early template call shows its previews before the routing call ends. When it is false, the newest preview waits until the routing call gives the kind `screen`.
- When Alphy cancels the early template call, or the request ends, its preview goes away with no error and no spoken line. So a turn that gets no screen ends with no preview.
- While the host check of a new screen (a template or a free app file) runs, the stage shows the compiled app as a preview. A template change makes this preview too, and the stage shows it as the change rule below says.
- A fallback, a cancel, or a failure of a screen removes its preview, and a later preview of that screen does not show.
- When the app is stored, the canvas keeps the drawing of the last preview until the live app draws. The control turns on when the operation is no longer busy.
- A spoken change of a template app makes a preview of each changed line, but the stage does not draw it over the shown app (`StagePreview.covers` in `ui/DialogueStage.kt`). The shown app stays dimmed until the dim ends (section "Session and hand-offs"). Then the last preview of the change, the compiled changed app, holds the canvas until the live app draws. The stored app stays in view while the change runs, so a value that the person moved stays in view and reaches the changed app.
- An `outside` reply to a template change call, a change of a free app file, and a typed request show no preview.
- `ToolController.previewStream` is true by default. When it is false, no screen gets a preview.
- The generation trace marks `template_call_start` and `template_outcome` (`clean0`, `clean1`, `partial1`, `outside`, `whole`, or `failed`) for the template call of a new screen.
  It marks `stage_preview` with the `holds` value of each preview that becomes the current preview of `ToolController.stagePreviews`, and `stage_preview_gone` when a current preview goes away with no app. A preview of a change is current also while the stage does not draw it over the dimmed app.
  It marks `controls_on` when the operation is no longer busy after a screen stored its app.

### Data sent to OpenAI

- The microphone audio, continuously while the microphone is not muted.
- Fixed Russian instructions (`LiveProtocol.INSTRUCTIONS` in `voice/LiveProtocol.kt`).
- A history preload: the latest text messages of the selected conversation, at most 128 messages and 8,192 tokens. Attachments, images, and mini-app content are not sent.
- The commentary and thinking texts: the replies of Alphy after the mark, the host lines, and the typed words of a typed turn.

Each hand-off request also goes to the chat model of the account, as a typed request does.
With an app on the stage, the routing call and the grounded decision of a spoken request also get the shown screen (`ShownScreen.line()` in `tools/ShownScreen.kt`).
It holds the title, the app file when the line fits, the labels of the controls, and `values`: the present value of each control by its label.
A steps scene adds `step` (the index, the count, and the label of the present step), and the line holds `actions`: the last three actions on the stage app, each with `who`, `control`, and `value`.
`values` also comes from an app that takes no press. An app with private results gives no `values` (`docs/tool-integration.md`).
The voice mode needs an OpenAI account, so this data goes to OpenAI too.
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

A hand-off or a typed turn runs until the bridge has read the end of its message (`LiveVoiceBridge.handOffRunning()`). So a silence check between the end of a build and that read cannot close the talk.
When a complete hand-off puts its app on the stage, the bridge starts the 60 s count again (`LiveVoiceSession.countSilence()`, called from `report()`). The person needs that time to see a screen, which can come with no spoken text.
The hold of a running hand-off stays limited by `LiveTiming.handOffHoldMs` (180 s).

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
While voice approval is off, the plan card needs a tap, and GPT-Live gets the line of `onScreenLine` for the plan phase, such as «Подтверди на экране: нажми «Выполнить».» (section "Exact speech and host lines").

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
- GPT-Live changed the line for a confirmation on the screen into a request to say «да» or «нет».

After run 1, the session finds replies by the sample level (rulings R24 to R30).
A chunk is voiced when its RMS level is above 0.01 (`LIVE_VOICED_RMS`).
A reply ends after 800 ms of silent stream audio after its last voiced chunk (`replyGapMs`), or after 800 ms without any chunk.
An instruction line of that build told GPT-Live to send the user to the button on the card for the line of a confirmation on the screen. Section "Exact speech and host lines" gives the present lines.

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
