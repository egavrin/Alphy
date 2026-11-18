// The emitter: an expanded app file as Alphy's app definition.

import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
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
