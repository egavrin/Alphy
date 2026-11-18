// The emitter: an expanded app file as Alphy's app definition.

import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { settings } from "../src/core.js";
import { emit } from "../src/emit.js";
import { handle } from "../src/run.js";
import { plan, planned } from "./helpers.js";

/** Alphy's rule for a state field and an action id (ToolValueSchema.name, ToolUiCatalog). */
const SAFE = /^[A-Za-z][A-Za-z0-9_]{0,47}$/;
const REFUSED = new Set(["__proto__", "prototype", "constructor"]);
const nodes = (node) => [node, ...(node.children ?? []).flatMap(nodes)];
const testPlan = (name) => readFileSync(new URL(`./${name}.plan`, import.meta.url), "utf8");
const open = (state, app, now) => handle({ artifactId: "kit-test", revision: 1, state, event: { type: "open" } }, app, now);

test("emit: calc.plan gives a definition with the fields of ToolSpec", async () => {
  const { definition, language, faults } = emit(await planned(plan("calc")));
  expect(faults).toEqual([]);
  expect(Object.keys(definition).sort()).toEqual(["capabilities", "description", "formatVersion", "initialState", "source", "stateSchema", "title", "ui"]);
  expect(definition).toMatchObject({ formatVersion: 3, title: "Tip split", description: "", capabilities: [] });
  expect(language).toBe("en");
  expect(definition.stateSchema).toEqual({ bill: "number", tip: "number", people: "number", d: { type: "object", fields: { perPerson_text: "string", total_text: "string" } } });
  expect(definition.initialState).toEqual({ bill: 80, tip: 15, people: 4, d: { perPerson_text: "23.00", total_text: "92.00" } });
  expect(definition.ui).toMatchObject({ type: "theme", preset: "clean", palette: "teal", cornerRadius: 20 });
  expect(definition.ui.children).toHaveLength(1);
  expect(definition.ui.children[0]).toMatchObject({ type: "column", style: { gap: "medium" } });
});

test("emit: the first figure is a hero card", async () => {
  const { definition } = emit(await planned(plan("calc")));
  expect(definition.ui.children[0].children[0]).toEqual({ type: "card", variant: "hero",
    children: [{ type: "metric", text: "Per person", bind: "d.perPerson_text", style: { emphasis: "display" } }] });
});

test("emit: names that Alphy refuses get safe names", async () => {
  const long = "a".repeat(60);
  const { definition, faults, app } = emit(await planned([
    'app("Names", lang="en")',
    'need 단어 = input("number", "Word", 1, min=0, max=10)',
    'need constructor = input("number", "Build", 2, min=0, max=10)',
    `need ${long} = input("number", "Long", 3, min=0, max=10)`,
    `need sum = figure("number", "Sum", $/단어 + $/constructor + $/${long})`,
    'need покупки = entries("Покупки", fields={назва: "text", куплено: "check", constructor: "text"}, rows=[{назва: "Хліб", куплено: false, constructor: "x"}])',
  ].join("\n")));
  expect(faults).toEqual([]);
  const schema = definition.stateSchema;
  const lists = Object.values(schema).filter((field) => field.type === "list");
  const fields = Object.keys(schema).flatMap((key) => [key, ...(schema[key].type === "object" ? Object.keys(schema[key].fields) : [])]);
  const rowFields = lists.flatMap((list) => Object.keys(list.fields));
  const actions = nodes(definition.ui).flatMap((node) => [node.action, node.dismissAction, node.reorder?.action, ...nodes(node.rowTemplate ?? { type: "" }).map((inner) => inner.action)]).filter(Boolean);
  // Three number fields, the add form (two fields and a button), and the delete button and the check of a row.
  expect(actions).toHaveLength(8);
  expect(lists).toHaveLength(1);
  expect(Object.keys(lists[0].fields)).toHaveLength(4);
  expect(lists[0].fields.id).toBe("string");
  for (const name of [...fields, ...rowFields, ...actions]) expect(SAFE.test(name) && !REFUSED.has(name)).toBe(true);
  expect(new Set([...fields, ...actions]).size).toBe(fields.length + actions.length);
  expect(new Set(rowFields).size).toBe(rowFields.length);
  // The compiled data maps each safe name back to its kit name, and each kit name to its safe name.
  for (const name of actions) expect(app.names.safe[app.names.kit[name]]).toBe(name);
  // The label of a control that has no text of its own is the kit's name of its line, never a safe name such as f3.
  const labels = nodes(definition.ui).flatMap((node) => [node, ...nodes(node.rowTemplate ?? { type: "" })]).filter((node) => node.action).map((node) => node.text);
  expect(labels).toContain("покупки remove");
  expect(labels).toContain("покупки куплено");
  for (const text of labels) expect(text).not.toMatch(/^f\d+$/);
});

