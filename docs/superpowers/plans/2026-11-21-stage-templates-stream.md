# Stage templates and stream: implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make each explain screen of the voice mode a filled template that fits the stage, repairs one line at a time, and draws line by line while its plan call runs.
**Architecture:** The kit reads a template file (named lines in a fixed order), expands it into an app file of today's kinds, and names each fault by its line. The host sends a spoken screen request to a short template prompt first, falls to the free plan on `outside`, and stores the template file. In phase 2 the plan call hands each text delta to a buffer, a conflated loop compiles the complete lines through the kit preview step, and the stage draws that in-memory preview until the stored app draws.
**Tech Stack:** Kotlin and Jetpack Compose, Android instrumentation tests on emulators, the kit in JavaScript with Bun tests and its sandbox bundle `kit.js`, `kit/modelcheck.js` with OpenAI `gpt-6.1-terra`.
**Spec:** `docs/superpowers/specs/2026-11-21-stage-templates-stream-design.md`

## Global Constraints

Paths: `app/…/` is `app/src/main/java/com/alpha/assistant/`, and `test/…/` is `app/src/androidTest/java/com/alpha/assistant/`.

- The design is the authority. A task that finds code which contradicts it stops and reports. It does not change the design or this plan.
- Template line grammar, in this fixed order, one item on each line:
  - First line: `scene(TITLE, lang=CODE, palette=NAME)`, `items(…)` or `cells(…)` with the same arguments. `palette` is optional (default `blue`), one of teal, green, blue, amber, orange, red, pink, purple, indigo, brown, graphite.
  - Control: `control = slider(LABEL, MIN, MAX, STEP, START)` or `control = choice(LABEL, [OPTION, …], START?)` for `scene`; `control = choice(LABEL)` for `items` (the options are the item labels); `control = slider(LABEL, 0, COUNT, 1, START)` for `cells`, COUNT 2 to 100.
  - Parts: `s1` to `s12` = `circle|rect|line|text|polygon(…)` with the optional named arguments `visible=` and `stroke=` (scene); `i1` to `i6` = `item(LABEL, SIGN, TEXT)` (items); no part lines (cells: the control draws the cells).
  - Last line: `card = card(TITLE, TEXT)` (scene, cells) or `card = card(TITLE)` (items: the card shows the text of the chosen item).
  - A part reads only `$/v`, the value of the control. A repair or change reply can also hold `delete NAME`.
  - Kit-written only: `$/v = VALUE` in the place of the control line stands for a left-out control in a stored file. VALUE is the start of the left-out control, or 0 when the reply had no control line. For a merge its name is `control`. A file with no card line shows no card, and that is not a fault.
  - A compile with left-out lines gives the faults of those lines and a definition. The host accepts it by its definition. A compile of its `kept` text gives faults `[]` and the same definition.
  - Example files: `kit/test/fixtures/template/ship.plan` (the example of design section 1), `word-parts.plan`, `majority.plan`. Task 1 writes them.
- Drawing size: 300 × 220 for each template, fixed from the first line.
- Content budget: title 30, control label 28, card title 32, card text 140, option 12, sign 14, shape text 28 characters; 6 options, 6 items, 12 shapes, 100 cells.
- 1.25 rule: a text up to floor(1.25 × cap) has no fault: 37, 35, 40, 175, 15, 17, 35. List caps get no factor. A text fault names the cap.
- Color names with no call (case does not matter): red #dc2626, orange #ea580c, yellow #facc15, green #16a34a, blue #2563eb, purple #9333ea, violet #7c3aed, pink #db2777, brown #a16207, gray and grey #64748b, cyan #0891b2, teal #0d9488, indigo #4f46e5, black `ink`, white `surface`. Kit tones and `#RRGGBB` stay valid.
- Fault form: `NAME: message`. NAME is the line name, or the template word for the first line.
- Each template call uses `CloudGenerationStage.APP_FILE_TEMPLATE`. The free plan keeps `APP_FILE`, and today's change keeps `APP_FILE_CHANGE`.
- Hashes: `PROMPT_HASH` stays `69759b168bc3` and covers System, Repair, Dialogue, Change. `TEMPLATE_HASH` covers Template, Template repair, Template change. `## Template rest` is in no hash.
- Gate 1a: no preview update above 1 s on an emulator at the phone or the fold size.
- Gate 1b: for each paired run with scripted deltas, controls on with the stream is at most 0.3 s later than with the stream off.
- Gate 2: 15 of 16 explain requests clean after at most one repair; 7 of 8 refusals; at most 2 of 16 explain requests refused; median first call of the 16 explain requests at most 5.75 s; 13 of 16 changes right by the controller's reading; 12 of 16 with only changed lines.
- Gate 3: each template at its caps, and at 1.25 times its text caps, shows with no scroll on the stage at the phone and the fold size.
- Gate 4: for each saved template file, each longer prefix keeps the drawing commands of the shorter one.
- Gate 2c: the host check of the app passes for at least 22 of the 24 model-made template files (the 8 change bases and the 16 explain outputs of the final run), on a device, with no paid call.
- Gate 5: the live marks `first_line_visible`, `first_part_visible`, `picture_complete`, `controls_on`, `preview_updates`, `previews_gone`, with no anti-goal seen.
- A failed gate goes to the owner before the work that depends on it.
- Model check budget: at most 150 calls of `gpt-6.1-terra` at the effort low. Each label starts with `tpl-`. `LIMIT` in `kit/modelcheck.js` goes from 539 to 689, and a second cap stops the 151st `tpl-` call. `evidence/modelcheck/spend.json` held 525 calls on 2026-11-21.
- Split of the 150 calls: tuning at most 56, change pilot at most 12, final run at most 80, 2 in reserve. The final run happens once.
- Live budget: at most 480 s of voice and 160 chat calls, for the one live check after both phases (Task 23). The 15 calls left of the template check stay with that check.
- Order of the owner, 2026-11-21 ("Faster"): one push to `main` after both phases, then the release. The stream tasks start when Task 5 is in, and they do not wait for the gates of phase 1. No check is dropped: the full device suite and the live check run once, on the final head.
- Only the controller runs a paid call, a merge, a commit, or a push. No push without the owner's yes.
- Commit gate: before each commit the controller runs the Slopo review and `bash .superpowers/sdd/2026-11-18-app-file-kit/scripts/gate.sh`, and resolves each blocking finding. The controller reads each patch itself, applies it, and commits it by task. No reviewer agent.
- Before a push the controller does the untangle review of the range against `origin/main` and records it.
- A paid live run has this form: `ALPHY_AI_PROVIDER=openai env -u ALPHY_AI_MODEL python3 tools/compose_acceptance/run.py live-check --serial <emulator> --label <label> --test com.alpha.assistant.ui.LiveDialogueStageTest#<method>`. The controller states the budget before the run and reports the spend after it. No key and no env line goes into a file, a log, or a report.
- An agent works in its own worktree, hands back a patch and the test output, and makes no commit.
- Device rule: a device task uses exactly one emulator of `emulator-5592`, `emulator-5594`, `emulator-5596`, never another device. Lane A uses `emulator-5592`, lane B `emulator-5594`, lane D `emulator-5596`, and the controller uses a free one.
- Every adb command names the serial: `adb -s <emulator> …`. Gradle: `ANDROID_SERIAL=<emulator> ./gradlew :app:installDebug :app:installDebugAndroidTest --console=plain`.
- Device test: `adb -s <emulator> shell am instrument -w -e class <class> com.alpha.assistant.test/androidx.test.runner.AndroidJUnitRunner`.
- Read `adb -s <emulator> shell dumpsys package com.alpha.assistant.test | grep lastUpdateTime` before and after each run. A changed value voids the run: install again and repeat it.
- Fold size: `adb -s <emulator> shell wm size 2480x2248` and `adb -s <emulator> shell wm density 520`. Reset both with `wm size reset` and `wm density reset` after the run. The phone size is the emulator default (width 411 dp).
- Kit checks: `bun test kit` shows `0 fail`. After a change of kit source or `kit/prompt.md`, run `bun kit/build.js` and hand back its outputs. `bun kit/build.js --check` exits 0.
- Tests come first. Prefer black-box tests: the voice path with scripted providers, and the kit through `compileStep` and `previewStep`. Do not write a unit test after the production code. Do not weaken an assertion.
- Second order of the owner, 2026-11-21 ("написать сразу, а потом разом все оттестировать и исправить"): the app code is written in batches, and its tests run after the batches are joined (section "Batches"). For an app task, "Step 2: run it and see it fail" is replaced: the agent writes the tests of the task with the assertions of the plan in the same pass as the code, and checks only that both compile. A kit task keeps its red run, because a kit test takes seconds. This is the owner's decision for this build: his standing policy says tests before code.
- In a fix round, an agent changes no assertion of a test without a reason that it reports to the controller. The controller reads the diff of each test file of each fix patch.
- AGENTS.md: no compatibility with earlier development versions. No migration of stored apps, and no reader of the spike fill form. Never delete stored user data silently.
- AGENTS.md behavior checks: the host keeps expected results and acceptance. Named requests and scenario presets stay in regression fixtures (`kit/test/fixtures/template*`). The template path stays on the shared generation path, whose app workflow and game workflow tests keep passing.
- No task adds a user string. The wait line and the reasons exist. A task that needs a new string stops and reports.
- Evidence outside Git (read-only): `/Users/egavrin/Documents/alphy/.claude/worktrees/app-file-parts/evidence/templatecheck/` holds `templates.js`, `fill-prompt.md`, `tight.txt`, `requests.json`, and `records/`.
- Scratch output goes to the agent's scratchpad, never into the worktree.

## Review Focus

1. A stored template file reaches a path that expects a free app file: typed change, restore, manual edit, rebase of a held change. Pinned by `aTemplatePreviousKeepsAMovedValue` (Task 1), and by `aTypedChangeOfAVoiceTemplateAppBecomesAFreePlan` and `aRestoreOfAnOlderTemplateRevisionKeepsTheMovedValue` (Task 5).
2. A left-out line comes back or a good line goes: `kept` holds a faulty line of a new screen, or a faulty changed line replaces its stored version. Pinned by `aShapeWithAFaultIsLeftOutAndTheOtherLinesStay` and `aFaultyChangedLineKeepsItsStoredVersion` (Task 1).
3. The preview loop and the final work share the transient slot: BUSY in the final compile or the host check, or a join that delays controls on. Pinned by `theFinalCompileNeverMeetsABusySlotFromThePreview` (Task 17) and gate 1b (Task 20).
4. Earlier parts move: between prefixes (UI tree, drawing), or at the switch from the last preview to the live app (bounds, canvas). Pinned by `eachLongerPrefixKeepsTheUiAndTheDrawingOfTheShorter` (Task 21) and `theLiveAppTakesTheBoundsAndTheDrawingOfTheLastPreview` (Task 16).
5. A frame stays when it must go: routing gives no screen, a whole failure, or a late frame after `gone`. Pinned by `aFrameBeforeRoutingGoesAwayWhenTheTurnIsNoScreen` (both gate settings), `aWholeFailureRemovesTheFrame`, and `aLateFrameAfterGoneNeverShows` (Task 17).

## Decisions of this plan

