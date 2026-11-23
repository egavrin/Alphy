// Stage 7 of block 2 (docs/superpowers/plans/2026-11-22-block-2-forms-library-motion.md): the play control of a scene (M1, M2, M7) and the
// diagram over the parts of an object (G1, G2, C2).

import { expect, test } from "bun:test";
import { compileAppFile } from "../src/compile.js";
import { handle } from "../src/run.js";
import { BUDGET } from "../src/template.js";
import { FREE, LABEL, lineWidth } from "../src/library.js";
import { boxOf } from "../src/kinds.js";
import { T0, clocked, every, valueAt } from "./helpers.js";

const timer = (timerId) => ({ type: "timer", timerId });
const request = (state, event) => ({ artifactId: "kit-test", revision: 1, state, event });
const orbit = ['app("Orbit", lang="ru")', 'need t = play("Движение", 0, 360, 10)', 'need picture = drawing("Orbit", 300, 300, [circle(150 + 100 * cos($/t), 150 - 100 * sin($/t), 10, "accent")])'].join("\n");

test("play: the value runs from the first state, with no press, and loops at the end of its round", async () => {
  const { definition, app } = await clocked(orbit);
  const run = definition.initialState.anim.t;
  expect([run.from, run.to, run.end - run.start, run.loop]).toEqual([0, 360, 10000, 1]);
  const opened = handle(request(definition.initialState, { type: "open" }), app, run.start + 2500);
  expect(opened.state.t).toBe(90);
  expect(opened.timers).toEqual([{ id: "anim", intervalMs: 50 }]);
  // A round later the value is at the same share: the loop never ends.
  const later = handle(request(opened.state, timer("anim")), app, run.start + 12500);
  expect([later.state.t, later.timers.length]).toEqual([90, 1]);
});

test("play: a pause keeps the value and stops the timer, and a play goes on from it", async () => {
  const { definition, app } = await clocked(orbit);
  const start = definition.initialState.anim.t.start, at = (ms, state, event) => handle(request(state, event), app, start + ms);
  const moved = at(1000, definition.initialState, timer("anim"));
  expect(moved.state.t).toBeCloseTo(36, 6);
  const paused = at(1000, moved.state, { type: "action", actionId: "t_button", value: null });
  expect([Math.round(paused.state.t), paused.state.t_s.on, paused.timers]).toEqual([36, false, []]);
  // While it stands, a late timer event changes nothing.
  expect(at(5000, paused.state, timer("anim")).state.t).toBe(paused.state.t);
  const played = at(9000, paused.state, { type: "action", actionId: "t_button", value: null });
  expect([played.state.t, played.state.t_s.on, played.timers]).toEqual([paused.state.t, true, [{ id: "anim", intervalMs: 50 }]]);
  expect(at(10000, played.state, timer("anim")).state.t).toBeCloseTo(72, 6);
});

test("play: each speed keeps the value: slow takes two times the round, and fast half of it", async () => {
  const { definition, app } = await clocked(orbit);
  const start = definition.initialState.anim.t.start, at = (ms, state, event) => handle(request(state, event), app, start + ms);
  const moved = at(1000, definition.initialState, timer("anim"));
  const slow = at(1000, moved.state, { type: "action", actionId: "t_speed", value: "slow" });
  expect([slow.state.t, slow.state.anim.t.end - slow.state.anim.t.start]).toEqual([moved.state.t, 20000]);
  expect(at(3000, slow.state, timer("anim")).state.t).toBeCloseTo(72, 6);
  const fast = at(1000, moved.state, { type: "action", actionId: "t_speed", value: "fast" });
  expect([fast.state.t, fast.state.anim.t.end - fast.state.anim.t.start]).toEqual([moved.state.t, 5000]);
  expect(at(1500, fast.state, timer("anim")).state.t).toBeCloseTo(72, 6);
});

test("play: the control is a label, then one row of a round tonal button with a pause or a play icon and three speeds in the language of the app", async () => {
  const { definition } = await clocked(orbit);
  const nodes = every(definition.ui), button = nodes.find((node) => node.type === "button"), speeds = nodes.find((node) => node.type === "singleChoice");
  expect(nodes.find((node) => node.type === "text" && node.text === "Движение")?.style).toEqual({ emphasis: "label" });
  // Fix list 1, F2: the button is round with its icon only, and its text is its spoken name. The speeds fill the rest of a row with no wrap.
  expect([button.text, button.icon, button.variant]).toEqual(["Пауза", "Pause", "tonalIcon"]);
  const row = nodes.find((node) => node.children?.includes(button));
  expect([row.type, row.wrap, row.children.indexOf(speeds), speeds.layout]).toEqual(["row", false, 1, { weight: 1 }]);
  expect([speeds.variant, speeds.options.map((option) => option.text)]).toEqual(["inline", ["Медленно", "Обычно", "Быстро"]]);
  expect(valueAt(definition.initialState, speeds.bind)).toBe("normal");
});

