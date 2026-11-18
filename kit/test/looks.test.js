// The look options of the components: each one as the property of Alphy's node, and each unknown value as a fault of its line.

import { expect, test } from "bun:test";
import { report } from "../src/check.js";
import { compileAppFile } from "../src/compile.js";
import { App } from "../src/core.js";
import { emit } from "../src/emit.js";
import { handle } from "../src/run.js";
import { every, planned } from "./helpers.js";

/** The children of the app's column of a plan that the emitter builds with no fault. */
const shown = async (...lines) => {
  const { definition, faults } = emit(await planned(['app("Look", lang="en")', ...lines].join("\n")));
  expect(faults).toEqual([]);
  return definition.ui.children[0].children;
};
/** The faults of the report of a plan. */
const faultsOf = async (...lines) => report(await planned(['app("Look", lang="en")', ...lines].join("\n"))).faults;

test("look: the column of the app has a medium gap and a medium padding", async () => {
  const { definition } = emit(await planned(['app("Pad", lang="en")', 'root = Column([Text("Hi")])'].join("\n")));
  expect(definition.ui.children).toEqual([{ type: "column", style: { gap: "medium", padding: "medium" }, children: [{ type: "text", text: "Hi" }] }]);
});

test("look: an Emoji has the size of the plan, from a sign, a state value, and a field of a row", async () => {
  const [sign, state, plain, list] = await shown('$/sign = "💧"', '$/rows = [{name: "Milk", emoji: "🥛"}]',
    'root = Column([Emoji("🛒", size="large"), Emoji($/sign, size="medium"), Emoji("🍵"), list])', 'list = List($/rows, Row([Emoji($emoji, size="small"), Text($name)]))');
  expect([sign, state, plain]).toEqual([
    { type: "emoji", text: "🛒", value: "🛒", size: "large" }, { type: "emoji", text: "💧", bind: "sign", size: "medium" }, { type: "emoji", text: "🍵", value: "🍵" }]);
  // One template draws each row, so its label is a fixed text: the kit's name of its line.
  expect(list.rowTemplate.children[0]).toEqual({ type: "emoji", text: "list Emoji", bind: "emoji", size: "small" });
});

test("look: a Button has the variant of the plan, else none, and selected= keeps filled and tonal", async () => {
  const buttons = await shown("$/n = 0", "$/on = true", "root = Column([plain, outlined, picked, unpicked, both])", 'plain = Button("Plain", inc($/n))',
    'outlined = Button("Outlined", inc($/n), variant="outlined")', 'picked = Button("Picked", inc($/n), selected=$/on)',
    'unpicked = Button("Unpicked", inc($/n), selected=!$/on)', 'both = Button("Both", inc($/n), selected=!$/on, variant="text")');
  // With no variant, the host's own rule decides: one button of a group is filled, and its unstyled peers are outlined.
  expect(buttons.map((node) => node.variant)).toEqual([undefined, "outlined", "filled", "tonal", "text"]);
  expect(buttons[0]).not.toHaveProperty("variant");
});

test("look: a Text has the emphasis, the alignment, and the tone of the plan, and a Metric, a Badge, and a Notice have a tone", async () => {
  const [big, center, quiet, eyebrow, figure, badge, notice] = await shown("$/n = 5", "root = Column([big, center, quiet, eyebrow, figure, badge, notice])",
    'big = Text("Big", emphasis="display")', 'center = Text("Mid", align="center", emphasis="headline")', 'quiet = Text("Quiet", variant="muted")',
    'eyebrow = Text("Top", variant="eyebrow", tone="success")', 'figure = Metric("Sum", $/n, emphasis="number", tone="accent")',
    'badge = Badge("New", tone="warning")', 'notice = Notice("Saved", tone="success")');
  expect([big, center, quiet, eyebrow, figure, badge, notice]).toEqual([
    { type: "text", text: "Big", style: { emphasis: "display" } },
    { type: "text", text: "Mid", style: { emphasis: "headline", align: "center" } },
    { type: "text", text: "Quiet", style: { tone: "muted" } },
    // A tone of the plan wins over the tone of the eyebrow.
    { type: "text", text: "Top", style: { emphasis: "label", tone: "success" } },
    { type: "metric", text: "Sum", bind: "n", style: { emphasis: "number", tone: "accent" } },
    { type: "badge", text: "New", style: { tone: "warning" } },
    { type: "banner", style: { tone: "success" }, children: [{ type: "text", text: "Saved" }] },
  ]);
});

