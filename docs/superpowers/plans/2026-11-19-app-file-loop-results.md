# App file loop: results

This file records the results of the loop work for the app file kit.
Each section belongs to one task and keeps the numbers of that task.

## Model check outside the app

Task A0 measured how the OpenAI models of the app write plans for the kit, before any Kotlin of the loop.
The scope is `.superpowers/sdd/2026-11-19-app-file-loop/a0-decisions.md`.
The first sweep ran on `85c37e2`. Round 1 and the final run ran on `85c37e2` with prompt v1 and the prompt hash in each record, which this commit adds.
All runs ran on this computer, with 4 calls in parallel. Each of the 328 calls completed.
Raw replies and records are in `evidence/modelcheck/<label>/`, which Git does not keep. Each reply of each call is kept as `<id>.<step>.txt`.

### What ran

- `kit/prompt.md` is the plan prompt. Its section "System" is the instructions of each call (21881 bytes, about 6300 input tokens with the task).
- `kit/modelcheck.js` builds the task text as `ChatToolReply.generationPrompt` does, sends the body of `OpenAiResponsesWire.wireBody` with no JSON mode, compiles the reply with `compileAppFile`, and sends up to 2 repair calls at the effort low.
- A repair call sends the task, the plan, the faults of the kit, and the section "Repair". The harness merges the reply into the plan: a line with the name of an earlier line replaces it in place, and other lines go at the end.
- Set C is the 45 corpus cases (`tools/agent_corpus/showcase.json` and `holdout.json`): 25 expect a plan and 20 expect `outside(...)`.
- Set D is 8 Russian requests of a voice dialogue (4 explanations, 4 lessons), with the host line of the section "Dialogue" under the request line.
- Each record has the prompt hash: v0 is `9529ed1a3a12` (`1f75bda`), and v1 is `c3d65bf76c7b` (this commit). All 53 records of the final run have `c3d65bf76c7b`.

| Label | Prompt | Model | Effort | Requests | Calls |
| --- | --- | --- | --- | --- | --- |
| `probe` | none | terra and sol | low | 2 | 2 |
| `r0-terra-low` | v0 | `gpt-6.1-terra` | low | 53 | 58 |
| `r0-terra-medium` | v0 | `gpt-6.1-terra` | medium | 53 | 61 |
| `r0-sol-low` | v0 | `gpt-6.1-sol` | low | 53 | 65 |
| `r0-sol-medium` | v0 | `gpt-6.1-sol` | medium | 53 | 64 |
| `r1-terra-low` | v1 | `gpt-6.1-terra` | low | 22 | 22 |
| `final-terra-low` | v1 | `gpt-6.1-terra` | low | 53 | 56 |

The API took both model names and the body as the app sends it.

### Final numbers: prompt v1, `gpt-6.1-terra`, effort low

| Measure | Set C (25 servable) | Set D (8) |
| --- | --- | --- |
| Clean at the first try | 23 (92%) | 8 (100%) |
| Clean after 1 repair | 1 (4%) | 0 |
| Clean after 2 repairs | 0 | 0 |
| Not clean | 1 (4%): `showcase-lang-pomodoro-zh` | 0 |
| Clean under the device bars (compile at most 2300 ms, start state at most 49152 bytes) | 24 of 25 | 8 of 8 |
| First call: median, 90th percentile, maximum | 3.974 s, 7.176 s, 15.531 s (`showcase-lang-habits-uk`) | 8.794 s, 13.859 s, 13.859 s |
| Path to a clean plan: median, 90th percentile (clean plans only) | 4.005 s, 7.23 s | 8.82 s, 13.89 s |
| Output tokens of the first call: median (reasoning) | 237 (32) | 589 (107.5) |

- Routing: 20 of 20 cases that are not servable got `outside(...)`. No servable case got `outside(...)`. No request with a device function got a plan.
- An `outside(...)` reply took a median of 1.327 s (maximum 2.627 s). The way to today's loop pays this time first.
- The one repair: `showcase-lang-workout-de` gave an emoji icon to a figure that is not the main one ("the icon of a Metric is the name of an icon of Alphy"). The repair call took 1.617 s.
- The not-clean case: see the kit findings K1 and K5. The plan of the 2 repairs compiles clean when its delete lines are applied first.
- Compile time of the clean plans: median 17.1 ms, maximum 66.4 ms. Start state: median 247.5 bytes, maximum 1520 bytes.
- No clean plan of any run crossed a device bar or had a form that the host refuses (a figure with an empty label, a choice that is the only child of a card or a row). Over all runs the compile maximum was 76.1 ms and the start state maximum was 1706 bytes.
- The check of a lone choice reads the node types `segmented`, `select`, and `multiChoice` under a `card`, `row`, or `flowRow`. Six compiled plans of the final run give their choices as `segmented` and `multiChoice` nodes, in a `column` or a `row` with other children (a scratch check).
- The final run took 56 calls: 355310 input tokens (349990 from the cache), 12339 output tokens (2836 reasoning).

### The first sweep: prompt v0, four settings

