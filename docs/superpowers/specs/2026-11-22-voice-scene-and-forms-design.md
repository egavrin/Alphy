# One scene and more forms for the voice mode: design

Date: 2026-11-22. Base: Alpha 17 (`8fb84bb`). Status: approved. The owner approved the 8 picks of section 2 on 2026-11-22 ("8 picks approved"), set the order of the stages (section 15), and asked for a graphics library that is not hardcoded, that can grow to thousands of components, and whose effect on speed and quality is measured (section 12, rewritten twice after his words). No code exists for this design.

## 1. Source

The owner used the voice mode of Alpha 17 and gave nine observations. A study checked them with 12 live dialogues in 13 paid runs and a code reading. The owner then decided:

- "Both directions": the voice and the screen act as one, and data gets its own forms.
- "I also agree with all report analysis details. a-e items should be included": the proposal groups A to E of the study report are the scope.

Records (outside Git, in the worktree `app-file-parts`):

- `evidence/ux-2026-11-21/report.html`: the study report with the proposals A to F.
- `evidence/ux-2026-11-21/code-facts.md`: the present code, 15 sections. Cited below as CF §N.
- `evidence/voice-probe-2026-11-22/design-recon.md`: where each change lands. Cited as DR §N.
- `evidence/voice-probe-2026-11-22/report.md`: the paid probe of the voice endpoint. Cited as PR.
- `evidence/ux-2026-11-21/lane-a.md`, `lane-b.md`, `lane-c.md`: each step of each dialogue.

## 2. Decisions of the owner (approved on 2026-11-22)

Each item below was a pick of this design. The owner approved all eight on 2026-11-22 with the words "8 picks approved".

1. **A voice-only answer stays instant, and a follow call adds a screen** (section 7.3). The other ways: every such question goes to Alphy first, or only the old screen leaves.
2. **Exact speech for each text of Alphy** (section 8.3). The voice no longer retells in its own words. The probe covered sentences of 6 to 10 words on one subject with no talk history.
3. **Voice-only turns are saved in the chat** and show there after the talk (section 10.3). The other way: keep them only for the present talk.
4. **A long table scrolls inside its own box. The page never scrolls** (section 11.1). His rule of 2026-11-21 is "always fits, no scroll". The other way: a table shows only the rows that fit, and «Ещё» or a spoken «дальше» shows the next rows.
5. **Weather from Open-Meteo** (section 11.3). It gets the place name of the request. For "here" it gets the coordinates rounded to 0.01 degree (about 1 km). Today no coordinate leaves the phone in a chat request.
6. **Photos from Wikimedia Commons** (section 11.3). It gets the search words of the request.
7. **Part F (polish) is in** (section 13), although his words name A to E. A map and a free drag are out (section 5).
8. **A release for each stage** (section 15). Each push needs his word. On 2026-11-22 he asked for smaller stages: each one a finished component with its own push and release.

## 3. Intent

A person talks with Alphy and looks at one scene. The voice speaks about what the scene shows. The scene moves with the voice. Each turn of the talk leaves a fitting scene on the stage, or none. Data, comparisons, and pictures have forms that fit them.

The owner's goal from 2026-11-20 stays: an interactive teacher. A person asks, sees, asks again, and the picture follows.

## 4. Goals, measured on the 12 study dialogues

The study gives the present value of each measure. A new paid run of the same 12 dialogues gives the new value.

| Id | Goal | Alpha 17 (study) | Target |
| --- | --- | --- | --- |
| G1 | The voice names no control, no effect, and no offer that the screen or Alphy's text does not have. | 10 steps: `uxTap` 1, 2, 3, 5b, `uxOrbit` 1, 5, `uxCompare` 1, `uxWeather` 1, 4, 5 | 0 steps |
| G2 | For an explanation with steps, the stage shows step k while the voice says sentence k, with no press. | 0 of 4 stepped explanations | each stepped explanation, the picture changes at most 0.3 s after the heard start of each sentence on the owner's phone (the plan of stage 2 measures the playback delay) |
| G3 | The first state of a picture shows the point. | 2 of 6 checked screens | at least 5 of 6 |
| G4 | No step ends with a screen of a different subject on the stage, or with a waiting action card of an earlier subject. | 4 steps: `uxMixed` 3 and 5, `uxFootball` 5, `uxWeather` 5 | 0 steps |
| G5 | A question that a picture makes clearer gets a screen, also when the voice answers alone. | sky, black hole, seasons: no screen | a first line on the stage at most 8 s after the end of the speech |
| G6 | "Show this" after a voice-only answer keeps its subject. | 3 questions back (black hole, tower photo, tower map) | 0 questions back |
| G7 | Weather, a comparison, a table, a chart, and a photo each show in their own form. | 0 of 5 | 5 of 5 |
| G8 | Motion that runs by itself comes on the fast path. | 37 s with 25 s of silence | controls on at most 12 s after the end of the speech |
| G9 | Speed does not get worse: the first voice word, the first line of a first screen, and its controls. | first word 1 to 2 s, first line 3.2 to 7.6 s, controls 5.3 to 16 s (emulator, three at once) | in a paired run on one emulator, no median is later than its Alpha 17 median by more than the noise of that run (the spread of Alpha 17 against itself, at least 0.3 s). The free stream gate (0.3 s) stays binding. |
| G10 | In dark theme the stage and the app have one background. | two backgrounds | one |

## 5. Non-goals, constraints, and anti-goals

Non-goals of this design:

- Lessons as courses (course slides). The owner put them after the templates of the voice chat.
- A map with a pin. The study showed the gap. No form of the lists A to E holds it.
- A free drag of an object in the picture (the first ChatGPT mock). Taps on the picture are in. A drag stays for a later design.
- A faster web search. A searched answer still takes 12 to 25 s. This design shows what Alphy does during that time.
- The full generation (the agent loop). It stays the path for a request that no form holds.
- The typed chat. Its screens do not change, except where it shares the kit.

