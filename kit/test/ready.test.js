// The ready plans (docs/superpowers/specs/2026-11-28-ready-plans-design.md, section 5): each template is a ready plan with a line table, and
// one engine fills it. The task screens predict and collect of the tests (test/fixtures/ready) are ready plans that need no line of engine code.

import { afterAll, beforeAll, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import "./helpers.js";
import { compileStep, previewStep } from "../src/compile.js";
import { READY, register } from "../src/ready.js";
import { PREDICT } from "./fixtures/ready/predict.js";
import { COLLECT } from "./fixtures/ready/collect.js";

const read = (path) => readFileSync(new URL(path, import.meta.url), "utf8");
const step = (text, input = {}) => compileStep({ text, plan: null, read: true, previous: null, cap: 65536, ...input });
const TASKS = { predict: PREDICT, collect: COLLECT };
const reply = (word) => read(`./fixtures/ready/${word}.reply`);

beforeAll(() => { for (const [word, table] of Object.entries(TASKS)) register(table, read(`./fixtures/ready/${word}.plan`)); });
afterAll(() => { for (const word of Object.keys(TASKS)) delete READY[word]; });

/** The example reply of each ready plan of kit/templates. */
const EXAMPLES = { scene: "ship", items: "word-parts", cells: "majority", compare: "compare-caps", table: "table-caps", chart: "chart-caps", nodata: "nodata-caps" };

test("each ready plan of kit/templates with the slots of its example reply is an app file that compiles clean", async () => {
  expect(Object.keys(READY).filter((word) => !TASKS[word])).toEqual(Object.keys(EXAMPLES));
  for (const [word, name] of Object.entries(EXAMPLES)) {
    const { result } = await step(read(`./fixtures/template/${name}.plan`));
    expect([word, result.template, result.faults]).toEqual([word, word, []]);
    // Each line of the app file is a line of the ready plan with each slot filled.
    const lines = READY[word].plan.split("\n").filter(Boolean).map((line) => new RegExp(`^${line.split(/\{\{\w+\}\}/).map((part) => part.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")).join("[^]*")}$`));
    for (const line of result.expanded.split("\n").filter(Boolean)) expect([word, line, lines.some((one) => one.test(line))]).toEqual([word, line, true]);
    const app = await step(result.expanded, { read: false });
    expect([word, app.result.template, app.result.faults]).toEqual([word, undefined, []]);
  }
});

/** The ui, the state schema, and the start state of a definition, as JSON, and the drawing commands of its frame in a stable order. */
const appOf = ({ definition, frame }) => [JSON.stringify([definition.ui, definition.stateSchema, definition.initialState]),
  Object.values(frame.drawing).flatMap((surface) => surface.commands.map((command) => JSON.stringify(command))).sort()];

test("predict and collect: the reply compiles clean to the app of the hand-written app file, and so does its kept file", async () => {
  for (const word of Object.keys(TASKS)) {
    const { result } = await step(reply(word)), hand = (await step(read(`./fixtures/ready/${word}-app.plan`))).result;
    expect([word, result.template, result.faults, result.leftOut, hand.faults]).toEqual([word, word, [], [], []]);
    expect([word, appOf(result)]).toEqual([word, appOf(hand)]);
    const kept = (await step(result.kept)).result;
    expect([word, kept.faults, JSON.stringify(kept.definition)]).toEqual([word, [], JSON.stringify(result.definition)]);
  }
});

/** Each node of before that after does not keep at its path: the same type and own properties, and the children of before first. */
function moved(before, after, path = "ui") {
  const { children = [], ...own } = before, { children: more = [], ...ownAfter } = after ?? {};
  if (JSON.stringify(own) !== JSON.stringify(ownAfter)) return [`${path}: ${JSON.stringify(own)} is ${JSON.stringify(ownAfter)}`];
  return children.flatMap((child, index) => moved(child, more[index], `${path}/${index}`));
}

test("predict and collect: each prefix of the reply gives a frame, and a longer prefix keeps the ui and the drawing of a shorter one", async () => {
  for (const word of Object.keys(TASKS)) {
    const lines = reply(word).split("\n").filter(Boolean), frames = [];
    for (let count = 1; count <= lines.length; count++) {
      const out = await previewStep({ text: lines.slice(0, count).join("\n") + "\n" });
      expect([word, count, out.kind]).toEqual([word, count, "preview"]);
      frames.push(out.frame);
    }
    for (let k = 1; k < frames.length; k++) {
      const [shorter, longer] = [frames[k - 1], frames[k]];
      for (const [id, { commands }] of Object.entries(shorter.drawing)) expect([word, k, id, longer.drawing[id]?.commands.slice(0, commands.length)]).toEqual([word, k, id, commands]);
      expect([word, k, moved(shorter.ui, longer.ui)]).toEqual([word, k, []]);
    }
  }
});

test("predict and collect: a wrong line is a fault of that line, each other line stays, and a repair reply replaces it by its name", async () => {
  const cases = {
    predict: ['ask = ask("Как?", ["Да"], "Да", "Верно.", "Нет.")', "ask", "a question needs at least 2 options"],
    collect: ['control = counter("Камешки", 0, 5)', "control", "the control of collect is counter(LABEL, MIN, MAX, START) with numbers"],
  };
  for (const [word, [wrong, name, message]] of Object.entries(cases)) {
    const good = reply(word).split("\n").find((line) => line.startsWith(`${name} =`)), broken = reply(word).replace(good, wrong);
    const { result } = await step(broken);
    expect([word, result.faults, result.leftOut, result.definition === null]).toEqual([word, [`${name}: ${message}`], [name], false]);
    for (const line of reply(word).split("\n").filter((one) => one && one !== good)) expect([word, result.kept.includes(line + "\n")]).toEqual([word, true]);
    const repaired = await step(good, { plan: broken });
    expect([word, repaired.text, repaired.result.faults, JSON.stringify(repaired.result.definition)]).toEqual([word, reply(word), [], JSON.stringify((await step(reply(word))).result.definition)]);
  }
});

test("a line that reads a value that its ready plan does not name is a fault of that line", async () => {
  const { result } = await step(reply("predict").replace('s5 = circle(96 + $/t, 198, 8, "ink")', 's5 = circle(96 + $/x, 198, 8, "ink")'));
  expect(result.faults).toEqual(["s5: a line reads only $/v, the value of the control, and $/t, the value of run, and this line reads $/x"]);
  // $/t is the value of the run line, so a shape reads it only beside a run line.
  const { result: still } = await step(reply("predict").split("\n").filter((line) => !line.startsWith("run =")).join("\n"));
  expect(still.faults[0]).toBe("s2: a line reads only $/v, the value of the control, and this line reads $/t");
});

test("a ready plan line with a slot that has no value is not in the app file", async () => {
  const { result } = await step(reply("predict").split("\n").filter((line) => !line.startsWith("ask =")).join("\n"));
  expect([result.faults, result.expanded.includes("need go = button("), result.expanded.includes("need guess")]).toEqual([[], true, false]);
  const ship = await step(read("./fixtures/template/ship.plan").split("\n").filter((line) => !line.startsWith("caption =")).join("\n"));
  expect([ship.result.faults, ship.result.expanded.includes("need picture"), ship.result.expanded.includes("need caption")]).toEqual([[], true, false]);
});

test("the engine names no template word outside a comment", () => {
  const code = read("../src/template.js").replace(/\/\*[^]*?\*\//g, "").replace(/\/\/.*$/gm, "");
  expect(code.match(/\b(?:scene|items|cells|compare|table|chart|nodata)\b/g)).toBeNull();
});
