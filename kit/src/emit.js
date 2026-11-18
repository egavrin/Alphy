// The emitter: an expanded app file as Alphy's app definition (formatVersion 3). The source of the definition is the
// run-time (run.js and the modules that it needs) and the compiled data of the app. The run-time answers each event.

import { App } from "./core.js";
import { PER_OBJECT, header, namer, screen, stateOf } from "./run.js";

/** The text of the run-time. kit/build.js puts it into the bundle, and a test of the sources sets it from the sources. */
export const runtime = { text: "" };
/**
 * The limits of Alphy that come from the kit's own mapping, so the author of a plan cannot see them: top-level state fields
 * (ToolValueSchema.validateSchemas), source bytes (SandboxContract.MAX_SOURCE_BYTES), and the nodes and depth of a ui
 * (ToolUiCatalog). ToolSpec.parse stays the check of the ui bytes and the state bytes.
 */
const MAX_FIELDS = 40, MAX_SOURCE_BYTES = 65536, MAX_NODES = 512, MAX_DEPTH = 8;
const SCALARS = new Set(["number", "string", "boolean"]);

/** The UTF-8 bytes of a text, as the host counts them. The sandbox has no TextEncoder that the kit can count on. */
const bytes = (text) => {
  let count = 0;
  for (const char of text) { const code = char.codePointAt(0); count += code < 0x80 ? 1 : code < 0x800 ? 2 : code < 0x10000 ? 3 : 4; }
  return count;
};

/**
 * The definition of the app, its language beside it, and the faults of what the emitter cannot build. app is the compiled
 * data that the source gives the run-time: the lines of the app, the field of each state value, the type of each shown
 * value, and the names in both directions. Faults give no definition.
 */
export function emit(file) {
  if (!runtime.text) throw new Error("emit.js has no run-time text: kit/build.js gives it");
  const kit = new App(file), { title, lang } = header(kit), faults = [], stateSchema = {}, owners = [], needObjects = new Map();
  const app = { lines: [...file.entries.values()].filter((entry) => entry.kind !== "check").map((entry) => entry.raw).join("\n"), fields: [], types: {}, names: { safe: {}, kit: {} } };
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
      needObjects.set(field, need);
      app.fields.push([root, `${field}.${key}`]);
    } else {
      stateSchema[field] = typeof value;
      app.fields.push([root, field]);
    }
  }
  for (const [field, need] of needObjects) {
    const count = Object.keys(stateSchema[field].fields).length;
    if (count > PER_OBJECT) faults.push(`need ${need}: its part keeps ${count} values in the object ${field}, and an object of Alphy holds at most ${PER_OBJECT} fields. Give the need fewer fields`);
  }
  const shown = screen(kit, app);
  faults.push(...shown.faults);
  for (const [object, values] of Object.entries(shown.values)) {
    stateSchema[object] = { type: "object", fields: Object.fromEntries(Object.entries(values).map(([key, value]) => [key, typeof value])) };
  }
  // The objects of the shown values come first, so the fault names the need or the line whose field is the 41st.
  const over = owners[MAX_FIELDS - Object.keys(shown.values).length];
  if (over) faults.push(`${over}: the app needs more than ${MAX_FIELDS} state fields from here on, and Alphy keeps at most ${MAX_FIELDS}. Use fewer needs`);
  // The theme and the column of the app are depths 0 and 1. A node that the kit adds (the text of a banner) belongs to its component.
  let nodes = 0;
  const measure = (node, depth, owner) => {
    nodes++;
    owner = shown.owners.get(node) ?? owner;
    const deep = `${owner}: this component is at depth ${depth} of the screen, and Alphy keeps at most depth ${MAX_DEPTH}. The frame of the app takes depths 0 and 1. Put it in fewer containers`;
    if (depth > MAX_DEPTH) { if (!faults.includes(deep)) faults.push(deep); return; }
    for (const child of node.children ?? []) measure(child, depth + 1, owner);
  };
  measure(shown.ui, 0, "app");
  if (nodes > MAX_NODES) faults.push(`app: the screen has ${nodes} nodes, and Alphy keeps at most ${MAX_NODES}. Show fewer components`);
  const language = typeof lang === "string" ? lang : null;
  if (faults.length) return { definition: null, language, faults, app };
  const source = `${runtime.text}\nreturn handle(input, ${JSON.stringify(app)});`, size = bytes(source);
  if (size > MAX_SOURCE_BYTES) return { definition: null, language, faults: [`app: the source is ${size} bytes, and Alphy keeps at most ${MAX_SOURCE_BYTES} bytes. Use shorter texts and fewer lines`], app };
  const definition = { formatVersion: 3, title, description: "", capabilities: [], stateSchema, initialState: stateOf(kit, app, shown), ui: shown.ui, source };
  return { definition, language, faults: [], app };
}
