// What each template form uses (docs/superpowers/plans/2026-11-22-one-scene.md, decision 1): the first line, the budget, the drawing size,
// the faults of a text, of the first line, and of a card with a text, the cut of a long text, and the colors of a shape. Each form has a
// module of its own (forms.js), and template.js reads, merges, expands, and builds a template file with them.

import { show } from "./dsl.js";
import { TONES, isColor } from "./core.js";
import { colorOf } from "./draw.js";
import { KINDS } from "./kinds.js";

/** The first line of a template file, with the template word as group 1: the explain family, then the data family (form-data.js). */
export const TEMPLATE_HEAD = /^\s*(scene|items|cells|compare|table|chart|nodata)\s*\(/;
/**
 * The content budget of each template: texts in characters, lists in items. A text inside the picture (a shape text, a sign) has no fault up to
 * floor(1.25 × cap), because it cannot change the height of the screen. A card text or an item text past its cap is cut after its last whole
 * sentence inside the cap (cut). Each other text past its cap, a long text with no sentence end inside its cap, and a list past its cap are faults.
 * A narration line (say) is cut the same way, and one with no sentence end inside its cap is left out with no fault (template.js).
 */
export const BUDGET = { title: 30, control: 28, cardTitle: 32, cardText: 140, option: 12, fewOption: 16, sign: 14, text: 28, options: 6, items: 6, shapes: 20, cells: 100, say: 110 };
/** The common color names that the kit takes with no call, by their small letters: the template check had one repair for a color name. */
export const COLOR_NAMES = { red: "#dc2626", orange: "#ea580c", yellow: "#facc15", green: "#16a34a", blue: "#2563eb", purple: "#9333ea", violet: "#7c3aed", pink: "#db2777",
  brown: "#a16207", gray: "#64748b", grey: "#64748b", cyan: "#0891b2", teal: "#0d9488", indigo: "#4f46e5", amber: "#d97706", graphite: "#4b5563", black: "ink", white: "surface" };
const PALETTES = ["teal", "green", "blue", "amber", "orange", "red", "pink", "purple", "indigo", "brown", "graphite"];
const NAMED_COLOR = new RegExp(`^(${Object.keys(COLOR_NAMES).join("|")})([+-][123])?(?:@(\\d{1,2}|100))?$`);
/**
 * A color name (COLOR_NAMES, each name of palette= too) in any case, with a tone step and an alpha as for a role, such as "red+2", "orange-1",
 * or "red@40", as a color of the kit, else null (fix list 3, H1). A name is a fixed hex, so "red" is red in each palette and in both themes, and
 * the kit mixes its tone as for the hex roles (draw.js toneColor). black and white stay the roles ink and surface.
 */
export function namedColor(text) {
  const [, name, step = "", alpha = "100"] = NAMED_COLOR.exec(String(text).toLowerCase()) ?? [];
  if (!name) return null;
  return toneColor(COLOR_NAMES[name], step, Number(alpha));
}
/**
 * A color with a tone step ("+1" to "+3", "-1" to "-3", or "") and an alpha in percent: a hex color mixed as draw.js colorOf mixes a tone of a
 * hex role, and a role as its tone text, such as "ink+1@40". It reuses colorOf with a role of that color, so the run-time keeps one mix.
 */
export const toneColor = (base, step, alpha) => colorOf(`ink${step}@${alpha}`, { ink: base });
/** The place of the color among the arguments of a shape kind (kinds.js), or undefined for a library line, which names its color. */
const colorAt = (name) => (Object.hasOwn(KINDS, name) ? KINDS[name].args.length : undefined);
/**
 * The drawing of each template, fixed from the first line: the picture has its final size before its first part. It is square (stage 6, L3):
 * on a phone the picture fills the width and grows into the empty zone under the app, and the fold keeps it in its column.
 */
export const WIDTH = 300;
export const HEIGHT = 300;

export const isText = (node) => node?.k === "lit" && typeof node.v === "string";
/** A number in the line, also a negative one, else undefined. */
export const numberOf = (node) => (node?.k === "lit" && typeof node.v === "number" ? node.v : node?.k === "un" && node.op === "-" && typeof node.arg?.v === "number" ? -node.arg.v : undefined);
/** Each node of a tree for which test is true. */
export const nodesIn = (node, test) => (!node || typeof node !== "object" ? [] : [...(test(node) ? [node] : []), ...Object.values(node).flatMap((inner) => nodesIn(inner, test))]);

/** The factor of the cap of a text inside the picture (BUDGET). */
export const INSIDE = 1.25;
/** The fault of a text of a line: a text in quotes, or a formula of texts where formula is true, with no text past floor(factor × cap). */
export function textFault(what, node, cap, formula = false, factor = 1) {
  if (!node || (!formula && !isText(node))) return `${what} is a text in quotes`;
  if (isText(node) && !node.v.trim()) return `${what} is empty`;
  const long = nodesIn(node, isText).find((text) => [...text.v].length > Math.floor(cap * factor));
  return long ? `${what} has ${[...long.v].length} characters, and its cap is ${cap}. Write it shorter` : null;
}
/** The most options of a choice whose options have the cap BUDGET.fewOption, else BUDGET.option (optionFault). */
export const FEW_OPTIONS = 4;
/**
 * The cap of each option of a choice, or each step of a steps control, of count items (fix list 3, H3, and fix list 5, L2). A steps control is
 * a choice on the stage. A choice of 2 to 4 has the cap BUDGET.fewOption: by the widths of the labels, two options fit one row on the phone,
 * and three or four wrap into two rows of whole cells (the fit gate holds scene-caps4 and steps-caps4). 5 or 6 keep BUDGET.option. A label
 * is never cut: a line of the scene compares $/v with it.
 */
export const optionCap = (count) => (count <= FEW_OPTIONS ? BUDGET.fewOption : BUDGET.option);
/** The hint of a fault of labels from BUDGET.option to BUDGET.fewOption long in a list of more than FEW_OPTIONS. */
const fewer = (count, lengths, word) => (count > FEW_OPTIONS && lengths.every((length) => length <= BUDGET.fewOption) ? `, or keep at most ${FEW_OPTIONS} ${word}s: their cap is ${BUDGET.fewOption}` : "");
/** The fault of one label of a list of count labels, such as an item label of items, which is an option of its choice. */
export function optionFault(what, node, count, word = "option") {
  const fault = textFault(what, node, optionCap(count));
  return fault && isText(node) && [...node.v].length > optionCap(count) ? fault + fewer(count, [[...node.v].length], word) : fault;
}
/** "1", "1 and 2", or "1, 2, and 3". */
const listed = (values) => (values.length < 3 ? values.join(" and ") : `${values.slice(0, -1).join(", ")}, and ${values.at(-1)}`);
/**
 * One fault for the labels of one line, such as the options of a choice (fix list 5, L1): it names each label over its cap with its number and
 * its length, so one repair can correct each of them. A label that is no text in quotes, or an empty one, is the fault. Null with no fault.
 */
export function labelsFault(word, labels, cap, hint = () => "") {
  const wrong = labels.map((label, index) => (isText(label) && label.v.trim() ? null : textFault(`${word} ${index + 1}`, label, cap))).find(Boolean);
  if (wrong) return wrong;
  const over = labels.map((label, index) => [index + 1, [...label.v].length]).filter(([, length]) => length > cap), lengths = over.map(([, length]) => length);
  if (!over.length) return null;
  const tail = hint(lengths);
  if (over.length === 1) return `${word} ${over[0][0]} has ${over[0][1]} characters, and its cap is ${cap}. Write it shorter${tail}`;
  const same = lengths.every((length) => length === lengths[0]);
  return `${word}s ${listed(over.map(([number, length]) => (same ? number : `${number} (${length} characters)`)))} ${same ? `have ${lengths[0]} characters, and their cap is` : "are over their cap of"} ${cap}. Write each one shorter${tail}`;
}
/** The fault of the options of a choice or the steps of a steps control: each label over the cap of their number (optionCap), in one text. */
export const optionsFault = (word, options) => labelsFault(word, options, optionCap(options.length), (lengths) => fewer(options.length, lengths, word));

/** A sentence end: . ! ? or …, or 。！？ of Chinese and Japanese, with the closing quote marks and brackets after it. */
export const SENTENCE_END = /[.!?…。！？]["'»”’)\]}」』）]*$/u;
/**
 * A text past its cap cut after its last whole sentence inside the cap, or the text itself when it fits or no sentence ends inside the cap.
 * A sentence end counts only before a space, so 3.5 ends no sentence, except 。！？, which no space follows. The space after the cut goes.
 */
export function cut(text, cap) {
  const chars = [...text];
  for (let end = chars.length > cap ? cap : 0; end > 0; end--) {
    const head = chars.slice(0, end).join(""), mark = SENTENCE_END.exec(head)?.[0];
    if (mark && (/\s/u.test(chars[end]) || /[。！？]/u.test(mark))) return head;
  }
  return text;
}
/** The node with each text in it given again by map. */
const textsMapped = (node, map) => (!node || typeof node !== "object" ? node : isText(node) ? map(node)
  : Array.isArray(node) ? node.map((inner) => textsMapped(inner, map)) : Object.fromEntries(Object.entries(node).map(([key, inner]) => [key, textsMapped(inner, map)])));
/**
 * The tree of a card line of scene and cells, or of an item line, with each text of its card text or item text cut to the cap (cut). Each
 * text of a formula is cut by itself. The same tree when no text changes.
 */
export function cutLong(tree, name, word) {
  const at = name === "card" ? (word === "items" ? -1 : 1) : word === "items" ? 2 : -1, long = tree.k === "call" && tree.name === (name === "card" ? "card" : "item") ? tree.args[at] : undefined;
  const short = long && textsMapped(long, (node) => ({ ...node, v: cut(node.v, BUDGET.cardText) }));
  return !long || show(short) === show(long) ? tree : { ...tree, args: tree.args.map((arg, index) => (index === at ? short : arg)) };
}

/** The fault of the first line. extra names a named argument of the form beyond lang= and palette=, such as kind= of chart, which the form checks. */
export function headFault(tree, word, extra) {
  const [title, ...more] = tree.args ?? [], { lang, palette, [extra]: _own, ...other } = tree.named ?? {};
  if (tree.k !== "call" || more.length || Object.keys(other).length) return `the first line is ${word}(TITLE, lang=CODE, palette=NAME${extra ? `, ${extra}=…` : ""}), and palette= is optional`;
  if (!(isText(lang) && /^[a-z]{2}$/.test(lang.v))) return 'lang= is the two-letter code of the language of the request, such as "en"';
  if (palette && !(isText(palette) && PALETTES.includes(palette.v))) return `palette= is one of ${PALETTES.join(", ")}, and ${show(palette)} is not one`;
  return textFault("the title", title, BUDGET.title);
}

/** The fault of the card line card(TITLE, TEXT) of the template word: the card of scene and of cells. */
export function textCardFault(tree, word) {
  if (tree.k !== "call" || tree.name !== "card" || tree.args.length !== 2 || Object.keys(tree.named).length) return `the card of ${word} is card(TITLE, TEXT)`;
  return textFault("the card title", tree.args[0], BUDGET.cardTitle, true) ?? textFault("the card text", tree.args[1], BUDGET.cardText, true);
}

/** The values of a color: the color, or each value of a choice (test ? a : b, or if(test, a, b)), as choices in parts.js reads them. map gives each value again. */
const colorValues = (node, map) => node?.k === "if" ? { ...node, yes: colorValues(node.yes, map), no: colorValues(node.no, map) }
  : node?.k === "call" && node.name === "if" && node.args.length === 3 ? { ...node, args: [node.args[0], ...node.args.slice(1).map((value) => colorValues(value, map))] } : map(node);
/** A color node with each color name (namedColor) of its values as the kit's color: a text that a choice compares stays a text. */
export const namedColors = (node) => colorValues(node, (one) => (isText(one) && namedColor(one.v) ? { k: "lit", v: namedColor(one.v) } : one));
/** A shape with each color name of its color and of gradient= as the kit's color (namedColors). A text of a text shape stays a text. */
export function colored(tree) {
  const at = colorAt(tree.name), named = tree.named.gradient ? { ...tree.named, gradient: namedColors(tree.named.gradient) } : tree.named;
  return { ...tree, args: tree.args.map((arg, index) => (index === at ? namedColors(arg) : arg)), named };
}
export const colorFault = (tree) => {
  const values = [];
  if (colorAt(tree.name) !== undefined) colorValues(tree.args[colorAt(tree.name)], (node) => values.push(node));
  if (tree.named.gradient) colorValues(tree.named.gradient, (node) => values.push(node));
  const wrong = values.find((node) => isText(node) && !isColor(node.v));
  return wrong ? `the color ${show(wrong)} is not a color of the kit. Use one of ${Object.keys(TONES).join(", ")}, a tone of one such as "water+1" or "accent-1@50", one of ${Object.keys(COLOR_NAMES).join(", ")}, or a hex such as "#336699"` : null;
};
