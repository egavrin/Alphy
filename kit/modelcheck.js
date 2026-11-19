// The model check of the plan prompt outside the app: a model writes a plan for each request, the kit compiles it, and a faulty plan
// gets up to 2 repair calls. Each call is a call of the OpenAI Responses API with the body that OpenAiResponsesWire.kt builds.
//   bun kit/modelcheck.js probe MODEL                          one call with a short request; the raw event stream goes to evidence/modelcheck/probe/
//   bun kit/modelcheck.js run LABEL SET MODEL EFFORT [ID...]   SET is C (the 45 corpus cases), D (8 dialogue requests), or CD. STOP_AT (default 600) refuses a run whose worst case passes it.
//   bun kit/modelcheck.js table LABEL...                       the numbers of each label, from its records
// The key is OPENAI_API_KEY, else its line in ~/.env. The script sends it only to api.openai.com, and writes it nowhere.
// Each call is counted in evidence/modelcheck/spend.json before it is sent. The count never passes LIMIT.

import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { AppFile } from "./src/dsl.js";
import { compileAppFile } from "./src/compile.js";
import { runtime } from "./src/emit.js";
import { runtimeText } from "./build.js";

const ROOT = fileURLToPath(new URL("..", import.meta.url));
const EVIDENCE = ROOT + "evidence/modelcheck/";
/** The calls that the owner approved for this check (task A0, decision 5). */
const LIMIT = 700;
/** The saved reports of today's agent loop: final-30d43aa, and for the 11 cases that it lacks, the first showcase run of 2026-11-16. */
const REPORTS = ["/Users/egavrin/Documents/alpha/alphy-agent-evidence-2026-11-17/final-30d43aa/", "/Users/egavrin/Documents/alpha/alphy-agent-evidence-2026-11-16/showcase1/"];
/** The output limit of the agent call of the app (ToolEngine.kt 131072, capped by OpenAiProfile.maxOutputTokens). */
const MAX_OUTPUT = 128000;
/** The device bars of a clean plan: Bun is about 1.3 times faster than the device, whose compile cap is 3000 ms, and the host refuses a larger start state. */
export const BARS = { compileMs: 2300, stateBytes: 49152 };