| Setting | C clean first, after 1, after 2, not | C under the device bars | C first call median, p90, max | C path median, p90 | C output median (reasoning) | D clean first, after 1, not | D first call median, p90 | D output median (reasoning) | Calls |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| terra low | 19, 5, 0, 1 | 24 | 4.552, 6.995, 9.95 s | 5.075, 10.03 s | 226 (32) | 8, 0, 0 | 10.06, 12.063 s | 593 (96.5) | 58 |
| terra medium | 16, 8, 0, 1 | 24 | 6.551, 13.706, 27.698 s | 7.61, 15.43 s | 459 (169) | 8, 0, 0 | 14.30, 20.245 s | 867 (388) | 61 |
| sol low | 16, 5, 1, 3 | 22 | 4.573, 8.401, 10.522 s | 5.51, 10.08 s | 224 (0) | 7, 1, 0 | 13.384, 22.101 s | 635 (81.5) | 65 |
| sol medium | 16, 5, 0, 4 | 21 | 6.995, 12.789, 18.511 s | 9.12, 13.59 s | 349 (116) | 8, 0, 0 | 19.42, 31.467 s | 980 (280.5) | 64 |

- The "not" count of each setting includes `showcase-ui-priorities`, which went `outside(...)` in all four settings ("device" three times, "game" one time). The request says "by dragging them into order", and prompt v0 said "no drag".
- Not clean after 2 repairs: sol low `holdout-words-ko` and `showcase-lang-habits-uk`. Sol medium `showcase-ui-alarm`, `holdout-words-ko`, and `showcase-lang-habits-uk`.
- Routing in each setting: 20 of 20 cases that are not servable got `outside(...)`, and no device miss. The only wrong `outside(...)` was `showcase-ui-priorities`.
- Terra low was the best terra setting: more plans clean at the first try (19 against 16), a faster first call (4.552 s against 6.551 s), and half the output tokens. The tuning and the final run used it.

### Sol beside terra (prompt v0)

The owner chose `gpt-6.1-terra` as the default model of the plan step. Sol ran only in the first sweep, as a comparison. Both columns use prompt v0.

| Measure (prompt v0) | terra low | sol low | terra medium | sol medium |
| --- | --- | --- | --- | --- |
| C clean at the first try | 19 of 25 | 16 of 25 | 16 of 25 | 16 of 25 |
| C clean after at most 2 repairs | 24 of 25 | 22 of 25 | 24 of 25 | 21 of 25 |
| The same, with the delete lines applied (recount, K1) | 24 of 25 | 24 of 25 | 24 of 25 | 23 of 25 |
| D clean at the first try | 8 of 8 | 7 of 8 | 8 of 8 | 8 of 8 |
| C first call median | 4.552 s | 4.573 s | 6.551 s | 6.995 s |
| D first call median | 10.06 s | 13.384 s | 14.30 s | 19.42 s |

At the same effort, sol had fewer clean plans at the first try and a slower dialogue screen (3.3 s slower at low, 5.1 s slower at medium).
The time of set C was almost the same at low. The choice of the model stays with the owner.

### Prompt rounds

Round 1 (v0 to v1) changed six rules. Each change answers faults or a routing miss of the first sweep:

1. Write each text value in quotes, also an option and a start value. Faults before: "margherita is a component name, not a value" and the same for `black`, `asc`, and `normale` (pizza, tea-tr, price-filter).
2. "No drag" now names `entries` with `reorder=true` as the form for a list in an order. Before: `showcase-ui-priorities` went `outside(...)` in all four settings.
3. A percent input with `min=` and `max=` is a slider, which takes no icon. Before: "Slider has no property icon" in `holdout-tip-ru` in all four settings, and "RangeSlider has no property icon".
4. `edit=true` only when the request asks to change rows, and `reorder=true` with no `sort=` and no `group=`. Before: the reorder and sort fault in `showcase-lang-shopping-it` in three settings, and typed fields in rows in 8 terra low plans.
5. `icons=` never with `add=false`. Before: the `icons=` fault in alarm, habits-uk, and steps-chart.
6. The repair rule: `delete NAME` removes a need or a component line, and `delete $/NAME` removes a state line. Before: "nothing named quizIndex: delete quizIndex".

| The 22 requests of round 1 (terra low) | v0 (`r0-terra-low`) | v1 (`r1-terra-low`) | v1 (`final-terra-low`) |
| --- | --- | --- | --- |
| 15 servable of set C: clean at the first try | 9 | 15 | 13 |
| 15 servable of set C: clean after at most 2 repairs | 14 (priorities went outside) | 15 | 14 |
| 5 not servable: `outside(...)` | 5 | 5 | 5 |
| 2 of set D: clean at the first try | 2 | 2 | 2 |

No fault of the targets of round 1 came back in the final run. Rounds 2 and 3 did not run. That was a choice, not the budget: 272 calls were left under the stop mark.
The same prompt on the same 15 requests changed 2 results between `r1-terra-low` and `final-terra-low`:

- `showcase-lang-workout-de` went from clean to clean after 1 repair. This is noise of one run.
- `showcase-lang-pomodoro-zh` went from clean to not clean. Its cause is known (K5), and one candidate rule exists. A round 2 for it needs a new final run of both sets (at most 119 calls) and new final tables, so the controller decides.

### Faults and repairs

The table counts each kind of fault over all runs of terra (v0 and v1) and sol (v0). "Repaired" means that the next step did not have it.

