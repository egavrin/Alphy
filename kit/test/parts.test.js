// The parts: a need line to ordinary lines, and the apps that they make. Ported from the prototype's test.js.

import { test } from "bun:test";
import { AppFile, parseExpression } from "../src/dsl.js";
import { App, settings } from "../src/core.js";
import { report, smoke } from "../src/check.js";
import { expand } from "../src/parts.js";
import { assert, fixture, gateEach, has, plan, planned, same } from "./helpers.js";

gateEach("checks");
const partFault = async (text, part) => assert(has(await planned('app("T")\n' + text), part), `"${text}" gives the fault "${part}"`);
// The clean test of a report, as generate.js of the prototype has it.
const isClean = (result) => !result.faults.length && result.checks.every((check) => check.pass);

test("a patch is more lines, and a need that changes is expanded again", async () => {
  // A patch is more lines. A later line replaces the earlier one with the same name or path.
  const tip = new AppFile().feedText(plan("calc"));
  await expand(tip);
  tip.feed('tip_field = Segmented("Tip", $/tip, [10, 15, 20])');
  tip.feed("$/perPerson := round($/bill * (1 + $/tip / 100) / $/people, 0)");
  tip.feed("delete total_view");
  const patched = new App(tip);
  same(patched.find("tip_field").type, "Segmented", "component replaced by name");
  same(patched.state.perPerson, 23, "formula replaced by path");
  same(patched.find("total_view").type, "Missing", "a deleted component leaves a placeholder where root still names it");
  same(report(tip).faults, ["total_view: no component line with this name"], "the report names the dangling reference");
  tip.feed("root = Column([perPerson_view, bill_field, tip_field, people_field])");
  same(report(tip).faults, [], "a patch of root repairs it");
  same(patched.input("people_field", 2), "", "input");
  same(patched.state.perPerson, 46, "formulas follow an input");

  // A need that changes is expanded again. Other lines stay.
  tip.feed('need people = input("count", "Guests", 2, min=1, max=20)');
  await expand(tip);
  same(new App(tip).find("people_field").props.label, "Guests", "changed need expanded again");
  same(new App(tip).find("tip_field").type, "Segmented", "an earlier patch survives");
  same(tip.get("root").raw, "root = Column([perPerson_view, bill_field, tip_field, people_field])", "a part does not replace a patched root");
  same(tip.get("total_view"), undefined, "a part does not restore a deleted line");
  same(report(tip).faults, [], "no faults after the need changed");
  // A new need joins a patched root at its end.
  tip.feed('need note = content("Round up for good service.")');
  await expand(tip);
  same(tip.get("root").raw, "root = Column([perPerson_view, bill_field, tip_field, people_field, note_view])", "a new part joins a patched root");
  same(report(tip).faults, [], "no faults after a new need");
});

test("part texts follow the language of the app", async () => {
  const italian = await planned('app("T", lang="it")\nneed cose = entries("Cose", fields={nome: "text"}, addText="Aggiungi", emptyText="Niente qui")');
  same(new App(italian).find("cose_add").props.label, "Aggiungi", "the add button has the given text");
  same(new App(italian).find("cose_list").props.empty, "Niente qui", "the empty list has the given text");
});

// ---- Richer parts, each for a failure of the tune set. ----
test("entries: a choice field, fields that change in place, rows that move, and a value for each row", async () => {
  const ranked = await planned('app("T")\nneed tasks = entries("Tasks", fields={task: "text", level: ["high", "medium", "low"], hours: "number"}, edit=true, reorder=true, add=false, remove=false, show={double: $hours * 2}, rows=[{task: "a", level: "high", hours: 1}, {task: "b", level: "low", hours: 2}])');
  const rk = new App(ranked);
  same(report(ranked).faults, [], "entries with a choice field, edit, reorder, show");
  same([rk.find("tasks_level", 1).type, rk.find("tasks_level", 1).props.options, rk.find("tasks_hours", 0).type, rk.find("tasks_task", 0).type, rk.find("tasks_double", 1).props.value], ["Segmented", ["high", "medium", "low"], "NumberField", "TextField", 4], "the row has a choice, fields that change, and a value");
  same([rk.press("tasks_down", 0), rk.state.tasks.map((row) => row.task), rk.input("tasks_level", "medium", 0), rk.state.tasks[0].level], ["", ["b", "a"], "", "medium"], "a row moves, and a choice changes that row");
});

test("entries: a filter and a sort order that inputs set", async () => {
  const bikeShop = await planned('app("T")\nneed max = input("number", "Max", 500, min=0, max=2000)\nneed order = input("choice", "Order", "asc", options=["asc", "desc"])\nneed bikes = entries("Bikes", fields={name: "text", price: "number"}, add=false, remove=false, filter=$price <= $/max, sort=$price, order=$/order, rows=[{name: "a", price: 900}, {name: "b", price: 300}, {name: "c", price: 100}])');
  const sh = new App(bikeShop);
  same([report(bikeShop).faults, sh.find("bikes_list").children.map((row) => row.children[0].props.text), sh.input("order_field", "desc"), sh.find("bikes_list").children.map((row) => row.children[0].props.text)], [[], ["c", "b"], "", ["b", "c"]], "a list that inputs filter and sort");
});

test("chart: one point for each row of a list", async () => {
  const stepsApp = await planned('app("T")\nneed days = entries("Days", fields={day: "text", steps: "number"}, edit=true, add=false, remove=false, rows=[{day: "Mo", steps: 100}, {day: "Tu", steps: 300}])\nneed line = chart("Steps", $/days, $day, $steps, kind="line")');
  same([report(stepsApp).faults, new App(stepsApp).find("line_chart").props.points.map((point) => point.value), new App(stepsApp).find("line_chart").props.kind], [[], [100, 300], "line"], "a chart part");
});

test("deck: one row at a time, show the back, count yes and no, mark the row, end, again", async () => {
  const quizApp = await planned('app("T")\nneed words = entries("Words", fields={word: "text", meaning: "text", known: "check"}, rows=[{word: "apple", meaning: "A", known: false}, {word: "pear", meaning: "P", known: false}])\nneed quiz = deck("Quiz", $/words, front=$word, back=$meaning, mark=$known, show="Show", yes="Got it", no="Again", again="Start again", done="All done")');
  const qa = new App(quizApp);
  settings.gate = "smoke";
  same(report(quizApp).faults, [], "a deck has no fault, and the smoke run reaches its end");
  settings.gate = "checks";
  same([qa.find("quiz_front").props.text, qa.find("quiz_back").props.visible, qa.press("quiz_show"), qa.find("quiz_back").props.visible, qa.find("quiz_back").props.text], ["apple", false, "", true, "A"], "the back shows after a tap");
  same([qa.press("quiz_yesButton"), qa.state.words[0].known, qa.find("quiz_front").props.text, qa.press("quiz_show"), qa.press("quiz_noButton"), qa.state.quiz_yes, qa.state.quiz_no, qa.find("quiz_endCard").props.visible], ["", true, "pear", "", "", 1, 1, true], "yes marks the row, the deck goes on, and it ends");
  same([qa.press("quiz_again"), qa.state.quiz_i, qa.state.quiz_yes, qa.find("quiz_front").props.text], ["", 0, 0, "apple"], "again starts the deck from the first row");
});

