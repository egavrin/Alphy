// The host report, the checks, and the smoke run. Ported from the prototype's test.js.

import { test } from "bun:test";
import { AppFile } from "../src/dsl.js";
import { App, settings } from "../src/core.js";
import { report, smoke } from "../src/check.js";
import { expand } from "../src/parts.js";
import { compileAppFile } from "../src/compile.js";
import { assert, fixture, gateEach, has, plan, planned, same } from "./helpers.js";

gateEach("checks");

test("the examples have no fault, their checks pass, and each prefix of a stream gives a tree", async () => {
  for (const [name, text] of [["tip", plan("calc")], ["habits", fixture("habits")], ["shopping", fixture("shopping")]]) {
    const file = new AppFile().feedText(text);
    const lines = await expand(file);
    const result = report(file);
    same(result.faults, [], `${name}: faults`);
    assert(result.checks.length > 0 && result.checks.every((check) => check.pass), `${name}: checks ${JSON.stringify(result.checks)}`);
    assert(lines.length > 4 && lines.at(-1).startsWith("root = Column("), `${name}: expansion`);
    // A stream: every prefix of the file must give a tree, with Missing nodes for names that have no line yet.
    const stream = new AppFile();
    for (const line of file.text().split("\n")) { stream.feed(line); new App(stream).tree(); }
    same(stream.text(), file.text(), `${name}: stream gives the same file`);
  }
});

test("faults reach the report, not an exception", () => {
  const bad = new AppFile().feedText('root = Column([a, b])\na = Texxt("x")\n$/n := nope(1)\nc = = 3');
  const faults = report(bad).faults.join(" | ");
  for (const part of ["unknown component Texxt", "unknown function nope", "b: no component line", "line 4"]) assert(faults.includes(part), `fault report misses "${part}": ${faults}`);
});

test("real requests of the Alphy corpus are clean, and the hand-written list line keeps its sort", async () => {
  // Planned by hand with the parts and the catalog as they are. Expected values are fixed by hand.
  for (const [name, text] of [["shopping-it", plan("list")], ["units-pl", fixture("units-pl")], ["water-ko", fixture("water-ko")], ["price-filter", fixture("price-filter")], ["kanji-ja", plan("quiz")]]) {
    const file = new AppFile().feedText(text);
    await expand(file);
    const result = report(file);
    same(result.faults, [], `real ${name}: faults`);
    assert(result.checks.length > 0 && result.checks.every((check) => check.pass), `real ${name}: checks ${JSON.stringify(result.checks)}`);
  }
  const bikes = new AppFile().feedText(fixture("price-filter"));
  await expand(bikes);
  const shop2 = new App(bikes);
  shop2.input("order_field", "desc");
  same(shop2.find("bikes_list").children.slice(0, 2).map((row) => row.children[1].props.text), [1490, 1340], "the hand-written list line sorts by price, and the part left it alone");
});

test("a fault belongs to the step that made it, and the next step starts clean", async () => {
  const fixed = await planned('app("T")\nneed note = content("Hello")');
  fixed.beginStep();
  fixed.feed("extra = = 3");
  fixed.feed("need z = nopart(1)");
  await expand(fixed);
  assert(has(fixed, "line not understood") || has(fixed, "extra"), "a bad line is in the report of its step");
  assert(has(fixed, "no part for need kind nopart"), "an unknown need kind is in the report");
  fixed.beginStep();
  fixed.feed("delete z");
  await expand(fixed);
  same(report(fixed).faults, [], "the next step has no old faults");
});

test("a hole is a fault until an author writes its view", async () => {
  const holed = await planned('app("T")\nneed chart = hole("A bar chart of spending")');
  assert(has(holed, "hole chart"), "an unbuilt hole is a fault");
  holed.feed('chart_view = Text("bars")');
  same(report(holed).faults, [], "a built hole is no fault");
});