| Fault (names and numbers removed) | Times | Repaired | What the model did |
| --- | --- | --- | --- |
| "no root line" | 10 | 7 | Terra repaired the other fault of the plan, and this one went away. Sol wrote `root = Column([...])` by hand 3 times (K2), and then deleted it (K1). |
| "X: no component line with this name", after a root line by hand | 3 | 3 | Deleted the root line (K2). |
| "need X reads $/X, and no line sets it" | 9 | 7 | Added a state line or a need. Kept in pomodoro-zh (K1). |
| "icons= names F, whose field of the add form shows no icon" | 9 | 9 | Removed `icons=` or the field from it. |
| The smoke faults of a deck in tabs over an empty list (K3) | 8 | 8 | Terra low wrote a hole in place of the deck. Sol wrote quiz lines by hand, which failed later on K1. |
| "sum needs a list", "count needs a list", "avg needs a list" | 6 | 5 | Read the list of an entries need, not a value. |
| "X is a component name, not a value" (a bare word as a value) | 5 | 5 | Put the value in quotes. |
| "Slider has no property icon" or "RangeSlider has no property icon" | 5 | 5 | Removed `icon=` from the input (K4). |
| "the arrows of a row move it in the stored list ...: no sort and no group" | 3 | 3 | Removed `sort=`. |
| "an input of kind choices does not use icon=" | 3 | 3 | Removed `icon=`. |
| "the icon of a Metric is the name of an icon of Alphy" (an emoji on a figure that is not the main one) | 2 | 2 | Removed the icon. |
| "$/X starts as an empty list, so the kind of its values is not known" | 2 | 2 | Gave the list as an entries need. |
| "nothing named quizIndex: delete quizIndex" (a delete of a state line without `$/`) | 3 | 0 | Prompt v1 names `delete $/NAME`. The recount (K1) makes the plan clean. |
| "need X is not on the screen: no line under root shows X_view", "the part made ..., and the screen no longer shows it", and "quiz_view: no component line with this name" | 10 | 0 | All came after a `delete` line of a repair (K1). |
| Other faults, one time each (two parse errors, `steps` names a need that does not exist, the icon of a NumberField, a List of rows that are not a list of the app) | 5 | 5 | Repaired from the text. |

The faults came 83 times in all.

### Kit findings for the controller

The task changed nothing in `kit/src`. Each finding names the plans that show it.

- **K1. A delete line of a repair blocks the line that a part makes later.** `AppFile.feed` (`kit/src/dsl.js` lines 236 to 255) adds the deleted name to `removed`, and `open` then refuses that name to each part. A model deletes its own line in a repair and writes a need in its place. The part of that need cannot write its line, and the kit says "no line sets it", "no root line", or "need X is not on the screen".
  - Recount with no call: the last merged plan of each record that is not clean, compiled after `new AppFile().feedText(plan).text()` has applied the deletes. 5 of the 6 records become clean: `final-terra-low/showcase-lang-pomodoro-zh`, `r0-sol-low/showcase-lang-habits-uk`, `r0-sol-low/holdout-words-ko`, `r0-sol-medium/showcase-ui-alarm`, and `r0-sol-medium/showcase-lang-habits-uk`. The sixth is K3.
  - For each of them except `r0-sol-low/holdout-words-ko`, the delete lines were in the last repair, so the recount shows the result of that step. For words-ko the first repair also had delete lines, so an earlier step could have been different.
  - Evidence: `evidence/modelcheck/final-terra-low/showcase-lang-pomodoro-zh.2.txt` (`delete $/dailyTotals`, then `need dailyTotals = entries(...)`), and `evidence/modelcheck/r0-sol-medium/showcase-ui-alarm.2.txt` (`delete root`).
  - The recorded numbers come from the merge of the harness, which keeps the delete lines of the repair reply in the compiled text. The recount shows that a merge of the loop that applies the deletes first removes this fault with no change of `kit/src`.
  - Unresolved decision: the remedy. The section "Repair" of the prompt tells the model to delete lines. The choices are a merge that applies the deletes before the compile, a kit that lets a later need take back a name that a delete removed, or a different repair rule. This decision affects the repair step of the loop.
- **K2. "no root line" comes as a second fault when the only need of a plan fails.** Sol read it as an order to write the root line by hand: `root = Column([doses])` gave "doses: no component line with this name" (`evidence/modelcheck/r0-sol-medium/showcase-ui-alarm.1.txt`, and habits-uk of both sol runs). Terra repaired the first fault, and the root fault went away.
- **K3. A deck over an entries list that starts empty, inside a tabs need, fails the smoke run with two faults that do not name the cause.** The faults are "the Button "…" of need quiz never changes a value" and "need quiz never comes to the screen". Without the tabs, or with one row in the list, the same deck compiles clean (a scratch probe). Terra low wrote a hole in place of the deck (`evidence/modelcheck/r0-terra-low/holdout-words-ko.1.txt`). Sol medium did not repair it in 2 steps. With v1, terra wrote the deck with no tabs, and it was clean at the first try.
- **K4. A fault names a component that the model did not write.** `input("percent", ..., icon=)` gives "Slider has no property icon", and `input("range", ..., icon=)` gives "RangeSlider has no property icon". The models repaired it each time. Prompt v1 covers it.
- **K5. Terra writes a hole where the count of the timer serves, and a hole compiles clean when nothing changes its state.**
  - The request of `showcase-lang-pomodoro-zh` asks for the count of today and a statistics page. The section "System" says that `$/NAME` of a timer is the count of the finished work phases, and the code map lists the case as served.
  - Both sol runs used that count (`$/focus` in a figure) and were clean at the first try. Terra low v0 wrote a list with an add form. Three of the four terra runs wrote a hole that records each finished phase by date: `r0-terra-medium`, `r1-terra-low`, and `final-terra-low`.
  - The hole of `r0-terra-medium` works: a button records a phase when the timer count is above the saved count. The hole of `r1-terra-low` is a dead screen: `$/todayCount = 0` and a Metric that shows it, and no line changes it (`evidence/modelcheck/r1-terra-low/showcase-lang-pomodoro-zh.0.txt`). The kit called it clean. The hole of `final-terra-low` did not become clean (K1).
  - A candidate rule for the prompt: a hole is never for a value that a part gives, such as the count of a timer. It did not run, because it needs a new final run and new final tables. The controller decides.
