// What the ported tests of the prototype's test.js share.

import { afterEach, beforeEach, expect } from "bun:test";
import { readFileSync } from "node:fs";
import { basename } from "node:path";
import { fileURLToPath } from "node:url";
import { AppFile } from "../src/dsl.js";
import { expand } from "../src/parts.js";
import { App, settings } from "../src/core.js";
import { report } from "../src/check.js";
import { emit, runtime } from "../src/emit.js";
import { handle } from "../src/run.js";
import { compileAppFile } from "../src/compile.js";
import { plansIn, runtimeText } from "../build.js";

// The bundle gives emit.js the text of the run-time (kit/build.js). A test of the sources gives it the same text from the sources of now.
runtime.text = await runtimeText();

// The prototype compared values as JSON text: the order of keys counts, and undefined in a list is null. The port keeps that.
export const same = (actual, expected, message) => expect(JSON.stringify(actual), message).toBe(JSON.stringify(expected));
export const assert = (ok, message) => expect(Boolean(ok), message).toBe(true);

/** A saved plan of the kit. */
export const plan = (name) => readFileSync(new URL(`../plans/${name}.plan`, import.meta.url), "utf8");
/** A plan of the prototype's examples that only the tests read. */
export const fixture = (name) => readFileSync(new URL(`./fixtures/${name}.plan`, import.meta.url), "utf8");
/** Each saved plan, test plan, and fixture of the kit as [its path in kit/, its text]. */
export const allPlans = () => ["plans", "test", "test/fixtures"].flatMap((dir) => plansIn(fileURLToPath(new URL(`../${dir}`, import.meta.url)))
  .map((path) => [`${dir}/${basename(path)}`, readFileSync(path, "utf8")]));

const event = (type) => (state, app, actionId, value = null) => handle({ artifactId: "kit-test", revision: 1, state, event: { type, actionId, value } }, app);
/** One action event of Alphy on a state: the value of a field with no live input (a choice, a number field), or a press when the value is null. */
export const press = event("action");
/** The value of a live control (a TextField or a Slider) while the person types or drags: the host sends it as a liveInput event. */
export const live = event("liveInput");
/** The value at a path of an Alphy state, such as "prodotti_s.new_nome" or "d.size_text". */
export const valueAt = (state, path) => path.split(".").reduce((value, key) => value?.[key], state);
export const planned = async (text) => { const file = new AppFile().feedText(text); await expand(file); return file; };
/** The faults of the compile step for the lines of a plan, after its header. */
export const faultsOf = async (...lines) => (await compileAppFile(['app("T", lang="en")', ...lines].join("\n"))).faults;
/** The definition and the compiled app of the plan of these lines, which the emitter builds with no fault. */
export const built = async (...lines) => {
  const result = emit(await planned(lines.join("\n")));
  expect(result.faults).toEqual([]);
  return result;
};
/** Each node of a ui, also the nodes of each row template. */
export const every = (node) => [node, ...(node.children ?? []).flatMap(every), ...(node.rowTemplate ? every(node.rowTemplate) : [])];

/** A time of the host's clock in ms. Each event of a timed test gives its time to handle. */
export const T0 = 1_800_000_000_000;
/**
 * A plan as the run-time and as the kit's App, whose state gives the expected values. send gives one event to both at a time in
 * ms after T0, each on the state of the reply before it: a field value, a press when the value is null, or a timer event (a look at the clock for the kit).
 */
export const clocked = async (text) => {
  const file = await planned(text), { definition, app } = emit(file), kit = new App(file);
  let state = definition.initialState;
  const send = (ms, event) => {
    settings.now = () => T0 + ms;
    if (event.type === "timer") kit.tick(); else if (event.value === null) kit.press(event.actionId); else kit.input(event.actionId, event.value);
    const reply = handle({ artifactId: "kit-test", revision: 1, state, event }, app, T0 + ms);
    state = reply.state;
    return reply;
  };
  return { kit, send, app, definition, get state() { return state; } };
};
export const has = (file, part) => report(file).faults.some((fault) => fault.includes(part));
/**
 * A fake of the API call of the model check (kit/modelcheck.js): each call takes the next reply of the list, and the bodies stay for the test.
 * A null reply is a call that the model did not complete.
 */
export const fake = (...texts) => {
  const bodies = [];
  const ask = async (body) => { bodies.push(body); const text = texts[bodies.length - 1]; return text === null ? { status: "incomplete", text: "", wallS: 1 } : { status: "completed", text, wallS: 2, firstTextS: 1, usage: { output_tokens: 10 } }; };
  return { ask, bodies };
};

const clock = settings.now;
/**
 * Each test starts with the gate that the prototype had at its lines: the prototype ran with the gate of checks, and the kit has smoke.
 * After each test the kit's gate and the real clock come back, also after a failure.
 */
export function gateEach(gate) {
  beforeEach(() => { settings.gate = gate; });
  afterEach(() => { settings.gate = "smoke"; settings.now = clock; });
}
