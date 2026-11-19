// The run-time of a compiled app. It runs inside the app's source in the sandbox (kit/build.js): for each event, the kit's
// App reads the lines of the app, takes the stored state, runs the event, and gives the next state and screen.
// The emitter (emit.js) builds the first screen and the first state with the same screen and stateOf functions.

import { AppFile, parseExpression, show } from "./dsl.js";
import { App, ICON_NAMES, foreground, given, settings } from "./core.js";
import { MAX_ROWS, cast, castRow, clockOf, namer, own, relative, rows, wished } from "./rows.js";
import { BAR_SURFACE, MODEL, STAGE, accentOf, barOf, commandsOf, fit, hostBytes, polygonsOf, sideOf } from "./draw.js";
import { IDLE, dragged } from "./drag.js";

/** False in the run-time text of an app (dsl.js): the run-time reads no fault of the screen. */
const MESSAGES = true;

/** An object of Alphy holds at most 32 fields, so the shown values go into d, d2, d3, and the tests into v, v2. */
export const PER_OBJECT = 32;
/** An array of Alphy holds at most 512 values (ToolValueSchema). */
export const MAX_ARRAY = 512;
/** Alphy keeps a state of at most 49152 bytes (ToolSpec). */
const MAX_STATE_BYTES = 49152;
/** The fields that a person types into. An add form with more than two of them is a bottom sheet. A Dialog that holds any field is one too. */
export const TYPED = new Set(["TextField", "NumberField", "DateField", "TimeField"]);
/** A chart of Alphy draws at most 256 points (ToolUiCatalog), so the run-time writes the last 256. */
export const MAX_POINTS = 256;
/** The alert at the end of the host's countdown, and the capabilities that it asks for (ForegroundTimerSpec.requests). */
const ALERT = { vibrationMs: 600, toneMs: 600 };
export const ALERT_CAPABILITIES = ["audio.tone", "vibration.play"];
/**
 * The host's countdown runs from 1 s to 24 h (ForegroundTimerSpec). A timer that the run-time moves gets one timer event each second,
 * and a value that an animate moves one each 50 ms.
 */
const MIN_TIMER_MS = 1000, MAX_TIMER_MS = 86400000, TICK_MS = 1000, ANIM_MS = 50;
/** The change of a value that no animate moves now. */
const STILL = { from: 0, to: 0, start: 0, end: 0, at: 0 };

/** The values of the app line: app("Title", lang="en", kind="Lesson", preset="clean", palette="teal"). */
export function header(kit) {
  const tree = kit.file.get("app")?.tree, read = (arg) => (arg ? kit.value(arg, {}) : undefined);
  return { title: read(tree?.args[0]), ...Object.fromEntries(Object.entries(tree?.named ?? {}).map(([key, arg]) => [key, read(arg)])) };
}

const today = (kit) => kit.value({ k: "call", name: "today", args: [] }, {});

const STYLES = { eyebrow: { emphasis: "label", tone: "accent" }, muted: { tone: "muted" } };
/** What a row template of Alphy cannot hold (ToolUiCatalog: no list, dialog, sheet, or canvas in a row), and what keeps one state field for all rows. */
const NOT_IN_ROW = new Set(["List", "Dialog", "Chart", "Tabs", "Timer", "Drawing", "Model3d"]);
/** The components of a part that a later task builds: the fault names the kind of the need. */
const LATER = { Picture: "picture", Link: "link" };
/** The share of value in max in percent, from 0 to 100: 0 for a max of 0 or below and for a value that is not finite. */
const percent = (value, max) => (Number.isFinite(value) && Number.isFinite(max) && max > 0 ? Math.min(100, Math.max(0, (value / max) * 100)) : 0);
/** True when an action appends a row: append(...) alone, in a list of actions, or in when(...). */
const appends = (tree) => (tree?.k === "call" ? tree.name === "append" || tree.args.some(appends) : tree?.k === "list" && tree.items.some(appends));
const defined = (props, keys) => Object.fromEntries(keys.filter((key) => props[key] !== undefined).map((key) => [key, props[key]]));
/** The style of a node: its own look (base), then the look options of the plan in keys (check.js checks their values). None when it is empty. */
const styled = (n, keys, base) => { const style = { ...base, ...defined(n.props, keys) }; return Object.keys(style).length ? { style } : {}; };
/** A row of no wrap centers its children across its height, as each row of a list of Alphy's own apps does. align= sets it. */
const rowStyle = (n, cross = n.props.align) => styled(n, ["gap"], cross && { crossAlign: cross });
/** The icon of the plan in Alphy's name: a kit name that differs (ICON_NAMES) becomes Alphy's. check.js checks each name. */
const iconOf = (n) => (n.props.icon === undefined ? {} : { icon: own(ICON_NAMES, n.props.icon) ?? n.props.icon });
/** Alphy shows at most 30 options of a choice, each with a text of 1 to 100 characters (ToolUiCatalog). */
const MAX_OPTIONS = 30, MAX_OPTION_TEXT = 100;
/**
 * The options of a choice as Alphy's options: each value with its label of the plan (core.js labels), else with the value as its text.
 * Alphy takes a value of the type of the value of the choice once, a text that is not blank, and at most 30 options (ToolUiCatalog).
 * The run-time leaves out each option that breaks a rule and cuts a long text, and the compile step names each one. value is the value of the choice, or an item of it.
 */
