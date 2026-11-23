// The library of a scene (stage 6, L4 to L6): the generic elements (elements.js) and the ready objects (catalog.js, which build.js writes
// from kit/library/objects). A template line places one of them:
//
//   s3 = use("heart", 150, 150, 180, mark="left_ventricle", parts=true)      a ready object: its name, its center, its size
//   s4 = use("thermometer", 60, 150, 120, [0.7], label="Вода")              an element: its name, its center, its size, and its numbers
//
// The kit expands the line into the shapes of the kind registry (kinds.js) when it expands the file (template.js), so the run-time draws
// no catalog. A name that the library does not have is a fault with that name (the miss log of spec 12.8).
//
// A ready object is a vector entry (layers of paths in the roles of the theme) or an image entry (stage 6, I1): a ready picture that the host
// draws from its assets with its own colors, in both themes. Both kinds have the same names, parts, labels, and marks.

import { parseExpression, show } from "./dsl.js";
import { isColor } from "./core.js";
import { fixed } from "./kinds.js";
import { ELEMENTS } from "./elements.js";
import { namedColor, namedColors } from "./form-base.js";
import { OBJECTS } from "./catalog.js";

/** The size of the box of an object in the units of its entry: its longer side. */
export const UNITS = 100;
/** The size at which each entry must read as its thing (L5, the review sheet): a rule of the catalog review, not of a scene. */
export const SMALLEST = 56;
/** The smallest SIZE of an object in a scene (fix list 3, H2): a small Sun beside a large Earth is meant. */
export const LEAST = 24;
/** The largest number of path segments of one entry (L5). A gradient, a highlight, and the ground shadow cost no segment. */
export const SEGMENTS = 40;
/** The named arguments of a library line. */
export const USE_NAMED = { label: "a text under it", mark: "the name of a part to mark", parts: "true to name each part", turn: "degrees", color: "a color of an element" };

/** The state of the library in one call of the host: the ids of the image entries whose image the host cannot open (compile.js sets it). */
export const LIBRARY = { missing: new Set() };

/** The entry of a name: an object or an element, by its name or by the id of an object (area.name). An image entry with no image is no entry. */
export function entryOf(name) {
  if (typeof name !== "string") return null;
  const key = name.includes(".") ? name.split(".").pop() : name;
  if (Object.hasOwn(OBJECTS, key) && (key === name || OBJECTS[key].id === name) && !LIBRARY.missing.has(OBJECTS[key].id)) return { kind: "object", name: key, entry: OBJECTS[key] };
  return Object.hasOwn(ELEMENTS, name) ? { kind: "element", name, entry: ELEMENTS[name] } : null;
}

const isText = (node) => node?.k === "lit" && typeof node.v === "string";
const numberOf = (node) => (node?.k === "lit" && typeof node.v === "number" ? node.v : node?.k === "un" && node.op === "-" && typeof node.arg?.v === "number" ? -node.arg.v : undefined);
const pathIn = (node) => node && typeof node === "object" && (node.k === "path" || Object.values(node).some(pathIn));
/** The names of the parts of an object in a language, with English for a language that the entry does not have. */
export const partName = (part, lang) => part.names[lang] ?? part.names.en;
/** The name of a part in a picture: the name that the model gives in names= of the line (stage 7b, R3), else partName. Only the kit writes names=. */
const shownName = (part, lang, named) => named.names?.v?.[part.id] ?? partName(part, lang);

/** The name of a library line whose name the library does not have (the miss log of spec 12.8), or null. */
export const missOf = (tree) => (tree?.k === "call" && tree.name === "use" && isText(tree.args?.[0]) && !entryOf(tree.args[0].v) ? tree.args[0].v : null);

/**
 * The fault of a library line use(NAME, X, Y, SIZE, [NUMBERS], ...), or null. A mark, a label, and the numbers are literals or formulas of
 * $/v. An unknown name is a fault that names it (missOf), so the repair draws the thing with shapes or an emoji. lang is the language of the
 * scene, whose part names must fit at the sides of the object.
 */
