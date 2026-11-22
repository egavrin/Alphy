// The template cells (docs/superpowers/plans/2026-11-22-one-scene.md, decision 1): a slider of a count of cells and a card with a text.
// The control draws the cells, so a cells file has no part line.

import { show } from "./dsl.js";
import { BUDGET, WIDTH, numberOf, textCardFault, textFault } from "./form-base.js";

const CONTROL = "slider(LABEL, 0, COUNT, 1, START)";

/** The form of cells (forms.js TemplateForm). It has no partFault, because its part names match no line. */
export const CELLS_FORM = {
  word: "cells",
  part: /(?!)/,
  lines: "control and card",
  control: CONTROL,
  controlFault(tree) {
    const args = tree.args ?? [], form = `the control of cells is ${CONTROL}`;
    if (tree.k !== "call" || Object.keys(tree.named).length) return form;
    if (tree.name !== "slider") return form;
    if (args.length !== 5 || args.slice(1).some((arg) => numberOf(arg) === undefined)) return form;
    const count = numberOf(args[2]);
    if (numberOf(args[1]) !== 0 || numberOf(args[3]) !== 1) return form;
    if (!(Number.isInteger(count) && count >= 2 && count <= BUDGET.cells)) return `COUNT is a whole number from 2 to ${BUDGET.cells}, and ${show(args[2])} is not one`;
    return textFault("the label", args[0], BUDGET.control);
  },
  cardFault: (tree) => textCardFault(tree, "cells"),
  /** The drawing of the cells of a control line that the kit can read, else the parts as they are. */
  expand(parts, control) {
    if (control?.k !== "call") return {};
    // Rows of at most ten cells, filled evenly, in the top 176 px, and the caption "K / N = P%" under them. One row of few cells gets taller cells.
    const count = numberOf(control.args[2]), rows = Math.ceil(count / 10), cols = Math.ceil(count / rows);
    const pitch = rows > 1 ? Math.min(Math.floor(280 / cols), Math.floor(176 / rows)) : Math.min(56, Math.floor(280 / cols)), side = pitch - 4, tall = rows > 1 ? side : Math.min(60, Math.round(side * 1.4));
    const x0 = Math.round((WIDTH - (cols * pitch - 4)) / 2), y0 = 8 + Math.round((176 - ((rows - 1) * pitch + tall)) / 2);
    return { shapes: [`each(${count}, rect(${x0} + ($index % ${cols}) * ${pitch}, ${y0} + floor($index / ${cols}) * ${pitch}, ${side}, ${tall}, $index < $/v ? "accent" : "muted", 3))`,
      `text(150, 202, text($/v) + " / ${count} = " + text(round($/v / ${count} * 100)) + "%", "ink", 18)`] };
  },
};