/** The text under each level-2 heading of kit/prompt.md, by the heading. */
export const PROMPT = Object.fromEntries(readFileSync(new URL("./prompt.md", import.meta.url), "utf8").split(/^## /m).slice(1)
  .map((part) => [part.slice(0, part.indexOf("\n")), part.slice(part.indexOf("\n") + 1).trim()]));

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

/** The cases of set C: each request of the corpus, the reply text of its saved report, and the path of its baseline app. */
export function corpus() {
  return ["showcase", "holdout"].flatMap((name) => JSON.parse(readFileSync(`${ROOT}tools/agent_corpus/${name}.json`, "utf8")).cases).map((item) => {
    const dir = REPORTS.find((base) => existsSync(`${base}${item.id}/report.json`)), report = dir ? JSON.parse(readFileSync(`${dir}${item.id}/report.json`, "utf8")) : {};
    return { id: item.id, set: "C", request: item.request, tags: item.tags, reply: report.assistant_text ?? "", baseline: dir && existsSync(`${dir}${item.id}/artifact.json`) ? `${dir}${item.id}/artifact.json` : null,
      expect: OUTSIDE_CASES.has(item.id) ? "outside" : "plan" };
  });
}

/**
 * The task text as ChatToolReply.generationPrompt builds it: the request, the widget brief, the fixed line, and the reply excerpt.
 * The saved reports keep no brief, so the brief is empty. A screen of a dialogue gets the host line under the request.
 */
export const taskText = ({ request, brief = "", reply = "", host = "" }) => `User request: ${request}${host ? "\n" + host : ""}\nWidget brief: ${brief}`
  + "\nExcerpts below may be truncated. Preserve the complete request, brief, and source URLs above." + (reply ? `\nReply excerpt: ${reply.slice(0, 4000)}` : "");

/** The text of a repair call: the task, the plan, the faults of the kit, and the repair rule of the prompt. */
export const repairText = ({ task, plan, faults }) => `${task}\n\nYour plan:\n${plan.trimEnd()}\n\nFaults of the kit:\n${faults.map((fault) => "- " + fault).join("\n")}\n\n${PROMPT.Repair}`;

/** The body of a call as OpenAiResponsesWire.wireBody builds it with no JSON mode: the profile's reasoning effort, the model, the instructions, and one user message. */
export const bodyOf = ({ model, effort, system, input, maxTokens = MAX_OUTPUT }) => ({
  reasoning: { effort }, model, stream: true, store: false, include: ["reasoning.encrypted_content"], max_output_tokens: maxTokens,
  instructions: system, input: [{ type: "message", role: "user", content: [{ type: "input_text", text: input }] }],
});

/** The lines of a reply: the one cleaning is that an empty line and a markdown fence line go. Each other text stays, and the kit reports it. */
const linesOf = (text) => text.split("\n").filter((line) => line.trim() && !line.trim().startsWith("```"));
const OUTSIDE = /^\s*outside\(\s*"(\w+)"\s*\)\s*$/;
/** The kind of a reply: "outside" with its word for the one line outside("word"), "plan" for a reply with a header or a need line, else "other". */
export function kindOf(text) {
  const lines = linesOf(text), word = lines.length === 1 ? OUTSIDE.exec(lines[0])?.[1] : undefined;
  if (word) return { kind: "outside", word };
  return { kind: lines.some((line) => /^\s*(app\s*\(|need\s)/.test(line)) ? "plan" : "other" };
}
/** A line of an app file: a need, a state or formula, the header, a check, a delete, or a component. A repair keeps only these. */
const LINE = /^\s*(need\s+[\p{L}_][\p{L}\p{N}_]*\s*=|\$\/[\p{L}\p{N}_/]+\s*:?=|app\s*\(|check\s*\(|delete\s+\S|[\p{L}_][\p{L}\p{N}_]*\s*=)/u;
/**
 * The plan after a repair: the lines that the kit took from the plan, in their order, then each line of the repair reply.
 * A line of the reply replaces the line with its name in place, and a delete line removes one, as the kit feeds lines.
 * A line of the plan that the kit could not read goes, as in the prototype loop: its fault was in the report, and the reply sends it again.
 */
export function merge(plan, reply) {
  const lines = linesOf(reply), kept = lines.filter((line) => LINE.test(line));
  return { text: new AppFile().feedText(plan).text() + kept.join("\n") + "\n", dropped: lines.length - kept.length };
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

async function compile(plan) {
  const started = performance.now(), result = await compileAppFile(plan), compileMs = Math.round((performance.now() - started) * 10) / 10;
  const stateBytes = result.definition ? Buffer.byteLength(JSON.stringify(result.definition.initialState)) : null;
  return { ...result, compileMs, stateBytes };
}

/**
 * One request: the plan call, the compile, and up to `repairs` repair calls at the effort low. ask(body) is the call of the API.
 * The record holds each call with its reply, each report of the kit, the outcome, and the final plan. clean is the step of the first clean report.
 */
export async function checkOne(item, { ask, model, effort, repairs = 2 }) {
  runtime.text ||= await runtimeText();
  const task = taskText(item), calls = [], steps = [];
  const send = async (step, level, input) => {
    const call = await ask(bodyOf({ model, effort: level, system: PROMPT.System, input }));
    calls.push({ step, effort: level, ...call });
    return call;
  };
  const first = await send(0, effort, task), kind = first.status === "completed" ? kindOf(first.text) : { kind: first.status };
  const record = { id: item.id, set: item.set, expect: item.expect, model, effort, task, calls, steps, first: kind, clean: null, plan: null };
  if (kind.kind !== "plan" && kind.kind !== "other") return finish(record);
  let plan = linesOf(first.text).join("\n") + "\n";
  for (let step = 0; ; step++) {
    const result = await compile(plan);
    steps.push({ faults: result.faults, notes: result.notes, compileMs: result.compileMs, stateBytes: result.stateBytes, dropped: step ? calls.at(-1).dropped : 0 });
    record.plan = plan;
    if (!result.faults.length) {
      record.clean = step;
      // The run-time text is the same for each plan, and the raw replies compile again to it, so the record keeps the rest of the definition.
      record.definition = { ...result.definition, source: undefined };
      record.bars = barFaults({ ...result, plan });
      break;
    }
    if (step === repairs) break;
    const call = await send(step + 1, "low", repairText({ task, plan, faults: result.faults }));
    if (call.status !== "completed") break;
    const merged = merge(plan, call.text);
    calls.at(-1).dropped = merged.dropped;
    plan = merged.text;
  }
  return finish(record);
}
function finish(record) {
  const plan = record.plan ?? "";
  record.outcome = record.clean !== null ? `clean${record.clean}` : record.first.kind === "plan" || record.first.kind === "other" ? "not" : record.first.kind;
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
const share = (part, all) => `${part}/${all}`;
/** The look numbers of definitions: score_app of tools/agent_corpus/visual_score.py, as kit/look.js runs it. */
export function looksOf(definitions) {
  const table = "import json, sys\nfrom visual_score import score_app\nprint(json.dumps({k: score_app(d, []) for k, d in json.load(sys.stdin).items()}))";
  const scored = Bun.spawnSync(["python3", "-c", table], { cwd: ROOT + "tools/agent_corpus", stdin: Buffer.from(JSON.stringify(definitions)), stderr: "inherit" });
  if (scored.exitCode) throw new Error("score_app failed");
  return JSON.parse(scored.stdout.toString());
}

/** The numbers of decision 6 for records of one model and effort, as rows of text. */
export function numbers(records) {
  const rows = [];
  for (const set of ["C", "D"]) {
    const planned = records.filter((record) => record.set === set && record.expect === "plan");
    if (!planned.length) continue;
    const at = (step) => planned.filter((record) => record.clean === step).length, bar = planned.filter((record) => record.clean !== null && !record.bars.some((fault) => /^(compile|start state)/.test(fault))).length;
    const firsts = planned.map((record) => record.calls[0].wallS), paths = planned.filter((record) => record.clean !== null).map((record) => record.pathS);
    rows.push(`set ${set}: clean first ${share(at(0), planned.length)}, after 1 repair ${share(at(1), planned.length)}, after 2 ${share(at(2), planned.length)}, not clean ${share(planned.length - at(0) - at(1) - at(2), planned.length)}`
      + ` (outside ${planned.filter((record) => record.first.kind === "outside").length}); clean under the device bars ${share(bar, planned.length)}`
      + ` | first call median ${median(firsts)} s, p90 ${p90(firsts)} s | path to clean median ${median(paths)} s, p90 ${p90(paths)} s`
      + ` | first call output tokens median ${median(planned.map((record) => record.calls[0].usage?.output_tokens ?? 0))}, reasoning ${median(planned.map((record) => record.calls[0].usage?.output_tokens_details?.reasoning_tokens ?? 0))}`);
  }
  const outside = records.filter((record) => record.set === "C" && record.expect === "outside");
  if (outside.length) rows.push(`routing: ${share(outside.filter((record) => record.first.kind === "outside").length, outside.length)} not servable got outside; `
    + `${records.filter((record) => record.set === "C" && record.expect === "plan" && record.first.kind === "outside").map((record) => `${record.id} outside(${record.first.word})`).join(", ") || "no servable case"} went outside wrongly; `
    + `device misses: ${records.filter((record) => DEVICE_CASES.has(record.id) && record.first.kind !== "outside").map((record) => record.id).join(", ") || "none"}`);
  const usage = (field) => records.reduce((sum, record) => sum + record.calls.reduce((inner, call) => inner + (field(call.usage ?? {}) ?? 0), 0), 0);
  rows.push(`calls ${records.reduce((sum, record) => sum + record.calls.length, 0)}, input tokens ${usage((u) => u.input_tokens)} (cached ${usage((u) => u.input_tokens_details?.cached_tokens)}), output ${usage((u) => u.output_tokens)} (reasoning ${usage((u) => u.output_tokens_details?.reasoning_tokens)})`);
  return rows;
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
    const [label, set, model, effort, ...ids] = rest, stopAt = Number(process.env.STOP_AT ?? 600);
    const items = [...(set.includes("C") ? corpus() : []), ...(set.includes("D") ? SET_D : [])].filter((item) => !ids.length || ids.includes(item.id))
      .filter((item) => !existsSync(`${EVIDENCE}${label}/${item.id}.json`));
    // The worst case: each first call, and 2 repairs for each request that expects a plan.
    const worst = items.length + 2 * items.filter((item) => item.expect === "plan").length, before = spent().calls;
    console.log(`${label}: ${items.length} requests, worst case ${worst} calls, spent ${before}, stop at ${stopAt}`);
    if (before + worst > stopAt) { console.log("refused: the worst case passes the stop mark"); process.exit(2); }
    mkdirSync(`${EVIDENCE}${label}`, { recursive: true });
    runtime.text = await runtimeText();
    await pool(items, 4, async (item) => {
      const record = await checkOne(item, { ask: (body) => openai(body, `${label} ${item.id} ${body.reasoning.effort}`), model, effort });
      // A first call that the transport lost leaves no record, so that the next run of the label tries the request again.
      if (/^(http|failed)/.test(record.outcome)) { console.log(item.id, record.outcome, record.calls[0].error); return; }
      record.calls.forEach((call, index) => writeFileSync(`${EVIDENCE}${label}/${item.id}.${index}.txt`, call.text));
      if (record.definition) record.look = looksOf({ x: record.definition }).x;
      writeFileSync(`${EVIDENCE}${label}/${item.id}.json`, JSON.stringify(record, null, 1));
      console.log(item.id.padEnd(28), record.outcome.padEnd(8), `first ${record.calls[0].wallS}s`, `path ${record.pathS}s`, record.first.word ?? "", (record.steps.at(-1)?.faults ?? []).slice(0, 2).join(" | ").slice(0, 200));
    });
    console.log(`spent ${spent().calls}`);
  } else if (phase === "table") {
    for (const label of rest) {
      const records = recordsOf(label);
      console.log(`## ${label} (${records[0]?.model} ${records[0]?.effort}, ${records.length} records)`);
      for (const row of numbers(records)) console.log("- " + row);
    }
  } else console.log("phase? probe, run, or table");
}
