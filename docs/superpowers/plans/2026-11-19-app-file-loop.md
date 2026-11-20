# The loop plan (plan A): implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task by task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A new app request gets its app from one fast plan call when the kit can serve it, and from today's agent loop when it cannot. A later change of such an app works by line patches and keeps the person's data.

**Architecture:** A new plan step runs in `AppAgent.run` before the capability guidance. It sends one streamed call with no JSON mode and reasoning off, compiles the reply with the kit in the sandbox, makes at most one repair call, and runs today's host checks (`validate`). Each other outcome goes on to today's loop. An app that the plan step made keeps its plan in the persisted host field `appFile`. A refine request or a test-and-fix request on such an app sends one change call. The kit merges the reply lines, compiles, and computes the next state from the stored state.

**Tech stack:** Kotlin (Android, coroutines, `org.json`), the kit in JavaScript (Bun 1.3.9 for tests and the build, the sandbox of `AndroidJsRuntime` on the device), Python 3 for `tools/agent_corpus`.

**Spec:** the section "Decisions of the controller" at the end of this document (binding). The design is `docs/superpowers/specs/2026-11-18-app-file-parts-design.md`, sections 3, 4, 5, and 11. The decisions file wins where the two differ (section "Differences from the design").

**Anchor commit:** each line reference is at `fc17a24` (branch `worktree-app-file-parts`). Since `267cde6`, only the model fixtures, `kit/build.js` (`outputs`), `kit/README.md`, and `kit/test/modelplans.test.js` changed **[checked]** (`git diff 267cde6 fc17a24 --stat`). No Kotlin anchor moved. Use the function name as the anchor. The line number is a hint.

Labels: **[checked]** = read in code at the anchor commit, with file and function. **[assumption]** = not checked, with its test.

> **Read the two last sections first.** "Rulings of the controller" settles each open decision of this plan and changes the task list (tasks 9 and 10 are one task, a model check of changes is new, the limits are 20 s and 45 s). Where a ruling differs from a task text, the ruling wins.
>
> A path under `.superpowers/sdd/` names a working file of the build session. Git does not keep those files. The numbers that this plan uses from them are in `docs/superpowers/plans/2026-11-19-app-file-loop-results.md`.

---

## Preconditions (not tasks of this plan)

M1 is met at `fc17a24`. K1 is not met, and it blocks tasks 1 (the `patchAppFile` global), 2, 5, 7, and 9. Check K1 before task 1.

| Precondition | Owner | Check command | Expected |
| --- | --- | --- | --- |
| K1: the kit exports `patchAppFile(plan, reply)` and the bundle sets `globalThis.patchAppFile` | task K1 (`k1-decisions.md`) | `grep -n "globalThis.patchAppFile" kit/build.js` | one line |
| M1: model plan fixtures | task M1 (`m1-decisions.md`) | `ls kit/test/fixtures/model/index.json app/src/androidTest/assets/appfile/model/holdout-tip-ru.plan` | both exist. Met at `fc17a24` (52 plans) |

At `fc17a24`, K1 is not met **[checked]**: `grep patchAppFile kit/src kit/build.js` finds nothing. M1 is met.

## Global constraints

- No compatibility with earlier development versions: no migration, no legacy path. An app with no `appFile` is an app of today's form (decisions, "Project rules").
- Do not delete user data or credentials silently (`AGENTS.md`).
- Kotlin goes in `app/src/main/java/com/alpha/assistant/`, device tests go in `app/src/androidTest/java/com/alpha/assistant/`, and the kit is `kit/`.
- Run device tests only on `emulator-5592`. A test of the default suite makes no model call.
- `kit/prompt.md` is the one source of prompt text. No prompt text is typed in Kotlin (decision 4).
- The switch of each provider is off by default. No task turns one on (decision 6).
- The kit run-time text stays at or under 49500 bytes (`k1-decisions.md`, "Limits").
- Commit message: one imperative sentence, no prefix, no period. Run the Slopo review and the architecture scan before each commit.
- Kit acceptance: `bun test kit` gives 0 fail, and `bun kit/build.js --check` exits 0.
- Device acceptance: the instrumented test class named in the task passes on `emulator-5592` with `adb -s emulator-5592 shell am instrument -w -e class <class> com.alpha.assistant.test/androidx.test.runner.AndroidJUnitRunner`. The runner name is `RUNNER` of `tools/compose_acceptance/run.py:21` **[checked]**. The suite runner and the commit gate are in `.superpowers/sdd/2026-11-18-app-file-kit/context-environment.md` (not read for this draft).

## Values from the decisions, and the values that this plan proposes

| Value | Source | Value in this plan |
| --- | --- | --- |
| Plan calls for a new app | decision 1 | 1 plan call, at most 1 repair call |
| Reasoning of the plan call and the repair call | decision 1 | the provider's `reasoningOff` (OpenAI: effort `low`) |
| Reasoning of the change call | decision 9 | the provider's `reasoningOn` (OpenAI: effort `medium`). The repair of a change uses `reasoningOff` |
| Time limit of the plan call and its repair | decision 1: 20 s to first text, 45 s in all | proposal: 15 s and 30 s (open decision OD-2). Basis: over the 84 calls of `evidence/modelcheck/final-terra-low` and `fresh-terra-low`, the first text came at most after 12.07 s (90th percentile 3.6 s and 4.6 s), and a whole call took at most 15.53 s. `AppFileLimits` holds both, so a test can set them |
| Output limit of a plan call | decision 5: propose | 4096 tokens. This is the existing `max_tokens` of a call with thinking off (`CloudChatProvider.respond`, `CloudProvider.kt:317`) **[checked]**. The model check had a median of 237 output tokens (589 for a dialogue screen), the largest plan was under 2000, and reasoning at `low` had a median of 32 tokens (107.5 for a dialogue screen) (results document) |
| Output limit of a change call | not given | no `max_tokens`, as for any call with thinking on and no expanded output (`CloudProvider.kt:316-317`) **[checked]** |
| Reply size in the app | not given | `SandboxContract.MAX_BYTES` (64 KiB), the limit of `ToolEngine.response` with `expandedWriterOutput = false` **[checked]**. `AppFileCompiler` reads at most 65536 characters **[checked]** |
| Transport retry of a plan call | not given | none. A failed plan call goes to the loop. This keeps the one transport retry of the session (`CloudSession.claimGenerationTransportRetry`) for the loop |

## Differences from the design (the decisions file wins)

| Design | This plan |
| --- | --- |
| Section 3: three lanes (T1 and T2 think, A does not), a 20 s and a 30 s point | One call with reasoning off (decision 1). No lane, no 20 s point for lane A |
| Section 3.4: up to 2 repair steps, and the host drops each line that is not a need, a state, a header, or a delete | 1 repair (decision 1). The kit's `patchAppFile` merges the reply (K1). The host drops no line |
| Section 3.6: after 30 s, `write_app` gets the request, the file, and the host report | Nothing of the plan goes to the loop (decision 1) |
| Section 5: test-and-fix by line steps keeps the `run` tool | One change call with no `run` tool (open decision OD-5) |
| Section 11: an event for each lane | One `Phase` event for each named point (decision 7) |

---

## File structure

| File | New or changed | Responsibility |
| --- | --- | --- |
| `kit/prompt.md` | changed | Adds the section "Change" |
| `kit/prompt.js` | new | `PROMPT` (the sections by heading), `taskText`, `repairText`, `changeText`. These move out of `kit/modelcheck.js`, which imports them |
| `kit/src/compile.js` | changed | `readPlanReply(text)`, `compileAppFile(text, previous)` with the next state, the `hole` flag of the result |
| `kit/src/emit.js`, `kit/src/rows.js`, `kit/src/run.js` | changed | Names that a previous compile seeds, kept fields, and the pass-through of kept fields in `stateOf` |
| `kit/build.js` | changed | Writes `app/src/main/assets/appfile/prompt.json`, and sets the globals `readPlanReply` and `patchAppFile` of the bundle |
| `tools/appfile/AppFileCompiler.kt` | changed | `compileReply`, `patch`, and the `previous` argument |
| `tools/appfile/AppFilePrompt.kt` | new | Reads `appfile/prompt.json`. Fills the repair and change templates |
| `tools/agent/AppFileSteps.kt` | new | The plan step and the change step: calls, compile, repair, outcome |
| `tools/agent/AppAgent.kt` | changed | The plan step before the capability guidance, the change step, `appFile` on the accepted spec |
| `tools/agent/GenerationSettings.kt` | changed | The switch of each provider |
| `tools/ToolSpec.kt` | changed | The host field `appFile`, `withAppFile`, the new rule of `stateForRevision` |
| `tools/ToolEngine.kt` | changed | `ToolGenerationService.appFileCall`, the `planStep` argument, the manual-edit rule |
| `tools/ToolController.kt` | changed | The provider seam of the switch, the trace before the draft |
| `tools/ToolGenerationDiagnostic.kt` | changed | The event `Phase` |
| `cloud/CloudProvider.kt` | changed | The stages `APP_FILE` and `APP_FILE_CHANGE`, `CloudSession.abortCall()` |
| `app/src/androidTest/.../tools/AgentScript.kt` | changed | Scripted failures and waits |
| `app/src/androidTest/.../tools/AppFileLoopWorkflowTest.kt` | new | Each branch of decision 1 through the real `AppAgent` |
| `app/src/androidTest/.../tools/AppFileChatWorkflowTest.kt` | new | The app workflow and the game workflow through the chat path |
| `app/src/androidTest/.../tools/AppFileChangeWorkflowTest.kt` | new | Line changes, refusals, and fallbacks |
| `app/src/androidTest/.../tools/appfile/AppFileStorageTest.kt` | new | `appFile` through parse, save, revision, export, import, restore, and manual edit |
| `ComparisonChatCaptureTest.kt`, `tools/agent_corpus/run_cases.py`, `test_run_cases.py` | changed | The switch from the envelope, the phase events in `report.json`, the plan-step summary, a call budget |

---

## Task 1 (kit): the prompt asset, the reply reader, and the section "Change"