Constraints from the project and from earlier decisions of the owner:

- Speed comes first. Each change is measured against Alpha 17 in a paired run.
- Strict text caps, and no page scroll of a template screen (2026-11-21).
- The app draws at font scale 1.0 (2026-11-21). No layout work for system font sizes.
- The model decides whether a turn gets a screen (2026-11-20).
- No compatibility with files of earlier development versions (`AGENTS.md`). A stored template file of Alpha 17 need not open as a scene with narration.
- Expected results and acceptance decisions of a generated check stay in the host (`AGENTS.md`).

Anti-goals (a result that has one of these is a failure):

- A later first voice word, or a later first line of a first screen.
- A template screen whose page scrolls at the phone size or the fold size.
- A rule by keyword that decides whether a turn gets a screen. The owner's rule from 2026-11-20: the model decides.
- A voice that waits in silence for a screen.
- A form that shows invented data.

## 6. Terms

- **Scene**: the app on the stage, its narration, and its present state. One model call writes the picture and the narration of a scene.
- **Narration**: the spoken sentences of a scene. One sentence for each step, written in the template file.
- **Step**: one option of the stepped control of a scene.
- **Narrator**: the host part that walks the steps: it sets the control and makes the voice say the sentence of the step.
- **Opener**: the first spoken text of an answer with a screen. It is the text of the routing call, as today.
- **Voice-only turn**: a turn that the voice model answers with no hand-off.
- **Follow call**: a background routing call for a voice-only turn. It decides what the stage does. It makes no speech.
- **Form**: a template kind. A **family** is a group of forms that share one prompt.
- **Hand-off**, **routing call**, **template call**, **stage**, **strip**: as in `docs/voice-integration.md`.

## 7. Approaches

### 7.1 Who writes the spoken text of a screen

| Approach | For | Against |
| --- | --- | --- |
| (a) The routing call writes all the speech, as today. | No change. | The first screen and its speech come from two calls that do not see each other (CF §8). The study showed the result: the voice told about lungs and valves over a picture of filling phases. |
| (b) The template call writes all the speech, also the opener. | One call owns the screen and every word about it. | With an app already on the stage the template call starts after the routing call, so the opener would come 2 to 3 s later. Two modes of the opener. |
| (c) **Picked.** The routing call writes a short opener. The template call writes the narration: one sentence for each step, or one sentence that says what to try. | The first words keep their time. Each sentence about the picture comes from the call that wrote the picture. One rule in each case. | The opener of a first screen is still blind to the picture. The rule for it gets strict: one sentence, the short answer, no list of parts or steps. |

### 7.2 How the picture walks with the voice

The probe PR measured both ways on the real endpoint.

| Approach | What a person hears and sees |
| --- | --- |
| M1: the host sends one sentence at a time. The stage moves when that sentence starts. | Sent after the last sentence ended: 1.5 to 1.8 s of silence before each step (PR Q1 a, b). Sent one ahead, when the last sentence starts to play: the voice says it after a pause of 0.6 to 0.9 s, with nothing cut or dropped (PR Q1 c, 3 of 3 tries). The host can stop the walk at each step, because at most one sentence waits. |
| M2: the host sends all sentences in one text. The stage moves by the times of the output transcript. | One continuous explanation with pauses of 0.4 to 0.9 s (PR Q3, two runs). The host cannot stop or change the later sentences: the voice model already has them. |

Picked: **M1 with one sentence queued ahead.** Its pause is almost the pause of one answer, and a tap or a spoken word can stop the walk with at most one sentence left.

The stage moves by one rule in both ways (PR Q3). The first piece of the output transcript of a new sentence arms the change. The change happens at the next voiced sound after at least 200 ms of silence, at the moment when the phone plays that sound. With no such sound in 1.5 s, the change happens 0.45 s after the transcript piece. In the probe this rule hit 14 of 14 sentence starts of the received stream. The rule was made after the data was seen, so the plan of stage 2 confirms it on new runs.

The end of a reply (800 ms of silence) cannot mark a step: in one run one answer split into three replies (PR section 3).

### 7.3 A turn that the voice answers alone

| Approach | For | Against |
| --- | --- | --- |
| (a) **Picked** (decision 1 of section 2). The voice answers at once. A follow call decides in the background whether a screen helps. | The first word stays at 1 to 2 s. The model decides for each turn. | One more routing call for each voice-only turn. The screen comes during the answer, not with its first word. |
| (b) Each such question goes to Alphy first. | Speech and screen agree by construction. | The answer starts 4 to 6 s later. |
| (c) Only remove the old screen. | Small. | Sky, black hole, seasons still get no screen. |

In the 53 hand-off steps of the study, the hand-off came 0.8 to 1.6 s after the end of the speech and 0.3 to 1.0 s before the first voice word. So the first voice word with no hand-off before it marks a voice-only turn. This signal needs no keyword.

### 7.4 How many forms one prompt holds

| Approach | For | Against |
| --- | --- | --- |
| (a) One template prompt with all forms. | One call path. | The template section is 7132 characters for 3 kinds (DR §1). With 11 more forms it grows to about three times that. A larger grammar makes more faults and a slower call. |
| (b) **Picked.** Families: explain, data, practice. The routing call names the family. The early call of a first screen uses the explain family. | Each call reads a short grammar. The most frequent case keeps its early start. | A data or practice screen starts its call after the routing call. These screens need data or a decision first in any case. |

The count of forms can grow to some tens. A family holds at most 8 forms in its prompt. When a family has more, the search of section 12.2 picks the forms that fit a request, and the prompt gets only those.

## 8. Part A: one scene for the voice, the taps, and the screen

### 8.1 Narration in the template file