export function useFault(tree, picture = [300, 300], lang = "en") {
  const [name, ...rest] = tree.args ?? [], form = "a library line is use(NAME, X, Y, SIZE), with [NUMBERS] of an element after SIZE";
  if (!isText(name)) return `${form}, and NAME is a name in quotes`;
  const found = entryOf(name.v);
  if (!found) return `the library has no ${JSON.stringify(name.v)}. Draw it with shapes, or with an emoji in a text shape`;
  if (rest.length < 3 || rest.length > (found.kind === "element" ? 4 : 3)) return found.kind === "element" ? `${form}: ${name.v}(${found.entry.numbers.join(", ")})` : `${form}, and ${name.v} is an object: it takes no numbers`;
  if (rest.length === 4 && rest[3].k !== "list") return `the numbers of ${name.v} are a list: [${found.entry.numbers.join(", ")}]`;
  if (rest.length === 4 && rest[3].items.length > found.entry.numbers.length) return `${name.v} takes at most ${found.entry.numbers.length} numbers: ${found.entry.numbers.join(", ")}`;
  // A plain number above the cap of its element would draw a wrong picture (the draw rule shows at most the cap), so it is a fault to repair.
  const over = rest.length === 4 && found.entry.numbers.map((number, index) => [number, numberOf(rest[3].items[index]), found.entry.caps?.[number]]).find(([, value, cap]) => value > cap);
  if (over) return `the ${over[0]} of ${name.v} is at most ${over[2]}, and this line has ${over[1]}`;
  const other = Object.keys(tree.named).find((key) => key !== "visible" && !Object.hasOwn(USE_NAMED, key));
  if (other) return `a library line takes only ${Object.keys(USE_NAMED).join("=, ")}=, and visible= by name, and ${other}= is not one`;
  if (tree.named.label && !(isText(tree.named.label) && tree.named.label.v.trim()) && !pathIn(tree.named.label)) return "label= is a text in quotes";
  if (isText(tree.named.label) && [...tree.named.label.v].length > 28) return `label= has ${[...tree.named.label.v].length} characters, and its cap is 28`;
  const parts = found.entry.parts ?? [], mark = tree.named.mark;
  if (mark && found.kind === "element") return `${name.v} is an element: it has no parts to mark`;
  if (isText(mark) && !parts.some((part) => part.id === mark.v || Object.values(part.names).includes(mark.v))) return `${name.v} has no part ${JSON.stringify(mark.v)}. Its parts are ${parts.map((part) => part.id).join(", ")}`;
  if (tree.named.color && found.entry.image) return `${name.v} is a ready picture with its own colors, so it takes no color=`;
  if (tree.named.color && isText(tree.named.color) && !isColor(tree.named.color.v) && !namedColor(tree.named.color.v)) return `the color ${JSON.stringify(tree.named.color.v)} is not a color of the kit`;
  if (found.entry.text && !tree.named.label) return `${name.v} shows the text of label=, and this line has no label=`;
  // A SIZE under LEAST or past the picture is a fault (H2).
  const size = numberOf(rest[2]), least = found.kind === "object" ? LEAST : 12;
  if (size !== undefined && (size < least || size > 300)) return `the SIZE of ${name.v} is from ${least} to 300, and this line has ${size}`;
  return roomFault(tree, tree.named, picture, lang);
}

/**
 * The fault of the part names of a library line of an object whose X, Y, and SIZE are numbers, or null. The names stand at the sides of the
 * object, inside the picture, and leave the object its SIZE, or SMALLEST for a larger SIZE. named holds parts=, mark=, and the names that the model gives.
 */
export function roomFault(tree, named, picture, lang) {
  const found = entryOf(tree.args[0].v), at = tree.args.slice(1, 4).map(numberOf);
  const placed = found.kind === "object" && at.every((value) => value !== undefined) ? layoutOf(found, named, at, picture, lang) : null;
  if (placed && (placed.size < Math.min(SMALLEST, at[2]) - 0.01 || placed.labels.some((one) => one.clipped))) {
    return `the names of the parts of ${tree.args[0].v} leave it too little room: name fewer parts, write shorter names, or give it more height`;
  }
  return null;
}

