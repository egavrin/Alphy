// The counter part in the look of the baseline apps (Task 11b-2a, F): a less and a more button around a large value, or one button that adds a step.

import { expect, test } from "bun:test";
import { built, press } from "./helpers.js";

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
