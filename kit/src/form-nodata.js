// The template nodata of the data family (block 2 plan, D5): a short card with what is missing and what the person can say next, at most
// 2 sentences. The voice says the same sentences: a file with no narration line gets say with the text of its card (template.js).

import { textCardFault } from "./form-base.js";
import { DATA_BUDGET, dataFile, sentences } from "./form-data.js";

/** The form of nodata (forms.js TemplateForm). It has no part and no control. */
export const NODATA_FORM = {
  word: "nodata",
  family: "data",
  part: /(?!)/,
  lines: "card",
  control: "no control",
  controlFault: () => "nodata has no control",
  cardFault: (tree) => textCardFault(tree, "nodata"),
  /** The card text keeps its first 2 sentences. */
  cut: (tree) => (tree.k === "call" && tree.name === "card" && tree.args[1]?.k === "lit" && typeof tree.args[1].v === "string"
    ? { ...tree, args: [tree.args[0], { ...tree.args[1], v: sentences(tree.args[1].v, DATA_BUDGET.nodataSentences) }] } : tree),
  start: () => ({ k: "lit", v: "" }),
  whole: (parts, lines, drawn) => (drawn("card") ? null : "nodata: the form needs a card with no fault"),
  file: (head, parts, control, card, source) => dataFile(head, null, null, card, source),
};
