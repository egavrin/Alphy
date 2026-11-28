// Stage A of the plan "Ready plans" (docs/superpowers/plans/2026-11-28-ready-plans.md, job AK): an explain screen has no card and one caption
// under the controls (K1), a choice of a scene and of items is inline and a scene needs no control (K2), a diagram names only the present part
// with a ring and a name chip and puts a dot at each other part (K3), and the play row shows beside each control (K4).

import { expect, test } from "bun:test";
import { compileAppFile, previewStep } from "../src/compile.js";
import { handle } from "../src/run.js";
import { BUDGET } from "../src/template.js";
import { LABEL, lineWidth } from "../src/library.js";
import { clocked, every, valueAt } from "./helpers.js";

const request = (state, event) => ({ artifactId: "kit-test", revision: 1, state, event });
const file = (...lines) => lines.join("\n") + "\n";
/** The caption node of a ui: the one text with the caption style. */
const captionNode = (ui) => every(ui).filter((node) => node.type === "text" && node.style?.emphasis === "caption");
/** The text of the caption in a state: a formula is a bound value, and a plain text is the text of the node. */
const captionOf = (ui, state) => captionNode(ui).map((node) => (node.bind ? valueAt(state, node.bind) : node.text));
/** Whether each card of a ui holds only the canvas of the picture: an explain screen has no card of text. */
const onlyPictureCards = (ui) => every(ui).filter((node) => node.type === "card").every((node) => node.children.every((child) => child.type === "canvas"));
/** The state after a press of the control of a definition, with its app. */
async function pressed(result, value) {
  const { app } = await clocked(result.expanded), control = every(result.definition.ui).find((node) => node.action === "v_field" || node.action?.startsWith?.("v"));
  return handle(request(result.definition.initialState, { type: control.inputMode === "live" ? "liveInput" : "action", actionId: control.action, value }), app).state;
}

test("K1: a scene with a slider has no card and one muted caption under its controls, and a caption may read $/v", async () => {
  const result = await compileAppFile(file('scene("A lever", lang="en", palette="teal")', 'control = slider("Weight", 1, 10, 1, 4)',
    's1 = rect(-150, 240, 600, 210, "land", 0)', 's2 = circle(150, 150, 20 + 5 * $/v, "accent")', 'caption = $/v > 5 ? "A heavy weight." : "A light weight."', 'say = "Move the slider."'));
  expect(result.faults).toEqual([]);
  const ui = result.definition.ui, nodes = every(ui), caption = captionNode(ui);
  expect([onlyPictureCards(ui), caption.length, caption[0].style]).toEqual([true, 1, { emphasis: "caption", tone: "muted" }]);
  // The caption is the last node of the screen: it stands under the picture and the controls.
  expect(nodes.filter((node) => !node.children).at(-1)).toBe(caption[0]);
  expect(captionOf(ui, result.definition.initialState)).toEqual(["A light weight."]);
  expect(captionOf(ui, await pressed(result, 8))).toEqual(["A heavy weight."]);
});

test("K1: the kit cuts a caption past its cap after the last whole sentence, and a long caption with no sentence end is a fault of its line", async () => {
  expect(BUDGET.caption).toBe(110);
  const first = "The arm of a lever turns around one point, so a small force far away lifts a large weight.";
  const long = file('scene("A lever", lang="en")', 's1 = circle(150, 150, 60, "accent")', `caption = ${JSON.stringify(`${first} Each extra unit of length makes the work easier.`)}`);
  const cut = await compileAppFile(long);
  expect([cut.faults, captionOf(cut.definition.ui, cut.definition.initialState), cut.kept.includes(`caption = ${JSON.stringify(first)}\n`)]).toEqual([[], [first], true]);
  // A formula of texts has each text cut by itself.
  const formula = await compileAppFile(file('scene("A lever", lang="en")', 'control = choice("Arm", ["Short", "Long"])', 's1 = rect(150 - ($/v == "Long" ? 120 : 60), 150, $/v == "Long" ? 240 : 120, 10, "wood", 2)',
    `caption = $/v == "Long" ? ${JSON.stringify(`${first} Each extra unit of length makes the work easier.`)} : "A short arm needs force."`));
  expect([formula.faults, captionOf(formula.definition.ui, formula.definition.initialState)]).toEqual([[], ["A short arm needs force."]]);
  expect([formula.kept.includes(JSON.stringify(first)), formula.kept.includes("Each extra unit")]).toEqual([true, false]);
  const plain = await compileAppFile(file('scene("A lever", lang="en")', 's1 = circle(150, 150, 60, "accent")', `caption = "${"a".repeat(120)}"`));
  expect([plain.faults, plain.leftOut, captionNode(plain.definition.ui).length]).toEqual([["caption: the caption has 120 characters, and its cap is 110. Write it shorter"], ["caption"], 0]);
});

