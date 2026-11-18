// Timers: the host's own countdown (Alphy's foreground timer) and a timer with rounds on a surface that the run-time draws.

import { expect, test } from "bun:test";
import { App, settings } from "../src/core.js";
import { report } from "../src/check.js";
import { commandsOf } from "../src/draw.js";
import { emit } from "../src/emit.js";
import { handle } from "../src/run.js";
import { gateEach, plan, planned } from "./helpers.js";

gateEach("smoke");

const every = (node) => [node, ...(node.children ?? []).flatMap(every)];
const compiled = async (text) => { const file = await planned(text); return { file, ...emit(file) }; };
const request = (state, event) => ({ artifactId: "kit-test", revision: 1, state, event });
const press = (actionId, value = null) => ({ type: "action", actionId, value });
const timer = (timerId) => ({ type: "timer", timerId });
const duration = (ui) => every(ui).find((node) => node.type === "timer").foreground.durationMs;
/** A time of the host's clock in ms. Each event of a test gives its time to handle. */
const T0 = 1_800_000_000_000;
/**
 * timer-rounds.plan as the run-time and as the kit's App, whose state gives the expected values. send gives one event to both
 * at a time in ms after T0, each on the state of the reply before it: a press, or a timer event (a look at the clock for the kit).
 */
const rounds = async () => {
  const { file, definition, app } = await compiled(plan("timer-rounds")), kit = new App(file);
  let state = definition.initialState;
  const send = (ms, event) => {
    settings.now = () => T0 + ms;
    if (event.type === "action") kit.press(event.actionId); else kit.tick();
    const reply = handle(request(state, event), app, T0 + ms);
    state = reply.state;
    return reply;
  };
  return { kit, send };
};
/** One work second and one rest second in 2 rounds. The device test timerRoundsPassesParseAndSmoke has the same plan. */
const SHORT = 'app("Short rounds", lang="en")\nneed t = timer("Short", 1, rounds=2, rest=1)\nneed done = progress("Rounds done", $/t, 2)\n';

test("emit: tea.plan gives a foreground timer with the duration of the chosen tea", async () => {
  const { file, definition, faults } = await compiled(plan("tea"));
  expect(faults).toEqual([]);
  expect(every(definition.ui).filter((node) => node.type === "timer")).toEqual([{ type: "timer", text: "Demleme süresi", mode: "countdown", action: "demleme_timer",
    foreground: { durationMs: 300000, vibrationMs: 600, toneMs: 600 } }]);
  expect(definition.capabilities).toEqual(["audio.tone", "vibration.play"]);
  expect(definition.surfaces).toBeUndefined();
  // The host keeps the time of its own countdown, so the state has nothing of the timer.
  expect(JSON.stringify(definition.stateSchema)).not.toContain("demleme");
  expect(new App(file).find("demleme_timer").props.seconds * 1000).toBe(300000);
  // The host's countdown has its own buttons and alert, so the texts of the need line do not show.
  expect(report(file).notes).toContain("need demleme: the host's countdown shows its own buttons and alert, so start=, pause=, reset=, done= do not show");
});

test("run: a new choice gives a new ui with the new duration", async () => {
  const { file, definition, app } = await compiled(plan("tea"));
  const kit = new App(file);
  const green = handle(request(definition.initialState, press("cay_field", "Yeşil çay")), app);
  kit.input("cay_field", "Yeşil çay");
  expect([green.state.cay, duration(green.ui)]).toEqual(["Yeşil çay", kit.find("demleme_timer").props.seconds * 1000]);
  expect(duration(green.ui)).toBe(180000);
  const herbal = handle(request(green.state, press("cay_field", "Bitki çayı")), app);
  kit.input("cay_field", "Bitki çayı");
  expect(duration(herbal.ui)).toBe(kit.find("demleme_timer").props.seconds * 1000);
  // The same choice again changes no screen, and the foreground timer's action is the host's: JavaScript changes nothing for it.
  expect(handle(request(herbal.state, press("cay_field", "Bitki çayı")), app).ui).toBeUndefined();
  expect(handle(request(herbal.state, press("demleme_timer")), app)).toEqual({ state: herbal.state });
});

