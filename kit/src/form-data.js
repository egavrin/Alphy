// What the forms of the data family use (docs/superpowers/plans/2026-11-22-block-2-forms-library-motion.md, stage 5): compare, table, chart,
// and nodata. Their call starts after the routing call, with the section "Template data" of prompt.md. A data form shows its rows or points
// in a native table or chart, and the marked row or point is the value $/v: a tap on a row or a point moves it (decision 2). The host check
// of decision 3 (checkData) compares each number of a searched form with the source excerpts.

import { show } from "./dsl.js";
import { BUDGET, SENTENCE_END, isText, numberOf, textFault } from "./form-base.js";

/** The words of the data family. */
export const DATA_WORDS = new Set(["compare", "table", "chart", "nodata"]);
/** The first line of a data form, as the host tells it (prompt.json dataHead): a spoken change of such a screen is a new data screen. */
export const DATA_HEAD = new RegExp(`^\\s*(?:${[...DATA_WORDS].join("|")})\\s*\\(`);
/**
 * The caps of the data forms, in characters and items. A label (a row label, a thing name, a column head, a point label) past its cap is cut
 * with "…", because a cut label keeps its meaning. A value past its cap is a fault, because a cut number is a wrong number.
 */
export const DATA_BUDGET = { things: 3, compareRows: 6, columns: 4, tableRows: 12, visibleRows: 8, points: 8, label: 16, value: 12, sign: 4, nodataSentences: 2 };

/** A label past cap: its first cap - 1 characters and "…". */
export const cutLabel = (text, cap = DATA_BUDGET.label) => ([...text].length > cap ? [...text].slice(0, cap - 1).join("") + "…" : text);
/** The node of a label cut to its cap, or the node itself. */
const cutNode = (node) => (isText(node) && [...node.v].length > DATA_BUDGET.label ? { ...node, v: cutLabel(node.v) } : node);
/** The tree of a call with the labels at the indexes of at cut to their cap, and each text of the list at index list cut too (a head). */
export function cutLabels(tree, at, list) {
  if (tree.k !== "call") return tree;
  return { ...tree, args: tree.args.map((arg, index) => (at.includes(index) ? cutNode(arg) : index === list && arg?.k === "list" ? { ...arg, items: arg.items.map(cutNode) } : arg)) };
}

/** The text of a value of a cell: a text in quotes as it is, or a number. Undefined for each other node. */
export const cellText = (node) => (isText(node) ? node.v : numberOf(node) !== undefined ? String(numberOf(node)) : undefined);

/** The fault of a label: a text in quotes that is not empty. A long one is cut (cutLabels), so it has no fault. */
const labelFault = (what, node) => (!isText(node) ? `${what} is a text in quotes` : !node.v.trim() ? `${what} is empty` : null);

/** The fault of the line row(LABEL, [VALUE, …]): a label and 1 to max values, each a text in quotes of at most DATA_BUDGET.value characters or a number. */
export function rowFault(tree, max) {
  if (tree.k !== "call" || tree.name !== "row" || tree.args.length !== 2 || Object.keys(tree.named).length || tree.args[1]?.k !== "list") return "a row is row(LABEL, [VALUE, …])";
  const values = tree.args[1].items;
  if (!values.length || values.length > max) return `a row has 1 to ${max} values, and this one has ${values.length}`;
  return labelFault("the label", tree.args[0]) ?? values.map((value, index) => (cellText(value) === undefined ? `value ${index + 1} is a text in quotes or a number`
    : !cellText(value).trim() ? `value ${index + 1} is empty` : [...cellText(value)].length > DATA_BUDGET.value
      ? `value ${index + 1} has ${[...cellText(value)].length} characters, and its cap is ${DATA_BUDGET.value}. Write it shorter, such as with a unit in the label` : null)).find(Boolean) ?? null;
}

