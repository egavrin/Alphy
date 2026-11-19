// The size of each compiled plan against each limit of Alphy. The numbers come from Alphy's code, not from the kit's own constants.

import { expect, test } from "bun:test";
import { budgetOf, hostBytes, utf8 } from "../src/draw.js";
import { emit } from "../src/emit.js";
import { MAX_POINTS, handle } from "../src/run.js";
import { allPlans, every, planned } from "./helpers.js";

/** Each limit of Alphy that a compiled plan must meet, with the code that holds it. */
const ALPHY = {
  // SandboxContract.MAX_SOURCE_BYTES: the UTF-8 bytes of the source.
  source: 65536,
  // ToolSpec.MAX_DEFINITION_BYTES (256 KiB): the bytes of the definition as JSONObject.toString writes it.
  definition: 256 * 1024,
  // ToolUiCatalog.MAX_UI_BYTES (64 KiB): the bytes of the ui as root.toString writes it.
  ui: 64 * 1024,
  // ToolUiCatalog.MAX_NODES: each node of the ui, also each node of a row template.
  nodes: 512,
  // ToolSpec.validateState: the bytes of the state.
  state: 49152,
  // ToolValueSchema: the top-level fields of the state schema, 1 to 40.
  fields: 40,
  // ToolUiCatalog.MAX_RENDER_WORK: the render work of the screen (validateRenderedWork).
  work: 2048,
  // GraphicsContract.frame: the commands, point pairs, and text characters of all surfaces of one reply, and FRAME_BYTES.
  frame: { commands: 512, pairs: 2048, chars: 2048, bytes: 32768 },
};
/** The bar of the kit for a source: room for a source that grows, under ALPHY.source. */
const SOURCE_BAR = 60000;
/** The saved plans in kit/plans. */
const SAVED = 9;

const COLUMNS = ["path", "source", "definition", "ui", "nodes", "state", "fields", "work", "commands", "pairs", "chars", "bytes"];

const LISTS = new Set(["list", "table", "groupedList", "lazyList", "lazyGrid"]);
const CHOICES = new Set(["select", "tabs", "singleChoice", "multiChoice", "segmented", "navigationBar", "navigationRail", "menu"]);
/** The kit's count of render work, by the rules of ToolUiCatalog.validateRenderedWork: each list full to its maxItems, and each series of
 * a chart at MAX_POINTS points (the host takes at most 256 points in all series of a chart, so this count is not below the host's). */
const renderWork = (node, schema) => 1 + (CHOICES.has(node.type) ? node.options.length : 0) + (node.type === "chart" ? MAX_POINTS * node.series.length : 0)
  + (LISTS.has(node.type) ? schema[node.bind].maxItems * (renderWork(node.rowTemplate, schema) + (node.groupBy === undefined ? 0 : 1) + (node.reorder === undefined ? 0 : 1)) : 0)
  + (node.children ?? []).reduce((sum, child) => sum + renderWork(child, schema), 0);

test("budget: each saved plan is inside each limit", async () => {
  const rows = [], over = [];
  for (const [path, text] of allPlans()) {
    const { definition, app } = emit(await planned(text));
    if (!definition) {
      // A fixture of the prototype can fail to compile. A saved plan cannot.
      if (path.startsWith("plans/")) over.push(`${path}: no definition`);
      continue;
    }
    const reply = handle({ artifactId: "kit-test", revision: 1, state: definition.initialState, event: { type: "open" } }, app);
    const drawn = Object.entries(reply.surfaces ?? {}).map(([id, { commands }]) => [id, commands]);
    const row = { path, source: utf8(definition.source), definition: hostBytes(definition), ui: hostBytes(definition.ui), nodes: every(definition.ui).length,
      state: hostBytes(definition.initialState), fields: Object.keys(definition.stateSchema).length, work: renderWork(definition.ui, definition.stateSchema), ...budgetOf(drawn) };
    rows.push(row);
    if (row.source >= SOURCE_BAR) over.push(`${path}: source ${row.source} bytes, and the bar is ${SOURCE_BAR}`);
    for (const key of ["source", "definition", "ui", "nodes", "state", "fields", "work"]) if (row[key] > ALPHY[key]) over.push(`${path}: ${key} ${row[key]} > ${ALPHY[key]}`);
    if (row.fields < 1) over.push(`${path}: no state field`);
    for (const [key, limit] of Object.entries(ALPHY.frame)) if (row[key] > limit) over.push(`${path}: drawing ${key} ${row[key]} > ${limit}`);
    // A plan with a surface draws on open: an open reply with no commands would pass each limit of the drawing.
    if (definition.surfaces?.length && !row.commands) over.push(`${path}: ${definition.surfaces.length} surfaces and no command on open`);
  }
  console.log([COLUMNS, COLUMNS.map(() => "---"), ...rows.map((row) => COLUMNS.map((key) => row[key]))].map((cells) => `| ${cells.join(" | ")} |`).join("\n"));
  expect(over).toEqual([]);
  expect(rows.filter((row) => row.path.startsWith("plans/"))).toHaveLength(SAVED);
});
