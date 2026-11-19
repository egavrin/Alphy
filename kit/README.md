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
| `src/check.js` | The report of the host, the check lines, the smoke run that uses each control, and the check of each look option of a component. Only the compile step uses it. |
| `src/icons.js` | Each icon name of Alphy. `build.js` writes it from `ToolUiCatalog.kt` (`shellIcons`) and `ToolIcons.kt` (the keys of `paths`). Only `check.js` reads it. |
| `src/rows.js` | The list mapping of the run-time: `rows` maps a List to Alphy's list, and the value forms that the screen and the state share (Alphy names, times, dates, shown values). |
| `src/draw.js` | The drawing of a surface for the run-time: `commandsOf` gives the kit's shapes (circle, rect, line, text, polygon, and their outlines) as Alphy's drawing commands with Alphy's colors, `barOf` is the bar of a timer, `budgetOf` counts what commands cost, and `fit` keeps a reply inside `LIMITS` of one reply. |
| `src/drag.js` | The drag of a 3D model for the run-time: `dragged` turns a model by 0.6 degrees for each pixel that a pointer moves along x, and `IDLE` is the drag when no drag runs, also after each open. |
| `src/run.js` | The run-time of a compiled app: `handle` answers each event, and `screen` maps each component to its Alphy node. A timer with rounds, a rest, or no alert draws its bar on a surface and asks for a timer event each second while it runs. A timer with an alert and one phase is Alphy's foreground timer. A Drawing is a canvas with one surface that each reply draws again. A value that an `animate` moves keeps its change in the state field `anim` and asks for a timer event each 50 ms while it moves. |
| `src/emit.js` | The emitter: an expanded app file as Alphy's app definition, whose `source` is the run-time and the compiled data of the app. It gives a fault for each limit of Alphy that the kit's own mapping can pass: state fields, the fields of a need's object, source bytes, and the nodes and depth of the screen. |
| `src/compile.js` | The sandbox entry `compileAppFile`: an app file in; the faults and notes of the report and the definition out. |
| `build.js` | The bundle of the modules as one plain script for the sandbox, the run-time text in it (minified by `Bun.build`), and the copies of the plans for the device tests. |
| `look.js` | The look numbers of each saved plan and each look fixture of `build.js`: `score_app` of `tools/agent_corpus/visual_score.py` on the compiled definition, as one table row for each plan. |
| `plans/` | The saved plans. |
| `test/` | The Bun tests. `test/fixtures/` holds example plans of the prototype that only the tests read. `test/nodes.plan` holds each component of the emitter's node table: the kit test and the device test `eachNodeOfTheTablePassesParseAndSmoke` read it. `test/lists.plan`, `test/rich.plan`, and `test/cards.plan` hold the lists and modals, the lists full to the render work limit, and the cards, charts, tabs, lessons, and questions: each has a kit test and a device test. `test/free.plan` holds the free forms that no other plan holds (an inline Row as a List template, a row Button with `enabled=`, `flip`, `move` with a test, and a line whose name is the name of an inline child): the kit tests and the device test `eachFreeFormPassesParseAndSmoke` read it. `test/shapes.plan` holds each shape kind, each outline form, `each` over a list, each kind of color, a text, and `animate` beside a timer with rounds: the kit tests and the device test `eachShapeAndColorPassesParseAndSmoke` read it. `test/draw.test.js` holds the commands, colors, and limits of `src/draw.js`. `test/budget.test.js` measures each compiled plan against each limit of Alphy and prints one table row for each plan. `test/timers.test.js` holds the timers of `plans/tea.plan` and `plans/timer-rounds.plan`, and the short plan of the device test `timerRoundsPassesParseAndSmoke`. `test/looks.plan` holds each look option and a `Grid`: `test/looks.test.js` and the device test `eachLookOptionPassesParseAndSmoke` read it. `test/fixtures/look-water-ko.plan` and `test/fixtures/look-shopping-it.plan` are two requests of the corpus with components only, in the tree of their baseline apps. `test/fixtures/mock-water-ko.plan`, `mock-units-pl.plan`, `mock-shopping-it.plan`, `mock-habits-uk.plan`, and `mock-price-filter.plan` are five requests of the corpus with parts: the device test `picturesOfEachPlan` shows each one beside the spec of its baseline app (`app/src/androidTest/assets/appfile/baseline/`). `test/entries.test.js`, `test/inputs.test.js`, `test/figures.test.js`, and `test/counter.test.js` hold the parts of a list app (section "Parts"). |

The gate of the kit is `"smoke"`: an app file of this design has no check lines.
A need takes only the named options of its part (`parts.js` `OPTIONS`): each other option is a fault of the need.
A fault of a line that a part wrote names the need, such as `need b`, and not the line `b_button`.

## Look options