1. **Line forms.** The grammar of Global Constraints. The first line reads with `parseExpression` and each other line with `parseLine` as kind `component` or `delete`. The ship file, an items line, a cells line, `visible=$/v == "…"`, and `delete s2` parse today. Reason: the parser and the merge by name serve with no new syntax.
2. **Template or app file.** `isTemplateFile(text)` tests `TEMPLATE_HEAD` (`/^\s*(scene|items|cells)\s*\(/`) on the first line that is not empty. The expansion lives in the new module `kit/src/template.js`, before `compile` in `MODULES`. `compileAppFile` and `compileStep` branch on it, also for `previous.appFile`. Reason: each host path that compiles a stored `appFile` (restore, manual edit, rebase, change) then takes a template file with no host branch.
3. **Faults by line.** `template.js` gives the read, name, budget, color, and `$/v` faults. When the whole expansion has kit faults, a compile of the head, the control, and one part finds the owner of each fault. A fault about `need v`, and a smoke fault that a control never changes a value or that the screen does not show its change, belong to `control`. Reason: the owner is found with no change to the fault texts of the kit.
4. **Fixes with no call, and left-out lines.** The color names and the 1.25 rule of Global Constraints. A new screen leaves out a faulty shape, item, card, or control. `kept` never holds a left-out line, and it holds `$/v = START` in the place of a left-out control, so `kept` compiles clean again. A change keeps the stored version of a faulty line. Reason: both measured faults of the template check go away, the restore, rebase, and manual edit paths (they accept only status `ok`) take `kept`, and a change never loses a good stored line.
5. **Repair and change.** Repair input: task, plan, and faults (`templateRepairText`). Change input: request and stored template file (`templateChangeText`). Replies hold named lines and `delete NAME`. `mergeTemplate` puts a known name in place, a new part after the last part, removes a deleted line, and replaces the first line. Reason: one merge serves the repair, the change, and the stream.
6. **Prompt and hash.** `## Template`, `## Template repair`, and `## Template change` go after `## Change` in `kit/prompt.md`, and phase 2 adds `## Template rest`. `promptAsset()` adds `template`, `templateRepairTask`, `templateChangeTask`, `templateHead`, and later `templateRestTask`. Reason: the hash split of Global Constraints keeps the free records and fixtures valid, and the template records carry their own hash.
7. **Model check.** Sets T (tuning), U (held-out screens), V (held-out changes), and W (change pilot) in `kit/modelcheck.js`. Records keep `prompt: TEMPLATE_HASH`, outcomes are clean0, clean1, partial1, not, outside, and `table` prints one line for each label. The comment at `LIMIT` names the approval (design of 2026-11-21, section 7). Reason: the counter holds 525, so a separate cap of 150 is exact whatever the 14 older calls do.
8. **Preview.** Kit `previewStep({ text, plan, previous })` is a second bundle global. It runs no check run and gives `frame = { definition, ui, state, drawing, holds }` from an open event. Kotlin `StagePreviewFrame`, `StagePreview`, and `StagePreviews` live in `app/…/tools/StagePreview.kt`, held by `ToolController.stagePreviews`. The stage draws an uncommitted preview, or else the tool with a `drawingSeed` from its committed preview. Each update takes a new transient isolate with a 1000 ms deadline, and BUSY skips an update. Before the final compile the loop is closed, and the join waits only for an update in flight. The frame of the final compile shows while the host check runs, for a template and for a free plan. Gate 1a confirms this form. Reason: the controller owns the operation, and the seed reuses `drawingBefore`, so no empty frame shows.
9. **Text hand-off.** `onText: ((String) -> Unit)?` on `response` and `appFileCall`, and as the fifth parameter of `internal typealias AppFileCall`, which replaces each copy of today's function type. `TemplateStream.append` appends under a lock and signals a conflated channel. No update starts after `close`. Reason: the read thread never waits, and the end of the stream adds at most one update in flight.
10. **Gate "show before routing".** `ToolController.previewBeforeRouting`, an internal var with the default true. When false, `EarlyPlan` holds the newest frame until `use()`. Reason: one test sets both values in one process.
11. **Recorder marks.** The production trace gets the phases `stage_preview` (detail: holds), `stage_preview_gone`, and `controls_on` (after busy ends for an accepted screen run). The recorder computes `first_part_visible`, `picture_complete`, `controls_on`, `preview_updates`, and `previews_gone` from them. Reason: the trace clock is the one clock of goal 5.
12. **Scripted stream.** `Scripted.Deltas(parts: List<Pair<Long, String>>)` in `AgentScript.kt` and a `Deltas` reply in `ScriptedChat` send one text delta for each part after its delay and end with the joined text. A script with no template reply answers `outside("other")`. Reason: the existing scripts keep working, and a rehearsal streams with no paid call.

Design section 6 changes two anti-goals of `docs/superpowers/specs/2026-11-20-dialogue-stage-design.md`. The controller already made that change, so no task changes that file.

## Design points that the code contradicts

1. Gate 1 as one first task (design 7.1, 8.2): the 0.3 s bar compares runs with and without the stream, so it needs the stream. This plan splits it. Gate 1a (Task 14) measures one update and keeps the 1 s owner rule. Gate 1b (Task 20) holds the paired bar after the stream of a new screen. Gate 1a measures the kit preview step, so Task 13 comes before it. Tasks 13 and 14 need only the kit of Tasks 1 to 3, so they run beside phase 1: the owner gets the device cost early.
2. "The kit's preview step gives the drawing commands" (design 3): a definition holds no drawing commands, and a preview event of the run-time returns only the state (`kit/src/emit.js:287`, `kit/src/run.js:509`). The kit computes the frame from an open event.
3. Case 3, one more call for the missing lines (design 2): `appFileCall` returns `Failed` with no text (`app/…/tools/ToolEngine.kt:375-406`). Phase 1 treats a stopped call as a whole failure. Task 17 adds the call for the missing lines with the stream buffer.
4. "One constant holds this gate" (design 3): a Kotlin `const` cannot change in a test. The plan uses an internal var.
5. "No shape and no item is valid" (design 2, case 2) cannot apply to `cells`, which has no part lines. A cells file whose control is left out is a whole failure.

## File map

| File | Responsibility | Owner |
| --- | --- | --- |
| `kit/src/template.js` (new) | Read, check, fix, expand, merge, and preview a template file | 1, then 13 |
| `kit/src/compile.js` | Branch to templates; result fields `template`, `kept`, `expanded`, `leftOut`, `frame` | 1, then 13 |
| `kit/test/template-file.test.js` (new) | Template compile, faults, fixes, merge | 1 |
| `kit/test/fixtures/template/*.plan` (new) | Three examples and six cap files | 1 |
| `kit/prompt.md` | Template sections | 2, then 6 (controller tuning), then 13b (rest section) |
| `kit/prompt.js` | Hashes, template task texts | 2, then 13b |
| `kit/build.js` | Modules, bundle globals, template copies | 1 (the `MODULES` line only), 2, 13 (the bundle global), then 13b (the asset key) |
| `kit/test/compile.test.js` | Bundle globals pin | 13 |
| `kit/test/prompt.test.js` | Section and asset pins | 2, then 13b |
| `app/src/main/assets/appfile/kit.js`, `prompt.json` (generated) | Bundle and prompt asset | each kit task |
| `kit/modelcheck.js` | Template sets, records, numbers, caps | 3 |
| `kit/test/modelcheck-template.test.js` (new) | Model check outcomes and caps, no network | 3 |
| `kit/test/fixtures/template-tuning.json`, `template-pilot.json`, `template-bases/` (new) | Tuning set, change pilot, eight base files | 3, 6 |
| `kit/test/fixtures/template-holdout.json`, `template-changes.json`, `kit/test/template-sets.test.js` (new) | Held-out sets and their shape test | 7 |
| `app/…/cloud/CloudProvider.kt` | `APP_FILE_TEMPLATE` | 4 |
| `app/…/tools/appfile/AppFilePrompt.kt` | Template prompt fields | 4, then 17 |
| `app/…/tools/appfile/AppFileCompiler.kt` | Template fields, `preview`, `frame` | 4, 5, then 16 |
| `app/…/tools/agent/AppFileSteps.kt` | Template screen, change, stream, `AppFileCall` | 4, 5, 15, 17, 18 |
| `app/…/tools/agent/AppAgent.kt` | Host check mapping, call type | 4, 15, 17 |
| `app/…/tools/ToolEngine.kt` | Early template call, text hand-off | 4, then 15 |
| `app/…/tools/ToolController.kt` | Early plan, stage previews, gate var, marks | 4, 16, 17 |
| `app/…/tools/ToolGenerationDiagnostic.kt` | Allowed phases and details | 4, then 16 |
| `app/…/ui/ShellViewModel.kt` | Template task of a screen, `use()` of the early plan | 4, then 17 |
| `app/…/tools/agent/TemplateStream.kt` (new) | Delta buffer and conflated loop | 15 |
| `app/…/tools/StagePreview.kt` (new) | Preview frame, preview, holder | 16 |
| `app/…/ui/DialogueStage.kt` | Preview branch and drawing seed | 16 |
| `app/…/tools/ToolUi.kt` | `drawingSeed`, static stage render | 16 |
| `test/…/ui/StageTemplateWorkflowTest.kt` (new) | Voice screen by template | 4 |
| `test/…/ui/ShellLiveVoiceWorkflowTest.kt`, `DialogueStageWideTest.kt`, `ShellRobot.kt`, `DialogueRecorderTest.kt`, `SettingsConsentWorkflowTest.kt` | Default `outside("other")` for template calls | 4 |
| `test/…/ui/StageChangeWorkflowTest.kt` | Template change, change stream | 4, 5, 18 |
| `test/…/tools/AppFileChangeWorkflowTest.kt` | Typed change, restore, manual edit of a template app | 5 |
| `test/…/ui/StageTemplateFitTest.kt` (new) | Gate 3 | 9 |
| `test/…/ui/StageTemplateHostCheckTest.kt` (new) | Gate 2c | 8b |
| `kit/README.md`, `docs/voice-integration.md`, `docs/tool-integration.md` | Documentation | 10, then 22 |
| `test/…/tools/StagePreviewCostTest.kt` (new) | Gate 1a | 14 |
| `test/…/tools/AgentScript.kt` | `Scripted.Deltas` | 15 |
| `test/…/tools/AppFileCallWorkflowTest.kt`, `AppFileLoopWorkflowTest.kt` | Hand-off tests, fifth lambda parameter | 15 |
| `test/…/ui/StagePreviewWorkflowTest.kt` (new) | Stage preview | 16 |
| `test/…/ui/StageStreamWorkflowTest.kt` (new) | Stream of a new screen | 17 |
| `test/…/ui/DialogueRecorder.kt`, `DialogueDriver.kt`, `DialogueStageRehearsalTest.kt` | Marks, scripted deltas, new dialogues, rehearsals | 4 (driver default), then 10b (driver, rehearsal), then 19 |
| `test/…/ui/LiveDialogueStageTest.kt` | One method for each new dialogue and for the no-screen probe | 10b, then 19 |
| `test/…/ui/StageStreamCostTest.kt` (new) | Gate 1b | 20 |
| `kit/test/template-grow.test.js` (new) | Gate 4 | 21 |

`app/src/main/res/values/strings.xml`: no task changes it.

## Lanes and order

Phase 1 (templates). It works alone. It gets no push of its own (the order of 2026-11-21 in Global Constraints).

| Lane | Tasks in order | Starts after |
| --- | --- | --- |
| K (kit) | 1, 2, 3 | — |
| A (app, `emulator-5592`) | 4, 5, 10 | 2 |
| D (device gate, `emulator-5596`) | 9, 14, 10b, then 8b | 9 after 4; 14 after 13; 10b after 5; 8b after 8 |
| P (paid, controller) | 6, then 8 | 6 after 3; 8 after 7 |
| H (held-out author) | 7 | 6 |
| Controller | 12 | 5, 8, 8b, 9, 10, 10b |

