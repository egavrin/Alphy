// The input part in the look of the baseline apps (Task 11b-2a, D): options as a map, an icon of the field, and a range of two numbers.

import { expect, test } from "bun:test";
import { report, smoke } from "../src/check.js";
import { built, every, planned, press } from "./helpers.js";


test("inputs: a choice takes options as a map of a value to its label, and the state keeps the value", async () => {
  const { definition, app } = await built('app("Bici", lang="it")', 'need kind = input("choice", "Tipo", "road", options={all: "Tutte", road: "Strada", city: "Città"})');
  const [field] = definition.ui.children[0].children;
  expect(field).toMatchObject({ type: "segmented", text: "Tipo", bind: "kind", options: [{ text: "Tutte", value: "all" }, { text: "Strada", value: "road" }, { text: "Città", value: "city" }] });
  expect(press(definition.initialState, app, field.action, "city").state.kind).toBe("city");
  // Five options in a map: the part writes a Select, and the run-time makes six options or fewer a segmented control. A multi-choice takes a map too.
  const many = await built('app("T", lang="en")', 'need size = input("choice", "Size", "s", options={xs: "XS", s: "S", m: "M", l: "L", xl: "XL"})',
    'need extras = input("choices", "Extras", [], options={bell: "Bell", lamp: "Lamp"})');
  expect(many.definition.ui.children[0].children.map((node) => [node.type, node.options.map((option) => option.text).join(" ")])).toEqual([["segmented", "XS S M L XL"], ["multiChoice", "Bell Lamp"]]);
});

test("inputs: icon= is the icon of the field of each input that takes one", async () => {
  const { definition } = await built('app("T", lang="en")', 'need name = input("text", "Name", "", icon="Pencil")', 'need n = input("number", "N", 1, icon="Drop")',
    'need day = input("date", "Day", "2026-11-20", icon="Calendar")', 'need at = input("time", "At", "08:00", icon="Clock")',
    'need city = input("choice", "City", "a", options=["a", "b", "c", "d", "e", "f", "g"], icon="Pin")');
  expect(definition.ui.children[0].children.map((node) => [node.type, node.icon])).toEqual([["input", "Pencil"], ["numberInput", "Drop"], ["dateInput", "Calendar"], ["timeInput", "Clock"], ["select", "Pin"]]);
});

test("inputs: a range of two numbers is one range slider whose state is the low and the high number", async () => {
  // AppFileProofTest.aRangeAMapOfOptionsAndAnIconOfAFieldPassParseAndSmoke uses the same plan.
  const { definition, app } = await built('app("Bikes", lang="en")', 'need price = input("range", "Price", [100, 1500], min=0, max=2000, step=50)',
    'need shown = figure("count", "Shown", count([300, 900, 1800], $it >= $/price[0] && $it <= $/price[1]), example=[{price: [0, 2000]}, 3])',
    'need kind = input("choice", "Type", "road", options={all: "All", road: "Road", city: "City"})', 'need name = input("text", "Name", "", icon="Pencil")');
  const [slider] = every(definition.ui).filter((node) => node.type === "rangeSlider");
  expect(slider).toEqual({ type: "rangeSlider", text: "Price", bind: "price", action: "price_field", min: 0, max: 2000, step: 50 });
  expect(definition.stateSchema.price).toEqual({ type: "array", maxItems: 512, items: "number" });
  expect(definition.initialState.price).toEqual([100, 1500]);
  const moved = press(definition.initialState, app, "price_field", [500, 1000]);
  expect([moved.state.price, moved.state.d.shown_text]).toEqual([[500, 1000], "1"]);
  // A range out of order, out of the bounds, or of another length changes nothing.
  for (const wrong of [[1000, 500], [-50, 100], [100], "100"]) expect(press(moved.state, app, "price_field", wrong).state.price).toEqual([500, 1000]);
  // The smoke run moves the range to its bounds, as Alphy's smoke step does.
  const file = await planned('app("T", lang="en")\nneed price = input("range", "Price", [100, 1500], min=0, max=2000)');
  expect(smoke(file).trace.some((line) => line.startsWith("set price_field: price [2] -> [2]"))).toBe(true);
  expect(report(file).faults).toEqual([]);
});
