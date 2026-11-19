// The input part in the look of the baseline apps (Task 11b-2a, D): options as a map, an icon of the field, and a range of two numbers.
// A text field and a slider are live inputs of the host (Task 11c): the host sends the value as a liveInput event while the person types or drags.

import { expect, test } from "bun:test";
import { report, smoke } from "../src/check.js";
import { handle } from "../src/run.js";
import { T0, built, every, live, planned, press, valueAt } from "./helpers.js";

test("live: each text field is a live input, at the top, in a row template, and in a bottom sheet", async () => {
  const { definition } = await built('app("T", lang="en")', 'need name = input("text", "Name", "")', 'need tasks = entries("Tasks", fields={task: "text"}, sheet=true, rows=[])',
    'need doses = entries("Doses", fields={dose: "text"}, edit=true, rows=[{dose: "A"}])');
  const inputs = every(definition.ui).filter((node) => node.type === "input");
  expect(inputs.map((node) => [node.action, node.inputMode])).toEqual([["name_field", "live"], ["tasks_new_task", "live"], ["doses_dose", "live"], ["doses_new_dose", "live"]]);
});

test("live: a liveInput sets the text with no ui when the screen does not change, and an action or a value of a wrong type changes nothing", async () => {
  const { definition, app } = await built('app("T", lang="en")', 'need name = input("text", "Name", "")', 'need size = figure("count", "Letters", len($/name), example=[{name: "ab"}, 2])',
    'need n = input("number", "N", 1, min=0, max=9)', 'need go = button("Go", inc($/n))');
  const start = definition.initialState, shown = every(definition.ui).find((node) => node.type === "metric").bind;
  const typed = live(start, app, "name_field", "Latte");
  expect(Object.keys(typed)).toEqual(["state"]);
  expect([typed.state.name, valueAt(typed.state, shown)]).toEqual(["Latte", "5"]);
  // The host never sends an action for a live control, and a text field takes only a text.
  for (const [type, value] of [["action", "Latte"], ["liveInput", 5], ["liveInput", null], ["liveInput", ["a"]]]) {
    expect(handle({ artifactId: "kit-test", revision: 1, state: start, event: { type, actionId: "name_field", value } }, app)).toEqual({ state: start });
  }
  // A number field and a button have no live input.
  expect(live(start, app, "n_field", 3)).toEqual({ state: start });
  expect(live(start, app, "go")).toEqual({ state: start });
  expect(press(start, app, "n_field", 3).state.n).toBe(3);
});

test("live: a text field of a row sets its row by the key, and a text field of an open add sheet leaves the sheet open", async () => {
  const { definition, app } = await built('app("T", lang="en")', 'need doses = entries("Doses", fields={dose: "text"}, edit=true, rows=[{dose: "A"}, {dose: "B"}])',
    'need tasks = entries("Tasks", fields={task: "text"}, sheet=true, rows=[])');
  const start = definition.initialState, key = start.doses[1].id;
  const renamed = live(start, app, "doses_dose", { key, value: "Bee" });
  expect(renamed.state.doses.map((row) => row.dose)).toEqual(["A", "Bee"]);
  expect(press(start, app, "doses_dose", { key, value: "Bee" })).toEqual({ state: start });
  expect(live(start, app, "doses_dose", { key: "nothing", value: "Bee" })).toEqual({ state: start });
  // The field of a closed sheet is hidden. In the open sheet the text goes in and the sheet stays open, and the add button closes it.
  expect(live(start, app, "tasks_new_task", "Call")).toEqual({ state: start });
  const opened = press(start, app, "tasks_open"), typed = live(opened.state, app, "tasks_new_task", "Call");
  expect([typed.state.tasks_s.adding, typed.state.tasks_s.new_task]).toEqual([true, "Call"]);
  const added = press(typed.state, app, "tasks_add");
  expect([added.state.tasks.map((row) => row.task), added.state.tasks_s.adding]).toEqual([["Call"], false]);
});

test("live: a hidden or disabled text field changes nothing, and a typed text that shows a second text field lets it take a text", async () => {
  const { definition, app } = await built('app("T", lang="en")', '$/a = ""', '$/b = ""', '$/c = ""', '$/e = ""', 'root = Column([aField, bField, cField, box])',
    'aField = TextField("A", $/a)', 'bField = TextField("B", $/b, visible=len($/a) > 0)', 'cField = TextField("C", $/c, enabled=len($/a) > 0)', 'box = Card([TextField("E", $/e)], visible=len($/a) > 0)');
  const start = definition.initialState;
  for (const id of ["bField", "cField", "box_0"]) expect(live(start, app, id, "x")).toEqual({ state: start });
  let state = live(start, app, "aField", "go").state;
  for (const id of ["bField", "cField", "box_0"]) state = live(state, app, id, id).state;
  expect([state.a, state.b, state.c, state.e]).toEqual(["go", "bField", "cField", "box_0"]);
});

