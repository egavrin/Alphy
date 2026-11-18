# App file with host parts: design

Date: 2026-11-18. Status: approved by the user on 2026-11-18 ("План ок").
The user's priorities for the build, in his words of that day: the speed
of generation, the look of each app in each mode, and interactivity in the
full duplex dialogue with OpenAI. So the build order is phases 1, 2, 3,
then 8. Phases 4 to 7 come after them.
This document does not replace `2026-11-17-app-file-generation-design.md`,
which the user rejected. That design let the model write JavaScript in
`logic` lines. In this design the model writes no JavaScript for an app
that the host parts cover.

**What "mix parts and code" means here.** On 2026-11-18 the user chose that
one app may hold host parts and a model-written section. The user then
named what the approach must be able to make: an app that speaks, an app
that takes a photo, sends it to a provider's API and shows the result, a
mixed app with classic UI, a picture, and a canvas or a 3D scene, and, in
principle, games. So this design has two kinds of written section:

- lines of the same app file (components, actions, formulas) for an
  ordinary screen. The prototype measured this kind;
- a JavaScript block for a drawing surface (a canvas, later a 3D scene).
  This kind is not measured. It uses today's surface protocol.

Device capabilities come as host parts, with no model-written code.

| The user's case | Today's Alphy | This design |
| --- | --- | --- |
| An app that speaks | No speech capability for a mini-app exists | A `speak` part, after Alphy gets the capability (section 9, gap G3) |
| Photo, a provider's API, the result | Photo and show: yes. No capability takes an image, and `ai.ask` is text only | `photo` and `ask` parts in phase 5, for text. The image step needs gap G1 or G2 |
| Classic UI, a picture, a canvas | Yes, one `source` serves controls and surfaces | Parts, a `picture` part, a `drawing` part for a scheme of shapes, and a code section for a canvas with logic of its own (section 10) |
| The same with a 3D scene | Yes, in the separate 3D runtime, with no capabilities | A `model3d` part for a thing of boxes and cylinders with named parts. Each other 3D scene stays on today's steps (section 10) |
| Games | Yes, as model-written code | A puzzle of a drawing, buttons, and values in lines. Each other game is a canvas code section (section 10) |
| A screen that follows a voice dialogue | No full duplex voice | The same app file and the same prompt, with one host line (section 12) |

**Decisions of the user that this design follows**, each from 2026-11-18:

| The user's words | What it means here | Section |
| --- | --- | --- |
| "Mix parts and code" | One app may hold host parts and a model-written section | 6, 10 |
| "Опция Б" | The host moves stored data when a later change adds a column | 5 |
| "Data lines first" | A 2D model, a simple game, and a 3D model are lines of data that the host draws. Model code is the escape | 1, 12 |
| "Both modes" | One plan prompt serves an app request and a request of a dialogue. An app request also gets the drawing and 3D kinds | 3, 12 |
| "Go outside" | A toy that needs movement by itself goes to the code path, also in a dialogue. The plan makes no version with buttons in its place | 3, 12 |

## Intent

A new mini-app shows nothing until the agent loop commits it. On 20 device
runs at revision b888e0f the median time to the first UI was 58.8 s. One
first-write call of the model takes about 29 s, and 10 of 20 first writes
were rejected.

The user wants the app to appear in stages and the time to drop by
multiples, with no loss of stability, quality, or look. The agent loop must
stay the one generation mechanism, and it must work on a declarative file
that it can patch by line.

The user's hypothesis of 2026-11-18: the main gain comes from no code
generation by the model. Two searches in an isolated prototype agree. So
the model writes an app file of part choices, and the host owns the code
of each part.

## Requirements

Source: the user, sessions of 2026-11-17 and 2026-11-18.

- R1. The agent loop stays the one generation mechanism. The fast step is a
  step of the loop, not a second path beside it.
- R2. The loop works on a declarative, line-based app file. One line is
  easy to replace and to stream.
- R3. Generation must not become less stable. App quality and look must not
  get worse.
- R4. Generation must become faster by multiples.
- R5. An app that is built only from host parts and checked formulas may go
  live with no review step that thinks.
- R6. A model-written section goes live when the host check passes.
  Thinking runs only when the check fails. A section that still fails is
  never shown as usable with a "not checked" notice.
- R7. One app may hold host parts and a model-written section.
- R8. The format keeps the host platform apart from the loop. A different
  platform should be able to render the same file later.
- R9. The plan that thinks is the default. The plan with no thinking may
  give an early preview.