- **K6. The text of `kind=` is the first node of the screen, so a timer after a choice loses rule 1.** In tea-tr and eggs-it the app line has `kind=`, a choice input comes first, and the hero card of the timer is the third node. Without `kind=` both plans score hero 1 (a scratch check). The saved plan `kit/plans/tea.plan` has no `kind=` and scores 1.

### Look

The look numbers come from `score_app` of `tools/agent_corpus/visual_score.py` on the compiled definition, as `kit/look.js` runs it.
The baseline is the `artifact.json` of `final-30d43aa` for 34 corpus cases, and of `2026-11-16/showcase1` for 11 cases (6 of them below: verbs-fr, tea-tr, trip-dates, onboarding, pizza, steps-chart).

| Case (final run, clean) | Plan: hero, fields on the first screen, fields in rows | Baseline |
| --- | --- | --- |
| `holdout-power-de` | 1, 3, 0 | 1, 3, 0 |
| `showcase-lang-water-ko` | 1, 0, 0 | 1, 0, 0 |
| `showcase-ui-priorities` | 1, 1, 1 | 1, 1, 1 |
| `showcase-lang-kanji-ja` | 1, 0, 0 | 1, 0, 0 |
| `showcase-ui-pizza` | 1, 0, 0 | 0, 0, 0 |
| `showcase-lang-units-pl` | 1, 1, 0 | 1, 1, 0 |
| `showcase-lang-tea-tr` | 0, 0, 0 | 0, 2, 0 |
| `showcase-ui-alarm` | 1, 2, 2 | 1, 0, 1 |
| `holdout-tip-ru` | 1, 2, 0 | 1, 3, 0 |
| `showcase-lang-verbs-fr` | 1, 0, 0 | 0, 1, 0 |
| `showcase-ui-steps-chart` | 1, 0, 2 | 0, 7, 0 |
| `showcase-ui-price-filter` | 1, 0, 0 | 1, 0, 0 |
| `holdout-calories-fr` | 1, 0, 0 | 1, 2, 0 |
| `showcase-lang-shopping-it` | 1, 2, 0 | 1, 1, 0 |
| `holdout-reading-es` | 1, 2, 2 | 1, 0, 0 |
| `holdout-sleep-en` | 1, 0, 0 | 1, 0, 0 |
| `showcase-lang-budget-es` | 1, 0, 0 | 0, 3, 0 |
| `showcase-lang-workout-de` | 1, 0, 0 | 1, 0, 0 |
| `showcase-ui-trip-dates` | 1, 2, 0 | 0, 2, 0 |
| `holdout-words-ko` | 1, 2, 0 | 1, 0, 0 |
| `showcase-ui-onboarding` | 0, 0, 0 | 0, 0, 0 |
| `holdout-study-ja` | 1, 0, 0 | 1, 0, 0 |
| `showcase-lang-habits-uk` | 1, 0, 1 | 1, 0, 0 |
| `holdout-eggs-it` | 0, 0, 0 | 1, 1, 0 |

The look rules of `kit/README.md` that plans broke:

- Rule 1 (the main element in the first two nodes): 3 of 24 plans have none (tea-tr, onboarding, eggs-it), against 7 of 24 baselines. Tea-tr and eggs-it lose it to the text of `kind=` (K6). Onboarding is a setup in steps, which has no main element with or without `kind=`, as its baseline.
- Rule 2 (at most two typed fields on the first screen): 1 of 24 plans (power-de, 3 fields), against 4 of 24 baselines.
- Rule 3 (no typed field in a row template): 5 of 24 plans (priorities, alarm, steps-chart, reading-es, habits-uk), against 2 of 24 baselines. Alarm asks to edit the times, and steps-chart takes the steps of each day in its row. Reading-es and habits-uk use `edit=true` where the request does not ask to change rows. Prompt v0 had 8 such plans in the terra low run.
- Rule 7 (no muted stroke): no clean plan of the final run has the word `muted`. This is a search of the plan text, not a check of the surfaces.
- Rules 4, 5, 6, and 8 were not checked on the model plans. Parts get them from their templates, which kit tests check. The one hole of the final run (trip-dates) has a heading and a list with a row of an emoji and two texts, and no button, so rules 6 and 8 do not apply to its lines.

### Function

Ten clean plans of set C from the final run, across kinds. Each line compares the request with the compiled plan. Nobody ran them on a device.

