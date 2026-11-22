// The template table of the data family (block 2 plan, D3): a head of 2 to 4 columns, up to 12 rows, one marked row or cell, and a card
// with the caption. The native table shows 8 rows (6 on a compact width), and more rows scroll inside its box (ToolUiCatalog visibleRows).

import { isText, textCardFault } from "./form-base.js";
import { DATA_BUDGET, cellText, contextFaults, cutLabel, cutLabels, dataFile, markFault, markStart, rowFault, tableLine } from "./form-data.js";

const heads = (lines) => lines.find((line) => line.name === "head" && line.tree)?.tree.args[0].items.map((item) => item.v) ?? [];

/** The form of table (forms.js TemplateForm). */
export const TABLE_FORM = {
  word: "table",
  family: "data",
  part: /^(?:head|r(?:[1-9]|1[0-2]))$/,
  lines: "control, head, r1 to r12 (at most 12 rows), and card",
  control: "mark(ROW) or mark(ROW, COLUMN): ROW is the label of the marked row, and COLUMN is the head of the marked cell",
  controlFault: (tree) => markFault(tree, true),
  partFault(tree) {
    if (tree.k === "call" && tree.name === "row") return rowFault(tree, DATA_BUDGET.columns - 1);
    if (tree.k !== "call" || tree.name !== "head" || tree.args.length !== 1 || Object.keys(tree.named).length || tree.args[0]?.k !== "list") return "the head is head([COLUMN, …]): the first column is the head of the row labels";
    const items = tree.args[0].items;
    if (items.length < 2 || items.length > DATA_BUDGET.columns) return `the head has 2 to ${DATA_BUDGET.columns} columns, and this one has ${items.length}. Keep the columns that matter most`;
    return items.map((item, index) => (!isText(item) || !item.v.trim() ? `column ${index + 1} is a text in quotes` : null)).find(Boolean) ?? null;
  },
  cardFault: (tree) => textCardFault(tree, "table"),
  cut: (tree) => cutLabels(tree, tree.name === "row" ? [0] : [], tree.name === "head" ? 0 : -1),
  context(lines) {
    const head = heads(lines);
    return contextFaults(lines, lines.filter((line) => line.tree?.name === "row").map((line) => line.tree.args[0].v), head.length ? head.length - 1 : undefined, head.slice(1));
  },
  start: (parts, control) => markStart(control),
  whole: (parts) => (parts.some((part) => part.name === "head") ? null : "table: the form needs the line head with no fault"),
  file(head, parts, control, card, source) {
    const columns = parts.find((part) => part.name === "head")?.tree.args[0].items.map((item) => item.v) ?? [];
    const rows = parts.filter((part) => part.name !== "head").map(({ tree: { args: [label, values] } }) => [label.v, ...values.items.map(cellText)]);
    const column = isText(control?.args?.[1]) ? columns.indexOf(cutLabel(control.args[1].v)) : -1;
    return dataFile(head, columns.length ? tableLine(head, columns, rows, column) : null, markStart(control), card, source);
  },
};
