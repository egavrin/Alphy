// Built by kit/build.js from kit/src with Bun 1.3.9. Do not edit: run bun kit/build.js.
(() => {
"use strict";
// icons.js
const { HOST_ICONS } = (() => {
// Built by kit/build.js from ToolUiCatalog.kt shellIcons and the keys of ToolIcons.kt paths. Do not edit: run bun kit/build.js.

/** Each icon name of Alphy (ToolUiCatalog.icons). Only the compile step reads it. */
const HOST_ICONS = ["Menu","History","Plus","Minus","Arrow","Close","Back","Cloud","Mic","Grid","Spark","Calendar","Repeat","Settings","File","Link","Chevron","Search","Screen","Camera","Bell","Check","Alarm","Clock","Timer","Cart","Calculator","Bolt","Pin","Filter","Play","Pause","Stop","Trash","Info","Layers","Notebook","Photo","Shield","Flask","Refresh","Coffee","Cup","Drop","Glass","Milk","Utensils","Chef","Apple","Carrot","Salad","Pizza","Fish","Bean","Cake","IceCream","Candy","Wine","Heart","Pulse","Dumbbell","Bike","Steps","Person","Pill","Moon","Bed","Flame","Weight","Thermometer","Stethoscope","Brain","Smile","Leaf","Sprout","Flower","Tree","Sun","Rain","Snow","Wind","Umbrella","Mountain","Globe","Wallet","Card","Coins","Money","Bank","Savings","Bag","Tag","Receipt","Percent","Chart","Trend","Gift","Hourglass","Book","Pencil","School","Idea","Language","Puzzle","Music","Headphones","Paint","Picture","Film","Home","Car","Bus","Plane","Train","Map","Compass","Luggage","Tent","Key","Lamp","Wrench","Hammer","Sofa","Bath","Shower","Laundry","Plug","Battery","Wifi","Phone","Mail","Message","People","User","Baby","Dog","Cat","Paw","Briefcase","Trophy","Star","Medal","Dice","Gamepad","Target","Flag","Rocket","Crown","Share","Sort","List","Checklist","Eye","Lock","Warning","Help","Volume","Bookmark","Package","Shirt","Scissors","Ruler"];

return { HOST_ICONS };
})();
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
/**
 * True in the kit and in the compile step. The run-time ignores each fault and each error text, so kit/build.js makes this name
 * false in the run-time text, and the minifier leaves out each message that only the compile step reads (MESSAGES ? text : "").
 * Each module of the run-time has this line of its own: the build takes it out and defines the name.
 */
const MESSAGES = true;

class DslError extends Error {}

function tokenize(text) {
  const tokens = [];
  let i = 0;
  const fail = (message) => { throw new DslError(MESSAGES ? `${message} at column ${i + 1}` : ""); };
  while (i < text.length) {
    const ch = text[i];
    if (/\s/.test(ch)) { i++; continue; }
    if (ch === '"') {
      let j = i + 1, value = "";
      while (j < text.length && text[j] !== '"') {
        if (text[j] === "\\") { j++; value += { n: "\n", t: "\t" }[text[j]] ?? text[j]; } else value += text[j];
        j++;
      }
      if (j >= text.length) fail(MESSAGES ? "string without a closing quote" : "");
      tokens.push({ t: "str", v: value });
      i = j + 1;
      continue;
    }
    if (ch === "$") {
      const match = /^\$(\/?)([\p{L}_][\p{L}\p{N}_]*(?:\/[\p{L}_][\p{L}\p{N}_]*)*)/u.exec(text.slice(i));
      if (!match) fail(MESSAGES ? "path expected after $" : "");
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
    fail(MESSAGES ? `unexpected character ${ch}` : "");
  }
  return tokens;
}

/** Parses one expression. Returns the tree and the count of tokens that it used. */
function parseTokens(tokens, start = 0) {
  let at = start, depth = 0;
  const peek = (v) => tokens[at] && tokens[at].t === "op" && tokens[at].v === v;
  const eat = (v) => { if (!peek(v)) throw new DslError(MESSAGES ? `"${v}" expected` : ""); at++; };
  // The levels of the tree of the line: each bracket, sign, call, list, and operator in a chain is one. Each step of the kit reads a tree level by level.
  // The run-time reads only lines that the compile step took, so it does not count them (MESSAGES).
  const deeper = (levels) => { if ((depth += levels) > 100) throw new DslError(MESSAGES ? "the line nests more than 100 levels of brackets, signs, calls, or operators. Compute a part of it in a formula of its own" : ""); };
  function primary() {
    if (MESSAGES) deeper(1);
    const node = atom();
    if (MESSAGES) depth--;
    return node;
  }
  function atom() {
    const token = tokens[at];
    if (!token) throw new DslError(MESSAGES ? "value expected at the end of the line" : "");
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
        if (!key || (key.t !== "id" && key.t !== "str")) throw new DslError(MESSAGES ? "field name expected in { }" : "");
        eat(":");
        entries.push([key.v, ternary()]);
        if (!peek("}")) eat(",");
      }
      at++;
      return { k: "map", entries };
    }
    if (token.t === "op" && (token.v === "-" || token.v === "!")) return { k: "un", op: token.v, arg: postfix() };
    throw new DslError(MESSAGES ? `unexpected "${token.v}"` : "");
  }
  function postfix() {
    let node = primary();
    for (;;) {
      const next = tokens[at + 1];
      if (peek(".")) {
        at++;
        const field = tokens[at++];
        if (!field || field.t !== "id") throw new DslError(MESSAGES ? "field name expected after ." : "");
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
    let left = postfix(), chain = 0;
    while (tokens[at]?.t === "op" && OPS[tokens[at].v] >= level) {
      const op = tokens[at++].v;
      if (MESSAGES) { deeper(1); chain++; }
      left = { k: "bin", op, left, right: binary(OPS[op] + 1) };
    }
    if (MESSAGES) depth -= chain;
    return left;
  }
  function ternary() {
    const test = binary(1);
    if (!peek("?")) return test;
    at++;
    if (MESSAGES) deeper(1);
    const yes = ternary();
    eat(":");
    const no = ternary();
    if (MESSAGES) depth--;
    return { k: "if", test, yes, no };
  }
  const tree = ternary();
  return { tree, used: at - start };
}

function parseExpression(text) {
  const tokens = tokenize(text);
  const { tree, used } = parseTokens(tokens);
  if (used !== tokens.length) throw new DslError(MESSAGES ? `unexpected "${tokens[used].v ?? tokens[used].t}" after the expression` : "");
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
  throw new DslError(MESSAGES ? "line not understood" : "");
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
      if (key) { this.entries.delete(key); this.removed.add(key); } else this.faults.push({ line: lineNumber, text: entry.raw, message: MESSAGES ? `nothing named ${entry.key}` : "" });
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
const { CATALOG, ICON_NAMES, ACTIONS, TONES, STEPS, explode, dayNumber, FUNCTIONS, phased, foreground, shownFormulas, given, App, settings } = (() => {
// Runs an app file without any platform: state, formulas, actions, and the component tree.
// A renderer draws the tree. The checks of the host are in check.js.



/** False in the run-time text of an app (dsl.js). */
const MESSAGES = true;

/** Positional arguments of each component of the prototype catalog. */
const CATALOG = {
  Column: ["children"], Row: ["children"], Card: ["children"], Section: ["title", "children"],
  Text: ["text"], Heading: ["text"], Metric: ["label", "value"], Emoji: ["value", "label"], Badge: ["value"], Grid: ["children"],
  // label is the caption of a progress, a ring, a chart, and tabs. The text of a ring is the large text inside it.
  Progress: ["value", "max", "label"], Ring: ["value", "max", "text", "label"], Divider: [], Notice: ["text"],
  // label is the text of an icon button for a person who does not see the icon.
  Button: ["label", "action"], IconButton: ["icon", "action", "label"],
  TextField: ["label", "bind"], NumberField: ["label", "bind"], Slider: ["label", "bind"], RangeSlider: ["label", "bind"],
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
/**
 * The kit's icon names that Alphy has under a different name (ToolUiCatalog.icons). ArrowDown has no glyph of its own there.
 * IconButton and icon= take these names and each name of Alphy (check.js).
 */
const ICON_NAMES = { ArrowUp: "Arrow", ArrowDown: "Sort", ArrowLeft: "Back", ArrowRight: "Chevron", Edit: "Pencil" };
const NAMED = new Set(["visible", "enabled", "variant", "tone", "emphasis", "weight", "min", "max", "step", "empty", "placeholder", "gap", "selected", "kind", "size", "align", "columns", "icon"]);
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

/**
 * The work of one event of the run-time, or of one look of the compile step, in steps: each value, shape, and node, and each item and
 * text of a value that a formula or an action builds (charge). So a plan cannot hold the memory or the time of the sandbox. The most
 * nodes of one screen and the most shapes of one drawing have limits of their own, as each one holds more memory than a step.
 * The saved plans use at most 44662 steps (with each list full of long texts), 1944 nodes, and 60 shapes. Alphy draws at most 512 commands.
 */
const STEPS = 200000;
const MAX_NODES = 20000, MAX_SHAPES = 4096;
const round = (x, n = 0) => { const f = 10 ** n; return Math.round((x + Number.EPSILON) * f) / f; };
/** A deep copy of a value of the app. The sandbox has no structuredClone. NaN and Infinity stay, so the report can find them. */
const clone = (value) => (Array.isArray(value) ? value.map(clone) : value && typeof value === "object" ? Object.fromEntries(Object.entries(value).map(([key, inner]) => [key, clone(inner)])) : value);
/** A model that is taken apart: each body of a named part moves away from the center of the model, by half of its distance from it. The bodies of the caller stay as they are. */
function explode(bodies) {
  const center = ["x", "y", "z"].map((axis) => bodies.reduce((sum, body) => sum + body[axis], 0) / (bodies.length || 1));
  return bodies.map((body) => (body.part ? { ...body, ...Object.fromEntries(["x", "y", "z"].map((axis, index) => [axis, round(body[axis] + (body[axis] - center[index]) / 2, 2)])) } : body));
}
/** The group sign and the decimal sign of a language of the app. Each other language prints as en. A table, not Intl: the kit and the sandbox of the device print one text. */
const SIGNS = { de: ".,", es: ".,", it: ".,", pt: ".,", tr: ".,", ru: "\u00a0,", fr: "\u00a0,", pl: "\u00a0,", uk: "\u00a0," };
/** A number for a person with n decimals, in the language of the app. trim takes the zeros at the end of the decimals away. In es, pl, and pt a whole part of four digits has no group. */
function print(lang, x, n, trim) {
  const [group, point] = Object.hasOwn(SIGNS, lang) ? SIGNS[lang] : ",.", fixed = Number(x).toFixed(n), parts = /^-?(\d+)\.?(\d*)$/.exec(fixed);
  if (!parts) return fixed;
  const [, whole, decimals] = parts, shown = trim ? decimals.replace(/0+$/, "") : decimals;
  return (Number(fixed) < 0 ? "-" : "") + (whole.length < 5 && /^(es|pl|pt)$/.test(lang) ? whole : whole.replace(/\B(?=(\d{3})+$)/g, group)) + (shown ? point + shown : "");
}
/** The format functions that print a number for a person. Each one takes the language of the app first. */
const PRINT = {
  fixed: (lang, x, n = 2) => print(lang, x, n), money: (lang, x, sign = "") => sign + print(lang, x, 2), percent: (lang, x, n = 0) => print(lang, x, n) + "%",
  // The number of a figure: rounded to at most n decimals, with no zeros at the end.
  amount: (lang, x, n = 0) => print(lang, round(Number(x), n), n, true),
};
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
  pad2: (x) => String(Math.floor(x)).padStart(2, "0"),
  join: (list, sep = ", ") => {
    const items = list || [];
    // The separator goes between each two items before the kit can count the text, so a long one is counted first.
    if (items.length * String(sep).length > STEPS) throw new DslError(MESSAGES ? `join gives a text of more than ${STEPS} characters` : "");
    return items.join(sep);
  },
  upper: (s) => String(s).toUpperCase(), lower: (s) => String(s).toLowerCase(),
  at: (list, index) => (Array.isArray(list) ? list[index] ?? null : null),
  // The longest run of true values in a list, such as the days in a row that a habit was done.
  streak: (list) => (list ?? []).reduce(([best, run], on) => (on ? [Math.max(best, run + 1), run + 1] : [best, 0]), [0, 0])[0],
  near: (a, b) => Math.abs(a - b) <= Math.max(0.01, Math.abs(b) / 1000),
  trim: (s) => String(s).trim(), contains: (a, b) => String(a).toLowerCase().includes(String(b).toLowerCase()),
  has: (list, value) => Array.isArray(list) && list.includes(value),
  // The hours from one "HH:MM" time to the next one, over midnight when the second time is the earlier one.
  hours: (from, to) => { const [a, b] = [from, to].map(minutes); return a === null || b === null ? 0 : ((b - a + 1440) % 1440) / 60; },
  // The local day of the clock of the host (settings.now), so that a test and the smoke run set it.
  today: () => { const now = new Date(settings.now()); return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}-${String(now.getDate()).padStart(2, "0")}`; },
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
    if (!key) throw new DslError(MESSAGES ? "groups needs a key, such as groups($/rows, $category, $amount)" : "");
    const sums = new Map();
    for (const row of rows) sums.set(key(row), (sums.get(key(row)) ?? 0) + Number(value ? value(row) : 1));
    return [...sums].map(([name, sum]) => ({ key: name, value: sum }));
  },
};

const FUNCTIONS = new Set([...Object.keys(PLAIN), ...Object.keys(PRINT), ...Object.keys(ROWWISE), "if"]);

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
    // The steps of work so far (step). A caller of the compile step sets steps to 0 for each look. tripped stays: the work stopped.
    this.steps = 0;
    this.tripped = false;
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

  /** After the work stops (step), each later step fails too. Only the first fault after the stop is kept: the one that names the line. */
  fault(where, message) {
    if (MESSAGES && this.told) return;
    if (MESSAGES && this.tripped) this.told = `${where}: ${message}`;
    this.faults.push({ where, message });
  }

  /** Counts n steps of work. Past STEPS the work stops: this step and each later one throw, so no value of a part of the work is kept. */
  step(n = 1) {
    if ((this.steps += n) > STEPS || this.tripped) this.stop(MESSAGES ? `the app needs more than ${STEPS} steps of work here, and Alphy stops it. Use fewer rows, shorter texts, or fewer levels of lists` : "");
  }

  /** Stops the work: this throw and the throw of each later step, so no value of a part of the work is kept. */
  stop(message) {
    this.tripped = true;
    throw new DslError(message);
  }

  /**
   * A value that a formula or an action builds counts one step for each item in it, also for each item that it holds two times, and
   * one step for each 16 characters of a text: a text costs less memory than an item, and the rows of a full list hold up to 49152 bytes of texts.
   */
  charge(value) {
    if (typeof value === "string") this.step(value.length >> 4);
    else if (value && typeof value === "object") { this.step(); for (const inner of Object.values(value)) this.charge(inner); }
    return value;
  }
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
      if (holder == null) throw new DslError(MESSAGES ? "a $field path needs a row" : "");
      if (typeof holder !== "object") throw new DslError(MESSAGES ? "the row is a plain value with no fields to set" : "");
      for (const seg of node.segs.slice(0, -1)) holder = holder[seg] ??= {};
      return [holder, node.segs.at(-1)];
    }
    if (node.k !== "field" && node.k !== "index") throw new DslError(MESSAGES ? "a place to set is a path, a field of a row, or an item of a list" : "");
    const holder = this.value(node.of, ctx);
    if (holder == null || typeof holder !== "object") throw new DslError(MESSAGES ? `${show(node.of)} has no value, so nothing in it can be set` : "");
    return [holder, node.k === "field" ? node.name : this.value(node.at, ctx)];
  }

  write(node, value, ctx) {
    const [holder, key] = this.place(node, ctx);
    holder[key] = value;
  }

  /** The value of an expression. ctx.it is the row for $field paths. */
  value(node, ctx) {
    this.step();
    switch (node.k) {
      case "lit": return node.v;
      case "path":
        // A row field that the row does not have is a mistake of the line, not an empty value.
        if (!node.abs && node.segs[0] === "it") return node.segs.slice(1).reduce((value, seg) => value?.[seg], ctx.it);
        // Inside each(list, shape), $index is the number of the row, from 0. A row field with that name wins.
        if (!node.abs && ctx.each !== undefined && node.segs.join() === "index" && !(ctx.it && typeof ctx.it === "object" && "index" in ctx.it)) return ctx.each;
        if (!node.abs && ctx.it === undefined) throw new DslError(MESSAGES ? `$${node.segs.join("/")} is a field of a list row, and there is no row here. A value of the app is $/${node.segs.join("/")}` : "");
        if (!node.abs && ctx.it !== undefined && (ctx.it === null || typeof ctx.it !== "object")) throw new DslError(MESSAGES ? "the row is a plain value with no fields. $it reads the row itself" : "");
        if (!node.abs && ctx.it && !(node.segs[0] in ctx.it)) {
          // A value that an entries need computes for each row, show={name: formula}, reads as a field of the row
          // in each other formula of a row (v1 sleep-en: a chart of $slept). busy stops a show formula that reads itself.
          const name = node.segs[0];
          this.shown ??= Object.fromEntries(shownFormulas(this.file));
          if (node.segs.length === 1 && this.shown[name] && !ctx.busy?.includes(name)) return this.value(this.shown[name], { ...ctx, busy: [...(ctx.busy ?? []), name] });
          throw new DslError(MESSAGES ? `the row has no field ${name}` : "");
        }
        return this.read(node, ctx);
      case "list": return this.charge(node.items.map((item) => this.value(item, ctx)));
      case "map": return this.charge(Object.fromEntries(node.entries.map(([key, inner]) => [key, this.value(inner, ctx)])));
      case "field": return this.value(node.of, ctx)?.[node.name];
      case "index": { const list = this.value(node.of, ctx); return Array.isArray(list) ? list[this.value(node.at, ctx)] ?? null : null; }
      case "un": return node.op === "-" ? -this.value(node.arg, ctx) : !this.value(node.arg, ctx);
      case "if": return this.value(node.test, ctx) ? this.value(node.yes, ctx) : this.value(node.no, ctx);
      case "bin": {
        if (node.op === "&&") return this.value(node.left, ctx) && this.value(node.right, ctx);
        if (node.op === "||") return this.value(node.left, ctx) || this.value(node.right, ctx);
        const a = this.value(node.left, ctx), b = this.value(node.right, ctx);
        switch (node.op) {
          case "+": return this.charge(Array.isArray(a) && Array.isArray(b) ? [...a, ...b] : typeof a === "string" || typeof b === "string" ? String(a ?? "") + String(b ?? "") : a + b);
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
          if (!Array.isArray(rows)) throw new DslError(MESSAGES ? `${node.name} needs a list` : "");
          const perRow = (arg) => arg && ((row) => this.value(arg, { ...ctx, it: row }));
          // The third argument of groups is a formula of the row. The third argument of sort is the order.
          return this.charge(ROWWISE[node.name](rows, perRow(node.args[1]), node.name === "groups" ? perRow(node.args[2]) : node.args[2] && this.value(node.args[2], ctx)));
        }
        if (PLAIN[node.name]) return this.charge(PLAIN[node.name](...node.args.map((arg) => this.value(arg, ctx))));
        if (PRINT[node.name]) return this.charge(PRINT[node.name](this.file.get("app")?.tree.named.lang?.v, ...node.args.map((arg) => this.value(arg, ctx))));
        if (CATALOG[node.name]) throw new DslError(MESSAGES ? `${node.name} is a component. A component line has a plain name with no $/ before it` : "");
        throw new DslError(MESSAGES ? `unknown function ${node.name}` : "");
      }
      case "ref": throw new DslError(MESSAGES ? `${node.name} is a component name, not a value` : "");
    }
    throw new DslError(MESSAGES ? "expression not understood" : "");
  }

  /**
   * The data calls of a Drawing (tag "shape") or a Model3d (tag "body") as plain values, so a renderer needs no formula.
   * each(list, call) gives one for each row of a list, or for each number below a count. A call with a false visible test gives nothing.
   */
  drawn(kinds, tag, tree, ctx) {
    this.step();
    if (tree.k === "list") return tree.items.flatMap((item) => this.drawn(kinds, tag, item, ctx));
    const { name = "", args = [], named = {} } = tree.k === "call" ? tree : {};
    if (name === "each" && args.length === 2) {
      const given = this.value(args[0], ctx), rows = Array.isArray(given) ? given : Number.isInteger(given) && given >= 0 && given <= 500 ? Array.from({ length: given }, (_, index) => index) : null;
      if (!rows) throw new DslError(MESSAGES ? "each needs a list or a count: each($/rows, shape) or each(5, shape)" : "");
      return rows.flatMap((row, index) => this.drawn(kinds, tag, args[1], { ...ctx, it: row, each: index }));
    }
    const keys = Object.hasOwn(kinds, name) ? kinds[name] : null;
    if (!keys) throw new DslError(MESSAGES ? `${show(tree).slice(0, 30)} is not one of ${Object.keys(kinds).join(", ")}, each` : "");
    const last = LAST[name], takes = MESSAGES ? `${name} takes ${keys.join(", ")}, color${last ? `, ${last}` : ""}` : "";
    if (args.length < keys.length || args.length > keys.length + (last ? 2 : 1)) throw new DslError(MESSAGES ? `${show(tree).slice(0, 60)} has ${args.length} values: ${takes}` : "");
    if (named.visible && !this.value(named.visible, ctx)) return [];
    const number = (value, key) => { if (value === null || value === "" || typeof value === "boolean" || !Number.isFinite(Number(value))) throw new DslError(MESSAGES ? `${key} of ${name} is not a number: ${takes}` : ""); return round(Number(value), 2); };
    const out = { [tag]: name };
    keys.forEach((key, index) => {
      const value = this.value(args[index], ctx);
      out[key] = TEXTS.has(key) ? String(value ?? "") : key === "points" ? [value].flat().map((item) => number(item, key)) : number(value, key);
    });
    if (out.axis !== undefined && !["x", "y", "z"].includes(out.axis)) throw new DslError(MESSAGES ? `axis of ${name} is "x", "y", or "z"` : "");
    out.color = String((args[keys.length] ? this.value(args[keys.length], ctx) : null) ?? "ink");
    if (args.length === keys.length + 2) out[last] = number(this.value(args[keys.length + 1], ctx), last);
    for (const [key, arg] of Object.entries(named)) {
      if (key === "visible") continue;
      if (!EXTRAS.includes(key)) throw new DslError(MESSAGES ? `${name} has no argument ${key}` : "");
      const value = this.value(arg, ctx);
      if (key !== "stroke") out[key] = number(value, key); else if (value) out.stroke = true;
    }
    if (++this.shapes > MAX_SHAPES) this.stop(MESSAGES ? `the drawing has more than ${MAX_SHAPES} shapes, and Alphy stops it. Alphy draws at most 512 commands` : "");
    return [out];
  }

  /**
   * Formulas can depend on each other in any order, so run them until nothing changes. Each pass settles at least one more formula
   * of a chain, so a pass for each formula is enough: check.js refuses formulas that read each other in a circle.
   */
  recompute() {
    const formulas = this.file.of("formula");
    for (let pass = 0; pass <= formulas.length; pass++) {
      const before = JSON.stringify(this.state);
      for (const entry of formulas) {
        if (this.tripped) return;
        this.attempt(entry, () => this.write(this.pathOf(entry.key), this.value(entry.tree, {}), {}));
      }
      if (JSON.stringify(this.state) === before) return;
    }
  }

  /** Runs one action call or a list of them, then the formulas. ctx has the row and its list for a row control. */
  act(tree, ctx = {}) {
    for (const call of tree.k === "list" ? tree.items : [tree]) {
      if (call.k !== "call" || !ACTIONS.has(call.name)) throw new DslError(MESSAGES ? `unknown action ${call.name ?? call.k}` : "");
      const [first, second] = call.args;
      if (call !== (tree.k === "list" ? tree.items[0] : tree)) this.recompute();
      const target = () => { if (!["path", "field", "index"].includes(first?.k)) throw new DslError(MESSAGES ? `${call.name} needs a place: a path, a field of a row, or an item of a list` : ""); return first; };
      const rowsOf = (node) => { const rows = this.value(node, ctx); if (!Array.isArray(rows)) throw new DslError(MESSAGES ? `${call.name} needs a list` : ""); return rows; };
      switch (call.name) {
        // A value that an action writes counts as work too (charge): an action can copy a value of the app into itself.
        case "set": this.write(target(), clone(this.charge(this.value(second, ctx))), ctx); break;
        case "toggle": this.write(target(), !this.value(target(), ctx), ctx); break;
        case "inc": this.write(target(), this.charge((this.value(target(), ctx) || 0) + (second ? this.value(second, ctx) : 1)), ctx); break;
        case "dec": this.write(target(), (this.value(target(), ctx) || 0) - (second ? this.value(second, ctx) : 1), ctx); break;
        case "clear": this.write(target(), [], ctx); break;
        // flip($/list, value): the value joins the list, or leaves it when it is there. For options that a person turns on and off.
        case "flip": { const rows = rowsOf(target()), value = this.charge(this.value(second, ctx)); this.write(target(), rows.includes(value) ? rows.filter((item) => item !== value) : [...rows, value], ctx); break; }
        case "move": {
          // In a list row: move(-1) moves that row one place up. Outside: move($/list, test, by) moves the first row that passes.
          const inRow = call.args.length === 1;
          if (inRow && !ctx.list) throw new DslError(MESSAGES ? "move(by) needs a row. Outside a list write move($/list, test, by)" : "");
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
          if (first?.k !== "path" || !first.abs) throw new DslError(MESSAGES ? "animate needs a value of the app as its place, such as $/angle" : "");
          const path = first.segs.join("/"), from = Number(this.value(first, ctx)) || 0, to = second ? Number(this.value(second, ctx)) : NaN, ms = call.args[2] ? Number(this.value(call.args[2], ctx)) : 0;
          if (!Number.isFinite(to) || !Number.isFinite(ms)) throw new DslError(MESSAGES ? "animate needs a number to go to and a time in milliseconds: animate($/angle, 360, 5000)" : "");
          const runs = (this.state._run ?? []).filter((run) => run.path !== path), now = settings.now();
          if (ms > 0 && to !== from) runs.push({ path, from, to, start: now, end: now + ms, at: from }); else this.write(first, to, ctx);
          if (runs.length) this.state._run = runs; else delete this.state._run;
          break;
        }
        case "append": {
          const row = clone(this.charge(this.value(second, ctx)));
          rowsOf(target()).push(row);
          this.tagRows([row]);
          break;
        }
        case "remove": {
          if (!first) {
            if (!ctx.list) throw new DslError(MESSAGES ? "remove() without a path needs a row" : "");
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
    let unnamed = 0, nodes = 0;
    // inside holds the names of the component lines that hold this node, so a line that shows itself is a fault and not a screen with no end.
    // The run-time draws only lines that the compile step took, so it does not look (MESSAGES).
    const visit = (tree, ctx, name, depth, inside) => {
      this.step();
      if (++nodes > MAX_NODES) this.stop(MESSAGES ? `the screen has more than ${MAX_NODES} parts, and Alphy stops it. Show fewer rows or fewer lines in each row` : "");
      if (depth > 40) throw new DslError(MESSAGES ? "components nest more than 40 levels deep" : "");
      if (tree.k === "ref") {
        const entry = this.file.get(tree.name);
        this.reached.add(tree.name);
        if (!entry || entry.kind !== "component") {
          if (MESSAGES && strict) this.fault(tree.name, "no component line with this name");
          return { type: "Missing", name: tree.name, props: {}, children: [] };
        }
        if (MESSAGES && inside.includes(tree.name)) throw new DslError(`${tree.name} shows itself inside itself, so its screen has no end. Show other lines in it`);
        return visit(entry.tree, ctx, tree.name, depth + 1, [...inside, tree.name]);
      }
      if (tree.k !== "call" || !CATALOG[tree.name]) {
        this.fault(name ?? "?", MESSAGES ? (tree.k === "path" ? `a child is a component name, not the path ${show(tree)}` : `unknown component ${tree.name ?? tree.k}`) : "");
        return { type: "Missing", name, props: {}, children: [] };
      }
      // call is the expression of the component, so the emitter can tell a constant property from a computed one.
      const node = { type: tree.name, name, props: {}, children: [], row: ctx.it, rowIndex: ctx.index, list: ctx.list, call: tree };
      const props = given(tree);
      let groups = [];
      for (const [key, arg] of Object.entries(props)) {
        if (arg === undefined) continue;
        // A field takes an action too. It runs after each change of the field.
        if (!CATALOG[tree.name].includes(key) && !NAMED.has(key) && !(key === "action" && props.bind)) { this.fault(name ?? tree.name, MESSAGES ? `${tree.name} has no property ${key}` : ""); continue; }
        if ((tree.name === "Chart" && (key === "point" || key === "value")) || (tree.name === "List" && key === "group") || key === "shapes" || key === "bodies") continue;
        try {
          if (key === "children") node.children = (arg.k === "list" ? arg.items : [arg]).map((child) => visit(child, ctx, undefined, depth + 1, inside));
          else if (key === "groups") groups = arg.k === "list" ? arg.items : [arg];
          else if (key === "action") node.props.action = arg;
          else if (key === "bind") {
            if (!["path", "field", "index"].includes(arg.k)) throw new DslError(MESSAGES ? "bind needs a place: a path, a field of a row, or an item of a list" : "");
            node.props.bind = arg;
            node.props.value = arg.k === "path" ? this.read(arg, ctx) : this.value(arg, ctx);
          } else if (key === "template") node.template = arg;
          else node.props[key] = this.value(arg, ctx);
          // Options as a map of a text value to its label: the state keeps the value, and the control shows the label (check.js refuses number keys).
          const options = node.props.options;
          if (key === "options" && options && typeof options === "object" && !Array.isArray(options)) [node.props.options, node.props.labels] = [Object.keys(options), Object.values(options)];
        } catch (failure) { this.fault(name ?? tree.name, MESSAGES ? `${key}: ${failure.message}` : ""); }
      }
      if (tree.name === "Chart") {
        // One point for each row. point (the label of the point) and value are formulas of the row.
        try {
          node.props.points = (Array.isArray(node.props.items) ? node.props.items : []).map((row) => ({
            label: props.point ? this.value(props.point, { ...ctx, it: row }) : "", value: Number(props.value ? this.value(props.value, { ...ctx, it: row }) : row) || 0 }));
        } catch (failure) { this.fault(name ?? tree.name, MESSAGES ? `points: ${failure.message}` : ""); }
        delete node.props.items;
      }
      if (tree.name === "Drawing" || tree.name === "Model3d") {
        const key = tree.name === "Drawing" ? "shapes" : "bodies", list = props[key] ?? { k: "list", items: [] };
        this.shapes = 0;
        // An item that fails, such as a shape whose x is not a number at run time, is a fault and is left out: the other items still draw.
        node.props[key] = (list.k === "list" ? list.items : [list]).flatMap((item) => {
          try { return this.drawn(key === "shapes" ? SHAPES : BODIES, key === "shapes" ? "shape" : "body", item, ctx); }
          catch (failure) { this.fault(name ?? tree.name, MESSAGES ? `${key}: ${failure.message}` : ""); return []; }
        });
        if (key === "bodies" && node.props.exploded) node.props.bodies = explode(node.props.bodies);
        if (MESSAGES && key === "shapes" && !(node.props.width > 0 && node.props.height > 0)) this.fault(name ?? tree.name, "a drawing needs a width and a height above 0: drawing(label, 320, 240, [shapes])");
      }
      // The address comes from a model, so the host shows only a web address.
      if (MESSAGES && tree.name === "Link" && !/^https?:\/\/\S+$/.test(String(node.props.url ?? ""))) this.fault(name ?? tree.name, "a Link needs a web address that starts with https://");
      if (MESSAGES && tree.name === "Dialog" && !props.visible) this.fault(name ?? tree.name, "a Dialog needs visible=test and a button that makes the test false. Without them the window never closes");
      if (tree.name === "Choices") {
        // One on-off button for each option. With max= the other options are disabled when that many are on.
        const on = Array.isArray(node.props.value) ? node.props.value : [], full = node.props.max !== undefined && on.length >= node.props.max;
        node.children = (node.props.options ?? []).map((option, index) => ({ type: "Button", name: name && `${name}_opt${index}`, children: [],
          props: { label: String(node.props.labels?.[index] ?? option), selected: on.includes(option), enabled: on.includes(option) || !full, action: { k: "call", name: "flip", args: [props.bind, { k: "lit", v: option }], named: {} } } }));
      }
      if (tree.name === "Tabs") {
        // The open tab is a value of the screen. It is in the state under _ui, so each reset of the state closes it again.
        const place = { k: "path", abs: true, segs: ["_ui", name ?? "tabs" + unnamed++] }, open = this.read(place, {}) ?? 0;
        node.props.value = open;
        node.children = [
          { type: "Row", props: {}, children: (node.props.titles ?? []).map((title, index) => ({ type: "Button", name: name && `${name}_tab${index}`, tab: true, children: [],
            props: { label: title, selected: index === open, action: { k: "call", name: "set", args: [place, { k: "lit", v: index }], named: {} } } })) },
          ...groups.map((group, index) => ({ type: "Column", props: { visible: index === open }, children: (group.k === "list" ? group.items : [group]).map((child) => visit(child, ctx, undefined, depth + 1, inside)) })),
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
        } catch (failure) { this.fault(name ?? tree.name, MESSAGES ? `group: ${failure.message}` : ""); }
        node.children = rows.flatMap((row, index) => [...(heads[index] ?? []), visit(node.template, { it: row, index, list }, undefined, depth + 1, inside)]);
        // For the emitter: the stored list of the rows, and the row template drawn for a row that it gives, such as a blank row.
        node.source = list;
        node.blank = (row) => visit(node.template, { it: row, index: 0, list }, undefined, depth + 1, inside);
      }
      return node;
    };
    const root = this.file.get("root");
    if (!root) return { type: "Missing", name: "root", props: {}, children: [] };
    try { return visit(root.tree, {}, "root", 0, ["root"]); } catch (failure) { this.fault("root", failure.message); return { type: "Missing", name: "root", props: {}, children: [] }; }
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
    } catch (failure) { return this.failed(node, failure); }
    this.tick();
    return "";
  }

  fire(node) {
    if (node.props.enabled === false || node.props.visible === false) return `${node.name ?? node.type} is not active`;
    this.tick();
    try { this.act(node.props.action, { it: node.row, list: node.list }); } catch (failure) { return this.failed(node, failure); }
    this.tick();
    return "";
  }

  /** The error text of a control. A stop of the work in its action is the fault of the stop (fault), as it names the control. */
  failed(node, failure) {
    const text = `${node.name ?? node.type}: ${failure.message}`;
    if (MESSAGES && this.tripped) this.told ??= text;
    return text;
  }

  /** The same by name, for checks and for the agent loop. Each one is one event, with the budget of work of one event (step). */
  input(name, value, rowIndex) {
    this.steps = 0;
    const node = this.find(name, rowIndex);
    if (node?.props.bind) return this.change(node, value);
    const why = !node ? `no field named ${name}` : node.props.action ? `${name} is not a field: use tap(${name})` : `${name} only shows a value and cannot be set. A check sets a field, such as a NumberField`;
    return why + (rowIndex === undefined ? "" : ` in row ${rowIndex}`);
  }

  press(name, rowIndex) {
    this.steps = 0;
    const node = this.find(name, rowIndex);
    if (node?.props.action) return this.fire(node);
    return (node?.props.bind ? `${name} is a field with no action: use set(${name}, value)` : `no control named ${name}`) + (rowIndex === undefined ? "" : ` in row ${rowIndex}`);
  }
}

/** How the host decides that model-written logic is fit: "checks" wants a check line for each control and formula, "smoke" uses each control itself. */
// now is the clock of the host in milliseconds. A test sets it, and the smoke run has its own.
// The kit uses the smoke gate: an app file of this design has no check lines.
const settings = { gate: "smoke", now: () => Date.now() };

return { CATALOG, ICON_NAMES, ACTIONS, TONES, STEPS, explode, dayNumber, FUNCTIONS, phased, foreground, shownFormulas, given, App, settings };
})();
// words.js
const { WORDS, hasWords, wordOf } = (() => {
// The words of the kit: the captions that a part writes by itself, in each language of Alphy, by their English word.
// Only the compile step reads this table (parts.js), so no word goes into the run-time text of an app.
// A word that Alphy's resources hold with the same meaning and form is a copy of it (values*/mini_app_strings.xml,
// values/mini_app_editor_english.xml, and the Russian default in values/), so that the app and the host use one word.

/** The words by language, each by its English word. English is the key itself. */
const WORDS = {
  ru: {
    Add: "Добавить", Yes: "Да", No: "Нет", Back: "Назад", Next: "Далее", Done: "Готово", Start: "Начать", Pause: "Пауза", Reset: "Сбросить", Show: "Показать",
    Again: "Ещё раз", Check: "Проверить", Right: "Верно", Wrong: "Неверно", "Not yet": "Пока нет", Points: "Очки", "Your answer": "Ваш ответ", Rest: "Отдых",
    Work: "Работа", "Time is up": "Время вышло", Step: "Шаг", "Put together": "Собрать", "Take apart": "Разобрать",
    Remove: "Убрать", Less: "Меньше", More: "Больше",
  },
  de: {
    Add: "Hinzufügen", Yes: "Ja", No: "Nein", Back: "Zurück", Next: "Weiter", Done: "Fertig", Start: "Starten", Pause: "Pause", Reset: "Zurücksetzen", Show: "Zeigen",
    Again: "Noch einmal", Check: "Prüfen", Right: "Richtig", Wrong: "Falsch", "Not yet": "Noch nicht", Points: "Punkte", "Your answer": "Deine Antwort", Rest: "Erholung",
    Work: "Belastung", "Time is up": "Zeit abgelaufen", Step: "Schritt", "Put together": "Zusammensetzen", "Take apart": "Zerlegen",
    Remove: "Entfernen", Less: "Weniger", More: "Mehr",
  },
  es: {
    Add: "Añadir", Yes: "Sí", No: "No", Back: "Atrás", Next: "Siguiente", Done: "Listo", Start: "Empezar", Pause: "Pausa", Reset: "Restablecer", Show: "Mostrar",
    Again: "Otra vez", Check: "Comprobar", Right: "Correcto", Wrong: "Incorrecto", "Not yet": "Todavía no", Points: "Puntos", "Your answer": "Tu respuesta", Rest: "Descanso",
    Work: "Trabajo", "Time is up": "Se acabó el tiempo", Step: "Paso", "Put together": "Montar", "Take apart": "Desmontar",
    Remove: "Quitar", Less: "Menos", More: "Más",
  },
  fr: {
    Add: "Ajouter", Yes: "Oui", No: "Non", Back: "Précédent", Next: "Suivant", Done: "Terminé", Start: "Démarrer", Pause: "Pause", Reset: "Réinitialiser", Show: "Afficher",
    Again: "Recommencer", Check: "Vérifier", Right: "Juste", Wrong: "Faux", "Not yet": "Pas encore", Points: "Points", "Your answer": "Ta réponse", Rest: "Repos",
    Work: "Effort", "Time is up": "Temps écoulé", Step: "Étape", "Put together": "Assembler", "Take apart": "Démonter",
    Remove: "Supprimer", Less: "Moins", More: "Plus",
  },
  it: {
    Add: "Aggiungi", Yes: "Sì", No: "No", Back: "Indietro", Next: "Avanti", Done: "Fatto", Start: "Avvia", Pause: "Pausa", Reset: "Azzera", Show: "Mostra",
    Again: "Di nuovo", Check: "Verifica", Right: "Giusto", Wrong: "Sbagliato", "Not yet": "Non ancora", Points: "Punti", "Your answer": "La tua risposta", Rest: "Riposo",
    Work: "Lavoro", "Time is up": "Tempo scaduto", Step: "Passo", "Put together": "Assembla", "Take apart": "Smonta",
    Remove: "Rimuovi", Less: "Meno", More: "Più",
  },
  ja: {
    Add: "追加", Yes: "はい", No: "いいえ", Back: "戻る", Next: "次へ", Done: "完了", Start: "開始", Pause: "一時停止", Reset: "リセット", Show: "表示",
    Again: "もう一度", Check: "確認", Right: "正解", Wrong: "不正解", "Not yet": "まだ", Points: "ポイント", "Your answer": "あなたの答え", Rest: "休憩",
    Work: "運動", "Time is up": "時間になりました", Step: "ステップ", "Put together": "組み立てる", "Take apart": "分解する",
    Remove: "削除", Less: "減らす", More: "増やす",
  },
  ko: {
    Add: "추가", Yes: "예", No: "아니요", Back: "뒤로", Next: "다음", Done: "완료", Start: "시작", Pause: "일시정지", Reset: "초기화", Show: "보기",
    Again: "다시", Check: "확인", Right: "정답", Wrong: "오답", "Not yet": "아직", Points: "점수", "Your answer": "내 답", Rest: "휴식",
    Work: "운동", "Time is up": "시간이 종료되었습니다", Step: "단계", "Put together": "조립하기", "Take apart": "분해하기",
    Remove: "제거", Less: "줄이기", More: "늘리기",
  },
  pl: {
    Add: "Dodaj", Yes: "Tak", No: "Nie", Back: "Wstecz", Next: "Dalej", Done: "Gotowe", Start: "Start", Pause: "Pauza", Reset: "Resetuj", Show: "Pokaż",
    Again: "Jeszcze raz", Check: "Sprawdź", Right: "Dobrze", Wrong: "Źle", "Not yet": "Jeszcze nie", Points: "Punkty", "Your answer": "Twoja odpowiedź", Rest: "Odpoczynek",
    Work: "Praca", "Time is up": "Czas minął", Step: "Krok", "Put together": "Złóż", "Take apart": "Rozłóż",
    Remove: "Usuń", Less: "Mniej", More: "Więcej",
  },
  pt: {
    Add: "Adicionar", Yes: "Sim", No: "Não", Back: "Voltar", Next: "Próximo", Done: "Concluído", Start: "Iniciar", Pause: "Pausar", Reset: "Redefinir", Show: "Mostrar",
    Again: "De novo", Check: "Verificar", Right: "Certo", Wrong: "Errado", "Not yet": "Ainda não", Points: "Pontos", "Your answer": "Sua resposta", Rest: "Descanso",
    Work: "Trabalho", "Time is up": "O tempo acabou", Step: "Passo", "Put together": "Montar", "Take apart": "Desmontar",
    Remove: "Remover", Less: "Menos", More: "Mais",
  },
  tr: {
    Add: "Ekle", Yes: "Evet", No: "Hayır", Back: "Geri", Next: "İleri", Done: "Tamam", Start: "Başlat", Pause: "Duraklat", Reset: "Sıfırla", Show: "Göster",
    Again: "Tekrar", Check: "Kontrol et", Right: "Doğru", Wrong: "Yanlış", "Not yet": "Henüz değil", Points: "Puan", "Your answer": "Cevabın", Rest: "Dinlenme",
    Work: "Çalışma", "Time is up": "Süre doldu", Step: "Adım", "Put together": "Birleştir", "Take apart": "Parçalara ayır",
    Remove: "Kaldır", Less: "Azalt", More: "Artır",
  },
  uk: {
    Add: "Додати", Yes: "Так", No: "Ні", Back: "Назад", Next: "Далі", Done: "Готово", Start: "Почати", Pause: "Пауза", Reset: "Скинути", Show: "Показати",
    Again: "Ще раз", Check: "Перевірити", Right: "Правильно", Wrong: "Неправильно", "Not yet": "Ще ні", Points: "Бали", "Your answer": "Ваша відповідь", Rest: "Відпочинок",
    Work: "Робота", "Time is up": "Час вийшов", Step: "Крок", "Put together": "Зібрати", "Take apart": "Розібрати",
    Remove: "Прибрати", Less: "Менше", More: "Більше",
  },
  zh: {
    Add: "添加", Yes: "是", No: "否", Back: "返回", Next: "下一步", Done: "完成", Start: "开始", Pause: "暂停", Reset: "重置", Show: "显示",
    Again: "再来一次", Check: "检查", Right: "正确", Wrong: "错误", "Not yet": "还没有", Points: "得分", "Your answer": "你的答案", Rest: "休息",
    Work: "训练", "Time is up": "时间到", Step: "步骤", "Put together": "组装", "Take apart": "拆开",
    Remove: "移除", Less: "减少", More: "增加",
  },
};

/** True when the kit has its words in the language of an app: English, a language of the table, or no language. */
const hasWords = (lang) => lang === undefined || lang === "en" || Object.hasOwn(WORDS, lang);
/** The word of the kit for an English caption in a language. A language that the table does not have gets the English word. */
const wordOf = (lang, english) => (Object.hasOwn(WORDS, lang ?? "") && Object.hasOwn(WORDS[lang], english) ? WORDS[lang][english] : english);

return { WORDS, hasWords, wordOf };
})();
// parts.js
const { rules, PART_KINDS, needsOf, faultName, expand } = (() => {
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
  // need bill = input("money", "Bill", 80, min=0, max=500, icon="Wallet")
  // need kind = input("choice", "Type", "road", options={road: "Road", city: "City"}); need price = input("range", "Price", [100, 1500], min=0, max=2000, step=50)
  async input(name, need, { choose }) {
    const [kind, title, value] = need.args, extras = need.named;
    const k = kind?.v;
    // Each kind of input uses its own options, and any other one is a fault (G.4).
    const uses = Object.hasOwn(INPUT_USES, k) ? INPUT_USES[k] : ["min", "max", "step", "icon"], unused = Object.keys(extras).filter((key) => !uses.includes(key));
    if (unused.length) throw new DslError(`an input of kind ${k} does not use ${unused.map((key) => key + "=").join(", ")}. ${uses.length ? `It uses ${uses.map((key) => key + "=").join(", ")}` : "It uses no option"}`);
    const named = Object.entries(extras).filter(([key]) => key !== "options").map(([key, tree]) => `, ${key}=${show(tree)}`).join("");
    // The options are a list of values or a map of a value to its label. The host draws a choice of six options or fewer as a segmented control, which shows no icon (run.js).
    const count = (extras.options?.items ?? extras.options?.entries)?.length;
    if (k === "choice" && extras.icon && count <= 6) throw new DslError(`a choice of ${count} options is a segmented control, which shows no icon. Remove icon=`);
    const icon = extras.icon ? `, icon=${show(extras.icon)}` : "", options = show(extras.options ?? { k: "list", items: [] }), few = (count ?? 9) <= 4;
    let widget;
    if (k === "choice") widget = `${few ? "Segmented" : "Select"}(${show(title)}, $/${name}, ${options}${few ? "" : icon})`;
    else if (k === "toggle") widget = `Toggle(${show(title)}, $/${name})`;
    else if (k === "text") widget = `TextField(${show(title)}, $/${name}${icon})`;
    else if (k === "choices") widget = `Choices(${show(title)}, $/${name}, ${options}${extras.max ? `, max=${show(extras.max)}` : ""})`;
    else if (k === "date" || k === "time") widget = `${k === "date" ? "DateField" : "TimeField"}(${show(title)}, $/${name}${icon})`;
    else if (k === "range") widget = `RangeSlider(${show(title)}, $/${name}${named})`;
    else {
      const ranged = extras.min && extras.max;
      const pick = await choose(`widget for ${name}`, ranged ? ["Slider", "NumberField"] : ["NumberField"], k === "percent" && ranged ? "Slider" : "NumberField");
      widget = `${pick}(${show(title)}, $/${name}${named}${k === "count" && !extras.step ? ", step=1" : ""})`;
    }
    return { lines: [`$/${name} = ${show(value)}`, `${name}_field = ${widget}`], top: [`${name}_field`] };
  },

  // need perPerson = figure("money", "Per person", $/bill / $/people, example=[{bill: 100, people: 4}, 25])
  // need today = figure("number", "Today", $/ml, icon="💧", unit="ml", goal=2000, goalText="Of the goal", note="250 ml for each cup"): the main figure is a hero card with a large emoji, a bar to its goal, and a muted note.
  // icon="Drop" (a name of a host icon) is the icon of the metric. role (expand): the place of a figure that is not the main one.
  async figure(name, need, { hero, role }) {
    const [format, title, formula] = need.args, f = format?.v, { icon, note, goal, goalText, unit } = need.named, main = hero === name;
    if ((note || goal || goalText) && !main) throw new DslError(`note=, goal=, and goalText= show only on the main figure, and ${name} is not it`);
    if (goalText && !goal) throw new DslError(`goalText= is the caption of the bar to goal=, and ${name} has no goal=. Give goal=, or remove goalText=`);
    const decimals = need.named.decimals && show(need.named.decimals);
    if (decimals && f === "text") throw new DslError(`a figure of the format text shows its value as it is, so it takes no decimals=. Remove decimals=, or give the format number`);
    // An own key, so that a format such as "constructor" is a number and never a property of Object. money() always prints 2 decimals, so decimals= of money is fixed().
    const formats = { money: decimals ? `fixed($/${name}, ${decimals})` : `money($/${name})`, percent: `percent($/${name}${decimals ? `, ${decimals}` : ""})`, text: `text($/${name})` };
    const text = Object.hasOwn(formats, f) ? formats[f] : `amount($/${name}, ${decimals || (f === "count" ? 0 : 2)})`;
    // unit="ml" follows the shown number ("2,000 ml"), and the value stays a number for a bar, a check, or an example.
    const lines = [`$/${name} := ${show(formula)}`, `$/${name}_text := ${text}${unit ? ` + " " + ${show(unit)}` : ""}`];
    // An emoji, or a formula that gives one, is the sign of the main figure. A word of letters is the name of a host icon.
    const named = icon?.k === "lit" && /^[A-Za-z]+$/.test(icon.v), sign = main && icon && !named, metric = (look) => `Metric(${show(title)}, $/${name}_text${look}${icon && !sign ? `, icon=${show(icon)}` : ""})`;
    // A row shows its icon as a small sign at the left of its title. A host icon there is an IconButton with no action, which Alphy draws as a glyph.
    const small = icon ? (named ? `IconButton(${show(icon)}, label=${show(title)}), ` : `Emoji(${show(icon)}, size="small", label=${show(title)}), `) : "";
    if (main) {
      const parts = [sign && `Emoji(${show(icon)}, size="large", label=${show(title)})`, metric(', emphasis="display"'), goal && `Progress($/${name}, ${show(goal)}, label=${show(goalText ?? title)})`, note && `Text(${show(note)}, tone="muted")`];
      lines.push(`${name}_view = Card([${parts.filter(Boolean).join(", ")}], variant="hero")`);
    } else lines.push(`${name}_view = ${role === "row" ? `Row([${small}Text(${show(title)}, weight=1), Text($/${name}_text, emphasis="number")])` : metric("")}`);
    // The first of two figures in a grid holds the second one, whose own place stays empty.
    const pair = role?.startsWith("grid ") && role.slice(5);
    if (pair) lines.push(`${name}_grid = Grid([${name}_view, ${pair}_view], columns=2)`);
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
    return { lines, top: role === "held" ? [] : [pair ? `${name}_grid` : `${name}_view`] };
  },

  // need tasks = entries("Tasks", fields={task: "text", level: ["high", "low"], done: "check"}, rows=[], edit=true, reorder=true, filter=$done, sort=$task, order=$/order, show={double: $n * 2})
  // More: field kinds "date", "time", "switch", "count". confirm="Delete?", sheet=true, group=$field, sort=[$day, $time], hidden=true.
  async entries(name, need, { caption, word, count }) {
    const title = need.args[0], opt = need.named, fields = opt.fields?.entries ?? [["name", { k: "lit", v: "text" }]];
    // Alphy keys each row by its id: not blank and of no other row (ToolValueSchema). A field that a person types must not be that key.
    if ([...fields, ...(opt.show?.entries ?? [])].some(([field]) => field === "id")) throw new DslError("a field named id is the key of each row, which the host gives. Give the field a different name");
    // A Map, so that a field named "constructor" does not read a property of Object.
    const labels = new Map((opt.labels?.entries ?? []).map(([key, tree]) => [key, tree.v])), text = caption;
    const lines = [`$/${name} = ${show(opt.rows ?? { k: "list", items: [] })}`];
    // The rows are data for a deck, a chart, or a figure, and the list is not on the screen.
    if (opt.hidden?.v === true) return { lines, top: [] };
    const cells = [], marks = [], texts = [], tail = [], add = [], blank = [], reset = [], canAdd = opt.add?.v !== false, edit = opt.edit?.v === true;
    // icon=: one sign for each row, or a value of the row (C.2). A field that is the sign of the row is not a text of the row too.
    const icon = opt.icon, sign = icon?.k === "path" && !icon.abs ? icon.segs[0] : null, shown = opt.show?.entries ?? [];
    // icons= gives a field of the add form its icon, and tones= gives a value of show= its tone (C.8). Maps, as labels= is.
    const icons = new Map(opt.icons?.entries ?? []), tones = new Map(opt.tones?.entries ?? []);
    for (const key of tones.keys()) if (!shown.some(([value]) => value === key && value !== sign)) throw new DslError(`tones= names ${key}, and show= has no value of that name`);
    if (icon?.k === "path" && ![...fields, ...shown].some(([key]) => key === sign)) throw new DslError(`icon=${show(icon)} reads no field of the rows. Use a field of fields= or a name of show=`);
    if (icon?.k === "lit" && /^[A-Za-z]+$/.test(icon.v)) throw new DslError(`icon= of a list is an emoji, such as icon="🛒", and ${q(icon.v)} is not one`);
    // More than 2 check marks are circles of one weight in a second row (C.3).
    const many = fields.filter(([, kindTree]) => kindTree.v === "check").length > 2;
    const kindOf = (kindTree) => kindTree.k === "list" ? "choice" : ["number", "money", "percent"].includes(kindTree.v) ? "number" : ["check", "switch", "count", "date", "time", "picture"].includes(kindTree.v) ? kindTree.v : "text";
    const others = fields.filter(([field]) => field !== sign);
    // In a row with one text, a choice that the row does not change is the muted line under the title: as a label beside it, "Frutta e verdura" broke into two lines.
    const under = !edit && others.filter(([, kindTree]) => kindOf(kindTree) === "text").length === 1 ? others.find(([, kindTree]) => kindOf(kindTree) === "choice")?.[0] : undefined;
    let firstText = null;
    for (const [field, kindTree] of fields) {
      const choice = kindTree.k === "list" ? show(kindTree) : null, kind = kindOf(kindTree);
      // The field for a value that the person types or picks.
      const widget = { number: "NumberField", date: "DateField", time: "TimeField" }[kind] ?? "TextField";
      // The add form and each control of a row name the field by labels=, else by the readable form of the field name.
      const named = q(labels.get(field) ?? label(field));
      const pick = (text, bind, more = "") => `${choice && kindTree.items.length <= 4 ? "Segmented" : "Select"}(${text}, ${bind}, ${choice}${more})`;
      // The cell of the row: a check mark, a choice, a field that the person can change, a text of the title column, or a small label of a value.
      if (field === sign || field === under) /* the emoji of the row, or the line under its title */;
      else if (kind === "check") lines.push(`${name}_${field} = CheckBox(${named}, $${field}${many ? ', variant="circle", weight=1' : ""})`);
      else if (kind === "switch") lines.push(`${name}_${field} = Toggle(${named}, $${field})`);
      else if (kind === "count") {
        // A count in the row: a minus and a plus button around the number, and the person can also type it
        // (route steps-chart: seven day rows with a count of steps, and no way to give 8000 but by taps). Not below 0.
        lines.push(`${name}_${field}_minus = IconButton("Minus", action=set($${field}, max(0, $${field} - 1)), label=${q(word("Less"))})`, `${name}_${field} = NumberField(${named}, $${field}, min=0, step=1)`,
          `${name}_${field}_plus = IconButton("Plus", action=inc($${field}), label=${q(word("More"))})`);
        cells.push(`${name}_${field}_minus`);
      } else if (kind === "picture") lines.push(`${name}_${field} = Picture($${field})`);
      else if (kind === "choice" && edit) lines.push(`${name}_${field} = ${pick(named, "$" + field)}`);
      else if (edit && kind !== "choice") lines.push(`${name}_${field} = ${widget}(${named}, $${field}${kind === "text" ? ", weight=1" : ""})`);
      // The host's number and display emphasis are too large for a row (Task 11b-1: "1 kg" in two lines).
      else if (kind !== "text") lines.push(`${name}_${field} = Text($${field}, emphasis="label")`);
      else texts.push(field);
      if (kind === "text" && field !== sign) firstText ??= field;
      if (field !== sign && field !== under && !(kind === "text" && !edit)) (kind === "check" ? marks : cells).push(`${name}_${field}`);
      if (kind === "count") cells.push(`${name}_${field}_plus`);
      if (!canAdd) continue;
      // A new row starts with no check mark, with its switch on, and with no picture.
      if (["check", "switch", "picture"].includes(kind)) { blank.push(`${field}: ${{ check: "false", switch: "true", picture: '""' }[kind]}`); continue; }
      // A count has a number field in the add form and its stepper in the row. final2: with the stepper only, three apps needed 30 taps for a count of 30.
      const empty = { number: "0", count: "0", date: "today()", time: '"08:00"' }[kind] ?? (kind === "choice" ? show(kindTree.items[0]) : '""');
      // A choice of more than six options is a Select, which shows an icon. A shorter one is a segmented control, which shows none (run.js choice).
      const iconed = icons.has(field) && (kind !== "choice" || kindTree.items.length > 6) ? `, icon=${show(icons.get(field))}` : "";
      const typed = kind === "count" ? `NumberField(${named}, $/${name}_new_${field}, min=0, step=1${iconed})` : `${widget}(${named}, $/${name}_new_${field}${iconed})`;
      lines.push(`$/${name}_new_${field} = ${empty}`, `${name}_new_${field} = ${kind === "choice" ? pick(named, `$/${name}_new_${field}`, iconed) : typed}`);
      if (iconed) icons.delete(field);
      add.push(`${name}_new_${field}`);
      blank.push(`${field}: $/${name}_new_${field}`);
      if (["text", "number", "count"].includes(kind)) reset.push(`set($/${name}_new_${field}, ${empty})`);
    }
    // Each name of icons= that is left has no field of the add form that shows an icon.
    for (const key of icons.keys()) throw new DslError(`icons= names ${key}, whose field of the add form shows no icon. A text, number, date, or time field shows one, and a choice of more than six options does too`);
    // A value for each row, from the fields of that row.
    for (const [key, formula] of shown) if (key !== sign) { lines.push(`${name}_${key} = Badge(${show(formula)}${tones.has(key) ? `, tone=${show(tones.get(key))}` : ""})`); cells.push(`${name}_${key}`); }
    // The arrows become the reorder of the list, which Alphy draws with its own words (NativeRecordReorder.kt), so they need no caption.
    if (opt.reorder?.v === true) { lines.push(`${name}_up = IconButton("ArrowUp", action=move(-1))`, `${name}_down = IconButton("ArrowDown", action=move(1))`); tail.push(`${name}_up`, `${name}_down`); }
    if (opt.remove?.v !== false) {
      // With confirm= the delete button asks first. The row that waits for the answer is known by its id.
      if (opt.confirm) lines.push(`$/${name}_drop = ""`, `${name}_remove = IconButton("Trash", action=set($/${name}_drop, $id), label=${q(word("Remove"))})`,
        `${name}_yes = Button(${text("yes", "Yes")}, action=[remove($/${name}, $id == $/${name}_drop), set($/${name}_drop, "")])`, `${name}_no = Button(${text("no", "No")}, action=set($/${name}_drop, ""))`,
        `${name}_confirm = Dialog(${show(opt.confirm)}, [Row([${name}_yes, ${name}_no])], visible=$/${name}_drop != "")`);
      else lines.push(`${name}_remove = IconButton("Trash", action=remove(), label=${q(word("Remove"))})`);
      tail.push(`${name}_remove`);
    }
    // The row (C.1): the emoji, the title with a muted line under it for each further text, the value cells, the check marks, and the remove button.
    // The emoji is named by the title of the list for a person who does not see it.
    if (icon) lines.push(`${name}_emoji = Emoji(${show(icon)}, size="small", label=${show(title)})`);
    if (under) texts.push(under);
    lines.push(...texts.map((field, index) => `${name}_${field} = Text($${field}${texts.length === 1 ? ", weight=1" : index ? ', tone="muted"' : ""})`));
    if (texts.length > 1) lines.push(`${name}_main = Column([${texts.map((field) => `${name}_${field}`).join(", ")}], weight=1, gap="none")`);
    const head = [...(icon ? [`${name}_emoji`] : []), ...(texts.length > 1 ? [`${name}_main`] : texts.map((field) => `${name}_${field}`)), ...cells];
    // Many check marks get a row of their own under the other cells. The host draws each row of a list on its own card, so the template has no card.
    lines.push(many ? `${name}_row = Column([Row([${[...head, ...tail].join(", ")}]), Row([${marks.join(", ")}], gap="none")])` : `${name}_row = Row([${[...head, ...marks, ...tail].join(", ")}])`);
    let source = `$/${name}`;
    if (opt.filter) source = `filter(${source}, ${show(opt.filter)})`;
    if (opt.sort) source = `sort(${source}, ${show(opt.sort)}${opt.order ? ", " + show(opt.order) : ""})`;
    // With no emptyText= the list has no text of its own, and Alphy shows its own word for an empty list in the language of the app.
    lines.push(`${name}_list = List(${source}, ${name}_row${opt.group ? `, group=${show(opt.group)}` : ""}${opt.emptyText ? `, empty=${show(opt.emptyText)}` : ""})`);
    const parts = [`${name}_list`];
    if (opt.remove?.v !== false && opt.confirm) parts.push(`${name}_confirm`);
    if (canAdd) {
      const sheet = opt.sheet?.v === true, adds = `[${[`append($/${name}, {${blank.join(", ")}})`, ...reset, ...(sheet ? [`set($/${name}_adding, false)`] : [])].join(", ")}]`;
      // The add button is enabled at the start (C.4). A press with an empty first text field changes nothing, and a sheet stays open.
      lines.push(`${name}_add = Button(${text("addText", "Add")}, action=${firstText ? `when(len(trim($/${name}_new_${firstText})) > 0, ${adds})` : adds}, icon="Plus")`);
      // With sheet=true the add form is a dialog that a button opens. The add button closes it, and so does the close button that Alphy
      // draws in each sheet with its own word (NativeToolUi.kt, bottomSheet), so the part writes no close button of its own.
      if (sheet) lines.push(`$/${name}_adding = false`, `${name}_open = Button(${text("addText", "Add")}, action=set($/${name}_adding, true), icon="Plus")`,
        `${name}_addRow = Dialog(${text("addText", "Add")}, [${[...add, `${name}_add`].join(", ")}], visible=$/${name}_adding)`);
      // The fields stand one below the other, and the add button is on its own line under them.
      else lines.push(`${name}_addRow = Column([${[...add, `${name}_add`].join(", ")}])`);
      parts.push(...(sheet ? [`${name}_open`] : []), `${name}_addRow`);
    }
    lines.push(`${name}_view = Section(${show(title)}, [${parts.join(", ")}])`);
    if (opt.total && !count) throw new DslError(`total= joins the shown rows and all rows of the hero count, and this list has no hero count. A list has one when it is the only main element of the app. Remove total=`);
    if (!count) return { lines, top: [`${name}_view`] };
    // The only main element of the app is this list (C.6): a hero card with the count of its shown rows comes first. A sign of each row gives no emoji there.
    // total="of" shows all rows too: "12 of 12".
    const counted = opt.countText ? show(opt.countText) : show(title), value = opt.total ? `text(len(${source})) + " " + ${show(opt.total)} + " " + text(len($/${name}))` : `len(${source})`;
    lines.push(`${name}_hero = Card([${icon?.k === "lit" ? `Emoji(${show(icon)}, size="large", label=${counted}), ` : ""}Metric(${counted}, ${value}, emphasis="display")], variant="hero")`);
    return { lines, top: [`${name}_view`], lead: [`${name}_hero`] };
  },

  // need line = chart("Steps", $/days, $day, $steps, kind="line"): one bar or point for each row of a list.
  // Alphy shows the label of a chart over it, so the chart has no section with the same title (tabs-chart.png: the title showed two times).
  async chart(name, need) {
    const [title, list, point, value] = need.args;
    return { lines: [`${name}_chart = Chart(${show(list)}, ${show(point)}, ${show(value)}, kind=${show(need.named.kind ?? { k: "lit", v: "bar" })}, label=${show(title)})`], top: [`${name}_chart`] };
  },

  // need quiz = deck("Quiz", $/words, front=$word, back=$meaning, mark=$known): one row at a time. A tap shows the back, then yes or no goes to the next row.
  async deck(name, need, { caption }) {
    const [title, list] = need.args, opt = need.named, text = caption;
    // A field of the row in front, such as $word, is a field of the row that the deck shows now.
    const now = (tree) => show(rebase(tree, `${name}_now`));
    // picture=$field: a picture on each card, from the description in that field of the row.
    const picture = opt.picture ? [`${name}_picture = Picture(text(${now(opt.picture)}))`] : [], inCard = opt.picture ? `${name}_picture, ` : "";
    // The front of a card that the person must know is a large centered text, as in the baseline flashcards (Task 11b-2b, A.2). The back is centered under it.
    const front = `${name}_front = Text(text(${now(opt.front)}), emphasis="display", align="center")`;
    // The hero card holds the card and at most its filled button. A field or an outlined button stands under it: on a hero card its outline shows no contrast in the dark theme.
    // With answer= the person types the answer. The check button says right or wrong, and a right answer counts one point. The check button goes away after its tap, so a point counts once.
    if (opt.answer) return {
      lines: [`$/${name}_i = 0`, `$/${name}_typed = ""`, `$/${name}_checked = false`, `$/${name}_points = 0`,
        `$/${name}_now := at(${show(list)}, $/${name}_i)`, `$/${name}_end := $/${name}_i >= len(${show(list)})`,
        `$/${name}_right := lower(trim(text($/${name}_typed))) == lower(trim(text(${now(opt.answer)})))`,
        ...picture, front,
        `${name}_field = TextField(${text("field", "Your answer")}, $/${name}_typed, enabled=!$/${name}_checked)`,
        `${name}_check = Button(${text("check", "Check")}, action=[when($/${name}_right, inc($/${name}_points)), set($/${name}_checked, true)], visible=!$/${name}_checked)`,
        `${name}_result = Notice($/${name}_right ? ${text("right", "Right")} : ${text("wrong", "Wrong")} + ": " + text(${now(opt.answer)}), visible=$/${name}_checked)`,
        `${name}_next = Button(${text("next", "Next")}, action=[inc($/${name}_i), set($/${name}_typed, ""), set($/${name}_checked, false)], visible=$/${name}_checked)`,
        `${name}_card = Card([${inCard}${name}_front], variant="hero", visible=!$/${name}_end)`,
        `${name}_reply = Column([${name}_field, ${name}_check, ${name}_result, ${name}_next], visible=!$/${name}_end)`,
        `${name}_again = Button(${text("again", "Again")}, action=[set($/${name}_i, 0), set($/${name}_points, 0), set($/${name}_typed, ""), set($/${name}_checked, false)])`,
        `${name}_endCard = Card([Heading(${text("done", "Done")}), ${name}_again], visible=$/${name}_end)`,
        `${name}_score = Metric(${text("points", "Points")}, $/${name}_points)`,
        `${name}_view = Section(${show(title)}, [${name}_card, ${name}_reply, ${name}_endCard, ${name}_score])`],
      top: [`${name}_view`],
    };
    // Without back= the deck is pages: no hidden side and no count. A next and a back button, and an end after the last row.
    // Back is outlined and forward is filled, both explicit, as in the steps and a lesson: Alphy fills the first available button with no variant.
    if (!opt.back) return {
      lines: [`$/${name}_i = 0`, `$/${name}_now := at(${show(list)}, $/${name}_i)`, `$/${name}_end := $/${name}_i >= len(${show(list)})`,
        ...picture, `${name}_front = Heading(text(${now(opt.front)}))`,
        `${name}_prev = Button(${text("prev", "Back")}, action=dec($/${name}_i), enabled=$/${name}_i > 0, variant="outlined")`, `${name}_next = Button(${text("next", "Next")}, action=inc($/${name}_i), variant="filled")`,
        `${name}_card = Card([${inCard}${name}_front], variant="hero", visible=!$/${name}_end)`,
        `${name}_turn = Row([${name}_prev, ${name}_next], visible=!$/${name}_end)`,
        `${name}_again = Button(${text("again", "Again")}, action=set($/${name}_i, 0))`,
        `${name}_endCard = Card([Heading(${text("done", "Done")}), ${name}_again], visible=$/${name}_end)`,
        `${name}_view = Section(${show(title)}, [${name}_card, ${name}_turn, ${name}_endCard])`],
      top: [`${name}_view`],
    };
    const next = `inc($/${name}_i), set($/${name}_open, false)`;
    return {
      lines: [`$/${name}_i = 0`, `$/${name}_open = false`, `$/${name}_yes = 0`, `$/${name}_no = 0`,
        `$/${name}_now := at(${show(list)}, $/${name}_i)`, `$/${name}_end := $/${name}_i >= len(${show(list)})`,
        ...picture, front, `${name}_back = Text(text(${now(opt.back)}), visible=$/${name}_open, emphasis="headline", align="center")`,
        `${name}_show = Button(${text("show", "Show")}, action=set($/${name}_open, true), visible=!$/${name}_open)`,
        `${name}_yesButton = Button(${text("yes", "Yes")}, action=[inc($/${name}_yes), ${opt.mark ? `set(${now(opt.mark)}, true), ` : ""}${next}], visible=$/${name}_open, icon="Check", variant="filled")`,
        `${name}_noButton = Button(${text("no", "No")}, action=[inc($/${name}_no), ${next}], visible=$/${name}_open, variant="outlined")`,
        `${name}_card = Card([${inCard}${name}_front, ${name}_back, ${name}_show], variant="hero", visible=!$/${name}_end)`,
        `${name}_mark = Row([${name}_yesButton, ${name}_noButton], visible=$/${name}_open)`,
        `${name}_again = Button(${text("again", "Again")}, action=[set($/${name}_i, 0), set($/${name}_yes, 0), set($/${name}_no, 0), set($/${name}_open, false)])`,
        `${name}_endCard = Card([Heading(${text("done", "Done")}), ${name}_again], visible=$/${name}_end)`,
        `${name}_counts = Grid([Metric(${text("yes", "Yes")}, $/${name}_yes), Metric(${text("no", "No")}, $/${name}_no)], columns=2)`,
        `${name}_view = Section(${show(title)}, [${name}_card, ${name}_mark, ${name}_endCard, ${name}_counts])`],
      top: [`${name}_view`],
    };
  },

  // need setup = steps(["Genres", "Pages"], [[genres], [pages, week]], finish="Start"): other needs in groups, one group at a time.
  async steps(name, need, { tops, caption }) {
    const [titles, groups] = need.args, opt = need.named, text = caption, last = groups.items.length - 1;
    const lines = [`$/${name}_step = 0`, `$/${name}_done = false`, `${name}_title = Heading(at(${show(titles)}, $/${name}_step))`];
    groups.items.forEach((group, index) => lines.push(`${name}_step${index} = Column([${group.items.flatMap((member) => tops(memberName(member))).join(", ")}], visible=$/${name}_step == ${index})`));
    lines.push(`${name}_back = Button(${text("back", "Back")}, action=dec($/${name}_step), enabled=$/${name}_step > 0, variant="outlined")`,
      `${name}_next = Button(${text("next", "Next")}, action=inc($/${name}_step), visible=$/${name}_step < ${last}, variant="filled")`,
      `${name}_finish = Button(${text("finish", "Done")}, action=set($/${name}_done, true), visible=$/${name}_step == ${last}, variant="filled")`,
      `${name}_doneNote = Notice(${text("done", "Done")}, visible=$/${name}_done, tone="success")`,
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
        hero === name ? `${name}_view = Card([${ring}], variant="hero")` : `${name}_view = Section(${show(title)}, [${ring}])`],
      top: [`${name}_view`],
    };
  },

  // need cups = counter("Cups", 0, step=1, min=0): a section of an outlined less button, the value, and a filled more button (lessText=, moreText=).
  // need ml = counter("Water", 0, step=250, addText="Add a cup", icon="Cup"): one filled button that adds the step, and a text button that resets the value.
  async counter(name, need, { caption }) {
    const [title, value] = need.args, opt = need.named, step = opt.step ? show(opt.step) : "1", start = show(value ?? { k: "lit", v: 0 });
    const down = opt.min ? `set($/${name}, max(${show(opt.min)}, $/${name} - ${step}))` : `dec($/${name}, ${step})`;
    const up = opt.max ? `set($/${name}, min(${show(opt.max)}, $/${name} + ${step}))` : `inc($/${name}, ${step})`;
    const lines = [`$/${name} = ${start}`];
    // Each form of the counter uses its own captions and icon (G.4).
    const unused = (opt.addText ? ["lessText", "moreText"] : ["icon", "resetText"]).filter((key) => opt[key]).map((key) => key + "=");
    if (unused.length) throw new DslError(`a counter with ${opt.addText ? "addText= has one add button and a reset button" : "no addText= has a less and a more button"}, and it does not use ${unused.join(" or ")}. Remove ${unused.join(" and ")}${opt.addText ? "" : ", or give addText="}`);
    if (opt.addText) {
      lines.push(`${name}_plus = Button(${show(opt.addText)}, action=${up}, icon=${opt.icon ? show(opt.icon) : q("Plus")}, variant="filled")`,
        `${name}_reset = Button(${caption("resetText", "Reset")}, action=set($/${name}, ${start}), icon="Refresh", variant="text")`, `${name}_view = Row([${name}_plus, ${name}_reset])`);
    } else {
      lines.push(`${name}_minus = Button(${caption("lessText", "Less")}, action=${down}, icon="Minus", variant="outlined")`,
        `${name}_value = Text($/${name}, emphasis="display", align="center", weight=1)`, `${name}_plus = Button(${caption("moreText", "More")}, action=${up}, icon="Plus", variant="filled")`,
        `${name}_view = Section(${show(title)}, [Row([${name}_minus, ${name}_value, ${name}_plus])])`);
    }
    return { lines, top: [`${name}_view`] };
  },

  // need tea = timer("Tea", $/kind == "green" ? 120 : 180, alert=true): one phase with an alert is the host's own countdown, a Timer line.
  // need set = timer("Workout", 40, rounds=8, rest=20): each round is a work phase and then a rest phase. $/NAME counts the work phases that ended.
  // A timer with more than one phase, or with no alert, is plain lines with start, pause, and reset. The host moves it with its clock (App.tick).
  // Evidence: 4 of Alphy's 45 corpus cases are timers (workout-de, tea-tr, pomodoro-zh, eggs-it). Only the passing of time is the host's.
  async timer(name, need, { caption }) {
    const [title, seconds] = need.args, opt = need.named, text = caption, at = (part) => `$/${name}_${part}`;
    if (foreground(need)) {
      const unshown = ["start", "pause", "reset", "done"].filter((key) => opt[key]);
      // A timer card is the hero of the screen, as in the baseline workout app (Task 11b-2b, A.1).
      return { lines: [`${name}_timer = Timer(${show(title)}, ${show(seconds ?? { k: "lit", v: 60 })})`, `${name}_view = Card([${name}_timer], variant="hero")`], top: [`${name}_view`],
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
      ...(many ? [`${name}_phaseText = Text((${at("resting")} ? ${text("restText", "Rest")} : ${text("workText", "Work")}) + " " + text(${at("round")}) + " / " + text(${opt.rounds ? show(opt.rounds) : 1}), tone="muted")`] : []),
      `${name}_bar = Progress(${at("spent")}, ${at("phase")}, label=${show(title)})`,
      `${name}_start = Button(${text("start", "Start")}, action=set(${at("on")}, true), visible=!${at("on")} && !${at("over")}, icon="Play", variant="filled")`,
      // While the timer runs, Pause is its main action. A hero card holds no outlined button: its outline shows no contrast in the dark theme.
      `${name}_pause = Button(${text("pause", "Pause")}, action=set(${at("on")}, false), visible=${at("on")}, icon="Pause", variant="filled")`,
      `${name}_reset = Button(${text("reset", "Reset")}, action=[set(${at("on")}, false), set(${at("spent")}, 0), set(${at("round")}, 1), set(${at("resting")}, false), set(${at("over")}, false)], enabled=${at("on")} || ${at("spent")} > 0 || ${at("round")} > 1 || ${at("resting")} || ${at("over")}, icon="Refresh", variant="text")`,
      `${name}_doneNote = Notice(${text("done", "Time is up")}, visible=${at("over")})`];
    const parts = [`${name}_time`, ...(many ? [`${name}_phaseText`] : []), `${name}_bar`, `Row([${name}_start, ${name}_pause, ${name}_reset])`, `${name}_doneNote`];
    // The run-time draws the bar and moves the clock while the app is open. No sound or vibration comes at the end of a phase.
    return { lines: [...lines, `${name}_view = Card([${parts.join(", ")}], variant="hero")`], top: [`${name}_view`],
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
  // The drawing that is the main element is in a hero card. Its surface keeps the name NAME_view.
  async drawing(name, need, { hero }) {
    const [title, width, height, ...shapes] = need.args;
    if (!height || width.k === "list" || height.k === "list") throw new DslError("drawing needs a width and a height before the shapes: drawing(label, 320, 240, [shapes])");
    // Shapes with no list around them are still the shapes.
    const list = shapes.length === 1 && shapes[0].k === "list" ? show(shapes[0]) : `[${shapes.map(show).join(", ")}]`, lines = [`${name}_view = Drawing(${[title, width, height].map(show).join(", ")}, ${list})`];
    if (hero !== name) return { lines, top: [`${name}_view`] };
    return { lines: [...lines, `${name}_card = Card([${name}_view], variant="hero")`], top: [`${name}_card`] };
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
  async model3d(name, need, { caption, hero }) {
    const [title, bodies] = need.args, opt = need.named, text = caption, at = (field) => `$/${name}/${field}`;
    const turn = opt.turn?.v !== false, chips = (opt.parts?.entries ?? []).map(([id, caption]) => [`${name}_part_${id}`, q(id), show(caption)]), tools = [];
    const lines = [`$/${name} = {selected: "", exploded: false, turn: 30}`,
      `${name}_model = Model3d(${show(title)}, ${show(bodies)}${turn ? `, ${at("turn")}` : ""}, exploded=${at("exploded")}, selected=${at("selected")})`,
      ...chips.map(([key, id, caption]) => `${key} = Button(${caption}, action=set(${at("selected")}, ${at("selected")} == ${id} ? "" : ${id}), selected=${at("selected")} == ${id}, variant=${at("selected")} == ${id} ? "filled" : "tonal")`)];
    if (turn) { lines.push(`${name}_left = IconButton("ArrowLeft", action=dec(${at("turn")}, 30), label="−30°")`, `${name}_right = IconButton("ArrowRight", action=inc(${at("turn")}, 30), label="+30°")`); tools.push(`${name}_left`, `${name}_right`); }
    if (opt.explode?.v !== false) { lines.push(`${name}_explode = Button(${at("exploded")} ? ${text("joinText", "Put together")} : ${text("explodeText", "Take apart")}, action=toggle(${at("exploded")}), variant="outlined")`); tools.push(`${name}_explode`); }
    const rows = [[`${name}_chips`, chips.map(([key]) => key)], [`${name}_tools`, tools]].filter(([, row]) => row.length).map(([key, row]) => [key, `Row([${row.join(", ")}])`]);
    if (hero !== name) return { lines: [...lines, `${name}_view = Card([${[`${name}_model`, ...rows.map(([, row]) => row)].join(", ")}])`], top: [`${name}_view`] };
    // The model that is the main element is in a hero card (A.8), and its chips and buttons stand under the card: a tonal or an outlined button on a hero card shows no container in the dark theme.
    return { lines: [...lines, `${name}_view = Card([${name}_model], variant="hero")`, ...rows.map(([key, row]) => `${key} = ${row}`)], top: [`${name}_view`, ...rows.map(([key]) => key)] };
  },

  // need focus = card("Title", $/rover/selected == "camera" ? "The camera" : "The rover", eyebrow="In focus", icon="Layers"): a card of text with a muted body.
  // The title and the text can be formulas.
  async card(name, need) {
    const [title, text] = need.args, made = [], icon = need.named.icon;
    if (need.named.eyebrow) made.push(["eyebrow", `Text(${show(need.named.eyebrow)}, variant="eyebrow")`]);
    made.push(["title", `Heading(${show(title)})`]);
    if (text) made.push(["text", `Text(${show(text)}, tone="muted")`]);
    return { lines: [...made.map(([key, line]) => `${name}_${key} = ${line}`), `${name}_view = Card([${made.map(([key]) => `${name}_${key}`).join(", ")}]${icon ? `, icon=${show(icon)}` : ""})`], top: [`${name}_view`] };
  },

  // need source = link("Apollo 17 photo", "https://science.nasa.gov/...", note="Open the source"): a link to a web page. The prototype only draws it.
  async link(name, need) {
    const [title, url] = need.args;
    return { lines: [`${name}_view = Link(${show(title)}, ${show(url)}${need.named.note ? `, ${show(need.named.note)}` : ""})`], top: [`${name}_view`] };
  },

  // need steps = lesson([{title: "Look", text: "..."}, {title: "Test", text: "..."}], next="Next", back="Back", stepText="Step")
  // One step at a time, with "Step · 1 / 2" and a progress bar. $/NAME is the number of the step, from 1.
  async lesson(name, need, { caption }) {
    const steps = need.args[0], opt = need.named, text = caption, count = `len($/${name}_steps)`, dots = steps.k === "list" && steps.items.length <= 12;
    const lines = [`$/${name} = 1`, `$/${name}_steps := ${show(steps)}`, `$/${name}_now := at($/${name}_steps, $/${name} - 1)`,
      // A lesson of at most 12 steps shows one dot for each step (Alphy draws at most 12 dots), and a longer one a bar.
      `${name}_bar = Progress($/${name}, ${count}${dots ? ', variant="dots"' : ""}, label=${text("stepText", "Step")})`,
      `${name}_title = Heading(text($/${name}_now/title))`, `${name}_text = Text(text($/${name}_now/text))`];
    // Alphy writes "Step: 1 / 3" under the dots, so only a bar needs the count line over it (lesson.png: the count showed two times).
    if (!dots) lines.push(`${name}_count = Text(${text("stepText", "Step")} + " · " + text($/${name}) + " / " + text(${count}), variant="eyebrow")`);
    const parts = [...(dots ? [] : [`${name}_count`]), `${name}_bar`, `${name}_title`, `${name}_text`];
    // One step has no next step, so it gets no buttons: a button that is never active is a fault of the smoke run.
    if (steps.k !== "list" || steps.items.length > 1) {
      lines.push(`${name}_back = Button(${text("back", "Back")}, action=dec($/${name}), enabled=$/${name} > 1, variant="outlined")`, `${name}_next = Button(${text("next", "Next")}, action=inc($/${name}), enabled=$/${name} < ${count}, variant="filled")`);
      parts.push(`Row([${name}_back, ${name}_next])`);
    }
    return { lines: [...lines, `${name}_view = Card([${parts.join(", ")}])`], top: [`${name}_view`] };
  },

  // need ask = question("Is the Moon a star?", options=["Yes", "No"], right="No", rightText="Right", wrongText="Not yet")
  // One button for each answer, and a text that says right or wrong. $/NAME is true while the right answer is the chosen one.
  async question(name, need, { caption }) {
    const opt = need.named, options = opt.options?.k === "list" ? opt.options.items : [], text = caption;
    if (options.length < 2 || !opt.right) throw new DslError('question needs options and the right one: question("Text", options=["Yes", "No"], right="No")');
    if (!options.some((option) => show(option) === show(opt.right))) throw new DslError(`right=${show(opt.right)} is not one of the options`);
    const at = `$/${name}_answer`, keys = options.map((_, index) => `${name}_opt${index}`);
    return {
      lines: [`${at} = ""`, `$/${name} := ${at} == ${show(opt.right)}`, `${name}_text = Heading(${show(need.args[0])})`,
        ...options.map((option, index) => `${keys[index]} = Button(${show(option)}, action=set(${at}, ${show(option)}), selected=${at} == ${show(option)})`),
        `${name}_result = Notice($/${name} ? ${text("rightText", "Right")} : ${text("wrongText", "Not yet")}, visible=${at} != "", tone=$/${name} ? "success" : "warning")`,
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
 * The named options of each part. An option that is not here is a fault of the need, never an option that the part drops with no word.
 * button gives each option to its Button, whose own check names each one that a Button does not take.
 */
const OPTIONS = {
  input: ["options", "min", "max", "step", "icon"], figure: ["decimals", "example", "icon", "note", "goal", "goalText", "unit"],
  entries: ["fields", "labels", "rows", "hidden", "add", "edit", "remove", "confirm", "yes", "no", "emptyText", "addText", "show", "reorder", "filter", "sort", "order", "group", "sheet", "icon", "countText", "icons", "tones", "total"],
  chart: ["kind"], deck: ["front", "back", "mark", "answer", "picture", "field", "check", "right", "wrong", "next", "again", "done", "points", "prev", "show", "yes", "no"],
  steps: ["back", "next", "finish", "done"], tabs: ["title"], progress: [], counter: ["step", "min", "max", "lessText", "moreText", "addText", "icon", "resetText"],
  timer: ["alert", "rounds", "rest", "start", "pause", "reset", "done", "workText", "restText"], picture: ["found", "caption", "credit", "tag"], drawing: [],
  model3d: ["turn", "parts", "explode", "joinText", "explodeText"], card: ["eyebrow", "icon"], link: ["note"], lesson: ["next", "back", "stepText"],
  question: ["options", "right", "rightText", "wrongText"], content: [], hole: [],
};
/** The options that each kind of input uses. A kind that is not here is a number field or a slider. */
const INPUT_USES = { choice: ["options", "icon"], choices: ["options", "max"], toggle: [], text: ["icon"], date: ["icon"], time: ["icon"] };
/**
 * The type of the value of an option (G.4). A part reads a flag once when it builds the screen, so a flag is true or false in the line.
 * A number can be a formula, which the kit cannot check here.
 */
const FLAGS = new Set(["sheet", "edit", "add", "remove", "hidden", "reorder"]), NUMBERS = new Set(["step", "decimals"]);
const literal = (tree) => tree.k === "lit" || tree.k === "list" || tree.k === "map";
function typeFault(key, tree) {
  if (FLAGS.has(key) && !(tree.k === "lit" && typeof tree.v === "boolean")) return `${key}= needs true or false, and ${show(tree)} ${literal(tree) ? "is neither" : "is a formula. The part reads it once when it builds the screen"}`;
  if (NUMBERS.has(key) && literal(tree) && typeof tree.v !== "number") return `${key}= needs a number, and ${show(tree)} is not one`;
  return null;
}
/** The need whose part wrote each line, by the name of the line. */
const needsOf = (file) => new Map([...(file.partLines ?? [])].flatMap(([need, keys]) => keys.map((key) => [key, need])));
/** The name of a line in a fault: "need NAME" for a line that a part wrote, so that each fault names a line that the author wrote. */
const faultName = (file, partOf = needsOf(file)) => (line) => (file.get(line)?.origin === "part" && partOf.has(line) ? `need ${partOf.get(line)}` : line);

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
  // A plan with no figure or progress has its first drawing or 3D model as the main element (Task 11b-2b, A.8). An author's root places
  // the drawing itself (it can name NAME_view), so then no drawing or model is the main element, as no list is.
  const kinds = (names) => needs.filter((entry) => names.includes(entry.tree.name)).map((entry) => entry.key);
  const counted = kinds(["figure", "progress"]), candidates = counted.length ? counted : file.open("root") ? kinds(["drawing", "model3d"]) : [];
  const hero = candidates.length ? await choose("main element", candidates, candidates[0]) : null;
  // A part that holds other needs, such as steps, is built after them. Its place on the screen stays the place of its line.
  const holder = (entry) => ["steps", "tabs"].includes(entry.tree.name);
  // The places of the screen: each group of a holder, and the root with the needs that no holder groups, each in its order.
  const grouped = needs.filter(holder).flatMap((entry) => (entry.tree.args[1]?.items ?? []).map((group) => (group.items ?? []).map(memberName)));
  // A plan with no figure, progress, drawing, timer, or model: the first list that shows and that no holder groups gets a hero card with its count
  // at the root (C.6). An author's root gets none.
  const listHero = !needs.some((entry) => ["figure", "progress", "drawing", "timer", "model3d"].includes(entry.tree.name)) && file.open("root")
    ? needs.find((entry) => entry.tree.name === "entries" && entry.tree.named.hidden?.v !== true && !grouped.flat().includes(entry.key))?.key : undefined;
  const places = [needs.map((entry) => entry.key).filter((key) => !grouped.flat().includes(key)), ...grouped];
  // The figures that are not the main element are a set in each place (E.2, fix round 1): two that stand one after the other are one grid of two,
  // three or more are rows of one size, and one stays a metric. A figure keeps its place, and a figure in two places is in no set.
  const once = (key) => places.filter((keys) => keys.includes(key)).length === 1;
  const roles = new Map(), figure = (key) => key !== hero && once(key) && needs.some((entry) => entry.key === key && entry.tree.name === "figure");
  for (const members of places.map((keys) => keys.filter((key) => key !== hero))) {
    const set = members.filter(figure);
    if (set.length > 2) set.forEach((key) => roles.set(key, "row"));
    else if (set.length === 2 && members.indexOf(set[1]) === members.indexOf(set[0]) + 1) roles.set(set[0], `grid ${set[1]}`).set(set[1], "held");
  }
  const roleOf = (key) => roles.get(key) ?? "";
  const out = [], top = [], fresh = [], held = new Set(), heads = [];
  const place = new Map();
  // The captions that a part writes by itself are in the language of the app (words.js). A language outside the table gets the English
  // words and one note that names each option of a need that took an English caption, so that the author can give it.
  const said = file.get("app")?.tree?.named?.lang, lang = said?.k === "lit" ? said.v : undefined, english = new Map();
  for (const entry of [...needs.filter((entry) => !holder(entry)), ...needs.filter(holder)]) {
    const part = entry.tree.k === "call" && Object.hasOwn(PARTS, entry.tree.name) ? PARTS[entry.tree.name] : null;
    if (!part) { file.partFaults.push({ line: null, text: entry.raw, message: `no part for need kind ${entry.tree.name ?? "?"}` }); continue; }
    const options = Object.hasOwn(OPTIONS, entry.tree.name) ? OPTIONS[entry.tree.name] : null;
    for (const key of options ? Object.keys(entry.tree.named).filter((key) => !options.includes(key)) : []) {
      file.partFaults.push({ line: null, text: entry.raw, message: `need ${entry.key}: ${entry.tree.name} has no option ${key}. ${options.length ? `Its options are ${options.join(", ")}` : "It takes no option"}` });
    }
    for (const [key, tree] of options ? Object.entries(entry.tree.named).filter(([key]) => options.includes(key)) : []) {
      const fault = typeFault(key, tree);
      if (fault) file.partFaults.push({ line: null, text: entry.raw, message: `need ${entry.key}: ${fault}` });
    }
    let built, parsed;
    try {
      const word = (text, option) => {
        if (option && !hasWords(lang)) english.set(entry.key, new Set(english.get(entry.key)).add(option));
        return wordOf(lang, text);
      };
      const caption = (option, text) => (entry.tree.named[option] ? show(entry.tree.named[option]) : q(word(text, option)));
      built = await part(entry.key, entry.tree, { choose, hero, word, caption, count: entry.key === listHero, role: roleOf(entry.key), tops: (other) => { if (!file.tops.has(other)) throw new DslError(`${entry.tree.name} names ${other}, and no need has that name`); return file.tops.get(other); } });
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
    const control = (made) => made.kind === "component" && made.tree.k === "call" && (made.tree.named.action || ["Button", "IconButton", "TextField", "NumberField", "Slider", "RangeSlider", "Toggle", "CheckBox", "Segmented", "Select", "DateField", "TimeField"].includes(made.tree.name));
    (file.made ??= new Map()).set(entry.key, parsed.filter(control).map((made) => made.key));
    // Each line of the part, so that a need that changes or goes away leaves no line of its earlier form behind.
    const keys = parsed.map((made) => made.key);
    drop((file.partLines.get(entry.key) ?? []).filter((line) => !keys.includes(line)));
    file.partLines.set(entry.key, keys);
    (built.holds ?? []).forEach((other) => held.add(other));
    heads.push(...(built.lead ?? []));
    place.set(entry.key, built.top);
    // A change of the language of the app, or of the place of a figure, writes the part again.
    const made = `${entry.raw}${entry.key === hero || entry.key === listHero}${lang}${roleOf(entry.key)}`;
    if (file.expanded.get(entry.key) === made) continue;
    if (!file.expanded.has(entry.key)) fresh.push(entry.key);
    file.expanded.set(entry.key, made);
    // A line that an author wrote or deleted belongs to the author. The part leaves it as it is.
    out.push(...built.lines.filter((line, index) => file.open(parsed[index].key)));
  }
  if (english.size) {
    const options = [...english].map(([key, said]) => `need ${key} ${[...said].map((option) => `${option}=`).join(", ")}`).join("; ");
    file.partNotes.push(`the kit has no words of the language "${lang}", so the parts wrote these captions in English: ${options}. Give each one in the language of the app`);
  }
  // The screen: the main element first, then each need in the order of its line. A need that a step holds is not there twice.
  for (const entry of needs) if (place.has(entry.key) && !held.has(entry.key)) (entry.key === hero ? top.unshift.bind(top) : top.push.bind(top))(...place.get(entry.key));
  top.unshift(...heads);
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

return { rules, PART_KINDS, needsOf, faultName, expand };
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
  // Each use of a control starts from a restore, and each look at the screen is new work too, so each one has the budget of work of one event (core.js STEPS).
  const restore = (saved) => { app.steps = 0; app.state = JSON.parse(saved.text); clock.now = saved.now; app.recompute(); };
  const screen = () => { app.steps = 0; return app.tree(); };
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
    walkNode(screen(), "0", true, null);
    if (usable) out.forEach((control) => reached.add(control.key));
    return out;
  };
  const start = save(), all = controls(false).map((control) => control.key);
  /** The paths of the app that an expression reads, such as "name" for $/name, also through each formula that it reads. */
  const reads = (tree, out = new Set()) => {
    walk(tree, (node) => {
      const path = node.k === "path" && node.abs ? node.segs.join("/") : null;
      if (path === null || out.has(path)) return;
      out.add(path);
      const formula = file.get("$/" + node.segs[0]);
      if (formula?.kind === "formula") reads(formula.tree, out);
    });
    return out;
  };
  /** Types "Test" into each empty text field of the screen whose value the action of button reads. True when it typed into one. */
  const typeInto = (button) => {
    const read = [...reads(button.props.action)].map((path) => path + "/");
    const fields = controls().filter(({ node }) => {
      const bind = node.props.bind, bound = bind?.k === "path" && bind.abs ? bind.segs.join("/") + "/" : null;
      return node.type === "TextField" && node.props.value === "" && bound && read.some((path) => path.startsWith(bound) || bound.startsWith(path));
    });
    return fields.map((control) => app.change(control.node, "Test")).length > 0;
  };
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
    while (queue.length && done.size < limit && !app.tripped) {
      const base = queue.shift();
      const reset = () => restore(base);
      reset();
      const here = controls().map((control) => control.key);
      for (const key of here) {
        if (app.tripped) break;
        if (done.has(key) || done.size >= limit) continue;
        done.add(key);
        reset();
        const found = controls().find((control) => control.key === key);
        if (!found) continue;
        // The tab that is open does nothing when a person taps it. That is the rule of the host, not a fault of the app.
        if (found.node.tab && found.node.props.selected) continue;
        const { node, parent } = found, p = node.props, title = node.name ?? `the ${node.type} "${p.label ?? p.icon ?? ""}"`;
        let seen = look(screen());
        const own = look({ ...node, children: [] }), known = new Set(app.faults.map((fault) => `${fault.where}: ${fault.message}`));
        let error;
        if (p.bind) {
          const now = Number(p.value) || 0, by = p.step ?? 1;
          const sample = ["Toggle", "CheckBox"].includes(node.type) ? !p.value : ["Segmented", "Select"].includes(node.type) ? (p.options ?? []).find((option) => option !== p.value)
            : node.type === "TextField" ? (p.value === "Test" ? "Test 2" : "Test") : node.type === "DateField" ? (p.value === "2026-01-02" ? "2026-01-03" : "2026-01-02")
            : node.type === "TimeField" ? (p.value === "08:30" ? "09:30" : "08:30")
            // A range moves to its bounds, as the smoke step of Alphy does: [min, max], or [min, min] when it is there.
            : node.type === "RangeSlider" ? [p.min ?? 0, p.value?.[0] === (p.min ?? 0) && p.value?.[1] === (p.max ?? 100) ? p.min ?? 0 : p.max ?? 100]
            : now + by > (p.max ?? Infinity) ? now - by : now + by;
          // The person uses a Choices through its option buttons, which have their own turn.
          if (sample === undefined || node.type === "Choices") continue;
          error = app.change(node, sample);
        } else error = app.fire(node);
        if (error) { faults.push("smoke run: " + error); continue; }
        // A button whose action needs a typed value, such as the add button of a form, gets a second press after the run types into each
        // empty text field that its action reads. It passes only when that press changes a value of the typed state, so a dead button beside a field stays a fault.
        // The typed text shows in its field, so the run looks at the screen again before that press.
        if (!p.bind && JSON.stringify(app.state) === base.text && typeInto(node)) {
          seen = look(screen());
          const filled = JSON.stringify(app.state);
          error = app.fire(controls().find((control) => control.key === key)?.node ?? node);
          if (error || JSON.stringify(app.state) === filled) reset();
          if (error) { faults.push("smoke run: " + error); continue; }
        }
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
          find(screen());
          // With one row the run cannot tell: a row cannot move, and the run does not judge it.
          const alone = node.rowIndex !== undefined && rows.length < 2;
          if (!p.bind && !alone && !(rows.length > 1 && !app.fire(rows.at(-1)) && JSON.stringify(app.state) !== base.text)) idle.set(key, title);
          continue;
        }
        // For the judge: what each control did to the values of the app.
        const was = JSON.parse(base.text);
        trace.push(`${p.bind ? "set" : "tap"} ${title}: ` + Object.keys({ ...was, ...app.state }).filter((name) => JSON.stringify(was[name]) !== JSON.stringify(app.state[name]))
          .map((name) => `${name} ${short(was[name])} -> ${short(app.state[name])}`).join(", "));
        if (look(screen()) === seen) faults.push(`smoke run: ${title} changes a value, and no part of the screen shows it`);
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
    for (let step = 0; step < 60 && !app.tripped; step++) {
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
  // Work past the budget stops the run: each later step stops at once, so the findings after it would be wrong.
  if (app.tripped) return { faults: faults.some((fault) => fault.includes(`more than ${STEPS} steps`)) ? faults : [...faults, `smoke run: ${app.told}`], notes, trace };
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

/** The two sides that an expectation compares: a comparison, or near(a, b). Null for each other expression. */
const sidesOf = (tree) => (tree.k === "bin" && OPS_COMPARE.has(tree.op) ? [tree.left, tree.right] : tree.k === "call" && tree.name === "near" ? tree.args : null);
/** Why an expectation is false, with the values that it compared. */
function explain(app, tree) {
  const seen = (node) => JSON.stringify(app.value(node, {}));
  if (tree.k === "bin" && tree.op === "&&") return explain(app, app.value(tree.left, {}) ? tree.right : tree.left);
  const sides = sidesOf(tree);
  return show(tree) + (sides ? `: left is ${seen(sides[0])}, right is ${seen(sides[1])}` : `: value is ${seen(tree)}`);
}
const OPS_COMPARE = new Set(["==", "!=", "<", "<=", ">", ">="]);

/**
 * Runs each check on a fresh copy of the app. Steps: set(name, value, row=0), tap(name, row=0), expect(expression).
 * A failed expectation that compares two sides also gives the value of its left side (got).
 */
function runChecks(file) {
  return file.of("check").map((entry) => {
    const app = new App(file);
    const [title, ...steps] = entry.tree.args;
    for (const step of steps) {
      let error = "", got;
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
        else if (step.name === "expect" && !app.value(step.args[0], {})) {
          error = "expect failed: " + explain(app, step.args[0]);
          got = sidesOf(step.args[0]) && app.value(sidesOf(step.args[0])[0], {});
        } else if (step.name !== "expect") error = `unknown step ${step.name}`;
      } catch (failure) { error = failure.message; }
      if (error) return { name: title?.v ?? "", pass: false, detail: error, ...(got !== undefined && got !== null && { got }) };
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

const SIZES = ["small", "medium", "large"], ALIGNS = ["start", "center", "end"], GAPS = ["none", "small", "medium", "large"], TONES = ["muted", "accent", "success", "warning", "error"];
/** The names of IconButton and icon=: each icon of Alphy, and the kit's names that the run-time gives Alphy's name (ICON_NAMES). */
const ICONS = [...HOST_ICONS, ...Object.keys(ICON_NAMES)];
/**
 * The look options of the components, and the values of each one that the kit gives: values of Alphy's catalog (ToolUiCatalog).
 * The run-time puts each value into its node as it is, so only the compile step reads this table.
 */
const LOOKS = {
  icon: Object.fromEntries(["Button", "IconButton", "Metric", "Badge", "Notice", "Card", "TextField", "NumberField", "Select", "DateField", "TimeField"].map((type) => [type, ICONS])),
  variant: { Button: ["filled", "outlined", "text", "tonal"], Text: ["muted", "eyebrow"], Card: ["hero"], CheckBox: ["circle"], Progress: ["bar", "dots"] },
  size: { Emoji: SIZES },
  emphasis: { Text: ["body", "title", "headline", "label", "number", "display"], Metric: ["display", "number"] },
  align: { Text: ALIGNS, Row: ALIGNS },
  tone: { Text: TONES, Metric: TONES, Badge: TONES, Notice: TONES },
  gap: { Row: GAPS, Column: GAPS },
  columns: { Grid: [1, 2, 3, 4] },
};
/** The values that an option can take: a value in the line, or each value of a choice (test ? a : b, or if(test, a, b)). Null for each other expression. */
const choices = (tree) => {
  const pair = tree.k === "if" ? [tree.yes, tree.no] : tree.k === "call" && tree.name === "if" && tree.args.length === 3 ? tree.args.slice(1) : null;
  if (!pair) return tree.k === "lit" ? [tree.v] : null;
  const [yes, no] = pair.map(choices);
  return yes && no && [...yes, ...no];
};

/**
 * The faults of the look options of each component line: an option that the component does not take, and a value that Alphy does not
 * take. Each value of a choice is checked, also in a row of a list and in a part of the screen that is hidden, so Alphy never refuses one later.
 * named gives the name of a line in a fault (parts.js faultName).
 */
function looks(file, named) {
  const out = [];
  // What draws a row of a list: the template of each List, inline or a line, and each line that it names. A line outside them has no row,
  // and the evaluator says so when one of its values reads a field of a row.
  const rowLines = new Set(), inline = new Set();
  for (const entry of file.of("component")) walk(entry.tree, (node) => {
    const template = node.k === "call" && node.name === "List" ? given(node).template : null;
    if (template?.k === "ref") rowLines.add(template.name);
    else if (template) walk(template, (inner) => { inline.add(inner); if (inner.k === "ref") rowLines.add(inner.name); });
  });
  for (const key of rowLines) walk(file.get(key)?.tree, (node) => { if (node.k === "ref") rowLines.add(node.name); });
  for (const entry of file.of("component")) walk(entry.tree, (node) => {
    if (node.k !== "call" || !Object.hasOwn(CATALOG, node.name)) return;
    const props = given(node), line = named(entry.key), inRow = rowLines.has(entry.key) || inline.has(node);
    // JavaScript puts the keys of an object that are numbers first, in the order of the numbers.
    const number = props.options?.k === "map" ? props.options.entries.find(([key]) => /^\d+$/.test(key)) : null;
    if (number) out.push(`${line}: a map of options keeps the order of the plan only with keys that are not numbers, and ${JSON.stringify(number[0])} is a number. Give options of numbers as a list`);
    for (const [key, byType] of Object.entries(LOOKS)) {
      if (!props[key]) continue;
      const allowed = Object.hasOwn(byType, node.name) ? byType[node.name] : null, values = choices(props[key]), what = `the ${key} of ${/^[AEIOU]/.test(node.name) ? "an" : "a"} ${node.name}`;
      let field = null;
      walk(props[key], (inner) => { if (!field && inner.k === "path" && !inner.abs) field = show(inner); });
      if (!allowed) out.push(`${line}: ${node.name} has no property ${key}`);
      else if (!values) out.push(`${line}: ${what} is a value in the line, or a choice of values such as $/on ? "a" : "b"`);
      // Alphy has no style of one row: the run-time computes the template once, from a blank row.
      else if (field && inRow) out.push(`${line}: ${what} reads ${field}, a field of a row. One template draws each row of a list, so a look value in a row must not read a field of the row. Use a value of the app, such as $/on ? "a" : "b"`);
      else if (!field) for (const value of values) {
        const one = key === "icon" ? "the name of an icon of Alphy, such as Plus, Check, Trash, Pencil, Star, or Heart" : `one of ${allowed.join(", ")}`;
        if (!allowed.includes(value)) out.push(`${line}: ${what} is ${one}, and ${JSON.stringify(value)} is not one`);
      }
    }
  });
  return out;
}

/** Items as one text: "a", "a and b", or "a, b, and c". */
const joined = (items) => (items.length < 3 ? items.join(" and ") : `${items.slice(0, -1).join(", ")}, and ${items.at(-1)}`);

/**
 * The formulas that read each other in a circle, each circle once, named by its first line. A formula reads each formula whose path
 * overlaps a path that it reads: $/a overlaps $/a/b. A value of a circle never settles (App.recompute).
 */
function circles(file, named) {
  const keys = file.of("formula").map((entry) => entry.key);
  const overlap = (a, b) => a === b || a.startsWith(b + "/") || b.startsWith(a + "/");
  const reads = new Map(file.of("formula").map((entry) => {
    const out = new Set();
    walk(entry.tree, (node) => { if (node.k === "path" && node.abs) keys.filter((key) => overlap(key, "$/" + node.segs.join("/"))).forEach((key) => out.add(key)); });
    return [entry.key, out];
  }));
  const reach = (from) => {
    const seen = new Set(), next = [...reads.get(from)];
    while (next.length) { const key = next.pop(); if (!seen.has(key)) { seen.add(key); next.push(...reads.get(key)); } }
    return seen;
  };
  const reached = new Map(keys.map((key) => [key, reach(key)])), done = new Set(), out = [];
  for (const key of keys.filter((key) => reached.get(key).has(key) && !done.has(key))) {
    const circle = keys.filter((other) => reached.get(key).has(other) && reached.get(other).has(key));
    circle.forEach((other) => done.add(other));
    out.push(circle.length === 1 ? `${named(key)}: the formula reads its own value, so the value never settles. Compute it from other values only`
      : `${named(key)}: the formulas ${joined(circle)} read each other, so their values never settle. Compute one of them from other values only`);
  }
  return out;
}

/**
 * Everything the host can find wrong without a model: line faults, tree faults, formulas in a circle, values that are not finite, gaps,
 * look options, the smoke run, and each check line that fails. The example of a figure is a check line of its part, so its fault names the need.
 */
function report(file) {
  const app = new App(file);
  app.tree(true);
  const broken = Object.keys(app.state).filter((name) => !finite(app.state[name])).map((name) => `$/${name} is not a finite number at the start`), named = faultName(file);
  const faults = [
    // A line of many thousand characters, such as one with too many levels, gives its start only.
    ...[...file.faults, ...file.partFaults].map((f) => `${f.line == null ? "line" : "line " + f.line}: ${f.message}: ${f.text.length > 200 ? f.text.slice(0, 200) + "..." : f.text}`),
    ...app.faults.map((f) => `${named(f.where)}: ${f.message}`),
    ...circles(file, named),
    ...broken,
    ...(file.get("root") ? [] : ["no root line"]),
    ...gaps(file, app.reached),
    ...looks(file, named),
  ];
  // The smoke run needs a screen that builds. So it runs only when nothing else is wrong.
  const run = settings.gate === "smoke" && !faults.length ? smoke(file) : { faults: [], notes: [] };
  // Work past the budget would stop each check again in its own copy of the app.
  const checks = app.tripped ? [] : runChecks(file), partOf = needsOf(file);
  const failed = file.of("check").map((entry, index) => [entry, checks[index]]).filter(([, check]) => check && !check.pass).map(([entry, check]) => {
    const need = entry.origin === "part" ? partOf.get(entry.key) : undefined, example = need !== undefined ? file.get("need:" + need)?.tree.named.example : undefined;
    if (!example) return `check(${JSON.stringify(check.name)}): ${check.detail}`;
    return `need ${need}: example=${show(example)}${"got" in check ? ` gives ${JSON.stringify(check.got)}, and the example expects ${show(example.items[1])}. Correct the formula or the example` : `: ${check.detail}`}`;
  });
  return { faults: [...new Set([...faults, ...run.faults, ...failed])], checks, notes: [...(file.partNotes ?? []), ...run.notes.slice(0, 5)] };
}

return { smoke, runChecks, report };
})();
// rows.js
const { MAX_ROW_FIELDS, MAX_ROWS, ALPHY, namer, wished, clockOf, cast, castRow, own, relative, rows } = (() => {
// The rows of a List as Alphy's list, and the value forms that a list shares with the screen and the state: the Alphy names of
// the kit's names, a time as minutes, a date as a real date, and a shown value in the type of its field. run.js builds the
// screen and gives each List to rows.



/** False in the run-time text of an app (dsl.js). */
const MESSAGES = true;

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
/** Alphy's state holds a text of at most 2048 characters (ToolValueSchema). */
const MAX_TEXT = 2048;
/**
 * A value of the kit in the form of its Alphy field. kind is "number", "string", "boolean", "time", or "date". A shown value has the type
 * of its field, which the emitter fixes with its first value (app.types). A number that is not finite is 0, and a text is never null, a list,
 * or a record. Each value stays inside what Alphy's state holds: a number of at most 2^53 - 1 either way, and a text of at most 2048 characters.
 */
const cast = (kind, value, today) => (kind === "time" ? minutesOf(value) : kind === "date" ? (dayNumber(value) === null ? today : String(value).trim())
  : kind === "number" ? (Number.isFinite(value) ? Math.min(Number.MAX_SAFE_INTEGER, Math.max(-Number.MAX_SAFE_INTEGER, value)) : 0) : kind === "boolean" ? value === true
  : (value === null || value === undefined || typeof value === "object" ? "" : String(value)).slice(0, MAX_TEXT));
/** A row of the kit as an Alphy row. spec is the field, the Alphy name, and the kind of each stored field (app.lists). */
const castRow = (row, spec, today) => ({ id: String(row?.id ?? ""), ...Object.fromEntries(spec.map(([field, safe, kind]) => [safe, cast(kind, row?.[field], today)])) });
const own = (map, key) => (Object.hasOwn(map, key) ? map[key] : undefined);
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
  if (!root || !node.blank) { at.fault(MESSAGES ? "a List shows the rows of a list of the app, such as $/rows, or sort or filter of it. Other rows are not built yet" : ""); return null; }
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
  if (MESSAGES && reorder && (group || !inOrder(expr.items))) at.fault("the arrows of a row move it in the stored list, so the list shows its rows in their stored order: no sort and no group");
  const label = reorder ? rowName("~label", "label") : null;
  if (label) scope.computed.set(label, "string");
  const shown = node.children.filter((child) => child.row).map((child) => {
    const out = {};
    walk(child, keyOf(child), line, { scope: { ...scope, out, id: String(child.row.id ?? "") }, weighted: false, off: at.off });
    if (scope.computed.has(groupBy)) { try { out[groupBy] = cast("string", kit.value(group, { it: child.row })); } catch { out[groupBy] = ""; } }
    // Alphy reads the label of a row to a person who moves it, so it is never blank: the first text of the row, else its id.
    if (label) out[label] = (spec.filter(([, , kind]) => kind === "string").map(([field]) => cast("string", child.row[field]).trim()).find(Boolean) ?? String(child.row.id)).slice(0, 300);
    return { ...castRow(child.row, spec, day), ...out };
  });
  const view = !(expr.items?.k === "path" && inOrder(expr.items)) || scope.computed.size > 0, field = view ? name("view:" + key, wished(key)) : fields.get(root);
  if (view) {
    const schema = { id: "string", ...Object.fromEntries(spec.map(([, safe, kind]) => [safe, own(ALPHY, kind) ?? kind])), ...Object.fromEntries(scope.computed) };
    const count = Object.keys(schema).length;
    if (MESSAGES && count > MAX_ROW_FIELDS) at.fault(`the rows of this list have ${count} fields with the id and the values that a row shows, and a row of Alphy has at most ${MAX_ROW_FIELDS}. Show fewer values in a row`);
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

return { MAX_ROW_FIELDS, MAX_ROWS, ALPHY, namer, wished, clockOf, cast, castRow, own, relative, rows };
})();
// draw.js
const { BAR_SURFACE, sideOf, LIMITS, PALETTE, utf8, hostBytes, fixed, commandsOf, barOf, MODEL, STAGE, ACCENTS, accentOf, polygonsOf, budgetOf, fit } = (() => {
// The drawing of a surface: the kit's shapes as Alphy's drawing commands (GraphicsContract.frame), and the limits of one reply.



/** The surface of a timer's bar, in Alphy's pixels of a surface: the bar fills it, 16 to 1, as a thick bar of the card's width. Alphy takes 64 pixels high at least. */
const BAR_SURFACE = { width: 1024, height: 64 };
/** A side of a surface: Alphy takes 64 to 2048 pixels each way. */
const sideOf = (value) => Math.min(2048, Math.max(64, Math.round(Number(value)) || 0));
/** What Alphy draws in one reply, for all surfaces together (GraphicsContract.frame, ArtifactProtocol.validateSurfaces). */
const LIMITS = { commands: 512, pairs: 2048, chars: 2048, bytes: 32768 };
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

/**
 * The bar of a timer's phase: the track, and the share of the phase that passed (0 to 1) in the color of the phase. Two shapes.
 * The track is the muted tone of the theme, which Alphy draws as a light tint of its muted ink (SurfaceDrawing.kt), so it reads in both themes.
 * A dot in the color of the phase near the end of the track is the stop sign of a Material bar: it shows where the bar ends before the phase moves.
 */
const barOf = (share, color) => [
  { shape: "rect", x: 0, y: 0, w: BAR_SURFACE.width, h: BAR_SURFACE.height, color: "muted", radius: BAR_SURFACE.height / 2 },
  { shape: "circle", x: BAR_SURFACE.width - BAR_SURFACE.height, y: BAR_SURFACE.height / 2, r: 10, color },
  { shape: "rect", x: 0, y: 0, w: Math.round(BAR_SURFACE.width * share), h: BAR_SURFACE.height, color, radius: BAR_SURFACE.height / 2 },
];

/**
 * The surface of a model, and its stage: a box under the faces in the muted tone of the theme, a tint that Alphy draws at alpha 64 (SurfaceDrawing.kt).
 * It is a mid tone on a light and on a dark card, so the dark and the white faces both read (Task 11b-2b: a light stage was a bright panel in a dark card).
 */
const MODEL = { width: 320, height: 240 };
const STAGE = { type: "rect", x: 0, y: 0, ...MODEL, color: "muted" };
/**
 * The accent of each palette of Alphy on a light theme, for a body whose color is "accent": the primary color of ToolPalettes, and the
 * text color of the shell for graphite (AlphaTheme). The faces of a body keep these colors in a dark theme too, because the light shades each
 * face from a hex color. A preset with no palette has its own palette (NativeToolUi.presetPalette).
 */
const ACCENTS = { graphite: "#242424", blue: "#0056d0", teal: "#006b5f", green: "#006e1d", amber: "#7f5700", orange: "#b02e00", red: "#c00015", pink: "#bc004f", purple: "#9e00c3", indigo: "#1a41ff", brown: "#a83900" };
const PRESETS = { clean: "blue", soft: "teal", expressive: "purple", editorial: "brown", technical: "indigo" };
const accentOf = (palette, preset) => {
  // The theme of the screen takes a palette only when it is not empty (run.js screen).
  const name = palette || (Object.hasOwn(PRESETS, preset) ? PRESETS[preset] : "orange");
  return Object.hasOwn(ACCENTS, name) ? ACCENTS[name] : ACCENTS.graphite;
};
/** The light of a model, its tilt toward the person in radians, and the sides of the prism of a cylinder. */
const LIGHT = [-0.42, 0.76, 0.5], PITCH = 0.38, SIDES = 12;
/** A color of a body as [alpha, red, green, blue]: a color of the kit, the accent, "#RRGGBB", or "#AARRGGBB". Each other text is ink. */
const argbOf = (color, accent) => {
  const hex = color === "accent" ? accent : Object.hasOwn(TONES, color) ? TONES[color] : HEX.test(color) ? color : TONES.ink;
  const digits = hex.length === 7 ? "ff" + hex.slice(1) : hex.slice(1);
  return [0, 2, 4, 6].map((at) => parseInt(digits.slice(at, at + 2), 16));
};
const hex2 = (value) => Math.min(255, Math.max(0, Math.round(value))).toString(16).padStart(2, "0");
const minus = (a, b) => a.map((value, index) => value - b[index]);
const cross = (u, v) => [u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0]];
const dot = (u, v) => u[0] * v[0] + u[1] * v[1] + u[2] * v[2];

/** The flat faces of a body, each as its corners in 3D. The corners of each face go around it so that its normal looks out of the body. */
function facesOf({ body, x, y, z, w, h, d, radius, length, axis }) {
  const at = [x, y, z];
  if (body === "box") {
    const half = [w, h, d].map((size) => Math.abs(size) / 2), faces = [];
    for (const across of [0, 1, 2]) for (const side of [-1, 1]) {
      const corners = [[-1, -1], [1, -1], [1, 1], [-1, 1]].map(([u, v]) => {
        const sign = [0, 0, 0];
        sign[across] = side; sign[(across + 1) % 3] = u; sign[(across + 2) % 3] = v;
        return at.map((center, index) => center + sign[index] * half[index]);
      });
      // These corners go around the axis: the face on the low side goes the other way.
      faces.push(side > 0 ? corners : corners.reverse());
    }
    return faces;
  }
  // A cylinder is a prism: two rings of 12 points, their caps, and the 12 sides between them.
  const along = Math.max(0, "xyz".indexOf(axis)), ring = (side) => Array.from({ length: SIDES }, (_, index) => {
    const angle = (index / SIDES) * 2 * Math.PI, point = [...at];
    point[along] += (side * Math.abs(length)) / 2; point[(along + 1) % 3] += Math.cos(angle) * Math.abs(radius); point[(along + 2) % 3] += Math.sin(angle) * Math.abs(radius);
    return point;
  });
  const [low, high] = [ring(-1), ring(1)];
  return [[...low].reverse(), high, ...low.map((point, index) => [point, low[(index + 1) % SIDES], high[(index + 1) % SIDES], high[index]])];
}

/**
 * The faces of a 3D model as Alphy's polygons on a surface of width x height, the far faces first (the prototype's model in render.js).
 * The model turns by turnDegrees around its upright axis and tilts toward the person, and a weak perspective makes the near faces larger.
 * Only the faces that look at the person are drawn. The light shades each face from 70 % to 100 % of its color. With exploded, each
 * named part is taken apart first. With a selected part, the faces of the other parts keep 40 % of their alpha. accent is the color of "accent".
 */
function polygonsOf(bodies, turnDegrees, exploded, selected, width, height, accent = ACCENTS.graphite) {
  const faces = (exploded ? explode(bodies) : bodies).flatMap((body) => facesOf(body).map((points) => ({ body, points })));
  const all = faces.flatMap((face) => face.points);
  const mid = [0, 1, 2].map((index) => (all.reduce((low, point) => Math.min(low, point[index]), Infinity) + all.reduce((high, point) => Math.max(high, point[index]), -Infinity)) / 2);
  const reach = all.reduce((most, point) => Math.max(most, Math.hypot(...minus(point, mid))), 1e-6);
  const yaw = ((Number.isFinite(turnDegrees) ? turnDegrees : 0) * Math.PI) / 180, scale = (Math.min(width, height) * 0.44) / reach, eye = [0, 0, reach * 5];
  const view = (point) => {
    const [x, y, z] = minus(point, mid), across = x * Math.cos(yaw) + z * Math.sin(yaw), deep = -x * Math.sin(yaw) + z * Math.cos(yaw);
    return [across, y * Math.cos(PITCH) - deep * Math.sin(PITCH), y * Math.sin(PITCH) + deep * Math.cos(PITCH)];
  };
  return faces.map(({ body, points }) => {
    const seen = points.map(view);
    return { body, seen, normal: cross(minus(seen[1], seen[0]), minus(seen[2], seen[0])), depth: seen.reduce((sum, point) => sum + point[2], 0) / seen.length };
  }).filter(({ seen, normal }) => dot(normal, minus(eye, seen[0])) > 0).sort((a, b) => a.depth - b.depth).flatMap(({ body, seen, normal }) => {
    const lit = 0.7 + 0.3 * Math.abs(dot(normal, LIGHT) / Math.hypot(...normal)), [alpha, ...rgb] = argbOf(String(body.color), accent);
    const points = seen.map(([x, y, z]) => { const near = eye[2] / (eye[2] - z); return [fixed(width / 2 + x * scale * near), fixed(height / 2 - y * scale * near)]; });
    if (!inside(points.flat())) return [];
    const shade = hex2(alpha * (selected && body.part !== selected ? 0.4 : 1));
    return [{ type: "polygon", points, color: `#${shade === "ff" ? "" : shade}${rgb.map((value) => hex2(value * lit)).join("")}` }];
  });
}

/** What one command costs: a box, a circle, and a text 1 pair, a line 2, a polygon or a polyline 1 for each point (GraphicsContract.frame). */
const pairsOf = (command) => command.points?.length ?? (command.type === "line" ? 2 : 1);
const charsOf = (command) => command.text?.length ?? 0;
/**
 * What the surfaces of one reply cost, from [id, commands] in order: the commands, pairs, and text characters of all of them, and
 * the bytes of {id: {commands}, ...} as the host writes it. The emitter checks the first reply with it, and fit starts from the reply with no commands.
 */
const budgetOf = (surfaces) => {
  const commands = surfaces.flatMap(([, list]) => list);
  return { commands: commands.length, pairs: commands.reduce((sum, command) => sum + pairsOf(command), 0),
    chars: commands.reduce((sum, command) => sum + charsOf(command), 0), bytes: hostBytes(Object.fromEntries(surfaces.map(([id, list]) => [id, { commands: list }]))) };
};

/**
 * The surfaces of one reply, {id: {commands}}, from [id, commands] in order. Alphy refuses a reply past a limit for all surfaces
 * together, so the reply keeps the first commands that fit, and none after the first one that does not.
 */
function fit(surfaces) {
  const used = budgetOf(surfaces.map(([id]) => [id, []]));
  let full = false;
  return Object.fromEntries(surfaces.map(([id, commands]) => [id, { commands: commands.filter((command, index) => {
    // A command costs its bytes, and a comma after the first command of its surface. The commands before it were kept, as none is kept after the first that does not fit.
    const cost = { commands: 1, pairs: pairsOf(command), chars: charsOf(command), bytes: hostBytes(command) + (index ? 1 : 0) };
    full ||= Object.keys(LIMITS).some((key) => used[key] + cost[key] > LIMITS[key]);
    if (!full) for (const key of Object.keys(cost)) used[key] += cost[key];
    return !full;
  }) }]));
}

return { BAR_SURFACE, sideOf, LIMITS, PALETTE, utf8, hostBytes, fixed, commandsOf, barOf, MODEL, STAGE, ACCENTS, accentOf, polygonsOf, budgetOf, fit };
})();
// drag.js
const { IDLE, dragged } = (() => {
// The drag of a 3D model on its surface: a pointer that moves along x turns the model around its upright axis (the prototype's rule).
// run.js gives each pointer event of a surface to dragged, and the kit's state keeps the drag that runs in _drag.



/** The turn of a model for each pixel that a drag moves along x, in degrees. */
const DEGREES_PER_PIXEL = 0.6;
/** The drag of a model when no drag runs, also after each open (run.js handle): the surface, the pointer, and the last x of a drag. The state field of the drag has its fields (emit.js). */
const IDLE = Object.freeze({ surface: "", pointer: 0, x: 0 });
/** A turn in degrees as a turn from 0 to 360, with 2 decimals. */
const wrap = (degrees) => fixed(((degrees % 360) + 360) % 360);

/**
 * A pointer event of a surface. drawn is each surface of the screen (run.js screen). On the surface of a model with a turn, a down
 * starts a drag at its x, a move or an up turns the model by DEGREES_PER_PIXEL for each pixel since the last event, and an up or a
 * cancel ends the drag. A down of a second pointer while a drag runs, a pointer of another surface, a place that is not a number,
 * and a move, an up, or a cancel of no drag of that surface and pointer change nothing (false).
 */
function dragged(kit, drawn, { surfaceId, x, phase, pointer }) {
  const model = drawn.find((surface) => surface.id === surfaceId)?.model, drag = kit.state._drag ?? IDLE;
  if (!model || !Number.isFinite(x)) return false;
  if (phase === "down") {
    if (drag.surface && drag.pointer !== pointer) return false;
    kit.state._drag = { surface: surfaceId, pointer, x };
    return "";
  }
  if (!["move", "up", "cancel"].includes(phase) || drag.surface !== surfaceId || drag.pointer !== pointer) return false;
  kit.state._drag = phase === "move" ? { ...drag, x } : IDLE;
  const from = Number.isFinite(model.props.value) ? model.props.value : 0;
  return phase === "cancel" ? "" : kit.change(model, wrap(fixed(from + (x - drag.x) * DEGREES_PER_PIXEL)));
}

return { IDLE, dragged };
})();
// run.js
const { PER_OBJECT, MAX_ARRAY, TYPED, MAX_POINTS, ALERT_CAPABILITIES, header, screen, stateOf, load, kitOf, handle } = (() => {
// The run-time of a compiled app. It runs inside the app's source in the sandbox (kit/build.js): for each event, the kit's
// App reads the lines of the app, takes the stored state, runs the event, and gives the next state and screen.
// The emitter (emit.js) builds the first screen and the first state with the same screen and stateOf functions.







/** False in the run-time text of an app (dsl.js): the run-time reads no fault of the screen. */
const MESSAGES = true;

/** An object of Alphy holds at most 32 fields, so the shown values go into d, d2, d3, and the tests into v, v2. */
const PER_OBJECT = 32;
/** An array of Alphy holds at most 512 values (ToolValueSchema). */
const MAX_ARRAY = 512;
/** Alphy keeps a state of at most 49152 bytes (ToolSpec). */
const MAX_STATE_BYTES = 49152;
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
function screen(kit, app, first = false) {
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
  if (app.drag) state[app.drag] = kit.state._drag ?? IDLE;
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
 * action that takes a list past its count of rows (app.caps), an array past Alphy's limit, or the state past 49152 bytes, and an event whose work passes
 * the budget of the kit (core.js STEPS) give the state back as it is.
 * A plain value of another type or a number that is not finite keeps its value from before the event, a bound number past its bounds comes back at
 * its bound, and a choice comes back into its options. The reply holds a new ui only when the screen differs from the screen of the state before the event.
 * An open and a timer event of a timer or a change that runs move each timer and each change to the clock of now. A pointer event
 * of the surface of a model turns the model (drag.js dragged), and an open ends a drag. A timer event of nothing that runs, also a late one after a pause or after
 * the end of a change, and each other event of a surface give the state back as it is. The host sends a liveInput, never an action, for a live
 * control (a TextField or a Slider), so a liveInput sets only a live control and an action for one changes nothing. The reply to a liveInput holds no timers.
 */
function handle(input, app, now = Date.now()) {
  const { state, event } = input;
  if (event.type === "preview" || event.type === "render") return { state };
  const clock = settings.now;
  settings.now = () => now;
  let kit;
  try {
    kit = kitOf(app, state);
    const kinds = app.kinds ?? {}, before = screen(kit, app);
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
    // An event whose work passes the budget of the kit (core.js STEPS) changes nothing too.
    if (kit.tripped || hostBytes(next) > MAX_STATE_BYTES) return { state };
    // Each reply of an app with surfaces draws each one again, inside Alphy's limits of one reply, and names each timer that runs:
    // the bar of a timer by the id of its surface, and the changes of animate by the field of their state. One list for all.
    if (after.drawn.length) {
      reply.surfaces = fit(after.drawn.map(({ id, commands }) => [id, commands]));
      // The host takes no timers in the reply to a liveInput (SandboxContract), and it keeps the timers that run.
      if (event.type !== "liveInput") reply.timers = [...after.drawn.filter((surface) => surface.running).map(({ id }) => ({ id, intervalMs: TICK_MS })), ...(kit.state._run ? [{ id: app.anim.field, intervalMs: ANIM_MS }] : [])];
    }
    return reply;
  } catch (failure) {
    // The stop of the budget can come in a part of the walk with no catch of its own (core.js App.step). The event changes nothing then too.
    if (kit?.tripped) return { state };
    throw failure;
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
  const name = namer(), keyNames = new Map();
  if (typeof title !== "string" || title.length < 1 || title.length > 100) faults.push("app: the app line needs a title of 1 to 100 characters");
  // The need whose part wrote each line. A line that an author wrote belongs to no need, and a fault of it names the line.
  const partOf = needsOf(file), label = faultName(file, partOf);
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
    // Alphy keys each row by an id that is not blank, has at most 128 characters, and that no other row has (ToolValueSchema).
    const ids = value.map((row) => String(row.id ?? "")), id = ids.find((each, index) => !each.trim() || each.length > 128 || ids.indexOf(each) !== index);
    if (id !== undefined) fault(`a row of $/${root} has the id ${JSON.stringify(id)}, and Alphy keys each row by an id of 1 to 128 characters that no other row has. Give each row an id of its own, or no id`);
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
    // Each object has a namer of its own, so the names in it do not depend on the other needs of the plan.
    if (!keyNames.has(field)) keyNames.set(field, namer());
    const key = keyNames.get(field)("$/" + root, wish);
    (stateSchema[field] ??= { type: "object", fields: {} }).fields[key] = schema;
    needObjects.set(field, need);
    app.fields.push([root, `${field}.${key}`]);
  };
  for (const entry of file.of("state")) {
    const root = entry.key.slice(2), value = kit.state[root], need = entry.origin === "part" ? partOf.get(entry.key) : undefined;
    const owner = need === undefined ? entry.key : `need ${need}`;
    // The record of a part, such as {selected: "", exploded: false, turn: 30} of a model, is an object of plain values. A record of an author's line is not built yet.
    const record = need !== undefined && value !== null && typeof value === "object" && !Array.isArray(value) && Object.values(value).every((inner) => SCALARS.has(typeof inner));
    if (root.includes("/") || !(Array.isArray(value) || SCALARS.has(typeof value) || record)) { faults.push(`${owner}: ${entry.key} is a ${root.includes("/") ? "path inside a value" : value !== null && typeof value === "object" ? "record" : "value of no known kind"}, and that is not built yet`); continue; }
    if (record) app.kinds[root] = "record";
    const schema = record ? { type: "object", fields: Object.fromEntries(Object.entries(value).map(([key, inner]) => [key, typeof inner])) }
      : Array.isArray(value) ? listed(root, value, owner, root === need ? declared.get(need) : undefined) : app.kinds[root] === "time" ? "number" : typeof value;
    if (schema) place(root, need, owner, schema);
  }
  // The open flag of an add form that the run-time puts into a bottom sheet: a value of its need that only the run-time sets. An author's Row of the form is a form too.
  for (const need of declared.keys()) {
    const form = file.get(`${need}_addRow`);
    const typed = ["Column", "Row"].includes(form?.tree.name) ? (given(form.tree).children?.items ?? []).filter((item) => item.k === "ref" && TYPED.has(file.get(item.name)?.tree.name)).length : 0;
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
  // A model with a turn gets the pointer events of its surface: the drag that runs (drag.js IDLE) is one field of the state.
  const turned = file.of("component").find((entry) => entry.tree.k === "call" && entry.tree.name === "Model3d" && given(entry.tree).bind);
  if (turned) {
    app.drag = name("drag", "drag");
    stateSchema[app.drag] = { type: "object", fields: Object.fromEntries(Object.entries(IDLE).map(([key, value]) => [key, typeof value])) };
    owners.push(label(turned.key));
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
  // Alphy keeps at least one state field. A screen with no value, such as a static card, keeps one hidden field d0: the namer gives
  // no line of a plan that name, and the screen names its objects d, d2, and so on. load and stateOf carry it as each other field.
  if (!Object.keys(stateSchema).length) {
    kit.state["app:empty"] = false;
    app.fields.push(["app:empty", "d0"]);
    stateSchema.d0 = "boolean";
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
  // The fault names the drawing whose surface takes the reply past a limit: the cost of the reply up to it, and up to the one before it.
  let before = budgetOf([]);
  shown.drawn.forEach(({ line }, index) => {
    const where = label(line), used = budgetOf(shown.drawn.slice(0, index + 1).map(({ id, commands }) => [id, commands]));
    if (index === MAX_SURFACES) faults.push(`${where}: the app draws ${surfaces.length} surfaces, and Alphy draws at most ${MAX_SURFACES}: one for each drawing and each timer with rounds or with no alert. Use fewer`);
    for (const [unit, limit] of Object.entries(LIMITS)) {
      if (before[unit] <= limit && used[unit] > limit) faults.push(`${where}: with this drawing the app draws ${used[unit]} ${UNITS[unit]} at the start, and Alphy draws at most ${limit} ${UNITS[unit]} in one reply. Draw fewer shapes`);
    }
    before = used;
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
runtime.text = "let handle;const __handle=(f)=>{handle=f};\n(()=>{var tQ={\"||\":1,\"&&\":2,\"==\":3,\"!=\":3,\"<\":4,\"<=\":4,\">\":4,\">=\":4,\"+\":5,\"-\":5,\"*\":6,\"/\":6,\"%\":6};class T extends Error{}function OZ(Q){let Z=[],$=0,q=(K)=>{throw new T(\"\")};while($<Q.length){let K=Q[$];if(/\\s/.test(K)){$++;continue}if(K==='\"'){let H=$+1,C=\"\";while(H<Q.length&&Q[H]!=='\"'){if(Q[H]===\"\\\\\")H++,C+={n:`\n`,t:\"\\t\"}[Q[H]]??Q[H];else C+=Q[H];H++}if(H>=Q.length)q(\"\");Z.push({t:\"str\",v:C}),$=H+1;continue}if(K===\"$\"){let H=/^\\$(\\/?)([\\p{L}_][\\p{L}\\p{N}_]*(?:\\/[\\p{L}_][\\p{L}\\p{N}_]*)*)/u.exec(Q.slice($));if(!H)q(\"\");Z.push({t:\"path\",abs:H[1]===\"/\",segs:H[2].split(\"/\")}),$+=H[0].length;continue}let V=/^(\\d+\\.?\\d*|\\.\\d+)/.exec(Q.slice($));if(V){Z.push({t:\"num\",v:Number(V[0])}),$+=V[0].length;continue}let Y=/^[\\p{L}_][\\p{L}\\p{N}_]*/u.exec(Q.slice($));if(Y){Z.push({t:\"id\",v:Y[0]}),$+=Y[0].length;continue}let W=Q.slice($,$+2);if([\":=\",\"==\",\"!=\",\"<=\",\">=\",\"&&\",\"||\"].includes(W)){Z.push({t:\"op\",v:W}),$+=2;continue}if(\"()[]{},:=+-*/%<>!?.\".includes(K)){Z.push({t:\"op\",v:K}),$++;continue}q(\"\")}return Z}function SZ(Q,Z=0){let $=Z,q=0,K=(J)=>Q[$]&&Q[$].t===\"op\"&&Q[$].v===J,V=(J)=>{if(!K(J))throw new T(\"\");$++},Y=(J)=>{if((q+=J)>100)throw new T(\"\")};function W(){return H()}function H(){let J=Q[$];if(!J)throw new T(\"\");if($++,J.t===\"num\")return{k:\"lit\",v:J.v};if(J.t===\"str\")return{k:\"lit\",v:J.v};if(J.t===\"path\")return{k:\"path\",abs:J.abs,segs:J.segs};if(J.t===\"id\"){if(J.v===\"true\"||J.v===\"false\")return{k:\"lit\",v:J.v===\"true\"};if(J.v===\"null\")return{k:\"lit\",v:null};if(!K(\"(\"))return{k:\"ref\",name:J.v};$++;let F=[],z={};while(!K(\")\")){if(Q[$]?.t===\"id\"&&Q[$+1]?.t===\"op\"&&Q[$+1].v===\"=\"){let G=Q[$].v;$+=2,z[G]=M()}else F.push(M());if(!K(\")\"))V(\",\")}return $++,{k:\"call\",name:J.v,args:F,named:z}}if(J.t===\"op\"&&J.v===\"(\"){let F=M();return V(\")\"),F}if(J.t===\"op\"&&J.v===\"[\"){let F=[];while(!K(\"]\"))if(F.push(M()),!K(\"]\"))V(\",\");return $++,{k:\"list\",items:F}}if(J.t===\"op\"&&J.v===\"{\"){let F=[];while(!K(\"}\")){let z=Q[$++];if(!z||z.t!==\"id\"&&z.t!==\"str\")throw new T(\"\");if(V(\":\"),F.push([z.v,M()]),!K(\"}\"))V(\",\")}return $++,{k:\"map\",entries:F}}if(J.t===\"op\"&&(J.v===\"-\"||J.v===\"!\"))return{k:\"un\",op:J.v,arg:C()};throw new T(\"\")}function C(){let J=W();for(;;){let F=Q[$+1];if(K(\".\")){$++;let z=Q[$++];if(!z||z.t!==\"id\")throw new T(\"\");J={k:\"field\",of:J,name:z.v}}else if(K(\"[\")){$++;let z=M();V(\"]\"),J={k:\"index\",of:J,at:z}}else if(K(\"/\")&&F?.t===\"id\"&&![\"true\",\"false\",\"null\"].includes(F.v)&&!(Q[$+2]?.t===\"op\"&&Q[$+2].v===\"(\"))J={k:\"field\",of:J,name:F.v},$+=2;else return J}}function U(J){let F=C(),z=0;while(Q[$]?.t===\"op\"&&tQ[Q[$].v]>=J){let G=Q[$++].v;F={k:\"bin\",op:G,left:F,right:U(tQ[G]+1)}}return F}function M(){let J=U(1);if(!K(\"?\"))return J;$++;let F=M();V(\":\");let z=M();return{k:\"if\",test:J,yes:F,no:z}}return{tree:M(),used:$-Z}}function CQ(Q){let Z=OZ(Q),{tree:$,used:q}=SZ(Z);if(q!==Z.length)throw new T(\"\");return $}function EZ(Q){let Z=Q.trim();if(!Z||Z.startsWith(\"#\"))return null;let $=(K,V,Y)=>({kind:K,key:V,tree:CQ(Y),raw:Z}),q;if(q=/^delete\\s+check\\s*\\(\\s*\"(.*)\"\\s*\\)$/.exec(Z))return{kind:\"delete\",key:\"check:\"+q[1],tree:null,raw:Z};if(q=/^delete\\s+(?:need\\s+)?(\\S+)$/.exec(Z))return{kind:\"delete\",key:q[1],tree:null,raw:Z};if(q=/^need\\s+([\\p{L}_][\\p{L}\\p{N}_]*)\\s*=(?!=)(.*)$/u.exec(Z))return $(\"need\",q[1],q[2]);if(q=/^(\\$\\/[\\p{L}\\p{N}_/]+)\\s*:=(.*)$/u.exec(Z))return $(\"formula\",q[1],q[2]);if(q=/^(\\$\\/[\\p{L}\\p{N}_/]+)\\s*=(?!=)(.*)$/u.exec(Z))return $(\"state\",q[1],q[2]);if(/^app\\s*\\(/.test(Z))return $(\"app\",\"app\",Z);if(/^check\\s*\\(/.test(Z)){let K=CQ(Z);return{kind:\"check\",key:\"check:\"+(K.args[0]?.v??\"\"),tree:K,raw:Z}}if(q=/^([\\p{L}_][\\p{L}\\p{N}_]*)\\s*=(?!=)(.*)$/u.exec(Z))return $(\"component\",q[1],q[2]);throw new T(\"\")}class bQ{constructor(){this.entries=new Map,this.faults=[],this.partFaults=[],this.tops=new Map,this.removed=new Set}beginStep(){this.faults=[]}feed(Q,Z=null,$=\"author\"){let q;try{q=EZ(Q)}catch(V){if(!(V instanceof T))throw V;return this.faults.push({line:Z,text:Q.trim(),message:V.message}),null}if(!q)return null;if(q.kind===\"delete\"){let V=[...this.entries.keys()].find((Y)=>Y===q.key||Y===\"need:\"+q.key);if(V)this.entries.delete(V),this.removed.add(V);else this.faults.push({line:Z,text:q.raw,message:\"\"});return q}let K=q.kind===\"need\"?\"need:\"+q.key:q.key;if(q.origin=$,$===\"author\")this.removed.delete(K);return this.entries.set(K,q),q}feedText(Q){return Q.split(`\n`).forEach((Z,$)=>this.feed(Z,$+1)),this}open(Q){return!this.removed.has(Q)&&(this.entries.get(Q)?.origin??\"part\")===\"part\"}of(Q){return[...this.entries.values()].filter((Z)=>Z.kind===Q)}get(Q){return this.entries.get(Q)}text(){return[...this.entries.values()].map((Q)=>Q.raw).join(`\n`)+`\n`}}var _Q={Column:[\"children\"],Row:[\"children\"],Card:[\"children\"],Section:[\"title\",\"children\"],Text:[\"text\"],Heading:[\"text\"],Metric:[\"label\",\"value\"],Emoji:[\"value\",\"label\"],Badge:[\"value\"],Grid:[\"children\"],Progress:[\"value\",\"max\",\"label\"],Ring:[\"value\",\"max\",\"text\",\"label\"],Divider:[],Notice:[\"text\"],Button:[\"label\",\"action\"],IconButton:[\"icon\",\"action\",\"label\"],TextField:[\"label\",\"bind\"],NumberField:[\"label\",\"bind\"],Slider:[\"label\",\"bind\"],RangeSlider:[\"label\",\"bind\"],Toggle:[\"label\",\"bind\"],CheckBox:[\"label\",\"bind\"],Segmented:[\"label\",\"bind\",\"options\"],Select:[\"label\",\"bind\",\"options\"],List:[\"items\",\"template\",\"group\"],Chart:[\"items\",\"point\",\"value\",\"label\"],Tabs:[\"titles\",\"groups\",\"label\"],Dialog:[\"title\",\"children\"],DateField:[\"label\",\"bind\"],TimeField:[\"label\",\"bind\"],Picture:[\"text\",\"found\"],Choices:[\"label\",\"bind\",\"options\"],Timer:[\"label\",\"seconds\"],Drawing:[\"label\",\"width\",\"height\",\"shapes\"],Model3d:[\"label\",\"bodies\",\"bind\",\"exploded\",\"selected\"],Link:[\"label\",\"url\",\"text\"]},QZ={ArrowUp:\"Arrow\",ArrowDown:\"Sort\",ArrowLeft:\"Back\",ArrowRight:\"Chevron\",Edit:\"Pencil\"},gZ=new Set([\"visible\",\"enabled\",\"variant\",\"tone\",\"emphasis\",\"weight\",\"min\",\"max\",\"step\",\"empty\",\"placeholder\",\"gap\",\"selected\",\"kind\",\"size\",\"align\",\"columns\",\"icon\"]),wZ=new Set([\"set\",\"toggle\",\"inc\",\"dec\",\"append\",\"remove\",\"clear\",\"when\",\"move\",\"flip\",\"animate\"]),vZ={circle:[\"x\",\"y\",\"r\"],rect:[\"x\",\"y\",\"w\",\"h\"],line:[\"x1\",\"y1\",\"x2\",\"y2\"],text:[\"x\",\"y\",\"text\"],polygon:[\"points\"]},hZ={box:[\"part\",\"x\",\"y\",\"z\",\"w\",\"h\",\"d\"],cylinder:[\"part\",\"x\",\"y\",\"z\",\"radius\",\"length\",\"axis\"]},fZ=new Set([\"text\",\"part\",\"axis\"]),mZ=[\"radius\",\"width\",\"size\",\"stroke\"],yZ={text:\"size\",line:\"width\",rect:\"radius\"},WQ={ink:\"#0f172a\",muted:\"#94a3b8\",accent:null,surface:\"#ffffff\",water:\"#38bdf8\",land:\"#65a30d\",sun:\"#facc15\",sand:\"#e7d3a8\",wood:\"#a16207\",success:\"#16a34a\",warning:\"#ea580c\"},ZZ=200000,kZ=20000,uZ=4096,YQ=(Q,Z=0)=>{let $=10**Z;return Math.round((Q+Number.EPSILON)*$)/$},XQ=(Q)=>Array.isArray(Q)?Q.map(XQ):Q&&typeof Q===\"object\"?Object.fromEntries(Object.entries(Q).map(([Z,$])=>[Z,XQ($)])):Q;function fQ(Q){let Z=[\"x\",\"y\",\"z\"].map(($)=>Q.reduce((q,K)=>q+K[$],0)/(Q.length||1));return Q.map(($)=>$.part?{...$,...Object.fromEntries([\"x\",\"y\",\"z\"].map((q,K)=>[q,YQ($[q]+($[q]-Z[K])/2,2)]))}:$)}var eQ={de:\".,\",es:\".,\",it:\".,\",pt:\".,\",tr:\".,\",ru:\" ,\",fr:\" ,\",pl:\" ,\",uk:\" ,\"};function LQ(Q,Z,$,q){let[K,V]=Object.hasOwn(eQ,Q)?eQ[Q]:\",.\",Y=Number(Z).toFixed($),W=/^-?(\\d+)\\.?(\\d*)$/.exec(Y);if(!W)return Y;let[,H,C]=W,U=q?C.replace(/0+$/,\"\"):C;return(Number(Y)<0?\"-\":\"\")+(H.length<5&&/^(es|pl|pt)$/.test(Q)?H:H.replace(/\\B(?=(\\d{3})+$)/g,K))+(U?V+U:\"\")}var gQ={fixed:(Q,Z,$=2)=>LQ(Q,Z,$),money:(Q,Z,$=\"\")=>$+LQ(Q,Z,2),percent:(Q,Z,$=0)=>LQ(Q,Z,$)+\"%\",amount:(Q,Z,$=0)=>LQ(Q,YQ(Number(Z),$),$,!0)},wQ={round:YQ,floor:Math.floor,ceil:Math.ceil,abs:Math.abs,sin:(Q)=>YQ(Math.sin(Q*Math.PI/180),12)+0,cos:(Q)=>YQ(Math.cos(Q*Math.PI/180),12)+0,pow:Math.pow,sqrt:Math.sqrt,exp:Math.exp,ln:Math.log,log10:Math.log10,min:(...Q)=>Q.flat(3).length?Math.min(...Q.flat(3)):0,max:(...Q)=>Q.flat(3).length?Math.max(...Q.flat(3)):0,clamp:(Q,Z,$)=>Math.min($,Math.max(Z,Q)),len:(Q)=>Q==null?0:Q.length,text:(Q)=>String(Q??\"\"),number:(Q)=>Number(Q)||0,pad2:(Q)=>String(Math.floor(Q)).padStart(2,\"0\"),join:(Q,Z=\", \")=>{let $=Q||[];if($.length*String(Z).length>ZZ)throw new T(\"\");return $.join(Z)},upper:(Q)=>String(Q).toUpperCase(),lower:(Q)=>String(Q).toLowerCase(),at:(Q,Z)=>Array.isArray(Q)?Q[Z]??null:null,streak:(Q)=>(Q??[]).reduce(([Z,$],q)=>q?[Math.max(Z,$+1),$+1]:[Z,0],[0,0])[0],near:(Q,Z)=>Math.abs(Q-Z)<=Math.max(0.01,Math.abs(Z)/1000),trim:(Q)=>String(Q).trim(),contains:(Q,Z)=>String(Q).toLowerCase().includes(String(Z).toLowerCase()),has:(Q,Z)=>Array.isArray(Q)&&Q.includes(Z),hours:(Q,Z)=>{let[$,q]=[Q,Z].map(xZ);return $===null||q===null?0:(q-$+1440)%1440/60},today:()=>{let Q=new Date(ZQ.now());return`${Q.getFullYear()}-${String(Q.getMonth()+1).padStart(2,\"0\")}-${String(Q.getDate()).padStart(2,\"0\")}`},split:(Q,Z=\",\")=>String(Q??\"\").split(String(Z)||\",\").map(($)=>$.trim()).filter(Boolean),weekday:(Q)=>{let Z=t(Q);return Z===null?0:(new Date(Z*86400000).getUTCDay()+6)%7+1},month:(Q)=>t(Q)===null?0:Number(String(Q).trim().slice(5,7)),year:(Q)=>t(Q)===null?0:Number(String(Q).trim().slice(0,4)),days:(Q,Z)=>{let[$,q]=[t(Q),t(Z)];return $===null||q===null?0:q-$},addDays:(Q,Z)=>{let $=t(Q);return $===null?null:new Date(($+Math.round(Number(Z)||0))*86400000).toISOString().slice(0,10)}};function t(Q){let Z=/^(\\d{4})-(\\d{2})-(\\d{2})$/.exec(String(Q??\"\").trim());if(!Z)return null;let $=Date.UTC(Number(Z[1]),Number(Z[2])-1,Number(Z[3])),q=new Date($);return q.getUTCMonth()===Number(Z[2])-1&&q.getUTCDate()===Number(Z[3])?$/86400000:null}function xZ(Q){let Z=/^(\\d{1,2}):(\\d{2})$/.exec(String(Q??\"\").trim());return Z?Number(Z[1])*60+Number(Z[2]):null}var $Z=(Q,Z)=>Array.isArray(Q)&&Array.isArray(Z)?Q.reduce(($,q,K)=>$||$Z(q,Z[K]),0):Q<Z?-1:Q>Z?1:0,vQ={count:(Q,Z)=>Z?Q.filter(Z).length:Q.length,sum:(Q,Z)=>Q.reduce(($,q)=>$+Number(Z?Z(q):q),0),avg:(Q,Z)=>Q.length?Q.reduce(($,q)=>$+Number(Z?Z(q):q),0)/Q.length:0,filter:(Q,Z)=>Q.filter(Z),any:(Q,Z)=>Q.some(Z),all:(Q,Z)=>Q.every(Z),map:(Q,Z)=>Q.map(Z),find:(Q,Z)=>Q.find(Z)??null,sort:(Q,Z,$)=>[...Q].sort((q,K)=>$Z(Z(q),Z(K))*($===\"desc\"?-1:1)),groups:(Q,Z,$)=>{if(!Z)throw new T(\"\");let q=new Map;for(let K of Q)q.set(Z(K),(q.get(Z(K))??0)+Number($?$(K):1));return[...q].map(([K,V])=>({key:K,value:V}))}},D0=new Set([...Object.keys(wQ),...Object.keys(gQ),...Object.keys(vQ),\"if\"]);function hQ(Q,Z){if(Q===Z)return!0;if(typeof Q!==\"object\"||typeof Z!==\"object\"||!Q||!Z||Array.isArray(Q)!==Array.isArray(Z))return!1;let $=(q)=>Object.keys(q).filter((K)=>K!==\"id\");return $(Q).length===$(Z).length&&$(Q).every((q)=>hQ(Q[q],Z[q]))}var cZ=(Q)=>Q.rest&&Q.rest.v!==0||Q.rounds&&Q.rounds.v!==1,mQ=(Q)=>Q.named.alert?.v===!0&&!cZ(Q.named),pZ=(Q)=>Q.of(\"need\").filter((Z)=>Z.tree.name===\"entries\"&&Z.tree.named?.show?.k===\"map\").flatMap((Z)=>Z.tree.named.show.entries),yQ=(Q)=>({...Object.fromEntries((_Q[Q.name]??[]).map((Z,$)=>[Z,Q.args[$]])),...Q.named});class kQ{constructor(Q,Z=!0){if(this.file=Q,this.faults=[],this.nextId=1,this.state={},this.seen=new Map,this.steps=0,this.tripped=!1,Z)this.follow()}follow(){for(let Q of this.file.of(\"state\")){if(this.seen.get(Q.key)===Q.raw)continue;this.seen.set(Q.key,Q.raw),this.write(this.pathOf(Q.key),this.attempt(Q,()=>XQ(this.value(Q.tree,{}))),{})}this.tagRows(this.state),this.recompute()}fault(Q,Z){this.faults.push({where:Q,message:Z})}step(Q=1){if((this.steps+=Q)>ZZ||this.tripped)this.stop(\"\")}stop(Q){throw this.tripped=!0,new T(Q)}charge(Q){if(typeof Q===\"string\")this.step(Q.length>>4);else if(Q&&typeof Q===\"object\"){this.step();for(let Z of Object.values(Q))this.charge(Z)}return Q}attempt(Q,Z){try{return Z()}catch($){return this.fault(Q.key,$.message),null}}pathOf(Q){return{k:\"path\",abs:!0,segs:Q.slice(2).split(\"/\")}}tagRows(Q){if(Array.isArray(Q))Q.forEach((Z)=>{if(Z&&typeof Z===\"object\"&&!Array.isArray(Z))Z.id??=\"r\"+this.nextId++,this.tagRows(Z)});else if(Q&&typeof Q===\"object\")Object.values(Q).forEach((Z)=>this.tagRows(Z))}read(Q,Z){let $=Q.abs?this.state:Z.it;for(let q of Q.segs)$=$==null?void 0:$[q];return $}place(Q,Z){if(Q.k===\"path\"){let q=Q.abs?this.state:Z.it;if(q==null)throw new T(\"\");if(typeof q!==\"object\")throw new T(\"\");for(let K of Q.segs.slice(0,-1))q=q[K]??={};return[q,Q.segs.at(-1)]}if(Q.k!==\"field\"&&Q.k!==\"index\")throw new T(\"\");let $=this.value(Q.of,Z);if($==null||typeof $!==\"object\")throw new T(\"\");return[$,Q.k===\"field\"?Q.name:this.value(Q.at,Z)]}write(Q,Z,$){let[q,K]=this.place(Q,$);q[K]=Z}value(Q,Z){switch(this.step(),Q.k){case\"lit\":return Q.v;case\"path\":if(!Q.abs&&Q.segs[0]===\"it\")return Q.segs.slice(1).reduce(($,q)=>$?.[q],Z.it);if(!Q.abs&&Z.each!==void 0&&Q.segs.join()===\"index\"&&!(Z.it&&typeof Z.it===\"object\"&&(\"index\"in Z.it)))return Z.each;if(!Q.abs&&Z.it===void 0)throw new T(\"\");if(!Q.abs&&Z.it!==void 0&&(Z.it===null||typeof Z.it!==\"object\"))throw new T(\"\");if(!Q.abs&&Z.it&&!(Q.segs[0]in Z.it)){let $=Q.segs[0];if(this.shown??=Object.fromEntries(pZ(this.file)),Q.segs.length===1&&this.shown[$]&&!Z.busy?.includes($))return this.value(this.shown[$],{...Z,busy:[...Z.busy??[],$]});throw new T(\"\")}return this.read(Q,Z);case\"list\":return this.charge(Q.items.map(($)=>this.value($,Z)));case\"map\":return this.charge(Object.fromEntries(Q.entries.map(([$,q])=>[$,this.value(q,Z)])));case\"field\":return this.value(Q.of,Z)?.[Q.name];case\"index\":{let $=this.value(Q.of,Z);return Array.isArray($)?$[this.value(Q.at,Z)]??null:null}case\"un\":return Q.op===\"-\"?-this.value(Q.arg,Z):!this.value(Q.arg,Z);case\"if\":return this.value(Q.test,Z)?this.value(Q.yes,Z):this.value(Q.no,Z);case\"bin\":{if(Q.op===\"&&\")return this.value(Q.left,Z)&&this.value(Q.right,Z);if(Q.op===\"||\")return this.value(Q.left,Z)||this.value(Q.right,Z);let $=this.value(Q.left,Z),q=this.value(Q.right,Z);switch(Q.op){case\"+\":return this.charge(Array.isArray($)&&Array.isArray(q)?[...$,...q]:typeof $===\"string\"||typeof q===\"string\"?String($??\"\")+String(q??\"\"):$+q);case\"-\":return $-q;case\"*\":return $*q;case\"/\":return $/q;case\"%\":return $%q;case\"==\":return hQ($,q);case\"!=\":return!hQ($,q);case\"<\":return $<q;case\"<=\":return $<=q;case\">\":return $>q;case\">=\":return $>=q}break}case\"call\":{if(Q.name===\"if\")return this.value(Q.args[0],Z)?this.value(Q.args[1],Z):this.value(Q.args[2],Z);if(vQ[Q.name]){let $=this.value(Q.args[0],Z);if(!Array.isArray($))throw new T(\"\");let q=(K)=>K&&((V)=>this.value(K,{...Z,it:V}));return this.charge(vQ[Q.name]($,q(Q.args[1]),Q.name===\"groups\"?q(Q.args[2]):Q.args[2]&&this.value(Q.args[2],Z)))}if(wQ[Q.name])return this.charge(wQ[Q.name](...Q.args.map(($)=>this.value($,Z))));if(gQ[Q.name])return this.charge(gQ[Q.name](this.file.get(\"app\")?.tree.named.lang?.v,...Q.args.map(($)=>this.value($,Z))));if(_Q[Q.name])throw new T(\"\");throw new T(\"\")}case\"ref\":throw new T(\"\")}throw new T(\"\")}drawn(Q,Z,$,q){if(this.step(),$.k===\"list\")return $.items.flatMap((P)=>this.drawn(Q,Z,P,q));let{name:K=\"\",args:V=[],named:Y={}}=$.k===\"call\"?$:{};if(K===\"each\"&&V.length===2){let P=this.value(V[0],q),J=Array.isArray(P)?P:Number.isInteger(P)&&P>=0&&P<=500?Array.from({length:P},(F,z)=>z):null;if(!J)throw new T(\"\");return J.flatMap((F,z)=>this.drawn(Q,Z,V[1],{...q,it:F,each:z}))}let W=Object.hasOwn(Q,K)?Q[K]:null;if(!W)throw new T(\"\");let H=yZ[K],C=\"\";if(V.length<W.length||V.length>W.length+(H?2:1))throw new T(\"\");if(Y.visible&&!this.value(Y.visible,q))return[];let U=(P,J)=>{if(P===null||P===\"\"||typeof P===\"boolean\"||!Number.isFinite(Number(P)))throw new T(\"\");return YQ(Number(P),2)},M={[Z]:K};if(W.forEach((P,J)=>{let F=this.value(V[J],q);M[P]=fZ.has(P)?String(F??\"\"):P===\"points\"?[F].flat().map((z)=>U(z,P)):U(F,P)}),M.axis!==void 0&&![\"x\",\"y\",\"z\"].includes(M.axis))throw new T(\"\");if(M.color=String((V[W.length]?this.value(V[W.length],q):null)??\"ink\"),V.length===W.length+2)M[H]=U(this.value(V[W.length+1],q),H);for(let[P,J]of Object.entries(Y)){if(P===\"visible\")continue;if(!mZ.includes(P))throw new T(\"\");let F=this.value(J,q);if(P!==\"stroke\")M[P]=U(F,P);else if(F)M.stroke=!0}if(++this.shapes>uZ)this.stop(\"\");return[M]}recompute(){let Q=this.file.of(\"formula\");for(let Z=0;Z<=Q.length;Z++){let $=JSON.stringify(this.state);for(let q of Q){if(this.tripped)return;this.attempt(q,()=>this.write(this.pathOf(q.key),this.value(q.tree,{}),{}))}if(JSON.stringify(this.state)===$)return}}act(Q,Z={}){for(let $ of Q.k===\"list\"?Q.items:[Q]){if($.k!==\"call\"||!wZ.has($.name))throw new T(\"\");let[q,K]=$.args;if($!==(Q.k===\"list\"?Q.items[0]:Q))this.recompute();let V=()=>{if(![\"path\",\"field\",\"index\"].includes(q?.k))throw new T(\"\");return q},Y=(W)=>{let H=this.value(W,Z);if(!Array.isArray(H))throw new T(\"\");return H};switch($.name){case\"set\":this.write(V(),XQ(this.charge(this.value(K,Z))),Z);break;case\"toggle\":this.write(V(),!this.value(V(),Z),Z);break;case\"inc\":this.write(V(),this.charge((this.value(V(),Z)||0)+(K?this.value(K,Z):1)),Z);break;case\"dec\":this.write(V(),(this.value(V(),Z)||0)-(K?this.value(K,Z):1),Z);break;case\"clear\":this.write(V(),[],Z);break;case\"flip\":{let W=Y(V()),H=this.charge(this.value(K,Z));this.write(V(),W.includes(H)?W.filter((C)=>C!==H):[...W,H],Z);break}case\"move\":{let W=$.args.length===1;if(W&&!Z.list)throw new T(\"\");let H=W?this.read(Z.list,{}):Y(V()),C=W?H.indexOf(Z.it):H.findIndex((M)=>this.value(K,{...Z,it:M})),U=C+this.value(W?q:$.args[2],Z);if(C>=0&&U>=0&&U<H.length)H.splice(U,0,H.splice(C,1)[0]);break}case\"when\":if(this.value(q,Z))this.act(K,Z);else this.recompute();break;case\"animate\":{if(q?.k!==\"path\"||!q.abs)throw new T(\"\");let W=q.segs.join(\"/\"),H=Number(this.value(q,Z))||0,C=K?Number(this.value(K,Z)):NaN,U=$.args[2]?Number(this.value($.args[2],Z)):0;if(!Number.isFinite(C)||!Number.isFinite(U))throw new T(\"\");let M=(this.state._run??[]).filter((J)=>J.path!==W),P=ZQ.now();if(U>0&&C!==H)M.push({path:W,from:H,to:C,start:P,end:P+U,at:H});else this.write(q,C,Z);if(M.length)this.state._run=M;else delete this.state._run;break}case\"append\":{let W=XQ(this.charge(this.value(K,Z)));Y(V()).push(W),this.tagRows([W]);break}case\"remove\":{if(!q){if(!Z.list)throw new T(\"\");this.write(Z.list,this.read(Z.list,{}).filter((W)=>W!==Z.it),{})}else this.write(V(),Y(V()).filter((W)=>!this.value(K,{...Z,it:W})),Z);break}}}this.recompute()}tree(Q=!1){this.reached=new Set;let Z=0,$=0,q=(V,Y,W,H,C)=>{if(this.step(),++$>kZ)this.stop(\"\");if(H>40)throw new T(\"\");if(V.k===\"ref\"){let J=this.file.get(V.name);if(this.reached.add(V.name),!J||J.kind!==\"component\")return{type:\"Missing\",name:V.name,props:{},children:[]};return q(J.tree,Y,V.name,H+1,[...C,V.name])}if(V.k!==\"call\"||!_Q[V.name])return this.fault(W??\"?\",\"\"),{type:\"Missing\",name:W,props:{},children:[]};let U={type:V.name,name:W,props:{},children:[],row:Y.it,rowIndex:Y.index,list:Y.list,call:V},M=yQ(V),P=[];for(let[J,F]of Object.entries(M)){if(F===void 0)continue;if(!_Q[V.name].includes(J)&&!gZ.has(J)&&!(J===\"action\"&&M.bind)){this.fault(W??V.name,\"\");continue}if(V.name===\"Chart\"&&(J===\"point\"||J===\"value\")||V.name===\"List\"&&J===\"group\"||J===\"shapes\"||J===\"bodies\")continue;try{if(J===\"children\")U.children=(F.k===\"list\"?F.items:[F]).map((G)=>q(G,Y,void 0,H+1,C));else if(J===\"groups\")P=F.k===\"list\"?F.items:[F];else if(J===\"action\")U.props.action=F;else if(J===\"bind\"){if(![\"path\",\"field\",\"index\"].includes(F.k))throw new T(\"\");U.props.bind=F,U.props.value=F.k===\"path\"?this.read(F,Y):this.value(F,Y)}else if(J===\"template\")U.template=F;else U.props[J]=this.value(F,Y);let z=U.props.options;if(J===\"options\"&&z&&typeof z===\"object\"&&!Array.isArray(z))[U.props.options,U.props.labels]=[Object.keys(z),Object.values(z)]}catch(z){this.fault(W??V.name,\"\")}}if(V.name===\"Chart\"){try{U.props.points=(Array.isArray(U.props.items)?U.props.items:[]).map((J)=>({label:M.point?this.value(M.point,{...Y,it:J}):\"\",value:Number(M.value?this.value(M.value,{...Y,it:J}):J)||0}))}catch(J){this.fault(W??V.name,\"\")}delete U.props.items}if(V.name===\"Drawing\"||V.name===\"Model3d\"){let J=V.name===\"Drawing\"?\"shapes\":\"bodies\",F=M[J]??{k:\"list\",items:[]};if(this.shapes=0,U.props[J]=(F.k===\"list\"?F.items:[F]).flatMap((z)=>{try{return this.drawn(J===\"shapes\"?vZ:hZ,J===\"shapes\"?\"shape\":\"body\",z,Y)}catch(G){return this.fault(W??V.name,\"\"),[]}}),J===\"bodies\"&&U.props.exploded)U.props.bodies=fQ(U.props.bodies)}if(V.name===\"Choices\"){let J=Array.isArray(U.props.value)?U.props.value:[],F=U.props.max!==void 0&&J.length>=U.props.max;U.children=(U.props.options??[]).map((z,G)=>({type:\"Button\",name:W&&`${W}_opt${G}`,children:[],props:{label:String(U.props.labels?.[G]??z),selected:J.includes(z),enabled:J.includes(z)||!F,action:{k:\"call\",name:\"flip\",args:[M.bind,{k:\"lit\",v:z}],named:{}}}}))}if(V.name===\"Tabs\"){let J={k:\"path\",abs:!0,segs:[\"_ui\",W??\"tabs\"+Z++]},F=this.read(J,{})??0;U.props.value=F,U.children=[{type:\"Row\",props:{},children:(U.props.titles??[]).map((z,G)=>({type:\"Button\",name:W&&`${W}_tab${G}`,tab:!0,children:[],props:{label:z,selected:G===F,action:{k:\"call\",name:\"set\",args:[J,{k:\"lit\",v:G}],named:{}}}}))},...P.map((z,G)=>({type:\"Column\",props:{visible:G===F},children:(z.k===\"list\"?z.items:[z]).map((X)=>q(X,Y,void 0,H+1,C))}))]}if(V.name===\"List\"&&U.template&&Array.isArray(U.props.items)){let J=(X,N=0)=>{if(!X||N>8)return null;if(X.k===\"call\"&&[\"sort\",\"filter\"].includes(X.name))return J(X.args[0],N+1);if(X.k===\"if\")return J(X.yes,N+1)??J(X.no,N+1);if(X.k!==\"path\")return null;let R=X.abs?this.file.get(\"$/\"+X.segs.join(\"/\")):null;return R?.kind===\"formula\"?J(R.tree,N+1):X},F=J(M.items),z=U.props.items,G=[];if(M.group)try{let X=z.map((R)=>this.value(M.group,{...Y,it:R})),N=[...new Set(X)].flatMap((R)=>z.map((E,g)=>[E,R,g]).filter((E)=>X[E[2]]===R));z=N.map((R)=>R[0]),G=N.map((R,E)=>E===0||N[E-1][1]!==R[1]?[{type:\"Heading\",props:{text:String(R[1]??\"\")},children:[]}]:[])}catch(X){this.fault(W??V.name,\"\")}U.children=z.flatMap((X,N)=>[...G[N]??[],q(U.template,{it:X,index:N,list:F},void 0,H+1,C)]),U.source=F,U.blank=(X)=>q(U.template,{it:X,index:0,list:F},void 0,H+1,C)}return U},K=this.file.get(\"root\");if(!K)return{type:\"Missing\",name:\"root\",props:{},children:[]};try{return q(K.tree,{},\"root\",0,[\"root\"])}catch(V){return this.fault(\"root\",V.message),{type:\"Missing\",name:\"root\",props:{},children:[]}}}find(Q,Z){let $=(q)=>q.name===Q&&(Z===void 0||q.rowIndex===Z)?q:q.children.reduce((K,V)=>K??$(V),null);return $(this.tree())}tick(){let Q=ZQ.now(),Z=this.state;if(Z._run){if(Z._run=Z._run.filter(($)=>{let q=this.pathOf(\"$/\"+$.path);if(this.read(q,{})!==$.at)return!1;return $.at=Q>=$.end?$.to:$.from+($.to-$.from)*Math.max(0,Q-$.start)/($.end-$.start),this.write(q,$.at,{}),Q<$.end}),!Z._run.length)delete Z._run;this.recompute()}for(let $ of this.file.of(\"need\")){if($.tree.name!==\"timer\"||mQ($.tree))continue;let q=$.key,K=(Y,W)=>{try{return Number(Y?this.value(Y,{}):W)||0}catch{return 0}},V=()=>Number(Z[q+\"_phase\"])||0;if(!Z[q+\"_on\"]){Z[q+\"_end\"]=0;continue}if(!Z[q+\"_end\"])Z[q+\"_end\"]=Q+Math.max(0,V()-Z[q+\"_spent\"])*1000;for(let Y=0;Z[q+\"_on\"]&&Q>=Z[q+\"_end\"]&&Y<1000;Y++){let W=Z[q+\"_end\"];if(!Z[q+\"_resting\"])Z[q]+=1;if(!Z[q+\"_resting\"]&&K($.tree.named.rest,0)>0)Z[q+\"_resting\"]=!0,Z[q+\"_spent\"]=0;else if(Z[q+\"_round\"]>=K($.tree.named.rounds,1))Z[q+\"_over\"]=!0,Z[q+\"_on\"]=!1,Z[q+\"_spent\"]=V(),Z[q+\"_end\"]=0;else Z[q+\"_round\"]+=1,Z[q+\"_resting\"]=!1,Z[q+\"_spent\"]=0;if(this.recompute(),Z[q+\"_on\"])Z[q+\"_end\"]=W+V()*1000}if(Z[q+\"_on\"])Z[q+\"_spent\"]=Math.max(0,V()-(Z[q+\"_end\"]-Q)/1000);this.recompute()}}change(Q,Z){this.tick();try{if(this.write(Q.props.bind,Z,{it:Q.row}),Q.props.action)this.act(Q.props.action,{it:Q.row,list:Q.list});else this.recompute()}catch($){return this.failed(Q,$)}return this.tick(),\"\"}fire(Q){if(Q.props.enabled===!1||Q.props.visible===!1)return`${Q.name??Q.type} is not active`;this.tick();try{this.act(Q.props.action,{it:Q.row,list:Q.list})}catch(Z){return this.failed(Q,Z)}return this.tick(),\"\"}failed(Q,Z){return`${Q.name??Q.type}: ${Z.message}`}input(Q,Z,$){this.steps=0;let q=this.find(Q,$);if(q?.props.bind)return this.change(q,Z);return(!q?`no field named ${Q}`:q.props.action?`${Q} is not a field: use tap(${Q})`:`${Q} only shows a value and cannot be set. A check sets a field, such as a NumberField`)+($===void 0?\"\":` in row ${$}`)}press(Q,Z){this.steps=0;let $=this.find(Q,Z);if($?.props.action)return this.fire($);return($?.props.bind?`${Q} is a field with no action: use set(${Q}, value)`:`no control named ${Q}`)+(Z===void 0?\"\":` in row ${Z}`)}}var ZQ={gate:\"smoke\",now:()=>Date.now()};var lZ=/^[A-Za-z][A-Za-z0-9_]{0,47}$/,iZ=new Set([\"__proto__\",\"prototype\",\"constructor\"]);var uQ=100,dZ={time:\"number\",date:\"string\"};function xQ(Q={},Z=new Set(Object.values(Q))){let $=new Map,q=0;return(K,V=K)=>{if($.has(K))return $.get(K);let Y=Object.hasOwn(Q,K)?Q[K]:V;if(!Object.hasOwn(Q,K)&&(!lZ.test(Y)||iZ.has(Y)||/^[dv]\\d*$/.test(Y)||Z.has(Y))){while(Z.has(\"f\"+q))q++;Y=Q[K]=\"f\"+q}return $.set(K,Y),Z.add(Y),Y}}var jQ=(Q)=>Q.replaceAll(\":\",\"_\"),qZ=(Q)=>String(Q).padStart(2,\"0\"),sZ=(Q)=>{let Z=/^(\\d{1,2}):(\\d{2})$/.exec(String(Q??\"\").trim());return Z&&Number(Z[1])<24&&Number(Z[2])<60?Number(Z[1])*60+Number(Z[2]):0},NQ=(Q)=>{let Z=Math.min(1439,Math.max(0,Math.round(Number(Q)||0)));return`${qZ(Math.floor(Z/60))}:${qZ(Z%60)}`},rZ=2048,a=(Q,Z,$)=>Q===\"time\"?sZ(Z):Q===\"date\"?t(Z)===null?$:String(Z).trim():Q===\"number\"?Number.isFinite(Z)?Math.min(Number.MAX_SAFE_INTEGER,Math.max(-Number.MAX_SAFE_INTEGER,Z)):0:Q===\"boolean\"?Z===!0:(Z===null||Z===void 0||typeof Z===\"object\"?\"\":String(Z)).slice(0,rZ),cQ=(Q,Z,$)=>({id:String(Q?.id??\"\"),...Object.fromEntries(Z.map(([q,K,V])=>[K,a(V,Q?.[q],$)]))}),$Q=(Q,Z)=>Object.hasOwn(Q,Z)?Q[Z]:void 0,RQ=(Q)=>Q?.k===\"path\"&&!Q.abs&&Q.segs.length===1?Q.segs[0]:null;function KZ(Q,Z,$,q,K,V,Y){let{kit:W,name:H,walk:C,day:U,fields:M,lists:P,views:J,viewSchemas:F,viewOwners:z,listRoots:G}=Q,X=Z.source,N=X?.k===\"path\"&&X.abs&&X.segs.length===1&&P[X.segs[0]]?X.segs[0]:null;if(!N||!Z.blank)return V.fault(\"\"),null;let R=P[N],E=xQ();E(\"$id\",\"id\"),R.forEach(([L,A])=>E(\"$\"+L,A));let g={key:$,name:E,stored:new Map(R.map(([L,A,w])=>[L,[A,w]])),computed:new Map,flags:{moves:!1},out:null,id:null},f={id:\"\",...Object.fromEntries(R.map(([L,,A])=>[L,A===\"number\"?0:A===\"boolean\"?!1:\"\"]))},l=(L)=>L.name??`${$}:row`,r=Z.blank(f),e=C(r,l(r),q,{scope:g,weighted:!1}),u=K.group,QQ=g.stored.get(RQ(u)),x=u?QQ&&QQ[1]!==\"time\"?QQ[0]:E(\"~group\",\"group\"):null;if(u&&!(QQ&&QQ[1]!==\"time\"))g.computed.set(x,\"string\");let PQ=(L)=>L?.k===\"path\"?L.abs&&L.segs.length===1&&L.segs[0]===N:L?.k===\"call\"&&L.name===\"filter\"&&PQ(L.args[0]),qQ=g.flags.moves,o=qQ?E(\"~label\",\"label\"):null;if(o)g.computed.set(o,\"string\");let SQ=Z.children.filter((L)=>L.row).map((L)=>{let A={};if(C(L,l(L),q,{scope:{...g,out:A,id:String(L.row.id??\"\")},weighted:!1,off:V.off}),g.computed.has(x))try{A[x]=a(\"string\",W.value(u,{it:L.row}))}catch{A[x]=\"\"}if(o)A[o]=(R.filter(([,,w])=>w===\"string\").map(([w])=>a(\"string\",L.row[w]).trim()).find(Boolean)??String(L.row.id)).slice(0,300);return{...cQ(L.row,R,U),...A}}),EQ=!(K.items?.k===\"path\"&&PQ(K.items))||g.computed.size>0,j=EQ?H(\"view:\"+$,jQ($)):M.get(N);if(EQ){let L={id:\"string\",...Object.fromEntries(R.map(([,w,m])=>[w,$Q(dZ,m)??m])),...Object.fromEntries(g.computed)},A=Object.keys(L).length;J[j]=SQ,F[j]={type:\"list\",maxItems:uQ,fields:L},z.push(q)}let D=qQ&&V.action((L)=>{let A=W.read(X,{}),w=A.findIndex((O)=>O.id===L?.sourceKey),m=A.findIndex((O)=>O.id===L?.destinationKey);return w<0||m<0?!1:W.fire({props:{action:{k:\"call\",name:\"move\",args:[{k:\"lit\",v:m-w}],named:{}}},row:A[w],list:X})},\"_move\"),h={type:x?\"groupedList\":\"list\",text:V.caption(Y),bind:j,...x&&{groupBy:x},...Z.props.empty&&{emptyText:V.plain(Z.props.empty).slice(0,300)},...D&&{reorder:{action:D,labelField:o}},rowTemplate:e};return G.set(h,N),h}var c={width:1024,height:64},iQ=(Q)=>Math.min(2048,Math.max(64,Math.round(Number(Q))||0)),lQ={commands:512,pairs:2048,chars:2048,bytes:32768},aZ={ink:\"default\",muted:\"muted\",accent:\"accent\",success:\"success\",warning:\"warning\"},oZ=Object.fromEntries(Object.keys(WQ).map((Q)=>[Q,aZ[Q]??WQ[Q]])),GZ=/^#(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/,nZ=(Q,Z)=>Object.hasOwn(Z,Q)?Z[Q]:GZ.test(Q)?Q:\"default\",tZ=(Q)=>{let Z=0;for(let $ of Q){let q=$.codePointAt(0);Z+=q<128?1:q<2048?2:q<65536?3:4}return Z},DQ=(Q)=>tZ(JSON.stringify(Q).replace(/\\//g,\"\\\\/\")),p=(Q)=>Math.round(Q*100)/100,pQ=(Q,Z,$)=>Math.min($,Math.max(Z,Q)),GQ=(Q)=>Q.every((Z)=>Number.isFinite(Z)&&Math.abs(Z)<=8192),VZ=(Q)=>({type:\"polyline\",points:[...Q,Q[0]],stroke:2});function eZ({shape:Q,x:Z,y:$,r:q,w:K,h:V,x1:Y,y1:W,x2:H,y2:C,text:U,points:M,radius:P,width:J,size:F,stroke:z}){if(Q===\"circle\"){if(!(q>=0&&q<=2048)||!GQ([Z-q,$-q,Z+q,$+q]))return null;let G=p(2*q);return z?{type:\"arc\",x:p(Z-q),y:p($-q),width:G,height:G,start:0,sweep:360,stroke:2}:{type:\"circle\",x:Z,y:$,radius:q}}if(Q===\"rect\"){let G={x:Math.min(Z,p(Z+K)),y:Math.min($,p($+V)),width:Math.abs(K),height:Math.abs(V)},X=p(G.x+G.width),N=p(G.y+G.height);if(!GQ([G.x,G.y,X,N])||G.width>8192||G.height>8192)return null;if(z)return VZ([[G.x,G.y],[X,G.y],[X,N],[G.x,N]]);return P?{type:\"roundRect\",...G,radius:pQ(P,0,1024)}:{type:\"rect\",...G}}if(Q===\"line\")return GQ([Y,W,H,C])?{type:\"line\",x:Y,y:W,x2:H,y2:C,stroke:pQ(J??2,0.1,128)}:null;if(Q===\"text\")return GQ([Z,$])?{type:\"text\",x:Z,y:$,size:pQ(F??14,1,128),text:U.slice(0,lQ.chars),align:\"center\",baseline:\"middle\"}:null;if(Q===\"polygon\"){let G=[];for(let X=0;X+1<M.length;X+=2)G.push([M[X],M[X+1]]);if(!GQ(G.flat())||G.length<(z?2:3)||G.length>(z?2047:2048))return null;return z?VZ(G):{type:\"polygon\",points:G}}return null}var dQ=(Q,Z=oZ)=>Q.flatMap(($)=>{let q=eZ($);return q?[{...q,color:nZ($.color,Z)}]:[]}),HZ=(Q,Z)=>[{shape:\"rect\",x:0,y:0,w:c.width,h:c.height,color:\"muted\",radius:c.height/2},{shape:\"circle\",x:c.width-c.height,y:c.height/2,r:10,color:Z},{shape:\"rect\",x:0,y:0,w:Math.round(c.width*Q),h:c.height,color:Z,radius:c.height/2}],HQ={width:320,height:240},zZ={type:\"rect\",x:0,y:0,...HQ,color:\"muted\"},IQ={graphite:\"#242424\",blue:\"#0056d0\",teal:\"#006b5f\",green:\"#006e1d\",amber:\"#7f5700\",orange:\"#b02e00\",red:\"#c00015\",pink:\"#bc004f\",purple:\"#9e00c3\",indigo:\"#1a41ff\",brown:\"#a83900\"},JZ={clean:\"blue\",soft:\"teal\",expressive:\"purple\",editorial:\"brown\",technical:\"indigo\"},UZ=(Q,Z)=>{let $=Q||(Object.hasOwn(JZ,Z)?JZ[Z]:\"orange\");return Object.hasOwn(IQ,$)?IQ[$]:IQ.graphite},Q0=[-0.42,0.76,0.5],TQ=0.38,AQ=12,Z0=(Q,Z)=>{let $=Q===\"accent\"?Z:Object.hasOwn(WQ,Q)?WQ[Q]:GZ.test(Q)?Q:WQ.ink,q=$.length===7?\"ff\"+$.slice(1):$.slice(1);return[0,2,4,6].map((K)=>parseInt(q.slice(K,K+2),16))},YZ=(Q)=>Math.min(255,Math.max(0,Math.round(Q))).toString(16).padStart(2,\"0\"),BQ=(Q,Z)=>Q.map(($,q)=>$-Z[q]),$0=(Q,Z)=>[Q[1]*Z[2]-Q[2]*Z[1],Q[2]*Z[0]-Q[0]*Z[2],Q[0]*Z[1]-Q[1]*Z[0]],WZ=(Q,Z)=>Q[0]*Z[0]+Q[1]*Z[1]+Q[2]*Z[2];function q0({body:Q,x:Z,y:$,z:q,w:K,h:V,d:Y,radius:W,length:H,axis:C}){let U=[Z,$,q];if(Q===\"box\"){let z=[K,V,Y].map((X)=>Math.abs(X)/2),G=[];for(let X of[0,1,2])for(let N of[-1,1]){let R=[[-1,-1],[1,-1],[1,1],[-1,1]].map(([E,g])=>{let f=[0,0,0];return f[X]=N,f[(X+1)%3]=E,f[(X+2)%3]=g,U.map((l,r)=>l+f[r]*z[r])});G.push(N>0?R:R.reverse())}return G}let M=Math.max(0,\"xyz\".indexOf(C)),P=(z)=>Array.from({length:AQ},(G,X)=>{let N=X/AQ*2*Math.PI,R=[...U];return R[M]+=z*Math.abs(H)/2,R[(M+1)%3]+=Math.cos(N)*Math.abs(W),R[(M+2)%3]+=Math.sin(N)*Math.abs(W),R}),[J,F]=[P(-1),P(1)];return[[...J].reverse(),F,...J.map((z,G)=>[z,J[(G+1)%AQ],F[(G+1)%AQ],F[G]])]}function FZ(Q,Z,$,q,K,V,Y=IQ.graphite){let W=($?fQ(Q):Q).flatMap((z)=>q0(z).map((G)=>({body:z,points:G}))),H=W.flatMap((z)=>z.points),C=[0,1,2].map((z)=>(H.reduce((G,X)=>Math.min(G,X[z]),1/0)+H.reduce((G,X)=>Math.max(G,X[z]),-1/0))/2),U=H.reduce((z,G)=>Math.max(z,Math.hypot(...BQ(G,C))),0.000001),M=(Number.isFinite(Z)?Z:0)*Math.PI/180,P=Math.min(K,V)*0.44/U,J=[0,0,U*5],F=(z)=>{let[G,X,N]=BQ(z,C),R=G*Math.cos(M)+N*Math.sin(M),E=-G*Math.sin(M)+N*Math.cos(M);return[R,X*Math.cos(TQ)-E*Math.sin(TQ),X*Math.sin(TQ)+E*Math.cos(TQ)]};return W.map(({body:z,points:G})=>{let X=G.map(F);return{body:z,seen:X,normal:$0(BQ(X[1],X[0]),BQ(X[2],X[0])),depth:X.reduce((N,R)=>N+R[2],0)/X.length}}).filter(({seen:z,normal:G})=>WZ(G,BQ(J,z[0]))>0).sort((z,G)=>z.depth-G.depth).flatMap(({body:z,seen:G,normal:X})=>{let N=0.7+0.3*Math.abs(WZ(X,Q0)/Math.hypot(...X)),[R,...E]=Z0(String(z.color),Y),g=G.map(([l,r,e])=>{let u=J[2]/(J[2]-e);return[p(K/2+l*P*u),p(V/2-r*P*u)]});if(!GQ(g.flat()))return[];let f=YZ(R*(q&&z.part!==q?0.4:1));return[{type:\"polygon\",points:g,color:`#${f===\"ff\"?\"\":f}${E.map((l)=>YZ(l*N)).join(\"\")}`}]})}var XZ=(Q)=>Q.points?.length??(Q.type===\"line\"?2:1),jZ=(Q)=>Q.text?.length??0,K0=(Q)=>{let Z=Q.flatMap(([,$])=>$);return{commands:Z.length,pairs:Z.reduce(($,q)=>$+XZ(q),0),chars:Z.reduce(($,q)=>$+jZ(q),0),bytes:DQ(Object.fromEntries(Q.map(([$,q])=>[$,{commands:q}])))}};function BZ(Q){let Z=K0(Q.map(([q])=>[q,[]])),$=!1;return Object.fromEntries(Q.map(([q,K])=>[q,{commands:K.filter((V,Y)=>{let W={commands:1,pairs:XZ(V),chars:jZ(V),bytes:DQ(V)+(Y?1:0)};if($||=Object.keys(lQ).some((H)=>Z[H]+W[H]>lQ[H]),!$)for(let H of Object.keys(W))Z[H]+=W[H];return!$})}]))}var V0=0.6,zQ=Object.freeze({surface:\"\",pointer:0,x:0}),J0=(Q)=>p((Q%360+360)%360);function PZ(Q,Z,{surfaceId:$,x:q,phase:K,pointer:V}){let Y=Z.find((C)=>C.id===$)?.model,W=Q.state._drag??zQ;if(!Y||!Number.isFinite(q))return!1;if(K===\"down\"){if(W.surface&&W.pointer!==V)return!1;return Q.state._drag={surface:$,pointer:V,x:q},\"\"}if(![\"move\",\"up\",\"cancel\"].includes(K)||W.surface!==$||W.pointer!==V)return!1;Q.state._drag=K===\"move\"?{...W,x:q}:zQ;let H=Number.isFinite(Y.props.value)?Y.props.value:0;return K===\"cancel\"?\"\":Q.change(Y,J0(p(H+(q-W.x)*V0)))}var MZ=32,Y0=512,W0=49152;var CZ=256,G0={vibrationMs:600,toneMs:600};var LZ=1000,H0=86400000,z0=1000,U0=50,F0={from:0,to:0,start:0,end:0,at:0};function X0(Q){let Z=Q.file.get(\"app\")?.tree,$=(q)=>q?Q.value(q,{}):void 0;return{title:$(Z?.args[0]),...Object.fromEntries(Object.entries(Z?.named??{}).map(([q,K])=>[q,$(K)]))}}var RZ=(Q)=>Q.value({k:\"call\",name:\"today\",args:[]},{}),TZ={eyebrow:{emphasis:\"label\",tone:\"accent\"},muted:{tone:\"muted\"}},j0=new Set([\"List\",\"Dialog\",\"Chart\",\"Tabs\",\"Timer\",\"Drawing\",\"Model3d\"]);var sQ=(Q,Z)=>Number.isFinite(Q)&&Number.isFinite(Z)&&Z>0?Math.min(100,Math.max(0,Q/Z*100)):0,oQ=(Q)=>Q?.k===\"call\"?Q.name===\"append\"||Q.args.some(oQ):Q?.k===\"list\"&&Q.items.some(oQ),OQ=(Q,Z)=>Object.fromEntries(Z.filter(($)=>Q[$]!==void 0).map(($)=>[$,Q[$]])),UQ=(Q,Z,$)=>{let q={...$,...OQ(Q.props,Z)};return Object.keys(q).length?{style:q}:{}},_Z=(Q,Z=Q.props.align)=>UQ(Q,[\"gap\"],Z&&{crossAlign:Z}),s=(Q)=>Q.props.icon===void 0?{}:{icon:$Q(QZ,Q.props.icon)??Q.props.icon},B0=30,P0=100,AZ=(Q,Z,$)=>{let q=Array.isArray(Q.props.options)?Q.props.options:[],K=typeof($??q[0]),V=new Set,Y=[];return q.forEach((W,H)=>{let C=Z.plain(Q.props.labels?.[H]??W),U=K+W;if(typeof W!==K||![\"string\",\"number\",\"boolean\"].includes(K)||K===\"number\"&&!Number.isFinite(W)?1:V.has(U)?2:!C.trim()?3:Y.length===B0?4:0)return;V.add(U),Y.push({text:C.slice(0,P0),value:W})}),Y},NZ=(Q,Z)=>{let $=AZ(Q,Z,Q.props.value),q=$.length>6;if(!$.length)return null;return Z.inside($),{type:q?\"select\":\"segmented\",...Z.field(),...q&&s(Q),options:$}},rQ=(Q,Z,$,q)=>(K,V)=>{let{min:Y=Z,max:W=$,step:H,value:C}=K.props,U=(P)=>Number.isFinite(P)&&P>=Y&&P<=W,M=q?(P)=>Array.isArray(P)&&P.length===2&&P.every(U)&&P[0]<=P[1]:U;if(!q&&!U(C))V.keep(Number.isFinite(C)?Math.min(W,Math.max(Y,C)):Y);return{type:Q,...V.field(q&&((P)=>M(P)?P:void 0)),min:Y,max:W,...OQ(K.props,[\"step\"]),...s(K)}},M0={TextField:(Q)=>typeof Q===\"string\",Slider:Number.isFinite},C0={Column:(Q,Z)=>Z.form()??{type:\"column\",...UQ(Q,[\"gap\"]),children:Z.children(!0)},Row:(Q,Z)=>Z.form()??(Q.children.some(($)=>$.props.weight!==void 0)?{type:\"row\",wrap:!1,..._Z(Q,Q.props.align??\"center\"),children:Z.children(!0)}:{type:\"flowRow\",..._Z(Q),children:Z.children()}),Grid:(Q,Z)=>({type:\"grid\",columns:Q.props.columns??2,children:Z.children()}),Card:(Q,Z)=>({type:\"card\",...Q.props.variant===\"hero\"&&{variant:\"hero\"},...s(Q),children:Z.children()}),Section:(Q,Z)=>({type:\"section\",text:Z.plain(Q.props.title),children:Z.children()}),Text:(Q,Z)=>({type:\"text\",...Z.text(\"text\"),...UQ(Q,[\"emphasis\",\"align\",\"tone\"],$Q(TZ,Q.props.variant))}),Heading:(Q,Z)=>({type:\"text\",...Z.text(\"text\"),style:{emphasis:\"title\"}}),Metric:(Q,Z)=>({type:\"metric\",text:Z.plain(Q.props.label),bind:Z.shown(\"value\"),...s(Q),...UQ(Q,[\"emphasis\",\"tone\"])}),Badge:(Q,Z)=>({type:\"badge\",...Z.text(\"value\"),...s(Q),...UQ(Q,[\"tone\"])}),Emoji:(Q,Z)=>({type:\"emoji\",text:Z.plain(Q.props.label)||Z.plain(Q.props.value)||Z.caption(),...Z.value(\"value\"),...OQ(Q.props,[\"size\"])}),Divider:()=>({type:\"divider\"}),Notice:(Q,Z)=>({type:\"banner\",...s(Q),...UQ(Q,[\"tone\"]),children:[{type:\"text\",...Z.text(\"text\")}]}),Button:(Q,Z)=>{let $=Q.props.variant??(Q.props.selected===void 0?void 0:Q.props.selected?\"filled\":\"tonal\");return{type:\"button\",text:Z.caption(Q.props.label),action:Z.action(),...$&&{variant:$},...s(Q)}},IconButton:(Q,Z)=>({type:\"icon\",name:s(Q).icon,text:Z.caption(Q.props.label),...Q.props.action&&{action:Z.action()}}),NumberField:rQ(\"numberInput\",0,1e6),Slider:rQ(\"slider\",0,100),RangeSlider:rQ(\"rangeSlider\",0,100,1),TextField:(Q,Z)=>({type:\"input\",...Z.field(),...s(Q)}),Toggle:(Q,Z)=>({type:\"toggle\",...Z.field()}),CheckBox:(Q,Z)=>({type:\"checkbox\",...Z.field(),...OQ(Q.props,[\"variant\"])}),Segmented:NZ,Select:NZ,DateField:(Q,Z)=>({type:\"dateInput\",...Z.field(),...s(Q)}),TimeField:(Q,Z)=>({type:\"timeInput\",...Z.field(NQ),...s(Q)}),Choices:(Q,Z)=>{let $=AZ(Q,Z,Array.isArray(Q.props.value)?Q.props.value[0]:void 0);if(!$.length)return null;return Z.inside($),{type:\"multiChoice\",...Z.field((q)=>Array.isArray(q)&&!(Q.props.max!==void 0&&q.length>Q.props.max)?q:void 0),options:$}},Dialog:(Q,Z)=>Z.modal(),List:(Q,Z)=>Z.list(),Progress:(Q,Z)=>{let{value:$,max:q}=Q.props,K=Q.props.variant===\"dots\"&&!Z.scope&&Number.isInteger(q)&&q>=1&&q<=12;return Z.drawn(sQ($,q)/100)??{type:\"progress\",text:Z.label(),variant:K?\"dots\":\"bar\",min:0,max:K?q:100,bind:Z.computed(\"value\",K?Number.isFinite($)?Math.min(q,Math.max(0,$)):0:sQ($,q))}},Timer:(Q,Z)=>{let $=Math.round(Number(Q.props.seconds)*1000);return{type:\"timer\",text:Z.label(),mode:\"countdown\",action:Z.action(()=>!1),foreground:{durationMs:Number.isFinite($)?Math.min(H0,Math.max(LZ,$)):LZ,...G0}}},Ring:(Q,Z)=>({type:\"progressRing\",text:Z.label(),size:\"large\",min:0,max:100,bind:Z.computed(\"value\",sQ(Q.props.value,Q.props.max)),...Q.props.text!==void 0&&{children:[{type:\"text\",...Z.text(\"text\"),style:{emphasis:\"display\"}}]}}),Chart:(Q,Z)=>Z.chart(),Tabs:(Q,Z)=>Z.tabs(),Drawing:(Q,Z)=>Z.canvas(dQ(Q.props.shapes),iQ(Q.props.width),iQ(Q.props.height)),Model3d:(Q,Z)=>Z.canvas([zZ,...FZ(Q.props.bodies??[],Q.props.value??30,!1,Q.props.selected,HQ.width,HQ.height,Z.accent)],HQ.width,HQ.height,!1,Q.props.bind?Q:void 0)},L0=(Q)=>Q?.k===\"path\"&&Q.abs?[Q,!1]:Q?.k===\"un\"&&Q.op===\"!\"&&Q.arg.k===\"path\"&&Q.arg.abs?[Q.arg,!0]:Q?.k===\"bin\"&&Q.op===\"!=\"&&Q.left.k===\"path\"&&Q.left.abs&&Q.right.k===\"lit\"?[Q.left,Q.right.v]:null;function aQ(Q,Z,$=!1){let q=xQ(Z.names,new Set([...Object.values(Z.names),...Z.fields.flatMap(([,j])=>j.split(\".\")),...Z.anim?[Z.anim.field]:[],...Z.drag?[Z.drag]:[]])),K=new Map(Z.fields),V=Z.kinds??{},Y=Z.lists??{},W=RZ(Q),H=(j,D)=>$?Z.types[j]??=typeof D===\"number\"?\"number\":\"string\":Z.types[j]??\"string\",C=[],U=new Map,M=new Set,P=new Map,J=new Map,F=new Map,z=[],G={},X={},N=[],R={d:new Map,v:new Map},E={},g=new Map(Q.file.of(\"need\").filter((j)=>j.tree.name===\"timer\"&&!mQ(j.tree)).map((j)=>[`${j.key}_bar`,j.key])),f=[],{kind:l,preset:r,palette:e}=X0(Q),u=(j,D,h,L,A)=>{let w=A.scope;if(w){let k=w.stored.get(RQ(h));if(k&&k[1]!==\"time\"&&(j===\"d\"||k[1]===\"boolean\"))return k[0];let n=w.name(`${j}:${D}`,D.replace(/\\W/g,\"_\")),KQ=j===\"v\"?\"boolean\":H(`${w.key}/${n}`,L);if(w.computed.set(n,KQ),w.out)w.out[n]=j===\"v\"?Boolean(L):a(KQ,L);return n}let m=h?.k===\"path\"&&h.abs?h.segs:null;if(m?.length===1&&K.has(m[0])&&V[m[0]]!==\"time\"&&(j===\"d\"||typeof L===\"boolean\"))return K.get(m[0]);let O=q(`${j}:${D}`,m?m.join(\"_\"):D.replace(/\\W/g,\"_\"));if(!R[j].has(O)){let k=j+(R[j].size<MZ?\"\":Math.floor(R[j].size/MZ)+1);R[j].set(O,`${k}.${O}`),(E[k]??={})[O]=j===\"v\"?Boolean(L):a(H(O,L),L)}return R[j].get(O)},QQ=(j,D,h)=>{if(!j)return U.set(D,h);if(j.id===null)return;let L=P.get(D);if(!L)P.set(D,L=new Map),U.set(D,(A)=>L.get(A?.key)?.(A.value)??!1);L.set(j.id,h)},x=(j,D,h,L)=>{h=j.name??h;let A=L.scope,w=$Q(C0,j.type),m=(B)=>C.push([h,B]);if(!w)return m(\"\"),null;if(A&&j0.has(j.type))return m(\"\"),null;let O=j.call?yQ(j.call):{},k=L.off||[\"visible\",\"enabled\"].some((B)=>O[B]&&!j.props[B]);if(A&&O.action?.k===\"call\"&&O.action.name===\"move\"&&O.action.args.length===1)return A.flags.moves=!0,null;let n={...L,off:k,...j.type===\"Section\"&&{title:j.props.title}},KQ=(B)=>O[B]&&O[B].k!==\"lit\"?u(\"d\",`${D}.${B}`,O[B],j.props[B],S):null,S={scope:A,fault:m,off:k,rowOptions:!1,blank:Boolean(A&&!A.out),children:(B=!1,_=n)=>j.children.map((I,b)=>x(I,I.name??`${D}:${b}`,h,{..._,weighted:B})).filter(Boolean),caption:(B)=>S.plain(B)||(j.name?j.name:`${h} ${j.props.icon??j.type}`).replace(/_/g,\" \"),label:()=>S.caption(S.plain(j.props.label)||L.title).slice(0,300),text:(B)=>{let _=KQ(B);return _?{text:\"\",bind:_}:{text:S.plain(j.props[B])}},plain:(B)=>{if(B===null||typeof B!==\"object\")return String(B??\"\");return\"\"},value:(B)=>{let _=KQ(B);return _?{bind:_}:{[B]:j.props[B]}},shown:(B)=>KQ(B)??S.computed(B,j.props[B]),computed:(B,_)=>u(\"d\",`${D}.${B}`,null,_,S),keep:(B)=>{if(!S.blank)z.push([j,B])},inside:(B)=>{let _=j.props.value,I=B.map((v)=>v.value),b=j.type!==\"Choices\"?I.includes(_)?_:I[0]:(Array.isArray(_)?_:[]).filter((v,i,d)=>I.includes(v)&&d.indexOf(v)===i);if(JSON.stringify(b)===JSON.stringify(_))return;S.keep(b)},field:(B=(_)=>_)=>{let _;if(A)_=A.stored.get(RQ(O.bind))?.[0];else{let v=O.bind?.k===\"path\"&&O.bind.abs&&O.bind.segs.length===1?O.bind.segs[0]:null;_=K.get(v)}let I=$Q(M0,j.type),b=S.action((v)=>{if(I&&(k||!I(v)))return!1;let i=B(v);return i===void 0?!1:Q.change(j,i)});if(I)M.add(b);return{text:S.caption(j.props.label),bind:_,action:b,...I&&{inputMode:\"live\"}}},action:(B=()=>{let I=L.sheet?.add===j&&Q.nextId,b=Q.fire(j);if(b===\"\"&&I&&Q.nextId>I)Q.state[L.sheet.flag]=!1;return b},_=\"\")=>{let I=q(\"@\"+D+_,jQ(D+_));return QQ(A,I,B),I},form:()=>{let B=`${D}:open`;if(!K.has(B))return null;let _=j.children.find((v)=>v.type===\"Button\"&&oQ(v.props.action)),I=S.caption(_?.props.label),b=(v)=>()=>{return Q.state[B]=v,\"\"};return{type:\"column\",children:[{type:\"button\",text:I,action:S.action(b(!0),\"_open\"),variant:\"filled\",icon:\"Plus\"},{type:\"bottomSheet\",text:I,visibleBind:K.get(B),dismissAction:S.action(b(!1),\"_dismiss\"),children:S.children(!1,{...n,off:k||!Q.state[B],sheet:{flag:B,add:_}})}]}},modal:()=>{let B=L0(O.visible),_=(b)=>b.props.bind!==void 0||b.children.some(_),I=S.action(()=>{if(!B)return!1;return Q.write(B[0],B[1],{}),Q.recompute(),\"\"},\"_dismiss\");return{type:j.children.some(_)?\"bottomSheet\":\"dialog\",text:S.caption(j.props.title),dismissAction:I,children:S.children()}},list:()=>KZ(PQ,j,D,h,O,S,L.title),drawn:(B)=>{let _=!A&&g.get(j.name);return _?S.canvas(dQ(HZ(B,Q.state[_+\"_resting\"]?\"success\":\"accent\")),c.width,c.height,Boolean(Q.state[_+\"_on\"])):null},canvas:(B,_,I,b=!1,v=void 0)=>{let i=q(\"surface:\"+D,jQ(D));return f.push({id:i,width:_,height:I,running:b,commands:B,line:h,model:v}),{type:\"canvas\",text:S.label(),surfaces:[i]}},accent:UZ(e,r??\"clean\"),chart:()=>{let B=j.props.kind??\"bar\",_=S.label().slice(0,100),I=(Array.isArray(j.props.points)?j.props.points:[]).slice(-CZ),[b,v]=[[\"labels\",\"string\",\"label\"],[\"values\",\"number\",\"value\"]].map(([i,d,MQ])=>{let FQ=q(`chart:${D}:${i}`,jQ(`${D}_${i}`));return G[FQ]=I.map((y)=>a(d,y[MQ])),X[FQ]={type:\"array\",maxItems:CZ,items:d},N.push(h),FQ});return{type:\"chart\",text:_,kind:B,categories:b,series:[{text:_,bind:v}]}},tabs:()=>{let B=`${j.name}:tab`,_=j.children.slice(1),I=(Array.isArray(j.props.titles)?j.props.titles:[]).map((y)=>S.plain(y)),b=_.findIndex((y)=>!y.children.length),v=!j.name||!K.has(B)?\"a Tabs needs a line of its own, such as pages = Tabs(titles, groups), for the field of its open tab\":!_.length||_.length>30?`a Tabs has 1 to 30 groups, and this one has ${_.length}`:b>=0?`group ${b+1} of this Tabs is empty, and Alphy draws no empty group. Put a component into it`:null;if(v)return m(v),null;let d=I.length===_.length&&new Set(I).size===I.length&&I.every((y)=>y.trim()&&y.length<=100)?I:_.map((y,JQ)=>String(JQ+1));if(!d.includes(Q.state[B]))Q.state[B]=d[0];let MQ=Q.state[B],FQ=(y)=>{if(!d.includes(y))return!1;return Q.state[B]=y,\"\"};return{type:\"column\",children:[{type:\"tabs\",text:S.label(),bind:K.get(B),action:S.action(FQ),options:d.map((y)=>({text:y,value:y}))},..._.map((y,JQ)=>({type:\"column\",visibleBind:u(\"v\",`${D}.tab${JQ}`,null,d[JQ]===MQ,S),children:y.children.map((nQ,DZ)=>x(nQ,nQ.name??`${D}:tab${JQ}:${DZ}`,h,{...n,off:k||d[JQ]!==MQ,weighted:!0})).filter(Boolean)}))]}}},VQ=w(j,S);if(!VQ)return null;J.set(VQ,h);for(let B of[\"visible\",\"enabled\"]){if(!O[B])continue;if(O[B].k!==\"lit\")VQ[B+\"Bind\"]=u(\"v\",`${D}.${B}`,O[B],j.props[B],S);else if(!j.props[B])VQ[B]=!1}if(j.props.weight!==void 0&&L.weighted)VQ.layout={weight:j.props.weight};return VQ},PQ={kit:Q,name:q,walk:x,day:W,fields:K,lists:Y,views:G,viewSchemas:X,viewOwners:N,listRoots:F},qQ=Q.tree(),o=x(qQ,\"root\",\"root\",{scope:null,weighted:!0}),SQ=[...l?[{type:\"text\",text:String(l),style:TZ.eyebrow}]:[],...qQ.type===\"Column\"?o?.children??[]:[o].filter(Boolean)];return{ui:{type:\"theme\",preset:r??\"clean\",...e&&{palette:e},cornerRadius:20,children:[{type:\"column\",style:{gap:\"medium\",...qQ.type===\"Column\"&&o?.style,padding:\"medium\"},children:SQ}]},values:E,views:G,viewSchemas:X,viewOwners:N,controls:U,liveIds:M,fixes:z,faults:C,owners:J,listRoots:F,drawn:f}}function _0(Q,Z,$,q){let K=Q.kinds?.[Z];if(K===\"list\")return Array.isArray($)?$.map((V)=>cQ(V,Q.lists[Z],q)):[];if(K===\"array\")return Array.isArray($)?$:[];return K===\"time\"||K===\"date\"?a(K,$,q):typeof $===\"number\"||typeof $===\"string\"?a(typeof $,$):$}function N0(Q,Z,$){let q={},K=RZ(Q);for(let[V,Y]of Z.fields){let[W,H]=Y.split(\".\"),C=_0(Z,V,Q.state[V],K);if(H)(q[W]??={})[H]=C;else q[W]=C}if(Z.anim)q[Z.anim.field]=Object.fromEntries(Z.anim.keys.map(([V,Y])=>{let W=Q.state._run?.find((H)=>H.path===V);return[Y,W?{from:W.from,to:W.to,start:W.start,end:W.end,at:W.at}:F0]}));if(Z.drag)q[Z.drag]=Q.state._drag??zQ;return{...q,...$.views,...$.values}}function R0(Q,Z,$){let q=Z.kinds??{},K=Q.nextId-1;for(let[Y,W]of Z.fields){let[H,C]=W.split(\".\"),U=C?$[H]?.[C]:$[H];if(U===void 0)continue;if(q[Y]===\"list\")Q.state[Y]=(Array.isArray(U)?U:[]).map((M)=>{return K=Math.max(K,Number(/^r(\\d+)$/.exec(M.id)?.[1]??0)),{id:M.id,...Object.fromEntries(Z.lists[Y].map(([P,J,F])=>[P,F===\"time\"?NQ(M[J]):M[J]]))}});else Q.state[Y]=q[Y]===\"time\"?NQ(U):q[Y]===\"array\"&&Array.isArray(U)?[...U]:q[Y]===\"record\"?{...U}:U}if(Z.drag)Q.state._drag={...zQ,...$[Z.drag]};Q.nextId=K+1;let V=Z.anim?Z.anim.keys.map(([Y,W])=>({path:Y,...$[Z.anim.field]?.[W]})).filter((Y)=>Y.end):[];if(V.length)Q.state._run=V;else delete Q.state._run;Q.recompute()}function T0(Q,Z){let $=new kQ(new bQ().feedText(Q.lines),!1);if($.nextId=Q.next,Q.shown)$.shown=Object.fromEntries(CQ(Q.shown).entries);return R0($,Q,Z),$}function IZ(Q,Z,$=Date.now()){let{state:q,event:K}=Q;if(K.type===\"preview\"||K.type===\"render\")return{state:q};let V=ZQ.now;ZQ.now=()=>$;let Y;try{Y=T0(Z,q);let W=Z.kinds??{},H=aQ(Y,Z);if(Z.drag&&K.type===\"open\")Y.state._drag=zQ;let C=K.type===\"open\"||K.type===\"timer\"&&(K.timerId===Z.anim?.field?Boolean(Y.state._run):H.drawn.some((G)=>G.id===K.timerId&&G.running)),M=K.type===(H.liveIds.has(K.actionId)?\"liveInput\":\"action\")?H.controls.get(K.actionId):C?()=>Y.tick():K.type===\"pointer\"?()=>PZ(Y,H.drawn,K):void 0;if(!M||M(K.value)===!1)return{state:q};for(let[G,X]of Object.entries(W))if(X===\"list\"&&Y.state[G]?.length>($Q(Z.caps,G)??uQ)||X===\"array\"&&Y.state[G]?.length>Y0)return{state:q};let P=!1;for(let[G,X]of Z.fields){let[N,R]=X.split(\".\"),E=R?q[N]?.[R]:q[N],g=Y.state[G],f=typeof E;if(W[G]||![\"string\",\"number\",\"boolean\"].includes(f)||(f===\"number\"?Number.isFinite(g):typeof g===f))continue;Y.state[G]=f===\"number\"?E:a(f,g),P=!0}if(P)Y.recompute();let J=aQ(Y,Z);if(J.fixes.length){for(let[G,X]of J.fixes)Y.write(G.props.bind,X,{it:G.row});Y.recompute(),J=aQ(Y,Z)}let F=N0(Y,Z,J),z=JSON.stringify(J.ui)===JSON.stringify(H.ui)?{state:F}:{state:F,ui:J.ui};if(Y.tripped||DQ(F)>W0)return{state:q};if(J.drawn.length){if(z.surfaces=BZ(J.drawn.map(({id:G,commands:X})=>[G,X])),K.type!==\"liveInput\")z.timers=[...J.drawn.filter((G)=>G.running).map(({id:G})=>({id:G,intervalMs:z0})),...Y.state._run?[{id:Z.anim.field,intervalMs:U0}]:[]]}return z}catch(W){if(Y?.tripped)return{state:q};throw W}finally{ZQ.now=V}}__handle(IZ);})();";
globalThis.compileAppFile = compileAppFile;
})();
