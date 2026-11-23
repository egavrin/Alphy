// The model check of the plan prompt outside the app: a model writes a plan for each request, the kit compiles it, and a faulty plan
// gets up to REPAIRS repair calls (default 2). A plan with a hole gets none and ends with the outcome hole, as in the app.
// Each call is a call of the OpenAI Responses API with the body that OpenAiResponsesWire.kt builds.
//   bun kit/modelcheck.js probe MODEL                          one call with a short request; the raw event stream goes to evidence/modelcheck/probe/
//   bun kit/modelcheck.js run LABEL SET MODEL EFFORT [ID...]   SET is C (the 45 corpus cases), D (8 dialogue requests), E (28 fresh requests), F (16 change requests),
//                                                              or a combination such as CD. STOP_AT (default 600) refuses a run whose worst case passes it.
//                                                              REPAIRS is the count of repair calls. A change case of set F gets one repair call,
//                                                              and none after a fault that the saved data blocks or the mismatch fault.
//                                                              SET T (template tuning), U (held-out screens), V (held-out changes), W (change pilot),
//                                                              or a combination of these four, is the template check: PROMPT.Template, one repair call,
//                                                              and a LABEL that starts with tpl-. A template set runs with no set of the free prompt.
//                                                              SET X (data tuning) or Y (data held-out) is the check of the data family: PROMPT["Template
//                                                              data"], the excerpts of each searched request in its task, and the host check (checkData).
//                                                              SET L (library tuning), M (library final, library arm), N (the same requests, free arm), or
//                                                              O (requests with no entry, library arm) is the check of the library (stage 6, M1): the
//                                                              library arm sends the candidates of the catalog in the host line, as the app does.
//                                                              SET P (motion tuning) or Q (motion held-out) is the check of motion and diagrams (block 2
//                                                              plan, stage 7): screens and changes in one set, library arm. An item with a base is a change.
//                                                              SET S (stage 7b, R2) holds 4 held-out changes that the base already shows: expect same().
//                                                              SET G (parts tuning) or H (parts held-out) is the check of the part names that the model
//                                                              gives (stage 7b, R3): diagrams of things with no ready object and of ready objects in
//                                                              languages with no names in the entry, library arm.
//   bun kit/modelcheck.js table LABEL...                       the numbers of each label, from its records; one line for a label of the template check
// The key is OPENAI_API_KEY, else its line in ~/.env. The script sends it only to api.openai.com, and writes it nowhere.
// Each call is counted in evidence/modelcheck/spend.json before it is sent. The count never passes LIMIT, and the calls of the labels
// that start with tpl- never pass TEMPLATE_LIMIT.

import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { BLOCKS, MISMATCH, isRecord } from "./src/change.js";
import { LINE, compileAppFile, compileStep, linesOf, needsIn, patchAppFile, readPlanReply } from "./src/compile.js";
import { dataOf, runtime } from "./src/emit.js";
import { MAX_STATE_BYTES, handle } from "./src/run.js";
import { runtimeText } from "./build.js";
import { DATA_HASH, PROMPT, PROMPT_HASH, TEMPLATE_HASH, candidatesText, changeText, repairText, taskText, templateChangeText, templateRepairText } from "./prompt.js";
import { catalogAsset } from "./build.js";
import { numbersIn } from "./src/form-data.js";
import { ELEMENTS } from "./src/elements.js";

const ROOT = fileURLToPath(new URL("..", import.meta.url));
const EVIDENCE = ROOT + "evidence/modelcheck/";
/**
 * The calls that the owner approved for the model checks: 491 before task P4 (A0, H1, P2, task 2b, and P3), P4 adds at most 32 (its decision 4), and task B1b adds at most 28.
 * The approval of the design of 2026-11-21 (docs/superpowers/specs/2026-11-21-stage-templates-stream-design.md, section 7) adds at most 150 calls of the template check.
 * The owner raised both caps by 2000 on 2026-11-22 (block 2 plan, stage 5), and the dispatch of stage 7 raised both by 200. The cap of each stage stays in its dispatch.
 */
export const LIMIT = 2889;
/** The calls of the template check, the labels that start with tpl-: at most 2350, whatever the calls of the other labels are. */
export const TEMPLATE_LIMIT = 2350;
/** The saved reports of today's agent loop: final-30d43aa, and for the 11 cases that it lacks, the first showcase run of 2026-11-16. */
const REPORTS = ["/Users/egavrin/Documents/alpha/alphy-agent-evidence-2026-11-17/final-30d43aa/", "/Users/egavrin/Documents/alpha/alphy-agent-evidence-2026-11-16/showcase1/"];
/** The output limit of the agent call of the app (ToolEngine.kt 131072, capped by OpenAiProfile.maxOutputTokens). */
const MAX_OUTPUT = 128000;
/** The device bars of a clean plan: Bun is about 1.3 times faster than the device, whose compile cap is 3000 ms, and the host refuses a larger start state. */
export const BARS = { compileMs: 2300, stateBytes: MAX_STATE_BYTES };

/** The cases that the kit cannot serve (the code map, section 8): 5 need a picture, 15 are games, a drawing canvas, or 3D. Their expected reply is outside(...). */
export const OUTSIDE_CASES = new Set(["showcase-image-recipe", "showcase-image-flashcards", "showcase-image-plant", "showcase-image-story", "showcase-image-menu",
  "showcase-3d-solar", "showcase-3d-dna", "showcase-3d-dice", "showcase-3d-globe", "showcase-3d-molecule", "showcase-3d-maze",
  "showcase-game-snake", "showcase-game-memory", "showcase-game-2048", "showcase-game-drawing",
  "holdout-bricks-en", "holdout-tilt-ru", "holdout-dodge-es", "holdout-jump3d-de", "holdout-moles-zh"]);
/** The cases that need a device function with no part: a plan for one of them is a miss. */
export const DEVICE_CASES = new Set(["holdout-tilt-ru"]);
/** Set D: what a person says in a voice dialogue. Four ask for an explanation, four ask to learn a thing step by step. */
export const SET_D = [
  ["d-moon", "Почему Луна всегда повёрнута к Земле одной стороной? Покажи, как это работает."],
  ["d-rainbow", "Объясни, как получается радуга."],
  ["d-interest", "Что такое сложный процент? Объясни на примере вклада."],
  ["d-heart", "Как сердце качает кровь по телу?"],
  ["d-pythagoras", "Научи меня теореме Пифагора по шагам, а в конце проверь вопросом."],
  ["d-spanish", "Давай выучим пять приветствий на испанском, а потом устрой мне маленький тест."],
  ["d-fractions", "Хочу разобраться с дробями. Дай мне ползунок, чтобы я сам поэкспериментировал."],
  ["d-breathing", "Проведи меня через дыхательное упражнение с таймером: вдох четыре секунды, выдох шесть."],
].map(([id, request]) => ({ id, set: "D", request, reply: "", host: PROMPT.Dialogue, expect: "plan" }));
/** Set E: 28 requests that no earlier run and no example of the prompt used (task H1). A request of a dialogue gets the host line, as in set D. */
export const SET_E = JSON.parse(readFileSync(new URL("./test/fixtures/fresh-requests.json", import.meta.url), "utf8"))
  .map(({ id, request, expect, dialogue }) => ({ id, set: "E", request, reply: "", host: dialogue ? PROMPT.Dialogue : "", expect }));
/** Set F (task 2b): two change requests for each of 8 model plans, and the events of the person that make the stored state of each plan. */
export const SET_F = JSON.parse(readFileSync(new URL("./test/fixtures/change-requests.json", import.meta.url), "utf8"))
  .flatMap(({ fixture, events, requests }) => requests.map(({ id, request, expect }) => ({ id: `${fixture}-${id}`, set: "F", fixture, events, request, expect,
    plan: readFileSync(new URL(`./test/fixtures/model/${fixture}.plan`, import.meta.url), "utf8") })));
/** The repair calls of a change case: the change call of the app sends one (task 2b, decision 1). */
const CHANGE_REPAIRS = 1;