- A template file gets narration lines. A stepped form has `say1` to `sayN`, one for each step, in the order of the steps. A form with a slider, or with no control, has one line `say`.
- A step sentence says what the picture shows at that step. The single `say` of a slider scene says what to move and what to watch.
- Cap of a sentence: 110 characters. A longer sentence is cut after its last whole sentence, as a card text is today.
- A narration line draws nothing. The host reads it from the stored template file (DR §1, `kept`).
- A change of a scene by lines can change a narration line by its name, as any other line.
- A stepped form with a missing `say` line is valid. The narrator then says the card text of that step.

### 8.2 The opener

- The routing text of a new screen becomes one sentence: the short answer. It lists no parts and no steps, and it names no control.
- The template call of a later screen still gets the routing text (CF §8), so its narration agrees with the opener.

### 8.3 The voice says Alphy's text

- The voice instructions change (`voice/LiveProtocol.kt:34-47`, DR §10):
  - Say a marked text from Alphy word for word (see the mark below). Add no control, no action, no offer, and no question.
  - Do not name Alphy, a hand-off, a request, or a mini-app. Wait lines do not say who works.
  - Speak the language of the person's last request.
  - Use one form of address in a talk (ты in Russian).
- The prefix «Ответ Alphy: » and the host lines that name Alphy get new wording with no inner words (`ui/LiveVoiceBridge.kt:330-340`).
- Host lines say what to press by its label: «Нажми «Выполнить»», not «нажми кнопочку на карточке».
- Each text of Alphy goes to the voice with a mark for exact speech. One instruction line tells the voice to say a marked text word for word, with no words of its own. Basis (PR Q2): under the present rule «перескажи своими словами» the voice told later steps ahead, merged 6 sentences into 2, and added «Я открыла на экране…». With the mark, 23 of 23 sentences were spoken word for word, with no filler, offer, or question.
- The lines of an approval keep their behavior and their tests: a proposal with «да» or «нет», and the button on a card.
- So the voice says about the screen only what Alphy wrote, and Alphy wrote it with the scene and its state in view. The short waiting line before an answer stays the voice's own, under the rules above.

### 8.4 The present state reaches each model call

- `ShownScreen` adds the present value of each control, the present step, and the last three actions on the scene. An action is: who (tap, voice, narrator), which control, which value.
- The routing call and the follow call get this line. So «Что сейчас на экране?» names the chosen option, and «следующую» means the option after the chosen one.
- The size stays far below the 8000 character cap (DR §8).

### 8.5 A tap is heard

- The voice bridge watches the state of the stage app (DR §8, choice b). A change of a stepped control by a tap is an action.
- When the voice is silent, a tap on a step makes the voice say the sentence of that step. No model call. In the probe the voice said such a sentence word for word 1.0 to 1.4 s after the send (PR Q4).
- When the voice speaks, a tap changes the picture and says nothing. The action still enters the state line.
- A tap during a walk of the narrator stops the walk. The person has taken control. The sentence that plays ends. The host does not play the one sentence that already waits at the voice model.
- A slider has no sentence for each value. A tap on a slider says nothing and enters the state line.
- Open, the owner's point 3 (a press stops the voice): the emulator did not show it, and the code has no path from a tap to the playback. The test on the owner's phone decides (mute the microphone, then tap while the voice speaks). When the microphone is the cause, stage 1 sends no microphone sound to the voice model from a touch on the stage until 300 ms after its end. When it is not the cause, stage 1 first adds a log of each flush and each reply end to a build for his phone.

### 8.6 A spoken answer counts on the screen

- When a check is on the stage, an answer goes to the check. The voice instructions send it to Alphy, and the follow call catches an answer that the voice took alone (section 10).
- The routing or follow call maps the heard words to one option (kind `press`). The check marks right or wrong with no further call, and the voice says the feedback sentence of the check.
- The voice asks a check question when that question shows on the stage, not before. The question text is narration.

## 9. Part B: the picture walks with the voice

### 9.1 The narrator

- After the opener of a stepped scene, the narrator walks the steps from the first to the last. For each step it sets the control and makes the voice say the sentence. The method is the pick of section 7.2.
- The walk starts by itself only for a new stepped scene that answers the present request.
- A new scene with one `say` (a slider, a choice, or no control) has no walk. The voice says that sentence once, after the opener. A scene that a follow call added says nothing.
- The walk stops when the person speaks, taps a control of the scene, or when a new answer comes. It never starts again by itself. The probe did not test a spoken word over a sentence that waits: the plan of stage 2 tests it first.
- «Дальше» after a stop moves one step and says its sentence. «Продолжай» walks the rest. Both go through the routing call as a press, as «Дальше» does today.
- While the narrator walks, the strip shows the status of the voice as today. No new control shows.

### 9.2 The first state shows the answer

- A stepped scene names its answer step (the step that shows the point, by default the last). The stage shows the answer step during the opener. The walk then goes from the first step to the last.
- A slider scene and a `cells` scene start at a value that shows the point. The prompt states this rule, and the model check judges it by a picture of each first state.

### 9.3 A change of the picture is smooth

- When a control changes, the picture does not jump. Numbers of shapes glide to their new values in about 250 ms when both states have the same shapes. In each other case the two states cross-fade.
- The host does this between two frames (DR §3, choice b). No extra call into the app.

### 9.4 Motion that runs by itself, on the fast path

- A new control form of `scene`: `play`. It is a value that runs from its start to its end in a named count of seconds, again and again, with pause and play, and three speeds: slow, normal, fast.
- The kit `animate` gets a loop and a pause (DR §3). The host playback control exists and is reused (`hostControls.playback`, DR §3).
- A spoken «быстрее», «медленнее», «стоп», «дальше» is a press of that control: one routing call, no template call.
- The template prompt no longer sends "a thing that moves by itself" to the full generation.