- R10. The same approach must be able to make an app that uses device
  capabilities (voice, camera, a provider's API), a mixed app with classic
  UI, a picture, and a canvas or a 3D scene, and a game.

- R11. Dialogue mode: the person talks with a voice model, and the screen
  shows material that follows the talk (a picture, a lesson with a model,
  a small game, a 3D scene with text and buttons), on a phone, a foldable,
  and a tablet. The user's mocks: `alphy-dialogue-mocks.zip`, four screens
  and 12 spoken interruptions. Section 12 says how the app file serves
  it. The dialogue itself is the last phase. Its parts (a drawing, a timed
  change, a 3D model, a card, a lesson, a question) are in the one part set
  and the one prompt from phase 1 on, by the user's decision "Both modes".

Source: `AGENTS.md`.

- No compatibility layer for earlier development versions.
- Do not silently delete user data.
- One executable behavior checker for apps and games. Test the shared
  generation path with an app workflow and a game workflow.

## Evidence

All of it comes from an isolated prototype with raw DeepSeek calls
(`deepseek-flash`), outside this repository:
`/Users/egavrin/Documents/alphy-dsl-prototype`, records in `search/log.md`
and `search/final2/verdicts.md`. No number below is a device number.

Held-out run: 20 requests that a model wrote after the configuration was
frozen, 2 repeats, against deal-studio's own harness and today's
first-write call.

| 20 new requests | App file route | deal-studio | Today's first write |
| --- | --- | --- | --- |
| Median time | 4.7 s | 5.1 s | 29.1 s |
| p90 | 20.0 s | 26.7 s | 81.4 s |
| Not delivered | 3 of 40 | 0 | not measured |
| Quality, repeat 1 | 16 pass, 1 partial, 2 fail, 1 not delivered | 8 pass, 3 partial, 9 fail | not judged |

- Each app that went live had no model-written code.
- The first line of the plan with no thinking came at 1.0 s.
- 11 of 104 plans that think needed a written section. Each took 24 to
  106 s. Each was a gap of the function list (no power, no date parts, no
  text split). Those functions were added after the run.
- A plan with no thinking fails about 3 in 10. No cheap gate or chooser
  lifted it (an asks-first plan, agreement of lanes, Jev, a model with no
  thinking). So the plan that thinks is the default.

Routing check on the 45 corpus cases of this repository
(`tools/agent_corpus`), after the timer part and an "outside" answer were
added: each of the 45 got the right path, with thinking and with none.
The 15 games, drawing, and 3D cases were sent outside in 1.1 s. The 26
form cases were clean on arrival in 24 with thinking, with no written
section.

Use of the parts in 188 thinking plans for 54 requests: list 37 requests,
figure 30, input 22, cards 6, progress 6. Only 16 of 54 need just input,
figure, a plain list, and progress. The table is in
`component-set.md` of the prototype.

Each number above is for the plan prompt of that time, with the form kinds
only. The prompt of this design is the later one for both modes
(`prompts-unified/` of the prototype). Section 12 has its numbers: the 45
corpus cases on their path, unseen forms at a median of 4.3 s with 16 pass
of 20 by the same judge, and the screens of a dialogue.

Not proven, and the reason for phase 1:

- No app file has passed `ToolSpec.parse` or `ToolGenerationSmoke`.
- The look of a part-built app on a device is not measured.
- The time on a device is not measured. The agent starts 6.6 s after the
  user sends a request, and this design does not change that.
- Each number comes from `deepseek-flash`. Alphy's latest main has four
  providers (OpenAI, Anthropic, OpenRouter, DeepSeek). The time and the
  quality of the plan step with the other models are not measured.
- The facts about providers and voice are from `origin/main` at ce37f1c.
  The local checkout of the author was at 43ab401, 5 commits behind. The
  implementation plan starts from `origin/main`.
- Quality on the held-out set comes from a scenario judge with a hand check
  of its fails. The judge matched 38 of 39 hand verdicts on development
  apps. The held-out requests were written by a model, not by the user.

## Why this is not the staged pipeline again

The staged pipeline committed 72 of 107 expected apps and was removed on
2026-11-16. `2026-11-13-agent-generation-design.md` names four causes.

| Cause then | This design |
| --- | --- |
| Strict stage gates | One file and one loop. A step that fails gets the host report and the loop goes on. The last fallback is today's `write_app` in the same loop. |
| A late stage could not change an earlier one | Each step may replace each line by its name. |
| The model wrote its own acceptance scenario | The gate of a part-built app is the host's: a computed example for each figure and the smoke test. |
| Hidden selector errors | The host names each control from the name of its part. The model selects nothing. |

## Goals, non-goals, anti-goals

Goals:

- A form-style app is live after one plan step of the loop, with no
  model-written code, when the parts cover the request.
- The first line of the screen can show about 1 s after the agent starts.
- Accepted-app rate and look score are not lower than today's.
- A request that the lines cannot give keeps today's steps of the loop, at
  today's speed, until a later phase moves the canvas into the app file
  (section 10). The lines cannot give a random value, movement that goes on
  by itself, a drag or a swipe, a canvas to draw on, a ball or a texture in
  3D, or a known board game.

Non-goals:

- A shorter chat decision before the agent starts (6.6 s today).
- Jev or a local choice model. See "Calls made by the author".
- Renderers for other platforms, and an export to A2UI.
- A change of the native renderer or of the way the sandbox runs an app.
  The stored state changes in one rule only (section 5).

Anti-goals:

- A second runtime or a second validator beside today's.
- A step that can end a generation without a fallback.
- An app that changes under the person after it went live. Dialogue mode
  is the stated exception (section 12).
- A model-written scenario as the only gate of an app.

## Approaches

Where the meaning of parts and formulas lives. Three places need it: the
initial state (Alphy wants initial derived values there), the test of each
figure example, and the run-time `source`.

1. **One JavaScript kit, run in the sandbox (chosen).** The prototype's
   parser, parts, and evaluator become one library. The host runs it in the
   existing JS sandbox to compile an app file, and it ships the evaluator
   as the app's `source`. One implementation, and its test corpus comes
   with it (the prototype has tests for each part and a sweep of 2,600 odd
   lines). Kotlin only passes text in and JSON out.
2. **A Kotlin compiler and a small JS run-time.** Natural for Android. But
   formulas must also run in JS at run time, so the meaning exists twice
   and can drift. This repository has no JVM tests, so a port also needs a
   new test setup.
3. **JavaScript from the model for each written section.** Full power. It
   brings back the code generation that the evidence names as the cost.
   Chosen only where nothing else can do the work: a drawing surface
   (section 10). An ordinary screen section stays in lines.

Lanes of the plan step: two plans that think and one with no thinking, at
one time, as measured. One plan that thinks is cheaper and has a worse
p90 (12.5 s against 8.9 s on development requests). The number of lanes is
a setting.

## Design

### 1. The app file

Plain lines, one item on each line. A later line with the same name
replaces the earlier one.

```
app("Sleep log", lang="en", preset="clean", palette="indigo")
need nights = entries("Nights", fields={day: "date", bed: "time", wake: "time"}, rows=[], show={slept: hours($bed, $wake)})
need average = figure("number", "Average hours", avg($/nights, $slept), example=[{nights: [{bed: "23:00", wake: "07:00"}, {bed: "23:30", wake: "06:00"}]}, 7.25], decimals=2)
need chart = chart("Hours each night", $/nights, $day, $slept)
```

- `need NAME = KIND(arguments)` chooses a host part. The kinds: input,
  figure, entries (list with row controls), deck (cards, pages, typed
  quiz), chart, progress, counter, tabs, steps, timer, picture, content.
  And the kinds of "Data lines first": drawing (shapes that formulas
  place: circle, rect, line, text, polygon, and `each` for a list or a
  count), button (with `animate`, a timed change of a number), model3d
  (boxes and cylinders with named parts, which the person turns and takes
  apart), card, link, lesson (steps), question.
- A state line holds a value that no need holds: `$/NAME = value`. A
  formula line is `$/NAME := formula`. A plan may write both. A button
  changes a state value, and a drawing, a card, or a content shows it. The
  bridge puzzle of the mocks is 6 such lines and 9 needs.
- The header may name the kind of screen (`kind="Lesson"`), which the host
  shows over the title.
- A formula uses a closed list of functions that the kit maps to `Math`,
  `Date`, and array methods. The model cannot call anything else.
- A written section for an ordinary screen is
  `need NAME = hole("what it does")` and then component, state, and formula
  lines of the same file.
- A written section for a drawing surface is `need NAME = canvas("...")`
  and then one block of JavaScript, `code NAME` to `end` (section 10).
- A capability part asks the device for something: `photo`, `ask`,
  `fetch`, `speak`, `alert`, `share` (section 9).
- `outside("game" | "drawing" | "3d")` is the whole reply for a request
  that the lines cannot give (the limits are in "Goals"). The prompt states
  these limits, not kinds of games. A puzzle or a small game that needs
  none of them is planned in lines. Phase 6 moves "game" and "drawing" into
  the app file as a canvas code section.

The full line syntax, each part with its arguments, and the prompts are in
the prototype: `prompts-unified/plan.txt`, `loop.txt`, `formulas.txt`, and
`dialogue.txt` (the host line of section 12), and `parts.js`. The
directory `prompts/` there holds an earlier text and is not the prompt of
this design.

The prototype has only what was measured. It has no capability part
(`photo`, `ask`, `fetch`, `speak`, `alert`, `share`), no `canvas` need and
no `code` block, and no `outside("device")`. These exist in this document
only, and each needs its prompt text and its model check when its phase
comes. The prototype draws a picture, a link, and the alert of a timer as
placeholders.

### 2. The kit and the compile step

The kit is one JS library in the app's assets. The host calls it in the
sandbox with the app file as text, through one new entry of the sandbox
with its own request and result shapes and its own time limit. The entry
for an app event stays as it is. It returns:

- a report: line faults, part faults, the result of each figure example,
  and the kit's own walk of the app file (it uses each control once and
  reports a control that does nothing, a control that never comes to the
  screen, and a change that the screen does not show). Each measured
  "clean" rate of the prototype used this walk, so it is part of the kit;
- `stateSchema` and `initialState`, with initial derived values;
- `ui`, a tree of today's native catalog;
- `source`: the evaluator and the compiled file. It handles each action
  event, computes each formula again, and returns the state.

