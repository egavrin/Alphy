# Ready plans: one plan for an app and a voice screen

Date: 2026-11-28. Base: `aab9e68` (Alpha 24).

## 1. Intent

The owner asked for three things on 2026-11-28:

1. One plan for each screen. The voice mode selects a ready plan and does not use a different mechanism.
2. More templates for the voice mode, with a larger picture, less text, and no control that is always there.
3. Pictures from a search in a voice talk.

He approved this order: the refactor first, then stage A (the large picture), then stage B (ready plans as the base of task templates).
Pictures from a search (stage C) are not part of this design.

## 2. Present state

- An app of the chat is an app file: `app(...)`, state lines, and `need NAME = KIND(...)` lines (`kit/prompt.md`, section "System").
- A voice screen is a template file: a first line (`scene`, `items`, `cells`, `compare`, `table`, `chart`, or `nodata`) and named lines.
  `kit/src/template.js` reads it, and `expandTemplate` writes an app file that the same compile builds.
- The code of each template is a JS module (`kit/src/form-*.js`, about 690 lines). `template.js` also holds rules of one template each
  (`word === "items"`, `word === "scene"`, `read.word === "cells"`, `word === "nodata"`).
- The set of line names is fixed in `template.js`: the first line, `control`, `play`, the parts, `card`, `source`, and the narration.
  A line reads only `$/v` and `$/t`.
- So a new template needs kit code, and a template cannot use the parts of an app file that hold a check or more than one value
  (`question`, `counter`, `button`). A request to practice gets `outside("learn")` and leaves the fast path.
- Measured on the phone picture of a diagram: the canvas is 32% of the screen, the thing itself is 7%, and 14% is empty under the card.

## 3. Goals

- G1. Each template is a ready plan: a plan file in the grammar of an app file, with named slots. The screen that a person gets is in that file.
- G2. One engine reads a reply, merges it by name, fills the ready plan, and keeps the lines with no fault. It names no template.
- G3. A new template whose slots have known types is a ready plan file and a line table. It needs no change of the engine.
- G4. A ready plan can use each part of an app file, more than one named line beyond the control and the card, and more than one value.
- G5. The behavior of the seven templates of Alpha 24 stays the same after the refactor.

Non-goals of the refactor:

- No change of the reply grammar. The model still writes named lines, because the stage draws a reply line by line.
- No change of `kit/prompt.md`, so no paid model run.
- No change of the host (Kotlin), of the run-time, or of a stored app.

Anti-goals:

- A new language for slot types with its own parser.
- A second mechanism beside the engine for new templates.
- A refactor that makes the voice screen slower or changes one fault text of a repair call.

## 4. Approaches

| Approach | What it gives | Why not, or why |
| --- | --- | --- |
| Keep the form modules and add a module for each new template | The least work now | Each template stays code. It keeps the split that the owner named. |
| One grammar: a template is one `need` of an app file | One prompt and one reader | A reply then has one long line. The stage cannot draw it line by line, and a repair cannot name a line. The first picture came at 3.3 s only with named lines. |
| Ready plan files and one engine with a line table (selected) | G1 to G5 | The checks of a diagram and of the data rows stay code, as functions of their template. |

The owner selected the third approach on 2026-11-28 («давай тогда начнем с рефакторинга»).

## 5. The refactor

### 5.1 A ready plan

- A ready plan is a file `kit/templates/WORD.plan` for each template word.
- Its lines are lines of an app file. A slot is `{{name}}`, as the placeholders of `kit/prompt.md`.
- With each slot filled, the text is the app file that `expandTemplate` gives today for the same reply.
- A plan line with a slot that has no value goes. So a reply with no card gives a screen with no card.
- The values that a reply line may read are the values that the ready plan names, such as `$/v` and `$/t`.

### 5.2 The line table

Each template has one table beside its ready plan. The table states, for each line of a reply:

- its name or its name pattern (`control`, `s1` to `s20`, `ask`), and whether it is one line or a list of parts;
- its place in the fixed order, and its place for a merge of a new line;
- the forms of its value: the call names, and for each argument a type with a cap (a text, a long text that the kit cuts, a number, a list of labels, a shape, a formula of the readable values);
- the slot or slots that it fills, and the app line that owns its faults after the compile (`need v` belongs to `control`);
- what a frame holds when the line is present (`frame`, `parts`, or `card`), and whether the screen needs it (a whole fault).

