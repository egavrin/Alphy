// The line table of the ready plan compare.plan of the data family (block 2 plan, D2): two or three things side by side, each with a name and a
// sign, up to 6 rows with one value for each thing, one marked row, and a card with the point. The kit shows it as a native table with signs=
// (a comparison): the host draws a head cell for each thing and a row block with a bar under each value (stage 5 look).

import { CONTROL_VALUE, NARRATION, isText } from "../src/form-base.js";
import { DATA_BUDGET, cardLine, SOURCE_LINE, cellText, checkSource, contextFaults, cutLabel, cutLabels, dataFill, markLine, markStart, rowFault, tableLine } from "../src/form-data.js";

const THING = /^t[1-3]$/;

/** The fault of a thing or a row line: a thing line and a row line take either form, as the kit placed them by their call. */
function partFault(tree) {
  if (tree.k === "call" && tree.name === "row") return rowFault(tree, DATA_BUDGET.things);
  if (tree.k !== "call" || tree.name !== "thing" || !tree.args.length || tree.args.length > 2 || Object.keys(tree.named).length) return "a thing is thing(NAME, SIGN): its name and a sign, such as an emoji";
  const [name, sign] = tree.args;
  if (!isText(name) || !name.v.trim()) return "the name is a text in quotes";
  if (sign && !(isText(sign) && [...sign.v].length <= DATA_BUDGET.sign)) return `the sign is a text in quotes of at most ${DATA_BUDGET.sign} characters, such as an emoji`;
  return null;
}
const cut = (tree) => cutLabels(tree, tree.name === "thing" || tree.name === "row" ? [0] : []);

export const COMPARE = {
  word: "compare",
  family: "data",
  text: "control, t1 to t3 (2 or 3 things), r1 to r6 (at most 6 rows), and card",
  values: [CONTROL_VALUE],
  whole: { line: "t", count: 2, fault: "compare: the form needs 2 or 3 things with no fault" },
  lines: [markLine(), { name: "t", count: DATA_BUDGET.things, part: true, fault: partFault, cut }, { name: "r", count: DATA_BUDGET.compareRows, part: true, fault: partFault, cut },
    cardLine("compare"), SOURCE_LINE, NARRATION],
  context: (lines) => contextFaults(lines, lines.filter((line) => line.tree?.name === "row").map((line) => line.tree.args[0].v),
    lines.filter((line) => THING.test(line.name) && line.tree).length || undefined),
  start: ({ call }) => markStart(call),
  check: checkSource,
  fill: dataFill((head, parts) => {
    const things = parts.filter((part) => THING.test(part.name)).map(({ tree: { args: [name, sign] } }) => [cutLabel(name.v), sign?.v ?? ""]);
    const rows = parts.filter((part) => !THING.test(part.name)).map(({ tree: { args: [label, values] } }) => [label.v, ...values.items.map(cellText)]);
    return things.length ? tableLine(head, [" ", ...things.map(([name]) => name)], rows, -1, things.map(([, sign]) => sign)) : null;
  }),
};