### 9.5 A classifier beside the walk and for spoken commands (amendment of 2026-11-22, stage 3)

Source: the owner asked for a more reliable step change and for more freedom: «верни назад», «повтори», moves inside one scene, and a question that returns the picture to an earlier step. A paid probe measured the documented GPT-Live mechanisms (`evidence/decisions-probe-2026-11-22/report.md`, outside Git). The owner then chose "add both" for stage 3.

Facts from the probe:
- The voice model has no tools of its own. It can only hand a request over.
- GPT-Live can run a fast classifier (the Decisions API, model `gpt-6-luna`) on each caption piece of the person or of the voice (`session.triggers`). An answer comes about 0.12 s after its piece.
- On the person's words it named the command at the end of the phrase in 6 of 6 cases, before the hand-off event. It also named the step of a question. A piece in the middle of a phrase can mislead.
- On the words of the voice it followed the steps also in the voice's own words, about 0.3 to 1.1 s into a sentence, and it was sometimes wrong or unsure. The exact comparison of stage 2 is faster and is never wrong, but only on exact words.
- A scene text with a stale line "the screen shows step N" made the classifier wrong.

Decisions:
1. (Requirement, the owner) Spoken commands inside a stepped scene: next, back, repeat, and go to a named step. The classifier names the command at the end of the phrase. The host moves the step and the voice says the sentence of that step. No routing call runs for such a command.
2. (Requirement, the owner) A question about a step moves the picture to that step. The answer comes as today: from the voice alone or through a hand-off.
3. (Requirement, the owner) A second signal for the walk. When the exact comparison does not find the start of the next sentence, a classifier answer for that step moves the picture. In a walk the picture only moves forward.
4. (Requirement, the owner) In an answer that the voice gives in its own words, the classifier can move the picture to the step that the voice talks about, also back. A text of the host (a narration sentence, a sentence after a tap) stays with the exact comparison.
5. (Requirement, the owner) With no Decisions permission, or with a Decisions error, the voice mode works as in Alpha 19. The person sees no error.
6. (Implementation choice) The host uses the native triggers of the voice session, not a call of its own. A call of the host with only the sentence in progress had no wrong step in a text replay. It stays an option when the live check shows misses.
7. (Implementation choice) The scene text for the classifier holds the title, the step labels, and the step sentences. It holds no line about the present step. The host resolves "next", "back", and "repeat" against its own present step.
8. (Implementation choice) The end of a phrase is the hand-off of that turn, or 1.0 s with no new caption piece of the person when no hand-off came. The thresholds are in the plan of stage 3.

Limits, not tested by the probe: a command while the voice narrates, an echo of the voice in the microphone on a phone, and a talk longer than one minute. The live check of stage 3 covers the first. The owner's phone covers the second.

Data: with the triggers, the captions of the talk and the scene text go to the Decisions model of OpenAI. `docs/voice-integration.md` states this under «Data sent to OpenAI».

## 10. Part C: every turn updates the stage

### 10.1 The voice words reach the host

- The session forwards each piece of the output transcript with its times to the host (DR §9). The host keeps the words of the voice for each turn.

### 10.2 The follow call

- Signal: a turn of the person ended, a reply of the voice starts, and no hand-off came for that turn. A reply to a text that the host sent (a sentence of the narrator, a sentence after a tap, a typed turn) is not a voice-only turn.
- The host then starts one follow call with the person's words, the shown scene with its state, and the talk so far. It runs beside the voice and makes no speech.
- Its kinds:
  - `none`: the stage stays.
  - `screen`: a new scene for this turn. The template call starts. The scene comes with no opener and no walk, because the voice already answers. Its first state shows the answer. Its narration serves a later tap and a later «Дальше».
  - `mark`: the answer is on the shown scene. The stage marks it: a step, a row, a cell, a bar, a part.
  - `press`: the turn is an answer to a check or a request to move a control.
  - `clear`: the subject changed and no screen helps. The scene leaves the stage.
- A follow call has its own lane beside the hand-off queue (DR §9: the queue runs one request at a time). A new turn or a hand-off cancels a follow call that still runs.
- Cost: one routing call for each voice-only turn. In the study, 11 of 60 lines were voice-only.

### 10.3 Voice-only turns enter the talk

- A voice-only turn is saved in the chat as a pair: the person's words and the words of the voice, marked as spoken. Reason: «это» and «её» in the next request then have a subject, and the chat after the talk holds the whole talk. Today the chat holds only the turns with a hand-off.
- The pair enters the context of each later routing and follow call (`spokenContext`, DR §9).

### 10.4 The stage has what the talk is about

- A scene that left the stage stays in the chat as a card.
- A waiting action card (a plan card) leaves the stage when a later turn has a different subject and the card still waits. It stays in the chat.
- While Alphy works on a turn with no scene yet (a search, a build), the stage shows a small work card with the subject: «Ищу: счёт матча «Реала»». The old scene of a different subject does not stay under a new answer.

## 11. Part D: more forms

### 11.1 Rules for every form

- Strict text caps, as today: a text outside the picture has a hard cap, and a long card text is cut at a sentence end.
- The page does not scroll at the phone size and at the fold size. A table with more rows than fit scrolls inside its own box.
- The stream: each form is drawn line by line while the call writes it. A fault leaves one line out, and one repair call writes only the faulty lines.
- A change by lines and the merge by name work for each form.
- A form that shows fetched data shows the source as a site name, and the time of the data.

### 11.2 The forms

The explain family (the early call of a first screen):

