// The sandbox entry of the kit: an app file in, the report of the host and the app definition out. kit/build.js makes it the one global of the bundle.

import { AppFile } from "./dsl.js";
import { expand } from "./parts.js";
import { report } from "./check.js";
import { emit } from "./emit.js";

/**
 * The faults hold the line faults and the report faults, or, when the report is clean, what the emitter cannot build yet.
 * A definition comes only with no fault. A throw inside the kit is the one fault "kit: <message>".
 */
export async function compileAppFile(text) {
  try {
    const file = new AppFile().feedText(text);
    await expand(file);
    const { faults, notes } = report(file);
    if (faults.length) return { faults, notes, definition: null, language: null };
    const emitted = emit(file);
    return { faults: emitted.faults, notes, definition: emitted.definition, language: emitted.language };
  } catch (failure) {
    return { faults: [`kit: ${failure?.message ?? failure}`], notes: [], definition: null, language: null };
  }
}
