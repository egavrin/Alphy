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

## Prompt v3 after the kit changes

Task P2 measured the plan prompt and the kit as the plan step of the app will use them.
The scope is `.superpowers/sdd/2026-11-19-app-file-loop/p2-decisions.md`.
The run ran on `c9100af` with `gpt-6.1-terra`, the effort low, and at most 1 repair call at the effort low, as the loop of the app does. The label is `v3-terra-low`.
Each of the 81 records has the prompt hash `c8cf5cf8f837` and `repairs: 1`. Each call completed.
Raw replies and records are in `evidence/modelcheck/v3-terra-low/`, which Git does not keep. Each reply of each call is kept whole as `<id>.<step>.txt`.

### What changed and what ran

- Since the final run of A0, task K1 changed the kit and the prompt (`665049851eca`): no `hole` in the prompt, a sixth word `outside("other")`, and fewer rules. A look option that cannot apply is a note, a repair may delete a name and write it again, and a round timer ends after its last work phase. Task L1 changed the look of the parts.
- This task added two sentences to the prompt. The new hash is `c8cf5cf8f837`. The section "System" is 20586 bytes (21881 bytes in v1).
  - The paragraph of `entries`: "labels= also names each value of show=, and the row shows the name with the value, such as "Total: 12"." The kit writes `"Name: " + amount(formula, 2)` when `labels=` names the value (`kit/src/parts.js` line 262, L1 item 2). A scratch compile with `labels={owes: "Owes"}` gave the row texts "Owes: 0" and "Owes: 40".
  - The section "Dialogue": "When the person asks to learn a set of things with a check (to count to ten in a language, a multiplication table), the check is a deck with answer= or back= over entries with hidden=true and a row for each thing, not one question." A scratch compile of each of the two decks over hidden entries gave no fault and no note.
- `kit/modelcheck.js` takes the setting `REPAIRS` (default 2), and the worst case of a run counts it. `LIMIT` is 490. The kit test "modelcheck (P2)" covers the setting, the worst case, and a record with 1 repair.
- Sets C (45), D (8), and E (28) ran one time: 81 requests. `holdout-tip-ru` ran first and alone to check the path. Its call had no cached input tokens (0 of 5940).
- Then `STOP_AT=490 REPAIRS=1 bun kit/modelcheck.js run v3-terra-low CDE gpt-6.1-terra low` ran the other 80 requests, with 4 calls in parallel. The run took 2 minutes (17:17:57 to 17:19:56 UTC).
- The task text of each record is the same as the task text of its id in `final-terra-low` or `fresh-terra-low`. The one difference is the new line of the section "Dialogue" in the 14 dialogue requests (a scratch check).

### Numbers: prompt v3, `gpt-6.1-terra`, effort low, at most 1 repair

| Measure | Set C (25 servable) | Set D (8) | Set E apps (14) | Set E dialogue (6) |
| --- | --- | --- | --- | --- |
| Clean at the first try | 24 (96%) | 8 (100%) | 14 (100%) | 5 (83%) |
| Clean after 1 repair | 0 | 0 | 0 | 1: `e-japanese-ru` |
| Not clean | 1: `showcase-lang-pomodoro-zh`, which got `outside("other")` | 0 | 0 | 0 |
| Clean under the device bars, with no form that the host refuses | 24 of 25 | 8 of 8 | 14 of 14 | 6 of 6 |
| First call: median, 90th percentile, maximum | 5.135 s, 8.711 s, 10.442 s (`showcase-lang-units-pl`) | 9.913 s, 12.869 s, 12.869 s (`d-pythagoras`) | 4.463 s, 6.675 s, 7.104 s (`e-mood-ru`) | 11.076 s, 15.874 s, 15.874 s (`e-lever-ru`) |
| Time to the first text: median | 2.773 s | 4.392 s | 1.975 s | 3.73 s |
| Path to a clean plan: median, 90th percentile | 4.87 s, 8.74 s | 9.945 s, 12.89 s | 4.465 s, 6.68 s | 11.915 s, 15.89 s |
| Output tokens of the first call: median (reasoning) | 223 (32) | 551.5 (119.5) | 237 (0) | 655.5 (68) |
| Input tokens of the first call: median | 5948 | 6171 | 5916.5 | 6161 |
| Input tokens from the cache | 94.5% | 95.0% | 99.0% | 95.0% of the first calls, 93.6% with the repair |

- The source of each number is `bun kit/modelcheck.js table v3-terra-low --detail`, except the input tokens and the cache, which come from a scratch count over the same records.
- Without the cold call of `holdout-tip-ru` (24 requests of set C): the first call median is 4.842 s, the first text median is 2.709 s, and 98.4% of the input tokens come from the cache. The 90th percentile and the maximum do not change.
- The 90th percentile of 6 or 8 values is the maximum.
- An `outside(...)` reply took a median of 2.566 s. The maximum, 7.809 s, is the wrong `outside("other")` of `showcase-lang-pomodoro-zh`.
- The dialogue input has 220 to 250 tokens more than an app input: the line of the section "Dialogue" is in the task text, after the request, so the cache does not hold it.

### Routing

28 of 28 requests that expect `outside(...)` got it. Each got the same word as in its earlier run.

| Word | Count of the 28 | Requests |
| --- | --- | --- |
| `game` | 11 | `holdout-bricks-en`, `holdout-dodge-es`, `holdout-jump3d-de`, `holdout-moles-zh`, `showcase-3d-dice`, `showcase-game-2048`, `showcase-game-memory`, `showcase-game-snake`, `e-tictactoe-en`, `e-dice-ja`, `e-tetris-fr` |
| `picture` | 6 | `showcase-image-flashcards`, `showcase-image-menu`, `showcase-image-plant`, `showcase-image-recipe`, `showcase-image-story`, `e-catphoto-ru` |
| `3d` | 5 | `showcase-3d-dna`, `showcase-3d-globe`, `showcase-3d-maze`, `showcase-3d-molecule`, `showcase-3d-solar` |
| `device` | 4 | `holdout-tilt-ru`, `e-whereami-de`, `e-compass-en`, `e-weather-en` |
| `drawing` | 2 | `showcase-game-drawing`, `e-sketch-es` |
| `other` | 0 (the 1 reply with `other` is a plan request, below) | |

1 of 53 requests that expect a plan got `outside(...)`: `showcase-lang-pomodoro-zh`, with `outside("other")`. No request with a device function got a plan.

