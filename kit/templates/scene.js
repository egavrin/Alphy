// The line table of the ready plan scene.plan (kit/README.md, section "Ready plans"): a picture of the shapes s1 to s20, one slider, choice,
// steps, or parts control, a play line, and a card with a text. The kit draws each shape as it is, and a library line as the shapes of its
// element or object (library.js). The shape kinds and their properties come from the registry of kinds.js, so a new kind needs no edit here.

import { parseExpression, show } from "../src/dsl.js";
import { KINDS, PROPERTIES } from "../src/kinds.js";
import { BUDGET, CONTROL_LINE, CONTROL_VALUE, CONTROL_LABEL, HEIGHT, INSIDE, NARRATION, WIDTH, cardLine, colorFault, colored, isText, labelsFault, numberOf, textFault } from "../src/form-base.js";
import { FREE, UNITS, entryOf, freeLayout, freeShapes, partName, roomFault, useFault, useShapes } from "../src/library.js";

const CONTROL = "slider(LABEL, MIN, MAX, STEP, START) with numbers, choice(LABEL, [OPTION, …], START), steps(LABEL, [STEP, …], ANSWER), or parts(LABEL, [PART, …])";
const PLAY = "play(LABEL, FROM, TO, SECONDS) with numbers: $/t runs from FROM to TO in SECONDS, again and again";
/** The longest round of a play line in seconds. */
const PLAY_SECONDS = 120;
const SHAPE = new RegExp(`^s(?:${Array.from({ length: BUDGET.shapes }, (_, index) => index + 1).join("|")})$`);
const number = (slot) => ({ slot, type: "number" });
/** The options of a choice or the steps of a steps control: 2 to 6 labels, each within the cap of their number (optionsFault). */
const labels = (what, word) => ({ slot: "options", type: "list", what, word, least: 2, most: BUDGET.options });
const START = { slot: "start", type: "word", optional: true };

/**
 * The fault of the motion line play = play(LABEL, FROM, TO, SECONDS) (block 2 plan, M1): $/t runs from FROM to TO in SECONDS, again and
 * again, beside the control of $/v (M7).
 */
function playFault(tree) {
  const args = tree.args ?? [], [from, to, seconds] = args.slice(1).map(numberOf), form = `the motion line is ${PLAY}`;
  if (tree.k !== "call" || tree.name !== "play" || Object.keys(tree.named).length || args.length !== 4 || [from, to, seconds].includes(undefined)) return form;
  if (from === to) return "FROM and TO of play are the same number, so nothing moves";
  if (!(seconds > 0 && seconds <= PLAY_SECONDS)) return `the SECONDS of play are above 0 and at most ${PLAY_SECONDS}, and this line has ${seconds}`;
  return textFault("the label", args[0], BUDGET.control);
}

/**
 * The fault of a parts control (block 2 plan, G1 and C2): a walk over up to BUDGET.parts parts, each named in the picture. The kit takes the
 * parts of the ready object of the scene in their order when the line names none. A part is a text, or part(...) with the name that the model
 * gives (diagramOf).
 */
function partsFault(tree) {
  const args = tree.args, names = args[1]?.items, form = `the control of scene is ${CONTROL}`;
  if (Object.keys(tree.named).length || args.length < 1 || args.length > 3 || (args[1] && !names) || (args[2] && !isText(args[2]))) return form;
  if (names && names.length > BUDGET.parts) return `the parts control has ${names.length} parts, and its cap is ${BUDGET.parts}. Keep the ones that matter most`;
  if (names && names.length < 2) return "a parts control needs at least 2 parts";
  const given = names?.map(nameOfPart);
  if (given?.some((name) => name === undefined) || new Set(names?.map(partKind)).size > 1) return `each part of a parts control has the same form: ${PART}`;
  // The cap of a name holds only for a name that the model gives (diagramOf): the name of a part of an entry can be longer (C2).
  const fault = names && labelsFault("part", given, names.every(isText) ? Infinity : BUDGET.part);
  if (fault) return fault;
  if (args[2] && names && !given.some((name) => name.v === args[2].v)) return `the start ${JSON.stringify(args[2].v)} is not one of the parts`;
  return textFault("the label", args[0], BUDGET.control);
}

