// draw.js: the kit's shapes as Alphy's drawing commands (GraphicsContract.frame), the colors, and the limits of one reply.

import { expect, test } from "bun:test";
import { LIMITS, budgetOf, commandsOf, fit } from "../src/draw.js";

const one = (shape) => commandsOf([shape]);
/** The bytes of a value in JSON as the host counts them: org.json writes "/" as "\/". */
const hostBytes = (value) => Buffer.byteLength(JSON.stringify(value).replaceAll("/", "\\/"), "utf8");

test("draw: a circle is a circle", () => {
  expect(one({ shape: "circle", x: 160, y: 50, r: 18, color: "sand" })).toEqual([{ type: "circle", x: 160, y: 50, radius: 18, color: "#e7d3a8" }]);
});

test("draw: an outline circle is an arc around the box of the circle", () => {
  expect(one({ shape: "circle", x: 160, y: 160, r: 110, color: "muted", stroke: true }))
    .toEqual([{ type: "arc", x: 50, y: 50, width: 220, height: 220, start: 0, sweep: 360, stroke: 2, color: "muted" }]);
});

test("draw: a rect is a rect, and a roundRect with a radius", () => {
  expect(one({ shape: "rect", x: 0, y: 80, w: 200, h: 20, color: "land" })).toEqual([{ type: "rect", x: 0, y: 80, width: 200, height: 20, color: "#65a30d" }]);
  expect(one({ shape: "rect", x: 0, y: 80, w: 200, h: 20, color: "land", radius: 4 })).toEqual([{ type: "roundRect", x: 0, y: 80, width: 200, height: 20, radius: 4, color: "#65a30d" }]);
});

test("draw: an outline rect is a closed polyline", () => {
  expect(one({ shape: "rect", x: 10, y: 20, w: 30, h: 40, color: "ink", stroke: true }))
    .toEqual([{ type: "polyline", points: [[10, 20], [40, 20], [40, 60], [10, 60], [10, 20]], stroke: 2, color: "default" }]);
});

test("draw: a line is a line with the stroke of its width, 2 by default", () => {
  expect(one({ shape: "line", x1: 0, y1: 0, x2: 200, y2: 100, color: "ink", width: 3 })).toEqual([{ type: "line", x: 0, y: 0, x2: 200, y2: 100, stroke: 3, color: "default" }]);
  expect(one({ shape: "line", x1: 160, y1: 160, x2: 160, y2: 50, color: "accent" })).toEqual([{ type: "line", x: 160, y: 160, x2: 160, y2: 50, stroke: 2, color: "accent" }]);
});

test("draw: a text is centered at its x and y, with a size of 14 by default", () => {
  expect(one({ shape: "text", x: 160, y: 160, text: "Земля", color: "surface", size: 12 }))
    .toEqual([{ type: "text", x: 160, y: 160, size: 12, text: "Земля", align: "center", baseline: "middle", color: "#ffffff" }]);
  expect(one({ shape: "text", x: 1, y: 2, text: "N", color: "muted" })).toEqual([{ type: "text", x: 1, y: 2, size: 14, text: "N", align: "center", baseline: "middle", color: "muted" }]);
});

test("draw: a polygon is a polygon of [x, y] points", () => {
  expect(one({ shape: "polygon", points: [0, 0, 10, 0, 5, 8], color: "#ff0000" })).toEqual([{ type: "polygon", points: [[0, 0], [10, 0], [5, 8]], color: "#ff0000" }]);
});

test("draw: an outline polygon is a closed polyline", () => {
  expect(one({ shape: "polygon", points: [0, 0, 10, 0, 5, 8], color: "#ff0000", stroke: true }))
    .toEqual([{ type: "polyline", points: [[0, 0], [10, 0], [5, 8], [0, 0]], stroke: 2, color: "#ff0000" }]);
});

test("draw: a color name of the kit maps to a tone or a hex", () => {
  const colors = ["ink", "muted", "accent", "success", "warning", "surface", "water", "land", "sun", "sand", "wood", "#336699", "#80FF0000", "purple", "#fff", "error", "toString"];
  expect(commandsOf(colors.map((color) => ({ shape: "circle", x: 1, y: 1, r: 1, color }))).map((command) => command.color)).toEqual([
    "default", "muted", "accent", "success", "warning", "#ffffff", "#38bdf8", "#65a30d", "#facc15", "#e7d3a8", "#a16207", "#336699", "#80FF0000", "default", "default", "default", "default"]);
});

test("draw: budgetOf counts commands, pairs, and characters", () => {
  const commands = [{ type: "circle", x: 1, y: 1, radius: 1, color: "default" }, { type: "rect", x: 0, y: 0, width: 2, height: 2, color: "muted" },
    { type: "line", x: 0, y: 0, x2: 9, y2: 9, stroke: 2, color: "accent" }, { type: "text", x: 5, y: 5, size: 14, text: "1/2 Земля", align: "center", baseline: "middle", color: "#ffffff" },
    { type: "polygon", points: [[0, 0], [10, 0], [5, 8]], color: "success" }, { type: "polyline", points: [[0, 0], [1, 0], [1, 1], [0, 1], [0, 0]], stroke: 2, color: "warning" }];
  // A box, a circle, and a text count one pair, a line two, and a polygon or a polyline one for each point (GraphicsContract.frame).
  expect(budgetOf(commands)).toEqual({ commands: 6, pairs: 13, chars: 9, bytes: hostBytes({ commands }) });
  expect(budgetOf([])).toEqual({ commands: 0, pairs: 0, chars: 0, bytes: 15 });
});