test("steps: needs in groups, one group at a time", async () => {
  const wiz = await planned('app("T")\nneed pages = input("count", "Pages", 20, min=1, max=100)\nneed week = figure("number", "Week", $/pages * 7, example=[{pages: 10}, 70])\nneed setup = steps(["Pages", "Summary"], [[pages], [week]], next="Next", back="Back", finish="Start", done="Started")\nneed note = content("Hello")');
  const wz = new App(wiz);
  settings.gate = "smoke";
  same(report(wiz).faults, [], "steps have no fault");
  settings.gate = "checks";
  same([wiz.get("root").raw, wz.find("setup_step0").props.visible, wz.find("setup_step1").props.visible, wz.press("setup_next"), wz.find("setup_step1").props.visible, wz.press("setup_finish"), wz.state.setup_done], ["root = Column([setup_view, note_view])", true, false, "", true, "", true], "the steps hold their needs, and root does not show them twice");
});

test("entries: a time field and a switch in the row, and a delete that asks first", async () => {
  const doses = await planned('app("T")\nneed doses = entries("Doses", fields={name: "text", time: "time", on: "switch"}, edit=true, confirm="Delete this dose?", yes="Yes", no="No", rows=[{name: "A", time: "08:00", on: true}, {name: "B", time: "14:00", on: true}, {name: "C", time: "20:00", on: true}])');
  const ds = new App(doses);
  same([ds.find("doses_time", 1).type, ds.find("doses_on", 1).type, ds.find("doses_new_time").type, ds.find("doses_new_time").props.value, ds.input("doses_time", "09:30", 0), ds.state.doses[0].time, ds.input("doses_on", false, 1), ds.state.doses.map((row) => row.on)],
    ["TimeField", "Toggle", "TimeField", "08:00", "", "09:30", "", [true, false, true]], "a time field and a switch in each row");
  same([ds.find("doses_confirm").props.visible, ds.press("doses_remove", 1), ds.state.doses.length, ds.find("doses_confirm").props.visible, ds.find("doses_confirm").props.title, ds.press("doses_no"), ds.state.doses.length, ds.find("doses_confirm").props.visible],
    [false, "", 3, true, "Delete this dose?", "", 3, false], "the delete button asks first, and no keeps the row");
  same([ds.press("doses_remove", 1), ds.press("doses_yes"), ds.state.doses.map((row) => row.name), ds.find("doses_confirm").props.visible], ["", "", ["A", "C"], false], "yes deletes that row");
  same([ds.input("doses_new_name", "D"), ds.press("doses_add"), ds.state.doses.at(-1).on, ds.state.doses.at(-1).time], ["", "", true, "08:00"], "a new row has its switch on");
});

test("entries: a date field, the add form in a dialog, and rows in groups by day", async () => {
  const trip = await planned('app("T")\nneed acts = entries("Plan", fields={what: "text", day: ["Sat", "Sun"], time: "time", date: "date"}, sheet=true, group=$day, sort=[$day, $time], rows=[{what: "Hike", day: "Sun", time: "09:00", date: "2026-11-22"}, {what: "Dinner", day: "Sat", time: "19:00", date: "2026-11-21"}, {what: "Museum", day: "Sat", time: "10:00", date: "2026-11-21"}])');
  const tr = new App(trip);
  // A heading of a group, or the title of a row: the first text of the column at the start of the row.
  const shown = (children) => children.map((child) => (child.type === "Heading" ? "# " + child.props.text : child.children[0].children[0].props.text));
  same(shown(tr.find("acts_list").children), ["# Sat", "Museum", "Dinner", "# Sun", "Hike"], "a heading for each day, and time order inside a day");
  same([tr.find("acts_addRow").type, tr.find("acts_addRow").props.visible, tr.find("acts_new_date").type, /^\d{4}-\d{2}-\d{2}$/.test(tr.find("acts_new_date").props.value), tr.press("acts_open"), tr.find("acts_addRow").props.visible],
    ["Dialog", false, "DateField", true, "", true], "the add form opens in a dialog, and a new date starts at today");
  same([tr.input("acts_new_what", "Lunch"), tr.input("acts_new_time", "13:00"), tr.input("acts_new_date", "2026-11-21"), tr.press("acts_add"), tr.find("acts_addRow").props.visible, shown(tr.find("acts_list").children.slice(0, 4)), tr.state.acts.at(-1).date],
    ["", "", "", "", false, ["# Sat", "Museum", "Lunch", "Dinner"], "2026-11-21"], "the add button adds the row in its group and closes the dialog");
  same([tr.press("acts_remove", 0), tr.state.acts.map((row) => row.what)], ["", ["Hike", "Dinner", "Lunch"]], "a row of a group acts on the stored list");
});

test("entries: a count in the row with a minus and a plus button", async () => {
  const menu = await planned('app("T")\nneed drinks = entries("Menu", fields={drink: "text", price: "money", qty: "count"}, add=false, remove=false, rows=[{drink: "Tea", price: 2.5, qty: 0}, {drink: "Latte", price: 4, qty: 0}])\nneed total = figure("money", "Total", sum($/drinks, $price * $qty), example=[{drinks: [{drink: "Tea", price: 2.5, qty: 2}]}, 5])');
  const mn = new App(menu);
  same([mn.state.total, mn.press("drinks_qty_plus", 1), mn.press("drinks_qty_plus", 1), mn.press("drinks_qty_plus", 0), mn.state.total, mn.find("drinks_qty", 1).props.value, mn.press("drinks_qty_minus", 0), mn.press("drinks_qty_minus", 0), mn.state.drinks[0].qty, mn.state.total],
    [0, "", "", "", 10.5, 2, "", "", 0, 8], "a count in the row changes the row and the total, and stays at 0 or more");
});

test("deck with no back: pages with a next and a back button and an end", async () => {
  const story = await planned('app("T")\nneed pages = entries("Pages", fields={line: "text"}, hidden=true, rows=[{line: "One"}, {line: "Two"}, {line: "Three"}])\nneed book = deck("Story", $/pages, front=$line, next="Next", prev="Back", again="Again", done="The end")');
  const st = new App(story);
  same([story.get("root").raw, st.find("pages_list"), st.find("book_front").props.text, st.find("book_prev").props.enabled, st.find("book_show")], ["root = Column([book_view])", null, "One", false, null], "pages: the first row, no way back from it, no hidden side, and no list on the screen");
  same([st.press("book_next"), st.find("book_front").props.text, st.press("book_prev"), st.find("book_front").props.text, st.press("book_next"), st.press("book_next"), st.find("book_front").props.text, st.find("book_endCard").props.visible],
    ["", "Two", "", "One", "", "", "Three", false], "next and back turn the pages");
  same([st.press("book_next"), st.find("book_card").props.visible, st.find("book_endCard").props.visible, st.press("book_again"), st.find("book_card").props.visible, st.find("book_front").props.text], ["", false, true, "", true, "One"], "after the last page the card with its next button is hidden and the end shows, so there is no page 4. Again starts from page 1");
});

