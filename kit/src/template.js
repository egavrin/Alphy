// The template file of a voice screen (docs/superpowers/specs/2026-11-21-stage-templates-stream-design.md, sections 1 and 2): named lines
// in a fixed order, which the kit checks, fixes, and expands into an app file of today's kinds. compile.js branches here for a template text,
// plan, or stored app file.
//
//   scene("Почему корабль не тонет?", lang="ru", palette="blue")      the first line: the template, the title, the language, the palette
//   control = choice("Предмет", ["Корабль", "Гвоздь"])                 the one control. A part reads its value as $/v
//   s1 = rect(0, 110, 300, 110, "water", 0)                            the parts: s1 to s12 are shapes (scene), i1 to i6 items (items)
//   card = card("Вытеснение воды", "Полый корабль ...")                 the card

import { DslError, parseExpression, parseLine, show } from "./dsl.js";
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
const SHAPES = { circle: 3, rect: 4, line: 4, text: 3, polygon: 1 };
/** The names of the part lines of each template. A cells file has none: the control draws the cells. */
const PART = { scene: /^s(?:[1-9]|1[0-2])$/, items: /^i[1-6]$/, cells: /(?!)/ };
const LINES = { scene: "control, s1 to s12 (at most 12 shapes), and card", items: "control, i1 to i6 (at most 6 items), and card", cells: "control and card" };
/** The drawing of each template, fixed from the first line: the picture has its final size before its first part. */
const WIDTH = 300, HEIGHT = 220;

export const isTemplateFile = (text) => typeof text === "string" && TEMPLATE_HEAD.test(text.split("\n").find((line) => line.trim()) ?? "");

const isText = (node) => node?.k === "lit" && typeof node.v === "string";
/** A number in the line, also a negative one, else undefined. */
const numberOf = (node) => (node?.k === "lit" && typeof node.v === "number" ? node.v : node?.k === "un" && node.op === "-" && typeof node.arg?.v === "number" ? -node.arg.v : undefined);
/** Each node of a tree for which test is true. */
const nodesIn = (node, test) => (!node || typeof node !== "object" ? [] : [...(test(node) ? [node] : []), ...Object.values(node).flatMap((inner) => nodesIn(inner, test))]);
const readsV = (node) => node.k === "path" && node.abs && node.segs.length === 1 && node.segs[0] === "v";

/** The factor of the cap of a text inside the picture (BUDGET). */
const INSIDE = 1.25;
/** The fault of a text of a line: a text in quotes, or a formula of texts where formula is true, with no text past floor(factor × cap). */
function textFault(what, node, cap, formula = false, factor = 1) {
  if (!node || (!formula && !isText(node))) return `${what} is a text in quotes`;
  if (isText(node) && !node.v.trim()) return `${what} is empty`;
  const long = nodesIn(node, isText).find((text) => [...text.v].length > Math.floor(cap * factor));
  return long ? `${what} has ${[...long.v].length} characters, and its cap is ${cap}. Write it shorter` : null;
}