test("live: a slider is a live input that takes a finite number in its bounds, and a range slider and a number field stay as they are", async () => {
  const { definition, app } = await built('app("T", lang="en")', 'need tip = input("percent", "Tip %", 15, min=0, max=30)', 'need total = figure("money", "Total", 100 * (1 + $/tip / 100), example=[{tip: 10}, 110])',
    'need price = input("range", "Price", [100, 500], min=0, max=1000)', 'need n = input("number", "N", 1, min=0, max=9)');
  const controls = every(definition.ui).filter((node) => ["slider", "rangeSlider", "numberInput"].includes(node.type));
  expect(controls.map((node) => [node.action, node.inputMode])).toEqual([["tip_field", "live"], ["price_field", undefined], ["n_field", undefined]]);
  const start = definition.initialState, shown = every(definition.ui).find((node) => node.type === "metric").bind;
  const moved = live(start, app, "tip_field", 20);
  expect(Object.keys(moved)).toEqual(["state"]);
  expect([moved.state.tip, valueAt(moved.state, shown) === valueAt(start, shown)]).toEqual([20, false]);
  // The same bounds as an action had: a number past the max comes back at the max.
  expect(live(start, app, "tip_field", 50).state.tip).toBe(30);
  for (const value of ["20", null, [20]]) expect(live(start, app, "tip_field", value)).toEqual({ state: start });
  expect(press(start, app, "tip_field", 20)).toEqual({ state: start });
  expect(live(start, app, "price_field", [200, 300])).toEqual({ state: start });
  expect(press(start, app, "price_field", [200, 300]).state.price).toEqual([200, 300]);
});

test("live: a slider in a list row and in an add sheet takes a liveInput, and a hidden live field of a row changes nothing", async () => {
  const { definition, app } = await built('app("T", lang="en")', '$/rows = [{name: "A", level: 2, note: ""}, {name: "B", level: 5, note: ""}]', "$/editing = false", "$/adding = false", "$/newLevel = 1",
    "root = Column([items, open, form])", "items = List($/rows, item)", 'item = Row([Text($name), Slider("Level", $level, min=0, max=10), TextField("Note", $note, visible=$/editing)])',
    'open = Button("Add", action=set($/adding, true))', 'form = Dialog("Add", [Slider("Level", $/newLevel, min=0, max=10), Button("Save", action=[append($/rows, {name: "C", level: $/newLevel, note: ""}), set($/adding, false)])], visible=$/adding)');
  const start = definition.initialState, key = start.rows[1].id;
  expect(every(definition.ui).filter((node) => node.inputMode === "live").map((node) => [node.type, node.action])).toEqual([["slider", "item_1"], ["input", "item_2"], ["slider", "form_0"]]);
  // The slider of a row sets its row by the key, in the bounds, and an action for it changes nothing.
  expect(live(start, app, "item_1", { key, value: 7 }).state.rows.map((row) => row.level)).toEqual([2, 7]);
  expect(live(start, app, "item_1", { key, value: 50 }).state.rows.map((row) => row.level)).toEqual([2, 10]);
  expect(press(start, app, "item_1", { key, value: 7 })).toEqual({ state: start });
  // The note field of each row is hidden while $/editing is false.
  expect(live(start, app, "item_2", { key, value: "x" })).toEqual({ state: start });
  const editing = { ...start, editing: true };
  expect(live(editing, app, "item_2", { key, value: "x" }).state.rows.map((row) => row.note)).toEqual(["", "x"]);
  // The slider of the closed sheet is hidden. In the open sheet it takes the value, and the sheet stays open.
  expect(live(start, app, "form_0", 4)).toEqual({ state: start });
  const opened = press(start, app, "open"), moved = live(opened.state, app, "form_0", 4);
  expect([moved.state.newLevel, moved.state.adding]).toEqual([4, true]);
  expect(press(moved.state, app, "form_1").state.rows.map((row) => row.level)).toEqual([2, 5, 4]);
});

