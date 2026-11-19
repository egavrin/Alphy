// The next state of a changed app (loop plan, task 2): compileAppFile(text, previous) compiles the changed plan and computes the next
// state from the stored state, so that no value of the person is lost or moves to a different need.

import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { basename } from "node:path";
import { fileURLToPath } from "node:url";
import { BLOCKS, MISMATCH, compileAppFile } from "../src/compile.js";
import { handle } from "../src/run.js";
import { plansIn } from "../build.js";
import { T0, fixture, gateEach, plan, valueAt } from "./helpers.js";
import { settings } from "../src/core.js";

// After each test the real clock comes back, also after a failure.
gateEach("smoke");

const HEAD = "\nreturn handle(input, ";
/** The compiled data of a definition: the JSON at the end of its source, as the sandbox gives it to handle. */
const appOf = (definition) => JSON.parse(definition.source.slice(definition.source.lastIndexOf(HEAD) + HEAD.length, -2));
const event = (state, app, value, now = T0) => handle({ artifactId: "kit-test", revision: 1, state, event: value }, app, now).state;
const action = (actionId, value = null) => ({ type: "action", actionId, value });
/** A stored app: its app file, its state schema, and its state after the events, as the host keeps them. */
async function stored(text, events = [], now = T0) {
  const { definition, faults } = await compileAppFile(text);
  expect(faults).toEqual([]);
  const app = appOf(definition);
  let state = definition.initialState;
  for (const each of events) state = event(state, app, each, now);
  return { appFile: text, stateSchema: definition.stateSchema, state };
}
/** The fields of the person in the compiled data: each top-level field of a value of a line and each object of the inner values of a need. */
const ownFields = (app) => [...new Set(app.fields.filter(([root]) => root !== "app:empty").map(([, path]) => path.split(".")[0]))];
const lines = (...items) => items.join("\n") + "\n";