Then today's checks run unchanged: `ToolSpec.parse`, `ToolGenerationSmoke`,
the language check, and the presentation check. The kit checks the app
file, and today's checks test the compiled app, so there is one validator
of the app format, as today.

- Today's smoke test sends each number input its lowest and highest value.
  So each part gives its number inputs a `min` and a `max`, and the kit
  keeps each figure finite.
- A candidate that the kit reports clean and that then fails one of
  today's checks: the fault text goes into the same repair steps as a kit
  fault.

Mapping rules that phase 1 must prove on a device:

- Each part keeps its inner values in its own state object, next to its
  value. A plan of 9 needs, the largest saved one, then has about 20 of
  the 40 fields.
- State field names come from need names, so a need that a later change
  leaves as it is keeps its data. For a need that changes, the kit gets
  the earlier file and the stored state and computes the next state by
  the rule of section 5.
- A `visible=` test needs a boolean state field, or the `source` returns
  `ui` for each event. Phase 1 picks one by measurement.
- A sorted or filtered view of a list is a second list field. A row
  control writes back to the stored list by the row key.
- `dateInput` cannot bind an empty text. A date field starts at today.
- `timeInput` holds minutes. The kit converts to and from "HH:MM".
- `numberInput` shows an Apply button, as in today's apps.
- `chart` wants a categories array and a number array in the state.
- At most 4 images. A picture in each row or card becomes an `emoji`:
  the row field then holds one emoji sign, not a description.
- A `picture` need gets its image from the picture maker of the agent,
  for the accepted plan only. A picture takes about 20 s and is a paid
  call, so the app goes live first and the picture fills its place when it
  is ready. The fill is a second revision of the saved app that changes
  only the image, because today's accept step refuses a commit after the
  person changed the state. If phase 5 finds no safe way to do that, the
  app waits for its pictures, as today. With pictures off, the part shows
  nothing.
  Before phase 5 the kit has no picture maker. So in phases 2 to 4 a plan
  with a `picture` need is not taken. The host knows this only when a plan
  comes back, so the rule is the rule of `outside`: when two of the three
  lanes return a plan with a picture, the loop goes on at once with
  today's steps. Such a request then takes today's time and the wait for
  the second plan, about 10 s: one plan that thinks took 9 to 15 s for
  these cases in the route run. In that run 3 of the 5 image cases of the
  corpus had a picture need.
  These cases are among the 26 form cases of the phase 3 median, so they
  pull it up until phase 5.
- `list.reorder` gives a real drag. The prototype used two buttons.
- A `drawing` part becomes one surface that a `canvas` node shows. The
  kit, not the model, writes the code that draws it: for each event it
  computes the shapes from the state and returns drawing commands. Read in
  `GraphicsContract.kt`: the commands circle, rect, roundRect, line,
  polygon, and text exist with the fields that the shapes need (a text
  has a size and an alignment). A circle, a rect, and a polygon take no
  outline there, so a shape with `stroke=true` becomes an `arc` of 360
  degrees, a `polyline`, or a `path`, which have a stroke width. Both
  saved plans with a drawing use `stroke=true`.
  The limits, for all surfaces of one app together: 512 commands, 2048
  characters of text, and 2048 pairs of coordinates. An app has at most
  four surfaces, and each drawing, each 3D model, a timer with rounds, and
  each canvas is one. The kit reports a plan that goes over a limit as a
  fault of its need line, so the repair step can make it smaller.
- `animate` (a timed change of a number after a press) needs frame or
  timer events, which Alphy gives to an app with a declared surface while
  its playback is `playing`. A drawing part gives the app that surface.
  The same rule already carries the rounds of the timer part (section 8).
  The kit asks for these events only while a timed change runs.
- A `model3d` part is drawn by the kit as polygons on such a surface, far
  faces first, as the prototype does it. It does not use the 3D runtime.
  A turn by a drag is a pointer event of the surface, with the 100 ms
  limit. The prototype also gives two arrow buttons. A model of 10 bodies
  is about 100 polygons.
- These three rules are read from the contract text and are not proven on
  a device. Phase 1 proves them with two more saved plans.

### 3. The loop

For a new app the first step of the loop is the plan step.

1. The host sends the request with the plan prompt in three lanes at one
   time: T1 and T2 think, A does not.
2. An `outside` reply comes fast, from each kind of lane (1 to 3 s). When
   two of the three lanes reply `outside`, the loop goes on at once with
   today's guide and tools. One `outside` reply does not decide: the plan
   of a different lane may still win. Basis: with the prompt for both
   modes each lane that thinks gave the right path for the 45 corpus
   cases, and the lane with no thinking replied `outside` alone in 4 of 14
   runs of a later check.
   The host itself skips the plan step for a request with an attached
   image or scene file, for a change of an app with no `appFile`, and for
   course slides (`generateLesson`).
   The lanes get the same task text as today's agent step (the request,
   the widget brief, and the excerpts). The prototype gave them the bare
   request, so the phase 2 model check runs this form too.
   A request that needs a device function with no part (calendar, maps,
   flashlight, location, a live value from the network before phase 5)
   must get `outside("device")`. The corpus has no such request, so this
   word is not measured. Phase 2 measures it before the switch goes on.
3. The first lane that thinks and compiles clean is the candidate. It
   passes today's checks (section 2) and is accepted. The host finishes
   the loop. The model made one call.
4. A plan with faults and no written section gets up to 2 repair steps with
   no thinking. The model may send need lines, state lines, the header,
   and delete lines. The host drops each other line.
5. A plan with a written section: see section 6.
6. No plan from a lane that thinks 20 s after the plan step started:
   lane A's app is taken if it is clean, passes the checks, and is simple
   (its plan has no drawing, no button, and no state line). A plan with no
   thinking that has one of these was weak in 6 of 7 runs of the dialogue
   check, where the screens had a drawing or a game. No app request with
   such a plan was measured at this point. So it is not taken. A lane that
   thinks may still win until 30 s. Then the loop goes on with today's
   `write_app`, and the model gets the request, the file, and the host
   report. Lane A is never taken when a lane that thinks has returned a
   plan with a written section or a code block, because lane A's app would
   then lack what the request asks for.

The steps above are for an app request. A request of a dialogue uses the
same three lanes and the same prompt, with one host line in the request,
and a different rule for step 3 (rule D, section 12).

The plan step needs a call with thinking off (lane A, repairs) and a call
with thinking on. On the latest main (ce37f1c, 2026-11-18) each of the
four providers has a profile with `reasoningOn` and `reasoningOff`, so
the stage of a call can choose. Only DeepSeek has a real "off". For
OpenAI, OpenRouter, and Anthropic "off" is a low effort, not none. So
lane A may not be fast with each provider.

Which provider gets the plan step: the switch of section 11 is one switch
for each provider. Two providers go first.
- DeepSeek, because each number of this design is from its model, and so
  is today's 58.8 s.
- OpenAI, because the user's goal is the full duplex dialogue with OpenAI.
  That voice mode exists only with an OpenAI account
  (`2026-11-18-live-voice-dialog-design.md`, 5.1.1), and each of its
  screens comes from the chat agent of that account. So without the
  OpenAI switch a dialogue gets nothing from this design. No number of
  this design is from an OpenAI model yet.