/** The fault of the control mark(ROW) of a form, or mark(ROW, COLUMN) when column is true. ROW and COLUMN name a row or a column of the form. */
export function markFault(tree, column = false) {
  const form = column ? "mark(ROW) or mark(ROW, COLUMN)" : "mark(LABEL)";
  if (tree.k !== "call" || tree.name !== "mark" || Object.keys(tree.named).length || !tree.args.length || tree.args.length > (column ? 2 : 1)) return `the control of this template is ${form}: it marks one row or point`;
  return tree.args.map((arg) => labelFault("the mark", arg)).find(Boolean) ?? null;
}

/**
 * The faults that need more than one line, as [name, message]: the mark must name a row (or a point) and a column of the form, and each row
 * needs one value for each column. labels are the labels that the mark can name, count is the count of values of each row, and columns are the
 * heads that a cell mark can name (the heads after the first one).
 */
export function contextFaults(lines, labels, count, columns = []) {
  const out = [], mark = lines.find((line) => line.name === "control" && line.tree?.k === "call"), labelled = lines.filter((one) => one.tree && ["row", "point"].includes(one.tree.name));
  for (const line of labelled.filter((one) => one.tree.name === "row" && count !== undefined && one.tree.args[1].items.length !== count)) {
    out.push([line.name, `this row has ${line.tree.args[1].items.length} values, and the form has ${count} columns of values. Give each row one value for each column`]);
  }
  // The label of a row or a point is its id, and a tap or a spoken mark names it, so each one is different.
  labelled.forEach((line, index) => {
    const twin = labelled.slice(0, index).find((one) => one.tree.args[0].v === line.tree.args[0].v);
    if (twin) out.push([line.name, `the label ${show(line.tree.args[0])} is the label of ${twin.name} too. Each label is different`]);
  });
  const [row, column] = mark?.tree.args ?? [];
  if (row && !labels.includes(cutLabel(row.v))) out.push(["control", `the mark ${show(row)} names no row. Name the label of one row, such as ${show({ k: "lit", v: labels[0] ?? "" })}`]);
  else if (column && !columns.includes(cutLabel(column.v))) out.push(["control", `the column ${show(column)} names no column of values. Name one of ${columns.map((text) => JSON.stringify(text)).join(", ")}`]);
  return out;
}

/** The fault of the host line source(SITE, TIME): two texts in quotes. */
export function sourceFault(tree) {
  if (tree.k !== "call" || tree.name !== "source" || tree.args.length !== 2 || Object.keys(tree.named).length) return "the source is source(SITE, TIME), and only the host writes it";
  return tree.args.map((arg, index) => textFault(index ? "the time" : "the site", arg, 100)).find(Boolean) ?? null;
}

/**
 * The app lines of a data form: the mark $/v, the main component, the card, the source, and the root in that order. main is the line of the
 * table or the chart, or null for nodata. A form with no source line shows none.
 */
export function dataFile(head, main, start, card, source, icon) {
  const title = show(head.args[0]), out = [`app(${title}, lang=${show(head.named.lang)}, preset="clean", palette=${show(head.named.palette ?? { k: "lit", v: "blue" })})`];
  if (main) out.push(`$/v = ${show(start)}`, `data_view = ${main}`);
  if (card) out.push(`need card = card(${show(card.args[0])}, ${show(card.args[1])}${icon ? `, icon=${show({ k: "lit", v: icon })}` : ""})`);
  // The source line is one small muted line with a link icon (stage 5 look).
  if (source) out.push(`data_source = Text(${show({ k: "lit", v: `${source.args[0].v} · ${source.args[1].v}` })}, variant="source")`);
  const top = [main && "data_view", card && "card_view", source && "data_source"].filter(Boolean);
  // The first line alone is a frame of the stream (the preview step): its screen holds one divider under the title.
  if (!top.length) out.push("data_wait = Divider()");
  out.push(`root = Column([${(top.length ? top : ["data_wait"]).join(", ")}])`);
  return out.join("\n") + "\n";
}

/**
 * The line Table(TITLE, COLUMNS, ROWS, $/v) of the table of a form, with column= for a marked cell (its index among the columns, from 0).
 * signs= makes the table a comparison: one sign for each thing, "" for none (stage 5 look).
 */