/** An expression with each sum, product, and sin or cos of plain numbers computed, so a line of plain numbers expands to plain numbers. */
function folded(node) {
  if (!node || typeof node !== "object") return node;
  const out = Array.isArray(node) ? node.map(folded) : Object.fromEntries(Object.entries(node).map(([key, inner]) => [key, folded(inner)]));
  const n = (inner) => numberOf(inner), round = (value) => ({ k: "lit", v: Math.round(value * 100) / 100 });
  if (out.k === "bin" && n(out.left) !== undefined && n(out.right) !== undefined && "+-*/".includes(out.op) && !(out.op === "/" && n(out.right) === 0)) {
    const [a, b] = [n(out.left), n(out.right)];
    return round(out.op === "+" ? a + b : out.op === "-" ? a - b : out.op === "*" ? a * b : a / b);
  }
  if (out.k === "call" && ["sin", "cos"].includes(out.name) && out.args.length === 1 && n(out.args[0]) !== undefined) return round(Math[out.name]((n(out.args[0]) * Math.PI) / 180));
  return out;
}
/** A shape text with its plain sums computed (folded). */
const tidy = (text) => show(folded(parseExpression(text)));
/** An argument as a part of a formula: a number as it is, else the formula in brackets. */
const term = (node) => (numberOf(node) !== undefined ? String(numberOf(node)) : `(${show(node)})`);

/** The color of a layer: its role with its tone step, such as "error+1". */
const tone = (role, step = 0, alpha) => `${role}${step > 0 ? `+${step}` : step < 0 ? step : ""}${alpha !== undefined ? `@${alpha}` : ""}`;
/** The points of an ellipse as a smooth closed path: 8 points, turned deg degrees around its center (clockwise on the screen). */
const ellipse = ([cx, cy, rx, ry, deg = 0]) => {
  const [c, s] = [Math.cos((deg * Math.PI) / 180), Math.sin((deg * Math.PI) / 180)];
  return Array.from({ length: 8 }, (_, index) => {
    const [ex, ey] = [rx * Math.cos((index * Math.PI) / 4), ry * Math.sin((index * Math.PI) / 4)];
    return [cx + ex * c - ey * s, cy + ex * s + ey * c].map((v) => Math.round(v * 10) / 10);
  }).flat();
};

/**
 * The shapes of one layer of an object, in the units of its entry: the fill (a path, a circle, or an ellipse) with its tone, its gradient
 * between two tone steps, its outline in a darker tone (a flat figure), and its highlight. paint gives the color text of a tone of the
 * role, and opacity is the opacity text, or null.
 */
function layerShapes(layer, paint, opacity) {
  const fade = opacity ? `, opacity=${opacity}` : "", out = [];
  const [from = layer.tone ?? 0, to, kind] = layer.gradient ?? [], shade = layer.gradient ? `, gradient=${paint(to)}, ${kind === "linear" ? "" : "radial=1, "}`.replace(/, $/, "") : "";
  const fill = layer.circle ? `circle(${layer.circle.join(", ")}, ${paint(from)}${shade}${fade})`
    : `path([${(layer.ellipse ? ellipse(layer.ellipse) : layer.path).join(", ")}], ${paint(from)}, smooth=${Boolean(layer.ellipse || layer.smooth)}, closed=true${shade}${fade})`;
  if (!layer.line) out.push(fill);
  if (layer.outline !== undefined || layer.line) {
    const width = layer.width ?? 1.4, shape = layer.circle ? `circle(${layer.circle.join(", ")}, ${paint(layer.outline ?? layer.tone ?? 0, undefined, true)}, stroke=true${fade})`
      : `path([${(layer.ellipse ? ellipse(layer.ellipse) : layer.path).join(", ")}], ${paint(layer.outline ?? layer.tone ?? 0, undefined, true)}, ${width}, smooth=${Boolean(layer.ellipse || layer.smooth)}, closed=${!layer.open}, stroke=true${fade})`;
    out.push(shape);
  }
  if (layer.highlight) {
    const [x, y, r] = layer.highlight;
    out.push(`circle(${x}, ${y}, ${r}, ${paint(3, 80)}, gradient=${paint(3, 0)}, radial=0${fade})`);
  }
  return out;
}

/**
 * The parts that a line names: each part with parts=true or with a mark that is a formula (only the marked one shows), else the marked part when
 * the mark is a name. A turned object names no part.
 */
