// The evaluator: formulas, functions, actions, rows, and the component tree. Ported from the prototype's test.js.

import { test } from "bun:test";
import { AppFile, parseExpression, show } from "../src/dsl.js";
import { App } from "../src/core.js";
import { report, runChecks, smoke } from "../src/check.js";
import { expand } from "../src/parts.js";
import { assert, fixture, gateEach, planned, same } from "./helpers.js";

gateEach("checks");
const value = (text) => new App(new AppFile()).value(parseExpression(text), {});

test("expressions: precedence, row fields, printing and parsing again", () => {
  const app0 = new App(new AppFile().feedText('$/a = 6\n$/rows = [{n: 2, on: true}, {n: 5, on: false}]\n$/b := $/a * 2 + sum($/rows, $n) > 10 ? "big" : "small"'));
  same(app0.state.b, "big", "formula");
  same(app0.value(parseExpression(show(parseExpression("count($/rows, $on && $n < 3) + -1 * (2 - 5)"))), {}), 4, "print and parse again");
});

test("row actions on a sorted view act on the stored list", async () => {
  const shop = new AppFile().feedText(fixture("shopping"));
  await expand(shop);
  const live = new App(shop);
  same(live.tree().children[1].children[0].children.map((row) => row.children[1].props.text), ["Bakery", "Dairy", "Produce"], "list drawn in aisle order");
  same(live.press("items_remove", 2), "", "remove third drawn row");
  same(live.state.items.map((row) => row.name), ["Milk", "Bread"], "the stored list lost Apples");
  same(runChecks(shop).map((check) => check.pass), [true], "shopping check");
});

test("row N of a list, a field of nothing, and an action that runs only when a test is true", () => {
  const deck = new App(new AppFile().feedText('$/rows = [{t: "a"}, {t: "b"}]\n$/i = 1\n$/now := at($/rows, $/i)\n$/title := text($/now/t)\n$/hits = 0\nroot = Column([go])\ngo = Button("Go", action=[when($/now/t == "b", inc($/hits)), set($/i, $/i + 1), when($/now/t == "b", inc($/hits))])'));
  same([deck.state.title, deck.faults], ["b", []], "at reads a row by its index");
  same(deck.press("go"), "", "press");
  same([deck.state.hits, deck.state.now, deck.state.title], [1, null, ""], "when runs its action only on a true test, and a field of nothing is nothing");
});

test("a tap on a field and a set on a button say what to write", () => {
  const kinds = new App(new AppFile().feedText('$/on = false\n$/n = 0\nroot = Column([sw, go])\nsw = Toggle("On", $/on)\ngo = Button("Go", action=inc($/n))'));
  assert(kinds.press("sw").includes("sw is a field with no action") && kinds.press("sw").includes("set(sw, value)"), `tap on a field: ${kinds.press("sw")}`);
  assert(kinds.input("go", 1).includes("go is not a field") && kinds.input("go", 1).includes("tap(go)"), `set on a button: ${kinds.input("go", 1)}`);
  same(kinds.press("nope"), "no control named nope", "a name that is not on the screen");
});

test("places, items of a list, and rows that move", () => {
  const grid = new AppFile().feedText('$/rows = [{name: "a", days: [false, false], done: false}, {name: "b", days: [true, false], done: false}]\n$/sel = "b"\n$/first := $/rows[0]/name\n$/second := at($/rows, 1)/name\nroot = Column([list, mark, up])\nlist = List($/rows, row)\nrow = Row([day0, day0text, rowUp])\nday0 = Button("Mon", action=toggle($days[0]), selected=$days[0])\nday0text = Text($days[0] ? "yes" : "no")\nrowUp = IconButton("ArrowUp", action=move(-1))\nmark = Button("Mark", action=set(find($/rows, $name == $/sel)/done, true))\nup = Button("Up", action=move($/rows, $name == "b", -1))');
  const g = new App(grid);
  same([g.state.first, g.state.second, g.faults], ["a", "b", []], "item N of a list, and a field after a call");
  same([g.press("day0", 0), g.state.rows[0].days[0], g.find("day0", 0).props.selected], ["", true, true], "an action writes item N of a list in a row, and the button shows it");
  same([g.press("mark"), g.state.rows[1].done], ["", true], "an action sets a field of the row that a test finds");
  same([g.press("up"), g.state.rows.map((r) => r.name), g.press("rowUp", 1), g.state.rows.map((r) => r.name), g.press("rowUp", 0), g.state.rows.map((r) => r.name)],
    ["", ["b", "a"], "", ["a", "b"], "", ["a", "b"]], "move changes the place of a row, and stays inside the list");
});