A look option is a named option of a component line, such as `Text("Total", emphasis="number")`.
The run-time puts each value into the Alphy node as it is.
The compile step checks each value against the table below (`check.js` `looks`).
An unknown value, a look option of a component that does not take it, and a value that is not in the line are faults of the line.
A value in the line is a literal, or a choice of literals, such as `tone=$/late ? "error" : "muted"`.
In a row of a list, a look value must not read a field of the row, such as `$done`, because one template draws each row: a choice on a value of the app is permitted.

| Option | Components | Values |
| --- | --- | --- |
| `icon` | `Button`, `IconButton` (first argument), `Metric`, `Badge`, `Notice`, `Card`, `TextField`, `NumberField`, `Select`, `DateField`, `TimeField` | Each icon of Alphy (`src/icons.js`), and the kit names `ArrowUp`, `ArrowDown`, `ArrowLeft`, `ArrowRight`, and `Edit`. A `Select` of six options or fewer is a segmented control, which shows no icon. |
| `variant` | `Button` | `filled`, `outlined`, `text`, `tonal`. With no variant, the host decides. `selected=` gives `filled` or `tonal`. |
| `variant` | `Text`, `Card`, `CheckBox`, `Progress` | `muted` and `eyebrow`, `hero`, `circle`, `bar` and `dots`. |
| `size` | `Emoji` | `small`, `medium`, `large`. |
| `emphasis` | `Text` | `body`, `title`, `headline`, `label`, `number`, `display`. |
| `emphasis` | `Metric` | `display`, `number`. |
| `align` | `Text`, `Row` | `start`, `center`, `end`. A `Row` of no wrap is centered across its height by default. |
| `tone` | `Text`, `Metric`, `Badge`, `Notice` | `muted`, `accent`, `success`, `warning`, `error`. |
| `gap` | `Row`, `Column` | `none`, `small`, `medium`, `large`. The root `Column` is the column of the app: its gap is the gap of the app, and its padding is `medium`. |
| `columns` | `Grid` | 1, 2, 3, 4. The default is 2. |

`Emoji(sign, size=)` takes a sign, a state value, or a field of a list row.
`label=` is its text for a screen reader. Without it, the text in a list row is the kit caption of its line, such as "item Emoji", because one template draws all rows. The `entries` part gives it the title of the list.
`Segmented`, `Select`, and `Choices` take `options` as a list or as a map of a text value to its label, such as `{asc: "Low to high", desc: "High to low"}`.
The state keeps the value, and the control shows the label in the order of the plan.
A map key that is a number is a fault, because JavaScript puts such keys in number order.
The value text inside a `Ring` has the `display` emphasis.
The `number` and `display` emphases of a `Text` are display sizes of the host (`NativeToolUi.kt` `textStyle`), so a short value in a list row takes `label` or `title`.

## Parts

A part changes one need line into component, state, and formula lines (`src/parts.js`).
The parts of a list app use the look of the baseline apps: a hero card first, rows with an emoji and a muted second line, and one filled button in each group.

The captions that a part writes by itself are in the language of `lang=` of the app line (`src/words.js`): "Add", "Remove", "Less", "More", "Reset", and the others of its table.
A language outside the table gets the English words and one note of the report that names each option of the need that took an English caption.
The host draws its own words where it has them: the empty text of a list, the close button of a sheet, and the move menu of a reordered list.

A figure prints its number with `amount(x, n)`: at most `n` decimals, no zeros at the end, and the digit groups and the decimal sign of the language of the app ("1.234,5" in `de`, "1,234.5" in `en`, "12 345,5" in `pl` with a no-break space). In `es`, `pl`, and `pt` a number of four digits has no group ("1234,5").
`fixed`, `money`, and `percent` use the same signs. A plain `text(x)` and a `Metric` of a state value print the number as it is, so a year belongs in `text()`, not in a figure.
A text that a format function printed is for a person: do not read it back as a number. `number(fixed(1234.5))` is 0 in `en`, because "1,234.50" has a group sign.

| Part | Look options | Example |
| --- | --- | --- |
| `entries` | `icon` (an emoji for each row, or `$field`), `countText` (the caption of the hero count), `edit` (a choice of a row is a control, not a label) | `need items = entries("Prodotti", fields={nome: "text", corsia: ["Frutta", "Forno"], preso: "check"}, icon=$segno, show={segno: $corsia == "Forno" ? "🥖" : "🍎"}, countText="Da comprare")` |
| `input` | `icon` (the icon of the field), a map in `options=`, and the kind `range` | `need price = input("range", "Price", [100, 1500], min=0, max=2000, step=50)` |
| `figure` | `icon` (an emoji, a formula that gives one, or a host icon), `unit` (a word after the number), `note` (a muted text), `goal` (a bar to the goal), `goalText` (the caption of the bar) | `need today = figure("number", "Today", $/ml, icon="💧", unit="ml", goal=2000, goalText="Of the goal", note="250 ml for each cup")` |
| `counter` | `lessText`, `moreText`, `addText` (one button that adds the step), `icon` (the icon of that button), `resetText` | `need ml = counter("Water (ml)", 0, step=250, addText="Add a cup", icon="Cup")` |

