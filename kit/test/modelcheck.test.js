// The pure parts of the model check (kit/modelcheck.js) with a fake of the API call: the task text, the body, the repair text,
// the merge of a repair, and the record of a request. No test calls the API.

import { expect, test } from "bun:test";
import { BARS, PROMPT, SET_E, barFaults, bodyOf, checkOne, faultTable, kindOf, merge, numbers, repairText, taskText } from "../modelcheck.js";
import "./helpers.js";

const PLAN = 'app("Tip", lang="en")\nneed bill = input("money", "Bill", 80, min=0, max=500)\nneed tip = figure("money", "Tip", $/bill * 0.1, example=[{bill: 100}, 10])\n';
/** A fake of the API call: each call takes the next reply of the list, and the bodies stay for the test. */
const fake = (...texts) => {
  const bodies = [];
  const ask = async (body) => { bodies.push(body); const text = texts[bodies.length - 1]; return text === null ? { status: "incomplete", text: "", wallS: 1 } : { status: "completed", text, wallS: 2, firstTextS: 1, usage: { output_tokens: 10 } }; };
  return { ask, bodies };
};
const item = { id: "t", set: "C", request: "A tip calculator", reply: "I'll build it.", expect: "plan" };

test("modelcheck: the task text is the generationPrompt of the app, with the host line under the request of a dialogue", () => {
  expect(taskText(item)).toBe("User request: A tip calculator\nWidget brief: \nExcerpts below may be truncated. Preserve the complete request, brief, and source URLs above.\nReply excerpt: I'll build it.");
  expect(taskText({ request: "Explain", host: "HOST" })).toBe("User request: Explain\nHOST\nWidget brief: \nExcerpts below may be truncated. Preserve the complete request, brief, and source URLs above.");
});

test("modelcheck: set E is 28 fresh requests with unique ids: 14 apps, 6 dialogue screens with the host line, and 8 that expect outside", async () => {
  expect(SET_E.length).toBe(28);
  expect(new Set(SET_E.map((entry) => entry.id)).size).toBe(28);
  expect(SET_E.every((entry) => ["plan", "outside"].includes(entry.expect) && entry.set === "E" && entry.reply === "")).toBe(true);
  const screens = SET_E.filter((entry) => entry.host);
  expect([SET_E.filter((entry) => entry.expect === "plan" && !entry.host).length, screens.length, SET_E.filter((entry) => entry.expect === "outside").length]).toEqual([14, 6, 8]);
  expect(screens.every((entry) => entry.host === PROMPT.Dialogue && entry.expect === "plan")).toBe(true);
  const { ask, bodies } = fake(PLAN, PLAN);
  const screen = await checkOne(screens[0], { ask, model: "m", effort: "low" }), app = await checkOne(SET_E[0], { ask, model: "m", effort: "low" });
  expect(bodies[0].input[0].content[0].text).toStartWith(`User request: ${screens[0].request}\n${PROMPT.Dialogue}\nWidget brief: \n`);
  expect(bodies[1].input[0].content[0].text).toStartWith(`User request: ${SET_E[0].request}\nWidget brief: \n`);
  expect([screen.set, screen.dialogue, app.set, app.dialogue]).toEqual(["E", true, "E", false]);
});

test("modelcheck: the body is the body of OpenAiResponsesWire with no JSON mode", () => {
  expect(bodyOf({ model: "m", effort: "low", system: "S", input: "I" })).toEqual({ reasoning: { effort: "low" }, model: "m", stream: true, store: false,
    include: ["reasoning.encrypted_content"], max_output_tokens: 128000, instructions: "S", input: [{ type: "message", role: "user", content: [{ type: "input_text", text: "I" }] }] });
});

test("modelcheck: a reply is outside, a plan, or other", () => {
  expect(kindOf('```\noutside("game")\n```')).toEqual({ kind: "outside", word: "game" });
  expect(kindOf(PLAN)).toEqual({ kind: "plan" });
  expect(kindOf('outside("3d")\nneed x = content("x")')).toEqual({ kind: "plan" });
  expect(kindOf("I cannot do that.")).toEqual({ kind: "other" });
});

test("modelcheck: a repair is patchAppFile of the kit: it replaces a line by its name in place, deletes a line, drops prose, and drops a plan line that the kit could not read", () => {
  const plan = PLAN + "need broken = figure(\n";
  const { text, dropped } = merge(plan, 'Here is the fix:\n```\nneed bill = input("money", "Bill", 50, min=0, max=500)\ndelete tip\nneed share = content("x")\n```');
  expect(dropped).toBe(1);
  expect(text).toBe('app("Tip", lang="en")\nneed bill = input("money", "Bill", 50, min=0, max=500)\nneed share = content("x")\n');
  expect(repairText({ task: "TASK", plan: text, faults: ["f1", "f2"] })).toBe(`TASK\n\nYour plan:\n${text.trimEnd()}\n\nFaults of the kit:\n- f1\n- f2\n\n${PROMPT.Repair}`);
});

test("modelcheck: a clean plan is clean at the first try, with its compile time, start state, and look forms", async () => {
  const { ask, bodies } = fake(PLAN);
  const record = await checkOne(item, { ask, model: "m", effort: "medium" });
  expect([record.outcome, record.clean, record.calls.length, record.lines, record.first]).toEqual(["clean0", 0, 1, 3, { kind: "plan" }]);
  expect(bodies[0].instructions).toBe(PROMPT.System);
  expect(bodies[0].reasoning.effort).toBe("medium");
  expect(record.steps[0].stateBytes).toBeGreaterThan(0);
  expect(record.steps[0].compileMs).toBeLessThan(BARS.compileMs);
  expect(record.bars).toEqual([]);
  expect(record.definition.source).toBeUndefined();
});

