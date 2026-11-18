// The run-time: each event of a compiled app, through handle and through the source text in a bare context.

import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { createContext, runInContext } from "node:vm";
import { App } from "../src/core.js";
import { parseLine } from "../src/dsl.js";
import { emit, runtime } from "../src/emit.js";
import { handle, kitOf } from "../src/run.js";
import { allPlans, plan, planned } from "./helpers.js";

const compiled = async (text) => { const file = await planned(text); return { file, ...emit(file) }; };
const testPlan = (name) => readFileSync(new URL(`./${name}.plan`, import.meta.url), "utf8");
const request = (state, event) => ({ artifactId: "kit-test", revision: 1, state, event });
const nodes = (node) => [node, ...(node.children ?? []).flatMap(nodes)];
/** The values of the objects d, d2, v, and so on: the values that the run-time computes. */
const derived = (state) => Object.entries(state).filter(([key]) => /^[dv]\d*$/.test(key)).flatMap(([, values]) => Object.values(values));

test("run: an action sets the bound value and the figures follow", async () => {
  const { file, definition, app } = await compiled(plan("calc"));
  const kit = (bill) => { const prototype = new App(file); prototype.input("bill_field", bill); return prototype.state; };
  const first = handle(request(definition.initialState, { type: "action", actionId: "bill_field", value: 200 }), app);
  expect(first.state.d).toEqual({ perPerson_text: kit(200).perPerson_text, total_text: kit(200).total_text });
  const { state } = handle(request(first.state, { type: "action", actionId: "bill_field", value: 80 }), app);
  expect(state.bill).toBe(80);
  expect(state.d.perPerson_text).not.toBe(first.state.d.perPerson_text);
  expect(state.d).toEqual({ perPerson_text: kit(80).perPerson_text, total_text: kit(80).total_text });
});

test("run: open of the initial state gives the initial state", async () => {
  // Each saved plan and fixture that compiles. The run-time reads no start value: each value comes from the state.
  // An app with a timer on a surface also gets the drawing of each surface and no timer that runs.
  const opened = [];
  for (const [path, text] of allPlans()) {
    const { definition, app } = await compiled(text);
    if (!definition) continue;
    const reply = handle(request(definition.initialState, { type: "open" }), app);
    expect(reply).toEqual({ state: definition.initialState, ...(definition.surfaces && { surfaces: reply.surfaces, timers: [] }) });
    opened.push(path);
  }
  expect(opened).toHaveLength(16);
  expect(opened).toEqual(expect.arrayContaining(["plans/tea.plan", "plans/timer-rounds.plan"]));
});

test("run: the kit of an event computes its formulas on the stored state, with no fault", async () => {
  // A kit that computes before it reads the state counts the rows of a list that is not there yet: "count needs a list".
  const checked = [];
  for (const [path, text] of allPlans()) {
    const { definition, app } = await compiled(text);
    if (!definition) continue;
    expect([path, kitOf(app, definition.initialState).faults]).toEqual([path, []]);
    checked.push(path);
  }
  expect(checked).toContain("plans/list.plan");
});

test("run: render and preview change no state", async () => {
  const { definition, app } = await compiled(plan("calc"));
  // A state whose figures do not follow its inputs: a read-only event must still give it back as it is.
  const stale = { ...definition.initialState, bill: 500 };
  for (const type of ["preview", "render"]) expect(handle(request(stale, { type }), app)).toEqual({ state: stale });
});

test("run: extreme inputs keep each derived value finite", async () => {
  const { definition, app } = await compiled(`${plan("calc")}need note = input("text", "Note", "Hi")\nneed echo = figure("text", "Echo", upper($/note))\n`
    + 'need zero = input("number", "Divisor", 1, min=0, max=5)\nneed ratio = content(10 / $/zero)\n');
  const samples = nodes(definition.ui).flatMap((node) => node.type === "input" ? [[node, ""], [node, "Preview"]]
    : ["numberInput", "slider"].includes(node.type) ? [[node, node.min], [node, node.max]] : []);
  expect(samples.map(([node]) => node.type).sort()).toEqual(["input", "input", ...Array(6).fill("numberInput"), "slider", "slider"]);
  for (const [node, value] of samples) {
    const { state } = handle(request(definition.initialState, { type: "action", actionId: node.action, value }), app);
    expect(state[node.bind]).toBe(value);
    for (const shown of derived(state)) expect(typeof shown === "string" || Number.isFinite(shown)).toBe(true);
  }
  // 10 / 0 is Infinity in the kit, and a shown number that is not finite is 0.
  const { state } = handle(request(definition.initialState, { type: "action", actionId: "zero_field", value: 0 }), app);
  expect(state.d.ratio_view_text).toBe(0);
});

