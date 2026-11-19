// The model check of the plan prompt outside the app: a model writes a plan for each request, the kit compiles it, and a faulty plan
// gets up to REPAIRS repair calls (default 2). Each call is a call of the OpenAI Responses API with the body that OpenAiResponsesWire.kt builds.
//   bun kit/modelcheck.js probe MODEL                          one call with a short request; the raw event stream goes to evidence/modelcheck/probe/
//   bun kit/modelcheck.js run LABEL SET MODEL EFFORT [ID...]   SET is C (the 45 corpus cases), D (8 dialogue requests), E (28 fresh requests), F (16 change requests),
//                                                              or a combination such as CD. STOP_AT (default 600) refuses a run whose worst case passes it.
//                                                              REPAIRS is the count of repair calls. A change case of set F gets one repair call.
//   bun kit/modelcheck.js table LABEL...                       the numbers of each label, from its records
// The key is OPENAI_API_KEY, else its line in ~/.env. The script sends it only to api.openai.com, and writes it nowhere.
// Each call is counted in evidence/modelcheck/spend.json before it is sent. The count never passes LIMIT.

import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { BLOCKS, LINE, MISMATCH, compileAppFile, linesOf, patchAppFile, readPlanReply } from "./src/compile.js";
import { runtime } from "./src/emit.js";
import { handle } from "./src/run.js";
import { runtimeText } from "./build.js";
import { PROMPT, PROMPT_HASH, changeText, repairText, taskText } from "./prompt.js";

const ROOT = fileURLToPath(new URL("..", import.meta.url));
const EVIDENCE = ROOT + "evidence/modelcheck/";
/** The calls that the owner approved for the model checks: A0 used 328, H1 28, and P2 82 (438 in all), and task 2b adds at most 64 (its decision 4). */
const LIMIT = 502;
/** The saved reports of today's agent loop: final-30d43aa, and for the 11 cases that it lacks, the first showcase run of 2026-11-16. */
const REPORTS = ["/Users/egavrin/Documents/alpha/alphy-agent-evidence-2026-11-17/final-30d43aa/", "/Users/egavrin/Documents/alpha/alphy-agent-evidence-2026-11-16/showcase1/"];
/** The output limit of the agent call of the app (ToolEngine.kt 131072, capped by OpenAiProfile.maxOutputTokens). */
const MAX_OUTPUT = 128000;
/** The device bars of a clean plan: Bun is about 1.3 times faster than the device, whose compile cap is 3000 ms, and the host refuses a larger start state. */
export const BARS = { compileMs: 2300, stateBytes: 49152 };

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

