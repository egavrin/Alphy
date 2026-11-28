// The graphics library of stage 6 (round 1): the registries of the shape kinds, the elements, and the ready objects, their lines in a scene,
// the faults of a library line, the miss log, and the catalog checks of L7.

import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import "./helpers.js";
import { compileAppFile, compileStep } from "../src/compile.js";
import { KINDS, PROPERTIES } from "../src/kinds.js";
import { TONES } from "../src/core.js";
import { ELEMENTS } from "../src/elements.js";
import { OBJECTS } from "../src/catalog.js";
import * as SCENE_FORM from "../templates/scene.js";
import { LABEL, LEAST, SEGMENTS, SMALLEST, entryOf, labelLines, layoutOf, lineWidth, segmentsOfEntry, useFault } from "../src/library.js";
import { parseExpression } from "../src/dsl.js";
import { COLOR_NAMES, toneColor } from "../src/form-base.js";
import { runtime } from "../src/emit.js";
import { expandTemplate, mergeTemplate, readTemplate } from "../src/template.js";
import { PROMPT, candidatesText, filled, registryValues } from "../prompt.js";
import { IMAGE, catalogAsset, imageAsset, imageFault, libraryEntries, libraryImages } from "../build.js";

/** A scene template with the shape lines, a slider that each shape reads, a card, and a say line. */
const scene = (...shapes) => [`scene("Probe", lang="en", palette="blue")`, `control = slider("Size", 1, 10, 1, 5)`,
  ...shapes.map((shape, index) => `s${index + 1} = ${shape.replace(/\)$/, ", visible=$/v > 0)")}`), `card = card("Probe", "Shapes of the library.")`, `say = "Look."`].join("\n") + "\n";
const compiled = async (...shapes) => {
  const result = await compileAppFile(scene(...shapes), null);
  return { ...result, commands: Object.values(result.frame?.drawing ?? {})[0]?.commands ?? [] };
};

test("each shape kind of the prompt draws from one line", async () => {
  const lines = { circle: 'circle(150, 150, 40, "accent")', rect: 'rect(40, 40, 80, 60, "sun", 8)', line: 'line(10, 10, 200, 100, "ink", 3)', text: 'text(150, 20, "Hi", "ink", 14)',
    polygon: 'polygon([0, 0, 40, 0, 20, 30], "land")', path: 'path([10, 10, 50, 40, 90, 10], "accent", 3, smooth=true)', arrow: 'arrow(20, 150, 200, 150, "accent", 6)', arc: 'arc(150, 150, 40, 0, 90, "ink", 4)' };
  const types = { circle: "circle", rect: "roundRect", line: "line", text: "text", polygon: "polygon", path: "path", arrow: "path", arc: "arc" };
  expect(Object.keys(lines).sort()).toEqual(Object.keys(KINDS).filter((kind) => KINDS[kind].prompt).sort());
  for (const [kind, line] of Object.entries(lines)) {
    const { faults, commands } = await compiled(line);
    expect([kind, faults, commands.map((command) => command.type)]).toEqual([kind, [], [types[kind]]]);
  }
  // A solid arrow is one closed path with rounded corners, and an arc goes counterclockwise as sin and cos of the prompt.
  const arrow = (await compiled(lines.arrow)).commands[0];
  expect([arrow.segments.at(-1).type, arrow.segments.some((segment) => segment.type === "quad"), arrow.stroke]).toEqual(["close", true, undefined]);
  const arc = (await compiled(lines.arc)).commands[0];
  expect([Math.abs(arc.start), arc.sweep, arc.stroke]).toEqual([0, -90, 4]);
});

