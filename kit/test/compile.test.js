// The sandbox entry: compileAppFile from the sources, and the built bundle in a context with only the language's globals.

import { expect, test } from "bun:test";
import { createContext, runInContext } from "node:vm";
import { compileAppFile } from "../src/compile.js";
import { bundle } from "../build.js";
import { plan } from "./helpers.js";

test("compileAppFile: calc.plan gives no fault and no definition yet", async () => {
  const result = await compileAppFile(plan("calc"));
  expect(result.faults).toEqual([]);
  expect(result.definition).toBeNull();
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
  expect(JSON.parse(text)).toEqual({ faults: [], notes: [], definition: null, language: null });
});
