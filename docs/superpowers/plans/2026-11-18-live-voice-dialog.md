# Live voice dialog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A full-duplex voice mode with OpenAI GPT-Live in the full chat and in the assistant button sheet, where GPT-Live talks and Alphy's chat agent does every task.

**Architecture:** A GPT-Live WebSocket session (`LiveVoiceSession`) owns audio, timers, and protocol events. A bridge in the UI layer (`LiveVoiceBridge`) turns GPT-Live delegations into normal chat requests and turns message, plan, and approval changes back into GPT-Live commentary. A voice layer over the chat shows the latest hand-off reply with the existing chat renderers.

**Tech Stack:** Kotlin, Jetpack Compose, OkHttp 5.5.1 WebSocket (existing dependency), `AudioRecord`/`AudioTrack` with platform echo cancellation, AndroidX instrumented tests through `tools/compose_acceptance/run.py`.

**Spec:** `docs/superpowers/specs/2026-11-18-live-voice-dialog-design.md` (approved 2026-11-18). Base revision: `origin/main` `ce37f1c`.

## Global Constraints

- Work in a linked worktree from `origin/main`: `git worktree add .claude/worktrees/live-voice -b live-voice origin/main`. The main checkout is older and its Slopo configuration is stale.
- Before each commit, follow `~/.codex/skills/slopo-review/SKILL.md`, then run `architecture-scan check <worktree> --policy pilot/alphy/policy.json --baseline pilot/alphy/baseline.json` from `~/Documents/architecture-scan-rs`. Exit 0 passes. Exit 1: fix or stop. Exit 2: stop and report.
- No push without the user's explicit yes.
- Device tests: `python3 tools/compose_acceptance/run.py suite --serial emulator-5590 --label <label> <targets>` on the owned AVD `AlphyCoursePlayer`. Targets are class or method names, not packages. A skipped test prints "failed": read `run.json`.
- A test class whose name starts with `Live` is a paid live check for `run.py live-check`. No other test class starts with `Live`.
- Never install a debug build on the user's OnePlus Open. A signature mismatch uninstalls the release app and deletes the courses.
- Model `gpt-live-1`, voice `marin`, `audio/pcm` at 16,000 Hz in both directions, URL path `/v1/live/sessions`, header `Authorization: Bearer <account key>` (spec 5.3).
- The voice mode exists only when the account provider is `AiProvider.OPENAI` (spec 5.1.1).
- The key never appears in a log, an error text, or test evidence (spec 5.3.6).
- History preload: at most 128 messages and 8,192 tokens, tokens estimated as characters / 3 (spec 5.3.4).
- Commentary and thinking text: at most 1,200 characters, cut at a sentence end (spec 5.5.5, 5.7.2).
- Timers: 10 s for `session.started`; 60 s without user speech while no hand-off runs; 10 s without speech after an assistant button press; 500 ms request-text wait; 30 s approval window; first assistant hand-off waits up to 2 s for the screen capture (spec 5.5.2, 5.5.10, 5.8, 5.11.2).
- New UI strings are Russian and go to `app/src/main/res/values/live_voice_strings.xml`. Locale folders translate only mini-app strings, so they get no copy.
- Behavior tests come first and fail before the production code (spec 6.1).

## Review Focus

1. Rotation or another configuration change while the voice mode is open: the session must continue, because `onStop` with `isChangingConfigurations` is not leaving Alphy. Test: Task 8, `rotationKeepsTheSession`.
2. The network drops while a hand-off runs: the voice mode ends with «Связь с OpenAI прервалась», and the chat request still completes (spec 5.5.9). Test: Task 6, `connectionLossKeepsTheRunningRequest`.
3. Two requests in quick succession: both are queued, the card shows the newer one, and each answer goes to its own delegation id. Test: Task 6, `twoDelegationsAnswerTheirOwnIds`.
4. The user speaks before `session.started`: no audio is sent early, and the status shows the connection. Test: Task 5, `noAudioBeforeStarted`.
5. A long Markdown reply with code and links: commentary has no Markdown marks and is cut at a sentence end at or below 1,200 characters. Test: Task 2, `spokenCutsLongMarkdownAtSentenceEnd`.

---

### Task 1: Live audio path and device echo check

Spec 5.4.1 to 5.4.5 and 6.4. This task runs first, because its result decides the audio path and turns voice approval on per device model.

**Files:**
- Create: `app/src/main/java/com/alpha/assistant/voice/LiveAudio.kt`
- Create: `app/src/main/java/com/alpha/assistant/voice/BargeInDetector.kt`
- Create: `app/src/androidTest/java/com/alpha/assistant/voice/BargeInDetectorTest.kt`
- Create: `app/src/androidTest/java/com/alpha/assistant/voice/EchoCheckDeviceTest.kt`
- Create: `app/src/androidTest/java/com/alpha/assistant/voice/WavFixture.kt`: `internal fun wavSamples(bytes: ByteArray): ShortArray` and `internal fun wavBytes(samples: ShortArray, rate: Int = LIVE_RATE): ByteArray`. Move the parser of `LiveVoiceInputTest.fixtureSamples()` into it, and make that test use it.
- Create: `app/src/androidTest/assets/voice/live-reply.wav` and `live-proposal.wav`. Make each with macOS `say -v Milena -o <name>.aiff "<text>"`, then `afconvert -f WAVE -d LEI16@16000 -c 1 <name>.aiff <name>.wav`, as for the spike input. Texts:
  - `live-reply`: the Solar System answer of the barge-in run in the 2026-11-18 spike (at least 10 s of speech).
  - `live-proposal`: «Alphy предлагает: поставить таймер на одну минуту. Скажите «да», чтобы выполнить, или «нет», чтобы отменить.»

This task covers the local half of spec 6.4: the detector and the echo canceller. The GPT-Live half (GPT-Live does not transcribe the echo) runs in Task 4, as soon as the protocol and the transport exist. Deviation from spec 6.4: that half cannot run before Tasks 2 and 4. Voice approval stays off until both halves pass.

**Interfaces:**
- Produces:
  - `internal const val LIVE_RATE = 16000` and `internal const val LIVE_BLOCK = 320` (20 ms).
  - `internal interface LiveAudio { fun open(onCapture: (ShortArray) -> Unit, onFocusLost: () -> Unit): Boolean; fun setCaptureMuted(muted: Boolean); fun play(samples: ShortArray); fun flushPlayback(); val playbackActive: Boolean; val level: Float; fun close() }`. `open` returns false when audio focus is denied.
  - `internal class AndroidLiveAudio(context: Context) : LiveAudio`.
  - `internal class BargeInDetector(minSpeechMs: Int = 200) { fun accept(block: ShortArray, playbackActive: Boolean): Boolean; val userSpeaking: Boolean }`. `accept` returns true at the block where the user's voice has lasted 200 ms.
  - `internal val VOICE_APPROVAL_MODELS: Set<String>`: the `Build.MODEL` values that passed the echo check. It starts empty.

