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
The commits are `1a27230` (the run-time text without the compile-only texts), `43b673e` (the drag of a model in `kit/src/drag.js`), and the commit "Check the size and the time of each compiled plan".

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
| `run.js` (now with `drag.js`) | 15496 | 12876 | -2620 |
| Run-time text | 50138 | 44578 | -5560 |

Method of the split: a scratch script builds the names that `run.js` and `drag.js` import, as `runtimeText` builds them, with one more module each time in the order `dsl`, `core`, `draw`, `rows`.
The bytes of a module are the increase of the minified text. The rest of the run-time text is `run.js` and `drag.js`.
The kit test `source: the run-time text is at most 46500 bytes` holds the bar.
Each source is 5560 bytes smaller than at `850c5e3`.

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
8. Take the slowest action whose reply changes the state, the add included. Send it once more through `executeEvent`.
9. For a plan with a surface, send the start event and five surface events through one retained `GraphicsSession`.
10. Log each action id of the open screen that no timed event has.

The limits are 250 ms for `open` and an action, and 50 ms for a surface event.
Alphy stops an evaluation at 500 ms (`SandboxContract.EVALUATION_MS`), and a frame, pointer, or timer event at 100 ms (`GraphicsContract.EVALUATION_MS`).
The medians of the run `appfile-sizes-times-7`:

| Plan | Open (ms) | Slowest action timed (ms) | Action | Add to `maxItems` (ms) | Surface event (ms) | State bytes, full | Actions: change state / on the open screen / smoke | Not timed |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `calc` | 9 | 10 | `bill_field` | none | none | 83 | 6 / 9 / 9 | none |
| `list` | 17 | 20 | `prodotti_remove` | 17 | none | 12530 | 6 / 10 / 11 | none |
| `quiz` | 16 | 17 | `learnedButton` | none | none | 7322 | 3 / 3 / 3 | none |
| `tabs-chart` | 18 | 19 | `gastos_add` | 19 | none | 7498 | 11 / 17 / 18 | none |
| `timer-rounds` | 11 | 11 | `timer_start` | none | 2 (`timer_bar`) | 282 | 1 / 1 / 3 | `timer_pause`, `timer_reset` |
| `tea` | 13 | 13 | `cay_field` | none | none | 20 | 1 / 2 / 2 | `demleme_timer` |
| `section` | 34 | 42 | `habits_remove` | 34 | none | 31652 | 17 / 32 / 33 | none |
| `lesson` | 16 | 16 | `ask_opt1` | none | 3 (`anim`) | 688 | 7 / 9 / 10 | `lesson_back` |
| `model3d` | 20 | 20 | `rover_part_camera` | none | 11 (pointer move) | 301 | 6 / 6 / 6 | none |

The slowest time is 42 ms for an action and 11 ms for a surface event, so no event needed a change for speed.
The actions of one plan differ by a few ms, so the name of the slowest action changes from run to run.
In this run, the actions of `section.plan` took 37 to 42 ms at first and 32 to 35 ms later, for the same kind of row event.
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
| `appfile-sizes-times-7` | `62c87b5+dirty`: the bundle and the device test of the commit "Check the size and the time of each compiled plan". Only this file changed after it | passed, 24 tests, 88.7 s. One helper builds each action event | `00:45:33`, `00:49:13` |

All times of the column are on 2026-11-19. No other session installed the test APK during these runs.
The time lines of `appfile-sizes-times-6` and `appfile-sizes-times-7` are in `evidence/alphy-ui/<label>/logcat.txt` (from `adb -s emulator-5592 logcat -d -s AppFileProofTest`).

Commands:

```text
bun test kit
bun kit/build.js --check
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home python3 tools/compose_acceptance/run.py suite --serial emulator-5592 --label appfile-sizes-times-7 com.alpha.assistant.tools.appfile.AppFileProofTest
```

### What the time test did not show

- No test timed an add on a list at `maxItems`. The open screen of the full state does not take `prodotti_add`, `habits_add`, or `gastos_add`.
  Assumption: an add is not active while its draft is empty. The test types a draft first, and then `executeEvent` takes the add.
- The open screen does not take `timer_pause` and `timer_reset` of `timer-rounds.plan`, or `lesson_back` of `lesson.plan`, so the test did not time them.
- The foreground timer `demleme_timer` of `tea.plan` sends no event to the source: the host owns it. The smoke step and this test do not send it.
- The open screen of `tabs-chart.plan` is its second tab, "Añadir", because the add steps choose that tab. The list and its row actions are on that tab.
- The 100 rows of `gastos` are copies of one added row and the add, so the chart of `tabs-chart.plan` shows one category.
- Before the add, the test fills a list that the run-time writes as a view (`prodotti_list`, `streak_list`) with copies of its own rows.
  After the add, the view has `maxItems` rows, so the run-time wrote it again. The test does not compare the view with its list.
- The times come from the emulator, not from a phone.