test("modelcheck: a faulty plan gets repair calls at the effort low until it is clean, at most two", async () => {
  const faulty = PLAN.replace("max=500)", "max=500, bogus=1)");
  const repaired = await checkOne(item, { ...fake(faulty, 'need bill = input("money", "Bill", 80, min=0, max=500)'), model: "m", effort: "medium" });
  expect([repaired.outcome, repaired.calls.map((call) => call.effort), repaired.steps.length]).toEqual(["clean1", ["medium", "low"], 2]);
  expect(repaired.steps[0].faults.join()).toContain("has no option bogus");
  const { ask, bodies } = fake(faulty, "nothing", "still nothing");
  const failed = await checkOne(item, { ask, model: "m", effort: "low" });
  expect([failed.outcome, failed.calls.length, failed.steps.length, failed.clean]).toEqual(["not", 3, 3, null]);
  expect(bodies[1].input[0].content[0].text).toContain("has no option bogus");
});

test("modelcheck: an outside reply and a cut reply get no compile", async () => {
  const outside = await checkOne({ ...item, expect: "outside" }, { ...fake('outside("game")'), model: "m", effort: "low" });
  expect([outside.outcome, outside.first.word, outside.steps.length]).toEqual(["outside", "game", 0]);
  const cut = await checkOne(item, { ...fake(null), model: "m", effort: "low" });
  expect([cut.outcome, cut.steps.length]).toEqual(["incomplete", 0]);
});

test("modelcheck: the device bars and the host forms that the kit takes", () => {
  expect(barFaults({ compileMs: 2400, stateBytes: 50000, plan: 'need a = figure("number", "", 1)' })).toEqual(["compile 2400 ms > 2300", "start state 50000 bytes > 49152", "a figure with an empty label"]);
  const ui = { type: "column", children: [{ type: "card", children: [{ type: "segmented", options: [] }] }] };
  expect(barFaults({ compileMs: 1, stateBytes: 1, plan: "", definition: { ui } })).toEqual(["a choice that is the only child of a card or a row"]);
});

test("modelcheck: the numbers count the clean steps, the times, the routing, and the calls, with the apps and the dialogue screens of set E apart", async () => {
  const fresh = { ...item, set: "E", reply: "" }, faulty = PLAN.replace("max=500)", "max=500, bogus=1)");
  const records = [await checkOne(item, { ...fake(PLAN), model: "m", effort: "low" }), await checkOne({ ...item, id: "holdout-tilt-ru", expect: "outside" }, { ...fake(PLAN), model: "m", effort: "low" }),
    await checkOne({ ...item, id: "x", expect: "plan" }, { ...fake('outside("3d")'), model: "m", effort: "low" }),
    await checkOne({ ...fresh, id: "e-app" }, { ...fake(PLAN), model: "m", effort: "low" }), await checkOne({ ...fresh, id: "e-screen", host: "HOST" }, { ...fake(faulty, 'need bill = input("money", "Bill", 80, min=0, max=500)'), model: "m", effort: "low" }),
    await checkOne({ ...fresh, id: "e-game", expect: "outside" }, { ...fake('outside("game")'), model: "m", effort: "low" })];
  const rows = numbers(records);
  expect(rows[0]).toStartWith("set C: clean first 1/2, after 1 repair 0/2, after 2 0/2, not clean 1/2 (outside 1); clean under the device bars 1/2 | first call median 2 s, p90 2 s, max 2 s | first text median 1 s |");
  expect(rows[1]).toStartWith("set E app: clean first 1/1, after 1 repair 0/1, after 2 0/1, not clean 0/1 (outside 0); clean under the device bars 1/1 |");
  expect(rows[2]).toStartWith("set E dialogue: clean first 0/1, after 1 repair 1/1, after 2 0/1, not clean 0/1 (outside 0); clean under the device bars 1/1 |");
  expect(rows[3]).toStartWith("set E: clean first 1/2, after 1 repair 1/2, after 2 0/2, not clean 0/2 (outside 0); clean under the device bars 2/2 |");
  expect(rows[4]).toBe("routing: 1/2 not servable got outside; x outside(3d) went outside wrongly; device misses: holdout-tilt-ru; an outside reply median 2 s, max 2 s");
  expect(rows[5]).toStartWith("calls 7,");
});

test("modelcheck: the fault table groups one kind of fault over names and numbers, and says whether the next report still had it", () => {
  const steps = (...faults) => faults.map((list) => ({ faults: list }));
  const records = [{ id: "a", steps: steps(["need x: figure has no option bogus. Its options are decimals: need x = figure(bogus=1)"], []) },
    { id: "b", steps: steps(["need y: figure has no option size. Its options are decimals: need y = figure(size=2)"], ["need y: figure has no option size. Its options are decimals: need y = figure(size=3)"]) }];
  expect(faultTable(records)).toEqual([["need X: figure has no option size. Its options are decimals", { count: 2, repaired: 0, kept: 1, last: 1, ids: ["b.0", "b.1"] }],
    ["need X: figure has no option bogus. Its options are decimals", { count: 1, repaired: 1, kept: 0, last: 0, ids: ["a.0"] }]]);
});