/** A sentence end: . ! ? or …, or 。！？ of Chinese and Japanese, with the closing quote marks and brackets after it. */
const SENTENCE_END = /[.!?…。！？]["'»”’)\]}」』）]*$/u;
/**
 * A text past its cap cut after its last whole sentence inside the cap, or the text itself when it fits or no sentence ends inside the cap.
 * A sentence end counts only before a space, so 3.5 ends no sentence, except 。！？, which no space follows. The space after the cut goes.
 */
function cut(text, cap) {
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
function cutLong(tree, name, word) {
  const at = name === "card" ? (word === "items" ? -1 : 1) : word === "items" ? 2 : -1, long = tree.k === "call" && tree.name === (name === "card" ? "card" : "item") ? tree.args[at] : undefined;
  const short = long && textsMapped(long, (node) => ({ ...node, v: cut(node.v, BUDGET.cardText) }));
  return !long || show(short) === show(long) ? tree : { ...tree, args: tree.args.map((arg, index) => (index === at ? short : arg)) };
}

function headFault(tree, word) {
  const [title, ...more] = tree.args ?? [], { lang, palette, ...other } = tree.named ?? {};
  if (tree.k !== "call" || more.length || Object.keys(other).length) return `the first line is ${word}(TITLE, lang=CODE, palette=NAME), and palette= is optional`;
  if (!(isText(lang) && /^[a-z]{2}$/.test(lang.v))) return 'lang= is the two-letter code of the language of the request, such as "en"';
  if (palette && !(isText(palette) && PALETTES.includes(palette.v))) return `palette= is one of ${PALETTES.join(", ")}, and ${show(palette)} is not one`;
  return textFault("the title", title, BUDGET.title);
}

const FORMS = { scene: "slider(LABEL, MIN, MAX, STEP, START) with numbers, or choice(LABEL, [OPTION, …], START)", items: "choice(LABEL): its options are the labels of the items", cells: "slider(LABEL, 0, COUNT, 1, START)" };
/** The fault of a control line. The kit checks the bounds and the start of a slider and of a choice when it compiles the expansion. */
function controlFault(tree, word) {
  const args = tree.args ?? [], form = `the control of ${word} is ${FORMS[word]}`;
  if (tree.k !== "call" || Object.keys(tree.named).length) return form;
  if (tree.name === "slider" && word !== "items") {
    if (args.length !== 5 || args.slice(1).some((arg) => numberOf(arg) === undefined)) return form;
    if (word === "cells") {
      const count = numberOf(args[2]);
      if (numberOf(args[1]) !== 0 || numberOf(args[3]) !== 1) return form;
      if (!(Number.isInteger(count) && count >= 2 && count <= BUDGET.cells)) return `COUNT is a whole number from 2 to ${BUDGET.cells}, and ${show(args[2])} is not one`;
    }
  } else if (tree.name === "choice" && word === "scene") {
    const options = args[1]?.items;
    if (args.length < 2 || args.length > 3 || !options || (args[2] && !isText(args[2]))) return form;
    if (options.length > BUDGET.options) return `the choice has ${options.length} options, and its cap is ${BUDGET.options}. Keep the ones that matter most`;
    if (options.length < 2) return "a choice needs at least 2 options";
    const fault = options.map((option, index) => textFault(`option ${index + 1}`, option, BUDGET.option)).find(Boolean);
    if (fault) return fault;
  } else if (!(tree.name === "choice" && word === "items" && args.length === 1)) return form;
  return textFault("the label", args[0], BUDGET.control);
}

function partFault(tree, word) {
  if (word === "items") {
    if (tree.k !== "call" || tree.name !== "item" || tree.args.length !== 3 || Object.keys(tree.named).length) return "an item is item(LABEL, SIGN, TEXT)";
    return textFault("the label", tree.args[0], BUDGET.option) ?? textFault("the sign", tree.args[1], BUDGET.sign, false, INSIDE) ?? textFault("the text", tree.args[2], BUDGET.cardText);
  }
  if (tree.k !== "call" || !Object.hasOwn(SHAPES, tree.name)) return "a shape is circle, rect, line, text, or polygon";
  const other = Object.keys(tree.named).find((key) => key !== "visible" && key !== "stroke");
  if (other) return `a shape takes only visible= and stroke= by name, and ${other}= is not one`;
  if (tree.name === "text") return textFault("the text", tree.args[2], BUDGET.text, true, INSIDE);
  // The kit compiles a shorter or an odd list clean: it draws nothing for two points and drops the last number of an odd list.
  const points = tree.name === "polygon" && tree.args[0]?.k === "list" ? tree.args[0].items.length : 6;
  if (points < 6 || points % 2) return `the points of a polygon are a list of x and y numbers for at least 3 points (6 numbers), and this list has ${points} numbers`;
  return null;
}

function cardFault(tree, word) {
  const count = word === "items" ? 1 : 2;
  if (tree.k !== "call" || tree.name !== "card" || tree.args.length !== count || Object.keys(tree.named).length) return `the card of ${word} is card(${count === 1 ? "TITLE): it shows the text of the chosen item" : "TITLE, TEXT)"}`;
  return textFault("the card title", tree.args[0], BUDGET.cardTitle, true) ?? (count === 2 ? textFault("the card text", tree.args[1], BUDGET.cardText, true) : null);
}

/** The values of a color: the color, or each value of a choice (test ? a : b, or if(test, a, b)), as choices in parts.js reads them. map gives each value again. */
const colorValues = (node, map) => node?.k === "if" ? { ...node, yes: colorValues(node.yes, map), no: colorValues(node.no, map) }
  : node?.k === "call" && node.name === "if" && node.args.length === 3 ? { ...node, args: [node.args[0], ...node.args.slice(1).map((value) => colorValues(value, map))] } : map(node);
/** A shape with each color name of its color as the kit's color. A text of a text shape and a text that a choice compares stay texts. */
function colored(tree) {
  const at = SHAPES[tree.name], swap = (node) => (isText(node) && Object.hasOwn(COLOR_NAMES, node.v.toLowerCase()) ? { k: "lit", v: COLOR_NAMES[node.v.toLowerCase()] } : node);
  return { ...tree, args: tree.args.map((arg, index) => (index === at ? colorValues(arg, swap) : arg)) };
}
const colorFault = (tree) => {
  const values = [];
  colorValues(tree.args[SHAPES[tree.name]], (node) => values.push(node));
  const wrong = values.find((node) => isText(node) && !Object.hasOwn(TONES, node.v) && !HEX.test(node.v));
  return wrong ? `the color ${show(wrong)} is not a color of the kit. Use one of ${Object.keys(TONES).join(", ")}, one of ${Object.keys(COLOR_NAMES).join(", ")}, or a hex such as "#336699"` : null;
};

/** A line of a template file or of a reply: {name, raw}, a first line with head, a delete as {gone}, or null for a line with no name. $/v is the line control. */
function lineOf(raw) {
  const line = raw.trim(), word = TEMPLATE_HEAD.exec(line)?.[1], gone = /^delete\s+(\S+)$/.exec(line)?.[1], name = /^(\$\/v|[\p{L}_][\p{L}\p{N}_]*)\s*=(?!=)/u.exec(line)?.[1];
  return word ? { name: word, head: true, raw: line } : gone ? { gone } : name ? { name: name === "$/v" ? "control" : name, raw: line } : null;
}
/** The place of each kind of line in the fixed order. */
const rank = (name, word) => (name === word ? 0 : name === "control" ? 1 : name === "card" ? 3 : 2);

/**
 * The lines of a template file in the fixed order (the first line, the control, the parts in their order, the card), and the fault of each
 * line as "NAME: message", where NAME is the template word for the first line and for a line with no name. A line of a known name has the tree
 * of its value, with the kit's color for each color name, or null when it has a fault. A second line of a name, a line of a name that the
 * template does not have, and a line with no name are faults and not lines. The kit-written line $/v = VALUE is the line control.
 */
export function readTemplate(text) {
  const [first = "", ...rest] = text.split("\n").filter((line) => line.trim() && !line.trim().startsWith("#")), word = TEMPLATE_HEAD.exec(first)?.[1];
  const faults = [], lines = [], fault = (name, message) => { faults.push(`${name}: ${message}`); return message; };
  const read = (raw, parse) => { try { return parse(raw.trim()); } catch (failure) { if (failure instanceof DslError) return failure; throw failure; } };
  const head = read(first, parseExpression), headless = head instanceof DslError ? head.message : headFault(head, word);
  if (headless) fault(word, headless);
  lines.push({ name: word, raw: first.trim(), tree: headless ? null : head });
  for (const raw of rest) {
    const one = lineOf(raw), name = one?.name;
    if (!one || one.head) { fault(word, `${JSON.stringify(raw.trim().slice(0, 40))} is not a line of the template. Its lines are the first line, ${LINES[word]}`); continue; }
    if (one.gone) { fault(one.gone, "a template file has no delete line. Only a repair or a change reply deletes a line"); continue; }
    if (name !== "control" && name !== "card" && !PART[word].test(name)) { fault(name, `the template ${word} has no line ${name}. Its lines are ${LINES[word]}`); continue; }
    if (lines.some((line) => line.name === name)) { fault(name, `this is a second line ${name}. Each line has its own name`); continue; }
    const entry = read(raw, parseLine);
    let tree = entry instanceof DslError ? null : entry.tree, message = entry instanceof DslError ? entry.message : null, source = raw.trim();
    if (tree && name === "control") message = entry.kind === "state" ? (isText(tree) || numberOf(tree) !== undefined ? null : "$/v = VALUE takes a number or a text in quotes") : controlFault(tree, word);
    else if (tree) {
      // The kept line has the cut text, so the stored file and a later change have it too.
      const short = cutLong(tree, name, word);
      if (short !== tree) { tree = short; source = `${name} = ${show(tree)}`; }
      const shape = name !== "card" && word === "scene", path = nodesIn(tree, (node) => node.k === "path" && !readsV(node))[0];
      message = (name === "card" ? cardFault(tree, word) : partFault(tree, word)) ?? (path ? `a line reads only $/v, the value of the control, and this line reads ${show(path)}` : null);
      if (shape && !message) message = colorFault(tree = colored(tree));
      // The choice of items shows their labels, so it cannot tell two items of one label apart.
      const twin = !message && word === "items" && name !== "card" && lines.find((line) => line.tree && PART.items.test(line.name) && line.tree.args[0].v === tree.args[0].v);
      if (twin) message = `the label ${show(tree.args[0])} is the label of ${twin.name} too. Each item needs a label of its own: the choice shows the labels`;
    }
    if (message) fault(name, message);
    lines.push({ name, raw: source, tree: message ? null : tree });
  }
  lines.sort((a, b) => rank(a.name, word) - rank(b.name, word));
  return { word, lines, faults };
}

/**
 * The template file after a repair or a change reply: a line of a name that the file has goes in its place, a new part after the last part,
 * a new control after the first line, and each other new line at the end. A delete removes the line of its name, and a new first line replaces
 * the first line. A line with no name, such as prose or a fence, goes.
 */
export function mergeTemplate(stored, reply) {
  let lines = stored.split("\n").map(lineOf).filter((line) => line && !line.gone);
  const last = (test) => lines.reduce((found, line, index) => (test(line) ? index : found), -1), part = (line) => /^[si]\d+$/.test(line.name);
  for (const line of reply.split("\n").map(lineOf).filter(Boolean)) {
    if (line.gone) { lines = lines.filter((one) => one.head || one.name !== line.gone); continue; }
    const same = lines.findIndex((one) => (line.head ? one.head : !one.head && one.name === line.name));
    if (same >= 0) { lines[same] = line; continue; }
    const head = last((one) => one.head);
    lines.splice(line.head ? 0 : part(line) ? Math.max(last(part), last((one) => one.name === "control"), head) + 1 : line.name === "control" ? head + 1 : lines.length, 0, line);
  }
  return lines.map((line) => line.raw).join("\n") + "\n";
}

/** The parts of a template that are drawn: each part line with no fault that is not left out. */
const drawnParts = (read, leftOut) => read.lines.filter((line) => line.tree && !leftOut.has(line.name) && PART[read.word].test(line.name));
/**
 * The value that $/v keeps when the control is left out: the start of a slider or a choice, the first item of items, or 0 when the file has no
 * control line that the kit can read.
 */
function startOf(read, leftOut) {
  if (read.word === "items") return drawnParts(read, leftOut)[0]?.tree.args[0] ?? { k: "lit", v: 0 };
  const line = read.lines.find((one) => one.name === "control");
  let tree = line?.tree;
  try { tree ??= line && parseLine(line.raw)?.tree; } catch { tree = null; }
  const start = tree?.k !== "call" ? tree : tree.name === "slider" ? tree.args[4] : tree.name === "choice" ? tree.args[2] ?? tree.args[1]?.items?.[0] : null;
  return isText(start) || numberOf(start) !== undefined ? start : { k: "lit", v: 0 };
}

/**
 * The app file of a template with each line of leftOut left out: the app line, the drawing of 300 × 220, the control (or $/v = START for a
 * control that is left out or has a fault), and the card. Null when the first line has a fault: a stored file with one then fails the change
 * rules as a mismatch (change.js).
 */
export function expandTemplate(read, leftOut) {
  const { word } = read, [{ tree: head }] = read.lines, parts = drawnParts(read, leftOut), line = (name) => read.lines.find((one) => one.name === name && one.tree && !leftOut.has(name));
  if (!head) return null;
  const control = line("control")?.tree, card = line("card")?.tree, title = show(head.args[0]);
  const out = [`app(${title}, lang=${show(head.named.lang)}, preset="clean", palette=${show(head.named.palette ?? { k: "lit", v: "blue" })})`];
  let shapes = parts.map((part) => show(part.tree));
  if (word === "items") {
    out.push(`$/items := [${parts.map(({ tree: { args: [label, sign, text] } }) => `{label: ${show(label)}, sign: ${show(sign)}, text: ${show(text)}}`).join(", ")}]`, "$/now := find($/items, $label == $/v)");
    // The sign is large when it is one or two characters, and smaller for a word, so a sign at 1.25 times its cap fits the 300 px of the drawing.
    // A preview with no item yet draws nothing, so the first item only adds to the picture.
    shapes = parts.length ? ['text(150, 95, $/now/sign, "accent", len($/now/sign) <= 2 ? 72 : (len($/now/sign) <= 6 ? 44 : 24))', 'text(150, 190, $/v, "ink", 20)'] : [];
  } else if (word === "cells" && control?.k === "call") {
    // Rows of at most ten cells, filled evenly, in the top 176 px, and the caption "K / N = P%" under them. One row of few cells gets taller cells.
    const count = numberOf(control.args[2]), rows = Math.ceil(count / 10), cols = Math.ceil(count / rows);
    const pitch = rows > 1 ? Math.min(Math.floor(280 / cols), Math.floor(176 / rows)) : Math.min(56, Math.floor(280 / cols)), side = pitch - 4, tall = rows > 1 ? side : Math.min(60, Math.round(side * 1.4));
    const x0 = Math.round((WIDTH - (cols * pitch - 4)) / 2), y0 = 8 + Math.round((176 - ((rows - 1) * pitch + tall)) / 2);
    shapes = [`each(${count}, rect(${x0} + ($index % ${cols}) * ${pitch}, ${y0} + floor($index / ${cols}) * ${pitch}, ${side}, ${tall}, $index < $/v ? "accent" : "muted", 3))`,
      `text(150, 202, text($/v) + " / ${count} = " + text(round($/v / ${count} * 100)) + "%", "ink", 18)`];
  }
  out.push(`need picture = drawing(${title}, ${WIDTH}, ${HEIGHT}, [${shapes.join(", ")}])`);
  const [label, ...args] = control?.args ?? [], start = show(startOf(read, leftOut));
  if (control?.k !== "call") out.push(`$/v = ${control ? show(control) : start}`);
  else if (control.name === "slider") out.push(`need v = input("slider", ${show(label)}, ${start}, min=${show(args[0])}, max=${show(args[1])}, step=${show(args[2])})`);
  else out.push(`need v = input("choice", ${show(label)}, ${start}, options=${show(word === "items" ? { k: "list", items: parts.map((part) => part.tree.args[0]) } : args[0])})`);
  if (card) out.push(`need card = card(${show(card.args[0])}, ${word === "items" ? "$/now/text" : show(card.args[1])})`);
  return out.join("\n") + "\n";
}

/** The text to store: each line with no fault that is not left out, and $/v = START in the place of a control that is left out or has a fault. */
const keptOf = (read, leftOut) => read.lines.flatMap((line) => (line.name === "control" && (!line.tree || leftOut.has("control")) ? [`$/v = ${show(startOf(read, leftOut))}`]
  : line.tree && !leftOut.has(line.name) ? [line.raw] : [])).join("\n") + "\n";

/**
 * The owner of each fault of the compile of an expansion, as a map of a line name to its faults. A fault of need v belongs to control and a fault
 * of need card to card. Each other fault belongs to each part whose compile with the first line and the control alone has faults, and the
 * faults of that compile name the part in the place of the need. A scene whose first line and control alone have faults gives them to control.
 */
function ownersOf(read, leftOut, faults, build) {
  const owners = new Map(), add = (name, fault) => owners.set(name, [...(owners.get(name) ?? []), `${name}: ${fault.replace(/^need \w+: /, "")}`]), rest = [];
  for (const fault of faults) /\bneed v\b/.test(fault) ? add("control", fault) : /\bneed card\b/.test(fault) ? add("card", fault) : rest.push(fault);
  if (!rest.length) return owners;
  const parts = drawnParts(read, leftOut).map((part) => part.name), alone = (names) => build(expandTemplate(read, new Set([...leftOut, ...owners.keys(), "card", ...names]))).faults;
  if (read.word === "scene" && !owners.has("control")) alone(parts).forEach((fault) => add("control", fault));
  for (const part of parts) alone(parts.filter((other) => other !== part)).forEach((fault) => add(part, fault));
  return owners;
}

/**
 * The compile of a template file, with build, the compile of compile.js, which comes after this module in the bundle (as change.js nextState
 * takes it). stored is the template file of the stored app of a change, or null for a new screen.
 * A line with a fault is left out: a change takes the stored version of that line instead. A control that no line reads changes nothing, so
 * it is left out too. When the expansion has kit faults, each owner (ownersOf) is left out and the kit compiles once more.
 * Returns {built, word, kept, expanded, leftOut, holds}. built is the result of build for expanded, the expansion of kept, with each fault of a line that
 * was left out. A whole fault gives built with no definition and expanded null: a fault of the first line, no part with no fault, a cells file
 * with no control, or a kit fault of the last compile.
 * preview is true for the lines so far of a stream (compile.js previewStep): a control that no line reads yet stays, and only a fault of the first
 * line or a kit fault of the last compile is whole, so the first line alone gives the frame. holds is what kept draws: "card", "parts" (a shape, an
 * item, or the cells of a control), or "frame" (the first line, maybe the control).
 */
export function buildTemplate(text, stored, build, preview = false) {
  const old = stored === null ? null : readTemplate(stored), faults = [], out = new Set();
  let read, leftOut, kept, expanded = null, built = null;
  const settle = () => {
    const back = old ? old.lines.filter((line) => line.tree && out.has(line.name)) : [];
    if (back.length) { text = mergeTemplate(text, back.map((line) => line.raw).join("\n")); read = readTemplate(text); faults.push(...read.faults); }
    leftOut = new Set([...read.lines.filter((line) => !line.tree).map((line) => line.name), ...[...out].filter((name) => !back.some((line) => line.name === name))]);
    const control = read.lines.some((line) => line.name === "control" && line.tree?.k === "call") && !leftOut.has("control");
    if (!preview && control && read.word === "scene" && !read.lines.some((line) => line.tree && line.name !== "control" && !leftOut.has(line.name) && nodesIn(line.tree, readsV).length)) {
      faults.push("control: the control changes nothing on the screen, because no line reads $/v. Make a part read $/v, such as visible=$/v > 2");
      leftOut.add("control");
    }
    kept = keptOf(read, leftOut);
  };
  // The first line's own fault is in faults already.
  const drawn = (test) => read.lines.some((line) => line.tree && !leftOut.has(line.name) && test(line)), cells = (line) => line.name === "control" && line.tree.k === "call";
  const whole = () => (!read.lines[0].tree ? faults[0] : preview ? null
    : read.word === "cells" ? (drawn(cells) ? null : "cells: the cells need a line control = slider(LABEL, 0, COUNT, 1, START) with no fault")
      : drawnParts(read, leftOut).length ? null : `${read.word}: the picture needs a ${read.word === "scene" ? "shape" : "item"} with no fault`);
  read = readTemplate(text);
  faults.push(...read.faults);
  read.lines.filter((line) => !line.tree).forEach((line) => out.add(line.name));
  settle();
  const compile = () => build(expanded = expandTemplate(readTemplate(kept), new Set()));
  if (!whole()) {
    built = compile();
    if (built.faults.length) {
      for (const [name, list] of ownersOf(read, leftOut, built.faults, build)) { faults.push(...list); out.add(name); }
      settle();
      built = whole() ? null : compile();
    }
  }
  const lacking = whole();
  faults.push(...(lacking ? [lacking] : built.faults.map((fault) => `${read.word}: ${fault}`)));
  const unique = [...new Set(faults)], broken = Boolean(lacking) || built.faults.length > 0;
  const holds = drawn((line) => line.name === "card") ? "card" : drawnParts(read, leftOut).length || (read.word === "cells" && drawn(cells)) ? "parts" : "frame";
  return { built: broken ? { faults: unique, notes: built?.notes ?? [], definition: null, language: null, hole: false } : { ...built, faults: unique },
    word: read.word, kept, expanded: broken ? null : expanded, leftOut: [...leftOut], holds };
}