test("each shape property of the prompt changes the drawing", async () => {
  const plain = (await compiled('rect(100, 100, 60, 30, "land")')).commands;
  expect(plain).toEqual([{ type: "rect", x: 100, y: 100, width: 60, height: 30, color: "#65a30d" }]);
  const ball = (await compiled('circle(150, 150, 40, "water+1", gradient="water-1", radial=1)')).commands[0];
  expect([ball.gradient.type, ball.gradient.stops.length, ball.gradient.x < 150, ball.gradient.y < 150]).toEqual(["radial", 2, true, true]);
  const fall = (await compiled('rect(100, 100, 60, 30, "land", gradient="land-2")')).commands[0];
  expect([fall.gradient.type, fall.gradient.y, fall.gradient.y2]).toEqual(["linear", 100, 130]);
  const glow = (await compiled('rect(40, 40, 80, 60, "sun", glow=true)')).commands;
  expect([glow.length, glow[0].gradient.type, glow[1].type]).toEqual([2, "radial", "rect"]);
  expect((await compiled('circle(150, 150, 40, "accent", opacity=0.5)')).commands[0].color).toBe("accent@50");
  const turned = (await compiled('rect(100, 100, 60, 30, "land", turn=30)')).commands[0];
  expect([turned.type, turned.transform.map((value) => Math.round(value * 1000) / 1000).slice(0, 4), turned.children.length]).toEqual(["group", [0.866, -0.5, 0.5, 0.866], 1]);
  const named = Object.keys(PROPERTIES).filter((name) => PROPERTIES[name].prompt);
  expect(named).toEqual(["visible", "stroke", "gradient", "radial", "glow", "opacity", "turn"]);
});

test("each element draws from one library line, inside the drawing budget", async () => {
  expect(Object.keys(ELEMENTS).length).toBeGreaterThanOrEqual(40);
  expect(new Set(Object.values(ELEMENTS).map((entry) => entry.group)).size).toBe(8);
  for (const [name, entry] of Object.entries(ELEMENTS)) {
    const label = entry.text ? ', label="a^2 + b^2"' : "";
    const { faults, leftOut, commands } = await compiled(`use("${name}", 150, 150, 160${label})`);
    expect([name, faults, leftOut, commands.length > 0]).toEqual([name, [], [], true]);
  }
});

test("each ready object draws with its named parts and a mark that a control moves", async () => {
  for (const name of Object.keys(OBJECTS)) {
    // An entry with no part (the star) takes no mark.
    const first = OBJECTS[name].parts[0]?.id;
    const { faults, leftOut, commands } = await compiled(`use("${name}", 90, 150, 120, parts=true${first ? `, mark="${first}"` : ""})`);
    expect([name, faults, leftOut]).toEqual([name, [], []]);
    // One group for the object, and a leader line, a point, and the lines of a text for each part.
    const texts = commands.filter((command) => command.type === "text").map((command) => command.text);
    expect([name, commands[0].type, texts.sort()]).toEqual([name, "group", OBJECTS[name].parts.flatMap((part) => labelLines(part.names.en)).sort()]);
  }
  // A mark that is a formula of $/v becomes the id of its part once, by its id or by its name in the language of the scene.
  const choose = (value) => [`scene("Heart", lang="ru", palette="red")`, `control = choice("Часть", ["Аорта", "Желудочек"], "${value}")`,
    `s1 = use("heart", 90, 150, 160, mark=$/v == "Аорта" ? "аорта" : "left_ventricle")`, `card = card("Сердце", "Сердце качает кровь.")`, `say = "Смотри."`].join("\n") + "\n";
  for (const [value, marked] of [["Аорта", "аорта"], ["Желудочек", "левый желудочек"]]) {
    const result = await compileAppFile(choose(value), null), commands = Object.values(result.frame.drawing)[0].commands;
    expect(result.faults).toEqual([]);
    expect(result.expanded).toContain("$/s1m := ");
    const accent = commands.filter((command) => command.type === "text" && command.color === "accent").map((command) => command.text);
    expect([value, accent.join(" ")]).toEqual([value, marked]);
  }
});