The word `other` came 1 time in 81 replies, and that reply is the miss:

- The request asks for a timer of 25 minutes of focus and 5 minutes of rest, the count of the pomodoros of today, and a statistics page.
- The section "System" says that `$/NAME` of a timer is the count of the finished work phases, and the code map lists the case as served.
- A plan by hand compiles with no fault and no note: a timer with `rounds=4, rest=300`, two figures of `$/focus`, and tabs for the statistics (a scratch probe with no call). The count is the count since the app opened, not a record of each day.
- Evidence: `evidence/modelcheck/v3-terra-low/showcase-lang-pomodoro-zh.0.txt` (the one line `outside("other")`). This is one reply of one run.

### The three requests whose earlier plans used a hole

| Request | Earlier plan | Plan now | Does it serve the request? |
| --- | --- | --- | --- |
| `showcase-ui-trip-dates` | A hole: a hand-written list of the activities between the two dates | Two date inputs (today and two days later), entries of activities with a date, a time, and a name in a sheet, and a drawing of a timeline: a dot, "day · time", and the name of each activity between the two dates, sorted by day and time | Partly, as before. The two dates are two inputs, not a date range, and an activity outside the dates is in the list but not on the timeline. The line name "timeline list" no longer shows (M1 F8), because the plan has no hole. |
| `e-score-zh` | A hole: two cards with a score and two buttons each, and a reset button | A drawing of the two team names and their scores, a plus button and a minus button for each team, and a reset button | Yes. The drawing is now the main element. A score can go below 0, as before. |
| `showcase-lang-pomodoro-zh` | A hole that recorded each finished phase by date, not clean after 2 repairs | `outside("other")` | No. The request goes to today's loop (see "Routing"). |

### Holes

No reply and no final plan of the run has a need of kind `hole` (a search of each reply and each plan).

### Faults, repairs, and notes

One fault came in the run. One repair call ran, and it made the plan clean.

| Fault | Count | What the repair did |
| --- | --- | --- |
| "need visual: shapes: each needs a list or a count: each($/rows, shape) or each(5, shape)" | 1 (`e-japanese-ru`, step 0) | The drawing used `each($/number, ...)`, and `$/number` is a choice whose value is a text from "1" to "10". The repair wrote `each(number($/number), ...)` and nothing else. The plan was clean at step 1. The repair call took 2.7 s and 66 output tokens. |

Each note is at step 0 of a plan that is clean:

| Note (names removed) | Count | Plans |
| --- | --- | --- |
| "the host's countdown shows its own buttons and alert, so start=, pause=, reset=, done= do not show" | 3 | `showcase-lang-tea-tr`, `holdout-eggs-it`, `d-breathing` |
| "the part draws a Slider, which shows no icon, so the kit left out icon="Percent"" | 2 | `holdout-tip-ru`, `e-loan-es` |

### Outcomes that differ from the earlier final runs

The earlier runs are `final-terra-low` (sets C and D) and `fresh-terra-low` (set E). Both used prompt v1, the kit of that time, and up to 2 repairs.

| Request | Earlier | Now | Cause |
| --- | --- | --- | --- |
| `showcase-lang-workout-de` | Clean after 1 repair | Clean at the first try | The earlier fault (an emoji on a figure that is not the main one) is a note in the kit of K1. The earlier first reply compiles clean with the kit of now too. |
| `showcase-lang-pomodoro-zh` | Not clean after 2 repairs | `outside("other")` | See "Routing". |
| `e-japanese-ru` | Clean at the first try | Clean after 1 repair | The new sentence of the section "Dialogue" changed the plan: it now has hidden entries of the ten numbers and a deck. Its drawing read the text of a choice as a count (see "Faults, repairs, and notes"). |

Each other record of the 81 has the same outcome as before. Each of the 28 `outside(...)` replies has the same word.

### Function

Each line compares the request with the final plan. Nobody ran them on a device.
These 11 plans are new in kind: their need kinds differ from the fixture of their request (`kit/test/fixtures/model/<id>.plan`).

1. `e-japanese-ru` (dialogue): yes. A choice from 1 to 10 draws that many circles, a card, a lesson of 3 steps with the ten readings and their variants, and a deck of cards over the ten numbers: the front is the digit, and the back is the reading. The check now covers each number, which was the target of the new sentence.
2. `d-spanish` (dialogue): yes. A card, a lesson of the five greetings, and a deck over the five with a typed answer. The check needs the exact text with its accents, because the deck compares only after `lower` and `trim` (`kit/src/parts.js` line 348): "buenos dias" is wrong. The plan has no drawing. The new sentence reached this request too ("then give me a small test").
3. `d-heart` (dialogue): yes. A button goes through four phases, the drawing marks the path of the phase, and a card explains it. A lesson of 2 steps and a question follow.
4. `e-lever-ru` (dialogue): partly. The beam now tilts when the force goes above or below the load of 5. The two arms have a fixed length, so the drawing does not show the rule that the lesson and the question teach: a force farther from the support turns more.
5. `d-breathing` (dialogue): partly. One timer of 10 s with an alert, and a text: inhale from 10 to 6, exhale from 6 to 0. The screen names no phase, and each breath needs a new start. See finding 4.
6. `showcase-lang-units-pl`: yes. The ingredient, the amount, and the unit give grams, cups, spoons, and milliliters (1 cup is 240 ml, a cup of flour is 120 g, of sugar 200 g, of water 240 g). The example of each figure is right: 120 g of flour is 1 cup, 16 spoons, and 240 ml.
7. `showcase-lang-workout-de`: yes. 8 rounds of 40 s with 20 s of rest, a ring of the finished rounds against 8, and a figure of the finished rounds.
8. `showcase-ui-onboarding`: yes. Genres, pages per day, and a summary text in three steps, with the Start button.
9. `showcase-ui-trip-dates`: partly (see the hole table).
10. `e-cafe-ko`: yes. Drink, size, extra shots, and the price with `decimals=0` and the unit 원. The earlier text line of the prices is gone, so a person sees a price only for the chosen drink.
11. `e-score-zh`: yes (see the hole table).

The other 41 plans have the same need kinds as their fixtures. A scratch count compared each one with its fixture: the option keys of each need, the fields, the rows, and the values of `show=` of each list, and the count of state lines.
"Same shape" below means that each of these is equal. A plan that differs got a full read, and so did each plan with a known item. The earlier verdict is the one of A0 or H1 for the fixture, where one exists.