test("a chart has one point for each row", () => {
  const charted = new App(new AppFile().feedText('$/days = [{d: "Mo", n: 3}, {d: "Tu", n: 5}]\nroot = Column([c])\nc = Chart($/days, $d, $n * 2, kind="line")'));
  same([charted.find("c").props.points, charted.faults], [[{ label: "Mo", value: 6 }, { label: "Tu", value: 10 }], []], "chart points");
});

test("a field takes an action, and a set on a component that only shows a value says so", () => {
  const acting = new App(new AppFile().feedText('$/a = false\n$/n = 0\nroot = Column([box])\nbox = CheckBox("A", $/a, action=inc($/n))'));
  same([acting.faults, acting.input("box", true), acting.state.n], [[], "", 1], "a field with an action");
  const shown2 = new App(new AppFile().feedText('$/n = 1\nroot = Column([b])\nb = Badge($/n)'));
  assert(shown2.input("b", 2).includes("only shows a value"), `set on a Badge: ${shown2.input("b", 2)}`);
});

test("a row field that no row has is a fault of the line that reads it", () => {
  const noField = new App(new AppFile().feedText('$/rows = [{name: "a"}]\nroot = Column([list])\nlist = List($/rows, row)\nrow = Row([Text($name), Text("Streak " + text($streak))])'));
  noField.tree(true);
  assert(noField.faults.some((fault) => fault.message.includes("the row has no field streak")), `a missing row field: ${JSON.stringify(noField.faults)}`);
});

test("$it reads a plain row, and a field of a plain row is a fault", () => {
  const plain = new App(new AppFile().feedText('$/types = ["road", "city"]\n$/has := any($/types, $it == "city")\n$/bad := any($/types, $x == "city")'));
  same([plain.state.has, [...new Set(plain.faults.map((fault) => fault.message))]], [true, ["the row is a plain value with no fields. $it reads the row itself"]], "$it reads a plain row, and a field of a plain row is a fault");
});

test("a row of a list that a formula sorts still acts on the source list", () => {
  const derived = new AppFile().feedText('$/items = [{n: "b"}, {n: "a"}]\n$/sorted := sort($/items, $n)\nroot = Column([list])\nlist = List($/sorted, row)\nrow = Row([Text($n), del])\ndel = IconButton("Trash", action=remove())');
  const dv = new App(derived);
  same([dv.press("del", 0), dv.state.items.map((row) => row.n)], ["", ["b"]], "remove in a row of a sorted formula removes from the source list");
});

test("a list that a formula chooses keeps its source list, and a field binds to an item of a list", () => {
  const chosen = new AppFile().feedText('$/items = [{n: "b"}, {n: "a"}]\n$/byName = true\n$/shown := $/byName ? sort($/items, $n) : $/items\n$/names = ["x", "y"]\nroot = Column([list, first, tags])\nlist = List($/shown, row)\nrow = Row([Text($n), del])\ndel = IconButton("Trash", action=remove())\nfirst = TextField("First", $/items[0]/n)\ntags = List($/names, tag)\ntag = Button("Tag", action=set($x, 1))');
  const ch = new App(chosen);
  same([ch.press("del", 0), ch.state.items.map((row) => row.n), ch.input("first", "z"), ch.state.items[0].n, ch.faults], ["", ["b"], "", "z", []], "a row of a chosen list acts on the source, and a field binds to an item of a list");
  assert(ch.press("tag", 0).includes("plain value"), `a field of a plain row cannot be set: ${ch.press("tag", 0)}`);
});

