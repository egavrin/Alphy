// The plan prompt (kit/prompt.md) against the kit: each kind, each option, and each icon that the prompt names exists,
// and each example plan of the prompt compiles with no fault. So the prompt and the kit cannot differ.

import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { createContext, runInContext } from "node:vm";
import { ICON_NAMES } from "../src/core.js";
import { HOST_ICONS } from "../src/icons.js";
import { BUDGET, TEMPLATE_HEAD } from "../src/template.js";
import { compileStep } from "../src/compile.js";
import * as prompts from "../prompt.js";
import { PROMPT as SECTIONS, changeText, repairText } from "../prompt.js";
import { bundle, promptAsset } from "../build.js";
import { every, faultsOf, valueAt } from "./helpers.js";

const SYSTEM = SECTIONS.System;
/** Each kind of the list "Kinds." as [kind, its paragraph]: the line need NAME = kind(...) and the lines under it. */
const KINDS = SYSTEM.slice(SYSTEM.indexOf("Kinds."), SYSTEM.indexOf("\nFormulas:")).split(/\n(?=need NAME = )/).slice(1)
  .map((paragraph) => [/^need NAME = (\w+)\(/.exec(paragraph)[1], paragraph]);
/** The examples of the prompt as [request, plan]: the lines after "Request:" up to an empty line. */
const EXAMPLES = [...SYSTEM.matchAll(/^Request: (.*)\n((?:.+\n?)+)/gm)].map(([, request, plan]) => [request, plan]);
const optionsIn = (text) => [...new Set([...text.matchAll(/\b([A-Za-z]+)=/g)].map(([, key]) => key))];
/** A task text of prompt.json with each placeholder filled one time, as the host fills it. */
const fill = (template, values) => template.replace(/\{\{(\w+)\}\}/g, (_, key) => values[key]);

test("prompt: the file has the sections System, Repair, Dialogue, Change, Template, Template repair, Template change, Template rest, and Template data", () => {
  expect(Object.keys(SECTIONS)).toEqual(["System", "Repair", "Dialogue", "Change", "Template", "Template repair", "Template change", "Template rest", "Template data"]);
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
  expect(Object.keys(asset)).toEqual(["system", "repair", "dialogue", "change", "repairTask", "changeTask", "template", "templateRepairTask", "templateChangeTask", "templateRestTask", "templateHead", "templateData", "dataHead"]);
  expect([asset.system, asset.repair, asset.dialogue, asset.change, asset.template]).toEqual([SECTIONS.System, SECTIONS.Repair, SECTIONS.Dialogue, SECTIONS.Change, SECTIONS.Template]);
  // templateHead is the source of the pattern of a first line, not a text of the prompt, so it is short.
  const { templateHead, dataHead, ...texts } = asset;
  expect(templateHead).toBe(TEMPLATE_HEAD.source);
  expect(new RegExp(dataHead).test('chart("T", lang="en")') && !new RegExp(dataHead).test('scene("T", lang="en")')).toBe(true);
  expect(Object.values(texts).filter((text) => typeof text !== "string" || text.length < 100 || text.includes("undefined"))).toEqual([]);
  for (const part of ["{{task}}", "{{plan}}", "- {{faults}}"]) expect([part, asset.repairTask.includes(part)]).toEqual([part, true]);
  expect([asset.repairTask.endsWith(SECTIONS.Repair), asset.changeTask.endsWith(SECTIONS.Change)]).toEqual([true, true]);
  // The host fills each placeholder one time: the plan with trimEnd, and the faults joined by "\n- ".
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

test("prompt (P3): the entries paragraph names the count of check fields that puts each label over its mark, and its sort by a choice compiles", async () => {
  const entries = KINDS.find(([kind]) => kind === "entries")[1], sortBy = 'sort=$/by == "aisle" ? $aisle : $name';
  expect([entries.includes("more than two check fields"), entries.includes(sortBy)]).toEqual([true, true]);
  const { compileAppFile } = await import("../src/compile.js");
  const circles = async (count) => JSON.stringify((await compileAppFile(['app("T", lang="en")', `need days = entries("Days", fields={name: "text", ${["a", "b", "c"].slice(0, count).map((field) => `${field}: "check"`).join(", ")}})`].join("\n"))).definition).includes('"variant":"circle"');
  expect([await circles(2), await circles(3)]).toEqual([false, true]);
  expect(await faultsOf('need by = input("choice", "Order by", "aisle", options=["aisle", "name"])', `need items = entries("Items", fields={name: "text", aisle: "text"}, ${sortBy})`)).toEqual([]);
});

test("prompt (P4): the section System says what a request implies, and each form that it names compiles and gives what it says", async () => {
  const { compileAppFile } = await import("../src/compile.js"), { settings } = await import("../src/core.js");
  const entries = KINDS.find(([kind]) => kind === "entries")[1];
  for (const part of ["Plan what the request asks for, and nothing more", "What a request implies is a part of what it asks for.", 'such as "top five" or "up to three", is max= of the entries',
    "The first screen of an app must not be empty"]) expect([part, SYSTEM.includes(part)]).toEqual([part, true]);
  expect(optionsIn(entries)).toContain("max");
  const shown = (result, name) => {
    expect(result.faults).toEqual([]);
    return valueAt(result.definition.initialState, every(result.definition.ui).find((node) => node.bind?.includes(name) || node.text === name)?.bind);
  };
  // The total of a budget is the main figure, the sum of each group is one line of text in the form of the prompt, and the first tab holds the chart with the total.
  // The sums are joined by " · ", because ", " beside a decimal comma hides where a sum ends, as in "Comida: 19,75, Casa: 300,00" (task P4b).
  const line = /content\(join\(map\(groups\(.*?\), " · "\)\)/.exec(SYSTEM)?.[0];
  const budget = await compileAppFile(['app("Costs", lang="en")', 'need total = figure("money", "This month", sum($/costs, $amount), example=[{costs: [{amount: 1}, {amount: 2}]}, 3])',
    'need chart = chart("By category", groups($/costs, $category, $amount), $key, $value)', `need byCategory = ${line}`,
    'need costs = entries("Costs", fields={category: ["Food", "Home"], amount: "money"}, rows=[{category: "Food", amount: 1.1}, {category: "Food", amount: 2.2}, {category: "Home", amount: 5}])',
    'need pages = tabs(["Summary", "Add"], [[total, chart, byCategory], [costs]])'].join("\n"));
  expect([typeof line, shown(budget, "byCategory")]).toEqual(["string", "Food: 3.3 · Home: 5"]);
  expect(every(budget.definition.ui).find((node) => node.children?.some((child) => child.type === "chart")).children.map((node) => [node.type, node.variant])).toEqual([["card", "hero"], ["chart", undefined], ["text", undefined]]);
  // A number that limits a list: the hero count shows the rows against max=.
  expect(shown(await compileAppFile('app("Top", lang="en")\nneed tasks = entries("Top five", fields={task: "text"}, reorder=true, max=5, countText="Ranked")'), "Ranked")).toBe("0 / 5");
  // A weekend starts on the coming Saturday, and the end of a range one day later, on each day of a week.
  const saturday = /addDays\(today\(\), \(13 - weekday\(today\(\)\)\) % 7\)/.exec(SYSTEM)[0], end = /addDays\(\$\/start, 1\)/.exec(SYSTEM)[0], now = settings.now;
  try {
    for (const [today, start, last] of [["2026-11-16", "2026-11-21", "2026-11-22"], ["2026-11-20", "2026-11-21", "2026-11-22"], ["2026-11-21", "2026-11-21", "2026-11-22"], ["2026-11-22", "2026-11-28", "2026-11-29"]]) {
      settings.now = () => Date.parse(`${today}T12:00:00`);
      const trip = await compileAppFile(['app("Trip", lang="en")', `need start = input("date", "From", ${saturday})`, `need end = input("date", "To", ${end})`].join("\n"));
      expect([today, trip.faults, trip.definition.initialState.start, trip.definition.initialState.end]).toEqual([today, [], start, last]);
    }
  } finally { settings.now = now; }
});

test("prompt (B1): the input paragraph names each kind of input of the kit, a slider for a value that changes a drawing or a 3D model, and the section Dialogue names it", async () => {
  const input = KINDS.find(([kind]) => kind === "input")[1];
  const named = [.../kind: ((?:"\w+", )*"\w+")\./.exec(input)[1].matchAll(/"(\w+)"/g)].map(([, kind]) => kind);
  // The fault of an unknown kind lists each kind of the kit.
  const kit = /Use one of the kinds ((?:\w+, )*\w+)/.exec((await faultsOf('need x = input("nothing", "X", 1)')).find((fault) => fault.includes("is not built")))[1].split(", ");
  expect([named.toSorted(), named.includes("slider")]).toEqual([kit.toSorted(), true]);
  expect(input).toContain('"slider" is a number that the person drags from min= to max=, for a value that changes a drawing or a 3D model.');
  expect(SECTIONS.Dialogue).toContain('A number that changes the main visual is an input of kind "slider".');
  // A drawing and a 3D model that read the value of a slider: a liveInput of the slider draws each one again at the new value.
  const { handle } = await import("../src/run.js"), { built } = await import("./helpers.js");
  const slider = 'need angle = input("slider", "Angle", 0, min=0, max=360, step=30)';
  const visuals = [['need orbit = drawing("Orbit", 300, 300, [circle(150, 150, 95, "ink", stroke=true), circle(150 + 95 * cos($/angle), 150 - 95 * sin($/angle), 15, "sand")])', "orbit_view"],
    ['need crane = model3d("Crane", [box("", 0, 0.5, 0, 2, 1, 2, "surface"), box("arm", 1.5 * cos($/angle), 1.5, 1.5 * sin($/angle), 0.4, 0.4, 0.4, "accent")], turn=false, explode=false)', "crane_model"]];
  for (const [visual, surface] of visuals) {
    expect([surface, await faultsOf(visual, slider)]).toEqual([surface, []]);
    const { definition, app } = await built('app("T", lang="en")', visual, slider);
    const send = (state, event) => handle({ artifactId: "kit-test", revision: 1, state, event }, app);
    const opened = send(definition.initialState, { type: "open" }), moved = send(opened.state, { type: "liveInput", actionId: "angle_field", value: 90 });
    const drawn = (reply) => JSON.stringify(reply.surfaces[surface].commands);
    expect([surface, moved.state.angle, drawn(opened) === drawn(moved)]).toEqual([surface, 90, false]);
  }
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

// ---- The template sections (stage templates plan, Task 2).

const TEMPLATE = SECTIONS.Template ?? "";
/** The examples of the section Template as [request, reply]: the line "Request:" and the lines between the two fence lines under it. */
const TEMPLATE_EXAMPLES = [...TEMPLATE.matchAll(/^Request: (.*)\n```\n([\s\S]*?)```$/gm)].map(([, request, reply]) => [request, reply]);
const ship = readFileSync(new URL("./fixtures/template/ship.plan", import.meta.url), "utf8");

test("theFreeHashStays", () => {
  // The free records and fixtures carry this hash, so a template section is in no part of it.
  expect(prompts.PROMPT_HASH).toBe("69759b168bc3");
});

test("theTemplateHashIsItsOwn", () => {
  const { PROMPT_HASH, TEMPLATE_HASH, hashOf } = prompts, names = ["Template", "Template repair", "Template change"];
  expect(TEMPLATE_HASH).toMatch(/^[0-9a-f]{12}$/);
  expect(TEMPLATE_HASH).not.toBe(PROMPT_HASH);
  expect(hashOf(names)).toBe(TEMPLATE_HASH);
  // One changed word of a template section gives another template hash, and the free hash stays.
  const copy = { ...SECTIONS, Template: SECTIONS.Template.replace(/\bcard\b/, "kard") };
  expect(copy.Template).not.toBe(SECTIONS.Template);
  expect([hashOf(names, copy) === TEMPLATE_HASH, hashOf(["System", "Repair", "Dialogue", "Change"], copy)]).toEqual([false, PROMPT_HASH]);
});

test("eachTemplateExampleCompilesClean", async () => {
  // Each fence pair of the section is the reply of an example.
  expect(TEMPLATE.split("```").length - 1).toBe(TEMPLATE_EXAMPLES.length * 2);
  const words = [], outside = [];
  for (const [request, reply] of TEMPLATE_EXAMPLES) {
    const step = await compileStep({ text: reply, plan: null, read: true, previous: null, cap: 65536 });
    if (step.kind === "outside") { outside.push(step.word); continue; }
    expect([request, step.result.faults, step.result.leftOut, step.result.definition === null]).toEqual([request, [], [], false]);
    words.push(step.result.template);
  }
  expect([...new Set(words)].sort()).toEqual(["cells", "items", "scene"]);
  expect(outside.length).toBeGreaterThan(0);
});

test("each example of Template compiles clean with its narration", async () => {
  // Plan "one scene", Task 26: each template example teaches the narration lines, and one of them is a steps scene with one say line for each step.
  let stepped = 0;
  for (const [request, reply] of TEMPLATE_EXAMPLES) {
    const step = await compileStep({ text: reply, plan: null, read: true, previous: null, cap: 65536 });
    if (step.kind === "outside") continue;
    const says = step.result.kept.split("\n").filter((line) => line.startsWith("say"));
    expect([request, step.result.faults, step.result.leftOut, says.length > 0]).toEqual([request, [], [], true]);
    const steps = /^control = steps\("[^"]*", \[(.*?)\]/m.exec(reply);
    if (steps) { stepped++; expect([request, says.length]).toEqual([request, steps[1].split(",").length]); }
  }
  expect(stepped).toBe(1);
});

test("theTemplateSectionStatesTheBudget", () => {
  // The rules state each cap: the text caps 30, 28, 32, 140, 12, 14, and the list caps 6, 12, 100. The examples do not count.
  const rules = TEMPLATE.replace(/```[\s\S]*?```/g, "");
  for (const [name, cap] of Object.entries(BUDGET)) expect([name, cap, new RegExp(`\\b${cap}\\b`).test(rules)]).toEqual([name, cap, true]);
});

test("theTemplateTasksHaveTheirPlaceholders", () => {
  const asset = promptAsset(), faults = ["f1", "f2"];
  for (const part of ["{{task}}", "{{plan}}", "{{faults}}"]) expect([part, asset.templateRepairTask?.includes(part)]).toEqual([part, true]);
  for (const part of ["{{request}}", "{{plan}}"]) expect([part, asset.templateChangeTask?.includes(part)]).toEqual([part, true]);
  expect([asset.templateRepairTask.endsWith(SECTIONS["Template repair"]), asset.templateChangeTask.endsWith(SECTIONS["Template change"])]).toEqual([true, true]);
  // The host fills them as the free task texts: the plan with trimEnd, and the faults joined by "\n- ".
  expect(fill(asset.templateRepairTask, { task: "TASK", plan: ship.trimEnd(), faults: faults.join("\n- ") })).toBe(prompts.templateRepairText({ task: "TASK", plan: ship, faults }));
  expect(fill(asset.templateChangeTask, { request: "Add a sun", plan: ship.trimEnd() })).toBe(prompts.templateChangeText({ request: "Add a sun", plan: ship }));
});

test("theBundleReadsATemplate", async () => {
  // The sandbox of the host: a fresh context of the language with the bundle and nothing more.
  const context = createContext();
  runInContext(await bundle(), context);
  const input = { text: ship, plan: null, read: true, previous: null, cap: 65536 };
  const step = JSON.parse(await runInContext(`compileStep(${JSON.stringify(input)}).then(JSON.stringify)`, context));
  expect([step.kind, step.text, step.result.faults, step.result.template, step.result.definition === null]).toEqual(["plan", ship, [], "scene", false]);
});

test("theRestSectionChangesNoHash", () => {
  // The hashes of the free records and of the records of gate 2b: the rest section came after them.
  const { PROMPT_HASH, TEMPLATE_HASH, hashOf } = prompts, rest = SECTIONS["Template rest"];
  expect([PROMPT_HASH, TEMPLATE_HASH]).toEqual(["69759b168bc3", "5e96d3de2523"]);
  expect(rest?.length).toBeGreaterThan(100);
  const copy = { ...SECTIONS, "Template rest": `${rest} Write it.` };
  expect([hashOf(["System", "Repair", "Dialogue", "Change"], copy), hashOf(["Template", "Template repair", "Template change"], copy)]).toEqual([PROMPT_HASH, TEMPLATE_HASH]);
  // The task text of a rest call: the task and the complete lines so far, filled as the host fills the other task texts.
  const asset = promptAsset();
  for (const part of ["{{task}}", "{{plan}}"]) expect([part, asset.templateRestTask?.includes(part)]).toEqual([part, true]);
  expect(asset.templateRestTask.endsWith(rest)).toBe(true);
  expect(fill(asset.templateRestTask, { task: "TASK", plan: ship.trimEnd() })).toBe(prompts.templateRestText({ task: "TASK", plan: ship }));
});

const DATA = SECTIONS["Template data"] ?? "";
/** The examples of the section Template data as [request, excerpts, reply]: the request, its source excerpt lines, and the lines between the fences. */
const DATA_EXAMPLES = [...DATA.matchAll(/^Request: (.*)\n((?:Source \[\d+\] excerpt: .*\n)*)```\n([\s\S]*?)```$/gm)].map(([, request, excerpts, reply]) => [request, excerpts, reply]);

test("the data family has its own section and hash, and the explain sections and hashes stay (block 2 plan, D1)", () => {
  const { PROMPT_HASH, TEMPLATE_HASH, DATA_HASH, hashOf } = prompts;
  expect([PROMPT_HASH, TEMPLATE_HASH]).toEqual(["69759b168bc3", "5e96d3de2523"]);
  expect(DATA_HASH).toBe(hashOf(["Template data", "Template repair", "Template change"]));
  expect([DATA_HASH === TEMPLATE_HASH, DATA_HASH === PROMPT_HASH]).toEqual([false, false]);
  // The explain prompt does not grow: it names no data form.
  for (const word of ["compare(", "table(", "chart(", "nodata("]) expect([word, SECTIONS.Template.includes(word)]).toEqual([word, false]);
  expect(promptAsset().templateData).toBe(DATA);
});

test("each example of Template data compiles clean with its narration, and a searched example passes the host check of its excerpts", async () => {
  expect(DATA.split("```").length - 1).toBe(DATA_EXAMPLES.length * 2);
  const words = [];
  for (const [request, excerpts, reply] of DATA_EXAMPLES) {
    const data = excerpts ? { excerpts, site: "example.org", time: "now" } : null;
    const step = await compileStep({ text: reply, plan: null, read: true, previous: null, cap: 65536, data });
    expect([request, step.result.faults, step.result.leftOut, step.result.kept.includes("\nsay = ")]).toEqual([request, [], [], true]);
    if (data) expect([request, step.result.rejected]).toEqual([request, null]);
    words.push(step.result.template);
  }
  expect(words.sort()).toEqual(["chart", "compare", "nodata", "table"]);
});

test("the section Template data states the caps of the data forms", () => {
  const rules = DATA.replace(/```[\s\S]*?```/g, "");
  for (const cap of [30, 16, 12, 4, 32, 140, 110, 6, 8, 2, 3]) expect([cap, new RegExp(`\\b${cap}\\b`).test(rules) || (cap === 3 && rules.includes("t3"))]).toEqual([cap, true]);
});