export function tableLine(head, columns, rows, column, signs) {
  const list = (items) => `[${items.map((text) => show({ k: "lit", v: text })).join(", ")}]`;
  // A value that is a whole number of 5 or more digits shows a thin space between groups of three ("12 742"). The file keeps the value.
  // Only a data form does this: in another app, such digits can be a code. The label of a row is its id, so it stays as written.
  const shown = ([label, ...values]) => [label, ...values.map((text) => text.replace(/^([-−]?)(\d{5,})$/, (all, sign, digits) => sign + digits.replace(/\B(?=(\d{3})+$)/g, " ")))];
  return `Table(${show(head.args[0])}, ${list(columns)}, [${rows.map(shown).map(list).join(", ")}], $/v${column > 0 ? `, column=${column}` : ""}${signs ? `, signs=${list(signs)}` : ""})`;
}

/** The value that $/v keeps: the label of the marked row, or "" for no mark. */
export const markStart = (control) => (control?.k === "call" && isText(control.args[0]) ? { k: "lit", v: cutLabel(control.args[0].v) } : { k: "lit", v: "" });

/** The text cut to its first count sentences, or the text itself. */
export function sentences(text, count) {
  let seen = 0;
  for (let end = 1; end <= text.length; end++) {
    if (SENTENCE_END.test(text.slice(0, end)) && (end === text.length || /\s/u.test(text[end])) && ++seen === count) return text.slice(0, end);
  }
  return text;
}

// The host check of decision 3: each number of a data cell or of a point of a searched form is in the source excerpts.

/**
 * The numbers of a text by their digits: a decimal comma equals a decimal point, and a space or a comma between groups of three digits is
 * dropped, so "12 742", "12,742", and "12742" are one number, and "3,5" is 3.5. A score such as "2:1" is its two numbers. The sign is not read:
 * "−5" and "5" are one number. Each number is its value as a text, such as "3.5", so "3.50" and "3.5" are one number.
 */
export function numbersIn(text) {
  const out = [], digits = /\d+(?:[    ,]\d{3}(?!\d))*(?:[.,]\d+)?/gu;
  for (const [token] of String(text).matchAll(digits)) {
    const plain = token.replace(/[    ]/gu, "").replace(/,(?=\d{3}(?!\d))/gu, "").replace(",", ".");
    out.push(String(Number(plain)));
  }
  return out;
}

/** The values of the cells and points of a data form that the host check reads: no label, no head, and no column that only counts the rows. */
export function checkedValues(read) {
  const rows = read.lines.filter((line) => line.tree?.k === "call" && line.tree.name === "row").map((line) => line.tree.args[1].items.map(cellText));
  const points = read.lines.filter((line) => line.tree?.k === "call" && line.tree.name === "point").map((line) => cellText(line.tree.args[1]));
  // A column whose values are 1, 2, 3, … in the order of the rows is a rank that the model adds, not a data value.
  const width = Math.max(0, ...rows.map((row) => row.length));
  const rank = (index) => rows.length > 1 && rows.every((row, at) => numbersIn(row[index] ?? "").join() === String(at + 1));
  const cells = [...Array(width).keys()].filter((index) => !rank(index)).flatMap((index) => rows.map((row) => row[index] ?? ""));
  return [...cells, ...points].filter((text) => text !== undefined);
}

/** The words of the nodata card that the host check writes, by the language of the form. Each other language gets en. */
const REJECT = {
  ru: ["Нет точных данных", (value) => `Источники не подтверждают значение ${value}. Скажи «найди ещё раз» или спроси по-другому.`],
  en: ["No exact data", (value) => `The sources do not hold the value ${value}. Say «search again» or ask another way.`],
};
/** The nodata file of a form whose value is in no excerpt: the title of the form, the reason, and the same sentences for the voice. */
export function rejectFile(head, value) {
  const [title, reason] = REJECT[head.named.lang?.v] ?? REJECT.en, text = reason(value);
  const palette = head.named.palette ? `, palette=${show(head.named.palette)}` : "";
  return `nodata(${show(head.args[0])}, lang=${show(head.named.lang ?? { k: "lit", v: "en" })}${palette})\ncard = card(${show({ k: "lit", v: title })}, ${show({ k: "lit", v: text })})\nsay = ${JSON.stringify(text)}\n`;
}
