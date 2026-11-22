// What each template form uses (docs/superpowers/plans/2026-11-22-one-scene.md, decision 1): the first line, the budget, the drawing size,
// the faults of a text, of the first line, and of a card with a text, the cut of a long text, and the colors of a shape. Each form has a
// module of its own (forms.js), and template.js reads, merges, expands, and builds a template file with them.

import { show } from "./dsl.js";
import { HEX, TONES } from "./core.js";

/** The first line of a template file, with the template word as group 1. */
export const TEMPLATE_HEAD = /^\s*(scene|items|cells)\s*\(/;
/**
 * The content budget of each template: texts in characters, lists in items. A text inside the picture (a shape text, a sign) has no fault up to
 * floor(1.25 × cap), because it cannot change the height of the screen. A card text or an item text past its cap is cut after its last whole
 * sentence inside the cap (cut). Each other text past its cap, a long text with no sentence end inside its cap, and a list past its cap are faults.
 */
export const BUDGET = { title: 30, control: 28, cardTitle: 32, cardText: 140, option: 12, sign: 14, text: 28, options: 6, items: 6, shapes: 12, cells: 100 };
/** The common color names that the kit takes with no call, by their small letters: the template check had one repair for a color name. */
export const COLOR_NAMES = { red: "#dc2626", orange: "#ea580c", yellow: "#facc15", green: "#16a34a", blue: "#2563eb", purple: "#9333ea", violet: "#7c3aed", pink: "#db2777",
  brown: "#a16207", gray: "#64748b", grey: "#64748b", cyan: "#0891b2", teal: "#0d9488", indigo: "#4f46e5", black: "ink", white: "surface" };
const PALETTES = ["teal", "green", "blue", "amber", "orange", "red", "pink", "purple", "indigo", "brown", "graphite"];
/** The shapes of a scene, with the place of the color among their arguments (core.js SHAPES). */
export const SHAPES = { circle: 3, rect: 4, line: 4, text: 3, polygon: 1 };
/** The drawing of each template, fixed from the first line: the picture has its final size before its first part. */
export const WIDTH = 300;
export const HEIGHT = 220;

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

export function headFault(tree, word) {
  const [title, ...more] = tree.args ?? [], { lang, palette, ...other } = tree.named ?? {};
  if (tree.k !== "call" || more.length || Object.keys(other).length) return `the first line is ${word}(TITLE, lang=CODE, palette=NAME), and palette= is optional`;
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
/** A shape with each color name of its color as the kit's color. A text of a text shape and a text that a choice compares stay texts. */
export function colored(tree) {
  const at = SHAPES[tree.name], swap = (node) => (isText(node) && Object.hasOwn(COLOR_NAMES, node.v.toLowerCase()) ? { k: "lit", v: COLOR_NAMES[node.v.toLowerCase()] } : node);
  return { ...tree, args: tree.args.map((arg, index) => (index === at ? colorValues(arg, swap) : arg)) };
}
export const colorFault = (tree) => {
  const values = [];
  colorValues(tree.args[SHAPES[tree.name]], (node) => values.push(node));
  const wrong = values.find((node) => isText(node) && !Object.hasOwn(TONES, node.v) && !HEX.test(node.v));
  return wrong ? `the color ${show(wrong)} is not a color of the kit. Use one of ${Object.keys(TONES).join(", ")}, one of ${Object.keys(COLOR_NAMES).join(", ")}, or a hex such as "#336699"` : null;
};