test("tabs: needs in groups, a date and a time input, and a chart of the sum for each category", async () => {
  const paged = await planned('app("T")\nneed bed = input("time", "Bed", "23:00")\nneed wake = input("time", "Wake", "07:00")\nneed slept = figure("number", "Hours", hours($/bed, $/wake), example=[{bed: "23:30", wake: "06:00"}, 6.5])\nneed day = input("date", "Day", "2026-11-21")\nneed costs = entries("Costs", fields={what: "text", cat: ["Food", "Bus"], amount: "number"})\nneed sums = chart("By category", groups($/costs, $cat, $amount), $key, $value)\nneed pages = tabs(["Sleep", "Costs"], [[slept, bed, wake, day], [costs, sums]])');
  const pg = new App(paged);
  same([paged.get("root").raw, pg.find("bed_field").type, pg.find("day_field").type, pg.state.slept, pg.find("pages_view").children.slice(1).map((group) => group.props.visible)], ["root = Column([pages_view])", "TimeField", "DateField", 8, [true, false]], "tabs hold their needs, and root does not show them twice");
  same([pg.press("pages_view_tab1"), pg.find("pages_view").children.slice(1).map((group) => group.props.visible), pg.input("costs_new_what", "Pizza"), pg.input("costs_new_amount", 12), pg.press("costs_add"), pg.input("costs_new_what", "Soup"), pg.input("costs_new_amount", 5), pg.press("costs_add"), pg.find("sums_chart").props.points],
    ["", [false, true], "", "", "", "", "", "", [{ label: "Food", value: 17 }]], "the second tab shows its group, and a chart shows the sum for each category");
});

// ---- Three kinds of holes of the f0 plans that a part now serves: a picture, a quiz with a typed answer, more than one option at a time. ----
test("picture: a box with the description on top, in each row, and on each card", async () => {
  const cafe = await planned('app("T")\nneed photo = picture("A small cafe with a red door")\nneed n = input("count", "Cups", 2, min=0, max=9)\nneed total = figure("money", "Total", $/n * 3, example=[{n: 2}, 6])\nneed animals = entries("Animals", fields={es: "text", drawing: "picture"}, rows=[{es: "gato", drawing: "A drawing of a cat"}, {es: "pez", drawing: "A drawing of a fish"}])\nneed cards = deck("Cards", $/animals, front=$es, picture=$drawing)');
  const cf = new App(cafe);
  settings.gate = "smoke";
  same([report(cafe).faults, cafe.get("root").raw.slice(0, 46), cf.find("photo_view").type, cf.find("photo_view").props.text], [[], "root = Column([photo_view, total_view, n_field", "Picture", "A small cafe with a red door"], "a picture is on top, with its description, and is no fault");
  settings.gate = "checks";
  same([cf.find("animals_drawing", 1).type, cf.find("animals_drawing", 1).props.text, cf.find("animals_new_drawing"), cf.input("animals_new_es", "oso"), cf.press("animals_add"), cf.state.animals.at(-1).drawing], ["Picture", "A drawing of a fish", null, "", "", ""], "a picture in each row, and no field for it in the add form");
  same([cf.find("cards_picture").props.text, cf.press("cards_next"), cf.find("cards_picture").props.text, cf.find("cards_front").props.text], ["A drawing of a cat", "", "A drawing of a fish", "pez"], "a picture on each card of a deck");
});

test("deck with answer=: a right answer counts one point once, and a wrong answer shows the right one", async () => {
  const verbs = await planned('app("T", lang="fr")\nneed rows = entries("Verbes", fields={q: "text", a: "text"}, hidden=true, rows=[{q: "parler, je", a: "parle"}, {q: "finir, nous", a: "finissons"}])\nneed quiz = deck("Quiz", $/rows, front=$q, answer=$a, field="Ta réponse", check="Vérifier", right="Correct", wrong="Faux", next="Suivant", points="Points", again="Recommencer", done="Fini")');
  const vb = new App(verbs);
  same([vb.find("quiz_front").props.text, vb.find("quiz_result").props.visible, vb.input("quiz_field", "  Parle "), vb.press("quiz_check"), vb.state.quiz_points, vb.find("quiz_result").props.visible, vb.find("quiz_result").props.text, vb.press("quiz_check").includes("not active"), vb.state.quiz_points],
    ["parler, je", false, "", "", 1, true, "Correct", true, 1], "a right answer, with other spaces and capital letters, counts one point once");
  same([vb.press("quiz_next"), vb.find("quiz_front").props.text, vb.find("quiz_field").props.value, vb.find("quiz_result").props.visible, vb.input("quiz_field", "finisons"), vb.press("quiz_check"), vb.state.quiz_points, vb.find("quiz_result").props.text],
    ["", "finir, nous", "", false, "", "", 1, "Faux: finissons"], "next shows the next row with an empty field, and a wrong answer counts no point and shows the right one");
  same([vb.press("quiz_next"), vb.find("quiz_card").props.visible, vb.find("quiz_endCard").props.visible, vb.find("quiz_score").props.value, vb.press("quiz_again"), vb.state.quiz_points, vb.find("quiz_front").props.text], ["", false, true, 1, "", 0, "parler, je"], "the quiz ends after the last row, and again starts it with 0 points");
  settings.gate = "smoke";
  same(report(verbs).faults, [], "the smoke gate is clean on a quiz with a typed answer, and reaches its end");
});

test("input choices: more than one option at a time, with a limit", async () => {
  const picks = await planned('app("T")\nneed genres = input("choices", "Genres", [], options=["Crime", "Fantasy", "History", "Poetry"], max=2)\nneed n = figure("count", "Chosen", count($/genres), example=[{genres: ["Crime", "Poetry"]}, 2])\nneed price = figure("money", "Price", 5 + sum($/genres, $it == "Crime" ? 2 : 1), example=[{genres: ["Crime", "Poetry"]}, 8])\nneed crime = figure("text", "Crime", has($/genres, "Crime") ? "yes" : "no", example=[{genres: ["Crime"]}, "yes"])');
  const pk = new App(picks);
  same([pk.find("genres_field").type, pk.state.n, pk.press("genres_field_opt0"), pk.press("genres_field_opt2"), pk.state.genres, pk.state.n, pk.state.price, pk.state.crime, pk.find("genres_field_opt0").props.selected, pk.find("genres_field_opt1").props.enabled, pk.press("genres_field_opt1").includes("not active")],
    ["Choices", 0, "", "", ["Crime", "History"], 2, 8, "yes", true, false, true], "two options are on, the figures follow, and at the limit the other options are disabled");
  same([pk.press("genres_field_opt0"), pk.state.genres, pk.state.crime, pk.find("genres_field_opt1").props.enabled, pk.press("genres_field_opt1"), pk.state.genres], ["", ["History"], "no", true, "", ["History", "Fantasy"]], "a second tap turns an option off, and the other options work again");
  settings.gate = "smoke";
  same([report(picks).faults, report(picks).checks.every((check) => check.pass)], [[], true], "the smoke gate is clean on choices");
});

