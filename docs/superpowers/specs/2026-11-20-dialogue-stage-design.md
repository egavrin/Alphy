# Dialogue stage: design

Date: 2026-11-20. Status: for the owner's approval.

This design changes the screen and the decision flow of the full duplex voice mode. It builds on two earlier designs and replaces parts of them:

- `2026-11-18-live-voice-dialog-design.md`, section 5.2 (the voice screen) and section 5.5 (the hand-off). This design replaces the layout of 5.2 and adds to 5.5.
- `2026-11-18-app-file-parts-design.md`, section 12 (dialogue mode). Its decisions stay: a screen of a dialogue is one app file, a model and a game are lines of data, a toy that moves by itself goes outside.

## Intent

The owner wants a dialogue mode that works as an interactive teacher. A person talks with Alphy, and Alphy explains with a picture, a model, or an experiment on the screen. The person asks a follow-up question, and the picture changes. The conversation goes on until the person stops it.

The owner's words of 2026-11-20:

- "Это должен решать агент." The model decides inside the dialogue whether a question gets a visual, with its tools. "Эти вещи не нужно хардкодить там какими-нибудь регэкспами."
- "То, что не требует визуализации, визуализировать не надо, а то, что требует визуализации какого-то объяснения, должно визуализироваться."
- "Большая часть вообще экрана должна заниматься именно вот областью под интерактив. И внизу … полоска с вот этой голосовой визуализацией, с кнопками там остановить, например, и выйти из режима."
- "Субтитры сейчас не являются основным моментом."
- "Это будущая заготовка для интерактивного учителя." Examples: how the heart works, how a planet is built, chemistry, physics.
- "Это нужно просто по-живому погонять, посмотреть, где это нужно."

The owner also gave mock pictures of four screens at the phone size and one screen at the fold and the tablet size. The same four screens are plans in the repository: `kit/plans/lesson.plan`, `kit/plans/model3d.plan`, `kit/test/fixtures/dialogue-materials.plan`, `kit/test/fixtures/dialogue-game.plan`, and `kit/test/fixtures/dialogue-rover.plan`.

## Goals, non-goals, anti-goals

Goals:

1. The interactive content takes most of the screen in the dialogue mode, at the phone, the fold, and the tablet size.
2. A model decides for each spoken request whether a visual helps. No list of words and no pattern decides it.
3. A screen stays in view while the conversation goes on. A follow-up changes it, and a new topic replaces it.
4. The delay from the end of speech to the first useful words, and to the screen, is measured on one clock and reported to the owner.
5. The owner can judge the mode by use: live scenarios run early, before the look work is finished.

Non-goals of this design:

- A history of the spoken lines (the button "Реплики" of the mocks). The strip shows only the current line.
- The tabs "Чат" and "Диалог" and the voice selector in the header of the mocks. The entry to the mode stays as it is today.
- A photo from the internet with its source, and a generated sketch beside a model. Both are in the mocks. They come after the first version, because each needs a host capability that takes long or costs a paid call.
- Echo, the gap after an interruption, and the mute events of the voice mode. A different session owns them.
- Other providers. The mode exists only for OpenAI.

Anti-goals (a result that works by its letter and fails the owner):

- A screen for each question. A short fact gets a spoken answer and nothing else.
  Changed on 2026-11-21 (`2026-11-21-stage-templates-stream-design.md`, section 6): this holds for the result of the turn. A frame can show while the routing call runs, and it goes away when that call gives no screen.
