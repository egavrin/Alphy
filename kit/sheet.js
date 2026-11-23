// The review sheet of the library (stage 6, L7; spec 12.8): each ready object with its named parts and at the smallest size of a scene, and
// each generic element, in the light and in the dark theme. The kit compiles each cell as a template scene, as the app does, and this file
// draws the commands of its first frame as SVG with the roles of the theme (SurfaceDrawing.kt draws the same commands on the phone).
//   bun kit/build.js --sheet DIR   writes library-objects-light.html, library-objects-dark.html, library-elements-light.html, and library-elements-dark.html

import { compileAppFile } from "./src/compile.js";
import { runtime } from "./src/emit.js";
import { ELEMENTS } from "./src/elements.js";
import { OBJECTS } from "./src/catalog.js";
import { SMALLEST } from "./src/library.js";

/**
 * The colors of the drawing roles of the blue palette (ToolPalettes.kt "blue", AlphaTheme.kt, NativeToolUi.drawingRoles): the ink of each
 * tone on the stage background, and surface as the lowest container. The phone gives the same roles for this palette.
 */
export const THEMES = {
  light: { background: "#FAF8FF", default: "#181B25", muted: "#424654", accent: "#0056D0", success: "#1B6D24", warning: "#835400", error: "#BA1A1A", surface: "#FFFFFF" },
  dark: { background: "#10131C", default: "#E0E2EF", muted: "#C3C6D6", accent: "#B1C5FF", success: "#88D982", warning: "#FFB957", error: "#FFB4AB", surface: "#0B0E17" },
};
const TONE = /^(default|muted|accent|success|warning|error|surface)([+-][123])?(?:@(\d{1,3}))?$/;

