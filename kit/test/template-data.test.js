// The data family (block 2 plan, stage 5): the forms compare, table, chart, and nodata, their caps, the mark, the stream, the merge by name,
// and the host check of decision 3 (template.js checkData through compileStep).

import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { compileAppFile, compileStep, previewStep } from "../src/compile.js";
import { checkData, mergeTemplate } from "../src/template.js";
import { BUDGET } from "../src/form-base.js";
import { DATA_BUDGET, numbersIn } from "../src/form-data.js";
import { built, press, valueAt } from "./helpers.js";

const template = (name) => readFileSync(new URL(`./fixtures/template/${name}.plan`, import.meta.url), "utf8");
const step = (input) => compileStep({ read: true, previous: null, cap: 65536, plan: null, ...input });
/** The text with the line NAME in place of the line of that name, or after the last line when the text has none. */
const put = (text, name, line) => (new RegExp(`^${name} = .*$`, "m").test(text) ? text.replace(new RegExp(`^${name} = .*$`, "m"), line) : text + line + "\n");
const named = (faults, name) => faults.filter((fault) => fault.startsWith(`${name}: `));
/** Each node of a ui tree for which test is true. */
const nodes = (node, test) => (!node || typeof node !== "object" ? [] : [...(test(node) ? [node] : []), ...(node.children ?? []).flatMap((child) => nodes(child, test))]);
const main = (ui) => nodes(ui, (node) => node.type === "table" || node.type === "chart")[0];
const CAPS = ["compare-caps", "table-caps", "chart-caps", "nodata-caps"];

test("each data form compiles clean from its file at its caps, and keeps the file", async () => {
  for (const name of CAPS) {
    const text = template(name), result = await compileAppFile(text);
    expect([name, result.faults]).toEqual([name, []]);
    expect([name, result.template]).toEqual([name, name.split("-")[0]]);
    // A nodata file with no say gets the first sentence of its card as its narration (D5), so the voice says the reason.
    expect([name, result.kept]).toEqual([name, name === "nodata-caps" ? text + 'say = "Источники не дают счёта этого матча, и я не стану угадывать."\n' : text]);
  }
});

test("a table at its caps shows 12 rows in a box of 8, and the mark of a row and of a cell", async () => {
  const { definition } = await compileAppFile(template("table-caps")), table = main(definition.ui);
  expect([table.type, table.columns.length, table.visibleRows]).toEqual(["table", DATA_BUDGET.columns, DATA_BUDGET.visibleRows]);
  const rows = valueAt(definition.initialState, table.bind) ?? definition.initialState[table.bind];
  expect(rows).toHaveLength(DATA_BUDGET.tableRows);
  expect([valueAt(definition.initialState, table.selectedKeyBind), table.markColumn]).toEqual(["Марс четвёртая п", "c2"]);
});

test("a line over a cap is a fault with its name", async () => {
  const compare = template("compare-caps"), table = template("table-caps"), chart = template("chart-caps");
  const cases = [
    [put(compare, "r7", 'r7 = row("Лишний", ["1", "2", "3"])'), "r7", "has no line r7"],
    [put(compare, "t4", 't4 = thing("Марс", "🔴")'), "t4", "has no line t4"],
    [put(table, "r13", 'r13 = row("Лишний", ["1", "2", "3"])'), "r13", "has no line r13"],
    [put(chart, "p9", 'p9 = point("Лишний", 1)'), "p9", "has no line p9"],
    [put(table, "head", 'head = head(["А", "Б", "В", "Г", "Д"])'), "head", "its cap is 4" ],
    [put(compare, "r1", `r1 = row("Диаметр", ["${"1".repeat(DATA_BUDGET.value + 1)}", "2", "3"])`), "r1", `its cap is ${DATA_BUDGET.value}`],
    [put(compare, "r1", 'r1 = row("Диаметр", ["1", "2"])'), "r1", "has 2 values"],
  ];
  for (const [text, name, message] of cases) {
    const result = await compileAppFile(text);
    expect([name, named(result.faults, name).some((fault) => fault.includes(message.replace("its cap is 4", "4 columns")))]).toEqual([name, true]);
    // The other lines stay, so the form still shows. A table with no head has no columns, so it waits for the repair.
    expect([name, result.definition !== null]).toEqual([name, name !== "head"]);
  }
});