test("emit: a plan over a limit is a fault of its need line", async () => {
  const needs = Array.from({ length: 41 }, (_, index) => `need x${index + 1} = input("number", "X ${index + 1}", 1, min=0, max=10)`);
  const { definition, faults } = emit(await planned(['app("Many", lang="en")', ...needs].join("\n")));
  expect(definition).toBeNull();
  expect(faults).toHaveLength(1);
  expect(faults[0]).toStartWith("need x41: ");
});

test("emit: a source over 64 KiB is a fault of the app", async () => {
  const { definition, faults } = emit(await planned(['app("Long", lang="en")', `$/note = "${"x".repeat(70000)}"`, "root = Column([note])", "note = Text($/note)"].join("\n")));
  expect(definition).toBeNull();
  expect(faults).toHaveLength(1);
  expect(faults[0]).toMatch(/^app: the source is \d+ bytes, and Alphy keeps at most 65536 bytes/);
});

test("emit: more than 32 other values of a need is a fault of the need", async () => {
  const fields = Array.from({ length: 33 }, (_, index) => `f${index + 1}: "text"`).join(", ");
  const { definition, faults } = emit(await planned(['app("Wide", lang="en")', `need wide = entries("Wide", fields={${fields}})`].join("\n")));
  expect(definition).toBeNull();
  // The list itself is a fault of Task 4 too. The fault of this limit names the need.
  expect(faults.filter((fault) => fault.startsWith("need wide: ") && fault.includes("32"))).toHaveLength(1);
});

test("emit: a screen deeper than 8 is a fault of the component that is too deep", async () => {
  const chain = Array.from({ length: 7 }, (_, index) => `c${index + 1} = Column([${index === 6 ? "deep" : `c${index + 2}`}])`);
  const { definition, faults } = emit(await planned(['app("Deep", lang="en")', "root = Column([c1])", ...chain, 'deep = Text("Deep")'].join("\n")));
  expect(definition).toBeNull();
  // The theme and the column of the app are levels 0 and 1, so c1 is at 2 and deep is at 9.
  expect(faults).toEqual([expect.stringMatching(/^deep: .*depth 9.*8/)]);
});

test("emit: a fault names the need of a line that a part wrote, and the author's line of an inline child", async () => {
  // NumberField has the bounds 0 to 1000000 when the need gives none, so -5 is outside them.
  const { definition, faults } = emit(await planned(['app("Faults", lang="en")', 'need t = input("number", "T", -5)'].join("\n")));
  expect(definition).toBeNull();
  expect(faults).toEqual([expect.stringMatching(/^need t: a NumberField needs min below max/)]);
  const chain = Array.from({ length: 6 }, (_, index) => `c${index + 1} = Column([${index === 5 ? 'Column([Text("Deep")])' : `c${index + 2}`}])`);
  const deep = emit(await planned(['app("Deep", lang="en")', "root = Column([c1])", ...chain].join("\n")));
  // c6 is at depth 7, its inline column at 8, and the inline text at 9: c6 is the line that the author wrote.
  expect(deep.faults).toEqual([expect.stringMatching(/^c6: .*depth 9/)]);
});

test("emit: a container with more than 50 children is a fault of its component, or of the app", async () => {
  const needs = Array.from({ length: 51 }, (_, index) => `need y${index + 1} = figure("number", "Y ${index + 1}", ${index + 1})`);
  const { definition, faults } = emit(await planned(['app("Figures", lang="en")', ...needs].join("\n")));
  expect(definition).toBeNull();
  expect(faults).toEqual([expect.stringMatching(/^app: .*51 children.*50/)]);
  const many = emit(await planned(['app("Many", lang="en")', "root = Column([many])", `many = Column([${Array(51).fill('Text("a")').join(", ")}])`].join("\n")));
  expect(many.definition).toBeNull();
  expect(many.faults).toEqual([expect.stringMatching(/^many: .*51 children.*50/)]);
});