const optionsOf = (n, at, value) => {
  const given = Array.isArray(n.props.options) ? n.props.options : [], type = typeof (value ?? given[0]), seen = new Set(), out = [];
  given.forEach((option, index) => {
    const text = at.plain(n.props.labels?.[index] ?? option), key = type + option;
    const why = typeof option !== type || !["string", "number", "boolean"].includes(type) || (type === "number" && !Number.isFinite(option)) ? 1 : seen.has(key) ? 2 : !text.trim() ? 3 : out.length === MAX_OPTIONS ? 4 : 0;
    if (MESSAGES && !at.rowOptions && (why || text.length > MAX_OPTION_TEXT)) {
      at.fault(`the option ${JSON.stringify(option)} ${["has a text of " + text.length + " characters", `is not a ${type}, and the value of the choice is one`, "is there two times", "has an empty text", `is one more than the ${MAX_OPTIONS} options that Alphy keeps`][why]}. A choice has 1 to ${MAX_OPTIONS} options, each one with a value of its own of the type of the choice and a text of 1 to ${MAX_OPTION_TEXT} characters`);
    }
    if (why) return;
    seen.add(key);
    out.push({ text: text.slice(0, MAX_OPTION_TEXT), value: option });
  });
  if (MESSAGES && !at.rowOptions && !given.length) at.fault(`a choice needs 1 to ${MAX_OPTIONS} options, and this one has none`);
  return out;
};
// A choice of six options or fewer is a segmented control, which takes no icon (ToolUiCatalog). A choice with no option that Alphy takes is not on the screen.
const choice = (n, at) => {
  const options = optionsOf(n, at, n.props.value), select = options.length > 6;
  if (!options.length) return null;
  at.inside(options);
  return { type: select ? "select" : "segmented", ...at.field(), ...(select && iconOf(n)), options };
};
/**
 * A number field or a slider. Alphy wants min below max, the value from min to max, and a step above 0 that fits the range (ToolUiCatalog).
 * The template of a row has no value of its own, so each row checks its value. After each event the run-time clamps the value into the bounds.
 */
const ranged = (type, low, high, pair) => (n, at) => {
  const { min = low, max = high, step, value } = n.props, inside = (x) => Number.isFinite(x) && x >= min && x <= max;
  // A range is two numbers in order inside the bounds, and Alphy sends both (ToolUiCatalog validRange).
  const fits = pair ? (x) => Array.isArray(x) && x.length === 2 && x.every(inside) && x[0] <= x[1] : inside;
  if (MESSAGES && (![min, max].every(Number.isFinite) || !(min < max) || (!at.blank && !fits(value)) || (step !== undefined && !(step > 0 && step <= max - min)))) {
    at.fault(`a ${n.type} needs min below max, ${pair ? "two numbers in order" : "its value"} from min to max, and a step above 0 and at most max - min. Now min is ${min}, max is ${max}, the value is ${value}${step === undefined ? "" : `, and the step is ${step}`}`);
  }
  // shortcut: an action of the plan that moves a range out of its bounds is not clamped as a number is (handle); the smoke run shows it as a fault of the screen. Clamp it when a plan needs that.
  if (!pair && !inside(value)) at.keep(Number.isFinite(value) ? Math.min(max, Math.max(min, value)) : min);
  return { type, ...at.field(pair && ((x) => (fits(x) ? x : undefined))), min, max, ...defined(n.props, ["step"]), ...iconOf(n) };
};
/** The controls that the host gives a live input (inputMode "live"), each with the test of the value that the host sends for it (ToolUiCatalog). */
const LIVE = { TextField: (value) => typeof value === "string", Slider: Number.isFinite };

/**
 * Each component of the kit as its Alphy node. at is the walk at the node: its children, its shown values, the field
 * that it changes, and its action. A component that is not here is a fault "is not built yet".
 */
