// The texts of the plan prompt (kit/prompt.md) and the task texts of each call. The model check (modelcheck.js) sends them, and
// kit/build.js writes them into app/src/main/assets/appfile/prompt.json for the app. So the app sends the texts that the check measured.

import { readFileSync } from "node:fs";

/** The text under each level-2 heading of kit/prompt.md, by the heading. */
export const PROMPT = Object.fromEntries(readFileSync(new URL("./prompt.md", import.meta.url), "utf8").split(/^## /m).slice(1)
  .map((part) => [part.slice(0, part.indexOf("\n")), part.slice(part.indexOf("\n") + 1).trim()]));
/** The first 12 hex digits of the SHA-256 of the sections of the prompt, so that each record names the prompt that made it. */
export const PROMPT_HASH = new Bun.CryptoHasher("sha256").update(JSON.stringify(PROMPT)).digest("hex").slice(0, 12);

/**
 * The task text as ChatToolReply.generationPrompt builds it: the request, the widget brief, the fixed line, and the reply excerpt.
 * The saved reports keep no brief, so the brief is empty. A screen of a dialogue gets the host line under the request.
 */
export const taskText = ({ request, brief = "", reply = "", host = "" }) => `User request: ${request}${host ? "\n" + host : ""}\nWidget brief: ${brief}`
  + "\nExcerpts below may be truncated. Preserve the complete request, brief, and source URLs above." + (reply ? `\nReply excerpt: ${reply.slice(0, 4000)}` : "");

/** The text of a repair call: the task, the plan, the faults of the kit, and the repair rule of the prompt. */
export const repairText = ({ task, plan, faults }) => `${task}\n\nYour plan:\n${plan.trimEnd()}\n\nFaults of the kit:\n${faults.map((fault) => "- " + fault).join("\n")}\n\n${PROMPT.Repair}`;

/** The text of a change call: the request of the person, the app file, and the change rule of the prompt. */
export const changeText = ({ request, plan }) => `${request}\n\nYour app file:\n${plan.trimEnd()}\n\n${PROMPT.Change}`;
