# App file kit

The kit turns an app file into an app.
An app file is a short list of declarative lines that a model writes.
The kit parses the lines and expands each need line into ordinary lines.
Then it runs the app (state, formulas, actions, and the component tree) and checks it.

| Path | Responsibility |
| --- | --- |
| `src/dsl.js` | Lines, expressions, and `AppFile`. `HEADS` holds the head of each kind of line that `parseLine` reads: `compile.js` builds `LINE` and `NEED_NAME` from it. |
| `src/parts.js` | The parts: each one changes a need line into component, state, and formula lines. |
| `src/core.js` | The evaluator: the `App` that holds the state and builds the component tree. It reads no `process` and no file. |
| `src/check.js` | The report of the host, the check lines, the smoke run that uses each control, and the check of each look option of a component. Only the compile step uses it. |
| `src/icons.js` | Each icon name of Alphy. `build.js` writes it from `ToolUiCatalog.kt` (`shellIcons`) and `ToolIcons.kt` (the keys of `paths`). Only `parts.js` reads it, and `check.js` takes the icon names from `parts.js`. |
| `src/rows.js` | The list mapping of the run-time: `rows` maps a List to Alphy's list, and the value forms that the screen and the state share (Alphy names, times, dates, shown values). `stable(key, wish)` gives the name of a stored value: its wish when Alphy takes it, else `f` and 11 letters or digits of a hash of its key, so no other line changes it. |
| `src/draw.js` | The drawing of a surface for the run-time: `commandsOf` gives the kit's shapes (circle, rect, line, text, polygon, and their outlines) as Alphy's drawing commands with Alphy's colors, `barOf` is the bar of a timer, `budgetOf` counts what commands cost, and `fit` keeps a reply inside `LIMITS` of one reply. |
| `src/drag.js` | The drag of a 3D model for the run-time: `dragged` turns a model by 0.6 degrees for each pixel that a pointer moves along x, and `IDLE` is the drag when no drag runs, also after each open. |
| `src/run.js` | The run-time of a compiled app: `handle` answers each event, and `screen` maps each component to its Alphy node. A timer with rounds, a rest, or no alert draws its bar on a surface and asks for a timer event each second while it runs. A timer with an alert and one phase is Alphy's foreground timer. A Drawing is a canvas with one surface that each reply draws again. A value that an `animate` moves keeps its change in the state field `anim` and asks for a timer event each 50 ms while it moves. `stateOf` copies each field of `app.keep` from the stored state, so a field that a change kept stays after each event. |
| `src/emit.js` | The emitter: an expanded app file as Alphy's app definition, whose `source` is the run-time and the compiled data of the app (`sourceOf`). `dataOf` reads the compiled data back from a `source`. It gives a fault for each limit of Alphy that the kit's own mapping can pass: state fields, the fields of a need's object, source bytes, and the nodes and depth of the screen. It also keeps the lower limits: a container with no child is a fault of its line, and a plan with no state value keeps one hidden boolean field `d0`, because Alphy keeps 1 to 40 state fields. No line of a plan gets the name `d0`. Each need object has a namer of its own, so the names in it do not depend on the other needs. |
| `src/change.js` | The next state of a change: `nextState` gives the definition and the state of a changed app from the stored app of the person (section "Changes of an app"), and `BLOCKS` and `MISMATCH` are the texts of its faults. Only the compile step uses it. |
| `src/form-base.js` | What each line table uses (section "Ready plans"): `BUDGET`, `COLOR_NAMES`, the drawing size `WIDTH` and `HEIGHT`, `INSIDE`, `SENTENCE_END`, `cut` and `cutLong` (the cut of a long text), `textFault`, `optionFault` (the cap of an option by the number of options), `namedColor` and `toneColor` (a color name with a tone), `headFault` (the first line), `formFault` and `cutOf` (the check and the cut of a value by the forms of its entry), the entries and arguments that most line tables share (such as `CONTROL_LINE`, `cardLine`, and `NARRATION`), `colored` and `colorFault` (the colors of a shape), and the tree helpers `isText`, `numberOf`, and `nodesIn`. |
| `src/form-data.js` | What the line tables of the data family use (section "The data family"): `DATA_BUDGET`, the faults of a row and a mark, the context faults, the fill of a data form (`dataFill`), `tableLine`, and the host check (`checkSource`). |
| `templates/` | The ready plans and their line tables (section "Ready plans"). `WORD.plan` holds the app file lines of the screen of the template `WORD` with `{{name}}` slots, and `WORD.js` holds its line table (`SCENE`, `ITEMS`, `CELLS`, `COMPARE`, `TABLE`, `CHART`, and `NODATA`). `scene.js` also exports `SHAPES` (the shape lines), `partFault`, `diagramOf`, and `controlStart`. |
| `src/ready.js` | The registry of the ready plans: `READY` holds the line table and the ready plan of each template by its word, and `register` adds one. It gives `TEMPLATE_HEAD`, `DATA_WORDS`, and `DATA_HEAD` from the templates that the app ships. `build.js` writes `src/plans.js` (`PLANS`, the text of each ready plan) from `templates/`. |
| `src/template.js` | The engine of the template files of a screen of the voice mode (section "Template files"): `isTemplateFile`, `readTemplate` (the lines in the order of the line table and the fault of each line), `mergeTemplate` (the merge of a reply by line name), `expandTemplate` (the app file: the ready plan with its slots filled), `buildTemplate` (the compile of a template file with the compile of `compile.js`), and `checkData`. It names no template and no line of one: it reads the entry of `src/ready.js`. It exports `BUDGET` of `form-base.js` and `TEMPLATE_HEAD` of `ready.js` again. |
| `src/compile.js` | The compile `compileAppFile(text, previous?)`: an app file in; the faults and notes of the report, the definition, `hole`, the flags `blockedBySavedData` and `savedDataMismatch`, and `state` out. With `previous`, `state` is the next state of a change (`src/change.js`), else `null`. `hole` is true when the file has a need of kind `hole`, also with faults, so the plan step of the app gives that plan to its loop. A result with a definition also has `frame` (section "The preview step"). A template file also gives `template`, `kept`, `expanded`, and `leftOut` (section "Template files"). The merge `patchAppFile(plan, reply)`: the plan text after a repair reply (section "Repair replies"). The reader `readPlanReply(text)`: `{kind: "outside", word}` for a reply of the one line `outside("word")`, with a word of 1 to 16 small Latin letters or digits, such as `3d`. Each other reply is `{kind: "plan", text}`: its lines with no empty line and no markdown fence line, and a final newline. Prose stays, and the compile reports it. The step `compileStep({text, plan, read, previous, cap})`: the sandbox entry, one step of the host with these three (section "The step of the host"). The step `previewStep({text, plan, previous})`: the second sandbox entry, the frame of the complete lines of a reply so far (section "The preview step"). |
| `build.js` | The bundle of the modules as one plain script for the sandbox, with the two globals `compileStep` and `previewStep`, the run-time text in it (minified by `Bun.build`), the prompt asset `app/src/main/assets/appfile/prompt.json` (`promptAsset`), and the copies of the plans and the template files for the device tests. |
| `prompt.js` | The texts of the prompt for the model check and the app: `PROMPT` (the sections of `prompt.md` by heading), `hashOf(names, sections)` (the first 12 hex digits of the SHA-256 of the named sections), `PROMPT_HASH` (the sections "System", "Repair", "Dialogue", and "Change"), and `TEMPLATE_HASH` (the sections "Template", "Template repair", and "Template change"). So each record names the prompt that made it. The section "Template rest" is in neither hash. The task texts are `taskText`, `repairText`, `changeText`, `templateRepairText`, `templateChangeText`, and `templateRestText`. `prompt.json` holds the four sections of the plan prompt as `system`, `repair`, `dialogue`, and `change`, and the section "Template" as `template`. It also holds the task texts `repairTask`, `changeTask`, `templateRepairTask`, `templateChangeTask`, and `templateRestTask`, and `templateHead`, the source of `TEMPLATE_HEAD`. The host puts the plan with no newline at its end in `{{plan}}`, the faults joined by `"\n- "` in `{{faults}}`, and the task text or the request in `{{task}}` or `{{request}}`. A filled task text is then the text of its function in `prompt.js`: `repairText` for `repairTask`, and so on. |
| `prompt.md` | The plan prompt: the section "System" is the instructions of each plan call and repair call, "Repair" is the rule of a repair call, "Dialogue" is the line under the request of the plan call of a screen of a voice dialogue (the app and `modelcheck.js` add it), and "Change" is the rule of a change call of an app (after the request and the app file). The sections "Template", "Template repair", "Template change", and "Template rest" are the template prompt of a voice screen (section "Template files"). Task 2b measured "Change" on set F of `modelcheck.js` (results document, section "Changes by lines"). `test/prompt.test.js` checks each kind, option, icon, and example of it against the kit. The section "System" names no `hole`: the kit keeps the kind and its tests. A request that no kind serves gets `outside(...)` with one of six words: `game`, `drawing`, `3d`, `picture`, `device`, or `other` for a main thing that no kind gives and that is none of the others. |
| `modelcheck.js` | The model check of `prompt.md` outside the app: for each request, a plan call of the OpenAI Responses API with the body of `OpenAiResponsesWire.kt`, the texts of `prompt.js`, `compileStep` of the kit for the read of the reply, each merge, and each compile, as the app runs them, and up to 2 repair calls at the effort low (the setting `REPAIRS` gives another count, such as 1 for the loop of the app). It counts each call in `evidence/modelcheck/spend.json` before the call and keeps each reply and record in `evidence/modelcheck/<label>/`. Its sets of requests for the plan prompt are C (the 45 cases of the corpus), D (8 requests of a voice dialogue), E (`test/fixtures/fresh-requests.json`, 28 requests that no earlier run used), and F (`test/fixtures/change-requests.json`, 16 change requests on 8 plans of `test/fixtures/model/`). A case of set F runs the events of the person through the run-time to make the stored state, sends `changeText`, merges the reply into the app file and compiles it with the stored app (`compileStep`), and gets one repair call. As in the app, it gets no repair call after a fault that the saved data blocks or the mismatch fault (the flags `blockedBySavedData` and `savedDataMismatch`). In each set, a plan with a hole gets no repair call and ends with the outcome `hole`, also when it compiles clean. The app gives it `fallback:hole`, or `fallback:data_blocks` when a saved-data flag is also set. Its record compares each stored value of the person with the next state by the names of the previous definition. `test/modelcheck.test.js` covers its task text, body, repair, sets, records, and change cases with a fake of the call. The template check runs the sets T (`test/fixtures/template-tuning.json`), U (`template-holdout.json`), V (`template-changes.json`), and W (`template-pilot.json`) alone, with a label that starts with `tpl-`. Each request gets one call with the section "Template" as the instructions and at most one repair call (`checkTemplate`, `checkTemplateChange`). A change item of V or W changes its base file of `test/fixtures/template-bases/`. The calls of the `tpl-` labels never pass `TEMPLATE_LIMIT` (2350), and all calls never pass `LIMIT` (2889). The motion check of stage 7 runs the sets P (`test/fixtures/template-motion-tuning.json`, 10 requests) and Q (`template-motion-final.json`, 20 held-out requests) the same way: a request for motion, a change that adds motion to its base file (`base`, with the bases `orbit`, `clock`, `windmill`, and `wheel` of `test/fixtures/template-bases/`), a diagram with or without a library entry, and a still scene. `motionOf` marks each record with its kind and whether the kept file has a play line or a parts control, and `table` gives one line of `motionNumbers` for such a label. `table` gives one line of `templateNumbers` for each such label, and `test/modelcheck-template.test.js` covers the template check with a fake of the call. The check of the data family runs the sets X (`test/fixtures/template-data-tuning.json`, 10 requests) and Y (`template-data-final.json`, 20 held-out requests) the same way (`checkTemplateData`): the section "Template data" is the instructions, the task holds the sources of a searched request as the app sends them (`taskText` with `sources`), and each compile runs the host check (`dataCheckOf`). A searched item marks its excerpts: `derived` (the facts of a named record in `evidence/`, written again by hand) or `hand` (no record holds such facts). The run keeps the kept file of each request as `<id>.plan`. `table` gives one line of `dataNumbers`: the files that are clean at the first try, the invented values, the candidate false rejects, and the median first call. |
| `look.js` | The look numbers of each saved plan and each look fixture of `build.js`: `score_app` of `tools/agent_corpus/visual_score.py` on the compiled definition, as one table row for each plan. |
| `plans/` | The saved plans. |
| `test/` | The Bun tests. `test/fixtures/` holds example plans of the prototype that only the tests read, and the requests of set E of `modelcheck.js`. `test/nodes.plan` holds each component of the emitter's node table: the kit test and the device test `eachNodeOfTheTablePassesParseAndSmoke` read it. `test/lists.plan`, `test/rich.plan`, and `test/cards.plan` hold the lists and modals, the lists full to the render work limit, and the cards, charts, tabs, lessons, and questions: each has a kit test and a device test. `test/free.plan` holds the free forms that no other plan holds (an inline Row as a List template, a row Button with `enabled=`, `flip`, `move` with a test, and a line whose name is the name of an inline child): the kit tests and the device test `eachFreeFormPassesParseAndSmoke` read it. `test/shapes.plan` holds each shape kind, each outline form, `each` over a list, each kind of color, a text, and `animate` beside a timer with rounds: the kit tests and the device test `eachShapeAndColorPassesParseAndSmoke` read it. `test/draw.test.js` holds the commands, colors, and limits of `src/draw.js`. `test/budget.test.js` measures each compiled plan against each limit of Alphy and prints one table row for each plan. `test/timers.test.js` holds the timers of `plans/tea.plan` and `plans/timer-rounds.plan`, and the short plan of the device test `timerRoundsPassesParseAndSmoke`. `test/looks.plan` holds each look option and a `Grid`: `test/looks.test.js` and the device test `eachLookOptionPassesParseAndSmoke` read it. `test/fixtures/look-water-ko.plan` and `test/fixtures/look-shopping-it.plan` are two requests of the corpus with components only, in the tree of their baseline apps. `test/fixtures/mock-water-ko.plan`, `mock-units-pl.plan`, `mock-shopping-it.plan`, `mock-habits-uk.plan`, and `mock-price-filter.plan` are five requests of the corpus with parts: the device test `picturesOfEachPlan` shows each one beside the spec of its baseline app (`app/src/androidTest/assets/appfile/baseline/`). `test/entries.test.js`, `test/inputs.test.js`, `test/figures.test.js`, and `test/counter.test.js` hold the parts of a list app (section "Parts"). `test/fixtures/model/` holds the 56 plans that `gpt-6.1-terra` wrote in the model check: the final plan of each clean record of the runs `final-terra-low` (sets C and D) and `fresh-terra-low` (set E), and the first reply of four records of the runs `v5-terra-low` and `v5b-terra-low` (task P5). Its `index.json` holds the request, the dialogue flag, the set, the model, the effort, the prompt hash, and the run label of each plan. `test/modelplans.test.js` compiles each one, and the device tests read them. `test/fixtures/template/` holds the three example template files `ship.plan`, `word-parts.plan`, and `majority.plan`, the template files at their caps (`*-caps.plan`, `*-caps4.plan` with a choice of 4 options at the option cap 16, and `*-caps125.plan` with each text inside the picture at floor(1.25 × cap)), and a file of each form of the data family at its caps (`compare-caps.plan`, `table-caps.plan`, `chart-caps.plan`, and `nodata-caps.plan`, section "The data family"). The motion files of stage 7 are `orbit.plan` (a play scene with the Sun, the Earth, and the Moon), `heart.plan` (a diagram of 7 parts over the heart entry), `play-caps.plan` (a play scene at its caps), and `parts-caps.plan` (a diagram of 8 free parts at their caps): `test/motion.test.js` holds play and diagrams. `test/template-data.test.js` holds the data family. `test/template-file.test.js` compiles them, and `build.js` copies them for the device tests. `test/fixtures/template-bases/` holds the 8 base files of the change sets of the template check, and the 4 base files `orbit`, `clock`, `windmill`, and `wheel` of the motion sets. `test/template-preview.test.js` holds the preview step. `test/template-grow.test.js` checks that each longer prefix of each of these template files keeps the UI and the drawing of the shorter prefix. |