const STEPS = ['scene("Day and night", lang="en", palette="blue")', 'control = steps("Time", ["Morning", "Noon", "Evening"], "Noon")',
  's1 = rect(-150, -150, 600, 600, $/v == "Noon" ? "water+2" : "water-1", 0)', 's2 = circle($/v == "Morning" ? 40 : $/v == "Noon" ? 150 : 260, $/v == "Noon" ? 70 : 200, 34, "sun", glow=true)',
  's3 = rect(-150, 230, 600, 220, "land", 0)'];
const SAYS = ['say1 = "The sun rises in the east."', 'say2 = "At noon the sun stands highest."', 'say3 = "In the evening it sets in the west."'];

test("K1: a steps scene takes the sentence of the present step as its caption, and a caption line beside it changes nothing", async () => {
  const result = await compileAppFile(file(...STEPS, ...SAYS)), { ui, initialState } = result.definition;
  expect([result.faults, onlyPictureCards(ui)]).toEqual([[], true]);
  expect(captionOf(ui, initialState)).toEqual(["At noon the sun stands highest."]);
  expect(captionOf(ui, await pressed(result, "Morning"))).toEqual(["The sun rises in the east."]);
  const beside = await compileAppFile(file(...STEPS, 'caption = "A line that the walk does not show."', ...SAYS));
  expect([beside.faults, captionOf(beside.definition.ui, beside.definition.initialState), beside.expanded.includes("does not show")]).toEqual([[], ["At noon the sun stands highest."], false]);
  // With no narration line, a walk has no caption: the stepper and the picture show the step.
  const quiet = await compileAppFile(file(...STEPS));
  expect([quiet.faults, captionNode(quiet.definition.ui).length]).toEqual([[], 0]);
});

const HEART = ['scene("Как устроено сердце", lang="ru", palette="red")', 'control = parts("Часть", ["полая вена", "правый желудочек", "аорта"])', 's1 = use("heart", 150, 150, 220)',
  'say1 = "Приносит в сердце кровь от тела."', 'say2 = "Толкает кровь в лёгкие."', 'say3 = "Несёт кровь ко всему телу."'];

test("K1: a diagram takes the sentence of the present part as its caption", async () => {
  const result = await compileAppFile(file(...HEART)), { ui, initialState } = result.definition;
  expect([result.faults, onlyPictureCards(ui), captionOf(ui, initialState)]).toEqual([[], true, ["Приносит в сердце кровь от тела."]]);
  expect(captionOf(ui, await pressed(result, "аорта"))).toEqual(["Несёт кровь ко всему телу."]);
});

const ROCKS = ['items("Rocks", lang="en", palette="brown")', 'control = choice("Rock")', 'i1 = item("Igneous", "🌋", "It forms when hot magma cools.")',
  `i2 = item("Sedimentary", "🐚", ${JSON.stringify("It forms from sand and shells that press into layers over a long time. A second sentence takes it past the cap.")})`, 'say = "Tap a rock."'];

test("K1, K2: items show the text of the chosen item as the caption, with an inline choice, and the kit cuts a long item text", async () => {
  const result = await compileAppFile(file(...ROCKS)), { ui, initialState } = result.definition;
  expect([result.faults, onlyPictureCards(ui)]).toEqual([[], true]);
  const choice = every(ui).find((node) => node.bind && node.options);
  expect([choice.type, choice.variant, choice.options.map((option) => option.text)]).toEqual(["singleChoice", "inline", ["Igneous", "Sedimentary"]]);
  expect(captionOf(ui, initialState)).toEqual(["It forms when hot magma cools."]);
  expect(captionOf(ui, await pressed(result, "Sedimentary"))).toEqual(["It forms from sand and shells that press into layers over a long time."]);
  // A stored file keeps no card: a card line is a line that the template does not have.
  const card = await compileAppFile(file(...ROCKS.slice(0, 3), 'card = card("How it forms")'));
  expect(card.faults).toEqual(["card: the template items has no line card. Its lines are control and i1 to i6 (at most 6 items)"]);
});