Phase 2 (stream). Tasks 13 and 14 (the device cost, gate 1a) run beside phase 1. Tasks 15 and 16 start when Tasks 5 and 14 are in. Task 12 is a check point, and no task waits for it.

| Lane | Tasks in order | Starts after |
| --- | --- | --- |
| K | 13, 21, 13b | 13 after 3; 13b after 8 and 13 |
| D (`emulator-5596`) | 20 | 17 and 19 |
| A (`emulator-5592`) | 15, 17, 18, 22 | 15 after 5 and 14; 17 after 13b, 15, and 16 |
| B (`emulator-5594`) | 16, then 19 | 16 after 5 and 14; 19 after 10b and 17 |
| Controller | 23, 24, 25 | 8, 8b, 9, 12, 18, 19, 20, 21, 22 |

Task 13 and Task 6 both lead to new generated assets (`kit.js`, `prompt.json`). The controller runs `bun kit/build.js` after it applies each of them.

Shared files go in the order of the File map. Two parallel tasks never own the same file: Tasks 15 and 16, and Tasks 18 and 19, have disjoint file lists.

### Batches

The owner's second order of 2026-11-21 groups the writing. The tasks stay the specification: their interfaces, their tests, and their bars. The "Waits for" lines now give the order of the test runs and of the gates, not of the writing. Each batch is one Opus agent in its own worktree, and each file has one writer.

| Batch | Tasks | Files it owns | Emulator |
| --- | --- | --- | --- |
| C (shared names, first) | the names that batches A, B, and T share | the enum value `APP_FILE_TEMPLATE` in `app/…/cloud/CloudProvider.kt`; `app/…/tools/StagePreview.kt` (whole); `stagePreviews`, `previewBeforeRouting`, and `previewStream` in `app/…/tools/ToolController.kt`; `Scripted.Deltas` in `test/…/tools/AgentScript.kt` | none (compile only) |
| K (kit) | 1 alone (it runs), then 2, 3, 13, 21 in one pass with their red runs; 13b after gate 2b | `kit/**` and the generated assets | none |
| A (app) | 4 and 5 first (hand back), then 15, 17, 18, and the host half of 16 (`AppFileCompiler.preview`, the trace phases, `controls_on`) | each production file of the File map that is not in batch B or C, `TemplateStream.kt`, and the tests `StageTemplateWorkflowTest`, `StageStreamWorkflowTest`, `StageChangeWorkflowTest`, `AppFileChangeWorkflowTest`, `AppFileCallWorkflowTest`, `AppFileLoopWorkflowTest`, and the template default of the providers in `ShellLiveVoiceWorkflowTest`, `DialogueStageWideTest`, `ShellRobot`, `SettingsConsentWorkflowTest` | `emulator-5592` |
| B (stage) | the stage half of 16, and the gate tests of 9, 8b, 14, 20 | `app/…/ui/DialogueStage.kt`, `app/…/tools/ToolUi.kt`, and the tests `StagePreviewWorkflowTest`, `StageTemplateFitTest`, `StageTemplateHostCheckTest`, `StagePreviewCostTest`, `StageStreamCostTest` | `emulator-5594` |
| T (test harness) | 10b, 19, and the template default of `ScriptedChat` | `test/…/ui/DialogueDriver.kt`, `DialogueRecorder.kt`, `DialogueRecorderTest.kt`, `DialogueStageRehearsalTest.kt`, `LiveDialogueStageTest.kt`, and the rest of `test/…/tools/AgentScript.kt` | `emulator-5596` |

Order:

1. Batch C lands first as one small patch that compiles for the app and for the tests. The controller commits it, and batches A, B, and T start from that commit.
2. Batch K does not wait for batch C. The paid tuning (Task 6) and gate 1a (Task 14) need only the kit, so they start when the kit is in.
3. Batch A hands back after Tasks 4 and 5. The controller tests the template path on a device while the same agent writes the stream tasks. So phase 1 is proved alone before the stream is on top of it.
4. The controller applies the batches, runs the kit tests and each device class, and runs each failure alone before it sends it back. A fix goes to the agent that wrote the batch, with its context. That agent first moves its worktree to the joined commit that the controller names.
5. For each test that pins a requirement of the owner (`theFirstCompleteLineShowsAFrame`, `aFaultyLineAppearsAfterItsRepairAndTheOthersStay`, `aFrameBeforeRoutingGoesAwayWhenTheTurnIsNoScreen`), the agent shows one deliberate break of the code that makes the test fail, then removes the break.
6. Then the gates, the paid checks, the documents (Tasks 10 and 22), and Tasks 12, 23, 24, and 25, as written.

Step forms:
- Agent build tasks: write the failing test, run it and see it fail, implement, run it and see it pass, hand back.
- Agent gate tasks (9, 14, 20, 21): write the measure, prove that it detects a fault, run it, judge against the bar, hand back.
- Documentation tasks (10, 22): list the facts, search, write, search again, hand back.
- Controller tasks: prepare, run, judge against the bar, failure path, record.

## Phase 1: templates

### Task 1: Kit template file

**Lane:** K · **Waits for:** none · **Who:** agent

**Files:**
- Create: `kit/src/template.js`, `kit/test/template-file.test.js`, and in `kit/test/fixtures/template/`: `ship.plan`, `word-parts.plan`, `majority.plan`, `scene-caps.plan`, `items-caps.plan`, `cells-caps.plan`, `scene-caps125.plan`, `items-caps125.plan`, `cells-caps125.plan`.
- Modify: `kit/src/compile.js`, and in `kit/build.js` only the `MODULES` line (`"template"` before `"compile"`), because the bundle test of `kit/test/compile.test.js` builds every module that `compile.js` imports.
- Test: `kit/test/template-file.test.js`, and all of `bun test kit`.

**Interfaces:**
- Consumes: `parseExpression(text)`, `parseLine(raw)`, `show(node)` (`kit/src/dsl.js`); `compileAppFile(text, previous)`, `compileStep({ text, plan, read, previous, cap })`, `readPlanReply`, `linesOf` (`kit/src/compile.js`); `TONES`, `HEX` (`kit/src/core.js`).
- Produces in `kit/src/template.js`: `TEMPLATE_HEAD`, `BUDGET`, `COLOR_NAMES`, `isTemplateFile(text): boolean`, `readTemplate(text): { word, lines: [{ name, raw, tree }], faults: string[] }`, `mergeTemplate(stored, reply): string`, `expandTemplate(read, leftOut: Set<string>): string`.
- Produces in the `compileAppFile` and `compileStep` results of a template: `template` (the word), `kept` (the text to store), `expanded` (the app file of the expansion), `leftOut` (line names). `definition` is null only on a whole fault. A template `previous.appFile` is expanded before the change rules run.

- [ ] **Step 1: Write the failing tests** in `kit/test/template-file.test.js`:
  - `theThreeExamplesCompileClean`: faults `[]`, definition not null, `kept` equals the input, one surface of 300 × 220.
  - `aShapeWithAFaultIsLeftOutAndTheOtherLinesStay`: ship with `s2` x = `"a"` gives one fault that starts with `s2: `, a definition with the shapes of s1 and s3 only, and a `kept` with no `s2` line.
  - `aControlThatChangesNothingIsLeftOut`: a slider that no part reads gives a fault `control: …` that says it changes nothing, a definition with no slider, `$/v` at START, and a `kept` with `$/v = START` in the place of the control line.
  - `keptCompilesCleanAgain`: for a left-out shape, a left-out card, and a left-out control, `compileAppFile(kept)` gives faults `[]` and a definition equal to the definition of the first compile.
  - `aKitFaultOfTheExpansionNamesItsLine`: a polygon with 3 numbers gives a fault that starts with `s2: `, not with a need name.
  - `wholeFaults`: an unknown palette gives a `scene: ` fault and definition null; all shapes faulty gives null; items with no valid item gives null; cells with a faulty control gives null.
  - `colorNamesNeedNoCall`: `"orange"`, `"Green"`, `"grey"`, `"black"`, `"white"` give faults `[]` and the colors #ea580c, #16a34a, #64748b, `ink`, `surface`. `"sky"` gives a fault that names the line and the kit colors.
  - `textsUpToOneAndAQuarterOfTheirCaps`: title 37 has no fault, and 38 gives a `scene: ` fault that names cap 30. The same pairs for card text 175/176 (cap 140), option 15/16 (cap 12), sign 17/18 (cap 14), shape text 35/36 (cap 28). 7 options, 7 items, 13 shapes, and COUNT 101 each give a fault.
  - `aPartReadsOnlyTheControlValue`: `$/x` in s1 gives a fault `s1: `.
  - `namesAndOrder`: `x1 = rect(…)` gives an `x1: ` fault, a second `s1` gives a fault on that line, and a reply with the card before the shapes gives the same definition as the fixed order.
  - `aRepairReplyMergesByName`: `compileStep({ text: "s2 = <fixed>", plan: <ship with a bad s2>, read: true })` gives `kind: "plan"`, the ship text with the fixed `s2` in its place, and faults `[]`.
  - `aChangeReplyMergesAndDeletes`: the reply `s4 = circle(…)`, `delete s3`, `card = card(…)` on ship gives the order head, control, s1, s2, s4, card. A reply with a new first line replaces the head.
  - `aFaultyChangedLineKeepsItsStoredVersion`: a change reply with a bad `s2` and a `previous` gives a `kept` with the stored `s2` and a fault `s2: `.
  - `aTemplatePreviousKeepsAMovedValue`: ship plus `s4`, compiled with `previous` = ship, its schema, and the state `v = "Гвоздь"`, keeps `v = "Гвоздь"`. The `expanded` text of ship with the same `previous` also keeps it.
  - `outsideStillReads`: `outside("other")` gives `{ kind: "outside", word: "other" }`.
  - `theCapFixturesCompileClean`: the six cap files give faults `[]`. Each text is at its cap (or at floor(1.25 × cap) in the `caps125` files), and each list is at its cap.
  - `aFreeAppFileIsNotATemplate`: each `kit/test/*.plan` gives `isTemplateFile` false.
- [ ] **Step 2: Run** `bun test kit/test/template-file.test.js` and see the tests fail (no module).
- [ ] **Step 3: Implement.**
  - `template.js` checks each line (name, kind, budget, color, `$/v`) and expands the valid lines: one `app(TITLE, lang=…, preset="clean", palette=…)` header, the control need or `$/v = START`, one drawing need of 300 × 220 before the control, and the card need. The spike `expand` in `evidence/templatecheck/templates.js` is a reference for the needs of items (`find($/items, $label == $/v)`) and cells (grid and caption "K / N = P%").
  - When the whole expansion has kit faults, compile the head, the control, and one part for each part line to find the owner of each fault. A fault about `need v`, and a smoke fault that a control never changes a value or that the screen does not show its change, belong to `control`. Leave out each owner and compile again once. A fault that remains with no owner is a whole fault.
  - `compileAppFile` and `compileStep` branch on `isTemplateFile` of the text, the plan, or `previous.appFile`.
  - `mergeTemplate` puts a line with a known name in its place, puts a new part line after the last part line, removes the line of `delete NAME`, and replaces the first line with a new first line.
- [ ] **Step 4: Run** `bun test kit` and see `0 fail`.
- [ ] **Step 5: Hand back** the patch and the test output. No commit.

### Task 2: Template prompt, bundle, and hashes

**Lane:** K · **Waits for:** 1 · **Who:** agent

**Files:**
- Modify: `kit/prompt.md` (add `## Template`, `## Template repair`, `## Template change` after `## Change`), `kit/prompt.js`, `kit/build.js`, `kit/test/prompt.test.js`.
- Generated by `bun kit/build.js`: `app/src/main/assets/appfile/kit.js`, `app/src/main/assets/appfile/prompt.json`, `app/src/androidTest/assets/appfile/template/*.plan`.

