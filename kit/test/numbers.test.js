// The numbers that a format function prints for a person, in the language of the app (Task 11b-2a, B).

import { expect, test } from "bun:test";
import { AppFile, parseExpression } from "../src/dsl.js";
import { App } from "../src/core.js";
import { emit } from "../src/emit.js";
import { compileAppFile } from "../src/compile.js";
import { handle } from "../src/run.js";
import { every, fixture, planned } from "./helpers.js";

/** The value of a formula in an app of a language. */
const printed = (lang, formula) => new App(new AppFile().feedText(lang ? `app("T", lang="${lang}")` : 'app("T")')).value(parseExpression(formula), {});
const NUMBERS = [1234.5, 2000, 20000, 0.5, -1234.5];
const NB = "\u00a0";

test("numbers: amount() prints the decimal sign and the digit groups of the language of the app", () => {
  const expected = {
    en: ["1,234.5", "2,000", "20,000", "0.5", "-1,234.5"],
    ko: ["1,234.5", "2,000", "20,000", "0.5", "-1,234.5"],
    // pl, es, and pt: a whole part of four digits has no group.
    pl: ["1234,5", "2000", `20${NB}000`, "0,5", "-1234,5"],
    ru: [`1${NB}234,5`, `2${NB}000`, `20${NB}000`, "0,5", `-1${NB}234,5`],
    es: ["1234,5", "2000", "20.000", "0,5", "-1234,5"],
    de: ["1.234,5", "2.000", "20.000", "0,5", "-1.234,5"],
  };
  for (const [lang, texts] of Object.entries(expected)) expect([lang, NUMBERS.map((x) => printed(lang, `amount(${x}, 2)`))]).toEqual([lang, texts]);
  // An app with no language, and a language that the table does not have, print as en.
  expect(NUMBERS.map((x) => printed(undefined, `amount(${x}, 2)`))).toEqual(expected.en);
  expect(NUMBERS.map((x) => printed("sv", `amount(${x}, 2)`))).toEqual(expected.en);
  // amount rounds to at most n decimals, and a count to none. A rounded zero has no minus sign.
  expect([printed("de", "amount(2.675, 2)"), printed("de", "amount(7.5, 0)"), printed("en", "amount(-0.001, 2)")]).toEqual(["2,68", "8", "0"]);
});

test("numbers: money(), percent(), and fixed() keep their decimals and print them in the language of the app", () => {
  expect(NUMBERS.map((x) => printed("de", `money(${x})`))).toEqual(["1.234,50", "2.000,00", "20.000,00", "0,50", "-1.234,50"]);
  expect(NUMBERS.map((x) => printed("pl", `money(${x}, "€")`))).toEqual(["€1234,50", "€2000,00", `€20${NB}000,00`, "€0,50", "€-1234,50"]);
  expect([printed("en", "money(1234.5)"), printed("ru", "percent(12.5, 1)"), printed("ko", "percent(20000)"), printed("es", "fixed(20000, 1)")])
    .toEqual(["1,234.50", "12,5%", "20,000%", "20.000,0"]);
  // A number that is not finite prints as JavaScript prints it, so a fault stays visible.
  expect([printed("de", "amount(1 / 0, 2)"), printed("de", "money(0 / 0)")]).toEqual(["Infinity", "NaN"]);
});

test("numbers (L1 item 5): money() in ja and ko prints no decimals, as won and yen have no minor unit, and decimals= of a money figure keeps its decimals", async () => {
  expect(NUMBERS.map((x) => printed("ko", `money(${x}, "₩")`))).toEqual(["₩1,235", "₩2,000", "₩20,000", "₩1", "₩-1,235"]);
  expect([printed("ja", "money(4000)"), printed("ja", "money(4000.4)"), printed("zh", "money(4000)"), printed("tr", "money(4000)"), printed("uk", "money(4000)")])
    .toEqual(["4,000", "4,000", "4,000.00", "4.000,00", `4${NB}000,00`]);
  // e-cafe-ko showed "4,000.00 원" (L1 item 5).
  const { definition } = emit(await planned(fixture("model/e-cafe-ko")));
  expect(every(definition.ui).filter((node) => node.type === "metric").map((node) => definition.initialState.d[node.bind.slice(2)])).toEqual(["4,000 원"]);
  const kept = emit(await planned(fixture("model/e-cafe-ko").replace('unit="원")', 'unit="원", decimals=2)'))).definition;
  expect(every(kept.ui).filter((node) => node.type === "metric").map((node) => kept.initialState.d[node.bind.slice(2)])).toEqual(["4,000.00 원"]);
});

test("numbers: a plain number that a text or a metric shows does not change", () => {
  // B.1: a year must stay "2019".
  expect([printed("de", "text(2019)"), printed("en", "text(1234.5)"), printed("pl", "text(20000)")]).toEqual(["2019", "1234.5", "20000"]);
});

test("numbers: the goal of look-water-ko is one state value, and each metric prints it with its digit groups", async () => {
  const text = fixture("look-water-ko"), { definition, app } = emit(await planned(text));
  // B.3: no probe metric, and no fixed text of the goal beside a computed one.
  expect([text.includes('"Plain"'), text.includes('"2,000 ml"'), text.match(/\b2000\b/g)]).toEqual([false, false, ["2000"]]);
  const shown = (state) => every(definition.ui).filter((node) => node.type === "metric").map((node) => [node.text, state.d[node.bind.slice(2)]]);
  expect(shown(definition.initialState)).toEqual([["오늘 마신 양", "0 ml"], ["남은 양", "2,000 ml"], ["하루 목표", "2,000 ml"]]);
  const reply = handle({ artifactId: "k", revision: 1, state: definition.initialState, event: { type: "action", actionId: "total", value: 1500 } }, app);
  expect(shown(reply.state)).toEqual([["오늘 마신 양", "1,500 ml"], ["남은 양", "500 ml"], ["하루 목표", "2,000 ml"]]);
});

test("numbers: a figure prints the same text at compile time and in the reply to an event", async () => {
  for (const [lang, start, after] of [["pl", "2469", `40${NB}000`], ["de", "2.469", "40.000"]]) {
    // The plan of the device test aFigurePrintsTheNumbersOfItsLanguageAtCompileAndInTheSandbox.
    const text = [`app("T", lang="${lang}")`, 'need x = input("number", "X", 1234.5)', 'need y = figure("number", "Y", $/x * 2, example=[{x: 10}, 20])',
      'need m = figure("money", "M", $/x, example=[{x: 10}, 10])'].join("\n");
    expect((await compileAppFile(text)).faults).toEqual([]);
    const { definition, app } = emit(await planned(text));
    expect(definition.initialState.d).toMatchObject({ y_text: start, m_text: lang === "pl" ? "1234,50" : "1.234,50" });
    const reply = handle({ artifactId: "k", revision: 1, state: definition.initialState, event: { type: "action", actionId: "x_field", value: 20000 } }, app);
    expect([lang, reply.state.d.y_text, reply.state.d.m_text]).toEqual([lang, after, lang === "pl" ? `20${NB}000,00` : "20.000,00"]);
  }
});