test("emit: a shown list or record is a fault of its line", async () => {
  const { definition, faults } = emit(await planned([
    'app("Shown", lang="en")', '$/tags = ["a", "b"]', "$/card = {word: \"x\"}", "root = Column([tagText, cardMetric, tagButton])",
    "tagText = Text($/tags)", 'cardMetric = Metric("Card", $/card)', "tagButton = Button($/tags, action=set($/tags, []))",
  ].join("\n")));
  expect(definition).toBeNull();
  // A record as a value of the app is a later task. Each of the three components shows a value that is not one text or number.
  expect(faults.filter((fault) => /^(tagText|cardMetric|tagButton): .*(list|record)/.test(fault))).toHaveLength(3);
});

test("emit: list.plan gives a list with a row template and an add form", async () => {
  const { definition, language, faults } = emit(await planned(plan("list")));
  expect(faults).toEqual([]);
  expect(language).toBe("it");
  const rows = { type: "list", maxItems: 100, fields: { id: "string", nome: "string", corsia: "string", preso: "boolean" } };
  // The stored list, the object of the other values of the need, the sorted view that the run-time writes, and the shown values.
  expect(definition.stateSchema).toEqual({
    prodotti: rows, prodotti_s: { type: "object", fields: { new_nome: "string", new_corsia: "string" } }, prodotti_list: rows,
    d: { type: "object", fields: { daComprare_text: "string" } }, v: { type: "object", fields: { prodotti_add_enabled: "boolean" } },
  });
  const latte = { id: "r1", nome: "Latte", corsia: "Latticini", preso: false }, pane = { id: "r2", nome: "Pane", corsia: "Forno", preso: false }, mele = { id: "r3", nome: "Mele", corsia: "Frutta", preso: false };
  expect(definition.initialState).toEqual({
    prodotti: [latte, pane, mele], prodotti_s: { new_nome: "", new_corsia: "" }, prodotti_list: [pane, mele, latte],
    d: { daComprare_text: "3" }, v: { prodotti_add_enabled: false },
  });
  expect(definition.ui.children[0].children[1]).toEqual({ type: "section", text: "Prodotti", children: [
    { type: "list", text: "Prodotti", bind: "prodotti_list", emptyText: "Nothing here yet", rowTemplate: { type: "row", wrap: false, children: [
      { type: "text", text: "", bind: "nome", layout: { weight: 1 } },
      { type: "text", text: "", bind: "corsia" },
      { type: "icon", name: "Trash", text: "prodotti remove", action: "prodotti_remove" },
      { type: "checkbox", text: "prodotti preso", bind: "preso", action: "prodotti_preso" },
    ] } },
    { type: "row", wrap: false, children: [
      { type: "input", text: "Prodotto", bind: "prodotti_s.new_nome", action: "prodotti_new_nome", inputMode: "apply", layout: { weight: 1 } },
      { type: "input", text: "Corsia", bind: "prodotti_s.new_corsia", action: "prodotti_new_corsia", inputMode: "apply", layout: { weight: 1 } },
      { type: "button", text: "Add", action: "prodotti_add", variant: "filled", enabledBind: "v.prodotti_add_enabled" },
    ] },
  ] });
});