/** The fault of a shape line: a shape of the kind registry, or a library line use(NAME, X, Y, SIZE, …) in the language lang. */
export function partFault(tree, lang) {
  if (tree.k === "call" && tree.name === "use") return useFault(tree, [WIDTH, HEIGHT], lang ?? "en");
  const kinds = Object.keys(KINDS).filter((name) => KINDS[name].prompt !== null), kind = tree.k === "call" && kinds.includes(tree.name) ? KINDS[tree.name] : null;
  if (!kind) return `a shape is ${kinds.join(", ")}, or use(NAME, X, Y, SIZE) of the library`;
  const named = [...Object.keys(PROPERTIES).filter((key) => PROPERTIES[key].prompt !== null), ...(kind.named ?? [])], other = Object.keys(tree.named).find((key) => !named.includes(key));
  if (other) return `a shape takes only ${named.map((key) => `${key}=`).join(", ")} by name, and ${other}= is not one`;
  const text = kind.args.indexOf("text");
  if (text >= 0) return textFault("the text", tree.args[text], BUDGET.text, true, INSIDE);
  // The kit compiles a shorter or an odd list clean: it draws nothing for too few points and drops the last number of an odd list.
  const points = kind.points && tree.args[0]?.k === "list" ? tree.args[0].items.length : 2 * (kind.points ?? 0);
  if (kind.points && (points < 2 * kind.points || points % 2)) return `the points of a ${tree.name} are a list of x and y numbers for at least ${kind.points} points (${2 * kind.points} numbers), and this list has ${points} numbers`;
  return null;
}

/**
 * The shapes of the drawing and the app lines before it: each shape as it is, and each library line as its shapes, with the part names in the
 * language of the first line (library.js useShapes).
 */
function shapesOf(parts, lang) {
  const used = parts.map((part) => (part.tree.k === "call" && part.tree.name === "use" ? useShapes(part.tree, lang, [WIDTH, HEIGHT], part.name) : { shapes: [show(part.tree)], lines: [] }));
  return { shapes: used.flatMap((one) => one.shapes), lines: used.flatMap((one) => one.lines) };
}

/** The shape lines s1 to s20: each one a shape with the kit's color for each color name, or a library line. predict of the tests uses them too. */
export const SHAPES = { name: "s", count: BUDGET.shapes, part: true, fault: (tree, { lang }) => partFault(tree, lang), draw: colored, check: colorFault,
  fill(parts, { lang }) {
    const { shapes, lines } = shapesOf(parts, lang);
    return { shapes: shapes.join(", "), lines: lines.join("\n") };
  } };

/**
 * The value that $/v keeps when the control is left out: the start of a slider or a choice, the answer of a steps control (by default its last
 * step, decision 3), the first part of a diagram (C2), or 0. tree is the control line, also one with a fault. cells takes it too.
 */
export function controlStart({ tree, parts, lang }) {
  const start = tree?.k !== "call" ? tree : tree.name === "slider" ? tree.args[4] : tree.name === "choice" ? tree.args[2] ?? tree.args[1]?.items?.[0]
    : tree.name === "steps" ? tree.args[2] ?? tree.args[1]?.items?.at(-1)
      : tree.name === "parts" ? tree.args[2] ?? { k: "lit", v: diagramOf(parts, tree, lang).steps?.[0] ?? "" } : null;
  return isText(start) || numberOf(start) !== undefined ? start : { k: "lit", v: 0 };
}

/** The card of a diagram (C2): the name of the part of the step with a capital first letter, and the sentence of the step, else the card text of the file. */
function diagramCard(read, steps, card) {
  const said = (index) => { const raw = read.lines.find((line) => line.name === `say${index + 1}` && line.tree)?.raw; try { return raw && JSON.parse(raw.slice(raw.indexOf("=") + 1)); } catch { return null; } };
  const pick = (values) => parseExpression(steps.slice(0, -1).reduceRight((rest, step, index) => `($/v == ${JSON.stringify(step)} ? ${JSON.stringify(values[index])} : ${rest})`, JSON.stringify(values.at(-1))));
  const fallback = card?.args?.[1] && isText(card.args[1]) ? card.args[1].v : null;
  return { k: "call", name: "card", named: {}, args: [pick(steps.map((step) => step.charAt(0).toUpperCase() + step.slice(1))), pick(steps.map((step, index) => said(index) ?? fallback ?? step))] };
}

