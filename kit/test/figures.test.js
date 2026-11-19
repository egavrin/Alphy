// The figure part in the look of the baseline apps (Task 11b-2a, E): the hero card, then in each place a grid of two figures or rows of three or more.

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

test("figures: two figures after the main one are a grid of two metrics with their icons, and three or more are rows of one size", async () => {
  // Fix round 1: a grid of two small figures beside a large row looked uneven (mock-units-pl), so a place has a grid or rows, never both.
  const two = await built('app("Przelicznik", lang="pl")', 'need g = figure("number", "Gramy", 125, example=[{}, 125])', 'need ilosc = input("number", "Ilość", 1)',
    'need gestosc = figure("number", "Gęstość", 0.5, icon="Weight", example=[{}, 0.5])', 'need ml = figure("number", "Mililitry", 250, icon="Drop", example=[{}, 250])');
  const [, , grid] = tops(two.definition);
  expect([shape(grid), grid.columns, grid.children.map((node) => [node.text, node.icon])]).toEqual([["grid", ["metric", "metric"]], 2, [["Gęstość", "Weight"], ["Mililitry", "Drop"]]]);
  const { definition } = await built('app("Przelicznik", lang="pl")', 'need g = figure("number", "Gramy", 125, example=[{}, 125])', 'need ilosc = input("number", "Ilość", 1)',
    'need ml = figure("number", "Mililitry", 250, example=[{}, 250])', 'need szklanki = figure("number", "Szklanki", 1, example=[{}, 1])',
    'need lyzki = figure("number", "Łyżki", $/ilosc * 50 / 3, example=[{ilosc: 1}, 16.67])');
  const nodes = tops(definition);
  expect(nodes.map(shape)).toEqual([["card", ["metric"]], "numberInput", ...Array(3).fill(["row", ["text", "text"]])]);
  for (const [row, title] of nodes.slice(2).map((node, index) => [node, ["Mililitry", "Szklanki", "Łyżki"][index]])) {
    expect(row.children).toMatchObject([{ type: "text", text: title, layout: { weight: 1 } }, { type: "text", style: { emphasis: "number" } }]);
  }
  expect(definition.initialState.d[nodes[4].children[1].bind.slice(2)]).toBe("16,67");
});

test("figures: the figures of one group of steps or tabs are a set of their own, and a figure never leaves its group", async () => {
  const head = ['app("T", lang="en")', 'need h = figure("number", "H", 1, example=[{}, 1])', 'need a = figure("number", "A", 2, example=[{}, 2])', 'need b = figure("number", "B", 3, example=[{}, 3])'];
  // Two figures in one step: a grid in that step.
  const one = await planned([...head, 'need n = input("number", "N", 1)', 'need p = steps(["One", "Two"], [[a, b], [n]])'].join("\n"));
  expect([report(one).faults, one.get("p_step0").raw, one.get("a_grid")?.raw]).toEqual([[], 'p_step0 = Column([a_grid], visible=$/p_step == 0)', "a_grid = Grid([a_view, b_view], columns=2)"]);
  // Two figures in two steps: one in each step, each a metric (the reviewer's plan put both into step 1 and left step 2 empty).
  const apart = await planned([...head, 'need p = steps(["One", "Two"], [[a], [b]])'].join("\n"));
  expect([report(apart).faults, apart.get("p_step0").raw, apart.get("p_step1").raw]).toEqual([[], "p_step0 = Column([a_view], visible=$/p_step == 0)", "p_step1 = Column([b_view], visible=$/p_step == 1)"]);
  expect(apart.get("a_view").raw).toStartWith('a_view = Metric("A"');
  // The reviewer's tabs plan: the main figure and a in one tab, b in the other. It has no fault.
  const tabbed = await planned([...head, 'need p = tabs(["X", "Y"], [[h, a], [b]])'].join("\n"));
  expect([report(tabbed).faults, tabbed.get("p_view").raw]).toEqual([[], 'p_view = Tabs(["X", "Y"], [[h_view, a_view], [b_view]], label=join(["X", "Y"]))']);
});

test("figures: one figure beside the main one, or two that do not stand one after the other, stay metrics", async () => {
  const one = await built('app("T", lang="en")', 'need a = figure("number", "A", 1, example=[{}, 1])', 'need b = figure("number", "B", 2, icon="Drop", example=[{}, 2])');
  expect(tops(one.definition).map(shape)).toEqual([["card", ["metric"]], "metric"]);
  expect(tops(one.definition)[1].icon).toBe("Drop");
  const apart = await built('app("T", lang="en")', 'need a = figure("number", "A", 1, example=[{}, 1])', 'need b = figure("number", "B", 2, example=[{}, 2])',
    'need n = input("number", "N", 1)', 'need c = figure("number", "C", 3, example=[{}, 3])');
  expect(tops(apart.definition).map(shape)).toEqual([["card", ["metric"]], "metric", "numberInput", "metric"]);
});

test("figures: unit= follows the number of each figure, and goalText= is the caption of the bar of the main figure", async () => {
  // Task 11b-2a pictures: the baseline shows "0 ml" and "달성률: 0%", and the plan showed "0" and the title of the figure again beside the bar.
  const { definition } = await built('app("물", lang="ko")', 'need ml = input("number", "물", 500)',
    'need today = figure("number", "오늘 마신 양", $/ml, unit="ml", goal=2000, goalText="달성률", example=[{ml: 750}, 750])',
    'need left = figure("number", "남은 양", 2000 - $/ml, unit="ml", example=[{ml: 500}, 1500])');
  const [hero] = tops(definition), left = tops(definition).find((node) => node.type === "metric");
  expect(hero.children.map((node) => [node.type, node.text])).toEqual([["metric", "오늘 마신 양"], ["progress", "달성률"]]);
  expect([hero.children[0], left].map((node) => definition.initialState.d[node.bind.slice(2)])).toEqual(["500 ml", "1,500 ml"]);
  // The value of the figure stays a number: the bar and the example read it.
  expect(definition.initialState.d[hero.children[1].bind.slice(2)]).toBe(25);
});

test("figures: a new figure writes the figures before it again in their new place, and note= or goal= on a figure that is not the main one is a fault", async () => {
  const file = await planned(['app("T", lang="en")', 'need a = figure("number", "A", 1, example=[{}, 1])', 'need b = figure("number", "B", 2, example=[{}, 2])'].join("\n"));
  file.feed('need c = figure("number", "C", 3, example=[{}, 3])');
  await expand(file);
  expect(tops(emit(file).definition).map(shape)).toEqual([["card", ["metric"]], ["grid", ["metric", "metric"]]]);
  for (const option of ['note="x"', "goal=10", 'goalText="x"']) {
    const line = `need b = figure("number", "B", 2, ${option})`;
    expect(report(await planned(`app("T")\nneed a = figure("number", "A", 1)\n${line}`)).faults)
      .toContain(`line: need b: note=, goal=, and goalText= show only on the main figure, and b is not it: ${line}`);
  }
  // A row shows no icon (fix round 1, G.4): the part said no word when it dropped icon= of a row.
  const line = 'need c = figure("number", "C", 3, icon="Drop")';
  expect(report(await planned(['app("T")', 'need h = figure("number", "H", 0)', 'need a = figure("number", "A", 1)', 'need b = figure("number", "B", 2)', line].join("\n"))).faults)
    .toContain(`line: need c: c is a row, because three figures or more in one place are rows of one size, and a row shows no icon. Remove icon=: ${line}`);
});
