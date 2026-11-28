// The line table of the ready plan chart.plan of the data family (block 2 plan, D4): 2 to 8 points with a label and a number each, as bars or
// a line, one marked point, and a card with the caption. The native chart shows a label and a value at each point (ToolUiCatalog).

import { show } from "../src/dsl.js";
import { CONTROL_VALUE, NARRATION, cardLine, isText, numberOf } from "../src/form-base.js";
import { DATA_BUDGET, SOURCE_LINE, checkSource, contextFaults, cutLabels, dataFill, markLine, markStart } from "../src/form-data.js";

function partFault(tree) {
  if (tree.k !== "call" || tree.name !== "point" || tree.args.length !== 2 || Object.keys(tree.named).length) return "a point is point(LABEL, NUMBER)";
  if (!isText(tree.args[0]) || !tree.args[0].v.trim()) return "the label is a text in quotes";
  return numberOf(tree.args[1]) === undefined ? "the value of a point is a number with no quotes and no unit, such as 88" : null;
}

export const CHART = {
  word: "chart",
  family: "data",
  head: { name: "kind", values: ["bar", "line"] },
  text: "control, p1 to p8 (at most 8 points), and card",
  values: [CONTROL_VALUE],
  whole: { line: "p", count: 2, fault: "chart: the form needs 2 points with no fault" },
  lines: [markLine(), { name: "p", count: DATA_BUDGET.points, part: true, fault: partFault, cut: (tree) => cutLabels(tree, tree.name === "point" ? [0] : []) },
    cardLine("chart"), SOURCE_LINE, NARRATION],
  context: (lines) => contextFaults(lines, lines.filter((line) => line.tree?.name === "point").map((line) => line.tree.args[0].v)),
  start: ({ call }) => markStart(call),
  check: checkSource,
  fill: dataFill((head, parts) => {
    const points = parts.map(({ tree: { args: [label, value] } }) => `{label: ${show(label)}, value: ${numberOf(value)}}`);
    return points.length ? `Chart([${points.join(", ")}], $label, $value, label=${show(head.args[0])}, kind=${show(head.named.kind ?? { k: "lit", v: "bar" })}, bind=$/v)` : null;
  }),
};