| Plan | One line |
| --- | --- |
| `holdout-power-de` | Same shape. Not read in full. |
| `showcase-lang-water-ko` | Same shape. Not read in full. |
| `showcase-ui-priorities` | Same shape, plus `countText=` on the list. Not read in full. |
| `showcase-lang-kanji-ja` | Same shape: the hidden list no longer writes `add=false` and `remove=false`, and the deck is the same. A0: yes. |
| `showcase-ui-pizza` | Same shape, plus `note=` on the main figure. A0: yes. |
| `showcase-lang-tea-tr` | Same shape. A0: yes. |
| `showcase-ui-alarm` | Read: yes. Three doses with editable times, a switch for each, and a confirmation before a delete. The rows have no name field now, which the request does not ask for, so the name field that M1 F1 showed one letter wide is gone. |
| `holdout-tip-ru` | Same shape, except `icon=` on the tip slider, which is a note now. A0: yes. |
| `showcase-lang-verbs-fr` | Same shape: the hidden list no longer writes `add=false` and `remove=false`, and the deck no longer writes `again=`. Not read in full. |
| `showcase-ui-steps-chart` | Same shape. Not read in full. |
| `showcase-ui-price-filter` | Same shape. Not read in full. |
| `holdout-calories-fr` | Same shape. Not read in full. |
| `showcase-lang-shopping-it` | Same shape. Not read in full. |
| `holdout-reading-es` | Same shape. Not read in full. |
| `holdout-sleep-en` | Same shape, with a name on its value of `show=`, as before. A0: yes. |
| `showcase-lang-budget-es` | Same shape, without `kind="bar"` on the chart (bars are the default). A0: yes. |
| `holdout-words-ko` | Same shape. Not read in full. |
| `holdout-study-ja` | Same shape. Not read in full. |
| `showcase-lang-habits-uk` | Read: yes. Three habits, a check for each day, and "Серія днів поспіль" for each habit: the longest run of checked days of the week (`streak` of `kit/src/core.js` line 105). The fixture cut the week at today, and this plan does not, so a checked day after today counts too. Its first call wrote 437 output tokens against 845. |
| `holdout-eggs-it` | Same shape. Not read in full. |
| `d-pythagoras` | Same shape. Not read in full. |
| `d-interest` | Same shape. Not read in full. |
| `d-moon` | Same shape. Not read in full. |
| `d-rainbow` | Same shape. Not read in full. |
| `d-fractions` | Same shape. Not read in full. |
| `e-loan-es` | Read: yes. The monthly payment of an annuity, with a separate formula for 0% interest, and the total paid. A state line now holds the payment, and both figures read it. `icon=` on the percent slider is a note. |
| `e-packing-de` | Same shape. H1: yes. |
| `e-vacation-en` | Same shape. H1: yes. |
| `e-timetable-tr` | Read: yes. `sort=$saat` now orders the lessons of each day by time, which H1 named as a gap. |
| `e-fuel-pl` | Same shape, plus `max=` on the three inputs. H1: yes. |
| `e-pages-ja` | Same shape. H1: yes. |
| `e-mood-ru` | Same shape. H1: yes. |
| `e-bbq-pt` | Read: yes now (H1: partly). `labels=` names the value of `show=` ("Acerto"), which was the target of the other new sentence. With three people who paid 100, 20, and 0, the rows show "Acerto: Recebe 60,00", "Acerto: Deve pagar 20,00", and "Acerto: Deve pagar 40,00" (a scratch compile of the plan with three rows). The paid amount of each row still has no name, because `labels=` names a field only in the add form. |
| `e-plank-fr` | Read: yes now. The plan is the same as before (`rounds=5, rest=15`). The kit of K1 ends the timer after the fifth round, at 285 s (H1 finding 3). |
| `e-capitals-it` | Read: yes. Ten cards of a country and its capital in a hidden list, and "La sapevo" and "Non la sapevo" count. The deck no longer has `mark=`, so no card keeps a check of its own. |
| `e-bmi-en` | Same shape. H1: yes. |
| `e-gifts-uk` | Same shape. H1: yes. |
| `e-times7-ru` | Read: yes. A deck of ten tasks from 7 × 1 to 7 × 10 with a typed answer, as before. |
| `e-percent-ru` | Same shape. H1: yes. |
| `e-seasons-ru` | Read: yes. The Earth at four places of its orbit with an axis of one fixed tilt, a choice of the season, a card, a lesson of 3 steps, and a question. In the summer of the north the axis leans to the Sun. The two state lines of the fixture are gone. |
| `e-atom-ru` | Same shape. H1: yes. |

Of the 52 plans: 11 are new in kind (8 yes, 3 partly). Of the 41 with the same need kinds, 9 got a full read (each yes), and 32 have the same shape or one of the small option differences that the table names. `showcase-lang-pomodoro-zh` has no plan.

### Findings about the kit and the prompt

The task changed nothing in `kit/src`. Each candidate sentence below did not run. The controller decides.

1. **`outside("other")` for a request that the lines serve.** See "Routing". Candidate sentence for the section "System": "When the lines give the main thing of a request but not each detail, such as a record of each day, plan the main thing: outside("other") is only for a main thing that no kind gives." Evidence: `evidence/modelcheck/v3-terra-low/showcase-lang-pomodoro-zh.0.txt`. One reply of one run.
2. **The value of a choice is text, and a drawing counted with it.** The fault and its repair are in "Faults, repairs, and notes". Candidate sentence: "The value of a choice is text: a formula that counts with it reads number($/NAME)." Evidence: `evidence/modelcheck/v3-terra-low/e-japanese-ru.0.txt` and `.1.txt`. One case, and one repair fixed it.
3. **Button texts on a timer of one phase with an alert.** Three plans gave `start=`, `pause=`, `reset=`, and `done=` to a timer that the host draws with its own buttons. Each is a note, and each plan is clean. Candidate sentence: "start=, pause=, reset=, and done= are for a timer with rounds or rest: a timer with alert=true and one phase shows the buttons of the host." Evidence: `evidence/modelcheck/v3-terra-low/showcase-lang-tea-tr.0.txt`, `holdout-eggs-it.0.txt`, and `d-breathing.0.txt`.
4. **A breathing timer and the end of a round timer.** The kit of K1 ends a timer with rounds after the work phase of its last round (`kit/README.md`, section "Parts", `timer`). A round timer starts and ends on its work phase. So an inhale of 4 s as the work phase and an exhale of 6 s as the rest has no form that ends on the exhale: `rounds=5, rest=6` gives five inhales and four exhales.
   - The fixture `kit/test/fixtures/model/d-breathing.plan` (prompt v1) has `rounds=1, rest=6`. By that rule its timer now counts only the inhale of 4 s, with no rest phase: with one round, the first work phase is the last one (`kit/src/core.js` lines 596 to 600). This comes from a reading of the code, not from a run.
   - The new plan avoids it with one timer of 10 s and a text, which names no phase on the screen (function line 5). Evidence: `evidence/modelcheck/v3-terra-low/d-breathing.0.txt`.
