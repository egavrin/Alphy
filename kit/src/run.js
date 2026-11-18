// The run-time of a compiled app. It runs inside the app's source in the sandbox (kit/build.js): for each event, the kit's
// App reads the lines of the app, takes the stored state, runs the event, and gives the next state and screen.
// The emitter (emit.js) builds the first screen and the first state with the same screen and stateOf functions.

import { AppFile, show } from "./dsl.js";
import { App, dayNumber, given, settings } from "./core.js";

/** Alphy's rule for a state field and an action id (ToolValueSchema.name, ToolUiCatalog.registerAction). */
const SAFE = /^[A-Za-z][A-Za-z0-9_]{0,47}$/;
const REFUSED = new Set(["__proto__", "prototype", "constructor"]);
/** An object of Alphy holds at most 32 fields, so the shown values go into d, d2, d3, and the tests into v, v2. */
export const PER_OBJECT = 32;
/** A row of Alphy has at most 12 flat fields with its id, a list at most 100 rows, and an array at most 512 values (ToolValueSchema). */
export const MAX_ROW_FIELDS = 12;
export const MAX_ROWS = 100;
export const MAX_ARRAY = 512;
/** The fields that a person types into. An add form with more than two of them is a bottom sheet. A Dialog that holds any field is one too. */
export const TYPED = new Set(["TextField", "NumberField", "DateField", "TimeField"]);
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

/** The values of the app line: app("Title", lang="en", kind="Lesson", preset="clean", palette="teal"). */
export function header(kit) {
  const tree = kit.file.get("app")?.tree, read = (arg) => (arg ? kit.value(arg, {}) : undefined);
  return { title: read(tree?.args[0]), ...Object.fromEntries(Object.entries(tree?.named ?? {}).map(([key, arg]) => [key, read(arg)])) };
}

const pad = (number) => String(number).padStart(2, "0");
/** A time of the kit, "HH:MM", as Alphy's minutes from 0 to 1439. A text that is not a time is 0. */
const minutesOf = (text) => {
  const match = /^(\d{1,2}):(\d{2})$/.exec(String(text ?? "").trim());
  return match && Number(match[1]) < 24 && Number(match[2]) < 60 ? Number(match[1]) * 60 + Number(match[2]) : 0;
};
const clockOf = (minutes) => { const at = Math.min(1439, Math.max(0, Math.round(Number(minutes) || 0))); return `${pad(Math.floor(at / 60))}:${pad(at % 60)}`; };
/** A value of the kit in the form of its Alphy field. kind is "number", "string", "boolean", "time", or "date". */
const cast = (kind, value, today) => (kind === "time" ? minutesOf(value) : kind === "date" ? (dayNumber(value) === null ? today : String(value).trim())
  : kind === "number" ? (typeof value === "number" && Number.isFinite(value) ? value : 0) : kind === "boolean" ? value === true
  : value === null || value === undefined || typeof value === "object" ? "" : String(value));
/** A row of the kit as an Alphy row. spec is the field, the Alphy name, and the kind of each stored field (app.lists). */
const castRow = (row, spec, today) => ({ id: String(row?.id ?? ""), ...Object.fromEntries(spec.map(([field, safe, kind]) => [safe, cast(kind, row?.[field], today)])) });
const today = (kit) => kit.value({ k: "call", name: "today", args: [] }, {});

const STYLES = { eyebrow: { emphasis: "label", tone: "accent" }, muted: { tone: "muted" } };
/** The kit's icons that Alphy has under a different name (ToolUiCatalog.icons). ArrowDown has no glyph of its own there. */
const ICON_NAMES = { ArrowUp: "Arrow", ArrowDown: "Sort", ArrowLeft: "Back", ArrowRight: "Chevron", Edit: "Pencil" };
/** What a row template of Alphy cannot hold (ToolUiCatalog: no list, dialog, or sheet in a row). */
const NOT_IN_ROW = new Set(["List", "Dialog"]);
const own = (map, key) => (Object.hasOwn(map, key) ? map[key] : undefined);
const defined = (props, keys) => Object.fromEntries(keys.filter((key) => props[key] !== undefined).map((key) => [key, props[key]]));
const choice = (n, at) => {
  const options = Array.isArray(n.props.options) ? n.props.options : [];
  return { type: options.length <= 6 ? "segmented" : "select", ...at.field(), options: options.map((value) => ({ text: at.plain(value), value })) };
};
/**
 * A number field or a slider. Alphy wants min below max, the value from min to max, and a step above 0 that fits the range (ToolUiCatalog).
 * The template of a row has no value of its own, so each row checks its value. After each event the run-time clamps the value into the bounds.
 */
