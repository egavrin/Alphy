// The counter part in the look of the baseline apps (Task 11b-2a, F): a less and a more button around a large value, or one button that adds a step.

import { expect, test } from "bun:test";
import { built, every, press } from "./helpers.js";

/** The nodes of the first screen in their order. */
const tops = (definition) => definition.ui.children[0].children;

test("counter: a section of a row of an outlined less button, the value large in the middle, and a filled more button", async () => {
  const { definition, app } = await built('app("Ricetta", lang="it")', 'need porzioni = counter("Porzioni", 2, min=1)');
  const [section] = tops(definition);
  expect(section).toMatchObject({ type: "section", text: "Porzioni", children: [{ type: "row", wrap: false, children: [
    { type: "button", text: "Meno", icon: "Minus", variant: "outlined", action: "porzioni_minus" },
    { type: "text", style: { emphasis: "display", align: "center" }, layout: { weight: 1 } },
    { type: "button", text: "Più", icon: "Plus", variant: "filled", action: "porzioni_plus" },
  ] }] });
  const more = press(definition.initialState, app, "porzioni_plus");
  expect([more.state.porzioni, press(press(definition.initialState, app, "porzioni_minus").state, app, "porzioni_minus").state.porzioni]).toEqual([3, 1]);
  // The author's captions win over the words of the table.
  const own = await built('app("T", lang="en")', 'need n = counter("N", 0, lessText="Fewer", moreText="Again")');
  expect(tops(own.definition)[0].children[0].children.map((node) => node.text).filter(Boolean)).toEqual(["Fewer", "Again"]);
});

// L1 item 8 (wide study W-K1): at about 600 dp the less button stood at the left edge, the value in the middle, and the more button at the right edge.
test("counter (L1 item 8): the less button, the value, and the more button are one group of at most 360 dp, so on a wide screen they stay together", async () => {
  const { definition } = await built('app("물", lang="ko")', 'need ml = counter("물 (ml)", 0, step=250)');
  const row = tops(definition)[0].children[0];
  // The content of the phone is about 346 dp wide, so there the group fills the line as before.
  expect([row.type, row.layout, row.children.map((node) => node.layout)]).toEqual(["row", { maxWidth: 360 }, [undefined, { weight: 1 }, undefined]]);
});

test("run (L1 item 8): a row with max= and a weight keeps both in its layout", async () => {
  const { definition } = await built('app("T")', "$/n = 1", 'root = Row([Row([Text("a"), Text("b", weight=1)], max=200, weight=1), Text("c")])');
  expect(every(definition.ui).filter((node) => node.layout?.maxWidth).map((node) => [node.type, node.layout])).toEqual([["row", { maxWidth: 200, weight: 1 }]]);
});

test("counter: addText= makes one filled button that adds the step, with its icon, and a text button that resets the value", async () => {
  const { definition, app } = await built('app("물", lang="ko")', 'need ml = counter("물 (ml)", 0, step=250, max=500, addText="컵 추가 (250 ml)", icon="Cup")');
  const [view] = tops(definition);
  expect(view.children).toEqual([
    { type: "button", text: "컵 추가 (250 ml)", action: "ml_plus", variant: "filled", icon: "Cup" },
    { type: "button", text: "초기화", action: "ml_reset", variant: "text", icon: "Refresh" },
  ]);
  const twice = press(press(definition.initialState, app, "ml_plus").state, app, "ml_plus");
  expect([twice.state.ml, press(twice.state, app, "ml_plus").state.ml, press(twice.state, app, "ml_reset").state.ml]).toEqual([500, 500, 0]);
  // With no icon= the add button has a plus.
  const plain = await built('app("T", lang="en")', 'need n = counter("N", 0, addText="Add one")');
  expect(tops(plain.definition)[0].children[0].icon).toBe("Plus");
});
