// draw.js: the kit's shapes as Alphy's drawing commands (GraphicsContract.frame), the colors, and the limits of one reply.

import { expect, test } from "bun:test";
import { ACCENTS, LIMITS, accentOf, budgetOf, commandsOf, fit, polygonsOf } from "../src/draw.js";

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
  // A text in the surface color on no shape is in the default ink (L1 item 6).
  expect(one({ shape: "text", x: 160, y: 160, text: "Земля", color: "surface", size: 12 }))
    .toEqual([{ type: "text", x: 160, y: 160, size: 12, text: "Земля", align: "center", baseline: "middle", color: "default" }]);
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
  // surface is the surface role of the theme (L1 item 6).
  const colors = ["ink", "muted", "accent", "success", "warning", "surface", "water", "land", "sun", "sand", "wood", "#336699", "#80FF0000", "purple", "#fff", "error", "toString"];
  expect(commandsOf(colors.map((color) => ({ shape: "circle", x: 1, y: 1, r: 1, color }))).map((command) => command.color)).toEqual([
    "default", "muted", "accent", "success", "warning", "surface", "#38bdf8", "#65a30d", "#facc15", "#e7d3a8", "#a16207", "#336699", "#80FF0000", "default", "default", "default", "default"]);
});

/** The WCAG contrast of two hex colors. */
const contrast = (...hexes) => {
  const [high, low] = hexes.map((hex) => [1, 3, 5].reduce((sum, at, index) => {
    const c = parseInt(hex.slice(at, at + 2), 16) / 255;
    return sum + [0.2126, 0.7152, 0.0722][index] * (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4);
  }, 0)).sort((a, b) => b - a);
  return (high + 0.05) / (low + 0.05);
};
const textOn = (...shapes) => commandsOf([...shapes, { shape: "text", x: 50, y: 50, text: "A", color: "ink" }]).at(-1).color;

test("draw (L1 item 6): a text on a shape in an ink role is in the surface role, on muted in the default ink, and on surface or on no shape it keeps its role", () => {
  const disk = (color, more = {}) => ({ shape: "circle", x: 50, y: 50, r: 20, color, ...more }), box = (color, x = 40) => ({ shape: "rect", x, y: 40, w: 20, h: 20, color, radius: 4 });
  expect(["ink", "accent", "success", "warning"].flatMap((color) => [textOn(disk(color)), textOn(box(color))])).toEqual(Array(8).fill("surface"));
  expect([textOn(disk("muted")), textOn(box("surface")), textOn(), textOn(disk("accent", { stroke: true })), textOn(box("accent", 60))]).toEqual(Array(5).fill("default"));
  // A muted shape is a light tint, so each ink but the default one can fall under 4.5 on it (NativePaletteWorkflowTest).
  expect(["muted", "accent", "success", "warning", "error"].map((color) => commandsOf([disk("muted"), { shape: "text", x: 50, y: 50, text: "A", color }]).at(-1).color))
    .toEqual(Array(5).fill("default"));
  expect(commandsOf([box("muted"), { shape: "text", x: 50, y: 50, text: "A", color: "accent" }]).at(-1).color).toBe("default");
  // The last shape under the text counts. A text in the surface role on a surface is in the default ink, and a text in the accent role stays.
  expect(textOn(box("accent"), disk("surface"))).toBe("default");
  expect(commandsOf([box("surface"), { shape: "text", x: 50, y: 50, text: "A", color: "surface" }, { shape: "text", x: 50, y: 50, text: "B", color: "accent" }])
    .slice(1).map((command) => command.color)).toEqual(["default", "accent"]);
  // A filled polygon in an ink role covers a text too, and its outline covers none.
  expect([textOn({ shape: "polygon", points: [0, 0, 100, 0, 50, 100], color: "accent" }), textOn({ shape: "polygon", points: [0, 0, 100, 0, 50, 100], color: "accent", stroke: true })])
    .toEqual(["surface", "default"]);
});

