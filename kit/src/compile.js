// The sandbox entry of the kit: an app file in, the report of the host and the app definition out. kit/build.js makes compileAppFile,
// patchAppFile, and readPlanReply the globals of the bundle.

import { AppFile, DslError } from "./dsl.js";
import { App, own } from "./core.js";
import { expand } from "./parts.js";
import { report } from "./check.js";
import { ALPHY, cast } from "./rows.js";
import { hostBytes, utf8 } from "./draw.js";
import { MAX_STATE_BYTES, kitOf, screen, stateOf } from "./run.js";
import { MAX_FIELDS, MAX_SOURCE_BYTES, emit, objectWish, rootWish, sourceOf } from "./emit.js";

/** The text of each fault of a change that a saved value of the person blocks (task 2). The host looks for it. */
export const BLOCKS = "the saved data blocks this change";
/** The text of the one fault of a stored state that the previous app file does not give (task 2). The host looks for it. */
export const MISMATCH = "the saved data does not match the app file";

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
    return { ...result, ...nextState(file, emitted, previous) };
  } catch (failure) {
    return { ...result, faults: [`kit: ${failure?.message ?? failure}`], definition: null, state: null };
  }
}

/** The word of each kind of a stored value in a fault of a change. */
const WORDS = { number: "number", string: "text", boolean: "true or false value", time: "time", date: "date", list: "list of rows", record: "record",
  "array:number": "list of numbers", "array:string": "list of texts", "array:boolean": "list of true or false values" };
const isRecord = (value) => value !== null && typeof value === "object" && !Array.isArray(value);
/** A schema as a text that does not depend on the order of its fields, or on maxItems, which the room of the screen sets. */
const shape = (schema) => JSON.stringify(isRecord(schema) ? { ...schema, maxItems: undefined, fields: schema.fields && Object.fromEntries(Object.entries(schema.fields).sort()) } : schema);
/** The kind of a stored value: the kit's kind of its root (time, date, list, array, record), else the type of its schema. An array names the type of its values. */
const kindOf = (kind, schema) => {
  kind ??= isRecord(schema) ? (schema.type === "object" ? "record" : schema.type) : schema;
  return kind === "array" ? `array:${schema.items}` : kind;
};
/** The top-level field of each root of a line, and the text of each need line. */
const topsOf = (app) => new Map(app.fields.filter(([root, path]) => root !== "app:empty" && !path.includes(".")));
const lineOf = (file, need) => file.of("need").find((entry) => entry.key === need)?.raw.trim();

/**
 * The next state of a changed app, and its definition with each field that it keeps (loop plan, task 2). previous is the stored app.
 * 1. The stored app file compiles clean, and its schema and its state hold each value of the person with the same schema, else the one fault MISMATCH.
 * 2. The names of the values of the person are stable (rows.js stable), so a line before a value cannot change its name.
 * 3. A root of a line that stays with the same kind keeps its value. A list keeps its rows by id, and a new column gets the value that the
 *    first append of the list gives a new row, such as the start value of the add form. A root that a change before kept comes back by its name.
 * 4. A value of another kind, a column that the change removes or retypes in a list with rows, a saved value that the screen would change, and a
 *    new value with the name of a saved one are faults with BLOCKS, as are more than 40 fields, a state of more than 49152 bytes, and a list past its room.
 * 5. Each stored field that the change does not name stays with its schema and its value (app.keep). A list or an array that the app computed stays empty.
 * 6. The values that the app computes come from the changed app. A run of animate goes on by its root, and a drag starts again.
 * 7. The inner values of a need (NAME_s) stay when the need line is the same text, else they start again.
 */
