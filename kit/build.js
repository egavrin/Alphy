// Builds the sandbox bundle of the kit and the prompt asset of the app, and copies the saved plans for the device tests.
//   bun kit/build.js           writes kit/src/icons.js, kit/src/catalog.js, and kit/src/plans.js, app/src/main/assets/appfile/kit.js and prompt.json,
//                              app/src/main/assets/library/catalog.json with the images of the library, and the test assets of app/src/androidTest/assets
//   bun kit/build.js --check   writes nothing, and exits 1 when a file that the build writes differs or a copied plan has no source
//   bun kit/build.js --sheet DIR   also writes the review sheet of the library (kit/sheet.js) to DIR, in the light and the dark theme
// The sandbox evaluates plain script text, not modules, so the bundle is one script with no import and no export.
// The bundle also holds the run-time as text: the emitter puts it into the source of each app. Bun's minifier makes that
// text, so the bundle depends on the version of Bun (kit/README.md names the version of the checked-in bundle).

import { existsSync, mkdirSync, readFileSync, readdirSync, unlinkSync, writeFileSync } from "node:fs";
import { basename, dirname, join, posix } from "node:path";
import { fileURLToPath } from "node:url";
import { PROMPT, changeText, repairText, templateChangeText, templateRepairText, templateRestText } from "./prompt.js";
import { DATA_HEAD, TEMPLATE_HEAD } from "./src/ready.js";

/**
 * The modules of the bundle in dependency order, by their path from kit/src: each one imports only modules before it. The line tables of the
 * ready plans are in kit/templates.
 */
const MODULES = ["icons", "dsl", "kinds", "core", "words", "parts", "check", "rows", "draw", "drag", "run", "emit", "change", "form-base", "elements", "catalog", "library", "form-data", "plans",
  ...["scene", "items", "cells", "compare", "table", "chart", "nodata"].map((word) => `../templates/${word}`), "ready", "template", "compile"];
const at = (path) => fileURLToPath(new URL(path, import.meta.url));
/** The host's sources of its icon names (ToolUiCatalog.icons): the shell glyphs and the domain icons. */
const HOST_SOURCES = ["ToolUiCatalog", "ToolIcons"].map((name) => at(`../app/src/main/java/com/alpha/assistant/tools/${name}.kt`));

/**
 * The text of src/icons.js: each icon name that the host's validator takes, in the host's order: shellIcons of ToolUiCatalog.kt,
 * then the keys of paths of ToolIcons.kt. The build writes it, so the kit and the host cannot differ, and only parts.js imports it.
 */
