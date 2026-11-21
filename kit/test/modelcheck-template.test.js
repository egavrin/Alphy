// The model check of the template prompt (kit/modelcheck.js, stage templates plan Task 3) with a fake of the API call: the outcomes of a
// screen and of a change, the numbers of the bars, and the two caps of the calls. No test calls the API or writes the spend file.

import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import * as check from "../modelcheck.js";
import * as prompts from "../prompt.js";
import { compileStep } from "../src/compile.js";
import { fake } from "./helpers.js";

const ship = readFileSync(new URL("./fixtures/template/ship.plan", import.meta.url), "utf8");
/** The line of a name of the ship file. */
const line = (name) => ship.split("\n").find((one) => one.startsWith(`${name} = `));
const broken = ship.replace(line("s2"), "s2 = polygon(45, 75, 255)");
const screen = { id: "t-ship", set: "T", request: "Почему железный корабль не тонет?", expect: "template" };
const task = prompts.taskText({ request: screen.request, brief: "" });
const screenOf = async (item, ...texts) => {
  const { ask, bodies } = fake(...texts);
  return { record: await check.checkTemplate(item, { ask, model: "m", effort: "low" }), bodies };
};

test("aCleanFirstReplyIsClean0", async () => {
  const { record, bodies } = await screenOf(screen, ship);
  expect([record.id, record.set, record.expect, record.prompt, record.outcome, record.calls.length]).toEqual(["t-ship", "T", "template", prompts.TEMPLATE_HASH, "clean0", 1]);
  expect([record.kept, record.faults, record.leftOut, record.replied, record.repeated]).toEqual([ship, [], [], { kind: "lines", lines: 6 }, 0]);
  expect([bodies[0].instructions, bodies[0].reasoning.effort, bodies[0].input[0].content[0].text]).toEqual([prompts.PROMPT.Template, "low", task]);
  expect(record.firstCallMs).toBeGreaterThanOrEqual(0);
});

test("aRepairedReplyIsClean1", async () => {
  const { record, bodies } = await screenOf(screen, broken, line("s2"));
  expect([record.outcome, record.calls.length, record.calls.map((call) => call.effort), record.faults, record.kept]).toEqual(["clean1", 2, ["low", "low"], [], ship]);
  // The repair call sends the task, the lines of the first reply, and their faults. Its reply holds only the faulty line.
  const { result } = await compileStep({ text: broken, plan: null, read: true, previous: null, cap: 65536 });
  expect(result.faults[0]).toStartWith("s2: ");
  expect([bodies[1].instructions, bodies[1].input[0].content[0].text]).toEqual([prompts.PROMPT.Template, prompts.templateRepairText({ task, plan: broken, faults: result.faults })]);
  // The record keeps the faults of each compile, so the fault table of the model check shows what the repair fixed.
  expect(record.steps.map((step) => step.faults)).toEqual([result.faults, []]);
  expect(check.faultTable([record]).map(([, row]) => [row.count, row.repaired, row.ids])).toEqual([[1, 1, ["t-ship.0"]]]);
});

test("aLeftOutLineIsPartial1", async () => {
  const { record } = await screenOf(screen, broken, "s2 = polygon(45, 75, 255)");
  expect([record.outcome, record.calls.length, record.leftOut, record.kept]).toEqual(["partial1", 2, ["s2"], ship.replace(line("s2") + "\n", "")]);
  expect(record.faults.length).toBeGreaterThan(0);
  expect(record.faults.every((fault) => fault.startsWith("s2: "))).toBe(true);
});

test("aWholeFaultIsNot", async () => {
  // A fault of the first line gets the one repair too, and the screen fails as a whole only after it.
  const wrong = ship.replace('palette="blue"', 'palette="sky"');
  const { record, bodies } = await screenOf(screen, wrong, wrong.split("\n")[0]);
  expect([record.outcome, record.calls.length, bodies.length, record.faults.some((fault) => fault.startsWith("scene: "))]).toEqual(["not", 2, 2, true]);
  // A free app file is no template file: a whole fault, also when it compiles clean as an app.
  const free = 'app("Ship", lang="en")\nneed note = content("A ship floats.")\n';
  expect((await screenOf(screen, free, free)).record.outcome).toBe("not");
});

test("outsideOnALearnRequestIsRight", async () => {
  const { record } = await screenOf({ ...screen, id: "t-notes", expect: "outside" }, '```\noutside("learn")\n```');
  expect([record.outcome, record.calls.length, record.replied, record.kept, record.faults, record.leftOut]).toEqual(["outside", 1, { kind: "outside", word: "learn" }, null, [], []]);
  expect(check.templateNumbers([record])).toMatchObject({ refusalsRight: 1, explainRefused: 0, explainClean: 0 });
});

test("outsideOnAnExplainRequestCountsAsRefused", async () => {
  const { record } = await screenOf(screen, 'outside("other")');
  expect([record.outcome, record.replied]).toEqual(["outside", { kind: "outside", word: "other" }]);
  expect(check.templateNumbers([record])).toMatchObject({ explainClean: 0, explainRefused: 1, refusalsRight: 0 });
});

