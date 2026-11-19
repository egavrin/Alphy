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
| `src/compile.js` | The sandbox entry `compileAppFile(text, previous?)`: an app file in; the faults and notes of the report, the definition, `hole`, the flags `blockedBySavedData` and `savedDataMismatch`, and `state` out. With `previous`, `state` is the next state of a change (`src/change.js`), else `null`. `hole` is true when the file has a need of kind `hole`, also with faults, so the plan step of the app gives that plan to its loop. The merge `patchAppFile(plan, reply)`: the plan text after a repair reply (section "Repair replies"). The reader `readPlanReply(text)`: `{kind: "outside", word}` for a reply of the one line `outside("word")`, with a word of 1 to 16 small Latin letters or digits, such as `3d`. Each other reply is `{kind: "plan", text}`: its lines with no empty line and no markdown fence line, and a final newline. Prose stays, and the compile reports it. The step `compileStep({text, plan, read, previous, cap})`: one step of the host with these three (section "The step of the host"). |
| `build.js` | The bundle of the modules as one plain script for the sandbox, with the globals `compileAppFile`, `patchAppFile`, `readPlanReply`, and `compileStep`, the run-time text in it (minified by `Bun.build`), the prompt asset `app/src/main/assets/appfile/prompt.json` (`promptAsset`), and the copies of the plans for the device tests. |
| `prompt.js` | The texts of the prompt for the model check and the app: `PROMPT` (the sections of `prompt.md` by heading), `PROMPT_HASH`, and the task texts `taskText`, `repairText`, and `changeText`. `prompt.json` holds the four sections as `system`, `repair`, `dialogue`, and `change`, and the templates `repairTask` and `changeTask`. The host puts the plan with no newline at its end in `{{plan}}`, the faults joined by `"\n- "` in `{{faults}}`, and the task text or the request in `{{task}}` or `{{request}}`. A filled template is then the text of `repairText` or `changeText`. |
| `prompt.md` | The plan prompt: the section "System" is the instructions of each plan call and repair call, "Repair" is the rule of a repair call, "Dialogue" is the line under the request of a screen of a voice dialogue (today only `modelcheck.js` adds it, and the app will add it with the voice path), and "Change" is the rule of a change call of an app (after the request and the app file). Task 2b measured "Change" on set F of `modelcheck.js` (results document, section "Changes by lines"). `test/prompt.test.js` checks each kind, option, icon, and example of it against the kit. The section "System" names no `hole`: the kit keeps the kind and its tests. A request that no kind serves gets `outside(...)` with one of six words: `game`, `drawing`, `3d`, `picture`, `device`, or `other` for a main thing that no kind gives and that is none of the others. |
| `modelcheck.js` | The model check of `prompt.md` outside the app: for each request, a plan call of the OpenAI Responses API with the body of `OpenAiResponsesWire.kt`, the texts of `prompt.js`, `compileStep` of the kit for the read of the reply, each merge, and each compile, as the app runs them, and up to 2 repair calls at the effort low (the setting `REPAIRS` gives another count, such as 1 for the loop of the app). It counts each call in `evidence/modelcheck/spend.json` before the call and keeps each reply and record in `evidence/modelcheck/<label>/`. Its sets of requests are C (the 45 cases of the corpus), D (8 requests of a voice dialogue), E (`test/fixtures/fresh-requests.json`, 28 requests that no earlier run used), and F (`test/fixtures/change-requests.json`, 16 change requests on 8 plans of `test/fixtures/model/`). A case of set F runs the events of the person through the run-time to make the stored state, sends `changeText`, merges the reply into the app file and compiles it with the stored app (`compileStep`), and gets one repair call. As in the app, it gets no repair call after a fault that the saved data blocks or the mismatch fault (the flags `blockedBySavedData` and `savedDataMismatch`). Its record compares each stored value of the person with the next state by the names of the previous definition. `test/modelcheck.test.js` covers its task text, body, repair, sets, records, and change cases with a fake of the call. |
| `look.js` | The look numbers of each saved plan and each look fixture of `build.js`: `score_app` of `tools/agent_corpus/visual_score.py` on the compiled definition, as one table row for each plan. |
| `plans/` | The saved plans. |
| `test/` | The Bun tests. `test/fixtures/` holds example plans of the prototype that only the tests read, and the requests of set E of `modelcheck.js`. `test/nodes.plan` holds each component of the emitter's node table: the kit test and the device test `eachNodeOfTheTablePassesParseAndSmoke` read it. `test/lists.plan`, `test/rich.plan`, and `test/cards.plan` hold the lists and modals, the lists full to the render work limit, and the cards, charts, tabs, lessons, and questions: each has a kit test and a device test. `test/free.plan` holds the free forms that no other plan holds (an inline Row as a List template, a row Button with `enabled=`, `flip`, `move` with a test, and a line whose name is the name of an inline child): the kit tests and the device test `eachFreeFormPassesParseAndSmoke` read it. `test/shapes.plan` holds each shape kind, each outline form, `each` over a list, each kind of color, a text, and `animate` beside a timer with rounds: the kit tests and the device test `eachShapeAndColorPassesParseAndSmoke` read it. `test/draw.test.js` holds the commands, colors, and limits of `src/draw.js`. `test/budget.test.js` measures each compiled plan against each limit of Alphy and prints one table row for each plan. `test/timers.test.js` holds the timers of `plans/tea.plan` and `plans/timer-rounds.plan`, and the short plan of the device test `timerRoundsPassesParseAndSmoke`. `test/looks.plan` holds each look option and a `Grid`: `test/looks.test.js` and the device test `eachLookOptionPassesParseAndSmoke` read it. `test/fixtures/look-water-ko.plan` and `test/fixtures/look-shopping-it.plan` are two requests of the corpus with components only, in the tree of their baseline apps. `test/fixtures/mock-water-ko.plan`, `mock-units-pl.plan`, `mock-shopping-it.plan`, `mock-habits-uk.plan`, and `mock-price-filter.plan` are five requests of the corpus with parts: the device test `picturesOfEachPlan` shows each one beside the spec of its baseline app (`app/src/androidTest/assets/appfile/baseline/`). `test/entries.test.js`, `test/inputs.test.js`, `test/figures.test.js`, and `test/counter.test.js` hold the parts of a list app (section "Parts"). `test/fixtures/model/` holds the 52 plans that `gpt-6.1-terra` wrote in the model check: the final plan of each clean record of the runs `final-terra-low` (sets C and D) and `fresh-terra-low` (set E). Its `index.json` holds the request, the dialogue flag, the set, the model, the effort, the prompt hash, and the run label of each plan. `test/modelplans.test.js` compiles each one, and the device tests read them. |

