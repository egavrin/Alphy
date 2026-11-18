// The run-time of a compiled app. It runs inside the app's source in the sandbox (kit/build.js): for each event, the kit's
// App reads the lines of the app, takes the stored state, runs the event, and gives the next state and screen.
// The emitter (emit.js) builds the first screen and the first state with the same screen and stateOf functions.

import { AppFile, show } from "./dsl.js";
import { App, given, settings } from "./core.js";

/** Alphy's rule for a state field and an action id (ToolValueSchema.name, ToolUiCatalog.registerAction). */
const SAFE = /^[A-Za-z][A-Za-z0-9_]{0,47}$/;
const REFUSED = new Set(["__proto__", "prototype", "constructor"]);
/** An object of Alphy holds at most 32 fields, so the shown values go into d, d2, d3, and the tests into v, v2. */
const PER_OBJECT = 32;

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

const STYLES = { eyebrow: { emphasis: "label", tone: "accent" }, muted: { tone: "muted" } };
/** The kit's icons that Alphy has under a different name (ToolUiCatalog.icons). ArrowDown has no glyph of its own there. */
const ICON_NAMES = { ArrowUp: "Arrow", ArrowDown: "Sort", ArrowLeft: "Back", ArrowRight: "Chevron", Edit: "Pencil" };
const own = (map, key) => (Object.hasOwn(map, key) ? map[key] : undefined);
const defined = (props, keys) => Object.fromEntries(keys.filter((key) => props[key] !== undefined).map((key) => [key, props[key]]));
const choice = (n, at) => {
  const options = Array.isArray(n.props.options) ? n.props.options : [];
  return { type: options.length <= 6 ? "segmented" : "select", ...at.field(), options: options.map((value) => ({ text: String(value), value })) };
};

/**
 * Each component of the kit as its Alphy node. at is the walk at the node: its children, its shown values, the field
 * that it changes, and its action. A component that is not here is a fault "is not built yet".
 */
const NODES = {
  Column: (n, at) => ({ type: "column", children: at.children() }),
  Row: (n, at) => ({ type: "row", ...(n.children.some((child) => child.props.weight !== undefined) && { wrap: false }), children: at.children() }),
  Card: (n, at) => ({ type: "card", ...(n.props.variant === "hero" && { variant: "hero" }), children: at.children() }),
  Section: (n, at) => ({ type: "section", text: String(n.props.title ?? ""), children: at.children() }),
  Text: (n, at) => ({ type: "text", ...at.text("text"), ...(own(STYLES, n.props.variant) && { style: STYLES[n.props.variant] }) }),
  Heading: (n, at) => ({ type: "text", ...at.text("text"), style: { emphasis: "title" } }),
  Metric: (n, at) => ({ type: "metric", text: String(n.props.label ?? ""), bind: at.shown("value"), ...(n.props.emphasis === "display" && { style: { emphasis: "display" } }) }),
  Badge: (n, at) => ({ type: "badge", ...at.text("value") }),
  // Alphy wants a label on an emoji too: the emoji itself.
  Emoji: (n, at) => ({ type: "emoji", text: String(n.props.value ?? ""), ...at.value("value") }),
  Divider: () => ({ type: "divider" }),
  // A banner is a container in Alphy's catalog, with 1 to 50 children, so its message is a child.
  Notice: (n, at) => ({ type: "banner", children: [{ type: "text", ...at.text("text") }] }),
  Button: (n, at) => ({ type: "button", text: String(n.props.label ?? ""), action: at.action(), variant: n.props.selected === undefined || n.props.selected ? "filled" : "tonal" }),
  IconButton: (n, at) => ({ type: "icon", name: own(ICON_NAMES, n.props.icon) ?? n.props.icon, text: at.action().replace(/_/g, " "), action: at.action() }),
  NumberField: (n, at) => ({ type: "numberInput", ...at.field(), min: n.props.min ?? 0, max: n.props.max ?? 1000000, ...defined(n.props, ["step"]) }),
  Slider: (n, at) => ({ type: "slider", ...at.field(), ...defined(n.props, ["min", "max", "step"]) }),
  TextField: (n, at) => ({ type: "input", ...at.field(), inputMode: "apply" }),
  Toggle: (n, at) => ({ type: "toggle", ...at.field() }),
  CheckBox: (n, at) => ({ type: "checkbox", ...at.field() }),
  Segmented: choice,
  Select: choice,
};

/** A shown value as Alphy keeps it: a number that is not finite is 0, and a text is never null. */
const shownValue = (value) => (typeof value === "number" ? (Number.isFinite(value) ? value : 0) : typeof value === "string" ? value : String(value ?? ""));

/**
 * The Alphy screen of the kit's tree in the state of now. app is the compiled data: the field of each state value and the names.
 * Returns the ui, the shown values by object (d, v, ...), the controls by action id, and the faults of what is not built yet.
 */