test("emit: an add form with more than two typed fields is a bottom sheet", async () => {
  const need = 'need tasks = entries("Tasks", fields={task: "text", note: "text", due: "date"}, rows=[{task: "Call", note: "Bank", due: "2026-11-20"}]';
  const fields = [
    { type: "input", text: "Task", bind: "tasks_s.new_task", action: "tasks_new_task", inputMode: "apply" },
    { type: "input", text: "Note", bind: "tasks_s.new_note", action: "tasks_new_note", inputMode: "apply" },
    { type: "dateInput", text: "Due", bind: "tasks_s.new_due", action: "tasks_new_due" },
  ];
  const add = { type: "button", text: "Add", action: "tasks_add", variant: "filled", enabledBind: "v.tasks_add_enabled" };
  const { definition, faults } = emit(await planned(['app("Tasks", lang="en")', `${need})`].join("\n")));
  expect(faults).toEqual([]);
  // The open flag is a value of the need that the run-time owns. A field in a sheet has no weight: its parent is not a row.
  expect(definition.stateSchema.tasks_s.fields.adding).toBe("boolean");
  expect(definition.initialState.tasks_s.adding).toBe(false);
  expect(definition.initialState.tasks_s.new_due).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  expect(definition.ui.children[0].children[0].children[1]).toEqual({ type: "column", children: [
    { type: "button", text: "Add", action: "tasks_addRow_open", variant: "filled", icon: "Plus" },
    { type: "bottomSheet", text: "Add", visibleBind: "tasks_s.adding", dismissAction: "tasks_addRow_dismiss", children: [...fields, add] },
  ] });
  // sheet=true: the part writes the flag, the button that opens the form, and a dialog. A dialog that holds a typed field is a bottom sheet.
  const sheet = emit(await planned(['app("Tasks", lang="en")', `${need}, sheet=true)`].join("\n")));
  expect(sheet.faults).toEqual([]);
  expect(sheet.definition.ui.children[0].children[0].children.slice(1)).toEqual([
    { type: "button", text: "Add", action: "tasks_open", variant: "filled" },
    { type: "bottomSheet", text: "Add", visibleBind: "tasks_s.adding", dismissAction: "tasks_addRow_dismiss", children: [...fields,
      { type: "row", children: [{ type: "icon", name: "Close", text: "tasks close", action: "tasks_close" }, add] }] },
  ]);
  // sheet=true with no typed field: a dialog that holds a field of the form is a bottom sheet too.
  const picks = emit(await planned(['app("Picks", lang="en")', 'need picks = entries("Picks", fields={day: ["Sat", "Sun"]}, sheet=true, rows=[])'].join("\n")));
  expect(picks.faults).toEqual([]);
  expect(picks.definition.ui.children[0].children[0].children.map((node) => node.type)).toEqual(["list", "button", "bottomSheet"]);
});

test("emit: a row with more than 12 fields is a fault of its need line", async () => {
  const fields = (count) => Array.from({ length: count }, (_, index) => `f${index + 1}: "text"`).join(", ");
  const { definition, faults } = emit(await planned([
    'app("Wide", lang="en")', `need wide = entries("Wide", fields={${fields(12)}})`,
    `need shown = entries("Shown", fields={${fields(11)}}, show={both: $f1 + $f2})`,
  ].join("\n")));
  expect(definition).toBeNull();
  // 12 fields and the id. 11 fields, the id, and the value that the row shows.
  expect(faults.filter((fault) => fault.startsWith("need wide: ") && fault.includes("13 fields"))).toHaveLength(1);
  expect(faults.filter((fault) => fault.startsWith("need shown: ") && fault.includes("13 fields"))).toHaveLength(1);
});