/** A color of a command as [rgb hex, alpha 0 to 1], as SurfaceDrawing.drawingColor gives it. */
function colorOf(value, roles) {
  const [, role, step = "0", alpha = "100"] = TONE.exec(value) ?? [];
  if (role) {
    const base = roles[role], up = Number(step) > 0, share = up ? 0.3 * Number(step) : -0.25 * Number(step);
    const rgb = [1, 3, 5].map((at) => Math.round(parseInt(base.slice(at, at + 2), 16) + ((up ? 255 : 0) - parseInt(base.slice(at, at + 2), 16)) * share));
    return [`rgb(${rgb.join(",")})`, Number(alpha) / 100];
  }
  if (/^#[0-9a-fA-F]{8}$/.test(value)) return [`#${value.slice(3)}`, parseInt(value.slice(1, 3), 16) / 255];
  return [value, 1];
}

/** The SVG of the commands of one surface. */
let svgs = 0;
export function svgOf(commands, roles, width, height) {
  const defs = [];
  const paint = (command, kind) => {
    if (command.gradient) {
      const g = command.gradient, id = `g${svgs}-${defs.length}`, stops = g.stops.map((stop) => { const [color, alpha] = colorOf(stop.color, roles); return `<stop offset="${stop.offset}" stop-color="${color}" stop-opacity="${alpha}"/>`; }).join("");
      defs.push(g.type === "linear" ? `<linearGradient id="${id}" gradientUnits="userSpaceOnUse" x1="${g.x}" y1="${g.y}" x2="${g.x2}" y2="${g.y2}">${stops}</linearGradient>`
        : `<radialGradient id="${id}" gradientUnits="userSpaceOnUse" cx="${g.x}" cy="${g.y}" r="${g.radius}">${stops}</radialGradient>`);
      return kind === "stroke" ? `fill="none" stroke="url(#${id})"` : `fill="url(#${id})"`;
    }
    let [color, alpha] = colorOf(command.color, roles);
    // A muted shape is a light tint on the phone (MUTED_SHAPE_ALPHA).
    if (command.color === "muted" && command.type !== "text") alpha = 56 / 255;
    return kind === "stroke" ? `fill="none" stroke="${color}" stroke-opacity="${alpha}" stroke-width="${command.stroke}"` : `fill="${color}" fill-opacity="${alpha}"`;
  };
  const one = (c) => {
    switch (c.type) {
      case "group": return `<g transform="matrix(${c.transform.join(" ")})">${c.children.map(one).join("")}</g>`;
      case "rect": return `<rect x="${c.x}" y="${c.y}" width="${c.width}" height="${c.height}" ${paint(c)}/>`;
      case "roundRect": return `<rect x="${c.x}" y="${c.y}" width="${c.width}" height="${c.height}" rx="${c.radius}" ${paint(c)}/>`;
      case "circle": return `<circle cx="${c.x}" cy="${c.y}" r="${c.radius}" ${paint(c)}/>`;
      case "line": return `<line x1="${c.x}" y1="${c.y}" x2="${c.x2}" y2="${c.y2}" ${paint(c, "stroke")}/>`;
      case "arc": {
        const [rx, ry, cx, cy] = [c.width / 2, c.height / 2, c.x + c.width / 2, c.y + c.height / 2], at = (deg) => [cx + rx * Math.cos((deg * Math.PI) / 180), cy + ry * Math.sin((deg * Math.PI) / 180)];
        if (Math.abs(c.sweep) >= 360) return `<ellipse cx="${cx}" cy="${cy}" rx="${rx}" ry="${ry}" ${paint(c, "stroke")}/>`;
        const [a, b] = [at(c.start), at(c.start + c.sweep)];
        return `<path d="M${a[0]} ${a[1]} A${rx} ${ry} 0 ${Math.abs(c.sweep) > 180 ? 1 : 0} ${c.sweep > 0 ? 1 : 0} ${b[0]} ${b[1]}" ${paint(c, "stroke")}/>`;
      }
      // The picture of an image entry, from the assets of the app, as the phone draws it (LibraryImages.kt).
      case "image": return `<image href="${new URL(`../app/src/main/assets/library/${c.id.replace(".", "/")}.webp`, import.meta.url).href}" x="${c.x}" y="${c.y}" width="${c.width}" height="${c.height}" preserveAspectRatio="none"/>`;
      case "polyline": return `<polyline points="${c.points.flat().join(" ")}" ${paint(c, "stroke")}/>`;
      case "polygon": return `<polygon points="${c.points.flat().join(" ")}" ${paint(c)}/>`;
      case "path": {
        const d = c.segments.map((s) => (s.type === "move" ? `M${s.x} ${s.y}` : s.type === "line" ? `L${s.x} ${s.y}` : s.type === "quad" ? `Q${s.cx} ${s.cy} ${s.x} ${s.y}`
          : s.type === "cubic" ? `C${s.cx1} ${s.cy1} ${s.cx2} ${s.cy2} ${s.x} ${s.y}` : "Z")).join(" ");
        return `<path d="${d}" ${c.stroke !== undefined ? paint(c, "stroke") + ' stroke-linecap="round" stroke-linejoin="round"' : paint(c)}/>`;
      }
      case "text": {
        const anchor = c.align === "start" ? "start" : c.align === "end" ? "end" : "middle", text = c.text.replace(/&/g, "&amp;").replace(/</g, "&lt;");
        return `<text x="${c.x}" y="${c.y}" font-size="${c.size}" text-anchor="${anchor}" dominant-baseline="central" font-family="Roboto, Helvetica, Arial, sans-serif" ${paint(c)}>${text}</text>`;
      }
      default: return "";
    }
  };
  svgs++;
  const body = commands.map(one).join("");
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${width} ${height}" width="${width}" height="${height}"><rect width="${width}" height="${height}" fill="${roles.background}"/><defs>${defs.join("")}</defs>${body}</svg>`;
}

/** The commands of the first frame of a template scene with these part lines. */
export async function sceneCommands(lines, lang = "ru") {
  const result = await compileAppFile(`scene("Sheet", lang="${lang}")\n${lines.map((line, index) => `s${index + 1} = ${line}`).join("\n")}\n`, null);
  if (!result.definition) throw new Error(`kit/sheet.js: ${result.faults.join("; ")}`);
  return Object.values(result.frame.drawing)[0].commands;
}

/**
 * The cells of the sheet: [title, part lines] of each object with its parts, with its parts and its first part marked, and at the smallest
 * size, and of each element.
 */
export const objectCells = () => Object.entries(OBJECTS).flatMap(([name, entry]) => [
  [`${entry.id}: parts`, [`use("${name}", 150, 150, 300, parts=true)`]],
  // An entry with no part (the star) has no mark cell.
  ...(entry.parts.length ? [[`${entry.id}: mark ${entry.parts[0].id}`, [`use("${name}", 150, 150, 300, parts=true, mark="${entry.parts[0].id}")`]]] : []),
  [`${entry.id}: size ${SMALLEST}, no label`, [`use("${name}", 150, 150, ${SMALLEST})`]],
]);
export const elementCells = () => Object.entries(ELEMENTS).map(([name, entry]) => [`${entry.group}: ${name}`,
  [`use("${name}", 150, 140, 200${entry.text ? `, label="${name === "formula" ? "a^2 + b^2 = c^2" : "Привет!"}"` : ""})`]]);

/** The pages of the sheet: [file name, html] for the objects and the elements, each in the light and the dark theme. */
export async function sheetPages() {
  if (!runtime.text) runtime.text = await (await import("./build.js")).runtimeText();
  const pages = [];
  for (const [kind, cells] of [["objects", objectCells()], ["elements", elementCells()]]) {
    const drawn = await Promise.all(cells.map(async ([title, lines]) => [title, await sceneCommands(lines)]));
    for (const [theme, roles] of Object.entries(THEMES)) {
      const cards = drawn.map(([title, commands]) => `<figure>${svgOf(commands, roles, 300, 300)}<figcaption>${title}</figcaption></figure>`).join("\n");
      pages.push([`library-${kind}-${theme}.html`, `<!doctype html><meta charset="utf-8"><title>Library ${kind}, ${theme}</title>
<style>body{margin:16px;background:${roles.background};color:${roles.default};font:13px Roboto,Helvetica,Arial,sans-serif}main{display:flex;flex-wrap:wrap;gap:16px}figure{margin:0}figcaption{margin-top:4px;opacity:.7}svg{display:block;border:1px solid ${roles.muted}40;border-radius:8px}</style>
<main>
${cards}
</main>
`]);
    }
  }
  return pages;
}