The gate of the kit is `"smoke"`: the smoke run uses each control. The report also runs each check line.
In each new state, the smoke run presses again each control that it used in an earlier state, such as a tab, when that press shows a control that it did not use yet.
So a control that only a later state enables is used too, such as the buttons of a deck over an empty list in a tab.
A press that starts an `animate` gets one more look at the screen while the value moves, at 0.37 of its time, when the end looks as the start: a full turn ends where it started, and the person sees it move.
The report gives the fault "no root line" only when no fault of a need, of a need line, or of the root line says why the screen has no root.
The example of a figure (`example=[{n: 2}, 4]`) is a check line of its part. A failed example is a fault of the need with the given values, the computed value, and the expected value.
A check line of the plan (`check("name", [steps])`) that fails is a fault of its line.
A need takes only the named options of its part (`parts.js` `OPTIONS`): each other option is a fault of the need.
A fault of a line that a part wrote names the need, such as `need b`, and not the line `b_button`.
This holds for the faults of the report, of the smoke run, and of a value that no line sets, such as `need c reads $/k, and no line sets it`.
A field of `entries` is a word, and it must not have the name of a line of the part, such as `row` or `add`.
`__proto__`, `constructor`, and `prototype` are not names of a plan: as a name, a part of a path, or a field, each one is a fault of its line.

A fault is for a thing that changes what the app does. A look option of a need that cannot apply changes only the look, so it is a note:
the kit leaves the option out, and the note names the need and the option, such as `need tip: the part draws a Slider, which shows no icon, so the kit left out icon="Percent"`.
A plan with only notes is clean. A look option of a line that the author wrote is still a fault of that line (section "Look options").
The same holds for a look option of a need that the form of its part has no place for, such as `icon=` of a counter with no `addText=` (section "Parts").
A value of the wrong type, an option that a part does not have, and a formula that reads nothing stay faults.

## Repair replies

A repair reply writes again each wrong line of a plan (`prompt.md` section "Repair").
`patchAppFile(plan, reply)` gives the plan text after the reply, as a person applies the reply by hand:

- The reply keeps only the lines of an app file (`compile.js` `LINE`: a line that starts with a head of `dsl.js` `HEADS`), so its prose and fences go.
- `delete NAME` and `delete $/NAME` remove that line. A delete of a name that the plan does not have changes nothing.
- A line with the name of an earlier line replaces it in place, and each other line goes at the end.
- The text has no delete line, so a name that a delete removed is free again for a later line and for the part of a later need.
- A need line of the plan that the kit could not read keeps its place until a reply writes that need or deletes it, so the compile gives its fault again and the plan never loses a need with no fault.
  Each other plan line that the kit could not read goes. A reply line that the kit cannot read stays at the end, so the compile names its fault.

`compileStep` uses it for the repair step of the app and of `modelcheck.js`.

## The step of the host

`compileStep({text, plan, read, previous, cap})` is one step of the host (`AppFileCompiler.kt`) in one call of the sandbox:

1. With `read` true, the kit reads the reply text with `readPlanReply`. An `outside` reply returns at once as `{kind: "outside", word}`.
2. With `plan` not `null`, `patchAppFile` merges the reply into `plan`. For a template file in `plan`, `mergeTemplate` merges it (section "Template files").
3. A merged text of more than `cap` characters gives `result: null`, and the kit does not compile it.
4. Else `result` is `compileAppFile` of the merged text with the stored app `previous`.

