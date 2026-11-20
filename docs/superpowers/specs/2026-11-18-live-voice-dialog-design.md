# Live voice dialog design

Date: 2026-11-18. Base revision: `origin/main` `ce37f1c` (Alpha 14 with the unified AI provider).

Source labels: (User) marks a decision of the user in the design conversation of 2026-11-18. (Claude) marks a proposal of Claude that the user can change. "Assumption" marks a statement without direct evidence, with its test.

## 1. Intent

1.1 (User) Alphy gets a full-duplex voice dialog. Alphy listens and speaks at the same time. The user can interrupt Alphy while it speaks, and no tap is necessary between turns.

1.2 (User) The visual target is the ChatGPT voice screen. Two reference screenshots show an orb at the bottom, a result card above it (a weather forecast with an hourly chart, or a list of matches), and a composer row with «+», a text field, a microphone, and a close button. The user wants "something similar".

1.3 (User) The voice comes from OpenAI GPT-Live (`gpt-live-1`) only. This is approach B in section 4.

1.4 (User) The card above the orb shows the reply of Alphy's own chat agent: the text answer with its sources, and a mini-app when the agent makes one.

1.5 (User) The dialog runs only in the foreground. It ends when the screen locks or the user leaves Alphy.

1.6 (User) In voice mode, the user approves a phone action, such as a timer, by voice (5.5.10).

1.7 (User) The voice mode must also work in the assistant button mode (5.11).

## 2. Goals, non-goals, anti-goals

Goals:

- A spoken dialog in Russian. GPT-Live's own speech, including its waiting line, starts about 1 s after the user stops speaking (section 3). The result of a hand-off takes the time of the chat agent: the normal chat answer time (not measured for voice), or 16 to 31 s for a mini-app.
- The user can interrupt Alphy at any time, and Alphy stops speaking at once.
- Every fact, search, phone action, and mini-app comes from Alphy's existing chat agent, so voice mode can do what the text chat can do.
- The result of each request shows as a card on the voice screen and stays in the chat history.

