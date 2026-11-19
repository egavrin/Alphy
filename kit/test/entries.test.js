// The entries part in the look of the baseline apps (Task 11b-2a, C): the row, its emoji, many check marks, the add form, and the hero count.

import { expect, test } from "bun:test";
import { AppFile } from "../src/dsl.js";
import { report, smoke } from "../src/check.js";
import { compileAppFile } from "../src/compile.js";
import { emit } from "../src/emit.js";
import { handle } from "../src/run.js";
import { built, every, live, planned, press, valueAt } from "./helpers.js";


test("entries: the fields of the add form stand one below the other, and the add button is on its own line, enabled, with a plus", async () => {
  const { definition } = await built('app("Spesa", lang="it")', 'need items = entries("Prodotti", fields={name: "text", aisle: ["Frutta", "Forno"]})');
  // The first node is the hero card of the list (C.6), and the section of the list comes next.
  const [list, form] = definition.ui.children[0].children[1].children;
  expect(list.type).toBe("list");
  expect(form).toEqual({ type: "column", children: [
    { type: "input", text: "Name", bind: "items_s.new_name", action: "items_new_name", inputMode: "live" },
    { type: "segmented", text: "Aisle", bind: "items_s.new_aisle", action: "items_new_aisle", options: [{ text: "Frutta", value: "Frutta" }, { text: "Forno", value: "Forno" }] },
    // No variant: the host makes the first button of a group filled. No enabledBind: the button is enabled at the start.
    { type: "button", text: "Aggiungi", action: "items_add", icon: "Plus" },
  ] });
});

test("entries: a press of the add button with an empty first text field changes nothing, and a typed name adds a row", async () => {
  const { definition, app } = await built('app("Spesa", lang="it")', 'need items = entries("Prodotti", fields={name: "text"}, rows=[])');
  const empty = press(definition.initialState, app, "items_add");
  expect(empty.state.items).toEqual([]);
  const typed = live(definition.initialState, app, "items_new_name", "Latte");
  expect(press(typed.state, app, "items_add").state.items.map((row) => row.name)).toEqual(["Latte"]);
  // A name of spaces is empty too.
  expect(press(live(definition.initialState, app, "items_new_name", "  ").state, app, "items_add").state.items).toEqual([]);
});

test("entries: an empty press in each add sheet leaves the sheet open and adds no row, and the button that opens a sheet has a plus", async () => {
  // sheet=true: the part writes the sheet and its flag.
  const own = await built('app("Tasks", lang="en")', 'need tasks = entries("Tasks", fields={task: "text"}, sheet=true, rows=[])');
  const open = every(own.definition.ui).find((node) => node.action === "tasks_open");
  expect(open).toMatchObject({ type: "button", text: "Add", icon: "Plus" });
  const opened = press(own.definition.initialState, own.app, "tasks_open");
  const pressed = press(opened.state, own.app, "tasks_add");
  expect([pressed.state.tasks, pressed.state.tasks_s.adding]).toEqual([[], true]);
  const added = press(live(opened.state, own.app, "tasks_new_task", "Call").state, own.app, "tasks_add");
  expect([added.state.tasks.map((row) => row.task), added.state.tasks_s.adding]).toEqual([["Call"], false]);
  // More than two typed fields: the run-time puts the form into a sheet.
  const many = await built('app("Tasks", lang="en")', 'need tasks = entries("Tasks", fields={task: "text", note: "text", due: "date"}, rows=[])');
  const shown = press(many.definition.initialState, many.app, "tasks_addRow_open");
  const kept = press(shown.state, many.app, "tasks_add");
  expect([kept.state.tasks, kept.state.tasks_s.adding]).toEqual([[], true]);
  const done = press(live(shown.state, many.app, "tasks_new_task", "Call").state, many.app, "tasks_add");
  expect([done.state.tasks.length, done.state.tasks_s.adding]).toEqual([1, false]);
});

