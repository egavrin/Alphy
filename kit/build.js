// Builds the sandbox bundle of the kit and copies the saved plans for the device tests.
//   bun kit/build.js           writes app/src/main/assets/appfile/kit.js and app/src/androidTest/assets/appfile/*.plan
//   bun kit/build.js --check   writes nothing, and exits 1 when a file that the build writes differs
// The sandbox evaluates plain script text, not modules, so the bundle is one script with no import and no export.

import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";
import { fileURLToPath } from "node:url";

/** The modules of the bundle in dependency order: each one imports only modules before it. */
const MODULES = ["dsl", "core", "parts", "check", "compile"];
const at = (path) => fileURLToPath(new URL(path, import.meta.url));

/**
 * The modules with their import and export statements removed. Each module keeps a scope of its own, so two modules
 * can use one name for their own helpers, and only the exported names are shared. The script defines globalThis.compileAppFile
 * and no other global.
 */
export function bundle() {
  const modules = MODULES.map((name) => {
    const names = [];
    const body = readFileSync(at(`src/${name}.js`), "utf8")
      .replace(/^import .*;$/gm, "")
      .replace(/^export ((?:async )?(?:function|class|const) ([\w$]+))/gm, (_, declaration, exported) => { names.push(exported); return declaration; });
    const left = /^(?:import|export)\b.*$/m.exec(body);
    if (left) throw new Error(`kit/src/${name}.js: the build cannot remove this line: ${left[0]}`);
    return `// ${name}.js\nconst { ${names.join(", ")} } = (() => {\n${body}\nreturn { ${names.join(", ")} };\n})();\n`;
  });
  return `// Built by kit/build.js from kit/src. Do not edit: run bun kit/build.js.\n(() => {\n"use strict";\n${modules.join("")}globalThis.compileAppFile = compileAppFile;\n})();\n`;
}

/** Each file that the build writes, with its text. */
function outputs() {
  const plans = readdirSync(at("plans")).filter((name) => name.endsWith(".plan"));
  return [[at("../app/src/main/assets/appfile/kit.js"), bundle()],
    ...plans.map((name) => [at(`../app/src/androidTest/assets/appfile/${name}`), readFileSync(at(`plans/${name}`), "utf8")])];
}

if (import.meta.main) {
  const check = process.argv.includes("--check");
  let differs = 0;
  for (const [path, text] of outputs()) {
    if (existsSync(path) && readFileSync(path, "utf8") === text) continue;
    if (check) { console.error(`differs: ${path}`); differs++; continue; }
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, text);
    console.log(`wrote ${path}`);
  }
  if (differs) { console.error("Run bun kit/build.js to write them."); process.exit(1); }
}
