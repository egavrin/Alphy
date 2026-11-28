// The line table of the ready plan table.plan of the data family (block 2 plan, D3): a head of 2 to 4 columns, up to 12 rows, one marked row
// or cell, and a card with the caption. The native table shows 8 rows (6 on a compact width), and more rows scroll inside its box
// (ToolUiCatalog visibleRows).

import { CONTROL_VALUE, NARRATION, cardLine, isText } from "../src/form-base.js";
import { DATA_BUDGET, SOURCE_LINE, cellText, checkSource, contextFaults, cutLabel, cutLabels, dataFill, markLine, markStart, rowFault, tableLine } from "../src/form-data.js";

const heads = (lines) => lines.find((line) => line.name === "head" && line.tree)?.tree.args[0].items.map((item) => item.v) ?? [];

/** The fault of the head line or a row line: each one takes either form, as the kit placed them by their call. */
function partFault(tree) {
  if (tree.k === "call" && tree.name === "row") return rowFault(tree, DATA_BUDGET.columns - 1);
  if (tree.k !== "call" || tree.name !== "head" || tree.args.length !== 1 || Object.keys(tree.named).length || tree.args[0]?.k !== "list") return "the head is head([COLUMN, …]): the first column is the head of the row labels";
  const items = tree.args[0].items;
  if (items.length < 2 || items.length > DATA_BUDGET.columns) return `the head has 2 to ${DATA_BUDGET.columns} columns, and this one has ${items.length}. Keep the columns that matter most`;
  return items.map((item, index) => (!isText(item) || !item.v.trim() ? `column ${index + 1} is a text in quotes` : null)).find(Boolean) ?? null;
}
const cut = (tree) => cutLabels(tree, tree.name === "row" ? [0] : [], tree.name === "head" ? 0 : -1);

export const TABLE = {
  word: "table",
  family: "data",
  text: "control, head, r1 to r12 (at most 12 rows), and card",
  values: [CONTROL_VALUE],
  whole: { line: "head", count: 1, fault: "table: the form needs the line head with no fault" },
  lines: [markLine(true), { name: "head", part: true, fault: partFault, cut }, { name: "r", count: DATA_BUDGET.tableRows, part: true, fault: partFault, cut },
    cardLine("table"), SOURCE_LINE, NARRATION],
  context(lines) {
    const head = heads(lines);
    return contextFaults(lines, lines.filter((line) => line.tree?.name === "row").map((line) => line.tree.args[0].v), head.length ? head.length - 1 : undefined, head.slice(1));
  },
  start: ({ call }) => markStart(call),
  check: checkSource,
  fill: dataFill((head, parts, call) => {
    const columns = parts.find((part) => part.name === "head")?.tree.args[0].items.map((item) => item.v) ?? [];
    const rows = parts.filter((part) => part.name !== "head").map(({ tree: { args: [label, values] } }) => [label.v, ...values.items.map(cellText)]);
    const column = isText(call?.args?.[1]) ? columns.indexOf(cutLabel(call.args[1].v)) : -1;
    return columns.length ? tableLine(head, columns, rows, column) : null;
  }),
};
