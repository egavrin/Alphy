// The registry of the generic elements (stage 6, L4; spec 12.5): things that a rule draws from numbers, for any subject. A template line places
// one with use(NAME, X, Y, SIZE, [NUMBERS]) (library.js). An element is one entry here: its group, its numbers with their defaults, its color,
// its prompt text, and its draw rule. A draw rule gives shape texts in a box of 100 × 100 units around 0, 0: library.js puts them into one
// group at X, Y, scaled to SIZE, so the element costs one shape of the scene. A number may be a formula of $/v.
//
// The look (stage 6 look target): a ball, a block, and a container have volume (a gradient between two tone steps and a soft highlight),
// and a bar, an axis, and a grid are flat (a soft fill with a thin outline in a darker tone of the role).

import { toneColor } from "./form-base.js";

/** A number text as a number, or null for a formula. */
const num = (text) => (/^-?\d+(?:\.\d+)?$/.test(String(text)) ? Number(text) : null);
/** A tone step of a color text: "accent" with step 1 is "accent+1", and a hex is mixed as the kit mixes a tone of a hex role. A formula stays as it is. */
export function toneOf(color, step = 0, alpha) {
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
export const ELEMENTS = {
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