test("a long row label is cut at its cap, and the kept line holds the cut label", async () => {
  const long = "Расстояние до Солнца", text = put(template("table-caps"), "r1", `r1 = row("${long}", ["1", "2", "3"])`), result = await compileAppFile(text);
  const cut = [...long].slice(0, DATA_BUDGET.label - 1).join("") + "…";
  expect(named(result.faults, "r1")).toEqual([]);
  expect(result.kept).toContain(`r1 = row("${cut}", ["1", "2", "3"])`);
  expect([...cut]).toHaveLength(DATA_BUDGET.label);
});

test("the mark names a row of the form, each label is different, and a cell mark names a column of values", async () => {
  const compare = template("compare-caps"), table = template("table-caps");
  expect(named((await compileAppFile(put(compare, "control", 'control = mark("Нет такой")'))).faults, "control")[0]).toContain("names no row");
  expect(named((await compileAppFile(put(table, "control", 'control = mark("Марс четвёртая п", "Планета системыш")'))).faults, "control")[0]).toContain("names no column");
  expect(named((await compileAppFile(put(table, "r2", 'r2 = row("Меркурий первая ", ["1", "2", "3"])'))).faults, "r2")[0]).toContain("is the label of r1 too");
});

test("a repair replaces only the faulty lines, and a change by lines keeps the other lines", async () => {
  const text = template("compare-caps"), faulty = put(text, "r2", 'r2 = row("Расстояние", ["1", "2"])');
  const first = await step({ text: faulty });
  expect(named(first.result.faults, "r2")).toHaveLength(1);
  const repaired = await step({ text: 'r2 = row("Расстояние", ["1", "2", "3"])', plan: first.text });
  expect(repaired.result.faults).toEqual([]);
  expect(repaired.text.split("\n").filter((line) => !line.startsWith("r2 "))).toEqual(faulty.split("\n").filter((line) => !line.startsWith("r2 ")));
  const changed = mergeTemplate(template("chart-caps"), "p2 = point(\"Венера\", 225)\ndelete p8\n");
  expect(changed.split("\n").filter((line) => !/^p[28] /.test(line))).toEqual(template("chart-caps").split("\n").filter((line) => !/^p[28] /.test(line)));
  expect(changed).toContain('p2 = point("Венера", 225)');
  expect(changed).not.toContain("p8 =");
});

test("the stream of each data form draws line by line: each prefix gives a frame, and the main part grows by rows and points", async () => {
  for (const name of CAPS) {
    const lines = template(name).trim().split("\n"), holds = [];
    for (let k = 1; k <= lines.length; k++) {
      const frame = await previewStep({ text: lines.slice(0, k).join("\n") + "\n" });
      expect([name, k, frame.kind]).toEqual([name, k, "preview"]);
      holds.push(frame.frame.holds);
    }
    expect([name, holds[0], holds.at(-1)]).toEqual([name, "frame", "card"]);
  }
  const table = template("table-caps").trim().split("\n"), rows = async (k) => {
    const { frame } = await previewStep({ text: table.slice(0, k).join("\n") + "\n" });
    return frame.state[main(frame.ui).bind].length;
  };
  // The head comes at line 3: then each row line adds one row.
  expect([await rows(3), await rows(4), await rows(9)]).toEqual([0, 1, 6]);
});

test("a tap on a row or a point moves the mark, and a value that names no row changes nothing", async () => {
  for (const name of ["table-caps", "chart-caps"]) {
    const { definition, app } = await built((await compileAppFile(template(name))).expanded), node = main(definition.ui);
    const label = name === "table-caps" ? "Земля третья пла" : "Венера вторая пл", after = press(definition.initialState, app, node.action, label);
    expect([name, valueAt(after.state, node.selectedKeyBind)]).toEqual([name, label]);
    const wrong = press(definition.initialState, app, node.action, "Нет такой");
    expect([name, valueAt(wrong.state ?? definition.initialState, node.selectedKeyBind)]).toEqual([name, valueAt(definition.initialState, node.selectedKeyBind)]);
  }
});

