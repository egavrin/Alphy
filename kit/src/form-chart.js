// The template chart of the data family (block 2 plan, D4): 2 to 8 points with a label and a number each, as bars or a line, one marked
// point, and a card with the caption. The native chart shows a label and a value at each point (ToolUiCatalog).

import { show } from "./dsl.js";
import { isText, numberOf, textCardFault } from "./form-base.js";
import { contextFaults, cutLabels, dataFile, markFault, markStart } from "./form-data.js";

const KINDS = ["bar", "line"];

/** The form of chart (forms.js TemplateForm). */
export const CHART_FORM = {
  word: "chart",
  family: "data",
  head: "kind",
  part: /^p[1-8]$/,
  lines: "control, p1 to p8 (at most 8 points), and card",
  control: "mark(LABEL): LABEL is the label of the marked point",
  headFault: (tree) => (tree.named.kind && !(isText(tree.named.kind) && KINDS.includes(tree.named.kind.v)) ? `kind= is "bar" or "line", and ${show(tree.named.kind)} is not one` : null),
  controlFault: (tree) => markFault(tree),
  partFault(tree) {
    if (tree.k !== "call" || tree.name !== "point" || tree.args.length !== 2 || Object.keys(tree.named).length) return "a point is point(LABEL, NUMBER)";
    if (!isText(tree.args[0]) || !tree.args[0].v.trim()) return "the label is a text in quotes";
    return numberOf(tree.args[1]) === undefined ? "the value of a point is a number with no quotes and no unit, such as 88" : null;
  },
  cardFault: (tree) => textCardFault(tree, "chart"),
  cut: (tree) => cutLabels(tree, tree.name === "point" ? [0] : []),
  context: (lines) => contextFaults(lines, lines.filter((line) => line.tree?.name === "point").map((line) => line.tree.args[0].v)),
  start: (parts, control) => markStart(control),
  whole: (parts) => (parts.length < 2 ? "chart: the form needs 2 points with no fault" : null),
  file(head, parts, control, card, source) {
    const points = parts.map(({ tree: { args: [label, value] } }) => `{label: ${show(label)}, value: ${numberOf(value)}}`);
    return dataFile(head, points.length ? `Chart([${points.join(", ")}], $label, $value, label=${show(head.args[0])}, kind=${show(head.named.kind ?? { k: "lit", v: "bar" })}, bind=$/v)` : null,
      markStart(control), card, source);
  },
};
