// The registry of the shape kinds and the shape properties (stage 6, L1 and L2). The evaluator (core.js), the drawing (draw.js), the faults
// of a template line (form-scene.js), and the prompt text (prompt.js) come from it: a new kind is one entry here, with its arguments, its
// prompt text, and its draw rule. A draw rule gives Alphy's drawing commands with no color: draw.js adds the paint.

/** False in the run-time text of an app (dsl.js). */
const MESSAGES = true;

/** A sum such as x - r keeps 2 decimals, as the kit's numbers do, with no float noise. */
export const fixed = (value) => Math.round(value * 100) / 100;
/** True when each value is a coordinate that Alphy takes: a finite number from -8192 to 8192. */
export const inside = (values) => values.every((value) => Number.isFinite(value) && Math.abs(value) <= 8192);
const clamp = (value, low, high) => Math.min(high, Math.max(low, value));
const closed = (points) => ({ type: "polyline", points: [...points, points[0]], stroke: 2 });
const pairs = (points) => { const out = []; for (let index = 0; index + 1 < points.length; index += 2) out.push([points[index], points[index + 1]]); return out; };
const at = (point) => ({ x: fixed(point[0]), y: fixed(point[1]) });

/**
 * The segments of a path through [x, y] points: straight lines, or a smooth curve (a Catmull-Rom spline as cubic segments) that passes
 * through each point. A closed path ends with a close segment.
 */
export function segmentsOf(points, smooth, shut) {
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
export const KINDS = {
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
export const PROPERTIES = {
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
export function boxOf(command) {
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