test("emit: each list, modal, and new input of the lists fixture becomes its Alphy node", async () => {
  // The device test eachListAndModalPassesParseAndSmoke sends the same plan through Alphy's validators.
  const { definition, faults } = emit(await planned(testPlan("lists")));
  expect(faults).toEqual([]);
  const [trip, wake, gear, acts, doses, steps] = definition.ui.children[0].children;
  expect([trip, wake, gear]).toEqual([
    { type: "dateInput", text: "Trip starts", bind: "trip", action: "trip_field" },
    { type: "timeInput", text: "Wake up", bind: "wake", action: "wake_field" },
    { type: "multiChoice", text: "Take with you", bind: "gear", action: "gear_field", options: ["Map", "Tent", "Stove"].map((value) => ({ text: value, value })) },
  ]);
  expect(definition.stateSchema).toMatchObject({ trip: "string", wake: "number", gear: { type: "array", maxItems: 512, items: "string" } });
  expect(definition.initialState).toMatchObject({ trip: "2026-11-21", wake: 450, gear: ["Map"] });
  // A time that a row only shows is a text that the run-time computes, so the list of acts is a view with that text.
  expect(definition.stateSchema.acts.fields).toEqual({ id: "string", what: "string", day: "string", at: "number" });
  expect(definition.initialState.acts.map((row) => row.at)).toEqual([540, 1140, 600]);
  // A list and a row control announce the captions of their need: its title and its labels=. An icon keeps the name of its line.
  expect(acts.children[0]).toEqual({ type: "groupedList", text: "Plan", bind: "acts_list", groupBy: "day", emptyText: "Nothing here yet", rowTemplate: { type: "row", wrap: false, children: [
    { type: "text", text: "", bind: "what", layout: { weight: 1 } },
    { type: "segmented", text: "Day", bind: "day", action: "acts_day", options: [{ text: "Sat", value: "Sat" }, { text: "Sun", value: "Sun" }] },
    { type: "badge", text: "", bind: "acts_at_value" },
    { type: "icon", name: "Trash", text: "acts remove", action: "acts_remove" },
  ] } });
  // The rows of a group come together, in the order of the first row of each group.
  expect(definition.initialState.acts_list.map((row) => [row.what, row.acts_at_value])).toEqual([["Hike", "09:00"], ["Dinner", "19:00"], ["Museum", "10:00"]]);
  expect(acts.children.slice(1).map((node) => [node.type, node.visibleBind, node.dismissAction])).toEqual([["button", undefined, undefined], ["bottomSheet", "acts_s.adding", "acts_addRow_dismiss"]]);
  // Each kind of row field with a control of its own, bound to the stored list.
  const [list, confirm, form] = doses.children;
  expect(list).toMatchObject({ type: "list", text: "Doses", bind: "doses" });
  expect(list.rowTemplate.children.map((node) => [node.type, node.text, node.bind ?? node.name])).toEqual([
    ["input", "Dose", "name"], ["toggle", "On", "on"], ["icon", "doses taken minus", "Minus"], ["numberInput", "Taken", "taken"],
    ["icon", "doses taken plus", "Plus"], ["dateInput", "Day", "day"], ["timeInput", "Time", "at"], ["segmented", "Kind", "kind"], ["icon", "doses remove", "Trash"],
  ]);
  expect(definition.initialState.doses.map((row) => row.at)).toEqual([480, 1200]);
  expect(confirm).toEqual({ type: "dialog", text: "Delete this dose?", visibleBind: "v.doses_confirm_visible", dismissAction: "doses_confirm_dismiss", children: [{ type: "row", children: [
    { type: "button", text: "Delete", action: "doses_yes", variant: "filled" }, { type: "button", text: "Keep", action: "doses_no", variant: "filled" }] }] });
  expect(form.children.map((node) => node.type)).toEqual(["button", "bottomSheet"]);
  expect(form.children[1].children.map((node) => node.type)).toEqual(["input", "numberInput", "dateInput", "timeInput", "segmented", "button"]);
  // The arrows of a row become the reorder of the list. Its label is a text that the run-time computes and that is never blank.
  expect(steps.children[0]).toMatchObject({ type: "list", text: "Steps", bind: "steps_list", reorder: { action: "steps_list_move", labelField: "label" } });
  expect(steps.children[0].rowTemplate.children.map((node) => [node.type, node.text])).toEqual([["text", ""], ["icon", "steps remove"], ["checkbox", "Done"]]);
  expect(definition.initialState.steps_list.map((row) => row.label)).toEqual(["Pack", "Drive", "Hike"]);
});

test("emit: an empty date start value becomes today", async () => {
  // 2026-11-18 is a Wednesday, day 3 of the week. A time that is not a time is 00:00, as the run-time reads it.
  const clock = settings.now, now = Date.UTC(2026, 10, 18, 12);
  settings.now = () => now;
  try {
    const { definition, faults, app } = emit(await planned([
      'app("Dates", lang="en")', 'need day = input("date", "Day", "")', 'need wd = figure("number", "Weekday", weekday($/day))',
      'need wake = input("time", "Wake", "")', 'need at = figure("text", "At", $/wake)',
    ].join("\n")));
    expect(faults).toEqual([]);
    expect(definition.initialState).toMatchObject({ day: "2026-11-18", wake: 0, d: { wd_text: "3", at_text: "00:00" } });
    expect(open(definition.initialState, app, now)).toEqual({ state: definition.initialState });
  } finally { settings.now = clock; }
});

