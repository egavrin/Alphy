// The look of the part templates (Task 11b-2b, A): each part in the look vocabulary of the baseline apps.

import { expect, test } from "bun:test";
import { emit } from "../src/emit.js";
import { expand } from "../src/parts.js";
import { handle } from "../src/run.js";
import { built, every, planned, press } from "./helpers.js";

/** The node of a type whose text or name is the given one. */
const named = (definition, type, text) => every(definition.ui).find((node) => node.type === type && (node.text === text || node.name === text));
/** A button as its text, variant, and icon. */
const look = (node) => [node.text, node.variant, node.icon];

test("template: a timer with rounds is a hero card, Start and Pause are filled with their icons, Reset is a text button, and the phase text is muted", async () => {
  const { definition } = await built('app("T", lang="en")', 'need t = timer("Workout", 40, rounds=2, rest=20)');
  const card = every(definition.ui).find((node) => node.type === "card");
  expect(card.variant).toBe("hero");
  // While the timer runs, Pause is the main action (round before the review): an outlined button on a hero card shows no container in the dark theme.
  expect(["Start", "Pause", "Reset"].map((text) => look(named(definition, "button", text)))).toEqual([["Start", "filled", "Play"], ["Pause", "filled", "Pause"], ["Reset", "text", "Refresh"]]);
  const phase = every(card).find((node) => node.type === "text" && String(node.bind).includes("phaseText"));
  expect([phase.style, definition.initialState.d[phase.bind.slice(2)]]).toEqual([{ tone: "muted" }, "Work 1 / 2"]);
  // The host's own countdown is a hero card too.
  const own = await built('app("T", lang="en")', 'need t = timer("Tea", 180, alert=true)');
  expect(every(own.definition.ui).find((node) => node.type === "card").variant).toBe("hero");
});

const WORDS = '$/words = [{word: "Haus", meaning: "house"}, {word: "Baum", meaning: "tree"}]';

test("template: the front of a card deck is a centered display text, the back a centered headline, Yes is filled with Check, No is outlined, and the counts are a grid of two", async () => {
  const { definition } = await built('app("T", lang="en")', WORDS, 'need cards = deck("Words", $/words, front=$word, back=$meaning)');
  const all = every(definition.ui), texts = all.filter((node) => node.type === "text" && String(node.bind).includes("cards_"));
  expect(texts.map((node) => [definition.initialState.d[node.bind.slice(2)], node.style])).toEqual([["Haus", { emphasis: "display", align: "center" }], ["house", { emphasis: "headline", align: "center" }]]);
  expect(["Yes", "No"].map((text) => look(named(definition, "button", text)))).toEqual([["Yes", "filled", "Check"], ["No", "outlined", undefined]]);
  const grid = all.find((node) => node.type === "grid");
  expect([grid.columns, grid.children.map((node) => [node.type, node.text])]).toEqual([2, [["metric", "Yes"], ["metric", "No"]]]);
  // A deck with answer= shows its front in the same way. A deck of pages (no back=) keeps a heading, because a page can be a long text.
  const typed = await built('app("T", lang="en")', WORDS, 'need cards = deck("Words", $/words, front=$word, answer=$meaning)');
  expect(every(typed.definition.ui).find((node) => node.type === "text" && String(node.bind).includes("cards_front")).style).toEqual({ emphasis: "display", align: "center" });
});

