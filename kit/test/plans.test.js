// The nine saved plans expand, and the kit's report finds no fault in them (the kit's gate is smoke).

import { expect, test } from "bun:test";
import { AppFile } from "../src/dsl.js";
import { expand } from "../src/parts.js";
import { report } from "../src/check.js";
import { plan } from "./helpers.js";

const PLANS = ["calc", "list", "quiz", "tabs-chart", "timer-rounds", "tea", "section", "lesson", "model3d"];

for (const name of PLANS) test(`${name}.plan expands, and its report has no fault`, async () => {
  const file = new AppFile().feedText(plan(name));
  await expand(file);
  expect(report(file).faults).toEqual([]);
});
