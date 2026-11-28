// Narration lines and the steps control of a template file (plan "one scene", Task 25, decisions 2 and 3): a narration line draws nothing, a
// line of the wrong kind or with no sentence end in 110 characters is left out with no fault, and a steps control starts at its answer step.

import { expect, test } from "bun:test";
import { compileAppFile, compileStep } from "../src/compile.js";
import { every } from "./helpers.js";

const FAZA = ['scene("Дыхание", lang="ru", palette="teal")', 'control = steps("Фаза", ["Вдох", "Выдох", "Пауза"], "Выдох")',
  's1 = circle(150, 110, $/v == "Вдох" ? 60 : 30, "accent")', 's2 = text(150, 30, $/v, "ink", 14)', 's3 = rect(0, 200, 300, 20, "muted", 0)'];
const SAY = ['say1 = "Сначала вдох: лёгкие растут."', 'say2 = "Потом выдох: они сжимаются."', 'say3 = "В паузе всё замирает."'];
const file = (lines) => lines.join("\n") + "\n";
/** The text with the line NAME in place of the line of that name. */
const put = (lines, name, line) => lines.map((one) => (one.startsWith(`${name} = `) ? line : one));
/** The choice of the expanded app: its start value and its options. */
const choiceOf = (result) => {
  const node = every(result.definition.ui).find((one) => one.action === "v_field");
  return { start: result.definition.initialState[node.bind.replace(/^\$\//, "")], options: node.options.map((option) => option.text) };
};
const SLIDER = ['scene("Рычаг", lang="ru")', 'control = slider("Плечо", 1, 10, 1, 5)', 's1 = rect(20, 100, $/v * 20, 10, "ink")', 'caption = "Длиннее плечо, меньше сила."'];
const CHOICE = ['scene("Корабль", lang="ru")', 'control = choice("Предмет", ["Корабль", "Гвоздь"])', 's1 = rect(0, 110, 300, 110, "water", 0, visible=$/v == "Корабль")',
  'caption = "Полый корабль плавает."'];
const PLANETS = ['items("Планеты", lang="ru")', 'control = steps("Планета", "Марс")', 'i1 = item("Земля", "З", "Наш дом.")', 'i2 = item("Марс", "М", "Красная планета.")',
  'i3 = item("Венера", "В", "Самая горячая.")'];

test("a steps scene starts at its answer step", async () => {
  const result = await compileAppFile(file([...FAZA, ...SAY]));
  expect([result.faults, result.leftOut]).toEqual([[], []]);
  expect(choiceOf(result)).toEqual({ start: "Выдох", options: ["Вдох", "Выдох", "Пауза"] });
});

test("the answer step is the last step by default", async () => {
  const result = await compileAppFile(file(put(FAZA, "control", 'control = steps("Фаза", ["Вдох", "Выдох", "Пауза"])')));
  expect(result.faults).toEqual([]);
  expect(choiceOf(result).start).toBe("Пауза");
});

test("the narration lines come last in kept", async () => {
  // The model wrote the narration before the last shape: the kept text puts it last.
  const result = await compileAppFile(file([...FAZA.slice(0, 4), ...SAY, FAZA[4]]));
  expect(result.faults).toEqual([]);
  const kept = result.kept.trim().split("\n");
  expect(kept.slice(-3)).toEqual(["Сначала вдох: лёгкие растут.", "Потом выдох: они сжимаются.", "В паузе всё замирает."].map((text, index) => `say${index + 1} = ${JSON.stringify(text)}`));
  expect(kept.at(-4)).toBe(FAZA[4]);
  // A sentence with a quote mark and a backslash comes back as a JSON string that the kit reads again.
  const quoted = await compileAppFile(file([...FAZA, 'say1 = "Скажи \\"вдох\\" и «выдох»."']));
  expect(quoted.kept.trim().split("\n").at(-1)).toBe(`say1 = ${JSON.stringify('Скажи "вдох" и «выдох».')}`);
  expect((await compileAppFile(quoted.kept)).kept).toBe(quoted.kept);
});

test("a long narration line is cut at a sentence end", async () => {
  const first = "Сначала вдох: воздух входит в лёгкие, и они растут.", text = `${first} ${"Потом идёт выдох, и воздух выходит наружу из лёгких сам".padEnd(150 - first.length - 2, "ш")}.`;
  expect([...text].length).toBe(150);
  const result = await compileAppFile(file([...FAZA, `say1 = "${text}"`]));
  expect([result.faults, result.leftOut]).toEqual([[], []]);
  expect(result.kept.trim().split("\n").at(-1)).toBe(`say1 = ${JSON.stringify(first)}`);
});

test("the build leaves out a narration line with no sentence end in 110 characters", async () => {
  const result = await compileAppFile(file([...FAZA, `say1 = "${"ш".repeat(130)}."`, 'say2 = "Потом выдох"', SAY[2]]));
  expect([result.faults, result.leftOut.sort()]).toEqual([[], ["say1", "say2"]]);
  expect(result.definition).not.toBeNull();
  expect(result.kept.trim().split("\n").at(-1)).toBe(SAY[2]);
});

test("the build leaves out a narration line of the wrong kind", async () => {
  const steps = await compileAppFile(file([...FAZA, 'say = "Нажимай шаги."', SAY[0], 'say4 = "Четвёртого шага нет."']));
  expect([steps.faults, steps.leftOut.sort()]).toEqual([[], ["say", "say4"]]);
  const slider = await compileAppFile(file([...SLIDER, 'say1 = "Двигай плечо."', 'say = "Двигай плечо и смотри на силу."']));
  expect([slider.faults, slider.leftOut]).toEqual([[], ["say1"]]);
  // A narration line that is no text draws nothing and is left out too.
  const number = await compileAppFile(file([...SLIDER, "say = 5"]));
  expect([number.faults, number.leftOut]).toEqual([[], ["say"]]);
});

test("a choice and a slider take one say line", async () => {
  for (const lines of [CHOICE, SLIDER]) {
    const result = await compileAppFile(file([...lines, 'say = "Выбери и смотри на картинку."']));
    expect([result.faults, result.leftOut]).toEqual([[], []]);
    expect(result.kept.trim().split("\n").at(-1)).toBe('say = "Выбери и смотри на картинку."');
  }
});

test("a steps scene with no narration is clean", async () => {
  const result = await compileAppFile(file(FAZA));
  expect([result.faults, result.leftOut, result.kept]).toEqual([[], [], file(FAZA)]);
});

test("items take steps over their labels", async () => {
  const result = await compileAppFile(file([...PLANETS, 'say1 = "Это Земля."', 'say2 = "Это Марс."', 'say3 = "Это Венера."', 'say4 = "Лишний."']));
  expect([result.faults, result.leftOut]).toEqual([[], ["say4"]]);
  expect(choiceOf(result)).toEqual({ start: "Марс", options: ["Земля", "Марс", "Венера"] });
  const last = await compileAppFile(file(put(PLANETS, "control", 'control = steps("Планета")')));
  expect([last.faults, choiceOf(last).start]).toEqual([[], "Венера"]);
});

test("the merge puts a new narration line last and replaces one by name", async () => {
  const stored = await compileAppFile(file([...FAZA, SAY[0], SAY[1]])), previous = { appFile: stored.kept, stateSchema: JSON.stringify(stored.definition.stateSchema), state: JSON.stringify(stored.definition.initialState) };
  const changed = await compileStep({ text: ['say2 = "Затем выдох."', 'say3 = "И пауза."'].join("\n"), plan: stored.kept, read: true, previous, cap: 65536 });
  expect(changed.result.faults).toEqual([]);
  expect(changed.result.kept.trim().split("\n").slice(-3)).toEqual([SAY[0], 'say2 = "Затем выдох."', 'say3 = "И пауза."']);
});
