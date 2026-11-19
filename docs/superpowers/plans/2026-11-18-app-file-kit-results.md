# App file kit (phase 1): results

This file records the device results of `docs/superpowers/plans/2026-11-18-app-file-kit.md`.
The section "Phase 1 proof" records Task 12: the whole proof on one revision, and the verdict of the stop rule of phase 1.
Each later section belongs to one task of the plan and keeps the numbers of that task. Where a number of a task differs from "Phase 1 proof", the number of "Phase 1 proof" is the state at `9bcd313`.

All runs used the emulator `emulator-5592` (AVD `AlphyAppFile`, API 36). The table or the command of each run names its test classes.
The `run.json` of each label is in `evidence/alphy-ui/<label>/`, which Git does not keep.

## Phase 1 proof

Task 12 ran the whole proof one time on `9bcd313`, with a clean tree.
`9bcd313` merges `origin/main` `80ca3d5` (the live voice mode) into this branch. No file changed on both sides.

### The run

| Command | Result |
| --- | --- |
| `bun test kit` | 320 pass, 0 fail, 1882 `expect()` calls in 21 files, 4.3 s |
| `bun kit/build.js --check` | exit 0 |
| `run.py suite`, label `appfile-phase1` | passed: 108 tests, 0 failures, 0 skipped. The runner took 198.4 s for the build, the install, and the tests. The tests took 189.3 s |

| Class | Tests | Result | Seconds |
| --- | --- | --- | --- |
| `com.alpha.assistant.tools.appfile.AppFileProofTest` | 29 | passed | 83.4 |
| `com.alpha.assistant.tools.appfile.AppFileLookTest` | 8 | passed | 99.0 |
| `com.alpha.assistant.sandbox.SandboxIsolationTest` | 10 | passed | 1.9 |
| `com.alpha.assistant.tools.AgentGenerationWorkflowTest` | 48 | passed | 4.9 |
| `com.alpha.assistant.voice.VoiceModeProtocolTest` | 13 | passed | 0.04 |

The seconds of a class come from the first `started` and the last `finished` line of the class in the log tag `TestRunner`. The log filter of the run kept only two other tags, and `device.log` is empty.
So a dump of the tag `TestRunner` after the run holds these lines: `evidence/alphy-ui/appfile-phase1/testrunner.txt`, 165 lines from 09:27:49, copied there in fix round 1 of Task 12.
The log buffer had lost the first lines of `AppFileProofTest`, so its seconds cannot be checked again. They are the rest of the 189.321 s: the four other classes took 105.906 s from their exact times, and 189.321 minus 105.906 is 83.415.
The rounded seconds of the table give 83.48 for the same difference.
The `run.json` names the revision `9bcd313beb826473dfa3cd0b61c4bef6d91cad75`, `dirty: false`, and the serial `emulator-5592`.
The test APK `lastUpdateTime` was `2026-11-19 09:03:45` before the run (the "after" time of `appfile-look-all-5`) and `2026-11-19 09:26:24` after it.
The run started at 09:26:17, installed the test APK at 09:26:24, and ended at 09:29:35. No other session installed the test APK.

The last three classes show that the paths of the app from before this plan still work on the merged tree: the sandbox, the agent loop with a scripted model, and the voice protocol of main.
`VoiceModeProtocolTest` tests the GPT-Live wire protocol with no network, no microphone, and no model.
The file `AgentGenerationWorkflowTest.kt` also holds the class `AgentGuideExampleTest` (3 tests). This run did not name that class.

Commands:

```text
bun test kit
bun kit/build.js --check
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home python3 tools/compose_acceptance/run.py suite --serial emulator-5592 --label appfile-phase1 com.alpha.assistant.tools.appfile.AppFileProofTest com.alpha.assistant.tools.appfile.AppFileLookTest com.alpha.assistant.sandbox.SandboxIsolationTest com.alpha.assistant.tools.AgentGenerationWorkflowTest com.alpha.assistant.voice.VoiceModeProtocolTest
```

During the run, `adb -s emulator-5592 logcat -T 1 -v time -s AppFileProofTest:I AppFileLookTest:I` wrote the time lines to `evidence/alphy-ui/appfile-phase1/logcat.txt`.
The runner does not copy the pictures. After the run, `adb pull` copied the folders `appfile-look`, `appfile-step-zero`, and `appfile-steps-kit` of the app's external files into `evidence/alphy-ui/appfile-phase1/`.

### The nine saved plans

Each saved plan compiled with the status `ok` and no fault, and passed `ToolSpec.parse` and the host smoke step on the device.
`parsedAndSmoked` asserts the three steps in the test of each plan (`calcPassesParseAndSmoke` and the others) and in `eachPlanAnswersInTime`.

| Plan | Language | Needs | Source (bytes) | Smoke events from the full state | Slowest action that changes the state (ms) | Live input (ms) | Hero, typed fields on the first screen, typed fields in rows |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `calc` | en | 5 | 47495 | 4 / 6 / 9 | 10 (`bill_field`) | `tip_field` 9, 9, 10 | 1, 2, 0 |
| `list` | it | 2 | 48306 | 4 / 7 / 11 | 18 (`prodotti_preso`) | `prodotti_new_nome` 18, 18. `prodotti_new_corsia` 17, 18 | 1, 2, 0 |
| `quiz` | ja | 0 | 47875 | 3 / 3 / 3 | 15 (`revealButton`) | none | 1, 0, 0 |
| `tabs-chart` | es | 4 | 48814 | 6 / 10 / 12 | 18 (`resumen_view`) | `gastos_new_concepto` 17, 17 | 1, 0, 0 |
| `timer-rounds` | de | 3 | 48887 | 1 / 1 / 3 | 10 (`timer_start`) | none | 1, 0, 0 |
| `tea` | tr | 2 | 47194 | 1 / 2 / 2 | 12 (`cay_field`) | none | 1, 0, 0 |
| `section` | uk | 2 | 49048 | 16 / 31 / 33 | 33 (`habits_remove`) | `habits_new_name` 33, 32 | 1, 1, 0 |
| `lesson` | ru | 6 | 50316 | 7 / 9 / 10 | 15 (`lesson_next`) | none | 1, 1, 0 |
| `model3d` | ru | 2 | 49951 | 6 / 6 / 6 | 19 (`rover_part_panel`) | none | 1, 0, 0 |

- Smoke events from the full state: the actions that change the state / the actions that the open screen takes / the action and live input events that the smoke step sent from the state with full lists. They come from the `time` line of `eachPlanAnswersInTime` in the log.
- `quiz.plan` writes its own component lines and has no need line.
- The look numbers come from `bun kit/look.js`. A scratch script compared each of the 62 pictures of this run with the same picture of `appfile-look-all-5`, below the status bar. It counts a pixel as changed when a channel differs by more than 8.
  In 61 pictures, 0.000 % of the pixels changed. `dark/look-guide-es-answered.png` has 33.3 % changed pixels: the screen scrolls by a different distance after the reveal of the notice (section "Look of each plan").
  So the column "Seen in the pictures" of "Look of each plan" holds at this head.

### Sizes at this head

The method is the method of "Sizes and times". The kit test `budget: each saved plan is inside each limit` printed this table at `9bcd313` (`bun test kit/test/budget.test.js`).
The column `Work` came in fix round 1 of Task 12, with no change of the kit sources. It is the kit's count of render work with each list full, not a count of the host (section "The stop rule").
It measures the 9 saved plans, the 9 test plans, and the 14 fixtures that compile. The fixtures `catalog`, `dialogue-game`, `dialogue-materials`, and `dialogue-rover` do not compile, and the test skips them.

