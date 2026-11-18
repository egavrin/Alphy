// The look numbers of each saved plan and each look fixture: score_app of tools/agent_corpus/visual_score.py on the compiled definition, with no tags.
//   bun kit/look.js   prints one table row for each plan: the plan, hero (1 or 0), typed fields on the first screen, and typed fields in rows

import { readFileSync } from "node:fs";
import { basename } from "node:path";
import { fileURLToPath } from "node:url";
import { compileAppFile } from "./src/compile.js";
import { runtime } from "./src/emit.js";
import { LOOK_FIXTURES, plansIn, runtimeText } from "./build.js";

runtime.text = await runtimeText();
const definitions = {};
for (const path of [...plansIn(fileURLToPath(new URL("plans", import.meta.url))).sort(), ...LOOK_FIXTURES]) {
  const { faults, definition } = await compileAppFile(readFileSync(path, "utf8"));
  if (!definition) throw new Error(`${path}: ${faults.join("; ")}`);
  definitions[basename(path, ".plan")] = definition;
}
const table = `import json, sys
from visual_score import score_app
for name, definition in json.load(sys.stdin).items(): s = score_app(definition, []); print(f"| \`{name}\` | {s['hero']} | {s['fields']} | {s['rowFields']} |")`;
const scored = Bun.spawnSync(["python3", "-c", table], { cwd: fileURLToPath(new URL("../tools/agent_corpus", import.meta.url)), stdin: Buffer.from(JSON.stringify(definitions)), stderr: "inherit" });
process.stdout.write(scored.stdout);
process.exit(scored.exitCode);