test("a library line over a limit is a fault, and an unknown name is a miss that still gives a screen", async () => {
  const fault = (line) => useFault(parseExpression(line));
  expect(fault(`use("heart", 150, 150, ${LEAST - 1})`)).toBe(`the SIZE of heart is from ${LEAST} to 300, and this line has ${LEAST - 1}`);
  expect(fault('use("heart", 150, 150, 120, label="A text that is far too long for a caption")')).toBe("label= has 41 characters, and its cap is 28");
  expect(fault('use("thermometer", 150, 150, 120, [1, 2, 3])')).toBe("thermometer takes at most 1 numbers: value");
  expect(fault('use("heart", 150, 150, 120, mark="tail")')).toContain('heart has no part "tail"');
  expect(fault('use("thermometer", 150, 150, 120, mark="glass")')).toBe("thermometer is an element: it has no parts to mark");
  expect(fault('use("heart", 150, 150, 120, parts=true)')).toBe(null);
  // The names of the scene language must leave the object its smallest size: the Russian names of the heart are longer than the English ones.
  const wide = parseExpression('use("heart", 150, 150, 160, parts=true)');
  expect([useFault(wide, [190, 300], "en"), useFault(wide, [190, 300], "ru")]).toEqual([null, "the names of the parts of heart leave it too little room: name fewer parts, write shorter names, or give it more height"]);
  expect([SCENE_FORM.partFault(wide, "ru"), SCENE_FORM.partFault(parseExpression('use("heart", 150, 150, 10, parts=true)'), "ru")?.slice(0, 16)]).toEqual([null, "the SIZE of hear"]);
  const result = await compiled('circle(150, 150, 40, "accent")', 'use("unicorn", 60, 60, 80)');
  expect([result.faults, result.misses, result.leftOut, result.definition === null]).toEqual([['s2: the library has no "unicorn". Draw it with shapes, or with an emoji in a text shape'], ["unicorn"], ["s2"], false]);
});

test("H1: each palette name is its own fixed color with a tone step and an alpha, in a shape and in an element, and an unknown word is a fault", async () => {
  const names = ["teal", "green", "blue", "amber", "orange", "red", "pink", "purple", "indigo", "brown", "graphite"];
  for (const name of names) {
    for (const [given, step, alpha] of [[name, "", 100], [`${name}+2`, "+2", 100], [`${name}-1`, "-1", 100], [`${name}@40`, "", 40], [name.toUpperCase(), "", 100]]) {
      // The color is the hex of the name with its tone, so "red" is red in each palette and in both themes.
      const want = toneColor(COLOR_NAMES[name], step, alpha), shape = await compiled(`circle(150, 150, 40, "${given}")`), element = await compiled(`use("thermometer", 60, 150, 120, color="${given}")`);
      expect([given, shape.faults, shape.commands[0].color, element.faults, JSON.stringify(element.commands).includes(`"color":"${want}"`)]).toEqual([given, [], want, [], true]);
    }
  }
  // A choice of color names in color= of an element gets the same colors.
  const choice = await compiled('use("thermometer", 60, 150, 120, color=$/v > 5 ? "red+2" : "Blue")'), drawn = JSON.stringify(choice.definition);
  expect([choice.faults, drawn.includes("#f1a8a8"), drawn.includes(COLOR_NAMES.blue)]).toEqual([[], true, true]);
  expect(toneColor("#dc2626", "+2", 100)).toBe("#f1a8a8");
  expect(toneColor("#4b5563", "", 40)).toBe("#664b5563");
  for (const line of ['circle(150, 150, 40, "shiny")', 'use("thermometer", 60, 150, 120, color="shiny")', 'circle(150, 150, 40, "red+4")']) {
    expect([line, (await compiled(line)).faults[0]]).toEqual([line, expect.stringContaining("is not a color of the kit")]);
  }
});

test("H2: a ready object reads from SIZE 24 and is drawn at its SIZE, and a SIZE under 24 or above 300 is a fault with the range", async () => {
  expect(useFault(parseExpression('use("sun", 60, 150, 40)'))).toBe(null);
  const small = await compiled('use("sun", 60, 150, 40)');
  expect([small.faults, small.commands[0].transform.map((value) => value + 0)]).toEqual([[], [0.4, 0, 0, 0.4, 60, 150]]);
  expect(useFault(parseExpression('use("sun", 60, 150, 10)'))).toBe("the SIZE of sun is from 24 to 300, and this line has 10");
  expect(useFault(parseExpression('use("sun", 60, 150, 301)'))).toBe("the SIZE of sun is from 24 to 300, and this line has 301");
});