test("emit: a label, an option, or an icon that changes with the row is a fault of its line", async () => {
  // Alphy draws one row template for all rows: a button, a field, and a choice have no binding for their text or options (ToolUiCatalog).
  const { definition, faults } = emit(await planned([
    'app("Rows", lang="en")', '$/rows = [{name: "a", kind: "x", seen: false}, {name: "b", kind: "y", seen: true}]', "root = Column([items])", "items = List($/rows, item)",
    "item = Row([pick, mark, size, shape])", "pick = Button($name, action=remove())", "mark = CheckBox($name, $seen)", 'size = Segmented("Kind", $kind, [$name, "x"])',
    "shape = IconButton(if($seen, \"Check\", \"Close\"), action=remove())",
  ].join("\n")));
  expect(definition).toBeNull();
  expect(faults).toEqual([
    expect.stringMatching(/^pick: the label of a Button in a row changes with the row/), expect.stringMatching(/^mark: the label of a CheckBox in a row changes with the row/),
    expect.stringMatching(/^size: the options of a Segmented in a row change with the row/), expect.stringMatching(/^shape: the icon of a IconButton in a row changes with the row/),
  ]);
});

/** The rendered work of a screen as ToolUiCatalog.validateRenderedWork counts it: each node, the template once for each row, the options of a choice, and one more for each row of a groupBy and of a reorder. */
const CHOICES = new Set(["select", "tabs", "singleChoice", "multiChoice", "segmented", "navigationBar", "navigationRail", "menu"]);
const renderWork = (node, values) => {
  let work = 1;
  if (["list", "table", "groupedList", "lazyList", "lazyGrid"].includes(node.type)) {
    const rows = values[node.bind];
    for (const row of rows) work += renderWork(node.rowTemplate, row);
    work += (node.groupBy === undefined ? 0 : rows.length) + (node.reorder === undefined ? 0 : rows.length);
  } else if (CHOICES.has(node.type)) work += node.options.length;
  else if (node.type === "text") work += node.spans?.length ?? 0;
  return work + (node.children ?? []).reduce((sum, child) => sum + renderWork(child, values), 0);
};
/** The state with each stored list at count rows (copies of its start rows), and the views that the run-time writes for it. */
const filled = (definition, app, count) => {
  const state = structuredClone(definition.initialState);
  for (const [root, field] of app.fields) {
    if (app.kinds[root] === "list") state[field] = Array.from({ length: count }, (_, index) => ({ ...state[field][index % state[field].length], id: `r${1000 + index}` }));
  }
  // Without the caps the run-time takes count rows past them, so the test can count the work of one row more.
  return open(state, { ...app, caps: {} }).state;
};

test("emit: each list holds the most rows that keep the full screen inside the render work limit", async () => {
  const rich = testPlan("rich"), counts = [];
  for (const text of [plan("list"), testPlan("lists"), rich]) {
    const { definition, faults, app } = emit(await planned(text));
    expect(faults).toEqual([]);
    // One count for each list of the app: the stored lists and their views.
    const lists = Object.values(definition.stateSchema).filter((schema) => schema.type === "list"), count = lists[0].maxItems;
    for (const schema of lists) expect(schema.maxItems).toBe(count);
    expect(renderWork(definition.ui, filled(definition, app, count))).toBeLessThanOrEqual(2048);
    if (count < 100) expect(renderWork(definition.ui, filled(definition, app, count + 1))).toBeGreaterThan(2048);
    counts.push(count);
  }
  // rich.plan starts with each list full to its count, and one start row more is a fault of its need.
  expect(counts).toEqual([100, 80, 91]);
  const over = emit(await planned(rich.replace("rows=[", 'rows=[{name: "Extra", size: "M", done: false}, ')));
  expect(over.definition).toBeNull();
  expect(over.faults).toEqual([expect.stringMatching(/^need shirts: \$\/shirts starts with 92 rows, .*91/)]);
});

test("emit: a list with room for fewer than 20 rows is a fault of its need", async () => {
  const choices = Array.from({ length: 8 }, (_, index) => `c${index + 1}: ["a", "b", "c", "d", "e", "f"]`).join(", ");
  const { definition, faults } = emit(await planned(['app("Wide", lang="en")', `need one = entries("One", fields={name: "text", ${choices}})`, `need two = entries("Two", fields={name: "text", ${choices}})`].join("\n")));
  expect(definition).toBeNull();
  expect(faults).toEqual([expect.stringMatching(/^need one: the row shows too much: .* 16 rows/), expect.stringMatching(/^need two: the row shows too much: .* 16 rows/)]);
});