| Form | What the person sees | Control | Narration |
| --- | --- | --- | --- |
| `scene` | One picture of free shapes and ready objects. Exists. | slider, choice, **steps**, **play** | `say1..N` for steps, else `say` |
| `items` | One sign and a label for each item. Exists. | choice or steps | `say1..N` |
| `cells` | Cells of a whole. Exists. The caption "K / N = P%" becomes optional, so a count of unit squares can be the accent. | slider | `say` |
| `diagram` (new) | One picture with all its parts named at once: up to 8 labels with leader lines. One part is marked. A card gives the fact of the marked part. | steps over the parts | `say1..N` |

"Steps with a play control" of the study report is the `steps` control with the narrator. It serves `scene`, `items`, and `diagram`.

The data family (the call starts after the routing call):

| Form | What the person sees | Caps |
| --- | --- | --- |
| `compare` (new) | Two or three things side by side: a sign or an object and a name for each, and up to 6 rows with one value for each thing. One row can be marked. A card gives the point. | 3 things, 6 rows |
| `table` (new) | A header and rows. One row or one cell can be marked. A caption. | 4 columns, 12 rows, 8 rows visible on a phone |
| `fact` (new) | One large value with its unit, a label, an icon or an object, and up to 3 small facts. For a score: the two names and the score. | value 8 characters, 3 facts |
| `forecast` (new) | Up to 7 days in a row: the day, a weather icon, the high and the low. The place and the source. | 7 days |
| `chart` (new) | Bars or a line, with a label and a value at each point. One point can be marked. | 8 points |
| `photo` (new) | One picture, a caption, and the source: the site, the author, the license. | 1 picture |
| `nodata` (new) | A short card: what is missing, and what the person can say next. | 2 sentences |

The practice family (the call starts after the routing call):

| Form | What the person sees | Caps |
| --- | --- | --- |
| `check` (new) | One question at a time with 2 to 4 options. A tap or a spoken answer marks it right or wrong at once. A count of right answers at the end, not before the first question. | 5 questions |
| `task` (new) | A goal, a grid of cells that the person fills by taps, a count that follows the taps, a success line, and the next task. First version: a grid and a count target, as in the second ChatGPT mock. | 3 tasks, grid 6 × 6 |

### 11.3 Where the data of a data form comes from

- **Weather**: the host asks Open-Meteo and fills `fact` and `forecast` itself. No template call. The routing call gives the place and the days. One answer call writes the spoken sentence from the numbers. Open-Meteo gets the place name of the request, or for "here" the coordinates rounded to 0.01 degree, as the app rounds them today (DR §7). It gets no other data.
- **A fresh fact** (a score, a league table, a next match): the web search as today, then the data template call with the source excerpts. When the sources do not hold the value, the call writes `nodata`. It never writes a value that no source has.
- **Known facts** (planets, a comparison): the data template call alone.
- **A photo**: the routing call gives the search words and the caption. The host asks Wikimedia Commons, takes the first result with a free license and a sufficient size, loads a picture of at most 1024 px, and stores it on the phone by the path of a generated image (DR §6). No template call. With no result, the stage shows a ready object of the same subject when one exists, else `nodata`. A generated picture stays behind its setting, as today.

### 11.4 Parts that exist and are reused

- The native table (DR §2) gets a mark for a row and a cell. The native chart gets a label at each point when it has 8 points or fewer.
- The kit `question` part checks an answer with no model call (DR §11). `check` uses it.
- The stage already sends a tap on a kit drawing to the kit (DR §5). `task` needs a hit test in the kit only.

## 12. Part E: the graphics library

### 12.1 The owner's words and the requirements from them

2026-11-22, first: "list of graphics and ready objects should be extendable, and be hardcoded - I expect that list could be systematically extended over time. Plus, provided list is not generic enough."

2026-11-22, second (dictated), after this design read the first as "fixed inside each release":

- «Я говорю не за хардкожина, потому что предполагаю, что этот список будет расширяться. Причем я не знаю, статически или динамически.»
- «Тем очень много ... они очень быстро могут вырасти там за 1000, за 2 и так далее. Это, их пополнение — это в целом отдельная тема.»
- «Непонятно, как это повлияет на скорость поиска, как это повлияет на качество. И эти вещи должны быть учтены.»
- «мы должны иметь возможность расширять количество темплейтов до какого-то разумного количества»
- «мы выбираем из какого-то набора компонент, в зависимости от контекста. Это тоже должно происходить быстро. Возможно ... через эмбеддинги, чтобы мы могли там по нескольким тысячам искать»
- «языковая модель просто какие-то дополнительные там тексты или формулы ... кастомизирует ... или ... данные дополнительные из интернета туда подставляет в этот готовый темплейт»
- A hypothesis of his, which he calls not reliable: the device fills the list by itself for a subject that the person studies, from the long path.

Requirements of this design from these words:

| Id | Requirement |
| --- | --- |
| L1 | Nothing is hardcoded: a form, a shape kind, an element, and an object are entries. The engine, the grammar, and the prompt text hold no list of them by hand. |
| L2 | The design holds for some thousands of components and for some tens of forms. The time of a model call and its fault rate do not grow with these counts. |
| L3 | The app picks the components that fit a request before the model call, fast. The model chooses among them. |
| L4 | The speed of the search, its accuracy, and the effect of the library on the pictures are measured before the library grows, and again at each round of growth. |
| L5 | The list is never complete. A thing with no entry never blocks a screen and never makes it later. |
| L6 | The list can grow with a release or on the device. The entry format and the search allow both. How the list is filled is a separate subject. |

### 12.2 The pipeline of a screen

This is the owner's picture of the work, with the place of each step in this design.

| Step | Who | How |
| --- | --- | --- |
| 1. The form | the routing call, then the template call | The routing call names the family (section 7.4). The template call picks the form among the forms of that family. A family holds at most 8 forms in its prompt. When a family has more, the search of step 2 also picks the forms that fit, and the prompt gets only those. |
| 2. The components | the app, with no model call | A search in the catalog with the words of the request gives a fixed count of candidates: 24 objects, each with its name, one line on what it shows, and its parts. The generic elements are always in the prompt: they fit any subject, and their list is short. |
| 3. The fill | the template call | The model places candidates, and writes the texts, the numbers, the formulas, and the narration. |
| 4. The data | the app | Fetched data enters as section 11.3 says. |

