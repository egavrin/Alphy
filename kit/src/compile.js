// The sandbox entry of the kit: an app file in, the report of the host and the app definition out. kit/build.js makes it the one global of the bundle.

import { AppFile } from "./dsl.js";
import { expand } from "./parts.js";
import { report } from "./check.js";
import { emit } from "./emit.js";

/**
 * The faults hold the line faults and the report faults, or, when the report is clean, what the emitter cannot build yet.
 * A definition comes only with no fault. A throw inside the kit is the one fault "kit: <message>".
 * The host reads the result as a promise (AppFileCompiler.kt: compileAppFile(...).then(JSON.stringify)), so the entry stays async.
 */
export async function compileAppFile(text) {
  try {
    const file = new AppFile().feedText(text);
    expand(file);
    const { faults, notes } = report(file);
    if (faults.length) return { faults, notes, definition: null, language: null };
    const emitted = emit(file);
    return { faults: emitted.faults, notes, definition: emitted.definition, language: emitted.language };
  } catch (failure) {
    return { faults: [`kit: ${failure?.message ?? failure}`], notes: [], definition: null, language: null };
  }
}

/** A line of an app file: a need, a state or formula, the header, a check, a delete, or a component. A repair reply keeps only these, so its prose and fences go. */
export const LINE = /^\s*(need\s+[\p{L}_][\p{L}\p{N}_]*\s*=|\$\/[\p{L}\p{N}_/]+\s*:?=|app\s*\(|check\s*\(|delete\s+\S|[\p{L}_][\p{L}\p{N}_]*\s*=)/u;

/**
 * The plan after a repair reply, as a person applies the reply by hand (A0-K1): each delete removes its line, a line with the name of an
 * earlier line replaces it in place, and each other line goes at the end. The text has no delete line, so a name that a delete removed is
 * free again for a later line and for the part of a later need. A delete of a name that no line has goes too.
 * A plan line that the kit could not read goes, because its fault was in the report. A reply line that the kit cannot read stays at the end, so the compile names it.
 */
export function patchAppFile(plan, reply) {
  const file = new AppFile().feedText(plan), unread = reply.split("\n").filter((line) => LINE.test(line) && !file.feed(line)).map((line) => line.trim() + "\n");
  return file.text() + unread.join("");
}