const SHOPPING = fixture("shopping");
// Milk (r1) is bought and Bread (r2) is gone: two stored rows.
const SHOPPING_EVENTS = [action("items_remove", { key: "r2", value: null }), action("items_done", { key: "r1", value: true })];
const ITEMS = [{ id: "r1", name: "Milk", aisle: "Dairy", done: true }, { id: "r3", name: "Apples", aisle: "Produce", done: false }];
const ITEMS_LINE = 'need items = entries("Items", fields={name: "text", aisle: "text", done: "check"}, sort=$aisle, labels={name: "Product", aisle: "Aisle"}, rows=[{name: "Milk", aisle: "Dairy", done: false}, {name: "Bread", aisle: "Bakery", done: false}, {name: "Apples", aisle: "Produce", done: false}])';
const LEFT_LINE = 'need left = figure("count", "Left to buy", count($/items, !$done))';
/** The shopping plan with another items line. Its check reads the start rows, so an example of the figure takes its place. */
const withItems = (line) => SHOPPING.replace(ITEMS_LINE, line).replace(LEFT_LINE, LEFT_LINE.slice(0, -1) + ", example=[{items: [{done: false}, {done: true}]}, 1])").replace(/^check\(.*\n/m, "");

test("change: the two fault texts are constants of the kit", () => {
  expect(BLOCKS).toBe("the saved data blocks this change");
  expect(MISMATCH).toBe("the saved data does not match the app file");
});

test("change: with no previous the result has no state, and with a previous the definition is the one of the same text", async () => {
  const alone = await compileAppFile(SHOPPING), previous = await stored(SHOPPING, SHOPPING_EVENTS), again = await compileAppFile(SHOPPING, previous);
  expect(alone.state).toBeNull();
  expect(again.faults).toEqual([]);
  expect(again.definition).toEqual(alone.definition);
  expect(again.state).toEqual(previous.state);
});

test("change: a list keeps its rows when a figure is added", async () => {
  const previous = await stored(SHOPPING, SHOPPING_EVENTS);
  expect(previous.state.items).toEqual(ITEMS);
  const result = await compileAppFile(SHOPPING + 'need bought = figure("count", "Bought", count($/items, $done), example=[{items: [{done: true}]}, 1])\n', previous);
  expect(result.faults).toEqual([]);
  expect(result.state.items).toEqual(ITEMS);
  expect(result.state.d).toMatchObject({ left_text: "1", bought_text: "1" });
  expect(Object.keys(result.state).sort()).toEqual(Object.keys(result.definition.stateSchema).sort());
});

test("change: a new column gets its start value in each stored row", async () => {
  const previous = await stored(SHOPPING, SHOPPING_EVENTS);
  const more = ITEMS_LINE.replace('done: "check"}', 'done: "check", urgent: "check", size: ["S", "L"], on: "switch", when: "time"}').replaceAll("done: false}", 'done: false, urgent: false, size: "S", on: true, when: "08:00"}');
  const result = await compileAppFile(withItems(more), previous);
  expect(result.faults).toEqual([]);
  // The add form gives a new row no check mark, its switch on, the first option of a choice, and the time 08:00 (480 minutes).
  expect(result.state.items).toEqual(ITEMS.map((row) => ({ ...row, urgent: false, size: "S", on: true, when: 480 })));
});

/** A change of the items line that the two stored rows block: the one fault of the result names the need and the column aisle. */
async function aisleBlocked(line) {
  const result = await compileAppFile(withItems(line), await stored(SHOPPING, SHOPPING_EVENTS));
  expect(result.faults).toHaveLength(1);
  expect(result.faults[0]).toStartWith(`need items: ${BLOCKS}: `);
  expect(result.faults[0]).toContain("aisle");
  expect([result.definition, result.state]).toEqual([null, null]);
}

test("change: a removed column of a list with rows is a fault that names the column", async () => {
  await aisleBlocked('need items = entries("Items", fields={name: "text", done: "check"})');
});

test("change: a removed column of an empty list compiles clean", async () => {
  const empty = await stored(SHOPPING, ["r1", "r2", "r3"].map((key) => action("items_remove", { key, value: null })));
  expect(empty.state.items).toEqual([]);
  const result = await compileAppFile(withItems('need items = entries("Items", fields={name: "text", done: "check"})'), empty);
  expect(result.faults).toEqual([]);
  expect(result.state.items).toEqual([]);
});

test("change: a retyped column of a list with rows is a fault that names the column", async () => {
  await aisleBlocked(ITEMS_LINE.replace('aisle: "text"', 'aisle: "number"'));
});

const SIZE = lines('app("Sizes", lang="en")', 'need size = input("number", "Size", 3, min=0, max=10)');

test("change: a retyped input is a fault, also when the need goes and comes back with the same name in one change", async () => {
  const previous = await stored(SIZE, [action("size_field", 5)]);
  expect(previous.state.size).toBe(5);
  const retyped = await compileAppFile(SIZE.replace('input("number", "Size", 3, min=0, max=10)', 'input("text", "Size", "3")'), previous);
  expect(retyped.faults).toEqual([`need size: ${BLOCKS}: it holds a saved number, and the change makes it a text. Keep its kind, or give the new need another name`]);
  expect(retyped.state).toBeNull();
  // A time is a number of minutes for Alphy, and a different value for the person.
  const time = await stored(lines('app("T", lang="en")', 'need at = input("time", "At", "08:00")'), [action("at_field", 600)]);
  const number = await compileAppFile(time.appFile.replace('input("time", "At", "08:00")', 'input("number", "At", 0)'), time);
  expect(number.faults).toEqual([`need at: ${BLOCKS}: it holds a saved time, and the change makes it a number. Keep its kind, or give the new need another name`]);
});

test("change: an input value and a counter value stay", async () => {
  const text = lines('app("Water", lang="en")', 'need goal = input("number", "Goal", 2000, min=0, max=5000)', 'need cups = counter("Cups", 0)', 'need share = figure("percent", "Share", $/cups * 250 / $/goal * 100, example=[{cups: 2, goal: 2000}, 25])');
  const previous = await stored(text, [action("goal_field", 2500), action("cups_plus"), action("cups_plus")]);
  expect([previous.state.goal, previous.state.cups]).toEqual([2500, 2]);
  const result = await compileAppFile(text + 'need note = card("Note", "One cup is 250 ml")\n', previous);
  expect(result.faults).toEqual([]);
  expect([result.state.goal, result.state.cups, result.state.d.share_text]).toEqual([2500, 2, "20%"]);
});

test("change: a state line that becomes a need keeps its value", async () => {
  const text = lines('app("Count", lang="en")', "$/n = 0", 'more = Button("More", action=inc($/n))', "shown = Text($/n)", "root = Column([shown, more])");
  const previous = await stored(text, [action("more"), action("more"), action("more")]);
  expect(previous.state.n).toBe(3);
  const result = await compileAppFile(lines('app("Count", lang="en")', 'need n = counter("Count", 0)'), previous);
  expect(result.faults).toEqual([]);
  expect(result.state.n).toBe(3);
});

/** The shopping app with no list: the need left stays, and the need items is gone. */
const WITHOUT = lines('app("Shopping list", lang="en", preset="clean", palette="blue")', 'need extra = counter("Extra", 0)', 'need left = figure("count", "Left to buy", $/extra, example=[{extra: 2}, 2])');
const TWICE = 'need twice = figure("count", "Twice", $/extra * 2, example=[{extra: 1}, 2])\n';

test("change: a removed need keeps its stored field, also after the next event of the new app", async () => {
  const previous = await stored(SHOPPING, SHOPPING_EVENTS);
  const result = await compileAppFile(WITHOUT, previous);
  expect(result.faults).toEqual([]);
  expect(result.state.items).toEqual(ITEMS);
  expect(result.definition.stateSchema.items).toEqual(previous.stateSchema.items);
  const app = appOf(result.definition);
  expect(app.keep).toContain("items");
  expect(result.definition.initialState.items).toEqual(ITEMS);
  const after = event(result.state, app, action("extra_plus"));
  expect([after.extra, after.items]).toEqual([1, ITEMS]);
  // An event that changes nothing gives the stored state back as it is, with the kept field.
  expect(event(result.state, app, action("nothing")).items).toEqual(ITEMS);
});

test("change: a kept field survives a second change, and a need that comes back gets its rows back", async () => {
  const previous = await stored(SHOPPING, SHOPPING_EVENTS);
  const first = await compileAppFile(WITHOUT, previous);
  const second = await compileAppFile(WITHOUT + TWICE, { appFile: WITHOUT, stateSchema: first.definition.stateSchema, state: first.state });
  expect(second.faults).toEqual([]);
  expect(second.state.items).toEqual(ITEMS);
  expect(appOf(second.definition).keep).toContain("items");
  // A restore of the first plan: the list need comes back, and so do its rows.
  const back = await compileAppFile(SHOPPING, { appFile: WITHOUT + TWICE, stateSchema: second.definition.stateSchema, state: second.state });
  expect(back.faults).toEqual([]);
  expect(back.state.items).toEqual(ITEMS);
  expect(back.state.d.left_text).toBe("1");
  expect(appOf(back.definition).keep).toEqual(expect.arrayContaining(["extra"]));
});

test("change: back to an older plan with one need less keeps the field of that need", async () => {
  const newer = SHOPPING + 'need extra = counter("Extra", 0)\n';
  const previous = await stored(newer, [action("extra_plus"), action("extra_plus"), action("extra_plus")]);
  const result = await compileAppFile(SHOPPING, previous);
  expect(result.faults).toEqual([]);
  expect(result.state.extra).toBe(3);
  expect(result.definition.stateSchema.extra).toBe("number");
  expect(appOf(result.definition).keep).toEqual(["extra"]);
});

test("change: back to an older plan with one column less in a list that has rows is a fault that names the column", async () => {
  await aisleBlocked('need items = entries("Items", fields={name: "text", done: "check"}, rows=[{name: "Milk", done: false}])');
});

test("change: names stay when an earlier need with a name that Alphy does not take is added, and values never swap in a chain of changes", async () => {
  // v and вода are names that Alphy does not take as a field: each one gets a name of its own that no other line changes.
  const first = lines('app("Names", lang="en")', 'need v = counter("V", 0)', 'need вода = counter("Вода", 0)');
  const previous = await stored(first, [action("v_plus")]);
  const names = Object.fromEntries(appOf((await compileAppFile(first)).definition).fields);
  expect(Object.values(names).every((name) => /^f[0-9a-z]{11}$/.test(name))).toBe(true);
  // The action id of the plus button of вода is a name of the screen, so the stored value of вода is set here.
  const start = { ...previous, state: { ...previous.state, [names["вода"]]: 7 } };
  expect([start.state[names.v], start.state[names["вода"]]]).toEqual([1, 7]);
  // A need before both: d is a name that Alphy does not take too.
  const second = lines('app("Names", lang="en")', 'need d = counter("D", 0)', 'need v = counter("V", 0)', 'need вода = counter("Вода", 0)');
  const changed = await compileAppFile(second, start);
  expect(changed.faults).toEqual([]);
  const now = Object.fromEntries(appOf(changed.definition).fields);
  expect([now.v, now["вода"]]).toEqual([names.v, names["вода"]]);
  expect([changed.state[names.v], changed.state[names["вода"]], changed.state[now.d]]).toEqual([1, 7, 0]);
  // The next change reads the stored names of the second plan from its app file alone, so v keeps 1 and d keeps 0.
  const third = await compileAppFile(second + 'need note = card("Note", "x")\n', { appFile: second, stateSchema: changed.definition.stateSchema, state: changed.state });
  expect(third.faults).toEqual([]);
  expect([third.state[names.v], third.state[names["вода"]], third.state[now.d]]).toEqual([1, 7, 0]);
});

test("change: inner values stay when the need line is the same, and start again when it changed", async () => {
  const text = plan("tabs-chart"), previous = await stored(text, [action("resumen_view", "Añadir")]);
  expect(previous.state.resumen_s).toEqual({ tab: "Añadir" });
  const same = await compileAppFile(text + 'need note = card("Nota", "Un gasto por línea")\n', previous);
  expect(same.faults).toEqual([]);
  expect(same.state.resumen_s).toEqual({ tab: "Añadir" });
  const changed = await compileAppFile(text.replace("[[grafico, total], [gastos]])", '[[grafico, total], [gastos]], title="Mes")'), previous);
  expect(changed.faults).toEqual([]);
  expect(changed.state.resumen_s).toEqual({ tab: "Resumen" });
});

test("change: a timer that runs keeps its end when its need line is the same, so the next timer event gives the time of the unchanged app", async () => {
  const text = plan("timer-rounds"), bar = { type: "timer", timerId: "timer_bar" };
  const previous = await stored(text, [action("timer_start")]);
  previous.state = event(previous.state, appOf((await compileAppFile(text)).definition), bar, T0 + 10000);
  expect(previous.state.timer_s).toMatchObject({ on: true, end: T0 + 40000, spent: 10 });
  const unchanged = event(previous.state, appOf((await compileAppFile(text)).definition), bar, T0 + 65000);
  const result = await compileAppFile(text + 'need note = card("Hinweis", "Trink Wasser")\n', previous);
  expect(result.faults).toEqual([]);
  expect(result.state.timer_s).toEqual(previous.state.timer_s);
  const later = event(result.state, appOf(result.definition), bar, T0 + 65000);
  // 40 s of work, then 20 s of rest, then 5 s of round 2: the same values as the app without the change.
  expect([later.timer, later.timer_s, later.d.timer_clock]).toEqual([unchanged.timer, unchanged.timer_s, unchanged.d.timer_clock]);
  expect([later.timer, later.timer_s.round, later.d.timer_clock]).toEqual([1, 2, "00:35"]);
  // A changed timer line starts its inner values again: the timer stands at the start of round 1. The count of work phases that ended stays.
  const restarted = await compileAppFile(text.replace('timer("Training", 40,', 'timer("Training", 45,'), { ...previous, state: later });
  expect(restarted.faults).toEqual([]);
  expect([restarted.state.timer, restarted.state.timer_s]).toEqual([1, { on: false, round: 1, resting: false, over: false, spent: 0, end: 0 }]);
});

test("change: a stored state that does not match the app file is the one fault of the kit", async () => {
  const previous = await stored(SHOPPING, SHOPPING_EVENTS);
  const wrong = { ...previous, stateSchema: { ...previous.stateSchema, items_s: { type: "object", fields: { new_name: "number", new_aisle: "string" } } } };
  expect((await compileAppFile(SHOPPING, wrong)).faults).toEqual([`kit: ${MISMATCH}`]);
  const missing = { ...previous, stateSchema: Object.fromEntries(Object.entries(previous.stateSchema).filter(([field]) => field !== "items")) };
  expect((await compileAppFile(SHOPPING, missing)).faults).toEqual([`kit: ${MISMATCH}`]);
  expect((await compileAppFile(SHOPPING, { ...previous, appFile: "need x = nothing(1)" })).faults).toEqual([`kit: ${MISMATCH}`]);
  expect((await compileAppFile(SHOPPING, { appFile: SHOPPING })).faults).toEqual([`kit: ${MISMATCH}`]);
});

test("change: a saved value outside the options of the changed need is a fault", async () => {
  const text = lines('app("Kind", lang="en")', 'need kind = input("choice", "Kind", "road", options=["road", "city", "trail"])');
  const previous = await stored(text, [action("kind_field", "trail")]);
  const result = await compileAppFile(text.replace('["road", "city", "trail"]', '["road", "city"]'), previous);
  expect(result.faults).toEqual([`need kind: ${BLOCKS}: the saved value "trail" is not one of the options or inside the bounds of the change, and the app would change it to "road". Keep the saved value possible`]);
});

test("change: a sort that goes and comes back again keeps the list, and the kept view of the list is empty", async () => {
  const previous = await stored(SHOPPING, SHOPPING_EVENTS);
  const unsorted = withItems(ITEMS_LINE.replace(", sort=$aisle", ""));
  const first = await compileAppFile(unsorted, previous);
  expect(first.faults).toEqual([]);
  expect([first.state.items, first.state.items_list]).toEqual([ITEMS, []]);
  const back = await compileAppFile(SHOPPING, { appFile: unsorted, stateSchema: first.definition.stateSchema, state: first.state });
  expect(back.faults).toEqual([]);
  expect(back.state.items_list.map((row) => row.id)).toEqual(["r1", "r3"]);
});

test("change: more than 40 values with the kept ones is a fault of the first need that adds a field", async () => {
  const counters = (prefix, count) => Array.from({ length: count }, (_, index) => `need ${prefix}${index + 1} = counter("${prefix} ${index + 1}", 0)`);
  const previous = await stored(lines('app("Many", lang="en")', ...counters("a", 20)));
  const result = await compileAppFile(lines('app("Many", lang="en")', ...counters("b", 21)), previous);
  expect(result.faults).toEqual([`need b1: ${BLOCKS}: the app would keep more than 40 values`]);
});

// ---- Each model plan: a stored state that differs from the start in each value of the person comes back. ----

const nodesOf = (node) => [node, ...(node.children ?? []).flatMap(nodesOf)];
/** A value of a bound control that differs from now, inside its options and bounds, and the type of its event. */
function sample(node, now) {
  const step = node.step ?? 1, other = (options) => options.find((option) => JSON.stringify(option.value) !== JSON.stringify(now))?.value;
  switch (node.type) {
    case "input": return ["liveInput", `${now} 2`];
    case "slider": return ["liveInput", now + step <= node.max ? now + step : now - step];
    case "numberInput": return ["action", now + step <= node.max ? now + step : now - step];
    case "segmented": case "select": case "tabs": return ["action", other(node.options)];
    case "multiChoice": return ["action", now.length ? [] : [node.options[0].value]];
    case "toggle": case "checkbox": return ["action", !now];
    case "dateInput": return ["action", now === "2026-03-04" ? "2026-03-05" : "2026-03-04"];
    case "timeInput": return ["action", now === 615 ? 616 : 615];
    case "rangeSlider": return ["action", [node.min + step, node.max - step]];
    default: return [null];
  }
}
/** Another value of the same type: a number one more, a text with more letters, a flag turned, and a row more in a list. */
function changed(value) {
  if (typeof value === "number") return value + 1;
  if (typeof value === "string") return value + " 2";
  if (typeof value === "boolean") return !value;
  if (Array.isArray(value)) return value.length && typeof value[0] === "object" ? [...value.map((row) => changed(row)), { ...changed(value[0]), id: "r900" }] : value;
  return Object.fromEntries(Object.entries(value).map(([key, inner]) => [key, key === "id" ? inner : changed(inner)]));
}
/** The object of the inner values of each timer need of a plan. Its round, rest, and end flags hold their start values in the first work phase. */
const timersOf = (text) => new Set([...text.matchAll(/^need (\w+) = timer\(/gm)].map(([, need]) => `${need}_s`));
const TIED = new Set(["round", "resting", "over"]);
/**
 * A stored state of the plan that differs from its start in each value of the person: events set each bound control and press each
 * button that does not remove or reset, then each value that is still the start value changes as a value of its type, and an open
 * event puts each value back inside its options and bounds, as the run-time does after each event. A timer runs from its start
 * button instead: its inner values follow the rules of its phases, which a change of one value would break.
 */
async function used(text) {
  const { definition } = await compileAppFile(text), app = appOf(definition), start = definition.initialState, timers = timersOf(text);
  let state = start, ui = definition.ui;
  for (const node of nodesOf(ui)) {
    if (!node.action || !["input", "slider", "numberInput", "segmented", "select", "tabs", "multiChoice", "toggle", "checkbox", "dateInput", "timeInput", "rangeSlider", "button"].includes(node.type)) continue;
    if (node.type === "button" && ["Trash", "Refresh", "Minus", "Pause"].includes(node.icon)) continue;
    const [type, value] = node.type === "button" ? ["action", null] : sample(node, valueAt(state, node.bind));
    if (type === null || value === undefined) continue;
    const reply = handle({ artifactId: "kit-test", revision: 1, state, event: { type, actionId: node.action, value } }, app, T0);
    state = reply.state;
    if (reply.ui) ui = reply.ui;
  }
  for (const field of ownFields(app).filter((field) => !timers.has(field))) {
    const value = state[field];
    if (value && typeof value === "object" && !Array.isArray(value)) state = { ...state, [field]: Object.fromEntries(Object.entries(value).map(([key, inner]) => [key, JSON.stringify(inner) === JSON.stringify(start[field][key]) ? changed(inner) : inner])) };
    else if (JSON.stringify(value) === JSON.stringify(start[field])) state = { ...state, [field]: changed(value) };
  }
  // The open event comes 5 s later, so a timer that runs has a count of seconds that is not its start value.
  state = event(state, app, { type: "open" }, LATER);
  return { previous: { appFile: text, stateSchema: definition.stateSchema, state }, app, start };
}
const LATER = T0 + 5000;

const models = fileURLToPath(new URL("./fixtures/model", import.meta.url));
for (const path of plansIn(models)) test(`change: ${basename(path)} gives each stored value back, also with a need more at the first or the last line`, async () => {
  const text = readFileSync(path, "utf8"), { previous, app, start } = await used(text);
  // The compile computes the shown values at the clock of the stored state, such as a count of days up to today.
  settings.now = () => LATER;
  const own = ownFields(app), values = (state) => Object.fromEntries(own.map((field) => [field, state[field]]));
  // Each value of the person differs from its start, so the test compares values that a change could lose. The timer test above
  // covers a timer in round 2 and in its rest.
  const timers = timersOf(text), same = own.flatMap((field) => (previous.state[field] && typeof previous.state[field] === "object" && !Array.isArray(previous.state[field])
    ? Object.keys(previous.state[field]).filter((key) => !(timers.has(field) && TIED.has(key)) && JSON.stringify(previous.state[field][key]) === JSON.stringify(start[field][key])).map((key) => `${field}.${key}`)
    : JSON.stringify(previous.state[field]) === JSON.stringify(start[field]) ? [field] : []));
  expect(same).toEqual([]);
  const again = await compileAppFile(text, previous);
  expect(again.faults).toEqual([]);
  expect(again.state).toEqual(previous.state);
  for (const more of [`need zzTally = counter("Tally", 0)\n${text}`, `${text.trimEnd()}\nneed zzNote = card("Note", "A need more")\n`]) {
    const result = await compileAppFile(more, previous);
    expect(result.faults).toEqual([]);
    expect(values(result.state)).toEqual(values(previous.state));
  }
});
