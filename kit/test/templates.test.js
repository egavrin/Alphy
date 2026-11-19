// The look of the part templates (Task 11b-2b, A): each part in the look vocabulary of the baseline apps.

import { expect, test } from "bun:test";
import { built, every } from "./helpers.js";

/** The node of a type whose text or name is the given one. */
const named = (definition, type, text) => every(definition.ui).find((node) => node.type === type && (node.text === text || node.name === text));
/** A button as its text, variant, and icon. */
const look = (node) => [node.text, node.variant, node.icon];

test("template: a timer with rounds is a hero card, Start is filled with Play, Pause is outlined, Reset is a text button, and the phase text is muted", async () => {
  const { definition } = await built('app("T", lang="en")', 'need t = timer("Workout", 40, rounds=2, rest=20)');
  const card = every(definition.ui).find((node) => node.type === "card");
  expect(card.variant).toBe("hero");
  expect(["Start", "Pause", "Reset"].map((text) => look(named(definition, "button", text)))).toEqual([["Start", "filled", "Play"], ["Pause", "outlined", "Pause"], ["Reset", "text", "Refresh"]]);
  const phase = every(card).find((node) => node.type === "text" && String(node.bind).includes("phaseText"));
  expect([phase.style, definition.initialState.d[phase.bind.slice(2)]]).toEqual([{ tone: "muted" }, "Work 1 / 2"]);
  // The host's own countdown is a hero card too.
  const own = await built('app("T", lang="en")', 'need t = timer("Tea", 180, alert=true)');
  expect(every(own.definition.ui).find((node) => node.type === "card").variant).toBe("hero");
});
