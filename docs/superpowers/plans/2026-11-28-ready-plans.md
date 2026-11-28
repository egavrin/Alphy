# Ready plans: build plan

**Goal:** each voice template is a ready plan (an app file with slots) that one engine fills, and then the voice screen gets a large picture and task templates.

**Spec:** `docs/superpowers/specs/2026-11-28-ready-plans-design.md`.

**Base:** `aab9e68` (Alpha 24). **Worktree:** this one (`worktree-app-file-parts`).

Each job is one large unit for one Opus subagent: its tests, its code, its documents, its test run, and its fixes.
The main session reviews each diff, runs the commit gates (Slopo, architecture scan), and makes each commit. No job commits or pushes.

## Global constraints

- No change of `kit/prompt.md` in job R. The prompt hashes stay `69759b168bc3` (plan), `1ba7d44bfba4` (template), and `ee9f0871b78c` (data).
- No paid model call in job R. No job runs `kit/modelcheck.js` without a budget that the main session states.
- No job edits `evidence/ready-plans/golden.js`, `corpus.json`, or `golden.ndjson`, and no job runs `golden.js record`.
- Device tests run only on `emulator-5594`. No install on a physical phone.
- Each `gradlew` call has the prefix `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools JAVA_HOME=/opt/homebrew/opt/openjdk@17`.
- No compatibility code for earlier Alpha versions (`AGENTS.md`). No stored data of a person is deleted.
- Documents and comments follow the controlled English of the repository documents.

## Job R: the refactor (no visible change)

Requirements:

- R1. Each of the seven templates has a ready plan file `kit/templates/WORD.plan`: the app-file lines of its screen, with `{{name}}` slots.
- R2. Each template has one line table beside its ready plan (spec 5.2): plain JS data, and functions only for a value that needs more than its own line.
- R3. `kit/src/template.js` is the engine. It names no template word and no line name of one template. The registry of ready plans gives `TEMPLATE_HEAD` and `DATA_HEAD` with the text of today.
- R4. The exports of `template.js` stay, so `compile.js`, `build.js`, `prompt.js`, `modelcheck.js`, and the host stay as they are.
- R5. A ready plan reaches the sandbox bundle as a module that `kit/build.js` writes from the plan files (the way of `src/catalog.js`). The bundle stays one plain script.
- R6. The task screens `predict` and `collect` (`evidence/ready-plans/tasks/*.plan` show their app files) are ready plans of the tests in `kit/test/fixtures/ready/`. Each one has a reply file in named lines. They need no line of engine code.
- R7. `kit/README.md` describes ready plans and the line table in place of the form modules. No document names a module that is gone.

Tests (the job writes the new ones first):

- T1. `bun evidence/ready-plans/golden.js check` prints `0 differ` (39,863 cases).
- T2. `bun test kit/test` passes. A present test changes only its imports, or its statement about the module structure (`forms.test.js`).
- T3. New test `kit/test/ready.test.js`:
  - Each ready plan of `kit/templates` with the slots of its example reply is an app file that compiles clean.
  - `predict` and `collect`: the reply compiles clean. Each prefix of its lines gives a frame, and a longer prefix keeps the UI and the drawing of a shorter one. A reply with one wrong line keeps each other line and names the wrong line in its fault. A repair reply replaces a line by its name. The kept file compiles to the same definition.
  - A line that reads a value that its ready plan does not name is a fault of that line.
  - A ready plan line with an empty slot is not in the app file.
- T4. `bun kit/build.js` leaves `app/src/main/assets/appfile/prompt.json` byte-equal, and the three prompt hashes stay.
- T5. A search of `kit/src/template.js` finds no template word (`scene`, `items`, `cells`, `compare`, `table`, `chart`, `nodata`) outside a comment.
- T6. One device run on `emulator-5594` of `AppFileCallWorkflowTest`, `AppFileChangeWorkflowTest`, and `StageChangeWorkflowTest` passes.

## Stage A: the large picture

The owner approved the drawn sample on 2026-11-28 («Yes»): `evidence/ready-plans/stage-a/sample-phone-1.png`, `sample-phone-2.png`, `sample-fold-1.png`, `sample-fold-2.png`. The right half of each pair is the target.
Stage A has two jobs that share no file. Job AH (the host) runs beside job R in a worktree of its own. Job AK (the kit) runs after job R.

### The contract between the two jobs

The kit writes these nodes, and the host draws them. Each one is a present node type, so no job waits for the other.