const ranged = (type, low, high) => (n, at) => {
  const { min = low, max = high, step, value } = n.props;
  if (![min, max].every(Number.isFinite) || !(min < max) || (!at.blank && !(Number.isFinite(value) && value >= min && value <= max)) || (step !== undefined && !(step > 0 && step <= max - min))) {
    at.fault(`a ${n.type} needs min below max, its value from min to max, and a step above 0 and at most max - min. Now min is ${min}, max is ${max}, the value is ${value}${step === undefined ? "" : `, and the step is ${step}`}`);
  }
  at.bounds(min, max);
  return { type, ...at.field(), min, max, ...defined(n.props, ["step"]) };
};

/**
 * Each component of the kit as its Alphy node. at is the walk at the node: its children, its shown values, the field
 * that it changes, and its action. A component that is not here is a fault "is not built yet".
 */
const NODES = {
  Column: (n, at) => ({ type: "column", children: at.children(true) }),
  // An add form with more than two typed fields is a column of a button and the bottom sheet that the button opens.
  Row: (n, at) => at.form() ?? ({ type: "row", ...(n.children.some((child) => child.props.weight !== undefined) && { wrap: false }), children: at.children(true) }),
  Card: (n, at) => ({ type: "card", ...(n.props.variant === "hero" && { variant: "hero" }), children: at.children() }),
  Section: (n, at) => ({ type: "section", text: at.plain(n.props.title), children: at.children() }),
  Text: (n, at) => ({ type: "text", ...at.text("text"), ...(own(STYLES, n.props.variant) && { style: STYLES[n.props.variant] }) }),
  Heading: (n, at) => ({ type: "text", ...at.text("text"), style: { emphasis: "title" } }),
  Metric: (n, at) => ({ type: "metric", text: at.plain(n.props.label), bind: at.shown("value"), ...(n.props.emphasis === "display" && { style: { emphasis: "display" } }) }),
  Badge: (n, at) => ({ type: "badge", ...at.text("value") }),
  // Alphy wants a label on an emoji too: the emoji itself.
  Emoji: (n, at) => ({ type: "emoji", text: at.plain(n.props.value), ...at.value("value") }),
  Divider: () => ({ type: "divider" }),
  // A banner is a container in Alphy's catalog, with 1 to 50 children, so its message is a child.
  Notice: (n, at) => ({ type: "banner", children: [{ type: "text", ...at.text("text") }] }),
  Button: (n, at) => ({ type: "button", text: at.caption(n.props.label), action: at.action(), variant: n.props.selected === undefined || n.props.selected ? "filled" : "tonal" }),
  IconButton: (n, at) => ({ type: "icon", name: own(ICON_NAMES, n.props.icon) ?? n.props.icon, text: at.caption(), action: at.action() }),
  NumberField: ranged("numberInput", 0, 1000000),
  // Alphy gives a slider without bounds 0 to 100.
  Slider: ranged("slider", 0, 100),
  TextField: (n, at) => ({ type: "input", ...at.field(), inputMode: "apply" }),
  Toggle: (n, at) => ({ type: "toggle", ...at.field() }),
  CheckBox: (n, at) => ({ type: "checkbox", ...at.field() }),
  Segmented: choice,
  Select: choice,
  DateField: (n, at) => ({ type: "dateInput", ...at.field() }),
  // Alphy keeps a time as minutes, and the kit as "HH:MM".
  TimeField: (n, at) => ({ type: "timeInput", ...at.field(clockOf) }),
  // max= is the kit's own limit: a choice over it changes nothing.
  Choices: (n, at) => ({ type: "multiChoice", ...at.field((value) => (Array.isArray(value) && !(n.props.max !== undefined && value.length > n.props.max) ? value : undefined)),
    options: (Array.isArray(n.props.options) ? n.props.options : []).map((value) => ({ text: at.plain(value), value })) }),
  Dialog: (n, at) => at.modal(),
  List: (n, at) => at.list(),
};

