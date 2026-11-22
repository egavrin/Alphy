// The template forms by their word (docs/superpowers/plans/2026-11-22-one-scene.md, decision 1). A new form is a module of its own and its
// entry here. template.js reads, merges, expands, and builds a template file with the form of its first line.

import { SCENE_FORM } from "./form-scene.js";
import { ITEMS_FORM } from "./form-items.js";
import { CELLS_FORM } from "./form-cells.js";

/**
 * @typedef {object} TemplateForm
 * @property {"scene"|"items"|"cells"} word
 * @property {RegExp} part   the names of its part lines
 * @property {string} lines  its line text for a fault
 * @property {string} control  its control forms for a fault
 * @property {(tree: object) => string | null} controlFault  the fault of its control line
 * @property {(tree: object) => string | null} [partFault]  the fault of a part line. A form whose part names match no line has none.
 * @property {(tree: object) => string | null} cardFault  the fault of its card line
 * @property {(parts: object[], control?: object) => {lines?: string[], shapes?: string[], options?: object, cardText?: string}} [expand]
 *   what the expansion of template.js takes from the form: app lines before the drawing, the shapes of the drawing, the options of a
 *   choice, and the card text. Each one that it leaves out keeps the default: no line, the parts as shapes, the options of the control,
 *   and the text of the card line.
 * @property {(parts: object[]) => object} [start]  the value that $/v keeps when the control is left out. Without it, the start of the control.
 * @property {(owners: Map<string, string[]>, alone: (names: string[]) => string[], parts: string[], add: (name: string, fault: string) => void) => void} [owners]
 *   the owners of the faults of the compile of an expansion that the form gives before the parts (template.js ownersOf).
 */

/** @type {Record<"scene"|"items"|"cells", TemplateForm>} */
export const FORMS_BY_WORD = { scene: SCENE_FORM, items: ITEMS_FORM, cells: CELLS_FORM };