test("run: a shown value keeps the type of its schema field when its formula changes type", async () => {
  const { definition, app } = await compiled([
    'app("Kinds", lang="en")', "$/n = 0", "root = Column([field, word, count])", 'field = NumberField("N", $/n, min=0, max=10)',
    'word = Text(if($/n > 0, $/n * 2, "none"))', 'count = Metric("Count", if($/n > 0, "many", 1))',
  ].join("\n"));
  expect(definition.stateSchema.d.fields).toEqual({ word_text: "string", count_value: "number" });
  const { state } = handle(request(definition.initialState, { type: "action", actionId: "field", value: 3 }), app);
  // A text field takes the number as text. A number field takes 0 for a value that is not a number.
  expect(state.d).toEqual({ word_text: "6", count_value: 0 });
});

test("run: a reply holds a new ui only when the screen changes", async () => {
  const { definition, app } = await compiled([
    'app("Pick", lang="en")', "$/on = false", "$/count = 0", "root = Column([pick, more, shown])",
    'pick = Button("Pick", action=toggle($/on), selected=$/on)', 'more = Button("More", action=inc($/count))', 'shown = Metric("Count", $/count)',
  ].join("\n"));
  const picked = handle(request(definition.initialState, { type: "action", actionId: "pick", value: null }), app);
  expect(picked.state.on).toBe(true);
  expect(nodes(picked.ui).find((node) => node.action === "pick").variant).toBe("filled");
  expect(nodes(definition.ui).find((node) => node.action === "pick").variant).toBe("tonal");
  const more = handle(request(picked.state, { type: "action", actionId: "more", value: null }), app);
  expect(more).toEqual({ state: { ...picked.state, count: 1 } });
});

test("run: an action id that the app does not have changes nothing", async () => {
  const { definition, app } = await compiled(plan("calc"));
  for (const actionId of ["nothing", "toString"]) {
    expect(handle(request(definition.initialState, { type: "action", actionId, value: 1 }), app)).toEqual({ state: definition.initialState });
  }
});

/** The rows of the List line name as the kit draws them, by the id of each row. */
const shownIds = (prototype, name) => prototype.find(name).children.filter((node) => node.row).map((node) => node.row.id);
/** The events of one app in sequence, each on the state of the reply before it. */
const session = (definition, app) => {
  let state = definition.initialState;
  return {
    get state() { return state; },
    act: (actionId, value = null) => { const reply = handle(request(state, { type: "action", actionId, value }), app); state = reply.state; return reply; },
  };
};
/**
 * The compiled app and the kit's App of one plan, and act, which sends each event to both: a field value, or a press when the
 * value is null. A row control names its row by the key for the run-time, and by the row's place in the List line for the kit.
 */
const twins = async (text) => {
  const { file, definition, app } = await compiled(text);
  const run = session(definition, app), prototype = new App(file);
  const act = (name, value = null, list, key) => {
    run.act(name, key === undefined ? value : { key, value });
    const index = key === undefined ? undefined : shownIds(prototype, list).indexOf(key);
    if (value === null) prototype.press(name, index); else prototype.input(name, value, index);
  };
  return { run, prototype, act };
};