test("draw: a shape that Alphy cannot draw is left out, and a size past Alphy's bounds is clamped", () => {
  const left = [{ shape: "star", x: 1, y: 1, color: "ink" }, { shape: "circle", x: 9000, y: 1, r: 1, color: "ink" }, { shape: "circle", x: 1, y: 1, r: -1, color: "ink" },
    { shape: "circle", x: 1, y: 1, r: 3000, color: "ink" }, { shape: "circle", x: Number.NaN, y: 1, r: 1, color: "ink" }, { shape: "rect", x: 8000, y: 0, w: 500, h: 1, color: "ink" },
    { shape: "rect", x: -9000, y: 0, w: 9000, h: 1, color: "ink", stroke: true }, { shape: "polygon", points: [0, 0, 1, 1], color: "ink" }, { shape: "polygon", points: [0, 0, 1], color: "ink", stroke: true }];
  expect(commandsOf(left)).toEqual([]);
  // A rect with a size below 0 is the same box from its other corner. A stroke, a text size, and the radius of a roundRect stay inside Alphy's bounds.
  expect(commandsOf([{ shape: "rect", x: 10, y: 10, w: -4, h: -6, color: "ink" }, { shape: "line", x1: 0, y1: 0, x2: 1, y2: 1, color: "ink", width: 0 },
    { shape: "line", x1: 0, y1: 0, x2: 1, y2: 1, color: "ink", width: 500 }, { shape: "text", x: 0, y: 0, text: "A", color: "ink", size: 0 }, { shape: "text", x: 0, y: 0, text: "A", color: "ink", size: 300 },
    { shape: "rect", x: 0, y: 0, w: 10, h: 10, color: "ink", radius: 2000 }, { shape: "polygon", points: [0, 0, 1, 1, 2], color: "ink", stroke: true }])).toEqual([
    { type: "rect", x: 6, y: 4, width: 4, height: 6, color: "default" }, { type: "line", x: 0, y: 0, x2: 1, y2: 1, stroke: 0.1, color: "default" },
    { type: "line", x: 0, y: 0, x2: 1, y2: 1, stroke: 128, color: "default" }, { type: "text", x: 0, y: 0, size: 1, text: "A", align: "center", baseline: "middle", color: "default" },
    { type: "text", x: 0, y: 0, size: 128, text: "A", align: "center", baseline: "middle", color: "default" }, { type: "roundRect", x: 0, y: 0, width: 10, height: 10, radius: 1024, color: "default" },
    { type: "polyline", points: [[0, 0], [1, 1], [0, 0]], stroke: 2, color: "default" }]);
  // A text longer than the characters of one reply keeps its first 2048, and a sum such as x - r has no float noise.
  expect(one({ shape: "text", x: 0, y: 0, text: "x".repeat(3000), color: "ink" })[0].text).toHaveLength(LIMITS.chars);
  expect(one({ shape: "circle", x: 0.3, y: 0.3, r: 0.1, color: "ink", stroke: true })[0]).toMatchObject({ x: 0.2, y: 0.2, width: 0.2, height: 0.2 });
});

test("draw: fit keeps the first commands of a reply inside each limit, for all surfaces together", () => {
  const dots = (count) => Array.from({ length: count }, (_, index) => ({ type: "circle", x: index % 100, y: 1, radius: 1, color: "default" }));
  const kept = (surfaces) => Object.values(fit(surfaces)).map((surface) => surface.commands.length);
  // 512 commands: the second surface keeps what the first one leaves, and a third one keeps nothing.
  expect(kept([["a", dots(300)], ["b", dots(300)], ["c", dots(5)]])).toEqual([300, 212, 0]);
  // 2048 pairs: the third polygon of 1000 points does not fit, and nothing after it is drawn.
  const polygon = { type: "polygon", points: Array.from({ length: 1000 }, (_, index) => [index % 100, 1]), color: "default" };
  expect(kept([["a", [polygon, polygon, polygon, ...dots(3)]]])).toEqual([2]);
  // 2048 text characters.
  const text = { type: "text", x: 0, y: 0, size: 14, text: "x".repeat(1000), align: "center", baseline: "middle", color: "default" };
  expect(kept([["a", [text, text]], ["b", [text]]])).toEqual([2, 0]);
  // 32768 bytes of the surfaces of the reply, as the host writes them: lines whose numbers cost bytes. One more command would pass the limit.
  const wide = Array.from({ length: 500 }, (_, index) => ({ type: "line", x: index + 0.25, y: index + 0.25, x2: index + 0.75, y2: index + 0.75, stroke: 1.25, color: "#80ff0000" }));
  const reply = fit([["a", wide], ["b", wide]]), count = reply.a.commands.length;
  expect([count < wide.length, reply.b.commands]).toEqual([true, []]);
  expect(hostBytes(reply)).toBeLessThanOrEqual(LIMITS.bytes);
  expect(hostBytes({ a: { commands: wide.slice(0, count + 1) }, b: { commands: [] } })).toBeGreaterThan(LIMITS.bytes);
  // A reply of exactly 32768 bytes keeps each command, as the emitter counts it: the first command of a surface has no comma before it.
  const lines = Array(440).fill({ type: "line", x: 1, y: 1, x2: 2, y2: 2, stroke: 1, color: "default" });
  const room = LIMITS.bytes - hostBytes({ a: { commands: [...lines, { ...text, text: "" }] } }), exact = [...lines, { ...text, text: "x".repeat(room) }];
  expect([room > 0 && room < LIMITS.chars, hostBytes({ a: { commands: exact } })]).toEqual([true, LIMITS.bytes]);
  expect(kept([["a", exact]])).toEqual([441]);
  // A reply inside each limit keeps each command.
  expect(fit([["a", dots(3)], ["b", []]])).toEqual({ a: { commands: dots(3) }, b: { commands: [] } });
});
