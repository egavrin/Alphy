// The run-time: each event of a compiled app, through handle and through the source text in a bare context.

import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { createContext, runInContext } from "node:vm";
import { ACTIONS, App, settings } from "../src/core.js";
import { parseLine } from "../src/dsl.js";
import { ACCENTS, LIMITS, budgetOf, commandsOf, polygonsOf } from "../src/draw.js";
import { emit, runtime } from "../src/emit.js";
import { handle, kitOf } from "../src/run.js";
import { T0, allPlans, clocked, gateEach, plan, planned } from "./helpers.js";

gateEach("smoke");

const compiled = async (text) => { const file = await planned(text); return { file, ...emit(file) }; };
const testPlan = (name) => readFileSync(new URL(`./${name}.plan`, import.meta.url), "utf8");
const request = (state, event) => ({ artifactId: "kit-test", revision: 1, state, event });
const nodes = (node) => [node, ...(node.children ?? []).flatMap(nodes)];
/** The values of the objects d, d2, v, and so on: the values that the run-time computes. */
const derived = (state) => Object.entries(state).filter(([key]) => /^[dv]\d*$/.test(key)).flatMap(([, values]) => Object.values(values));
/** The stored values of a state of the run-time by the kit's name of each value (app.fields). */
const storedOf = (app, state) => Object.fromEntries(app.fields.map(([root, path]) => [root, path.split(".").reduce((value, key) => value?.[key], state)]));
/** The same values in the kit's App. */
const keptOf = (app, kit) => Object.fromEntries(app.fields.map(([root]) => [root, kit.state[root]]));

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
  expect(opened).toHaveLength(22);
  expect(opened).toEqual(expect.arrayContaining(["plans/tea.plan", "plans/timer-rounds.plan", "plans/lesson.plan", "plans/model3d.plan", "test/shapes.plan", "test/model.plan"]));
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
/** The events of one app in sequence, each on the state of the reply before it, at the time of settings.now. */
const session = (definition, app) => {
  let state = definition.initialState;
  const send = (event) => { const reply = handle(request(state, event), app, settings.now()); state = reply.state; return reply; };
  return {
    get state() { return state; },
    act: (actionId, value = null) => send({ type: "action", actionId, value }),
    timer: (timerId) => send({ type: "timer", timerId }),
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
  return { run, prototype, act, app };
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

/** A button for each action kind of the kit, a List whose row removes itself and moves up, and a drawing: Alphy sends the timer events of animate to an app with a surface. */
const KINDS = ['app("Kinds", lang="en")', "$/n = 5", "$/on = false", '$/word = "a"', '$/tags = ["home"]',
  '$/rows = [{name: "Walk", done: false}, {name: "Read", done: true}, {name: "Hike", done: false}]',
  "root = Column([setB, toggleB, incB, decB, appendB, removeB, clearB, whenB, moveB, flipB, animB, bothB, items, dot])",
  'animB = Button("Animate", action=animate($/n, 9, 1000))', 'dot = Drawing("N", 64, 64, [circle(32, 32, $/n, "accent")])',
  'setB = Button("Set", action=set($/word, "b"))', 'toggleB = Button("Toggle", action=toggle($/on))', 'incB = Button("Inc", action=inc($/n, 2))',
  'decB = Button("Dec", action=dec($/n))', 'appendB = Button("Append", action=append($/rows, {name: "Swim", done: false}))',
  'removeB = Button("Remove", action=remove($/rows, $done))', 'clearB = Button("Clear", action=clear($/tags))', 'whenB = Button("When", action=when($/on, inc($/n, 10)))',
  'moveB = Button("Move", action=move($/rows, $name == "Hike", -2))', 'flipB = Button("Flip", action=flip($/tags, "work"))',
  'bothB = Button("Both", action=[set($/word, "c"), toggle($/on)])',
  "items = List($/rows, item)", "item = Row([label, trash, up])", "label = Text($name)", 'trash = IconButton("Trash", action=remove())', 'up = IconButton("ArrowUp", action=move(-1))'].join("\n");

test("run: each action kind of the kit changes the state as the prototype's App does", async () => {
  /** Each case: its events, and the stored values that differ from the start after them. */
  const CASES = {
    set: [(t) => t.act("setB"), ["word"]],
    toggle: [(t) => t.act("toggleB"), ["on"]],
    inc: [(t) => t.act("incB"), ["n"]],
    dec: [(t) => t.act("decB"), ["n"]],
    append: [(t) => t.act("appendB"), ["rows"]],
    // The rows that pass a test, then the row of a row control.
    remove: [(t) => { t.act("removeB"); t.act("trash", null, "items", "r3"); }, ["rows"]],
    clear: [(t) => t.act("clearB"), ["tags"]],
    // A false test changes nothing, and a true test runs the action.
    when: [(t) => { t.act("whenB"); t.act("toggleB"); t.act("whenB"); }, ["n", "on"]],
    // The first row that passes the test, then the arrow of a row as Alphy's reorder of the list: r1 goes to the place of r2.
    move: [(t) => { t.act("moveB"); t.reorder("r1", "r2", "up", "r2"); }, ["rows"]],
    // The value joins the list, leaves it, and joins it again.
    flip: [(t) => { t.act("flipB"); t.act("flipB"); t.act("flipB"); }, ["tags"]],
    // The press starts the change from 5 to 9. The timer event at half of its time gives 7.
    animate: [(t) => { t.act("animB"); t.tick(500); }, ["n"]],
    list: [(t) => t.act("bothB"), ["on", "word"]],
  };
  expect(Object.keys(CASES).sort()).toEqual([...ACTIONS, "list"].sort());
  for (const [kind, [events, changed]] of Object.entries(CASES)) {
    settings.now = () => T0;
    const twin = await twins(KINDS), { run, prototype, app } = twin, start = storedOf(app, run.state);
    // The stored values of the run-time and of the kit after each event.
    const same = () => expect([kind, storedOf(app, run.state)]).toEqual([kind, keptOf(app, prototype)]);
    const t = {
      act: (...args) => { twin.act(...args); same(); },
      // Alphy's reorder moves the row of sourceKey to the place of destinationKey. The kit presses the arrow of a row.
      reorder: (sourceKey, destinationKey, arrow, row) => { run.act("items_move", { sourceKey, destinationKey }); prototype.press(arrow, shownIds(prototype, "items").indexOf(row)); same(); },
      // ms after T0: the timer event of the timed changes, and a look at the clock for the kit.
      tick: (ms) => { settings.now = () => T0 + ms; run.timer("anim"); prototype.tick(); same(); },
    };
    events(t);
    const end = storedOf(app, run.state);
    expect([kind, Object.keys(end).filter((root) => JSON.stringify(end[root]) !== JSON.stringify(start[root])).sort()]).toEqual([kind, changed]);
  }
});

const press = (actionId, value = null) => ({ type: "action", actionId, value });
const timer = (timerId) => ({ type: "timer", timerId });
/** The stored values of the run-time and of the kit of a clocked plan are equal. */
const sameStored = (twin) => expect(storedOf(twin.app, twin.state)).toEqual(keptOf(twin.app, twin.kit));
/** The Moon of lesson.plan: its circle of sand. */
const moon = (reply) => reply.surfaces.orbit_view.commands.find((command) => command.color === "#e7d3a8");

test("run: a change of the angle moves the Moon", async () => {
  const { kit, send } = await clocked(plan("lesson"));
  const turned = send(0, press("angle_field", 90));
  expect(moon(turned)).toEqual({ type: "circle", x: 160, y: 50, radius: 18, color: "#e7d3a8" });
  // The run-time draws the shapes of the kit's App.
  expect(turned.surfaces.orbit_view.commands).toEqual(commandsOf(kit.find("orbit_view").props.shapes));
  expect(turned.timers).toEqual([]);
});

test("run: the animate button starts a timer and the value is 180 after half of its time", async () => {
  const { kit, send } = await clocked(plan("lesson"));
  const started = send(0, press("lap_button"));
  expect(started.timers).toEqual([{ id: "anim", intervalMs: 50 }]);
  expect(started.state.anim).toEqual({ angle: { from: 0, to: 360, start: T0, end: T0 + 5000, at: 0 } });
  expect([started.state.angle, kit.state.angle, moon(started).x, moon(started).y]).toEqual([0, 0, 270, 160]);
  const half = send(2500, timer("anim"));
  expect([half.state.angle, kit.state.angle, moon(half).x, moon(half).y]).toEqual([180, 180, 50, 160]);
  expect(half.surfaces.orbit_view.commands).toEqual(commandsOf(kit.find("orbit_view").props.shapes));
  expect(half.timers).toEqual([{ id: "anim", intervalMs: 50 }]);
});

test("run: the last timer event returns timers: []", async () => {
  const { kit, send, app } = await clocked(plan("lesson"));
  send(0, press("lap_button"));
  send(2500, timer("anim"));
  const end = send(5000, timer("anim"));
  expect([end.state.angle, kit.state.angle, end.timers]).toEqual([360, 360, []]);
  expect(end.state.anim.angle).toEqual({ from: 0, to: 0, start: 0, end: 0, at: 0 });
  // A late timer event after the end, and a timer id that the app does not have, change nothing.
  for (const timerId of ["anim", "nothing", "toString"]) expect(handle(request(end.state, timer(timerId)), app, T0 + 9000)).toEqual({ state: end.state });
});

test("run: a late timer event or an open after a long gap ends a timed change at its value", async () => {
  // The host can send a timer event late, or close the app while the value moves. The next event ends the change, so no value stays in the middle.
  const late = await clocked(plan("lesson"));
  late.send(0, press("lap_button"));
  const ended = late.send(60000, timer("anim"));
  expect([ended.state.angle, late.kit.state.angle, ended.timers]).toEqual([360, 360, []]);
  const { definition, app } = await compiled(plan("lesson"));
  const started = handle(request(definition.initialState, press("lap_button")), app, T0);
  const opened = handle(request(started.state, { type: "open" }), app, T0 + 86400000);
  expect([opened.state.angle, opened.timers, opened.state.anim.angle.end]).toEqual([360, [], 0]);
  // A timer id that the app does not have changes nothing while the value moves.
  expect(handle(request(started.state, timer("orbit_view")), app, T0 + 1000)).toEqual({ state: started.state });
});

test("run: a value that the person sets ends its change, and two values move at once", async () => {
  const text = ['app("Two", lang="en")', "$/a = 0", "$/b = 0", "root = Column([field, pic, goA, goB, back])", 'field = NumberField("A", $/a, min=0, max=100)',
    'pic = Drawing("Two", 100, 100, [circle($/a, $/b, 5, "accent")])', 'goA = Button("A", action=animate($/a, 100, 1000))', 'goB = Button("B", action=animate($/b, 50, 2000))',
    'back = Button("Back", action=animate($/a, 0, 1000))'].join("\n");
  const hand = await clocked(text);
  hand.send(0, press("goA"));
  expect(hand.send(250, timer("anim")).state.a).toBe(25);
  // The field sets 60 between two timer events: the change ends, and the value stays.
  const set = hand.send(400, press("field", 60));
  expect([set.state.a, set.timers, hand.kit.state._run]).toEqual([60, [], undefined]);
  sameStored(hand);
  const both = await clocked(text);
  both.send(0, press("goA"));
  const two = both.send(500, press("goB"));
  expect([two.state.a, two.state.b, two.timers]).toEqual([50, 0, [{ id: "anim", intervalMs: 50 }]]);
  // A second animate of the same value replaces the first: a goes back from 75 to 0 in 1000 ms.
  const back = both.send(750, press("back"));
  expect([back.state.a, back.state.b]).toEqual([75, 6.25]);
  sameStored(both);
  const mid = both.send(1250, timer("anim"));
  expect([mid.state.a, mid.state.b]).toEqual([37.5, 18.75]);
  sameStored(both);
  expect(both.send(1750, timer("anim")).state.a).toBe(0);
  const end = both.send(2500, timer("anim"));
  expect([end.state.a, end.state.b, end.timers]).toEqual([0, 50, []]);
  sameStored(both);
});

test("run: a clock that goes back during a timed change keeps the value at its start", async () => {
  const { kit, send } = await clocked(plan("lesson"));
  send(1000, press("lap_button"));
  // The clock of the host goes back 1 s, to before the start of the change: the angle stays at its start value, and the change runs on.
  const back = send(0, timer("anim"));
  expect([back.state.angle, kit.state.angle, back.timers]).toEqual([0, 0, [{ id: "anim", intervalMs: 50 }]]);
  expect(back.state.anim.angle).toEqual({ from: 0, to: 360, start: T0 + 1000, end: T0 + 6000, at: 0 });
  const half = send(3500, timer("anim"));
  expect([half.state.angle, kit.state.angle]).toEqual([180, 180]);
});

test("run: a preview and a render change no state while a timed change or a timer with rounds runs", async () => {
  const lesson = await clocked(plan("lesson")), rounds = await clocked(plan("timer-rounds"));
  const moving = lesson.send(0, press("lap_button")), running = rounds.send(0, press("timer_start"));
  // Half of the change, and 5 s after the end of the first work phase: a timer event would move both.
  for (const type of ["preview", "render"]) {
    expect(handle(request(moving.state, { type }), lesson.app, T0 + 2500)).toEqual({ state: moving.state });
    expect(handle(request(running.state, { type }), rounds.app, T0 + 45000)).toEqual({ state: running.state });
  }
});

test("run: a timed change past the bounds of its field stops at the bound and ends at the next timer event, and the kit's App keeps moving", async () => {
  // The shortcut of run.js at the clamp of a bound number: this test pins what happens.
  const { kit, send } = await clocked(['app("Past", lang="en")', 'need angle = input("number", "Angle", 40, min=0, max=360)', 'need lap = button("Twice", animate($/angle, 720, 1000))',
    'need dial = drawing("Dial", 64, 64, [circle(32, 32, 4, "ink")])'].join("\n"));
  send(0, press("lap_button"));
  const past = send(500, timer("anim"));
  expect([past.state.angle, kit.state.angle, past.state.anim.angle.at, past.timers]).toEqual([360, 380, 380, [{ id: "anim", intervalMs: 50 }]]);
  const ended = send(600, timer("anim"));
  expect([ended.state.angle, kit.state.angle, ended.state.anim.angle, ended.timers]).toEqual([360, 448, { from: 0, to: 0, start: 0, end: 0, at: 0 }, []]);
});

test("run: the timer of a timer with rounds and the timer of animate are one list with distinct ids", async () => {
  // shapes.plan is kit/test/shapes.plan: each shape kind, each outline form, each over a list, each kind of color, a text, and animate beside a timer with rounds.
  const { kit, send } = await clocked(testPlan("shapes"));
  send(0, press("laps_start"));
  const both = send(100, press("grow_button"));
  expect(both.timers).toEqual([{ id: "laps_bar", intervalMs: 1000 }, { id: "anim", intervalMs: 50 }]);
  // A timer event of one moves both. The size goes from 40 to 100 in 2000 ms, and the work phase of 2 s ends at 2000 ms.
  const tick = send(1100, timer("anim"));
  expect([tick.state.size, kit.state.size, tick.state.laps_s.spent]).toEqual([70, 70, 1.1]);
  const rest = send(2100, timer("laps_bar"));
  expect([rest.state.size, rest.state.laps_s.resting, rest.timers]).toEqual([100, true, [{ id: "laps_bar", intervalMs: 1000 }]]);
  expect(rest.surfaces.art_view.commands).toEqual(commandsOf(kit.find("art_view").props.shapes));
  expect(rest.surfaces.art_view.commands.map((command) => [command.type, command.color])).toEqual([["rect", "#ffffff"], ["roundRect", "#a16207"], ["polyline", "default"],
    ["circle", "#336699"], ["arc", "#80ff0000"], ["line", "muted"], ["polygon", "success"], ["polyline", "warning"], ["text", "accent"], ["rect", "#facc15"], ["rect", "#facc15"], ["circle", "default"]]);
});

test("run: a shape that fails at run time is left out, and the other shapes draw", async () => {
  // 100 / 0 is not a finite number, so the first circle has no x: the kit's App faults it, and the reply draws the second circle.
  const { definition, app } = await compiled(['app("Dots", lang="en")', 'need n = input("number", "N", 1, min=0, max=5)',
    'need dots = drawing("Dots", 100, 100, [circle(100 / $/n, 1, 1, "ink"), circle(5, 5, 5, "accent")])'].join("\n"));
  expect(definition.surfaces).toEqual([{ id: "dots_view", width: 100, height: 100 }]);
  const reply = handle(request(definition.initialState, press("n_field", 0)), app);
  expect(reply.surfaces.dots_view.commands).toEqual([{ type: "circle", x: 5, y: 5, radius: 5, color: "accent" }]);
});

test("run: a reply never goes over the command limit", async () => {
  // 600 rows drawn with each: the reply keeps the first 512 commands, so Alphy takes it.
  const { definition, app } = await compiled(['app("Dots", lang="en")', 'need csv = input("text", "Values", "1")', '$/rows := split($/csv, ",")',
    'need dots = drawing("Dots", 320, 64, [each($/rows, circle($index % 300, 1, 1, "ink"))])'].join("\n"));
  const reply = handle(request(definition.initialState, press("csv_field", Array(600).fill("1").join(","))), app);
  expect(reply.surfaces.dots_view.commands).toHaveLength(LIMITS.commands);
  expect(reply.surfaces.dots_view.commands.at(-1)).toEqual({ type: "circle", x: 211, y: 1, radius: 1, color: "default" });
});

test("run: a reply of exactly 32768 bytes keeps each command, and one byte more cuts the last one", async () => {
  // bytes.plan is kit/test/bytes.plan. The device test aReplyAtTheByteLimitPassesTheHostPath sends the same values, and Alphy counts the same bytes.
  const { definition, app } = await compiled(testPlan("bytes"));
  const lines = handle(request(definition.initialState, press("n_field", 350)), app).state;
  const drawn = (pad) => handle(request(lines, press("pad_field", pad)), app).surfaces;
  const exact = drawn("x".repeat(773)), cut = drawn("x".repeat(774));
  expect([exact.lines_view.commands.length, budgetOf([["lines_view", exact.lines_view.commands]]).bytes]).toEqual([351, LIMITS.bytes]);
  // The text of 774 characters would pass the limit by 1 byte, so the reply keeps the 350 lines before it.
  expect(cut.lines_view.commands).toEqual(exact.lines_view.commands.slice(0, 350));
  expect(budgetOf([["lines_view", cut.lines_view.commands]]).bytes).toBe(31889);
});

/** The stage of a model: a light box under its faces, so the model reads on a light and on a dark theme. */
const STAGE = { type: "rect", x: 0, y: 0, width: 320, height: 240, color: "#e8f1ef" };
/** The surface of the rover of model3d.plan as the kit's App gives it: the stage, and the faces of the App's bodies at the App's turn. The App takes the bodies apart itself. */
const rover = (kit) => { const { bodies, value, selected } = kit.find("rover_model").props; return [STAGE, ...polygonsOf(bodies, value, false, selected, 320, 240, ACCENTS.teal)]; };
const pointer = (phase, x, surfaceId = "rover_model", id = 0) => ({ type: "pointer", surfaceId, x, y: 120, phase, pointer: id });
const IDLE = { surface: "", pointer: 0, x: 0 };

test("run: a pointer drag of 50 pixels turns the model by 30 degrees", async () => {
  const { file, definition, app } = await compiled(plan("model3d"));
  const send = (state, event) => handle(request(state, event), app);
  const down = send(definition.initialState, pointer("down", 100));
  expect([down.state.rover, down.state.drag, down.ui, down.surfaces.rover_model.commands]).toEqual([{ selected: "", exploded: false, turn: 30 }, { surface: "rover_model", pointer: 0, x: 100 }, undefined, rover(new App(file))]);
  // 0.6 degrees for each pixel: 50 pixels to the right turn the model from 30 to 60 degrees. The kit's App draws the same model at 60.
  const moved = send(down.state, pointer("move", 150));
  const kit = new App(file);
  kit.input("rover_model", 60);
  expect([moved.state.rover.turn, moved.state.drag, moved.ui, moved.surfaces.rover_model.commands]).toEqual([60, { surface: "rover_model", pointer: 0, x: 150 }, undefined, rover(kit)]);
  const up = send(moved.state, pointer("up", 150));
  expect([up.state.rover.turn, up.state.drag]).toEqual([60, IDLE]);
  // The up turns the model by its own way since the last move too, and a cancel ends the drag where it is.
  const late = send(moved.state, pointer("up", 160));
  expect([late.state.rover.turn, late.state.drag]).toEqual([66, IDLE]);
  const cancel = send(moved.state, pointer("cancel", 200));
  expect([cancel.state.rover.turn, cancel.state.drag]).toEqual([60, IDLE]);
});

test("run: a drag keeps the turn from 0 to 360, and a pointer that is not a drag of a model changes nothing", async () => {
  const { definition, app } = await compiled(plan("model3d"));
  const send = (state, event) => handle(request(state, event), app);
  const drag = (state, from, to) => send(send(send(state, pointer("down", from)).state, pointer("move", to)).state, pointer("up", to)).state;
  // From 30 degrees, 100 pixels to the left give -30, which is 330. Then 100 pixels to the right give 390, which is 30. A part of a pixel stays.
  const left = drag(definition.initialState, 200, 100);
  expect(left.rover.turn).toBe(330);
  expect(drag(left, 0, 100).rover.turn).toBe(30);
  expect(drag(definition.initialState, 100, 100.5).rover.turn).toBe(30.3);
  const down = send(definition.initialState, pointer("down", 100)).state;
  // An up or a move with no down, a place that is not a number, a second pointer, and a surface that the app does not have.
  for (const [state, event] of [[definition.initialState, pointer("up", 150)], [definition.initialState, pointer("move", 150)], [down, pointer("move", Number.NaN)],
    [definition.initialState, pointer("down", "100")], [down, pointer("move", 150, "rover_model", 1)], [down, pointer("up", 150, "rover_model", 1)], [down, pointer("move", 150, "nothing")]]) {
    expect(send(state, event)).toEqual({ state });
  }
});

test("run: the part buttons and the arrows give the values of the kit's App, and the arrows keep its turn past 0 and 360", async () => {
  const twin = await clocked(plan("model3d"));
  const camera = twin.send(0, press("rover_part_camera"));
  sameStored(twin);
  expect([camera.state.rover.selected, camera.surfaces.rover_model.commands]).toEqual(["camera", rover(twin.kit)]);
  // The chip of the selected part is filled, and the faces of the other parts are dimmed: the commands change with the selection.
  expect(nodes(camera.ui).filter((node) => node.type === "button" && node.action.startsWith("rover_part_")).map((node) => [node.text, node.variant]))
    .toEqual([["Камера", "filled"], ["Панель", "tonal"], ["Колёса", "tonal"]]);
  expect(camera.surfaces.rover_model.commands.filter((command) => command.color.length === 9).length).toBeGreaterThan(0);
  for (const [id, turn] of [["rover_left", 0], ["rover_left", -30], ["rover_right", 0], ["rover_right", 30], ["rover_right", 60]]) {
    const reply = twin.send(0, press(id));
    sameStored(twin);
    expect([id, reply.state.rover.turn, reply.surfaces.rover_model.commands]).toEqual([id, turn, rover(twin.kit)]);
  }
  // A second tap on the chip clears the selection.
  expect(twin.send(0, press("rover_part_camera")).state.rover.selected).toBe("");
  sameStored(twin);
});

test("run: the explode button changes its text through a new ui", async () => {
  const twin = await clocked(plan("model3d")), together = twin.kit.find("rover_model").props.bodies;
  const button = (ui) => nodes(ui).find((node) => node.action === "rover_explode");
  const apart = twin.send(0, press("rover_explode"));
  sameStored(twin);
  expect([button(twin.definition.ui).text, button(apart.ui).text, apart.state.rover.exploded]).toEqual(["Разобрать", "Собрать", true]);
  // The App's tree has the bodies taken apart, and the reply takes them apart once: the same faces as the bodies together, taken apart by polygonsOf.
  expect(apart.surfaces.rover_model.commands).toEqual(rover(twin.kit));
  expect(apart.surfaces.rover_model.commands).toEqual([STAGE, ...polygonsOf(together, 30, true, "", 320, 240, ACCENTS.teal)]);
  const back = twin.send(0, press("rover_explode"));
  expect([button(back.ui).text, back.surfaces.rover_model.commands]).toEqual(["Разобрать", rover(twin.kit)]);
});

test("run: a model beside a drawing keeps each reply inside Alphy's limits", async () => {
  // model.plan is kit/test/model.plan: a toy model first, then a drawing of $/n lines. 500 lines pass the byte limit, so the reply cuts the lines.
  const { file, definition, app } = await compiled(testPlan("model"));
  const reply = handle(request(definition.initialState, press("n_field", 500)), app), kit = new App(file);
  kit.input("n_field", 500);
  const used = budgetOf(Object.entries(reply.surfaces).map(([id, surface]) => [id, surface.commands]));
  console.log(`model.plan with 500 lines: ${JSON.stringify(used)}, toy_model ${reply.surfaces.toy_model.commands.length} commands, lines_view ${reply.surfaces.lines_view.commands.length} commands`);
  expect(Object.keys(LIMITS).filter((unit) => used[unit] > LIMITS[unit])).toEqual([]);
  const { bodies, value, selected } = kit.find("toy_model").props;
  expect(reply.surfaces.toy_model.commands).toEqual([STAGE, ...polygonsOf(bodies, value, false, selected, 320, 240, ACCENTS.blue)]);
  expect(reply.surfaces.lines_view.commands.length > 0 && reply.surfaces.lines_view.commands.length < 500).toBe(true);
  // A pointer of the drawing changes nothing, and a drag of the model keeps the reply inside the limits too.
  expect(handle(request(reply.state, pointer("down", 10, "lines_view")), app)).toEqual({ state: reply.state });
  const down = handle(request(reply.state, pointer("down", 10, "toy_model")), app);
  const moved = handle(request(down.state, pointer("move", 300, "toy_model")), app);
  const after = budgetOf(Object.entries(moved.surfaces).map(([id, surface]) => [id, surface.commands]));
  expect([moved.state.toy.turn, Object.keys(LIMITS).filter((unit) => after[unit] > LIMITS[unit])]).toEqual([204, []]);
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

test("source: the run-time text is at most 46500 bytes", () => {
  // kit/build.js leaves out each text that only the compile step reads (KIT_RUNTIME), so each source has room to grow.
  console.log(`run-time ${bytes(runtime.text)} bytes`);
  expect(bytes(runtime.text)).toBeLessThanOrEqual(46500);
});

test("source: a refused add leaves the add sheet open in the run-time text, as handle does", async () => {
  // The add button is not active while the task is empty, and only an add that the kit takes closes the sheet.
  const { definition, app } = await compiled(['app("Tasks", lang="en")', 'need tasks = entries("Tasks", fields={task: "text", note: "text", due: "date"}, rows=[])'].join("\n"));
  const run = sandboxed(definition.source, createContext());
  const open = run(request(definition.initialState, { type: "action", actionId: "tasks_addRow_open", value: null })).state;
  const add = request(open, { type: "action", actionId: "tasks_add", value: null });
  expect(run(add)).toEqual(handle(add, app));
  expect([run(add).state.tasks_s.adding, run(add).state.tasks]).toEqual([true, []]);
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
    // The run-time evaluates the app line, the formulas, and the components. These plans have no timer, so it reads no need line
    // (it has no parts; only the need line of a timer that it moves comes with it), and no start value (each value comes from the state).
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
