// The ready plans (kit/README.md, section "Ready plans"): each template is a ready plan with its line table in kit/templates, and the registry
// ready.js finds each one by its word.

import { expect, test } from "bun:test";
import { READY } from "../src/ready.js";
import { script } from "../build.js";

test("each template has its ready plan and its line table", () => {
  expect(Object.keys(READY)).toEqual(["scene", "items", "cells", "compare", "table", "chart", "nodata"]);
  for (const entry of Object.values(READY)) expect([entry.word, typeof entry.plan, Array.isArray(entry.lines)]).toEqual([entry.word, "string", true]);
  expect(READY.scene.lines.some((entry) => entry.name === "s" && entry.count >= 12)).toBe(true);
  expect(READY.cells.lines.some((entry) => entry.name === "s")).toBe(false);
});

// template.js exports BUDGET of form-base.js and TEMPLATE_HEAD of ready.js again, so its importers stay unchanged.
test("bundle: a module exports again the names of a module before it, and the script declares each name once", () => {
  const a = ["a", "export const x = 1;"], text = script([a, ["b", 'export { x } from "./a.js";\nexport const z = 2;'], ["c", 'import { x, z } from "./b.js";\nexport const w = x + z;']]);
  expect(new Function(`${text}return w;`)()).toBe(3);
  expect(() => script([a, ["b", 'export { y } from "./a.js";']])).toThrow("cannot keep this export");
});