export const SCENE = {
  word: "scene",
  text: `control, play, s1 to s${BUDGET.shapes} (at most ${BUDGET.shapes} shapes), and card`,
  values: [CONTROL_VALUE, { name: "t", what: "the value of play", line: "play" }],
  whole: { line: "s", count: 1, fault: "scene: the picture needs a shape with no fault" },
  // A scene whose first line and control alone have faults gives them to control (template.js ownersOf).
  owner: "control",
  lines: [
    { ...CONTROL_LINE, form: `the control of scene is ${CONTROL}`,
      // A steps control is a choice whose start is its answer step, by default the last step (decision 3).
      forms: { slider: [CONTROL_LABEL, number("min"), number("max"), number("step"), number("start")], choice: [CONTROL_LABEL, labels("choice control", "option"), START],
        steps: [CONTROL_LABEL, labels("steps control", "step"), START], parts: partsFault },
      // The kit adds mark=$/v to the object of a diagram, so only the other forms need a line that reads $/v.
      unread: { forms: ["slider", "choice", "steps"], fault: "the control changes nothing on the screen, because no line reads $/v. Make a part read $/v, such as visible=$/v > 2" },
      walk: (tree, { parts, lang }) => (tree.name === "steps" ? tree.args[1].items.length : tree.name === "parts" ? diagramOf(parts, tree, lang).steps?.length ?? 0 : null) },
    { name: "play", sets: "t", merge: 2, owner: "t", slot: "play", fault: playFault },
    SHAPES,
    cardLine("scene"),
    NARRATION,
  ],
  start: controlStart,
  /** A parts control with names of a ready object of the scene names its parts, and one with no names needs such an object (diagramOf). */
  context(lines) {
    const control = lines.find((line) => line.name === "control" && line.tree?.name === "parts")?.tree, lang = lines[0].tree?.named?.lang?.v ?? "en";
    if (!control) return [];
    const diagram = diagramOf(lines.filter((line) => line.tree && SHAPE.test(line.name)), control, lang);
    return diagram.fault ? [["control", diagram.fault]] : [];
  },
  /**
   * A diagram (G1, C2): the kit names the parts of its object, or the parts of a thing with no entry at their points (stage 7b, R3), and marks
   * the part of the step. The card shows the name of the part and its sentence. The model writes no label and no condition for it. In a stream,
   * a thing with no entry names its parts only after a line that comes after the shapes, so that its drawing only grows.
   */
  fill({ read, call, line, parts, lang, preview }) {
    const diagram = call?.name === "parts" ? diagramOf(parts, call, lang) : null;
    if (!diagram?.steps) return call?.name === "parts" ? { label: show(call.args[0]), options: show(call.args[1]) } : {};
    const names = diagram.names ? { names: { k: "lit", v: diagram.names } } : {};
    const marked = parts.map((part) => (part === diagram.object ? { ...part, tree: { ...part.tree, named: { ...part.tree.named, parts: { k: "lit", v: true }, mark: parseExpression("$/v"), ...names } } } : part));
    const card = diagramCard(read, diagram.steps, line("card")?.tree), own = shapesOf(marked, lang);
    const after = !preview || read.lines.some((one) => one.name === "card" || NARRATION_NAME.test(one.name));
    const { shapes, lines } = diagram.points ? freeShapes(own.shapes, diagram.points, [WIDTH, HEIGHT], after) : { shapes: own.shapes, lines: [] };
    return { lines: [...own.lines, ...lines].join("\n"), shapes: shapes.join(", "), label: show(call.args[0]), steps: JSON.stringify(diagram.steps), cardTitle: show(card.args[0]), cardText: show(card.args[1]) };
  },
};
const NARRATION_NAME = /^say(?:[1-9]\d*)?$/;

/** The forms of a part of a parts control (stage 7b, R3). */
const PART = "the name or the id of a part of the ready object in quotes, part(ID, NAME) with the name in the language of the request, or part(NAME, X, Y) with a point of a thing that you draw";
/** The kind of a part of a parts control: "id" (a text), "named" (part(ID, NAME)), "point" (part(NAME, X, Y)), or undefined. */
function partKind(item) {
  if (isText(item)) return "id";
  const args = item?.k === "call" && item.name === "part" && !Object.keys(item.named).length ? item.args : [];
  if (args.length === 2 && args.every(isText)) return "named";
  if (args.length === 3 && isText(args[0]) && numberOf(args[1]) !== undefined && numberOf(args[2]) !== undefined) return "point";
  return undefined;
}
/** The name of a part of a parts control as a text node: the text, NAME of part(ID, NAME), or NAME of part(NAME, X, Y). */
const nameOfPart = (item) => ({ id: item, named: item.args?.[1], point: item.args?.[0] })[partKind(item)];

