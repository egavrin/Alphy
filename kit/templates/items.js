// The line table of the ready plan items.plan (kit/README.md, section "Ready plans"): the items i1 to i6 and a choice of their labels. The
// drawing shows the sign and the label of the chosen item, and the caption under the choice is its text (stage A, K1).

import { show } from "../src/dsl.js";
import { BUDGET, CONTROL_LABEL, CONTROL_LINE, CONTROL_VALUE, NARRATION, optionFault } from "../src/form-base.js";

const CONTROL = "choice(LABEL), or steps(LABEL, ANSWER): its options are the labels of the items";
const ITEM = /^i[1-6]$/;

export const ITEMS = {
  word: "items",
  text: "control and i1 to i6 (at most 6 items)",
  values: [CONTROL_VALUE],
  whole: { line: "i", count: 1, fault: "items: the picture needs a item with no fault" },
  lines: [
    { ...CONTROL_LINE, form: `the control of items is ${CONTROL}`, forms: { choice: [CONTROL_LABEL], steps: [CONTROL_LABEL, { type: "word", optional: true }] },
      walk: (tree, { parts }) => (tree.name === "steps" ? parts.length : null) },
    { name: "i", count: BUDGET.items, part: true, form: "an item is item(LABEL, SIGN, TEXT)",
      forms: { item: [{ type: "text", what: "the label", cap: BUDGET.fewOption }, { type: "text", what: "the sign", cap: BUDGET.sign, inside: true },
        { type: "text", what: "the text", cap: BUDGET.caption, long: true }] },
      // The choice of items shows their labels, so it cannot tell two items of one label apart.
      check(tree, { lines }) {
        const twin = lines.find((line) => line.tree && ITEM.test(line.name) && line.tree.args[0].v === tree.args[0].v);
        return twin ? `the label ${show(tree.args[0])} is the label of ${twin.name} too. Each item needs a label of its own: the choice shows the labels` : null;
      },
      /**
       * The list of the items and the chosen item, the drawing of its sign and label, the labels as the options of the inline choice (also
       * for a steps control, stage A, J3), and the caption.
       */
      fill: (parts, { call }) => ({
        items: parts.map(({ tree: { args: [label, sign, text] } }) => `{label: ${show(label)}, sign: ${show(sign)}, text: ${show(text)}}`).join(", "),
        // The sign is large when it is one or two characters, and smaller for a word, so a sign at 1.25 times its cap fits the 300 px of the drawing.
        // A preview with no item yet draws nothing, so the first item only adds to the picture.
        shapes: parts.length ? 'text(150, 95, $/now/sign, "accent", len($/now/sign) <= 2 ? 72 : (len($/now/sign) <= 6 ? 44 : 24)), text(150, 190, $/v, "ink", 20)' : "",
        options: show({ k: "list", items: parts.map((part) => part.tree.args[0]) }),
        // The caption is the text of the chosen item, so it comes with the first item, after the choice.
        caption: parts.length ? "$/now/text" : null,
      }) },
    NARRATION,
  ],
  /** The labels are the options of the choice, so their cap follows the number of items and the control (optionFault), which only the whole file knows. */
  context(lines) {
    const items = lines.filter((line) => line.tree && ITEM.test(line.name));
    return items.flatMap((line) => [optionFault("the label", line.tree.args[0], items.length, "item")].filter(Boolean).map((fault) => [line.name, fault]));
  },
  /**
   * The start of $/v: the answer of a steps control, by default the label of the last item (decision 3), else the label of the first item,
   * or 0 with no item.
   */
  start: ({ parts, call }) => (call?.name === "steps" ? call.args[1] ?? parts.at(-1)?.tree.args[0] : parts[0]?.tree.args[0]) ?? { k: "lit", v: 0 },
};
