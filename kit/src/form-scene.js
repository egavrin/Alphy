// The template scene (docs/superpowers/plans/2026-11-22-one-scene.md, decision 1): a picture of the shapes s1 to s20, one slider or choice,
// and a card with a text. The kit draws each shape as it is, and a library line as the shapes of its element or object (library.js). The
// shape kinds and their properties come from the registry of kinds.js, so a new kind needs no edit here.

import { show } from "./dsl.js";
import { KINDS, PROPERTIES } from "./kinds.js";
import { BUDGET, HEIGHT, INSIDE, WIDTH, isText, labelsFault, numberOf, optionsFault, textCardFault, textFault } from "./form-base.js";
import { entryOf, partName, useFault, useShapes } from "./library.js";

const CONTROL = "slider(LABEL, MIN, MAX, STEP, START) with numbers, choice(LABEL, [OPTION, …], START), steps(LABEL, [STEP, …], ANSWER), or parts(LABEL, [PART, …])";
const PLAY = "play(LABEL, FROM, TO, SECONDS) with numbers: $/t runs from FROM to TO in SECONDS, again and again";
/** The longest round of a play line in seconds. */
export const PLAY_SECONDS = 120;

/** The form of scene (forms.js TemplateForm). */
export const SCENE_FORM = {
  word: "scene",
  part: new RegExp(`^s(?:${Array.from({ length: BUDGET.shapes }, (_, index) => index + 1).join("|")})$`),
  lines: `control, play, s1 to s${BUDGET.shapes} (at most ${BUDGET.shapes} shapes), and card`,
  control: CONTROL,
  /**
   * The fault of the motion line play = play(LABEL, FROM, TO, SECONDS) (block 2 plan, M1): $/t runs from FROM to TO in SECONDS, again and
   * again, beside the control of $/v (M7).
   */
  playFault(tree) {
    const args = tree.args ?? [], [from, to, seconds] = args.slice(1).map(numberOf), form = `the motion line is ${PLAY}`;
    if (tree.k !== "call" || tree.name !== "play" || Object.keys(tree.named).length || args.length !== 4 || [from, to, seconds].includes(undefined)) return form;
    if (from === to) return "FROM and TO of play are the same number, so nothing moves";
    if (!(seconds > 0 && seconds <= PLAY_SECONDS)) return `the SECONDS of play are above 0 and at most ${PLAY_SECONDS}, and this line has ${seconds}`;
    return textFault("the label", args[0], BUDGET.control);
  },
  /** The fault of a control line. The kit checks the bounds and the start of a slider and of a choice when it compiles the expansion. */
  controlFault(tree) {
    const args = tree.args ?? [], form = `the control of scene is ${CONTROL}`;
    if (tree.k !== "call" || Object.keys(tree.named).length) return form;
    if (tree.name === "slider") {
      if (args.length !== 5 || args.slice(1).some((arg) => numberOf(arg) === undefined)) return form;
    } else if (tree.name === "parts") {
      // A diagram (block 2 plan, G1 and C2): a walk over up to BUDGET.parts parts, each named in the picture. The kit takes the parts of the
      // ready object of the scene in their order when the line names none.
      const names = args[1]?.items;
      if (args.length < 1 || args.length > 3 || (args[1] && !names) || (args[2] && !isText(args[2]))) return form;
      if (names && names.length > BUDGET.parts) return `the parts control has ${names.length} parts, and its cap is ${BUDGET.parts}. Keep the ones that matter most`;
      if (names && names.length < 2) return "a parts control needs at least 2 parts";
      // The cap of a name holds only for a thing with no entry (diagramOf): the name of a part of an entry can be longer (C2).
      const fault = names && labelsFault("part", names, Infinity);
      if (fault) return fault;
      if (args[2] && names && !names.some((name) => name.v === args[2].v)) return `the start ${JSON.stringify(args[2].v)} is not one of the parts`;
    } else if (tree.name === "choice" || tree.name === "steps") {
      // A steps control is a choice whose start is its answer step, by default the last step (decision 3).
      const options = args[1]?.items, what = tree.name === "steps" ? "steps" : "options";
      if (args.length < 2 || args.length > 3 || !options || (args[2] && !isText(args[2]))) return form;
      if (options.length > BUDGET.options) return `the ${tree.name} control has ${options.length} ${what}, and its cap is ${BUDGET.options}. Keep the ones that matter most`;
      if (options.length < 2) return `a ${tree.name} control needs at least 2 ${what}`;
      const fault = optionsFault(tree.name === "steps" ? "step" : "option", options);
      if (fault) return fault;
    } else return form;
    return textFault("the label", args[0], BUDGET.control);
  },
  partFault(tree, lang) {
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
  },
  /**
   * The shapes of the drawing: each shape as it is, and each library line as its shapes, with the part names in the language of the first
   * line. A library line can add app lines before the drawing (library.js useShapes).
   */
  expand(parts, control, head) {
    const used = parts.map((part) => (part.tree.k === "call" && part.tree.name === "use" ? useShapes(part.tree, head.named.lang?.v ?? "en", [WIDTH, HEIGHT], part.name) : { shapes: [show(part.tree)], lines: [] }));
    return { shapes: used.flatMap((one) => one.shapes), lines: used.flatMap((one) => one.lines) };
  },
  cardFault: (tree) => textCardFault(tree, "scene"),
  /** A parts control with names of a ready object of the scene names its parts, and one with no names needs such an object (diagramOf). */
  context(lines) {
    const control = lines.find((line) => line.name === "control" && line.tree?.name === "parts")?.tree, lang = lines[0].tree?.named?.lang?.v ?? "en";
    if (!control) return [];
    const diagram = diagramOf(lines.filter((line) => line.tree && SCENE_FORM.part.test(line.name)), control, lang);
    return diagram.fault ? [["control", diagram.fault]] : [];
  },
  /** A scene whose first line and control alone have faults gives them to control (template.js ownersOf). */
  owners(owners, alone, parts, add) {
    if (!owners.has("control")) alone(parts).forEach((fault) => add("control", fault));
  },
};