test("run: add, check, and delete a row", async () => {
  const { run, prototype, act } = await twins(plan("list"));
  const same = () => {
    expect(run.state.prodotti).toEqual(prototype.state.prodotti);
    expect(run.state.d.daComprare_text).toBe(prototype.state.daComprare_text);
    expect(run.state.prodotti_s).toEqual({ new_nome: prototype.state.prodotti_new_nome, new_corsia: prototype.state.prodotti_new_corsia });
  };
  act("prodotti_new_nome", "Burro");
  act("prodotti_new_corsia", "Latticini");
  act("prodotti_add");
  same();
  expect(run.state.prodotti).toHaveLength(4);
  const burro = run.state.prodotti.find((row) => row.nome === "Burro").id;
  // A row control finds its row by the key. The kit finds it by its place in the list on the screen.
  act("prodotti_preso", true, "prodotti_list", burro);
  same();
  expect(run.state.prodotti.find((row) => row.id === burro).preso).toBe(true);
  expect(run.state.d.daComprare_text).toBe("3");
  act("prodotti_remove", null, "prodotti_list", burro);
  same();
  expect(run.state.prodotti.map((row) => row.id)).toEqual(["r1", "r2", "r3"]);
});

test("run: a list at its limits", async () => {
  const rows = Array.from({ length: 98 }, (_, index) => `{name: "Item ${index + 1}"}`).join(", ");
  const { definition, app } = await compiled(['app("Items", lang="en")', `need items = entries("Items", fields={name: "text"}, rows=[${rows}])`].join("\n"));
  const run = session(definition, app);
  // Two rows in two events: each event starts a new kit, and each new row still gets an id of its own.
  for (const name of ["A", "B"]) { run.act("items_new_name", name); run.act("items_add"); }
  const ids = run.state.items.map((row) => row.id);
  expect(ids).toHaveLength(100);
  expect(new Set(ids).size).toBe(100);
  expect(run.state.items.at(-1).name).toBe("B");
  run.act("items_new_name", "C");
  const full = run.state;
  // The 101st row: Alphy keeps at most 100 rows, so the event changes nothing.
  expect(handle(request(full, { type: "action", actionId: "items_add", value: null }), app)).toEqual({ state: full });
  // A delete with a key that does not exist changes nothing.
  expect(handle(request(full, { type: "action", actionId: "items_remove", value: { key: "nothing", value: null } }), app)).toEqual({ state: full });
  // Rows that cost more render work hold fewer rows: each list of rich.plan starts full to its count.
  const rich = await compiled(testPlan("rich")), count = rich.definition.stateSchema.shirts.maxItems;
  expect(count).toBeLessThan(100);
  const wardrobe = session(rich.definition, rich.app);
  wardrobe.act("shirts_remove", { key: "r1", value: null });
  wardrobe.act("shirts_new_name", "Scarf");
  wardrobe.act("shirts_add");
  expect(wardrobe.state.shirts).toHaveLength(count);
  expect(wardrobe.state.shirts.at(-1).name).toBe("Scarf");
  // The add form is ready, so only the count refuses the next row.
  wardrobe.act("shirts_new_name", "Tie");
  const fullRich = wardrobe.state;
  expect(handle(request(fullRich, { type: "action", actionId: "shirts_add", value: null }), rich.app)).toEqual({ state: fullRich });
  // The delete of the last row gives an empty list, a second delete of the same key changes nothing, and the next add gets a new id.
  const one = await compiled(['app("One", lang="en")', 'need items = entries("Items", fields={name: "text"}, rows=[{name: "Only"}])'].join("\n"));
  const single = session(one.definition, one.app);
  single.act("items_remove", { key: "r1", value: null });
  expect(single.state.items).toEqual([]);
  const empty = single.state;
  expect(handle(request(empty, { type: "action", actionId: "items_remove", value: { key: "r1", value: null } }), one.app)).toEqual({ state: empty });
  single.act("items_new_name", "Next");
  single.act("items_add");
  expect(single.state.items).toEqual([{ id: "r2", name: "Next" }]);
});

test("run: a reorder event moves the row", async () => {
  const { file, definition, app } = await compiled(['app("Steps", lang="en")', 'need steps = entries("Steps", fields={step: "text"}, reorder=true, rows=[{step: "Pack"}, {step: "Drive"}, {step: "Hike"}])'].join("\n"));
  const move = nodes(definition.ui).find((node) => node.reorder).reorder.action;
  const run = session(definition, app), prototype = new App(file);
  // The first row goes to the place of the last row. The kit moves it down two times.
  run.act(move, { sourceKey: "r1", destinationKey: "r3" });
  prototype.press("steps_down", 0); prototype.press("steps_down", 1);
  expect(run.state.steps).toEqual(prototype.state.steps);
  expect(run.state.steps.map((row) => row.step)).toEqual(["Drive", "Hike", "Pack"]);
  expect(run.state.steps_list.map((row) => row.id)).toEqual(["r2", "r3", "r1"]);
  // A key that is not a row of the list changes nothing.
  const before = run.state;
  expect(handle(request(before, { type: "action", actionId: move, value: { sourceKey: "r1", destinationKey: "nothing" } }), app)).toEqual({ state: before });
});