- [ ] **Step 1: Write the failing detector tests** in `BargeInDetectorTest`:
  - `silenceNeverFires`: 10 s of noise at RMS 0.002 gives no `true`.
  - `speechFiresAfter200Ms`: noise, then speech blocks at RMS 0.1. `accept` returns true at the 10th speech block (200 ms) and not before.
  - `residualEchoDoesNotFire`: with `playbackActive = true`, blocks at a steady RMS of 0.02 (residual echo) for 5 s give no `true`.
  - `speechOverEchoFires`: with `playbackActive = true`, after 1 s of echo at RMS 0.02, speech at RMS 0.15 fires within 200 ms.
- [ ] **Step 2: Run** `run.py suite --serial emulator-5590 --label live-audio-red --expect failed com.alpha.assistant.voice.BargeInDetectorTest`. Expected: build failure or failed tests, because the class does not exist.
- [ ] **Step 3: Implement `BargeInDetector`.** Use the threshold rule of `SpeechDetector` (`voice/PcmCapture.kt`): speech is more than 3 times the quietest block of the last 10 s. While playback is active, the threshold is also at least 2 times the mean level of the playback blocks that the detector did not count as speech.
- [ ] **Step 4: Implement `AndroidLiveAudio`.** Follow spec 5.4.1 to 5.4.4 exactly:
  - capture: `AudioSource.VOICE_COMMUNICATION`, `AcousticEchoCanceler` and `NoiseSuppressor` when `isAvailable()`;
  - playback: streaming `AudioTrack` with `USAGE_VOICE_COMMUNICATION` and `CONTENT_TYPE_SPEECH`;
  - routing: `MODE_IN_COMMUNICATION`, and the built-in speaker through `setCommunicationDevice` when no wired or Bluetooth headset is present;
  - one `AUDIOFOCUS_GAIN_TRANSIENT` request.
  
  `close` restores the mode, clears the communication device, and abandons focus.
- [ ] **Step 5: Run the detector tests.** Expected: PASS.
- [ ] **Step 6: Write `EchoCheckDeviceTest`.** Each method plays a fixture through `AndroidLiveAudio`, captures at the same time, and writes the capture as WAV to the test output directory. The class is skipped on an emulator (`ro.kernel.qemu == 1`), because an emulator has no acoustic echo.
  - `loudspeakerEchoStaysBelowTheDetector`: `live-reply.wav` in a quiet room. `BargeInDetector.accept(…, playbackActive = true)` never returns true. The test reports the ratio of capture RMS to playback RMS.
  - `proposalEchoStaysBelowTheDetector`: the same assertion with `live-proposal.wav`.
  - `speechOverPlaybackFlushes`: `live-reply.wav` plays for 12 s. The logcat line "speak now" asks the operator to speak from second 4. Expected: one `flushPlayback()` after second 4. The test logs the flush time. The operator measures the speech onset in the capture WAV and records the delay, which must be at most 300 ms.
- [ ] **Step 7: Ask the user which real phone to use, and get an explicit yes before any install.** The OnePlus Open is excluded (Global Constraints). Then run:

```bash
ANDROID_SERIAL=<serial> ./gradlew :app:installDebug :app:installDebugAndroidTest
```

```bash
adb -s <serial> shell am instrument -w -e class com.alpha.assistant.voice.EchoCheckDeviceTest com.alpha.assistant.test/androidx.test.runner.AndroidJUnitRunner
```

  Expected: `OK (3 tests)` and a speech-to-flush delay of at most 300 ms. Pull the two quiet-room captures with `adb pull` into `app/src/androidTest/assets/voice/echo/` as `<Build.MODEL>-reply.wav` and `<Build.MODEL>-proposal.wav`. They become the fixtures of Task 4, so the GPT-Live half is repeatable on the emulator. Record the revision, the device model, the results, and the delay in `evidence/live-voice/echo-check.md`.
- [ ] **Step 8: On FAIL, stop and report to the user.** Spec 5.4.5 makes the fallback (headset-only use or WebRTC audio processing) their decision. On PASS, leave `VOICE_APPROVAL_MODELS` empty until Task 4, step 7.
- [ ] **Step 9: Commit** `LiveAudio.kt`, `BargeInDetector.kt`, `WavFixture.kt`, the changed `LiveVoiceInputTest.kt`, both tests, the fixtures, and the evidence files. Message: "Add the live voice audio path and the loudspeaker echo check".

### Task 2: GPT-Live protocol

Spec 5.3, 5.5.5, and 5.7.2. Pure functions without network.

**Files:**
- Create: `app/src/main/java/com/alpha/assistant/voice/LiveProtocol.kt`
- Modify: `app/src/main/java/com/alpha/assistant/cloud/ProviderProfile.kt` (pins next to `OpenAiProfile`)
- Create: `app/src/androidTest/java/com/alpha/assistant/voice/VoiceModeProtocolTest.kt`

**Interfaces:**
- Produces:
  - `internal const val OPENAI_LIVE_MODEL = "gpt-live-1"` and `internal const val OPENAI_LIVE_VOICE = "marin"` in `ProviderProfile.kt`.
  - `internal sealed interface LiveEvent` with these members:
    - `Started(sessionId: String)`;
    - `OutputAudio(samples: ShortArray)`;
    - `InputTranscript(delta: String, startMs: Long, endMs: Long)` and `OutputTranscript(delta: String, startMs: Long, endMs: Long)`;
    - `Delegation(id: String, offsetMs: Long)`;
    - `Appended(clientEventId: String)`;
    - `Closed(reason: String?)`;
    - `Failure(message: String, clientEventId: String?)`;
    - `Other`.
  - `internal object LiveProtocol` with these functions:
    - `start(history: List<Message>): String` and `history(messages: List<Message>): JSONArray`;
    - `audio(samples: ShortArray): String`;
    - `commentary(eventId: String, delegationId: String, text: String): String` and `thinking(eventId: String, text: String): String`;
    - `mute(eventId: String): String`, `unmute(eventId: String): String`, and `close(): String`;
    - `parse(text: String): LiveEvent`;
    - `spoken(markdown: String, max: Int = 1200): String`;
    - `const val INSTRUCTIONS: String`.

