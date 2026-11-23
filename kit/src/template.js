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

import { DslError, parseExpression, parseLine, show } from "./dsl.js";
import { BUDGET, HEIGHT, SENTENCE_END, TEMPLATE_HEAD, WIDTH, colorFault, colored, cut, cutLong, headFault, isText, nodesIn, numberOf } from "./form-base.js";
import { FORMS_BY_WORD } from "./forms.js";
import { DATA_WORDS, checkedValues, numbersIn, rejectFile, sourceFault } from "./form-data.js";
import { missOf } from "./library.js";
import { diagramOf } from "./form-scene.js";

export { BUDGET, TEMPLATE_HEAD } from "./form-base.js";

export const isTemplateFile = (text) => typeof text === "string" && TEMPLATE_HEAD.test(text.split("\n").find((line) => line.trim()) ?? "");

const readsV = (node) => node.k === "path" && node.abs && node.segs.length === 1 && node.segs[0] === "v";
/** The value of a play line (block 2 plan, M1): $/t, which a part reads beside $/v. */
const readsT = (node) => node.k === "path" && node.abs && node.segs.length === 1 && node.segs[0] === "t";

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
const rank = (name, word) => (name === word ? 0 : name === "control" ? 1 : name === "play" ? 1.2 : name === "head" || /^t[1-3]$/.test(name) ? 1.5 : name === "card" ? 3 : name === "source" ? 3.5
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
export function readTemplate(text) {
  const [first = "", ...rest] = text.split("\n").filter((line) => line.trim() && !line.trim().startsWith("#")), word = TEMPLATE_HEAD.exec(first)?.[1], form = FORMS_BY_WORD[word];
  const faults = [], lines = [], misses = [], fault = (name, message) => { faults.push(`${name}: ${message}`); return message; };
  // A part reads $/t only beside a play line of a scene.
  const playing = Boolean(form.playFault) && rest.some((raw) => lineOf(raw)?.name === "play"), read$ = (node) => readsV(node) || (playing && readsT(node));
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
    if (name !== "control" && name !== "card" && !form.part.test(name) && !(name === "source" && form.family) && !(name === "play" && form.playFault)) { fault(name, `the template ${word} has no line ${name}. Its lines are ${form.lines}`); continue; }
    if (lines.some((line) => line.name === name)) { fault(name, `this is a second line ${name}. Each line has its own name`); continue; }
    const entry = read(raw, parseLine);
    let tree = entry instanceof DslError ? null : entry.tree, message = entry instanceof DslError ? entry.message : null, source = raw.trim();
    if (missOf(tree)) misses.push(missOf(tree));
    if (tree && name === "control") message = entry.kind === "state" ? (isText(tree) || numberOf(tree) !== undefined ? null : "$/v = VALUE takes a number or a text in quotes") : form.controlFault(tree);
    else if (tree && name === "play") message = form.playFault(tree);
    else if (tree) {
      // The kept line has the cut text, so the stored file and a later change have it too.
      const long = cutLong(tree, name, word), short = form.cut ? form.cut(long, name) : long;
      if (show(short) !== show(tree)) { tree = short; source = `${name} = ${show(tree)}`; }
      const shape = name !== "card" && word === "scene", path = nodesIn(tree, (node) => node.k === "path" && !read$(node))[0];
      message = (name === "card" ? form.cardFault(tree) : name === "source" ? sourceFault(tree) : form.partFault(tree, head?.named?.lang?.v))
        ?? (path ? `a line reads only $/v, the value of the control${playing ? ", and $/t, the value of play" : ""}, and this line reads ${show(path)}` : null);
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
export function mergeTemplate(stored, reply) {
  let lines = stored.split("\n").map(lineOf).filter((line) => line && !line.gone);
  const last = (test) => lines.reduce((found, line, index) => (test(line) ? index : found), -1), part = (line) => /^(?:[sitrp]\d+|head)$/.test(line.name);
  for (const line of reply.split("\n").map(lineOf).filter(Boolean)) {
    if (line.gone) { lines = lines.filter((one) => one.head || one.name !== line.gone); continue; }
    const same = lines.findIndex((one) => (line.head ? one.head : !one.head && one.name === line.name));
    if (same >= 0) { lines[same] = line; continue; }
    const head = last((one) => one.head);
    const control = last((one) => one.name === "control");
    lines.splice(line.head ? 0 : part(line) ? Math.max(last(part), control, last((one) => one.name === "play"), head) + 1 : line.name === "control" ? head + 1
      : line.name === "play" ? Math.max(control, head) + 1 : lines.length, 0, line);
  }
  return lines.map((line) => line.raw).join("\n") + "\n";
}

/**
 * The host check of a searched data form (block 2 plan, decision 3): each number of a data cell or of a point is in the source excerpts.
 * A form that passes gets the host line source = source(SITE, TIME), so it shows its source. A form with a number that no excerpt holds
 * becomes a nodata file with the reason (rejected is that value). A file that is no data form, or nodata, stays as it is.
 */
export function checkData(text, { excerpts, site, time }) {
  const read = isTemplateFile(text) ? readTemplate(text) : null, head = read?.lines[0].tree;
  if (!head || !DATA_WORDS.has(read.word) || read.word === "nodata") return { text, rejected: null };
  const known = new Set(numbersIn(excerpts)), rejected = checkedValues(read).find((value) => numbersIn(value).some((number) => !known.has(number)));
  if (rejected !== undefined) return { text: rejectFile(head, rejected), rejected };
  return { text: mergeTemplate(text, `source = source(${show({ k: "lit", v: site })}, ${show({ k: "lit", v: time })})`), rejected: null };
}

/** The language of the first line of a template, or "en". */
const langOf = (read) => read.lines[0].tree?.named?.lang?.v ?? "en";
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
    : tree.name === "steps" ? tree.args[2] ?? tree.args[1]?.items?.at(-1)
      // A diagram starts at its first part (C2), or at the start of its line.
      : tree.name === "parts" ? tree.args[2] ?? { k: "lit", v: diagramOf(drawnParts(read, leftOut), tree, langOf(read)).steps?.[0] ?? "" } : null;
  return isText(start) || numberOf(start) !== undefined ? start : { k: "lit", v: 0 };
}

/**
 * The app file of a template with each line of leftOut left out: the app line, the drawing of 300 × 220, the control (or $/v = START for a
 * control that is left out or has a fault), and the card. Null when the first line has a fault: a stored file with one then fails the change
 * rules as a mismatch (change.js).
 */
export function expandTemplate(read, leftOut) {
  const [{ tree: head }] = read.lines, line = (name) => read.lines.find((one) => one.name === name && one.tree && !leftOut.has(name));
  let parts = drawnParts(read, leftOut);
  if (!head) return null;
  const control = line("control")?.tree, title = show(head.args[0]), form = FORMS_BY_WORD[read.word];
  let card = line("card")?.tree;
  if (form.file) return form.file(head, parts, control, card, line("source")?.tree);
  const out = [`app(${title}, lang=${show(head.named.lang)}, preset="clean", palette=${show(head.named.palette ?? { k: "lit", v: "blue" })})`];
  // A diagram (G1, C2): the kit names the parts of its object and marks the part of the step, or marks the label of the step of a thing with
  // no entry. The card shows the name of the part and its sentence. The model writes no label and no condition for it.
  const diagram = control?.k === "call" && control.name === "parts" ? diagramOf(parts, control, langOf(read)) : null;
  if (diagram?.steps) {
    parts = parts.map((part) => (part === diagram.object ? { ...part, tree: { ...part.tree, named: { ...part.tree.named, parts: { k: "lit", v: true }, mark: parseExpression("$/v") } } }
      : diagram.object ? part : { ...part, tree: markedLabel(part.tree, diagram.steps) }));
    card = diagramCard(read, diagram.steps, card);
  }
  // What the form gives in place of the defaults (forms.js TemplateForm expand).
  const own = FORMS_BY_WORD[read.word].expand?.(parts, control, head) ?? {}, shapes = own.shapes ?? parts.map((part) => show(part.tree));
  out.push(...(own.lines ?? []), `need picture = drawing(${title}, ${WIDTH}, ${HEIGHT}, [${shapes.join(", ")}])`);
  const [label, ...args] = control?.args ?? [], start = show(startOf(read, leftOut));
  if (control?.k !== "call") out.push(`$/v = ${control ? show(control) : start}`);
  else if (control.name === "slider") out.push(`need v = input("slider", ${show(label)}, ${start}, min=${show(args[0])}, max=${show(args[1])}, step=${show(args[2])})`);
  else if (diagram?.steps) out.push(`need v = input("choice", ${show(label)}, ${start}, options=${JSON.stringify(diagram.steps)}, variant="stepper")`);
  else out.push(`need v = input("choice", ${show(label)}, ${start}, options=${show(own.options ?? args[0])})`);
  const play = line("play")?.tree;
  if (play) out.push(`need t = ${show(play)}`);
  if (card) out.push(`need card = card(${show(card.args[0])}, ${own.cardText ?? show(card.args[1])})`);
  return out.join("\n") + "\n";
}

/** A shape of a diagram of a thing with no entry: a text or the label= of a library line that names a step takes the accent while its step shows (G2). */
function markedLabel(tree, steps) {
  const step = (node) => isText(node) && steps.includes(node.v) ? node.v : null, when = (name, other) => parseExpression(`$/v == ${JSON.stringify(name)} ? "accent" : ${show(other)}`);
  if (tree.k === "call" && tree.name === "text" && step(tree.args[2])) return { ...tree, args: tree.args.map((arg, index) => (index === 3 ? when(tree.args[2].v, arg) : arg)) };
  if (tree.k === "call" && tree.name === "use" && step(tree.named.label)) return { ...tree, named: { ...tree.named, tone: when(tree.named.label.v, tree.named.color ?? { k: "lit", v: "ink" }) } };
  return tree;
}
/** The card of a diagram (C2): the name of the part of the step with a capital first letter, and the sentence of the step, else the card text of the file. */
function diagramCard(read, steps, card) {
  const said = (index) => { const raw = read.lines.find((line) => line.name === `say${index + 1}` && line.tree)?.raw; try { return raw && JSON.parse(raw.slice(raw.indexOf("=") + 1)); } catch { return null; } };
  const pick = (values) => parseExpression(steps.slice(0, -1).reduceRight((rest, step, index) => `($/v == ${JSON.stringify(step)} ? ${JSON.stringify(values[index])} : ${rest})`, JSON.stringify(values.at(-1))));
  const fallback = card?.args?.[1] && isText(card.args[1]) ? card.args[1].v : null;
  return { k: "call", name: "card", named: {}, args: [pick(steps.map((step) => step.charAt(0).toUpperCase() + step.slice(1))), pick(steps.map((step, index) => said(index) ?? fallback ?? step))] };
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
  for (const fault of faults) /\bneed v\b/.test(fault) ? add("control", fault) : /\bneed card\b/.test(fault) ? add("card", fault) : /\bneed t\b/.test(fault) ? add("play", fault) : rest.push(fault);
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
export function buildTemplate(text, stored, build, preview = false) {
  const old = stored === null ? null : readTemplate(stored), faults = [], out = new Set();
  let read, leftOut, kept, expanded = null, built = null;
  const settle = () => {
    const back = old ? old.lines.filter((line) => line.tree && out.has(line.name)) : [];
    if (back.length) { text = mergeTemplate(text, back.map((line) => line.raw).join("\n")); read = readTemplate(text); faults.push(...read.faults); }
    leftOut = new Set([...read.lines.filter((line) => !line.tree).map((line) => line.name), ...[...out].filter((name) => !back.some((line) => line.name === name))]);
    const control = read.lines.some((line) => line.name === "control" && line.tree?.k === "call") && !leftOut.has("control");
    const walked = read.lines.find((line) => line.name === "control")?.tree?.name === "parts";
    if (!preview && control && !walked && read.word === "scene" && !read.lines.some((line) => line.tree && line.name !== "control" && !leftOut.has(line.name) && nodesIn(line.tree, readsV).length)) {
      faults.push("control: the control changes nothing on the screen, because no line reads $/v. Make a part read $/v, such as visible=$/v > 2");
      leftOut.add("control");
    }
    // A narration line of the wrong kind is left out with no fault: say beside steps, sayK beside another control, or sayK above the count of steps.
    const steps = read.lines.find((line) => line.name === "control" && ["steps", "parts"].includes(line.tree?.name) && !leftOut.has("control"))?.tree;
    const count = !steps ? 0 : read.word === "items" ? drawnParts(read, leftOut).length
      : steps.name === "parts" ? diagramOf(drawnParts(read, leftOut), steps, langOf(read)).steps?.length ?? 0 : steps.args[1].items.length;
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