The prompt has the same size for a catalog of 100 entries and of 5000 entries. So the call time does not depend on the size of the catalog. Only the search does.

### 12.3 The search

- The app already has a text embedding model on the device (EmbeddingGemma, 768 numbers for a text, `semantic/SemanticRuntime.kt`). The agent uses it today to pick device capabilities for a request (`tools/agent/AppAgent.kt:801-812`). A comment in that code gives its cost: about 0.1 s for a short text. This design did not measure it.
- Each entry has a vector. The build makes the vectors with the same model, and the catalog keeps the signature of the model (`VECTOR_SIGNATURE`), so a new model makes new vectors.
- At run time the app makes one vector for the request and compares it with each entry: some thousands of entries cost milliseconds.
- A second source with almost no cost: a word of the request that equals a name or another word of an entry (Russian and English) puts that entry among the candidates.
- The search needs only the words of the request. It runs while the routing call and the early template call start. Budget: the candidates are ready at most 150 ms after the request text is known. When the search is late, or the model is not loaded, the template call starts with the generic elements only. It never waits.
- The voice mode loads the embedding model when the mode starts, so the first request does not pay for the load.

### 12.4 Measures and gates

| What | How it is measured | Bar |
| --- | --- | --- |
| Search time | On a phone, with a catalog of 5000 entries (made of copies for the test), warm | at most 150 ms for 95 of 100 requests |
| Load time of the embedding model | From the start of the voice mode | reported, and ready before the end of a first request of normal length |
| Search accuracy | A held-out list of requests, each with the objects that fit it | a fitting object is among the 24 candidates for at least 90 of 100 requests |
| Effect on the picture | A paired model check on the same held-out requests: free shapes only, against the library | pictures judged better with the library, clean files at the first try not fewer, call time not longer |
| A thing with no entry | Requests for things that the catalog does not have | a screen each time, with an emoji or free shapes, in the same time |

- A first small set passes these gates before the rest is drawn: the generic elements and about 30 objects of two areas.
- Each later round of growth repeats the measures. A round that makes one of them worse does not ship.
- The same search and the same measures serve the forms when a family passes 8 forms.

### 12.5 Three layers, one catalog

| Layer | What it is | How it grows |
| --- | --- | --- |
| 1. Shape kinds | The primitives of a drawing. Today: circle, rectangle, line, text, polygon. New: `path` (a line through points, straight or smooth, open or closed), `arrow`, `arc`. New properties: a gradient fill, a glow, an opacity, a turn by an angle. | One entry: the argument form, the fault text, the draw rule. |
| 2. Generic elements | Things drawn by a rule from numbers. They fit any subject. | One entry with one draw rule and its parameters. |
| 3. Ready objects | Drawn things with a name. An object can have named parts, and a scene can mark and label one part. | One data file. No code. |

- Each layer is a registry. The grammar, the faults, the prompt text, the tests, and the review sheet are made from the registries.
- The renderer already draws paths with curves, gradients, groups with a transform, and bitmaps. The kit writes none of them today (DR §3). So the work is in the kit.
- A scene holds up to 20 shapes (12 today). A ready object or a generic element counts as one shape.
- The picture gets more room: on a phone it grows into the empty zone under the app. The fold layout keeps its two columns.

Generic elements, first version (about 40):

| Group | Elements |
| --- | --- |
| Arrows and links | straight arrow, curved arrow, two-way arrow, a flow of marks along a path (it moves while a `play` control runs), a link between two things with a label |
| Marks | a label with a leader line, a brace, a ring around a part, a pulse, check, cross, plus, minus, equals, question mark |
| Formulas | a formula with a fraction, a power, an index, and a root, for math, physics, and chemistry |
| Measures | an axis with ticks, a grid, a number line, a ruler, an angle mark, a dial, a level bar, a thermometer, a clock face, a time line |
| Quantities | a bar, a stacked bar, a sector of a circle, a ring, a fraction bar, an array of dots for a count |
| Motion and force | a wave, a spring, a gear, a wheel, a pendulum, a force arrow with a value |
| Containers | a box, a tank with a level, a beaker, a stack of layers |
| Structure | a node, a tree branch, a block of cells, a speech bubble |

Ready objects, first version: the main things of each area, about 150 in 17 areas. It is a start, not a full list (requirement L5).

| Area | Objects of the first version |
| --- | --- |
| Space | sun, planet, earth, moon with its phase, star, comet, rocket, satellite, galaxy |
| Earth and nature | mountain, volcano in section, river, sea wave, island, rock layers, cave, glacier, dune, fire, water drop |
| Weather | cloud (four kinds), rain, snow, lightning, wind, fog, rainbow |
| Plants | tree, leaf, flower with its parts, seed, roots, plant cell with its parts, mushroom |
| Animals | fish, bird, cat, dog, insect, butterfly in its stages, frog, animal cell |
| Human body | heart with its chambers, lungs, brain, eye with its parts, ear, tooth, skeleton, stomach and gut, muscle, skin layers, DNA |
| People | person, child, face with a mood, hand, group |
| Home and food | house, cup, pot, bottle, egg, apple, bread, plate, lamp, key |
| Tools and machines | lever, pulley, inclined plane, pair of gears, wheel and axle, piston, pump, magnet, balance, hammer |
| Electricity and devices | battery, bulb, switch, circuit wire, resistor, motor, phone, computer, chip, antenna, solar panel |
| Transport | bicycle, car, train, ship, plane, balloon, parachute |
| Buildings and places | tower, bridge, factory, dam, wind turbine, lighthouse |
| Chemistry and lab | atom with shells, molecule of balls and sticks, flask, test tube, burner, crystal lattice |
| Math figures | right triangle, circle with a radius, square on a grid, cube, cylinder, cone, coordinate plane |
| Music | note, staff with a clef, piano keys, guitar, drum, sound wave from a speaker |
| Money and business | coin, banknote, bank, shop, price tag, contract, a line that goes up or down, scales of cost and gain |
| Signs | flag of bands, book, letter, calendar, globe, map pin |