test("K1: cells show the line caption under the slider, and a card line is a fault", async () => {
  const head = ['cells("Water of the Earth", lang="en", palette="blue")', 'control = slider("Parts of 100", 0, 100, 1, 71)'];
  const result = await compileAppFile(file(...head, 'caption = "Water covers about 71 parts of each 100."'));
  expect([result.faults, onlyPictureCards(result.definition.ui), captionOf(result.definition.ui, result.definition.initialState)]).toEqual([[], true, ["Water covers about 71 parts of each 100."]]);
  const card = await compileAppFile(file(...head, 'card = card("Water", "Most of it.")'));
  expect(card.faults[0]).toStartWith("card: the template cells has no line card");
  // A scene keeps no card either.
  const scene = await compileAppFile(file('scene("A lever", lang="en")', 's1 = circle(150, 150, 60, "accent")', 'card = card("Lever", "A bar.")'));
  expect(scene.faults[0]).toStartWith("card: the template scene has no line card");
});

test("K2: the choice and the steps of a scene are inline, and a still picture with a caption and no control is a clean scene", async () => {
  const choice = await compileAppFile(file('scene("Ship and nail", lang="en")', 'control = choice("Thing", ["Ship", "Nail"], "Ship")', 's1 = rect(-150, 150, 600, 300, "water", 0)',
    's2 = rect(90, $/v == "Ship" ? 110 : 250, 120, 40, "wood", 6)', 'caption = $/v == "Ship" ? "The hollow ship floats." : "The solid nail sinks."'));
  const node = every(choice.definition.ui).find((one) => one.action === "v_field");
  expect([choice.faults, node.type, node.variant]).toEqual([[], "singleChoice", "inline"]);
  const still = await compileAppFile(file('scene("The water cycle", lang="en")', 's1 = rect(-150, -150, 600, 380, "water+2", 0)', 's2 = rect(-150, 230, 600, 220, "water", 0)',
    's3 = circle(240, 60, 30, "sun", glow=true)', 'caption = "The sun lifts water as vapor, and the rain brings it back."', 'say = "Look at the way of the water."'));
  expect([still.faults, every(still.definition.ui).some((one) => one.bind), captionNode(still.definition.ui).length]).toEqual([[], false, 1]);
  // A steps control is an inline choice too (stage A, J3): the host tells it from the speeds by the row of the play button.
  const steps = await compileAppFile(file(...STEPS, ...SAYS));
  const walk = every(steps.definition.ui).find((one) => one.action === "v_field");
  expect([steps.faults, walk.type, walk.variant]).toEqual([[], "singleChoice", "inline"]);
  const items = await compileAppFile(file('items("Phases", lang="en")', 'control = steps("Phase", "Full")', 'i1 = item("New", "N", "The Moon is dark.")',
    'i2 = item("Full", "F", "The Moon is bright.")', 'say1 = "The new Moon is dark."', 'say2 = "The full Moon is bright."'));
  const itemsWalk = every(items.definition.ui).find((one) => one.action === "v_field");
  expect([items.faults, itemsWalk.type, itemsWalk.variant]).toEqual([[], "singleChoice", "inline"]);
});

