// Runs an app file without any platform: state, formulas, actions, and the component tree.
// A renderer draws the tree. The checks of the host are in check.js.

import { DslError, show } from "./dsl.js";

/** Positional arguments of each component of the prototype catalog. */
export const CATALOG = {
  Column: ["children"], Row: ["children"], Card: ["children"], Section: ["title", "children"],
  Text: ["text"], Heading: ["text"], Metric: ["label", "value"], Emoji: ["value"], Badge: ["value"],
  Progress: ["value", "max"], Ring: ["value", "max", "label"], Divider: [], Notice: ["text"],
  Button: ["label", "action"], IconButton: ["icon", "action"],
  TextField: ["label", "bind"], NumberField: ["label", "bind"], Slider: ["label", "bind"],
  Toggle: ["label", "bind"], CheckBox: ["label", "bind"],
  Segmented: ["label", "bind", "options"], Select: ["label", "bind", "options"],
  List: ["items", "template", "group"], Chart: ["items", "label", "value"],
  Tabs: ["titles", "groups"], Dialog: ["title", "children"], DateField: ["label", "bind"], TimeField: ["label", "bind"],
  Picture: ["text", "found"], Choices: ["label", "bind", "options"],
  // What the host draws from lines of data. The bound value of a Model3d is its turn angle in degrees. A Link only shows its address.
  Drawing: ["label", "width", "height", "shapes"], Model3d: ["label", "bodies", "bind", "exploded", "selected"], Link: ["label", "url", "text"],
};
export const ICONS = { Trash: "🗑", Plus: "+", Minus: "−", Refresh: "↻", Check: "✓", ArrowUp: "↑", ArrowDown: "↓", ArrowLeft: "←", ArrowRight: "→", Edit: "✎", Close: "✕" };
const NAMED = new Set(["visible", "enabled", "variant", "tone", "emphasis", "weight", "min", "max", "step", "empty", "placeholder", "gap", "selected", "kind"]);
export const ACTIONS = new Set(["set", "toggle", "inc", "dec", "append", "remove", "clear", "when", "move", "flip", "animate"]);
/** The data calls of a Drawing and of a Model3d, with the arguments of each one in order. A color follows them. */
const SHAPES = { circle: ["x", "y", "r"], rect: ["x", "y", "w", "h"], line: ["x1", "y1", "x2", "y2"], text: ["x", "y", "text"], polygon: ["points"] };
const BODIES = { box: ["part", "x", "y", "z", "w", "h", "d"], cylinder: ["part", "x", "y", "z", "radius", "length", "axis"] };
const TEXTS = new Set(["text", "part", "axis"]), EXTRAS = ["radius", "width", "size", "stroke"];
// A shape also takes its own extra value after the color with no name. The dev run of the dialogue check: 5 of 16 plans
// that think wrote text(x, y, "Земля", "muted", 12) or line(x1, y1, x2, y2, "muted", 2), and no repair step found the cause.
const LAST = { text: "size", line: "width", rect: "radius" };
/** The colors of a shape or a body by role. "accent" is the palette of the app. A different color is "#RRGGBB", and a renderer draws a text that it does not know as ink. */
export const TONES = { ink: "#0f172a", muted: "#94a3b8", accent: null, surface: "#ffffff", water: "#38bdf8", land: "#65a30d", sun: "#facc15", sand: "#e7d3a8", wood: "#a16207", success: "#16a34a", warning: "#ea580c" };