test("entries: a press in an add sheet that changes a value and adds no row leaves the sheet open", async () => {
  // An author's add button that counts each press: the empty press changes $/tries, and only a press that adds a row closes the sheet.
  const { definition, app } = await built('app("Tasks", lang="en")', "$/tries = 0", 'need tasks = entries("Tasks", fields={task: "text", note: "text", due: "date"}, rows=[])',
    'tasks_add = Button("Add", action=[inc($/tries), when(len(trim($/tasks_new_task)) > 0, append($/tasks, {task: $/tasks_new_task, note: $/tasks_new_note, due: $/tasks_new_due}))], icon="Plus")',
    'need tries = figure("count", "Tries", $/tries)');
  const shown = press(definition.initialState, app, "tasks_addRow_open");
  const kept = press(shown.state, app, "tasks_add");
  expect([kept.state.tasks, kept.state.tries, kept.state.tasks_s.adding]).toEqual([[], 1, true]);
  const done = press(live(kept.state, app, "tasks_new_task", "Call").state, app, "tasks_add");
  expect([done.state.tasks.length, done.state.tries, done.state.tasks_s.adding]).toEqual([1, 2, false]);
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

test("entries: the smoke run types only into the fields that the button reads, looks at the screen after the typing, and keeps the error of the typed press", () => {
  const app = (action) => smoke(new AppFile().feedText(['app("T")', '$/name = ""', '$/note = ""', '$/hidden = 0', '$/n = 0', "root = Column([Text($/n), name, note, go])",
    'name = TextField("Name", $/name)', 'note = TextField("Note", $/note)', `go = Button("Go", ${action})`].join("\n")));
  // The typed text shows in its field, so only a look after the typing finds a change that no part of the screen shows.
  expect(app("when(len($/name) > 0, inc($/hidden))").faults).toEqual(["smoke run: go changes a value, and no part of the screen shows it"]);
  // The note is not typed: the run goes on from a state with only the name that the press read.
  expect(app("when(len($/name) > 0, inc($/n))").trace.find((line) => line.startsWith("tap go"))).toBe('tap go: name "" -> "Test", n 0 -> 1');
  // A name that a formula reads is a name that the press reads.
  expect(smoke(new AppFile().feedText(['app("T")', '$/name = ""', '$/n = 0', "$/ready := len($/name) > 0", "root = Column([Text($/n), name, go])",
    'name = TextField("Name", $/name)', 'go = Button("Go", when($/ready, inc($/n)))'].join("\n"))).faults).toEqual([]);
  // The error of the typed press is a fault, not a dead button.
  expect(app("when(len($/name) > 0, flip($/n, 1))").faults).toEqual(["smoke run: go: flip needs a list"]);
});

/** The template of the first list of a definition. */
const rowOf = (definition) => every(definition.ui).find((node) => node.rowTemplate).rowTemplate;
const muted = { tone: "muted" }, cell = { emphasis: "label" };

// L1 item 2 (e-bbq-pt, showcase-lang-habits-uk): a computed value showed as "28.333333333333332" with no name, in a cell one digit wide.
test("entries (L1 item 2): a value of show= prints as a number of the language of the app, with at most 2 decimals, and with its name of labels=", async () => {
  const { definition, app } = emit(await planned('app("T", lang="pt")\nneed people = entries("Pessoas", fields={name: "text", paid: "number"}, labels={share: "Parte"}, '
    + 'show={share: sum($/people, $paid) / count($/people), owes: max(0, sum($/people, $paid) / count($/people) - $paid), sign: $paid > 50 ? "💰" : "-", kept: fixed($paid, 3)}, '
    + 'rows=[{name: "Ana", paid: 60}, {name: "Rui", paid: 25}, {name: "Eva", paid: 1000}])'));
  // The values that the run-time shows in each row. A text, and a value that the plan prints with its own format, stay as they are.
  const binds = every(rowOf(definition)).filter((node) => node.type === "badge").map((node) => node.bind);
  const { state } = handle({ artifactId: "t", revision: 1, state: definition.initialState, event: { type: "open" } }, app);
  expect(binds).toEqual(["people_share_value", "people_owes_value", "people_sign_value", "people_kept_value"]);
  expect(state.people_list.map((row) => binds.map((bind) => row[bind])))
    .toEqual([["Parte: 361,67", "301,67", "💰", "60,000"], ["Parte: 361,67", "336,67", "-", "25,000"], ["Parte: 361,67", "0", "💰", "1000,000"]]);
});

test("entries (L1 item 2): in a row with values of show=, the title and the cells are one group that wraps, so a cell that does not fit goes to a line under the title", async () => {
  const { definition } = await built('app("T", lang="pt")', 'need people = entries("Pessoas", fields={name: "text", paid: "number"}, show={share: $paid / 3}, icon="🍖")');
  const row = rowOf(definition);
  // The emoji and the remove button stay at the ends of the line. The group takes the rest of it, and each cell in it keeps the width of its content.
  expect(row.children.map((node) => [node.type, node.layout?.weight])).toEqual([["emoji", undefined], ["flowRow", 1], ["icon", undefined]]);
  expect(row.children[1].children.map((node) => [node.type, node.layout?.weight])).toEqual([["text", undefined], ["text", undefined], ["badge", undefined]]);
  // A row with no value of show= keeps its title, with the rest of the line, beside its cells.
  const plain = rowOf((await built('app("T", lang="pt")', 'need bikes = entries("Bikes", fields={name: "text", price: "number"})')).definition);
  expect(plain.children.map((node) => [node.type, node.layout?.weight])).toEqual([["text", 1], ["text", undefined], ["icon", undefined]]);
});

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
  // In a row with one text, a choice that the row does not change is the muted line under the title.
  // Task 11b-2a pictures: "Frutta e verdura" as a label broke into two lines beside the title.
  const one = await built('app("Spesa", lang="it")', 'need items = entries("Prodotti", fields={aisle: ["Frutta e verdura", "Forno"], name: "text"})');
  expect(rowOf(one.definition).children[0].children).toEqual([{ type: "text", text: "", bind: "name" }, { type: "text", text: "", bind: "aisle", style: muted }]);
  // A choice that the row changes stays a control.
  const edit = await built('app("Spesa", lang="it")', 'need items = entries("Prodotti", fields={name: "text", aisle: ["Frutta", "Forno"]}, edit=true)');
  expect(rowOf(edit.definition).children.map((node) => node.type)).toEqual(["input", "segmented", "icon"]);
});