| Plan | Source | Definition | UI | Nodes | State | Fields | Work | Commands | Pairs | Chars | Bytes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Limit | 65536 (bar 60000) | 262144 | 65536 | 512 | 49152 | 40 | 2048 | 512 | 2048 | 2048 | 32768 |
| `plans/tea.plan` | 47194 | 50011 | 577 | 5 | 20 | 1 | 8 | 0 | 0 | 0 | 2 |
| `plans/lesson.plan` | 50316 | 55898 | 1940 | 22 | 637 | 7 | 24 | 5 | 5 | 5 | 452 |
| `plans/calc.plan` | 47495 | 50551 | 661 | 8 | 83 | 4 | 8 | 0 | 0 | 0 | 2 |
| `plans/timer-rounds.plan` | 48887 | 53235 | 1255 | 16 | 282 | 4 | 16 | 3 | 3 | 0 | 260 |
| `plans/list.plan` | 48306 | 52560 | 1159 | 16 | 459 | 4 | 610 | 0 | 0 | 0 | 2 |
| `plans/section.plan` | 49048 | 55157 | 2210 | 26 | 1015 | 4 | 1511 | 0 | 0 | 0 | 2 |
| `plans/quiz.plan` | 47875 | 51718 | 857 | 14 | 370 | 7 | 14 | 0 | 0 | 0 | 2 |
| `plans/model3d.plan` | 49951 | 54311 | 1206 | 17 | 301 | 3 | 17 | 52 | 253 | 0 | 6445 |
| `plans/tabs-chart.plan` | 48814 | 53979 | 2015 | 22 | 257 | 7 | 879 | 0 | 0 | 0 | 2 |
| `test/rich.plan` | 49116 | 64767 | 2541 | 26 | 10253 | 5 | 2030 | 0 | 0 | 0 | 2 |
| `test/nodes.plan` | 49123 | 55709 | 3121 | 45 | 450 | 12 | 352 | 0 | 0 | 0 | 2 |
| `test/lists.plan` | 51732 | 61229 | 4477 | 55 | 1195 | 13 | 2034 | 0 | 0 | 0 | 2 |
| `test/shapes.plan` | 49264 | 53901 | 1193 | 16 | 375 | 7 | 16 | 15 | 25 | 7 | 1285 |
| `test/bytes.plan` | 47202 | 49859 | 413 | 6 | 17 | 2 | 6 | 2 | 3 | 1 | 224 |
| `test/free.plan` | 47685 | 51815 | 1092 | 19 | 426 | 5 | 514 | 0 | 0 | 0 | 2 |
| `test/looks.plan` | 49557 | 57110 | 4075 | 53 | 378 | 11 | 856 | 0 | 0 | 0 | 2 |
| `test/cards.plan` | 53650 | 68779 | 5343 | 71 | 5017 | 18 | 1182 | 0 | 0 | 0 | 2 |
| `test/model.plan` | 47961 | 51364 | 758 | 12 | 96 | 3 | 12 | 12 | 51 | 0 | 1392 |
| `test/fixtures/price-filter.plan` | 48200 | 53793 | 1354 | 15 | 1606 | 7 | 516 | 0 | 0 | 0 | 2 |
| `test/fixtures/mock-units-pl.plan` | 48518 | 52998 | 1762 | 21 | 162 | 4 | 28 | 0 | 0 | 0 | 2 |
| `test/fixtures/water-ko.plan` | 47490 | 50679 | 825 | 10 | 62 | 2 | 10 | 0 | 0 | 0 | 2 |
| `test/fixtures/habits.plan` | 47902 | 51588 | 994 | 14 | 228 | 3 | 410 | 0 | 0 | 0 | 2 |
| `test/fixtures/mock-water-ko.plan` | 47858 | 51346 | 979 | 13 | 103 | 2 | 13 | 0 | 0 | 0 | 2 |
| `test/fixtures/mock-price-filter.plan` | 48362 | 55298 | 1363 | 16 | 2814 | 6 | 616 | 0 | 0 | 0 | 2 |
| `test/fixtures/look-water-ko.plan` | 48138 | 52516 | 1669 | 20 | 166 | 5 | 20 | 0 | 0 | 0 | 2 |
| `test/fixtures/units-pl.plan` | 48294 | 52602 | 1651 | 19 | 155 | 4 | 26 | 0 | 0 | 0 | 2 |
| `test/fixtures/mock-shopping-it.plan` | 48901 | 53535 | 1727 | 19 | 117 | 4 | 720 | 0 | 0 | 0 | 2 |
| `test/fixtures/shopping.plan` | 48103 | 52254 | 1108 | 16 | 431 | 4 | 610 | 0 | 0 | 0 | 2 |
| `test/fixtures/look-shopping-it.plan` | 49352 | 55715 | 2589 | 25 | 736 | 8 | 832 | 0 | 0 | 0 | 2 |
| `test/fixtures/look-guide-es.plan` | 49705 | 55661 | 2653 | 35 | 393 | 5 | 35 | 0 | 0 | 0 | 2 |
| `test/fixtures/look-deck-es.plan` | 48616 | 53198 | 1334 | 16 | 533 | 4 | 16 | 0 | 0 | 0 | 2 |
| `test/fixtures/mock-habits-uk.plan` | 48683 | 54447 | 1766 | 21 | 1107 | 3 | 1407 | 0 | 0 | 0 | 2 |

The largest source is `test/cards.plan` with 53650 bytes: 6350 bytes under the bar 60000 and 11886 bytes under the limit 65536. The largest saved plan is `plans/lesson.plan` with 50316 bytes.
The drawing columns are of the open reply. The rover of `model3d.plan` takes 6589 bytes at its largest whole turn: the kit test of `model3d.plan` in `kit/test/emit.test.js` prints it ("most at any whole turn").

The run-time text by module, with the method of "Run-time text":

| Module | Bytes at `f1865a5` (Task 10) | Bytes at `9bcd313` |
| --- | --- | --- |
| `dsl.js` | 5283 | 5283 |
| `core.js` | 17557 | 18413 |
| `draw.js` | 5422 | 5509 |
| `rows.js` | 3440 | 3450 |
| `run.js` and `drag.js` | 12924 | 14007 |
| Run-time text | 44626 | 46662 |

The kit test `source: the run-time text is at most 48500 bytes` holds the bar, so 1838 bytes are left under it.
The rest of a source is the compiled data of the plan and a call line of 24 bytes. It is 532 bytes (`tea`) to 3654 bytes (`lesson`) for the saved plans, and 6988 bytes for `test/cards.plan`.
For example, the kit test of `calc.plan` prints the source 47495 bytes with the compiled data 809 bytes: 47495 = 46662 + 809 + 24.

### Times at this head

The device test `eachPlanAnswersInTime` takes the steps of "Time of each saved plan". The medians of the run `appfile-phase1`:

| Plan | Open (ms) | Slowest action timed (ms) | Action | Add to `maxItems` (ms) | After one earlier event (ms) | Surface event (ms) | State bytes, full | Actions: change state / on the open screen / smoke | Not timed |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `calc` | 9 | 10 | `bill_field` | none | none | none | 83 | 4 / 6 / 9 | none |
| `list` | 18 | 18 | `prodotti_preso` | 18 | none | none | 12495 | 4 / 7 / 11 | none |
| `quiz` | 15 | 15 | `revealButton` | none | none | none | 7322 | 3 / 3 / 3 | none |
| `tabs-chart` | 17 | 18 | `resumen_view` | 17 | none | none | 7472 | 6 / 10 / 12 | none |
| `timer-rounds` | 10 | 10 | `timer_start` | none | `timer_pause` 10, `timer_reset` 10 | 2 (`timer_bar`) | 282 | 1 / 1 / 3 | none |
| `tea` | 12 | 12 | `cay_field` | none | none | none | 20 | 1 / 2 / 2 | `demleme_timer` |
| `section` | 32 | 33 | `habits_remove` | 33 | none | none | 31650 | 16 / 31 / 33 | none |
| `lesson` | 14 | 15 | `lesson_next` | none | `lesson_back` 14 | 3 (`anim`) | 637 | 7 / 9 / 10 | none |
| `model3d` | 18 | 19 | `rover_part_panel` | none | none | 10 (pointer move) | 301 | 6 / 6 / 6 | none |

The column "Slowest action timed" holds the slowest action whose reply changes the state. An action whose reply keeps the state took at most 19 ms (`list`).
Each time is under its bar of the kit: 250 ms for an open and an action, 100 ms for a live input, and 50 ms for a surface event.
The medians of the 31 timed actions of `section.plan` were 31 to 33 ms in this run.
The full lists are 100 rows each: `prodotti` and `prodotti_list`, `cards`, `gastos`, and `habits` and `streak_list`. The largest full state is 31650 bytes (`section.plan`).

| Plan | Path | Live control: median of each timed value (ms) |
| --- | --- | --- |
| `calc` | fresh | `tip_field` 9, 9, 10 |
| `list` | fresh | `prodotti_new_nome` 18, 18. `prodotti_new_corsia` 17, 18 |
| `tabs-chart` | fresh | `gastos_new_concepto` 17, 17 |
| `section` | fresh | `habits_new_name` 33, 32 |
| a text field beside a surface timer | retained | `note_field` 2 |
| `test/bytes.plan` (logged, no bar) | retained | `pad_field` 120 |

The other medians of the run, each of five events (ms):

| Measure | Test | Median (ms) |
| --- | --- | --- |
| Compile of `quiz.plan` | `compileTakesUnderOneSecond` | 32 |
| Event of `calc.plan` | `calcPassesParseAndSmoke` | 9 |
| Add of `tabs-chart.plan` | `tabsChartPassesParseAndSmoke` | 11 |
| Event of `rich.plan` | `twoRichListsFullToTheirCountPassParseAndSmoke` | 21 |
| Event of `cards.plan` | `eachCardChartAndTabPassesParseAndSmoke` | 18 |
| Event on 100 rows | `anActionOnAListOf100RowsTakesUnder250Ms` | 15 |
| Event of `section.plan` | `sectionPassesParseAndSmoke` | 14 |
| Timer event of the 40 s work phase of `timer-rounds.plan`, host path | `timerRoundsPassesParseAndSmoke` | 3 |
| Action and timer event of `lesson.plan`, retained session | `lessonPassesParseAndSmoke` | 2 and 2 |
| Pointer move of `model3d.plan`, host path | `model3dPassesParseAndSmoke` | 9 |
| Live input of `test/bytes.plan` at the byte limit, retained session | `aReplyAtTheByteLimitPassesTheHostPath` | 120 |

### The timer points of the design, section 8

1. A duration that a choice gives: yes. The `source` may return a `ui` with a new duration after the choice changes, and Alphy's validator takes the new duration.
   In this run, `teaPassesParseAndSmoke` read 300000 ms before a choice, 180000 ms after "Yeşil çay", and 420000 ms after "Bitki çayı".
   So a timer whose time comes from a choice needs no `outside("timer")` rule.
2. No effect from a timer event: the canvas form works. A timer with rounds draws its bar on a surface and asks for timer events.
   In this run, `timerRoundsPassesParseAndSmoke` passed: the smoke step sent `timer_bar`, and a short plan of 2 rounds went through the host's path on the real clock.
   Five timer events of the 40 s work phase took 4, 3, 3, 3, and 3 ms (median 3 ms). The end of a round shows on the screen only, and the kit's report has a note, not a fault.
   So the timer part needs no `outside("timer")` rule for rounds.

The section "Timers" holds the record of Task 6.

### The mapping rules of the design, section 2

