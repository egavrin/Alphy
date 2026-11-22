// The template scene (docs/superpowers/plans/2026-11-22-one-scene.md, decision 1): a picture of the shapes s1 to s12, one slider or choice,
// and a card with a text. The kit draws the shapes as they are (template.js expandTemplate).

import { BUDGET, INSIDE, SHAPES, isText, numberOf, textCardFault, textFault } from "./form-base.js";

const CONTROL = "slider(LABEL, MIN, MAX, STEP, START) with numbers, or choice(LABEL, [OPTION, …], START)";

/** The form of scene (forms.js TemplateForm). */
export const SCENE_FORM = {
  word: "scene",
  part: /^s(?:[1-9]|1[0-2])$/,
  lines: "control, s1 to s12 (at most 12 shapes), and card",
  control: CONTROL,
  /** The fault of a control line. The kit checks the bounds and the start of a slider and of a choice when it compiles the expansion. */
  controlFault(tree) {
    const args = tree.args ?? [], form = `the control of scene is ${CONTROL}`;
    if (tree.k !== "call" || Object.keys(tree.named).length) return form;
    if (tree.name === "slider") {
      if (args.length !== 5 || args.slice(1).some((arg) => numberOf(arg) === undefined)) return form;
    } else if (tree.name === "choice") {
      const options = args[1]?.items;
      if (args.length < 2 || args.length > 3 || !options || (args[2] && !isText(args[2]))) return form;
      if (options.length > BUDGET.options) return `the choice has ${options.length} options, and its cap is ${BUDGET.options}. Keep the ones that matter most`;
      if (options.length < 2) return "a choice needs at least 2 options";
      const fault = options.map((option, index) => textFault(`option ${index + 1}`, option, BUDGET.option)).find(Boolean);
      if (fault) return fault;
    } else return form;
    return textFault("the label", args[0], BUDGET.control);
  },
  partFault(tree) {
    if (tree.k !== "call" || !Object.hasOwn(SHAPES, tree.name)) return "a shape is circle, rect, line, text, or polygon";
    const other = Object.keys(tree.named).find((key) => key !== "visible" && key !== "stroke");
    if (other) return `a shape takes only visible= and stroke= by name, and ${other}= is not one`;
    if (tree.name === "text") return textFault("the text", tree.args[2], BUDGET.text, true, INSIDE);
    // The kit compiles a shorter or an odd list clean: it draws nothing for two points and drops the last number of an odd list.
    const points = tree.name === "polygon" && tree.args[0]?.k === "list" ? tree.args[0].items.length : 6;
    if (points < 6 || points % 2) return `the points of a polygon are a list of x and y numbers for at least 3 points (6 numbers), and this list has ${points} numbers`;
    return null;
  },
  cardFault: (tree) => textCardFault(tree, "scene"),
  /** A scene whose first line and control alone have faults gives them to control (template.js ownersOf). */
  owners(owners, alone, parts, add) {
    if (!owners.has("control")) alone(parts).forEach((fault) => add("control", fault));
  },
};
