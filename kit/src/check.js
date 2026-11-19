// The checks of the host: the report of the faults of an app file, its check lines, and the smoke run that uses each control.
// The agent loop reads the faults and the check results. An app that runs does not need this file.

import { HOST_ICONS } from "./icons.js";
import { show } from "./dsl.js";
import { App, CATALOG, ICON_NAMES, given, settings } from "./core.js";

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
export function smoke(file, limit = 60) {
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
export function runChecks(file) {
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
 */
function looks(file) {
  const out = [];
  for (const entry of file.of("component")) walk(entry.tree, (node) => {
    if (node.k !== "call" || !Object.hasOwn(CATALOG, node.name)) return;
    const props = given(node);
    // JavaScript puts the keys of an object that are numbers first, in the order of the numbers.
    const number = props.options?.k === "map" ? props.options.entries.find(([key]) => /^\d+$/.test(key)) : null;
    if (number) out.push(`${entry.key}: a map of options keeps the order of the plan only with keys that are not numbers, and ${JSON.stringify(number[0])} is a number. Give options of numbers as a list`);
    for (const [key, byType] of Object.entries(LOOKS)) {
      if (!props[key]) continue;
      const allowed = Object.hasOwn(byType, node.name) ? byType[node.name] : null, values = choices(props[key]), what = `the ${key} of ${/^[AEIOU]/.test(node.name) ? "an" : "a"} ${node.name}`;
      let field = null;
      walk(props[key], (inner) => { if (!field && inner.k === "path" && !inner.abs) field = show(inner); });
      if (!allowed) out.push(`${entry.key}: ${node.name} has no property ${key}`);
      else if (!values) out.push(`${entry.key}: ${what} is a value in the line, or a choice of values such as $/on ? "a" : "b"`);
      // Alphy has no style of one row: the run-time computes the template once, from a blank row.
      else if (field) out.push(`${entry.key}: ${what} reads ${field}, a field of a row. One template draws each row of a list, so a look value in a row must not read a field of the row. Use a value of the app, such as $/on ? "a" : "b"`);
      else for (const value of values) {
        const one = key === "icon" ? "the name of an icon of Alphy, such as Plus, Check, Trash, Pencil, Star, or Heart" : `one of ${allowed.join(", ")}`;
        if (!allowed.includes(value)) out.push(`${entry.key}: ${what} is ${one}, and ${JSON.stringify(value)} is not one`);
      }
    }
  });
  return out;
}

/** Everything the host can find wrong without a model: line faults, tree faults, values that are not finite, gaps, and look options. */
export function report(file) {
  const app = new App(file);
  app.tree(true);
  const broken = Object.keys(app.state).filter((name) => !finite(app.state[name])).map((name) => `$/${name} is not a finite number at the start`);
  const faults = [
    ...[...file.faults, ...file.partFaults].map((f) => `${f.line == null ? "line" : "line " + f.line}: ${f.message}: ${f.text}`),
    ...app.faults.map((f) => `${f.where}: ${f.message}`),
    ...broken,
    ...(file.get("root") ? [] : ["no root line"]),
    ...gaps(file, app.reached),
    ...looks(file),
  ];
  // The smoke run needs a screen that builds. So it runs only when nothing else is wrong.
  const run = settings.gate === "smoke" && !faults.length ? smoke(file) : { faults: [], notes: [] };
  return { faults: [...new Set([...faults, ...run.faults])], checks: runChecks(file), notes: [...(file.partNotes ?? []), ...run.notes.slice(0, 5)] };
}
