// Builds the sandbox bundle of the kit and copies the saved plans for the device tests.
//   bun kit/build.js           writes app/src/main/assets/appfile/kit.js and app/src/androidTest/assets/appfile/*.plan
//   bun kit/build.js --check   writes nothing, and exits 1 when a file that the build writes differs or a copied plan has no source
// The sandbox evaluates plain script text, not modules, so the bundle is one script with no import and no export.
// The bundle also holds the run-time as text: the emitter puts it into the source of each app. Bun's minifier makes that
// text, so the bundle depends on the version of Bun (kit/README.md names the version of the checked-in bundle).

import { existsSync, mkdirSync, readFileSync, readdirSync, unlinkSync, writeFileSync } from "node:fs";
import { basename, dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

/** The modules of the bundle in dependency order: each one imports only modules before it. */
const MODULES = ["dsl", "core", "parts", "check", "rows", "draw", "drag", "run", "emit", "compile"];
const at = (path) => fileURLToPath(new URL(path, import.meta.url));
const source = (name) => [name, readFileSync(at(`src/${name}.js`), "utf8")];
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
 */
export async function runtimeText() {
  const entry = "/kit-runtime-entry.js";
  const modules = Object.fromEntries(MODULES.map((name) => [at(`src/${name}.js`), withoutMessages(name, readFileSync(at(`src/${name}.js`), "utf8"))]));
  const built = await Bun.build({ entrypoints: [entry], files: { [entry]: `import { handle } from ${JSON.stringify(at("src/run.js"))};\n__handle(handle);\n`, ...modules },
    minify: true, format: "iife", target: "browser", define: { MESSAGES: "false" } });
  if (!built.success) throw new AggregateError(built.logs, "kit/build.js: Bun.build of the run-time failed");
  return `let handle;const __handle=(f)=>{handle=f};\n${(await built.outputs[0].text()).trim()}`;
}

/** The bundle defines globalThis.compileAppFile and no other global. */
export async function bundle() {
  return `// Built by kit/build.js from kit/src with Bun ${Bun.version}. Do not edit: run bun kit/build.js.\n(() => {\n"use strict";\n${script(MODULES.map(source))}runtime.text = ${JSON.stringify(await runtimeText())};\nglobalThis.compileAppFile = compileAppFile;\n})();\n`;
}

/** The plan files of a directory. */
export const plansIn = (directory) => readdirSync(directory).filter((name) => name.endsWith(".plan")).map((name) => join(directory, name));

/** The copy of each plan file in to, and null as the text of a copy in to whose plan is gone: the build deletes it. */
export function planCopies(plans, to) {
  const names = plans.map((path) => basename(path));
  const gone = existsSync(to) ? readdirSync(to).filter((name) => name.endsWith(".plan") && !names.includes(name)) : [];
  return [...plans.map((path) => [join(to, basename(path)), readFileSync(path, "utf8")]), ...gone.map((name) => [join(to, name), null])];
}

/** The fixtures that share a name with a case of the corpus: the pictures of AppFileLookTest and kit/look.js show them beside the saved plans. */
export const LOOK_FIXTURES = ["water-ko", "units-pl", "shopping", "habits", "price-filter"].map((name) => at(`test/fixtures/${name}.plan`));

/** Each file that the build writes, with its text. The device tests also read the test plans of the node table, the lists, the render work limit, the cards, the free forms, the shapes, the reply at the byte limit, and a model beside a drawing, and the look fixtures. */
const outputs = async () => [[at("../app/src/main/assets/appfile/kit.js"), await bundle()],
  ...planCopies([...plansIn(at("plans")), ...["nodes", "lists", "rich", "cards", "free", "shapes", "bytes", "model"].map((name) => at(`test/${name}.plan`)), ...LOOK_FIXTURES],
    at("../app/src/androidTest/assets/appfile"))];

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
