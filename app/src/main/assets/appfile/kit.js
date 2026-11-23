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
const { DslError, parseExpression, parseLine, HEADS, AppFile, show } = (() => {
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

/** A name that reaches Object.prototype in the kit: a plan must not use it as a name, a path part, or a field (final review, Minor). */
const REFUSED = /(^|\/)(__proto__|constructor|prototype)(\/|$)/;
const refused = (name) => `${REFUSED.exec(name)[2]} is not a name that a plan can use. Use a different name`;

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
      if (MESSAGES && REFUSED.test(match[2])) fail(refused(match[2]));
      tokens.push({ t: "path", abs: match[1] === "/", segs: match[2].split("/") });
      i += match[0].length;
      continue;
    }
    // A number can have an exponent, as show prints a large or a small one: 1e+21, 1e-7.
    const number = /^(\d+\.?\d*|\.\d+)(e[+-]?\d+)?/i.exec(text.slice(i));
    if (number) { tokens.push({ t: "num", v: Number(number[0]) }); i += number[0].length; continue; }
    const word = /^[\p{L}_][\p{L}\p{N}_]*/u.exec(text.slice(i));
    if (MESSAGES && word && REFUSED.test(word[0])) fail(refused(word[0]));
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
        if (MESSAGES && REFUSED.test(key.v)) throw new DslError(refused(key.v));
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
  const entry = (kind, key, text) => {
    if (MESSAGES && REFUSED.test(key)) throw new DslError(refused(key));
    const tree = parseExpression(text);
    // A need line calls a part, and an app line or a check line calls app or check. Each other form is a fault of its line.
    if (["need", "app", "check"].includes(kind) && (tree.k !== "call" || (kind !== "need" && tree.name !== kind))) {
      throw new DslError(MESSAGES ? { need: 'a need line is one call of a part, such as need x = entries("Items")', app: 'an app line is one call of app, such as app("Title", lang="en")',
        check: 'a check line is one call of check, such as check("name", [steps])' }[kind] : "");
    }
    return { kind, key, tree, raw: line };
  };
  let match;
  if ((match = /^delete\s+check\s*\(\s*"(.*)"\s*\)$/.exec(line))) return { kind: "delete", key: "check:" + match[1], tree: null, raw: line };
  // "delete need NAME" is the same as "delete NAME": a model writes the word need as the need line has it (f0 trip-dates).
  if ((match = /^delete\s+(?:need\s+)?(\S+)$/.exec(line))) return { kind: "delete", key: match[1], tree: null, raw: line };
  if ((match = /^need\s+([\p{L}_][\p{L}\p{N}_]*)\s*=(?!=)(.*)$/u.exec(line))) return entry("need", match[1], match[2]);
  if ((match = /^(\$\/[\p{L}\p{N}_/]+)\s*:=(.*)$/u.exec(line))) return entry("formula", match[1], match[2]);
  if ((match = /^(\$\/[\p{L}\p{N}_/]+)\s*=(?!=)(.*)$/u.exec(line))) return entry("state", match[1], match[2]);
  if (/^app\s*\(/.test(line)) return entry("app", "app", line);
  if (/^check\s*\(/.test(line)) {
    const check = entry("check", "", line);
    return { ...check, key: "check:" + (check.tree.args[0]?.v ?? "") };
  }
  if ((match = /^([\p{L}_][\p{L}\p{N}_]*)\s*=(?!=)(.*)$/u.exec(line))) return entry("component", match[1], match[2]);
  throw new DslError(MESSAGES ? "line not understood" : "");
}

/**
 * The head of each kind of line that parseLine reads, by its kind: the start of the line before its value. The head of a need, a formula, a
 * state line, and a component holds the key of its line as group 1. compile.js builds LINE (the lines that a repair reply keeps) and NEED_NAME
 * from them, so a new kind of line needs its head here. parseLine writes each head again in its own patterns, because a build from HEADS adds
 * bytes to the run-time of each app: tests tie the kinds of parseLine to these keys and each pattern of parseLine to its head (compile.test.js).
 */
const HEADS = { delete: /delete\s+(?=\S)/, need: /need\s+([\p{L}_][\p{L}\p{N}_]*)\s*=/u, formula: /(\$\/[\p{L}\p{N}_/]+)\s*:=/u, state: /(\$\/[\p{L}\p{N}_/]+)\s*=/u,
  app: /app\s*\(/, check: /check\s*\(/, component: /([\p{L}_][\p{L}\p{N}_]*)\s*=/u };

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

return { DslError, parseExpression, parseLine, HEADS, AppFile, show };
})();
// kinds.js
const { fixed, inside, segmentsOf, KINDS, PROPERTIES, boxOf } = (() => {
// The registry of the shape kinds and the shape properties (stage 6, L1 and L2). The evaluator (core.js), the drawing (draw.js), the faults
// of a template line (form-scene.js), and the prompt text (prompt.js) come from it: a new kind is one entry here, with its arguments, its
// prompt text, and its draw rule. A draw rule gives Alphy's drawing commands with no color: draw.js adds the paint.

/** False in the run-time text of an app (dsl.js). */
const MESSAGES = true;

/** A sum such as x - r keeps 2 decimals, as the kit's numbers do, with no float noise. */
const fixed = (value) => Math.round(value * 100) / 100;
/** True when each value is a coordinate that Alphy takes: a finite number from -8192 to 8192. */
const inside = (values) => values.every((value) => Number.isFinite(value) && Math.abs(value) <= 8192);
const clamp = (value, low, high) => Math.min(high, Math.max(low, value));
const closed = (points) => ({ type: "polyline", points: [...points, points[0]], stroke: 2 });
const pairs = (points) => { const out = []; for (let index = 0; index + 1 < points.length; index += 2) out.push([points[index], points[index + 1]]); return out; };
const at = (point) => ({ x: fixed(point[0]), y: fixed(point[1]) });

/**
 * The segments of a path through [x, y] points: straight lines, or a smooth curve (a Catmull-Rom spline as cubic segments) that passes
 * through each point. A closed path ends with a close segment.
 */
function segmentsOf(points, smooth, shut) {
  const count = points.length, out = [{ type: "move", ...at(points[0]) }], point = (index) => points[shut ? (index + count) % count : clamp(index, 0, count - 1)];
  for (let index = 1; index < count + (shut ? 1 : 0); index++) {
    const [a, b, c, d] = [point(index - 2), point(index - 1), point(index), point(index + 1)];
    if (!smooth) { out.push({ type: "line", ...at(c) }); continue; }
    out.push({ type: "cubic", cx1: fixed(b[0] + (c[0] - a[0]) / 6), cy1: fixed(b[1] + (c[1] - a[1]) / 6), cx2: fixed(c[0] - (d[0] - b[0]) / 6), cy2: fixed(c[1] - (d[1] - b[1]) / 6), ...at(c) });
  }
  if (shut) out.push({ type: "close" });
  return out;
}

/** The segments of a closed polygon with each corner rounded by a curve of size r: a solid body with no sharp point, as the look target asks. */
function rounded(points, r) {
  const count = points.length, toward = (from, to, size) => {
    const length = Math.hypot(to[0] - from[0], to[1] - from[1]), share = Math.min(0.5, size / (length || 1));
    return [from[0] + (to[0] - from[0]) * share, from[1] + (to[1] - from[1]) * share];
  };
  const out = [];
  points.forEach((point, index) => {
    const [a, b] = [toward(point, points[(index + count - 1) % count], r), toward(point, points[(index + 1) % count], r)];
    out.push({ type: index ? "line" : "move", ...at(a) }, { type: "quad", cx: fixed(point[0]), cy: fixed(point[1]), ...at(b) });
  });
  return [...out, { type: "close" }];
}

/**
 * The shape kinds: args are the positional arguments before the color, last is the one extra value after the color with no name, prompt is
 * the text of the template prompt (null for a kind that only a library line writes), and draw gives the commands of one shape, or null for a
 * shape that Alphy cannot draw. A shape with stroke draws its outline only. points is the least count of points of a list of points, and named
 * names the properties of this kind alone, which its prompt text explains.
 */
const KINDS = {
  circle: { args: ["x", "y", "r"], prompt: MESSAGES ? "circle(x, y, r, color)" : "", draw({ x, y, r, stroke }) {
    if (!(r >= 0 && r <= 2048) || !inside([x - r, y - r, x + r, y + r])) return null;
    const side = fixed(2 * r);
    return stroke ? { type: "arc", x: fixed(x - r), y: fixed(y - r), width: side, height: side, start: 0, sweep: 360, stroke: 2 } : { type: "circle", x, y, radius: r };
  } },
  rect: { args: ["x", "y", "w", "h"], last: "radius", prompt: MESSAGES ? "rect(x, y, w, h, color, radius)" : "", draw({ x, y, w, h, radius, stroke }) {
    // A size below 0 is the same box from its other corner.
    const box = { x: Math.min(x, fixed(x + w)), y: Math.min(y, fixed(y + h)), width: Math.abs(w), height: Math.abs(h) };
    const right = fixed(box.x + box.width), bottom = fixed(box.y + box.height);
    if (!inside([box.x, box.y, right, bottom]) || box.width > 8192 || box.height > 8192) return null;
    if (stroke) return closed([[box.x, box.y], [right, box.y], [right, bottom], [box.x, bottom]]);
    return radius ? { type: "roundRect", ...box, radius: clamp(radius, 0, 1024) } : { type: "rect", ...box };
  } },
  line: { args: ["x1", "y1", "x2", "y2"], last: "width", prompt: MESSAGES ? "line(x1, y1, x2, y2, color, width)" : "",
    draw: ({ x1, y1, x2, y2, width }) => (inside([x1, y1, x2, y2]) ? { type: "line", x: x1, y: y1, x2, y2, stroke: clamp(width ?? 2, 0.1, 128) } : null) },
  text: { args: ["x", "y", "text"], last: "size", prompt: MESSAGES ? "text(x, y, value, color, size)" : "",
    draw: ({ x, y, text, size, align }) => (inside([x, y]) ? { type: "text", x, y, size: clamp(size ?? 14, 1, 128), text: text.slice(0, 2048), align: align ?? "center", baseline: "middle" } : null) },
  polygon: { args: ["points"], points: 3, prompt: MESSAGES ? "polygon([x1, y1, x2, y2, ...], color)" : "", draw({ points, stroke }) {
    // [x1, y1, x2, y2, ...] as [x, y] points. A polygon has 3 to 2048 points, and its outline 2 or more and its first point again.
    const list = pairs(points);
    if (!inside(list.flat()) || list.length < (stroke ? 2 : 3) || list.length > (stroke ? 2047 : 2048)) return null;
    return stroke ? closed(list) : { type: "polygon", points: list };
  } },
  path: { args: ["points"], last: "width", points: 2, named: ["smooth", "closed"], prompt: MESSAGES ? "path([x1, y1, x2, y2, ...], color, width): a line through the points, straight, or a curve with smooth=true. With closed=true it is a filled shape" : "",
    draw({ points, width, smooth, closed: shut, stroke }) {
      const list = pairs(points);
      if (!inside(list.flat()) || list.length < 2 || list.length > 200) return null;
      const segments = segmentsOf(list, Boolean(smooth), Boolean(shut));
      return shut && !stroke ? { type: "path", segments } : { type: "path", segments, stroke: clamp(width ?? 2, 0.1, 128) };
    } },
  arrow: { args: ["x1", "y1", "x2", "y2"], last: "width", prompt: MESSAGES ? "arrow(x1, y1, x2, y2, color, width): a solid arrow from the first point to the head at the second point" : "",
    draw({ x1, y1, x2, y2, width }) {
      const length = Math.hypot(x2 - x1, y2 - y1), w = clamp(width ?? 4, 1, 24);
      if (!inside([x1, y1, x2, y2]) || length < 1) return null;
      // A body of the width and a head of 3 widths, so the body is a third of the head (F4): a solid shape with a rounded head, as the force
      // arrow of the look target.
      const [ux, uy] = [(x2 - x1) / length, (y2 - y1) / length], head = Math.min(length * 0.5, 2.8 * w + 4), half = 1.5 * w, a = w / 2;
      const base = [x2 - ux * head, y2 - uy * head], side = (point, off) => [point[0] - uy * off, point[1] + ux * off];
      const points = [side([x1, y1], a), side(base, a), side(base, half), [x2, y2], side(base, -half), side(base, -a), side([x1, y1], -a)];
      return { type: "path", segments: rounded(points, Math.max(1, w * 0.4)) };
    } },
  arc: { args: ["x", "y", "r", "from", "sweep"], last: "width", prompt: MESSAGES ? "arc(x, y, r, from, sweep, color, width): a part of a circle from the angle from, sweep degrees counterclockwise" : "",
    draw({ x, y, r, from, sweep, width }) {
      if (!(r > 0 && r <= 2048) || !inside([x - r, y - r, x + r, y + r])) return null;
      // Alphy measures an angle clockwise from the x axis, and the kit counterclockwise, as sin and cos of the prompt.
      return { type: "arc", x: fixed(x - r), y: fixed(y - r), width: fixed(2 * r), height: fixed(2 * r), start: fixed(clamp(-from, -360, 360)), sweep: fixed(clamp(-sweep, -360, 360)), stroke: clamp(width ?? 3, 0.1, 128) };
    } },
  // A group places its shapes at x, y, scaled and turned: a ready object of the library is one group (library.js). No prompt: only a library line writes it.
  group: { args: ["x", "y", "scale", "shapes"], prompt: null, draw: () => null },
  // The picture of an image entry of the library (stage 6, I4) in the box x, y, w, h: the host draws it from its assets with its own colors, so
  // it takes no color. No prompt: only a library line writes it.
  image: { args: ["x", "y", "w", "h", "image"], color: false, prompt: null,
    draw: ({ x, y, w, h, image }) => (w > 0 && h > 0 && inside([x, y, x + w, y + h]) ? { type: "image", id: image, x, y, width: w, height: h } : null) },
};

/**
 * The properties of a shape by name: kind is how core.js reads the value (a test, a boolean, a number, a color, or a text), and prompt is
 * the text of the template prompt (null for a property that only a library line writes).
 */
const PROPERTIES = {
  visible: { kind: "test", prompt: MESSAGES ? "visible=test" : "" },
  stroke: { kind: "boolean", prompt: MESSAGES ? "stroke=true for the outline only" : "" },
  smooth: { kind: "boolean", prompt: null },
  closed: { kind: "boolean", prompt: null },
  gradient: { kind: "color", prompt: MESSAGES ? "gradient=color for a fill that fades from the color of the shape at its top to this color at its bottom" : "" },
  radial: { kind: "number", prompt: MESSAGES ? "radial=1 with gradient= for a ball: the light comes from the upper left" : "" },
  glow: { kind: "boolean", prompt: MESSAGES ? "glow=true for a soft light around it" : "" },
  opacity: { kind: "number", prompt: MESSAGES ? "opacity=0.5 for a shape you can see through (0 to 1)" : "" },
  turn: { kind: "number", prompt: MESSAGES ? "turn=degrees to turn it counterclockwise around its center" : "" },
  align: { kind: "text", prompt: null },
  radius: { kind: "number", prompt: null },
  width: { kind: "number", prompt: null },
  size: { kind: "number", prompt: null },
};

/** The box of a command: {x, y, w, h}, for a gradient, a glow, and a turn. */
function boxOf(command) {
  const { type, x, y, radius, width, height, x2, y2, points, segments } = command;
  if (type === "circle") return { x: x - radius, y: y - radius, w: 2 * radius, h: 2 * radius };
  if (type === "line") return { x: Math.min(x, x2), y: Math.min(y, y2), w: Math.abs(x2 - x), h: Math.abs(y2 - y) };
  const list = points ?? (segments ? segments.flatMap((one) => [[one.x, one.y], [one.cx1, one.cy1], [one.cx2, one.cy2]]).filter(([px]) => px !== undefined) : null);
  if (list) {
    const xs = list.map(([px]) => px), ys = list.map(([, py]) => py), left = Math.min(...xs), top = Math.min(...ys);
    return { x: left, y: top, w: Math.max(...xs) - left, h: Math.max(...ys) - top };
  }
  return { x, y, w: width ?? 0, h: height ?? 0 };
}

return { fixed, inside, segmentsOf, KINDS, PROPERTIES, boxOf };
})();
// core.js
const { CATALOG, ICON_NAMES, ACTIONS, TONES, HEX, TONE, isColor, STEPS, explode, dayNumber, minutes, phased, foreground, shownFormulas, own, MAX_DOTS, given, App, settings } = (() => {
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
  // A Chart and a Table with bind= mark one point or row: the bound value is its label, and a tap on a point or a row sets it (data forms).
  // column= of a Table marks the cell of that column (from 0) in the marked row. signs= makes it a comparison: one sign for each column after the first.
  List: ["items", "template", "group"], Chart: ["items", "point", "value", "label", "bind"], Table: ["label", "heads", "rows", "bind", "column", "signs"],
  Tabs: ["titles", "groups", "label"], Dialog: ["title", "children"], DateField: ["label", "bind"], TimeField: ["label", "bind"],
  Picture: ["text", "found"], Choices: ["label", "bind", "options"],
  // The host's own countdown: it keeps its time and has its own buttons and alert (Alphy's foreground timer).
  Timer: ["label", "seconds"],
  // What the host draws from lines of data. The bound value of a Model3d is its turn angle in degrees. A Link only shows its address.
  Drawing: ["label", "width", "height", "shapes"], Model3d: ["label", "bodies", "bind", "exploded", "selected"], Link: ["label", "url", "text"],
};
/**
 * The kit's icon names that Alphy has under a different name (ToolUiCatalog.icons). ArrowDown has no glyph of its own there.
 * IconButton and icon= take these names and each name of Alphy (parts.js ICONS).
 */
const ICON_NAMES = { ArrowUp: "Arrow", ArrowDown: "Sort", ArrowLeft: "Back", ArrowRight: "Chevron", Edit: "Pencil" };
const NAMED = new Set(["visible", "enabled", "variant", "tone", "emphasis", "weight", "min", "max", "step", "empty", "placeholder", "gap", "selected", "kind", "size", "align", "columns", "icon", "fit"]);
const ACTIONS = new Set(["set", "toggle", "inc", "dec", "append", "remove", "clear", "when", "move", "flip", "animate"]);
/**
 * The data calls of a Model3d, with the arguments of each one in order, as the shape kinds of a Drawing (kinds.js KINDS) have them. A color
 * follows them. The evaluator reads the registry when it draws, so a new kind needs no other edit.
 */
const BODIES = { box: { args: ["part", "x", "y", "z", "w", "h", "d"] }, cylinder: { args: ["part", "x", "y", "z", "radius", "length", "axis"] } };
const TEXTS = new Set(["text", "part", "axis", "image"]);
// A shape also takes its own extra value after the color with no name (kinds.js last). The dev run of the dialogue check: 5 of 16 plans
// that think wrote text(x, y, "Земля", "muted", 12) or line(x1, y1, x2, y2, "muted", 2), and no repair step found the cause.
/**
 * The colors of a shape or a body by role. "accent" is the palette of the app. A different color is "#RRGGBB" (HEX). Each other color of a line is a
 * fault of the line, and a renderer draws a computed color that it does not know as ink.
 */
const TONES = { ink: "#0f172a", muted: "#94a3b8", accent: null, surface: "#ffffff", water: "#38bdf8", land: "#65a30d", sun: "#facc15", sand: "#e7d3a8", wood: "#a16207", success: "#16a34a", warning: "#ea580c", error: "#dc2626" };
const HEX = /^#(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/;
/** A tone of a color of the kit (stage 6, L2): the color, a step of +1 to +3 (lighter) or -1 to -3 (darker), and an alpha in percent, such as "accent+1@40". */
const TONE = new RegExp(`^(${Object.keys(TONES).join("|")})([+-][123])?(?:@(\\d{1,2}|100))?$`);
/** True for a color of a shape: a color of the kit, a tone of one, or a hex. */
const isColor = (color) => typeof color === "string" && (TONE.test(color) || HEX.test(color));

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
/**
 * The format functions that print a number for a person. Each one takes the language of the app first. Money has 2 decimals, and none in ja
 * and ko: the won and the yen have no minor unit (L1 item 5).
 */
const PRINT = {
  fixed: (lang, x, n = 2) => print(lang, x, n), money: (lang, x, sign = "") => sign + print(lang, x, /^(ja|ko)$/.test(lang) ? 0 : 2), percent: (lang, x, n = 0) => print(lang, x, n) + "%",
  // The number of a figure: rounded to at most n decimals, with no zeros at the end. A value that is not a number, such as a text that a
  // format function printed, stays as it is, so a value of show= of a list can take amount() for each type (L1 item 2).
  amount: (lang, x, n = 0) => (typeof x == "number" ? print(lang, round(x, n), n, true) : x),
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
/** A "HH:MM" time of the kit as minutes from 0 to 1439, or null when the text is not a time of a day. */
function minutes(text) {
  const match = /^(\d{1,2}):(\d{2})$/.exec(String(text ?? "").trim());
  return match && Number(match[1]) < 24 && Number(match[2]) < 60 ? Number(match[1]) * 60 + Number(match[2]) : null;
}
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

/** The value of a key of a table of the kit, never a member of Object.prototype such as toString (final review, Minor). */
const own = (map, key) => (Object.hasOwn(map, key) ? map[key] : undefined);

/** Alphy draws at most 12 dots of a progress (ToolUiCatalog). A longer lesson, and a progress with a larger max, show a bar. */
const MAX_DOTS = 12;

/** The arguments of a component call by property name: positional ones by the catalog order, then the named ones. */
const given = (tree) => ({ ...Object.fromEntries((own(CATALOG, tree.name) ?? []).map((key, index) => [key, tree.args[index]])), ...tree.named });

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
    for (const seg of path.segs) value = value == null ? undefined : own(value, seg);
    return value;
  }

  /** The place that a write goes to, as a holder and a key: a path, a field of a value, or item N of a list. */
  place(node, ctx) {
    if (node.k === "path") {
      let holder = node.abs ? this.state : ctx.it;
      if (holder == null) throw new DslError(MESSAGES ? "a $field path needs a row" : "");
      if (typeof holder !== "object") throw new DslError(MESSAGES ? "the row is a plain value with no fields to set" : "");
      for (const seg of node.segs.slice(0, -1)) holder = own(holder, seg) ?? (holder[seg] = {});
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
        if (!node.abs && node.segs[0] === "it") return node.segs.slice(1).reduce((value, seg) => (value == null ? undefined : own(value, seg)), ctx.it);
        // Inside each(list, shape), $index is the number of the row, from 0. A row field with that name wins.
        if (!node.abs && ctx.each !== undefined && node.segs.join() === "index" && !(ctx.it && typeof ctx.it === "object" && Object.hasOwn(ctx.it, "index"))) return ctx.each;
        if (!node.abs && ctx.it === undefined) throw new DslError(MESSAGES ? `$${node.segs.join("/")} is a field of a list row, and there is no row here. A value of the app is $/${node.segs.join("/")}` : "");
        if (!node.abs && ctx.it !== undefined && (ctx.it === null || typeof ctx.it !== "object")) throw new DslError(MESSAGES ? "the row is a plain value with no fields. $it reads the row itself" : "");
        if (!node.abs && ctx.it && !Object.hasOwn(ctx.it, node.segs[0])) {
          // A value that an entries need computes for each row, show={name: formula}, reads as a field of the row
          // in each other formula of a row (v1 sleep-en: a chart of $slept). busy stops a show formula that reads itself.
          const name = node.segs[0];
          this.shown ??= Object.fromEntries(shownFormulas(this.file));
          if (node.segs.length === 1 && own(this.shown, name) && !ctx.busy?.includes(name)) return this.value(this.shown[name], { ...ctx, busy: [...(ctx.busy ?? []), name] });
          throw new DslError(MESSAGES ? `the row has no field ${name}` : "");
        }
        return this.read(node, ctx);
      case "list": return this.charge(node.items.map((item) => this.value(item, ctx)));
      case "map": return this.charge(Object.fromEntries(node.entries.map(([key, inner]) => [key, this.value(inner, ctx)])));
      case "field": { const of = this.value(node.of, ctx); return of == null ? undefined : own(of, node.name); }
      case "index": { const list = this.value(node.of, ctx); return Array.isArray(list) ? own(list, this.value(node.at, ctx)) ?? null : null; }
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
        const rowwise = own(ROWWISE, node.name), plain = own(PLAIN, node.name), print = own(PRINT, node.name);
        if (rowwise) {
          const rows = this.value(node.args[0], ctx);
          if (!Array.isArray(rows)) throw new DslError(MESSAGES ? `${node.name} needs a list` : "");
          const perRow = (arg) => arg && ((row) => this.value(arg, { ...ctx, it: row }));
          // The third argument of groups is a formula of the row. The third argument of sort is the order.
          return this.charge(rowwise(rows, perRow(node.args[1]), node.name === "groups" ? perRow(node.args[2]) : node.args[2] && this.value(node.args[2], ctx)));
        }
        if (plain) return this.charge(plain(...node.args.map((arg) => this.value(arg, ctx))));
        if (print) return this.charge(print(this.file.get("app")?.tree.named.lang?.v, ...node.args.map((arg) => this.value(arg, ctx))));
        if (own(CATALOG, node.name)) throw new DslError(MESSAGES ? `${node.name} is a component. A component line has a plain name with no $/ before it` : "");
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
    const keys = Object.hasOwn(kinds, name) ? kinds[name].args : null;
    if (!keys) throw new DslError(MESSAGES ? `${show(tree).slice(0, 30)} is not one of ${Object.keys(kinds).filter((kind) => kinds[kind].prompt !== null).join(", ")}, each` : "");
    // A group has no color: its shapes have their own (kinds.js). A picture of the library has its own colors.
    const last = kinds[name].last, colored = keys.at(-1) !== "shapes" && kinds[name].color !== false, takes = MESSAGES ? `${name} takes ${keys.join(", ")}${colored ? ", color" : ""}${last ? `, ${last}` : ""}` : "";
    if (args.length < keys.length || args.length > keys.length + (last ? 2 : colored ? 1 : 0)) throw new DslError(MESSAGES ? `${show(tree).slice(0, 60)} has ${args.length} values: ${takes}` : "");
    if (named.visible && !this.value(named.visible, ctx)) return [];
    const number = (value, key) => { if (value === null || value === "" || typeof value === "boolean" || !Number.isFinite(Number(value))) throw new DslError(MESSAGES ? `${key} of ${name} is not a number: ${takes}` : ""); return round(Number(value), 2); };
    const color = (value, key) => { const text = String(value ?? "ink"); if (MESSAGES && !isColor(text)) throw new DslError(`the color ${JSON.stringify(text)} of ${key === "color" ? name : `${key}= of ${name}`} is not a color of the kit. Use one of ${Object.keys(TONES).join(", ")}, or a hex such as "#336699"`); return text; };
    const out = { [tag]: name };
    keys.forEach((key, index) => {
      if (key === "shapes") { out.shapes = this.drawn(kinds, tag, args[index], ctx); return; }
      const value = this.value(args[index], ctx);
      out[key] = TEXTS.has(key) ? String(value ?? "") : key === "points" ? [value].flat().map((item) => number(item, key)) : number(value, key);
    });
    if (out.axis !== undefined && !["x", "y", "z"].includes(out.axis)) throw new DslError(MESSAGES ? `axis of ${name} is "x", "y", or "z"` : "");
    if (colored) out.color = color(args[keys.length] ? this.value(args[keys.length], ctx) : null, "color");
    if (args.length === keys.length + 2) out[last] = number(this.value(args[keys.length + 1], ctx), last);
    for (const [key, arg] of Object.entries(named)) {
      const property = tag === "shape" || ["radius", "width", "size", "stroke"].includes(key) ? PROPERTIES[key] : undefined;
      if (key === "visible") continue;
      if (!property) throw new DslError(MESSAGES ? `${name} has no argument ${key}` : "");
      const value = this.value(arg, ctx);
      if (property.kind === "boolean") { if (value) out[key] = true; }
      else out[key] = property.kind === "color" ? color(value, key) : property.kind === "text" ? String(value ?? "") : number(value, key);
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
      if (tree.k !== "call" || !own(CATALOG, tree.name)) {
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
          try { return this.drawn(key === "shapes" ? KINDS : BODIES, key === "shapes" ? "shape" : "body", item, ctx); }
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
   * it has one and a round follows, then the next round starts. The timer part made the state names. A test and the smoke run move the clock and call this.
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
        const named = entry.tree.named, ended = s[n + "_end"], rounds = number(named.rounds, 1), last = s[n + "_round"] >= rounds;
        if (!s[n + "_resting"]) s[n] += 1;
        // A rest is between two rounds: after the work phase of the last round the timer is over (K1 item 6: 5 x 45 + 4 x 15 = 285 s).
        // A timer of one round, and a timer with cycle=true, also rest after the last work phase (K2: inhale 4 s and exhale 6 s end at 10 s).
        if (!s[n + "_resting"] && (!last || rounds <= 1 || named.cycle?.v === true) && number(named.rest, 0) > 0) { s[n + "_resting"] = true; s[n + "_spent"] = 0; }
        else if (last) { s[n + "_over"] = true; s[n + "_on"] = false; s[n + "_spent"] = phase(); s[n + "_end"] = 0; }
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

return { CATALOG, ICON_NAMES, ACTIONS, TONES, HEX, TONE, isColor, STEPS, explode, dayNumber, minutes, phased, foreground, shownFormulas, own, MAX_DOTS, given, App, settings };
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
    Remove: "Убрать", Less: "Меньше", More: "Больше", "No records yet": "Записей пока нет", "The list is full": "Список заполнен",
  },
  de: {
    Add: "Hinzufügen", Yes: "Ja", No: "Nein", Back: "Zurück", Next: "Weiter", Done: "Fertig", Start: "Starten", Pause: "Pause", Reset: "Zurücksetzen", Show: "Zeigen",
    Again: "Noch einmal", Check: "Prüfen", Right: "Richtig", Wrong: "Falsch", "Not yet": "Noch nicht", Points: "Punkte", "Your answer": "Deine Antwort", Rest: "Erholung",
    Work: "Belastung", "Time is up": "Zeit abgelaufen", Step: "Schritt", "Put together": "Zusammensetzen", "Take apart": "Zerlegen",
    Remove: "Entfernen", Less: "Weniger", More: "Mehr", "No records yet": "Noch keine Einträge", "The list is full": "Die Liste ist voll",
  },
  es: {
    Add: "Añadir", Yes: "Sí", No: "No", Back: "Atrás", Next: "Siguiente", Done: "Listo", Start: "Empezar", Pause: "Pausa", Reset: "Restablecer", Show: "Mostrar",
    Again: "Otra vez", Check: "Comprobar", Right: "Correcto", Wrong: "Incorrecto", "Not yet": "Todavía no", Points: "Puntos", "Your answer": "Tu respuesta", Rest: "Descanso",
    Work: "Trabajo", "Time is up": "Se acabó el tiempo", Step: "Paso", "Put together": "Montar", "Take apart": "Desmontar",
    Remove: "Quitar", Less: "Menos", More: "Más", "No records yet": "Aún no hay registros", "The list is full": "La lista está completa",
  },
  fr: {
    Add: "Ajouter", Yes: "Oui", No: "Non", Back: "Précédent", Next: "Suivant", Done: "Terminé", Start: "Démarrer", Pause: "Pause", Reset: "Réinitialiser", Show: "Afficher",
    Again: "Recommencer", Check: "Vérifier", Right: "Juste", Wrong: "Faux", "Not yet": "Pas encore", Points: "Points", "Your answer": "Ta réponse", Rest: "Repos",
    Work: "Effort", "Time is up": "Temps écoulé", Step: "Étape", "Put together": "Assembler", "Take apart": "Démonter",
    Remove: "Supprimer", Less: "Moins", More: "Plus", "No records yet": "Aucun enregistrement pour l'instant", "The list is full": "La liste est pleine",
  },
  it: {
    Add: "Aggiungi", Yes: "Sì", No: "No", Back: "Indietro", Next: "Avanti", Done: "Fatto", Start: "Avvia", Pause: "Pausa", Reset: "Azzera", Show: "Mostra",
    Again: "Di nuovo", Check: "Verifica", Right: "Giusto", Wrong: "Sbagliato", "Not yet": "Non ancora", Points: "Punti", "Your answer": "La tua risposta", Rest: "Riposo",
    Work: "Lavoro", "Time is up": "Tempo scaduto", Step: "Passo", "Put together": "Assembla", "Take apart": "Smonta",
    Remove: "Rimuovi", Less: "Meno", More: "Più", "No records yet": "Ancora nessun elemento", "The list is full": "La lista è piena",
  },
  ja: {
    Add: "追加", Yes: "はい", No: "いいえ", Back: "戻る", Next: "次へ", Done: "完了", Start: "開始", Pause: "一時停止", Reset: "リセット", Show: "表示",
    Again: "もう一度", Check: "確認", Right: "正解", Wrong: "不正解", "Not yet": "まだ", Points: "ポイント", "Your answer": "あなたの答え", Rest: "休憩",
    Work: "運動", "Time is up": "時間になりました", Step: "ステップ", "Put together": "組み立てる", "Take apart": "分解する",
    Remove: "削除", Less: "減らす", More: "増やす", "No records yet": "記録はまだありません", "The list is full": "リストがいっぱいです",
  },
  ko: {
    Add: "추가", Yes: "예", No: "아니요", Back: "뒤로", Next: "다음", Done: "완료", Start: "시작", Pause: "일시정지", Reset: "초기화", Show: "보기",
    Again: "다시", Check: "확인", Right: "정답", Wrong: "오답", "Not yet": "아직", Points: "점수", "Your answer": "내 답", Rest: "휴식",
    Work: "운동", "Time is up": "시간이 종료되었습니다", Step: "단계", "Put together": "조립하기", "Take apart": "분해하기",
    Remove: "제거", Less: "줄이기", More: "늘리기", "No records yet": "아직 기록이 없습니다", "The list is full": "목록이 가득 찼습니다",
  },
  pl: {
    Add: "Dodaj", Yes: "Tak", No: "Nie", Back: "Wstecz", Next: "Dalej", Done: "Gotowe", Start: "Start", Pause: "Pauza", Reset: "Resetuj", Show: "Pokaż",
    Again: "Jeszcze raz", Check: "Sprawdź", Right: "Dobrze", Wrong: "Źle", "Not yet": "Jeszcze nie", Points: "Punkty", "Your answer": "Twoja odpowiedź", Rest: "Odpoczynek",
    Work: "Praca", "Time is up": "Czas minął", Step: "Krok", "Put together": "Złóż", "Take apart": "Rozłóż",
    Remove: "Usuń", Less: "Mniej", More: "Więcej", "No records yet": "Brak wpisów", "The list is full": "Lista jest pełna",
  },
  pt: {
    Add: "Adicionar", Yes: "Sim", No: "Não", Back: "Voltar", Next: "Próximo", Done: "Concluído", Start: "Iniciar", Pause: "Pausar", Reset: "Redefinir", Show: "Mostrar",
    Again: "De novo", Check: "Verificar", Right: "Certo", Wrong: "Errado", "Not yet": "Ainda não", Points: "Pontos", "Your answer": "Sua resposta", Rest: "Descanso",
    Work: "Trabalho", "Time is up": "O tempo acabou", Step: "Passo", "Put together": "Montar", "Take apart": "Desmontar",
    Remove: "Remover", Less: "Menos", More: "Mais", "No records yet": "Ainda não há registros", "The list is full": "A lista está cheia",
  },
  tr: {
    Add: "Ekle", Yes: "Evet", No: "Hayır", Back: "Geri", Next: "İleri", Done: "Tamam", Start: "Başlat", Pause: "Duraklat", Reset: "Sıfırla", Show: "Göster",
    Again: "Tekrar", Check: "Kontrol et", Right: "Doğru", Wrong: "Yanlış", "Not yet": "Henüz değil", Points: "Puan", "Your answer": "Cevabın", Rest: "Dinlenme",
    Work: "Çalışma", "Time is up": "Süre doldu", Step: "Adım", "Put together": "Birleştir", "Take apart": "Parçalara ayır",
    Remove: "Kaldır", Less: "Azalt", More: "Artır", "No records yet": "Henüz kayıt yok", "The list is full": "Liste dolu",
  },
  uk: {
    Add: "Додати", Yes: "Так", No: "Ні", Back: "Назад", Next: "Далі", Done: "Готово", Start: "Почати", Pause: "Пауза", Reset: "Скинути", Show: "Показати",
    Again: "Ще раз", Check: "Перевірити", Right: "Правильно", Wrong: "Неправильно", "Not yet": "Ще ні", Points: "Бали", "Your answer": "Ваша відповідь", Rest: "Відпочинок",
    Work: "Робота", "Time is up": "Час вийшов", Step: "Крок", "Put together": "Зібрати", "Take apart": "Розібрати",
    Remove: "Прибрати", Less: "Менше", More: "Більше", "No records yet": "Записів поки немає", "The list is full": "Список заповнено",
  },
  zh: {
    Add: "添加", Yes: "是", No: "否", Back: "返回", Next: "下一步", Done: "完成", Start: "开始", Pause: "暂停", Reset: "重置", Show: "显示",
    Again: "再来一次", Check: "检查", Right: "正确", Wrong: "错误", "Not yet": "还没有", Points: "得分", "Your answer": "你的答案", Rest: "休息",
    Work: "训练", "Time is up": "时间到", Step: "步骤", "Put together": "组装", "Take apart": "拆开",
    Remove: "移除", Less: "减少", More: "增加", "No records yet": "暂无记录", "The list is full": "列表已满",
  },
};

/** True when the kit has its words in the language of an app: English, a language of the table, or no language. */
const hasWords = (lang) => lang === undefined || lang === "en" || Object.hasOwn(WORDS, lang);
/** The word of the kit for an English caption in a language. A language that the table does not have gets the English word. */
const wordOf = (lang, english) => (Object.hasOwn(WORDS, lang ?? "") && Object.hasOwn(WORDS[lang], english) ? WORDS[lang][english] : english);

return { WORDS, hasWords, wordOf };
})();
// parts.js
const { ICONS, ICONED, choices, FIELD_KINDS, needsOf, faultName, expand } = (() => {
// Generic parts. A part turns one need line into ordinary app lines: state, formulas, components, and checks.
// After that the lines are plain lines. The loop can patch each one by its name.
// A part is a kind of need, never a named app.






const q = JSON.stringify;
/** The names of IconButton and icon=: each icon of Alphy, and the kit's names that the run-time gives Alphy's name (ICON_NAMES). */
const ICONS = [...HOST_ICONS, ...Object.keys(ICON_NAMES)];
/** The components that show icon=. A Select of six options or fewer is Alphy's segmented control, which shows none (run.js). */
const ICONED = ["Button", "IconButton", "Metric", "Badge", "Notice", "Card", "TextField", "NumberField", "Select", "DateField", "TimeField"];
/** The values that an option can take: a value in the line, or each value of a choice (test ? a : b, or if(test, a, b)). Null for each other expression. */
const choices = (tree) => {
  const pair = tree.k === "if" ? [tree.yes, tree.no] : tree.k === "call" && tree.name === "if" && tree.args.length === 3 ? tree.args.slice(1) : null;
  if (!pair) return tree.k === "lit" ? [tree.v] : null;
  const [yes, no] = pair.map(choices);
  return yes && no && [...yes, ...no];
};
/** Why a component of a part line cannot show its icon, or null when it can. A formula that is no choice of values stays for the report (check.js looks). */
function iconless(node, icon) {
  const options = ["Segmented", "Select"].includes(node.name) ? (node.args[2]?.items ?? node.args[2]?.entries) : null;
  if (options && options.length <= 6) return `the part draws a choice of ${options.length} options as a segmented control, which shows no icon`;
  if (!ICONED.includes(node.name)) return `the part draws a ${node.name}, which shows no icon`;
  const wrong = (choices(icon) ?? []).find((value) => !ICONS.includes(value));
  return wrong === undefined ? null : `${q(wrong)} is not the name of an icon of Alphy`;
}
/**
 * A look option that cannot apply changes only the look, so it is a note and never a fault of a need (A0-K4). The icon of each component of a
 * line that a part wrote goes when the component cannot show it, and a glyph (an IconButton with no action) with such an icon goes from its list.
 * Returns the notes. The lines of an author keep their look options, and the report checks them (check.js looks).
 */
function dropIcons(tree, need) {
  const notes = [], drop = (node, icon) => { const why = iconless(node, icon); if (why) notes.push(`need ${need}: ${why}, so the kit left out icon=${show(icon)}`); return why; };
  const visit = (node) => {
    if (!node || typeof node !== "object") return;
    if (Array.isArray(node)) return node.forEach(visit);
    if (node.k === "list") node.items = node.items.filter((item) => !(item.k === "call" && item.name === "IconButton" && !item.named.action && item.args[0] && drop(item, item.args[0])));
    if (node.k === "call" && node.named.icon && drop(node, node.named.icon)) delete node.named.icon;
    Object.values(node).forEach(visit);
  };
  visit(tree);
  return notes;
}
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
/** The kind of each field kind of an entries need, as a row shows it and the add form takes it. A list of options is a choice, and each other kind is a text. */
const FIELD_KINDS = { number: "number", money: "number", percent: "number", count: "count", check: "check", switch: "switch", date: "date", time: "time", picture: "picture" };
/** A name of a line or a field of a plan (dsl.js parseLine). */
const WORD = /^[\p{L}_][\p{L}\p{N}_]*$/u;
/** The most rows of a list of Alphy: rows.js MAX_ROWS, which comes after this module in the bundle (build.js MODULES). A test of max= ties the two. */
const MAX_ROWS = 100;
/** The most fields of a row of Alphy with the id: rows.js MAX_ROW_FIELDS. The test of L3 decision 2 ties the two. */
const MAX_ROW_FIELDS = 12;
/** The lines NAME_X of an entries part that are not a field: the rows, the list, the add form, the remove dialog, the arrows, the emoji, and the hero. */
const ENTRIES_LINES = new Set(["row", "list", "view", "main", "add", "addRow", "open", "remove", "confirm", "yes", "no", "up", "down", "emoji", "hero"]);

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
  // need angle = input("slider", "Angle", 0, min=0, max=360, step=30): a Slider for a number that the person explores. A percent input with min= and max= is a Slider too.
  input(name, need) {
    const [kind, title, value] = need.args, extras = need.named;
    const k = kind?.v;
    // Each kind of input uses its own options, and any other one is a fault (G.4).
    if (!Object.hasOwn(INPUT_USES, k)) throw new DslError(`an input of kind ${k ?? (kind ? show(kind) : "nothing")} is not built. Use one of the kinds ${Object.keys(INPUT_USES).join(", ")}`);
    // icon= is a look option: each kind takes it, and a component that cannot show it drops it with a note (dropIcons).
    const uses = INPUT_USES[k], unused = Object.keys(extras).filter((key) => key !== "icon" && !uses.includes(key));
    if (unused.length) throw new DslError(`an input of kind ${k} does not use ${unused.map((key) => key + "=").join(", ")}. ${uses.length ? `It uses ${uses.map((key) => key + "=").join(", ")}` : "It uses no option"}`);
    const named = Object.entries(extras).filter(([key]) => key !== "options").map(([key, tree]) => `, ${key}=${show(tree)}`).join("");
    // The options are a list of values or a map of a value to its label. The host draws a choice of six options or fewer as a segmented control, which shows no icon (run.js).
    const count = (extras.options?.items ?? extras.options?.entries)?.length;
    const icon = extras.icon ? `, icon=${show(extras.icon)}` : "", options = show(extras.options ?? { k: "list", items: [] }), few = (count ?? 9) <= 4;
    let widget;
    if (k === "choice") widget = `${few ? "Segmented" : "Select"}(${show(title)}, $/${name}, ${options}${icon})`;
    else if (k === "toggle") widget = `Toggle(${show(title)}, $/${name}${icon})`;
    else if (k === "text") widget = `TextField(${show(title)}, $/${name}${icon})`;
    else if (k === "choices") widget = `Choices(${show(title)}, $/${name}, ${options}${extras.max ? `, max=${show(extras.max)}` : ""}${icon})`;
    else if (k === "date" || k === "time") widget = `${k === "date" ? "DateField" : "TimeField"}(${show(title)}, $/${name}${icon})`;
    else if (k === "range") widget = `RangeSlider(${show(title)}, $/${name}${named})`;
    else {
      const ranged = extras.min && extras.max;
      if (k === "slider" && !ranged) throw new DslError("a slider needs min= and max=");
      const pick = (k === "percent" && ranged) || k === "slider" ? "Slider" : "NumberField";
      widget = `${pick}(${show(title)}, $/${name}${named}${k === "count" && !extras.step ? ", step=1" : ""})`;
    }
    return { lines: [`$/${name} = ${show(value)}`, `${name}_field = ${widget}`], top: [`${name}_field`] };
  },

  // need perPerson = figure("money", "Per person", $/bill / $/people, example=[{bill: 100, people: 4}, 25])
  // need today = figure("number", "Today", $/ml, icon="💧", unit="ml", goal=2000, goalText="Of the goal", note="250 ml for each cup"): the main figure is a hero card with a large emoji, a bar to its goal, and a muted note.
  // icon="Drop" (a name of a host icon) is the icon of the metric. role (expand): the place of a figure that is not the main one.
  figure(name, need, { hero, role }) {
    const [format, title, formula] = need.args, f = format?.v, { icon, note, goal, goalText, unit } = need.named, main = hero === name;
    // A look option that the form of the figure has no place for goes, with a note (K1 fix 3). Only the main figure draws them.
    const notes = [], away = ["note", "goal", "goalText"].filter((key) => need.named[key]).map((key) => key + "=");
    if (away.length && !main) notes.push(`need ${name}: note=, goal=, and goalText= show only on the main figure, and ${name} is not it, so the kit left out ${away.join(", ")}`);
    else if (goalText && !goal) notes.push(`need ${name}: goalText= is the caption of the bar to goal=, and ${name} has no goal=, so the kit left it out`);
    const decimals = need.named.decimals && f !== "text" && show(need.named.decimals);
    if (need.named.decimals && f === "text") notes.push(`need ${name}: a figure of the format text shows its value as it is, so the kit left out decimals=`);
    // An own key, so that a format such as "constructor" is a number and never a property of Object. money() prints 2 decimals, and none in ja and ko, so decimals= of money is fixed().
    const formats = { money: decimals ? `fixed($/${name}, ${decimals})` : `money($/${name})`, percent: `percent($/${name}${decimals ? `, ${decimals}` : ""})`, text: `text($/${name})` };
    const text = Object.hasOwn(formats, f) ? formats[f] : `amount($/${name}, ${decimals || (f === "count" ? 0 : 2)})`;
    // unit="ml" follows the shown number ("2,000 ml"), and the value stays a number for a bar, a check, or an example.
    const lines = [`$/${name} := ${show(formula)}`, `$/${name}_text := ${text}${unit ? ` + " " + ${show(unit)}` : ""}`];
    // An emoji, or a formula that gives one, is the sign of the main figure. A word of letters is the name of a host icon.
    const named = icon?.k === "lit" && /^[A-Za-z]+$/.test(icon.v), sign = main && icon && !named, metric = (look) => `Metric(${show(title)}, $/${name}_text${look}${icon && !sign ? `, icon=${show(icon)}` : ""})`;
    // A row shows its icon as a small sign at the left of its title. A host icon there is an IconButton with no action, which Alphy draws as a glyph.
    const small = icon ? (named ? `IconButton(${show(icon)}, label=${show(title)}), ` : `Emoji(${show(icon)}, size="small", label=${show(title)}), `) : "";
    // A row of a figure is at most 360 dp wide, as a counter is, so on a wide screen its value stays near its title, and a phone row does not change (L1 item 8, H5).
    if (main) {
      const parts = [sign && `Emoji(${show(icon)}, size="large", label=${show(title)})`, metric(', emphasis="display"'), goal && `Progress($/${name}, ${show(goal)}, label=${show(goalText ?? title)})`, note && `Text(${show(note)}, tone="muted")`];
      lines.push(`${name}_view = Card([${parts.filter(Boolean).join(", ")}], variant="hero")`);
    } else lines.push(`${name}_view = ${role === "row" ? `Row([${small}Text(${show(title)}, weight=1), Text($/${name}_text, emphasis="number")], max=360)` : metric("")}`);
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
    return { lines, top: role === "held" ? [] : [pair ? `${name}_grid` : `${name}_view`], note: notes };
  },

  // need tasks = entries("Tasks", fields={task: "text", level: ["high", "low"], done: "check"}, rows=[], edit=true, reorder=true, filter=$done, sort=$task, order=$/order, show={double: $n * 2})
  // More: field kinds "date", "time", "switch", "count". confirm="Delete?", sheet=true, group=$field, sort=[$day, $time], hidden=true.
  entries(name, need, { caption, word, count }) {
    const title = need.args[0], opt = need.named, fields = opt.fields?.entries ?? [["name", { k: "lit", v: "text" }]];
    const names = [...fields, ...(opt.show?.entries ?? [])].map(([field]) => field), odd = names.find((field) => !WORD.test(field)), taken = names.find((field) => ENTRIES_LINES.has(field));
    // Alphy keys each row by its id: not blank and of no other row (ToolValueSchema). A field that a person types must not be that key.
    if (names.includes("id")) throw new DslError("a field named id is the key of each row, which the host gives. Give the field a different name");
    // The part names a line NAME_FIELD for each field, so a field is a word and does not have the name of one of its other lines.
    if (odd !== undefined) throw new DslError(`a field named ${q(odd)} is not a word, and the part names its lines and $FIELD by it. Give the field a name of letters, digits, and _`);
    if (taken) throw new DslError(`a field named ${taken} has the name of the line ${name}_${taken} of the list part. Give the field a different name`);
    // A Map, so that a field named "constructor" does not read a property of Object.
    const labels = new Map((opt.labels?.entries ?? []).map(([key, tree]) => [key, tree.v])), text = caption;
    // max=N: the list takes at most N rows (P4). The part reads it once when it builds the screen, so it is a whole number in the line.
    const max = opt.max?.v, start = opt.rows?.items?.length ?? 0;
    if (opt.max && !(opt.max.k === "lit" && Number.isInteger(max) && max >= 1 && max <= MAX_ROWS)) throw new DslError(`max= is the most rows of the list, a whole number from 1 to ${MAX_ROWS}, and ${show(opt.max)} is not one`);
    if (opt.max && start > max) throw new DslError(`the list starts with ${start} rows, and max=${max} takes at most ${max}`);
    const lines = [`$/${name} = ${show(opt.rows ?? { k: "list", items: [] })}`];
    // The rows are data for a deck, a chart, or a figure, and the list is not on the screen.
    if (opt.hidden?.v === true) return { lines, top: [] };
    const cells = [], marks = [], texts = [], typedTexts = [], counts = [], tail = [], add = [], blank = [], reset = [], canAdd = opt.add?.v !== false, edit = opt.edit?.v === true;
    // A look option that cannot apply goes, with a note (A0-K4).
    const notes = [], iconWord = opt.icon?.k === "lit" && /^[A-Za-z]+$/.test(opt.icon.v);
    if (iconWord) notes.push(`need ${name}: icon= of a list is an emoji, such as icon="🛒", and ${q(opt.icon.v)} is not one, so the kit left it out`);
    // icon=: one sign for each row, or a value of the row (C.2). A field that is the sign of the row is not a text of the row too.
    const icon = iconWord ? undefined : opt.icon, sign = icon?.k === "path" && !icon.abs ? icon.segs[0] : null, shown = opt.show?.entries ?? [];
    // icons= gives a field of the add form its icon, and tones= gives a value of show= its tone (C.8). Maps, as labels= is.
    const icons = new Map(opt.icons?.entries ?? []), tones = new Map(opt.tones?.entries ?? []);
    for (const key of tones.keys()) if (!shown.some(([value]) => value === key && value !== sign)) notes.push(`need ${name}: tones= names ${key}, and show= has no value of that name, so the kit left it out`);
    if (icon?.k === "path" && ![...fields, ...shown].some(([key]) => key === sign)) throw new DslError(`icon=${show(icon)} reads no field of the rows. Use a field of fields= or a name of show=`);
    // More than 2 check marks are circles of one weight in a second row (C.3).
    const many = fields.filter(([, kindTree]) => kindTree.v === "check").length > 2;
    const kindOf = (kindTree) => (kindTree.k === "list" ? "choice" : own(FIELD_KINDS, kindTree.v) ?? "text");
    // group=$field: Alphy shows the value of the field as the heading of each group, so a row that cannot change the field leaves it out (L2 decision 2).
    // A check mark, a switch, and a count change in the row, so they stay. The add form and the data keep the field. A row with no other field keeps it.
    const grouped = opt.group?.k === "path" && !opt.group.abs && opt.group.segs.length === 1 ? fields.find(([field]) => field === opt.group.segs[0]) : undefined;
    const heading = !edit && grouped && !["check", "switch", "count", "picture"].includes(kindOf(grouped[1])) && fields.some(([field]) => field !== sign && field !== grouped[0]) ? grouped[0] : null;
    const others = fields.filter(([field]) => field !== sign && field !== heading);
    // In a row with one text, a choice that the row does not change is the muted line under the title: as a label beside it, "Frutta e verdura" broke into two lines.
    const under = !edit && others.filter(([, kindTree]) => kindOf(kindTree) === "text").length === 1 ? others.find(([, kindTree]) => kindOf(kindTree) === "choice")?.[0] : undefined;
    // Each printed number is one more field of the row, which the run-time computes, so a row that they would take past the limit of Alphy keeps raw numbers.
    // The count takes each field, value of show=, and time, an icon= formula, a group, and the label of a reorder, so it is never below the count of rows.js.
    const printed = others.filter(([, kindTree]) => kindOf(kindTree) === "number").length;
    const used = 1 + new Set([...fields.map(([field]) => field), ...keysOf(opt.rows)]).size + shown.length + fields.filter(([, kindTree]) => kindTree.v === "time").length
      + (icon && icon.k !== "lit" && !sign ? 1 : 0) + (opt.group ? 1 : 0) + (opt.reorder?.v === true ? 1 : 0);
    const prints = used + printed <= MAX_ROW_FIELDS;
    let firstText = null;
    for (const [field, kindTree] of fields) {
      const choice = kindTree.k === "list" ? show(kindTree) : null, kind = kindOf(kindTree);
      // The field for a value that the person types or picks.
      const widget = { number: "NumberField", date: "DateField", time: "TimeField" }[kind] ?? "TextField";
      // The add form and each control of a row name the field by labels=, else by the readable form of the field name.
      const named = q(labels.get(field) ?? label(field));
      const pick = (text, bind, more = "") => `${choice && kindTree.items.length <= 4 ? "Segmented" : "Select"}(${text}, ${bind}, ${choice}${more})`;
      // The cell of the row: a check mark, a choice, a field that the person can change, a text of the title column, or a small label of a value.
      if (field === sign || field === under || field === heading) /* the emoji of the row, the line under its title, or the heading of its group */;
      else if (kind === "check") lines.push(`${name}_${field} = CheckBox(${named}, $${field}${many ? ', variant="circle", weight=1' : ""})`);
      else if (kind === "switch") lines.push(`${name}_${field} = Toggle(${named}, $${field})`);
      else if (kind === "count") {
        // A count in the row: a minus and a plus button around the number, and the person can also type it
        // (route steps-chart: seven day rows with a count of steps, and no way to give 8000 but by taps). Not below 0.
        // The three are one group on a line of their own. The field has no step: with one, the host drew its own stepper beside these buttons (L1 item 1).
        lines.push(`${name}_${field}_minus = IconButton("Minus", action=set($${field}, max(0, $${field} - 1)), label=${q(word("Less"))})`, `${name}_${field} = NumberField(${named}, $${field}, min=0, weight=1)`,
          `${name}_${field}_plus = IconButton("Plus", action=inc($${field}), label=${q(word("More"))})`);
        counts.push(`Row([${name}_${field}_minus, ${name}_${field}, ${name}_${field}_plus])`);
      } else if (kind === "picture") lines.push(`${name}_${field} = Picture($${field})`);
      else if (kind === "choice" && edit) lines.push(`${name}_${field} = ${pick(named, "$" + field)}`);
      // A text field of an editable row is a line of its own. The first one shares it only with the emoji of the row, and takes the rest of it (L1 item 1).
      else if (edit && kind !== "choice") lines.push(`${name}_${field} = ${widget}(${named}, $${field}${kind === "text" && icon && !typedTexts.length ? ", weight=1" : ""})`);
      // The host's number and display emphasis are too large for a row (Task 11b-1: "1 kg" in two lines).
      // A typed number, money, or percent prints in the language of the app, as the total over the list does: "7.5" beside "23,50" in an es app
      // (L3 decision 2). amount(x, 2) keeps the typed value: the forms of a figure put "180.00" beside a range "0 – 1000" and "13%" for a typed 12.5.
      else if (kind !== "text") lines.push(`${name}_${field} = Text(${prints && kind === "number" ? `amount($${field}, 2)` : `$${field}`}, emphasis="label")`);
      else texts.push(field);
      if (kind === "text" && field !== sign) firstText ??= field;
      if (field !== sign && field !== under && field !== heading && kind !== "count" && !(kind === "text" && !edit)) (kind === "check" ? marks : kind === "text" ? typedTexts : cells).push(`${name}_${field}`);
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
    for (const key of icons.keys()) notes.push(canAdd ? `need ${name}: icons= names ${key}, whose field of the add form shows no icon, so the kit left it out. A text, number, date, or time field shows one, and a choice of more than six options does too`
      : `need ${name}: icons= names ${key}, and with add=false the list has no add form, so the kit left it out`);
    // A value for each row, from the fields of that row. A number prints in the language of the app with at most 2 decimals, and a text, such
    // as one that a format function of the plan printed, stays as it is. A name of labels= stands before the value (L1 item 2).
    const valued = shown.filter(([key]) => key !== sign);
    for (const [key, formula] of valued) {
      lines.push(`${name}_${key} = Badge(${labels.has(key) ? `${q(`${labels.get(key)}: `)} + ` : ""}amount(${show(formula)}, 2)${tones.has(key) ? `, tone=${show(tones.get(key))}` : ""})`);
      cells.push(`${name}_${key}`);
    }
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
    // In a row with values of show=, the title keeps the width of its text: the group of the title and the cells takes the rest of the line.
    // In a column Alphy makes each text fill the width, so the texts of a title of two lines there have fit=true (L1 item 2).
    const grow = valued.length ? "" : ", weight=1", fit = valued.length ? ", fit=true" : "";
    lines.push(...texts.map((field, index) => `${name}_${field} = Text($${field}${texts.length === 1 ? grow : (index ? ', tone="muted"' : "") + fit})`));
    if (texts.length > 1) lines.push(`${name}_main = Column([${texts.map((field) => `${name}_${field}`).join(", ")}]${grow}, gap="none")`);
    const emoji = icon ? [`${name}_emoji`] : [], [first, ...more] = typedTexts, shut = many ? [] : marks, line = (items, look = "") => (items.length ? [`Row([${items.join(", ")}]${look})`] : []);
    // An editable row (L1 item 1) is one line that wraps. The host gives a text field there up to 300 dp, so on a phone it has a line of its own,
    // each other control keeps the width of its content, and a count group fills a line. The first text field shares its line only with the emoji.
    // A row with values of show= (L1 item 2) has the title and the cells in one group that wraps, so a cell that does not fit beside the title goes
    // under it with the width of its content. The emoji, the check marks, and the remove button stay at the ends of the line.
    // A row with no value of show= (L2 decision 1) has its value cells in one group with no weight after the title. Alphy gives each text with no weight
    // in a row of no wrap a share of the row (NativeToolUi.kt rowTextTypes), so beside a title and a number a date got a third of the line and broke inside.
    // The group keeps the width of its cells, and the title takes the rest of the line. At most 120 dp: a date and a time fit on one line, and more cells
    // wrap inside the group, so the title keeps room on a phone.
    const head = texts.length > 1 ? [`${name}_main`] : texts.map((field) => `${name}_${field}`), titled = [...head, ...cells];
    const top = edit ? line([...(first && emoji.length ? [`Row([${name}_emoji, ${first}])`, ...more] : [...emoji, ...typedTexts]), ...cells, ...shut, ...tail, ...counts], ', align="center"')
      : [...line([...emoji, ...(valued.length ? line(titled, ', align="center", weight=1') : head.length ? [...head, ...line(cells, ", max=120")] : titled), ...shut, ...tail]), ...counts];
    // A count group, and many check marks, get lines of their own under the other cells. The host draws each row of a list on its own card, so the template has no card.
    const rowLines = [...top, ...(many ? [`Row([${marks.join(", ")}], gap="none")`] : [])];
    lines.push(`${name}_row = ${rowLines.length > 1 ? `Column([${rowLines.join(", ")}])` : rowLines[0]}`);
    let source = `$/${name}`;
    if (opt.filter) source = `filter(${source}, ${show(opt.filter)})`;
    if (opt.sort) source = `sort(${source}, ${show(opt.sort)}${opt.order ? ", " + show(opt.order) : ""})`;
    // With no emptyText= the list has no text of its own, and Alphy shows its own word for an empty list in the language of the app.
    lines.push(`${name}_list = List(${source}, ${name}_row${opt.group ? `, group=${show(opt.group)}` : ""}${opt.emptyText ? `, empty=${show(opt.emptyText)}` : ""})`);
    const parts = [`${name}_list`];
    if (opt.remove?.v !== false && opt.confirm) parts.push(`${name}_confirm`);
    if (canAdd) {
      const sheet = opt.sheet?.v === true, adds = `[${[`append($/${name}, {${blank.join(", ")}})`, ...reset, ...(sheet ? [`set($/${name}_adding, false)`] : [])].join(", ")}]`;
      // The add button is enabled at the start (C.4). A press with an empty first text field, or at the limit of max=, changes nothing, and a sheet stays open.
      const tests = [firstText && `len(trim($/${name}_new_${firstText})) > 0`, max && `len($/${name}) < ${max}`].filter(Boolean);
      lines.push(`${name}_add = Button(${text("addText", "Add")}, action=${tests.length ? `when(${tests.join(" && ")}, ${adds})` : adds}, icon="Plus")`);
      // At the limit of max= the form, or the button that opens its sheet, gives way to a muted text that says that the list is full.
      const room = max ? `, visible=len($/${name}) < ${max}` : "";
      // With sheet=true the add form is a dialog that a button opens. The add button closes it, and so does the close button that Alphy
      // draws in each sheet with its own word (NativeToolUi.kt, bottomSheet), so the part writes no close button of its own.
      if (sheet) lines.push(`$/${name}_adding = false`, `${name}_open = Button(${text("addText", "Add")}, action=set($/${name}_adding, true), icon="Plus"${room})`,
        `${name}_addRow = Dialog(${text("addText", "Add")}, [${[...add, `${name}_add`].join(", ")}], visible=$/${name}_adding)`);
      // The fields stand one below the other, and the add button is on its own line under them.
      else lines.push(`${name}_addRow = Column([${[...add, `${name}_add`].join(", ")}]${room})`);
      parts.push(...(sheet ? [`${name}_open`] : []), `${name}_addRow`, ...(max ? [`Text(${q(word("The list is full"))}, tone="muted", visible=len($/${name}) >= ${max})`] : []));
    }
    lines.push(`${name}_view = Section(${show(title)}, [${parts.join(", ")}])`);
    if (opt.total && !count) notes.push(`need ${name}: total= joins the shown rows and all rows of the hero count, and this list has no hero count, so the kit left it out. A list has one when it is the only main element of the app`);
    else if (opt.total && max) notes.push(`need ${name}: total= joins the shown rows and all rows of the hero count, and with max= the hero count shows all rows against the limit, so the kit left it out`);
    if (!count) return { lines, top: [`${name}_view`], note: notes };
    // The only main element of the app is this list (C.6): a hero card with the count of its shown rows comes first. A sign of each row gives no emoji there.
    // total="of" shows all rows too: "12 of 12". With max= the count is all rows against the limit: "2 / 5", as a ring shows its value and goal.
    const counted = opt.countText ? show(opt.countText) : show(title);
    const value = max ? `text(len($/${name})) + " / ${max}"` : opt.total ? `text(len(${source})) + " " + ${show(opt.total)} + " " + text(len($/${name}))` : `len(${source})`;
    lines.push(`${name}_hero = Card([${icon?.k === "lit" ? `Emoji(${show(icon)}, size="large", label=${counted}), ` : ""}Metric(${counted}, ${value}, emphasis="display")], variant="hero")`);
    return { lines, top: [`${name}_view`], lead: [`${name}_hero`], note: notes };
  },

  // need line = chart("Steps", $/days, $day, $steps, kind="line"): one bar or point for each row of a list.
  // Alphy shows the label of a chart over it, so the chart has no section with the same title (tabs-chart.png: the title showed two times).
  chart(name, need) {
    const [title, list, point, value] = need.args;
    return { lines: [`${name}_chart = Chart(${show(list)}, ${show(point)}, ${show(value)}, kind=${show(need.named.kind ?? { k: "lit", v: "bar" })}, label=${show(title)})`], top: [`${name}_chart`] };
  },

  // need quiz = deck("Quiz", $/words, front=$word, back=$meaning, mark=$known): one row at a time. A tap shows the back, then yes or no goes to the next row.
  deck(name, need, { caption, word, emptyOf }) {
    const [title, list] = need.args, opt = need.named, text = caption;
    // Over an empty list the deck shows the empty text of the list, else the kit's words, and no end, no score, and no button (L1 item 3).
    const none = [`$/${name}_none := len(${show(list)}) == 0`, `${name}_empty = Text(${emptyOf(list) ?? q(word("No records yet"))}, tone="muted", align="center", visible=$/${name}_none)`];
    const end = `visible=$/${name}_end && !$/${name}_none`;
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
        `$/${name}_now := at(${show(list)}, $/${name}_i)`, `$/${name}_end := $/${name}_i >= len(${show(list)})`, ...none,
        `$/${name}_right := lower(trim(text($/${name}_typed))) == lower(trim(text(${now(opt.answer)})))`,
        ...picture, front,
        `${name}_field = TextField(${text("field", "Your answer")}, $/${name}_typed, enabled=!$/${name}_checked)`,
        `${name}_check = Button(${text("check", "Check")}, action=[when($/${name}_right, inc($/${name}_points)), set($/${name}_checked, true)], visible=!$/${name}_checked)`,
        `${name}_result = Notice($/${name}_right ? ${text("right", "Right")} : ${text("wrong", "Wrong")} + ": " + text(${now(opt.answer)}), visible=$/${name}_checked)`,
        `${name}_next = Button(${text("next", "Next")}, action=[inc($/${name}_i), set($/${name}_typed, ""), set($/${name}_checked, false)], visible=$/${name}_checked)`,
        `${name}_card = Card([${inCard}${name}_front], variant="hero", visible=!$/${name}_end)`,
        `${name}_reply = Column([${name}_field, ${name}_check, ${name}_result, ${name}_next], visible=!$/${name}_end)`,
        `${name}_again = Button(${text("again", "Again")}, action=[set($/${name}_i, 0), set($/${name}_points, 0), set($/${name}_typed, ""), set($/${name}_checked, false)])`,
        `${name}_endCard = Card([Heading(${text("done", "Done")}), ${name}_again], ${end})`,
        `${name}_score = Metric(${text("points", "Points")}, $/${name}_points, visible=!$/${name}_none)`,
        `${name}_view = Section(${show(title)}, [${name}_empty, ${name}_card, ${name}_reply, ${name}_endCard, ${name}_score])`],
      top: [`${name}_view`],
    };
    // Without back= the deck is pages: no hidden side and no count. A next and a back button, and an end after the last row.
    // At the end Next goes and Back stays, so a deck of one row has a Back button that works (L1 item 3).
    // Back is outlined and forward is filled, both explicit, as in the steps and a lesson: Alphy fills the first available button with no variant.
    if (!opt.back) return {
      lines: [`$/${name}_i = 0`, `$/${name}_now := at(${show(list)}, $/${name}_i)`, `$/${name}_end := $/${name}_i >= len(${show(list)})`, ...none,
        ...picture, `${name}_front = Heading(text(${now(opt.front)}))`,
        `${name}_prev = Button(${text("prev", "Back")}, action=dec($/${name}_i), enabled=$/${name}_i > 0, variant="outlined")`, `${name}_next = Button(${text("next", "Next")}, action=inc($/${name}_i), variant="filled", visible=!$/${name}_end)`,
        `${name}_card = Card([${inCard}${name}_front], variant="hero", visible=!$/${name}_end)`,
        `${name}_turn = Row([${name}_prev, ${name}_next], visible=!$/${name}_none)`,
        `${name}_again = Button(${text("again", "Again")}, action=set($/${name}_i, 0))`,
        `${name}_endCard = Card([Heading(${text("done", "Done")}), ${name}_again], ${end})`,
        `${name}_view = Section(${show(title)}, [${name}_empty, ${name}_card, ${name}_turn, ${name}_endCard])`],
      top: [`${name}_view`],
    };
    const next = `inc($/${name}_i), set($/${name}_open, false)`;
    return {
      lines: [`$/${name}_i = 0`, `$/${name}_open = false`, `$/${name}_yes = 0`, `$/${name}_no = 0`,
        `$/${name}_now := at(${show(list)}, $/${name}_i)`, `$/${name}_end := $/${name}_i >= len(${show(list)})`, ...none,
        ...picture, front, `${name}_back = Text(text(${now(opt.back)}), visible=$/${name}_open, emphasis="headline", align="center")`,
        `${name}_show = Button(${text("show", "Show")}, action=set($/${name}_open, true), visible=!$/${name}_open)`,
        `${name}_yesButton = Button(${text("yes", "Yes")}, action=[inc($/${name}_yes), ${opt.mark ? `set(${now(opt.mark)}, true), ` : ""}${next}], visible=$/${name}_open, icon="Check", variant="filled")`,
        `${name}_noButton = Button(${text("no", "No")}, action=[inc($/${name}_no), ${next}], visible=$/${name}_open, variant="outlined")`,
        `${name}_card = Card([${inCard}${name}_front, ${name}_back, ${name}_show], variant="hero", visible=!$/${name}_end)`,
        `${name}_mark = Row([${name}_yesButton, ${name}_noButton], visible=$/${name}_open)`,
        `${name}_again = Button(${text("again", "Again")}, action=[set($/${name}_i, 0), set($/${name}_yes, 0), set($/${name}_no, 0), set($/${name}_open, false)])`,
        `${name}_endCard = Card([Heading(${text("done", "Done")}), ${name}_again], ${end})`,
        `${name}_counts = Grid([Metric(${text("yes", "Yes")}, $/${name}_yes), Metric(${text("no", "No")}, $/${name}_no)], columns=2, visible=!$/${name}_none)`,
        `${name}_view = Section(${show(title)}, [${name}_empty, ${name}_card, ${name}_mark, ${name}_endCard, ${name}_counts])`],
      top: [`${name}_view`],
    };
  },

  // need setup = steps(["Genres", "Pages"], [[genres], [pages, week]], finish="Start"): other needs in groups, one group at a time.
  steps(name, need, { tops, caption }) {
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
  tabs(name, need, { tops }) {
    const [titles, groups] = need.args, label = need.named.title ? show(need.named.title) : `join(${show(titles)})`;
    return { lines: [`${name}_view = Tabs(${show(titles)}, [${groups.items.map((group) => `[${group.items.flatMap((member) => tops(memberName(member))).join(", ")}]`).join(", ")}], label=${label})`],
      top: [`${name}_view`], holds: groups.items.flatMap((group) => group.items.map(memberName)) };
  },

  // need done = progress("Done today", count($/habits, $done), len($/habits))
  progress(name, need, { hero }) {
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
  counter(name, need, { caption }) {
    const [title, value] = need.args, opt = need.named, step = opt.step ? show(opt.step) : "1", start = show(value ?? { k: "lit", v: 0 });
    const down = opt.min ? `set($/${name}, max(${show(opt.min)}, $/${name} - ${step}))` : `dec($/${name}, ${step})`;
    const up = opt.max ? `set($/${name}, min(${show(opt.max)}, $/${name} + ${step}))` : `inc($/${name}, ${step})`;
    const lines = [`$/${name} = ${start}`];
    // Each form of the counter uses its own captions and icon (G.4). The other form's ones have no place, so they go with a note (K1 fix 3).
    const unused = (opt.addText ? ["lessText", "moreText"] : ["icon", "resetText"]).filter((key) => opt[key]).map((key) => key + "=");
    const note = unused.length ? `need ${name}: a counter with ${opt.addText ? "addText= has one add button and a reset button" : "no addText= has a less and a more button"}, and it does not use ${unused.join(" or ")}, so the kit left ${unused.length > 1 ? "them" : "it"} out` : null;
    if (opt.addText) {
      lines.push(`${name}_plus = Button(${show(opt.addText)}, action=${up}, icon=${opt.icon ? show(opt.icon) : q("Plus")}, variant="filled")`,
        `${name}_reset = Button(${caption("resetText", "Reset")}, action=set($/${name}, ${start}), icon="Refresh", variant="text")`, `${name}_view = Row([${name}_plus, ${name}_reset])`);
    } else {
      lines.push(`${name}_minus = Button(${caption("lessText", "Less")}, action=${down}, icon="Minus", variant="outlined")`,
        `${name}_value = Text($/${name}, emphasis="display", align="center", weight=1)`, `${name}_plus = Button(${caption("moreText", "More")}, action=${up}, icon="Plus", variant="filled")`,
        // One group of at most 360 dp: on a wide screen the buttons stay beside the value. The content of a phone is about 346 dp wide, so there nothing moves (L1 item 8).
        `${name}_view = Section(${show(title)}, [Row([${name}_minus, ${name}_value, ${name}_plus], max=360)])`);
    }
    return { lines, top: [`${name}_view`], note };
  },

  // need tea = timer("Tea", $/kind == "green" ? 120 : 180, alert=true): one phase with an alert is the host's own countdown, a Timer line.
  // need set = timer("Workout", 40, rounds=8, rest=20): each round is a work phase, and a rest phase is between two rounds. $/NAME counts the work phases that ended.
  // need breath = timer("Breath", 4, rest=6) and timer(..., rounds=5, rest=6, cycle=true): each round is the work phase and then the rest phase, also the last one (App.tick).
  // A timer with more than one phase, or with no alert, is plain lines with start, pause, and reset. The host moves it with its clock (App.tick).
  // Evidence: 4 of Alphy's 45 corpus cases are timers (workout-de, tea-tr, pomodoro-zh, eggs-it). Only the passing of time is the host's.
  timer(name, need, { caption }) {
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
  picture(name, need) {
    const opt = need.named, picture = `Picture(${show(need.args[0] ?? opt.found)}${opt.found ? `, found=${show(opt.found)}` : ""})`;
    const texts = [["caption", "Text(", ")"], ["credit", "Text(", ', variant="muted")'], ["tag", "Badge(", ")"]].filter(([key]) => opt[key]);
    if (!texts.length) return { lines: [`${name}_view = ${picture}`], top: [`${name}_view`] };
    return { lines: [`${name}_picture = ${picture}`, ...texts.map(([key, open, close]) => `${name}_${key} = ${open}${show(opt[key])}${close}`),
      `${name}_view = Column([${[`${name}_picture`, ...texts.map(([key]) => `${name}_${key}`)].join(", ")}])`], top: [`${name}_view`] };
  },

  // need orbit = drawing("Top view", 320, 320, [circle(160, 160, 30, "water"), each($/cells, rect($index * 40, 0, 36, 20, "wood"))])
  // A 2D picture from shapes that the host draws. Each number is a formula, so the picture follows the values of the app. Evidence: screens 2 and 3 of the dialogue mocks.
  // The drawing that is the main element is in a hero card. Its surface keeps the name NAME_view.
  drawing(name, need, { hero }) {
    const [title, width, height, ...shapes] = need.args;
    if (!height || width.k === "list" || height.k === "list") throw new DslError("drawing needs a width and a height before the shapes: drawing(label, 320, 240, [shapes])");
    // Shapes with no list around them are still the shapes.
    const list = shapes.length === 1 && shapes[0].k === "list" ? show(shapes[0]) : `[${shapes.map(show).join(", ")}]`, lines = [`${name}_view = Drawing(${[title, width, height].map(show).join(", ")}, ${list})`];
    if (hero !== name) return { lines, top: [`${name}_view`] };
    return { lines: [...lines, `${name}_card = Card([${name}_view], variant="hero")`], top: [`${name}_card`] };
  },

  // need lap = button("One turn", [set($/angle, 0), animate($/angle, 360, 5000)], enabled=$/ready): a button with an action or a list of actions.
  button(name, need) {
    const action = need.args[1] ?? need.named.action;
    if (!action) throw new DslError("button needs an action: button(label, set($/NAME, value))");
    const more = Object.entries(need.named).filter(([key]) => key !== "action").map(([key, tree]) => `, ${key}=${show(tree)}`).join("");
    return { lines: [`${name}_button = Button(${show(need.args[0])}, action=${show(action)}${more})`], top: [`${name}_button`] };
  },

  // need rover = model3d("Rover", [box("", 0, 0, 0, 4, 1, 2, "sand"), cylinder("wheels", -1.4, -0.8, 1.2, 0.6, 0.4, "z", "ink")], parts={wheels: "Wheels"})
  // A 3D model from boxes and cylinders. $/NAME/selected is the id of the part that a chip selected, or "". $/NAME/exploded is true when the model is taken apart.
  // Evidence: screen 4 of the dialogue mocks. A second tap on a chip clears the selection.
  model3d(name, need, { caption, hero }) {
    const [title, bodies] = need.args, opt = need.named, text = caption, at = (field) => `$/${name}/${field}`;
    // The line of a chip is named by its part id, or by its place when the id is no name, such as "wheel-l".
    const chipOf = (id, index) => `${name}_part_${WORD.test(id) ? id : index + 1}`;
    const turn = opt.turn?.v !== false, chips = (opt.parts?.entries ?? []).map(([id, caption], index) => [chipOf(id, index), q(id), show(caption)]), tools = [];
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
  card(name, need) {
    const [title, text] = need.args, made = [], icon = need.named.icon;
    if (need.named.eyebrow) made.push(["eyebrow", `Text(${show(need.named.eyebrow)}, variant="eyebrow")`]);
    made.push(["title", `Heading(${show(title)})`]);
    if (text) made.push(["text", `Text(${show(text)}, tone="muted")`]);
    return { lines: [...made.map(([key, line]) => `${name}_${key} = ${line}`), `${name}_view = Card([${made.map(([key]) => `${name}_${key}`).join(", ")}]${icon ? `, icon=${show(icon)}` : ""})`], top: [`${name}_view`] };
  },

  // need source = link("Apollo 17 photo", "https://science.nasa.gov/...", note="Open the source"): a link to a web page. The prototype only draws it.
  link(name, need) {
    const [title, url] = need.args;
    return { lines: [`${name}_view = Link(${show(title)}, ${show(url)}${need.named.note ? `, ${show(need.named.note)}` : ""})`], top: [`${name}_view`] };
  },

  // need steps = lesson([{title: "Look", text: "..."}, {title: "Test", text: "..."}], next="Next", back="Back", stepText="Step")
  // One step at a time, with "Step · 1 / 2" and a progress bar. $/NAME is the number of the step, from 1.
  lesson(name, need, { caption }) {
    const steps = need.args[0], opt = need.named, text = caption, count = `len($/${name}_steps)`, dots = steps.k === "list" && steps.items.length <= MAX_DOTS;
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
  question(name, need, { caption }) {
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
  content(name, need) { return { lines: [`${name}_view = Text(${show(need.args[0])})`], top: [`${name}_view`] }; },

  // need chart = hole("A bar chart of spending by category"): nothing ready does it, so a model writes NAME_view later.
  hole(name, need) { return { lines: [`${name}_view = Notice("Not built yet: " + ${show(need.args[0])})`], top: [`${name}_view`] }; },
};

/**
 * The named options of each part. An option that is not here is a fault of the need, never an option that the part drops with no word.
 * button gives each option to its Button, whose own check names each one that a Button does not take.
 */
const OPTIONS = {
  input: ["options", "min", "max", "step", "icon"], figure: ["decimals", "example", "icon", "note", "goal", "goalText", "unit"],
  entries: ["fields", "labels", "rows", "hidden", "add", "edit", "remove", "confirm", "yes", "no", "emptyText", "addText", "show", "reorder", "filter", "sort", "order", "group", "sheet", "icon", "countText", "icons", "tones", "total", "max"],
  chart: ["kind"], deck: ["front", "back", "mark", "answer", "picture", "field", "check", "right", "wrong", "next", "again", "done", "points", "prev", "show", "yes", "no"],
  steps: ["back", "next", "finish", "done"], tabs: ["title"], progress: [], counter: ["step", "min", "max", "lessText", "moreText", "addText", "icon", "resetText"],
  timer: ["alert", "rounds", "rest", "cycle", "start", "pause", "reset", "done", "workText", "restText"], picture: ["found", "caption", "credit", "tag"], drawing: [],
  model3d: ["turn", "parts", "explode", "joinText", "explodeText"], card: ["eyebrow", "icon"], link: ["note"], lesson: ["next", "back", "stepText"],
  question: ["options", "right", "rightText", "wrongText"], content: [], hole: [],
};
/** The options that each kind of input uses. A number kind is a number field or a slider, and a kind that is not here is a fault of the need. */
const NUMBER_USES = ["min", "max", "step", "icon"];
const INPUT_USES = { number: NUMBER_USES, money: NUMBER_USES, percent: NUMBER_USES, count: NUMBER_USES, range: NUMBER_USES, slider: NUMBER_USES, choice: ["options", "icon"], choices: ["options", "max"], toggle: [], text: ["icon"], date: ["icon"], time: ["icon"] };
/**
 * The type of the value of an option (G.4). A part reads a flag once when it builds the screen, so a flag is true or false in the line.
 * A number can be a formula, which the kit cannot check here.
 */
const FLAGS = new Set(["sheet", "edit", "add", "remove", "hidden", "reorder", "cycle"]), NUMBERS = new Set(["step", "decimals"]);
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
 * The main element is the first figure or progress need.
 */
function expand(file) {
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
  const hero = candidates[0] ?? null;
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
    // The empty text of another need that the part read, so that a change of that text writes the part again.
    let built, parsed, read = "";
    try {
      const word = (text, option) => {
        if (option && !hasWords(lang)) english.set(entry.key, new Set(english.get(entry.key)).add(option));
        return wordOf(lang, text);
      };
      const caption = (option, text) => (entry.tree.named[option] ? show(entry.tree.named[option]) : q(word(text, option)));
      // The emptyText= of the list need whose state is this list, such as $/words, shown as a line. A list of another kind has none.
      const emptyOf = (list) => {
        const tree = needs.find((other) => other.tree.name === "entries" && show(list) === `$/${other.key}`)?.tree.named.emptyText;
        return tree && (read = show(tree));
      };
      built = part(entry.key, entry.tree, { hero, word, caption, emptyOf, count: entry.key === listHero, role: roleOf(entry.key), tops: (other) => { if (!file.tops.has(other)) throw new DslError(`${entry.tree.name} names ${other}, and no need has that name`); return file.tops.get(other); } });
      // A part line that the host cannot read is a fault of this need, never an exception. The other needs still expand.
      parsed = built.lines.map((line) => parseLine(line));
      if (parsed.includes(null)) throw new DslError("the part made an empty line");
    } catch (failure) {
      if (!(failure instanceof DslError) && !(failure instanceof TypeError)) throw failure;
      file.partFaults.push({ line: null, text: entry.raw, message: `need ${entry.key}: ${failure.message}` });
      continue;
    }
    file.tops.set(entry.key, built.top);
    // What the part builds in a different way than the need line asks: one note or a list of them. The report shows each one.
    file.partNotes.push(...[].concat(built.note ?? []));
    // Each icon that a component of the part cannot show goes, with a note (A0-K4).
    parsed.forEach((line, index) => {
      const notes = line.kind === "component" ? dropIcons(line.tree, entry.key) : [];
      if (notes.length) { built.lines[index] = `${line.key} = ${show(line.tree)}`; file.partNotes.push(...notes); }
    });
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
    // A change of the language of the app, of the place of a figure, or of the empty text that the part read writes the part again.
    const made = `${entry.raw}${entry.key === hero || entry.key === listHero}${lang}${roleOf(entry.key)}${read}`;
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

return { ICONS, ICONED, choices, FIELD_KINDS, needsOf, faultName, expand };
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
  // The run has its own clock, which starts at the clock of the compile step, so today() is the day of the compile. Only the run moves that clock.
  const real = settings.now, clock = { now: real() };
  settings.now = () => clock.now;
  try { return smokeRun(file, limit, clock); } finally { settings.now = real; }
}
function smokeRun(file, limit, clock) {
  const app = new App(file), faults = [], notes = [], trace = [], done = new Set(), reached = new Set(), idle = new Map();
  // A fault of a line that a part wrote names its need (faultName): a text of the kit that starts with that line, and a control as its caption in its need.
  const named = faultName(file), head = (text) => text.replace(/^[^ :]+/, (line) => named(line));
  const titleOf = (node) => {
    const caption = `the ${node.type} "${node.props.label ?? node.props.icon ?? ""}"`, line = node.name && named(node.name);
    return !node.name ? caption : line === node.name ? line : `${caption} of ${line}`;
  };
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
  /** True when the screen differs from seen while the timed changes that a press started move. A full turn ends where it started (FX-11). */
  const moves = (pressed, seen) => {
    const ended = save();
    restore(pressed);
    // A share of the time that is no simple fraction: a shape that turns 2, 3, or 4 times as fast as the value is not at a whole turn there.
    clock.now = Math.min(...app.state._run.map((run) => run.start + (run.end - run.start) * 0.37));
    app.tick();
    const shown = look(screen()) !== seen;
    restore(ended);
    return shown;
  };
  // Each control is used from the state in which it came to the screen. So one control cannot hide or delete what the next one needs.
  const queue = [start];
  const drain = () => {
    while (queue.length && done.size < limit && !app.tripped) {
      const base = queue.shift();
      const reset = () => restore(base);
      reset();
      const here = controls().map((control) => control.key), used = here.filter((key) => done.has(key));
      // A control that the run used in an earlier state can bring a control that it did not use yet to the screen in this state: a tab or
      // the next step opens over a list that has rows now (A0-K3). The run goes on from that state too. A field gives no new place.
      for (const key of used) {
        reset();
        const found = controls().find((control) => control.key === key);
        if (!found || found.node.props.bind || app.fire(found.node)) continue;
        if (controls().some((control) => !done.has(control.key) && !here.includes(control.key))) queue.push(save());
      }
      for (const key of here) {
        if (app.tripped) break;
        if (done.has(key) || done.size >= limit) continue;
        done.add(key);
        reset();
        const found = controls().find((control) => control.key === key);
        if (!found) continue;
        // The tab that is open does nothing when a person taps it. That is the rule of the host, not a fault of the app.
        if (found.node.tab && found.node.props.selected) continue;
        const { node, parent } = found, p = node.props, title = titleOf(node), faultOf = (fault) => `${named(fault.where)}: ${fault.message}`;
        let seen = look(screen());
        const own = look({ ...node, children: [] }), known = new Set(app.faults.map(faultOf));
        let error;
        if (p.bind) {
          const now = Number(p.value) || 0, by = p.step ?? 1;
          // The options of the mark of a Table or a Chart are the label of each row or point (data forms).
          const options = node.type === "Table" ? (p.rows ?? []).map((row) => String(Array.isArray(row) ? row[0] : row)) : node.type === "Chart" ? (p.points ?? []).map((point) => String(point.label)) : p.options;
          const sample = ["Toggle", "CheckBox"].includes(node.type) ? !p.value : ["Segmented", "Select", "Table", "Chart"].includes(node.type) ? (options ?? []).find((option) => option !== p.value)
            : node.type === "TextField" ? (p.value === "Test" ? "Test 2" : "Test") : node.type === "DateField" ? (p.value === "2026-01-02" ? "2026-01-03" : "2026-01-02")
            : node.type === "TimeField" ? (p.value === "08:30" ? "09:30" : "08:30")
            // A range moves to its bounds, as the smoke step of Alphy does: [min, max], or [min, min] when it is there.
            : node.type === "RangeSlider" ? [p.min ?? 0, p.value?.[0] === (p.min ?? 0) && p.value?.[1] === (p.max ?? 100) ? p.min ?? 0 : p.max ?? 100]
            : now + by > (p.max ?? Infinity) ? now - by : now + by;
          // The person uses a Choices through its option buttons, which have their own turn.
          if (sample === undefined || node.type === "Choices") continue;
          error = app.change(node, sample);
        } else error = app.fire(node);
        if (error) { faults.push("smoke run: " + head(error)); continue; }
        // A button whose action needs a typed value, such as the add button of a form, gets a second press after the run types into each
        // empty text field that its action reads. It passes only when that press changes a value of the typed state, so a dead button beside a field stays a fault.
        // The typed text shows in its field, so the run looks at the screen again before that press.
        if (!p.bind && JSON.stringify(app.state) === base.text && typeInto(node)) {
          seen = look(screen());
          const filled = JSON.stringify(app.state);
          error = app.fire(controls().find((control) => control.key === key)?.node ?? node);
          if (error || JSON.stringify(app.state) === filled) reset();
          if (error) { faults.push("smoke run: " + head(error)); continue; }
        }
        // A timed change shows only when time passes. The run moves its clock to the end of each one before it looks at the screen.
        const pressed = app.state._run && save();
        if (pressed) { wait(); app.tick(); }
        const after = controls(), late = [...new Set(app.faults.map(faultOf))].filter((text) => !known.has(text));
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
        trace.push(`${p.bind ? "set" : "tap"} ${node.name ?? title}: ` + Object.keys({ ...was, ...app.state }).filter((name) => JSON.stringify(was[name]) !== JSON.stringify(app.state[name]))
          .map((name) => `${name} ${short(was[name])} -> ${short(app.state[name])}`).join(", "));
        if (look(screen()) === seen) { if (!pressed || !moves(pressed, seen)) faults.push([title, "changes a value, and no part of the screen shows it"]); }
        else if (onOff(node) && look({ ...(after.find((control) => control.key === key)?.node ?? {}), children: [] }) === own) {
          // One on-off button can show its effect in a different part of the screen. A group of them cannot: the person must see which ones are on.
          const finding = [title, "turns a value on and off, and looks the same in both states. Give it selected=test"];
          ((parent?.children ?? []).filter(onOff).length > 1 ? faults : notes).push(finding);
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
  if (app.tripped) {
    const found = grouped(faults, "smoke run: ");
    return { faults: found.some((fault) => fault.includes(`more than ${STEPS} steps`)) ? found : [...found, `smoke run: ${head(app.told)}`], notes: grouped(notes, ""), trace };
  }
  // A control that changed nothing in each state that the run tried. A field is not in this list: the run cannot know a value that it accepts.
  for (const title of idle.values()) faults.push(`smoke run: ${title} never changes a value: its action does nothing`);
  const never = [...new Set(all.filter((key) => !reached.has(key) && !/^0(\.\d+)*$/.test(key)).map((key) => named(key)))];
  if (never.length) faults.push(`smoke run: ${never.slice(0, 3).join(", ")}${never.length > 3 ? ` and ${never.length - 3} more` : ""} never comes to the screen: it is in a part that stays hidden, or it stays disabled. A control that the person can reach must show it`);
  return { faults: grouped(faults, "smoke run: "), notes: grouped(notes, ""), trace };
}

/** Many controls with the same finding become one line, after prefix. A finding of a control is [title, text], and each other finding is a line. */
function grouped(findings, prefix) {
  const byText = new Map();
  for (const finding of findings) {
    const [title, text] = Array.isArray(finding) ? finding : [null, finding];
    byText.set(text, title === null ? null : [...(byText.get(text) ?? []), title]);
  }
  return [...byText].map(([text, titles]) => (titles ? `${prefix}${titles.slice(0, 3).join(", ")}${titles.length > 3 ? ` and ${titles.length - 3} more` : ""} ${text}` : text));
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
function gaps(file, reached, named) {
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
  // A path that no state line or formula line sets is a fault of the line that reads it: of its need for a need line or a line of a part.
  const roots = new Set([...file.of("state"), ...file.of("formula")].map((entry) => entry.key.split("/")[1]));
  for (const entry of file.entries.values()) {
    // The groups of a tabs or steps need name needs, also as $/NAME (memberName in parts.js). They read no value.
    const names = new Set(), line = entry.kind === "need" ? `need ${entry.key}` : named(entry.key);
    if (entry.kind === "need" && ["tabs", "steps"].includes(entry.tree.name) && entry.tree.args?.[1]) walk(entry.tree.args[1], (node) => names.add(node));
    walk(entry.tree, (node) => {
      if (node.k === "path" && node.abs && !names.has(node) && !roots.has(node.segs[0])) out.push(`${line} reads $/${node.segs[0]}, and no line sets it`);
    });
  }
  for (const entry of file.entries.values()) {
    if (entry.origin !== "author" || settings.gate !== "checks") continue;
    if (entry.kind === "formula" && !read(entry.key)) out.push(`${entry.key}: no check reads this formula`);
    if (entry.kind !== "component") continue;
    if (given(entry.tree).action && !used.has(entry.key)) out.push(`${entry.key}: no check taps or sets this control`);
    walk(entry.tree, (node) => {
      if (node === entry.tree || node.k !== "call" || !Object.hasOwn(CATALOG, node.name)) return;
      const props = given(node);
      if (props.action || props.bind) out.push(`${entry.key}: give the ${node.name} inside it its own named line, so that a check can use it`);
    });
  }
  return out;
}

const SIZES = ["small", "medium", "large"], ALIGNS = ["start", "center", "end"], GAPS = ["none", "small", "medium", "large"], TONES = ["muted", "accent", "success", "warning", "error"];
/**
 * The look options of the components, and the values of each one that the kit gives: values of Alphy's catalog (ToolUiCatalog).
 * The run-time puts each value into its node as it is, so only the compile step reads this table.
 */
const LOOKS = {
  icon: Object.fromEntries(ICONED.map((type) => [type, ICONS])),
  variant: { Button: ["filled", "outlined", "text", "tonal"], Text: ["muted", "eyebrow", "source"], Card: ["hero"], CheckBox: ["circle"], Progress: ["bar", "dots"] },
  size: { Emoji: SIZES },
  emphasis: { Text: ["body", "title", "headline", "label", "number", "display"], Metric: ["display", "number"] },
  align: { Text: ALIGNS, Row: ALIGNS },
  tone: { Text: TONES, Metric: TONES, Badge: TONES, Notice: TONES },
  gap: { Row: GAPS, Column: GAPS },
  columns: { Grid: [1, 2, 3, 4] },
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
  const named = faultName(file), broken = Object.keys(app.state).filter((name) => !finite(app.state[name])).map((name) => {
    // A formula of a part names its need.
    const line = named("$/" + name);
    return `${line === "$/" + name ? "" : line + ": "}$/${name} is not a finite number at the start`;
  });
  // The fault of a need, of a need line, or of the root line already says why the screen has no root. A second fault "no root line"
  // made a model write root by hand (A0-K2), so the report gives it only with no such cause.
  const rootless = !file.get("root") && !file.partFaults.length && !file.faults.some((fault) => /^(need\s|root\s*=)/.test(fault.text));
  const faults = [
    // A line of many thousand characters, such as one with too many levels, gives its start only.
    ...[...file.faults, ...file.partFaults].map((f) => `${f.line == null ? "line" : "line " + f.line}: ${f.message}: ${f.text.length > 200 ? f.text.slice(0, 200) + "..." : f.text}`),
    ...app.faults.map((f) => `${named(f.where)}: ${f.message}`),
    ...circles(file, named),
    ...broken,
    ...(rootless ? ["no root line"] : []),
    ...gaps(file, app.reached, named),
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
const { MAX_ROW_FIELDS, MAX_ROWS, ALPHY, stable, namer, wished, clockOf, MAX_TEXT, cast, castRow, relative, rows } = (() => {
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

/** True for a name that Alphy takes as a field and that is not one of the objects of the shown values: d, v, d2, and so on. */
const takes = (name) => SAFE.test(name) && !REFUSED.has(name) && !/^[dv]\d*$/.test(name);
/**
 * The wish of a key of a stored value: its own wish when Alphy takes it, else f and 11 letters or digits of a hash of the key (53 bits).
 * So the name of a value of the person comes from its key alone, and a line before it cannot change it (loop plan, task 2).
 */
function stable(key, wish) {
  if (takes(wish)) return wish;
  let a = 0xdeadbeef, b = 0x41c6ce57;
  for (const sign of key) { const code = sign.codePointAt(0); a = Math.imul(a ^ code, 2654435761); b = Math.imul(b ^ code, 1597334677); }
  a = Math.imul(a ^ (a >>> 16), 2246822507) ^ Math.imul(b ^ (b >>> 13), 3266489909);
  b = Math.imul(b ^ (b >>> 16), 2246822507) ^ Math.imul(a ^ (a >>> 13), 3266489909);
  return "f" + (4294967296 * (2097151 & b) + (a >>> 0)).toString(36).padStart(11, "0");
}

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
    if (!Object.hasOwn(differ, key) && (!takes(name) || taken.has(name))) {
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
const clockOf = (minutes) => { const at = Math.min(1439, Math.max(0, Math.round(Number(minutes) || 0))); return `${pad(Math.floor(at / 60))}:${pad(at % 60)}`; };
/** Alphy's state holds a text of at most 2048 characters (ToolValueSchema). */
const MAX_TEXT = 2048;
/**
 * A value of the kit in the form of its Alphy field. kind is "number", "string", "boolean", "time", or "date". A shown value has the type
 * of its field, which the emitter fixes with its first value (app.types). A number that is not finite is 0, and a text is never null, a list,
 * or a record. Each value stays inside what Alphy's state holds: a number of at most 2^53 - 1 either way, and a text of at most 2048 characters.
 */
const cast = (kind, value, today) => (kind === "time" ? minutes(value) ?? 0 : kind === "date" ? (dayNumber(value) === null ? today : String(value).trim())
  : kind === "number" ? (Number.isFinite(value) ? Math.min(Number.MAX_SAFE_INTEGER, Math.max(-Number.MAX_SAFE_INTEGER, value)) : 0) : kind === "boolean" ? value === true
  : (value === null || value === undefined || typeof value === "object" ? "" : String(value)).slice(0, MAX_TEXT));
/** A row of the kit as an Alphy row. spec is the field, the Alphy name, and the kind of each stored field (app.lists). */
const castRow = (row, spec, today) => ({ id: String(row?.id ?? ""), ...Object.fromEntries(spec.map(([field, safe, kind]) => [safe, cast(kind, row?.[field], today)])) });
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

return { MAX_ROW_FIELDS, MAX_ROWS, ALPHY, stable, namer, wished, clockOf, MAX_TEXT, cast, castRow, relative, rows };
})();
// draw.js
const { BAR_SURFACE, sideOf, LIMITS, PALETTE, colorOf, utf8, hostBytes, commandsOf, barOf, MODEL, STAGE, ACCENTS, accentOf, polygonsOf, budgetOf, fit } = (() => {
// The drawing of a surface: the kit's shapes as Alphy's drawing commands (GraphicsContract.frame), and the limits of one reply.




/** The surface of a timer's bar, in Alphy's pixels of a surface: the bar fills it, 16 to 1, as a thick bar of the card's width. Alphy takes 64 pixels high at least. */
const BAR_SURFACE = { width: 1024, height: 64 };
/** A side of a surface: Alphy takes 64 to 2048 pixels each way. */
const sideOf = (value) => Math.min(2048, Math.max(64, Math.round(Number(value)) || 0));
/** What Alphy draws in one reply, for all surfaces together (GraphicsContract.frame, ArtifactProtocol.validateSurfaces): also the path segments and the gradient stops. */
const LIMITS = { commands: 512, pairs: 2048, chars: 2048, bytes: 32768, segments: 512, stops: 128 };
/**
 * The kit's color names as Alphy's colors: a tone of the theme for a role, else the hex of the kit. The role surface is the lowest container of the
 * theme (NativeToolUi.drawingRoles): white on a light theme and near black on a dark one (L1 item 6).
 */
const ROLES = { ink: "default", muted: "muted", accent: "accent", success: "success", warning: "warning", error: "error", surface: "surface" };
const PALETTE = Object.fromEntries(Object.keys(TONES).map((name) => [name, ROLES[name] ?? TONES[name]]));
const clampOf = (value, low, high) => Math.min(high, Math.max(low, value));
/**
 * A color name of the palette, a hex that passes as it is, or the default ink for each other text. A tone (core.js TONE) of a role of the theme
 * stays a tone, which Alphy mixes from the color of the role in the theme (GraphicsContract.toneOf). A tone of a hex color is mixed here the
 * same way: 30 % toward white for each step up, 25 % toward black for each step down. opacity multiplies the alpha.
 */
function colorOf(color, palette = PALETTE, opacity = 1) {
  const [, name, step = "", alpha = "100"] = TONE.exec(color) ?? [], share = Math.round(clampOf(Number(alpha) * opacity, 0, 100));
  const base = name ? palette[name] : HEX.test(color) ? color : "default";
  if (!HEX.test(base) || (!step && share === 100)) return step || share < 100 ? `${base}${step}${share < 100 ? `@${share}` : ""}` : base;
  const [a, ...rgb] = argbOf(base), up = Number(step) > 0, mix = up ? 0.3 * Number(step) : -0.25 * Number(step);
  const channels = rgb.map((value) => hex2(value + ((up ? 255 : 0) - value) * mix)).join("");
  return a === 255 && share === 100 ? `#${channels}` : `#${hex2((a * share) / 100)}${channels}`;
}

/** The UTF-8 bytes of a text. The sandbox has no TextEncoder that the kit can count on. */
const utf8 = (text) => {
  let count = 0;
  for (const char of text) { const code = char.codePointAt(0); count += code < 0x80 ? 1 : code < 0x800 ? 2 : code < 0x10000 ? 3 : 4; }
  return count;
};
/** The bytes of a value in JSON as the host writes it: org.json writes "/" as "\/". */
const hostBytes = (value) => utf8(JSON.stringify(value).replace(/\//g, "\\/"));

/**
 * True when the point X, Y is inside the [x, y] points by the non-zero rule, the fill rule of an Android Path (SurfaceDrawing.kt): each edge that
 * crosses the ray from the point toward a larger x adds 1 when it goes toward a larger y and -1 when it goes toward a smaller y, and the sum is not 0.
 */
const wound = (points, X, Y) => points.reduce((turns, [x, y], index) => {
  const [a, b] = points.at(index - 1), side = (x - a) * (Y - b) - (X - a) * (y - b);
  return turns + (b <= Y ? +(y > Y && side > 0) : -(y <= Y && side < 0));
}, 0) !== 0;

/**
 * The paint of a command: its color with the opacity of the shape, or a gradient over its box. gradient= fades from the color at the top of the
 * box to its color at the bottom. With radial=SHARE it fades from a point to the edge: radial=0 from the center, which gives a soft spot, and
 * radial=1 from a point up and left of the center, which gives a body its volume.
 */
function painted(command, shape, palette) {
  const opacity = clampOf(shape.opacity ?? 1, 0, 1), color = colorOf(shape.color, palette, opacity);
  if (!shape.gradient || command.type === "text" || command.type === "line" || command.type === "arc" || command.stroke) return { ...command, color };
  const { x, y, w, h } = boxOf(command), stops = [{ offset: 0, color }, { offset: 1, color: colorOf(shape.gradient, palette, opacity) }];
  const share = clampOf(shape.radial ?? 0, 0, 1);
  const gradient = shape.radial !== undefined ? { type: "radial", x: fixed(x + w * (0.5 - 0.12 * share)), y: fixed(y + h * (0.5 - 0.16 * share)), radius: fixed(Math.max(0.1, Math.max(w, h) * (0.5 + 0.3 * share))), stops }
    : { type: "linear", x: fixed(x + w / 2), y: fixed(y), x2: fixed(x + w / 2), y2: fixed(y + Math.max(h, 0.1)), stops };
  return { ...command, gradient };
}
/** The transform of a turn by degrees counterclockwise and a scale, with the origin moved to x, y (GraphicsContract.frame). */
const transformOf = (degrees, scale, x, y) => {
  const angle = (degrees * Math.PI) / 180, cos = Math.cos(angle) * scale, sin = Math.sin(angle) * scale, four = (value) => Math.round(value * 10000) / 10000;
  return [four(cos), four(-sin), four(sin), four(cos), fixed(x), fixed(y)];
};
/** One shape of the kit (App.drawn) as its commands of Alphy: a glow under it, the shape, and a group for a turn or a group shape. None for a shape that Alphy cannot draw. */
function commandsOfShape(shape, palette) {
  if (shape.shape === "group") {
    const scale = Number.isFinite(shape.scale) && shape.scale > 0 ? Math.min(shape.scale, 64) : 1;
    return inside([shape.x, shape.y]) ? [{ type: "group", transform: transformOf(shape.turn ?? 0, scale, shape.x, shape.y), children: commandsOf(shape.shapes ?? [], palette) }] : [];
  }
  const command = KINDS[shape.shape]?.draw(shape);
  if (!command) return [];
  // A picture of the library keeps its own colors: no paint, no glow.
  if (command.type === "image") return [command];
  const box = boxOf(command), [cx, cy] = [box.x + box.w / 2, box.y + box.h / 2], out = [];
  if (shape.glow) {
    // A soft light around the shape: a disk of 1.4 times its size in its color that fades to nothing.
    const radius = fixed(Math.max(4, Math.max(box.w, box.h) * 0.7)), fade = (share) => colorOf(shape.color, palette, share * clampOf(shape.opacity ?? 1, 0, 1));
    if (inside([cx - radius, cy - radius, cx + radius, cy + radius]))
      out.push({ type: "circle", x: fixed(cx), y: fixed(cy), radius, gradient: { type: "radial", x: fixed(cx), y: fixed(cy), radius, stops: [{ offset: 0, color: fade(0.55) }, { offset: 1, color: fade(0) }] } });
  }
  out.push(painted(command, shape, palette));
  if (!shape.turn) return out;
  // A turn around the center of the box: the group moves the center to the origin, turns, and moves it back.
  const [a, b, c, d] = transformOf(shape.turn, 1, 0, 0);
  return [{ type: "group", transform: [a, b, c, d, fixed(cx - a * cx - c * cy), fixed(cy - b * cx - d * cy)], children: out }];
}

/**
 * The shapes of the kit as Alphy's commands, each with its color by the palette: a color name as an Alphy color. A shape that Alphy cannot
 * draw is left out, and a size past Alphy's bounds is clamped. A text takes a color that reads on the last filled disk, box, or polygon under its anchor (L1 item 6):
 * - on a shape in a hex color, black or white by the luminance of the shape. With a gamma of 2.2 and a limit of 0.18, the contrast is 4.5 or more.
 * - on a shape in an ink role, the surface role. The host keeps each ink and the surface role at 4.5 or more (NativeToolUi.drawingRoles).
 * - on a shape in muted, or in a tone at 50 % alpha or less, the default ink. Such a shape is a light tint, and only the default ink keeps 4.5 on that tint.
 * - on a shape in surface, or on no shape, its own color, and the default ink for the surface role.
 * A line and an outline cover no text.
 */
const commandsOf = (shapes, palette = PALETTE) => shapes.reduce((drawn, shape) => {
  const made = commandsOfShape(shape, palette), command = made.length === 1 ? made[0] : null;
  if (command?.type !== "text") { drawn.push(...made); return drawn; }
  let color = command.color, under;
  {
    const { x: X, y: Y } = command;
    // A box is a rect or a roundRect, the only types that start with r. An arc is the outline of a disk.
    for (const { type, x, y, radius, width, height, points, color: fill } of drawn) {
      if (type === "circle" ? (X - x) ** 2 + (Y - y) ** 2 <= radius ** 2 : type === "polygon" ? wound(points, X, Y)
        : /^r/.test(type) && X >= x && X <= x + width && Y >= y && Y <= y + height) under = fill;
    }
    // The relative luminance of the shape, with weights times 10000.
    color = HEX.test(under) ? (argbOf(under).reduce((sum, channel, index) => sum + [0, 2126, 7152, 722][index] * (channel / 255) ** 2.2, 0) > 1800 ? "#000000" : "#ffffff")
      : under === "muted" || Number(TONE.exec(under ?? "")?.[3] ?? 100) <= 50 ? "default" : under && under !== "surface" ? "surface" : color === "surface" ? "default" : color;
  }
  drawn.push({ ...command, color });
  return drawn;
}, []);

/**
 * The bar of a timer's phase: the track, and the share of the phase that passed (0 to 1) in the color of the phase. Three shapes.
 * The track is the muted tone of the theme, which Alphy draws as a light tint of its muted ink (SurfaceDrawing.kt), so it reads in both themes.
 * A dot in the color of the phase near the end of the track is the stop sign of a Material bar: it shows where the bar ends before the phase moves.
 */
const barOf = (share, color) => [
  { shape: "rect", x: 0, y: 0, w: BAR_SURFACE.width, h: BAR_SURFACE.height, color: "muted", radius: BAR_SURFACE.height / 2 },
  { shape: "circle", x: BAR_SURFACE.width - BAR_SURFACE.height, y: BAR_SURFACE.height / 2, r: 10, color },
  { shape: "rect", x: 0, y: 0, w: Math.round(BAR_SURFACE.width * share), h: BAR_SURFACE.height, color, radius: BAR_SURFACE.height / 2 },
];

/**
 * The surface of a model, and its stage: a box under the faces in the muted tone of the theme, a tint that Alphy draws at a low alpha (SurfaceDrawing.kt MUTED_SHAPE_ALPHA).
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

/**
 * What one command costs (GraphicsContract.frame): a box, a circle, and a text 1 pair, a line 2, a polygon or a polyline 1 for each point, a
 * path 1 for each point of its segments, a gradient 1 for its point and 1 more for a linear one, and a group what its commands cost and 1 more.
 */
const SEGMENT_PAIRS = { move: 1, line: 1, quad: 2, cubic: 3, close: 0 };
const pairsOf = (command) => (command.type === "group" ? 1 + command.children.reduce((sum, child) => sum + pairsOf(child), 0)
  : (command.points?.length ?? (command.segments ? command.segments.reduce((sum, one) => sum + SEGMENT_PAIRS[one.type], 0) : command.type === "line" ? 2 : 1))
    + (command.gradient ? (command.gradient.type === "linear" ? 2 : 1) : 0));
const each = (cost) => (command) => (command.type === "group" ? command.children.reduce((sum, child) => sum + each(cost)(child), 0) : cost(command));
const charsOf = each((command) => command.text?.length ?? 0);
const countOf = (command) => (command.type === "group" ? 1 + command.children.reduce((sum, child) => sum + countOf(child), 0) : 1);
const segmentsOf = each((command) => command.segments?.length ?? 0);
const stopsOf = each((command) => command.gradient?.stops.length ?? 0);
/**
 * What the surfaces of one reply cost, from [id, commands] in order: the commands, pairs, and text characters of all of them, and
 * the bytes of {id: {commands}, ...} as the host writes it. The emitter checks the first reply with it, and fit starts from the reply with no commands.
 */
const budgetOf = (surfaces) => {
  const commands = surfaces.flatMap(([, list]) => list);
  const sum = (cost) => commands.reduce((total, command) => total + cost(command), 0);
  return { commands: sum(countOf), pairs: sum(pairsOf), chars: sum(charsOf), bytes: hostBytes(Object.fromEntries(surfaces.map(([id, list]) => [id, { commands: list }]))),
    segments: sum(segmentsOf), stops: sum(stopsOf) };
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
    const cost = { commands: countOf(command), pairs: pairsOf(command), chars: charsOf(command), bytes: hostBytes(command) + (index ? 1 : 0), segments: segmentsOf(command), stops: stopsOf(command) };
    full ||= Object.keys(LIMITS).some((key) => used[key] + cost[key] > LIMITS[key]);
    if (!full) for (const key of Object.keys(cost)) used[key] += cost[key];
    return !full;
  }) }]));
}

return { BAR_SURFACE, sideOf, LIMITS, PALETTE, colorOf, utf8, hostBytes, commandsOf, barOf, MODEL, STAGE, ACCENTS, accentOf, polygonsOf, budgetOf, fit };
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
const { PER_OBJECT, MAX_ARRAY, MAX_STATE_BYTES, TYPED, MAX_POINTS, ALERT_CAPABILITIES, header, screen, stateOf, load, kitOf, handle } = (() => {
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
/** The rows that a Table shows on a phone. More rows scroll inside its box, so the page does not scroll (block 2 plan, D3). */
const VISIBLE_ROWS = 8;
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

const STYLES = { eyebrow: { emphasis: "label", tone: "accent" }, muted: { tone: "muted" }, source: { emphasis: "source", tone: "muted" } };
/** What a row template of Alphy cannot hold (ToolUiCatalog: no list, dialog, sheet, or canvas in a row), and what keeps one state field for all rows. */
const NOT_IN_ROW = new Set(["List", "Dialog", "Chart", "Table", "Tabs", "Timer", "Drawing", "Model3d"]);
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
// Alphy draws a segmented control whose labels do not fit its width as cells that wrap (NativeToolUi.kt NativeChoices, L1 item 4).
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
  // max= is the largest width of the row in dp, such as the one group of a counter on a wide screen (L1 item 8).
  Row: (n, at) => at.form() ?? { ...(n.children.some((child) => child.props.weight !== undefined) ? { type: "row", wrap: false, ...rowStyle(n, n.props.align ?? "center"), children: at.children(true) }
    : { type: "flowRow", ...rowStyle(n), children: at.children() }), ...(n.props.max && { layout: { maxWidth: n.props.max } }) },
  Grid: (n, at) => ({ type: "grid", columns: n.props.columns ?? 2, children: at.children() }),
  Card: (n, at) => ({ type: "card", ...(n.props.variant === "hero" && { variant: "hero" }), ...iconOf(n), children: at.children() }),
  // A Section of no title is a fault of its line, and Alphy takes a section with no text (ToolUiCatalog).
  Section: (n, at) => {
    const title = at.plain(n.props.title);
    if (MESSAGES && !title) at.fault("a Section needs a title of 1 to 300 characters");
    return { type: "section", ...(title && { text: title }), children: at.children() };
  },
  // A tone or an emphasis of the plan wins over the one of its variant.
  // The emphasis source is a small line with a link icon before it (stage 5 look).
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
    const { value, max } = n.props, dots = n.props.variant === "dots" && !at.scope && Number.isInteger(max) && max >= 1 && max <= MAX_DOTS;
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
  Table: (n, at) => at.table(),
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
    const bound = (prop, long = false) => (expr[prop] && (long || expr[prop].k !== "lit") ? slot("d", `${key}.${prop}`, expr[prop], node.props[prop], at) : null);
    const at = {
      scope, fault, off,
      // Options that read the row are a fault of their own (above), so their rules give no fault more.
      rowOptions: MESSAGES && Boolean(scope) && readsRow(expr.options),
      blank: Boolean(scope && !scope.out),
      children: (weighted = false, inner = within) => node.children.map((child, index) => walk(child, child.name ?? `${key}:${index}`, line, { ...inner, weighted })).filter(Boolean),
      // The text of a control: its label, else the kit's name of its line, which a person can read. Never a safe name such as f3.
      caption: (label) => at.plain(label) || (node.name ? node.name : `${line} ${node.props.icon ?? node.type}`).replace(/_/g, " "),
      // The text of a progress, a ring, a chart, and tabs: its label=, else the title of the Section around it, else the caption.
      label: () => at.caption(at.plain(node.props.label) || ctx.title),
      // Alphy takes a literal text of 1 to 300 characters and a bound text of up to 2048 (ToolUiCatalog, ToolValueSchema), so a longer literal at the top is bound.
      text: (prop) => {
        const literal = String(node.props[prop] ?? ""), long = !scope && expr[prop]?.k === "lit" && literal.length > 300;
        if (MESSAGES && long && literal.length > MAX_TEXT) fault(`the text has ${literal.length} characters, and Alphy keeps at most ${MAX_TEXT}. Use a shorter text`);
        const bind = bound(prop, long);
        if (bind) return { text: "", bind };
        const text = at.plain(node.props[prop]);
        if (MESSAGES && !text) fault(`a ${node.type} needs a text of 1 to 300 characters`);
        return { text };
      },
      // A text of the screen that is a list or a record is a fault, never "a,b" or "[object Object]". Alphy shows at most 300 characters of a text of the screen.
      plain: (value) => {
        if (value !== null && typeof value === "object") {
          if (MESSAGES) fault(`it shows a ${Array.isArray(value) ? "list" : "record"} as a text, and Alphy shows one text there. Show one field of it, or join(list)`);
          return "";
        }
        const text = String(value ?? "");
        if (MESSAGES && text.length > 300) fault(`it shows a text of ${text.length} characters here, and Alphy shows at most 300. Use a shorter text`);
        return text.slice(0, 300);
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
        // A chart with bind= marks one point: its label is the bound value, and a tap on a point sets it (data forms, block 2 plan D7).
        const labels = points.map((point) => cast("string", point.label)), mark = node.props.bind && at.field((value) => (value === "" || labels.includes(value) ? value : undefined));
        return { type: "chart", text, kind, categories, series: [{ text, bind: values }], ...(mark && { action: mark.action, selectedKeyBind: mark.bind }) };
      },
      // The rows of a table as a list that the run-time writes: the first cell of a row is its id. With bind= the bound value is the id of the
      // marked row, and a tap on a row sets it. column= marks the cell of that column in the marked row. The table shows 8 rows, and more scroll in its box.
      table: () => {
        const columns = (Array.isArray(node.props.heads) ? node.props.heads : []).map((column) => at.plain(column).slice(0, 100) || " ");
        const body = (Array.isArray(node.props.rows) ? node.props.rows : []).map((row) => (Array.isArray(row) ? row : [row]));
        if (MESSAGES && (columns.length < 1 || columns.length > 8)) fault(`a Table has 1 to 8 columns, and this one has ${columns.length}`);
        const field = name(`table:${key}:rows`, wished(`${key}_rows`)), cell = (index) => `c${index}`;
        views[field] = body.map((row) => ({ id: at.plain(row[0]).slice(0, 128) || " ", ...Object.fromEntries(columns.map((_, index) => [cell(index), at.plain(row[index] ?? "")])) }));
        viewSchemas[field] = { type: "list", maxItems: MAX_ROWS, fields: { id: "string", ...Object.fromEntries(columns.map((_, index) => [cell(index), "string"])) } };
        viewOwners.push(line);
        const ids = views[field].map((row) => row.id), mark = node.props.bind && at.field((value) => (value === "" || ids.includes(value) ? value : undefined));
        const column = node.props.column, signs = node.props.signs;
        // signs= makes a comparison: a head cell with the sign of each thing (columns 1 on), and a bar under each number of a row.
        return { type: "table", text: at.plain(node.props.label).slice(0, 100) || at.caption(), bind: field, columns: columns.map((text, index) => ({ text, field: cell(index) })),
          ...(Array.isArray(signs) && { variant: "compare", signs: signs.map((sign) => at.plain(sign).slice(0, 16)) }),
          visibleRows: VISIBLE_ROWS, ...(mark && { action: mark.action, selectedKeyBind: mark.bind }),
          ...(mark && Number.isInteger(column) && column > 0 && column < columns.length && { markColumn: cell(column) }) };
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
    if (node.props.weight !== undefined && ctx.weighted) out.layout = { ...out.layout, weight: node.props.weight };
    // fit=true keeps a node at the width of its content in a column, where Alphy fills the width (L1 item 2).
    if (node.props.fit) out.layout = { ...out.layout, fillWidth: false };
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

/**
 * The Alphy state of the kit's state: each value of a state line in its field, the change of each value that an animate moves, each
 * kept field of a changed app (app.keep) as the stored state given has it, then the views and the shown values.
 */
function stateOf(kit, app, shown, stored = {}) {
  const state = {}, day = today(kit);
  for (const [root, path] of app.fields) {
    const [field, inner] = path.split("."), value = alphyOf(app, root, kit.state[root], day);
    if (inner) (state[field] ??= {})[inner] = value; else state[field] = value;
  }
  for (const field of app.keep ?? []) state[field] = stored[field];
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
    const next = stateOf(kit, app, after, state), reply = JSON.stringify(after.ui) === JSON.stringify(before.ui) ? { state: next } : { state: next, ui: after.ui };
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

return { PER_OBJECT, MAX_ARRAY, MAX_STATE_BYTES, TYPED, MAX_POINTS, ALERT_CAPABILITIES, header, screen, stateOf, load, kitOf, handle };
})();
// emit.js
const { runtime, sourceOf, dataOf, rootWish, objectWish, MAX_FIELDS, MAX_SOURCE_BYTES, emit } = (() => {
// The emitter: an expanded app file as Alphy's app definition (formatVersion 3). The source of the definition is the
// run-time (run.js and the modules that it needs) and the compiled data of the app. The run-time answers each event.









/** The text of the run-time. kit/build.js puts it into the bundle, and a test of the sources sets it from the sources. */
const runtime = { text: "" };
/** The text between the run-time and the compiled data in the source of an app. */
const HEAD = "\nreturn handle(input, ";
/** The source of an app: the run-time and the compiled data. */
const sourceOf = (app) => `${runtime.text}${HEAD}${JSON.stringify(app)});`;
/** The compiled data of a definition: the JSON that sourceOf puts after the run-time, as the sandbox gives it to handle. */
const dataOf = (definition) => JSON.parse(definition.source.slice(definition.source.lastIndexOf(HEAD) + HEAD.length, -2));
/**
 * The name that the field of the value $/root wishes, and the one of the object of the inner values of a need: stable names (rows.js).
 * A root that Alphy does not take as a name, such as yaş or pages:tab, gets the hash of its key, so two roots never wish one name.
 */
const rootWish = (root) => stable("$/" + root, root);
const objectWish = (need) => stable("need:" + need, need + "_s");
/**
 * The limits of Alphy that come from the kit's own mapping, so the author of a plan cannot see them: top-level state fields
 * (ToolValueSchema.validateSchemas), source bytes (SandboxContract.MAX_SOURCE_BYTES), and the nodes, depth, and children of a ui
 * (ToolUiCatalog), and the render work of a screen (ToolUiCatalog.MAX_RENDER_WORK). ToolSpec.parse stays the check of the ui bytes
 * and the state bytes. MIN_ROWS is the kit's own limit: a list with room for fewer rows is a fault.
 */
const MAX_FIELDS = 40;
const MAX_SOURCE_BYTES = 65536;
const MAX_NODES = 512, MAX_DEPTH = 8, MAX_CHILDREN = 50, MAX_RENDER_WORK = 2048, MIN_ROWS = 20;
/** The nodes whose options cost render work (ToolUiCatalog.choiceTypes). */
const CHOICES = new Set(["select", "tabs", "singleChoice", "multiChoice", "segmented", "navigationBar", "navigationRail", "menu"]);
const SCALARS = new Set(["number", "string", "boolean"]);
/** The stored type of each kind of an entries field (parts.js FIELD_KINDS). A choice, a text, and a picture are strings. */
const STORED = { number: "number", count: "number", check: "boolean", switch: "boolean", time: "time", date: "date" };
/** Alphy declares at most 4 surfaces (ToolSpec). */
const MAX_SURFACES = 4;
/** The fields of the change of a value that an animate moves: its start and end values, its start and end times in ms of the host's clock, and its value now. */
const RUN = { type: "object", fields: { from: "number", to: "number", start: "number", end: "number", at: "number" } };
/** The limits of one reply by their unit in a fault. */
const UNITS = { commands: "commands", pairs: "coordinate pairs", chars: "text characters", bytes: "bytes", segments: "path segments", stops: "gradient stops" };

/**
 * The definition of the app, its language beside it, and the faults of what the emitter cannot build. app is the compiled
 * data that the source gives the run-time, and only what the run-time reads: the lines that it evaluates (the app line, the
 * formulas, the components, and the need line of each timer that it moves), the field of each state value, the kind of each time, date, list, and array, the fields of each
 * list, the count of rows of each shown list, each shown value that is a number, the names of the screen that differ from their wish,
 * the next row id, the values that an entries need shows for each row, and the field of the changes of animate with the key of each value (anim).
 * Faults give no definition. With a definition, owners gives the need or the line of each field in the order of the fields, and objects
 * gives the need of each object of inner values: change.js reads both for the next state of a change.
 */
function emit(file) {
  if (!runtime.text) throw new Error("emit.js has no run-time text: kit/build.js gives it");
  const kit = new App(file), { title, lang } = header(kit), faults = [], stateSchema = {}, owners = new Map(), needObjects = new Map();
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
    .map((entry) => [entry.key, (entry.tree.named.fields?.entries ?? [["name", { k: "lit", v: "text" }]]).map(([field, kind]) => [field, own(STORED, own(FIELD_KINDS, kind.v)) ?? "string"])]));
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
    app.lists[root] = [...kinds].map(([field, kind]) => [field, rowName("$" + field, stable("$" + field, field)), kind]);
    app.kinds[root] = "list";
    return { type: "list", maxItems: MAX_ROWS, fields: { id: "string", ...Object.fromEntries(app.lists[root].map(([, safe, kind]) => [safe, own(ALPHY, kind) ?? kind])) } };
  };
  /**
   * A need keeps its value in one field and each other plain value of its part in one object NAME_s. A list or an array of the
   * part is a field of its own, as the list of a need is: a List and a reply bind a top-level field.
   */
  const place = (root, need, owner, schema, wish = root.slice(need?.length + 1)) => {
    const inner = typeof schema === "string" && need !== undefined && root !== need && root.startsWith(need + "_");
    const field = inner ? name("need:" + need, objectWish(need)) : name("$/" + root, rootWish(root));
    if (!stateSchema[field]) owners.set(field, owner);
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
    owners.set(app.anim.field, first);
    if (moved.size > PER_OBJECT) faults.push(`${first}: animate moves ${moved.size} values of the app, and an object of Alphy holds at most ${PER_OBJECT} fields. Animate fewer values`);
  }
  // A model with a turn gets the pointer events of its surface: the drag that runs (drag.js IDLE) is one field of the state.
  const turned = file.of("component").find((entry) => entry.tree.k === "call" && entry.tree.name === "Model3d" && given(entry.tree).bind);
  if (turned) {
    app.drag = name("drag", "drag");
    stateSchema[app.drag] = { type: "object", fields: Object.fromEntries(Object.entries(IDLE).map(([key, value]) => [key, typeof value])) };
    owners.set(app.drag, label(turned.key));
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
  Object.keys(shown.viewSchemas).forEach((field, index) => owners.set(field, label(shown.viewOwners[index])));
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
  const over = [...owners.values()][MAX_FIELDS - Object.keys(shown.values).length];
  if (over) faults.push(`${over}: the app needs more than ${MAX_FIELDS} state fields from here on, and Alphy keeps at most ${MAX_FIELDS}. Use fewer needs`);
  // The theme and the column of the app are depths 0 and 1. A node that the kit adds (the text of a banner) belongs to its line.
  // The template of a row is one level below its list, and it counts once.
  // A line that the screen shows a second time gives each control in it its action id a second time, and ToolSpec.parse takes each
  // action id once. firstBy keeps the line that shows each line with an id first. The children of the root are the children of the app's column.
  const ids = (node) => [node.action, node.dismissAction, node.reorder?.action, ...(node.surfaces ?? []), ...(node.children ?? []).flatMap(ids), ...(node.rowTemplate ? ids(node.rowTemplate) : [])].filter(Boolean);
  const firstBy = new Map(), quiet = faults.length > 0;
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
    // Alphy refuses a container with no children (ToolUiCatalog). A component with a fault leaves no node, so an earlier fault keeps this one quiet.
    if (node.children && !children.length && !quiet) faults.push(`${where}: this container has no children, and Alphy keeps 1 to ${MAX_CHILDREN}. Put a component into it, or remove it`);
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
  const source = sourceOf(app), size = utf8(source);
  if (size > MAX_SOURCE_BYTES) return { definition: null, language, faults: [`app: the source is ${size} bytes, and Alphy keeps at most ${MAX_SOURCE_BYTES} bytes. Use shorter texts and fewer lines`], app };
  // The alert of the host's countdown asks for its capabilities, and Alphy refuses a timer whose app does not declare them.
  const capabilities = file.of("component").some((entry) => entry.tree.k === "call" && entry.tree.name === "Timer") ? [...ALERT_CAPABILITIES] : [];
  const definition = { formatVersion: 3, title, description: "", capabilities, ...(surfaces.length && { surfaces }), stateSchema, initialState: stateOf(kit, app, shown), ui: shown.ui, source };
  return { definition, language, faults: [], app, owners, objects: needObjects };
}


return { runtime, sourceOf, dataOf, rootWish, objectWish, MAX_FIELDS, MAX_SOURCE_BYTES, emit };
})();
// change.js
const { BLOCKS, MISMATCH, isRecord, nextState } = (() => {
// The next state of a changed app (loop plan, task 2): a changed app file and the stored app of the person give the definition and the
// state of the changed app. compileAppFile of compile.js calls nextState when it gets a previous.









/** The text of each fault of a change that a saved value of the person blocks (task 2). Such a fault sets blockedBySavedData of the result. */
const BLOCKS = "the saved data blocks this change";
/** The text of the one fault of a stored state that the previous app file does not give (task 2). It sets savedDataMismatch of the result. */
const MISMATCH = "the saved data does not match the app file";

/** The word of each kind of a stored value in a fault of a change. */
const WORDS = { number: "number", string: "text", boolean: "true or false value", time: "time", date: "date", list: "list of rows", record: "record",
  "array:number": "list of numbers", "array:string": "list of texts", "array:boolean": "list of true or false values" };
/** A record: an object that is not null and not an array. */
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
/** The blank value of a kept schema: what the kit gives an Alphy type with nothing stored (cast), an empty list, or a record of blanks. */
const blankValue = (schema) => (!isRecord(schema) ? cast(schema, undefined) : schema.type === "object"
  ? Object.fromEntries(Object.entries(schema.fields).map(([key, inner]) => [key, blankValue(inner)])) : []);
/** The result of faults with BLOCKS: each fault of nextState after the check of the stored app is one. */
const blocked = (faults) => ({ faults, definition: null, state: null, blockedBySavedData: true });

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
 * 5. Each stored field that the change does not name stays with its schema (app.keep). Its value stays when it is data of the person: it differs from
 *    its start value of the previous app file, or a change before kept it. A value that the app computed, and a value at its start, is blank (rule 3b).
 *    The start state of the definition gives each kept field the blank value of its schema, so only the state holds a kept value.
 *    A kept root that comes back with its blank value takes the start value of the change (rule 3b).
 * 6. The values that the app computes come from the changed app. A run of animate goes on by its root, and a drag starts again.
 * 7. The inner values of a need (NAME_s) stay when the need line is the same text and 3c does not apply, else they start again.
 */
function nextState(file, { app, definition, owners, objects }, previous, build) {
  const mismatch = { faults: [`kit: ${MISMATCH}`], definition: null, state: null, savedDataMismatch: true };
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
    // also of another kind. For a kept root that start is its blank (rule 5). Rule 3c: a list that gets other start rows so moves the rows that inner values point into.
    if (canonical(value) === canonical(back ? blankValue(schemas[from]) : oldStart[from])) {
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
  if (faults.length) return blocked(faults);
  // Rule 3b for a kept field: a value that the app computed, such as d or the view of a sorted list, and a value that still equals its start
  // of the previous app file, is no data of the person, so it is blank. A field that an earlier change kept stays as it is stored.
  const kept = Object.fromEntries(keep.map((field) => [field, older.has(field) || (person.has(field) && canonical(stored[field]) !== canonical(oldStart[field]))
    ? stored[field] : blankValue(schemas[field])]));
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
  if (stop !== null || kit.tripped) return blocked([`app: ${BLOCKS}: the app stops on the saved data: ${stop ?? kit.told ?? "it needs more work than Alphy gives an app"}`]);
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
  if (faults.length) return blocked(faults);
  // The start state holds a blank for each kept field, never a value of the person: the host finds the data of the person as the state that differs from it.
  const blanks = Object.fromEntries(keep.map((field) => [field, blankValue(schemas[field])]));
  return { faults: [], notes, definition: keep.length ? { ...definition, stateSchema: schema, initialState: { ...start, ...blanks }, source } : definition, state };
}

return { BLOCKS, MISMATCH, isRecord, nextState };
})();
// form-base.js
const { TEMPLATE_HEAD, BUDGET, COLOR_NAMES, namedColor, toneColor, WIDTH, HEIGHT, isText, numberOf, nodesIn, INSIDE, textFault, FEW_OPTIONS, optionCap, optionFault, labelsFault, optionsFault, SENTENCE_END, cut, cutLong, headFault, textCardFault, namedColors, colored, colorFault } = (() => {
// What each template form uses (docs/superpowers/plans/2026-11-22-one-scene.md, decision 1): the first line, the budget, the drawing size,
// the faults of a text, of the first line, and of a card with a text, the cut of a long text, and the colors of a shape. Each form has a
// module of its own (forms.js), and template.js reads, merges, expands, and builds a template file with them.






/** The first line of a template file, with the template word as group 1: the explain family, then the data family (form-data.js). */
const TEMPLATE_HEAD = /^\s*(scene|items|cells|compare|table|chart|nodata)\s*\(/;
/**
 * The content budget of each template: texts in characters, lists in items. A text inside the picture (a shape text, a sign) has no fault up to
 * floor(1.25 × cap), because it cannot change the height of the screen. A card text or an item text past its cap is cut after its last whole
 * sentence inside the cap (cut). Each other text past its cap, a long text with no sentence end inside its cap, and a list past its cap are faults.
 * A narration line (say) is cut the same way, and one with no sentence end inside its cap is left out with no fault (template.js).
 */
const BUDGET = { title: 30, control: 28, cardTitle: 32, cardText: 140, option: 12, fewOption: 16, sign: 14, text: 28, options: 6, items: 6, shapes: 20, cells: 100, say: 110 };
/** The common color names that the kit takes with no call, by their small letters: the template check had one repair for a color name. */
const COLOR_NAMES = { red: "#dc2626", orange: "#ea580c", yellow: "#facc15", green: "#16a34a", blue: "#2563eb", purple: "#9333ea", violet: "#7c3aed", pink: "#db2777",
  brown: "#a16207", gray: "#64748b", grey: "#64748b", cyan: "#0891b2", teal: "#0d9488", indigo: "#4f46e5", amber: "#d97706", graphite: "#4b5563", black: "ink", white: "surface" };
const PALETTES = ["teal", "green", "blue", "amber", "orange", "red", "pink", "purple", "indigo", "brown", "graphite"];
const NAMED_COLOR = new RegExp(`^(${Object.keys(COLOR_NAMES).join("|")})([+-][123])?(?:@(\\d{1,2}|100))?$`);
/**
 * A color name (COLOR_NAMES, each name of palette= too) in any case, with a tone step and an alpha as for a role, such as "red+2", "orange-1",
 * or "red@40", as a color of the kit, else null (fix list 3, H1). A name is a fixed hex, so "red" is red in each palette and in both themes, and
 * the kit mixes its tone as for the hex roles (draw.js toneColor). black and white stay the roles ink and surface.
 */
function namedColor(text) {
  const [, name, step = "", alpha = "100"] = NAMED_COLOR.exec(String(text).toLowerCase()) ?? [];
  if (!name) return null;
  return toneColor(COLOR_NAMES[name], step, Number(alpha));
}
/**
 * A color with a tone step ("+1" to "+3", "-1" to "-3", or "") and an alpha in percent: a hex color mixed as draw.js colorOf mixes a tone of a
 * hex role, and a role as its tone text, such as "ink+1@40". It reuses colorOf with a role of that color, so the run-time keeps one mix.
 */
const toneColor = (base, step, alpha) => colorOf(`ink${step}@${alpha}`, { ink: base });
/** The place of the color among the arguments of a shape kind (kinds.js), or undefined for a library line, which names its color. */
const colorAt = (name) => (Object.hasOwn(KINDS, name) ? KINDS[name].args.length : undefined);
/**
 * The drawing of each template, fixed from the first line: the picture has its final size before its first part. It is square (stage 6, L3):
 * on a phone the picture fills the width and grows into the empty zone under the app, and the fold keeps it in its column.
 */
const WIDTH = 300;
const HEIGHT = 300;

const isText = (node) => node?.k === "lit" && typeof node.v === "string";
/** A number in the line, also a negative one, else undefined. */
const numberOf = (node) => (node?.k === "lit" && typeof node.v === "number" ? node.v : node?.k === "un" && node.op === "-" && typeof node.arg?.v === "number" ? -node.arg.v : undefined);
/** Each node of a tree for which test is true. */
const nodesIn = (node, test) => (!node || typeof node !== "object" ? [] : [...(test(node) ? [node] : []), ...Object.values(node).flatMap((inner) => nodesIn(inner, test))]);

/** The factor of the cap of a text inside the picture (BUDGET). */
const INSIDE = 1.25;
/** The fault of a text of a line: a text in quotes, or a formula of texts where formula is true, with no text past floor(factor × cap). */
function textFault(what, node, cap, formula = false, factor = 1) {
  if (!node || (!formula && !isText(node))) return `${what} is a text in quotes`;
  if (isText(node) && !node.v.trim()) return `${what} is empty`;
  const long = nodesIn(node, isText).find((text) => [...text.v].length > Math.floor(cap * factor));
  return long ? `${what} has ${[...long.v].length} characters, and its cap is ${cap}. Write it shorter` : null;
}
/** The most options of a choice whose options have the cap BUDGET.fewOption, else BUDGET.option (optionFault). */
const FEW_OPTIONS = 4;
/**
 * The cap of each option of a choice, or each step of a steps control, of count items (fix list 3, H3, and fix list 5, L2). A steps control is
 * a choice on the stage. A choice of 2 to 4 has the cap BUDGET.fewOption: by the widths of the labels, two options fit one row on the phone,
 * and three or four wrap into two rows of whole cells (the fit gate holds scene-caps4 and steps-caps4). 5 or 6 keep BUDGET.option. A label
 * is never cut: a line of the scene compares $/v with it.
 */
const optionCap = (count) => (count <= FEW_OPTIONS ? BUDGET.fewOption : BUDGET.option);
/** The hint of a fault of labels from BUDGET.option to BUDGET.fewOption long in a list of more than FEW_OPTIONS. */
const fewer = (count, lengths, word) => (count > FEW_OPTIONS && lengths.every((length) => length <= BUDGET.fewOption) ? `, or keep at most ${FEW_OPTIONS} ${word}s: their cap is ${BUDGET.fewOption}` : "");
/** The fault of one label of a list of count labels, such as an item label of items, which is an option of its choice. */
function optionFault(what, node, count, word = "option") {
  const fault = textFault(what, node, optionCap(count));
  return fault && isText(node) && [...node.v].length > optionCap(count) ? fault + fewer(count, [[...node.v].length], word) : fault;
}
/** "1", "1 and 2", or "1, 2, and 3". */
const listed = (values) => (values.length < 3 ? values.join(" and ") : `${values.slice(0, -1).join(", ")}, and ${values.at(-1)}`);
/**
 * One fault for the labels of one line, such as the options of a choice (fix list 5, L1): it names each label over its cap with its number and
 * its length, so one repair can correct each of them. A label that is no text in quotes, or an empty one, is the fault. Null with no fault.
 */
function labelsFault(word, labels, cap, hint = () => "") {
  const wrong = labels.map((label, index) => (isText(label) && label.v.trim() ? null : textFault(`${word} ${index + 1}`, label, cap))).find(Boolean);
  if (wrong) return wrong;
  const over = labels.map((label, index) => [index + 1, [...label.v].length]).filter(([, length]) => length > cap), lengths = over.map(([, length]) => length);
  if (!over.length) return null;
  const tail = hint(lengths);
  if (over.length === 1) return `${word} ${over[0][0]} has ${over[0][1]} characters, and its cap is ${cap}. Write it shorter${tail}`;
  const same = lengths.every((length) => length === lengths[0]);
  return `${word}s ${listed(over.map(([number, length]) => (same ? number : `${number} (${length} characters)`)))} ${same ? `have ${lengths[0]} characters, and their cap is` : "are over their cap of"} ${cap}. Write each one shorter${tail}`;
}
/** The fault of the options of a choice or the steps of a steps control: each label over the cap of their number (optionCap), in one text. */
const optionsFault = (word, options) => labelsFault(word, options, optionCap(options.length), (lengths) => fewer(options.length, lengths, word));

/** A sentence end: . ! ? or …, or 。！？ of Chinese and Japanese, with the closing quote marks and brackets after it. */
const SENTENCE_END = /[.!?…。！？]["'»”’)\]}」』）]*$/u;
/**
 * A text past its cap cut after its last whole sentence inside the cap, or the text itself when it fits or no sentence ends inside the cap.
 * A sentence end counts only before a space, so 3.5 ends no sentence, except 。！？, which no space follows. The space after the cut goes.
 */
function cut(text, cap) {
  const chars = [...text];
  for (let end = chars.length > cap ? cap : 0; end > 0; end--) {
    const head = chars.slice(0, end).join(""), mark = SENTENCE_END.exec(head)?.[0];
    if (mark && (/\s/u.test(chars[end]) || /[。！？]/u.test(mark))) return head;
  }
  return text;
}
/** The node with each text in it given again by map. */
const textsMapped = (node, map) => (!node || typeof node !== "object" ? node : isText(node) ? map(node)
  : Array.isArray(node) ? node.map((inner) => textsMapped(inner, map)) : Object.fromEntries(Object.entries(node).map(([key, inner]) => [key, textsMapped(inner, map)])));
/**
 * The tree of a card line of scene and cells, or of an item line, with each text of its card text or item text cut to the cap (cut). Each
 * text of a formula is cut by itself. The same tree when no text changes.
 */
function cutLong(tree, name, word) {
  const at = name === "card" ? (word === "items" ? -1 : 1) : word === "items" ? 2 : -1, long = tree.k === "call" && tree.name === (name === "card" ? "card" : "item") ? tree.args[at] : undefined;
  const short = long && textsMapped(long, (node) => ({ ...node, v: cut(node.v, BUDGET.cardText) }));
  return !long || show(short) === show(long) ? tree : { ...tree, args: tree.args.map((arg, index) => (index === at ? short : arg)) };
}

/** The fault of the first line. extra names a named argument of the form beyond lang= and palette=, such as kind= of chart, which the form checks. */
function headFault(tree, word, extra) {
  const [title, ...more] = tree.args ?? [], { lang, palette, [extra]: _own, ...other } = tree.named ?? {};
  if (tree.k !== "call" || more.length || Object.keys(other).length) return `the first line is ${word}(TITLE, lang=CODE, palette=NAME${extra ? `, ${extra}=…` : ""}), and palette= is optional`;
  if (!(isText(lang) && /^[a-z]{2}$/.test(lang.v))) return 'lang= is the two-letter code of the language of the request, such as "en"';
  if (palette && !(isText(palette) && PALETTES.includes(palette.v))) return `palette= is one of ${PALETTES.join(", ")}, and ${show(palette)} is not one`;
  return textFault("the title", title, BUDGET.title);
}

/** The fault of the card line card(TITLE, TEXT) of the template word: the card of scene and of cells. */
function textCardFault(tree, word) {
  if (tree.k !== "call" || tree.name !== "card" || tree.args.length !== 2 || Object.keys(tree.named).length) return `the card of ${word} is card(TITLE, TEXT)`;
  return textFault("the card title", tree.args[0], BUDGET.cardTitle, true) ?? textFault("the card text", tree.args[1], BUDGET.cardText, true);
}

/** The values of a color: the color, or each value of a choice (test ? a : b, or if(test, a, b)), as choices in parts.js reads them. map gives each value again. */
const colorValues = (node, map) => node?.k === "if" ? { ...node, yes: colorValues(node.yes, map), no: colorValues(node.no, map) }
  : node?.k === "call" && node.name === "if" && node.args.length === 3 ? { ...node, args: [node.args[0], ...node.args.slice(1).map((value) => colorValues(value, map))] } : map(node);
/** A color node with each color name (namedColor) of its values as the kit's color: a text that a choice compares stays a text. */
const namedColors = (node) => colorValues(node, (one) => (isText(one) && namedColor(one.v) ? { k: "lit", v: namedColor(one.v) } : one));
/** A shape with each color name of its color and of gradient= as the kit's color (namedColors). A text of a text shape stays a text. */
function colored(tree) {
  const at = colorAt(tree.name), named = tree.named.gradient ? { ...tree.named, gradient: namedColors(tree.named.gradient) } : tree.named;
  return { ...tree, args: tree.args.map((arg, index) => (index === at ? namedColors(arg) : arg)), named };
}
const colorFault = (tree) => {
  const values = [];
  if (colorAt(tree.name) !== undefined) colorValues(tree.args[colorAt(tree.name)], (node) => values.push(node));
  if (tree.named.gradient) colorValues(tree.named.gradient, (node) => values.push(node));
  const wrong = values.find((node) => isText(node) && !isColor(node.v));
  return wrong ? `the color ${show(wrong)} is not a color of the kit. Use one of ${Object.keys(TONES).join(", ")}, a tone of one such as "water+1" or "accent-1@50", one of ${Object.keys(COLOR_NAMES).join(", ")}, or a hex such as "#336699"` : null;
};

return { TEMPLATE_HEAD, BUDGET, COLOR_NAMES, namedColor, toneColor, WIDTH, HEIGHT, isText, numberOf, nodesIn, INSIDE, textFault, FEW_OPTIONS, optionCap, optionFault, labelsFault, optionsFault, SENTENCE_END, cut, cutLong, headFault, textCardFault, namedColors, colored, colorFault };
})();
// elements.js
const { toneOf, ELEMENTS } = (() => {
// The registry of the generic elements (stage 6, L4; spec 12.5): things that a rule draws from numbers, for any subject. A template line places
// one with use(NAME, X, Y, SIZE, [NUMBERS]) (library.js). An element is one entry here: its group, its numbers with their defaults, its color,
// its prompt text, and its draw rule. A draw rule gives shape texts in a box of 100 × 100 units around 0, 0: library.js puts them into one
// group at X, Y, scaled to SIZE, so the element costs one shape of the scene. A number may be a formula of $/v.
//
// The look (stage 6 look target): a ball, a block, and a container have volume (a gradient between two tone steps and a soft highlight),
// and a bar, an axis, and a grid are flat (a soft fill with a thin outline in a darker tone of the role).



/** A number text as a number, or null for a formula. */
const num = (text) => (/^-?\d+(?:\.\d+)?$/.test(String(text)) ? Number(text) : null);
/** A tone step of a color text: "accent" with step 1 is "accent+1", and a hex is mixed as the kit mixes a tone of a hex role. A formula stays as it is. */
function toneOf(color, step = 0, alpha) {
  let name;
  try { name = JSON.parse(color); } catch { return color; }
  if (typeof name === "string" && /^#[0-9a-fA-F]{6}$/.test(name)) return JSON.stringify(toneColor(name, step > 0 ? `+${step}` : step < 0 ? String(step) : "", alpha ?? 100));
  if (typeof name !== "string" || !/^[a-z]+$/.test(name)) return color;
  return JSON.stringify(`${name}${step > 0 ? `+${step}` : step < 0 ? step : ""}${alpha !== undefined ? `@${alpha}` : ""}`);
}
const T = toneOf;
/** The shapes i = 0 to count - 1: all of them for a number, else max shapes, each one visible while i < count. */
const upTo = (count, max, shape) => {
  const known = num(count);
  return known !== null ? Array.from({ length: Math.max(0, Math.min(max, Math.round(known))) }, (_, i) => shape(i, ""))
    : Array.from({ length: max }, (_, i) => shape(i, `, visible=${count} > ${i}`));
};
/** A ball with volume: a radial gradient from a light tone to a dark tone, and a soft highlight. */
const ball = (x, y, r, c, more = "") => [`circle(${x}, ${y}, ${r}, ${T(c, 1)}, gradient=${T(c, -1)}, radial=1${more})`,
  `circle(${x} - ${r} * 0.34, ${y} - ${r} * 0.38, ${r} * 0.3, ${T(c, 3, 80)}, gradient=${T(c, 3, 0)}, radial=0${more})`];
/** A flat box: a soft fill and a thin outline in a darker tone of its role. */
const flat = (x, y, w, h, c, step = 2, more = "") => [`rect(${x}, ${y}, ${w}, ${h}, ${T(c, step)}${more})`, `rect(${x}, ${y}, ${w}, ${h}, ${T(c, -1)}, stroke=true${more})`];
/** A text that keeps its size in the picture: size px at each size of the element (u is 100 / SIZE). */
const label = (x, y, value, color, size, u, more = "") => `text(${x}, ${y}, ${value}, ${color}, ${u} * ${size}${more})`;

/**
 * The generic elements by name: group is the group of spec 12.5, numbers names the numbers in order with defaults, color is the role when the
 * line gives none, prompt is the line of the template prompt, turn is the index of a number that turns the whole element, label is the point
 * of label= in units ([x, y, align]; by default under it), and text is true when label= is the content of the element. draw({n, c, u}) gives
 * the shape texts: n are the number texts, c the color text, and u the text of 100 / SIZE. caps names the largest value of a number that the
 * draw rule shows: a plain number above it is a fault of the line, and a formula above it draws the cap.
 */
const ELEMENTS = {
  straight_arrow: { group: "arrows", numbers: ["angle"], defaults: [0], color: "accent", turn: 0, prompt: "a solid arrow along angle",
    draw: ({ c }) => [`arrow(-46, 0, 46, 0, ${c}, 9)`] },
  curved_arrow: { group: "arrows", numbers: ["sweep"], defaults: [120], color: "accent", prompt: "an arrow along a part of a circle, sweep degrees clockwise",
    // The head is a solid arrow along the end of the arc, so the body is a third of the head (F4).
    draw: ({ n: [s], c }) => {
      const [cos, sin] = [`cos(90 - ${s} / 2)`, `sin(90 - ${s} / 2)`];
      return [`arc(0, 0, 40, 90 + ${s} / 2, 0 - ${s}, ${c}, 6)`,
        `arrow(40 * ${cos} - 2 * ${sin}, 0 - 40 * ${sin} - 2 * ${cos}, 40 * ${cos} + 20 * ${sin}, 0 - 40 * ${sin} + 20 * ${cos}, ${c}, 6)`];
    } },
  two_way_arrow: { group: "arrows", numbers: ["angle"], defaults: [0], color: "accent", turn: 0, prompt: "an arrow with a head at each end",
    // The two bodies overlap, so the rounded tails do not show in the middle.
    draw: ({ c }) => [`arrow(-12, 0, 50, 0, ${c}, 8)`, `arrow(12, 0, -50, 0, ${c}, 8)`] },
  flow: { group: "arrows", numbers: ["angle", "shift"], defaults: [0, 0], color: "accent", turn: 0, prompt: "marks that flow along a line; shift from 0 to 1 moves them",
    draw: ({ n: [, s], c }) => [`line(-50, 0, 50, 0, ${T(c, 0, 35)}, 2)`,
      ...[0, 1, 2, 3, 4].map((i) => `polygon([${-44 + 20 * i} + ${s} * 20 + 6, 0, ${-44 + 20 * i} + ${s} * 20 - 4, -7, ${-44 + 20 * i} + ${s} * 20 - 4, 7], ${c})`)] },
  link: { group: "arrows", numbers: [], defaults: [], color: "ink", label: [0, -10, "center"], prompt: "a line between two things, with label= over its middle",
    draw: ({ c }) => [`line(-46, 0, 46, 0, ${T(c, 0, 70)}, 2)`, `circle(-46, 0, 4, ${c})`, `circle(46, 0, 4, ${c})`] },

  leader_label: { group: "marks", numbers: [], defaults: [], color: "ink", label: [46, -34, "start"], prompt: "a point with a thin line up and right to label=",
    draw: ({ c }) => [`circle(-40, 30, 4, ${c})`, `line(-40, 30, 40, -34, ${T(c, 0, 55)}, 1.5)`] },
  brace: { group: "marks", numbers: ["angle"], defaults: [0], color: "ink", turn: 0, label: [0, -18, "center"], prompt: "a curly brace over a span, with label= above it",
    draw: ({ c }) => [`path([-50, 10, -46, 2, -8, 2, 0, -8, 8, 2, 46, 2, 50, 10], ${c}, 3, smooth=true)`] },
  ring: { group: "marks", numbers: [], defaults: [], color: "accent", prompt: "a ring around a part, to point at it",
    draw: ({ c }) => [`arc(0, 0, 46, 0, 360, ${c}, 4)`] },
  pulse: { group: "marks", numbers: ["phase"], defaults: [0], color: "accent", prompt: "rings that spread from a point; phase from 0 to 1",
    draw: ({ n: [p], c }) => [`circle(0, 0, 8, ${c}, glow=true)`,
      ...[0, 1, 2].map((i) => `arc(0, 0, 12 + (${15 * i} + ${p} * 15) % 45, 0, 360, ${c}, 3, opacity=1 - (${15 * i} + ${p} * 15) % 45 / 50)`)] },
  check: { group: "marks", numbers: [], defaults: [], color: "success", prompt: "a check mark", draw: ({ c }) => [`path([-38, 0, -12, 26, 40, -30], ${c}, 13)`] },
  cross: { group: "marks", numbers: [], defaults: [], color: "error", prompt: "a cross", draw: ({ c }) => [`line(-32, -32, 32, 32, ${c}, 13)`, `line(-32, 32, 32, -32, ${c}, 13)`] },
  plus: { group: "marks", numbers: [], defaults: [], color: "ink", prompt: "a plus sign", draw: ({ c }) => [`rect(-40, -7, 80, 14, ${c}, 7)`, `rect(-7, -40, 14, 80, ${c}, 7)`] },
  minus: { group: "marks", numbers: [], defaults: [], color: "ink", prompt: "a minus sign", draw: ({ c }) => [`rect(-40, -7, 80, 14, ${c}, 7)`] },
  equals: { group: "marks", numbers: [], defaults: [], color: "ink", prompt: "an equals sign", draw: ({ c }) => [`rect(-40, -20, 80, 13, ${c}, 6)`, `rect(-40, 7, 80, 13, ${c}, 6)`] },
  question: { group: "marks", numbers: [], defaults: [], color: "accent", prompt: "a question mark in a soft disk",
    draw: ({ c }) => [`circle(0, 0, 48, ${T(c, 2, 45)})`, `text(0, 4, "?", ${c}, 72)`] },

  formula: { group: "formulas", numbers: [], defaults: [], color: "ink", text: true,
    prompt: "the formula of label=, such as \"a^2 + b^2 = c^2\", \"H_2O\", \"frac{m}{V}\", or \"sqrt{x}\": ^ raises, _ lowers, frac{a}{b} is a fraction, sqrt{x} a root",
    draw: ({ c, u, text }) => formulaShapes(text, c, u) },

  axis: { group: "measures", numbers: ["min", "max"], defaults: [0, 10], color: "ink", label: [0, 30, "center"], prompt: "a number axis with 6 ticks from min to max",
    draw: ({ n: [a, b], c, u }) => [`line(-50, 0, 50, 0, ${c}, 2)`, `polygon([52, 0, 44, -5, 44, 5], ${c})`,
      ...[0, 1, 2, 3, 4, 5].flatMap((i) => [`line(${-45 + 17 * i}, -4, ${-45 + 17 * i}, 4, ${c}, 1.5)`, label(-45 + 17 * i, 14, `text(round(${a} + (${b} - ${a}) * ${i / 5}, 1))`, T(c, 0, 75), 11, u)])] },
  grid: { group: "measures", numbers: ["columns", "rows"], defaults: [5, 5], caps: { columns: 10, rows: 10 }, color: "ink", prompt: "a flat grid of columns × rows",
    draw: ({ n: [x, y], c }) => [`rect(-50, -50, 100, 100, ${T(c, 0, 4)})`,
      ...upTo(`${x} + 1`, 11, (i, v) => `line(-50 + 100 * ${i} / ${x}, -50, -50 + 100 * ${i} / ${x}, 50, ${T(c, 0, 30)}, 1${v})`),
      ...upTo(`${y} + 1`, 11, (i, v) => `line(-50, -50 + 100 * ${i} / ${y}, 50, -50 + 100 * ${i} / ${y}, ${T(c, 0, 30)}, 1${v})`)] },
  number_line: { group: "measures", numbers: ["min", "max", "value"], defaults: [0, 10, 3], color: "accent", prompt: "a number line from min to max with a point at value",
    draw: ({ n: [a, b, v], c, u }) => [`line(-48, 0, 48, 0, "ink", 2)`, ...[0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10].map((i) => `line(${-45 + 9 * i}, -4, ${-45 + 9 * i}, 4, "ink", 1.2)`),
      label(-45, 14, `text(${a})`, '"ink@75"', 11, u), label(45, 14, `text(${b})`, '"ink@75"', 11, u),
      ...ball(`-45 + 90 * (${v} - ${a}) / (${b} - ${a})`, 0, 6, c), label(`-45 + 90 * (${v} - ${a}) / (${b} - ${a})`, -15, `text(${v})`, c, 12, u)] },
  ruler: { group: "measures", numbers: ["length"], defaults: [10], caps: { length: 20 }, color: "wood", prompt: "a ruler with marks from 0 to length",
    draw: ({ n: [l], c, u }) => [...flat(-50, -14, 100, 28, c, 2),
      ...upTo(`${l} + 1`, 21, (i, v) => `line(-46 + 92 * ${i} / ${l}, -14, -46 + 92 * ${i} / ${l}, -4, ${T(c, -2)}, 1.2${v})`),
      label(-46, 6, '"0"', T(c, -2), 10, u), label(46, 6, `text(${l})`, T(c, -2), 10, u)] },
  angle_mark: { group: "measures", numbers: ["degrees"], defaults: [60], color: "accent", prompt: "two rays from a point with the angle between them marked",
    draw: ({ n: [d], c, u }) => [`line(-40, 30, 50, 30, "ink", 2)`, `line(-40, 30, -40 + 80 * cos(${d}), 30 - 80 * sin(${d}), "ink", 2)`,
      `arc(-40, 30, 24, 0, ${d}, ${c}, 3)`, label(`-40 + 40 * cos(${d} / 2)`, `30 - 40 * sin(${d} / 2)`, `text(${d}) + "°"`, c, 12, u)] },
  dial: { group: "measures", numbers: ["value"], defaults: [0.5], color: "accent", prompt: "a dial with a needle; value from 0 to 1",
    draw: ({ n: [v], c }) => [`arc(0, 20, 44, 180, -180, ${T(c, 2, 50)}, 10)`, `arc(0, 20, 44, 180, 0 - 180 * ${v}, ${c}, 10)`,
      `line(0, 20, 34 * cos(180 - 180 * ${v}), 20 - 34 * sin(180 - 180 * ${v}), "ink", 3)`, `circle(0, 20, 5, "ink")`] },
  level_bar: { group: "measures", numbers: ["value"], defaults: [0.6], color: "accent", prompt: "an upright bar filled to value from 0 to 1",
    draw: ({ n: [v], c }) => [`rect(-14, -48, 28, 96, ${T(c, 2, 45)}, 6)`, `rect(-14, 48 - 96 * ${v}, 28, 96 * ${v}, ${c}, 6)`, `rect(-14, -48, 28, 96, ${T(c, -1)}, stroke=true)`] },
  thermometer: { group: "measures", numbers: ["value"], defaults: [0.5], color: "error", prompt: "a thermometer filled to value from 0 to 1",
    draw: ({ n: [v], c }) => [`rect(-9, -48, 18, 84, "surface", 9)`, `rect(-9, -48, 18, 84, "ink@40", stroke=true)`, `rect(-4.5, 30 - 70 * ${v}, 9, 70 * ${v} + 8, ${c}, 4)`,
      ...[0, 1, 2, 3, 4].map((i) => `line(10, ${-36 + 16 * i}, 17, ${-36 + 16 * i}, "ink@55", 1.5)`), ...ball(0, 36, 13, c)] },
  clock: { group: "measures", numbers: ["hours", "minutes"], defaults: [3, 0], color: "ink", prompt: "a clock face that shows hours and minutes",
    draw: ({ n: [h, m], c }) => [`circle(0, 0, 48, "surface")`, `circle(0, 0, 48, ${T(c, 0, 60)}, stroke=true)`,
      ...Array.from({ length: 12 }, (_, i) => `line(${fixed(40 * Math.cos((i * Math.PI) / 6))}, ${fixed(40 * Math.sin((i * Math.PI) / 6))}, ${fixed(46 * Math.cos((i * Math.PI) / 6))}, ${fixed(46 * Math.sin((i * Math.PI) / 6))}, ${c}, ${i % 3 ? 1.5 : 3})`),
      `line(0, 0, 24 * cos(90 - 30 * (${h} + ${m} / 60)), 0 - 24 * sin(90 - 30 * (${h} + ${m} / 60)), ${c}, 5)`,
      `line(0, 0, 36 * cos(90 - 6 * ${m}), 0 - 36 * sin(90 - 6 * ${m}), "accent", 3)`, `circle(0, 0, 4, ${c})`] },
  time_line: { group: "measures", numbers: ["count", "marked"], defaults: [5, 1], caps: { count: 8 }, color: "accent", prompt: "a time line of count points with the point marked (1 is the first)",
    draw: ({ n: [k, m], c }) => [`line(-50, 0, 50, 0, "ink@60", 2)`,
      ...upTo(k, 8, (i, v) => `circle(-44 + 88 * ${i} / max(1, ${k} - 1), 0, ${m} == ${i + 1} ? 8 : 5, ${m} == ${i + 1} ? ${c} : "ink@70"${v})`)] },

  bar: { group: "quantities", numbers: ["value"], defaults: [0.6], color: "accent", prompt: "a flat bar of height value from 0 to 1",
    draw: ({ n: [v], c }) => flat(-22, `48 - 96 * ${v}`, 44, `96 * ${v}`, c, 1) },
  stacked_bar: { group: "quantities", numbers: ["a", "b", "c"], defaults: [1, 1, 1], color: "accent", prompt: "a bar of three stacked shares a, b, c",
    draw: ({ n: [a, b, d], c }) => {
      const sum = `(${a} + ${b} + ${d})`;
      return [...flat(-22, `48 - 96 * ${a} / ${sum}`, 44, `96 * ${a} / ${sum}`, c, -1), ...flat(-22, `48 - 96 * (${a} + ${b}) / ${sum}`, 44, `96 * ${b} / ${sum}`, c, 0),
        ...flat(-22, -48, 44, `96 * ${d} / ${sum}`, c, 2)];
    } },
  sector: { group: "quantities", numbers: ["share"], defaults: [0.25], color: "accent", prompt: "a circle with a sector of share from 0 to 1",
    draw: ({ n: [s], c }) => [`circle(0, 0, 46, ${T(c, 2, 45)})`,
      `polygon([0, 0, ${Array.from({ length: 13 }, (_, i) => `46 * cos(90 - 360 * ${s} * ${i / 12}), 0 - 46 * sin(90 - 360 * ${s} * ${i / 12})`).join(", ")}], ${c})`,
      `circle(0, 0, 46, ${T(c, -1)}, stroke=true)`] },
  donut: { group: "quantities", numbers: ["share"], defaults: [0.7], color: "accent", prompt: "a ring filled to share from 0 to 1, with the percent in it",
    draw: ({ n: [s], c, u }) => [`arc(0, 0, 38, 0, 360, ${T(c, 2, 45)}, 14)`, `arc(0, 0, 38, 90, 0 - 360 * ${s}, ${c}, 14)`, label(0, 0, `text(round(${s} * 100)) + "%"`, '"ink"', 18, u)] },
  fraction_bar: { group: "quantities", numbers: ["parts", "filled"], defaults: [4, 1], caps: { parts: 12 }, color: "accent", prompt: "a strip of equal parts with filled parts in color",
    draw: ({ n: [p, f], c }) => upTo(p, 12, (i, v) => [`rect(-50 + 100 * ${i} / ${p}, -14, 100 / ${p}, 28, ${f} > ${i} ? ${T(c, 0)} : ${T(c, 2, 35)}${v})`,
      `rect(-50 + 100 * ${i} / ${p}, -14, 100 / ${p}, 28, ${T(c, -1)}, stroke=true${v})`]).flat() },
  dots: { group: "quantities", numbers: ["count"], defaults: [5], caps: { count: 20 }, color: "accent", prompt: "a count of round dots, 5 in a row, up to 20",
    draw: ({ n: [k], c }) => upTo(k, 20, (i, v) => `circle(${-40 + 20 * (i % 5)}, ${-30 + 20 * Math.floor(i / 5)}, 8, ${T(c, 1)}, gradient=${T(c, -1)}, radial=1${v})`) },

  wave: { group: "motion", numbers: ["cycles", "amplitude", "shift"], defaults: [2, 1, 0], color: "accent", prompt: "a wave of cycles; amplitude from 0 to 1; shift in degrees moves it, such as $/v * 3.6 for a running wave",
    draw: ({ n: [k, a, s], c }) => [`line(-50, 0, 50, 0, "ink@30", 1)`,
      `path([${Array.from({ length: 25 }, (_, i) => `${-50 + (100 * i) / 24}, 0 - 40 * ${a} * sin(360 * ${k} * ${i / 24} - ${s})`).join(", ")}], ${c}, 4, smooth=true)`] },
  spring: { group: "motion", numbers: ["stretch"], defaults: [0.5], color: "ink", prompt: "a spring along x; stretch from 0 to 1",
    draw: ({ n: [s], c }) => [`path([${Array.from({ length: 22 }, (_, i) => `-50 + (40 + 60 * ${s}) * ${fixed(i / 21)}, ${i === 0 || i === 21 ? 0 : i % 2 ? -14 : 14}`).join(", ")}], ${c}, 3)`,
      `rect(-10 + 60 * ${s}, -18, 10, 36, ${T(c, 0, 70)}, 3)`] },
  gear: { group: "motion", numbers: ["teeth", "turn"], defaults: [10, 0], caps: { teeth: 16 }, color: "muted", turn: 1, prompt: "a gear of 6 to 16 teeth, turned by turn degrees",
    draw: ({ n: [t], c }) => {
      const teeth = Math.min(16, Math.max(6, Math.round(num(t) ?? 10))), points = Array.from({ length: 4 * teeth }, (_, i) => {
        const angle = (2 * Math.PI * (i + 0.5)) / (4 * teeth), r = i % 4 < 2 ? 47 : 37;
        return `${fixed(r * Math.cos(angle))}, ${fixed(r * Math.sin(angle))}`;
      });
      return [`polygon([${points.join(", ")}], ${T(c, 0)}, gradient=${T(c, -2)}, radial=1)`, `circle(0, 0, 12, "surface")`, `circle(0, 0, 12, ${T(c, -2)}, stroke=true)`];
    } },
  wheel: { group: "motion", numbers: ["turn"], defaults: [0], color: "ink", turn: 0, prompt: "a wheel with spokes, turned by turn degrees",
    draw: ({ c }) => [`circle(0, 0, 47, ${T(c, 1)}, gradient=${T(c, -1)}, radial=1)`, `circle(0, 0, 35, "surface")`,
      ...[0, 60, 120].map((a) => `line(${fixed(-34 * Math.cos((a * Math.PI) / 180))}, ${fixed(-34 * Math.sin((a * Math.PI) / 180))}, ${fixed(34 * Math.cos((a * Math.PI) / 180))}, ${fixed(34 * Math.sin((a * Math.PI) / 180))}, ${T(c, 0, 70)}, 3)`),
      `circle(0, 0, 7, ${c})`] },
  pendulum: { group: "motion", numbers: ["angle", "length"], defaults: [20, 1], color: "accent", prompt: "a pendulum turned by angle from straight down; length of its string from 0.3 to 1",
    draw: ({ n: [a, l], c }) => [`rect(-26, -50, 52, 6, "ink@70", 3)`,
      `group(0, -44, 1, [line(0, 0, 0, 74 * ${l}, "ink", 2), ${ball(0, `(76 * ${l})`, 13, c).join(", ")}], turn=${a})`] },
  force_arrow: { group: "motion", numbers: ["angle", "length"], defaults: [0, 1], color: "accent", turn: 0, label: [0, -22, "center"], prompt: "a solid force arrow along angle; length from 0 to 1; label= for its value",
    draw: ({ n: [, l], c }) => [`arrow(-46, 0, -46 + 92 * max(0.2, ${l}), 0, ${c}, 11)`] },

  box: { group: "containers", numbers: [], defaults: [], color: "wood", prompt: "a block with volume",
    draw: ({ c }) => [`path([-44, 40, -10, 47, 44, 40, -10, 33], "#000000", closed=true, smooth=true, opacity=0.12)`, `rect(-44, -20, 70, 58, ${T(c, 1)}, gradient=${T(c, -1)})`,
      `polygon([-44, -20, -28, -38, 44, -38, 26, -20], ${T(c, 2)})`, `polygon([26, -20, 44, -38, 44, 20, 26, 38], ${T(c, -2)})`] },
  tank: { group: "containers", numbers: ["level"], defaults: [0.5], color: "water", prompt: "an open tank with water to level from 0 to 1",
    draw: ({ n: [v], c }) => [`rect(-40, -44, 80, 90, "ink@6")`, `rect(-38, 46 - 88 * ${v}, 76, 88 * ${v}, ${T(c, 1)}, gradient=${T(c, -1)})`,
      `line(-38, 46 - 88 * ${v}, 38, 46 - 88 * ${v}, ${T(c, 2)}, 2)`, `path([-40, -46, -40, 46, 40, 46, 40, -46], "ink@70", 3)`] },
  beaker: { group: "containers", numbers: ["level"], defaults: [0.5], color: "accent", prompt: "a lab beaker with liquid to level from 0 to 1",
    draw: ({ n: [v], c }) => [`rect(-28, 42 - 80 * ${v}, 56, 80 * ${v}, ${T(c, 1, 85)}, gradient=${T(c, -1, 85)}, radius=4)`,
      `path([-36, -46, -30, -40, -30, 40, -24, 46, 24, 46, 30, 40, 30, -40, 36, -46], "ink@60", 3)`,
      ...[0, 1, 2].map((i) => `line(16, ${-18 + 20 * i}, 26, ${-18 + 20 * i}, "ink@45", 1.5)`)] },
  layers: { group: "containers", numbers: ["count"], defaults: [4], caps: { count: 8 }, color: "sand", prompt: "a stack of count layers, up to 8",
    draw: ({ n: [k], c }) => upTo(k, 8, (i, v) => [`rect(-48, -46 + 92 * ${i} / ${k}, 96, 92 / ${k}, ${T(c, [1, 0, -1, -2][i % 4])}${v})`,
      `line(-48, -46 + 92 * ${i} / ${k}, 48, -46 + 92 * ${i} / ${k}, ${T(c, -2)}, 1${v})`]).flat() },

  node: { group: "structure", numbers: [], defaults: [], color: "accent", prompt: "a round node of a scheme", draw: ({ c }) => ball(0, 0, 40, c) },
  branch: { group: "structure", numbers: ["count"], defaults: [3], caps: { count: 6 }, color: "accent", prompt: "one node above count nodes, joined by lines, up to 6",
    draw: ({ n: [k], c }) => [...upTo(k, 6, (i, v) => `line(0, -32, -40 + 80 * ${i} / max(1, ${k} - 1), 32, "ink@55", 2${v})`),
      ...ball(0, -32, 12, c), ...upTo(k, 6, (i, v) => ball(`-40 + 80 * ${i} / max(1, ${k} - 1)`, 32, 9, T(c, 1), v)).flat()] },
  cell_block: { group: "structure", numbers: ["columns", "rows", "filled"], defaults: [4, 3, 5], caps: { columns: 6, rows: 6 }, color: "accent", prompt: "a block of columns × rows cells with filled cells in color, up to 6 × 6",
    draw: ({ n: [x, y, f], c }) => {
      const [cols, rows] = [x, y].map((value) => Math.min(6, Math.max(1, Math.round(num(value) ?? 6))));
      return Array.from({ length: cols * rows }, (_, i) => {
        const [col, row] = [i % cols, Math.floor(i / cols)];
        return `rect(${fixed(-48 + (96 * col) / cols + 1.5)}, ${fixed(-48 + (96 * row) / rows + 1.5)}, ${fixed(96 / cols - 3)}, ${fixed(96 / rows - 3)}, ${f} > ${i} ? ${T(c, 0)} : ${T(c, 2, 40)}, 3)`;
      });
    } },
  bubble: { group: "structure", numbers: [], defaults: [], color: "accent", text: true, prompt: "a speech bubble with the text of label=",
    draw: ({ c, u, text }) => [`rect(-48, -36, 96, 58, ${T(c, 0, 25)}, 14)`, `polygon([-22, 21, -32, 44, -6, 21], ${T(c, 0, 25)})`, ...(text ? [label(0, -7, text, '"ink"', 13, u)] : [])] },
};

/** A sum such as x - r keeps 2 decimals. */
function fixed(value) { return Math.round(value * 100) / 100; }

/** Unicode marks of a raised and a lowered character, for ^ and _ in a formula. */
const RAISED = Object.fromEntries([..."0123456789+-=()ni"].map((char, index) => [char, "⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁼⁽⁾ⁿⁱ"[index]]));
const LOWERED = Object.fromEntries([..."0123456789+-=()aeoxhklmnpst"].map((char, index) => [char, "₀₁₂₃₄₅₆₇₈₉₊₋₌₍₎ₐₑₒₓₕₖₗₘₙₚₛₜ"[index]]));
/** The text of a formula with ^x, ^{xy}, _x, and _{xy} as raised and lowered characters, where Unicode has them. */
const marked = (text) => text.replace(/([\^_])(\{[^}]*\}|[^\s{])/g, (all, mark, what) => {
  const chars = [...what.replace(/^\{|\}$/g, "")], table = mark === "^" ? RAISED : LOWERED;
  return chars.every((char) => table[char]) ? chars.map((char) => table[char]).join("") : all;
});
/**
 * The shapes of a formula text (formula and bubble): runs of text, frac{a}{b} as a fraction with a bar, and sqrt{x} as a root, side by side
 * around 0, 0. A label that is a formula of $/v is one text.
 */
function formulaShapes(text, c, u) {
  let value;
  try { value = JSON.parse(text ?? ""); } catch { return text ? [label(0, 0, text, c, 22, u)] : []; }
  const size = 22, wide = (part) => [...part].length * size * 0.55, parts = [];
  for (const match of String(value).matchAll(/frac\{([^}]*)\}\{([^}]*)\}|sqrt\{([^}]*)\}|((?:(?!frac\{|sqrt\{).)+)/g)) {
    const [, top, bottom, root, run] = match;
    if (run) parts.push({ w: wide(marked(run)), shapes: (x) => [`text(${x + wide(marked(run)) / 2}, 0, ${JSON.stringify(marked(run))}, ${c}, ${size})`] });
    else if (root !== undefined) {
      const w = wide(marked(root)) + 14;
      parts.push({ w, shapes: (x) => [`path([${x}, 2, ${x + 4}, 0, ${x + 9}, 13, ${x + 14}, -15, ${x + w}, -15], ${c}, 2)`, `text(${x + 14 + (w - 14) / 2}, 0, ${JSON.stringify(marked(root))}, ${c}, ${size})`] });
    } else {
      const w = Math.max(wide(marked(top)), wide(marked(bottom))) * 0.8 + 6;
      parts.push({ w, shapes: (x) => [`text(${x + w / 2}, -13, ${JSON.stringify(marked(top))}, ${c}, ${size * 0.8})`, `line(${x}, 0, ${x + w}, 0, ${c}, 2)`,
        `text(${x + w / 2}, 14, ${JSON.stringify(marked(bottom))}, ${c}, ${size * 0.8})`] });
    }
  }
  // The parts sit side by side in pixels, centered at 0: a group of scale u cancels the scale of the element, so the formula stays at 22 px.
  const total = parts.reduce((sum, part) => sum + part.w, 0);
  let x = -total / 2;
  const shapes = parts.flatMap((part) => { const out = part.shapes(fixed(x)); x += part.w; return out; });
  return [`group(0, 0, ${u}, [${shapes.map((shape) => shape.replace(/(-?\d+\.\d{3,})/g, (n) => String(fixed(Number(n))))).join(", ")}])`];
}

return { toneOf, ELEMENTS };
})();
// catalog.js
const { OBJECTS } = (() => {
// Built by kit/build.js from kit/library/objects. Do not edit: run bun kit/build.js.

/** The ready objects of the library by their name: the box, the layers, and the parts of each one (library.js). */
const OBJECTS = {"brain":{"id":"body.brain","box":[100,91.4],"image":true,"parts":[{"id":"cerebrum","names":{"ru":"большие полушария","en":"cerebrum"},"label":[-23.9,-23.4],"ring":15.2},{"id":"cerebellum","names":{"ru":"мозжечок","en":"cerebellum"},"label":[15.2,22.3],"ring":10.8},{"id":"brain_stem","names":{"ru":"ствол мозга","en":"brain stem"},"label":[4.3,37.5],"ring":6.5}]},"dna":{"id":"body.dna","box":[54.9,100],"image":true,"parts":[{"id":"strand","names":{"ru":"цепочка","en":"strand"},"label":[-19,-38.1],"ring":7.5},{"id":"base_pair","names":{"ru":"пара оснований","en":"base pair"},"label":[3.6,-34.8],"ring":7.5}]},"ear":{"id":"body.ear","box":[100,79.1],"image":true,"parts":[{"id":"outer_ear","names":{"ru":"ушная раковина","en":"outer ear"},"label":[-39.1,-12],"ring":10.2},{"id":"ear_canal","names":{"ru":"слуховой проход","en":"ear canal"},"label":[-16.6,4.4],"ring":6.1},{"id":"eardrum","names":{"ru":"барабанная перепонка","en":"eardrum"},"label":[-3.2,4.4],"ring":6.1},{"id":"small_bones","names":{"ru":"слуховые косточки","en":"small bones"},"label":[8.1,-3.8],"ring":7.1},{"id":"cochlea","names":{"ru":"улитка","en":"cochlea"},"label":[33.7,9.5],"ring":10.2}]},"eye":{"id":"body.eye","box":[100,86],"image":true,"parts":[{"id":"cornea","names":{"ru":"роговица","en":"cornea"},"label":[-44.9,-11],"ring":5.1},{"id":"iris","names":{"ru":"радужка","en":"iris"},"label":[-33,-13.2],"ring":6.5},{"id":"lens","names":{"ru":"хрусталик","en":"lens"},"label":[-18.9,2],"ring":7.5},{"id":"retina","names":{"ru":"сетчатка","en":"retina"},"label":[29.9,-21.8],"ring":8.6},{"id":"optic_nerve","names":{"ru":"зрительный нерв","en":"optic nerve"},"label":[45,6.3],"ring":5}]},"heart":{"id":"body.heart","box":[68.6,100],"image":true,"parts":[{"id":"vena_cava","names":{"ru":"полая вена","en":"vena cava"},"label":[-20.2,-35],"ring":6.3},{"id":"aorta","names":{"ru":"аорта","en":"aorta"},"label":[-3.3,-39.2],"ring":7.3},{"id":"pulmonary_artery","names":{"ru":"лёгочная артерия","en":"pulmonary artery"},"label":[24.1,-24.4],"ring":6.3},{"id":"right_atrium","names":{"ru":"правое предсердие","en":"right atrium"},"label":[-25.5,-4.4],"ring":8.7},{"id":"left_atrium","names":{"ru":"левое предсердие","en":"left atrium"},"label":[26.2,-6.5],"ring":8},{"id":"right_ventricle","names":{"ru":"правый желудочек","en":"right ventricle"},"label":[-11.8,23],"ring":12.6},{"id":"left_ventricle","names":{"ru":"левый желудочек","en":"left ventricle"},"label":[17.7,20.9],"ring":12.6}]},"lungs":{"id":"body.lungs","box":[92.7,100],"image":true,"parts":[{"id":"trachea","names":{"ru":"трахея","en":"trachea"},"label":[0.3,-34.4],"ring":6.4},{"id":"bronchi","names":{"ru":"бронхи","en":"bronchi"},"label":[-8.2,-1.3],"ring":6.4},{"id":"right_lung","names":{"ru":"правое лёгкое","en":"right lung"},"label":[-31.8,19],"ring":14.5},{"id":"left_lung","names":{"ru":"левое лёгкое","en":"left lung"},"label":[32.4,19],"ring":13.9}]},"muscle":{"id":"body.muscle","box":[98.8,100],"image":true,"parts":[{"id":"biceps","names":{"ru":"бицепс","en":"biceps"},"label":[-3.1,9.4],"ring":11.1},{"id":"triceps","names":{"ru":"трицепс","en":"triceps"},"label":[10.4,22.3],"ring":9},{"id":"forearm_bones","names":{"ru":"кости предплечья","en":"forearm bones"},"label":[-38.9,-5.1],"ring":7.8},{"id":"elbow","names":{"ru":"локоть","en":"elbow"},"label":[-32.2,39.7],"ring":7.8},{"id":"humerus","names":{"ru":"плечевая кость","en":"humerus"},"label":[19.3,-14.1],"ring":7.8},{"id":"shoulder_blade","names":{"ru":"лопатка","en":"shoulder blade"},"label":[41.7,-8.5],"ring":7.6}]},"skeleton":{"id":"body.skeleton","box":[43.9,100],"image":true,"parts":[{"id":"skull","names":{"ru":"череп","en":"skull"},"label":[-2,-40.6],"ring":7.1},{"id":"rib_cage","names":{"ru":"грудная клетка","en":"rib cage"},"label":[-6.1,-22.2],"ring":8.1},{"id":"spine","names":{"ru":"позвоночник","en":"spine"},"label":[1.1,-7.8],"ring":5.1},{"id":"pelvis","names":{"ru":"таз","en":"pelvis"},"label":[6.2,1.4],"ring":6.1},{"id":"thigh_bone","names":{"ru":"бедренная кость","en":"thigh bone"},"label":[-7.1,22.9],"ring":6.1}]},"skin":{"id":"body.skin","box":[95.1,100],"image":true,"parts":[{"id":"epidermis","names":{"ru":"эпидермис","en":"epidermis"},"label":[22.6,-29.7],"ring":7.8},{"id":"dermis","names":{"ru":"дерма","en":"dermis"},"label":[-40.2,5],"ring":7.3},{"id":"hair","names":{"ru":"волос","en":"hair"},"label":[-5.4,-35.3],"ring":6.7},{"id":"sweat_gland","names":{"ru":"потовая железа","en":"sweat gland"},"label":[15.8,11.8],"ring":8.9},{"id":"fat_layer","names":{"ru":"жировой слой","en":"fat layer"},"label":[-22.2,34.2],"ring":10}]},"stomach":{"id":"body.stomach","box":[63.4,100],"image":true,"parts":[{"id":"esophagus","names":{"ru":"пищевод","en":"esophagus"},"label":[-1.2,-43.6],"ring":5.1},{"id":"stomach","names":{"ru":"желудок","en":"stomach"},"label":[15.3,-31.3],"ring":10.2},{"id":"small_intestine","names":{"ru":"тонкая кишка","en":"small intestine"},"label":[4,16],"ring":12.3},{"id":"large_intestine","names":{"ru":"толстая кишка","en":"large intestine"},"label":[-24.8,7.8],"ring":6.8}]},"tooth":{"id":"body.tooth","box":[75.3,100],"image":true,"parts":[{"id":"enamel","names":{"ru":"эмаль","en":"enamel"},"label":[-23.4,-39.6],"ring":6.5},{"id":"dentin","names":{"ru":"дентин","en":"dentin"},"label":[-23.4,-11.1],"ring":6.5},{"id":"pulp","names":{"ru":"пульпа","en":"pulp"},"label":[9.6,-17.7],"ring":7.6},{"id":"root","names":{"ru":"корень","en":"root"},"label":[16.2,28.5],"ring":6.5},{"id":"gum","names":{"ru":"десна","en":"gum"},"label":[-28.9,27.4],"ring":8.7}]},"water_molecule":{"id":"chem.water_molecule","box":[100,66],"layers":[{"part":"bonds","role":"muted","tone":1,"line":true,"open":true,"width":7,"path":[0,-8,-34,18]},{"part":"bonds","role":"muted","tone":1,"line":true,"open":true,"width":7,"path":[0,-8,34,18]},{"part":"hydrogen","role":"muted","tone":3,"gradient":[3,1,"radial"],"circle":[-34,18,14],"highlight":[-39,12,4.5]},{"part":"hydrogen","role":"muted","tone":3,"gradient":[3,1,"radial"],"circle":[34,18,14],"highlight":[29,12,4.5]},{"part":"oxygen","role":"error","tone":0,"gradient":[1,-1,"radial"],"circle":[0,-8,24],"highlight":[-8,-17,7]}],"parts":[{"id":"oxygen","names":{"ru":"атом кислорода","en":"oxygen atom"},"label":[8,-16]},{"id":"hydrogen","names":{"ru":"атом водорода","en":"hydrogen atom"},"label":[36,20]},{"id":"bonds","names":{"ru":"связь","en":"bond"},"label":[-17,5]}]},"circle_radius":{"id":"math.circle_radius","box":[100,100],"layers":[{"part":"circle","role":"water","tone":2,"outline":-2,"circle":[0,0,47]},{"part":"diameter","role":"water","tone":-2,"line":true,"open":true,"width":1.6,"path":[-47,0,47,0]},{"part":"radius","role":"water","tone":-2,"line":true,"open":true,"width":2.4,"path":[0,0,33.2,-33.2]},{"part":"center","role":"water","tone":-2,"circle":[0,0,3]}],"parts":[{"id":"circle","names":{"ru":"окружность","en":"circle"},"label":[-33.2,-33.2]},{"id":"center","names":{"ru":"центр","en":"center"},"label":[-1,1]},{"id":"radius","names":{"ru":"радиус","en":"radius"},"label":[16.6,-16.6]},{"id":"diameter","names":{"ru":"диаметр","en":"diameter"},"label":[-30,0]}]},"cone":{"id":"math.cone","box":[80,100],"layers":[{"role":"error","tone":2,"outline":-2,"path":[0,-48,-36,34,-31.2,39.5,-18,43.5,0,45,18,43.5,31.2,39.5,36,34]},{"part":"base","role":"error","tone":1,"outline":-2,"ellipse":[0,34,36,11]},{"part":"height","role":"error","tone":-2,"line":true,"open":true,"width":1.6,"path":[0,-48,0,34]},{"part":"slant","role":"error","tone":-2,"line":true,"open":true,"width":3,"path":[0,-48,-36,34]},{"part":"apex","role":"error","tone":-3,"circle":[0,-48,3]}],"parts":[{"id":"apex","names":{"ru":"вершина","en":"apex"},"label":[0,-48]},{"id":"slant","names":{"ru":"образующая","en":"slant height"},"label":[-18,-7]},{"id":"height","names":{"ru":"высота","en":"height"},"label":[0,5]},{"id":"base","names":{"ru":"основание","en":"base"},"label":[20,38]}]},"coordinate_plane":{"id":"math.coordinate_plane","box":[100,100],"layers":[{"part":"grid","role":"muted","tone":0,"line":true,"open":true,"width":1,"path":[-40,-48,-40,48]},{"part":"grid","role":"muted","tone":0,"line":true,"open":true,"width":1,"path":[-20,-48,-20,48]},{"part":"grid","role":"muted","tone":0,"line":true,"open":true,"width":1,"path":[20,-48,20,48]},{"part":"grid","role":"muted","tone":0,"line":true,"open":true,"width":1,"path":[40,-48,40,48]},{"part":"grid","role":"muted","tone":0,"line":true,"open":true,"width":1,"path":[-48,-40,48,-40]},{"part":"grid","role":"muted","tone":0,"line":true,"open":true,"width":1,"path":[-48,-20,48,-20]},{"part":"grid","role":"muted","tone":0,"line":true,"open":true,"width":1,"path":[-48,20,48,20]},{"part":"grid","role":"muted","tone":0,"line":true,"open":true,"width":1,"path":[-48,40,48,40]},{"part":"x_axis","role":"ink","tone":0,"line":true,"open":true,"width":2.4,"path":[-48,0,44,0]},{"part":"x_axis","role":"ink","tone":0,"path":[50,0,42,-4,42,4]},{"part":"y_axis","role":"ink","tone":0,"line":true,"open":true,"width":2.4,"path":[0,48,0,-44]},{"part":"y_axis","role":"ink","tone":0,"path":[0,-50,-4,-42,4,-42]},{"part":"origin","role":"ink","tone":0,"circle":[0,0,3]},{"part":"point","role":"error","tone":0,"circle":[20,-20,4]}],"parts":[{"id":"x_axis","names":{"ru":"ось x","en":"x axis"},"label":[36,0]},{"id":"y_axis","names":{"ru":"ось y","en":"y axis"},"label":[-1,-36]},{"id":"origin","names":{"ru":"начало координат","en":"origin"},"label":[-2,2]},{"id":"point","names":{"ru":"точка","en":"point"},"label":[20,-20]},{"id":"grid","names":{"ru":"сетка","en":"grid"},"label":[-40,30]}]},"cube":{"id":"math.cube","box":[100,100],"layers":[{"part":"face","role":"water","tone":1,"outline":-2,"path":[-50,-20,20,-20,20,50,-50,50]},{"role":"water","tone":2,"outline":-2,"path":[-50,-20,-20,-50,50,-50,20,-20]},{"role":"water","tone":0,"outline":-2,"path":[20,-20,50,-50,50,20,20,50]},{"part":"edge","role":"water","tone":-2,"line":true,"open":true,"width":3,"path":[20,-20,20,50]},{"part":"vertex","role":"water","tone":-3,"circle":[50,-50,3]}],"parts":[{"id":"face","names":{"ru":"грань","en":"face"},"label":[-15,15]},{"id":"edge","names":{"ru":"ребро","en":"edge"},"label":[20,30]},{"id":"vertex","names":{"ru":"вершина","en":"vertex"},"label":[50,-50]}]},"cylinder":{"id":"math.cylinder","box":[80,100],"layers":[{"part":"side","role":"land","tone":2,"outline":-2,"path":[-30,-38,-30,38,-26,43,-15,46.7,0,48,15,46.7,26,43,30,38,30,-38]},{"part":"base","role":"land","tone":1,"outline":-2,"ellipse":[0,-38,30,10]},{"part":"radius","role":"land","tone":-2,"line":true,"open":true,"width":2,"path":[0,-38,30,-38]},{"part":"height","role":"land","tone":-2,"line":true,"open":true,"width":2,"path":[38,-38,38,38]}],"parts":[{"id":"base","names":{"ru":"основание","en":"base"},"label":[-15,-38]},{"id":"side","names":{"ru":"боковая поверхность","en":"lateral surface"},"label":[-15,10]},{"id":"radius","names":{"ru":"радиус","en":"radius"},"label":[15,-38]},{"id":"height","names":{"ru":"высота","en":"height"},"label":[38,0]}]},"right_triangle":{"id":"math.right_triangle","box":[100,70],"layers":[{"role":"land","tone":2,"path":[-46,33,46,33,-46,-33]},{"part":"leg_a","role":"land","tone":-2,"line":true,"open":true,"width":2.4,"path":[-46,-33,-46,33]},{"part":"leg_b","role":"land","tone":-2,"line":true,"open":true,"width":2.4,"path":[-46,33,46,33]},{"part":"hypotenuse","role":"land","tone":-2,"line":true,"open":true,"width":2.4,"path":[46,33,-46,-33]},{"part":"right_angle","role":"land","tone":-2,"line":true,"open":true,"width":1.6,"path":[-46,22,-35,22,-35,33]}],"parts":[{"id":"leg_a","names":{"ru":"катет a","en":"leg a"},"label":[-46,0]},{"id":"leg_b","names":{"ru":"катет b","en":"leg b"},"label":[10,33]},{"id":"hypotenuse","names":{"ru":"гипотенуза c","en":"hypotenuse c"},"label":[4,-3]},{"id":"right_angle","names":{"ru":"прямой угол","en":"right angle"},"label":[-40,28]}]},"square_grid":{"id":"math.square_grid","box":[100,100],"layers":[{"part":"side","role":"sun","tone":2,"outline":-2,"path":[-48,-48,48,-48,48,48,-48,48]},{"part":"cell","role":"sun","tone":0,"path":[-48,-48,-24,-48,-24,-24,-48,-24]},{"part":"grid","role":"sun","tone":-2,"line":true,"open":true,"width":1,"path":[-24,-48,-24,48]},{"part":"grid","role":"sun","tone":-2,"line":true,"open":true,"width":1,"path":[0,-48,0,48]},{"part":"grid","role":"sun","tone":-2,"line":true,"open":true,"width":1,"path":[24,-48,24,48]},{"part":"grid","role":"sun","tone":-2,"line":true,"open":true,"width":1,"path":[-48,-24,48,-24]},{"part":"grid","role":"sun","tone":-2,"line":true,"open":true,"width":1,"path":[-48,0,48,0]},{"part":"grid","role":"sun","tone":-2,"line":true,"open":true,"width":1,"path":[-48,24,48,24]},{"part":"side","role":"sun","tone":-2,"line":true,"open":true,"width":3,"path":[-48,48,48,48]},{"part":"vertex","role":"sun","tone":-3,"circle":[48,-48,3]}],"parts":[{"id":"side","names":{"ru":"сторона","en":"side"},"label":[10,48]},{"id":"cell","names":{"ru":"клетка","en":"unit square"},"label":[-36,-36]},{"id":"grid","names":{"ru":"сетка","en":"grid"},"label":[0,12]},{"id":"vertex","names":{"ru":"вершина","en":"vertex"},"label":[48,-48]}]},"comet":{"id":"space.comet","box":[100,82.4],"image":true,"parts":[{"id":"nucleus","names":{"ru":"ядро","en":"nucleus"},"label":[-34.5,22.4],"ring":9.7},{"id":"tail","names":{"ru":"хвост","en":"tail"},"label":[24.7,-25],"ring":10.7}]},"earth":{"id":"space.earth","box":[100,99.2],"image":true,"parts":[{"id":"ocean","names":{"ru":"океан","en":"ocean"},"label":[-8.8,-6.3],"ring":7.9},{"id":"south_america","names":{"ru":"Южная Америка","en":"South America"},"label":[-13.4,15.4],"ring":9.1},{"id":"clouds","names":{"ru":"облака","en":"clouds"},"label":[-40.7,1.7],"ring":6.8},{"id":"africa","names":{"ru":"Африка","en":"Africa"},"label":[29.9,8.6],"ring":11.3},{"id":"europe","names":{"ru":"Европа","en":"Europe"},"label":[20.8,-25.6],"ring":7.9}]},"galaxy":{"id":"space.galaxy","box":[100,93.8],"image":true,"parts":[{"id":"core","names":{"ru":"ядро","en":"core"},"label":[-3.2,-1.1],"ring":8.7},{"id":"spiral_arm","names":{"ru":"спиральный рукав","en":"spiral arm"},"label":[37.2,-24],"ring":9.8}]},"moon":{"id":"space.moon","box":[99.6,100],"image":true,"parts":[{"id":"crater","names":{"ru":"кратер","en":"crater"},"label":[-29.6,18.1],"ring":6.8},{"id":"mare","names":{"ru":"лунное море","en":"lunar sea"},"label":[25,-4.7],"ring":10.2}]},"rocket":{"id":"space.rocket","box":[65.2,100],"image":true,"parts":[{"id":"nose_cone","names":{"ru":"носовой обтекатель","en":"nose cone"},"label":[-3.7,-40.1],"ring":7.3},{"id":"window","names":{"ru":"иллюминатор","en":"window"},"label":[2.7,-7.4],"ring":9.4},{"id":"body","names":{"ru":"корпус","en":"body"},"label":[16.4,-23.3],"ring":7.3},{"id":"fins","names":{"ru":"стабилизаторы","en":"fins"},"label":[-24.7,27.4],"ring":7.9},{"id":"nozzle","names":{"ru":"сопло","en":"nozzle"},"label":[-5.8,44.2],"ring":5.7}]},"satellite":{"id":"space.satellite","box":[100,66.6],"image":true,"parts":[{"id":"solar_panels","names":{"ru":"солнечные батареи","en":"solar panels"},"label":[-36.9,3.6],"ring":9.2},{"id":"body","names":{"ru":"корпус","en":"body"},"label":[-4,17],"ring":8.2},{"id":"dish","names":{"ru":"антенна","en":"dish antenna"},"label":[10.4,-21.1],"ring":9.2}]},"saturn":{"id":"space.saturn","box":[100,61.6],"image":true,"parts":[{"id":"planet","names":{"ru":"газовый шар","en":"ball of gas"},"label":[-18.6,-7.9],"ring":12.3},{"id":"bands","names":{"ru":"полосы облаков","en":"cloud bands"},"label":[6.1,20.9],"ring":7.2},{"id":"rings","names":{"ru":"кольца","en":"rings"},"label":[43.3,-7.9],"ring":6.7}]},"star":{"id":"space.star","box":[100,96.5],"image":true,"parts":[]},"sun":{"id":"space.sun","box":[99.6,100],"image":true,"parts":[{"id":"surface","names":{"ru":"поверхность","en":"surface"},"label":[-13.1,9.6],"ring":13.4},{"id":"prominence","names":{"ru":"протуберанец","en":"prominence"},"label":[40.8,-35.4],"ring":8.9}]}};

return { OBJECTS };
})();
// library.js
const { UNITS, SMALLEST, LEAST, SEGMENTS, USE_NAMED, LIBRARY, entryOf, partName, missOf, useFault, LABEL, lineWidth, labelLines, layoutOf, useShapes, segmentsOfEntry } = (() => {
// The library of a scene (stage 6, L4 to L6): the generic elements (elements.js) and the ready objects (catalog.js, which build.js writes
// from kit/library/objects). A template line places one of them:
//
//   s3 = use("heart", 150, 150, 180, mark="left_ventricle", parts=true)      a ready object: its name, its center, its size
//   s4 = use("thermometer", 60, 150, 120, [0.7], label="Вода")              an element: its name, its center, its size, and its numbers
//
// The kit expands the line into the shapes of the kind registry (kinds.js) when it expands the file (template.js), so the run-time draws
// no catalog. A name that the library does not have is a fault with that name (the miss log of spec 12.8).
//
// A ready object is a vector entry (layers of paths in the roles of the theme) or an image entry (stage 6, I1): a ready picture that the host
// draws from its assets with its own colors, in both themes. Both kinds have the same names, parts, labels, and marks.








/** The size of the box of an object in the units of its entry: its longer side. */
const UNITS = 100;
/** The size at which each entry must read as its thing (L5, the review sheet): a rule of the catalog review, not of a scene. */
const SMALLEST = 56;
/** The smallest SIZE of an object in a scene (fix list 3, H2): a small Sun beside a large Earth is meant. */
const LEAST = 24;
/** The largest number of path segments of one entry (L5). A gradient, a highlight, and the ground shadow cost no segment. */
const SEGMENTS = 40;
/** The named arguments of a library line. */
const USE_NAMED = { label: "a text under it", mark: "the name of a part to mark", parts: "true to name each part", turn: "degrees", color: "a color of an element" };

/** The state of the library in one call of the host: the ids of the image entries whose image the host cannot open (compile.js sets it). */
const LIBRARY = { missing: new Set() };

/** The entry of a name: an object or an element, by its name or by the id of an object (area.name). An image entry with no image is no entry. */
function entryOf(name) {
  if (typeof name !== "string") return null;
  const key = name.includes(".") ? name.split(".").pop() : name;
  if (Object.hasOwn(OBJECTS, key) && (key === name || OBJECTS[key].id === name) && !LIBRARY.missing.has(OBJECTS[key].id)) return { kind: "object", name: key, entry: OBJECTS[key] };
  return Object.hasOwn(ELEMENTS, name) ? { kind: "element", name, entry: ELEMENTS[name] } : null;
}

const isText = (node) => node?.k === "lit" && typeof node.v === "string";
const numberOf = (node) => (node?.k === "lit" && typeof node.v === "number" ? node.v : node?.k === "un" && node.op === "-" && typeof node.arg?.v === "number" ? -node.arg.v : undefined);
const pathIn = (node) => node && typeof node === "object" && (node.k === "path" || Object.values(node).some(pathIn));
/** The names of the parts of an object in a language, with English for a language that the entry does not have. */
const partName = (part, lang) => part.names[lang] ?? part.names.en;

/** The name of a library line whose name the library does not have (the miss log of spec 12.8), or null. */
const missOf = (tree) => (tree?.k === "call" && tree.name === "use" && isText(tree.args?.[0]) && !entryOf(tree.args[0].v) ? tree.args[0].v : null);

/**
 * The fault of a library line use(NAME, X, Y, SIZE, [NUMBERS], ...), or null. A mark, a label, and the numbers are literals or formulas of
 * $/v. An unknown name is a fault that names it (missOf), so the repair draws the thing with shapes or an emoji. lang is the language of the
 * scene, whose part names must fit at the sides of the object.
 */
function useFault(tree, picture = [300, 300], lang = "en") {
  const [name, ...rest] = tree.args ?? [], form = "a library line is use(NAME, X, Y, SIZE), with [NUMBERS] of an element after SIZE";
  if (!isText(name)) return `${form}, and NAME is a name in quotes`;
  const found = entryOf(name.v);
  if (!found) return `the library has no ${JSON.stringify(name.v)}. Draw it with shapes, or with an emoji in a text shape`;
  if (rest.length < 3 || rest.length > (found.kind === "element" ? 4 : 3)) return found.kind === "element" ? `${form}: ${name.v}(${found.entry.numbers.join(", ")})` : `${form}, and ${name.v} is an object: it takes no numbers`;
  if (rest.length === 4 && rest[3].k !== "list") return `the numbers of ${name.v} are a list: [${found.entry.numbers.join(", ")}]`;
  if (rest.length === 4 && rest[3].items.length > found.entry.numbers.length) return `${name.v} takes at most ${found.entry.numbers.length} numbers: ${found.entry.numbers.join(", ")}`;
  // A plain number above the cap of its element would draw a wrong picture (the draw rule shows at most the cap), so it is a fault to repair.
  const over = rest.length === 4 && found.entry.numbers.map((number, index) => [number, numberOf(rest[3].items[index]), found.entry.caps?.[number]]).find(([, value, cap]) => value > cap);
  if (over) return `the ${over[0]} of ${name.v} is at most ${over[2]}, and this line has ${over[1]}`;
  const other = Object.keys(tree.named).find((key) => key !== "visible" && !Object.hasOwn(USE_NAMED, key));
  if (other) return `a library line takes only ${Object.keys(USE_NAMED).join("=, ")}=, and visible= by name, and ${other}= is not one`;
  if (tree.named.label && !(isText(tree.named.label) && tree.named.label.v.trim()) && !pathIn(tree.named.label)) return "label= is a text in quotes";
  if (isText(tree.named.label) && [...tree.named.label.v].length > 28) return `label= has ${[...tree.named.label.v].length} characters, and its cap is 28`;
  const parts = found.entry.parts ?? [], mark = tree.named.mark;
  if (mark && found.kind === "element") return `${name.v} is an element: it has no parts to mark`;
  if (isText(mark) && !parts.some((part) => part.id === mark.v || Object.values(part.names).includes(mark.v))) return `${name.v} has no part ${JSON.stringify(mark.v)}. Its parts are ${parts.map((part) => part.id).join(", ")}`;
  if (tree.named.color && found.entry.image) return `${name.v} is a ready picture with its own colors, so it takes no color=`;
  if (tree.named.color && isText(tree.named.color) && !isColor(tree.named.color.v) && !namedColor(tree.named.color.v)) return `the color ${JSON.stringify(tree.named.color.v)} is not a color of the kit`;
  if (found.entry.text && !tree.named.label) return `${name.v} shows the text of label=, and this line has no label=`;
  // A SIZE under LEAST or past the picture is a fault (H2).
  const size = numberOf(rest[2]), least = found.kind === "object" ? LEAST : 12;
  if (size !== undefined && (size < least || size > 300)) return `the SIZE of ${name.v} is from ${least} to 300, and this line has ${size}`;
  // The names of the parts stand at the sides of the object, inside the picture, and leave the object its SIZE, or SMALLEST for a larger SIZE.
  const at = rest.slice(0, 3).map(numberOf);
  const placed = found.kind === "object" && at.every((value) => value !== undefined) ? layoutOf(found, tree.named, at, picture, lang) : null;
  if (placed && (placed.size < Math.min(SMALLEST, at[2]) - 0.01 || placed.labels.some((one) => one.clipped))) {
    return `the names of the parts of ${name.v} leave it too little room: name fewer parts, or give it more height`;
  }
  return null;
}

/** An expression with each sum, product, and sin or cos of plain numbers computed, so a line of plain numbers expands to plain numbers. */
function folded(node) {
  if (!node || typeof node !== "object") return node;
  const out = Array.isArray(node) ? node.map(folded) : Object.fromEntries(Object.entries(node).map(([key, inner]) => [key, folded(inner)]));
  const n = (inner) => numberOf(inner), round = (value) => ({ k: "lit", v: Math.round(value * 100) / 100 });
  if (out.k === "bin" && n(out.left) !== undefined && n(out.right) !== undefined && "+-*/".includes(out.op) && !(out.op === "/" && n(out.right) === 0)) {
    const [a, b] = [n(out.left), n(out.right)];
    return round(out.op === "+" ? a + b : out.op === "-" ? a - b : out.op === "*" ? a * b : a / b);
  }
  if (out.k === "call" && ["sin", "cos"].includes(out.name) && out.args.length === 1 && n(out.args[0]) !== undefined) return round(Math[out.name]((n(out.args[0]) * Math.PI) / 180));
  return out;
}
/** A shape text with its plain sums computed (folded). */
const tidy = (text) => show(folded(parseExpression(text)));
/** An argument as a part of a formula: a number as it is, else the formula in brackets. */
const term = (node) => (numberOf(node) !== undefined ? String(numberOf(node)) : `(${show(node)})`);

/** The color of a layer: its role with its tone step, such as "error+1". */
const tone = (role, step = 0, alpha) => `${role}${step > 0 ? `+${step}` : step < 0 ? step : ""}${alpha !== undefined ? `@${alpha}` : ""}`;
/** The points of an ellipse as a smooth closed path: 8 points, turned deg degrees around its center (clockwise on the screen). */
const ellipse = ([cx, cy, rx, ry, deg = 0]) => {
  const [c, s] = [Math.cos((deg * Math.PI) / 180), Math.sin((deg * Math.PI) / 180)];
  return Array.from({ length: 8 }, (_, index) => {
    const [ex, ey] = [rx * Math.cos((index * Math.PI) / 4), ry * Math.sin((index * Math.PI) / 4)];
    return [cx + ex * c - ey * s, cy + ex * s + ey * c].map((v) => Math.round(v * 10) / 10);
  }).flat();
};

/**
 * The shapes of one layer of an object, in the units of its entry: the fill (a path, a circle, or an ellipse) with its tone, its gradient
 * between two tone steps, its outline in a darker tone (a flat figure), and its highlight. paint gives the color text of a tone of the
 * role, and opacity is the opacity text, or null.
 */
function layerShapes(layer, paint, opacity) {
  const fade = opacity ? `, opacity=${opacity}` : "", out = [];
  const [from = layer.tone ?? 0, to, kind] = layer.gradient ?? [], shade = layer.gradient ? `, gradient=${paint(to)}, ${kind === "linear" ? "" : "radial=1, "}`.replace(/, $/, "") : "";
  const fill = layer.circle ? `circle(${layer.circle.join(", ")}, ${paint(from)}${shade}${fade})`
    : `path([${(layer.ellipse ? ellipse(layer.ellipse) : layer.path).join(", ")}], ${paint(from)}, smooth=${Boolean(layer.ellipse || layer.smooth)}, closed=true${shade}${fade})`;
  if (!layer.line) out.push(fill);
  if (layer.outline !== undefined || layer.line) {
    const width = layer.width ?? 1.4, shape = layer.circle ? `circle(${layer.circle.join(", ")}, ${paint(layer.outline ?? layer.tone ?? 0, undefined, true)}, stroke=true${fade})`
      : `path([${(layer.ellipse ? ellipse(layer.ellipse) : layer.path).join(", ")}], ${paint(layer.outline ?? layer.tone ?? 0, undefined, true)}, ${width}, smooth=${Boolean(layer.ellipse || layer.smooth)}, closed=${!layer.open}, stroke=true${fade})`;
    out.push(shape);
  }
  if (layer.highlight) {
    const [x, y, r] = layer.highlight;
    out.push(`circle(${x}, ${y}, ${r}, ${paint(3, 80)}, gradient=${paint(3, 0)}, radial=0${fade})`);
  }
  return out;
}

/**
 * The parts that a line names: each part with parts=true or with a mark that is a formula (only the marked one shows), else the marked part when
 * the mark is a name. A turned object names no part.
 */
function namedParts(found, { mark, parts, turn }) {
  const list = found.kind === "object" ? found.entry.parts ?? [] : [];
  return turn ? [] : parts?.v === true || (mark && !isText(mark)) ? list : isText(mark) ? list.filter((part) => part.id === mark.v || Object.values(part.names).includes(mark.v)) : [];
}
/** The size, the line height, the gap to the object, and the margin of the part labels, in the units of the picture. */
const LABEL = { size: 12, line: 14, gap: 8, margin: 4 };
/** The width of a text line of the part labels: a wide letter (м, ю, m, w, a capital) takes 0.85 of the size, each other character 0.56. */
const lineWidth = (text) => [...text].reduce((sum, char) => sum + (/[мшщжюыфmw]/.test(char) || char !== char.toLowerCase() ? 0.85 : 0.56), 0) * LABEL.size;
/** The lines of a part label: a name of more than 8 characters breaks at the space that gives the shortest longer line, if each line keeps 3 characters. */
function labelLines(text) {
  const chars = [...text];
  if (chars.length <= 8) return [text];
  const breaks = chars.map((char, index) => (char === " " && index >= 3 && chars.length - index - 1 >= 3 ? index : -1)).filter((index) => index > 0);
  if (!breaks.length) return [text];
  const best = breaks.reduce((a, b) => (Math.max(a, chars.length - a - 1) <= Math.max(b, chars.length - b - 1) ? a : b));
  return [chars.slice(0, best).join(""), chars.slice(best + 1).join("")];
}

/**
 * The place of an object with the labels of its named parts (F2), as {x, y, size, labels}. A part in the left half of the object gets its
 * label at the left, each other part at the right. With labels, the object stands in the middle of the space that the two label columns
 * leave in the width of the picture, as large as that space permits and at most SIZE, at the height Y. Each label is {part, lines, px, py, x,
 * y, align, clipped}: px, py is the point of its part, x, y the end of its leader line, and clipped is true for a label that leaves the
 * picture. With no named part, the object keeps X, Y, and SIZE.
 */
function layoutOf(found, named, [X, Y, S], [width, height], lang) {
  const parts = namedParts(found, named), [w, h] = found.entry.box, { line, gap, margin } = LABEL;
  if (!parts.length) return { x: X, y: Y, size: S, labels: [] };
  const items = parts.map((part) => ({ part, lines: labelLines(partName(part, lang)), left: part.label[0] < 0 }));
  const column = (left) => Math.max(0, ...items.filter((one) => one.left === left).flatMap((one) => one.lines.map(lineWidth)));
  const [L, R] = [column(true), column(false)];
  const xl = margin + (L ? L + gap : 0), xr = width - margin - (R ? R + gap : 0);
  const size = Math.max(0, Math.min(S, ((xr - xl) * UNITS) / w, ((height - 2 * margin) * UNITS) / h));
  const k = size / UNITS, x = (xl + xr) / 2, y = Math.min(Math.max(Y, margin + (h * k) / 2), height - margin - (h * k) / 2);
  const labels = [];
  for (const left of [true, false]) {
    const side = items.filter((one) => one.left === left).map((one) => ({ ...one, px: x + one.part.label[0] * k, py: y + one.part.label[1] * k })).sort((a, b) => a.py - b.py);
    // Each label starts at the height of its point. Then the labels of one side move down, so none overlaps the one above it, and up, so the
    // last one stays inside the picture.
    let bottom = -Infinity;
    for (const one of side) { one.y = Math.max(one.py, bottom + 2 + (one.lines.length * line) / 2); bottom = one.y + (one.lines.length * line) / 2; }
    let top = height - margin;
    for (const one of [...side].reverse()) { one.y = Math.min(one.y, top - (one.lines.length * line) / 2); top = one.y - (one.lines.length * line) / 2 - 2; }
    for (const one of side) {
      labels.push({ ...one, align: left ? "end" : "start", x: left ? x - (w * k) / 2 - gap : x + (w * k) / 2 + gap, clipped: one.y - (one.lines.length * line) / 2 < margin - 0.5 });
    }
  }
  return { x, y, size, labels };
}

/**
 * The shapes of a library line (useFault gives it no fault), as {shapes, lines}: an object as one group of its layers with its part labels at
 * the sides, or an element as one group of the shapes of its draw rule. lines are app lines before the drawing: a mark that is a formula of
 * $/v becomes the value $/KEYm once, so each layer reads it with no copy of the formula. lang picks the names of the parts, and picture is
 * [width, height] of the drawing, for the places of the labels.
 */
function useShapes(tree, lang, picture = [300, 300], key = "s") {
  const [name, x, y, size, numbers] = tree.args, found = entryOf(name.v), { label, mark: given, parts, turn, color, visible } = tree.named;
  const shown = visible ? `, visible=${show(visible)}` : "", [X, Y, S] = [x, y, size].map(term), lines = [];
  if (found.kind === "element") {
    // A number that is a formula becomes the value $/KEYa, $/KEYb, … once (fix list 2, G2): a draw rule writes each number into many shapes.
    const entry = found.entry, values = numbers?.items ?? [], n = entry.numbers.map((_, index) => {
      const value = values[index];
      if (!value) return String(entry.defaults[index]);
      if (numberOf(value) !== undefined || value.k === "path") return term(value);
      const name = `$/${key}${"abc"[index]}`;
      lines.push(`${name} := ${show(value)}`);
      return name;
    });
    const c = color ? show(namedColors(color)) : JSON.stringify(entry.color), u = numberOf(size) !== undefined ? String(fixed(100 / numberOf(size))) : `(100 / ${S})`;
    // An element that an angle turns (entry.turn) adds turn= of the line to it.
    const turned = [entry.turn !== undefined ? n[entry.turn] : null, turn ? `(${show(turn)})` : null].filter((one) => one && one !== "0").join(" + ");
    const shapes = [tidy(`group(${X}, ${Y}, ${S} / 100, [${entry.draw({ n, c, u, text: entry.text && label ? show(label) : undefined }).join(", ")}]${turned ? `, turn=${turned}` : ""}${shown})`)];
    if (label && !entry.text) {
      const [lx, ly, align] = entry.label ?? [0, 62, "center"];
      shapes.push(tidy(`text(${X} + ${S} * ${lx / 100}, ${Y} + ${S} * ${ly / 100}, ${show(label)}, "ink", 13${align === "center" ? "" : `, align=${JSON.stringify(align)}`}${shown})`));
    }
    return { shapes, lines };
  }
  const entry = found.entry, [, h] = entry.box, list = entry.parts ?? [];
  const fixedMark = isText(given) ? list.find((part) => part.id === given.v || Object.values(part.names).includes(given.v)) : null;
  // A mark that is a formula becomes the id of its part once: $/KEYn is its text in lower case, and $/KEYm the id of the part that it names, so
  // each layer compares one value with one id and the source stays small.
  const mark = given && !fixedMark ? `$/${key}m` : null;
  if (mark) {
    const raw = `$/${key}n`, names = list.filter((part) => partName(part, lang).toLowerCase() !== part.id);
    lines.push(`${raw} := lower(${show(given)})`, `${mark} := ${names.reduceRight((rest, part) => `(${raw} == ${JSON.stringify(partName(part, lang).toLowerCase())} ? ${JSON.stringify(part.id)} : ${rest})`, raw)}`);
  }
  const marked = (part) => (!given || !part ? "false" : fixedMark ? String(fixedMark === part) : `${mark} == ${JSON.stringify(part.id)}`);
  const choose = (on, yes, no) => (on === "false" ? JSON.stringify(no) : on === "true" ? JSON.stringify(yes) : `(${on} ? ${JSON.stringify(yes)} : ${JSON.stringify(no)})`);
  const shapes = [];
  // The ground shadow darkens what is under it in both themes, so it is black with an alpha: the draw path gives it, not the entry.
  if (entry.shadow) shapes.push(`path([${ellipse(entry.shadow).join(", ")}], "#40000000", smooth=true, closed=true, gradient="#00000000", radial=0)`);
  if (entry.image) {
    // An image entry (I4, I5): its picture fills a square of UNITS around the center, and its box is the part of the square that the picture
    // covers. Over the picture, a marked part gets an accent ring of its mark radius around its point. The picture keeps its colors.
    shapes.push(`image(${-UNITS / 2}, ${-UNITS / 2}, ${UNITS}, ${UNITS}, ${JSON.stringify(entry.id)})`);
    for (const part of list) {
      const on = marked(part);
      if (on !== "false") shapes.push(`arc(${part.label.join(", ")}, ${part.ring}, 0, 360, "accent", ${MARK_RIM}${on === "true" ? "" : `, visible=${on}`})`);
    }
  }
  // A marked part keeps its own colors, and each other part keeps its full opacity (F3). Under the first layer of the marked part, an accent
  // rim of MARK_RIM units follows the outline of each of its layers, so the rim shows only around the part.
  const ringed = new Set();
  for (const layer of entry.layers ?? []) {
    const part = list.find((one) => one.id === layer.part), on = marked(part);
    if (on !== "false" && !ringed.has(part)) {
      ringed.add(part);
      const when = on === "true" ? "" : `, visible=${on}`;
      shapes.push(...entry.layers.filter((one) => one.part === part.id).map((one) => rimOf(one, when)));
    }
    shapes.push(...layerShapes(layer, (step, alpha) => JSON.stringify(tone(layer.role, step, alpha)), null));
  }
  // The labels of the parts: with parts=true each part, else the marked part. Each one is a text at the side and a thin leader line to its point.
  // With a mark that is a formula and no parts=true, each label shows only while its part is the marked one. With labels, the kit places the
  // object between the two label columns (layoutOf).
  const at = [X, Y, S].map(Number), alone = mark && parts?.v !== true;
  const placed = at.every(Number.isFinite) ? layoutOf(found, tree.named, at, picture, lang) : null;
  const [GX, GY, GS] = placed?.labels.length ? [placed.x, placed.y, placed.size].map(fixed) : [X, Y, S];
  const out = [tidy(`group(${GX}, ${GY}, ${GS} / ${UNITS}, [${shapes.join(", ")}]${turn ? `, turn=${show(turn)}` : ""}${shown})`)];
  for (const one of placed?.labels ?? []) {
    const ink = alone ? '"accent"' : choose(marked(one.part), "accent", "ink"), end = one.align === "end" ? one.x + 3 : one.x - 3, [px, py, ty] = [one.px, one.py, one.y].map(fixed);
    const seen = alone ? `, visible=${visible ? `(${show(visible)}) && ` : ""}${marked(one.part)}` : shown;
    out.push(`line(${px}, ${py}, ${fixed(end)}, ${ty}, "ink@55", 1${seen})`, `circle(${px}, ${py}, 2.5, ${ink}${seen})`,
      ...one.lines.map((text, index) => `text(${fixed(one.x)}, ${fixed(one.y + (index - (one.lines.length - 1) / 2) * LABEL.line)}, ${JSON.stringify(text)}, ${ink}, ${LABEL.size}, align=${JSON.stringify(one.align)}${seen})`));
  }
  if (label) out.push(tidy(`text(${GX}, ${GY} + ${GS} * ${h / 200} + 12, ${show(label)}, "ink", 13${shown})`));
  return { shapes: out, lines };
}

/** The width of the accent rim of a marked part, in the units of its entry (F3). */
const MARK_RIM = 3;
/** The accent rim of one layer of a marked part: a stroke of its outline, MARK_RIM wider at each side than its fill or its line. when is its visible= text. */
function rimOf(layer, when) {
  const width = layer.line ? (layer.width ?? 1.4) + 2 * MARK_RIM : 2 * MARK_RIM;
  if (layer.circle) return `arc(${layer.circle.join(", ")}, 0, 360, "accent", ${width}${when})`;
  const points = layer.ellipse ? ellipse(layer.ellipse) : layer.path;
  return `path([${points.join(", ")}], "accent", ${width}, smooth=${Boolean(layer.ellipse || layer.smooth)}, closed=${!(layer.line && layer.open)}, stroke=true${when})`;
}

/** The number of path segments that an entry draws: each point of a path or an ellipse, again for its outline (L5 limit). An image entry draws none. */
const segmentsOfEntry = (entry) => (entry.layers ?? []).reduce((sum, layer) => {
  const points = layer.circle ? 0 : layer.ellipse ? 8 : layer.path.length / 2;
  return sum + (layer.line ? 0 : points) + (layer.outline !== undefined || layer.line ? points : 0);
}, 0);

return { UNITS, SMALLEST, LEAST, SEGMENTS, USE_NAMED, LIBRARY, entryOf, partName, missOf, useFault, LABEL, lineWidth, labelLines, layoutOf, useShapes, segmentsOfEntry };
})();
// form-scene.js
const { SCENE_FORM } = (() => {
// The template scene (docs/superpowers/plans/2026-11-22-one-scene.md, decision 1): a picture of the shapes s1 to s20, one slider or choice,
// and a card with a text. The kit draws each shape as it is, and a library line as the shapes of its element or object (library.js). The
// shape kinds and their properties come from the registry of kinds.js, so a new kind needs no edit here.






const CONTROL = "slider(LABEL, MIN, MAX, STEP, START) with numbers, choice(LABEL, [OPTION, …], START), or steps(LABEL, [STEP, …], ANSWER)";

/** The form of scene (forms.js TemplateForm). */
const SCENE_FORM = {
  word: "scene",
  part: new RegExp(`^s(?:${Array.from({ length: BUDGET.shapes }, (_, index) => index + 1).join("|")})$`),
  lines: `control, s1 to s${BUDGET.shapes} (at most ${BUDGET.shapes} shapes), and card`,
  control: CONTROL,
  /** The fault of a control line. The kit checks the bounds and the start of a slider and of a choice when it compiles the expansion. */
  controlFault(tree) {
    const args = tree.args ?? [], form = `the control of scene is ${CONTROL}`;
    if (tree.k !== "call" || Object.keys(tree.named).length) return form;
    if (tree.name === "slider") {
      if (args.length !== 5 || args.slice(1).some((arg) => numberOf(arg) === undefined)) return form;
    } else if (tree.name === "choice" || tree.name === "steps") {
      // A steps control is a choice whose start is its answer step, by default the last step (decision 3).
      const options = args[1]?.items, what = tree.name === "steps" ? "steps" : "options";
      if (args.length < 2 || args.length > 3 || !options || (args[2] && !isText(args[2]))) return form;
      if (options.length > BUDGET.options) return `the ${tree.name} control has ${options.length} ${what}, and its cap is ${BUDGET.options}. Keep the ones that matter most`;
      if (options.length < 2) return `a ${tree.name} control needs at least 2 ${what}`;
      const fault = optionsFault(tree.name === "steps" ? "step" : "option", options);
      if (fault) return fault;
    } else return form;
    return textFault("the label", args[0], BUDGET.control);
  },
  partFault(tree, lang) {
    if (tree.k === "call" && tree.name === "use") return useFault(tree, [WIDTH, HEIGHT], lang ?? "en");
    const kinds = Object.keys(KINDS).filter((name) => KINDS[name].prompt !== null), kind = tree.k === "call" && kinds.includes(tree.name) ? KINDS[tree.name] : null;
    if (!kind) return `a shape is ${kinds.join(", ")}, or use(NAME, X, Y, SIZE) of the library`;
    const named = [...Object.keys(PROPERTIES).filter((key) => PROPERTIES[key].prompt !== null), ...(kind.named ?? [])], other = Object.keys(tree.named).find((key) => !named.includes(key));
    if (other) return `a shape takes only ${named.map((key) => `${key}=`).join(", ")} by name, and ${other}= is not one`;
    const text = kind.args.indexOf("text");
    if (text >= 0) return textFault("the text", tree.args[text], BUDGET.text, true, INSIDE);
    // The kit compiles a shorter or an odd list clean: it draws nothing for too few points and drops the last number of an odd list.
    const points = kind.points && tree.args[0]?.k === "list" ? tree.args[0].items.length : 2 * (kind.points ?? 0);
    if (kind.points && (points < 2 * kind.points || points % 2)) return `the points of a ${tree.name} are a list of x and y numbers for at least ${kind.points} points (${2 * kind.points} numbers), and this list has ${points} numbers`;
    return null;
  },
  /**
   * The shapes of the drawing: each shape as it is, and each library line as its shapes, with the part names in the language of the first
   * line. A library line can add app lines before the drawing (library.js useShapes).
   */
  expand(parts, control, head) {
    const used = parts.map((part) => (part.tree.k === "call" && part.tree.name === "use" ? useShapes(part.tree, head.named.lang?.v ?? "en", [WIDTH, HEIGHT], part.name) : { shapes: [show(part.tree)], lines: [] }));
    return { shapes: used.flatMap((one) => one.shapes), lines: used.flatMap((one) => one.lines) };
  },
  cardFault: (tree) => textCardFault(tree, "scene"),
  /** A scene whose first line and control alone have faults gives them to control (template.js ownersOf). */
  owners(owners, alone, parts, add) {
    if (!owners.has("control")) alone(parts).forEach((fault) => add("control", fault));
  },
};

return { SCENE_FORM };
})();
// form-items.js
const { ITEMS_FORM } = (() => {
// The template items (docs/superpowers/plans/2026-11-22-one-scene.md, decision 1): the items i1 to i6, a choice of their labels, and a card
// that shows the text of the chosen item. The drawing shows the sign and the label of the chosen item.




const CONTROL = "choice(LABEL), or steps(LABEL, ANSWER): its options are the labels of the items";

/** The form of items (forms.js TemplateForm). */
const ITEMS_FORM = {
  word: "items",
  part: /^i[1-6]$/,
  lines: "control, i1 to i6 (at most 6 items), and card",
  control: CONTROL,
  controlFault(tree) {
    const args = tree.args ?? [], form = `the control of items is ${CONTROL}`;
    if (tree.k !== "call" || Object.keys(tree.named).length) return form;
    if (!(tree.name === "choice" && args.length === 1 || tree.name === "steps" && args.length <= 2 && args.length >= 1 && (!args[1] || isText(args[1])))) return form;
    return textFault("the label", args[0], BUDGET.control);
  },
  partFault(tree) {
    if (tree.k !== "call" || tree.name !== "item" || tree.args.length !== 3 || Object.keys(tree.named).length) return "an item is item(LABEL, SIGN, TEXT)";
    return textFault("the label", tree.args[0], BUDGET.fewOption) ?? textFault("the sign", tree.args[1], BUDGET.sign, false, INSIDE) ?? textFault("the text", tree.args[2], BUDGET.cardText);
  },
  /** The labels are the options of the choice, so their cap follows the number of items and the control (optionFault), which only the whole file knows. */
  context(lines) {
    const items = lines.filter((line) => line.tree && ITEMS_FORM.part.test(line.name));
    return items.flatMap((line) => [optionFault("the label", line.tree.args[0], items.length, "item")].filter(Boolean).map((fault) => [line.name, fault]));
  },
  cardFault(tree) {
    if (tree.k !== "call" || tree.name !== "card" || tree.args.length !== 1 || Object.keys(tree.named).length) return "the card of items is card(TITLE): it shows the text of the chosen item";
    return textFault("the card title", tree.args[0], BUDGET.cardTitle, true);
  },
  /** The list of the items and the chosen item, the drawing of its sign and label, the labels as the options of the choice, and its text on the card. */
  expand(parts) {
    // The sign is large when it is one or two characters, and smaller for a word, so a sign at 1.25 times its cap fits the 300 px of the drawing.
    // A preview with no item yet draws nothing, so the first item only adds to the picture.
    return {
      lines: [`$/items := [${parts.map(({ tree: { args: [label, sign, text] } }) => `{label: ${show(label)}, sign: ${show(sign)}, text: ${show(text)}}`).join(", ")}]`, "$/now := find($/items, $label == $/v)"],
      shapes: parts.length ? ['text(150, 95, $/now/sign, "accent", len($/now/sign) <= 2 ? 72 : (len($/now/sign) <= 6 ? 44 : 24))', 'text(150, 190, $/v, "ink", 20)'] : [],
      options: { k: "list", items: parts.map((part) => part.tree.args[0]) },
      cardText: "$/now/text",
    };
  },
  /**
   * The start of $/v: the answer of a steps control, by default the label of the last item (decision 3), else the label of the first item,
   * or 0 with no item.
   */
  start: (parts, control) => (control?.name === "steps" ? control.args[1] ?? parts.at(-1)?.tree.args[0] : parts[0]?.tree.args[0]) ?? { k: "lit", v: 0 },
};

return { ITEMS_FORM };
})();
// form-cells.js
const { CELLS_FORM } = (() => {
// The template cells (docs/superpowers/plans/2026-11-22-one-scene.md, decision 1): a slider of a count of cells and a card with a text.
// The control draws the cells, so a cells file has no part line.




const CONTROL = "slider(LABEL, 0, COUNT, 1, START)";

/** The form of cells (forms.js TemplateForm). It has no partFault, because its part names match no line. */
const CELLS_FORM = {
  word: "cells",
  part: /(?!)/,
  lines: "control and card",
  control: CONTROL,
  controlFault(tree) {
    const args = tree.args ?? [], form = `the control of cells is ${CONTROL}`;
    if (tree.k !== "call" || Object.keys(tree.named).length) return form;
    if (tree.name !== "slider") return form;
    if (args.length !== 5 || args.slice(1).some((arg) => numberOf(arg) === undefined)) return form;
    const count = numberOf(args[2]);
    if (numberOf(args[1]) !== 0 || numberOf(args[3]) !== 1) return form;
    if (!(Number.isInteger(count) && count >= 2 && count <= BUDGET.cells)) return `COUNT is a whole number from 2 to ${BUDGET.cells}, and ${show(args[2])} is not one`;
    return textFault("the label", args[0], BUDGET.control);
  },
  cardFault: (tree) => textCardFault(tree, "cells"),
  /** The drawing of the cells of a control line that the kit can read, else the parts as they are. */
  expand(parts, control) {
    if (control?.k !== "call") return {};
    // Rows of at most ten cells, filled evenly, in the top 176 px, and the caption "K / N = P%" under them. One row of few cells gets taller cells.
    const count = numberOf(control.args[2]), rows = Math.ceil(count / 10), cols = Math.ceil(count / rows);
    const pitch = rows > 1 ? Math.min(Math.floor(280 / cols), Math.floor(176 / rows)) : Math.min(56, Math.floor(280 / cols)), side = pitch - 4, tall = rows > 1 ? side : Math.min(60, Math.round(side * 1.4));
    const x0 = Math.round((WIDTH - (cols * pitch - 4)) / 2), y0 = 8 + Math.round((176 - ((rows - 1) * pitch + tall)) / 2);
    return { shapes: [`each(${count}, rect(${x0} + ($index % ${cols}) * ${pitch}, ${y0} + floor($index / ${cols}) * ${pitch}, ${side}, ${tall}, $index < $/v ? "accent" : "muted", 3))`,
      `text(150, 202, text($/v) + " / ${count} = " + text(round($/v / ${count} * 100)) + "%", "ink", 18)`] };
  },
};

return { CELLS_FORM };
})();
// form-data.js
const { DATA_WORDS, DATA_HEAD, DATA_BUDGET, cutLabel, cutLabels, cellText, rowFault, markFault, contextFaults, sourceFault, dataFile, tableLine, markStart, sentences, numbersIn, checkedValues, rejectFile } = (() => {
// What the forms of the data family use (docs/superpowers/plans/2026-11-22-block-2-forms-library-motion.md, stage 5): compare, table, chart,
// and nodata. Their call starts after the routing call, with the section "Template data" of prompt.md. A data form shows its rows or points
// in a native table or chart, and the marked row or point is the value $/v: a tap on a row or a point moves it (decision 2). The host check
// of decision 3 (checkData) compares each number of a searched form with the source excerpts.




/** The words of the data family. */
const DATA_WORDS = new Set(["compare", "table", "chart", "nodata"]);
/** The first line of a data form, as the host tells it (prompt.json dataHead): a spoken change of such a screen is a new data screen. */
const DATA_HEAD = new RegExp(`^\\s*(?:${[...DATA_WORDS].join("|")})\\s*\\(`);
/**
 * The caps of the data forms, in characters and items. A label (a row label, a thing name, a column head, a point label) past its cap is cut
 * with "…", because a cut label keeps its meaning. A value past its cap is a fault, because a cut number is a wrong number.
 */
const DATA_BUDGET = { things: 3, compareRows: 6, columns: 4, tableRows: 12, visibleRows: 8, points: 8, label: 16, value: 12, sign: 4, nodataSentences: 2 };

/** A label past cap: its first cap - 1 characters and "…". */
const cutLabel = (text, cap = DATA_BUDGET.label) => ([...text].length > cap ? [...text].slice(0, cap - 1).join("") + "…" : text);
/** The node of a label cut to its cap, or the node itself. */
const cutNode = (node) => (isText(node) && [...node.v].length > DATA_BUDGET.label ? { ...node, v: cutLabel(node.v) } : node);
/** The tree of a call with the labels at the indexes of at cut to their cap, and each text of the list at index list cut too (a head). */
function cutLabels(tree, at, list) {
  if (tree.k !== "call") return tree;
  return { ...tree, args: tree.args.map((arg, index) => (at.includes(index) ? cutNode(arg) : index === list && arg?.k === "list" ? { ...arg, items: arg.items.map(cutNode) } : arg)) };
}

/** The text of a value of a cell: a text in quotes as it is, or a number. Undefined for each other node. */
const cellText = (node) => (isText(node) ? node.v : numberOf(node) !== undefined ? String(numberOf(node)) : undefined);

/** The fault of a label: a text in quotes that is not empty. A long one is cut (cutLabels), so it has no fault. */
const labelFault = (what, node) => (!isText(node) ? `${what} is a text in quotes` : !node.v.trim() ? `${what} is empty` : null);

/** The fault of the line row(LABEL, [VALUE, …]): a label and 1 to max values, each a text in quotes of at most DATA_BUDGET.value characters or a number. */
function rowFault(tree, max) {
  if (tree.k !== "call" || tree.name !== "row" || tree.args.length !== 2 || Object.keys(tree.named).length || tree.args[1]?.k !== "list") return "a row is row(LABEL, [VALUE, …])";
  const values = tree.args[1].items;
  if (!values.length || values.length > max) return `a row has 1 to ${max} values, and this one has ${values.length}`;
  const wrong = values.map((value, index) => (cellText(value) === undefined ? `value ${index + 1} is a text in quotes or a number` : !cellText(value).trim() ? `value ${index + 1} is empty` : null)).find(Boolean);
  // Each value over the cap in one fault (fix list 5, L1), a number by its text.
  return labelFault("the label", tree.args[0]) ?? wrong ?? labelsFault("value", values.map((value) => ({ k: "lit", v: cellText(value) })), DATA_BUDGET.value,
    () => ", such as with a unit in the label");
}

/** The fault of the control mark(ROW) of a form, or mark(ROW, COLUMN) when column is true. ROW and COLUMN name a row or a column of the form. */
function markFault(tree, column = false) {
  const form = column ? "mark(ROW) or mark(ROW, COLUMN)" : "mark(LABEL)";
  if (tree.k !== "call" || tree.name !== "mark" || Object.keys(tree.named).length || !tree.args.length || tree.args.length > (column ? 2 : 1)) return `the control of this template is ${form}: it marks one row or point`;
  return tree.args.map((arg) => labelFault("the mark", arg)).find(Boolean) ?? null;
}

/**
 * The faults that need more than one line, as [name, message]: the mark must name a row (or a point) and a column of the form, and each row
 * needs one value for each column. labels are the labels that the mark can name, count is the count of values of each row, and columns are the
 * heads that a cell mark can name (the heads after the first one).
 */
function contextFaults(lines, labels, count, columns = []) {
  const out = [], mark = lines.find((line) => line.name === "control" && line.tree?.k === "call"), labelled = lines.filter((one) => one.tree && ["row", "point"].includes(one.tree.name));
  for (const line of labelled.filter((one) => one.tree.name === "row" && count !== undefined && one.tree.args[1].items.length !== count)) {
    out.push([line.name, `this row has ${line.tree.args[1].items.length} values, and the form has ${count} columns of values. Give each row one value for each column`]);
  }
  // The label of a row or a point is its id, and a tap or a spoken mark names it, so each one is different.
  labelled.forEach((line, index) => {
    const twin = labelled.slice(0, index).find((one) => one.tree.args[0].v === line.tree.args[0].v);
    if (twin) out.push([line.name, `the label ${show(line.tree.args[0])} is the label of ${twin.name} too. Each label is different`]);
  });
  const [row, column] = mark?.tree.args ?? [];
  if (row && !labels.includes(cutLabel(row.v))) out.push(["control", `the mark ${show(row)} names no row. Name the label of one row, such as ${show({ k: "lit", v: labels[0] ?? "" })}`]);
  else if (column && !columns.includes(cutLabel(column.v))) out.push(["control", `the column ${show(column)} names no column of values. Name one of ${columns.map((text) => JSON.stringify(text)).join(", ")}`]);
  return out;
}

/** The fault of the host line source(SITE, TIME): two texts in quotes. */
function sourceFault(tree) {
  if (tree.k !== "call" || tree.name !== "source" || tree.args.length !== 2 || Object.keys(tree.named).length) return "the source is source(SITE, TIME), and only the host writes it";
  return tree.args.map((arg, index) => textFault(index ? "the time" : "the site", arg, 100)).find(Boolean) ?? null;
}

/**
 * The app lines of a data form: the mark $/v, the main component, the card, the source, and the root in that order. main is the line of the
 * table or the chart, or null for nodata. A form with no source line shows none.
 */
function dataFile(head, main, start, card, source, icon) {
  const title = show(head.args[0]), out = [`app(${title}, lang=${show(head.named.lang)}, preset="clean", palette=${show(head.named.palette ?? { k: "lit", v: "blue" })})`];
  if (main) out.push(`$/v = ${show(start)}`, `data_view = ${main}`);
  if (card) out.push(`need card = card(${show(card.args[0])}, ${show(card.args[1])}${icon ? `, icon=${show({ k: "lit", v: icon })}` : ""})`);
  // The source line is one small muted line with a link icon (stage 5 look).
  if (source) out.push(`data_source = Text(${show({ k: "lit", v: `${source.args[0].v} · ${source.args[1].v}` })}, variant="source")`);
  const top = [main && "data_view", card && "card_view", source && "data_source"].filter(Boolean);
  // The first line alone is a frame of the stream (the preview step): its screen holds one divider under the title.
  if (!top.length) out.push("data_wait = Divider()");
  out.push(`root = Column([${(top.length ? top : ["data_wait"]).join(", ")}])`);
  return out.join("\n") + "\n";
}

/**
 * The line Table(TITLE, COLUMNS, ROWS, $/v) of the table of a form, with column= for a marked cell (its index among the columns, from 0).
 * signs= makes the table a comparison: one sign for each thing, "" for none (stage 5 look).
 */
function tableLine(head, columns, rows, column, signs) {
  const list = (items) => `[${items.map((text) => show({ k: "lit", v: text })).join(", ")}]`;
  // A value that is a whole number of 5 or more digits shows a thin space between groups of three ("12 742"). The file keeps the value.
  // Only a data form does this: in another app, such digits can be a code. The label of a row is its id, so it stays as written.
  const shown = ([label, ...values]) => [label, ...values.map((text) => text.replace(/^([-−]?)(\d{5,})$/, (all, sign, digits) => sign + digits.replace(/\B(?=(\d{3})+$)/g, " ")))];
  return `Table(${show(head.args[0])}, ${list(columns)}, [${rows.map(shown).map(list).join(", ")}], $/v${column > 0 ? `, column=${column}` : ""}${signs ? `, signs=${list(signs)}` : ""})`;
}

/** The value that $/v keeps: the label of the marked row, or "" for no mark. */
const markStart = (control) => (control?.k === "call" && isText(control.args[0]) ? { k: "lit", v: cutLabel(control.args[0].v) } : { k: "lit", v: "" });

/** The text cut to its first count sentences, or the text itself. */
function sentences(text, count) {
  let seen = 0;
  for (let end = 1; end <= text.length; end++) {
    if (SENTENCE_END.test(text.slice(0, end)) && (end === text.length || /\s/u.test(text[end])) && ++seen === count) return text.slice(0, end);
  }
  return text;
}

// The host check of decision 3: each number of a data cell or of a point of a searched form is in the source excerpts.

/**
 * The numbers of a text by their digits: a decimal comma equals a decimal point, and a space or a comma between groups of three digits is
 * dropped, so "12 742", "12,742", and "12742" are one number, and "3,5" is 3.5. A score such as "2:1" is its two numbers. The sign is not read:
 * "−5" and "5" are one number. Each number is its value as a text, such as "3.5", so "3.50" and "3.5" are one number.
 */
function numbersIn(text) {
  const out = [], digits = /\d+(?:[    ,]\d{3}(?!\d))*(?:[.,]\d+)?/gu;
  for (const [token] of String(text).matchAll(digits)) {
    const plain = token.replace(/[    ]/gu, "").replace(/,(?=\d{3}(?!\d))/gu, "").replace(",", ".");
    out.push(String(Number(plain)));
  }
  return out;
}

/** The values of the cells and points of a data form that the host check reads: no label, no head, and no column that only counts the rows. */
function checkedValues(read) {
  const rows = read.lines.filter((line) => line.tree?.k === "call" && line.tree.name === "row").map((line) => line.tree.args[1].items.map(cellText));
  const points = read.lines.filter((line) => line.tree?.k === "call" && line.tree.name === "point").map((line) => cellText(line.tree.args[1]));
  // A column whose values are 1, 2, 3, … in the order of the rows is a rank that the model adds, not a data value.
  const width = Math.max(0, ...rows.map((row) => row.length));
  const rank = (index) => rows.length > 1 && rows.every((row, at) => numbersIn(row[index] ?? "").join() === String(at + 1));
  const cells = [...Array(width).keys()].filter((index) => !rank(index)).flatMap((index) => rows.map((row) => row[index] ?? ""));
  return [...cells, ...points].filter((text) => text !== undefined);
}

/** The words of the nodata card that the host check writes, by the language of the form. Each other language gets en. */
const REJECT = {
  ru: ["Нет точных данных", (value) => `Источники не подтверждают значение ${value}. Скажи «найди ещё раз» или спроси по-другому.`],
  en: ["No exact data", (value) => `The sources do not hold the value ${value}. Say «search again» or ask another way.`],
};
/** The nodata file of a form whose value is in no excerpt: the title of the form, the reason, and the same sentences for the voice. */
function rejectFile(head, value) {
  const [title, reason] = REJECT[head.named.lang?.v] ?? REJECT.en, text = reason(value);
  const palette = head.named.palette ? `, palette=${show(head.named.palette)}` : "";
  return `nodata(${show(head.args[0])}, lang=${show(head.named.lang ?? { k: "lit", v: "en" })}${palette})\ncard = card(${show({ k: "lit", v: title })}, ${show({ k: "lit", v: text })})\nsay = ${JSON.stringify(text)}\n`;
}

return { DATA_WORDS, DATA_HEAD, DATA_BUDGET, cutLabel, cutLabels, cellText, rowFault, markFault, contextFaults, sourceFault, dataFile, tableLine, markStart, sentences, numbersIn, checkedValues, rejectFile };
})();
// form-compare.js
const { COMPARE_FORM } = (() => {
// The template compare of the data family (block 2 plan, D2): two or three things side by side, each with a name and a sign, up to 6 rows
// with one value for each thing, one marked row, and a card with the point. The kit shows it as a native table with signs= (a comparison):
// the host draws a head cell for each thing and a row block with a bar under each value (stage 5 look).




const THING = /^t[1-3]$/;

/** The form of compare (forms.js TemplateForm). */
const COMPARE_FORM = {
  word: "compare",
  family: "data",
  part: /^(?:t[1-3]|r[1-6])$/,
  lines: "control, t1 to t3 (2 or 3 things), r1 to r6 (at most 6 rows), and card",
  control: "mark(LABEL): LABEL is the label of the marked row",
  controlFault: (tree) => markFault(tree),
  partFault(tree) {
    if (tree.k === "call" && tree.name === "row") return rowFault(tree, DATA_BUDGET.things);
    if (tree.k !== "call" || tree.name !== "thing" || !tree.args.length || tree.args.length > 2 || Object.keys(tree.named).length) return "a thing is thing(NAME, SIGN): its name and a sign, such as an emoji";
    const [name, sign] = tree.args;
    if (!isText(name) || !name.v.trim()) return "the name is a text in quotes";
    if (sign && !(isText(sign) && [...sign.v].length <= DATA_BUDGET.sign)) return `the sign is a text in quotes of at most ${DATA_BUDGET.sign} characters, such as an emoji`;
    return null;
  },
  cardFault: (tree) => textCardFault(tree, "compare"),
  cut: (tree) => cutLabels(tree, tree.name === "thing" || tree.name === "row" ? [0] : []),
  context: (lines) => contextFaults(lines, lines.filter((line) => line.tree?.name === "row").map((line) => line.tree.args[0].v),
    lines.filter((line) => THING.test(line.name) && line.tree).length || undefined),
  start: (parts, control) => markStart(control),
  whole: (parts) => (parts.filter((part) => THING.test(part.name)).length < 2 ? "compare: the form needs 2 or 3 things with no fault" : null),
  file(head, parts, control, card, source) {
    const things = parts.filter((part) => THING.test(part.name)).map(({ tree: { args: [name, sign] } }) => [cutLabel(name.v), sign?.v ?? ""]);
    const rows = parts.filter((part) => !THING.test(part.name)).map(({ tree: { args: [label, values] } }) => [label.v, ...values.items.map(cellText)]);
    return dataFile(head, things.length ? tableLine(head, [" ", ...things.map(([name]) => name)], rows, -1, things.map(([, sign]) => sign)) : null, markStart(control), card, source);
  },
};

return { COMPARE_FORM };
})();
// form-table.js
const { TABLE_FORM } = (() => {
// The template table of the data family (block 2 plan, D3): a head of 2 to 4 columns, up to 12 rows, one marked row or cell, and a card
// with the caption. The native table shows 8 rows (6 on a compact width), and more rows scroll inside its box (ToolUiCatalog visibleRows).




const heads = (lines) => lines.find((line) => line.name === "head" && line.tree)?.tree.args[0].items.map((item) => item.v) ?? [];

/** The form of table (forms.js TemplateForm). */
const TABLE_FORM = {
  word: "table",
  family: "data",
  part: /^(?:head|r(?:[1-9]|1[0-2]))$/,
  lines: "control, head, r1 to r12 (at most 12 rows), and card",
  control: "mark(ROW) or mark(ROW, COLUMN): ROW is the label of the marked row, and COLUMN is the head of the marked cell",
  controlFault: (tree) => markFault(tree, true),
  partFault(tree) {
    if (tree.k === "call" && tree.name === "row") return rowFault(tree, DATA_BUDGET.columns - 1);
    if (tree.k !== "call" || tree.name !== "head" || tree.args.length !== 1 || Object.keys(tree.named).length || tree.args[0]?.k !== "list") return "the head is head([COLUMN, …]): the first column is the head of the row labels";
    const items = tree.args[0].items;
    if (items.length < 2 || items.length > DATA_BUDGET.columns) return `the head has 2 to ${DATA_BUDGET.columns} columns, and this one has ${items.length}. Keep the columns that matter most`;
    return items.map((item, index) => (!isText(item) || !item.v.trim() ? `column ${index + 1} is a text in quotes` : null)).find(Boolean) ?? null;
  },
  cardFault: (tree) => textCardFault(tree, "table"),
  cut: (tree) => cutLabels(tree, tree.name === "row" ? [0] : [], tree.name === "head" ? 0 : -1),
  context(lines) {
    const head = heads(lines);
    return contextFaults(lines, lines.filter((line) => line.tree?.name === "row").map((line) => line.tree.args[0].v), head.length ? head.length - 1 : undefined, head.slice(1));
  },
  start: (parts, control) => markStart(control),
  whole: (parts) => (parts.some((part) => part.name === "head") ? null : "table: the form needs the line head with no fault"),
  file(head, parts, control, card, source) {
    const columns = parts.find((part) => part.name === "head")?.tree.args[0].items.map((item) => item.v) ?? [];
    const rows = parts.filter((part) => part.name !== "head").map(({ tree: { args: [label, values] } }) => [label.v, ...values.items.map(cellText)]);
    const column = isText(control?.args?.[1]) ? columns.indexOf(cutLabel(control.args[1].v)) : -1;
    return dataFile(head, columns.length ? tableLine(head, columns, rows, column) : null, markStart(control), card, source);
  },
};

return { TABLE_FORM };
})();
// form-chart.js
const { CHART_FORM } = (() => {
// The template chart of the data family (block 2 plan, D4): 2 to 8 points with a label and a number each, as bars or a line, one marked
// point, and a card with the caption. The native chart shows a label and a value at each point (ToolUiCatalog).





const KINDS = ["bar", "line"];

/** The form of chart (forms.js TemplateForm). */
const CHART_FORM = {
  word: "chart",
  family: "data",
  head: "kind",
  part: /^p[1-8]$/,
  lines: "control, p1 to p8 (at most 8 points), and card",
  control: "mark(LABEL): LABEL is the label of the marked point",
  headFault: (tree) => (tree.named.kind && !(isText(tree.named.kind) && KINDS.includes(tree.named.kind.v)) ? `kind= is "bar" or "line", and ${show(tree.named.kind)} is not one` : null),
  controlFault: (tree) => markFault(tree),
  partFault(tree) {
    if (tree.k !== "call" || tree.name !== "point" || tree.args.length !== 2 || Object.keys(tree.named).length) return "a point is point(LABEL, NUMBER)";
    if (!isText(tree.args[0]) || !tree.args[0].v.trim()) return "the label is a text in quotes";
    return numberOf(tree.args[1]) === undefined ? "the value of a point is a number with no quotes and no unit, such as 88" : null;
  },
  cardFault: (tree) => textCardFault(tree, "chart"),
  cut: (tree) => cutLabels(tree, tree.name === "point" ? [0] : []),
  context: (lines) => contextFaults(lines, lines.filter((line) => line.tree?.name === "point").map((line) => line.tree.args[0].v)),
  start: (parts, control) => markStart(control),
  whole: (parts) => (parts.length < 2 ? "chart: the form needs 2 points with no fault" : null),
  file(head, parts, control, card, source) {
    const points = parts.map(({ tree: { args: [label, value] } }) => `{label: ${show(label)}, value: ${numberOf(value)}}`);
    return dataFile(head, points.length ? `Chart([${points.join(", ")}], $label, $value, label=${show(head.args[0])}, kind=${show(head.named.kind ?? { k: "lit", v: "bar" })}, bind=$/v)` : null,
      markStart(control), card, source);
  },
};

return { CHART_FORM };
})();
// form-nodata.js
const { NODATA_FORM } = (() => {
// The template nodata of the data family (block 2 plan, D5): a short card with what is missing and what the person can say next, at most
// 2 sentences. The voice says the same sentences: a file with no narration line gets say with the text of its card (template.js).




/** The form of nodata (forms.js TemplateForm). It has no part and no control. */
const NODATA_FORM = {
  word: "nodata",
  family: "data",
  part: /(?!)/,
  lines: "card",
  control: "no control",
  controlFault: () => "nodata has no control",
  cardFault: (tree) => textCardFault(tree, "nodata"),
  /** The card text keeps its first 2 sentences. */
  cut: (tree) => (tree.k === "call" && tree.name === "card" && tree.args[1]?.k === "lit" && typeof tree.args[1].v === "string"
    ? { ...tree, args: [tree.args[0], { ...tree.args[1], v: sentences(tree.args[1].v, DATA_BUDGET.nodataSentences) }] } : tree),
  start: () => ({ k: "lit", v: "" }),
  whole: (parts, lines, drawn) => (drawn("card") ? null : "nodata: the form needs a card with no fault"),
  // The card has a search icon at the left of its title (stage 5 look).
  file: (head, parts, control, card, source) => dataFile(head, null, null, card, source, "Search"),
};

return { NODATA_FORM };
})();
// forms.js
const { FORMS_BY_WORD } = (() => {
// The template forms by their word (docs/superpowers/plans/2026-11-22-one-scene.md, decision 1). A new form is a module of its own and its
// entry here. template.js reads, merges, expands, and builds a template file with the form of its first line.









/**
 * @typedef {object} TemplateForm
 * @property {string} word
 * @property {"data"} [family]  a form of the data family (form-data.js): it has the host line source, and its own app file (file)
 * @property {RegExp} part   the names of its part lines
 * @property {string} lines  its line text for a fault
 * @property {string} control  its control forms for a fault
 * @property {(tree: object) => string | null} controlFault  the fault of its control line
 * @property {(tree: object) => string | null} [partFault]  the fault of a part line. A form whose part names match no line has none.
 * @property {(tree: object) => string | null} cardFault  the fault of its card line
 * @property {(parts: object[], control?: object) => {lines?: string[], shapes?: string[], options?: object, cardText?: string}} [expand]
 *   what the expansion of template.js takes from the form: app lines before the drawing, the shapes of the drawing, the options of a
 *   choice, and the card text. Each one that it leaves out keeps the default: no line, the parts as shapes, the options of the control,
 *   and the text of the card line.
 * @property {(parts: object[]) => object} [start]  the value that $/v keeps when the control is left out. Without it, the start of the control.
 * @property {(owners: Map<string, string[]>, alone: (names: string[]) => string[], parts: string[], add: (name: string, fault: string) => void) => void} [owners]
 *   the owners of the faults of the compile of an expansion that the form gives before the parts (template.js ownersOf).
 * @property {string} [head]  a named argument of the first line beyond lang= and palette=, which headFault of the form checks
 * @property {(tree: object) => string | null} [headFault]  the fault of that argument
 * @property {(tree: object, name: string) => object} [cut]  the tree of a line with each long label cut (the kept line holds the cut text)
 * @property {(lines: object[]) => [string, string][]} [context]  the faults that need more than one line, as [name, message]
 * @property {(parts: object[], lines: object[], drawn: (name: string) => boolean) => string | null} [whole]  the whole fault of a file with these parts
 * @property {(head: object, parts: object[], control?: object, card?: object, source?: object) => string} [file]  the whole app file of a data form
 */

/** @type {Record<string, TemplateForm>} */
const FORMS_BY_WORD = { scene: SCENE_FORM, items: ITEMS_FORM, cells: CELLS_FORM, compare: COMPARE_FORM, table: TABLE_FORM, chart: CHART_FORM, nodata: NODATA_FORM };

return { FORMS_BY_WORD };
})();
// template.js
const { isTemplateFile, readTemplate, mergeTemplate, checkData, expandTemplate, buildTemplate } = (() => {
// The template file of a voice screen (docs/superpowers/specs/2026-11-21-stage-templates-stream-design.md, sections 1 and 2): named lines
// in a fixed order, which the kit checks, fixes, and expands into an app file of today's kinds. compile.js branches here for a template text,
// plan, or stored app file.
//
//   scene("Почему корабль не тонет?", lang="ru", palette="blue")      the first line: the template, the title, the language, the palette
//   control = choice("Предмет", ["Корабль", "Гвоздь"])                 the one control. A part reads its value as $/v
//   s1 = rect(0, 110, 300, 110, "water", 0)                            the parts: s1 to s12 are shapes (scene), i1 to i6 items (items)
//   card = card("Вытеснение воды", "Полый корабль ...")                 the card
//   say = "Выбери предмет и смотри на воду."                           the narration: say for one sentence, say1 to sayN for steps
//
// The code of each template form is in a module of its own (forms.js), and what each form uses is in form-base.js.









const isTemplateFile = (text) => typeof text === "string" && TEMPLATE_HEAD.test(text.split("\n").find((line) => line.trim()) ?? "");

const readsV = (node) => node.k === "path" && node.abs && node.segs.length === 1 && node.segs[0] === "v";

/** A line of a template file or of a reply: {name, raw}, a first line with head, a delete as {gone}, or null for a line with no name. $/v is the line control. */
function lineOf(raw) {
  const line = raw.trim(), word = TEMPLATE_HEAD.exec(line)?.[1], gone = /^delete\s+(\S+)$/.exec(line)?.[1], name = /^(\$\/v|[\p{L}_][\p{L}\p{N}_]*)\s*=(?!=)/u.exec(line)?.[1];
  return word ? { name: word, head: true, raw: line } : gone ? { gone } : name ? { name: name === "$/v" ? "control" : name, raw: line } : null;
}
/** A narration line (plan "one scene", decision 2): say beside a slider, a choice, or no control, and say1 to sayN beside a steps control. */
const NARRATION = /^say(?:[1-9]\d*)?$/;
/**
 * The place of each kind of line in the fixed order. The narration lines come last. The head of a table and the things of a compare come before
 * the rows, and the source line of a data form, which the host writes, comes after the card.
 */
const rank = (name, word) => (name === word ? 0 : name === "control" ? 1 : name === "head" || /^t[1-3]$/.test(name) ? 1.5 : name === "card" ? 3 : name === "source" ? 3.5
  : NARRATION.test(name) ? 4 : 2);
/**
 * A narration line: its sentence with each run of space as one space, cut to BUDGET.say, as say = "<JSON string>", so the host reads it as JSON.
 * Null for a line that is no text or has no sentence end inside the cap: a narration line draws nothing, so it is left out with no fault.
 */
function narrationOf(name, raw) {
  let tree;
  try { tree = parseLine(raw.trim())?.tree; } catch (failure) { if (failure instanceof DslError) return null; throw failure; }
  const text = isText(tree) ? cut(tree.v.replace(/\s+/gu, " ").trim(), BUDGET.say) : "";
  return [...text].length <= BUDGET.say && SENTENCE_END.test(text) ? `${name} = ${JSON.stringify(text)}` : null;
}

/**
 * The lines of a template file in the fixed order (the first line, the control, the parts in their order, the card), and the fault of each
 * line as "NAME: message", where NAME is the template word for the first line and for a line with no name. A line of a known name has the tree
 * of its value, with the kit's color for each color name, or null when it has a fault. A second line of a name, a line of a name that the
 * template does not have, and a line with no name are faults and not lines. The kit-written line $/v = VALUE is the line control.
 * misses names each library name that a line places and the library does not have (library.js missOf): the miss log of spec 12.8.
 */
function readTemplate(text) {
  const [first = "", ...rest] = text.split("\n").filter((line) => line.trim() && !line.trim().startsWith("#")), word = TEMPLATE_HEAD.exec(first)?.[1], form = FORMS_BY_WORD[word];
  const faults = [], lines = [], misses = [], fault = (name, message) => { faults.push(`${name}: ${message}`); return message; };
  const read = (raw, parse) => { try { return parse(raw.trim()); } catch (failure) { if (failure instanceof DslError) return failure; throw failure; } };
  const head = read(first, parseExpression), headless = head instanceof DslError ? head.message : headFault(head, word, form.head) ?? form.headFault?.(head) ?? null;
  if (headless) fault(word, headless);
  lines.push({ name: word, raw: first.trim(), tree: headless ? null : head });
  for (const raw of rest) {
    const one = lineOf(raw), name = one?.name;
    if (!one || one.head) { fault(word, `${JSON.stringify(raw.trim().slice(0, 40))} is not a line of the template. Its lines are the first line, ${form.lines}`); continue; }
    if (one.gone) { fault(one.gone, "a template file has no delete line. Only a repair or a change reply deletes a line"); continue; }
    // A narration line has no fault: one that the kit cannot keep is left out, and a second line of a name goes.
    if (NARRATION.test(name)) {
      if (lines.some((line) => line.name === name)) continue;
      const kept = narrationOf(name, raw);
      lines.push({ name, raw: kept ?? raw.trim(), tree: kept && { k: "lit", v: name } });
      continue;
    }
    if (name !== "control" && name !== "card" && !form.part.test(name) && !(name === "source" && form.family)) { fault(name, `the template ${word} has no line ${name}. Its lines are ${form.lines}`); continue; }
    if (lines.some((line) => line.name === name)) { fault(name, `this is a second line ${name}. Each line has its own name`); continue; }
    const entry = read(raw, parseLine);
    let tree = entry instanceof DslError ? null : entry.tree, message = entry instanceof DslError ? entry.message : null, source = raw.trim();
    if (missOf(tree)) misses.push(missOf(tree));
    if (tree && name === "control") message = entry.kind === "state" ? (isText(tree) || numberOf(tree) !== undefined ? null : "$/v = VALUE takes a number or a text in quotes") : form.controlFault(tree);
    else if (tree) {
      // The kept line has the cut text, so the stored file and a later change have it too.
      const long = cutLong(tree, name, word), short = form.cut ? form.cut(long, name) : long;
      if (show(short) !== show(tree)) { tree = short; source = `${name} = ${show(tree)}`; }
      const shape = name !== "card" && word === "scene", path = nodesIn(tree, (node) => node.k === "path" && !readsV(node))[0];
      message = (name === "card" ? form.cardFault(tree) : name === "source" ? sourceFault(tree) : form.partFault(tree, head?.named?.lang?.v))
        ?? (path ? `a line reads only $/v, the value of the control, and this line reads ${show(path)}` : null);
      if (shape && !message) message = colorFault(tree = colored(tree));
      // The choice of items shows their labels, so it cannot tell two items of one label apart.
      const twin = !message && word === "items" && name !== "card" && lines.find((line) => line.tree && FORMS_BY_WORD.items.part.test(line.name) && line.tree.args[0].v === tree.args[0].v);
      if (twin) message = `the label ${show(tree.args[0])} is the label of ${twin.name} too. Each item needs a label of its own: the choice shows the labels`;
    }
    if (message) fault(name, message);
    lines.push({ name, raw: source, tree: message ? null : tree });
  }
  // The faults that need more than one line, such as a row with fewer values than the columns of the form.
  for (const [name, message] of form.context?.(lines) ?? []) {
    const line = lines.find((one) => one.name === name && one.tree);
    if (line) { fault(name, message); line.tree = null; }
  }
  // The voice of a nodata form says its card (D5): a file with no narration line gets say with the text of the card.
  const card = lines.find((line) => line.name === "card" && line.tree);
  if (word === "nodata" && !lines.some((line) => NARRATION.test(line.name)) && isText(card?.tree.args[1])) {
    const say = narrationOf("say", `say = ${JSON.stringify(card.tree.args[1].v)}`);
    if (say) lines.push({ name: "say", raw: say, tree: { k: "lit", v: "say" } });
  }
  lines.sort((a, b) => rank(a.name, word) - rank(b.name, word));
  return { word, lines, faults, misses };
}

/**
 * The template file after a repair or a change reply: a line of a name that the file has goes in its place, a new part after the last part,
 * a new control after the first line, and each other new line at the end. A delete removes the line of its name, and a new first line replaces
 * the first line. A line with no name, such as prose or a fence, goes.
 */
function mergeTemplate(stored, reply) {
  let lines = stored.split("\n").map(lineOf).filter((line) => line && !line.gone);
  const last = (test) => lines.reduce((found, line, index) => (test(line) ? index : found), -1), part = (line) => /^(?:[sitrp]\d+|head)$/.test(line.name);
  for (const line of reply.split("\n").map(lineOf).filter(Boolean)) {
    if (line.gone) { lines = lines.filter((one) => one.head || one.name !== line.gone); continue; }
    const same = lines.findIndex((one) => (line.head ? one.head : !one.head && one.name === line.name));
    if (same >= 0) { lines[same] = line; continue; }
    const head = last((one) => one.head);
    lines.splice(line.head ? 0 : part(line) ? Math.max(last(part), last((one) => one.name === "control"), head) + 1 : line.name === "control" ? head + 1 : lines.length, 0, line);
  }
  return lines.map((line) => line.raw).join("\n") + "\n";
}

/**
 * The host check of a searched data form (block 2 plan, decision 3): each number of a data cell or of a point is in the source excerpts.
 * A form that passes gets the host line source = source(SITE, TIME), so it shows its source. A form with a number that no excerpt holds
 * becomes a nodata file with the reason (rejected is that value). A file that is no data form, or nodata, stays as it is.
 */
function checkData(text, { excerpts, site, time }) {
  const read = isTemplateFile(text) ? readTemplate(text) : null, head = read?.lines[0].tree;
  if (!head || !DATA_WORDS.has(read.word) || read.word === "nodata") return { text, rejected: null };
  const known = new Set(numbersIn(excerpts)), rejected = checkedValues(read).find((value) => numbersIn(value).some((number) => !known.has(number)));
  if (rejected !== undefined) return { text: rejectFile(head, rejected), rejected };
  return { text: mergeTemplate(text, `source = source(${show({ k: "lit", v: site })}, ${show({ k: "lit", v: time })})`), rejected: null };
}

/** The parts of a template that are drawn: each part line with no fault that is not left out. */
const drawnParts = (read, leftOut) => read.lines.filter((line) => line.tree && !leftOut.has(line.name) && FORMS_BY_WORD[read.word].part.test(line.name));
/**
 * The value that $/v keeps when the control is left out: the start of a slider or a choice, the first item of items, or 0 when the file has no
 * control line that the kit can read.
 */
function startOf(read, leftOut) {
  const form = FORMS_BY_WORD[read.word], line = read.lines.find((one) => one.name === "control");
  if (form.start) return form.start(drawnParts(read, leftOut), !leftOut.has("control") && line?.tree?.k === "call" ? line.tree : undefined);
  let tree = line?.tree;
  try { tree ??= line && parseLine(line.raw)?.tree; } catch { tree = null; }
  // A steps control starts at its answer step, by default the last step (decision 3).
  const start = tree?.k !== "call" ? tree : tree.name === "slider" ? tree.args[4] : tree.name === "choice" ? tree.args[2] ?? tree.args[1]?.items?.[0]
    : tree.name === "steps" ? tree.args[2] ?? tree.args[1]?.items?.at(-1) : null;
  return isText(start) || numberOf(start) !== undefined ? start : { k: "lit", v: 0 };
}

/**
 * The app file of a template with each line of leftOut left out: the app line, the drawing of 300 × 220, the control (or $/v = START for a
 * control that is left out or has a fault), and the card. Null when the first line has a fault: a stored file with one then fails the change
 * rules as a mismatch (change.js).
 */
function expandTemplate(read, leftOut) {
  const [{ tree: head }] = read.lines, parts = drawnParts(read, leftOut), line = (name) => read.lines.find((one) => one.name === name && one.tree && !leftOut.has(name));
  if (!head) return null;
  const control = line("control")?.tree, card = line("card")?.tree, title = show(head.args[0]), form = FORMS_BY_WORD[read.word];
  if (form.file) return form.file(head, parts, control, card, line("source")?.tree);
  const out = [`app(${title}, lang=${show(head.named.lang)}, preset="clean", palette=${show(head.named.palette ?? { k: "lit", v: "blue" })})`];
  // What the form gives in place of the defaults (forms.js TemplateForm expand).
  const own = FORMS_BY_WORD[read.word].expand?.(parts, control, head) ?? {}, shapes = own.shapes ?? parts.map((part) => show(part.tree));
  out.push(...(own.lines ?? []), `need picture = drawing(${title}, ${WIDTH}, ${HEIGHT}, [${shapes.join(", ")}])`);
  const [label, ...args] = control?.args ?? [], start = show(startOf(read, leftOut));
  if (control?.k !== "call") out.push(`$/v = ${control ? show(control) : start}`);
  else if (control.name === "slider") out.push(`need v = input("slider", ${show(label)}, ${start}, min=${show(args[0])}, max=${show(args[1])}, step=${show(args[2])})`);
  else out.push(`need v = input("choice", ${show(label)}, ${start}, options=${show(own.options ?? args[0])})`);
  if (card) out.push(`need card = card(${show(card.args[0])}, ${own.cardText ?? show(card.args[1])})`);
  return out.join("\n") + "\n";
}

/** The text to store: each line with no fault that is not left out, and $/v = START in the place of a control that is left out or has a fault. */
const keptOf = (read, leftOut) => read.lines.flatMap((line) => (line.name === "control" && (!line.tree || leftOut.has("control")) ? [`$/v = ${show(startOf(read, leftOut))}`]
  : line.tree && !leftOut.has(line.name) ? [line.raw] : [])).join("\n") + "\n";

/**
 * The owner of each fault of the compile of an expansion, as a map of a line name to its faults. A fault of need v belongs to control and a fault
 * of need card to card. Each other fault belongs to each part whose compile with the first line and the control alone has faults, and the
 * faults of that compile name the part in the place of the need. A scene whose first line and control alone have faults gives them to control.
 */
function ownersOf(read, leftOut, faults, build) {
  const owners = new Map(), add = (name, fault) => owners.set(name, [...(owners.get(name) ?? []), `${name}: ${fault.replace(/^need \w+: /, "")}`]), rest = [];
  for (const fault of faults) /\bneed v\b/.test(fault) ? add("control", fault) : /\bneed card\b/.test(fault) ? add("card", fault) : rest.push(fault);
  if (!rest.length) return owners;
  const parts = drawnParts(read, leftOut).map((part) => part.name), alone = (names) => build(expandTemplate(read, new Set([...leftOut, ...owners.keys(), "card", ...names]))).faults;
  FORMS_BY_WORD[read.word].owners?.(owners, alone, parts, add);
  for (const part of parts) alone(parts.filter((other) => other !== part)).forEach((fault) => add(part, fault));
  return owners;
}

/**
 * The compile of a template file, with build, the compile of compile.js, which comes after this module in the bundle (as change.js nextState
 * takes it). stored is the template file of the stored app of a change, or null for a new screen.
 * A line with a fault is left out: a change takes the stored version of that line instead. A control that no line reads changes nothing, so
 * it is left out too. When the expansion has kit faults, each owner (ownersOf) is left out and the kit compiles once more.
 * Returns {built, word, kept, expanded, leftOut, holds, misses}, where misses are the library names that the file placed and the library does not have. built is the result of build for expanded, the expansion of kept, with each fault of a line that
 * was left out. A whole fault gives built with no definition and expanded null: a fault of the first line, no part with no fault, a cells file
 * with no control, or a kit fault of the last compile.
 * preview is true for the lines so far of a stream (compile.js previewStep): a control that no line reads yet stays, and only a fault of the first
 * line or a kit fault of the last compile is whole, so the first line alone gives the frame. holds is what kept draws: "card", "parts" (a shape, an
 * item, or the cells of a control), or "frame" (the first line, maybe the control).
 */
function buildTemplate(text, stored, build, preview = false) {
  const old = stored === null ? null : readTemplate(stored), faults = [], out = new Set();
  let read, leftOut, kept, expanded = null, built = null;
  const settle = () => {
    const back = old ? old.lines.filter((line) => line.tree && out.has(line.name)) : [];
    if (back.length) { text = mergeTemplate(text, back.map((line) => line.raw).join("\n")); read = readTemplate(text); faults.push(...read.faults); }
    leftOut = new Set([...read.lines.filter((line) => !line.tree).map((line) => line.name), ...[...out].filter((name) => !back.some((line) => line.name === name))]);
    const control = read.lines.some((line) => line.name === "control" && line.tree?.k === "call") && !leftOut.has("control");
    if (!preview && control && read.word === "scene" && !read.lines.some((line) => line.tree && line.name !== "control" && !leftOut.has(line.name) && nodesIn(line.tree, readsV).length)) {
      faults.push("control: the control changes nothing on the screen, because no line reads $/v. Make a part read $/v, such as visible=$/v > 2");
      leftOut.add("control");
    }
    // A narration line of the wrong kind is left out with no fault: say beside steps, sayK beside another control, or sayK above the count of steps.
    const steps = read.lines.find((line) => line.name === "control" && line.tree?.name === "steps" && !leftOut.has("control"))?.tree;
    const count = !steps ? 0 : read.word === "items" ? drawnParts(read, leftOut).length : steps.args[1].items.length;
    for (const line of read.lines) {
      const index = NARRATION.test(line.name) ? Number(line.name.slice(3)) : NaN;
      if (index === 0 ? steps : index > count) leftOut.add(line.name);
    }
    kept = keptOf(read, leftOut);
  };
  // The first line's own fault is in faults already.
  const drawn = (test) => read.lines.some((line) => line.tree && !leftOut.has(line.name) && test(line)), cells = (line) => line.name === "control" && line.tree.k === "call";
  const whole = () => (!read.lines[0].tree ? faults[0] : preview ? null
    : FORMS_BY_WORD[read.word].whole ? FORMS_BY_WORD[read.word].whole(drawnParts(read, leftOut), read.lines, (name) => drawn((line) => line.name === name))
    : read.word === "cells" ? (drawn(cells) ? null : "cells: the cells need a line control = slider(LABEL, 0, COUNT, 1, START) with no fault")
      : drawnParts(read, leftOut).length ? null : `${read.word}: the picture needs a ${read.word === "scene" ? "shape" : "item"} with no fault`);
  read = readTemplate(text);
  const misses = read.misses;
  faults.push(...read.faults);
  read.lines.filter((line) => !line.tree).forEach((line) => out.add(line.name));
  settle();
  const compile = () => build(expanded = expandTemplate(readTemplate(kept), new Set()));
  if (!whole()) {
    built = compile();
    if (built.faults.length) {
      for (const [name, list] of ownersOf(read, leftOut, built.faults, build)) { faults.push(...list); out.add(name); }
      settle();
      built = whole() ? null : compile();
    }
  }
  const lacking = whole();
  faults.push(...(lacking ? [lacking] : built.faults.map((fault) => `${read.word}: ${fault}`)));
  const unique = [...new Set(faults)], broken = Boolean(lacking) || built.faults.length > 0;
  const holds = drawn((line) => line.name === "card") ? "card" : drawnParts(read, leftOut).length || (read.word === "cells" && drawn(cells)) ? "parts" : "frame";
  return { built: broken ? { faults: unique, notes: built?.notes ?? [], definition: null, language: null, hole: false } : { ...built, faults: unique },
    word: read.word, kept, expanded: broken ? null : expanded, leftOut: [...leftOut], holds, misses };
}

return { isTemplateFile, readTemplate, mergeTemplate, checkData, expandTemplate, buildTemplate };
})();
// compile.js
const { compileAppFile, linesOf, readPlanReply, LINE, NEED_NAME, needsIn, patchAppFile, compileStep, previewStep } = (() => {
// The compile of the kit: an app file in, the report of the host and the app definition out. kit/build.js makes compileStep (one step of
// the host with compileAppFile, patchAppFile, and readPlanReply) and previewStep (the frame of the lines so far of a stream) the globals of
// the bundle: the sandbox entries.











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
 * The frame that the stage draws for a definition on a state: {ui, state, drawing, holds}. The definition holds no drawing commands, so an open
 * event of the run-time on its compiled data gives the state and the drawing (the surfaces of the reply, {} with no surface), and the screen of
 * that state gives the ui. The frame holds no copy of the definition: the result beside it has it, so a reply of the host holds its source once.
 */
function frameOf(definition, state, holds) {
  const app = dataOf(definition), reply = handle({ artifactId: "preview", revision: 0, state, event: { type: "open" } }, app);
  return { ui: screen(kitOf(app, reply.state), app).ui, state: reply.state, drawing: reply.surfaces ?? {}, holds };
}

/**
 * The faults hold the line faults and the report faults, or, when the report is clean, what the emitter cannot build yet.
 * A definition comes only with no fault. A throw inside the kit is the one fault "kit: <message>".
 * The host reads the result through compileStep as a promise (AppFileCompiler.kt: compileStep(...).then(JSON.stringify)).
 * hole is true when the file has a need of kind hole, also with faults: the plan step of the app gives such a plan to its loop.
 * previous is the stored app {appFile, stateSchema, state} of a change: then state is the next state of the stored one (nextState), else null.
 * blockedBySavedData is true when a saved value of the person blocks the change (each fault holds BLOCKS), and savedDataMismatch is true for
 * the one fault MISMATCH. nextState sets them where it makes these faults, so the host reads the two flags and never the texts of the faults.
 * A template file (template.js) also gives template (its word), kept (the text to store), expanded (the app file of kept, null on a whole fault), and leftOut (the names
 * of the lines that it left out). Its definition comes also with the faults of the lines that it left out, and is null only on a whole fault.
 * A template file as previous.appFile is the app file of its expansion for the change rules.
 * A result with a definition has frame (frameOf) with holds "whole". preview is true only for previewStep: a template then keeps the lines so far
 * (template.js buildTemplate), and its frame holds what they draw.
 */
async function compileAppFile(text, previous, preview = false) {
  const storedTemplate = isTemplateFile(previous?.appFile), template = isTemplateFile(text) && buildTemplate(text, storedTemplate ? previous.appFile : null, build, preview);
  const stored = storedTemplate ? { ...previous, appFile: expandTemplate(readTemplate(previous.appFile), new Set()) } : previous;
  const { file, emitted, ...built } = template ? template.built : build(text), result = { ...built, blockedBySavedData: false, savedDataMismatch: false };
  if (template) Object.assign(result, { template: template.word, kept: template.kept, expanded: template.expanded, leftOut: template.leftOut, misses: template.misses });
  const framed = (out) => (out.definition ? { ...out, frame: frameOf(out.definition, out.state ?? out.definition.initialState, preview && template ? template.holds : "whole") } : out);
  if (!stored || !result.definition) return framed({ ...result, state: null });
  try {
    // A change adds its own notes to the notes of the report.
    // A template keeps the faults of the lines that it left out beside the faults of the change.
    const { notes = [], ...change } = nextState(file, emitted, stored, build);
    return framed({ ...result, ...change, faults: [...result.faults, ...change.faults], notes: [...result.notes, ...notes] });
  } catch (failure) {
    return { ...result, faults: [`kit: ${failure?.message ?? failure}`], definition: null, state: null };
  }
}

/** The lines of a reply: an empty line and a markdown fence line go. Each other text stays, and the kit reports it. */
const linesOf = (text) => text.split("\n").filter((line) => line.trim() && !line.trim().startsWith("```"));
const OUTSIDE = /^\s*outside\(\s*"([a-z0-9]{1,16})"\s*\)\s*$/;

/**
 * A reply of a plan call: {kind: "outside", word} for the one line outside("word"), else {kind: "plan", text} with the lines of the reply
 * and a final newline. The word is 1 to 16 small Latin letters or digits, such as 3d, and the reader holds no list of words.
 * A reply of prose is a plan too: the compile reports its faults.
 */
function readPlanReply(text) {
  const lines = linesOf(text), word = lines.length === 1 ? OUTSIDE.exec(lines[0])?.[1] : undefined;
  return word ? { kind: "outside", word } : { kind: "plan", text: lines.join("\n") + "\n" };
}

/** A line of an app file: a line that starts with a head of a kind of parseLine (dsl.js HEADS). A repair reply keeps only these, so its prose and fences go. */
const LINE = new RegExp(`^\\s*(?:${Object.values(HEADS).map((head) => head.source).join("|")})`, "u");
/** A need line with its name as group 1, also a need line that the kit cannot read. */
const NEED_NAME = new RegExp(`^\\s*${HEADS.need.source}`, "u");
/** The name of each need line of a text, also of a need line that the kit cannot read. */
const needsIn = (text) => text.split("\n").map((line) => NEED_NAME.exec(line)?.[1]).filter(Boolean);

/**
 * The plan after a repair reply, as a person applies the reply by hand (A0-K1): each delete removes its line, a line with the name of an
 * earlier line replaces it in place, and each other line goes at the end. The text has no delete line, so a name that a delete removed is
 * free again for a later line and for the part of a later need. A delete of a name that no line has goes too.
 * A need line that the kit cannot read keeps its place until a reply writes that need or deletes it (K1 fix 2): else the plan lost the need,
 * could be clean, and the app lacked a thing of the request. Each other plan line that the kit could not read goes, because its fault was in
 * the report and a line that reads it gives a fault. Each other reply line that the kit cannot read stays at the end, so the compile names it.
 */
function patchAppFile(plan, reply) {
  const file = new AppFile(), unread = [];
  const keep = (line) => {
    const name = NEED_NAME.exec(line)?.[1];
    if (name) file.entries.set("need:" + name, { kind: "need", key: name, raw: line.trim() });
    return name;
  };
  plan.split("\n").forEach((line) => file.feed(line) || keep(line));
  reply.split("\n").filter((line) => LINE.test(line)).forEach((line) => file.feed(line) || keep(line) || unread.push(line.trim() + "\n"));
  return file.text() + unread.join("");
}

/**
 * One step of the host (AppFileCompiler.kt) in one call: the reply text as readPlanReply reads it when read is true, where an outside
 * reply returns at once; then the reply merged into plan by patchAppFile when plan is not null; then compileAppFile of the merged text
 * with the stored app previous. A merged text of more than cap characters gives result null and no compile.
 * A template plan (template.js) takes the reply by mergeTemplate in place of patchAppFile.
 * previous is {appFile, stateSchema, state} with stateSchema and state as JSON text, as the host sends them, or null. A text that is no
 * JSON makes the step reject. The result is {kind: "outside", word} or {kind: "plan", text, result}, where text is the merged text.
 * data is {excerpts, site, time} for a new searched data screen, or null: the merged text then gets the host check of template.js checkData
 * before the compile, and result.rejected is the value that no excerpt holds, or null.
 */
async function compileStep({ text, plan, read, previous, cap, data = null, missing = [] }) {
  // An image entry whose image the host cannot open is no entry of this call: its name is a miss (library.js LIBRARY).
  LIBRARY.missing = new Set(missing);
  const reply = read ? readPlanReply(text) : { kind: "plan", text };
  if (reply.kind !== "plan") return reply;
  const { text: merged, rejected } = data ? checkData(mergedOf(plan, reply.text), data) : { text: mergedOf(plan, reply.text), rejected: null };
  const result = merged.length > cap ? null : await compileAppFile(merged, storedOf(previous));
  return { kind: "plan", text: merged, result: data && result ? { ...result, rejected } : result };
}

/** The reply merged into plan (null for a new screen), and the stored app previous with its schema and state read from JSON text. */
const mergedOf = (plan, text) => (plan === null ? text : isTemplateFile(plan) ? mergeTemplate(plan, text) : patchAppFile(plan, text));
const storedOf = (previous) => previous && { appFile: previous.appFile, stateSchema: JSON.parse(previous.stateSchema), state: JSON.parse(previous.state) };

/**
 * The preview step of the host (stage templates design, section 3): {kind: "preview", frame} for the complete lines so far of a reply, with
 * frame {definition, ui, state, drawing, holds}, or {kind: "none"}. text is read as readPlanReply reads it and merged into plan as compileStep merges it, and previous is the stored app of a change
 * as compileStep takes it, so a value that the person moved stays. The compile runs with no check run (settings.gate off) and keeps the lines so far
 * of a template: a line with a fault is left out with no repair (a change keeps its stored version), and the first line alone gives the frame with
 * the picture card at its final size. A free plan gives holds "whole". An outside reply, a text with no usable first line, and each throw give none.
 */
async function previewStep({ text, plan = null, previous = null, data = null, missing = [] }) {
  LIBRARY.missing = new Set(missing);
  const gate = settings.gate;
  settings.gate = "off";
  try {
    // A searched data form shows only checked values, also while it streams: its frame has the host check of compileStep.
    const merged = (text) => (data ? checkData(mergedOf(plan, text), data).text : mergedOf(plan, text));
    const reply = readPlanReply(text), { definition, frame } = reply.kind === "plan" ? await compileAppFile(merged(reply.text), storedOf(previous), true) : {};
    // A preview has no result beside its frame, so the frame holds the definition.
    return frame ? { kind: "preview", frame: { definition, ...frame } } : { kind: "none" };
  } catch {
    return { kind: "none" };
  } finally {
    settings.gate = gate;
  }
}

return { compileAppFile, linesOf, readPlanReply, LINE, NEED_NAME, needsIn, patchAppFile, compileStep, previewStep };
})();
runtime.text = "let handle;const __handle=(f)=>{handle=f};\n(()=>{var WZ={\"||\":1,\"&&\":2,\"==\":3,\"!=\":3,\"<\":4,\"<=\":4,\">\":4,\">=\":4,\"+\":5,\"-\":5,\"*\":6,\"/\":6,\"%\":6};class R extends Error{}function pZ(Q){let Z=[],$=0,q=(J)=>{throw new R(\"\")};while($<Q.length){let J=Q[$];if(/\\s/.test(J)){$++;continue}if(J==='\"'){let H=$+1,j=\"\";while(H<Q.length&&Q[H]!=='\"'){if(Q[H]===\"\\\\\")H++,j+={n:`\n`,t:\"\\t\"}[Q[H]]??Q[H];else j+=Q[H];H++}if(H>=Q.length)q(\"\");Z.push({t:\"str\",v:j}),$=H+1;continue}if(J===\"$\"){let H=/^\\$(\\/?)([\\p{L}_][\\p{L}\\p{N}_]*(?:\\/[\\p{L}_][\\p{L}\\p{N}_]*)*)/u.exec(Q.slice($));if(!H)q(\"\");Z.push({t:\"path\",abs:H[1]===\"/\",segs:H[2].split(\"/\")}),$+=H[0].length;continue}let V=/^(\\d+\\.?\\d*|\\.\\d+)(e[+-]?\\d+)?/i.exec(Q.slice($));if(V){Z.push({t:\"num\",v:Number(V[0])}),$+=V[0].length;continue}let K=/^[\\p{L}_][\\p{L}\\p{N}_]*/u.exec(Q.slice($));if(K){Z.push({t:\"id\",v:K[0]}),$+=K[0].length;continue}let W=Q.slice($,$+2);if([\":=\",\"==\",\"!=\",\"<=\",\">=\",\"&&\",\"||\"].includes(W)){Z.push({t:\"op\",v:W}),$+=2;continue}if(\"()[]{},:=+-*/%<>!?.\".includes(J)){Z.push({t:\"op\",v:J}),$++;continue}q(\"\")}return Z}function lZ(Q,Z=0){let $=Z,q=0,J=(G)=>Q[$]&&Q[$].t===\"op\"&&Q[$].v===G,V=(G)=>{if(!J(G))throw new R(\"\");$++},K=(G)=>{if((q+=G)>100)throw new R(\"\")};function W(){return H()}function H(){let G=Q[$];if(!G)throw new R(\"\");if($++,G.t===\"num\")return{k:\"lit\",v:G.v};if(G.t===\"str\")return{k:\"lit\",v:G.v};if(G.t===\"path\")return{k:\"path\",abs:G.abs,segs:G.segs};if(G.t===\"id\"){if(G.v===\"true\"||G.v===\"false\")return{k:\"lit\",v:G.v===\"true\"};if(G.v===\"null\")return{k:\"lit\",v:null};if(!J(\"(\"))return{k:\"ref\",name:G.v};$++;let U=[],F={};while(!J(\")\")){if(Q[$]?.t===\"id\"&&Q[$+1]?.t===\"op\"&&Q[$+1].v===\"=\"){let Y=Q[$].v;$+=2,F[Y]=P()}else U.push(P());if(!J(\")\"))V(\",\")}return $++,{k:\"call\",name:G.v,args:U,named:F}}if(G.t===\"op\"&&G.v===\"(\"){let U=P();return V(\")\"),U}if(G.t===\"op\"&&G.v===\"[\"){let U=[];while(!J(\"]\"))if(U.push(P()),!J(\"]\"))V(\",\");return $++,{k:\"list\",items:U}}if(G.t===\"op\"&&G.v===\"{\"){let U=[];while(!J(\"}\")){let F=Q[$++];if(!F||F.t!==\"id\"&&F.t!==\"str\")throw new R(\"\");if(V(\":\"),U.push([F.v,P()]),!J(\"}\"))V(\",\")}return $++,{k:\"map\",entries:U}}if(G.t===\"op\"&&(G.v===\"-\"||G.v===\"!\"))return{k:\"un\",op:G.v,arg:j()};throw new R(\"\")}function j(){let G=W();for(;;){let U=Q[$+1];if(J(\".\")){$++;let F=Q[$++];if(!F||F.t!==\"id\")throw new R(\"\");G={k:\"field\",of:G,name:F.v}}else if(J(\"[\")){$++;let F=P();V(\"]\"),G={k:\"index\",of:G,at:F}}else if(J(\"/\")&&U?.t===\"id\"&&![\"true\",\"false\",\"null\"].includes(U.v)&&!(Q[$+2]?.t===\"op\"&&Q[$+2].v===\"(\"))G={k:\"field\",of:G,name:U.v},$+=2;else return G}}function z(G){let U=j(),F=0;while(Q[$]?.t===\"op\"&&WZ[Q[$].v]>=G){let Y=Q[$++].v;U={k:\"bin\",op:Y,left:U,right:z(WZ[Y]+1)}}return U}function P(){let G=z(1);if(!J(\"?\"))return G;$++;let U=P();V(\":\");let F=P();return{k:\"if\",test:G,yes:U,no:F}}return{tree:P(),used:$-Z}}function uQ(Q){let Z=pZ(Q),{tree:$,used:q}=lZ(Z);if(q!==Z.length)throw new R(\"\");return $}function iZ(Q){let Z=Q.trim();if(!Z||Z.startsWith(\"#\"))return null;let $=(J,V,K)=>{let W=uQ(K);if([\"need\",\"app\",\"check\"].includes(J)&&(W.k!==\"call\"||J!==\"need\"&&W.name!==J))throw new R(\"\");return{kind:J,key:V,tree:W,raw:Z}},q;if(q=/^delete\\s+check\\s*\\(\\s*\"(.*)\"\\s*\\)$/.exec(Z))return{kind:\"delete\",key:\"check:\"+q[1],tree:null,raw:Z};if(q=/^delete\\s+(?:need\\s+)?(\\S+)$/.exec(Z))return{kind:\"delete\",key:q[1],tree:null,raw:Z};if(q=/^need\\s+([\\p{L}_][\\p{L}\\p{N}_]*)\\s*=(?!=)(.*)$/u.exec(Z))return $(\"need\",q[1],q[2]);if(q=/^(\\$\\/[\\p{L}\\p{N}_/]+)\\s*:=(.*)$/u.exec(Z))return $(\"formula\",q[1],q[2]);if(q=/^(\\$\\/[\\p{L}\\p{N}_/]+)\\s*=(?!=)(.*)$/u.exec(Z))return $(\"state\",q[1],q[2]);if(/^app\\s*\\(/.test(Z))return $(\"app\",\"app\",Z);if(/^check\\s*\\(/.test(Z)){let J=$(\"check\",\"\",Z);return{...J,key:\"check:\"+(J.tree.args[0]?.v??\"\")}}if(q=/^([\\p{L}_][\\p{L}\\p{N}_]*)\\s*=(?!=)(.*)$/u.exec(Z))return $(\"component\",q[1],q[2]);throw new R(\"\")}class xQ{constructor(){this.entries=new Map,this.faults=[],this.partFaults=[],this.tops=new Map,this.removed=new Set}beginStep(){this.faults=[]}feed(Q,Z=null,$=\"author\"){let q;try{q=iZ(Q)}catch(V){if(!(V instanceof R))throw V;return this.faults.push({line:Z,text:Q.trim(),message:V.message}),null}if(!q)return null;if(q.kind===\"delete\"){let V=[...this.entries.keys()].find((K)=>K===q.key||K===\"need:\"+q.key);if(V)this.entries.delete(V),this.removed.add(V);else this.faults.push({line:Z,text:q.raw,message:\"\"});return q}let J=q.kind===\"need\"?\"need:\"+q.key:q.key;if(q.origin=$,$===\"author\")this.removed.delete(J);return this.entries.set(J,q),q}feedText(Q){return Q.split(`\n`).forEach((Z,$)=>this.feed(Z,$+1)),this}open(Q){return!this.removed.has(Q)&&(this.entries.get(Q)?.origin??\"part\")===\"part\"}of(Q){return[...this.entries.values()].filter((Z)=>Z.kind===Q)}get(Q){return this.entries.get(Q)}text(){return[...this.entries.values()].map((Q)=>Q.raw).join(`\n`)+`\n`}}var N=(Q)=>Math.round(Q*100)/100,s=(Q)=>Q.every((Z)=>Number.isFinite(Z)&&Math.abs(Z)<=8192),qQ=(Q,Z,$)=>Math.min($,Math.max(Z,Q)),GZ=(Q)=>({type:\"polyline\",points:[...Q,Q[0]],stroke:2}),HZ=(Q)=>{let Z=[];for(let $=0;$+1<Q.length;$+=2)Z.push([Q[$],Q[$+1]]);return Z},XQ=(Q)=>({x:N(Q[0]),y:N(Q[1])});function dZ(Q,Z,$){let q=Q.length,J=[{type:\"move\",...XQ(Q[0])}],V=(K)=>Q[$?(K+q)%q:qQ(K,0,q-1)];for(let K=1;K<q+($?1:0);K++){let[W,H,j,z]=[V(K-2),V(K-1),V(K),V(K+1)];if(!Z){J.push({type:\"line\",...XQ(j)});continue}J.push({type:\"cubic\",cx1:N(H[0]+(j[0]-W[0])/6),cy1:N(H[1]+(j[1]-W[1])/6),cx2:N(j[0]-(z[0]-H[0])/6),cy2:N(j[1]-(z[1]-H[1])/6),...XQ(j)})}if($)J.push({type:\"close\"});return J}function sZ(Q,Z){let $=Q.length,q=(V,K,W)=>{let H=Math.hypot(K[0]-V[0],K[1]-V[1]),j=Math.min(0.5,W/(H||1));return[V[0]+(K[0]-V[0])*j,V[1]+(K[1]-V[1])*j]},J=[];return Q.forEach((V,K)=>{let[W,H]=[q(V,Q[(K+$-1)%$],Z),q(V,Q[(K+1)%$],Z)];J.push({type:K?\"line\":\"move\",...XQ(W)},{type:\"quad\",cx:N(V[0]),cy:N(V[1]),...XQ(H)})}),[...J,{type:\"close\"}]}var RQ={circle:{args:[\"x\",\"y\",\"r\"],prompt:\"\",draw({x:Q,y:Z,r:$,stroke:q}){if(!($>=0&&$<=2048)||!s([Q-$,Z-$,Q+$,Z+$]))return null;let J=N(2*$);return q?{type:\"arc\",x:N(Q-$),y:N(Z-$),width:J,height:J,start:0,sweep:360,stroke:2}:{type:\"circle\",x:Q,y:Z,radius:$}}},rect:{args:[\"x\",\"y\",\"w\",\"h\"],last:\"radius\",prompt:\"\",draw({x:Q,y:Z,w:$,h:q,radius:J,stroke:V}){let K={x:Math.min(Q,N(Q+$)),y:Math.min(Z,N(Z+q)),width:Math.abs($),height:Math.abs(q)},W=N(K.x+K.width),H=N(K.y+K.height);if(!s([K.x,K.y,W,H])||K.width>8192||K.height>8192)return null;if(V)return GZ([[K.x,K.y],[W,K.y],[W,H],[K.x,H]]);return J?{type:\"roundRect\",...K,radius:qQ(J,0,1024)}:{type:\"rect\",...K}}},line:{args:[\"x1\",\"y1\",\"x2\",\"y2\"],last:\"width\",prompt:\"\",draw:({x1:Q,y1:Z,x2:$,y2:q,width:J})=>s([Q,Z,$,q])?{type:\"line\",x:Q,y:Z,x2:$,y2:q,stroke:qQ(J??2,0.1,128)}:null},text:{args:[\"x\",\"y\",\"text\"],last:\"size\",prompt:\"\",draw:({x:Q,y:Z,text:$,size:q,align:J})=>s([Q,Z])?{type:\"text\",x:Q,y:Z,size:qQ(q??14,1,128),text:$.slice(0,2048),align:J??\"center\",baseline:\"middle\"}:null},polygon:{args:[\"points\"],points:3,prompt:\"\",draw({points:Q,stroke:Z}){let $=HZ(Q);if(!s($.flat())||$.length<(Z?2:3)||$.length>(Z?2047:2048))return null;return Z?GZ($):{type:\"polygon\",points:$}}},path:{args:[\"points\"],last:\"width\",points:2,named:[\"smooth\",\"closed\"],prompt:\"\",draw({points:Q,width:Z,smooth:$,closed:q,stroke:J}){let V=HZ(Q);if(!s(V.flat())||V.length<2||V.length>200)return null;let K=dZ(V,Boolean($),Boolean(q));return q&&!J?{type:\"path\",segments:K}:{type:\"path\",segments:K,stroke:qQ(Z??2,0.1,128)}}},arrow:{args:[\"x1\",\"y1\",\"x2\",\"y2\"],last:\"width\",prompt:\"\",draw({x1:Q,y1:Z,x2:$,y2:q,width:J}){let V=Math.hypot($-Q,q-Z),K=qQ(J??4,1,24);if(!s([Q,Z,$,q])||V<1)return null;let[W,H]=[($-Q)/V,(q-Z)/V],j=Math.min(V*0.5,2.8*K+4),z=1.5*K,P=K/2,C=[$-W*j,q-H*j],G=(F,Y)=>[F[0]-H*Y,F[1]+W*Y],U=[G([Q,Z],P),G(C,P),G(C,z),[$,q],G(C,-z),G(C,-P),G([Q,Z],-P)];return{type:\"path\",segments:sZ(U,Math.max(1,K*0.4))}}},arc:{args:[\"x\",\"y\",\"r\",\"from\",\"sweep\"],last:\"width\",prompt:\"\",draw({x:Q,y:Z,r:$,from:q,sweep:J,width:V}){if(!($>0&&$<=2048)||!s([Q-$,Z-$,Q+$,Z+$]))return null;return{type:\"arc\",x:N(Q-$),y:N(Z-$),width:N(2*$),height:N(2*$),start:N(qQ(-q,-360,360)),sweep:N(qQ(-J,-360,360)),stroke:qQ(V??3,0.1,128)}}},group:{args:[\"x\",\"y\",\"scale\",\"shapes\"],prompt:null,draw:()=>null},image:{args:[\"x\",\"y\",\"w\",\"h\",\"image\"],color:!1,prompt:null,draw:({x:Q,y:Z,w:$,h:q,image:J})=>$>0&&q>0&&s([Q,Z,Q+$,Z+q])?{type:\"image\",id:J,x:Q,y:Z,width:$,height:q}:null}},zZ={visible:{kind:\"test\",prompt:\"\"},stroke:{kind:\"boolean\",prompt:\"\"},smooth:{kind:\"boolean\",prompt:null},closed:{kind:\"boolean\",prompt:null},gradient:{kind:\"color\",prompt:\"\"},radial:{kind:\"number\",prompt:\"\"},glow:{kind:\"boolean\",prompt:\"\"},opacity:{kind:\"number\",prompt:\"\"},turn:{kind:\"number\",prompt:\"\"},align:{kind:\"text\",prompt:null},radius:{kind:\"number\",prompt:null},width:{kind:\"number\",prompt:null},size:{kind:\"number\",prompt:null}};function cQ(Q){let{type:Z,x:$,y:q,radius:J,width:V,height:K,x2:W,y2:H,points:j,segments:z}=Q;if(Z===\"circle\")return{x:$-J,y:q-J,w:2*J,h:2*J};if(Z===\"line\")return{x:Math.min($,W),y:Math.min(q,H),w:Math.abs(W-$),h:Math.abs(H-q)};let P=j??(z?z.flatMap((C)=>[[C.x,C.y],[C.cx1,C.cy1],[C.cx2,C.cy2]]).filter(([C])=>C!==void 0):null);if(P){let C=P.map(([Y])=>Y),G=P.map(([,Y])=>Y),U=Math.min(...C),F=Math.min(...G);return{x:U,y:F,w:Math.max(...C)-U,h:Math.max(...G)-F}}return{x:$,y:q,w:V??0,h:K??0}}var AQ={Column:[\"children\"],Row:[\"children\"],Card:[\"children\"],Section:[\"title\",\"children\"],Text:[\"text\"],Heading:[\"text\"],Metric:[\"label\",\"value\"],Emoji:[\"value\",\"label\"],Badge:[\"value\"],Grid:[\"children\"],Progress:[\"value\",\"max\",\"label\"],Ring:[\"value\",\"max\",\"text\",\"label\"],Divider:[],Notice:[\"text\"],Button:[\"label\",\"action\"],IconButton:[\"icon\",\"action\",\"label\"],TextField:[\"label\",\"bind\"],NumberField:[\"label\",\"bind\"],Slider:[\"label\",\"bind\"],RangeSlider:[\"label\",\"bind\"],Toggle:[\"label\",\"bind\"],CheckBox:[\"label\",\"bind\"],Segmented:[\"label\",\"bind\",\"options\"],Select:[\"label\",\"bind\",\"options\"],List:[\"items\",\"template\",\"group\"],Chart:[\"items\",\"point\",\"value\",\"label\",\"bind\"],Table:[\"label\",\"heads\",\"rows\",\"bind\",\"column\",\"signs\"],Tabs:[\"titles\",\"groups\",\"label\"],Dialog:[\"title\",\"children\"],DateField:[\"label\",\"bind\"],TimeField:[\"label\",\"bind\"],Picture:[\"text\",\"found\"],Choices:[\"label\",\"bind\",\"options\"],Timer:[\"label\",\"seconds\"],Drawing:[\"label\",\"width\",\"height\",\"shapes\"],Model3d:[\"label\",\"bodies\",\"bind\",\"exploded\",\"selected\"],Link:[\"label\",\"url\",\"text\"]},YZ={ArrowUp:\"Arrow\",ArrowDown:\"Sort\",ArrowLeft:\"Back\",ArrowRight:\"Chevron\",Edit:\"Pencil\"},aZ=new Set([\"visible\",\"enabled\",\"variant\",\"tone\",\"emphasis\",\"weight\",\"min\",\"max\",\"step\",\"empty\",\"placeholder\",\"gap\",\"selected\",\"kind\",\"size\",\"align\",\"columns\",\"icon\",\"fit\"]),oZ=new Set([\"set\",\"toggle\",\"inc\",\"dec\",\"append\",\"remove\",\"clear\",\"when\",\"move\",\"flip\",\"animate\"]),nZ={box:{args:[\"part\",\"x\",\"y\",\"z\",\"w\",\"h\",\"d\"]},cylinder:{args:[\"part\",\"x\",\"y\",\"z\",\"radius\",\"length\",\"axis\"]}},tZ=new Set([\"text\",\"part\",\"axis\",\"image\"]),HQ={ink:\"#0f172a\",muted:\"#94a3b8\",accent:null,surface:\"#ffffff\",water:\"#38bdf8\",land:\"#65a30d\",sun:\"#facc15\",sand:\"#e7d3a8\",wood:\"#a16207\",success:\"#16a34a\",warning:\"#ea580c\",error:\"#dc2626\"},CQ=/^#(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/,lQ=new RegExp(`^(${Object.keys(HQ).join(\"|\")})([+-][123])?(?:@(\\\\d{1,2}|100))?$`);var FZ=200000,eZ=20000,Q0=4096,YQ=(Q,Z=0)=>{let $=10**Z;return Math.round((Q+Number.EPSILON)*$)/$},LQ=(Q)=>Array.isArray(Q)?Q.map(LQ):Q&&typeof Q===\"object\"?Object.fromEntries(Object.entries(Q).map(([Z,$])=>[Z,LQ($)])):Q;function iQ(Q){let Z=[\"x\",\"y\",\"z\"].map(($)=>Q.reduce((q,J)=>q+J[$],0)/(Q.length||1));return Q.map(($)=>$.part?{...$,...Object.fromEntries([\"x\",\"y\",\"z\"].map((q,J)=>[q,YQ($[q]+($[q]-Z[J])/2,2)]))}:$)}var UZ={de:\".,\",es:\".,\",it:\".,\",pt:\".,\",tr:\".,\",ru:\" ,\",fr:\" ,\",pl:\" ,\",uk:\" ,\"};function TQ(Q,Z,$,q){let[J,V]=Object.hasOwn(UZ,Q)?UZ[Q]:\",.\",K=Number(Z).toFixed($),W=/^-?(\\d+)\\.?(\\d*)$/.exec(K);if(!W)return K;let[,H,j]=W,z=q?j.replace(/0+$/,\"\"):j;return(Number(K)<0?\"-\":\"\")+(H.length<5&&/^(es|pl|pt)$/.test(Q)?H:H.replace(/\\B(?=(\\d{3})+$)/g,J))+(z?V+z:\"\")}var Z0={fixed:(Q,Z,$=2)=>TQ(Q,Z,$),money:(Q,Z,$=\"\")=>$+TQ(Q,Z,/^(ja|ko)$/.test(Q)?0:2),percent:(Q,Z,$=0)=>TQ(Q,Z,$)+\"%\",amount:(Q,Z,$=0)=>typeof Z==\"number\"?TQ(Q,YQ(Z,$),$,!0):Z},$0={round:YQ,floor:Math.floor,ceil:Math.ceil,abs:Math.abs,sin:(Q)=>YQ(Math.sin(Q*Math.PI/180),12)+0,cos:(Q)=>YQ(Math.cos(Q*Math.PI/180),12)+0,pow:Math.pow,sqrt:Math.sqrt,exp:Math.exp,ln:Math.log,log10:Math.log10,min:(...Q)=>Q.flat(3).length?Math.min(...Q.flat(3)):0,max:(...Q)=>Q.flat(3).length?Math.max(...Q.flat(3)):0,clamp:(Q,Z,$)=>Math.min($,Math.max(Z,Q)),len:(Q)=>Q==null?0:Q.length,text:(Q)=>String(Q??\"\"),number:(Q)=>Number(Q)||0,pad2:(Q)=>String(Math.floor(Q)).padStart(2,\"0\"),join:(Q,Z=\", \")=>{let $=Q||[];if($.length*String(Z).length>FZ)throw new R(\"\");return $.join(Z)},upper:(Q)=>String(Q).toUpperCase(),lower:(Q)=>String(Q).toLowerCase(),at:(Q,Z)=>Array.isArray(Q)?Q[Z]??null:null,streak:(Q)=>(Q??[]).reduce(([Z,$],q)=>q?[Math.max(Z,$+1),$+1]:[Z,0],[0,0])[0],near:(Q,Z)=>Math.abs(Q-Z)<=Math.max(0.01,Math.abs(Z)/1000),trim:(Q)=>String(Q).trim(),contains:(Q,Z)=>String(Q).toLowerCase().includes(String(Z).toLowerCase()),has:(Q,Z)=>Array.isArray(Q)&&Q.includes(Z),hours:(Q,Z)=>{let[$,q]=[Q,Z].map(dQ);return $===null||q===null?0:(q-$+1440)%1440/60},today:()=>{let Q=new Date(GQ.now());return`${Q.getFullYear()}-${String(Q.getMonth()+1).padStart(2,\"0\")}-${String(Q.getDate()).padStart(2,\"0\")}`},split:(Q,Z=\",\")=>String(Q??\"\").split(String(Z)||\",\").map(($)=>$.trim()).filter(Boolean),weekday:(Q)=>{let Z=VQ(Q);return Z===null?0:(new Date(Z*86400000).getUTCDay()+6)%7+1},month:(Q)=>VQ(Q)===null?0:Number(String(Q).trim().slice(5,7)),year:(Q)=>VQ(Q)===null?0:Number(String(Q).trim().slice(0,4)),days:(Q,Z)=>{let[$,q]=[VQ(Q),VQ(Z)];return $===null||q===null?0:q-$},addDays:(Q,Z)=>{let $=VQ(Q);return $===null?null:new Date(($+Math.round(Number(Z)||0))*86400000).toISOString().slice(0,10)}};function VQ(Q){let Z=/^(\\d{4})-(\\d{2})-(\\d{2})$/.exec(String(Q??\"\").trim());if(!Z)return null;let $=Date.UTC(Number(Z[1]),Number(Z[2])-1,Number(Z[3])),q=new Date($);return q.getUTCMonth()===Number(Z[2])-1&&q.getUTCDate()===Number(Z[3])?$/86400000:null}function dQ(Q){let Z=/^(\\d{1,2}):(\\d{2})$/.exec(String(Q??\"\").trim());return Z&&Number(Z[1])<24&&Number(Z[2])<60?Number(Z[1])*60+Number(Z[2]):null}var jZ=(Q,Z)=>Array.isArray(Q)&&Array.isArray(Z)?Q.reduce(($,q,J)=>$||jZ(q,Z[J]),0):Q<Z?-1:Q>Z?1:0,q0={count:(Q,Z)=>Z?Q.filter(Z).length:Q.length,sum:(Q,Z)=>Q.reduce(($,q)=>$+Number(Z?Z(q):q),0),avg:(Q,Z)=>Q.length?Q.reduce(($,q)=>$+Number(Z?Z(q):q),0)/Q.length:0,filter:(Q,Z)=>Q.filter(Z),any:(Q,Z)=>Q.some(Z),all:(Q,Z)=>Q.every(Z),map:(Q,Z)=>Q.map(Z),find:(Q,Z)=>Q.find(Z)??null,sort:(Q,Z,$)=>[...Q].sort((q,J)=>jZ(Z(q),Z(J))*($===\"desc\"?-1:1)),groups:(Q,Z,$)=>{if(!Z)throw new R(\"\");let q=new Map;for(let J of Q)q.set(Z(J),(q.get(Z(J))??0)+Number($?$(J):1));return[...q].map(([J,V])=>({key:J,value:V}))}};function pQ(Q,Z){if(Q===Z)return!0;if(typeof Q!==\"object\"||typeof Z!==\"object\"||!Q||!Z||Array.isArray(Q)!==Array.isArray(Z))return!1;let $=(q)=>Object.keys(q).filter((J)=>J!==\"id\");return $(Q).length===$(Z).length&&$(Q).every((q)=>pQ(Q[q],Z[q]))}var J0=(Q)=>Q.rest&&Q.rest.v!==0||Q.rounds&&Q.rounds.v!==1,sQ=(Q)=>Q.named.alert?.v===!0&&!J0(Q.named),V0=(Q)=>Q.of(\"need\").filter((Z)=>Z.tree.name===\"entries\"&&Z.tree.named?.show?.k===\"map\").flatMap((Z)=>Z.tree.named.show.entries),m=(Q,Z)=>Object.hasOwn(Q,Z)?Q[Z]:void 0,BZ=12,rQ=(Q)=>({...Object.fromEntries((m(AQ,Q.name)??[]).map((Z,$)=>[Z,Q.args[$]])),...Q.named});class aQ{constructor(Q,Z=!0){if(this.file=Q,this.faults=[],this.nextId=1,this.state={},this.seen=new Map,this.steps=0,this.tripped=!1,Z)this.follow()}follow(){for(let Q of this.file.of(\"state\")){if(this.seen.get(Q.key)===Q.raw)continue;this.seen.set(Q.key,Q.raw),this.write(this.pathOf(Q.key),this.attempt(Q,()=>LQ(this.value(Q.tree,{}))),{})}this.tagRows(this.state),this.recompute()}fault(Q,Z){this.faults.push({where:Q,message:Z})}step(Q=1){if((this.steps+=Q)>FZ||this.tripped)this.stop(\"\")}stop(Q){throw this.tripped=!0,new R(Q)}charge(Q){if(typeof Q===\"string\")this.step(Q.length>>4);else if(Q&&typeof Q===\"object\"){this.step();for(let Z of Object.values(Q))this.charge(Z)}return Q}attempt(Q,Z){try{return Z()}catch($){return this.fault(Q.key,$.message),null}}pathOf(Q){return{k:\"path\",abs:!0,segs:Q.slice(2).split(\"/\")}}tagRows(Q){if(Array.isArray(Q))Q.forEach((Z)=>{if(Z&&typeof Z===\"object\"&&!Array.isArray(Z))Z.id??=\"r\"+this.nextId++,this.tagRows(Z)});else if(Q&&typeof Q===\"object\")Object.values(Q).forEach((Z)=>this.tagRows(Z))}read(Q,Z){let $=Q.abs?this.state:Z.it;for(let q of Q.segs)$=$==null?void 0:m($,q);return $}place(Q,Z){if(Q.k===\"path\"){let q=Q.abs?this.state:Z.it;if(q==null)throw new R(\"\");if(typeof q!==\"object\")throw new R(\"\");for(let J of Q.segs.slice(0,-1))q=m(q,J)??(q[J]={});return[q,Q.segs.at(-1)]}if(Q.k!==\"field\"&&Q.k!==\"index\")throw new R(\"\");let $=this.value(Q.of,Z);if($==null||typeof $!==\"object\")throw new R(\"\");return[$,Q.k===\"field\"?Q.name:this.value(Q.at,Z)]}write(Q,Z,$){let[q,J]=this.place(Q,$);q[J]=Z}value(Q,Z){switch(this.step(),Q.k){case\"lit\":return Q.v;case\"path\":if(!Q.abs&&Q.segs[0]===\"it\")return Q.segs.slice(1).reduce(($,q)=>$==null?void 0:m($,q),Z.it);if(!Q.abs&&Z.each!==void 0&&Q.segs.join()===\"index\"&&!(Z.it&&typeof Z.it===\"object\"&&Object.hasOwn(Z.it,\"index\")))return Z.each;if(!Q.abs&&Z.it===void 0)throw new R(\"\");if(!Q.abs&&Z.it!==void 0&&(Z.it===null||typeof Z.it!==\"object\"))throw new R(\"\");if(!Q.abs&&Z.it&&!Object.hasOwn(Z.it,Q.segs[0])){let $=Q.segs[0];if(this.shown??=Object.fromEntries(V0(this.file)),Q.segs.length===1&&m(this.shown,$)&&!Z.busy?.includes($))return this.value(this.shown[$],{...Z,busy:[...Z.busy??[],$]});throw new R(\"\")}return this.read(Q,Z);case\"list\":return this.charge(Q.items.map(($)=>this.value($,Z)));case\"map\":return this.charge(Object.fromEntries(Q.entries.map(([$,q])=>[$,this.value(q,Z)])));case\"field\":{let $=this.value(Q.of,Z);return $==null?void 0:m($,Q.name)}case\"index\":{let $=this.value(Q.of,Z);return Array.isArray($)?m($,this.value(Q.at,Z))??null:null}case\"un\":return Q.op===\"-\"?-this.value(Q.arg,Z):!this.value(Q.arg,Z);case\"if\":return this.value(Q.test,Z)?this.value(Q.yes,Z):this.value(Q.no,Z);case\"bin\":{if(Q.op===\"&&\")return this.value(Q.left,Z)&&this.value(Q.right,Z);if(Q.op===\"||\")return this.value(Q.left,Z)||this.value(Q.right,Z);let $=this.value(Q.left,Z),q=this.value(Q.right,Z);switch(Q.op){case\"+\":return this.charge(Array.isArray($)&&Array.isArray(q)?[...$,...q]:typeof $===\"string\"||typeof q===\"string\"?String($??\"\")+String(q??\"\"):$+q);case\"-\":return $-q;case\"*\":return $*q;case\"/\":return $/q;case\"%\":return $%q;case\"==\":return pQ($,q);case\"!=\":return!pQ($,q);case\"<\":return $<q;case\"<=\":return $<=q;case\">\":return $>q;case\">=\":return $>=q}break}case\"call\":{if(Q.name===\"if\")return this.value(Q.args[0],Z)?this.value(Q.args[1],Z):this.value(Q.args[2],Z);let $=m(q0,Q.name),q=m($0,Q.name),J=m(Z0,Q.name);if($){let V=this.value(Q.args[0],Z);if(!Array.isArray(V))throw new R(\"\");let K=(W)=>W&&((H)=>this.value(W,{...Z,it:H}));return this.charge($(V,K(Q.args[1]),Q.name===\"groups\"?K(Q.args[2]):Q.args[2]&&this.value(Q.args[2],Z)))}if(q)return this.charge(q(...Q.args.map((V)=>this.value(V,Z))));if(J)return this.charge(J(this.file.get(\"app\")?.tree.named.lang?.v,...Q.args.map((V)=>this.value(V,Z))));if(m(AQ,Q.name))throw new R(\"\");throw new R(\"\")}case\"ref\":throw new R(\"\")}throw new R(\"\")}drawn(Q,Z,$,q){if(this.step(),$.k===\"list\")return $.items.flatMap((U)=>this.drawn(Q,Z,U,q));let{name:J=\"\",args:V=[],named:K={}}=$.k===\"call\"?$:{};if(J===\"each\"&&V.length===2){let U=this.value(V[0],q),F=Array.isArray(U)?U:Number.isInteger(U)&&U>=0&&U<=500?Array.from({length:U},(Y,X)=>X):null;if(!F)throw new R(\"\");return F.flatMap((Y,X)=>this.drawn(Q,Z,V[1],{...q,it:Y,each:X}))}let W=Object.hasOwn(Q,J)?Q[J].args:null;if(!W)throw new R(\"\");let H=Q[J].last,j=W.at(-1)!==\"shapes\"&&Q[J].color!==!1,z=\"\";if(V.length<W.length||V.length>W.length+(H?2:j?1:0))throw new R(\"\");if(K.visible&&!this.value(K.visible,q))return[];let P=(U,F)=>{if(U===null||U===\"\"||typeof U===\"boolean\"||!Number.isFinite(Number(U)))throw new R(\"\");return YQ(Number(U),2)},C=(U,F)=>{return String(U??\"ink\")},G={[Z]:J};if(W.forEach((U,F)=>{if(U===\"shapes\"){G.shapes=this.drawn(Q,Z,V[F],q);return}let Y=this.value(V[F],q);G[U]=tZ.has(U)?String(Y??\"\"):U===\"points\"?[Y].flat().map((X)=>P(X,U)):P(Y,U)}),G.axis!==void 0&&![\"x\",\"y\",\"z\"].includes(G.axis))throw new R(\"\");if(j)G.color=C(V[W.length]?this.value(V[W.length],q):null,\"color\");if(V.length===W.length+2)G[H]=P(this.value(V[W.length+1],q),H);for(let[U,F]of Object.entries(K)){let Y=Z===\"shape\"||[\"radius\",\"width\",\"size\",\"stroke\"].includes(U)?zZ[U]:void 0;if(U===\"visible\")continue;if(!Y)throw new R(\"\");let X=this.value(F,q);if(Y.kind===\"boolean\"){if(X)G[U]=!0}else G[U]=Y.kind===\"color\"?C(X,U):Y.kind===\"text\"?String(X??\"\"):P(X,U)}if(++this.shapes>Q0)this.stop(\"\");return[G]}recompute(){let Q=this.file.of(\"formula\");for(let Z=0;Z<=Q.length;Z++){let $=JSON.stringify(this.state);for(let q of Q){if(this.tripped)return;this.attempt(q,()=>this.write(this.pathOf(q.key),this.value(q.tree,{}),{}))}if(JSON.stringify(this.state)===$)return}}act(Q,Z={}){for(let $ of Q.k===\"list\"?Q.items:[Q]){if($.k!==\"call\"||!oZ.has($.name))throw new R(\"\");let[q,J]=$.args;if($!==(Q.k===\"list\"?Q.items[0]:Q))this.recompute();let V=()=>{if(![\"path\",\"field\",\"index\"].includes(q?.k))throw new R(\"\");return q},K=(W)=>{let H=this.value(W,Z);if(!Array.isArray(H))throw new R(\"\");return H};switch($.name){case\"set\":this.write(V(),LQ(this.charge(this.value(J,Z))),Z);break;case\"toggle\":this.write(V(),!this.value(V(),Z),Z);break;case\"inc\":this.write(V(),this.charge((this.value(V(),Z)||0)+(J?this.value(J,Z):1)),Z);break;case\"dec\":this.write(V(),(this.value(V(),Z)||0)-(J?this.value(J,Z):1),Z);break;case\"clear\":this.write(V(),[],Z);break;case\"flip\":{let W=K(V()),H=this.charge(this.value(J,Z));this.write(V(),W.includes(H)?W.filter((j)=>j!==H):[...W,H],Z);break}case\"move\":{let W=$.args.length===1;if(W&&!Z.list)throw new R(\"\");let H=W?this.read(Z.list,{}):K(V()),j=W?H.indexOf(Z.it):H.findIndex((P)=>this.value(J,{...Z,it:P})),z=j+this.value(W?q:$.args[2],Z);if(j>=0&&z>=0&&z<H.length)H.splice(z,0,H.splice(j,1)[0]);break}case\"when\":if(this.value(q,Z))this.act(J,Z);else this.recompute();break;case\"animate\":{if(q?.k!==\"path\"||!q.abs)throw new R(\"\");let W=q.segs.join(\"/\"),H=Number(this.value(q,Z))||0,j=J?Number(this.value(J,Z)):NaN,z=$.args[2]?Number(this.value($.args[2],Z)):0;if(!Number.isFinite(j)||!Number.isFinite(z))throw new R(\"\");let P=(this.state._run??[]).filter((G)=>G.path!==W),C=GQ.now();if(z>0&&j!==H)P.push({path:W,from:H,to:j,start:C,end:C+z,at:H});else this.write(q,j,Z);if(P.length)this.state._run=P;else delete this.state._run;break}case\"append\":{let W=LQ(this.charge(this.value(J,Z)));K(V()).push(W),this.tagRows([W]);break}case\"remove\":{if(!q){if(!Z.list)throw new R(\"\");this.write(Z.list,this.read(Z.list,{}).filter((W)=>W!==Z.it),{})}else this.write(V(),K(V()).filter((W)=>!this.value(J,{...Z,it:W})),Z);break}}}this.recompute()}tree(Q=!1){this.reached=new Set;let Z=0,$=0,q=(V,K,W,H,j)=>{if(this.step(),++$>eZ)this.stop(\"\");if(H>40)throw new R(\"\");if(V.k===\"ref\"){let G=this.file.get(V.name);if(this.reached.add(V.name),!G||G.kind!==\"component\")return{type:\"Missing\",name:V.name,props:{},children:[]};return q(G.tree,K,V.name,H+1,[...j,V.name])}if(V.k!==\"call\"||!m(AQ,V.name))return this.fault(W??\"?\",\"\"),{type:\"Missing\",name:W,props:{},children:[]};let z={type:V.name,name:W,props:{},children:[],row:K.it,rowIndex:K.index,list:K.list,call:V},P=rQ(V),C=[];for(let[G,U]of Object.entries(P)){if(U===void 0)continue;if(!AQ[V.name].includes(G)&&!aZ.has(G)&&!(G===\"action\"&&P.bind)){this.fault(W??V.name,\"\");continue}if(V.name===\"Chart\"&&(G===\"point\"||G===\"value\")||V.name===\"List\"&&G===\"group\"||G===\"shapes\"||G===\"bodies\")continue;try{if(G===\"children\")z.children=(U.k===\"list\"?U.items:[U]).map((Y)=>q(Y,K,void 0,H+1,j));else if(G===\"groups\")C=U.k===\"list\"?U.items:[U];else if(G===\"action\")z.props.action=U;else if(G===\"bind\"){if(![\"path\",\"field\",\"index\"].includes(U.k))throw new R(\"\");z.props.bind=U,z.props.value=U.k===\"path\"?this.read(U,K):this.value(U,K)}else if(G===\"template\")z.template=U;else z.props[G]=this.value(U,K);let F=z.props.options;if(G===\"options\"&&F&&typeof F===\"object\"&&!Array.isArray(F))[z.props.options,z.props.labels]=[Object.keys(F),Object.values(F)]}catch(F){this.fault(W??V.name,\"\")}}if(V.name===\"Chart\"){try{z.props.points=(Array.isArray(z.props.items)?z.props.items:[]).map((G)=>({label:P.point?this.value(P.point,{...K,it:G}):\"\",value:Number(P.value?this.value(P.value,{...K,it:G}):G)||0}))}catch(G){this.fault(W??V.name,\"\")}delete z.props.items}if(V.name===\"Drawing\"||V.name===\"Model3d\"){let G=V.name===\"Drawing\"?\"shapes\":\"bodies\",U=P[G]??{k:\"list\",items:[]};if(this.shapes=0,z.props[G]=(U.k===\"list\"?U.items:[U]).flatMap((F)=>{try{return this.drawn(G===\"shapes\"?RQ:nZ,G===\"shapes\"?\"shape\":\"body\",F,K)}catch(Y){return this.fault(W??V.name,\"\"),[]}}),G===\"bodies\"&&z.props.exploded)z.props.bodies=iQ(z.props.bodies)}if(V.name===\"Choices\"){let G=Array.isArray(z.props.value)?z.props.value:[],U=z.props.max!==void 0&&G.length>=z.props.max;z.children=(z.props.options??[]).map((F,Y)=>({type:\"Button\",name:W&&`${W}_opt${Y}`,children:[],props:{label:String(z.props.labels?.[Y]??F),selected:G.includes(F),enabled:G.includes(F)||!U,action:{k:\"call\",name:\"flip\",args:[P.bind,{k:\"lit\",v:F}],named:{}}}}))}if(V.name===\"Tabs\"){let G={k:\"path\",abs:!0,segs:[\"_ui\",W??\"tabs\"+Z++]},U=this.read(G,{})??0;z.props.value=U,z.children=[{type:\"Row\",props:{},children:(z.props.titles??[]).map((F,Y)=>({type:\"Button\",name:W&&`${W}_tab${Y}`,tab:!0,children:[],props:{label:F,selected:Y===U,action:{k:\"call\",name:\"set\",args:[G,{k:\"lit\",v:Y}],named:{}}}}))},...C.map((F,Y)=>({type:\"Column\",props:{visible:Y===U},children:(F.k===\"list\"?F.items:[F]).map((X)=>q(X,K,void 0,H+1,j))}))]}if(V.name===\"List\"&&z.template&&Array.isArray(z.props.items)){let G=(X,I=0)=>{if(!X||I>8)return null;if(X.k===\"call\"&&[\"sort\",\"filter\"].includes(X.name))return G(X.args[0],I+1);if(X.k===\"if\")return G(X.yes,I+1)??G(X.no,I+1);if(X.k!==\"path\")return null;let T=X.abs?this.file.get(\"$/\"+X.segs.join(\"/\")):null;return T?.kind===\"formula\"?G(T.tree,I+1):X},U=G(P.items),F=z.props.items,Y=[];if(P.group)try{let X=F.map((T)=>this.value(P.group,{...K,it:T})),I=[...new Set(X)].flatMap((T)=>F.map((v,h)=>[v,T,h]).filter((v)=>X[v[2]]===T));F=I.map((T)=>T[0]),Y=I.map((T,v)=>v===0||I[v-1][1]!==T[1]?[{type:\"Heading\",props:{text:String(T[1]??\"\")},children:[]}]:[])}catch(X){this.fault(W??V.name,\"\")}z.children=F.flatMap((X,I)=>[...Y[I]??[],q(z.template,{it:X,index:I,list:U},void 0,H+1,j)]),z.source=U,z.blank=(X)=>q(z.template,{it:X,index:0,list:U},void 0,H+1,j)}return z},J=this.file.get(\"root\");if(!J)return{type:\"Missing\",name:\"root\",props:{},children:[]};try{return q(J.tree,{},\"root\",0,[\"root\"])}catch(V){return this.fault(\"root\",V.message),{type:\"Missing\",name:\"root\",props:{},children:[]}}}find(Q,Z){let $=(q)=>q.name===Q&&(Z===void 0||q.rowIndex===Z)?q:q.children.reduce((J,V)=>J??$(V),null);return $(this.tree())}tick(){let Q=GQ.now(),Z=this.state;if(Z._run){if(Z._run=Z._run.filter(($)=>{let q=this.pathOf(\"$/\"+$.path);if(this.read(q,{})!==$.at)return!1;return $.at=Q>=$.end?$.to:$.from+($.to-$.from)*Math.max(0,Q-$.start)/($.end-$.start),this.write(q,$.at,{}),Q<$.end}),!Z._run.length)delete Z._run;this.recompute()}for(let $ of this.file.of(\"need\")){if($.tree.name!==\"timer\"||sQ($.tree))continue;let q=$.key,J=(K,W)=>{try{return Number(K?this.value(K,{}):W)||0}catch{return 0}},V=()=>Number(Z[q+\"_phase\"])||0;if(!Z[q+\"_on\"]){Z[q+\"_end\"]=0;continue}if(!Z[q+\"_end\"])Z[q+\"_end\"]=Q+Math.max(0,V()-Z[q+\"_spent\"])*1000;for(let K=0;Z[q+\"_on\"]&&Q>=Z[q+\"_end\"]&&K<1000;K++){let W=$.tree.named,H=Z[q+\"_end\"],j=J(W.rounds,1),z=Z[q+\"_round\"]>=j;if(!Z[q+\"_resting\"])Z[q]+=1;if(!Z[q+\"_resting\"]&&(!z||j<=1||W.cycle?.v===!0)&&J(W.rest,0)>0)Z[q+\"_resting\"]=!0,Z[q+\"_spent\"]=0;else if(z)Z[q+\"_over\"]=!0,Z[q+\"_on\"]=!1,Z[q+\"_spent\"]=V(),Z[q+\"_end\"]=0;else Z[q+\"_round\"]+=1,Z[q+\"_resting\"]=!1,Z[q+\"_spent\"]=0;if(this.recompute(),Z[q+\"_on\"])Z[q+\"_end\"]=H+V()*1000}if(Z[q+\"_on\"])Z[q+\"_spent\"]=Math.max(0,V()-(Z[q+\"_end\"]-Q)/1000);this.recompute()}}change(Q,Z){this.tick();try{if(this.write(Q.props.bind,Z,{it:Q.row}),Q.props.action)this.act(Q.props.action,{it:Q.row,list:Q.list});else this.recompute()}catch($){return this.failed(Q,$)}return this.tick(),\"\"}fire(Q){if(Q.props.enabled===!1||Q.props.visible===!1)return`${Q.name??Q.type} is not active`;this.tick();try{this.act(Q.props.action,{it:Q.row,list:Q.list})}catch(Z){return this.failed(Q,Z)}return this.tick(),\"\"}failed(Q,Z){return`${Q.name??Q.type}: ${Z.message}`}input(Q,Z,$){this.steps=0;let q=this.find(Q,$);if(q?.props.bind)return this.change(q,Z);return(!q?`no field named ${Q}`:q.props.action?`${Q} is not a field: use tap(${Q})`:`${Q} only shows a value and cannot be set. A check sets a field, such as a NumberField`)+($===void 0?\"\":` in row ${$}`)}press(Q,Z){this.steps=0;let $=this.find(Q,Z);if($?.props.action)return this.fire($);return($?.props.bind?`${Q} is a field with no action: use set(${Q}, value)`:`no control named ${Q}`)+(Z===void 0?\"\":` in row ${Z}`)}}var GQ={gate:\"smoke\",now:()=>Date.now()};var K0=/^[A-Za-z][A-Za-z0-9_]{0,47}$/,W0=new Set([\"__proto__\",\"prototype\",\"constructor\"]);var IQ=100,G0={time:\"number\",date:\"string\"},H0=(Q)=>K0.test(Q)&&!W0.has(Q)&&!/^[dv]\\d*$/.test(Q);function oQ(Q={},Z=new Set(Object.values(Q))){let $=new Map,q=0;return(J,V=J)=>{if($.has(J))return $.get(J);let K=Object.hasOwn(Q,J)?Q[J]:V;if(!Object.hasOwn(Q,J)&&(!H0(K)||Z.has(K))){while(Z.has(\"f\"+q))q++;K=Q[J]=\"f\"+q}return $.set(J,K),Z.add(K),K}}var FQ=(Q)=>Q.replaceAll(\":\",\"_\"),PZ=(Q)=>String(Q).padStart(2,\"0\"),DQ=(Q)=>{let Z=Math.min(1439,Math.max(0,Math.round(Number(Q)||0)));return`${PZ(Math.floor(Z/60))}:${PZ(Z%60)}`},XZ=2048,t=(Q,Z,$)=>Q===\"time\"?dQ(Z)??0:Q===\"date\"?VQ(Z)===null?$:String(Z).trim():Q===\"number\"?Number.isFinite(Z)?Math.min(Number.MAX_SAFE_INTEGER,Math.max(-Number.MAX_SAFE_INTEGER,Z)):0:Q===\"boolean\"?Z===!0:(Z===null||Z===void 0||typeof Z===\"object\"?\"\":String(Z)).slice(0,XZ),nQ=(Q,Z,$)=>({id:String(Q?.id??\"\"),...Object.fromEntries(Z.map(([q,J,V])=>[J,t(V,Q?.[q],$)]))}),SQ=(Q)=>Q?.k===\"path\"&&!Q.abs&&Q.segs.length===1?Q.segs[0]:null;function LZ(Q,Z,$,q,J,V,K){let{kit:W,name:H,walk:j,day:z,fields:P,lists:C,views:G,viewSchemas:U,viewOwners:F,listRoots:Y}=Q,X=Z.source,I=X?.k===\"path\"&&X.abs&&X.segs.length===1&&C[X.segs[0]]?X.segs[0]:null;if(!I||!Z.blank)return V.fault(\"\"),null;let T=C[I],v=oQ();v(\"$id\",\"id\"),T.forEach(([M,S])=>v(\"$\"+M,S));let h={key:$,name:v,stored:new Map(T.map(([M,S,y])=>[M,[S,y]])),computed:new Map,flags:{moves:!1},out:null,id:null},u={id:\"\",...Object.fromEntries(T.map(([M,,S])=>[M,S===\"number\"?0:S===\"boolean\"?!1:\"\"]))},n=(M)=>M.name??`${$}:row`,QQ=Z.blank(u),KQ=j(QQ,n(QQ),q,{scope:h,weighted:!1}),r=J.group,WQ=h.stored.get(SQ(r)),a=r?WQ&&WQ[1]!==\"time\"?WQ[0]:v(\"~group\",\"group\"):null;if(r&&!(WQ&&WQ[1]!==\"time\"))h.computed.set(a,\"string\");let _Q=(M)=>M?.k===\"path\"?M.abs&&M.segs.length===1&&M.segs[0]===I:M?.k===\"call\"&&M.name===\"filter\"&&_Q(M.args[0]),zQ=h.flags.moves,ZQ=zQ?v(\"~label\",\"label\"):null;if(ZQ)h.computed.set(ZQ,\"string\");let kQ=Z.children.filter((M)=>M.row).map((M)=>{let S={};if(j(M,n(M),q,{scope:{...h,out:S,id:String(M.row.id??\"\")},weighted:!1,off:V.off}),h.computed.has(a))try{S[a]=t(\"string\",W.value(r,{it:M.row}))}catch{S[a]=\"\"}if(ZQ)S[ZQ]=(T.filter(([,,y])=>y===\"string\").map(([y])=>t(\"string\",M.row[y]).trim()).find(Boolean)??String(M.row.id)).slice(0,300);return{...nQ(M.row,T,z),...S}}),mQ=!(J.items?.k===\"path\"&&_Q(J.items))||h.computed.size>0,B=mQ?H(\"view:\"+$,FQ($)):P.get(I);if(mQ){let M={id:\"string\",...Object.fromEntries(T.map(([,y,x])=>[y,m(G0,x)??x])),...Object.fromEntries(h.computed)},S=Object.keys(M).length;G[B]=kQ,U[B]={type:\"list\",maxItems:IQ,fields:M},F.push(q)}let O=zQ&&V.action((M)=>{let S=W.read(X,{}),y=S.findIndex((b)=>b.id===M?.sourceKey),x=S.findIndex((b)=>b.id===M?.destinationKey);return y<0||x<0?!1:W.fire({props:{action:{k:\"call\",name:\"move\",args:[{k:\"lit\",v:x-y}],named:{}}},row:S[y],list:X})},\"_move\"),f={type:a?\"groupedList\":\"list\",text:V.caption(K),bind:B,...a&&{groupBy:a},...Z.props.empty&&{emptyText:V.plain(Z.props.empty).slice(0,300)},...O&&{reorder:{action:O,labelField:ZQ}},rowTemplate:KQ};return Y.set(f,I),f}var o={width:1024,height:64},eQ=(Q)=>Math.min(2048,Math.max(64,Math.round(Number(Q))||0)),CZ={commands:512,pairs:2048,chars:2048,bytes:32768,segments:512,stops:128},z0={ink:\"default\",muted:\"muted\",accent:\"accent\",success:\"success\",warning:\"warning\",error:\"error\",surface:\"surface\"},RZ=Object.fromEntries(Object.keys(HQ).map((Q)=>[Q,z0[Q]??HQ[Q]])),gQ=(Q,Z,$)=>Math.min($,Math.max(Z,Q));function tQ(Q,Z=RZ,$=1){let[,q,J=\"\",V=\"100\"]=lQ.exec(Q)??[],K=Math.round(gQ(Number(V)*$,0,100)),W=q?Z[q]:CQ.test(Q)?Q:\"default\";if(!CQ.test(W)||!J&&K===100)return J||K<100?`${W}${J}${K<100?`@${K}`:\"\"}`:W;let[H,...j]=QZ(W),z=Number(J)>0,P=z?0.3*Number(J):-0.25*Number(J),C=j.map((G)=>vQ(G+((z?255:0)-G)*P)).join(\"\");return H===255&&K===100?`#${C}`:`#${vQ(H*K/100)}${C}`}var U0=(Q)=>{let Z=0;for(let $ of Q){let q=$.codePointAt(0);Z+=q<128?1:q<2048?2:q<65536?3:4}return Z},wQ=(Q)=>U0(JSON.stringify(Q).replace(/\\//g,\"\\\\/\")),Y0=(Q,Z,$)=>Q.reduce((q,[J,V],K)=>{let[W,H]=Q.at(K-1),j=(J-W)*($-H)-(Z-W)*(V-H);return q+(H<=$?+(V>$&&j>0):-(V<=$&&j<0))},0)!==0;function F0(Q,Z,$){let q=gQ(Z.opacity??1,0,1),J=tQ(Z.color,$,q);if(!Z.gradient||Q.type===\"text\"||Q.type===\"line\"||Q.type===\"arc\"||Q.stroke)return{...Q,color:J};let{x:V,y:K,w:W,h:H}=cQ(Q),j=[{offset:0,color:J},{offset:1,color:tQ(Z.gradient,$,q)}],z=gQ(Z.radial??0,0,1),P=Z.radial!==void 0?{type:\"radial\",x:N(V+W*(0.5-0.12*z)),y:N(K+H*(0.5-0.16*z)),radius:N(Math.max(0.1,Math.max(W,H)*(0.5+0.3*z))),stops:j}:{type:\"linear\",x:N(V+W/2),y:N(K),x2:N(V+W/2),y2:N(K+Math.max(H,0.1)),stops:j};return{...Q,gradient:P}}var MZ=(Q,Z,$,q)=>{let J=Q*Math.PI/180,V=Math.cos(J)*Z,K=Math.sin(J)*Z,W=(H)=>Math.round(H*1e4)/1e4;return[W(V),W(-K),W(K),W(V),N($),N(q)]};function j0(Q,Z){if(Q.shape===\"group\"){let P=Number.isFinite(Q.scale)&&Q.scale>0?Math.min(Q.scale,64):1;return s([Q.x,Q.y])?[{type:\"group\",transform:MZ(Q.turn??0,P,Q.x,Q.y),children:hQ(Q.shapes??[],Z)}]:[]}let $=RQ[Q.shape]?.draw(Q);if(!$)return[];if($.type===\"image\")return[$];let q=cQ($),[J,V]=[q.x+q.w/2,q.y+q.h/2],K=[];if(Q.glow){let P=N(Math.max(4,Math.max(q.w,q.h)*0.7)),C=(G)=>tQ(Q.color,Z,G*gQ(Q.opacity??1,0,1));if(s([J-P,V-P,J+P,V+P]))K.push({type:\"circle\",x:N(J),y:N(V),radius:P,gradient:{type:\"radial\",x:N(J),y:N(V),radius:P,stops:[{offset:0,color:C(0.55)},{offset:1,color:C(0)}]}})}if(K.push(F0($,Q,Z)),!Q.turn)return K;let[W,H,j,z]=MZ(Q.turn,1,0,0);return[{type:\"group\",transform:[W,H,j,z,N(J-W*J-j*V),N(V-H*J-z*V)],children:K}]}var hQ=(Q,Z=RZ)=>Q.reduce(($,q)=>{let J=j0(q,Z),V=J.length===1?J[0]:null;if(V?.type!==\"text\")return $.push(...J),$;let K=V.color,W;{let{x:H,y:j}=V;for(let{type:z,x:P,y:C,radius:G,width:U,height:F,points:Y,color:X}of $)if(z===\"circle\"?(H-P)**2+(j-C)**2<=G**2:z===\"polygon\"?Y0(Y,H,j):/^r/.test(z)&&H>=P&&H<=P+U&&j>=C&&j<=C+F)W=X;K=CQ.test(W)?QZ(W).reduce((z,P,C)=>z+[0,2126,7152,722][C]*(P/255)**2.2,0)>1800?\"#000000\":\"#ffffff\":W===\"muted\"||Number(lQ.exec(W??\"\")?.[3]??100)<=50?\"default\":W&&W!==\"surface\"?\"surface\":K===\"surface\"?\"default\":K}return $.push({...V,color:K}),$},[]),TZ=(Q,Z)=>[{shape:\"rect\",x:0,y:0,w:o.width,h:o.height,color:\"muted\",radius:o.height/2},{shape:\"circle\",x:o.width-o.height,y:o.height/2,r:10,color:Z},{shape:\"rect\",x:0,y:0,w:Math.round(o.width*Q),h:o.height,color:Z,radius:o.height/2}],jQ={width:320,height:240},AZ={type:\"rect\",x:0,y:0,...jQ,color:\"muted\"},bQ={graphite:\"#242424\",blue:\"#0056d0\",teal:\"#006b5f\",green:\"#006e1d\",amber:\"#7f5700\",orange:\"#b02e00\",red:\"#c00015\",pink:\"#bc004f\",purple:\"#9e00c3\",indigo:\"#1a41ff\",brown:\"#a83900\"},_Z={clean:\"blue\",soft:\"teal\",expressive:\"purple\",editorial:\"brown\",technical:\"indigo\"},IZ=(Q,Z)=>{let $=Q||(Object.hasOwn(_Z,Z)?_Z[Z]:\"orange\");return Object.hasOwn(bQ,$)?bQ[$]:bQ.graphite},B0=[-0.42,0.76,0.5],OQ=0.38,EQ=12,QZ=(Q,Z)=>{let $=Q===\"accent\"?Z:Object.hasOwn(HQ,Q)?HQ[Q]:CQ.test(Q)?Q:HQ.ink,q=$.length===7?\"ff\"+$.slice(1):$.slice(1);return[0,2,4,6].map((J)=>parseInt(q.slice(J,J+2),16))},vQ=(Q)=>Math.min(255,Math.max(0,Math.round(Q))).toString(16).padStart(2,\"0\"),MQ=(Q,Z)=>Q.map(($,q)=>$-Z[q]),P0=(Q,Z)=>[Q[1]*Z[2]-Q[2]*Z[1],Q[2]*Z[0]-Q[0]*Z[2],Q[0]*Z[1]-Q[1]*Z[0]],NZ=(Q,Z)=>Q[0]*Z[0]+Q[1]*Z[1]+Q[2]*Z[2];function X0({body:Q,x:Z,y:$,z:q,w:J,h:V,d:K,radius:W,length:H,axis:j}){let z=[Z,$,q];if(Q===\"box\"){let F=[J,V,K].map((X)=>Math.abs(X)/2),Y=[];for(let X of[0,1,2])for(let I of[-1,1]){let T=[[-1,-1],[1,-1],[1,1],[-1,1]].map(([v,h])=>{let u=[0,0,0];return u[X]=I,u[(X+1)%3]=v,u[(X+2)%3]=h,z.map((n,QQ)=>n+u[QQ]*F[QQ])});Y.push(I>0?T:T.reverse())}return Y}let P=Math.max(0,\"xyz\".indexOf(j)),C=(F)=>Array.from({length:EQ},(Y,X)=>{let I=X/EQ*2*Math.PI,T=[...z];return T[P]+=F*Math.abs(H)/2,T[(P+1)%3]+=Math.cos(I)*Math.abs(W),T[(P+2)%3]+=Math.sin(I)*Math.abs(W),T}),[G,U]=[C(-1),C(1)];return[[...G].reverse(),U,...G.map((F,Y)=>[F,G[(Y+1)%EQ],U[(Y+1)%EQ],U[Y]])]}function DZ(Q,Z,$,q,J,V,K=bQ.graphite){let W=($?iQ(Q):Q).flatMap((F)=>X0(F).map((Y)=>({body:F,points:Y}))),H=W.flatMap((F)=>F.points),j=[0,1,2].map((F)=>(H.reduce((Y,X)=>Math.min(Y,X[F]),1/0)+H.reduce((Y,X)=>Math.max(Y,X[F]),-1/0))/2),z=H.reduce((F,Y)=>Math.max(F,Math.hypot(...MQ(Y,j))),0.000001),P=(Number.isFinite(Z)?Z:0)*Math.PI/180,C=Math.min(J,V)*0.44/z,G=[0,0,z*5],U=(F)=>{let[Y,X,I]=MQ(F,j),T=Y*Math.cos(P)+I*Math.sin(P),v=-Y*Math.sin(P)+I*Math.cos(P);return[T,X*Math.cos(OQ)-v*Math.sin(OQ),X*Math.sin(OQ)+v*Math.cos(OQ)]};return W.map(({body:F,points:Y})=>{let X=Y.map(U);return{body:F,seen:X,normal:P0(MQ(X[1],X[0]),MQ(X[2],X[0])),depth:X.reduce((I,T)=>I+T[2],0)/X.length}}).filter(({seen:F,normal:Y})=>NZ(Y,MQ(G,F[0]))>0).sort((F,Y)=>F.depth-Y.depth).flatMap(({body:F,seen:Y,normal:X})=>{let I=0.7+0.3*Math.abs(NZ(X,B0)/Math.hypot(...X)),[T,...v]=QZ(String(F.color),K),h=Y.map(([n,QQ,KQ])=>{let r=G[2]/(G[2]-KQ);return[N(J/2+n*C*r),N(V/2-QQ*C*r)]});if(!s(h.flat()))return[];let u=vQ(T*(q&&F.part!==q?0.4:1));return[{type:\"polygon\",points:h,color:`#${u===\"ff\"?\"\":u}${v.map((n)=>vQ(n*I)).join(\"\")}`}]})}var L0={move:1,line:1,quad:2,cubic:3,close:0},ZZ=(Q)=>Q.type===\"group\"?1+Q.children.reduce((Z,$)=>Z+ZZ($),0):(Q.points?.length??(Q.segments?Q.segments.reduce((Z,$)=>Z+L0[$.type],0):Q.type===\"line\"?2:1))+(Q.gradient?Q.gradient.type===\"linear\"?2:1:0),fQ=(Q)=>(Z)=>Z.type===\"group\"?Z.children.reduce(($,q)=>$+fQ(Q)(q),0):Q(Z),SZ=fQ((Q)=>Q.text?.length??0),$Z=(Q)=>Q.type===\"group\"?1+Q.children.reduce((Z,$)=>Z+$Z($),0):1,OZ=fQ((Q)=>Q.segments?.length??0),EZ=fQ((Q)=>Q.gradient?.stops.length??0),C0=(Q)=>{let Z=Q.flatMap(([,q])=>q),$=(q)=>Z.reduce((J,V)=>J+q(V),0);return{commands:$($Z),pairs:$(ZZ),chars:$(SZ),bytes:wQ(Object.fromEntries(Q.map(([q,J])=>[q,{commands:J}]))),segments:$(OZ),stops:$(EZ)}};function bZ(Q){let Z=C0(Q.map(([q])=>[q,[]])),$=!1;return Object.fromEntries(Q.map(([q,J])=>[q,{commands:J.filter((V,K)=>{let W={commands:$Z(V),pairs:ZZ(V),chars:SZ(V),bytes:wQ(V)+(K?1:0),segments:OZ(V),stops:EZ(V)};if($||=Object.keys(CZ).some((H)=>Z[H]+W[H]>CZ[H]),!$)for(let H of Object.keys(W))Z[H]+=W[H];return!$})}]))}var M0=0.6,BQ=Object.freeze({surface:\"\",pointer:0,x:0}),_0=(Q)=>N((Q%360+360)%360);function gZ(Q,Z,{surfaceId:$,x:q,phase:J,pointer:V}){let K=Z.find((j)=>j.id===$)?.model,W=Q.state._drag??BQ;if(!K||!Number.isFinite(q))return!1;if(J===\"down\"){if(W.surface&&W.pointer!==V)return!1;return Q.state._drag={surface:$,pointer:V,x:q},\"\"}if(![\"move\",\"up\",\"cancel\"].includes(J)||W.surface!==$||W.pointer!==V)return!1;Q.state._drag=J===\"move\"?{...W,x:q}:BQ;let H=Number.isFinite(K.props.value)?K.props.value:0;return J===\"cancel\"?\"\":Q.change(K,_0(N(H+(q-W.x)*M0)))}var vZ=32,N0=512,R0=49152;var wZ=256,T0=8,A0={vibrationMs:600,toneMs:600};var hZ=1000,I0=86400000,D0=1000,S0=50,O0={from:0,to:0,start:0,end:0,at:0};function E0(Q){let Z=Q.file.get(\"app\")?.tree,$=(q)=>q?Q.value(q,{}):void 0;return{title:$(Z?.args[0]),...Object.fromEntries(Object.entries(Z?.named??{}).map(([q,J])=>[q,$(J)]))}}var kZ=(Q)=>Q.value({k:\"call\",name:\"today\",args:[]},{}),mZ={eyebrow:{emphasis:\"label\",tone:\"accent\"},muted:{tone:\"muted\"},source:{emphasis:\"source\",tone:\"muted\"}},b0=new Set([\"List\",\"Dialog\",\"Chart\",\"Table\",\"Tabs\",\"Timer\",\"Drawing\",\"Model3d\"]);var qZ=(Q,Z)=>Number.isFinite(Q)&&Number.isFinite(Z)&&Z>0?Math.min(100,Math.max(0,Q/Z*100)):0,KZ=(Q)=>Q?.k===\"call\"?Q.name===\"append\"||Q.args.some(KZ):Q?.k===\"list\"&&Q.items.some(KZ),yQ=(Q,Z)=>Object.fromEntries(Z.filter(($)=>Q[$]!==void 0).map(($)=>[$,Q[$]])),PQ=(Q,Z,$)=>{let q={...$,...yQ(Q.props,Z)};return Object.keys(q).length?{style:q}:{}},fZ=(Q,Z=Q.props.align)=>PQ(Q,[\"gap\"],Z&&{crossAlign:Z}),e=(Q)=>Q.props.icon===void 0?{}:{icon:m(YZ,Q.props.icon)??Q.props.icon},g0=30,v0=100,uZ=(Q,Z,$)=>{let q=Array.isArray(Q.props.options)?Q.props.options:[],J=typeof($??q[0]),V=new Set,K=[];return q.forEach((W,H)=>{let j=Z.plain(Q.props.labels?.[H]??W),z=J+W;if(typeof W!==J||![\"string\",\"number\",\"boolean\"].includes(J)||J===\"number\"&&!Number.isFinite(W)?1:V.has(z)?2:!j.trim()?3:K.length===g0?4:0)return;V.add(z),K.push({text:j.slice(0,v0),value:W})}),K},yZ=(Q,Z)=>{let $=uZ(Q,Z,Q.props.value),q=$.length>6;if(!$.length)return null;return Z.inside($),{type:q?\"select\":\"segmented\",...Z.field(),...q&&e(Q),options:$}},JZ=(Q,Z,$,q)=>(J,V)=>{let{min:K=Z,max:W=$,step:H,value:j}=J.props,z=(C)=>Number.isFinite(C)&&C>=K&&C<=W,P=q?(C)=>Array.isArray(C)&&C.length===2&&C.every(z)&&C[0]<=C[1]:z;if(!q&&!z(j))V.keep(Number.isFinite(j)?Math.min(W,Math.max(K,j)):K);return{type:Q,...V.field(q&&((C)=>P(C)?C:void 0)),min:K,max:W,...yQ(J.props,[\"step\"]),...e(J)}},w0={TextField:(Q)=>typeof Q===\"string\",Slider:Number.isFinite},h0={Column:(Q,Z)=>Z.form()??{type:\"column\",...PQ(Q,[\"gap\"]),children:Z.children(!0)},Row:(Q,Z)=>Z.form()??{...Q.children.some(($)=>$.props.weight!==void 0)?{type:\"row\",wrap:!1,...fZ(Q,Q.props.align??\"center\"),children:Z.children(!0)}:{type:\"flowRow\",...fZ(Q),children:Z.children()},...Q.props.max&&{layout:{maxWidth:Q.props.max}}},Grid:(Q,Z)=>({type:\"grid\",columns:Q.props.columns??2,children:Z.children()}),Card:(Q,Z)=>({type:\"card\",...Q.props.variant===\"hero\"&&{variant:\"hero\"},...e(Q),children:Z.children()}),Section:(Q,Z)=>{let $=Z.plain(Q.props.title);return{type:\"section\",...$&&{text:$},children:Z.children()}},Text:(Q,Z)=>({type:\"text\",...Z.text(\"text\"),...PQ(Q,[\"emphasis\",\"align\",\"tone\"],m(mZ,Q.props.variant))}),Heading:(Q,Z)=>({type:\"text\",...Z.text(\"text\"),style:{emphasis:\"title\"}}),Metric:(Q,Z)=>({type:\"metric\",text:Z.plain(Q.props.label),bind:Z.shown(\"value\"),...e(Q),...PQ(Q,[\"emphasis\",\"tone\"])}),Badge:(Q,Z)=>({type:\"badge\",...Z.text(\"value\"),...e(Q),...PQ(Q,[\"tone\"])}),Emoji:(Q,Z)=>({type:\"emoji\",text:Z.plain(Q.props.label)||Z.plain(Q.props.value)||Z.caption(),...Z.value(\"value\"),...yQ(Q.props,[\"size\"])}),Divider:()=>({type:\"divider\"}),Notice:(Q,Z)=>({type:\"banner\",...e(Q),...PQ(Q,[\"tone\"]),children:[{type:\"text\",...Z.text(\"text\")}]}),Button:(Q,Z)=>{let $=Q.props.variant??(Q.props.selected===void 0?void 0:Q.props.selected?\"filled\":\"tonal\");return{type:\"button\",text:Z.caption(Q.props.label),action:Z.action(),...$&&{variant:$},...e(Q)}},IconButton:(Q,Z)=>({type:\"icon\",name:e(Q).icon,text:Z.caption(Q.props.label),...Q.props.action&&{action:Z.action()}}),NumberField:JZ(\"numberInput\",0,1e6),Slider:JZ(\"slider\",0,100),RangeSlider:JZ(\"rangeSlider\",0,100,1),TextField:(Q,Z)=>({type:\"input\",...Z.field(),...e(Q)}),Toggle:(Q,Z)=>({type:\"toggle\",...Z.field()}),CheckBox:(Q,Z)=>({type:\"checkbox\",...Z.field(),...yQ(Q.props,[\"variant\"])}),Segmented:yZ,Select:yZ,DateField:(Q,Z)=>({type:\"dateInput\",...Z.field(),...e(Q)}),TimeField:(Q,Z)=>({type:\"timeInput\",...Z.field(DQ),...e(Q)}),Choices:(Q,Z)=>{let $=uZ(Q,Z,Array.isArray(Q.props.value)?Q.props.value[0]:void 0);if(!$.length)return null;return Z.inside($),{type:\"multiChoice\",...Z.field((q)=>Array.isArray(q)&&!(Q.props.max!==void 0&&q.length>Q.props.max)?q:void 0),options:$}},Dialog:(Q,Z)=>Z.modal(),List:(Q,Z)=>Z.list(),Progress:(Q,Z)=>{let{value:$,max:q}=Q.props,J=Q.props.variant===\"dots\"&&!Z.scope&&Number.isInteger(q)&&q>=1&&q<=BZ;return Z.drawn(qZ($,q)/100)??{type:\"progress\",text:Z.label(),variant:J?\"dots\":\"bar\",min:0,max:J?q:100,bind:Z.computed(\"value\",J?Number.isFinite($)?Math.min(q,Math.max(0,$)):0:qZ($,q))}},Timer:(Q,Z)=>{let $=Math.round(Number(Q.props.seconds)*1000);return{type:\"timer\",text:Z.label(),mode:\"countdown\",action:Z.action(()=>!1),foreground:{durationMs:Number.isFinite($)?Math.min(I0,Math.max(hZ,$)):hZ,...A0}}},Ring:(Q,Z)=>({type:\"progressRing\",text:Z.label(),size:\"large\",min:0,max:100,bind:Z.computed(\"value\",qZ(Q.props.value,Q.props.max)),...Q.props.text!==void 0&&{children:[{type:\"text\",...Z.text(\"text\"),style:{emphasis:\"display\"}}]}}),Chart:(Q,Z)=>Z.chart(),Table:(Q,Z)=>Z.table(),Tabs:(Q,Z)=>Z.tabs(),Drawing:(Q,Z)=>Z.canvas(hQ(Q.props.shapes),eQ(Q.props.width),eQ(Q.props.height)),Model3d:(Q,Z)=>Z.canvas([AZ,...DZ(Q.props.bodies??[],Q.props.value??30,!1,Q.props.selected,jQ.width,jQ.height,Z.accent)],jQ.width,jQ.height,!1,Q.props.bind?Q:void 0)},f0=(Q)=>Q?.k===\"path\"&&Q.abs?[Q,!1]:Q?.k===\"un\"&&Q.op===\"!\"&&Q.arg.k===\"path\"&&Q.arg.abs?[Q.arg,!0]:Q?.k===\"bin\"&&Q.op===\"!=\"&&Q.left.k===\"path\"&&Q.left.abs&&Q.right.k===\"lit\"?[Q.left,Q.right.v]:null;function VZ(Q,Z,$=!1){let q=oQ(Z.names,new Set([...Object.values(Z.names),...Z.fields.flatMap(([,B])=>B.split(\".\")),...Z.anim?[Z.anim.field]:[],...Z.drag?[Z.drag]:[]])),J=new Map(Z.fields),V=Z.kinds??{},K=Z.lists??{},W=kZ(Q),H=(B,O)=>$?Z.types[B]??=typeof O===\"number\"?\"number\":\"string\":Z.types[B]??\"string\",j=[],z=new Map,P=new Set,C=new Map,G=new Map,U=new Map,F=[],Y={},X={},I=[],T={d:new Map,v:new Map},v={},h=new Map(Q.file.of(\"need\").filter((B)=>B.tree.name===\"timer\"&&!sQ(B.tree)).map((B)=>[`${B.key}_bar`,B.key])),u=[],{kind:n,preset:QQ,palette:KQ}=E0(Q),r=(B,O,f,M,S)=>{let y=S.scope;if(y){let i=y.stored.get(SQ(f));if(i&&i[1]!==\"time\"&&(B===\"d\"||i[1]===\"boolean\"))return i[0];let JQ=y.name(`${B}:${O}`,O.replace(/\\W/g,\"_\")),UQ=B===\"v\"?\"boolean\":H(`${y.key}/${JQ}`,M);if(y.computed.set(JQ,UQ),y.out)y.out[JQ]=B===\"v\"?Boolean(M):t(UQ,M);return JQ}let x=f?.k===\"path\"&&f.abs?f.segs:null;if(x?.length===1&&J.has(x[0])&&V[x[0]]!==\"time\"&&(B===\"d\"||typeof M===\"boolean\"))return J.get(x[0]);let b=q(`${B}:${O}`,x?x.join(\"_\"):O.replace(/\\W/g,\"_\"));if(!T[B].has(b)){let i=B+(T[B].size<vZ?\"\":Math.floor(T[B].size/vZ)+1);T[B].set(b,`${i}.${b}`),(v[i]??={})[b]=B===\"v\"?Boolean(M):t(H(b,M),M)}return T[B].get(b)},WQ=(B,O,f)=>{if(!B)return z.set(O,f);if(B.id===null)return;let M=C.get(O);if(!M)C.set(O,M=new Map),z.set(O,(S)=>M.get(S?.key)?.(S.value)??!1);M.set(B.id,f)},a=(B,O,f,M)=>{f=B.name??f;let S=M.scope,y=m(h0,B.type),x=(L)=>j.push([f,L]);if(!y)return x(\"\"),null;if(S&&b0.has(B.type))return x(\"\"),null;let b=B.call?rQ(B.call):{},i=M.off||[\"visible\",\"enabled\"].some((L)=>b[L]&&!B.props[L]);if(S&&b.action?.k===\"call\"&&b.action.name===\"move\"&&b.action.args.length===1)return S.flags.moves=!0,null;let JQ={...M,off:i,...B.type===\"Section\"&&{title:B.props.title}},UQ=(L,_=!1)=>b[L]&&(_||b[L].k!==\"lit\")?r(\"d\",`${O}.${L}`,b[L],B.props[L],D):null,D={scope:S,fault:x,off:i,rowOptions:!1,blank:Boolean(S&&!S.out),children:(L=!1,_=JQ)=>B.children.map((A,E)=>a(A,A.name??`${O}:${E}`,f,{..._,weighted:L})).filter(Boolean),caption:(L)=>D.plain(L)||(B.name?B.name:`${f} ${B.props.icon??B.type}`).replace(/_/g,\" \"),label:()=>D.caption(D.plain(B.props.label)||M.title),text:(L)=>{let _=String(B.props[L]??\"\"),A=!S&&b[L]?.k===\"lit\"&&_.length>300,E=UQ(L,A);if(E)return{text:\"\",bind:E};return{text:D.plain(B.props[L])}},plain:(L)=>{if(L!==null&&typeof L===\"object\")return\"\";return String(L??\"\").slice(0,300)},value:(L)=>{let _=UQ(L);return _?{bind:_}:{[L]:B.props[L]}},shown:(L)=>UQ(L)??D.computed(L,B.props[L]),computed:(L,_)=>r(\"d\",`${O}.${L}`,null,_,D),keep:(L)=>{if(!D.blank)F.push([B,L])},inside:(L)=>{let _=B.props.value,A=L.map((w)=>w.value),E=B.type!==\"Choices\"?A.includes(_)?_:A[0]:(Array.isArray(_)?_:[]).filter((w,p,c)=>A.includes(w)&&c.indexOf(w)===p);if(JSON.stringify(E)===JSON.stringify(_))return;D.keep(E)},field:(L=(_)=>_)=>{let _;if(S)_=S.stored.get(SQ(b.bind))?.[0];else{let w=b.bind?.k===\"path\"&&b.bind.abs&&b.bind.segs.length===1?b.bind.segs[0]:null;_=J.get(w)}let A=m(w0,B.type),E=D.action((w)=>{if(A&&(i||!A(w)))return!1;let p=L(w);return p===void 0?!1:Q.change(B,p)});if(A)P.add(E);return{text:D.caption(B.props.label),bind:_,action:E,...A&&{inputMode:\"live\"}}},action:(L=()=>{let A=M.sheet?.add===B&&Q.nextId,E=Q.fire(B);if(E===\"\"&&A&&Q.nextId>A)Q.state[M.sheet.flag]=!1;return E},_=\"\")=>{let A=q(\"@\"+O+_,FQ(O+_));return WQ(S,A,L),A},form:()=>{let L=`${O}:open`;if(!J.has(L))return null;let _=B.children.find((w)=>w.type===\"Button\"&&KZ(w.props.action)),A=D.caption(_?.props.label),E=(w)=>()=>{return Q.state[L]=w,\"\"};return{type:\"column\",children:[{type:\"button\",text:A,action:D.action(E(!0),\"_open\"),variant:\"filled\",icon:\"Plus\"},{type:\"bottomSheet\",text:A,visibleBind:J.get(L),dismissAction:D.action(E(!1),\"_dismiss\"),children:D.children(!1,{...JQ,off:i||!Q.state[L],sheet:{flag:L,add:_}})}]}},modal:()=>{let L=f0(b.visible),_=(E)=>E.props.bind!==void 0||E.children.some(_),A=D.action(()=>{if(!L)return!1;return Q.write(L[0],L[1],{}),Q.recompute(),\"\"},\"_dismiss\");return{type:B.children.some(_)?\"bottomSheet\":\"dialog\",text:D.caption(B.props.title),dismissAction:A,children:D.children()}},list:()=>LZ(_Q,B,O,f,b,D,M.title),drawn:(L)=>{let _=!S&&h.get(B.name);return _?D.canvas(hQ(TZ(L,Q.state[_+\"_resting\"]?\"success\":\"accent\")),o.width,o.height,Boolean(Q.state[_+\"_on\"])):null},canvas:(L,_,A,E=!1,w=void 0)=>{let p=q(\"surface:\"+O,FQ(O));return u.push({id:p,width:_,height:A,running:E,commands:L,line:f,model:w}),{type:\"canvas\",text:D.label(),surfaces:[p]}},accent:IZ(KQ,QQ??\"clean\"),chart:()=>{let L=B.props.kind??\"bar\",_=D.label().slice(0,100),A=(Array.isArray(B.props.points)?B.props.points:[]).slice(-wZ),[E,w]=[[\"labels\",\"string\",\"label\"],[\"values\",\"number\",\"value\"]].map(([l,g,k])=>{let d=q(`chart:${O}:${l}`,FQ(`${O}_${l}`));return Y[d]=A.map((NQ)=>t(g,NQ[k])),X[d]={type:\"array\",maxItems:wZ,items:g},I.push(f),d}),p=A.map((l)=>t(\"string\",l.label)),c=B.props.bind&&D.field((l)=>l===\"\"||p.includes(l)?l:void 0);return{type:\"chart\",text:_,kind:L,categories:E,series:[{text:_,bind:w}],...c&&{action:c.action,selectedKeyBind:c.bind}}},table:()=>{let L=(Array.isArray(B.props.heads)?B.props.heads:[]).map((g)=>D.plain(g).slice(0,100)||\" \"),_=(Array.isArray(B.props.rows)?B.props.rows:[]).map((g)=>Array.isArray(g)?g:[g]),A=q(`table:${O}:rows`,FQ(`${O}_rows`)),E=(g)=>`c${g}`;Y[A]=_.map((g)=>({id:D.plain(g[0]).slice(0,128)||\" \",...Object.fromEntries(L.map((k,d)=>[E(d),D.plain(g[d]??\"\")]))})),X[A]={type:\"list\",maxItems:IQ,fields:{id:\"string\",...Object.fromEntries(L.map((g,k)=>[E(k),\"string\"]))}},I.push(f);let w=Y[A].map((g)=>g.id),p=B.props.bind&&D.field((g)=>g===\"\"||w.includes(g)?g:void 0),c=B.props.column,l=B.props.signs;return{type:\"table\",text:D.plain(B.props.label).slice(0,100)||D.caption(),bind:A,columns:L.map((g,k)=>({text:g,field:E(k)})),...Array.isArray(l)&&{variant:\"compare\",signs:l.map((g)=>D.plain(g).slice(0,16))},visibleRows:T0,...p&&{action:p.action,selectedKeyBind:p.bind},...p&&Number.isInteger(c)&&c>0&&c<L.length&&{markColumn:E(c)}}},tabs:()=>{let L=`${B.name}:tab`,_=B.children.slice(1),A=(Array.isArray(B.props.titles)?B.props.titles:[]).map((k)=>D.plain(k)),E=_.findIndex((k)=>!k.children.length),w=!B.name||!J.has(L)?\"a Tabs needs a line of its own, such as pages = Tabs(titles, groups), for the field of its open tab\":!_.length||_.length>30?`a Tabs has 1 to 30 groups, and this one has ${_.length}`:E>=0?`group ${E+1} of this Tabs is empty, and Alphy draws no empty group. Put a component into it`:null;if(w)return x(w),null;let c=A.length===_.length&&new Set(A).size===A.length&&A.every((k)=>k.trim()&&k.length<=100)?A:_.map((k,d)=>String(d+1));if(!c.includes(Q.state[L]))Q.state[L]=c[0];let l=Q.state[L],g=(k)=>{if(!c.includes(k))return!1;return Q.state[L]=k,\"\"};return{type:\"column\",children:[{type:\"tabs\",text:D.label(),bind:J.get(L),action:D.action(g),options:c.map((k)=>({text:k,value:k}))},..._.map((k,d)=>({type:\"column\",visibleBind:r(\"v\",`${O}.tab${d}`,null,c[d]===l,D),children:k.children.map((NQ,cZ)=>a(NQ,NQ.name??`${O}:tab${d}:${cZ}`,f,{...JQ,off:i||c[d]!==l,weighted:!0})).filter(Boolean)}))]}}},$Q=y(B,D);if(!$Q)return null;G.set($Q,f);for(let L of[\"visible\",\"enabled\"]){if(!b[L])continue;if(b[L].k!==\"lit\")$Q[L+\"Bind\"]=r(\"v\",`${O}.${L}`,b[L],B.props[L],D);else if(!B.props[L])$Q[L]=!1}if(B.props.weight!==void 0&&M.weighted)$Q.layout={...$Q.layout,weight:B.props.weight};if(B.props.fit)$Q.layout={...$Q.layout,fillWidth:!1};return $Q},_Q={kit:Q,name:q,walk:a,day:W,fields:J,lists:K,views:Y,viewSchemas:X,viewOwners:I,listRoots:U},zQ=Q.tree(),ZQ=a(zQ,\"root\",\"root\",{scope:null,weighted:!0}),kQ=[...n?[{type:\"text\",text:String(n),style:mZ.eyebrow}]:[],...zQ.type===\"Column\"?ZQ?.children??[]:[ZQ].filter(Boolean)];return{ui:{type:\"theme\",preset:QQ??\"clean\",...KQ&&{palette:KQ},cornerRadius:20,children:[{type:\"column\",style:{gap:\"medium\",...zQ.type===\"Column\"&&ZQ?.style,padding:\"medium\"},children:kQ}]},values:v,views:Y,viewSchemas:X,viewOwners:I,controls:z,liveIds:P,fixes:F,faults:j,owners:G,listRoots:U,drawn:u}}function y0(Q,Z,$,q){let J=Q.kinds?.[Z];if(J===\"list\")return Array.isArray($)?$.map((V)=>nQ(V,Q.lists[Z],q)):[];if(J===\"array\")return Array.isArray($)?$:[];return J===\"time\"||J===\"date\"?t(J,$,q):typeof $===\"number\"||typeof $===\"string\"?t(typeof $,$):$}function k0(Q,Z,$,q={}){let J={},V=kZ(Q);for(let[K,W]of Z.fields){let[H,j]=W.split(\".\"),z=y0(Z,K,Q.state[K],V);if(j)(J[H]??={})[j]=z;else J[H]=z}for(let K of Z.keep??[])J[K]=q[K];if(Z.anim)J[Z.anim.field]=Object.fromEntries(Z.anim.keys.map(([K,W])=>{let H=Q.state._run?.find((j)=>j.path===K);return[W,H?{from:H.from,to:H.to,start:H.start,end:H.end,at:H.at}:O0]}));if(Z.drag)J[Z.drag]=Q.state._drag??BQ;return{...J,...$.views,...$.values}}function m0(Q,Z,$){let q=Z.kinds??{},J=Q.nextId-1;for(let[K,W]of Z.fields){let[H,j]=W.split(\".\"),z=j?$[H]?.[j]:$[H];if(z===void 0)continue;if(q[K]===\"list\")Q.state[K]=(Array.isArray(z)?z:[]).map((P)=>{return J=Math.max(J,Number(/^r(\\d+)$/.exec(P.id)?.[1]??0)),{id:P.id,...Object.fromEntries(Z.lists[K].map(([C,G,U])=>[C,U===\"time\"?DQ(P[G]):P[G]]))}});else Q.state[K]=q[K]===\"time\"?DQ(z):q[K]===\"array\"&&Array.isArray(z)?[...z]:q[K]===\"record\"?{...z}:z}if(Z.drag)Q.state._drag={...BQ,...$[Z.drag]};Q.nextId=J+1;let V=Z.anim?Z.anim.keys.map(([K,W])=>({path:K,...$[Z.anim.field]?.[W]})).filter((K)=>K.end):[];if(V.length)Q.state._run=V;else delete Q.state._run;Q.recompute()}function u0(Q,Z){let $=new aQ(new xQ().feedText(Q.lines),!1);if($.nextId=Q.next,Q.shown)$.shown=Object.fromEntries(uQ(Q.shown).entries);return m0($,Q,Z),$}function xZ(Q,Z,$=Date.now()){let{state:q,event:J}=Q;if(J.type===\"preview\"||J.type===\"render\")return{state:q};let V=GQ.now;GQ.now=()=>$;let K;try{K=u0(Z,q);let W=Z.kinds??{},H=VZ(K,Z);if(Z.drag&&J.type===\"open\")K.state._drag=BQ;let j=J.type===\"open\"||J.type===\"timer\"&&(J.timerId===Z.anim?.field?Boolean(K.state._run):H.drawn.some((Y)=>Y.id===J.timerId&&Y.running)),P=J.type===(H.liveIds.has(J.actionId)?\"liveInput\":\"action\")?H.controls.get(J.actionId):j?()=>K.tick():J.type===\"pointer\"?()=>gZ(K,H.drawn,J):void 0;if(!P||P(J.value)===!1)return{state:q};for(let[Y,X]of Object.entries(W))if(X===\"list\"&&K.state[Y]?.length>(m(Z.caps,Y)??IQ)||X===\"array\"&&K.state[Y]?.length>N0)return{state:q};let C=!1;for(let[Y,X]of Z.fields){let[I,T]=X.split(\".\"),v=T?q[I]?.[T]:q[I],h=K.state[Y],u=typeof v;if(W[Y]||![\"string\",\"number\",\"boolean\"].includes(u)||(u===\"number\"?Number.isFinite(h):typeof h===u))continue;K.state[Y]=u===\"number\"?v:t(u,h),C=!0}if(C)K.recompute();let G=VZ(K,Z);if(G.fixes.length){for(let[Y,X]of G.fixes)K.write(Y.props.bind,X,{it:Y.row});K.recompute(),G=VZ(K,Z)}let U=k0(K,Z,G,q),F=JSON.stringify(G.ui)===JSON.stringify(H.ui)?{state:U}:{state:U,ui:G.ui};if(K.tripped||wQ(U)>R0)return{state:q};if(G.drawn.length){if(F.surfaces=bZ(G.drawn.map(({id:Y,commands:X})=>[Y,X])),J.type!==\"liveInput\")F.timers=[...G.drawn.filter((Y)=>Y.running).map(({id:Y})=>({id:Y,intervalMs:D0})),...K.state._run?[{id:Z.anim.field,intervalMs:S0}]:[]]}return F}catch(W){if(K?.tripped)return{state:q};throw W}finally{GQ.now=V}}__handle(xZ);})();";
globalThis.compileStep = compileStep;
globalThis.previewStep = previewStep;
})();
