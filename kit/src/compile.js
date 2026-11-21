// The compile of the kit: an app file in, the report of the host and the app definition out. kit/build.js makes compileStep (one step of
// the host with compileAppFile, patchAppFile, and readPlanReply) the one global of the bundle: the sandbox entry.

import { AppFile, HEADS } from "./dsl.js";
import { expand } from "./parts.js";
import { report } from "./check.js";
import { emit } from "./emit.js";
import { nextState } from "./change.js";
import { buildTemplate, expandTemplate, isTemplateFile, mergeTemplate, readTemplate } from "./template.js";

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
 * The host reads the result through compileStep as a promise (AppFileCompiler.kt: compileStep(...).then(JSON.stringify)).
 * hole is true when the file has a need of kind hole, also with faults: the plan step of the app gives such a plan to its loop.
 * previous is the stored app {appFile, stateSchema, state} of a change: then state is the next state of the stored one (nextState), else null.
 * blockedBySavedData is true when a saved value of the person blocks the change (each fault holds BLOCKS), and savedDataMismatch is true for
 * the one fault MISMATCH. nextState sets them where it makes these faults, so the host reads the two flags and never the texts of the faults.
 * A template file (template.js) also gives template (its word), kept (the text to store), expanded (the app file of kept, null on a whole fault), and leftOut (the names
 * of the lines that it left out). Its definition comes also with the faults of the lines that it left out, and is null only on a whole fault.
 * A template file as previous.appFile is the app file of its expansion for the change rules.
 */
export async function compileAppFile(text, previous) {
  const storedTemplate = isTemplateFile(previous?.appFile), template = isTemplateFile(text) && buildTemplate(text, storedTemplate ? previous.appFile : null, build);
  const stored = storedTemplate ? { ...previous, appFile: expandTemplate(readTemplate(previous.appFile), new Set()) } : previous;
  const { file, emitted, ...built } = template ? template.built : build(text), result = { ...built, blockedBySavedData: false, savedDataMismatch: false };
  if (template) Object.assign(result, { template: template.word, kept: template.kept, expanded: template.expanded, leftOut: template.leftOut });
  if (!stored || !result.definition) return { ...result, state: null };
  try {
    // A change adds its own notes to the notes of the report.
    // A template keeps the faults of the lines that it left out beside the faults of the change.
    const { notes = [], ...change } = nextState(file, emitted, stored, build);
    return { ...result, ...change, faults: [...result.faults, ...change.faults], notes: [...result.notes, ...notes] };
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

/** A line of an app file: a line that starts with a head of a kind of parseLine (dsl.js HEADS). A repair reply keeps only these, so its prose and fences go. */
export const LINE = new RegExp(`^\\s*(?:${Object.values(HEADS).map((head) => head.source).join("|")})`, "u");
/** A need line with its name as group 1, also a need line that the kit cannot read. */
export const NEED_NAME = new RegExp(`^\\s*${HEADS.need.source}`, "u");
/** The name of each need line of a text, also of a need line that the kit cannot read. */
export const needsIn = (text) => text.split("\n").map((line) => NEED_NAME.exec(line)?.[1]).filter(Boolean);

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
    const name = NEED_NAME.exec(line)?.[1];
    if (name) file.entries.set("need:" + name, { kind: "need", key: name, raw: line.trim() });
    return name;
  };
  plan.split("\n").forEach((line) => file.feed(line) || keep(line));
  reply.split("\n").filter((line) => LINE.test(line)).forEach((line) => file.feed(line) || keep(line) || unread.push(line.trim() + "\n"));
  return file.text() + unread.join("");
}

/**
 * One step of the host (AppFileCompiler.kt) in one call: the reply text as readPlanReply reads it when read is true, where an outside
 * reply returns at once; then the reply merged into plan by patchAppFile when plan is not null; then compileAppFile of the merged text
 * with the stored app previous. A merged text of more than cap characters gives result null and no compile.
 * A template plan (template.js) takes the reply by mergeTemplate in place of patchAppFile.
 * previous is {appFile, stateSchema, state} with stateSchema and state as JSON text, as the host sends them, or null. A text that is no
 * JSON makes the step reject. The result is {kind: "outside", word} or {kind: "plan", text, result}, where text is the merged text.
 */
export async function compileStep({ text, plan, read, previous, cap }) {
  const reply = read ? readPlanReply(text) : { kind: "plan", text };
  if (reply.kind !== "plan") return reply;
  const merged = plan === null ? reply.text : isTemplateFile(plan) ? mergeTemplate(plan, reply.text) : patchAppFile(plan, reply.text);
  const stored = previous && { appFile: previous.appFile, stateSchema: JSON.parse(previous.stateSchema), state: JSON.parse(previous.state) };
  return { kind: "plan", text: merged, result: merged.length > cap ? null : await compileAppFile(merged, stored) };
}
