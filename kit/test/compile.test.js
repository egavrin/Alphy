// The sandbox entry: compileAppFile from the sources, the built bundle in a context with only the language's globals, and the build's own rules.

import { expect, test } from "bun:test";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createContext, runInContext } from "node:vm";
import { compileAppFile } from "../src/compile.js";
import { bundle, planCopies, script } from "../build.js";
import { plan } from "./helpers.js";

test("compileAppFile: calc.plan gives a definition and no fault", async () => {
  const result = await compileAppFile(plan("calc"));
  expect(result.faults).toEqual([]);
  expect(result.definition.title).toBe("Tip split");
  expect(result.language).toBe("en");
});

test("compileAppFile: a need with no part gives one fault that names its line", async () => {
  const { faults } = await compileAppFile("need x = nothing(1)");
  expect(faults.filter((fault) => fault.includes("need x = nothing(1)"))).toHaveLength(1);
});

test("compileAppFile: a throw inside the kit is the one fault kit: <message>", async () => {
  // A check line that is not a call makes parseLine throw a TypeError, which AppFile.feed does not catch.
  const { faults } = await compileAppFile('check("a") + 1');
  expect(faults).toHaveLength(1);
  expect(faults[0]).toStartWith("kit: ");
});

test("bundle: it defines only compileAppFile, and the sandbox expression compiles calc.plan through it", async () => {
  // The sandbox has no structuredClone and no host API: a fresh context of the language is closer to it than Bun's own.
  const context = createContext();
  const names = () => [...runInContext("Object.getOwnPropertyNames(globalThis)", context)];
  const before = names();
  const text = await runInContext(`${bundle()}\ncompileAppFile(${JSON.stringify(plan("calc"))}).then(JSON.stringify)`, context);
  expect(names().filter((name) => !before.includes(name))).toEqual(["compileAppFile"]);
  expect(JSON.parse(text)).toEqual(JSON.parse(JSON.stringify(await compileAppFile(plan("calc")))));
});

test("bundle: an import that the build cannot keep is an error of the build", () => {
  const a = ["a", "export const x = 1;\nexport function y() { return 2; }"];
  expect(script([a, ["b", 'import { x, y } from "./a.js";\nexport const z = x + y();']])).toContain("const { z } = ");
  expect(() => script([a, ["b", 'import { x as w } from "./a.js";']])).toThrow("cannot keep this import");
  expect(() => script([a, ["b", 'import { w } from "./a.js";']])).toThrow("cannot keep this import");
  expect(() => script([a, ["b", 'import { x } from "./c.js";']])).toThrow("cannot keep this import");
  expect(() => script([["b", 'import { x } from "./a.js";'], a])).toThrow("cannot keep this import");
});

test("bundle: an export line with two names is an error of the build", () => {
  expect(() => script([["a", "export const x = 1, y = 2;"]])).toThrow("one name");
});

test("build: a copy of a plan whose source is gone is in the outputs with no text", () => {
  const root = mkdtempSync(join(tmpdir(), "kit-build-"));
  try {
    mkdirSync(join(root, "plans"));
    mkdirSync(join(root, "copies"));
    writeFileSync(join(root, "plans", "kept.plan"), "kept\n");
    writeFileSync(join(root, "copies", "kept.plan"), "kept\n");
    writeFileSync(join(root, "copies", "gone.plan"), "gone\n");
    expect(planCopies(join(root, "plans"), join(root, "copies"))).toEqual([[join(root, "copies", "kept.plan"), "kept\n"], [join(root, "copies", "gone.plan"), null]]);
  } finally { rmSync(root, { recursive: true }); }
});