export function iconModule(catalog = readFileSync(HOST_SOURCES[0], "utf8"), icons = readFileSync(HOST_SOURCES[1], "utf8")) {
  const shell = /\bshellIcons\s*=\s*setOf\(([^)]*)\)/.exec(catalog)?.[1], domain = [...icons.matchAll(/^\s*"(\w+)" to "/gm)].map((match) => match[1]);
  if (!shell || !domain.length) throw new Error("kit/build.js: no icon names in ToolUiCatalog.kt shellIcons or in ToolIcons.kt paths");
  const names = [...[...shell.matchAll(/"(\w+)"/g)].map((match) => match[1]), ...domain];
  return `// Built by kit/build.js from ToolUiCatalog.kt shellIcons and the keys of ToolIcons.kt paths. Do not edit: run bun kit/build.js.

/** Each icon name of Alphy (ToolUiCatalog.icons). Only the compile step reads it. */
export const HOST_ICONS = ${JSON.stringify(names)};
`;
}

/** The directory of the ready objects of the library (spec 12.6): a directory for each area, and one data file for each object, area/name.json. */
export const OBJECTS_DIR = at("library/objects");
/** The entries of the ready objects, by area and name. The id of each entry is area.name of its file, so the name of an object is its file name. */
export function libraryEntries(directory = OBJECTS_DIR) {
  return readdirSync(directory).sort().flatMap((area) => readdirSync(join(directory, area)).filter((file) => file.endsWith(".json")).sort().map((file) => {
    const entry = JSON.parse(readFileSync(join(directory, area, file), "utf8")), id = `${area}.${basename(file, ".json")}`;
    if (entry.id !== id) throw new Error(`kit/library/objects/${area}/${file}: its id is ${JSON.stringify(entry.id)}, and the file gives ${id}`);
    return entry;
  }));
}
/** The limits of the image of an image entry (stage 6, I2): a WebP file with alpha, its side in pixels, its bytes, and the bytes of all of them. */
export const IMAGE = { side: 768, bytes: 150 * 1024, total: 3 * 1024 * 1024 };
/** The asset path of the image of an image entry, as the host opens it (LibraryImages.kt). */
export const imageAsset = (id) => `library/${id.replace(".", "/")}.webp`;
/**
 * The fault of the bytes of the image of an entry, or null: a WebP file with alpha (an extended header with its alpha flag), IMAGE.side
 * pixels on each side, and at most IMAGE.bytes.
 */
export function imageFault(bytes) {
  const text = (from, to) => String.fromCharCode(...bytes.subarray(from, to)), side = (at) => 1 + (bytes[at] | (bytes[at + 1] << 8) | (bytes[at + 2] << 16));
  if (bytes.length < 30 || text(0, 4) !== "RIFF" || text(8, 12) !== "WEBP" || text(12, 16) !== "VP8X" || !(bytes[20] & 0x10)) return "it is not a WebP file with alpha";
  if (side(24) !== IMAGE.side || side(27) !== IMAGE.side) return `it is ${side(24)} x ${side(27)} pixels, and an image is ${IMAGE.side} x ${IMAGE.side}`;
  return bytes.length > IMAGE.bytes ? `it has ${bytes.length} bytes, and its cap is ${IMAGE.bytes}` : null;
}
/** The image of each image entry as [asset path, bytes]. An image past its limits, a missing image, and images past IMAGE.total stop the build. */
export function libraryImages(entries = libraryEntries(), directory = OBJECTS_DIR) {
  const out = entries.filter((entry) => entry.image).map((entry) => {
    const [area, name] = entry.id.split("."), file = join(directory, area, entry.image);
    if (entry.image !== `${name}.webp` || !existsSync(file)) throw new Error(`kit/library/objects/${area}: ${entry.id} needs its image ${name}.webp beside its entry`);
    const bytes = readFileSync(file), fault = imageFault(bytes);
    if (fault) throw new Error(`kit/library/objects/${area}/${entry.image}: ${fault}`);
    return [imageAsset(entry.id), bytes];
  });
  const total = out.reduce((sum, [, bytes]) => sum + bytes.length, 0);
  if (total > IMAGE.total) throw new Error(`the images of the library have ${total} bytes, and their cap is ${IMAGE.total}`);
  return out;
}
/** The text of src/catalog.js: what the kit draws of each ready object (library.js), by its name. The words of the search stay in the asset of the host. */
export function catalogModule(entries = libraryEntries()) {
  // An image entry has its image in place of layers, and a mark radius for each part (stage 6, I1).
  const objects = Object.fromEntries(entries.map(({ id, box, shadow, layers, image, parts }) => [id.split(".")[1],
    { id, box, ...(shadow ? { shadow } : {}), ...(image ? { image: true } : { layers }),
      parts: parts.map(({ id: part, names, label, ring }) => ({ id: part, names, label, ...(ring ? { ring } : {}) })) }]));
  return `// Built by kit/build.js from kit/library/objects. Do not edit: run bun kit/build.js.

/** The ready objects of the library by their name: the box, the layers, and the parts of each one (library.js). */
export const OBJECTS = ${JSON.stringify(objects)};
`;
}
/**
 * The catalog asset of the host (LibrarySearch.kt): for each ready object, its id, its name, its names and other words in Russian and English,
 * its one line, and its parts. The host searches it and gives the candidates to the template prompt (spec 12.2, 12.3). An image entry also
 * names its asset, so the host tells the kit each image that it cannot open (LibraryImages.kt).
 */
export const catalogAsset = (entries = libraryEntries()) => JSON.stringify({ objects: entries.map(({ id, names, words, line, image, parts }) => ({ id, name: id.split(".")[1], names, words, line,
  ...(image ? { image: imageAsset(id) } : {}), parts: parts.map(({ id: part, names: partNames }) => ({ id: part, names: partNames })) })) }, null, 1) + "\n";

/** Each image of the library in the app assets, and null for an image in the assets whose entry is gone: the build deletes it. */
function imageCopies() {
  const images = libraryImages(), root = at("../app/src/main/assets/library"), paths = images.map(([path]) => join(root, "..", path));
  const gone = existsSync(root) ? readdirSync(root, { withFileTypes: true }).filter((one) => one.isDirectory())
    .flatMap((area) => readdirSync(join(root, area.name)).filter((file) => file.endsWith(".webp")).map((file) => join(root, area.name, file))).filter((path) => !paths.includes(path)) : [];
  return [...images.map(([path, bytes]) => [join(root, "..", path), bytes]), ...gone.map((path) => [path, null])];
}

/** The text of src/plans.js: the ready plan of each template (kit/templates/WORD.plan) by its word. */
export function plansModule(directory = at("templates")) {
  const plans = Object.fromEntries(plansIn(directory).sort().map((path) => [basename(path, ".plan"), readFileSync(path, "utf8")]));
  return `// Built by kit/build.js from kit/templates. Do not edit: run bun kit/build.js.

/** The ready plan of each template by its word (kit/README.md, section "Ready plans"). */
export const PLANS = ${JSON.stringify(plans, null, 1)};
`;
}

/** A module of the kit as [name, text]. The icon names, the catalog, and the ready plans come from their sources, so the bundle never holds an old copy. */
const source = (name) => [name, name === "icons" ? iconModule() : name === "catalog" ? catalogModule() : name === "plans" ? plansModule() : readFileSync(at(`src/${name}.js`), "utf8")];
const scanner = new Bun.Transpiler({ loader: "js" });

/**
 * The modules [[name, text]] as one script. Each module keeps a scope of its own and shares only its exported names, so two
 * modules can use one name for their own helpers. An import names a module before it and names that it exports, and an
 * export line declares one function, class, or const, or exports again names that a module before it exports
 * (export { a, b } from "./x.js";). The build refuses each other form.
 */
export function script(modules) {
  const exported = new Map();
  return modules.map(([name, text]) => {
    const names = [], again = [], known = (list, from) => list.split(", ").every((one) => exported.get(from)?.has(one));
    // A module of kit/src and a line table of kit/templates import each other by a path such as "./dsl.js" or "../src/dsl.js".
    const body = text.replace(/^import (.*);$/gm, (line, what) => {
      const [, list, from] = /^\{ ([\w$, ]+) \} from "\.\.?\/(?:(?:src|templates)\/)?([\w-]+)\.js"$/.exec(what) ?? [];
      if (!list || !known(list, from)) throw new Error(`kit/src/${name}.js: the build cannot keep this import: ${line}`);
      return "";
    }).replace(/^export \{ ([\w$, ]+) \} from "\.\.?\/(?:(?:src|templates)\/)?([\w-]+)\.js";$/gm, (line, list, from) => {
      if (!known(list, from)) throw new Error(`kit/src/${name}.js: the build cannot keep this export: ${line}`);
      // The script declares each exported name once, so a name that a module exports again gets no second declaration.
      again.push(...list.split(", "));
      return "";
    }).replace(/^export ((?:async )?(?:function|class|const) ([\w$]+))/gm, (_, declaration, one) => { names.push(one); return declaration; });
    const left = /^(?:import|export)\b.*$/m.exec(body);
    if (left) throw new Error(`kit/src/${name}.js: the build cannot remove this line: ${left[0]}`);
    // export const a = 1, b = 2 would share only a.
    const all = scanner.scan(text).exports, shared = [...names, ...again];
    if (all.length !== shared.length) throw new Error(`kit/src/${name}.js: an export line declares one name: ${all.filter((one) => !shared.includes(one)).join(", ")}`);
    exported.set(basename(name), new Set(shared));
    return `// ${name}.js\nconst { ${names.join(", ")} } = (() => {\n${body}\nreturn { ${names.join(", ")} };\n})();\n`;
  }).join("");
}

/** The line of each module that keeps its messages (dsl.js MESSAGES). */
const MESSAGES = /^const MESSAGES = true;$/m;

/** The text of a module for the run-time: without its MESSAGES line. A module that names MESSAGES with no such line is an error of the build. */
export function withoutMessages(name, text) {
  if (/\bMESSAGES\b/.test(text) && !MESSAGES.test(text)) throw new Error(`kit/src/${name}.js: the build takes out only the line const MESSAGES = true;`);
  return text.replace(MESSAGES, "");
}

/**
 * The text of the run-time that the emitter puts into the source of each app, before `return handle(input, <data>);`.
 * The source is a function body, so handle and __handle are locals of the app's function and the global object gets no name.
 * Each module comes without its MESSAGES line, and define makes the name false, so the minifier leaves out each message that only
 * the compile step reads. Bun 1.3.9 folds a constant of a module with an import into no other line, so a false const is not enough.
 * Each module goes to Bun.build at a fixed path, never at its path in the checkout: Bun 1.3.9 gives other short names for other
 * paths (K1 fix 1: a checkout at another path wrote another bundle), so the text depends on the sources only.
 */
export async function runtimeText() {
  const entry = "/kit-runtime-entry.js";
  const modules = Object.fromEntries(MODULES.map((name) => [posix.join("/kit/src", `${name}.js`), withoutMessages(name, source(name)[1])]));
  const built = await Bun.build({ entrypoints: [entry], files: { [entry]: 'import { handle } from "/kit/src/run.js";\n__handle(handle);\n', ...modules },
    minify: true, format: "iife", target: "browser", define: { MESSAGES: "false" } });
  if (!built.success) throw new AggregateError(built.logs, "kit/build.js: Bun.build of the run-time failed");
  return `let handle;const __handle=(f)=>{handle=f};\n${(await built.outputs[0].text()).trim()}`;
}

/**
 * The bundle defines globalThis.compileStep (one step of the host: the read of a reply, the merge, and the compile), globalThis.previewStep
 * (compile.js: the frame of the lines so far of a stream, for the preview of the stage), and no other global.
 * The script of the host (AppFileCompiler.kt) calls only it.
 */
export async function bundle() {
  return `// Built by kit/build.js from kit/src with Bun ${Bun.version}. Do not edit: run bun kit/build.js.\n(() => {\n"use strict";\n${script(MODULES.map(source))}runtime.text = ${JSON.stringify(await runtimeText())};\nglobalThis.compileStep = compileStep;\nglobalThis.previewStep = previewStep;\n})();\n`;
}

/**
 * The prompt asset of the app (prompt.json): the four sections of kit/prompt.md, and the task texts of a repair call and a change call
 * with the placeholders {{task}}, {{plan}}, {{faults}}, and {{request}}. The host puts the plan with no newline at its end, and the faults
 * joined by "\n- ", so a filled template is the text of repairText and changeText (prompt.js). No prompt text is typed in Kotlin.
 * The template prompt adds its section (template), the task texts of its repair and change calls with the same placeholders
 * (templateRepairTask, templateChangeTask), and templateHead, the source of the pattern that tells a template file from an app file (src/ready.js).
 * templateRestTask is the task text of the call for the missing lines of a template call that stopped early, with {{task}} and {{plan}}.
 * templateData is the section of the template call of the data family (block 2 plan, D1): it shares the repair, change, and rest task texts.
 * dataHead is the source of the pattern of the first line of a data form (src/ready.js DATA_HEAD).
 */
export const promptAsset = () => ({ system: PROMPT.System, repair: PROMPT.Repair, dialogue: PROMPT.Dialogue, change: PROMPT.Change,
  repairTask: repairText({ task: "{{task}}", plan: "{{plan}}", faults: ["{{faults}}"] }), changeTask: changeText({ request: "{{request}}", plan: "{{plan}}" }),
  template: PROMPT.Template, templateLibrary: PROMPT["Template library"], templateRepairTask: templateRepairText({ task: "{{task}}", plan: "{{plan}}", faults: ["{{faults}}"] }),
  templateChangeTask: templateChangeText({ request: "{{request}}", plan: "{{plan}}" }), templateRestTask: templateRestText({ task: "{{task}}", plan: "{{plan}}" }),
  templateHead: TEMPLATE_HEAD.source, templateData: PROMPT["Template data"], dataHead: DATA_HEAD.source });

/** The plan files of a directory. */
export const plansIn = (directory) => readdirSync(directory).filter((name) => name.endsWith(".plan")).map((name) => join(directory, name));

/** The copy of each plan file in to, and null as the text of a copy in to whose plan is gone: the build deletes it. */
export function planCopies(plans, to) {
  const names = plans.map((path) => basename(path));
  const gone = existsSync(to) ? readdirSync(to).filter((name) => name.endsWith(".plan") && !names.includes(name)) : [];
  return [...plans.map((path) => [join(to, basename(path)), readFileSync(path, "utf8")]), ...gone.map((name) => [join(to, name), null])];
}

/** The fixtures that share a name with a case of the corpus: the pictures of AppFileLookTest and kit/look.js show them beside the saved plans. */
export const LOOK_FIXTURES = ["water-ko", "units-pl", "shopping", "habits", "price-filter", "look-water-ko", "look-shopping-it", "mock-water-ko", "mock-units-pl", "mock-shopping-it", "mock-habits-uk", "mock-price-filter", "look-deck-es", "look-guide-es"].map((name) => at(`test/fixtures/${name}.plan`));

/**
 * Each file that the build writes, with its text. The device tests also read the test plans of the node table, the lists, the render work limit, the cards, the free forms, the shapes, the reply at the byte limit, and a model beside a drawing, and the look fixtures.
 * The plans that a model wrote (test/fixtures/model) go to a directory of their own, appfile/model, with their index: the device test reads the request of each plan.
 * The template files (test/fixtures/template: the examples and the cap files) go to appfile/template.
 */
export const outputs = async () => [[at("src/icons.js"), iconModule()], [at("src/catalog.js"), catalogModule()], [at("src/plans.js"), plansModule()], [at("../app/src/main/assets/appfile/kit.js"), await bundle()],
  [at("../app/src/main/assets/library/catalog.json"), catalogAsset()], ...imageCopies(),
  ...["names", "search"].map((name) => [at(`../app/src/androidTest/assets/library/${name}.json`), readFileSync(at(`test/fixtures/library-${name}.json`), "utf8")]),
  [at("../app/src/main/assets/appfile/prompt.json"), JSON.stringify(promptAsset(), null, 2) + "\n"],
  ...planCopies([...plansIn(at("plans")), ...["nodes", "looks", "lists", "rich", "cards", "free", "shapes", "bytes", "model"].map((name) => at(`test/${name}.plan`)), ...LOOK_FIXTURES],
    at("../app/src/androidTest/assets/appfile")),
  ...planCopies(plansIn(at("test/fixtures/model")), at("../app/src/androidTest/assets/appfile/model")),
  ...planCopies(plansIn(at("test/fixtures/template")), at("../app/src/androidTest/assets/appfile/template")),
  [at("../app/src/androidTest/assets/appfile/model/index.json"), readFileSync(at("test/fixtures/model/index.json"), "utf8")]];

if (import.meta.main) {
  const check = process.argv.includes("--check");
  let differs = 0;
  for (const [path, text] of await outputs()) {
    // An image of the library is bytes, each other output is text.
    if (text === null ? !existsSync(path) : existsSync(path) && (Buffer.isBuffer(text) ? readFileSync(path).equals(text) : readFileSync(path, "utf8") === text)) continue;
    if (check) { console.error(`${text === null ? "no source" : "differs"}: ${path}`); differs++; continue; }
    if (text === null) { unlinkSync(path); console.log(`deleted ${path}`); continue; }
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, text);
    console.log(`wrote ${path}`);
  }
  const sheet = process.argv.indexOf("--sheet");
  if (sheet > 0) {
    const { sheetPages } = await import("./sheet.js");
    for (const [name, text] of await sheetPages()) { mkdirSync(process.argv[sheet + 1], { recursive: true }); writeFileSync(join(process.argv[sheet + 1], name), text); console.log(`wrote ${join(process.argv[sheet + 1], name)}`); }
  }
  // The minified run-time depends on the version of Bun: a different version can be the only difference.
  if (differs) { console.error(`Run bun kit/build.js to write them. This check ran with Bun ${Bun.version}.`); process.exit(1); }
}