The gate of the kit is `"smoke"`: the smoke run uses each control. The report also runs each check line.
In each new state, the smoke run presses again each control that it used in an earlier state, such as a tab, when that press shows a control that it did not use yet.
So a control that only a later state enables is used too, such as the buttons of a deck over an empty list in a tab.
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

`compileStep` uses it for the repair step of the app and of `modelcheck.js`, and the device bundle defines both.

## The step of the host

`compileStep({text, plan, read, previous, cap})` is one step of the host (`AppFileCompiler.kt`) in one call of the sandbox:

1. With `read` true, the kit reads the reply text with `readPlanReply`. An `outside` reply returns at once as `{kind: "outside", word}`.
2. With `plan` not `null`, `patchAppFile` merges the reply into `plan`.
3. A merged text of more than `cap` characters gives `result: null`, and the kit does not compile it.
4. Else `result` is `compileAppFile` of the merged text with the stored app `previous`.

`previous` is `null`, or `{appFile, stateSchema, state}` with `stateSchema` and `state` as JSON text, as the host sends them. A text that is not JSON makes the step reject.
Each other result is `{kind: "plan", text, result}`, where `text` is the merged text.
`test/compile.test.js` tests each branch, and the bundle test runs the step in a fresh context.

## Changes of an app

`compileAppFile(text, previous)` compiles a changed app file and gives the next state of the stored app.
`previous` is `{appFile, stateSchema, state}` as the host keeps it.
The result is `{faults, notes, definition, language, hole, blockedBySavedData, savedDataMismatch, state}`. `state` is `null` with a fault, and with no `previous`.
`blockedBySavedData` is true for the faults of rule 4, and `savedDataMismatch` is true for the fault of rule 1. The kit sets each flag where it makes the fault, so the host reads the flags and not the texts of the faults.
The rules keep each stored value of the person, and they never move a value to another line:

