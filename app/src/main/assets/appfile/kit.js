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
const { CATALOG, ICONS, ACTIONS, TONES, dayNumber, FUNCTIONS, phased, foreground, shownFormulas, given, App, settings } = (() => {
// Runs an app file without any platform: state, formulas, actions, and the component tree.
// A renderer draws the tree. The checks of the host are in check.js.



/** Positional arguments of each component of the prototype catalog. */
const CATALOG = {
  Column: ["children"], Row: ["children"], Card: ["children"], Section: ["title", "children"],
  Text: ["text"], Heading: ["text"], Metric: ["label", "value"], Emoji: ["value"], Badge: ["value"],
  // label is the caption of a progress, a ring, a chart, and tabs. The text of a ring is the text under it.
  Progress: ["value", "max", "label"], Ring: ["value", "max", "text", "label"], Divider: [], Notice: ["text"],
  Button: ["label", "action"], IconButton: ["icon", "action"],
  TextField: ["label", "bind"], NumberField: ["label", "bind"], Slider: ["label", "bind"],
  Toggle: ["label", "bind"], CheckBox: ["label", "bind"],
  Segmented: ["label", "bind", "options"], Select: ["label", "bind", "options"],
  List: ["items", "template", "group"], Chart: ["items", "point", "value", "label"],
  Tabs: ["titles", "groups", "label"], Dialog: ["title", "children"], DateField: ["label", "bind"], TimeField: ["label", "bind"],
  Picture: ["text", "found"], Choices: ["label", "bind", "options"],
  // The host's own countdown: it keeps its time and has its own buttons and alert (Alphy's foreground timer).
  Timer: ["label", "seconds"],
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

/** A timer need with more than one phase: a rest, or more than one round. Its part shows the phase and the round. */
const phased = (named) => (named.rest && named.rest.v !== 0) || (named.rounds && named.rounds.v !== 1);
/** A timer need with an alert and one phase is the host's own countdown (a Timer line). tick() moves each other timer need. */
const foreground = (need) => need.named.alert?.v === true && !phased(need.named);

/** The values that the entries needs of a file show for each row, show={name: formula}, as [name, formula] pairs. */
const shownFormulas = (file) => file.of("need").filter((entry) => entry.tree.name === "entries" && entry.tree.named?.show?.k === "map").flatMap((entry) => entry.tree.named.show.entries);

/** The arguments of a component call by property name: positional ones by the catalog order, then the named ones. */
const given = (tree) => ({ ...Object.fromEntries((CATALOG[tree.name] ?? []).map((key, index) => [key, tree.args[index]])), ...tree.named });

class App {
  /** start=false: no state line starts and no formula runs, so the caller puts a stored state in first (run.js kitOf). */
  constructor(file, start = true) {
    this.file = file;
    this.faults = [];
    this.nextId = 1;
    this.state = {};
    this.seen = new Map();
    if (start) this.follow();
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
          this.shown ??= Object.fromEntries(shownFormulas(this.file));
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
        // The change keeps the times of its start and its end, so a kit that starts from a stored state, as each event of the run-time does, knows where the value is.
        case "animate": {
          if (first?.k !== "path" || !first.abs) throw new DslError("animate needs a value of the app as its place, such as $/angle");
          const path = first.segs.join("/"), from = Number(this.value(first, ctx)) || 0, to = second ? Number(this.value(second, ctx)) : NaN, ms = call.args[2] ? Number(this.value(call.args[2], ctx)) : 0;
          if (!Number.isFinite(to) || !Number.isFinite(ms)) throw new DslError("animate needs a number to go to and a time in milliseconds: animate($/angle, 360, 5000)");
          const runs = (this.state._run ?? []).filter((run) => run.path !== path), now = settings.now();
          if (ms > 0 && to !== from) runs.push({ path, from, to, start: now, end: now + ms, at: from }); else this.write(first, to, ctx);
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
        if ((tree.name === "Chart" && (key === "point" || key === "value")) || (tree.name === "List" && key === "group") || key === "shapes" || key === "bodies") continue;
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
        // One point for each row. point (the label of the point) and value are formulas of the row.
        try {
          node.props.points = (Array.isArray(node.props.items) ? node.props.items : []).map((row) => ({
            label: props.point ? this.value(props.point, { ...ctx, it: row }) : "", value: Number(props.value ? this.value(props.value, { ...ctx, it: row }) : row) || 0 }));
        } catch (failure) { this.fault(name ?? tree.name, `points: ${failure.message}`); }
        delete node.props.items;
      }
      if (tree.name === "Drawing" || tree.name === "Model3d") {
        const key = tree.name === "Drawing" ? "shapes" : "bodies", list = props[key] ?? { k: "list", items: [] };
        // An item that fails, such as a shape whose x is not a number at run time, is a fault and is left out: the other items still draw.
        node.props[key] = (list.k === "list" ? list.items : [list]).flatMap((item) => {
          try { return this.drawn(key === "shapes" ? SHAPES : BODIES, key === "shapes" ? "shape" : "body", item, ctx); }
          catch (failure) { this.fault(name ?? tree.name, `${key}: ${failure.message}`); return []; }
        });
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
   * The host looks at the clock (settings.now). Each timer need that runs keeps the end of its phase in $/NAME_end, in ms of
   * the clock, and 0 while it stands. So a kit that starts from a stored state, as each event of the run-time does, knows
   * the time that is left. Each phase whose end passed ends: a work phase counts in $/NAME, then its rest phase runs when
   * it has one, then the next round starts. The timer part made the state names. A test and the smoke run move the clock and call this.
   */
  tick() {
    const now = settings.now(), s = this.state;
    // A timed change (the animate action) is at the share of its time that passed: a late look ends it at its value. A value that
    // something else set in the meantime ends its change. A clock that goes back keeps the value at its start.
    if (s._run) {
      s._run = s._run.filter((run) => {
        const place = this.pathOf("$/" + run.path);
        if (this.read(place, {}) !== run.at) return false;
        run.at = now >= run.end ? run.to : run.from + ((run.to - run.from) * Math.max(0, now - run.start)) / (run.end - run.start);
        this.write(place, run.at, {});
        return now < run.end;
      });
      if (!s._run.length) delete s._run;
      this.recompute();
    }
    for (const entry of this.file.of("need")) {
      if (entry.tree.name !== "timer" || foreground(entry.tree)) continue;
      const n = entry.key, number = (tree, fallback) => { try { return Number(tree ? this.value(tree, {}) : fallback) || 0; } catch { return 0; } };
      const phase = () => Number(s[n + "_phase"]) || 0;
      if (!s[n + "_on"]) { s[n + "_end"] = 0; continue; }
      // A start, also after a pause: the phase ends when the time that is left passes.
      if (!s[n + "_end"]) s[n + "_end"] = now + Math.max(0, phase() - s[n + "_spent"]) * 1000;
      // 1000 phases at most in one look, so a phase of no length cannot hold the host.
      for (let guard = 0; s[n + "_on"] && now >= s[n + "_end"] && guard < 1000; guard++) {
        const ended = s[n + "_end"];
        if (!s[n + "_resting"]) s[n] += 1;
        if (!s[n + "_resting"] && number(entry.tree.named.rest, 0) > 0) { s[n + "_resting"] = true; s[n + "_spent"] = 0; }
        else if (s[n + "_round"] >= number(entry.tree.named.rounds, 1)) { s[n + "_over"] = true; s[n + "_on"] = false; s[n + "_spent"] = phase(); s[n + "_end"] = 0; }
        else { s[n + "_round"] += 1; s[n + "_resting"] = false; s[n + "_spent"] = 0; }
        this.recompute();
        // The next phase starts at the end of the last one, not at the look: a late look loses no time.
        if (s[n + "_on"]) s[n + "_end"] = ended + phase() * 1000;
      }
      // The end of a phase is a time of the clock: a clock that goes back makes the phase longer, and the time spent stays at 0 or above.
      // shortcut: a phase length that changes while its timer runs keeps the end, so the time spent jumps by the change; keep the time spent and move the end when a plan changes a phase while it runs.
      if (s[n + "_on"]) s[n + "_spent"] = Math.max(0, phase() - (s[n + "_end"] - now) / 1000);
      this.recompute();
    }
  }

  /**
   * What a person does to a drawn node: change its field, or press it. Returns an error text or "". The look at the clock
   * after the action gives a timer that the action started or stopped the end of its phase.
   */
  change(node, value) {
    this.tick();
    try {
      this.write(node.props.bind, value, { it: node.row });
      if (node.props.action) this.act(node.props.action, { it: node.row, list: node.list }); else this.recompute();
    } catch (failure) { return `${node.name ?? node.type}: ${failure.message}`; }
    this.tick();
    return "";
  }

  fire(node) {
    if (node.props.enabled === false || node.props.visible === false) return `${node.name ?? node.type} is not active`;
    this.tick();
    try { this.act(node.props.action, { it: node.row, list: node.list }); } catch (failure) { return `${node.name ?? node.type}: ${failure.message}`; }
    this.tick();
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

return { CATALOG, ICONS, ACTIONS, TONES, dayNumber, FUNCTIONS, phased, foreground, shownFormulas, given, App, settings };
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
    return { lines: [`${name}_chart = Chart(${show(list)}, ${show(point)}, ${show(value)}, kind=${show(need.named.kind ?? { k: "lit", v: "bar" })}, label=${show(title)})`, `${name}_view = Section(${show(title)}, [${name}_chart])`], top: [`${name}_view`] };
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

  // need pages = tabs(["Summary", "Add"], [[total, chart], [costs]], title="Month"): other needs in groups, one group at a time. The person opens a tab at any time.
  // The caption of the tabs is the title, else the titles of the tabs.
  async tabs(name, need, { tops }) {
    const [titles, groups] = need.args, label = need.named.title ? show(need.named.title) : `join(${show(titles)})`;
    return { lines: [`${name}_view = Tabs(${show(titles)}, [${groups.items.map((group) => `[${group.items.flatMap((member) => tops(memberName(member))).join(", ")}]`).join(", ")}], label=${label})`],
      top: [`${name}_view`], holds: groups.items.flatMap((group) => group.items.map(memberName)) };
  },

  // need done = progress("Done today", count($/habits, $done), len($/habits))
  async progress(name, need, { hero }) {
    const [title, value, goal] = need.args;
    const ring = `Ring($/${name}, $/${name}_goal, $/${name}_text, label=${show(title)})`;
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

  // need tea = timer("Tea", $/kind == "green" ? 120 : 180, alert=true): one phase with an alert is the host's own countdown, a Timer line.
  // need set = timer("Workout", 40, rounds=8, rest=20): each round is a work phase and then a rest phase. $/NAME counts the work phases that ended.
  // A timer with more than one phase, or with no alert, is plain lines with start, pause, and reset. The host moves it with its clock (App.tick).
  // Evidence: 4 of Alphy's 45 corpus cases are timers (workout-de, tea-tr, pomodoro-zh, eggs-it). Only the passing of time is the host's.
  async timer(name, need) {
    const [title, seconds] = need.args, opt = need.named, text = (key, fallback) => (opt[key] ? show(opt[key]) : q(fallback)), at = (part) => `$/${name}_${part}`;
    if (foreground(need)) {
      const unshown = ["start", "pause", "reset", "done"].filter((key) => opt[key]);
      return { lines: [`${name}_timer = Timer(${show(title)}, ${show(seconds ?? { k: "lit", v: 60 })})`, `${name}_view = Card([${name}_timer])`], top: [`${name}_view`],
        note: unshown.length ? `need ${name}: the host's countdown shows its own buttons and alert, so ${unshown.map((key) => key + "=").join(", ")} do not show` : null };
    }
    // The phase and the round show when the timer has more than one phase.
    const many = phased(opt);
    const lines = [`$/${name} = 0`, `${at("on")} = false`, `${at("round")} = 1`, `${at("resting")} = false`, `${at("over")} = false`, `${at("spent")} = 0`, `${at("end")} = 0`,
      `${at("phase")} := ${at("resting")} ? ${opt.rest ? show(opt.rest) : 0} : ${show(seconds ?? { k: "lit", v: 60 })}`,
      // The time that is left in whole seconds. round(…, 3) keeps a sum of parts of a second from showing one second more.
      `${at("left")} := ceil(round(max(0, ${at("phase")} - ${at("spent")}), 3))`,
      `${at("clock")} := pad2(floor(${at("left")} / 60)) + ":" + pad2(${at("left")} % 60)`,
      `${name}_time = Metric(${show(title)}, ${at("clock")}, emphasis="display")`,
      ...(many ? [`${name}_phaseText = Text((${at("resting")} ? ${text("restText", "Rest")} : ${text("workText", "Work")}) + " " + text(${at("round")}) + " / " + text(${opt.rounds ? show(opt.rounds) : 1}))`] : []),
      `${name}_bar = Progress(${at("spent")}, ${at("phase")}, label=${show(title)})`,
      `${name}_start = Button(${text("start", "Start")}, action=set(${at("on")}, true), visible=!${at("on")} && !${at("over")})`,
      `${name}_pause = Button(${text("pause", "Pause")}, action=set(${at("on")}, false), visible=${at("on")})`,
      `${name}_reset = Button(${text("reset", "Reset")}, action=[set(${at("on")}, false), set(${at("spent")}, 0), set(${at("round")}, 1), set(${at("resting")}, false), set(${at("over")}, false)], enabled=${at("on")} || ${at("spent")} > 0 || ${at("round")} > 1 || ${at("resting")} || ${at("over")})`,
      `${name}_doneNote = Notice(${text("done", "Time is up")}, visible=${at("over")})`];
    const parts = [`${name}_time`, ...(many ? [`${name}_phaseText`] : []), `${name}_bar`, `Row([${name}_start, ${name}_pause, ${name}_reset])`, `${name}_doneNote`];
    // The run-time draws the bar and moves the clock while the app is open. No sound or vibration comes at the end of a phase.
    return { lines: [...lines, `${name}_view = Card([${parts.join(", ")}])`], top: [`${name}_view`],
      note: opt.alert?.v === true ? `need ${name}: a timer with rounds shows the end of a round on the screen only` : null };
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
      // A lesson of at most 12 steps shows one dot for each step (Alphy draws at most 12 dots), and a longer one a bar.
      `${name}_bar = Progress($/${name}, ${count}${steps.k === "list" && steps.items.length <= 12 ? ', variant="dots"' : ""}, label=${text("stepText", "Step")})`,
      `${name}_title = Heading(text($/${name}_now/title))`, `${name}_text = Text(text($/${name}_now/text))`];
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
    // What the part builds in a different way than the need line asks. The report shows it as a note.
    if (built.note) file.partNotes.push(built.note);
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
    if (!file.expanded.has(entry.key)) fresh.push(entry.key);
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
    // A need that a tabs or steps need holds is on the screen inside its holder, so only the holder joins.
    const missing = fresh.filter((key) => !held.has(key)).flatMap((key) => place.get(key)).filter((name) => !root.raw.includes(name));
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
  const real = settings.now, clock = { now: 0 };
  settings.now = () => clock.now;
  try { return smokeRun(file, limit, clock); } finally { settings.now = real; }
}
function smokeRun(file, limit, clock) {
  const app = new App(file), faults = [], notes = [], trace = [], done = new Set(), reached = new Set(), idle = new Map();
  const wait = () => { clock.now += 30 * 864e5; };
  // A state of the run is the text of the app's state and the time of the clock at it. A timer keeps the end of its phase in the state,
  // so the run comes back to a state at its own time: a later time would end the phases of a timer that runs at the next press.
  const save = () => ({ text: JSON.stringify(app.state), now: clock.now });
  const restore = (saved) => { app.state = JSON.parse(saved.text); clock.now = saved.now; app.recompute(); };
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
  const start = save(), all = controls(false).map((control) => control.key);
  /** A control that did nothing so far gets a new try in the state of now. Returns that state. */
  const retryIdle = (list) => {
    const later = save();
    for (const other of list) {
      if (!idle.has(other.key) || other.node.props.bind) continue;
      if (!app.fire(other.node) && JSON.stringify(app.state) !== later.text) idle.delete(other.key);
      restore(later);
    }
    return later;
  };
  // Each control is used from the state in which it came to the screen. So one control cannot hide or delete what the next one needs.
  const queue = [start];
  const drain = () => {
    while (queue.length && done.size < limit) {
      const base = queue.shift();
      const reset = () => restore(base);
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
        if (JSON.stringify(app.state) === base.text) {
          // A control of a list row gets a second try on the last row: the first row cannot move up.
          const rows = [];
          const find = (other) => { if (other.name === key && other.rowIndex !== undefined) rows.push(other); other.children.forEach(find); };
          reset();
          find(app.tree());
          // With one row the run cannot tell: a row cannot move, and the run does not judge it.
          const alone = node.rowIndex !== undefined && rows.length < 2;
          if (!p.bind && !alone && !(rows.length > 1 && !app.fire(rows.at(-1)) && JSON.stringify(app.state) !== base.text)) idle.set(key, title);
          continue;
        }
        // For the judge: what each control did to the values of the app.
        const was = JSON.parse(base.text);
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
        const ended = save();
        if (ended.text === later.text) continue;
        const stuck = Object.keys(app.state).filter((name) => !finite(app.state[name]));
        if (stuck.length) { faults.push(`smoke run: after the time of a timer passes, ${stuck.map((name) => "$/" + name).join(", ")} is not a finite number`); continue; }
        trace.push("time passes: " + Object.keys(app.state).filter((name) => JSON.stringify(JSON.parse(later.text)[name]) !== JSON.stringify(app.state[name])).map((name) => `${name} -> ${short(app.state[name])}`).join(", "));
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
    restore(begin);
    shapes.add(shape());
    for (let step = 0; step < 60; step++) {
      // A tab button is not a step of the walk. There is one walk for each tab, and it stays in that tab.
      const usable = controls().filter((control) => !control.node.props.bind && !deletes(control.node) && !control.node.tab);
      if (usable.some((control) => !done.has(control.key))) queue.push(save());
      if (!usable.length) break;
      const now = save();
      states.add(now.text);
      const tried = usable.map((control) => {
        const failed = app.fire(control.node), found = { control, newShape: !failed && !shapes.has(shape()), newState: !failed && !states.has(JSON.stringify(app.state)) };
        restore(now);
        return found;
      });
      const count = (item) => taps.get(item.control.key) ?? 0;
      const pick = (tried.find((item) => item.newShape) ?? tried.filter((item) => item.newState).sort((a, b) => count(a) - count(b))[0])?.control ?? usable[step % usable.length];
      taps.set(pick.key, (taps.get(pick.key) ?? 0) + 1);
      if (app.fire(controls().find((control) => control.key === pick.key)?.node ?? pick.node)) break;
      if (JSON.stringify(app.state) !== now.text) idle.delete(pick.key);
      shapes.add(shape());
      // The walk prefers new states, so it does not tap a button that goes back to the start. Such a button gets its try here.
      if (idle.size) retryIdle(controls());
    }
  };
  restore(start);
  const tabStates = controls().filter((control) => control.node.tab && !control.node.props.selected).map((control) => {
    app.fire(control.node);
    const state = save();
    restore(start);
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
// rows.js
const { MAX_ROW_FIELDS, MAX_ROWS, ALPHY, namer, wished, clockOf, cast, castRow, own, shownAs, relative, rows } = (() => {
// The rows of a List as Alphy's list, and the value forms that a list shares with the screen and the state: the Alphy names of
// the kit's names, a time as minutes, a date as a real date, and a shown value in the type of its field. run.js builds the
// screen and gives each List to rows.



/** Alphy's rule for a state field and an action id (ToolValueSchema.name, ToolUiCatalog.registerAction). */
const SAFE = /^[A-Za-z][A-Za-z0-9_]{0,47}$/;
const REFUSED = new Set(["__proto__", "prototype", "constructor"]);
/** A row of Alphy has at most 12 flat fields with its id, and a list at most 100 rows (ToolValueSchema). */
const MAX_ROW_FIELDS = 12;
const MAX_ROWS = 100;
/** The Alphy type of each kind of row field: a time is minutes, and a date is a "YYYY-MM-DD" text. */
const ALPHY = { time: "number", date: "string" };

/**
 * The Alphy names of the kit's keys. A key gets the name that it wishes when Alphy takes that name and no other key has it, else
 * f<index>. differ keeps by key only the names that are not the wish, and taken holds the names that are given already. A walk of
 * the same keys in the same order with the same differ and taken gives the same names, so the compiled data keeps only differ.
 */
function namer(differ = {}, taken = new Set(Object.values(differ))) {
  const named = new Map();
  let next = 0;
  return (key, wish = key) => {
    if (named.has(key)) return named.get(key);
    let name = Object.hasOwn(differ, key) ? differ[key] : wish;
    // d, v, d2, and so on are the objects of the shown values.
    if (!Object.hasOwn(differ, key) && (!SAFE.test(name) || REFUSED.has(name) || /^[dv]\d*$/.test(name) || taken.has(name))) {
      while (taken.has("f" + next)) next++;
      name = differ[key] = "f" + next;
    }
    named.set(key, name);
    taken.add(name);
    return name;
  };
}

/**
 * The name that a key of the screen wishes. The key of an inline child joins the key of its parent and its place with ":", which
 * no line name holds, so it never is the key of a line, such as bar_0. Its wish joins them with "_".
 */
const wished = (key) => key.replaceAll(":", "_");

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
const own = (map, key) => (Object.hasOwn(map, key) ? map[key] : undefined);
/** Alphy's state holds a text of at most 2048 characters (ToolValueSchema). */
const MAX_TEXT = 2048;
/**
 * A shown value in the type of its field, which the emitter fixes with the first value (app.types): the stateSchema keeps that
 * type. A number field takes 0 for a value that is not a finite number, and a text field is never null, a list, or a record.
 * Each value stays inside what Alphy's state holds: a number of at most 2^53 - 1 either way, and a text of at most 2048 characters.
 */
const shownAs = (type, value) => (type === "number" ? (Number.isFinite(value) ? Math.min(Number.MAX_SAFE_INTEGER, Math.max(-Number.MAX_SAFE_INTEGER, value)) : 0)
  : (value !== null && typeof value === "object" ? "" : String(value ?? "")).slice(0, MAX_TEXT));
/** The field name of a path of the row, such as $name, or null. */
const relative = (expr) => (expr?.k === "path" && !expr.abs && expr.segs.length === 1 ? expr.segs[0] : null);

/**
 * A List as an Alphy list of its stored rows. A row template is the kit's template drawn for a blank row. When the list shows
 * other rows (sort, filter) or values that the run-time computes, it binds a view: a list field that the run-time writes,
 * with the rows in the kit's order. A row control of the view changes the stored row with the same id. screen is what the
 * walk of the screen shares: the kit, the names, the walk, the day, the stored fields and lists, and the outputs of views and lists.
 */
function rows(screen, node, key, line, expr, at, title) {
  const { kit, name, walk, day, fields, lists, views, viewSchemas, viewOwners, listRoots } = screen;
  const source = node.source, root = source?.k === "path" && source.abs && source.segs.length === 1 && lists[source.segs[0]] ? source.segs[0] : null;
  if (!root || !node.blank) { at.fault("a List shows the rows of a list of the app, such as $/rows, or sort or filter of it. Other rows are not built yet"); return null; }
  const spec = lists[root], rowName = namer();
  rowName("$id", "id");
  spec.forEach(([field, safe]) => rowName("$" + field, safe));
  const scope = { key, name: rowName, stored: new Map(spec.map(([field, safe, kind]) => [field, [safe, kind]])), computed: new Map(), flags: { moves: false }, out: null, id: null };
  const blank = { id: "", ...Object.fromEntries(spec.map(([field, , kind]) => [field, kind === "number" ? 0 : kind === "boolean" ? false : ""])) };
  const keyOf = (template) => template.name ?? `${key}:row`, template = node.blank(blank);
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
  const view = !(expr.items?.k === "path" && inOrder(expr.items)) || scope.computed.size > 0, field = view ? name("view:" + key, wished(key)) : fields.get(root);
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

return { MAX_ROW_FIELDS, MAX_ROWS, ALPHY, namer, wished, clockOf, cast, castRow, own, shownAs, relative, rows };
})();
// draw.js
const { BAR_SURFACE, sideOf, LIMITS, PALETTE, utf8, commandsOf, barOf, budgetOf, fit } = (() => {
// The drawing of a surface: the kit's shapes as Alphy's drawing commands (GraphicsContract.frame), and the limits of one reply.



/** The surface of a timer's bar, in Alphy's pixels of a surface. */
const BAR_SURFACE = { width: 320, height: 64 };
/** A side of a surface: Alphy takes 64 to 2048 pixels each way. */
const sideOf = (value) => Math.min(2048, Math.max(64, Math.round(Number(value)) || 0));
/** What Alphy draws in one reply, for all surfaces together (GraphicsContract.frame, ArtifactProtocol.validateSurfaces). */
const LIMITS = { commands: 512, pairs: 2048, chars: 2048, bytes: 32768 };
/** A neutral track at 20 % opacity, so the bar reads on a light and on a dark theme. */
const TRACK = "#33808080";
/** The kit's color names as Alphy's colors: a tone of the theme for a role, else the hex of the kit. */
const ROLES = { ink: "default", muted: "muted", accent: "accent", success: "success", warning: "warning" };
const PALETTE = Object.fromEntries(Object.keys(TONES).map((name) => [name, ROLES[name] ?? TONES[name]]));
const HEX = /^#(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/;
/** A color name of the palette, a hex that passes as it is, or the default ink for each other text. */
const colorOf = (color, palette) => (Object.hasOwn(palette, color) ? palette[color] : HEX.test(color) ? color : "default");

/** The UTF-8 bytes of a text. The sandbox has no TextEncoder that the kit can count on. */
const utf8 = (text) => {
  let count = 0;
  for (const char of text) { const code = char.codePointAt(0); count += code < 0x80 ? 1 : code < 0x800 ? 2 : code < 0x10000 ? 3 : 4; }
  return count;
};
/** The bytes of a value in JSON as the host writes it: org.json writes "/" as "\/". */
const hostBytes = (value) => utf8(JSON.stringify(value).replace(/\//g, "\\/"));
/** A sum such as x - r keeps 2 decimals, as the kit's numbers do, with no float noise. */
const fixed = (value) => Math.round(value * 100) / 100;
const clamp = (value, low, high) => Math.min(high, Math.max(low, value));
/** True when each value is a coordinate that Alphy takes: a finite number from -8192 to 8192. */
const inside = (values) => values.every((value) => Number.isFinite(value) && Math.abs(value) <= 8192);
const closed = (points) => ({ type: "polyline", points: [...points, points[0]], stroke: 2 });

/** One shape of the kit (App.drawn) as one command of Alphy, or null for a shape that Alphy cannot draw. */
function commandOf({ shape, x, y, r, w, h, x1, y1, x2, y2, text, points, radius, width, size, stroke }) {
  if (shape === "circle") {
    if (!(r >= 0 && r <= 2048) || !inside([x - r, y - r, x + r, y + r])) return null;
    const side = fixed(2 * r);
    return stroke ? { type: "arc", x: fixed(x - r), y: fixed(y - r), width: side, height: side, start: 0, sweep: 360, stroke: 2 } : { type: "circle", x, y, radius: r };
  }
  if (shape === "rect") {
    // A size below 0 is the same box from its other corner.
    const box = { x: Math.min(x, fixed(x + w)), y: Math.min(y, fixed(y + h)), width: Math.abs(w), height: Math.abs(h) };
    const right = fixed(box.x + box.width), bottom = fixed(box.y + box.height);
    if (!inside([box.x, box.y, right, bottom]) || box.width > 8192 || box.height > 8192) return null;
    if (stroke) return closed([[box.x, box.y], [right, box.y], [right, bottom], [box.x, bottom]]);
    return radius ? { type: "roundRect", ...box, radius: clamp(radius, 0, 1024) } : { type: "rect", ...box };
  }
  if (shape === "line") return inside([x1, y1, x2, y2]) ? { type: "line", x: x1, y: y1, x2, y2, stroke: clamp(width ?? 2, 0.1, 128) } : null;
  if (shape === "text") return inside([x, y]) ? { type: "text", x, y, size: clamp(size ?? 14, 1, 128), text: text.slice(0, LIMITS.chars), align: "center", baseline: "middle" } : null;
  if (shape === "polygon") {
    // [x1, y1, x2, y2, ...] as [x, y] points. A polygon has 3 to 2048 points, and its outline 2 or more and its first point again.
    const pairs = [];
    for (let index = 0; index + 1 < points.length; index += 2) pairs.push([points[index], points[index + 1]]);
    if (!inside(pairs.flat()) || pairs.length < (stroke ? 2 : 3) || pairs.length > (stroke ? 2047 : 2048)) return null;
    return stroke ? closed(pairs) : { type: "polygon", points: pairs };
  }
  return null;
}

/**
 * The shapes of the kit as Alphy's commands, each with its color by the palette: a color name as an Alphy color. A shape that
 * Alphy cannot draw is left out, and a size past Alphy's bounds is clamped.
 */
const commandsOf = (shapes, palette = PALETTE) => shapes.flatMap((shape) => { const command = commandOf(shape); return command ? [{ ...command, color: colorOf(shape.color, palette) }] : []; });

/** The bar of a timer's phase: the track, and the share of the phase that passed (0 to 1) in the color of the phase. Two shapes. */
const barOf = (share, color) => [
  { shape: "rect", x: 0, y: 24, w: BAR_SURFACE.width, h: 16, color: TRACK, radius: 8 },
  { shape: "rect", x: 0, y: 24, w: Math.round(BAR_SURFACE.width * share), h: 16, color, radius: 8 },
];

/** What one command costs: a box, a circle, and a text 1 pair, a line 2, a polygon or a polyline 1 for each point (GraphicsContract.frame). */
const pairsOf = (command) => command.points?.length ?? (command.type === "line" ? 2 : 1);
const charsOf = (command) => command.text?.length ?? 0;
/** What commands cost in one reply: commands, pairs, text characters, and the bytes of {commands} as the host writes it. */
const budgetOf = (commands) => ({ commands: commands.length, pairs: commands.reduce((sum, command) => sum + pairsOf(command), 0),
  chars: commands.reduce((sum, command) => sum + charsOf(command), 0), bytes: hostBytes({ commands }) });

/**
 * The surfaces of one reply, {id: {commands}}, from [id, commands] in order. Alphy refuses a reply past a limit for all surfaces
 * together, so the reply keeps the first commands that fit, and none after the first one that does not.
 */
function fit(surfaces) {
  const used = { commands: 0, pairs: 0, chars: 0, bytes: hostBytes(Object.fromEntries(surfaces.map(([id]) => [id, { commands: [] }]))) };
  let full = false;
  return Object.fromEntries(surfaces.map(([id, commands]) => [id, { commands: commands.filter((command, index) => {
    // A command costs its bytes, and a comma after the first command of its surface. The commands before it were kept, as none is kept after the first that does not fit.
    const cost = { commands: 1, pairs: pairsOf(command), chars: charsOf(command), bytes: hostBytes(command) + (index ? 1 : 0) };
    full ||= Object.keys(LIMITS).some((key) => used[key] + cost[key] > LIMITS[key]);
    if (!full) for (const key of Object.keys(cost)) used[key] += cost[key];
    return !full;
  }) }]));
}

return { BAR_SURFACE, sideOf, LIMITS, PALETTE, utf8, commandsOf, barOf, budgetOf, fit };
})();
// run.js
const { PER_OBJECT, MAX_ARRAY, TYPED, MAX_POINTS, ALERT_CAPABILITIES, header, screen, stateOf, load, kitOf, handle } = (() => {
// The run-time of a compiled app. It runs inside the app's source in the sandbox (kit/build.js): for each event, the kit's
// App reads the lines of the app, takes the stored state, runs the event, and gives the next state and screen.
// The emitter (emit.js) builds the first screen and the first state with the same screen and stateOf functions.






/** An object of Alphy holds at most 32 fields, so the shown values go into d, d2, d3, and the tests into v, v2. */
const PER_OBJECT = 32;
/** An array of Alphy holds at most 512 values (ToolValueSchema). */
const MAX_ARRAY = 512;
/** The fields that a person types into. An add form with more than two of them is a bottom sheet. A Dialog that holds any field is one too. */
const TYPED = new Set(["TextField", "NumberField", "DateField", "TimeField"]);
/** A chart of Alphy draws at most 256 points (ToolUiCatalog), so the run-time writes the last 256. */
const MAX_POINTS = 256;
/** The alert at the end of the host's countdown, and the capabilities that it asks for (ForegroundTimerSpec.requests). */
const ALERT = { vibrationMs: 600, toneMs: 600 };
const ALERT_CAPABILITIES = ["audio.tone", "vibration.play"];
/**
 * The host's countdown runs from 1 s to 24 h (ForegroundTimerSpec). A timer that the run-time moves gets one timer event each second,
 * and a value that an animate moves one each 50 ms.
 */
const MIN_TIMER_MS = 1000, MAX_TIMER_MS = 86400000, TICK_MS = 1000, ANIM_MS = 50;
/** The change of a value that no animate moves now. */
const STILL = { from: 0, to: 0, start: 0, end: 0, at: 0 };

/** The values of the app line: app("Title", lang="en", kind="Lesson", preset="clean", palette="teal"). */
function header(kit) {
  const tree = kit.file.get("app")?.tree, read = (arg) => (arg ? kit.value(arg, {}) : undefined);
  return { title: read(tree?.args[0]), ...Object.fromEntries(Object.entries(tree?.named ?? {}).map(([key, arg]) => [key, read(arg)])) };
}

const today = (kit) => kit.value({ k: "call", name: "today", args: [] }, {});

const STYLES = { eyebrow: { emphasis: "label", tone: "accent" }, muted: { tone: "muted" } };
/** The kit's icons that Alphy has under a different name (ToolUiCatalog.icons). ArrowDown has no glyph of its own there. */
const ICON_NAMES = { ArrowUp: "Arrow", ArrowDown: "Sort", ArrowLeft: "Back", ArrowRight: "Chevron", Edit: "Pencil" };
/** What a row template of Alphy cannot hold (ToolUiCatalog: no list, dialog, sheet, or canvas in a row), and what keeps one state field for all rows. */
const NOT_IN_ROW = new Set(["List", "Dialog", "Chart", "Tabs", "Timer", "Drawing"]);
/** The components of a part that a later task builds: the fault names the kind of the need. */
const LATER = { Picture: "picture", Link: "link" };
/** The share of value in max in percent, from 0 to 100: 0 for a max of 0 or below and for a value that is not finite. */
const percent = (value, max) => (Number.isFinite(value) && Number.isFinite(max) && max > 0 ? Math.min(100, Math.max(0, (value / max) * 100)) : 0);
/** True when an action appends a row: append(...) alone, in a list of actions, or in when(...). */
const appends = (tree) => (tree?.k === "call" ? tree.name === "append" || tree.args.some(appends) : tree?.k === "list" && tree.items.some(appends));
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
  // An add form with more than two typed fields is a column of a button and the bottom sheet that the button opens. A weighted
  // child needs a row that does not wrap. Alphy draws a row with no wrap=false as a flow row, so that row is a flowRow.
  Row: (n, at) => at.form() ?? (n.children.some((child) => child.props.weight !== undefined) ? { type: "row", wrap: false, children: at.children(true) } : { type: "flowRow", children: at.children() }),
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
    if (!(ms >= MIN_TIMER_MS && ms <= MAX_TIMER_MS)) at.fault(`a Timer runs from 1 second to 24 hours, and this one has ${n.props.seconds} seconds`);
    return { type: "timer", text: at.label(), mode: "countdown", action: at.action(() => false),
      foreground: { durationMs: Number.isFinite(ms) ? Math.min(MAX_TIMER_MS, Math.max(MIN_TIMER_MS, ms)) : MIN_TIMER_MS, ...ALERT } };
  },
  // A ring takes children only when it is large (ToolUiCatalog): its text is a text under it.
  Ring: (n, at) => ({ type: "progressRing", text: at.label(), size: "large", min: 0, max: 100, bind: at.computed("value", percent(n.props.value, n.props.max)),
    ...(n.props.text !== undefined && { children: [{ type: "text", ...at.text("text") }] }) }),
  Chart: (n, at) => at.chart(),
  Tabs: (n, at) => at.tabs(),
  // A canvas with one surface, which each reply draws again.
  Drawing: (n, at) => at.canvas(commandsOf(n.props.shapes), sideOf(n.props.width), sideOf(n.props.height)),
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
 * the bounds of each number control, the faults as [line, text], for the emitter the line of each node of the ui (owners)
 * and the stored list of each list node (listRoots), and each surface that the walk draws as {id, width, height, running, commands, line} (drawn).
 */
function screen(kit, app, first = false) {
  // The names of the screen avoid the names of the stored fields and the timer id of animate. The emitter's walk and each event's walk start with the same taken names.
  // shortcut: a key that is new at run time could take a name that the emitter gave at its wish; no walk makes new keys now, add them to app.names when one does.
  const name = namer(app.names, new Set([...Object.values(app.names), ...app.fields.flatMap(([, path]) => path.split(".")), ...(app.anim ? [app.anim.field] : [])]));
  const fields = new Map(app.fields), kinds = app.kinds ?? {}, lists = app.lists ?? {}, day = today(kit);
  // The first walk (the emitter's) fixes the type of each shown value with its first value. The compiled data keeps the numbers, and each other shown value is a text.
  const typeOf = (key, value) => (first ? (app.types[key] ??= typeof value === "number" ? "number" : "string") : app.types[key] ?? "string");
  const faults = [], controls = new Map(), rowControls = new Map(), owners = new Map(), listRoots = new Map(), bounds = [], views = {}, viewSchemas = {}, viewOwners = [];
  const slots = { d: new Map(), v: new Map() }, values = {};
  // The bar of each timer need that the run-time moves, by the name of its line, and each surface that the walk draws.
  const timed = new Map(kit.file.of("need").filter((entry) => entry.tree.name === "timer" && !foreground(entry.tree)).map((entry) => [`${entry.key}_bar`, entry.key]));
  const drawn = [];
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
      const type = kind === "v" ? "boolean" : typeOf(`${scope.key}/${field}`, value);
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
      (values[object] ??= {})[field] = kind === "v" ? Boolean(value) : shownAs(typeOf(field, value), value);
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
  /** ctx: scope is the row of a list (null at the top), sheet is the open flag of the add sheet, weighted is true in a column or a row. */
  const walk = (node, key, line, ctx) => {
    line = node.name ?? line;
    const scope = ctx.scope, map = own(NODES, node.type), fault = (text) => faults.push([line, text]);
    if (!map) { fault(`${own(LATER, node.type) ?? node.type} is not built yet`); return null; }
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
      children: (weighted = false, inner = within) => node.children.map((child, index) => walk(child, child.name ?? `${key}:${index}`, line, { ...inner, weighted })).filter(Boolean),
      // The text of a control: its label, else the kit's name of its line, which a person can read. Never a safe name such as f3.
      caption: (label) => at.plain(label) || (node.name ? node.name : `${line} ${node.props.icon ?? node.type}`).replace(/_/g, " "),
      // The text of a progress, a ring, a chart, and tabs: its label=, else the title of the Section around it, else the caption. Alphy shows at most 300 characters.
      label: () => at.caption(at.plain(node.props.label) || ctx.title).slice(0, 300),
      text: (prop) => { const bind = bound(prop); return bind ? { text: "", bind } : { text: at.plain(node.props[prop]) }; },
      // A text of the screen that is a list or a record is a fault, never "a,b" or "[object Object]".
      plain: (value) => {
        if (value === null || typeof value !== "object") return String(value ?? "");
        fault(`it shows a ${Array.isArray(value) ? "list" : "record"} as a text, and Alphy shows one text there. Show one field of it, or join(list)`);
        return "";
      },
      value: (prop) => { const bind = bound(prop); return bind ? { bind } : { [prop]: node.props[prop] }; },
      shown: (prop) => bound(prop) ?? at.computed(prop, node.props[prop]),
      // A value that the run-time computes for the node, such as the share of a progress.
      computed: (prop, value) => slot("d", `${key}.${prop}`, null, value, at),
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
      // An action returns "" or the kit's error text, or false for an event that changes nothing. A press of the add button of an add sheet that the kit takes closes the sheet.
      action: (run = () => { const done = kit.fire(node); if (done === "" && ctx.sheet?.add === node) kit.state[ctx.sheet.flag] = false; return done; }, suffix = "") => {
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
          { type: "bottomSheet", text, visibleBind: fields.get(flag), dismissAction: at.action(open(false), "_dismiss"), children: at.children(false, { ...ctx, sheet: { flag, add } }) }] };
      },
      modal: () => {
        if (!expr.visible || expr.visible.k === "lit") fault("a Dialog shows while a test of the app is true, so it needs visible=test, such as visible=$/open");
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
      // A canvas with one surface of its own, which the reply draws.
      canvas: (commands, width, height, running = false) => {
        const id = name("surface:" + key, wished(key));
        drawn.push({ id, width, height, running, commands, line });
        return { type: "canvas", text: at.label(), surfaces: [id] };
      },
      // The points of a chart are two arrays that the run-time writes: the labels and the values of the last 256 points.
      chart: () => {
        const kind = node.props.kind ?? "bar", text = at.label().slice(0, 100), points = (Array.isArray(node.props.points) ? node.props.points : []).slice(-MAX_POINTS);
        if (kind !== "bar" && kind !== "line") fault(`a Chart of kind ${at.plain(kind)} is not built yet: give kind="bar" or kind="line"`);
        const [categories, values] = [["labels", "string", "label"], ["values", "number", "value"]].map(([suffix, type, prop]) => {
          const field = name(`chart:${key}:${suffix}`, wished(`${key}_${suffix}`));
          views[field] = points.map((point) => shownAs(type, point[prop]));
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
        if (!valid) fault(`a Tabs needs one title for each of its ${groups.length} groups: texts of 1 to 100 characters, each one different. Now they are ${JSON.stringify(titles)}`);
        // A change of computed titles can make them invalid after the start: the screen then numbers the tabs, so each reply still holds a tabs control.
        const shown = valid ? titles : groups.map((_, index) => String(index + 1));
        if (!shown.includes(kit.state[root])) kit.state[root] = shown[0];
        const open = kit.state[root], select = (value) => { if (!shown.includes(value)) return false; kit.state[root] = value; return ""; };
        return { type: "column", children: [
          { type: "tabs", text: at.label(), bind: fields.get(root), action: at.action(select), options: shown.map((title) => ({ text: title, value: title })) },
          ...groups.map((group, index) => ({ type: "column", visibleBind: slot("v", `${key}.tab${index}`, null, shown[index] === open, at),
            children: group.children.map((child, inner) => walk(child, child.name ?? `${key}:tab${index}:${inner}`, line, { ...ctx, weighted: true })).filter(Boolean) })),
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
  const { kind, preset, palette } = header(kit), tree = kit.tree(), top = walk(tree, "root", "root", { scope: null, weighted: true });
  const children = [...(kind ? [{ type: "text", text: String(kind), style: STYLES.eyebrow }] : []), ...(tree.type === "Column" ? top?.children ?? [] : [top].filter(Boolean))];
  const ui = { type: "theme", preset: preset ?? "clean", ...(palette && { palette }), cornerRadius: 20, children: [{ type: "column", style: { gap: "medium" }, children }] };
  return { ui, values, views, viewSchemas, viewOwners, controls, bounds, faults, owners, listRoots, drawn };
}

/** The Alphy value of a stored value of the kit: a time as minutes, a date as a real date (today for a text that is not one), and each row cast. */
function alphyOf(app, root, value, day) {
  const kind = app.kinds?.[root];
  if (kind === "list") return Array.isArray(value) ? value.map((row) => castRow(row, app.lists[root], day)) : [];
  if (kind === "array") return Array.isArray(value) ? value : [];
  return kind === "time" || kind === "date" ? cast(kind, value, day) : value;
}

/** The Alphy state of the kit's state: each value of a state line in its field, the change of each value that an animate moves, then the views and the shown values. */
function stateOf(kit, app, shown) {
  const state = {}, day = today(kit);
  for (const [root, path] of app.fields) {
    const [field, inner] = path.split("."), value = alphyOf(app, root, kit.state[root], day);
    if (inner) (state[field] ??= {})[inner] = value; else state[field] = value;
  }
  if (app.anim) state[app.anim.field] = Object.fromEntries(app.anim.keys.map(([root, key]) => {
    const run = kit.state._run?.find((each) => each.path === root);
    return [key, run ? { from: run.from, to: run.to, start: run.start, end: run.end, at: run.at } : STILL];
  }));
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
  // A change with an end runs: the kit's tick moves its value to the clock of the event.
  const runs = app.anim ? app.anim.keys.map(([root, key]) => ({ path: root, ...state[app.anim.field]?.[key] })).filter((run) => run.end) : [];
  if (runs.length) kit.state._run = runs; else delete kit.state._run;
  kit.recompute();
}

/**
 * The kit of one event: the lines of the compiled app with the stored state in it. The kit does not start its lines, so its first
 * compute (in load) sees the stored state, and a formula never runs on a state that is not there yet.
 */
function kitOf(app, state) {
  const kit = new App(new AppFile().feedText(app.lines), false);
  kit.nextId = app.next;
  if (app.shown) kit.shown = Object.fromEntries(parseExpression(app.shown).entries);
  load(kit, app, state);
  return kit;
}

/**
 * Answers one event of the sandbox request {artifactId, revision, state, event}. app is the compiled data of emit.js, and now
 * is the clock of the host. A read-only event, an action id that the app does not have, an action that changes nothing, and an
 * action that takes a list past its count of rows (app.caps) or an array past Alphy's limit give the state back as it is. A bound
 * number past its bounds comes back at its bound. The reply holds a new ui only when the screen differs from the screen of the state before the event.
 * An open and a timer event of a timer or a change that runs move each timer and each change to the clock of now. A timer event
 * of nothing that runs, also a late one after a pause or after the end of a change, and each other event of a surface give the state back as it is.
 */
function handle(input, app, now = Date.now()) {
  const { state, event } = input;
  if (event.type === "preview" || event.type === "render") return { state };
  const clock = settings.now;
  settings.now = () => now;
  try {
    const kit = kitOf(app, state), kinds = app.kinds ?? {}, before = screen(kit, app);
    const timed = event.type === "open" || (event.type === "timer" && (event.timerId === app.anim?.field ? Boolean(kit.state._run)
      : before.drawn.some((surface) => surface.id === event.timerId && surface.running)));
    const control = event.type === "action" ? before.controls.get(event.actionId) : timed ? () => kit.tick() : undefined;
    if (!control || control(event.value) === false) return { state };
    for (const [root, kind] of Object.entries(kinds)) {
      if ((kind === "list" && kit.state[root]?.length > (own(app.caps, root) ?? MAX_ROWS)) || (kind === "array" && kit.state[root]?.length > MAX_ARRAY)) return { state };
    }
    let after = screen(kit, app);
    const outside = after.bounds.filter(([node, min, max]) => !(node.props.value >= min && node.props.value <= max));
    if (outside.length) {
      for (const [node, min, max] of outside) kit.write(node.props.bind, Number.isFinite(node.props.value) ? Math.min(max, Math.max(min, node.props.value)) : min, { it: node.row });
      kit.recompute();
      after = screen(kit, app);
    }
    const next = stateOf(kit, app, after), reply = JSON.stringify(after.ui) === JSON.stringify(before.ui) ? { state: next } : { state: next, ui: after.ui };
    // Each reply of an app with surfaces draws each one again, inside Alphy's limits of one reply, and names each timer that runs:
    // the bar of a timer by the id of its surface, and the changes of animate by the field of their state. One list for all.
    if (after.drawn.length) {
      reply.surfaces = fit(after.drawn.map(({ id, commands }) => [id, commands]));
      reply.timers = [...after.drawn.filter((surface) => surface.running).map(({ id }) => ({ id, intervalMs: TICK_MS })), ...(kit.state._run ? [{ id: app.anim.field, intervalMs: ANIM_MS }] : [])];
    }
    return reply;
  } finally { settings.now = clock; }
}

return { PER_OBJECT, MAX_ARRAY, TYPED, MAX_POINTS, ALERT_CAPABILITIES, header, screen, stateOf, load, kitOf, handle };
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
/** Alphy declares at most 4 surfaces (ToolSpec). */
const MAX_SURFACES = 4;
/** The fields of the change of a value that an animate moves: its start and end values, its start and end times in ms of the host's clock, and its value now. */
const RUN = { type: "object", fields: { from: "number", to: "number", start: "number", end: "number", at: "number" } };
/** The limits of one reply by their unit in a fault. */
const UNITS = { commands: "commands", pairs: "coordinate pairs", chars: "text characters", bytes: "bytes" };

/**
 * The definition of the app, its language beside it, and the faults of what the emitter cannot build. app is the compiled
 * data that the source gives the run-time, and only what the run-time reads: the lines that it evaluates (the app line, the
 * formulas, the components, and the need line of each timer that it moves), the field of each state value, the kind of each time, date, list, and array, the fields of each
 * list, the count of rows of each shown list, each shown value that is a number, the names of the screen that differ from their wish,
 * the next row id, the values that an entries need shows for each row, and the field of the changes of animate with the key of each value (anim).
 * Faults give no definition.
 */
function emit(file) {
  if (!runtime.text) throw new Error("emit.js has no run-time text: kit/build.js gives it");
  const kit = new App(file), { title, lang } = header(kit), faults = [], stateSchema = {}, owners = [], needObjects = new Map();
  // Each event starts its kit with no start values, so a new row gets an id after the ids that the start rows took.
  const app = { lines: "", fields: [], types: {}, names: {}, kinds: {}, lists: {}, caps: {}, next: kit.nextId };
  // The run-time gets the field of each state value from app.fields, so these names stay out of app.names.
  const name = namer();
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
    const field = inner ? name("need:" + need, need + "_s") : name("$/" + root, root.replace(/\W/g, "_"));
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
  // The open tab of each Tabs line: a text of its need, or of the line, that only the run-time sets. The screen opens the first tab.
  for (const entry of file.of("component").filter((entry) => entry.tree.k === "call" && entry.tree.name === "Tabs")) {
    const root = `${entry.key}:tab`, need = partOf.get(entry.key);
    kit.state[root] = "";
    place(root, need, need === undefined ? entry.key : `need ${need}`, "string", "tab");
  }
  // Each number of a state line that an animate moves keeps its change in one object of the state, so each event knows where the value is.
  const stored = new Map(app.fields), moved = new Map();
  const animated = (tree, owner) => {
    if (!tree || typeof tree !== "object") return;
    if (tree.k === "call" && tree.name === "animate") {
      const place = tree.args[0], root = place?.k === "path" && place.abs && place.segs.length === 1 ? place.segs[0] : null;
      if (stored.has(root) && typeof kit.state[root] === "number") { if (!moved.has(root)) moved.set(root, owner); }
      else faults.push(`${owner}: animate moves a number of a state line, such as $/angle, and ${place ? show(place) : "nothing"} is not one`);
    }
    Object.values(tree).forEach((inner) => animated(inner, owner));
  };
  file.of("component").forEach((entry) => animated(entry.tree, label(entry.key)));
  if (moved.size) {
    const key = namer(), first = moved.values().next().value;
    app.anim = { field: name("anim", "anim"), keys: [...moved.keys()].map((root) => [root, key("$/" + root, root.replace(/\W/g, "_"))]) };
    stateSchema[app.anim.field] = { type: "object", fields: Object.fromEntries(app.anim.keys.map(([, inner]) => [inner, RUN])) };
    owners.push(first);
    if (moved.size > PER_OBJECT) faults.push(`${first}: animate moves ${moved.size} values of the app, and an object of Alphy holds at most ${PER_OBJECT} fields. Animate fewer values`);
  }
  for (const [field, need] of needObjects) {
    const count = Object.keys(stateSchema[field].fields).length;
    if (count > PER_OBJECT) faults.push(`need ${need}: its part keeps ${count} values in the object ${field}, and an object of Alphy holds at most ${PER_OBJECT} fields. Give the need fewer fields`);
  }
  // The first screen comes from the start values as the run-time reads them: an empty date is today, and a text that is not a time is 00:00.
  load(kit, app, stateOf(kit, app, { views: {}, values: {} }));
  const shown = screen(kit, app, true);
  for (const [key, type] of Object.entries(app.types)) if (type === "string") delete app.types[key];
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
  // A line that the screen shows a second time gives each control in it its action id a second time, and ToolSpec.parse takes each
  // action id once. firstBy keeps the line that shows each line with an id first. The children of the root are the children of the app's column.
  const ids = (node) => [node.action, node.dismissAction, node.reorder?.action, ...(node.surfaces ?? []), ...(node.children ?? []).flatMap(ids), ...(node.rowTemplate ? ids(node.rowTemplate) : [])].filter(Boolean);
  const firstBy = new Map();
  let nodes = 0;
  const measure = (node, depth, owner, again = false) => {
    nodes++;
    const holder = owner === "app" ? "root" : owner;
    owner = shown.owners.get(node) ?? owner;
    if (!again && owner !== holder && owner !== "app" && ids(node).length) {
      if (!firstBy.has(owner)) firstBy.set(owner, holder);
      else {
        // The fault names the shown line itself: the need of a part line can hold both places.
        const twice = `${label(holder)}: it shows ${owner} a second time, after ${label(firstBy.get(owner))}, and each control in it would have its action id two times. Show it in one place`;
        again = true;
        if (!faults.includes(twice)) faults.push(twice);
      }
    }
    const where = label(owner), children = node.children ?? [];
    const deep = `${where}: this component is at depth ${depth} of the screen, and Alphy keeps at most depth ${MAX_DEPTH}. The frame of the app takes depths 0 and 1. Put it in fewer containers`;
    if (depth > MAX_DEPTH) { if (!faults.includes(deep)) faults.push(deep); return; }
    if (children.length > MAX_CHILDREN) faults.push(`${where}: this container has ${children.length} children, and Alphy keeps at most ${MAX_CHILDREN}. Put them in groups`);
    for (const child of [...children, ...(node.rowTemplate ? [node.rowTemplate] : [])]) measure(child, depth + 1, owner, again);
  };
  measure(shown.ui, 0, "app");
  if (nodes > MAX_NODES) faults.push(`app: the screen has ${nodes} nodes, and Alphy keeps at most ${MAX_NODES}. Show fewer components`);
  // The render work of the screen as ToolUiCatalog.validateRenderedWork counts it: each node costs 1 (a hidden node too), a
  // choice costs its options too, and a chart its points times its series, here at its full 256 points. A list draws its row
  // template once for each row, and its groupBy and its reorder cost 1 more for each row.
  const work = (node) => 1 + (CHOICES.has(node.type) ? node.options.length : 0) + (node.type === "chart" ? MAX_POINTS * node.series.length : 0)
    + (node.children ?? []).reduce((sum, child) => sum + work(child), 0);
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
  const fixed = work(shown.ui);
  if (fixed > MAX_RENDER_WORK) faults.push(`app: the screen costs ${fixed} of render work with each chart full at ${MAX_POINTS} points, and Alphy keeps at most ${MAX_RENDER_WORK}. Show fewer charts or components`);
  // One count for all lists of the app: the most rows for which the screen stays inside the limit when each list is full.
  const cost = [...perRow.values()].reduce((sum, each) => sum + each, 0);
  const rowCount = cost ? Math.min(MAX_ROWS, Math.floor((MAX_RENDER_WORK - fixed) / cost)) : MAX_ROWS, storedField = new Map(app.fields);
  // A screen with no room left is the fault of the app alone: a count of rows below 0 says nothing more.
  for (const node of fixed > MAX_RENDER_WORK ? [] : shownLists) {
    const root = shown.listRoots.get(node), where = label(shown.owners.get(node)), start = kit.state[root].length;
    app.caps[root] = rowCount;
    stateSchema[storedField.get(root)].maxItems = stateSchema[node.bind].maxItems = rowCount;
    const few = `${where}: the row shows too much: with each list of the app full, the screen has room for ${rowCount} rows in each list, and a list needs room for ${MIN_ROWS}. Show fewer controls or options in a row, or fewer lists`;
    const over = `${where}: $/${root} starts with ${start} rows, and the screen has room for ${rowCount} rows in each list. Give it fewer rows`;
    for (const fault of [...(rowCount < MIN_ROWS ? [few] : []), ...(start > rowCount ? [over] : [])]) if (!faults.includes(fault)) faults.push(fault);
  }
  // Each drawing and each timer that the run-time moves draws one surface. The emitter checks the drawings of the first state against
  // the limits of one reply, for all surfaces together. At run time a reply keeps its first commands that fit (draw.js fit).
  const surfaces = shown.drawn.map(({ id, width, height }) => ({ id, width, height }));
  // The bytes of {"id":{"commands":[...]},...}: the first brace, then for each surface its id in quotes, a colon, and a comma or the last brace.
  const used = { commands: 0, pairs: 0, chars: 0, bytes: 1 };
  shown.drawn.forEach(({ id, commands, line }, index) => {
    const where = label(line), cost = budgetOf(commands);
    if (index === MAX_SURFACES) faults.push(`${where}: the app draws ${surfaces.length} surfaces, and Alphy draws at most ${MAX_SURFACES}: one for each drawing and each timer with rounds or with no alert. Use fewer`);
    cost.bytes += id.length + 4;
    for (const [unit, limit] of Object.entries(LIMITS)) {
      const before = used[unit];
      used[unit] += cost[unit];
      if (before <= limit && used[unit] > limit) faults.push(`${where}: with this drawing the app draws ${used[unit]} ${UNITS[unit]} at the start, and Alphy draws at most ${limit} ${UNITS[unit]} in one reply. Draw fewer shapes`);
    }
  });
  // Alphy sends a timer event only to an app with a surface, so a value that an animate moves needs one.
  if (moved.size && !surfaces.length) faults.push(`${moved.values().next().value}: animate moves a value with a timer, and Alphy sends timer events only to an app with a surface. Add a drawing that shows the value`);
  const language = typeof lang === "string" ? lang : null;
  // The run-time has no parts, and the state of each event replaces each start value: it reads no state line. It reads the need
  // line of each timer that it moves: App.tick takes the rounds and the rest from it.
  const timed = file.of("need").filter((entry) => entry.tree.name === "timer" && !foreground(entry.tree));
  app.lines = file.of("app").concat(file.of("formula"), file.of("component"), timed).map((entry) => entry.raw).join("\n");
  // A value that an entries need shows for each row reads as a field of the row in each formula of a row (App.value).
  const shows = shownFormulas(file);
  if (shows.length) app.shown = show({ k: "map", entries: shows });
  if (faults.length) return { definition: null, language, faults, app };
  const source = `${runtime.text}\nreturn handle(input, ${JSON.stringify(app)});`, size = utf8(source);
  if (size > MAX_SOURCE_BYTES) return { definition: null, language, faults: [`app: the source is ${size} bytes, and Alphy keeps at most ${MAX_SOURCE_BYTES} bytes. Use shorter texts and fewer lines`], app };
  // The alert of the host's countdown asks for its capabilities, and Alphy refuses a timer whose app does not declare them.
  const capabilities = file.of("component").some((entry) => entry.tree.k === "call" && entry.tree.name === "Timer") ? [...ALERT_CAPABILITIES] : [];
  const definition = { formatVersion: 3, title, description: "", capabilities, ...(surfaces.length && { surfaces }), stateSchema, initialState: stateOf(kit, app, shown), ui: shown.ui, source };
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
runtime.text = "let handle;const __handle=(f)=>{handle=f};\n(()=>{var kQ={\"||\":1,\"&&\":2,\"==\":3,\"!=\":3,\"<\":4,\"<=\":4,\">\":4,\">=\":4,\"+\":5,\"-\":5,\"*\":6,\"/\":6,\"%\":6};class M extends Error{}function qV(Q){let V=[],Z=0,$=(q)=>{throw new M(`${q} at column ${Z+1}`)};while(Z<Q.length){let q=Q[Z];if(/\\s/.test(q)){Z++;continue}if(q==='\"'){let J=Z+1,B=\"\";while(J<Q.length&&Q[J]!=='\"'){if(Q[J]===\"\\\\\")J++,B+={n:`\n`,t:\"\\t\"}[Q[J]]??Q[J];else B+=Q[J];J++}if(J>=Q.length)$(\"string without a closing quote\");V.push({t:\"str\",v:B}),Z=J+1;continue}if(q===\"$\"){let J=/^\\$(\\/?)([\\p{L}_][\\p{L}\\p{N}_]*(?:\\/[\\p{L}_][\\p{L}\\p{N}_]*)*)/u.exec(Q.slice(Z));if(!J)$(\"path expected after $\");V.push({t:\"path\",abs:J[1]===\"/\",segs:J[2].split(\"/\")}),Z+=J[0].length;continue}let K=/^(\\d+\\.?\\d*|\\.\\d+)/.exec(Q.slice(Z));if(K){V.push({t:\"num\",v:Number(K[0])}),Z+=K[0].length;continue}let H=/^[\\p{L}_][\\p{L}\\p{N}_]*/u.exec(Q.slice(Z));if(H){V.push({t:\"id\",v:H[0]}),Z+=H[0].length;continue}let Y=Q.slice(Z,Z+2);if([\":=\",\"==\",\"!=\",\"<=\",\">=\",\"&&\",\"||\"].includes(Y)){V.push({t:\"op\",v:Y}),Z+=2;continue}if(\"()[]{},:=+-*/%<>!?.\".includes(q)){V.push({t:\"op\",v:q}),Z++;continue}$(`unexpected character ${q}`)}return V}function JV(Q,V=0){let Z=V,$=(W)=>Q[Z]&&Q[Z].t===\"op\"&&Q[Z].v===W,q=(W)=>{if(!$(W))throw new M(`\"${W}\" expected`);Z++};function K(){let W=Q[Z];if(!W)throw new M(\"value expected at the end of the line\");if(Z++,W.t===\"num\")return{k:\"lit\",v:W.v};if(W.t===\"str\")return{k:\"lit\",v:W.v};if(W.t===\"path\")return{k:\"path\",abs:W.abs,segs:W.segs};if(W.t===\"id\"){if(W.v===\"true\"||W.v===\"false\")return{k:\"lit\",v:W.v===\"true\"};if(W.v===\"null\")return{k:\"lit\",v:null};if(!$(\"(\"))return{k:\"ref\",name:W.v};Z++;let z=[],G={};while(!$(\")\")){if(Q[Z]?.t===\"id\"&&Q[Z+1]?.t===\"op\"&&Q[Z+1].v===\"=\"){let j=Q[Z].v;Z+=2,G[j]=J()}else z.push(J());if(!$(\")\"))q(\",\")}return Z++,{k:\"call\",name:W.v,args:z,named:G}}if(W.t===\"op\"&&W.v===\"(\"){let z=J();return q(\")\"),z}if(W.t===\"op\"&&W.v===\"[\"){let z=[];while(!$(\"]\"))if(z.push(J()),!$(\"]\"))q(\",\");return Z++,{k:\"list\",items:z}}if(W.t===\"op\"&&W.v===\"{\"){let z=[];while(!$(\"}\")){let G=Q[Z++];if(!G||G.t!==\"id\"&&G.t!==\"str\")throw new M(\"field name expected in { }\");if(q(\":\"),z.push([G.v,J()]),!$(\"}\"))q(\",\")}return Z++,{k:\"map\",entries:z}}if(W.t===\"op\"&&(W.v===\"-\"||W.v===\"!\"))return{k:\"un\",op:W.v,arg:H()};throw new M(`unexpected \"${W.v}\"`)}function H(){let W=K();for(;;){let z=Q[Z+1];if($(\".\")){Z++;let G=Q[Z++];if(!G||G.t!==\"id\")throw new M(\"field name expected after .\");W={k:\"field\",of:W,name:G.v}}else if($(\"[\")){Z++;let G=J();q(\"]\"),W={k:\"index\",of:W,at:G}}else if($(\"/\")&&z?.t===\"id\"&&![\"true\",\"false\",\"null\"].includes(z.v)&&!(Q[Z+2]?.t===\"op\"&&Q[Z+2].v===\"(\"))W={k:\"field\",of:W,name:z.v},Z+=2;else return W}}function Y(W){let z=H();while(Q[Z]?.t===\"op\"&&kQ[Q[Z].v]>=W){let G=Q[Z++].v;z={k:\"bin\",op:G,left:z,right:Y(kQ[G]+1)}}return z}function J(){let W=Y(1);if(!$(\"?\"))return W;Z++;let z=J();return q(\":\"),{k:\"if\",test:W,yes:z,no:J()}}return{tree:J(),used:Z-V}}function UQ(Q){let V=qV(Q),{tree:Z,used:$}=JV(V);if($!==V.length)throw new M(`unexpected \"${V[$].v??V[$].t}\" after the expression`);return Z}function KV(Q){let V=Q.trim();if(!V||V.startsWith(\"#\"))return null;let Z=(q,K,H)=>({kind:q,key:K,tree:UQ(H),raw:V}),$;if($=/^delete\\s+check\\s*\\(\\s*\"(.*)\"\\s*\\)$/.exec(V))return{kind:\"delete\",key:\"check:\"+$[1],tree:null,raw:V};if($=/^delete\\s+(?:need\\s+)?(\\S+)$/.exec(V))return{kind:\"delete\",key:$[1],tree:null,raw:V};if($=/^need\\s+([\\p{L}_][\\p{L}\\p{N}_]*)\\s*=(?!=)(.*)$/u.exec(V))return Z(\"need\",$[1],$[2]);if($=/^(\\$\\/[\\p{L}\\p{N}_/]+)\\s*:=(.*)$/u.exec(V))return Z(\"formula\",$[1],$[2]);if($=/^(\\$\\/[\\p{L}\\p{N}_/]+)\\s*=(?!=)(.*)$/u.exec(V))return Z(\"state\",$[1],$[2]);if(/^app\\s*\\(/.test(V))return Z(\"app\",\"app\",V);if(/^check\\s*\\(/.test(V)){let q=UQ(V);return{kind:\"check\",key:\"check:\"+(q.args[0]?.v??\"\"),tree:q,raw:V}}if($=/^([\\p{L}_][\\p{L}\\p{N}_]*)\\s*=(?!=)(.*)$/u.exec(V))return Z(\"component\",$[1],$[2]);throw new M(\"line not understood\")}class jQ{constructor(){this.entries=new Map,this.faults=[],this.partFaults=[],this.tops=new Map,this.removed=new Set}beginStep(){this.faults=[]}feed(Q,V=null,Z=\"author\"){let $;try{$=KV(Q)}catch(K){if(!(K instanceof M))throw K;return this.faults.push({line:V,text:Q.trim(),message:K.message}),null}if(!$)return null;if($.kind===\"delete\"){let K=[...this.entries.keys()].find((H)=>H===$.key||H===\"need:\"+$.key);if(K)this.entries.delete(K),this.removed.add(K);else this.faults.push({line:V,text:$.raw,message:`nothing named ${$.key}`});return $}let q=$.kind===\"need\"?\"need:\"+$.key:$.key;if($.origin=Z,Z===\"author\")this.removed.delete(q);return this.entries.set(q,$),$}feedText(Q){return Q.split(`\n`).forEach((V,Z)=>this.feed(V,Z+1)),this}open(Q){return!this.removed.has(Q)&&(this.entries.get(Q)?.origin??\"part\")===\"part\"}of(Q){return[...this.entries.values()].filter((V)=>V.kind===Q)}get(Q){return this.entries.get(Q)}text(){return[...this.entries.values()].map((Q)=>Q.raw).join(`\n`)+`\n`}}function g(Q){switch(Q.k){case\"lit\":return typeof Q.v===\"string\"?JSON.stringify(Q.v):String(Q.v);case\"path\":return\"$\"+(Q.abs?\"/\":\"\")+Q.segs.join(\"/\");case\"ref\":return Q.name;case\"list\":return\"[\"+Q.items.map(g).join(\", \")+\"]\";case\"map\":return\"{\"+Q.entries.map(([V,Z])=>`${/^[\\p{L}_][\\p{L}\\p{N}_]*$/u.test(V)?V:JSON.stringify(V)}: ${g(Z)}`).join(\", \")+\"}\";case\"field\":return g(Q.of)+\".\"+Q.name;case\"index\":return`${g(Q.of)}[${g(Q.at)}]`;case\"un\":return Q.op+g(Q.arg);case\"bin\":return`(${g(Q.left)} ${Q.op} ${g(Q.right)})`;case\"if\":return`(${g(Q.test)} ? ${g(Q.yes)} : ${g(Q.no)})`;case\"call\":return`${Q.name}(${[...Q.args.map(g),...Object.entries(Q.named).map(([V,Z])=>`${V}=${g(Z)}`)].join(\", \")})`}throw new M(\"cannot print \"+Q.k)}var WQ={Column:[\"children\"],Row:[\"children\"],Card:[\"children\"],Section:[\"title\",\"children\"],Text:[\"text\"],Heading:[\"text\"],Metric:[\"label\",\"value\"],Emoji:[\"value\"],Badge:[\"value\"],Progress:[\"value\",\"max\",\"label\"],Ring:[\"value\",\"max\",\"text\",\"label\"],Divider:[],Notice:[\"text\"],Button:[\"label\",\"action\"],IconButton:[\"icon\",\"action\"],TextField:[\"label\",\"bind\"],NumberField:[\"label\",\"bind\"],Slider:[\"label\",\"bind\"],Toggle:[\"label\",\"bind\"],CheckBox:[\"label\",\"bind\"],Segmented:[\"label\",\"bind\",\"options\"],Select:[\"label\",\"bind\",\"options\"],List:[\"items\",\"template\",\"group\"],Chart:[\"items\",\"point\",\"value\",\"label\"],Tabs:[\"titles\",\"groups\",\"label\"],Dialog:[\"title\",\"children\"],DateField:[\"label\",\"bind\"],TimeField:[\"label\",\"bind\"],Picture:[\"text\",\"found\"],Choices:[\"label\",\"bind\",\"options\"],Timer:[\"label\",\"seconds\"],Drawing:[\"label\",\"width\",\"height\",\"shapes\"],Model3d:[\"label\",\"bodies\",\"bind\",\"exploded\",\"selected\"],Link:[\"label\",\"url\",\"text\"]};var zV=new Set([\"visible\",\"enabled\",\"variant\",\"tone\",\"emphasis\",\"weight\",\"min\",\"max\",\"step\",\"empty\",\"placeholder\",\"gap\",\"selected\",\"kind\"]),HV=new Set([\"set\",\"toggle\",\"inc\",\"dec\",\"append\",\"remove\",\"clear\",\"when\",\"move\",\"flip\",\"animate\"]),GV={circle:[\"x\",\"y\",\"r\"],rect:[\"x\",\"y\",\"w\",\"h\"],line:[\"x1\",\"y1\",\"x2\",\"y2\"],text:[\"x\",\"y\",\"text\"],polygon:[\"points\"]},YV={box:[\"part\",\"x\",\"y\",\"z\",\"w\",\"h\",\"d\"],cylinder:[\"part\",\"x\",\"y\",\"z\",\"radius\",\"length\",\"axis\"]},UV=new Set([\"text\",\"part\",\"axis\"]),WV=[\"radius\",\"width\",\"size\",\"stroke\"],FV={text:\"size\",line:\"width\",rect:\"radius\"},NQ={ink:\"#0f172a\",muted:\"#94a3b8\",accent:null,surface:\"#ffffff\",water:\"#38bdf8\",land:\"#65a30d\",sun:\"#facc15\",sand:\"#e7d3a8\",wood:\"#a16207\",success:\"#16a34a\",warning:\"#ea580c\"},qQ=(Q,V=0)=>{let Z=10**V;return Math.round((Q+Number.EPSILON)*Z)/Z},JQ=(Q)=>Array.isArray(Q)?Q.map(JQ):Q&&typeof Q===\"object\"?Object.fromEntries(Object.entries(Q).map(([V,Z])=>[V,JQ(Z)])):Q;function PV(Q){let V=[\"x\",\"y\",\"z\"].map((Z)=>Q.reduce(($,q)=>$+q[Z],0)/(Q.length||1));for(let Z of Q)if(Z.part)[\"x\",\"y\",\"z\"].forEach(($,q)=>{Z[$]=qQ(Z[$]+(Z[$]-V[q])/2,2)})}var LQ={round:qQ,floor:Math.floor,ceil:Math.ceil,abs:Math.abs,sin:(Q)=>qQ(Math.sin(Q*Math.PI/180),12)+0,cos:(Q)=>qQ(Math.cos(Q*Math.PI/180),12)+0,pow:Math.pow,sqrt:Math.sqrt,exp:Math.exp,ln:Math.log,log10:Math.log10,min:(...Q)=>Q.flat(3).length?Math.min(...Q.flat(3)):0,max:(...Q)=>Q.flat(3).length?Math.max(...Q.flat(3)):0,clamp:(Q,V,Z)=>Math.min(Z,Math.max(V,Q)),len:(Q)=>Q==null?0:Q.length,text:(Q)=>String(Q??\"\"),number:(Q)=>Number(Q)||0,fixed:(Q,V=2)=>Number(Q).toFixed(V),pad2:(Q)=>String(Math.floor(Q)).padStart(2,\"0\"),money:(Q,V=\"\")=>V+Number(Q).toFixed(2),percent:(Q,V=0)=>Number(Q).toFixed(V)+\"%\",join:(Q,V=\", \")=>(Q||[]).join(V),upper:(Q)=>String(Q).toUpperCase(),lower:(Q)=>String(Q).toLowerCase(),at:(Q,V)=>Array.isArray(Q)?Q[V]??null:null,streak:(Q)=>(Q??[]).reduce(([V,Z],$)=>$?[Math.max(V,Z+1),Z+1]:[V,0],[0,0])[0],near:(Q,V)=>Math.abs(Q-V)<=Math.max(0.01,Math.abs(V)/1000),trim:(Q)=>String(Q).trim(),contains:(Q,V)=>String(Q).toLowerCase().includes(String(V).toLowerCase()),has:(Q,V)=>Array.isArray(Q)&&Q.includes(V),hours:(Q,V)=>{let[Z,$]=[Q,V].map(XV);return Z===null||$===null?0:($-Z+1440)%1440/60},today:()=>{let Q=new Date;return`${Q.getFullYear()}-${String(Q.getMonth()+1).padStart(2,\"0\")}-${String(Q.getDate()).padStart(2,\"0\")}`},split:(Q,V=\",\")=>String(Q??\"\").split(String(V)||\",\").map((Z)=>Z.trim()).filter(Boolean),weekday:(Q)=>{let V=p(Q);return V===null?0:(new Date(V*86400000).getUTCDay()+6)%7+1},month:(Q)=>p(Q)===null?0:Number(String(Q).trim().slice(5,7)),year:(Q)=>p(Q)===null?0:Number(String(Q).trim().slice(0,4)),days:(Q,V)=>{let[Z,$]=[p(Q),p(V)];return Z===null||$===null?0:$-Z},addDays:(Q,V)=>{let Z=p(Q);return Z===null?null:new Date((Z+Math.round(Number(V)||0))*86400000).toISOString().slice(0,10)}};function p(Q){let V=/^(\\d{4})-(\\d{2})-(\\d{2})$/.exec(String(Q??\"\").trim());if(!V)return null;let Z=Date.UTC(Number(V[1]),Number(V[2])-1,Number(V[3])),$=new Date(Z);return $.getUTCMonth()===Number(V[2])-1&&$.getUTCDate()===Number(V[3])?Z/86400000:null}function XV(Q){let V=/^(\\d{1,2}):(\\d{2})$/.exec(String(Q??\"\").trim());return V?Number(V[1])*60+Number(V[2]):null}var xQ=(Q,V)=>Array.isArray(Q)&&Array.isArray(V)?Q.reduce((Z,$,q)=>Z||xQ($,V[q]),0):Q<V?-1:Q>V?1:0,MQ={count:(Q,V)=>V?Q.filter(V).length:Q.length,sum:(Q,V)=>Q.reduce((Z,$)=>Z+Number(V?V($):$),0),avg:(Q,V)=>Q.length?Q.reduce((Z,$)=>Z+Number(V?V($):$),0)/Q.length:0,filter:(Q,V)=>Q.filter(V),any:(Q,V)=>Q.some(V),all:(Q,V)=>Q.every(V),map:(Q,V)=>Q.map(V),find:(Q,V)=>Q.find(V)??null,sort:(Q,V,Z)=>[...Q].sort(($,q)=>xQ(V($),V(q))*(Z===\"desc\"?-1:1)),groups:(Q,V,Z)=>{if(!V)throw new M(\"groups needs a key, such as groups($/rows, $category, $amount)\");let $=new Map;for(let q of Q)$.set(V(q),($.get(V(q))??0)+Number(Z?Z(q):1));return[...$].map(([q,K])=>({key:q,value:K}))}},oV=new Set([...Object.keys(LQ),...Object.keys(MQ),\"if\"]);function _Q(Q,V){if(Q===V)return!0;if(typeof Q!==\"object\"||typeof V!==\"object\"||!Q||!V||Array.isArray(Q)!==Array.isArray(V))return!1;let Z=($)=>Object.keys($).filter((q)=>q!==\"id\");return Z(Q).length===Z(V).length&&Z(Q).every(($)=>_Q(Q[$],V[$]))}var CV=(Q)=>Q.rest&&Q.rest.v!==0||Q.rounds&&Q.rounds.v!==1,TQ=(Q)=>Q.named.alert?.v===!0&&!CV(Q.named),BV=(Q)=>Q.of(\"need\").filter((V)=>V.tree.name===\"entries\"&&V.tree.named?.show?.k===\"map\").flatMap((V)=>V.tree.named.show.entries),IQ=(Q)=>({...Object.fromEntries((WQ[Q.name]??[]).map((V,Z)=>[V,Q.args[Z]])),...Q.named});class RQ{constructor(Q,V=!0){if(this.file=Q,this.faults=[],this.nextId=1,this.state={},this.seen=new Map,V)this.follow()}follow(){for(let Q of this.file.of(\"state\")){if(this.seen.get(Q.key)===Q.raw)continue;this.seen.set(Q.key,Q.raw),this.write(this.pathOf(Q.key),this.attempt(Q,()=>JQ(this.value(Q.tree,{}))),{})}this.tagRows(this.state),this.recompute()}fault(Q,V){this.faults.push({where:Q,message:V})}attempt(Q,V){try{return V()}catch(Z){return this.fault(Q.key,Z.message),null}}pathOf(Q){return{k:\"path\",abs:!0,segs:Q.slice(2).split(\"/\")}}tagRows(Q){if(Array.isArray(Q))Q.forEach((V)=>{if(V&&typeof V===\"object\"&&!Array.isArray(V))V.id??=\"r\"+this.nextId++,this.tagRows(V)});else if(Q&&typeof Q===\"object\")Object.values(Q).forEach((V)=>this.tagRows(V))}read(Q,V){let Z=Q.abs?this.state:V.it;for(let $ of Q.segs)Z=Z==null?void 0:Z[$];return Z}place(Q,V){if(Q.k===\"path\"){let $=Q.abs?this.state:V.it;if($==null)throw new M(\"a $field path needs a row\");if(typeof $!==\"object\")throw new M(\"the row is a plain value with no fields to set\");for(let q of Q.segs.slice(0,-1))$=$[q]??={};return[$,Q.segs.at(-1)]}if(Q.k!==\"field\"&&Q.k!==\"index\")throw new M(\"a place to set is a path, a field of a row, or an item of a list\");let Z=this.value(Q.of,V);if(Z==null||typeof Z!==\"object\")throw new M(`${g(Q.of)} has no value, so nothing in it can be set`);return[Z,Q.k===\"field\"?Q.name:this.value(Q.at,V)]}write(Q,V,Z){let[$,q]=this.place(Q,Z);$[q]=V}value(Q,V){switch(Q.k){case\"lit\":return Q.v;case\"path\":if(!Q.abs&&Q.segs[0]===\"it\")return Q.segs.slice(1).reduce((Z,$)=>Z?.[$],V.it);if(!Q.abs&&V.each!==void 0&&Q.segs.join()===\"index\"&&!(V.it&&typeof V.it===\"object\"&&(\"index\"in V.it)))return V.each;if(!Q.abs&&V.it===void 0)throw new M(`$${Q.segs.join(\"/\")} is a field of a list row, and there is no row here. A value of the app is $/${Q.segs.join(\"/\")}`);if(!Q.abs&&V.it!==void 0&&(V.it===null||typeof V.it!==\"object\"))throw new M(\"the row is a plain value with no fields. $it reads the row itself\");if(!Q.abs&&V.it&&!(Q.segs[0]in V.it)){let Z=Q.segs[0];if(this.shown??=Object.fromEntries(BV(this.file)),Q.segs.length===1&&this.shown[Z]&&!V.busy?.includes(Z))return this.value(this.shown[Z],{...V,busy:[...V.busy??[],Z]});throw new M(`the row has no field ${Z}`)}return this.read(Q,V);case\"list\":return Q.items.map((Z)=>this.value(Z,V));case\"map\":return Object.fromEntries(Q.entries.map(([Z,$])=>[Z,this.value($,V)]));case\"field\":return this.value(Q.of,V)?.[Q.name];case\"index\":{let Z=this.value(Q.of,V);return Array.isArray(Z)?Z[this.value(Q.at,V)]??null:null}case\"un\":return Q.op===\"-\"?-this.value(Q.arg,V):!this.value(Q.arg,V);case\"if\":return this.value(Q.test,V)?this.value(Q.yes,V):this.value(Q.no,V);case\"bin\":{if(Q.op===\"&&\")return this.value(Q.left,V)&&this.value(Q.right,V);if(Q.op===\"||\")return this.value(Q.left,V)||this.value(Q.right,V);let Z=this.value(Q.left,V),$=this.value(Q.right,V);switch(Q.op){case\"+\":return Array.isArray(Z)&&Array.isArray($)?[...Z,...$]:typeof Z===\"string\"||typeof $===\"string\"?String(Z??\"\")+String($??\"\"):Z+$;case\"-\":return Z-$;case\"*\":return Z*$;case\"/\":return Z/$;case\"%\":return Z%$;case\"==\":return _Q(Z,$);case\"!=\":return!_Q(Z,$);case\"<\":return Z<$;case\"<=\":return Z<=$;case\">\":return Z>$;case\">=\":return Z>=$}break}case\"call\":{if(Q.name===\"if\")return this.value(Q.args[0],V)?this.value(Q.args[1],V):this.value(Q.args[2],V);if(MQ[Q.name]){let Z=this.value(Q.args[0],V);if(!Array.isArray(Z))throw new M(`${Q.name} needs a list`);let $=(q)=>q&&((K)=>this.value(q,{...V,it:K}));return MQ[Q.name](Z,$(Q.args[1]),Q.name===\"groups\"?$(Q.args[2]):Q.args[2]&&this.value(Q.args[2],V))}if(LQ[Q.name])return LQ[Q.name](...Q.args.map((Z)=>this.value(Z,V)));if(WQ[Q.name])throw new M(`${Q.name} is a component. A component line has a plain name with no $/ before it`);throw new M(`unknown function ${Q.name}`)}case\"ref\":throw new M(`${Q.name} is a component name, not a value`)}throw new M(\"expression not understood\")}drawn(Q,V,Z,$){if(Z.k===\"list\")return Z.items.flatMap((G)=>this.drawn(Q,V,G,$));let{name:q=\"\",args:K=[],named:H={}}=Z.k===\"call\"?Z:{};if(q===\"each\"&&K.length===2){let G=this.value(K[0],$),j=Array.isArray(G)?G:Number.isInteger(G)&&G>=0&&G<=500?Array.from({length:G},(T,P)=>P):null;if(!j)throw new M(\"each needs a list or a count: each($/rows, shape) or each(5, shape)\");return j.flatMap((T,P)=>this.drawn(Q,V,K[1],{...$,it:T,each:P}))}let Y=Object.hasOwn(Q,q)?Q[q]:null;if(!Y)throw new M(`${g(Z).slice(0,30)} is not one of ${Object.keys(Q).join(\", \")}, each`);let J=FV[q],B=`${q} takes ${Y.join(\", \")}, color${J?`, ${J}`:\"\"}`;if(K.length<Y.length||K.length>Y.length+(J?2:1))throw new M(`${g(Z).slice(0,60)} has ${K.length} values: ${B}`);if(H.visible&&!this.value(H.visible,$))return[];let W=(G,j)=>{if(G===null||G===\"\"||typeof G===\"boolean\"||!Number.isFinite(Number(G)))throw new M(`${j} of ${q} is not a number: ${B}`);return qQ(Number(G),2)},z={[V]:q};if(Y.forEach((G,j)=>{let T=this.value(K[j],$);z[G]=UV.has(G)?String(T??\"\"):G===\"points\"?[T].flat().map((P)=>W(P,G)):W(T,G)}),z.axis!==void 0&&![\"x\",\"y\",\"z\"].includes(z.axis))throw new M(`axis of ${q} is \"x\", \"y\", or \"z\"`);if(z.color=String((K[Y.length]?this.value(K[Y.length],$):null)??\"ink\"),K.length===Y.length+2)z[J]=W(this.value(K[Y.length+1],$),J);for(let[G,j]of Object.entries(H)){if(G===\"visible\")continue;if(!WV.includes(G))throw new M(`${q} has no argument ${G}`);let T=this.value(j,$);if(G!==\"stroke\")z[G]=W(T,G);else if(T)z.stroke=!0}return[z]}recompute(){let Q=this.file.of(\"formula\");for(let V=0;V<5;V++){let Z=JSON.stringify(this.state);for(let $ of Q)this.attempt($,()=>this.write(this.pathOf($.key),this.value($.tree,{}),{}));if(JSON.stringify(this.state)===Z)return}}act(Q,V={}){for(let Z of Q.k===\"list\"?Q.items:[Q]){if(Z.k!==\"call\"||!HV.has(Z.name))throw new M(`unknown action ${Z.name??Z.k}`);let[$,q]=Z.args;if(Z!==(Q.k===\"list\"?Q.items[0]:Q))this.recompute();let K=()=>{if(![\"path\",\"field\",\"index\"].includes($?.k))throw new M(`${Z.name} needs a place: a path, a field of a row, or an item of a list`);return $},H=(Y)=>{let J=this.value(Y,V);if(!Array.isArray(J))throw new M(`${Z.name} needs a list`);return J};switch(Z.name){case\"set\":this.write(K(),JQ(this.value(q,V)),V);break;case\"toggle\":this.write(K(),!this.value(K(),V),V);break;case\"inc\":this.write(K(),(this.value(K(),V)||0)+(q?this.value(q,V):1),V);break;case\"dec\":this.write(K(),(this.value(K(),V)||0)-(q?this.value(q,V):1),V);break;case\"clear\":this.write(K(),[],V);break;case\"flip\":{let Y=H(K()),J=this.value(q,V);this.write(K(),Y.includes(J)?Y.filter((B)=>B!==J):[...Y,J],V);break}case\"move\":{let Y=Z.args.length===1;if(Y&&!V.list)throw new M(\"move(by) needs a row. Outside a list write move($/list, test, by)\");let J=Y?this.read(V.list,{}):H(K()),B=Y?J.indexOf(V.it):J.findIndex((z)=>this.value(q,{...V,it:z})),W=B+this.value(Y?$:Z.args[2],V);if(B>=0&&W>=0&&W<J.length)J.splice(W,0,J.splice(B,1)[0]);break}case\"when\":if(this.value($,V))this.act(q,V);else this.recompute();break;case\"animate\":{if($?.k!==\"path\"||!$.abs)throw new M(\"animate needs a value of the app as its place, such as $/angle\");let Y=$.segs.join(\"/\"),J=Number(this.value($,V))||0,B=q?Number(this.value(q,V)):NaN,W=Z.args[2]?Number(this.value(Z.args[2],V)):0;if(!Number.isFinite(B)||!Number.isFinite(W))throw new M(\"animate needs a number to go to and a time in milliseconds: animate($/angle, 360, 5000)\");let z=(this.state._run??[]).filter((j)=>j.path!==Y),G=QQ.now();if(W>0&&B!==J)z.push({path:Y,from:J,to:B,start:G,end:G+W,at:J});else this.write($,B,V);if(z.length)this.state._run=z;else delete this.state._run;break}case\"append\":{let Y=JQ(this.value(q,V));H(K()).push(Y),this.tagRows([Y]);break}case\"remove\":{if(!$){if(!V.list)throw new M(\"remove() without a path needs a row\");this.write(V.list,this.read(V.list,{}).filter((Y)=>Y!==V.it),{})}else this.write(K(),H(K()).filter((Y)=>!this.value(q,{...V,it:Y})),V);break}}}this.recompute()}tree(Q=!1){this.reached=new Set;let V=0,Z=(q,K,H,Y)=>{if(Y>40)throw new M(\"components nest too deep or refer to each other\");if(q.k===\"ref\"){let z=this.file.get(q.name);if(this.reached.add(q.name),!z||z.kind!==\"component\"){if(Q)this.fault(q.name,\"no component line with this name\");return{type:\"Missing\",name:q.name,props:{},children:[]}}return Z(z.tree,K,q.name,Y+1)}if(q.k!==\"call\"||!WQ[q.name])return this.fault(H??\"?\",q.k===\"path\"?`a child is a component name, not the path ${g(q)}`:`unknown component ${q.name??q.k}`),{type:\"Missing\",name:H,props:{},children:[]};let J={type:q.name,name:H,props:{},children:[],row:K.it,rowIndex:K.index,list:K.list,call:q},B=IQ(q),W=[];for(let[z,G]of Object.entries(B)){if(G===void 0)continue;if(!WQ[q.name].includes(z)&&!zV.has(z)&&!(z===\"action\"&&B.bind)){this.fault(H??q.name,`${q.name} has no property ${z}`);continue}if(q.name===\"Chart\"&&(z===\"point\"||z===\"value\")||q.name===\"List\"&&z===\"group\"||z===\"shapes\"||z===\"bodies\")continue;try{if(z===\"children\")J.children=(G.k===\"list\"?G.items:[G]).map((j)=>Z(j,K,void 0,Y+1));else if(z===\"groups\")W=G.k===\"list\"?G.items:[G];else if(z===\"action\")J.props.action=G;else if(z===\"bind\"){if(![\"path\",\"field\",\"index\"].includes(G.k))throw new M(\"bind needs a place: a path, a field of a row, or an item of a list\");J.props.bind=G,J.props.value=G.k===\"path\"?this.read(G,K):this.value(G,K)}else if(z===\"template\")J.template=G;else J.props[z]=this.value(G,K)}catch(j){this.fault(H??q.name,`${z}: ${j.message}`)}}if(q.name===\"Chart\"){try{J.props.points=(Array.isArray(J.props.items)?J.props.items:[]).map((z)=>({label:B.point?this.value(B.point,{...K,it:z}):\"\",value:Number(B.value?this.value(B.value,{...K,it:z}):z)||0}))}catch(z){this.fault(H??q.name,`points: ${z.message}`)}delete J.props.items}if(q.name===\"Drawing\"||q.name===\"Model3d\"){let z=q.name===\"Drawing\"?\"shapes\":\"bodies\",G=B[z]??{k:\"list\",items:[]};if(J.props[z]=(G.k===\"list\"?G.items:[G]).flatMap((j)=>{try{return this.drawn(z===\"shapes\"?GV:YV,z===\"shapes\"?\"shape\":\"body\",j,K)}catch(T){return this.fault(H??q.name,`${z}: ${T.message}`),[]}}),z===\"bodies\"&&J.props.exploded)PV(J.props.bodies);if(z===\"shapes\"&&!(J.props.width>0&&J.props.height>0))this.fault(H??q.name,\"a drawing needs a width and a height above 0: drawing(label, 320, 240, [shapes])\")}if(q.name===\"Link\"&&!/^https?:\\/\\/\\S+$/.test(String(J.props.url??\"\")))this.fault(H??q.name,\"a Link needs a web address that starts with https://\");if(q.name===\"Dialog\"&&!B.visible)this.fault(H??q.name,\"a Dialog needs visible=test and a button that makes the test false. Without them the window never closes\");if(q.name===\"Choices\"){let z=Array.isArray(J.props.value)?J.props.value:[],G=J.props.max!==void 0&&z.length>=J.props.max;J.children=(J.props.options??[]).map((j,T)=>({type:\"Button\",name:H&&`${H}_opt${T}`,children:[],props:{label:String(j),selected:z.includes(j),enabled:z.includes(j)||!G,action:{k:\"call\",name:\"flip\",args:[B.bind,{k:\"lit\",v:j}],named:{}}}}))}if(q.name===\"Tabs\"){let z={k:\"path\",abs:!0,segs:[\"_ui\",H??\"tabs\"+V++]},G=this.read(z,{})??0;J.props.value=G,J.children=[{type:\"Row\",props:{},children:(J.props.titles??[]).map((j,T)=>({type:\"Button\",name:H&&`${H}_tab${T}`,tab:!0,children:[],props:{label:j,selected:T===G,action:{k:\"call\",name:\"set\",args:[z,{k:\"lit\",v:T}],named:{}}}}))},...W.map((j,T)=>({type:\"Column\",props:{visible:T===G},children:(j.k===\"list\"?j.items:[j]).map((P)=>Z(P,K,void 0,Y+1))}))]}if(q.name===\"List\"&&J.template&&Array.isArray(J.props.items)){let z=(P,X=0)=>{if(!P||X>8)return null;if(P.k===\"call\"&&[\"sort\",\"filter\"].includes(P.name))return z(P.args[0],X+1);if(P.k===\"if\")return z(P.yes,X+1)??z(P.no,X+1);if(P.k!==\"path\")return null;let _=P.abs?this.file.get(\"$/\"+P.segs.join(\"/\")):null;return _?.kind===\"formula\"?z(_.tree,X+1):P},G=z(B.items),j=J.props.items,T=[];if(B.group)try{let P=j.map((_)=>this.value(B.group,{...K,it:_})),X=[...new Set(P)].flatMap((_)=>j.map((E,m)=>[E,_,m]).filter((E)=>P[E[2]]===_));j=X.map((_)=>_[0]),T=X.map((_,E)=>E===0||X[E-1][1]!==_[1]?[{type:\"Heading\",props:{text:String(_[1]??\"\")},children:[]}]:[])}catch(P){this.fault(H??q.name,`group: ${P.message}`)}J.children=j.flatMap((P,X)=>[...T[X]??[],Z(J.template,{it:P,index:X,list:G},void 0,Y+1)]),J.source=G,J.blank=(P)=>Z(J.template,{it:P,index:0,list:G},void 0,Y+1)}return J},$=this.file.get(\"root\");if(!$)return{type:\"Missing\",name:\"root\",props:{},children:[]};return Z($.tree,{},\"root\",0)}find(Q,V){let Z=($)=>$.name===Q&&(V===void 0||$.rowIndex===V)?$:$.children.reduce((q,K)=>q??Z(K),null);return Z(this.tree())}tick(){let Q=QQ.now(),V=this.state;if(V._run){if(V._run=V._run.filter((Z)=>{let $=this.pathOf(\"$/\"+Z.path);if(this.read($,{})!==Z.at)return!1;return Z.at=Q>=Z.end?Z.to:Z.from+(Z.to-Z.from)*Math.max(0,Q-Z.start)/(Z.end-Z.start),this.write($,Z.at,{}),Q<Z.end}),!V._run.length)delete V._run;this.recompute()}for(let Z of this.file.of(\"need\")){if(Z.tree.name!==\"timer\"||TQ(Z.tree))continue;let $=Z.key,q=(H,Y)=>{try{return Number(H?this.value(H,{}):Y)||0}catch{return 0}},K=()=>Number(V[$+\"_phase\"])||0;if(!V[$+\"_on\"]){V[$+\"_end\"]=0;continue}if(!V[$+\"_end\"])V[$+\"_end\"]=Q+Math.max(0,K()-V[$+\"_spent\"])*1000;for(let H=0;V[$+\"_on\"]&&Q>=V[$+\"_end\"]&&H<1000;H++){let Y=V[$+\"_end\"];if(!V[$+\"_resting\"])V[$]+=1;if(!V[$+\"_resting\"]&&q(Z.tree.named.rest,0)>0)V[$+\"_resting\"]=!0,V[$+\"_spent\"]=0;else if(V[$+\"_round\"]>=q(Z.tree.named.rounds,1))V[$+\"_over\"]=!0,V[$+\"_on\"]=!1,V[$+\"_spent\"]=K(),V[$+\"_end\"]=0;else V[$+\"_round\"]+=1,V[$+\"_resting\"]=!1,V[$+\"_spent\"]=0;if(this.recompute(),V[$+\"_on\"])V[$+\"_end\"]=Y+K()*1000}if(V[$+\"_on\"])V[$+\"_spent\"]=Math.max(0,K()-(V[$+\"_end\"]-Q)/1000);this.recompute()}}change(Q,V){this.tick();try{if(this.write(Q.props.bind,V,{it:Q.row}),Q.props.action)this.act(Q.props.action,{it:Q.row,list:Q.list});else this.recompute()}catch(Z){return`${Q.name??Q.type}: ${Z.message}`}return this.tick(),\"\"}fire(Q){if(Q.props.enabled===!1||Q.props.visible===!1)return`${Q.name??Q.type} is not active`;this.tick();try{this.act(Q.props.action,{it:Q.row,list:Q.list})}catch(V){return`${Q.name??Q.type}: ${V.message}`}return this.tick(),\"\"}input(Q,V,Z){let $=this.find(Q,Z);if($?.props.bind)return this.change($,V);return(!$?`no field named ${Q}`:$.props.action?`${Q} is not a field: use tap(${Q})`:`${Q} only shows a value and cannot be set. A check sets a field, such as a NumberField`)+(Z===void 0?\"\":` in row ${Z}`)}press(Q,V){let Z=this.find(Q,V);if(Z?.props.action)return this.fire(Z);return(Z?.props.bind?`${Q} is a field with no action: use set(${Q}, value)`:`no control named ${Q}`)+(V===void 0?\"\":` in row ${V}`)}}var QQ={gate:\"smoke\",now:()=>Date.now()};var jV=/^[A-Za-z][A-Za-z0-9_]{0,47}$/,LV=new Set([\"__proto__\",\"prototype\",\"constructor\"]),cQ=12,SQ=100,MV={time:\"number\",date:\"string\"};function AQ(Q={},V=new Set(Object.values(Q))){let Z=new Map,$=0;return(q,K=q)=>{if(Z.has(q))return Z.get(q);let H=Object.hasOwn(Q,q)?Q[q]:K;if(!Object.hasOwn(Q,q)&&(!jV.test(H)||LV.has(H)||/^[dv]\\d*$/.test(H)||V.has(H))){while(V.has(\"f\"+$))$++;H=Q[q]=\"f\"+$}return Z.set(q,H),V.add(H),H}}var KQ=(Q)=>Q.replaceAll(\":\",\"_\"),pQ=(Q)=>String(Q).padStart(2,\"0\"),_V=(Q)=>{let V=/^(\\d{1,2}):(\\d{2})$/.exec(String(Q??\"\").trim());return V&&Number(V[1])<24&&Number(V[2])<60?Number(V[1])*60+Number(V[2]):0},FQ=(Q)=>{let V=Math.min(1439,Math.max(0,Math.round(Number(Q)||0)));return`${pQ(Math.floor(V/60))}:${pQ(V%60)}`},PQ=(Q,V,Z)=>Q===\"time\"?_V(V):Q===\"date\"?p(V)===null?Z:String(V).trim():Q===\"number\"?typeof V===\"number\"&&Number.isFinite(V)?V:0:Q===\"boolean\"?V===!0:V===null||V===void 0||typeof V===\"object\"?\"\":String(V),DQ=(Q,V,Z)=>({id:String(Q?.id??\"\"),...Object.fromEntries(V.map(([$,q,K])=>[q,PQ(K,Q?.[$],Z)]))}),r=(Q,V)=>Object.hasOwn(Q,V)?Q[V]:void 0,NV=2048,zQ=(Q,V)=>Q===\"number\"?Number.isFinite(V)?Math.min(Number.MAX_SAFE_INTEGER,Math.max(-Number.MAX_SAFE_INTEGER,V)):0:(V!==null&&typeof V===\"object\"?\"\":String(V??\"\")).slice(0,NV),XQ=(Q)=>Q?.k===\"path\"&&!Q.abs&&Q.segs.length===1?Q.segs[0]:null;function lQ(Q,V,Z,$,q,K,H){let{kit:Y,name:J,walk:B,day:W,fields:z,lists:G,views:j,viewSchemas:T,viewOwners:P,listRoots:X}=Q,_=V.source,E=_?.k===\"path\"&&_.abs&&_.segs.length===1&&G[_.segs[0]]?_.segs[0]:null;if(!E||!V.blank)return K.fault(\"a List shows the rows of a list of the app, such as $/rows, or sort or filter of it. Other rows are not built yet\"),null;let m=G[E],l=AQ();l(\"$id\",\"id\"),m.forEach(([C,A])=>l(\"$\"+C,A));let f={key:Z,name:l,stored:new Map(m.map(([C,A,D])=>[C,[A,D]])),computed:new Map,flags:{moves:!1},out:null,id:null},o={id:\"\",...Object.fromEntries(m.map(([C,,A])=>[C,A===\"number\"?0:A===\"boolean\"?!1:\"\"]))},YQ=(C)=>C.name??`${Z}:row`,i=V.blank(o),CQ=B(i,YQ(i),$,{scope:f,weighted:!1}),k=q.group,s=f.stored.get(XQ(k)),u=k?s&&s[1]!==\"time\"?s[0]:l(\"~group\",\"group\"):null;if(k&&!(s&&s[1]!==\"time\"))f.computed.set(u,\"string\");let n=(C)=>C?.k===\"path\"?C.abs&&C.segs.length===1&&C.segs[0]===E:C?.k===\"call\"&&C.name===\"filter\"&&n(C.args[0]),t=f.flags.moves;if(t&&(k||!n(q.items)))K.fault(\"the arrows of a row move it in the stored list, so the list shows its rows in their stored order: no sort and no group\");let d=t?l(\"~label\",\"label\"):null;if(d)f.computed.set(d,\"string\");let mQ=V.children.filter((C)=>C.row).map((C)=>{let A={};if(B(C,YQ(C),$,{scope:{...f,out:A,id:String(C.row.id??\"\")},weighted:!1}),f.computed.has(u))try{A[u]=zQ(\"string\",Y.value(k,{it:C.row}))}catch{A[u]=\"\"}if(d)A[d]=(m.filter(([,,D])=>D===\"string\").map(([D])=>PQ(\"string\",C.row[D]).trim()).find(Boolean)??String(C.row.id)).slice(0,300);return{...DQ(C.row,m,W),...A}}),U=!(q.items?.k===\"path\"&&n(q.items))||f.computed.size>0,S=U?J(\"view:\"+Z,KQ(Z)):z.get(E);if(U){let C={id:\"string\",...Object.fromEntries(m.map(([,D,N])=>[D,r(MV,N)??N])),...Object.fromEntries(f.computed)},A=Object.keys(C).length;if(A>cQ)K.fault(`the rows of this list have ${A} fields with the id and the values that a row shows, and a row of Alphy has at most ${cQ}. Show fewer values in a row`);j[S]=mQ,T[S]={type:\"list\",maxItems:SQ,fields:C},P.push($)}let b=t&&K.action((C)=>{let A=Y.read(_,{}),D=A.findIndex((w)=>w.id===C?.sourceKey),N=A.findIndex((w)=>w.id===C?.destinationKey);return D<0||N<0?!1:Y.fire({props:{action:{k:\"call\",name:\"move\",args:[{k:\"lit\",v:N-D}],named:{}}},row:A[D],list:_})},\"_move\"),O={type:u?\"groupedList\":\"list\",text:K.caption(H),bind:S,...u&&{groupBy:u},...V.props.empty&&{emptyText:K.plain(V.props.empty).slice(0,300)},...b&&{reorder:{action:b,labelField:d}},rowTemplate:CQ};return X.set(O,E),O}var GQ={width:320,height:64},bQ=(Q)=>Math.min(2048,Math.max(64,Math.round(Number(Q))||0)),EQ={commands:512,pairs:2048,chars:2048,bytes:32768},TV=\"#33808080\",IV={ink:\"default\",muted:\"muted\",accent:\"accent\",success:\"success\",warning:\"warning\"},RV=Object.fromEntries(Object.keys(NQ).map((Q)=>[Q,IV[Q]??NQ[Q]])),SV=/^#(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/,AV=(Q,V)=>Object.hasOwn(V,Q)?V[Q]:SV.test(Q)?Q:\"default\",DV=(Q)=>{let V=0;for(let Z of Q){let $=Z.codePointAt(0);V+=$<128?1:$<2048?2:$<65536?3:4}return V},iQ=(Q)=>DV(JSON.stringify(Q).replace(/\\//g,\"\\\\/\")),a=(Q)=>Math.round(Q*100)/100,OQ=(Q,V,Z)=>Math.min(Z,Math.max(V,Q)),HQ=(Q)=>Q.every((V)=>Number.isFinite(V)&&Math.abs(V)<=8192),sQ=(Q)=>({type:\"polyline\",points:[...Q,Q[0]],stroke:2});function OV({shape:Q,x:V,y:Z,r:$,w:q,h:K,x1:H,y1:Y,x2:J,y2:B,text:W,points:z,radius:G,width:j,size:T,stroke:P}){if(Q===\"circle\"){if(!($>=0&&$<=2048)||!HQ([V-$,Z-$,V+$,Z+$]))return null;let X=a(2*$);return P?{type:\"arc\",x:a(V-$),y:a(Z-$),width:X,height:X,start:0,sweep:360,stroke:2}:{type:\"circle\",x:V,y:Z,radius:$}}if(Q===\"rect\"){let X={x:Math.min(V,a(V+q)),y:Math.min(Z,a(Z+K)),width:Math.abs(q),height:Math.abs(K)},_=a(X.x+X.width),E=a(X.y+X.height);if(!HQ([X.x,X.y,_,E])||X.width>8192||X.height>8192)return null;if(P)return sQ([[X.x,X.y],[_,X.y],[_,E],[X.x,E]]);return G?{type:\"roundRect\",...X,radius:OQ(G,0,1024)}:{type:\"rect\",...X}}if(Q===\"line\")return HQ([H,Y,J,B])?{type:\"line\",x:H,y:Y,x2:J,y2:B,stroke:OQ(j??2,0.1,128)}:null;if(Q===\"text\")return HQ([V,Z])?{type:\"text\",x:V,y:Z,size:OQ(T??14,1,128),text:W.slice(0,EQ.chars),align:\"center\",baseline:\"middle\"}:null;if(Q===\"polygon\"){let X=[];for(let _=0;_+1<z.length;_+=2)X.push([z[_],z[_+1]]);if(!HQ(X.flat())||X.length<(P?2:3)||X.length>(P?2047:2048))return null;return P?sQ(X):{type:\"polygon\",points:X}}return null}var gQ=(Q,V=RV)=>Q.flatMap((Z)=>{let $=OV(Z);return $?[{...$,color:AV(Z.color,V)}]:[]}),dQ=(Q,V)=>[{shape:\"rect\",x:0,y:24,w:GQ.width,h:16,color:TV,radius:8},{shape:\"rect\",x:0,y:24,w:Math.round(GQ.width*Q),h:16,color:V,radius:8}],EV=(Q)=>Q.points?.length??(Q.type===\"line\"?2:1),bV=(Q)=>Q.text?.length??0;function rQ(Q){let V={commands:0,pairs:0,chars:0,bytes:iQ(Object.fromEntries(Q.map(([$])=>[$,{commands:[]}])))},Z=!1;return Object.fromEntries(Q.map(([$,q])=>[$,{commands:q.filter((K,H)=>{let Y={commands:1,pairs:EV(K),chars:bV(K),bytes:iQ(K)+(H?1:0)};if(Z||=Object.keys(EQ).some((J)=>V[J]+Y[J]>EQ[J]),!Z)for(let J of Object.keys(Y))V[J]+=Y[J];return!Z})}]))}var aQ=32,gV=512;var oQ=256,hV={vibrationMs:600,toneMs:600};var hQ=1000,nQ=86400000,vV=1000,wV=50,fV={from:0,to:0,start:0,end:0,at:0};function yV(Q){let V=Q.file.get(\"app\")?.tree,Z=($)=>$?Q.value($,{}):void 0;return{title:Z(V?.args[0]),...Object.fromEntries(Object.entries(V?.named??{}).map(([$,q])=>[$,Z(q)]))}}var QV=(Q)=>Q.value({k:\"call\",name:\"today\",args:[]},{}),fQ={eyebrow:{emphasis:\"label\",tone:\"accent\"},muted:{tone:\"muted\"}},mV={ArrowUp:\"Arrow\",ArrowDown:\"Sort\",ArrowLeft:\"Back\",ArrowRight:\"Chevron\",Edit:\"Pencil\"},uV=new Set([\"List\",\"Dialog\",\"Chart\",\"Tabs\",\"Timer\",\"Drawing\"]),kV={Picture:\"picture\",Link:\"link\"},vQ=(Q,V)=>Number.isFinite(Q)&&Number.isFinite(V)&&V>0?Math.min(100,Math.max(0,Q/V*100)):0,yQ=(Q)=>Q?.k===\"call\"?Q.name===\"append\"||Q.args.some(yQ):Q?.k===\"list\"&&Q.items.some(yQ),xV=(Q,V)=>Object.fromEntries(V.filter((Z)=>Q[Z]!==void 0).map((Z)=>[Z,Q[Z]])),tQ=(Q,V)=>{let Z=Array.isArray(Q.props.options)?Q.props.options:[];return{type:Z.length<=6?\"segmented\":\"select\",...V.field(),options:Z.map(($)=>({text:V.plain($),value:$}))}},eQ=(Q,V,Z)=>($,q)=>{let{min:K=V,max:H=Z,step:Y,value:J}=$.props;if(![K,H].every(Number.isFinite)||!(K<H)||!q.blank&&!(Number.isFinite(J)&&J>=K&&J<=H)||Y!==void 0&&!(Y>0&&Y<=H-K))q.fault(`a ${$.type} needs min below max, its value from min to max, and a step above 0 and at most max - min. Now min is ${K}, max is ${H}, the value is ${J}${Y===void 0?\"\":`, and the step is ${Y}`}`);return q.bounds(K,H),{type:Q,...q.field(),min:K,max:H,...xV($.props,[\"step\"])}},cV={Column:(Q,V)=>({type:\"column\",children:V.children(!0)}),Row:(Q,V)=>V.form()??(Q.children.some((Z)=>Z.props.weight!==void 0)?{type:\"row\",wrap:!1,children:V.children(!0)}:{type:\"flowRow\",children:V.children()}),Card:(Q,V)=>({type:\"card\",...Q.props.variant===\"hero\"&&{variant:\"hero\"},children:V.children()}),Section:(Q,V)=>({type:\"section\",text:V.plain(Q.props.title),children:V.children()}),Text:(Q,V)=>({type:\"text\",...V.text(\"text\"),...r(fQ,Q.props.variant)&&{style:fQ[Q.props.variant]}}),Heading:(Q,V)=>({type:\"text\",...V.text(\"text\"),style:{emphasis:\"title\"}}),Metric:(Q,V)=>({type:\"metric\",text:V.plain(Q.props.label),bind:V.shown(\"value\"),...Q.props.emphasis===\"display\"&&{style:{emphasis:\"display\"}}}),Badge:(Q,V)=>({type:\"badge\",...V.text(\"value\")}),Emoji:(Q,V)=>({type:\"emoji\",text:V.plain(Q.props.value),...V.value(\"value\")}),Divider:()=>({type:\"divider\"}),Notice:(Q,V)=>({type:\"banner\",children:[{type:\"text\",...V.text(\"text\")}]}),Button:(Q,V)=>({type:\"button\",text:V.caption(Q.props.label),action:V.action(),variant:Q.props.selected===void 0||Q.props.selected?\"filled\":\"tonal\"}),IconButton:(Q,V)=>({type:\"icon\",name:r(mV,Q.props.icon)??Q.props.icon,text:V.caption(),action:V.action()}),NumberField:eQ(\"numberInput\",0,1e6),Slider:eQ(\"slider\",0,100),TextField:(Q,V)=>({type:\"input\",...V.field(),inputMode:\"apply\"}),Toggle:(Q,V)=>({type:\"toggle\",...V.field()}),CheckBox:(Q,V)=>({type:\"checkbox\",...V.field()}),Segmented:tQ,Select:tQ,DateField:(Q,V)=>({type:\"dateInput\",...V.field()}),TimeField:(Q,V)=>({type:\"timeInput\",...V.field(FQ)}),Choices:(Q,V)=>({type:\"multiChoice\",...V.field((Z)=>Array.isArray(Z)&&!(Q.props.max!==void 0&&Z.length>Q.props.max)?Z:void 0),options:(Array.isArray(Q.props.options)?Q.props.options:[]).map((Z)=>({text:V.plain(Z),value:Z}))}),Dialog:(Q,V)=>V.modal(),List:(Q,V)=>V.list(),Progress:(Q,V)=>{let{value:Z,max:$}=Q.props,q=Q.props.variant===\"dots\"&&!V.scope&&Number.isInteger($)&&$>=1&&$<=12;return V.drawn(vQ(Z,$)/100)??{type:\"progress\",text:V.label(),variant:q?\"dots\":\"bar\",min:0,max:q?$:100,bind:V.computed(\"value\",q?Number.isFinite(Z)?Math.min($,Math.max(0,Z)):0:vQ(Z,$))}},Timer:(Q,V)=>{let Z=Math.round(Number(Q.props.seconds)*1000);if(!(Z>=hQ&&Z<=nQ))V.fault(`a Timer runs from 1 second to 24 hours, and this one has ${Q.props.seconds} seconds`);return{type:\"timer\",text:V.label(),mode:\"countdown\",action:V.action(()=>!1),foreground:{durationMs:Number.isFinite(Z)?Math.min(nQ,Math.max(hQ,Z)):hQ,...hV}}},Ring:(Q,V)=>({type:\"progressRing\",text:V.label(),size:\"large\",min:0,max:100,bind:V.computed(\"value\",vQ(Q.props.value,Q.props.max)),...Q.props.text!==void 0&&{children:[{type:\"text\",...V.text(\"text\")}]}}),Chart:(Q,V)=>V.chart(),Tabs:(Q,V)=>V.tabs(),Drawing:(Q,V)=>V.canvas(gQ(Q.props.shapes),bQ(Q.props.width),bQ(Q.props.height))},pV=(Q)=>Q?.k===\"path\"&&Q.abs?[Q,!1]:Q?.k===\"un\"&&Q.op===\"!\"&&Q.arg.k===\"path\"&&Q.arg.abs?[Q.arg,!0]:Q?.k===\"bin\"&&Q.op===\"!=\"&&Q.left.k===\"path\"&&Q.left.abs&&Q.right.k===\"lit\"?[Q.left,Q.right.v]:null,VV=(Q)=>Q!==null&&typeof Q===\"object\"&&(Q.k===\"path\"&&!Q.abs||Object.values(Q).some(VV));function wQ(Q,V,Z=!1){let $=AQ(V.names,new Set([...Object.values(V.names),...V.fields.flatMap(([,U])=>U.split(\".\")),...V.anim?[V.anim.field]:[]])),q=new Map(V.fields),K=V.kinds??{},H=V.lists??{},Y=QV(Q),J=(U,S)=>Z?V.types[U]??=typeof S===\"number\"?\"number\":\"string\":V.types[U]??\"string\",B=[],W=new Map,z=new Map,G=new Map,j=new Map,T=[],P={},X={},_=[],E={d:new Map,v:new Map},m={},l=new Map(Q.file.of(\"need\").filter((U)=>U.tree.name===\"timer\"&&!TQ(U.tree)).map((U)=>[`${U.key}_bar`,U.key])),f=[],o=(U,S,b,O,C)=>{if(O!==null&&typeof O===\"object\")C.fault(`it shows a ${Array.isArray(O)?\"list\":\"record\"}, and Alphy shows one text or number there. Show one field of it, or join(list)`);let A=C.scope;if(A){let w=A.stored.get(XQ(b));if(w&&w[1]!==\"time\"&&(U===\"d\"||w[1]===\"boolean\"))return w[0];let x=A.name(`${U}:${S}`,S.replace(/\\W/g,\"_\")),R=U===\"v\"?\"boolean\":J(`${A.key}/${x}`,O);if(A.computed.set(x,R),A.out)A.out[x]=U===\"v\"?Boolean(O):zQ(R,O);return x}let D=b?.k===\"path\"&&b.abs?b.segs:null;if(D?.length===1&&q.has(D[0])&&K[D[0]]!==\"time\"&&(U===\"d\"||typeof O===\"boolean\"))return q.get(D[0]);let N=$(`${U}:${S}`,D?D.join(\"_\"):S.replace(/\\W/g,\"_\"));if(!E[U].has(N)){let w=U+(E[U].size<aQ?\"\":Math.floor(E[U].size/aQ)+1);E[U].set(N,`${w}.${N}`),(m[w]??={})[N]=U===\"v\"?Boolean(O):zQ(J(N,O),O)}return E[U].get(N)},YQ=(U,S,b)=>{if(!U)return W.set(S,b);if(U.id===null)return;let O=z.get(S);if(!O)z.set(S,O=new Map),W.set(S,(C)=>O.get(C?.key)?.(C.value)??!1);O.set(U.id,b)},i=(U,S,b,O)=>{b=U.name??b;let C=O.scope,A=r(cV,U.type),D=(F)=>B.push([b,F]);if(!A)return D(`${r(kV,U.type)??U.type} is not built yet`),null;if(C&&uV.has(U.type))return D(`a ${U.type} in a row of a list is not built yet`),null;let N=U.call?IQ(U.call):{};if(C&&N.action?.k===\"call\"&&N.action.name===\"move\"&&N.action.args.length===1)return C.flags.moves=!0,null;if(C){for(let F of[\"label\",\"title\",\"icon\",\"options\"])if(VV(N[F]))D(`the ${F} of a ${U.type} in a row ${F===\"options\"?\"change\":\"changes\"} with the row, and Alphy draws one ${F} for all rows. Show the value in a Text of the row`)}let w=U.type===\"Section\"?{...O,title:U.props.title}:O,x=(F)=>N[F]&&N[F].k!==\"lit\"?o(\"d\",`${S}.${F}`,N[F],U.props[F],R):null,R={scope:C,fault:D,blank:Boolean(C&&!C.out),children:(F=!1,L=w)=>U.children.map((I,h)=>i(I,I.name??`${S}:${h}`,b,{...L,weighted:F})).filter(Boolean),caption:(F)=>R.plain(F)||(U.name?U.name:`${b} ${U.props.icon??U.type}`).replace(/_/g,\" \"),label:()=>R.caption(R.plain(U.props.label)||O.title).slice(0,300),text:(F)=>{let L=x(F);return L?{text:\"\",bind:L}:{text:R.plain(U.props[F])}},plain:(F)=>{if(F===null||typeof F!==\"object\")return String(F??\"\");return D(`it shows a ${Array.isArray(F)?\"list\":\"record\"} as a text, and Alphy shows one text there. Show one field of it, or join(list)`),\"\"},value:(F)=>{let L=x(F);return L?{bind:L}:{[F]:U.props[F]}},shown:(F)=>x(F)??R.computed(F,U.props[F]),computed:(F,L)=>o(\"d\",`${S}.${F}`,null,L,R),bounds:(F,L)=>{if(!R.blank)T.push([U,F,L])},field:(F=(L)=>L)=>{let L;if(C){let I=C.stored.get(XQ(N.bind));if(!I||I[1]===\"time\"!==(U.type===\"TimeField\")||U.type===\"DateField\"&&I[1]!==\"date\")D(`a ${U.type} in a row binds a field of the row of its own kind, such as $name. ${N.bind?g(N.bind):\"nothing\"} is not built yet`);L=I?.[0]}else{let I=N.bind?.k===\"path\"&&N.bind.abs&&N.bind.segs.length===1?N.bind.segs[0]:null;if(!q.has(I)||K[I]===\"time\"!==(U.type===\"TimeField\"))D(`a binding to ${N.bind?g(N.bind):\"nothing\"} is not built yet: a field binds a value of a state line`);L=q.get(I)}return{text:R.caption(U.props.label),bind:L,action:R.action((I)=>{let h=F(I);return h===void 0?!1:Q.change(U,h)})}},action:(F=()=>{let I=Q.fire(U);if(I===\"\"&&O.sheet?.add===U)Q.state[O.sheet.flag]=!1;return I},L=\"\")=>{let I=$(\"@\"+S+L,KQ(S+L));return YQ(C,I,F),I},form:()=>{let F=`${S}:open`;if(!q.has(F))return null;let L=U.children.find((y)=>y.type===\"Button\"&&yQ(y.props.action)),I=R.caption(L?.props.label),h=(y)=>()=>{return Q.state[F]=y,\"\"};return{type:\"column\",children:[{type:\"button\",text:I,action:R.action(h(!0),\"_open\"),variant:\"filled\",icon:\"Plus\"},{type:\"bottomSheet\",text:I,visibleBind:q.get(F),dismissAction:R.action(h(!1),\"_dismiss\"),children:R.children(!1,{...O,sheet:{flag:F,add:L}})}]}},modal:()=>{if(!N.visible||N.visible.k===\"lit\")D(\"a Dialog shows while a test of the app is true, so it needs visible=test, such as visible=$/open\");let F=pV(N.visible),L=(h)=>h.props.bind!==void 0||h.children.some(L),I=R.action(()=>{if(!F)return!1;return Q.write(F[0],F[1],{}),Q.recompute(),\"\"},\"_dismiss\");return{type:U.children.some(L)?\"bottomSheet\":\"dialog\",text:R.caption(U.props.title),dismissAction:I,children:R.children()}},list:()=>lQ(CQ,U,S,b,N,R,O.title),drawn:(F)=>{let L=!C&&l.get(U.name);return L?R.canvas(gQ(dQ(F,Q.state[L+\"_resting\"]?\"success\":\"accent\")),GQ.width,GQ.height,Boolean(Q.state[L+\"_on\"])):null},canvas:(F,L,I,h=!1)=>{let y=$(\"surface:\"+S,KQ(S));return f.push({id:y,width:L,height:I,running:h,commands:F,line:b}),{type:\"canvas\",text:R.label(),surfaces:[y]}},chart:()=>{let F=U.props.kind??\"bar\",L=R.label().slice(0,100),I=(Array.isArray(U.props.points)?U.props.points:[]).slice(-oQ);if(F!==\"bar\"&&F!==\"line\")D(`a Chart of kind ${R.plain(F)} is not built yet: give kind=\"bar\" or kind=\"line\"`);let[h,y]=[[\"labels\",\"string\",\"label\"],[\"values\",\"number\",\"value\"]].map(([VQ,c,BQ])=>{let ZQ=$(`chart:${S}:${VQ}`,KQ(`${S}_${VQ}`));return P[ZQ]=I.map((v)=>zQ(c,v[BQ])),X[ZQ]={type:\"array\",maxItems:oQ,items:c},_.push(b),ZQ});return{type:\"chart\",text:L,kind:F,categories:h,series:[{text:L,bind:y}]}},tabs:()=>{let F=`${U.name}:tab`,L=U.children.slice(1),I=(Array.isArray(U.props.titles)?U.props.titles:[]).map((v)=>R.plain(v)),h=L.findIndex((v)=>!v.children.length),y=!U.name||!q.has(F)?\"a Tabs needs a line of its own, such as pages = Tabs(titles, groups), for the field of its open tab\":!L.length||L.length>30?`a Tabs has 1 to 30 groups, and this one has ${L.length}`:h>=0?`group ${h+1} of this Tabs is empty, and Alphy draws no empty group. Put a component into it`:null;if(y)return D(y),null;let VQ=I.length===L.length&&new Set(I).size===I.length&&I.every((v)=>v.trim()&&v.length<=100);if(!VQ)D(`a Tabs needs one title for each of its ${L.length} groups: texts of 1 to 100 characters, each one different. Now they are ${JSON.stringify(I)}`);let c=VQ?I:L.map((v,$Q)=>String($Q+1));if(!c.includes(Q.state[F]))Q.state[F]=c[0];let BQ=Q.state[F],ZQ=(v)=>{if(!c.includes(v))return!1;return Q.state[F]=v,\"\"};return{type:\"column\",children:[{type:\"tabs\",text:R.label(),bind:q.get(F),action:R.action(ZQ),options:c.map((v)=>({text:v,value:v}))},...L.map((v,$Q)=>({type:\"column\",visibleBind:o(\"v\",`${S}.tab${$Q}`,null,c[$Q]===BQ,R),children:v.children.map((uQ,$V)=>i(uQ,uQ.name??`${S}:tab${$Q}:${$V}`,b,{...O,weighted:!0})).filter(Boolean)}))]}}},e=A(U,R);if(!e)return null;G.set(e,b);for(let F of[\"visible\",\"enabled\"]){if(!N[F])continue;if(N[F].k!==\"lit\")e[F+\"Bind\"]=o(\"v\",`${S}.${F}`,N[F],U.props[F],R);else if(!U.props[F])e[F]=!1}if(U.props.weight!==void 0&&O.weighted)e.layout={weight:U.props.weight};return e},CQ={kit:Q,name:$,walk:i,day:Y,fields:q,lists:H,views:P,viewSchemas:X,viewOwners:_,listRoots:j},{kind:k,preset:s,palette:u}=yV(Q),n=Q.tree(),t=i(n,\"root\",\"root\",{scope:null,weighted:!0}),d=[...k?[{type:\"text\",text:String(k),style:fQ.eyebrow}]:[],...n.type===\"Column\"?t?.children??[]:[t].filter(Boolean)];return{ui:{type:\"theme\",preset:s??\"clean\",...u&&{palette:u},cornerRadius:20,children:[{type:\"column\",style:{gap:\"medium\"},children:d}]},values:m,views:P,viewSchemas:X,viewOwners:_,controls:W,bounds:T,faults:B,owners:G,listRoots:j,drawn:f}}function lV(Q,V,Z,$){let q=Q.kinds?.[V];if(q===\"list\")return Array.isArray(Z)?Z.map((K)=>DQ(K,Q.lists[V],$)):[];if(q===\"array\")return Array.isArray(Z)?Z:[];return q===\"time\"||q===\"date\"?PQ(q,Z,$):Z}function iV(Q,V,Z){let $={},q=QV(Q);for(let[K,H]of V.fields){let[Y,J]=H.split(\".\"),B=lV(V,K,Q.state[K],q);if(J)($[Y]??={})[J]=B;else $[Y]=B}if(V.anim)$[V.anim.field]=Object.fromEntries(V.anim.keys.map(([K,H])=>{let Y=Q.state._run?.find((J)=>J.path===K);return[H,Y?{from:Y.from,to:Y.to,start:Y.start,end:Y.end,at:Y.at}:fV]}));return{...$,...Z.views,...Z.values}}function sV(Q,V,Z){let $=V.kinds??{},q=Q.nextId-1;for(let[H,Y]of V.fields){let[J,B]=Y.split(\".\"),W=B?Z[J]?.[B]:Z[J];if(W===void 0)continue;if($[H]===\"list\")Q.state[H]=(Array.isArray(W)?W:[]).map((z)=>{return q=Math.max(q,Number(/^r(\\d+)$/.exec(z.id)?.[1]??0)),{id:z.id,...Object.fromEntries(V.lists[H].map(([G,j,T])=>[G,T===\"time\"?FQ(z[j]):z[j]]))}});else Q.state[H]=$[H]===\"time\"?FQ(W):$[H]===\"array\"&&Array.isArray(W)?[...W]:W}Q.nextId=q+1;let K=V.anim?V.anim.keys.map(([H,Y])=>({path:H,...Z[V.anim.field]?.[Y]})).filter((H)=>H.end):[];if(K.length)Q.state._run=K;else delete Q.state._run;Q.recompute()}function dV(Q,V){let Z=new RQ(new jQ().feedText(Q.lines),!1);if(Z.nextId=Q.next,Q.shown)Z.shown=Object.fromEntries(UQ(Q.shown).entries);return sV(Z,Q,V),Z}function ZV(Q,V,Z=Date.now()){let{state:$,event:q}=Q;if(q.type===\"preview\"||q.type===\"render\")return{state:$};let K=QQ.now;QQ.now=()=>Z;try{let H=dV(V,$),Y=V.kinds??{},J=wQ(H,V),B=q.type===\"open\"||q.type===\"timer\"&&(q.timerId===V.anim?.field?Boolean(H.state._run):J.drawn.some((P)=>P.id===q.timerId&&P.running)),W=q.type===\"action\"?J.controls.get(q.actionId):B?()=>H.tick():void 0;if(!W||W(q.value)===!1)return{state:$};for(let[P,X]of Object.entries(Y))if(X===\"list\"&&H.state[P]?.length>(r(V.caps,P)??SQ)||X===\"array\"&&H.state[P]?.length>gV)return{state:$};let z=wQ(H,V),G=z.bounds.filter(([P,X,_])=>!(P.props.value>=X&&P.props.value<=_));if(G.length){for(let[P,X,_]of G)H.write(P.props.bind,Number.isFinite(P.props.value)?Math.min(_,Math.max(X,P.props.value)):X,{it:P.row});H.recompute(),z=wQ(H,V)}let j=iV(H,V,z),T=JSON.stringify(z.ui)===JSON.stringify(J.ui)?{state:j}:{state:j,ui:z.ui};if(z.drawn.length)T.surfaces=rQ(z.drawn.map(({id:P,commands:X})=>[P,X])),T.timers=[...z.drawn.filter((P)=>P.running).map(({id:P})=>({id:P,intervalMs:vV})),...H.state._run?[{id:V.anim.field,intervalMs:wV}]:[]];return T}finally{QQ.now=K}}__handle(ZV);})();";
globalThis.compileAppFile = compileAppFile;
})();