A template can give a function for a value that needs more than its own line: the layout of a diagram, the rows of a table, the cells of `cells`.
Such a function is part of its template and not of the engine.

### 5.3 The engine

`template.js` keeps its exports (`isTemplateFile`, `readTemplate`, `mergeTemplate`, `expandTemplate`, `buildTemplate`, `checkData`, `BUDGET`, `TEMPLATE_HEAD`), so the compile, the build, and the host stay as they are.
The engine reads the line table of the first word of a file. It holds no rule of one template.
`TEMPLATE_HEAD` and `DATA_HEAD` come from the registry of the ready plans, with the same text as today.

### 5.4 The proof of G3 and G4

Two task screens of the owner's target pictures are the proof. Both compile clean at `aab9e68` as app files written by hand (`evidence/ready-plans/tasks/`):

- `predict` (picture 03): a picture, a slider, a question with three options, and a button that runs the experiment.
- `collect` (picture 01): a picture of places and pieces, a counter, a check button, and a text with the result.

The refactor adds both as ready plans of the tests only (`kit/test/fixtures/ready/`). No prompt names them, and the app does not ship them.
Each one needs named lines beyond the control and the card, and values beyond `$/v` and `$/t`.

### 5.5 The bundle

The sandbox bundle is one plain script (`kit/build.js`). A ready plan reaches it as a module that the build writes from the plan files, as `src/catalog.js` comes from the library entries.

## 6. Stage A: the large picture

Requirements (the look needs the owner's eye on a drawn sample before the build):

- A1. The picture takes the most of the stage. No empty zone stays under the screen.
- A2. The card is optional. A screen whose voice says the text has no card. A diagram shows the name of the present part in the picture.
- A3. The control is optional. A walk by voice has a compact stepper. A scene with no control has no row.
- A4. The pause and the speeds of a moving scene are reachable beside a control (open item of Alpha 24).
- A5. The template prompt changes, so the template check runs again within the paid budget of a stage.
- A6. Real pictures at the phone width and at the fold width before the release.

Unresolved decision: the layout of the phone and of the fold. It blocks the build of stage A. The owner selects it from a drawn sample.

## 7. Stage B: task templates

- B1. The first task templates come from the owner's four pictures: collect a quantity, predict and test, compare forms, balance by rules.
- B2. Each one is a ready plan and a line table, with a section of the template prompt and a set of the model check.
- B3. A request to practice stays on the fast path. The voice reads the check result from the shown screen.

The owner decided on 2026-11-28: the first task templates are "collect a quantity" (picture 01) and "predict, then test" (picture 03), with the layout of the drawn sample (`evidence/ready-plans/stage-a/sample-tasks-phone.png`, `sample-tasks-fold.png`).
"Compare forms" (picture 02) and "balance by rules" (picture 04) need parts that the kit does not have (pieces that a person moves, and tabs of forms), so they come later.

Implementation choice: the feedback of a task is its caption, and the voice says a caption that a press changed. One host rule then serves each task template. A feedback inside a part of its own (the banner of a `question`) would need a rule for each part.

The plan (`docs/superpowers/plans/2026-11-28-ready-plans.md`, section "Stage B") holds the requirements B1 to B8.

## 8. Acceptance of the refactor

- The golden check `evidence/ready-plans/golden.js check` gives `0 differ`: about 2,100 template texts of the model check and the fixtures, as a new reply, as a merge, as each prefix of a stream, as a change, and with the host check of data.
- Each test of `kit/test` passes. A test changes only its imports, or its statement about the module structure.
- `app/src/main/assets/appfile/prompt.json` stays byte-equal, and the hashes stay `69759b168bc3` (plan), `1ba7d44bfba4` (template), and `ee9f0871b78c` (data).
- `template.js` names no template word.
- The two task screens of 5.4 compile, stream, merge, and repair by name through the engine, with no line of engine code for them.
- The device tests `AppFileCallWorkflowTest`, `AppFileChangeWorkflowTest`, and `StageChangeWorkflowTest` pass with the new bundle.

## 9. Left to the builder

- The form of the line table (plain JS data) and the names of its fields.
- Where the functions of a template live, and which of the present helpers they reuse.
- The split of the engine into functions.