`previous` is `null`, or `{appFile, stateSchema, state}` with `stateSchema` and `state` as JSON text, as the host sends them. A text that is not JSON makes the step reject.
Each other result is `{kind: "plan", text, result}`, where `text` is the merged text.
`test/compile.test.js` tests each branch, and the bundle test runs the step in a fresh context.
`modelcheck.js` runs each reply through this step as the app does: with `read` true and the cap of the app (65536 characters). An outside repair reply ends the request, and a text past the cap gets the fault of the app and no repair call.

## Template files

A template file is the text that a model writes for a screen of the voice mode (`src/template.js`).
Its lines have names and a fixed order: the first line, the control, the parts of the picture, and the card.
Each line is complete by itself, so the stage can draw each line when it arrives (section "The preview step").
The kit checks each line, expands the file into an app file, and compiles that app file.
This is `test/fixtures/template/ship.plan`:

```
scene("Почему корабль не тонет?", lang="ru", palette="blue")
control = choice("Предмет", ["Корабль", "Гвоздь"])
s1 = rect(0, 110, 300, 110, "water", 0)
s2 = polygon([45, 75, 255, 75, 225, 140, 75, 140], "ink", visible=$/v == "Корабль")
s3 = text(150, 50, "Воздух внутри", "ink", 14, visible=$/v == "Корабль")
card = card("Вытеснение воды", "Полый корабль вытесняет достаточно воды, чтобы удержаться на плаву.")
```

The lines of a template file (the narration lines are optional):

- The first line names the template: `scene(TITLE, lang=CODE, palette=NAME)`, or `items(…)` or `cells(…)` with the same arguments.
  `isTemplateFile` finds the word of a ready plan of `READY` on the first line that is not empty, so it tells a template file from an app file. `TEMPLATE_HEAD` is the same pattern for the templates that the app ships.
  `lang=` is a code of two small letters. `palette=` is optional: `teal`, `green`, `blue` (the default), `amber`, `orange`, `red`, `pink`, `purple`, `indigo`, `brown`, or `graphite`.
- The control line `control = …`:
  - `scene`: `slider(LABEL, MIN, MAX, STEP, START)` with numbers, or `choice(LABEL, [OPTION, …], START)` with 2 to 6 options. With no START, the first option is the start.
  - `items`: `choice(LABEL)`. The labels of the items are its options.
  - `cells`: `slider(LABEL, 0, COUNT, 1, START)`, where COUNT is a whole number from 2 to 100. The control draws the cells.
- The parts: `s1` to `s20` of `scene`, and `i1` to `i6` of `items`. `cells` has no part line.
  - A shape is one kind of the kind registry (`src/kinds.js` `KINDS`): `circle`, `rect`, `line`, `text`, `polygon`, `path`, `arrow`, or `arc`.
    Its named properties are those of `PROPERTIES` with a prompt text (`visible=`, `stroke=`, `gradient=`, `radial=`, `glow=`, `opacity=`, and `turn=`), and `smooth=` and `closed=` of a path.
    The points of a polygon are an even count of at least 6 numbers, and of a path at least 4.
  - A library line `use(NAME, X, Y, SIZE, …)` places a ready object or an element of the library (section "The library"). It counts as one shape.
  - An item is `item(LABEL, SIGN, TEXT)`. Each item has a label of its own, because the choice shows the labels.
- The card: `card = card(TITLE, TEXT)` of `scene` and `cells`, and `card = card(TITLE)` of `items`, whose card shows the text of the chosen item.
- The steps control `control = steps(LABEL, [STEP, …], ANSWER)` of `scene`, or `steps(LABEL, ANSWER)` of `items`, is a choice of 2 to 6 steps in their order. Its start is ANSWER, by default the last step. The host walks its steps with the voice (`docs/voice-integration.md`, section "The narration of a scene").
- The parts control `control = parts(LABEL, [PART, …], START)` of `scene` is a diagram (block 2 plan, stage 7). It walks 2 to 8 parts (`BUDGET.parts`) in their order, and its start is the first part.
  - With a ready object of the library (`use(NAME, …)` of an entry with parts), each PART is a name or the id of a part of that entry, and `parts(LABEL)` walks each part of the entry in the order of the entry. A name that the entry does not have is a fault.
  - The entries have names in Russian and English only. In another language, each PART is `part(ID, NAME)` (stage 7b, R3): the picture, the stepper, and the card show NAME. A PART with no name, or no PART list, in such a language is a fault that asks for the names, so the host check of the language passes. The kit gives the names to the object as `names=`, which only the kit writes.
  - With no ready object, each PART is `part(NAME, X, Y)`: a name and a point on the part in the drawing (stage 7b, R3). The model draws the thing from x = 80 to x = 220 (`FREE.zone` of `library.js`), and each point stays in that zone. `freeShapes` places the names with `layoutOf`, as the parts of an object whose box is the zone: a point left of x = 150 gets its name in the left column, each other point in the right one. When the names need more room, the drawing moves and shrinks with that object (each shape in a group of its own), and a drawing under 0.6 of its size (`FREE.fewest`) or a clipped name is a fault that asks for shorter names. The part of the step has the accent name and an accent ring at its point. A wider ring in the surface color (`FREE.halo`) lies under the accent ring, so the ring stays visible on a part that the model paints in the accent color.
  - A name that the model gives has at most 20 characters (`BUDGET.part`): 8 names of 20 characters of two or three words fit at both sizes (`test/fixtures/template/parts-caps.plan`). Each PART has the same form. The model writes no text shape and no `label=` for a part.
  - In a stream, a diagram of a thing with no ready object shows its names after the first line that comes after the shapes, so its drawing only grows (`expandTemplate` with `preview`).
  - Sets G (6 tuning requests) and H (12 held-out requests) of `modelcheck.js` check the part names that the model gives. The row of a record shows `+names` when its control gives them.
  - The kit adds `parts=true` and `mark=$/v` to the ready object, so its accent ring stands at the part of the step. The kept file holds neither.
  - A diagram has no card line. Its card title is the part of the step with a capital first letter, and its card text is the narration line of that step.
  - The stage draws the control as a stepper (`singleChoice` with `variant: "stepper"`): the label, then one row across the width with a round previous button at the start, the count «2 из 7» in the middle, and a round next button at the end.
- The play line `play = play(LABEL, FROM, TO, SECONDS)` of `scene` moves the value `$/t` from FROM to TO in SECONDS (at most 120) and starts again, from the first state, with no press.
  The kit writes it as `animate($/t, TO, MS, FROM)` with a loop. The state keeps `t_s.on` (true while it runs) and `t_s.sp` (`slow`, `normal`, or `fast`).
  The control is a label line, then one row with a round tonal button that shows only its Pause or Play icon (variant `tonalIcon`: its text «Пауза» or «Пуск» is its accessible name and its label in the shown-screen line) and an inline choice of three speeds («Медленно», «Обычно», «Быстро») that fills the rest of the row. Slow takes 2 × SECONDS, and fast SECONDS / 2. A change of speed keeps the value.
  At least one shape reads `$/t` when a play line is present, and a shape that reads `$/t` with no play line is a fault. A play line can stand beside a steps control (M7).
  The play row shows only in a scene with no control line (stage 7, fix list 2, H4). Beside a `steps`, `parts`, `slider`, or `choice` control, the motion runs from the first state and the kit hides the play row (`visible=false`): its button keeps the loop, the smoke run does not ask that the two controls of a play need come to the screen (each other control must, also one with `animate`), and the shown-screen line has no `motion` field.
  The loop is part of the run-time, which each app source holds: the run-time text has at most 57,800 bytes (C4, `test/motion.test.js` and `test/run.test.js`), 56,599 bytes at stage 7. The library test of six formula lines for one element keeps 24 of its 33 elements clean.
  The frame of a compile and of a preview shows each moving value at the start of its run (`compile.js` `frameOf`), so each preview of the same lines is the same picture. The stage moves it from the first state of the app.
- The narration lines come after the card: `say = TEXT` with a slider, a choice, a play line alone, or no control, and `say1 = TEXT` to `sayN = TEXT`, one sentence for each step of a steps control or each part of a parts control.
  A narration line draws nothing, so it has no fault. The kit cuts its text to `BUDGET.say` (110 characters) after the last whole sentence, and keeps it as `sayK = "<JSON string>"`.
  The kit leaves out with no fault a narration line with no sentence end inside the cap, `say` beside a steps control, `sayK` beside another control, and `sayK` past the count of steps. A second line of one name goes.
- A line reads no value other than `$/v`, the value of the control, and `$/t` of the play line.
- The line `$/v = VALUE` in the place of the control holds the start value of a control that the kit left out (`kept` below).

`readTemplate` puts the lines in the fixed order, so a reply with the card before the shapes gives the same app.
A second line of one name, a line of a name that the template does not have, a line with no name, and a `delete` line are faults.

`expandTemplate` fills the ready plan of the template (section "Ready plans"). The ready plan of `scene` has the app line `app(TITLE, lang=…, preset="clean", palette=…)`, the drawing `need picture` of 300 × 300 with the parts, the control, and the card.
The control is `need v = input("slider", …)` or `need v = input("choice", …)`, and the card is `need card = card(…)`.
The drawing has its final size from the first line. An `items` drawing shows the sign and the label of the chosen item.
A `cells` drawing shows rows of at most ten cells and the caption "K / N = P%".

### Ready plans

Each template is a ready plan and a line table (`templates/`, `src/ready.js`). The engine `src/template.js` names no template and no line of one.

- The ready plan `templates/WORD.plan` holds the app file lines of the screen of the template. A slot `{{name}}` stands for a value of the template file.
- `expandTemplate` fills each slot. A plan line with a slot that has no value goes, and so does a line that is empty after the fill.
  So the plan line of a control form that the file does not use goes, and so does the card line of a file with no card.
- The line table `templates/WORD.js` is plain data: one entry for each line of the template file, in the fixed order of `readTemplate`.
  A function of a template gives what a list of arguments cannot state: the layout of a diagram, the rows and the mark of a data form, the cells of `cells`, the play line (FROM and TO differ), and the two cuts of a data label and of a nodata card. The engine holds no function of one template.