test("play: a scene template with a play line runs $/t, and a file with play and steps compiles (M7)", async () => {
  const file = ['scene("Земля вокруг Солнца", lang="ru")', 'play = play("Движение", 0, 360, 14)', 's1 = use("sun", 150, 150, 80)',
    's2 = use("earth", 150 + 100 * cos($/t), 150 - 100 * sin($/t), 40)', 'card = card("Один оборот за год", "Земля обходит Солнце за год.")'].join("\n");
  const one = await compileAppFile(file);
  expect(one.faults).toEqual([]);
  expect(one.definition.initialState.anim.t.loop).toBe(1);
  const both = await compileAppFile(['scene("Фазы Луны", lang="ru")', 'control = steps("Фаза", ["Новая", "Полная"])', 'play = play("Вращение", 0, 360, 8)',
    's1 = circle(150 + 90 * cos($/t), 150, 25, "graphite", visible=$/v == "Полная")', 's2 = circle(150, 150, 20, "accent", visible=$/v == "Новая")',
    'card = card("Луна", "Луна обходит Землю.")', 'say1 = "Сначала Луну не видно."', 'say2 = "Потом она полная."'].join("\n"));
  expect(both.faults).toEqual([]);
  const kept = both.kept.split("\n");
  expect([kept.some((line) => line.startsWith("play = ")), kept.some((line) => line.startsWith("control = steps("))]).toEqual([true, true]);
});

test("play: a play line over the caps of its label is a fault, and a part that reads $/t with no play line is one", async () => {
  const long = await compileAppFile(['scene("Орбита", lang="ru")', `play = play("${"Д".repeat(BUDGET.control + 1)}", 0, 360, 14)`, 's1 = circle(150 + 90 * cos($/t), 150, 20, "accent")', 'card = card("Орбита", "Текст.")'].join("\n"));
  expect(long.faults.some((fault) => fault.startsWith("play: the label has"))).toBe(true);
  const alone = await compileAppFile(['scene("Орбита", lang="ru")', 's1 = circle(150 + 90 * cos($/t), 150, 20, "accent")', 'card = card("Орбита", "Текст.")'].join("\n"));
  expect(alone.faults.some((fault) => fault.includes("$/t"))).toBe(true);
});

test("play: the run-time text with the loop stays inside 57800 bytes (C4)", async () => {
  const { runtime } = await import("../src/emit.js");
  expect(new TextEncoder().encode(runtime.text).length).toBeLessThanOrEqual(57800);
});

test("play: the first state of a play scene is a template file whose picture moves at once (spec 9.2)", async () => {
  const result = await compileAppFile(['scene("Маятник", lang="ru")', 'play = play("Качание", -30, 30, 2)', 's1 = line(150, 40, 150 + 120 * sin($/t), 40 + 120 * cos($/t), "ink", 2)',
    'card = card("Маятник", "Груз качается.")'].join("\n"));
  expect(result.faults).toEqual([]);
  expect(result.definition.initialState.t_s.on).toBe(true);
  expect(T0).toBeGreaterThan(0);
});

const heart = ['scene("Как устроено сердце", lang="ru", palette="red")',
  'control = parts("Часть", ["полая вена", "правое предсердие", "правый желудочек", "лёгочная артерия", "левое предсердие", "левый желудочек", "аорта"])',
  's1 = use("heart", 150, 150, 220)', ...["Приносит в сердце кровь от тела.", "Принимает кровь от тела.", "Толкает кровь в лёгкие.", "Несёт кровь в лёгкие.",
    "Принимает кровь из лёгких.", "Толкает кровь ко всему телу.", "Несёт кровь ко всему телу."].map((text, index) => `say${index + 1} = ${JSON.stringify(text)}`)].join("\n");
/** The title and the text of the card of a scene in a state: the card texts are bound values. */
const cardOf = (ui, state) => every(ui).filter((node) => node.type === "text" && node.bind?.startsWith("d.card_")).map((node) => valueAt(state, node.bind));