**Files:**
- Modify: `kit/prompt.md` (add `## Change` after `## Dialogue`, line 154)
- Create: `kit/prompt.js`
- Modify: `kit/modelcheck.js:29-33` (`PROMPT`, `PROMPT_HASH`), `:69-73` (`taskText`, `repairText`), `:82-89` (`linesOf`, `OUTSIDE`, `kindOf`). Each moves out, and `modelcheck.js` imports it.
- Modify: `kit/src/compile.js` (`compileAppFile`, line 13)
- Modify: `kit/build.js:87` (`bundle`), `:107-110` (`outputs`) **[checked]** at `fc17a24`
- Test: `kit/test/prompt.test.js`, `kit/test/compile.test.js`, `kit/test/modelcheck.test.js`

**Interfaces:**
- Produces (kit, bundle globals): `readPlanReply(text: string) -> {kind: "outside", word: string} | {kind: "plan", text: string}`. `text` is the reply with blank lines and each markdown fence line removed, and a final newline. The rule is today's `linesOf` and `kindOf` of `modelcheck.js` (`:82-89`), except that "other" is also `"plan"`: the kit reports its faults.
- Produces: the result of `compileAppFile` gets `hole: boolean`. It is true when a need of kind `hole` is in the file after `feedText`, whether or not the compile is clean.
- Produces (asset): `app/src/main/assets/appfile/prompt.json` = `{"system", "repair", "dialogue", "change", "repairTask", "changeTask"}`. The first four keys are the text of the four sections. `repairTask` = `repairText({task: "{{task}}", plan: "{{plan}}", faults: ["{{faults}}"]})`. `changeTask` = `changeText({request: "{{request}}", plan: "{{plan}}"})`. The host replaces `{{faults}}` with the faults joined by `"\n- "`.
- Produces (`kit/prompt.js`): `changeText({request, plan}) = request + "\n\nYour app file:\n" + plan.trimEnd() + "\n\n" + PROMPT.Change`.

The section "Change" (new text, open decision OD-4): one paragraph in the form of "Repair". It says that the person asks for a change of the app file below. The model writes each changed line, each new line, and `delete NAME` lines, and keeps each other line. It must not rename a need or a field of a list that holds data. When the change needs a thing that the lines cannot give, it replies with one line `outside("word")`. It replies with lines only.

- [ ] **Step 1: Write the failing kit tests**
  - `prompt.test.js`, "prompt.json holds each section of prompt.md and the two templates": run `bundle`-side `promptAsset()` (exported from `build.js`), and expect the keys `system, repair, dialogue, change, repairTask, changeTask`. `repairTask` contains `{{task}}`, `{{plan}}`, and `- {{faults}}`, and ends with the text of the section "Repair".
  - `prompt.test.js`, "the section Change names delete and outside": expect `PROMPT.Change` to contain `delete NAME` and `outside(`.
  - `compile.test.js`, "readPlanReply reads one outside line": `readPlanReply('outside("game")\n')` gives `{kind: "outside", word: "game"}`. `readPlanReply('\n```\noutside("3d")\n```\n')` gives the word `3d`.
  - `compile.test.js`, "readPlanReply drops fences and blank lines of a plan": a plan of `plans/calc.plan` in a fence with blank lines gives the text of `calc.plan` with no blank line, and `compileAppFile` of it has no fault.
  - `compile.test.js`, "a plan with a hole sets hole": a plan with one `hole` need gives `hole: true`. `plans/calc.plan` gives `hole: false`.
  - `modelcheck.test.js`: the existing tests stay green with the imports from `prompt.js` and `src/compile.js`.
- [ ] **Step 2: Run `bun test kit`.** Expected: the new tests fail.
- [ ] **Step 3: Implement.** `kit/prompt.js` exports `PROMPT`, `PROMPT_HASH`, `taskText`, `repairText`, `changeText`. `src/compile.js` exports `readPlanReply`. `build.js` exports `promptAsset()`, adds `[at("../app/src/main/assets/appfile/prompt.json"), JSON.stringify(promptAsset(), null, 2) + "\n"]` to `outputs`, and adds `globalThis.readPlanReply = readPlanReply;` in `bundle` after `globalThis.compileAppFile`.
- [ ] **Step 4: Run `bun test kit` and `bun kit/build.js`, then `bun kit/build.js --check`.** Expected: 0 fail, then exit 0.
- [ ] **Step 5: Commit** `kit/prompt.md kit/prompt.js kit/modelcheck.js kit/src/compile.js kit/build.js kit/test app/src/main/assets/appfile` with "Move the prompt texts into an asset of the app and read a plan reply in the kit".

## Task 2 (kit): the next state of a changed app

**Files:**
- Modify: `kit/src/compile.js` (`compileAppFile`)
- Modify: `kit/src/emit.js:41` (`emit`), `:47` (`namer` of the app), `:87` (`rowName` of each list), `:97-108` (`place`)
- Modify: `kit/src/rows.js:24` (`namer`): no change of its signature. `namer(differ, taken)` already takes earlier names **[checked]**.
- Modify: `kit/src/run.js:435` (`stateOf`)
- Test: `kit/test/compile.test.js` (or a new `kit/test/change.test.js`)

**How names behave today [checked]:**
- A plain value `$/root` gets the field `root` with each non-word sign as `_` (`emit.js` `place`, `name("$/" + root, ...)`).
- `namer` (`rows.js:24-39`) gives `f0`, `f1`, and so on, in the order of the calls, to a name that is not safe, is refused, looks like `d`, `v`, `d2`, or is taken. So such a name depends on the order of the lines, and a patch can change it.
- The fields of a need's object `NAME_s` and the fields of the rows of a list each have a namer of their own (`emit.js:87`, `:103`).
- The view objects `d`, `v`, `d2` follow the screen (`emit.js:170-174`). They are derived values.
- `app.fields` (root to field) exists only in the `source` of the definition (`emit.js:268`).
- `stateOf` builds the state of each reply from `app.fields`, the views, and the values only (`run.js:435-447`). So a stored field that the app does not name is lost at the first event.

So names are stable across a patch only for safe names. This task makes them stable for each root that stays.

**Interfaces:**
- Consumes: `patchAppFile` (precondition K1).
- Produces (kit, bundle global): `compileAppFile(text: string, previous?: {appFile: string, stateSchema: object, state: object}) -> {faults, notes, definition, language, hole, state}`. `state` is null with no `previous` or with a fault. With `previous` and no fault, `state` is the next state, and it passes the `stateSchema` of `definition`.

Rules with `previous` (decision 9):
1. The kit compiles `previous.appFile` again. A fault there, or a field of the previous `app.fields` that is not in `previous.stateSchema`, or one with a different kind, is the one fault `"kit: the saved data does not match the app file"`. This covers a kit update that changes names, and a restored or imported app.
2. The namers of the new compile start from the previous names: the app namer from the previous `app.fields` and `app.anim`/`app.drag`, the namer of each list from the previous `app.lists`, and each object namer from the previous object fields.
3. Each stored value whose root stays with the same kind keeps its value. A list keeps its rows by row id. A column that the change adds gets its start value in each stored row: the value that the add form of the list gives a new row.
4. A removed or retyped column of a list with at least one row, and a retyped input, give a fault of the need line: `need NAME: the saved data blocks this change: <what>`.
5. A stored field that the new compile does not name (a removed need, or a view object that the screen no longer has) stays in the state and in the `stateSchema` with its previous schema. Its name is reserved, so no new line gets it. `app.keep` lists these fields.
6. Derived values (formulas, the views, the arrays of a chart, a sorted view) come from the new compile.
7. The inner values of a part (`NAME_s`: the open tab, the step of a lesson) stay when the need line of the part is the same text. Otherwise they start again.
- Produces (run-time): `stateOf(kit, app, shown)` copies each field of `app.keep` from the input state to the reply. Measure the run-time text after it. It must stay at or under 49500 bytes. If it does not, stop and report.

- [ ] **Step 1: Write the failing kit tests** (titles start with "change:")
  - "a list keeps its rows when a figure is added": `previous` is `test/fixtures/shopping.plan` compiled (an `entries` need `items` with `fields=` and 3 start rows, and a `figure` need) **[checked]**, with a stored state of 2 rows. The change adds a second `figure` need. Expect `state` to hold the 2 rows with the same ids and values.
  - "a new column gets its start value in each stored row": add a `check` field to the entries need. Expect each stored row to have the start value of that field kind.
  - "a removed column of a list with rows is a fault": expect one fault that starts with `need ` and contains `the saved data blocks this change`, and `state: null`.
  - "a removed column of an empty list compiles clean".
  - "a retyped input is a fault": change `input("number", ...)` to `input("text", ...)` of the same need.
  - "a removed need keeps its stored field": remove the entries need of `shopping.plan` with 2 rows. Expect the old field and its rows in `state`, its schema in `definition.stateSchema`, and the field in the `app.keep` of the source. Then run `handle` with one action of the new app and expect the field unchanged in the reply.
  - "names stay when an earlier need with an f-name is added": a previous plan has a need whose name the namer refuses (for example `v`). Add a need before it. Expect each previous field name unchanged.
  - "an input value and a counter value stay": a plan with an `input` need and a `counter` need, stored values that differ from the start values, and a change that adds a need. Expect both stored values.
  - "inner values stay when the need line is the same, and start again when it changed": the open tab of a `tabs` need, once with the need line unchanged and once with a changed label.
  - "a kept field survives a second change": remove the entries need, then make a second change that adds a figure. Expect the kept field and its rows after both changes.
  - "a stored state that does not match the app file is a fault": change the type of one field in `previous.stateSchema`. Expect the fault `kit: the saved data does not match the app file`.
  - "the run-time text stays under its bar": `runtimeText()` has at most 49500 bytes.
- [ ] **Step 2: Run `bun test kit`.** Expected: the "change:" tests fail.
- [ ] **Step 3: Implement** the rules above in `compileAppFile`, `emit`, and `stateOf`.
- [ ] **Step 4: Run `bun test kit`, `bun kit/build.js`, `bun kit/build.js --check`.** Expected: 0 fail, exit 0. No saved plan of `kit/plans/` changes its compile result.
- [ ] **Step 5: Commit** with "Compute the next state of a changed app file in the kit".

## Task 3 (Kotlin): the host field `appFile`

**Files:**
- Modify: `tools/ToolSpec.kt`: the constructor (`:45-51`), `providerDefinition` (`:88-98`), `withAppLanguage` (`:123-132`, the form to copy), `stateForRevision` (`:161-172`), `forRestore` (`:173-180`), `parse` (`:192-299`, the persisted field set at `:197`)
- Modify: `tools/ToolEngine.kt`: `saveManualDefinition` (`:524-545`)
- Test: create `app/src/androidTest/java/com/alpha/assistant/tools/appfile/AppFileStorageTest.kt`

