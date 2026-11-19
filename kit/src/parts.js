// Generic parts. A part turns one need line into ordinary app lines: state, formulas, components, and checks.
// After that the lines are plain lines. The loop can patch each one by its name.
// A part is a kind of need, never a named app.

import { HOST_ICONS } from "./icons.js";
import { DslError, parseLine, show } from "./dsl.js";
import { ICON_NAMES, MAX_DOTS, foreground, own, phased } from "./core.js";
import { hasWords, wordOf } from "./words.js";

const q = JSON.stringify;
/** The names of IconButton and icon=: each icon of Alphy, and the kit's names that the run-time gives Alphy's name (ICON_NAMES). */
export const ICONS = [...HOST_ICONS, ...Object.keys(ICON_NAMES)];
/** The components that show icon=. A Select of six options or fewer is Alphy's segmented control, which shows none (run.js). */
export const ICONED = ["Button", "IconButton", "Metric", "Badge", "Notice", "Card", "TextField", "NumberField", "Select", "DateField", "TimeField"];
/** The values that an option can take: a value in the line, or each value of a choice (test ? a : b, or if(test, a, b)). Null for each other expression. */
export const choices = (tree) => {
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
export const FIELD_KINDS = { number: "number", money: "number", percent: "number", count: "count", check: "check", switch: "switch", date: "date", time: "time", picture: "picture" };
/** A name of a line or a field of a plan (dsl.js parseLine). */
const WORD = /^[\p{L}_][\p{L}\p{N}_]*$/u;
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
      const pick = k === "percent" && ranged ? "Slider" : "NumberField";
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
        // The three are one group on a line of their own. The field has no step: with one, the host drew its own stepper beside these buttons (L1 item 1).
        lines.push(`${name}_${field}_minus = IconButton("Minus", action=set($${field}, max(0, $${field} - 1)), label=${q(word("Less"))})`, `${name}_${field} = NumberField(${named}, $${field}, min=0, weight=1)`,
          `${name}_${field}_plus = IconButton("Plus", action=inc($${field}), label=${q(word("More"))})`);
        counts.push(`Row([${name}_${field}_minus, ${name}_${field}, ${name}_${field}_plus])`);
      } else if (kind === "picture") lines.push(`${name}_${field} = Picture($${field})`);
      else if (kind === "choice" && edit) lines.push(`${name}_${field} = ${pick(named, "$" + field)}`);
      // A text field of an editable row is a line of its own. The first one shares it only with the emoji of the row, and takes the rest of it (L1 item 1).
      else if (edit && kind !== "choice") lines.push(`${name}_${field} = ${widget}(${named}, $${field}${kind === "text" && icon && !typedTexts.length ? ", weight=1" : ""})`);
      // The host's number and display emphasis are too large for a row (Task 11b-1: "1 kg" in two lines).
      else if (kind !== "text") lines.push(`${name}_${field} = Text($${field}, emphasis="label")`);
      else texts.push(field);
      if (kind === "text" && field !== sign) firstText ??= field;
      if (field !== sign && field !== under && kind !== "count" && !(kind === "text" && !edit)) (kind === "check" ? marks : kind === "text" ? typedTexts : cells).push(`${name}_${field}`);
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
    const titled = [...(texts.length > 1 ? [`${name}_main`] : texts.map((field) => `${name}_${field}`)), ...cells];
    const top = edit ? line([...(first && emoji.length ? [`Row([${name}_emoji, ${first}])`, ...more] : [...emoji, ...typedTexts]), ...cells, ...shut, ...tail, ...counts], ', align="center"')
      : [...line([...emoji, ...(valued.length ? line(titled, ', align="center", weight=1') : titled), ...shut, ...tail]), ...counts];
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
    if (opt.total && !count) notes.push(`need ${name}: total= joins the shown rows and all rows of the hero count, and this list has no hero count, so the kit left it out. A list has one when it is the only main element of the app`);
    if (!count) return { lines, top: [`${name}_view`], note: notes };
    // The only main element of the app is this list (C.6): a hero card with the count of its shown rows comes first. A sign of each row gives no emoji there.
    // total="of" shows all rows too: "12 of 12".
    const counted = opt.countText ? show(opt.countText) : show(title), value = opt.total ? `text(len(${source})) + " " + ${show(opt.total)} + " " + text(len($/${name}))` : `len(${source})`;
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
  entries: ["fields", "labels", "rows", "hidden", "add", "edit", "remove", "confirm", "yes", "no", "emptyText", "addText", "show", "reorder", "filter", "sort", "order", "group", "sheet", "icon", "countText", "icons", "tones", "total"],
  chart: ["kind"], deck: ["front", "back", "mark", "answer", "picture", "field", "check", "right", "wrong", "next", "again", "done", "points", "prev", "show", "yes", "no"],
  steps: ["back", "next", "finish", "done"], tabs: ["title"], progress: [], counter: ["step", "min", "max", "lessText", "moreText", "addText", "icon", "resetText"],
  timer: ["alert", "rounds", "rest", "cycle", "start", "pause", "reset", "done", "workText", "restText"], picture: ["found", "caption", "credit", "tag"], drawing: [],
  model3d: ["turn", "parts", "explode", "joinText", "explodeText"], card: ["eyebrow", "icon"], link: ["note"], lesson: ["next", "back", "stepText"],
  question: ["options", "right", "rightText", "wrongText"], content: [], hole: [],
};
/** The options that each kind of input uses. A number kind is a number field or a slider, and a kind that is not here is a fault of the need. */
const NUMBER_USES = ["min", "max", "step", "icon"];
const INPUT_USES = { number: NUMBER_USES, money: NUMBER_USES, percent: NUMBER_USES, count: NUMBER_USES, range: NUMBER_USES, choice: ["options", "icon"], choices: ["options", "max"], toggle: [], text: ["icon"], date: ["icon"], time: ["icon"] };
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
export const needsOf = (file) => new Map([...(file.partLines ?? [])].flatMap(([need, keys]) => keys.map((key) => [key, need])));
/** The name of a line in a fault: "need NAME" for a line that a part wrote, so that each fault names a line that the author wrote. */
export const faultName = (file, partOf = needsOf(file)) => (line) => (file.get(line)?.origin === "part" && partOf.has(line) ? `need ${partOf.get(line)}` : line);

/**
 * Expands every need of the file that is new or changed. Returns the new lines and feeds them into the file.
 * The main element is the first figure or progress need.
 */
export function expand(file) {
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