- `build.js` writes `src/plans.js` from the ready plans, as it writes `src/catalog.js`, so the bundle stays one plain script.
- `test/ready.test.js` adds the task screens `predict` and `collect` of `test/fixtures/ready/` to the registry with `register`. Each one is a ready plan and a line table with no line of engine code. The app does not ship them.

The fields of a line table:

| Field | Meaning |
| --- | --- |
| `word`, `family` | The template word, and `"data"` for a form of the data family. |
| `text` | The lines of the template in a fault, such as "control, i1 to i6 (at most 6 items), and card". |
| `head` | A named argument of the first line other than `lang=` and `palette=`, with its values, such as `kind=` of `chart`. |
| `values` | The values that a line can read, such as `$/v`, the value of the control. A value with `line` is readable only beside that line, such as `$/t` beside `play`. |
| `lines` | The entries of the lines. |
| `whole` | The whole rule: the file needs `count` lines of the entry `line` with no fault, else the whole fault `fault`. |
| `owner` | The line that gets the faults of the first line and the control alone (section "Faults by line"). |
| `voice` | A line and an argument whose text the voice says when the file has no narration line, such as the card text of `nodata`. |
| `start`, `context`, `fill`, `check` | The functions for what needs more than one line: the start of the held value, the faults that need more than one line, the slots of the whole file, and the host check of a searched form (`checkData`). |

The fields of an entry:

| Field | Meaning |
| --- | --- |
| `name`, `count` | The line name, or the names NAME1 to NAMEn of a list, such as `s1` to `s20`. |
| `forms`, `form` | The forms of the value by their call: a list of arguments, or a function for a form that a list cannot check. An argument has a kind (`text`, `number`, `whole`, `word`, or `list`), its caps, and its slot. `formFault` gives the fault of a value by its forms, and `form` is the fault of a value of no form. |
| `fault`, `check` | A function that gives the fault of a value in place of `forms`, and a function that gives a second fault after the read check. |
| `cut`, `draw` | The cut of a long text, and the tree that the kit draws, such as a shape with the kit's color for each color name. |
| `fill`, `slot`, `absent` | The slots of a list entry, the slot that holds the whole value, and the slots of an absent line. |
| `part`, `holds` | A part of the picture, and what a line of the entry holds in a frame: `"card"` or `"parts"`. |
| `held`, `sets`, `merge` | The line `$/NAME = VALUE` stands for the entry, the value that the line sets, and the place of a new line in the merge (1 after the first line, 2 after the control). A line that sets a value reads no value. |
| `owner` | The needs whose faults belong to the line, such as `v` of the control. |
| `unread`, `walk` | The forms of a control that changes nothing when no line reads its value, with the fault, and the count of steps of a walk for the narration lines. |
| `narration` | The narration lines `say` and `say1` to `sayN`. |

### Budget and colors

`BUDGET` holds the caps of the texts in characters and of the lists in items:

| Text or list | Cap |
| --- | --- |
| The title | 30 |
| The label of the control | 28 |
| The card title | 32 |
| The card text, and the text of an item | 140 |
| An option, a step, and the label of an item | 16 with 2 to 4 of them, else 12 (`optionCap`) |
| The sign of an item | 14 |
| A text in quotes of a shape | 28 |
| Options, items, and shapes | 6, 6, and 20 |
| Cells | 100 |
| A narration line (`say`) | 110 |

- A text inside the picture (the sign of an item, and a text in quotes of a shape) has no fault up to floor(1.25 × cap) (`INSIDE`). Such a text cannot change the height of the screen.
- A text outside the picture has a hard cap.
- The kit cuts a card text or an item text past its cap after the last whole sentence inside the cap (`cut`). This gives no fault and needs no model call.
  The kept line holds the cut text. In a formula, the kit cuts each text by itself.
- A sentence ends at `.`, `!`, `?`, or `…` before a space, or at `。`, `！`, or `？`, with the quote marks and brackets after it (`SENTENCE_END`). So 3.5 ends no sentence. With no sentence end inside the cap, the text is a fault of its line.
- Each other text past its cap, and each list past its cap, is a fault of its line. The fault names the cap.
- The cap of an option follows the number of options (fix list 3, H3): 16 characters for 2 to 4 options, steps, or items, and 12 for 5 or 6 (fix list 5, L2: a steps control is a choice on the stage). By the widths of the labels, two options of 16 fit one row of the phone, and three or four wrap into two rows of whole cells. The kit never cuts a label, because a line of the scene compares `$/v` with it. The fit gate holds `scene-caps4.plan`, `items-caps4.plan`, and `steps-caps4.plan`. One fault of a line names each label over its cap with its number and its length (`labelsFault`, fix list 5, L1): the options or the steps of a control, and the values of a row.

The color of a shape is a color of the kit (`core.js` `TONES`), a tone of one, a hex such as `"#336699"`, or a common color name (`COLOR_NAMES`).
A tone is a color of the kit with a step from `+1` to `+3` (lighter) or `-1` to `-3` (darker), and an optional alpha `@0` to `@100`, such as `"water+1"` or `"accent@40"`.
The common color names are `red`, `orange`, `yellow`, `green`, `blue`, `purple`, `violet`, `pink`, `brown`, `gray`, `grey`, `cyan`, `teal`, `indigo`, `amber`, `graphite`, `black`, and `white`. So each name of `palette=` is a color name too.
The kit puts the hex of a common color name in its place with no model call, and `ink` for `black` and `surface` for `white`. The case of the name has no effect.
A common color name takes a tone step and an alpha as a role does, such as `"red+2"`, `"orange-1"`, or `"red@40"` (`namedColor`, fix list 3, H1). The kit mixes the tone of the fixed hex as `draw.js` `colorOf` mixes a hex role (`toneColor`), so the person sees the named color in each palette and in both themes.
Each value of a choice, such as `$/v == "Day" ? "yellow" : "ink"`, gets the same check. Each other color is a fault of its line, and the fault names the colors of the kit.

### The library

The library (stage 6) has three registries. The grammar, the faults, the prompt text, the tests, and the review sheet come from them:

- `src/kinds.js`: the shape kinds (`KINDS`) and the shape properties (`PROPERTIES`). A kind has its arguments, its prompt text, and its draw rule.
- `src/elements.js`: the generic elements (`ELEMENTS`), in 8 groups (arrows, marks, formulas, measures, quantities, motion, containers, and structure).
  An element draws from its numbers in a box of 100 units, and each number may be a formula of `$/v`.
- `kit/library/objects/AREA/NAME.json`: the ready objects. `build.js` writes `src/catalog.js` (what the kit draws) and the asset
  `app/src/main/assets/library/catalog.json` (what the host searches) from them. The id of an object is `AREA.NAME`, and its name is `NAME`.

An object entry has `names` and `words` in Russian and English, a `line`, a `box`, and its `parts`. A part has an id, its names, and a label
point inside the box. An entry is one of two kinds:

- A vector entry has an optional ground `shadow` and its `layers`. A layer has a role of `TONES`, a tone step, an optional gradient between two
  tone steps, a shape (`path`, `circle`, or `ellipse`), and an optional outline, line, and highlight. The figures of mathematics and the water
  molecule are vector entries.
- An image entry (the objects of the world) has `image`, the file `NAME.webp` beside its JSON: a WebP file with alpha, 768 x 768 pixels, at most
  150 KB, and at most 3 MB for all images (`IMAGE`, `imageFault`). The picture fills a square of 100 units, and `box` is the part of that square
  that the picture covers. Each part also has `ring`, the radius of its mark ring, inside the box. The build copies each image to
  `app/src/main/assets/library/AREA/NAME.webp` and stops at an image past its limits. The PNG sources stay out of the repository.

The catalog has 20 image entries (11 of the human body and 9 of space) and 8 vector entries (the water molecule and 7 figures of mathematics).

A library line of a `scene`:

```
s1 = use("heart", 150, 130, 220, parts=true, mark=$/v == "Аорта" ? "aorta" : "left_ventricle")
s2 = use("thermometer", 150, 262, 60, [$/v / 100], label="Тепло")
```

- X, Y is the center, and SIZE is the longer side, from 24 (an object, `LEAST`) or 12 (an element) to 300. `SMALLEST` (56) stays a rule of the catalog review: each object reads at that size.
- `label=` writes a text under it, at most 28 characters. `visible=` and `turn=` work as for a shape. `color=` changes the color of an element.
- `parts=true` names each part of an object with a leader line: a part in the left half of the object at the left, each other part at the right.
  A name of more than 8 characters breaks into two lines at a space (`labelLines`). With named parts the kit puts the object in the middle of
  the space that the two name columns leave in the picture width, as large as that space permits and at most SIZE, at the height Y (`layoutOf`).
  `mark=` names one part in the accent role and draws an accent rim around it (on an image, an accent ring of the `ring` of the part around its
  point). The part keeps its colors, and the other parts keep theirs. An image takes no `color=`: that is a fault.
  A mark is a part id or a part name, or a formula of `$/v`. A formula becomes the values `$/KEYn` (its text in lower case) and `$/KEYm` (the id
  of its part) once, so each layer compares one value with one id. With a formula mark and no `parts=true`, only the label of the marked part shows.
- A number of an element that is a formula (not a plain number and not a plain `$/name`) becomes the value `$/KEYa`, `$/KEYb`, or `$/KEYc`
  once, so the draw rule writes a short name into each of its shapes. An element with `caps` draws at most the cap of a number, such as 12
  parts of a `fraction_bar`: a plain number above the cap is a fault that names the cap, and a formula above it draws the cap.
- The names of the parts must leave the object at least `SMALLEST` (or its SIZE when it is smaller) and fit in the height of the picture, else the line has a fault.
- A name that the library does not have is a fault that names it, and the screen compiles without that line. `compileAppFile` gives
  `misses`, the names of the miss log, and the host writes them to its release log (`AppFileCompiler.kt`, tag `AlphyLibrary`).