test("logic that a model wrote needs a check that uses it, and a failed expectation shows the values", async () => {
  const loose = await planned('app("T")\nneed a = input("number", "A", 2)\nneed twice = figure("number", "Twice", $/a * 2)');
  assert(has(loose, "need twice"), "a figure without an example or a check is a fault");
  loose.feed("check(\"Twice\", set(a_field, 5), expect($/twice == 10))");
  same(report(loose).faults, [], "a check that reads the figure clears the fault");
  loose.feed("$/half := $/a / 2");
  assert(has(loose, "$/half"), "a formula from an author needs a check");
  loose.feed('reset = Button("Reset", action=set($/a, 0))');
  loose.feed("root = Column([twice_view, a_field, reset, Button(\"Inline\", action=set($/a, 1))])");
  assert(has(loose, "reset: no check"), "a control from an author needs a check that taps it");
  assert(has(loose, "own named line"), "an inline control cannot be used by a check");
  loose.feed("root = Column([twice_view, a_field, reset])");
  loose.feed('check("Reset and half", tap(reset), expect($/a == 0), expect($/half == 0))');
  same(report(loose).faults, [], "checks clear the faults");
  // A failed expectation shows the values, so the cheapest repair is not to change the check blindly.
  loose.feed('check("Wrong", set(a_field, 3), expect($/twice == 7))');
  const wrong = report(loose).checks.find((check) => check.name === "Wrong");
  assert(!wrong.pass && wrong.detail.includes("6") && wrong.detail.includes("7"), `a failed expect shows both sides: ${wrong.detail}`);
  const off = await planned(plan("calc").replace("23]", "25]"));
  assert(report(off).checks.some((check) => !check.pass && check.detail.includes("23")), "a failed example shows the computed value");
});

test("a value that is not a number, and a path that no line sets, have their names in the report", async () => {
  const lost = await planned('app("T")\nneed n = figure("count", "N", $/a + 1, example=[{a: 1}, 2])');
  assert(has(lost, "$/n is not a finite number") && has(lost, "reads $/a, and no line sets it"), `the report names the path: ${report(lost).faults}`);
});

test("an example that gives a value for a formula says so", async () => {
  const chained = await planned('app("T")\nneed a = input("number", "A", 1)\nneed b = figure("number", "B", $/a * 2, example=[{a: 2}, 4])\nneed c = figure("number", "C", $/b + 1, example=[{b: 4}, 5])');
  assert(report(chained).checks.some((check) => check.detail.includes("$/b is a formula")), "a put on a formula is explained");
});

test("three mistakes of a model draft get a fault that says what to write", () => {
  const mixed = new AppFile().feedText('$/n = 0\n$/next = Button("Next", action=inc($/n))\nroot = Column([$/next, ok])\nok = Button("Ok", action=inc($/n))\ncheck("Old", expect($/n == 0))\ncheck("Tap", tap($/next), expect($/n == 1))\ndelete check("Old")');
  const mixedReport = report(mixed);
  for (const part of ["Button is a component", "a child is a component name", "tap needs a control name"]) assert(JSON.stringify(mixedReport).includes(part), `the report misses "${part}": ${JSON.stringify(mixedReport)}`);
  same(mixed.of("check").map((entry) => entry.key), ["check:Tap"], "delete check(\"Name\") removes that check");
  same(mixed.faults, [], "the delete line is understood");
});

test("a need whose view no line under root shows is a fault", async () => {
  const hidden = await planned('app("T")\nneed a = input("number", "A", 2)\nneed b = input("number", "B", 3)');
  hidden.feed("root = Column([a_field])");
  assert(has(hidden, "need b is not on the screen"), "a need that root does not reach is a fault");
});

