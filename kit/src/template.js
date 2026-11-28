// The template file of a voice screen (docs/superpowers/specs/2026-11-21-stage-templates-stream-design.md, sections 1 and 2): named lines
// in a fixed order, which the kit checks, fixes, and fills into the ready plan of its template, an app file of today's kinds. compile.js
// branches here for a template text, plan, or stored app file.
//
//   scene("Почему корабль не тонет?", lang="ru", palette="blue")      the first line: the template, the title, the language, the palette
//   control = choice("Предмет", ["Корабль", "Гвоздь"])                 the one control. A part reads its value as $/v
//   s1 = rect(0, 110, 300, 110, "water", 0)                            the parts: s1 to s20 are shapes (scene), i1 to i6 items (items)
//   caption = "Полый корабль вытесняет много воды."                    the caption under the controls
//   say = "Выбери предмет и смотри на воду."                           the narration: say for one sentence, say1 to sayN for steps
//
// This module is the engine: it names no template and no line of one. Each template is a ready plan with its line table (ready.js,
// kit/README.md section "Ready plans"), and what the line tables use is in form-base.js and form-data.js.

import { DslError, parseExpression, parseLine, show } from "./dsl.js";
import { BUDGET, SENTENCE_END, cut, cutOf, formFault, formOf, headFault, isText, nodesIn, numberOf } from "./form-base.js";
import { READY } from "./ready.js";
import { missOf } from "./library.js";

export { BUDGET } from "./form-base.js";
export { TEMPLATE_HEAD } from "./ready.js";

