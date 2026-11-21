// The template file (stage templates plan, Task 1): the kit reads, checks, fixes, expands, and merges named lines, through compileAppFile and compileStep.

import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { compileAppFile, compileStep } from "../src/compile.js";
import { isTemplateFile } from "../src/template.js";
import { parseExpression, parseLine } from "../src/dsl.js";
import { TONES } from "../src/core.js";
import { plansIn } from "../build.js";
import { every } from "./helpers.js";

const template = (name) => readFileSync(new URL(`./fixtures/template/${name}.plan`, import.meta.url), "utf8");
const ship = template("ship");
/** The text with the line NAME in place of the line of that name, or after the last line when the text has none. */
const put = (text, name, line) => (new RegExp(`^${name} = .*$`, "m").test(text) ? text.replace(new RegExp(`^${name} = .*$`, "m"), line) : text + line + "\n");
const drop = (text, name) => text.replace(new RegExp(`^${name} = .*\\n`, "m"), "");
const named = (faults, name) => faults.filter((fault) => fault.startsWith(`${name}: `));
const step = (input) => compileStep({ read: true, previous: null, cap: 65536, ...input });
/** A text of n characters. */
const chars = (n) => "Ш".repeat(n);
/** The lines of a merged text by their names: the template word for the first line. */
const order = (text) => text.trim().split("\n").map((line) => /^([\w$/]+)\s*[=(]/.exec(line)[1]);
/** The value of a stored app as the host sends it to compileStep. */
const stored = (appFile, definition, state = definition.initialState) => ({ appFile, stateSchema: JSON.stringify(definition.stateSchema), state: JSON.stringify(state) });

test("theThreeExamplesCompileClean", async () => {
  for (const [name, word] of [["ship", "scene"], ["word-parts", "items"], ["majority", "cells"]]) {
    const text = template(name), result = await compileAppFile(text);
    expect([name, result.faults, result.template, result.leftOut]).toEqual([name, [], word, []]);
    expect(result.definition).not.toBeNull();
    expect(result.kept).toBe(text);
    expect(result.definition.surfaces.map(({ width, height }) => [width, height])).toEqual([[300, 220]]);
  }
});

test("aShapeWithAFaultIsLeftOutAndTheOtherLinesStay", async () => {
  const text = put(ship, "s2", 's2 = polygon(["a", 75, 255, 75, 225, 140, 75, 140], "ink", visible=$/v == "Корабль")'), result = await compileAppFile(text);
  expect(result.faults).toHaveLength(1);
  expect(result.faults[0]).toStartWith("s2: ");
  // The definition is the one of the ship with the shapes s1 and s3 only.
  const without = await compileAppFile(drop(ship, "s2"));
  expect(without.faults).toEqual([]);
  expect(result.definition).toEqual(without.definition);
  expect([result.kept, result.leftOut]).toEqual([drop(ship, "s2"), ["s2"]]);
});

const STILL = ['scene("Круг", lang="ru")', 'control = slider("Размер", 0, 10, 1, 4)', 's1 = circle(150, 110, 40, "accent")', 'card = card("Круг", "Круг всегда один и тот же.")'].join("\n") + "\n";

test("aControlThatChangesNothingIsLeftOut", async () => {
  const result = await compileAppFile(STILL);
  expect(result.faults).toHaveLength(1);
  expect(result.faults[0]).toStartWith("control: ");
  expect(result.faults[0]).toContain("changes nothing");
  expect(every(result.definition.ui).filter((node) => node.type === "slider")).toEqual([]);
  // The one value of the app is $/v at the start of the slider.
  expect(Object.values(result.definition.initialState)).toEqual([4]);
  expect(result.kept).toBe(put(STILL, "control", "$/v = 4"));
});

test("keptCompilesCleanAgain", async () => {
  const cases = { shape: put(ship, "s2", "s2 = polygon(45, 75, 255)"), card: put(ship, "card", `card = card("Вытеснение воды", "${chars(176)}")`), control: STILL };
  for (const [name, text] of Object.entries(cases)) {
    const first = await compileAppFile(text), again = await compileAppFile(first.kept);
    expect([name, first.faults.length > 0, first.definition !== null, again.faults]).toEqual([name, true, true, []]);
    expect(again.definition).toEqual(first.definition);
  }
});

test("aKitFaultOfTheExpansionNamesItsLine", async () => {
  // Three numbers and no list: the kit refuses the shape when it draws the picture, and the fault names the line s2.
  const { faults } = await compileAppFile(put(ship, "s2", "s2 = polygon(45, 75, 255)"));
  expect(faults).toHaveLength(1);
  expect(faults[0]).toStartWith("s2: ");
  expect(faults[0]).not.toContain("need ");
});

test("wholeFaults", async () => {
  const palette = await compileAppFile(ship.replace(/^scene\(.*$/m, 'scene("Почему корабль не тонет?", lang="ru", palette="sky")'));
  expect(named(palette.faults, "scene")).toHaveLength(1);
  expect(palette.definition).toBeNull();
  let shapes = ship;
  for (const name of ["s1", "s2", "s3"]) shapes = put(shapes, name, `${name} = circle(150, 110, 10, "sky")`);
  expect((await compileAppFile(shapes)).definition).toBeNull();
  let items = template("word-parts");
  for (const name of ["i1", "i2", "i3", "i4"]) items = put(items, name, `${name} = item("Часть", "-")`);
  expect((await compileAppFile(items)).definition).toBeNull();
  const cells = await compileAppFile(put(template("majority"), "control", 'control = slider("Votes for", 0, 20, 2, 11)'));
  expect(named(cells.faults, "control")).toHaveLength(1);
  expect(cells.definition).toBeNull();
});

test("colorNamesNeedNoCall", async () => {
  const text = ['scene("Цвета", lang="ru")', 'control = slider("Сдвиг", 0, 40, 10, 0)', 's1 = circle(40 + $/v, 60, 20, "orange")', 's2 = rect(100, 40, 40, 40, "Green")',
    's3 = line(160, 40, 200, 80, "grey", 3)', 's4 = text(240, 60, "Orange", "black", 14)', 's5 = polygon([20, 200, 80, 200, 50, 150], "white")'].join("\n") + "\n";
  const result = await compileAppFile(text);
  expect(result.faults).toEqual([]);
  const picture = result.expanded.split("\n").find((line) => line.startsWith("need picture"));
  const shapes = parseLine(picture).tree.args[3].items, at = { circle: 3, rect: 4, line: 4, text: 3, polygon: 1 };
  expect(shapes.map((shape) => shape.args[at[shape.name]].v)).toEqual(["#ea580c", "#16a34a", "#64748b", "ink", "surface"]);
  // A color name is a color only as the color of a shape: the text of a text shape stays.
  expect(shapes[3].args[2].v).toBe("Orange");
  // Only a value of the color is a color: a text that a choice of colors compares stays.
  expect((await compileAppFile(put(ship, "s1", 's1 = rect(0, 110, 300, 110, $/v == "Корабль" ? "water" : "muted", 0)'))).faults).toEqual([]);
  const lamp = await compileAppFile(['scene("Свет", lang="ru")', 'control = choice("Сигнал", ["Red", "Blue"])', 's1 = circle(150, 110, 40, $/v == "Red" ? "red" : "blue")'].join("\n") + "\n");
  const color = parseLine(lamp.expanded.split("\n").find((line) => line.startsWith("need picture"))).tree.args[3].items[0].args[3];
  expect([lamp.faults, color.test.right.v, color.yes.v, color.no.v]).toEqual([[], "Red", "#dc2626", "#2563eb"]);
  const sky = await compileAppFile(put(text, "s2", 's2 = rect(100, 40, 40, 40, "sky")'));
  expect(sky.faults).toHaveLength(1);
  expect(sky.faults[0]).toStartWith("s2: ");
  expect(sky.faults[0]).toContain(Object.keys(TONES).join(", "));
});

test("textsUpToOneAndAQuarterOfTheirCaps", async () => {
  const items = template("word-parts"), cells = template("majority");
  const head = (title) => ship.replace(/^scene\(.*$/m, `scene("${title}", lang="ru", palette="blue")`);
  const pairs = [
    ["scene", 30, (n) => head(chars(n)), 37],
    ["card", 140, (n) => put(ship, "card", `card = card("Вытеснение воды", "${chars(n)}")`), 175],
    ["control", 12, (n) => put(ship, "control", `control = choice("Предмет", ["Корабль", "${chars(n)}"])`), 15],
    ["i1", 14, (n) => put(items, "i1", `i1 = item("Приставка", "${chars(n)}", "Стоит перед корнем.")`), 17],
    ["s3", 28, (n) => put(ship, "s3", `s3 = text(150, 50, "${chars(n)}", "ink", 12, visible=$/v == "Корабль")`), 35],
  ];
  for (const [name, cap, text, most] of pairs) {
    expect([name, most, (await compileAppFile(text(most))).faults]).toEqual([name, most, []]);
    const faults = named((await compileAppFile(text(most + 1))).faults, name);
    expect([name, faults.length]).toEqual([name, 1]);
    expect(faults[0]).toMatch(new RegExp(`\\b${cap}\\b`));
  }
  // A list cap has no factor.
  const options = Array.from({ length: 6 }, (_, index) => `"Вещь ${index + 1}"`);
  expect(named((await compileAppFile(put(ship, "control", `control = choice("Предмет", ["Корабль", ${options.join(", ")}])`))).faults, "control")).toHaveLength(1);
  const seven = put(put(put(items, "i5", 'i5 = item("Основа", "ход", "Слово без окончания.")'), "i6", 'i6 = item("Постфикс", "-ся", "Стоит после окончания.")'), "i7", 'i7 = item("Нуль", "∅", "Окончание без звуков.")');
  expect(named((await compileAppFile(seven)).faults, "i7")).toHaveLength(1);
  let thirteen = ship;
  for (let index = 4; index <= 13; index++) thirteen = put(thirteen, `s${index}`, `s${index} = circle(${index * 20}, 200, 5, "ink")`);
  expect(named((await compileAppFile(thirteen)).faults, "s13")).toHaveLength(1);
  expect(named((await compileAppFile(put(cells, "control", 'control = slider("Votes for", 0, 101, 1, 11)'))).faults, "control")).toHaveLength(1);
});

test("aPartReadsOnlyTheControlValue", async () => {
  const { faults } = await compileAppFile(put(ship, "s1", 's1 = rect(0, 110, 300, $/x, "water", 0)'));
  expect(faults).toHaveLength(1);
  expect(faults[0]).toStartWith("s1: ");
});

test("namesAndOrder", async () => {
  const clean = await compileAppFile(ship);
  expect(named((await compileAppFile(put(ship, "x1", 'x1 = rect(0, 0, 10, 10, "ink")'))).faults, "x1")).toHaveLength(1);
  const twice = await compileAppFile(ship + 's1 = circle(150, 110, 30, "sun")\n');
  expect(named(twice.faults, "s1")).toHaveLength(1);
  // The first line s1 stays.
  expect(twice.definition).toEqual(clean.definition);
  const lines = ship.trim().split("\n"), cardFirst = [lines[0], lines[1], lines[5], lines[2], lines[3], lines[4]].join("\n") + "\n";
  const turned = await compileAppFile(cardFirst);
  expect(turned.faults).toEqual([]);
  expect(turned.definition).toEqual(clean.definition);
});

test("aRepairReplyMergesByName", async () => {
  const fixed = ship.split("\n").find((line) => line.startsWith("s2 = "));
  const repaired = await compileStep({ text: fixed, plan: put(ship, "s2", "s2 = polygon(45, 75, 255)"), read: true });
  expect([repaired.kind, repaired.text, repaired.result.faults]).toEqual(["plan", ship, []]);
});

test("aChangeReplyMergesAndDeletes", async () => {
  const reply = ['s4 = circle(150, 160, 20, "sun", visible=$/v == "Гвоздь")', "delete s3", 'card = card("Почему", "Вода держит полый корабль.")'].join("\n");
  const changed = await step({ text: reply, plan: ship });
  expect(order(changed.text)).toEqual(["scene", "control", "s1", "s2", "s4", "card"]);
  expect(changed.result.faults).toEqual([]);
  const head = 'scene("Корабль и гвоздь", lang="ru", palette="teal")', titled = await step({ text: head, plan: ship });
  expect(titled.text).toBe(ship.replace(/^scene\(.*$/m, head));
});

test("aFaultyChangedLineKeepsItsStoredVersion", async () => {
  const { definition } = await compileAppFile(ship);
  const changed = await step({ text: "s2 = polygon(45, 75, 255)", plan: ship, previous: stored(ship, definition) });
  expect(changed.result.kept).toBe(ship);
  expect(changed.result.faults).toHaveLength(1);
  expect(changed.result.faults[0]).toStartWith("s2: ");
  expect(changed.result.definition).toEqual(definition);
});

test("aTemplatePreviousKeepsAMovedValue", async () => {
  const first = await compileAppFile(ship), field = Object.keys(first.definition.initialState).find((key) => first.definition.initialState[key] === "Корабль");
  const previous = { appFile: ship, stateSchema: first.definition.stateSchema, state: { ...first.definition.initialState, [field]: "Гвоздь" } };
  const changed = await compileAppFile(ship + 's4 = circle(150, 180, 15, "sun", visible=$/v == "Гвоздь")\n', previous);
  expect([changed.faults, changed.state[field]]).toEqual([[], "Гвоздь"]);
  const expanded = await compileAppFile(first.expanded, previous);
  expect([expanded.faults, expanded.state[field]]).toEqual([[], "Гвоздь"]);
});

test("outsideStillReads", async () => {
  expect(await step({ text: 'outside("other")', plan: null })).toEqual({ kind: "outside", word: "other" });
  expect(await step({ text: '```\noutside("other")\n```', plan: ship })).toEqual({ kind: "outside", word: "other" });
});

const len = (node) => [...node.v].length;
test("theCapFixturesCompileClean", async () => {
  for (const [name, factor] of [["scene-caps", 1], ["items-caps", 1], ["cells-caps", 1], ["scene-caps125", 1.25], ["items-caps125", 1.25], ["cells-caps125", 1.25]]) {
    const text = template(name), cap = (n) => Math.floor(n * factor), result = await compileAppFile(text);
    expect([name, result.faults]).toEqual([name, []]);
    const [first, ...rest] = text.trim().split("\n"), head = parseExpression(first), lines = Object.fromEntries(rest.map((line) => [parseLine(line).key, parseLine(line).tree]));
    const parts = Object.keys(lines).filter((key) => /^[si]\d+$/.test(key)).map((key) => lines[key]), control = lines.control, card = lines.card;
    const seen = { title: len(head.args[0]), control: len(control.args[0]), cardTitle: len(card.args[0]) }, want = { title: cap(30), control: cap(28), cardTitle: cap(32) };
    if (head.name === "scene") {
      Object.assign(seen, { options: control.args[1].items.map(len), shapes: parts.length, texts: parts.filter((part) => part.name === "text").map((part) => len(part.args[2])), cardText: len(card.args[1]) });
      Object.assign(want, { options: Array(6).fill(cap(12)), shapes: 12, texts: Array(seen.texts.length).fill(cap(28)), cardText: cap(140) });
      expect(seen.texts.length).toBeGreaterThan(0);
    } else if (head.name === "items") {
      Object.assign(seen, { items: parts.map((part) => part.args.map(len)) });
      Object.assign(want, { items: Array(6).fill([cap(12), cap(14), cap(140)]) });
    } else {
      Object.assign(seen, { count: control.args[2].v, cardText: len(card.args[1]) });
      Object.assign(want, { count: 100, cardText: cap(140) });
    }
    expect([name, seen]).toEqual([name, want]);
  }
});

test("aFreeAppFileIsNotATemplate", () => {
  const plans = plansIn(fileURLToPath(new URL(".", import.meta.url)));
  expect(plans.length).toBeGreaterThan(5);
  expect(plans.filter((path) => isTemplateFile(readFileSync(path, "utf8")))).toEqual([]);
  expect(["ship", "word-parts", "majority"].every((name) => isTemplateFile(template(name)))).toBe(true);
});
