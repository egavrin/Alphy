// The figure part in the look of the baseline apps (Task 11b-2a, E): the hero card, a grid of the next two figures, and rows after them.

import { expect, test } from "bun:test";
import { report } from "../src/check.js";
import { emit } from "../src/emit.js";
import { expand } from "../src/parts.js";
import { built, planned } from "./helpers.js";

/** The nodes of the first screen in their order. */
const tops = (definition) => definition.ui.children[0].children;
/** A node as its type, and the type of each child. */
const shape = (node) => (node.children ? [node.type, node.children.map(shape)] : node.type);

test("figures: the main figure is a hero card of a large emoji, a display metric, a bar to its goal, and a muted note", async () => {
  const { definition } = await built('app("물", lang="ko")', 'need ml = input("number", "물 (ml)", 500)',
    'need today = figure("number", "오늘 마신 물", $/ml, icon="💧", goal=2000, note="하루 목표 2,000 ml", example=[{ml: 750}, 750])');
  const [hero] = tops(definition);
  expect(hero).toMatchObject({ type: "card", variant: "hero", children: [
    { type: "emoji", text: "오늘 마신 물", value: "💧", size: "large" },
    { type: "metric", text: "오늘 마신 물", style: { emphasis: "display" } },
    { type: "progress", text: "오늘 마신 물", variant: "bar", min: 0, max: 100 },
    { type: "text", text: "하루 목표 2,000 ml", style: { tone: "muted" } },
  ] });
  expect(hero.children[1].icon).toBeUndefined();
  expect(definition.initialState.d[hero.children[2].bind.slice(2)]).toBe(25);
  // A name of a host icon is the icon of the metric, and a note can be a formula.
  const named = await built('app("T", lang="en")', 'need n = input("number", "N", 3)', 'need f = figure("number", "F", $/n * 2, icon="Drop", note="of " + text($/n), example=[{n: 1}, 2])');
  const [card] = tops(named.definition);
  expect([shape(card), card.children[0].icon, typeof card.children[1].bind]).toEqual([["card", ["metric", "text"]], "Drop", "string"]);
  // A formula that gives an emoji is the sign too.
  const chosen = await built('app("T", lang="en")', 'need n = input("number", "N", 3)', 'need f = figure("number", "F", $/n, icon=$/n > 2 ? "🌾" : "💧", example=[{n: 1}, 1])');
  const [emoji] = tops(chosen.definition)[0].children;
  expect([emoji.type, emoji.text, chosen.definition.initialState.d[emoji.bind.slice(2)]]).toEqual(["emoji", "F", "🌾"]);
});

test("figures: the two figures after the main one are a grid of two metrics with their icons, and each further figure is a row of its title and its value", async () => {
  const { definition } = await built('app("Przelicznik", lang="pl")', 'need g = figure("number", "Gramy", 125, example=[{}, 125])',
    'need ilosc = input("number", "Ilość", 1)',
    'need gestosc = figure("number", "Gęstość", 0.5, icon="Weight", example=[{}, 0.5])', 'need ml = figure("number", "Mililitry", 250, icon="Drop", example=[{}, 250])',
    'need szklanki = figure("number", "Szklanki", 1, example=[{}, 1])', 'need lyzki = figure("number", "Łyżki", $/ilosc * 50 / 3, example=[{ilosc: 1}, 16.67])');
  const nodes = tops(definition);
  expect(nodes.map(shape)).toEqual([["card", ["metric"]], "numberInput", ["grid", ["metric", "metric"]], ["row", ["text", "text"]], ["row", ["text", "text"]]]);
  const [, , grid, row, last] = nodes;
  expect([grid.columns, grid.children.map((node) => [node.text, node.icon])]).toEqual([2, [["Gęstość", "Weight"], ["Mililitry", "Drop"]]]);
  expect(row.children).toMatchObject([{ type: "text", text: "Szklanki", layout: { weight: 1 } }, { type: "text", style: { emphasis: "number" } }]);
  expect(definition.initialState.d[last.children[1].bind.slice(2)]).toBe("16,67");
});

test("figures: one figure beside the main one, or two that do not stand one after the other, stay metrics", async () => {
  const one = await built('app("T", lang="en")', 'need a = figure("number", "A", 1, example=[{}, 1])', 'need b = figure("number", "B", 2, icon="Drop", example=[{}, 2])');
  expect(tops(one.definition).map(shape)).toEqual([["card", ["metric"]], "metric"]);
  expect(tops(one.definition)[1].icon).toBe("Drop");
  const apart = await built('app("T", lang="en")', 'need a = figure("number", "A", 1, example=[{}, 1])', 'need b = figure("number", "B", 2, example=[{}, 2])',
    'need n = input("number", "N", 1)', 'need c = figure("number", "C", 3, example=[{}, 3])');
  expect(tops(apart.definition).map(shape)).toEqual([["card", ["metric"]], "metric", "numberInput", "metric"]);
});

test("figures: a new figure writes the figures before it again in their new place, and note= or goal= on a figure that is not the main one is a fault", async () => {
  const file = await planned(['app("T", lang="en")', 'need a = figure("number", "A", 1, example=[{}, 1])', 'need b = figure("number", "B", 2, example=[{}, 2])'].join("\n"));
  file.feed('need c = figure("number", "C", 3, example=[{}, 3])');
  await expand(file);
  expect(tops(emit(file).definition).map(shape)).toEqual([["card", ["metric"]], ["grid", ["metric", "metric"]]]);
  for (const option of ['note="x"', "goal=10"]) {
    const line = `need b = figure("number", "B", 2, ${option})`;
    expect(report(await planned(`app("T")\nneed a = figure("number", "A", 1)\n${line}`)).faults)
      .toContain(`line: need b: note= and goal= show only on the main figure, and b is not it: ${line}`);
  }
});