| What the person sees | Node of the kit | Host |
| --- | --- | --- |
| A choice with no label line | `Segmented` or `Select` with `variant="inline"` (present) | no change |
| A stepper with dots | `variant="stepper"` (present) | new look (AH4) |
| A caption under the controls | `Text(TEXT, variant="caption")` (new value): the node `{"type": "text", "style": {"emphasis": "caption", "tone": "muted"}}`, as `variant="source"` gives `emphasis: "source"` (`kit/src/run.js` `STYLES`) | new style (AH6) |
| Pause and speed inside the picture | the play row of today: a `Button` with `variant="tonalIcon"` and a `Segmented` with `variant="inline"` | drawn over the picture (AH5) |
| A background to the edges | shapes with points outside the box of 300 × 300 | no clip at the box (AH3) |

### Job AH: the stage of the host (Kotlin)

Job AH changes no file under `kit/` and does not build the kit again: the kit of today rejects `variant="caption"`, and a new `kit.js` would collide with job R. Its tests use UI definitions written by hand.

Requirements, for a stage app or a stage preview whose main visual is a drawing (`DialogueSplit.drawing`) and that has no side part (`DialogueSplit.side` is empty: no card and no list after the controls):

- AH1. One fitted column at each width: the phone, the fold, and the tablet. An app with a side part keeps the two columns of today on a wide stage, so a task screen can have its panel at the right. A chat card outside the stage keeps its size.
- AH2. The picture card takes the width of the stage (side padding 8 dp on a compact width, 16 dp on a wide one) and the whole height that the other parts leave. No empty zone stays under the last part. The floor of today stays: under 0.6 of the width the stage scrolls.
- AH3. The box of the drawing (300 × 300) is as large as the card permits and stays in its center. The tint of the card fills the rest. A shape that goes past the box shows up to the edges of the card, and the card clips it. The contract of a drawing accepts such points already (`GraphicsContract.kt`: each number from -8192 to 8192), so this is the layout and the clip.
- AH4. A stepper on the stage shows no label line and no text «2 из 7»: a round previous button, one dot for each step (the present dot larger, in the accent color), and a round next button, at most 440 dp wide, in the center. The label and the count stay as the accessible text, and the shown-screen line stays as it is.
- AH5. The play row of a moving scene is drawn over the bottom left corner of the picture, with no label line: the round pause or play button, and one chip with the present speed. A tap on the chip selects the next speed (slow, normal, fast, slow). The controls of the shown screen and each spoken press stay as they are.
- AH6. A text with the style `caption` is one muted text in the body size, at most 2 lines, with no container. On a wide stage it stands in the center.
- AH7. The title of a stage app has the style `titleLarge` and one line.
- AH8. `docs/voice-integration.md` and `docs/tool-integration.md` describe the new stage. No sentence describes the two columns of a drawing or the square picture.
- AH9. While a stage preview streams, the picture keeps one size: until the control and the caption come, the stage keeps the height of one control row and of a caption of two lines free. So the picture does not get smaller when a line arrives. It changes its size at most one time, when the complete screen has fewer parts.

Tests (device, `emulator-5596` only):

- TH1. At 412 × 915 dp and at 864 × 930 dp, for a scene with a stepper and for a scene with a choice: the picture card is as wide as the stage less its padding, the caption is under the picture (not beside it), and the bottom of the last part is at most 24 dp over the bottom of the stage.
- TH2. A stepper of 7 steps shows 7 dots and no label text. Its accessible text holds the label and the count. A press of next moves the present dot.
- TH3. The pause button of a play scene lies inside the bounds of the picture card. The chip shows the present speed, and three taps bring the first speed back. The shown screen has the same `motion` field as before.
- TH4. A drawing with a rect from x = -150 to x = 450 passes the contract, and a pixel left of the box has the color of that rect.
- TH5. Each present device test of the stage passes, with its statements about two columns and the square changed to the new rule: `DialogueStageContentTest`, `DialogueStageWideTest`, `DialogueStageRehearsalTest`, `StageLibraryLookTest`, `StageMotionWorkflowTest`, `StageChangeWorkflowTest`, and each test that reads `STAGE_FIT_MARK` or `LocalPictureFloor`.
- TH6. A free app with a drawing and a card keeps two columns at 864 × 930 dp, and a chat card with a drawing outside the stage keeps its size.
- TH7. Look pictures for the review of the main session, at both sizes and both themes, of four scenes (bicycle and heart with a stepper, orbit with the play row, ship with a choice): each scene as the kit of today compiles it ("old kit": a card, a label, names at the sides), and each scene with the UI of its definition changed by the test as job AK will write it (no card, a caption text, an inline choice). The drawings stay those of the kit of today.
- TH8. A stream of preview frames (the first line, then the control, the parts, and the caption) keeps the height of the picture, by AH9.