5. **The paid amount of a row has no name.** In `e-bbq-pt`, `labels=` names `pago` only in the add form. The row shows the number with no word beside the named value of `show=`. Record C of L1 keeps such rows as they are. Evidence: `evidence/modelcheck/v3-terra-low/e-bbq-pt.0.txt`.

### Prompt v1 runs beside this run

| Measure | C earlier | C now | D earlier | D now | E apps earlier | E apps now | E dialogue earlier | E dialogue now |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Clean at the first try (recorded) | 23 | 24 | 8 | 8 | 14 | 14 | 6 | 5 |
| Earlier first replies with the kit of now (recount, no call) | 24 | | 8 | | 14 | | 6 | |
| Clean after at most 1 repair | 24 | 24 | 8 | 8 | 14 | 14 | 6 | 6 |
| First call median | 3.974 s | 5.135 s | 8.794 s | 9.913 s | 4.528 s | 4.463 s | 8.659 s | 11.076 s |
| Time to the first text: median | 1.678 s | 2.773 s | 3.301 s | 4.392 s | 2.347 s | 1.975 s | 3.457 s | 3.73 s |
| Output tokens of the first call: median (reasoning) | 237 (32) | 223 (32) | 589 (107.5) | 551.5 (119.5) | 234 (9) | 237 (0) | 582.5 (100.5) | 655.5 (68) |
| Input tokens of the first call: median | 6300 | 5948 | 6470 | 6171 | 6268.5 | 5916.5 | 6460 | 6161 |
| `outside(...)` where expected | 20 of 20 | 20 of 20 | none expected | none expected | 8 of 8 | 8 of 8 | none expected | none expected |
| A plan request that got `outside(...)` | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 |

- "Earlier" is `final-terra-low` for C and D and `fresh-terra-low` for E: prompt v1, the kit of that time, and up to 2 repairs. "Now" is `v3-terra-low`.
- The recount compiles the first reply of each earlier record with the kit at `c9100af`. K1 found 32 of 33 for `final-terra-low` the same way.

What the difference means, with the limit that each column is one run:

- The clean rate at the first try is the rate of the recount: 24, 8, and 14. The kit, not the prompt, explains the gain of set C from 23 to 24 (`showcase-lang-workout-de`).
- The dialogue sentence moved its two targets: `e-japanese-ru` and `d-spanish` now check each thing with a deck. It cost one fault and a larger plan: `e-japanese-ru` took 662 output tokens against 535, and its path took 14.34 s against 8.55 s.
- The names on the values of `show=` reached their target. `e-bbq-pt` had three values with no name, and now has one text value with a name. The two other plans with `show=` (`holdout-sleep-en`, `showcase-lang-habits-uk`) name their value, as their fixtures did.
- The routing has one new miss, and it uses the new word `other`.
- The times of sets C and D and of the dialogue screens of set E are slower, and the apps of set E are not. Assumption: the API was slower at the time of the run, and the prompt does not cause it.
  - Basis: the output tokens are about the same, and the input is 300 to 350 tokens shorter. An `outside(...)` reply has 8 or 9 output tokens and no reasoning. Its time to the first text was a median of 1.0 s slower in set C (19 of 20 pairs slower) and 0.68 s slower in set E (7 of 8 pairs).
  - Test: run the same requests again at a different time. That needs calls, so the controller decides.
- The dialogue screens of set E wrote 73 more output tokens (median), and the time after the first text grew from 5.0 s to 7.3 s (a scratch count). One run of 6 requests cannot separate the larger plans from the speed of the API.

### Calls and tokens

| Model and effort of each call | Calls | Input tokens | From the cache | Output tokens | Reasoning tokens |
| --- | --- | --- | --- | --- | --- |
| `gpt-6.1-terra` low | 82 | 490967 | 474336 (96.6%) | 18270 | 3142 |

The budget was 134 calls. The task used 82: 81 first calls and 1 repair. `evidence/modelcheck/spend.json` went from 356 to 438, and its log has 82 entries of the label. `LIMIT` stays at 490. Over tasks A0, H1, and P2 the count is 438 calls.

### What the numbers do not show

- One run of 81 requests. The same prompt on the same 15 requests changed 2 results between two runs of A0.
- The dialogue sentence names the requests of `e-japanese-ru` and `e-times7-ru` as its examples, and the run measures it on them. `d-spanish` is the one other request that the sentence changed. No new request tested either sentence.
- Each difference from the earlier runs mixes three changes: the kit (K1 and L1), the prompt (K1 and this task), and the variation of the API. The recount separates the kit only for the clean rate at the first try.
- Only one repair ran. So this run says little about the repair step with 1 repair.
- The times come from this computer, with 4 calls in parallel and a warm cache for 80 of 81 first calls. A cold call from the phone can differ.
- The function check reads plans. No plan ran on a device. This task measured no look numbers.
- The assumptions of A0 stay: the widget brief is empty, and the task text of a dialogue screen is the request line, the host line, and an empty brief.

## Changes by lines

Task 2b measured the change call of an app outside the app: the request and the app file go to the model, and the model replies with only the lines that change.
The scope is `.superpowers/sdd/2026-11-19-app-file-loop/t2b-decisions.md`.
The runs ran on `c855fda` with `gpt-6.1-terra`, the prompt hash `2fb332c18d90` (no prompt change), and 1 repair call at the effort low. The labels are `change-terra-low` and `change-terra-medium`.
Raw replies and records are in `evidence/modelcheck/<label>/`, which Git does not keep. Each of the 34 calls completed.

### What ran

