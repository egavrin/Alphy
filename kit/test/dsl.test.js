// Lines and expressions: parsing, printing, and delete lines. Ported from the prototype's test.js.

import { test } from "bun:test";
import { AppFile, parseExpression, show } from "../src/dsl.js";
import { report } from "../src/check.js";
import { expand } from "../src/parts.js";
import { gateEach, planned, same } from "./helpers.js";

gateEach("checks");

test("an index prints and parses", () => {
  same(show(parseExpression("$/rows[($/i + 1)]/name")), "$/rows[($/i + 1)].name", "an index prints and parses");
});

test("delete need NAME does the same as delete NAME", async () => {
  // f0 trip-dates-1, repair step 3: "line 1: line not understood: delete need day".
  const dropped = await planned('app("T")\nneed day = input("choice", "Day", "Saturday", options=["Saturday", "Sunday"])\nneed title = input("text", "Activity", "Hike")');
  dropped.beginStep();
  dropped.feed("delete need day", 1);
  await expand(dropped);
  same([dropped.faults, dropped.get("need:day") === undefined, dropped.get("root").raw, report(dropped).faults], [[], true, "root = Column([title_field])", []], "delete need NAME removes the need");
  same(new AppFile().feedText("delete need").faults.map((fault) => fault.message), ["nothing named need"], "delete need with no name still means a thing named need");
});

test("a field name with a space prints with its quotes", () => {
  same(show(parseExpression(show(parseExpression('{"my field": "text", ok: 1}')))), '{"my field": "text", ok: 1}', "a field name with a space prints with its quotes");
});
