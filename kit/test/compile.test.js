// The sandbox entry: compileAppFile from the sources, the built bundle in a context with only the language's globals, and the build's own rules.

import { expect, test } from "bun:test";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createContext, runInContext } from "node:vm";
import { compileAppFile } from "../src/compile.js";
import { bundle, iconModule, planCopies, plansIn, script, withoutMessages } from "../build.js";
import { HOST_ICONS } from "../src/icons.js";
import { emit } from "../src/emit.js";
import { handle } from "../src/run.js";
import { STEPS } from "../src/core.js";
import { plan, planned } from "./helpers.js";

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
  const text = await runInContext(`${await bundle()}\ncompileAppFile(${JSON.stringify(plan("calc"))}).then(JSON.stringify)`, context);
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

test("build: src/icons.js holds the icon names of the host's sources, so the kit and the host cannot differ", () => {
  // bun kit/build.js --check fails when the file differs from what iconModule makes of the host's sources.
  expect(readFileSync(new URL("../src/icons.js", import.meta.url), "utf8")).toBe(iconModule());
  const catalog = 'private val shellIcons=setOf("Menu","Plus",\n        "Trash")\n    val icons=shellIcons+ToolIcons.paths.keys';
  const icons = 'val paths: Map<String, String> = mapOf(\n        "Coffee" to "M10 2v2",\n        "Star" to "M1 1",\n    )';
  expect(iconModule(catalog, icons)).toContain('export const HOST_ICONS = ["Menu","Plus","Trash","Coffee","Star"];');
  expect(() => iconModule("val icons=setOf()", icons)).toThrow("ToolUiCatalog.kt");
  // A shell glyph and a domain icon of the host: the first and the last name of each source.
  expect([HOST_ICONS[0], HOST_ICONS.includes("Refresh"), HOST_ICONS.includes("Coffee"), HOST_ICONS.at(-1)]).toEqual(["Menu", true, true, "Ruler"]);
});

test("build: a module that names MESSAGES keeps its texts out of the run-time only with the line of the build", () => {
  expect(withoutMessages("a", "const MESSAGES = true;\nif (MESSAGES) f();")).toBe("\nif (MESSAGES) f();");
  expect(withoutMessages("a", "f();")).toBe("f();");
  // A line in another form would ship each text of the module with no error.
  expect(() => withoutMessages("a", "const MESSAGES=true;\nif (MESSAGES) f();")).toThrow("kit/src/a.js");
  expect(() => withoutMessages("a", "let MESSAGES = true;")).toThrow("kit/src/a.js");
});

test("build: a copy of a plan whose source is gone is in the outputs with no text", () => {
  const root = mkdtempSync(join(tmpdir(), "kit-build-"));
  try {
    mkdirSync(join(root, "plans"));
    mkdirSync(join(root, "copies"));
    writeFileSync(join(root, "plans", "kept.plan"), "kept\n");
    writeFileSync(join(root, "copies", "kept.plan"), "kept\n");
    writeFileSync(join(root, "copies", "gone.plan"), "gone\n");
    expect(planCopies(plansIn(join(root, "plans")), join(root, "copies"))).toEqual([[join(root, "copies", "kept.plan"), "kept\n"], [join(root, "copies", "gone.plan"), null]]);
  } finally { rmSync(root, { recursive: true }); }
});