**Interfaces:**
- Consumes: Task 1 exports; `taskText`, `repairText`, `changeText`, `PROMPT`, `PROMPT_HASH` (`kit/prompt.js`); `MODULES`, `promptAsset()`, `planCopies`, `outputs()` (`kit/build.js`).
- Produces: `PROMPT_HASH` over System, Repair, Dialogue, Change; `TEMPLATE_HASH` over the three template sections; `templateRepairText({ task, plan, faults })`; `templateChangeText({ request, plan })`; `promptAsset()` keys `system`, `repair`, `dialogue`, `change`, `repairTask`, `changeTask`, `template`, `templateRepairTask`, `templateChangeTask`, `templateHead` (`TEMPLATE_HEAD.source`); `template` before `compile` in `MODULES`; copies of `kit/test/fixtures/template/*.plan` in `app/src/androidTest/assets/appfile/template/`.

- [ ] **Step 1: Write the failing tests** in `kit/test/prompt.test.js`:
  - `theFreeHashStays`: `PROMPT_HASH === "69759b168bc3"`.
  - `theTemplateHashIsItsOwn`: 12 hex characters, not `PROMPT_HASH`, and different for a copy with one changed template word.
  - The section pin becomes `["System", "Repair", "Dialogue", "Change", "Template", "Template repair", "Template change"]`, and the asset pin becomes the 10 keys above.
  - `eachTemplateExampleCompilesClean`: each fenced example of `## Template` compiles through `compileStep` with faults `[]`. At least one example for each template word, and at least one `outside("…")` reply.
  - `theTemplateSectionStatesTheBudget`: it holds 30, 28, 32, 140, 12, 14, and the list caps 6, 12, 100, each read from `BUDGET`.
  - `theTemplateTasksHaveTheirPlaceholders`: `templateRepairTask` holds `{{task}}`, `{{plan}}`, `{{faults}}`, and `templateChangeTask` holds `{{request}}`, `{{plan}}`.
  - `theBundleReadsATemplate`: evaluate the bundle text, call `globalThis.compileStep` on ship, and get a plan with faults `[]`.
- [ ] **Step 2: Run** `bun test kit/test/prompt.test.js` and see the new tests fail.
- [ ] **Step 3: Implement.**
  - `## Template`: the grammar and the order of Global Constraints, the budget, the colors (kit tones and `#RRGGBB`), and formulas of `$/v`. A request to learn or practice with a check, a timer, a list, a form, or a game gets `outside("learn")`, and any other miss gets `outside("other")`. Give three short examples. Adapt the words of `evidence/templatecheck/fill-prompt.md` and `tight.txt` to the lines form.
  - `## Template repair`: reply with only the named lines, each complete and with its name.
  - `## Template change`: reply with only the lines that change, each with its name, and `delete NAME` for a part that goes. Reply `outside("other")` when a template cannot hold the change.
  - Compute `PROMPT_HASH` over the four free sections in their order, and `TEMPLATE_HASH` over the three template sections.
- [ ] **Step 4: Run** `bun kit/build.js`, `bun test kit`, and `bun kit/build.js --check`, and see `0 fail` and exit 0.
- [ ] **Step 5: Hand back** the patch with the generated files and the test output. No commit.

### Task 3: Model check for templates

**Lane:** K · **Waits for:** 2 · **Who:** agent

**Files:**
- Modify: `kit/modelcheck.js`.
- Create: `kit/test/fixtures/template-tuning.json` (the 14 requests of `evidence/templatecheck/requests.json`), `kit/test/modelcheck-template.test.js`. The `expect` of each tuning item follows the owner's rule (a check, a timer, a list, a form, or a game is refused): `template` for each item of type explain; `outside` for `t-notes`, `t-stress`, and `t-minus`, which ask for a check or for tasks; `any` for `t-morse`, which asks for steps with no check. An item with `any` counts in no bar.

**Interfaces:**
- Consumes: `compileStep`, `TEMPLATE_HASH`, `PROMPT`, `taskText`, `templateRepairText`, `templateChangeText`, and in `kit/modelcheck.js` `openai(body, label)`, `count(label)`, `numbers`, `faultTable`.
- Produces:
  - Sets `T` (`template-tuning.json`), `U` (`template-holdout.json`), `V` (`template-changes.json`), `W` (`template-pilot.json`). V and W items are `{ id, base, request, expect: "lines" | "outside" }`, and `base` names a file of `kit/test/fixtures/template-bases/`. A set whose file is missing stops with a clear message.
  - `checkTemplate(item, { ask, model, effort })` and `checkTemplateChange(item, { ask, model, effort })` give a record `{ id, set, prompt, outcome, firstCallMs, calls, replied, kept, faults, leftOut, repeated }` with `prompt === TEMPLATE_HASH`.
  - Outcomes: `clean0`, `clean1`, `partial1` (lines left out, not clean), `not`, `outside`.
  - `templateNumbers(records)` gives `{ explainClean, refusalsRight, explainRefused, medianFirstCallMs, changesOnlyChanged }`.
  - `overLimit(log, label): boolean`, `LIMIT = 689`, `TEMPLATE_LIMIT = 150`.

- [ ] **Step 1: Write the failing tests** with a fake `ask` (no network, no spend file):
  - `aCleanFirstReplyIsClean0`; `aRepairedReplyIsClean1` (two calls, the second reply holds only the faulty line); `aLeftOutLineIsPartial1`; `aWholeFaultIsNot`.
  - `outsideOnALearnRequestIsRight` and `outsideOnAnExplainRequestCountsAsRefused`.
  - `aChangeThatRepeatsAStoredLineCountsIt`: a change reply with one unchanged stored line gives `repeated` 1.
  - `templateNumbersReadTheBars`: 16 explain and 8 learn records give the five numbers, and the median first call uses the 16 explain records only. A record of an item with `expect` `any` changes none of the five numbers.
  - `overLimitHoldsBothCaps`: a log of 689 entries is over for each label. A log of 150 `tpl-` entries is over for a `tpl-` label and not over for another label.
- [ ] **Step 2: Run** `bun test kit/test/modelcheck-template.test.js` and see it fail.
- [ ] **Step 3: Implement.** The template call sends `PROMPT["Template"]` as instructions and `taskText({ request, brief: "" })` as input. `firstCallMs` runs from the send to the reply end. One repair uses `templateRepairText`. A change sends `templateChangeText` of its base file. `count` calls `overLimit`. The CLI and `table` take the new sets.
- [ ] **Step 4: Run** `bun test kit` and see `0 fail`.
- [ ] **Step 5: Hand back** the patch and the test output. No commit.

### Task 4: Voice screen by template

**Lane:** A · **Waits for:** 2 · **Who:** agent (`emulator-5592`)

**Files:**
- Modify: `app/…/cloud/CloudProvider.kt`, `app/…/tools/appfile/AppFilePrompt.kt`, `app/…/tools/appfile/AppFileCompiler.kt`, `app/…/tools/agent/AppFileSteps.kt`, `app/…/tools/agent/AppAgent.kt`, `app/…/tools/ToolEngine.kt`, `app/…/tools/ToolController.kt`, `app/…/tools/ToolGenerationDiagnostic.kt`, `app/…/ui/ShellViewModel.kt`.
- Create: `test/…/ui/StageTemplateWorkflowTest.kt`.
- Modify (tests): `test/…/ui/ShellLiveVoiceWorkflowTest.kt` (`StageProvider`), `test/…/ui/DialogueStageWideTest.kt` (`StageShell.provider`), `test/…/ui/StageChangeWorkflowTest.kt` (`HeldChange`), `test/…/ui/ShellRobot.kt` (fixture provider), `test/…/ui/DialogueRecorderTest.kt`, `test/…/ui/SettingsConsentWorkflowTest.kt`, `test/…/ui/DialogueDriver.kt` (`ScriptedChat`).

**Interfaces:**
- Consumes: the `prompt.json` keys of Task 2; the kit result fields `template`, `kept`, `expanded`, `leftOut`; `AppFileSteps.plan(task, limits)`; `routeAppFile(…)`; `ChatToolReply.generationPrompt(userText, brief, replyText, sources, selectedText, host)`.
- Produces:
  - `enum class CloudGenerationStage { AGENT, APP_FILE, APP_FILE_CHANGE, APP_FILE_TEMPLATE }`, with `APP_FILE_TEMPLATE` in the app-file set at `CloudProvider.kt:317`.
  - `AppFilePrompt(system, dialogue, repairTask, changeTask, template, templateRepairTask, templateChangeTask, templateHead)`, `templateRepairText(task, plan, faults)`, `templateChangeText(request, plan)`, `isTemplate(appFile: String): Boolean`.
  - `AppFileCompilation(…, template: String?, kept: String?, expanded: String?, leftOut: List<String>)`.
  - `ScreenRun(val early: Deferred<AppFileOutcome>? = null, val template: String? = null)`.
  - `AppFileSteps.screen(templateTask: String, planTask: String, limits: AppFileLimits): AppFileOutcome`.
  - `ToolGenerationService.earlyPlan(key, template: String, prompt: String, session, limits, onPhase)` and `ToolController.startEarlyPlan(template: String, prompt: String, key: String, operationId: String): EarlyPlan?`.
  - `AppFileNames.TEMPLATE_CALL_START = "template_call_start"`, `TEMPLATE_OUTCOME = "template_outcome"` (details `clean0`, `clean1`, `partial1`, `outside`, `whole`, `failed`), and the fallback reasons `TEMPLATE_WHOLE` and `TEMPLATE_CHECK`, in `PHASES` and `safePhaseDetail`.
  - The template task: `generationPrompt(user.text, brief, decoded.text, sources, selectedText, host = "")`. The early template task: `generationPrompt(user.text, "", "", emptyList(), host = "")`.

- [ ] **Step 1: Write the failing tests** in `StageTemplateWorkflowTest` (voice path, scripted providers):
  - `aSpokenExplainScreenIsAStoredTemplate`: template reply = ship. The stage shows the app, the stored `appFile` equals ship, and no `APP_FILE` call runs.
  - `outsideFallsToTheFreePlanWithItsDialogueLine`: template reply `outside("learn")`. One `APP_FILE` call follows, its input holds the Dialogue line, and the stored app is the free plan.
  - `aFaultyLineGetsOneRepairOfThatLine`: ship with a bad `s2`, then a repair reply of `s2` only. Two template calls run, the repair input holds `s2: `, and the stored file holds the fixed `s2`.
  - `aLineThatStaysFaultyIsLeftOut`: the repair reply is still bad. The stored file has no `s2`, the canvas shows s1 and s3, and `template_outcome` has the detail `partial1`.
  - `aWholeFaultRunsTheFullGeneration`: the first line is bad twice. The voice says the wait line, the full generation runs, and no `APP_FILE` call runs.
  - `aStoppedTemplateCallRunsTheFullGeneration`: the template call fails (`Scripted.Fails`), and the full generation runs.
  - `aHostCheckFaultOfATemplateRunsTheFullGenerationWithNoRepairCall`: a clean compile and a host check fault. The full generation runs after one template call and no other app-file call.
  - `theEarlyPlanSendsTheTemplateFirst`: on a first turn the early call is `APP_FILE_TEMPLATE` with no brief and no Dialogue line, and the screen run uses its outcome with no second template call.
  - `theTemplateInputHoldsTheBriefAndTheExcerpts`: a later new screen with search results sends the brief and the excerpt text.
  - `aTypedChatRequestSendsNoTemplateCall`: a typed request sends only `APP_FILE`.
  - Update the existing scripted providers: each answers `APP_FILE_TEMPLATE` with `outside("other")` when its script has no template reply, and each plan call count counts `APP_FILE` only.