test("run: a sorted view follows the stored list", async () => {
  const { run, prototype, act } = await twins(plan("list"));
  const follows = () => {
    expect(run.state.prodotti_list.map((row) => row.id)).toEqual(shownIds(prototype, "prodotti_list"));
    // Each row of the view is its row of the stored list.
    for (const row of run.state.prodotti_list) expect(row).toEqual(run.state.prodotti.find((stored) => stored.id === row.id));
  };
  follows();
  act("prodotti_new_nome", "Acqua");
  act("prodotti_new_corsia", "Bevande");
  act("prodotti_add");
  follows();
  expect(run.state.prodotti_list[0].nome).toBe("Acqua");
  // The row control of the view changes the row of the stored list.
  act("prodotti_preso", true, "prodotti_list", "r2");
  follows();
  expect(run.state.prodotti.find((row) => row.id === "r2").preso).toBe(true);
});

test('run: a time field reads "07:30" as 450 minutes and writes it back', async () => {
  const { file, definition, app } = await compiled([
    'app("Times", lang="en")', 'need wake = input("time", "Wake", "07:30")', 'need left = figure("number", "Hours to nine", hours($/wake, "09:00"))',
    'need naps = entries("Naps", fields={start: "time"}, edit=true, rows=[{start: "13:15"}])', 'need first = figure("text", "First nap", at($/naps, 0)/start)',
  ].join("\n"));
  expect(definition.stateSchema.wake).toBe("number");
  expect(definition.stateSchema.naps.fields.start).toBe("number");
  const prototype = new App(file);
  expect(definition.initialState).toMatchObject({ wake: 450, naps: [{ id: "r1", start: 795 }], d: { left_text: prototype.state.left_text, first_text: "13:15" } });
  expect(handle(request(definition.initialState, { type: "open" }), app)).toEqual({ state: definition.initialState });
  const run = session(definition, app);
  run.act("wake_field", 480); prototype.input("wake_field", "08:00");
  expect(run.state.wake).toBe(480);
  expect(run.state.d.left_text).toBe(prototype.state.left_text);
  expect(run.state.d.left_text).toBe("1");
  run.act("naps_start", { key: "r1", value: 800 }); prototype.input("naps_start", "13:20", 0);
  expect(run.state.naps).toEqual([{ id: "r1", start: 800 }]);
  expect(run.state.d.first_text).toBe(prototype.state.first_text);
  expect(run.state.d.first_text).toBe("13:20");
});

test("run: a bound number past its bounds comes back at its bound", async () => {
  const { definition, app } = await compiled([
    'app("Bounds", lang="en")', "$/n = 9", "root = Column([field, more])", 'field = NumberField("N", $/n, min=0, max=10)', 'more = Button("More", action=inc($/n, 5))',
    'need counts = entries("Counts", fields={c: "count"}, add=false, remove=false, rows=[{c: 1000000}])',
  ].join("\n"));
  const run = session(definition, app);
  run.act("more");
  expect(run.state.n).toBe(10);
  // The number field of a count in a row has the bounds 0 to 1000000.
  run.act("counts_c_plus", { key: "r1", value: null });
  expect(run.state.counts).toEqual([{ id: "r1", c: 1000000 }]);
});

