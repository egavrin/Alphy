// The sandbox entry: compileAppFile from the sources, the built bundle in a context with only the language's globals, and the build's own rules.

import { expect, test } from "bun:test";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createContext, runInContext } from "node:vm";
import { compileAppFile, patchAppFile } from "../src/compile.js";
import { AppFile } from "../src/dsl.js";
import { expand } from "../src/parts.js";
import { bundle, iconModule, planCopies, plansIn, script, withoutMessages } from "../build.js";
import { HOST_ICONS } from "../src/icons.js";
import { emit } from "../src/emit.js";
import { handle } from "../src/run.js";
import { STEPS } from "../src/core.js";
import { faultsOf, plan, planned } from "./helpers.js";

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
  // The host gives a text. Each line of a text gets a fault of its line (final review 12), so only a value that is no text makes the kit throw.
  const { faults } = await compileAppFile(null);
  expect(faults).toHaveLength(1);
  expect(faults[0]).toStartWith("kit: ");
});

test("compileAppFile: a fault of a line that a part wrote names its need, and no line gives a fault kit: (final review 12)", async () => {
  const cases = [
    // The smoke run names a control of a part line by its caption in its need.
    [['need n = counter("N", 0, min=0, max=0)'], ['smoke run: the Button "Less" of need n never changes a value', 'smoke run: the Button "More" of need n never changes a value']],
    // A path that no line sets, read by a need and by the formula of its part, or by a line NAME_text of its part.
    [['need c = figure("number", "C", $/k * 2)'], ["need c reads $/k, and no line sets it", "need c: $/c is not a finite number at the start"]],
    [['need q = question($/k, right="a", options=["a", "b"])'], ["need q reads $/k, and no line sets it"]],
    // A line that does not have the form of its kind.
    [['check("a") + 1'], ['line 2: a check line is one call of check, such as check("name", [steps]): check("a") + 1']],
    [["need x = entries"], ['line 2: a need line is one call of a part, such as need x = entries("Items"): need x = entries']],
  ];
  for (const [lines, parts] of cases) {
    const faults = await faultsOf(...lines), text = faults.join(" | ");
    const lost = parts.filter((part) => !faults.some((fault) => fault.includes(part)));
    // A name that a part wrote, such as n_minus, c_text, or $/c alone, and a throw of the kit.
    const stray = faults.filter((fault) => /\b(n_minus|n_plus|q_text)\b|^kit: |^[cq] reads|^\$\/c /.test(fault));
    expect({ lines, lost, stray }, text).toEqual({ lines, lost: [], stray: [] });
  }
  // The app line has its own form too.
  expect((await compileAppFile('app("T", lang="en") + 1\nroot = Column([Text("a")])')).faults).toEqual(['line 1: an app line is one call of app, such as app("Title", lang="en"): app("T", lang="en") + 1']);
});

test('"no root line" as a second fault (A0-K2): the fault of a need, of a need line, or of the root line explains a screen with no root, and a plan with no need still gets the fault', async () => {
  // A need whose part fails writes no line. Its fault is the cause, so the model does not write root by hand (A0-K2, alarm of r0-sol-medium).
  const id = 'need things = entries("Things", fields={id: "text"})';
  expect(await faultsOf(id)).toEqual([`line: need things: a field named id is the key of each row, which the host gives. Give the field a different name: ${id}`]);
  // A need line or a root line that the kit cannot read is the cause too.
  expect(await faultsOf("need x = figure(")).toEqual(["line 2: value expected at the end of the line: need x = figure("]);
  expect(await faultsOf("root = Column([")).toEqual(["line 2: value expected at the end of the line: root = Column(["]);
  // A plan with no need has no screen, also beside the fault of a line that is not a need or root.
  expect(await faultsOf("$/n = 1")).toEqual(["no root line"]);
  expect(await faultsOf("$/n = (")).toEqual(["line 2: value expected at the end of the line: $/n = (", "no root line"]);
  // A need that shows nothing, such as a hidden list, explains nothing.
  expect(await faultsOf('need items = entries("Items", hidden=true)')).toEqual(["no root line"]);
});