/**
 * A shown value in the type of its field, which the emitter fixes with the first value (app.types): the stateSchema keeps that
 * type. A number field takes 0 for a value that is not a finite number, and a text field is never null, a list, or a record.
 */
const shownAs = (type, value) => (type === "number" ? (Number.isFinite(value) ? value : 0) : value !== null && typeof value === "object" ? "" : String(value ?? ""));
/** The place and the value that make a visible test false: $/open, !$/closed, or $/pick != "". Other tests have none. */
const falseOf = (test) => (test?.k === "path" && test.abs ? [test, false] : test?.k === "un" && test.op === "!" && test.arg.k === "path" && test.arg.abs ? [test.arg, true]
  : test?.k === "bin" && test.op === "!=" && test.left.k === "path" && test.left.abs && test.right.k === "lit" ? [test.left, test.right.v] : null);
const relative = (expr) => (expr?.k === "path" && !expr.abs && expr.segs.length === 1 ? expr.segs[0] : null);
/** True when an expression reads a field of the row, such as $name. */
const readsRow = (tree) => tree !== null && typeof tree === "object" && ((tree.k === "path" && !tree.abs) || Object.values(tree).some(readsRow));

/**
 * The Alphy screen of the kit's tree in the state of now. app is the compiled data: the field of each state value, the kind of
 * each time, date, list, and array, the type of each shown value, and the names. Returns the ui, the shown values by object
 * (d, v, ...), the views of lists that the run-time writes (views, their schemas, and their lines), the controls by action id,
 * the bounds of each number control, the faults as [line, text], and for the emitter the line of each node of the ui (owners)
 * and the stored list of each list node (listRoots).
 */