test("a row field written as a value of the app in a row formula of a part", async () => {
  // f0 verbs-fr-2: "carte reads $/verbe, and no line sets it". The host reads the row field, writes the need line again, and says so in a note.
  settings.gate = "smoke";
  const verbes = 'need verbes = entries("Verbes", fields={verbe: "text", pronom: "text", reponse: "text"}, rows=[{verbe: "être", pronom: "je", reponse: "suis"}, {verbe: "avoir", pronom: "tu", reponse: "as"}], labels={verbe: "Verbe", pronom: "Pronom", reponse: "Réponse"}, addText="Ajouter", emptyText="Aucun verbe")';
  const carte = 'need carte = deck("Conjugue au présent", $/verbes, front=$/verbe + " — " + $/pronom, back=$/pronom + " " + $/reponse, show="Voir la réponse", yes="Je savais", no="À revoir", again="Recommencer", done="Terminé")';
  const lenient = await planned(`app("T", lang="fr")\n${verbes}\n${carte}`);
  const lr = report(lenient), la = new App(lenient);
  same([lr.faults, lr.notes.filter((note) => note.includes("The host read $verbe, $pronom, $reponse")).length, la.find("carte_front").props.text, la.press("carte_show"), la.find("carte_back").props.text],
    [[], 1, "être — je", "", "je suis"], "a deck reads $/verbe as the field of its row");
  assert(lenient.get("need:carte").raw.includes('$/verbes, front=(($verbe + " — ") + $pronom), back=(($pronom + " ") + $reponse)'), `the need line has the row fields now: ${lenient.get("need:carte").raw}`);
  // A line that sets $/verbe keeps its meaning, so the host changes nothing.
  const setValue = await planned(`app("T", lang="fr")\n$/verbe = "x"\n$/pronom = "y"\n$/reponse = "z"\n${verbes}\n${carte}`);
  same([setValue.get("need:carte").raw, report(setValue).notes.filter((note) => note.startsWith("need carte")), new App(setValue).find("carte_front").props.text], [carte, [], "x — y"], "a value of the app that a line sets is not read as a row field");
  // f0 kanji-ja-2: front=$/kanji reads the whole list. The host cannot correct that, so it changes nothing, and the faults stay for a repair step.
  const wholeList = await planned('app("T", lang="ja")\nneed cards = deck("練習カード", $/kanji, front=$/kanji, back=$/yomi + " — " + $/imi, mark=$oboe, show="答えを見る", yes="覚えた", no="まだ", again="最初から", done="完了")\nneed kanji = entries("漢字", fields={ji: "text", yomi: "text", imi: "text", oboe: "check"}, rows=[{ji: "日", yomi: "ひ・にち", imi: "sun, day", oboe: false}], labels={ji: "漢字", yomi: "読み方", imi: "意味", oboe: "覚えた"}, addText="追加", emptyText="まだカードがありません")');
  const wl = report(wholeList);
  assert(wl.faults.includes("cards reads $/yomi, and no line sets it") && !wl.notes.some((note) => note.startsWith("need cards")) && wholeList.get("need:cards").raw.includes("back=$/yomi"), `a deck that reads its whole list keeps its faults: ${JSON.stringify(wl)}`);
  // The same reading in a chart and in the row formulas of entries. An input need named max sets $/max, so $/max stays a value of the app.
  const rowwise = await planned('app("T")\nneed max = input("number", "Max", 4, min=0, max=10)\nneed days = entries("Days", fields={day: "text", steps: "number"}, rows=[{day: "Tu", steps: 5}, {day: "Mo", steps: 3}, {day: "We", steps: 9}], show={twice: $/steps * 2}, filter=$/steps > $/max, sort=$/day, group=$/day)\nneed bars = chart("Steps", $/days, $/day, $/steps)');
  const rw = new App(rowwise), rwReport = report(rowwise);
  same([rwReport.faults, rwReport.notes.filter((note) => note.includes("wrote the need line again")).length, rw.find("bars_chart").props.points.map((point) => `${point.label} ${point.value}`), rw.find("days_twice", 0).props.value, rw.find("days_twice", 1).props.value],
    [[], 2, ["Tu 5", "Mo 3", "We 9"], 10, 18], "a chart and the show, filter, sort, and group of entries read the row fields");
  // A component line of a model is not a part: its fault stays.
  const written = new AppFile().feedText('$/days = [{day: "Mo"}]\nroot = Column([list])\nlist = List($/days, row)\nrow = Row([Text($/day)])');
  await expand(written);
  assert(report(written).faults.includes("row reads $/day, and no line sets it"), `a model-written row keeps its fault: ${JSON.stringify(report(written).faults)}`);
});

test("an odd need gives a fault or a correct expansion, and the other needs still expand", async () => {
  // f0 words-ko-3 ended with the exception "unexpected ','".
  settings.gate = "smoke";
  const odd = await planned('app("T")\nneed n = figure("count", "N", 1 + 1, example=[{}, 2])\nneed s = steps([], [])\nneed bad = entries("L", fields={"my field": "text"})\nneed kind = toString(1)\nneed ok = input("text", "Ok", "")');
  const oddReport = report(odd);
  same([oddReport.faults.map((fault) => ["line: need bad: line not understood", "line: no part for need kind toString"].find((start) => fault.startsWith(start)) ?? fault), oddReport.checks, odd.get("check:n example").raw, odd.get("root").raw],
    [["line: need bad: line not understood", "line: no part for need kind toString"], [{ name: "n example", pass: true, detail: "" }], 'check("n example", expect(near($/n, 2)))', "root = Column([n_view, s_view, ok_field])"], "an example with no value, steps with no group, an odd field name, and a kind that is no part");
});

test("the host never throws for model text in a need", async () => {
  const ODD = ['"a, b (c) \\"d\\" e"', '"줄\\n바꿈, 따옴표 \\"x\\" (괄호)"', '"単語、意味（かっこ）"', "7", "-1", "true", "null", "[]", "{}", '["a,b", "c)d"]', "[{}]", "[[]]", '{a: "x, y"}', '{"my field": "text"}', "$/nope", "$field", "$/rows", "name_ref", "count($/rows)", "nope(1, 2)", "$/rows[0]/word", '"a" + 1', "[$a, $b]", "{a: [1, 2], b: {c: 1}}", "toString", "constructor(1)"];
  const BASE = ['app("T", lang="ko")', 'need rows = entries("단어", fields={word: "text", meaning: "text", n: "number", known: "check"}, rows=[{word: "apple", meaning: "사과", n: 1, known: false}])', 'need pages = input("count", "Pages", 20, min=1, max=100)'];
  const KINDS = {
    input: [['"number"', '"Label"', "5"], { min: "0", max: "10", step: "1", options: '["a", "b"]' }],
    figure: [['"number"', '"Total"', "$/pages * 2"], { example: "[{pages: 10}, 20]", decimals: "1" }],
    entries: [['"List"'], { fields: '{name: "text", qty: "count", on: "switch", day: ["Sat", "Sun"], at: "time", d: "date", p: "picture", done: "check"}', rows: "[]", labels: '{name: "Name"}', addText: '"Add"', emptyText: '"None"', edit: "true", reorder: "true", add: "false", remove: "false", filter: "$qty > 0", sort: "$name", order: "$/pages", show: "{twice: $qty * 2}", confirm: '"Delete?"', yes: '"Yes"', no: '"No"', sheet: "true", group: "$day", hidden: "true" }],
    progress: [['"Done"', "count($/rows, $known)", "len($/rows)"], {}],
    counter: [['"Cups"', "0"], { step: "1", min: "0", max: "9" }],
    chart: [['"Chart"', "$/rows", "$word", "$n"], { kind: '"line"' }],
    deck: [['"Quiz"', "$/rows"], { front: "$word", back: "$meaning", mark: "$known", answer: "$meaning", picture: "$word", show: '"Show"', yes: '"Yes"', next: '"Next"', prev: '"Back"', field: '"Answer"', points: '"Points"' }],
    steps: [['["One", "Two"]', "[[rows], [pages]]"], { next: '"Next"', back: '"Back"', finish: '"Go"', done: '"Done"' }],
    tabs: [['["One", "Two"]', "[[rows], [pages]]"], {}],
    content: [['"Text"'], {}], picture: [['"A cat"'], {}], hole: [['"A thing"'], {}],
  };
  const line = (kind, args, named) => `need x = ${kind}(${[...args, ...Object.entries(named).map(([key, value]) => `${key}=${value}`)].join(", ")})`;
  const cases = ["need x = 5", "need x = constructor(1)", 'need 단어 = entries("단어", fields={단어: "text"})', 'need x = figure("number", "N", 1, example=[{rows: []}])', 'need x = figure("number", "N", 1, example={})', 'need x = deck("Q", $/rows)', 'need x = steps(["A"], [["rows"]])', 'need x = tabs(["A"], [[x], [nope]])', 'need rows = hole("the name of the list")'];
  for (const [kind, [args, named]] of Object.entries(KINDS)) {
    cases.push(line(kind, [], {}), line(kind, args, named), line(kind, args.slice(0, -1), named), line(kind, [...args, '"extra"'], { nope: "1" }));
    for (const value of ODD) {
      args.forEach((_, index) => cases.push(line(kind, args.map((arg, at) => (at === index ? value : arg)), {})));
      for (const key of Object.keys(named)) cases.push(line(kind, args, { ...named, [key]: value }), line(kind, args, { [key]: value }));
    }
  }
  // Each case runs with the gate of checks. Each fifth one also runs the smoke gate, which is slower.
  for (const [index, need] of cases.entries()) for (const gate of index % 5 ? ["checks"] : ["checks", "smoke"]) {
    settings.gate = gate;
    const file = new AppFile();
    [...BASE, need].forEach((text) => file.feed(text));
    try { await expand(file); report(file); } catch (failure) { assert(false, `the host threw for a need line (${gate}): ${failure.message}: ${need}`); }
  }
  assert(cases.length > 2000, `odd need lines: ${cases.length}`);
}, 60000);