Facts **[checked]**:
- `ToolSpec.parse` (not persisted) refuses each host field. `parsePersisted` accepts `appLanguage`, `iconId`, `sceneAssets`, `threeLibrary`, generated images, `lessonImageIntent` (`:196-198`).
- `MiniAppArchive.write` and `read` use `spec.json` and `parsePersisted` (`portability/MiniAppArchive.kt:30`, `:44`, `:64`). So export and import keep a persisted field with no change there.
- The manual edit dialog shows `expected.spec`, the full persisted JSON (`ToolPortabilityDialog.kt:66`, `:81`), and `saveManualDefinition` parses it with `parsePersisted` (`ToolEngine.kt:531`).
- `forRestore` joins the JSON of the old revision with the schema and start state of the current one (`ToolSpec.kt:173-180`).

**Interfaces:**
- Produces: `internal val appFile: String?` on `ToolSpec`. Persisted field name `"appFile"`, a string of 1 to `APP_FILE_MAX_CHARS` (65536) characters.
- Produces: `internal fun withAppFile(text: String?): ToolSpec`. Null removes the field. It checks `MAX_DEFINITION_BYTES` as `withAppLanguage` does.
- Produces: `fun stateForRevision(previous: ToolSpec, next: ToolSpec, raw: String, migrated: String? = null): String`. New rule, only when `previous.appFile != null && next.appFile != null`: `migrated` must not be null, each key of `previous.schema` must be a key of `next.schema` (nothing disappears), and the result is `next.validateState(migrated)`. A type change is permitted: the kit computed the values (task 2). Each other case keeps today's rule.
- Rules for divergence (Implementation choices, open decision OD-6):
  - `providerDefinition()` removes `appFile`, also in its early return condition.
  - `forRestore` removes `appFile`: a restored revision has the schema of a different revision, so it becomes an app of today's form.
  - `saveManualDefinition` removes `appFile` from the parsed definition before `stateForRevision`: a hand edit changes the compiled definition, not the plan.
  - Import keeps `appFile`. Task 2, rule 1, catches a plan that does not match the stored data.

- [ ] **Step 1: Write the failing device tests** in `AppFileStorageTest`:
  - `parseRefusesAppFileInAModelDefinition`: `ToolSpec.parse` of `calc` compiled plus `"appFile"` throws `ToolValidationFailure`.
  - `persistedAppFileSurvivesParseSaveAndRevision`: `parse(definition).withAppFile(plan)`, then `ToolStore.commitTool` and `store.tool(id)`, and `store.revisions(id)`. Each gives the same `appFile`.
  - `exportAndImportKeepAppFile`: `MiniAppArchive.write` then `read`. Expect the same `appFile`.
  - `providerDefinitionHasNoAppFile`.
  - `restoreDropsAppFile`: `ToolSpec.forRestore(current, previous)` with `appFile` on both gives `appFile == null`.
  - `manualEditDropsAppFile`: `ToolEngine.saveManualDefinition` with the persisted JSON of an app with `appFile` gives a saved spec with no `appFile`.
  - `stateForRevisionOfAnAppFileAppTakesTheMigratedState`, `stateForRevisionOfAnAppFileAppRefusesAMissingField`, `stateForRevisionOfAnAppFileAppNeedsAMigratedState`.
- [ ] **Step 2: Run the class on `emulator-5592`.** Expected: fail.
- [ ] **Step 3: Implement** the interfaces above.
- [ ] **Step 4: Run `AppFileStorageTest` and the existing `AgentGenerationWorkflowTest`.** Expected: pass.
- [ ] **Step 5: Commit** with "Keep the app file of a part-built app as a host field".

## Task 4 (Kotlin): the call stages of the app file

**Files:**
- Modify: `cloud/CloudProvider.kt`: `CloudGenerationStage` (`:51`), `CloudSession` (`:111-146`), `CloudChatProvider.respond` (`:301-410`; `think` at `:313`, the system message at `:307`, the transport retry at `:355-361`)
- Modify: `tools/ToolEngine.kt`: `ToolGenerationService.response` (`:148-177`, `jsonObject = true` at `:159`)
- Test: `cloud/OpenAiResponsesWorkflowTest.kt`, `cloud/OpenAiCompatibleProfileTest.kt`, `cloud/AnthropicWorkflowTest.kt`

Facts **[checked]**: each wire chooses `reasoningOn` or `reasoningOff` from the canonical `thinking.type` only (`OpenAiResponsesWire.wireBody:31-32`, `OpenAiCompatibleWire:29-30`, `AnthropicWire:111-112`). So each wire takes a new stage with no wire change. `CloudSession.cancel()` sets `canceled` for good (`:136-139`). `streamLines` reads with blocking IO and has no watcher (`cloud/CloudHttp.kt:66-98`). A failure before the headers with a non-null stage claims the one transport retry of the session (`:355-361`).

**Interfaces:**
- Produces: `enum class CloudGenerationStage { AGENT, APP_FILE, APP_FILE_CHANGE }`. `think = stage == AGENT || stage == APP_FILE_CHANGE`.
- Produces: for `APP_FILE` and `APP_FILE_CHANGE`, the system message is `request.systemInstructions` alone, with no "You are Alphy" text (open decision OD-1). No transport retry: the failure goes up.
- Produces: `fun CloudSession.abortCall()`. It disconnects the attached connection and does not set `canceled`. A later call of the session works.
- Produces: `ToolGenerationService.response(..., jsonObject: Boolean = true, ...)`. The plan stages pass `false`.
- Produces: `internal sealed interface AppFileReply { data class Text(val text: String) : AppFileReply; data class Failed(val reason: String) : AppFileReply }`. `reason` is one of `call_failed`, `first_text_timeout`, `total_timeout`.
- Produces: `internal class AppFileLimits(val firstTextMs: Long = 15_000, val totalMs: Long = 30_000)` with `companion object { val CHANGE = AppFileLimits(60_000, 120_000) }` (the change call reasons at medium. No number measures it: OD-4).
- Produces: `internal suspend fun ToolGenerationService.appFileCall(key: String, session: CloudSession, stage: CloudGenerationStage, instructions: String, input: String, limits: AppFileLimits, onTiming: (ModelCompletionTiming) -> Unit): AppFileReply`. It runs `response` as a child job. A watcher sets the reason `first_text_timeout` when `limits.firstTextMs` passes with no text, or `total_timeout` when `limits.totalMs` passes. Then it calls `session.abortCall()` (for a blocked HTTP read) and cancels the child job (for a call with no connection, such as a fake). The watcher's reason wins over the `pre_headers_*` or `stream_*` failure that the disconnect causes. A `CloudFailure` with code `cancelled`, or a cancel of the caller, goes up unchanged. Each other `CloudFailure` or `IOException` gives `Failed("call_failed")`.

- [ ] **Step 1: Write the failing tests**
  - `OpenAiResponsesWorkflowTest.appFileStageSendsTheInstructionsAloneAtEffortLow`: `CloudRequest(turns, false, "PLAN SYSTEM", generationStage = APP_FILE)`. Expect `instructions == "PLAN SYSTEM"`, `reasoning.effort == "low"`, `max_output_tokens == 4096`, no `text.format`, and no developer message.
  - `OpenAiResponsesWorkflowTest.appFileChangeStageReasonsAtMedium`: `reasoning.effort == "medium"`, no `max_output_tokens`.
  - `OpenAiResponsesWorkflowTest.appFileStageMakesNoTransportRetry`: the loopback server closes the first connection before the headers. Expect one HTTP request and the failure `pre_headers_io`.
  - `OpenAiResponsesWorkflowTest.abortCallEndsTheStreamAndKeepsTheSession`: a server that sends `responsesStart("Hel")` and waits. `abortCall()` after the first text. Expect the call to end within 5 s, and a second request on the same session to complete.
  - `OpenAiCompatibleProfileTest.appFileStageTurnsThinkingOff` (DeepSeek: `thinking.type == "disabled"`; OpenRouter: effort `low`). `AnthropicWorkflowTest.appFileStageUsesOutputEffortLow`.
- [ ] **Step 2: Run the three classes.** Expected: the new tests fail.
- [ ] **Step 3: Implement** the interfaces above.
- [ ] **Step 4: Run the three classes and `AgentGenerationWorkflowTest`.** Expected: pass. `newAppRunsAndFinishesWithHostChecks` still sees `AGENT` and `jsonObject` on each call.
- [ ] **Step 5: Commit** with "Add the call stages of the app file with their own limits".

## Task 5 (Kotlin): the compiler and the prompt on the device

**Files:**
- Modify: `tools/appfile/AppFileCompiler.kt` (`compile`, `:21-29`; `read`, `:33-39`)
- Create: `tools/appfile/AppFilePrompt.kt`
- Test: `app/src/androidTest/java/com/alpha/assistant/tools/appfile/AppFileProofTest.kt`

**Interfaces:**
- Consumes: the globals `readPlanReply` (task 1), `patchAppFile` (K1), `compileAppFile(text, previous)` (task 2).
- Produces: `AppFileCompilation` gets `val hole: Boolean = false` and `val state: String? = null` (JSON text).
- Produces: `internal data class AppFilePrevious(val appFile: String, val stateSchema: String, val state: String)`.
- Produces: `internal sealed interface AppFileRead { data class Outside(val word: String) : AppFileRead; data class Plan(val text: String, val compilation: AppFileCompilation) : AppFileRead }`.
- Produces on `AppFileCompiler`:
  - `suspend fun compileReply(reply: String): AppFileRead`. One `evaluateScript`: `readPlanReply`, then `compileAppFile` of the plan text. A sandbox status that is not OK gives `Plan(reply, AppFileCompilation(status, ...))`.
  - `suspend fun patch(plan: String, reply: String, previous: AppFilePrevious? = null): AppFileRead`. One `evaluateScript`: `readPlanReply(reply)`. An outside reply gives `Outside`. Else `patchAppFile(plan, text)`, then `compileAppFile(merged, previous)`. `Plan.text` is the merged plan.
  - `compile(appFile: String, previous: AppFilePrevious? = null)`.