1. `holdout-tip-ru` (calculator): yes. Bill, tip percent, and people, with the amount for each person. The tip and the total are not shown on their own.
2. `showcase-ui-pizza` (configurator): yes. Base, size, at most five toppings (`max=5`), and the total. The request gives no prices, so the plan uses its own.
3. `showcase-lang-budget-es` (list, chart, tabs): yes. Total of the month, a bar chart by category, and a tab to see and a tab to add. The category is free text, so a typing error makes a new bar.
4. `holdout-sleep-en` (log): yes. Bed time and wake time for each night, the hours of each night in its row, and the average of the last 7 days.
5. `showcase-lang-habits-uk` (tracker): yes. Three habits, a check for each day, and the streak of each habit up to today. The names are editable, which the request does not ask for.
6. `showcase-ui-alarm` (schedule): yes. Three doses with editable times, a switch for each, and a confirmation before a delete.
7. `showcase-lang-kanji-ja` (cards): yes. Five kanji, a tap shows the reading and the meaning, and the buttons 覚えた and まだ count.
8. `showcase-lang-tea-tr` (timer): yes. Three kinds of tea with their times and `alert=true`, which gives a sound or a vibration at the end.
9. `showcase-ui-onboarding` (setup in steps): yes. Genres, pages per day, and a summary with the button Start. Start ends the setup and starts nothing more.
10. `showcase-ui-trip-dates` (hole): partly. Two date inputs (no date range), activities with a date and a time in a sheet, and a timeline as a hand-written list of the activities between the two dates. An activity can have a date outside the trip.

The code map lists trip-dates and priorities as partial. `showcase-lang-pomodoro-zh` is served by the count of the timer, but terra wrote a hole for it (K5).

### Calls and tokens

| Model and effort of each call | Calls | Input tokens | From the cache | Output tokens | Reasoning tokens |
| --- | --- | --- | --- | --- | --- |
| `gpt-6.1-terra` low | 145 | 917286 | 878532 | 31675 | 6130 |
| `gpt-6.1-terra` medium | 53 | 331076 | 306350 | 19769 | 9476 |
| `gpt-6.1-sol` low | 77 | 492012 | 465652 | 17285 | 2446 |
| `gpt-6.1-sol` medium | 53 | 331076 | 306350 | 17175 | 5576 |
| All | 328 | 2071450 | 1956884 | 85904 | 23628 |

The budget was 700 calls with a stop mark at 600. The task used 328. The rows group the calls by their own effort, so the repairs of a medium run (effort low) are in the low rows. The two probe calls are in the low rows.

### Recommendation for the loop

- Plan step: `gpt-6.1-terra` at the effort low (`reasoningOff` of `ProviderProfile`), with prompt v1. One call, then 1 repair call at the effort low.
- A second repair: in the four terra runs it made no plan clean. Only the K1 recount makes one more plan clean at the second repair (pomodoro-zh). So the numbers support a second repair only after the decision on K1.
- Two calls in parallel: not needed for the clean rate. On the 15 requests that ran twice with v1, one run gave 13 clean at the first try and the better of the two runs gave 15. This estimate comes from 15 cases and one pair of runs.
- To today's loop: each `outside(...)` reply (games, 3D, drawing, picture, device), and each plan that is not clean after its repairs.
- A clean plan with a hole can be a dead screen (K5). The kit does not detect it yet.
- Before the loop is built, the controller rules on K1. Each repair that deletes a line can fail on it.

### What the numbers do not show

- One run of each setting. The same prompt on the same 15 requests changed 2 results between two runs. So the gain from v0 to v1 (19 to 23 of 25 at the first try) is not proven.
- Prompt v1 was tuned on the failures of the same sets, and then measured on them. No new requests tested it.
- The times come from this computer, with 4 calls in parallel and a warm cache (98.5% of the input tokens of the final run). A cold call from the phone can differ.
- The path statistics leave out the plans that are not clean. With 25 cases the 90th percentile hides the two slowest, so the tables give the maximum too.
- Assumption: the widget brief is empty in each task text. The saved reports of `final-30d43aa` and `showcase1` hold no brief. Test it: log the task text of the app for one corpus request.
- Assumption: the task text of a dialogue screen is the request line, the host line, and an empty brief. The app has no dialogue path for plans yet. Test it when the loop builds that text.
- `outside("device")` was checked only by `holdout-tilt-ru`, which is also a game.
- Look numbers come from the compiled definition, not from a screen. The function check reads plans. No plan ran on a device.

## Fresh requests (prompt v1)

Task H1 measured prompt v1, unchanged, on 28 requests that no earlier run and no example of the prompt used.
The scope is `.superpowers/sdd/2026-11-19-app-file-loop/h1-decisions.md`.
The run ran on `a7b9310` with prompt v1, `gpt-6.1-terra`, the effort low, and repairs as in A0 (up to 2, at the effort low). Each of the 28 records has the prompt hash `c3d65bf76c7b`.
Raw replies and records are in `evidence/modelcheck/fresh-terra-low/`, which Git does not keep. Each call completed.

### What ran

- Set E is `kit/test/fixtures/fresh-requests.json`. It holds 14 requests for an app in 13 languages, 6 Russian requests of a voice dialogue, and 8 requests that expect `outside(...)`. The controller wrote the requests and their expectations.
- A dialogue request gets the host line of the section "Dialogue" under the request line, as set D. The widget brief is empty, as in A0.
- `e-seasons-ru` ran first and alone, to check the load path. Then `STOP_AT=398 bun kit/modelcheck.js run fresh-terra-low E gpt-6.1-terra low` ran the other 27 requests, with 4 calls in parallel.

### Numbers: prompt v1, `gpt-6.1-terra`, effort low