export function screen(kit, app) {
  const name = namer(app.names), fields = new Map(app.fields), kinds = app.kinds ?? {}, lists = app.lists ?? {}, day = today(kit);
  const faults = [], controls = new Map(), rowControls = new Map(), owners = new Map(), listRoots = new Map(), bounds = [], views = {}, viewSchemas = {}, viewOwners = [];
  const slots = { d: new Map(), v: new Map() }, values = {};
  /**
   * A value that a node shows: the field of its state line (of its row in a row), or a field that the run-time computes. At the top
   * the field is in d (or in v for a test). In a row it is a field of the row, and the list becomes a view with that field.
   */
  const slot = (kind, key, expr, value, at) => {
    if (value !== null && typeof value === "object") at.fault(`it shows a ${Array.isArray(value) ? "list" : "record"}, and Alphy shows one text or number there. Show one field of it, or join(list)`);
    const scope = at.scope;
    if (scope) {
      const stored = scope.stored.get(relative(expr));
      if (stored && stored[1] !== "time" && (kind === "d" || stored[1] === "boolean")) return stored[0];
      const field = scope.name(`${kind}:${key}`, key.replace(/\W/g, "_"));
      const type = kind === "v" ? "boolean" : (app.types[`${scope.key}/${field}`] ??= typeof value === "number" ? "number" : "string");
      scope.computed.set(field, type);
      if (scope.out) scope.out[field] = kind === "v" ? Boolean(value) : shownAs(type, value);
      return field;
    }
    const root = expr?.k === "path" && expr.abs ? expr.segs : null;
    if (root?.length === 1 && fields.has(root[0]) && kinds[root[0]] !== "time" && (kind === "d" || typeof value === "boolean")) return fields.get(root[0]);
    const field = name(`${kind}:${key}`, root ? root.join("_") : key.replace(/\W/g, "_"));
    if (!slots[kind].has(field)) {
      const object = kind + (slots[kind].size < PER_OBJECT ? "" : Math.floor(slots[kind].size / PER_OBJECT) + 1);
      slots[kind].set(field, `${object}.${field}`);
      (values[object] ??= {})[field] = kind === "v" ? Boolean(value) : shownAs(app.types[field] ??= typeof value === "number" ? "number" : "string", value);
    }
    return slots[kind].get(field);
  };
  /** A control of a row is one action id for all rows: its value {key, value} names the row. */
  const register = (scope, id, run) => {
    if (!scope) return controls.set(id, run);
    if (scope.id === null) return;
    let rows = rowControls.get(id);
    if (!rows) { rowControls.set(id, (rows = new Map())); controls.set(id, (payload) => rows.get(payload?.key)?.(payload.value) ?? false); }
    rows.set(scope.id, run);
  };
  /** ctx: scope is the row of a list (null at the top), sheet is the open flag of the add sheet, weighted is true in a column or a row. */
  const walk = (node, key, line, ctx) => {
    line = node.name ?? line;
    const scope = ctx.scope, map = own(NODES, node.type), fault = (text) => faults.push([line, text]);
    if (!map) { fault(`${node.type} is not built yet`); return null; }
    if (scope && NOT_IN_ROW.has(node.type)) { fault(`a ${node.type} in a row of a list is not built yet`); return null; }
    const expr = node.call ? given(node.call) : {};
    // The arrows of a row, move(-1) and move(1), become the reorder of its list.
    if (scope && expr.action?.k === "call" && expr.action.name === "move" && expr.action.args.length === 1) { scope.flags.moves = true; return null; }
    // Alphy draws one row template for all rows, and a button, a field, or a choice binds no text or options (ToolUiCatalog).
    // shortcut: each field of the row in these values is a fault, also one that gives all rows the same text, such as count($/x, $done); allow those when a plan needs them.
    if (scope) for (const prop of ["label", "title", "icon", "options"]) {
      if (readsRow(expr[prop])) fault(`the ${prop} of a ${node.type} in a row ${prop === "options" ? "change" : "changes"} with the row, and Alphy draws one ${prop} for all rows. Show the value in a Text of the row`);
    }
    // A list in a Section announces the title of the Section: an entries need puts its list into a Section with the title of the need.
    const within = node.type === "Section" ? { ...ctx, title: node.props.title } : ctx;
    const bound = (prop) => (expr[prop] && expr[prop].k !== "lit" ? slot("d", `${key}.${prop}`, expr[prop], node.props[prop], at) : null);
    const at = {
      scope, fault,
      blank: Boolean(scope && !scope.out),
      children: (weighted = false, inner = within) => node.children.map((child, index) => walk(child, child.name ?? `${key}_${index}`, line, { ...inner, weighted })).filter(Boolean),
      // The text of a control: its label, else the kit's name of its line, which a person can read. Never a safe name such as f3.
      caption: (label) => at.plain(label) || (node.name ? node.name : `${line} ${node.props.icon ?? node.type}`).replace(/_/g, " "),
      text: (prop) => { const bind = bound(prop); return bind ? { text: "", bind } : { text: at.plain(node.props[prop]) }; },
      // A text of the screen that is a list or a record is a fault, never "a,b" or "[object Object]".
      plain: (value) => {
        if (value === null || typeof value !== "object") return String(value ?? "");
        fault(`it shows a ${Array.isArray(value) ? "list" : "record"} as a text, and Alphy shows one text there. Show one field of it, or join(list)`);
        return "";
      },
      value: (prop) => { const bind = bound(prop); return bind ? { bind } : { [prop]: node.props[prop] }; },
      shown: (prop) => bound(prop) ?? slot("d", `${key}.${prop}`, null, node.props[prop], at),
      bounds: (min, max) => { if (!at.blank) bounds.push([node, min, max]); },
      field: (convert = (value) => value) => {
        let bind;
        if (scope) {
          const stored = scope.stored.get(relative(expr.bind));
          if (!stored || (stored[1] === "time") !== (node.type === "TimeField") || (node.type === "DateField" && stored[1] !== "date")) {
            fault(`a ${node.type} in a row binds a field of the row of its own kind, such as $name. ${expr.bind ? show(expr.bind) : "nothing"} is not built yet`);
          }
          bind = stored?.[0];
        } else {
          const root = expr.bind?.k === "path" && expr.bind.abs && expr.bind.segs.length === 1 ? expr.bind.segs[0] : null;
          if (!fields.has(root) || (kinds[root] === "time") !== (node.type === "TimeField")) fault(`a binding to ${expr.bind ? show(expr.bind) : "nothing"} is not built yet: a field binds a value of a state line`);
          bind = fields.get(root);
        }
        return { text: at.caption(node.props.label), bind, action: at.action((value) => { const next = convert(value); return next === undefined ? false : kit.change(node, next); }) };
      },
      // An action returns "" or the kit's error text, or false for an event that changes nothing. A press in an add sheet that the kit takes closes the sheet.
      action: (run = () => { const done = kit.fire(node); if (done === "" && ctx.sheet && node.type === "Button") kit.state[ctx.sheet] = false; return done; }, suffix = "") => {
        const id = name("@" + key + suffix, key + suffix);
        register(scope, id, run);
        return id;
      },
      form: () => {
        const flag = `${key}:open`;
        if (!fields.has(flag)) return null;
        const text = at.caption(node.children.find((child) => child.type === "Button")?.props.label);
        const open = (to) => () => { kit.state[flag] = to; return ""; };
        return { type: "column", children: [{ type: "button", text, action: at.action(open(true), "_open"), variant: "filled", icon: "Plus" },
          { type: "bottomSheet", text, visibleBind: fields.get(flag), dismissAction: at.action(open(false), "_dismiss"), children: at.children(false, { ...ctx, sheet: flag }) }] };
      },
      modal: () => {
        if (!expr.visible || expr.visible.k === "lit") fault("a Dialog shows while a test of the app is true, so it needs visible=test, such as visible=$/open");
        const target = falseOf(expr.visible), form = (child) => child.props.bind !== undefined || child.children.some(form);
        // The dismiss of Alphy (a tap outside, the back key) makes the test false, as the buttons of the dialog do.
        const dismiss = at.action(() => { if (!target) return false; kit.write(target[0], target[1], {}); kit.recompute(); return ""; }, "_dismiss");
        return { type: node.children.some(form) ? "bottomSheet" : "dialog", text: at.caption(node.props.title), dismissAction: dismiss, children: at.children() };
      },
      list: () => rows(node, key, line, expr, at, ctx.title),
    };
    const out = map(node, at);
    if (!out) return null;
    owners.set(out, line);
    for (const flag of ["visible", "enabled"]) {
      if (!expr[flag]) continue;
      if (expr[flag].k !== "lit") out[flag + "Bind"] = slot("v", `${key}.${flag}`, expr[flag], node.props[flag], at);
      else if (!node.props[flag]) out[flag] = false;
    }
    // Alphy takes a weight only in a column or a row.
    if (node.props.weight !== undefined && ctx.weighted) out.layout = { weight: node.props.weight };
    return out;
  };
  /**
   * A List as an Alphy list of its stored rows. A row template is the kit's template drawn for a blank row. When the list shows
   * other rows (sort, filter) or values that the run-time computes, it binds a view: a list field that the run-time writes,
   * with the rows in the kit's order. A row control of the view changes the stored row with the same id.
   */
  const rows = (node, key, line, expr, at, title) => {
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
  };
  const { kind, preset, palette } = header(kit), tree = kit.tree(), top = walk(tree, "root", "root", { scope: null, weighted: true });
  const children = [...(kind ? [{ type: "text", text: String(kind), style: STYLES.eyebrow }] : []), ...(tree.type === "Column" ? top?.children ?? [] : [top].filter(Boolean))];
  const ui = { type: "theme", preset: preset ?? "clean", ...(palette && { palette }), cornerRadius: 20, children: [{ type: "column", style: { gap: "medium" }, children }] };
  return { ui, values, views, viewSchemas, viewOwners, controls, bounds, faults, owners, listRoots };
}