test("emit: a foreground timer outside 1 second to 24 hours is a fault, and the run-time keeps a duration inside them", async () => {
  const zero = await compiled('app("T", lang="en")\nneed t = timer("T", 0, alert=true)');
  expect(zero.faults).toEqual(["need t: a Timer runs from 1 second to 24 hours, and this one has 0 seconds"]);
  const { definition, app } = await compiled('app("T", lang="en")\nneed n = input("number", "Minutes", 1, min=0, max=2000)\nneed t = timer("T", $/n * 60, alert=true)');
  expect(duration(definition.ui)).toBe(60000);
  expect(duration(handle(request(definition.initialState, press("n_field", 0)), app).ui)).toBe(1000);
  expect(duration(handle(request(definition.initialState, press("n_field", 2000)), app).ui)).toBe(86400000);
});

test("emit: timer-rounds.plan gives a surface, a canvas, and no foreground timer", async () => {
  const { definition, faults } = await compiled(plan("timer-rounds"));
  expect(faults).toEqual([]);
  expect(definition.surfaces).toEqual([{ id: "timer_bar", width: 320, height: 64 }]);
  const all = every(definition.ui);
  expect(all.filter((node) => node.type === "canvas")).toEqual([{ type: "canvas", text: "Training", surfaces: ["timer_bar"] }]);
  expect(all.filter((node) => node.type === "timer" || node.type === "progress")).toEqual([]);
  expect(definition.capabilities).toEqual([]);
  // The end of the phase is a number of the state, in ms of the host's clock: 0 while the timer stands.
  expect(definition.initialState.timer_s).toEqual({ on: false, round: 1, resting: false, over: false, spent: 0, end: 0 });
});

test("check: alert with rounds is a note, not a fault", async () => {
  const result = report(await planned(plan("timer-rounds")));
  expect(result.faults).toEqual([]);
  expect(result.notes).toContain("need timer: a timer with rounds shows the end of a round on the screen only");
  // A timer with no alert gives no note.
  expect(report(await planned('app("T", lang="en")\nneed t = timer("T", 40, rounds=8, rest=20)')).notes).toEqual([]);
});

test("draw: a rect of the kit is a rect or a roundRect of Alphy", () => {
  expect(commandsOf([{ shape: "rect", x: 0, y: 24, w: 320, h: 16, color: "accent", radius: 8 }, { shape: "rect", x: 1, y: 2, w: 3, h: 4, color: "#33808080" }])).toEqual([
    { type: "roundRect", x: 0, y: 24, width: 320, height: 16, radius: 8, color: "accent" },
    { type: "rect", x: 1, y: 2, width: 3, height: 4, color: "#33808080" },
  ]);
  expect(() => commandsOf([{ shape: "circle", x: 1, y: 1, r: 1, color: "accent" }])).toThrow("a circle is not drawn yet");
});

/** The bar of a phase: the track, then the share of the phase that passed in its tone. */
const bar = (width, color) => [{ type: "roundRect", x: 0, y: 24, width: 320, height: 16, radius: 8, color: "#33808080" }, { type: "roundRect", x: 0, y: 24, width, height: 16, radius: 8, color }];