test("a row field outside a list row is a fault", () => {
  const stray = new App(new AppFile().feedText('$/who = "je"\n$/x := $who + "!"\nroot = Column([Text($who)])'));
  stray.tree(true);
  assert(stray.faults.filter((fault) => fault.message.includes("there is no row here")).length >= 2, `a row field with no row: ${JSON.stringify(stray.faults)}`);
});

test("min and max take a list, + joins two lists, and streak is the longest run", () => {
  same(new App(new AppFile().feedText("$/a := max(map([{n: 2}, {n: 5}], $n))\n$/b := min([4, 3], 9)\n$/c := max([])\n$/d := len([1] + [2, 3])")).state, { a: 5, b: 3, c: 0, d: 3 }, "min and max of a list");
  same(new App(new AppFile().feedText("$/a := streak([true, true, false, true, true, true, false])\n$/b := streak([])")).state, { a: 3, b: 0 }, "streak");
});

test("hours over midnight, groups, sort by two keys, and today", () => {
  same(new App(new AppFile().feedText('$/a := hours("23:00", "07:00")\n$/b := hours("23:30", "06:00")\n$/c := hours("", "07:00")\n$/d := hours("09:15", "09:15")')).state, { a: 8, b: 6.5, c: 0, d: 0 }, "hours");
  same(new App(new AppFile().feedText('$/rows = [{c: "Food", a: 10}, {c: "Bus", a: 3}, {c: "Food", a: 15}]\n$/sums := groups($/rows, $c, $a)\n$/counts := groups($/rows, $c)')).state.sums, [{ key: "Food", value: 25 }, { key: "Bus", value: 3 }], "groups gives the sum for each key");
  same(new App(new AppFile().feedText('$/rows = [{c: "Food", a: 10}, {c: "Bus", a: 3}, {c: "Food", a: 15}]\n$/counts := groups($/rows, $c)')).state.counts, [{ key: "Food", value: 2 }, { key: "Bus", value: 1 }], "groups with no value counts the rows");
  same(new App(new AppFile().feedText('$/rows = [{d: "Sun", t: "09:00"}, {d: "Sat", t: "14:00"}, {d: "Sat", t: "09:00"}]\n$/out := map(sort($/rows, [$d, $t]), $d + " " + $t)')).state.out, ["Sat 09:00", "Sat 14:00", "Sun 09:00"], "sort by two keys");
  assert(/^\d{4}-\d{2}-\d{2}$/.test(new App(new AppFile().feedText("$/d := today()")).state.d), "today is a date");
});

test("tabs: a tap on a tab opens its group and hides the other ones", () => {
  const tabbed = new AppFile().feedText('$/n = 0\n$/m = 0\n$/k = 0\nroot = Column([tabs])\ntabs = Tabs(["One", "Two", "Three"], [[Text("n " + $/n), plus], [Text("m " + $/m), more], [Text("k " + $/k), last]])\nplus = Button("Plus", action=inc($/n))\nmore = Button("More", action=inc($/m))\nlast = Button("Last", action=inc($/k))');
  const tb = new App(tabbed);
  same([tb.faults, tb.find("tabs").children.slice(1).map((group) => group.props.visible), tb.find("tabs_tab0").props.selected, tb.press("tabs_tab1"), tb.find("tabs").children.slice(1).map((group) => group.props.visible), tb.find("tabs_tab1").props.selected, tb.state.n],
    [[], [true, false, false], true, "", [false, true, false], true, 0], "a tab hides the other groups");
  same(smoke(tabbed).faults, [], "the smoke run reaches the controls of each tab");
});