test("K3: a change reply that adds a ready object to a scene of shapes merges by name and compiles", async () => {
  const shapes = scene('rect(60, 100, 80, 90, "error", 8)', 'rect(160, 100, 80, 90, "water", 8)', 'text(150, 40, "Chambers", "ink", 14)');
  const merged = mergeTemplate(shapes, 's4 = use("heart", 150, 150, 200, parts=true, visible=$/v > 0)\ndelete s1\ndelete s2\n');
  const result = await compileAppFile(merged, null);
  expect([result.faults, merged.includes('s4 = use("heart"'), merged.includes("s1 ="), JSON.stringify(result.definition).includes("body.heart")]).toEqual([[], true, false, true]);
});

test("F2: the labels of the parts stand at the side of their part, and the object stands large between them", () => {
  const wide = lineWidth;
  // A wide letter takes more room: the Russian name of the porthole of the rocket was cut at the edge of the phone stage with 0.56 for each letter.
  expect([lineWidth("корпус"), lineWidth("иллюминатор"), lineWidth("Южная")].map(Math.round)).toEqual([40, 81, 41]);
  for (const name of Object.keys(OBJECTS)) {
    for (const lang of ["en", "ru"]) {
      const found = entryOf(name), [w, h] = found.entry.box, at = `${name} ${lang}`;
      const { x, y, size, labels } = layoutOf(found, { parts: { k: "lit", v: true } }, [150, 150, 300], [300, 300], lang);
      const k = size / 100, box = { left: x - (w * k) / 2, right: x + (w * k) / 2, top: y - (h * k) / 2, bottom: y + (h * k) / 2 };
      expect([at, labels.length, labels.some((one) => one.clipped), size >= SMALLEST]).toEqual([at, found.entry.parts.length, false, true]);
      for (const one of labels) {
        // A leader line goes from the point of its part out of the box at the side of the part: in the box it runs at most the way from
        // the nearer side of the box to the point.
        const inside = one.align === "end" ? one.px - box.left : box.right - one.px;
        expect([at, one.part.id, inside <= Math.min(one.px - box.left, box.right - one.px) + 1]).toEqual([at, one.part.id, true]);
      }
      // No two labels overlap, and each one stays in the picture.
      const rects = labels.map((one) => {
        const long = Math.max(...one.lines.map(wide)), tall = one.lines.length * LABEL.line;
        return { left: one.align === "end" ? one.x - long : one.x, right: one.align === "end" ? one.x : one.x + long, top: one.y - tall / 2, bottom: one.y + tall / 2 };
      });
      rects.forEach((a, i) => rects.slice(i + 1).forEach((b) => {
        expect([at, a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom]).toEqual([at, false]);
      }));
      expect([at, rects.every((one) => one.left >= 0 && one.right <= 300 && one.top >= 0 && one.bottom <= 300)]).toEqual([at, true]);
      // The object stands in the middle of the space that the two label columns leave, and fills its width or the height of the picture.
      const column = (end) => Math.max(0, ...labels.filter((one) => (one.align === "end") === end).flatMap((one) => one.lines.map(wide)));
      const [xl, xr] = [LABEL.margin + (column(true) ? column(true) + LABEL.gap : 0), 300 - LABEL.margin - (column(false) ? column(false) + LABEL.gap : 0)];
      expect([at, Math.abs(x - (xl + xr) / 2) < 0.01, box.right - box.left >= xr - xl - 0.5 || box.bottom - box.top >= 300 - 2 * LABEL.margin - 0.5]).toEqual([at, true, true]);
    }
  }
  // With no label, an object keeps its place and its size.
  expect(layoutOf(entryOf("heart"), {}, [90, 120, 100], [300, 300], "en")).toEqual({ x: 90, y: 120, size: 100, labels: [] });
  expect([labelLines("левый желудочек"), labelLines("аорта"), labelLines("гипотенуза c"), labelLines("ball of gas")]).toEqual([["левый", "желудочек"], ["аорта"], ["гипотенуза c"], ["ball", "of gas"]]);
});