Non-goals (by the user's decisions in section 1):

- No duplex dialog for Anthropic, OpenRouter, or DeepSeek accounts. They keep «Непрерывный голосовой сеанс» as it is.
- No background or locked-screen dialog.
- No fixed set of native result cards (weather, schedule). The card shows the agent reply and its mini-app.

Anti-goals (the feature fails even if it technically works):

- GPT-Live says that it did an action (a timer, a reminder, a search) that Alphy did not do. The spike showed this failure without instructions (section 3).
- Alphy hears its own voice from the loudspeaker and answers itself.
- A phone action runs without the user's own approval: from Alphy's voice through the loudspeaker, from text on the screen, or from a decision of GPT-Live.
- The session stays open and costs money while nobody speaks.
- The microphone stays open after the voice screen closes, after a pause, or after a screen lock.

## 3. Evidence: GPT-Live spike of 2026-11-18

A throwaway desktop script (Bun, not app code) connected to GPT-Live with the user's own `OPENAI_API_KEY`. It ran 5 sessions of about 20 s each, for about $0.085 in total. The input was `app/src/androidTest/assets/voice/russian.wav` («Поставь таймер на пять минут») and a macOS «Milena» recording with a long question.

| Question | Result |
| --- | --- |
| Can the phone connect with the user's key? | Yes. `wss://api.openai.com/v1/live/sessions` with `Authorization: Bearer <key>`. No server and no ephemeral key were necessary. |
| Russian input | The input transcript was exact: «Поставь таймер на пять минут». |
| Russian output | Voice `marin` answered in Russian. The voice table of the documentation lists no Russian voice. The user has the recordings. |
| Delay | 0.9 to 1.6 s from the end of speech to the first reply text. Reply audio started about 1.1 s after the end of speech. |
| Audio format | `audio/pcm` at 16,000 Hz for both directions, as `session.started` confirmed. |
| Output stream | One continuous stream that includes silence. The session is billed for every open second. |
| Client delegation | With `delegation` omitted, the session runs in client mode. `session.delegation.created` came about 1 s after the user stopped speaking. GPT-Live said a filler line («Сейчас поставлю»), then spoke the text of `session.commentary.append`. It also delegated a knowledge question without an instruction to do so. |
| False claims | Without delegation instructions, GPT-Live said «Хорошо, запускаю таймер на пять минут» and nothing ran. |
| Interruption | GPT-Live stopped in mid-sentence and answered the new request. It kept speaking for about 2.4 s over the user, until the user's phrase ended. |

Not tested: echo on a phone loudspeaker, a session longer than 30 s, more than one `session.commentary.append` for one delegation, and context without an open delegation.

## 4. Approaches

| Approach | Description | Trade-off |
| --- | --- | --- |
| A | Two engines in one mode: GPT-Live for OpenAI, and a duplex dialog built on the phone for the other providers (OpenRouter with ElevenLabs voice). | Widest coverage. Two paths to build and test. OpenRouter has no realtime endpoint, so the phone-built path waits for transcription and speech synthesis at each turn. |
| **B (selected)** | GPT-Live only. The mode exists only with an OpenAI account. | The smallest build with the most natural dialog. Other providers get no duplex dialog. |
| C | The phone-built duplex dialog on every provider, no GPT-Live. | Each answer waits for the pause, transcription, the chat reply, and the first spoken sentence: seconds, not 1 s. |

(User) B, because GPT-Live talks in both directions by itself.

Card source, two options. (User) The first option.

- The reply of Alphy's chat agent, with its mini-app when one is ready. This reuses the agent and its renderers. A mini-app arrives 16 to 31 s after the request (recent generation measurements), well after the spoken answer.
- A fixed set of fast native cards filled with structured data. Closer to the screenshots, but each card type must be built, and other requests fall back to text.

## 5. Design

### 5.1 Availability and entry

5.1.1 (User) The voice mode exists only when the account provider is OpenAI (`AiProvider.OPENAI`).

5.1.2 (Claude) The entry is in the composer of the full chat. When the field is empty and the provider is OpenAI, the disabled send button becomes the voice-mode button «Голосовой режим». This is the ChatGPT pattern. With text in the field, the button sends as today. The existing composer microphone keeps its one-shot recording.

5.1.3 (User) The compact assistant that the assistant button opens (`AssistantActivity`) also gets the voice mode (5.11). (Claude) The course composers do not get it in this version. They keep their current voice controls.

5.1.4 (Claude) The voice mode runs in the selected conversation. With no selected conversation, the first hand-off (5.5) creates one, as `submitDraft` does today.

5.1.5 The start needs the microphone permission. Use `rememberVoiceStart` and `MicrophonePermissionGuidance` (`ui/VoiceControls.kt`) as the composer microphone does.

5.1.6 The voice mode and the existing `VoiceSession` never run at the same time. Starting one stops the other.

### 5.2 Voice screen

```
┌───────────────────────────────┐
│ ┌───────────────────────────┐ │
│ │ Alphy reply: text, sources│ │
│ │ [mini-app when ready]     │ │
│ └───────────────────────────┘ │
│                               │
│            (Alphy)            │  listening or speaking pose
│          status line          │
│                               │
│ [+] [ Спросите Alphy ]  🎤  ✕ │
└───────────────────────────────┘
```

5.2.1 (Claude) A full-screen layer over the chat, so the chat stays under it and shows the saved turns when the layer closes.

5.2.2 (Claude) The orb is the Alphy character. The character concept (`docs/superpowers/specs/2026-11-05-alphy-character-concept.md`) asks for a listening pose during voice interaction. The screen uses a listening pose while the user speaks or the dialog waits, and a speaking pose while Alphy speaks. The level of the active voice (the user's microphone level, or the playback level) drives a small scale or glow change. With reduced motion, the pose changes without animation.

5.2.3 The card shows the assistant message of the latest hand-off with the existing chat message renderer (`ChatMessageCard` and the mini-app surface). The card shows the queued and running states of that message as the chat does. The card scrolls when it is taller than the space above the orb. Before the first hand-off, the area above the orb is empty.

5.2.4 A status line under the orb always states the microphone state and what Alphy does: «Слушаю», «Alphy говорит», «Alphy выполняет запрос», «Микрофон выключен». The character concept requires a clear microphone status, because a pose alone is not sufficient.

5.2.5 🎤 mutes and unmutes the microphone (5.4.6). ✕ ends the voice mode (5.8). Both have content descriptions: «Выключить микрофон», «Включить микрофон», «Завершить голосовой режим».

5.2.6 The screen stays on while the voice mode is open (`FLAG_KEEP_SCREEN_ON`), because the dialog ends at a screen lock (1.5).

### 5.3 Live session

5.3.1 The voice mode opens one WebSocket to `<OpenAI base URL>/v1/live/sessions` with `wss` and `Authorization: Bearer <account key>`. Take the base URL from `Endpoints`, so tests can point the session at `LoopbackServer`. Use OkHttp (`com.squareup.okhttp3:okhttp:5.5.1`, already a dependency) for the WebSocket. No new dependency is necessary.

5.3.2 The first message is `session.start`:

```json
{"type": "session.start", "event_id": "<id>", "session": {
  "model": "gpt-live-1",
  "instructions": "<5.3.3>",
  "input": ["<5.3.4>"],
  "audio": {"format": {"type": "audio/pcm", "rate": 16000}, "output": {"voice": "marin"}}
}}
```

The session omits `delegation`, so it runs in client mode (section 3). Alphy sends audio only after `session.started`.

5.3.3 (Claude) The instructions, in Russian, state these rules. The builder writes the final text and checks it in the live check (6.3).

- You are the voice mode of the assistant Alphy. Speak Russian, briefly and naturally. The user speaks Russian or English.
- You do not do actions and do not know current facts. Hand every request for a phone action, a search, current facts, weather, a mini-app, memory, files, or anything on the screen to Alphy through delegation.
- Do not ask clarifying questions about a task yourself. Hand the task to Alphy at once. Alphy asks when it needs more facts, and you speak its question.
- While Alphy works, say one short waiting line.
- Never say that an action is done before Alphy confirms it.
- When Alphy proposes an action, read the proposal and ask the user to say «да» or «нет». Never say that the action is approved or started. Alphy confirms it.
- Retell Alphy's answer briefly. The full answer is on the screen.
- You do not see the user's screen. Hand every question about the screen to Alphy.
- When Alphy says that a mini-app is in progress, say so once. Do not invent its content.

5.3.4 (Claude) `input` preloads the latest text messages of the selected conversation: the user and assistant messages with status `complete`, newest kept first, at most 128 messages and at most 8,192 tokens (the documented limits). Estimate tokens as characters divided by 3 and keep a margin. Attachments, images, and mini-app content are not sent. Tool messages send their reply text only.

5.3.5 The model is `gpt-live-1` and the voice is `marin`. Pin both next to the OpenAI profile (`cloud/ProviderProfile.kt`), as the other models are pinned.

5.3.6 The key never appears in a log, an error text, or a test report. Error texts follow the provider error rules of the unified provider spec (section 13).

### 5.4 Audio path

5.4.1 Capture: `AudioRecord` with `MediaRecorder.AudioSource.VOICE_COMMUNICATION`, 16 kHz, mono, PCM16. Attach `AcousticEchoCanceler` and `NoiseSuppressor` to its audio session when they are available. Send 20 ms blocks as `session.input_audio.append` with base64 PCM, continuously while the microphone is not muted.

5.4.2 Playback: `AudioTrack` in streaming mode, 16 kHz, mono, PCM16, with `AudioAttributes.USAGE_VOICE_COMMUNICATION` and `CONTENT_TYPE_SPEECH`. Write each `session.output_audio.delta` to it as it arrives.

5.4.3 Routing: set `AudioManager` mode to `MODE_IN_COMMUNICATION` for the session, so the platform echo canceller uses the playback as its reference. Without a wired or Bluetooth headset, select the built-in speaker with `setCommunicationDevice`, because the communication mode routes to the earpiece by default. Restore the mode and clear the communication device when the session ends.

5.4.4 Audio focus: one `AUDIOFOCUS_GAIN_TRANSIENT` request for the whole session, as `VoiceSession` does. A focus loss ends the voice mode with «Голосовой режим прерван другим звуком».

5.4.5 Interruption (barge-in). The server alone overlapped the user for about 2.4 s (section 3), so Alphy also stops its own playback locally. While playback has audio, a local voice detector on the echo-cancelled microphone signal watches for the user. When the user's voice is present for at least 200 ms, Alphy flushes the `AudioTrack` buffer. Alphy then drops reply audio while the local detector still hears the user, because the server can keep speaking until the user's phrase ends. After that, Alphy drops the rest of the current reply audio until the next reply starts. The detector uses an adaptive threshold above the residual echo level. Reuse the adaptive noise threshold of `PcmCapture` where it fits. Audio input continues during all of this, so GPT-Live hears the user.

- Assumption (failed in live check run 1): the output stream has no event that marks a new reply, so "the next reply" means the next output audio after at least 300 ms of output silence. Test: the loopback test and the live check.
  - Run 1 result: GPT-Live sends one continuous stream of 100 ms chunks, silence included, so the arrival of chunks never pauses between replies.
  - Replacement (rulings R24 and R27): a chunk is voiced when its RMS level is above 0.01 (`LIVE_VOICED_RMS`). A reply ends after 800 ms of silent stream audio after its last voiced chunk (`replyGapMs`), counted in samples. A timer also ends the reply when no chunk arrives for 800 ms.
  - Run 2 result: 8 replies, one reply end for each, the same count as the output transcript. Pauses inside a reply were at most 500 ms. Silence between replies was at least 1,000 ms, after the interrupted reply.
- Assumption: platform echo cancellation on the loudspeaker removes enough of Alphy's voice that GPT-Live does not hear itself and the local detector does not fire on echo. Test: the device echo check (6.4). If it fails, the fallback is the decision of the user. Options are headset-only use or WebRTC audio processing (a new native dependency).

5.4.6 Mute: 🎤 stops the capture and sends `session.input_audio.mute`. Unmute restarts the capture and sends `session.input_audio.unmute`. GPT-Live can finish its current speech while the microphone is muted. An `error` event for a sent mute or unmute does not end the voice mode, because the phone stops the capture in any case (ruling R37). The live check (6.3) tests that GPT-Live accepts both events.

### 5.5 Hand-off to Alphy (client delegation)

5.5.1 Alphy keeps the input transcript of the session from `session.input_transcript.delta`, with `start_ms` and `end_ms`.

5.5.2 At `session.delegation.created`, the request text is the last user turn, up to the `offset_ms` of the event. It holds the input transcript deltas that start after the previous hand-off. They also start at or after the latest end of a GPT-Live output that did not talk over the current speech run. Small talk that GPT-Live already answered is not part of the request. If that text is blank, Alphy waits up to 500 ms for more input transcript deltas. If it is still blank, Alphy sends `session.commentary.append` with «Alphy не расслышал запрос.» and does nothing else.

- The current speech run (rulings R31 and R32): the input deltas back from the last one at or before `offset_ms`, while the gap from one delta's `end_ms` to the next delta's `start_ms` is at most 1,000 ms. The run exists only when its last delta ends at most 1,000 ms before `offset_ms`.
- An output that ends after the start of the current speech run talked over the user, so it does not cut the request. Output ends at or before the run start still cut. Without a current run, every output end at or before `offset_ms` cuts, so a blank result waits as above.
- The late last word of an answered turn is not part of the request. A speech run that began before the cutting output end, has no hand-off, has no output end before its last delta, and ends with one single input delta at most 200 ms (one stamp step) after that end gave that delta to the answered turn. Basis: a person who heard the answer starts later, because the answer audio ends after its output stamp. In form J (`live-8377b64de824`), « системе» started at the end of «Юпитер.». Each sign makes the cut rarer, so the worst result is the request text of the rules above. Known limit: a new request loses its first delta when that delta starts within 200 ms of the stamp end of an answer, within 1,000 ms of a turn that GPT-Live did not talk over, and before a pause of more than 1,000 ms. A late word still joins the next request after two late deltas, or when its run holds a hand-off.
- Known limit (R32): answered small talk followed by a request within 1,000 ms on the input stamps joins the request. Assumption: a user who waits for the answer starts later. In run 2, the output audio arrived 0.6 to 1.4 s after the last `end_ms` of its reply.

- Assumption: the input transcript of the turn is complete, or complete within 500 ms, when `session.delegation.created` arrives. The spike did not log the order of these events. Test: the live check (6.3) logs the order.

5.5.3 Alphy submits the request text as a normal user message through the existing path (`ShellViewModel.submitDraft`, with search allowed as in `send()`). The existing request queue (`requests`, `dispatchNext`) runs hand-offs in order. When the queue has no room, Alphy sends commentary «Alphy ещё занят предыдущими запросами.».

5.5.4 Each hand-off keeps the pair (delegation id, assistant message id). The card (5.2.3) shows the assistant message of the latest hand-off.

5.5.5 When the assistant message of a hand-off reaches status `complete`, Alphy sends `session.commentary.append` with that delegation id. The content is «Ответ Alphy: » plus `speakableText` of the reply (`voice/SpeechOutput.kt`), cut at a sentence end to at most 1,200 characters, below the 500-token limit. Alphy does not speak a streaming answer before `complete`, because the agent can still change the operation (`decision_changed`).

5.5.6 A mini-app request has no reply text until the tool is committed. In `ShellViewModel.startRequest`, `prepareToolOperation` sets the assistant message to status `generating` with a `toolOperationId` and a progress text, and `committed` sets the reply text and `toolId` together. So:

- When the hand-off message enters status `generating`, Alphy sends commentary «Alphy готовит мини-приложение, оно появится на экране.».
- When the message reaches status `complete` with a `toolId`, Alphy sends commentary «Мини-приложение готово. Ответ Alphy: » plus the speakable reply text, as in 5.5.5.

- Assumption: GPT-Live accepts and speaks more than one commentary for one delegation. Test: the live check. If it does not, Alphy sends only the first commentary, and the card alone shows the ready mini-app.
- Assumption: GPT-Live still speaks a commentary that arrives 20 to 30 s after its delegation. The spike answered each delegation at once. Test: the live check delays one commentary by 25 s.

5.5.7 A failed request sends commentary «Alphy не смог выполнить запрос: » plus the error text of the message. The card shows the error as the chat does.

5.5.8 Some hand-offs do not end in a text answer. Their message finishes with a fixed notice, and the work continues in another surface. The voice mode handles them as follows.

- **Phone actions.** The message finishes with «Проверьте действия на карточке плана.» (`agent_plan_ready`). The plan runs only after the user acts on `AgentPlanCard`, which `AlphaShell` shows as a separate list item, not inside `ChatMessageCard`. The voice card shows the plan card of the hand-off under the reply. The user approves the plan by voice (5.5.10) or with a tap on the card. (Claude) After the run, Alphy watches the `AgentPhase` of the plan and sends one more commentary: `COMPLETED` «Готово.» with the completion text of the plan card, `FAILED` «Не получилось.» with the error text, `STOPPED` «Действие отменено.». Without this, GPT-Live never confirms the action, by the rule of 5.3.3.
- **Permission requests and refinement choices.** Their controls show in the card as in the chat. Alphy sends commentary «Нужно подтверждение на экране.». A system permission dialog follows 5.8.1.
- **Memory fact, automation draft, course topic.** (Claude) These open an editor or a sheet in the chat (the memory editor, `chatAutomationSetup`, `CoursePlanCard`). The voice mode ends with the status «Продолжите на экране», as `voice.stop()` does today for the memory editor.

5.5.9 (Claude) A hand-off request runs to its end even when the user interrupts the spoken answer or the voice mode ends, ✕ included. Its result stays in the chat. Reason: a mini-app takes up to 30 s, and losing it at ✕ would surprise the user. The stop control of the chat composer cancels a running request as it does today.

5.5.10 Voice approval of phone actions.

(User, 2026-11-18) In voice mode, the user can approve a phone-action plan by voice. Before this decision, the user saw the risk: a misheard «да», or Alphy's own voice, can start an action.

(Claude) Rules:

1. Alphy's code decides the approval, not GPT-Live. GPT-Live only reads the plan aloud and asks. No delegation, commentary, screen text, or mini-app content can approve a plan. Only the user's own input transcript or a tap on the card can approve a plan.
2. When the plan of a hand-off reaches phase `AgentPhase.REVIEW` (the only phase where `AgentPlanCard` shows its run button «agent_execute»), Alphy sends commentary «Alphy предлагает: <the speakable step list of the plan card>. Скажите «да», чтобы выполнить, или «нет», чтобы отменить.». Alphy records the plan id and revision as the spoken plan.
3. The approval window opens when GPT-Live finishes speaking that proposal: at the `end_ms` of the last output transcript delta after the commentary is acknowledged. The window lasts 30 s.
4. Alphy examines the first user turn that starts inside the window and after the end of Alphy's playback. The whole turn, without case and punctuation, must be one phrase from a closed list:
   - approval: «да», «да, запускай», «запускай», «выполняй», «подтверждаю», «давай», «yes», «go ahead»;
   - rejection: «нет», «не надо», «отмена», «отмени», «стоп», «no», «cancel».
5. On approval, Alphy calls `AgentConversation.execute(plan)` only if the current plan still has the spoken id and revision and is still in phase `REVIEW`. On rejection, Alphy calls `AgentConversation.dismiss(plan)`, as the cancel button of the card does in that phase. Then Alphy sends commentary «Запускаю.» or «Действие отменено.», and 5.5.8 reports the result.
6. A turn that is not exactly one listed phrase does not approve. Examples are a longer sentence, a change request, or a question. It goes to Alphy as a normal hand-off (5.5.2), and the plan stays on the screen without approval. A revised plan has a new revision and needs its own approval.
7. If GPT-Live opens a delegation for an approval or rejection turn, Alphy answers that delegation with the commentary of rule 5. It does not submit the turn as a chat message.
8. A turn that starts while Alphy's playback has audio never approves. When the window ends without a decision, voice approval of that revision closes, and the card keeps its tap controls.
9. The tap on the card works at all times. Voice approval replaces only the run button. A system permission dialog (`NEEDS_PERMISSION`), a selection (`NEEDS_SELECTION`), and the camera step (`WAITING_FOR_CAMERA`) still need the screen.
10. Voice approval is on only when the device echo check (6.4) passed on the device model. (Claude) The app keeps the passed models as a pinned list of `Build.MODEL` values, which the echo check evidence updates. Otherwise the card needs a tap, and GPT-Live says «Нужно подтверждение на экране.».

- Assumption: the `start_ms` and `end_ms` values of the input and output transcripts use one session timeline, so rule 3 and rule 8 can compare them. Test: the live check (6.3).

### 5.6 Chat history

5.6.1 (Claude) Each hand-off saves its user message and assistant message in the conversation, as a typed message does. The user message is the request text of 5.5.2.

5.6.2 (Claude) Talk that GPT-Live answers by itself, without a hand-off, is not saved. Reason: the chat history keeps requests and results, and the spoken small talk has no card. The user can change this decision.

### 5.7 Typing and «+» in voice mode

5.7.1 (Claude) Text and attachments from the voice screen composer go to Alphy's agent as normal messages through `submitDraft`. The card shows the reply.

5.7.2 (Claude) When that reply is complete, Alphy sends `session.thinking.append` (quiet context, at most 1,200 characters) with «Пользователь написал: <text>. Ответ Alphy на экране: <speakable reply>.» GPT-Live does not read it aloud, but it can talk about it.

- Assumption: GPT-Live accepts `session.thinking.append` without an open delegation. Test: the live check. If it does not, the typed turn has no context in the live session, and the card alone shows the reply.

### 5.8 End of the voice mode and failures

The voice mode ends at any of these events. Each end closes the capture and the playback, sends `session.close` when the socket is open, restores the audio mode and focus, and closes the screen layer. The chat shows the last status in the composer status line.

| Event | Status |
| --- | --- |
| ✕ | none |
| Screen off, or the activity stops while no screen or app that Alphy opened is in front (1.5, 5.8.1) | none |
| 60 s without user speech, by the local voice detector, while no hand-off runs (Claude). A running hand-off holds this end off for at most 180 s after the last user speech or Alphy output (ruling R38). | «Голосовой режим завершён: нет речи 60 с» |
| Audio focus loss | «Голосовой режим прерван другим звуком» |
| The account provider is no longer OpenAI, or the key is removed | «Голосовой режим доступен только с ключом OpenAI» |
| `session.closed` with `expired` | «Сеанс голосового режима истёк. Начните заново.» |
| `session.closed` with `content` | «OpenAI прервал голосовой режим по правилам содержания» |
| `connection_lost`, a socket failure, or no `session.started` within 10 s | «Связь с OpenAI прервалась» |
| An `error` event or an HTTP refusal of the upgrade | The provider error text by the unified provider spec, section 13. A key error opens the «Сервис ИИ» link, as `VoiceSession.settingsRequired` does. |

Requests that a hand-off started keep running after the voice mode ends (5.5.9). Their results stay in the chat.

5.8.1 (Claude) A system screen or an app that Alphy itself opens for the voice mode is not "leaving Alphy". Examples are the location permission request of `WeatherLocationAccess`, the permission request of `AgentPlanCard`, the microphone permission request, the pickers of «+», and the app that a phone-action step opens (`clock.setTimer` sends `AlarmClock.ACTION_SET_TIMER` without `EXTRA_SKIP_UI`, so the Clock app comes to the front). A source link that the user opens in a browser is leaving Alphy. While such a screen or app is in front, the voice mode mutes the microphone (5.4.6), flushes the playback, and keeps the session open. When Alphy is in front again, the voice mode unmutes. The 60 s rule of the table still applies. The existing `VoiceVisibility` stops `VoiceSession` at `ON_PAUSE`, so the voice mode needs its own lifecycle rule and must not reuse that one as it is.

### 5.9 Consent and privacy

5.9.1 (Claude) No new consent line. The OpenAI consent already lists «Записи голоса для распознавания речи», and chat text already goes to OpenAI. A new consent line would change the consent identity and stop automations (unified provider spec 14.7).

5.9.2 Audio is never stored on the phone. Transcripts exist only in memory for the session, except the saved hand-off messages (5.6).

5.9.3 The microphone runs only while the voice screen is visible, the device is unlocked, and the session is open. The status line shows its state at all times.

### 5.10 Cost

GPT-Live costs $0.05 per minute, billed per second of open session, including silence. The 60 s silence end (5.8) limits idle cost. Hand-offs also cost the normal chat requests of the account. (Claude) The voice screen shows no price or timer.

### 5.11 Assistant button mode

5.11.1 (User, 2026-11-18) The voice mode also works in the assistant button mode. Today the assistant button starts `AlphaVoiceSession` (`surfaces/AssistantSession.kt`). That session captures the screen (assist structure, page text, screenshot) and opens `AssistantActivity`, a compact sheet over the current app with `ChatPane`, the screen context rows, and `Composer`.

5.11.2 (User, 2026-11-18) With an OpenAI account, a press of the assistant button starts the voice mode at once. The user saw the cost: every press opens a paid session. Rules:

- (Claude) The sheet opens with the voice view, and the session starts when `AssistantActivity` is resumed and the assistant is available. Without an OpenAI account, the sheet opens as today.
- Without the microphone permission, the sheet asks for it through `rememberVoiceStart`, as the composer microphone does. The session starts only after the permission is granted.
- (Claude) The screen capture arrives after the sheet opens (`onHandleAssist`, `onHandleScreenshot`). The first hand-off waits up to 2 s for a capture that is still in progress, then goes without it.
- (Claude) If the user says nothing in the first 10 s after the press, the voice mode ends and the sheet stays open with its normal composer. Later silences follow the 60 s rule of 5.8.
- (Claude) In the sheet, ✕ of the voice view ends the voice mode and shows the normal sheet. The close button of the sheet closes everything.

5.11.3 (Claude) Layout. In the sheet, the voice view replaces the chat pane: the card, the orb, and the status line, in the sheet height. The screen context rows (screen text, page text, screenshot) stay visible above the composer row, with their remove controls, so the user sees what goes to Alphy.

5.11.4 (Claude) Screen context. The first hand-off of an invocation goes through the existing assistant send path (`ShellViewModel.sendAssistant`) with the request text as the draft. So it carries the screen context and the screenshot that the user did not remove, exactly as a typed assistant request does, and the capture is discarded after it. Later hand-offs carry no screen context, as typed requests do today. GPT-Live does not see the screen. The instructions (5.3.3) send every question about the screen to Alphy.

5.11.5 Conversation. The voice mode uses the conversation that the sheet shows. A new invocation starts a new chat (`newChatPending`), as today.

5.11.6 Lock. The assistant does not start on a locked device (`supportsLaunchVoiceAssistFromKeyguard="false"`), and `AssistantActivity` shows the unlock prompt when `assistantUnavailable()` is true. The voice mode starts only when the assistant is available, and it ends at `onLockscreenShown`.

5.11.7 (Claude) End. In addition to 5.8, the voice mode ends when the sheet closes (its close button, `onHide`, a swipe down) and when the sheet hands off to the full app (`openApp`, for example to open a mini-app in full). The rule of 5.8.1 for screens and apps that Alphy opens applies in the sheet too. `CompactAssistant` calls `VoiceVisibility(model.voice)` today. The voice mode uses its own lifecycle rule there too.

5.11.8 The assistant menu item «Непрерывный голосовой сеанс» stays for accounts of other providers.

## 6. Verification

6.1 Behavior tests start with failing cases, before the production code, as the shared engineering policy requires.

6.2 Device workflow tests (`tools/compose_acceptance/run.py suite`) use a fake GPT-Live WebSocket server on `LoopbackServer` (or an equivalent loopback WebSocket server) and the fixture audio. They cover:

- start: the button appears only with an OpenAI account and an empty field; `session.start` has the fields of 5.3.2 and the history of 5.3.4;
- a hand-off: `session.delegation.created` submits the transcript as a user message, the card shows the reply, and the commentary of 5.5.5 goes out with the right delegation id;
- a mini-app hand-off sends the commentary of 5.5.6;
- a failed request sends the commentary of 5.5.7;
- a phone-action hand-off shows its plan card in the voice card, and each end phase of the plan sends the commentary of 5.5.8;
- voice approval (5.5.10): a listed approval phrase inside the window runs the spoken plan revision; a rejection phrase stops it; a longer sentence becomes a hand-off and runs nothing; a phrase that overlaps playback, comes after the window, or meets a changed revision runs nothing; a delegation for an approval turn submits no chat message; without a passed echo check, only the tap runs the plan;
- a memory-fact hand-off ends the voice mode with «Продолжите на экране»;
- an app or a permission dialog that Alphy opens mutes the microphone and keeps the session, and the return unmutes it (5.8.1);
- interruption: microphone voice while playback has audio flushes the playback (5.4.5);
- each end of 5.8 closes the capture, the playback, and the socket, and restores the audio mode;
- mute and unmute send their events;
- assistant button mode (5.11): a press with an OpenAI account starts the session at once, and a press with another provider opens the sheet as today; no permission means no session; 10 s without speech after the press ends the voice mode and keeps the sheet; the first hand-off waits up to 2 s for a capture in progress; the first hand-off carries the screen context and screenshot that the user kept, and later hand-offs carry none; closing the sheet, `onHide`, `onLockscreenShown`, and `openApp` end the voice mode;
- the voice mode and `VoiceSession` never run together.

6.3 A live check (`run.py live-check`, a test class whose name starts with `Live`) runs one short session with the real key from `~/.env`: one Russian request with a hand-off, one mini-app commentary sequence with the second commentary delayed by 25 s, one typed turn with `session.thinking.append`, one interruption, one voice approval with a recorded «да» fixture, and one mute and unmute (ruling R37). It logs the order and the timeline values of transcript and delegation events. It tests the assumptions of 5.4.5, 5.4.6, 5.5.2, 5.5.6, 5.5.10, and 5.7.2, and it checks that GPT-Live does not claim an action before the commentary.

6.4 A device echo check on a real phone, with the loudspeaker and without a headset. Run it as the first task of the plan, with a minimal capture and playback path, because its result decides the audio path (5.4.5). Alphy speaks a long answer while the room is quiet. Expected: GPT-Live does not hear itself (no input transcript of Alphy's words), and the local detector does not flush the playback. Then the user speaks over Alphy. Expected: the playback stops within 300 ms. Then Alphy reads a proposal that contains the word «да». Expected: no approval and no input transcript of that word. Voice approval (5.5.10) stays off until this check passes. Record the revision, the device, the steps, and the results.

6.5 Run the tests on the owned emulator (`AlphyCoursePlayer`, port 5590) as the project voice tests do. The echo check needs a real phone.

## 7. Assumptions and risks

| Item | Basis | Test |
| --- | --- | --- |
| Platform echo cancellation is sufficient on the loudspeaker | Standard Android communication audio path. Not tested in the spike. | 6.4 |
| GPT-Live speaks more than one commentary per delegation | The delegation guide permits several commentary updates for one delegation (builder check, read through a summarizer). | 6.3 |
| `session.thinking.append` works without an open delegation | The WebSocket reference permits `delegation_id: null` (builder check, read through a summarizer). | 6.3 |
| The output stream gives no reply-start event, so 800 ms of silent stream audio after the last voiced chunk marks a reply end (R24, R27) | Spike: no turn event among the received events. Run 1 disproved the earlier 300 ms rule. Run 2: 8 replies and 8 reply ends, pauses inside a reply at most 500 ms. | 6.2, 6.3 |
| Input and output transcripts share one session timeline (`start_ms`, `end_ms`) | Both carry the same fields. The spike did not compare them. | 6.3 |
| Russian quality of voice `marin` is acceptable | Spike recordings. The user judges them. | user review |
| A key on the user's device is acceptable to OpenAI | Alphy already sends the user's key from the phone for chat. The Live guide says to keep keys on a server, for keys of an app developer. | none in tests |

Live check results (6.3): run 1 (`live-0ac2b42f003f`) and run 2 after rulings R24 to R30 (`live-2a3dd7bb5762`), both on 2026-11-18.

- More than one commentary for one delegation: held in both runs. GPT-Live spoke a commentary that came 25 s after its delegation.
- Thinking without a delegation: no error in both runs. GPT-Live spoke about the thinking text without a user turn, which 5.7.2 does not expect. In run 2 it said «Четыре.».
- One session timeline: both transcripts use one clock. The output transcript times lead the received output audio by 1.7 to 2.4 s (run 1).
- Interruption (5.4.5): in run 1, GPT-Live did not stop a commentary and opened no delegation for the request. In run 2, it stopped its reply after the first sentence and opened a delegation, and Alphy flushed its playback.
- Request text (5.5.2): complete at `session.delegation.created` for a request spoken in silence. For a request that interrupted a reply, the first rule dropped the words before the last `end_ms` of that reply. Run 2 got «пять минут» for «Поставь таймер на пять минут». Rulings R31 and R32 fixed this in the client. The fix is tested on the recorded run 2 timeline, not in a new paid run.
- The instruction for «Нужно подтверждение на экране.» (ruling R25): in run 1, GPT-Live asked for «да» or «нет». In run 2, it said «Пожалуйста, подтвердите действие на экране.», with no «да», «нет», or tap.

## 8. Left to the builder

- The final Russian instructions text (5.3.3), checked by the live check.
- The exact character art, animation, and layout of the voice screen inside 5.2.
- The voice detector thresholds inside 5.4.5.
- The class structure. A new session class next to `VoiceSession` in `voice/` and a voice-screen composable in `ui/` fit the current structure.
- The fake WebSocket server for tests.

## 9. Documentation

Update `docs/voice-integration.md` with a «Голосовой режим» section: availability, data sent to OpenAI, cost, the end rules, and the test commands. Update `README.md` if it lists voice features.
