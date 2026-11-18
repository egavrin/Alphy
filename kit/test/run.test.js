// The run-time: each event of a compiled app, through handle and through the source text in a bare context.

import { expect, test } from "bun:test";
import { createContext, runInContext } from "node:vm";
import { App } from "../src/core.js";
import { emit, runtime } from "../src/emit.js";
import { handle } from "../src/run.js";
import { plan, planned } from "./helpers.js";

const compiled = async (text) => { const file = await planned(text); return { file, ...emit(file) }; };
const request = (state, event) => ({ artifactId: "kit-test", revision: 1, state, event });
const nodes = (node) => [node, ...(node.children ?? []).flatMap(nodes)];
/** The values of the objects d, d2, v, and so on: the values that the run-time computes. */
const derived = (state) => Object.entries(state).filter(([key]) => /^[dv]\d*$/.test(key)).flatMap(([, values]) => Object.values(values));

test("run: an action sets the bound value and the figures follow", async () => {
  const { file, definition, app } = await compiled(plan("calc"));
  const kit = (bill) => { const prototype = new App(file); prototype.input("bill_field", bill); return prototype.state; };
  const first = handle(request(definition.initialState, { type: "action", actionId: "bill_field", value: 200 }), app);
  expect(first.state.d).toEqual({ perPerson_text: kit(200).perPerson_text, total_text: kit(200).total_text });
  const { state } = handle(request(first.state, { type: "action", actionId: "bill_field", value: 80 }), app);
  expect(state.bill).toBe(80);
  expect(state.d.perPerson_text).not.toBe(first.state.d.perPerson_text);
  expect(state.d).toEqual({ perPerson_text: kit(80).perPerson_text, total_text: kit(80).total_text });
});

test("run: open of the initial state gives the initial state", async () => {
  const { definition, app } = await compiled(plan("calc"));
  expect(handle(request(definition.initialState, { type: "open" }), app)).toEqual({ state: definition.initialState });
});

test("run: render and preview change no state", async () => {
  const { definition, app } = await compiled(plan("calc"));
  // A state whose figures do not follow its inputs: a read-only event must still give it back as it is.
  const stale = { ...definition.initialState, bill: 500 };
  for (const type of ["preview", "render"]) expect(handle(request(stale, { type }), app)).toEqual({ state: stale });
});

test("run: extreme inputs keep each derived value finite", async () => {
  const { definition, app } = await compiled(`${plan("calc")}need note = input("text", "Note", "Hi")\nneed echo = figure("text", "Echo", upper($/note))\n`
    + 'need zero = input("number", "Divisor", 1, min=0, max=5)\nneed ratio = content(10 / $/zero)\n');
  const samples = nodes(definition.ui).flatMap((node) => node.type === "input" ? [[node, ""], [node, "Preview"]]
    : ["numberInput", "slider"].includes(node.type) ? [[node, node.min], [node, node.max]] : []);
  expect(samples.map(([node]) => node.type).sort()).toEqual(["input", "input", ...Array(6).fill("numberInput"), "slider", "slider"]);
  for (const [node, value] of samples) {
    const { state } = handle(request(definition.initialState, { type: "action", actionId: node.action, value }), app);
    expect(state[node.bind]).toBe(value);
    for (const shown of derived(state)) expect(typeof shown === "string" || Number.isFinite(shown)).toBe(true);
  }
  // 10 / 0 is Infinity in the kit, and a shown number that is not finite is 0.
  const { state } = handle(request(definition.initialState, { type: "action", actionId: "zero_field", value: 0 }), app);
  expect(state.d.ratio_view_text).toBe(0);
});

test("run: a reply holds a new ui only when the screen changes", async () => {
  const { definition, app } = await compiled([
    'app("Pick", lang="en")', "$/on = false", "$/count = 0", "root = Column([pick, more, shown])",
    'pick = Button("Pick", action=toggle($/on), selected=$/on)', 'more = Button("More", action=inc($/count))', 'shown = Metric("Count", $/count)',
  ].join("\n"));
  const picked = handle(request(definition.initialState, { type: "action", actionId: "pick", value: null }), app);
  expect(picked.state.on).toBe(true);
  expect(nodes(picked.ui).find((node) => node.action === "pick").variant).toBe("filled");
  expect(nodes(definition.ui).find((node) => node.action === "pick").variant).toBe("tonal");
  const more = handle(request(picked.state, { type: "action", actionId: "more", value: null }), app);
  expect(more).toEqual({ state: { ...picked.state, count: 1 } });
});

test("run: an action id that the app does not have changes nothing", async () => {
  const { definition, app } = await compiled(plan("calc"));
  for (const actionId of ["nothing", "toString"]) {
    expect(handle(request(definition.initialState, { type: "action", actionId, value: 1 }), app)).toEqual({ state: definition.initialState });
  }
});

test("source: the source of calc.plan is under 64 KiB and evaluates as a function body", async () => {
  const { definition, app } = await compiled(plan("calc"));
  const bytes = (text) => Buffer.byteLength(text, "utf8");
  console.log(`calc.plan source: ${bytes(definition.source)} bytes (run-time ${bytes(runtime.text)}, compiled data ${bytes(JSON.stringify(app))}); definition ${bytes(JSON.stringify(definition))}; ui ${bytes(JSON.stringify(definition.ui))}`);
  expect(bytes(definition.source)).toBeLessThan(60000);
  // The sandbox's own wrapper (SandboxClient.evaluationScript) in a context with only the language's globals.
  const context = createContext();
  const run = (input) => JSON.parse(runInContext(`JSON.stringify((function(input){'use strict';\n${definition.source}\n})(JSON.parse(${JSON.stringify(JSON.stringify(input))})))`, context));
  expect(run(request(definition.initialState, { type: "open" }))).toEqual({ state: definition.initialState });
  const action = request(definition.initialState, { type: "action", actionId: "bill_field", value: 200 });
  const reply = run(action);
  expect(reply.state.bill).toBe(200);
  expect(reply).toEqual(handle(action, app));
});