test("entries: a count has a number field in the add form and its stepper in the row", async () => {
  settings.gate = "smoke";
  const stock = await planned('app("T")\nneed meds = entries("Stock", fields={name: "text", count: "count"}, rows=[])');
  const st = new App(stock);
  same([st.input("meds_new_name", "Ibuprofen"), st.input("meds_new_count", 30), st.press("meds_add"), st.find("meds_count", 0).props.value, st.state.meds_new_count, st.press("meds_count_plus", 0), st.find("meds_count", 0).props.value],
    ["", "", "", 30, 0, "", 31], "a row with the count 30 comes from the form, and plus makes it 31");
  same(smoke(stock).faults, [], "the smoke run is clean on a list with a count");
});

test("timer: a clock that the test sets, rounds with a rest, and seconds from a choice", async () => {
  settings.gate = "smoke";
  const realClock = settings.now;
  let clock = 5000;
  settings.now = () => clock;
  const after = (app, seconds) => { clock += seconds * 1000; app.tick(); };
  const egg = await planned('app("T")\nneed egg = timer("Egg", 90)\nneed count = figure("count", "Eggs done", $/egg, example=[{egg: 2}, 2])');
  const eg = new App(egg), face = (app, name) => app.find(name + "_time").props.value;
  same([face(eg, "egg"), eg.find("egg_pause").props.visible, eg.find("egg_reset").props.enabled, eg.find("egg_phaseText"), eg.find("egg_bar").props.label], ["01:30", false, false, null, "Egg"], "a timer of 90 s shows 01:30 and waits");
  // With an alert and one round, the timer is the host's own countdown: one Timer line, and no value of the app.
  const alarm = await planned('app("T")\nneed egg = timer("Egg", 90, alert=true)');
  same([alarm.get("egg_timer")?.raw, alarm.of("state").length, new App(alarm).find("egg_timer")?.props.seconds], ['egg_timer = Timer("Egg", 90)', 0, 90], "a timer with an alert and one round is a Timer line");
  after(eg, 600);
  same(face(eg, "egg"), "01:30", "no time passes before the start");
  same([eg.press("egg_start"), (after(eg, 30), face(eg, "egg")), eg.press("egg_pause"), (after(eg, 100), face(eg, "egg")), eg.find("egg_start").props.visible], ["", "01:00", "", "01:00", true], "30 s after the start it shows 01:00, and a pause holds it");
  same([eg.press("egg_start"), (after(eg, 60), face(eg, "egg")), eg.find("egg_doneNote").props.visible, eg.find("egg_doneNote").props.text, eg.state.egg, eg.state.count, eg.find("egg_start").props.visible],
    ["", "00:00", true, "Time is up", 1, 1, false], "60 s more and the time is up: the done text shows, and $/egg is 1 for a figure");
  same([eg.press("egg_reset"), face(eg, "egg"), eg.find("egg_doneNote").props.visible, eg.state.egg], ["", "01:30", false, 1], "reset gives the full time again, and the count stays");
  // Rounds with a rest: work, rest, work, and the end after the rest of round 8.
  const workout = await planned('app("T")\nneed set = timer("Workout", 40, rounds=8, rest=20, workText="Go", restText="Rest")');
  const wo = new App(workout), phase = () => wo.find("set_phaseText").props.text;
  same([phase(), wo.press("set_start"), (after(wo, 39), [face(wo, "set"), phase()]), (after(wo, 1), [face(wo, "set"), phase(), wo.state.set]), (after(wo, 20), [face(wo, "set"), phase()])],
    ["Go 1 / 8", "", ["00:01", "Go 1 / 8"], ["00:20", "Rest 1 / 8", 1], ["00:40", "Go 2 / 8"]], "work, rest, and the work phase of round 2");
  after(wo, 7 * 60 - 1);
  same([wo.state.set, wo.state.set_over, face(wo, "set"), phase()], [8, false, "00:01", "Rest 8 / 8"], "one second before the end of round 8");
  after(wo, 500);
  same([wo.state.set, wo.state.set_over, wo.state.set_on, wo.find("set_doneNote").props.visible], [8, true, false, true], "the timer ends after 8 rounds with $/set 8");
  // The seconds come from a choice input, and the choice changes the time before the start.
  const tea = await planned('app("T")\nneed kind = input("choice", "Tea", "black", options=["black", "green", "herbal"])\nneed brew = timer("Brew", $/kind == "green" ? 120 : $/kind == "herbal" ? 300 : 180)');
  const te = new App(tea);
  same([face(te, "brew"), te.input("kind_field", "green"), face(te, "brew"), te.input("kind_field", "herbal"), face(te, "brew")], ["03:00", "", "02:00", "", "05:00"], "the choice sets the time of the timer");
  settings.now = realClock;
  // The smoke run has its own clock. It uses start, pause, and reset, moves the clock to the end, and reports no dead button.
  for (const file of [egg, workout, tea]) {
    const run = smoke(file), name = file.of("need").find((entry) => entry.tree.name === "timer").key, text = run.trace.join(" | ");
    same(run.faults, [], `the smoke run is clean on the timer ${name}`);
    for (const part of [`tap ${name}_start`, `tap ${name}_pause`, `tap ${name}_reset`, `${name}_over -> true`]) assert(text.includes(part), `the smoke run of ${name} has "${part}": ${text}`);
  }
  same([settings.now, isClean(report(egg)), isClean(report(workout)), isClean(report(tea))], [realClock, true, true, true], "the smoke run gives the clock back, and each timer app is clean");
});

