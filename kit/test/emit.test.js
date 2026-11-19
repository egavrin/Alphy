// The emitter: an expanded app file as Alphy's app definition.

import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { App, settings } from "../src/core.js";
import { ACCENTS, LIMITS, STAGE, budgetOf, polygonsOf } from "../src/draw.js";
import { emit } from "../src/emit.js";
import { handle, kitOf, screen } from "../src/run.js";
import { allPlans, every, faultsOf, fixture, plan, planned, press, valueAt } from "./helpers.js";
import { compileAppFile } from "../src/compile.js";

/** Alphy's rule for a state field and an action id (ToolValueSchema.name, ToolUiCatalog). */
const SAFE = /^[A-Za-z][A-Za-z0-9_]{0,47}$/;
const REFUSED = new Set(["__proto__", "prototype", "constructor"]);
const nodes = (node) => [node, ...(node.children ?? []).flatMap(nodes)];
/** The text of a node and of each series of a chart. */
const texts = (node) => [node.text, ...(node.series ?? []).map((series) => series.text)];
/** The texts of each progress, ring, chart, and tabs control of a ui, after its type. */
const captions = (ui) => every(ui).filter((node) => ["progress", "progressRing", "chart", "tabs"].includes(node.type)).map((node) => [node.type, ...texts(node)]);
const testPlan = (name) => readFileSync(new URL(`./${name}.plan`, import.meta.url), "utf8");
const open = (state, app, now) => handle({ artifactId: "kit-test", revision: 1, state, event: { type: "open" } }, app, now);
/** Each id of a ui in its order: the action of each control, each dismiss and reorder action, and each surface. */
const ids = (ui) => every(ui).flatMap((node) => [node.action, node.dismissAction, node.reorder?.action, ...(node.surfaces ?? [])]).filter(Boolean);
/**
 * The names of a definition as ToolSpec.parse wants them, in the kit: each id once and safe, a start value for each field of the
 * state, and each binding to a field of the state (or of an object of it), or to a field of the row in a row template.
 */
const valid = (definition) => {
  const all = ids(definition.ui), schema = definition.stateSchema;
  for (const id of all) expect([id, SAFE.test(id) && !REFUSED.has(id)]).toEqual([id, true]);
  expect(all.filter((id, index) => all.indexOf(id) !== index)).toEqual([]);
  expect(Object.keys(definition.initialState).sort()).toEqual(Object.keys(schema).sort());
  const bound = (node, fields) => {
    for (const bind of [node.bind, node.visibleBind, node.enabledBind, node.categories, ...(node.series ?? []).map((series) => series.bind)].filter(Boolean)) {
      const [field, inner] = bind.split(".");
      expect([bind, Boolean(inner ? fields[field]?.fields?.[inner] : fields[field])]).toEqual([bind, true]);
    }
    for (const child of node.children ?? []) bound(child, fields);
    if (node.rowTemplate) bound(node.rowTemplate, schema[node.bind].fields);
  };
  bound(definition.ui, schema);
};

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
  const long = "a".repeat(60), longField = "b".repeat(60);
  const { definition, faults, app } = emit(await planned([
    'app("Names", lang="en")',
    'need 단어 = input("number", "Word", 1, min=0, max=10)',
    'need toString = input("number", "Build", 2, min=0, max=10)',
    `need ${long} = input("number", "Long", 3, min=0, max=10)`,
    `need sum = figure("number", "Sum", $/단어 + $/toString + $/${long})`,
    `need покупки = entries("Покупки", fields={назва: "text", куплено: "check", toString: "text", ${longField}: "text", 가격: "text"}, rows=[{назва: "Хліб", куплено: false, toString: "x", ${longField}: "y", 가격: "z"}])`,
  ].join("\n")));
  expect(faults).toEqual([]);
  const schema = definition.stateSchema;
  const lists = Object.values(schema).filter((field) => field.type === "list");
  const fields = Object.keys(schema).flatMap((key) => [key, ...(schema[key].type === "object" ? Object.keys(schema[key].fields) : [])]);
  const rowFields = lists.flatMap((list) => Object.keys(list.fields));
  const actions = nodes(definition.ui).flatMap((node) => [node.action, node.dismissAction, node.reorder?.action, ...nodes(node.rowTemplate ?? { type: "" }).map((inner) => inner.action)]).filter(Boolean);
  // Three number fields, the add form in its sheet (open, dismiss, four fields, and a button), and the delete button and the check of a row.
  expect(actions).toHaveLength(12);
  expect(lists).toHaveLength(1);
  // The id, the three fields before, a field of 60 letters, and a Korean field.
  expect(Object.keys(lists[0].fields)).toHaveLength(6);
  const [row] = definition.initialState[Object.keys(schema).find((key) => schema[key].type === "list")];
  expect(Object.values(row)).toEqual(["r1", "Хліб", false, "x", "y", "z"]);
  expect(lists[0].fields.id).toBe("string");
  for (const name of [...fields, ...rowFields, ...actions]) expect(SAFE.test(name) && !REFUSED.has(name)).toBe(true);
  // The top-level fields and the action ids differ from each other. The names inside an object differ from each other, and each object has its own (final review 3).
  const top = Object.keys(schema);
  expect(new Set([...top, ...actions]).size).toBe(top.length + actions.length);
  expect(new Set(rowFields).size).toBe(rowFields.length);
  // The compiled data keeps only the names of the screen that differ from the name that the kit wishes, each one f<index>.
  expect(Object.values(app.names).filter((name) => actions.includes(name)).length).toBeGreaterThan(0);
  for (const [key, name] of Object.entries(app.names)) { expect(name).toMatch(/^f\d+$/); expect(key).not.toMatch(/^(\$\/|need:)/); }
  // The label of a control of a part is a word of the kit or the readable name of its field, never a safe name such as f3.
  const labels = nodes(definition.ui).flatMap((node) => [node, ...nodes(node.rowTemplate ?? { type: "" })]).filter((node) => node.action).map((node) => node.text);
  expect(labels).toContain("Remove");
  expect(labels).toContain("Куплено");
  expect(labels).toContain("To String");
  for (const text of labels) expect(text).not.toMatch(/^f\d+$/);
});

test("emit: a plan over a limit is a fault of its need line", async () => {
  const needs = Array.from({ length: 41 }, (_, index) => `need x${index + 1} = input("number", "X ${index + 1}", 1, min=0, max=10)`);
  const { definition, faults } = emit(await planned(['app("Many", lang="en")', ...needs].join("\n")));
  expect(definition).toBeNull();
  expect(faults).toHaveLength(1);
  expect(faults[0]).toStartWith("need x41: ");
  // The drawings of the first state count together, as in one reply of Alphy: the fault names the drawing that passes a limit.
  const drawn = async (line) => emit(await planned(['app("Big", lang="en")', 'need small = drawing("Small", 64, 64, [circle(1, 1, 1, "ink")])', line].join("\n"))).faults;
  const over = (count, unit, limit) => `need big: with this drawing the app draws ${count} ${unit} at the start, and Alphy draws at most ${limit} ${unit} in one reply. Draw fewer shapes`;
  expect(await drawn('need big = drawing("Big", 320, 64, [each(500, circle(1, 1, 1, "ink")), each(12, circle(1, 1, 1, "ink"))])')).toEqual([over(513, "commands", 512)]);
  const points = Array.from({ length: 300 }, (_, index) => `${index % 100}, 1`).join(", ");
  expect(await drawn(`need big = drawing("Big", 320, 64, [each(7, polygon([${points}], "ink"))])`)).toEqual([over(2101, "coordinate pairs", 2048)]);
  expect(await drawn(`need big = drawing("Big", 320, 64, [each(2, text(1, 1, "${"x".repeat(1100)}", "ink"))])`)).toEqual([over(2200, "text characters", 2048)]);
  const wide = await drawn('need big = drawing("Big", 320, 64, [each(500, line($index + 0.25, $index + 0.25, $index + 0.75, $index + 0.75, "#80ff0000", width=1.25))])');
  expect(wide).toEqual([expect.stringMatching(/^need big: with this drawing the app draws \d+ bytes at the start, and Alphy draws at most 32768 bytes in one reply/)]);
  // Alphy declares at most 4 surfaces: one for each drawing and each timer that the run-time draws.
  const five = emit(await planned(['app("Five", lang="en")', ...[1, 2, 3, 4, 5].map((n) => `need d${n} = drawing("D${n}", 64, 64, [circle(1, 1, 1, "ink")])`)].join("\n")));
  expect(five.faults).toEqual(["need d5: the app draws 5 surfaces, and Alphy draws at most 4: one for each drawing and each timer with rounds or with no alert. Use fewer"]);
});