/**
 * The diagram of a parts control (block 2 plan, G1, G2, and C2), as {object, steps, fault}. object is the first part line that places a ready
 * object with parts, and steps are the names of the walk in the language of the scene: the names of the control, else each part of the object
 * in its order, at most BUDGET.parts. A name of the control that is a part of the object (by its name or its id) takes the name of the entry.
 * With no object, the names of the control are free labels (a thing with no entry, G2).
 */
export function diagramOf(parts, control, lang) {
  const object = parts.find((part) => part.tree.k === "call" && part.tree.name === "use" && isText(part.tree.args[0]) && entryOf(part.tree.args[0].v)?.entry.parts?.length);
  const list = object && entryOf(object.tree.args[0].v).entry.parts, names = control.args[1]?.items;
  if (!list && names) return { object: null, steps: names.map((name) => name.v), fault: labelsFault("part", names, BUDGET.part) };
  if (!list) return { fault: "a parts control with no names needs a ready object with parts in the picture, such as use(\"heart\", 150, 150, 220)" };
  if (!names) return { object, steps: list.slice(0, BUDGET.parts).map((part) => partName(part, lang)) };
  const found = names.map((name) => list.find((part) => part.id === name.v || partName(part, lang).toLowerCase() === name.v.toLowerCase() || Object.values(part.names).includes(name.v)));
  const missing = names.find((_, index) => !found[index]);
  if (missing) return { fault: `${object.tree.args[0].v} has no part ${JSON.stringify(missing.v)}. Its parts are ${list.map((part) => partName(part, lang)).join(", ")}` };
  return { object, steps: found.map((part) => partName(part, lang)) };
}
