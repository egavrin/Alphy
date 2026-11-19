// The plan prompt (kit/prompt.md) against the kit: each kind, each option, each icon, and each component that the prompt names exists,
// and each example plan of the prompt compiles with no fault. So the prompt and the kit cannot differ.

import { expect, test } from "bun:test";
import { CATALOG, ICON_NAMES } from "../src/core.js";
import { HOST_ICONS } from "../src/icons.js";
import { PROMPT as SECTIONS } from "../modelcheck.js";
import { faultsOf } from "./helpers.js";

const SYSTEM = SECTIONS.System;
/** Each kind of the list "Kinds." as [kind, its paragraph]: the line need NAME = kind(...) and the lines under it. */
const KINDS = SYSTEM.slice(SYSTEM.indexOf("Kinds."), SYSTEM.indexOf("\nFormulas:")).split(/\n(?=need NAME = )/).slice(1)
  .map((paragraph) => [/^need NAME = (\w+)\(/.exec(paragraph)[1], paragraph]);
/** The examples of the prompt as [request, plan]: the lines after "Request:" up to an empty line. */
const EXAMPLES = [...SYSTEM.matchAll(/^Request: (.*)\n((?:.+\n?)+)/gm)].map(([, request, plan]) => [request, plan]);
const optionsIn = (text) => [...new Set([...text.matchAll(/\b([A-Za-z]+)=/g)].map(([, key]) => key))];

test("prompt: the file has the sections System, Repair, and Dialogue", () => {
  expect(Object.keys(SECTIONS)).toEqual(["System", "Repair", "Dialogue"]);
  for (const text of Object.values(SECTIONS)) expect(text.length).toBeGreaterThan(100);
  expect(KINDS.length).toBe(18);
  expect(EXAMPLES.length).toBe(4);
});

test("prompt: each kind that the prompt names is a part of the kit", async () => {
  for (const [kind] of KINDS) expect((await faultsOf(`need x = ${kind}()`)).filter((fault) => fault.includes("no part for need kind"))).toEqual([]);
});

test("prompt: each option that the prompt names for a kind is an option of its part", async () => {
  let checked = 0;
  for (const [kind, paragraph] of KINDS) {
    // A hole names the options of its components (the next test), and button gives each option to its Button.
    if (kind === "hole") continue;
    // A drawing takes no option: visible= and stroke= of its paragraph are options of a shape.
    if (kind === "drawing") {
      expect(optionsIn(paragraph)).toEqual(["visible", "stroke"]);
      expect(await faultsOf('need d = drawing("D", 100, 100, [circle(50, 50, 10, "ink", visible=true, stroke=true)])')).toEqual([]);
      continue;
    }
    const options = optionsIn(paragraph);
    checked += options.length;
    const faults = kind === "button" ? await faultsOf("$/x = 0", "need shown = content(text($/x))", `need x = button("B", inc($/x), ${options.map((key) => `${key}=${key === "icon" ? '"Plus"' : key === "variant" ? '"filled"' : "true"}`).join(", ")})`)
      : (await faultsOf(`need x = ${kind}(${options.map((key) => `${key}=1`).join(", ")})`)).filter((fault) => /has no option|has no property/.test(fault));
    expect([kind, faults]).toEqual([kind, []]);
  }
  expect(checked).toBeGreaterThan(80);
});

test("prompt: each component of a hole is a component of the kit, and each icon of the list is an icon of Alphy or of the kit", () => {
  const hole = KINDS.find(([kind]) => kind === "hole")[1];
  const components = [...hole.slice(hole.indexOf("Components:")).split("\n")[0].matchAll(/\b([A-Z]\w+)\(/g)].map(([, name]) => name);
  expect(components.length).toBeGreaterThan(20);
  expect(components.filter((name) => !Object.hasOwn(CATALOG, name))).toEqual([]);
  const icons = /An icon is one of these names: (.*)\./.exec(SYSTEM)[1].split(", ");
  expect(icons.length).toBeGreaterThan(150);
  expect(icons.filter((name) => !HOST_ICONS.includes(name) && !Object.hasOwn(ICON_NAMES, name))).toEqual([]);
});

for (const [index, [request, plan]] of EXAMPLES.entries()) test(`prompt: example ${index + 1} compiles with no fault (${request.slice(0, 40)})`, async () => {
  const { compileAppFile } = await import("../src/compile.js");
  const result = await compileAppFile(plan);
  expect(result.faults).toEqual([]);
  expect(result.definition).not.toBeNull();
});
