// Timers: the host's own countdown (Alphy's foreground timer) and a timer with rounds on a surface that the run-time draws.

import { expect, test } from "bun:test";
import { App, settings } from "../src/core.js";
import { report } from "../src/check.js";
import { emit } from "../src/emit.js";
import { handle } from "../src/run.js";
import { T0, clocked, faultsOf, fixture, gateEach, plan, planned } from "./helpers.js";

gateEach("smoke");

const every = (node) => [node, ...(node.children ?? []).flatMap(every)];
const compiled = async (text) => { const file = await planned(text); return { file, ...emit(file) }; };
const request = (state, event) => ({ artifactId: "kit-test", revision: 1, state, event });
const press = (actionId, value = null) => ({ type: "action", actionId, value });
const timer = (timerId) => ({ type: "timer", timerId });
const duration = (ui) => every(ui).find((node) => node.type === "timer").foreground.durationMs;
/** timer-rounds.plan as the run-time and as the kit's App, with send: a press or a timer event at a time in ms after T0 (helpers.js clocked). */
const rounds = () => clocked(plan("timer-rounds"));
/** Two work seconds and two rest seconds in 2 rounds. The device test timerRoundsPassesParseAndSmoke has the same plan. */
const SHORT = 'app("Short rounds", lang="en")\nneed t = timer("Short", 2, rounds=2, rest=2)\nneed done = progress("Rounds done", $/t, 2)\n';

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
  // A duration that is not finite at run time, 60 / 0, runs 1 second.
  const cups = await compiled('app("T", lang="en")\nneed n = input("number", "Cups", 2, min=0, max=10)\nneed t = timer("T", 60 / $/n, alert=true)');
  expect(duration(cups.definition.ui)).toBe(30000);
  expect(duration(handle(request(cups.definition.initialState, press("n_field", 0)), cups.app).ui)).toBe(1000);
});

test("emit: timer-rounds.plan gives a surface, a canvas, and no foreground timer", async () => {
  const { definition, faults } = await compiled(plan("timer-rounds"));
  expect(faults).toEqual([]);
  expect(definition.surfaces).toEqual([{ id: "timer_bar", width: 1024, height: 64 }]);
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

/**
 * The bar of a phase: the track in the muted tone of the theme, then the share of the phase that passed in its tone. Each fills the
 * surface of 1024 x 64 (Task 11b-2b: a 16-high bar in a 320 x 64 surface left empty bands above and under it).
 */
const bar = (width, color) => [{ type: "roundRect", x: 0, y: 0, width: 1024, height: 64, radius: 32, color: "muted" }, { type: "circle", x: 960, y: 32, radius: 10, color },
  { type: "roundRect", x: 0, y: 0, width, height: 64, radius: 32, color }];

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
  expect(paused.surfaces.timer_bar.commands).toEqual(bar(256, "accent"));
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
  expect(rest.surfaces.timer_bar.commands).toEqual(bar(26, "success"));
});