test("run: start asks for a 1000 ms timer and stop returns timers: []", async () => {
  const { kit, send } = await rounds();
  const started = send(0, press("timer_start"));
  expect(started.timers).toEqual([{ id: "timer_bar", intervalMs: 1000 }]);
  expect(started.state.timer_s).toEqual({ on: true, round: 1, resting: false, over: false, spent: 0, end: T0 + 40000 });
  expect(started.state.timer_s.end).toBe(kit.state.timer_end);
  expect(started.surfaces).toEqual({ timer_bar: { commands: bar(0, "accent") } });
  // The pause keeps the time that is left.
  const paused = send(10000, press("timer_pause"));
  expect(paused.timers).toEqual([]);
  expect(paused.state.timer_s).toEqual({ on: false, round: 1, resting: false, over: false, spent: kit.state.timer_spent, end: 0 });
  expect(paused.state.timer_s.spent).toBe(10);
  expect(paused.surfaces.timer_bar.commands).toEqual(bar(80, "accent"));
  // A start 100 s later ends the work phase 30 s after it.
  const again = send(110000, press("timer_start"));
  expect([again.state.timer_s.end, kit.state.timer_end]).toEqual([T0 + 140000, T0 + 140000]);
  const reset = send(111000, press("timer_reset"));
  expect([reset.timers, reset.state.timer_s]).toEqual([[], { on: false, round: 1, resting: false, over: false, spent: 0, end: 0 }]);
});

test("run: a timer event after the end of the work phase starts the rest phase", async () => {
  const { kit, send } = await rounds();
  send(0, press("timer_start"));
  const rest = send(40500, timer("timer_bar"));
  expect(rest.state.timer_s).toEqual({ on: true, round: 1, resting: true, over: false, spent: 0.5, end: T0 + 60000 });
  expect([rest.state.timer, rest.state.timer_s.spent, rest.state.timer_s.end]).toEqual([kit.state.timer, kit.state.timer_spent, kit.state.timer_end]);
  expect(Object.values(rest.state.d)).toEqual(expect.arrayContaining([kit.find("timer_phaseText").props.text, kit.find("timer_time").props.value]));
  expect(kit.find("timer_phaseText").props.text).toBe("Pause 1 / 8");
  expect(rest.timers).toEqual([{ id: "timer_bar", intervalMs: 1000 }]);
  expect(rest.surfaces.timer_bar.commands).toEqual(bar(8, "success"));
});

test("run: the count of finished rounds follows", async () => {
  const { kit, send } = await rounds();
  send(0, press("timer_start"));
  // One timer event 10 s before the end of each rest phase: round r has 40 s of work and 20 s of rest.
  for (let round = 1; round <= 8; round++) {
    const reply = send((60 * round - 10) * 1000, timer("timer_bar")), state = reply.state;
    expect([state.timer, state.timer_s.round, state.timer_s.resting]).toEqual([round, round, true]);
    expect(state.timer).toBe(kit.state.timer);
    // The figure of the round and the text of the ring follow the count.
    expect(Object.values(state.d)).toEqual(expect.arrayContaining([kit.state.runde_text, kit.state.ring_text]));
    expect(kit.state.ring_text).toBe(`${round} / 8`);
    expect(reply.timers).toEqual([{ id: "timer_bar", intervalMs: 1000 }]);
  }
  const over = send(480000, timer("timer_bar"));
  expect([over.state.timer, over.state.timer_s.over, over.state.timer_s.on, over.state.timer_s.end, over.timers]).toEqual([8, true, false, 0, []]);
  expect(over.state.timer_s).toEqual({ on: kit.state.timer_on, round: kit.state.timer_round, resting: kit.state.timer_resting, over: kit.state.timer_over, spent: kit.state.timer_spent, end: kit.state.timer_end });
});

test("run: a timer event with an unknown id changes nothing", async () => {
  const { definition, app } = await compiled(plan("timer-rounds"));
  const started = handle(request(definition.initialState, press("timer_start")), app, T0);
  // The time is past the end of the work phase: a timer event of timer_bar would start the rest.
  for (const timerId of ["nothing", "toString", "timer_start"]) expect(handle(request(started.state, timer(timerId)), app, T0 + 41000)).toEqual({ state: started.state });
});

