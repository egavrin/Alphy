// The five mock plans with parts (Task 11b-2a) and the two look fixtures of the deck and the guide (Task 11b-2b, round before the review):
// their check lines pass, and one event of the main action of each one gives the expected reply.

import { expect, test } from "bun:test";
import { report } from "../src/check.js";
import { emit } from "../src/emit.js";
import { every, fixture, live, planned, press } from "./helpers.js";

/** The report and the compiled app of a mock plan. */
const mock = async (name) => {
  const file = await planned(fixture(name)), result = report(file), { definition, app } = emit(file);
  return { result, app, state: definition.initialState };
};

test("mocks: each mock plan and look fixture of parts has no fault, and each of its check lines passes", async () => {
  for (const name of ["mock-water-ko", "mock-units-pl", "mock-shopping-it", "mock-habits-uk", "mock-price-filter", "look-deck-es", "look-guide-es"]) {
    const { result } = await mock(name);
    expect([name, result.faults, result.checks.length > 0, result.checks.filter((check) => !check.pass)]).toEqual([name, [], true, []]);
  }
});

test("mocks: a cup adds 250 ml to the hero, its bar, and the amount that is left", async () => {
  const { app, state } = await mock("mock-water-ko");
  const { d } = press(state, app, "ml_plus").state;
  expect([d.today_text, d.today_view_2_value, d.left_text]).toEqual(["250 ml", 12.5, "1,750 ml"]);
});

test("mocks: the daily goal of water is in one need, so a new goal moves the bar and the amount that is left", async () => {
  const file = await planned(fixture("mock-water-ko").replace('figure("number", "하루 목표", 2000', 'figure("number", "하루 목표", 3000')), { definition, app } = emit(file);
  const { d } = press(definition.initialState, app, "ml_plus").state;
  expect([d.today_view_2_value, d.left_text, d.goal_text]).toEqual([(250 / 3000) * 100, "2,750 ml", "3,000 ml"]);
});

test("mocks: a change of the product and then of the quantity converts each unit again", async () => {
  const { app, state } = await mock("mock-units-pl");
  const sugar = press(state, app, "produkt_field", "cukier").state;
  expect([sugar.d.gramy_view_0_value, sugar.d.gramy_text]).toEqual(["🍬", "200"]);
  const { d } = press(sugar, app, "ilosc_field", 2).state;
  expect([d.gramy_text, d.ml_text, d.szklanki_text, d.lyzki_text]).toEqual(["400", "500", "2", "33,3"]);
});

test("mocks: an added product is in the list in the order of its aisle, and the hero counts it", async () => {
  const { app, state } = await mock("mock-shopping-it");
  const add = (from, name, aisle) => press(press(live(from, app, "prodotti_new_nome", name).state, app, "prodotti_new_corsia", aisle).state, app, "prodotti_add").state;
  const bread = add(state, "Pane", "Panetteria");
  expect([bread.prodotti_list.map((row) => row.nome), bread.d.left_text, bread.prodotti_s.new_nome]).toEqual([["Pane"], "1", ""]);
  const both = add(bread, "Latte", "Latticini");
  expect([both.prodotti_list.map((row) => row.nome), both.d.left_text]).toEqual([["Latte", "Pane"], "2"]);
});

test("mocks: a day mark counts in the week ring, the days of the habit, and its streak", async () => {
  const { app, state } = await mock("mock-habits-uk");
  expect([state.d.week_text, state.habits_list[0].habits_days_value, state.habits_list[0].habits_streak_value]).toEqual(["3 / 21", "2 з 7", "🔥 2"]);
  const marked = press(state, app, "habits_sr", { key: "r1", value: true }).state;
  // The ring draws its value as a percent of the goal: 4 of 21.
  expect([Math.round(marked.d.week_view_0_value), marked.d.week_text, marked.habits_list[0].habits_days_value, marked.habits_list[0].habits_streak_value]).toEqual([19, "4 / 21", "3 з 7", "🔥 3"]);
});

test("mocks: the price range, the type, and the order change the hero count and the order of the list", async () => {
  const { app, state } = await mock("mock-price-filter");
  const names = (at) => at.bikes_list.map((row) => row.name);
  // total="of" (C.8): the hero shows the matching bikes of all 12, as the baseline does.
  expect([state.d.bikes_hero_1_value, names(state).at(0), names(state).at(-1)]).toEqual(["12 of 12", "Retrospec Beaumont", "Cube Kathmandu"]);
  const ranged = press(state, app, "price_field", [500, 1000]).state;
  expect([ranged.d.bikes_hero_1_value, names(ranged)]).toEqual(["5 of 12", ["Trek Marlin 6", "Specialized Sirrus X 2", "Marin Bobcat Trail", "Fuji Sportif 2.3", "Cannondale CAAD Optimo"]]);
  const road = press(press(ranged, app, "type_field", "road").state, app, "order_field", "desc").state;
  expect([road.d.bikes_hero_1_value, names(road)]).toEqual(["2 of 12", ["Cannondale CAAD Optimo", "Fuji Sportif 2.3"]]);
});

test("mocks: the shopping list gives its add fields their icons, and the streak of a habit is an accent badge", async () => {
  const shopping = await planned(fixture("mock-shopping-it")), habits = await planned(fixture("mock-habits-uk"));
  expect([shopping.get("prodotti_new_nome").raw, shopping.get("prodotti_new_corsia").raw.endsWith(', icon="Tag")'), habits.get("habits_streak").raw.endsWith(', tone="accent")')])
    .toEqual(['prodotti_new_nome = TextField("Prodotto", $/prodotti_new_nome, icon="Cart")', true, true]);
});

test("mocks: Ver traducción shows the back of the deck, and La sabía counts one, marks the word as known, and shows the next card", async () => {
  const { app, state } = await mock("look-deck-es");
  const shown = press(state, app, "tarjetas_show").state;
  expect([state.tarjetas_s.open, shown.tarjetas_s.open, shown.d.tarjetas_back_text]).toEqual([false, true, "the apple"]);
  const known = press(shown, app, "tarjetas_yesButton").state;
  expect([known.tarjetas_s, known.d.tarjetas_front_text, known.palabras.map((row) => row.sabida)]).toEqual([{ i: 1, open: false, yes: 1, no: 0 }, "el perro", [true, false, false, false, false]]);
});

test("mocks: Siguiente shows the second step of the guide, and the right answer shows the success notice", async () => {
  const { app, state } = await mock("look-guide-es");
  expect([state.d.cafe_text, press(state, app, "pasos_next").state.pasos_s.step]).toEqual(["30 g", 1]);
  const reply = press(state, app, "prueba_opt1"), notice = every(reply.ui).find((node) => node.type === "banner");
  expect([reply.state.prueba_s.answer, notice.style, reply.state.v.prueba_result_visible, reply.state.d.prueba_result_text]).toEqual(["92–96 °C", { tone: "success" }, true, "¡Correcto! Así el café no se quema."]);
});