const NODES = {
  // An add form with more than two typed fields is a column of a button and the bottom sheet that the button opens, also when an author wrote it as a Row.
  Column: (n, at) => at.form() ?? { type: "column", ...styled(n, ["gap"]), children: at.children(true) },
  // A weighted child needs a row that does not wrap. Alphy draws a row with no wrap=false as a flow row, so that row is a flowRow.
  Row: (n, at) => at.form() ?? (n.children.some((child) => child.props.weight !== undefined) ? { type: "row", wrap: false, ...rowStyle(n, n.props.align ?? "center"), children: at.children(true) }
    : { type: "flowRow", ...rowStyle(n), children: at.children() }),
  Grid: (n, at) => ({ type: "grid", columns: n.props.columns ?? 2, children: at.children() }),
  Card: (n, at) => ({ type: "card", ...(n.props.variant === "hero" && { variant: "hero" }), ...iconOf(n), children: at.children() }),
  Section: (n, at) => ({ type: "section", text: at.plain(n.props.title), children: at.children() }),
  // A tone or an emphasis of the plan wins over the one of its variant.
  Text: (n, at) => ({ type: "text", ...at.text("text"), ...styled(n, ["emphasis", "align", "tone"], own(STYLES, n.props.variant)) }),
  Heading: (n, at) => ({ type: "text", ...at.text("text"), style: { emphasis: "title" } }),
  Metric: (n, at) => ({ type: "metric", text: at.plain(n.props.label), bind: at.shown("value"), ...iconOf(n), ...styled(n, ["emphasis", "tone"]) }),
  Badge: (n, at) => ({ type: "badge", ...at.text("value"), ...iconOf(n), ...styled(n, ["tone"]) }),
  // Alphy wants a label on an emoji too: label=, else the emoji itself. One template draws each row of a list, so a blank row gives the kit's name of the line.
  Emoji: (n, at) => ({ type: "emoji", text: at.plain(n.props.label) || at.plain(n.props.value) || at.caption(), ...at.value("value"), ...defined(n.props, ["size"]) }),
  Divider: () => ({ type: "divider" }),
  // A banner is a container in Alphy's catalog, with 1 to 50 children, so its message is a child.
  Notice: (n, at) => ({ type: "banner", ...iconOf(n), ...styled(n, ["tone"]), children: [{ type: "text", ...at.text("text") }] }),
  // With no variant the host decides: one button of a group is filled and its other buttons with no variant are outlined.
  // A selected button stays filled, also beside a second selected one, and a button that is not selected is tonal.
  Button: (n, at) => {
    const variant = n.props.variant ?? (n.props.selected === undefined ? undefined : n.props.selected ? "filled" : "tonal");
    return { type: "button", text: at.caption(n.props.label), action: at.action(), ...(variant && { variant }), ...iconOf(n) };
  },
  // An IconButton with no action is a sign: Alphy draws its glyph with no button.
  IconButton: (n, at) => ({ type: "icon", name: iconOf(n).icon, text: at.caption(n.props.label), ...(n.props.action && { action: at.action() }) }),
  NumberField: ranged("numberInput", 0, 1000000),
  // Alphy gives a slider without bounds 0 to 100.
  Slider: ranged("slider", 0, 100),
  RangeSlider: ranged("rangeSlider", 0, 100, 1),
  TextField: (n, at) => ({ type: "input", ...at.field(), ...iconOf(n) }),
  Toggle: (n, at) => ({ type: "toggle", ...at.field() }),
  CheckBox: (n, at) => ({ type: "checkbox", ...at.field(), ...defined(n.props, ["variant"]) }),
  Segmented: choice,
  Select: choice,
  DateField: (n, at) => ({ type: "dateInput", ...at.field(), ...iconOf(n) }),
  // Alphy keeps a time as minutes, and the kit as "HH:MM".
  TimeField: (n, at) => ({ type: "timeInput", ...at.field(clockOf), ...iconOf(n) }),
  // max= is the kit's own limit: a choice over it changes nothing.
  Choices: (n, at) => {
    const options = optionsOf(n, at, Array.isArray(n.props.value) ? n.props.value[0] : undefined);
    if (!options.length) return null;
    at.inside(options);
    return { type: "multiChoice", ...at.field((value) => (Array.isArray(value) && !(n.props.max !== undefined && value.length > n.props.max) ? value : undefined)), options };
  },
  Dialog: (n, at) => at.modal(),
  List: (n, at) => at.list(),
  // Alphy's progress binds the share in percent. Dots show the step itself: at most 12, and with one max for all rows of a list.
  // The bar of a timer that the run-time moves is a surface that the run-time draws.
  Progress: (n, at) => {
    const { value, max } = n.props, dots = n.props.variant === "dots" && !at.scope && Number.isInteger(max) && max >= 1 && max <= 12;
    return at.drawn(percent(value, max) / 100) ?? { type: "progress", text: at.label(), variant: dots ? "dots" : "bar", min: 0, max: dots ? max : 100,
      bind: at.computed("value", dots ? (Number.isFinite(value) ? Math.min(max, Math.max(0, value)) : 0) : percent(value, max)) };
  },
  // The host's own countdown (Alphy's foreground timer): the host keeps its time and sends its action to no source.
  Timer: (n, at) => {
    const ms = Math.round(Number(n.props.seconds) * 1000);
    if (MESSAGES && !(ms >= MIN_TIMER_MS && ms <= MAX_TIMER_MS)) at.fault(`a Timer runs from 1 second to 24 hours, and this one has ${n.props.seconds} seconds`);
    return { type: "timer", text: at.label(), mode: "countdown", action: at.action(() => false),
      foreground: { durationMs: Number.isFinite(ms) ? Math.min(MAX_TIMER_MS, Math.max(MIN_TIMER_MS, ms)) : MIN_TIMER_MS, ...ALERT } };
  },
  // A ring takes children only when it is large (ToolUiCatalog): its text is a large text inside it, and the host shows its label under it.
  Ring: (n, at) => ({ type: "progressRing", text: at.label(), size: "large", min: 0, max: 100, bind: at.computed("value", percent(n.props.value, n.props.max)),
    ...(n.props.text !== undefined && { children: [{ type: "text", ...at.text("text"), style: { emphasis: "display" } }] }) }),
  Chart: (n, at) => at.chart(),
  Tabs: (n, at) => at.tabs(),
  // A canvas with one surface, which each reply draws again.
  Drawing: (n, at) => at.canvas(commandsOf(n.props.shapes), sideOf(n.props.width), sideOf(n.props.height)),
  // A 3D model on a surface of its own: the stage and the faces of its bodies. The kit's tree has the bodies taken apart already.
  // A model with a turn gets the pointer events of its surface: a drag turns it.
  Model3d: (n, at) => at.canvas([STAGE, ...polygonsOf(n.props.bodies ?? [], n.props.value ?? 30, false, n.props.selected, MODEL.width, MODEL.height, at.accent)],
    MODEL.width, MODEL.height, false, n.props.bind ? n : undefined),
};