test("the smoke run uses each control once and reports what it sees", () => {
  const smoky = new AppFile().feedText('$/n = 0\n$/on = false\n$/hidden = 0\n$/open = false\nroot = Column([count, plus, mark, ghost, bad, opener, panel])\ncount = Text("n " + $/n + ($/on ? " on" : ""))\nplus = Button("Plus", action=inc($/n))\nmark = Button("Day", action=toggle($/on))\nghost = Button("Ghost", action=inc($/hidden))\nbad = Button("Bad", action=set($/n, $/n / 0))\nopener = Button("Open", action=set($/open, true))\npanel = Card([inner], visible=$/open)\ninner = Button("Inner", action=append($/nope, {a: 1}))');
  const run = smoke(smoky);
  const smokeText = run.faults.join(" | ");
  for (const part of ["ghost changes a value, and no part of the screen shows it", "after bad, $/n is not a finite number", "inner:"]) assert(smokeText.includes(part), `the smoke run misses "${part}": ${smokeText}`);
  assert(!smokeText.includes("plus") && !smokeText.includes("opener"), `the smoke run blames a good control: ${smokeText}`);
  // One on-off button that looks the same in both states is a note. A group of them is a fault, in one line.
  assert(run.notes.join().includes("mark turns a value on and off") && !smokeText.includes("mark"), `one on-off button is a note: ${run.notes}`);
  const week = new AppFile().feedText('$/a = false\n$/b = false\n$/c = false\n$/d = false\nroot = Column([Text(($/a ? 1 : 0) + ($/b ? 1 : 0) + ($/c ? 1 : 0) + ($/d ? 1 : 0)), days])\ndays = Row([da, db, dc, dd])\nda = Button("A", action=toggle($/a))\ndb = Button("B", action=toggle($/b))\ndc = Button("C", action=toggle($/c))\ndd = Button("D", action=toggle($/d))');
  same(smoke(week).faults, ["smoke run: da, db, dc and 1 more turns a value on and off, and looks the same in both states. Give it selected=test"], "a group of on-off buttons is one fault line");
  week.feedText('da = Button("A", action=toggle($/a), selected=$/a)\ndb = Button("B", action=toggle($/b), selected=$/b)\ndc = Button("C", action=toggle($/c), selected=$/c)\ndd = Button("D", action=toggle($/d), selected=$/d)');
  same(smoke(week).faults, [], "buttons with selected= show their state");
});

test("a control that never comes to the screen is a fault, and one that comes after six taps is reached", () => {
  const locked = new AppFile().feedText('$/open = false\n$/i = 0\n$/n = 0\nroot = Column([Text("i " + $/i + " n " + $/n), next, quiz, end])\nnext = Button("Next", action=inc($/i))\nquiz = Card([start, Text("Quiz")], visible=$/open)\nstart = Button("Start", action=set($/open, true))\nend = Card([again], visible=$/i >= 6)\nagain = Button("Again", action=[set($/i, 0), inc($/n)])');
  const lockedFaults = smoke(locked).faults.join(" | ");
  assert(lockedFaults.includes("start never comes to the screen"), `a control that cannot be reached: ${lockedFaults}`);
  assert(!lockedFaults.includes("again"), `a control that comes after six taps is reached: ${lockedFaults}`);
});

test("the walk taps one Next button again to reach the last step of a wizard", () => {
  const wizard = new AppFile().feedText('$/step = 0\n$/pages = 20\nroot = Column([one, two, three, nav])\none = Card([Text("Step 1")], visible=$/step == 0)\ntwo = Card([Text("Pages " + $/pages), more], visible=$/step == 1)\nmore = Button("More", action=inc($/pages, 5))\nthree = Card([Text("Done"), go], visible=$/step == 2)\ngo = Button("Start", action=set($/step, 0))\nnav = Row([back, next])\nback = Button("Back", action=dec($/step), enabled=$/step > 0)\nnext = Button("Next", action=inc($/step), enabled=$/step < 2)');
  same(smoke(wizard).faults, [], "the walk reaches the last step of a wizard");
});

test("a control that changes nothing in each state is a fault, and a control at its limit is not", () => {
  const dead = new AppFile().feedText('$/n = 0\n$/rows = [{t: "a"}, {t: "b"}]\nroot = Column([Text("n " + $/n), minus, plus, noop, list])\nminus = Button("-", action=set($/n, max(0, $/n - 1)))\nplus = Button("+", action=inc($/n))\nnoop = Button("Save", action=set($/n, $/n))\nlist = List($/rows, row)\nrow = Row([Text($t), up])\nup = IconButton("ArrowUp", action=move(-1))');
  same(smoke(dead).faults, ["smoke run: noop never changes a value: its action does nothing"], "a dead control is a fault, and a control at its limit is not");
});

test("the smoke run comes back to a state at the time of that state", async () => {
  // A button that shows only while the timer runs, and that changes nothing. The run comes back to the state after the start
  // when its own clock is 30 days later. At that time each phase of the timer is over, and a press would end them.
  const running = await planned('app("T", lang="en")\n$/x = 1\nneed t = timer("T", 40, rounds=2, rest=10)\nsame = Button("Same", action=set($/x, $/x), visible=$/t_on)\nshown = Text($/x)\nroot = Column([t_view, same, shown])');
  const run = smoke(running);
  same(run.faults, ["smoke run: same never changes a value: its action does nothing"], "a button that does nothing while the timer runs is dead");
  same(run.trace.find((line) => line.startsWith("tap t_pause")), "tap t_pause: t_on true -> false, t_end 40000 -> 0", "the pause stops the timer and ends no phase");
});