- [ ] **Step 1: Write the failing tests** in `VoiceModeProtocolTest`:
  - `startHasTheSpecFields`: `JSONObject(start(emptyList()))` has `type == "session.start"`, `session.model == "gpt-live-1"`, `session.audio.format == {"type":"audio/pcm","rate":16000}`, `session.audio.output.voice == "marin"`, `session.instructions == INSTRUCTIONS`, and no `delegation` key.
  - `historyKeepsNewestCompleteTextWithinLimits`: from 200 alternating `complete` messages of 300 characters, the result has at most 128 items, at most 24,576 characters in total, and the newest message last. Statuses `error`, `queued`, and `streaming` are excluded. Roles map `user` to `input_text` and `assistant` to `output_text`. Attachments are not sent.
  - `audioIsBase64LittleEndian`: `audio(shortArrayOf(1, -2))` has `type == "session.input_audio.append"` and `audio == "AQD+/w=="`.
  - `parseReadsTheSpikeEvents`: the JSON lines recorded in the 2026-11-18 spike (`session.started`, `session.output_audio.delta`, `session.input_transcript.delta`, `session.output_transcript.delta`, `session.delegation.created` with `offset_ms` and `delegation.id`, `session.commentary.appended`, `session.closed` with `reason`, `error`) parse to the matching `LiveEvent`. An unknown type gives `Other`.
  - `spokenCutsLongMarkdownAtSentenceEnd` (Review Focus 5): a 3,000-character Markdown reply with a code block, a link, and a source chip gives at most 1,200 characters. The result ends with `.`, `!`, or `?` and contains no `` ` ``, `*`, `#`, `[`, or `](`.
- [ ] **Step 2: Run** `run.py suite … --expect failed com.alpha.assistant.voice.VoiceModeProtocolTest`. Expected: failure.
- [ ] **Step 3: Implement `LiveProtocol`.** `spoken` calls `speakableText` (`voice/SpeechOutput.kt`), then cuts at the last sentence end at or below `max`. The history budget is 24,576 characters (8,192 tokens × 3), with a margin left to the builder. `INSTRUCTIONS` holds this Russian text:

```text
Ты голосовой режим помощника Alphy. Говори по-русски, коротко и естественно. Пользователь говорит по-русски или по-английски.
Ты сам не выполняешь действия и не знаешь свежих фактов. Любую просьбу о действии на телефоне, поиске, свежих фактах, погоде, мини-приложении, памяти, файлах или о том, что на экране, сразу передавай Alphy через делегирование.
Не задавай уточняющих вопросов о задаче сам. Alphy спросит, если нужно, а ты озвучишь его вопрос.
Пока Alphy работает, скажи одну короткую фразу ожидания.
Никогда не говори, что действие выполнено, пока Alphy этого не подтвердил.
Когда Alphy предлагает действие, прочитай предложение и попроси сказать «да» или «нет». Никогда не говори, что действие одобрено или запущено. Это подтверждает Alphy.
Ответ Alphy перескажи кратко. Полный ответ пользователь видит на экране.
Ты не видишь экран пользователя. Любой вопрос об экране передавай Alphy.
Если Alphy сообщает, что готовит мини-приложение, скажи об этом один раз и не придумывай его содержание.
```

- [ ] **Step 4: Run the tests.** Expected: PASS.
- [ ] **Step 5: Commit.** Message: "Add the GPT-Live protocol for the voice mode".

### Task 3: Turns, request text, and voice approval rules

Spec 5.5.2 and 5.5.10. Pure logic.

**Files:**
- Create: `app/src/main/java/com/alpha/assistant/voice/LiveTurns.kt`
- Create: `app/src/androidTest/java/com/alpha/assistant/voice/VoiceModeTurnsTest.kt`

**Interfaces:**
- Produces:
  - `internal data class UserTurn(val text: String, val startMs: Long, val endMs: Long, val overlappedPlayback: Boolean)`.
  - `internal class LiveTranscript` with these members:
    - `fun input(delta: String, startMs: Long, endMs: Long, playbackActive: Boolean)`;
    - `fun output(delta: String, startMs: Long, endMs: Long): UserTurn?`, which ends and returns an open user turn;
    - `fun endTurn(): UserTurn?`;
    - `fun request(offsetMs: Long): String`;
    - `val outputEndMs: Long`.
  - `internal enum class SpokenAnswer { APPROVE, REJECT, OTHER }` and `internal fun spokenAnswer(turn: String): SpokenAnswer`.
  - `internal class ApprovalWindow(val planId: String, val revision: Int, val opensAtMs: Long, val lengthMs: Long = 30_000) { fun decide(turn: UserTurn): SpokenAnswer? }`. It returns null when the turn is outside the window or overlapped playback. Only the first turn that starts inside the window counts (spec 5.5.10, rule 4). Every later call returns null.

- [ ] **Step 1: Write the failing tests:**
  - `requestIsTheLastUserTurnOnly`: the input «Привет» (0–800 ms), the output «Привет!» (1,000–1,600 ms), and the input «Поставь таймер» (2,000–3,000 ms) give `request(3,200) == "Поставь таймер"`.
  - `requestStartsAfterThePreviousHandOff`: after `request(3,200)`, the input «на пять минут» (3,400–4,200 ms) gives `request(4,400) == "на пять минут"`.
  - `requestIsBlankWithoutNewInput`: `request(5,000)` right after `request(4,400)` gives `""`.
  - `closedPhraseListOnly`: «Да», «да, запускай.», «Go ahead», and «ПОДТВЕРЖДАЮ» give `APPROVE`. «Нет», «отмена», and «Cancel» give `REJECT`. «да, но на десять минут», «давай позже», «не знаю», and «» give `OTHER`.
  - `windowRejectsLateOverlappedOrEarlyTurns`: with `opensAtMs = 10,000`, the turn «да» at 9,000 gives null, at 41,000 gives null, and at 12,000 with `overlappedPlayback = true` gives null. At 12,000 without overlap it gives `APPROVE`.
  - `onlyTheFirstTurnInTheWindowCounts`: the turn «да, но позже» at 12,000 gives `OTHER`, then «да» at 14,000 gives null.
- [ ] **Step 2: Run** `run.py suite … --expect failed com.alpha.assistant.voice.VoiceModeTurnsTest`. Expected: failure.
- [ ] **Step 3: Implement.** Match phrases after lowercasing and removing punctuation and extra spaces. These are the exact lists of spec 5.5.10:
  - approve: `да`, `да запускай`, `запускай`, `выполняй`, `подтверждаю`, `давай`, `yes`, `go ahead`;
  - reject: `нет`, `не надо`, `отмена`, `отмени`, `стоп`, `no`, `cancel`.
- [ ] **Step 4: Run the tests.** Expected: PASS.
- [ ] **Step 5: Commit.** Message: "Add live voice turns and the voice approval rules".

### Task 4: WebSocket transport and the GPT-Live echo check

Spec 5.3.1 and the GPT-Live half of 6.4.

**Files:**
- Create: `app/src/main/java/com/alpha/assistant/voice/LiveTransport.kt`
- Modify: `app/build.gradle.kts`: add `androidTestImplementation("com.squareup.okhttp3:mockwebserver3:5.5.1")` (Implementation choice: a test-only loopback WebSocket server, the same version as `okhttp`).
- Create: `app/src/androidTest/java/com/alpha/assistant/voice/VoiceModeTransportTest.kt`
- Create: `app/src/androidTest/java/com/alpha/assistant/voice/LiveEchoTranscriptTest.kt`
- Modify: `app/src/main/java/com/alpha/assistant/voice/LiveAudio.kt` (`VOICE_APPROVAL_MODELS`, on PASS only)

