// The emitter: an expanded app file as Alphy's app definition.

import { expect, test } from "bun:test";
import { emit } from "../src/emit.js";
import { plan, planned } from "./helpers.js";

/** Alphy's rule for a state field and an action id (ToolValueSchema.name, ToolUiCatalog). */
const SAFE = /^[A-Za-z][A-Za-z0-9_]{0,47}$/;
const REFUSED = new Set(["__proto__", "prototype", "constructor"]);
const nodes = (node) => [node, ...(node.children ?? []).flatMap(nodes)];

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
  ].join("\n")));
  expect(faults).toEqual([]);
  const schema = definition.stateSchema;
  const fields = Object.keys(schema).flatMap((key) => [key, ...Object.keys(schema[key].fields ?? {})]);
  const actions = nodes(definition.ui).filter((node) => node.action).map((node) => node.action);
  expect(actions).toHaveLength(3);
  for (const name of [...fields, ...actions]) expect(SAFE.test(name) && !REFUSED.has(name)).toBe(true);
  expect(new Set([...fields, ...actions]).size).toBe(fields.length + actions.length);
  // The compiled data maps each safe name back to its kit name, and each kit name to its safe name.
  for (const name of actions) expect(app.names.safe[app.names.kit[name]]).toBe(name);
});

test("emit: a plan over a limit is a fault of its need line", async () => {
  const needs = Array.from({ length: 41 }, (_, index) => `need x${index + 1} = input("number", "X ${index + 1}", 1, min=0, max=10)`);
  const { definition, faults } = emit(await planned(['app("Many", lang="en")', ...needs].join("\n")));
  expect(definition).toBeNull();
  expect(faults).toHaveLength(1);
  expect(faults[0]).toStartWith("need x41: ");
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
  const { definition, faults } = emit(await planned([
    'app("All", lang="en", preset="soft", kind="Lesson")',
    '$/name = "Ann"', "$/on = true", '$/size = "S"', "$/level = 3", "$/count = 0",
    "root = Column([title, intro, note, part, line, card, badge, emoji, rule, notice, nameField, onToggle, onBox, sizePick, sizeList, levelSlider, countField, add, up])",
    'title = Heading("Hello")', 'intro = Text("Hi " + $/name, variant="eyebrow")', 'note = Text("Plain", variant="muted")',
    'part = Section("Part", [Text("Inside")])', "line = Row([Text($/name, weight=1), Badge($/count)])",
    'card = Card([Metric("Count", $/count, emphasis="display")], variant="hero")', 'badge = Badge("New")', 'emoji = Emoji("🍵")', "rule = Divider()",
    'notice = Notice("Saved")', 'nameField = TextField("Name", $/name)', 'onToggle = Toggle("On", $/on)', 'onBox = CheckBox("Box", $/on)',
    'sizePick = Segmented("Size", $/size, ["S", "M", "L"])', 'sizeList = Select("Size", $/size, ["S", "M", "L", "XL", "2XL", "3XL", "4XL"])',
    'levelSlider = Slider("Level", $/level, min=0, max=10, step=1)', 'countField = NumberField("Count", $/count)',
    'add = Button("Add", action=inc($/count), selected=!$/on)', 'up = IconButton("ArrowUp", action=inc($/level))',
  ].join("\n")));
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
    { type: "select", text: "Size", bind: "size", action: "sizeList", options: ["S", "M", "L", "XL", "2XL", "3XL", "4XL"].map((value) => ({ text: value, value })) },
    { type: "slider", text: "Level", bind: "level", action: "levelSlider", min: 0, max: 10, step: 1 },
    { type: "numberInput", text: "Count", bind: "count", action: "countField", min: 0, max: 1000000 },
    { type: "button", text: "Add", action: "add", variant: "tonal" },
    { type: "icon", name: "Arrow", text: "up", action: "up" },
  ]);
  expect(definition.initialState.d).toEqual({ intro_text: "Hi Ann" });
});