test("live: a liveInput in an app with a surface timer draws the surfaces with no timers, and the timer goes on", async () => {
  const { definition, app } = await built('app("T", lang="en")', 'need t = timer("Run", 60, rounds=2, rest=10)', 'need note = input("text", "Note", "")');
  const send = (state, event, ms) => handle({ artifactId: "kit-test", revision: 1, state, event }, app, T0 + ms);
  const started = send(definition.initialState, { type: "action", actionId: "t_start", value: null }, 0);
  const typed = send(started.state, { type: "liveInput", actionId: "note_field", value: "Go" }, 5000);
  expect(Object.keys(typed).sort()).toEqual(["state", "surfaces"]);
  // The timer runs on to the same end, and its spent seconds follow the clock of the event, as they do for an action.
  expect([typed.state.note, typed.state.t_s]).toEqual(["Go", { ...started.state.t_s, spent: 5 }]);
  const ticked = send(typed.state, { type: "timer", timerId: "t_bar" }, 30000);
  expect([ticked.state.note, ticked.state.t_s.on, ticked.timers]).toEqual(["Go", true, [{ id: "t_bar", intervalMs: 1000 }]]);
});


test("inputs: a choice takes options as a map of a value to its label, and the state keeps the value", async () => {
  const { definition, app } = await built('app("Bici", lang="it")', 'need kind = input("choice", "Tipo", "road", options={all: "Tutte", road: "Strada", city: "Città"})');
  const [field] = definition.ui.children[0].children;
  expect(field).toMatchObject({ type: "segmented", text: "Tipo", bind: "kind", options: [{ text: "Tutte", value: "all" }, { text: "Strada", value: "road" }, { text: "Città", value: "city" }] });
  expect(press(definition.initialState, app, field.action, "city").state.kind).toBe("city");
  // Five options in a map: the part writes a Select, and the run-time makes six options or fewer a segmented control. A multi-choice takes a map too.
  const many = await built('app("T", lang="en")', 'need size = input("choice", "Size", "s", options={xs: "XS", s: "S", m: "M", l: "L", xl: "XL"})',
    'need extras = input("choices", "Extras", [], options={bell: "Bell", lamp: "Lamp"})');
  expect(many.definition.ui.children[0].children.map((node) => [node.type, node.options.map((option) => option.text).join(" ")])).toEqual([["segmented", "XS S M L XL"], ["multiChoice", "Bell Lamp"]]);
});

// L1 item 4 (e-packing-de: "Dokumen te"; d-rainbow: "Преломление" lost its last letter): a segmented control gives each option an equal
// share of the width, and a long label broke or shrank in it.
const choiceForms = (definition) => every(definition.ui).filter((node) => ["segmented", "singleChoice", "select"].includes(node.type)).map((node) => [node.text, node.type, node.variant, node.options.map((option) => option.text)]);

test("inputs (L1 item 4): a single choice whose labels do not fit a segmented row on the phone is a group of cells that wraps, with its title over it", async () => {
  const { readFileSync } = await import("node:fs");
  const model = (name) => readFileSync(new URL(`./fixtures/model/${name}.plan`, import.meta.url), "utf8");
  const forms = async (text) => choiceForms((await built(...text.split("\n").filter(Boolean))).definition);
  expect(await forms(model("d-rainbow"))).toEqual([["Этап", "singleChoice", "scene", ["Преломление", "Отражение", "Разложение"]]]);
  expect(await forms(model("e-packing-de"))).toEqual([["Kategorie", "singleChoice", "scene", ["Kleidung", "Hygiene", "Dokumente", "Technik", "Sonstiges"]]]);
  // Labels that fit stay a segmented row: Hangul is wide, and three short words still fit.
  expect((await forms(model("e-cafe-ko"))).map(([text, type]) => [text, type])).toEqual([["음료 종류", "segmented"], ["사이즈", "segmented"]]);
  expect((await forms(model("d-heart"))).map(([text, type]) => [text, type])).toEqual([["Проследить путь", "segmented"]]);
  // The host draws no title over the group of cells, so the group stands under a label text of its own, and its action takes the value of an option.
  const { definition, app } = await built('app("T", lang="ru")', 'need stage = input("choice", "Этап", "Преломление", options=["Преломление", "Отражение", "Разложение"])');
  const [label, cells] = definition.ui.children[0].children[0].children;
  expect([definition.ui.children[0].children[0].type, label]).toEqual(["column", { type: "text", text: "Этап", style: { emphasis: "label" } }]);
  expect(press(definition.initialState, app, cells.action, "Отражение").state.stage).toBe("Отражение");
});