test("F3: a marked part keeps its own colors, and an accent rim stands around it", async () => {
  const group = async (line) => (await compiled(line)).commands[0].children;
  for (const [name, part] of [["water_molecule", "oxygen"], ["cube", "face"], ["cone", "base"], ["right_triangle", "hypotenuse"], ["heart", "aorta"], ["eye", "lens"]]) {
    const plain = await group(`use("${name}", 150, 150, 200, parts=true)`), marked = await group(`use("${name}", 150, 150, 200, parts=true, mark="${part}")`);
    // Each command of the plain object stays as it is, in its order, and nothing fades.
    let next = 0;
    for (const command of marked) if (next < plain.length && JSON.stringify(command) === JSON.stringify(plain[next])) next += 1;
    const added = marked.filter((command) => !plain.some((one) => JSON.stringify(one) === JSON.stringify(command)));
    const accent = (await compiled('circle(150, 150, 4, "accent")')).commands[0].color;
    expect([name, next, added.length > 0, added.every((command) => command.color === accent && command.stroke > 0)]).toEqual([name, plain.length, true, true]);
    expect([name, marked.some((command) => /@/.test(command.color ?? "") || command.opacity !== undefined)]).toEqual([name, plain.some((command) => /@/.test(command.color ?? "") || command.opacity !== undefined)]);
  }
});

