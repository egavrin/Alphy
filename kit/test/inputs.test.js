// The input part in the look of the baseline apps (Task 11b-2a, D): options as a map, an icon of the field, and a range of two numbers.
// A text field is a live input of the host (Task 11c): the host sends its text as a liveInput event while the person types.

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