test("a control that does nothing at the start gets a new try in later states", async () => {
  const emptyDeck = await planned('app("T")\nneed words = entries("Words", fields={word: "text", meaning: "text"})\nneed quiz = deck("Quiz", $/words, front=$word, back=$meaning)');
  same(smoke(emptyDeck).faults, [], "a deck over a list that starts empty has no dead control");
});

test("what a part made for a need stays on the screen", async () => {
  const cut = await planned('app("T")\nneed items = entries("Items", fields={name: "text"})');
  cut.feed('items_view = Section("Items", [items_list])');
  assert(has(cut, "need items: the part made") && has(cut, "items_add"), `a part control that the screen lost: ${report(cut).faults}`);
  const kept = await planned('app("T")\nneed items = entries("Items", fields={name: "text"})');
  kept.feed('items_view = Card([Heading("My items"), items_list, items_addRow])');
  kept.feed('need items = entries("Items", fields={name: "text"}, remove=false)');
  await expand(kept);
  same(report(kept).faults.filter((fault) => fault.includes("the part made")), [], "a new look of a part view, and a need line that drops a control, are no fault");
});

test("a change that only the data of a list shows is a change that the screen does not show", () => {
  const rowsOnly = new AppFile().feedText('$/rows = [{name: "a", on: false}]\nroot = Column([list])\nlist = List($/rows, row)\nrow = Row([Text($name), flip])\nflip = Button("Flip", action=toggle($on))');
  assert(smoke(rowsOnly).faults.join().includes("flip changes a value, and no part of the screen shows it"), `a change in list data only: ${smoke(rowsOnly).faults}`);
});

test("the smoke gate replaces the duty to write a check for each control", () => {
  settings.gate = "smoke";
  const gated = new AppFile().feedText('$/n = 0\nroot = Column([Text("n " + $/n), Button("Plus", action=inc($/n)), ghost])\nghost = Button("Ghost", action=set($/z, 1))');
  const gatedFaults = report(gated).faults.join(" | ");
  assert(!gatedFaults.includes("no check") && !gatedFaults.includes("own named line") && gatedFaults.includes("ghost"), `the smoke gate: ${gatedFaults}`);
});

test("the walk of the smoke run turns five pages to the end inside a tab", async () => {
  const longBook = await planned('app("T")\nneed n = counter("Taps", 0)\nneed pages = entries("Pages", fields={line: "text"}, hidden=true, rows=[{line: "1"}, {line: "2"}, {line: "3"}, {line: "4"}, {line: "5"}])\nneed book = deck("Story", $/pages, front=$line)\nneed tabs = tabs(["Count", "Story"], [[n], [pages, book]])');
  same(smoke(longBook).faults, [], "the smoke run reaches the end of five pages in a tab");
});

test("a button that goes back to the start is not dead", () => {
  const resets = new AppFile().feedText('$/open = false\nroot = Column([Text($/open ? "A" : "?"), show, reset])\nshow = Button("Show", action=set($/open, true), enabled=$/open == false)\nreset = IconButton("Refresh", action=set($/open, false))');
  same(smoke(resets).faults, [], "a reset button has an effect after a different button");
});

