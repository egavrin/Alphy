// The plan prompt (kit/prompt.md) against the kit: each kind, each option, and each icon that the prompt names exists,
// and each example plan of the prompt compiles with no fault. So the prompt and the kit cannot differ.

import { expect, test } from "bun:test";
import { ICON_NAMES } from "../src/core.js";
import { HOST_ICONS } from "../src/icons.js";
import { PROMPT as SECTIONS, changeText, repairText } from "../prompt.js";
import { promptAsset } from "../build.js";
import { faultsOf } from "./helpers.js";

const SYSTEM = SECTIONS.System;
/** Each kind of the list "Kinds." as [kind, its paragraph]: the line need NAME = kind(...) and the lines under it. */
const KINDS = SYSTEM.slice(SYSTEM.indexOf("Kinds."), SYSTEM.indexOf("\nFormulas:")).split(/\n(?=need NAME = )/).slice(1)
  .map((paragraph) => [/^need NAME = (\w+)\(/.exec(paragraph)[1], paragraph]);
/** The examples of the prompt as [request, plan]: the lines after "Request:" up to an empty line. */
const EXAMPLES = [...SYSTEM.matchAll(/^Request: (.*)\n((?:.+\n?)+)/gm)].map(([, request, plan]) => [request, plan]);
const optionsIn = (text) => [...new Set([...text.matchAll(/\b([A-Za-z]+)=/g)].map(([, key]) => key))];

test("prompt: the file has the sections System, Repair, Dialogue, and Change", () => {
  expect(Object.keys(SECTIONS)).toEqual(["System", "Repair", "Dialogue", "Change"]);
  for (const text of Object.values(SECTIONS)) expect(text.length).toBeGreaterThan(100);
  expect(KINDS.length).toBe(17);
  expect(EXAMPLES.length).toBe(4);
});

test("prompt: the section System names no hole and no rule that only kept a look option from a fault, and a request that no kind serves gets outside (K1 item 5)", () => {
  // The first loop of the app takes no plan with a section of its own lines, so the prompt names no hole, and the kit still has the kind.
  expect(KINDS.map(([kind]) => kind)).not.toContain("hole");
  expect(SYSTEM).not.toContain("Components:");
  // A look option that cannot apply is a note of the kit now (A0-K4).
  expect(SYSTEM).not.toContain("which takes no icon");
  expect(SYSTEM).not.toContain("never with add=false");
  expect(SYSTEM.split("\n").find((line) => line.includes('outside("other")'))).toContain("a main thing that no kind below gives");
});

test("prompt (K1 fix 4): outside has a sixth word, other, for a main thing that no kind gives, and the section Repair names no component line", () => {
  expect([...SYSTEM.matchAll(/outside\("(\w+)"\)/g)].map(([, word]) => word)).toEqual(["game", "drawing", "3d", "picture", "device", "other"]);
  expect(SECTIONS.Repair).toContain("delete NAME removes a need line, and delete $/NAME removes a state line");
  expect(SECTIONS.Repair).not.toContain("component");
});

test("prompt (task 1): the section Change is one paragraph that names the changed lines, delete NAME, and outside", () => {
  const change = SECTIONS.Change;
  expect(change.split("\n")).toHaveLength(1);
  for (const part of ["delete NAME", 'outside("', 'outside("other")', "Do not rename a need or a field", "Reply with lines only."]) expect([part, change.includes(part)]).toEqual([part, true]);
  expect(changeText({ request: "Add a note", plan: "P\n\n" })).toBe(`Add a note\n\nYour app file:\nP\n\n${change}`);
});

test("prompt.json (task 1): it holds each section of prompt.md and the two templates, and a filled template is the text that the model check sends", () => {
  const asset = promptAsset();
  expect(Object.keys(asset)).toEqual(["system", "repair", "dialogue", "change", "repairTask", "changeTask"]);
  expect([asset.system, asset.repair, asset.dialogue, asset.change]).toEqual([SECTIONS.System, SECTIONS.Repair, SECTIONS.Dialogue, SECTIONS.Change]);
  expect(Object.values(asset).filter((text) => typeof text !== "string" || text.length < 100 || text.includes("undefined"))).toEqual([]);
  for (const part of ["{{task}}", "{{plan}}", "- {{faults}}"]) expect([part, asset.repairTask.includes(part)]).toEqual([part, true]);
  expect([asset.repairTask.endsWith(SECTIONS.Repair), asset.changeTask.endsWith(SECTIONS.Change)]).toEqual([true, true]);
  // The host fills each placeholder one time: the plan with trimEnd, and the faults joined by "\n- ".
  const fill = (template, values) => template.replace(/\{\{(\w+)\}\}/g, (_, key) => values[key]);
  const plan = 'app("T", lang="en")\nneed a = content("a")\n', faults = ["f1", "f2"];
  expect(fill(asset.repairTask, { task: "TASK", plan: plan.trimEnd(), faults: faults.join("\n- ") })).toBe(repairText({ task: "TASK", plan, faults }));
  expect(fill(asset.changeTask, { request: "Add a note", plan: plan.trimEnd() })).toBe(changeText({ request: "Add a note", plan }));
});

test("prompt (K2): the timer paragraph keeps the rest between two rounds and names cycle=true for a cycle of two phases", () => {
  const timer = KINDS.find(([kind]) => kind === "timer")[1];
  expect(timer).toContain("a rest phase of rest seconds is between two rounds");
  expect(timer).toContain("With cycle=true each round is the work phase and then the rest phase, also the last round,");
  expect(timer).toContain("a timer of one round with a rest runs both phases");
  expect(optionsIn(timer)).toContain("cycle");
});

test("prompt: each kind that the prompt names is a part of the kit", async () => {
  for (const [kind] of KINDS) expect((await faultsOf(`need x = ${kind}()`)).filter((fault) => fault.includes("no part for need kind"))).toEqual([]);
});

test("prompt: each option that the prompt names for a kind is an option of its part", async () => {
  let checked = 0;
  for (const [kind, paragraph] of KINDS) {
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

test("prompt: each icon of the list is an icon of Alphy or of the kit", () => {
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