test("aChangeThatRepeatsAStoredLineCountsIt", async () => {
  const change = { id: "w-ship", set: "W", base: "ship.plan", request: "Сделай воду зелёной.", expect: "lines", plan: ship }, green = 's1 = rect(0, 110, 300, 110, "green", 0)';
  const { ask, bodies } = fake([line("s3"), green].join("\n"));
  const record = await check.checkTemplateChange(change, { ask, model: "m", effort: "low" });
  expect([record.outcome, record.prompt, record.replied, record.repeated, record.kept]).toEqual(["clean0", prompts.TEMPLATE_HASH, { kind: "lines", lines: 2 }, 1, ship.replace(line("s1"), green)]);
  expect([bodies[0].instructions, bodies[0].input[0].content[0].text]).toEqual([prompts.PROMPT.Template, prompts.templateChangeText({ request: change.request, plan: ship })]);
});

test("templateNumbersReadTheBars", () => {
  const record = (set, expect, outcome, firstCallMs, more = {}) => ({ id: `${set}-${expect}`, set, expect, outcome, firstCallMs, repeated: 0, replied: { kind: outcome === "outside" ? "outside" : "lines" }, ...more });
  const explain = [...Array(13).fill("clean0"), "clean1", "clean1", "outside"].map((outcome, index) => record("U", "template", outcome, (index + 1) * 1000));
  const learn = [...Array(7).fill("outside"), "clean0"].map((outcome) => record("U", "outside", outcome, 90000));
  const changes = [0, 0, 1, 0, 2].map((repeated, index) => record("V", index === 4 ? "outside" : "lines", "clean0", 99000, { repeated }));
  const numbers = check.templateNumbers([...explain, ...learn, ...changes, record("V", "outside", "outside", 99000)]);
  // The median first call is the one of the 16 explain records only.
  // The right refusal of a change writes no unchanged line, so it counts with the three replies that repeat no stored line.
  expect(numbers).toEqual({ explainClean: 15, refusalsRight: 7, explainRefused: 1, medianFirstCallMs: 8500, changesOnlyChanged: 4 });
  // An item with expect any (steps with no check) counts in no number.
  const any = [record("T", "any", "outside", 1), record("T", "any", "clean0", 1), record("T", "any", "not", 1)];
  expect(check.templateNumbers([...explain, ...learn, ...changes, record("V", "outside", "outside", 99000), ...any])).toEqual(numbers);
});

test("aRightRefusalOfAChangeCountsAsOnlyChangedLines", () => {
  const change = (expect, outcome, replied, repeated = 0) => ({ id: `w-${expect}-${outcome}`, set: "W", expect, outcome, firstCallMs: 1, replied, repeated });
  const onlyChanged = (record) => check.templateNumbers([record]).changesOnlyChanged, refused = { kind: "outside", word: "other" };
  // A right refusal writes no line again. A refusal of a change that the template can hold is no right refusal.
  expect([onlyChanged(change("outside", "outside", refused)), onlyChanged(change("lines", "outside", refused))]).toEqual([1, 0]);
  // A reply of lines counts only when it repeats no stored line.
  expect([onlyChanged(change("lines", "clean0", { kind: "lines", lines: 2 })), onlyChanged(change("lines", "clean0", { kind: "lines", lines: 2 }, 1))]).toEqual([1, 0]);
});

test("overLimitHoldsBothCaps", () => {
  expect([check.LIMIT, check.TEMPLATE_LIMIT]).toEqual([689, 150]);
  const entries = (count, label) => Array.from({ length: count }, () => [label, "2026-11-21T00:00:00.000Z"]);
  const full = entries(689, "final-terra-low d-moon low");
  expect([check.overLimit(full, "tpl-tune t-ship low"), check.overLimit(full, "fresh-terra-low e-bmi-en low"), check.overLimit(full.slice(1), "fresh-terra-low e-bmi-en low")]).toEqual([true, true, false]);
  const templates = [...entries(525, "final-terra-low d-moon low"), ...entries(150, "tpl-tune t-ship low")];
  expect([check.overLimit(templates, "tpl-final u-x low"), check.overLimit(templates, "fresh-terra-low e-bmi-en low"), check.overLimit(templates.slice(0, -1), "tpl-final u-x low")]).toEqual([true, false, false]);
});

test("theHeldOutScreensLoadWithTheExpectOfTheirType", () => {
  // Set U names the type of each item (Task 7): explain gives template, and learn gives outside.
  const items = check.templateSet("U");
  expect([items.length, items.filter((item) => item.expect === "template").length, items.filter((item) => item.expect === "outside").length]).toEqual([24, 16, 8]);
  expect(items.filter((item) => item.expect !== { explain: "template", learn: "outside" }[item.type]).map((item) => item.id)).toEqual([]);
  // An item that has its own expect keeps it.
  expect(check.templateSet("T").find((item) => item.id === "t-morse").expect).toBe("any");
});

test("aRecordWithNoExpectTakesTheExpectOfItsItem", () => {
  // The records of the final run of 2026-11-21 hold no expect, so the table takes it from the set file by the id of the record.
  const items = check.templateSet("U"), explain = items.find((item) => item.type === "explain"), learn = items.find((item) => item.type === "learn");
  const record = (id, outcome, more = {}) => ({ id, set: "U", outcome, firstCallMs: 1000, repeated: 0, replied: { kind: outcome === "outside" ? "outside" : "lines" }, ...more });
  const records = check.withItemExpect([record(explain.id, "clean0"), record(learn.id, "outside"), record(learn.id, "outside", { expect: "template" })]);
  expect(records.map((one) => one.expect)).toEqual(["template", "outside", "template"]);
  expect(check.templateNumbers(records.slice(0, 2))).toMatchObject({ explainClean: 1, refusalsRight: 1, explainRefused: 0 });
});