function nextState(file, { app, definition, owners, objects }, previous) {
  const mismatch = { faults: [`kit: ${MISMATCH}`], definition: null, state: null };
  const { appFile, stateSchema: schemas, state: stored } = previous;
  if (typeof appFile !== "string" || !isRecord(schemas) || !isRecord(stored) || Object.keys(schemas).some((field) => !Object.hasOwn(stored, field))) return mismatch;
  const was = build(appFile);
  if (!was.definition) return mismatch;
  const old = was.emitted.app, oldSchemas = was.definition.stateSchema, oldTops = topsOf(old), newTops = topsOf(app);
  const oldObjects = new Map([...was.emitted.objects].map(([field, need]) => [need, field]));
  // The fields of the person: a value of a line, the inner values of a need, and each field that a change before kept.
  const older = new Set(Object.keys(schemas).filter((field) => !Object.hasOwn(oldSchemas, field)));
  const person = new Set([...oldTops.values(), ...was.emitted.objects.keys(), ...older]);
  if ([...oldTops.values(), ...was.emitted.objects.keys()].some((field) => !Object.hasOwn(schemas, field) || shape(schemas[field]) !== shape(oldSchemas[field]))) return mismatch;
  const faults = [], start = definition.initialState, next = { ...start }, used = new Set();
  // The row that the first append of each list adds, as the start values give it, and the day of a date that it does not give.
  const appends = new Map();
  const scan = (tree) => {
    if (!tree || typeof tree !== "object") return;
    const [list, row] = tree.k === "call" && tree.name === "append" ? tree.args : [];
    if (list?.k === "path" && list.abs && list.segs.length === 1 && row?.k === "map" && !appends.has(list.segs[0])) appends.set(list.segs[0], row);
    Object.values(tree).forEach(scan);
  };
  file.of("component").forEach((entry) => scan(entry.tree));
  let fresh;
  const blankOf = (root) => {
    fresh ??= new App(file);
    try { return fresh.value(appends.get(root) ?? { k: "map", entries: [] }, {}) ?? {}; } catch { return {}; }
  };
  for (const [root, field] of newTops) {
    const schema = definition.stateSchema[field], owner = owners.get(field), kind = kindOf(app.kinds[root], schema);
    let from = oldTops.get(root), back = false;
    if (from === undefined && older.has(field) && field === rootWish(root)) { from = field; back = true; }
    if (from === undefined) continue;
    used.add(from);
    // A kept field has only its Alphy type: a time is a number there, and a date a text.
    const value = stored[from], was = back ? kindOf(undefined, schemas[from]) : kindOf(old.kinds[root], oldSchemas[from]), now = back ? own(ALPHY, kind) ?? kind : kind;
    if (Array.isArray(value) && !value.length && was !== now) continue;
    if (was !== now || (kind === "record" && shape(schemas[from]) !== shape(schema))) {
      faults.push(`${owner}: ${BLOCKS}: it holds a saved ${WORDS[was]}, and the change makes it a ${was === now ? "record of other fields" : WORDS[now]}. Keep its kind, or give the new need another name`);
      continue;
    }
    if (kind !== "list") { next[field] = value; continue; }
    // The columns match by their key, and the columns of a kept list by their stored name and Alphy type.
    const columns = back ? Object.entries(schemas[from].fields).filter(([name]) => name !== "id").map(([safe, type]) => [safe, safe, type]) : old.lists[root];
    const target = app.lists[root].map(([key, safe, type]) => [back ? safe : key, safe, back ? own(ALPHY, type) ?? type : type, key, type]);
    if (value.length) for (const [key, , type] of columns) {
      const to = target.find(([match]) => match === key);
      if (!to) faults.push(`${owner}: ${BLOCKS}: the saved rows hold values in the column ${key}, and the change removes it. Keep the column`);
      else if (to[2] !== type) faults.push(`${owner}: ${BLOCKS}: the saved rows hold a ${WORDS[type]} in the column ${key}, and the change makes it a ${WORDS[to[2]]}. Keep its kind, or give the new column another name`);
    }
    const blank = value.length ? blankOf(root) : {}, today = fresh?.value({ k: "call", name: "today", args: [] }, {});
    next[field] = value.map((row) => ({ id: row.id, ...Object.fromEntries(target.map(([match, safe, type, key, kitKind]) => {
      const at = columns.find(([column]) => column === match);
      return [safe, at && at[2] === type ? row[at[1]] : cast(kitKind, blank[key], today)];
    })) }));
  }
  const oldPaths = new Map(old.fields);
  for (const [field, need] of objects) {
    const from = oldObjects.get(need);
    if (from !== undefined) used.add(from);
    // A need that comes back after a change that kept its inner values starts them again: no stored line says that they stay.
    else if (older.has(field) && field === objectWish(need)) used.add(field);
    if (from === undefined || lineOf(file, need) !== lineOf(was.file, need)) continue;
    next[field] = { ...start[field] };
    for (const [root, path] of app.fields) {
      const [object, key] = path.split("."), [oldObject, oldKey] = oldPaths.get(root)?.split(".") ?? [];
      if (object === field && key && oldObject === from && oldKey && typeof stored[from][oldKey] === definition.stateSchema[field].fields[key]) next[field][key] = stored[from][oldKey];
    }
  }
  if (app.anim && old.anim) next[app.anim.field] = Object.fromEntries(app.anim.keys.map(([root, key]) => {
    const at = old.anim.keys.find(([moved]) => moved === root);
    return [key, (at && stored[old.anim.field]?.[at[1]]) ?? start[app.anim.field][key]];
  }));
  // A new value can take the name of a stored field only when that field holds nothing of the person: an object of shown values, or an empty list.
  for (const field of Object.keys(definition.stateSchema)) {
    if (person.has(field) && !used.has(field) && !/^[dv]\d*$/.test(field) && !(Array.isArray(stored[field]) && !stored[field].length)) {
      faults.push(`${owners.get(field) ?? "app"}: ${BLOCKS}: the change gives the name ${field} to a new value, and a saved value has that name. Give the line another name`);
    }
  }
  const keep = Object.keys(schemas).filter((field) => !Object.hasOwn(definition.stateSchema, field));
  const schema = { ...definition.stateSchema, ...Object.fromEntries(keep.map((field) => [field, schemas[field]])) };
  if (!faults.length && Object.keys(schema).length > MAX_FIELDS) {
    faults.push(`${[...owners].find(([field]) => !Object.hasOwn(schemas, field))?.[1] ?? "app"}: ${BLOCKS}: the app would keep more than ${MAX_FIELDS} values`);
  }
  if (faults.length) return { faults, definition: null, state: null };
  // A list or an array that the app computed, such as the view of a sorted list, stays empty: the app computes it again when it comes back.
  const kept = Object.fromEntries(keep.map((field) => [field, Array.isArray(stored[field]) && !person.has(field) ? [] : stored[field]]));
  const full = keep.length ? { ...app, keep } : app, source = keep.length ? sourceOf(full) : definition.source;
  // The budget of the kit can stop the work in a part with a catch of its own, so kit.tripped is the stop too, as in handle.
  let kit, shown, stop = null;
  try {
    kit = kitOf(full, next);
    shown = screen(kit, full);
  } catch (failure) {
    if (!(failure instanceof DslError)) throw failure;
    stop = failure.message;
  }
  if (stop !== null || kit.tripped) return { faults: [`app: ${BLOCKS}: the app stops on the saved data: ${stop ?? kit.told ?? "it needs more work than Alphy gives an app"}`], definition: null, state: null };
  const fieldOf = new Map(app.fields);
  for (const [node, value] of shown.fixes) {
    const bind = node.props.bind, root = bind.abs ? bind.segs[0] : Object.keys(app.lists).find((list) => kit.state[list]?.includes(node.row));
    faults.push(`${owners.get(fieldOf.get(root)?.split(".")[0]) ?? "app"}: ${BLOCKS}: the saved value ${JSON.stringify(node.props.value)} is not one of the options or inside the bounds of the change, and the app would change it to ${JSON.stringify(value)}. Keep the saved value possible`);
  }
  const state = stateOf(kit, full, shown, kept);
  for (const [root, room] of Object.entries(app.caps)) {
    const rows = state[fieldOf.get(root)].length;
    if (rows > room) faults.push(`${owners.get(fieldOf.get(root))}: ${BLOCKS}: it holds ${rows} saved rows, and the change has room for ${room} rows in each list. Show fewer controls or options in a row`);
  }
  if (hostBytes(state) > MAX_STATE_BYTES) faults.push(`app: ${BLOCKS}: the app would keep more than ${MAX_STATE_BYTES} bytes`);
  if (utf8(source) > MAX_SOURCE_BYTES) faults.push(`app: ${BLOCKS}: the fields that the app keeps make its source more than ${MAX_SOURCE_BYTES} bytes`);
  if (faults.length) return { faults, definition: null, state: null };
  return { faults: [], definition: keep.length ? { ...definition, stateSchema: schema, initialState: { ...start, ...kept }, source } : definition, state };
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
