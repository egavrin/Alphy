// The run-time: each event of a compiled app, through handle and through the source text in a bare context.

import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { createContext, runInContext } from "node:vm";
import { App } from "../src/core.js";
import { emit, runtime } from "../src/emit.js";
import { handle } from "../src/run.js";
import { plan, planned } from "./helpers.js";

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
  for (const text of [plan("calc"), plan("list"), testPlan("lists")]) {
    const { definition, app } = await compiled(text);
    expect(handle(request(definition.initialState, { type: "open" }), app)).toEqual({ state: definition.initialState });
  }
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