test("emit: the arrows of a row with a sort or a group are a fault of its need", async () => {
  for (const order of ["sort=$step", "group=$day"]) {
    const { definition, faults } = emit(await planned(['app("Steps", lang="en")',
      `need steps = entries("Steps", fields={step: "text", day: ["Sat", "Sun"]}, reorder=true, ${order}, rows=[{step: "Pack", day: "Sat"}, {step: "Hike", day: "Sun"}])`].join("\n")));
    expect(definition).toBeNull();
    expect(faults).toEqual([expect.stringMatching(/^need steps: the arrows of a row move it in the stored list, .*no sort and no group/)]);
  }
});

test("emit: a list or an array that a part keeps beside its need is a state field of its own", async () => {
  const file = await planned(['app("Tasks", lang="en")', 'need tasks = entries("Tasks", fields={task: "text"}, rows=[{task: "Call"}])'].join("\n"));
  // No part keeps a list beside its need yet. These two lines stand for one that does.
  file.feed('$/tasks_log = [{at: "09:00"}]', null, "part");
  file.feed('$/tasks_tags = ["home"]', null, "part");
  file.partLines.get("tasks").push("$/tasks_log", "$/tasks_tags");
  const { definition, faults } = emit(file);
  expect(faults).toEqual([]);
  expect(definition.stateSchema).toMatchObject({ tasks_log: { type: "list", fields: { id: "string", at: "string" } }, tasks_tags: { type: "array", maxItems: 512, items: "string" } });
  expect(definition.stateSchema.tasks_s.fields).toEqual({ new_task: "string" });
  expect(definition.initialState).toMatchObject({ tasks_log: [{ at: "09:00" }], tasks_tags: ["home"] });
});

test("emit: a screen of more than 512 nodes is a fault of the app", async () => {
  const groups = Array.from({ length: 11 }, (_, index) => `g${index + 1} = Column([${Array(50).fill('Text("a")').join(", ")}])`);
  const { definition, faults } = emit(await planned(['app("Many", lang="en")', `root = Column([${groups.map((line) => line.split(" ")[0]).join(", ")}])`, ...groups].join("\n")));
  expect(definition).toBeNull();
  // The theme, the column of the app, 11 columns, and 550 texts.
  expect(faults).toEqual([expect.stringMatching(/^app: the screen has 563 nodes, and Alphy keeps at most 512/)]);
});

test("emit: a Slider or a NumberField whose bounds do not hold its start value is a fault of its line", async () => {
  const { definition, faults } = emit(await planned([
    'app("Bounds", lang="en")', "$/a = 50", "$/b = 150", "$/c = 5", "$/d = -5", "$/e = 5",
    "root = Column([free, over, flat, below, steep])",
    'free = Slider("Free", $/a)', 'over = Slider("Over", $/b)', 'flat = Slider("Flat", $/c, min=5, max=5)',
    'below = NumberField("Below", $/d)', 'steep = NumberField("Steep", $/e, min=0, max=10, step=20)',
  ].join("\n")));
  expect(definition).toBeNull();
  // A Slider without bounds has Alphy's 0 to 100, so 50 passes and 150 does not. A NumberField without bounds has 0 to 1000000.
  expect(faults.map((fault) => fault.split(":")[0])).toEqual(["over", "flat", "below", "steep"]);
  const { definition: free } = emit(await planned(['app("Free", lang="en")', "$/a = 50", "root = Column([free])", 'free = Slider("Free", $/a)'].join("\n")));
  expect(free.ui.children[0].children[0]).toEqual({ type: "slider", text: "Free", bind: "a", action: "free", min: 0, max: 100 });
});

test("emit: the other values of a part are one object of its need, and a computed test is a field of v", async () => {
  const { definition, faults } = emit(await planned([
    'app("Wizard", lang="en")',
    'need a = input("number", "A", 1, min=0, max=10)',
    'need b = input("number", "B", 2, min=0, max=10)',
    'need wiz = steps(["First", "Second"], [[a], [b]], finish="Done")',
  ].join("\n")));
  expect(faults).toEqual([]);
  expect(Object.keys(definition.stateSchema)).toEqual(["a", "b", "wiz_s", "d", "v"]);
  expect(definition.initialState.wiz_s).toEqual({ step: 0, done: false });
  const steps = definition.ui.children[0].children[0].children.filter((node) => node.type === "column");
  expect(steps.map((node) => node.visibleBind)).toEqual(["v.wiz_step0_visible", "v.wiz_step1_visible"]);
  expect(steps.map((node) => definition.initialState.v[node.visibleBind.slice(2)])).toEqual([true, false]);
  // A test that reads one boolean value of a state line binds that field itself.
  expect(nodes(definition.ui).find((node) => node.type === "banner")).toMatchObject({ visibleBind: "wiz_s.done" });
});