test("K4: the play row shows beside a steps control and beside a choice, and the check run reaches its button and its speeds", async () => {
  for (const control of ['control = steps("Phase", ["New", "Full"])', 'control = choice("Phase", ["New", "Full"], "New")']) {
    const result = await compileAppFile(file('scene("The Moon", lang="en")', control, 'play = play("Turn", 0, 360, 8)', 's1 = circle(150, 150, 30, "water")',
      's2 = circle(150 + 100 * cos($/t), 150 - 100 * sin($/t), $/v == "Full" ? 18 : 12, "graphite")', 'caption = "The Moon goes around the Earth."',
      'say1 = "The new Moon is dark."', 'say2 = "The full Moon is bright."'));
    expect([control, result.faults]).toEqual([control, []]);
    // No node holds the play row hidden: the view of the row has no visible=false.
    const hidden = every(result.definition.ui).filter((node) => node.visible === false);
    const nodes = every(result.definition.ui), button = nodes.find((node) => node.action === "t_button"), speeds = nodes.find((node) => node.action === "t_speed");
    expect([control, hidden.length, button.variant, speeds.variant]).toEqual([control, 0, "tonalIcon", "inline"]);
    // The order of the screen: the picture, the control, the play row, then the caption.
    const order = nodes.filter((node) => ["v_field", "t_button"].includes(node.action) || node.style?.emphasis === "caption").map((node) => node.action ?? "caption");
    expect([control, order]).toEqual([control, ["v_field", "t_button", "caption"]]);
  }
});

/** The drawing of a state of a compile: each command of the run-time surface, in its order. */
async function drawn(result, state = result.definition.initialState) {
  const { app } = await clocked(result.expanded), surface = Object.values(handle(request(state, { type: "open" }), app).surfaces)[0];
  return surface.commands ?? surface;
}
/** The name chips of a drawing: each surface box with the accent text in its center, as {text, box}. */
const chipsOf = (commands) => commands.filter((one) => one.type === "text" && one.color === "accent").map((one) => {
  const box = commands.find((rect) => rect.type === "roundRect" && rect.color === "surface" && Math.abs(rect.x + rect.width / 2 - one.x) < 0.02 && Math.abs(rect.y + rect.height / 2 - one.y) < 0.02);
  return { text: one.text, box: box && { x: box.x, y: box.y, w: box.width, h: box.height } };
});
/** The dots of a drawing: each small accent circle. */
const dotsOf = (commands) => commands.filter((one) => one.type === "circle" && one.color === "accent" && one.radius < 4);
const inside = ({ x, y, w, h }) => x >= 0 && y >= 0 && x + w <= 300 && y + h <= 300;

test("K3: a diagram over a ready object keeps its SIZE, names the part of the step in a chip inside the picture, and puts a dot at each other part", async () => {
  const result = await compileAppFile(file(...HEART));
  expect(result.faults).toEqual([]);
  for (const [value, name] of [[null, "полая вена"], ["правый желудочек", "правый желудочек"], ["аорта", "аорта"]]) {
    const commands = await drawn(result, value ? await pressed(result, value) : undefined);
    // The object is one group at its X and Y with the scale of its SIZE: no name column takes room from it.
    expect([value, commands[0].type, commands[0].transform.map((one) => Math.round(one * 100) / 100 + 0)]).toEqual([value, "group", [2.2, 0, 0, 2.2, 150, 150]]);
    const chips = chipsOf(commands);
    expect([value, chips.map((one) => one.text), Boolean(chips[0].box) && inside(chips[0].box)]).toEqual([value, [name], true]);
    expect([value, Math.abs(chips[0].box.w - (lineWidth(name) + 2 * LABEL.pad)) < 0.05]).toEqual([value, true]);
    // A dot at each other part of the walk, and the accent ring of the image entry at the part of the step.
    const all = (list) => list.flatMap((one) => (one.type === "group" ? all(one.children) : [one]));
    expect([value, dotsOf(commands).length, all(commands).filter((one) => one.type === "arc" && one.color === "accent").length]).toEqual([value, 2, 1]);
  }
  // The file stays short: the control names the parts, and no line holds a name, a mark, or parts=true.
  expect([result.kept.includes("mark="), result.kept.includes("parts=true"), result.kept.includes("text(")]).toEqual([false, false, false]);
});

const VOLCANO = [["очаг магмы", 150, 270], ["жерло", 150, 180], ["кратер", 150, 76], ["лава", 220, 200], ["пепел", 150, 14]];
const volcano = (points) => file('scene("Как устроен вулкан", lang="ru", palette="orange")', `control = parts("Часть", [${points.map(([name, x, y]) => `part(${JSON.stringify(name)}, ${x}, ${y})`).join(", ")}])`,
  's1 = rect(-150, 250, 600, 200, "land", 0)', 's2 = polygon([20, 290, 120, 70, 180, 70, 280, 290], "wood", gradient="wood-2")', 's3 = circle(150, 30, 26, "muted", gradient="muted-2", radial=1)',
  ...points.map(([name], index) => `say${index + 1} = "Это ${name}."`));

