# Dialogue fixes implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan lane by lane. Each lane writes its failing tests first.

**Goal:** A spoken follow-up changes the shown screen or gives a spoken reason, in each case that the owner's dialogue of 2026-11-20 showed as broken.

**Architecture:** The voice mode keeps its two steps (a routing call, then a build). The routing call of a spoken turn gets the earlier clean turns of the talk. A spoken request that the fast kit path cannot serve goes on to the full generation. The base checks of a change tolerate a state change of the shown app. A card that fails to open tries again. The release build writes one log line for each failure.

**Tech stack:** Kotlin and Jetpack Compose (`app/`), the kit in JavaScript with Bun (`kit/`), instrumented tests with `tools/compose_acceptance/run.py`.

**Spec:** the investigation of 2026-11-21 (`.superpowers/sdd/2026-11-19-app-file-loop/dlg1-a-report.md`, `dlg1-b-report.md`, `dlg1-c-report.md`, `dlg1-d-report.md`, git-ignored work files of the session) and `docs/superpowers/specs/2026-11-20-dialogue-stage-design.md`.

## Global constraints

- No change of the plan prompt asset (`app/src/main/assets/appfile/prompt.json`, `kit/prompt.md`). Its hash stays `69759b168bc3`.
- The routing body of a typed request stays byte-equal to the body at `51e4455`. A test proves it.
- Earlier turns only explain the latest request. They never authorize a device action, an automation, a memory write, or an operation that the latest request does not ask for.
- No data of the person is lost. A value that the person sets while a change runs survives the accepted change.
- Do not preserve compatibility with earlier Alphy versions (AGENTS.md).
- Each defect has a test that fails at `51e4455` and passes after the fix. If a test for an Assumption does not reproduce the defect, report it and do not fix blind.
- No secret value in a log line, a file, or a report. A log line of the app holds names and codes only, never text of the person.
- Every new text that a person sees is Russian and is a string resource.

## The defects (fix set)

| Id | Defect | Evidence |
| --- | --- | --- |
| A | The routing call of a spoken turn gets no earlier turn. A hint sends a short follow-up to the answer step. The answer step does not know that a stage exists. | `cloud/CloudProvider.kt:716-724`, `:741`, `:1082` |
| B | A spoken screen or change uses only the fast path. Attempt 2 gets no plan of the current screen. The voice gets no reason. | `ui/ShellViewModel.kt:2007-2045`, `tools/agent/AppFileSteps.kt:258-295` |
| C | The base check of a change compares a record that was read before the routing call. A state change of the shown app in between ends in a generic error. | `tools/ToolEngine.kt:429`, `:461`, `:480`, `:523`, `ui/ShellViewModel.kt:1641-1653`, `ui/DialogueStage.kt:139` |
| D | A card that fails to open drops its exception, shows «Сеанс остановлен», and never tries again. | `tools/NativeToolSurface.kt:240`, `sandbox/AndroidJsRuntime.kt:81-109` |
| E | At an accepted change the stage shows the stopped line and an empty drawing for a moment. | `tools/ToolEngine.kt:558-559`, frames of lane C of the investigation |
| F | The release build keeps no record of a failure. | `tools/ToolController.kt:430-436`, `ui/ShellViewModel.kt:2037-2062` |
| G | An unexpected exception shows an English text with the code "network". | `ui/ShellViewModel.kt:2062` |
| H | The kit smoke check rejects an `animate` that ends at its start value, which is the example of the prompt. | `dlg1-a-report.md` section 3.6 |

Not in this set: turn T7 of the owner's dialogue (no evidence yet), and movement that goes on by itself in the kit (a feature, not a defect).

## Rulings

- **FX-1 (A).** A spoken routing call gets the last turns of the chat as untrusted context turns before the request: at most 6 messages and 2,000 characters. It takes the texts of the person and the replies of clean provenance. A reply with web sources or another provenance becomes a host marker with no model text. A failed change or screen is in the list with its failure line. The shown-screen line stays.
- **FX-2 (A).** For a spoken turn the short follow-up hint is replaced: a retry, a yes, or a follow-up to what Alphy offered, asked, or failed takes its meaning from the earlier turns, and the decision has a full brief. The answer step of a spoken turn gets one sentence: screens exist in this talk, so it never says that it cannot show or change one, and it claims no action as done.
- **FX-3 (B).** Attempt 2 (a new screen after a failed change by lines) gets the plan of the current screen as untrusted context.
- **FX-4 (B).** This reverses ruling OD-5 for two cases. When the fast path fails for a spoken request that the person asked to see (`show`) or to change, the full generation builds a new app. The old app stays. The voice says a waiting line first. A visual that the model added by its own choice (`show` false) is still dropped with no line. Cost: about one minute of billed voice for each such request, and a later change of that app is also slow, because it has no app file.
- **FX-5 (B).** When the full generation also fails, the voice gets the reason group and one hint to say the request in full. The message keeps the reason code.
- **FX-6 (C).** A state change of the shown app while a change runs does not fail the change. The checks that protect saved data stay. The lane designs the mechanism and proves it with the two tests of its task.
- **FX-7 (D).** A card whose open fails tries again a bounded number of times and logs the class of the exception. A busy slot is no error of the person.
- **FX-8 (E).** The stage never shows the stopped line while an accepted change replaces the app.
- **FX-9 (F).** One log line for each spoken or typed build that fails, with the tag `AlphyGeneration`: the decision kind, the plan outcome or fallback reason, and the class of an exception.
- **FX-10 (G).** An unexpected exception shows a Russian text from a string resource and has the code `internal`. The code `network` is for a transport failure only.
- **FX-11 (H).** The smoke check accepts a button whose `animate` returns to its start value, when the value differs during the motion.