/**
 * The files of the sets of the template check (stage templates plan, decision 7): T tuning, U held-out screens, V held-out changes, W the change
 * pilot, and the data family (block 2 plan, M1): X tuning and Y held-out.
 */
const TEMPLATE_SETS = { T: "template-tuning.json", U: "template-holdout.json", V: "template-changes.json", W: "template-pilot.json",
  X: "template-data-tuning.json", Y: "template-data-final.json",
  L: "template-library-tuning.json", M: "template-library-final.json", N: "template-library-final.json", O: "template-library-none.json",
  P: "template-motion-tuning.json", Q: "template-motion-final.json", S: "template-same.json",
  G: "template-parts-tuning.json", H: "template-parts-final.json" };
/** The arm of each set of the library check (stage 6, M1): "library" sends the candidates of the catalog in the host line, "free" sends none. */
export const LIBRARY_ARMS = { L: "library", M: "library", N: "free", O: "library", P: "library", Q: "library", G: "library", H: "library" };
const isChangeSet = (set) => set === "V" || set === "W" || set === "S";
export const isDataSet = (set) => set === "X" || set === "Y";
/** The expect of a screen item that names only its type, as the items of set U do (stage templates plan, Task 7). */
const EXPECT_OF_TYPE = { explain: "template", learn: "outside" };
/**
 * The items of a template set. A screen item is {id, request, expect: "template" | "outside" | "any"}, and a change item of V or W is
 * {id, base, request, expect: "lines" | "outside"}, which gets the text of its base file of test/fixtures/template-bases/ as plan. An item of S
 * (stage 7b, R2) is a change that its base already shows, with expect "same". Each item of V, W, and S is a change, and an item of P or Q is a change when it has a base.
 * The read is lazy: a set whose file is missing stops only the run that names it.
 * A screen item with type: "explain" | "learn" and no expect gets the expect of its type (EXPECT_OF_TYPE).
 */
export function templateSet(name) {
  const at = (path) => new URL(`./test/fixtures/${path}`, import.meta.url);
  if (!existsSync(at(TEMPLATE_SETS[name]))) throw new Error(`set ${name}: kit/test/fixtures/${TEMPLATE_SETS[name]} is missing`);
  return JSON.parse(readFileSync(at(TEMPLATE_SETS[name]), "utf8")).map((item) => ({ ...item, set: name, expect: item.expect ?? EXPECT_OF_TYPE[item.type],
    ...(item.base && { plan: readFileSync(at(`template-bases/${item.base}`), "utf8") }) }));
}
/**
 * The records with the expect of their item: a record with no expect takes the one of its item in the file of its set, by its id. The records of
 * the final run of 2026-11-21 hold no expect, because set U named only the type of each item then.
 */
export function withItemExpect(records) {
  const items = new Map();
  const itemOf = (record) => (items.get(record.set) ?? items.set(record.set, templateSet(record.set)).get(record.set)).find((item) => item.id === record.id);
  return records.map((record) => (record.expect !== undefined || !Object.hasOwn(TEMPLATE_SETS, record.set) ? record : { ...record, expect: itemOf(record)?.expect }));
}

/** The cases of set C: each request of the corpus, the reply text of its saved report, and the path of its baseline app. */
export function corpus() {
  return ["showcase", "holdout"].flatMap((name) => JSON.parse(readFileSync(`${ROOT}tools/agent_corpus/${name}.json`, "utf8")).cases).map((item) => {
    const dir = REPORTS.find((base) => existsSync(`${base}${item.id}/report.json`)), report = dir ? JSON.parse(readFileSync(`${dir}${item.id}/report.json`, "utf8")) : {};
    return { id: item.id, set: "C", request: item.request, tags: item.tags, reply: report.assistant_text ?? "", baseline: dir && existsSync(`${dir}${item.id}/artifact.json`) ? `${dir}${item.id}/artifact.json` : null,
      expect: OUTSIDE_CASES.has(item.id) ? "outside" : "plan" };
  });
}

/** The body of a call as OpenAiResponsesWire.wireBody builds it with no JSON mode: the profile's reasoning effort, the model, the instructions, and one user message. */
export const bodyOf = ({ model, effort, system, input, maxTokens = MAX_OUTPUT }) => ({
  reasoning: { effort }, model, stream: true, store: false, include: ["reasoning.encrypted_content"], max_output_tokens: maxTokens,
  instructions: system, input: [{ type: "message", role: "user", content: [{ type: "input_text", text: input }] }],
});
/** The call of one request: it sends input with system as the instructions through ask, and keeps the call in calls with its step and effort. */
const senderOf = ({ ask, model, system, calls }) => async (step, level, input) => {
  const call = await ask(bodyOf({ model, effort: level, system, input }));
  calls.push({ step, effort: level, ...call });
  return call;
};

