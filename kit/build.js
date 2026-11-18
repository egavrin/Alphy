// Builds the sandbox bundle of the kit and copies the saved plans for the device tests.
//   bun kit/build.js           writes app/src/main/assets/appfile/kit.js and app/src/androidTest/assets/appfile/*.plan
//   bun kit/build.js --check   writes nothing, and exits 1 when a file that the build writes differs or a copied plan has no source
// The sandbox evaluates plain script text, not modules, so the bundle is one script with no import and no export.
// The bundle also holds the run-time as text: the emitter puts it into the source of each app.

import { existsSync, mkdirSync, readFileSync, readdirSync, unlinkSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

/** The modules of the bundle in dependency order: each one imports only modules before it. */
const MODULES = ["dsl", "core", "parts", "check", "run", "emit", "compile"];
/** The modules of the run-time, which answers each event of a compiled app. */
const RUNTIME = ["dsl", "core", "run"];
const at = (path) => fileURLToPath(new URL(path, import.meta.url));
const source = (name) => [name, readFileSync(at(`src/${name}.js`), "utf8")];
const scanner = new Bun.Transpiler({ loader: "js" });

/** A module with no comment line, no indentation, and no empty line. A template literal over lines would change, so it is an error. */
function stripped(name, text) {
  let comment = false;
  return text.split("\n").map((line) => line.trim()).filter((line) => {
    if (comment || line.startsWith("/*")) { comment = !line.includes("*/"); return false; }
    if ((line.match(/`/g) ?? []).length % 2) throw new Error(`kit/src/${name}.js: the build cannot strip a template literal over lines: ${line}`);
    return line && !line.startsWith("//");
  }).join("\n");
}

/**
 * The modules [[name, text]] as one script. Each module keeps a scope of its own and shares only its exported names, so two
 * modules can use one name for their own helpers. An import names a module before it and names that it exports, and an
 * export line declares one function, class, or const. The build refuses each other form. strip makes the run-time text.
 */
export function script(modules, strip = false) {
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
    return `${strip ? "" : `// ${name}.js\n`}const { ${names.join(", ")} } = (() => {\n${strip ? stripped(name, body) : body}\nreturn { ${names.join(", ")} };\n})();\n`;
  }).join("");
}

/** The text of the run-time that the emitter puts into the source of each app, before its compiled data. */
export const runtimeText = () => script(RUNTIME.map(source), true);

/** The bundle defines globalThis.compileAppFile and no other global. */
export function bundle() {
  return `// Built by kit/build.js from kit/src. Do not edit: run bun kit/build.js.\n(() => {\n"use strict";\n${script(MODULES.map(source))}runtime.text = ${JSON.stringify(runtimeText())};\nglobalThis.compileAppFile = compileAppFile;\n})();\n`;
}

/** The copy of each plan of from in to, and null as the text of a copy in to whose plan is gone: the build deletes it. */
export function planCopies(from, to) {
  const plans = readdirSync(from).filter((name) => name.endsWith(".plan"));
  const gone = existsSync(to) ? readdirSync(to).filter((name) => name.endsWith(".plan") && !plans.includes(name)) : [];
  return [...plans.map((name) => [join(to, name), readFileSync(join(from, name), "utf8")]), ...gone.map((name) => [join(to, name), null])];
}

/** Each file that the build writes, with its text. */
const outputs = () => [[at("../app/src/main/assets/appfile/kit.js"), bundle()], ...planCopies(at("plans"), at("../app/src/androidTest/assets/appfile"))];

if (import.meta.main) {
  const check = process.argv.includes("--check");
  let differs = 0;
  for (const [path, text] of outputs()) {
    if (text === null ? !existsSync(path) : existsSync(path) && readFileSync(path, "utf8") === text) continue;
    if (check) { console.error(`${text === null ? "no source" : "differs"}: ${path}`); differs++; continue; }
    if (text === null) { unlinkSync(path); console.log(`deleted ${path}`); continue; }
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, text);
    console.log(`wrote ${path}`);
  }
  if (differs) { console.error("Run bun kit/build.js to write them."); process.exit(1); }
}
