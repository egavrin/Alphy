// The line table of the task screen collect of the tests (ready plans design, section 5.4): a picture of places and pieces, a counter, a check
// button, and a text with the result. The drawing is in the ready plan, so the reply has no shape line. It is only data: the engine needs no
// line of code for it. No prompt names it, and the app does not ship it.

import { BUDGET, CONTROL_LINE, CONTROL_VALUE, CONTROL_LABEL, NARRATION } from "../../../src/form-base.js";

const number = (slot) => ({ slot, type: "number" });
const text = (slot, what, cap = BUDGET.cardText) => ({ slot, type: "text", what, cap });

export const COLLECT = {
  word: "collect",
  text: "control, target, check, and say",
  values: [CONTROL_VALUE],
  whole: { line: "target", count: 1, fault: "collect: the picture needs a line target = target(NUMBER) with no fault" },
  lines: [
    { ...CONTROL_LINE, form: "the control of collect is counter(LABEL, MIN, MAX, START) with numbers",
      forms: { counter: [CONTROL_LABEL, number("min"), number("max"), number("start")] } },
    // With no target line, the picture keeps its place with no places and no pieces, so the first line alone gives the frame.
    { name: "target", holds: "parts", absent: { target: "0" }, form: "the target is target(NUMBER)",
      forms: { target: [{ slot: "target", type: "whole", what: "NUMBER", least: 1, most: 5 }] } },
    { name: "check", owner: ["check", "result"], form: "the check is check(LABEL, RIGHT_TEXT, WRONG_TEXT, HINT)",
      forms: { check: [text("button", "the label", BUDGET.control), text("right", "the right text"), text("wrong", "the wrong text"), text("hint", "the hint")] } },
    NARRATION,
  ],
};
