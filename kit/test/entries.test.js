// The entries part in the look of the baseline apps (Task 11b-2a, C): the row, its emoji, many check marks, the add form, and the hero count.

import { expect, test } from "bun:test";
import { AppFile } from "../src/dsl.js";
import { report, smoke } from "../src/check.js";
import { emit } from "../src/emit.js";
import { every, planned, press } from "./helpers.js";

/** The definition and the compiled app of a plan that the emitter builds with no fault. */
const built = async (...lines) => {
  const result = emit(await planned(lines.join("\n")));
  expect(result.faults).toEqual([]);
  return result;
};

test("entries: the fields of the add form stand one below the other, and the add button is on its own line, enabled, with a plus", async () => {
  const { definition } = await built('app("Spesa", lang="it")', 'need items = entries("Prodotti", fields={name: "text", aisle: ["Frutta", "Forno"]})');
  // The first node is the hero card of the list (C.6), and the section of the list comes next.
  const [list, form] = definition.ui.children[0].children[1].children;
  expect(list.type).toBe("list");
  expect(form).toEqual({ type: "column", children: [
    { type: "input", text: "Name", bind: "items_s.new_name", action: "items_new_name", inputMode: "apply" },
    { type: "segmented", text: "Aisle", bind: "items_s.new_aisle", action: "items_new_aisle", options: [{ text: "Frutta", value: "Frutta" }, { text: "Forno", value: "Forno" }] },
    // No variant: the host makes the first button of a group filled. No enabledBind: the button is enabled at the start.
    { type: "button", text: "Aggiungi", action: "items_add", icon: "Plus" },
  ] });
});

test("entries: a press of the add button with an empty first text field changes nothing, and a typed name adds a row", async () => {
  const { definition, app } = await built('app("Spesa", lang="it")', 'need items = entries("Prodotti", fields={name: "text"}, rows=[])');
  const empty = press(definition.initialState, app, "items_add");
  expect(empty.state.items).toEqual([]);
  const typed = press(definition.initialState, app, "items_new_name", "Latte");
  expect(press(typed.state, app, "items_add").state.items.map((row) => row.name)).toEqual(["Latte"]);
  // A name of spaces is empty too.
  expect(press(press(definition.initialState, app, "items_new_name", "  ").state, app, "items_add").state.items).toEqual([]);
});

test("entries: an empty press in each add sheet leaves the sheet open and adds no row, and the button that opens a sheet has a plus", async () => {
  // sheet=true: the part writes the sheet and its flag.
  const own = await built('app("Tasks", lang="en")', 'need tasks = entries("Tasks", fields={task: "text"}, sheet=true, rows=[])');
  const open = every(own.definition.ui).find((node) => node.action === "tasks_open");
  expect(open).toMatchObject({ type: "button", text: "Add", icon: "Plus" });
  const opened = press(own.definition.initialState, own.app, "tasks_open");
  const pressed = press(opened.state, own.app, "tasks_add");
  expect([pressed.state.tasks, pressed.state.tasks_s.adding]).toEqual([[], true]);
  const added = press(press(opened.state, own.app, "tasks_new_task", "Call").state, own.app, "tasks_add");
  expect([added.state.tasks.map((row) => row.task), added.state.tasks_s.adding]).toEqual([["Call"], false]);
  // More than two typed fields: the run-time puts the form into a sheet.
  const many = await built('app("Tasks", lang="en")', 'need tasks = entries("Tasks", fields={task: "text", note: "text", due: "date"}, rows=[])');
  const shown = press(many.definition.initialState, many.app, "tasks_addRow_open");
  const kept = press(shown.state, many.app, "tasks_add");
  expect([kept.state.tasks, kept.state.tasks_s.adding]).toEqual([[], true]);
  const done = press(press(shown.state, many.app, "tasks_new_task", "Call").state, many.app, "tasks_add");
  expect([done.state.tasks.length, done.state.tasks_s.adding]).toEqual([1, false]);
});

test("entries: the smoke run types into the form before it presses the add button, and a button that never changes a value is still a fault", async () => {
  const shop = await planned('app("Spesa", lang="it")\nneed items = entries("Prodotti", fields={name: "text", aisle: "text"}, rows=[])');
  const run = smoke(shop);
  expect(run.faults).toEqual([]);
  expect(run.trace.some((line) => line.startsWith("tap items_add: ") && line.includes("items [0] -> [1]"))).toBe(true);
  // A dead button beside the fields of a form: the typed values do not make it pass.
  const dead = new AppFile().feedText(['app("T")', '$/name = ""', '$/n = 0', "root = Column([field, dead])", 'field = TextField("Name", $/name)', 'dead = Button("Go", set($/n, 0))'].join("\n"));
  expect(smoke(dead).faults).toEqual(["smoke run: dead never changes a value: its action does nothing"]);
  expect(report(shop).faults).toEqual([]);
});

/** The template of the first list of a definition. */
const rowOf = (definition) => every(definition.ui).find((node) => node.rowTemplate).rowTemplate;
const muted = { tone: "muted" }, cell = { emphasis: "label" };