const APP = 'app("T", lang="en")', WORK = `: the app needs more than ${STEPS} steps of work`;
/** The hostile plans of item 9 of the final review, each with a part of the fault that names its line and what is too large. */
const HOSTILE = [
  ["a line that shows itself", `${APP}\nroot = Column([root, root])`, "root: children: root shows itself inside itself"],
  // 2 to the 18th nodes, at 37 levels: under the limit of 40 levels.
  ["18 levels of lines that each show the next one two times", [APP, ...Array.from({ length: 18 }, (_, i) => `c${i} = Column([c${i + 1}, c${i + 1}])`), 'c18 = Text("x")', "root = Column([c0])"].join("\n"), ": children: the screen has more than 20000 parts"],
  // The reviewer's two text formulas that grow each other. Each press of the button doubles the text again.
  ["two text formulas that grow each other", `${APP}\n$/n = 0\n$/a := text($/b) + text($/b) + "x"\n$/b := $/a\nb1 = Button("go", inc($/n))\nroot = Column([Text($/a), Text(text($/n)), b1])`, "the formulas $/a and $/b read each other"],
  ["30 formulas that each double a text", [APP, '$/a0 = "xy"', ...Array.from({ length: 30 }, (_, i) => `$/a${i + 1} := $/a${i} + $/a${i}`), "root = Column([Text($/a30)])"].join("\n"), WORK],
  ["30 formulas that each hold the last one two times", [APP, "$/a0 = [1]", ...Array.from({ length: 30 }, (_, i) => `$/a${i + 1} := [$/a${i}, $/a${i}]`), "root = Column([Text(text(len($/a30)))])"].join("\n"), WORK],
  ["a join of joins", `${APP}\n$/r = [${Array.from({ length: 100 }, (_, i) => i).join(", ")}]\n$/s = "${"x".repeat(2000)}"\n$/x := len(join(map($/r, join(map($/r, $/s), "")), ""))\nroot = Column([Text(text($/x))])`, `$/x${WORK}`],
  ["a button that doubles a text", `${APP}\n$/s = "ab"\nb1 = Button("go", set($/s, $/s + $/s))\nroot = Column([Text($/s), b1])`, `smoke run: b1${WORK}`],
  ["shapes in each of 500 in each of 500 in each of 500", `${APP}\nneed d = drawing("D", 320, 240, [each(500, each(500, each(500, circle(1, 1, 1))))])`, "need d: shapes: the drawing has more than 4096 shapes"],
  ["a map in a map in a map in a map of 100 rows", `${APP}\n$/r = [${Array.from({ length: 100 }, (_, i) => i).join(", ")}]\n$/x := len(map($/r, map($/r, map($/r, map($/r, $it)))))\nroot = Column([Text(text($/x))])`, `$/x${WORK}`],
  ["20000 brackets", `${APP}\n$/x := ${"(".repeat(20000)}1${")".repeat(20000)}\nroot = Column([Text(text($/x))])`, "line 2: the line nests more than 100 levels of brackets, signs, calls, or operators. Compute a part of it in a formula of its own: $/x := ((("],
  ["20000 signs", `${APP}\n$/x := ${"-".repeat(20000)}1\nroot = Column([Text(text($/x))])`, "line 2: the line nests more than 100 levels of brackets, signs, calls, or operators. Compute a part of it in a formula of its own: $/x := ---"],
  ["10000 operators", `${APP}\n$/x := 1${" + 1".repeat(10000)}\nroot = Column([Text(text($/x))])`, "line 2: the line nests more than 100 levels of brackets, signs, calls, or operators. Compute a part of it in a formula of its own: $/x := 1 + 1"],
  ["10000 choices", `${APP}\n$/x := ${"1 ? 1 : ".repeat(10000)}1\nroot = Column([Text(text($/x))])`, "line 2: the line nests more than 100 levels of brackets, signs, calls, or operators. Compute a part of it in a formula of its own: $/x := 1 ? 1"],
];

test("compileAppFile: a plan that would hold the memory or the time of the compile step gets a fault that names its line (final review 9)", async () => {
  for (const [name, text, part] of HOSTILE) {
    const started = performance.now(), { faults } = await compileAppFile(text), fast = performance.now() - started < 2000;
    expect({ name, fast, found: faults.some((fault) => fault.includes(part)) }, faults.slice(0, 3).join(" | ")).toEqual({ name, fast: true, found: true });
  }
});

test("run: an event whose work passes the budget of the kit ends in a bounded time (final review 9)", async () => {
  // At the start one row makes the formula cheap. The rows that a person adds make it pass the budget at run time.
  const text = [APP, 'need items = entries("Items", fields={name: "text"}, rows=[{name: "a"}])', "$/x := len(map($/items, map($/items, map($/items, map($/items, $name)))))", "root = Column([items_view, Text(text($/x))])"].join("\n");
  const { definition, faults } = await compileAppFile(text), { app } = emit(await planned(text));
  expect(faults).toEqual([]);
  const items = Array.from({ length: 100 }, (_, index) => ({ id: `r${index + 1}`, name: `n${index}` })), state = { ...definition.initialState, items };
  const started = performance.now(), reply = handle({ artifactId: "kit-test", revision: 1, state, event: { type: "open" } }, app);
  // The event changes nothing: the host keeps the state that it has.
  expect([performance.now() - started < 300, reply]).toEqual([true, { state }]);
  expect(reply.state).toBe(state);
});
