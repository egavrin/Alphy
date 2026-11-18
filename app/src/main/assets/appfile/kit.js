// Built by kit/build.js from kit/src with Bun 1.3.9. Do not edit: run bun kit/build.js.
(() => {
"use strict";
// dsl.js
const { DslError, parseExpression, parseLine, AppFile, show } = (() => {
// The app file: one item on each line. A later line with the same name or path replaces the earlier one,
// so a patch is more lines of the same kind.
//
//   app("Tip split", preset="clean", palette="teal")      header
//   $/bill = 80                                           state value
//   $/perPerson := $/bill / $/people                      formula, kept current by the host
//   root = Column([hero, billField])                      component, by name
//   need bill = input("money", "Bill", 80)                need: a part expands it into lines like the ones above
//   check("Split", set(billField, 100), expect($/perPerson == 25))
//   delete billField
//   # comment

const OPS = { "||": 1, "&&": 2, "==": 3, "!=": 3, "<": 4, "<=": 4, ">": 4, ">=": 4, "+": 5, "-": 5, "*": 6, "/": 6, "%": 6 };

class DslError extends Error {}

function tokenize(text) {
  const tokens = [];
  let i = 0;
  const fail = (message) => { throw new DslError(`${message} at column ${i + 1}`); };
  while (i < text.length) {
    const ch = text[i];
    if (/\s/.test(ch)) { i++; continue; }
    if (ch === '"') {
      let j = i + 1, value = "";
      while (j < text.length && text[j] !== '"') {
        if (text[j] === "\\") { j++; value += { n: "\n", t: "\t" }[text[j]] ?? text[j]; } else value += text[j];
        j++;
      }
      if (j >= text.length) fail("string without a closing quote");
      tokens.push({ t: "str", v: value });
      i = j + 1;
      continue;
    }
    if (ch === "$") {
      const match = /^\$(\/?)([\p{L}_][\p{L}\p{N}_]*(?:\/[\p{L}_][\p{L}\p{N}_]*)*)/u.exec(text.slice(i));
      if (!match) fail("path expected after $");
      tokens.push({ t: "path", abs: match[1] === "/", segs: match[2].split("/") });
      i += match[0].length;
      continue;
    }
    const number = /^(\d+\.?\d*|\.\d+)/.exec(text.slice(i));
    if (number) { tokens.push({ t: "num", v: Number(number[0]) }); i += number[0].length; continue; }
    const word = /^[\p{L}_][\p{L}\p{N}_]*/u.exec(text.slice(i));
    if (word) { tokens.push({ t: "id", v: word[0] }); i += word[0].length; continue; }
    const two = text.slice(i, i + 2);
    if ([":=", "==", "!=", "<=", ">=", "&&", "||"].includes(two)) { tokens.push({ t: "op", v: two }); i += 2; continue; }
    if ("()[]{},:=+-*/%<>!?.".includes(ch)) { tokens.push({ t: "op", v: ch }); i++; continue; }
    fail(`unexpected character ${ch}`);
  }
  return tokens;
}

/** Parses one expression. Returns the tree and the count of tokens that it used. */
function parseTokens(tokens, start = 0) {
  let at = start;
  const peek = (v) => tokens[at] && tokens[at].t === "op" && tokens[at].v === v;
  const eat = (v) => { if (!peek(v)) throw new DslError(`"${v}" expected`); at++; };
  function primary() {
    const token = tokens[at];
    if (!token) throw new DslError("value expected at the end of the line");
    at++;
    if (token.t === "num") return { k: "lit", v: token.v };
    if (token.t === "str") return { k: "lit", v: token.v };
    if (token.t === "path") return { k: "path", abs: token.abs, segs: token.segs };
    if (token.t === "id") {
      if (token.v === "true" || token.v === "false") return { k: "lit", v: token.v === "true" };
      if (token.v === "null") return { k: "lit", v: null };
      if (!peek("(")) return { k: "ref", name: token.v };
      at++;
      const args = [], named = {};
      while (!peek(")")) {
        if (tokens[at]?.t === "id" && tokens[at + 1]?.t === "op" && tokens[at + 1].v === "=") {
          const key = tokens[at].v;
          at += 2;
          named[key] = ternary();
        } else args.push(ternary());
        if (!peek(")")) eat(",");
      }
      at++;
      return { k: "call", name: token.v, args, named };
    }
    if (token.t === "op" && token.v === "(") { const inner = ternary(); eat(")"); return inner; }
    if (token.t === "op" && token.v === "[") {
      const items = [];
      while (!peek("]")) { items.push(ternary()); if (!peek("]")) eat(","); }
      at++;
      return { k: "list", items };
    }
    if (token.t === "op" && token.v === "{") {
      const entries = [];
      while (!peek("}")) {
        const key = tokens[at++];
        if (!key || (key.t !== "id" && key.t !== "str")) throw new DslError("field name expected in { }");
        eat(":");
        entries.push([key.v, ternary()]);
        if (!peek("}")) eat(",");
      }
      at++;
      return { k: "map", entries };
    }
    if (token.t === "op" && (token.v === "-" || token.v === "!")) return { k: "un", op: token.v, arg: postfix() };
    throw new DslError(`unexpected "${token.v}"`);
  }
  function postfix() {
    let node = primary();
    for (;;) {
      const next = tokens[at + 1];
      if (peek(".")) {
        at++;
        const field = tokens[at++];
        if (!field || field.t !== "id") throw new DslError("field name expected after .");
        node = { k: "field", of: node, name: field.v };
      } else if (peek("[")) {
        at++;
        const index = ternary();
        eat("]");
        node = { k: "index", of: node, at: index };
      } else if (peek("/") && next?.t === "id" && !["true", "false", "null"].includes(next.v) && !(tokens[at + 2]?.t === "op" && tokens[at + 2].v === "(")) {
        // "/name" after a value is a field, as in a path: at($/rows, 0)/name. A bare name has no value of its own in a formula.
        node = { k: "field", of: node, name: next.v };
        at += 2;
      } else return node;
    }
  }
  function binary(level) {
    let left = postfix();
    while (tokens[at]?.t === "op" && OPS[tokens[at].v] >= level) {
      const op = tokens[at++].v;
      left = { k: "bin", op, left, right: binary(OPS[op] + 1) };
    }
    return left;
  }
  function ternary() {
    const test = binary(1);
    if (!peek("?")) return test;
    at++;
    const yes = ternary();
    eat(":");
    return { k: "if", test, yes, no: ternary() };
  }
  const tree = ternary();
  return { tree, used: at - start };
}

function parseExpression(text) {
  const tokens = tokenize(text);
  const { tree, used } = parseTokens(tokens);
  if (used !== tokens.length) throw new DslError(`unexpected "${tokens[used].v ?? tokens[used].t}" after the expression`);
  return tree;
}

/** One line as an entry: {kind, key, tree, raw}. Null for an empty line or a comment. */
function parseLine(raw) {
  const line = raw.trim();
  if (!line || line.startsWith("#")) return null;
  const entry = (kind, key, text) => ({ kind, key, tree: parseExpression(text), raw: line });
  let match;
  if ((match = /^delete\s+check\s*\(\s*"(.*)"\s*\)$/.exec(line))) return { kind: "delete", key: "check:" + match[1], tree: null, raw: line };
  // "delete need NAME" is the same as "delete NAME": a model writes the word need as the need line has it (f0 trip-dates).
  if ((match = /^delete\s+(?:need\s+)?(\S+)$/.exec(line))) return { kind: "delete", key: match[1], tree: null, raw: line };
  if ((match = /^need\s+([\p{L}_][\p{L}\p{N}_]*)\s*=(?!=)(.*)$/u.exec(line))) return entry("need", match[1], match[2]);
  if ((match = /^(\$\/[\p{L}\p{N}_/]+)\s*:=(.*)$/u.exec(line))) return entry("formula", match[1], match[2]);
  if ((match = /^(\$\/[\p{L}\p{N}_/]+)\s*=(?!=)(.*)$/u.exec(line))) return entry("state", match[1], match[2]);
  if (/^app\s*\(/.test(line)) return entry("app", "app", line);
  if (/^check\s*\(/.test(line)) {
    const tree = parseExpression(line);
    return { kind: "check", key: "check:" + (tree.args[0]?.v ?? ""), tree, raw: line };
  }
  if ((match = /^([\p{L}_][\p{L}\p{N}_]*)\s*=(?!=)(.*)$/u.exec(line))) return entry("component", match[1], match[2]);
  throw new DslError("line not understood");
}

/** The app as ordered entries by key. Feed lines one at a time: this is also how a stream and a patch arrive. */
class AppFile {
  constructor() {
    this.entries = new Map();
    this.faults = [];
    this.partFaults = [];
    this.tops = new Map();
    this.removed = new Set();
  }

  /** Line faults belong to the step that fed the lines. A new step starts without them, so a repair can end clean. */
  beginStep() { this.faults = []; }

  /**
   * Returns the entry, or null when the line was empty, a comment, or a fault.
   * origin is "author" for a line from a model, the loop, or a person, and "part" for a line that a part wrote.
   */
  feed(raw, lineNumber = null, origin = "author") {
    let entry;
    try { entry = parseLine(raw); } catch (failure) {
      if (!(failure instanceof DslError)) throw failure;
      this.faults.push({ line: lineNumber, text: raw.trim(), message: failure.message });
      return null;
    }
    if (!entry) return null;
    if (entry.kind === "delete") {
      const key = [...this.entries.keys()].find((k) => k === entry.key || k === "need:" + entry.key);
      if (key) { this.entries.delete(key); this.removed.add(key); } else this.faults.push({ line: lineNumber, text: entry.raw, message: `nothing named ${entry.key}` });
      return entry;
    }
    const key = entry.kind === "need" ? "need:" + entry.key : entry.key;
    entry.origin = origin;
    if (origin === "author") this.removed.delete(key);
    // A state line and a formula line share one path. The later one replaces the earlier one in place.
    this.entries.set(key, entry);
    return entry;
  }

  feedText(text) {
    text.split("\n").forEach((line, index) => this.feed(line, index + 1));
    return this;
  }

  /** True when a part may write this key: no author wrote it and no author deleted it. */
  open(key) { return !this.removed.has(key) && (this.entries.get(key)?.origin ?? "part") === "part"; }

  of(kind) { return [...this.entries.values()].filter((entry) => entry.kind === kind); }
  get(key) { return this.entries.get(key); }
  text() { return [...this.entries.values()].map((entry) => entry.raw).join("\n") + "\n"; }
}

/** An expression tree as text, so a part can write lines from the arguments of a need. */
function show(node) {
  switch (node.k) {
    case "lit": return typeof node.v === "string" ? JSON.stringify(node.v) : String(node.v);
    case "path": return "$" + (node.abs ? "/" : "") + node.segs.join("/");
    case "ref": return node.name;
    case "list": return "[" + node.items.map(show).join(", ") + "]";
    // A field name that is not a plain word keeps its quotes, so the printed line parses again.
    case "map": return "{" + node.entries.map(([key, inner]) => `${/^[\p{L}_][\p{L}\p{N}_]*$/u.test(key) ? key : JSON.stringify(key)}: ${show(inner)}`).join(", ") + "}";
    case "field": return show(node.of) + "." + node.name;
    case "index": return `${show(node.of)}[${show(node.at)}]`;
    case "un": return node.op + show(node.arg);
    case "bin": return `(${show(node.left)} ${node.op} ${show(node.right)})`;
    case "if": return `(${show(node.test)} ? ${show(node.yes)} : ${show(node.no)})`;
    case "call": return `${node.name}(${[...node.args.map(show), ...Object.entries(node.named).map(([key, inner]) => `${key}=${show(inner)}`)].join(", ")})`;
  }
  throw new DslError("cannot print " + node.k);
}

return { DslError, parseExpression, parseLine, AppFile, show };
})();
// core.js
const { CATALOG, ICONS, ACTIONS, TONES, dayNumber, FUNCTIONS, given, App, settings } = (() => {
// Runs an app file without any platform: state, formulas, actions, and the component tree.
// A renderer draws the tree. The checks of the host are in check.js.



/** Positional arguments of each component of the prototype catalog. */
const CATALOG = {
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
const ICONS = { Trash: "🗑", Plus: "+", Minus: "−", Refresh: "↻", Check: "✓", ArrowUp: "↑", ArrowDown: "↓", ArrowLeft: "←", ArrowRight: "→", Edit: "✎", Close: "✕" };
const NAMED = new Set(["visible", "enabled", "variant", "tone", "emphasis", "weight", "min", "max", "step", "empty", "placeholder", "gap", "selected", "kind"]);
const ACTIONS = new Set(["set", "toggle", "inc", "dec", "append", "remove", "clear", "when", "move", "flip", "animate"]);
/** The data calls of a Drawing and of a Model3d, with the arguments of each one in order. A color follows them. */
const SHAPES = { circle: ["x", "y", "r"], rect: ["x", "y", "w", "h"], line: ["x1", "y1", "x2", "y2"], text: ["x", "y", "text"], polygon: ["points"] };
const BODIES = { box: ["part", "x", "y", "z", "w", "h", "d"], cylinder: ["part", "x", "y", "z", "radius", "length", "axis"] };
const TEXTS = new Set(["text", "part", "axis"]), EXTRAS = ["radius", "width", "size", "stroke"];
// A shape also takes its own extra value after the color with no name. The dev run of the dialogue check: 5 of 16 plans
// that think wrote text(x, y, "Земля", "muted", 12) or line(x1, y1, x2, y2, "muted", 2), and no repair step found the cause.
const LAST = { text: "size", line: "width", rect: "radius" };
/** The colors of a shape or a body by role. "accent" is the palette of the app. A different color is "#RRGGBB", and a renderer draws a text that it does not know as ink. */
const TONES = { ink: "#0f172a", muted: "#94a3b8", accent: null, surface: "#ffffff", water: "#38bdf8", land: "#65a30d", sun: "#facc15", sand: "#e7d3a8", wood: "#a16207", success: "#16a34a", warning: "#ea580c" };

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

const FUNCTIONS = new Set([...Object.keys(PLAIN), ...Object.keys(ROWWISE), "if"]);

/** Equal values. Two lists or two rows are equal when each value in them is equal, with no look at the row id of the host. */
function same(a, b) {
  if (a === b) return true;
  if (typeof a !== "object" || typeof b !== "object" || !a || !b || Array.isArray(a) !== Array.isArray(b)) return false;
  const keys = (value) => Object.keys(value).filter((key) => key !== "id");
  return keys(a).length === keys(b).length && keys(a).every((key) => same(a[key], b[key]));
}

/** The arguments of a component call by property name: positional ones by the catalog order, then the named ones. */
const given = (tree) => ({ ...Object.fromEntries((CATALOG[tree.name] ?? []).map((key, index) => [key, tree.args[index]])), ...tree.named });

class App {
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
        // For the emitter: the stored list of the rows, and the row template drawn for a row that it gives, such as a blank row.
        node.source = list;
        node.blank = (row) => visit(node.template, { it: row, index: 0, list }, undefined, depth + 1);
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
const settings = { gate: "smoke", now: () => Date.now() };

return { CATALOG, ICONS, ACTIONS, TONES, dayNumber, FUNCTIONS, given, App, settings };
})();
// parts.js
const { rules, PART_KINDS, expand } = (() => {
// Generic parts. A part turns one need line into ordinary app lines: state, formulas, components, and checks.
// After that the lines are plain lines. The loop can patch each one by its name.
// A part is a kind of need, never a named app.



/** The choice step. Rules here. A closed-choice model (Jev, or the local one) can take its place. */
const rules = (question, options, fallback) => fallback ?? options[0];

const q = JSON.stringify;
// A member of a tabs or steps group is the name of a need. A plan that knows $/NAME as the value of a need also writes $/NAME or $NAME
// there (route run of the unified prompt: 2 of 45 plans), and the host reads it as the name.
const memberName = (member) => member.name ?? (member.k === "path" && member.segs?.length === 1 ? member.segs[0] : undefined);
/** A formula of a row, such as $word, as a formula of the row that the value $/NAME holds. */
function rebase(tree, name) {
  if (!tree || typeof tree !== "object") return tree;
  if (Array.isArray(tree)) return tree.map((inner) => rebase(inner, name));
  if (tree.k === "path" && !tree.abs) return { k: "path", abs: true, segs: [name, ...tree.segs] };
  return Object.fromEntries(Object.entries(tree).map(([key, inner]) => [key, rebase(inner, name)]));
}
const label = (id) => id.replace(/([a-z])([A-Z])/g, "$1 $2").replace(/_/g, " ").replace(/^./, (c) => c.toUpperCase());

// The arguments of a part that are formulas of a row of its list.
const ROW_FORMULAS = { deck: { named: ["front", "back", "mark", "answer", "picture"] }, chart: { args: [2, 3] }, entries: { named: ["show", "filter", "sort", "group"] } };
const keysOf = (rows) => (rows?.k === "list" ? rows.items.flatMap((row) => (row.k === "map" ? row.entries.map(([key]) => key) : [])) : []);
/** The name of a list and the fields of its rows, when the file can tell: an entries need, a state line with rows, or a sort or a filter of one. */
function rowsOf(file, tree, hops = 0) {
  if (!tree || hops > 6) return null;
  if (tree.k === "call" && ["sort", "filter"].includes(tree.name)) return rowsOf(file, tree.args[0], hops + 1);
  if (tree.k !== "path" || !tree.abs || tree.segs.length !== 1) return null;
  const name = tree.segs[0], need = file.get("need:" + name)?.tree, line = file.get("$/" + name);
  if (need?.name === "entries") return { name, fields: new Set([...(need.named.fields?.entries ?? [["name"]]).map(([key]) => key), ...keysOf(need.named.rows)]) };
  if (line?.kind === "state") return { name, fields: new Set(keysOf(line.tree)) };
  return line ? rowsOf(file, line.tree, hops + 1) : null;
}
/**
 * A plan with no thinking writes a row field as a value of the app: deck("Cards", $/verbes, front=$/verbe) (f0 verbs-fr-2, kanji-ja-2).
 * In a formula of a row, $/name is the row field $name when the rows of the list have that field and no line sets $/name.
 * The host writes the need line again with $name. Returns a note for the report, or null when it changed nothing.
 */
function readRowFields(file, entry) {
  const need = entry.tree, spot = need.k === "call" && Object.hasOwn(ROW_FORMULAS, need.name) ? ROW_FORMULAS[need.name] : null;
  if (!spot) return null;
  const list = need.name === "entries" ? { k: "path", abs: true, segs: [entry.key] } : need.args[1], rows = rowsOf(file, list);
  if (!rows) return null;
  const read = new Set();
  let unsure = false;
  const swap = (tree) => {
    if (!tree || typeof tree !== "object") return tree;
    if (Array.isArray(tree)) return tree.map(swap);
    if (tree.k !== "path" || !tree.abs || tree.segs.length !== 1) return Object.fromEntries(Object.entries(tree).map(([key, inner]) => [key, swap(inner)]));
    const name = tree.segs[0];
    // A formula of a row that reads the whole list, front=$/kanji for the list $/kanji, is wrong in a way that the host cannot
    // correct. Then the host changes nothing in this need, and its faults stay for a repair step (f0 kanji-ja-2).
    if (name === rows.name || name === list.segs?.[0]) unsure = true;
    if (!rows.fields.has(name) || file.get("$/" + name) || file.get("need:" + name)) return tree;
    read.add(name);
    return { ...tree, abs: false };
  };
  const tree = { ...need, args: need.args.map((arg, index) => (spot.args?.includes(index) ? swap(arg) : arg)), named: Object.fromEntries(Object.entries(need.named).map(([key, arg]) => [key, spot.named?.includes(key) ? swap(arg) : arg])) };
  const fixed = `need ${entry.key} = ${show(tree)}`;
  if (!read.size || unsure) return null;
  try { parseLine(fixed); } catch { return null; }
  file.feed(fixed);
  const names = [...read];
  return `need ${entry.key}: ${names.map((name) => "$/" + name).join(", ")} ${names.length > 1 ? "are fields" : "is a field"} of the rows of $/${rows.name}, and no line sets ${names.length > 1 ? "them" : "it"} as a value of the app. The host read ${names.map((name) => "$" + name).join(", ")} and wrote the need line again`;
}

const PARTS = {
  // need bill = input("money", "Bill", 80, min=0, max=500)
  async input(name, need, { choose }) {
    const [kind, title, value] = need.args, extras = need.named;
    const k = kind?.v;
    const named = Object.entries(extras).filter(([key]) => key !== "options").map(([key, tree]) => `, ${key}=${show(tree)}`).join("");
    let widget;
    if (k === "choice") widget = `${(extras.options?.items.length ?? 9) <= 4 ? "Segmented" : "Select"}(${show(title)}, $/${name}, ${show(extras.options ?? { k: "list", items: [] })})`;
    else if (k === "toggle") widget = `Toggle(${show(title)}, $/${name})`;
    else if (k === "text") widget = `TextField(${show(title)}, $/${name})`;
    else if (k === "choices") widget = `Choices(${show(title)}, $/${name}, ${show(extras.options ?? { k: "list", items: [] })}${extras.max ? `, max=${show(extras.max)}` : ""})`;
    else if (k === "date" || k === "time") widget = `${k === "date" ? "DateField" : "TimeField"}(${show(title)}, $/${name})`;
    else {
      const ranged = extras.min && extras.max;
      const pick = await choose(`widget for ${name}`, ranged ? ["Slider", "NumberField"] : ["NumberField"], k === "percent" && ranged ? "Slider" : "NumberField");
      widget = `${pick}(${show(title)}, $/${name}${named}${k === "count" && !extras.step ? ", step=1" : ""})`;
    }
    return { lines: [`$/${name} = ${show(value)}`, `${name}_field = ${widget}`], top: [`${name}_field`] };
  },

  // need perPerson = figure("money", "Per person", $/bill / $/people, example=[{bill: 100, people: 4}, 25])
  async figure(name, need, { hero }) {
    const [format, title, formula] = need.args, f = format?.v;
    const text = { money: `money($/${name})`, percent: `percent($/${name})`, text: `text($/${name})` }[f] ?? `text(round($/${name}, ${need.named.decimals ? show(need.named.decimals) : f === "count" ? 0 : 2}))`;
    const lines = [`$/${name} := ${show(formula)}`, `$/${name}_text := ${text}`];
    lines.push(hero === name
      ? `${name}_view = Card([Metric(${show(title)}, $/${name}_text, emphasis="display")], variant="hero")`
      : `${name}_view = Metric(${show(title)}, $/${name}_text)`);
    const example = need.named.example;
    if (example) {
      const [given, result] = example.items ?? [];
      // v1 budget-es-1 wrote example=[{importe: 0}]: the model must hear what is missing, not a host error text.
      if (given?.k !== "map" || !result) throw new DslError(`example needs two items, the given values and the result that you computed: example=[{NAME: value}, result]`);
      const puts = given.entries.map(([key, tree]) => `put($/${key}, ${show(tree)})`);
      const test = typeof result.v === "number" ? `near($/${name}, ${show(result)})` : `$/${name} == ${show(result)}`;
      // An example with no given value, example=[{}, 0], has no put step (f0 words-ko-3 threw "unexpected ','" here).
      lines.push(`check(${[q(name + " example"), ...puts, `expect(${test})`].join(", ")})`);
    }
    return { lines, top: [`${name}_view`] };
  },

  // need tasks = entries("Tasks", fields={task: "text", level: ["high", "low"], done: "check"}, rows=[], edit=true, reorder=true, filter=$done, sort=$task, order=$/order, show={double: $n * 2})
  // More: field kinds "date", "time", "switch", "count". confirm="Delete?", sheet=true, group=$field, sort=[$day, $time], hidden=true.
  async entries(name, need) {
    const title = need.args[0], opt = need.named, fields = opt.fields?.entries ?? [["name", { k: "lit", v: "text" }]];
    const labels = Object.fromEntries((opt.labels?.entries ?? []).map(([key, tree]) => [key, tree.v])), text = (key, fallback) => (opt[key] ? show(opt[key]) : q(fallback));
    const lines = [`$/${name} = ${show(opt.rows ?? { k: "list", items: [] })}`];
    // The rows are data for a deck, a chart, or a figure, and the list is not on the screen.
    if (opt.hidden?.v === true) return { lines, top: [] };
    const cells = [], marks = [], add = [], blank = [], reset = [], canAdd = opt.add?.v !== false, edit = opt.edit?.v === true;
    let firstText = null;
    for (const [field, kindTree] of fields) {
      const choice = kindTree.k === "list" ? show(kindTree) : null;
      const kind = choice ? "choice" : ["number", "money", "percent"].includes(kindTree.v) ? "number" : ["check", "switch", "count", "date", "time", "picture"].includes(kindTree.v) ? kindTree.v : "text";
      // The field for a value that the person types or picks.
      const widget = { number: "NumberField", date: "DateField", time: "TimeField" }[kind] ?? "TextField";
      // The add form names each field. A control of a row takes the caption of labels= only: without one, the emitter names it by its line.
      const caption = q(labels[field] ?? label(field)), cell = q(labels[field] ?? "");
      const pick = (text, bind) => `${choice && kindTree.items.length <= 4 ? "Segmented" : "Select"}(${text}, ${bind}, ${choice})`;
      // The cell of the row: a check mark, a choice, a field that the person can change, or a plain value.
      if (kind === "check") lines.push(`${name}_${field} = CheckBox(${cell}, $${field})`);
      else if (kind === "switch") lines.push(`${name}_${field} = Toggle(${cell}, $${field})`);
      else if (kind === "count") {
        // A count in the row: a minus and a plus button around the number, and the person can also type it
        // (route steps-chart: seven day rows with a count of steps, and no way to give 8000 but by taps). Not below 0.
        lines.push(`${name}_${field}_minus = IconButton("Minus", action=set($${field}, max(0, $${field} - 1)))`, `${name}_${field} = NumberField(${cell}, $${field}, min=0, step=1)`, `${name}_${field}_plus = IconButton("Plus", action=inc($${field}))`);
        cells.push(`${name}_${field}_minus`);
      } else if (kind === "picture") lines.push(`${name}_${field} = Picture($${field})`);
      else if (kind === "choice") lines.push(`${name}_${field} = ${pick(cell, "$" + field)}`);
      else if (edit) lines.push(`${name}_${field} = ${widget}(${cell}, $${field}${kind === "text" ? ", weight=1" : ""})`);
      else if (kind !== "text") lines.push(`${name}_${field} = Badge($${field})`);
      else lines.push(`${name}_${field} = Text($${field}${firstText ? "" : ", weight=1"})`);
      if (kind === "text") firstText ??= field;
      (kind === "check" ? marks : cells).push(`${name}_${field}`);
      if (kind === "count") cells.push(`${name}_${field}_plus`);
      if (!canAdd) continue;
      // A new row starts with no check mark, with its switch on, and with no picture.
      if (["check", "switch", "picture"].includes(kind)) { blank.push(`${field}: ${{ check: "false", switch: "true", picture: '""' }[kind]}`); continue; }
      // A count has a number field in the add form and its stepper in the row. final2: with the stepper only, three apps needed 30 taps for a count of 30.
      const empty = { number: "0", count: "0", date: "today()", time: '"08:00"' }[kind] ?? (kind === "choice" ? show(kindTree.items[0]) : '""');
      const typed = kind === "count" ? `NumberField(${caption}, $/${name}_new_${field}, min=0, step=1)` : `${widget}(${caption}, $/${name}_new_${field}${kind === "text" ? ", weight=1" : ""})`;
      lines.push(`$/${name}_new_${field} = ${empty}`, `${name}_new_${field} = ${kind === "choice" ? pick(caption, `$/${name}_new_${field}`) : typed}`);
      add.push(`${name}_new_${field}`);
      blank.push(`${field}: $/${name}_new_${field}`);
      if (["text", "number", "count"].includes(kind)) reset.push(`set($/${name}_new_${field}, ${empty})`);
    }
    // A value for each row, from the fields of that row.
    for (const [key, formula] of opt.show?.entries ?? []) { lines.push(`${name}_${key} = Badge(${show(formula)})`); cells.push(`${name}_${key}`); }
    if (opt.reorder?.v === true) { lines.push(`${name}_up = IconButton("ArrowUp", action=move(-1))`, `${name}_down = IconButton("ArrowDown", action=move(1))`); cells.push(`${name}_up`, `${name}_down`); }
    if (opt.remove?.v !== false) {
      // With confirm= the delete button asks first. The row that waits for the answer is known by its id.
      if (opt.confirm) lines.push(`$/${name}_drop = ""`, `${name}_remove = IconButton("Trash", action=set($/${name}_drop, $id))`,
        `${name}_yes = Button(${text("yes", "Yes")}, action=[remove($/${name}, $id == $/${name}_drop), set($/${name}_drop, "")])`, `${name}_no = Button(${text("no", "No")}, action=set($/${name}_drop, ""))`,
        `${name}_confirm = Dialog(${show(opt.confirm)}, [Row([${name}_yes, ${name}_no])], visible=$/${name}_drop != "")`);
      else lines.push(`${name}_remove = IconButton("Trash", action=remove())`);
      cells.push(`${name}_remove`);
    }
    // Many check marks get a line of their own under the other cells.
    lines.push(marks.length > 2 ? `${name}_row = Card([Row([${cells.join(", ")}]), Row([${marks.join(", ")}])])` : `${name}_row = Row([${[...cells.slice(0, firstText ? 1 : 0), ...cells.slice(firstText ? 1 : 0), ...marks].join(", ")}])`);
    let source = `$/${name}`;
    if (opt.filter) source = `filter(${source}, ${show(opt.filter)})`;
    if (opt.sort) source = `sort(${source}, ${show(opt.sort)}${opt.order ? ", " + show(opt.order) : ""})`;
    lines.push(`${name}_list = List(${source}, ${name}_row${opt.group ? `, group=${show(opt.group)}` : ""}, empty=${text("emptyText", "Nothing here yet")})`);
    const parts = [`${name}_list`];
    if (opt.remove?.v !== false && opt.confirm) parts.push(`${name}_confirm`);
    if (canAdd) {
      const ready = firstText ? `, enabled=len(trim($/${name}_new_${firstText})) > 0` : "", sheet = opt.sheet?.v === true;
      lines.push(`${name}_add = Button(${text("addText", "Add")}, action=[${[`append($/${name}, {${blank.join(", ")}})`, ...reset, ...(sheet ? [`set($/${name}_adding, false)`] : [])].join(", ")}]${ready})`);
      // With sheet=true the add form is a dialog that a button opens. The add button and the close button close it.
      if (sheet) lines.push(`$/${name}_adding = false`, `${name}_open = Button(${text("addText", "Add")}, action=set($/${name}_adding, true))`, `${name}_close = IconButton("Close", action=set($/${name}_adding, false))`,
        `${name}_addRow = Dialog(${text("addText", "Add")}, [${[...add, `Row([${name}_close, ${name}_add])`].join(", ")}], visible=$/${name}_adding)`);
      else lines.push(`${name}_addRow = Row([${[...add, `${name}_add`].join(", ")}])`);
      parts.push(...(sheet ? [`${name}_open`] : []), `${name}_addRow`);
    }
    lines.push(`${name}_view = Section(${show(title)}, [${parts.join(", ")}])`);
    return { lines, top: [`${name}_view`] };
  },

  // need line = chart("Steps", $/days, $day, $steps, kind="line"): one bar or point for each row of a list.
  async chart(name, need) {
    const [title, list, point, value] = need.args;
    return { lines: [`${name}_chart = Chart(${show(list)}, ${show(point)}, ${show(value)}, kind=${show(need.named.kind ?? { k: "lit", v: "bar" })})`, `${name}_view = Section(${show(title)}, [${name}_chart])`], top: [`${name}_view`] };
  },

  // need quiz = deck("Quiz", $/words, front=$word, back=$meaning, mark=$known): one row at a time. A tap shows the back, then yes or no goes to the next row.
  async deck(name, need) {
    const [title, list] = need.args, opt = need.named, text = (key, fallback) => (opt[key] ? show(opt[key]) : q(fallback));
    // A field of the row in front, such as $word, is a field of the row that the deck shows now.
    const now = (tree) => show(rebase(tree, `${name}_now`));
    // picture=$field: a picture on each card, from the description in that field of the row.
    const picture = opt.picture ? [`${name}_picture = Picture(text(${now(opt.picture)}))`] : [], inCard = opt.picture ? `${name}_picture, ` : "";
    // With answer= the person types the answer. The check button says right or wrong, and a right answer counts one point. The check button goes away after its tap, so a point counts once.
    if (opt.answer) return {
      lines: [`$/${name}_i = 0`, `$/${name}_typed = ""`, `$/${name}_checked = false`, `$/${name}_points = 0`,
        `$/${name}_now := at(${show(list)}, $/${name}_i)`, `$/${name}_end := $/${name}_i >= len(${show(list)})`,
        `$/${name}_right := lower(trim(text($/${name}_typed))) == lower(trim(text(${now(opt.answer)})))`,
        ...picture, `${name}_front = Heading(text(${now(opt.front)}))`,
        `${name}_field = TextField(${text("field", "Your answer")}, $/${name}_typed, enabled=!$/${name}_checked)`,
        `${name}_check = Button(${text("check", "Check")}, action=[when($/${name}_right, inc($/${name}_points)), set($/${name}_checked, true)], visible=!$/${name}_checked)`,
        `${name}_result = Notice($/${name}_right ? ${text("right", "Right")} : ${text("wrong", "Wrong")} + ": " + text(${now(opt.answer)}), visible=$/${name}_checked)`,
        `${name}_next = Button(${text("next", "Next")}, action=[inc($/${name}_i), set($/${name}_typed, ""), set($/${name}_checked, false)], visible=$/${name}_checked)`,
        `${name}_card = Card([${inCard}${name}_front, ${name}_field, ${name}_check, ${name}_result, ${name}_next], variant="hero", visible=!$/${name}_end)`,
        `${name}_again = Button(${text("again", "Again")}, action=[set($/${name}_i, 0), set($/${name}_points, 0), set($/${name}_typed, ""), set($/${name}_checked, false)])`,
        `${name}_endCard = Card([Heading(${text("done", "Done")}), ${name}_again], visible=$/${name}_end)`,
        `${name}_score = Metric(${text("points", "Points")}, $/${name}_points)`,
        `${name}_view = Section(${show(title)}, [${name}_card, ${name}_endCard, ${name}_score])`],
      top: [`${name}_view`],
    };
    // Without back= the deck is pages: no hidden side and no count. A next and a back button, and an end after the last row.
    if (!opt.back) return {
      lines: [`$/${name}_i = 0`, `$/${name}_now := at(${show(list)}, $/${name}_i)`, `$/${name}_end := $/${name}_i >= len(${show(list)})`,
        ...picture, `${name}_front = Heading(text(${now(opt.front)}))`,
        `${name}_prev = Button(${text("prev", "Back")}, action=dec($/${name}_i), enabled=$/${name}_i > 0)`, `${name}_next = Button(${text("next", "Next")}, action=inc($/${name}_i))`,
        `${name}_card = Card([${inCard}${name}_front, Row([${name}_prev, ${name}_next])], variant="hero", visible=!$/${name}_end)`,
        `${name}_again = Button(${text("again", "Again")}, action=set($/${name}_i, 0))`,
        `${name}_endCard = Card([Heading(${text("done", "Done")}), ${name}_again], visible=$/${name}_end)`,
        `${name}_view = Section(${show(title)}, [${name}_card, ${name}_endCard])`],
      top: [`${name}_view`],
    };
    const next = `inc($/${name}_i), set($/${name}_open, false)`;
    return {
      lines: [`$/${name}_i = 0`, `$/${name}_open = false`, `$/${name}_yes = 0`, `$/${name}_no = 0`,
        `$/${name}_now := at(${show(list)}, $/${name}_i)`, `$/${name}_end := $/${name}_i >= len(${show(list)})`,
        ...picture, `${name}_front = Heading(text(${now(opt.front)}))`, `${name}_back = Text(text(${now(opt.back)}), visible=$/${name}_open)`,
        `${name}_show = Button(${text("show", "Show")}, action=set($/${name}_open, true), visible=!$/${name}_open)`,
        `${name}_yesButton = Button(${text("yes", "Yes")}, action=[inc($/${name}_yes), ${opt.mark ? `set(${now(opt.mark)}, true), ` : ""}${next}], visible=$/${name}_open)`,
        `${name}_noButton = Button(${text("no", "No")}, action=[inc($/${name}_no), ${next}], visible=$/${name}_open)`,
        `${name}_card = Card([${inCard}${name}_front, ${name}_back, ${name}_show, Row([${name}_yesButton, ${name}_noButton])], variant="hero", visible=!$/${name}_end)`,
        `${name}_again = Button(${text("again", "Again")}, action=[set($/${name}_i, 0), set($/${name}_yes, 0), set($/${name}_no, 0), set($/${name}_open, false)])`,
        `${name}_endCard = Card([Heading(${text("done", "Done")}), ${name}_again], visible=$/${name}_end)`,
        `${name}_counts = Row([Metric(${text("yes", "Yes")}, $/${name}_yes), Metric(${text("no", "No")}, $/${name}_no)])`,
        `${name}_view = Section(${show(title)}, [${name}_card, ${name}_endCard, ${name}_counts])`],
      top: [`${name}_view`],
    };
  },

  // need setup = steps(["Genres", "Pages"], [[genres], [pages, week]], finish="Start"): other needs in groups, one group at a time.
  async steps(name, need, { tops }) {
    const [titles, groups] = need.args, opt = need.named, text = (key, fallback) => (opt[key] ? show(opt[key]) : q(fallback)), last = groups.items.length - 1;
    const lines = [`$/${name}_step = 0`, `$/${name}_done = false`, `${name}_title = Heading(at(${show(titles)}, $/${name}_step))`];
    groups.items.forEach((group, index) => lines.push(`${name}_step${index} = Column([${group.items.flatMap((member) => tops(memberName(member))).join(", ")}], visible=$/${name}_step == ${index})`));
    lines.push(`${name}_back = Button(${text("back", "Back")}, action=dec($/${name}_step), enabled=$/${name}_step > 0)`,
      `${name}_next = Button(${text("next", "Next")}, action=inc($/${name}_step), visible=$/${name}_step < ${last})`,
      `${name}_finish = Button(${text("finish", "Done")}, action=set($/${name}_done, true), visible=$/${name}_step == ${last})`,
      `${name}_doneNote = Notice(${text("done", "Done")}, visible=$/${name}_done)`,
      `${name}_view = Column([${[`${name}_title`, ...groups.items.map((_, index) => `${name}_step${index}`), `Row([${name}_back, ${name}_next, ${name}_finish])`, `${name}_doneNote`].join(", ")}])`);
    return { lines, top: [`${name}_view`], holds: groups.items.flatMap((group) => group.items.map(memberName)) };
  },

  // need pages = tabs(["Summary", "Add"], [[total, chart], [costs]]): other needs in groups, one group at a time. The person opens a tab at any time.
  async tabs(name, need, { tops }) {
    const [titles, groups] = need.args;
    return { lines: [`${name}_view = Tabs(${show(titles)}, [${groups.items.map((group) => `[${group.items.flatMap((member) => tops(memberName(member))).join(", ")}]`).join(", ")}])`],
      top: [`${name}_view`], holds: groups.items.flatMap((group) => group.items.map(memberName)) };
  },

  // need done = progress("Done today", count($/habits, $done), len($/habits))
  async progress(name, need, { hero }) {
    const [title, value, goal] = need.args;
    const ring = `Ring($/${name}, $/${name}_goal, $/${name}_text)`;
    return {
      lines: [`$/${name} := ${show(value)}`, `$/${name}_goal := ${show(goal)}`, `$/${name}_text := text($/${name}) + " / " + text($/${name}_goal)`,
        hero === name ? `${name}_view = Card([Heading(${show(title)}), ${ring}], variant="hero")` : `${name}_view = Section(${show(title)}, [${ring}])`],
      top: [`${name}_view`],
    };
  },

  // need cups = counter("Cups", 0, step=1, min=0)
  async counter(name, need) {
    const [title, value] = need.args, step = need.named.step ? show(need.named.step) : "1";
    const down = need.named.min ? `set($/${name}, max(${show(need.named.min)}, $/${name} - ${step}))` : `dec($/${name}, ${step})`;
    const up = need.named.max ? `set($/${name}, min(${show(need.named.max)}, $/${name} + ${step}))` : `inc($/${name}, ${step})`;
    return {
      lines: [`$/${name} = ${show(value ?? { k: "lit", v: 0 })}`, `${name}_minus = IconButton("Minus", action=${down})`,
        `${name}_value = Metric(${show(title)}, $/${name})`, `${name}_plus = IconButton("Plus", action=${up})`,
        `${name}_view = Row([${name}_minus, ${name}_value, ${name}_plus])`],
      top: [`${name}_view`],
    };
  },

  // need tea = timer("Tea", $/kind == "green" ? 120 : 180, alert=true): a countdown with start, pause, and reset. The host moves it with its clock (App.tick).
  // need set = timer("Workout", 40, rounds=8, rest=20): each round is a work phase and then a rest phase. $/NAME counts the work phases that ended.
  // Evidence: 4 of Alphy's 45 corpus cases are timers (workout-de, tea-tr, pomodoro-zh, eggs-it). All lines are plain lines. Only the passing of time is the host's.
  async timer(name, need) {
    const [title, seconds] = need.args, opt = need.named, text = (key, fallback) => (opt[key] ? show(opt[key]) : q(fallback)), at = (part) => `$/${name}_${part}`;
    // The phase and the round show when the timer has more than one phase.
    const phased = (opt.rest && opt.rest.v !== 0) || (opt.rounds && opt.rounds.v !== 1);
    const lines = [`$/${name} = 0`, `${at("on")} = false`, `${at("round")} = 1`, `${at("resting")} = false`, `${at("over")} = false`, `${at("spent")} = 0`,
      `${at("phase")} := ${at("resting")} ? ${opt.rest ? show(opt.rest) : 0} : ${show(seconds ?? { k: "lit", v: 60 })}`,
      // The time that is left in whole seconds. round(…, 3) keeps a sum of parts of a second from showing one second more.
      `${at("left")} := ceil(round(max(0, ${at("phase")} - ${at("spent")}), 3))`,
      `${at("clock")} := pad2(floor(${at("left")} / 60)) + ":" + pad2(${at("left")} % 60)`,
      `${name}_time = Metric(${show(title)}, ${at("clock")}, emphasis="display")`,
      ...(phased ? [`${name}_phaseText = Text((${at("resting")} ? ${text("restText", "Rest")} : ${text("workText", "Work")}) + " " + text(${at("round")}) + " / " + text(${opt.rounds ? show(opt.rounds) : 1}))`] : []),
      `${name}_bar = Progress(${at("spent")}, ${at("phase")})`,
      `${name}_start = Button(${text("start", "Start")}, action=set(${at("on")}, true), visible=!${at("on")} && !${at("over")})`,
      `${name}_pause = Button(${text("pause", "Pause")}, action=set(${at("on")}, false), visible=${at("on")})`,
      `${name}_reset = Button(${text("reset", "Reset")}, action=[set(${at("on")}, false), set(${at("spent")}, 0), set(${at("round")}, 1), set(${at("resting")}, false), set(${at("over")}, false)], enabled=${at("on")} || ${at("spent")} > 0 || ${at("round")} > 1 || ${at("resting")} || ${at("over")})`,
      `${name}_doneNote = Notice(${text("done", "Time is up")}, visible=${at("over")})`,
      // An alert (a sound or a vibration) is outside the prototype. The note shows that the plan asked for one.
      ...(opt.alert?.v === true ? [`${name}_alert = Text("alert at the end")`] : [])];
    const parts = [`${name}_time`, ...(phased ? [`${name}_phaseText`] : []), `${name}_bar`, `Row([${name}_start, ${name}_pause, ${name}_reset])`, `${name}_doneNote`, ...(opt.alert?.v === true ? [`${name}_alert`] : [])];
    return { lines: [...lines, `${name}_view = Card([${parts.join(", ")}])`], top: [`${name}_view`] };
  },

  // need photo = picture("A plate of pasta with tomato sauce"): the host makes the picture from the description. The prototype draws a box with the description.
  // With found="search words" the host finds a real photo. caption=, credit=, and tag= are texts with the picture, each a line of its own (screen 1 of the dialogue mocks).
  async picture(name, need) {
    const opt = need.named, picture = `Picture(${show(need.args[0] ?? opt.found)}${opt.found ? `, found=${show(opt.found)}` : ""})`;
    const texts = [["caption", "Text(", ")"], ["credit", "Text(", ', variant="muted")'], ["tag", "Badge(", ")"]].filter(([key]) => opt[key]);
    if (!texts.length) return { lines: [`${name}_view = ${picture}`], top: [`${name}_view`] };
    return { lines: [`${name}_picture = ${picture}`, ...texts.map(([key, open, close]) => `${name}_${key} = ${open}${show(opt[key])}${close}`),
      `${name}_view = Column([${[`${name}_picture`, ...texts.map(([key]) => `${name}_${key}`)].join(", ")}])`], top: [`${name}_view`] };
  },

  // need orbit = drawing("Top view", 320, 320, [circle(160, 160, 30, "water"), each($/cells, rect($index * 40, 0, 36, 20, "wood"))])
  // A 2D picture from shapes that the host draws. Each number is a formula, so the picture follows the values of the app. Evidence: screens 2 and 3 of the dialogue mocks.
  async drawing(name, need) {
    const [title, width, height, ...shapes] = need.args;
    if (!height || width.k === "list" || height.k === "list") throw new DslError("drawing needs a width and a height before the shapes: drawing(label, 320, 240, [shapes])");
    // Shapes with no list around them are still the shapes.
    const list = shapes.length === 1 && shapes[0].k === "list" ? show(shapes[0]) : `[${shapes.map(show).join(", ")}]`;
    return { lines: [`${name}_view = Drawing(${[title, width, height].map(show).join(", ")}, ${list})`], top: [`${name}_view`] };
  },

  // need lap = button("One turn", [set($/angle, 0), animate($/angle, 360, 5000)], enabled=$/ready): a button with an action or a list of actions.
  async button(name, need) {
    const action = need.args[1] ?? need.named.action;
    if (!action) throw new DslError("button needs an action: button(label, set($/NAME, value))");
    const more = Object.entries(need.named).filter(([key]) => key !== "action").map(([key, tree]) => `, ${key}=${show(tree)}`).join("");
    return { lines: [`${name}_button = Button(${show(need.args[0])}, action=${show(action)}${more})`], top: [`${name}_button`] };
  },

  // need rover = model3d("Rover", [box("", 0, 0, 0, 4, 1, 2, "sand"), cylinder("wheels", -1.4, -0.8, 1.2, 0.6, 0.4, "z", "ink")], parts={wheels: "Wheels"})
  // A 3D model from boxes and cylinders. $/NAME/selected is the id of the part that a chip selected, or "". $/NAME/exploded is true when the model is taken apart.
  // Evidence: screen 4 of the dialogue mocks. A second tap on a chip clears the selection.
  async model3d(name, need) {
    const [title, bodies] = need.args, opt = need.named, text = (key, fallback) => (opt[key] ? show(opt[key]) : q(fallback)), at = (field) => `$/${name}/${field}`;
    const turn = opt.turn?.v !== false, chips = (opt.parts?.entries ?? []).map(([id, caption]) => [`${name}_part_${id}`, q(id), show(caption)]), tools = [];
    const lines = [`$/${name} = {selected: "", exploded: false, turn: 30}`,
      `${name}_model = Model3d(${show(title)}, ${show(bodies)}${turn ? `, ${at("turn")}` : ""}, exploded=${at("exploded")}, selected=${at("selected")})`,
      ...chips.map(([key, id, caption]) => `${key} = Button(${caption}, action=set(${at("selected")}, ${at("selected")} == ${id} ? "" : ${id}), selected=${at("selected")} == ${id})`)];
    if (turn) { lines.push(`${name}_left = IconButton("ArrowLeft", action=dec(${at("turn")}, 30))`, `${name}_right = IconButton("ArrowRight", action=inc(${at("turn")}, 30))`); tools.push(`${name}_left`, `${name}_right`); }
    if (opt.explode?.v !== false) { lines.push(`${name}_explode = Button(${at("exploded")} ? ${text("joinText", "Put together")} : ${text("explodeText", "Take apart")}, action=toggle(${at("exploded")}))`); tools.push(`${name}_explode`); }
    const rows = [chips.map(([key]) => key), tools].filter((row) => row.length).map((row) => `Row([${row.join(", ")}])`);
    return { lines: [...lines, `${name}_view = Card([${[`${name}_model`, ...rows].join(", ")}])`], top: [`${name}_view`] };
  },

  // need focus = card("Title", $/rover/selected == "camera" ? "The camera" : "The rover", eyebrow="In focus"): a card of text. The title and the text can be formulas.
  async card(name, need) {
    const [title, text] = need.args, made = [];
    if (need.named.eyebrow) made.push(["eyebrow", `Text(${show(need.named.eyebrow)}, variant="eyebrow")`]);
    made.push(["title", `Heading(${show(title)})`]);
    if (text) made.push(["text", `Text(${show(text)})`]);
    return { lines: [...made.map(([key, line]) => `${name}_${key} = ${line}`), `${name}_view = Card([${made.map(([key]) => `${name}_${key}`).join(", ")}])`], top: [`${name}_view`] };
  },

  // need source = link("Apollo 17 photo", "https://science.nasa.gov/...", note="Open the source"): a link to a web page. The prototype only draws it.
  async link(name, need) {
    const [title, url] = need.args;
    return { lines: [`${name}_view = Link(${show(title)}, ${show(url)}${need.named.note ? `, ${show(need.named.note)}` : ""})`], top: [`${name}_view`] };
  },

  // need steps = lesson([{title: "Look", text: "..."}, {title: "Test", text: "..."}], next="Next", back="Back", stepText="Step")
  // One step at a time, with "Step · 1 / 2" and a progress bar. $/NAME is the number of the step, from 1.
  async lesson(name, need) {
    const steps = need.args[0], opt = need.named, text = (key, fallback) => (opt[key] ? show(opt[key]) : q(fallback)), count = `len($/${name}_steps)`;
    const lines = [`$/${name} = 1`, `$/${name}_steps := ${show(steps)}`, `$/${name}_now := at($/${name}_steps, $/${name} - 1)`,
      `${name}_count = Text(${text("stepText", "Step")} + " · " + text($/${name}) + " / " + text(${count}), variant="eyebrow")`,
      `${name}_bar = Progress($/${name}, ${count})`, `${name}_title = Heading(text($/${name}_now/title))`, `${name}_text = Text(text($/${name}_now/text))`];
    const parts = [`${name}_count`, `${name}_bar`, `${name}_title`, `${name}_text`];
    // One step has no next step, so it gets no buttons: a button that is never active is a fault of the smoke run.
    if (steps.k !== "list" || steps.items.length > 1) {
      lines.push(`${name}_back = Button(${text("back", "Back")}, action=dec($/${name}), enabled=$/${name} > 1)`, `${name}_next = Button(${text("next", "Next")}, action=inc($/${name}), enabled=$/${name} < ${count})`);
      parts.push(`Row([${name}_back, ${name}_next])`);
    }
    return { lines: [...lines, `${name}_view = Card([${parts.join(", ")}])`], top: [`${name}_view`] };
  },

  // need ask = question("Is the Moon a star?", options=["Yes", "No"], right="No", rightText="Right", wrongText="Not yet")
  // One button for each answer, and a text that says right or wrong. $/NAME is true while the right answer is the chosen one.
  async question(name, need) {
    const opt = need.named, options = opt.options?.k === "list" ? opt.options.items : [], text = (key, fallback) => (opt[key] ? show(opt[key]) : q(fallback));
    if (options.length < 2 || !opt.right) throw new DslError('question needs options and the right one: question("Text", options=["Yes", "No"], right="No")');
    if (!options.some((option) => show(option) === show(opt.right))) throw new DslError(`right=${show(opt.right)} is not one of the options`);
    const at = `$/${name}_answer`, keys = options.map((_, index) => `${name}_opt${index}`);
    return {
      lines: [`${at} = ""`, `$/${name} := ${at} == ${show(opt.right)}`, `${name}_text = Heading(${show(need.args[0])})`,
        ...options.map((option, index) => `${keys[index]} = Button(${show(option)}, action=set(${at}, ${show(option)}), selected=${at} == ${show(option)})`),
        `${name}_result = Notice($/${name} ? ${text("rightText", "Right")} : ${text("wrongText", "Not yet")}, visible=${at} != "")`,
        `${name}_view = Card([${name}_text, Row([${keys.join(", ")}]), ${name}_result])`],
      top: [`${name}_view`],
    };
  },

  // need intro = content("Shown as text")
  async content(name, need) { return { lines: [`${name}_view = Text(${show(need.args[0])})`], top: [`${name}_view`] }; },

  // need chart = hole("A bar chart of spending by category"): nothing ready does it, so a model writes NAME_view later.
  async hole(name, need) { return { lines: [`${name}_view = Notice("Not built yet: " + ${show(need.args[0])})`], top: [`${name}_view`] }; },
};

const PART_KINDS = Object.keys(PARTS);

/**
 * Expands every need of the file that is new or changed. Returns the new lines and feeds them into the file.
 * The main element is the first figure or progress need, unless the choice step picks a different one.
 */
async function expand(file, choose = rules) {
  file.expanded ??= new Map();
  file.partFaults = [];
  // What the host read in a different way than a need line says. The report shows these as notes.
  file.partNotes = file.of("need").map((entry) => readRowFields(file, entry)).filter(Boolean);
  const needs = file.of("need");
  // A need that a step deleted takes the lines of its part with it (v1fix: "delete slept" left $/slept and its example check).
  const drop = (lines) => { for (const line of lines) if (file.entries.get(line)?.origin === "part") file.entries.delete(line); };
  for (const [key, lines] of [...(file.partLines ??= new Map())]) if (!needs.some((entry) => entry.key === key)) {
    drop(lines);
    for (const map of [file.partLines, file.expanded, file.tops, file.made]) map?.delete(key);
  }
  const candidates = needs.filter((entry) => ["figure", "progress"].includes(entry.tree.name)).map((entry) => entry.key);
  const hero = candidates.length ? await choose("main element", candidates, candidates[0]) : null;
  const out = [], top = [], fresh = [], held = new Set();
  // A part that holds other needs, such as steps, is built after them. Its place on the screen stays the place of its line.
  const holder = (entry) => ["steps", "tabs"].includes(entry.tree.name);
  const place = new Map();
  for (const entry of [...needs.filter((entry) => !holder(entry)), ...needs.filter(holder)]) {
    const part = entry.tree.k === "call" && Object.hasOwn(PARTS, entry.tree.name) ? PARTS[entry.tree.name] : null;
    if (!part) { file.partFaults.push({ line: null, text: entry.raw, message: `no part for need kind ${entry.tree.name ?? "?"}` }); continue; }
    let built, parsed;
    try {
      built = await part(entry.key, entry.tree, { choose, hero, tops: (other) => { if (!file.tops.has(other)) throw new DslError(`${entry.tree.name} names ${other}, and no need has that name`); return file.tops.get(other); } });
      // A part line that the host cannot read is a fault of this need, never an exception. The other needs still expand.
      parsed = built.lines.map((line) => parseLine(line));
      if (parsed.includes(null)) throw new DslError("the part made an empty line");
    } catch (failure) {
      if (!(failure instanceof DslError) && !(failure instanceof TypeError)) throw failure;
      file.partFaults.push({ line: null, text: entry.raw, message: `need ${entry.key}: ${failure.message}` });
      continue;
    }
    file.tops.set(entry.key, built.top);
    // The controls that the part made, by name, so that the report can say when a later step took one off the screen.
    const control = (made) => made.kind === "component" && made.tree.k === "call" && (made.tree.named.action || ["Button", "IconButton", "TextField", "NumberField", "Slider", "Toggle", "CheckBox", "Segmented", "Select", "DateField", "TimeField"].includes(made.tree.name));
    (file.made ??= new Map()).set(entry.key, parsed.filter(control).map((made) => made.key));
    // Each line of the part, so that a need that changes or goes away leaves no line of its earlier form behind.
    const keys = parsed.map((made) => made.key);
    drop((file.partLines.get(entry.key) ?? []).filter((line) => !keys.includes(line)));
    file.partLines.set(entry.key, keys);
    (built.holds ?? []).forEach((other) => held.add(other));
    place.set(entry.key, built.top);
    if (file.expanded.get(entry.key) === entry.raw + (entry.key === hero)) continue;
    if (!file.expanded.has(entry.key)) fresh.push(...built.top);
    file.expanded.set(entry.key, entry.raw + (entry.key === hero));
    // A line that an author wrote or deleted belongs to the author. The part leaves it as it is.
    out.push(...built.lines.filter((line, index) => file.open(parsed[index].key)));
  }
  // The screen: the main element first, then each need in the order of its line. A need that a step holds is not there twice.
  for (const entry of needs) if (place.has(entry.key) && !held.has(entry.key)) (entry.key === hero ? top.unshift.bind(top) : top.push.bind(top))(...place.get(entry.key));
  // A picture that is the first need stays on top, above the main element (menu: "a picture of the cafe on top").
  const lead = needs[0]?.tree.name === "picture" && !held.has(needs[0].key) ? place.get(needs[0].key) ?? [] : [];
  if (lead.length) top.unshift(...top.splice(top.indexOf(lead[0]), lead.length));
  const root = file.get("root");
  const screen = `root = Column([${top.join(", ")}])`;
  if (top.length && file.open("root")) { if (root?.raw !== screen) out.push(screen); }
  else if (fresh.length && root) {
    // The author owns root. A new part still has to appear, so its view joins the end of the author's list.
    const list = root.tree.k === "call" && root.tree.args[0]?.k === "list" ? root.tree.args[0] : null;
    const missing = fresh.filter((name) => !root.raw.includes(name));
    // When root is not a plain list, the report says that the need is not on the screen.
    if (list && missing.length) {
      const joined = { ...root.tree, args: [{ k: "list", items: [...list.items, ...missing.map((name) => ({ k: "ref", name }))] }, ...root.tree.args.slice(1)] };
      file.feed(`root = ${show(joined)}`);
    }
  }
  out.forEach((line) => file.feed(line, null, "part"));
  return out;
}

return { rules, PART_KINDS, expand };
})();
// check.js
const { smoke, runChecks, report } = (() => {
// The checks of the host: the report of the faults of an app file, its check lines, and the smoke run that uses each control.
// The agent loop reads the faults and the check results. An app that runs does not need this file.




/** Calls visit for each node of an expression tree. */
function walk(node, visit) {
  if (!node || typeof node !== "object") return;
  if (Array.isArray(node)) return node.forEach((inner) => walk(inner, visit));
  if (node.k) visit(node);
  Object.values(node).forEach((inner) => walk(inner, visit));
}

const finite = (value) => (typeof value === "number" ? Number.isFinite(value) : value && typeof value === "object" ? Object.values(value).every(finite) : true);

/**
 * The host uses each control once, as a person does, and reports what it sees: an action that fails, a value that stops
 * being a number, a change that no part of the screen shows, and an on-off button that looks the same in both states.
 * It finds faults. It does not decide that the app does what the request asks.
 */
function smoke(file, limit = 60) {
  // The run has its own clock, so an app with a timer gives the same result in each run. Only the run moves that clock.
  const real = settings.now;
  let clock = 0;
  settings.now = () => clock;
  try { return smokeRun(file, limit, () => { clock += 30 * 864e5; }); } finally { settings.now = real; }
}
function smokeRun(file, limit, wait) {
  const app = new App(file), faults = [], notes = [], trace = [], done = new Set(), reached = new Set(), idle = new Map();
  // What a person can see of a node. The rows of a list are data, not a look: the look is in the children.
  const look = (node) => JSON.stringify(node, (key, value) => (["row", "list", "template", "action", "bind", "items", "call"].includes(key) ? undefined : value));
  const short = (value) => (Array.isArray(value) ? `[${value.length}]` : JSON.stringify(value) ?? "nothing").slice(0, 40);
  const onOff = (node) => !node.props.bind && node.props.action && /\btoggle\(/.test(show(node.props.action));
  const deletes = (node) => /\b(remove|clear)\(/.test(show(node.props.action));
  /** The controls of the screen. usable: only those that a person can use now. In a list the first row stands for the others. */
  const controls = (usable = true) => {
    const out = [];
    const walkNode = (node, key, shown, parent) => {
      const here = shown && node.props.visible !== false && node.props.enabled !== false;
      if ((here || !usable) && (node.rowIndex ?? 0) === 0 && (node.props.action || node.props.bind)) out.push({ node, key: node.name ?? key, parent });
      node.children.forEach((child, index) => walkNode(child, key + "." + index, shown && node.props.visible !== false, node));
    };
    walkNode(app.tree(), "0", true, null);
    if (usable) out.forEach((control) => reached.add(control.key));
    return out;
  };
  const start = JSON.stringify(app.state), all = controls(false).map((control) => control.key);
  /** A control that did nothing so far gets a new try in the state of now. Returns that state. */
  const retryIdle = (list) => {
    const later = JSON.stringify(app.state);
    for (const other of list) {
      if (!idle.has(other.key) || other.node.props.bind) continue;
      if (!app.fire(other.node) && JSON.stringify(app.state) !== later) idle.delete(other.key);
      app.state = JSON.parse(later);
      app.recompute();
    }
    return later;
  };
  // Each control is used from the state in which it came to the screen. So one control cannot hide or delete what the next one needs.
  const queue = [start];
  const drain = () => {
    while (queue.length && done.size < limit) {
      const base = queue.shift();
      const reset = () => { app.state = JSON.parse(base); app.recompute(); };
      reset();
      const here = controls().map((control) => control.key);
      for (const key of here) {
        if (done.has(key) || done.size >= limit) continue;
        done.add(key);
        reset();
        const found = controls().find((control) => control.key === key);
        if (!found) continue;
        // The tab that is open does nothing when a person taps it. That is the rule of the host, not a fault of the app.
        if (found.node.tab && found.node.props.selected) continue;
        const { node, parent } = found, p = node.props, title = node.name ?? `the ${node.type} "${p.label ?? p.icon ?? ""}"`;
        const seen = look(app.tree()), own = look({ ...node, children: [] }), known = new Set(app.faults.map((fault) => `${fault.where}: ${fault.message}`));
        let error;
        if (p.bind) {
          const now = Number(p.value) || 0, by = p.step ?? 1;
          const sample = ["Toggle", "CheckBox"].includes(node.type) ? !p.value : ["Segmented", "Select"].includes(node.type) ? (p.options ?? []).find((option) => option !== p.value)
            : node.type === "TextField" ? (p.value === "Test" ? "Test 2" : "Test") : node.type === "DateField" ? (p.value === "2026-01-02" ? "2026-01-03" : "2026-01-02")
            : node.type === "TimeField" ? (p.value === "08:30" ? "09:30" : "08:30") : now + by > (p.max ?? Infinity) ? now - by : now + by;
          // The person uses a Choices through its option buttons, which have their own turn.
          if (sample === undefined || node.type === "Choices") continue;
          error = app.change(node, sample);
        } else error = app.fire(node);
        if (error) { faults.push("smoke run: " + error); continue; }
        // A timed change shows only when time passes. The run moves its clock to the end of each one before it looks at the screen.
        if (app.state._run) { wait(); app.tick(); }
        const after = controls(), late = [...new Set(app.faults.map((fault) => `${fault.where}: ${fault.message}`))].filter((text) => !known.has(text));
        if (late.length) { faults.push(`smoke run: after ${title}, ${late.join("; ")}`); continue; }
        const broken = Object.keys(app.state).filter((name) => !finite(app.state[name]));
        if (broken.length) { faults.push(`smoke run: after ${title}, ${broken.map((name) => "$/" + name).join(", ")} is not a finite number`); continue; }
        if (JSON.stringify(app.state) === base) {
          // A control of a list row gets a second try on the last row: the first row cannot move up.
          const rows = [];
          const find = (other) => { if (other.name === key && other.rowIndex !== undefined) rows.push(other); other.children.forEach(find); };
          reset();
          find(app.tree());
          // With one row the run cannot tell: a row cannot move, and the run does not judge it.
          const alone = node.rowIndex !== undefined && rows.length < 2;
          if (!p.bind && !alone && !(rows.length > 1 && !app.fire(rows.at(-1)) && JSON.stringify(app.state) !== base)) idle.set(key, title);
          continue;
        }
        // For the judge: what each control did to the values of the app.
        const was = JSON.parse(base);
        trace.push(`${p.bind ? "set" : "tap"} ${title}: ` + Object.keys({ ...was, ...app.state }).filter((name) => JSON.stringify(was[name]) !== JSON.stringify(app.state[name]))
          .map((name) => `${name} ${short(was[name])} -> ${short(app.state[name])}`).join(", "));
        if (look(app.tree()) === seen) faults.push(`smoke run: ${title} changes a value, and no part of the screen shows it`);
        else if (onOff(node) && look({ ...(after.find((control) => control.key === key)?.node ?? {}), children: [] }) === own) {
          // One on-off button can show its effect in a different part of the screen. A group of them cannot: the person must see which ones are on.
          const text = `${title} turns a value on and off, and looks the same in both states. Give it selected=test`;
          if ((parent?.children ?? []).filter(onOff).length > 1) faults.push("smoke run: " + text); else notes.push(text);
        }
        const later = retryIdle(after);
        // A control that brings new controls to the screen: the run goes on from that state.
        if (after.some((control) => !done.has(control.key) && !here.includes(control.key))) queue.push(later);
        // Time passes: each timer that runs goes to its end, and the run goes on from that state too.
        wait();
        app.tick();
        const ended = JSON.stringify(app.state);
        if (ended === later) continue;
        const stuck = Object.keys(app.state).filter((name) => !finite(app.state[name]));
        if (stuck.length) { faults.push(`smoke run: after the time of a timer passes, ${stuck.map((name) => "$/" + name).join(", ")} is not a finite number`); continue; }
        trace.push("time passes: " + Object.keys(app.state).filter((name) => JSON.stringify(JSON.parse(later)[name]) !== JSON.stringify(app.state[name])).map((name) => `${name} -> ${short(app.state[name])}`).join(", "));
        queue.push(ended);
      }
    }
  };
  drain();
  // A walk: the buttons that do not delete, one after the other, again and again. It reaches a screen that comes only after many taps, such as the end of a quiz.
  // It prefers a tap that brings a set of controls to the screen that the walk did not see before, such as the next step of a wizard.
  // Then a tap that gives a state that the walk did not see before, the button with the fewest taps first. So it turns the pages of a deck to the end.
  const shape = () => controls().map((control) => control.key).join(",");
  const shapes = new Set(), states = new Set(), taps = new Map();
  const walkFrom = (begin) => {
    app.state = JSON.parse(begin);
    app.recompute();
    shapes.add(shape());
    for (let step = 0; step < 60; step++) {
      // A tab button is not a step of the walk. There is one walk for each tab, and it stays in that tab.
      const usable = controls().filter((control) => !control.node.props.bind && !deletes(control.node) && !control.node.tab);
      if (usable.some((control) => !done.has(control.key))) queue.push(JSON.stringify(app.state));
      if (!usable.length) break;
      const now = JSON.stringify(app.state);
      states.add(now);
      const tried = usable.map((control) => {
        const failed = app.fire(control.node), found = { control, newShape: !failed && !shapes.has(shape()), newState: !failed && !states.has(JSON.stringify(app.state)) };
        app.state = JSON.parse(now);
        app.recompute();
        return found;
      });
      const count = (item) => taps.get(item.control.key) ?? 0;
      const pick = (tried.find((item) => item.newShape) ?? tried.filter((item) => item.newState).sort((a, b) => count(a) - count(b))[0])?.control ?? usable[step % usable.length];
      taps.set(pick.key, (taps.get(pick.key) ?? 0) + 1);
      if (app.fire(controls().find((control) => control.key === pick.key)?.node ?? pick.node)) break;
      if (JSON.stringify(app.state) !== now) idle.delete(pick.key);
      shapes.add(shape());
      // The walk prefers new states, so it does not tap a button that goes back to the start. Such a button gets its try here.
      if (idle.size) retryIdle(controls());
    }
  };
  app.state = JSON.parse(start);
  app.recompute();
  const tabStates = controls().filter((control) => control.node.tab && !control.node.props.selected).map((control) => {
    app.fire(control.node);
    const state = JSON.stringify(app.state);
    app.state = JSON.parse(start);
    app.recompute();
    return state;
  });
  [start, ...tabStates].forEach(walkFrom);
  drain();
  // A control that changed nothing in each state that the run tried. A field is not in this list: the run cannot know a value that it accepts.
  for (const title of idle.values()) faults.push(`smoke run: ${title} never changes a value: its action does nothing`);
  const never = all.filter((key) => !reached.has(key) && !/^0(\.\d+)*$/.test(key));
  if (never.length) faults.push(`smoke run: ${never.slice(0, 3).join(", ")}${never.length > 3 ? ` and ${never.length - 3} more` : ""} never comes to the screen: it is in a part that stays hidden, or it stays disabled. A control that the person can reach must show it`);
  // Many controls with the same finding become one line.
  const grouped = (lines) => {
    const byText = new Map();
    for (const line of lines) {
      const match = /^(smoke run: )?(\S+) (.*)$/.exec(line), text = match && !line.includes(": ", 11) && !line.includes(" never comes ") ? match[3] : null;
      if (!text) { byText.set(line, null); continue; }
      byText.set(text, [...(byText.get(text) ?? []), [match[1] ?? "", match[2]]]);
    }
    return [...byText].map(([text, names]) => (names ? `${names[0][0]}${names.slice(0, 3).map((name) => name[1]).join(", ")}${names.length > 3 ? ` and ${names.length - 3} more` : ""} ${text}` : text));
  };
  return { faults: grouped(faults), notes: grouped(notes), trace };
}

/** Why an expectation is false, with the values that it compared. */
function explain(app, tree) {
  const seen = (node) => JSON.stringify(app.value(node, {}));
  if (tree.k === "bin" && tree.op === "&&") return explain(app, app.value(tree.left, {}) ? tree.right : tree.left);
  const sides = tree.k === "bin" && OPS_COMPARE.has(tree.op) ? [tree.left, tree.right] : tree.k === "call" && tree.name === "near" ? tree.args : null;
  return show(tree) + (sides ? `: left is ${seen(sides[0])}, right is ${seen(sides[1])}` : `: value is ${seen(tree)}`);
}
const OPS_COMPARE = new Set(["==", "!=", "<", "<=", ">", ">="]);

/** Runs each check on a fresh copy of the app. Steps: set(name, value, row=0), tap(name, row=0), expect(expression). */
function runChecks(file) {
  return file.of("check").map((entry) => {
    const app = new App(file);
    const [title, ...steps] = entry.tree.args;
    for (const step of steps) {
      let error = "";
      try {
        const row = step.named?.row ? app.value(step.named.row, {}) : undefined;
        if (step.k !== "call") error = "a step is set(...), tap(...) or expect(...)";
        else if (["set", "tap"].includes(step.name) && step.args[0]?.k !== "ref") error = `${step.name} needs a control name, such as ${step.name}(name_field${step.name === "set" ? ", value" : ""})`;
        else if (step.name === "set") error = app.input(step.args[0].name, app.value(step.args[1], {}), row);
        else if (step.name === "tap") error = app.press(step.args[0].name, row);
        else if (step.name === "put") {
          const target = "$/" + step.args[0].segs.join("/");
          if (file.get(target)?.kind === "formula") error = `${target} is a formula: put the values that it reads`;
          else { app.write(step.args[0], app.value(step.args[1], {}), {}); app.recompute(); }
        }
        else if (step.name === "expect") { if (!app.value(step.args[0], {})) error = "expect failed: " + explain(app, step.args[0]); }
        else error = `unknown step ${step.name}`;
      } catch (failure) { error = failure.message; }
      if (error) return { name: title?.v ?? "", pass: false, detail: error };
    }
    return { name: title?.v ?? "", pass: true, detail: "" };
  });
}

/**
 * What must hold before the app can go live, apart from the faults of the lines:
 * each hole has a view from an author, each need is on the screen, and a check uses each piece of logic that a model wrote.
 * Logic inside a part belongs to the host, so it needs no check.
 */
function gaps(file, reached) {
  const out = [], paths = [], used = new Set();
  for (const check of file.of("check")) walk(check.tree, (node) => {
    if (node.k === "path" && node.abs) paths.push("$/" + node.segs.join("/"));
    if (node.k === "call" && ["tap", "set"].includes(node.name) && node.args[0]?.k === "ref") used.add(node.args[0].name);
  });
  const read = (path) => paths.some((seen) => seen === path || seen.startsWith(path + "/"));
  for (const need of file.of("need")) {
    const kind = need.tree.name, name = need.key;
    if (kind === "hole" && file.get(name + "_view")?.origin !== "author") out.push(`hole ${name} is not built: write ${name}_view and the lines that it needs`);
    if ((kind === "figure" || (kind === "progress" && settings.gate === "checks")) && !need.tree.named.example && !read("$/" + name))
      out.push(`need ${name}: no check reads $/${name}. ${kind === "figure" ? "Give the need example=[{inputs}, result], or add a check line" : "Add a check line"}`);
    for (const top of file.tops.get(name) ?? []) if (file.get(top) && !reached.has(top)) out.push(`need ${name} is not on the screen: no line under root shows ${top}`);
  }
  // What a part made for a need stays on the screen. To drop a control, change the need line. A hole is the model's own.
  const linked = new Set(["root"]);
  for (const name of linked) walk(file.get(name)?.tree, (node) => { if (node.k === "ref") linked.add(node.name); });
  for (const need of file.of("need")) {
    if (need.tree.name === "hole") continue;
    const lost = (file.made?.get(need.key) ?? []).filter((key) => !linked.has(key));
    if (lost.length) out.push(`need ${need.key}: the part made ${lost.slice(0, 3).join(", ")}${lost.length > 3 ? ` and ${lost.length - 3} more` : ""}, and the screen no longer shows it. To change a part, write the need line again with other arguments`);
  }
  // A path that no state line or formula line sets is a fault of the line that reads it.
  const roots = new Set([...file.of("state"), ...file.of("formula")].map((entry) => entry.key.split("/")[1]));
  for (const entry of file.entries.values()) {
    // The groups of a tabs or steps need name needs, also as $/NAME (memberName in parts.js). They read no value.
    const names = new Set();
    if (entry.kind === "need" && ["tabs", "steps"].includes(entry.tree.name) && entry.tree.args?.[1]) walk(entry.tree.args[1], (node) => names.add(node));
    walk(entry.tree, (node) => {
      if (node.k === "path" && node.abs && !names.has(node) && !roots.has(node.segs[0])) out.push(`${entry.key} reads $/${node.segs[0]}, and no line sets it`);
    });
  }
  for (const entry of file.entries.values()) {
    if (entry.origin !== "author" || settings.gate !== "checks") continue;
    if (entry.kind === "formula" && !read(entry.key)) out.push(`${entry.key}: no check reads this formula`);
    if (entry.kind !== "component") continue;
    if (given(entry.tree).action && !used.has(entry.key)) out.push(`${entry.key}: no check taps or sets this control`);
    walk(entry.tree, (node) => {
      if (node === entry.tree || node.k !== "call" || !CATALOG[node.name]) return;
      const props = given(node);
      if (props.action || props.bind) out.push(`${entry.key}: give the ${node.name} inside it its own named line, so that a check can use it`);
    });
  }
  return out;
}

/** Everything the host can find wrong without a model: line faults, tree faults, values that are not finite, and gaps. */
function report(file) {
  const app = new App(file);
  app.tree(true);
  const broken = Object.keys(app.state).filter((name) => !finite(app.state[name])).map((name) => `$/${name} is not a finite number at the start`);
  const faults = [
    ...[...file.faults, ...file.partFaults].map((f) => `${f.line == null ? "line" : "line " + f.line}: ${f.message}: ${f.text}`),
    ...app.faults.map((f) => `${f.where}: ${f.message}`),
    ...broken,
    ...(file.get("root") ? [] : ["no root line"]),
    ...gaps(file, app.reached),
  ];
  // The smoke run needs a screen that builds. So it runs only when nothing else is wrong.
  const run = settings.gate === "smoke" && !faults.length ? smoke(file) : { faults: [], notes: [] };
  return { faults: [...new Set([...faults, ...run.faults])], checks: runChecks(file), notes: [...(file.partNotes ?? []), ...run.notes.slice(0, 5)] };
}

return { smoke, runChecks, report };
})();
// run.js
const { PER_OBJECT, MAX_ROW_FIELDS, MAX_ROWS, MAX_ARRAY, TYPED, ALPHY, namer, header, screen, stateOf, load, handle } = (() => {
// The run-time of a compiled app. It runs inside the app's source in the sandbox (kit/build.js): for each event, the kit's
// App reads the lines of the app, takes the stored state, runs the event, and gives the next state and screen.
// The emitter (emit.js) builds the first screen and the first state with the same screen and stateOf functions.




/** Alphy's rule for a state field and an action id (ToolValueSchema.name, ToolUiCatalog.registerAction). */
const SAFE = /^[A-Za-z][A-Za-z0-9_]{0,47}$/;
const REFUSED = new Set(["__proto__", "prototype", "constructor"]);
/** An object of Alphy holds at most 32 fields, so the shown values go into d, d2, d3, and the tests into v, v2. */
const PER_OBJECT = 32;
/** A row of Alphy has at most 12 flat fields with its id, a list at most 100 rows, and an array at most 512 values (ToolValueSchema). */
const MAX_ROW_FIELDS = 12;
const MAX_ROWS = 100;
const MAX_ARRAY = 512;
/** The fields that a person types into. An add form with more than two of them is a bottom sheet. A Dialog that holds any field is one too. */
const TYPED = new Set(["TextField", "NumberField", "DateField", "TimeField"]);
/** The Alphy type of each kind of row field: a time is minutes, and a date is a "YYYY-MM-DD" text. */
const ALPHY = { time: "number", date: "string" };

/**
 * The Alphy names of the kit's names, in both directions: map.safe by key and map.kit by Alphy name. A key gets the name that
 * it wishes when Alphy takes that name and no other key has it, else f<index>. With the map of the compiled data it only looks up.
 */
function namer(map = { safe: {}, kit: {} }) {
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
function header(kit) {
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
function screen(kit, app) {
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
function stateOf(kit, app, shown) {
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
function load(kit, app, state) {
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
function handle(input, app, now = Date.now()) {
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

return { PER_OBJECT, MAX_ROW_FIELDS, MAX_ROWS, MAX_ARRAY, TYPED, ALPHY, namer, header, screen, stateOf, load, handle };
})();
// emit.js
const { runtime, emit } = (() => {
// The emitter: an expanded app file as Alphy's app definition (formatVersion 3). The source of the definition is the
// run-time (run.js and the modules that it needs) and the compiled data of the app. The run-time answers each event.




/** The text of the run-time. kit/build.js puts it into the bundle, and a test of the sources sets it from the sources. */
const runtime = { text: "" };
/**
 * The limits of Alphy that come from the kit's own mapping, so the author of a plan cannot see them: top-level state fields
 * (ToolValueSchema.validateSchemas), source bytes (SandboxContract.MAX_SOURCE_BYTES), and the nodes, depth, and children of a ui
 * (ToolUiCatalog), and the render work of a screen (ToolUiCatalog.MAX_RENDER_WORK). ToolSpec.parse stays the check of the ui bytes
 * and the state bytes. MIN_ROWS is the kit's own limit: a list with room for fewer rows is a fault.
 */
const MAX_FIELDS = 40, MAX_SOURCE_BYTES = 65536, MAX_NODES = 512, MAX_DEPTH = 8, MAX_CHILDREN = 50, MAX_RENDER_WORK = 2048, MIN_ROWS = 20;
/** The nodes whose options cost render work (ToolUiCatalog.choiceTypes). */
const CHOICES = new Set(["select", "tabs", "singleChoice", "multiChoice", "segmented", "navigationBar", "navigationRail", "menu"]);
const SCALARS = new Set(["number", "string", "boolean"]);
/** The kind of each field kind of an entries need. A choice and a text are strings. */
const FIELD_KINDS = { number: "number", money: "number", percent: "number", count: "number", check: "boolean", switch: "boolean", time: "time", date: "date" };
const own = (map, key) => (Object.hasOwn(map, key) ? map[key] : undefined);

/** The UTF-8 bytes of a text, as the host counts them. The sandbox has no TextEncoder that the kit can count on. */
const bytes = (text) => {
  let count = 0;
  for (const char of text) { const code = char.codePointAt(0); count += code < 0x80 ? 1 : code < 0x800 ? 2 : code < 0x10000 ? 3 : 4; }
  return count;
};

/**
 * The definition of the app, its language beside it, and the faults of what the emitter cannot build. app is the compiled
 * data that the source gives the run-time: the lines of the app, the field of each state value, the kind of each time, date,
 * list, and array, the fields of each list, the count of rows of each shown list, the type of each shown value, and the names
 * in both directions. Faults give no definition.
 */
function emit(file) {
  if (!runtime.text) throw new Error("emit.js has no run-time text: kit/build.js gives it");
  const kit = new App(file), { title, lang } = header(kit), faults = [], stateSchema = {}, owners = [], needObjects = new Map();
  const app = { lines: [...file.entries.values()].filter((entry) => entry.kind !== "check").map((entry) => entry.raw).join("\n"), fields: [], types: {}, names: { safe: {}, kit: {} }, kinds: {}, lists: {}, caps: {} };
  const name = namer(app.names);
  if (typeof title !== "string" || title.length < 1 || title.length > 100) faults.push("app: the app line needs a title of 1 to 100 characters");
  // The need whose part wrote each line. A line that an author wrote belongs to no need, and a fault of it names the line.
  const partOf = new Map([...(file.partLines ?? [])].flatMap(([need, keys]) => keys.map((key) => [key, need])));
  const label = (line) => (file.get(line)?.origin === "part" && partOf.has(line) ? `need ${partOf.get(line)}` : line);
  // The value that each TimeField, DateField, and Choices binds: Alphy keeps a time as minutes, a date as a real date, and choices as an array.
  const bound = new Map();
  const scan = (tree) => {
    if (!tree || typeof tree !== "object") return;
    const bind = tree.k === "call" && ["TimeField", "DateField", "Choices"].includes(tree.name) ? given(tree).bind : null;
    if (bind?.k === "path" && bind.abs && bind.segs.length === 1) bound.set(bind.segs[0], tree.name);
    Object.values(tree).forEach(scan);
  };
  file.of("component").forEach((entry) => scan(entry.tree));
  for (const [root, component] of bound) if (component !== "Choices") app.kinds[root] = component === "TimeField" ? "time" : "date";
  // The kind of each field of the rows of an entries need, from its fields= line.
  const declared = new Map(file.of("need").filter((entry) => entry.tree.name === "entries")
    .map((entry) => [entry.key, (entry.tree.named.fields?.entries ?? [["name", { k: "lit", v: "text" }]]).map(([field, kind]) => [field, own(FIELD_KINDS, kind.v) ?? "string"])]));
  /** The schema of a list value: a list of flat rows, or an array of plain values. A list that Alphy cannot hold is a fault of its owner. */
  const listed = (root, value, owner, fields) => {
    const fault = (text) => { faults.push(`${owner}: ${text}`); return null; };
    if (value.length ? value.every((item) => item === null || typeof item !== "object") : !fields) {
      const types = new Set(value.map((item) => typeof item));
      if (!value.length && bound.get(root) !== "Choices") return fault(`$/${root} starts as an empty list, so the kind of its values is not known. Give it rows or values`);
      if (types.size > 1 || types.has("object") || types.has("undefined")) return fault(`$/${root} mixes values of different kinds, and a list of Alphy holds one kind`);
      if (value.length > MAX_ARRAY) return fault(`$/${root} starts with ${value.length} values, and a list of Alphy holds at most ${MAX_ARRAY}`);
      app.kinds[root] = "array";
      return { type: "array", maxItems: MAX_ARRAY, items: [...types][0] ?? "string" };
    }
    if (!value.every((row) => row && typeof row === "object" && !Array.isArray(row))) return fault(`$/${root} mixes rows and plain values, and a list of Alphy holds one kind`);
    const kinds = new Map(fields ?? []);
    for (const row of value) for (const [field, inner] of Object.entries(row)) {
      if (field === "id" || kinds.has(field)) continue;
      if (inner !== null && typeof inner === "object") return fault(`a row of $/${root} has a list or a record in its field ${field}, and a row of Alphy holds plain values`);
      kinds.set(field, typeof inner === "number" || typeof inner === "boolean" ? typeof inner : "string");
    }
    if (kinds.size + 1 > MAX_ROW_FIELDS) return fault(`the rows of $/${root} have ${kinds.size + 1} fields with the id, and a row of Alphy has at most ${MAX_ROW_FIELDS}. Give the rows fewer fields`);
    if (value.length > MAX_ROWS) return fault(`$/${root} starts with ${value.length} rows, and a list of Alphy holds at most ${MAX_ROWS}`);
    const rowName = namer();
    rowName("$id", "id");
    app.lists[root] = [...kinds].map(([field, kind]) => [field, rowName("$" + field, field), kind]);
    app.kinds[root] = "list";
    return { type: "list", maxItems: MAX_ROWS, fields: { id: "string", ...Object.fromEntries(app.lists[root].map(([, safe, kind]) => [safe, own(ALPHY, kind) ?? kind])) } };
  };
  /**
   * A need keeps its value in one field and each other plain value of its part in one object NAME_s. A list or an array of the
   * part is a field of its own, as the list of a need is: a List and a reply bind a top-level field.
   */
  const place = (root, need, owner, schema, wish = root.slice(need?.length + 1)) => {
    const inner = typeof schema === "string" && need !== undefined && root !== need && root.startsWith(need + "_");
    const field = inner ? name("need:" + need, need + "_s") : name("$/" + root, root);
    if (!stateSchema[field]) owners.push(owner);
    if (!inner) { stateSchema[field] = schema; app.fields.push([root, field]); return; }
    const key = name("$/" + root, wish);
    (stateSchema[field] ??= { type: "object", fields: {} }).fields[key] = schema;
    needObjects.set(field, need);
    app.fields.push([root, `${field}.${key}`]);
  };
  for (const entry of file.of("state")) {
    const root = entry.key.slice(2), value = kit.state[root], need = entry.origin === "part" ? partOf.get(entry.key) : undefined;
    const owner = need === undefined ? entry.key : `need ${need}`;
    if (root.includes("/") || !(Array.isArray(value) || SCALARS.has(typeof value))) { faults.push(`${owner}: ${entry.key} is a ${root.includes("/") ? "path inside a value" : value !== null && typeof value === "object" ? "record" : "value of no known kind"}, and that is not built yet`); continue; }
    const schema = Array.isArray(value) ? listed(root, value, owner, root === need ? declared.get(need) : undefined) : app.kinds[root] === "time" ? "number" : typeof value;
    if (schema) place(root, need, owner, schema);
  }
  // The open flag of an add form that the run-time puts into a bottom sheet: a value of its need that only the run-time sets.
  for (const need of declared.keys()) {
    const form = file.get(`${need}_addRow`);
    const typed = form?.tree.name === "Row" ? (given(form.tree).children?.items ?? []).filter((item) => item.k === "ref" && TYPED.has(file.get(item.name)?.tree.name)).length : 0;
    if (typed <= 2) continue;
    const root = `${need}_addRow:open`;
    kit.state[root] = false;
    place(root, need, `need ${need}`, "boolean", "adding");
  }
  for (const [field, need] of needObjects) {
    const count = Object.keys(stateSchema[field].fields).length;
    if (count > PER_OBJECT) faults.push(`need ${need}: its part keeps ${count} values in the object ${field}, and an object of Alphy holds at most ${PER_OBJECT} fields. Give the need fewer fields`);
  }
  // The first screen comes from the start values as the run-time reads them: an empty date is today, and a text that is not a time is 00:00.
  load(kit, app, stateOf(kit, app, { views: {}, values: {} }));
  const shown = screen(kit, app);
  for (const [line, text] of shown.faults) { const fault = `${label(line)}: ${text}`; if (!faults.includes(fault)) faults.push(fault); }
  Object.assign(stateSchema, shown.viewSchemas);
  owners.push(...shown.viewOwners.map(label));
  for (const [object, values] of Object.entries(shown.values)) {
    stateSchema[object] = { type: "object", fields: Object.fromEntries(Object.entries(values).map(([key, value]) => [key, typeof value])) };
  }
  // The objects of the shown values come first, so the fault names the need or the line whose field is the 41st.
  const over = owners[MAX_FIELDS - Object.keys(shown.values).length];
  if (over) faults.push(`${over}: the app needs more than ${MAX_FIELDS} state fields from here on, and Alphy keeps at most ${MAX_FIELDS}. Use fewer needs`);
  // The theme and the column of the app are depths 0 and 1. A node that the kit adds (the text of a banner) belongs to its line.
  // The template of a row is one level below its list, and it counts once.
  let nodes = 0;
  const measure = (node, depth, owner) => {
    nodes++;
    owner = shown.owners.get(node) ?? owner;
    const where = label(owner), children = node.children ?? [];
    const deep = `${where}: this component is at depth ${depth} of the screen, and Alphy keeps at most depth ${MAX_DEPTH}. The frame of the app takes depths 0 and 1. Put it in fewer containers`;
    if (depth > MAX_DEPTH) { if (!faults.includes(deep)) faults.push(deep); return; }
    if (children.length > MAX_CHILDREN) faults.push(`${where}: this container has ${children.length} children, and Alphy keeps at most ${MAX_CHILDREN}. Put them in groups`);
    for (const child of [...children, ...(node.rowTemplate ? [node.rowTemplate] : [])]) measure(child, depth + 1, owner);
  };
  measure(shown.ui, 0, "app");
  if (nodes > MAX_NODES) faults.push(`app: the screen has ${nodes} nodes, and Alphy keeps at most ${MAX_NODES}. Show fewer components`);
  // The render work of the screen as ToolUiCatalog.validateRenderedWork counts it: each node costs 1 (a hidden node too), and a
  // choice costs its options too. A list draws its row template once for each row, and its groupBy and its reorder cost 1 more for each row.
  const work = (node) => 1 + (CHOICES.has(node.type) ? node.options.length : 0) + (node.children ?? []).reduce((sum, child) => sum + work(child), 0);
  const perRow = new Map(), shownLists = [];
  const collect = (node) => {
    const root = shown.listRoots.get(node);
    if (root !== undefined) {
      perRow.set(root, (perRow.get(root) ?? 0) + work(node.rowTemplate) + (node.groupBy === undefined ? 0 : 1) + (node.reorder === undefined ? 0 : 1));
      shownLists.push(node);
    }
    (node.children ?? []).forEach(collect);
  };
  collect(shown.ui);
  // One count for all lists of the app: the most rows for which the screen stays inside the limit when each list is full.
  const cost = [...perRow.values()].reduce((sum, each) => sum + each, 0);
  const rowCount = cost ? Math.min(MAX_ROWS, Math.floor((MAX_RENDER_WORK - work(shown.ui)) / cost)) : MAX_ROWS, storedField = new Map(app.fields);
  for (const node of shownLists) {
    const root = shown.listRoots.get(node), where = label(shown.owners.get(node)), start = kit.state[root].length;
    app.caps[root] = rowCount;
    stateSchema[storedField.get(root)].maxItems = stateSchema[node.bind].maxItems = rowCount;
    const few = `${where}: the row shows too much: with each list of the app full, the screen has room for ${rowCount} rows in each list, and a list needs room for ${MIN_ROWS}. Show fewer controls or options in a row, or fewer lists`;
    const over = `${where}: $/${root} starts with ${start} rows, and the screen has room for ${rowCount} rows in each list. Give it fewer rows`;
    for (const fault of [...(rowCount < MIN_ROWS ? [few] : []), ...(start > rowCount ? [over] : [])]) if (!faults.includes(fault)) faults.push(fault);
  }
  const language = typeof lang === "string" ? lang : null;
  if (faults.length) return { definition: null, language, faults, app };
  const source = `${runtime.text}\nreturn handle(input, ${JSON.stringify(app)});`, size = bytes(source);
  if (size > MAX_SOURCE_BYTES) return { definition: null, language, faults: [`app: the source is ${size} bytes, and Alphy keeps at most ${MAX_SOURCE_BYTES} bytes. Use shorter texts and fewer lines`], app };
  const definition = { formatVersion: 3, title, description: "", capabilities: [], stateSchema, initialState: stateOf(kit, app, shown), ui: shown.ui, source };
  return { definition, language, faults: [], app };
}


return { runtime, emit };
})();
// compile.js
const { compileAppFile } = (() => {
// The sandbox entry of the kit: an app file in, the report of the host and the app definition out. kit/build.js makes it the one global of the bundle.






/**
 * The faults hold the line faults and the report faults, or, when the report is clean, what the emitter cannot build yet.
 * A definition comes only with no fault. A throw inside the kit is the one fault "kit: <message>".
 */
async function compileAppFile(text) {
  try {
    const file = new AppFile().feedText(text);
    await expand(file);
    const { faults, notes } = report(file);
    if (faults.length) return { faults, notes, definition: null, language: null };
    const emitted = emit(file);
    return { faults: emitted.faults, notes, definition: emitted.definition, language: emitted.language };
  } catch (failure) {
    return { faults: [`kit: ${failure?.message ?? failure}`], notes: [], definition: null, language: null };
  }
}

return { compileAppFile };
})();
runtime.text = "let handle;const __handle=(f)=>{handle=f};\n(()=>{var jK={\"||\":1,\"&&\":2,\"==\":3,\"!=\":3,\"<\":4,\"<=\":4,\">\":4,\">=\":4,\"+\":5,\"-\":5,\"*\":6,\"/\":6,\"%\":6};class B extends Error{}function uK(K){let Q=[],V=0,Z=($)=>{throw new B(`${$} at column ${V+1}`)};while(V<K.length){let $=K[V];if(/\\s/.test($)){V++;continue}if($==='\"'){let z=V+1,X=\"\";while(z<K.length&&K[z]!=='\"'){if(K[z]===\"\\\\\")z++,X+={n:`\n`,t:\"\\t\"}[K[z]]??K[z];else X+=K[z];z++}if(z>=K.length)Z(\"string without a closing quote\");Q.push({t:\"str\",v:X}),V=z+1;continue}if($===\"$\"){let z=/^\\$(\\/?)([\\p{L}_][\\p{L}\\p{N}_]*(?:\\/[\\p{L}_][\\p{L}\\p{N}_]*)*)/u.exec(K.slice(V));if(!z)Z(\"path expected after $\");Q.push({t:\"path\",abs:z[1]===\"/\",segs:z[2].split(\"/\")}),V+=z[0].length;continue}let q=/^(\\d+\\.?\\d*|\\.\\d+)/.exec(K.slice(V));if(q){Q.push({t:\"num\",v:Number(q[0])}),V+=q[0].length;continue}let G=/^[\\p{L}_][\\p{L}\\p{N}_]*/u.exec(K.slice(V));if(G){Q.push({t:\"id\",v:G[0]}),V+=G[0].length;continue}let Y=K.slice(V,V+2);if([\":=\",\"==\",\"!=\",\"<=\",\">=\",\"&&\",\"||\"].includes(Y)){Q.push({t:\"op\",v:Y}),V+=2;continue}if(\"()[]{},:=+-*/%<>!?.\".includes($)){Q.push({t:\"op\",v:$}),V++;continue}Z(`unexpected character ${$}`)}return Q}function kK(K,Q=0){let V=Q,Z=(U)=>K[V]&&K[V].t===\"op\"&&K[V].v===U,$=(U)=>{if(!Z(U))throw new B(`\"${U}\" expected`);V++};function q(){let U=K[V];if(!U)throw new B(\"value expected at the end of the line\");if(V++,U.t===\"num\")return{k:\"lit\",v:U.v};if(U.t===\"str\")return{k:\"lit\",v:U.v};if(U.t===\"path\")return{k:\"path\",abs:U.abs,segs:U.segs};if(U.t===\"id\"){if(U.v===\"true\"||U.v===\"false\")return{k:\"lit\",v:U.v===\"true\"};if(U.v===\"null\")return{k:\"lit\",v:null};if(!Z(\"(\"))return{k:\"ref\",name:U.v};V++;let H=[],J={};while(!Z(\")\")){if(K[V]?.t===\"id\"&&K[V+1]?.t===\"op\"&&K[V+1].v===\"=\"){let P=K[V].v;V+=2,J[P]=z()}else H.push(z());if(!Z(\")\"))$(\",\")}return V++,{k:\"call\",name:U.v,args:H,named:J}}if(U.t===\"op\"&&U.v===\"(\"){let H=z();return $(\")\"),H}if(U.t===\"op\"&&U.v===\"[\"){let H=[];while(!Z(\"]\"))if(H.push(z()),!Z(\"]\"))$(\",\");return V++,{k:\"list\",items:H}}if(U.t===\"op\"&&U.v===\"{\"){let H=[];while(!Z(\"}\")){let J=K[V++];if(!J||J.t!==\"id\"&&J.t!==\"str\")throw new B(\"field name expected in { }\");if($(\":\"),H.push([J.v,z()]),!Z(\"}\"))$(\",\")}return V++,{k:\"map\",entries:H}}if(U.t===\"op\"&&(U.v===\"-\"||U.v===\"!\"))return{k:\"un\",op:U.v,arg:G()};throw new B(`unexpected \"${U.v}\"`)}function G(){let U=q();for(;;){let H=K[V+1];if(Z(\".\")){V++;let J=K[V++];if(!J||J.t!==\"id\")throw new B(\"field name expected after .\");U={k:\"field\",of:U,name:J.v}}else if(Z(\"[\")){V++;let J=z();$(\"]\"),U={k:\"index\",of:U,at:J}}else if(Z(\"/\")&&H?.t===\"id\"&&![\"true\",\"false\",\"null\"].includes(H.v)&&!(K[V+2]?.t===\"op\"&&K[V+2].v===\"(\"))U={k:\"field\",of:U,name:H.v},V+=2;else return U}}function Y(U){let H=G();while(K[V]?.t===\"op\"&&jK[K[V].v]>=U){let J=K[V++].v;H={k:\"bin\",op:J,left:H,right:Y(jK[J]+1)}}return H}function z(){let U=Y(1);if(!Z(\"?\"))return U;V++;let H=z();return $(\":\"),{k:\"if\",test:U,yes:H,no:z()}}return{tree:z(),used:V-Q}}function TK(K){let Q=uK(K),{tree:V,used:Z}=kK(Q);if(Z!==Q.length)throw new B(`unexpected \"${Q[Z].v??Q[Z].t}\" after the expression`);return V}function xK(K){let Q=K.trim();if(!Q||Q.startsWith(\"#\"))return null;let V=($,q,G)=>({kind:$,key:q,tree:TK(G),raw:Q}),Z;if(Z=/^delete\\s+check\\s*\\(\\s*\"(.*)\"\\s*\\)$/.exec(Q))return{kind:\"delete\",key:\"check:\"+Z[1],tree:null,raw:Q};if(Z=/^delete\\s+(?:need\\s+)?(\\S+)$/.exec(Q))return{kind:\"delete\",key:Z[1],tree:null,raw:Q};if(Z=/^need\\s+([\\p{L}_][\\p{L}\\p{N}_]*)\\s*=(?!=)(.*)$/u.exec(Q))return V(\"need\",Z[1],Z[2]);if(Z=/^(\\$\\/[\\p{L}\\p{N}_/]+)\\s*:=(.*)$/u.exec(Q))return V(\"formula\",Z[1],Z[2]);if(Z=/^(\\$\\/[\\p{L}\\p{N}_/]+)\\s*=(?!=)(.*)$/u.exec(Q))return V(\"state\",Z[1],Z[2]);if(/^app\\s*\\(/.test(Q))return V(\"app\",\"app\",Q);if(/^check\\s*\\(/.test(Q)){let $=TK(Q);return{kind:\"check\",key:\"check:\"+($.args[0]?.v??\"\"),tree:$,raw:Q}}if(Z=/^([\\p{L}_][\\p{L}\\p{N}_]*)\\s*=(?!=)(.*)$/u.exec(Q))return V(\"component\",Z[1],Z[2]);throw new B(\"line not understood\")}class VK{constructor(){this.entries=new Map,this.faults=[],this.partFaults=[],this.tops=new Map,this.removed=new Set}beginStep(){this.faults=[]}feed(K,Q=null,V=\"author\"){let Z;try{Z=xK(K)}catch(q){if(!(q instanceof B))throw q;return this.faults.push({line:Q,text:K.trim(),message:q.message}),null}if(!Z)return null;if(Z.kind===\"delete\"){let q=[...this.entries.keys()].find((G)=>G===Z.key||G===\"need:\"+Z.key);if(q)this.entries.delete(q),this.removed.add(q);else this.faults.push({line:Q,text:Z.raw,message:`nothing named ${Z.key}`});return Z}let $=Z.kind===\"need\"?\"need:\"+Z.key:Z.key;if(Z.origin=V,V===\"author\")this.removed.delete($);return this.entries.set($,Z),Z}feedText(K){return K.split(`\n`).forEach((Q,V)=>this.feed(Q,V+1)),this}open(K){return!this.removed.has(K)&&(this.entries.get(K)?.origin??\"part\")===\"part\"}of(K){return[...this.entries.values()].filter((Q)=>Q.kind===K)}get(K){return this.entries.get(K)}text(){return[...this.entries.values()].map((K)=>K.raw).join(`\n`)+`\n`}}function O(K){switch(K.k){case\"lit\":return typeof K.v===\"string\"?JSON.stringify(K.v):String(K.v);case\"path\":return\"$\"+(K.abs?\"/\":\"\")+K.segs.join(\"/\");case\"ref\":return K.name;case\"list\":return\"[\"+K.items.map(O).join(\", \")+\"]\";case\"map\":return\"{\"+K.entries.map(([Q,V])=>`${/^[\\p{L}_][\\p{L}\\p{N}_]*$/u.test(Q)?Q:JSON.stringify(Q)}: ${O(V)}`).join(\", \")+\"}\";case\"field\":return O(K.of)+\".\"+K.name;case\"index\":return`${O(K.of)}[${O(K.at)}]`;case\"un\":return K.op+O(K.arg);case\"bin\":return`(${O(K.left)} ${K.op} ${O(K.right)})`;case\"if\":return`(${O(K.test)} ? ${O(K.yes)} : ${O(K.no)})`;case\"call\":return`${K.name}(${[...K.args.map(O),...Object.entries(K.named).map(([Q,V])=>`${Q}=${O(V)}`)].join(\", \")})`}throw new B(\"cannot print \"+K.k)}var o={Column:[\"children\"],Row:[\"children\"],Card:[\"children\"],Section:[\"title\",\"children\"],Text:[\"text\"],Heading:[\"text\"],Metric:[\"label\",\"value\"],Emoji:[\"value\"],Badge:[\"value\"],Progress:[\"value\",\"max\"],Ring:[\"value\",\"max\",\"label\"],Divider:[],Notice:[\"text\"],Button:[\"label\",\"action\"],IconButton:[\"icon\",\"action\"],TextField:[\"label\",\"bind\"],NumberField:[\"label\",\"bind\"],Slider:[\"label\",\"bind\"],Toggle:[\"label\",\"bind\"],CheckBox:[\"label\",\"bind\"],Segmented:[\"label\",\"bind\",\"options\"],Select:[\"label\",\"bind\",\"options\"],List:[\"items\",\"template\",\"group\"],Chart:[\"items\",\"label\",\"value\"],Tabs:[\"titles\",\"groups\"],Dialog:[\"title\",\"children\"],DateField:[\"label\",\"bind\"],TimeField:[\"label\",\"bind\"],Picture:[\"text\",\"found\"],Choices:[\"label\",\"bind\",\"options\"],Drawing:[\"label\",\"width\",\"height\",\"shapes\"],Model3d:[\"label\",\"bodies\",\"bind\",\"exploded\",\"selected\"],Link:[\"label\",\"url\",\"text\"]};var cK=new Set([\"visible\",\"enabled\",\"variant\",\"tone\",\"emphasis\",\"weight\",\"min\",\"max\",\"step\",\"empty\",\"placeholder\",\"gap\",\"selected\",\"kind\"]),pK=new Set([\"set\",\"toggle\",\"inc\",\"dec\",\"append\",\"remove\",\"clear\",\"when\",\"move\",\"flip\",\"animate\"]),lK={circle:[\"x\",\"y\",\"r\"],rect:[\"x\",\"y\",\"w\",\"h\"],line:[\"x1\",\"y1\",\"x2\",\"y2\"],text:[\"x\",\"y\",\"text\"],polygon:[\"points\"]},iK={box:[\"part\",\"x\",\"y\",\"z\",\"w\",\"h\",\"d\"],cylinder:[\"part\",\"x\",\"y\",\"z\",\"radius\",\"length\",\"axis\"]},sK=new Set([\"text\",\"part\",\"axis\"]),dK=[\"radius\",\"width\",\"size\",\"stroke\"],aK={text:\"size\",line:\"width\",rect:\"radius\"};var i=(K,Q=0)=>{let V=10**Q;return Math.round((K+Number.EPSILON)*V)/V},s=(K)=>Array.isArray(K)?K.map(s):K&&typeof K===\"object\"?Object.fromEntries(Object.entries(K).map(([Q,V])=>[Q,s(V)])):K;function rK(K){let Q=[\"x\",\"y\",\"z\"].map((V)=>K.reduce((Z,$)=>Z+$[V],0)/(K.length||1));for(let V of K)if(V.part)[\"x\",\"y\",\"z\"].forEach((Z,$)=>{V[Z]=i(V[Z]+(V[Z]-Q[$])/2,2)})}var ZK={round:i,floor:Math.floor,ceil:Math.ceil,abs:Math.abs,sin:(K)=>i(Math.sin(K*Math.PI/180),12)+0,cos:(K)=>i(Math.cos(K*Math.PI/180),12)+0,pow:Math.pow,sqrt:Math.sqrt,exp:Math.exp,ln:Math.log,log10:Math.log10,min:(...K)=>K.flat(3).length?Math.min(...K.flat(3)):0,max:(...K)=>K.flat(3).length?Math.max(...K.flat(3)):0,clamp:(K,Q,V)=>Math.min(V,Math.max(Q,K)),len:(K)=>K==null?0:K.length,text:(K)=>String(K??\"\"),number:(K)=>Number(K)||0,fixed:(K,Q=2)=>Number(K).toFixed(Q),pad2:(K)=>String(Math.floor(K)).padStart(2,\"0\"),money:(K,Q=\"\")=>Q+Number(K).toFixed(2),percent:(K,Q=0)=>Number(K).toFixed(Q)+\"%\",join:(K,Q=\", \")=>(K||[]).join(Q),upper:(K)=>String(K).toUpperCase(),lower:(K)=>String(K).toLowerCase(),at:(K,Q)=>Array.isArray(K)?K[Q]??null:null,streak:(K)=>(K??[]).reduce(([Q,V],Z)=>Z?[Math.max(Q,V+1),V+1]:[Q,0],[0,0])[0],near:(K,Q)=>Math.abs(K-Q)<=Math.max(0.01,Math.abs(Q)/1000),trim:(K)=>String(K).trim(),contains:(K,Q)=>String(K).toLowerCase().includes(String(Q).toLowerCase()),has:(K,Q)=>Array.isArray(K)&&K.includes(Q),hours:(K,Q)=>{let[V,Z]=[K,Q].map(oK);return V===null||Z===null?0:(Z-V+1440)%1440/60},today:()=>{let K=new Date;return`${K.getFullYear()}-${String(K.getMonth()+1).padStart(2,\"0\")}-${String(K.getDate()).padStart(2,\"0\")}`},split:(K,Q=\",\")=>String(K??\"\").split(String(Q)||\",\").map((V)=>V.trim()).filter(Boolean),weekday:(K)=>{let Q=k(K);return Q===null?0:(new Date(Q*86400000).getUTCDay()+6)%7+1},month:(K)=>k(K)===null?0:Number(String(K).trim().slice(5,7)),year:(K)=>k(K)===null?0:Number(String(K).trim().slice(0,4)),days:(K,Q)=>{let[V,Z]=[k(K),k(Q)];return V===null||Z===null?0:Z-V},addDays:(K,Q)=>{let V=k(K);return V===null?null:new Date((V+Math.round(Number(Q)||0))*86400000).toISOString().slice(0,10)}};function k(K){let Q=/^(\\d{4})-(\\d{2})-(\\d{2})$/.exec(String(K??\"\").trim());if(!Q)return null;let V=Date.UTC(Number(Q[1]),Number(Q[2])-1,Number(Q[3])),Z=new Date(V);return Z.getUTCMonth()===Number(Q[2])-1&&Z.getUTCDate()===Number(Q[3])?V/86400000:null}function oK(K){let Q=/^(\\d{1,2}):(\\d{2})$/.exec(String(K??\"\").trim());return Q?Number(Q[1])*60+Number(Q[2]):null}var NK=(K,Q)=>Array.isArray(K)&&Array.isArray(Q)?K.reduce((V,Z,$)=>V||NK(Z,Q[$]),0):K<Q?-1:K>Q?1:0,$K={count:(K,Q)=>Q?K.filter(Q).length:K.length,sum:(K,Q)=>K.reduce((V,Z)=>V+Number(Q?Q(Z):Z),0),avg:(K,Q)=>K.length?K.reduce((V,Z)=>V+Number(Q?Q(Z):Z),0)/K.length:0,filter:(K,Q)=>K.filter(Q),any:(K,Q)=>K.some(Q),all:(K,Q)=>K.every(Q),map:(K,Q)=>K.map(Q),find:(K,Q)=>K.find(Q)??null,sort:(K,Q,V)=>[...K].sort((Z,$)=>NK(Q(Z),Q($))*(V===\"desc\"?-1:1)),groups:(K,Q,V)=>{if(!Q)throw new B(\"groups needs a key, such as groups($/rows, $category, $amount)\");let Z=new Map;for(let $ of K)Z.set(Q($),(Z.get(Q($))??0)+Number(V?V($):1));return[...Z].map(([$,q])=>({key:$,value:q}))}},XQ=new Set([...Object.keys(ZK),...Object.keys($K),\"if\"]);function zK(K,Q){if(K===Q)return!0;if(typeof K!==\"object\"||typeof Q!==\"object\"||!K||!Q||Array.isArray(K)!==Array.isArray(Q))return!1;let V=(Z)=>Object.keys(Z).filter(($)=>$!==\"id\");return V(K).length===V(Q).length&&V(K).every((Z)=>zK(K[Z],Q[Z]))}var qK=(K)=>({...Object.fromEntries((o[K.name]??[]).map((Q,V)=>[Q,K.args[V]])),...K.named});class HK{constructor(K){this.file=K,this.faults=[],this.nextId=1,this.state={},this.seen=new Map,this.follow()}follow(){for(let K of this.file.of(\"state\")){if(this.seen.get(K.key)===K.raw)continue;this.seen.set(K.key,K.raw),this.write(this.pathOf(K.key),this.attempt(K,()=>s(this.value(K.tree,{}))),{})}this.tagRows(this.state),this.recompute()}fault(K,Q){this.faults.push({where:K,message:Q})}attempt(K,Q){try{return Q()}catch(V){return this.fault(K.key,V.message),null}}pathOf(K){return{k:\"path\",abs:!0,segs:K.slice(2).split(\"/\")}}tagRows(K){if(Array.isArray(K))K.forEach((Q)=>{if(Q&&typeof Q===\"object\"&&!Array.isArray(Q))Q.id??=\"r\"+this.nextId++,this.tagRows(Q)});else if(K&&typeof K===\"object\")Object.values(K).forEach((Q)=>this.tagRows(Q))}read(K,Q){let V=K.abs?this.state:Q.it;for(let Z of K.segs)V=V==null?void 0:V[Z];return V}place(K,Q){if(K.k===\"path\"){let Z=K.abs?this.state:Q.it;if(Z==null)throw new B(\"a $field path needs a row\");if(typeof Z!==\"object\")throw new B(\"the row is a plain value with no fields to set\");for(let $ of K.segs.slice(0,-1))Z=Z[$]??={};return[Z,K.segs.at(-1)]}if(K.k!==\"field\"&&K.k!==\"index\")throw new B(\"a place to set is a path, a field of a row, or an item of a list\");let V=this.value(K.of,Q);if(V==null||typeof V!==\"object\")throw new B(`${O(K.of)} has no value, so nothing in it can be set`);return[V,K.k===\"field\"?K.name:this.value(K.at,Q)]}write(K,Q,V){let[Z,$]=this.place(K,V);Z[$]=Q}value(K,Q){switch(K.k){case\"lit\":return K.v;case\"path\":if(!K.abs&&K.segs[0]===\"it\")return K.segs.slice(1).reduce((V,Z)=>V?.[Z],Q.it);if(!K.abs&&Q.each!==void 0&&K.segs.join()===\"index\"&&!(Q.it&&typeof Q.it===\"object\"&&(\"index\"in Q.it)))return Q.each;if(!K.abs&&Q.it===void 0)throw new B(`$${K.segs.join(\"/\")} is a field of a list row, and there is no row here. A value of the app is $/${K.segs.join(\"/\")}`);if(!K.abs&&Q.it!==void 0&&(Q.it===null||typeof Q.it!==\"object\"))throw new B(\"the row is a plain value with no fields. $it reads the row itself\");if(!K.abs&&Q.it&&!(K.segs[0]in Q.it)){let V=K.segs[0];if(this.shown??=Object.fromEntries(this.file.of(\"need\").filter((Z)=>Z.tree.name===\"entries\"&&Z.tree.named?.show?.k===\"map\").flatMap((Z)=>Z.tree.named.show.entries)),K.segs.length===1&&this.shown[V]&&!Q.busy?.includes(V))return this.value(this.shown[V],{...Q,busy:[...Q.busy??[],V]});throw new B(`the row has no field ${V}`)}return this.read(K,Q);case\"list\":return K.items.map((V)=>this.value(V,Q));case\"map\":return Object.fromEntries(K.entries.map(([V,Z])=>[V,this.value(Z,Q)]));case\"field\":return this.value(K.of,Q)?.[K.name];case\"index\":{let V=this.value(K.of,Q);return Array.isArray(V)?V[this.value(K.at,Q)]??null:null}case\"un\":return K.op===\"-\"?-this.value(K.arg,Q):!this.value(K.arg,Q);case\"if\":return this.value(K.test,Q)?this.value(K.yes,Q):this.value(K.no,Q);case\"bin\":{if(K.op===\"&&\")return this.value(K.left,Q)&&this.value(K.right,Q);if(K.op===\"||\")return this.value(K.left,Q)||this.value(K.right,Q);let V=this.value(K.left,Q),Z=this.value(K.right,Q);switch(K.op){case\"+\":return Array.isArray(V)&&Array.isArray(Z)?[...V,...Z]:typeof V===\"string\"||typeof Z===\"string\"?String(V??\"\")+String(Z??\"\"):V+Z;case\"-\":return V-Z;case\"*\":return V*Z;case\"/\":return V/Z;case\"%\":return V%Z;case\"==\":return zK(V,Z);case\"!=\":return!zK(V,Z);case\"<\":return V<Z;case\"<=\":return V<=Z;case\">\":return V>Z;case\">=\":return V>=Z}break}case\"call\":{if(K.name===\"if\")return this.value(K.args[0],Q)?this.value(K.args[1],Q):this.value(K.args[2],Q);if($K[K.name]){let V=this.value(K.args[0],Q);if(!Array.isArray(V))throw new B(`${K.name} needs a list`);let Z=($)=>$&&((q)=>this.value($,{...Q,it:q}));return $K[K.name](V,Z(K.args[1]),K.name===\"groups\"?Z(K.args[2]):K.args[2]&&this.value(K.args[2],Q))}if(ZK[K.name])return ZK[K.name](...K.args.map((V)=>this.value(V,Q)));if(o[K.name])throw new B(`${K.name} is a component. A component line has a plain name with no $/ before it`);throw new B(`unknown function ${K.name}`)}case\"ref\":throw new B(`${K.name} is a component name, not a value`)}throw new B(\"expression not understood\")}drawn(K,Q,V,Z){if(V.k===\"list\")return V.items.flatMap((J)=>this.drawn(K,Q,J,Z));let{name:$=\"\",args:q=[],named:G={}}=V.k===\"call\"?V:{};if($===\"each\"&&q.length===2){let J=this.value(q[0],Z),P=Array.isArray(J)?J:Number.isInteger(J)&&J>=0&&J<=500?Array.from({length:J},(_,W)=>W):null;if(!P)throw new B(\"each needs a list or a count: each($/rows, shape) or each(5, shape)\");return P.flatMap((_,W)=>this.drawn(K,Q,q[1],{...Z,it:_,each:W}))}let Y=Object.hasOwn(K,$)?K[$]:null;if(!Y)throw new B(`${O(V).slice(0,30)} is not one of ${Object.keys(K).join(\", \")}, each`);let z=aK[$],X=`${$} takes ${Y.join(\", \")}, color${z?`, ${z}`:\"\"}`;if(q.length<Y.length||q.length>Y.length+(z?2:1))throw new B(`${O(V).slice(0,60)} has ${q.length} values: ${X}`);if(G.visible&&!this.value(G.visible,Z))return[];let U=(J,P)=>{if(J===null||J===\"\"||typeof J===\"boolean\"||!Number.isFinite(Number(J)))throw new B(`${P} of ${$} is not a number: ${X}`);return i(Number(J),2)},H={[Q]:$};if(Y.forEach((J,P)=>{let _=this.value(q[P],Z);H[J]=sK.has(J)?String(_??\"\"):J===\"points\"?[_].flat().map((W)=>U(W,J)):U(_,J)}),H.axis!==void 0&&![\"x\",\"y\",\"z\"].includes(H.axis))throw new B(`axis of ${$} is \"x\", \"y\", or \"z\"`);if(H.color=String((q[Y.length]?this.value(q[Y.length],Z):null)??\"ink\"),q.length===Y.length+2)H[z]=U(this.value(q[Y.length+1],Z),z);for(let[J,P]of Object.entries(G)){if(J===\"visible\")continue;if(!dK.includes(J))throw new B(`${$} has no argument ${J}`);let _=this.value(P,Z);if(J!==\"stroke\")H[J]=U(_,J);else if(_)H.stroke=!0}return[H]}recompute(){let K=this.file.of(\"formula\");for(let Q=0;Q<5;Q++){let V=JSON.stringify(this.state);for(let Z of K)this.attempt(Z,()=>this.write(this.pathOf(Z.key),this.value(Z.tree,{}),{}));if(JSON.stringify(this.state)===V)return}}act(K,Q={}){for(let V of K.k===\"list\"?K.items:[K]){if(V.k!==\"call\"||!pK.has(V.name))throw new B(`unknown action ${V.name??V.k}`);let[Z,$]=V.args;if(V!==(K.k===\"list\"?K.items[0]:K))this.recompute();let q=()=>{if(![\"path\",\"field\",\"index\"].includes(Z?.k))throw new B(`${V.name} needs a place: a path, a field of a row, or an item of a list`);return Z},G=(Y)=>{let z=this.value(Y,Q);if(!Array.isArray(z))throw new B(`${V.name} needs a list`);return z};switch(V.name){case\"set\":this.write(q(),s(this.value($,Q)),Q);break;case\"toggle\":this.write(q(),!this.value(q(),Q),Q);break;case\"inc\":this.write(q(),(this.value(q(),Q)||0)+($?this.value($,Q):1),Q);break;case\"dec\":this.write(q(),(this.value(q(),Q)||0)-($?this.value($,Q):1),Q);break;case\"clear\":this.write(q(),[],Q);break;case\"flip\":{let Y=G(q()),z=this.value($,Q);this.write(q(),Y.includes(z)?Y.filter((X)=>X!==z):[...Y,z],Q);break}case\"move\":{let Y=V.args.length===1;if(Y&&!Q.list)throw new B(\"move(by) needs a row. Outside a list write move($/list, test, by)\");let z=Y?this.read(Q.list,{}):G(q()),X=Y?z.indexOf(Q.it):z.findIndex((H)=>this.value($,{...Q,it:H})),U=X+this.value(Y?Z:V.args[2],Q);if(X>=0&&U>=0&&U<z.length)z.splice(U,0,z.splice(X,1)[0]);break}case\"when\":if(this.value(Z,Q))this.act($,Q);else this.recompute();break;case\"animate\":{if(Z?.k!==\"path\"||!Z.abs)throw new B(\"animate needs a value of the app as its place, such as $/angle\");let Y=Z.segs.join(\"/\"),z=Number(this.value(Z,Q))||0,X=$?Number(this.value($,Q)):NaN,U=V.args[2]?Number(this.value(V.args[2],Q)):0;if(!Number.isFinite(X)||!Number.isFinite(U))throw new B(\"animate needs a number to go to and a time in milliseconds: animate($/angle, 360, 5000)\");let H=(this.state._run??[]).filter((J)=>J.path!==Y);if(U>0&&X!==z)H.push({path:Y,from:z,to:X,ms:U,spent:0,at:z});else this.write(Z,X,Q);if(H.length)this.state._run=H;else delete this.state._run;break}case\"append\":{let Y=s(this.value($,Q));G(q()).push(Y),this.tagRows([Y]);break}case\"remove\":{if(!Z){if(!Q.list)throw new B(\"remove() without a path needs a row\");this.write(Q.list,this.read(Q.list,{}).filter((Y)=>Y!==Q.it),{})}else this.write(q(),G(q()).filter((Y)=>!this.value($,{...Q,it:Y})),Q);break}}}this.recompute()}tree(K=!1){this.reached=new Set;let Q=0,V=($,q,G,Y)=>{if(Y>40)throw new B(\"components nest too deep or refer to each other\");if($.k===\"ref\"){let H=this.file.get($.name);if(this.reached.add($.name),!H||H.kind!==\"component\"){if(K)this.fault($.name,\"no component line with this name\");return{type:\"Missing\",name:$.name,props:{},children:[]}}return V(H.tree,q,$.name,Y+1)}if($.k!==\"call\"||!o[$.name])return this.fault(G??\"?\",$.k===\"path\"?`a child is a component name, not the path ${O($)}`:`unknown component ${$.name??$.k}`),{type:\"Missing\",name:G,props:{},children:[]};let z={type:$.name,name:G,props:{},children:[],row:q.it,rowIndex:q.index,list:q.list,call:$},X=qK($),U=[];for(let[H,J]of Object.entries(X)){if(J===void 0)continue;if(!o[$.name].includes(H)&&!cK.has(H)&&!(H===\"action\"&&X.bind)){this.fault(G??$.name,`${$.name} has no property ${H}`);continue}if($.name===\"Chart\"&&(H===\"label\"||H===\"value\")||$.name===\"List\"&&H===\"group\"||H===\"shapes\"||H===\"bodies\")continue;try{if(H===\"children\")z.children=(J.k===\"list\"?J.items:[J]).map((P)=>V(P,q,void 0,Y+1));else if(H===\"groups\")U=J.k===\"list\"?J.items:[J];else if(H===\"action\")z.props.action=J;else if(H===\"bind\"){if(![\"path\",\"field\",\"index\"].includes(J.k))throw new B(\"bind needs a place: a path, a field of a row, or an item of a list\");z.props.bind=J,z.props.value=J.k===\"path\"?this.read(J,q):this.value(J,q)}else if(H===\"template\")z.template=J;else z.props[H]=this.value(J,q)}catch(P){this.fault(G??$.name,`${H}: ${P.message}`)}}if($.name===\"Chart\"){try{z.props.points=(Array.isArray(z.props.items)?z.props.items:[]).map((H)=>({label:X.label?this.value(X.label,{...q,it:H}):\"\",value:Number(X.value?this.value(X.value,{...q,it:H}):H)||0}))}catch(H){this.fault(G??$.name,`points: ${H.message}`)}delete z.props.items}if($.name===\"Drawing\"||$.name===\"Model3d\"){let H=$.name===\"Drawing\"?\"shapes\":\"bodies\";try{z.props[H]=this.drawn(H===\"shapes\"?lK:iK,H===\"shapes\"?\"shape\":\"body\",X[H]??{k:\"list\",items:[]},q)}catch(J){z.props[H]=[],this.fault(G??$.name,`${H}: ${J.message}`)}if(H===\"bodies\"&&z.props.exploded)rK(z.props.bodies);if(H===\"shapes\"&&!(z.props.width>0&&z.props.height>0))this.fault(G??$.name,\"a drawing needs a width and a height above 0: drawing(label, 320, 240, [shapes])\")}if($.name===\"Link\"&&!/^https?:\\/\\/\\S+$/.test(String(z.props.url??\"\")))this.fault(G??$.name,\"a Link needs a web address that starts with https://\");if($.name===\"Dialog\"&&!X.visible)this.fault(G??$.name,\"a Dialog needs visible=test and a button that makes the test false. Without them the window never closes\");if($.name===\"Choices\"){let H=Array.isArray(z.props.value)?z.props.value:[],J=z.props.max!==void 0&&H.length>=z.props.max;z.children=(z.props.options??[]).map((P,_)=>({type:\"Button\",name:G&&`${G}_opt${_}`,children:[],props:{label:String(P),selected:H.includes(P),enabled:H.includes(P)||!J,action:{k:\"call\",name:\"flip\",args:[X.bind,{k:\"lit\",v:P}],named:{}}}}))}if($.name===\"Tabs\"){let H={k:\"path\",abs:!0,segs:[\"_ui\",G??\"tabs\"+Q++]},J=this.read(H,{})??0;z.props.value=J,z.children=[{type:\"Row\",props:{},children:(z.props.titles??[]).map((P,_)=>({type:\"Button\",name:G&&`${G}_tab${_}`,tab:!0,children:[],props:{label:P,selected:_===J,action:{k:\"call\",name:\"set\",args:[H,{k:\"lit\",v:_}],named:{}}}}))},...U.map((P,_)=>({type:\"Column\",props:{visible:_===J},children:(P.k===\"list\"?P.items:[P]).map((W)=>V(W,q,void 0,Y+1))}))]}if($.name===\"List\"&&z.template&&Array.isArray(z.props.items)){let H=(W,b=0)=>{if(!W||b>8)return null;if(W.k===\"call\"&&[\"sort\",\"filter\"].includes(W.name))return H(W.args[0],b+1);if(W.k===\"if\")return H(W.yes,b+1)??H(W.no,b+1);if(W.k!==\"path\")return null;let f=W.abs?this.file.get(\"$/\"+W.segs.join(\"/\")):null;return f?.kind===\"formula\"?H(f.tree,b+1):W},J=H(X.items),P=z.props.items,_=[];if(X.group)try{let W=P.map((f)=>this.value(X.group,{...q,it:f})),b=[...new Set(W)].flatMap((f)=>P.map((y,n)=>[y,f,n]).filter((y)=>W[y[2]]===f));P=b.map((f)=>f[0]),_=b.map((f,y)=>y===0||b[y-1][1]!==f[1]?[{type:\"Heading\",props:{text:String(f[1]??\"\")},children:[]}]:[])}catch(W){this.fault(G??$.name,`group: ${W.message}`)}z.children=P.flatMap((W,b)=>[..._[b]??[],V(z.template,{it:W,index:b,list:J},void 0,Y+1)]),z.source=J,z.blank=(W)=>V(z.template,{it:W,index:0,list:J},void 0,Y+1)}return z},Z=this.file.get(\"root\");if(!Z)return{type:\"Missing\",name:\"root\",props:{},children:[]};return V(Z.tree,{},\"root\",0)}find(K,Q){let V=(Z)=>Z.name===K&&(Q===void 0||Z.rowIndex===Q)?Z:Z.children.reduce(($,q)=>$??V(q),null);return V(this.tree())}tick(){let K=d.now(),Q=(K-(this.clock??K))/1000,V=this.state;if(this.clock=K,Q>0&&V._run){if(V._run=V._run.filter((Z)=>{let $=this.pathOf(\"$/\"+Z.path);if(this.read($,{})!==Z.at)return!1;return Z.spent+=Q*1000,Z.at=Z.spent>=Z.ms?Z.to:Z.from+(Z.to-Z.from)*Z.spent/Z.ms,this.write($,Z.at,{}),Z.spent<Z.ms}),!V._run.length)delete V._run;this.recompute()}for(let Z of Q>0?this.file.of(\"need\"):[]){if(Z.tree.name!==\"timer\")continue;let $=Z.key,q=(Y,z)=>{try{return Number(Y?this.value(Y,{}):z)||0}catch{return 0}},G=Q;for(let Y=0;V[$+\"_on\"]&&G>0&&Y<1000;Y++){let z=Number(V[$+\"_phase\"])||0,X=z-V[$+\"_spent\"];if(G<X){V[$+\"_spent\"]+=G;break}if(G-=Math.max(0,X),!V[$+\"_resting\"])V[$]+=1;if(!V[$+\"_resting\"]&&q(Z.tree.named.rest,0)>0)V[$+\"_resting\"]=!0,V[$+\"_spent\"]=0;else if(V[$+\"_round\"]>=q(Z.tree.named.rounds,1))V[$+\"_over\"]=!0,V[$+\"_on\"]=!1,V[$+\"_spent\"]=z;else V[$+\"_round\"]+=1,V[$+\"_resting\"]=!1,V[$+\"_spent\"]=0;this.recompute()}this.recompute()}}change(K,Q){this.tick();try{if(this.write(K.props.bind,Q,{it:K.row}),K.props.action)this.act(K.props.action,{it:K.row,list:K.list});else this.recompute()}catch(V){return`${K.name??K.type}: ${V.message}`}return\"\"}fire(K){if(K.props.enabled===!1||K.props.visible===!1)return`${K.name??K.type} is not active`;this.tick();try{this.act(K.props.action,{it:K.row,list:K.list})}catch(Q){return`${K.name??K.type}: ${Q.message}`}return\"\"}input(K,Q,V){let Z=this.find(K,V);if(Z?.props.bind)return this.change(Z,Q);return(!Z?`no field named ${K}`:Z.props.action?`${K} is not a field: use tap(${K})`:`${K} only shows a value and cannot be set. A check sets a field, such as a NumberField`)+(V===void 0?\"\":` in row ${V}`)}press(K,Q){let V=this.find(K,Q);if(V?.props.action)return this.fire(V);return(V?.props.bind?`${K} is a field with no action: use set(${K}, value)`:`no control named ${K}`)+(Q===void 0?\"\":` in row ${Q}`)}}var d={gate:\"smoke\",now:()=>Date.now()};var nK=/^[A-Za-z][A-Za-z0-9_]{0,47}$/,tK=new Set([\"__proto__\",\"prototype\",\"constructor\"]),RK=32,SK=12,EK=100,eK=512;var KQ={time:\"number\",date:\"string\"};function IK(K={safe:{},kit:{}}){let Q=0;return(V,Z=V)=>{if(Object.hasOwn(K.safe,V))return K.safe[V];let $=Z;if(!nK.test($)||tK.has($)||/^[dv]\\d*$/.test($)||Object.hasOwn(K.kit,$)){while(Object.hasOwn(K.kit,\"f\"+Q))Q++;$=\"f\"+Q}return K.safe[V]=$,K.kit[$]=V,$}}function QQ(K){let Q=K.file.get(\"app\")?.tree,V=(Z)=>Z?K.value(Z,{}):void 0;return{title:V(Q?.args[0]),...Object.fromEntries(Object.entries(Q?.named??{}).map(([Z,$])=>[Z,V($)]))}}var AK=(K)=>String(K).padStart(2,\"0\"),VQ=(K)=>{let Q=/^(\\d{1,2}):(\\d{2})$/.exec(String(K??\"\").trim());return Q&&Number(Q[1])<24&&Number(Q[2])<60?Number(Q[1])*60+Number(Q[2]):0},YK=(K)=>{let Q=Math.min(1439,Math.max(0,Math.round(Number(K)||0)));return`${AK(Math.floor(Q/60))}:${AK(Q%60)}`},MK=(K,Q,V)=>K===\"time\"?VQ(Q):K===\"date\"?k(Q)===null?V:String(Q).trim():K===\"number\"?typeof Q===\"number\"&&Number.isFinite(Q)?Q:0:K===\"boolean\"?Q===!0:Q===null||Q===void 0||typeof Q===\"object\"?\"\":String(Q),bK=(K,Q,V)=>({id:String(K?.id??\"\"),...Object.fromEntries(Q.map(([Z,$,q])=>[$,MK(q,K?.[Z],V)]))}),gK=(K)=>K.value({k:\"call\",name:\"today\",args:[]},{}),FK={eyebrow:{emphasis:\"label\",tone:\"accent\"},muted:{tone:\"muted\"}},ZQ={ArrowUp:\"Arrow\",ArrowDown:\"Sort\",ArrowLeft:\"Back\",ArrowRight:\"Chevron\",Edit:\"Pencil\"},$Q=new Set([\"List\",\"Dialog\"]),a=(K,Q)=>Object.hasOwn(K,Q)?K[Q]:void 0,zQ=(K,Q)=>Object.fromEntries(Q.filter((V)=>K[V]!==void 0).map((V)=>[V,K[V]])),OK=(K,Q)=>{let V=Array.isArray(K.props.options)?K.props.options:[];return{type:V.length<=6?\"segmented\":\"select\",...Q.field(),options:V.map((Z)=>({text:Q.plain(Z),value:Z}))}},DK=(K,Q,V)=>(Z,$)=>{let{min:q=Q,max:G=V,step:Y,value:z}=Z.props;if(![q,G].every(Number.isFinite)||!(q<G)||!$.blank&&!(Number.isFinite(z)&&z>=q&&z<=G)||Y!==void 0&&!(Y>0&&Y<=G-q))$.fault(`a ${Z.type} needs min below max, its value from min to max, and a step above 0 and at most max - min. Now min is ${q}, max is ${G}, the value is ${z}${Y===void 0?\"\":`, and the step is ${Y}`}`);return $.bounds(q,G),{type:K,...$.field(),min:q,max:G,...zQ(Z.props,[\"step\"])}},qQ={Column:(K,Q)=>({type:\"column\",children:Q.children(!0)}),Row:(K,Q)=>Q.form()??{type:\"row\",...K.children.some((V)=>V.props.weight!==void 0)&&{wrap:!1},children:Q.children(!0)},Card:(K,Q)=>({type:\"card\",...K.props.variant===\"hero\"&&{variant:\"hero\"},children:Q.children()}),Section:(K,Q)=>({type:\"section\",text:Q.plain(K.props.title),children:Q.children()}),Text:(K,Q)=>({type:\"text\",...Q.text(\"text\"),...a(FK,K.props.variant)&&{style:FK[K.props.variant]}}),Heading:(K,Q)=>({type:\"text\",...Q.text(\"text\"),style:{emphasis:\"title\"}}),Metric:(K,Q)=>({type:\"metric\",text:Q.plain(K.props.label),bind:Q.shown(\"value\"),...K.props.emphasis===\"display\"&&{style:{emphasis:\"display\"}}}),Badge:(K,Q)=>({type:\"badge\",...Q.text(\"value\")}),Emoji:(K,Q)=>({type:\"emoji\",text:Q.plain(K.props.value),...Q.value(\"value\")}),Divider:()=>({type:\"divider\"}),Notice:(K,Q)=>({type:\"banner\",children:[{type:\"text\",...Q.text(\"text\")}]}),Button:(K,Q)=>({type:\"button\",text:Q.caption(K.props.label),action:Q.action(),variant:K.props.selected===void 0||K.props.selected?\"filled\":\"tonal\"}),IconButton:(K,Q)=>({type:\"icon\",name:a(ZQ,K.props.icon)??K.props.icon,text:Q.caption(),action:Q.action()}),NumberField:DK(\"numberInput\",0,1e6),Slider:DK(\"slider\",0,100),TextField:(K,Q)=>({type:\"input\",...Q.field(),inputMode:\"apply\"}),Toggle:(K,Q)=>({type:\"toggle\",...Q.field()}),CheckBox:(K,Q)=>({type:\"checkbox\",...Q.field()}),Segmented:OK,Select:OK,DateField:(K,Q)=>({type:\"dateInput\",...Q.field()}),TimeField:(K,Q)=>({type:\"timeInput\",...Q.field(YK)}),Choices:(K,Q)=>({type:\"multiChoice\",...Q.field((V)=>Array.isArray(V)&&!(K.props.max!==void 0&&V.length>K.props.max)?V:void 0),options:(Array.isArray(K.props.options)?K.props.options:[]).map((V)=>({text:Q.plain(V),value:V}))}),Dialog:(K,Q)=>Q.modal(),List:(K,Q)=>Q.list()},JK=(K,Q)=>K===\"number\"?Number.isFinite(Q)?Q:0:Q!==null&&typeof Q===\"object\"?\"\":String(Q??\"\"),HQ=(K)=>K?.k===\"path\"&&K.abs?[K,!1]:K?.k===\"un\"&&K.op===\"!\"&&K.arg.k===\"path\"&&K.arg.abs?[K.arg,!0]:K?.k===\"bin\"&&K.op===\"!=\"&&K.left.k===\"path\"&&K.left.abs&&K.right.k===\"lit\"?[K.left,K.right.v]:null,UK=(K)=>K?.k===\"path\"&&!K.abs&&K.segs.length===1?K.segs[0]:null,hK=(K)=>K!==null&&typeof K===\"object\"&&(K.k===\"path\"&&!K.abs||Object.values(K).some(hK));function GK(K,Q){let V=IK(Q.names),Z=new Map(Q.fields),$=Q.kinds??{},q=Q.lists??{},G=gK(K),Y=[],z=new Map,X=new Map,U=new Map,H=new Map,J=[],P={},_={},W=[],b={d:new Map,v:new Map},f={},y=(F,S,D,T,I)=>{if(T!==null&&typeof T===\"object\")I.fault(`it shows a ${Array.isArray(T)?\"list\":\"record\"}, and Alphy shows one text or number there. Show one field of it, or join(list)`);let w=I.scope;if(w){let h=w.stored.get(UK(D));if(h&&h[1]!==\"time\"&&(F===\"d\"||h[1]===\"boolean\"))return h[0];let v=w.name(`${F}:${S}`,S.replace(/\\W/g,\"_\")),j=F===\"v\"?\"boolean\":Q.types[`${w.key}/${v}`]??=typeof T===\"number\"?\"number\":\"string\";if(w.computed.set(v,j),w.out)w.out[v]=F===\"v\"?Boolean(T):JK(j,T);return v}let A=D?.k===\"path\"&&D.abs?D.segs:null;if(A?.length===1&&Z.has(A[0])&&$[A[0]]!==\"time\"&&(F===\"d\"||typeof T===\"boolean\"))return Z.get(A[0]);let C=V(`${F}:${S}`,A?A.join(\"_\"):S.replace(/\\W/g,\"_\"));if(!b[F].has(C)){let h=F+(b[F].size<RK?\"\":Math.floor(b[F].size/RK)+1);b[F].set(C,`${h}.${C}`),(f[h]??={})[C]=F===\"v\"?Boolean(T):JK(Q.types[C]??=typeof T===\"number\"?\"number\":\"string\",T)}return b[F].get(C)},n=(F,S,D)=>{if(!F)return z.set(S,D);if(F.id===null)return;let T=X.get(S);if(!T)X.set(S,T=new Map),z.set(S,(I)=>T.get(I?.key)?.(I.value)??!1);T.set(F.id,D)},r=(F,S,D,T)=>{D=F.name??D;let I=T.scope,w=a(qQ,F.type),A=(M)=>Y.push([D,M]);if(!w)return A(`${F.type} is not built yet`),null;if(I&&$Q.has(F.type))return A(`a ${F.type} in a row of a list is not built yet`),null;let C=F.call?qK(F.call):{};if(I&&C.action?.k===\"call\"&&C.action.name===\"move\"&&C.action.args.length===1)return I.flags.moves=!0,null;if(I){for(let M of[\"label\",\"title\",\"icon\",\"options\"])if(hK(C[M]))A(`the ${M} of a ${F.type} in a row ${M===\"options\"?\"change\":\"changes\"} with the row, and Alphy draws one ${M} for all rows. Show the value in a Text of the row`)}let h=F.type===\"Section\"?{...T,title:F.props.title}:T,v=(M)=>C[M]&&C[M].k!==\"lit\"?y(\"d\",`${S}.${M}`,C[M],F.props[M],j):null,j={scope:I,fault:A,blank:Boolean(I&&!I.out),children:(M=!1,N=h)=>F.children.map((R,g)=>r(R,R.name??`${S}_${g}`,D,{...N,weighted:M})).filter(Boolean),caption:(M)=>j.plain(M)||(F.name?F.name:`${D} ${F.props.icon??F.type}`).replace(/_/g,\" \"),text:(M)=>{let N=v(M);return N?{text:\"\",bind:N}:{text:j.plain(F.props[M])}},plain:(M)=>{if(M===null||typeof M!==\"object\")return String(M??\"\");return A(`it shows a ${Array.isArray(M)?\"list\":\"record\"} as a text, and Alphy shows one text there. Show one field of it, or join(list)`),\"\"},value:(M)=>{let N=v(M);return N?{bind:N}:{[M]:F.props[M]}},shown:(M)=>v(M)??y(\"d\",`${S}.${M}`,null,F.props[M],j),bounds:(M,N)=>{if(!j.blank)J.push([F,M,N])},field:(M=(N)=>N)=>{let N;if(I){let R=I.stored.get(UK(C.bind));if(!R||R[1]===\"time\"!==(F.type===\"TimeField\")||F.type===\"DateField\"&&R[1]!==\"date\")A(`a ${F.type} in a row binds a field of the row of its own kind, such as $name. ${C.bind?O(C.bind):\"nothing\"} is not built yet`);N=R?.[0]}else{let R=C.bind?.k===\"path\"&&C.bind.abs&&C.bind.segs.length===1?C.bind.segs[0]:null;if(!Z.has(R)||$[R]===\"time\"!==(F.type===\"TimeField\"))A(`a binding to ${C.bind?O(C.bind):\"nothing\"} is not built yet: a field binds a value of a state line`);N=Z.get(R)}return{text:j.caption(F.props.label),bind:N,action:j.action((R)=>{let g=M(R);return g===void 0?!1:K.change(F,g)})}},action:(M=()=>{let R=K.fire(F);if(R===\"\"&&T.sheet&&F.type===\"Button\")K.state[T.sheet]=!1;return R},N=\"\")=>{let R=V(\"@\"+S+N,S+N);return n(I,R,M),R},form:()=>{let M=`${S}:open`;if(!Z.has(M))return null;let N=j.caption(F.children.find((g)=>g.type===\"Button\")?.props.label),R=(g)=>()=>{return K.state[M]=g,\"\"};return{type:\"column\",children:[{type:\"button\",text:N,action:j.action(R(!0),\"_open\"),variant:\"filled\",icon:\"Plus\"},{type:\"bottomSheet\",text:N,visibleBind:Z.get(M),dismissAction:j.action(R(!1),\"_dismiss\"),children:j.children(!1,{...T,sheet:M})}]}},modal:()=>{if(!C.visible||C.visible.k===\"lit\")A(\"a Dialog shows while a test of the app is true, so it needs visible=test, such as visible=$/open\");let M=HQ(C.visible),N=(g)=>g.props.bind!==void 0||g.children.some(N),R=j.action(()=>{if(!M)return!1;return K.write(M[0],M[1],{}),K.recompute(),\"\"},\"_dismiss\");return{type:F.children.some(N)?\"bottomSheet\":\"dialog\",text:j.caption(F.props.title),dismissAction:R,children:j.children()}},list:()=>vK(F,S,D,C,j,T.title)},u=w(F,j);if(!u)return null;U.set(u,D);for(let M of[\"visible\",\"enabled\"]){if(!C[M])continue;if(C[M].k!==\"lit\")u[M+\"Bind\"]=y(\"v\",`${S}.${M}`,C[M],F.props[M],j);else if(!F.props[M])u[M]=!1}if(F.props.weight!==void 0&&T.weighted)u.layout={weight:F.props.weight};return u},vK=(F,S,D,T,I,w)=>{let A=F.source,C=A?.k===\"path\"&&A.abs&&A.segs.length===1&&q[A.segs[0]]?A.segs[0]:null;if(!C||!F.blank)return I.fault(\"a List shows the rows of a list of the app, such as $/rows, or sort or filter of it. Other rows are not built yet\"),null;let h=q[C],v=IK();v(\"$id\",\"id\"),h.forEach(([L,E])=>v(\"$\"+L,E));let j={key:S,name:v,stored:new Map(h.map(([L,E,m])=>[L,[E,m]])),computed:new Map,flags:{moves:!1},out:null,id:null},u={id:\"\",...Object.fromEntries(h.map(([L,,E])=>[L,E===\"number\"?0:E===\"boolean\"?!1:\"\"]))},M=(L)=>L.name??`${S}_row`,N=F.blank(u),R=r(N,M(N),D,{scope:j,weighted:!1}),g=T.group,c=j.stored.get(UK(g)),x=g?c&&c[1]!==\"time\"?c[0]:v(\"~group\",\"group\"):null;if(g&&!(c&&c[1]!==\"time\"))j.computed.set(x,\"string\");let t=(L)=>L?.k===\"path\"?L.abs&&L.segs.length===1&&L.segs[0]===C:L?.k===\"call\"&&L.name===\"filter\"&&t(L.args[0]),e=j.flags.moves;if(e&&(g||!t(T.items)))I.fault(\"the arrows of a row move it in the stored list, so the list shows its rows in their stored order: no sort and no group\");let p=e?v(\"~label\",\"label\"):null;if(p)j.computed.set(p,\"string\");let yK=F.children.filter((L)=>L.row).map((L)=>{let E={};if(r(L,M(L),D,{scope:{...j,out:E,id:String(L.row.id??\"\")},weighted:!1}),j.computed.has(x))try{E[x]=JK(\"string\",K.value(g,{it:L.row}))}catch{E[x]=\"\"}if(p)E[p]=(h.filter(([,,m])=>m===\"string\").map(([m])=>MK(\"string\",L.row[m]).trim()).find(Boolean)??String(L.row.id)).slice(0,300);return{...bK(L.row,h,G),...E}}),WK=!(T.items?.k===\"path\"&&t(T.items))||j.computed.size>0,KK=WK?V(\"view:\"+S,S):Z.get(C);if(WK){let L={id:\"string\",...Object.fromEntries(h.map(([,m,l])=>[m,a(KQ,l)??l])),...Object.fromEntries(j.computed)},E=Object.keys(L).length;if(E>SK)I.fault(`the rows of this list have ${E} fields with the id and the values that a row shows, and a row of Alphy has at most ${SK}. Show fewer values in a row`);P[KK]=yK,_[KK]={type:\"list\",maxItems:EK,fields:L},W.push(D)}let _K=e&&I.action((L)=>{let E=K.read(A,{}),m=E.findIndex((QK)=>QK.id===L?.sourceKey),l=E.findIndex((QK)=>QK.id===L?.destinationKey);return m<0||l<0?!1:K.fire({props:{action:{k:\"call\",name:\"move\",args:[{k:\"lit\",v:l-m}],named:{}}},row:E[m],list:A})},\"_move\"),LK={type:x?\"groupedList\":\"list\",text:I.caption(w),bind:KK,...x&&{groupBy:x},...F.props.empty&&{emptyText:I.plain(F.props.empty).slice(0,300)},..._K&&{reorder:{action:_K,labelField:p}},rowTemplate:R};return H.set(LK,C),LK},{kind:XK,preset:wK,palette:PK}=QQ(K),BK=K.tree(),CK=r(BK,\"root\",\"root\",{scope:null,weighted:!0}),mK=[...XK?[{type:\"text\",text:String(XK),style:FK.eyebrow}]:[],...BK.type===\"Column\"?CK?.children??[]:[CK].filter(Boolean)];return{ui:{type:\"theme\",preset:wK??\"clean\",...PK&&{palette:PK},cornerRadius:20,children:[{type:\"column\",style:{gap:\"medium\"},children:mK}]},values:f,views:P,viewSchemas:_,viewOwners:W,controls:z,bounds:J,faults:Y,owners:U,listRoots:H}}function JQ(K,Q,V,Z){let $=K.kinds?.[Q];if($===\"list\")return Array.isArray(V)?V.map((q)=>bK(q,K.lists[Q],Z)):[];if($===\"array\")return Array.isArray(V)?V:[];return $===\"time\"||$===\"date\"?MK($,V,Z):V}function UQ(K,Q,V){let Z={},$=gK(K);for(let[q,G]of Q.fields){let[Y,z]=G.split(\".\"),X=JQ(Q,q,K.state[q],$);if(z)(Z[Y]??={})[z]=X;else Z[Y]=X}return{...Z,...V.views,...V.values}}function GQ(K,Q,V){let Z=Q.kinds??{},$=K.nextId-1;for(let[q,G]of Q.fields){let[Y,z]=G.split(\".\"),X=z?V[Y]?.[z]:V[Y];if(X===void 0)continue;if(Z[q]===\"list\")K.state[q]=(Array.isArray(X)?X:[]).map((U)=>{return $=Math.max($,Number(/^r(\\d+)$/.exec(U.id)?.[1]??0)),{id:U.id,...Object.fromEntries(Q.lists[q].map(([H,J,P])=>[H,P===\"time\"?YK(U[J]):U[J]]))}});else K.state[q]=Z[q]===\"time\"?YK(X):Z[q]===\"array\"&&Array.isArray(X)?[...X]:X}K.nextId=$+1,K.recompute()}function fK(K,Q,V=Date.now()){let{state:Z,event:$}=K;if($.type===\"preview\"||$.type===\"render\")return{state:Z};let q=d.now;d.now=()=>V;try{let G=new HK(new VK().feedText(Q.lines)),Y=Q.kinds??{};GQ(G,Q,Z);let z=GK(G,Q),X=$.type===\"action\"?z.controls.get($.actionId):void 0;if($.type!==\"open\"&&!X)return{state:Z};if(X&&X($.value)===!1)return{state:Z};for(let[P,_]of Object.entries(Y))if(_===\"list\"&&G.state[P]?.length>(a(Q.caps,P)??EK)||_===\"array\"&&G.state[P]?.length>eK)return{state:Z};let U=X?GK(G,Q):z,H=U.bounds.filter(([P,_,W])=>!(P.props.value>=_&&P.props.value<=W));if(H.length){for(let[P,_,W]of H)G.write(P.props.bind,Number.isFinite(P.props.value)?Math.min(W,Math.max(_,P.props.value)):_,{it:P.row});G.recompute(),U=GK(G,Q)}let J=UQ(G,Q,U);return JSON.stringify(U.ui)===JSON.stringify(z.ui)?{state:J}:{state:J,ui:U.ui}}finally{d.now=q}}__handle(fK);})();";
globalThis.compileAppFile = compileAppFile;
})();
