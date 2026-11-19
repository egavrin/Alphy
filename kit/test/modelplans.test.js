// The plans that a model wrote in the model check (test/fixtures/model): the final plan of each clean record of the runs
// final-terra-low and fresh-terra-low. index.json holds the request and the run of each one. Each plan compiles with no fault.

import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { basename } from "node:path";
import { fileURLToPath } from "node:url";
import { compileAppFile } from "../src/compile.js";
import { plansIn } from "../build.js";
// helpers.js gives the emitter the text of the run-time.
import "./helpers.js";

const directory = fileURLToPath(new URL("./fixtures/model", import.meta.url));
const index = JSON.parse(readFileSync(`${directory}/index.json`, "utf8"));
const plans = plansIn(directory);

test("model plans: the index holds each fixture of the sets C, D, and E with its request and run", () => {
  expect(plans.map((path) => basename(path, ".plan")).sort()).toEqual(Object.keys(index).sort());
  const sets = Object.values(index).map((entry) => entry.set);
  expect(["C", "D", "E"].map((set) => sets.filter((one) => one === set).length)).toEqual([24, 8, 20]);
  for (const [id, entry] of Object.entries(index)) {
    expect(Object.keys(entry), id).toEqual(["request", "dialogue", "set", "model", "effort", "prompt", "label"]);
    expect(entry.request.length, id).toBeGreaterThan(0);
    expect(typeof entry.dialogue, id).toBe("boolean");
  }
});

for (const path of plans) test(`model plans: ${basename(path)} compiles with no fault`, async () => {
  const result = await compileAppFile(readFileSync(path, "utf8"));
  expect(result.faults).toEqual([]);
  expect(result.definition).not.toBeNull();
});