export function screen(kit, app) {
  const name = namer(app.names), fields = new Map(app.fields), faults = [], controls = new Map();
  const slots = { d: new Map(), v: new Map() }, values = {};
  // A value that a node shows: the field of its state line, or a field of d (or of v for a test) that the run-time computes.
  const slot = (kind, key, expr, value) => {
    const root = expr?.k === "path" && expr.abs ? expr.segs : null;
    if (root?.length === 1 && fields.has(root[0]) && (kind === "d" || typeof value === "boolean")) return fields.get(root[0]);
    const field = name(`${kind}:${key}`, root ? root.join("_") : key.replace(/\W/g, "_"));
    if (!slots[kind].has(field)) {
      const object = kind + (slots[kind].size < PER_OBJECT ? "" : Math.floor(slots[kind].size / PER_OBJECT) + 1);
      slots[kind].set(field, `${object}.${field}`);
      (values[object] ??= {})[field] = kind === "v" ? Boolean(value) : shownValue(value);
    }
    return slots[kind].get(field);
  };
  const walk = (node, key) => {
    const map = own(NODES, node.type);
    if (!map) { faults.push(`${node.name ?? key}: ${node.type} is not built yet`); return null; }
    const expr = node.call ? given(node.call) : {};
    const bound = (prop) => (expr[prop] && expr[prop].k !== "lit" ? slot("d", `${key}.${prop}`, expr[prop], node.props[prop]) : null);
    const at = {
      children: () => node.children.map((child, index) => walk(child, child.name ?? `${key}_${index}`)).filter(Boolean),
      text: (prop) => { const bind = bound(prop); return bind ? { text: "", bind } : { text: String(node.props[prop] ?? "") }; },
      value: (prop) => { const bind = bound(prop); return bind ? { bind } : { [prop]: node.props[prop] }; },
      shown: (prop) => bound(prop) ?? slot("d", `${key}.${prop}`, null, node.props[prop]),
      field: () => {
        const root = expr.bind?.k === "path" && expr.bind.abs && expr.bind.segs.length === 1 ? expr.bind.segs[0] : null;
        if (!fields.has(root)) faults.push(`${node.name ?? key}: a binding to ${expr.bind ? show(expr.bind) : "nothing"} is not built yet: a field binds a value of a state line`);
        return { text: String(node.props.label ?? ""), bind: fields.get(root), action: at.action() };
      },
      action: () => { const id = name("@" + key, key); controls.set(id, node); return id; },
    };
    const out = map(node, at);
    for (const flag of ["visible", "enabled"]) {
      if (!expr[flag]) continue;
      if (expr[flag].k !== "lit") out[flag + "Bind"] = slot("v", `${key}.${flag}`, expr[flag], node.props[flag]);
      else if (!node.props[flag]) out[flag] = false;
    }
    if (node.props.weight !== undefined) out.layout = { weight: node.props.weight };
    return out;
  };
  const { kind, preset, palette } = header(kit), tree = kit.tree(), top = walk(tree, "root");
  const children = [...(kind ? [{ type: "text", text: String(kind), style: STYLES.eyebrow }] : []), ...(tree.type === "Column" ? top.children : [top].filter(Boolean))];
  const ui = { type: "theme", preset: preset ?? "clean", ...(palette && { palette }), cornerRadius: 20, children: [{ type: "column", style: { gap: "medium" }, children }] };
  return { ui, values, controls, faults };
}

/** The Alphy state of the kit's state: each value of a state line in its field, then the shown values. */
export function stateOf(kit, app, shown) {
  const state = {};
  for (const [root, path] of app.fields) {
    const [field, inner] = path.split(".");
    if (inner) (state[field] ??= {})[inner] = kit.state[root]; else state[field] = kit.state[root];
  }
  return { ...state, ...shown.values };
}

/**
 * Answers one event of the sandbox request {artifactId, revision, state, event}. app is the compiled data of emit.js, and now
 * is the clock of the host. A read-only event and an action id that the app does not have give the state back as it is.
 * The reply holds a new ui only when the screen differs from the screen of the state before the event.
 */
export function handle(input, app, now = Date.now()) {
  const { state, event } = input;
  if (event.type === "preview" || event.type === "render") return { state };
  const clock = settings.now;
  settings.now = () => now;
  try {
    const kit = new App(new AppFile().feedText(app.lines));
    for (const [root, path] of app.fields) {
      const [field, inner] = path.split("."), value = inner ? state[field]?.[inner] : state[field];
      if (value !== undefined) kit.state[root] = value;
    }
    kit.recompute();
    const before = screen(kit, app), control = event.type === "action" ? before.controls.get(event.actionId) : undefined;
    if (event.type !== "open" && !control) return { state };
    if (control?.props.bind) kit.change(control, event.value); else if (control) kit.fire(control);
    const after = control ? screen(kit, app) : before, next = stateOf(kit, app, after);
    return JSON.stringify(after.ui) === JSON.stringify(before.ui) ? { state: next } : { state: next, ui: after.ui };
  } finally { settings.now = clock; }
}