test("the smoke gate is clean on the catalog example and on an app with each new item", async () => {
  const full = await planned('app("T")\nneed bed = input("time", "Bed", "23:00")\nneed wake = input("time", "Wake", "07:00")\nneed slept = figure("number", "Hours", hours($/bed, $/wake), example=[{bed: "23:30", wake: "06:00"}, 6.5])\nneed start = input("date", "Start", "2026-11-21")\n'
    + 'need doses = entries("Doses", fields={name: "text", time: "time", on: "switch", qty: "count"}, edit=true, confirm="Delete this dose?", yes="Yes", no="No", rows=[{name: "A", time: "08:00", on: true, qty: 0}, {name: "B", time: "20:00", on: true, qty: 2}])\n'
    + 'need acts = entries("Plan", fields={what: "text", day: ["Sat", "Sun"], time: "time", date: "date"}, sheet=true, group=$day, sort=[$day, $time], rows=[{what: "Hike", day: "Sun", time: "09:00", date: "2026-11-22"}, {what: "Dinner", day: "Sat", time: "19:00", date: "2026-11-21"}])\n'
    + 'need perDay = chart("For each day", groups($/acts, $day), $key, $value)\nneed cards = entries("Cards", fields={es: "text", en: "text"}, hidden=true, rows=[{es: "gato", en: "cat"}, {es: "perro", en: "dog"}])\nneed book = deck("Cards", $/cards, front=$es + " / " + $en)\n'
    + 'need pages = tabs(["Sleep", "Doses", "Trip", "Cards"], [[slept, bed, wake, start], [doses], [acts, perDay], [cards, book]])');
  settings.gate = "smoke";
  const demo = await planned(fixture("catalog"));
  same([report(demo).faults, report(demo).checks.length, report(demo).checks.every((check) => check.pass)], [[], 2, true], "the catalog example of the demo page is clean under the smoke gate");
  same(report(full), { faults: [], checks: [{ name: "slept example", pass: true, detail: "" }], notes: [] }, "the smoke gate is clean on an app with each new item");
  const fullRun = smoke(full).trace.join(" | ");
  for (const part of ["doses_yes", "doses_no", "acts_add", "acts_open", "book_again", "doses_qty_plus", "doses_time", "start_field"]) assert(fullRun.includes(part), `the smoke run did not use ${part}: ${fullRun}`);
});

test("a chain of figures that read later lines settles: the formulas run once for each formula (final review 1)", async () => {
  // The probe of the review at depth 5: g1 to g5 each read the next need, and g6 reads n.
  const lines = [1, 2, 3, 4, 5, 6].map((n) => `need g${n} = figure("number", "G${n}", ${n < 6 ? `$/g${n + 1} + 1` : "$/n"}, example=[{n: 1}, ${7 - n}])`);
  const chain = await planned(['app("T", lang="en")', ...lines, 'need n = input("number", "N", 1, min=0, max=10)'].join("\n"));
  same([report(chain).faults, new App(chain).state.g1], [[], 6]);
});

test("a formula that reads itself, and formulas that read each other, are a fault that names their lines (final review 10)", async () => {
  const self = await planned('app("T", lang="en")\n$/n = 2\n$/total := number($/total) + $/n\ngo = Button("go", inc($/n))\nroot = Column([Text(text($/total)), go])');
  assert(report(self).faults.includes("$/total: the formula reads its own value, so the value never settles. Compute it from other values only"), report(self).faults.join(" | "));
  const pair = await planned('app("T", lang="en")\n$/n = 0\n$/a := text($/b) + text($/b) + "x"\n$/b := $/a\ngo = Button("go", inc($/n))\nroot = Column([Text($/a), Text(text($/n)), go])');
  assert(report(pair).faults.includes("$/a: the formulas $/a and $/b read each other, so their values never settle. Compute one of them from other values only"), report(pair).faults.join(" | "));
});

test("a failed example of a figure and a failed check line are faults of their lines, with the values (final review 4)", async () => {
  const base = ['app("T", lang="en")', 'need n = input("number", "N", 2, min=0, max=10)'];
  const wrong = await compileAppFile([...base, 'need f = figure("number", "F", $/n * 2, example=[{n: 2}, 5])'].join("\n"));
  same([wrong.faults, wrong.notes, wrong.definition, wrong.language], [["need f: example=[{n: 2}, 5] gives 4, and the example expects 5. Correct the formula or the example"], [], null, null]);
  const checked = await compileAppFile([...base, 'need f = figure("number", "F", $/n * 2, example=[{n: 2}, 4])', 'check("Big", expect($/f == 99))'].join("\n"));
  same(checked.faults, ['check("Big"): expect failed: ($/f == 99): left is 4, right is 99']);
  // A step that fails before its expect, such as a put on a formula, names the need with the text of the step.
  const chained = await compileAppFile([...base, 'need b = figure("number", "B", $/n * 2, example=[{n: 2}, 4])', 'need c = figure("number", "C", $/b + 1, example=[{b: 4}, 5])'].join("\n"));
  same(chained.faults, ["need c: example=[{b: 4}, 5]: $/b is a formula: put the values that it reads"]);
});