- `kit/modelcheck.js` has a change mode for set F (decision 1). For each case it compiles the model plan, runs the events of the person through the run-time (`handle`), and so makes the stored state.
- The one call has the section "System" as its instructions and `changeText({request, plan})` as its input. The harness reads the reply with `readPlanReply`, merges it with `patchAppFile`, and compiles with `compileAppFile(merged, {appFile, stateSchema, state})`.
- On faults, one repair call at the effort low gets `repairText` over the change input, the merged plan, and the faults.
- Set F is `kit/test/fixtures/change-requests.json`: the 16 requests of decision 2 on 8 model plans, and the events of each plan.
- The harness compares the stored state with the next state by the field names of the previous definition. It compares only the fields whose stored value differs from the start state (rule 3b of `kit/README.md`: a start value is no data of the person). A list compares each stored row by its id and each stored column by its name.
- The comparison names two groups apart. A value of a line must stay (rule 3). An inner value of a need (`NAME_s`) starts again by design when its line changes (rule 7) or when its list takes new start rows (rule 3c).
- The harness also records a field that only `app.keep` holds (kept, but no line shows it), and a final plan that equals the stored app file (outcome "unchanged").
- The harness throws when an event changes nothing. The kit test runs the events of each plan with no call. It checks that the stored state differs from the start, and that the unchanged plan gives no fault, the same state, and no lost value.
- `holdout-tip-ru-a` ran first and alone, to check the path. Its call had no cached input tokens (0 of 6261). Then `STOP_AT=502 REPAIRS=1 bun kit/modelcheck.js run change-terra-low F gpt-6.1-terra low` ran the other 15 cases, and the same command with `change-terra-medium` and `medium` ran all 16, with 4 calls in parallel.

| Plan | Events of the person | Compared fields (stored value differs from the start) |
| --- | --- | --- |
| `holdout-tip-ru` | bill 2400, tip 12, people 3 | `bill`, `tip`, `people` |
| `showcase-lang-shopping-it` | one row through the add form (Latte, Latticini), and its check mark | `prodotti` |
| `showcase-lang-habits-uk` | three check marks in rows 1 and 2, and a new name for row 3 | `habits` |
| `showcase-lang-tea-tr` | the choice Bitki çayı, then Yeşil çay | `tea` |
| `showcase-lang-kanji-ja` | show, 覚えた, show, まだ | `cards_s` (inner values of the deck) |
| `showcase-lang-budget-es` | the tab "Añadir gastos", and one row through the add form (Comida, 25.5) | `gastos`, `pestanas_s` (inner value: the open tab) |
| `e-fuel-pl` | 420 km, 31 l, 6.5 zł | `dystans`, `litry`, `cena` |
| `d-pythagoras` | scale 3, the next step of the lesson, the answer "10" | `scale`, `lesson`, `quiz_s` (inner value: the answer) |

Implementation choice: a row through the add form counts as one event, as decision 2 lists it. The fixture holds its events of the run-time: the budget row takes five (the tab, the open form, the category, the amount, and the add button).

### The worst outcome

No clean plan lost or moved a stored value of a line. The runs gave 28 clean plans (14 at each effort), and the comparison of each record gives these counts (a scratch count over the records):

- 24 of 24 clean plans that compared a value of a line kept each one.
- 4 clean plans compared no value of a line: `showcase-lang-kanji-ja-a` and `-b` at each effort. Their only compared field is the inner values of the deck.
- 8 clean plans started inner values of the person again, by design:
  - Rule 3c: the place and the counts of the deck in `showcase-lang-kanji-ja-a` and `-b`, at each effort.
  - Rule 7: the open tab in `showcase-lang-budget-es-a` and `-b`, at each effort.

The table row "stored values of the lines stayed in 14/14 clean plans" of each run counts the kanji records too, because they lost no value of a line.
No clean plan kept a value only in `app.keep`, and no reply renamed a need or a field of a list.

### Numbers

| Measure | Effort low | Effort medium |
| --- | --- | --- |
| Clean at the first try | 14 of 16 | 14 of 16 |
| Clean after the repair | 0 | 0 |
| Unchanged: the final plan is the stored app file | 1: `showcase-lang-shopping-it-b` | 1: `showcase-lang-shopping-it-b` |
| Not clean | 0 | 0 |
| `outside(...)` | 1: `e-fuel-pl-b`, `outside("game")`, as expected | 1: the same |
| Reply lines of the 15 replies of lines: median, maximum | 1, 3 (`showcase-lang-budget-es-a`) | 1, 3 (`showcase-lang-budget-es-a`) |
| Lines of the plan of the same 15 cases: median, maximum | 3, 6 | 3, 6 |
| First call: median, 90th percentile, maximum | 3.882 s, 7.414 s, 11.942 s (`d-pythagoras-b`) | 3.062 s, 7.4 s, 13.225 s (`d-pythagoras-b`) |
| Time to the first text: median, 90th percentile, maximum | 1.678 s, 6.381 s, 7.919 s | 1.588 s, 4.941 s, 7.924 s |
| Whole path (each call and each compile): median, 90th percentile, maximum | 4.615 s, 7.42 s, 11.98 s | 3.785 s, 7.42 s, 13.26 s |
| Output tokens of the first call: median (reasoning) | 109.5 (0) | 125 (0) |
| Reasoning tokens of all first calls | 263 of 2835 output tokens (9.3%), in 2 of 16 calls | 761 of 3326 output tokens (22.9%), in 7 of 16 calls |
| Input tokens of the first call: median, and from the cache | 6330, 87.1% | 6330, 81.3% |

- The source of each number is `bun kit/modelcheck.js table change-terra-low --detail` and the same for `change-terra-medium`. The input tokens, the cache, and the count of calls with reasoning come from a scratch count over the same records.
- Each of the 16 cases has the same outcome at both efforts.
- Three first calls had no cached input tokens: `holdout-tip-ru-a` at low (4.939 s), and `holdout-tip-ru-a` (2.677 s) and `showcase-lang-shopping-it-b` (2.4 s) at medium.
- The slowest case at both efforts is `d-pythagoras-b`: the reply writes the drawing line again with three squares (692 and 809 output tokens, 238 and 355 of them reasoning).
- The time after the first text was at most 4.218 s (low) and 5.301 s (medium). The rest of the time was the wait for the first text. A reply of 27 output tokens with no reasoning (`holdout-tip-ru-b` at low) got its first text at 6.381 s.
- The one repair call took 3.337 s at low and 3.895 s at medium.

### Each case

"Values of lines" is the comparison of the values of lines. "Inner values" names the inner values that started again. "Serves the request" comes from a read of the final plan and its compiled screen. The low and medium plans were the same in kind for each case, so one verdict covers both, except where the line says so.

