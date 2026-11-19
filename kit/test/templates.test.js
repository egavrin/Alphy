// The look of the part templates (Task 11b-2b, A): each part in the look vocabulary of the baseline apps.

import { expect, test } from "bun:test";
import { built, every, press } from "./helpers.js";

/** The node of a type whose text or name is the given one. */
const named = (definition, type, text) => every(definition.ui).find((node) => node.type === type && (node.text === text || node.name === text));
/** A button as its text, variant, and icon. */
const look = (node) => [node.text, node.variant, node.icon];

test("template: a timer with rounds is a hero card, Start is filled with Play, Pause is outlined, Reset is a text button, and the phase text is muted", async () => {
  const { definition } = await built('app("T", lang="en")', 'need t = timer("Workout", 40, rounds=2, rest=20)');
  const card = every(definition.ui).find((node) => node.type === "card");
  expect(card.variant).toBe("hero");
  expect(["Start", "Pause", "Reset"].map((text) => look(named(definition, "button", text)))).toEqual([["Start", "filled", "Play"], ["Pause", "outlined", "Pause"], ["Reset", "text", "Refresh"]]);
  const phase = every(card).find((node) => node.type === "text" && String(node.bind).includes("phaseText"));
  expect([phase.style, definition.initialState.d[phase.bind.slice(2)]]).toEqual([{ tone: "muted" }, "Work 1 / 2"]);
  // The host's own countdown is a hero card too.
  const own = await built('app("T", lang="en")', 'need t = timer("Tea", 180, alert=true)');
  expect(every(own.definition.ui).find((node) => node.type === "card").variant).toBe("hero");
});

const WORDS = '$/words = [{word: "Haus", meaning: "house"}, {word: "Baum", meaning: "tree"}]';

test("template: the front of a card deck is a centered display text, the back a headline, Yes is filled with Check, No is outlined, and the counts are a grid of two", async () => {
  const { definition } = await built('app("T", lang="en")', WORDS, 'need cards = deck("Words", $/words, front=$word, back=$meaning)');
  const all = every(definition.ui), texts = all.filter((node) => node.type === "text" && String(node.bind).includes("cards_"));
  expect(texts.map((node) => [definition.initialState.d[node.bind.slice(2)], node.style])).toEqual([["Haus", { emphasis: "display", align: "center" }], ["house", { emphasis: "headline" }]]);
  expect(["Yes", "No"].map((text) => look(named(definition, "button", text)))).toEqual([["Yes", "filled", "Check"], ["No", "outlined", undefined]]);
  const grid = all.find((node) => node.type === "grid");
  expect([grid.columns, grid.children.map((node) => [node.type, node.text])]).toEqual([2, [["metric", "Yes"], ["metric", "No"]]]);
  // A deck with answer= shows its front in the same way. A deck of pages (no back=) keeps a heading, because a page can be a long text.
  const typed = await built('app("T", lang="en")', WORDS, 'need cards = deck("Words", $/words, front=$word, answer=$meaning)');
  expect(every(typed.definition.ui).find((node) => node.type === "text" && String(node.bind).includes("cards_front")).style).toEqual({ emphasis: "display", align: "center" });
});

test("template: the note at the end of steps is a success notice, and the notice of a question is success for a right answer and warning for a wrong one", async () => {
  const { definition } = await built('app("T", lang="en")', 'need n = input("number", "N", 1)', 'need m = input("number", "M", 2)', 'need p = steps(["One", "Two"], [[n], [m]])');
  expect(every(definition.ui).find((node) => node.type === "banner").style).toEqual({ tone: "success" });
  const asked = await built('app("T", lang="en")', 'need ask = question("Is the Moon a star?", options=["Yes", "No"], right="No")');
  const banner = (ui) => every(ui).find((node) => node.type === "banner").style;
  // The notice shows after a choice: before it, its tone is the one of a wrong answer.
  expect(banner(asked.definition.ui)).toEqual({ tone: "warning" });
  expect(banner(press(asked.definition.initialState, asked.app, "ask_opt1").ui)).toEqual({ tone: "success" });
});