function namedParts(found, { mark, parts, turn }) {
  const list = found.kind === "object" ? found.entry.parts ?? [] : [];
  return turn ? [] : parts?.v === true || (mark && !isText(mark)) ? list : isText(mark) ? list.filter((part) => part.id === mark.v || Object.values(part.names).includes(mark.v)) : [];
}
/** The size, the line height, the gap to the object, and the margin of the part labels, in the units of the picture. */
export const LABEL = { size: 12, line: 14, gap: 8, margin: 4 };
/** The width of a text line of the part labels: a wide letter (м, ю, m, w, a capital) takes 0.85 of the size, each other character 0.56. */
export const lineWidth = (text) => [...text].reduce((sum, char) => sum + (/[мшщжюыфmw]/.test(char) || char !== char.toLowerCase() ? 0.85 : 0.56), 0) * LABEL.size;
/** The lines of a part label: a name of more than 8 characters breaks at the space that gives the shortest longer line, if each line keeps 3 characters. */
export function labelLines(text) {
  const chars = [...text];
  if (chars.length <= 8) return [text];
  const breaks = chars.map((char, index) => (char === " " && index >= 3 && chars.length - index - 1 >= 3 ? index : -1)).filter((index) => index > 0);
  if (!breaks.length) return [text];
  const best = breaks.reduce((a, b) => (Math.max(a, chars.length - a - 1) <= Math.max(b, chars.length - b - 1) ? a : b));
  return [chars.slice(0, best).join(""), chars.slice(best + 1).join("")];
}

/**
 * The place of an object with the labels of its named parts (F2), as {x, y, size, labels}. A part in the left half of the object gets its
 * label at the left, each other part at the right. With labels, the object stands in the middle of the space that the two label columns
 * leave in the width of the picture, as large as that space permits and at most SIZE, at the height Y. Each label is {part, lines, px, py, x,
 * y, align, clipped}: px, py is the point of its part, x, y the end of its leader line, and clipped is true for a label that leaves the
 * picture. With no named part, the object keeps X, Y, and SIZE.
 */
export function layoutOf(found, named, [X, Y, S], [width, height], lang) {
  const parts = namedParts(found, named), [w, h] = found.entry.box, { line, gap, margin } = LABEL;
  if (!parts.length) return { x: X, y: Y, size: S, labels: [] };
  const items = parts.map((part) => ({ part, lines: labelLines(shownName(part, lang, named)), left: part.label[0] < 0 }));
  const column = (left) => Math.max(0, ...items.filter((one) => one.left === left).flatMap((one) => one.lines.map(lineWidth)));
  const [L, R] = [column(true), column(false)];
  const xl = margin + (L ? L + gap : 0), xr = width - margin - (R ? R + gap : 0);
  const size = Math.max(0, Math.min(S, ((xr - xl) * UNITS) / w, ((height - 2 * margin) * UNITS) / h));
  const k = size / UNITS, x = (xl + xr) / 2, y = Math.min(Math.max(Y, margin + (h * k) / 2), height - margin - (h * k) / 2);
  const labels = [];
  for (const left of [true, false]) {
    const side = items.filter((one) => one.left === left).map((one) => ({ ...one, px: x + one.part.label[0] * k, py: y + one.part.label[1] * k })).sort((a, b) => a.py - b.py);
    // Each label starts at the height of its point. Then the labels of one side move down, so none overlaps the one above it, and up, so the
    // last one stays inside the picture.
    let bottom = -Infinity;
    for (const one of side) { one.y = Math.max(one.py, bottom + 2 + (one.lines.length * line) / 2); bottom = one.y + (one.lines.length * line) / 2; }
    let top = height - margin;
    for (const one of [...side].reverse()) { one.y = Math.min(one.y, top - (one.lines.length * line) / 2); top = one.y - (one.lines.length * line) / 2 - 2; }
    for (const one of side) {
      labels.push({ ...one, align: left ? "end" : "start", x: left ? x - (w * k) / 2 - gap : x + (w * k) / 2 + gap, clipped: one.y - (one.lines.length * line) / 2 < margin - 0.5 });
    }
  }
  return { x, y, size, labels };
}