// ---- Data lines first: a drawing, a simple game, and a 3D model are lines of data that the host draws. Expected values are fixed by hand. ----
test("drawing: one Drawing whose shapes are plain numbers", async () => {
  settings.gate = "smoke";
  // each gives a shape for each row or for each number below a count.
  const sky = await planned('app("T")\n$/cells = [{n: 3, on: true}, {n: 5, on: false}]\nneed r = counter("R", 10)\nneed sky = drawing("Sky", 200, 100, [circle(50, 50, $/r * 2, "sun"), rect(0, 80, 200, 20, "land", radius=4), line(0, 0, 200, 100, "ink", width=3), text(100, 10, "N " + text(len($/cells)), "muted", size=9), polygon([0, 0, 10, 0, 5, 8], "#ff0000", stroke=true), each($/cells, rect($index * 20 + 100, 30, $n * 2, 6, "wood", visible=$on)), each(3, circle($index * 10 / 4, 90, 2))])');
  const sk = new App(sky);
  same(sk.find("sky_view").props, { label: "Sky", width: 200, height: 100, shapes: [{ shape: "circle", x: 50, y: 50, r: 20, color: "sun" }, { shape: "rect", x: 0, y: 80, w: 200, h: 20, color: "land", radius: 4 },
    { shape: "line", x1: 0, y1: 0, x2: 200, y2: 100, color: "ink", width: 3 }, { shape: "text", x: 100, y: 10, text: "N 2", color: "muted", size: 9 }, { shape: "polygon", points: [0, 0, 10, 0, 5, 8], color: "#ff0000", stroke: true },
    { shape: "rect", x: 100, y: 30, w: 6, h: 6, color: "wood" }, { shape: "circle", x: 0, y: 90, r: 2, color: "ink" }, { shape: "circle", x: 2.5, y: 90, r: 2, color: "ink" }, { shape: "circle", x: 5, y: 90, r: 2, color: "ink" }] }, "the shapes of a drawing as plain values");
  same([sk.press("r_plus"), sk.find("sky_view").props.shapes[0].r, sky.made.get("sky"), sky.get("sky_view").tree.name, report(sky).faults], ["", 22, [], "Drawing", []], "a shape follows a value of the app, and a drawing is no control");
  await partFault('need d = drawing("D", 100, 100, [star(1, 2, 3)])', "star(1, 2, 3) is not one of circle, rect, line, text, polygon, each");
  await partFault('need d = drawing("D", 100, 100, [circle("a", 2, 3, "ink")])', "x of circle is not a number");
  await partFault('need d = drawing("D", 100, 100, [rect(1, 2, 3, "ink")])', "h of rect is not a number: rect takes x, y, w, h, color");
  await partFault('need d = drawing("D", 100, 100, [rect(1, 2, 3)])', "shapes: rect(1, 2, 3) has 3 values: rect takes x, y, w, h, color, radius");
  // A model writes the size of a text, the width of a line, and the radius of a rect after the color with no name. The host takes it.
  const loose = new App(await planned('app("T")\nneed d = drawing("D", 100, 100, [text(10, 10, "A", "muted", 12), line(0, 0, 9, 9, "ink", 3), rect(0, 0, 5, 5, "ink", 2), text(1, 2, "B", "ink", 9, size=7)])')).find("d_view").props.shapes;
  same(loose.map((shape) => [shape.size, shape.width, shape.radius]), [[12, undefined, undefined], [undefined, 3, undefined], [undefined, undefined, 2], [7, undefined, undefined]], "the extra value of a shape after its color, and a named one wins");
  await partFault('need d = drawing("D", 100, 100, [circle(1, 2, 3, "ink", 5)])', 'circle(1, 2, 3, "ink", 5) has 5 values: circle takes x, y, r, color');
  await partFault('need d = drawing("D", 100, 100, [text(1, 2, "A", "ink", 9, 4)])', "has 6 values: text takes x, y, text, color, size");
  await partFault('need d = drawing("D", 100, 100, [circle(1, 2, 3, "ink", glow=2)])', "circle has no argument glow");
  await partFault('need d = drawing("D", [circle(1, 2, 3, "ink")])', "drawing needs a width and a height");
  // Shapes with no list around them are still the shapes.
  same(new App(await planned('app("T")\nneed d = drawing("D", 10, 10, circle(1, 2, 3, "ink"), circle(4, 5, 6, "sun"))')).find("d_view").props.shapes.map((shape) => shape.x), [1, 4], "shapes with no list");
});

test("button and a timed change: animate moves a value over its time", async () => {
  settings.gate = "smoke";
  const realClock = settings.now;
  let clock = 1000;
  settings.now = () => clock;
  const after = (app, ms) => { clock += ms; app.tick(); return app.state.angle; };
  const spin = await planned('app("T")\nneed angle = input("number", "Angle", 40, min=0, max=360)\nneed lap = button("One turn", [set($/angle, 0), animate($/angle, 360, 5000)], enabled=$/angle < 400)');
  const stuck = await planned('app("T")\nneed angle = input("number", "Angle", 40, min=0, max=360)\nneed stay = button("Stay", animate($/angle, $/angle, 500))');
  const sp = new App(spin);
  same([sp.find("lap_button").props.label, sp.find("lap_button").props.enabled, spin.made.get("lap"), sp.press("lap_button"), sp.state.angle, after(sp, 1250), after(sp, 1250), after(sp, 2499), after(sp, 1), sp.state._run, after(sp, 9000)],
    ["One turn", true, ["lap_button"], "", 0, 90, 180, 359.928, 360, undefined, 360], "animate moves the value in a straight line over its time, and ends at the value");
  // A value that the person sets in the meantime ends the timed change.
  same([sp.press("lap_button"), after(sp, 1000), sp.input("angle_field", 200), after(sp, 1000), sp.state._run], ["", 72, "", 200, undefined], "a field that the person sets ends the timed change");
  const instant = new App(spin);
  same([new App(stuck).press("stay_button"), new App(stuck).state._run, instant.act(parseExpression("animate($/angle, 10, 0)")), instant.state.angle, sp.fire({ type: "Button", props: { action: parseExpression("animate($angle, 10, 100)") } })],
    ["", undefined, undefined, 10, "Button: animate needs a value of the app as its place, such as $/angle"], "a change to the same value starts nothing, a time of 0 sets the value at once, and animate needs a $/path");
  settings.now = realClock;
  // The smoke run moves its own clock to the end of the change. So the button that starts it shows its effect, and a button that changes nothing is still a fault.
  const spun = smoke(spin);
  assert(spun.trace.some((line) => line.startsWith("tap lap_button: angle 40 -> 360")), "the smoke run sees the end of the timed change: " + spun.trace.join(" | "));
  same([spun.faults, smoke(stuck).faults, settings.now], [[], ["smoke run: stay_button never changes a value: its action does nothing"], realClock], "the button with a timed change is not dead, and the one that changes nothing is");
  await partFault('need b = button("B")', "button needs an action");
});