## Lanes, files, and landing order

Each lane works in its own worktree at `51e4455` and delivers one patch. The controller applies the patches in this order in the session worktree, runs the commit gate, and commits: C, D+E, A, H, B, V.

| Lane | Defects | Files it may change |
| --- | --- | --- |
| A | A | `cloud/CloudProvider.kt`, `cloud/ChatToolReply.kt`, the one `CloudRequest(...)` construction of a chat request in `ui/ShellViewModel.kt` (only if a new field needs it), `androidTest/.../cloud/CloudWorkflowTest.kt`, a new test file |
| B | B, F, G | `ui/ShellViewModel.kt` lines 2007 to 2062 and the helpers that these lines call, `ui/LiveVoiceBridge.kt`, `tools/agent/AppFileSteps.kt`, `tools/ToolGenerationDiagnostic.kt`, `res/values/strings.xml`, `androidTest/.../ui/ShellLiveVoiceWorkflowTest.kt`, `androidTest/.../tools/AppFileLoopWorkflowTest.kt`, new files |
| C | C | `tools/ToolEngine.kt` lines 416 to 540, `tools/ArtifactCoordinator.kt`, `ui/ShellViewModel.kt` lines 1638 to 1660, `ui/DialogueStage.kt`, `androidTest/.../tools/AppFileChangeWorkflowTest.kt`, a new test file |
| D+E | D, E | `tools/NativeToolSurface.kt`, `tools/ToolEngine.kt` lines 541 to 570, `tools/ToolController.kt`, `tools/ToolUi.kt`, `sandbox/AndroidJsRuntime.kt`, `androidTest/.../tools/SharedArtifactWorkflowTest.kt`, `androidTest/.../ui/DialogueStageRehearsalTest.kt`, a new test file |
| H | H | `kit/src/`, the kit tests, the generated bundle `app/src/main/assets/appfile/kit.js` and its test copy |
| V | none (the check) | `androidTest/.../ui/LiveDialogueStageTest.kt`, `androidTest/.../ui/DialogueDriver.kt`, `androidTest/.../ui/DialogueRecorder.kt`, `tools/compose_acceptance/` |

## Tasks

### Lane A: context of a spoken turn

- Red tests (scripted server, no paid call): the routing body of a spoken turn after a failed change holds the earlier request and the failure line (owner's T3 and T9); the body after a question back holds the question (T6); a reply with web sources is a marker with none of its text; the typed routing body equals the body at `51e4455`; the tests at `CloudWorkflowTest.kt:1096` and `:1988` stay for typed chat.
- Produces: the routing instructions of a spoken turn per FX-1 and FX-2. Lane V relies on them.

### Lane C: a change while the shown app changes

- Red tests: (1) a state commit of the stage app between the start of a spoken change and its draft; at `51e4455` the chat shows the generic error (owner's R1); after the fix the change is accepted. (2) A value that the person sets while the change call runs is in the state of the accepted revision.
- Consumes nothing. Produces: no `IllegalStateException` of a base check reaches the shell for a state change alone.

### Lane D+E: cards and the stage surface

- Red tests: (1) the open of a drawing card meets a busy retained slot; at `51e4455` the card shows «Сеанс остановлен» and stays so; after the fix it draws with no tap. (2) At an accepted change of the stage app the stopped line is never composed.
- The log line of FX-7 uses the tag `AlphyGeneration`.

### Lane H: the smoke check

- Red test (Bun): the change reply `scratchpad/dlg1/a/plans/t2-change-animate.reply` on its stand-in plan compiles clean. A button that changes a value that no part shows still gives the fault.
- Then build the bundle and run the kit tests. The device proof of the kit runs on the landed head.

### Lane B: the fallback, the reasons, and the record

- Red tests: the owner's T2, T8, and T10 as fixtures: two fast failures; at `51e4455` the chat shows «Изменить экран не получилось.»; after the fix the full generation runs, the voice gets the waiting line first, and the stage shows the new app. Attempt 2 holds the plan of the current screen. A spoken screen with `show` false and a fast failure still makes no further call. An unexpected exception gives the Russian text and the code `internal`. Each failure writes the log line of FX-9.
- Consumes: lane C must be landed before the check of lane B on the session worktree, because an app of the full generation can move by itself.

### Lane V: the live check

- Three new subjects, none of moon, heart, Japanese numbers, or the Solar system. Together they hold: a bare retry after a failure, a «Да» after a question back, a full replace, a request for motion by itself, a change with search, and a change while the stage moves. One run is at the fold size.
- Pass bar, written before any paid call: each step ends in a changed stage or in a spoken reason. A step that the model did not reach (for example no question back came) is recorded as not exercised, not as passed.
- The call cap of a dialogue rises for a run that uses the full generation.

## Review focus

- A spoken turn must not let earlier text start an operation: an earlier reply that holds an instruction ("delete the reminders") does not change the decision of a harmless latest request.
- The typed chat numbers of push 1 must hold: no new call and no new text in a typed routing body.
- A change by lines after a tick must not write an old state over a newer one.
- The full generation in the voice mode must end the hand-off in each outcome: accepted, failed, and stopped.
- A retry of a card open must stop when the card leaves the screen.
