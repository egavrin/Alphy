# Agent generation design

Date: 2026-11-13. Branch: `claude/agent-generation`.

## Intent

Mini-app generation is unstable. A live run of the 134-case corpus on
2026-11-11 committed 72 of 107 expected apps. Repeated runs of one prompt gave
different outcomes. The generated apps that passed often looked plain.

The cause is the architecture, not one defect:

- Generation is a chain of strict stages: PLAN, SELECTOR, COMPLETE, and REFINE.
  Each stage has all-or-nothing contract checks (about 340 rules).
- A stage gets at most 3 repairs. A failure in a late stage cannot change an
  earlier stage, except through a complex replan path.
- The model writes its own acceptance scenario in a slot and binding language.
  Most failures were errors in that scenario, not in the app.
- Some errors are hidden. For example, `JevSelector` drops errors, and
  unknown exceptions become the `network` error code.
- The writer prompt asks for a "simple default presentation". Visual polish is
  a separate optional stage.

## Requirements

Source: the user in this session.

- Replace the staged pipeline with our own agent loop.
- The loop has at most 20 steps.
- The loop supports follow-up requests (changes to an existing app).
- Acceptance: every corpus prompt passes live and produces a functional and
  good-looking app. The corpus is
  `alphy-deal-comparison-evidence-2026-11-10/frozen/final.json`.

Source: `AGENTS.md`.

- Use one executable behavior checker for generated apps and games.
- Derive each scenario from the request. Bind its steps to real controls and
  observable outputs. Keep expected results and acceptance decisions in the host.
- Keep named apps, games, and scenario presets in regression fixtures, not in
  product code.
- Do not keep compatibility code for earlier development versions. Do not
  silently delete user data.

## Goals and non-goals

Goals:

- One generation path for new apps, follow-up changes, test-and-fix requests,
  and course lessons.
- Every host rejection reaches the model as readable feedback in the same run.
- The model sees the app run: it can operate controls and read the screen.
- A finished design from the first write: hierarchy, grouping, focal result.

Non-goals:

- No change to the mini-app format, the native renderer, the sandbox, the store,
  widgets, or the capability broker.
- No prompt-specific rules in product code.

## Architecture

### Agent loop

`AppAgent` (package `com.alpha.assistant.tools.agent`) runs one generation.

1. The host builds a system prompt: the agent protocol, the design guide, the
   UI catalog guidance, the artifact protocol guidance, and short examples.
2. The first user message holds the request, the interface language, and, for a
   follow-up, the current app definition and a summary of its saved state.
3. Each step is one model completion in JSON object mode. The reply names one
   tool and its arguments. An optional `note` becomes a public progress note.
4. The host runs the tool and appends the result as the next user message.
5. The loop stops when `finish` passes, or after 20 steps. A stop without a
   passing `finish` fails with the last host feedback as the error detail.

Implementation choice: one tool for each step keeps each host result attached
to its cause. JSON object mode is the existing reliable path for large
replies. Native function calling is also permitted later.

### Tools

| Tool | Arguments | Host result |
| --- | --- | --- |
| `write_app` | `app`: the complete definition | Validation result and the first screen |
| `edit_app` | `set`: top-level fields to replace, `source_edits`: find and replace pairs for `source` | Validation result and the first screen |
| `run` | `steps`: operations from the initial state | The screen after each step, or the first error |
| `finish` | `checks`: scenarios with expectations, `summary`: the chat reply | Pass, or the failed expectation and the observed screen |

Each write or edit runs the full host validation:

1. `ToolSpec.parse` for the definition, UI catalog, and state schema.
2. Policy and capability checks (`ToolExecutionPolicy`, approved capabilities).
3. For a follow-up, `ToolSpec.stateForRevision` with the saved state.
4. The safe smoke test (`ToolGenerationSmoke.run`): preview, open, and every
   visible control once.
5. The interface language check (`GenerationLanguage`).

A failure returns the validation message and its correction text. The model
fixes the app in the next step.

### Steps and checks

`run` and `finish` use one step language. This is the one executable behavior
checker. It replaces `GenerationBehavior` and its slot and binding scenarios.

- `{"tap": "actionId"}` presses a button, icon, list item, or chip.
- `{"set": "actionId", "value": v}` sends a value from an input, toggle,
  checkbox, slider, choice, or number field.
- `{"row": "actionId", "key": "k", "value": v}` sends a row action.
- `{"wait": ms}` advances the app clock. Timers and countdowns read this clock.
- `{"pointer": "surfaceId", "x": 0.5, "y": 0.5}` taps a drawing surface at a
  relative position.