test("run: the add sheet opens, adds a row, and closes", async () => {
  const { definition, app } = await compiled(['app("Tasks", lang="en")', 'need tasks = entries("Tasks", fields={task: "text", note: "text", due: "date"}, rows=[])'].join("\n"));
  const run = session(definition, app);
  run.act("tasks_addRow_open");
  expect(run.state.tasks_s.adding).toBe(true);
  run.act("tasks_addRow_dismiss");
  expect(run.state.tasks_s.adding).toBe(false);
  run.act("tasks_addRow_open");
  run.act("tasks_new_task", "Call");
  run.act("tasks_new_due", "2026-12-01");
  // An add that the kit takes closes the sheet.
  run.act("tasks_add");
  expect(run.state.tasks).toEqual([{ id: "r1", task: "Call", note: "", due: "2026-12-01" }]);
  expect(run.state.tasks_s.adding).toBe(false);
  // A second button of the form, such as a clear button, leaves the sheet open: only the add button of the form closes it.
  const custom = await compiled(['app("Tasks", lang="en")', 'need tasks = entries("Tasks", fields={task: "text", note: "text", due: "date"}, rows=[])',
    "tasks_addRow = Row([tasks_new_task, tasks_new_note, tasks_new_due, tasks_clear, tasks_add])", 'tasks_clear = Button("Clear", action=[set($/tasks_new_task, ""), set($/tasks_new_note, "")])'].join("\n"));
  // The button that opens the sheet has the caption of the add button.
  expect(nodes(custom.definition.ui).find((node) => node.action === "tasks_addRow_open").text).toBe("Add");
  const cleared = session(custom.definition, custom.app);
  cleared.act("tasks_addRow_open");
  cleared.act("tasks_new_task", "Call");
  cleared.act("tasks_clear");
  expect(cleared.state.tasks_s).toMatchObject({ adding: true, new_task: "" });
  cleared.act("tasks_new_task", "Mail");
  cleared.act("tasks_add");
  expect(cleared.state.tasks.map((row) => row.task)).toEqual(["Mail"]);
  expect(cleared.state.tasks_s.adding).toBe(false);
});

test("run: a tab press shows its group and hides the others", async () => {
  const { file, definition, app } = await compiled(plan("tabs-chart"));
  const run = session(definition, app), prototype = new App(file);
  const groups = () => [run.state.v.resumen_view_tab0, run.state.v.resumen_view_tab1];
  // The kit presses the button of the tab, and Alphy's tabs control gives the title of the tab.
  run.act("resumen_view", "Añadir");
  prototype.press("resumen_view_tab1");
  expect(run.state.resumen_s.tab).toBe("Añadir");
  expect(groups()).toEqual(prototype.find("resumen_view").children.slice(1).map((group) => group.props.visible));
  expect(groups()).toEqual([false, true]);
  // A title that is not a tab changes nothing, and a start value that names no tab opens the first tab.
  const open = run.state;
  expect(handle(request(open, { type: "action", actionId: "resumen_view", value: "Nada" }), app)).toEqual({ state: open });
  const lost = handle(request({ ...open, resumen_s: { tab: "Nada" } }, { type: "open" }), app).state;
  expect([lost.resumen_s.tab, lost.v.resumen_view_tab0, lost.v.resumen_view_tab1]).toEqual(["Resumen", true, false]);
  // A title that changes takes the open tab with it to the first tab.
  const renamed = await compiled(['app("Names", lang="en")', '$/first = "A"', "root = Column([pages, rename, clash])", 'one = Text("One")', 'two = Text("Two")',
    'pages = Tabs([$/first, "B"], [[one], [two]])', 'rename = Button("Rename", action=set($/first, "Z"))', 'clash = Button("Clash", action=set($/first, "B"))'].join("\n"));
  const names = session(renamed.definition, renamed.app);
  expect(names.state.pages_tab).toBe("A");
  names.act("rename");
  expect(names.state.pages_tab).toBe("Z");
  // Titles that become the same text are numbered, so the reply still holds a tabs control that Alphy takes, and the first tab opens.
  const clashed = names.act("clash");
  expect(nodes(clashed.ui).find((node) => node.type === "tabs").options.map((option) => option.value)).toEqual(["1", "2"]);
  expect([names.state.pages_tab, names.state.v.pages_tab0, names.state.v.pages_tab1]).toEqual(["1", true, false]);
});

test("run: a quiz answer counts a point", async () => {
  // The kanji cards count each answer, and the deck with answer= counts one point for a right answer. The values are the kit's.
  const kanji = await twins(plan("quiz"));
  kanji.act("revealButton");
  kanji.act("learnedButton");
  const { run, prototype } = kanji;
  expect([run.state.learned, run.state.index, run.state.shown, run.state.d.card_kanji]).toEqual([prototype.state.learned, prototype.state.index, prototype.state.shown, prototype.state.card.kanji]);
  expect([run.state.learned, run.state.d.card_kanji]).toEqual([1, "火"]);
  const deck = await twins(testPlan("cards"));
  deck.act("words_field", "Sol ");
  deck.act("words_check");
  expect([deck.run.state.words_s.points, deck.run.state.words_s.checked]).toEqual([deck.prototype.state.words_points, deck.prototype.state.words_checked]);
  expect(deck.run.state.words_s.points).toBe(1);
});