| Measure | 14 app requests | 6 dialogue requests | Both (20) |
| --- | --- | --- | --- |
| Clean at the first try | 14 (100%) | 6 (100%) | 20 (100%) |
| Clean after 1 repair | 0 | 0 | 0 |
| Clean after 2 repairs | 0 | 0 | 0 |
| Not clean | 0 | 0 | 0 |
| Clean under the device bars (compile at most 2300 ms, start state at most 49152 bytes) | 14 of 14 | 6 of 6 | 20 of 20 |
| First call: median, 90th percentile, maximum | 4.528 s, 5.544 s, 7.421 s (`e-score-zh`) | 8.659 s, 15.247 s, 15.247 s (`e-seasons-ru`) | 4.847 s, 8.8 s, 15.247 s |
| Time to the first text: median | 2.347 s | 3.457 s | 2.419 s |
| Path to a clean plan: median, 90th percentile | 4.535 s, 5.55 s | 8.685 s, 15.3 s | 4.885 s, 8.82 s |
| Output tokens of the first call: median (reasoning) | 234 (9) | 582.5 (100.5) | 261 (29) |

- The source of each number is `bun kit/modelcheck.js table fresh-terra-low --detail`, except the lines below that name a scratch count.
- No plan had a fault of the kit, so no repair call ran. The path to a clean plan is the first call and one compile.
- No clean plan crossed a device bar or had a form that the host refuses.
- `e-seasons-ru` ran alone before the run, and its call had no cached input tokens (0 of 6457). It is the maximum of the dialogue times. Without it (5 requests, a scratch count), the dialogue median is 8.518 s, the maximum is 12.82 s (`e-lever-ru`), and the first text median is 2.408 s.
- The 90th percentile of 6 values is the maximum.
- Compile time of the clean plans (a scratch count): median 11 ms, maximum 68.6 ms (`e-percent-ru`, a grid of 100 cells). Start state: median 131.5 bytes, maximum 845 bytes (`e-capitals-it`, ten cards).
- The run took 28 calls: 176528 input tokens (167616 from the cache, 95.0%), 6711 output tokens (1087 reasoning).

### Routing

8 of 8 requests that expect `outside(...)` got it. None of the 20 requests that expect a plan got `outside(...)`. An `outside(...)` reply took a median of 1.363 s (maximum 2.115 s).

| Request | Reply | The line of the section "System" that rules it out |
| --- | --- | --- |
| `e-tictactoe-en` | `outside("game")` | A known game with rules for a whole board. |
| `e-tetris-fr` | `outside("game")` | A known game. Its pieces also move by themselves. |
| `e-dice-ja` | `outside("game")` | No random value. The prompt has no word for this limit, so the word is a choice of the model. |
| `e-sketch-es` | `outside("drawing")` | No canvas that a person draws on. |
| `e-catphoto-ru` | `outside("picture")` | No picture or photo. |
| `e-whereami-de` | `outside("device")` | No location and no map. |
| `e-compass-en` | `outside("device")` | No sensors. |
| `e-weather-en` | `outside("device")` | No live value from the internet. |

The run had no routing miss in either direction, so no expectation needed a judgment. Each of the 8 expectations agrees with the prompt and `kit/README.md`: each request needs one thing that the section "System" names.

### Faults and repairs

No plan of the run had a fault. No repair call ran, so this run gives no number for the repair step and no new evidence on K1 or K3.

### Holes

One plan has a hole: `e-score-zh`, a scoreboard (`evidence/modelcheck/fresh-terra-low/e-score-zh.0.txt`).

- The hole shows two cards, each with a Metric of the score, a minus button, and a plus button, and one reset button under them. Each button changes a state value, so the screen is not dead as in K5.
- The kit gives this screen with no hole. Two `counter` needs and one `button` need with `[set($/teamA, 0), set($/teamB, 0)]` compile with no fault and no note (a scratch probe with no call). The counter version also has no main element.
- The hole cost time. Its first call took 7.421 s and 384 output tokens, the maximum of the apps, against a median of 4.528 s and 234 tokens.
- This is the second hole in place of a part, after K5 (`showcase-lang-pomodoro-zh`, the count of a timer). Two cases do not make a pattern.

### Function

Each line compares the request with the compiled plan. Nobody ran them on a device.
Three lists start empty, and the example of the loan tests only 0% interest. So a scratch probe with no call compiled `e-bbq-pt`, `e-mood-ru`, and `e-pages-ja` with three rows each, and `e-loan-es` as it is. Each value was right: for example, 526,46 a month and 12.634,96 in all for 12000 at 5% over 2 years, and a chart of the last 7 days that left out a row of 20 days ago.