test("diagram: a parts control over a ready object names each part, walks them with a stepper, and marks the part of the step", async () => {
  const result = await compileAppFile(heart);
  expect(result.faults).toEqual([]);
  const nodes = every(result.definition.ui), stepper = nodes.find((node) => node.type === "singleChoice");
  expect([stepper.variant, stepper.text, stepper.options.length, stepper.options[1].value]).toEqual(["stepper", "Часть", 7, "правое предсердие"]);
  // The first part shows first, with its name as the card title and its sentence as the card text.
  expect(valueAt(result.definition.initialState, stepper.bind)).toBe("полая вена");
  expect(cardOf(result.definition.ui, result.definition.initialState)).toEqual(["Полая вена", "Приносит в сердце кровь от тела."]);
  // The file stays short: the kept control names the parts, and no line holds a label or a condition of a mark.
  expect(result.kept).not.toContain("mark=");
  expect(result.kept).not.toContain("parts=true");
});

test("diagram: the marked part and the card follow the step", async () => {
  const result = await compileAppFile(heart), { definition } = result;
  const { app } = await clocked(result.expanded);
  const stepper = every(definition.ui).find((node) => node.type === "singleChoice");
  const moved = handle(request(definition.initialState, { type: "action", actionId: stepper.action, value: "лёгочная артерия" }), app);
  expect(cardOf(definition.ui, moved.state)).toEqual(["Лёгочная артерия", "Несёт кровь в лёгкие."]);
  // The accent ring of the image entry stands at the point of the pulmonary artery: one arc in the accent.
  const arcs = JSON.stringify(moved.surfaces).match(/"type":"arc"[^}]*"color":"accent"/g) ?? [];
  expect(arcs.length).toBe(1);
});

test("diagram: a ready object with no names in the control walks each of its parts in the order of its entry", async () => {
  const result = await compileAppFile(['scene("Глаз", lang="ru")', 'control = parts("Часть")', 's1 = use("eye", 150, 150, 200)'].join("\n"));
  expect(result.faults).toEqual([]);
  expect(every(result.definition.ui).find((node) => node.type === "singleChoice").options.map((option) => option.value)).toEqual(["роговица", "радужка", "хрусталик", "сетчатка", "зрительный нерв"]);
});

test("diagram: a ninth part is a fault, and a name that the object does not have is one", async () => {
  const names = Array.from({ length: 9 }, (_, index) => `"Часть ${index + 1}"`).join(", ");
  const nine = await compileAppFile(['scene("Вулкан", lang="ru")', `control = parts("Часть", [${names}])`, 's1 = polygon([60, 260, 150, 80, 240, 260], "brown")', 'card = card("Вулкан", "Гора с жерлом.")'].join("\n"));
  expect(nine.faults.some((fault) => fault.startsWith("control: the parts control has 9 parts, and its cap is 8"))).toBe(true);
  const wrong = await compileAppFile(['scene("Сердце", lang="ru")', 'control = parts("Часть", ["аорта", "клапан"])', 's1 = use("heart", 150, 150, 220)'].join("\n"));
  expect(wrong.faults.some((fault) => fault.startsWith('control: heart has no part "клапан"'))).toBe(true);
});

// Stage 7b (R3): a diagram of a thing with no ready object. The model draws it in the zone of FREE and gives each part a name and a point.
const VOLCANO = [["очаг магмы", 140, 266], ["жерло", 146, 190], ["кратер", 154, 100], ["лава", 190, 176], ["пепел", 140, 46]];
const freeFile = (points, shapes = ['s1 = polygon([84, 248, 134, 102, 166, 102, 216, 248], "wood", gradient="wood-2")', 's2 = circle(150, 46, 24, "muted", gradient="muted-2", radial=1)']) => [
  'scene("Как устроен вулкан", lang="ru", palette="orange")', `control = parts("Часть", [${points.map(([name, x, y]) => `part(${JSON.stringify(name)}, ${x}, ${y})`).join(", ")}])`,
  ...shapes, ...points.map(([name], index) => `say${index + 1} = "Это ${name}."`)].join("\n");
/** The drawing of a state: each text of a part name with its box, each box of a shape of the drawing (in the group that the kit adds), and each accent arc. */
async function drawingOf(result, state) {
  const { app } = await clocked(result.expanded);
  const surface = Object.values(handle(request(state, { type: "open" }), app).surfaces)[0], commands = surface.commands ?? surface;
  const group = commands.find((command) => command.type === "group"), [k, , , , tx, ty] = group?.transform ?? [1, 0, 0, 1, 0, 0];
  const inner = group ? group.children : commands.filter((command) => !["text", "line"].includes(command.type) && !(command.type === "circle" && command.radius === 2.5) && command.type !== "arc");
  const shapes = inner.map(boxOf).map(({ x, y, w, h }) => ({ x: tx + k * x, y: ty + k * y, w: k * w, h: k * h }));
  const names = commands.filter((command) => command.type === "text").map((command) => ({ ...command, box: { x: command.align === "end" ? command.x - lineWidth(command.text) : command.x, y: command.y - 7, w: lineWidth(command.text), h: 14 } }));
  const all = (list) => list.flatMap((command) => (command.type === "group" ? all(command.children) : [command]));
  const arcs = (color) => all(commands).filter((command) => command.type === "arc" && command.color === color);
  return { k, shapes, names, rings: arcs("accent"), halos: arcs("surface") };
}
const meets = (a, b) => a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;

