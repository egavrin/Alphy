// The drag of a 3D model on its surface: a pointer that moves along x turns the model around its upright axis (the prototype's rule).
// run.js gives each pointer event of a surface to dragged, and the kit's state keeps the drag that runs in _drag.

import { fixed } from "./draw.js";

/** The turn of a model for each pixel that a drag moves along x, in degrees. */
const DEGREES_PER_PIXEL = 0.6;
/** The drag of a model when no drag runs, also after each open (run.js handle): the surface, the pointer, and the last x of a drag. The state field of the drag has its fields (emit.js). */
export const IDLE = Object.freeze({ surface: "", pointer: 0, x: 0 });
/** A turn in degrees as a turn from 0 to 360, with 2 decimals. */
const wrap = (degrees) => fixed(((degrees % 360) + 360) % 360);

/**
 * A pointer event of a surface. drawn is each surface of the screen (run.js screen). On the surface of a model with a turn, a down
 * starts a drag at its x, a move or an up turns the model by DEGREES_PER_PIXEL for each pixel since the last event, and an up or a
 * cancel ends the drag. A down of a second pointer while a drag runs, a pointer of another surface, a place that is not a number,
 * and a move, an up, or a cancel of no drag of that surface and pointer change nothing (false).
 */
export function dragged(kit, drawn, { surfaceId, x, phase, pointer }) {
  const model = drawn.find((surface) => surface.id === surfaceId)?.model, drag = kit.state._drag ?? IDLE;
  if (!model || !Number.isFinite(x)) return false;
  if (phase === "down") {
    if (drag.surface && drag.pointer !== pointer) return false;
    kit.state._drag = { surface: surfaceId, pointer, x };
    return "";
  }
  if (!["move", "up", "cancel"].includes(phase) || drag.surface !== surfaceId || drag.pointer !== pointer) return false;
  kit.state._drag = phase === "move" ? { ...drag, x } : IDLE;
  const from = Number.isFinite(model.props.value) ? model.props.value : 0;
  return phase === "cancel" ? "" : kit.change(model, wrap(fixed(from + (x - drag.x) * DEGREES_PER_PIXEL)));
}