- [ ] **Step 2: Add only the enum value** `APP_FILE_TEMPLATE`, so the test module compiles. Run `StageTemplateWorkflowTest` and see it fail. Run the changed classes and see each pass (no template call runs yet).
- [ ] **Step 3: Implement.**
  - `screen`: send the template call. `outside` runs `plan(planTask, limits)`. A failed call gives the outcome that a failed plan call gives today.
  - Compile with `compileReply`. Template faults, or a reply that is neither a template file nor `outside`, get one repair with `templateRepairText` and `patch(plan, reply)`. A definition then gives `Clean(appFile = kept, …)`, and none gives `Fallback(TEMPLATE_WHOLE)`.
  - In `routeAppFile` a new screen with `ScreenRun.template` calls `screen`. A host check fault of a template outcome throws `ScreenFallback(TEMPLATE_CHECK)`.
  - `ShellViewModel` passes the template tasks to `ScreenRun` and `startEarlyPlan`. No other change.
- [ ] **Step 4: Run** `StageTemplateWorkflowTest`, the changed classes, `AppFileLoopWorkflowTest`, `AppFileChatWorkflowTest`, and `GameGenerationWorkflowTest`, and see each pass.
- [ ] **Step 5: Hand back** the patch and the test output with the `lastUpdateTime` values. No commit.

### Task 5: Template change

**Lane:** A · **Waits for:** 4 · **Who:** agent (`emulator-5592`)

**Files:**
- Modify: `app/…/tools/agent/AppFileSteps.kt` (`change`, the call in `routeAppFile`), `app/…/tools/appfile/AppFileCompiler.kt` if a field is missing.
- Test: `test/…/ui/StageChangeWorkflowTest.kt`, `test/…/tools/AppFileChangeWorkflowTest.kt`.

**Interfaces:**
- Consumes: `AppFilePrompt.templateChangeText`, `isTemplate`; `AppFileCompilation.expanded`, `kept`; `compiler.patch(plan, reply, previous)`, which merges template lines in the kit.
- Produces: `AppFileSteps.change(request: String, stored: AppFilePrevious, limits: AppFileLimits, template: Boolean): AppFileOutcome`. `routeAppFile` passes `template = request.screen != null && prompt.isTemplate(previous.appFile)`.

- [ ] **Step 1: Write the failing tests:**
  - `aSpokenChangeOfATemplateScreenSendsTheTemplateFile` (StageChangeWorkflowTest): stored ship with `v = "Гвоздь"`, a voice change. The call is `APP_FILE_TEMPLATE` with the request and the stored file. The reply `s4 = circle(…)` gives a stored file with `s4` before the card, and `v` stays `"Гвоздь"`.
  - `aDeleteLineRemovesThePart`: the reply `delete s3` gives a stored file with no `s3`.
  - `aChangeWithAFaultyLineKeepsTheStoredLine`: a bad `s2` after its one repair keeps the stored `s2`, and the other changed lines are saved.
  - `aTemplateOutsideRunsTodaysChangeOnTheExpansion`: the reply `outside("other")` gives one `APP_FILE_CHANGE` call whose plan is the `expanded` text, and the stored file is a free plan.
  - `aWholeFaultOfAChangeKeepsTheApp`: a faulty first line after the repair keeps the app at its revision, and the voice says the reason.
  - `anEmptyTemplateChangeIsEmpty`: a merge equal to the stored text gives `Fallback(AppFileNames.EMPTY)`.
  - `aTypedChangeOfAVoiceTemplateAppBecomesAFreePlan` (AppFileChangeWorkflowTest): a typed change sends no template call, runs `APP_FILE_CHANGE` on the expansion, and keeps the moved value.
  - `aRestoreOfAnOlderTemplateRevisionKeepsTheMovedValue` and `aManualEditOfATemplateFileCompilesIt` (AppFileChangeWorkflowTest): restore and manual edit of a template app through `ToolEngine` keep the value.
- [ ] **Step 2: Run** both classes and see the new tests fail.
- [ ] **Step 3: Implement.** With `template` true: the template change call, one repair by lines, and `kept` as the stored file. `outside`, or `template` false on a stored template file: today's change with `AppFilePrevious(expanded, stateSchema, state)`.
- [ ] **Step 4: Run** both classes and `StageTemplateWorkflowTest`, and see each pass.
- [ ] **Step 5: Hand back** the patch and the test output. No commit.

### Task 6: Gate 2a, tuning and change bases

**Lane:** P · **Waits for:** 3 · **Who:** controller

**Files:**
- Modify: `kit/prompt.md` (template sections only), and the generated assets.
- Create: `kit/test/fixtures/template-bases/*.plan` (8 files), `kit/test/fixtures/template-pilot.json`.

- [ ] **Step 1: Prepare.** Read `evidence/modelcheck/spend.json` and confirm 0 `tpl-` entries. Assign the call budget: tuning at most 56, change pilot at most 12.
- [ ] **Step 2: Run** `bun kit/modelcheck.js run tpl-tune1-terra-low T gpt-6.1-terra low`. Read each record and each fault. Edit only the template sections of `kit/prompt.md`, then run `bun kit/build.js` and `bun test kit`. Run `tpl-tune2-terra-low` once more if a fault class remains.
- [ ] **Step 3: Write the change pilot and the bases.** Copy the `kept` text of 8 clean outputs of the last round to `kit/test/fixtures/template-bases/<id>.plan` (each template word that has a clean output). Write 6 change requests on these bases in `kit/test/fixtures/template-pilot.json`. Run `bun kit/modelcheck.js run tpl-pilot-terra-low W gpt-6.1-terra low`, and edit `## Template change` once if needed.
- [ ] **Step 4: Failure path.** Fewer than 8 clean explain outputs, or a learn request that is filled in the last round: stop and report to the owner before Task 7.
- [ ] **Step 5: Record** the labels, the calls, the outcomes, and `TEMPLATE_HASH` in the ledger. Commit the prompt and the fixtures after the commit gate.

### Task 7: Held-out sets

**Lane:** H · **Waits for:** 6 · **Who:** agent (with no access to the prompt)

The agent must not read `kit/prompt.md`, `kit/prompt.js`, `kit/test/prompt.test.js`, `app/src/main/assets/appfile/prompt.json`, `kit.js`, or any model record. It reads Global Constraints, `kit/test/fixtures/template-bases/`, and `kit/test/fixtures/template-tuning.json` (to keep away from its subjects).

**Files:**
- Create: `kit/test/fixtures/template-holdout.json`, `kit/test/fixtures/template-changes.json`, `kit/test/template-sets.test.js`.

**Interfaces:**
- Produces: `template-holdout.json` with 24 items `{ id, type: "explain" | "learn", request }`; `template-changes.json` with 16 items `{ id, base, request, expect: "lines" | "outside" }`.

- [ ] **Step 1: Write the failing test** `kit/test/template-sets.test.js`: 16 explain and 8 learn items; unique ids; each request differs from each tuning request; 16 change items with 2 on each of the 8 bases; each base file exists; at least 13 items with `expect` `lines`.
- [ ] **Step 2: Run** `bun test kit/test/template-sets.test.js` and see it fail (no files).
- [ ] **Step 3: Write the sets.** New subjects in a spoken style, in Russian with at least 4 in English. A learn item asks to learn or practice with a check, a timer, a list, a form, or a game. An `outside` change asks for a thing that one picture, one control, and one card cannot hold.
- [ ] **Step 4: Run** the test and see it pass.
- [ ] **Step 5: Hand back** the patch and the test output. No commit.

### Task 8: Gate 2b, final model check

**Lane:** P · **Waits for:** 7 · **Who:** controller

- [ ] **Step 1: Prepare.** Confirm that `tpl-` calls so far plus 80 is at most 150.
- [ ] **Step 2: Run once** `bun kit/modelcheck.js run tpl-final-terra-low UV gpt-6.1-terra low`, then `bun kit/modelcheck.js table`. Read each change and mark it right or wrong.
- [ ] **Step 3: Judge** against the gate 2 bars of Global Constraints.
- [ ] **Step 4: Failure path.** One missed bar: stop, and report the numbers and the faults to the owner. No new tuning on this set, and no Task 11.
- [ ] **Step 5: Record** the table, the calls, and the `TEMPLATE_HASH` value. That value goes into the test of Task 13.

### Task 8b: Gate 2c, the host check of model-made template files

**Lane:** D · **Waits for:** 4, 8, 9 · **Who:** agent (`emulator-5596`) for the run, controller for the decision

Gate 2 runs in Bun, so it shows only the faults of the kit. The app also runs its own host check on the complete screen (the smoke test of the host, the language of the texts, a long decimal number, the presentation). A fault there sends the whole template to the full generation. This gate measures how often that happens, with no paid call.

**Files:**
- Create: `test/…/ui/StageTemplateHostCheckTest.kt`.

**Interfaces:**
- Consumes: the voice path with a scripted template reply (the form of `StageTemplateWorkflowTest`), and the fallback reason `TEMPLATE_CHECK` of Task 4.
- Input files (read-only): the 8 files of `kit/test/fixtures/template-bases/`, and the `kept` text of each clean explain record of `/Users/egavrin/Documents/alphy/.claude/worktrees/app-file-parts/evidence/modelcheck/tpl-final-terra-low/`. The agent pushes them with `adb -s <emulator> push` to the folder `tpl-hostcheck/` in the external files directory of the app. They do not go into the patch.

- [ ] **Step 1: Write the test** `eachModelMadeTemplatePassesTheHostCheck`. It is skipped when the folder is absent, so the full suite stays green. For each file: the scripted template reply is the file. Assert that the app is stored with that `appFile` and that no `TEMPLATE_CHECK` fallback ran. Print one line for each file with the outcome, and for a miss the host check reason.
- [ ] **Step 2: Prove that it detects a miss.** Run it once with one more file whose card text is in a different language than its `lang`, and see that file reported as a miss.
- [ ] **Step 3: Run** the class at the phone size.
- [ ] **Step 4: Judge.** The bar: at most 2 files miss. Failure path: the controller reports each reason to the owner before Task 11. A reason that one line owns becomes a fix task in lane K (the fault gets its line, so a repair or a left-out line serves it).
- [ ] **Step 5: Hand back** the patch, the lines of the run, and the `lastUpdateTime` values. No commit.

### Task 9: Gate 3, fit on the stage

**Lane:** D · **Waits for:** 4 · **Who:** agent (`emulator-5596`)

**Files:**
- Create: `test/…/ui/StageTemplateFitTest.kt`. It reads the assets `appfile/template/*-caps.plan` and `*-caps125.plan`.

