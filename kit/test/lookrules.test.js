// The look rules of a plan as kit tests (Task 11b-2b, B), over the nine saved plans and the look fixtures (LOOK_FIXTURES of build.js).
// The main element, the typed fields, and the fields of a row follow score_app of tools/agent_corpus/visual_score.py, which bun kit/look.js runs.
// Rule 4, a plan with only a list gets a hero card with its count: entries.test.js ("entries: a list that a steps or tabs need groups ...").
// Rule 5, two figures are a grid and three or more are rows: figures.test.js.

import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { basename } from "node:path";
import { fileURLToPath } from "node:url";
import { LOOK_FIXTURES, plansIn } from "../build.js";
import { emit } from "../src/emit.js";
import { handle } from "../src/run.js";
import { built, every, planned, press, valueAt } from "./helpers.js";

const PATHS = [...plansIn(fileURLToPath(new URL("../plans", import.meta.url))).sort(), ...LOOK_FIXTURES];
/** Each saved plan and look fixture by its name, compiled: its definition and its run-time. */
const APPS = await Promise.all(PATHS.map(async (path) => {
  const { definition, app, faults } = emit(await planned(readFileSync(path, "utf8")));
  return { name: basename(path, ".plan"), definition, app, faults };
}));

const FIELD_TYPES = new Set(["input", "numberInput", "timeInput", "dateInput", "dateRangeInput"]);
const CHROME = new Set(["appBar", "bottomBar", "navigationBar", "navigationRail", "spacer", "divider"]);
const kids = (node) => (node.children ?? []).filter((child) => child && typeof child === "object");
const shows = (node, state) => node.visible !== false && (!node.visibleBind || Boolean(valueAt(state, node.visibleBind)));
/** The nodes of the first screen: no hidden node, no node of a dialog or a sheet, and only the first page of a pager. */
const firstScreen = (node, state) => ["dialog", "bottomSheet"].includes(node.type) || !shows(node, state) ? []
  : [node, ...(["pager", "routeStack"].includes(node.type) ? kids(node).slice(0, 1) : kids(node)).flatMap((child) => firstScreen(child, state))];
/** The nodes of the row templates of lists. */
const rowNodes = (node, inRow = false) => [...(inRow ? [node] : []), ...kids(node).flatMap((child) => rowNodes(child, inRow)), ...(node.rowTemplate ? rowNodes(node.rowTemplate, true) : [])];
const isHero = (node) => (node.type === "card" && node.variant === "hero") || (node.type === "progressRing" && node.size === "large") || (node.type === "progress" && ["level", "dots"].includes(node.variant))
  || ["image", "canvas"].includes(node.type) || (node.type === "emoji" && node.size === "large") || (["metric", "text"].includes(node.type) && node.style?.emphasis === "display");
const hasHero = (node, depth = 0) => isHero(node) || (depth < 2 && kids(node).some((child) => hasHero(child, depth + 1)));
/** The first two nodes of the screen under the columns of the app. */
const topNodes = (ui) => {
  let node = ui;
  while (["theme", "scroll", "column"].includes(node.type) && kids(node).length === 1) node = kids(node)[0];
  const top = kids(node).filter((child) => !CHROME.has(child.type)).slice(0, 2);
  return top.length ? top : [node];
};

test("look: each saved plan and look fixture compiles with no fault", () => {
  expect(APPS.filter((app) => !app.definition).map((app) => [app.name, app.faults])).toEqual([]);
  expect(APPS.length).toBe(9 + LOOK_FIXTURES.length);
});

test("look: each saved plan has its main element in the first two nodes", () => {
  // tea: the hero card of the host's countdown. section: the author's hero card with the count of the habits.
  const missing = APPS.filter(({ definition }) => definition.presentation?.mode !== "immersive" && !topNodes(definition.ui).some((node) => hasHero(node)));
  expect(missing.map((app) => app.name)).toEqual([]);
});

test("look: no saved plan has more than two typed fields on the first screen", () => {
  const over = APPS.map(({ name, definition }) => [name, firstScreen(definition.ui, definition.initialState).filter((node) => FIELD_TYPES.has(node.type)).length]).filter(([, count]) => count > 2);
  expect(over).toEqual([]);
});

test("look: no line of a saved plan has the muted role, because Alphy draws a muted shape at the alpha 64", () => {
  // SurfaceDrawing.kt 185 (MUTED_SHAPE_ALPHA): a thin muted stroke, such as the orbit of lesson, was faint (1.6 to 1 in the dark theme).
  const faint = APPS.flatMap(({ name, definition, app }) => Object.values(handle({ artifactId: "kit-test", revision: 1, state: definition.initialState, event: { type: "open" } }, app).surfaces ?? {})
    .flatMap(({ commands }) => commands).filter((command) => command.stroke && command.color === "muted").map((command) => [name, command.type]));
  expect(faint).toEqual([]);
});

