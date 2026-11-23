// Stage 7 of block 2 (docs/superpowers/plans/2026-11-22-block-2-forms-library-motion.md): the play control of a scene (M1, M2, M7) and the
// diagram over the parts of an object (G1, G2, C2).

import { expect, test } from "bun:test";
import { compileAppFile } from "../src/compile.js";
import { handle } from "../src/run.js";
import { BUDGET } from "../src/template.js";
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

test("diagram: a thing with no entry marks the label of the step in its free shapes, and a name over 16 characters is a fault", async () => {
  const volcano = ['scene("Как устроен вулкан", lang="ru", palette="orange")', 'control = parts("Часть", ["Кратер", "Жерло", "Очаг магмы", "Пепел", "Лава"])',
    's1 = polygon([40, 270, 150, 90, 260, 270], "brown")', 's2 = use("leader_label", 150, 95, 40, label="Кратер")', 's3 = text(60, 200, "Жерло", "ink", 13)',
    's4 = use("leader_label", 150, 250, 40, label="Очаг магмы")', 's5 = use("leader_label", 120, 50, 40, label="Пепел")', 's6 = use("leader_label", 220, 200, 40, label="Лава")',
    'say1 = "Кратер на вершине."', 'say2 = "Жерло ведёт вниз."', 'say3 = "Внизу очаг магмы."', 'say4 = "Пепел летит вверх."', 'say5 = "Лава течёт по склону."'].join("\n");
  const result = await compileAppFile(volcano);
  expect(result.faults).toEqual([]);
  const { app } = await clocked(result.expanded);
  const stepper = every(result.definition.ui).find((node) => node.type === "singleChoice");
  const accented = (state) => JSON.stringify(state.surfaces ?? handle(request(state, { type: "open" }), app).surfaces).match(/"text":"[^"]+"[^}]*"color":"accent"/g)?.map((one) => /"text":"([^"]+)"/.exec(one)[1]);
  expect(accented(result.definition.initialState)).toEqual(["Кратер"]);
  const shown = handle(request(result.definition.initialState, { type: "action", actionId: stepper.action, value: "Жерло" }), app);
  expect(accented(shown)).toEqual(["Жерло"]);
  const long = await compileAppFile(['scene("Вулкан", lang="ru")', 'control = parts("Часть", ["Кратер", "Очень длинное имя части"])', 's1 = polygon([60, 260, 150, 80, 240, 260], "brown")'].join("\n"));
  expect(long.faults.some((fault) => fault.startsWith("control: part 2 has 23 characters, and its cap is 16"))).toBe(true);
});

test("diagram: a parts control takes 8 steps with a sentence each, and the narration of each step stays", async () => {
  const names = ["Кратер", "Жерло", "Очаг", "Пепел", "Лава", "Склон", "Конус", "Дым"];
  const result = await compileAppFile(['scene("Вулкан", lang="ru")', `control = parts("Часть", ${JSON.stringify(names)})`, 's1 = polygon([60, 260, 150, 80, 240, 260], "brown")',
    ...names.map((name, index) => `say${index + 1} = "${name} здесь."`)].join("\n"));
  expect(result.faults).toEqual([]);
  expect(result.kept.split("\n").filter((line) => /^say\d = /.test(line)).length).toBe(8);
});