test("3D: one Model3d whose bodies are plain numbers, a chip for each named part, an explode button, and two turn buttons", async () => {
  settings.gate = "smoke";
  const toy = await planned('app("T")\nneed toy = model3d("Toy", [box("", 0, 0, 0, 2, 1, 1, "sand"), box("top", 0, 2, 0, 1, 1, 1, "accent"), cylinder("legs", 0, -2, 0, 0.5, 1, "y", "ink")], parts={top: "Top", legs: "Legs"}, explodeText="Apart", joinText="Join")');
  const to = new App(toy), model = () => to.find("toy_model").props, heights = () => model().bodies.map((body) => body.y);
  same([to.state.toy, model().label, model().value, model().exploded, model().selected, model().bodies],
    [{ selected: "", exploded: false, turn: 30 }, "Toy", 30, false, "", [{ body: "box", part: "", x: 0, y: 0, z: 0, w: 2, h: 1, d: 1, color: "sand" }, { body: "box", part: "top", x: 0, y: 2, z: 0, w: 1, h: 1, d: 1, color: "accent" },
      { body: "cylinder", part: "legs", x: 0, y: -2, z: 0, radius: 0.5, length: 1, axis: "y", color: "ink" }]], "the bodies of a model as plain values");
  same(toy.made.get("toy"), ["toy_part_top", "toy_part_legs", "toy_left", "toy_right", "toy_explode"], "the controls of the 3D part");
  same([to.press("toy_part_top"), to.state.toy.selected, model().selected, to.find("toy_part_top").props.selected, to.find("toy_part_legs").props.selected, to.press("toy_part_legs"), to.state.toy.selected, to.press("toy_part_legs"), to.state.toy.selected],
    ["", "top", "top", true, false, "", "legs", "", ""], "a chip selects its part, and a second tap clears the selection");
  // Taken apart: the center of the three bodies is (0, 0, 0), and each named part moves away by half of its distance.
  same([to.find("toy_explode").props.label, heights(), to.press("toy_explode"), to.state.toy.exploded, model().exploded, heights(), to.find("toy_explode").props.label, to.press("toy_explode"), heights()],
    ["Apart", [0, 2, -2], "", true, true, [0, 3, -3], "Join", "", [0, 2, -2]], "the explode button moves each named part away from the center, and back");
  same([to.press("toy_right"), model().value, to.press("toy_left"), to.press("toy_left"), model().value, to.input("toy_model", 90), to.state.toy.turn], ["", 60, "", "", 0, "", 90], "the turn buttons and a drag set the angle");
  same(report(toy).faults, [], "the smoke run is clean on the 3D part");
  const still = new App(await planned('app("T")\nneed m = model3d("M", [box("a", 0, 0, 0, 1, 1, 1, "ink")], explode=false, turn=false)'));
  same([still.find("m_explode"), still.find("m_left"), still.find("m_model").props.bind, still.find("m_model").props.bodies.length], [null, null, undefined, 1], "explode=false and turn=false give a model with no buttons and no drag");
  await partFault('need m = model3d("M", [cylinder("a", 0, 0, 0, 1, 1, "w", "ink")])', 'axis of cylinder is "x", "y", or "z"');
  await partFault('need m = model3d("M", [ball("a", 0, 0, 0, 1, "ink")])', "is not one of box, cylinder, each");
});

test("the text parts, the arguments of a picture, and the kind of the header", async () => {
  settings.gate = "smoke";
  const texts = await planned(['app("T", kind="Lesson", lang="en")', 'need n = counter("N", 1)', 'need c = card("T" + text($/n), $/n > 1 ? "many" : "one", eyebrow="Look")', 'need l = link("Site", "https://example.org/a", note="Open")',
    'need les = lesson([{title: "A", text: "a"}, {title: "B", text: "b"}, {title: "C", text: "c"}], next="Go", back="Back", stepText="Part")', 'need ask = question("Q?", options=["Yes", "No"], right="No", rightText="Right", wrongText="Not yet")',
    'need p = picture("A rover", found="apollo rover", caption="Cap", credit="NASA", tag="1972")', 'need plain = picture("Plain")', 'need one = lesson([{title: "Only", text: "o"}])'].join("\n"));
  const tx = new App(texts), prop = (name, key = "text") => tx.find(name).props[key];
  same([texts.get("app").tree.named.kind.v, report(texts).faults], ["Lesson", []], "the header takes kind=, and the text parts are clean in the smoke run");
  same([prop("c_eyebrow"), prop("c_eyebrow", "variant"), prop("c_title"), prop("c_text"), tx.press("n_plus"), prop("c_title"), prop("c_text"), tx.find("c_view").type], ["Look", "eyebrow", "T1", "one", "", "T2", "many", "Card"], "a card with formulas follows the values of the app");
  same([tx.find("l_view").type, tx.find("l_view").props], ["Link", { label: "Site", url: "https://example.org/a", text: "Open" }], "a link only draws: a label, an address, and a note");
  await partFault('need l = link("X", "javascript:alert(1)")', "a Link needs a web address that starts with https://");
  same([tx.state.les, prop("les_count"), prop("les_bar", "value"), prop("les_bar", "max"), prop("les_title"), prop("les_text"), prop("les_back", "enabled"), tx.press("les_next"), tx.state.les, prop("les_count"), prop("les_title"),
    tx.press("les_next"), prop("les_count"), prop("les_next", "enabled"), tx.press("les_next"), tx.press("les_back"), tx.state.les, prop("les_text")],
    [1, "Part · 1 / 3", 1, 3, "A", "a", false, "", 2, "Part · 2 / 3", "B", "", "Part · 3 / 3", false, "les_next is not active", "", 2, "b"], "a lesson shows one step at a time, and $/NAME is the number of the step from 1");
  same([tx.find("one_next"), tx.find("one_back"), prop("one_count"), prop("one_title")], [null, null, "Step · 1 / 1", "Only"], "a lesson of one step has no buttons");
  same([tx.state.ask, prop("ask_text"), prop("ask_result", "visible"), tx.press("ask_opt0"), tx.state.ask, prop("ask_result"), prop("ask_opt0", "selected"), tx.press("ask_opt1"), tx.state.ask, prop("ask_result"), prop("ask_opt0", "selected")],
    [false, "Q?", false, "", false, "Not yet", true, "", true, "Right", false], "a question: $/NAME is true after the right answer");
  await partFault('need ask = question("Q?", options=["Yes", "No"], right="Maybe")', 'right="Maybe" is not one of the options');
  same([tx.find("p_picture").props, prop("p_caption"), prop("p_credit"), prop("p_credit", "variant"), prop("p_tag", "value"), tx.find("p_view").type, texts.get("plain_view").raw],
    [{ text: "A rover", found: "apollo rover" }, "Cap", "NASA", "muted", "1972", "Column", 'plain_view = Picture("Plain")'], "a found picture with its texts, and a plain picture as before");
});

test("a group of tabs or steps names its needs, also as $/NAME and $NAME", async () => {
  settings.gate = "smoke";
  const grouped = await planned('app("T")\nneed a = content("A")\nneed b = content("B")\nneed c = content("C")\nneed pages = tabs(["One", "Two"], [[$/a, b], [$c]])');
  same([report(grouped).faults, grouped.get("pages_view").raw], [[], 'pages_view = Tabs(["One", "Two"], [[a_view, b_view], [c_view]], label=join(["One", "Two"]))'], "a group of tabs with a need by its value name");
});