- `{"frames": "surfaceId", "count": n}` advances animation frames.
- `{"expect": "text", "contains": "23.00"}` requires visible text.
- `{"expect": "absent", "contains": "Victory"}` requires that text is not visible.
- `{"expect": "value", "target": "id", "equals": v}` compares the displayed
  value of the node with that action ID or bind path.
- `{"expect": "enabled" | "disabled", "target": "actionId"}`.
- `{"expect": "effect", "capability": "vibration.play"}` requires that the
  last step requested this effect.

The host resolves each step against the current rendered UI. A step on a hidden,
disabled, or missing control fails with the list of available controls.
A tap on a control that needs a value fails with a hint to use set.
An effect from a frame, timer, or live input event fails with the rule that
only action events request effects. The feedback names a timer node with
foreground as the alternative for an alert at the end of a countdown.
Visible text includes each text, metric, and fact line as the screen shows it
("Label: value"), so a check can quote the screen text.
The host owns the expectations and decides acceptance. `finish` requires at
least one check with an expectation after the last operation.

### Presentation checks

After validation and the language check, the host examines the first screen
of each candidate. It rejects two defects that validation does not find:

- A bound text, metric, fact, or badge that shows a placeholder word: "None",
  "null", "undefined", "NaN", or "[object Object]". The feedback asks for a
  dash or a short message in the interface language.
- Buttons in one grid that have different actions and the same word label,
  such as nine "Open" buttons under the cell numbers. The feedback asks for a
  label that names each cell. Symbol labels such as + and − stay permitted.

The guide also tells the agent not to show the message of a capability result.
The host writes these messages in Russian, for example for audio.
The agent shows its own short status in the interface language. The guide also
tells the agent to write status and error texts in the user's words, without
endpoints, JSON paths, value types, state fields, or capability names.

The shell above the app shows the message of the last effect only when the
effect did not succeed. A successful beep or an opened share sheet adds no line.
The access panel keeps the full effect history.

### Screen observation

`AppScreen` renders the current UI and state as indented text. It shows the
visible nodes, their labels, action IDs, bound values, options, the first rows
of collections, and drawing command counts. The model uses this text to see
the result of a step.

### Follow-up requests

The chat already sends follow-ups to `ToolEngine.generate` with the saved app as
`base`. The agent receives the current definition and a state summary. It
usually uses `edit_app`. The task message tells the agent to change only what
the request asks and to keep the other controls, texts, list columns, and
layout. Without this sentence, a follow-up that changed quiz points also
restyled the history list and removed its time column. With the sentence, the
ledger and grid follow-ups passed their workflow checks in 4 of 4 live runs. The host keeps saved data through `stateForRevision`.
Before the first step, the host validates the saved app. When it passes, `run`
works before any edit. When it fails, the task message gives the failure.

Test-and-fix requests use the same loop in test-fix mode (`AppAgentRequest.testFix`):

- The host rejects `write_app` and `edit_app` until a problem is reproduced.
- A `run` on the unchanged app reproduces a problem when the app script fails,
  or when a failed expectation has a value that the user's request contains.
  A failed smoke check of the saved app also reproduces a problem.
- `finish` without edits is permitted. The result is "checks passed".
- After edits, `finish` replays each reproduction first. The smoke replay
  uses the failing control events. Removal of the failing control fails.

Limit: the model writes the reproduction steps. Only the expected value comes
from the user's words. The earlier test-fix agent also bound the controls to the
user's words.

### Course lessons

Implementation choice for this change: `generateLesson` keeps the staged
pipeline. Lessons have their own contract (exercise kinds, source IDs, and an
illustration intent) and no corpus case. The staged code stays only for lessons.
A later change can move lessons to the agent loop and then delete that code.

### Chat routing

The chat decision sends an app request to generation. The earlier decision
instructions answered practical calculations with all values as text, sent a
named timer with a duration to the device plan, and asked for a missing duration.
The user requires all corpus prompts to produce apps. The new rules:

- A request that names a tool or utility creates an app, also as a fragment.
- A practical calculation creates an app with the supplied values as defaults.
  The chat text gives the computed result.
- The device plan gets a timer only for a command to set or start it.
- Knowledge questions, explanations, advice, and pure arithmetic get an answer.

The agent prompt must contain no localized product text. Russian capability
titles in the prompt made the model write Russian interfaces for English requests.

The chat decision sometimes wrote its text and brief in another language than
the request (5 of about 120 live runs: Russian or Ukrainian for English
requests on a Russian device). The agent then followed the brief. Two changes
address this:

- The decision prompt asks for text and brief in the language of the latest
  request, also when the device or earlier messages use another language.
  It earlier said "the user's language". This rule alone left 4 of 132 live
  replies in Russian for English requests. The host now also identifies the
  language of the latest request on the device and states it in the decision
  prompt.