test("run: the chart arrays follow the list", async () => {
  const { run, prototype, act } = await twins(plan("tabs-chart"));
  const follows = () => {
    const points = prototype.find("grafico_chart").props.points;
    expect(run.state.grafico_chart_labels).toEqual(points.map((point) => point.label));
    expect(run.state.grafico_chart_values).toEqual(points.map((point) => point.value));
  };
  const add = (concepto, categoria, importe) => { act("gastos_new_concepto", concepto); act("gastos_new_categoria", categoria); act("gastos_new_importe", importe); act("gastos_add"); };
  follows();
  add("Taxi", "Transporte", 300);
  follows();
  expect([run.state.grafico_chart_labels, run.state.grafico_chart_values]).toEqual([["Transporte"], [300]]);
  add("Bus", "Transporte", 20);
  add("Cine", "Ocio", 15);
  follows();
  expect([run.state.grafico_chart_labels, run.state.grafico_chart_values]).toEqual([["Transporte", "Ocio"], [320, 15]]);
  act("gastos_remove", null, "gastos_list", run.state.gastos[0].id);
  follows();
  // A chart keeps its last 256 points, and a point that Alphy's state cannot hold is its bound, or 0 when it is not a number.
  const { definition, app } = await compiled(['app("Big", lang="en")', `$/points = [${Array.from({ length: 300 }, (_, index) => index + 1).join(", ")}]`,
    "$/scale = 1", "root = Column([big, more])", "big = Chart($/points, text($it), $it * $/scale)", 'more = Button("More", action=set($/scale, 100000000000000000000))'].join("\n"));
  expect(definition.initialState.big_labels).toHaveLength(256);
  expect([definition.initialState.big_labels[0], definition.initialState.big_values.at(-1)]).toEqual(["45", 300]);
  const big = handle(request(definition.initialState, { type: "action", actionId: "more", value: null }), app).state;
  expect(big.big_values.at(-1)).toBe(Number.MAX_SAFE_INTEGER);
  expect(big.big_values.every((value) => Math.abs(value) <= Number.MAX_SAFE_INTEGER)).toBe(true);
});

test("run: a value that an entries need shows for each row reads in its sort and in a chart", async () => {
  const text = ['app("Days", lang="en")', 'need days = entries("Days", fields={day: "text", steps: "number"}, rows=[{day: "Mo", steps: 3}, {day: "Tu", steps: 5}], show={twice: $steps * 2}, sort=$twice)',
    'need bars = chart("Twice", $/days, $day, $twice)'].join("\n");
  const { definition, app } = await compiled(text);
  expect(handle(request(definition.initialState, { type: "open" }), app)).toEqual({ state: definition.initialState });
  const { run, prototype, act } = await twins(text);
  act("days_new_day", "We");
  act("days_new_steps", 1);
  act("days_add");
  expect([run.state.bars_chart_labels, run.state.bars_chart_values]).toEqual([["Mo", "Tu", "We"], [6, 10, 2]]);
  expect(run.state.bars_chart_values).toEqual(prototype.find("bars_chart").props.points.map((point) => point.value));
  expect(run.state.days_list.map((row) => row.id)).toEqual(shownIds(prototype, "days_list"));
  expect(run.state.days_list.map((row) => row.day)).toEqual(["We", "Mo", "Tu"]);
});

test("run: a question shows its result after an answer", async () => {
  const { run, prototype, act } = await twins(['app("Ask", lang="en")', 'need ask = question("Is the Moon a star?", options=["Yes", "No"], right="No", rightText="Right", wrongText="Not yet")'].join("\n"));
  expect(run.state.v.ask_result_visible).toBe(false);
  act("ask_opt0");
  expect([run.state.ask_s.answer, run.state.v.ask_result_visible, run.state.d.ask_result_text]).toEqual(["Yes", true, prototype.find("ask_result").props.text]);
  expect(run.state.d.ask_result_text).toBe("Not yet");
  act("ask_opt1");
  expect([run.state.d.ask_result_text, prototype.find("ask_result").props.text]).toEqual(["Right", "Right"]);
});

