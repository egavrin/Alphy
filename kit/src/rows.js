// The rows of a List as Alphy's list, and the value forms that a list shares with the screen and the state: the Alphy names of
// the kit's names, a time as minutes, a date as a real date, and a shown value in the type of its field. run.js builds the
// screen and gives each List to rows.

import { dayNumber } from "./core.js";

/** Alphy's rule for a state field and an action id (ToolValueSchema.name, ToolUiCatalog.registerAction). */
const SAFE = /^[A-Za-z][A-Za-z0-9_]{0,47}$/;
const REFUSED = new Set(["__proto__", "prototype", "constructor"]);
/** A row of Alphy has at most 12 flat fields with its id, and a list at most 100 rows (ToolValueSchema). */
export const MAX_ROW_FIELDS = 12;
export const MAX_ROWS = 100;
/** The Alphy type of each kind of row field: a time is minutes, and a date is a "YYYY-MM-DD" text. */
export const ALPHY = { time: "number", date: "string" };

/**
 * The Alphy names of the kit's names, in both directions: map.safe by key and map.kit by Alphy name. A key gets the name that
 * it wishes when Alphy takes that name and no other key has it, else f<index>. With the map of the compiled data it only looks up.
 */
export function namer(map = { safe: {}, kit: {} }) {
  let next = 0;
  return (key, wish = key) => {
    if (Object.hasOwn(map.safe, key)) return map.safe[key];
    let name = wish;
    // d, v, d2, and so on are the objects of the shown values.
    if (!SAFE.test(name) || REFUSED.has(name) || /^[dv]\d*$/.test(name) || Object.hasOwn(map.kit, name)) {
      while (Object.hasOwn(map.kit, "f" + next)) next++;
      name = "f" + next;
    }
    map.safe[key] = name;
    map.kit[name] = key;
    return name;
  };
}

const pad = (number) => String(number).padStart(2, "0");
/** A time of the kit, "HH:MM", as Alphy's minutes from 0 to 1439. A text that is not a time is 0. */
const minutesOf = (text) => {
  const match = /^(\d{1,2}):(\d{2})$/.exec(String(text ?? "").trim());
  return match && Number(match[1]) < 24 && Number(match[2]) < 60 ? Number(match[1]) * 60 + Number(match[2]) : 0;
};
export const clockOf = (minutes) => { const at = Math.min(1439, Math.max(0, Math.round(Number(minutes) || 0))); return `${pad(Math.floor(at / 60))}:${pad(at % 60)}`; };
/** A value of the kit in the form of its Alphy field. kind is "number", "string", "boolean", "time", or "date". */
export const cast = (kind, value, today) => (kind === "time" ? minutesOf(value) : kind === "date" ? (dayNumber(value) === null ? today : String(value).trim())
  : kind === "number" ? (typeof value === "number" && Number.isFinite(value) ? value : 0) : kind === "boolean" ? value === true
  : value === null || value === undefined || typeof value === "object" ? "" : String(value));
/** A row of the kit as an Alphy row. spec is the field, the Alphy name, and the kind of each stored field (app.lists). */
export const castRow = (row, spec, today) => ({ id: String(row?.id ?? ""), ...Object.fromEntries(spec.map(([field, safe, kind]) => [safe, cast(kind, row?.[field], today)])) });
export const own = (map, key) => (Object.hasOwn(map, key) ? map[key] : undefined);
/** Alphy's state holds a text of at most 2048 characters (ToolValueSchema). */
const MAX_TEXT = 2048;
/**
 * A shown value in the type of its field, which the emitter fixes with the first value (app.types): the stateSchema keeps that
 * type. A number field takes 0 for a value that is not a finite number, and a text field is never null, a list, or a record.
 * Each value stays inside what Alphy's state holds: a number of at most 2^53 - 1 either way, and a text of at most 2048 characters.
 */
export const shownAs = (type, value) => (type === "number" ? (Number.isFinite(value) ? Math.min(Number.MAX_SAFE_INTEGER, Math.max(-Number.MAX_SAFE_INTEGER, value)) : 0)
  : (value !== null && typeof value === "object" ? "" : String(value ?? "")).slice(0, MAX_TEXT));
/** The field name of a path of the row, such as $name, or null. */
export const relative = (expr) => (expr?.k === "path" && !expr.abs && expr.segs.length === 1 ? expr.segs[0] : null);