- The identifier needed 12 letters. A one-word request such as "stopwatch"
  got no statement, and on a Russian device the app was in Russian. The limit
  for the latest request is now 4 letters. The confidence limit of 0.85 still
  rejects ambiguous words such as "bmi".
- For a new app, the host identifies the language of the request text with the
  on-device language identifier and states it in the agent task. The agent
  takes the interface language from the request text only, unless that text
  names another language. The brief and the reply excerpt do not set it.

The decision prompt sent every requested photo to the device plan. A request
to keep a photo together with entered values (a record) then failed, because
the device plan cannot store a record. Such a request is now a mini-app
request.

### Runtime changes from live acceptance

Live workflow checks found these host defects. They are not agent defects:

- A mini-app that ticks with `timers` or frames committed a new state version
  between a tap and its queued action. The exact version check then rejected
  the action ("The mini-app data changed"). An action now applies when only
  timer or frame ticks committed after the version that the UI showed. The
  action is still validated against the current UI and state.
- A table gave every column a fixed 160 dp, so 3 or more columns scrolled
  sideways. Columns now share the width when each keeps 80 dp.
- A grid of cards needed 144 dp for each cell, so a phone showed a
  "columns 3" board as 2 columns. A grid with an explicit column count now
  keeps its columns while each cell keeps 80 dp.
- A labeled checkbox or toggle filled the whole width of its row. In a list
  row with a checkbox and a text column, the text then showed one letter per
  line. The checkbox label now wraps in the free width without filling it.
- A column child with a layout weight got 0 dp in a scrolling screen, because
  the height there is unbounded. A budget app then showed only its page
  switcher. A column now applies weights only when its height is bounded.
- A bound text joined its label and value with ": " also after a sentence,
  for example "Collect the stars.: 1 of 3". A label that ends a sentence or is
  empty now joins without a colon.
- A chat decision request that lost its connection before response headers
  ended the chat request with a network error (2 of about 160 live decision
  requests). Generation stages already sent such a request again once. A
  decision request now also does this once. No output exists before headers,
  so the second request cannot repeat a visible result.
- After a search, the grounded decision sometimes changed the operation, for
  example from a new app to an answer (1 of 134 live cases). The host ended
  the request. The changed operation now gets the one correction of that
  stage. The correction asks for the original kind and search policy. A
  second change still ends the request.

The full-corpus visual review found these presentation defects in the host
renderer:

- A short metric value broke between a number and its unit, or inside a
  number ("10,00 / 0 / steps"). A value of 24 characters or fewer now shrinks
  to fit one line. When one line cannot fit at 12 sp (at a large font scale),
  it breaks only between words. Its line height follows the shrunk font.
- A segmented option broke a word such as "Quarterly" inside the word. Its
  label now shrinks until each line break falls between words.
- A timer showed its time as body text, for example "Ready: 00:06:00". A timer
  now shows its label, a large clock, and a status line only for an outcome.
  The foreground timer uses the same display. Its vibration note no longer
  shows milliseconds.
- The image placeholder and the vibration note used Russian text in English
  apps. They now follow the language of the app controls.

### Model settings

Each step uses `deepseek-flash` with thinking enabled. A write can use the
expanded output limit. A new request stage `AGENT` identifies these calls for
traces and transport retries.

### Errors

Every exception inside the loop becomes model feedback or a typed
`CloudFailure` with a readable message. No exception becomes the `network`
code unless it is a transport failure.

## Removed code

The staged pipeline for mini-apps is removed:

- The app plan prompt, the whole-screen route, and the staged selector,
  complete, refine, and polish stages for apps.
- Three.js scene generation and the generated-image slots in new apps.
- The replay and reliability tests of the staged app path, with their
  recorded fixtures.

Course lessons still use the staged builder. `ToolGenerationService.generateLesson`
calls `buildCandidate`, so `NeedsPlan`, `PartsGeneration`, `GenerationRepair`
(writer repair), `SceneRequirement`, and `GenerationBehavior` stay for lessons.
Runtime code that saved apps need also stays, for example composition metadata
and the Three.js renderer.

Some code is dead or almost dead after the removal, but kept tests use it:

- `ToolSpec.withThreeAssets` and `withGeneratedImages`.
- The draft preview publisher and the preview observer chain in
  `ToolGenerationService.generate`.
- The trace event types Stage, Attempt, and Repair, and 7 of the 9
  `ToolGenerationProgress` values. The app path does not emit them.
  The app path still emits Transport events.
- `ToolGenerationSmoke.checkNativePlan` and `fencedCapabilities`.
- `ImagePlan.threeScene` and `CloudProvider.supportsSceneVision`.

