# App file kit

The kit turns an app file into an app.
An app file is a short list of declarative lines that a model writes.
The kit parses the lines and expands each need line into ordinary lines.
Then it runs the app (state, formulas, actions, and the component tree) and checks it.

| Path | Responsibility |
| --- | --- |
| `src/dsl.js` | Lines, expressions, and `AppFile`. |
| `src/parts.js` | The parts: each one changes a need line into component, state, and formula lines. |
| `src/core.js` | The evaluator: the `App` that holds the state and builds the component tree. It reads no `process` and no file. |
| `src/check.js` | The report of the host, the check lines, and the smoke run that uses each control. Only the compile step uses it. |
| `src/rows.js` | The list mapping of the run-time: `rows` maps a List to Alphy's list, and the value forms that the screen and the state share (Alphy names, times, dates, shown values). |
| `src/draw.js` | The drawing of a surface for the run-time: `commandsOf` gives the kit's shapes (circle, rect, line, text, polygon, and their outlines) as Alphy's drawing commands with Alphy's colors, `barOf` is the bar of a timer, `budgetOf` counts what commands cost, and `fit` keeps a reply inside `LIMITS` of one reply. |
| `src/drag.js` | The drag of a 3D model for the run-time: `dragged` turns a model by 0.6 degrees for each pixel that a pointer moves along x, and `IDLE` is the drag when no drag runs, also after each open. |
| `src/run.js` | The run-time of a compiled app: `handle` answers each event, and `screen` maps each component to its Alphy node. A timer with rounds, a rest, or no alert draws its bar on a surface and asks for a timer event each second while it runs. A timer with an alert and one phase is Alphy's foreground timer. A Drawing is a canvas with one surface that each reply draws again. A value that an `animate` moves keeps its change in the state field `anim` and asks for a timer event each 50 ms while it moves. |
| `src/emit.js` | The emitter: an expanded app file as Alphy's app definition, whose `source` is the run-time and the compiled data of the app. It gives a fault for each limit of Alphy that the kit's own mapping can pass: state fields, the fields of a need's object, source bytes, and the nodes and depth of the screen. |
| `src/compile.js` | The sandbox entry `compileAppFile`: an app file in; the faults and notes of the report and the definition out. |
| `build.js` | The bundle of the modules as one plain script for the sandbox, the run-time text in it (minified by `Bun.build`), and the copies of the plans for the device tests. |
| `plans/` | The saved plans. |
| `test/` | The Bun tests. `test/fixtures/` holds example plans of the prototype that only the tests read. `test/nodes.plan` holds each component of the emitter's node table: the kit test and the device test `eachNodeOfTheTablePassesParseAndSmoke` read it. `test/lists.plan`, `test/rich.plan`, and `test/cards.plan` hold the lists and modals, the lists full to the render work limit, and the cards, charts, tabs, lessons, and questions: each has a kit test and a device test. `test/free.plan` holds the free forms that no other plan holds (an inline Row as a List template, a row Button with `enabled=`, `flip`, `move` with a test, and a line whose name is the name of an inline child): the kit tests and the device test `eachFreeFormPassesParseAndSmoke` read it. `test/shapes.plan` holds each shape kind, each outline form, `each` over a list, each kind of color, a text, and `animate` beside a timer with rounds: the kit tests and the device test `eachShapeAndColorPassesParseAndSmoke` read it. `test/draw.test.js` holds the commands, colors, and limits of `src/draw.js`. `test/budget.test.js` measures each compiled plan against each limit of Alphy and prints one table row for each plan. `test/timers.test.js` holds the timers of `plans/tea.plan` and `plans/timer-rounds.plan`, and the short plan of the device test `timerRoundsPassesParseAndSmoke`. |

The gate of the kit is `"smoke"`: an app file of this design has no check lines.

## Commands

Run each command from the repository root.
The kit has no dependencies and no install step.

- `bun test kit` runs the tests. The summary of the output shows `0 fail`.
- `bun kit/build.js` writes the sandbox bundle `app/src/main/assets/appfile/kit.js` and copies `plans/*.plan` and the test plans `nodes`, `lists`, `rich`, `cards`, `free`, and `shapes` to `app/src/androidTest/assets/appfile/` for the device tests.
- `bun kit/build.js --check` writes nothing. It exits 1 when one of these files differs from what the build writes, and it prints the version of Bun that ran.

The minifier of Bun makes the run-time text, so the bundle depends on the version of Bun.
Bun 1.3.9 wrote the checked-in bundle, and the first line of the bundle names that version.
With a different version, `--check` can fail with no change in `kit/src`.

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