test("run: a late timer event after a stop changes nothing", async () => {
  const { definition, app } = await compiled(plan("timer-rounds"));
  const started = handle(request(definition.initialState, press("timer_start")), app, T0);
  const paused = handle(request(started.state, press("timer_pause")), app, T0 + 5000);
  expect(handle(request(paused.state, timer("timer_bar")), app, T0 + 50000)).toEqual({ state: paused.state });
});

test("run: open moves a timer that runs to the clock of now, and a timer that stands stays", async () => {
  const { definition, app } = await compiled(plan("timer-rounds"));
  const started = handle(request(definition.initialState, press("timer_start")), app, T0);
  const opened = handle(request(started.state, { type: "open" }), app, T0 + 45000);
  expect([opened.state.timer, opened.state.timer_s.resting, opened.state.timer_s.spent, opened.timers]).toEqual([1, true, 5, [{ id: "timer_bar", intervalMs: 1000 }]]);
  expect(handle(request(definition.initialState, { type: "open" }), app, T0)).toEqual({ state: definition.initialState, surfaces: { timer_bar: { commands: bar(0, "accent") } }, timers: [] });
  // A surface event of the host, such as start, changes nothing.
  expect(handle(request(started.state, { type: "start", surfaceId: "timer_bar" }), app, T0 + 1000)).toEqual({ state: started.state });
});

test("run: the timers of two timers that run are one list with distinct ids", async () => {
  const { definition, app } = await compiled('app("Two", lang="en")\nneed a = timer("A", 30, rounds=2)\nneed b = timer("B", 60)\n');
  expect(definition.surfaces.map((surface) => surface.id)).toEqual(["a_bar", "b_bar"]);
  const one = handle(request(definition.initialState, press("a_start")), app, T0);
  const both = handle(request(one.state, press("b_start")), app, T0 + 1000);
  expect(both.timers).toEqual([{ id: "a_bar", intervalMs: 1000 }, { id: "b_bar", intervalMs: 1000 }]);
  // A timer event of one moves both: each one keeps its own end. The names of the second need's fields are not their wish.
  const ticked = handle(request(both.state, timer("b_bar")), app, T0 + 31000), [object, field] = new Map(app.fields).get("b_spent").split(".");
  expect([ticked.state.a, ticked.state.a_s.round, ticked.state[object][field], ticked.timers.map((each) => each.id)]).toEqual([1, 2, 30, ["a_bar", "b_bar"]]);
});

test("emit: a fifth timer on a surface is a fault of the app", async () => {
  const needs = (count) => Array.from({ length: count }, (_, index) => `need t${index + 1} = timer("T ${index + 1}", 30, rounds=2)`).join("\n");
  expect((await compiled(`app("Four", lang="en")\n${needs(4)}`)).definition.surfaces).toHaveLength(4);
  const five = await compiled(`app("Five", lang="en")\n${needs(5)}`);
  expect(five.definition).toBeNull();
  expect(five.faults).toEqual(["app: the screen has 5 timers with rounds or with no alert, and Alphy draws at most 4. Use fewer timers"]);
});

test("run: the short plan of the device test ends after its second rest", async () => {
  const { definition, app } = await compiled(SHORT);
  expect(definition.surfaces).toEqual([{ id: "t_bar", width: 320, height: 64 }]);
  let { state } = handle(request(definition.initialState, press("t_start")), app, T0);
  const seen = [];
  // The device waits until 300 ms after the end of each phase in the state of the reply before it.
  for (let step = 0; step < 4; step++) {
    const reply = handle(request(state, timer("t_bar")), app, state.t_s.end + 300);
    state = reply.state;
    seen.push([state.t_s.over ? "over" : state.t_s.resting ? "rest" : "work", state.t_s.round, state.t, reply.timers.length]);
  }
  expect(seen).toEqual([["rest", 1, 1, 1], ["work", 2, 1, 1], ["rest", 2, 2, 1], ["over", 2, 2, 0]]);
});
