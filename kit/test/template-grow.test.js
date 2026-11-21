// Gate 4 (stage templates plan, Task 21): the picture only grows. Each longer prefix of a saved template file keeps the ui and the drawing of the shorter.

import { expect, test } from "bun:test";
import { existsSync, readFileSync } from "node:fs";
import { basename } from "node:path";
import { fileURLToPath } from "node:url";
import { compileStep, previewStep } from "../src/compile.js";
import { plansIn } from "../build.js";
import "./helpers.js";

/** The saved template files: the examples and the cap files, and the change bases when they exist. */
const FOLDERS = ["template", "template-bases"].map((name) => fileURLToPath(new URL(`./fixtures/${name}`, import.meta.url))).filter((folder) => existsSync(folder));

/**
 * Each place where the ui after does not keep the ui before: a node of before that after has not at its path with its type and its own
 * properties, or children or options of before that are not the start of those of after. New nodes and new options come only after the old ones.
 */
function moved(before, after, path = "ui") {
  if (after?.type !== before.type) return [`${path}: ${before.type} is ${after?.type ?? "gone"}`];
  const { children = [], options = [], ...own } = before, { children: more = [], options: moreOptions = [], ...ownAfter } = after;
  if (JSON.stringify(own) !== JSON.stringify(ownAfter)) return [`${path}: ${JSON.stringify(own)} is ${JSON.stringify(ownAfter)}`];
  if (JSON.stringify(moreOptions.slice(0, options.length)) !== JSON.stringify(options)) return [`${path}: options ${JSON.stringify(options)} are ${JSON.stringify(moreOptions)}`];
  return children.flatMap((child, index) => moved(child, more[index], `${path}/${index}`));
}

test("eachLongerPrefixKeepsTheUiAndTheDrawingOfTheShorter", async () => {
  const files = FOLDERS.flatMap(plansIn);
  expect(files.length).toBeGreaterThanOrEqual(9);
  for (const path of files) {
    const text = readFileSync(path, "utf8"), name = basename(path), lines = text.split("\n").filter((line) => line.trim()), frames = [];
    for (let k = 1; k <= lines.length; k++) {
      const step = await previewStep({ text: lines.slice(0, k).join("\n") + "\n", plan: null, previous: null });
      expect([name, k, step.kind]).toEqual([name, k, "preview"]);
      frames.push(step.frame);
    }
    // The picture card has its size from the first line.
    expect([name, frames[0].definition.surfaces.map(({ width, height }) => [width, height])]).toEqual([name, [[300, 220]]]);
    frames.forEach((frame, k) => expect([name, k + 1, frame.definition.surfaces]).toEqual([name, k + 1, frames[0].definition.surfaces]));
    for (let k = 1; k < frames.length; k++) {
      const [shorter, longer] = [frames[k - 1], frames[k]], at = `${name}, lines ${k} to ${k + 1}`;
      for (const [id, { commands }] of Object.entries(shorter.drawing)) expect([at, id, longer.drawing[id]?.commands.slice(0, commands.length)]).toEqual([at, id, commands]);
      expect([at, moved(shorter.ui, longer.ui)]).toEqual([at, []]);
    }
    const { result } = await compileStep({ text, plan: null, read: true, previous: null, cap: 65536 });
    if (!result.faults.length) expect([name, result.frame.drawing]).toEqual([name, frames.at(-1).drawing]);
  }
});