test("draw: a label on a polygon takes the contrast of the polygon fill", () => {
  // A V of #1e3a8a: its notch at (50, 10) is inside the box of its points, but not inside the polygon.
  const dark = { shape: "polygon", points: [0, 0, 30, 0, 50, 30, 70, 0, 100, 0, 50, 90], color: "#1e3a8a" }, page = { shape: "rect", x: 0, y: 0, w: 200, h: 200, color: "#f8fafc" };
  const label = (x, y, ...shapes) => commandsOf([...shapes, { shape: "text", x, y, text: "c²", color: "ink" }]).at(-1).color;
  // On the canvas: the light ink inside the polygon, and the default ink in the notch and outside the polygon.
  expect([label(50, 60, dark), label(50, 10, dark), label(150, 150, dark)]).toEqual(["#ffffff", "default", "default"]);
  // On a light page: the light ink inside the polygon, and the dark ink outside it. Each has a contrast of 4.5 or more against the fill under it.
  const [inside, outside] = [label(50, 60, page, dark), label(50, 10, page, dark)];
  expect([inside, outside, contrast("#1e3a8a", inside) >= 4.5, contrast("#f8fafc", outside) >= 4.5]).toEqual(["#ffffff", "#000000", true, true]);
  // The topmost fill under the anchor decides: a box over the polygon, and the polygon over a box.
  expect([label(50, 60, dark, { shape: "rect", x: 40, y: 50, w: 20, h: 20, color: "sun" }), label(50, 60, { shape: "circle", x: 50, y: 60, r: 9, color: "accent" }, dark)]).toEqual(["#000000", "#ffffff"]);
  // A star whose edges cross: Alphy fills a Path by the non-zero rule (SurfaceDrawing.kt), so its center is inside it.
  expect(label(50, 50, { shape: "polygon", points: [50, 0, 79, 90, 2, 35, 98, 35, 21, 90], color: "#1e3a8a" })).toBe("#ffffff");
});

test("draw (L1 item 6): a text on a shape in a hex color is black or white, with a contrast of 4.5 or more against the shape", () => {
  expect([textOn({ shape: "circle", x: 50, y: 50, r: 30, color: "sun" }), textOn({ shape: "circle", x: 50, y: 50, r: 30, color: "#0056d0" }), textOn({ shape: "rect", x: 0, y: 0, w: 99, h: 99, color: "water" })])
    .toEqual(["#000000", "#ffffff", "#000000"]);
  const low = [];
  for (let red = 0; red < 256; red += 15) for (let green = 0; green < 256; green += 5) for (let blue = 0; blue < 256; blue += 15) {
    const fill = `#${[red, green, blue].map((value) => value.toString(16).padStart(2, "0")).join("")}`, text = textOn({ shape: "circle", x: 50, y: 50, r: 30, color: fill });
    if (contrast(fill, text) < 4.5) low.push(`${text} on ${fill}`);
  }
  expect(low).toEqual([]);
});

test("draw (L1 item 6): in d-heart, e-atom-ru, and e-seasons-ru, each text on a shape reads in both themes", async () => {
  const { readFileSync } = await import("node:fs");
  const { emit } = await import("../src/emit.js");
  const { handle } = await import("../src/run.js");
  const { planned } = await import("./helpers.js");
  const texts = {};
  for (const name of ["d-heart", "e-atom-ru", "e-seasons-ru"]) {
    const { definition, app } = emit(await planned(readFileSync(new URL(`./fixtures/model/${name}.plan`, import.meta.url), "utf8")));
    const { surfaces } = handle({ artifactId: "t", revision: 1, state: definition.initialState, event: { type: "open" } }, app);
    texts[name] = Object.values(surfaces).flatMap(({ commands }) => commands.filter((command) => command.type === "text").map((command) => `${command.text}: ${command.color}`));
  }
  // "Сердце" stands on the accent box, "Лёгкие" on the light blue box, and "Тело" on the surface box. The particles of the nucleus are accent disks,
  // and the Sun is a yellow disk. The letter С of the Earth stands beside it.
  expect(texts).toEqual({ "d-heart": ["Сердце: surface", "Лёгкие: #000000", "Тело: default"], "e-atom-ru": ["+: surface", "+: surface", "н: surface", "н: surface"],
    "e-seasons-ru": ["Солнце: #000000", "С: default"] });
});

