// The pure parts of the model check (kit/modelcheck.js) with a fake of the API call: the task text, the body, the repair text,
// the prose count of a repair reply, and the record of a request. No test calls the API.

import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { APP_CAP, BARS, SET_E, SET_F, barFaults, bodyOf, changeNumbers, checkOne, droppedOf, faultTable, keptOf, kindOf, numbers, overCap, repairsOf, storedOf, worstCase } from "../modelcheck.js";
import { PROMPT, changeText, repairText, taskText } from "../prompt.js";
import { BLOCKS } from "../src/change.js";
import { patchAppFile } from "../src/compile.js";
import { MAX_STATE_BYTES } from "../src/run.js";
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
  // The sixth word of the prompt (K1 fix 4): a request whose main thing no kind gives.
  expect(kindOf('outside("other")')).toEqual({ kind: "outside", word: "other" });
  expect(kindOf(PLAN)).toEqual({ kind: "plan" });
  expect(kindOf('outside("3d")\nneed x = content("x")')).toEqual({ kind: "plan" });
  expect(kindOf("I cannot do that.")).toEqual({ kind: "other" });
});

test("modelcheck: a repair reply counts its prose lines, which the merge of the kit drops, and the repair text holds the task, the plan, and the faults", () => {
  // compile.test.js tests the merge itself (patchAppFile).
  expect(droppedOf('Here is the fix:\n```\nneed bill = input("money", "Bill", 50, min=0, max=500)\ndelete tip\nneed share = content("x")\n```')).toBe(1);
  expect(repairText({ task: "TASK", plan: PLAN, faults: ["f1", "f2"] })).toBe(`TASK\n\nYour plan:\n${PLAN.trimEnd()}\n\nFaults of the kit:\n- f1\n- f2\n\n${PROMPT.Repair}`);
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

test("modelcheck (P2): the repair count is a setting of 0 or more, default 2, and the worst case of a run counts it for each request that expects a plan", async () => {
  expect([repairsOf(), repairsOf("1"), repairsOf("0")]).toEqual([2, 1, 0]);
  // A count that is not a whole number would never end the loop of checkOne before the hard stop.
  for (const bad of ["", "-1", "1.5", "two"]) expect(() => repairsOf(bad)).toThrow();
  const items = [{ expect: "plan" }, { expect: "plan" }, { expect: "outside" }];
  expect([worstCase(items, 2), worstCase(items, 1), worstCase(items, 0)]).toEqual([7, 5, 3]);
  const { ask, bodies } = fake(PLAN.replace("max=500)", "max=500, bogus=1)"), "nothing", "still nothing");
  const record = await checkOne(item, { ask, model: "m", effort: "low", repairs: 1 });
  expect([record.outcome, record.calls.length, record.steps.length, record.repairs, bodies.length]).toEqual(["not", 2, 2, 1, 2]);
});

test("modelcheck: an outside reply and a cut reply get no compile", async () => {
  const outside = await checkOne({ ...item, expect: "outside" }, { ...fake('outside("game")'), model: "m", effort: "low" });
  expect([outside.outcome, outside.first.word, outside.steps.length]).toEqual(["outside", "game", 0]);
  const cut = await checkOne(item, { ...fake(null), model: "m", effort: "low" });
  expect([cut.outcome, cut.steps.length]).toEqual(["incomplete", 0]);
});

test("modelcheck (U-K fix 1): a repair reply is read as the app reads it, so an outside line ends the request, and a plan past the cap of the app gets no repair call", async () => {
  const faulty = PLAN.replace("max=500)", "max=500, bogus=1)");
  const outside = await checkOne(item, { ...fake(faulty, '```\noutside("game")\n```', PLAN), model: "m", effort: "low" });
  expect([outside.outcome, outside.calls.length, outside.calls[1].outside, outside.steps.length, outside.plan]).toEqual(["not", 2, "game", 1, faulty]);
  // AppFileCompiler.kt: a text past APP_FILE_MAX_CHARS is the fault of the app with no compile, and the app gives it no repair call.
  const long = PLAN + `need note = card("Note", "${"x".repeat(65536)}")\n`;
  const over = await checkOne(item, { ...fake(long, PLAN), model: "m", effort: "low" });
  expect([over.outcome, over.calls.length, over.steps[0].faults]).toEqual(["not", 1, [`the plan has ${long.length} characters, and Alphy reads at most 65536. Use shorter texts and fewer lines`]]);
});

test("modelcheck (U2-K item 2): the cap of the harness and its fault are APP_FILE_MAX_CHARS and the fault of overCap in AppFileCompiler.kt", () => {
  // The test reads the Kotlin source as kit/build.js reads ToolUiCatalog.kt, so a change on one side fails here.
  const kotlin = readFileSync(new URL("../../app/src/main/java/com/alpha/assistant/tools/appfile/AppFileCompiler.kt", import.meta.url), "utf8");
  const cap = /\bAPP_FILE_MAX_CHARS=(\d+)/.exec(kotlin)?.[1], start = /\bTOO_LONG="([^"]*)"/.exec(kotlin)?.[1];
  const template = /fun overCap\(length: Int\)=AppFileCompilation\("faults",\s*listOf\("([^"]*)"\)/.exec(kotlin)?.[1];
  expect([typeof cap, typeof start, typeof template]).toEqual(["string", "string", "string"]);
  const length = Number(cap) + 1, sentence = template.replace("$TOO_LONG", start).replace("$length", String(length)).replace("$APP_FILE_MAX_CHARS", cap);
  expect([APP_CAP, overCap(length).faults, sentence.includes("$")]).toEqual([Number(cap), [sentence], false]);
});

