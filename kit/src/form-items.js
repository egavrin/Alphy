// The template items (docs/superpowers/plans/2026-11-22-one-scene.md, decision 1): the items i1 to i6, a choice of their labels, and a card
// that shows the text of the chosen item. The drawing shows the sign and the label of the chosen item.

import { show } from "./dsl.js";
import { BUDGET, INSIDE, isText, textFault } from "./form-base.js";

const CONTROL = "choice(LABEL), or steps(LABEL, ANSWER): its options are the labels of the items";

/** The form of items (forms.js TemplateForm). */
export const ITEMS_FORM = {
  word: "items",
  part: /^i[1-6]$/,
  lines: "control, i1 to i6 (at most 6 items), and card",
  control: CONTROL,
  controlFault(tree) {
    const args = tree.args ?? [], form = `the control of items is ${CONTROL}`;
    if (tree.k !== "call" || Object.keys(tree.named).length) return form;
    if (!(tree.name === "choice" && args.length === 1 || tree.name === "steps" && args.length <= 2 && args.length >= 1 && (!args[1] || isText(args[1])))) return form;
    return textFault("the label", args[0], BUDGET.control);
  },
  partFault(tree) {
    if (tree.k !== "call" || tree.name !== "item" || tree.args.length !== 3 || Object.keys(tree.named).length) return "an item is item(LABEL, SIGN, TEXT)";
    return textFault("the label", tree.args[0], BUDGET.option) ?? textFault("the sign", tree.args[1], BUDGET.sign, false, INSIDE) ?? textFault("the text", tree.args[2], BUDGET.cardText);
  },
  cardFault(tree) {
    if (tree.k !== "call" || tree.name !== "card" || tree.args.length !== 1 || Object.keys(tree.named).length) return "the card of items is card(TITLE): it shows the text of the chosen item";
    return textFault("the card title", tree.args[0], BUDGET.cardTitle, true);
  },
  /** The list of the items and the chosen item, the drawing of its sign and label, the labels as the options of the choice, and its text on the card. */
  expand(parts) {
    // The sign is large when it is one or two characters, and smaller for a word, so a sign at 1.25 times its cap fits the 300 px of the drawing.
    // A preview with no item yet draws nothing, so the first item only adds to the picture.
    return {
      lines: [`$/items := [${parts.map(({ tree: { args: [label, sign, text] } }) => `{label: ${show(label)}, sign: ${show(sign)}, text: ${show(text)}}`).join(", ")}]`, "$/now := find($/items, $label == $/v)"],
      shapes: parts.length ? ['text(150, 95, $/now/sign, "accent", len($/now/sign) <= 2 ? 72 : (len($/now/sign) <= 6 ? 44 : 24))', 'text(150, 190, $/v, "ink", 20)'] : [],
      options: { k: "list", items: parts.map((part) => part.tree.args[0]) },
      cardText: "$/now/text",
    };
  },
  /**
   * The start of $/v: the answer of a steps control, by default the label of the last item (decision 3), else the label of the first item,
   * or 0 with no item.
   */
  start: (parts, control) => (control?.name === "steps" ? control.args[1] ?? parts.at(-1)?.tree.args[0] : parts[0]?.tree.args[0]) ?? { k: "lit", v: 0 },
};