test("template: the hero card of a deck holds the card and at most its filled button, and the other controls stand under it", async () => {
  // Round before the review: an outlined button or a field on a hero card shows no container in the dark theme.
  const kinds = (definition) => {
    const section = every(definition.ui).find((node) => node.type === "section");
    return section.children.map((node) => [node.type, node.variant, every(node).slice(1).map((child) => (child.type === "button" ? child.text : child.type))]);
  };
  const back = await built('app("T", lang="en")', WORDS, 'need cards = deck("Words", $/words, front=$word, back=$meaning)');
  expect(kinds(back.definition).slice(0, 2)).toEqual([["card", "hero", ["text", "text", "Show"]], ["flowRow", undefined, ["Yes", "No"]]]);
  const pages = await built('app("T", lang="en")', WORDS, 'need cards = deck("Words", $/words, front=$word)');
  expect(kinds(pages.definition).slice(0, 2)).toEqual([["card", "hero", ["text"]], ["flowRow", undefined, ["Back", "Next"]]]);
  const typed = await built('app("T", lang="en")', WORDS, 'need cards = deck("Words", $/words, front=$word, answer=$meaning)');
  expect(kinds(typed.definition).slice(0, 2)).toEqual([["card", "hero", ["text"]], ["column", undefined, ["input", "Check", "banner", "text", "Next"]]]);
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

test("template: icon= gives a card its icon, and the body text of a card is muted", async () => {
  const { definition } = await built('app("T", lang="en")', 'need legend = card("Legend", "Teal is the Moon", icon="Layers")');
  const card = every(definition.ui).find((node) => node.type === "card");
  expect([card.icon, card.children.map((node) => [node.text, node.style])]).toEqual(["Layers", [["Legend", { emphasis: "title" }], ["Teal is the Moon", { tone: "muted" }]]]);
});

test("template: a drawing or a 3D model that is the main element is a hero card, and the controls of a model stand under the card", async () => {
  const shape = 'need art = drawing("Art", 320, 200, [circle(160, 100, 40, "accent")])';
  const drawn = await built('app("T", lang="en")', 'need n = input("number", "N", 1)', shape);
  const [first] = drawn.definition.ui.children[0].children;
  expect([first.type, first.variant, first.children.map((node) => node.type)]).toEqual(["card", "hero", ["canvas"]]);
  const { definition, app } = await built('app("T", lang="en")', 'need toy = model3d("Toy", [box("", 0, 0, 0, 2, 1, 1, "sand"), box("top", 0, 1, 0, 1, 1, 1, "accent")], parts={top: "Top"})');
  // Round before the review: the hero card holds the model only. The chips, the turn buttons, and the outlined button that takes the model apart follow it.
  const [card, chips, tools] = definition.ui.children[0].children;
  expect([card.type, card.variant, card.children.map((node) => node.type)]).toEqual(["card", "hero", ["canvas"]]);
  expect([chips.children.map((node) => node.text), tools.children.map((node) => node.text ?? node.name)]).toEqual([["Top"], ["−30°", "+30°", "Take apart"]]);
  expect(look(named(definition, "button", "Take apart"))).toEqual(["Take apart", "outlined", undefined]);
  // The stage under the faces is the muted tone of the theme, not a light hex: Alphy draws it as a mid tint on a light and on a dark card.
  const opened = handle({ artifactId: "kit-test", revision: 1, state: definition.initialState, event: { type: "open" } }, app);
  expect(opened.surfaces.toy_model.commands[0]).toEqual({ type: "rect", x: 0, y: 0, width: 320, height: 240, color: "muted" });
  // A part chip under the card is tonal, and filled while it is the selected part.
  const chosen = press(definition.initialState, app, "toy_part_top");
  expect([named(definition, "button", "Top").variant, named(chosen, "button", "Top").variant]).toEqual(["tonal", "filled"]);
  // A figure is the main element before a drawing: the drawing stays a plain canvas.
  const counted = await built('app("T", lang="en")', 'need f = figure("number", "F", 1)', shape);
  expect(counted.definition.ui.children[0].children.map((node) => [node.type, node.variant])).toEqual([["card", "hero"], ["canvas", undefined]]);
});

test("template: a figure that a later step adds writes the drawing again with no hero card", async () => {
  const file = await planned('app("T", lang="en")\nneed art = drawing("Art", 320, 200, [circle(160, 100, 40, "accent")])');
  expect(file.get("art_card")?.raw).toBe('art_card = Card([art_view], variant="hero")');
  file.feed('need f = figure("number", "F", 1)');
  await expand(file);
  expect([file.get("art_card"), emit(file).definition.ui.children[0].children.map((node) => node.type)]).toEqual([undefined, ["card", "canvas"]]);
});

test("template: the title of a chart shows once, as the label of the chart, with no section around it", async () => {
  const { definition } = await built('app("T", lang="en")', '$/days = [{day: "Mo", steps: 6}, {day: "Tu", steps: 10}]', 'need line = chart("Steps", $/days, $day, $steps)');
  const all = every(definition.ui);
  expect([all.some((node) => node.type === "section"), all.filter((node) => node.text === "Steps").map((node) => node.type)]).toEqual([false, ["chart"]]);
});