const round = (x, n = 0) => { const f = 10 ** n; return Math.round((x + Number.EPSILON) * f) / f; };
/** A deep copy of a value of the app. The sandbox has no structuredClone. NaN and Infinity stay, so the report can find them. */
const clone = (value) => (Array.isArray(value) ? value.map(clone) : value && typeof value === "object" ? Object.fromEntries(Object.entries(value).map(([key, inner]) => [key, clone(inner)])) : value);
/** A model that is taken apart: each body of a named part moves away from the center of the model, by half of its distance from it. */
function explode(bodies) {
  const center = ["x", "y", "z"].map((axis) => bodies.reduce((sum, body) => sum + body[axis], 0) / (bodies.length || 1));
  for (const body of bodies) if (body.part) ["x", "y", "z"].forEach((axis, index) => { body[axis] = round(body[axis] + (body[axis] - center[index]) / 2, 2); });
}
const PLAIN = {
  round, floor: Math.floor, ceil: Math.ceil, abs: Math.abs,
  // Angles in degrees, for a place on a circle. round takes the float noise away: sin(180) is 0, and never -0.
  sin: (degrees) => round(Math.sin((degrees * Math.PI) / 180), 12) + 0, cos: (degrees) => round(Math.cos((degrees * Math.PI) / 180), 12) + 0,
  // Plain math of the platform. final2 n-mutuo-calc showed the cost of its absence: with no pow, the plans that
  // think built a power from 20 to 30 lines of repeated squaring in 31 to 93 s, and the app was dimmed twice.
  pow: Math.pow, sqrt: Math.sqrt, exp: Math.exp, ln: Math.log, log10: Math.log10,
  // min and max take numbers, lists of numbers, or both. Of nothing they give 0.
  min: (...values) => (values.flat(3).length ? Math.min(...values.flat(3)) : 0), max: (...values) => (values.flat(3).length ? Math.max(...values.flat(3)) : 0),
  clamp: (x, low, high) => Math.min(high, Math.max(low, x)),
  len: (x) => (x == null ? 0 : x.length), text: (x) => String(x ?? ""), number: (x) => Number(x) || 0,
  fixed: (x, n = 2) => Number(x).toFixed(n), pad2: (x) => String(Math.floor(x)).padStart(2, "0"),
  money: (x, sign = "") => sign + Number(x).toFixed(2), percent: (x, n = 0) => Number(x).toFixed(n) + "%",
  join: (list, sep = ", ") => (list || []).join(sep), upper: (s) => String(s).toUpperCase(), lower: (s) => String(s).toLowerCase(),
  at: (list, index) => (Array.isArray(list) ? list[index] ?? null : null),
  // The longest run of true values in a list, such as the days in a row that a habit was done.
  streak: (list) => (list ?? []).reduce(([best, run], on) => (on ? [Math.max(best, run + 1), run + 1] : [best, 0]), [0, 0])[0],
  near: (a, b) => Math.abs(a - b) <= Math.max(0.01, Math.abs(b) / 1000),
  trim: (s) => String(s).trim(), contains: (a, b) => String(a).toLowerCase().includes(String(b).toLowerCase()),
  has: (list, value) => Array.isArray(list) && list.includes(value),
  // The hours from one "HH:MM" time to the next one, over midnight when the second time is the earlier one.
  hours: (from, to) => { const [a, b] = [from, to].map(minutes); return a === null || b === null ? 0 : ((b - a + 1440) % 1440) / 60; },
  today: () => { const now = new Date(); return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}-${String(now.getDate()).padStart(2, "0")}`; },
  // The parts of a text as a list, each trimmed, with no empty part (final2 n-meal-planner: a grocery list from the meals).
  split: (text, sep = ",") => String(text ?? "").split(String(sep) || ",").map((part) => part.trim()).filter(Boolean),
  // Parts of a "YYYY-MM-DD" date (final2 n-mood-tracker: the last seven days. n-fatura-takip: a total for each month).
  // A text that is not a date gives 0, and addDays of it gives null.
  weekday: (date) => { const day = dayNumber(date); return day === null ? 0 : ((new Date(day * 864e5).getUTCDay() + 6) % 7) + 1; },
  month: (date) => (dayNumber(date) === null ? 0 : Number(String(date).trim().slice(5, 7))),
  year: (date) => (dayNumber(date) === null ? 0 : Number(String(date).trim().slice(0, 4))),
  days: (from, to) => { const [a, b] = [dayNumber(from), dayNumber(to)]; return a === null || b === null ? 0 : b - a; },
  addDays: (date, n) => { const day = dayNumber(date); return day === null ? null : new Date((day + Math.round(Number(n) || 0)) * 864e5).toISOString().slice(0, 10); },
};
/** The day of a "YYYY-MM-DD" text as a count of days, or null when the text is not a real date. */
function dayNumber(text) {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(text ?? "").trim());
  if (!match) return null;
  const time = Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3])), date = new Date(time);
  return date.getUTCMonth() === Number(match[2]) - 1 && date.getUTCDate() === Number(match[3]) ? time / 864e5 : null;
}
function minutes(text) { const match = /^(\d{1,2}):(\d{2})$/.exec(String(text ?? "").trim()); return match ? Number(match[1]) * 60 + Number(match[2]) : null; }
/** The order of two sort values. A list of values is in order by its first value, then by the next one. */
const order = (x, y) => (Array.isArray(x) && Array.isArray(y) ? x.reduce((found, value, index) => found || order(value, y[index]), 0) : x < y ? -1 : x > y ? 1 : 0);
/** Helpers whose later arguments are evaluated for each row, with $field as the row's field. */
const ROWWISE = {
  count: (rows, f) => (f ? rows.filter(f).length : rows.length),
  sum: (rows, f) => rows.reduce((a, row) => a + Number(f ? f(row) : row), 0),
  // With no formula the rows are plain numbers, as in avg(map($/nights, hours($bed, $wake))) (v1 sleep-en-3 threw here).
  avg: (rows, f) => (rows.length ? rows.reduce((a, row) => a + Number(f ? f(row) : row), 0) / rows.length : 0),
  filter: (rows, f) => rows.filter(f),
  any: (rows, f) => rows.some(f), all: (rows, f) => rows.every(f),
  map: (rows, f) => rows.map(f), find: (rows, f) => rows.find(f) ?? null,
  sort: (rows, f, way) => [...rows].sort((a, b) => order(f(a), f(b)) * (way === "desc" ? -1 : 1)),
  // One row {key, value} for each different key, in the order of the first row of each key. value is the sum, or the count of rows when no value is given.
  groups: (rows, key, value) => {
    if (!key) throw new DslError("groups needs a key, such as groups($/rows, $category, $amount)");
    const sums = new Map();
    for (const row of rows) sums.set(key(row), (sums.get(key(row)) ?? 0) + Number(value ? value(row) : 1));
    return [...sums].map(([name, sum]) => ({ key: name, value: sum }));
  },
};

export const FUNCTIONS = new Set([...Object.keys(PLAIN), ...Object.keys(ROWWISE), "if"]);

/** Equal values. Two lists or two rows are equal when each value in them is equal, with no look at the row id of the host. */
function same(a, b) {
  if (a === b) return true;
  if (typeof a !== "object" || typeof b !== "object" || !a || !b || Array.isArray(a) !== Array.isArray(b)) return false;
  const keys = (value) => Object.keys(value).filter((key) => key !== "id");
  return keys(a).length === keys(b).length && keys(a).every((key) => same(a[key], b[key]));
}

/** The arguments of a component call by property name: positional ones by the catalog order, then the named ones. */
export const given = (tree) => ({ ...Object.fromEntries((CATALOG[tree.name] ?? []).map((key, index) => [key, tree.args[index]])), ...tree.named });

export class App {
  constructor(file) {
    this.file = file;
    this.faults = [];
    this.nextId = 1;
    this.state = {};
    this.seen = new Map();
    this.follow();
  }

  /**
   * Starts each state line that is new or different since the last look at the file. The other values stay.
   * So an app that runs follows a step that changed one line, and the person keeps what they did in the other parts.
   */
  follow() {
    for (const entry of this.file.of("state")) {
      if (this.seen.get(entry.key) === entry.raw) continue;
      this.seen.set(entry.key, entry.raw);
      this.write(this.pathOf(entry.key), this.attempt(entry, () => clone(this.value(entry.tree, {}))), {});
    }
    this.tagRows(this.state);
    this.recompute();
  }

  fault(where, message) { this.faults.push({ where, message }); }
  attempt(entry, run) { try { return run(); } catch (failure) { this.fault(entry.key, failure.message); return null; } }
  pathOf(key) { return { k: "path", abs: true, segs: key.slice(2).split("/") }; }

  tagRows(value) {
    if (Array.isArray(value)) value.forEach((row) => { if (row && typeof row === "object" && !Array.isArray(row)) { row.id ??= "r" + this.nextId++; this.tagRows(row); } });
    else if (value && typeof value === "object") Object.values(value).forEach((inner) => this.tagRows(inner));
  }

  read(path, ctx) {
    let value = path.abs ? this.state : ctx.it;
    for (const seg of path.segs) value = value == null ? undefined : value[seg];
    return value;
  }

  /** The place that a write goes to, as a holder and a key: a path, a field of a value, or item N of a list. */
  place(node, ctx) {
    if (node.k === "path") {
      let holder = node.abs ? this.state : ctx.it;
      if (holder == null) throw new DslError("a $field path needs a row");
      if (typeof holder !== "object") throw new DslError("the row is a plain value with no fields to set");
      for (const seg of node.segs.slice(0, -1)) holder = holder[seg] ??= {};
      return [holder, node.segs.at(-1)];
    }
    if (node.k !== "field" && node.k !== "index") throw new DslError("a place to set is a path, a field of a row, or an item of a list");
    const holder = this.value(node.of, ctx);
    if (holder == null || typeof holder !== "object") throw new DslError(`${show(node.of)} has no value, so nothing in it can be set`);
    return [holder, node.k === "field" ? node.name : this.value(node.at, ctx)];
  }

  write(node, value, ctx) {
    const [holder, key] = this.place(node, ctx);
    holder[key] = value;
  }

  /** The value of an expression. ctx.it is the row for $field paths. */
  value(node, ctx) {
    switch (node.k) {
      case "lit": return node.v;
      case "path":
        // A row field that the row does not have is a mistake of the line, not an empty value.
        if (!node.abs && node.segs[0] === "it") return node.segs.slice(1).reduce((value, seg) => value?.[seg], ctx.it);
        // Inside each(list, shape), $index is the number of the row, from 0. A row field with that name wins.
        if (!node.abs && ctx.each !== undefined && node.segs.join() === "index" && !(ctx.it && typeof ctx.it === "object" && "index" in ctx.it)) return ctx.each;
        if (!node.abs && ctx.it === undefined) throw new DslError(`$${node.segs.join("/")} is a field of a list row, and there is no row here. A value of the app is $/${node.segs.join("/")}`);
        if (!node.abs && ctx.it !== undefined && (ctx.it === null || typeof ctx.it !== "object")) throw new DslError("the row is a plain value with no fields. $it reads the row itself");
        if (!node.abs && ctx.it && !(node.segs[0] in ctx.it)) {
          // A value that an entries need computes for each row, show={name: formula}, reads as a field of the row
          // in each other formula of a row (v1 sleep-en: a chart of $slept). busy stops a show formula that reads itself.
          const name = node.segs[0];
          this.shown ??= Object.fromEntries(this.file.of("need").filter((entry) => entry.tree.name === "entries" && entry.tree.named?.show?.k === "map").flatMap((entry) => entry.tree.named.show.entries));
          if (node.segs.length === 1 && this.shown[name] && !ctx.busy?.includes(name)) return this.value(this.shown[name], { ...ctx, busy: [...(ctx.busy ?? []), name] });
          throw new DslError(`the row has no field ${name}`);
        }
        return this.read(node, ctx);
      case "list": return node.items.map((item) => this.value(item, ctx));
      case "map": return Object.fromEntries(node.entries.map(([key, inner]) => [key, this.value(inner, ctx)]));
      case "field": return this.value(node.of, ctx)?.[node.name];
      case "index": { const list = this.value(node.of, ctx); return Array.isArray(list) ? list[this.value(node.at, ctx)] ?? null : null; }
      case "un": return node.op === "-" ? -this.value(node.arg, ctx) : !this.value(node.arg, ctx);
      case "if": return this.value(node.test, ctx) ? this.value(node.yes, ctx) : this.value(node.no, ctx);
      case "bin": {
        if (node.op === "&&") return this.value(node.left, ctx) && this.value(node.right, ctx);
        if (node.op === "||") return this.value(node.left, ctx) || this.value(node.right, ctx);
        const a = this.value(node.left, ctx), b = this.value(node.right, ctx);
        switch (node.op) {
          case "+": return Array.isArray(a) && Array.isArray(b) ? [...a, ...b] : typeof a === "string" || typeof b === "string" ? String(a ?? "") + String(b ?? "") : a + b;
          case "-": return a - b; case "*": return a * b; case "/": return a / b; case "%": return a % b;
          // Lists and rows are equal when their values are equal. The id that the host gives a row does not count (v1 budget-es: an example of groups).
          case "==": return same(a, b); case "!=": return !same(a, b);
          case "<": return a < b; case "<=": return a <= b; case ">": return a > b; case ">=": return a >= b;
        }
        break;
      }
      case "call": {
        if (node.name === "if") return this.value(node.args[0], ctx) ? this.value(node.args[1], ctx) : this.value(node.args[2], ctx);
        if (ROWWISE[node.name]) {
          const rows = this.value(node.args[0], ctx);
          if (!Array.isArray(rows)) throw new DslError(`${node.name} needs a list`);
          const perRow = (arg) => arg && ((row) => this.value(arg, { ...ctx, it: row }));
          // The third argument of groups is a formula of the row. The third argument of sort is the order.
          return ROWWISE[node.name](rows, perRow(node.args[1]), node.name === "groups" ? perRow(node.args[2]) : node.args[2] && this.value(node.args[2], ctx));
        }
        if (PLAIN[node.name]) return PLAIN[node.name](...node.args.map((arg) => this.value(arg, ctx)));
        if (CATALOG[node.name]) throw new DslError(`${node.name} is a component. A component line has a plain name with no $/ before it`);
        throw new DslError(`unknown function ${node.name}`);
      }
      case "ref": throw new DslError(`${node.name} is a component name, not a value`);
    }
    throw new DslError("expression not understood");
  }

  /**
   * The data calls of a Drawing (tag "shape") or a Model3d (tag "body") as plain values, so a renderer needs no formula.
   * each(list, call) gives one for each row of a list, or for each number below a count. A call with a false visible test gives nothing.
   */
  drawn(kinds, tag, tree, ctx) {
    if (tree.k === "list") return tree.items.flatMap((item) => this.drawn(kinds, tag, item, ctx));
    const { name = "", args = [], named = {} } = tree.k === "call" ? tree : {};
    if (name === "each" && args.length === 2) {
      const given = this.value(args[0], ctx), rows = Array.isArray(given) ? given : Number.isInteger(given) && given >= 0 && given <= 500 ? Array.from({ length: given }, (_, index) => index) : null;
      if (!rows) throw new DslError("each needs a list or a count: each($/rows, shape) or each(5, shape)");
      return rows.flatMap((row, index) => this.drawn(kinds, tag, args[1], { ...ctx, it: row, each: index }));
    }
    const keys = Object.hasOwn(kinds, name) ? kinds[name] : null;
    if (!keys) throw new DslError(`${show(tree).slice(0, 30)} is not one of ${Object.keys(kinds).join(", ")}, each`);
    const last = LAST[name], takes = `${name} takes ${keys.join(", ")}, color${last ? `, ${last}` : ""}`;
    if (args.length < keys.length || args.length > keys.length + (last ? 2 : 1)) throw new DslError(`${show(tree).slice(0, 60)} has ${args.length} values: ${takes}`);
    if (named.visible && !this.value(named.visible, ctx)) return [];
    const number = (value, key) => { if (value === null || value === "" || typeof value === "boolean" || !Number.isFinite(Number(value))) throw new DslError(`${key} of ${name} is not a number: ${takes}`); return round(Number(value), 2); };
    const out = { [tag]: name };
    keys.forEach((key, index) => {
      const value = this.value(args[index], ctx);
      out[key] = TEXTS.has(key) ? String(value ?? "") : key === "points" ? [value].flat().map((item) => number(item, key)) : number(value, key);
    });
    if (out.axis !== undefined && !["x", "y", "z"].includes(out.axis)) throw new DslError(`axis of ${name} is "x", "y", or "z"`);
    out.color = String((args[keys.length] ? this.value(args[keys.length], ctx) : null) ?? "ink");
    if (args.length === keys.length + 2) out[last] = number(this.value(args[keys.length + 1], ctx), last);
    for (const [key, arg] of Object.entries(named)) {
      if (key === "visible") continue;
      if (!EXTRAS.includes(key)) throw new DslError(`${name} has no argument ${key}`);
      const value = this.value(arg, ctx);
      if (key !== "stroke") out[key] = number(value, key); else if (value) out.stroke = true;
    }
    return [out];
  }

  /** Formulas can depend on each other in any order, so run them until nothing changes. */
  recompute() {
    const formulas = this.file.of("formula");
    for (let pass = 0; pass < 5; pass++) {
      const before = JSON.stringify(this.state);
      for (const entry of formulas) this.attempt(entry, () => this.write(this.pathOf(entry.key), this.value(entry.tree, {}), {}));
      if (JSON.stringify(this.state) === before) return;
    }
  }

  /** Runs one action call or a list of them, then the formulas. ctx has the row and its list for a row control. */
  act(tree, ctx = {}) {
    for (const call of tree.k === "list" ? tree.items : [tree]) {
      if (call.k !== "call" || !ACTIONS.has(call.name)) throw new DslError(`unknown action ${call.name ?? call.k}`);
      const [first, second] = call.args;
      if (call !== (tree.k === "list" ? tree.items[0] : tree)) this.recompute();
      const target = () => { if (!["path", "field", "index"].includes(first?.k)) throw new DslError(`${call.name} needs a place: a path, a field of a row, or an item of a list`); return first; };
      const rowsOf = (node) => { const rows = this.value(node, ctx); if (!Array.isArray(rows)) throw new DslError(`${call.name} needs a list`); return rows; };
      switch (call.name) {
        case "set": this.write(target(), clone(this.value(second, ctx)), ctx); break;
        case "toggle": this.write(target(), !this.value(target(), ctx), ctx); break;
        case "inc": this.write(target(), (this.value(target(), ctx) || 0) + (second ? this.value(second, ctx) : 1), ctx); break;
        case "dec": this.write(target(), (this.value(target(), ctx) || 0) - (second ? this.value(second, ctx) : 1), ctx); break;
        case "clear": this.write(target(), [], ctx); break;
        // flip($/list, value): the value joins the list, or leaves it when it is there. For options that a person turns on and off.
        case "flip": { const rows = rowsOf(target()), value = this.value(second, ctx); this.write(target(), rows.includes(value) ? rows.filter((item) => item !== value) : [...rows, value], ctx); break; }
        case "move": {
          // In a list row: move(-1) moves that row one place up. Outside: move($/list, test, by) moves the first row that passes.
          const inRow = call.args.length === 1;
          if (inRow && !ctx.list) throw new DslError("move(by) needs a row. Outside a list write move($/list, test, by)");
          const rows = inRow ? this.read(ctx.list, {}) : rowsOf(target());
          const from = inRow ? rows.indexOf(ctx.it) : rows.findIndex((row) => this.value(second, { ...ctx, it: row }));
          const to = from + this.value(inRow ? first : call.args[2], ctx);
          if (from >= 0 && to >= 0 && to < rows.length) rows.splice(to, 0, rows.splice(from, 1)[0]);
          break;
        }
        case "when": if (this.value(first, ctx)) this.act(second, ctx); else this.recompute(); break;
        // animate($/place, to, ms): the number goes to the value in a straight line over ms milliseconds of the host clock. tick() moves it.
        case "animate": {
          if (first?.k !== "path" || !first.abs) throw new DslError("animate needs a value of the app as its place, such as $/angle");
          const path = first.segs.join("/"), from = Number(this.value(first, ctx)) || 0, to = second ? Number(this.value(second, ctx)) : NaN, ms = call.args[2] ? Number(this.value(call.args[2], ctx)) : 0;
          if (!Number.isFinite(to) || !Number.isFinite(ms)) throw new DslError("animate needs a number to go to and a time in milliseconds: animate($/angle, 360, 5000)");
          const runs = (this.state._run ?? []).filter((run) => run.path !== path);
          if (ms > 0 && to !== from) runs.push({ path, from, to, ms, spent: 0, at: from }); else this.write(first, to, ctx);
          if (runs.length) this.state._run = runs; else delete this.state._run;
          break;
        }
        case "append": {
          const row = clone(this.value(second, ctx));
          rowsOf(target()).push(row);
          this.tagRows([row]);
          break;
        }
        case "remove": {
          if (!first) {
            if (!ctx.list) throw new DslError("remove() without a path needs a row");
            this.write(ctx.list, this.read(ctx.list, {}).filter((row) => row !== ctx.it), {});
          } else this.write(target(), rowsOf(target()).filter((row) => !this.value(second, { ...ctx, it: row })), ctx);
          break;
        }
      }
    }
    this.recompute();
  }

  /** The tree that a renderer draws. A name that has no line yet becomes a Missing node, so a stream can draw. */
  tree(strict = false) {
    this.reached = new Set();
    let unnamed = 0;
    const visit = (tree, ctx, name, depth) => {
      if (depth > 40) throw new DslError("components nest too deep or refer to each other");
      if (tree.k === "ref") {
        const entry = this.file.get(tree.name);
        this.reached.add(tree.name);
        if (!entry || entry.kind !== "component") {
          if (strict) this.fault(tree.name, "no component line with this name");
          return { type: "Missing", name: tree.name, props: {}, children: [] };
        }
        return visit(entry.tree, ctx, tree.name, depth + 1);
      }
      if (tree.k !== "call" || !CATALOG[tree.name]) {
        this.fault(name ?? "?", tree.k === "path" ? `a child is a component name, not the path ${show(tree)}` : `unknown component ${tree.name ?? tree.k}`);
        return { type: "Missing", name, props: {}, children: [] };
      }
      // call is the expression of the component, so the emitter can tell a constant property from a computed one.
      const node = { type: tree.name, name, props: {}, children: [], row: ctx.it, rowIndex: ctx.index, list: ctx.list, call: tree };
      const props = given(tree);
      let groups = [];
      for (const [key, arg] of Object.entries(props)) {
        if (arg === undefined) continue;
        // A field takes an action too. It runs after each change of the field.
        if (!CATALOG[tree.name].includes(key) && !NAMED.has(key) && !(key === "action" && props.bind)) { this.fault(name ?? tree.name, `${tree.name} has no property ${key}`); continue; }
        if ((tree.name === "Chart" && (key === "label" || key === "value")) || (tree.name === "List" && key === "group") || key === "shapes" || key === "bodies") continue;
        try {
          if (key === "children") node.children = (arg.k === "list" ? arg.items : [arg]).map((child) => visit(child, ctx, undefined, depth + 1));
          else if (key === "groups") groups = arg.k === "list" ? arg.items : [arg];
          else if (key === "action") node.props.action = arg;
          else if (key === "bind") {
            if (!["path", "field", "index"].includes(arg.k)) throw new DslError("bind needs a place: a path, a field of a row, or an item of a list");
            node.props.bind = arg;
            node.props.value = arg.k === "path" ? this.read(arg, ctx) : this.value(arg, ctx);
          } else if (key === "template") node.template = arg;
          else node.props[key] = this.value(arg, ctx);
        } catch (failure) { this.fault(name ?? tree.name, `${key}: ${failure.message}`); }
      }
      if (tree.name === "Chart") {
        // One point for each row. label and value are formulas of the row.
        try {
          node.props.points = (Array.isArray(node.props.items) ? node.props.items : []).map((row) => ({
            label: props.label ? this.value(props.label, { ...ctx, it: row }) : "", value: Number(props.value ? this.value(props.value, { ...ctx, it: row }) : row) || 0 }));
        } catch (failure) { this.fault(name ?? tree.name, `points: ${failure.message}`); }
        delete node.props.items;
      }
      if (tree.name === "Drawing" || tree.name === "Model3d") {
        const key = tree.name === "Drawing" ? "shapes" : "bodies";
        try { node.props[key] = this.drawn(key === "shapes" ? SHAPES : BODIES, key === "shapes" ? "shape" : "body", props[key] ?? { k: "list", items: [] }, ctx); }
        catch (failure) { node.props[key] = []; this.fault(name ?? tree.name, `${key}: ${failure.message}`); }
        if (key === "bodies" && node.props.exploded) explode(node.props.bodies);
        if (key === "shapes" && !(node.props.width > 0 && node.props.height > 0)) this.fault(name ?? tree.name, "a drawing needs a width and a height above 0: drawing(label, 320, 240, [shapes])");
      }
      // The address comes from a model, so the host shows only a web address.
      if (tree.name === "Link" && !/^https?:\/\/\S+$/.test(String(node.props.url ?? ""))) this.fault(name ?? tree.name, "a Link needs a web address that starts with https://");
      if (tree.name === "Dialog" && !props.visible) this.fault(name ?? tree.name, "a Dialog needs visible=test and a button that makes the test false. Without them the window never closes");
      if (tree.name === "Choices") {
        // One on-off button for each option. With max= the other options are disabled when that many are on.
        const on = Array.isArray(node.props.value) ? node.props.value : [], full = node.props.max !== undefined && on.length >= node.props.max;
        node.children = (node.props.options ?? []).map((option, index) => ({ type: "Button", name: name && `${name}_opt${index}`, children: [],
          props: { label: String(option), selected: on.includes(option), enabled: on.includes(option) || !full, action: { k: "call", name: "flip", args: [props.bind, { k: "lit", v: option }], named: {} } } }));
      }
      if (tree.name === "Tabs") {
        // The open tab is a value of the screen. It is in the state under _ui, so each reset of the state closes it again.
        const place = { k: "path", abs: true, segs: ["_ui", name ?? "tabs" + unnamed++] }, open = this.read(place, {}) ?? 0;
        node.props.value = open;
        node.children = [
          { type: "Row", props: {}, children: (node.props.titles ?? []).map((title, index) => ({ type: "Button", name: name && `${name}_tab${index}`, tab: true, children: [],
            props: { label: title, selected: index === open, action: { k: "call", name: "set", args: [place, { k: "lit", v: index }], named: {} } } })) },
          ...groups.map((group, index) => ({ type: "Column", props: { visible: index === open }, children: (group.k === "list" ? group.items : [group]).map((child) => visit(child, ctx, undefined, depth + 1)) })),
        ];
      }
      if (tree.name === "List" && node.template && Array.isArray(node.props.items)) {
        // sort(...) and filter(...) keep the row objects, so a row control still acts on the list they read.
        // The list that a row acts on is the stored list, also when the rows come through sort, filter, or a formula of them.
        const stored = (tree, hops = 0) => {
          if (!tree || hops > 8) return null;
          if (tree.k === "call" && ["sort", "filter"].includes(tree.name)) return stored(tree.args[0], hops + 1);
          if (tree.k === "if") return stored(tree.yes, hops + 1) ?? stored(tree.no, hops + 1);
          if (tree.k !== "path") return null;
          const formula = tree.abs ? this.file.get("$/" + tree.segs.join("/")) : null;
          return formula?.kind === "formula" ? stored(formula.tree, hops + 1) : tree;
        };
        const list = stored(props.items);
        let rows = node.props.items, heads = [];
        if (props.group) try {
          // Rows in groups, in the order of the first row of each group, with a heading before each group.
          const keys = rows.map((row) => this.value(props.group, { ...ctx, it: row }));
          const sorted = [...new Set(keys)].flatMap((key) => rows.map((row, index) => [row, key, index]).filter((item) => keys[item[2]] === key));
          rows = sorted.map((item) => item[0]);
          heads = sorted.map((item, index) => (index === 0 || sorted[index - 1][1] !== item[1] ? [{ type: "Heading", props: { text: String(item[1] ?? "") }, children: [] }] : []));
        } catch (failure) { this.fault(name ?? tree.name, `group: ${failure.message}`); }
        node.children = rows.flatMap((row, index) => [...(heads[index] ?? []), visit(node.template, { it: row, index, list }, undefined, depth + 1)]);
      }
      return node;
    };
    const root = this.file.get("root");
    if (!root) return { type: "Missing", name: "root", props: {}, children: [] };
    return visit(root.tree, {}, "root", 0);
  }

  find(name, rowIndex) {
    const walk = (node) => (node.name === name && (rowIndex === undefined || node.rowIndex === rowIndex) ? node : node.children.reduce((hit, child) => hit ?? walk(child), null));
    return walk(this.tree());
  }

  /**
   * The host looks at the clock (settings.now). Each timer need that runs moves forward by the time since the last
   * look: its work phase ends and counts in $/NAME, then its rest phase runs when it has one, then the next round starts.
   * The timer part made the state names. A test and the smoke run move the clock and call this.
   */
  tick() {
    const now = settings.now(), passed = (now - (this.clock ?? now)) / 1000, s = this.state;
    this.clock = now;
    // A timed change (the animate action) moves by the time that passed. A value that something else set in the meantime ends its change.
    if (passed > 0 && s._run) {
      s._run = s._run.filter((run) => {
        const place = this.pathOf("$/" + run.path);
        if (this.read(place, {}) !== run.at) return false;
        run.spent += passed * 1000;
        run.at = run.spent >= run.ms ? run.to : run.from + ((run.to - run.from) * run.spent) / run.ms;
        this.write(place, run.at, {});
        return run.spent < run.ms;
      });
      if (!s._run.length) delete s._run;
      this.recompute();
    }
    for (const entry of passed > 0 ? this.file.of("need") : []) {
      if (entry.tree.name !== "timer") continue;
      const n = entry.key, number = (tree, fallback) => { try { return Number(tree ? this.value(tree, {}) : fallback) || 0; } catch { return 0; } };
      let left = passed;
      // 1000 phases at most in one look, so a phase of no length cannot hold the host.
      for (let guard = 0; s[n + "_on"] && left > 0 && guard < 1000; guard++) {
        const phase = Number(s[n + "_phase"]) || 0, room = phase - s[n + "_spent"];
        if (left < room) { s[n + "_spent"] += left; break; }
        left -= Math.max(0, room);
        if (!s[n + "_resting"]) s[n] += 1;
        if (!s[n + "_resting"] && number(entry.tree.named.rest, 0) > 0) { s[n + "_resting"] = true; s[n + "_spent"] = 0; }
        else if (s[n + "_round"] >= number(entry.tree.named.rounds, 1)) { s[n + "_over"] = true; s[n + "_on"] = false; s[n + "_spent"] = phase; }
        else { s[n + "_round"] += 1; s[n + "_resting"] = false; s[n + "_spent"] = 0; }
        this.recompute();
      }
      this.recompute();
    }
  }

  /** What a person does to a drawn node: change its field, or press it. Returns an error text or "". */
  change(node, value) {
    this.tick();
    try {
      this.write(node.props.bind, value, { it: node.row });
      if (node.props.action) this.act(node.props.action, { it: node.row, list: node.list }); else this.recompute();
    } catch (failure) { return `${node.name ?? node.type}: ${failure.message}`; }
    return "";
  }

  fire(node) {
    if (node.props.enabled === false || node.props.visible === false) return `${node.name ?? node.type} is not active`;
    this.tick();
    try { this.act(node.props.action, { it: node.row, list: node.list }); } catch (failure) { return `${node.name ?? node.type}: ${failure.message}`; }
    return "";
  }

  /** The same by name, for checks and for the agent loop. */
  input(name, value, rowIndex) {
    const node = this.find(name, rowIndex);
    if (node?.props.bind) return this.change(node, value);
    const why = !node ? `no field named ${name}` : node.props.action ? `${name} is not a field: use tap(${name})` : `${name} only shows a value and cannot be set. A check sets a field, such as a NumberField`;
    return why + (rowIndex === undefined ? "" : ` in row ${rowIndex}`);
  }

  press(name, rowIndex) {
    const node = this.find(name, rowIndex);
    if (node?.props.action) return this.fire(node);
    return (node?.props.bind ? `${name} is a field with no action: use set(${name}, value)` : `no control named ${name}`) + (rowIndex === undefined ? "" : ` in row ${rowIndex}`);
  }
}

/** How the host decides that model-written logic is fit: "checks" wants a check line for each control and formula, "smoke" uses each control itself. */
// now is the clock of the host in milliseconds. A test sets it, and the smoke run has its own.
// The kit uses the smoke gate: an app file of this design has no check lines.
export const settings = { gate: "smoke", now: () => Date.now() };