test("emit: a source over 64 KiB is a fault of the app", async () => {
  // A formula line is in the source: the run-time reads it. A start value is not.
  const { definition, faults } = emit(await planned(['app("Long", lang="en")', `$/note := "${"x".repeat(70000)}"`, "root = Column([note])", "note = Text($/note)"].join("\n")));
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
  // 51 figures: the hero and 50 rows (E.2, fix round 1: three figures or more in a place are rows) are 51 children of the screen.
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
    d: { type: "object", fields: { daComprare_text: "string" } },
  });
  const latte = { id: "r1", nome: "Latte", corsia: "Latticini", preso: false }, pane = { id: "r2", nome: "Pane", corsia: "Forno", preso: false }, mele = { id: "r3", nome: "Mele", corsia: "Frutta", preso: false };
  expect(definition.initialState).toEqual({
    prodotti: [latte, pane, mele], prodotti_s: { new_nome: "", new_corsia: "" }, prodotti_list: [pane, mele, latte],
    d: { daComprare_text: "3" },
  });
  expect(definition.ui.children[0].children[1]).toEqual({ type: "section", text: "Prodotti", children: [
    { type: "list", text: "Prodotti", bind: "prodotti_list", rowTemplate: { type: "row", wrap: false, style: { crossAlign: "center" }, children: [
      { type: "column", style: { gap: "none" }, layout: { weight: 1 }, children: [{ type: "text", text: "", bind: "nome" }, { type: "text", text: "", bind: "corsia", style: { tone: "muted" } }] },
      { type: "checkbox", text: "Preso", bind: "preso", action: "prodotti_preso" },
      { type: "icon", name: "Trash", text: "Rimuovi", action: "prodotti_remove" },
    ] } },
    { type: "column", children: [
      { type: "input", text: "Prodotto", bind: "prodotti_s.new_nome", action: "prodotti_new_nome", inputMode: "live" },
      { type: "input", text: "Corsia", bind: "prodotti_s.new_corsia", action: "prodotti_new_corsia", inputMode: "live" },
      { type: "button", text: "Aggiungi", action: "prodotti_add", icon: "Plus" },
    ] },
  ] });
});

test("emit: an add form with more than two typed fields is a bottom sheet", async () => {
  const need = 'need tasks = entries("Tasks", fields={task: "text", note: "text", due: "date"}, rows=[{task: "Call", note: "Bank", due: "2026-11-20"}]';
  const fields = [
    { type: "input", text: "Task", bind: "tasks_s.new_task", action: "tasks_new_task", inputMode: "live" },
    { type: "input", text: "Note", bind: "tasks_s.new_note", action: "tasks_new_note", inputMode: "live" },
    { type: "dateInput", text: "Due", bind: "tasks_s.new_due", action: "tasks_new_due" },
  ];
  const add = { type: "button", text: "Add", action: "tasks_add", icon: "Plus" };
  const { definition, faults } = emit(await planned(['app("Tasks", lang="en")', `${need})`].join("\n")));
  expect(faults).toEqual([]);
  // The open flag is a value of the need that the run-time owns. A field in a sheet has no weight: its parent is not a row.
  expect(definition.stateSchema.tasks_s.fields.adding).toBe("boolean");
  expect(definition.initialState.tasks_s.adding).toBe(false);
  expect(definition.initialState.tasks_s.new_due).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  // The first node is the hero card of the list (C.6), and the section of the list comes next.
  expect(definition.ui.children[0].children[1].children[1]).toEqual({ type: "column", children: [
    { type: "button", text: "Add", action: "tasks_addRow_open", variant: "filled", icon: "Plus" },
    { type: "bottomSheet", text: "Add", visibleBind: "tasks_s.adding", dismissAction: "tasks_addRow_dismiss", children: [...fields, add] },
  ] });
  // sheet=true: the part writes the flag, the button that opens the form, and a dialog. A dialog that holds a typed field is a bottom sheet.
  const sheet = emit(await planned(['app("Tasks", lang="en")', `${need}, sheet=true)`].join("\n")));
  expect(sheet.faults).toEqual([]);
  expect(sheet.definition.ui.children[0].children[1].children.slice(1)).toEqual([
    { type: "button", text: "Add", action: "tasks_open", icon: "Plus" },
    { type: "bottomSheet", text: "Add", visibleBind: "tasks_s.adding", dismissAction: "tasks_addRow_dismiss", children: [...fields, add] },
  ]);
  // sheet=true with no typed field: a dialog that holds a field of the form is a bottom sheet too.
  const picks = emit(await planned(['app("Picks", lang="en")', 'need picks = entries("Picks", fields={day: ["Sat", "Sun"]}, sheet=true, rows=[])'].join("\n")));
  expect(picks.faults).toEqual([]);
  expect(picks.definition.ui.children[0].children[1].children.map((node) => node.type)).toEqual(["list", "button", "bottomSheet"]);
  // A row with no weighted child is a flowRow in a row template too. The device test eachCardChartAndTabPassesParseAndSmoke sends this plan through Alphy's validators.
  expect(picks.definition.ui.children[0].children[1].children[0].rowTemplate.type).toBe("flowRow");
});

test("emit: an author's Row of an add form with more than two typed fields is a bottom sheet, as a Column is", async () => {
  const { definition, faults } = emit(await planned(['app("Tasks", lang="en")', 'need tasks = entries("Tasks", fields={task: "text", note: "text", due: "date"}, rows=[])',
    "tasks_addRow = Row([tasks_new_task, tasks_new_note, tasks_new_due, tasks_add])"].join("\n")));
  expect(faults).toEqual([]);
  expect(definition.initialState.tasks_s.adding).toBe(false);
  const form = definition.ui.children[0].children[1].children[1];
  expect(form.children.map((node) => [node.type, node.visibleBind])).toEqual([["button", undefined], ["bottomSheet", "tasks_s.adding"]]);
  expect(form.children[1].children.map((node) => node.type)).toEqual(["input", "input", "dateInput", "button"]);
  // The first screen has no typed field: each field is in the sheet.
  expect(every(definition.ui).filter((node) => ["input", "numberInput", "dateInput", "timeInput"].includes(node.type)).length).toBe(3);
  expect(nodes(definition.ui).filter((node) => node.type === "bottomSheet").flatMap(nodes).filter((node) => node.type === "input").length).toBe(2);
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
  const [hero, trip, wake, gear, acts, doses, steps] = definition.ui.children[0].children;
  // The plan has no figure, so its first list that shows gets a hero card with the count of its rows (C.6).
  expect(hero).toMatchObject({ type: "card", variant: "hero", children: [{ type: "metric", text: "Plan", style: { emphasis: "display" } }] });
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
  // A list and a row control announce the captions of their need: its title and its labels=. An icon button announces a word of the kit.
  expect(acts.children[0]).toEqual({ type: "groupedList", text: "Plan", bind: "acts_list", groupBy: "day", rowTemplate: { type: "row", wrap: false, style: { crossAlign: "center" }, children: [
    // A choice that the row does not change (no edit=true) is the muted line under the one text of the row, and a time is a small label after them (C.1).
    { type: "column", style: { gap: "none" }, layout: { weight: 1 }, children: [{ type: "text", text: "", bind: "what" }, { type: "text", text: "", bind: "day", style: { tone: "muted" } }] },
    { type: "text", text: "", bind: "acts_at_text", style: { emphasis: "label" } },
    { type: "icon", name: "Trash", text: "Remove", action: "acts_remove" },
  ] } });
  // The rows of a group come together, in the order of the first row of each group.
  expect(definition.initialState.acts_list.map((row) => [row.what, row.acts_at_text])).toEqual([["Hike", "09:00"], ["Dinner", "19:00"], ["Museum", "10:00"]]);
  expect(acts.children.slice(1).map((node) => [node.type, node.visibleBind, node.dismissAction])).toEqual([["button", undefined, undefined], ["bottomSheet", "acts_s.adding", "acts_addRow_dismiss"]]);
  // Each kind of row field with a control of its own, bound to the stored list.
  const [list, confirm, form] = doses.children;
  expect(list).toMatchObject({ type: "list", text: "Doses", bind: "doses" });
  expect(list.rowTemplate.children.map((node) => [node.type, node.text, node.bind ?? node.name])).toEqual([
    ["input", "Dose", "name"], ["toggle", "On", "on"], ["icon", "Less", "Minus"], ["numberInput", "Taken", "taken"],
    ["icon", "More", "Plus"], ["dateInput", "Day", "day"], ["timeInput", "Time", "at"], ["segmented", "Kind", "kind"], ["icon", "Remove", "Trash"],
  ]);
  expect(definition.initialState.doses.map((row) => row.at)).toEqual([480, 1200]);
  expect(confirm).toEqual({ type: "dialog", text: "Delete this dose?", visibleBind: "v.doses_confirm_visible", dismissAction: "doses_confirm_dismiss", children: [{ type: "flowRow", children: [
    { type: "button", text: "Delete", action: "doses_yes" }, { type: "button", text: "Keep", action: "doses_no" }] }] });
  expect(form.children.map((node) => node.type)).toEqual(["button", "bottomSheet"]);
  expect(form.children[1].children.map((node) => node.type)).toEqual(["input", "numberInput", "dateInput", "timeInput", "segmented", "button"]);
  // The arrows of a row become the reorder of the list. Its label is a text that the run-time computes and that is never blank.
  expect(steps.children[0]).toMatchObject({ type: "list", text: "Steps", bind: "steps_list", reorder: { action: "steps_list_move", labelField: "label" } });
  expect(steps.children[0].rowTemplate.children.map((node) => [node.type, node.text])).toEqual([["text", ""], ["checkbox", "Done"], ["icon", "Remove"]]);
  expect(definition.initialState.steps_list.map((row) => row.label)).toEqual(["Pack", "Drive", "Hike"]);
});

