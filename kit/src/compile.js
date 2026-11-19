// The sandbox entry of the kit: an app file in, the report of the host and the app definition out. kit/build.js makes compileAppFile,
// patchAppFile, and readPlanReply the globals of the bundle.

import { AppFile } from "./dsl.js";
import { expand } from "./parts.js";
import { report } from "./check.js";
import { emit } from "./emit.js";
import { nextState } from "./change.js";

/** The compile of an app file, and with no fault the expanded file and what the emitter gives. */
function build(text) {
  let hole = false;
  try {
    const file = new AppFile().feedText(text);
    hole = file.of("need").some((entry) => entry.tree.name === "hole");
    expand(file);
    const { faults, notes } = report(file);
    if (faults.length) return { faults, notes, definition: null, language: null, hole };
    const emitted = emit(file);
    return { faults: emitted.faults, notes, definition: emitted.definition, language: emitted.language, hole, file, emitted };
  } catch (failure) {
    return { faults: [`kit: ${failure?.message ?? failure}`], notes: [], definition: null, language: null, hole };
  }
}

/**
 * The faults hold the line faults and the report faults, or, when the report is clean, what the emitter cannot build yet.
 * A definition comes only with no fault. A throw inside the kit is the one fault "kit: <message>".
 * The host reads the result as a promise (AppFileCompiler.kt: compileAppFile(...).then(JSON.stringify)), so the entry stays async.
 * hole is true when the file has a need of kind hole, also with faults: the plan step of the app gives such a plan to its loop.
 * previous is the stored app {appFile, stateSchema, state} of a change: then state is the next state of the stored one (nextState), else null.
 */
export async function compileAppFile(text, previous) {
  const { file, emitted, ...result } = build(text);
  if (!previous || !result.definition) return { ...result, state: null };
  try {
    // A change adds its own notes to the notes of the report.
    const { notes = [], ...change } = nextState(file, emitted, previous, build);
    return { ...result, ...change, notes: [...result.notes, ...notes] };
  } catch (failure) {
    return { ...result, faults: [`kit: ${failure?.message ?? failure}`], definition: null, state: null };
  }
}

/** The lines of a reply: an empty line and a markdown fence line go. Each other text stays, and the kit reports it. */
export const linesOf = (text) => text.split("\n").filter((line) => line.trim() && !line.trim().startsWith("```"));
const OUTSIDE = /^\s*outside\(\s*"([a-z0-9]{1,16})"\s*\)\s*$/;

/**
 * A reply of a plan call: {kind: "outside", word} for the one line outside("word"), else {kind: "plan", text} with the lines of the reply
 * and a final newline. The word is 1 to 16 small Latin letters or digits, such as 3d, and the reader holds no list of words.
 * A reply of prose is a plan too: the compile reports its faults.
 */
export function readPlanReply(text) {
  const lines = linesOf(text), word = lines.length === 1 ? OUTSIDE.exec(lines[0])?.[1] : undefined;
  return word ? { kind: "outside", word } : { kind: "plan", text: lines.join("\n") + "\n" };
}

/** A line of an app file: a need, a state or formula, the header, a check, a delete, or a component. A repair reply keeps only these, so its prose and fences go. */
export const LINE = /^\s*(need\s+[\p{L}_][\p{L}\p{N}_]*\s*=|\$\/[\p{L}\p{N}_/]+\s*:?=|app\s*\(|check\s*\(|delete\s+\S|[\p{L}_][\p{L}\p{N}_]*\s*=)/u;

/**
 * The plan after a repair reply, as a person applies the reply by hand (A0-K1): each delete removes its line, a line with the name of an
 * earlier line replaces it in place, and each other line goes at the end. The text has no delete line, so a name that a delete removed is
 * free again for a later line and for the part of a later need. A delete of a name that no line has goes too.
 * A need line that the kit cannot read keeps its place until a reply writes that need or deletes it (K1 fix 2): else the plan lost the need,
 * could be clean, and the app lacked a thing of the request. Each other plan line that the kit could not read goes, because its fault was in
 * the report and a line that reads it gives a fault. Each other reply line that the kit cannot read stays at the end, so the compile names it.
 */
export function patchAppFile(plan, reply) {
  const file = new AppFile(), unread = [];
  const keep = (line) => {
    const name = /^\s*need\s+([\p{L}_][\p{L}\p{N}_]*)\s*=/u.exec(line)?.[1];
    if (name) file.entries.set("need:" + name, { kind: "need", key: name, raw: line.trim() });
    return name;
  };
  plan.split("\n").forEach((line) => file.feed(line) || keep(line));
  reply.split("\n").filter((line) => LINE.test(line)).forEach((line) => file.feed(line) || keep(line) || unread.push(line.trim() + "\n"));
  return file.text() + unread.join("");
}
