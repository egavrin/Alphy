// The registry of the ready plans (kit/README.md, section "Ready plans"): each template is a ready plan (kit/templates/WORD.plan, in the module
// plans.js that kit/build.js writes) and its line table (kit/templates/WORD.js). template.js reads, merges, fills, and builds a template file
// with the entry of its word. A test can add a ready plan with register.

import { PLANS } from "./plans.js";
import { SCENE } from "../templates/scene.js";
import { ITEMS } from "../templates/items.js";
import { CELLS } from "../templates/cells.js";
import { COMPARE } from "../templates/compare.js";
import { TABLE } from "../templates/table.js";
import { CHART } from "../templates/chart.js";
import { NODATA } from "../templates/nodata.js";

/** The entry of each template by its word: its line table and its ready plan as plan. */
export const READY = {};
/** Adds the line table with its ready plan to the registry, and returns the entry. */
export function register(table, plan) {
  READY[table.word] = { ...table, plan };
  return READY[table.word];
}
// The explain family first, then the data family.
for (const table of [SCENE, ITEMS, CELLS, COMPARE, TABLE, CHART, NODATA]) register(table, PLANS[table.word]);

/** The words of the data family. */
export const DATA_WORDS = new Set(Object.values(READY).filter((entry) => entry.family === "data").map((entry) => entry.word));
/** The first line of a template file that the app ships, with the word as group 1 (prompt.json templateHead). */
export const TEMPLATE_HEAD = new RegExp(`^\\s*(${Object.keys(READY).join("|")})\\s*\\(`);
/** The first line of a data form, as the host tells it (prompt.json dataHead): a spoken change of such a screen is a new data screen. */
export const DATA_HEAD = new RegExp(`^\\s*(?:${[...DATA_WORDS].join("|")})\\s*\\(`);