/**
 * A List as an Alphy list of its stored rows. A row template is the kit's template drawn for a blank row. When the list shows
 * other rows (sort, filter) or values that the run-time computes, it binds a view: a list field that the run-time writes,
 * with the rows in the kit's order. A row control of the view changes the stored row with the same id. screen is what the
 * walk of the screen shares: the kit, the names, the walk, the day, the stored fields and lists, and the outputs of views and lists.
 */
export function rows(screen, node, key, line, expr, at, title) {
  const { kit, name, walk, day, fields, lists, views, viewSchemas, viewOwners, listRoots } = screen;
  const source = node.source, root = source?.k === "path" && source.abs && source.segs.length === 1 && lists[source.segs[0]] ? source.segs[0] : null;
  if (!root || !node.blank) { at.fault("a List shows the rows of a list of the app, such as $/rows, or sort or filter of it. Other rows are not built yet"); return null; }
  const spec = lists[root], rowName = namer();
  rowName("$id", "id");
  spec.forEach(([field, safe]) => rowName("$" + field, safe));
  const scope = { key, name: rowName, stored: new Map(spec.map(([field, safe, kind]) => [field, [safe, kind]])), computed: new Map(), flags: { moves: false }, out: null, id: null };
  const blank = { id: "", ...Object.fromEntries(spec.map(([field, , kind]) => [field, kind === "number" ? 0 : kind === "boolean" ? false : ""])) };
  const keyOf = (template) => template.name ?? `${key}_row`, template = node.blank(blank);
  const rowTemplate = walk(template, keyOf(template), line, { scope, weighted: false });
  const group = expr.group, plain = scope.stored.get(relative(group));
  const groupBy = group ? (plain && plain[1] !== "time" ? plain[0] : rowName("~group", "group")) : null;
  if (group && !(plain && plain[1] !== "time")) scope.computed.set(groupBy, "string");
  // The rows in their stored order: the stored list, or a filter of it.
  const inOrder = (items) => (items?.k === "path" ? items.abs && items.segs.length === 1 && items.segs[0] === root : items?.k === "call" && items.name === "filter" && inOrder(items.args[0]));
  const reorder = scope.flags.moves;
  if (reorder && (group || !inOrder(expr.items))) at.fault("the arrows of a row move it in the stored list, so the list shows its rows in their stored order: no sort and no group");
  const label = reorder ? rowName("~label", "label") : null;
  if (label) scope.computed.set(label, "string");
  const shown = node.children.filter((child) => child.row).map((child) => {
    const out = {};
    walk(child, keyOf(child), line, { scope: { ...scope, out, id: String(child.row.id ?? "") }, weighted: false });
    if (scope.computed.has(groupBy)) { try { out[groupBy] = shownAs("string", kit.value(group, { it: child.row })); } catch { out[groupBy] = ""; } }
    // Alphy reads the label of a row to a person who moves it, so it is never blank: the first text of the row, else its id.
    if (label) out[label] = (spec.filter(([, , kind]) => kind === "string").map(([field]) => cast("string", child.row[field]).trim()).find(Boolean) ?? String(child.row.id)).slice(0, 300);
    return { ...castRow(child.row, spec, day), ...out };
  });
  const view = !(expr.items?.k === "path" && inOrder(expr.items)) || scope.computed.size > 0, field = view ? name("view:" + key, key) : fields.get(root);
  if (view) {
    const schema = { id: "string", ...Object.fromEntries(spec.map(([, safe, kind]) => [safe, own(ALPHY, kind) ?? kind])), ...Object.fromEntries(scope.computed) };
    const count = Object.keys(schema).length;
    if (count > MAX_ROW_FIELDS) at.fault(`the rows of this list have ${count} fields with the id and the values that a row shows, and a row of Alphy has at most ${MAX_ROW_FIELDS}. Show fewer values in a row`);
    views[field] = shown;
    viewSchemas[field] = { type: "list", maxItems: MAX_ROWS, fields: schema };
    viewOwners.push(line);
  }
  const move = reorder && at.action((value) => {
    const list = kit.read(source, {}), from = list.findIndex((row) => row.id === value?.sourceKey), to = list.findIndex((row) => row.id === value?.destinationKey);
    return from < 0 || to < 0 ? false : kit.fire({ props: { action: { k: "call", name: "move", args: [{ k: "lit", v: to - from }], named: {} } }, row: list[from], list: source });
  }, "_move");
  const out = { type: groupBy ? "groupedList" : "list", text: at.caption(title), bind: field, ...(groupBy && { groupBy }), ...(node.props.empty && { emptyText: at.plain(node.props.empty).slice(0, 300) }),
    ...(move && { reorder: { action: move, labelField: label } }), rowTemplate };
  listRoots.set(out, root);
  return out;
}