test("run: a progress value stays inside its bounds", async () => {
  const { definition, app } = await compiled([
    'app("Bars", lang="en")', "$/n = 5", "$/m = 10", "$/r := $/n / $/m", "root = Column([bar, dots, ring, share, over, zero])",
    "bar = Progress($/n, $/m)", 'dots = Progress($/n, 10, variant="dots")', 'ring = Ring($/n, $/m, "n")', "share = Progress($/r, 1)",
    'over = Button("Over", action=set($/n, 50))', 'zero = Button("Zero", action=set($/m, 0))',
  ].join("\n"));
  const run = session(definition, app), values = () => [run.state.d.bar_value, run.state.d.dots_value, run.state.d.ring_value, run.state.d.share_value];
  expect(values()).toEqual([50, 5, 50, 50]);
  run.act("over");
  expect(values()).toEqual([100, 10, 100, 100]);
  // A max of 0 gives 0, and so does a value that is not finite: 50 / 0.
  run.act("zero");
  expect(values()).toEqual([0, 10, 0, 0]);
});

test("run: a shown value stays inside Alphy's state limits", async () => {
  // Alphy's state holds a text of at most 2048 characters and a number of at most 2^53 - 1 (ToolValueSchema).
  const { definition, app } = await compiled(['app("Long", lang="en")', `$/word = "${"x".repeat(1500)}"`, "$/n = 1", "root = Column([long, big, more])",
    "long = Text($/word + $/word)", "big = Metric(\"Big\", $/n * 100000000000000000000)", 'more = Button("More", action=set($/n, -1))'].join("\n"));
  expect(definition.initialState.d).toEqual({ long_text: "x".repeat(2048), big_value: Number.MAX_SAFE_INTEGER });
  expect(handle(request(definition.initialState, { type: "action", actionId: "more", value: null }), app).state.d.big_value).toBe(-Number.MAX_SAFE_INTEGER);
});

test("run: the dismiss of a dialog makes its test false", async () => {
  const { definition, app } = await compiled(readFileSync(new URL("./lists.plan", import.meta.url), "utf8"));
  const run = session(definition, app);
  // The confirm dialog shows while $/doses_drop is not "". Its dismiss sets it to "" again.
  run.act("doses_remove", { key: "r4", value: null });
  expect([run.state.doses_s.drop, run.state.v.doses_confirm_visible]).toEqual(["r4", true]);
  run.act("doses_confirm_dismiss");
  expect([run.state.doses_s.drop, run.state.v.doses_confirm_visible]).toEqual(["", false]);
  expect(run.state.doses).toHaveLength(2);
  // The sheet of acts shows while $/acts_adding is true.
  run.act("acts_open");
  expect(run.state.acts_s.adding).toBe(true);
  run.act("acts_addRow_dismiss");
  expect(run.state.acts_s.adding).toBe(false);
});

test("run: a multi choice over its max changes nothing", async () => {
  const { definition, app } = await compiled(readFileSync(new URL("./lists.plan", import.meta.url), "utf8"));
  const all = handle(request(definition.initialState, { type: "action", actionId: "gear_field", value: ["Map", "Tent", "Stove"] }), app);
  expect(all).toEqual({ state: definition.initialState });
  const two = handle(request(definition.initialState, { type: "action", actionId: "gear_field", value: ["Map", "Tent"] }), app);
  expect(two.state.gear).toEqual(["Map", "Tent"]);
});

test("run: a shown value that becomes a list is never its text", async () => {
  const { definition, app } = await compiled([
    'app("Kinds", lang="en")', "$/n = 0", "root = Column([field, word])", 'field = NumberField("N", $/n, min=0, max=10)', 'word = Text(if($/n > 0, ["a", "b"], "none"))',
  ].join("\n"));
  expect(definition.initialState.d.word_text).toBe("none");
  const { state } = handle(request(definition.initialState, { type: "action", actionId: "field", value: 1 }), app);
  expect(state.d.word_text).toBe("");
});