### Job AK: the kit and the prompt (after job R)

- AK1. A scene has no card: its text is a caption (`Text(TEXT, variant="caption")`), and the kit cuts it to one or two short sentences. A diagram shows the name of the present part in the picture and the sentence of the part as the caption.
- AK2. A choice of a scene is `variant="inline"`. A scene needs no control: the prompt asks for one only when the person picks a value or a step.
- AK3. A diagram names only the present part: a mark ring and a name chip beside it. Each other part has a small dot. The thing fills the box (no name columns at the sides).
- AK4. The play row shows beside each control (the rule "only with no control line" goes), and the shown screen has `motion` then.
- AK5. The prompt tells the model to fill the box with the thing, and to draw a background (water, sky, ground) 150 units past the box at each side.
- AK6. The template check runs again on the changed prompt within the stated paid budget, and the look pictures of the check files are the look gate.

Tests: the kit tests of each rule, the golden check with a new record for the approved behavior change, the template check numbers against Alpha 24, and real pictures at both sizes.

## Stage B: task templates

The owner approved the drawn sample on 2026-11-28 («давай Stage B делай, там с теми экранами»): `evidence/ready-plans/stage-a/sample-tasks-phone.png` and `sample-tasks-fold.png`.
Job B is one job for the kit, the prompt, and the host. It starts after jobs AH and AK are joined in this tree, because it changes the same files.

Requirements:

- B1. Two task templates ship as ready plans with line tables: `collect` (collect a quantity, the owner's picture 01) and `predict` (predict, then test, the owner's picture 03). Their reply forms start from the test fixtures of job R (`kit/test/fixtures/ready/`), which move to `kit/templates/`.
- B2. `collect`: places for the target count (2 to 10, rows of five) and pieces with volume (a gradient), a counter (less and more), a check button, and one caption. Before the check the caption is the hint. After the check it is the right text or the wrong text.
- B3. `predict`: a picture of shapes that read the variable `$/v` and the run value `$/t`, one control of the variable (a slider or a choice), a question with 2 to 4 options, and a button that runs the experiment. The time of the run can be a formula of `$/v`, so the experiment shows the effect. The button is ready only after the person selected an option. After the run the caption is the right text or the wrong text for that option.
- B4. The feedback of a task is its caption. When a press of a control of the stage app (a tap, or a spoken press) changes the caption, the voice says the new caption word for word: as the answer of the hand-off for a spoken press, and as thinking for a tap while the voice does not speak. A caption that changes with a step of a walk says nothing more (the narrator says that sentence).
- B5. Layout as in the sample. On a compact width: the picture, the hint, the controls, the button. On a wide stage: the picture at the left and one panel at the right with the task title, the hint, the controls, and the button. The picture keeps the rules of stage A (it fills its column, and a background goes to its edges).
- B6. A spoken request to practice that fits one of the two templates gets it on the fast path, with the stream preview. Each other request to practice (a quiz, a test, a timer, a list, a form, a game) still gets `outside("learn")`. The explain screens are not worse: the held-out sets of stage A keep their numbers.
- B7. The template check has a tuning set and a held-out set for tasks. The requests come from the two domains of the owner's pictures (counting and number composition for ages 5 to 7; forces, motion, and experiment for ages 12 to 18) and from the recorded `outside("learn")` requests.
- B8. The documents (`kit/README.md`, `docs/voice-integration.md`, `docs/tool-integration.md`) describe the task templates, the feedback rule, and the panel.

Tests:

- TB1. Kit tests for each line and fault of both templates, the stream order, the merge and the repair by name, and the feedback caption in each state (before a check, right, wrong).
- TB2. Device tests: a tap on the check button of `collect` with the right count shows the right text, and the voice gets that text after the mark; a spoken press does the same as the answer of its hand-off; a tap on a step of a walk sends no second text.
- TB3. Device tests of the layout at 412 × 915 dp and 864 × 930 dp, as TH1 of job AH, with the panel at the right on the wide stage.
- TB4. The template check: the task sets, and the held-out sets of stage A again, within the stated paid budget.
- TB5. Real pictures of both task screens at both sizes and both themes, for the owner.

## Review focus

- A fault text of a repair call that changes by one character (T1 finds it).
- A stream frame that loses a part which an earlier frame had (T1 preview cases, T3).
- A stored template file of Alpha 24 that compiles to another app after the refactor (T1 change cases).
- A bundle that works under Bun and fails in the sandbox of the device (T6).
- A ready plan whose text differs from the app file of today by spaces or by the order of lines (T1 `expanded`).