**Interfaces:**
- Produces:
  - `internal interface LiveListener { fun onText(text: String); fun onFailure(status: Int?, message: String); fun onClosed() }`.
  - `internal interface LiveConnection { fun send(text: String): Boolean; fun close() }`.
  - `internal fun interface LiveTransport { fun connect(url: String, key: String, listener: LiveListener): LiveConnection }`.
  - `internal class OkHttpLiveTransport : LiveTransport`.
  - `internal fun liveUrl(endpoints: Endpoints): String`.

- [ ] **Step 1: Write the failing tests:**
  - `urlUsesWssInProductionAndWsOnLoopback`: `liveUrl(Endpoints.PRODUCTION) == "wss://api.openai.com/v1/live/sessions"`, and `liveUrl(Endpoints { "http://127.0.0.1:4000" }) == "ws://127.0.0.1:4000/v1/live/sessions"`.
  - `connectSendsBearerAndPassesMessagesBothWays`: a `MockWebServer` WebSocket upgrade records the path `/v1/live/sessions` and `Authorization: Bearer alpha-test-only`. A sent text reaches the server, and a server text reaches `onText`.
  - `refusedUpgradeReportsTheStatusWithoutTheKey`: a 401 upgrade gives `onFailure(401, …)`, and the message does not contain `alpha-test-only`.
- [ ] **Step 2: Run** `run.py suite … --expect failed com.alpha.assistant.voice.VoiceModeTransportTest`. Expected: failure.
- [ ] **Step 3: Implement.** `liveUrl` calls `endpoints.checked(AiProvider.OPENAI)`, maps `https` to `wss` and `http` to `ws`, and appends `/v1/live/sessions`. `OkHttpLiveTransport` uses one `OkHttpClient` with `pingInterval(20, SECONDS)` and no call timeout. Error messages go through `providerFailure` (`cloud/ProviderErrors.kt`) for HTTP statuses.
- [ ] **Step 4: Run the tests.** Expected: PASS.
- [ ] **Step 5: Commit.** Message: "Add the GPT-Live WebSocket transport".
- [ ] **Step 6: Write `LiveEchoTranscriptTest#echoIsNotTranscribed`** with `liveCheck(…)`. Skip when the account provider is not OpenAI. For each pair of captures in `voice/echo/` (Task 1, step 7), open one session with `OkHttpLiveTransport` and `LiveProtocol.start(emptyList())`. After `session.started`, send the capture in 20 ms blocks at real-time pace, then 3 s of silence, then `session.close`. Collect the input transcript deltas. Assertions:
  - the reply capture gives no run of 3 consecutive words of the `live-reply` text;
  - the proposal capture gives no word «да».
  
  The report holds each model, its input transcript, and the result. It never holds the key.
- [ ] **Step 7: Run** `python3 tools/compose_acceptance/run.py live-check --serial emulator-5590 --label live-echo --test com.alpha.assistant.voice.LiveEchoTranscriptTest#echoIsNotTranscribed` with the OpenAI account in `~/.env`. Expected: PASS. Add the result to `evidence/live-voice/echo-check.md`. On PASS of both halves, add the `Build.MODEL` value to `VOICE_APPROVAL_MODELS`. On FAIL, leave the list empty and report to the user before Task 5.
- [ ] **Step 8: Commit.** Message: "Check that GPT-Live does not hear the loudspeaker echo".

### Task 5: Live voice session

Spec 5.3.2, 5.4.5, 5.4.6, 5.8 (table and timers), and 5.8.1.

**Files:**
- Create: `app/src/main/java/com/alpha/assistant/voice/LiveVoiceSession.kt`
- Create: `app/src/main/res/values/live_voice_strings.xml`
- Create: `app/src/androidTest/java/com/alpha/assistant/voice/FakeLiveTransport.kt` and `FakeLiveAudio.kt`
- Create: `app/src/androidTest/java/com/alpha/assistant/voice/VoiceModeSessionTest.kt`

**Interfaces:**
- Consumes: `LiveAudio`, `BargeInDetector` (Task 1); `LiveProtocol`, `LiveEvent` (Task 2); `LiveTranscript`, `UserTurn` (Task 3); `LiveTransport`, `liveUrl` (Task 4).
- Produces:
  - `internal enum class LivePhase { IDLE, CONNECTING, LISTENING, SPEAKING, MUTED }`.
  - `internal data class LiveTiming(val startedMs: Long = 10_000, val silenceMs: Long = 60_000, val firstSpeechMs: Long = 10_000, val requestWaitMs: Long = 500, val turnGapMs: Long = 1_200, val replyGapMs: Long = 300)`. `turnGapMs` is an Implementation choice: a user turn also ends after 1.2 s without input transcript deltas. The other values come from the spec.
  - `internal interface LiveVoiceHost` with these members:
    - `fun history(): List<Message>`;
    - `fun delegation(id: String, request: String)`;
    - `fun turn(turn: UserTurn)`;
    - `fun handOffRunning(): Boolean`;
    - `fun appended(eventId: String)`, at `session.commentary.appended`;
    - `fun outputEnded(endMs: Long)`, each time output audio has been silent for `replyGapMs`, with `LiveTranscript.outputEndMs`;
    - `fun ended(status: String, settingsRequired: Boolean)`.
  - `internal class LiveVoiceSession(context: Context, scope: CoroutineScope, transport: LiveTransport, audio: () -> LiveAudio, account: suspend () -> AiAccount?, endpoints: Endpoints, timing: LiveTiming = LiveTiming())`. Its Compose state is `active`, `phase`, `level`, `status`, and `userSpeaking`.
  - Its methods are `start(host: LiveVoiceHost, firstSpeech: Boolean = false)`, `mute(muted: Boolean)`, `away(away: Boolean)` (spec 5.8.1), `commentary(delegationId: String, text: String): String` (it returns the client event id), `thinking(text: String)`, and `stop(status: String = "")`.
  - `FakeLiveTransport` records sent texts and exposes `emit(json: String)`, `fail(status: Int?)`, and `closeRemote()`. `FakeLiveAudio` takes capture blocks from the test and records played samples and flushes.

- [ ] **Step 1: Add the strings.** Put them in `live_voice_strings.xml` with this exact copy:

| Name | Text |
| --- | --- |
| `live_voice` | Голосовой режим |
| `live_voice_mute` | Выключить микрофон |
| `live_voice_unmute` | Включить микрофон |
| `live_voice_end` | Завершить голосовой режим |
| `live_voice_connecting` | Подключение к OpenAI |
| `live_voice_listening` | Слушаю |
| `live_voice_speaking` | Alphy говорит |
| `live_voice_working` | Alphy выполняет запрос |
| `live_voice_muted` | Микрофон выключен |
| `live_voice_no_speech` | Голосовой режим завершён: нет речи 60 с |
| `live_voice_no_first_speech` | Голосовой режим завершён: нет речи |
| `live_voice_focus_lost` | Голосовой режим прерван другим звуком |
| `live_voice_openai_only` | Голосовой режим доступен только с ключом OpenAI |
| `live_voice_expired` | Сеанс голосового режима истёк. Начните заново. |
| `live_voice_content` | OpenAI прервал голосовой режим по правилам содержания |
| `live_voice_connection` | Связь с OpenAI прервалась |
| `live_voice_on_screen` | Продолжите на экране |

  (`live_voice_connecting` and `live_voice_no_first_speech` are Implementation choices. The spec does not fix their copy.)
- [ ] **Step 2: Write the failing tests** in `VoiceModeSessionTest`. Use short `LiveTiming` values, `FakeLiveTransport`, and `FakeLiveAudio`:
  - `startSendsSessionStartWithHistory`: the first sent text is `LiveProtocol.start(host.history())` on `ws://…/v1/live/sessions` with the account key.
  - `noAudioBeforeStarted` (Review Focus 4): capture blocks before `session.started` are not sent, and `status == live_voice_connecting`. After `Started`, each block is sent as `session.input_audio.append`.
  - `outputAudioPlaysAndSetsSpeaking`: `OutputAudio` reaches `FakeLiveAudio.played`, and `phase == SPEAKING` while `playbackActive`.
  - `bargeInFlushesAndDropsUntilTheNextReply`: while playback is active, 200 ms of loud capture calls `flushPlayback()` once. Output audio is dropped while `userSpeaking`, then until 300 ms of output silence. The next output audio plays.
  - `muteSendsEventsAndStopsCapture`: `mute(true)` sends `session.input_audio.mute`, stops appends, and sets `phase == MUTED`. `mute(false)` reverses this.
  - `awayMutesAndFlushesAndReturnUnmutes`: `away(true)` mutes and flushes. `away(false)` unmutes.
  - `endsFollowTheSpecTable`: each case below ends with its status and closes audio and connection:
    - no `Started` in `startedMs` gives `live_voice_connection`;
    - `Closed("expired")` gives `live_voice_expired`;
    - `Closed("content")` gives `live_voice_content`;
    - `Closed("connection_lost")` or `fail(null)` gives `live_voice_connection`;
    - `fail(401)` gives the provider error text with `settingsRequired = true`;
    - an `error` event gives its provider error text;
    - focus loss gives `live_voice_focus_lost`;
    - a null account or a non-OpenAI account gives `live_voice_openai_only`.
  - `silenceEndsOnlyWithoutRunningHandOff`: no user speech for `silenceMs` ends with `live_voice_no_speech`. The same silence with `handOffRunning() == true` does not end.
  - `firstSpeechTimerOnlyForButtonStart`: `start(host, firstSpeech = true)` without speech for `firstSpeechMs` ends with `live_voice_no_first_speech`.
  - `delegationWaitsForTheRequestText`: a `Delegation` with an empty transcript waits `requestWaitMs` for an input delta, then calls `host.delegation(id, text)`.
  - `turnsEndAtOutputDelegationOrGap`: `host.turn` gets one `UserTurn` per user phrase, with `overlappedPlayback` set when playback was active at its start. For a delegation, `host.turn` of its turn comes before `host.delegation`.
  - `commentaryAckAndOutputEndReachTheHost`: `commentary(…)` returns an id. `Appended(id)` calls `host.appended(id)`. Output transcript deltas up to `end_ms` 5,000, then `replyGapMs` without output audio, call `host.outputEnded(5,000)` once.
  - `sessionRunsOverTheRealTransport`: with `OkHttpLiveTransport` and a `MockWebServer` WebSocket that answers `session.started`, one capture block reaches the server as `session.input_audio.append`, and `stop()` sends `session.close`. This test covers the listener thread of OkHttp.
  - `stopSendsCloseAndReleasesAudio`: `stop()` sends `session.close`, closes the connection, and calls `FakeLiveAudio.close()` once.
- [ ] **Step 3: Run** `run.py suite … --expect failed com.alpha.assistant.voice.VoiceModeSessionTest`. Expected: failure.
- [ ] **Step 4: Implement `LiveVoiceSession`.** It reads the account on `Dispatchers.IO`, and then it requires `AiProvider.OPENAI`. It sends audio on one worker. It reads all Compose state on the main thread. A generation token ignores late events after `stop`, as `VoiceSession` does.
- [ ] **Step 5: Run the tests.** Expected: PASS.
- [ ] **Step 6: Commit.** Message: "Add the GPT-Live voice session".

### Task 6: Hand-off bridge

Spec 5.5.1 to 5.5.9, 5.6, 5.7, and 5.1.6.

**Files:**
- Create: `app/src/main/java/com/alpha/assistant/ui/LiveVoiceBridge.kt`
- Modify: `app/src/main/java/com/alpha/assistant/ui/ShellViewModel.kt` (constructor, `submitDraft`, `sendAssistant`, memory, automation, and course points, mutual exclusion)
- Modify: `app/src/androidTest/java/com/alpha/assistant/ui/ShellRobot.kt` (`ShellFixture.install` gets `liveVoice: LiveVoiceFactory?`)
- Create: `app/src/androidTest/java/com/alpha/assistant/ui/ShellLiveVoiceWorkflowTest.kt`

**Interfaces:**
- Consumes: `LiveVoiceSession`, `LiveVoiceHost` (Task 5); `LiveProtocol.spoken` (Task 2).
- Produces in `ShellViewModel`:
  - `internal typealias LiveVoiceFactory = (Application, CoroutineScope) -> LiveVoiceSession`, and the constructor parameter `liveVoiceFactory: LiveVoiceFactory? = null`. The default builds `OkHttpLiveTransport`, `AndroidLiveAudio`, `keys.account()`, and `Endpoints.PRODUCTION`.
  - `internal val liveVoice: LiveVoiceSession`.
  - `internal fun startLiveVoice(assistantInvocation: String? = null, capture: AssistCapture? = null, firstSpeech: Boolean = false)`.
  - `internal fun endLiveVoice(status: String = "")`.
  - `internal val liveVoiceCardId: String?`, the assistant message id of the latest hand-off.
  - `internal val liveVoiceWorking: Boolean`, true while a tracked hand-off message is `queued`, `pending`, `streaming`, or `generating`. `LiveVoiceHost.handOffRunning()` returns it.
  - `internal fun sendLiveTyped()`, which sends the composer draft during the voice mode (spec 5.7).
  - `submitDraft` returns the assistant message id as `String?` instead of `Boolean`. Its three callers compare with `null`.
- Produces in `LiveVoiceBridge`: `internal class LiveVoiceBridge(model: ShellViewModel, session: LiveVoiceSession, assistantInvocation: String?, capture: AssistCapture?) : LiveVoiceHost`. It keeps `handOffs: Map<String, String>` (delegation id to message id). It also implements Task 7.
- Commentary copy. Alphy sends it to GPT-Live and does not show it:

| Case | Text |
| --- | --- |
| Blank request | Alphy не расслышал запрос. |
| Queue full | Alphy ещё занят предыдущими запросами. |
| Answer complete | Ответ Alphy: <spoken reply> |
| Mini-app started | Alphy готовит мини-приложение, оно появится на экране. |
| Mini-app complete | Мини-приложение готово. Ответ Alphy: <spoken reply> |
| Failure | Alphy не смог выполнить запрос: <message error> |
| Confirmation on screen | Нужно подтверждение на экране. |
| Typed turn (thinking) | Пользователь написал: <text>. Ответ Alphy на экране: <spoken reply> |

- [ ] **Step 1: Write the failing workflow tests** in `ShellLiveVoiceWorkflowTest`. Use `ShellFixture`, `FixtureProvider`, `FakeLiveTransport`, and `FakeLiveAudio`, and start the mode with `model.startLiveVoice()`:
  - `startPreloadsTheSelectedConversation`: with two saved `complete` messages in the selected chat, the `session.start` input holds both texts in order.
  - `delegationSubmitsTheTranscriptAndSpeaksTheAnswer`: emit input «Какая погода» and `Delegation("d1", …)`. A user message «Какая погода» is saved, `liveVoiceCardId` is its assistant message, and after «Fixture response» the transport got `commentary("…","d1","Ответ Alphy: Fixture response")`.
  - `blankDelegationAsksAgain`: a delegation without input sends «Alphy не расслышал запрос.» and saves nothing.
  - `miniAppSendsProgressThenReady`: with the fixture app generation, `generating` sends the mini-app started text, and `complete` with a `toolId` sends the mini-app complete text, both to the same delegation id.
  - `failureIsSpoken`: `provider.failure = CloudFailure("x","Сбой")` sends «Alphy не смог выполнить запрос: Сбой».
  - `twoDelegationsAnswerTheirOwnIds` (Review Focus 3): `d1` and `d2` in quick succession are both queued. The card shows the `d2` message. Each answer goes to its own id.
  - `connectionLossKeepsTheRunningRequest` (Review Focus 2): with `provider.gate` open, `closeRemote()` ends the mode with «Связь с OpenAI прервалась». After `gate.complete`, the reply is saved as `complete`.
  - `endDoesNotCancelTheRequest`: `endLiveVoice()` while a request runs leaves it running to `complete` (spec 5.5.9).
  - `smallTalkIsNotSaved`: output and input transcripts without a delegation save no message (spec 5.6.2).
  - `typedTurnGoesToTheAgentAndThinking`: during the mode, a draft «Привет» sent with `sendLiveTyped()` is saved and answered. After `complete`, `session.thinking.append` carries the thinking text.
  - `refinementAsksForTheScreen`: a hand-off that ends with `requiresRefinement` (the fixture of `AutomaticToolWorkflowTest`) sends «Нужно подтверждение на экране.» and not the answer text.
  - `providerChangeEndsTheMode`: after `model.accountProvider = AiProvider.DEEPSEEK` (the state that the account settings set), the mode ends with «Голосовой режим доступен только с ключом OpenAI».
  - `memoryFactEndsWithContinueOnScreen`: with `provider.memoryFact`, the mode ends with «Продолжите на экране». The same holds for `automationDraft` and a course topic.
  - `liveAndContinuousVoiceExclude`: `startLiveVoice()` stops an active `voice`. `startComposerVoice(...)` ends an active `liveVoice`.
- [ ] **Step 2: Run** `run.py suite … --expect failed com.alpha.assistant.ui.ShellLiveVoiceWorkflowTest`. Expected: failure.
- [ ] **Step 3: Change `submitDraft` to return `String?`, and update its callers.**
- [ ] **Step 4: Implement the bridge and the model entry points.**
  - The bridge observes `snapshotFlow { model.snapshot }` for the tracked message ids, and `snapshotFlow { model.accountProvider }` for the provider rule of spec 5.8.
  - At the memory editor point (`memoryPage.tryOpenPrefilledEditor`), the automation point (`chatAutomationSetup = …`), and the course point (`if (courseTopic != null)` in `startRequest`), add `endLiveVoice(text(R.string.live_voice_on_screen))` when the mode is active.
- [ ] **Step 5: Run the tests and the existing `ShellVoiceWorkflowTest` and `ShellWorkflowTest`.** Expected: PASS.
- [ ] **Step 6: Commit.** Message: "Hand GPT-Live requests to the chat agent and speak the results".

### Task 7: Phone plans and voice approval

Spec 5.5.8 (phone actions) and 5.5.10.

**Files:**
- Modify: `app/src/main/java/com/alpha/assistant/ui/LiveVoiceBridge.kt`
- Modify: `app/src/androidTest/java/com/alpha/assistant/ui/ShellLiveVoiceWorkflowTest.kt`

**Interfaces:**
- Consumes: `ApprovalWindow`, `spokenAnswer`, `UserTurn` (Task 3); `VOICE_APPROVAL_MODELS` (Tasks 1 and 4); `commentary(…): String`, `LiveVoiceHost.appended`, and `LiveVoiceHost.outputEnded` (Task 5); `AgentConversation.plan`, `execute(plan)`, `dismiss(plan)`, `AgentPhase`.
- Produces: `LiveVoiceBridge(…, voiceApproval: Boolean = Build.MODEL in VOICE_APPROVAL_MODELS)`.
- Commentary copy:

| Case | Text |
| --- | --- |
| Proposal | Alphy предлагает: <steps>. Скажите «да», чтобы выполнить, или «нет», чтобы отменить. |
| Approved | Запускаю. |
| Rejected or `STOPPED` | Действие отменено. |
| `COMPLETED` | Готово. <completion text of the plan card> |
| `FAILED` | Не получилось. <error text> |

  `<steps>` is the step titles of the plan card, joined with «, ».

- [ ] **Step 1: Write the failing tests:**
  - `reviewPlanIsReadAndYesRunsIt`: with `provider.devicePlanSteps` and `voiceApproval = true`, the plan reaches `REVIEW` and the proposal commentary goes out. After `Appended`, output end at 5,000 ms and the input «да» at 6,000 ms make `agent.execute` run the same id and revision.
  - `noDismissesThePlan`: «нет» calls `dismiss` and sends «Действие отменено.».
  - `otherTurnBecomesAHandOffAndRunsNothing`: «да, но на десять минут» runs nothing. The plan stays in `REVIEW`. A delegation for it is submitted as a request.
  - `overlapLateOrChangedRevisionRunsNothing`: «да» with `overlappedPlayback`, after 30 s, or after a revision change runs nothing.
  - `approvalDelegationSubmitsNoMessage`: a delegation for the «да» turn gets «Запускаю.» and saves no user message.
  - `withoutEchoCheckOnlyTapRuns`: with `voiceApproval = false`, the proposal is «Нужно подтверждение на экране.», and «да» runs nothing.
  - `endPhasesAreSpoken`: `COMPLETED`, `FAILED`, and `STOPPED` send their texts once.
  - `screenPhasesAskForTheScreen`: a plan in `NEEDS_PERMISSION` or `NEEDS_SELECTION` sends «Нужно подтверждение на экране.» once per revision, and opens no approval window.