| Rule | Result | Evidence |
| --- | --- | --- |
| Each part keeps its inner values in its own state object, next to its value. A plan of 9 needs then has about 20 of the 40 fields. | Changed (Tasks 4 and 9). The inner values of a part are the object `NAME_s` beside its value (Task 3). A list of a part is a top-level list field (Task 4), and the record of a model is a top-level object (`rover`, Task 9). The saved plans have 0 to 6 needs and 1 to 7 of the 40 fields. The most fields of a plan is 18 (`test/cards.plan`). | The column "Fields" of the budget table. Each plan passed `ToolSpec.parse` in this run. |
| State field names come from need names. For a need that changes, the kit gets the earlier file and the stored state and computes the next state by the rule of section 5. | Proved for the names. The purpose of the rule is not proved: a need that a later change keeps keeps its data. That needs the next state of section 5 (storage and later changes), which belongs to phase 2, and no task of this plan built it. | Each field of a need has the name of the need (`prodotti`, `habits`, `gastos`, `rover`). The kit tests `emit: the other values of a part are one object of its need, and a computed test is a field of v` (fields `a`, `b`, `wiz_s`) and `emit: a list or an array that a part keeps beside its need is a state field of its own` (fields `tasks_log`, `tasks_s`) in `kit/test/emit.test.js`. |
| A `visible=` test needs a boolean state field, or the `source` returns `ui` for each event. Phase 1 picks one by measurement. | Changed: the boolean field, with no measurement. A computed `visible=` is a boolean field of the state object `v` with `visibleBind` (Task 3). A reply holds `ui` only when the screen changed (`kit/src/run.js` 463). The brief of Task 3 made this choice. No run measured the other form. | `eachNodeOfTheTablePassesParseAndSmoke` (a computed `visible=`). `aTypedTextShowsASecondLiveFieldThatTakesItsText` (`visible=len($/first) > 0`). With this form, the slowest action of a saved plan took 33 ms. |
| A sorted or filtered view of a list is a second list field. A row control writes back to the stored list by the row key. | Proved. | `list.plan` has the list `prodotti` and its sorted view `prodotti_list`, and `section.plan` has `habits` and `streak_list`. A row event carries `{key, value}`. `aTapOnARowControlChangesTheRow` taps the first checkbox of the sorted view, and only the row `r2` of the stored list changes. `eachPlanAnswersInTime` sends the row actions on lists of 100 rows. |
| `dateInput` cannot bind an empty text. A date field starts at today. | Proved. | The kit test `emit: an empty date start value becomes today, the day of the clock of the kit`. The `dateInput` of `test/lists.plan` passed parse and the smoke step in `eachListAndModalPassesParseAndSmoke`. |
| `timeInput` holds minutes. The kit converts to and from "HH:MM". | Proved. | The kit test `run: a time field reads "07:30" as 450 minutes and writes it back`. The `timeInput` of `test/lists.plan` holds 480 and 1200 ("08:00" and "20:00") and passed parse and the smoke step in `eachListAndModalPassesParseAndSmoke`. |
| `numberInput` shows an Apply button, as in today's apps. | Proved in code and by the action event for a number field. The Apply button is not seen on a device. Changed for a text field and a slider (Task 11c). | A `NumberField` is a `numberInput` with no `inputMode` (`kit/src/run.js` 113), so the host keeps its apply step. A `TextField` and a `Slider` are live inputs with no apply button, as the inputs of the baseline apps: `theKitListTakesTheStepsOfTheBaselineList`, `theTipSliderOfCalcChangesTheTotalWithNoStepByHand`. No device test typed into a number field, so no picture shows its apply button. |
| `chart` wants a categories array and a number array in the state. | Proved. | `Chart` writes the fields `<key>_labels` and `<key>_values` (Task 5). `tabsChartPassesParseAndSmoke`, `eachCardChartAndTabPassesParseAndSmoke`. |
| At most 4 images. A picture in each row or card becomes an `emoji`: the row field then holds one emoji sign. | Proved for the emoji. Images are not built in phase 1. | A row field holds one emoji sign, and `icon=$sign` shows it (`mock-habits-uk`, pictures of this run). A `Picture` line is the fault "picture is not built yet" (`kit/src/run.js` 220), so the kit makes no image and has no check of 4 images. |
| A `picture` need gets its image from the picture maker of the agent (phase 5). In phases 2 to 4, a plan with a `picture` need is not taken. | Proved for phase 1: a `picture` need is a fault. | The kit test `emit: a picture need is a fault "picture is not built yet"`. `model3d.plan` lost its picture line (ruling of Task 9). `kit/test/fixtures/dialogue-rover.plan` keeps the line and gives only that fault. No device test. |
| `list.reorder` gives a real drag. The prototype used two buttons. | Proved for the reorder event. No finger drag on a device. | The arrows of a row, `move(-1)` and `move(1)`, become the `reorder` of the list (`kit/src/rows.js` 116 to 121, Task 4). The host draws a drag handle for it (`NativeRecordReorder.kt`, `detectDragGestures`). `eachListAndModalPassesParseAndSmoke` sends the event of a drag (`steps_list_move` from `r6` to `r8`), and the order becomes `r7`, `r8`, `r6`. |
| A `drawing` part becomes one surface that a `canvas` node shows. The kit writes the code that draws it. A shape with `stroke=true` becomes an `arc`, a `polyline`, or a `path`. The limits are for all surfaces of one app together, and a plan over a limit is a fault of its need line. | Proved (Task 8). | `lessonPassesParseAndSmoke`, `eachShapeAndColorPassesParseAndSmoke`, `aReplyAtTheByteLimitPassesTheHostPath`, `aSmallModelBesideADrawingPassesParseAndSmoke`. The orbit of `lesson.plan` has `stroke=true` and becomes an `arc` of 360 degrees (`kit/src/draw.js` 38). A plan over a limit and a fifth surface are faults of the need line (kit tests `emit: a plan over a limit is a fault of its need line` and `emit: a fifth timer on a surface is a fault of its need line`). |
| `animate` needs frame or timer events, which Alphy gives to an app with a declared surface while its playback is `playing`. The kit asks for these events only while a timed change runs. | Proved (Tasks 8 and 11a). | The reply asks for `{id: "anim", intervalMs: 50}` only while a change runs. In `theLapButtonMovesTheHandInTheDrawnApp`, Alphy's own timer loop of the drawn app moves `angle` to 360. |
| A `model3d` part is drawn by the kit as polygons on such a surface, with no 3D run-time. A turn by a drag is a pointer event with the 100 ms limit. The prototype also gives two arrow buttons. | Proved (Task 9). | `model3dPassesParseAndSmoke`: 52 faces, a drag of 50 pixels turns the rover from 30 to 60 degrees, and a pointer move takes 9 ms (median). The plan has the two turn buttons. |
| The three rules of the drawing, of `animate`, and of `model3d` are read from the contract text. Phase 1 proves them with two more saved plans. | Proved. | `lesson.plan` and `model3d.plan` passed parse, the smoke step, and their events in this run. |

### The stop rule

The stop rule of phase 1 (design, section "Phases and stop rules", item 1): if a plan cannot pass inside Alphy's limits, the design comes back to the user before anything else is built.
If only the drawing or the 3D model cannot pass, that kind leaves the prompt, its requests get `outside`, and the user hears it.

Verdict: the stop rule does not stop. Each of the nine saved plans passes inside each limit of Alphy at `9bcd313`, also the drawing (`lesson`) and the 3D model (`model3d`).
So the design does not come back to the user for a limit, and the drawing and the 3D model stay in the prompt.

Each plan against each limit, the smallest margin first. The size limits are the limits of the budget test. A limit of time is the host limit: 500 ms for an event in the sandbox (`SandboxContract.EVALUATION_MS`), 100 ms for a surface event (`GraphicsContract.EVALUATION_MS`).
"none" marks a limit that does not apply to the plan: no surface, or no live input. Two rows are not measures of the host: the render work is the kit's count, and the request is a bound (see the notes under the table).