test("diagram (stage 7b, R3): a free diagram of 5 parts names each part in a side column, and no name meets a shape of the drawing", async () => {
  const result = await compileAppFile(freeFile(VOLCANO));
  expect(result.faults).toEqual([]);
  // The kept file is the reply: the kit writes the names, so no text and no label= of a part is in it.
  expect(result.kept).not.toContain("text(");
  const { shapes, names } = await drawingOf(result, result.definition.initialState);
  expect(names.map((one) => one.text).join(" ")).toBe("пепел жерло очаг магмы кратер лава");
  const left = Math.min(...shapes.map((one) => one.x)), right = Math.max(...shapes.map((one) => one.x + one.w));
  for (const one of names) {
    expect(one.align === "end" ? one.x <= left - LABEL.gap : one.x >= right + LABEL.gap).toBe(true);
    expect(shapes.some((shape) => meets(one.box, shape))).toBe(false);
  }
});

test("diagram (stage 7b, R3): the ring, the accent name, and the card of a free diagram follow the step", async () => {
  const result = await compileAppFile(freeFile(VOLCANO)), { definition } = result;
  const { app } = await clocked(result.expanded);
  const stepper = every(definition.ui).find((node) => node.type === "singleChoice");
  expect([stepper.variant, stepper.options.map((option) => option.value)]).toEqual(["stepper", VOLCANO.map(([name]) => name)]);
  const at = async (state, [name]) => {
    const { rings, halos, names } = await drawingOf(result, state), dot = (await drawingOf(result, state)).names.find((one) => one.text === name.split(" ")[0]);
    expect(rings.length).toBe(1);
    // Failure case (stage 7b look): the dark accent ring was not visible on a part of the accent color. A wider surface ring lies under it.
    expect(halos.map(({ x, y, stroke }) => [x, y, stroke])).toEqual([[rings[0].x, rings[0].y, rings[0].stroke + FREE.halo]]);
    // The ring stands at the point of the part: its leader line starts there, at the height of the point.
    expect(Math.abs(rings[0].y + rings[0].height / 2 - VOLCANO.find(([one]) => one === name)[2])).toBeLessThan(0.01);
    expect(names.filter((one) => one.color === "accent").map((one) => one.text).join(" ")).toBe(name);
    return dot;
  };
  await at(definition.initialState, VOLCANO[0]);
  expect(cardOf(definition.ui, definition.initialState)).toEqual(["Очаг магмы", "Это очаг магмы."]);
  const moved = handle(request(definition.initialState, { type: "action", actionId: stepper.action, value: "лава" }), app);
  await at(moved.state, VOLCANO[3]);
  expect(cardOf(definition.ui, moved.state)).toEqual(["Лава", "Это лава."]);
});

test("diagram (stage 7b, R3): 8 names at the cap of 20 characters fit the picture, clear of the drawing", async () => {
  const names = ["верхний кратер горы1", "главное жерло вниз22", "очаг магмы под горой", "столб пепла и дым444", "лава течёт по склону", "старый слой пепла666", "боковой конус лавы77", "газы и пар над горой"];
  expect(names.every((name) => [...name].length === BUDGET.part)).toBe(true);
  const points = names.map((name, index) => [name, index % 2 ? 190 : 110, 30 + index * 32]);
  const result = await compileAppFile(freeFile(points));
  expect(result.faults).toEqual([]);
  const { k, shapes, names: shown } = await drawingOf(result, result.definition.initialState);
  expect(k).toBeGreaterThanOrEqual(FREE.fewest);
  for (const one of shown) {
    expect(one.box.x >= 0 && one.box.x + one.box.w <= 300 && one.box.y >= 0 && one.box.y + one.box.h <= 300).toBe(true);
    expect(shapes.some((shape) => meets(one.box, shape))).toBe(false);
  }
});