test("look: no saved plan has a typed field in a row template", () => {
  expect(APPS.map(({ name, definition }) => [name, rowNodes(definition.ui).filter((node) => FIELD_TYPES.has(node.type)).length]).filter(([, count]) => count)).toEqual([]);
});

/**
 * The buttons that Alphy fills in a group (NativeToolUi.kt secondaryButtons): a section, a card, a column, or a row with two or more
 * shown buttons fills each button whose variant is filled, and, when no enabled button is filled, the first enabled button with no variant.
 * A lone button with no variant is filled.
 */
const FILLED = new Set(["filled", "fab", "extendedFab"]);
const filledIn = (group, state) => {
  const buttons = kids(group).filter((child) => child.type === "button" && shows(child, state));
  const enabled = (node) => node.enabled !== false && (!node.enabledBind || Boolean(valueAt(state, node.enabledBind)));
  if (buttons.length < 2) return buttons.filter((button) => !button.variant || FILLED.has(button.variant));
  const primary = buttons.some((button) => FILLED.has(button.variant) && enabled(button)) ? null : buttons.find((button) => !button.variant && enabled(button));
  return buttons.filter((button) => FILLED.has(button.variant) || button === primary);
};
const GROUPS = ["section", "card", "column", "form", "row", "flowRow"];
/** The screens that a test reaches with at most one event: the first screen, and the screen after the press of the main action (the first filled button). */
const screens = ({ definition, app }) => {
  const main = firstScreen(definition.ui, definition.initialState).flatMap((node) => filledIn(node, definition.initialState)).find((button) => button.action);
  const first = { when: "first screen", ui: definition.ui, state: definition.initialState, main };
  if (!main) return [first];
  const reply = press(definition.initialState, app, main.action);
  return [first, { when: `after ${main.text}`, ui: reply.ui ?? definition.ui, state: reply.state, main }];
};

test("look: the group of the main action has exactly one filled button and each other group at most one, on the first screen and after the press of the main action", () => {
  const found = APPS.flatMap((compiled) => screens(compiled).flatMap(({ when, ui, state, main }) => {
    const groups = firstScreen(ui, state).filter((node) => GROUPS.includes(node.type));
    const crowded = groups.map((group) => filledIn(group, state)).filter((filled) => filled.length > 1).map((filled) => [compiled.name, when, filled.map((button) => button.text)]);
    // The group of the main action, while it shows a button: Pause of a timer is filled while it runs (round before the review).
    const home = main && groups.find((group) => kids(group).some((child) => child.action === main.action));
    const shown = home && kids(home).some((child) => child.type === "button" && shows(child, state));
    const count = shown ? filledIn(home, state).length : 1;
    return [...crowded, ...(count === 1 ? [] : [[compiled.name, when, `${count} filled buttons in the group of ${main.text}`]])];
  }));
  expect(found).toEqual([]);
});

/** The controls that Alphy draws with an outline: the typed fields, a segmented control, a select, and a multi-choice. */
const OUTLINED = new Set([...FIELD_TYPES, "segmented", "select", "multiChoice"]);
/**
 * Each control of a hero card that Alphy draws outlined or tonal, shown or not: on a hero card such a control shows no container in the dark theme
 * (model3d.png of appfile-look-all-2: 1.0 to 1). A button with no variant beside another button can be outlined (secondaryButtons).
 */
const outlinedIn = (hero) => every(hero).flatMap((node) => {
  if (OUTLINED.has(node.type)) return [node.text ?? node.type];
  const buttons = kids(node).filter((child) => child.type === "button");
  return buttons.filter((button) => ["outlined", "tonal", "elevated"].includes(button.variant) || (!button.variant && buttons.length > 1)).map((button) => button.text);
});

test("look: the hero rule finds an outlined button and two buttons with no variant that stand directly in a hero card", async () => {
  const heroOf = async (...buttons) => {
    const { definition } = await built('app("T", lang="en")', "$/n = 0", "root = Column([hero])", `hero = Card([Metric("Count", $/n), ${buttons.join(", ")}], variant="hero")`);
    return every(definition.ui).find((node) => node.type === "card");
  };
  expect(outlinedIn(await heroOf('Button("Less", action=set($/n, 0), variant="outlined")'))).toEqual(["Less"]);
  expect(outlinedIn(await heroOf('Button("One", action=set($/n, 1))', 'Button("Two", action=set($/n, 2))'))).toEqual(["One", "Two"]);
  // A lone button with no variant is filled, so it can stand on a hero card.
  expect(outlinedIn(await heroOf('Button("One", action=set($/n, 1))'))).toEqual([]);
});

test("look: a hero card holds no outlined or tonal button", () => {
  // Round before the review: a hero card holds the main element and at most a filled button or a text button. Each other control stands under the card.
  const found = APPS.flatMap((compiled) => screens(compiled).flatMap(({ when, ui }) => every(ui).filter((node) => node.type === "card" && node.variant === "hero")
    .flatMap(outlinedIn).map((text) => [compiled.name, when, text])));
  expect(found).toEqual([]);
});