test("look: a Row of no wrap is centered across its height, align= sets it, and gap= sets the gap of a Row and a Column", async () => {
  const [even, start, flow, ended, tight] = await shown("root = Column([even, start, flow, ended, tight])", 'even = Row([Text("A", weight=1), Text("B")])',
    'start = Row([Text("A", weight=1), Text("B")], align="start", gap="small")', 'flow = Row([Text("A"), Text("B")])',
    'ended = Row([Text("A"), Text("B")], align="end", gap="large")', 'tight = Column([Text("A"), Text("B")], gap="none")');
  expect([even, start, flow, ended, tight].map(({ type, wrap, style }) => ({ type, wrap, style }))).toEqual([
    { type: "row", wrap: false, style: { crossAlign: "center" } },
    { type: "row", wrap: false, style: { gap: "small", crossAlign: "start" } },
    { type: "flowRow", wrap: undefined, style: undefined },
    { type: "flowRow", wrap: undefined, style: { gap: "large", crossAlign: "end" } },
    { type: "column", wrap: undefined, style: { gap: "none" } },
  ]);
});

test("look: a Grid holds its children in 2 columns, or in the columns of the plan", async () => {
  const [two, three] = await shown("root = Column([two, three])", 'two = Grid([Text("A"), Text("B")])', 'three = Grid([Text("A"), Text("B"), Text("C")], columns=3)');
  expect(two).toEqual({ type: "grid", columns: 2, children: [{ type: "text", text: "A" }, { type: "text", text: "B" }] });
  expect([three.type, three.columns, three.children.length]).toEqual(["grid", 3, 3]);
});

test("look: a CheckBox is a circle with variant=, and takes a weight in a row", async () => {
  const [days] = await shown("$/mon = false", "$/tue = true", "root = Column([days])",
    'days = Row([CheckBox("Mon", $/mon, variant="circle", weight=1), CheckBox("Tue", $/tue, variant="circle", weight=1)], gap="none")');
  expect(days).toEqual({ type: "row", wrap: false, style: { gap: "none", crossAlign: "center" }, children: [
    { type: "checkbox", text: "Mon", bind: "mon", action: "days_0", variant: "circle", layout: { weight: 1 } },
    { type: "checkbox", text: "Tue", bind: "tue", action: "days_1", variant: "circle", layout: { weight: 1 } }] });
});

test("look: the value inside a ring is a display text, and the progress part shows its title one time", async () => {
  const [ring] = await shown("$/done = 2", "root = Column([ring])", 'ring = Ring($/done, 3, text($/done) + " / 3", label="Done")');
  expect(ring.children).toEqual([{ type: "text", text: "", bind: "d.ring_text", style: { emphasis: "display" } }]);
  // The host shows the caption of the ring under it, so the hero card holds no heading of the same title.
  const { definition, faults } = emit(await planned(['app("Habits", lang="en")', "$/n = 1", 'need done = progress("Done today", $/n, 3)'].join("\n")));
  expect(faults).toEqual([]);
  const card = definition.ui.children[0].children[0];
  expect([card.type, card.variant, card.children.map((node) => node.type)]).toEqual(["card", "hero", ["progressRing"]]);
  expect(every(definition.ui).filter((node) => node.text === "Done today").map((node) => node.type)).toEqual(["progressRing"]);
});

test("look: icon= gives a button, a metric, a badge, a notice, a card, and the fields their icon, in Alphy's names", async () => {
  const [add, up, sum, badge, notice, card, nameField, countField, many, few, dayField, timeField] = await shown(
    "$/n = 0", '$/name = ""', '$/when = "2026-11-20"', '$/at = "08:00"', '$/pick = "a"',
    "root = Column([add, up, sum, badge, notice, card, nameField, countField, many, few, dayField, timeField])",
    'add = Button("Add", inc($/n), variant="filled", icon="Plus")', 'up = Button("Up", inc($/n), icon="ArrowUp")', 'sum = Metric("Sum", $/n, icon="Drop")',
    'badge = Badge("New", icon="Star")', 'notice = Notice("Saved", icon="Trophy")', 'card = Card([Text("Inside")], icon="Layers")',
    'nameField = TextField("Name", $/name, icon="Cart")', 'countField = NumberField("Count", $/n, icon="Money")',
    'many = Select("Many", $/pick, ["a", "b", "c", "d", "e", "f", "g"], icon="Tag")', 'few = Select("Few", $/pick, ["a", "b"], icon="Tag")',
    'dayField = DateField("Day", $/when, icon="Calendar")', 'timeField = TimeField("At", $/at, icon="Clock")');
  expect([add, up, sum, badge, notice, card, nameField, countField, many, dayField, timeField].map((node) => [node.type, node.icon])).toEqual([
    ["button", "Plus"], ["button", "Arrow"], ["metric", "Drop"], ["badge", "Star"], ["banner", "Trophy"], ["card", "Layers"],
    ["input", "Cart"], ["numberInput", "Money"], ["select", "Tag"], ["dateInput", "Calendar"], ["timeInput", "Clock"]]);
  // A Select of six options or fewer is Alphy's segmented control, which shows no icon.
  expect([few.type, few.icon]).toEqual(["segmented", undefined]);
});