- [ ] **Step 2: Run** with `--expect failed`. Expected: failure.
- [ ] **Step 3: Implement.** The bridge observes `snapshotFlow { model.agent.plan }` for the conversation of the hand-off. For each `REVIEW` revision, it keeps the event id of the proposal commentary. The first `outputEnded(endMs)` after `appended(thatId)` opens one `ApprovalWindow` at `endMs` (spec 5.5.10, rule 3).
- [ ] **Step 4: Run the tests.** Expected: PASS.
- [ ] **Step 5: Commit.** Message: "Read phone plans aloud and run them on a spoken yes".

### Task 8: Voice screen in the full chat

Spec 5.1.2, 5.1.5, 5.2, 5.7, 5.8.1, and 5.11.7 (lifecycle rule).

**Files:**
- Create: `app/src/main/java/com/alpha/assistant/ui/LiveVoiceScreen.kt`
- Modify: `app/src/main/java/com/alpha/assistant/ui/AlphaShell.kt`: extract the message item of `ChatPane` (lines 694–725 at `ce37f1c`) into `ChatMessageItem`, and show `LiveVoiceLayer` over the chat while `model.liveVoice.active`.
- Modify: `app/src/main/java/com/alpha/assistant/ui/Composer.kt`: the voice-mode button in place of the disabled send button (around line 171), and `model.liveVoice.status` in the status line next to `model.voice.status` (line 124).
- Modify, to call `model.expectAlphyScreen()` right before each launch: `agent/AgentConversation.kt:303` (phone-action apps, such as Clock for `clock.setTimer`), `ui/ReviewedContextUi.kt:83,84,126` (pickers of «+»), `capabilities/ContactActions.kt:78`, `agent/AgentPlanCard.kt:62`, `ui/WeatherLocationAccess.kt:22`, and the permission launcher of `ui/VoiceControls.kt`. Line numbers are at `ce37f1c`.
- Create: `app/src/androidTest/java/com/alpha/assistant/ui/VoiceModeScreenWorkflowTest.kt`

**Interfaces:**
- Consumes: `liveVoice`, `startLiveVoice`, `endLiveVoice`, `liveVoiceCardId`, and `sendLiveTyped` (Task 6).
- Produces:
  - `@Composable internal fun ChatMessageItem(model: ShellViewModel, chat: Conversation, message: Message, cardActions: MiniAppActions, openTool: (String) -> Unit, openAutomation: (String) -> Unit, showLivePreviews: Boolean, pairedToolId: String?, pairedDraftId: String?, share: (String) -> Unit, settings: () -> Unit, linkError: () -> Unit)`.
  - `@Composable internal fun LiveVoiceLayer(model: ShellViewModel, chat: Conversation?, compact: Boolean, openTool: (String) -> Unit, openAutomation: (String) -> Unit, settings: () -> Unit, onEnd: () -> Unit)`.
  - `@Composable internal fun rememberLiveVoiceStart(model: ShellViewModel, assistantInvocation: String? = null, capture: AssistCapture? = null, firstSpeech: Boolean = false): () -> Unit`. It handles the microphone permission as `rememberVoiceStart` does.
  - `@Composable internal fun LiveVoiceLifecycle(model: ShellViewModel)`. It provides the rule of 5.8.1:
    - `ON_PAUSE`: `away(true)`. A system permission dialog pauses Alphy without a stop.
    - `ON_RESUME`: `away(false)`, and the expectation flag clears.
    - `ON_STOP`: nothing when `isChangingConfigurations` or when the expectation flag is set. Otherwise the mode ends.
    - Screen off: the mode ends.
  - `internal fun ShellViewModel.expectAlphyScreen()`. It sets the expectation flag. A source link opened in a browser does not call it, so the browser ends the mode. The flag also clears 5 s after it is set (Implementation choice), because a launch can return without a pause: the permission is already granted, or `startActivity` throws.
  - The status line of the layer: `CONNECTING` «Подключение к OpenAI», `MUTED` «Микрофон выключен», `SPEAKING` «Alphy говорит», `liveVoiceWorking` «Alphy выполняет запрос», otherwise «Слушаю».

- [ ] **Step 1: Write the failing tests:**
  - `buttonOnlyForOpenAiWithEmptyField`: with an OpenAI key and an empty field, the node `live_voice` exists. With text, the send button exists. With a DeepSeek key, no `live_voice` node exists.
  - `layerShowsCardStatusAndControls`: while `provider.gate` holds a hand-off, the status is «Alphy выполняет запрос». After the reply, the layer shows «Fixture response» from `ChatMessageItem`, the status «Слушаю», and the controls `live_voice_mute` and `live_voice_end`. `live_voice_mute` changes the status to «Микрофон выключен».
  - `endStatusShowsInTheComposer`: after `closeRemote()`, the layer closes and the composer status line shows «Связь с OpenAI прервалась».
  - `phonePlanCardShowsInTheLayer`: with `provider.devicePlanSteps`, the layer shows `AgentPlanCard` for the hand-off plan.
  - `endClosesTheLayer`: `live_voice_end` closes the layer and shows the chat with the saved hand-off messages.
  - `rotationKeepsTheSession` (Review Focus 1): `scenario.recreate()` keeps `liveVoice.active` and the open connection.
  - `homeEndsButAlphyScreenOnlyMutes`: pressing Home ends the mode. A phone plan step that opens Clock (`clock.setTimer`) mutes the microphone and keeps the session, and the return unmutes it. A permission dialog does the same.
  - `staleExpectationDoesNotKeepTheSession`: `expectAlphyScreen()` without a launch, then Home after 5 s, ends the mode.
  - `screenStaysOn`: `LocalView.current.keepScreenOn` is true while the layer is shown.
- [ ] **Step 2: Run** with `--expect failed`. Expected: failure.
- [ ] **Step 3: Implement.**
  - Character: `R.drawable.alphy_welcome` with a scale and glow that follow `liveVoice.level`. No animation with reduced motion.
  - Layout: the card area scrolls, and the composer row has «+», the field, mic mute, and ✕ (spec 5.2).
  - Implementation choice: one character image for both poses, with the phase shown by the status line and the motion. Separate pose art can replace it later without other changes.
- [ ] **Step 4: Run the tests, `ShellWorkflowTest`, and `ChatPresentationWorkflowTest`.** Expected: PASS.
- [ ] **Step 5: Commit.** Message: "Add the voice mode screen to the chat".