Each provider gets its own paired runs of phase 3 before its switch goes
on. For a provider with no real "off", which OpenAI is, the plan step has
the two lanes that think and no lane A, so it has no preview, no 20 s
point, and no fast lane for rule D. Until its switch is on, a provider
uses today's steps. A model check with an OpenAI model needs calls with
the user's OpenAI key, which the user approves before phase 2 runs them.

### 4. The gate, and `finish`

- A part-built app: each figure example (the model gives inputs and the
  expected result, the kit computes), then the smoke test. The model
  writes no `finish` checks, because it wrote no controls.
- An app with a code block (section 10): the model also writes `finish`
  checks for the block, as it does today for its own code.
- `AppSteps` stays the one behavior checker. It runs the model's checks on
  today's steps as before. For the new step it runs fixture scenarios in
  tests (section 11).

### 5. Storage and later changes

- The app definition gets one optional field, `appFile`, with the lines.
  The compiled `ui` and `source` are stored as today, so the renderer and
  each surface read what they read now.
- A follow-up request for an app that has `appFile`: the loop gets the
  file and replies with lines. The host compiles and checks again. This is
  the line patch that R2 asks for. The step thinks, as today's edit does.
  It gets the header, the state lines, and the need lines, and it may send
  lines of these kinds and delete lines. It may send section lines only
  for an app that has a section. Measured for the screens of a dialogue
  (section 12): such a patch is 1 to 8 lines, and the values of the parts
  that it does not change stay.
