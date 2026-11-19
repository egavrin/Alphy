// The next state of a changed app (loop plan, task 2): a changed app file and the stored app of the person give the definition and the
// state of the changed app. compileAppFile of compile.js calls nextState when it gets a previous.

import { DslError } from "./dsl.js";
import { App, own } from "./core.js";
import { needsOf } from "./parts.js";
import { ALPHY, cast } from "./rows.js";
import { hostBytes, utf8 } from "./draw.js";
import { MAX_STATE_BYTES, kitOf, screen, stateOf } from "./run.js";
import { MAX_FIELDS, MAX_SOURCE_BYTES, objectWish, rootWish, sourceOf } from "./emit.js";

/** The text of each fault of a change that a saved value of the person blocks (task 2). The host looks for it. */
export const BLOCKS = "the saved data blocks this change";
/** The text of the one fault of a stored state that the previous app file does not give (task 2). The host looks for it. */
export const MISMATCH = "the saved data does not match the app file";

/** The word of each kind of a stored value in a fault of a change. */
const WORDS = { number: "number", string: "text", boolean: "true or false value", time: "time", date: "date", list: "list of rows", record: "record",
  "array:number": "list of numbers", "array:string": "list of texts", "array:boolean": "list of true or false values" };
const isRecord = (value) => value !== null && typeof value === "object" && !Array.isArray(value);
/** A value as a text that does not depend on the order of the keys of its records. */
const canonical = (value) => JSON.stringify(value, (key, inner) => (isRecord(inner) ? Object.fromEntries(Object.keys(inner).sort().map((name) => [name, inner[name]])) : inner));
/** A schema as a text that does not depend on the order of its fields, or on maxItems, which the room of the screen sets. */
const shape = (schema) => canonical(isRecord(schema) ? { ...schema, maxItems: undefined } : schema);
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
 * build is the compile of compile.js, which comes after this module in the bundle: nextState compiles the stored app file with it.
 * 1. The stored app file compiles clean, and its schema and its state hold each value of the person with the same schema, else the one fault MISMATCH.
 * 2. The names of the values of the person are stable (rows.js stable), so a line before a value cannot change its name.
 * 3. A root of a line that stays with the same kind keeps its value. A list keeps its rows by id, and a new column gets the value that the
 *    first append of the list gives a new row, such as the start value of the add form. A root that a change before kept comes back by its name.
 * 3b. A root whose stored value still equals its start value of the previous plan takes the start value of the change, before the kind and column rules.
 *    A list with other rows keeps them, and a note says so when the change has other start rows.
 * 3c. When a list takes other start rows by 3b, the inner values of its need and of each need whose line reads it, also through a formula, start again.
 * 4. A value of another kind, a column that the change removes or retypes in a list with rows, a saved value that the screen would change, and a
 *    new value with the name of a saved one are faults with BLOCKS, as are more than 40 fields, a state of more than 49152 bytes, and a list past its room.
 * 5. Each stored field that the change does not name stays with its schema and its value (app.keep). A list or an array that the app computed stays empty.
 * 6. The values that the app computes come from the changed app. A run of animate goes on by its root, and a drag starts again.
 * 7. The inner values of a need (NAME_s) stay when the need line is the same text and 3c does not apply, else they start again.
 */
export function nextState(file, { app, definition, owners, objects }, previous, build) {
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
  const faults = [], notes = [], start = definition.initialState, oldStart = was.definition.initialState, next = { ...start }, used = new Set();
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
  const moved = new Set();
  for (const [root, field] of newTops) {
    const schema = definition.stateSchema[field], owner = owners.get(field), kind = kindOf(app.kinds[root], schema);
    let from = oldTops.get(root), back = false;
    if (from === undefined && older.has(field) && field === rootWish(root)) { from = field; back = true; }
    if (from === undefined) continue;
    used.add(from);
    // A kept field has only its Alphy type: a time is a number there, and a date a text.
    const value = stored[from], was = back ? kindOf(undefined, schemas[from]) : kindOf(old.kinds[root], oldSchemas[from]), now = back ? own(ALPHY, kind) ?? kind : kind;
    // Rule 3b: a value that still equals its start value of the previous plan is no data of the person yet, so it takes the start value of the change,
    // also of another kind. Rule 3c: a list that gets other start rows so moves the rows that inner values point into.
    if (!back && canonical(value) === canonical(oldStart[from])) {
      if (was === "list" && canonical(start[field]) !== canonical(value)) moved.add(root);
      continue;
    }
    if (Array.isArray(value) && !value.length && was !== now) continue;
    if (was !== now || (kind === "record" && shape(schemas[from]) !== shape(schema))) {
      faults.push(`${owner}: ${BLOCKS}: it holds a saved ${WORDS[was]}, and the change makes it a ${was === now ? "record of other fields" : WORDS[now]}. Keep its kind, or give the new need another name`);
      continue;
    }
    if (kind !== "list") { next[field] = value; continue; }
    if (!back && canonical(start[field]) !== canonical(oldStart[from])) notes.push(`${owner}: the list keeps its ${value.length} saved rows, and the start rows of the change are not used`);
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
  // Rule 3c: a formula that reads a moved list moves too. The need of a moved root and each need whose line reads one start their inner values again.
  const reads = (tree) => !!tree && typeof tree === "object" && ((tree.k === "path" && tree.abs && moved.has(tree.segs[0])) || Object.values(tree).some(reads));
  for (let more = moved.size > 0; more; ) {
    more = false;
    for (const entry of file.of("formula")) if (!moved.has(entry.key.slice(2)) && reads(entry.tree)) { moved.add(entry.key.slice(2)); more = true; }
  }
  const partOf = needsOf(file), restart = new Set([...moved].map((root) => partOf.get("$/" + root)));
  file.of("need").forEach((entry) => reads(entry.tree) && restart.add(entry.key));
  const oldPaths = new Map(old.fields);
  for (const [field, need] of objects) {
    const from = oldObjects.get(need);
    if (from !== undefined) used.add(from);
    // A need that comes back after a change that kept its inner values starts them again: no stored line says that they stay.
    else if (older.has(field) && field === objectWish(need)) used.add(field);
    if (from === undefined || restart.has(need) || lineOf(file, need) !== lineOf(was.file, need)) continue;
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
  return { faults: [], notes, definition: keep.length ? { ...definition, stateSchema: schema, initialState: { ...start, ...kept }, source } : definition, state };
}