### Task 9: Assistant button mode

Spec 5.11.

**Files:**
- Modify: `app/src/main/java/com/alpha/assistant/surfaces/AssistantActivity.kt` (`CompactAssistant`)
- Modify: `app/src/main/java/com/alpha/assistant/ui/LiveVoiceBridge.kt` (`submitLiveRequest` when `assistantInvocation != null`)
- Modify: `app/src/main/java/com/alpha/assistant/ui/ShellViewModel.kt`: split `sendAssistant` so that a voice hand-off can use its screen context and screenshot attachment with its own text.
- Create: `app/src/androidTest/java/com/alpha/assistant/surfaces/AssistantLiveVoiceWorkflowTest.kt`

**Interfaces:**
- Consumes: `startLiveVoice(assistantInvocation, capture, firstSpeech = true)`, `LiveVoiceLayer(compact = true)`, and `LiveVoiceLifecycle` (Tasks 6 and 8).
- Produces: `internal suspend fun ShellViewModel.submitLiveRequest(text: String, assistantInvocation: String?, capture: AssistCapture?): String?`. The first call of an invocation attaches the kept screen context and screenshot as `sendAssistant` does, and then discards the capture. Later calls attach nothing. The bridge of Task 6 calls it instead of `submitDraft` when `assistantInvocation != null`.

- [ ] **Step 1: Write the failing tests** (pattern of `AssistantSheetWorkflowTest`):
  - `pressWithOpenAiStartsAtOnce`: `launch(capture("Screen"))` with an OpenAI key gives `liveVoice.active` and a `session.start` without a tap.
  - `pressWithOtherProviderOpensTheSheet`: with a DeepSeek key, the normal composer shows and no session starts.
  - `firstHandOffCarriesTheKeptScreen`: the first delegation saves a user message with the `screenContext` and the screenshot attachment. The second has neither. A screen removed before the delegation is not attached.
  - `firstHandOffWaitsForALateCapture`: `AssistantCapture.updateScreen` 1 s after the delegation is attached. An update at 3 s is not.
  - `silenceAfterPressKeepsTheSheet`: no speech for `firstSpeechMs` ends the mode with «Голосовой режим завершён: нет речи», and the sheet composer shows.
  - `sheetEndsTheMode`: the sheet close button, `onHide`, `onLockscreenShown`, and `openApp` end the mode. ✕ of the layer returns to the sheet.
  - `noPermissionNoSession`: a denied microphone permission shows `MicrophonePermissionGuidance` and starts no session.
- [ ] **Step 2: Run** with `--expect failed`. Expected: failure.
- [ ] **Step 3: Implement.** With an OpenAI account, `CompactAssistant` replaces `ChatPane` with `LiveVoiceLayer(compact = true)` while the mode is active, and keeps the context rows above the composer. The start happens on the first `ON_RESUME` with `assistantUnavailable() == false`.
- [ ] **Step 4: Run the tests and the existing `AssistantSheetWorkflowTest` and `AssistantContextWorkflowTest`.** Expected: PASS.
- [ ] **Step 5: Commit.** Message: "Start the voice mode from the assistant button".

### Task 10: Live check and documentation

Spec 6.3 and section 9.

**Files:**
- Create: `app/src/androidTest/java/com/alpha/assistant/voice/LiveVoiceDialogTest.kt`
- Create: `app/src/androidTest/assets/voice/da.wav`: «да», made with `say -v Milena` and `afconvert` as in Task 1
- Modify: `docs/voice-integration.md` (new section «Голосовой режим»), `README.md` if it lists voice features

**Interfaces:**
- Consumes: `LiveVoiceSession` with `OkHttpLiveTransport` and a fixture `LiveAudio` that plays WAV files as capture; `liveCheck` (`tools/OneUseCredential.kt`).

- [ ] **Step 1: Write `LiveVoiceDialogTest#russianDialogWithHandOffs`** with `liveCheck(…)`. Skip when the account provider is not OpenAI. One session:
  - Send `russian.wav` («Поставь таймер на пять минут»). Expected: a delegation, and no output transcript before the commentary contains «поставил», «поставлен», «запустил», «запускаю», or «готово» (the first anti-goal). The test answers it with a commentary delayed by 25 s and expects output transcript text after it.
  - Send a second commentary for the same delegation. Expected: it is spoken.
  - Send `session.thinking.append` without a delegation. Expected: no `error`.
  - While output audio plays, send `russian.wav` again. Expected: the output transcript stops before the reply ends.
  - After a proposal commentary, send `da.wav`. Expected: an input transcript «да» with timeline values after the proposal output.
  
  Log every event type with its `start_ms`/`end_ms` and the order of input transcripts and delegations to the evidence output. The log must not contain the key.
- [ ] **Step 2: Run** `python3 tools/compose_acceptance/run.py live-check --serial emulator-5590 --label live-voice --test com.alpha.assistant.voice.LiveVoiceDialogTest#russianDialogWithHandOffs` with an OpenAI account in `~/.env`. Expected: PASS. Record whether each assumption of spec section 7 held.
- [ ] **Step 3: Update the documentation.** Cover availability, the data sent to OpenAI, the cost of $0.05 per minute, the end rules, voice approval and its device list, and the test commands.
- [ ] **Step 4: Run the full voice suite:** `run.py suite --serial emulator-5590 --label live-voice-all com.alpha.assistant.voice.BargeInDetectorTest com.alpha.assistant.voice.VoiceModeProtocolTest com.alpha.assistant.voice.VoiceModeTurnsTest com.alpha.assistant.voice.VoiceModeTransportTest com.alpha.assistant.voice.VoiceModeSessionTest com.alpha.assistant.ui.ShellLiveVoiceWorkflowTest com.alpha.assistant.ui.VoiceModeScreenWorkflowTest com.alpha.assistant.surfaces.AssistantLiveVoiceWorkflowTest com.alpha.assistant.ui.ShellVoiceWorkflowTest`. Expected: PASS, with no unexpected skips in `run.json`.
- [ ] **Step 5: Confirm the whole mode on the Task 1 phone.** This end-to-end run repeats the echo check with the real app. Ask the user for a yes again before the install. The user enters the OpenAI key in the app on that phone. You must not enter it. Use the loudspeaker and no headset:
  1. Ask «Расскажи подробно о Солнечной системе» and stay quiet during the answer.
  2. Speak over the answer.
  3. Ask «Поставь таймер на одну минуту» and stay quiet while Alphy reads the proposal.
  
  Expected: no saved user message contains Alphy's words, the playback stops when you speak, and the timer plan stays in `REVIEW` until you say «да». Record the revision, the model, the steps, and the results in `evidence/live-voice/echo-check.md`. On FAIL, remove the model from `VOICE_APPROVAL_MODELS` and report to the user.
- [ ] **Step 6: Commit.** Message: "Add the live voice check and document the voice mode".