test("run: the count of finished rounds follows", async () => {
  const { kit, send } = await rounds();
  send(0, press("timer_start"));
  // One timer event 10 s before the end of each rest phase: round r has 40 s of work and 20 s of rest, and round 8 has no rest (K1 item 6).
  for (let round = 1; round <= 7; round++) {
    const reply = send((60 * round - 10) * 1000, timer("timer_bar")), state = reply.state;
    expect([state.timer, state.timer_s.round, state.timer_s.resting]).toEqual([round, round, true]);
    expect(state.timer).toBe(kit.state.timer);
    // The figure of the round and the text of the ring follow the count.
    expect(Object.values(state.d)).toEqual(expect.arrayContaining([kit.state.runde_text, kit.state.ring_text]));
    expect(kit.state.ring_text).toBe(`${round} / 8`);
    expect(reply.timers).toEqual([{ id: "timer_bar", intervalMs: 1000 }]);
  }
  // 8 x 40 + 7 x 20 = 460 s.
  expect(send(459000, timer("timer_bar")).state.timer_s).toMatchObject({ on: true, round: 8, resting: false });
  const over = send(460000, timer("timer_bar"));
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

test("emit: a fifth timer on a surface is a fault of its need line", async () => {
  const needs = (count) => Array.from({ length: count }, (_, index) => `need t${index + 1} = timer("T ${index + 1}", 30, rounds=2)`).join("\n");
  expect((await compiled(`app("Four", lang="en")\n${needs(4)}`)).definition.surfaces).toHaveLength(4);
  const five = await compiled(`app("Five", lang="en")\n${needs(5)}`);
  expect(five.definition).toBeNull();
  expect(five.faults).toEqual(["need t5: the app draws 5 surfaces, and Alphy draws at most 4: one for each drawing and each timer with rounds or with no alert. Use fewer"]);
});

test("run: the short plan of the device test ends after its second work phase, with no rest after its last round (K1 item 6)", async () => {
  const { definition, app } = await compiled(SHORT);
  expect(definition.surfaces).toEqual([{ id: "t_bar", width: 1024, height: 64 }]);
  let { state } = handle(request(definition.initialState, press("t_start")), app, T0);
  const seen = [];
  // The device waits until 300 ms after the end of each phase in the state of the reply before it.
  for (let step = 0; step < 3; step++) {
    const reply = handle(request(state, timer("t_bar")), app, state.t_s.end + 300);
    state = reply.state;
    seen.push([state.t_s.over ? "over" : state.t_s.resting ? "rest" : "work", state.t_s.round, state.t, reply.timers]);
  }
  const ticks = [{ id: "t_bar", intervalMs: 1000 }];
  expect(seen).toEqual([["rest", 1, 1, ticks], ["work", 2, 1, ticks], ["over", 2, 2, []]]);
});

test("run: a timer with rounds does not rest after its last round (K1 item 6): 5 rounds of 45 s with 15 s of rest between them end at 285 s", async () => {
  const { kit, send } = await clocked(fixture("model/e-plank-fr"));
  send(0, press("planche_start"));
  // 4 rests: the fourth one ends at 4 x 60 = 240 s, and the fifth work phase ends at 285 s.
  expect(send(230000, timer("planche_bar")).state.planche_s).toMatchObject({ on: true, round: 4, resting: true });
  expect(send(284000, timer("planche_bar")).state.planche_s).toMatchObject({ on: true, round: 5, resting: false, over: false });
  const over = send(285000, timer("planche_bar"));
  expect([over.state.planche, over.state.planche_s.over, over.state.planche_s.on, over.timers]).toEqual([5, true, false, []]);
  // The end shows the full bar of the last work phase and the done text.
  expect([kit.state.planche_left, kit.find("planche_doneNote").props.visible]).toEqual([0, true]);
});

/**
 * Each change of the phase of the timer need `name`, at the times of `changes` in s after its start, as
 * "time: phase round count > phase round count": the state 0.5 s before the change, and 0.5 s after it. The run-time and the kit's App agree at each time.
 */
const changesOf = async (text, name, changes) => {
  const { kit, send } = await clocked(text), at = (seconds) => {
    const state = send(seconds * 1000, timer(`${name}_bar`)).state, phase = state[`${name}_s`];
    expect([state[name], phase.round, phase.resting, phase.over]).toEqual(["", "_round", "_resting", "_over"].map((part) => kit.state[name + part]));
    return `${phase.over ? "over" : phase.resting ? "rest" : "work"} ${phase.round} ${state[name]}`;
  };
  send(0, press(`${name}_start`));
  return changes.map((seconds) => `${seconds}: ${at(seconds - 0.5)} > ${at(seconds + 0.5)}`);
};

test("run (K2): a timer of one round with a rest runs the work phase and then the rest phase: 4 s and 6 s end at 10 s", async () => {
  for (const line of ['timer("Breath", 4, rounds=1, rest=6)', 'timer("Breath", 4, rest=6)']) {
    expect([line, await changesOf(`app("T", lang="en")\nneed t = ${line}`, "t", [4, 10])]).toEqual([line, ["4: work 1 0 > rest 1 1", "10: rest 1 1 > over 1 1"]]);
  }
});

test("run (K2): a timer of more rounds rests only between two rounds: 5 rounds of 45 s with 15 s of rest end at 285 s", async () => {
  expect(await changesOf('app("T", lang="en")\nneed t = timer("Plank", 45, rounds=5, rest=15)', "t", [45, 60, 105, 120, 165, 180, 225, 240, 285])).toEqual([
    "45: work 1 0 > rest 1 1", "60: rest 1 1 > work 2 1", "105: work 2 1 > rest 2 2", "120: rest 2 2 > work 3 2", "165: work 3 2 > rest 3 3",
    "180: rest 3 3 > work 4 3", "225: work 4 3 > rest 4 4", "240: rest 4 4 > work 5 4", "285: work 5 4 > over 5 5"]);
});

test("run (K2): with cycle=true each round is the work phase and then the rest phase, also the last one: 5 rounds of 4 s and 6 s end at 50 s", async () => {
  expect(await changesOf('app("T", lang="en")\nneed t = timer("Breath", 4, rounds=5, rest=6, cycle=true)', "t", [4, 10, 14, 20, 24, 30, 34, 40, 44, 50])).toEqual([
    "4: work 1 0 > rest 1 1", "10: rest 1 1 > work 2 1", "14: work 2 1 > rest 2 2", "20: rest 2 2 > work 3 2", "24: work 3 2 > rest 3 3",
    "30: rest 3 3 > work 4 3", "34: work 4 3 > rest 4 4", "40: rest 4 4 > work 5 4", "44: work 5 4 > rest 5 5", "50: rest 5 5 > over 5 5"]);
  // cycle=false is the timer with a rest between two rounds.
  expect((await changesOf('app("T", lang="en")\nneed t = timer("T", 4, rounds=2, rest=6, cycle=false)', "t", [14])).at(-1)).toBe("14: work 2 1 > over 2 2");
});

test("run (K2): the saved model plan d-breathing runs its exhale of 6 s after its inhale of 4 s, and ends at 10 s", async () => {
  expect(await changesOf(fixture("model/d-breathing"), "breath", [4, 10])).toEqual(["4: work 1 0 > rest 1 1", "10: rest 1 1 > over 1 1"]);
  const { kit, send } = await clocked(fixture("model/d-breathing"));
  send(0, press("breath_start"));
  send(4500, timer("breath_bar"));
  expect([kit.find("breath_phaseText").props.text, kit.state.breath_left]).toEqual(["Выдох 1 / 1", 6]);
  // At the end the bar of the exhale is full, and the done text shows.
  send(10000, timer("breath_bar"));
  expect([kit.find("breath_phaseText").props.text, kit.state.breath_left, kit.find("breath_doneNote").props.visible]).toEqual(["Выдох 1 / 1", 0, true]);
});

test("check (K2): cycle= is true or false in the line, as each other flag of a part", async () => {
  const line = (value) => `need t = timer("T", 4, rounds=5, rest=6, cycle=${value})`;
  expect(await faultsOf(line('"yes"'))).toEqual([`line: need t: cycle= needs true or false, and "yes" is neither: ${line('"yes"')}`]);
  expect(await faultsOf("$/on = true", line("$/on"))).toEqual([`line: need t: cycle= needs true or false, and $/on is a formula. The part reads it once when it builds the screen: ${line("$/on")}`]);
  expect(await faultsOf(line("true"))).toEqual([]);
});

test("emit: a Timer in a row of a list is a fault of its line", async () => {
  const { definition, faults } = await compiled('app("Teas", lang="en")\n$/teas = [{name: "Green", s: 120}]\nroot = Column([teas])\nteas = List($/teas, row)\nrow = Row([Text($name), brew])\nbrew = Timer("Brew", $s)');
  expect(definition).toBeNull();
  expect(faults).toEqual(["brew: a Timer in a row of a list is not built yet"]);
});

test("run: a clock that goes back keeps the end of the phase, and the time spent is never below 0", async () => {
  const { kit, send } = await rounds();
  send(0, press("timer_start"));
  const back = send(-5000, timer("timer_bar"));
  expect(back.state.timer_s).toEqual({ on: true, round: 1, resting: false, over: false, spent: 0, end: T0 + 40000 });
  expect([kit.state.timer_spent, kit.state.timer_end]).toEqual([0, T0 + 40000]);
  // The work phase still ends at its end.
  expect(send(39000, timer("timer_bar")).state.timer_s).toMatchObject({ resting: false, spent: 39 });
  expect(send(40500, timer("timer_bar")).state.timer_s).toMatchObject({ resting: true, spent: 0.5, end: T0 + 60000 });
});

test("run: a phase length that changes while the timer runs keeps the end of the phase", async () => {
  const { file, definition, app } = await compiled('app("T", lang="en")\nneed work = input("number", "Work", 40, min=10, max=100)\nneed t = timer("T", $/work, rounds=2, rest=10)');
  const kit = new App(file);
  settings.now = () => T0;
  kit.press("t_start");
  const started = handle(request(definition.initialState, press("t_start")), app, T0);
  settings.now = () => T0 + 10000;
  kit.input("work_field", 60);
  // 10 s of 40 passed. The end stays, so the time spent of the longer phase is 30 s: the bar jumps from a quarter to a half.
  const longer = handle(request(started.state, press("work_field", 60)), app, T0 + 10000);
  expect(longer.state.t_s).toMatchObject({ on: true, spent: 30, end: T0 + 40000 });
  expect([kit.state.t_spent, kit.state.t_end]).toEqual([30, T0 + 40000]);
});