test("modelcheck: the device bars and the host forms that the kit takes", () => {
  // The bar of the start state is the state limit of the kit (run.js), which is the limit of the host.
  expect(barFaults({ compileMs: 2400, stateBytes: MAX_STATE_BYTES + 1, plan: 'need a = figure("number", "", 1)' })).toEqual(["compile 2400 ms > 2300", `start state ${MAX_STATE_BYTES + 1} bytes > ${MAX_STATE_BYTES}`, "a figure with an empty label"]);
  expect(barFaults({ compileMs: 1, stateBytes: MAX_STATE_BYTES, plan: "" })).toEqual([]);
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

// ---- Task 2b: change cases (set F).

const caseOf = (id) => SET_F.find((entry) => entry.id === id);

test("modelcheck (2b): set F is 16 change requests on 8 model plans, and the unchanged plan keeps each stored value of the person whole", async () => {
  expect(SET_F.length).toBe(16);
  expect(new Set(SET_F.map((entry) => entry.id)).size).toBe(16);
  expect(SET_F.filter((entry) => entry.expect === "outside").map((entry) => entry.id)).toEqual(["e-fuel-pl-b"]);
  expect(worstCase(SET_F, 2)).toBe(31);
  for (const entry of SET_F.filter((each) => each.id.endsWith("-a"))) {
    // storedOf throws when an event changes nothing or the events leave each value of the person at its start.
    const stored = await storedOf(entry);
    expect(stored.fields.length).toBeGreaterThan(0);
    // A reply that only repeats a line of the plan leaves the app file as it was: no MISMATCH, the same state, and nothing lost.
    const record = await checkOne(entry, { ...fake(entry.plan.trimEnd().split("\n").at(-1)), model: "m", effort: "low" });
    expect([record.outcome, record.steps[0].faults, record.repeated, record.compared]).toEqual(["unchanged", [], 1, stored.fields]);
    expect(record.state).toEqual(stored.previous.state);
    expect([record.lost, record.restarted, record.hidden, record.gone]).toEqual([[], [], [], []]);
  }
  await expect(storedOf({ ...caseOf("e-fuel-pl-a"), events: [{ type: "action", actionId: "nothing", value: 1 }] })).rejects.toThrow("changes nothing");
});

test("modelcheck (2b): a change reply goes into the app file, compiles against the stored state, and keeps the tip of the person", async () => {
  const entry = caseOf("holdout-tip-ru-b"), { ask, bodies } = fake('need tip = input("percent", "Чаевые", 15, min=0, max=100)');
  const record = await checkOne(entry, { ask, model: "m", effort: "medium" });
  expect([bodies[0].instructions, bodies[0].reasoning.effort, bodies[0].input[0].content[0].text]).toEqual([PROMPT.System, "medium", changeText(entry)]);
  expect([record.outcome, record.first, record.repairs, record.replyLines, record.planLines, record.repeated]).toEqual(["clean0", { kind: "lines" }, 1, 1, 5, 0]);
  // The person set 12, so the start value 15 of the change does not replace it (rule 3).
  expect([record.state.tip, record.lost, record.plan.includes('"Чаевые", 15')]).toEqual([12, [], true]);
});

test("modelcheck (U-K item 1): a reply that removes a column with saved rows gets BLOCKS and no repair call, as in the app", async () => {
  const entry = caseOf("showcase-lang-shopping-it-b"), line = entry.plan.split("\n")[1];
  const removed = line.replace(', corsia: "text"', "").replace(', corsia: "Corsia"', "").replace(', corsia: "Pin"', "");
  const { ask, bodies } = fake(removed, line);
  const record = await checkOne(entry, { ask, model: "m", effort: "medium", repairs: 2 });
  expect([record.outcome, record.steps.map((step) => [step.blocks, step.mismatch]), record.calls.map((call) => call.effort), bodies.length]).toEqual(["not", [[1, 0]], ["medium"], 1]);
  expect(record.steps[0].faults[0]).toContain(BLOCKS);
  expect([record.plan, record.state]).toEqual([patchAppFile(entry.plan, removed), undefined]);
});

test("modelcheck (2b): a change reply with a fault of a line gets one repair call at the effort low over the change input, also when the run asks for more", async () => {
  const entry = caseOf("holdout-tip-ru-b"), fixed = 'need tip = input("percent", "Чаевые", 15, min=0, max=100)', faulty = fixed.replace("max=100)", "max=100, bogus=1)");
  const { ask, bodies } = fake(faulty, fixed);
  const record = await checkOne(entry, { ask, model: "m", effort: "medium", repairs: 2 });
  expect([record.outcome, record.steps.map((step) => [step.blocks, step.mismatch]), record.calls.map((call) => call.effort)]).toEqual(["clean1", [[0, 0], [0, 0]], ["medium", "low"]]);
  expect(bodies[1].input[0].content[0].text).toBe(repairText({ task: changeText(entry), plan: patchAppFile(entry.plan, faulty), faults: record.steps[0].faults }));
  expect([record.state.tip, record.lost]).toEqual([12, []]);
  const failed = await checkOne(entry, { ...fake(faulty, faulty, faulty), model: "m", effort: "low", repairs: 2 });
  expect([failed.outcome, failed.calls.length, failed.state]).toEqual(["not", 2, undefined]);
});

test("modelcheck (2b): a rename keeps the value only in app.keep, a prose reply changes nothing, and an outside reply gets no compile", async () => {
  const rename = await checkOne(caseOf("e-fuel-pl-a"), { ...fake(['delete cena', 'need price = input("money", "Cena za litr w zł", 6, min=0, icon="Coins")',
    'need koszt = figure("money", "Koszt przejazdu", $/litry * $/price, example=[{litry: 15, price: 6}, 90], decimals=2, icon="Wallet", unit="zł")'].join("\n")), model: "m", effort: "low" });
  expect([rename.outcome, rename.lost, rename.hidden, rename.gone, rename.state.cena]).toEqual(["clean0", [], ["cena"], ["cena"], 6.5]);
  const prose = await checkOne(caseOf("e-fuel-pl-a"), { ...fake("I would add a field for the people."), model: "m", effort: "low" });
  expect([prose.outcome, prose.first, prose.replyLines, prose.repeated]).toEqual(["unchanged", { kind: "lines" }, 1, 0]);
  const outside = await checkOne(caseOf("e-fuel-pl-b"), { ...fake('outside("game")'), model: "m", effort: "low" });
  expect([outside.outcome, outside.first.word, outside.steps.length, outside.replyLines, outside.gone]).toEqual(["outside", "game", 0, 1, []]);
  expect(changeNumbers([rename, prose, outside])[0]).toBe("clean at the first try 1/3, after the repair 0/3, not clean 0/3 (none), unchanged 1/3 (e-fuel-pl-a), outside 1/3 (e-fuel-pl-b game)");
});

test("modelcheck (2b): the comparison finds a lost row, a moved value, a new row, and a restarted inner value, and a new column is no difference", () => {
  const state = { list: [{ id: "r1", name: "a" }, { id: "r2", name: "b" }], n: 3, deck_s: { i: 2, open: false } }, start = { list: [], n: 0, deck_s: { i: 0, open: false } };
  const stored = { previous: { state }, start, fields: ["list", "n", "deck_s"], inner: ["deck_s"] }, data = { fields: [["list", "list"], ["n", "n"], ["deck_i", "deck_s.i"]] };
  expect(keptOf(stored, { list: [{ id: "r1", name: "a", qty: 1 }, { id: "r2", name: "b", qty: 1 }], n: 3, deck_s: { i: 2, open: true } }, data)).toEqual({ lost: [], restarted: [], hidden: [] });
  expect(keptOf(stored, { list: [{ id: "r1", name: "x" }, { id: "r3", name: "c" }], n: 4, deck_s: { i: 0, open: false } }, { fields: [["n", "n"]] })).toEqual({
    lost: [{ at: "list/r1/name", was: "a", now: "x" }, { at: "list/r2", was: { id: "r2", name: "b" } }, { at: "list/r3", now: { id: "r3", name: "c" } }, { at: "n", was: 3, now: 4 }],
    restarted: [{ at: "deck_s/i", was: 2, now: 0 }], hidden: ["list", "deck_s"] });
});