1. The kit compiles `previous.appFile` again. A fault there is the one fault `kit: the saved data does not match the app file` (`MISMATCH`).
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
5. A stored field that the change does not name stays with its schema and its value, and `app.keep` lists it.
   A list or an array that the app computed, such as the view of a sorted list, stays empty. A root that comes back later gets its kept value again.
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
| `variant` | `Button` | `filled`, `outlined`, `text`, `tonal`. With no variant, the host decides. `selected=` gives `filled` or `tonal`. |
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
  - More than two check marks are circles in a second row under the other cells, each named by `labels=` or its field. The host draws each row of a list on a card of its own, so the row has no card of the part.
  - `icon="🛒"` gives each row the same sign. An icon name in its place, such as `icon="Cart"`, is a note, and the rows have no sign. `icon=$x` reads `x` from a field of `fields=` or a name of `show=`. A row that the add form makes gets its sign the same way: `show=` computes it from the new row, or the person types the field.
  - The text of the emoji for a screen reader is the title of the list.
  - The add form is a column of its fields and a filled add button with a plus. A press with an empty first text field adds nothing.
  - A text field of the form is a live input of the host (`inputMode: "live"`): the typed text goes into the state while the person types.
    To add a row, a person types, finishes the edit (the action key of the keyboard or the host's finish button under the field), and then presses the add button.
    The host refuses a press before the finish and shows a notice, and the baseline apps of the agent loop have the same steps. No step adds a row with one press after the typing.
  - A plan with a list and no figure, progress, drawing, timer, or model starts with a hero card of the count of the shown rows of its first list. `countText=` is its caption, else the title of the list. `icon=` with a sign is its large emoji.
    `total="of"` shows all rows too, such as "5 of 12". `total=` on a list with no hero count is a note, and the kit leaves it out.
  - `icons={nome: "Cart"}` gives a field of the add form its icon. A text, number, count, date, or time field shows an icon, and a choice of more than six options does too. Each other field, a name that is not a field, and each name with `add=false` are notes, and the kit leaves the icon out.
  - `tones={streak: "accent"}` gives the badge of a value of `show=` its tone. A name that `show=` does not give, or the sign of the row, is a note, and the kit leaves it out.
  - A value of `show=` prints with `amount(x, 2)`: a number in the language of the app with at most 2 decimals, and a text as it is, such as one that `fixed()` or `money()` of the plan printed. A name of `labels=` stands before the value, such as "Серія днів: 0" (L1 item 2).
  - In a row with values of `show=`, the title and the value cells are one group that wraps: a cell that does not fit beside the title goes under it, with the width of its content. The emoji, the check marks, and the remove button stay at the ends of the line. A row with no value of `show=` keeps its title with the rest of the line.
    The texts of a title of two lines there have `fit=true`, so the title is as wide as its text, and a value that fits stands beside it.
  - An editable row (`edit=true`) is one line that wraps (L1 item 1). A text field there is as wide as the host lets it be (up to 300 dp), so on a phone it has a line of its own. The first text field shares its line only with the emoji, and each other control keeps the width of its content.
    A count of a row is one group of a minus button, a number field with no stepper of its own, and a plus button, on a line of its own.
- `input`: `options={value: "Label"}` keeps the value in the state and shows the label. `icon=` goes to a text, number, date, or time field and to a choice of more than six options.
  A single choice of more than six options is a select, and a choice of six options or fewer is a segmented control.
  Alphy measures the labels at the width that the control gets (`NativeToolUi.kt` `NativeChoices`, L1 item 4). When the widest label and the check of a selected option do not fit the share of one option, the options are cells that wrap.
  Each cell shows its label whole on one line, and the selected cell has the colors of a selected segment. The title of the choice stands over the cells, as over a segmented row. So one plan can show cells on a phone and a segmented row on a wide screen.
  The host draws a segmented control with no icon. Each kind of input takes `icon=`: on a segmented control, a slider, a range slider, a multi-choice, or a toggle it is a note, and the kit leaves it out.
  Each other option of an input is only for its own kinds: `min=` on a text field is a fault.
  The kinds are `number`, `money`, `percent`, `count`, `range`, `choice`, `choices`, `toggle`, `text`, `date`, and `time`. Each other kind, such as `slider`, is a fault of the need.
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
- `chart`: the title shows once, as the label of the chart, with no section around it.
- `card`: `icon=` is the icon of the card, and the body text is muted.
- `drawing` and `model3d`: a plan with no figure or progress has its first drawing or model as the main element, unless the author wrote `root`. The main drawing is in a hero card, and the main model card is a hero card that holds only the model.
  The part chips, the turn buttons, and the explode button of the main model stand under its card, in this order.
  The stage of a model is the muted tone of the theme. A part chip of a model is tonal, and filled while it is the selected part. The explode button is outlined.
  The line of a part chip has the part id in its name, such as `rover_part_wheels`. An id that is not a word, such as `"wheel-l"`, gives the place of the chip, such as `rover_part_2`.
  The color of a shape or a body is a color name of the kit (`core.js` `TONES`) or a hex such as `"#336699"`. Each other color of a line is a fault of the line.
  The color `surface` is the surface role of the theme (`NativeToolUi.kt` `drawingRoles`): white in a light theme and near black in a dark one. The host keeps each ink role and the surface role at a contrast of 4.5 or more.
  A text takes a color that reads on the last filled disk or box under its anchor (`draw.js` `commandsOf`, L1 item 6):
  - On a shape in a hex color, the text is black or white by the luminance of the shape, so the contrast is 4.5 or more.
  - On a shape in an ink role (`ink`, `accent`, `success`, `warning`, `error`), the text is in the surface role.
  - On a muted shape, the text is in the default ink. The host draws a muted shape as the muted ink at the alpha `MUTED_SHAPE_ALPHA` (56), and only the default ink keeps 4.5 on that tint on each canvas.
  - On a shape in `surface`, or on no shape, the text keeps its color, and a text in `surface` takes the default ink.
  - A polygon, a line, and an outline cover no text, so a text on a filled polygon keeps its color.
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
- `bun kit/build.js` writes `src/icons.js` from the host's sources, the sandbox bundle `app/src/main/assets/appfile/kit.js`, and the prompt asset `app/src/main/assets/appfile/prompt.json`, and copies `plans/*.plan`, the test plans `nodes`, `looks`, `lists`, `rich`, `cards`, `free`, `shapes`, `bytes`, and `model`, and the fixtures `water-ko`, `units-pl`, `shopping`, `habits`, `price-filter`, `look-water-ko`, `look-shopping-it`, `look-deck-es`, `look-guide-es`, and the five `mock-*` plans to `app/src/androidTest/assets/appfile/` for the device tests. It copies the model plans of `test/fixtures/model/` and their `index.json` to `app/src/androidTest/assets/appfile/model/`.
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