1. `e-bmi-en` (calculator): yes. Height and weight give the BMI and its range as a text (under 18.5, 25, and 30).
2. `e-packing-de` (list): yes. Each item has a category of five fixed options and a check "Im Koffer", and the list is in groups by category. The hero counts all items, not the packed ones.
3. `e-plank-fr` (timer): yes. Five rounds of 45 s with 15 s of rest. The kit also runs a rest after the last round (finding 3).
4. `e-loan-es` (calculator): yes. The monthly payment of an annuity, with a separate formula for 0% interest, and the total paid.
5. `e-mood-ru` (log, chart): yes. A date, a score from 1 to 5, and a note for each entry, and a line chart of the last 7 days.
6. `e-bbq-pt` (list): partly. Each row shows what the person paid, the equal share, what the person owes, and what the person gets back. The last three values have no names on the screen (finding 2).
7. `e-pages-ja` (goal): yes. A goal of pages, entries of the date and the pages, and a ring of the pages of this month against the goal.
8. `e-capitals-it` (cards): yes. Ten cards of a country and its capital, a tap shows the capital, and "La sapevo" sets the check of the card.
9. `e-timetable-tr` (list): yes. A day of seven options, a time, and a lesson name, in groups by day. In a day the rows keep the order of entry, not the order of time.
10. `e-fuel-pl` (calculator): yes. Liters on 100 km and the cost of the trip.
11. `e-cafe-ko` (configurator): yes. Three drinks, two sizes, a toggle for one extra shot, and the price. The request gives no prices, so the plan uses its own and lists them in a text line. The price shows two decimals of won ("4,000.00 원", finding 4).
12. `e-vacation-en` (countdown): yes. A date input that starts today, and the days left, never below 0.
13. `e-gifts-uk` (list): yes. To whom, what, the price, and a check for bought, and the sum of the prices of the gifts that are not bought.
14. `e-score-zh` (hole): yes. Two scores with plus and minus buttons and one reset. A score can go below 0. A hole where two counters serve (see "Holes").
15. `e-seasons-ru` (dialogue): yes. The Earth at four places of its orbit with an axis of one fixed tilt, a choice of the season, a card, a lesson of 3 steps, and a question. In the summer of the north the axis leans to the Sun.
16. `e-lever-ru` (dialogue): partly. Inputs of the force and the arm move the arrow of the force, and a text says which side is heavier against a load of 10 N at 80. The beam does not tilt, and the arm has no unit.
17. `e-percent-ru` (dialogue): yes. A grid of 100 cells that fills to the input, a card, a lesson of 2 steps, and a question. The example of the card stays at 25% when the input changes.
18. `e-japanese-ru` (dialogue): partly. A choice from 1 to 10 shows the kanji and its reading, and a lesson of 3 steps gives all ten. The check is one question, about five.
19. `e-times7-ru` (dialogue): yes. A deck of ten tasks from 7 × 1 to 7 × 10 with a typed answer, a check, and points.
20. `e-atom-ru` (dialogue): yes. A scheme of a helium atom (two protons, two neutrons, two electrons), a choice that marks the nucleus or the electrons, a card for the chosen part, a lesson of 2 steps, and a question.

Of the 20 clean plans: 17 yes, 3 partly, 0 no.

### Look

The look numbers come from `score_app`, as in A0. No baseline exists for these requests.

| Request | Hero, fields on the first screen, fields in rows | Compile | Start state |
| --- | --- | --- | --- |
| `e-bmi-en` | 1, 2, 0 | 9.3 ms | 80 bytes |
| `e-packing-de` | 1, 1, 0 | 37.3 ms | 106 bytes |
| `e-plank-fr` | 1, 0, 0 | 9.4 ms | 231 bytes |
| `e-loan-es` | 1, 2, 0 | 1.8 ms | 91 bytes |
| `e-mood-ru` | 1, 2, 0 | 4.8 ms | 152 bytes |
| `e-bbq-pt` | 1, 2, 0 | 4.8 ms | 104 bytes |
| `e-pages-ja` | 1, 3, 0 | 39.5 ms | 135 bytes |
| `e-capitals-it` | 1, 0, 0 | 6.5 ms | 845 bytes |
| `e-timetable-tr` | 1, 2, 0 | 12.6 ms | 128 bytes |
| `e-fuel-pl` | 1, 3, 0 | 2.4 ms | 95 bytes |
| `e-cafe-ko` | 1, 0, 0 | 6.4 ms | 100 bytes |
| `e-vacation-en` | 1, 1, 0 | 1.9 ms | 57 bytes |
| `e-gifts-uk` | 1, 0, 0 | 8.7 ms | 113 bytes |
| `e-score-zh` | 0, 0, 0 | 14.8 ms | 21 bytes |
| `e-seasons-ru` | 1, 0, 0 | 51.9 ms | 480 bytes |
| `e-lever-ru` | 1, 2, 0 | 22.1 ms | 450 bytes |
| `e-percent-ru` | 1, 1, 0 | 68.6 ms | 336 bytes |
| `e-japanese-ru` | 1, 0, 0 | 31.3 ms | 442 bytes |
| `e-times7-ru` | 1, 0, 0 | 16.9 ms | 646 bytes |
| `e-atom-ru` | 1, 0, 0 | 20.3 ms | 628 bytes |

- Rule 1 (the main element in the first two nodes): 1 of 20 plans has none, `e-score-zh` (the hole).
- Rule 2 (at most two typed fields on the first screen): 2 of 20 plans break it. `e-fuel-pl` has the three inputs that the request names. `e-pages-ja` has the goal and the two fields of the add form.
- Rule 3 (no typed field in a row template): no plan breaks it.
- Rule 7 (no muted stroke): no line or outline is muted. One text is muted (`e-japanese-ru`), which the prompt permits. This is a search of the plan text.
- Rules 4, 5, 6, and 8 were not checked, as in A0.

### Findings about the kit and the prompt

The task changed nothing in `kit/src` and nothing in the prompt.

