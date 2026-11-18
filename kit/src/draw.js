// The drawing of a surface: the kit's shapes as Alphy's drawing commands (GraphicsContract.frame), and the limits of one reply.

import { TONES } from "./core.js";

/** The surface of a timer's bar, in Alphy's pixels of a surface. */
export const BAR_SURFACE = { width: 320, height: 64 };
/** A side of a surface: Alphy takes 64 to 2048 pixels each way. */
export const sideOf = (value) => Math.min(2048, Math.max(64, Math.round(Number(value)) || 0));
/** What Alphy draws in one reply, for all surfaces together (GraphicsContract.frame, ArtifactProtocol.validateSurfaces). */
export const LIMITS = { commands: 512, pairs: 2048, chars: 2048, bytes: 32768 };
/** A neutral track at 20 % opacity, so the bar reads on a light and on a dark theme. */
const TRACK = "#33808080";
/** The kit's color names as Alphy's colors: a tone of the theme for a role, else the hex of the kit. */
const ROLES = { ink: "default", muted: "muted", accent: "accent", success: "success", warning: "warning" };
export const PALETTE = Object.fromEntries(Object.keys(TONES).map((name) => [name, ROLES[name] ?? TONES[name]]));
const HEX = /^#(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/;
/** A color name of the palette, a hex that passes as it is, or the default ink for each other text. */
const colorOf = (color, palette) => (Object.hasOwn(palette, color) ? palette[color] : HEX.test(color) ? color : "default");

/** The UTF-8 bytes of a text. The sandbox has no TextEncoder that the kit can count on. */
export const utf8 = (text) => {
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
export const commandsOf = (shapes, palette = PALETTE) => shapes.flatMap((shape) => { const command = commandOf(shape); return command ? [{ ...command, color: colorOf(shape.color, palette) }] : []; });

/** The bar of a timer's phase: the track, and the share of the phase that passed (0 to 1) in the color of the phase. Two shapes. */
export const barOf = (share, color) => [
  { shape: "rect", x: 0, y: 24, w: BAR_SURFACE.width, h: 16, color: TRACK, radius: 8 },
  { shape: "rect", x: 0, y: 24, w: Math.round(BAR_SURFACE.width * share), h: 16, color, radius: 8 },
];

/** What one command costs: a box, a circle, and a text 1 pair, a line 2, a polygon or a polyline 1 for each point (GraphicsContract.frame). */
const pairsOf = (command) => command.points?.length ?? (command.type === "line" ? 2 : 1);
const charsOf = (command) => command.text?.length ?? 0;
/** What commands cost in one reply: commands, pairs, text characters, and the bytes of {commands} as the host writes it. */
export const budgetOf = (commands) => ({ commands: commands.length, pairs: commands.reduce((sum, command) => sum + pairsOf(command), 0),
  chars: commands.reduce((sum, command) => sum + charsOf(command), 0), bytes: hostBytes({ commands }) });

/**
 * The surfaces of one reply, {id: {commands}}, from [id, commands] in order. Alphy refuses a reply past a limit for all surfaces
 * together, so the reply keeps the first commands that fit, and none after the first one that does not.
 */
export function fit(surfaces) {
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