The kit expands a library line into one `group` of the shapes of the kind registry, so the run-time draws no catalog. The picture of an image
entry is the kind `image`, which only a library line writes: the drawing command `{type:"image", id, x, y, width, height}`, which the host draws
from its assets (`LibraryImages.kt`). The host gives `compileStep` and `previewStep` the ids of each image that it cannot open (`missing`), and
the kit treats such a name as one that the library does not have (`LIBRARY`).
`bun kit/build.js --sheet DIR` writes the review sheet of the objects and of the elements, in light and dark, as HTML pages.
The checks of `test/library.test.js` hold for each entry: unique names, at most 40 path segments (`SEGMENTS`), roles and tone steps only, and a
label point inside the box for each part, and for each image entry its file inside the limits and each mark ring inside the box. `test/fixtures/library-names.json` holds the names of spec 12.5 for the search gate of the host.

### Faults by line

Each fault has the form `NAME: message`. NAME is the name of its line, or the template word for the first line and for a line with no name.
When the compile of the expansion has faults of the kit, the kit finds the line that owns each one (`ownersOf`):

- A fault of a need belongs to the line whose entry owns that need (`owner`): `need v` to `control`, `need t` to `play`, and `need card` to `card`.
- Each other fault belongs to each part whose compile with the first line and the control alone has faults. The fault then names the part, not the need.
- In a `scene`, the faults of the first line and the control alone belong to `control` (the `owner` of its line table).

The kit leaves out each owner and compiles once more. These rules give the result:

- The kit leaves out each line with a fault, and the definition comes with the faults of these lines.
- A `scene` control that no line reads changes nothing on the screen. The kit leaves it out with the fault `control: the control changes nothing on the screen, …`.
- `kept` is the text to store. It holds each line with no fault that the kit did not leave out.
  In the place of a control that is left out or has a fault, it holds `$/v = START`.
  START is the start of the control, the label of the first item of `items`, or 0. A compile of `kept` gives no fault and the same definition.
- A whole fault gives no definition, and `expanded` is null. These faults are whole:
  - A fault of the first line.
  - No shape or item with no fault.
  - A `cells` file whose control has a fault or is absent.
  - A fault of the kit after the second compile.
- For a change, a changed line with a fault takes the stored version of that line, so a change never loses a good stored line.

`compileAppFile` of a template file gives `template` (the template word), `kept`, `expanded` (the app file of `kept`), and `leftOut` (the names of the lines that the kit left out).

### Merge by name

`mergeTemplate(stored, reply)` merges a repair reply, a change reply, or a rest reply into a template file by the name of each line:

- A line of a name that the file has replaces that line in place. A new first line replaces the first line.
- A new part goes after the last part, a new control goes after the first line, a new play line goes after the control, and each other new line goes at the end.
  The entries of the line tables give the place of each name (`part` and `merge`).
- `delete NAME` removes the line of that name. A line with no name, such as prose or a fence, goes.

### The template prompt

The sections "Template", "Template repair", "Template change", and "Template rest" of `prompt.md` are the template prompt.
"Template" is the instructions of each template call: the lines, the order, the caps, the colors, the formulas of `$/v`, and four examples.
A template call replies `outside("learn")` to a request to learn or practice with a check, a timer, a list, a form, or a game.
It replies `outside("other")` to each other request that no template shows. `readPlanReply` reads these replies.
"Template repair" asks again for each line with a fault. "Template change" asks for each changed line, and `delete NAME` for a part that goes.
A change that a template cannot hold gets `outside("other")`. A change that the screen already shows gets the one line `same()` (stage 7b, R2): `readPlanReply` gives `{kind: "same"}`, and the host keeps the screen, runs no other call, and says one host sentence (`docs/voice-integration.md`). For a new screen, the host takes `same()` as a whole fault (`template_whole`). Set S of `modelcheck.js` holds 4 changes that expect `same()`.
"Template rest" is the rule of the call for the missing lines (section "The preview step").
"Template library" is the host line of the candidates of the search (`candidatesText`): one line for each ready object, with its name, its line, and its parts.
The host puts it under the request of a template call and of a template change call: the candidates that the search gives within 150 ms, else the word match alone (`docs/voice-integration.md`). The change sets V and W of `modelcheck.js` send the candidates of the catalog the same way.
Each record of the template check holds `TEMPLATE_HASH` (row `prompt.js`).

### The data family

The forms `compare`, `table`, `chart`, and `nodata` show data side by side (`src/form-data.js`, and the ready plans and line tables `compare`, `table`, `chart`, and `nodata` of `templates/`).
The routing call of the app chooses the family (`docs/tool-integration.md`). The data call has the section "Template data" of `prompt.md` as its instructions, and the repair, change, and rest rules of the template prompt.
`DATA_HEAD` tells a file of the data family by its first line, and `DATA_HASH` names the prompt of a data record.
These are the lines of each form:

- `compare(TITLE, lang=CODE, palette=NAME)`: `control = mark(ROW)`, `t1` to `t3` = `thing(NAME, SIGN)`, `r1` to `r6` = `row(LABEL, [VALUE, …])` with one value for each thing, and the card.
- `table(…)`: `control = mark(ROW)` or `mark(ROW, COLUMN)`, `head = head([COLUMN, …])` with 2 to 4 columns, `r1` to `r12` with one value for each column after the first, and the card.
- `chart(…, kind="bar" | "line")`: `control = mark(LABEL)`, `p1` to `p8` = `point(LABEL, NUMBER)`, and the card.
- `nodata(…)`: the card only. The kit gives a nodata file with no narration line the first sentence of its card text as `say`.
- Each form has one narration line `say = TEXT`, and the control is optional.

The caps of the data family are in `DATA_BUDGET`: a title 30, a label 16, a value 12, a card title 32, a card text 140, and a narration line 110 characters.
The kit cuts a label of a row or a point past its cap. Each other text past its cap is a fault of its line, and a fault of the first line or the head is a whole fault.
The context faults are a mark that names no row, point, or column, two rows of one label, and a row with the wrong count of values.
A form expands to the native `Table` or `Chart` node, with no drawing. The mark is the state `$/v`: the node binds it with `selectedKeyBind`, and a tap on a row or a point sets it through the `action` of the node.
Each table node has `visibleRows` 8. The host shows at most 6 of them on a compact width (smallest width under 600 dp), so a form at its caps does not scroll the page on a phone. More rows, and rows that wrap, scroll inside their box. `markColumn` names the column of the marked cell.
A comparison is the line `Table(…, signs=[SIGN, …])`: its columns are the label column and the names of the things, and `signs` holds one sign for each thing (`""` for none). The run-time gives the table node `variant: "compare"` and `signs`, and the native side draws a head cell for each thing and a block for each row with bars (stage 5 look).
In the compiled rows of a table or a comparison of a data form, a value that is a whole number of 5 or more digits has a thin space between groups of three (`tableLine`). The file keeps the value as the model wrote it, and the label of a row stays as written. A `Table` of another app shows each cell as written.
The source line of a searched form is `Text("SITE · TIME", variant="source")`, a small muted line with a link icon. The card of a nodata form has `icon="Search"`.
Each line of a data form gives a frame of the preview step, so a table grows by rows.

`checkData(text, {excerpts, site, time})` is the host check of a searched form (block 2 plan, decision 3). It reads each number of a cell or a point, and it skips a first column that only counts the rows.
A form whose numbers all are in the excerpts gets the line `source = source(SITE, TIME)`. A form with one number that no excerpt holds becomes a nodata file with the reason and a narration line, and `rejected` holds that value.
`compileStep` and `previewStep` take the check as `data`. With no `data` (a request of known facts), the kit runs no check, and the result holds no `rejected`.

## The preview step

`previewStep({text, plan, previous})` gives the frame that the stage of the voice mode draws while a reply arrives: `{kind: "preview", frame}`, or `{kind: "none"}`.

- `text` is the complete lines of a reply so far. The step reads them as `readPlanReply` reads a reply, and merges them into `plan` as `compileStep` merges a reply.
- `plan` is the stored template file of a change, or `null`. `previous` is the stored app of a change as `compileStep` takes it, so a value that the person moved stays.
- `frame` is `{definition, ui, state, drawing, holds}`. The kit runs an open event of the run-time on the compiled data (`compile.js` `frameOf`).
  `state` is the state of the reply, `drawing` is its `surfaces` (`{}` with no surface), and `ui` is the screen of that state.
- `holds` tells what the frame shows: `frame` (the first line, with or without the control), `parts` (a shape, an item, or the cells of a control), `card`, or `whole` (an app file that is not a template file).
- The step runs no smoke run: it sets `settings.gate` to `"off"` for its compile, and then sets it back. It also keeps a control that no line reads yet.
- The step takes each line with a fault as the compile takes it (section "Faults by line"), with no repair.
  In a preview, only a fault of the first line and a fault of the kit after the second compile are whole. So the first line alone gives a frame, with the picture at its final size.
  An `items` frame draws nothing before its first item.
- An `outside` reply, a text with no usable first line, and each throw give `{kind: "none"}`.

The result of `compileStep` with a definition also has `frame`: `{ui, state, drawing, holds}` with `holds` `"whole"`, for a template file and for an app file.
That frame holds no copy of the definition, because the result beside it holds it. `AppFileCompiler.preview` of the app calls the preview step (`docs/tool-integration.md`).

The call for the missing lines of a template call that ended early (`docs/voice-integration.md`) sends `templateRestText({task, plan})`.
That text holds the task, the complete lines so far, and the section "Template rest", which asks only for the lines that the screen does not hold yet.

## Changes of an app

`compileAppFile(text, previous)` compiles a changed app file and gives the next state of the stored app.
`previous` is `{appFile, stateSchema, state}` as the host keeps it.
The result is `{faults, notes, definition, language, hole, blockedBySavedData, savedDataMismatch, state}`, and `frame` with a definition (section "The preview step"). `state` is `null` with a fault, and with no `previous`.
A template file adds `template`, `kept`, `expanded`, and `leftOut` (section "Template files").
`blockedBySavedData` is true for the faults of rule 4, and `savedDataMismatch` is true for the fault of rule 1. The kit sets each flag where it makes the fault, so the host reads the flags and not the texts of the faults.
The rules keep each stored value of the person, and they never move a value to another line:

