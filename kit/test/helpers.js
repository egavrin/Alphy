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

/** One event of Alphy on a state: a field value, or a press when the value is null. */
export const press = (state, app, actionId, value = null) => handle({ artifactId: "kit-test", revision: 1, state, event: { type: "action", actionId, value } }, app);
export const planned = async (text) => { const file = new AppFile().feedText(text); await expand(file); return file; };
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

const clock = settings.now;
/**
 * Each test starts with the gate that the prototype had at its lines: the prototype ran with the gate of checks, and the kit has smoke.
 * After each test the kit's gate and the real clock come back, also after a failure.
 */
export function gateEach(gate) {
  beforeEach(() => { settings.gate = gate; });
  afterEach(() => { settings.gate = "smoke"; settings.now = clock; });
}