- **A change that alters stored data.** Decided by the user on 2026-11-18
  (option B). Read in the code: today a change may add a state field, and
  it cannot remove one or alter its shape (`stateForRevision`:
  "Refinements may add fields, but cannot remove, retype, or reset stored
  values"). A list is one field, so "add a notes column to my list" is
  refused today when the list has data.
  For an app with `appFile` the kit computes the next state from the
  stored one:
  - A value of the person (an input, a counter, the rows of a list) is
    kept by the name of its need and of its field.
  - A column that the change adds gets its start value in each stored row.
  - A removed or retyped column, and a retyped input, stay refused. The
    line is a fault, and the loop tells the person that the saved data
    blocks the change.
  - A need that the change removes keeps its stored field, unused. Nothing
    of the person is deleted.
  - Derived values (a figure, the arrays of a chart, a sorted view) are
    computed again.
  - The inner values of a part (the open tab, the step of a lesson, the
    part that a 3D model shows as selected) stay when the need line of
    that part did not change. They start again when it changed. This is
    the rule that the prototype measured: a stored value stays when its
    state line is the same.
  `stateForRevision` gets this one new rule, for an app with `appFile`
  only. An app of today's form keeps today's rule.
- An app with a part that needs a result (photo, ask, fetch) sets
  `capabilityResults`, and today's loop treats such an app as private: the
  model sees no `ui` and no start state, and `write_app` is refused. That
  rule stays. For a later change the model gets the app file, which is its
  own earlier output and holds no data of the person. The fallback to
  `write_app` does not exist for such an app: the loop tells the person.
  An app with only alert or share parts does not set `capabilityResults`.
- If the line steps fail for a new app, the loop goes on with `write_app`
  and the app has no `appFile`. It is then an app of today's form.
- If the line steps fail for a later change of an app with `appFile`: when
  the person has stored nothing in the app yet, the loop goes on with
  `write_app`, as for a new app. When the app has stored data, the change
  ends with no change: the loop tells the person, and the app stays as it
  was. A `write_app` there would have to keep each state field that the
  kit made, from a `source` that the kit wrote, and today's rule refuses
  each removed or retyped field, so that fallback would fail each time.
- Test and fix on an app with `appFile` uses the same line steps: the
  report of the person and one patch of the plan. Its gate is the gate of
  a new part-built app (the kit's report and the smoke test), so the model
  writes no `finish` checks. The `run` tool stays, so the model can look
  at the state that the report names.
- An app with no `appFile` keeps `edit_app`.

### 6. A written section in a part-built app

When no part fits an ask, the plan holds `need NAME = hole("...")`. A
hole is for a part of an ordinary screen. The prompt forbids it for a game
and for a 3D scene, which get the `outside` reply: three such requests got
a hole from an earlier prompt, and no step could build it.

1. The parts of the plan compile. When the preview of section 7 is on,
   they show there, and the place of the section shows a placeholder. The
   app is not live yet, so it does not change under the person later.
2. One step with no thinking writes the lines of the section. The kit
   checks them with its walk of the whole app.
3. If that fails, one step that thinks writes them again, with a limit of
   60 s.
4. If that fails too, the loop goes on with today's `write_app` for the
   whole app. The app is never shown as usable with a broken section.

The 20 s and 30 s limits of section 3 are for the plan step. An app with a
section is slower: a whole route with a section took 24 to 106 s in the
held-out run, before the missing functions were added.

The kit must compile free component lines and formulas to Alphy's
path-only bindings and flat list records. This is the largest single piece
of phase 2, so phase 1 includes one plan with a section.

Evidence: a first try with no thinking was clean for 6 of 8 sections in
1.2 to 3.8 s on development requests.

### 7. Preview

Today nothing reads partial output, and the publisher of the provisional
card is not called (`ToolDraftPreviewPublisher.publish`). The card and its
state still exist (`GeneratedToolPreview`, not interactive).

- The lines of lane A are compiled as they arrive and published to that
  card, with its "preview, check continues" label.
- The live app replaces the preview once. After that the app never changes
  by itself.
- Risk: lane A's plan had the same part kinds as the plan that thinks in
  about 1 of 3 development runs. So the preview often differs from the
  live app. Phase 4 measures this on a device, and the user decides
  whether the preview stays.

### 8. Timers

Verified in the code: the bound `timer` node shows a countdown or a
stopwatch and sends no event. A foreground countdown gives an alert at the
end, and the host owns its state.

- A single countdown with an alert (tea, eggs) maps to the `timer` node.
- Rounds, a rest phase, and a count of finished rounds (workout, pomodoro)
  need an event when the time ends. The source can ask for timer events
  (`timers:[{id,intervalMs}]`, 16 to 60000 ms, 8 at most), and Alphy gives
  them only to an app with a declared surface that a `canvas` node shows.
  Timer events also run only while playback is `playing`.
  So this form of the part draws its own ring on a small canvas with host
  code, and gets its events that way. Phase 1 proves it on a device. If it
  fails, the plan prompt gets a fourth word, `outside("timer")`, for such
  a request, and it takes today's steps.
- Two points that the code text leaves open, for phase 1:
  - The prompt lets `seconds` be a formula, such as the time of the tea
    that a choice holds, and 2 of the 4 timer cases of the corpus need it
    with an alert. The guide text says that the `timer` node has a fixed
    duration. Phase 1 tests whether the `source` may return `ui` with a
    new duration after the choice changed. Its saved timer plan has such a
    formula.
  - A timer event cannot ask for an effect. So a timer with rounds on a
    canvas cannot give a sound or a vibration at the end of a round. The
    part then shows the end on the screen only, and the report of the kit
    has a note, not a fault. Neither of the two corpus cases with rounds
    asks for a sound.
  If the first point fails, a timer whose time comes from a choice also
  gets `outside("timer")`.

### 9. Capability parts

A capability part is a host template over one capability of Alphy's
catalog. The kit writes the code that asks for it and that takes its
result. The model chooses the part and writes no code.

Rules of today's Alphy that the kit must keep (read in
`CapabilityContract.kt`, `SandboxContract.kt`, `ToolEngine.kt`):

- Only a press of a control (an action event) may ask for a capability, at
  most 3 for one press.
- `ai.ask`, `network.request`, `image.generate`, and `share.text` must be
  the only one of their press.
- The result comes in a later event, and that event cannot ask for a
  capability. So a chain takes one press for each step.
- The app lists each capability and sets `capabilityResults`.

| Part | Capability | Works today | Limit today |
| --- | --- | --- | --- |
| `photo` (camera or picker) | `camera.capture`, `photo.pick` | yes | The app holds a handle, never the bytes. An `image` node shows it |
| `ask` | `ai.ask` | yes, for text | Prompt and answer up to 1500 characters, the provider of the account, no image |
| `fetch` | `network.request` | yes, for a small JSON value | Body and reply up to 1024 bytes, no stored key |
| `picture` | the agent's picture tool | yes | 4 pictures in one app, a paid call that the user confirms |
| `alert` | `audio.tone`, `vibration.play`, `notification.post` | yes | |
| `share` | `share.text` | yes | The user reviews the text |
| `speak` | none | no | See gap G3 |

Example, the part of the user's case that works today:

```
need shot = photo("Take a photo", source="camera")
need note = input("text", "What do you want to know?", "")
need answer = ask("Ask", "Answer in one paragraph: " + $/note)
```

Gaps of Alphy's capability layer. Each is its own change, outside this
design, and each blocks one of the user's cases whatever writes the app:

- G1. No capability takes an image. `ai.ask` needs an image argument (a
  resource handle) and a provider that reads images. This blocks "photo,
  then a provider's API, then the result".
- G2. `network.request` cannot hold a provider key, and its body and reply
  are 1024 bytes. A call to a provider's API needs a stored key and larger
  limits, or its own capability.
- G3. No speech output. A mini-app needs a `speech.speak` capability (the
  platform has a speech engine). Speech input exists only in the chat.
- G4. No picture from the internet. An `image` node takes no address, and
  a mini-app cannot fetch a source (section 12).

When such a capability exists, its part is one host template in the kit.

`2026-11-16-unified-ai-provider-design.md` (a draft) plans picture
understanding and voice output for the chat, through the provider of the
user's one key. It does not plan them for a mini-app. G1 and G3 can use
its provider layer.

Not measured: no model has seen the lines of a capability part, and the
corpus has no request for one. A check with a model needs requests and a
new budget of calls.

### 10. A code section for a drawing surface, games, and 3D

This section is for what the lines cannot give (the limits are in
"Goals"). A scheme of shapes is the `drawing` part, a thing of boxes and
cylinders is the `model3d` part, and a puzzle with buttons is lines. None
of them has model code.

Today one `source` serves the native controls and each drawing surface
("Artifact protocol 3 uses one source function body for every native
control and surface"). So a part-built app can hold a canvas.

```
need score = figure("count", "Score", $/board/score, example=[{}, 0])
need board = canvas("Snake on a 20 by 20 grid. A swipe turns it.", width=360, height=360, state={score: 0})
code board
  ...JavaScript of the model...
end
```

The contract of a code block:

- It is the body of one function. It gets its own state object, the
  values of the other needs (read only), and one event: frame, pointer,
  timer, viewport, sensor (tilt), start, pause, resume, restart, stop, or
  a press of one of its own controls.
- In a mixed app the canvas need names its controls, such as
  `controls={clear: "Clear", undo: "Undo"}`. The kit draws them as native
  buttons and sends each press to the block. The drawing board of the
  corpus needs one (a clear). A choice that a part holds, such as a color,
  is one of the values that the block reads. A game in the immersive form
  has no native controls: its block draws its own buttons and gets their
  taps as pointer events (the two forms are below).
- The kit also calls it to draw, with no change of state, when the app
  opens, for a preview or a render, and after a press of a part control
  changed a value that it reads. Without that the canvas would show the
  old value until its next surface event.
- It returns its new state, its drawing commands, and its requests for
  frames and timers. It cannot ask for a capability, because Alphy lets
  only a press do that.
- A timer event and a tilt event carry no surface name in Alphy. So the
  kit owns the names: a timer belongs to the need that asked for it, and
  tilt events go to the block of a canvas with `tilt=true`. For that
  canvas the kit lists the tilt stream as a capability and asks for it on
  a press, as Alphy's rule wants.
- The values that it names in `state=` are readable by the parts, as
  `$/board/score` above. So a figure, a progress, or a list can show them,
  and a button of a part can reset them with an action.
- The kit's `source` sends each event to its owner: a press of a part
  control to the kit, a surface event to the block.
- Limits that the block shares with the kit: 64 KiB for the whole
  `source`, 100 ms for a frame, pointer, timer, or live input event, and
  512 drawing commands and 32 KiB for all surfaces of the app together.
- The kit's walk does not report a control whose press goes to a block.
- The gate is today's gate for code: the smoke test, and `finish` checks
  that the model writes with pointer, drag, and wait steps of `AppSteps`.
  A block is model code, so R6 applies: it goes live when the checks pass.
- A later change patches the block by find and replace, as `edit_app`
  does today for `source`.
- A block that fails its checks is written again by the loop. When the
  steps run out, the loop goes on with today's `write_app` for the whole
  app, as for a section in lines.

Two forms, as in today's Alphy:

- A mixed app: the canvas is one node among the parts, with native
  controls around it. A drawing board with a color choice is this form.
- A game: `app(..., presentation="immersive")` with one canvas need that
  fills the screen and no other visible need. The block draws its own
  buttons and score and gets pointer events, as today's game rule asks.
  The kit writes no canvas node, because Alphy refuses one in this mode.

The game logic is model code, as today, so the time of a game is about
today's time. The gain is one format for each kind of app, and that a
mixed app needs code only for its canvas.

How the loop writes a block: the plan names the canvas need with its
description, its controls, and its state. Then one step of the loop that
thinks writes the block and its `finish` checks, with today's limits for
a write step. Each failed check goes back to the model in the same loop,
as today.

3D is a different runtime in Alphy: the `source` is an ES module with
`createScene`, with no capabilities and no canvas. Native controls and
state work there. The kit needs a second form for that runtime. Until it
exists, a 3D request that the `model3d` part cannot give (a ball, a
texture, a scene that moves by itself, a world to walk in) gets
`outside("3d")` and today's steps. A mixed app with classic UI, a picture,
and such a 3D scene is possible in today's Alphy, and it stays on today's
steps until then.

### 11. Tests, trace, and switch

- Tests with the scripted provider (`AgentScriptProvider`): an app
  workflow through the plan step, a game workflow through `outside` and
  `write_app`, a follow-up change by lines, and each fallback.
- The kit's own corpus runs on a development machine and, as one
  instrumented test, in the device sandbox.
- `ComparisonChatCaptureTest` already takes `first_ui_ms` and
  `completed_ui_ms` from the screen. Until phase 4 the two are equal.
  `ToolGenerationTrace` gets an event for each lane and for the compile
  step, so a run shows where its time went.
- A switch in `GenerationSettings` turns the plan step on, one for each
  provider (section 3). The switch of a provider is off until phase 3
  passes with that provider.

### 12. Dialogue mode

The screen of a dialogue is one app file. The voice model speaks. A step of
the loop writes and patches the file, and the host compiles and draws it.
The voice model does not write lines. It asks for a screen in words, and it
may press a control of the screen.

Three kinds of change, from the 12 prepared interruptions of the mocks
(three for each screen):

| Kind | Interruptions of the mocks | Model work |
| --- | --- | --- |
| A press of a control that the screen has | "Верни фотографию", "А если не вращать?", "Начнём заново", "Разбери по частям", "Стоп, только камеру" (5 of 12) | none |
| A changed line (a text, an argument, a shape) | "Что на заднем плане?", "Покажи связь", "Подскажи", "Сделай сложнее", "Убери панель" (5 of 12) | one patch of the plan |
| A new part | "Нарисуй нашего ровера", "Проверь меня" (2 of 12) | one patch of the plan, and for a picture a paid call |

So a screen must be planned with the controls that the talk will need.
Then 5 of 12 turns cost no model call.

What each mock needs from the format:

| Mock | Exists in the prototype | New part or function | Blocked by Alphy |
| --- | --- | --- | --- |
| Talk and materials | tabs, a generated picture | a text card, a link to a source, a label of the kind of screen | a picture found on the internet (gap G4) |
| Lesson and experiment | a slider, a choice | a drawing of shapes that formulas place (with sin and cos), a button that moves a value over time, a lesson step card, a question with choices | |
| Game and tutor | a written section in lines (a list of pieces, a check by a formula) | the same drawing part | |
| 3D and sketch | a generated picture, a text card | a 3D model as a list of simple bodies with named parts, or a code block in the 3D runtime | |

Read in the source of the mocks: the orbit is a set of shapes that one
angle places, the bridge game is a list of pieces and one check, and the
rover is a set of boxes and cylinders with named parts. None needs logic
on each frame.

Decided by the user on 2026-11-18 ("Data lines first"): a 2D model, a
simple game, and a 3D model are lines of data that the host draws. The
format gets a drawing part (shapes that formulas place), a timed change of
a value, and a 3D part of simple bodies with named parts. Model code (the
canvas code block of section 10, the 3D runtime) is the escape for what
these parts cannot do, such as a game with logic on each frame.

Rules of this design and of Alphy that dialogue mode touches. Each is the
user's to decide:

- The screen changes while the person looks at it. Here that is the
  purpose. Proposed rule: it changes only as the answer to what the person
  said or did.
- A press that the voice model makes may change the state of the screen.
  It may never start a capability (a photo, a share, a paid call).
- The voice model gets the app file and the presses of the person. For a
  private app it gets no state and no result of a capability.
- Whether the plan with no thinking may show at once (R9). For a form it
  fails about 3 in 10. For a screen of a dialogue the model check gives a
  narrower rule (rule D below): at once only when the plan has no drawing,
  no button, and no state line.
- A generated picture is a paid call that the person confirms today.
- A source of a found picture comes from the host. The plan may write a
  link only for an address that the request gives, and the host drops a
  different one.

Depends on, outside this design:

- A voice model that listens and speaks at one time. Checked on the latest
  main (ce37f1c): the voice session records speech, sends the file to a
  transcription model or to the phone, asks the chat model, and reads the
  reply with the phone voice or a cloud voice. It has no realtime model
  and no socket code, and the OpenAI profile has no cloud voice. The user
  expected a ChatGPT live model there. It is not on main.
- G4: a picture from the internet with its source. An `image` node takes
  no address today, and source fetch is on the planned list.
- A wide screen (foldable, tablet) puts the main visual beside the text.
  The host must lay the parts out by their role.

The chat decision (6.6 s) cannot sit in each turn. When the person
interrupts, a running step for the earlier line is cancelled.

#### The model check of 2026-11-18

The record is `alphy-dsl-prototype/search/log.md`, section "Dialogue
screens by a model". 655 calls of DeepSeek, thinking low. Pictures are in
`alphy-dsl-prototype/shots/`. Each time below is a time of that model.

Built in the prototype, with tests: the parts drawing, button with a timed
change, model3d, card, link, lesson, question, a found picture, and a
layout by role for a wide screen. The four mock screens are app files of 4
to 16 lines, and each is live under the host gate.

Implementation choices that the check gave:

- **Two prompts by mode** (the first proposal of the author; the user then
  decided "Both modes", see "One prompt for both modes" below). The host knows the mode. An app request gets the
  earlier plan prompt, byte for byte (45 of 45 corpus cases on the expected
  path with the code of today). A request of a dialogue gets a prompt with
  the new kinds, state lines in the plan, a rule of what a screen of a
  dialogue is, and three more examples. One prompt for both sent 4 of 15
  game and 3D cases to the wrong path. The final dialogue prompt on the 45
  app cases sent 7 of 15 to the wrong path and made a form slower (median
  7.9 s against 5.5 s). Its rule for a small game is too wide: memory and
  2048 got a part that the host cannot build.
- **Rule D for the first screen.** Three plans start at one time, as in the
  form path: two that think and one that does not. The plan with no
  thinking goes live at once when the host report is clean and the plan has
  no drawing, no button, and no state line. Else the first clean plan that
  thinks goes live. The 20 s point of section 3 adds nothing here, because
  a simple plan is live before it. At 30 s with no clean plan the loop
  goes on with today's steps, as for an app request. Whether a dialogue
  waits for them is for phase 8.
- **A spoken change is a patch of the plan.** The model gets the header,
  the state lines, and the need lines, and the words of the person. It
  sends the lines that change. The host drops each line that is not a need,
  a state, a header, or a delete line. The app that runs keeps each value
  whose state line did not change.
- **Lenient shapes.** A shape takes its size, width, or radius after the
  color with no name, and a shape fault says what the host got.

Results on requests that the tuning did not see (12 topics that a model
wrote from the user's words about the mode, two forms of each request, two
repeats, and 3 requests that need a program). Separate readers rated the
pictures blind.

| | Result | Bar set before the run |
| --- | --- | --- |
| First screen, pass / partial / fail of 48 | 24 / 22 / 2 | 75% pass: not met. 10% fail: met |
| First screen, seconds, median / 90th | 12.7 / 20.0 | 8 s: not met. 20 s: met |
| A screen that rule D takes from the fast plan at once (12 photo screens, 2 3D models) | 1.5 to 3.5 s, 12 pass and 2 partial of 14 | |
| A fast plan that is not simple, taken at the 20 s guard | 1 pass, 5 partial, 1 fail of 7 | |
| A drawn model, a game, or a 3D model, from a plan that thinks | 12 to 16 s | |
| A request that needs a program goes outside | 6 of 6, in 3 s | met |
| A spoken change with thinking, right / partly / wrong of 24 | 16 / 5 / 3, median 6.7 s | 80% and 5 s: not met |
| A spoken change with no thinking | 8 / 12 / 4, median 2.1 s | |
| A stored value lost by a change, on a screen that was used first (36 turns, 139 values, replayed with no call) | 0 | met |

What limits the result:

- A plan that thinks about a drawing has a median of 2500 reasoning tokens
  (630 for a form). Effort "minimal", a hint to think briefly, and a third
  and fourth plan did not change the time much.
- Half of the screens have one defect that a reader notices. The causes
  with a host fix that needs no model: a label of a drawing that is cut off
  or lies on a shape (7 of 24), a lesson or a question that the request did
  not ask for (5), a wrong text of the timer part (2).
- Not built and not tested: these host fixes, a second model, the layout
  on a wide screen for a made screen, and each turn with a real voice
  model. The form of the request that the voice model sends is an
  Assumption: the check used the words of the person, and a brief of one
  or two sentences.
- Limits of the read: each picture was read one time by one of four
  separate readers, and the share of pass by reader went from 5 to 12 of
  21. The set has the four kinds of the mocks only. One of the two fails
  is partly an effect of the test code.

#### One prompt for both modes

Decided by the user on 2026-11-18 ("Both modes"), against the
recommendation of the author: an app request also gets the drawing and 3D
kinds. So "Data lines first" holds for an app request and for a dialogue.

How it is built (`alphy-dsl-prototype/prompts-unified/`):

- One system prompt with each kind. It states the limits of the lines, not
  kinds of games: no random value, no movement that goes on by itself (a
  value moves after a press, with a timed change, or while a timer counts
  down), no drag and no swipe, no canvas to draw on, and in 3D no ball and
  no texture. A request that needs one of these, or a known board game,
  gets the one-line outside reply. A puzzle or a small game that needs
  none of them is planned. A hole is never the answer to a game or a 3D
  scene.
- A drawing, a 3D model, a lesson, a question, and a card are for a request
  that asks to show, to explain, to teach, or to play. An illustration or a
  photo of a thing is a picture, not a drawing.
- For a request of a dialogue the host adds one line to the request, as it
  adds the language line: what a screen of a dialogue is. Without this
  line a spoken request gave a chart of invented data.
- The go-live rule is host logic and stays by mode: section 3 for an app
  request, rule D for a dialogue.

Measured with 444 calls (the record is the log section "One prompt for
both modes"). `prompts/` in the prototype is still the earlier text. The
prompt for both modes is `prompts-unified/`.

| | Result | Bar set before the run |
| --- | --- | --- |
| The 45 corpus cases on their path | 45 of 45, 25 of 25 forms clean | met (in-sample for the border wording) |
| Forms not seen in tuning, seconds to live, median | 4.3 (4.8 with the earlier prompt) | 6.1 s: met |
| The same forms, behavior judge, pass / fail or dimmed of 20 | 16 / 4 (16 / 4 before) | 14 and 5: met |
| App requests that data lines must serve, on a visual path | 12 of 16 | not met by its letter |
| Dialogue screens, paired blind read of 22 | new better 10, earlier better 8, same 4: no difference found | not worse more often: met |
| Dialogue, seconds to live, median | 13.8 (12.7 with the dialogue prompt) | not more than 2 s higher: met |

What an app request gets now: a drawn timeline for a trip plan (a part that
the host could not build before), a strip of moods for a week, a lesson
with a drawn model, a 3D model of a castle with named parts, a puzzle with
a drawing and buttons.

What is not stable: a toy or a scene near the border. As an app request,
with one plan call, a fishing game with fish that swim, a game with fruit
that falls, a kite in the wind, and a volcano that erupts went outside (the
4 of 16 above). As a request of a dialogue, with the host line and three
plans, the kite and the volcano were planned, the fishing game by one plan
of three, and the fruit game by none. The volcano was planned in one route
round and outside in the next.

Also measured: a spoken change under the unified prompt stays clean (37 of
38 with thinking) and is about 1 s slower in the median (5.6 s and 7.8 s
against 4.2 s and 6.7 s). No reader looked at these changes. With the
unified prompt the fast plan of a dialogue is simple half as often, so the
photo path of rule D fires in 3 of 24 runs against 6.

Decided and open after this part:

- **Decided by the user on 2026-11-18 ("Go outside"): a toy that needs
  movement by itself, in a dialogue, goes outside**, to the code path. The
  plan makes no version with buttons in its place. The host line of a
  dialogue says so. Checked with 42 calls: the three game briefs that name
  such movement went outside in each plan, in 1.3 to 1.5 s. The bridge
  puzzle, the lessons, the photo screens, and the 3D models are still
  planned (16 of 16 plans that think clean).
- **When the plans disagree on outside** (Implementation choice): two
  outside replies of the three plans send the request outside at the time
  of the second one. One outside reply does not stop a plan of a different
  lane. The plan with no thinking replied outside alone in 4 of 14 runs of
  the check, so it cannot decide alone.

- The cost of the longer prompt (5340 tokens against 3228) for a form is
  between 0 and about 1 s. Runs of one prompt differ by as much.
- The behavior judge does not see shapes. It failed a mood tracker whose
  strip of moods is a drawing. It needs a view of a drawing before it can
  score an app with one.
- `found=` for a real photo needs the capability G4 in both modes. Until
  then the host makes the picture from the description.

## Phases and stop rules

1. **Proof with no model.** The kit in the sandbox. Eight saved plans
   (a calculator, a list with row controls, a quiz, tabs with a chart, a
   timer with rounds, one plan with a written section, a lesson with a
   drawing and a timed change, and a 3D model with named parts) compile
   and pass `ToolSpec.parse` and `ToolGenerationSmoke`, with its lowest
   and highest number values, on a device. The last two prove the mapping
   rules of the drawing, of `animate`, and of `model3d` (section 2). The
   prototype has such plans: `examples/dialogue-lesson.plan` and
   `examples/dialogue-rover.plan`. Stop: if a plan cannot pass inside
   Alphy's limits, the design comes back to the user before anything else
   is built. If only the drawing or the 3D model cannot pass, that kind
   leaves the prompt, its requests get `outside`, and the user hears it.
2. **The loop.** Plan step, repairs, written section, fallbacks, storage,
   later changes. Scripted tests, and one model check outside the app: the
   routing word for a request with a device function, the task text of
   section 3, and each prompt line that phase 1 changed. Its request set:
   the 45 corpus cases and at least 15 requests for a device function,
   which the user approves. Pass: no request for a device function with no
   part gets a plan. A miss would go live without the asked function, so
   on a miss the host gets its own filter before the switch goes on.
3. **Paired device runs** on the 45 corpus cases and on about 20 requests
   that the user writes. See "Acceptance".
4. **Preview** on the provisional card, and the user's decision on it.
5. **Capability parts**: photo, ask, fetch, picture, alert, share. Scripted
   tests, then device runs with real permissions. Gaps G1 to G3 are
   separate changes of the capability layer. Each adds its part when done.
6. **The canvas code section.** The contract of section 10, a mixed app
   (parts, a picture, a canvas), then games through the app file. Stop: a
   game through the app file must not be accepted less often, and must
   not get a lower look score, than through today's steps on the 8 game
   and drawing cases of the corpus. Until this phase passes, such a
   request gets `outside`.
7. **The 3D form of the kit.** Only after phase 6, and only if the user
   wants 3D in the app file.
8. **Dialogue mode** (section 12). It needs a full duplex voice in Alphy
   first, which is not part of this design. The drawing part, the timed
   change of a value, and the 3D part of simple bodies belong to the part
   set of phases 1 and 2, by the user's decision, so each of the four
   mocks is in reach with no model code. This phase adds what only a
   dialogue needs: the host line in the request, rule D for the first
   screen, a spoken change as a patch of the plan, a press of a control by
   the voice model, the source of a found picture (gap G4), and the layout
   by role on a wide screen. Its acceptance bar is the user's to set when
   the voice exists. The bars of the author were not met in the prototype
   (section 12), so this phase starts with the host fixes that the model
   check names, and with a new set of requests.

## Acceptance

Paired device runs, today's loop against the plan step, same hour.

- Form-style cases: the median `completed_ui_ms` is at most 0.4 of today's.
  Basis: about 13 s expected (6.6 s before the agent, about 5 s for the
  plan, compile and checks) against 58.8 s.
- Accepted-app rate is not lower than today's on the same cases.
- The look score (`tools/agent_corpus/visual_score.py`) is not lower.
- Games, drawing, and 3D: accepted-app rate is not lower, and the median
  time is at most 2 s above today's.
- On the user's requests: pass or partial by scenario for at least as many
  apps as today's loop.
- A later change by lines, and test and fix: the corpus has no change
  case, so phase 3 gets a set of about 20 change requests on apps of the
  corpus, which the user approves. The change by lines is accepted no less
  often than `edit_app` on the same requests, and no stored value is lost.
  The only measurement so far is the spoken change of section 12 (16 of 24
  right), which did not meet the bar of the author.
- Phase 5: each capability part passes a scripted test and one device run
  with the real permission or picker.
- Phase 6: a mixed app (parts, a picture, a canvas with a code block)
  passes scripted checks with pointer steps, and the 8 game and drawing
  cases are accepted no less often, with no lower look score, than
  through today's steps.

## Open points

- The canvas code section has no measurement. Its contract is a design
  from Alphy's protocol text, not from a run.
- Gaps G1 to G3 of the capability layer (section 9). Without G1 or G2
  no app can send a photo to a provider, and without G3 no app can speak.
- Prompts. The prompts and the part arguments are measured as they are in
  the prototype (`prompts-unified/`). Three lines must change for Alphy (a
  list can be dragged, the timer count, and a picture in a row or on a
  card, which is one emoji sign and not a description). A change of a prompt or of a
  part argument needs the routing check and the judged check of the
  prototype again, with a model, before phase 3. That is about 160 calls
  each time. A difference of less than about 1.5 s between two prompts is
  inside the noise of one run each.
- The longer prompt for both modes (5340 tokens against 3228) may cost a
  form up to about 1 s. The runs did not resolve it.
- The behavior judge of the prototype does not see a drawing or a 3D
  model. An app request now may get one, so the judged check needs a view
  of the shapes first: the kit lists the shapes of a drawing and the
  bodies of a model as values that a scenario step can read. This is work
  of phase 2 in the prototype, before the first judged check of a changed
  prompt.
- Screens with a drawing. Half of the screens of a dialogue had one defect
  that a reader notices. Host fixes that need no model are named and not
  built: a test of each label of a drawing against its box and against
  the other shapes, a rule against a lesson or a question that the request
  did not ask for, and the text of the timer part.
- A plan that thinks about a drawing takes 12 to 16 s with the measured
  model. No second model is measured.
- Model calls for each check. The user approves each budget of calls. The
  counter of the prototype had 57 calls left of 4471 at the end of
  2026-11-18, and the user allowed a reset for this work. One round of the
  routing check and the judged check is about 160 calls.
- Look. The look score counts typed fields on the first screen and in rows
  as worse, and today's design rules ask for icons and emoji. The part
  templates have neither yet. Parts draw from fixed templates, and
  today's apps are designed by the model for each request. No look score
  exists for part-built apps.
  A call of the author, for the user to check: a host template cannot
  choose an emoji for the topic of a request, so the parts may get one
  optional look argument (`icon=`, an emoji sign or the name of an icon)
  on the header, a figure, a card, and the rows of a list. It is a change
  of the prompt, so it goes into the same round of model checks as the
  three lines above. Phase 1 decides by the look score of its saved plans
  whether the templates need it.
- Cost. A new app costs three plan calls in place of one, each much
  shorter than today's call.
- Rounds and counts for timers (section 8).
- Whether the preview stays (section 7).
- The function list will have more gaps. A gap shows as a written section
  that is slow or fails. The trace should count them.

## Calls made by the author

- The closed function list stays. The user pointed at `Math.pow`: the kit
  now has pow, sqrt, exp, ln, log10, date parts, and text split, each
  mapped to the platform function. A formula as free JavaScript is the
  other valid choice. It was not measured.
- The timer part is in the first set, for the single countdown.
- Jev and the local model are not in this design. Measured: Jev names the
  ready part for a section 38 of 41 right in 0.2 s, so it could later turn
  a section into a part before code is written. As a chooser between plans
  it was no better than the first plan. The local model of the deal-studio
  checkout got 5 of 41.
- The last fallback is today's `write_app`, not a dimmed app.
- The kit's walk of the app file is part of the kit. Today's smoke test
  alone does not find a control that does nothing.
- A code block goes live on the smoke test and the model's own checks, as
  model code does today. Games stay at about today's time.
- The stored-data rule is the user's decision (option B, section 5).
- An app with a section is not live before its section passes.
- An app goes live before its picture is ready.
- The sandbox gets one new entry for the compile step.
- Two kinds of written section: lines for a screen, a JavaScript block
  for a drawing surface. 3D comes last, because it is a second runtime.
- Capability parts follow Alphy's rule that only a press may ask for a
  capability. The design does not ask to change that rule.
- An app with a part that needs a result stays a private app, as today.
- A game keeps today's form: immersive, one surface, drawn buttons.
- A late picture is a second revision that changes only the image.
- The one prompt states the limits of the lines, not kinds of games. The
  dialogue rule is one host line in the request, not a second prompt.
- Two `outside` replies of the three lanes decide. One does not.
- Rule D: in a dialogue the plan with no thinking goes live at once only
  when it has no drawing, no button, and no state line.
- A spoken change, and each later change of an app with `appFile`, is a
  patch of the plan: need, state, header, and delete lines only.
- The host drops a link whose address the request does not give, and it
  shows the source of a found picture itself.
- The host reads a shape with its size, width, or radius after the color,
  and `$/NAME` in a group of tabs or steps, because plans write them so.
- A drawing and a 3D model are surfaces that kit code draws. The 3D model
  does not use the 3D runtime.

Calls that answer the last builder check (2026-11-18). Each changes what
gets built, so the user should check them:

- The plan step has one switch for each provider. DeepSeek and OpenAI go
  first, each with its own phase 3 runs. A provider with no real "off"
  gets no lane A (section 3).
- Before phase 5 a plan with a `picture` need takes today's steps
  (section 2).
- The parts may get one optional look argument, `icon=` (open points).
- At the 20 s point the plan with no thinking is taken only when it is
  simple (section 3).
- A later change of an app with stored data has no `write_app` fallback:
  it ends with no change and a message (section 5).
- Test and fix on a part-built app has no model-written `finish` checks.
  A later change by lines gets its own request set and bar in phase 3
  (section 5, "Acceptance").
- A timer with rounds gives no sound at the end of a round (section 8).
- In a mixed app a canvas has native controls. An immersive game draws its
  own (section 10).

## Left to the builder

- The form of the kit's API, and the size split between compile-time code
  and the `source` (limit 64 KiB).
- Boolean state fields or a returned `ui` for visibility tests.
- The names of new trace events and of the settings key.
- The place of the `appFile` field in the validator and the store.
- The exact arguments of a code block, and how the kit keeps its own
  work on a frame event small enough for the 100 ms limit.
- On which press the kit asks for the tilt stream.
- How the kit keeps a drawing and a 3D model inside the limits of the
  drawing commands, and whether a tap on a 3D model selects a part (the
  prototype selects by buttons only).
- Whether a timed change uses frame events or timer events.
