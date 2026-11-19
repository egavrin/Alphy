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

test("template: a lesson of at most 12 steps shows its count once, in the dots, and a longer lesson keeps the count line over its bar", async () => {
  // lesson.png of Task 11b-2a: "Исследуем · 1 / 3" over the dots, and the dots "Исследуем: 1 / 3" again.
  const steps = (count) => `[${Array.from({ length: count }, (_, index) => `{title: "S${index + 1}", text: "T"}`).join(", ")}]`;
  const short = await built('app("T", lang="en")', `need l = lesson(${steps(3)})`);
  expect(every(short.definition.ui).filter((node) => node.type === "progress").map((node) => [node.variant, node.text])).toEqual([["dots", "Step"]]);
  expect(Object.values(short.definition.initialState.d ?? {}).filter((value) => String(value).includes("1 / 3"))).toEqual([]);
  const long = await built('app("T", lang="en")', `need l = lesson(${steps(13)})`);
  const count = every(long.definition.ui).find((node) => node.type === "text" && String(node.bind).includes("l_count"));
  expect([long.definition.initialState.d[count.bind.slice(2)], every(long.definition.ui).find((node) => node.type === "progress").variant]).toEqual(["Step · 1 / 13", "bar"]);
});

test("template: the title of a chart shows once, as the label of the chart, with no section around it", async () => {
  const { definition } = await built('app("T", lang="en")', '$/days = [{day: "Mo", steps: 6}, {day: "Tu", steps: 10}]', 'need line = chart("Steps", $/days, $day, $steps)');
  const all = every(definition.ui);
  expect([all.some((node) => node.type === "section"), all.filter((node) => node.text === "Steps").map((node) => node.type)]).toEqual([false, ["chart"]]);
});