test("draw: budgetOf counts commands, pairs, and characters", () => {
  const commands = [{ type: "circle", x: 1, y: 1, radius: 1, color: "default" }, { type: "rect", x: 0, y: 0, width: 2, height: 2, color: "muted" },
    { type: "line", x: 0, y: 0, x2: 9, y2: 9, stroke: 2, color: "accent" }, { type: "text", x: 5, y: 5, size: 14, text: "1/2 Земля", align: "center", baseline: "middle", color: "#ffffff" },
    { type: "polygon", points: [[0, 0], [10, 0], [5, 8]], color: "success" }, { type: "polyline", points: [[0, 0], [1, 0], [1, 1], [0, 1], [0, 0]], stroke: 2, color: "warning" }];
  // A box, a circle, and a text count one pair, a line two, and a polygon or a polyline one for each point (GraphicsContract.frame).
  // The bytes are those of the whole reply, {id: {commands}, ...}, for all its surfaces together.
  expect(budgetOf([["a", commands]])).toEqual({ commands: 6, pairs: 13, chars: 9, bytes: hostBytes({ a: { commands } }) });
  expect(budgetOf([["a", commands.slice(0, 2)], ["b", commands.slice(2)]])).toEqual({ commands: 6, pairs: 13, chars: 9, bytes: hostBytes({ a: { commands: commands.slice(0, 2) }, b: { commands: commands.slice(2) } }) });
  expect([budgetOf([]), budgetOf([["a", []]])]).toEqual([{ commands: 0, pairs: 0, chars: 0, bytes: 2 }, { commands: 0, pairs: 0, chars: 0, bytes: 21 }]);
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

// The bodies of a model as the kit's App gives them (App.drawn, tag "body").
const box = (part, x, y, z, w, h, d, color = "ink") => ({ body: "box", part, x, y, z, w, h, d, color });
const cylinder = (part, x, y, z, radius, length, axis, color = "ink") => ({ body: "cylinder", part, x, y, z, radius, length, axis, color });
/** The center of a polygon on the surface. */
const centerOf = ({ points }) => [0, 1].map((index) => points.reduce((sum, point) => sum + point[index], 0) / points.length);

test("draw: a box at the origin gives three polygons at turn 30", () => {
  // At turn 30 the person sees the top, the front, and the left side of the box. At turn 0 the sides are edge-on, and only the top and the front show.
  const faces = polygonsOf([box("", 0, 0, 0, 2, 2, 2)], 30, false, "", 320, 240);
  expect(faces.map((face) => [face.type, face.points.length])).toEqual([["polygon", 4], ["polygon", 4], ["polygon", 4]]);
  expect(faces.every((face) => face.points.every(([x, y]) => x >= 0 && x <= 320 && y >= 0 && y <= 240))).toBe(true);
  expect(polygonsOf([box("", 0, 0, 0, 2, 2, 2)], 0, false, "", 320, 240)).toHaveLength(2);
  // A cylinder is a prism of 12 sides. At turn 30 the person sees its near cap and 5 sides: with no perspective the side whose normal
  // is at 225 degrees would look at the person too, but the eye at 5 times the reach of the model sees it from behind (n · eye 0.34 < n · p 0.97).
  const wheel = polygonsOf([cylinder("", 0, 0, 0, 1, 0.5, "z")], 30, false, "", 320, 240);
  expect(wheel.map((face) => face.points.length).sort((a, b) => b - a)).toEqual([12, 4, 4, 4, 4, 4]);
});

test("draw: a far face is before a near face", () => {
  // A red box behind a blue box: the reply draws each face of the red box first, so the blue box covers it.
  const faces = polygonsOf([box("", 0, 0, 3, 1, 1, 1, "#0000ff"), box("", 0, 0, -3, 1, 1, 1, "#ff0000")], 30, false, "", 320, 240);
  expect(faces.map((face) => (face.color.endsWith("0000") ? "red" : "blue"))).toEqual(["red", "red", "red", "blue", "blue", "blue"]);
});

test("draw: explode moves a body of a named part away from the center by half of its distance", () => {
  // The center of the three bodies is (0, 0, 0): top goes from y 2 to 3, legs from -2 to -3, and the body with no part stays.
  const bodies = [box("", 0, 0, 0, 2, 1, 1), box("top", 0, 2, 0, 1, 1, 1), cylinder("legs", 0, -2, 0, 0.5, 1, "y")];
  const apart = [bodies[0], { ...bodies[1], y: 3 }, { ...bodies[2], y: -3 }];
  expect(polygonsOf(bodies, 30, true, "", 320, 240)).toEqual(polygonsOf(apart, 30, false, "", 320, 240));
  expect(polygonsOf(bodies, 30, true, "", 320, 240)).not.toEqual(polygonsOf(bodies, 30, false, "", 320, 240));
  // The bodies of the caller stay as they are.
  expect(bodies.map((body) => body.y)).toEqual([0, 2, -2]);
});

test("draw: a selected part keeps its color and the others are dimmed", () => {
  // Two boxes of one color at turn 0: a on the left and b on the right. With a selected, each face of b is at 40 % of its alpha.
  const bodies = [box("a", -2, 0, 0, 1, 1, 1, "#336699"), box("b", 2, 0, 0, 1, 1, 1, "#336699")];
  const plain = polygonsOf(bodies, 0, false, "", 320, 240), chosen = polygonsOf(bodies, 0, false, "a", 320, 240);
  expect(chosen.map((face) => face.points)).toEqual(plain.map((face) => face.points));
  expect(chosen.map((face) => [centerOf(face)[0] < 160 ? "a" : "b", face.color])).toEqual(plain.map((face) => centerOf(face)[0] < 160 ? ["a", face.color] : ["b", "#66" + face.color.slice(1)]));
  // A face is shaded by the light from 70 % to 100 % of its color, and a color with its own alpha keeps it.
  expect(plain.map((face) => face.color).every((color) => /^#[0-9a-f]{6}$/.test(color) && color !== "#336699")).toBe(true);
  expect(polygonsOf([box("a", 0, 0, 0, 1, 1, 1, "#80ff0000")], 30, false, "b", 320, 240).map((face) => face.color.slice(0, 3))).toEqual(["#33", "#33", "#33"]);
});

test("draw: the color of a body is a color of the kit, the accent of the palette, or a hex, and each other text is ink", () => {
  const top = (color, accent) => polygonsOf([box("", 0, 0, 0, 1, 1, 1, color)], 0, false, "", 320, 240, accent).map((face) => face.color)[1];
  // The top face of a box at turn 0: the same light for each color.
  const lit = (hex) => top(hex);
  expect([top("sand"), top("accent", ACCENTS.teal), top("accent"), top("#006B5F"), top("nothing"), top("toString")])
    .toEqual([lit("#e7d3a8"), lit("#006b5f"), lit(ACCENTS.graphite), lit("#006b5f"), lit("#0f172a"), lit("#0f172a")]);
  // The accent is the palette of the app, else the palette of its preset as Alphy picks it, and graphite for a palette that Alphy does not have.
  expect([accentOf("teal", "playful"), accentOf(undefined, "clean"), accentOf("", "soft"), accentOf(undefined, "playful"), accentOf("nothing", "clean"), accentOf("toString", "clean")])
    .toEqual([ACCENTS.teal, ACCENTS.blue, ACCENTS.teal, ACCENTS.orange, ACCENTS.graphite, ACCENTS.graphite]);
});

test("draw: a model never throws: no bodies, a value that is not finite, a size below 0, and a turn that is not finite", () => {
  expect(polygonsOf([], 30, false, "", 320, 240)).toEqual([]);
  expect(polygonsOf([box("", Number.NaN, 0, 0, 1, 1, 1)], 30, false, "", 320, 240)).toEqual([]);
  expect(polygonsOf([box("", 0, 0, 0, -2, -2, -2)], 30, false, "", 320, 240)).toEqual(polygonsOf([box("", 0, 0, 0, 2, 2, 2)], 30, false, "", 320, 240));
  expect(polygonsOf([cylinder("", 0, 0, 0, -1, -0.5, "z")], 30, false, "", 320, 240)).toEqual(polygonsOf([cylinder("", 0, 0, 0, 1, 0.5, "z")], 30, false, "", 320, 240));
  expect(polygonsOf([box("", 0, 0, 0, 2, 2, 2)], Number.NaN, false, "", 320, 240)).toEqual(polygonsOf([box("", 0, 0, 0, 2, 2, 2)], 0, false, "", 320, 240));
  // A body of no size draws nothing that Alphy refuses.
  expect(polygonsOf([box("", 0, 0, 0, 0, 0, 0)], 30, false, "", 320, 240).every((face) => face.points.flat().every(Number.isFinite))).toBe(true);
});