test("K3: a free diagram keeps the drawing where the model drew it, rings the part of the step, and puts its chip above the ring or under it", async () => {
  const result = await compileAppFile(volcano(VOLCANO));
  expect(result.faults).toEqual([]);
  const first = await drawn(result);
  // The shapes of the model come first and stay as they are: no group moves or scales them.
  expect([first[0].type, first[0].x, first[0].width, first[1].type, first[1].points[0]]).toEqual(["rect", -150, 600, "polygon", [20, 290]]);
  for (const [index, [name, x, y]] of VOLCANO.entries()) {
    const commands = index ? await drawn(result, await pressed(result, name)) : first, chips = chipsOf(commands);
    const rings = commands.filter((one) => one.type === "arc" && one.color === "accent"), halos = commands.filter((one) => one.type === "arc" && one.color === "surface");
    expect([name, rings.length, halos.length, rings[0].x + rings[0].width / 2, rings[0].y + rings[0].height / 2]).toEqual([name, 1, 1, x, y]);
    expect([name, chips.map((one) => one.text), inside(chips[0].box), dotsOf(commands).length]).toEqual([name, [name], true, VOLCANO.length - 1]);
    // The chip stands above the ring when it has room, else under it.
    const above = chips[0].box.y + chips[0].box.h <= y - 8, under = chips[0].box.y >= y + 8;
    expect([name, y < 50 ? under : above]).toEqual([name, true]);
  }
});

test("K3: a point out of the box is a fault, and 8 names at the cap each fit in a chip inside the box", async () => {
  const out = await compileAppFile(volcano([["кратер", 150, 76], ["склон", 304, 200]]));
  expect(out.faults.some((fault) => fault.startsWith("control: the point of part 2 is at 304, 200: each point stays inside the box"))).toBe(true);
  // A point near the side of the box is no fault now: the thing fills the box, and no name column needs room.
  expect((await compileAppFile(volcano([["кратер", 150, 76], ["склон", 24, 280]]))).faults).toEqual([]);
  const names = ["верхний кратер горы1", "главное жерло вниз22", "очаг магмы под горой", "столб пепла и дым444", "лава течёт по склону", "старый слой пепла666", "боковой конус лавы77", "газы и пар над горой"];
  expect(names.every((name) => [...name].length === BUDGET.part)).toBe(true);
  const points = names.map((name, index) => [name, index % 2 ? 280 : 20, 12 + index * 38]), result = await compileAppFile(volcano(points));
  expect(result.faults).toEqual([]);
  for (const [name] of points) {
    const chips = chipsOf(await drawn(result, await pressed(result, name)));
    expect([name, chips.length, inside(chips[0].box)]).toEqual([name, 1, true]);
  }
});

test("K3: in a stream, the ring, the chip, and the dots come after the shapes, once a line after the shapes has come", async () => {
  for (const lines of [HEART, volcano(VOLCANO).trim().split("\n")]) {
    const frames = [];
    for (let count = 1; count <= lines.length; count++) frames.push((await previewStep({ text: file(...lines.slice(0, count)), plan: null, previous: null })).frame);
    const commands = (frame) => Object.values(frame.drawing)[0]?.commands ?? [];
    const chipsAt = frames.map((frame) => commands(frame).some((one) => one.type === "text"));
    const shapes = lines.filter((line) => /^s\d+ = /.test(line)).length, firstSay = lines.findIndex((line) => line.startsWith("say1"));
    // No chip before the first narration line, and each frame from it on draws the chip as the last commands.
    expect([lines[0], chipsAt.indexOf(true)]).toEqual([lines[0], firstSay]);
    const before = commands(frames[firstSay - 1]), after = commands(frames[firstSay]);
    expect([lines[0], shapes > 0, after.slice(0, before.length)]).toEqual([lines[0], true, before]);
    expect([lines[0], after.at(-1).type]).toEqual([lines[0], "text"]);
  }
});