- An emoji stays the picture of a thing that has no entry. The system font draws it, as in `items` today.
- `forecast` and `fact` are not drawings. They use the host icons that exist (Sun, Rain, Snow, Wind, Cloud, DR §4), so the weather stage does not wait for this library.

### 12.6 An entry is data

- One file for each object, in a directory of its area.
- An entry holds: its id (`area.name`), its names and other words for it in Russian and English, one line that says what it shows, its box, its layers of paths with a color role each, and its named parts with a point for a label.
- A color is a role of the palette (ink, accent, water, land, sun, and the others), never a fixed color. So each object follows the palette and the theme.
- Limits of an entry: at most 40 path segments (a frame has 512, DR §3), and readable at the smallest size that a scene draws.
- The build makes from the entries: the catalog, the vectors of the search, a review sheet with each entry in light and dark theme, and the catalog tests (unique names, the segment limit, roles only, a label point for each part).
- A template line places an object or an element: its name, its place, its size, an optional label, an optional marked part, and for an element its numbers.

### 12.7 Where entries come from

The catalog reads entries from more than one source from the first version. The search and the prompt are the same for each source.

| Source | In the first version | Note |
| --- | --- | --- |
| The release | yes | Entries that ship inside the app. To add one: add a file and run the build. |
| A pack that the app loads with no release | no, the format allows it | A set of entries of one subject, with its vectors. Needs a decision on where packs live and who reviews them. |
| The device fills the list by itself | no, the format allows it | The owner's hypothesis: for a subject that the person studies, the long path draws the things once, and the app keeps them as entries. Open: the look of such an entry has no review. This is a subject of its own. |

### 12.8 How the list grows, round by round

- **The miss log.** A template line that names an unknown object is a fault with that name. The same call path draws the thing with an emoji or free shapes. The host writes the name to the release log line, and the model check counts it. The misses, by count, are the first candidates of each round.
- **The coverage table.** A table in the kit documents: the subject areas and the things that an explanation of each area needs. Each round takes its next rows.
- **The review sheet.** Each new entry is looked at on the sheet in both themes before it ships. The owner can see each sheet.
- **The gates of section 12.4** run on each round.

### 12.9 Real pictures

- The `photo` form of section 11.

## 13. Part F: polish (an addition of this design, from the same study)

- Dark theme: on the stage a kit app paints no background of its own. The stage, the app, and the canvas have the shell surface. Cards keep the palette tint (DR §12). The full mini-app gets the same rule and no light canvas.
- The mascot picture gets a transparent background.
- Labels on shapes are readable in both themes: the kit already picks a contrast color for a text shape, and it must do so against the fill under the text.
- A new part of a scene scrolls into view and is marked for a moment.
- During a change of a scene, the old scene dims until the new one is complete. No mix of the two.
- The preview of a slider shows no input warning and no «Применить» button.
- An error on the stage is in the language of the talk and says what the person can say next.
- The close of the voice mode after 60 s with no speech: the timer must not count while Alphy builds a screen. First find which of the two causes of `lane-b.md` O1 holds.

## 14. Speed

- The first voice word, the opener, and the early template call of a first screen keep their paths.
- Narration adds output to the template call: at most about 660 characters for 6 steps. The narration lines are the last lines of the file. The stage stores the scene and turns its controls on at the last line before the narration, not at the end of the call. So the narration cannot delay the controls. The narrator needs the first sentence only after the opener, which takes some seconds to say.
- A paired run on one emulator compares the first line and the controls of a first screen with Alpha 17. A later median is a failure (G9).
- The follow call and the walk add no wait before a first word.
- Weather with a service and a host-made form: the target is a form on the stage at most 2 s after the routing call ends.

## 15. Stages and releases

The owner's words of 2026-11-22:

- «План разбей на стадии, так чтобы можно было какие-то законченные компоненты пушить в мейн, делать релиз, и так шаг за шагом, чтобы оно продолжало становиться лучше.»
- «погоду можно подвинуть на следующие шаги ... давай начнем тогда с первой, второй, третьей. Потом пятый, шестой, седьмой. И потом все остальное ... После каждой стадии делай следующий релиз. Планируй блоками по 3 ... Ты их один за одним делаешь, выпускаешь, пушишь и делаешь релиз. Как заканчиваешь, планируешь следующие 3 стадии. И точно в таком же порядке все выпускаешь.»

Rules of a stage:

- A stage is one finished component. After it, `main` is better for a person and holds no half-built behavior.
- Each stage has its own tasks, its own gates, a paid live check sized to the stage, a push, and a release. The owner gave the word for the push and the release of each stage, when every gate of the stage holds. A gate that misses its bar goes to him first.
- The owner checks each release on his phone after it ships. A failed check becomes the first fix of the next stage.
- The stages run in blocks of three. The task plan of a block is written when the block before it is released.