| Case | Low | Medium | Values of lines stayed | Inner values | Serves the request |
| --- | --- | --- | --- | --- | --- |
| `holdout-tip-ru-a` | clean0 | clean0 | yes | none compared | yes: a percent input of the discount, and the amount for each person takes the discount before the tip |
| `holdout-tip-ru-b` | clean0 | clean0 | yes: the tip stays at the 12 of the person (rule 3) | none compared | yes in the plan: the start value is 15. The person who set 12 sees no change (finding 2) |
| `showcase-lang-shopping-it-a` | clean0 | clean0 | yes: the row keeps its values and gets a quantity of 0 | none compared | yes: a column `quantita` of kind count |
| `showcase-lang-shopping-it-b` | unchanged | unchanged | yes | none compared | no: the repair restored the column, so nothing changed (finding 1) |
| `showcase-lang-habits-uk-a` | clean0 | clean0 | yes | none compared | yes: a figure of the check marks of the week, which the kit shows as the main element at the top |
| `showcase-lang-habits-uk-b` | clean0 | clean0 | yes: the marks and the new name stay | none compared | yes: the line without `add=false`, with `addText=` |
| `showcase-lang-tea-tr-a` | clean0 | clean0 | yes | none compared | yes: the option "Oolong çayı" and 240 s in the timer formula |
| `showcase-lang-tea-tr-b` | clean0 | clean0 | yes | none compared | partly: a figure of the temperature for each tea, but at the top, not under the timer (finding 3) |
| `showcase-lang-kanji-ja-a` | clean0 | clean0 | no value of a line compared | the place and the counts of the deck (rule 3c) | yes: ten start rows, and the untouched list shows them |
| `showcase-lang-kanji-ja-b` | clean0 | clean0 | no value of a line compared | the place and the counts of the deck (rule 3c) | yes: a column `example` with a sentence in each row, and the back of the card shows it |
| `showcase-lang-budget-es-a` | clean0 | clean0 | yes | the open tab (rule 7) | yes: a budget input and the money left this month. The budget starts at 0, so the person sees -25,50 until a budget is set |
| `showcase-lang-budget-es-b` | clean0 | clean0 | yes | the open tab (rule 7) | yes: `delete grafico` and the tabs without it |
| `e-fuel-pl-a` | clean0 | clean0 | yes | none compared | yes: a count of people from 1 and the cost for each person |
| `e-fuel-pl-b` | outside | outside | no compile | no compile | yes: `outside("game")`, as expected |
| `d-pythagoras-a` | clean0 | clean0 | yes: also the answer "10" of the first question | none | yes: a second question (13 and 5, answer 12) |
| `d-pythagoras-b` | clean0 | clean0 | yes | none | yes: three squares on the sides with their areas (9, 16, and 25 times the square of the scale). The labels of the side lengths are gone |

Of the 32 records: 28 yes (with the two `outside("game")` replies), 2 partly (`showcase-lang-tea-tr-b` in each run), and 2 no (`showcase-lang-shopping-it-b` in each run).

### `BLOCKS` and `MISMATCH` faults

| Case | Run | Fault | What the repair did |
| --- | --- | --- | --- |
| `showcase-lang-shopping-it-b` | low | "need prodotti: the saved data blocks this change: the saved rows hold values in the column corsia, and the change removes it. Keep the column" | It wrote the line of the stored app file again. The final plan is the stored app file. |
| `showcase-lang-shopping-it-b` | medium | the same | the same |

No `MISMATCH` fault came. No other fault came.

### Rewrites and renames

- No reply wrote the whole plan again. No reply repeated a line of the plan that it did not change.
- No reply renamed a need or a field of a list. `showcase-lang-budget-es-b` deleted the need `grafico`, which the request asks for.
- A reply that changes an option of a list writes the whole line of that need again, because an option is inside the line. `showcase-lang-habits-uk-b` wrote 368 and 382 output tokens for that one line.

### Findings about the kit and the prompt

The task changed nothing in `kit/src` and nothing in the prompt.

1. **A repair of a removed column only undoes the change.** In `showcase-lang-shopping-it-b` the first reply removed the column `corsia` in both runs. The kit refused it with `BLOCKS`, and the repair wrote the stored line again. So the change call ends clean with no change, and the person gets no word. The kit has no way to hide a field of a list, so no clean plan can keep the data and serve this request.
   - Unresolved decision: the change call sends no repair for a `BLOCKS` fault and tells the person what blocks the change, or it treats a final plan that equals the stored app file as not done. This decision affects the repair step of the change call.
   - Evidence: `evidence/modelcheck/change-terra-low/showcase-lang-shopping-it-b.0.txt` and `.1.txt`, and the same files of `change-terra-medium`.
2. **A new default does not reach a person who changed the value.** In `holdout-tip-ru-b` the plan has the start value 15, and the stored 12 stays (rule 3). The result has no note, so the person sees the same tip after the change. Evidence: `evidence/modelcheck/change-terra-low/holdout-tip-ru-b.0.txt`.
3. **A new figure becomes the main element at the top.** `showcase-lang-tea-tr-b` asks for the temperature under the timer. The reply adds a figure, the merge puts it at the end, and the kit makes the first figure of the app the hero card at the top. Both runs did it. Candidate sentence for the section "Change", which did not run: "A line that you add goes at the end of the app file, and the first figure of an app is its main element at the top: to show a new value under a thing, use content or card." Evidence: `evidence/modelcheck/change-terra-low/showcase-lang-tea-tr-b.0.txt`.
4. **A new column of kind count starts at 0 in each stored row.** In `showcase-lang-shopping-it-a` the stored "Latte" gets the quantity 0, the value of the add form (rule 3). Evidence: the next state in `evidence/modelcheck/change-terra-low/showcase-lang-shopping-it-a.json`.
5. **A deck starts again when its cards get one more value.** `showcase-lang-kanji-ja-b` asks for an example sentence on each card. The reply keeps the same five cards in the same order and adds the column `example`. The untouched list takes the new start rows (rule 3b), so rule 3c starts the deck again: the person loses the place 2 and both counts (1 and 1).
   - This is the first case from a model reply of the open question 3 of fix round 2 in `.superpowers/sdd/2026-11-19-app-file-loop/t2-report.md`: a deck over a list with the same cards starts again.
   - Evidence: `evidence/modelcheck/change-terra-low/showcase-lang-kanji-ja-b.0.txt` and the stored and next states in `showcase-lang-kanji-ja-b.json`, the same in `change-terra-medium/`.