test("entries: the row is a title with a muted second line, then the value cells, the check marks, and the remove button", async () => {
  const { definition } = await built('app("Spesa", lang="it")',
    'need items = entries("Prodotti", fields={name: "text", qty: "text", price: "money", aisle: ["Frutta", "Forno"], done: "check"}, labels={done: "Preso"})');
  expect(rowOf(definition)).toEqual({ type: "row", wrap: false, style: { crossAlign: "center" }, children: [
    { type: "column", style: { gap: "none" }, layout: { weight: 1 }, children: [{ type: "text", text: "", bind: "name" }, { type: "text", text: "", bind: "qty", style: muted }] },
    // A value that is not text, and a choice that the row does not change, is a small label after the column.
    { type: "text", text: "", bind: "price", style: cell }, { type: "text", text: "", bind: "aisle", style: cell },
    { type: "checkbox", text: "Preso", bind: "done", action: "items_done" },
    { type: "icon", name: "Trash", text: "Rimuovi", action: "items_remove" },
  ] });
  // One text field is the title alone, with the weight of the row.
  expect(rowOf((await built('app("T", lang="en")', 'need items = entries("Items", fields={name: "text"})')).definition).children[0])
    .toEqual({ type: "text", text: "", bind: "name", layout: { weight: 1 } });
});

test("entries: icon= gives each row a leading emoji named by the title of the list, from a sign or from a value of the row", async () => {
  const sign = await built('app("Spesa", lang="it")', 'need items = entries("Prodotti", fields={name: "text"}, icon="🛒")');
  expect(rowOf(sign.definition).children[0]).toEqual({ type: "emoji", text: "Prodotti", value: "🛒", size: "small" });
  // A value of the row: a field of the form, or a value that show= computes for each row.
  const shown = await built('app("Spesa", lang="it")',
    'need items = entries("Prodotti", fields={name: "text", aisle: ["Frutta", "Forno"]}, show={sign: $aisle == "Forno" ? "🥖" : "🍎"}, icon=$sign, rows=[{name: "Pane", aisle: "Forno"}])');
  const [emoji, title] = rowOf(shown.definition).children;
  expect([emoji.type, emoji.text, emoji.size, typeof emoji.bind, title.bind]).toEqual(["emoji", "Prodotti", "small", "string", "name"]);
  expect(shown.definition.initialState.items_list[0][emoji.bind]).toBe("🥖");
  // The sign of a row that the add form makes: show= computes it from the new row.
  const typed = press(shown.definition.initialState, shown.app, "items_new_name", "Mela");
  expect(press(typed.state, shown.app, "items_add").state.items_list.map((row) => row[emoji.bind])).toEqual(["🥖", "🍎"]);
  // A field that the row shows as its emoji is not a text of the row too.
  const field = await built('app("T", lang="en")', 'need items = entries("Items", fields={sign: "text", name: "text"}, icon=$sign)');
  expect(rowOf(field.definition).children.map((node) => node.bind)).toEqual(["sign", "name", undefined]);
  for (const [option, fault] of [["icon=$nothing", "need items: icon=$nothing reads no field of the rows. Use a field of fields= or a name of show="],
    ['icon="Cart"', 'need items: icon= of a list is an emoji, such as icon="🛒", and "Cart" is not one']]) {
    const line = `need items = entries("Items", fields={name: "text"}, ${option})`;
    expect(report(await planned(`app("T")\n${line}`)).faults).toEqual([`line: ${fault}: ${line}`, "no root line"]);
  }
});

test("entries: more than two check marks are circles of one weight in a second row of a card, each named by its label or its field", async () => {
  const { definition } = await built('app("Звички", lang="uk")',
    'need habits = entries("Звички", fields={name: "text", mon: "check", tue: "check", wed: "check"}, labels={mon: "Пн", tue: "Вт"}, show={streak: count([$mon, $tue, $wed], $it)})');
  const [top, marks] = rowOf(definition).children;
  expect(rowOf(definition).type).toBe("card");
  expect(top.children.map((node) => node.type)).toEqual(["text", "badge", "icon"]);
  expect(marks).toEqual({ type: "row", wrap: false, style: { gap: "none", crossAlign: "center" }, children: [["Пн", "mon"], ["Вт", "tue"], ["Wed", "wed"]].map(([text, bind]) => (
    { type: "checkbox", text, bind, action: `habits_${bind}`, variant: "circle", layout: { weight: 1 } })) });
});

test("entries: a plan whose only main element is a list starts with a hero card of the count of its shown rows", async () => {
  const { definition } = await built('app("Spesa", lang="it")', 'need n = input("number", "N", 1)',
    'need items = entries("Prodotti", fields={name: "text", done: "check"}, icon="🛒", countText="Da comprare", filter=!$done, rows=[{name: "Latte", done: false}, {name: "Pane", done: true}])');
  const [hero, input] = definition.ui.children[0].children;
  expect(hero).toMatchObject({ type: "card", variant: "hero", children: [{ type: "emoji", text: "Da comprare", value: "🛒", size: "large" },
    { type: "metric", text: "Da comprare", style: { emphasis: "display" } }] });
  expect(input.type).toBe("numberInput");
  expect(definition.initialState.d[hero.children[1].bind.slice(2)]).toBe(1);
  // The caption is the title of the list when there is no countText=, and a sign of a row gives no emoji.
  const plain = await built('app("T", lang="en")', 'need items = entries("Items", fields={name: "text"}, icon=$name)');
  expect(plain.definition.ui.children[0].children[0].children.map((node) => [node.type, node.text])).toEqual([["metric", "Items"]]);
  // A figure, a progress, a drawing, a timer, or a model is the main element, and an author's root or a hidden list gets no card.
  for (const [lines, hidden] of [[['need f = figure("number", "F", 2)'], ""], [['need t = timer("T", 60)'], ""], [["root = Column([items_view])"], ""],
    [['need n = input("number", "N", 1)'], ", hidden=true"]]) {
    const other = emit(await planned(['app("T", lang="en")', ...lines, `need items = entries("Items", fields={name: "text"}${hidden})`].join("\n")));
    expect([lines, every(other.definition.ui).filter((node) => node.variant === "hero" && node.children?.some((child) => child.text === "Items")).length]).toEqual([lines, 0]);
  }
});