1. The kit compiles `previous.appFile` again. For a template file there, it compiles the expansion of the file (section "Template files"). A fault there is the one fault `kit: the saved data does not match the app file` (`MISMATCH`).
   So are a field of the person that `previous.stateSchema` does not hold with the same schema, and a field of the schema that the state does not hold.
2. The name of a stored value comes from its key alone (`rows.js` `stable`), so a line that a change adds before it cannot change its name.
   A root that Alphy does not take as a name, such as `yaş` or the open tab `pages:tab` of a Tabs line, gets the hash name, so two roots never share a name.
3. A root of a line that stays with the same kind keeps its value. A list keeps its rows by id.
   A new column gets the value that the first `append` of the list gives a new row, such as the start value of the add form.
   - 3b. A value that still equals its start value of the previous plan is no data of the person yet: it takes the start value of the change.
     For a list, this means the same rows with the same ids and the same values, and the kit checks it before the column rules.
     So a list that still holds its start rows takes the start rows of the change whole, also when the change removes or retypes a column.
     The kit checks it before the kind too, so a value that still equals its start value can change its kind.
   - 3c. When a list takes other start rows by rule 3b, each inner value that points into its old rows starts again.
     These are the inner values of the need of the list, such as an open delete dialog, and of each need whose line reads the list, such as the place and the counts of a deck.
     A formula that reads the list counts as the list, so a deck over `$/due := filter($/words, !$known)` starts again too.
     A list that keeps its saved rows, or that gets the same start rows, changes no inner value: rule 7 applies.
   - A list with other rows keeps them. When the change has other start rows, the note `need NAME: the list keeps its N saved rows, and the start rows of the change are not used` comes with the result.
   - Start rows get the ids `r1`, `r2`, and so on by their place: one count over all lists in the order of their lines.
     So a start row that a change puts in the middle of a list moves the ids of the rows after it, and of the rows of each later list.
     Only a list that takes the start rows of the change gets the moved ids, and a list with saved rows keeps its ids.
4. These changes are faults with the words `the saved data blocks this change` (`BLOCKS`). Each one starts with its need (`need NAME: `), with the name of its line, or with `app: `. The kit names what blocks it:
   - A value of another kind, such as a number that becomes a text, or a time that becomes a number. An empty list, and a value that still equals its start value (rule 3b), can change its kind.
   - A column that the change removes or retypes in a list with at least one row.
   - A saved value that is no option or is outside the bounds of the change, which the screen would change.
   - A new value with the name of a saved value of the person.
   - More than 40 fields with the kept ones: `the app would keep more than 40 values`, with the first need that adds a field.
   - A state of more than 49152 bytes, a list with more rows than the screen of the change has room for, and an app that stops on the saved data.
5. A stored field that the change does not name stays with its schema, and `app.keep` lists it.
   Its value stays only when it is data of the person: it differs from its start value of the previous app file, or a change before kept it.
   A value that the app computed (such as `d` or the view of a sorted list) and a value at its start get the blank value of the schema (0, an empty text, false, an empty list, or a record of blanks).
   The start state (`initialState`) gives each kept field that blank value, so the host finds a kept value of the person as a difference, and an untouched app has none.
   A root that comes back later gets its kept value again, and with a blank value it takes the start value of the change (rule 3b).
6. The values that the app computes come from the changed app. A change of `animate` that runs goes on by its root, and a drag starts again.
7. The inner values of a need (the object `NAME_s`) stay when the need line is the same text and rule 3c does not apply, else they start again.
   So a timer that runs goes on to the same end of its phase when its line stays, and it stands at the start of round 1 when its line changes.
   The count of the work phases that ended is a value of its own, so it stays.

`test/change.test.js` holds a test for each rule, and two tests of each plan of `test/fixtures/model/`:

- A stored state that differs from its start in each value comes back after the same plan, and after the plan with a need more at the first line or at the last line.
- The start state of the plan takes the start values of a change with one more start row or another start value (rule 3b).

The compile step and the run-time have budgets, so a plan cannot use all the memory or time of the sandbox:

- One event of the run-time, and one look of the compile step, have 200000 steps (`core.js` `STEPS`). Each value, shape, node, and row costs steps, and a text costs one step for each 16 characters.
- One screen has at most 20000 parts, and one drawing has at most 4096 shapes.
- A line nests at most 100 levels of brackets, signs, calls, or operators. A line that shows itself is a fault.
- The host refuses a plan of more than 65536 characters before it calls the sandbox (`AppFileCompiler.kt`).

A budget that stops a plan gives a fault that names the line. An event of the run-time that passes the budget changes nothing.

Each reply of the run-time keeps the state inside the rules of the host:

- A plain value keeps its type. A value of another type, or a number that is not finite, keeps its value from before the event.
- A number stays within 2^53 - 1 either way, and a text has at most 2048 characters.
- A choice stays inside its options. An event that takes the state past 49152 bytes changes nothing.

Alphy shows at most 300 characters of a literal text of a screen, and a bound text of at most 2048 characters in full.
Outside a list row, the kit binds a literal text of 301 to 2048 characters to a value of the state, so Alphy shows it in full.
An empty literal text, an empty `Section` title, a literal text of more than 2048 characters, and a computed caption or title of more than 300 characters are faults of the line.

## Look options

A look option is a named option of a component line, such as `Text("Total", emphasis="number")`.
The run-time puts each value into the Alphy node as it is.
The compile step checks each value against the table below (`check.js` `looks`).
An unknown value, a look option of a component that does not take it, and a value that is not in the line are faults of the line.
In a line that a part wrote, an `icon` that the component cannot show, or that is not a name of the table, is a note: the kit leaves it out.
A value in the line is a literal, or a choice of literals, such as `tone=$/late ? "error" : "muted"`.
In a row of a list, a look value must not read a field of the row, such as `$done`, because one template draws each row: a choice on a value of the app is permitted.

| Option | Components | Values |
| --- | --- | --- |
| `icon` | `Button`, `IconButton` (first argument), `Metric`, `Badge`, `Notice`, `Card`, `TextField`, `NumberField`, `Select`, `DateField`, `TimeField` | Each icon of Alphy (`src/icons.js`), and the kit names `ArrowUp`, `ArrowDown`, `ArrowLeft`, `ArrowRight`, and `Edit`. A `Select` of six options or fewer is a segmented control, which shows no icon. |
| `variant` | `Button` | `filled`, `outlined`, `text`, `tonal`, `tonalIcon`. With no variant, the host decides. `selected=` gives `filled` or `tonal`. `tonalIcon` is a round button that shows only its icon, so it needs `icon=` (the play part). |
| `variant` | `Text`, `Card`, `CheckBox`, `Progress` | `muted` and `eyebrow`, `hero`, `circle`, `bar` and `dots`. |
| `size` | `Emoji` | `small`, `medium`, `large`. |
| `emphasis` | `Text` | `body`, `title`, `headline`, `label`, `number`, `display`. |
| `emphasis` | `Metric` | `display`, `number`. |
| `align` | `Text`, `Row` | `start`, `center`, `end`. A `Row` of no wrap is centered across its height by default. |
| `tone` | `Text`, `Metric`, `Badge`, `Notice` | `muted`, `accent`, `success`, `warning`, `error`. |
| `gap` | `Row`, `Column` | `none`, `small`, `medium`, `large`. The root `Column` is the column of the app: its gap is the gap of the app, and its padding is `medium`. |
| `columns` | `Grid` | 1, 2, 3, 4. The default is 2. |

`Row(children, max=360)` gives the row a largest width in dp, the `maxWidth` of its Alphy layout. A row that is narrower than its column stands at its start.
`fit=true` keeps a node at the width of its content in a column, where Alphy makes it fill the width (`fillWidth: false` of its Alphy layout).
`Emoji(sign, size=)` takes a sign, a state value, or a field of a list row.
`label=` is its text for a screen reader. Without it, the text in a list row is the kit caption of its line, such as "item Emoji", because one template draws all rows. The `entries` part gives it the title of the list.
`Segmented`, `Select`, and `Choices` take `options` as a list or as a map of a text value to its label, such as `{asc: "Low to high", desc: "High to low"}`.
The state keeps the value, and the control shows the label in the order of the plan.
A map key that is a number is a fault, because JavaScript puts such keys in number order.
The value text inside a `Ring` has the `display` emphasis.
The `number` and `display` emphases of a `Text` are display sizes of the host (`NativeToolUi.kt` `textStyle`), so a short value in a list row takes `label` or `title`.

## Parts

A part changes one need line into component, state, and formula lines (`src/parts.js`).
The parts use the look of the baseline apps: a hero card first, rows with an emoji and a muted second line, and at most one filled button in each group.
A hero card holds the main element, at most one filled button, and at most one text button: the card of a timer with rounds holds its filled Start or Pause and its Reset text button. A field and an outlined or tonal control stand under the card, because their outline shows no contrast on a hero card in the dark theme.

The captions that a part writes by itself are in the language of `lang=` of the app line (`src/words.js`): "Add", "Remove", "Less", "More", "Reset", and the others of its table.
A language outside the table gets the English words and one note of the report that names each option of the need that took an English caption.
The host draws its own words where it has them: the empty text of a list, the empty text of a chart, the close button of a sheet, and the move menu of a reordered list. Each one is in the language of the app (`NativeToolUi.kt` `miniAppString`).

A figure prints its number with `amount(x, n)`: at most `n` decimals, no zeros at the end, and the digit groups and the decimal sign of the language of the app ("1.234,5" in `de`, "1,234.5" in `en`, "12 345,5" in `pl` with a no-break space). In `es`, `pl`, and `pt` a number of four digits has no group ("1234,5").
`amount` gives a value that is not a number, such as a text that a format function printed, as it is.
`fixed`, `money`, and `percent` use the same signs. `money` has 2 decimals, and none in `ja` and `ko`, because the yen and the won have no minor unit (L1 item 5). `decimals=` on a money figure gives its decimals in each language. A plain `text(x)` and a `Metric` of a state value print the number as it is, so a year belongs in `text()`, not in a figure.
A text that a format function printed is for a person: do not read it back as a number. `number(fixed(1234.5))` is 0 in `en`, because "1,234.50" has a group sign.