/**
 * The shapes of a library line (useFault gives it no fault), as {shapes, lines}: an object as one group of its layers with its part labels at
 * the sides, or an element as one group of the shapes of its draw rule. lines are app lines before the drawing: a mark that is a formula of
 * $/v becomes the value $/KEYm once, so each layer reads it with no copy of the formula. lang picks the names of the parts, and picture is
 * [width, height] of the drawing, for the places of the labels.
 */
export function useShapes(tree, lang, picture = [300, 300], key = "s") {
  const [name, x, y, size, numbers] = tree.args, found = entryOf(name.v), { label, mark: given, parts, turn, color, visible } = tree.named;
  const shown = visible ? `, visible=${show(visible)}` : "", [X, Y, S] = [x, y, size].map(term), lines = [];
  if (found.kind === "element") {
    // A number that is a formula becomes the value $/KEYa, $/KEYb, … once (fix list 2, G2): a draw rule writes each number into many shapes.
    const entry = found.entry, values = numbers?.items ?? [], n = entry.numbers.map((_, index) => {
      const value = values[index];
      if (!value) return String(entry.defaults[index]);
      if (numberOf(value) !== undefined || value.k === "path") return term(value);
      const name = `$/${key}${"abc"[index]}`;
      lines.push(`${name} := ${show(value)}`);
      return name;
    });
    const c = color ? show(namedColors(color)) : JSON.stringify(entry.color), u = numberOf(size) !== undefined ? String(fixed(100 / numberOf(size))) : `(100 / ${S})`;
    // An element that an angle turns (entry.turn) adds turn= of the line to it.
    const turned = [entry.turn !== undefined ? n[entry.turn] : null, turn ? `(${show(turn)})` : null].filter((one) => one && one !== "0").join(" + ");
    const shapes = [tidy(`group(${X}, ${Y}, ${S} / 100, [${entry.draw({ n, c, u, text: entry.text && label ? show(label) : undefined }).join(", ")}]${turned ? `, turn=${turned}` : ""}${shown})`)];
    if (label && !entry.text) {
      const [lx, ly, align] = entry.label ?? [0, 62, "center"];
      shapes.push(tidy(`text(${X} + ${S} * ${lx / 100}, ${Y} + ${S} * ${ly / 100}, ${show(label)}, "ink", 13${align === "center" ? "" : `, align=${JSON.stringify(align)}`}${shown})`));
    }
    return { shapes, lines };
  }
  const entry = found.entry, [, h] = entry.box, list = entry.parts ?? [];
  const fixedMark = isText(given) ? list.find((part) => part.id === given.v || Object.values(part.names).includes(given.v)) : null;
  // A mark that is a formula becomes the id of its part once: $/KEYn is its text in lower case, and $/KEYm the id of the part that it names, so
  // each layer compares one value with one id and the source stays small.
  const mark = given && !fixedMark ? `$/${key}m` : null;
  if (mark) {
    const raw = `$/${key}n`, name = (part) => shownName(part, lang, tree.named).toLowerCase(), names = list.filter((part) => name(part) !== part.id);
    lines.push(`${raw} := lower(${show(given)})`, `${mark} := ${names.reduceRight((rest, part) => `(${raw} == ${JSON.stringify(name(part))} ? ${JSON.stringify(part.id)} : ${rest})`, raw)}`);
  }
  const marked = (part) => (!given || !part ? "false" : fixedMark ? String(fixedMark === part) : `${mark} == ${JSON.stringify(part.id)}`);
  const shapes = [];
  // The ground shadow darkens what is under it in both themes, so it is black with an alpha: the draw path gives it, not the entry.
  if (entry.shadow) shapes.push(`path([${ellipse(entry.shadow).join(", ")}], "#40000000", smooth=true, closed=true, gradient="#00000000", radial=0)`);
  if (entry.image) {
    // An image entry (I4, I5): its picture fills a square of UNITS around the center, and its box is the part of the square that the picture
    // covers. Over the picture, a marked part gets an accent ring of its mark radius around its point. The picture keeps its colors.
    shapes.push(`image(${-UNITS / 2}, ${-UNITS / 2}, ${UNITS}, ${UNITS}, ${JSON.stringify(entry.id)})`);
    for (const part of list) shapes.push(...ringOf(part.label, part.ring, MARK_RIM, marked(part)));
  }
  // A marked part keeps its own colors, and each other part keeps its full opacity (F3). Under the first layer of the marked part, an accent
  // rim of MARK_RIM units follows the outline of each of its layers, so the rim shows only around the part.
  const ringed = new Set();
  for (const layer of entry.layers ?? []) {
    const part = list.find((one) => one.id === layer.part), on = marked(part);
    if (on !== "false" && !ringed.has(part)) {
      ringed.add(part);
      const when = on === "true" ? "" : `, visible=${on}`;
      shapes.push(...entry.layers.filter((one) => one.part === part.id).map((one) => rimOf(one, when)));
    }
    shapes.push(...layerShapes(layer, (step, alpha) => JSON.stringify(tone(layer.role, step, alpha)), null));
  }
  // The labels of the parts: with parts=true each part, else the marked part. Each one is a text at the side and a thin leader line to its point.
  // With a mark that is a formula and no parts=true, each label shows only while its part is the marked one. With labels, the kit places the
  // object between the two label columns (layoutOf).
  const at = [X, Y, S].map(Number), alone = mark && parts?.v !== true;
  const placed = at.every(Number.isFinite) ? layoutOf(found, tree.named, at, picture, lang) : null;
  const [GX, GY, GS] = placed?.labels.length ? [placed.x, placed.y, placed.size].map(fixed) : [X, Y, S];
  const out = [tidy(`group(${GX}, ${GY}, ${GS} / ${UNITS}, [${shapes.join(", ")}]${turn ? `, turn=${show(turn)}` : ""}${shown})`)];
  out.push(...labelShapes(placed?.labels ?? [], marked, alone, visible));
  if (label) out.push(tidy(`text(${GX}, ${GY} + ${GS} * ${h / 200} + 12, ${show(label)}, "ink", 13${shown})`));
  return { shapes: out, lines };
}