/** The place and the value that make a visible test false: $/open, !$/closed, or $/pick != "". Other tests have none. */
const falseOf = (test) => (test?.k === "path" && test.abs ? [test, false] : test?.k === "un" && test.op === "!" && test.arg.k === "path" && test.arg.abs ? [test.arg, true]
  : test?.k === "bin" && test.op === "!=" && test.left.k === "path" && test.left.abs && test.right.k === "lit" ? [test.left, test.right.v] : null);
/** True when an expression reads a field of the row, such as $name. */
const readsRow = (tree) => tree !== null && typeof tree === "object" && ((tree.k === "path" && !tree.abs) || Object.values(tree).some(readsRow));

/**
 * The Alphy screen of the kit's tree in the state of now. app is the compiled data: the field of each state value, the kind of
 * each time, date, list, and array, the type of each shown value, and the names. Returns the ui, the shown values by object
 * (d, v, ...), the views of lists that the run-time writes (views, their schemas, and their lines), the controls by action id,
 * the value that each number control and each choice keeps after the event (fixes), the faults as [line, text], for the emitter the line of each node of the ui (owners)
 * and the stored list of each list node (listRoots), and each surface that the walk draws as {id, width, height, running, commands, line} (drawn).
 */
export function screen(kit, app, first = false) {
  // The names of the screen avoid the names of the stored fields and the timer id of animate. The emitter's walk and each event's walk start with the same taken names.
  // shortcut: a key that is new at run time could take a name that the emitter gave at its wish; no walk makes new keys now, add them to app.names when one does.
  const name = namer(app.names, new Set([...Object.values(app.names), ...app.fields.flatMap(([, path]) => path.split(".")), ...(app.anim ? [app.anim.field] : []), ...(app.drag ? [app.drag] : [])]));
  const fields = new Map(app.fields), kinds = app.kinds ?? {}, lists = app.lists ?? {}, day = today(kit);
  // The first walk (the emitter's) fixes the type of each shown value with its first value. The compiled data keeps the numbers, and each other shown value is a text.
  const typeOf = (key, value) => (first ? (app.types[key] ??= typeof value === "number" ? "number" : "string") : app.types[key] ?? "string");
  const faults = [], controls = new Map(), liveIds = new Set(), rowControls = new Map(), owners = new Map(), listRoots = new Map(), fixes = [], views = {}, viewSchemas = {}, viewOwners = [];
  const slots = { d: new Map(), v: new Map() }, values = {};
  // The bar of each timer need that the run-time moves, by the name of its line, and each surface that the walk draws.
  const timed = new Map(kit.file.of("need").filter((entry) => entry.tree.name === "timer" && !foreground(entry.tree)).map((entry) => [`${entry.key}_bar`, entry.key]));
  const drawn = [], { kind, preset, palette } = header(kit);
  /**
   * A value that a node shows: the field of its state line (of its row in a row), or a field that the run-time computes. At the top
   * the field is in d (or in v for a test). In a row it is a field of the row, and the list becomes a view with that field.
   */
  const slot = (kind, key, expr, value, at) => {
    if (MESSAGES && value !== null && typeof value === "object") at.fault(`it shows a ${Array.isArray(value) ? "list" : "record"}, and Alphy shows one text or number there. Show one field of it, or join(list)`);
    const scope = at.scope;
    if (scope) {
      const stored = scope.stored.get(relative(expr));
      if (stored && stored[1] !== "time" && (kind === "d" || stored[1] === "boolean")) return stored[0];
      const field = scope.name(`${kind}:${key}`, key.replace(/\W/g, "_"));
      const type = kind === "v" ? "boolean" : typeOf(`${scope.key}/${field}`, value);
      scope.computed.set(field, type);
      if (scope.out) scope.out[field] = kind === "v" ? Boolean(value) : cast(type, value);
      return field;
    }
    const root = expr?.k === "path" && expr.abs ? expr.segs : null;
    if (root?.length === 1 && fields.has(root[0]) && kinds[root[0]] !== "time" && (kind === "d" || typeof value === "boolean")) return fields.get(root[0]);
    const field = name(`${kind}:${key}`, root ? root.join("_") : key.replace(/\W/g, "_"));
    if (!slots[kind].has(field)) {
      const object = kind + (slots[kind].size < PER_OBJECT ? "" : Math.floor(slots[kind].size / PER_OBJECT) + 1);
      slots[kind].set(field, `${object}.${field}`);
      (values[object] ??= {})[field] = kind === "v" ? Boolean(value) : cast(typeOf(field, value), value);
    }
    return slots[kind].get(field);
  };
  /** A control of a row is one action id for all rows: its value {key, value} names the row. */
  const register = (scope, id, run) => {
    if (!scope) return controls.set(id, run);
    if (scope.id === null) return;
    let byRow = rowControls.get(id);
    if (!byRow) { rowControls.set(id, (byRow = new Map())); controls.set(id, (payload) => byRow.get(payload?.key)?.(payload.value) ?? false); }
    byRow.set(scope.id, run);
  };
  /** ctx: scope is the row of a list (null at the top), sheet is the open flag of the add sheet, weighted is true in a column or a row, off is true in a hidden or disabled node. */
  const walk = (node, key, line, ctx) => {
    line = node.name ?? line;
    const scope = ctx.scope, map = own(NODES, node.type), fault = (text) => faults.push([line, text]);
    if (!map) { fault(MESSAGES ? `${own(LATER, node.type) ?? node.type} is not built yet` : ""); return null; }
    if (scope && NOT_IN_ROW.has(node.type)) { fault(MESSAGES ? `a ${node.type} in a row of a list is not built yet` : ""); return null; }
    const expr = node.call ? given(node.call) : {};
    // The host takes no live input for a control that is hidden or disabled, or in a node that is (ToolUiCatalog resolveAction).
    const off = ctx.off || ["visible", "enabled"].some((flag) => expr[flag] && !node.props[flag]);
    // The arrows of a row, move(-1) and move(1), become the reorder of its list.
    if (scope && expr.action?.k === "call" && expr.action.name === "move" && expr.action.args.length === 1) { scope.flags.moves = true; return null; }
    // Alphy draws one row template for all rows, and a button, a field, or a choice binds no text or options (ToolUiCatalog).
    // shortcut: each field of the row in these values is a fault, also one that gives all rows the same text, such as count($/x, $done); allow those when a plan needs them.
    if (MESSAGES && scope) for (const prop of ["label", "title", "icon", "options"]) {
      if (readsRow(expr[prop])) fault(`the ${prop} of a ${node.type} in a row ${prop === "options" ? "change" : "changes"} with the row, and Alphy draws one ${prop} for all rows. Show the value in a Text of the row`);
    }
    // A list in a Section announces the title of the Section: an entries need puts its list into a Section with the title of the need.
    const within = { ...ctx, off, ...(node.type === "Section" && { title: node.props.title }) };
    const bound = (prop) => (expr[prop] && expr[prop].k !== "lit" ? slot("d", `${key}.${prop}`, expr[prop], node.props[prop], at) : null);
    const at = {
      scope, fault, off,
      // Options that read the row are a fault of their own (above), so their rules give no fault more.
      rowOptions: MESSAGES && Boolean(scope) && readsRow(expr.options),
      blank: Boolean(scope && !scope.out),
      children: (weighted = false, inner = within) => node.children.map((child, index) => walk(child, child.name ?? `${key}:${index}`, line, { ...inner, weighted })).filter(Boolean),
      // The text of a control: its label, else the kit's name of its line, which a person can read. Never a safe name such as f3.
      caption: (label) => at.plain(label) || (node.name ? node.name : `${line} ${node.props.icon ?? node.type}`).replace(/_/g, " "),
      // The text of a progress, a ring, a chart, and tabs: its label=, else the title of the Section around it, else the caption. Alphy shows at most 300 characters.
      label: () => at.caption(at.plain(node.props.label) || ctx.title).slice(0, 300),
      text: (prop) => { const bind = bound(prop); return bind ? { text: "", bind } : { text: at.plain(node.props[prop]) }; },
      // A text of the screen that is a list or a record is a fault, never "a,b" or "[object Object]".
      plain: (value) => {
        if (value === null || typeof value !== "object") return String(value ?? "");
        if (MESSAGES) fault(`it shows a ${Array.isArray(value) ? "list" : "record"} as a text, and Alphy shows one text there. Show one field of it, or join(list)`);
        return "";
      },
      value: (prop) => { const bind = bound(prop); return bind ? { bind } : { [prop]: node.props[prop] }; },
      shown: (prop) => bound(prop) ?? at.computed(prop, node.props[prop]),
      // A value that the run-time computes for the node, such as the share of a progress.
      computed: (prop, value) => slot("d", `${key}.${prop}`, null, value, at),
      // The value that the bound place of the node gets after the event (handle): a number back into its bounds, a choice back into its options.
      keep: (value) => { if (!at.blank) fixes.push([node, value]); },
      // A choice keeps a value that is one of its options, else its first option, and a multi-choice each of its values that is an option, once.
      // At the start, a value that is no option is a fault of the line.
      inside: (options) => {
        const value = node.props.value, members = options.map((option) => option.value);
        const kept = node.type !== "Choices" ? (members.includes(value) ? value : members[0]) : (Array.isArray(value) ? value : []).filter((item, index, all) => members.includes(item) && all.indexOf(item) === index);
        if (JSON.stringify(kept) === JSON.stringify(value)) return;
        if (MESSAGES && !at.blank && !at.rowOptions) fault(`the value ${JSON.stringify(value)} of this ${node.type} is not ${node.type === "Choices" ? "a list of its options, each one once" : "one of its options"}`);
        at.keep(kept);
      },
      field: (convert = (value) => value) => {
        let bind;
        if (scope) {
          const stored = scope.stored.get(relative(expr.bind));
          if (MESSAGES && (!stored || (stored[1] === "time") !== (node.type === "TimeField") || (node.type === "DateField" && stored[1] !== "date"))) {
            fault(`a ${node.type} in a row binds a field of the row of its own kind, such as $name. ${expr.bind ? show(expr.bind) : "nothing"} is not built yet`);
          }
          bind = stored?.[0];
        } else {
          const root = expr.bind?.k === "path" && expr.bind.abs && expr.bind.segs.length === 1 ? expr.bind.segs[0] : null;
          if (MESSAGES && (!fields.has(root) || (kinds[root] === "time") !== (node.type === "TimeField"))) fault(`a binding to ${expr.bind ? show(expr.bind) : "nothing"} is not built yet: a field binds a value of a state line`);
          bind = fields.get(root);
        }
        const live = own(LIVE, node.type), action = at.action((value) => {
          if (live && (off || !live(value))) return false;
          const next = convert(value);
          return next === undefined ? false : kit.change(node, next);
        });
        if (live) liveIds.add(action);
        return { text: at.caption(node.props.label), bind, action, ...(live && { inputMode: "live" }) };
      },
      // An action returns "" or the kit's error text, or false for an event that changes nothing. A press of the add button of an add sheet that adds a row
      // closes the sheet: an appended row takes the next id of the kit, and a press that changes only other values leaves the sheet open.
      action: (run = () => { const was = ctx.sheet?.add === node && kit.nextId, done = kit.fire(node); if (done === "" && was && kit.nextId > was) kit.state[ctx.sheet.flag] = false; return done; }, suffix = "") => {
        const id = name("@" + key + suffix, wished(key + suffix));
        register(scope, id, run);
        return id;
      },
      form: () => {
        const flag = `${key}:open`;
        if (!fields.has(flag)) return null;
        // The add button of the form appends the row. Only its press closes the sheet: a second button, such as a clear button, leaves it open.
        const add = node.children.find((child) => child.type === "Button" && appends(child.props.action)), text = at.caption(add?.props.label);
        const open = (to) => () => { kit.state[flag] = to; return ""; };
        return { type: "column", children: [{ type: "button", text, action: at.action(open(true), "_open"), variant: "filled", icon: "Plus" },
          { type: "bottomSheet", text, visibleBind: fields.get(flag), dismissAction: at.action(open(false), "_dismiss"), children: at.children(false, { ...within, off: off || !kit.state[flag], sheet: { flag, add } }) }] };
      },
      modal: () => {
        if (MESSAGES && (!expr.visible || expr.visible.k === "lit")) fault("a Dialog shows while a test of the app is true, so it needs visible=test, such as visible=$/open");
        const target = falseOf(expr.visible), form = (child) => child.props.bind !== undefined || child.children.some(form);
        // The dismiss of Alphy (a tap outside, the back key) makes the test false, as the buttons of the dialog do.
        const dismiss = at.action(() => { if (!target) return false; kit.write(target[0], target[1], {}); kit.recompute(); return ""; }, "_dismiss");
        return { type: node.children.some(form) ? "bottomSheet" : "dialog", text: at.caption(node.props.title), dismissAction: dismiss, children: at.children() };
      },
      list: () => rows(shared, node, key, line, expr, at, ctx.title),
      // The bar of a timer that the run-time moves: a canvas whose surface gets the timer events. The host sends a timer event only to an app with a surface.
      drawn: (share) => {
        const need = !scope && timed.get(node.name);
        return need ? at.canvas(commandsOf(barOf(share, kit.state[need + "_resting"] ? "success" : "accent")), BAR_SURFACE.width, BAR_SURFACE.height, Boolean(kit.state[need + "_on"])) : null;
      },
      // A canvas with one surface of its own, which the reply draws. model is the Model3d node whose turn a drag of the surface sets.
      canvas: (commands, width, height, running = false, model = undefined) => {
        const id = name("surface:" + key, wished(key));
        drawn.push({ id, width, height, running, commands, line, model });
        return { type: "canvas", text: at.label(), surfaces: [id] };
      },
      // The color of "accent" on a surface: the accent of the palette of the app.
      accent: accentOf(palette, preset ?? "clean"),
      // The points of a chart are two arrays that the run-time writes: the labels and the values of the last 256 points.
      chart: () => {
        const kind = node.props.kind ?? "bar", text = at.label().slice(0, 100), points = (Array.isArray(node.props.points) ? node.props.points : []).slice(-MAX_POINTS);
        if (MESSAGES && kind !== "bar" && kind !== "line") fault(`a Chart of kind ${at.plain(kind)} is not built yet: give kind="bar" or kind="line"`);
        const [categories, values] = [["labels", "string", "label"], ["values", "number", "value"]].map(([suffix, type, prop]) => {
          const field = name(`chart:${key}:${suffix}`, wished(`${key}_${suffix}`));
          views[field] = points.map((point) => cast(type, point[prop]));
          viewSchemas[field] = { type: "array", maxItems: MAX_POINTS, items: type };
          viewOwners.push(line);
          return field;
        });
        return { type: "chart", text, kind, categories, series: [{ text, bind: values }] };
      },
      // The open tab is a text of the state: its title. The run-time opens the first tab when that text names no tab, also after a title changes.
      tabs: () => {
        const root = `${node.name}:tab`, groups = node.children.slice(1), titles = (Array.isArray(node.props.titles) ? node.props.titles : []).map((title) => at.plain(title));
        const empty = groups.findIndex((group) => !group.children.length);
        const problem = !node.name || !fields.has(root) ? "a Tabs needs a line of its own, such as pages = Tabs(titles, groups), for the field of its open tab"
          : !groups.length || groups.length > 30 ? `a Tabs has 1 to 30 groups, and this one has ${groups.length}`
            : empty >= 0 ? `group ${empty + 1} of this Tabs is empty, and Alphy draws no empty group. Put a component into it` : null;
        if (problem) { fault(problem); return null; }
        const valid = titles.length === groups.length && new Set(titles).size === titles.length && titles.every((title) => title.trim() && title.length <= 100);
        if (MESSAGES && !valid) fault(`a Tabs needs one title for each of its ${groups.length} groups: texts of 1 to 100 characters, each one different. Now they are ${JSON.stringify(titles)}`);
        // A change of computed titles can make them invalid after the start: the screen then numbers the tabs, so each reply still holds a tabs control.
        const shown = valid ? titles : groups.map((_, index) => String(index + 1));
        if (!shown.includes(kit.state[root])) kit.state[root] = shown[0];
        const open = kit.state[root], select = (value) => { if (!shown.includes(value)) return false; kit.state[root] = value; return ""; };
        return { type: "column", children: [
          { type: "tabs", text: at.label(), bind: fields.get(root), action: at.action(select), options: shown.map((title) => ({ text: title, value: title })) },
          ...groups.map((group, index) => ({ type: "column", visibleBind: slot("v", `${key}.tab${index}`, null, shown[index] === open, at),
            children: group.children.map((child, inner) => walk(child, child.name ?? `${key}:tab${index}:${inner}`, line, { ...within, off: off || shown[index] !== open, weighted: true })).filter(Boolean) })),
        ] };
      },
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
  // What the list mapping (rows.js) shares with the walk.
  const shared = { kit, name, walk, day, fields, lists, views, viewSchemas, viewOwners, listRoots };
  const tree = kit.tree(), top = walk(tree, "root", "root", { scope: null, weighted: true });
  const children = [...(kind ? [{ type: "text", text: String(kind), style: STYLES.eyebrow }] : []), ...(tree.type === "Column" ? top?.children ?? [] : [top].filter(Boolean))];
  const ui = { type: "theme", preset: preset ?? "clean", ...(palette && { palette }), cornerRadius: 20, children: [{ type: "column", style: { gap: "medium", ...(tree.type === "Column" && top?.style), padding: "medium" }, children }] };
  return { ui, values, views, viewSchemas, viewOwners, controls, liveIds, fixes, faults, owners, listRoots, drawn };
}

/** The Alphy value of a stored value of the kit: a time as minutes, a date as a real date (today for a text that is not one), and each row cast. */
function alphyOf(app, root, value, day) {
  const kind = app.kinds?.[root];
  if (kind === "list") return Array.isArray(value) ? value.map((row) => castRow(row, app.lists[root], day)) : [];
  if (kind === "array") return Array.isArray(value) ? value : [];
  // A plain number or text stays inside what Alphy's state holds.
  return kind === "time" || kind === "date" ? cast(kind, value, day) : typeof value === "number" || typeof value === "string" ? cast(typeof value, value) : value;
}

/** The Alphy state of the kit's state: each value of a state line in its field, the change of each value that an animate moves, then the views and the shown values. */
export function stateOf(kit, app, shown) {
  const state = {}, day = today(kit);
  for (const [root, path] of app.fields) {
    const [field, inner] = path.split("."), value = alphyOf(app, root, kit.state[root], day);
    if (inner) (state[field] ??= {})[inner] = value; else state[field] = value;
  }
  if (app.anim) state[app.anim.field] = Object.fromEntries(app.anim.keys.map(([root, key]) => {
    const run = kit.state._run?.find((each) => each.path === root);
    return [key, run ? { from: run.from, to: run.to, start: run.start, end: run.end, at: run.at } : STILL];
  }));
  if (app.drag) state[app.drag] = kit.state._drag ?? IDLE;
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
    } else kit.state[root] = kinds[root] === "time" ? clockOf(value) : kinds[root] === "array" && Array.isArray(value) ? [...value] : kinds[root] === "record" ? { ...value } : value;
  }
  if (app.drag) kit.state._drag = { ...IDLE, ...state[app.drag] };
  // Each event starts a new kit, so a new row gets an id after the highest id of the stored rows.
  kit.nextId = last + 1;
  // A change with an end runs: the kit's tick moves its value to the clock of the event.
  const runs = app.anim ? app.anim.keys.map(([root, key]) => ({ path: root, ...state[app.anim.field]?.[key] })).filter((run) => run.end) : [];
  if (runs.length) kit.state._run = runs; else delete kit.state._run;
  kit.recompute();
}

