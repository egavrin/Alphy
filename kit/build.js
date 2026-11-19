// Builds the sandbox bundle of the kit and the prompt asset of the app, and copies the saved plans for the device tests.
//   bun kit/build.js           writes kit/src/icons.js, app/src/main/assets/appfile/kit.js and prompt.json, and app/src/androidTest/assets/appfile/*.plan
//   bun kit/build.js --check   writes nothing, and exits 1 when a file that the build writes differs or a copied plan has no source
// The sandbox evaluates plain script text, not modules, so the bundle is one script with no import and no export.
// The bundle also holds the run-time as text: the emitter puts it into the source of each app. Bun's minifier makes that
// text, so the bundle depends on the version of Bun (kit/README.md names the version of the checked-in bundle).

import { existsSync, mkdirSync, readFileSync, readdirSync, unlinkSync, writeFileSync } from "node:fs";
import { basename, dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { PROMPT, changeText, repairText } from "./prompt.js";

/** The modules of the bundle in dependency order: each one imports only modules before it. */
const MODULES = ["icons", "dsl", "core", "words", "parts", "check", "rows", "draw", "drag", "run", "emit", "change", "compile"];
const at = (path) => fileURLToPath(new URL(path, import.meta.url));
/** The host's sources of its icon names (ToolUiCatalog.icons): the shell glyphs and the domain icons. */
const HOST_SOURCES = ["ToolUiCatalog", "ToolIcons"].map((name) => at(`../app/src/main/java/com/alpha/assistant/tools/${name}.kt`));

/**
 * The text of src/icons.js: each icon name that the host's validator takes, in the host's order: shellIcons of ToolUiCatalog.kt,
 * then the keys of paths of ToolIcons.kt. The build writes it, so the kit and the host cannot differ, and only check.js imports it.
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

/** A module of the kit as [name, text]. The icon names come from the host's sources, so the bundle never holds an old copy. */
const source = (name) => [name, name === "icons" ? iconModule() : readFileSync(at(`src/${name}.js`), "utf8")];
const scanner = new Bun.Transpiler({ loader: "js" });

/**
 * The modules [[name, text]] as one script. Each module keeps a scope of its own and shares only its exported names, so two
 * modules can use one name for their own helpers. An import names a module before it and names that it exports, and an
 * export line declares one function, class, or const. The build refuses each other form.
 */
export function script(modules) {
  const exported = new Map();
  return modules.map(([name, text]) => {
    const names = [];
    const body = text.replace(/^import (.*);$/gm, (line, what) => {
      const [, list, from] = /^\{ ([\w$, ]+) \} from "\.\/([\w-]+)\.js"$/.exec(what) ?? [];
      if (!list || !list.split(", ").every((one) => exported.get(from)?.has(one))) throw new Error(`kit/src/${name}.js: the build cannot keep this import: ${line}`);
      return "";
    }).replace(/^export ((?:async )?(?:function|class|const) ([\w$]+))/gm, (_, declaration, one) => { names.push(one); return declaration; });
    const left = /^(?:import|export)\b.*$/m.exec(body);
    if (left) throw new Error(`kit/src/${name}.js: the build cannot remove this line: ${left[0]}`);
    // export const a = 1, b = 2 would share only a.
    const all = scanner.scan(text).exports;
    if (all.length !== names.length) throw new Error(`kit/src/${name}.js: an export line declares one name: ${all.filter((one) => !names.includes(one)).join(", ")}`);
    exported.set(name, new Set(names));
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
  const modules = Object.fromEntries(MODULES.map((name) => [`/kit/src/${name}.js`, withoutMessages(name, source(name)[1])]));
  const built = await Bun.build({ entrypoints: [entry], files: { [entry]: 'import { handle } from "/kit/src/run.js";\n__handle(handle);\n', ...modules },
    minify: true, format: "iife", target: "browser", define: { MESSAGES: "false" } });
  if (!built.success) throw new AggregateError(built.logs, "kit/build.js: Bun.build of the run-time failed");
  return `let handle;const __handle=(f)=>{handle=f};\n${(await built.outputs[0].text()).trim()}`;
}

/**
 * The bundle defines globalThis.compileAppFile, globalThis.patchAppFile (the plan after a repair reply), and globalThis.readPlanReply
 * (a plan or an outside word from the reply of a plan call), and no other global.
 */
export async function bundle() {
  return `// Built by kit/build.js from kit/src with Bun ${Bun.version}. Do not edit: run bun kit/build.js.\n(() => {\n"use strict";\n${script(MODULES.map(source))}runtime.text = ${JSON.stringify(await runtimeText())};\nglobalThis.compileAppFile = compileAppFile;\nglobalThis.patchAppFile = patchAppFile;\nglobalThis.readPlanReply = readPlanReply;\n})();\n`;
}

/**
 * The prompt asset of the app (prompt.json): the four sections of kit/prompt.md, and the task texts of a repair call and a change call
 * with the placeholders {{task}}, {{plan}}, {{faults}}, and {{request}}. The host puts the plan with no newline at its end, and the faults
 * joined by "\n- ", so a filled template is the text of repairText and changeText (prompt.js). No prompt text is typed in Kotlin.
 */
export const promptAsset = () => ({ system: PROMPT.System, repair: PROMPT.Repair, dialogue: PROMPT.Dialogue, change: PROMPT.Change,
  repairTask: repairText({ task: "{{task}}", plan: "{{plan}}", faults: ["{{faults}}"] }), changeTask: changeText({ request: "{{request}}", plan: "{{plan}}" }) });

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
 */
export const outputs = async () => [[at("src/icons.js"), iconModule()], [at("../app/src/main/assets/appfile/kit.js"), await bundle()],
  [at("../app/src/main/assets/appfile/prompt.json"), JSON.stringify(promptAsset(), null, 2) + "\n"],
  ...planCopies([...plansIn(at("plans")), ...["nodes", "looks", "lists", "rich", "cards", "free", "shapes", "bytes", "model"].map((name) => at(`test/${name}.plan`)), ...LOOK_FIXTURES],
    at("../app/src/androidTest/assets/appfile")),
  ...planCopies(plansIn(at("test/fixtures/model")), at("../app/src/androidTest/assets/appfile/model")),
  [at("../app/src/androidTest/assets/appfile/model/index.json"), readFileSync(at("test/fixtures/model/index.json"), "utf8")]];

if (import.meta.main) {
  const check = process.argv.includes("--check");
  let differs = 0;
  for (const [path, text] of await outputs()) {
    if (text === null ? !existsSync(path) : existsSync(path) && readFileSync(path, "utf8") === text) continue;
    if (check) { console.error(`${text === null ? "no source" : "differs"}: ${path}`); differs++; continue; }
    if (text === null) { unlinkSync(path); console.log(`deleted ${path}`); continue; }
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, text);
    console.log(`wrote ${path}`);
  }
  // The minified run-time depends on the version of Bun: a different version can be the only difference.
  if (differs) { console.error(`Run bun kit/build.js to write them. This check ran with Bun ${Bun.version}.`); process.exit(1); }
}