test("compileAppFile: a field of a list with the name of a line of the list part is a fault of the need (final review 13)", async () => {
  // fields= and show= of an entries need name the lines NAME_FIELD of its part, so a field must not have the name of another line of the part.
  for (const field of ["row", "list", "view", "main", "add", "remove", "hero", "emoji", "up", "down", "open", "addRow", "confirm", "yes", "no"]) {
    for (const need of [`fields={name: "text", ${field}: "text"}`, `fields={name: "text"}, show={${field}: $name}`]) {
      const faults = await faultsOf(`need shop = entries("Shop", ${need}, icon="🛒", confirm="Sure?", reorder=true)`);
      const found = faults.some((fault) => fault.includes(`need shop: a field named ${field} has the name of the line shop_${field} of the list part`));
      expect({ field, need, found }, faults.join(" | ")).toEqual({ field, need, found: true });
    }
  }
  expect(await faultsOf('need shop = entries("Shop", fields={name: "text", rowing: "text"})', "root = Column([shop_view])")).toEqual([]);
});

/** The saved replies of the model check that the tests replay (fixtures/replies.json): each reply of a record, by the label of its run and its request. */
const REPLIES = JSON.parse(readFileSync(new URL("./fixtures/replies.json", import.meta.url), "utf8"));
/** The plan of each step of a record: the first reply, then each repair reply patched into the plan before it, as the model check does. */
const stepsOf = (replies) => replies.reduce((plans, reply) => [...plans, plans.length ? patchAppFile(plans.at(-1), reply) : reply], []);
const NEED = /^need\s+([\p{L}_][\p{L}\p{N}_]*)\s*=/u;
const needsIn = (text) => text.split("\n").map((line) => NEED.exec(line)?.[1]).filter(Boolean);

test("patchAppFile (A0-K1): a repair reply that deletes a name and writes it again gives the plan that a person gets by hand", async () => {
  const plan = 'app("T", lang="en")\n$/days = []\nneed note = content("a")\nneed old = content("b")\n';
  const reply = 'delete $/days\nneed days = entries("Days", fields={day: "date"}, rows=[])\nneed note = content("c")\ndelete old\ndelete nothing\n';
  // Each delete removes its line, a line with the name of an earlier line replaces it in place, and each new line goes at the end.
  // A delete of a name that no line has goes too: a person cannot apply it.
  const patched = patchAppFile(plan, reply);
  expect(patched).toBe('app("T", lang="en")\nneed note = content("c")\nneed days = entries("Days", fields={day: "date"}, rows=[])\n');
  // The name that the delete freed is free for the part of the later need, so the part writes $/days.
  expect((await compileAppFile(patched)).faults).toEqual([]);
  // Prose and markdown fences of the reply go. A reply line that the kit cannot read stays at the end, so that the compile names it.
  expect(patchAppFile(plan, `Here is the fix:\n\`\`\`\nneed note = content("c")\nneed broken = figure(\n\`\`\``))
    .toBe('app("T", lang="en")\n$/days = []\nneed note = content("c")\nneed old = content("b")\nneed broken = figure(\n');
});

test("patchAppFile (A0-K1): the saved repairs of pomodoro-zh (final-terra-low) and alarm (r0-sol-medium) delete a line and write it again, and are clean after the second repair", async () => {
  for (const key of ["final-terra-low/showcase-lang-pomodoro-zh", "r0-sol-medium/showcase-ui-alarm"]) {
    const plans = stepsOf(REPLIES[key].slice(0, 3));
    expect({ key, faults: (await compileAppFile(plans[2])).faults }).toEqual({ key, faults: [] });
  }
});

