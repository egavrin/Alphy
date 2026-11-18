// The sandbox entry of the kit: an app file in, the report of the host out. kit/build.js makes it the one global of the bundle.

import { AppFile } from "./dsl.js";
import { expand } from "./parts.js";
import { report } from "./check.js";

/** The faults hold the line faults and the report faults. A throw inside the kit is the one fault "kit: <message>". */
export async function compileAppFile(text) {
  try {
    const file = new AppFile().feedText(text);
    await expand(file);
    const { faults, notes } = report(file);
    return { faults, notes, definition: null, language: null };
  } catch (failure) {
    return { faults: [`kit: ${failure?.message ?? failure}`], notes: [], definition: null, language: null };
  }
}
