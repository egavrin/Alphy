// The line table of the ready plan cells.plan (kit/README.md, section "Ready plans"): a slider of a count of cells and a card with a text.
// The control draws the cells, so a cells file has no part line.

import { BUDGET, CONTROL_LABEL, CONTROL_LINE, CONTROL_VALUE, NARRATION, WIDTH, cardLine, numberOf } from "../src/form-base.js";
import { controlStart } from "./scene.js";

export const CELLS = {
  word: "cells",
  text: "control and card",
  values: [CONTROL_VALUE],
  whole: { line: "control", count: 1, fault: "cells: the cells need a line control = slider(LABEL, 0, COUNT, 1, START) with no fault" },
  lines: [
    { ...CONTROL_LINE, holds: "parts", form: "the control of cells is slider(LABEL, 0, COUNT, 1, START)",
      forms: { slider: [CONTROL_LABEL, { slot: "min", type: "number", is: 0 }, { slot: "max", type: "whole", what: "COUNT", least: 2, most: BUDGET.cells },
        { slot: "step", type: "number", is: 1 }, { slot: "start", type: "number" }] } },
    cardLine("cells"),
    NARRATION,
  ],
  start: controlStart,
  /** The drawing of the cells of a control line that the kit can read, else no shape. */
  fill({ call }) {
    if (!call) return { shapes: "" };
    // Rows of at most ten cells, filled evenly, in the top 176 px, and the caption "K / N = P%" under them. One row of few cells gets taller cells.
    const count = numberOf(call.args[2]), rows = Math.ceil(count / 10), cols = Math.ceil(count / rows);
    const pitch = rows > 1 ? Math.min(Math.floor(280 / cols), Math.floor(176 / rows)) : Math.min(56, Math.floor(280 / cols)), side = pitch - 4, tall = rows > 1 ? side : Math.min(60, Math.round(side * 1.4));
    const x0 = Math.round((WIDTH - (cols * pitch - 4)) / 2), y0 = 8 + Math.round((176 - ((rows - 1) * pitch + tall)) / 2);
    return { shapes: `each(${count}, rect(${x0} + ($index % ${cols}) * ${pitch}, ${y0} + floor($index / ${cols}) * ${pitch}, ${side}, ${tall}, $index < $/v ? "accent" : "muted", 3)), `
      + `text(150, 202, text($/v) + " / ${count} = " + text(round($/v / ${count} * 100)) + "%", "ink", 18)` };
  },
};
