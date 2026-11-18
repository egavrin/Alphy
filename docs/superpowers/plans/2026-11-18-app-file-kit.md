# App file kit (phase 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The kit compiles an app file to an Alphy app definition inside the device sandbox, and eight saved plans pass `ToolSpec.parse` and `ToolGenerationSmoke` on a device and look good there, with no model call.

**Architecture:** One JavaScript library ("the kit") holds the parser, the parts, the evaluator, the host checks, and an emitter that turns the expanded component tree into `stateSchema`, `initialState`, `ui`, `surfaces`, and `source`. The host loads the kit bundle from the app assets and calls it through one new sandbox entry. The `source` of a compiled app is the kit's run-time plus the compiled app as data, so no model-written code runs.

**Tech Stack:** JavaScript (ES modules, run and tested with Bun 1.3 on the development machine), Kotlin, `androidx.javascriptengine` through the existing `SandboxClient`, AndroidX instrumented tests through `tools/compose_acceptance/run.py`.

**Spec:** `docs/superpowers/specs/2026-11-18-app-file-parts-design.md` (approved 2026-11-18). This plan is phase 1 of its "Phases and stop rules". Base revision: `origin/main` `ce37f1c`. Prototype: `/Users/egavrin/Documents/alphy-dsl-prototype` (read-only source of the kit code and of the saved plans).

## Roadmap

The spec has eight phases, and each depends on the proof of the one before it. So there is one plan for each step of the build order that the user set (speed, look, and the full duplex dialogue with OpenAI).

| Plan | Spec phase | Starts when |
| --- | --- | --- |
| This plan | 1. Proof with no model | now |
| `app-file-loop` | 2. The plan step in the agent loop, for DeepSeek and OpenAI | phase 1 passes its stop rule |
| `app-file-device-runs` | 3. Paired device runs, look, and the switches | phase 2 is merged in the branch |
| `app-file-dialogue` | 8. Dialogue mode on the live voice branch | phase 3 passes, and `worktree-live-voice` is on main |
| later | 4 preview, 5 capability parts, 6 canvas code, 7 3D runtime | after the dialogue plan |

## Global Constraints

- Work in a linked worktree from `origin/main`: `git worktree add .claude/worktrees/app-file-parts -b app-file-parts origin/main`. The main checkout is older and its Slopo configuration is stale. Never `cd` into the worktree in a shell that must keep its directory: use `git -C` and absolute paths.
- Before each commit, follow `~/.codex/skills/slopo-review/SKILL.md`, then run `./target/release/architecture-scan check <worktree> --policy pilot/alphy/policy.json --baseline pilot/alphy/baseline.json` from `~/Documents/architecture-scan-rs`. Exit 0 passes. Exit 1: fix or stop. Exit 2: stop and report.
- Commit messages: imperative, sentence case, no prefix, no period, no attribution lines. No push without the user's explicit yes.
- Device tests: `python3 tools/compose_acceptance/run.py suite --serial emulator-5592 --label <label> <targets>` on the AVD `AlphyAppFile` (Task 0). Never use emulator-5590: a parallel session owns it. Targets are class or method names. A skipped test prints "failed": read `run.json`. Before and after each run record `dumpsys package com.alpha.assistant.test | grep lastUpdateTime` and discard the run when it changed.
- Never install a debug build on the user's OnePlus Open.
- Kit tests: `bun test kit` from the worktree root. Expected last line: `0 fail`. The kit has no dependency: no `package.json` dependencies, no npm install.
- The built bundle `app/src/main/assets/appfile/kit.js` is checked in. `bun kit/build.js --check` must exit 0 before each commit that touches `kit/`.
- No model call and no network call in this plan.
- Limits of Alphy that each emitted app must meet (read in `ToolSpec.kt`, `ToolUiCatalog.kt`, `SandboxContract.kt`, `GraphicsContract.kt` at ce37f1c): `source` at most 64 KiB; definition at most 256 KiB; `ui` at most 64 KiB, 512 nodes, depth 8, 1 to 50 children; state at most 48 KiB; 1 to 40 top-level state fields; names `[A-Za-z][A-Za-z0-9_]{0,47}`; a list has at most 100 rows and 1 to 12 flat scalar fields with `id`; an object has at most 32 fields; an array at most 512 items; action ids unique in the UI; at most 4 surfaces; for all surfaces of one reply 512 commands, 2048 coordinate pairs, 2048 text characters, 32768 bytes; at most 8 timers of 16 to 60000 ms; 500 ms for an event, 100 ms for a frame, pointer, timer, or live input event.
- A definition for `ToolSpec.parse` has `formatVersion: 3`, `description`, and `capabilities: []`, and no `language` key. The language goes beside it.
- The kit is the only place that knows the app file. Kotlin passes text in and JSON out. No second validator: `ToolSpec.parse` and `ToolGenerationSmoke` stay the validators of the app format.
- No compatibility layer and no migration (AGENTS.md).
- Behavior tests come first and fail before the production code.

## Review Focus

1. A need name or a row field name that is not a valid Alphy name (Cyrillic, Korean, a name of 60 letters, `constructor`): the emitted state field and action id are still valid and distinct. Test: Task 3, `emit: names that Alphy refuses get safe names`.
2. A number input that the smoke test sets to its lowest and highest value, and a text input set to `""` and `"Preview"`: no figure becomes `NaN`, `Infinity`, or `null` in the state. Test: Task 3, `run: extreme inputs keep each derived value finite`.
3. A list at its limits: the 100th row, a delete of the last row, a row key that does not exist any more. The reply is a valid state and no exception. Test: Task 4, `run: a list at its limits`.
4. A plan that is over an Alphy limit (41 state fields, 13 row fields, 5 surfaces, 513 drawing commands): the compile result names the need line, and no definition comes out. Test: Tasks 3, 4, and 8, `emit: a plan over a limit is a fault of its need line`.
5. Two events in one process and a fresh process: the `source` keeps no value outside the state. A `render` or `preview` event changes no state. Test: Task 3, `run: render and preview change no state`, and the device test of Task 3.