| Part | Look options | Example |
| --- | --- | --- |
| `entries` | `icon` (an emoji for each row, or `$field`), `countText` (the caption of the hero count), `edit` (a choice of a row is a control, not a label), `icons` (the icon of a field of the add form), `tones` (the tone of a value of `show=`), `total` (the word between the shown rows and all rows of the hero count) | `need items = entries("Prodotti", fields={nome: "text", corsia: ["Frutta", "Forno"], preso: "check"}, icon=$segno, show={segno: $corsia == "Forno" ? "🥖" : "🍎"}, countText="Da comprare", icons={nome: "Cart"})` |
| `input` | `icon` (the icon of the field), a map in `options=`, and the kind `range` | `need price = input("range", "Price", [100, 1500], min=0, max=2000, step=50)` |
| `figure` | `icon` (an emoji, a formula that gives one, or a host icon), `unit` (a word after the number), `note` (a muted text), `goal` (a bar to the goal), `goalText` (the caption of the bar), `decimals` (the decimals of a number, money, or percent figure) | `need today = figure("number", "Today", $/ml, icon="💧", unit="ml", goal=2000, goalText="Of the goal", note="250 ml for each cup")` |
| `counter` | `lessText`, `moreText`, `addText` (one button that adds the step), `icon` (the icon of that button), `resetText` | `need ml = counter("Water (ml)", 0, step=250, addText="Add a cup", icon="Cup")` |
| `card` | `eyebrow` (a small text over the title), `icon` (the icon of the card) | `need legend = card("Legend", "Teal is the Moon", icon="Layers")` |