test("entries: icon= gives each row a leading emoji named by the title of the list, from a sign or from a value of the row", async () => {
  const sign = await built('app("Spesa", lang="it")', 'need items = entries("Prodotti", fields={name: "text"}, icon="🛒")');
  expect(rowOf(sign.definition).children[0]).toEqual({ type: "emoji", text: "Prodotti", value: "🛒", size: "small" });
  // A value of the row: a field of the form, or a value that show= computes for each row.
  const shown = await built('app("Spesa", lang="it")',
    'need items = entries("Prodotti", fields={name: "text", aisle: ["Frutta", "Forno"]}, show={sign: $aisle == "Forno" ? "🥖" : "🍎"}, icon=$sign, rows=[{name: "Pane", aisle: "Forno"}])');
  // The aisle is the line under the title.
  const [emoji, main] = rowOf(shown.definition).children;
  expect([emoji.type, emoji.text, emoji.size, typeof emoji.bind, main.children.map((node) => node.bind)]).toEqual(["emoji", "Prodotti", "small", "string", ["name", "aisle"]]);
  expect(shown.definition.initialState.items_list[0][emoji.bind]).toBe("🥖");
  // The sign of a row that the add form makes: show= computes it from the new row.
  const typed = live(shown.definition.initialState, shown.app, "items_new_name", "Mela");
  expect(press(typed.state, shown.app, "items_add").state.items_list.map((row) => row[emoji.bind])).toEqual(["🥖", "🍎"]);
  // A field that the row shows as its emoji is not a text of the row too.
  const field = await built('app("T", lang="en")', 'need items = entries("Items", fields={sign: "text", name: "text"}, icon=$sign)');
  expect(rowOf(field.definition).children.map((node) => node.bind)).toEqual(["sign", "name", undefined]);
  // An icon word in place of an emoji is a note (A0-K4, looks.test.js), and a formula that reads no field stays a fault.
  const line = 'need items = entries("Items", fields={name: "text"}, icon=$nothing)';
  expect(report(await planned(`app("T")\n${line}`)).faults).toEqual([`line: need items: icon=$nothing reads no field of the rows. Use a field of fields= or a name of show=: ${line}`]);
});

test("entries: more than two check marks are circles of one weight in a second row under the cells, each named by its label or its field", async () => {
  const { definition } = await built('app("Звички", lang="uk")',
    'need habits = entries("Звички", fields={name: "text", mon: "check", tue: "check", wed: "check"}, labels={mon: "Пн", tue: "Вт"}, show={streak: count([$mon, $tue, $wed], $it)})');
  const [top, marks] = rowOf(definition).children;
  // The host draws each row of a list on a card of its own, so a card in the template is a card in a card (Task 11b-2a pictures).
  expect(rowOf(definition).type).toBe("column");
  // The title and the value of show= are one group that wraps (L1 item 2).
  expect([top.children.map((node) => node.type), top.children[0].children.map((node) => node.type)]).toEqual([["flowRow", "icon"], ["text", "badge"]]);
  expect(marks).toEqual({ type: "row", wrap: false, style: { gap: "none", crossAlign: "center" }, children: [["Пн", "mon"], ["Вт", "tue"], ["Wed", "wed"]].map(([text, bind]) => (
    { type: "checkbox", text, bind, action: `habits_${bind}`, variant: "circle", layout: { weight: 1 } })) });
});