Decision 5 permits one change of the section "Change". It did not apply:

- The one failed case is `showcase-lang-shopping-it-b`. Its cause is the repair step and the kit (finding 1), not a reply that a sentence of the section "Change" removes.
- Finding 3 is one case. One case is no class of bad replies.

### Recommendation for the change call

- The effort low. Each of the 16 cases had the same outcome and the same kind of plan at both efforts. Medium wrote 2.9 times the reasoning tokens (761 against 263) and had the slower maximum (13.225 s against 11.942 s). Its median first call was faster (3.062 s against 3.882 s). One run of each effort cannot separate this from the variation of the API.
- The time limits:
  - First text: 15 s. The maximum was 7.924 s.
  - Total: 30 s. The maximum whole path was 13.26 s. The slowest first call and the slowest repair together take about 17.1 s.
- One repair is sufficient for these 16 cases, because no plan had a fault after the repair. This rests on 2 repair calls on one case, and both only undid the change (finding 1). So the decision on finding 1 matters more than the count of repairs.

### What the numbers do not show

- One run of each effort, on 16 cases. A result of 0 losses in 24 clean plans that compared a value of a line says nothing about a rare loss.
- No first reply of the 32 had a fault other than `BLOCKS`. So the runs show nothing about the repair of an ordinary fault, such as a formula that reads nothing, in a change call.
- The kanji cases compared no value of a line. The list of the plan is hidden and the deck has no `mark=`, so the person cannot change the rows. The branch of decision 2 with "a stored mark of the person" cannot happen with this plan.
- No reply renamed a need, so no model reply tested the path "kept, but not shown". Only the kit test covers it.
- Each stored state comes from 2 to 5 events, and each list holds at most 3 rows. A state of months of use is larger.
- The tea timer is the foreground timer of the host and keeps no state, so no stored state held a timer that runs.
- The controller wrote the requests. Each one is short and asks for one change.
- The times come from this computer, with 4 calls in parallel and a warm cache for 29 of 32 first calls. A cold call from the phone can differ.
- Assumption: the change call of the app merges and compiles as the harness does (`patchAppFile`, then `compileAppFile` with the stored app). Test it: log the steps of the change call of the app for one case.
- The verdicts read plans and compiled screens. No plan ran on a device.

### Calls and tokens

| Effort of each call | Calls | Input tokens | From the cache | Output tokens | Reasoning tokens |
| --- | --- | --- | --- | --- | --- |
| `gpt-6.1-terra` low | 18 | 114478 | 100891 | 3087 | 311 |
| `gpt-6.1-terra` medium | 16 | 101682 | 82684 | 3326 | 761 |
| All | 34 | 216160 | 183575 | 6413 | 1072 |

The budget was 64 calls. The task used 34: 32 first calls and 2 repairs. `evidence/modelcheck/spend.json` went from 438 to 472, and its log has 34 entries of the two labels. `LIMIT` of `kit/modelcheck.js` is 502.

## Prompt v4: two sentences from the first live run

The first live run of the plan step in the app showed two defects that come from the plan of the model. Task P3 added one sentence to the prompt for each, and ran 15 requests one time.
The scope is `.superpowers/sdd/2026-11-19-app-file-loop/p3-decisions.md`.
The run ran on `046b09a` with `gpt-6.1-terra`, the effort low, and at most 1 repair call. The label is `v4-terra-low`.
Each of the 15 records has the prompt hash `be5e56293490` and `repairs: 1`. Each call completed.
Raw replies and records are in `evidence/modelcheck/v4-terra-low/`, which Git does not keep.

### The two sentences

1. The labels of many check marks. A new line in the paragraph of `entries`, after the field kinds:
   "When a row has more than two check fields, each check mark shows its label over it: in labels=, give each of these fields at most three letters, such as the short name of a weekday."
   - The kit puts the marks of a row with more than two check fields in a line of their own, as circles named by `labels=` or the field (`kit/src/parts.js` line 208, `kit/README.md` section "Parts", `entries`). With two check fields or fewer the marks stay in the row.
2. The order of a list. Three sentences changed, as decision 2 permits:
   - The list of what the lines cannot give: "no drag and no swipe (a list whose rows the person moves by hand is entries with reorder=true)". It was "(a list that the person puts in order is entries with reorder=true)".
   - The paragraph of `entries`: "reorder=true: only for an order that the person sets by hand, row by row, such as a ranking, a queue, or priorities, with no sort= and no group=." It was "reorder=true: the person moves the rows into an order, with no sort= and no group=."
   - A new sentence after `order=`: "An order by a field is sort= or group=, also when the request says "reorder by" that field, and a request to switch the order gets sort= with order=, or sort= that reads a choice, such as sort=$/by == "aisle" ? $aisle : $name."
   - The kit reads `order=` only with `sort=` (`kit/src/parts.js` line 297). Without `sort=` it drops `order=` with no fault and no note, so the sentence ties `order=` to `sort=`.
   - A scratch run of a list with `sort=$/by == "corsia" ? $corsia : $nome` showed the rows in the order of the aisles, and in the order of the names after the choice changed.

- The new prompt test "prompt (P3)" checks that the paragraph names "more than two check fields", that 2 check fields give no circle and 3 give circles, and that the sort by a choice of the paragraph compiles with no fault. It failed before the change of the prompt.
- The new hash is `be5e56293490` (it was `2fb332c18d90`). The section "System" is 21277 bytes (20798 bytes before).
- Commit `046b09a` holds `kit/prompt.md`, `kit/test/prompt.test.js`, and `app/src/main/assets/appfile/prompt.json`. `bun test kit`: 601 pass, 0 fail. `bun kit/build.js --check`: exit 0.

### What ran

- Before the run, the 15 first replies of `v3-terra-low` compiled clean with the kit at `046b09a` (a scratch compile, no call). So a different outcome would come from the reply, not from the kit.
- The section "System" of `v3-terra-low` (`c9100af`) differs from now in two places: the sentences of this task, and the timer paragraph of task K2 (`cycle=true`). Of the 15 requests, only `showcase-lang-tea-tr` has a timer.
- `holdout-tip-ru` ran first and alone to check the path. Its call had no cached input tokens (0 of 6117).
- Then `REPAIRS=1 STOP_AT=502 bun kit/modelcheck.js run v4-terra-low CDE gpt-6.1-terra low` with the other 14 ids ran them, with 4 calls in parallel.