test("emit: shown values past 32 go to the object d2", async () => {
  const needs = Array.from({ length: 33 }, (_, index) => `need y${index + 1} = figure("number", "Y ${index + 1}", ${index + 1})`);
  const { definition, faults } = emit(await planned(['app("Figures", lang="en")', ...needs].join("\n")));
  expect(faults).toEqual([]);
  expect(Object.keys(definition.stateSchema.d.fields)).toHaveLength(32);
  expect(definition.stateSchema.d2).toEqual({ type: "object", fields: { y33_text: "string" } });
  expect(nodes(definition.ui).at(-1)).toMatchObject({ type: "metric", bind: "d2.y33_text" });
});

test("emit: each component of the node table becomes its Alphy node", async () => {
  // The device test eachNodeOfTheTablePassesParseAndSmoke sends the same plan through Alphy's validators.
  const { definition, faults } = emit(await planned(readFileSync(new URL("./nodes.plan", import.meta.url), "utf8")));
  expect(faults).toEqual([]);
  expect(definition.ui).toMatchObject({ type: "theme", preset: "soft" });
  expect(definition.ui).not.toHaveProperty("palette");
  expect(definition.ui.children[0].children).toEqual([
    { type: "text", text: "Lesson", style: { emphasis: "label", tone: "accent" } },
    { type: "text", text: "Hello", style: { emphasis: "title" } },
    { type: "text", text: "", bind: "d.intro_text", style: { emphasis: "label", tone: "accent" } },
    { type: "text", text: "Plain", style: { tone: "muted" } },
    { type: "section", text: "Part", children: [{ type: "text", text: "Inside" }] },
    { type: "row", wrap: false, children: [{ type: "text", text: "", bind: "name", layout: { weight: 1 } }, { type: "badge", text: "", bind: "count" }] },
    { type: "card", variant: "hero", children: [{ type: "metric", text: "Count", bind: "count", style: { emphasis: "display" } }] },
    { type: "badge", text: "New" },
    { type: "emoji", text: "🍵", value: "🍵" },
    { type: "divider" },
    { type: "banner", children: [{ type: "text", text: "Saved" }] },
    { type: "input", text: "Name", bind: "name", action: "nameField", inputMode: "apply" },
    { type: "toggle", text: "On", bind: "on", action: "onToggle" },
    { type: "checkbox", text: "Box", bind: "on", action: "onBox" },
    { type: "segmented", text: "Size", bind: "size", action: "sizePick", options: ["S", "M", "L"].map((value) => ({ text: value, value })) },
    { type: "select", text: "Big", bind: "big", action: "sizeList", options: ["S", "M", "L", "XL", "2XL", "3XL", "4XL"].map((value) => ({ text: value, value })) },
    { type: "slider", text: "Level", bind: "level", action: "levelSlider", min: 0, max: 10, step: 1 },
    // A Slider without bounds gets Alphy's own 0 to 100.
    { type: "slider", text: "Free", bind: "pct", action: "freeSlider", min: 0, max: 100 },
    { type: "numberInput", text: "Count", bind: "count", action: "countField", min: 0, max: 1000000 },
    { type: "button", text: "Add", action: "add", variant: "tonal" },
    { type: "row", children: [["Arrow", "up"], ["Sort", "down"], ["Back", "left"], ["Chevron", "right"], ["Pencil", "edit"]].map(([name, action]) => ({ type: "icon", name, text: action, action })) },
    { type: "text", text: "", bind: "level" },
    { type: "text", text: "Hidden", visible: false },
    { type: "text", text: "Many", visibleBind: "v.shownIf_visible" },
    { type: "button", text: "Reset", action: "enabledIf", variant: "filled", enabledBind: "v.enabledIf_enabled" },
  ]);
  expect(definition.initialState.d).toEqual({ intro_text: "Hi Ann" });
  expect(definition.initialState.v).toEqual({ shownIf_visible: false, enabledIf_enabled: false });
});