The lesson scene guidance (`SceneRequirement.guidance`) still shows a
whole-screen example, which the lesson parser rejects. This was also true
before the agent change.

## Unresolved decisions

- 3D scene generation (`generateThreeScene`) depends on the PLAN stage. No corpus
  case uses it. This rewrite removes 3D generation. Saved 3D apps still open.
  A later agent tool can add it back. The chat decision prompt no longer
  says that 3D scenes are available.
- Generated images inside apps (`imageRequests`, image polish) depend on the
  PLAN and REFINE stages. No corpus case uses them. This rewrite removes them.
  Saved images still show.
- A number field that is not a live input shows a disabled "Apply: X" button
  under the field. Calculator apps then repeat this button under each field.
  A live input for every number field removes the button, but it changes the
  input contract and the tests that use the button.
- Host capability results, for example the audio result message, are in
  Russian. The guide rule hides them from the app, and the shell shows only
  failed results. A failed result in an English app still shows a Russian line.
  A host change can give each result a message in the app language.
- A canvas drawing in tone "muted" uses the text ink color, a dark gray. A
  drawn progress track at 0% then looks full. The guide tells the agent to use
  progress and progressRing nodes instead, but some apps still draw bars.
- The shell around a mini-app (for example "Изменить" in the top bar) uses the
  device language, also for an English app. The shell theme is grayscale, so
  apps look gray unless they set tones. Both are shell decisions outside the
  agent loop.

## Verification

1. Workflow tests with a scripted provider, written before the code:
   new app, follow-up, validation feedback, failed check feedback, step limit,
   and a game with a drawing surface.
2. The existing instrumentation suites that do not test the removed pipeline.
3. Live runs of all 134 corpus cases on new emulators with
   `ComparisonChatCaptureTest#captureFrozenSlot`. For each case: the chat outcome,
   the artifact, and screenshots.
4. A functional review of each artifact against the case `interactions`, and a
   visual review against the corpus `visual_rubric`.
   `tools/agent_corpus/check_apps.py` runs host-owned scenarios on the saved
   artifacts with `AppScenarioCheckTest`. A scenario binds its steps to real
   controls by label pattern, so one scenario works for every generated
   version. The host judges the final visible text. Scenarios stay outside
   product code.

### Evidence

Live runs on four new emulators (Android 16, 1080x2400) with the DeepSeek
provider. "As expected" uses the corpus outcome: "generated" needs an app,
"either" accepts an app or a completed chat answer, and "refused" needs no app.

| Run | Build | As expected | Apps | Workflows | Steps (median, max) | Seconds (median) |
| --- | --- | --- | --- | --- | --- | --- |
| full6 | earlier | 131/134 | 126 | 7/9 | 3, 12 | 58.5 |
| full7 | earlier | 134/134 | 126 | 7/9 | 4, 11 | 59 |
| full8 | green42 | 133/134 | 125 | 8/9 | 4, 10 | 51.5 |
| full9 | green45 | 134/134 | 127 | 8/9 | 4, 18 | 53 |

In full8, about half of the cases ran at font scale 2.0 on 960x2160. An
earlier instrumentation test stopped before it restored these settings. full9
uses the default settings on all four emulators.

The full8 visual review found 4 high and 53 medium defects in 124 reviewed
apps. The high defects were a host audio message, a "None" value, a Russian
image placeholder, and a Russian app for "stopwatch". A rerun of these cases on
green45 showed none of them in the app. The medium defects were mostly line breaks inside
metric values and segmented labels, and the timer display. The renderer changes
above address them.

The full9 visual review found 2 high and 25 medium defects in 126 reviewed
apps. 99 apps had no high or medium defect. Line breaks inside words and
values went from 7 to 2, wrapped values from 26 to 4, and timer text defects
from 10 to 2. The two high defects:

- The coffee counter showed the Russian audio receipt above an English app.
  The shell, not the app, showed it. The shell change above removes this line.
- The metal price app had no price source and showed "Live prices need a
  source endpoint, JSON path, and value type" in its banner. After the new
  guide rule about the user's words, two reruns on green47 used
  `network.request` with a public price source and a Refresh action. One of
  them still showed the JSON field names in a "Data source" section.

Most medium defects are layout choices, such as "Label: value" rows in list
templates, a tall last segment in a 5-option control, and drawn progress tracks.

In full9, one workflow failed: the grid game used list items with actions as
its cells, and the checker counted only buttons as controls. A rerun with the
fixed checker passed.

Workflow checker gaps found on new app variants are regression fixtures in
`Plan5SavedArtifactWorkflowTest`: a text balance ("0 очк."), an amount
"300 g → 450 g", an ingredient row beside a hidden empty state, and a grid
with list-item cells.