/** The kind of a reply for the numbers: readPlanReply of the kit, and "other" for a plan with no header and no need line. */
export function kindOf(text) {
  const reply = readPlanReply(text);
  if (reply.kind === "outside") return reply;
  return { kind: /^\s*(app\s*\(|need\s)/m.test(reply.text) ? "plan" : "other" };
}
/** The kind of a change reply: outside(...), or lines that change the app file, as the change call of the app reads it with readPlanReply. */
export function changeKindOf(text) {
  const reply = readPlanReply(text);
  return reply.kind === "outside" ? reply : { kind: "lines" };
}
/** The plan after a repair: patchAppFile of the kit, as the loop of the app calls it. dropped counts the prose lines of the reply. */
export function merge(plan, reply) {
  return { text: patchAppFile(plan, reply), dropped: linesOf(reply).filter((line) => !LINE.test(line)).length };
}

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

const HEAD = "\nreturn handle(input, ";
/** The compiled data of a definition: the JSON that its source gives the run-time. */
const dataOf = (definition) => JSON.parse(definition.source.slice(definition.source.lastIndexOf(HEAD) + HEAD.length, -2));
/** The fields of the person in compiled data: the value of each line, and each object of the inner values of a need (compile.js nextState). */
const ownOf = (data) => [...new Set(data.fields.filter(([root]) => root !== "app:empty").map(([, path]) => path.split(".")[0]))];
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const isRecord = (value) => value !== null && typeof value === "object" && !Array.isArray(value);
const needsIn = (text) => [...text.matchAll(/^\s*need\s+([\p{L}_][\p{L}\p{N}_]*)\s*=/gmu)].map(([, name]) => name);

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
 * compares each key whose stored value differs from its start (rule 3b of compile.js: a start value is no data of the person).
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

async function compile(plan, previous) {
  const started = performance.now(), result = await compileAppFile(plan, previous), compileMs = Math.round((performance.now() - started) * 10) / 10;
  const stateBytes = result.definition ? Buffer.byteLength(JSON.stringify(result.definition.initialState)) : null;
  return { ...result, compileMs, stateBytes };
}

/**
 * One request: the plan call, the compile, and up to `repairs` repair calls at the effort low. ask(body) is the call of the API.
 * The record holds each call with its reply, each report of the kit, the outcome, and the final plan. clean is the step of the first clean report.
 * A change case (set F) sends the request and the app file of its stored app (changeText), merges the reply into the app file, compiles each plan
 * against the stored state, and gets one repair call. Its record also holds the stored state, the next state, and the comparison of the two (keptOf).
 */
export async function checkOne(item, { ask, model, effort, repairs = 2 }) {
  runtime.text ||= await runtimeText();
  const stored = item.set === "F" ? await storedOf(item) : null, limit = stored ? CHANGE_REPAIRS : repairs;
  const task = stored ? changeText(item) : taskText(item), calls = [], steps = [];
  const send = async (step, level, input) => {
    const call = await ask(bodyOf({ model, effort: level, system: PROMPT.System, input }));
    calls.push({ step, effort: level, ...call });
    return call;
  };
  const first = await send(0, effort, task), kind = first.status !== "completed" ? { kind: first.status } : stored ? changeKindOf(first.text) : kindOf(first.text);
  const record = { id: item.id, set: item.set, dialogue: Boolean(item.host), expect: item.expect, model, effort, repairs: limit, prompt: PROMPT_HASH, task, calls, steps, first: kind, clean: null, plan: null };
  let result = null;
  const done = () => finish(stored ? Object.assign(record, { fixture: item.fixture, request: item.request, events: item.events.length, stored: stored.previous.state,
    compared: stored.fields, inner: stored.inner }, changeOf(item, stored, first.text, record.plan, record.clean !== null && result.definition)) : record);
  if (!["plan", "other", "lines"].includes(kind.kind)) return done();
  let plan = stored ? merge(item.plan, first.text).text : readPlanReply(first.text).text;
  for (let step = 0; ; step++) {
    result = await compile(plan, stored?.previous);
    steps.push({ faults: result.faults, notes: result.notes, compileMs: result.compileMs, stateBytes: result.stateBytes, dropped: step ? calls.at(-1).dropped : 0,
      ...(stored && { blocks: result.faults.filter((fault) => fault.includes(BLOCKS)).length, mismatch: result.faults.filter((fault) => fault.includes(MISMATCH)).length }) });
    record.plan = plan;
    if (!result.faults.length) {
      record.clean = step;
      // The run-time text is the same for each plan, and the raw replies compile again to it, so the record keeps the rest of the definition.
      record.definition = { ...result.definition, source: undefined };
      record.bars = barFaults({ ...result, plan });
      if (stored) Object.assign(record, { state: result.state }, keptOf(stored, result.state, dataOf(result.definition)));
      break;
    }
    if (step === limit) break;
    const call = await send(step + 1, "low", repairText({ task, plan, faults: result.faults }));
    if (call.status !== "completed") break;
    const merged = merge(plan, call.text);
    calls.at(-1).dropped = merged.dropped;
    plan = merged.text;
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

/** The outcome of a record: cleanN, not, or the kind of a first reply that got no compile. A change whose final plan is the app file as it was is unchanged. */
function finish(record) {
  const plan = record.plan ?? "";
  record.outcome = record.same ? "unchanged" : record.clean !== null ? `clean${record.clean}` : ["plan", "other", "lines"].includes(record.first.kind) ? "not" : record.first.kind;
  record.lines = plan ? linesOf(plan).length : 0;
  record.bytes = Buffer.byteLength(plan);
  record.pathS = Math.round((record.calls.reduce((sum, call) => sum + (call.wallS ?? 0), 0) + record.steps.reduce((sum, step) => sum + step.compileMs / 1000, 0)) * 100) / 100;
  return record;
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
/** Counts one call before it is sent. JavaScript runs one worker at a time, so the read and the write of the count do not interleave. */
function count(label) {
  const state = spent();
  if (state.calls >= LIMIT) throw new Error(`the limit of ${LIMIT} calls is reached`);
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
  return [`clean at the first try ${share(of("clean0").length, all)}, after the repair ${share(of("clean1").length, all)}, not clean ${share(of("not").length, all)} (${ids(of("not"))}),`
    + ` unchanged ${share(of("unchanged").length, all)} (${ids(of("unchanged"))}), outside ${share(of("outside").length, all)} (${ids(of("outside"), (record) => `${record.id} ${record.first.word}`)})`,
  `reply lines of the ${lines.length} replies of lines: ${spread(lines, (record) => record.replyLines)}; plan lines ${spread(lines, (record) => record.planLines)};`
    + ` replies that repeat a line of the plan: ${ids(lines.filter((record) => record.repeated), (record) => `${record.id} ${record.repeated} of ${record.replyLines}`)}`,
  `first call ${spread(records, (record) => record.calls[0].wallS)} s | first text ${spread(records, (record) => record.calls[0].firstTextS)} s | whole path ${spread(records, (record) => record.pathS)} s`,
  `first call output tokens median ${median(output)} (reasoning ${median(reasoning)}), reasoning ${sum(reasoning)} of ${sum(output)} output tokens of the first calls`,
  `stored values of the lines stayed in ${share(clean.filter((record) => !record.lost.length).length, clean.length)} clean plans; lost or moved: ${listed("lost")}; kept but not shown: ${listed("hidden")}; inner values started again: ${listed("restarted")}`,
  `faults with BLOCKS: ${ids(steps("blocks"), String)}; with MISMATCH: ${ids(steps("mismatch"), String)}; needs and list columns that the final plan lacks: ${ids(records.filter((record) => record.gone?.length), (record) => `${record.id} ${record.gone.join(" ")}`)}`];
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
    const items = [...(set.includes("C") ? corpus() : []), ...(set.includes("D") ? SET_D : []), ...(set.includes("E") ? SET_E : []), ...(set.includes("F") ? SET_F : [])]
      .filter((item) => !ids.length || ids.includes(item.id))
      .filter((item) => !existsSync(`${EVIDENCE}${label}/${item.id}.json`));
    const worst = worstCase(items, repairs), before = spent().calls;
    console.log(`${label}: ${items.length} requests, up to ${repairs} repairs each, worst case ${worst} calls, spent ${before}, stop at ${stopAt}`);
    if (before + worst > stopAt) { console.log("refused: the worst case passes the stop mark"); process.exit(2); }
    mkdirSync(`${EVIDENCE}${label}`, { recursive: true });
    runtime.text = await runtimeText();
    await pool(items, 4, async (item) => {
      const record = await checkOne(item, { ask: (body) => openai(body, `${label} ${item.id} ${body.reasoning.effort}`), model, effort, repairs });
      // A first call that the transport lost leaves no record, so that the next run of the label tries the request again.
      if (/^(http|failed)/.test(record.outcome)) { console.log(item.id, record.outcome, record.calls[0].error); return; }
      record.calls.forEach((call, index) => writeFileSync(`${EVIDENCE}${label}/${item.id}.${index}.txt`, call.text));
      if (record.definition) record.look = looksOf({ x: record.definition }).x;
      writeFileSync(`${EVIDENCE}${label}/${item.id}.json`, JSON.stringify(record, null, 1));
      console.log(item.id.padEnd(28), record.outcome.padEnd(8), `first ${record.calls[0].wallS}s`, `path ${record.pathS}s`, record.first.word ?? "", (record.steps.at(-1)?.faults ?? []).slice(0, 2).join(" | ").slice(0, 200));
    });
    console.log(`spent ${spent().calls}`);
  } else if (phase === "table") {
    const detail = rest.includes("--detail"), labels = rest.filter((label) => label !== "--detail");
    for (const label of labels) {
      const records = recordsOf(label);
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
