// The template scene (docs/superpowers/plans/2026-11-22-one-scene.md, decision 1): a picture of the shapes s1 to s20, one slider or choice,
// and a card with a text. The kit draws each shape as it is, and a library line as the shapes of its element or object (library.js). The
// shape kinds and their properties come from the registry of kinds.js, so a new kind needs no edit here.

import { show } from "./dsl.js";
import { KINDS, PROPERTIES } from "./kinds.js";
import { BUDGET, HEIGHT, INSIDE, WIDTH, isText, numberOf, optionsFault, textCardFault, textFault } from "./form-base.js";
import { useFault, useShapes } from "./library.js";

const CONTROL = "slider(LABEL, MIN, MAX, STEP, START) with numbers, choice(LABEL, [OPTION, …], START), or steps(LABEL, [STEP, …], ANSWER)";

/** The form of scene (forms.js TemplateForm). */
export const SCENE_FORM = {
  word: "scene",
  part: new RegExp(`^s(?:${Array.from({ length: BUDGET.shapes }, (_, index) => index + 1).join("|")})$`),
  lines: `control, s1 to s${BUDGET.shapes} (at most ${BUDGET.shapes} shapes), and card`,
  control: CONTROL,
  /** The fault of a control line. The kit checks the bounds and the start of a slider and of a choice when it compiles the expansion. */
  controlFault(tree) {
    const args = tree.args ?? [], form = `the control of scene is ${CONTROL}`;
    if (tree.k !== "call" || Object.keys(tree.named).length) return form;
    if (tree.name === "slider") {
      if (args.length !== 5 || args.slice(1).some((arg) => numberOf(arg) === undefined)) return form;
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
  /** A scene whose first line and control alone have faults gives them to control (template.js ownersOf). */
  owners(owners, alone, parts, add) {
    if (!owners.has("control")) alone(parts).forEach((fault) => add("control", fault));
  },
};