1. **A hole where two counters serve.** See "Holes". The candidate rule of K5 (a hole is never for a value that a part gives) can cover this case too, if it names the counter. Evidence: `evidence/modelcheck/fresh-terra-low/e-score-zh.0.txt`.
2. **A value of `show=` has no name on the screen.** In `e-bbq-pt` the row template of the compiled plan holds three badges with an empty text (`pessoas_parte_value`, `pessoas_deve_value`, `pessoas_recebe_value`) beside the money that the person paid. A person sees four amounts in each row and no word for any of them. With three people who paid 100, 20, and 0, the row of the first one holds 100, 40, 0, and 60 (the scratch probe of "Function"). The section "System" and `kit/README.md` give `show=` no label, and `labels=` names only fields.
   - Unresolved decision: the remedy. A label for a value of `show=` changes `kit/src`. A prompt rule (for example, one value of text that names itself, such as "deve 20,00") changes the prompt. Both are outside this task. The decision affects each list with more than one value of `show=`.
   - Evidence: `evidence/modelcheck/fresh-terra-low/e-bbq-pt.0.txt`.
3. **A timer with rest also rests after its last round.** This is the design of the kit: `App.tick` (`kit/src/core.js` lines 593 to 596) starts the rest phase after each work phase, and the kit test "run: the short plan of the device test ends after its second rest" (`kit/test/timers.test.js`) fixes this end. It does not fit a request for rest between the sets: `e-plank-fr` asks for 15 seconds of rest between the sets, so its timer ends 15 s after the fifth set. The plan uses `rounds=5, rest=15`, as the section "System" says. Evidence: `evidence/modelcheck/fresh-terra-low/e-plank-fr.0.txt`.
4. **A money figure has two decimals unless `decimals=` gives a different number.** `e-cafe-ko` shows "4,000.00 원", and the won has no smaller unit. The prompt does not say that such a currency takes `decimals=0`. Evidence: `evidence/modelcheck/fresh-terra-low/e-cafe-ko.0.txt`.
5. **The section "Dialogue" asks for one question in a lesson.** `e-japanese-ru` asks to learn ten numbers "with a check", and the plan checks one of them. A deck with `answer=`, as in `e-times7-ru`, checks each row. The model did what the section says: "also a lesson in steps and a question". Evidence: `evidence/modelcheck/fresh-terra-low/e-japanese-ru.0.txt`.

### A0 beside this run

| Measure (prompt v1, terra, low) | A0 set C (25) | H1 apps (14) | A0 set D (8) | H1 dialogue (6) |
| --- | --- | --- | --- | --- |
| Clean at the first try | 23 (92%) | 14 (100%) | 8 (100%) | 6 (100%) |
| Clean after at most 2 repairs | 24 (96%) | 14 (100%) | 8 (100%) | 6 (100%) |
| First call median | 3.974 s | 4.528 s | 8.794 s | 8.659 s |
| First call maximum | 15.531 s | 7.421 s | 13.859 s | 15.247 s |
| First text median | 1.678 s | 2.347 s | 3.301 s | 3.457 s |
| Output tokens median (reasoning) | 237 (32) | 234 (9) | 589 (107.5) | 582.5 (100.5) |
| `outside(...)` where expected | 20 of 20 | 8 of 8 | none expected | none expected |
| An `outside(...)` reply: median, maximum | 1.327 s, 2.627 s | 1.363 s, 2.115 s | | |
| Input tokens from the cache, whole run | 98.5% | 95.0% | the same run as set C | the same run as the apps |

The A0 first text medians come from the same table command on `final-terra-low`. The A0 results above give them rounded (1.68 s and 3.3 s).

What the difference means:

- The clean rate transfers. Prompt v1 made 20 of 20 fresh plans clean at the first try, against 31 of 33 on the sets that tuned it. With 20 requests, the 95% interval of the rate starts at about 83% (Clopper-Pearson). So this run does not show that fresh requests do better than 92%. It shows no sign that the tuning on sets C and D made the A0 rate too high.
- The routing transfers: 8 of 8, and no wrong `outside(...)`.
- The app times are a little slower: the first call median by 0.55 s and the first text median by 0.67 s, with the same output tokens. The dialogue median is about the same. One run of 14 and 6 requests cannot separate this from the variation of the API.
- Assumption: set E is easier than set C. Basis: no request of set E needs a hole, and set C has two partial cases (trip-dates and priorities). Test it with fresh requests that need a hole or a part at the edge of the kit.

### Calls and tokens

| Model and effort of each call | Calls | Input tokens | From the cache | Output tokens | Reasoning tokens |
| --- | --- | --- | --- | --- | --- |
| `gpt-6.1-terra` low | 28 | 176528 | 167616 | 6711 | 1087 |

The budget was 70 calls. The task used 28: 28 first calls and no repair. `evidence/modelcheck/spend.json` went from 328 to 356, and its log has 28 entries of the label. `LIMIT` of `kit/modelcheck.js` is now 398, so the count stops at the budget of this task.

### What the numbers do not show

- One run of 28 requests. A result of 20 of 20 has a 95% interval of about 83% to 100%.
- No repair ran, so this run says nothing about the repair step, K1, or K3.
- Each request that expects `outside(...)` needs one thing that the prompt names. The set has no case at the border, such as a small game that the lines can serve, or a request that names a picture and works without one.
- The controller wrote the requests and their expectations. Each request is short and names its inputs. Requests of real people can be longer and less exact.
- `e-seasons-ru` ran alone with a cold cache. It gives the dialogue maximum, and the 90th percentile of 6 values is the maximum.
- The times come from this computer, with 4 calls in parallel and a warm cache for 27 of 28 calls. A cold call from the phone can differ.
- The look numbers come from the compiled definition, not from a screen. The function check reads plans. No plan ran on a device.
- The assumptions of A0 stay: the widget brief is empty, and the task text of a dialogue screen is the request line, the host line, and an empty brief.