// L1 item 1 (showcase-ui-alarm, showcase-ui-steps-chart): on the phone the text field of an editable row was one letter wide, and a
// count showed a loose minus beside the host's own stepper of its number field.
test("entries (L1 item 1): an editable row is one line that wraps: its text field is as wide as the host lets it be, and its other controls keep their own widths", async () => {
  const { definition } = await built('app("T", lang="en")',
    'need doses = entries("Doses", fields={name: "text", time: "time", on: "switch"}, labels={on: "On"}, edit=true, confirm="Delete?", icon="💊", rows=[{name: "Dose 1", time: "08:00", on: true}])');
  const row = rowOf(definition), [first, ...controls] = row.children;
  // The host gives a node of a row that wraps up to 300 dp: on a phone the text field has a line of its own, and the controls wrap under it.
  expect([row.type, row.style]).toEqual(["flowRow", { crossAlign: "center" }]);
  // The emoji, and the text field with the rest of their group.
  expect([first.type, first.wrap, first.children.map((node) => [node.type, node.bind, node.layout])]).toEqual(["row", false, [["emoji", undefined, undefined], ["input", "name", { weight: 1 }]]]);
  // The time, the switch, and the delete button have no weight, so each one keeps the width of its content.
  expect([controls.map((node) => node.type), controls.filter((node) => node.layout)]).toEqual([["timeInput", "toggle", "icon"], []]);
  // With no emoji, the text field is the first item of the row, with no weight.
  const plain = rowOf((await built('app("T", lang="en")', 'need tasks = entries("Tasks", fields={task: "text", level: ["high", "low"]}, edit=true)')).definition);
  expect([plain.type, plain.children.map((node) => node.type), plain.children[0]])
    .toEqual(["flowRow", ["input", "segmented", "icon"], { type: "input", text: "Task", bind: "task", action: "tasks_task", inputMode: "live" }]);
});

test("entries (L1 item 1): a count in a row is one group of a minus, its number field, and a plus, with no second stepper of the host", async () => {
  const steps = await built('app("T", lang="de")',
    'need tage = entries("Woche", fields={tag: "text", schritte: "count"}, labels={schritte: "Schritte"}, edit=true, add=false, remove=false, icon="👟", rows=[{tag: "Montag", schritte: 0}])');
  const [first, group] = rowOf(steps.definition).children;
  expect([rowOf(steps.definition).type, first.children.map((node) => node.type)]).toEqual(["flowRow", ["emoji", "input"]]);
  // The number field takes the width between its two buttons. It has no step, so the host draws no stepper of its own beside it.
  expect([group.type, group.wrap, group.children.map((node) => [node.type, node.name ?? node.bind, node.layout?.weight, node.step])])
    .toEqual(["row", false, [["icon", "Minus", undefined, undefined], ["numberInput", "schritte", 1, undefined], ["icon", "Plus", undefined, undefined]]]);
  // A row that the person does not edit: the title and the other cells on the first line, and the count group on the line under it.
  const menu = await built('app("T", lang="en")', 'need drinks = entries("Menu", fields={drink: "text", price: "money", qty: "count"}, add=false, remove=false, rows=[{drink: "Tea", price: 2.5, qty: 0}])');
  const [top, counted] = rowOf(menu.definition).children;
  expect([rowOf(menu.definition).type, top.children.map((node) => node.bind), counted.children.map((node) => node.type)]).toEqual(["column", ["drink", "price"], ["icon", "numberInput", "icon"]]);
});

