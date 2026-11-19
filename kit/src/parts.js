// Generic parts. A part turns one need line into ordinary app lines: state, formulas, components, and checks.
// After that the lines are plain lines. The loop can patch each one by its name.
// A part is a kind of need, never a named app.

import { DslError, parseLine, show } from "./dsl.js";
import { foreground, phased } from "./core.js";

/** The choice step. Rules here. A closed-choice model (Jev, or the local one) can take its place. */
export const rules = (question, options, fallback) => fallback ?? options[0];

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
    // Back is outlined and forward is filled, both explicit, as in the steps and a lesson: Alphy fills the first available button with no variant.
    if (!opt.back) return {
      lines: [`$/${name}_i = 0`, `$/${name}_now := at(${show(list)}, $/${name}_i)`, `$/${name}_end := $/${name}_i >= len(${show(list)})`,
        ...picture, `${name}_front = Heading(text(${now(opt.front)}))`,
        `${name}_prev = Button(${text("prev", "Back")}, action=dec($/${name}_i), enabled=$/${name}_i > 0, variant="outlined")`, `${name}_next = Button(${text("next", "Next")}, action=inc($/${name}_i), variant="filled")`,
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
    lines.push(`${name}_back = Button(${text("back", "Back")}, action=dec($/${name}_step), enabled=$/${name}_step > 0, variant="outlined")`,
      `${name}_next = Button(${text("next", "Next")}, action=inc($/${name}_step), visible=$/${name}_step < ${last}, variant="filled")`,
      `${name}_finish = Button(${text("finish", "Done")}, action=set($/${name}_done, true), visible=$/${name}_step == ${last}, variant="filled")`,
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
        hero === name ? `${name}_view = Card([${ring}], variant="hero")` : `${name}_view = Section(${show(title)}, [${ring}])`],
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
    if (turn) { lines.push(`${name}_left = IconButton("ArrowLeft", action=dec(${at("turn")}, 30), label="−30°")`, `${name}_right = IconButton("ArrowRight", action=inc(${at("turn")}, 30), label="+30°")`); tools.push(`${name}_left`, `${name}_right`); }
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
      lines.push(`${name}_back = Button(${text("back", "Back")}, action=dec($/${name}), enabled=$/${name} > 1, variant="outlined")`, `${name}_next = Button(${text("next", "Next")}, action=inc($/${name}), enabled=$/${name} < ${count}, variant="filled")`);
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

export const PART_KINDS = Object.keys(PARTS);

/**
 * Expands every need of the file that is new or changed. Returns the new lines and feeds them into the file.
 * The main element is the first figure or progress need, unless the choice step picks a different one.
 */
export async function expand(file, choose = rules) {
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
