// The line table of the task screen predict of the tests (ready plans design, section 5.4): a picture of shapes, a slider, a question with
// options, and a button that runs the experiment. It is only data: the engine needs no line of code for it. No prompt names it, and the app
// does not ship it.

import { BUDGET, CONTROL_LINE, CONTROL_VALUE, CONTROL_LABEL, NARRATION } from "../../../src/form-base.js";
import { SHAPES } from "../../../templates/scene.js";

const number = (slot) => ({ slot, type: "number" });
const text = (slot, what, cap = 140) => ({ slot, type: "text", what, cap });

export const PREDICT = {
  word: "predict",
  text: "control, ask, run, s1 to s20, and say",
  values: [CONTROL_VALUE, { name: "t", what: "the value of run", line: "run" }],
  whole: { line: "s", count: 1, fault: "predict: the picture needs a shape with no fault" },
  lines: [
    { ...CONTROL_LINE, form: "the control of predict is slider(LABEL, MIN, MAX, STEP, START) with numbers",
      forms: { slider: [CONTROL_LABEL, number("min"), number("max"), number("step"), number("start")] } },
    { name: "ask", owner: "guess", form: "the question is ask(TEXT, [OPTION, …], RIGHT, RIGHT_TEXT, WRONG_TEXT)",
      forms: { ask: [text("question", "the question"), { slot: "options", type: "list", what: "question", word: "option", least: 2, most: 4 }, { slot: "right", type: "word" },
        text("rightText", "the right text"), text("wrongText", "the wrong text")] } },
    { name: "run", sets: "t", merge: 2, owner: "go", form: "the run line is run(LABEL, FROM, TO, SECONDS) with numbers",
      forms: { run: [text("button", "the label", BUDGET.control), number("from"), number("to"), { slot: "seconds", type: "whole", what: "SECONDS", least: 1, most: 120 }] } },
    SHAPES,
    NARRATION,
  ],
};