test("emit: an empty date start value becomes today, the day of the clock of the kit", async () => {
  // 2026-11-18 is a Wednesday, day 3 of the week, and 2026-11-22 a Sunday, day 7. Noon of a local day is that day in each time zone.
  // A time that is not a time is 00:00, as the run-time reads it.
  const clock = settings.now;
  try {
    for (const [day, weekday] of [["2026-11-18", "3"], ["2026-11-22", "7"]]) {
      const [year, month, date] = day.split("-").map(Number), now = new Date(year, month - 1, date, 12).getTime();
      settings.now = () => now;
      const { definition, faults, app } = emit(await planned([
        'app("Dates", lang="en")', 'need day = input("date", "Day", "")', 'need wd = figure("number", "Weekday", weekday($/day))',
        'need wake = input("time", "Wake", "")', 'need at = figure("text", "At", $/wake)',
      ].join("\n")));
      expect(faults).toEqual([]);
      expect(definition.initialState).toMatchObject({ day, wake: 0, d: { wd_text: weekday, at_text: "00:00" } });
      expect(open(definition.initialState, app, now)).toEqual({ state: definition.initialState });
    }
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

/**
 * The rendered work of a screen as ToolUiCatalog.validateRenderedWork counts it: each node, the template once for each row, the options of a choice,
 * one more for each row of a groupBy and of a reorder, and each point of a chart: its categories times its series.
 */
const CHOICES = new Set(["select", "tabs", "singleChoice", "multiChoice", "segmented", "navigationBar", "navigationRail", "menu"]);
const renderWork = (node, values) => {
  let work = 1;
  if (["list", "table", "groupedList", "lazyList", "lazyGrid"].includes(node.type)) {
    const rows = values[node.bind];
    for (const row of rows) work += renderWork(node.rowTemplate, row);
    work += (node.groupBy === undefined ? 0 : rows.length) + (node.reorder === undefined ? 0 : rows.length);
  } else if (CHOICES.has(node.type)) work += node.options.length;
  else if (node.type === "text") work += node.spans?.length ?? 0;
  else if (node.type === "chart") work += values[node.categories].length * node.series.length;
  return work + (node.children ?? []).reduce((sum, child) => sum + renderWork(child, values), 0);
};
/** The state with each stored list at count rows (copies of its start rows), the views that the run-time writes for it, and each chart full at 256 points. */
const filled = (definition, app, count) => {
  const state = structuredClone(definition.initialState);
  for (const [root, field] of app.fields) {
    if (app.kinds[root] === "list") state[field] = Array.from({ length: count }, (_, index) => ({ ...state[field][index % state[field].length], id: `r${1000 + index}` }));
  }
  // Without the caps the run-time takes count rows past them, so the test can count the work of one row more.
  const full = open(state, { ...app, caps: {} }).state;
  for (const chart of nodes(definition.ui).filter((node) => node.type === "chart")) {
    full[chart.categories] = Array.from({ length: 256 }, (_, index) => `p${index}`);
    for (const series of chart.series) full[series.bind] = Array(256).fill(1);
  }
  return full;
};

test("emit: each list holds the most rows that keep the full screen inside the render work limit", async () => {
  const rich = testPlan("rich"), counts = [];
  for (const text of [plan("list"), testPlan("lists"), rich, plan("tabs-chart"), testPlan("cards")]) {
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
  expect(counts).toEqual([100, 83, 91, 100, 100]);
  const over = emit(await planned(rich.replace("rows=[", 'rows=[{name: "Extra", size: "M", done: false}, ')));
  expect(over.definition).toBeNull();
  expect(over.faults).toEqual([expect.stringMatching(/^need shirts: \$\/shirts starts with 92 rows, .*91/)]);
  // A chart costs its full 256 points: two charts lower the count to 68, so the 91 start rows of each list are a fault.
  const charted = emit(await planned(`${rich}need shirtSizes = chart("Shirt sizes", groups($/shirts, $size), $key, $value)\nneed shoeSizes = chart("Shoe sizes", groups($/shoes, $size), $key, $value, kind="line")\n`));
  expect(charted.faults).toEqual(["shirts", "shoes"].map((list) => expect.stringMatching(new RegExp(`^need ${list}: \\$/${list} starts with 91 rows, and the screen has room for 68 rows`))));
});

test("emit: a screen whose charts alone pass the render work limit is a fault of the app", async () => {
  const charts = Array.from({ length: 8 }, (_, index) => `need c${index + 1} = chart("C ${index + 1}", $/points, text($it), $it)`);
  const { definition, faults } = emit(await planned(['app("Charts", lang="en")', "$/points = [1, 2, 3]", ...charts].join("\n")));
  expect(definition).toBeNull();
  // 8 charts full at 256 points cost 2048, and the nodes of the screen come on top.
  expect(faults).toEqual([expect.stringMatching(/^app: .*render work.*2048/)]);
  // A list beside them has no room at all: the fault of the app is the only fault, with no count of rows below 0.
  const listed = emit(await planned(['app("Charts", lang="en")', "$/points = [1, 2, 3]", ...charts, 'need notes = entries("Notes", rows=[{name: "One"}])'].join("\n")));
  expect(listed.faults).toEqual([expect.stringMatching(/^app: .*render work.*2048/)]);
});

test("emit: a list with room for fewer than 20 rows is a fault of its need", async () => {
  const choices = Array.from({ length: 8 }, (_, index) => `c${index + 1}: ["a", "b", "c", "d", "e", "f"]`).join(", ");
  // edit=true: each choice is a control of the row, so each row costs more render work.
  const { definition, faults } = emit(await planned(['app("Wide", lang="en")', `need one = entries("One", fields={name: "text", ${choices}}, edit=true)`, `need two = entries("Two", fields={name: "text", ${choices}}, edit=true)`].join("\n")));
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
  expect(free.ui.children[0].children[0]).toEqual({ type: "slider", text: "Free", bind: "a", action: "free", inputMode: "live", min: 0, max: 100 });
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
  // The last figure is a row of its title and its value (E.2).
  expect(nodes(definition.ui).at(-1)).toMatchObject({ type: "text", bind: "d2.y33_text", style: { emphasis: "number" } });
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
    { type: "row", wrap: false, style: { crossAlign: "center" }, children: [{ type: "text", text: "", bind: "name", layout: { weight: 1 } }, { type: "badge", text: "", bind: "count" }] },
    { type: "card", variant: "hero", children: [{ type: "metric", text: "Count", bind: "count", style: { emphasis: "display" } }] },
    { type: "badge", text: "New" },
    { type: "emoji", text: "🍵", value: "🍵" },
    { type: "divider" },
    { type: "banner", children: [{ type: "text", text: "Saved" }] },
    { type: "input", text: "Name", bind: "name", action: "nameField", inputMode: "live" },
    { type: "toggle", text: "On", bind: "on", action: "onToggle" },
    { type: "checkbox", text: "Box", bind: "on", action: "onBox" },
    { type: "segmented", text: "Size", bind: "size", action: "sizePick", options: ["S", "M", "L"].map((value) => ({ text: value, value })) },
    { type: "select", text: "Big", bind: "big", action: "sizeList", options: ["S", "M", "L", "XL", "2XL", "3XL", "4XL"].map((value) => ({ text: value, value })) },
    { type: "slider", text: "Level", bind: "level", action: "levelSlider", inputMode: "live", min: 0, max: 10, step: 1 },
    // A Slider without bounds gets Alphy's own 0 to 100.
    { type: "slider", text: "Free", bind: "pct", action: "freeSlider", inputMode: "live", min: 0, max: 100 },
    { type: "numberInput", text: "Count", bind: "count", action: "countField", min: 0, max: 1000000 },
    { type: "button", text: "Add", action: "add", variant: "tonal" },
    // A row with no weighted child wraps (the native row has wrap true by default): Alphy's flowRow.
    { type: "flowRow", children: [["Arrow", "up"], ["Sort", "down"], ["Back", "left"], ["Chevron", "right"], ["Pencil", "edit"]].map(([name, action]) => ({ type: "icon", name, text: action, action })) },
    { type: "text", text: "", bind: "level" },
    { type: "text", text: "Hidden", visible: false },
    { type: "text", text: "Many", visibleBind: "v.shownIf_visible" },
    { type: "button", text: "Reset", action: "enabledIf", enabledBind: "v.enabledIf_enabled" },
    // A progress bar in a row binds a field of the view that the run-time writes: the share of the row in percent.
    { type: "section", text: "Goals", children: [{ type: "list", text: "Goals", bind: "goalList", rowTemplate: { type: "row", wrap: false, style: { crossAlign: "center" }, children: [
      { type: "text", text: "", bind: "name", layout: { weight: 1 } }, { type: "progress", text: "Goal", variant: "bar", min: 0, max: 100, bind: "goalRow_1_value" }] } }] },
    // A ring with no text under it has no children, and it takes the title of its Section.
    { type: "section", text: "Calm", children: [{ type: "progressRing", text: "Calm", size: "large", min: 0, max: 100, bind: "d.calmRing_value" }] },
    // Dots show a value that is not a whole number as it is.
    { type: "progress", text: "Half", variant: "dots", min: 0, max: 4, bind: "d.half_value" },
  ]);
  expect(definition.initialState.d).toEqual({ intro_text: "Hi Ann", calmRing_value: 25, half_value: 2.5 });
  expect(definition.initialState.goalList.map((row) => row.goalRow_1_value)).toEqual([75, 20]);
  expect(definition.initialState.v).toEqual({ shownIf_visible: false, enabledIf_enabled: false });
});

test("emit: quiz.plan gives a card with the question and its buttons", async () => {
  const { definition, faults } = emit(await planned(plan("quiz")));
  expect(faults).toEqual([]);
  expect(definition.ui.children[0].children).toEqual([
    { type: "card", variant: "hero", children: [
      { type: "metric", text: "漢字", bind: "d.card_kanji", style: { emphasis: "display" } },
      { type: "button", text: "答えを見る", action: "revealButton", visibleBind: "v.revealButton_visible" },
    ] },
    // A test that reads one boolean value of a state line binds that field itself.
    { type: "card", children: [{ type: "metric", text: "読み方", bind: "d.card_reading" }, { type: "metric", text: "意味", bind: "d.card_meaning" }], visibleBind: "shown" },
    { type: "flowRow", children: [
      { type: "button", text: "覚えた", action: "learnedButton" }, { type: "button", text: "まだ", action: "notYetButton" }] },
    { type: "flowRow", children: [{ type: "metric", text: "覚えた", bind: "learned" }, { type: "metric", text: "まだ", bind: "notYet" }] },
  ]);
  expect(definition.initialState).toMatchObject({ index: 0, shown: false, learned: 0, notYet: 0, d: { card_kanji: "水" }, v: { revealButton_visible: true } });
});

test("emit: tabs-chart.plan gives a tabs control, groups with visibleBind, and a chart with two array fields", async () => {
  const { definition, faults } = emit(await planned(plan("tabs-chart")));
  expect(faults).toEqual([]);
  const [tabs] = definition.ui.children[0].children;
  const title = "Gastos por categoría";
  // The open tab is a value of the need that the run-time owns: the title of the tab, the first one at the start.
  expect(tabs.children[0]).toEqual({ type: "tabs", text: "Resumen, Añadir", bind: "resumen_s.tab", action: "resumen_view", options: ["Resumen", "Añadir"].map((value) => ({ text: value, value })) });
  expect(tabs.children.slice(1).map((group) => [group.type, group.visibleBind, group.children.map((node) => node.type)])).toEqual([
    ["column", "v.resumen_view_tab0", ["chart", "card"]], ["column", "v.resumen_view_tab1", ["section"]]]);
  // The chart shows its title once, as its label (Task 11b-2b, A.6).
  expect(tabs.children[1].children[0]).toEqual({ type: "chart", text: title, kind: "bar", categories: "grafico_chart_labels", series: [{ text: title, bind: "grafico_chart_values" }] });
  // The figure that is the main element stays the hero card in its tab.
  expect(tabs.children[1].children[1]).toMatchObject({ type: "card", variant: "hero" });
  expect(definition.stateSchema).toMatchObject({ resumen_s: { type: "object", fields: { tab: "string" } },
    grafico_chart_labels: { type: "array", maxItems: 256, items: "string" }, grafico_chart_values: { type: "array", maxItems: 256, items: "number" } });
  expect(definition.initialState).toMatchObject({ resumen_s: { tab: "Resumen" }, grafico_chart_labels: [], grafico_chart_values: [], v: { resumen_view_tab0: true, resumen_view_tab1: false } });
});

test("emit: a Tabs that Alphy cannot draw is a fault of its line", async () => {
  const { definition, faults } = emit(await planned([
    'app("Tabs", lang="en")', '$/a = "A"', "root = Column([inline, same, few, empty])", 'one = Text("One")', 'two = Text("Two")',
    'inline = Column([Tabs(["A", "B"], [[one], [two]])])', 'same = Tabs(["A", "A"], [[one], [two]])', 'few = Tabs(["A", "B", "C"], [[one], [two]])',
    'empty = Tabs(["A", $/a + "B"], [[one], []])',
  ].join("\n")));
  expect(definition).toBeNull();
  expect(faults).toEqual([
    expect.stringMatching(/^inline: a Tabs needs a line of its own/), expect.stringMatching(/^same: .*each one different. Now they are \["A","A"\]/),
    expect.stringMatching(/^few: .*one title for each of its 2 groups/), expect.stringMatching(/^empty: .*group 2 .*empty/),
  ]);
});

test("emit: a progress need that is the main element is a ring in the hero card", async () => {
  const { definition, faults } = emit(await planned(['app("Habits", lang="en")', 'need done = progress("Done", count($/habits, $done), len($/habits))',
    'need habits = entries("Habits", fields={name: "text", done: "check"}, rows=[{name: "Run", done: true}, {name: "Read", done: false}, {name: "Sleep", done: false}])'].join("\n")));
  expect(faults).toEqual([]);
  // The rule of the main element is the rule of the first figure (parts.js expand): the progress part writes the ring into the hero card.
  // The host shows the label of the ring under it, so the card has no heading of the same title.
  expect(definition.ui.children[0].children[0]).toEqual({ type: "card", variant: "hero", children: [
    { type: "progressRing", text: "Done", size: "large", min: 0, max: 100, bind: "d.done_view_0_value", children: [{ type: "text", text: "", bind: "d.done_text", style: { emphasis: "display" } }] },
  ] });
  // The ring shows the share in percent, and its text inside it the count.
  expect(definition.initialState.d).toMatchObject({ done_view_0_value: (1 / 3) * 100, done_text: "1 / 3" });
});

test("emit: a lesson of three steps has a dots progress with max 3", async () => {
  const steps = (count) => `[${Array.from({ length: count }, (_, index) => `{title: "Part ${index + 1}", text: "Step ${index + 1}."}`).join(", ")}]`;
  const { definition, faults } = emit(await planned(['app("Lessons", lang="en")', `need les = lesson(${steps(3)})`, `need long = lesson(${steps(13)})`].join("\n")));
  expect(faults).toEqual([]);
  const bars = nodes(definition.ui).filter((node) => node.type === "progress");
  // Alphy draws at most 12 dots, so a lesson of 13 steps has a bar of the share in percent.
  expect(bars).toEqual([
    { type: "progress", text: "Step", variant: "dots", min: 0, max: 3, bind: "d.les_bar_value" },
    { type: "progress", text: "Step", variant: "bar", min: 0, max: 100, bind: "d.long_bar_value" },
  ]);
  expect(definition.initialState.d).toMatchObject({ les_bar_value: 1, long_bar_value: (1 / 13) * 100 });
});

test('emit: a picture need is a fault "picture is not built yet"', async () => {
  const { definition, faults } = emit(await planned(['app("Later", lang="en")', 'need photo = picture("A plate of pasta")', 'need source = link("Docs", "https://example.com/docs")'].join("\n")));
  expect(definition).toBeNull();
  expect(faults).toEqual(["need photo: picture is not built yet", "need source: link is not built yet"]);
});

test('emit: the rover of the dialogue mock with its sketch is a fault "picture is not built yet", and only that', async () => {
  // dialogue-rover.plan is model3d.plan with the sketch of screen 4. The model is built: only the picture is a fault.
  const { definition, faults } = emit(await planned(fixture("dialogue-rover")));
  expect([definition, faults]).toEqual([null, ["need sketch: picture is not built yet"]]);
});

test("emit: model3d.plan gives a surface, a canvas, three part buttons, two arrows, and the explode button", async () => {
  const { definition, faults } = emit(await planned(plan("model3d")));
  expect(faults).toEqual([]);
  valid(definition);
  expect(definition.surfaces).toEqual([{ id: "rover_model", width: 320, height: 240 }]);
  // The model is the main element of the plan, so its card is the hero, and the explode button is outlined (Task 11b-2b, A.8).
  // The hero card holds the model only. The tonal chips and the buttons stand under it (round before the review: model3d.png of appfile-look-all-2).
  const screen = definition.ui.children[0].children, at = screen.findIndex((node) => node.type === "card" && node.children[0].type === "canvas");
  const [card, ...under] = screen.slice(at, at + 3);
  expect([card.variant, card.children]).toEqual(["hero", [{ type: "canvas", text: "Живая 3D-сцена", surfaces: ["rover_model"] }]]);
  expect(under.map((node) => [node.type, ...node.children.map((child) => [child.type, child.name ?? child.variant, child.text, child.action])])).toEqual([
    ["flowRow", ["button", "tonal", "Камера", "rover_part_camera"], ["button", "tonal", "Панель", "rover_part_panel"], ["button", "tonal", "Колёса", "rover_part_wheels"]],
    ["flowRow", ["icon", "Back", "−30°", "rover_left"], ["icon", "Chevron", "+30°", "rover_right"], ["button", "outlined", "Разобрать", "rover_explode"]]]);
  // The record of the part is a field of plain values, and the drag of the model is a field of the run-time.
  expect([definition.stateSchema.rover, definition.initialState.rover]).toEqual([{ type: "object", fields: { selected: "string", exploded: "boolean", turn: "number" } }, { selected: "", exploded: false, turn: 30 }]);
  expect([definition.stateSchema.drag, definition.initialState.drag]).toEqual([{ type: "object", fields: { surface: "string", pointer: "number", x: "number" } }, { surface: "", pointer: 0, x: 0 }]);
  // A model with no turn has no drag.
  const still = emit(await planned('app("Still", lang="en")\nneed m = model3d("M", [box("a", 0, 0, 0, 1, 1, 1, "ink")], turn=false)'));
  expect([still.faults, Object.keys(still.definition.stateSchema), still.definition.surfaces]).toEqual([[], ["m"], [{ id: "m_model", width: 320, height: 240 }]]);
});

test("emit: model3d.plan stays under the drawing limits", async () => {
  // The rover at rest, taken apart, and with the camera selected. Then each whole turn from 0 to 359, together and apart, each with and without the camera selected.
  const file = await planned(plan("model3d")), kit = new App(file), props = () => kit.find("rover_model").props;
  const used = () => budgetOf([["rover_model", [STAGE, ...polygonsOf(props().bodies, props().value, false, props().selected, 320, 240, ACCENTS.teal)]]]);
  const rest = used();
  kit.press("rover_explode");
  const apart = used();
  kit.press("rover_explode");
  kit.press("rover_part_camera");
  const selected = used();
  kit.press("rover_part_camera");
  const most = { commands: 0, pairs: 0, chars: 0, bytes: 0 };
  for (const id of [null, "rover_explode", "rover_part_camera", "rover_explode"]) {
    if (id) kit.press(id);
    for (let turn = 0; turn < 360; turn++) {
      kit.input("rover_model", turn);
      for (const [unit, value] of Object.entries(used())) most[unit] = Math.max(most[unit], value);
    }
  }
  console.log(`model3d.plan rover_model: at rest ${JSON.stringify(rest)}, taken apart ${JSON.stringify(apart)}, camera selected ${JSON.stringify(selected)}, most at any whole turn ${JSON.stringify(most)}; limits ${JSON.stringify(LIMITS)}`);
  for (const unit of Object.keys(LIMITS)) expect([unit, most[unit] <= LIMITS[unit]]).toEqual([unit, true]);
  expect(emit(file).faults).toEqual([]);
});

test("emit: a model past a limit of one reply is a fault of its need line", async () => {
  // 100 boxes in a row: about 3 faces each, and each face costs about 115 bytes.
  const { faults } = emit(await planned('app("Big", lang="en")\nneed big = model3d("Big", [each(100, box("", $index, 0, 0, 0.5, 0.5, 0.5, "ink"))], turn=false, explode=false)'));
  expect(faults).toHaveLength(1);
  const [, bytes] = /^need big: with this drawing the app draws (\d+) bytes at the start, and Alphy draws at most 32768 bytes in one reply\. Draw fewer shapes$/.exec(faults[0]) ?? [];
  expect(Number(bytes)).toBeGreaterThan(LIMITS.bytes);
});

test("emit: a record of an author's own state line is still a fault", async () => {
  const { faults } = emit(await planned(['app("Rec", lang="en")', "$/r = {a: 1}", "root = Column([t])", "t = Text($/r/a)"].join("\n")));
  expect(faults).toEqual(["$/r: $/r is a record, and that is not built yet"]);
});

test("emit: an author's root beside a tabs need shows each control of the held needs once", async () => {
  // The device test anAuthorsRootBesideATabsNeedPassesParseAndSmoke sends the same plan through ToolSpec.parse.
  const file = await planned(['app("Held", lang="en")', 'need a = counter("A")', 'need b = counter("B")', 'need pages = tabs(["One", "Two"], [[a], [b]])', 'note = Text("Hi")', "root = Column([note])"].join("\n"));
  expect(file.get("root").raw).toBe("root = Column([note, pages_view])");
  const { definition, faults } = emit(file);
  expect(faults).toEqual([]);
  const actions = every(definition.ui).map((node) => node.action).filter(Boolean);
  expect(actions).toEqual(["pages_view", "a_minus", "a_plus", "b_minus", "b_plus"]);
});

test("emit: each card, chart, tab, lesson, and question of the cards fixture becomes its Alphy node", async () => {
  // The device test eachCardChartAndTabPassesParseAndSmoke sends the same plan through Alphy's validators.
  const { definition, faults } = emit(await planned(testPlan("cards")));
  expect(faults).toEqual([]);
  const all = nodes(definition.ui), state = definition.initialState;
  expect(all[2]).toMatchObject({ type: "card", variant: "hero", children: [{ type: "progressRing", size: "large" }] });
  expect(all.filter((node) => node.type === "chart").map((node) => [node.kind, state[node.categories].length, state[node.series[0].bind].length])).toEqual([["bar", 3, 3], ["line", 256, 256]]);
  // The line chart keeps the last 256 of its 300 points.
  expect([state.trend_chart_labels[0], state.trend_chart_values.at(-1)]).toEqual(["45", 600]);
  expect(all.filter((node) => node.type === "progress").map((node) => [node.variant, node.max])).toEqual([["dots", 3], ["bar", 100]]);
  expect(all.find((node) => node.type === "tabs").options.map((option) => option.value)).toEqual(["Charts", "Tasks"]);
  // The steps of wiz are columns with visibleBind, and the question has its answers in a flow row and its result in a banner.
  expect(all.filter((node) => node.type === "column" && node.visibleBind?.startsWith("v.wiz_")).map((node) => node.visibleBind)).toEqual(["v.wiz_step0_visible", "v.wiz_step1_visible"]);
  const ask = all.find((node) => node.children?.some((child) => child.action === "ask_opt0"));
  expect([ask.type, ask.children.map((node) => node.action)]).toEqual(["flowRow", ["ask_opt0", "ask_opt1", "ask_opt2"]]);
  expect(all.find((node) => node.type === "banner" && node.visibleBind === "v.ask_result_visible")).toBeDefined();
});

test("emit: the text of a progress, a ring, a chart, and the tabs is its label, else the title of its Section, else the name of its line", async () => {
  const { definition, faults } = emit(await planned([
    'app("Captions", lang="en")', "$/n = 3", "$/points = [1, 2]", "root = Column([bare, own, sec, pages])",
    "bare = Progress($/n, 10)", 'own = Progress($/n, 10, label="Own")', 'sec = Section("Sec", [ring, chart, blank, mine])',
    "ring = Ring($/n, 10)", "chart = Chart($/points, text($it), $it)", 'blank = Progress($/n, 10, label="")', 'mine = Ring($/n, 10, "3 of 10", label="Mine")',
    'one = Text("One")', 'two = Text("Two")', 'pages = Tabs(["A", "B"], [[one], [two]], label="Pages")',
  ].join("\n")));
  expect(faults).toEqual([]);
  // An empty label is no label. The text of a ring is a large text inside it, a child of the ring.
  expect(captions(definition.ui)).toEqual([["progress", "bare"], ["progress", "Own"], ["progressRing", "Sec"], ["chart", "Sec", "Sec"], ["progress", "Sec"], ["progressRing", "Mine"], ["tabs", "Pages"]]);
  expect(every(definition.ui).find((node) => node.text === "Mine").children).toEqual([{ type: "text", text: "3 of 10", style: { emphasis: "display" } }]);
});

test("emit: the progress, ring, chart, and tabs of a part show a caption of the part, never the name of a line", async () => {
  for (const [text, expected] of [
    [plan("tabs-chart"), [["tabs", "Resumen, Añadir"], ["chart", "Gastos por categoría", "Gastos por categoría"]]],
    // The title of a need, and the step word of a lesson. A tabs need with no title= shows its titles.
    [testPlan("cards"), [["progressRing", "Done"], ["tabs", "Charts, Tasks"], ["chart", "Sizes", "Sizes"], ["chart", "Trend", "Trend"], ["progress", "Step"], ["progress", "Step"]]],
  ]) {
    const file = await planned(text), { definition } = emit(file);
    const names = [...file.entries.values()].map((entry) => entry.key.replace(/_/g, " "));
    expect(captions(definition.ui)).toEqual(expected);
    for (const caption of captions(definition.ui).flatMap(([, ...shown]) => shown)) expect(names.filter((name) => caption === name || caption.startsWith(name + " "))).toEqual([]);
  }
  const titled = emit(await planned(['app("T", lang="en")', 'need a = content("A")', 'need b = content("B")', 'need pages = tabs(["One", "Two"], [[a], [b]], title="Pages")'].join("\n")));
  expect(captions(titled.definition.ui)).toEqual([["tabs", "Pages"]]);
});

test("emit: the texts that are the name of a line, in each saved plan and fixture that compiles", async () => {
  // The list of now, so that a later task sees when it grows. A part names each control by a word of the kit or by its field (Task 11b-2a, A.3 and A.4),
  // so only the icon buttons that an author wrote with no label= are in it.
  const PINNED = {
    "plans/calc.plan": [], "plans/list.plan": [], "plans/quiz.plan": [], "plans/section.plan": [],
    "plans/tabs-chart.plan": [], "plans/tea.plan": [], "plans/timer-rounds.plan": [], "plans/lesson.plan": [], "plans/model3d.plan": [], "test/shapes.plan": [], "test/bytes.plan": [], "test/model.plan": [],
    "test/cards.plan": [], "test/free.plan": [], "test/fixtures/habits.plan": [], "test/fixtures/price-filter.plan": [],
    "test/fixtures/shopping.plan": [], "test/fixtures/units-pl.plan": [], "test/fixtures/water-ko.plan": [],
    "test/fixtures/look-water-ko.plan": [], "test/fixtures/look-shopping-it.plan": [], "test/looks.plan": [],
    "test/fixtures/mock-water-ko.plan": [], "test/fixtures/mock-units-pl.plan": [], "test/fixtures/mock-shopping-it.plan": [], "test/fixtures/mock-habits-uk.plan": [],
    "test/fixtures/mock-price-filter.plan": [], "test/fixtures/look-deck-es.plan": [], "test/fixtures/look-guide-es.plan": [],
    "test/lists.plan": [],
    "test/nodes.plan": ["up", "down", "left", "right", "edit"],
    "test/rich.plan": [],
  };
  const found = {};
  for (const [path, text] of allPlans()) {
    const file = await planned(text), { definition } = emit(file);
    if (!definition) continue;
    const names = new Set([...file.entries.values()].map((entry) => entry.key.replace(/_/g, " ")));
    found[path] = every(definition.ui).flatMap(texts).filter((shown) => names.has(shown));
  }
  expect(found).toEqual(PINNED);
});

test("emit: a name that the screen wishes and a state field has already gets a name of its own", async () => {
  const { definition, faults, app } = emit(await planned(['app("Clash", lang="en")', '$/big_labels = ["x"]', "$/points = [1, 2]", "root = Column([big, add, shown])",
    "big = Chart($/points, text($it), $it)", 'add = Button("Add", action=append($/points, 3))', "shown = Text(join($/big_labels))"].join("\n")));
  expect(faults).toEqual([]);
  const chart = every(definition.ui).find((node) => node.type === "chart");
  expect(chart.categories).toMatch(/^f\d+$/);
  expect(definition.stateSchema.big_labels).toEqual({ type: "array", maxItems: 512, items: "string" });
  const { state } = handle({ artifactId: "kit-test", revision: 1, state: definition.initialState, event: { type: "action", actionId: "add", value: null } }, app);
  expect([state.big_labels, state[chart.categories], state.d.shown_text]).toEqual([["x"], ["1", "2", "3"], "x"]);
});

test("emit: section.plan gives a valid definition", async () => {
  // The device test sectionPassesParseAndSmoke sends the same plan through Alphy's validators.
  const { definition, language, faults, app } = emit(await planned(plan("section")));
  expect(faults).toEqual([]);
  expect(language).toBe("uk");
  valid(definition);
  // The author's root has a hero card with the count of the habits first (Task 11b-2b, B.1: the plan had no main element).
  expect(definition.ui.children[0].children[0]).toEqual({ type: "card", variant: "hero", children: [{ type: "metric", text: "Звичок у трекері", bind: expect.any(String), style: { emphasis: "display" } }] });
  // The free lines of the hole: a Section with a List of the habits, whose row of its own shows the name and a text that each row computes.
  expect(definition.ui.children[0].children[2]).toEqual({ type: "section", text: "Серія днів поспіль", children: [
    { type: "list", text: "Серія днів поспіль", bind: "streak_list", emptyText: "Ще немає звичок", rowTemplate: { type: "row", wrap: false, style: { crossAlign: "center" }, children: [
      { type: "text", text: "", bind: "name", layout: { weight: 1 } }, { type: "text", text: "", bind: "streak_text_text" }] } }] });
  expect(definition.initialState.streak_list.map((row) => [row.id, row.streak_text_text])).toEqual([["r1", "Серія: 0"], ["r2", "Серія: 0"], ["r3", "Серія: 0"]]);
  expect(ids(definition.ui)).toEqual(["habits_remove", ...["mon", "tue", "wed", "thu", "fri", "sat", "sun"].map((day) => `habits_${day}`), "habits_new_name", "habits_add"]);
  // The check of Monday makes a streak of 1.
  const { state } = press(definition.initialState, app, "habits_mon", { key: "r1", value: true });
  expect(state.streak_list.map((row) => row.streak_text_text)).toEqual(["Серія: 1", "Серія: 0", "Серія: 0"]);
});

test("emit: an inline child gets a stable name", async () => {
  // An inline child has the name of its parent and its place: the button of bar is bar_0. The line bar_0 and the line bar_1 keep names of their own.
  const { definition, faults, app } = emit(await planned(testPlan("free")));
  expect(faults).toEqual([]);
  valid(definition);
  const [, , bar, zero, twice] = definition.ui.children[0].children;
  expect([bar.children[0].action, bar.children[1].bind]).toEqual(["bar_0", "d.bar_1_text"]);
  expect([zero.action, twice.bind]).toEqual(["f0", "d.f1"]);
  // The controls of the inline row template of a List in an inline Section.
  expect(ids(definition.ui).slice(0, 3)).toEqual(["items_row_0", "items_row_2", "items_row_3"]);
  const more = press(definition.initialState, app, "bar_0").state;
  expect([more.count, more.d.bar_1_text, more.d.f1]).toEqual([1, "Count 1", "Twice 2"]);
  expect(press(more, app, "f0").state.count).toBe(0);
  // An author's line with when(...): an odd count goes to the next even count, and an even count stays.
  const even = press(more, app, "even").state;
  expect([even.count, press(even, app, "even")]).toEqual([2, { state: even }]);
  // The walk of each event gives each node the name that the emitter gave it.
  expect(screen(kitOf(app, more), app).ui).toEqual(definition.ui);
});

test("emit: enabled=test is an enabledBind", async () => {
  const { definition, faults, app } = emit(await planned(['app("On", lang="en")', "$/n = 0", "$/on = false", "root = Column([reset, flag, off])",
    'reset = Button("Reset", action=set($/n, 0), enabled=$/n > 0)', 'flag = Button("Flag", action=toggle($/on), enabled=$/on)', 'off = Button("Off", action=inc($/n), enabled=false)'].join("\n")));
  expect(faults).toEqual([]);
  // A computed test is a field of v, a test that reads one boolean value binds that value, and false is enabled: false.
  expect(definition.ui.children[0].children.map((node) => [node.action, node.enabledBind, node.enabled])).toEqual([["reset", "v.reset_enabled", undefined], ["flag", "on", undefined], ["off", undefined, false]]);
  expect(definition.initialState.v).toEqual({ reset_enabled: false });
  // In a row of free.plan, $done binds the field of the row, and !$done is a field of the view that the run-time computes for each row.
  const free = emit(await planned(testPlan("free"))), template = every(free.definition.ui).find((node) => node.rowTemplate).rowTemplate;
  expect(template.children.slice(2).map((node) => [node.text, node.enabledBind])).toEqual([["Mark", "items_row_2_enabled"], ["Undo", "done"]]);
  const enabled = (state) => state.items.map((row) => [row.done, row.items_row_2_enabled]);
  expect(enabled(free.definition.initialState)).toEqual([[false, true], [true, false], [false, true]]);
  // After the press of Mark in row r1, the test of that row follows.
  expect(enabled(press(free.definition.initialState, free.app, "items_row_2", { key: "r1", value: null }).state)).toEqual([[true, false], [true, false], [false, true]]);
  expect(press(definition.initialState, app, "flag").state).toEqual(definition.initialState);
});

test("emit: a line that the screen shows two times is a fault of the line that shows it the second time", async () => {
  // Each control in the line would have its action id two times, and ToolSpec.parse takes each action id once.
  const counters = ['app("Held", lang="en")', 'need a = counter("A")', 'need b = counter("B")'];
  const tabs = emit(await planned([...counters, 'need pages = tabs(["One", "Two"], [[a], [b]])', "root = Column([a_view])"].join("\n")));
  expect(tabs.definition).toBeNull();
  expect(tabs.faults).toEqual([expect.stringMatching(/^need pages: it shows a_view a second time, after root, /)]);
  const steps = emit(await planned([...counters, 'need wiz = steps(["First", "Second"], [[a], [b]], finish="Done")', "root = Column([b_view])"].join("\n")));
  expect(steps.faults).toEqual([expect.stringMatching(/^need wiz: it shows b_view a second time, after root, /)]);
  // A row of the author's that shows a check of the habits need: the need holds the first place. The third place of more gives no second fault of the same text.
  const habits = emit(await planned(`${plan("section")}\nmore = List($/habits, Row([habits_mon]))\nroot = Column([habits_view, streakrow_view, more, more, more])`));
  expect(habits.faults).toEqual([expect.stringMatching(/^more: it shows habits_mon a second time, after need habits, /), expect.stringMatching(/^root: it shows more a second time, after root, /)]);
  const lists = async (template) => emit(await planned(['app("Rows", lang="en")', '$/rows = [{name: "a", done: false}]', "root = Column([one, two])",
    "one = List($/rows, row)", "two = List($/rows, row)", `row = Row(${template})`, "label = Text($name)", 'mark = CheckBox("Done", $done)'].join("\n")));
  expect((await lists("[label, mark]")).faults).toEqual([expect.stringMatching(/^two: it shows row a second time, after one, /)]);
  // A line with no control has no id, so it may show two times: section.plan shows habits_name in two lists.
  expect((await lists("[label]")).faults).toEqual([]);
});

test("emit: lesson.plan gives a surface, a canvas, and commands in the open reply", async () => {
  // The device test lessonPassesParseAndSmoke sends the same plan through Alphy's validators.
  const { definition, faults, app } = emit(await planned(plan("lesson")));
  expect(faults).toEqual([]);
  valid(definition);
  expect(definition.surfaces).toEqual([{ id: "orbit_view", width: 320, height: 320 }]);
  expect(every(definition.ui).filter((node) => node.type === "canvas")).toEqual([{ type: "canvas", text: "Земля в центре, Луна на орбите и метка её стороны", surfaces: ["orbit_view"] }]);
  // The object anim keeps the change of each value that an animate moves: all 0 while the value stands.
  const run = { type: "object", fields: { from: "number", to: "number", start: "number", end: "number", at: "number" } };
  expect([definition.stateSchema.anim, definition.initialState.anim]).toEqual([{ type: "object", fields: { angle: run } }, { angle: { from: 0, to: 0, start: 0, end: 0, at: 0 } }]);
  // The orbit (the ink role: a muted line is faint), the Earth, its name, the Moon at 40 degrees, and the mark of its side. The guide line shows from step 2 of the lesson.
  expect(open(definition.initialState, app)).toEqual({ state: definition.initialState, timers: [], surfaces: { orbit_view: { commands: [
    { type: "arc", x: 50, y: 50, width: 220, height: 220, start: 0, sweep: 360, stroke: 2, color: "default" },
    { type: "circle", x: 160, y: 160, radius: 34, color: "#38bdf8" },
    { type: "text", x: 160, y: 160, size: 12, text: "Земля", align: "center", baseline: "middle", color: "#ffffff" },
    { type: "circle", x: 244.26, y: 89.29, radius: 18, color: "#e7d3a8" },
    { type: "circle", x: 230.48, y: 100.86, radius: 5, color: "accent" }] } } });
});

test("emit: an animate in an app with no surface, or on a place that is not a number of a state line, is a fault of its line", async () => {
  // Alphy sends a timer event only to an app with a surface, so the value would never move.
  const spin = ['app("Spin", lang="en")', 'need angle = input("number", "Angle", 40, min=0, max=360)', 'need lap = button("One turn", [set($/angle, 0), animate($/angle, 360, 5000)])'];
  const bare = emit(await planned(spin.join("\n")));
  expect(bare.definition).toBeNull();
  expect(bare.faults).toEqual(["need lap: animate moves a value with a timer, and Alphy sends timer events only to an app with a surface. Add a drawing that shows the value"]);
  const odd = emit(await planned([...spin, 'need dial = drawing("Dial", 64, 64, [circle(32, 32, 4, "ink")])', '$/word = "a"', "$/twice := $/angle * 2",
    "root = Column([angle_field, lap_button, dial_view, wordB, twiceB])", 'wordB = Button("Word", action=animate($/word, 5, 100))', 'twiceB = Button("Twice", action=animate($/twice, 5, 100))'].join("\n")));
  expect(odd.faults).toEqual(["wordB: animate moves a number of a state line, such as $/angle, and $/word is not one", "twiceB: animate moves a number of a state line, such as $/angle, and $/twice is not one"]);
});

test("emit: each free form of the free fixture becomes its Alphy node", async () => {
  // The device test eachFreeFormPassesParseAndSmoke sends the same plan through Alphy's validators.
  const { definition, faults } = emit(await planned(testPlan("free")));
  expect(faults).toEqual([]);
  const [plan, tools] = definition.ui.children[0].children;
  // An inline Row as the template of a List, with a field and buttons of its own. A test of the row makes the list a view.
  expect(plan).toEqual({ type: "section", text: "Plan", children: [{ type: "list", text: "Plan", bind: "items", rowTemplate: { type: "row", wrap: false, style: { crossAlign: "center" }, children: [
    { type: "checkbox", text: "Done", bind: "done", action: "items_row_0" }, { type: "text", text: "", bind: "name", layout: { weight: 1 } },
    { type: "button", text: "Mark", action: "items_row_2", enabledBind: "items_row_2_enabled" },
    { type: "button", text: "Undo", action: "items_row_3", enabledBind: "done" }] } }] });
  // move with a test and flip are buttons of an inline Row.
  expect(tools).toEqual({ type: "flowRow", children: [{ type: "button", text: "Hike first", action: "tools_0" },
    { type: "button", text: "Work", action: "tools_1" }, { type: "text", text: "", bind: "d.tools_2_text" }] });
});

test("emit: a plan with no state value keeps one hidden field d0, and each event keeps it (final review 2)", async () => {
  // Alphy keeps 1 to 40 state fields (ToolValueSchema). A static screen and a drawing of fixed shapes have no value of their own.
  for (const text of ['app("T", lang="en")\nneed intro = content("Hello")\nneed note = card("Title", "Text")', 'app("T", lang="en")\nneed d = drawing("D", 320, 240, [circle(10, 10, 5)])']) {
    const { definition, faults, app } = emit(await planned(text));
    expect([faults, definition.stateSchema, definition.initialState]).toEqual([[], { d0: "boolean" }, { d0: false }]);
    expect(handle({ artifactId: "kit-test", revision: 1, state: definition.initialState, event: { type: "open" } }, app).state).toEqual({ d0: false });
  }
});

test("emit: the inner names of the state of a need do not depend on the needs before it (final review 3)", async () => {
  const inner = async (lines) => Object.keys(emit(await planned(['app("T", lang="en")', ...lines].join("\n"))).definition.stateSchema.t2_s.fields);
  const alone = await inner(['need t2 = timer("B", 5, rounds=2)']);
  expect(alone).toContain("on");
  expect(await inner(['need t1 = timer("A", 5, rounds=2)', 'need t2 = timer("B", 5, rounds=2)'])).toEqual(alone);
});

test("emit: a literal text of more than 300 characters is bound, so Alphy shows it in full (final review 11)", async () => {
  // Alphy takes a literal text of 1 to 300 characters, and a bound text up to 2048 in its value (ToolUiCatalog, ToolValueSchema).
  const long = "A long text. ".repeat(33).trim(), { faults, definition } = await compileAppFile(`app("T", lang="en")\nneed intro = content(${JSON.stringify(long)})`);
  expect([long.length, faults]).toEqual([428, []]);
  const shown = every(definition.ui).filter((node) => node.type === "text" && node.bind).map((node) => [node.text, valueAt(definition.initialState, node.bind)]);
  expect(shown).toEqual([["", long]]);
  // A literal text of 300 characters stays a text of its node.
  const edge = "x".repeat(300), short = await compileAppFile(`app("T", lang="en")\nroot = Column([Text(${JSON.stringify(edge)})])`);
  expect(every(short.definition.ui).filter((node) => node.text === edge)).toHaveLength(1);
});

test("emit: each other text, container, choice, and row that Alphy refuses is a fault of its line (final review 11)", async () => {
  const cases = [
    [['root = Column([Text("")])'], "root: a Text needs a text of 1 to 300 characters"],
    [['root = Column([Section("", [Text("a")])])'], "root: a Section needs a title of 1 to 300 characters"],
    [[`root = Column([Text(${JSON.stringify("x".repeat(2049))})])`], "root: the text has 2049 characters, and Alphy keeps at most 2048"],
    [[`root = Column([Section(${JSON.stringify("x".repeat(301))}, [Text("a")])])`], "root: it shows a text of 301 characters here, and Alphy shows at most 300"],
    [['root = Column([Text("a"), Column([])])'], "root: this container has no children, and Alphy keeps 1 to 50"],
    [["root = Column([Card([])])"], "root: this container has no children"],
    [['need setup = steps(["A", "B"], [[a], []])', 'need a = input("number", "A", 1)'], "need setup: this container has no children"],
    [['$/c = "z"', 'root = Column([Segmented("C", $/c, ["a", "b"])])'], 'root: the value "z" of this Segmented is not one of its options'],
    [['$/c = "a"', 'root = Column([Segmented("C", $/c, [1, 2])])'], "root: the option 1 is not a string"],
    [['$/c = "a"', 'root = Column([Segmented("C", $/c, [])])'], "root: a choice needs 1 to 30 options, and this one has none"],
    [['$/c = "o0"', `root = Column([Select("C", $/c, [${Array.from({ length: 31 }, (_, i) => `"o${i}"`).join(", ")}])])`], 'root: the option "o30" is one more than the 30 options'],
    [['$/c = "a"', `root = Column([Segmented("C", $/c, {a: ${JSON.stringify("y".repeat(101))}, b: "B"})])`], 'root: the option "a" has a text of 101 characters'],
    [['$/c = "a"', 'root = Column([Segmented("C", $/c, ["a", "a", "b"])])'], 'root: the option "a" is there two times'],
    [['$/rows = [{id: "a", n: 1}, {id: "a", n: 2}]', "root = Column([List($/rows, Text(text($n)))])"], '$/rows: a row of $/rows has the id "a"'],
  ];
  for (const [lines, part] of cases) {
    const faults = await faultsOf(...lines);
    expect({ lines, found: faults.some((fault) => fault.startsWith(part)) }, faults.join(" | ")).toEqual({ lines, found: true });
  }
});