| Limit | `calc` | `list` | `quiz` | `tabs-chart` | `timer-rounds` | `tea` | `section` | `lesson` | `model3d` | Smallest margin |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Source, 65536 bytes | 47495 | 48306 | 47875 | 48814 | 48887 | 47194 | 49048 | 50316 | 49951 | `lesson`: 76.8 % used, 15220 bytes left |
| Render work with full lists, 2048 units (kit's count) | 8 | 610 | 14 | 879 | 16 | 8 | 1511 | 24 | 17 | `section`: 73.8 % used, 537 units left |
| Request of an event with full lists, 65536 bytes (bound) | 14076 | 26488 | 21315 | 21465 | 14275 | 14013 | 45643 | 14630 | 14294 | `section`: 69.6 % used, 19893 bytes left |
| State with full lists, 49152 bytes | 83 | 12495 | 7322 | 7472 | 282 | 20 | 31650 | 637 | 301 | `section`: 64.4 % used, 17502 bytes left |
| Definition, 262144 bytes | 50551 | 52560 | 51718 | 53979 | 53235 | 50011 | 55157 | 55898 | 54311 | `lesson`: 21.3 % used |
| Drawing bytes, 32768 | none | none | none | none | 260 | none | none | 452 | 6589 | `model3d`: 20.1 % used |
| Fields, 40 | 4 | 4 | 7 | 7 | 4 | 1 | 4 | 7 | 3 | `quiz`, `tabs-chart`, `lesson`: 17.5 % used |
| Drawing pairs, 2048 | none | none | none | none | 3 | none | none | 5 | 253 | `model3d`: 12.4 % used |
| Drawing commands, 512 | none | none | none | none | 3 | none | none | 5 | 52 | `model3d`: 10.2 % used |
| Surface event, 100 ms | none | none | none | none | 2 | none | none | 3 | 10 | `model3d`: 10.0 % used |
| Slowest timed action, 500 ms | 10 | 19 | 15 | 18 | 10 | 12 | 33 | 15 | 19 | `section`: 6.6 % used |
| Slowest live input, 500 ms | 10 | 18 | none | 17 | none | none | 33 | none | none | `section`: 6.6 % used |
| Open, 500 ms | 9 | 18 | 15 | 17 | 10 | 12 | 32 | 14 | 18 | `section`: 6.4 % used |
| Nodes, 512 | 8 | 16 | 14 | 22 | 16 | 5 | 26 | 22 | 17 | `section`: 5.1 % used |
| UI, 65536 bytes | 661 | 1159 | 857 | 2015 | 1255 | 577 | 2210 | 1940 | 1206 | `section`: 3.4 % used |
| Start state, 49152 bytes | 83 | 459 | 370 | 257 | 282 | 20 | 1015 | 637 | 301 | `section`: 2.1 % used |
| Drawing characters, 2048 | none | none | none | none | 0 | none | none | 5 | 0 | `lesson`: 0.2 % used |

- The smallest margin is the source of `lesson.plan`: 50316 of 65536 bytes, 15220 bytes left, and 9684 bytes under the kit bar 60000. The run-time text is 46662 bytes of each source.
- The next three margins are of `section.plan`: the render work (1511 of 2048 units with 100 rows in each list), the request of an event (at most 45643 of 65536 bytes), and the full state (31650 of 49152 bytes with 100 habits). Each other limit is at most 21.3 % used.
- Render work: the host refuses a screen over 2048 units (`ToolUiCatalog.MAX_RENDER_WORK`, `ToolUiCatalog.kt` 15, `validateRenderedWork` 644 to 685). The row is the kit's count, not a count of the host.
  The kit test `budget: each saved plan is inside each limit` counts it by the rules of `validateRenderedWork`, with each list full to its row count and each series of a chart at 256 points (column `work`, `bun test kit/test/budget.test.js`).
  The host counts the points that the state holds, at most 256 in all series of a chart (`ToolUiCatalog.kt` 830), so its count is not above the kit's count. The host does not log its count. The host checked each open of the run with full lists, and each open passed.
  The emitter sets the row count of the lists so that this count stays at most 2048 (`kit/src/emit.js` 216 to 220). In `section.plan`, the row count is 100, the most rows of a list in the kit (`MAX_ROWS`, `kit/src/rows.js` 15).
- Request: `ToolEngine.kt` 281 builds the request `{artifactId, revision, state, event}` with the stored state. `SandboxContract.validateRequest` (`SandboxContract.kt` 100 to 102) reads it with the limit `MAX_BYTES`, 65536 bytes. No test measured a request.
  A cell is the full state of the run plus 13993 bytes, the most that the rules of the host permit for the rest of a request. That rest is:
  an artifact id of 128 characters, a revision of 10 digits, a `liveInput` event with an action id of 48 characters (`[A-Za-z][A-Za-z0-9_]{0,47}`) and a row value with a key of 128 and a text of 2048 characters, each character at 6 bytes (`\uXXXX`), and 111 bytes of keys and signs.
  A stored state has at most 49152 bytes (`ToolSpec.validateState`), so a request has at most 63145 bytes, 2391 under its limit. So this limit cannot fail before the state limit.
- The drawing cells are the open reply, except `model3d`. Its three cells are the rover at its largest whole turn (52 commands, 253 pairs, 6589 bytes). At the open, the rover has the same commands and pairs and 6445 bytes.
- The open of each plan with full lists went through `executeEvent`, which checks the state and the reply.
- The sources of these numbers: the budget table and the kit test of `model3d.plan` (`bun test kit`), and the `time` lines and the `event times` lines of `evidence/alphy-ui/appfile-phase1/logcat.txt`. The column `work` of the budget table is from fix round 1 of Task 12, and the kit sources did not change since `9bcd313`. The scratch script that sorts the matrix is not in the tree.

The matrix does not hold these limits of the host. Each one is not measured, and each one cannot fail before a limit of the matrix or a fault of the kit:

- The result of an event, 196608 bytes (`SandboxContract.MAX_RESULT_BYTES`, `SandboxContract.kt` 9, read at 169 and in `GraphicsSession.kt` 53). A reply of the kit holds the state, the `ui`, the surfaces, and one timer request for each running surface and for a running change of `animate` (`kit/src/run.js` 469). The state, the `ui`, and the drawing have their own limits, 147456 bytes together.
- The depth of the `ui`, 8 (`ToolUiCatalog.kt` 205 and 658). The emitter gives a fault that names the line at a depth over 8 (`kit/src/emit.js` 194 to 195). The host checked the depth at each open of the run.
- The children of a container, 50 (`ToolUiCatalog.kt` 680). The emitter gives a fault that names the line (`kit/src/emit.js` 196).
- The surfaces of an app, 4 (`ToolSpec.kt` 224). The emitter gives a fault that names the line of a fifth surface (`kit/src/emit.js` 237).
- The segments of the drawing, 512 (`GraphicsContract.kt` 201 to 202). Only a `path` command has segments, and the kit draws no `path` (`kit/src/draw.js`).
- The images of a screen, 4 (`ToolUiCatalog.MAX_IMAGES`). The kit makes no image node: a `Picture` line is a fault.

Limits of this verdict:

- `model3d.plan` passes without the picture line of the prototype plan (ruling of Task 9). `kit/test/fixtures/dialogue-rover.plan` keeps the line and gives the fault "picture is not built yet". Pictures belong to phase 5.
- The times come from the emulator, not from a phone. Each time comes from one run. In the run `appfile-sizes-times-6` of Task 10, the times of `section.plan` rose to 57 ms in the middle of the plan (section "Time of each saved plan").
- The full lists are copies of the start rows. Rows with longer texts make a larger state and a larger request.
- The saved plans are fixed plans of this plan, not plans that the model of the app wrote. Phase 2 measures the plans that a model writes.

### Host items

This plan changed no host code. These facts about the host came from the device runs of this plan.

1. The empty text of a chart does not follow the language of the app. `R.string.tool_chart_empty` (`app/src/main/res/values/strings.xml` 399) has no `_en` string and no translation, and `NativeToolUi.kt` 1846 reads it with `stringResource`, not with `miniAppString`.
   A person sees the Russian "Нет данных для графика" in the Spanish app `tabs-chart`.
2. The refine action of the shell bar does not follow the language of the app. `R.string.tool_refine` "Изменить" (`strings.xml` 284) shows in each app (`ToolUi.kt` 686). It is a text of the shell, not of the app.
3. The inactive dots of a `progress` with `variant="dots"` have low contrast in the dark theme (`NativeToolUi.kt` 1337, the track color of `progressTrack` at 369 to 370).
   A person sees the steps of the lesson of `lesson.plan` with faint inactive dots.
4. The border of an outlined button on a hero card has no contrast in the dark theme. The host draws the Material 3 `OutlinedButton` border (`NativeToolUi.kt` 1176) on the hero container (`NativeToolUi.kt` 1062): 1.0 to 1 in the dark theme, 1.3 to 1 in the light theme. A tonal button has the color of the hero card.
   The text of the button reads well. Look rule 8 of `kit/README.md` keeps each outlined and tonal control off a hero card, so no plan of the kit shows it now.
5. A fast press after the finish of a live text value sends the earlier state version and gets the generic error.
   The finish button goes when the host commits the final live value as version V (`NativeToolUi.kt` 307, `NativeLiveDraft.complete`). The screen takes the version of an action from the record that the shell model publishes later (`ToolUi.kt` 965, `MiniAppContent`, after `ToolController.kt` 215 `watchStore` and 1382 `loadSnapshots`).
   A press in that interval sends V-1, and `ArtifactCoordinator.kt` 74 (`current`) throws "The mini-app data changed. Reopen it.".
   A person sees "Не удалось выполнить действие инструмента. Сохранённые данные доступны." and no new row. The typed text stays, and a second press adds the row.
   It happened in 3 of 80 presses with a store of 28 apps (2 of 40 in `mock-shopping-it`, 1 of 40 in the baseline app), and in 0 of 80 presses with a store of one app (section "The add that failed one time").
6. The host writes no log of an action exception. `ToolController.kt` 1355 to 1375 (`run`) keeps only the error text. A person sees only the generic text, and a developer finds no cause in the log.
7. No way exists to add a row with one press after the typing of a text. `NativeToolUi.kt` 209 to 213 (`allowAction`) refuses a press of a different control before the finish of a live edit and shows the notice `tool_live_input_finish_first` and the finish button. A `form` node is only a container.
   A person types, finishes the edit with the action key or the finish button, and then presses the add button. The baseline app has the same steps (section "Live inputs").
8. A reply at the byte limit of a drawing costs about 120 ms of host time for each event, an action or a live input. In this run, the live input of `test/bytes.plan` (32768 bytes, about 350 commands) took 120 ms (median), and the kit's dispatch takes 2 to 5 ms of it (Task 11c).
   About 90 ms follows the count of commands, and about 30 ms follows the length of the text. Assumption: the cost is in the checks of the host path outside the dispatch (`ArtifactCoordinator.kt` 457, `evaluate`). A probe that times the checks one by one can test it.
   A person would see a timed change with 50 ms steps fall behind on such a drawing. The saved plans are far under it (`model3d` 6589 bytes, pointer move 10 ms).
9. A shape of the muted role has the alpha 64 (`SurfaceDrawing.kt` 185 and 245, `MUTED_SHAPE_ALPHA`). A muted line had the contrast 1.5 to 1 (light) and 1.6 to 1 (dark). A look rule of the kit keeps lines out of the muted role.
10. A disabled button is almost invisible in the dark theme. A person sees the disabled "Atrás" of the steps of `look-guide-es` as a faint outline (`NativeToolUi.kt` 1176, `OutlinedButton` with `enabled=false`).
11. The host has no pair of left and right arrow icons (`ToolUiCatalog.kt` 42, `shellIcons`). The two turn buttons of `model3d.plan` show a back arrow and a chevron.
12. The host does not shorten a text in a row. A text with no weight shares the free width with the title (`NativeToolUi.kt` 945 to 947), so a long text breaks into more lines.
    A person sees "Frutta e verdura" in three lines in `look-shopping-it`, "Пити воду" in two lines in `mock-habits-uk`, and "2017 · One size · Fair" in two lines in the plan and in the baseline app of the bikes.
13. The faces of a model keep their hex colors, because the shading of a face needs a hex color (`SurfaceDrawing.kt` 247 takes a role name or a hex). The colors of a model do not change with the theme.
14. A loss of focus does not finish a live edit (`NativeToolUi.kt` 240, `NativeLiveDraft`). Task 11c read it in the code. No device test showed it.

### Open decisions for the user

This section makes no choice. Each item gives the facts for a decision of the user.

1. Where the run-time lives.
   - Now, the run-time lives inside each app. Each `source` holds the run-time text of 46662 bytes: `dsl.js` 5283, `core.js` 18413, `draw.js` 5509, `rows.js` 3450, and `run.js` with `drag.js` 14007 (section "Sizes at this head").
   - The kit bar of the run-time text is 48500 bytes, so 1838 bytes are left. The largest source is 53650 bytes (`test/cards.plan`): 11886 bytes under the host limit 65536 and 6350 under the kit bar 60000. The largest saved plan is 50316 bytes (`lesson.plan`): 15220 bytes under the host limit.
   - The run-time text was 50138 bytes after Task 9, 44626 bytes after the cut of Task 10, and 46662 bytes now. The tasks after Task 10 (the look and the live inputs) added 2036 bytes.
   - The later phases add pictures and capabilities (phase 5), the canvas code section (phase 6), and more parts. In this form, each of them adds run-time code to each `source`.
   - The other form: the run-time lives in the host. Then an app holds only its plan and its compiled data, and it needs a host that has the run-time.
     The saved plans are 387 bytes (`timer-rounds.plan`) to 2314 bytes (`lesson.plan`). Their compiled data with the call line is 532 to 3654 bytes.
2. The host items that change what a person sees: items 1, 2, 3, 5, 7, 10, 11, and 12 of "Host items". Item 4 shows only if the kit permits an outlined or tonal control on a hero card. Item 8 shows only with a drawing near the byte limit.
   Item 9 shows only with a line in the muted role, and a look rule of the kit keeps lines out of it. Item 13 shows in each model: its faces have the same colors in both themes.
   Item 14 comes from the code only, and no device test showed it. Item 6 changes the log, not the screen.
3. The open points for the prompt of phase 2 that the ledger of this plan names:
   - A year belongs in `text()`, not in a figure: a figure prints a year with a digit group (2,019).
   - The display sizes of a text (`emphasis` `number` and `display`) are too large for a list row.
   - `edit=true` of `entries` puts typed fields into rows. An edit of a row in a dialog has no part form yet.
   - For an alert timer of one round, the host already shows its own buttons and alert there. The open point is what the prompt says about `start=`, `pause=`, `reset=`, and `done=` for such a timer. A done text could go into a notification, which needs the permission `POST_NOTIFICATIONS`.

### Pictures

The pictures of this run are in `evidence/alphy-ui/appfile-phase1/light/` and `dark/`, 31 in each theme. Git does not keep these files.
The contact sheets are `evidence/alphy-ui/appfile-phase1/contact-light.png` and `contact-dark.png`. The pair sheet `pairs-light.png` in the same folder shows each of the five baseline apps at the left and its plan with parts at the right.
Scratch scripts of Task 12 made the sheets. They are not in the tree.

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
Its bar is one surface in a `canvas` node: 320 by 64 in Task 6, and 1024 by 64 since Task 11b-2b (`kit/src/draw.js` `BAR_SURFACE`).
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
- In Task 6, no test took a screenshot of the canvas. The drawing commands passed `GraphicsContract`, and nobody looked at the bar.
  Since Task 11a, `picturesOfEachPlan` takes a picture of the bar of `timer-rounds` in both themes (section "Look").
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

| Module | Bytes at `850c5e3` | Bytes at `f1865a5` | Change |
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
At `f1865a5`, each source was 5512 bytes smaller than at `850c5e3`. The rule of an open after a drag added 48 bytes after `0b18408`.
The split at `9bcd313` is in "Phase 1 proof", section "Sizes at this head".

### Budget of each compiled plan

The kit test `budget: each saved plan is inside each limit` (`kit/test/budget.test.js`) prints a table of each plan that compiles, with the columns below.
The table at `9bcd313` is in "Phase 1 proof" (section "Sizes at this head"). In Task 10, it measured the 9 saved plans, the 8 test plans, and the 5 fixtures that compiled.
The fixtures `catalog`, `dialogue-game`, `dialogue-materials`, and `dialogue-rover` do not compile, and the test skips them.

- source: the UTF-8 bytes of the source (`SandboxContract.MAX_SOURCE_BYTES`). The test fails at 60000 bytes or more.
- definition, ui, and state: the bytes as org.json writes them (`ToolSpec.MAX_DEFINITION_BYTES`, `ToolUiCatalog.MAX_UI_BYTES`, `ToolSpec.validateState`).
- nodes: each node of the ui, also each node of a row template (`ToolUiCatalog.MAX_NODES`).
- fields: the top-level fields of the state schema (`ToolValueSchema`).
- work (since fix round 1 of Task 12): the kit's count of render work by the rules of `ToolUiCatalog.validateRenderedWork` (`MAX_RENDER_WORK` 2048), with each list full to its row count and each series of a chart at 256 points. It is not a count of the host.
- commands, pairs, chars, and bytes: the drawing of the open reply over all its surfaces (`GraphicsContract.frame`). A plan with no surface shows 2 bytes: the text `{}`.

At `0b18408` (Task 10), the largest source was `test/cards.plan` with 51476 bytes, and the largest saved plan was `plans/lesson.plan` with 48247 bytes.
The state column is the start state. The state with each list full to its row count is in the time table of "Times at this head".

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
6. Run the smoke step again from the full state, and keep each action event that it sends. Since Task 11c, it also keeps each live input event.
7. Keep each of these actions that `ToolUiCatalog.validateAction` takes on the open screen. Time each one five times on the full state.
   Since Task 11c, the test also times each live input event that `ToolUiCatalog.validateLiveInput` takes (section "Time of a live input").
8. For an action that shows after one earlier event, send that event through `executeEvent`, time the action five times, and assert that its reply changes the state.
   Then send the action through `executeEvent`. These are `timer_pause` and `timer_reset` after `timer_start`, and `lesson_back` after `lesson_next`.
9. Take the slowest action whose reply changes the state, the add and the actions of step 8 included. Send it once more through `executeEvent`.
10. For a plan with a surface, send the start event and five surface events through one retained `GraphicsSession`.
11. Assert the set of action ids of the open screen that no timed event has: `demleme_timer` for `tea`, and no action id for each other plan.

The limits are 250 ms for `open` and for each timed action, also an action whose reply keeps the state, and 50 ms for a surface event. Since Task 11c, the limit of a live input is 100 ms.
Alphy stops an evaluation at 500 ms (`SandboxContract.EVALUATION_MS`), and a frame, pointer, or timer event at 100 ms (`GraphicsContract.EVALUATION_MS`).
The table of the run `appfile-phase1` at `9bcd313` is in "Phase 1 proof" (section "Times at this head").

In the run `appfile-sizes-times-round` of Task 10, the slowest time was 36 ms for an action (`section`) and 11 ms for a surface event, so no event needed a change for speed.
The actions of one plan differ by a few ms, so the name of the slowest action changes from run to run.
In the run `appfile-sizes-times-7`, the slowest action took 42 ms (`section`). Its actions took 37 to 42 ms at first and 32 to 35 ms later, for the same kind of row event.
In the run `appfile-sizes-times-6` on the same test, the times of `section.plan` rose from 32 to 41 to 57 ms in the middle of the plan, and its slowest action took 52 ms.
Assumption: other work on the host computer caused these changes. A run with nothing else on the host computer can test this assumption.
The full lists are 100 rows each: `prodotti` and `prodotti_list`, `cards`, `gastos`, and `habits` and `streak_list`.
In the run `appfile-sizes-times-round`, the largest full state was 31652 bytes (`section.plan`), under the limit of 49152 bytes.

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

### Time of a live input (Task 11c)

Task 11c makes the run-time text 46580 bytes (46227 before, bar 48500). The largest source is `test/cards.plan` with 53628 bytes (bar 60000).
The only change of a compiled definition is `inputMode: "live"` on each `input` and `slider` node, in 14 of the 34 compiled plans.

The host answers a `liveInput` event on one of two paths:

- A plan with no surface: a fresh sandbox for each event (`SandboxClient.execute`), with the host limit of 500 ms. The bar of the kit is 100 ms.
- A plan with a surface: its retained session (`GraphicsSession.dispatch`), with the host limit of 100 ms. The bar of the kit is 50 ms.

`eachPlanAnswersInTime` times each live control of each saved plan five times on the full state, with the 100 ms bar. It also asserts that no saved plan has a live control beside a surface.
No saved plan and no look fixture has a live control beside a surface. The test `aLiveInputBesideASurfaceTimerKeepsTheTimer` holds the 50 ms bar on a text field beside a surface timer.
The medians of the run `appfile-live-final`:

| Plan | Path | Live control: median of each timed value (ms) |
| --- | --- | --- |
| `calc` | fresh | `tip_field` 9, 10, 9 |
| `list` | fresh | `prodotti_new_nome` 18, 18; `prodotti_new_corsia` 18, 18 |
| `tabs-chart` | fresh | `gastos_new_concepto` 17, 17 |
| `section` | fresh | `habits_new_name` 32, 31 |
| a text field beside a surface timer | retained | `note_field` 2 |
| `test/bytes.plan` (logged, no bar) | retained | `pad_field` 118 |

The slowest live input of a saved plan takes 32 ms (`section`, with 100 habits). It is under the 100 ms bar.
The plans with no text field and no slider (`quiz`, `tea`, `timer-rounds`, `lesson`, `model3d`) have no live control.

A reply at the byte limit of a drawing (32768 bytes, `test/bytes.plan` with 350 lines) costs about 120 ms of host time for each event, an action or a live input.
The kit's dispatch takes 2 to 5 ms of it. About 90 ms follows the count of commands, and about 30 ms follows the length of the text (about 770 characters).
This is a fact about the host for the later drawing work, not a defect of the kit. The test logs the time of that fixture and has no time bar for it.
The split comes from a probe of the run `appfile-live-times` that timed `GraphicsSession.dispatch` alone. The probe is not in the tree.

## Look

The device class `AppFileLookTest` shows each compiled plan as a saved app in the real shell (`ShellFixture`, `ShellRobot`, `ToolStore.saveTool`).
It compiles each plan with `parsedAndSmoked` of `AppFileProofTest`, which gives the spec the language of the compile result (`ToolSpec.withAppLanguage`). The shell makes no model call.

- `picturesOfEachPlan` opens each of the nine saved plans and each look fixture of `kit/build.js` (`LOOK_FIXTURES`: five in Task 11a, seven since Task 11b-1, twelve since Task 11b-2a, fourteen since Task 11b-2b). Since Task 11b-2a, it also opens the spec of each of the five baseline apps (section "Look with parts").
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
The table is the state of Task 11a. The section "Look of each plan" gives the numbers and the pictures since Task 11b-2b.

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

This table is the state of Task 11a (run `appfile-look-before-3`). The section "Look of each plan" gives the state since Task 11b-2b.

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

In Task 11b-1, the number 1234.5 showed as "1234.5" both from a state value (`Metric("Plain", $/n)`) and from a computed value (`Metric("Computed", $/n * 1)`).
In Task 11b-1, the kit had no number format with digit groups: "남은 양" showed "2000 ml", and the baseline shows "2,000 ml".
Since Task 11b-2a, a figure prints its numbers with the digit groups of the language of the app (`kit/src/core.js` 53 to 60, section "Look with parts").

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

This table is the state of Task 11b-1. Since Task 11b-2a, a figure has digit groups (section "Look with parts").

| Plan | Difference | Cause |
| --- | --- | --- |
| `look-water-ko` | The emoji stands beside the metric, and the hero color differs. | Host revision: the baseline pictures come from `30d43aa`, this branch has `03c6e58`. |
| `look-water-ko` | "남은 양" shows "2000 ml", the baseline shows "2,000 ml". | Missing option in Task 11b-1: the kit had no number format with digit groups (C.5). Task 11b-2a added it. |
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

The shopping list starts empty, as in the baseline. In Task 11b-2a, the test also typed "Latte", pressed the apply button of the field and "Aggiungi", and took the picture `mock-shopping-it-added.png` with the new row.
Since Task 11c, the field "Prodotto" is a live input, as in the baseline, and the add of `mock-shopping-it` needs no apply step. The test adds "Latte" and "Pane" with the steps of the baseline (section "Live inputs"), and the picture shows two rows.

### Runs of Task 11b-2a

| Label | Revision | Observed | Test APK `lastUpdateTime` before and after |
| --- | --- | --- | --- |
| `appfile-look-parts-try1` | `de20585+dirty` (the plans and the baselines) | failed: `ToolSpec.parse` refused the field `appLanguage` of a baseline spec | `04:22:14`, `04:27:20` |
| `appfile-look-parts-try2` | `de20585+dirty` | failed: the IME action of "Prodotto" did not apply the typed text, so the test then pressed the apply button. Since Task 11c, the field is a live input with no apply button (section "Live inputs") | `04:27:20`, `04:30:53` |
| `appfile-look-parts-try3` | `9f435e9` tree | passed, 32 tests (`AppFileProofTest` and `AppFileLookTest`), 159.0 s. The pictures of the first round | `04:30:53`, `04:35:18` |
| `appfile-look-parts-try4` | `c022bdb+dirty` (the tree of `e8af9d2`) | passed, 32 tests, 156.7 s. The pictures of the second round | `04:35:18`, `04:46:51` |
| `appfile-look-parts` | `e8af9d2` | passed, 32 tests, 161.2 s. The pictures of this section | `04:46:51`, `04:50:49` |

All times are on 2026-11-19. The runner installed the test APK at each "after" time. No other session installed it during these runs.
The runs of the single items of the task (`appfile-numbers`, `appfile-entries`, `appfile-inputs`, `appfile-figures`, `appfile-counter`, and their red runs) passed or failed as expected before these runs.
The pictures are in `evidence/alphy-ui/appfile-look-parts/light/` and `dark/`, with the contact sheets `contact-light.png` and `contact-dark.png`.
The pair sheet `pairs-light.png` in the same folder shows each baseline app at the left and its plan at the right. Git does not keep these files.
The pictures after fix round 1 of the task are in `evidence/alphy-ui/appfile-look-parts-fix2/`, with the same sheets. There `mock-units-pl` shows three rows of one size, and `units-pl` shows four.

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

This table is the state of Task 11b-2a. Task 11b-2b gave four of its missing options: the icons of the add form of `mock-shopping-it`, the tone of a `show=` value, the total of the hero count, and the icons of the rows of `mock-units-pl`.
The section "Look of each plan" gives the state since Task 11b-2b and the differences that stay.

| Plan | Difference | Cause |
| --- | --- | --- |
| `mock-units-pl` | The baseline shows each conversion as a large value on a tinted row under the heading "Przeliczenia". The plan shows three plain rows of one size, each with the title at the left and the value at the right, with no tint and no heading. | Missing option: a row of a figure has no tone, and the figures of one place have no heading. The layout rule of the figures (Task 11b-2a, E.2, fix round 1) makes three figures or more in one place rows. Each value keeps `number` (36 sp), the size of the baseline rows. |
| `mock-shopping-it` | The fields of the add form have no icon, and the form has no heading "Aggiungi prodotto". | Missing option: the add form of `entries` gives its fields no icon. The heading: choice of the plan. |
| `mock-shopping-it` | No filter "Mostra", no order "Ordina per", and no button "Svuota comprati". | Choice of the plan: the list sorts by aisle with `sort=$corsia`. |
| `mock-habits-uk` | "Пити воду" breaks into two lines, and "Прогулянка 30 хв" has a smaller text. In the baseline both are wider. | Host limit: each text cell of a row without a weight shares the free width with the title (`NativeToolUi.kt` 946 to 947). The two `show=` values are badges with their own padding, and the baseline has one plain text and one badge. |
| `mock-habits-uk` | The streak badge is grey, and the baseline badge has the accent tone. The ring shows "3 / 21", and the baseline shows "3 з 21". The plan has no button "Редагувати назви". | Missing options: a `show=` value has no tone, and the `progress` part writes "/" in each language. The button: choice of the plan (`add=false`). |
| `mock-price-filter` | The hero shows "12", and the baseline shows "12 of 12". The inputs have no heading "Filters", and the plan has no button "Reset filters". | Missing option: the hero count of `entries` has no total. The heading and the button: choices of the plan. |
| `mock-water-ko` | The baseline has a section "기록 관리" with a manual entry and a reset. The plan has the reset beside the cup button and no manual entry. | Choice of the plan: the request asks for the cup button and the progress only. |

A long detail line of a bike ("2017 · One size · Fair") breaks into two lines in the plan and in the baseline, for the same host limit.

## Live inputs

Since Task 11c, each `TextField` and each `Slider` of the kit is a live input of the host (`inputMode: "live"`), also in a list row and in a bottom sheet.
The inputs of the baseline apps are live too (14 of the 14 `input` nodes of the 15 baseline apps).
The host sends each value as a `liveInput` event while the person types or drags. The run-time takes only that event for a live control, and its reply holds only `state`, `ui`, and `surfaces`.
A `RangeSlider`, a `NumberField`, and each other control send an `action` as before.

### How a person adds a row

1. Type the text into the field.
2. Finish the edit with the action key of the keyboard, or with the host's finish button under the field.
3. Press the add button.

The host refuses a press of a different control before the finish (`NativeLiveEdits.allowAction`). It shows a notice in the language of the app ("Termina la modifica e attendi il salvataggio delle modifiche") and the finish button ("Termina modifica: Prodotto").
The baseline app has the same steps. The device tests `theBaselineListNeedsAFinishStepBetweenTheTypingAndTheRow` (the baseline `showcase-lang-shopping-it`) and `theKitListTakesTheStepsOfTheBaselineList` (`mock-shopping-it`) run the same helper on the two apps:

| Step | Baseline: draft, rows | `mock-shopping-it`: draft, rows |
| --- | --- | --- |
| 1. Type "Latte", press "Aggiungi" at once: the notice shows | "Latte", 0 | "Latte", 0 |
| 2. Press the finish button, wait until it goes away | "Latte", 0 | "Latte", 0 |
| 3. Press "Aggiungi": the row and its check mark "Preso" show, and the field is empty | "", 1 | "", 1 |
| 4. Tap the field, type "Pane", press the action key, wait until the finish button goes away | "Pane", 1 | "Pane", 1 |
| 5. Press "Aggiungi" | "", 2 | "", 2 |

Open point: no step adds a row with one press after the typing. The host has no such step for a text field, and a `form` node is only a container.

A list with `edit=true` and `sort=` on a text field of the row sorts the view again at each delivered value, so the row moves while the person types.

### The slider and a second field

- `theTipSliderOfCalcChangesTheTotalWithNoStepByHand`: the accessibility action that sets the progress of "Tip %" sets the tip to 22.5, and the total changes. A drag to the left end sets it to 0, and the total changes again. No apply button and no finish button show. The slider finishes its edit when the drag ends, so the next press of a different control goes through.
- `aTypedTextShowsASecondLiveFieldThatTakesItsText`: a text in the first field shows a second field (`visible=len($/first) > 0`), and the second field takes its text.

### The first screen

A live text field shows no apply button. The run `appfile-live-final` took the pictures of each plan in both themes. A script compared each one with the run `appfile-look-parts-fix2` of Task 11b-2a, pixel by pixel:

- Each first screen is the same, except for the status bar strip at the top (the clock and the signal icon). This includes each plan with a live control (`calc`, `habits`, `list`, `look-shopping-it`, `mock-shopping-it`, `section`, `shopping`, and `tabs-chart`) and `lesson`, which has a number field and a choice but no live control.
- The first screen of `tabs-chart` is the tab "Resumen", and its text field is on the tab "Añadir", so its picture does not show the field.
- `mock-shopping-it-added.png` shows two rows ("Latte" and "Pane") and the count 2. Before, it showed one row and the count 1. The field "Prodotto" is empty, and no apply button shows.

### Runs of Task 11c

| Label | Revision | Observed | Test APK `lastUpdateTime` before and after |
| --- | --- | --- | --- |
| `appfile-step-zero` | `a74c20f` + the step zero test | passed: the baseline needs a finish step | `05:31:24`, `05:51:51` |
| `appfile-step-zero-classes` | the tree of `86810bd` | passed, 33 tests, 162.7 s | `05:51:51`, `05:52:27` |
| `appfile-live-ui` | `f53ef3b+dirty` | 3 of the 4 new tests passed. The slider test failed | `05:52:27`, `06:24:04` |
| `appfile-live-slider` to `appfile-live-slider-3` | `f53ef3b+dirty` | failed: the test read a node with no range, set a value that is not a fraction, read the total during the drag, and ended the swipe right of the low end | `06:24:04`, `06:26:51` |
| `appfile-live-slider-4` | `f53ef3b+dirty` | passed | `06:26:51`, `06:27:28` |
| `appfile-live-classes` | `7400405+dirty` | 36 of 37 tests passed. The time bar of `test/bytes.plan` failed (121 ms) | `06:27:28`, `06:28:10` |
| `appfile-live-times` | `7400405+dirty` and a probe | the probe that split the 120 ms of `test/bytes.plan` | `06:28:10`, `06:32:20` |
| `appfile-live-final` | the tree of `b2a7141` | passed, 37 tests, 173.3 s. The times and the pictures of this section | `06:32:20`, `06:41:12` |

All times are on 2026-11-19. Each "before" time is the "after" time of the run before it, so no other session installed the test APK during these runs.
The time lines are in `evidence/alphy-ui/<label>/logcat.txt`, and the pictures and the step files of `appfile-live-final` are in its folder. Git does not keep these files.

Command:

```text
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home python3 tools/compose_acceptance/run.py suite --serial emulator-5592 --label appfile-live-final com.alpha.assistant.tools.appfile.AppFileProofTest com.alpha.assistant.tools.appfile.AppFileLookTest
```

## Look of each plan

Task 11b-2b gave the parts `timer`, `deck`, `steps`, `question`, `lesson`, `chart`, `card`, `drawing`, and `model3d` the look of the baseline apps.
It also made the look rules kit tests (`kit/README.md`, sections "Parts" and "Look rules").
The numbers come from `bun kit/look.js`, before at `c92230b` and after at `f614e9f`.
The last column is what the pictures of the run `appfile-look-all-5` show in both themes, against the pictures of `appfile-live-final`.

The round before the review added a hero card rule: a hero card holds the main element and at most a filled button or a text button.
A field and an outlined or tonal control stand under the card, because their outline shows no contrast on a hero card in the dark theme.
The rule moved the controls of `model3d` and of the three forms of `deck` under their hero card, and a running timer now has a filled Pause.
The round also added two Spanish look fixtures: `look-deck-es` (a deck with a back, a mark, and the two counts) and `look-guide-es` (steps of cards with icons, and a question).
The second round before the review centered the back of a deck under its front (`403c573`), and it found the cause of the add that failed one time (see "The add that failed one time").

| Plan | Hero, before and after | Typed fields, first screen | Typed fields in rows | Seen in the pictures |
| --- | --- | --- | --- | --- |
| `calc` | 1, 1 | 2 | 0 | No change. |
| `lesson` | 1, 1 | 1 | 0 | The orbit drawing is a hero card. The orbit line has the role `ink`: 13.3 to 1 (light) and 7.3 to 1 (dark). Before, it was muted: 1.5 to 1 and 1.6 to 1. The count shows one time, "Исследуем: 1 / 3" over the dots. Before, the eyebrow "Исследуем · 1 / 3" showed the count a second time. Dark: the inactive dots have low contrast. |
| `list` | 1, 1 | 2 | 0 | No change. |
| `model3d` | 1, 1 | 0 | 0 | The model card is a hero card that holds only the model, and its stage has the muted tone of the theme. Under the card are the tonal chips "Камера", "Панель", "Колёса", then the two turn buttons and the outlined "Разобрать". Before, the stage was a bright panel in the dark theme, the chips were on the card, and "Разобрать" was filled. In `appfile-look-all-2`, the chips and "Разобрать" were outlined on the hero card, and their outline was not visible in the dark theme (1.0 to 1). Now, against the dark screen, a chip container has the contrast 2.0 to 1 and the outline of "Разобрать" 2.0 to 1 (light: 1.2 and 1.6). These are the colors of the host for these controls on each screen, and the texts read well. The two turn buttons show a back arrow and a chevron, because the host has no left and right arrow pair. |
| `quiz` | 1, 1 | 0 | 0 | No change. The plan writes its own component lines, so the template of `deck` does not apply. |
| `section` | 0, 1 | 1 | 0 | A hero card "Звичок у трекері" with the count 3 is first. The three rows of habits follow, and the section of the streaks is under the first screen. |
| `tabs-chart` | 1, 1 | 0 | 0 | The title "Gastos por categoría" shows one time, as the label of the chart. Before, it showed two times. The host text "Нет данных для графика" stays (section "Look"). |
| `tea` | 0, 1 | 0 | 0 | The timer card with the host's countdown "00:05:00" is a hero card. |
| `timer-rounds` | 1, 1 | 0 | 0 | Two hero cards: the ring of the rounds, then the timer. The timer has the muted phase text "Belastung 1 / 8", a bar over the full width of the card with a muted track and a stop dot, a filled "Start" with a play icon, and the text button "Zurücksetzen". Before, the bar was a short track with low contrast, and "Start" had no icon. |
| `water-ko` | 1, 1 | 0 | 0 | No change. |
| `units-pl` | 1, 1 | 1 | 0 | No change. |
| `shopping` | 1, 1 | 2 | 0 | No change. |
| `habits` | 1, 1 | 1 | 0 | No change. |
| `price-filter` | 1, 1 | 2 | 0 | No change. |
| `look-water-ko` | 1, 1 | 0 | 0 | No change. |
| `look-shopping-it` | 1, 1 | 1 | 0 | No change. |
| `mock-water-ko` | 1, 1 | 0 | 0 | No change. |
| `mock-units-pl` | 1, 1 | 1 | 0 | Each of the three rows has its icon of Alphy at the left of its title: a drop, a glass, and a fork and knife. |
| `mock-shopping-it` | 1, 1 | 1 | 0 | The field "Prodotto" has a cart icon, and the choice "Corsia" has a tag icon. `mock-shopping-it-added.png` shows the same icons and the two rows. |
| `mock-habits-uk` | 1, 1 | 0 | 0 | The streak badge of each row has the accent tone. |
| `mock-price-filter` | 1, 1 | 0 | 0 | The hero shows "12 of 12". |
| `look-deck-es` | new, 1 | 0 | 0 | The hero card holds the front "la manzana" (centered, display emphasis) and the filled "Ver traducción". The counts "La sabía" and "Todavía no" are a grid of two under it. `look-deck-es-shown.png`: the back "the apple" (centered, headline emphasis) is on the card under the front, and the row of the filled "La sabía" with a check and the outlined "Todavía no" is under the card. |
| `look-guide-es` | new, 1 | 1 | 0 | The hero card "Café molido" shows "30 g", a large coffee emoji, and the muted note. The field "Tazas" with its two step buttons follows, then the step title "1. Moler", the card "Muele el café" with its coffee icon and muted body, the disabled outlined "Atrás" and the filled "Siguiente", and the card of the question with three tonal answers. "Atrás" is faint because the host draws a disabled button so. `look-guide-es-answered.png`: the chosen "92–96 °C" is filled, and the success notice "¡Correcto! Así el café no se quema." shows under the answers. |

A script compared each picture with the same picture of `appfile-live-final`, pixel by pixel under the status bar.
Only the pictures of the 10 plans with a note in the last column changed, and `mock-shopping-it-added.png`, in both themes. Each other picture is the same, also each picture of a baseline app.
Against `appfile-look-all-2`, only `model3d` changed in the run `appfile-look-all-3` (22.9 % of the pixels in both themes). The four pictures of the two new fixtures are new.
Against `appfile-look-all-3`, the runs `appfile-look-all-4` and `appfile-look-all-5` changed only the back in `look-deck-es-shown.png` (0.6 % in both themes), which is now centered.
`look-guide-es-answered.png` shows the same screen at a different scroll offset in each run (33 % of the pixels), because the reveal of the notice does not always scroll by the same distance.
No picture shows cut text, an overlap, an empty first screen, or a control with no label.
A text at the bottom edge of a picture continues under the first screen.
The captions of the two counts of `quiz` and of the deck of `look-deck-es` are the texts of their two buttons, because each count is the number of presses of its button.
So in `look-deck-es-shown.png`, "La sabía" and "Todavía no" show two times: on the buttons and over the counts. The template of `deck` has no option for a different caption of a count.
The second round kept these captions by a ruling: each count is the tally of its button, and the saved plan `quiz.plan` has the same form.

The 4 items of "Where the plans with parts are still worse than the baseline" (section "Look with parts") that had a missing option now use it: the icons of `mock-shopping-it`, the tone of `mock-habits-uk`, the total of `mock-price-filter`, and the icons of the rows of `mock-units-pl`.
These differences stay:

- `mock-units-pl`: the rows have no tint and no heading "Przeliczenia".
- `mock-habits-uk`: the ring shows "3 / 21", and the baseline shows "3 з 21".
- The choices of the plans in that table.

Host limits that the pictures show (no change of the host in this task):

- Dark: the inactive dots of a `progress` with `variant="dots"` have low contrast (`NativeToolUi.kt` 369 to 370, 1337).
- Dark: on a hero card, a button with no fill shows no container. The outline of an outlined button has the contrast 1.0 to 1 with the hero card, and a tonal button has the color of the hero card. The text of the button reads well.
  The host gives a progress track on a hero card the surface color (`NativeToolUi.kt` 368 to 370), but it does not change the border of a button.
  The kit keeps each outlined and tonal control off a hero card (look rule 8 of `kit/README.md`), so no saved plan or look fixture shows this limit now.
- A shape of the muted role has the alpha 64 (`SurfaceDrawing.kt`, `MUTED_SHAPE_ALPHA`). The orbit of `lesson` was a muted line with the contrast 1.5 to 1 (light) and 1.6 to 1 (dark). It now has the role `ink`, and a look rule of the kit keeps lines out of the muted role.
- The faces of a model keep their hex colors, because the shading of a face needs a hex color.

| Measure | Before (`c92230b`) | After (`6715b30`) | After the round before the review (`f614e9f`) | After the second round (`403c573`) |
| --- | --- | --- | --- | --- |
| Run-time text | 46580 bytes | 46662 bytes (bar 48500) | 46662 bytes | 46662 bytes |
| Largest source | `test/cards.plan`, 53628 bytes | `test/cards.plan`, 53591 bytes (bar 60000) | `test/cards.plan`, 53650 bytes. The new fixtures: 49705 and 48598 bytes | `test/cards.plan`, 53650 bytes. `look-deck-es`: 48616 bytes |
| Kit tests | 289 pass | 315 pass | 319 pass | 320 pass |

### The add that failed one time

In `appfile-look-r2-pre`, a press of "Aggiungi" right after the finish button showed "Не удалось выполнить действие инструмента. Сохранённые данные доступны." and added no row.
The cause is a race in the host, not in the kit. The baseline list app `showcase-lang-shopping-it` shows it too.

- The finish button goes when the host accepts the final live value and saves it as state version V (`NativeToolUi.kt` `NativeLiveDraft.complete`).
- The screen takes the state version of an action from the record that the shell model publishes (`ToolUi.kt` `MiniAppContent`, `current`).
  The shell model publishes V later: a watcher of the store reads every saved app again (`ToolController.kt` `watchStore`, `loadSnapshots`), and then the screen composes the new record.
- A press in that interval sends V-1. The host refuses it with `java.lang.IllegalStateException: The mini-app data changed. Reopen it.` (`ArtifactCoordinator.current`), because the final live value set its quiet version to V (`committed`).
  `ToolController.run` shows the generic text for an exception with an English message, and it writes no log.

A temporary device test pressed "Aggiungi" right after the end of the finish button, 20 times for each app and theme (80 presses in each loop):

| Loop | Store of the shell | Presses that failed |
| --- | --- | --- |
| `appfile-addloop-3` | the list app only | 0 of 80 |
| `appfile-addloop-4` | the 28 apps of the look run, as in `picturesOfEachPlan` | 3 of 80: 2 of 40 in `mock-shopping-it`, 1 of 40 in the baseline app |
| `appfile-addloop-5`, publication held | the 28 apps. The test held the publication of the shell model until the press | 4 of 4: the same error text as in `appfile-look-r2-pre`, the typed text in the field, and no row |
| `appfile-addloop-5`, with the wait | the 28 apps. Each press waits for the condition below | 0 of 80 |

Each failed press showed the error in 24 to 28 ms. In loops 3 and 4, each row took at least 30 ms in the baseline app and at least 56 ms in the kit app. So the host refused the press before the app ran.
On the state of each natural failure, the same add through `ToolEngine` at V added the row. In the forced probe, the same add at V-1 threw the exception above, and at V it added the row.
The helper of `AppFileLookTest` (`published`, `902a904`) now presses an action after a live value only when the shell model publishes the stored state version and two frames compose it.
A person can see the same error when a press comes in that interval after the finish button or the action key of the keyboard. The typed text stays, and a second press adds the row.

### Runs of Task 11b-2b

| Label | Revision | Observed | Test APK `lastUpdateTime` before and after |
| --- | --- | --- | --- |
| `appfile-look-spike-a8` | `62ea185+dirty` (the changes of `79170cf` and of `3c1b757` before the stop dot) | failed: the inset of 16 dp in `AppFileLookTest.drawn` was larger than the timer canvas, and the bar at the share 0 had one color | `06:41:12`, `07:32:55` |
| `appfile-look-spike-a8b` | `62ea185+dirty` | passed. The inset is now at most a third of the canvas height, and the stop dot gives the bar a second color | `07:32:55`, `07:35:30` |
| `appfile-look-pre` | `2705ed4` | passed (`picturesOfEachPlan`), 66.1 s | `07:35:30`, `07:41:21` |
| `appfile-look-all` | `2705ed4+dirty` (the two documents) | passed, 37 tests (`AppFileProofTest` and `AppFileLookTest`), 182.0 s. Under the status bar, each picture is the same as in `appfile-look-pre` | `07:41:21`, `07:44:37` |
| `appfile-look-all-2` | `6715b30` (the orbit in the role `ink`) | passed, 37 tests, 184.6 s. Against `appfile-look-all`, only the orbit of `lesson` changed | `07:44:37`, `07:56:12` |
| `appfile-look-r2-pre` | `f614e9f` (the hero card rule and the two new fixtures) | failed (`picturesOfEachPlan`). The light pass took each picture, also the four new ones. In the dark pass, the press of "Aggiungi" after the finish button of `mock-shopping-it` showed the host error "Не удалось выполнить действие инструмента" and added no row (`AppFileLookTest.kt` 153). The log has no cause, because the host keeps the exception only as the error text (`ToolController.kt` `run`). The same step passed in the light pass of this run, in `appfile-look-all-3`, and in `appfile-look-r2-solo`. The round changed no line of that plan or of the run-time | `07:56:12`, `08:17:03` |
| `appfile-look-all-3` | `f614e9f+dirty` (the two documents) | passed, 37 tests, 182.0 s | `08:17:03`, `08:20:34` |
| `appfile-look-r2-solo` | `d0cdf9f+dirty` (only `kit/test/lookrules.test.js`, which is not in the APK) | passed (`picturesOfEachPlan` alone), 65.9 s. A second check of the step that failed in `appfile-look-r2-pre` | `08:20:34`, `08:30:17` |
| `appfile-addloop-1` | `5528920+dirty` (the centered back and a temporary loop test) | stopped after 4 presses (4 rows): the loop waited for the notice of the host at the top of the screen, off the screen | `08:30:17`, `08:37:09` |
| `appfile-addloop-2` | `5528920+dirty` | stopped after 5 presses (5 rows): the loop waited for the finish button off the screen | `08:37:09`, `08:38:32` |
| `appfile-addloop-3` | `5528920+dirty` | 80 presses, 0 failed, 166.8 s. A store of one app | `08:38:32`, `08:44:56` |
| `appfile-addloop-4` | `5528920+dirty` | 80 presses, 3 failed, 181.5 s. A store of 28 apps | `08:44:56`, `08:49:04` |
| `appfile-addloop-5` | `5528920+dirty` | 4 presses with the publication held, 4 failed. Then 80 presses with the wait, 0 failed, 191.4 s | `08:49:04`, `08:54:25` |
| `appfile-look-all-4` | `902a904` (the centered back and the wait of the helper, with no temporary test in the tree) | passed, 37 tests, 196.0 s | `08:54:25`, `09:00:12` |
| `appfile-look-all-5` | `902a904` | passed, 37 tests, 190.1 s. The final run and the pictures of this section | `09:00:12`, `09:03:45` |

All times are on 2026-11-19. Each "before" time is the "after" time of the run before it, so no other session installed the test APK during these runs.
The pictures are in `evidence/alphy-ui/appfile-look-all-5/light/` and `dark/`, with the contact sheets `contact-light.png` and `contact-dark.png` and the pair sheet `pairs-light.png`. Git does not keep these files.

Command:

```text
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home python3 tools/compose_acceptance/run.py suite --serial emulator-5592 --label appfile-look-all-5 com.alpha.assistant.tools.appfile.AppFileProofTest com.alpha.assistant.tools.appfile.AppFileLookTest
```
