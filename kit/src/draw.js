// The drawing of a surface: the kit's shapes as Alphy's drawing commands (GraphicsContract.frame), and the limits of one reply.

import { HEX, TONES, explode } from "./core.js";

/** The surface of a timer's bar, in Alphy's pixels of a surface: the bar fills it, 16 to 1, as a thick bar of the card's width. Alphy takes 64 pixels high at least. */
export const BAR_SURFACE = { width: 1024, height: 64 };
/** A side of a surface: Alphy takes 64 to 2048 pixels each way. */
export const sideOf = (value) => Math.min(2048, Math.max(64, Math.round(Number(value)) || 0));
/** What Alphy draws in one reply, for all surfaces together (GraphicsContract.frame, ArtifactProtocol.validateSurfaces). */
export const LIMITS = { commands: 512, pairs: 2048, chars: 2048, bytes: 32768 };
/**
 * The kit's color names as Alphy's colors: a tone of the theme for a role, else the hex of the kit. The role surface is the lowest container of the
 * theme (NativeToolUi.drawingRoles): white on a light theme and near black on a dark one (L1 item 6).
 */
const ROLES = { ink: "default", muted: "muted", accent: "accent", success: "success", warning: "warning", surface: "surface" };
export const PALETTE = Object.fromEntries(Object.keys(TONES).map((name) => [name, ROLES[name] ?? TONES[name]]));
/** A color name of the palette, a hex that passes as it is, or the default ink for each other text. */
const colorOf = (color, palette) => (Object.hasOwn(palette, color) ? palette[color] : HEX.test(color) ? color : "default");

/** The UTF-8 bytes of a text. The sandbox has no TextEncoder that the kit can count on. */
export const utf8 = (text) => {
  let count = 0;
  for (const char of text) { const code = char.codePointAt(0); count += code < 0x80 ? 1 : code < 0x800 ? 2 : code < 0x10000 ? 3 : 4; }
  return count;
};
/** The bytes of a value in JSON as the host writes it: org.json writes "/" as "\/". */
export const hostBytes = (value) => utf8(JSON.stringify(value).replace(/\//g, "\\/"));
/** A sum such as x - r keeps 2 decimals, as the kit's numbers do, with no float noise. */
export const fixed = (value) => Math.round(value * 100) / 100;
const clamp = (value, low, high) => Math.min(high, Math.max(low, value));
/** True when each value is a coordinate that Alphy takes: a finite number from -8192 to 8192. */
const inside = (values) => values.every((value) => Number.isFinite(value) && Math.abs(value) <= 8192);
const closed = (points) => ({ type: "polyline", points: [...points, points[0]], stroke: 2 });
/**
 * True when the point X, Y is inside the [x, y] points by the non-zero rule, the fill rule of an Android Path (SurfaceDrawing.kt): each edge that
 * crosses the ray from the point toward a larger x adds 1 when it goes toward a larger y and -1 when it goes toward a smaller y, and the sum is not 0.
 */
const wound = (points, X, Y) => points.reduce((turns, [x, y], index) => {
  const [a, b] = points.at(index - 1), side = (x - a) * (Y - b) - (X - a) * (y - b);
  return turns + (b <= Y ? +(y > Y && side > 0) : -(y <= Y && side < 0));
}, 0) !== 0;

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
 * The shapes of the kit as Alphy's commands, each with its color by the palette: a color name as an Alphy color. A shape that Alphy cannot
 * draw is left out, and a size past Alphy's bounds is clamped. A text takes a color that reads on the last filled disk, box, or polygon under its anchor (L1 item 6):
 * - on a shape in a hex color, black or white by the luminance of the shape. With a gamma of 2.2 and a limit of 0.18, the contrast is 4.5 or more.
 * - on a shape in an ink role, the surface role. The host keeps each ink and the surface role at 4.5 or more (NativeToolUi.drawingRoles).
 * - on a shape in muted, the default ink. A muted shape is a light tint of the muted ink, and only the default ink keeps 4.5 on that tint.
 * - on a shape in surface, or on no shape, its own color, and the default ink for the surface role.
 * A line and an outline cover no text.
 */
export const commandsOf = (shapes, palette = PALETTE) => shapes.reduce((drawn, shape) => {
  const command = commandOf(shape);
  let color = colorOf(shape.color, palette), under;
  if (command?.type === "text") {
    const { x: X, y: Y } = command;
    // A box is a rect or a roundRect, the only types that start with r. An arc is the outline of a disk.
    for (const { type, x, y, radius, width, height, points, color: fill } of drawn) {
      if (type === "circle" ? (X - x) ** 2 + (Y - y) ** 2 <= radius ** 2 : type === "polygon" ? wound(points, X, Y)
        : /^r/.test(type) && X >= x && X <= x + width && Y >= y && Y <= y + height) under = fill;
    }
    // The relative luminance of the shape, with weights times 10000.
    color = HEX.test(under) ? (argbOf(under).reduce((sum, channel, index) => sum + [0, 2126, 7152, 722][index] * (channel / 255) ** 2.2, 0) > 1800 ? "#000000" : "#ffffff")
      : under === "muted" ? "default" : under && under !== "surface" ? "surface" : color === "surface" ? "default" : color;
  }
  if (command) drawn.push({ ...command, color });
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

/** What one command costs: a box, a circle, and a text 1 pair, a line 2, a polygon or a polyline 1 for each point (GraphicsContract.frame). */
const pairsOf = (command) => command.points?.length ?? (command.type === "line" ? 2 : 1);
const charsOf = (command) => command.text?.length ?? 0;
/**
 * What the surfaces of one reply cost, from [id, commands] in order: the commands, pairs, and text characters of all of them, and
 * the bytes of {id: {commands}, ...} as the host writes it. The emitter checks the first reply with it, and fit starts from the reply with no commands.
 */
export const budgetOf = (surfaces) => {
  const commands = surfaces.flatMap(([, list]) => list);
  return { commands: commands.length, pairs: commands.reduce((sum, command) => sum + pairsOf(command), 0),
    chars: commands.reduce((sum, command) => sum + charsOf(command), 0), bytes: hostBytes(Object.fromEntries(surfaces.map(([id, list]) => [id, { commands: list }]))) };
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
    const cost = { commands: 1, pairs: pairsOf(command), chars: charsOf(command), bytes: hostBytes(command) + (index ? 1 : 0) };
    full ||= Object.keys(LIMITS).some((key) => used[key] + cost[key] > LIMITS[key]);
    if (!full) for (const key of Object.keys(cost)) used[key] += cost[key];
    return !full;
  }) }]));
}