test("diagram (stage 7b, R3): plain names with no ready object, a point out of the zone, a long name, and names with no room are faults", async () => {
  const plain = await compileAppFile(['scene("Вулкан", lang="ru")', 'control = parts("Часть", ["Кратер", "Жерло"])', 's1 = polygon([84, 248, 150, 100, 216, 248], "wood")'].join("\n"));
  expect(plain.faults.some((fault) => fault.startsWith("control: with no ready object in the picture, write each part as part(NAME, X, Y)"))).toBe(true);
  const out = await compileAppFile(freeFile([["кратер", 150, 100], ["склон", 40, 200]]));
  expect(out.faults.some((fault) => fault.startsWith("control: the point of part 2 is at 40, 200: the drawing and each point stay from x = 80 to x = 220"))).toBe(true);
  const long = await compileAppFile(freeFile([["кратер", 150, 100], ["очень длинное имя части горы", 120, 200]]));
  expect(long.faults.some((fault) => fault.startsWith("control: part 2 has 28 characters, and its cap is 20"))).toBe(true);
  const crowded = await compileAppFile(freeFile(Array.from({ length: 6 }, (_, index) => [`Длинноеслово${index}aaa`, index % 2 ? 190 : 110, 40 + index * 40])));
  expect(crowded.faults.some((fault) => fault.startsWith("control: the names of the parts leave the drawing too little room"))).toBe(true);
  const mixed = await compileAppFile(freeFile([["кратер", 150, 100]]).replace('part("кратер", 150, 100)', 'part("кратер", 150, 100), "жерло"'));
  expect(mixed.faults.some((fault) => fault.startsWith("control: each part of a parts control has the same form"))).toBe(true);
});

test("diagram: a parts control takes 8 steps with a sentence each, and the narration of each step stays", async () => {
  const points = ["Кратер", "Жерло", "Очаг", "Пепел", "Лава", "Склон", "Конус", "Дым"].map((name, index) => [name, index % 2 ? 190 : 110, 30 + index * 32]);
  const result = await compileAppFile(freeFile(points));
  expect(result.faults).toEqual([]);
  expect(result.kept.split("\n").filter((line) => /^say\d = /.test(line)).length).toBe(8);
});

// Stage 7b (R3): the entries have Russian and English names only. In another language the model gives each name with part(ID, NAME).
const LUNGS = [["trachea", "Luftröhre"], ["bronchi", "Bronchien"], ["right_lung", "rechte Lunge"], ["left_lung", "linke Lunge"]];
const lungs = (items) => ['scene("Wie die Lunge aufgebaut ist", lang="de", palette="pink")', `control = parts("Teil", [${items}])`, 's1 = use("lungs", 150, 150, 220)',
  ...LUNGS.map(([, name], index) => `say${index + 1} = "Das ist die ${name}."`)].join("\n");

test("diagram (stage 7b, R3): a German file of a ready object shows its German names in the picture, the stepper, and the card", async () => {
  const result = await compileAppFile(lungs(LUNGS.map(([id, name]) => `part("${id}", "${name}")`).join(", "))), { definition } = result;
  expect(result.faults).toEqual([]);
  const stepper = every(definition.ui).find((node) => node.type === "singleChoice");
  expect(stepper.options.map((option) => option.value)).toEqual(LUNGS.map(([, name]) => name));
  const { names } = await drawingOf(result, definition.initialState);
  expect(names.map((one) => one.text).join(" ").split(" ").sort()).toEqual(LUNGS.flatMap(([, name]) => name.split(" ")).sort());
  expect(cardOf(definition.ui, definition.initialState)).toEqual(["Luftröhre", "Das ist die Luftröhre."]);
  const { app } = await clocked(result.expanded);
  const moved = handle(request(definition.initialState, { type: "action", actionId: stepper.action, value: "rechte Lunge" }), app);
  expect(cardOf(definition.ui, moved.state)).toEqual(["Rechte Lunge", "Das ist die rechte Lunge."]);
  const after = await drawingOf(result, moved.state);
  expect([after.rings.length, after.names.filter((one) => one.color === "accent").map((one) => one.text).join(" ")]).toEqual([1, "rechte Lunge"]);
});

test("diagram (stage 7b, R3): a ready object in a language with no names of its parts is a fault that asks for the names", async () => {
  const ids = await compileAppFile(lungs(LUNGS.map(([id]) => `"${id}"`).join(", ")));
  expect(ids.faults.some((fault) => fault.startsWith('control: the kit has no names in the language "de" for the parts of lungs: write each part as part(ID, NAME)'))).toBe(true);
  // Russian and English keep the ids only, and a name that the model gives there is a name too.
  const russian = await compileAppFile(['scene("Лёгкие", lang="ru")', 'control = parts("Часть", ["trachea", "bronchi"])', 's1 = use("lungs", 150, 150, 220)'].join("\n"));
  expect(russian.faults).toEqual([]);
});