test("entries (L1 item 1): in each model plan and saved plan, a text field of a row shares its line with no control, and no number field of a row has a stepper", async () => {
  const { allPlans } = await import("./helpers.js");
  const { plansIn } = await import("../build.js");
  const { readFileSync } = await import("node:fs");
  const { fileURLToPath } = await import("node:url");
  const models = plansIn(fileURLToPath(new URL("./fixtures/model", import.meta.url))).map((path) => [path, readFileSync(path, "utf8")]);
  const found = [];
  for (const [path, text] of [...allPlans(), ...models]) {
    const { definition } = await compileAppFile(text);
    for (const node of definition ? every(definition.ui) : []) {
      const template = node.rowTemplate;
      if (!template) continue;
      for (const inner of every(template)) {
        if (inner.type === "numberInput" && inner.step !== undefined) found.push(`${path}: ${inner.bind} has a stepper`);
        const crowd = (inner.children ?? []).filter((child) => !["emoji", "input"].includes(child.type));
        if ((inner.children ?? []).some((child) => child.type === "input") && inner.wrap === false && crowd.length) found.push(`${path}: ${inner.children.map((child) => child.type)}`);
      }
    }
  }
  expect(found).toEqual([]);
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

test("entries: icons= gives a field of the add form its icon, tones= gives a value of show= its tone, and total= shows the hero count of all rows", async () => {
  const aisles = '["Frutta", "Forno", "Latte", "Carne", "Pesce", "Casa", "Bevande"]';
  const { definition } = await built('app("Spesa", lang="it")', `need items = entries("Prodotti", fields={nome: "text", corsia: ${aisles}, peso: "number", preso: "check"}, icons={nome: "Cart", corsia: "Tag", peso: "Weight"}, show={giorni: "🔥 " + text($peso)}, tones={giorni: "accent"}, filter=!$preso, total="di", rows=[{nome: "Latte", corsia: "Latte", peso: 1, preso: false}, {nome: "Pane", corsia: "Forno", peso: 1, preso: true}])`);
  const nodes = every(definition.ui);
  expect(["items_new_nome", "items_new_corsia", "items_new_peso"].map((action) => [nodes.find((node) => node.action === action).type, nodes.find((node) => node.action === action).icon]))
    .toEqual([["input", "Cart"], ["select", "Tag"], ["numberInput", "Weight"]]);
  expect(nodes.find((node) => node.type === "badge")).toMatchObject({ bind: "items_giorni_value", style: { tone: "accent" } });
  const count = nodes.find((node) => node.type === "metric");
  expect([count.style, valueAt(definition.initialState, count.bind)]).toEqual([{ emphasis: "display" }, "1 di 2"]);
});

test("entries: icons= of a field with no icon in the add form (A0-K4), tones= of no value of show=, and total= with no hero count (K1 fix 3) are notes of the need", async () => {
  const lines = (options, more = []) => ['app("T")', ...more, `need items = entries("Items", fields={name: "text", kind: ["a", "b"], done: "check"}${options})`].join("\n");
  for (const key of ["done", "kind", "size"]) {
    const result = await compileAppFile(lines(`, icons={${key}: "Tag"}`));
    expect([key, result.faults, result.notes]).toEqual([key, [], [`need items: icons= names ${key}, whose field of the add form shows no icon, so the kit left it out. A text, number, date, or time field shows one, and a choice of more than six options does too`]]);
  }
  // The look case of each is in looks.test.js (K1 fix 3). A sign of the row shows no badge, so tones= of it is a note too.
  const sign = await compileAppFile(lines(', show={mark: "⭐"}, icon=$mark, tones={mark: "accent"}'));
  expect([sign.faults, sign.notes]).toEqual([[], ["need items: tones= names mark, and show= has no value of that name, so the kit left it out"]]);
});

test("entries: a list that a steps or tabs need groups gets no hero count at the root, and the first list that no holder groups gets it", async () => {
  const counted = (definition) => every(definition.ui).filter((node) => node.variant === "hero").map((node) => node.children.find((child) => child.type === "metric").text);
  for (const holder of ['need pages = tabs(["A", "B"], [[items], [n]])', 'need pages = steps(["A", "B"], [[items], [n]])']) {
    const grouped = await built('app("T", lang="en")', holder, 'need n = input("number", "N", 1)', 'need items = entries("Items", fields={name: "text"})');
    expect([holder, counted(grouped.definition)]).toEqual([holder, []]);
  }
  const both = await built('app("T", lang="en")', 'need pages = tabs(["A", "B"], [[items], [n]])', 'need n = input("number", "N", 1)',
    'need items = entries("Items", fields={name: "text"})', 'need more = entries("More", fields={name: "text"})');
  expect(counted(both.definition)).toEqual(["More"]);
  expect(both.definition.ui.children[0].children[0]).toMatchObject({ type: "card", variant: "hero" });
});
