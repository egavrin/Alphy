# App file kit (phase 1): results

This file records the device results of `docs/superpowers/plans/2026-11-18-app-file-kit.md`.
Each section belongs to one task of the plan.

All runs used the emulator `emulator-5592` (AVD `AlphyAppFile`, API 36) and the class `com.alpha.assistant.tools.appfile.AppFileProofTest`.
The `run.json` of each label is in `evidence/alphy-ui/<label>/`, which Git does not keep.

## Timers

This section records the device proof of Task 6.
It answers the two open points of section 8 of `docs/superpowers/specs/2026-11-18-app-file-parts-design.md`.

### Runs

| Label | Revision | Targets | Expected | Observed |
| --- | --- | --- | --- | --- |
| `appfile-timer-red` | `fb72fb3+dirty` | `teaPassesParseAndSmoke`, `timerRoundsPassesParseAndSmoke` | failed | failed: "need demleme: timer is not built yet" and "need timer: timer is not built yet" |
| `appfile-timer` | `fb72fb3+dirty`: the kit sources, the bundle, and the device test of commit `6ae3204`. Only kit tests and the evidence file changed after it | the class, 16 tests | passed | passed, `OK (16 tests)`, 28.6 s |
| `appfile-timer-commit` | `6ae3204+dirty`: the content of commit `c2041f1` | the class, 16 tests | passed | passed, `OK (16 tests)`, 27.8 s, the same APKs as `appfile-timer` |
| `appfile-timer-harden` | `c2041f1+dirty`: the content of the commit "Move the timer findings into the results document and harden the timer tests" | the class, 16 tests | passed | passed, `OK (16 tests)`, 33.7 s. Timer event `[3, 3, 3, 3, 2]` ms, median 3 ms |

Command of a run:

```text
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home python3 tools/compose_acceptance/run.py suite --serial emulator-5592 --label appfile-timer com.alpha.assistant.tools.appfile.AppFileProofTest
```

The test APK `lastUpdateTime` was `2026-11-18 20:45:47` before `appfile-timer` and `2026-11-18 20:47:42` after it.
It was `20:47:42` before `appfile-timer-commit` and `20:55:59` after it.
It was `20:55:59` before `appfile-timer-harden` and `21:18:21` after it.
No other session installed the test APK during these runs.

### Open point 1: a duration that a choice gives

The plan `kit/plans/tea.plan` gives the countdown a formula: 5, 3, or 7 minutes for the chosen tea.
The kit maps the need to Alphy's foreground timer: `{type: "timer", mode: "countdown", action, foreground: {durationMs, vibrationMs: 600, toneMs: 600}}`.
The definition declares the capabilities `audio.tone` and `vibration.play`.

`teaPassesParseAndSmoke` observed these results:

- `ToolSpec.parse` and the smoke step accepted the definition.
- Before a choice, `ToolUiCatalog.validateForegroundTimer` read 300000 ms.
- After the choice "Yeşil çay", `ToolGenerationService.executeEvent` accepted the reply with a new `ui`, and the timer read 180000 ms.
- After the choice "Bitki çayı", the timer read 420000 ms.

Answer: yes. The source may return a `ui` with a new duration after the choice changes, and Alphy's validator takes the new duration.
So the timer part needs no `outside("timer")` rule for a time from a choice.

### Open point 2: no effect from a timer event

