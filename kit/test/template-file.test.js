// The template file (stage templates plan, Task 1): the kit reads, checks, fixes, expands, and merges named lines, through compileAppFile and compileStep.

import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { compileAppFile, compileStep } from "../src/compile.js";
import { isTemplateFile, mergeTemplate } from "../src/template.js";
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
/** A text of n characters that ends a sentence. */
const sentence = (n) => chars(n - 1) + ".";
/** A caption of 200 characters with a sentence end at character 100, and the 100 characters that the kit keeps of it (cap 110). */
const LONG = `${sentence(100)} ${chars(99)}`, CUT = sentence(100);
const shipCaption = (text) => put(ship, "caption", `caption = "${text}"`);
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
    expect(result.definition.surfaces.map(({ width, height }) => [width, height])).toEqual([[300, 300]]);
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

const STILL = ['scene("Круг", lang="ru")', 'control = slider("Размер", 0, 10, 1, 4)', 's1 = circle(150, 110, 40, "accent")', 'caption = "Круг всегда один и тот же."'].join("\n") + "\n";

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
  // A cut text has no fault: the kept text is the cut one.
  const cases = { shape: [put(ship, "s2", "s2 = polygon(45, 75, 255)"), true], caption: [shipCaption(chars(176)), true], control: [STILL, true], cut: [shipCaption(LONG), false] };
  for (const [name, [text, faulty]] of Object.entries(cases)) {
    const first = await compileAppFile(text), again = await compileAppFile(first.kept);
    expect([name, first.faults.length > 0, first.definition !== null, again.faults]).toEqual([name, faulty, true, []]);
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

test("hardCapsOutsideThePictureAndOneAndAQuarterInside", async () => {
  const items = template("word-parts"), cells = template("majority");
  const head = (title) => ship.replace(/^scene\(.*$/m, `scene("${title}", lang="ru", palette="blue")`);
  // A text outside the picture changes the height of the screen, so its cap has no factor.
  const pairs = [
    ["scene", 30, (n) => head(chars(n)), 30],
    ["control", 28, (n) => put(ship, "control", `control = choice("${chars(n)}", ["Корабль", "Гвоздь"])`), 28],
    // A choice of 2 to 4 options, and the 4 items of word-parts, have the option cap 16 (fix list 3, H3).
    ["control", 16, (n) => put(ship, "control", `control = choice("Предмет", ["Корабль", "${chars(n)}"])`), 16],
    ["i1", 16, (n) => put(items, "i1", `i1 = item("${chars(n)}", "пере-", "Стоит перед корнем.")`), 16],
    // A caption with no sentence end inside its cap cannot be cut, so one character past the cap is a fault.
    ["caption", 110, (n) => put(ship, "caption", `caption = "${chars(n)}"`), 110],
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
  // A scene has at most 20 shapes (stage 6, L3), so s21 is a fault and s20 is not.
  let many = ship;
  for (let index = 4; index <= 21; index++) many = put(many, `s${index}`, `s${index} = circle(${index * 12}, 200, 5, "ink")`);
  const faults = (await compileAppFile(many)).faults;
  expect([named(faults, "s21").length, named(faults, "s20").length]).toEqual([1, 0]);
  expect(named((await compileAppFile(put(cells, "control", 'control = slider("Votes for", 0, 101, 1, 11)'))).faults, "control")).toHaveLength(1);
});

test("aLongCaptionIsCutAfterItsLastWholeSentence", async () => {
  expect([...LONG].length).toBe(200);
  const result = await compileAppFile(shipCaption(LONG));
  expect([result.faults, result.leftOut, result.kept]).toEqual([[], [], shipCaption(CUT)]);
  // The screen shows the cut text.
  expect(every(result.definition.ui).some((node) => Object.values(node).includes(CUT))).toBe(true);
  expect(JSON.stringify(result.definition)).not.toContain(LONG);
  // A closing quote mark stays with its sentence, and a full stop that no space follows (3.5) ends no sentence.
  const quoted = `«${chars(107)}!»`, odd = await compileAppFile(shipCaption(`${quoted} ${chars(10)}3.5${chars(66)}`));
  expect([odd.faults, odd.kept]).toEqual([[], shipCaption(quoted)]);
});

test("aLongItemTextIsCutAfterItsLastWholeSentence", async () => {
  const item = (text) => put(template("word-parts"), "i1", `i1 = item("Приставка", "пере-", "${text}")`), result = await compileAppFile(item(LONG));
  expect([result.faults, result.leftOut, result.kept]).toEqual([[], [], item(CUT)]);
  expect(result.expanded).toContain(`text: "${CUT}"`);
  expect(result.expanded).not.toContain(LONG);
});

test("eachTextOfACaptionFormulaIsCutByItself", async () => {
  const other = `${sentence(90)} ${chars(79)}`, result = await compileAppFile(put(ship, "caption", `caption = $/v == "Корабль" ? "${LONG}" : "${other}"`));
  expect(result.faults).toEqual([]);
  const text = parseLine(result.kept.split("\n").find((line) => line.startsWith("caption = "))).tree;
  expect([text.test.right.v, text.yes.v, text.no.v]).toEqual(["Корабль", CUT, sentence(90)]);
});

test("aLongCaptionOrItemTextWithNoSentenceEndInsideItsCapIsAFaultOfItsLine", async () => {
  // A sentence end past the cap does not count.
  for (const text of [chars(200), `${sentence(150)} ${chars(49)}`]) {
    const caption = await compileAppFile(shipCaption(text)), item = await compileAppFile(put(template("word-parts"), "i1", `i1 = item("Приставка", "пере-", "${text}")`));
    for (const [name, result] of [["caption", caption], ["i1", item]]) {
      expect([name, result.faults.length, result.leftOut]).toEqual([name, 1, [name]]);
      expect(result.faults[0]).toStartWith(`${name}: `);
      expect(result.faults[0]).toMatch(/\b110\b/);
    }
  }
});

test("aCaptionAtItsCapIsNotCut", async () => {
  const text = `${sentence(80)} ${chars(29)}`, result = await compileAppFile(shipCaption(text));
  expect([...text].length).toBe(110);
  expect([result.faults, result.kept]).toEqual([[], shipCaption(text)]);
});

test("twoOptionsOrItemsWithTheSameTextAreAFaultOfTheirLine", async () => {
  // The choice cannot tell two options of one text apart.
  const options = await compileAppFile(put(ship, "control", 'control = choice("Предмет", ["Корабль", "Корабль"])'));
  expect([named(options.faults, "control").length, options.leftOut]).toEqual([1, ["control"]]);
  // The second item with the label of an earlier item is left out, and the control keeps the other items.
  const items = await compileAppFile(put(template("word-parts"), "i3", 'i3 = item("Приставка", "-ок", "Стоит после корня.")'));
  expect(items.faults).toHaveLength(1);
  expect(items.faults[0]).toStartWith("i3: ");
  expect(items.leftOut).toEqual(["i3"]);
  expect(every(items.definition.ui).find((node) => node.action === "v_field").options.map((option) => option.text)).toEqual(["Приставка", "Корень", "Окончание"]);
});

test("aPolygonWithFewerThanThreePointsOrAnOddListIsAFaultOfItsLine", async () => {
  // The kit compiles both clean: it draws nothing for two points and drops the last number of an odd list.
  for (const points of ["[45, 75, 255, 75]", "[45, 75, 255, 75, 225, 140, 75]"]) {
    const result = await compileAppFile(put(ship, "s2", `s2 = polygon(${points}, "ink", visible=$/v == "Корабль")`));
    expect([points, result.faults.length, result.leftOut]).toEqual([points, 1, ["s2"]]);
    expect(result.faults[0]).toStartWith("s2: ");
  }
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
  const lines = ship.trim().split("\n"), captionFirst = [lines[0], lines[1], lines[5], lines[2], lines[3], lines[4]].join("\n") + "\n";
  const turned = await compileAppFile(captionFirst);
  expect(turned.faults).toEqual([]);
  expect(turned.definition).toEqual(clean.definition);
});

test("aRepairReplyMergesByName", async () => {
  const fixed = ship.split("\n").find((line) => line.startsWith("s2 = "));
  const repaired = await compileStep({ text: fixed, plan: put(ship, "s2", "s2 = polygon(45, 75, 255)"), read: true });
  expect([repaired.kind, repaired.text, repaired.result.faults]).toEqual(["plan", ship, []]);
});

test("aChangeReplyMergesAndDeletes", async () => {
  const reply = ['s4 = circle(150, 160, 20, "sun", visible=$/v == "Гвоздь")', "delete s3", 'caption = "Вода держит полый корабль."'].join("\n");
  const changed = await step({ text: reply, plan: ship });
  expect(order(changed.text)).toEqual(["scene", "control", "s1", "s2", "s4", "caption"]);
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
  for (const [name, factor] of [["scene-caps", 1], ["items-caps", 1], ["cells-caps", 1], ["scene-caps125", 1.25], ["items-caps125", 1.25], ["cells-caps125", 1.25],
    ["steps-caps", 1], ["items-steps-caps", 1], ["scene-caps4", 1], ["items-caps4", 1], ["steps-caps4", 1]]) {
    // A text inside the picture (a shape text, a sign) is at floor(1.25 × cap) in a caps125 file. Each other text is at its cap in each file.
    const text = template(name), inside = (n) => Math.floor(n * factor), result = await compileAppFile(text);
    expect([name, result.faults, result.kept]).toEqual([name, [], text]);
    const [first, ...rest] = text.trim().split("\n"), head = parseExpression(first), lines = Object.fromEntries(rest.map((line) => [parseLine(line).key, parseLine(line).tree]));
    const parts = Object.keys(lines).filter((key) => /^[si]\d+$/.test(key)).map((key) => lines[key]), control = lines.control, caption = lines.caption;
    const seen = { title: len(head.args[0]), control: len(control.args[0]) }, want = { title: 30, control: 28 };
    if (head.name === "scene") {
      // A steps scene has no caption line: its caption is the narration sentence of the present step (stage A, K1).
      Object.assign(seen, { options: control.args[1].items.map(len), shapes: parts.length, texts: parts.filter((part) => part.name === "text").map((part) => len(part.args[2])), caption: caption ? len(caption) : null });
      // A choice of 2 to 4 options has options of 16 characters, and one of 5 or 6 options has 12 (fix list 3, H3).
      const few = name.endsWith("caps4");
      Object.assign(want, { options: few ? Array(4).fill(16) : Array(6).fill(12), shapes: 12, texts: Array(seen.texts.length).fill(inside(28)), caption: name.includes("steps") ? null : 110 });
      expect(seen.texts.length).toBeGreaterThan(0);
    } else if (head.name === "items") {
      Object.assign(seen, { items: parts.map((part) => part.args.map(len)) });
      Object.assign(want, { items: name.endsWith("caps4") ? Array(4).fill([16, 14, 110]) : Array(6).fill([12, inside(14), 110]) });
    } else {
      Object.assign(seen, { count: control.args[2].v, caption: len(caption) });
      Object.assign(want, { count: 100, caption: 110 });
    }
    // A steps file has six narration lines at their cap (plan "one scene", Task 25).
    const says = Object.keys(lines).filter((key) => /^say\d+$/.test(key)).map((key) => len(lines[key]));
    expect([name, says]).toEqual([name, name.includes("steps") ? Array(name.endsWith("caps4") ? 4 : 6).fill(110) : []]);
    expect([name, seen]).toEqual([name, want]);
  }
});

test("H3 and L2: an option or a step of a list of 2 to 4 has the cap 16, one of 5 or 6 has the cap 12, and a label is never cut", async () => {
  const scene = template("scene-caps"), items = template("items-caps4");
  const options = (list) => `control = choice("Color of the light to follow", [${list.map((one) => JSON.stringify(one)).join(", ")}], ${JSON.stringify(list[0])})`;
  const four = ["Violet light ray", "Indigo light ray", "Yellow light ray", "Orange light ray"], faultOf = async (text, name) => (await compileAppFile(text)).faults.filter((one) => one.startsWith(`${name}:`));
  expect(await faultOf(put(scene, "control", options(four)), "control")).toEqual([]);
  expect(await faultOf(put(scene, "control", options([...four.slice(0, 3), "Orange light rays"])), "control")).toEqual(["control: option 4 has 17 characters, and its cap is 16. Write it shorter"]);
  expect(await faultOf(put(scene, "control", options([...four, "Purple light"])), "control")).toEqual(["control: options 1, 2, 3, and 4 have 16 characters, and their cap is 12. Write each one shorter, or keep at most 4 options: their cap is 16"]);
  // A steps control is a choice on the stage, so its steps have the same caps (fix list 5, L2).
  expect(await faultOf(put(scene, "control", 'control = steps("Color of the light to follow", ["Violet light ray", "Indigo"])'), "control")).toEqual([]);
  expect(await faultOf(put(scene, "control", 'control = steps("Color of the light to follow", ["Violet light ray", "A", "B", "C", "D"])'), "control"))
    .toEqual(["control: step 1 has 16 characters, and its cap is 12. Write it shorter, or keep at most 4 steps: their cap is 16"]);
  // The labels of items are the options of its choice: a fifth item brings the cap of each label to 12, with a choice and with steps.
  const five = put(items, "i5", 'i5 = item("Хлоропласт", "Свет", "Здесь свет становится пищей.")');
  expect(await faultOf(five, "i1")).toEqual(["i1: the label has 16 characters, and its cap is 12. Write it shorter, or keep at most 4 items: their cap is 16"]);
  expect(await faultOf(put(items, "control", 'control = steps("Раздел клетки для просмотра.")'), "i2")).toEqual([]);
  expect(await faultOf(put(five, "control", 'control = steps("Раздел клетки для просмотра.")'), "i2")).toEqual(["i2: the label has 16 characters, and its cap is 12. Write it shorter, or keep at most 4 items: their cap is 16"]);
  expect(await faultOf(put(items, "i1", 'i1 = item("Ядро клетки и его ДНК", "ДНК", "Ядро.")'), "i1")).toEqual(["i1: the label has 21 characters, and its cap is 16. Write it shorter"]);
  // A label is not cut: the kept file has each label as the reply wrote it.
  expect((await compileAppFile(items)).kept).toBe(items);
});

test("L1: one fault of a control names each option or step over the cap, and one repair of the control line corrects each of them", async () => {
  // The first reply of the live heart talk (live-6b-uxHeart) with a fifth step: steps 1 and 2 have 13 characters.
  const steps = (list) => `control = steps("Путь крови", [${list.map((one) => JSON.stringify(one)).join(", ")}], ${JSON.stringify(list.at(-1))})`;
  const live = ["Правое предс.", "Правый желуд.", "Левое предс.", "Левый желуд."], scene = template("scene-caps").replace(/visible=\$\/v == "[^"]*"/g, "visible=$/v != \"\"");
  const faultOf = async (text) => (await compileAppFile(text)).faults.filter((one) => one.startsWith("control:"));
  expect(await faultOf(put(scene, "control", steps(live)))).toEqual([]);
  const long = put(scene, "control", steps([...live, "Аорта"]));
  expect(await faultOf(long)).toEqual(["control: steps 1 and 2 have 13 characters, and their cap is 12. Write each one shorter, or keep at most 4 steps: their cap is 16"]);
  expect(await faultOf(put(scene, "control", steps(["Правое предсердие!", "Правый желудочек", "Левое предс.", "Левый желуд.", "Аорта"]))))
    .toEqual(["control: steps 1 (18 characters) and 2 (16 characters) are over their cap of 12. Write each one shorter"]);
  // A repair reply of the control line with both steps shorter merges by name, and the file compiles clean.
  const repaired = mergeTemplate(long, steps(["Правое пред.", "Правый жел.", "Левое предс.", "Левый желуд.", "Аорта"]) + "\n");
  expect((await compileAppFile(repaired)).faults).toEqual([]);
  // The values of a row: each value over the cap in one fault.
  const table = template("table-caps"), row = (await compileAppFile(put(table, "r1", 'r1 = row("Меркурий", ["1234567890123", "2", "1234567890123"])'))).faults.filter((one) => one.startsWith("r1:"));
  expect(row).toEqual(["r1: values 1 and 3 have 13 characters, and their cap is 12. Write each one shorter, such as with a unit in the label"]);
});

test("aFreeAppFileIsNotATemplate", () => {
  const plans = plansIn(fileURLToPath(new URL(".", import.meta.url)));
  expect(plans.length).toBeGreaterThan(5);
  expect(plans.filter((path) => isTemplateFile(readFileSync(path, "utf8")))).toEqual([]);
  expect(["ship", "word-parts", "majority"].every((name) => isTemplateFile(template(name)))).toBe(true);
});