/** The Alphy value of a stored value of the kit: a time as minutes, a date as a real date (today for a text that is not one), and each row cast. */
function alphyOf(app, root, value, day) {
  const kind = app.kinds?.[root];
  if (kind === "list") return Array.isArray(value) ? value.map((row) => castRow(row, app.lists[root], day)) : [];
  if (kind === "array") return Array.isArray(value) ? value : [];
  return kind === "time" || kind === "date" ? cast(kind, value, day) : value;
}

/** The Alphy state of the kit's state: each value of a state line in its field, then the views and the shown values. */
export function stateOf(kit, app, shown) {
  const state = {}, day = today(kit);
  for (const [root, path] of app.fields) {
    const [field, inner] = path.split("."), value = alphyOf(app, root, kit.state[root], day);
    if (inner) (state[field] ??= {})[inner] = value; else state[field] = value;
  }
  return { ...state, ...shown.views, ...shown.values };
}

/**
 * Puts an Alphy state into the kit, as each event and the emitter's first screen read it: a time as "HH:MM", each row in the
 * kit's form, and the formulas of the app computed from them.
 */
export function load(kit, app, state) {
  const kinds = app.kinds ?? {};
  let last = kit.nextId - 1;
  for (const [root, path] of app.fields) {
    const [field, inner] = path.split("."), value = inner ? state[field]?.[inner] : state[field];
    if (value === undefined) continue;
    if (kinds[root] === "list") {
      kit.state[root] = (Array.isArray(value) ? value : []).map((row) => {
        last = Math.max(last, Number(/^r(\d+)$/.exec(row.id)?.[1] ?? 0));
        return { id: row.id, ...Object.fromEntries(app.lists[root].map(([kitField, safe, kind]) => [kitField, kind === "time" ? clockOf(row[safe]) : row[safe]])) };
      });
    } else kit.state[root] = kinds[root] === "time" ? clockOf(value) : kinds[root] === "array" && Array.isArray(value) ? [...value] : value;
  }
  // Each event starts a new kit, so a new row gets an id after the highest id of the stored rows.
  kit.nextId = last + 1;
  kit.recompute();
}

