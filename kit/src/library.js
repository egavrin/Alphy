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
export const USE_NAMED = { label: "a text under it", mark: "the name of a part to mark", parts: "true to put a dot at each part", turn: "degrees", color: "a color of an element" };

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
 * $/v. An unknown name is a fault that names it (missOf), so the repair draws the thing with shapes or an emoji. The name of a marked part
 * stands in a chip inside the picture (chipOf), so no name needs room at the sides of the object (stage A, K3).
 */
export function useFault(tree) {
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

/** The size of the text of a name chip, its side padding, its height, its gap to the ring of its part, and its margin to the edge of the picture. */
export const LABEL = { size: 12, pad: 6, height: 20, gap: 4, margin: 4 };
/** The width of a text line of a name chip: a wide letter (м, ю, m, w, a capital) takes 0.85 of the size, each other character 0.56. */
export const lineWidth = (text) => [...text].reduce((sum, char) => sum + (/[мшщжюыфmw]/.test(char) || char !== char.toLowerCase() ? 0.85 : 0.56), 0) * LABEL.size;
/**
 * The name chip of a part (stage A, K3): a rounded box in the surface color with the name in the accent color on one line. It stands centered
 * above the ring of radius ring around the point [x, y] when it has room there, else under it, and it moves inside the picture [width, height].
 * when is its visible= text.
 */
function chipOf([x, y], ring, name, [width, height], when = "") {
  const { size, pad, height: tall, gap, margin } = LABEL, wide = lineWidth(name) + 2 * pad, above = y - ring - gap - tall;
  const top = above >= margin ? above : Math.min(y + ring + gap, height - margin - tall), left = Math.min(Math.max(x - wide / 2, margin), width - margin - wide);
  return [`rect(${fixed(left)}, ${fixed(top)}, ${fixed(wide)}, ${tall}, "surface", ${tall / 2}${when})`, `text(${fixed(left + wide / 2)}, ${fixed(top + tall / 2)}, ${JSON.stringify(name)}, "accent", ${size}${when})`];
}
/** The dot of a part that is not the present one (K3): a small accent dot on a surface dot, so that it shows on a part in the accent color too. */
const dotOf = ([x, y], when = "") => [`circle(${fixed(x)}, ${fixed(y)}, ${DOT + 1.5}, "surface"${when})`, `circle(${fixed(x)}, ${fixed(y)}, ${DOT}, "accent"${when})`];
/** The radius of the dot of a part. */
const DOT = 2.5;
/** The room around the point of a part of a vector entry, which has no ring radius: its chip stands this far from the point, in units of the entry. */
const ROOM = 8;
/** The visible= text of a mark that shows while on holds ("true" or a formula) and while the line is visible (visible= of the line, or undefined). */
const whenOn = (on, visible) => {
  const tests = [visible && `(${show(visible)})`, on !== "true" && on].filter(Boolean);
  return tests.length ? `, visible=${tests.join(" && ")}` : "";
};

/**
 * The shapes of a library line (useFault gives it no fault), as {shapes, lines, marks}: an object as one group of its layers at X, Y, and
 * SIZE, or an element as one group of the shapes of its draw rule. lines are app lines before the drawing: a mark that is a formula of $/v
 * becomes the value $/KEYm once, so each layer reads it with no copy of the formula. marks are the name chip of the marked part and a dot at
 * each other part of parts=, which the drawing puts after each shape (stage A, K3). lang picks the names of the parts, and picture is
 * [width, height] of the drawing, which holds each chip.
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
    return { shapes, lines, marks: [] };
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
  // The marks of the parts (K3): the marked part has its name in a chip, and parts= (true, or the ids of the parts of a walk) puts a dot at each
  // other part. A mark that is a formula gives each part a chip that shows only while it is the marked one. The object keeps X, Y, and SIZE.
  // A turned object, or one whose place is a formula, has no marks.
  const out = [tidy(`group(${X}, ${Y}, ${S} / ${UNITS}, [${shapes.join(", ")}]${turn ? `, turn=${show(turn)}` : ""}${shown})`)];
  if (label) out.push(tidy(`text(${X}, ${Y} + ${S} * ${h / 200} + 12, ${show(label)}, "ink", 13${shown})`));
  const at = [X, Y, S].map(Number), k = at[2] / UNITS, dotted = (part) => parts?.v === true || (Array.isArray(parts?.v) && parts.v.includes(part.id));
  const dots = [], chips = [];
  if (at.every(Number.isFinite) && !turn) {
    for (const part of list) {
      const point = [at[0] + part.label[0] * k, at[1] + part.label[1] * k], on = marked(part);
      if (on !== "false") chips.push(...chipOf(point, (part.ring ?? ROOM) * k, shownName(part, lang, tree.named), picture, whenOn(on, visible)));
      if (dotted(part) && on !== "true") dots.push(...dotOf(point, whenOn(on === "false" ? "true" : `!(${on})`, visible)));
    }
  }
  return { shapes: out, lines, marks: [...dots, ...chips] };
}

/** The accent ring of a marked point [x, y] with its radius and its width, while on holds (I4, I5): no shape when on is "false". */
const ringOf = ([x, y], radius, width, on, color = "accent") => (on === "false" ? [] : [`arc(${fixed(x)}, ${fixed(y)}, ${radius}, 0, 360, "${color}", ${width}${on === "true" ? "" : `, visible=${on}`})`]);

/**
 * The marks of a diagram of a thing with no ready object (stage 7b, R3, and stage A, K3), as {lines, marks}: the model draws the thing with
 * shapes that fill the box, and gives each part its name and a point [NAME, X, Y]. The part of the step ($/v is its name) has an accent ring
 * at its point and its name in a chip, and each other part has a dot. The drawing of the model stays as it is.
 */
export function freeMarks(points, picture = [300, 300]) {
  // The number of the marked part is the value $/fp once, so each mark compares a short number and the source stays small.
  const lines = [`$/fp := ${points.reduceRight((rest, [name], at) => `($/v == ${JSON.stringify(name)} ? ${at + 1} : ${rest})`, "0")}`], dots = [], chips = [];
  points.forEach(([name, x, y], index) => {
    const on = `$/fp == ${index + 1}`;
    dots.push(...dotOf([x, y], `, visible=!(${on})`));
    // The model can paint a part in the accent color, so a wider ring in the surface color under the accent ring keeps the mark visible.
    chips.push(...ringOf([x, y], FREE.ring, FREE.rim + FREE.halo, on, "surface"), ...ringOf([x, y], FREE.ring, FREE.rim, on),
      ...chipOf([x, y], FREE.ring + FREE.rim / 2, name, picture, `, visible=${on}`));
  });
  return { lines, marks: [...dots, ...chips] };
}
/**
 * The margin of the points of a diagram of a thing with no ready object to the edge of the box, the radius and the width of the accent ring of
 * its marked part, and the added width of the surface ring under it (halo).
 */
export const FREE = { margin: 10, ring: 8, rim: 4, halo: 4 };

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