/**
 * The kit of one event: the lines of the compiled app with the stored state in it. The kit does not start its lines, so its first
 * compute (in load) sees the stored state, and a formula never runs on a state that is not there yet.
 */
export function kitOf(app, state) {
  const kit = new App(new AppFile().feedText(app.lines), false);
  kit.nextId = app.next;
  if (app.shown) kit.shown = Object.fromEntries(parseExpression(app.shown).entries);
  load(kit, app, state);
  return kit;
}

/**
 * Answers one event of the sandbox request {artifactId, revision, state, event}. app is the compiled data of emit.js, and now
 * is the clock of the host. A read-only event, an action id that the app does not have, an action that changes nothing, and an
 * action that takes a list past its count of rows (app.caps), an array past Alphy's limit, or the state past 49152 bytes give the state back as it is.
 * A plain value of another type or a number that is not finite keeps its value from before the event, a bound number past its bounds comes back at
 * its bound, and a choice comes back into its options. The reply holds a new ui only when the screen differs from the screen of the state before the event.
 * An open and a timer event of a timer or a change that runs move each timer and each change to the clock of now. A pointer event
 * of the surface of a model turns the model (drag.js dragged), and an open ends a drag. A timer event of nothing that runs, also a late one after a pause or after
 * the end of a change, and each other event of a surface give the state back as it is. The host sends a liveInput, never an action, for a live
 * control (a TextField or a Slider), so a liveInput sets only a live control and an action for one changes nothing. The reply to a liveInput holds no timers.
 */