- Produces: `internal class AppFilePrompt(val system: String, val repair: String, val dialogue: String, val change: String, private val repairTask: String, private val changeTask: String)` with `fun repairText(task: String, plan: String, faults: List<String>): String`, `fun changeText(request: String, plan: String): String`, and `companion object { fun load(context: Context): AppFilePrompt }` (reads `appfile/prompt.json` once, as `AppFileCompiler.kit` does).

- [ ] **Step 1: Write the failing device tests** in `AppFileProofTest`:
  - `aFencedOutsideReplyIsRead`: `compileReply("```\noutside(\"game\")\n```")` gives `Outside("game")`.
  - `aFencedPlanCompilesClean`: `calc.plan` in a fence gives `Plan` with status `ok`.
  - `aRepairReplyMergesAndCompiles`: `calc.plan` with one broken need line, and a reply that writes the line again. Expect status `ok` and a merged text with no broken line.
  - `aChangeKeepsTheRowsOfAList`: `shopping.plan` with 2 stored rows, a reply that adds a figure. Expect `state` with the 2 rows.
  - `thePromptAssetFillsItsTemplates`: `repairText("T", "P", listOf("a", "b"))` contains `"T"`, `"P"`, `"- a\n- b"`, and ends with `AppFilePrompt.load(context).repair`.
- [ ] **Step 2: Run `AppFileProofTest`.** Expected: the new tests fail.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run `AppFileProofTest` and `AppFileLookTest`.** Expected: pass.
- [ ] **Step 5: Commit** with "Read, merge, and compile a reply of the app file on the device".

## Task 6 (Kotlin and Python): the trace of the time before the first screen

**Files:**
- Modify: `tools/agent/GenerationSettings.kt`
- Modify: `tools/ToolGenerationDiagnostic.kt` (`ToolGenerationTraceEvent`, `:20-52`; `ToolGenerationTrace`, `:55-96`)
- Modify: `tools/ToolController.kt`: `generateAutomatic` (`:294-352`, `createDraft` at `:313`), `generateWithPreview` (`:419-435`, the trace at `:426`)
- Modify: `tools/agent/AppAgent.kt`: `AppAgentRequest` (`:46-68`), `run` (`capabilityGuidance` at `:112`, the step dispatch at `:397-398`)
- Modify: `tools/ToolEngine.kt`: `ToolGenerationService.generate` (`:184-212`), `generateWithAgent` (`:214-228`)
- Modify: `app/src/androidTest/.../tools/ComparisonChatCaptureTest.kt` (`traceEvent`, `:797-819`; the envelope, `:144-167`; the settings, `:223`)
- Modify: `tools/agent_corpus/run_cases.py` (`run_case`, `:58-134`; `main`, `:137-177`), `tools/agent_corpus/test_run_cases.py`

Facts **[checked]**: the trace exists only when `generationTraceObserver` is set (`ToolController.kt:426`), and it is made after `createDraft`. `Run.completion` adds each timing to the next `AgentStep` (`ToolGenerationDiagnostic.kt:69-75`), so a plan call that used it would make a wrong `host_ms` in `step_timing` (`run_cases.py:43-55`).

**Interfaces:**
- Produces: `data class Phase(operationId, attempt, atNanos, val name: String, val detail: String?, val ms: Long?) : ToolGenerationTraceEvent`.
- Produces: `fun ToolGenerationTrace.phase(name: String, detail: String? = null, ms: Long? = null)` (attempt 0), and `Run.phase(...)` with the attempt of the run.
- Phase names: `draft_start`, `draft_end`, `capability_guidance_start`, `capability_guidance_end`, `agent_call_start` (step 1 only), `plan_call_start`, `plan_first_text` (`ms` from the call start), `plan_reply_end` (`ms`), `compile` (`detail` = status, `ms`), `repair_call_start`, `change_call_start`, `plan_outcome` (`detail` = `accepted`, `outside:<word>`, or `fallback:<reason>`).
- Detail values that leave the device: the word of `outside` only when it matches `[a-z]{1,16}`, else `outside:other`. Reasons only from this list: `call_failed`, `first_text_timeout`, `total_timeout`, `hole`, `faults`, `host_check`, `data_blocks`, `outside`, `empty`, `compile_<STATUS>` with a status of `AppFileCompiler`.
- Produces: `AppAgentRequest.onPhase: ((name: String, detail: String?, ms: Long?) -> Unit)? = null`. `ToolGenerationService.generate` sets it to `run::phase`.
- Produces: `ToolController.generateWithPreview(..., trace: ToolGenerationTrace?)`. `generateAutomatic` makes the trace before `createDraft` and emits `draft_start` and `draft_end` around it.
- Produces (Python): `plan_step_summary(events: list) -> dict` with keys `outcome`, `detail`, `plan_ms`, `first_text_ms`, `compile_ms` (list), `repairs`, `guidance_ms`, `draft_ms`. `run_case` adds it as `summary["plan_step"]`.
- Produces: `GenerationSettings.planStep(provider: AiProvider): Boolean` (preference `plan_step_<provider.id>`, default false) and `GenerationSettings.setPlanStep(provider: AiProvider, on: Boolean)` (with `commit()`, as `screenshotReview`, `GenerationSettings.kt:10-13`). Task 7 reads it.
- Produces (envelope): `"plan_step": true|false`. `captureFrozenSlot` calls `GenerationSettings(app).setPlanStep(account.provider, value)` and restores the earlier value in its cleanup, as for `screenshotReview` (`:223`). `run_cases.py --plan-step on|off` sets it.
- Produces: `run_cases.py --call-budget N`: before each case, it sums `measurements.provider_http_calls` of the run so far and stops when the sum reaches `N`.

- [ ] **Step 1: Write the failing tests**
  - `test_run_cases.py`: `test_plan_step_summary_of_an_accepted_plan`, `test_plan_step_summary_of_an_outside_reply`, `test_plan_step_summary_without_phases_is_none`, `test_call_budget_stops_before_the_case_that_passes_it`.
  - `AgentGenerationWorkflowTest.theTraceShowsTheGuidanceAndTheFirstAgentCall`: a trace through `ToolGenerationService.generate`. Expect the phases `capability_guidance_start`, `capability_guidance_end`, `agent_call_start` in this order, before the first `AgentStep`.
  - `AutomaticToolWorkflowTest.theTraceShowsTheDraftBeforeTheGeneration`: set `vm.tools.generationTraceObserver`, send one create request. Expect `draft_start` and `draft_end` before `capability_guidance_start`.
- [ ] **Step 2: Run them.** Expected: fail.
- [ ] **Step 3: Implement**, and add `is Phase -> put("kind", "phase").put("name", ...).put("detail", ...).put("ms", ...)` to `traceEvent`.
- [ ] **Step 4: Run `python3 -m unittest tools/agent_corpus/test_run_cases.py` and the two device tests.** Expected: pass.
- [ ] **Step 5: Commit** with "Trace the phases before the first screen of a new app".

## Task 7 (Kotlin): the plan step of a new app

**Files:**
- Create: `tools/agent/AppFileSteps.kt`
- Modify: `tools/agent/AppAgent.kt`: `AppAgent` constructor (`:82-86`), `AppAgentRequest` (`:46-68`), `run` (`:105-428`; `threeD` at `:111`, `instructions` at `:112-115`, `requestLanguage` at `:117-118`, `validate` at `:140-244`, the loop at `:380`)
- Modify: `tools/ToolEngine.kt`: `appAgent` (`:143-146`), `generate` (`:184-212`), `generateWithAgent` (`:214-228`), `ToolEngine.generate` (`:358-402`)
- Modify: `tools/ToolController.kt`: `generateWithPreview` (`:419-435`)
- Modify: `app/src/androidTest/.../tools/AgentScript.kt`
- Test: create `app/src/androidTest/.../tools/AppFileLoopWorkflowTest.kt` and `app/src/androidTest/.../tools/AppFileChatWorkflowTest.kt` (the tests of task 8)

**Interfaces:**
- Consumes: `GenerationSettings.planStep(provider)` (task 6).
- Produces: the switch seam. `ToolController.generateWithPreview` reads `keys.provider()` (`data/SecureKeyStore.kt:59`, no decryption) **[checked]**. When `GenerationSettings(app).planStep(provider)` is true, it passes `planStep = AppFileLimits()` to `ToolEngine.generate`, and from there to `ToolGenerationService.generate` and `AppAgentRequest.planStep`. The other three sites that make a `ToolGenerationService` (`ShellViewModel.kt:314` for courses, `AutomationTools.kt:15`, `ToolWidgets.kt:194`) pass nothing, so they keep today's steps **[checked]**.
- Produces: `AppAgentRequest.planStep: AppFileLimits? = null`.
- Produces: `AppAgent(service, sandbox, complete, appFileCall: (suspend (stage: CloudGenerationStage, instructions: String, input: String, limits: AppFileLimits) -> AppFileReply)? = null)`. `ToolGenerationService.appAgent` sets it with `appFileCall` of task 4, and with an `onTiming` that emits `plan_first_text` and `plan_reply_end`. It does not use `Run.completion`.
- Produces in `AppFileSteps.kt`:
  ```kotlin
  internal sealed interface AppFileOutcome {
      class Clean(val appFile: String, val definition: JSONObject, val state: String?, val calls: Int) : AppFileOutcome
      class Outside(val word: String) : AppFileOutcome
      class Fallback(val reason: String) : AppFileOutcome
  }
  internal class AppFileSteps(
      private val compiler: AppFileCompiler, private val prompt: AppFilePrompt,
      private val call: suspend (CloudGenerationStage, String, String, AppFileLimits) -> AppFileReply,
      private val phase: (String, String?, Long?) -> Unit,
  ) {
      suspend fun plan(task: String, limits: AppFileLimits): AppFileOutcome
      suspend fun change(request: String, previous: AppFilePrevious, limits: AppFileLimits): AppFileOutcome
  }
  ```
  `Clean.definition` already holds the key `language` from the compile, because `validate` reads that key (`AppAgent.kt:142`) **[checked]**.
- The rule of `plan` (decision 1): the call with stage `APP_FILE`, instructions `prompt.system`, input `task`. `Failed` gives `Fallback(reason)`. `Outside` gives `Outside(word)`. A `Plan` with `hole` gives `Fallback("hole")`. Status `ok` gives `Clean`. A status other than `ok` and `faults` gives `Fallback("compile_<STATUS>")`. Faults give one repair call (stage `APP_FILE`, input `prompt.repairText(task, text, faults)`), then `compiler.patch`. `ok` gives `Clean`, and each other result gives `Fallback` with its reason (`faults` for faults again).
- The rule of `run` (decisions 1, 2, 3):
  - The gate: `request.planStep != null && previous == null && request.lesson == null && request.images.isEmpty() && request.sceneAssets.isEmpty() && !request.testFix && appFileCall != null`.
  - `threeD` becomes `val threeD by lazy { lesson == null && ThreeSceneWebView.available() }`, so the plan step does not pay the WebView check. A kit definition has no `runtime` field, so `validate` does not read `threeD` for it **[checked]**: `emit.js:272` makes no `runtime`.
  - `instructions`, `requestLanguage`, and `task` move after the plan step, before the saved-draft check (`:366`). `validate` does not read them **[checked]**.
  - The plan step sends `request.prompt` as the task. That is the `ChatToolReply.generationPrompt` text that the model check sent (`modelcheck.js` `taskText` mirrors it) **[checked]**. It does not send `taskMessage`.
  - `Clean`: `validate(definition, appFile = plan)` (new optional parameter that calls `withAppFile` after `withIcon`). A `Feedback` gives `plan_outcome fallback:host_check` and the loop. Else return `AppAgentResult(candidate.preview, summary = candidate.spec.title, steps = outcome.calls, changed = true)`, and emit `plan_outcome accepted`. `edited` becomes true.
  - `Outside`, and each `Fallback`: emit `plan_outcome`, then the loop as today. Nothing of the plan goes to the loop.
  - A `CloudFailure("cancelled")` or a `CancellationException` goes up. No loop starts.
  - `request.progress`: `COMPLETING` at the plan call start, `VALIDATING` at the compile.
- Produces in `AgentScript.kt`: `sealed interface Scripted { data class Text(val text: String); data class Fails(val code: String); data class Waits(val firstTextMs: Long, val text: String); data class Stalls(val firstLine: String, val stallMs: Long) }`, and a constructor `AgentScriptProvider(vararg replies: Scripted)`. The string constructor stays. `Waits` emits nothing for `firstTextMs` with `delay`, so a cancel ends it. `Stalls` emits `firstLine` at once, then waits `stallMs` with `delay`. `Fails` throws `CloudFailure(code, ...)`.

- [ ] **Step 1: Write the failing tests** in `AppFileLoopWorkflowTest`. Each one calls `ToolGenerationService.generate` with `planStep = AppFileLimits(500, 2000)` and a collecting trace, and the plan `calc.plan` from the test assets (`plan("calc")`, `AppFileProofTest.kt:30`). The game loop replies are `writeAppReply(tipApp())` and `finish(splitCheck)` of `AgentGenerationWorkflowTest`.
  - `aCleanPlanIsAcceptedWithOneCall`: calls `== 1`, the stage `APP_FILE`, `jsonObject == false`, `systemInstructions == AppFilePrompt.load(context).system`, the user turn `== prompt`. The spec has `appFile == plan("calc")` and the title of `calc.plan`. No phase `capability_guidance_start`. `plan_outcome accepted`.
  - `anOutsideReplyGoesToTheLoop`: replies `outside("game")`, then the game loop. Calls 3, stages `APP_FILE, AGENT, AGENT`. `plan_outcome outside:game`. The spec has no `appFile`. The first `AGENT` turn has no plan text.
  - `faultsThenACleanRepairAreAccepted`: a broken calc, then a reply line that fixes it. Calls 2, both `APP_FILE`. The second user turn starts with the prompt and contains `- ` and the fault text. The spec has the merged plan.
  - `faultsTwiceGoToTheLoop`: calls `APP_FILE, APP_FILE, AGENT, AGENT`. `plan_outcome fallback:faults`.
  - `aPlanWithAHoleGoesToTheLoop`: `fallback:hole`, and no repair call.
  - `aFailedPlanCallGoesToTheLoop`: `Scripted.Fails("pre_headers_io")`, then the game loop. `fallback:call_failed`.
  - `aSlowPlanCallGoesToTheLoopAtItsLimit`: `Scripted.Waits(5000, plan)` with `AppFileLimits(500, 2000)`. Expect `fallback:first_text_timeout`, and the first `AGENT` call starts under 1500 ms after `plan_call_start`.
  - `aCallWithAFirstTextThatRunsPastItsTotalGoesToTheLoop`: `Scripted.Stalls("need total = figure(...)", 5000)` with `AppFileLimits(500, 2000)`. Expect `fallback:total_timeout`.
  - `aModelPlanBecomesASavedAppThatWorks` and `aGameRequestGoesThroughOutsideToTheLoop`: the chat-path tests of task 8, written in this step.
  - `aHostCheckFailureGoesToTheLoop`: a plan whose `lang` differs from its visible text (the language check of `validate` fails). `fallback:host_check`.
  - `aBusySandboxGoesToTheLoop`: hold the transient slot with a long `evaluateScript` while the plan compiles, as `aCallThatOverlapsIsBusyNotAHang` does. `fallback:compile_BUSY`.
  - `aStopDuringThePlanCallEndsTheRequest`: `Waits(5000, ...)` and `session.cancel()` after 200 ms. Expect `CloudFailure("cancelled")` and no `AGENT` call.
  - `theSwitchOffSendsNoPlanCall`: `planStep = null`. Calls are `AGENT` only.
  - `aRequestWithAnImageSendsNoPlanCall`: `images = listOf(<a data URL>)`. No `APP_FILE` call.
- [ ] **Step 2: Run `AppFileLoopWorkflowTest`.** Expected: fail.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run `AppFileLoopWorkflowTest`, `AgentGenerationWorkflowTest`, `AgentImageWorkflowTest`, `AgentThreeSceneWorkflowTest`, `LessonAgentWorkflowTest`.** Expected: pass.
- [ ] **Step 5: Commit** with "Build a new app from one plan call when the kit can serve it".

## Task 8 (Kotlin): the app workflow and the game workflow through the chat path (tests written in step 1 of task 7)

**Files:**
- Test: create `app/src/androidTest/.../tools/AppFileChatWorkflowTest.kt`

This task holds the test file. Write it in step 1 of task 7, run it red there, and commit it with task 7. The steps below are part of task 7.

Use the `Fixture` form of `chat/AutomaticToolWorkflowTest.kt:87-121` **[checked]**: `SecureKeyStore.save(..., AiProvider.OPENAI)`, `ShellViewModel` with the fake provider, `send`, `idle`. Set `GenerationSettings(app).setPlanStep(AiProvider.OPENAI, true)` in the test and restore it in `close`. The fake provider answers `chatDecision` with a `create` decision, and each generation stage from a script.

- [ ] **Step 1: Write the failing tests**
  - `aModelPlanBecomesASavedAppThatWorks`: the plan is the M1 model fixture `holdout-tip-ru`, read from the test assets `appfile/model/holdout-tip-ru.plan` **[checked]**. The scenario comes from the request text of `index.json`: set the bill, set the tip, expect the share that you compute by hand. Run it with `AppSteps` on the saved app (the form of `AppScenarioCheckTest.runScenarios`). Keep the expected values in the test. Expect one `APP_FILE` call, no `AGENT` call, and a chat card with the app.
  - `aGameRequestGoesThroughOutsideToTheLoop`: the script is `outside("game")`, then a canvas game by `write_app` and `finish`, as `ComparisonChatCaptureTest.replayGeneratedCanvasGameFromChat` uses. Expect the stages `APP_FILE, AGENT, AGENT`, a saved app with no `appFile`, and a press that changes its score.
- [ ] **Step 2: Run the class in step 4 of task 7.** Expected: pass. It goes into the commit of task 7.

## Task 9 (Kotlin): a later change by lines

**Files:**
- Modify: `tools/agent/AppFileSteps.kt` (`change`)
- Modify: `tools/agent/AppAgent.kt`: `run`, `validate` (the state at `:193-200`), `taskMessage` (`:683-708`)
- Test: create `app/src/androidTest/.../tools/AppFileChangeWorkflowTest.kt`

**Interfaces:**
- Consumes: `compiler.patch(plan, reply, previous)` (task 5), `ToolSpec.stateForRevision(..., migrated)` (task 3), `AppFileLimits.CHANGE` (task 4).
- The rule of `change` (decision 9): the call with stage `APP_FILE_CHANGE`, instructions `prompt.system`, input `prompt.changeText(request, previous.appFile)`. Then `compiler.patch(previous.appFile, reply, previous)`. `Outside` gives `Fallback("outside")`. Faults give one repair call (stage `APP_FILE`, input `prompt.repairText(changeInput, merged, faults)`), then `compiler.patch(merged, reply, previous)`. A fault that contains `the saved data blocks this change` after the repair gives `Fallback("data_blocks")`. Each other failure gives its reason as in `plan`.
- The gate in `run`: `previous?.appFile != null && request.lesson == null && !request.testFix`. Until task 10, a test-and-fix request on an app with `appFile` returns `TestFixResult(TestFixStatus.UNVERIFIED, <detail>)` from `ToolEngine.testAndFix` (`ToolEngine.kt:128`) **[checked]** with no model call, so it never reaches `edit_app`. The chat shows `R.string.chat_app_test_unverified` for that status (`ShellViewModel.kt:1908`) **[checked]**. The chat shows `detail` below that headline (`ShellViewModel.kt:1913`, `"$headline\n${result.detail}"`) **[checked]**, so `detail` comes from a new string resource `chat_app_test_by_lines_pending` with the translations of the other `chat_app_test_*` strings. Test: `aTestFixOnAnAppFileAppMakesNoCall` (calls `== 0`, status `UNVERIFIED`). This stub differs from decision 9 (open decision OD-5). The switch does not gate a change: an app with `appFile` exists only after the plan step made it, and it never gets `edit_app` (decision 9).
- "Stored data of the person" (Implementation choice): `hasStoredData = !sameValue(JSONObject(request.base.state), JSONObject(previous.initialState))`. `sameValue(a: Any?, b: Any?): Boolean` is a new private helper in the companion of `AppAgent`: a deep comparison of `JSONObject`, `JSONArray`, numbers by value, and other values by `equals`. The main code does not use `JSONObject.similar` **[checked]** (`grep -rn ".similar(" app/src/main/java` finds nothing). The host computes it in `run`. **[assumption]** A new kit app keeps its start state until a press: `handle` returns `state` unchanged for `preview` and `render` (`run.js:501`) **[checked]**, and `acceptDraft` saves `previewState` (`ToolEngine.kt:447`) **[checked]**. But an `open` event runs `kit.tick()` (`run.js:510-513`) **[checked]**, and its effect on a stored state with no timer is not checked. Test: `aNewAppHasNoStoredDataUntilAPress` sends an `open` event before its first assertion. If `open` changes the state, the controller rules on a narrower comparison.
- After `Clean`: `validate(definition, appFile = merged, migrated = state)`. It calls `stateForRevision(previous, spec, base.state, migrated)`. Return `AppAgentResult(preview, summary = spec.title, steps = calls, changed = true)`.
- After `Fallback` or a host check failure:
  - No stored data: today's loop as for a new app. `previous` counts as null for the task text (`taskMessage(request, null, ...)`), for `draft`, and for the state (`spec.initialState`). The saved app keeps its id, gets `revision + 1`, and has no `appFile`.
  - Stored data: throw `CloudFailure("widget_state", "The saved data blocks this change. The app is unchanged.")`. The chat shows `R.string.tool_generation_state` for this code (`ToolUi.kt` `toolErrorMessage`, `:145`) **[checked]**: "Новая версия не подходит к сохранённым данным. Уточните запрос. Данные не изменились."
- `privateApp` apps: a kit app sets `capabilityResults` never today **[checked]**: `emit.js:272` writes no such field. So the rule of a private app does not apply in this plan.

- [ ] **Step 1: Write the failing tests** (all with a fake model; the base app is `shopping.plan` compiled and saved with `appFile`):
  - `aChangeByLinesKeepsTheRowsOfAList`: 2 rows added by presses, then a refine whose reply adds a second figure line. Expect one `APP_FILE_CHANGE` call with `jsonObject == false`, the same 2 rows, the new figure on the screen, and `appFile` with the new line.
  - `aChangeThatAddsAColumnFillsEachRow`.
  - `aRefusedChangeWithStoredDataChangesNothing`: a reply that retypes a column, twice. Expect `widget_state`, the same revision, state, and `appFile`.
  - `aRefusedChangeWithNoStoredDataWritesANewApp`: no press, the same replies, then `writeAppReply(tipApp())` and `finish`. Expect a saved revision with no `appFile`.
  - `aNewAppHasNoStoredDataUntilAPress`: right after accept, and after one `open` event, `sameValue` is true. After one press, false.
  - `anAppWithAppFileNeverGetsEditApp`: the first `AGENT` call of a fallback has no `edit_app` in its task.
- [ ] **Step 2: Run the class.** Expected: fail.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the class, `AppFileLoopWorkflowTest`, and `AgentGenerationWorkflowTest`.** Expected: pass.
- [ ] **Step 5: Commit** with "Change a part-built app by lines and keep the data of the person".

## Task 10 (Kotlin): test-and-fix by lines (after the ruling on OD-5)

**Files:**
- Modify: `tools/ToolEngine.kt`: `ToolGenerationService.testAndFix` (`:128-141`)
- Modify: `tools/agent/AppAgent.kt`: `run` (the test-fix gate)
- Test: `AppFileChangeWorkflowTest`

**Interfaces (option A of OD-5):** a test-and-fix request on an app with `appFile` runs `change` with the report of the person as the request. `TestFixResult` mapping:
- A clean patch that passes the host checks: `VERIFIED`, `candidate = preview`, detail = the app title.
- A reply with no line: `PASSED`, detail "No change was needed.". `AppFileSteps.change` gives `Fallback("empty")` for it.
- Each fallback with stored data: `UNREPAIRED`, with the message of `widget_state`.
- Each fallback with no stored data: today's test-and-fix loop as for a new app.

- [ ] **Step 1: Write the failing tests**: `aTestFixByLinesSavesAVerifiedRepair`, `aTestFixWithNoLineIsPassed`, `aRefusedTestFixWithStoredDataIsUnrepaired`.
- [ ] **Step 2: Run them.** Expected: fail.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run `AppFileChangeWorkflowTest`.** Expected: pass.
- [ ] **Step 5: Commit** with "Test and fix a part-built app by lines".

## Task 11 (no tracked file): paired corpus run

**Files:** none tracked, except a results document that the controller writes. Evidence goes to `evidence/appfile-loop/paired-<head>/` (not in Git).

- [ ] **Step 1: Build and install** the debug APK and the test APK at one head. Check the APK `lastUpdateTime` before and after the run (memory note: other sessions install on emulators).
- [ ] **Step 2: Run the off arm**: `python3 tools/agent_corpus/run_cases.py --serial emulator-5592 --corpus tools/agent_corpus/showcase.json --output <dir>/off --plan-step off --call-budget <B>`, then the same with `holdout.json`.
- [ ] **Step 3: Run the on arm** with `--plan-step on`.
- [ ] **Step 4: Report for each arm**: `first_ui_ms` and `completed_ui_ms` (median, p90, max), the share of the 45 requests with `plan_step.outcome == "accepted"`, each `fallback` with its reason, the `outside` words, and the phase times (`guidance_ms`, `draft_ms`, `plan_ms`). Make picture sheets with `tools/agent_corpus/pair_sheet.py`. No corpus case has a workflow check: `Plan5WorkflowChecks.cases` holds no id of `showcase.json` or `holdout.json` **[checked]**. So the report says that no function check exists for these cases.
- Warm runs: `run_cases.py` starts one process for each case (`am instrument` per case, `run_cases.py:77`) **[checked]**. So the runner does not permit a warm run as it is (open decision OD-8).

**Budget of model calls (worst case):**
- For one case of the off arm: the decision call 1, its correction 1, a search plan call 1, a grounding call 1, at most 2 web searches, at most 20 agent steps with at most 2 transient retries each (60), and 1 transport retry of the session. Sum: 67.
- For one case of the on arm: 67 + 1 plan call + 1 repair call = 69.
- The 45 cases of both arms: 45 × (67 + 69) = 6120 calls.
- **[assumption]** The expected count is much lower: each saved case of `final-30d43aa` has one decision call and no search (`delay-study.md`, section 0). Test: sum `provider_http_calls` of the off arm after its first 5 cases.
- Proposal: `--call-budget 900` for each arm, and the controller states the final number before the run.

---

## Review focus

The five inputs most likely to hurt a person, and the test that pins each one:

1. A reply in a markdown fence, or with blank lines: it must compile as the model check compiled it. Tests: task 1 `readPlanReply drops fences and blank lines of a plan`, task 5 `aFencedPlanCompilesClean`.
2. The person uses an open app while a plan compiles, so the sandbox slot is busy: the request must go on to the loop, with no error. Test: task 7 `aBusySandboxGoesToTheLoop`.
3. The person stops the request during the plan call: no loop starts, and nothing is saved. Test: task 7 `aStopDuringThePlanCallEndsTheRequest`.
4. A change removes a list that holds rows: the rows must stay in the stored state, also after the next press. Test: task 2 `a removed need keeps its stored field`.
5. A kit update (or a restore, or an import) changes the field names of a stored app: the next change must not lose data. Tests: task 2 `a stored state that does not match the app file is a fault`, task 9 `aRefusedChangeWithStoredDataChangesNothing`.

## Seams for later plans (not built here)

- Dialogue screens: `AppFileSteps.plan(task, limits)` takes the whole task text, so a later plan adds `prompt.dialogue` as the host line under the request, as `modelcheck.js` `taskText` does.
- A preview while the plan streams: `appFileCall` has an `onTiming`. A later plan adds an `onText` observer of streamed lines to it.
- A plan call in parallel with the routing call: `AppFileSteps.plan` reads no state of `AppAgent`, so a later plan can start it from `ShellViewModel`. `CloudSession` still holds one connection (`CloudProvider.kt:116`), so two calls need two sessions.
- Lesson slides and other providers' switches: `GenerationSettings.planStep(provider)` and the gate in `run`.

## Assumptions

| Assumption | Basis | Test |
| --- | --- | --- |
| A new kit app stores its start state unchanged | `run.js:501`, `ToolEngine.kt:447` | task 9 `aNewAppHasNoStoredDataUntilAPress` |
| Most of stage C (4.79 s) is `capabilityGuidance` | `delay-study.md` section 2 | task 11: `guidance_ms` against the time from `draft_end` to `agent_call_start` in the off arm |
| The phone calls take about the time of the model check (3.97 s median) | The check ran on a desktop with 98.5% cached input (results document) | task 11: `plan_ms` of the on arm |
| A kit app sets no `capabilityResults` and no `runtime` | `emit.js:272` | task 7 `aCleanPlanIsAcceptedWithOneCall` reads the spec |
| The change prompt gives clean patches | none: no model check measured a change | OD-4 |

## Open decisions (the controller rules before the named task starts)

| ID | Blocks | Question | Option 1 and its cost | Option 2 and its cost |
| --- | --- | --- | --- | --- |
| OD-1 | task 4 | `CloudChatProvider.respond` puts "You are Alphy, a helpful assistant. …" before each system text (`CloudProvider.kt:307`). The model check sent the section "System" alone | Plan stages send the section alone (this plan). Cost: one branch in `respond`. The call is then the measured call | Keep the preface. Cost: none in code, but the prompt in the app is not the prompt that the check measured |
| OD-2 | task 4 | Time limits of the plan call | 15 s to first text, 30 s in all (this plan). Basis: the first text came at most after 12.07 s, and the longest whole call took 15.53 s (84 calls of the two final runs). A stalled call then costs at most 30 s before the loop | 20 s and 45 s (decisions). Cost: a stalled call adds up to 45 s to the about 60 s of the loop |
| OD-3 | task 7 | The check measured `gpt-6.1-terra`. The person can choose `gpt-6.1-sol` (`ProviderProfile.kt:43`) | The gate also needs the first chat model of the profile. Cost: one condition, and sol users get today's loop | No model condition. Cost: an unmeasured model on the fast path when the switch goes on |
| OD-4 | task 9 | The section "Change" is new text, and no model check measured a change by lines (and its reasoning at medium, and `AppFileLimits.CHANGE`) | Run a small model check of changes (for example the 8 M1 scenario apps with 2 change requests each, at most 2 calls each, so at most 32 calls) before the switch goes on | Ship it behind the switch, and measure only in the paired runs. Cost: the paired runs have no change requests, so the change path stays unmeasured |
| OD-5 | tasks 9, 10 | Test-and-fix on an app with `appFile`. Decision 9 sends it through line steps. Between tasks 9 and 10 this plan answers `UNVERIFIED` with no call | One change call with no `run` tool, and the mapping of task 10. Cost: no reproduction of the problem before the fix | Keep the design: a loop with `run` and a line-patch tool. Cost: a new tool and a new guide text in `AppAgentGuide` |
| OD-6 | task 3 | Restore and manual edit of an app with `appFile` | Both drop `appFile` (this plan). Cost: the app keeps the kit run-time as its `source`, so a later change by today's loop shows the model about 48 KB of minified run-time and offers `edit_app` on it, the case that the last point of decision 9 forbids. Third option (no task builds it yet): such an app (kit source, no `appFile`) gets the no-data or with-data rule of task 9 in place of `edit_app` | Keep `appFile`. Cost: the plan and the compiled definition can differ, and task 2, rule 1, then refuses each change of an app with data |
| OD-7 | task 2 | A removed need keeps its field (decision 9). The run-time must carry the field (`stateOf`, `run.js:435`), so the run-time text grows | Add `app.keep` (this plan). Cost: run-time bytes against the 49500 bar, and kept fields count against the 40 fields of a state | Make the removal of a need with stored data a fault. Cost: it differs from decision 9 |
| OD-8 | task 11 | A warm run needs a second request in one process | No warm arm (this plan). Cost: no number for a warm process | Add an envelope field `repeat: 2` to `captureFrozenSlot`, which sends the request a second time in a new conversation. Cost: a test change and twice the calls |

---

## Rulings of the controller


The draft is `plan-a-draft.md` (anchors at `fc17a24`). The controller read all of it on 2026-11-19. These rulings bind each task. Where a ruling differs from the draft, the ruling wins, and the task's decisions file repeats it.

### Rulings on the open decisions

- **OD-1: the plan stages send the section "System" alone**, with no "You are Alphy" preface. Reason: it is the call that the model check measured.
- **OD-2: 20 s to the first text and 45 s in all** for the plan call and its repair (the decisions file), not 15 s and 30 s. Reason: the largest first-text time of 84 desktop calls was 12.07 s, which leaves too little room under 15 s for a phone with a cold connection. A false timeout turns a plan of about 16 s into a loop of about 60 s. A real stall is rare and costs 15 s more. Look at both values again after the paired runs.
- **OD-3: the gate also needs the measured model.** The plan step runs when the chat model of the account is the first chat model of the provider profile (`gpt-6.1-terra` for OpenAI). An account with a different model keeps today's loop. Reason: the owner chose terra as the default, and sol was measured only with the first prompt version, with a lower clean rate.
- **OD-4: a small model check of changes runs before the switch goes on.** It is a task of its own after task 2 (see "Changes of the task list"). Budget: at most 64 calls of the OpenAI API.
- **OD-5: one change call with no `run` tool** (option A). Task 10 joins task 9. No interim stub that answers UNVERIFIED, and no new string for it.
- **OD-6: restore and manual edit keep the app file as the source of truth.**
  - Restore of a revision that has `appFile`, on an app that has `appFile`: the kit compiles the app file of the old revision with the current app as `previous` (task 2). The result gives the definition, the next state, and the old `appFile`. A fault of the kit ("the saved data blocks this change") refuses the restore with the message of `widget_state`. No data of the person is lost.
  - Restore between an app of today's form and a revision with `appFile` (either direction): permitted only when the app has no stored data of the person (`hasStoredData` of task 9 is false), and then the old revision comes back with its own start state. With stored data it is refused with the same message.
  - Manual edit of an app with `appFile`: the edit is an edit of the plan. When the saved text changes the field `appFile`, the host compiles the new app file with the current app as `previous` and saves the compiled result. When the text changes a different field and leaves `appFile` as it is, or removes `appFile`, the host refuses the save and says that the other fields are built from `appFile`. If `saveManualDefinition` cannot call the compiler without a large change (say so with the call chain), stop and report: the fallback ruling is to refuse a manual edit of an app with `appFile`.
  - So no path leaves an app that has the kit run-time as its `source` and no `appFile`. The third option of the draft is not needed.
- **OD-7: `app.keep`** (option 1): a removed need keeps its stored field. If the run-time text passes 49500 bytes with it, stop and report with the byte counts.
- **OD-8: add the envelope field `repeat: 2`**, and use it only on a subset (ruling on task 11 below).

### Other rulings on the draft

- **A busy sandbox.** When the compile of a plan returns BUSY, the plan step waits 500 ms and compiles one time more. A second BUSY goes to the loop (`fallback:compile_BUSY`). Reason: a person who uses an open app while a request runs must not lose the fast path to a short overlap. The test `aBusySandboxGoesToTheLoop` holds the slot for both tries, and a new test `aShortBusySandboxStillGivesThePlan` frees it after the first.
- **The change call.** Its reasoning effort and its limits come from the model check of changes (OD-4), not from the draft's 60 s and 120 s. Until that check ends, task 4 builds the stage `APP_FILE_CHANGE` with the values of the draft, and the check's task changes them.
- **Task 11, the paired run.** The off arm runs the 25 servable corpus cases. The on arm runs the same 25 and 6 cases that expect `outside` (two games, two 3D cases, two picture cases). A warm second request (`repeat: 2`) runs for 8 of the 25 in each arm. Budget: the controller states it to the owner before the run. The cap of the run is `--call-budget 300` for each arm, and the run stops at the cap.
- **The plan document.** After task M1 ends, the controller moves the draft with these rulings into `docs/superpowers/plans/2026-11-19-app-file-loop.md` and commits it.

### Changes of the task list

| Order | Task | Source |
| --- | --- | --- |
| before | K1: the kit changes that the model check asks for, with `patchAppFile` | `k1-decisions.md` |
| 1 | The prompt asset, the reply reader, the section "Change" | draft task 1 |
| 2 | The next state of a changed app | draft task 2 |
| 2b | A model check of changes: 8 saved model plans with 2 change requests each, the change call at the efforts low and medium, one repair; at most 64 calls; it sets the effort and the limits of the change call and tunes the section "Change" one time if needed | OD-4 |
| 3 | The host field `appFile` | draft task 3, with the restore and manual-edit rulings of OD-6 moved to task 9 |
| 4 | The call stages | draft task 4 |
| 5 | The compiler and the prompt on the device | draft task 5 |
| 6 | The trace | draft task 6 |
| 7 | The plan step of a new app, with the chat workflows | draft tasks 7 and 8, with OD-3 and the busy-sandbox ruling |
| 8 | A later change by lines, test-and-fix by lines, restore, and manual edit | draft tasks 9 and 10, with OD-5 and OD-6 |
| 9 | The paired corpus run | draft task 11, with the ruling above |

### Rulings after task M1 (2026-11-19)

- **A plan with a `hole` goes to the loop** (`fallback:hole`, as the draft says). The prompt has no `hole` after task K1, so this is only a guard.
- **The language check and the presentation check of `validate`.** All 52 model plans pass them on the device (task M1). No extra rule is needed.
- **A long decimal on the first screen.** `validate` does not catch a raw number such as 28.333333333333332: the long-decimal check runs only in `finish` (`AppAgent.kt`, near 445 to 451). Task 7 runs that same check on the first screen of a compiled plan after `validate`. A failure is `fallback:host_check`. Test: `aPlanThatShowsALongDecimalGoesToTheLoop`.
- **A voice hand-off of a create request** goes through the same generation path. With the switch on it also gets the plan step in the first push. Task 7 gets one more test: a voice hand-off of a create request (`FakeLiveTransport.say` with a scripted provider) gets one `APP_FILE` call and shows its card.
- **Two pushes** (the owner, 2026-11-19). This plan, the wide-screen checks, and the switch for OpenAI on terra are the first push. The voice screens and the course slides are the second.

### As built

The code at `b9c7b1f` differs from the tasks below in these points (task U-K, item 14, and task U2-K, item 4). The code wins.

- The comparison of the stored state with the start state uses the helper `sameToolJson` that the app has. There is no new `sameValue`.
- A test-and-fix reply with no line gives `UNVERIFIED`, not `PASSED`. The detail is the string resource `tool_test_fix_no_line`. The test is `aTestFixWithNoLineIsUnverified`.
- The failure texts of a change come from string resources (`tool_generation_state`, `tool_generation_connection`, `tool_generation_unchanged`), not from English texts in Kotlin.
- The word of `outside` leaves the device only when it matches `[a-z0-9]{1,16}` (`ToolGenerationDiagnostic.kt`), so `3d` stays.
- A fault that the saved data blocks, and the mismatch fault, give `fallback:data_blocks` with no repair call (`AppFileSteps.outcome`).
- Test and fix by lines never goes to the loop. When the kit cannot apply its reply, the request fails and the app, its data, and its revision stay.
- The change call uses the low effort of a plan call and the limits of a plan call (`AppFileLimits()`: 20 s to the first text and 45 s in all).
- The plan step and the change step run in `routeAppFile` (`tools/agent/AppFileSteps.kt`), which `AppAgent.run` calls. The tasks place them in `AppAgent.run`.
- The bundle of the kit defines one global, `compileStep`, and the script of `AppFileCompiler.kt` calls only it. It reads the reply, merges it, and compiles the plan in one call. The globals `compileAppFile`, `readPlanReply`, and `patchAppFile` of tasks 1, 2, and K1 are gone.
- The restore of a version (`ToolEngine.kt` `restoreSpec`, `kitCompile`, `kitRevision`):
  - When the app and the version both have an app file, the kit compiles the app file of the version with the current app as `previous`.
  - A restore between the two forms (one has an app file, the other has none) is refused with `widget_state` when the app holds data of the person or either version has a collection. OD-6 names only the data of the person. Else the version comes back with its own start state.
  - The two saved-data faults (the flags `blockedBySavedData` and `savedDataMismatch`) refuse the restore with `widget_state`.
  - Each other fault, a hole, and a sandbox status that is not ok fail the check of `kitRevision`. The restore check then gives its general text, "Эта версия не прошла проверку на текущих данных." OD-6 does not name these cases.
  - The restored version keeps the icon of the current app. OD-6 says that the version comes back as it was saved.
- The manual edit of an app with an app file (`ToolEngine.kt` `saveManualDefinition`):
  - Only the field `appFile` may change. An edit of another field, an edit that removes `appFile`, and an edit that adds `appFile` to an app of today's form are refused with `app_file_edit` and the text `tool_manual_edit_app_file`.
  - The kit compiles the new app file with the current app as `previous`. The two saved-data faults refuse the edit with `widget_state`.
  - Each other fault of the kit goes to the person with its line (`app_file_edit`, with the faults of the kit as the text). A clean plan with a hole and a sandbox status that is not ok fail the check of `kitRevision`, and nothing is saved.
- No function has the name `hasStoredData` (task 9, OD-6). The rule of the data of the person is `ToolSpec.holdsDataOfThePerson` (task U2-A adds it under this name).

## Decisions of the controller


This file gives the decisions. The plan writer turns them into an implementation plan with exact files, signatures, and tests.

### Goal

A new app request gets its app from one fast plan call when the kit can serve it, and from today's agent loop when it cannot, with no loss of function. A later change of such an app works by line patches and keeps the person's data.

The owner's goals, in order: speed of generation; good-looking apps; apps "as functional as the usual ones"; interactive screens in the full duplex voice mode with OpenAI (a later plan builds on this one).

### Measured facts (sources in the files below)

- Model check (results document, section "Model check outside the app"): `gpt-6.1-terra` at reasoning effort low with the prompt `kit/prompt.md` wrote a clean plan at the first try for 23 of 25 servable corpus requests and for 8 of 8 dialogue requests, and 24 of 25 after one repair call. The second repair call made no terra plan clean. Whole call: median 3.97 s for an app, 8.79 s for a dialogue screen; first text after 1.68 s and 3.3 s. A request that the kit cannot serve got the one-line reply `outside("word")` in 20 of 20 cases, median 1.33 s. Compile: median 17 ms, max 76 ms in Bun.
- Delay study (`delay-study.md`): in today's path 7.9 s pass before the agent's first model call: 0.25 s to the routing call, 2.56 s routing call (DeepSeek), 4.79 s between the accepted decision and the first agent call. No timestamp splits the 4.79 s. The suspect is `AppAgent.capabilityGuidance` (`AppAgent.kt:112`, `:710-722`). The sandbox is not used before the first agent call of a new app.
- The design of the whole work: `docs/superpowers/specs/2026-11-18-app-file-parts-design.md`. Sections 3 (loop), 4 (gate), 5 (storage and later changes), 11 (tests, trace, switch). The design planned three parallel lanes. The model check replaces that for OpenAI: one lane is sufficient.

### Decisions

1. **One plan call, one repair, then today's loop.** For a request that passes the gate of decision 2:
   - The host sends one plan call: the section "System" of the plan prompt as instructions, and the task text that today's agent step gets (request, widget brief, excerpts). Reasoning: the provider's `reasoningOff` (effort low for OpenAI). No JSON mode. The reply streams.
   - Reply `outside("word")`: go on with today's loop at once. Record the word in the trace.
   - Reply is a plan: compile it with `AppFileCompiler`. Clean: run today's host checks on the compiled definition (`validate` of `AppAgent`: `ToolSpec.parse`, state, smoke, language check, presentation check) and accept the app. The model wrote no `finish` checks: a part-built app has none (design section 4). The result is an `AppAgentResult` as today, and the stored app keeps the plan in the field `appFile`.
   - Faults: one repair call (the request, the plan, the faults, the section "Repair"; reasoning off). The host merges the reply with the kit's merge function (`patchAppFile`, which task K1 adds to the kit bundle) and compiles again. Clean: accept as above. Not clean: today's loop.
   - A plan that holds a `hole` need, or a compiled definition that fails a host check, or a compile status other than ok or faults (TIMEOUT, MEMORY_LIMIT, BUSY, INVALID_RESULT), or a plan call that fails or passes its time limit: today's loop. Nothing of the plan goes to the loop in this plan (a later change can pass the faults).
   - Time limit of the plan call: 20 s to the first text, 45 s in all. The plan writer may propose other values with a reason from the numbers.
2. **The gate of the plan step.** A new app (no previous definition), not a lesson slide, no attached image, no scene asset, not test-and-fix, and the switch of the provider is on. Each other request keeps today's steps. (A later plan moves lesson slides.)
3. **Where it runs.** Before `capabilityGuidance` in `AppAgent.run`, so that an accepted plan never pays the embedding. The capability guidance is computed only when the loop runs (after `outside`, or after a failed plan). Do not make it parallel: a plan that is accepted needs no guidance, and a request that goes to the loop pays what it pays today.
4. **The prompt is an asset of the app.** `kit/prompt.md` stays the one source. The kit build writes its sections "System", "Repair", and "Dialogue" into the bundle or an asset beside it, and `kit/build.js --check` covers it, as it covers the kit bundle. The Kotlin side reads the three texts from there. No prompt text is typed in Kotlin.
5. **A call for the plan stage.** `CloudGenerationStage` gets a stage for plan calls: reasoning off, no JSON mode, an output limit that fits a plan (propose a value: the largest plan of the model check was under 2000 output tokens), streamed text with `ModelCompletionTiming`. The cancel of the plan call must work while no other call of the session runs. Each provider wire must accept the stage, but only the OpenAI switch goes on in this plan.
6. **The switch.** One switch for each provider in `GenerationSettings`, default off for each provider in this plan. A later task turns OpenAI on after paired runs. A test can set it.
7. **The trace.** `ToolGenerationTrace` gets a phase event (name, time on the elapsed-realtime clock) for: plan call start, first text, reply end, each compile with its status and ms, each repair call, the outcome of the plan step (accepted, outside with its word, fallback with its reason), and also for the start and the end of `capabilityGuidance`, the draft creation, and the first agent call. So one trace shows where the time before the first screen goes, with and without the plan step. `ComparisonChatCaptureTest` writes these events into `report.json`, and `tools/agent_corpus/run_cases.py` puts the plan-step fields into its summary lines.
8. **Storage.** The app definition gets one optional host field, `appFile` (the plan text), kept through save, load, revision, export, and share, as `appLanguage` is. `ToolSpec.parse` refuses it in a definition that a model wrote, as today. No migration of old apps: an app with no `appFile` is an app of today's form.
9. **A later change of an app with `appFile`** (design section 5). A refine request, and test-and-fix, on an app with `appFile` use line steps:
   - The model gets the request and the app file and replies with lines (new lines, replaced lines, delete lines). Reasoning on for this call, as today's edit (the design's choice; the plan writer may propose off with a reason). The host merges with `patchAppFile`, compiles, and runs the host checks. One repair call on faults. Then, if still not clean: when the app has no stored data of the person, today's `write_app` path as for a new app (the app loses `appFile`); when it has stored data, the change ends with no change and the loop tells the person.
   - The state after the change: a value of the person (an input, a counter, the rows of a list) stays by the name of its need and field. A new column of a list gets its start value in each stored row. A removed need keeps its stored field, unused. A removed or retyped column and a retyped input are a fault of the line. Derived values are computed again. The kit computes this: the plan writer defines the kit function (for example `compileAppFile(text, {previous: {appFile, stateSchema, state}})` that returns the definition and the next state, or a fault), its tests, and the one new rule in `ToolSpec.stateForRevision` for an app with `appFile`. Read `kit/src/emit.js` and `kit/src/run.js` (`stateOf`, the namer) to see how state fields get their names today, and say in the plan whether names are stable across a patch.
   - An app with `appFile` never gets `edit_app` on its compiled `source`.
10. **Tests.** Follow the project rules (AGENTS.md and the engineering policy of the owner): black-box workflow tests on the device are preferred; one executable behavior checker (`AppSteps`); scenario fixtures in the host; the shared generation path gets an app workflow and a game workflow. For this plan:
    - a fake model (scripted replies) drives each branch of decision 1 through the real `AppAgent`, the real `AppFileCompiler`, and the real sandbox on the device: clean plan; `outside`; faults then a clean repair; faults twice; a plan with a hole; a plan call that fails; a plan call that is slow; the switch off; a request with an image;
    - an app workflow (a saved model plan from `kit/test/fixtures/model/`, a scenario with real presses) and a game workflow (`outside("game")`, then the loop with scripted replies) through the chat path;
    - a later change by lines that keeps the rows of a list, one that adds a column, one that the kit refuses (a retyped column) with stored data, and the fallback with no stored data;
    - tests are written before the production code of each task.
11. **Out of scope of this plan.** The voice path, a plan call in parallel with the routing call, lesson slides, a preview while the plan streams, written sections, other providers' switches, the wide-screen look. Name the seams that those later plans need (for example: the plan step must be callable with a host line for a dialogue screen and with an observer for streamed lines), but build none of them.
12. **Paired runs come last.** The last task of the plan is a paired corpus run on `emulator-5592` with the OpenAI account: the same head, the switch off and on, the 45 corpus cases, each case in a fresh process and once more warm where the runner permits. It reports the time to the first screen and to the finished app for each arm, the share of requests on the fast path, each fallback with its reason, pictures of each app, and the function checks that exist. It needs a stated budget of model calls: the plan gives the worst-case count.

### Project rules that bind the plan

- No compatibility with earlier development versions: no migration, no legacy path. Do not delete user data or credentials silently.
- Kotlin and tests live in `app/src/main/java/com/alpha/assistant/` and `app/src/androidTest/java/com/alpha/assistant/`. The kit is `kit/`.
- Only `emulator-5592` for device runs. No model call in a test that the suite runs by default.
- Each commit: imperative sentence, no prefix, no period; Slopo review and the architecture scan before it.

### What the plan writer delivers

One plan document in the form that `superpowers:writing-plans` describes (header with goal, architecture, tech stack, the spec path; global constraints; review focus with the five input classes most likely to hurt a person; then tasks). For each task: the files to create or change with line anchors checked at the current head; the interfaces it consumes and produces with exact names and signatures; the tests by name with their assertions in words or code; the acceptance command. Keep bodies out: signatures and tests decide the code. Order the tasks so that each one ends with a testable result and one writer can do them in sequence. Mark which tasks touch only Kotlin, only the kit, or both. List each assumption with its test, and each open decision that the controller must rule on before a task starts.