## File Structure

| Path | Responsibility |
| --- | --- |
| `kit/src/dsl.js` | Lines, expressions, `AppFile`. From the prototype's `dsl.js`. |
| `kit/src/parts.js` | The parts: a need line to component, state, and formula lines. From the prototype's `parts.js`. |
| `kit/src/core.js` | The evaluator: formulas, actions, the `App` that holds state and builds the component tree, shapes and bodies as values. From the run-time half of the prototype's `runtime.js`. No `process`, no `fs`. |
| `kit/src/check.js` | The host report and the walk that uses each control. From the other half of `runtime.js`. Compile time only. |
| `kit/src/emit.js` | The expanded component tree to an Alphy definition: schema, state, `ui`, surfaces. New. |
| `kit/src/draw.js` | Shapes to Alphy drawing commands, and bodies to polygons. New. |
| `kit/src/run.js` | The run-time entry of a compiled app: one event in, one reply out. New. |
| `kit/src/compile.js` | `compileAppFile(text)`: parse, expand, check, emit. New. |
| `kit/build.js` | Writes the bundle, with the run-time as a text constant inside it. `--check` compares. |
| `kit/plans/*.plan` | The eight saved plans, and `tea.plan`. |
| `kit/test/*.test.js` | Bun tests. |
| `app/src/main/assets/appfile/kit.js` | The built bundle. |
| `app/src/main/java/com/alpha/assistant/sandbox/SandboxClient.kt` | Gets one method for a script call. |
| `app/src/main/java/com/alpha/assistant/tools/appfile/AppFileCompiler.kt` | Loads the bundle and calls it. |
| `app/src/androidTest/java/com/alpha/assistant/tools/appfile/AppFileProofTest.kt` | The device proof of each saved plan. |
| `app/src/androidTest/java/com/alpha/assistant/tools/appfile/AppFileLookTest.kt` | A native picture of each compiled plan, and its look numbers. |
| `app/src/androidTest/assets/appfile/*.plan` | The saved plans for the device tests. `kit/build.js` copies them. |

---

### Task 0: Worktree, emulator, and the documents

**Files:**
- Create (in the worktree): `docs/superpowers/specs/2026-11-18-app-file-parts-design.md`, `docs/superpowers/plans/2026-11-18-app-file-kit.md` (copies of the files in the main checkout, which are untracked there).

- [ ] **Step 1:** Create the worktree with the command of "Global Constraints". Copy the two documents into it.
- [ ] **Step 2:** Create the AVD: `avdmanager create avd -n AlphyAppFile -k "system-images;android-36;google_apis;arm64-v8a" -d pixel_7` (the tools are in `/opt/homebrew/share/android-commandlinetools`). Start it: `emulator -avd AlphyAppFile -port 5592 -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect`. Verify: `adb -s emulator-5592 shell getprop sys.boot_completed` prints `1`.
- [ ] **Step 3:** Baseline run, to know that the worktree builds and that a sandbox test passes before any change: `python3 tools/compose_acceptance/run.py suite --serial emulator-5592 --label appfile-base com.alpha.assistant.sandbox.SandboxIsolationTest`. Expected: passed.
- [ ] **Step 4:** Set up the Slopo state of the worktree as `references/workflow.md` of the skill says (config from the template, `index`, `embed`). This takes several minutes, one time.
- [ ] **Step 5:** Commit the two documents ("Add the app file design and the plan of its first phase").

### Task 1: The kit workspace with the prototype's code and tests

**Files:**
- Create: `kit/src/dsl.js`, `kit/src/parts.js`, `kit/src/core.js`, `kit/src/check.js`, `kit/test/dsl.test.js`, `kit/test/parts.test.js`, `kit/test/core.test.js`, `kit/test/check.test.js`, `kit/test/helpers.js`, `kit/README.md`
- Create: `kit/plans/calc.plan`, `list.plan`, `quiz.plan`, `tabs-chart.plan`, `timer-rounds.plan`, `tea.plan`, `section.plan`, `lesson.plan`, `model3d.plan`