A timer with rounds or a rest keeps the end of its phase in the state (`NAME_s.end`, in ms of the host's clock).
Its bar is one surface of 320 by 64 in a `canvas` node.
While it runs, each reply asks for `timers: [{id: "NAME_bar", intervalMs: 1000}]`, and a stop gives `timers: []`.
A timer event cannot ask for an effect, so this form gives no sound and no vibration.
The kit's report has the note "need timer: a timer with rounds shows the end of a round on the screen only", and no fault.
The kit test `check: alert with rounds is a note, not a fault` holds this result.

`timerRoundsPassesParseAndSmoke` observed these results:

- `kit/plans/timer-rounds.plan` passed `ToolSpec.parse` and the smoke step, with no `widget_timer_host` failure.
- The smoke step sent the timer `timer_bar` (its replay held a `timer` event with that id).
- A short plan of 2 rounds went through the host's path of an open app on the real clock.
  The path was one retained `GraphicsSession`, `executeEvent`, and `dispatch` for each event.
  Up to `appfile-timer-commit`, each phase of the short plan was 1 s. From `appfile-timer-harden`, each phase is 2 s, so that a slow emulator does not miss a phase.
- Each timer event came 300 ms after the end of the phase in the state of the reply before it.
- The four replies were: rest of round 1 with 1 finished round, work of round 2, rest of round 2 with 2 finished rounds, and the end with `timers: []`.
  From `appfile-timer-harden`, the test compares the whole `timers` of each reply: `{t_bar: 1000}` in the first three replies, and no timer in the last one.
- Five timer events of the 40 s work phase of `timer-rounds.plan` took `[3, 4, 4, 2, 3]` ms in `appfile-timer`, median 3 ms (limit 50 ms).

Answer: the canvas form works on the device, so the timer part needs no `outside("timer")` rule for rounds.

### Times of the run `appfile-timer`

| Measure | Samples (ms) | Median (ms) |
| --- | --- | --- |
| Timer event through the host's path | 3, 4, 4, 2, 3 | 3 |
| Compile of `quiz.plan` | 29, 29, 30, 28, 29 | 29 |
| Event of `calc.plan` | 9, 9, 8, 9, 9 | 9 |
| Add of `tabs-chart.plan` | 10, 10, 11, 9, 11 | 10 |
| Event of `rich.plan` | 20, 20, 20, 20, 20 | 20 |
| Event of `cards.plan` | 20, 18, 19, 18, 18 | 18 |
| Event on 100 rows | 15, 18, 16, 18, 17 | 17 |

### What the device did not show

- No test heard a sound or felt a vibration at the end of a countdown.
- No test saw a notification. The kit asks for no notification.
- No test pressed the host's own Start, Pause, or Reset button of a foreground timer.
  So no test saw the host's countdown run with the new duration. The proof stops at Alphy's validator and parser of the reply.
- No test took a screenshot of the canvas. The drawing commands passed `GraphicsContract`, and nobody looked at the bar.
- No test sent a timer event with a playback state other than `playing`.
- No test closed the app while a timer ran. Only a kit test shows that an open moves a timer to the clock of now.

## Sizes and times

This section records Task 10: the size of each compiled plan against each limit of Alphy, and the time of each saved plan on the device.
The commits are `1a27230` (the run-time text without the compile-only texts), `43b673e` (the drag of a model in `kit/src/drag.js`), `0b18408` (the budget test and the time test), and the commit "End a drag at each open and time the actions that show after one event".
In that last commit, an `open` event ends a drag (`kit/src/run.js` `handle`), so a drag that the state kept from an app that stopped in it refuses no down of another pointer.
The kit test `run: an open ends a drag, so a drag of pointer 1 that the state kept refuses no down of pointer 0` and the device test `model3dPassesParseAndSmoke` hold this rule.

### Run-time text

Each module of the run-time puts a text that only the compile step reads behind its line `const MESSAGES = true;`.
`kit/build.js` builds the run-time text from the modules without that line and with `define: {MESSAGES: "false"}`, so the minifier drops each such text.
Bun 1.3.9 does not put the value of a module-level constant into the lines of a module that has an import, so a constant with the value false was not sufficient.

| Module | Bytes at `850c5e3` | Bytes now | Change |
| --- | --- | --- | --- |
| `dsl.js` | 5596 | 5283 | -313 |
| `core.js` | 19744 | 17557 | -2187 |
| `draw.js` | 5422 | 5422 | 0 |
| `rows.js` | 3880 | 3440 | -440 |
| `run.js` (now with `drag.js`) | 15496 | 12924 | -2572 |
| Run-time text | 50138 | 44626 | -5512 |

Method of the split: a scratch script builds the names that `run.js` and `drag.js` import, as `runtimeText` builds them, with one more module each time in the order `dsl`, `core`, `draw`, `rows`.
The bytes of a module are the increase of the minified text. The rest of the run-time text is `run.js` and `drag.js`.
The kit test `source: the run-time text is at most 46500 bytes` held the bar. Task 11b-1 changed the bar to 48500 bytes (see "Look vocabulary").
Each source is 5512 bytes smaller than at `850c5e3`. The rule of an open after a drag added 48 bytes. The budget table below is from `0b18408`, before these 48 bytes.

### Budget of each compiled plan

The kit test `budget: each saved plan is inside each limit` (`kit/test/budget.test.js`) prints this table.
It measures the 9 saved plans, the 8 test plans, and the 5 fixtures that compile.
The fixtures `catalog`, `dialogue-game`, `dialogue-materials`, and `dialogue-rover` do not compile, and the test skips them.

- source: the UTF-8 bytes of the source (`SandboxContract.MAX_SOURCE_BYTES`). The test fails at 60000 bytes or more.
- definition, ui, and state: the bytes as org.json writes them (`ToolSpec.MAX_DEFINITION_BYTES`, `ToolUiCatalog.MAX_UI_BYTES`, `ToolSpec.validateState`).
- nodes: each node of the ui, also each node of a row template (`ToolUiCatalog.MAX_NODES`).
- fields: the top-level fields of the state schema (`ToolValueSchema`).
- commands, pairs, chars, and bytes: the drawing of the open reply over all its surfaces (`GraphicsContract.frame`). A plan with no surface shows 2 bytes: the text `{}`.

| Plan | Source | Definition | UI | Nodes | State | Fields | Commands | Pairs | Chars | Bytes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Limit | 65536 (bar 60000) | 262144 | 65536 | 512 | 49152 | 40 | 512 | 2048 | 2048 | 32768 |
| `plans/tea.plan` | 45092 | 47759 | 541 | 5 | 20 | 1 | 0 | 0 | 0 | 2 |
| `plans/lesson.plan` | 48247 | 53822 | 1965 | 22 | 688 | 7 | 5 | 5 | 5 | 450 |
| `plans/calc.plan` | 45411 | 48319 | 623 | 8 | 83 | 4 | 0 | 0 | 0 | 2 |
| `plans/timer-rounds.plan` | 46698 | 50842 | 1190 | 17 | 282 | 4 | 2 | 2 | 0 | 201 |
| `plans/list.plan` | 46159 | 50404 | 1172 | 15 | 494 | 5 | 0 | 0 | 0 | 2 |
| `plans/section.plan` | 46543 | 51970 | 1691 | 24 | 1019 | 4 | 0 | 0 | 0 | 2 |
| `plans/quiz.plan` | 45791 | 49562 | 895 | 14 | 370 | 7 | 0 | 0 | 0 | 2 |
| `plans/model3d.plan` | 47563 | 51694 | 1143 | 17 | 301 | 3 | 52 | 253 | 0 | 6447 |
| `plans/tabs-chart.plan` | 46777 | 52135 | 2252 | 22 | 284 | 7 | 0 | 0 | 0 | 2 |
| `test/rich.plan` | 46854 | 62351 | 2473 | 24 | 10281 | 5 | 0 | 0 | 0 | 2 |
| `test/nodes.plan` | 47039 | 53414 | 3020 | 45 | 450 | 12 | 0 | 0 | 0 | 2 |
| `test/lists.plan` | 49527 | 59137 | 4657 | 54 | 1247 | 12 | 0 | 0 | 0 | 2 |
| `test/shapes.plan` | 46992 | 51349 | 1061 | 15 | 375 | 7 | 14 | 24 | 7 | 1226 |
| `test/bytes.plan` | 45067 | 47545 | 349 | 5 | 17 | 2 | 2 | 3 | 1 | 224 |
| `test/free.plan` | 45601 | 49703 | 1174 | 19 | 426 | 5 | 0 | 0 | 0 | 2 |
| `test/cards.plan` | 51476 | 66766 | 5571 | 73 | 5050 | 18 | 0 | 0 | 0 | 2 |
| `test/model.plan` | 45731 | 48962 | 720 | 12 | 96 | 3 | 12 | 51 | 0 | 1394 |
| `test/fixtures/price-filter.plan` | 46037 | 51349 | 1196 | 14 | 1606 | 7 | 0 | 0 | 0 | 2 |
| `test/fixtures/water-ko.plan` | 45310 | 48198 | 654 | 10 | 62 | 2 | 0 | 0 | 0 | 2 |
| `test/fixtures/habits.plan` | 45843 | 49619 | 1097 | 15 | 261 | 4 | 0 | 0 | 0 | 2 |
| `test/fixtures/units-pl.plan` | 46067 | 49634 | 1036 | 11 | 155 | 4 | 0 | 0 | 0 | 2 |
| `test/fixtures/shopping.plan` | 45969 | 50102 | 1118 | 15 | 463 | 5 | 0 | 0 | 0 | 2 |

The largest source is `test/cards.plan` with 51476 bytes, 8524 bytes under the bar. The largest saved plan is `plans/lesson.plan` with 48247 bytes.
The state column is the start state. The state of a list that is full to its row count is in the next table.

### Time of each saved plan

The device test `eachPlanAnswersInTime` does these steps for each saved plan:

1. Compile the plan, and run `ToolSpec.parse` and the smoke step.
2. Fill each list of rows: the start rows first, then copies of them in turn with new ids.
   A plan with an add (`list`, `section`, `tabs-chart`) gets each list to `maxItems - 1`. Each other plan gets each list to `maxItems`.
   The list of `tabs-chart.plan` starts empty, so the add steps of `tabsChartPassesParseAndSmoke` give its first row.
3. Send `open` through `executeEvent`, which checks the state and the reply.
4. For a plan with an add, send the draft steps through `executeEvent`. Time the add five times through `SandboxClient.execute`, and assert that its reply changes the state.
   Then send the add through `executeEvent`. Each list then has `maxItems` rows.
5. Send `open` again, and assert that each list has `maxItems` rows. Time `open` five times through `SandboxClient.execute`.
6. Run the smoke step again from the full state, and keep each action event that it sends.
7. Keep each of these actions that `ToolUiCatalog.validateAction` takes on the open screen. Time each one five times on the full state.
8. For an action that shows after one earlier event, send that event through `executeEvent`, time the action five times, and assert that its reply changes the state.
   Then send the action through `executeEvent`. These are `timer_pause` and `timer_reset` after `timer_start`, and `lesson_back` after `lesson_next`.
9. Take the slowest action whose reply changes the state, the add and the actions of step 8 included. Send it once more through `executeEvent`.
10. For a plan with a surface, send the start event and five surface events through one retained `GraphicsSession`.
11. Assert the set of action ids of the open screen that no timed event has: `demleme_timer` for `tea`, and no action id for each other plan.

The limits are 250 ms for `open` and for each timed action, also an action whose reply keeps the state, and 50 ms for a surface event.
Alphy stops an evaluation at 500 ms (`SandboxContract.EVALUATION_MS`), and a frame, pointer, or timer event at 100 ms (`GraphicsContract.EVALUATION_MS`).
The medians of the run `appfile-sizes-times-round`:

| Plan | Open (ms) | Slowest action timed (ms) | Action | Add to `maxItems` (ms) | After one earlier event (ms) | Surface event (ms) | State bytes, full | Actions: change state / on the open screen / smoke | Not timed |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `calc` | 9 | 10 | `bill_field` | none | none | none | 83 | 6 / 9 / 9 | none |
| `list` | 18 | 18 | `prodotti_add` | 18 | none | none | 12530 | 6 / 10 / 11 | none |
| `quiz` | 16 | 16 | `revealButton` | none | none | none | 7322 | 3 / 3 / 3 | none |
| `tabs-chart` | 17 | 19 | `gastos_new_importe` | 18 | none | none | 7498 | 11 / 17 / 18 | none |
| `timer-rounds` | 10 | 12 | `timer_reset` | none | `timer_pause` 11, `timer_reset` 12 | 3 (`timer_bar`) | 282 | 1 / 1 / 3 | none |
| `tea` | 13 | 12 | `cay_field` | none | none | none | 20 | 1 / 2 / 2 | `demleme_timer` |
| `section` | 32 | 36 | `habits_remove` | 32 | none | none | 31652 | 17 / 32 / 33 | none |
| `lesson` | 15 | 16 | `lesson_next` | none | `lesson_back` 15 | 3 (`anim`) | 688 | 7 / 9 / 10 | none |
| `model3d` | 19 | 20 | `rover_part_camera` | none | none | 11 (pointer move) | 301 | 6 / 6 / 6 | none |

The slowest time is 36 ms for an action and 11 ms for a surface event, so no event needed a change for speed.
The actions of one plan differ by a few ms, so the name of the slowest action changes from run to run.
In the run `appfile-sizes-times-7`, the slowest action took 42 ms (`section`). Its actions took 37 to 42 ms at first and 32 to 35 ms later, for the same kind of row event.
In the run `appfile-sizes-times-6` on the same test, the times of `section.plan` rose from 32 to 41 to 57 ms in the middle of the plan, and its slowest action took 52 ms.
Assumption: other work on the host computer caused these changes. A run with nothing else on the host computer can test this assumption.
The full lists are 100 rows each: `prodotti` and `prodotti_list`, `cards`, `gastos`, and `habits` and `streak_list`.
The largest full state is 31652 bytes (`section.plan`), under the limit of 49152 bytes.

### Runs of Task 10

| Label | Revision | Observed | Test APK `lastUpdateTime` before and after |
| --- | --- | --- | --- |
| `appfile-sizes-times` | `43b673e+dirty` | failed: `full` read a plain field of the schema as an object ("Value number at bill") | `00:11:09`, `00:26:15` |
| `appfile-sizes-times-2` | `43b673e+dirty` | failed: the control check of `executeEvent` (`ToolEngine.kt:280`) refused a seed step of `tabs-chart.plan`. The seed had no tab step. With the tab step first, the seed passed | `00:26:15`, `00:27:35` |
| `appfile-sizes-times-3` | `43b673e+dirty` | passed, 24 tests, 72.1 s | `00:27:35`, `00:28:45` |
| `appfile-sizes-times-4` | `43b673e+dirty` | passed, 24 tests, 76.3 s. The log got the actions that keep the state | `00:28:45`, `00:30:26` |
| `appfile-sizes-times-5` | `43b673e+dirty` | passed, 24 tests, 73.9 s. The log got the actions that are not on the open screen. This test took its actions from the smoke step on the start state, so it did not time the adds or the row actions of `gastos` | `00:30:26`, `00:32:11` |
| `appfile-sizes-times-6` | `62c87b5+dirty` | passed, 24 tests, 102.1 s. The test times the adds, and takes its actions from a smoke step on the full state | `00:32:11`, `00:45:33` |
| `appfile-sizes-times-7` | `62c87b5+dirty`: the bundle and the device test of `0b18408`. Only this file changed after it | passed, 24 tests, 88.7 s. One helper builds each action event | `00:45:33`, `00:49:13` |
| `appfile-sizes-times-round` | `0b18408+dirty`: the bundle and the device test of the commit "End a drag at each open and time the actions that show after one event". Only this file changed after it | passed, 24 tests, 87.6 s. The open after a drag, and the three actions after one earlier event | `00:49:13`, `01:01:16` |

All times of the column are on 2026-11-19. No other session installed the test APK during these runs.
The time lines of `appfile-sizes-times-6`, `appfile-sizes-times-7`, and `appfile-sizes-times-round` are in `evidence/alphy-ui/<label>/logcat.txt` (from `adb -s emulator-5592 logcat -d -s AppFileProofTest`).

Commands:

```text
bun test kit
bun kit/build.js --check
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home python3 tools/compose_acceptance/run.py suite --serial emulator-5592 --label appfile-sizes-times-round com.alpha.assistant.tools.appfile.AppFileProofTest
```

### What the time test did not show

- No test timed an add on a list at `maxItems`. The open screen of the full state does not take `prodotti_add`, `habits_add`, or `gastos_add`.
  Assumption: an add is not active while its draft is empty. The test types a draft first, and then `executeEvent` takes the add.
- The foreground timer `demleme_timer` of `tea.plan` sends no event to the source: the host owns it. The smoke step and this test do not send it.
- The open screen of `tabs-chart.plan` is its second tab, "Añadir", because the add steps choose that tab. The list and its row actions are on that tab.
- The 100 rows of `gastos` are copies of one added row and the add, so the chart of `tabs-chart.plan` shows one category.
- Before the add, the test fills a list that the run-time writes as a view (`prodotti_list`, `streak_list`) with copies of its own rows.
  After the add, the view has `maxItems` rows, so the run-time wrote it again. The test does not compare the view with its list.
- The times come from the emulator, not from a phone.

## Look

The device class `AppFileLookTest` shows each compiled plan as a saved app in the real shell (`ShellFixture`, `ShellRobot`, `ToolStore.saveTool`).
It compiles each plan with `parsedAndSmoked` of `AppFileProofTest`, which gives the spec the language of the compile result (`ToolSpec.withAppLanguage`). The shell makes no model call.

- `picturesOfEachPlan` opens each of the nine saved plans and each look fixture of `kit/build.js` (`LOOK_FIXTURES`: five in Task 11a, seven since Task 11b-1, twelve since Task 11b-2a). Since Task 11b-2a, it also opens the spec of each of the five baseline apps (section "Look with parts").
  For each app, it waits for the title, the refine action, and the reply to the open event. Then it takes a screenshot of the first screen.
  For a plan with a surface (`timer-rounds`, `lesson`, `model3d`), it waits until the inside of each canvas holds more than one color.
  It takes the pictures in the light theme and in the dark theme, and it sets the night mode of the emulator back in a `finally` block.
- `theHostWordsOfAnAppAreInTheLanguageOfTheApp` opens `tea.plan` (language `tr`). The screen shows `R.string.foreground_timer_start_en` from the Turkish resources, and not the Russian `R.string.foreground_timer_start`.
- `aTapOnARowControlChangesTheRow` taps the checkbox of the first row of `list.plan` through the accessibility node.
  The stored state then has `preso` true only for the row `r2`, `daComprare_text` is "2", and the screen shows the first checkbox checked and "2".
- `theLapButtonMovesTheHandInTheDrawnApp` presses "Один круг" (`lap_button`, the control with the `animate` action) of `lesson.plan`.
  The stored `anim` change starts within 10 s. Within 10 s more, `angle` is 360 and each entry of `anim` has `end` 0 (since Task 11b-2a; before, only `anim.angle.end`). The test sends no timer event and calls no `execute`.

The numbers of the table come from `score_app(definition, [])` of `tools/agent_corpus/visual_score.py` on the compiled definition:

```text
bun kit/look.js
```

The last column is what the pictures of the run `appfile-look-before-3` show. "Dark" marks a defect of the dark theme only.

| Plan | Hero | Typed fields, first screen | Typed fields in rows | Seen in the picture |
| --- | --- | --- | --- | --- |
| `calc` | 1 | 2 | 0 | No defect. A large empty area under the content. |
| `lesson` | 1 | 1 | 0 | The first text "Alphy Learn" is cut at its left edge. The eyebrow shows two times: "Исследуем · 1 / 3", then "Исследуем: 1 / 3" beside the dots. The orbit canvas has large empty bands above and under the orbit, so the lesson card starts at the bottom of the first screen. Dark: the orbit circle and the inactive dots have low contrast. |
| `list` | 1 | 2 | 0 | The English word "Add" in an Italian app: the compiled ui has it as the text of the add button. Each row checkbox shows the internal name "prodotti preso" as its label. The texts of a row are at its top, but the trash icon and the checkbox are at its vertical center. The aisle text "Latticini" is smaller than the other texts. The "Add" button is not aligned with the two text fields. |
| `model3d` | 1 | 0 | 0 | The first text "Мастерская" is cut at its left edge. Two arrow icon buttons have no visible text. Dark: the light model stage is a bright panel in the dark card. |
| `quiz` | 1 | 0 | 0 | Three filled buttons in one group ("答えを見る", "覚えた", "まだ"). A large empty area under the content. |
| `section` | 0 | 1 | 0 | No hero. Each row is a card in a card, with low contrast in the dark theme. The trash icon is not aligned with the row title. The first screen shows only two rows. |
| `tabs-chart` | 1 | 0 | 0 | The chart title "Gastos por categoría" shows two times. The Russian host text "Нет данных для графика" in a Spanish app, also with the language set (see below). A large empty area under the content. |
| `tea` | 0 | 0 | 0 | No hero. The first text "Çay türü" is cut at its left edge. A large empty area under the content. The host texts of the timer are Turkish: "Süre dolunca telefon titreşir" and "Zamanlayıcıyı başlat". |
| `timer-rounds` | 1 | 0 | 0 | The ring heading "Runden geschafft" shows two times. The bar has an empty band above and under its track (see "Known look points"). The track has low contrast in both themes. |
| `water-ko` | 1 | 0 | 0 | The ring heading "오늘 마신 물 (ml)" shows two times. The minus and the plus of the counter are small bare glyphs at the left. A large empty area under the content. |
| `units-pl` | 1 | 1 | 0 | Four secondary metrics in a plain column, not in a grid. |
| `shopping` | 1 | 2 | 0 | Each row checkbox shows the internal name "items done" as its label. The trash icon is at a different x in each row: 481, 449, and 490 px of 1080 px. The aisle texts are smaller than the other texts, and the texts of a row are at its top. The "Add" button is not aligned with the two text fields. |
| `habits` | 1 | 1 | 0 | The ring heading "Done today" shows two times. Each row checkbox shows the internal name "habits done" as its label. The "Add" button is not aligned with the text field. |
| `price-filter` | 1 | 2 | 0 | The raw option words "all", "asc", and "desc" show as the labels of the choices. |

With the language of each app set, these host texts are still not in the language of the app:

- "Нет данных для графика" in `tabs-chart` (Spanish): `R.string.tool_chart_empty` (`app/src/main/res/values/strings.xml`).
  `NativeToolUi.kt` reads it with `stringResource`, not with `miniAppString`, and it has no `_en` string or translation.
- "Изменить" in each app: `R.string.tool_refine`, the refine action of the shell bar. It is a text of the shell, not of the app.

The pictures of `appfile-look-before-3` and `appfile-look-before-2` are the same below the status bar, except the timer card of `tea` in both themes.

### Known look points

| Point | Plan | Result |
| --- | --- | --- |
| The English defaults "Add" and "Nothing here yet" | `list` | "Add": seen, also with the language `it` set. The compiled ui has it as the text of the add button. "Nothing here yet": not seen, because the list has three rows. The compiled ui has it as `emptyText`. |
| The eyebrow beside the dots shows two times | `lesson` | Seen. |
| The chart title shows a second time under its Section title | `tabs-chart` | Seen. |
| The heading beside a ring shows two times | `timer-rounds`, `water-ko`, `habits` | Seen in each of the three plans. |
| The size and the place of the timer bar | `timer-rounds` | The track spans x 95 to 984 px and y 1580 to 1625 px of the 1080 x 2400 px picture. The 5 to 1 canvas is about 890 x 178 px, so an empty band of about 65 px is above and under the track. The track color is (230, 212, 208) on (255, 233, 228) in the light theme and (60, 48, 45) on (43, 28, 24) in the dark theme. |
| The number format of `Metric("覚えた", $/learned)` | `quiz` | `覚えた` and `まだ` compile to plain state fields (`bind` `learned` and `notYet`). Both show "0", so the picture does not show a difference between the two formats. |

### Runs of Task 11a

| Label | Revision | Observed | Test APK `lastUpdateTime` before and after |
| --- | --- | --- | --- |
| `appfile-look-assert` | `f1865a5+dirty` (the tree of `80b27ef`) | passed, 24 tests, 83.8 s: `AppFileProofTest` with the new assertions | `01:01:16`, `01:15:55` |
| `appfile-look-1` | `80b27ef+dirty` | passed, `AppFileLookTest`, 3 tests, 54.9 s | `01:15:55`, `01:21:21` |
| `appfile-look-before` | `80b27ef+dirty` | passed, 27 tests, 131.4 s. Not used: the light picture of `calc` shows the open in the middle of its change from the chat, with the busy row "Выполняем действие…". The open now waits for the reply to the open event, and each screenshot waits for three frames | `01:21:21`, `01:22:51` |
| `appfile-look-before-2` | `80b27ef+dirty`: the tree of the commit "Show each compiled plan in the shell and take its pictures" | passed, 27 tests (`AppFileProofTest` and `AppFileLookTest`), 140.4 s. Not used: each saved app had no app language, so `tea` and `tabs-chart` showed Russian host texts | `01:22:51`, `01:26:22` |
| `appfile-look-language-red` | `0bb9875+dirty`: the new test only | failed as expected: `theHostWordsOfAnAppAreInTheLanguageOfTheApp` did not find the Turkish text within 10 s | `01:26:22`, `01:40:40` |
| `appfile-look-before-3` | `0bb9875+dirty`: the tree of the commit "Give each compiled plan in the device tests the language of its app" | passed, 28 tests (`AppFileProofTest` and `AppFileLookTest`), 141.9 s | `01:40:40`, `01:41:21` |

All times of the column are on 2026-11-19. The runner installed the test APK at each "after" time. No other session installed it during these runs.
The pictures are in `evidence/alphy-ui/appfile-look-before-3/light/` and `evidence/alphy-ui/appfile-look-before-3/dark/`, one `<plan>.png` for each plan.
The contact sheets are `evidence/alphy-ui/appfile-look-before-3/contact-light.png` and `evidence/alphy-ui/appfile-look-before-3/contact-dark.png`. Git does not keep these files.
Before this task, `kit/build.js` did not copy the five look fixtures to the test assets. It now copies them.

Command:

```text
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home python3 tools/compose_acceptance/run.py suite --serial emulator-5592 --label appfile-look-before-3 com.alpha.assistant.tools.appfile.AppFileProofTest com.alpha.assistant.tools.appfile.AppFileLookTest
```

## Look vocabulary

Task 11b-1 gave the components the look options of the host (`kit/README.md`, section "Look options").
The compile step checks each value, and the run-time puts it into the Alphy node as it is.

| Measure | Before (`e2e9eac`) | After |
| --- | --- | --- |
| Run-time text | 44626 bytes | 45417 bytes (bar 48500) |
| Largest source | `test/cards.plan`, 51524 bytes | `test/cards.plan`, 52296 bytes (bar 60000) |
| Kit tests | 224 pass | 242 pass |

The run-time text holds no icon list and no fault text. The kit test `source: the run-time text is at most 48500 bytes` holds the bar.

The cut first letter: the root column of each app now has a medium padding.
"Alphy Learn" (`lesson`), "Мастерская" (`model3d`), and "Çay türü" (`tea`) are whole in the pictures (`evidence/alphy-ui/appfile-look-vocabulary/first-letters.png`).
The assumption of the gap analysis is true: the clip of the rounded corner of the app cut the letter, and the padding moves the text inside it.

Two requests of the corpus with components only, in the tree of their baseline apps:

| Plan | Hero | Typed fields, first screen | Typed fields in rows | Baseline (same metric) |
| --- | --- | --- | --- | --- |
| `look-water-ko` | 1 | 0 | 0 | `showcase-lang-water-ko`: 1, 0, 0 |
| `look-shopping-it` | 1 | 1 | 0 | `showcase-lang-shopping-it`: 1, 1, 0 |

The number 1234.5 shows as "1234.5" both from a state value (`Metric("Plain", $/n)`) and from a computed value (`Metric("Computed", $/n * 1)`).
The kit has no number format with digit groups: "남은 양" shows "2000 ml", and the baseline shows "2,000 ml".

The hero cards of the baseline pictures come from the host at `30d43aa`. The host of this branch has `03c6e58` "Stop repeating titles and calm the hero cards".
That host puts a large emoji at the end of the texts that follow it (`NativeToolUi.kt` `heroEmojiSide`), and it gives a hero card the secondary container color.
So the emoji of `look-water-ko` stands beside the metric, not above it, and the hero colors differ from the baseline. The plan gives the same tree as the baseline.

### Runs of Task 11b-1

| Label | Revision | Observed | Test APK `lastUpdateTime` before and after |
| --- | --- | --- | --- |
| `appfile-look-vocabulary` | `0314cc4` | passed, 29 tests (`AppFileProofTest` and `AppFileLookTest`), 137.2 s. Not used for the pair of `look-shopping-it`: the quantity of a row had the number emphasis, a display size, and "1 kg" took two lines | `01:41:21`, `02:28:22` |
| `appfile-look-vocabulary-2` | `27fb5e3+dirty` (only `kit/README.md` and this file) | passed, 29 tests, 137.0 s. Not used for the pair of `look-water-ko`: the plan had no manual entry sheet | `02:28:22`, `02:33:38` |
| `appfile-look-vocabulary-3` | `60a1ed6+dirty` (only `kit/README.md` and this file) | passed, 29 tests, 141.0 s. Not used for the pair of `look-shopping-it`: the row had the default gap of the host | `02:33:38`, `02:37:13` |
| `appfile-look-vocabulary-4` | `f4cb1ba+dirty` (only `kit/README.md` and this file) | passed, 29 tests, 138.1 s. The pictures and the pairs of this task | `02:37:13`, `02:41:59` |

All times are on 2026-11-19. The runner installed the test APK at each "after" time. No other session installed it during these runs.
The pictures are in `evidence/alphy-ui/appfile-look-vocabulary-4/light/` and `dark/`, with the contact sheets `contact-light.png` and `contact-dark.png`.
The pairs beside the baseline are `pair-water-ko.png` and `pair-shopping-it.png` in the same folder. Git does not keep these files.
Below the status bar, the pictures of the 14 earlier plans are the same in the runs 1, 3, and 4.

Command:

```text
JAVA_HOME=/opt/homebrew/opt/openjdk@17 python3 tools/compose_acceptance/run.py suite --serial emulator-5592 --label appfile-look-vocabulary-4 com.alpha.assistant.tools.appfile.AppFileProofTest com.alpha.assistant.tools.appfile.AppFileLookTest
```

### Where the two pairs are still worse than the baseline

| Plan | Difference | Cause |
| --- | --- | --- |
| `look-water-ko` | The emoji stands beside the metric, and the hero color differs. | Host revision: the baseline pictures come from `30d43aa`, this branch has `03c6e58`. |
| `look-water-ko` | "남은 양" shows "2000 ml", the baseline shows "2,000 ml". | Missing option: the kit has no number format with digit groups (C.5). |
| `look-water-ko` | The success banner has no title "목표 달성". | Missing option: `Notice` takes one text. |
| `look-water-ko` | The grid "Plain" and "Computed" is not in the baseline. | Choice of the plan: D.3 requires it. |
| `look-shopping-it` | The button "Aggiungi" is grey on the first screen. | Choice of the plan: `enabled=` keeps the smoke step from a control that changes nothing (the pattern of the entries part). |
| `look-shopping-it` | The list has no caption "Prodotti". | Host rule, corrected in Task 11b-2a: the only list under a shown section title takes its name from that title and draws no caption of its own (`namedByTitle`, `NativeToolUi.kt` 830 to 836). |
| `look-shopping-it` | The list starts with three rows, so the empty text does not show. The hero shows "2" and "3 prodotti in lista". | Choice of the plan: each check then runs the row of a list. |
| `look-shopping-it` | "Frutta e verdura" breaks into three lines in a row. | Host limit: the weighted column gets the width that the quantity, the label "Preso", and the trash leave. The host does not shorten the text. |
| `look-shopping-it` | A row has the trash, the baseline row has a pencil and an edit sheet. | Choice of the plan: the row of B.11. An edit sheet of a row needs a selected-row value. |

## Look with parts

Task 11b-2a gave the parts `entries`, `input`, `figure`, and `counter` the look of the baseline apps (`kit/README.md`, section "Parts").
The captions of the parts are in the language of the app, and a figure prints its numbers with the signs of that language.

An agent with the kit guide wrote the five plans below for the five requests of the corpus (`tools/agent_corpus/showcase.json`).
The model of the app did not write them. They show what the kit can show. The next phase measures the plans that a model writes.

| Plan | Request | Lines | Bytes | Hero | Typed fields, first screen | Typed fields in rows | Baseline (same metric) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `mock-water-ko` | `showcase-lang-water-ko` | 6 | 816 | 1 | 0 | 0 | 1, 0, 0 |
| `mock-units-pl` | `showcase-lang-units-pl` | 10 | 1354 | 1 | 1 | 0 | 1, 1, 0 |
| `mock-shopping-it` | `showcase-lang-shopping-it` | 4 | 1062 | 1 | 1 | 0 | 1, 1, 0 |
| `mock-habits-uk` | `showcase-lang-habits-uk` | 4 | 1363 | 1 | 0 | 0 | 1, 0, 0 |
| `mock-price-filter` | `showcase-ui-price-filter` | 6 | 2085 | 1 | 0 | 0 | 1, 0, 0 |

A line is a line of the plan that is not a comment or empty. The byte count is the whole file.
The numbers of a plan come from `bun kit/look.js`. The numbers of a baseline come from `score_app` on its spec.

| Measure | Before (`733bc91`) | After (`e8af9d2`) |
| --- | --- | --- |
| Run-time text | 45417 bytes | 46227 bytes (bar 48500) |
| Largest source | `test/cards.plan`, 52296 bytes | `test/cards.plan`, 53275 bytes (bar 60000) |
| Kit tests | 242 pass | 274 pass |

### The baseline on the same host

`AppFileLookTest` now shows the spec of each of the five baseline apps (`app/src/androidTest/assets/appfile/baseline/<case>.json`, from `final-30d43aa`) as a saved app of the same host.
It takes their pictures with the same steps as for a compiled plan, in both themes. A baseline spec keeps the host field `appLanguage`, so the test reads it with `ToolSpec.parsePersisted`.

On the same host, the hero card of each plan has the color of the hero card of its baseline.
The assumption of Task 11b-1 is true: the old pictures `review.png` came from the host at `30d43aa`, and the difference of the color came from that revision.
The side of the large emoji now depends only on the tree of the card:

- `water-ko` and `units-pl`: the emoji is first in the card in both apps, and the host puts it at the end of the texts (`NativeToolUi.kt` `heroEmojiSide`).
- `shopping-it`: the baseline card holds a `Row` of the emoji and a column, so its emoji is at the start. The plan puts it at the end.
- `price-filter`: the baseline card has `align: "center"`, so its emoji is above the texts. The plan puts it at the end.

The shopping list starts empty, as in the baseline. The test also types "Latte", presses the apply button of the field and "Aggiungi", and takes the picture `mock-shopping-it-added.png` with the new row.

### Runs of Task 11b-2a

| Label | Revision | Observed | Test APK `lastUpdateTime` before and after |
| --- | --- | --- | --- |
| `appfile-look-parts-try1` | `de20585+dirty` (the plans and the baselines) | failed: `ToolSpec.parse` refused the field `appLanguage` of a baseline spec | `04:22:14`, `04:27:20` |
| `appfile-look-parts-try2` | `de20585+dirty` | failed: the IME action of "Prodotto" did not apply the typed text, so the test now presses the apply button | `04:27:20`, `04:30:53` |
| `appfile-look-parts-try3` | `9f435e9` tree | passed, 32 tests (`AppFileProofTest` and `AppFileLookTest`), 159.0 s. The pictures of the first round | `04:30:53`, `04:35:18` |
| `appfile-look-parts-try4` | `c022bdb+dirty` (the tree of `e8af9d2`) | passed, 32 tests, 156.7 s. The pictures of the second round | `04:35:18`, `04:46:51` |
| `appfile-look-parts` | `e8af9d2` | passed, 32 tests, 161.2 s. The pictures of this section | `04:46:51`, `04:50:49` |

All times are on 2026-11-19. The runner installed the test APK at each "after" time. No other session installed it during these runs.
The runs of the single items of the task (`appfile-numbers`, `appfile-entries`, `appfile-inputs`, `appfile-figures`, `appfile-counter`, and their red runs) passed or failed as expected before these runs.
The pictures are in `evidence/alphy-ui/appfile-look-parts/light/` and `dark/`, with the contact sheets `contact-light.png` and `contact-dark.png`.
The pair sheet `pairs-light.png` in the same folder shows each baseline app at the left and its plan at the right. Git does not keep these files.

The first round of pictures changed the parts and the plans:

- A row with many check marks was a card in the card that the host draws for each row of a list. The row is now a column, and "Читати 20 сторінок" is two lines, not three.
- In a row with one text field, a choice that the row does not change is now the muted line under the title, not a label beside it.
- `figure` takes `unit=` ("0 ml", "2,000 ml") and `goalText=`, the caption of the bar ("달성률: 0%").
- `mock-units-pl` has step buttons on its quantity, and `mock-price-filter` shows the price as a badge "$260".

Command:

```text
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home python3 tools/compose_acceptance/run.py suite --serial emulator-5592 --label appfile-look-parts com.alpha.assistant.tools.appfile.AppFileProofTest com.alpha.assistant.tools.appfile.AppFileLookTest
```

### Where the plans with parts are still worse than the baseline

| Plan | Difference | Cause |
| --- | --- | --- |
| `mock-units-pl` | The baseline shows each conversion as a large value on a tinted row under the heading "Przeliczenia". The plan shows two plain metrics in a grid, with smaller values, and one row with a large value. | The layout rule of the figures (Task 11b-2a, E.2), and a host limit: a plain `Metric` value is `headlineSmall` (24 sp), and no `Text` emphasis has that size (`title` 22 sp, `headline` 28 sp, `number` 36 sp; `NativeToolUi.kt` `textStyle`). The row keeps `number`, the size of the baseline rows. |
| `mock-shopping-it` | The fields of the add form have no icon, and the form has no heading "Aggiungi prodotto". | Missing option: the add form of `entries` gives its fields no icon. The heading: choice of the plan. |
| `mock-shopping-it` | A typed product goes into the state only after the apply button of the field ("Applica: Prodotto"). The baseline field is a live input, so its add button works at once. | The design of the kit: a `TextField` is an apply input of the host (`docs/superpowers/plans/2026-11-18-app-file-kit.md`, the node table). |
| `mock-shopping-it` | No filter "Mostra", no order "Ordina per", and no button "Svuota comprati". | Choice of the plan: the list sorts by aisle with `sort=$corsia`. |
| `mock-habits-uk` | "Пити воду" breaks into two lines, and "Прогулянка 30 хв" has a smaller text. In the baseline both are wider. | Host limit: each text cell of a row without a weight shares the free width with the title (`NativeToolUi.kt` 946 to 947). The two `show=` values are badges with their own padding, and the baseline has one plain text and one badge. |
| `mock-habits-uk` | The streak badge is grey, and the baseline badge has the accent tone. The ring shows "3 / 21", and the baseline shows "3 з 21". The plan has no button "Редагувати назви". | Missing options: a `show=` value has no tone, and the `progress` part writes "/" in each language. The button: choice of the plan (`add=false`). |
| `mock-price-filter` | The hero shows "12", and the baseline shows "12 of 12". The inputs have no heading "Filters", and the plan has no button "Reset filters". | Missing option: the hero count of `entries` has no total. The heading and the button: choices of the plan. |
| `mock-water-ko` | The baseline has a section "기록 관리" with a manual entry and a reset. The plan has the reset beside the cup button and no manual entry. | Choice of the plan: the request asks for the cup button and the progress only. |

A long detail line of a bike ("2017 · One size · Fair") breaks into two lines in the plan and in the baseline, for the same host limit.
