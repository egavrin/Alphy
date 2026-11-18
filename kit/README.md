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
| `src/run.js` | The run-time of a compiled app: `handle` answers each event, and `screen` maps each component to its Alphy node. |
| `src/emit.js` | The emitter: an expanded app file as Alphy's app definition, whose `source` is the run-time and the compiled data of the app. |
| `src/compile.js` | The sandbox entry `compileAppFile`: an app file in; the faults and notes of the report and the definition out. |
| `build.js` | The bundle of the modules as one plain script for the sandbox, the run-time text in it, and the copies of the plans for the device tests. |
| `plans/` | The saved plans. |
| `test/` | The Bun tests. `test/fixtures/` holds example plans of the prototype that only the tests read. |

The gate of the kit is `"smoke"`: an app file of this design has no check lines.

## Commands

Run each command from the repository root.
The kit has no dependencies and no install step.

- `bun test kit` runs the tests. The summary of the output shows `0 fail`.
- `bun kit/build.js` writes the sandbox bundle `app/src/main/assets/appfile/kit.js` and copies `plans/*.plan` to `app/src/androidTest/assets/appfile/` for the device tests.
- `bun kit/build.js --check` writes nothing. It exits 1 when one of these files differs from what the build writes.

## Origin

The kit comes from the isolated prototype of the app file design (`alphy-dsl-prototype`, outside this repository).
`src/dsl.js` and `src/parts.js` are copies of the prototype files.
`src/core.js` and `src/check.js` are the two halves of the prototype's `runtime.js`.
The tests are a port of the prototype's `test.js` without the blocks that need its prompts, generation, or agent loop.
The plan `docs/superpowers/plans/2026-11-18-app-file-kit.md` names the source of each saved plan.
