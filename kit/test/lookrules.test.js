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
import { planned, press, valueAt } from "./helpers.js";

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
/** Each group of the first screen with more than one filled button, by its type and the texts of those buttons. */
const crowded = (ui, state) => firstScreen(ui, state).filter((node) => ["section", "card", "column", "form", "row", "flowRow"].includes(node.type))
  .map((group) => filledIn(group, state)).filter((filled) => filled.length > 1).map((filled) => filled.map((button) => button.text));

test("look: a group of buttons has one filled button, on the first screen and after the press of the main action", () => {
  // At most one: after Start of a timer, Pause is outlined and Reset is a text button (A.1), so that group has no filled button.
  const found = APPS.flatMap(({ name, definition, app }) => {
    const first = crowded(definition.ui, definition.initialState).map((texts) => [name, "first screen", texts]);
    // The main action: the first filled button of the first screen.
    const main = firstScreen(definition.ui, definition.initialState).flatMap((node) => filledIn(node, definition.initialState)).find((button) => button.action);
    if (!main) return first;
    const reply = press(definition.initialState, app, main.action), ui = reply.ui ?? definition.ui;
    return [...first, ...crowded(ui, reply.state).map((texts) => [name, `after ${main.text}`, texts])];
  });
  expect(found).toEqual([]);
});