// ---- The four screens of the dialogue mocks as app files, and the turns of a dialogue on them. No model. ----
const mock = async (name, text) => {
  const file = await planned(text);
  assert(text.trim().split("\n").every((line) => /^(app\(|need [a-z]+ = |\$\/[a-z]+ :?= )/.test(line)), `${name}: need lines and section lines only`);
  same([report(file).faults, report(file).checks], [[], []], `${name}: clean under the smoke gate`);
  return file;
};

test("screen 1, talk and materials: the photo tab comes back", async () => {
  settings.gate = "smoke";
  // "Верни фотографию": the person is on the sketch tab, and a press goes back to the photo.
  const materials = await mock("materials", fixture("dialogue-materials")), ma = new App(materials), open = () => ma.find("views_view").children.slice(1).map((group) => group.props.visible);
  same([open(), ma.press("views_view_tab1"), open(), ma.press("views_view_tab0"), open(), ma.find("photo_picture").props.found, ma.find("photo_credit").props.text, ma.find("source_view").props.url],
    [[true, false], "", [false, true], "", [true, false], "Apollo 17 Gene Cernan lunar roving vehicle", "NASA / Harrison Schmitt", "https://science.nasa.gov/resource/apollo-17-gene-cernan-in-lunar-roving-vehicle/"], "the photo tab comes back");
});

test("screen 2, lesson and experiment: the choice changes the mark of the Moon, and one turn takes 5 s", async () => {
  settings.gate = "smoke";
  // At 90 degrees the Moon is at (160, 50). With rotation the mark looks at the Earth: (160, 68).
  // "А если не вращать?": without rotation the mark keeps its direction in space: (142, 50).
  const lesson = await mock("lesson", plan("lesson")), le = new App(lesson), shapes = () => le.find("orbit_view").props.shapes, spot = (shape) => [shape.x, shape.y];
  same([le.input("angle_field", 90), spot(shapes().at(-2)), spot(shapes().at(-1)), le.input("mode_field", "Без вращения"), spot(shapes().at(-2)), spot(shapes().at(-1))], ["", [160, 50], [160, 68], "", [160, 50], [142, 50]], "the choice changes the mark of the Moon");
  same([shapes().some((shape) => shape.shape === "line"), le.find("lesson_count").props.text, le.press("lesson_next"), le.state.lesson, le.find("lesson_title").props.text, shapes().filter((shape) => shape.shape === "line").map((line) => [line.x1, line.y1, line.x2, line.y2])],
    [false, "Исследуем · 1 / 3", "", 2, "Метка всё время обращена к Земле", [[160, 160, 160, 50]]], "step 2 of the lesson adds the guide line from the Earth to the Moon");
  // "Один круг": the Moon goes once around in 5 s. After 2.5 s it is at 180 degrees, at (50, 160), and the mark without rotation is at (32, 160).
  const realClock = settings.now;
  let clock = 0;
  settings.now = () => clock;
  same([le.press("lap_button"), le.state.angle, (clock += 2500, le.tick(), le.state.angle), spot(shapes().at(-2)), spot(shapes().at(-1)), (clock += 2500, le.tick(), le.state.angle), spot(shapes().at(-2)), le.state._run],
    ["", 0, 180, [50, 160], [32, 160], 360, [270, 160], undefined], "one turn of the Moon in 5 s");
  settings.now = realClock;
  same([le.state.ask, le.press("ask_opt0"), le.find("ask_result").props.text, le.press("ask_opt1"), le.state.ask, le.find("ask_result").props.text], [false, "", "Попробуй режим «Без вращения» и передвинь Луну.", "", true, "Верно! Без вращения к Земле обращались бы разные стороны."], "the question of the lesson");
});

test("screen 3, game and tutor: two pieces on the bridge, and the pieces are empty again", async () => {
  settings.gate = "smoke";
  // The river has 5 cells. Pieces 2 and 4 make a bridge that is 1 cell too long.
  const game = await mock("game", fixture("dialogue-game")), ga = new App(game), scene = () => ga.find("scene_view").props.shapes, bridge = () => scene().filter((shape) => ["wood", "sand"].includes(shape.color)).map((shape) => shape.color);
  const river = () => scene().filter((shape) => shape.shape === "rect" && shape.stroke).length, text = (name) => ga.find(name).props.text;
  same([river(), bridge(), text("count_view"), text("note_view"), ga.find("check_button").props.enabled, ga.press("two_button"), ga.press("four_button"), ga.state.pieces, ga.state.sum, bridge(), ga.find("three_button").props.enabled, ga.press("check_button"), text("note_view")],
    [5, [], "Мост: 0 / 5 клеток", "Нажми на деталь, чтобы поставить её.", false, "", "", [2, 4], 6, ["wood", "wood", "sand", "sand", "sand"], false, "", "Мост длиннее на 1 клетки. Убери последнюю деталь."], "two pieces on the bridge, and the check says that it is too long");
  // "Начнём заново": the button of the mock takes the last piece away, so two presses give an empty bridge.
  same([ga.press("undo_button"), ga.state.pieces, ga.press("undo_button"), ga.state.pieces, bridge(), text("count_view"), text("note_view"), ga.find("undo_button").props.enabled, ga.press("undo_button")],
    ["", [2], "", [], [], "Мост: 0 / 5 клеток", "Нажми на деталь, чтобы поставить её.", false, "undo_button is not active"], "the pieces are empty again");
});

test("screen 4, 3D and sketch: the rover is taken apart, and the camera is the selected part", async () => {
  settings.gate = "smoke";
  // The center of the nine bodies is (0, 1, 0). "Разбери по частям": each named part moves away by half of its distance.
  const workshop = await mock("rover", fixture("dialogue-rover")), ro = new App(workshop), body = (index) => { const at = ro.find("rover_model").props.bodies[index]; return [at.x, at.y, at.z]; };
  same([body(0), body(1), body(6), body(8), ro.press("rover_explode"), ro.state.rover.exploded, body(0), body(1), body(6), body(8), ro.find("rover_explode").props.label],
    [[0, 0.9, 0], [-1.1, 0.4, 1], [1, 2.2, 0], [-1, 1.4, 0], "", true, [0, 0.9, 0], [-1.65, 0.1, 1.5], [1.5, 2.8, 0], [-1.5, 1.6, 0], "Собрать"], "the rover is taken apart: the frame stays, and a wheel, the camera, and the panel move");
  // "Стоп, только камеру": the model is put together, the camera is the selected part, and the card has the camera text.
  same([ro.find("focus_title").props.text, ro.press("rover_explode"), ro.press("rover_part_camera"), ro.state.rover, ro.find("rover_model").props.selected, body(6), ro.find("focus_title").props.text, ro.find("focus_text").props.text, ro.find("focus_eyebrow").props.text],
    ["Твой лунный вездеход", "", "", { selected: "camera", exploded: false, turn: 30 }, "camera", [1, 2.2, 0], "Камера на мачте", "В этой концепции камера поднята над корпусом. Так ей удобнее смотреть по сторонам.", "В фокусе"], "the camera is selected, and the card follows");
  same([ro.press("rover_part_camera"), ro.state.rover.selected, ro.find("focus_title").props.text], ["", "", "Твой лунный вездеход"], "a second tap clears the selection of the rover");
});