- `entries`:
  - The row is the emoji, the first text field as the title with each further text field as a muted line under it, the value cells as small labels, the check marks, and the remove button.
  - In a row with one text field, a choice that the row does not change is the muted line under the title.
  - More than two check marks are circles in a second row under the other cells, each named by `labels=` or its field. The host draws each row of a list on a card of its own, so the row has no card of the part.
  - `icon="🛒"` gives each row the same sign. `icon=$x` reads `x` from a field of `fields=` or a name of `show=`. A row that the add form makes gets its sign the same way: `show=` computes it from the new row, or the person types the field.
  - The text of the emoji for a screen reader is the title of the list.
  - The add form is a column of its fields and a filled add button with a plus. A press with an empty first text field adds nothing.
  - A text field of the form is a live input of the host (`inputMode: "live"`): the typed text goes into the state while the person types.
    To add a row, a person types, finishes the edit (the action key of the keyboard or the host's finish button under the field), and then presses the add button.
    The host refuses a press before the finish and shows a notice, and the baseline apps of the agent loop have the same steps. No step adds a row with one press after the typing.
  - A plan with a list and no figure, progress, drawing, timer, or model starts with a hero card of the count of the shown rows of its first list. `countText=` is its caption, else the title of the list. `icon=` with a sign is its large emoji.
- `input`: `options={value: "Label"}` keeps the value in the state and shows the label. `icon=` goes to a text, number, date, or time field and to a choice of more than six options.
  The host draws a choice of six options or fewer as a segmented control, which shows no icon. Each kind of input uses only its own options: `icon=` on a segmented control, a multi-choice, or a toggle is a fault, and so is `min=` on a text field.
  The kind `range` writes `RangeSlider(label, $/x, min=, max=, step=)`: one state value of two numbers in order, the low and the high (`$/price[0]`, `$/price[1]`).
  Each `TextField` and each `Slider` is a live input of the host (`inputMode: "live"`), also in a row and in a bottom sheet. The host sends its value as a `liveInput` event while the person types or drags, and `handle` takes only that event for it.
  A slider finishes its edit when the drag ends. A text field finishes it with the action key of the keyboard or the finish button. Each other control of the kit, the `RangeSlider` and the `NumberField` included, sends an `action`.
- `figure`: the main figure is a hero card of the emoji, the metric with the display emphasis, the bar of `goal=`, and the muted `note=`.
  The caption of the bar is `goalText=`, else the title of the figure. `unit=` follows the shown number ("2,000 ml"), and the value of the figure stays a number.
  The other figures of one place are a set. A place is one group of a `steps` or `tabs` need, or the screen for the needs that no such need groups. A figure never leaves its place, and a figure that two groups name is a metric in each of them.
  - Two figures of a set that stand one after the other are a grid of two metrics, each with its `icon=`. Two figures with another need between them stay two metrics.
  - Three figures or more of a set are rows of one size: the title at the left and the value at the right. A row shows no `icon=`.
  - One figure of a set is a metric.
  `note=`, `goal=`, and `goalText=` show only on the main figure. `goalText=` with no `goal=` is a fault, and so is `icon=` on a row.
- `counter`: a section with the title of the counter. It holds an outlined less button, the value with the display emphasis, and a filled more button.
  With `addText=`, the counter is one filled button that adds the step and a text button that resets the value. The plan shows the value elsewhere, such as in a figure.
  `lessText=` and `moreText=` with `addText=` are a fault, and so are `icon=` and `resetText=` without it.
- The value of an option has its type. `sheet=`, `edit=`, `add=`, `remove=`, `hidden=`, and `reorder=` are `true` or `false` in the line, because a part reads them once when it builds the screen. `step=` and `decimals=` are numbers or formulas. A value of another type is a fault of the need.

## Commands

Run each command from the repository root.
The kit has no dependencies and no install step.

- `bun test kit` runs the tests. The summary of the output shows `0 fail`.
- `bun kit/build.js` writes `src/icons.js` from the host's sources and the sandbox bundle `app/src/main/assets/appfile/kit.js`, and copies `plans/*.plan`, the test plans `nodes`, `looks`, `lists`, `rich`, `cards`, `free`, `shapes`, `bytes`, and `model`, and the fixtures `water-ko`, `units-pl`, `shopping`, `habits`, `price-filter`, `look-water-ko`, `look-shopping-it`, and the five `mock-*` plans to `app/src/androidTest/assets/appfile/` for the device tests.
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