test("inputs (L1 item 4): in each saved plan and model plan, a choice is a group of cells where a segmented row cut a label on the phone", async () => {
  const { allPlans } = await import("./helpers.js");
  const { plansIn } = await import("../build.js");
  const { readFileSync } = await import("node:fs");
  const { fileURLToPath } = await import("node:url");
  const { compileAppFile } = await import("../src/compile.js");
  const models = plansIn(fileURLToPath(new URL("./fixtures/model", import.meta.url))).map((path) => [path, readFileSync(path, "utf8")]);
  const cells = [];
  for (const [path, text] of [...allPlans(), ...models]) {
    const { definition } = await compileAppFile(text);
    for (const node of definition ? every(definition.ui) : []) if (node.type === "singleChoice") cells.push(`${path.split("/").pop()}: ${node.text}`);
  }
  // The list comes from the widths of the labels in the Roboto and Noto CJK fonts of the phone. In each of these choices, a selected label had less than
  // 5 dp of the segment beside its check, or a word broke. Each other segmented row keeps 6 dp or more, such as "Szklanki" of units-pl (7 dp)
  // and "Da comprare" of look-shopping-it (7 dp), and stays a segmented row.
  expect(cells.sort()).toEqual(["Categoría", "Dimensione", "Estado", "Kategorie", "Size", "Bike type", "Type", "Этап"].map((text, index) =>
    `${["tabs-chart", "showcase-ui-pizza", "holdout-reading-es", "e-packing-de", "rich", "mock-price-filter", "price-filter", "d-rainbow"][index]}.plan: ${text}`).sort());
  // In a row of a list, the group of cells has no label text over it: two more nodes in each row would leave room for fewer rows.
  const { definition } = await compileAppFile(readFileSync(new URL("./rich.plan", import.meta.url), "utf8"));
  const rows = every(definition.ui).filter((node) => node.rowTemplate).map((node) => every(node.rowTemplate));
  const labeled = (node) => node.children?.[0]?.type === "text" && node.children[1]?.type === "singleChoice";
  expect(rows.map((nodes) => [nodes.filter((node) => node.type === "singleChoice").length, nodes.some(labeled)])).toEqual([[1, false], [0, false]]);
  expect(definition.stateSchema.shirts.maxItems).toBe(91);
});

test("inputs: icon= is the icon of the field of each input that takes one", async () => {
  const { definition } = await built('app("T", lang="en")', 'need name = input("text", "Name", "", icon="Pencil")', 'need n = input("number", "N", 1, icon="Drop")',
    'need day = input("date", "Day", "2026-11-20", icon="Calendar")', 'need at = input("time", "At", "08:00", icon="Clock")',
    'need city = input("choice", "City", "a", options=["a", "b", "c", "d", "e", "f", "g"], icon="Pin")');
  expect(definition.ui.children[0].children.map((node) => [node.type, node.icon])).toEqual([["input", "Pencil"], ["numberInput", "Drop"], ["dateInput", "Calendar"], ["timeInput", "Clock"], ["select", "Pin"]]);
});

test("inputs: a range of two numbers is one range slider whose state is the low and the high number", async () => {
  // AppFileProofTest.aRangeAMapOfOptionsAndAnIconOfAFieldPassParseAndSmoke uses the same plan.
  const { definition, app } = await built('app("Bikes", lang="en")', 'need price = input("range", "Price", [100, 1500], min=0, max=2000, step=50)',
    'need shown = figure("count", "Shown", count([300, 900, 1800], $it >= $/price[0] && $it <= $/price[1]), example=[{price: [0, 2000]}, 3])',
    'need kind = input("choice", "Type", "road", options={all: "All", road: "Road", city: "City"})', 'need name = input("text", "Name", "", icon="Pencil")');
  const [slider] = every(definition.ui).filter((node) => node.type === "rangeSlider");
  expect(slider).toEqual({ type: "rangeSlider", text: "Price", bind: "price", action: "price_field", min: 0, max: 2000, step: 50 });
  expect(definition.stateSchema.price).toEqual({ type: "array", maxItems: 512, items: "number" });
  expect(definition.initialState.price).toEqual([100, 1500]);
  const moved = press(definition.initialState, app, "price_field", [500, 1000]);
  expect([moved.state.price, moved.state.d.shown_text]).toEqual([[500, 1000], "1"]);
  // A range out of order, out of the bounds, or of another length changes nothing.
  for (const wrong of [[1000, 500], [-50, 100], [100], "100"]) expect(press(moved.state, app, "price_field", wrong).state.price).toEqual([500, 1000]);
  // The smoke run moves the range to its bounds, as Alphy's smoke step does.
  const file = await planned('app("T", lang="en")\nneed price = input("range", "Price", [100, 1500], min=0, max=2000)');
  expect(smoke(file).trace.some((line) => line.startsWith("set price_field: price [2] -> [2]"))).toBe(true);
  expect(report(file).faults).toEqual([]);
});