/** The kind of a reply for the numbers: readPlanReply of the kit, and "other" for a plan with no header and no need line. */
export function kindOf(text) {
  const reply = readPlanReply(text);
  if (reply.kind !== "plan") return reply;
  return { kind: /^\s*(app\s*\(|need\s)/m.test(reply.text) ? "plan" : "other" };
}
/** The kind of a change reply: outside(...), same(), or lines that change the app file, as the change call of the app reads it with readPlanReply. */
export function changeKindOf(text) {
  const reply = readPlanReply(text);
  return reply.kind === "plan" ? { kind: "lines" } : reply;
}
/** The count of the prose lines of a repair reply: patchAppFile of the kit drops each line that is no line of an app file (LINE). */
export const droppedOf = (reply) => linesOf(reply).filter((line) => !LINE.test(line)).length;

/** The faults that the device bars add to a plan that the kit calls clean (BARS), and the forms that the host refuses with no fault of the kit. */
export function barFaults({ compileMs, stateBytes, plan, definition }) {
  const out = [];
  if (compileMs > BARS.compileMs) out.push(`compile ${Math.round(compileMs)} ms > ${BARS.compileMs}`);
  if (stateBytes > BARS.stateBytes) out.push(`start state ${stateBytes} bytes > ${BARS.stateBytes}`);
  if (/\bfigure\(\s*"[^"]*"\s*,\s*""/.test(plan)) out.push("a figure with an empty label");
  const lone = (node) => ["card", "row", "flowRow"].includes(node?.type) && node.children?.length === 1 && ["segmented", "select", "multiChoice"].includes(node.children[0]?.type);
  const walk = (node) => (node && typeof node === "object" ? (lone(node) ? 1 : 0) + (node.children ?? []).reduce((sum, child) => sum + walk(child), 0) + walk(node.rowTemplate) : 0);
  if (definition && walk(definition.ui)) out.push("a choice that is the only child of a card or a row");
  return out;
}

// ---- A change case: the stored app of the person and the comparison of its values with the next state.

/** The fields of the person in compiled data: the value of each line, and each object of the inner values of a need (change.js nextState). */
export const ownOf = (data) => [...new Set(data.fields.filter(([root]) => root !== "app:empty").map(([, path]) => path.split(".")[0]))];
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);

/**
 * The stored app of a change case: the plan compiled, and its start state after the events of the person through the run-time, as the host keeps it.
 * fields are the fields of the person whose stored value differs from the start, and inner names those that hold the inner values of a need.
 * An event that changes nothing throws, so a wrong control name in the fixture cannot leave a stored value at its start.
 */
export async function storedOf({ plan, events }) {
  runtime.text ||= await runtimeText();
  const { definition, faults } = await compileAppFile(plan);
  if (faults.length) throw new Error(`the plan of a change case has a fault: ${faults[0]}`);
  const data = dataOf(definition), start = definition.initialState;
  let state = start;
  for (const event of events) {
    const next = handle({ artifactId: "modelcheck", revision: 1, state, event }, data).state;
    if (same(next, state)) throw new Error(`the event ${event.type} ${event.actionId} changes nothing`);
    state = next;
  }
  const fields = ownOf(data).filter((field) => !same(state[field], start[field]));
  if (!fields.length) throw new Error("the events leave each value of the person at its start");
  const inner = [...new Set(data.fields.filter(([, path]) => path.includes(".")).map(([, path]) => path.split(".")[0]))].filter((field) => fields.includes(field));
  return { previous: { appFile: plan, stateSchema: definition.stateSchema, state }, start, data, fields, inner };
}

/**
 * Each stored value of the person that the next state does not hold unchanged, by the names of the previous definition. A list compares each stored
 * row by its id and each stored column by its name, so a new column is no difference, and a row that only the next state has is one. A record
 * compares each key whose stored value differs from its start (rule 3b of change.js: a start value is no data of the person).
 * lost holds the values of lines, and restarted the inner values of a need, which rules 7 and 3c start again by design.
 * hidden names each compared field that no line of the next app owns, so only app.keep holds it and the screen does not show it.
 */
export function keptOf({ previous: { state }, start, fields, inner }, next, data) {
  const lost = [], restarted = [], owned = ownOf(data);
  for (const field of fields) {
    const was = state[field], now = next[field], out = inner.includes(field) ? restarted : lost;
    if (Array.isArray(was) && was.every(isRecord)) {
      const rows = Array.isArray(now) ? now : [];
      for (const row of was) {
        const to = rows.find((other) => other?.id === row.id);
        if (!to) out.push({ at: `${field}/${row.id}`, was: row });
        else for (const [key, value] of Object.entries(row)) if (!same(to[key], value)) out.push({ at: `${field}/${row.id}/${key}`, was: value, now: to[key] });
      }
      for (const row of rows) if (!was.some((other) => other.id === row?.id)) out.push({ at: `${field}/${row?.id}`, now: row });
    } else if (isRecord(was)) {
      for (const [key, value] of Object.entries(was)) if (!same(value, start[field]?.[key]) && !same(now?.[key], value)) out.push({ at: `${field}/${key}`, was: value, now: now?.[key] });
    } else if (!same(was, now)) out.push({ at: field, was, now });
  }
  return { lost, restarted, hidden: fields.filter((field) => !owned.includes(field)) };
}

/**
 * What the first reply of a change case did to the plan: its lines, the lines that repeat a line of the plan, whether the final plan is the plan
 * as it was (a reply with no line that changes it), and the needs and list columns that the final plan lacks (columns only for a clean plan).
 */
function changeOf(item, stored, reply, plan, definition) {
  const lines = linesOf(item.plan).map((line) => line.trim()), replied = linesOf(reply), needs = new Set(needsIn(plan ?? "")), lists = definition && dataOf(definition).lists;
  const columns = lists ? Object.entries(stored.data.lists).flatMap(([root, old]) => (lists[root] ? old.filter(([key]) => !lists[root].some(([other]) => other === key)).map(([key]) => `${root}.${key}`) : [])) : [];
  return { replyLines: replied.length, planLines: lines.length, repeated: replied.filter((line) => lines.includes(line.trim())).length,
    same: plan === patchAppFile(item.plan, ""), gone: plan === null ? [] : [...needsIn(item.plan).filter((need) => !needs.has(need)), ...columns] };
}

/** The cap of the app on the reply, the plan, and the merged plan (AppFileCompiler.kt APP_FILE_MAX_CHARS). A test compares it with that file. */
export const APP_CAP = 65536;
/**
 * The fault of the app for a text past its cap (AppFileCompiler.kt overCap). The app gives it no repair call (AppFileSteps.outcome, tooLong).
 * A test compares its sentence with that file.
 */
export const overCap = (length) => ({ faults: [`the plan has ${length} characters, and Alphy reads at most ${APP_CAP}. Use shorter texts and fewer lines`], notes: [],
  definition: null, tooLong: true });

/**
 * One step of the app (compileStep of the kit with the cap of the app): {outside} for an outside reply, {same: true} for same(), else the merged plan, and the result with its
 * time and the bytes of the start state of a clean plan. A reply or a plan past the cap gets the fault of the app with no compile, as in AppFileCompiler.kt.
 */
async function compile(input) {
  const started = performance.now(), long = [input.plan, input.text].find((text) => text?.length > APP_CAP);
  const step = long === undefined ? await compileStep({ ...input, cap: APP_CAP }) : { kind: "plan", text: input.plan ?? input.text, result: null };
  if (step.kind === "outside") return { outside: step.word };
  if (step.kind === "same") return { same: true };
  const result = step.result ?? overCap((long ?? step.text).length), compileMs = Math.round((performance.now() - started) * 10) / 10;
  const stateBytes = result.definition ? Buffer.byteLength(JSON.stringify(result.definition.initialState)) : null;
  return { plan: step.text, result: { ...result, compileMs, stateBytes } };
}

/**
 * One request: the plan call, the compile, and up to `repairs` repair calls at the effort low. ask(body) is the call of the API.
 * The record holds each call with its reply, each report of the kit, the outcome, and the final plan. clean is the step of the first clean report.
 * Each compile is compileStep of the kit with the cap of the app, as the app runs it: each reply as readPlanReply reads it, and a repair reply merged
 * into the plan. An outside repair reply ends the request (its call holds the word), and a text past the cap gets its fault and no repair call.
 * A plan with a hole ends the request with the outcome hole and no repair call, also when it compiles clean (AppFileSteps.outcome, fallback:hole).
 * A change case (set F) sends the request and the app file of its stored app (changeText), merges the reply into the app file, compiles each plan
 * against the stored state, and gets one repair call, or none after a fault that the saved data blocks (blockedBySavedData) or the mismatch
 * fault (savedDataMismatch), as in the app. Its record also holds the stored state, the next state, and the comparison of the two (keptOf).
 */
export async function checkOne(item, { ask, model, effort, repairs = 2 }) {
  runtime.text ||= await runtimeText();
  const stored = item.set === "F" ? await storedOf(item) : null, limit = stored ? CHANGE_REPAIRS : repairs;
  const task = stored ? changeText(item) : taskText(item), calls = [], steps = [], send = senderOf({ ask, model, system: PROMPT.System, calls });
  const first = await send(0, effort, task), kind = first.status !== "completed" ? { kind: first.status } : stored ? changeKindOf(first.text) : kindOf(first.text);
  const record = { id: item.id, set: item.set, dialogue: Boolean(item.host), expect: item.expect, model, effort, repairs: limit, prompt: PROMPT_HASH, task, calls, steps, first: kind, clean: null, hole: false, plan: null };
  let result = null;
  const done = () => finish(stored ? Object.assign(record, { fixture: item.fixture, request: item.request, events: item.events.length, stored: stored.previous.state,
    compared: stored.fields, inner: stored.inner }, changeOf(item, stored, first.text, record.plan, record.clean !== null && result.definition)) : record);
  if (!["plan", "other", "lines"].includes(kind.kind)) return done();
  // The stored app as the app sends it: the state schema and the state as JSON text. Each reply is read as the app reads it (AppFileCompiler.kt patch).
  const previous = stored && { appFile: stored.previous.appFile, stateSchema: JSON.stringify(stored.previous.stateSchema), state: JSON.stringify(stored.previous.state) };
  const stepOf = (text, plan) => compile({ text, plan, read: true, previous });
  let next = await stepOf(first.text, stored ? item.plan : null), plan;
  for (let step = 0; ; step++) {
    ({ plan, result } = next);
    steps.push({ faults: result.faults, notes: result.notes, compileMs: result.compileMs, stateBytes: result.stateBytes, dropped: step ? calls.at(-1).dropped : 0,
      ...(stored && { blocks: result.faults.filter((fault) => fault.includes(BLOCKS)).length, mismatch: result.faults.filter((fault) => fault.includes(MISMATCH)).length }) });
    record.plan = plan;
    // As in the app (AppFileSteps.outcome): a plan with a hole ends with fallback:hole, before the clean check and with no repair call.
    if (result.hole) { record.hole = true; break; }
    if (!result.faults.length) {
      record.clean = step;
      // The run-time text is the same for each plan, and the raw replies compile again to it, so the record keeps the rest of the definition.
      record.definition = { ...result.definition, source: undefined };
      record.bars = barFaults({ ...result, plan });
      if (stored) Object.assign(record, { state: result.state }, keptOf(stored, result.state, dataOf(result.definition)));
      break;
    }
    // As in the app (AppFileSteps.outcome): a model cannot fix the saved data or shorten a text past the cap, so these faults get no repair call.
    if (result.blockedBySavedData || result.savedDataMismatch || result.tooLong) break;
    if (step === limit) break;
    const call = await send(step + 1, "low", repairText({ task, plan, faults: result.faults }));
    if (call.status !== "completed") break;
    calls.at(-1).dropped = droppedOf(call.text);
    next = await stepOf(call.text, plan);
    // As in the app (AppFileSteps.steps): an outside repair reply ends the request with fallback:outside, and the plan stays as it was.
    if (next.outside || next.same) { calls.at(-1).outside = next.outside ?? "same"; break; }
  }
  return done();
}
/** The count of repair calls from the setting REPAIRS: a whole number of 0 or more, default 2. A count that is not one would never end the loop of checkOne. */
export function repairsOf(text = "2") {
  if (!/^\d+$/.test(text)) throw new Error(`REPAIRS is a whole number of 0 or more, not ${JSON.stringify(text)}`);
  return Number(text);
}
/**
 * The worst case of a run: each first call, and the repairs for each request that expects a plan, one for a change case. A plan for a request
 * that expects outside also gets repairs, and LIMIT stops them.
 */
export const worstCase = (items, repairs) => items.length + items.filter((item) => item.expect === "plan").reduce((sum, item) => sum + (item.set === "F" ? CHANGE_REPAIRS : repairs), 0);

/**
 * The outcome of a record: hole, cleanN, not, or the kind of a first reply that got no compile. A change whose final plan is the app file as it
 * was is unchanged. hole comes before unchanged and the clean outcomes, as fallback:hole comes before fallback:empty and a clean plan in the app.
 * The app tests the saved-data flags before the hole, so it gives fallback:data_blocks to a plan with a hole and such a fault.
 */
function finish(record) {
  const plan = record.plan ?? "";
  record.outcome = record.hole ? "hole" : record.same ? "unchanged" : record.clean !== null ? `clean${record.clean}` : ["plan", "other", "lines"].includes(record.first.kind) ? "not" : record.first.kind;
  record.lines = plan ? linesOf(plan).length : 0;
  record.bytes = Buffer.byteLength(plan);
  record.pathS = Math.round((record.calls.reduce((sum, call) => sum + (call.wallS ?? 0), 0) + record.steps.reduce((sum, step) => sum + step.compileMs / 1000, 0)) * 100) / 100;
  return record;
}

// ---- The template check: a template file for a voice screen (stage templates plan, decision 7).

/**
 * One request of the template check: the call with PROMPT.Template as instructions and input as its input, the compile of the reply (merged
 * into plan for a change, against the stored app previous), and one repair call at the effort low over the faults (templateRepairText).
 * A reply that is no template file, such as a free app file, is a whole fault. An outside repair reply ends the request, and the lines stay.
 * The outcome is clean0 or clean1, partial1 (a line is left out or keeps its stored version after the repair), not (no screen after the repair),
 * outside, or the status of a first call that did not complete. firstCallMs runs from the send of the first call to the end of its reply.
 * replied is the first reply as the kit reads it: {kind: "outside", word}, or {kind: "lines", lines} with the count of its lines, and repeated
 * counts its lines that a line of plan already has. steps holds the faults of each compile, as faultTable reads them. say counts the kept
 * narration lines (plan "one scene", Task 34).
 */
async function templateRecord(item, { ask, model, effort }, input, plan, previous, { system = PROMPT.Template, hash = TEMPLATE_HASH, data = null, family = false } = {}) {
  runtime.text ||= await runtimeText();
  const calls = [], steps = [], send = senderOf({ ask, model, system, calls });
  const started = performance.now(), first = await send(0, effort, input);
  const record = { id: item.id, set: item.set, expect: item.expect, prompt: hash, outcome: first.status, firstCallMs: Math.round(performance.now() - started), calls,
    replied: { kind: first.status }, kept: null, faults: [], leftOut: [], repeated: 0, say: 0, steps, ...(family && { form: null, check: data }) };
  if (first.status !== "completed") return record;
  const stepOf = async (text, on) => {
    const step = await compile({ text, plan: on, read: true, previous, data });
    // A step of a searched data form keeps the value that the host check rejected, or null.
    if (step.result) steps.push({ faults: step.result.faults, ...(data && { rejected: step.result.rejected ?? null }) });
    return step;
  };
  let next = await stepOf(first.text, plan);
  if (next.outside) return Object.assign(record, { outcome: "outside", replied: { kind: "outside", word: next.outside } });
  // A change that the screen already shows (stage 7b, R2): the stored file stays.
  if (next.same) return Object.assign(record, { outcome: "same", replied: { kind: "same" } });
  const lines = linesOf(first.text), stored = plan === null ? [] : linesOf(plan).map((one) => one.trim());
  Object.assign(record, { replied: { kind: "lines", lines: lines.length }, repeated: lines.filter((one) => stored.includes(one.trim())).length });
  const clean = ({ result }) => Boolean(result.template) && !result.faults.length;
  if (!clean(next)) {
    const call = await send(1, "low", templateRepairText({ task: input, plan: next.plan, faults: next.result.faults }));
    const repaired = call.status === "completed" ? await stepOf(call.text, next.plan) : null;
    if (repaired?.outside || repaired?.same) calls.at(-1).outside = repaired.outside ?? "same";
    else if (repaired) next = repaired;
  }
  const { result } = next;
  return Object.assign(record, { outcome: clean(next) ? `clean${calls.length - 1}` : result.template && result.definition ? "partial1" : "not",
    kept: result.kept ?? null, faults: result.faults, leftOut: result.leftOut ?? [], say: linesOf(result.kept ?? "").filter((one) => /^say\d* = /.test(one)).length,
    ...(family && { form: result.template ?? null }) });
}

/** The time of the search that the check gives the host check of each searched request: a fixed time, so each record of a request is the same. */
export const CHECK_TIME = "22.11.2026 12:00";
/**
 * The host check of a request with sources, as DataCheck.of builds it (AppFileCompiler.kt): the title and the summary of each source, its first
 * two sites, and the search time. Null for a request with no source, which gets no check.
 */
export const dataCheckOf = (sources = []) => (sources.length ? { excerpts: sources.map((source) => `${source.title}\n${source.summary}`).join("\n"),
  site: [...new Set(sources.map((source) => new URL(source.url).hostname.replace(/^www\./, "")))].slice(0, 2).join(", "), time: CHECK_TIME } : null);
/**
 * A request of the data family (block 2 plan, M1): taskText of its request, brief, reply, and sources, as the data call of the app sends it, with
 * PROMPT["Template data"] as instructions, and the host check of its sources on each compile. A record also holds its form and its check.
 */
export const checkTemplateData = (item, options) => templateRecord(item, options,
  taskText({ request: item.request, brief: item.brief ?? "", reply: item.reply ?? "", sources: item.sources ?? [] }), null, null,
  { system: PROMPT["Template data"], hash: DATA_HASH, data: dataCheckOf(item.sources), family: true });

/**
 * A screen request of the template check: taskText of its request with no brief, as the early plan of a first screen sends it. A request of the
 * library arm has the candidates in its host line: each object of the catalog, as the search of the app gives a catalog of 24 objects or fewer.
 * A record of the library check holds its arm and the library names of its kept file.
 */
export async function checkTemplate(item, options) {
  const arm = LIBRARY_ARMS[item.set], host = arm === "library" ? candidatesText(JSON.parse(catalogAsset()).objects) : "";
  const record = await templateRecord(item, options, taskText({ request: item.request, brief: "", host }), null, null);
  return arm ? { ...record, arm, names: libraryNames(record.kept ?? "") } : record;
}
/** The names of the library lines of a template file, in their order. */
export const libraryNames = (text) => [...text.matchAll(/=\s*use\("([^"]+)"/g)].map((match) => match[1]);

/**
 * A change request of the template check: templateChangeText of the request, the candidates of the catalog, and the base file. The app sends
 * the ready objects of its search with a spoken change of a shown template (fix list 4, K3). Each reply compiles against the base at its
 * start state, as the host sends a stored app, so a faulty changed line keeps its stored version.
 */
export async function checkTemplateChange(item, options) {
  runtime.text ||= await runtimeText();
  const { definition, faults } = await compileAppFile(item.plan);
  if (faults.length) throw new Error(`the base ${item.base} of ${item.id} has a fault: ${faults[0]}`);
  const previous = { appFile: item.plan, stateSchema: JSON.stringify(definition.stateSchema), state: JSON.stringify(definition.initialState) };
  return templateRecord(item, options, templateChangeText({ ...item, host: candidatesText(JSON.parse(catalogAsset()).objects) }), item.plan, previous);
}

// ---- The API call: the only place that sends a request to an outside service.

function key() {
  if (process.env.OPENAI_API_KEY) return process.env.OPENAI_API_KEY;
  for (const line of readFileSync(process.env.HOME + "/.env", "utf8").split("\n")) {
    const match = /^\s*(?:export\s+)?OPENAI_API_KEY\s*=\s*(.*)$/.exec(line);
    if (match) return match[1].trim().replace(/^(['"])(.*)\1$/, "$2");
  }
  throw new Error("no OPENAI_API_KEY");
}
const SPEND = EVIDENCE + "spend.json";
const spent = () => (existsSync(SPEND) ? JSON.parse(readFileSync(SPEND, "utf8")) : { calls: 0, log: [] });
const isTemplateLabel = (label) => label.startsWith("tpl-");
/** True when the log of spend.json leaves no call for label: it holds LIMIT calls, or label is of the template check and the log holds TEMPLATE_LIMIT of its calls. */
export const overLimit = (log, label) => log.length >= LIMIT || (isTemplateLabel(label) && log.filter(([one]) => isTemplateLabel(one)).length >= TEMPLATE_LIMIT);
/** Counts one call before it is sent. JavaScript runs one worker at a time, so the read and the write of the count do not interleave. */
function count(label) {
  const state = spent();
  if (overLimit(state.log, label)) throw new Error(`the limit of ${LIMIT} calls, or of ${TEMPLATE_LIMIT} calls with a label that starts with tpl-, is reached`);
  state.calls++;
  state.log.push([label, new Date().toISOString()]);
  mkdirSync(EVIDENCE, { recursive: true });
  writeFileSync(SPEND, JSON.stringify(state));
  return state.calls;
}
/** An error text with each key-like token masked, so that no part of a key reaches a record. */
const masked = (text) => text.replace(/sk-[\w*-]+/g, "sk-…");

/** One streamed call. Returns the reply text, the status (completed, incomplete, failed, or http N), the times in seconds, and the usage of the API. */
async function openai(body, label, rawTo = null) {
  count(label);
  const started = performance.now(), now = () => Math.round(performance.now() - started) / 1000;
  const out = { status: "failed", text: "", wallS: null, firstTextS: null, usage: null };
  try {
    const response = await fetch("https://api.openai.com/v1/responses", { method: "POST", signal: AbortSignal.timeout(300000),
      headers: { "Content-Type": "application/json", Authorization: "Bearer " + key() }, body: JSON.stringify(body) });
    if (!response.ok) return { ...out, status: `http ${response.status}`, error: masked((await response.text()).slice(0, 600)), wallS: now() };
    const raw = [], decoder = new TextDecoder();
    let buffer = "";
    for await (const bytes of response.body) {
      const chunk = decoder.decode(bytes, { stream: true });
      if (rawTo) raw.push(chunk);
      buffer += chunk;
      const lines = buffer.split("\n");
      buffer = lines.pop();
      for (const line of lines) {
        if (!line.startsWith("data:")) continue;
        const event = JSON.parse(line.slice(5));
        if (event.type === "response.output_text.delta") { out.firstTextS ??= now(); out.text += event.delta; }
        else if (["response.completed", "response.incomplete", "response.failed"].includes(event.type)) {
          out.status = event.type.slice(9);
          out.usage = event.response?.usage ?? null;
          if (event.response?.incomplete_details) out.incomplete = event.response.incomplete_details;
          if (event.response?.error) out.error = masked(JSON.stringify(event.response.error));
        } else if (event.type === "error") out.error = masked(JSON.stringify(event));
      }
    }
    if (rawTo) writeFileSync(rawTo, raw.join(""));
  } catch (failure) { out.error = masked(String(failure?.message ?? failure)); }
  out.wallS = now();
  return out;
}

// ---- The numbers.

const median = (list) => { const sorted = [...list].sort((a, b) => a - b); return sorted.length ? (sorted[Math.floor((sorted.length - 1) / 2)] + sorted[Math.ceil((sorted.length - 1) / 2)]) / 2 : null; };
const p90 = (list) => { const sorted = [...list].sort((a, b) => a - b); return sorted.length ? sorted[Math.ceil(sorted.length * 0.9) - 1] : null; };
const max = (list) => (list.length ? Math.max(...list) : null);
const share = (part, all) => `${part}/${all}`;
/** The look numbers of definitions: score_app of tools/agent_corpus/visual_score.py, as kit/look.js runs it. */
export function looksOf(definitions) {
  const table = "import json, sys\nfrom visual_score import score_app\nprint(json.dumps({k: score_app(d, []) for k, d in json.load(sys.stdin).items()}))";
  const scored = Bun.spawnSync(["python3", "-c", table], { cwd: ROOT + "tools/agent_corpus", stdin: Buffer.from(JSON.stringify(definitions)), stderr: "inherit" });
  if (scored.exitCode) throw new Error("score_app failed");
  return JSON.parse(scored.stdout.toString());
}

/** The groups of the numbers: each set, and the apps and the dialogue screens of set E apart and together. */
const GROUPS = [["C", (record) => record.set === "C"], ["D", (record) => record.set === "D"], ["E app", (record) => record.set === "E" && !record.dialogue],
  ["E dialogue", (record) => record.set === "E" && record.dialogue], ["E", (record) => record.set === "E"]];
/** The numbers of decision 6 of A0 and decision 4 of H1 for records of one model and effort, as rows of text. */
export function numbers(records) {
  const rows = [];
  for (const [group, inGroup] of GROUPS) {
    const planned = records.filter((record) => inGroup(record) && record.expect === "plan");
    if (!planned.length) continue;
    const at = (step) => planned.filter((record) => record.clean === step).length, bar = planned.filter((record) => record.clean !== null && !record.bars.some((fault) => /^(compile|start state)/.test(fault))).length;
    const firsts = planned.map((record) => record.calls[0].wallS), paths = planned.filter((record) => record.clean !== null).map((record) => record.pathS);
    const texts = planned.map((record) => record.calls[0].firstTextS).filter((seconds) => seconds !== null && seconds !== undefined);
    rows.push(`set ${group}: clean first ${share(at(0), planned.length)}, after 1 repair ${share(at(1), planned.length)}, after 2 ${share(at(2), planned.length)}, not clean ${share(planned.length - at(0) - at(1) - at(2), planned.length)}`
      + ` (outside ${planned.filter((record) => record.first.kind === "outside").length}); clean under the device bars ${share(bar, planned.length)}`
      + ` | first call median ${median(firsts)} s, p90 ${p90(firsts)} s, max ${max(firsts)} s | first text median ${median(texts)} s | path to clean median ${median(paths)} s, p90 ${p90(paths)} s`
      + ` | first call output tokens median ${median(planned.map((record) => record.calls[0].usage?.output_tokens ?? 0))}, reasoning ${median(planned.map((record) => record.calls[0].usage?.output_tokens_details?.reasoning_tokens ?? 0))}`);
  }
  const outside = records.filter((record) => record.expect === "outside"), replies = records.filter((record) => record.first.kind === "outside").map((record) => record.calls[0].wallS);
  if (outside.length) rows.push(`routing: ${share(outside.filter((record) => record.first.kind === "outside").length, outside.length)} not servable got outside; `
    + `${records.filter((record) => record.expect === "plan" && record.first.kind === "outside").map((record) => `${record.id} outside(${record.first.word})`).join(", ") || "no servable case"} went outside wrongly; `
    + `device misses: ${records.filter((record) => DEVICE_CASES.has(record.id) && record.first.kind !== "outside").map((record) => record.id).join(", ") || "none"}`
    + `; an outside reply median ${median(replies)} s, max ${max(replies)} s`);
  const usage = (field) => records.reduce((sum, record) => sum + record.calls.reduce((inner, call) => inner + (field(call.usage ?? {}) ?? 0), 0), 0);
  rows.push(`calls ${records.reduce((sum, record) => sum + record.calls.length, 0)}, input tokens ${usage((u) => u.input_tokens)} (cached ${usage((u) => u.input_tokens_details?.cached_tokens)}), output ${usage((u) => u.output_tokens)} (reasoning ${usage((u) => u.output_tokens_details?.reasoning_tokens)})`);
  return rows;
}

/** The numbers of decision 6 of task 2b for the change records of one model and effort, as rows of text. numbers() adds the routing and the calls. */
export function changeNumbers(records) {
  const all = records.length, of = (outcome) => records.filter((record) => record.outcome === outcome), ids = (list, text = (record) => record.id) => list.map(text).join(", ") || "none";
  const spread = (list, value) => { const values = list.map(value).filter((number) => typeof number === "number"); return `median ${median(values)}, p90 ${p90(values)}, max ${max(values)}`; };
  const lines = records.filter((record) => record.first.kind === "lines"), clean = records.filter((record) => record.clean !== null && !record.same);
  const first = (field) => records.map((record) => field(record.calls[0].usage ?? {}) ?? 0), sum = (list) => list.reduce((total, value) => total + value, 0);
  const output = first((usage) => usage.output_tokens), reasoning = first((usage) => usage.output_tokens_details?.reasoning_tokens);
  const steps = (count) => records.flatMap((record) => record.steps.map((step, index) => [record, step, index])).filter(([, step]) => step[count]).map(([record, step, index]) => `${record.id}.${index} (${step[count]})`);
  const listed = (field) => ids(clean.filter((record) => record[field].length), (record) => `${record.id} ${record[field].map((each) => each.at ?? each).join(" ")}`);
  // A change with a hole is not applied in the app (fallback:hole), so it is not clean.
  const not = [...of("not"), ...of("hole")];
  return [`clean at the first try ${share(of("clean0").length, all)}, after the repair ${share(of("clean1").length, all)}, not clean ${share(not.length, all)} (${ids(not)}),`
    + ` unchanged ${share(of("unchanged").length, all)} (${ids(of("unchanged"))}), outside ${share(of("outside").length, all)} (${ids(of("outside"), (record) => `${record.id} ${record.first.word}`)})`,
  `reply lines of the ${lines.length} replies of lines: ${spread(lines, (record) => record.replyLines)}; plan lines ${spread(lines, (record) => record.planLines)};`
    + ` replies that repeat a line of the plan: ${ids(lines.filter((record) => record.repeated), (record) => `${record.id} ${record.repeated} of ${record.replyLines}`)}`,
  `first call ${spread(records, (record) => record.calls[0].wallS)} s | first text ${spread(records, (record) => record.calls[0].firstTextS)} s | whole path ${spread(records, (record) => record.pathS)} s`,
  `first call output tokens median ${median(output)} (reasoning ${median(reasoning)}), reasoning ${sum(reasoning)} of ${sum(output)} output tokens of the first calls`,
  `stored values of the lines stayed in ${share(clean.filter((record) => !record.lost.length).length, clean.length)} clean plans; lost or moved: ${listed("lost")}; kept but not shown: ${listed("hidden")}; inner values started again: ${listed("restarted")}`,
  `faults with BLOCKS: ${ids(steps("blocks"), String)}; with MISMATCH: ${ids(steps("mismatch"), String)}; needs and list columns that the final plan lacks: ${ids(records.filter((record) => record.gone?.length), (record) => `${record.id} ${record.gone.join(" ")}`)}`];
}

/** The groups of the template records: screens to explain, screens to refuse, and changes. A screen of expect any is in no group. */
const isChange = (record) => isChangeSet(record.set) || record.kind === "change";
const TEMPLATE_GROUPS = { explain: (record) => !isChange(record) && record.expect === "template", refusal: (record) => !isChange(record) && record.expect === "outside",
  change: isChange };
/**
 * The record of an item of the motion check (sets P and Q) with what its kept file is: kind and entry of its item, template (a template file
 * with no fault, or with only a left-out line), play (the file has a play line), diagram (its control is a parts control), and named (the
 * control gives its part names with part(...), stage 7b).
 * A record of each other item stays as it is.
 */
export function motionOf(item, record) {
  if (!item.kind) return record;
  const kept = record.kept ?? "";
  return { ...record, kind: item.kind, ...(item.entry !== undefined && { entry: item.entry }), template: record.outcome !== "outside" && Boolean(record.kept),
    play: /^play = play\(/m.test(kept), diagram: /^control = parts\(/m.test(kept), named: /^control = parts\(.*\bpart\(/m.test(kept) };
}
/**
 * The numbers of the gate of the motion check (block 2 plan, stage 7): the files clean at the first try (clean0, or outside for an item that
 * expects it), the motion items (screens and changes) whose file is a template file with play, the diagram items whose control is a parts control,
 * and each item with no motion or diagram whose file has play or parts.
 */
export function motionNumbers(records) {
  const of = (...kinds) => records.filter((record) => kinds.includes(record.kind));
  const clean = (record) => record.outcome === "clean0" || (record.expect === "outside" && record.outcome === "outside");
  return { size: records.length, cleanFirst: records.filter(clean).length, motion: of("motion", "change").length, motionPlay: of("motion", "change").filter((record) => record.template && record.play).length,
    diagram: of("diagram").length, diagramParts: of("diagram").filter((record) => record.template && record.diagram).length,
    stillMoved: of("still").filter((record) => record.play || record.diagram).map((record) => record.id), medianFirstCallMs: median(records.map((record) => record.firstCallMs)) };
}
/** The one line of a label of the motion check. */
function motionRow(label, records) {
  const numbers = motionNumbers(records);
  return `${label} (${records[0]?.prompt}, ${numbers.size} records): clean at the first try ${share(numbers.cleanFirst, numbers.size)}, motion files with play ${share(numbers.motionPlay, numbers.motion)},`
    + ` diagrams with parts ${share(numbers.diagramParts, numbers.diagram)}, median first call ${numbers.medianFirstCallMs} ms | still with play or parts: ${numbers.stillMoved.join(", ") || "none"}`
    + ` | ${records.map((record) => `${record.id}:${record.outcome}${record.play ? "+play" : ""}${record.diagram ? "+parts" : ""}${record.named ? "+names" : ""}`).join(" ")}`;
}
/**
 * The numbers of the bars of gate 2 (stage templates plan, Global Constraints) as counts: the explain screens clean after at most one repair, the
 * refusals that got outside, the explain screens that got outside, the median first call of the explain screens in ms, and the changes whose
 * first reply writes no unchanged line again: lines that repeat no stored line, or outside for a change that expects outside (the controller's
 * decision of 2026-11-21). Any outside word is a right refusal, because the app goes to the free plan after each one.
 */
export function templateNumbers(records) {
  const explain = records.filter(TEMPLATE_GROUPS.explain), outcome = (list, test) => list.filter((record) => test.test(record.outcome)).length;
  const onlyChanged = (record) => record.expect === "same" ? record.outcome === "same"
    : (record.replied.kind === "lines" && !record.repeated) || (record.expect === "outside" && record.outcome === "outside");
  return { explainClean: outcome(explain, /^clean[01]$/), refusalsRight: outcome(records.filter(TEMPLATE_GROUPS.refusal), /^outside$/), explainRefused: outcome(explain, /^outside$/),
    medianFirstCallMs: median(explain.map((record) => record.firstCallMs)), changesOnlyChanged: records.filter((record) => TEMPLATE_GROUPS.change(record) && onlyChanged(record)).length };
}
/** The digits of each number of a text with no sign, so that 37,1 and 37.1, or 1 234 and 1234, are the same. */
const digitsOf = (text) => numbersIn(text).map((number) => number.replace(/\D/g, ""));
/**
 * The kind of a value that the host check rejected: "reject?" when the excerpts hold each of its numbers in another writing, a candidate false
 * reject that the controller reads, else "invented".
 */
export function rejectKind(value, excerpts) {
  const known = new Set(digitsOf(excerpts));
  return digitsOf(value).every((digits) => known.has(digits)) ? "reject?" : "invented";
}
/**
 * The numbers of the bars of the data family (block 2 plan, gate of M1). A file is clean at the first try when the first reply compiles with no fault,
 * the host check rejects none of its values, and its form fits the expect of its request: "data" a comparison, a table, or a chart, "nodata" a
 * nodata card, "outside" an outside reply, and "any" each of these. Each rejected value is an invented value or a candidate false reject (rejectKind).
 * The host check shows no rejected value, so a searched form on the screen holds no number that its excerpts do not hold.
 */
export function dataNumbers(records) {
  const fits = (record) => record.expect === "any" || (record.expect === "outside" ? record.outcome === "outside"
    : record.outcome !== "outside" && record.form !== null && (record.expect === "nodata") === (record.form === "nodata"));
  const rejects = records.flatMap((record) => record.steps.filter((step) => step.rejected != null)
    .map((step) => ({ id: record.id, value: step.rejected, kind: rejectKind(step.rejected, record.check?.excerpts ?? "") })));
  // An outside first reply is clean when it fits: fits() takes it only for a request that expects outside or any.
  return { size: records.length, cleanFirst: records.filter((record) => ["clean0", "outside"].includes(record.outcome) && record.steps[0]?.rejected == null && fits(record)).length,
    invented: rejects.filter((reject) => reject.kind === "invented").length, falseRejects: rejects.filter((reject) => reject.kind === "reject?").length,
    medianFirstCallMs: median(records.map((record) => record.firstCallMs)), notFitting: records.filter((record) => !fits(record)).map((record) => `${record.id} ${record.form ?? record.outcome}`), rejects };
}
/** The one line of a label of the data family. */
function dataRow(label, records) {
  const numbers = dataNumbers(records);
  return `${label} (${records[0]?.prompt}, ${records.length} records): clean at the first try ${share(numbers.cleanFirst, numbers.size)}, invented values ${numbers.invented},`
    + ` candidate false rejects ${numbers.falseRejects}, median first call ${numbers.medianFirstCallMs} ms | not fitting: ${numbers.notFitting.join(", ") || "none"}`
    + ` | rejected: ${numbers.rejects.map((reject) => `${reject.id} "${reject.value}" ${reject.kind}`).join(", ") || "none"}`;
}

/**
 * The numbers of a label of the library check (stage 6, M1): its arm, the files clean at the first try, the median time of the first call, and the
 * library names that each file used. A request of set O expects no ready object: a file that uses one is listed.
 */
export function libraryNumbers(records) {
  return { arm: records[0]?.arm, size: records.length, cleanFirst: records.filter((record) => record.outcome === "clean0").length,
    medianFirstCallMs: median(records.map((record) => record.firstCallMs)), names: records.map((record) => `${record.id}:${(record.names ?? []).join("+") || "-"}`),
    objectsWithNoEntry: records.filter((record) => record.set === "O" && (record.names ?? []).some((name) => !(name in ELEMENTS))).map((record) => record.id) };
}
/** The one line of a label of the library check. */
function libraryRow(label, records) {
  const numbers = libraryNumbers(records);
  return `${label} (${records[0]?.prompt}, ${numbers.arm} arm, ${numbers.size} records): clean at the first try ${share(numbers.cleanFirst, numbers.size)},`
    + ` median first call ${numbers.medianFirstCallMs} ms | objects with no entry: ${numbers.objectsWithNoEntry.join(", ") || "none"} | names: ${numbers.names.join(" ")}`;
}

/** The one line of a label of the template check: the five numbers with the size of each group. */
function templateRow(label, records) {
  const numbers = templateNumbers(records), size = (group) => records.filter(TEMPLATE_GROUPS[group]).length;
  return `${label} (${records[0]?.prompt}, ${records.length} records): explain clean ${share(numbers.explainClean, size("explain"))}, refusals right ${share(numbers.refusalsRight, size("refusal"))},`
    + ` explain refused ${share(numbers.explainRefused, size("explain"))}, median first call ${numbers.medianFirstCallMs} ms, changes with only changed lines ${share(numbers.changesOnlyChanged, size("change"))}`;
}

/** A fault with its names, texts, and numbers taken out, so that one kind of fault is one key. */
const faultKind = (fault) => fault.replace(/"(?:[^"\\]|\\.)*"/g, '"…"').replace(/: (need \S+ =|\$\/\S+ :?=|app\(|[\p{L}_][\p{L}\p{N}_]* =) .*$/u, "").replace(/(need|hole) [\p{L}_][\p{L}\p{N}_]*/gu, "$1 X")
  .replace(/\$\/[\p{L}\p{N}_/]+/gu, "$/X").replace(/\b\d+(\.\d+)?\b/g, "N").slice(0, 160);
/** Each kind of fault: how often a report had it, and how often the next report of the same request no longer had it. */
export function faultTable(records) {
  const kinds = new Map();
  for (const record of records) record.steps.forEach((step, index) => {
    const next = record.steps[index + 1]?.faults.map(faultKind);
    for (const fault of new Set(step.faults.map(faultKind))) {
      const row = kinds.get(fault) ?? { count: 0, repaired: 0, kept: 0, last: 0, ids: [] };
      row.count++;
      if (!next) row.last++; else if (next.includes(fault)) row.kept++; else row.repaired++;
      row.ids.push(`${record.id}.${index}`);
      kinds.set(fault, row);
    }
  });
  return [...kinds].sort(([, a], [, b]) => b.count - a.count);
}

// ---- The command line.

async function pool(items, size, work) {
  const queue = [...items];
  await Promise.all(Array.from({ length: size }, async () => { while (queue.length) await work(queue.shift()); }));
}
const recordsOf = (label) => readdirSync(EVIDENCE + label).filter((name) => /\.json$/.test(name)).map((name) => JSON.parse(readFileSync(`${EVIDENCE}${label}/${name}`, "utf8")));

if (import.meta.main) {
  const [phase, ...rest] = process.argv.slice(2);
  if (phase === "probe") {
    mkdirSync(EVIDENCE + "probe", { recursive: true });
    const [model, effort = "low"] = rest;
    const call = await openai(bodyOf({ model, effort, system: PROMPT.System, input: taskText({ request: "A tip calculator: bill, tip percent, and people." }) }), `probe ${model} ${effort}`, `${EVIDENCE}probe/${model}-${effort}.sse`);
    writeFileSync(`${EVIDENCE}probe/${model}-${effort}.json`, JSON.stringify(call, null, 1));
    console.log(JSON.stringify({ ...call, text: call.text.slice(0, 1500) }, null, 1));
  } else if (phase === "run") {
    const [label, set, model, effort, ...ids] = rest, stopAt = Number(process.env.STOP_AT ?? 600), repairs = repairsOf(process.env.REPAIRS);
    // A template run counts under its own cap only with a label that starts with tpl-, and a run of the free prompt never takes that cap.
    const template = [...set].filter((name) => Object.hasOwn(TEMPLATE_SETS, name));
    if (template.length ? template.length !== set.length || !isTemplateLabel(label) : isTemplateLabel(label)) {
      console.log("refused: the sets T, U, V, W, X, Y, L, M, N, O, P, Q, S, G, and H run alone, with a label that starts with tpl-, and only they take such a label"); process.exit(2);
    }
    const items = (template.length ? template.flatMap(templateSet) : [...(set.includes("C") ? corpus() : []), ...(set.includes("D") ? SET_D : []), ...(set.includes("E") ? SET_E : []), ...(set.includes("F") ? SET_F : [])])
      .filter((item) => !ids.length || ids.includes(item.id))
      .filter((item) => !existsSync(`${EVIDENCE}${label}/${item.id}.json`));
    // A template request takes at most two calls: the template call and one repair call.
    const worst = template.length ? items.length * 2 : worstCase(items, repairs), before = spent().calls, templates = spent().log.filter(([one]) => isTemplateLabel(one)).length;
    console.log(`${label}: ${items.length} requests, up to ${template.length ? 1 : repairs} repairs each, worst case ${worst} calls, spent ${before}${template.length ? ` (${templates} of the template check)` : ""}, stop at ${stopAt}`);
    if (before + worst > stopAt) { console.log("refused: the worst case passes the stop mark"); process.exit(2); }
    if (template.length && templates + worst > TEMPLATE_LIMIT) { console.log(`refused: the worst case passes the ${TEMPLATE_LIMIT} calls of the template check`); process.exit(2); }
    mkdirSync(`${EVIDENCE}${label}`, { recursive: true });
    runtime.text = await runtimeText();
    await pool(items, 4, async (item) => {
      const options = { ask: (body) => openai(body, `${label} ${item.id} ${body.reasoning.effort}`), model, effort, repairs };
      const record = motionOf(item, await (!template.length ? checkOne : isDataSet(item.set) ? checkTemplateData : item.base ? checkTemplateChange : checkTemplate)(item, options));
      // A first call that the transport lost leaves no record, so that the next run of the label tries the request again.
      if (/^(http|failed)/.test(record.outcome)) { console.log(item.id, record.outcome, record.calls[0].error); return; }
      record.calls.forEach((call, index) => writeFileSync(`${EVIDENCE}${label}/${item.id}.${index}.txt`, call.text));
      // The kept file of a data request, so that the controller can look at each form (gate 2c).
      if (isDataSet(item.set) && record.kept) writeFileSync(`${EVIDENCE}${label}/${item.id}.plan`, record.kept);
      if (record.definition) record.look = looksOf({ x: record.definition }).x;
      writeFileSync(`${EVIDENCE}${label}/${item.id}.json`, JSON.stringify(record, null, 1));
      if (isDataSet(item.set)) console.log(item.id.padEnd(28), record.outcome.padEnd(8), `first ${record.firstCallMs} ms`, record.form ?? record.replied.word ?? "",
        record.steps.map((step) => step.rejected).filter((value) => value != null).map((value) => `rejected "${value}"`).join(" "), record.faults.slice(0, 2).join(" | ").slice(0, 200));
      else if (template.length) console.log(item.id.padEnd(28), record.outcome.padEnd(8), `first ${record.firstCallMs} ms`, record.names ? `use ${record.names.join("+") || "-"}` : "", record.replied.kind === "lines" ? `${record.replied.lines} lines, ${record.repeated} repeated` : record.replied.word ?? "", record.faults.slice(0, 2).join(" | ").slice(0, 200));
      else console.log(item.id.padEnd(28), record.outcome.padEnd(8), `first ${record.calls[0].wallS}s`, `path ${record.pathS}s`, record.first.word ?? "", (record.steps.at(-1)?.faults ?? []).slice(0, 2).join(" | ").slice(0, 200));
    });
    console.log(`spent ${spent().calls}`);
  } else if (phase === "table") {
    const detail = rest.includes("--detail"), labels = rest.filter((label) => label !== "--detail");
    for (const label of labels) {
      const records = recordsOf(label);
      if (records.some((record) => isDataSet(record.set))) {
        console.log(dataRow(label, withItemExpect(records)));
        if (detail) for (const [fault, row] of faultTable(records)) console.log(`  ${row.count} ${row.repaired} ${row.kept} ${row.last} | ${fault} | ${row.ids.join(" ")}`);
        continue;
      }
      if (records.some((record) => record.kind)) {
        console.log(motionRow(label, records));
        if (detail) for (const [fault, row] of faultTable(records)) console.log(`  ${row.count} ${row.repaired} ${row.kept} ${row.last} | ${fault} | ${row.ids.join(" ")}`);
        continue;
      }
      if (records.some((record) => record.arm)) {
        console.log(libraryRow(label, records));
        if (detail) for (const [fault, row] of faultTable(records)) console.log(`  ${row.count} ${row.repaired} ${row.kept} ${row.last} | ${fault} | ${row.ids.join(" ")}`);
        continue;
      }
      if (records.some((record) => Object.hasOwn(TEMPLATE_SETS, record.set))) {
        console.log(templateRow(label, withItemExpect(records)));
        if (detail) for (const [fault, row] of faultTable(records)) console.log(`  ${row.count} ${row.repaired} ${row.kept} ${row.last} | ${fault} | ${row.ids.join(" ")}`);
        continue;
      }
      console.log(`## ${label} (${records[0]?.model} ${records[0]?.effort}, ${records.length} records)`);
      const changes = records.filter((record) => record.set === "F");
      for (const row of [...(changes.length ? changeNumbers(changes) : []), ...numbers(records)]) console.log("- " + row);
      if (!detail) continue;
      if (changes.length) console.log("each change case: outcome, first call and first text, reply and plan lines, repeated lines, the compared fields, and the comparison:");
      for (const record of changes) console.log(`  ${record.id} ${record.outcome} ${record.first.word ?? ""} | ${record.calls[0].wallS} s, text ${record.calls[0].firstTextS} s | reply ${record.replyLines} of ${record.planLines} lines,`
        + ` repeated ${record.repeated} | compared ${record.compared.join(" ")} | lost ${(record.lost ?? []).map((each) => each.at).join(" ") || "-"} | restarted ${(record.restarted ?? []).map((each) => each.at).join(" ") || "-"}`
        + ` | hidden ${(record.hidden ?? []).join(" ") || "-"} | gone ${record.gone.join(" ") || "-"} | notes ${record.steps.flatMap((step) => step.notes).join("; ").slice(0, 200) || "-"}`);
      console.log("faults (count, repaired in the next report, kept, in the last report; the record.step of each):");
      for (const [fault, row] of faultTable(records)) console.log(`  ${row.count} ${row.repaired} ${row.kept} ${row.last} | ${fault} | ${row.ids.join(" ")}`);
      console.log("each request that expects a plan and is not clean, and each clean plan that a device bar or a host form refuses:");
      for (const record of records.filter((record) => record.expect === "plan" && (record.clean === null || record.bars.length))) console.log(`  ${record.id} ${record.outcome} ${record.first.word ?? ""} ${(record.bars ?? []).join("; ")}`);
      console.log("each request that expects outside: the word of its reply, or the outcome of its plan:");
      for (const record of records.filter((record) => record.expect === "outside")) console.log(`  ${record.id} ${record.first.word ?? record.outcome}`);
      // The look of each clean plan beside the look of its baseline app. Only the cases of set C have a baseline.
      const clean = records.filter((record) => record.look), cases = new Map(corpus().map((item) => [item.id, item]));
      const baseline = looksOf(Object.fromEntries(clean.filter((record) => cases.get(record.id)?.baseline).map((record) => [record.id, JSON.parse(readFileSync(cases.get(record.id).baseline, "utf8")).spec])));
      console.log("look (hero, fields on the first screen, fields in rows): plan | baseline");
      for (const record of clean) console.log(`  ${record.id} ${record.look.hero} ${record.look.fields} ${record.look.rowFields} | ${baseline[record.id] ? `${baseline[record.id].hero} ${baseline[record.id].fields} ${baseline[record.id].rowFields}` : "none"}`);
    }
  } else console.log("phase? probe, run, or table");
}
