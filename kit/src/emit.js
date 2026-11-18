// The emitter: an expanded app file as Alphy's app definition (formatVersion 3). The source of the definition is the
// run-time (run.js and the modules that it needs) and the compiled data of the app. The run-time answers each event.

import { App } from "./core.js";
import { header, namer, screen, stateOf } from "./run.js";

/** The text of the run-time. kit/build.js puts it into the bundle, and a test of the sources sets it from the sources. */
export const runtime = { text: "" };
/** Alphy keeps at most 40 top-level state fields (ToolValueSchema.validateSchemas). */
const MAX_FIELDS = 40;
const SCALARS = new Set(["number", "string", "boolean"]);

/**
 * The definition of the app, its language beside it, and the faults of what the emitter cannot build. app is the compiled
 * data that the source gives the run-time: the lines of the app, the field of each state value, and the names in both directions.
 * Faults give no definition.
 */
export function emit(file) {
  if (!runtime.text) throw new Error("emit.js has no run-time text: kit/build.js gives it");
  const kit = new App(file), { title, lang } = header(kit), faults = [], stateSchema = {}, owners = [];
  const app = { lines: [...file.entries.values()].filter((entry) => entry.kind !== "check").map((entry) => entry.raw).join("\n"), fields: [], names: { safe: {}, kit: {} } };
  const name = namer(app.names);
  if (typeof title !== "string" || title.length < 1 || title.length > 100) faults.push("app: the app line needs a title of 1 to 100 characters");
  // The need whose part wrote each state line. A line that an author wrote belongs to no need.
  const partOf = new Map([...(file.partLines ?? [])].flatMap(([need, keys]) => keys.map((key) => [key, need])));
  for (const entry of file.of("state")) {
    const root = entry.key.slice(2), value = kit.state[root], need = entry.origin === "part" ? partOf.get(entry.key) : undefined;
    if (root.includes("/") || !SCALARS.has(typeof value)) { faults.push(`${entry.key}: a ${Array.isArray(value) ? "list" : root.includes("/") ? "path inside a value" : "value of this kind"} is not built yet`); continue; }
    // A need keeps its value in one field and each other value of its part in one object NAME_s, so it costs at most two fields.
    const inner = need !== undefined && root !== need && root.startsWith(need + "_");
    const field = inner ? name("need:" + need, need + "_s") : name("$/" + root, root);
    if (!stateSchema[field]) owners.push(need === undefined ? entry.key : `need ${need}`);
    if (inner) {
      const key = name("$/" + root, root.slice(need.length + 1));
      (stateSchema[field] ??= { type: "object", fields: {} }).fields[key] = typeof value;
      app.fields.push([root, `${field}.${key}`]);
    } else {
      stateSchema[field] = typeof value;
      app.fields.push([root, field]);
    }
  }
  const shown = screen(kit, app);
  faults.push(...shown.faults);
  for (const [object, values] of Object.entries(shown.values)) {
    stateSchema[object] = { type: "object", fields: Object.fromEntries(Object.entries(values).map(([key, value]) => [key, typeof value])) };
  }
  // The objects of the shown values come first, so the fault names the need or the line whose field is the 41st.
  const over = owners[MAX_FIELDS - Object.keys(shown.values).length];
  if (over) faults.push(`${over}: the app needs more than ${MAX_FIELDS} state fields from here on, and Alphy keeps at most ${MAX_FIELDS}. Use fewer needs`);
  const language = typeof lang === "string" ? lang : null;
  if (faults.length) return { definition: null, language, faults, app };
  const definition = { formatVersion: 3, title, description: "", capabilities: [], stateSchema, initialState: stateOf(kit, app, shown), ui: shown.ui,
    source: `${runtime.text}\nreturn handle(input, ${JSON.stringify(app)});` };
  return { definition, language, faults: [], app };
}
