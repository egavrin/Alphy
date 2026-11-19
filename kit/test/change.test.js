// The next state of a changed app (loop plan, task 2): compileAppFile(text, previous) compiles the changed plan and computes the next
// state from the stored state, so that no value of the person is lost or moves to a different need.

import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { basename } from "node:path";
import { fileURLToPath } from "node:url";
import { BLOCKS, MISMATCH, isRecord } from "../src/change.js";
import { compileAppFile } from "../src/compile.js";
import { handle } from "../src/run.js";
import { plansIn } from "../build.js";
import { T0, fixture, gateEach, plan, valueAt } from "./helpers.js";
import { settings } from "../src/core.js";
import { AppFile, show } from "../src/dsl.js";
import { dataOf } from "../src/emit.js";

// After each test the real clock comes back, also after a failure.
gateEach("smoke");

const event = (state, app, value, now = T0) => handle({ artifactId: "kit-test", revision: 1, state, event: value }, app, now).state;
const action = (actionId, value = null) => ({ type: "action", actionId, value });
/** A stored app: its app file, its state schema, and its state after the events, as the host keeps them. */
async function stored(text, events = [], now = T0) {
  const { definition, faults } = await compileAppFile(text);
  expect(faults).toEqual([]);
  const app = dataOf(definition);
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

/** The two flags of a result: a saved value of the person blocks the change, and the stored state does not match its app file. */
const flagsOf = (result) => [result.blockedBySavedData, result.savedDataMismatch];

test("change: a plan line that quotes the words of BLOCKS and has a line fault sets no flag", async () => {
  const quoted = SHOPPING + `need note = card("Note", "${BLOCKS}", bogus=)\n`, result = await compileAppFile(quoted, await stored(SHOPPING, SHOPPING_EVENTS));
  // The line fault holds the text of its line, so a search of the fault texts would find the words.
  expect(result.faults.some((fault) => fault.startsWith("line ") && fault.includes(BLOCKS))).toBe(true);
  expect(flagsOf(result)).toEqual([false, false]);
});

test("change: with no previous the result has no state, and with a previous the definition is the one of the same text", async () => {
  const alone = await compileAppFile(SHOPPING), previous = await stored(SHOPPING, SHOPPING_EVENTS), again = await compileAppFile(SHOPPING, previous);
  expect([flagsOf(alone), flagsOf(again)]).toEqual([[false, false], [false, false]]);
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
  expect(flagsOf(result)).toEqual([true, false]);
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
  expect(flagsOf(retyped)).toEqual([true, false]);
  // A time is a number of minutes for Alphy, and a different value for the person.
  const time = await stored(lines('app("T", lang="en")', 'need at = input("time", "At", "08:00")'), [action("at_field", 600)]);
  const number = await compileAppFile(time.appFile.replace('input("time", "At", "08:00")', 'input("number", "At", 0)'), time);
  expect(number.faults).toEqual([`need at: ${BLOCKS}: it holds a saved time, and the change makes it a number. Keep its kind, or give the new need another name`]);
  expect(flagsOf(number)).toEqual([true, false]);
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
  const app = dataOf(result.definition);
  expect(app.keep).toContain("items");
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
  expect(dataOf(second.definition).keep).toContain("items");
  // A restore of the first plan: the list need comes back, and so do its rows.
  const back = await compileAppFile(SHOPPING, { appFile: WITHOUT + TWICE, stateSchema: second.definition.stateSchema, state: second.state });
  expect(back.faults).toEqual([]);
  expect(back.state.items).toEqual(ITEMS);
  expect(back.state.d.left_text).toBe("1");
  expect(dataOf(back.definition).keep).toEqual(expect.arrayContaining(["extra"]));
});

/**
 * The host's check of a value against its schema (ToolValueSchema.validate): a number is finite and at most 2^53 - 1 either way, a text has at most
 * 2048 characters, a record has the fields of its schema, and an array or a list has at most maxItems items, each row with an id that is not blank and that no other row has.
 */
const fits = (schema, value) => {
  if (typeof schema === "string") return { number: Number.isFinite(value) && Math.abs(value) <= Number.MAX_SAFE_INTEGER, string: typeof value === "string" && value.length <= 2048, boolean: typeof value === "boolean" }[schema] === true;
  if (schema.type === "object") return isRecord(value) && Object.keys(value).sort().join() === Object.keys(schema.fields).sort().join() && Object.entries(schema.fields).every(([key, inner]) => fits(inner, value[key]));
  const ids = new Set(), list = schema.type === "list", child = list ? { type: "object", fields: schema.fields } : schema.items;
  return Array.isArray(value) && value.length <= schema.maxItems && value.every((item) => fits(child, item) && (!list || (item.id.trim() !== "" && item.id.length <= 128 && !ids.has(item.id) && ids.add(item.id))));
};
/** The start state and the state of a result as the host checks them against the state schema of its definition. */
const bothFit = ({ definition, state }) => [fits({ type: "object", fields: definition.stateSchema }, definition.initialState), fits({ type: "object", fields: definition.stateSchema }, state)];
const BLANK_FORM = { new_name: "", new_aisle: "" }, TYPED_FORM = { ...BLANK_FORM, new_name: "Eggs" };
/** The shopping app with Eggs typed in its add form, changed to the app with no list. */
const withoutTyped = async () => compileAppFile(WITHOUT, await stored(SHOPPING, [...SHOPPING_EVENTS, { type: "liveInput", actionId: "items_new_name", value: "Eggs" }]));

test("change (kept start): a removed list with two stored rows gives an empty list in the start state and the two rows in the state", async () => {
  const result = await compileAppFile(WITHOUT, await stored(SHOPPING, SHOPPING_EVENTS));
  expect(result.faults).toEqual([]);
  expect([result.definition.initialState.items, result.state.items]).toEqual([[], ITEMS]);
  // A kept field whose stored value is blank, such as the empty view of the sorted list, makes no difference: it is no data of the person.
  expect([result.definition.initialState.items_list, result.state.items_list]).toEqual([[], []]);
  expect(bothFit(result)).toEqual([true, true]);
});

test("change (kept start): a removed number input with the stored value 7 gives 0 in the start state and 7 in the state", async () => {
  const result = await compileAppFile(lines('app("Sizes", lang="en")', 'need cups = counter("Cups", 0)'), await stored(SIZE, [action("size_field", 7)]));
  expect(result.faults).toEqual([]);
  expect([result.definition.initialState.size, result.state.size]).toEqual([0, 7]);
  expect(bothFit(result)).toEqual([true, true]);
});

test("change (kept start): the inner values of a removed need give the blank record in the start state and stay in the state", async () => {
  const result = await withoutTyped();
  expect(result.faults).toEqual([]);
  expect([result.definition.initialState.items_s, result.state.items_s]).toEqual([BLANK_FORM, TYPED_FORM]);
  expect(bothFit(result)).toEqual([true, true]);
});

test("change (kept start): a second change keeps the kept values in the state and the blanks in the start state", async () => {
  const first = await withoutTyped();
  const second = await compileAppFile(WITHOUT + TWICE, { appFile: WITHOUT, stateSchema: first.definition.stateSchema, state: first.state });
  expect(second.faults).toEqual([]);
  expect([second.definition.initialState.items, second.state.items]).toEqual([[], ITEMS]);
  expect([second.definition.initialState.items_s, second.state.items_s]).toEqual([BLANK_FORM, TYPED_FORM]);
  expect(bothFit(second)).toEqual([true, true]);
});

test("change: back to an older plan with one need less keeps the field of that need", async () => {
  const newer = SHOPPING + 'need extra = counter("Extra", 0)\n';
  const previous = await stored(newer, [action("extra_plus"), action("extra_plus"), action("extra_plus")]);
  const result = await compileAppFile(SHOPPING, previous);
  expect(result.faults).toEqual([]);
  expect(result.state.extra).toBe(3);
  expect(result.definition.stateSchema.extra).toBe("number");
  expect(dataOf(result.definition).keep).toEqual(["extra"]);
});

test("change: back to an older plan with one column less in a list that has rows is a fault that names the column", async () => {
  await aisleBlocked('need items = entries("Items", fields={name: "text", done: "check"}, rows=[{name: "Milk", done: false}])');
});

test("change: names stay when an earlier need with a name that Alphy does not take is added, and values never swap in a chain of changes", async () => {
  // v and вода are names that Alphy does not take as a field: each one gets a name of its own that no other line changes.
  const first = lines('app("Names", lang="en")', 'need v = counter("V", 0)', 'need вода = counter("Вода", 0)');
  const previous = await stored(first, [action("v_plus")]);
  const names = Object.fromEntries(dataOf((await compileAppFile(first)).definition).fields);
  expect(Object.values(names).every((name) => /^f[0-9a-z]{11}$/.test(name))).toBe(true);
  // The action id of the plus button of вода is a name of the screen, so the stored value of вода is set here.
  const start = { ...previous, state: { ...previous.state, [names["вода"]]: 7 } };
  expect([start.state[names.v], start.state[names["вода"]]]).toEqual([1, 7]);
  // A need before both: d is a name that Alphy does not take too.
  const second = lines('app("Names", lang="en")', 'need d = counter("D", 0)', 'need v = counter("V", 0)', 'need вода = counter("Вода", 0)');
  const changed = await compileAppFile(second, start);
  expect(changed.faults).toEqual([]);
  const now = Object.fromEntries(dataOf(changed.definition).fields);
  expect([now.v, now["вода"]]).toEqual([names.v, names["вода"]]);
  expect([changed.state[names.v], changed.state[names["вода"]], changed.state[now.d]]).toEqual([1, 7, 0]);
  // The next change reads the stored names of the second plan from its app file alone, so v keeps 1 and d keeps 0.
  const third = await compileAppFile(second + 'need note = card("Note", "x")\n', { appFile: second, stateSchema: changed.definition.stateSchema, state: changed.state });
  expect(third.faults).toEqual([]);
  expect([third.state[names.v], third.state[names["вода"]], third.state[now.d]]).toEqual([1, 7, 0]);
});

test("change: a need that comes back gets only its own kept value, also when another name differs only in letters that Alphy does not take", async () => {
  const first = lines('app("Oil", lang="tr")', 'need other = counter("Other", 0)', 'need yaş = counter("Yaş", 0)');
  const yas = Object.fromEntries(dataOf((await compileAppFile(first)).definition).fields)["yaş"], start = await stored(first);
  start.state = { ...start.state, [yas]: 3 };
  const without = lines('app("Oil", lang="tr")', 'need other = counter("Other", 0)');
  const kept = await compileAppFile(without, start);
  expect(kept.state[yas]).toBe(3);
  const keptApp = { appFile: without, stateSchema: kept.definition.stateSchema, state: kept.state };
  // yağ and ya_ are other needs: each one starts at 0, and the value of yaş stays kept.
  for (const name of ["yağ", "ya_"]) {
    const result = await compileAppFile(without + `need ${name} = counter("${name}", 0)\n`, keptApp);
    expect(result.faults).toEqual([]);
    expect([result.state[Object.fromEntries(dataOf(result.definition).fields)[name]], result.state[yas]]).toEqual([0, 3]);
  }
  expect((await compileAppFile(first, keptApp)).state[yas]).toBe(3);
});

test("change: an app that stops on the saved data is a fault, as each event of that app would change nothing", async () => {
  // At the start one row makes the formula cheap. 100 stored rows make it pass the budget of the kit.
  const text = lines('app("T", lang="en")', 'need items = entries("Items", fields={name: "text"}, rows=[{name: "a"}])', "$/x := len(map($/items, map($/items, map($/items, map($/items, $name)))))", "root = Column([items_view, Text(text($/x))])");
  const previous = await stored(text);
  previous.state = { ...previous.state, items: Array.from({ length: 100 }, (_, index) => ({ id: `r${index + 1}`, name: `n${index}` })) };
  const result = await compileAppFile(text, previous);
  expect(result.faults).toHaveLength(1);
  expect(result.faults[0]).toStartWith(`app: ${BLOCKS}: the app stops on the saved data: `);
  expect(flagsOf(result)).toEqual([true, false]);
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
  previous.state = event(previous.state, dataOf((await compileAppFile(text)).definition), bar, T0 + 10000);
  expect(previous.state.timer_s).toMatchObject({ on: true, end: T0 + 40000, spent: 10 });
  const unchanged = event(previous.state, dataOf((await compileAppFile(text)).definition), bar, T0 + 65000);
  const result = await compileAppFile(text + 'need note = card("Hinweis", "Trink Wasser")\n', previous);
  expect(result.faults).toEqual([]);
  expect(result.state.timer_s).toEqual(previous.state.timer_s);
  const later = event(result.state, dataOf(result.definition), bar, T0 + 65000);
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
  const missing = { ...previous, stateSchema: Object.fromEntries(Object.entries(previous.stateSchema).filter(([field]) => field !== "items")) };
  for (const each of [wrong, missing, { ...previous, appFile: "need x = nothing(1)" }, { appFile: SHOPPING }]) {
    const result = await compileAppFile(SHOPPING, each);
    expect(result.faults).toEqual([`kit: ${MISMATCH}`]);
    expect(flagsOf(result)).toEqual([false, true]);
  }
});

test("change: a saved value outside the options of the changed need is a fault", async () => {
  const text = lines('app("Kind", lang="en")', 'need kind = input("choice", "Kind", "road", options=["road", "city", "trail"])');
  const previous = await stored(text, [action("kind_field", "trail")]);
  const result = await compileAppFile(text.replace('["road", "city", "trail"]', '["road", "city"]'), previous);
  expect(result.faults).toEqual([`need kind: ${BLOCKS}: the saved value "trail" is not one of the options or inside the bounds of the change, and the app would change it to "road". Keep the saved value possible`]);
  expect(flagsOf(result)).toEqual([true, false]);
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
  expect(flagsOf(result)).toEqual([true, false]);
});

// ---- Rule 3b: a value that still equals its start value of the previous plan takes the start value of the change. ----

const KANJI = fixture("model/showcase-lang-kanji-ja");
const MORE_KANJI = ["日", "月", "火", "水", "木"].map((kanji) => `{kanji: "${kanji}", reading: "よみ", meaning: "いみ"}`).join(", ");
const TIP = lines('app("Tip", lang="en")', 'need tip = input("percent", "Tip", 10, min=0, max=30)');
const startOf = async (text) => (await compileAppFile(text)).definition.initialState;
/** The tip after a change of its start value from 10 to 15, for the tip app stored after the events. */
async function tipAfter(events) {
  const result = await compileAppFile(TIP.replace("10, min", "15, min"), await stored(TIP, events));
  expect(result.faults).toEqual([]);
  return result.state.tip;
}

test("change (rule 3b): a list that the plan fills and the person does not edit takes the start rows of the change", async () => {
  const ten = KANJI.replace('meaning: "広く塩水をたたえた場所"}]', `meaning: "広く塩水をたたえた場所"}, ${MORE_KANJI}]`);
  const result = await compileAppFile(ten, await stored(KANJI));
  expect(result.faults).toEqual([]);
  expect(result.state.words).toHaveLength(10);
  expect(result.state.words).toEqual((await startOf(ten)).words);
  expect(result.notes).toEqual([]);
});

test("change (rule 3b): a start value that the person did not change takes the start value of the change", async () => {
  expect(await tipAfter([])).toBe(15);
});

test("change (rule 3b): a start value that the person changed stays", async () => {
  // The tip is a slider, so the host sends a live input.
  expect(await tipAfter([{ type: "liveInput", actionId: "tip_field", value: 12 }])).toBe(12);
});

test("change (rule 3b): a list with one changed check mark keeps its rows when the change has five more start rows, and a note says so", async () => {
  const previous = await stored(SHOPPING, [action("items_done", { key: "r1", value: true })]);
  const more = ITEMS_LINE.replace('done: false}])', `done: false}, ${["Eggs", "Rice", "Tea", "Salt", "Oil"].map((name) => `{name: "${name}", aisle: "Pantry", done: false}`).join(", ")}])`);
  const result = await compileAppFile(withItems(more), previous);
  expect(result.faults).toEqual([]);
  expect(result.state.items).toEqual(previous.state.items);
  expect(result.notes).toContain("need items: the list keeps its 3 saved rows, and the start rows of the change are not used");
});

test("change (rule 3b): a deck that marks its start rows keeps the rows after one mark by the person", async () => {
  const text = fixture("model/e-capitals-it"), previous = await stored(text, [action("mazzo_show"), action("mazzo_yesButton")]);
  expect(previous.state.carte.filter((row) => row.saputa)).toHaveLength(1);
  const result = await compileAppFile(text.replace('{paese: "Italia", capitale: "Roma", saputa: false}', '{paese: "Italia", capitale: "Roma", saputa: false}, {paese: "Malta", capitale: "La Valletta", saputa: false}'), previous);
  expect(result.faults).toEqual([]);
  expect(result.state.carte).toEqual(previous.state.carte);
  expect(result.notes).toContain(`need carte: the list keeps its ${previous.state.carte.length} saved rows, and the start rows of the change are not used`);
});

test("change (rule 3b): a list that still holds its start rows takes the start rows of the change, also when the change removes a column", async () => {
  const older = withItems('need items = entries("Items", fields={name: "text", done: "check"}, rows=[{name: "Milk", done: false}])');
  const result = await compileAppFile(older, await stored(SHOPPING));
  expect(result.faults).toEqual([]);
  expect(result.state.items).toEqual([{ id: "r1", name: "Milk", done: false }]);
});

test("change (rule 3b): an untouched value takes the start value of a change that retypes it, and a changed value blocks", async () => {
  const text = SIZE.replace('input("number", "Size", 3, min=0, max=10)', 'input("text", "Size", "3")');
  const untouched = await compileAppFile(text, await stored(SIZE));
  expect(untouched.faults).toEqual([]);
  expect(untouched.state.size).toBe("3");
  const changed = await compileAppFile(text, await stored(SIZE, [action("size_field", 5)]));
  expect(changed.faults).toEqual([`need size: ${BLOCKS}: it holds a saved number, and the change makes it a text. Keep its kind, or give the new need another name`]);
  expect([flagsOf(untouched), flagsOf(changed)]).toEqual([[false, false], [true, false]]);
});

// ---- Rule 3c: when rule 3b gives a list other start rows, the inner values that point into its old rows start again. ----

/** A plan with a list of words that the plan fills, and the lines after it. */
const wordsPlan = (count, ...after) => lines('app("Words", lang="en")',
  `need words = entries("Words", fields={word: "text", meaning: "text", known: "check"}, rows=[${Array.from({ length: count }, (_, index) => `{word: "w${index + 1}", meaning: "m${index + 1}", known: false}`).join(", ")}])`, ...after);
const PAGES = ['need pages = deck("Pages", $/words, front=$word)', "$/due := filter($/words, !$known)", 'need later = deck("Later", $/due, front=$word)'];
const times = (count, ...events) => Array.from({ length: count }, () => events).flat();
/** The two decks at the places 7 and 3 after the events, and the change of their list from ten to five start rows. */
async function pagesAfter(events) {
  const previous = await stored(wordsPlan(10, ...PAGES), [...times(7, action("pages_next")), ...times(3, action("later_next")), ...events]);
  expect([previous.state.pages_s.i, previous.state.later_s.i]).toEqual([7, 3]);
  const result = await compileAppFile(wordsPlan(5, ...PAGES), previous);
  expect(result.faults).toEqual([]);
  return { previous, result, places: [result.state.pages_s, result.state.later_s] };
}

test("change (rule 3c): a pages deck at place 7 over a list that takes new start rows starts at its first card, as does a deck over a formula of the list", async () => {
  const { result, places } = await pagesAfter([]);
  expect(result.state.words).toEqual((await startOf(wordsPlan(5, ...PAGES))).words);
  expect(places).toEqual([{ i: 0 }, { i: 0 }]);
});

test("change (rule 3c): a deck with counts over a list that takes new start rows starts with its counts at their start", async () => {
  const flash = (count) => wordsPlan(count, 'need flash = deck("Flash", $/words, front=$word, back=$meaning)');
  const previous = await stored(flash(10), times(3, action("flash_show"), action("flash_noButton")));
  expect(previous.state.flash_s).toMatchObject({ i: 3, no: 3 });
  const result = await compileAppFile(flash(5), previous);
  expect(result.faults).toEqual([]);
  expect(result.state.flash_s).toEqual((await startOf(flash(5))).flash_s);
  expect(result.state.flash_s).toMatchObject({ i: 0, yes: 0, no: 0 });
});

test("change (rule 3c): a pages deck over a list with a saved mark keeps its place", async () => {
  const { previous, result, places } = await pagesAfter([action("words_known", { key: "r1", value: true })]);
  expect(previous.state.words[0].known).toBe(true);
  expect(result.state.words).toEqual(previous.state.words);
  expect(places).toEqual([{ i: 7 }, { i: 3 }]);
});

test("change (rule 3c): an open delete dialog of a list that takes new start rows is closed, also when only the ids of its rows move", async () => {
  // The start rows of both lists take their ids from one count, so a new row in the first list moves the ids of the second one. Its need line stays the same text.
  const two = (...first) => lines('app("Two", lang="en")', `need first = entries("First", fields={name: "text"}, rows=[${first.map((name) => `{name: "${name}"}`).join(", ")}])`,
    'need second = entries("Second", fields={name: "text"}, confirm="Delete?", rows=[{name: "x"}, {name: "y"}])');
  const previous = await stored(two("a", "b", "c"), [action("second_remove", { key: "r5", value: null })]);
  expect(previous.state.second).toEqual([{ id: "r4", name: "x" }, { id: "r5", name: "y" }]);
  expect(previous.state.second_s.drop).toBe("r5");
  const result = await compileAppFile(two("a", "new", "b", "c"), previous);
  expect(result.faults).toEqual([]);
  // Without rule 3c the dialog stays open on r5, which is now the row x, so a yes removes x.
  expect(result.state.second).toEqual([{ id: "r5", name: "x" }, { id: "r6", name: "y" }]);
  expect(result.state.second_s.drop).toBe("");
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
  const { definition } = await compileAppFile(text), app = dataOf(definition), start = definition.initialState, timers = timersOf(text);
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

/** A value of each kind of a field of entries: a word, 1, a check mark, a switch that is on, a date, a time, or the first option of a choice. */
const SAMPLES = { number: 1, money: 1, count: 1, check: true, switch: true, date: "2026-03-04", time: "09:15" };
const sampleOf = (kind) => (kind.k === "list" ? kind.items[0].v : SAMPLES[kind.v] ?? "x");
/**
 * The plan with one start row more in its first list of entries (a copy of its first start row at the end, or a row of a sample value of each
 * field), else with another start value of its first input or counter: a step more inside the bounds of a number, another option, a flag
 * turned, another date or time, or more letters of a text. null for a plan with neither.
 */
function mirrorOf(text) {
  const needs = new AppFile().feedText(text).of("need"), line = (entry, tree) => text.replace(entry.raw, `need ${entry.key} = ${show(tree)}`);
  const listed = needs.find((entry) => entry.tree.name === "entries");
  if (listed) {
    const { rows = { k: "list", items: [] }, fields } = listed.tree.named;
    const row = rows.items[0] ?? { k: "map", entries: fields.entries.map(([key, kind]) => [key, { k: "lit", v: sampleOf(kind) }]) };
    return line(listed, { ...listed.tree, named: { ...listed.tree.named, rows: { ...rows, items: [...rows.items, row] } } });
  }
  for (const entry of needs) {
    const tree = entry.tree, at = { input: 2, counter: 1 }[tree.name], start = tree.args?.[at], value = start?.v;
    if (at === undefined) continue;
    // The options of a choice are a list, or a map from each value to its text.
    const given = tree.named.options, options = given?.items?.map((item) => item.v) ?? given?.entries?.map(([key]) => key);
    const step = tree.named.step?.v ?? 1, max = tree.named.max?.v ?? Infinity, kind = tree.args[0]?.v;
    const other = start.k === "list" ? { k: "list", items: start.items.length ? [] : [{ k: "lit", v: options[0] }] }
      : { k: "lit", v: options ? options.find((option) => option !== value) : typeof value === "number" ? (value + step <= max ? value + step : value - step)
        : typeof value === "boolean" ? !value : kind === "date" ? (value === SAMPLES.date ? "2026-03-05" : SAMPLES.date) : kind === "time" ? (value === SAMPLES.time ? "09:16" : SAMPLES.time) : `${value} 2` };
    return line(entry, { ...tree, args: tree.args.map((arg, index) => (index === at ? other : arg)) });
  }
  return null;
}

for (const path of plansIn(models)) test(`change (rule 3b): ${basename(path)} with its start state takes the start values of a change with another start value or one more start row`, async () => {
  const text = readFileSync(path, "utf8"), more = mirrorOf(text);
  // A plan with no list of start rows, no input, and no counter has no start value that a change of its content moves.
  if (more === null) { expect(text).not.toMatch(/rows=\[|= (input|counter)\(/); return; }
  const { definition } = await compileAppFile(text), changedStart = await compileAppFile(more);
  expect(changedStart.faults).toEqual([]);
  const own = ownFields(dataOf(changedStart.definition)), values = (state) => Object.fromEntries(own.map((field) => [field, state[field]]));
  // The change moves at least one start value of the person.
  expect(values(changedStart.definition.initialState)).not.toEqual(values(definition.initialState));
  const result = await compileAppFile(more, { appFile: text, stateSchema: definition.stateSchema, state: definition.initialState });
  expect(result.faults).toEqual([]);
  expect(values(result.state)).toEqual(values(changedStart.definition.initialState));
});