| Block | Stage | Release | Content | What the owner gets |
| --- | --- | --- | --- | --- |
| 1 | 1. A clean stage and an exact voice | Alpha 18 | Part F (one dark surface, the mascot, readable labels, the dim of an old scene, the scroll to a new part, stage errors, the close after 60 s), exact speech with the new voice rules and host lines (8.3), the present values in the state line (8.4), the tap that stops the voice (8.5), and the split of the template code by form | A calm dark screen. A voice that adds no control and no inner word of its own. A talk that stays open during a build. |
| 1 | 2. The scene speaks and walks | Alpha 19 | Narration and the stepped control (8.1), the short opener (8.2), the step and the last actions in the state line (8.4), a tap that is heard (8.5), the narrator with the answer step, «Дальше», and «Продолжай» (9.1, 9.2) | The picture walks with the voice, with no press. The first state shows the answer. |
| 1 | 3. Every turn updates the stage | Alpha 20 | The follow call, saved voice-only turns, the stage lifetime, the work card (part C) | A question that the voice answers alone can get a screen. No old screen stays under a new subject. «Покажи это» keeps its subject. |
| 2 | 5. Comparison, table, chart | Alpha 21 | The families of forms, `compare`, `table`, `chart`, `nodata`, a marked row, cell, or bar | Data in a form that fits it. An honest card when data is missing. |
| 2 | 6. The library, first set | Alpha 22 | The three registries, the catalog build, the search, the generic elements, about 30 objects of two areas, and the gates of section 12.4 | Pictures from ready parts, with a measured effect on speed and look. |
| 2 | 7. Motion and diagrams | Alpha 23 | `play` with pause and speeds (9.4), the smooth change (9.3), `diagram` | A picture that moves by itself on the fast path. A diagram with all its parts named. |
| 3 | 4. Weather | Alpha 24 | `fact`, `forecast` with Open-Meteo | Weather on the stage with numbers. |
| 3 | 8. Photos | Alpha 25 | `photo` with Wikimedia Commons | A real picture with its source. |
| 3 | 9. Practice | Alpha 26 | `check` with a spoken answer, `task` with taps on the picture | A person tries and gets feedback. |
| later | Library rounds | Alpha 27 and later, one round in each release | More areas of objects, by the miss log and the coverage table (12.8) | A wider library, with the gates of 12.4 at each round. |

The stage numbers stay as the owner used them. The table is in the order of the releases.

Where each stage is planned:

- Block 1 (stages 1 to 3) has its task plan: `docs/superpowers/plans/2026-11-22-one-scene.md`.
- Block 2 (stages 5, 6, 7) gets its task plan when Alpha 20 is released. Block 3 (stages 4, 8, 9) gets its task plan when Alpha 23 is released.
- Until then, each later stage has its content, its goals, and its gates in this design (sections 9, 11, 12).

The release numbers are the planned order, not a promise of dates.

## 16. Verification

- Kit tests for each new line kind, form, and control, with the present template tests as the model (DR §13).
- The model check for each family on held-out requests: clean files at the first try, the call time, and for G3 a picture of each first state.
- The fit gate for each form at its caps, phone and fold. The stream gates 1a and 1b for the new forms.
- A free rehearsal with a scripted voice for: the narrator, a tap that is heard, a follow call of each kind, a saved voice-only turn, the stage lifetime.
- The paid live check of each phase: the 12 study dialogues again with the study harness (`evidence/ux-2026-11-21/harness/`), judged by the goals of section 4, and looked at picture by picture. The budget is stated before each run.
- A check on the owner's phone for the points that an emulator cannot show: the tap that stops the voice (the test of the study report), the feel of the walk, and the dark theme.

## 17. Risks and open points

- Exact speech and the sync rule come from one probe: 7 sessions, one subject, no talk history, and an emulator that plays no sound (PR section 4). The plans of stage 1 (exact speech) and stage 2 (the sync rule) confirm them on new subjects and on the owner's phone, where a playback delay and an echo exist. If exact speech does not hold in a real talk, the fallback is the present retelling with the stage moved by the transcript alone (an error of at most 0.4 s in the probe).
- The voice model takes no silent context: asked not to speak, it said «Понял.» (PR Q6). So the state of the scene goes to Alphy's calls and never to the voice.
- The end of a reply fires in the middle of a walk (PR section 3). The strip status and the approval window use it today. During a walk the narrator owns the status.
- The opener of a first screen is still blind to the picture. Measure in the live check whether a short opener removes the disagreement. If it does not, approach 7.1 (b) is the next step for a first screen.
- The follow call can add a screen that the person did not want. The model decides, and `none` must be its frequent answer. Measure the share of each kind in the live check.
- A saved voice-only pair changes what the chat shows after a talk. The owner sees it in the first build of stage 3.
- Web search found no weather numbers and no score in the study. Open-Meteo answers weather. A score still depends on the search.
- A Wikimedia Commons result can be a wrong or unfit picture for some words. The first version shows a photo only for a request that asks to see a thing, takes search words from the routing call, and shows the source.
- The terms of use of Open-Meteo and Wikimedia Commons are read before stage 4 and stage 8 start (DR §6, §7: both are assumptions today).
- Richer drawings cost output tokens. Ready objects reduce that cost. The model check measures the call time of each family.
- About 190 entries of the first library version are drawn by agents as vector data. Their look is the risk. Each area ships only after its review sheet is accepted, and an entry that does not read at the smallest size is drawn again or left out.
- The library can make pictures worse or calls slower: a model that gets ready objects can force them where free shapes fit better. The paired model check of section 12.4 decides before the library grows.
- The cost of the embedding model is known only from a code comment (about 0.1 s for a short text), and its load time is not known. Stage 6 measures both on a phone first. When the search cannot meet its budget, the candidates come from the word match alone.
- The vectors of the entries must come from the same model that the phone runs. The build makes them in a device job.

## 18. Left to the plan of each stage

- The exact line forms of `say`, `steps`, `play`, each new form, and the line that places an object or an element.
- The file format of a catalog entry, the build of the catalog and of its vectors, the count of candidates, and the order of the areas.
- The exact JSON of the state line and of the follow kinds.
- The native changes for a marked row, chart labels, the smooth change, and the scroll into view.
- The split of the template code and the prompt by family.
- The paid budget of each model check and live check.