/** The word of a first line of a template file whose ready plan the registry has, else undefined. */
const wordOf = (line) => { const word = /^\s*(\w+)\s*\(/.exec(line)?.[1]; return word && Object.hasOwn(READY, word) ? word : undefined; };
export const isTemplateFile = (text) => typeof text === "string" && Boolean(wordOf(text.split("\n").find((line) => line.trim()) ?? ""));

/**
 * Whether the line name is a line of the entry: its name, a name of a list entry with its number (NAME1 to NAMEn), or a narration line (say
 * and say1 to sayN). loose takes each number of a list, as the merge places a line of any template by its name.
 */
function named(entry, name, loose = false) {
  if (name === entry.name) return !entry.count;
  const tail = name.startsWith(entry.name) ? name.slice(entry.name.length) : "";
  if (entry.narration) return /^[1-9]\d*$/.test(tail);
  return Boolean(entry.count) && (loose ? /^\d+$/.test(tail) : /^[1-9]\d*$/.test(tail) && Number(tail) <= entry.count);
}
/** The entry of the line table of ready for the line name, else undefined. */
const entryNamed = (ready, name) => ready.lines.find((entry) => named(entry, name));
/** The entry that the line $/NAME = VALUE stands for (held), such as the control. */
const heldOf = (ready) => ready.lines.find((entry) => entry.held);
/** The entry of the narration lines. */
const narrationOf = (ready) => ready.lines.find((entry) => entry.narration);
/** Whether a node reads the value $/name. */
const reads = (name) => (node) => node.k === "path" && node.abs && node.segs.length === 1 && node.segs[0] === name;

/**
 * The place of a new line of a reply in the merge, by the line tables of each template: 3 for a part, 1 for a line that goes after the first
 * line (the control), 2 for a line that goes after it (play), and 0 for a line at the end.
 */
function placeOf(name) {
  for (const ready of Object.values(READY)) {
    const entry = ready.lines.find((one) => named(one, name, true));
    if (entry) return entry.part ? 3 : entry.merge ?? 0;
  }
  return 0;
}

/**
 * A line of a template file or of a reply: {name, raw}, a first line with head, a delete as {gone}, or null for a line with no name. The line
 * $/NAME = VALUE is the line of the entry that it stands for (held), such as $/v of the control.
 */
function lineOf(raw) {
  const line = raw.trim(), word = wordOf(line), gone = /^delete\s+(\S+)$/.exec(line)?.[1];
  const [, value, plain] = /^(?:\$\/([\p{L}_][\p{L}\p{N}_]*)|([\p{L}_][\p{L}\p{N}_]*))\s*=(?!=)/u.exec(line) ?? [];
  const name = plain ?? (value && Object.values(READY).map(heldOf).find((entry) => entry?.sets === value)?.name);
  return word ? { name: word, head: true, raw: line } : gone ? { gone } : name ? { name, raw: line } : null;
}
/**
 * A narration line: its sentence with each run of space as one space, cut to BUDGET.say, as say = "<JSON string>", so the host reads it as JSON.
 * Null for a line that is no text or has no sentence end inside the cap: a narration line draws nothing, so it is left out with no fault.
 */
function narrationLine(name, raw) {
  let tree;
  try { tree = parseLine(raw.trim())?.tree; } catch (failure) { if (failure instanceof DslError) return null; throw failure; }
  const text = isText(tree) ? cut(tree.v.replace(/\s+/gu, " ").trim(), BUDGET.say) : "";
  return [...text].length <= BUDGET.say && SENTENCE_END.test(text) ? `${name} = ${JSON.stringify(text)}` : null;
}

/**
 * The lines of a template file in the order of its line table (the first line, then the lines of each entry), and the fault of each line as
 * "NAME: message", where NAME is the template word for the first line and for a line with no name. A line of a known name has the tree of its
 * value, as the entry draws it (such as the kit's color for each color name), or null when it has a fault. A second line of a name, a line
 * of a name that the line table does not have, and a line with no name are faults and not lines. The line $/NAME = VALUE is the line of its entry.
 * misses names each library name that a line places and the library does not have (library.js missOf): the miss log of spec 12.8.
 */
export function readTemplate(text) {
  const [first = "", ...rest] = text.split("\n").filter((line) => line.trim() && !line.trim().startsWith("#")), word = wordOf(first), ready = READY[word];
  const faults = [], lines = [], misses = [], fault = (name, message) => { faults.push(`${name}: ${message}`); return message; };
  // A line reads only the values of its template, and a value of a line only beside that line, such as $/t beside play.
  const values = ready.values.filter((value) => !value.line || rest.some((raw) => lineOf(raw)?.name === value.line));
  const readable = (node) => node.k === "path" && node.abs && node.segs.length === 1 && values.some((value) => value.name === node.segs[0]);
  const read = (raw, parse) => { try { return parse(raw.trim()); } catch (failure) { if (failure instanceof DslError) return failure; throw failure; } };
  const head = read(first, parseExpression), headless = head instanceof DslError ? head.message : headFault(head, word, ready.head);
  if (headless) fault(word, headless);
  lines.push({ name: word, raw: first.trim(), tree: headless ? null : head });
  const context = { lines, lang: head?.named?.lang?.v };
  for (const raw of rest) {
    const one = lineOf(raw), name = one?.name, entry = name && !one.head ? entryNamed(ready, name) : undefined;
    if (!one || one.head) { fault(word, `${JSON.stringify(raw.trim().slice(0, 40))} is not a line of the template. Its lines are the first line, ${ready.text}`); continue; }
    if (one.gone) { fault(one.gone, "a template file has no delete line. Only a repair or a change reply deletes a line"); continue; }
    // A narration line has no fault: one that the kit cannot keep is left out, and a second line of a name goes.
    if (entry?.narration) {
      if (lines.some((line) => line.name === name)) continue;
      const kept = narrationLine(name, raw);
      lines.push({ name, raw: kept ?? raw.trim(), tree: kept && { k: "lit", v: name } });
      continue;
    }
    if (!entry) { fault(name, `the template ${word} has no line ${name}. Its lines are ${ready.text}`); continue; }
    if (lines.some((line) => line.name === name)) { fault(name, `this is a second line ${name}. Each line has its own name`); continue; }
    const parsed = read(raw, parseLine);
    let tree = parsed instanceof DslError ? null : parsed.tree, message = parsed instanceof DslError ? parsed.message : null, source = raw.trim();
    if (missOf(tree)) misses.push(missOf(tree));
    if (tree && entry.held && parsed.kind === "state") message = isText(tree) || numberOf(tree) !== undefined ? null : `$/${entry.sets} = VALUE takes a number or a text in quotes`;
    else if (tree) {
      // The kept line has the cut text, so the stored file and a later change have it too.
      const short = cutOf(entry, tree);
      if (show(short) !== show(tree)) { tree = short; source = `${name} = ${show(tree)}`; }
      // A line that sets a value, such as the control, reads no value.
      const path = !entry.sets && nodesIn(tree, (node) => node.k === "path" && !readable(node))[0];
      message = (entry.fault ? entry.fault(tree, context) : formFault(entry, tree))
        ?? (path ? `a line reads only ${values.map((value) => `$/${value.name}, ${value.what}`).join(", and ")}, and this line reads ${show(path)}` : null);
      if (!message && entry.draw) tree = entry.draw(tree);
      if (!message && entry.check) message = entry.check(tree, context);
    }
    if (message) fault(name, message);
    lines.push({ name, raw: source, tree: message ? null : tree });
  }
  // The faults that need more than one line, such as a row with fewer values than the columns of the form.
  for (const [name, message] of ready.context?.(lines) ?? []) {
    const line = lines.find((one) => one.name === name && one.tree);
    if (line) { fault(name, message); line.tree = null; }
  }
  // The voice of a template with voice says a text of a line, such as the card of nodata (D5): a file with no narration line gets say with it.
  const narration = narrationOf(ready), [from, at] = ready.voice ?? [], said = lines.find((line) => line.name === from && line.tree)?.tree.args[at];
  if (ready.voice && !lines.some((line) => entryNamed(ready, line.name)?.narration) && isText(said)) {
    const say = narrationLine(narration.name, `${narration.name} = ${JSON.stringify(said.v)}`);
    if (say) lines.push({ name: narration.name, raw: say, tree: { k: "lit", v: narration.name } });
  }
  const rank = (name) => (name === word ? 0 : ready.lines.indexOf(entryNamed(ready, name)) + 1);
  lines.sort((a, b) => rank(a.name) - rank(b.name));
  return { word, lines, faults, misses };
}

/**
 * The template file after a repair or a change reply: a line of a name that the file has goes in its place, a new part after the last part,
 * a new control after the first line, a new play line after the control, and each other new line at the end (placeOf). A delete removes the
 * line of its name, and a new first line replaces the first line. A line with no name, such as prose or a fence, goes.
 */
export function mergeTemplate(stored, reply) {
  let lines = stored.split("\n").map(lineOf).filter((line) => line && !line.gone);
  const last = (test) => lines.reduce((found, line, index) => (test(line) ? index : found), -1), at = (place) => (line) => !line.head && placeOf(line.name) === place;
  for (const line of reply.split("\n").map(lineOf).filter(Boolean)) {
    if (line.gone) { lines = lines.filter((one) => one.head || one.name !== line.gone); continue; }
    const same = lines.findIndex((one) => (line.head ? one.head : !one.head && one.name === line.name));
    if (same >= 0) { lines[same] = line; continue; }
    const head = last((one) => one.head), control = last(at(1)), place = line.head ? null : placeOf(line.name);
    lines.splice(line.head ? 0 : place === 3 ? Math.max(last(at(3)), control, last(at(2)), head) + 1 : place === 1 ? head + 1 : place === 2 ? Math.max(control, head) + 1 : lines.length, 0, line);
  }
  return lines.map((line) => line.raw).join("\n") + "\n";
}

/**
 * The host check of a searched data form (block 2 plan, decision 3) by the check of its line table (form-data.js checkSource). A form that passes
 * gets the host line source = source(SITE, TIME), so it shows its source. A form with a number that no excerpt holds becomes a nodata file with
 * the reason (rejected is that value). A file of a template with no check stays as it is.
 */
export function checkData(text, data) {
  const read = isTemplateFile(text) ? readTemplate(text) : null, check = read?.lines[0].tree && READY[read.word].check;
  if (!check) return { text, rejected: null };
  const out = check(read, data);
  return out.line ? { text: mergeTemplate(text, out.line), rejected: null } : out;
}

/** The parts of a template that are drawn: each line of a part entry with no fault that is not left out. */
const drawnParts = (read, leftOut) => read.lines.filter((line) => line.tree && !leftOut.has(line.name) && entryNamed(READY[read.word], line.name)?.part);
/**
 * What a line table reads to fill its ready plan: the lines (line gives a drawn line by its name), the first line, its language, the drawn parts,
 * call (the line of the control as a call when it is drawn), and tree (the tree of that line, also one with a fault or one that is left out).
 */
function contextOf(read, leftOut, preview) {
  const held = heldOf(READY[read.word]), head = read.lines[0].tree, line = (name) => read.lines.find((one) => one.name === name && one.tree && !leftOut.has(name));
  const own = held && read.lines.find((one) => one.name === held.name), drawn = held && line(held.name)?.tree;
  let tree = own?.tree;
  try { tree ??= own && parseLine(own.raw)?.tree; } catch { tree = null; }
  return { read, preview, line, head, lang: head?.named?.lang?.v ?? "en", parts: drawnParts(read, leftOut), call: drawn?.k === "call" ? drawn : undefined, tree };
}
/**
 * The value that the held line keeps when its line is left out: the start of the line table, else the argument of the slot start of the form
 * of its line, else the value of its line $/NAME = VALUE, or 0.
 */
function startOf(context) {
  const ready = READY[context.read.word];
  if (ready.start) return ready.start(context);
  const form = formOf(heldOf(ready) ?? {}, context.tree), at = Array.isArray(form) ? form.findIndex((spec) => spec.slot === "start") : -1;
  const start = at >= 0 ? context.tree.args[at] : context.tree?.k === "call" ? undefined : context.tree;
  return isText(start) || numberOf(start) !== undefined ? start : { k: "lit", v: 0 };
}

/** The ready plan with each slot {{name}} filled: a line with a slot that has no value goes, and so does a line that is empty after the fill. */
const filled = (plan, slots) => plan.split("\n").flatMap((line) => {
  let gone = false;
  const text = line.replace(/\{\{(\w+)\}\}/g, (_, name) => { gone ||= slots[name] == null; return slots[name] ?? ""; });
  return gone || !text.trim() ? [] : [text];
}).join("\n") + "\n";

/**
 * The app file of a template with each line of leftOut left out: its ready plan with the slots that the line table gives. The first line gives
 * title, lang, and palette. Each argument of the form of a line with a slot gives that slot, the value of an entry with slot gives it, and a list
 * entry gives what its fill gives. The held line gives start, and held (the value of $/NAME = VALUE) when it is no call or is left out. The fill
 * of the line table comes last. Null when the first line has a fault: a stored file with one then fails the change rules as a mismatch
 * (change.js). preview is true for the lines so far of a stream.
 */
export function expandTemplate(read, leftOut, preview = false) {
  const ready = READY[read.word], [{ tree: head }] = read.lines;
  if (!head) return null;
  const context = contextOf(read, leftOut, preview), slots = { title: show(head.args[0]), lang: show(head.named.lang), palette: show(head.named.palette ?? { k: "lit", v: "blue" }) };
  for (const entry of ready.lines) {
    const drawn = read.lines.filter((line) => line.tree && !leftOut.has(line.name) && entryNamed(ready, line.name) === entry), tree = drawn[0]?.tree, form = formOf(entry, tree);
    if (entry.fill) Object.assign(slots, entry.fill(drawn, context));
    else if (!drawn.length) Object.assign(slots, entry.absent);
    if (Array.isArray(form)) form.forEach((spec, index) => { if (spec.slot && tree.args[index]) slots[spec.slot] = show(tree.args[index]); });
    if (entry.slot && tree) slots[entry.slot] = show(tree);
  }
  const held = heldOf(ready), value = held && context.line(held.name)?.tree;
  if (held) slots.start = show(startOf(context));
  if (held && value?.k !== "call") slots.held = value ? show(value) : slots.start;
  return filled(ready.plan, { ...slots, ...ready.fill?.(context) });
}

/** The text to store: each line with no fault that is not left out, and $/NAME = START in the place of a held line that is left out or has a fault. */
function keptOf(read, leftOut) {
  const held = heldOf(READY[read.word]);
  return read.lines.flatMap((line) => (line.name === held?.name && (!line.tree || leftOut.has(line.name)) ? [`$/${held.sets} = ${show(startOf(contextOf(read, leftOut)))}`]
    : line.tree && !leftOut.has(line.name) ? [line.raw] : [])).join("\n") + "\n";
}

/**
 * The owner of each fault of the compile of an expansion, as a map of a line name to its faults. A fault of a need belongs to the line whose entry
 * owns that need, such as need v to the control and need caption to the caption. Each other fault belongs to each part whose compile with the first
 * line and the control alone has faults, and the faults of that compile name the part in the place of the need. A line table with owner gives
 * the faults of the first line and the control alone to that line.
 */
function ownersOf(read, leftOut, faults, build) {
  const ready = READY[read.word], owners = new Map(), rest = [];
  const add = (name, fault) => owners.set(name, [...(owners.get(name) ?? []), `${name}: ${fault.replace(/^need \w+: /, "")}`]);
  const needs = ready.lines.flatMap((entry) => [entry.owner ?? []].flat().map((need) => [new RegExp(`\\bneed ${need}\\b`), entry.name]));
  for (const fault of faults) { const owner = needs.find(([need]) => need.test(fault)); if (owner) add(owner[1], fault); else rest.push(fault); }
  if (!rest.length) return owners;
  const parts = drawnParts(read, leftOut).map((part) => part.name), cards = ready.lines.filter((entry) => entry.holds === "card").map((entry) => entry.name);
  const alone = (names) => build(expandTemplate(read, new Set([...leftOut, ...owners.keys(), ...cards, ...names]))).faults;
  if (ready.owner && !owners.has(ready.owner)) alone(parts).forEach((fault) => add(ready.owner, fault));
  for (const part of parts) alone(parts.filter((other) => other !== part)).forEach((fault) => add(part, fault));
  return owners;
}

/**
 * The compile of a template file, with build, the compile of compile.js, which comes after this module in the bundle (as change.js nextState
 * takes it). stored is the template file of the stored app of a change, or null for a new screen.
 * A line with a fault is left out: a change takes the stored version of that line instead. A held line whose value no line reads changes
 * nothing (unread), so it is left out too. When the expansion has kit faults, each owner (ownersOf) is left out and the kit compiles once more.
 * Returns {built, word, kept, expanded, leftOut, holds, misses}, where misses are the library names that the file placed and the library does not
 * have. built is the result of build for expanded, the expansion of kept, with each fault of a line that was left out. A whole fault gives built
 * with no definition and expanded null: a fault of the first line, fewer lines of an entry than the whole rule of its line table, or a kit fault
 * of the last compile.
 * preview is true for the lines so far of a stream (compile.js previewStep): an unread control stays, and only a fault of the first line or a
 * kit fault of the last compile is whole, so the first line alone gives the frame. holds is what kept draws: "card" (the text of the screen,
 * such as a caption or a card), "parts" (a part, or a line of an entry that holds parts, such as the control of cells), or "frame" (the first
 * line, maybe the control).
 */
export function buildTemplate(text, stored, build, preview = false) {
  const old = stored === null ? null : readTemplate(stored), faults = [], out = new Set();
  let read, leftOut, kept, expanded = null, built = null;
  const settle = () => {
    const back = old ? old.lines.filter((line) => line.tree && out.has(line.name)) : [];
    if (back.length) { text = mergeTemplate(text, back.map((line) => line.raw).join("\n")); read = readTemplate(text); faults.push(...read.faults); }
    leftOut = new Set([...read.lines.filter((line) => !line.tree).map((line) => line.name), ...[...out].filter((name) => !back.some((line) => line.name === name))]);
    const ready = READY[read.word], held = heldOf(ready), line = held && read.lines.find((one) => one.name === held.name);
    const call = line?.tree?.k === "call" && !leftOut.has(line.name) ? line.tree : null;
    if (!preview && call && held.unread?.forms.includes(call.name) && !read.lines.some((one) => one.tree && one.name !== line.name && !leftOut.has(one.name) && nodesIn(one.tree, reads(held.sets)).length)) {
      faults.push(`${line.name}: ${held.unread.fault}`);
      leftOut.add(line.name);
    }
    // A narration line of the wrong kind is left out with no fault: say beside a walk, sayK beside another control, or sayK above the count of steps.
    const narration = narrationOf(ready), steps = line?.tree && !leftOut.has(line.name) ? held.walk?.(line.tree, contextOf(read, leftOut, preview)) ?? null : null;
    for (const one of read.lines.filter((each) => narration && entryNamed(ready, each.name) === narration)) {
      const index = Number(one.name.slice(narration.name.length));
      if (index === 0 ? steps !== null : index > (steps ?? 0)) leftOut.add(one.name);
    }
    kept = keptOf(read, leftOut);
  };
  // A line drawn in kept that passes test with its entry.
  const drawn = (test) => read.lines.some((line) => line.tree && !leftOut.has(line.name) && test(line, entryNamed(READY[read.word], line.name)));
  // The first line's own fault is in faults already.
  const whole = () => {
    if (!read.lines[0].tree) return faults[0];
    if (preview) return null;
    const ready = READY[read.word], { line: name, count, fault } = ready.whole, entry = ready.lines.find((one) => one.name === name);
    return read.lines.filter((line) => line.tree?.k === "call" && !leftOut.has(line.name) && entryNamed(ready, line.name) === entry).length >= count ? null : fault;
  };
  read = readTemplate(text);
  const misses = read.misses;
  faults.push(...read.faults);
  read.lines.filter((line) => !line.tree).forEach((line) => out.add(line.name));
  settle();
  const compile = () => build(expanded = expandTemplate(readTemplate(kept), new Set(), preview));
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
  // A held line holds its kind only as a call: $/v = VALUE in its place draws no control. A text line, such as a caption, holds it as a text.
  const holding = (kind) => drawn((line, entry) => entry?.holds === kind && (line.tree.k === "call" || !entry.held));
  const holds = holding("card") ? "card" : drawnParts(read, leftOut).length || holding("parts") ? "parts" : "frame";
  return { built: broken ? { faults: unique, notes: built?.notes ?? [], definition: null, language: null, hole: false } : { ...built, faults: unique },
    word: read.word, kept, expanded: broken ? null : expanded, leftOut: [...leftOut], holds, misses };
}