/**
 * Answers one event of the sandbox request {artifactId, revision, state, event}. app is the compiled data of emit.js, and now
 * is the clock of the host. A read-only event, an action id that the app does not have, an action that changes nothing, and an
 * action that takes a list past its count of rows (app.caps) or an array past Alphy's limit give the state back as it is. A bound
 * number past its bounds comes back at its bound. The reply holds a new ui only when the screen differs from the screen of the state before the event.
 */
export function handle(input, app, now = Date.now()) {
  const { state, event } = input;
  if (event.type === "preview" || event.type === "render") return { state };
  const clock = settings.now;
  settings.now = () => now;
  try {
    const kit = new App(new AppFile().feedText(app.lines)), kinds = app.kinds ?? {};
    load(kit, app, state);
    const before = screen(kit, app), control = event.type === "action" ? before.controls.get(event.actionId) : undefined;
    if (event.type !== "open" && !control) return { state };
    if (control && control(event.value) === false) return { state };
    for (const [root, kind] of Object.entries(kinds)) {
      if ((kind === "list" && kit.state[root]?.length > (own(app.caps, root) ?? MAX_ROWS)) || (kind === "array" && kit.state[root]?.length > MAX_ARRAY)) return { state };
    }
    let after = control ? screen(kit, app) : before;
    const outside = after.bounds.filter(([node, min, max]) => !(node.props.value >= min && node.props.value <= max));
    if (outside.length) {
      for (const [node, min, max] of outside) kit.write(node.props.bind, Number.isFinite(node.props.value) ? Math.min(max, Math.max(min, node.props.value)) : min, { it: node.row });
      kit.recompute();
      after = screen(kit, app);
    }
    const next = stateOf(kit, app, after);
    return JSON.stringify(after.ui) === JSON.stringify(before.ui) ? { state: next } : { state: next, ui: after.ui };
  } finally { settings.now = clock; }
}
