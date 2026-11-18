// The drawing of a surface: the kit's shapes as Alphy's drawing commands (GraphicsContract.frame). Now only the bar of a timer.

/** The surface of a timer's bar, in Alphy's pixels of a surface (64 to 2048 each way). */
export const BAR_SURFACE = { width: 320, height: 64 };
/** A neutral track at 20 % opacity, so the bar reads on a light and on a dark theme. A tone of Alphy is a text color. */
const TRACK = "#33808080";

/** Each rect of the kit, {shape: "rect", x, y, w, h, color, radius?}, as a rect of Alphy, or as a roundRect when it has a radius. */
export function commandsOf(shapes) {
  return shapes.map(({ shape, x, y, w, h, color, radius }) => {
    if (shape !== "rect") throw new Error(`a ${shape} is not drawn yet`);
    return radius ? { type: "roundRect", x, y, width: w, height: h, radius, color } : { type: "rect", x, y, width: w, height: h, color };
  });
}

/** The bar of a timer's phase: the track, and the share of the phase that passed (0 to 1) in the color of the phase. Two commands. */
export const barOf = (share, color) => [
  { shape: "rect", x: 0, y: 24, w: BAR_SURFACE.width, h: 16, color: TRACK, radius: 8 },
  { shape: "rect", x: 0, y: 24, w: Math.round(BAR_SURFACE.width * share), h: 16, color, radius: 8 },
];
