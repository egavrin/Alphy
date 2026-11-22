// The template forms (one scene plan, Task 2): the code of each template form is in a module of its own, and forms.js finds each one by its word.

import { expect, test } from "bun:test";
import { FORMS_BY_WORD } from "../src/forms.js";
import { script } from "../build.js";

test("each form has its own module", () => {
  expect(Object.keys(FORMS_BY_WORD)).toEqual(["scene", "items", "cells"]);
  expect(FORMS_BY_WORD.scene.part.test("s12")).toBe(true);
  expect(FORMS_BY_WORD.cells.part.test("s1")).toBe(false);
});

// template.js exports BUDGET and TEMPLATE_HEAD of form-base.js again, so its importers stay unchanged.
test("bundle: a module exports again the names of a module before it, and the script declares each name once", () => {
  const a = ["a", "export const x = 1;"], text = script([a, ["b", 'export { x } from "./a.js";\nexport const z = 2;'], ["c", 'import { x, z } from "./b.js";\nexport const w = x + z;']]);
  expect(new Function(`${text}return w;`)()).toBe(3);
  expect(() => script([a, ["b", 'export { y } from "./a.js";']])).toThrow("cannot keep this export");
});