/**
 * The diagram of a parts control (block 2 plan, G1, G2, and C2), as {object, steps, names, points, fault}. object is the first part line that
 * places a ready object with parts, and steps are the names of the walk in the language of the scene: the names of the control, else each
 * part of the object in its order, at most BUDGET.parts. A name of the control that is a part of the object (by its name or its id) takes the
 * name of the entry. The entries have Russian and English names only, so in another language the model gives each name with part(ID, NAME),
 * and names maps each id to its name (stage 7b, R3).
 * With no object, each part is part(NAME, X, Y) of a thing that the model draws (G2, stage 7b), and points are [NAME, X, Y] (library.js freeShapes).
 */
export function diagramOf(parts, control, lang) {
  const object = parts.find((part) => part.tree.k === "call" && part.tree.name === "use" && isText(part.tree.args[0]) && entryOf(part.tree.args[0].v)?.entry.parts?.length);
  const list = object && entryOf(object.tree.args[0].v).entry.parts, items = control.args[1]?.items, kinds = new Set(items?.map(partKind)), [kind] = kinds;
  if (kinds.size > 1 || (items && !kind)) return { fault: `each part of a parts control has the same form: ${PART}` };
  if (!list) return freeDiagram(items, kind);
  if (kind === "point") return { fault: `with the ready object ${object.tree.args[0].v}, a part is not part(NAME, X, Y): the kit knows where its parts are. Write the id of each part, or part(ID, NAME)` };
  const known = Object.hasOwn(list[0].names, lang);
  if (!known && kind !== "named") {
    return { fault: `the kit has no names in the language "${lang}" for the parts of ${object.tree.args[0].v}: write each part as part(ID, NAME) with the name in "${lang}", such as part(${JSON.stringify(list[0].id)}, NAME)` };
  }
  if (!items) return { object, steps: list.slice(0, BUDGET.parts).map((part) => partName(part, lang)) };
  const keys = items.map((item) => (kind === "named" ? item.args[0] : item));
  const found = keys.map((key) => list.find((part) => part.id === key.v || partName(part, lang).toLowerCase() === key.v.toLowerCase() || Object.values(part.names).includes(key.v)));
  const missing = keys.find((_, index) => !found[index]);
  if (missing) return { fault: `${object.tree.args[0].v} has no part ${JSON.stringify(missing.v)}. Its parts are ${list.map((part) => (known ? partName(part, lang) : part.id)).join(", ")}` };
  if (kind !== "named") return { object, steps: found.map((part) => partName(part, lang)) };
  const names = Object.fromEntries(found.map((part, index) => [part.id, items[index].args[1].v]));
  const tree = object.tree, room = roomFault(tree, { ...tree.named, parts: { k: "lit", v: true }, names: { k: "lit", v: names } }, [WIDTH, HEIGHT], lang);
  return { object, steps: Object.values(names), names, fault: room };
}

/** The diagram of a parts control with no ready object in the picture (G2, stage 7b): each part is part(NAME, X, Y) at a point of the drawing zone. */
function freeDiagram(items, kind) {
  if (!items) return { fault: "a parts control with no names needs a ready object with parts in the picture, such as use(\"heart\", 150, 150, 220), or part(NAME, X, Y) for each part of a thing that you draw" };
  if (kind !== "point") return { fault: "with no ready object in the picture, write each part as part(NAME, X, Y): its name and a point of the part in your drawing, and the kit writes the name beside the drawing" };
  const points = items.map((item) => [item.args[0].v, numberOf(item.args[1]), numberOf(item.args[2])]), [left, right] = FREE.zone;
  const out = points.findIndex(([, x, y]) => x < left || x > right || y < 0 || y > HEIGHT);
  if (out >= 0) return { fault: `the point of part ${out + 1} is at ${points[out][1]}, ${points[out][2]}: the drawing and each point stay from x = ${left} to x = ${right} and from y = 0 to y = ${HEIGHT}, so the names have room at the sides` };
  const placed = freeLayout(points, [WIDTH, HEIGHT]);
  if (placed.size / UNITS < FREE.fewest || placed.labels.some((one) => one.clipped)) {
    return { fault: "the names of the parts leave the drawing too little room: write shorter names, or names of two words, or name fewer parts" };
  }
  return { object: null, steps: points.map(([name]) => name), points };
}
