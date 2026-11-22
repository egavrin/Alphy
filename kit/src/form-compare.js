// The template compare of the data family (block 2 plan, D2): two or three things side by side, each with a name and a sign, up to 6 rows
// with one value for each thing, one marked row, and a card with the point. The kit shows it as a native table with signs= (a comparison):
// the host draws a head cell for each thing and a row block with a bar under each value (stage 5 look).

import { isText, textCardFault } from "./form-base.js";
import { DATA_BUDGET, cellText, contextFaults, cutLabel, cutLabels, dataFile, markFault, markStart, rowFault, tableLine } from "./form-data.js";

const THING = /^t[1-3]$/;

/** The form of compare (forms.js TemplateForm). */
export const COMPARE_FORM = {
  word: "compare",
  family: "data",
  part: /^(?:t[1-3]|r[1-6])$/,
  lines: "control, t1 to t3 (2 or 3 things), r1 to r6 (at most 6 rows), and card",
  control: "mark(LABEL): LABEL is the label of the marked row",
  controlFault: (tree) => markFault(tree),
  partFault(tree) {
    if (tree.k === "call" && tree.name === "row") return rowFault(tree, DATA_BUDGET.things);
    if (tree.k !== "call" || tree.name !== "thing" || !tree.args.length || tree.args.length > 2 || Object.keys(tree.named).length) return "a thing is thing(NAME, SIGN): its name and a sign, such as an emoji";
    const [name, sign] = tree.args;
    if (!isText(name) || !name.v.trim()) return "the name is a text in quotes";
    if (sign && !(isText(sign) && [...sign.v].length <= DATA_BUDGET.sign)) return `the sign is a text in quotes of at most ${DATA_BUDGET.sign} characters, such as an emoji`;
    return null;
  },
  cardFault: (tree) => textCardFault(tree, "compare"),
  cut: (tree) => cutLabels(tree, tree.name === "thing" || tree.name === "row" ? [0] : []),
  context: (lines) => contextFaults(lines, lines.filter((line) => line.tree?.name === "row").map((line) => line.tree.args[0].v),
    lines.filter((line) => THING.test(line.name) && line.tree).length || undefined),
  start: (parts, control) => markStart(control),
  whole: (parts) => (parts.filter((part) => THING.test(part.name)).length < 2 ? "compare: the form needs 2 or 3 things with no fault" : null),
  file(head, parts, control, card, source) {
    const things = parts.filter((part) => THING.test(part.name)).map(({ tree: { args: [name, sign] } }) => [cutLabel(name.v), sign?.v ?? ""]);
    const rows = parts.filter((part) => !THING.test(part.name)).map(({ tree: { args: [label, values] } }) => [label.v, ...values.items.map(cellText)]);
    return dataFile(head, things.length ? tableLine(head, [" ", ...things.map(([name]) => name)], rows, -1, things.map(([, sign]) => sign)) : null, markStart(control), card, source);
  },
};
