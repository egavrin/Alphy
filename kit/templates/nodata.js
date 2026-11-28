// The line table of the ready plan nodata.plan of the data family (block 2 plan, D5): a short card with what is missing and what the person can
// say next, at most 2 sentences. The voice says the same sentences: a file with no narration line gets say with the text of its card (voice).
// The card has a search icon at the left of its title (stage 5 look), and the ready plan holds it.

import { CONTROL_LINE, CONTROL_VALUE, NARRATION, cardLine } from "../src/form-base.js";
import { DATA_BUDGET, SOURCE_LINE, dataFill, sentences } from "../src/form-data.js";

export const NODATA = {
  word: "nodata",
  family: "data",
  text: "card",
  values: [CONTROL_VALUE],
  whole: { line: "card", count: 1, fault: "nodata: the form needs a card with no fault" },
  voice: ["card", 1],
  lines: [{ ...CONTROL_LINE, form: "nodata has no control" },
    // The card text keeps its first 2 sentences.
    { ...cardLine("nodata"), cut: (tree) => (tree.k === "call" && tree.name === "card" && tree.args[1]?.k === "lit" && typeof tree.args[1].v === "string"
      ? { ...tree, args: [tree.args[0], { ...tree.args[1], v: sentences(tree.args[1].v, DATA_BUDGET.nodataSentences) }] } : tree) },
    SOURCE_LINE, NARRATION],
  start: () => ({ k: "lit", v: "" }),
  fill: dataFill(),
};