/** A test text as a color: yes while on holds, else no. on is "true", "false", or a formula. */
const choose = (on, yes, no) => (on === "false" ? JSON.stringify(no) : on === "true" ? JSON.stringify(yes) : `(${on} ? ${JSON.stringify(yes)} : ${JSON.stringify(no)})`);
/**
 * The shapes of the part labels of layoutOf: for each label a thin leader line to its point, a dot at the point, and its text lines. marked
 * gives the test text of a part. A marked part has the accent. alone is true when each label shows only while its part is the marked one.
 * visible is the visible= formula of the line, or undefined.
 */
function labelShapes(labels, marked, alone, visible) {
  const shown = visible ? `, visible=${show(visible)}` : "", out = [];
  for (const one of labels) {
    const ink = alone ? '"accent"' : choose(marked(one.part), "accent", "ink"), end = one.align === "end" ? one.x + 3 : one.x - 3, [px, py, ty] = [one.px, one.py, one.y].map(fixed);
    const seen = alone ? `, visible=${visible ? `(${show(visible)}) && ` : ""}${marked(one.part)}` : shown;
    out.push(`line(${px}, ${py}, ${fixed(end)}, ${ty}, "ink@55", 1${seen})`, `circle(${px}, ${py}, 2.5, ${ink}${seen})`,
      ...one.lines.map((text, index) => `text(${fixed(one.x)}, ${fixed(one.y + (index - (one.lines.length - 1) / 2) * LABEL.line)}, ${JSON.stringify(text)}, ${ink}, ${LABEL.size}, align=${JSON.stringify(one.align)}${seen})`));
  }
  return out;
}
/** The accent ring of a marked point [x, y] with its radius and its width, while on holds (I4, I5): no shape when on is "false". */
const ringOf = ([x, y], radius, width, on, color = "accent") => (on === "false" ? [] : [`arc(${fixed(x)}, ${fixed(y)}, ${radius}, 0, 360, "${color}", ${width}${on === "true" ? "" : `, visible=${on}`})`]);

/**
 * A diagram of a thing with no ready object (stage 7b, R3): the model draws it with shapes in the drawing zone of FREE and gives each part
 * its name and a point [NAME, X, Y]. The kit names the parts with layoutOf and labelShapes, as the parts of an object whose box is the zone:
 * the names stand in the two side columns, and the drawing moves and shrinks with that object when the names need more room. The part of
 * the step ($/v is its name) has the accent ring at its point and the accent name. Returns {shapes, lines}: the shapes of the drawing, with
 * no names and no rings when named is false (a stream whose shapes can still grow), and the app lines before the drawing.
 */
