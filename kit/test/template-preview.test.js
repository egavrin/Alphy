// The preview step (stage templates plan, Task 13): the frame of the complete lines so far, with no check run, and the frame of the compile.

import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { compileAppFile, compileStep, previewStep } from "../src/compile.js";
import { settings } from "../src/core.js";
import { runtime } from "../src/emit.js";
import { every, plan } from "./helpers.js";

const template = (name) => readFileSync(new URL(`./fixtures/template/${name}.plan`, import.meta.url), "utf8");
const ship = template("ship");
/** The first n lines of a text. */
const first = (text, n) => text.trim().split("\n").slice(0, n).join("\n") + "\n";
const put = (text, name, line) => text.replace(new RegExp(`^${name} = .*$`, "m"), line);
const drop = (text, name) => text.replace(new RegExp(`^${name} = .*\\n`, "m"), "");
const preview = (text, more) => previewStep({ text, plan: null, previous: null, ...more });
/** The frame of a preview that must give one. */
const frameOf = async (text, more) => {
  const step = await preview(text, more);
  expect(step.kind).toBe("preview");
  return step.frame;
};
const commands = (frame) => Object.values(frame.drawing).flatMap((surface) => surface.commands);
const nodes = (frame, type) => every(frame.ui).filter((node) => node.type === type);

test("theFirstLineAloneGivesAFrame", async () => {
  const frame = await frameOf(first(ship, 1));
  expect(frame.holds).toBe("frame");
  expect(frame.definition.surfaces.map(({ id, width, height }) => [id, width, height])).toEqual([[Object.keys(frame.drawing)[0], 300, 220]]);
  expect(Object.keys(frame.drawing)).toHaveLength(1);
  expect(commands(frame)).toEqual([]);
  // The title is the text of the picture, in the picture card.
  expect(every(frame.ui).map((node) => node.type)).toEqual(["theme", "column", "card", "canvas"]);
  expect([frame.definition.title, nodes(frame, "canvas")[0].text]).toEqual(["Почему корабль не тонет?", "Почему корабль не тонет?"]);
});

test("eachLineAddsItsPart", async () => {
  const control = await frameOf(first(ship, 2));
  expect([control.holds, nodes(control, "segmented").map((node) => node.text), commands(control)]).toEqual(["frame", ["Предмет"], []]);
  const s1 = await frameOf(first(ship, 3));
  expect([s1.holds, commands(s1).length]).toEqual(["parts", 1]);
  const card = await frameOf(ship);
  expect([card.holds, nodes(card, "card").length, commands(card).length]).toEqual(["card", 2, 3]);
});

test("aFaultyLineIsLeftOutOfThePreview", async () => {
  // The kit refuses this shape when it draws the picture.
  const frame = await frameOf(put(ship, "s2", "s2 = polygon(45, 75, 255)"));
  expect(commands(frame).map((command) => command.type)).toEqual(["rect", "text"]);
  expect(frame.drawing).toEqual((await frameOf(drop(ship, "s2"))).drawing);
});

test("thePreviewRunsNoCheckRun", async () => {
  // No line reads the slider yet: the compile leaves this control out, and the preview keeps it for the parts that come later.
  const still = ['scene("Круг", lang="ru")', 'control = slider("Размер", 0, 10, 1, 4)', 's1 = circle(150, 110, 40, "accent")'].join("\n") + "\n";
  const frame = await frameOf(still);
  expect(nodes(frame, "slider").map((node) => node.text)).toEqual(["Размер"]);
  expect(settings.gate).toBe("smoke");
});

test("anItemsFrameShowsOneItem", async () => {
  const frame = await frameOf(first(template("word-parts"), 3));
  expect(frame.holds).toBe("parts");
  // The sign and the label of the first item.
  expect(commands(frame).map((command) => command.text)).toEqual(["пере-", "Приставка"]);
  expect(nodes(frame, "segmented")[0].options.map((option) => option.text)).toEqual(["Приставка"]);
});

test("aCellsFrameDrawsAtTheControl", async () => {
  const cells = template("majority"), head = await frameOf(first(cells, 1));
  expect([head.holds, commands(head)]).toEqual(["frame", []]);
  // The 20 cells, 11 of them filled, at the start of the slider.
  const control = await frameOf(first(cells, 2)), cellColors = commands(control).filter((command) => command.type === "roundRect").map((command) => command.color);
  expect([control.holds, cellColors.length, cellColors.filter((color) => color === "accent").length, nodes(control, "slider").length]).toEqual(["parts", 20, 11, 1]);
});

test("aChangePreviewMergesOverTheStoredFile", async () => {
  const { definition } = await compileAppFile(ship), field = Object.keys(definition.initialState).find((key) => definition.initialState[key] === "Корабль");
  const previous = { appFile: ship, stateSchema: JSON.stringify(definition.stateSchema), state: JSON.stringify({ ...definition.initialState, [field]: "Гвоздь" }) };
  const frame = await frameOf('s4 = circle(150, 180, 15, "sun", visible=$/v == "Гвоздь")', { plan: ship, previous });
  expect(frame.state[field]).toBe("Гвоздь");
  // The water and the shape of s4: the ship and its text show only for "Корабль".
  expect(commands(frame).map((command) => command.type)).toEqual(["rect", "circle"]);
});

test("aFreePlanPreviewIsWhole", async () => {
  const frame = await frameOf(plan("calc"));
  expect(frame.holds).toBe("whole");
  expect(frame.ui).toEqual(frame.definition.ui);
});

test("anUnusableFirstLineGivesNone", async () => {
  const sky = ship.replace('palette="blue"', 'palette="sky"');
  for (const text of [sky, first(sky, 1), "", 'scene("Почему', undefined]) expect([text, await preview(text)]).toEqual([text, { kind: "none" }]);
  // A stored state that is no JSON text gives none too, and the gate comes back.
  expect(await preview("s4 = circle(1, 2, 3)", { plan: ship, previous: { appFile: ship, stateSchema: "{", state: "{" } })).toEqual({ kind: "none" });
  expect(settings.gate).toBe("smoke");
});

test("theCompileResultCarriesTheFrame", async () => {
  const step = await compileStep({ text: ship, plan: null, read: true, previous: null, cap: 65536 });
  expect(step.result.frame.holds).toBe("whole");
  expect(step.result.frame.drawing).toEqual((await frameOf(ship)).drawing);
  // A free plan too.
  const free = await compileStep({ text: plan("calc"), plan: null, read: false, previous: null, cap: 65536 });
  expect([free.result.faults, free.result.frame.holds]).toEqual([[], "whole"]);
});

test("theCompileFrameHoldsNoSecondDefinition", async () => {
  // The host takes the definition of a compile from result.definition, so the reply holds the source of the run-time once.
  const step = await compileStep({ text: ship, plan: null, read: true, previous: null, cap: 65536 });
  expect(step.result.frame.definition).toBeUndefined();
  expect(step.result.definition).not.toBeNull();
  expect(JSON.stringify(step).split(JSON.stringify(runtime.text).slice(1, -1)).length - 1).toBe(1);
});
