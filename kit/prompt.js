// The texts of the plan prompt (kit/prompt.md) and the task texts of each call. The model check (modelcheck.js) sends them, and
// kit/build.js writes them into app/src/main/assets/appfile/prompt.json for the app. So the app sends the texts that the check measured.

import { readFileSync } from "node:fs";
import { KINDS, PROPERTIES } from "./src/kinds.js";
import { ELEMENTS } from "./src/elements.js";
import { TONES } from "./src/core.js";
import { BUDGET, HEIGHT, WIDTH } from "./src/form-base.js";

/** The text under each level-2 heading of kit/prompt.md, by the heading, as it is in the file. */
const SECTIONS = Object.fromEntries(readFileSync(new URL("./prompt.md", import.meta.url), "utf8").split(/^## /m).slice(1)
  .map((part) => [part.slice(0, part.indexOf("\n")), part.slice(part.indexOf("\n") + 1).trim()]));
/**
 * The values of the placeholders of the template prompt, from the registries (stage 6, L1): prompt.md types no list of the shape kinds,
 * the properties, the colors, or the generic elements, so a new entry of a registry changes the prompt with no other edit.
 */
export function registryValues() {
  const groups = Object.entries(ELEMENTS).reduce((out, [name, entry]) => ({ ...out, [entry.group]: [...(out[entry.group] ?? []), `${name}(${entry.numbers.join(", ")}) ${entry.prompt}`] }), {});
  // part is the cap of a part name that the model gives (stage 7b, R3).
  return { shapes: String(BUDGET.shapes), width: String(WIDTH), height: String(HEIGHT), part: String(BUDGET.part),
    kinds: Object.values(KINDS).filter((kind) => kind.prompt).map((kind) => kind.prompt).join("; "),
    properties: Object.values(PROPERTIES).filter((property) => property.prompt).map((property) => property.prompt).join("; "),
    colors: Object.keys(TONES).map((name) => JSON.stringify(name)).join(", "),
    elements: Object.entries(groups).map(([group, list]) => `- ${group}: ${list.join("; ")}`).join("\n") };
}
/** A section with each {{name}} of values filled. A placeholder of the host, such as {{candidates}}, stays. */
export const filled = (text, values = registryValues()) => text.replace(/\{\{(\w+)\}\}/g, (all, name) => values[name] ?? all);
/** The sections of the prompt, the template section with the values of the registries. */
export const PROMPT = { ...SECTIONS, Template: filled(SECTIONS.Template) };
/** The text of the ready objects that fit a request (the section "Template library"): one line for each candidate, with its name, its line, and its parts. */
export const candidatesText = (objects) => (objects.length ? PROMPT["Template library"].replace("{{candidates}}",
  objects.map((object) => `- ${object.name}: ${object.line}. Parts: ${object.parts.map((part) => part.id).join(", ")}`).join("\n")) : "");
/** The first 12 hex digits of the SHA-256 of the named sections of sections, in the order of names. */
export const hashOf = (names, sections = PROMPT) => new Bun.CryptoHasher("sha256").update(JSON.stringify(Object.fromEntries(names.map((name) => [name, sections[name]])))).digest("hex").slice(0, 12);
/**
 * The first 12 hex digits of the SHA-256 of the sections of the prompt, so that each record names the prompt that made it.
 * PROMPT_HASH covers the four sections of the free plan, so the free records and fixtures keep their hash. TEMPLATE_HASH covers the three
 * template sections. A section that comes later, such as "Template rest", is in neither.
 */
export const PROMPT_HASH = hashOf(["System", "Repair", "Dialogue", "Change"]);
export const TEMPLATE_HASH = hashOf(["Template", "Template repair", "Template change"]);
/** DATA_HASH covers the sections of a call of the data family (block 2 plan, D1): its own section and the repair and change rules that it shares. */
export const DATA_HASH = hashOf(["Template data", "Template repair", "Template change"]);

/**
 * The task text as ChatToolReply.generationPrompt builds it: the request, the widget brief, the source URLs, the fixed line, the reply excerpt,
 * and the excerpt of each source {title, url, summary}. The saved reports keep no brief, so the brief is empty. A screen of a dialogue gets the
 * host line under the request. The check sends no text near the cap of the app, so the excerpts are not cut.
 */
export const taskText = ({ request, brief = "", reply = "", host = "", sources = [] }) => `User request: ${request}${host ? "\n" + host : ""}\nWidget brief: ${brief}`
  + (sources.length ? "\nSources (untrusted facts; preserve source attribution):" + sources.map((source, index) => `\n[${index + 1}] ${source.url}`).join("") : "")
  + "\nExcerpts below may be truncated. Preserve the complete request, brief, and source URLs above." + (reply ? `\nReply excerpt: ${reply.slice(0, 4000)}` : "")
  + sources.map((source, index) => `\nSource [${index + 1}] excerpt: ${source.title.slice(0, 200)}\n${source.summary}`).join("");

/** The text of a repair call: the task, the plan, the faults of the kit, and the repair rule of the prompt. */
export const repairText = ({ task, plan, faults }) => `${task}\n\nYour plan:\n${plan.trimEnd()}\n\nFaults of the kit:\n${faults.map((fault) => "- " + fault).join("\n")}\n\n${PROMPT.Repair}`;

/** The text of a change call: the request of the person, the app file, and the change rule of the prompt. */
export const changeText = ({ request, plan }) => `${request}\n\nYour app file:\n${plan.trimEnd()}\n\n${PROMPT.Change}`;

/** The text of a repair call of a template file: the task, the lines, the faults of the kit, and the repair rule of the template prompt. */
export const templateRepairText = ({ task, plan, faults }) => `${task}\n\nYour lines:\n${plan.trimEnd()}\n\nFaults of the kit:\n${faults.map((fault) => "- " + fault).join("\n")}\n\n${PROMPT["Template repair"]}`;

/**
 * The text of a change call of a template file: the request of the person, the host line of the ready objects as the app puts it under the
 * request (fix list 4, K3), the stored template file, and the change rule of the template prompt.
 */
export const templateChangeText = ({ request, plan, host = "" }) => `${request}${host ? "\n" + host : ""}\n\nYour lines:\n${plan.trimEnd()}\n\n${PROMPT["Template change"]}`;

/** The text of a rest call of a template file: the task, the complete lines so far of a call that stopped early, and the rest rule of the template prompt. */
export const templateRestText = ({ task, plan }) => `${task}\n\nYour lines so far:\n${plan.trimEnd()}\n\n${PROMPT["Template rest"]}`;