- `entries`:
  - The row is the emoji, the first text field as the title with each further text field as a muted line under it, the value cells as small labels, the check marks, and the remove button.
  - In a row with one text field, a choice that the row does not change is the muted line under the title.
  - In a row that the person does not edit, a `number`, `money`, or `percent` cell prints with `amount(x, 2)` (L3 decision 2): the typed value with the decimal sign and the digit groups of the language, at most 2 decimals, and no forced decimals or percent sign.
    So a money value of 7.5 is "7,5" in `es` and "7.5" in `en`, 180 is "180", and a percent value of 12.5 is "12,5" in `es`. A date, a time, a count, and a choice stay as they are.
    Each printed cell is one more field of the row, which the run-time computes, so the list binds a view. A row that its printed cells would take past the 12 fields of a row of Alphy keeps the raw numbers.
  - With `group=$field`, Alphy shows the value of the field as the heading of each group, so a row that cannot change the field leaves it out (L2 decision 2).
    A check mark, a switch, a count, and a picture stay, and so does each control of an editable row (`edit=true`). The add form and the data keep the field.
    A row with no other field than the field of its group (and the field of its emoji) keeps it, so the row is not empty.
  - More than two check marks are circles in a second row under the other cells, each named by `labels=` or its field. The host draws each row of a list on a card of its own, so the row has no card of the part.
  - `icon="🛒"` gives each row the same sign. An icon name in its place, such as `icon="Cart"`, is a note, and the rows have no sign. `icon=$x` reads `x` from a field of `fields=` or a name of `show=`. A row that the add form makes gets its sign the same way: `show=` computes it from the new row, or the person types the field.
  - The text of the emoji for a screen reader is the title of the list.
  - The add form is a column of its fields and a filled add button with a plus. A press with an empty first text field adds nothing.
  - A text field of the form is a live input of the host (`inputMode: "live"`): the typed text goes into the state while the person types.
    To add a row, a person types, finishes the edit (the action key of the keyboard or the host's finish button under the field), and then presses the add button.
    The host refuses a press before the finish and shows a notice, and the baseline apps of the agent loop have the same steps. No step adds a row with one press after the typing.
  - A plan with a list and no figure, progress, drawing, timer, or model starts with a hero card of the count of the shown rows of its first list. `countText=` is its caption, else the title of the list. `icon=` with a sign is its large emoji.
    `total="of"` shows all rows too, such as "5 of 12". `total=` on a list with no hero count is a note, and the kit leaves it out.
  - `max=5` takes at most 5 rows (P4). The value is a whole number from 1 to 100 in the line (the most rows of a list of Alphy, `src/rows.js` `MAX_ROWS`), and the start rows fit in it. Each other value is a fault of the need.
    At the limit the add form, or the button that opens its sheet, gives way to a muted text in the language of the app ("The list is full"), and the add action adds no row. A remove makes room, and the form shows again.
    The hero count then shows all rows against the limit, such as "2 / 5", with the caption of `countText=`. `total=` beside `max=` is a note, and the kit leaves it out. With `add=false` the limit shows only in the hero count.
    A list that starts full with `remove=false` never shows its form, so the smoke run gives a fault.
  - `icons={nome: "Cart"}` gives a field of the add form its icon. A text, number, count, date, or time field shows an icon, and a choice of more than six options does too. Each other field, a name that is not a field, and each name with `add=false` are notes, and the kit leaves the icon out.
  - `tones={streak: "accent"}` gives the badge of a value of `show=` its tone. A name that `show=` does not give, or the sign of the row, is a note, and the kit leaves it out.
  - A value of `show=` prints with `amount(x, 2)`: a number in the language of the app with at most 2 decimals, and a text as it is, such as one that `fixed()` or `money()` of the plan printed. A name of `labels=` stands before the value, such as "Серія днів: 0" (L1 item 2).
  - In a row with values of `show=`, the title and the value cells are one group that wraps: a cell that does not fit beside the title goes under it, with the width of its content. The emoji, the check marks, and the remove button stay at the ends of the line. A row with no value of `show=` keeps its title with the rest of the line.
    The texts of a title of two lines there have `fit=true`, so the title is as wide as its text, and a value that fits stands beside it.
  - In a row with no value of `show=`, the value cells (each field that is not a text, a check mark, or a count) are one group with no weight after the title, at most 120 dp wide (L2 decision 1).
    Alphy gives each text with no weight in a row of no wrap a share of the row (`NativeToolUi.kt` `rowTextTypes`), so a date beside a title and a number got a third of the line and broke inside its value.
    The group keeps the width of its cells, so each value stays on one line, and the title takes the rest of the line and wraps there. A date and a time fit on one line of the group, and more cells wrap inside it.
  - An editable row (`edit=true`) is one line that wraps (L1 item 1). A text field there is as wide as the host lets it be (up to 300 dp), so on a phone it has a line of its own. The first text field shares its line only with the emoji, and each other control keeps the width of its content.
    A count of a row is one group of a minus button, a number field with no stepper of its own, and a plus button, on a line of its own.
- `input`: `options={value: "Label"}` keeps the value in the state and shows the label. `icon=` goes to a text, number, date, or time field and to a choice of more than six options.
  A single choice of more than six options is a select, and a choice of six options or fewer is a segmented control.
  Alphy measures the labels at the width that the control gets (`NativeToolUi.kt` `NativeChoices`, L1 item 4). When the widest label and the check of a selected option do not fit the share of one option, the options are cells that wrap.
  Each cell shows its label whole on one line, and the selected cell has the colors of a selected segment. The title of the choice stands over the cells, as over a segmented row. So one plan can show cells on a phone and a segmented row on a wide screen.
  The host draws a segmented control with no icon. Each kind of input takes `icon=`: on a segmented control, a slider, a range slider, a multi-choice, or a toggle it is a note, and the kit leaves it out.
  Each other option of an input is only for its own kinds: `min=` on a text field is a fault.
  The kinds are `number`, `money`, `percent`, `count`, `range`, `slider`, `choice`, `choices`, `toggle`, `text`, `date`, and `time`. Each other kind, such as `dial`, is a fault of the need.
  The kind `slider` writes `Slider(label, $/x, min=, max=, step=)` for a number that the person explores, such as an angle that a drawing reads (B1). It needs `min=` and `max=`: without one, the need has the fault `need x: a slider needs min= and max=`.
  A `percent` input with `min=` and `max=` is a `Slider` too. Each other number kind is a `NumberField`, also with bounds.
  The kind `range` writes `RangeSlider(label, $/x, min=, max=, step=)`: one state value of two numbers in order, the low and the high (`$/price[0]`, `$/price[1]`).
  Each `TextField` and each `Slider` is a live input of the host (`inputMode: "live"`), also in a row and in a bottom sheet. The host sends its value as a `liveInput` event while the person types or drags, and `handle` takes only that event for it.
  A slider finishes its edit when the drag ends. A text field finishes it with the action key of the keyboard or the finish button. Each other control of the kit, the `RangeSlider` and the `NumberField` included, sends an `action`.
- `figure`: the main figure is a hero card of the emoji, the metric with the display emphasis, the bar of `goal=`, and the muted `note=`.
  The caption of the bar is `goalText=`, else the title of the figure. `unit=` follows the shown number ("2,000 ml"), and the value of the figure stays a number.
  The other figures of one place are a set. A place is one group of a `steps` or `tabs` need, or the screen for the needs that no such need groups. A figure never leaves its place, and a figure that two groups name is a metric in each of them.
  - Two figures of a set that stand one after the other are a grid of two metrics, each with its `icon=`. Two figures with another need between them stay two metrics.
  - Three figures or more of a set are rows of one size: the title at the left and the value at the right. `icon=` is a small sign at the left of the title: an emoji, or an icon of Alphy with no action.
    A row of a figure is at most 360 dp wide (`Row(..., max=360)`), as a counter is, so on a wide screen its value stays near its title (L1 item 8). The content of a phone is about 346 dp wide, so there the row fills the line.
  - On a figure that is not the main one, an emoji where a metric needs an icon of Alphy and a name that Alphy does not have are notes, and the kit leaves the icon out.
  - One figure of a set is a metric.
  `note=`, `goal=`, and `goalText=` show only on the main figure: on another figure they are a note, and the kit leaves them out. `goalText=` with no `goal=` is a note too.
  `decimals=` is the number of decimals of a `number`, `money`, or `percent` figure. A `text` figure shows its value as it is, so `decimals=` on it is a note.
- `counter`: a section with the title of the counter. It holds an outlined less button, the value with the display emphasis, and a filled more button.
  The three are one row of at most 360 dp (`Row(..., max=360)`), so on a wide screen the buttons stay beside the value (L1 item 8). The content of a phone is about 346 dp wide, so there the row fills the line.
  With `addText=`, the counter is one filled button that adds the step and a text button that resets the value. The plan shows the value elsewhere, such as in a figure.
  `lessText=` and `moreText=` with `addText=` are a note, and so are `icon=` and `resetText=` without it: the kit leaves them out.
- `timer`: the card of a timer is a hero card, also the host's own countdown. A timer with rounds has a filled Start with a play icon, a filled Pause with a pause icon while it runs, a Reset text button, and a muted phase text.
  Its bar is a surface of 1024 x 64 that the bar fills: a track in the muted tone of the theme, a stop dot near its end, and the share of the phase that passed.
  A rest phase is between two rounds: after the work phase of the last round the timer is over. 5 rounds of 45 s with 15 s of rest take 5 x 45 + 4 x 15 = 285 s (`core.js` `tick`).
  A timer of one round (`rounds=1` or no `rounds=`) and a timer with `cycle=true` also run the rest phase after the work phase of the last round, for a cycle of two phases such as breathing in and out:
  `timer(4, rest=6)` ends at 10 s, and `timer(4, rounds=5, rest=6, cycle=true)` ends at 5 x (4 + 6) = 50 s (K2).
- `deck`: the front of a card is a centered text with the display emphasis, and the back is a centered text with the headline emphasis. Yes is filled with a check, No is outlined, and the two counts are a grid of two.
  The hero card holds the card and its Show button. The row of Yes and No stands under the card.
  With `answer=`, the field, the check button, the notice, and the next button stand under the card.
  A deck of pages (no `back=`) keeps a heading for its front, because a page can be a long text. Its Back and Next row stands under the card.
  At the end of a deck of pages, Next goes and Back stays, so a person can go back from the end. A deck of one row has a Back button that works.
  Over an empty list each form of deck shows only a muted centered text: the `emptyText=` of the list need, else "No records yet" in the language of the app (L1 item 3).
  It shows no end card, no score or counts, and no button. The deck is written again when the `emptyText=` of its list changes.
- `steps`: the note at the end has the success tone. `question`: its notice has the success tone for a right answer and the warning tone for a wrong one.
- `lesson`: a lesson of at most 12 steps shows its count only in its dots, where the host writes "Step: 1 / 3". A longer lesson has a bar and a count line over it.
- `chart`: the kit writes the title once, as the label of the chart, with no section around it. The one series of the chart has the title as its text too,
  because the catalog of Alphy needs a series text of 1 to 100 characters (`ToolUiCatalog.kt`, `chart_series_entry`).
  Alphy draws the label as the heading of the chart (`NativeToolUi.kt`, `showsToolHeading`) and the text of each series as a caption under the chart in the color of the series.
  It draws no caption for one series whose text equals the heading (`NativeChart`, L3 decision 1), so a person sees the title once.
- `card`: `icon=` is the icon of the card, and the body text is muted.
- `drawing` and `model3d`: a plan with no figure or progress has its first drawing or model as the main element, unless the author wrote `root`. The main drawing is in a hero card, and the main model card is a hero card that holds only the model.
  The part chips, the turn buttons, and the explode button of the main model stand under its card, in this order.
  The stage of a model is the muted tone of the theme. A part chip of a model is tonal, and filled while it is the selected part. The explode button is outlined.
  The line of a part chip has the part id in its name, such as `rover_part_wheels`. An id that is not a word, such as `"wheel-l"`, gives the place of the chip, such as `rover_part_2`.
  The color of a shape or a body is a color name of the kit (`core.js` `TONES`) or a hex such as `"#336699"`. Each other color of a line is a fault of the line.
  The color `surface` is the surface role of the theme (`NativeToolUi.kt` `drawingRoles`): white in a light theme and near black in a dark one. The host keeps each ink role and the surface role at a contrast of 4.5 or more.
  A text takes a color that reads on the last filled disk, box, or polygon under its anchor (`draw.js` `commandsOf`, L1 item 6):
  - On a shape in a hex color, the text is black or white by the luminance of the shape, so the contrast is 4.5 or more.
  - On a shape in an ink role (`ink`, `accent`, `success`, `warning`, `error`), the text is in the surface role.
  - On a muted shape, the text is in the default ink. The host draws a muted shape as the muted ink at the alpha `MUTED_SHAPE_ALPHA` (56), and only the default ink keeps 4.5 on that tint on each canvas.
  - On a shape in `surface`, or on no shape, the text keeps its color, and a text in `surface` takes the default ink.
  - A point is inside a polygon by the non-zero rule, the fill rule of the host path. A line and an outline cover no text.
- The value of an option has its type. `sheet=`, `edit=`, `add=`, `remove=`, `hidden=`, `reorder=`, and `cycle=` are `true` or `false` in the line, because a part reads them once when it builds the screen. `step=` and `decimals=` are numbers or formulas. A value of another type is a fault of the need.

## Look rules

Kit tests check the look rules over the nine saved plans and the look fixtures (`LOOK_FIXTURES` of `build.js`).
`bun kit/look.js` prints the numbers of the first three rules with `score_app` of `tools/agent_corpus/visual_score.py`.

1. `look: each saved plan has its main element in the first two nodes` (`test/lookrules.test.js`): a hero card, a large ring, dots, a canvas, a large emoji, or a display text.
2. `look: no saved plan has more than two typed fields on the first screen` (`test/lookrules.test.js`).
3. `look: no saved plan has a typed field in a row template` (`test/lookrules.test.js`).
4. A plan with only a list gets a hero card with its count: `entries: a list that a steps or tabs need groups gets no hero count at the root, and the first list that no holder groups gets it` (`test/entries.test.js`).
5. The figures of one place (`test/figures.test.js`): `figures: two figures after the main one are a grid of two metrics with their icons, and three or more are rows of one size`, `figures: the figures of one group of steps or tabs are a set of their own, and a figure never leaves its group`, and `figures: one figure beside the main one, or two that do not stand one after the other, stay metrics`.
6. `look: the group of the main action has exactly one filled button and each other group at most one, on the first screen and after the press of the main action` (`test/lookrules.test.js`): by the rule of the host (`NativeToolUi.kt` `secondaryButtons`).
   While a timer runs, its filled Pause is the one filled button of the group of Start. After Show of a deck, its hero card shows no button, and the filled Yes is in the row under the card.
   A timer whose time is up shows only its Reset text button, so its group has no filled button. One event does not reach that state.
7. `look: no line of a saved plan has the muted role, because Alphy draws a muted shape as a light tint (MUTED_SHAPE_ALPHA)` (`test/lookrules.test.js`): a line or a stroked shape uses the role `ink` (`SurfaceDrawing.kt` `MUTED_SHAPE_ALPHA`). A filled shape, such as a stage or a track, can be muted.
8. `look: a hero card holds no outlined or tonal button` (`test/lookrules.test.js`): on the first screen and after the press of the main action. A field, a segmented control, a select, a multi-choice, and an outlined, tonal, or elevated button stand under the card.

## Commands

Run each command from the repository root.
The kit has no dependencies and no install step.

- `bun test kit` runs the tests. The summary of the output shows `0 fail`.
- `bun kit/build.js` writes `src/icons.js` from the host's sources, the sandbox bundle `app/src/main/assets/appfile/kit.js`, and the prompt asset `app/src/main/assets/appfile/prompt.json`, and copies `plans/*.plan`, the test plans `nodes`, `looks`, `lists`, `rich`, `cards`, `free`, `shapes`, `bytes`, and `model`, and the fixtures `water-ko`, `units-pl`, `shopping`, `habits`, `price-filter`, `look-water-ko`, `look-shopping-it`, `look-deck-es`, `look-guide-es`, and the five `mock-*` plans to `app/src/androidTest/assets/appfile/` for the device tests. It copies the model plans of `test/fixtures/model/` and their `index.json` to `app/src/androidTest/assets/appfile/model/`, and the template files of `test/fixtures/template/` to `app/src/androidTest/assets/appfile/template/`.
- `bun kit/build.js --check` writes nothing. It exits 1 when one of these files differs from what the build writes, and it prints the version of Bun that ran.

The minifier of Bun makes the run-time text, so the bundle depends on the version of Bun.
Bun 1.3.9 wrote the checked-in bundle, and the first line of the bundle names that version.
With a different version, `--check` can fail with no change in `kit/src`.
The directory of the checkout does not change the bundle: the build gives each module to the minifier at a fixed path, such as `/kit/src/run.js`, because Bun 1.3.9 gives other short names for other paths.
The kit test `build (K1 fix 1)` builds the outputs from a copy of the sources at another path and compares them.

The run-time text leaves out each message that only the compile step reads, such as the text of a fault.
Each module of the run-time puts such a text behind its line `const MESSAGES = true;`.
The build of the run-time text takes that line out and defines `MESSAGES` as false, so the minifier drops the text.
The kit tests and the compile step keep each text.

## Origin

The kit comes from the isolated prototype of the app file design (`alphy-dsl-prototype`, outside this repository).
`src/dsl.js` and `src/parts.js` are copies of the prototype files.
`src/core.js` and `src/check.js` are the two halves of the prototype's `runtime.js`.
The tests are a port of the prototype's `test.js` without the blocks that need its prompts, generation, or agent loop.
The plan `docs/superpowers/plans/2026-11-18-app-file-kit.md` names the source of each saved plan.