- [ ] **Step 1: Write the test** `eachCapFileFitsTheStage`: for each of the 6 files, the scripted template reply is the file, and the voice path stores it. Then assert: the scrollable descendant of `dialogue_stage` (the column with a vertical scroll axis range) has the maximum 0, and the canvas, the control, and the whole card text are displayed above the strip. The output names the file and the size class.
- [ ] **Step 2: Run** it once on the free plan asset `appfile/shopping.plan` (today's screens scroll by 210 to 597 dp at the phone size), and see it fail. This proves that the scroll assertion detects an overflow.
- [ ] **Step 3: Run** the class at the phone size, then at the fold size (Global Constraints).
- [ ] **Step 4: Judge.** The bar is 12 of 12 (3 templates × 2 text levels × 2 sizes). Failure path: report each overflow in dp to the owner with two options, a lower cap or a smaller canvas height. Change no cap without the owner's yes.
- [ ] **Step 5: Hand back** the patch, the output of both runs, and the `lastUpdateTime` values. No commit.

### Task 10: Phase 1 documentation

**Lane:** A · **Waits for:** 5, 8 · **Who:** agent

**Files:**
- Modify: `kit/README.md` (a template file section), `docs/voice-integration.md` (section «Голосовой режим», lines 88 to 111), `docs/tool-integration.md` (one sentence after the stored-definition paragraph).

- [ ] **Step 1: List the facts** with their source: the grammar, order, budget, 1.25 rule, colors, faults by line, merge by name, `outside`, the two hashes (Tasks 1 to 3); prompt choice, repair, left-out lines, whole failures, spoken change, typed change of a template app (Tasks 4, 5); a voice screen stores a template file in `appFile`, and the kit expands it for each compile, restore, and manual edit.
- [ ] **Step 2: Search** the three files and confirm that each fact is absent or out of date.
- [ ] **Step 3: Write** the text in the style of each file.
- [ ] **Step 4: Search** again: each value and name matches the code, once.
- [ ] **Step 5: Hand back** the patch. No commit.

### Task 10b: New subjects of the phase 1 live check

**Lane:** D · **Waits for:** 5 · **Who:** agent (`emulator-5596`)

**Files:**
- Modify: `test/…/ui/DialogueDriver.kt` (the list of dialogues, and the `evidence` map of the goals), `test/…/ui/LiveDialogueStageTest.kt` (one method for each new dialogue), `test/…/ui/DialogueStageRehearsalTest.kt`.

**Interfaces:**
- Consumes: `Dialogue(name, steps, calls, adaptive)`, `ask(fixture, said, goal)`, and the `evidence` map in `DialogueDriver.kt` (near the list that starts with `Dialogue("pendulum", …)`). A goal is a free word. The goals `screen` and `search` exist. The goals `change` and `learn` are new.
- Produces: the goal `change` with its evidence (the step record says that the stored `appFile` of the stage app changed and is still a template file), and the goal `learn` with its evidence (the stage changed, and the stored `appFile` is no template file). The step record gets the field that each evidence reads.
- Produces: three dialogues `tpl-a`, `tpl-b`, `tpl-c` on subjects that no earlier run used (absent from `kit/test/fixtures/`, `kit/prompt.md`, and each earlier dialogue of the driver). Each has these steps in this order: a first request to explain a thing (goal `screen`), a spoken change of that screen (goal `change`), a request to learn a thing with a check (goal `learn`), and a request to explain a different thing (goal `screen`: the new screen replaces the shown one). In `tpl-b` the change asks for fresh facts (goal `search`). The methods are `LiveDialogueStageTest#tplA`, `#tplB`, `#tplC`. A dialogue holds no screen size: the controller sets the size before a run.

- [ ] **Step 1: Write the failing test** `eachTemplateDialoguePassesTheRehearsal` in `DialogueStageRehearsalTest`: each of the three dialogues runs with scripted voice and scripted chat (a template reply for each explain step, `outside("learn")` and a free plan for the learn step, changed lines for the change step) and passes the bar of the driver for each step.
- [ ] **Step 2: Run** the class and see it fail (no dialogue with these names).
- [ ] **Step 3: Write** the three dialogues and the goal `learn`.
- [ ] **Step 4: Run** `DialogueStageRehearsalTest` and `DialogueRecorderTest`, and see each pass.
- [ ] **Step 5: Hand back** the patch and the test output with the `lastUpdateTime` values. No commit.

### Task 11: Phase 1 live check (moved)

The owner chose one live check after both phases (Global Constraints, the order of 2026-11-21). Task 23 runs the dialogues `tplA`, `tplB`, and `tplC` of Task 10b with its own dialogues, in one budget. No task has this number.

### Task 12: Phase 1 check point

**Lane:** controller · **Waits for:** 5, 8, 8b, 9, 10, 10b · **Who:** controller

- [ ] **Step 1: Prepare.** Each patch of Tasks 1 to 10b is applied in the order of the File map and committed by its task.
- [ ] **Step 2: Run** `bun test kit`, `bun kit/build.js --check`, the free rehearsal (`DialogueStageRehearsalTest`, `StageTemplateWorkflowTest`), and each changed device class on one emulator. The full device suite runs once, in Task 24.
- [ ] **Step 3: Judge.** Each gate of phase 1 (2, 2c, 3) holds its bar.
- [ ] **Step 4: Failure path.** A failed check: fix it in the owning task, and repeat Step 2. A missed bar goes to the owner.
- [ ] **Step 5: Record** the commits and the gate results in the ledger. No push here.

## Phase 2: the stream

### Task 13: Kit preview step

**Lane:** K · **Waits for:** 3 · **Who:** agent

**Files:**
- Modify: `kit/src/template.js`, `kit/src/compile.js`, `kit/build.js` (the bundle also sets `globalThis.previewStep`), `kit/test/compile.test.js` (the bundle test defines `["compileStep", "previewStep"]`).
- Create: `kit/test/template-preview.test.js`.
- Generated: `kit.js`.

**Interfaces:**
- Consumes: `handle(input, app, now)`, `kitOf`, `screen`, `stateOf` (`kit/src/run.js`); `emit` (`kit/src/emit.js`); `settings.gate` (`kit/src/core.js`); Task 1 exports.
- Produces:
  - `previewStep({ text, plan, previous })` gives `{ kind: "preview", frame }` or `{ kind: "none" }`. `text` is the complete lines so far. `plan` is the stored template of a change, or null. `previous` holds the saved state of a change.
  - `frame = { definition, ui, state, drawing, holds }`. `drawing` is the `surfaces` object of the run-time reply to an open event. `holds` is `frame` (first line, maybe the control), `parts` (a shape, an item, or the cells), `card`, or `whole`.
  - The `compileStep` result gets `frame` with `holds: "whole"` when it has a definition, for a template and for a free plan.

- [ ] **Step 1: Write the failing tests** in `kit/test/template-preview.test.js`:
  - `theFirstLineAloneGivesAFrame`: the ship head gives `holds: "frame"`, one surface of 300 × 220, no drawing commands, and a UI with the title and the picture card.
  - `eachLineAddsItsPart`: head and control give the control in the UI. Adding `s1` gives `holds: "parts"` and 1 shape. Adding the card gives `holds: "card"`.
  - `aFaultyLineIsLeftOutOfThePreview`: a bad `s2` gives the shapes of s1 and s3.
  - `thePreviewRunsNoCheckRun`: a control that changes nothing stays in the preview UI, and `settings.gate` is `"smoke"` again after the call.
  - `anItemsFrameShowsOneItem` and `aCellsFrameDrawsAtTheControl`.
  - `aChangePreviewMergesOverTheStoredFile`: `previewStep({ text: "s4 = …", plan: ship, previous: { ship, schema, state v = "Гвоздь" } })` gives `v = "Гвоздь"` and the shape of s4.
  - `aFreePlanPreviewIsWhole`: a free plan gives `holds: "whole"`.
  - `anUnusableFirstLineGivesNone`: an unknown palette gives `{ kind: "none" }`.
  - `theCompileResultCarriesTheFrame`: `compileStep` on ship gives a `frame` whose drawing equals the drawing of `previewStep` on ship.
- [ ] **Step 2: Run** `bun test kit` and see the new tests fail.
- [ ] **Step 3: Implement.** The preview reads the lines, leaves out faulty lines with no repair, keeps the picture card at 300 × 220 from the first line, sets `settings.gate` off in `try`/`finally`, and computes the frame with an open event of the compiled data.
- [ ] **Step 4: Run** `bun kit/build.js`, `bun test kit`, and `bun kit/build.js --check`, and see `0 fail` and exit 0.
- [ ] **Step 5: Hand back** the patch with the generated files and the test output. No commit.

### Task 13b: Template rest section

**Lane:** K · **Waits for:** 8, 13 · **Who:** agent

**Files:**
- Modify: `kit/prompt.md` (add `## Template rest` only, after `## Template change`), `kit/prompt.js`, `kit/build.js` (the asset key), `kit/test/prompt.test.js`.
- Generated: `prompt.json`.

**Interfaces:**
- Consumes: `PROMPT`, `PROMPT_HASH`, `TEMPLATE_HASH`, `promptAsset()`.
- Produces: `templateRestText({ task, plan })`, the section `Template rest`, and the asset key `templateRestTask` with the placeholders `{{task}}` and `{{plan}}`. The section is in no hash.

- [ ] **Step 1: Write the failing test** `theRestSectionChangesNoHash` in `kit/test/prompt.test.js`: `PROMPT_HASH === "69759b168bc3"`, and `TEMPLATE_HASH` equals the value of the gate 2b records (the controller writes that value into this line before dispatch). The section pin gets `Template rest`, and the asset pin gets `templateRestTask`.
- [ ] **Step 2: Run** `bun test kit/test/prompt.test.js` and see it fail.
- [ ] **Step 3: Implement.** `## Template rest`: reply with only the lines that the plan does not hold yet, in the fixed order, each with its name.
- [ ] **Step 4: Run** `bun kit/build.js`, `bun test kit`, and `bun kit/build.js --check`, and see `0 fail` and exit 0.
- [ ] **Step 5: Hand back** the patch with the generated file and the test output. No commit.

### Task 14: Gate 1a, device cost of one update

**Lane:** D · **Waits for:** 13 · **Who:** agent (`emulator-5596`) for the run, controller for the decision

**Files:**
- Create: `test/…/tools/StagePreviewCostTest.kt`.

**Interfaces:**
- Consumes: `SandboxClient.evaluateScript(script, expression, deadlineMs, maxReturnBytes)`, the asset `appfile/kit.js`, `ToolSpec.availablePersisted`, `ToolContent(ui, state, enabled, …)`, `StaticDraftDrawing(spec, surfacesJson, maxHeight, …)`, the assets `appfile/template/ship.plan`, `word-parts.plan`, `majority.plan`.

- [ ] **Step 1: Write the test** `eachPreviewUpdateTakesAtMostOneSecond`. For each prefix by complete line of the three files: evaluate `previewStep` in a new transient isolate with the deadline 1000 ms, parse `frame.definition`, and draw `ToolContent(ui, state, false)` with `StaticDraftDrawing` at the stage canvas height until idle. Also time the evaluation of the expression `1` with the same script (the isolate start). Print one JSON line for each update with the start, evaluation, parse, and draw times. Assert that each update is at most 1000 ms.
- [ ] **Step 2: Run** it once with the deadline 1 ms and see it fail. This proves that it reads real times.
- [ ] **Step 3: Run** the class at the phone size and at the fold size.
- [ ] **Step 4: Judge.** The bar: no update above 1 s. Failure path: the controller reports the numbers to the owner before more stream work. When the isolate start is more than half of the median update, the controller also reports the form with one isolate for the stream as an option. No task builds that form without the owner's yes.
- [ ] **Step 5: Hand back** the patch, the JSON lines, the median and p90 for each size, and the `lastUpdateTime` values. No commit.

### Task 15: Text hand-off

**Lane:** A · **Waits for:** 5, 14 · **Who:** agent (`emulator-5592`)

**Files:**
- Modify: `app/…/tools/ToolEngine.kt` (`response`, `appFileCall`, `planCall`, `earlyPlan`), `app/…/tools/agent/AppFileSteps.kt` (the typealias and the constructor and `routeAppFile` types), `app/…/tools/agent/AppAgent.kt` (the type at line 103).
- Create: `app/…/tools/agent/TemplateStream.kt`.
- Test: `test/…/tools/AgentScript.kt` (`Scripted.Deltas`), `test/…/tools/AppFileCallWorkflowTest.kt`, `test/…/tools/AppFileLoopWorkflowTest.kt` (the lambdas at lines 103 and 423 take the fifth parameter).

**Interfaces:**
- Produces:
  - `internal typealias AppFileCall = suspend (CloudGenerationStage, String, String, AppFileLimits, ((String) -> Unit)?) -> AppFileReply`, used at each site of today's function type.
  - `response(…, onFirstText: (() -> Unit)? = null, onText: ((String) -> Unit)? = null)`: calls `onText` on the read thread for each text delta, after the size check.
  - `appFileCall(key, session, stage, instructions, input, limits, onText: ((String) -> Unit)? = null, onTiming)`.
  - `internal class TemplateStream { fun append(delta: String); val text: String; fun completeLines(): String; suspend fun collect(block: suspend (String) -> Unit); fun close() }`. `append` never blocks or suspends. `collect` runs `block` with the newest complete lines when it is free. No run starts after `close`, and `collect` returns when the run in flight ends.
  - `Scripted.Deltas(val parts: List<Pair<Long, String>>)`: a delay, then one text delta for each part, then the round end with the joined text.

- [ ] **Step 1: Write the failing tests** in `AppFileCallWorkflowTest`:
  - `eachDeltaReachesTheConsumerInOrder`: 12 parts give 12 strings in order, their join equals the reply, and `AppFileReply.Text` holds the whole text.
  - `aSlowCollectorDoesNotDelayTheReplyEnd`: a collector that suspends 2 s for each run. `plan_reply_end` comes less than 100 ms after the last part, and the collector runs fewer times than the lines. After `close`, no run starts, and a join returns when the run in flight ends.
  - `anUnfinishedLastLineIsNotComplete`: `completeLines()` of `"a\nb"` is `"a\n"`.
  - `theLimitsStayWithAConsumer`: `Scripted.Stalls` after the first text gives `Failed` at `totalMs`, with a consumer.
- [ ] **Step 2: Run** the class and see the tests fail.
- [ ] **Step 3: Implement.** `onText` runs on the read thread, so `append` only appends under a lock and sends to a conflated channel. Replace each copy of today's function type with `AppFileCall`. `planCall` and `earlyPlan` pass the fifth parameter to `appFileCall`.
- [ ] **Step 4: Run** `AppFileCallWorkflowTest`, `AppFileLoopWorkflowTest`, `StageTemplateWorkflowTest`, and `ShellLiveVoiceWorkflowTest`, and see each pass.
- [ ] **Step 5: Hand back** the patch and the test output. No commit.

### Task 16: Stage preview

**Lane:** B · **Waits for:** 5, 14 · **Who:** agent (`emulator-5594`)

**Files:**
- Create: `app/…/tools/StagePreview.kt`, `test/…/ui/StagePreviewWorkflowTest.kt`.
- Modify: `app/…/tools/ToolController.kt`, `app/…/ui/DialogueStage.kt`, `app/…/tools/ToolUi.kt`, `app/…/tools/appfile/AppFileCompiler.kt`, `app/…/tools/ToolGenerationDiagnostic.kt`.

**Interfaces:**
- Consumes: `CoveredToolSurface(…)` and its `lastDrawing` (`ToolUi.kt:229-276`); `StageApp` and `canvasHeight` (`DialogueStage.kt:97-145`); `ArtifactSnapshot(tool, sequence, surfaces, …)`; `generateAutomatic(…)`; `traceFor(id)`.
- Produces:
  - `internal data class StagePreviewFrame(val spec: ToolSpec, val ui: String, val state: String, val drawing: String?, val holds: String)`.
  - `internal data class StagePreview(val operationId: String, val frame: StagePreviewFrame, val toolId: String?, val revision: Int?)`. `toolId` is the changed tool, and `revision` is set when the app is stored.
  - `internal class StagePreviews { val current: StagePreview?; fun show(operationId: String, frame: StagePreviewFrame, toolId: String? = null); fun gone(operationId: String); fun committed(operationId: String, toolId: String, revision: Int); fun drawn(toolId: String) }`. Compose state on the main thread. A `show` after `gone` of the same operation is dropped.
  - `ToolController.stagePreviews`.
  - `AppFileCompiler.preview(text: String, plan: String?, previous: AppFilePrevious?): StagePreviewFrame?`: a new transient isolate, `APP_FILE_PREVIEW_MS = 1000`, and null on BUSY, timeout, or `none`. `AppFileCompilation.frame: StagePreviewFrame?`.
  - `CoveredToolSurface(…, drawingSeed: ArtifactSnapshot? = null)`: a seed that is not null replaces `lastDrawing` when it changes.
  - Trace phases `stage_preview` (detail `frame`, `parts`, `card`, `whole`), `stage_preview_gone`, `controls_on` in `phaseNames` and `safePhaseDetail`. The controller marks `controls_on` after `busy` becomes false for each accepted screen run, with or without a preview (gate 1b compares both).
  - Test seam `AppFileCompiler.previewHoldMs` (companion internal var, default 0): a value above 0 adds a busy wait of that many milliseconds inside the preview script, so the transient slot stays held. Tasks 17 and 20 use it.

- [ ] **Step 1: Write the failing tests** in `StagePreviewWorkflowTest` (the test calls `stagePreviews` directly):
  - `aPreviewDrawsOnTheStageWithTheControlOff`: `show` with the frame of ship. The stage shows the title and the canvas, the control is not enabled, the strip keeps its working status, and no store row or draft row exists.
  - `aLaterNewScreenReplacesTheShownApp`: a stored app is on the stage, and a frame of another operation replaces it.
  - `goneRemovesThePreviewAndDropsLateFrames`.
  - `theLiveAppTakesTheBoundsAndTheDrawingOfTheLastPreview`: `show`, then store the same file and call `committed`. Until the live drawing, the canvas label texts are those of the preview (the method of `SharedArtifactWorkflowTest.busyCards`). The title, control, and canvas bounds of the live app equal those of the last preview within 1 dp, at the phone and the fold size.
  - `aChangeOfTheSameToolSeedsTheLastPreviewDrawing`: a tool with live drawing A, a change preview with drawing B, then the commit. The canvas shows B until the new live drawing, and never A.
  - `controlsOnComesAfterBusyEnds`: the trace has `controls_on` after the accept, and the control is enabled at that mark.
- [ ] **Step 2: Run** the class and see it fail.
- [ ] **Step 3: Implement.** `DialogueStage` draws an uncommitted `current` with the `StageApp` layout, `ToolContent(enabled = false)`, and `StaticDraftDrawing` at `canvasHeight`. A committed `current` of the shown tool gives `StageApp` a `drawingSeed` (an `ArtifactSnapshot` with the frame drawing as `surfaces`). The first live snapshot calls `drawn`.
- [ ] **Step 4: Run** `StagePreviewWorkflowTest`, `DialogueStageWideTest`, `DialogueStageContentTest`, and `SharedArtifactWorkflowTest`, and see each pass.
- [ ] **Step 5: Hand back** the patch and the test output. No commit.

### Task 17: Stream of a new screen

**Lane:** A · **Waits for:** 13b, 15, 16 · **Who:** agent (`emulator-5592`)

**Files:**
- Modify: `app/…/tools/agent/AppFileSteps.kt`, `app/…/tools/agent/AppAgent.kt`, `app/…/tools/ToolController.kt`, `app/…/tools/appfile/AppFilePrompt.kt`, `app/…/ui/ShellViewModel.kt` (call `EarlyPlan.use()` when the routing classes a screen; no other change).
- Create: `test/…/ui/StageStreamWorkflowTest.kt`.

**Interfaces:**
- Consumes: `TemplateStream`, `AppFileCall`, `AppFileCompiler.preview`, `StagePreviews`, `EarlyPlan.use()` and `stop()`, `Scripted.Deltas`.
- Produces:
  - `ScreenRun(val early: Deferred<AppFileOutcome>? = null, val template: String? = null, val preview: ((StagePreviewFrame) -> Unit)? = null)`. `ToolController` sets `preview` to `stagePreviews.show(operationId, …)` with a `stage_preview` mark.
  - `ToolController.previewBeforeRouting: Boolean = true` and `ToolController.previewStream: Boolean = true` (internal vars; `previewStream = false` attaches no consumer, for gate 1b).
  - `AppFileOutcome.Clean(…, frame: StagePreviewFrame?)`.
  - `AppFilePrompt.templateRestText(task: String, plan: String)`.

- [ ] **Step 1: Write the failing tests** in `StageStreamWorkflowTest` (voice path, `Scripted.Deltas` of ship with 300 ms gaps):
  - `theFirstCompleteLineShowsAFrame`: the stage shows the title before the last delta of the second line, and `stage_preview` comes before `plan_reply_end`.
  - `eachLaterLineDrawsWhenComplete`: the canvas label texts grow from s1 to s3, and each earlier text stays.
  - `anUnfinishedLineNeverDraws`: a delta that ends inside `s3` draws no s3 until its line end.
  - `aFaultyLineAppearsAfterItsRepairAndTheOthersStay` (the owner's requirement 4): the deltas of ship with a bad `s2`, then a repair reply of `s2` only. The preview shows the texts of s1 and s3 and no s2. From the first frame to the stored app, the texts of s1 and s3 never leave the canvas, the title and the control keep their bounds within 1 dp, and exactly one repair call runs. After the repair the canvas shows s1, s2, and s3.
  - `theFinalCompileNeverMeetsABusySlotFromThePreview`: `AppFileCompiler.previewHoldMs = 800`, with the reply end during a held preview. The final compile runs after the join, no `javascript_busy` shows, and the app is stored.
  - `theFinalFrameShowsDuringTheHostCheck`: a template screen gets a `stage_preview` mark with the detail `whole` before the host check ends, and the card shows while the host check is held.
  - `aFrameBeforeRoutingGoesAwayWhenTheTurnIsNoScreen`: the routing gives a spoken answer. With `previewBeforeRouting = true` a frame shows and goes away with no error and no spoken line, and the trace has `stage_preview_gone`. With false no frame shows.
  - `withTheGateOffTheFrameWaitsForTheRouting`: with false the first frame shows after the routing classes a screen.
  - `aLateFrameAfterGoneNeverShows`.
  - `aWholeFailureRemovesTheFrame`: a faulty first line after the repair removes the frame, the voice says the wait line, and the full generation runs.
  - `aStoppedCallAsksOnceForTheMissingLines`: deltas, then a failure after `s2`. One rest call sends the lines so far, and the reply of `s3` and the card gives a complete stored app. When the rest call fails too, the frame goes away and the full generation runs.
  - `aFreePlanShowsItsCompiledPlanDuringTheHostCheck`: template `outside`, then a free plan. A `stage_preview` mark with the detail `whole` comes before the host check ends, and the stage shows it with the control off while the host check is held.
  - `typedChatShowsNoPreview`.
- [ ] **Step 2: Run** the class and see it fail.
- [ ] **Step 3: Implement.** In `screen`, inside one `coroutineScope`: launch `stream.collect { lines -> compiler.preview(lines, null, null)?.let(sink) }`, send the call with `stream::append`, then `close` and `join`, then the compile, the one repair by lines, and the outcome as they are now. A failed call with a complete first line sends one rest call (`templateRestText`) and merges its reply by name. `routeAppFile` calls `screen.preview` with `outcome.frame` after each clean screen outcome (template or free plan) and before `kitApp`. `EarlyPlan` holds the newest frame until `use()` when `previewBeforeRouting` is false, and `stop()` calls `gone`. Each fallback or cancel of a screen run calls `gone`.
- [ ] **Step 4: Run** `StageStreamWorkflowTest`, `StageTemplateWorkflowTest`, `ShellLiveVoiceWorkflowTest`, and `StagePreviewWorkflowTest`, and see each pass.
- [ ] **Step 5: Hand back** the patch and the test output. No commit.

### Task 18: Stream of a change

**Lane:** A · **Waits for:** 17 · **Who:** agent (`emulator-5592`)

**Files:**
- Modify: `app/…/tools/agent/AppFileSteps.kt` (`change`, template branch).
- Test: `test/…/ui/StageChangeWorkflowTest.kt`.

**Interfaces:**
- Consumes: `TemplateStream`, `AppFileCompiler.preview(text, plan = stored.appFile, previous = stored)`, `ScreenRun.preview`, `StagePreviews.show(…, toolId)`.

- [ ] **Step 1: Write the failing tests:**
  - `aSpokenChangeDrawsEachChangedLineOverTheShownScreen`: the deltas `s4 = …` and `card = …`. The canvas keeps s1 to s3 and adds s4, the control stays off, and the voice says the answer only at completion.
  - `theChangePreviewKeepsTheMovedValue`: `v = "Гвоздь"` in the preview.
  - `aDeleteLineRemovesThePartInThePreview`.
  - `aWholeFailureOfAChangeKeepsTheApp`: the preview goes, the app stays at its revision, and the voice says the reason.
  - `anOutsideChangeShowsNoStream`: no `stage_preview` mark.
- [ ] **Step 2: Run** the class and see the new tests fail.
- [ ] **Step 3: Implement** the stream loop that `AppFileSteps.screen` already uses (collect complete lines, preview, close, join) in the template branch of `change`, with the stored file as `plan` and the stored state as `previous`.
- [ ] **Step 4: Run** `StageChangeWorkflowTest` and `StageStreamWorkflowTest`, and see each pass.
- [ ] **Step 5: Hand back** the patch and the test output. No commit.

### Task 19: Recorder marks and the stream rehearsal

**Lane:** B · **Waits for:** 10b, 17 · **Who:** agent (`emulator-5594`)

**Files:**
- Modify: `test/…/ui/DialogueRecorder.kt`, `test/…/ui/DialogueDriver.kt` (`ScriptedChat`, the list of dialogues), `test/…/ui/DialogueStageRehearsalTest.kt`, `test/…/ui/DialogueRecorderTest.kt`, `test/…/ui/LiveDialogueStageTest.kt`.

**Interfaces:**
- Produces: in `report()` for each request, `first_line_visible` (speech end to the first `stage_preview` of any detail: the owner's moment of requirement 1), `first_part_visible` (the first `stage_preview` with the detail `parts`, `card`, or `whole`), `picture_complete` (the first `card` or `whole`, else `controls_on`), `controls_on`, `preview_updates` (count of `stage_preview`), `previews_gone` (count of `stage_preview_gone`). A `ScriptedChat` step reply can be `Deltas`.
- Produces: three more dialogues `tpl-d`, `tpl-e`, `tpl-f` on subjects that no earlier dialogue and no fixture used, with the steps and the goals of Task 10b, and the methods `LiveDialogueStageTest#tplD`, `#tplE`, `#tplF`. A dialogue holds no screen size.
- Produces: the new goal `answer` with its evidence (a spoken answer, and no app on the stage at the end of the step), the dialogue `no-screen`, and the method `LiveDialogueStageTest#noScreen`: six first turns that need no screen (two greetings, two short facts, two questions about the assistant), each in a new talk. Its report counts `previews_gone` for each turn.

- [ ] **Step 1: Write the failing tests:** `theStreamMarksComeFromTheTracePhases` (DialogueRecorderTest, a synthetic trace with known times and counts); `shipStreams`, `aNoScreenTurnCountsAGonePreview`, and `eachStreamDialoguePassesTheRehearsal` (DialogueStageRehearsalTest, with `Deltas` replies).
- [ ] **Step 2: Run** both classes and see the new tests fail.
- [ ] **Step 3: Implement** the marks from the trace phases on the trace clock, and write the four dialogues. The `Deltas` reply of `ScriptedChat` sends one text delta for each part after its delay, then ends the round with the joined text.
- [ ] **Step 4: Run** both classes and `LiveDialogueStageTest` in its free mode, and see each pass.
- [ ] **Step 5: Hand back** the patch and the test output. No commit.

### Task 20: Gate 1b, controls on with and without the stream

**Lane:** D · **Waits for:** 17, 19 · **Who:** agent (`emulator-5596`) for the run, controller for the decision

**Files:**
- Create: `test/…/ui/StageStreamCostTest.kt`.

- [ ] **Step 1: Write the test** `theStreamAddsAtMostThreeTenthsOfASecond`. For each of the three example files, 5 pairs of voice turns with the same scripted deltas: first text at 1.7 s, then even gaps to the end at 4.5 s. One run has `previewStream = true`, and one has false, in alternating order. Read `controls_on` from the recorder. Print each pair, and assert each difference is at most 300 ms.
- [ ] **Step 2: Run** it once with `AppFileCompiler.previewHoldMs = 500`, and see it fail. This proves that the pairs detect a delay.
- [ ] **Step 3: Run** the class at the phone size and at the fold size.
- [ ] **Step 4: Judge.** The bar: each pair at most 0.3 s. Failure path: the controller reports the pairs to the owner with the options (a longer gap between updates, or one isolate for the stream). No change without the owner's yes.
- [ ] **Step 5: Hand back** the patch, the pairs, and the `lastUpdateTime` values. No commit.

### Task 21: Gate 4, the picture only grows

**Lane:** K · **Waits for:** 13 · **Who:** agent

**Files:**
- Create: `kit/test/template-grow.test.js`.

- [ ] **Step 1: Write the test** `eachLongerPrefixKeepsTheUiAndTheDrawingOfTheShorter`. For each file in `kit/test/fixtures/template/` and `kit/test/fixtures/template-bases/`, and each pair of prefixes k and k + 1 by complete line:
  - Each surface command list of k is a prefix of the list of k + 1.
  - Each UI node of k is at the same path with the same type in k + 1. New nodes and new list items (choice options) come only after the existing ones.
  - The picture card has the same size from the first line.
  - When the file has no faulty line, the drawing of the `compileStep` frame equals the drawing of the last prefix.
- [ ] **Step 2: Run** it with a fixture in which the card line comes before the control, and see the UI assertion fail. This proves that the test detects a move.
- [ ] **Step 3: Run** `bun test kit/test/template-grow.test.js` on the real fixtures.
- [ ] **Step 4: Judge.** The bar: each pair of each file. Failure path: hand back the first command or node that moves. The controller then opens a fix task in lane K, before Task 23.
- [ ] **Step 5: Hand back** the patch and the test output. No commit.

### Task 22: Phase 2 documentation

**Lane:** A · **Waits for:** 18 · **Who:** agent

**Files:**
- Modify: `kit/README.md` (the preview step, the frame, `holds`, no check run, the rest section), `docs/voice-integration.md` (the stream, a frame before routing and its gate var, previews that go away, the canvas keeps the last preview, controls on, the change stream), `docs/tool-integration.md` (a paragraph after the drawing and smoke paragraphs: the stage preview is in memory with no store row, a new transient isolate for each update, the 1000 ms deadline, BUSY skips an update, and the loop ends before the final compile).

- [ ] **Step 1: List the facts** with their source (Tasks 13 to 19).
- [ ] **Step 2: Search** the three files and confirm that each fact is absent or out of date.
- [ ] **Step 3: Write** the text in the style of each file.
- [ ] **Step 4: Search** again: each value and name matches the code, once.
- [ ] **Step 5: Hand back** the patch. No commit.

### Task 23: Gate 5, live check

**Lane:** controller · **Waits for:** 12, 18, 19, 20, 21, 22 · **Who:** controller

- [ ] **Step 1: Prepare.** One free emulator. Budget: 480 s of voice and 160 chat calls. `#tplC` and `#tplF` run at the fold size: set it before each of these runs and reset it after (Global Constraints).
- [ ] **Step 2: Run** the free rehearsal (the cases of Tasks 10b and 19), then the paid dialogues `LiveDialogueStageTest#tplA` to `#tplF` with the label `tpl-live`, then the paid probe `LiveDialogueStageTest#noScreen` in the same budget (the form of Global Constraints). Run `#tplA` and `#tplD` first and look at their pictures before the other runs.
- [ ] **Step 3: Judge.** Look at each picture. Report `first_line_visible`, `first_part_visible`, `picture_complete`, and `controls_on` for each screen against the estimates (frame about 3.1 s, first shape about 3.5 s, picture about 5.0 s, controls on 6.4 to 7.8 s) and today's 8.4 to 12.3 s. Report `preview_updates`, and the count of frames that went away in the probe (design section 6).
- [ ] **Step 4: Failure path.** An anti-goal seen (a part that moves, a screen that starts again, a control on before the app is live, a frame with no end), or controls on later than today: report to the owner before Task 24.
- [ ] **Step 5: Record** the marks, the voice seconds, and the calls in the ledger.

### Task 24: Final checks and the push

**Lane:** controller · **Waits for:** 23 · **Who:** controller

- [ ] **Step 1: Prepare.** Each patch of Tasks 1 to 22 is applied in the order of the File map and committed by its task.
- [ ] **Step 2: Run** `bun test kit`, `bun kit/build.js --check`, and the full device suite once on the final head. Explain each failure: run it alone, and run it on `origin/main` when it fails alone.
- [ ] **Step 3: Do** the untangle review of the range against `origin/main` (read after a fetch) and record it.
- [ ] **Step 4: Failure path.** A blocking finding or a failure that this range causes: fix it in the owning task, and repeat Steps 2 and 3.
- [ ] **Step 5: Push** to `main` (the owner's go-ahead of 2026-11-21 covers this push when the checks hold), and record the range in the ledger.

### Task 25: Release

**Lane:** controller · **Waits for:** 24 · **Who:** an Opus agent for the build and the checks, the controller for the version push and the publish

- [ ] **Step 1: Prepare.** The next version (`0.1.0-alpha.17`, version code 17), by the release procedure of the earlier releases: one commit "Prepare Alphy Alpha 17 release" that changes only the two version lines.
- [ ] **Step 2: Build** `./gradlew :app:assembleRelease`, and sign a copy with the key of the earlier release only when the signer certificate of that release equals it.
- [ ] **Step 3: Check** (AGENTS.md): `aapt dump badging` succeeds, and its output has no `application-debuggable` entry. The version is right. The new file installs over the earlier release on one own emulator and starts.
- [ ] **Step 4: Publish** the version commit to `main`, then `gh release create v0.1.0-alpha.17` as a prerelease with the signed APK. Compare the digest of the asset with the local file.
- [ ] **Step 5: Record** the release, and tell the owner each thing that was not checked.

## Self-check

- Coverage: goals 1 to 5 (Tasks 1, 4, 9; 13, 17; 1, 4; 5, 18; 16, 19, 23). Anti-goals: moving parts (Tasks 16, 21), restart after one fault (Task 4 `aLineThatStaysFaultyIsLeftOut`, Task 17 `aFaultyLineAppearsAfterItsRepairAndTheOthersStay`), a later final screen (Task 20), a control on before live (Tasks 16, 17), no end (Task 17). The live checks have their dialogues from Tasks 10b and 19. Owner decisions 1 to 5 (Tasks 17, 4, 23, 1 and 4, 5 and 18). Design sections 1 to 6 and the gates of section 7 have one task each.
- Skipped: the form with one isolate for the stream (only after gate 1a and the owner's yes), and more templates than three (a non-goal).