test("the host check: a searched form whose numbers are all in the excerpts stays and shows its source", async () => {
  const excerpts = "Земля: диаметр 12 742 км, год 365,25 дня. Марс: диаметр 6779 км, год 687 дней. Счёт 2:1.";
  const text = 'compare("Земля и Марс", lang="ru")\nt1 = thing("Земля")\nt2 = thing("Марс")\nr1 = row("Диаметр, км", ["12742", "6 779"])\nr2 = row("Год, дней", ["365.25", "687"])\nr3 = row("Место", [1, 2])\ncard = card("Главное", "Марс меньше Земли.")\n';
  const { text: merged, result } = await step({ text, data: { excerpts, site: "ru.wikipedia.org", time: "22.11.2026 18:00" } });
  expect([result.rejected, result.faults]).toEqual([null, []]);
  expect(merged).toContain('source = source("ru.wikipedia.org", "22.11.2026 18:00")');
  expect(JSON.stringify(result.definition.ui)).toContain("ru.wikipedia.org · 22.11.2026 18:00");
});

test("the host check: one number that no excerpt holds gives nodata with the reason, and the voice says it", async () => {
  const excerpts = "Земля: диаметр 12 742 км. Марс: диаметр 6779 км.";
  const text = 'table("Планеты", lang="ru")\nhead = head(["Планета", "Диаметр, км", "Год, дней"])\nr1 = row("Земля", ["12 742", "365"])\nr2 = row("Марс", ["6 779", "687"])\ncard = card("Подпись", "Год Марса длиннее.")\n';
  const { text: merged, result } = await step({ text, data: { excerpts, site: "a.org", time: "t" } });
  expect(result.rejected).toBe("365");
  expect(result.template).toBe("nodata");
  expect(result.faults).toEqual([]);
  expect(merged).toContain("365");
  expect(result.kept).toMatch(/^say = ".*365.*"$/m);
  // A known-facts form runs no check: the step gets no data, and the result has no rejected value.
  const known = await step({ text });
  expect([known.result.template, "rejected" in known.result]).toEqual(["table", false]);
});

test("the host check holds in the stream too: a searched frame shows no value that no excerpt holds", async () => {
  const lines = ['chart("Год планет, дней", lang="ru", kind="bar")', 'p1 = point("Земля", 365)', 'p2 = point("Марс", 687)'], data = { excerpts: "Год Земли 365 дней.", site: "s.org", time: "t" };
  const first = await previewStep({ text: lines.slice(0, 2).join("\n") + "\n", data });
  expect(JSON.stringify(first.frame.ui)).toContain("s.org · t");
  const second = await previewStep({ text: lines.join("\n") + "\n", data });
  expect([main(second.frame.ui), JSON.stringify(second.frame.ui).includes("687")]).toEqual([undefined, true]);
  expect(JSON.stringify(second.frame.ui)).not.toContain('"categories"');
});

test("the host check reads numbers by their digits", () => {
  expect(numbersIn("12 742 км, 12,742, 12742 и 12 742")).toEqual(["12742", "12742", "12742", "12742"]);
  expect(numbersIn("3,5 и 3.50, счёт 2:1, −5 °C")).toEqual(["3.5", "3.5", "2", "1", "5"]);
  // A rank column that counts the rows is no data value, so it is not checked.
  const ranked = 'table("Лига", lang="ru")\nhead = head(["Клуб", "Место", "Очки"])\nr1 = row("А", [1, 30])\nr2 = row("Б", [2, 28])\ncard = card("Подпись", "А первый.")\n';
  expect(checkData(ranked, { excerpts: "А набрал 30 очков, Б набрал 28.", site: "s", time: "t" }).rejected).toBe(null);
  expect(checkData(ranked, { excerpts: "А набрал 30 очков.", site: "s", time: "t" }).rejected).toBe("28");
});

test("V4: the step choices of the classifier cover the option cap of the kit (LiveProtocol.MAX_STEPS)", () => {
  const protocol = readFileSync(new URL("../../app/src/main/java/com/alpha/assistant/voice/LiveProtocol.kt", import.meta.url), "utf8");
  expect(Number(/val MAX_STEPS = (\d+)/.exec(protocol)?.[1])).toBe(BUDGET.options);
});
