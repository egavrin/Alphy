// The compile of the kit: an app file in, the report of the host and the app definition out. kit/build.js makes compileStep (one step of
// the host with compileAppFile, patchAppFile, and readPlanReply) and previewStep (the frame of the lines so far of a stream) the globals of
// the bundle: the sandbox entries.

import { AppFile, HEADS } from "./dsl.js";
import { settings } from "./core.js";
import { expand } from "./parts.js";
import { report } from "./check.js";
import { handle, kitOf, screen } from "./run.js";
import { dataOf, emit } from "./emit.js";
import { nextState } from "./change.js";
import { buildTemplate, checkData, expandTemplate, isTemplateFile, mergeTemplate, readTemplate } from "./template.js";
import { LIBRARY } from "./library.js";

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
 * The frame that the stage draws for a definition on a state: {ui, state, drawing, holds}. The definition holds no drawing commands, so an open
 * event of the run-time on its compiled data gives the state and the drawing (the surfaces of the reply, {} with no surface), and the screen of
 * that state gives the ui. The frame holds no copy of the definition: the result beside it has it, so a reply of the host holds its source once.
 */
function frameOf(definition, state, holds) {
  const app = dataOf(definition);
  // A moving value (play, animate) shows the state at the start of its run, so each frame of the same lines is the same picture (gate 4).
  const starts = app.anim ? Object.values(state?.[app.anim.field] ?? {}).map((run) => run?.start).filter(Number.isFinite) : [];
  const reply = handle({ artifactId: "preview", revision: 0, state, event: { type: "open" } }, app, Math.min(Date.now(), ...starts));
  return { ui: screen(kitOf(app, reply.state), app).ui, state: reply.state, drawing: reply.surfaces ?? {}, holds };
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
 * A result with a definition has frame (frameOf) with holds "whole". preview is true only for previewStep: a template then keeps the lines so far
 * (template.js buildTemplate), and its frame holds what they draw.
 */
export async function compileAppFile(text, previous, preview = false) {
  const storedTemplate = isTemplateFile(previous?.appFile), template = isTemplateFile(text) && buildTemplate(text, storedTemplate ? previous.appFile : null, build, preview);
  const stored = storedTemplate ? { ...previous, appFile: expandTemplate(readTemplate(previous.appFile), new Set()) } : previous;
  const { file, emitted, ...built } = template ? template.built : build(text), result = { ...built, blockedBySavedData: false, savedDataMismatch: false };
  if (template) Object.assign(result, { template: template.word, kept: template.kept, expanded: template.expanded, leftOut: template.leftOut, misses: template.misses });
  const framed = (out) => (out.definition ? { ...out, frame: frameOf(out.definition, out.state ?? out.definition.initialState, preview && template ? template.holds : "whole") } : out);
  if (!stored || !result.definition) return framed({ ...result, state: null });
  try {
    // A change adds its own notes to the notes of the report.
    // A template keeps the faults of the lines that it left out beside the faults of the change.
    const { notes = [], ...change } = nextState(file, emitted, stored, build);
    return framed({ ...result, ...change, faults: [...result.faults, ...change.faults], notes: [...result.notes, ...notes] });
  } catch (failure) {
    return { ...result, faults: [`kit: ${failure?.message ?? failure}`], definition: null, state: null };
  }
}

/** The lines of a reply: an empty line and a markdown fence line go. Each other text stays, and the kit reports it. */
export const linesOf = (text) => text.split("\n").filter((line) => line.trim() && !line.trim().startsWith("```"));
const OUTSIDE = /^\s*outside\(\s*"([a-z0-9]{1,16})"\s*\)\s*$/;
const SAME = /^\s*same\(\s*\)\s*$/;

/**
 * A reply of a plan call: {kind: "outside", word} for the one line outside("word"), {kind: "same"} for the one line same() of a change
 * that the screen already shows (stage 7b, R2), else {kind: "plan", text} with the lines of the reply and a final newline. The word is
 * 1 to 16 small Latin letters or digits, such as 3d, and the reader holds no list of words.
 * A reply of prose is a plan too: the compile reports its faults.
 */
export function readPlanReply(text) {
  const lines = linesOf(text), word = lines.length === 1 ? OUTSIDE.exec(lines[0])?.[1] : undefined;
  if (lines.length === 1 && SAME.test(lines[0])) return { kind: "same" };
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
 * JSON makes the step reject. The result is {kind: "outside", word}, {kind: "same"}, or {kind: "plan", text, result}, where text is the merged text.
 * data is {excerpts, site, time} for a new searched data screen, or null: the merged text then gets the host check of template.js checkData
 * before the compile, and result.rejected is the value that no excerpt holds, or null.
 */
export async function compileStep({ text, plan, read, previous, cap, data = null, missing = [] }) {
  // An image entry whose image the host cannot open is no entry of this call: its name is a miss (library.js LIBRARY).
  LIBRARY.missing = new Set(missing);
  const reply = read ? readPlanReply(text) : { kind: "plan", text };
  if (reply.kind !== "plan") return reply;
  const { text: merged, rejected } = data ? checkData(mergedOf(plan, reply.text), data) : { text: mergedOf(plan, reply.text), rejected: null };
  const result = merged.length > cap ? null : await compileAppFile(merged, storedOf(previous));
  return { kind: "plan", text: merged, result: data && result ? { ...result, rejected } : result };
}

/** The reply merged into plan (null for a new screen), and the stored app previous with its schema and state read from JSON text. */
const mergedOf = (plan, text) => (plan === null ? text : isTemplateFile(plan) ? mergeTemplate(plan, text) : patchAppFile(plan, text));
const storedOf = (previous) => previous && { appFile: previous.appFile, stateSchema: JSON.parse(previous.stateSchema), state: JSON.parse(previous.state) };

/**
 * The preview step of the host (stage templates design, section 3): {kind: "preview", frame} for the complete lines so far of a reply, with
 * frame {definition, ui, state, drawing, holds}, or {kind: "none"}. text is read as readPlanReply reads it and merged into plan as compileStep merges it, and previous is the stored app of a change
 * as compileStep takes it, so a value that the person moved stays. The compile runs with no check run (settings.gate off) and keeps the lines so far
 * of a template: a line with a fault is left out with no repair (a change keeps its stored version), and the first line alone gives the frame with
 * the picture card at its final size. A free plan gives holds "whole". An outside reply, a text with no usable first line, and each throw give none.
 */
export async function previewStep({ text, plan = null, previous = null, data = null, missing = [] }) {
  LIBRARY.missing = new Set(missing);
  const gate = settings.gate;
  settings.gate = "off";
  try {
    // A searched data form shows only checked values, also while it streams: its frame has the host check of compileStep.
    const merged = (text) => (data ? checkData(mergedOf(plan, text), data).text : mergedOf(plan, text));
    const reply = readPlanReply(text), { definition, frame } = reply.kind === "plan" ? await compileAppFile(merged(reply.text), storedOf(previous), true) : {};
    // A preview has no result beside its frame, so the frame holds the definition.
    return frame ? { kind: "preview", frame: { definition, ...frame } } : { kind: "none" };
  } catch {
    return { kind: "none" };
  } finally {
    settings.gate = gate;
  }
}