- A fixed rule in code that picks the requests that get a screen.
- A conversation that stops or loses its screen because an answer came.
- A spoken answer that does not agree with the picture beside it.
  Changed on 2026-11-21 (the same section): this holds for a complete picture. A picture that draws while the voice speaks is normal (the owner's words: "то, что картинка будет генериться под голос. Нормально.").

## Facts that limit the design

Each fact has its source.

| Fact | Source |
| --- | --- |
| The voice model has one tool: the hand-off to Alphy (client delegation). Alphy answers a hand-off with text that the voice model speaks. | `docs/voice-integration.md`, line 97 |
| The voice model hands off a request to explain or to teach in 15 of 15 requests when its instructions say so, about 1.0 s after the end of speech. | Probe of 2026-11-20, `b2-report.md` of the task folder |
| After a hand-off the voice model never answers the request by itself. It says a short phrase at about 1.6 s and waits. Three wordings, 0 of 15. | Same probe |
| The voice model speaks the text of Alphy about 1.6 s after Alphy sends it. | Same probe, form (b) |
| The routing call of the app takes 3.5 to 5.1 s on OpenAI. | Paired run of 2026-11-20 |
| A dialogue plan takes a median of 9 to 11 s for the whole call. Its first text comes after 3.3 to 4.4 s. | Model check, results document |
| A change of a stored screen by lines takes a median of 3.9 s. | Change check, results document |
| The voice endpoint gives the text of the speech of the voice model, with times that lead the audio. | `docs/voice-integration.md`, lines 226 to 232 |
| Today the voice screen shows one chat card of the latest hand-off in a column of at most 620 dp, then the character, a status text, and the composer. A later answer replaces the card. | `ui/LiveVoiceScreen.kt`, `LiveVoiceLayer` |
| Today a mini-app is a column of at most 640 dp at each screen size. | `tools/ToolUi.kt`; gate run of 2026-11-20 |
| The kit has the parts of the four mock screens: drawing, slider, timed change, lesson, question, card, model3d, deck, and a found picture. | `kit/README.md`; the fixtures named above |
| The emulators of this work cannot run the 3D scene tests. | Full suite of 2026-11-20 |

## Approaches

### Who decides that a request gets a screen

**A. The voice model decides with its hand-off (chosen, the author's call).** The instructions of the voice model tell it to hand a request off when a picture, a diagram, or a model that the person can change makes the answer clearer, and to answer by itself otherwise. Alphy then returns the words to speak and builds the screen. Cost: for a request with a screen the spoken explanation starts after the routing call of the app, an estimate of 6 to 8 s after the end of speech, with a short phrase at about 1.6 s. Gain: the words and the picture come from one source, so they agree, and the model of the dialogue decides, as the owner asked.

**B. A second model in the app decides beside the voice.** The voice model answers each question by itself at once. The app sends the text of each spoken request to its routing call, which builds a screen when it helps. Cost: one more model call for each spoken request, the voice and the picture come from two sources, and the voice model does not know what is on the screen. Gain: the spoken answer starts as fast as today.

**C. Both: the voice answers, and it also hands off.** Not possible. The probe showed that the voice model does not answer after a hand-off.

The author chose A because a teacher's words and picture must agree, which is an anti-goal of B. The delay of A is the thing that the owner judges in the live scenarios. If the owner finds the wait too long, B replaces A for the request to explain, and the rest of this design stays.

### How the screen is laid out

**One stage that holds one app (chosen).** The stage is the app of the dialogue, drawn by the code that draws each mini-app, in a wider layout. The strip at the bottom is new host code. This reuses the renderer, the store, the change by lines, and the tests of push 1.

**A special renderer for dialogue screens.** Not chosen: it would be a second way to draw the same parts.

## Design

### 1. The screen

The dialogue mode is one full screen with two zones.

**The stage** takes all the space above the strip.

- Before the first screen, the stage shows only the Alphy character, and the strip shows the status and the microphone state with no small character. With a screen, the strip also shows the small character beside the status.
- With a screen, the stage shows the app: its kind label, its title, and its parts. The stage scrolls when the app is taller than its space.
- At the phone size the parts stand in one column, as in the four phone mocks.
- At the fold and the tablet size the stage has two columns, as in the wide mocks: the main visual with its controls at the left, and the cards (the focus card, the lesson step, the question) at the right. The host places the parts by their role, and the role comes from the kind of the part. The main visual is the first part that is a drawing or a 3D model (a canvas, or a card that holds only canvases), or a deck. The main visual and each next part up to the first card or list stay at the left. So the inputs and buttons that change it stay under it, and a text right after it is its caption or status and stays with it. Each other part goes to the second column. Changed during the build (task Da2): a text after the main visual stays with it, and a picture part is not built, so it is no main visual. A screen with no main visual stays one column. A plan does not name columns.
- The stage keeps its screen until a hand-off replaces or changes it. A spoken answer with no screen leaves the stage as it is.
- A screen is a stored app with its plan. So it is in the chat of the conversation, and the person finds it after the mode ends.

**The strip** is at the bottom and is as low as its content.

- First line: who speaks or what happens (Alphy speaks, Alphy listens, Alphy works), with the voice indicator, and the state of the microphone at the right.
- Second line: the current line of text. While the voice model speaks, this is the text of its speech from the endpoint. This line is the only subtitle of the first version. If it costs more than a small change, it is cut, and the owner is told.
- Buttons: the microphone (mute), and the button that ends the mode.
- Typed text and the attach button of today's composer stay reachable from the strip. The exact form is left to the builder.

### 2. Who decides, and what happens

1. The person speaks.
2. The voice model decides. It answers a plain question by itself at once. It hands off a request when a picture, a diagram, or a model that the person can change makes the answer clearer, and each request about the screen. Its instructions say this in words, with two or three examples, and name no list of topics.
3. For a hand-off, the routing call of the app decides the kind. The routing call is a model too. For a request from the voice mode it has one more kind: a screen. It also keeps today's kinds (an answer in text, an app, a change, an action).
4. For a screen, the app starts the plan call beside the routing call, so that the screen does not wait for the routing call. The routing call returns one to three sentences to speak. The voice model speaks them. The screen appears when its plan compiles clean and passes the host checks.
5. If the routing call says that the request needs no screen, the plan call is cancelled, and the voice model speaks the text answer.
6. A request that needs fresh facts (the owner's example: the prices of a stock as a chart) searches first, as today. The screen is then built from the found facts, so its plan call starts after the search and not beside the routing call. The voice says a short line while this runs. An estimate from the measured parts: the chart is on the stage 12 to 18 s after the end of speech.

The request to explain starts with the fast path. When the person asked in words to see a thing and the plan falls back, the voice says in one line that the screen takes about a minute, and the full generation builds a new app (ruling FX-4 of the plan of 2026-11-21, which reverses OD-5). The old app stays in the store. When the person did not ask to see a thing, a plan that falls back gets no more call: the voice has answered, and the stage stays as it was. A failed full generation says its reason group and one hint to say the request in full (ruling FX-5).

### 3. A follow-up

The routing call gets the title and the plan of the screen on the stage. A follow-up is one of three kinds, as section 12 of the app file design found in the mocks:

- **A press**: the screen has a control for it ("разбери по частям", "а если не вращать?"). The app runs that control. No plan call. The voice says the text of the new state.
- **A change**: one change call with lines ("покажи связь", "убери панель"). The stored values of the screen stay.
- **A new screen**: a new topic, or a change that the kit cannot apply. The new screen of a change gets the plan of the old screen as untrusted data (ruling FX-3). When it also gives no app, the full generation builds a new app (ruling FX-4).

So a screen is planned with the controls that the talk will need, as the prompt section "Dialogue" already asks.

### 4. What the owner gets to judge, and when

The build order puts the live trial early:

1. The stage and the strip, with today's hand-offs. A mini-app that a hand-off makes then shows on the stage.
2. The decision flow of part 2 for a request to explain or to teach.
3. The follow-ups of part 3.
4. Live scenarios with the real voice endpoint and recorded speech: "объясни, как работает сердце", "почему Луна повёрнута к Земле одной стороной", "научи меня считать до десяти по-японски", each with two follow-ups. Each run reports on one clock: the end of speech, the hand-off, the first spoken words of the answer, and the visible screen, with a picture of each screen at the phone, the fold, and the tablet size.

After step 2 the owner gets a build to install and try. The author cannot install a build on the owner's phone or tablet.

### 5. Measures

No bar is set before the first live run, because the owner asked to judge by use. The runs report these numbers, and the owner decides:

- The time from the end of speech to the first spoken words of the answer, for a request with a screen and for one without.
- The time from the end of speech to the visible screen.
- The share of spoken requests that got a screen, and for each one a reading: did the screen help.
- The share of follow-ups by kind, and the time of each kind.

## Assumptions

| Assumption | Basis | Test |
| --- | --- | --- |
| The voice model hands off only the requests that a visual helps when its instructions leave the choice to it. | The probe told it to hand off each such request, and it did. A free choice was not tested. | A probe session with a mix of plain questions and requests that a visual helps, before the build of part 2. |
| The spoken explanation of a request with a screen starts 6 to 8 s after the end of speech. | The sum of measured parts. | The clock of step 4. |
| A screen for "how the heart works" from simple shapes is good enough to teach with. | The mock screens are plans of 4 to 16 lines. No model wrote a heart. | The live scenarios, read by the owner. |
| The two-column stage needs no change of a plan. | The prototype had a layout by role. The app has none. | The fixtures of the four mocks at the fold and the tablet size. |

## Open points

- A 3D model cannot be checked on the emulators of this work. A screen with a 3D model needs a device with a suitable WebView, or the owner's own trial.
- The voice mode has known defects that a different session owns (echo, the gap after an interruption). They can make a live trial feel worse than this design.

## Left to the builder

- The exact sizes of the strip and of the two columns, and the width at which the stage changes from one column to two.
- The place of typed text and of the attach button in the strip.
- The wording of the instructions of the voice model and of the routing rule, inside the decisions above.
- The names of the routing kinds and of the trace events.