test("look: an icon that Alphy does not have, and an icon of a component that takes none, are faults of the line", async () => {
  const faults = await faultsOf("$/n = 0", "root = Column([a, b, c, d, e])", 'a = Button("x", inc($/n), icon="Rocketship")', 'b = IconButton("Bin", inc($/n), "Remove")',
    'c = Text("x", icon="Plus")', 'd = IconButton("Edit", inc($/n), "Edit")', 'e = Metric("x", $/n, icon=$/n > 0 ? "Check" : "Clock")');
  expect(faults).toEqual([
    'a: the icon of a Button is the name of an icon of Alphy, such as Plus, Check, Trash, Pencil, Star, or Heart, and "Rocketship" is not one',
    'b: the icon of an IconButton is the name of an icon of Alphy, such as Plus, Check, Trash, Pencil, Star, or Heart, and "Bin" is not one',
    "c: Text has no property icon",
  ]);
});

test("look: options as a map of a value to its label: the state keeps the value, and the control shows the label, in the order of the plan", async () => {
  const text = ['app("Bikes", lang="en")', '$/order = "asc"', '$/kind = "road"', '$/tags = ["b"]', "root = Column([order, kind, tags, shown])",
    'order = Segmented("Order", $/order, {asc: "Low to high", desc: "High to low"})',
    'kind = Select("Type", $/kind, {road: "Road", city: "City", mtb: "Mountain", kids: "Kids", gravel: "Gravel", cargo: "Cargo", fold: "Folding"})',
    'tags = Choices("Tags", $/tags, {b: "Bravo", a: "Alpha"})', 'shown = Text($/order + " " + $/kind + " " + join($/tags))'].join("\n");
  const { definition, faults, app } = emit(await planned(text));
  expect(faults).toEqual([]);
  const [order, kind, tags] = definition.ui.children[0].children;
  expect(order.options).toEqual([{ text: "Low to high", value: "asc" }, { text: "High to low", value: "desc" }]);
  expect([kind.type, kind.options.map((option) => option.value).join(), kind.options[2]]).toEqual(["select", "road,city,mtb,kids,gravel,cargo,fold", { text: "Mountain", value: "mtb" }]);
  expect(tags.options).toEqual([{ text: "Bravo", value: "b" }, { text: "Alpha", value: "a" }]);
  const picked = handle({ artifactId: "kit-test", revision: 1, state: definition.initialState, event: { type: "action", actionId: order.action, value: "desc" } }, app);
  expect(picked.state.order).toBe("desc");
  // The kit's own on-off buttons of a Choices show the labels too, and the smoke run picks a value of the map.
  expect(new App(await planned(text)).find("tags").children.map((button) => button.props.label)).toEqual(["Bravo", "Alpha"]);
  expect((await compileAppFile(text)).faults).toEqual([]);
});

test("look: a map of options with a key that is a number is a fault of the line, and options of numbers stay a list", async () => {
  const faults = await faultsOf("$/n = 1", "root = Column([a, b])", 'a = Segmented("N", $/n, {"1": "One", "2": "Two"})', 'b = Select("M", $/n, [1, 2, 3])');
  expect(faults).toEqual(['a: a map of options keeps the order of the plan only with keys that are not numbers, and "1" is a number. Give options of numbers as a list']);
});

test("look: an unknown value of a look option, and an option that the component does not take, are faults of the line", async () => {
  const faults = await faultsOf("$/n = 0", "$/b = false", '$/t = "muted"', "root = Column([a, b, c, d, e, f, g, h, i, j, k, l])",
    'a = Text("x", emphasis="huge")', 'b = Text("x", size="large")', 'c = Emoji("🍵", size="tiny")', 'd = Button("x", inc($/n), variant="fab")',
    'e = Row([Text("x")], align="top")', 'f = Column([Text("x")], gap="wide")', 'g = Grid([Text("x")], columns=5)', 'h = Metric("x", $/n, emphasis="title")',
    'i = Notice("x", tone="info")', 'j = CheckBox("x", $/b, variant="square")', "k = Text(\"x\", tone=$/t)", 'l = Text("x", tone=$/b ? "success" : "error")');
  expect(faults).toEqual([
    'a: the emphasis of a Text is one of body, title, headline, label, number, display, and "huge" is not one',
    "b: Text has no property size",
    'c: the size of an Emoji is one of small, medium, large, and "tiny" is not one',
    'd: the variant of a Button is one of filled, outlined, text, tonal, and "fab" is not one',
    'e: the align of a Row is one of start, center, end, and "top" is not one',
    'f: the gap of a Column is one of none, small, medium, large, and "wide" is not one',
    "g: the columns of a Grid is one of 1, 2, 3, 4, and 5 is not one",
    'h: the emphasis of a Metric is one of display, number, and "title" is not one',
    'i: the tone of a Notice is one of muted, accent, success, warning, error, and "info" is not one',
    'j: the variant of a CheckBox is one of circle, and "square" is not one',
    'k: the tone of a Text is a value in the line, or a choice of values such as $/on ? "a" : "b"',
  ]);
});