export function freeShapes(shapes, points, picture = [300, 300], named = true) {
  const [cx, cy] = [picture[0] / 2, picture[1] / 2], placed = freeLayout(points, picture), k = fixed(placed.size / UNITS);
  // The number of the marked part is the value $/fp once, so each shape compares a short number and the source stays small.
  const index = (part) => points.findIndex(([name]) => name === part.id) + 1, marked = (part) => `$/fp == ${index(part)}`;
  const lines = [`$/fp := ${points.reduceRight((rest, [name], at) => `($/v == ${JSON.stringify(name)} ? ${at + 1} : ${rest})`, "0")}`];
  const moved = k !== 1 || placed.x !== cx || placed.y !== cy;
  // Each shape has a group of its own: the move comes from the points alone, so the drawing of a stream only grows (compile.js previewStep).
  const out = moved ? shapes.map((shape) => `group(${fixed(placed.x - k * cx)}, ${fixed(placed.y - k * cy)}, ${k}, [${shape}])`) : [...shapes];
  if (!named) return { shapes: out, lines: [] };
  // The model can paint a part in the accent color, so a wider ring in the surface color under the accent ring keeps the mark visible.
  for (const one of placed.labels) {
    out.push(...ringOf([one.px, one.py], FREE.ring, FREE.rim + FREE.halo, marked(one.part), "surface"), ...ringOf([one.px, one.py], FREE.ring, FREE.rim, marked(one.part)));
  }
  return { shapes: [...out, ...labelShapes(placed.labels, marked, false)], lines };
}
/**
 * The drawing zone of a diagram of a thing with no ready object, from x = zone[0] to x = zone[1] of the picture (the prompt says it), the
 * smallest scale of its drawing beside the names (fewest), the radius and the width of its accent ring, and the added width of the
 * surface ring under it (halo).
 */
export const FREE = { zone: [80, 220], fewest: 0.6, ring: 8, rim: 4, halo: 4 };
/**
 * The place of a diagram of a thing with no ready object (freeShapes): layoutOf of an object at the center of the picture whose box is the
 * zone of FREE and the height of the picture, with a part at each point [NAME, X, Y]. size / UNITS is the scale of the drawing.
 */
export function freeLayout(points, picture = [300, 300]) {
  const [cx, cy] = [picture[0] / 2, picture[1] / 2];
  const parts = points.map(([name, x, y]) => ({ id: name, names: { en: name }, label: [x - cx, y - cy] }));
  return layoutOf({ kind: "object", entry: { box: [FREE.zone[1] - FREE.zone[0], picture[1] - 2 * LABEL.margin], parts } }, { parts: { k: "lit", v: true } }, [cx, cy, UNITS], picture, "en");
}

/** The width of the accent rim of a marked part, in the units of its entry (F3). */
const MARK_RIM = 3;
/** The accent rim of one layer of a marked part: a stroke of its outline, MARK_RIM wider at each side than its fill or its line. when is its visible= text. */
function rimOf(layer, when) {
  const width = layer.line ? (layer.width ?? 1.4) + 2 * MARK_RIM : 2 * MARK_RIM;
  if (layer.circle) return `arc(${layer.circle.join(", ")}, 0, 360, "accent", ${width}${when})`;
  const points = layer.ellipse ? ellipse(layer.ellipse) : layer.path;
  return `path([${points.join(", ")}], "accent", ${width}, smooth=${Boolean(layer.ellipse || layer.smooth)}, closed=${!(layer.line && layer.open)}, stroke=true${when})`;
}

/** The number of path segments that an entry draws: each point of a path or an ellipse, again for its outline (L5 limit). An image entry draws none. */
export const segmentsOfEntry = (entry) => (entry.layers ?? []).reduce((sum, layer) => {
  const points = layer.circle ? 0 : layer.ellipse ? 8 : layer.path.length / 2;
  return sum + (layer.line ? 0 : points) + (layer.outline !== undefined || layer.line ? points : 0);
}, 0);