test("F4: the body of a solid arrow is a third of its head, and each arrow element but flow and link draws solid arrows", () => {
  for (const width of [4, 8, 12]) {
    const { segments } = KINDS.arrow.draw({ x1: 0, y1: 0, x2: 200, y2: 0, width });
    const points = segments.flatMap((one) => [[one.x, one.y], [one.cx, one.cy]]).filter(([x]) => x !== undefined);
    const half = (from, to) => Math.max(...points.filter(([x]) => x >= from && x <= to).map(([, y]) => Math.abs(y)));
    // The body from the tail to the middle is the width, and the head is at most 3 widths wide.
    expect([width, half(0, 100), half(0, 200) * 2 <= width * 3 + 0.01]).toEqual([width, width / 2, true]);
  }
  for (const [name, entry] of Object.entries(ELEMENTS).filter(([key, entry]) => entry.group === "arrows" && !["flow", "link"].includes(key))) {
    const shapes = entry.draw({ n: entry.defaults.map(String), c: '"accent"', u: "1" }).join(" ");
    expect([name, /arrow\(/.test(shapes), /polygon\(/.test(shapes)]).toEqual([name, true, false]);
  }
});

test("G2: a number of an element that is a formula is written one time, and a plain number above the cap of its element is a fault", async () => {
  const bytes = (result) => Buffer.byteLength(result.definition.source);
  // The held-out scene of the model check (evidence/modelcheck/tpl-lib-final-m/m-half.0.txt) with its option label cut to the cap of 12.
  const half = [`scene("Сравнение дробей", lang="ru", palette="indigo")`, `control = choice("Представление", ["Дроби", "Общий знамен"], "Общий знамен")`,
    `s1 = text(150, 40, "2/3 больше 3/5", "ink", 20)`, `s2 = text(150, 85, $/v == "Дроби" ? "2/3" : "10/15", "ink", 18)`,
    `s3 = use("fraction_bar", 150, 120, 240, [$/v == "Дроби" ? 3 : 15, $/v == "Дроби" ? 2 : 10])`, `s4 = text(150, 180, $/v == "Дроби" ? "3/5" : "9/15", "ink", 18)`,
    `s5 = use("fraction_bar", 150, 215, 240, [$/v == "Дроби" ? 5 : 15, $/v == "Дроби" ? 3 : 9])`,
    `card = card("Две трети больше", "При общем знаменателе получаем 10/15 и 9/15. Разница равна 1/15.")`, `say = "Выбери представление и сравни закрашенные части равных полос."`].join("\n") + "\n";
  const result = await compileAppFile(half, null), app = (one) => one.definition.source.slice(runtime.text.length);
  console.log(`G2 m-half source: ${bytes(result)} bytes, of them the app after the run-time ${Buffer.byteLength(app(result))} (before the change: 67322)`);
  // Each formula of a number stands one time in the app, and the source keeps under the limit.
  expect([result.faults, bytes(result) < 65536, app(result).split("? 3 : 15").length - 1, app(result).split("? 5 : 15").length - 1]).toEqual([[], true, 1, 1]);
  // Six lines of each element with numbers, each number a formula, write each formula one time for each line.
  // shortcut: these elements draw up to their cap of shapes with formulas, and six of them pass the 9.2 KB that the 56.3 KB run-time leaves
  // in the 64 KB source, or the budget of gradient stops; lift the list when an element draws fewer shapes for a formula (fix list 2, G2).
  const heavy = ["grid", "ruler", "sector", "fraction_bar", "dots", "wave", "layers", "branch", "cell_block"];
  for (const [name, entry] of Object.entries(ELEMENTS).filter(([, one]) => one.numbers.length)) {
    const numbers = entry.numbers.map((_, index) => `$/v == "A${index}" ? ${entry.defaults[index] + 1} : ${entry.defaults[index] + 2}`).join(", ");
    const label = entry.text ? ', label="a^2"' : "";
    const plan = [`scene("Six", lang="en", palette="blue")`, `control = choice("Kind", ["A", "B"], "A")`,
      ...[0, 1, 2, 3, 4, 5].map((i) => `s${i + 1} = use("${name}", ${50 + 100 * (i % 3)}, ${60 + 100 * Math.floor(i / 3)}, 80, [${numbers}]${label})`),
      `card = card("Six", "Six of one element.")`, `say = "Look."`].join("\n") + "\n";
    const six = await compileAppFile(plan, null), lines = readTemplate(plan);
    const once = expandTemplate(lines, new Set()), counts = entry.numbers.map((_, index) => once.split(`"A${index}"`).length - 1);
    expect([name, counts]).toEqual([name, entry.numbers.map(() => 6)]);
    expect([name, heavy.includes(name) ? six.faults.length === 1 && /bytes|gradient stops/.test(six.faults[0]) : six.faults.length === 0]).toEqual([name, true]);
  }
  // A plain number above the cap is a fault that names the cap, and a formula above it draws the cap.
  expect(useFault(parseExpression('use("fraction_bar", 150, 120, 240, [15, 10])'))).toBe("the parts of fraction_bar is at most 12, and this line has 15");
  expect(useFault(parseExpression('use("fraction_bar", 150, 120, 240, [12, 10])'))).toBe(null);
  for (const [name, entry] of Object.entries(ELEMENTS).filter(([, one]) => one.caps)) {
    for (const [number, cap] of Object.entries(entry.caps)) {
      const values = entry.numbers.map((one, index) => (one === number ? cap + 1 : entry.defaults[index]));
      expect([name, useFault(parseExpression(`use("${name}", 150, 120, 200, [${values.join(", ")}])`))]).toEqual([name, `the ${number} of ${name} is at most ${cap}, and this line has ${cap + 1}`]);
    }
  }
});

test("a change of a registry changes the prompt, the grammar, and the faults", () => {
  const before = registryValues();
  KINDS.star = { args: ["x", "y", "r"], prompt: "star(x, y, r, color)", draw: () => null };
  ELEMENTS.kite = { group: "marks", numbers: [], defaults: [], color: "accent", prompt: "a kite", draw: () => ['circle(0, 0, 10, "accent")'] };
  OBJECTS.moon = { id: "space.moon", box: [100, 100], layers: [{ role: "muted", circle: [0, 0, 40] }], parts: [{ id: "crater", names: { en: "crater" }, label: [10, 10] }] };
  try {
    const after = registryValues();
    expect([before.kinds.includes("star("), after.kinds.includes("star(x, y, r, color)"), after.elements.includes("kite() a kite")]).toEqual([false, true, true]);
    expect(filled("Kinds: {{kinds}}. {{candidates}}", after)).toBe(`Kinds: ${after.kinds}. {{candidates}}`);
    expect(SCENE_FORM.partFault(parseExpression('star(1, 2, 3, "ink")'))).toBeNull();
    expect(SCENE_FORM.partFault(parseExpression('comet(1, 2, 3, "ink")'))).toContain("star");
    expect([entryOf("moon")?.kind, entryOf("space.moon")?.kind, useFault(parseExpression('use("moon", 150, 150, 100, mark="crater")'))]).toEqual(["object", "object", null]);
  } finally {
    delete KINDS.star;
    delete ELEMENTS.kite;
    delete OBJECTS.moon;
  }
  expect(registryValues()).toEqual(before);
});

test("the candidates of a request are one line each, with the name, the line, and the parts", () => {
  const objects = JSON.parse(catalogAsset()).objects.slice(0, 2);
  const text = candidatesText(objects);
  expect(text.split("\n")[0]).toBe(PROMPT["Template library"].split("\n")[0]);
  for (const object of objects) expect(text).toContain(`- ${object.name}: ${object.line}. Parts: ${object.parts.map((part) => part.id).join(", ")}`);
  expect(candidatesText([])).toBe("");
});

// ---- The catalog checks of L7.

const entries = libraryEntries();
const names = JSON.parse(readFileSync(new URL("./fixtures/library-names.json", import.meta.url), "utf8")).entries;

test("catalog: each name is unique across the objects, the elements, and the names of the spec list", () => {
  const ids = entries.map((entry) => entry.id.split(".")[1]);
  expect(new Set([...ids, ...Object.keys(ELEMENTS)]).size).toBe(ids.length + Object.keys(ELEMENTS).length);
  for (const lang of ["ru", "en"]) {
    const all = entries.flatMap((entry) => entry.names[lang]);
    expect([lang, new Set(all).size]).toEqual([lang, all.length]);
  }
  const listed = names.map((entry) => entry.name);
  expect(new Set(listed).size).toBe(listed.length);
  for (const entry of entries) expect([entry.id, listed.includes(entry.id.split(".")[1]) || entry.id === "chem.water_molecule"]).toEqual([entry.id, true]);
});

test("I1, I4, I5: an image entry compiles to a node with its image, its box, its labels, and its mark, keeps its colors, and a missing image is a miss", async () => {
  const { faults, commands } = await compiled('use("eye", 150, 150, 200, parts=true, mark="lens")');
  const group = commands[0], image = group.children[0];
  expect([faults, group.type, image]).toEqual([[], "group", { type: "image", id: "body.eye", x: -50, y: -50, width: 100, height: 100 }]);
  // The picture fills the box of the line: the group scales the 100 units of the entry to the size that the labels leave.
  const lens = OBJECTS.eye.parts.find((part) => part.id === "lens"), ring = group.children[1], accent = (await compiled('circle(150, 150, 4, "accent")')).commands[0].color;
  expect([group.children.length, ring.type, ring.color, ring.width, ring.x + ring.width / 2, ring.y + ring.height / 2]).toEqual([2, "arc", accent, 2 * lens.ring, lens.label[0], lens.label[1]]);
  const texts = commands.filter((command) => command.type === "text");
  expect([texts.map((one) => one.text).sort(), texts.filter((one) => one.color === accent).map((one) => one.text)]).toEqual([OBJECTS.eye.parts.flatMap((part) => labelLines(part.names.en)).sort(), ["lens"]]);
  // A color or a tone on a picture is a fault that names the option, and turn= turns it.
  expect(useFault(parseExpression('use("eye", 150, 150, 120, color="red")'))).toBe("eye is a ready picture with its own colors, so it takes no color=");
  expect(useFault(parseExpression('use("eye", 150, 150, 120, tone=1)'))).toContain("tone= is not one");
  const [a, b] = (await compiled('use("rocket", 150, 150, 120, turn=30)')).commands[0].transform;
  expect(Math.round((Math.atan2(-b, a) * 180) / Math.PI)).toBe(30);
  // An image that the host cannot open makes its name a miss: the repair draws it with shapes or an emoji.
  const plan = scene('use("eye", 150, 150, 120)');
  const missing = await compileStep({ text: plan, plan: null, read: false, previous: null, cap: 100000, missing: ["body.eye"] });
  expect([missing.result.misses, missing.result.faults[0]]).toEqual([["eye"], 's1: the library has no "eye". Draw it with shapes, or with an emoji in a text shape']);
  expect((await compileStep({ text: plan, plan: null, read: false, previous: null, cap: 100000 })).result.faults).toEqual([]);
});

test("catalog: each entry has at most 40 segments, roles and tone steps only, and a label point inside its box for each part", () => {
  const steps = (layer) => [layer.tone, layer.outline, ...(layer.gradient ?? []).slice(0, 2)].filter((step) => step !== undefined);
  for (const entry of entries) {
    const [w, h] = entry.box, parts = entry.parts.map((part) => part.id);
    expect([entry.id, segmentsOfEntry(entry) <= SEGMENTS, Boolean(entry.image) !== Boolean(entry.layers)]).toEqual([entry.id, true, true]);
    // An image entry: its label points and its mark rings lie inside its box (I1, I3).
    if (entry.image) for (const part of entry.parts) {
      const [x, y] = part.label;
      expect([entry.id, part.id, part.ring > 0 && Math.abs(x) + part.ring <= w / 2 + 0.01 && Math.abs(y) + part.ring <= h / 2 + 0.01]).toEqual([entry.id, part.id, true]);
    }
    for (const layer of entry.layers ?? []) {
      expect([entry.id, Object.hasOwn(TONES, layer.role)]).toEqual([entry.id, true]);
      expect([entry.id, steps(layer).every((step) => Number.isInteger(step) && Math.abs(step) <= 3)]).toEqual([entry.id, true]);
      if (layer.part) expect([entry.id, layer.part, parts.includes(layer.part)]).toEqual([entry.id, layer.part, true]);
    }
    for (const part of entry.parts) {
      const [x, y] = part.label;
      expect([entry.id, part.id, Math.abs(x) <= w / 2 && Math.abs(y) <= h / 2, Boolean(part.names.ru && part.names.en)]).toEqual([entry.id, part.id, true, true]);
      if (entry.layers) expect([entry.id, part.id, entry.layers.some((layer) => layer.part === part.id)]).toEqual([entry.id, part.id, true]);
    }
    expect([entry.id, new Set(parts).size]).toEqual([entry.id, parts.length]);
  }
});

test("catalog: each image is a WebP file with alpha inside the limits of I2, and the build refuses one past them", () => {
  const images = libraryImages();
  expect([images.length, images.every(([, bytes]) => imageFault(bytes) === null)]).toEqual([20, true]);
  expect(images.reduce((sum, [, bytes]) => sum + bytes.length, 0)).toBeLessThanOrEqual(IMAGE.total);
  expect(images.map(([path]) => path)).toEqual(entries.filter((entry) => entry.image).map((entry) => imageAsset(entry.id)));
  // 28 entries: 20 images and 8 vector entries (the two of round 1 that stay, and the six figures of mathematics).
  expect([entries.length, entries.filter((entry) => !entry.image).map((entry) => entry.id).sort()]).toEqual([28, ["chem.water_molecule", "math.circle_radius", "math.cone",
    "math.coordinate_plane", "math.cube", "math.cylinder", "math.right_triangle", "math.square_grid"]]);
  const [, heart] = images[0], wrong = Buffer.from(heart);
  wrong[24] = 0;
  expect(imageFault(wrong)).toBe("it is 513 x 768 pixels, and an image is 768 x 768");
  expect(imageFault(Buffer.concat([heart, Buffer.alloc(IMAGE.bytes)]))).toContain("and its cap is 153600");
  expect(imageFault(Buffer.from("RIFF0000WEBPVP8 0000000000000000000000"))).toBe("it is not a WebP file with alpha");
});

test("catalog: the format, the build, and the checks hold for 27 objects", async () => {
  // 27 copies of the samples under other names stand for the first set of round 2: each one compiles, and the asset lists each one.
  const copies = Array.from({ length: 27 }, (_, index) => {
    const entry = entries[index % entries.length], name = `copy_${index}`;
    return { ...entry, id: `test.${name}`, names: { ru: [`копия ${index}`], en: [`copy ${index}`] } };
  });
  expect(JSON.parse(catalogAsset(copies)).objects.map((object) => object.name)).toEqual(copies.map((entry) => entry.id.split(".")[1]));
  try {
    for (const entry of copies) OBJECTS[entry.id.split(".")[1]] = entry;
    // Three objects of a scene at a time: Alphy draws at most 32768 bytes and 512 path segments in one reply, so a scene holds a few objects.
    for (let index = 0; index < copies.length; index += 3) {
      const three = copies.slice(index, index + 3).map((entry, k) => `use("${entry.id.split(".")[1]}", ${60 + k * 90}, 150, 80)`);
      const { faults, leftOut } = await compiled(...three);
      expect([index, faults, leftOut]).toEqual([index, [], []]);
    }
  } finally {
    for (const entry of copies) delete OBJECTS[entry.id.split(".")[1]];
  }
});