const bytes = (text) => Buffer.byteLength(text, "utf8");
/** Prints the bytes of the source of a plan and of its parts, and gives the bytes of the source. */
const measured = (plan, definition, app) => {
  console.log(`${plan} source: ${bytes(definition.source)} bytes (run-time ${bytes(runtime.text)}, compiled data ${bytes(JSON.stringify(app))}); definition ${bytes(JSON.stringify(definition))}; ui ${bytes(JSON.stringify(definition.ui))}; state ${bytes(JSON.stringify(definition.initialState))}`);
  return bytes(definition.source);
};
/** The source in the sandbox's own wrapper (SandboxClient.evaluationScript), in a context with only the language's globals. */
const sandboxed = (source, context) => (input) => JSON.parse(runInContext(`JSON.stringify((function(input){'use strict';\n${source}\n})(JSON.parse(${JSON.stringify(JSON.stringify(input))})))`, context));

test("source: the source of list.plan is under 60000 bytes and answers an add as handle does", async () => {
  const { definition, app } = await compiled(plan("list"));
  expect(measured("list.plan", definition, app)).toBeLessThan(60000);
  const run = sandboxed(definition.source, createContext());
  const typed = handle(request(definition.initialState, { type: "action", actionId: "prodotti_new_nome", value: "Burro" }), app).state;
  const add = request(typed, { type: "action", actionId: "prodotti_add", value: null });
  expect(run(add)).toEqual(handle(add, app));
  expect(run(add).state.prodotti).toHaveLength(4);
});

test("source: no source of a saved plan or fixture that compiles passes 60000 bytes", async () => {
  const sizes = [];
  for (const [path, text] of allPlans()) {
    const { definition, app } = await compiled(text);
    if (definition) sizes.push([path, measured(path, definition, app)]);
  }
  expect(sizes.filter(([, size]) => size >= 60000)).toEqual([]);
  expect(sizes.length).toBeGreaterThan(0);
});

/** The bytes that a field of the state may cost in the maps of the compiled data: its kit name and its Alphy name of at most 16 characters each, and its kind, in JSON. */
const PER_FIELD = 48;
test("source: the compiled data of rich.plan and cards.plan holds only what the run-time reads", async () => {
  for (const name of ["rich", "cards"]) {
    const { file, definition, app } = await compiled(testPlan(name));
    const size = bytes(JSON.stringify(app));
    measured(`test/${name}.plan`, definition, app);
    // The run-time evaluates the app line, the formulas, and the components. It reads no need line (it has no parts) and no start value (each value comes from the state).
    expect(app.lines.split("\n").map((line) => parseLine(line)?.kind).filter((kind) => !["app", "formula", "component"].includes(kind))).toEqual([]);
    const read = [...file.entries.values()].filter((entry) => ["app", "formula", "component"].includes(entry.kind)).map((entry) => entry.raw).join("\n");
    // Each field of the state: a top-level field, a field of an object, and a field of the rows of a list.
    const fields = Object.values(definition.stateSchema).reduce((sum, schema) => sum + 1 + Object.keys(schema.fields ?? {}).length, 0);
    console.log(`test/${name}.plan compiled data ${size} bytes, bound ${bytes(JSON.stringify(read)) + PER_FIELD * fields} (lines ${bytes(JSON.stringify(read))}, ${fields} fields)`);
    expect(size).toBeLessThan(bytes(JSON.stringify(read)) + PER_FIELD * fields);
  }
});

test("source: the source of calc.plan is under 64 KiB and evaluates as a function body", async () => {
  const { definition, app } = await compiled(plan("calc"));
  expect(measured("calc.plan", definition, app)).toBeLessThan(60000);
  const context = createContext();
  const names = () => runInContext("Object.getOwnPropertyNames(globalThis).join()", context), before = names();
  const run = sandboxed(definition.source, context);
  expect(run(request(definition.initialState, { type: "open" }))).toEqual({ state: definition.initialState });
  // The source is a function body: it gives the global object no name.
  expect(names()).toBe(before);
  const action = request(definition.initialState, { type: "action", actionId: "bill_field", value: 200 });
  const reply = run(action);
  expect(reply.state.bill).toBe(200);
  expect(reply).toEqual(handle(action, app));
});
