// What the ported tests of the prototype's test.js share.

import { afterEach, beforeEach, expect } from "bun:test";
import { readFileSync } from "node:fs";
import { basename } from "node:path";
import { fileURLToPath } from "node:url";
import { AppFile } from "../src/dsl.js";
import { expand } from "../src/parts.js";
import { settings } from "../src/core.js";
import { report } from "../src/check.js";
import { runtime } from "../src/emit.js";
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

export const planned = async (text) => { const file = new AppFile().feedText(text); await expand(file); return file; };
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