export function handle(input, app, now = Date.now()) {
  const { state, event } = input;
  if (event.type === "preview" || event.type === "render") return { state };
  const clock = settings.now;
  settings.now = () => now;
  try {
    const kit = kitOf(app, state), kinds = app.kinds ?? {}, before = screen(kit, app);
    // A drag that the state kept from an app that stopped in it would refuse the down of each other pointer.
    if (app.drag && event.type === "open") kit.state._drag = IDLE;
    const timed = event.type === "open" || (event.type === "timer" && (event.timerId === app.anim?.field ? Boolean(kit.state._run)
      : before.drawn.some((surface) => surface.id === event.timerId && surface.running)));
    const pressed = event.type === (before.liveIds.has(event.actionId) ? "liveInput" : "action");
    const control = pressed ? before.controls.get(event.actionId) : timed ? () => kit.tick() : event.type === "pointer" ? () => dragged(kit, before.drawn, event) : undefined;
    if (!control || control(event.value) === false) return { state };
    for (const [root, kind] of Object.entries(kinds)) {
      if ((kind === "list" && kit.state[root]?.length > (own(app.caps, root) ?? MAX_ROWS)) || (kind === "array" && kit.state[root]?.length > MAX_ARRAY)) return { state };
    }
    // A plain value that the event set to a value of another type, or to a number that is not finite, keeps its value from before the event.
    let typed = false;
    for (const [root, path] of app.fields) {
      const [field, inner] = path.split("."), was = inner ? state[field]?.[inner] : state[field], value = kit.state[root], type = typeof was;
      if (kinds[root] || !["string", "number", "boolean"].includes(type) || (type === "number" ? Number.isFinite(value) : typeof value === type)) continue;
      kit.state[root] = type === "number" ? was : cast(type, value);
      typed = true;
    }
    if (typed) kit.recompute();
    let after = screen(kit, app);
    // shortcut: a timed change (animate) that passes the bounds of a field of its value stops at the bound and ends at the next timer event, while the kit's App moves on; make it a fault of the emitter when a plan animates past a bound.
    if (after.fixes.length) {
      for (const [node, value] of after.fixes) kit.write(node.props.bind, value, { it: node.row });
      kit.recompute();
      after = screen(kit, app);
    }
    const next = stateOf(kit, app, after), reply = JSON.stringify(after.ui) === JSON.stringify(before.ui) ? { state: next } : { state: next, ui: after.ui };
    // Alphy keeps a state of at most 49152 bytes (ToolSpec): an event past it changes nothing, as an add to a full list does.
    if (hostBytes(next) > MAX_STATE_BYTES) return { state };
    // Each reply of an app with surfaces draws each one again, inside Alphy's limits of one reply, and names each timer that runs:
    // the bar of a timer by the id of its surface, and the changes of animate by the field of their state. One list for all.
    if (after.drawn.length) {
      reply.surfaces = fit(after.drawn.map(({ id, commands }) => [id, commands]));
      // The host takes no timers in the reply to a liveInput (SandboxContract), and it keeps the timers that run.
      if (event.type !== "liveInput") reply.timers = [...after.drawn.filter((surface) => surface.running).map(({ id }) => ({ id, intervalMs: TICK_MS })), ...(kit.state._run ? [{ id: app.anim.field, intervalMs: ANIM_MS }] : [])];
    }
    return reply;
  } finally { settings.now = clock; }
}