**Interfaces:**
- Produces (names as in the prototype, so later tasks and the prototype's records agree):
  - `dsl.js`: `class DslError`, `parseExpression(text)`, `parseLine(raw)`, `class AppFile` (`feed`, `feedText`, `of`, `get`, `text`, `entries`), `show(node)`.
  - `parts.js`: `async expand(file)`, `PART_KINDS`.
  - `core.js`: `class App` (`state`, `tree()`, `find(name)`, `press(name, rowIndex)`, `input(name, value, rowIndex)`, `change(node, value)`, `fire(node)`, `tick()`, `follow()`, `recompute()`), `CATALOG`, `ACTIONS`, `FUNCTIONS`, `ICONS`, `TONES`, `settings` (`gate`, `now`).
  - `check.js`: `report(file) → {faults: string[], checks: [], notes: string[]}`, `smoke(file)`. `settings.gate` is `"smoke"` in the kit: an app file of this design has no check lines.

- [ ] **Step 1:** Copy the saved plans. Sources in the prototype: `examples/tip.plan` → `calc.plan`; `examples/real/shopping-it.plan` → `list.plan`; `examples/real/kanji-ja.plan` → `quiz.plan`; the `plan` lines of `search/route3/low/showcase-lang-budget-es.json` → `tabs-chart.plan`; of `showcase-lang-workout-de.json` → `timer-rounds.plan`; of `showcase-lang-tea-tr.json` → `tea.plan`; the author lines of `search/f0/app/habits-uk-3.json` (`file.entries` with origin `author`, in order) → `section.plan`; `examples/dialogue-lesson.plan` → `lesson.plan`; `examples/dialogue-rover.plan` → `model3d.plan`.
- [ ] **Step 2:** Write the failing tests by porting the prototype's `test.js` (868 lines) into the four test files with `bun:test` (`test`, `expect`). Keep each assertion and its expected values. Leave out the blocks that need a prompt file, `generate.js`, `loop.js`, or `scenario.js`: they belong to phase 2. Add `plans.test.js`: each of the nine plans expands, and `report(file).faults` is `[]`.
- [ ] **Step 3:** Run `bun test kit`. Expected: fails, the modules do not exist.
- [ ] **Step 4:** Copy `dsl.js` and `parts.js` from the prototype. Split the prototype's `runtime.js` into `core.js` (everything that an app needs while it runs) and `check.js` (`smoke`, `runChecks`, `report`, and their helpers). `core.js` must not import `check.js`, and it must not read `process` or `globalThis.process`.
- [ ] **Step 5:** Run `bun test kit`. Expected: `0 fail`.
- [ ] **Step 6:** `kit/README.md`: what the kit is, the two commands, and that the prototype is its origin. Commit ("Add the app file kit with its parser, parts, and evaluator").

### Task 2: The sandbox entry and the compiler class

**Files:**
- Create: `kit/src/compile.js`, `kit/build.js`, `kit/test/compile.test.js`, `app/src/main/assets/appfile/kit.js`
- Create: `app/src/main/java/com/alpha/assistant/tools/appfile/AppFileCompiler.kt`
- Modify: `app/src/main/java/com/alpha/assistant/sandbox/SandboxClient.kt` (add one method next to `execute`)
- Test: `app/src/androidTest/java/com/alpha/assistant/tools/appfile/AppFileProofTest.kt`

**Interfaces:**
- Consumes: Task 1 (`AppFile`, `expand`, `report`).
- Produces:
  - JS: `async compileAppFile(text: string) → {faults: string[], notes: string[], definition: object | null, language: string | null}`. In this task `definition` is always `null` and `faults` holds the line faults and the report faults. A throw inside the kit becomes the one fault `"kit: <message>"`.
  - The bundle defines `globalThis.compileAppFile` and no other global. `bun kit/build.js` writes it, and copies `kit/plans/*.plan` to `app/src/androidTest/assets/appfile/`. `bun kit/build.js --check` exits 1 when a written file differs.
  - Kotlin: `internal suspend fun SandboxClient.evaluateScript(script: String, expression: String, deadlineMs: Long, maxReturnBytes: Int): SandboxResult`. It evaluates `script + "\n" + expression` in a fresh isolate of the slot that `execute` uses, with no request or result validation, and closes the isolate.
  - Kotlin: `internal class AppFileCompiler(context: Context, sandbox: SandboxClient) { suspend fun compile(appFile: String): AppFileCompilation }`, and `internal data class AppFileCompilation(val status: String, val faults: List<String>, val notes: List<String>, val definition: String?, val language: String?)`. `status` is `"ok"` (a definition, no fault), `"faults"`, or the name of the sandbox status that failed (`TIMEOUT`, `BUSY`, and so on). Constants in the same file: `APP_FILE_COMPILE_MS = 3000L`, `APP_FILE_RETURN_BYTES = 384 * 1024`.
  - The kit's entry is async, and the sandbox call must resolve it: the expression is `compileAppFile(<quoted text>).then(JSON.stringify)`. If `evaluateJavaScriptAsync` of this engine version does not resolve a promise, make `expand` and `compileAppFile` synchronous in the kit and say so in the commit.

- [ ] **Step 1:** Failing kit test `compile.test.js`: `compileAppFile` of `calc.plan` gives `faults: []`; of `need x = nothing(1)` gives one fault that names the line; of a text that makes the kit throw gives a fault that starts with `kit:`.
- [ ] **Step 2:** Failing device tests in `AppFileProofTest`: `compilesAPlanInTheSandbox` (`calc.plan` → status `"faults"` or `"ok"`, and `faults` is empty), `reportsAFaultOfALine` (`need x = nothing(1)` → status `"faults"`, one fault with `nothing`), `aCallThatOverlapsIsBusyNotAHang` (two `compile` calls at one time: each returns, and a result with status `BUSY` is allowed), `compileTakesUnderOneSecond` (the median of 5 compiles of `quiz.plan` is under 1000 ms; print the times).
- [ ] **Step 3:** Run both. Expected: fail.
- [ ] **Step 4:** Implement `compile.js`, `build.js` (plain concatenation of the modules into one script with the imports and exports removed, in dependency order; no minifier dependency), `evaluateScript`, and `AppFileCompiler` (read the asset as `semantic/SemanticRuntime.kt` reads its asset).
- [ ] **Step 5:** `bun test kit` → `0 fail`. `bun kit/build.js --check` → exit 0. Device: `run.py suite --serial emulator-5592 --label appfile-entry AppFileProofTest` → passed. Record the compile times in the commit message body.
- [ ] **Step 6:** Commit ("Compile an app file in the sandbox and return the kit's report").

### Task 3: The emitter and the run-time for inputs and figures

The first full path: `calc.plan` becomes a definition that passes `ToolSpec.parse` and `ToolGenerationSmoke` and that computes on a device.

**Files:**
- Create: `kit/src/emit.js`, `kit/src/run.js`, `kit/test/emit.test.js`, `kit/test/run.test.js`
- Modify: `kit/src/compile.js`, `kit/build.js` (embed the run-time), `AppFileProofTest.kt`

**Interfaces:**
- Consumes: `App`, `report`, `compileAppFile`.
- Produces:
  - `emit(file) → {definition, language, faults}`. `definition` is `{formatVersion: 3, title, description: "", capabilities: [], stateSchema, initialState, ui, source}` and, when a surface exists, `surfaces`.
  - `handle(input, app, now = Date.now()) → reply` in `run.js`: `input` is the sandbox request (`{artifactId, revision, state, event}`), `app` is the compiled data that `emit` puts into the `source`, `now` is the clock for a test, and `reply` is `{state}` plus `ui`, `surfaces`, `frames`, `timers` when they apply.
  - The `source` text: the run-time (the bundle of `dsl.js`, `core.js`, `draw.js`, `run.js`, with comments and indentation removed by `build.js`), then `return handle(input, <compiled data as JSON>);`.
  - `compileAppFile` now returns the definition when `faults` is empty.

**The mapping rules of this task** (the spec, section 2, leaves their form to the builder; these are the decisions):

- **Root.** `{type: "theme", preset, palette, cornerRadius: 20, children: [{type: "column", style: {gap: "medium"}, children: [...]}]}`. Presets and palettes of the app header pass as they are (the prompt's lists are a subset of Alphy's). `kind=` of the header becomes the first node: `text` with `style: {emphasis: "label", tone: "accent"}`. The title of the app is the `title` of the definition.
- **State.** Each root `$/NAME` that a state line of the author sets is one top-level field with the type of its start value. The roots that a part made for one need (`NAME_...`) are the fields of one object `NAME_s`, so a need costs at most two top-level fields: its value and its inner object. Each value that a node shows and that the host computes (a formula line, a formula inside a component) is a field of an object `d` (strings and numbers) or `v` (booleans for `visibleBind` and `enabledBind`). An object holds at most 32 fields, so the next ones go to `d2`, `v2`, and so on. A formula that no node shows is not in the state: the run-time computes it for each event.
- **Names.** A kit name that Alphy refuses (not `[A-Za-z][A-Za-z0-9_]{0,47}`, or `__proto__`, `prototype`, `constructor`) gets the name `f<index>`. The compiled data holds the map in both directions.
- **Nodes.**

| Kit component | Alphy node |
| --- | --- |
| `Column`, `Row` | `column`, `row`. A row whose child has `weight` gets `wrap: false`, and that child gets `layout: {weight}`. |
| `Card` | `card`. `variant="hero"` passes as `variant: "hero"`. |
| `Section(title, children)` | `section` with `text`. |
| `Text` | `text`. A constant text is the `text`. A computed text is `text: ""` with `bind` to its `d` field. `variant="eyebrow"` → `style: {emphasis: "label", tone: "accent"}`, `variant="muted"` → `style: {tone: "muted"}`. |
| `Heading` | `text` with `style: {emphasis: "title"}`. |
| `Metric(label, value)` | `metric` with `text` = label and `bind` to a `d` field that holds the value as the kit formats it. `emphasis="display"` → `style: {emphasis: "display"}`. |
| `Badge`, `Emoji`, `Divider` | `badge`, `emoji` (`value`), `divider`. |
| `Notice` | `banner` with `text`. |
| `Button(label, action)` | `button`, `variant: "filled"`, `action` = the safe name of the component. `selected=test` → `variant: "tonal"` while the test is false and `"filled"` while it is true. |
| `IconButton(icon, action)` | `icon` with `name` (the kit's icon names exist in Alphy's list: check `ToolUiCatalog.shellIcons`, and map `ArrowUp`, `ArrowDown`, `ArrowLeft`, `ArrowRight`, `Edit`, `Close` to the nearest name there) and a `text` for accessibility from the action. |
| `NumberField` | `numberInput` with `bind`, `action`, `min`, `max`, `step`. Without a `min` or `max` in the line: 0 and 1000000. |
| `Slider` | `slider` with `min`, `max`, `step`. |
| `TextField` | `input` with `inputMode: "apply"`. |
| `Toggle`, `CheckBox` | `toggle`, `checkbox`. |
| `Segmented`, `Select` | `segmented` up to 6 options, else `select`. Options are `[{text, value}]`. |

- **What Alphy cannot bind** (the text of a button, the options of a choice, a `variant`): when such a value depends on the state, the reply of an event carries a new `ui`. The run-time builds the `ui` with the same function as the emitter and sends it only when it differs from the one of the event before.
- **Events.** `open`: compute each derived field, return the state. `preview`, `render`: the same, and the state of the person does not change. `action`: find the control by `actionId`; a control with `bind` sets its value (the event carries it), a control with an action runs the action of the kit; then compute again. An `actionId` that the app does not have changes nothing.
- **Values.** A derived number that is not finite becomes `0`, and a derived text is never `null`: `ToolSpec` refuses them and the presentation check refuses `NaN`.

- [ ] **Step 1:** Failing kit tests:
  - `emit: calc.plan gives a definition with the fields of ToolSpec` (keys as above, `formatVersion` 3, no `language` key, `language` beside it is `"en"`, the root is a `theme` with one `column`).
  - `emit: the first figure is a hero card` (the first child of the column is a `card` with `variant: "hero"` that holds a `metric`).
  - `emit: names that Alphy refuses get safe names` (needs `단어`, `constructor`, and a name of 60 letters: each state field and action id matches the name rule, and all are distinct).
  - `emit: a plan over a limit is a fault of its need line` (a plan with 41 inputs: no definition, and the fault names the 41st need).
  - `run: an action sets the bound value and the figures follow` (on `calc.plan`: the action of the bill input with value 80 changes the figure field to the value that the prototype's `App` gives for the same input).
  - `run: render and preview change no state`.
  - `run: extreme inputs keep each derived value finite` (each number input at its `min` and its `max`, each text input at `""` and `"Preview"`: each `d` field is a finite number or a text).
  - `run: an action id that the app does not have changes nothing`.
  - `source: the source of calc.plan is under 64 KiB and evaluates as a function body` (`new Function("input", source)` with an `open` request returns `{state}`). Print its size.
- [ ] **Step 2:** Failing device test `calcPassesParseAndSmoke`: compile `calc.plan`, then `ToolSpec.parse(definition)`, then `ToolGenerationSmoke(...).run()` as `AppAgent.validate` builds it (`tools/agent/AppAgent.kt`, the smoke step), then one `action` through `SandboxClient.execute(spec.source, input)` and an assertion on the figure field.
- [ ] **Step 3:** Run both. Expected: fail.
- [ ] **Step 4:** Implement `emit.js`, `run.js`, the embedding in `build.js`, and the definition in `compileAppFile`.
- [ ] **Step 5:** `bun test kit` → `0 fail`. `bun kit/build.js --check` → 0. Device: `run.py suite --serial emulator-5592 --label appfile-calc AppFileProofTest` → passed.
- [ ] **Step 6:** Commit ("Emit an Alphy app from an app file with inputs and figures").

### Task 4: Lists

`list.plan` and the list half of `quiz.plan` pass on a device.

**Files:**
- Modify: `kit/src/emit.js`, `kit/src/run.js`, `kit/test/emit.test.js`, `kit/test/run.test.js`, `AppFileProofTest.kt`

**Interfaces:**
- Consumes: `emit`, `handle`.
- Produces: the mapping of `List`, `Dialog`, `DateField`, `TimeField`, `Choices`, and of a row control.

**Rules:**

- A kit list of rows is a state field `{type: "list", maxItems: 100, fields: {id: "string", ...}}`. The kit's row id is the `id`. A row field is a flat scalar. A check or a switch field is a boolean, a count is a number, a date is a string, a time is a number of minutes, and a choice field is a string.
- A value that the row shows and that the host computes (`show=`, a text formula of the row template) is one more flat field of the row. More than 12 fields with them: a fault of the need line.
- `List(items, template)` → `list` with `bind`, `emptyText`, and `rowTemplate`. Inside the template a bound text is `text: ""` with `bind` to the row field. A row control sends `{key, value}`: the run-time finds the row by the key. With `group=` → `groupedList` with `groupBy`.
- A sorted or filtered view is a second list field that the run-time writes for each event. A row control of the view changes the stored list by the row key.
- `reorder=true` → `reorder: {action, labelField}` on the list, in place of the two arrow buttons of the prototype. The event value is `{sourceKey, destinationKey}`.
- The add form: its fields and its button. A form with more than 2 typed fields, or `sheet=true`, goes into a `bottomSheet` with `visibleBind` and `dismissAction`, with an "add" button on the screen that opens it. This follows Alphy's rule of at most 2 typed fields on the first screen. `Dialog(title, children, visible=test)` → `dialog` with `visibleBind` to a `v` field and a `dismissAction` that sets the test's state value to false when the test is one state value, else no `dismissAction`.
- `DateField` → `dateInput` (a real ISO date: an empty start value becomes today). `TimeField` → `timeInput` (minutes). The kit's formulas read a time as `"HH:MM"`, so the run-time converts in both directions.
- `Choices` → `multiChoice` bound to an array of strings.

- [ ] **Step 1:** Failing kit tests: `emit: list.plan gives a list with a row template and an add form`, `emit: an add form with more than two typed fields is a bottom sheet`, `emit: a row with more than 12 fields is a fault of its need line`, `run: add, check, and delete a row` (values as the prototype's `App` gives them for the same presses), `run: a list at its limits` (the 100th row is added and the 101st is refused with no exception; a delete with a key that does not exist changes nothing), `run: a reorder event moves the row`, `run: a sorted view follows the stored list`, `run: a time field reads "07:30" as 450 minutes and writes it back`.
- [ ] **Step 2:** Failing device test `listPassesParseAndSmoke` (`list.plan`: parse, smoke, then add a row, check it, delete it through `execute`, and assert the list field).
- [ ] **Step 3:** Run both. Expected: fail.
- [ ] **Step 4:** Implement.
- [ ] **Step 5:** `bun test kit` → `0 fail`. Build check → 0. Device: label `appfile-list` → passed.
- [ ] **Step 6:** Commit ("Emit lists with row controls, views, and add forms").

### Task 5: Cards, quiz, tabs, steps, chart, progress, lesson, question

`quiz.plan` and `tabs-chart.plan` pass on a device.

**Files:**
- Modify: `kit/src/emit.js`, `kit/src/run.js`, tests, `AppFileProofTest.kt`

**Rules:**

- `Progress(value, max)` → `progress` with `variant: "bar"`, `min: 0`, `max: 100`, bound to a `d` field that holds the share in percent. `Ring(value, max, label)` → `progressRing` with `size: "large"` and one child `text` with the label, bound the same way. A ring or a figure that is the main element of the plan sits in the hero card.
- `Chart(items, label, value)` → `chart` with `kind` `bar` or `line`, `categories` and one `series` bound to two array fields (`{type: "array", maxItems: 256, items: ...}`) that the run-time writes. More than 256 points: the run-time keeps the last 256.
- `Tabs(titles, groups)` → a `tabs` control bound to a string field (the title of the open tab), then one `column` for each group with `visibleBind` to a `v` field.
- The steps part (its `Column` nodes with `visible=`) uses `visibleBind` the same way.
- A lesson part: its step count as `progress` with `variant: "dots"` when it has 12 steps or fewer, else `bar`.
- A question part: its answers in a `flowRow`, its result as a `banner` with `visibleBind`.
- `Link` and `Picture`: not in this plan. The emitter gives the fault `"<kind> is not built yet"` for the need, so a plan with one is not taken (spec, section 2, pictures before phase 5).

- [ ] **Step 1:** Failing kit tests: `emit: quiz.plan gives a card with the question and its buttons`, `emit: tabs-chart.plan gives a tabs control, groups with visibleBind, and a chart with two array fields`, `run: a tab press shows its group and hides the others`, `run: a quiz answer counts a point` (values as the prototype's `App` gives), `run: the chart arrays follow the list`, `emit: a lesson of three steps has a dots progress with max 3`, `run: a question shows its result after an answer`, `emit: a picture need is a fault "picture is not built yet"`.
- [ ] **Step 2:** Failing device tests `quizPassesParseAndSmoke`, `tabsChartPassesParseAndSmoke`.
- [ ] **Step 3:** Run. Expected: fail. **Step 4:** Implement. **Step 5:** All tests and the build check pass; device label `appfile-cards` → passed.
- [ ] **Step 6:** Commit ("Emit cards, quizzes, tabs, charts, lessons, and questions").

### Task 6: Timers

`tea.plan` and `timer-rounds.plan` pass on a device. This task also answers the two open points of the spec, section 8.

**Files:**
- Modify: `kit/src/emit.js`, `kit/src/run.js`, tests, `AppFileProofTest.kt`
- Create: `docs/superpowers/plans/evidence/2026-11-18-app-file-kit-timer.md`

**Rules:**

- A timer with one round and no rest → the foreground `timer` node: `{type: "timer", text, mode: "countdown", action, foreground: {durationMs, vibrationMs?, toneMs?, notification?}}`. `alert=true` gives `vibrationMs: 600` and `toneMs: 600`. Find in `ToolSpec.kt` and `ToolUiCatalog.kt` which capability ids a foreground alert needs in `capabilities`, and emit them.
- `seconds` as a formula: `durationMs` is a number in the `ui`, so the reply of the event that changed the formula's inputs carries a new `ui`. The device test must show that the host takes the new duration.
- A timer with rounds or a rest → one surface (320 by 64) in a `canvas` node, a ring or a bar that `draw.js` draws, the end time of the phase as a state number, and `timers: [{id, intervalMs: 1000}]` while it runs. The run-time changes the phase when `now` passes the end time, and returns `timers: []` when the timer stops. It gives no sound, and the report has the note `"<need>: a timer with rounds shows the end of a round on the screen only"`.
- The run-time reads the time only as `Date.now()` in the event, as Alphy's guide asks. For tests, `handle(input, app, now)` takes the time as a third argument.

- [ ] **Step 1:** Failing kit tests: `emit: tea.plan gives a foreground timer with the duration of the chosen tea`, `run: a new choice gives a new ui with the new duration`, `emit: timer-rounds.plan gives a surface, a canvas, and no foreground timer`, `run: start asks for a 1000 ms timer and stop returns timers: []`, `run: a timer event after the end of the work phase starts the rest phase` (with a given `now`), `run: the count of finished rounds follows`, `check: alert with rounds is a note, not a fault`.
- [ ] **Step 2:** Failing device tests `teaPassesParseAndSmoke` (and: after the `action` of the choice, the returned `ui` has the new `durationMs`, and `ToolUiCatalog` accepts it) and `timerRoundsPassesParseAndSmoke` (smoke sends each timer once and must not fail with `widget_timer_host`).
- [ ] **Step 3:** Run. Expected: fail. **Step 4:** Implement. **Step 5:** All pass; device label `appfile-timer`.
- [ ] **Step 6:** Write the evidence file: what the device showed for the formula duration and for the rounds, with the revision, the command, and the result. If the host does not take a new duration from a returned `ui`, or the rounds form fails the smoke test and no change inside this task fixes it: stop, and report to the user. The spec's rule is then `outside("timer")` for that kind of request.
- [ ] **Step 7:** Commit ("Emit timers: a foreground countdown and rounds on a surface").

### Task 7: A written section

`section.plan` passes on a device: free component, state, and formula lines of a model, compiled with the same emitter.

**Files:**
- Modify: `kit/src/emit.js`, tests, `AppFileProofTest.kt`

**Rules:** A hole's lines are ordinary component lines, so the node table of Tasks 3 to 5 already maps them. This task closes what only free lines can hold: an inline child (a component inside a list of children with no name of its own) gets a name from its parent and its index; a `List` template that is an inline `Row`; an action list `[a, b]`; `when(test, action)`; `move`, `flip`; a `Button` with `enabled=test` (→ `enabledBind`).

- [ ] **Step 1:** Failing kit tests: `emit: section.plan gives a valid definition`, `emit: an inline child gets a stable name`, `run: each action kind of the kit changes the state as the prototype's App does` (one case for each of `set`, `toggle`, `inc`, `dec`, `append`, `remove`, `clear`, `when`, `move`, `flip`, and a list of two actions), `emit: enabled=test is an enabledBind`.
- [ ] **Step 2:** Failing device test `sectionPassesParseAndSmoke`.
- [ ] **Step 3:** Run. Expected: fail. **Step 4:** Implement. **Step 5:** All pass; device label `appfile-section`.
- [ ] **Step 6:** Commit ("Emit the free lines of a written section").

### Task 8: Drawings and timed changes

`lesson.plan` passes on a device: a drawing that follows the state, and a button that moves a value over time.

**Files:**
- Create: `kit/src/draw.js`, `kit/test/draw.test.js`
- Modify: `kit/src/emit.js`, `kit/src/run.js`, tests, `AppFileProofTest.kt`

**Interfaces:**
- Produces: `commandsOf(shapes, palette) → command[]` in `draw.js`, and `budgetOf(commands) → {commands, pairs, chars, bytes}`.

**Rules:**

- A `Drawing(label, width, height, shapes)` → one surface `{id, width, height}` (the kit's sizes, each raised to at least 64) and a `canvas` node `{type: "canvas", text: label, surfaces: [id]}`. Each reply carries `surfaces: {id: {commands}}` for each drawing.
- Shapes to commands: `circle` → `circle` (`radius`); `rect` → `rect`, or `roundRect` with `radius`; `line` → `line` with `stroke` = the width, default 2; `text` → `text` with `size` (default 14), `align: "center"`, `baseline: "middle"` (the kit's text is centered at x, y); `polygon` → `polygon` with `points: [[x, y], ...]`.
- `stroke=true`: Alphy's circle, rect, and polygon are filled. So an outline circle is an `arc` of the box of the circle with `start: 0`, `sweep: 360`, `stroke: 2`; an outline rect or polygon is a closed `polyline` with `stroke: 2`.
- Colors: `ink` → `default`, `muted` → `muted`, `accent` → `accent`, `success` → `success`, `warning` → `warning`. The other names of the kit (`surface`, `water`, `land`, `sun`, `sand`, `wood`) → their `#RRGGBB` values of `TONES`. A `#RRGGBB` passes. Any other text → `default`.
- Limits: the emitter counts the commands of the first state. Over 512 commands, 2048 pairs, 2048 characters, or 4 surfaces for the whole app: a fault of the need line with the number. At run time, a reply that would go over a limit keeps its first commands up to the limit, so the app never returns an invalid drawing.
- `animate($/path, to, ms)`: the state gets an object field `anim` with the path, the start and end values, and the start and end times. While a change runs, each reply has `timers: [{id: "anim", intervalMs: 50}]`, and a `timer` event sets the value by the clock. At the end the reply has `timers: []`. The app needs a surface for timers, which a drawing gives. `animate` in an app with no surface: a fault of the need line.

- [ ] **Step 1:** Failing kit tests in `draw.test.js`: one test for each shape and for each `stroke=true` form with the exact command that it gives; `draw: a color name of the kit maps to a tone or a hex`; `draw: budgetOf counts commands, pairs, and characters`. In `emit.test.js` and `run.test.js`: `emit: lesson.plan gives a surface, a canvas, and commands in the open reply`, `run: a change of the angle moves the Moon` (the circle's x and y as the prototype gives them for 90 degrees: 160 and 50), `run: the animate button starts a timer and the value is 180 after half of its time` (with a given `now`), `run: the last timer event returns timers: []`, `emit: a plan over a limit is a fault of its need line` (513 shapes; 5 drawings), `run: a reply never goes over the command limit` (a list of 600 rows drawn with `each`).
- [ ] **Step 2:** Failing device test `lessonPassesParseAndSmoke`: parse, smoke (it sends frames and each timer), then the `action` of the angle input and of the animate button through a retained `GraphicsSession`, with assertions on the returned commands and on `timers`.
- [ ] **Step 3:** Run. Expected: fail. **Step 4:** Implement. **Step 5:** All pass; device label `appfile-drawing`. Print the time of an `action` event and of a `timer` event on the emulator: both must be under 100 ms.
- [ ] **Step 6:** Commit ("Draw a drawing part on a surface and move a value over time").

### Task 9: 3D models of simple bodies

`model3d.plan` passes on a device.

**Files:**
- Modify: `kit/src/draw.js`, `kit/src/emit.js`, `kit/src/run.js`, tests, `AppFileProofTest.kt`

**Interfaces:**
- Produces: `polygonsOf(bodies, turnDegrees, exploded, selected, width, height) → command[]` in `draw.js`.

**Rules:**

- A `Model3d` → one surface of 320 by 240 and a `canvas` node. The projection, the order of the faces (far first), the shading, the explode rule, and the dimming of the parts that are not selected are the prototype's (`render.js`, function `model`, and `runtime.js`, `BODIES` and `explode`). Port that code to `draw.js` as pure functions with no DOM.
- A box is 6 faces, and only the faces that look at the viewer are drawn. A cylinder is a prism of 12 sides.
- A drag turns the model: `pointer` events of its surface change the turn angle by 0.6 degrees for each pixel, as in the prototype. The two arrow buttons stay.
- The part buttons, the arrows, and the explode button are native nodes, as the part's lines say.
- The limits of Task 8 apply. A model over them: a fault of the need line.

- [ ] **Step 1:** Failing kit tests: `draw: a box at the origin gives three polygons at turn 30`, `draw: a far face is before a near face`, `draw: explode moves a body of a named part away from the center by half of its distance`, `draw: a selected part keeps its color and the others are dimmed`, `emit: model3d.plan gives a surface, a canvas, three part buttons, two arrows, and the explode button`, `run: a pointer drag of 50 pixels turns the model by 30 degrees`, `run: the explode button changes its text through a new ui`, `emit: model3d.plan stays under the drawing limits` (print the numbers).
- [ ] **Step 2:** Failing device test `model3dPassesParseAndSmoke` (smoke sends pointer events; then a press of a part button and of the explode button, with assertions on the state).
- [ ] **Step 3:** Run. Expected: fail. **Step 4:** Implement. **Step 5:** All pass; device label `appfile-model3d`. Print the time of a `pointer` event: under 100 ms.
- [ ] **Step 6:** Commit ("Draw a 3D model of boxes and cylinders on a surface").

### Task 10: Sizes and times of each saved plan

**Files:**
- Create: `kit/test/budget.test.js`
- Modify: `AppFileProofTest.kt`
- Create: `docs/superpowers/plans/evidence/2026-11-18-app-file-kit-budgets.md`

- [ ] **Step 1:** Kit test `budget: each saved plan is inside each limit`: for each of the nine plans, the `source` bytes (limit 65536, and the test fails above 60000 so that the kit has room to grow), the definition bytes, the `ui` bytes and node count, the state bytes, the count of top-level state fields, and the drawing budget. It prints one table row for each plan.
- [ ] **Step 2:** Device test `eachPlanAnswersInTime`: for each plan, the median of 5 runs of an `open` event and of its slowest `action` through `SandboxClient.execute`, and for a plan with a surface a `timer` or `pointer` event through `GraphicsSession`. Limits: 250 ms for `open` and `action` (half of Alphy's 500 ms), 50 ms for the others (half of 100 ms). It prints the numbers.
- [ ] **Step 3:** Run. If a number is over: make the run-time smaller or faster inside this task (first candidates: ship the parsed tree in the compiled data and leave the parser out of the `source`; compute a derived value only when one of its inputs changed).
- [ ] **Step 4:** Write the evidence file with both tables, the revision, and the commands. Commit ("Check the size and the time of each compiled plan").

### Task 11: The look of a compiled app

The user's second goal is the look of each app. This task makes a native picture of each compiled plan, measures it with the rules that `tools/agent_corpus/visual_score.py` uses, and brings the templates to those rules.

**Files:**
- Create: `app/src/androidTest/java/com/alpha/assistant/tools/appfile/AppFileLookTest.kt`
- Modify: `kit/src/emit.js`, `kit/src/parts.js`, tests
- Create: `docs/superpowers/plans/evidence/2026-11-18-app-file-kit-look/` (pictures and a table)

**Rules (from `AppAgentGuide.design` and `visual_score.py`):**

- The first or second node of the column is the main element: a hero card, a large ring, a dots or level progress, or a drawing. A plan with a figure, a progress, a drawing, or a 3D model already has one. A plan with only a list gets a hero card with the count of its rows.
- At most 2 typed fields on the first screen (Task 4 puts a longer form into a bottom sheet). No typed field in a list row: a text of a row that the person may edit opens a dialog for that row.
- A card, a list row, and an empty list have an icon or an emoji. The kit has no topic knowledge, so it takes the icon from the part kind (a list → `List`, a timer → `Timer`, a quiz → `Help`, and so on, with names from `ToolUiCatalog.shellIcons` and `ToolIcons.paths`). An `icon=` argument for the plan is not in this plan (spec, open points).
- At most 2 secondary figures, in a `grid` with `columns: 2`. More figures: the first is the hero, the next two are the grid, and the others follow as rows.
- One `filled` button in a group. The other buttons of the group are `tonal`.
- A hero number uses `style: {emphasis: "display"}`.

- [ ] **Step 1:** Failing kit tests: `look: each saved plan has its main element in the first two nodes`, `look: no saved plan has more than two typed fields on the first screen`, `look: no saved plan has a typed field in a row template`, `look: a plan with only a list gets a hero card with its count`, `look: three figures give a hero and a grid of two`, `look: a group of buttons has one filled button`.
- [ ] **Step 2:** `AppFileLookTest.picturesOfEachPlan`: for each plan, compile, show the app with the native renderer as an existing visual test does (read `MiniAppVisualWorkflowTest` and `AppSnapshotTest` for the pattern), take a screenshot of the first screen in the light theme, and save it with the evidence of the run.
- [ ] **Step 3:** Run both. Expected: the kit tests fail, the pictures exist.
- [ ] **Step 4:** Change the templates in `parts.js` and the emitter until the kit tests pass. Run the pictures again (label `appfile-look`).
- [ ] **Step 5:** Copy the nine pictures and one contact sheet into the evidence directory, with a table: plan, hero yes or no, typed fields on the first screen, typed fields in rows. Look at each picture: a text that is cut, an overlap, an empty first screen, or a control with no label is a defect to fix in this task.
- [ ] **Step 6:** Commit ("Give compiled apps a main element, icons, and short first screens").

### Task 12: The stop rule of phase 1

**Files:**
- Create: `docs/superpowers/plans/evidence/2026-11-18-app-file-kit-phase1.md`

- [ ] **Step 1:** Run the whole proof one time on a clean build: `bun test kit`, `bun kit/build.js --check`, and `run.py suite --serial emulator-5592 --label appfile-phase1 AppFileProofTest AppFileLookTest SandboxIsolationTest AgentGenerationWorkflowTest`. The last two classes show that today's path still works.
- [ ] **Step 2:** Write the evidence file: the revision, each command, the result of each of the eight plans and of `tea.plan` (parse, smoke, events), the budgets of Task 10, the look table of Task 11, the answers to the two timer points of Task 6, and each mapping rule of the spec's section 2 with its result (proved, changed, or failed).
- [ ] **Step 3:** Stop rule of the spec: if a plan cannot pass inside Alphy's limits, the design goes back to the user before phase 2. If only the drawing or the 3D model cannot pass, that kind leaves the prompt and the user hears it. Report the result to the user with the pictures.
- [ ] **Step 4:** Commit ("Record the proof of the app file kit on a device").