test("patchAppFile (A0-K1): each of the 41 saved repairs that writes a need of its plan again keeps one line of that need, and its part writes each of its lines", async () => {
  let hits = 0;
  for (const [key, replies] of Object.entries(REPLIES)) {
    const plans = stepsOf(replies);
    for (let step = 1; step < plans.length; step++) {
      // The needs of the plan before, as the kit reads it: a line that the kit could not read is no need.
      const before = new AppFile().feedText(plans[step - 1]).of("need").map((entry) => entry.key), file = new AppFile().feedText(plans[step]);
      expand(file);
      for (const name of new Set(needsIn(replies[step]).filter((name) => before.includes(name)))) {
        hits++;
        const lines = plans[step].split("\n").filter((line) => NEED.exec(line)?.[1] === name), written = replies[step].split("\n").findLast((line) => NEED.exec(line)?.[1] === name).trim();
        expect({ key, step, name, lines, lost: (file.partLines.get(name) ?? []).filter((line) => !file.get(line)) }).toEqual({ key, step, name, lines: [written], lost: [] });
      }
    }
  }
  expect(hits).toBe(41);
});

test("bundle: it defines only compileAppFile and patchAppFile, and the sandbox expression compiles calc.plan and patches a plan through them", async () => {
  // The sandbox has no structuredClone and no host API: a fresh context of the language is closer to it than Bun's own.
  const context = createContext();
  const names = () => [...runInContext("Object.getOwnPropertyNames(globalThis)", context)];
  const before = names();
  const text = await runInContext(`${await bundle()}\ncompileAppFile(${JSON.stringify(plan("calc"))}).then(JSON.stringify)`, context);
  expect(names().filter((name) => !before.includes(name))).toEqual(["compileAppFile", "patchAppFile"]);
  expect(JSON.parse(text)).toEqual(JSON.parse(JSON.stringify(await compileAppFile(plan("calc")))));
  const reply = 'need tip = input("percent", "Tip", 20)\ndelete people';
  expect(runInContext(`patchAppFile(${JSON.stringify(plan("calc"))}, ${JSON.stringify(reply)})`, context)).toBe(patchAppFile(plan("calc"), reply));
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

test("compileAppFile: __proto__, constructor, and prototype as a name, a path part, or a field are a fault of the line (final review, Minor)", async () => {
  const cases = [
    ["$/__proto__/polluted = 1"], ['need __proto__ = input("number", "N", 1)'], ["$/x := constructor([1])"], ["x = constructor()"],
    ["$/x = 1", "b = Button(\"go\", set($/__proto__/polluted, 7))"], ["$/rows = [{constructor: 1}]"], ['$/m = {"prototype": 1}'],
    ['need list = entries("L", fields={constructor: "text"})'], ["$/a = {b: 1}", "$/x := $/a.constructor"],
  ];
  for (const lines of cases) {
    const faults = await faultsOf(...lines), found = faults.some((fault) => /^line \d+: .*(__proto__|constructor|prototype) is not a name that a plan can use/.test(fault));
    expect({ lines, found, polluted: {}.polluted }, faults.join(" | ")).toEqual({ lines, found: true, polluted: undefined });
  }
});

test("compileAppFile: a member of Object.prototype, such as toString, is no function, component, or field of the kit (final review, Minor)", async () => {
  expect(await faultsOf("$/x := toString([1])", "root = Column([Text(text($/x))])")).toEqual(["$/x: unknown function toString"]);
  expect(await faultsOf("x = toString()", "root = Column([x])")).toEqual(["x: unknown component toString"]);
  // A field that the row or the record does not have: a member of Object.prototype is no field of it.
  const { faults, definition } = await compileAppFile([APP, "$/rows = [{b: 1}]", "$/x := text($/rows[0].toString)", "root = Column([Text($/x)])"].join("\n"));
  expect([faults, definition.initialState.d.x]).toEqual([[], ""]);
  expect((await faultsOf("$/rows = [{b: 1}]", "root = Column([List($/rows, Text(text($toString)))])")).some((fault) => fault.includes("the row has no field toString"))).toBe(true);
});

/** The start state of the plan of these lines, after its header, and its faults. */
const started = async (...lines) => { const { faults, definition } = await compileAppFile([APP, ...lines].join("\n")); return { faults, state: JSON.stringify(definition?.initialState ?? null) }; };

test("compileAppFile: a time after 23:59 is no time for hours, as for a time field (final review, Minor)", async () => {
  expect(await started('$/x := hours("25:00", "10:00")', "root = Column([Text(text($/x))])")).toEqual({ faults: [], state: '{"d":{"root_0_text":"0"}}' });
});

test("compileAppFile: a number that the kit prints in exponent form reads back (final review, Minor)", async () => {
  expect(await started("$/x = 2.5e-7", "$/y := $/x * 1e+7", "root = Column([Text(text($/y))])")).toEqual({ faults: [], state: '{"x":2.5e-7,"d":{"root_0_text":"2.5"}}' });
});

test("compileAppFile: a part id of a 3D model that is not a word still gives its chip (final review, Minor)", async () => {
  const { faults, definition } = await compileAppFile([APP, 'need m = model3d("M", [box("wheel-l", 0, 0, 0, 1, 1, 1, "ink"), box("", 0, 1, 0, 1, 1, 1, "ink")], parts={"wheel-l": "Wheel"})'].join("\n"));
  expect([faults, JSON.stringify(definition?.ui ?? null).includes('"text":"Wheel"')]).toEqual([[], true]);
});

test("compileAppFile: a list field that is not a word, an input of an unknown kind, and a color that the kit does not have are each a fault of the need (final review, Minor)", async () => {
  const cases = [
    [['need shop = entries("Shop", fields={"my field": "text"})'], 'need shop: a field named "my field" is not a word'],
    [['need s = input("slider", "S", 1)'], "need s: an input of kind slider is not built. Use one of the kinds number, money, percent, count, range, choice, choices, toggle, text, date, time"],
    [['need d = drawing("D", 100, 100, [circle(1, 1, 1, "blu")])'], 'need d: shapes: the color "blu" of circle is not a color of the kit. Use one of ink, muted, accent'],
  ];
  for (const [lines, part] of cases) {
    const faults = await faultsOf(...lines);
    expect({ lines, found: faults.some((fault) => fault.includes(part)) }, faults.join(" | ")).toEqual({ lines, found: true });
  }
});

test("a deck over an empty list inside tabs (A0-K3): the saved plan of words-ko (r0-terra-low, step 0) compiles clean", async () => {
  expect((await compileAppFile(REPLIES["r0-terra-low/holdout-words-ko"][0])).faults).toEqual([]);
});

test("a deck over an empty list inside tabs (A0-K3): a deck with a back or an answer over a list that starts empty compiles clean in tabs and in steps, as with no holder", async () => {
  const list = 'need words = entries("Words", fields={word: "text", meaning: "text"}, rows=[])';
  for (const deck of ["back=$meaning", "answer=$meaning"]) for (const holder of ["", 'need pages = tabs(["Words", "Quiz"], [[words], [quiz]])', 'need pages = steps(["Words", "Quiz"], [[words], [quiz]])']) {
    const faults = await faultsOf(list, `need quiz = deck("Quiz", $/words, front=$word, ${deck})`, holder);
    expect({ deck, holder, faults }).toEqual({ deck, holder, faults: [] });
  }
});

test("smoke: the run starts at the clock of the compile step, so a control that shows only after a date comes to its screen (final review, Minor)", async () => {
  expect(await faultsOf("$/n = 0", 'b = Button("go", inc($/n), visible=year(today()) > 2000)', "root = Column([Text(text($/n)), b])")).toEqual([]);
});
