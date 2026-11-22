// The template forms by their word (docs/superpowers/plans/2026-11-22-one-scene.md, decision 1). A new form is a module of its own and its
// entry here. template.js reads, merges, expands, and builds a template file with the form of its first line.

import { SCENE_FORM } from "./form-scene.js";
import { ITEMS_FORM } from "./form-items.js";
import { CELLS_FORM } from "./form-cells.js";
import { COMPARE_FORM } from "./form-compare.js";
import { TABLE_FORM } from "./form-table.js";
import { CHART_FORM } from "./form-chart.js";
import { NODATA_FORM } from "./form-nodata.js";

/**
 * @typedef {object} TemplateForm
 * @property {string} word
 * @property {"data"} [family]  a form of the data family (form-data.js): it has the host line source, and its own app file (file)
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
 * @property {string} [head]  a named argument of the first line beyond lang= and palette=, which headFault of the form checks
 * @property {(tree: object) => string | null} [headFault]  the fault of that argument
 * @property {(tree: object, name: string) => object} [cut]  the tree of a line with each long label cut (the kept line holds the cut text)
 * @property {(lines: object[]) => [string, string][]} [context]  the faults that need more than one line, as [name, message]
 * @property {(parts: object[], lines: object[], drawn: (name: string) => boolean) => string | null} [whole]  the whole fault of a file with these parts
 * @property {(head: object, parts: object[], control?: object, card?: object, source?: object) => string} [file]  the whole app file of a data form
 */

/** @type {Record<string, TemplateForm>} */
export const FORMS_BY_WORD = { scene: SCENE_FORM, items: ITEMS_FORM, cells: CELLS_FORM, compare: COMPARE_FORM, table: TABLE_FORM, chart: CHART_FORM, nodata: NODATA_FORM };
