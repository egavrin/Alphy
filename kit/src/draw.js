// The drawing of a surface: the kit's shapes as Alphy's drawing commands (GraphicsContract.frame), and the limits of one reply.

import { HEX, TONE, TONES, explode } from "./core.js";
import { KINDS, boxOf, fixed, inside } from "./kinds.js";

/** The surface of a timer's bar, in Alphy's pixels of a surface: the bar fills it, 16 to 1, as a thick bar of the card's width. Alphy takes 64 pixels high at least. */
export const BAR_SURFACE = { width: 1024, height: 64 };
/** A side of a surface: Alphy takes 64 to 2048 pixels each way. */
export const sideOf = (value) => Math.min(2048, Math.max(64, Math.round(Number(value)) || 0));
/** What Alphy draws in one reply, for all surfaces together (GraphicsContract.frame, ArtifactProtocol.validateSurfaces): also the path segments and the gradient stops. */
export const LIMITS = { commands: 512, pairs: 2048, chars: 2048, bytes: 32768, segments: 512, stops: 128 };
/**
 * The kit's color names as Alphy's colors: a tone of the theme for a role, else the hex of the kit. The role surface is the lowest container of the
 * theme (NativeToolUi.drawingRoles): white on a light theme and near black on a dark one (L1 item 6).
 */
const ROLES = { ink: "default", muted: "muted", accent: "accent", success: "success", warning: "warning", error: "error", surface: "surface" };
export const PALETTE = Object.fromEntries(Object.keys(TONES).map((name) => [name, ROLES[name] ?? TONES[name]]));
const clampOf = (value, low, high) => Math.min(high, Math.max(low, value));
/**
 * A color name of the palette, a hex that passes as it is, or the default ink for each other text. A tone (core.js TONE) of a role of the theme
 * stays a tone, which Alphy mixes from the color of the role in the theme (GraphicsContract.toneOf). A tone of a hex color is mixed here the
 * same way: 30 % toward white for each step up, 25 % toward black for each step down. opacity multiplies the alpha.
 */
export function colorOf(color, palette = PALETTE, opacity = 1) {
  const [, name, step = "", alpha = "100"] = TONE.exec(color) ?? [], share = Math.round(clampOf(Number(alpha) * opacity, 0, 100));
  const base = name ? palette[name] : HEX.test(color) ? color : "default";
  if (!HEX.test(base) || (!step && share === 100)) return step || share < 100 ? `${base}${step}${share < 100 ? `@${share}` : ""}` : base;
  const [a, ...rgb] = argbOf(base), up = Number(step) > 0, mix = up ? 0.3 * Number(step) : -0.25 * Number(step);
  const channels = rgb.map((value) => hex2(value + ((up ? 255 : 0) - value) * mix)).join("");
  return a === 255 && share === 100 ? `#${channels}` : `#${hex2((a * share) / 100)}${channels}`;
}

/** The UTF-8 bytes of a text. The sandbox has no TextEncoder that the kit can count on. */
export const utf8 = (text) => {
  let count = 0;
  for (const char of text) { const code = char.codePointAt(0); count += code < 0x80 ? 1 : code < 0x800 ? 2 : code < 0x10000 ? 3 : 4; }
  return count;
};
/** The bytes of a value in JSON as the host writes it: org.json writes "/" as "\/". */
export const hostBytes = (value) => utf8(JSON.stringify(value).replace(/\//g, "\\/"));
export { fixed } from "./kinds.js";
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
export const commandsOf = (shapes, palette = PALETTE) => shapes.reduce((drawn, shape) => {
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
export const barOf = (share, color) => [
  { shape: "rect", x: 0, y: 0, w: BAR_SURFACE.width, h: BAR_SURFACE.height, color: "muted", radius: BAR_SURFACE.height / 2 },
  { shape: "circle", x: BAR_SURFACE.width - BAR_SURFACE.height, y: BAR_SURFACE.height / 2, r: 10, color },
  { shape: "rect", x: 0, y: 0, w: Math.round(BAR_SURFACE.width * share), h: BAR_SURFACE.height, color, radius: BAR_SURFACE.height / 2 },
];

/**
 * The surface of a model, and its stage: a box under the faces in the muted tone of the theme, a tint that Alphy draws at a low alpha (SurfaceDrawing.kt MUTED_SHAPE_ALPHA).
 * It is a mid tone on a light and on a dark card, so the dark and the white faces both read (Task 11b-2b: a light stage was a bright panel in a dark card).
 */
export const MODEL = { width: 320, height: 240 };
export const STAGE = { type: "rect", x: 0, y: 0, ...MODEL, color: "muted" };
/**
 * The accent of each palette of Alphy on a light theme, for a body whose color is "accent": the primary color of ToolPalettes, and the
 * text color of the shell for graphite (AlphaTheme). The faces of a body keep these colors in a dark theme too, because the light shades each
 * face from a hex color. A preset with no palette has its own palette (NativeToolUi.presetPalette).
 */
export const ACCENTS = { graphite: "#242424", blue: "#0056d0", teal: "#006b5f", green: "#006e1d", amber: "#7f5700", orange: "#b02e00", red: "#c00015", pink: "#bc004f", purple: "#9e00c3", indigo: "#1a41ff", brown: "#a83900" };
const PRESETS = { clean: "blue", soft: "teal", expressive: "purple", editorial: "brown", technical: "indigo" };
export const accentOf = (palette, preset) => {
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
export function polygonsOf(bodies, turnDegrees, exploded, selected, width, height, accent = ACCENTS.graphite) {
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
export const budgetOf = (surfaces) => {
  const commands = surfaces.flatMap(([, list]) => list);
  const sum = (cost) => commands.reduce((total, command) => total + cost(command), 0);
  return { commands: sum(countOf), pairs: sum(pairsOf), chars: sum(charsOf), bytes: hostBytes(Object.fromEntries(surfaces.map(([id, list]) => [id, { commands: list }]))),
    segments: sum(segmentsOf), stops: sum(stopsOf) };
};

/**
 * The surfaces of one reply, {id: {commands}}, from [id, commands] in order. Alphy refuses a reply past a limit for all surfaces
 * together, so the reply keeps the first commands that fit, and none after the first one that does not.
 */
export function fit(surfaces) {
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