### Numbers

| Measure | `v4-terra-low` (15) | The same 15 in `v3-terra-low` |
| --- | --- | --- |
| Clean at the first try | 15 | 15 |
| Repair calls | 0 | 0 |
| First call: median, maximum | 4.934 s, 9.116 s (`d-moon`) | 5.01 s, 10.11 s (`d-moon`) |
| Output tokens of all first calls (reasoning) | 4041 (517) | not counted |

The source is `bun kit/modelcheck.js table v4-terra-low` and a scratch count over the records of both labels.

### Each request beside `v3-terra-low`

Each of the 15 final plans got a full read beside its plan of `v3-terra-low`. Each one is clean at the first try.

| Request | The plan beside `v3-terra-low` |
| --- | --- |
| `showcase-lang-habits-uk` | Same needs. The check fields have the labels "Пн", "Вт", "Ср", "Чт", "Пт", "Сб", and "Нд". In v3 they were the whole weekday names, such as "Понеділок". New: `icons={name: "Pencil"}`, a note because of `add=false`, and `addText=` and `emptyText=`. |
| `showcase-lang-shopping-it` | `sort=$corsia` in place of `reorder=true`. The rest is the same, except the icon "Pin" in place of "Tag" on the aisle field of the add form. |
| `showcase-ui-priorities` | Same needs: `reorder=true` with `edit=true` and a choice of the priority. |
| `e-timetable-tr` | `group=$gun` as in v3, but no `sort=$saat`. The lessons of a day stand in the order in which the person added them, not by time. Worse than v3. |
| `e-packing-de` | Same needs with `group=$kategorie`. The one check field keeps its whole label "Im Koffer". Two fields have other names. |
| `e-gifts-uk` | Same needs. The one check field has the label "Куплено", a whole word (v3: "Вже куплено"). The header has no `kind=`. |
| `showcase-ui-price-filter` | `sort=$price, order=$/order` with a choice "Low to high" or "High to low", as in v3. The type filter is now an input of kind `"choices"`: types that can be on together, and none on shows each bike. v3 had one choice with "All". The 12 bikes have other names and prices. |
| `showcase-ui-alarm` | Same needs, plus a text field `dose` ("Morning dose") in each row, which the request does not ask for and v3 left out. By L1 item 1 (`kit/README.md`), that field has a line of its own on a phone, so it is not one letter wide as in M1 F1. |
| `showcase-ui-steps-chart` | Same needs. New `icons=` for two fields, two notes because of `add=false`. |
| `holdout-sleep-en` | Same needs and the same named value of `show=`. The date field is `day`, with the label "Wake date". |
| `holdout-tip-ru` | Same needs. The tip input has no `max=`, and the run gave no note. |
| `showcase-lang-kanji-ja` | Same shape: a hidden list of 5 kanji and a deck. One kanji differs (月 in place of 海). |
| `d-moon` | Same needs: a drawing, the angle input, a card, a lesson of 3 steps, and a question. The Moon has no outline now. |
| `e-bbq-pt` | Same needs. The value of `show=` still has the name "Acerto". |
| `showcase-lang-tea-tr` | Same needs, and the same note about `start=`, `pause=`, `reset=`, and `done=` on a timer of one phase. |

The order options of the five lists that the decision names:

| Request | `sort=` | `group=` | `order=` | `reorder=` | Fits the request? |
| --- | --- | --- | --- | --- | --- |
| `showcase-lang-shopping-it` | `$corsia` | none | none | none | Yes, by decision 2: the rows stand in the order of the aisle. The aisle is a text field, so the rows follow the sorted aisle names, not the way through the shop. The plan has no switch. |
| `showcase-ui-priorities` | none | none | none | `true` | Yes: the request asks to drag the tasks into an order. |
| `e-timetable-tr` | none | `$gun` | none | none | Partly: the request asks for groups by day, and the plan has them. In each day the lessons are not in the order of time. |
| `e-packing-de` | none | `$kategorie` | none | none | Yes: the request asks for categories. |
| `showcase-ui-price-filter` | `$price` | none | `$/order` | none | Yes: the request asks for a sort by price, and the person switches the direction. |

### Plans that got worse

- `e-timetable-tr` lost `sort=$saat`. The earlier run `fresh-terra-low` (prompt v1) also had only `group=$gun`, and H1 named that as a gap. So the plan is back at its v1 form.
  - Assumption: the words "sort= or group=" of the new sentence read as a choice of one of the two. Basis: the one change of the plan is the loss of `sort=` beside `group=`. The variation of the API can explain it too, because two of three runs had no `sort=`. Test: run the request several times with each prompt. That needs calls, so the controller decides.
  - Candidate text, which did not run (decision 6): "An order by a field is sort= or group=, or both, such as group=$day, sort=$time, also when the request says "reorder by" that field, ...". The plan of v3 with `group=$gun, sort=$saat` compiles clean with the kit of now.
- No other plan got worse. Two plans got notes that v3 did not have: `icons=` with `add=false` in `showcase-lang-habits-uk` and `showcase-ui-steps-chart`. The kit leaves each such icon out, and the screen does not change.

### What the numbers do not show

- One run of 15 requests. Each sentence reached its target in one reply. The same prompt on the same requests changed 2 results between two runs of A0.
- No request of the 15 asks for a switch between two orders by a field, so no reply tested the sort by a choice.
- The new labels of `showcase-lang-habits-uk` did not run on a device. In the picture of the live run, "Середа" (6 letters) fits over one mark of seven, and "Понеділок" shows as "Понеді" (`evidence/appfile-loop/smoke-1/on/showcase-lang-habits-uk/review.png`). So a label of two letters fits. This comes from the picture, not from a run.
- The times come from this computer, with 4 calls in parallel and a warm cache for 14 of 15 first calls.

### Calls and tokens

| Model and effort of each call | Calls | Input tokens | From the cache | Output tokens | Reasoning tokens |
| --- | --- | --- | --- | --- | --- |
| `gpt-6.1-terra` low | 15 | 92012 | 84462 (91.8%) | 4041 | 517 |

The budget was 30 calls. The task used 15: 15 first calls and no repair. `evidence/modelcheck/spend.json` went from 472 to 487, and its log has 15 entries of the label. `LIMIT` of `kit/modelcheck.js` stays at 502.