test("dialog: a window that a test shows, and a dialog that never closes is a fault", () => {
  const asked = new AppFile().feedText('$/rows = [1, 2]\n$/asking = false\nroot = Column([Text("rows " + len($/rows)), ask, confirm])\nask = Button("Delete all", action=set($/asking, true))\nconfirm = Dialog("Delete everything?", [yes, no], visible=$/asking)\nyes = Button("Yes", action=[clear($/rows), set($/asking, false)])\nno = Button("No", action=set($/asking, false))');
  same([new App(asked).find("confirm").props.title, new App(asked).find("confirm").props.visible, smoke(asked).faults], ["Delete everything?", false, []], "a dialog");
  const stuck = new App(new AppFile().feedText('root = Column([d])\nd = Dialog("Hello", [Text("x")])'));
  stuck.tree(true);
  assert(stuck.faults.some((fault) => fault.message.includes("a Dialog needs visible=test")), `a dialog that never closes: ${JSON.stringify(stuck.faults)}`);
});

// The prototype ran this block after the loop of odd need lines, whose last case ran with the gate of checks.
test("plain math of the platform: pow gives the monthly payment of a loan", async () => {
  // 120000 at 3.6% for 20 years: r = 0.003, n = 240, payment 702.13, interest 48512.
  const loan = await planned('app("T")\nneed importo = input("money", "Importo", 120000)\nneed tasso = input("percent", "Tasso annuo", 3.6)\nneed anni = input("number", "Anni", 20)\n'
    + 'need rata = figure("money", "Rata mensile", $/importo * ($/tasso / 1200) / (1 - pow(1 + $/tasso / 1200, -($/anni * 12))), example=[{importo: 120000, tasso: 3.6, anni: 20}, 702.13], decimals=2)\n'
    + 'need interessi = figure("money", "Interessi totali", $/rata * $/anni * 12 - $/importo, example=[{importo: 120000, tasso: 3.6, anni: 20}, 48512.1], decimals=2)\n'
    + 'need altro = figure("number", "Math", sqrt(81) + round(exp(ln(5)), 6) + log10(1000), example=[{}, 17])');
  const result = report(loan);
  same([result.faults, result.checks.map((check) => check.pass)], [[], [true, true, true]], "pow gives the monthly payment of a loan, and sqrt, exp, ln, log10 work");
});

test("text and date functions, and a text that is not a date", () => {
  // 2026-11-18 is a Wednesday.
  same([value('split("oats, milk,, eggs ", ",")'), value('weekday("2026-11-18")'), value('month("2026-11-18")'), value('year("2026-11-18")'), value('days("2026-11-18", "2026-12-01")'), value('days("2026-12-01", "2026-11-18")'),
    value('addDays("2026-11-18", -6)'), value('addDays("2026-12-31", 1)'), value('weekday("2026-11-22")'), value('weekday("2026-11-23")')],
    [["oats", "milk", "eggs"], 3, 11, 2026, 13, -13, "2026-11-12", "2027-01-01", 7, 1], "split, weekday, month, year, days, addDays");
  same([value('weekday("soon")'), value('month("2026-02-30")'), value('days("x", "2026-11-18")'), value('addDays("", 3)'), value('split("", ",")')], [0, 0, 0, null, []], "a text that is not a date gives 0 or null, and never a throw");
});

test("sin and cos take degrees, with no float noise", () => {
  same([value("sin(90)"), value("cos(180)"), value("sin(180)"), value("cos(90)"), value("sin(30)"), value("cos(60)"), value("sin(270)"), value("160 + 110 * cos(90)")], [1, -1, 0, 0, 0.5, 0.5, -1, 160], "sin and cos of degrees");
  assert(Object.is(value("sin(180)"), 0) && Object.is(value("sin(360)"), 0) && Object.is(value("cos(270)"), 0), "sin and cos give 0, and never -0 or a number near 0");
});
