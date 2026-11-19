// The words of the parts: the host's words where the host has them, else the kit's word table in the language of the app.

import { expect, test } from "bun:test";
import { compileAppFile } from "../src/compile.js";
import { emit } from "../src/emit.js";
import { WORDS } from "../src/words.js";
import { runtimeText } from "../build.js";
import { every, planned } from "./helpers.js";

/** The definition of a plan that the emitter builds with no fault, and its notes. */
const built = async (...lines) => {
  const result = await compileAppFile(lines.join("\n"));
  expect(result.faults).toEqual([]);
  return { ui: result.definition.ui, nodes: every(result.definition.ui), notes: result.notes };
};

test("words: a list with no emptyText= shows the host's word for an empty list", async () => {
  const { nodes } = await built('app("Spesa", lang="it")', 'need items = entries("Prodotti", fields={name: "text"})');
  const list = nodes.find((node) => node.type === "list");
  expect(list).not.toHaveProperty("emptyText");
  // The author's text wins.
  const given = await built('app("Spesa", lang="it")', 'need items = entries("Prodotti", fields={name: "text"}, emptyText="Niente")');
  expect(given.nodes.find((node) => node.type === "list").emptyText).toBe("Niente");
});

test("words: the captions of a part are in the language of the app, and an option of the author wins", async () => {
  const { nodes } = await built('app("Spesa", lang="it")',
    'need items = entries("Prodotti", fields={name: "text", qty: "count", done: "check", on: "switch"}, confirm="Togliere?", reorder=true, sheet=true, labels={on: "Attivo"})');
  const texts = (type) => nodes.filter((node) => node.type === type).map((node) => node.text);
  // The answers of the confirm, the button that opens the sheet, the add button in the sheet, and the captions of the row controls.
  expect(texts("button")).toEqual(["Sì", "No", "Aggiungi", "Aggiungi"]);
  expect(nodes.find((node) => node.type === "bottomSheet").text).toBe("Aggiungi");
  // A row control is named by labels=, else by the readable name of its field, never by its line.
  expect([...texts("checkbox"), ...texts("toggle")]).toEqual(["Done", "Attivo"]);
  expect(texts("icon").sort()).toEqual(["Meno", "Più", "Rimuovi"]);
  // The arrows of reorder=true are the reorder of the list, which Alphy names with its own words.
  expect(nodes.find((node) => node.type === "list").reorder).toBeDefined();
  // Alphy draws the close button of a sheet with its own word, so the part writes none.
  expect(nodes.filter((node) => node.type === "icon" && node.name === "Close")).toEqual([]);
  const english = await built('app("Shop", lang="it")', 'need items = entries("Prodotti", fields={name: "text"}, addText="Metti")');
  expect(english.nodes.filter((node) => node.type === "button").map((node) => node.text)).toEqual(["Metti"]);
  // The parts of Task 11b-2b take their words through the same table.
  const timer = await built('app("Timer", lang="de")', 'need t = timer("Training", 40, rounds=2, rest=10)');
  expect(timer.nodes.filter((node) => node.type === "button").map((node) => node.text)).toEqual(["Starten", "Pause", "Zurücksetzen"]);
});

test("words: no icon button announces the name of its line", async () => {
  const { nodes } = await built('app("Count", lang="pl")', 'need cups = counter("Kubki", 0, min=0)', 'need items = entries("Rzeczy", fields={name: "text", qty: "count"}, reorder=true)');
  const icons = nodes.filter((node) => node.type === "icon");
  expect(icons.length).toBeGreaterThan(0);
  for (const icon of icons) expect([icon.name, /_| (minus|plus|remove|up|down|close)$/.test(icon.text)]).toEqual([icon.name, false]);
  expect(icons.map((icon) => icon.text)).toEqual(expect.arrayContaining(["Mniej", "Więcej", "Usuń"]));
});

test("words: a language that the table does not have gets the English words and one note that names it", async () => {
  const { nodes, notes } = await built('app("Handla", lang="sv")', 'need items = entries("Varor", fields={name: "text"}, confirm="Ta bort?")', 'need t = timer("Te", 60, rounds=2)');
  expect(nodes.filter((node) => node.type === "button").map((node) => node.text)).toEqual(["Yes", "No", "Add", "Start", "Pause", "Reset"]);
  expect(notes.filter((note) => note.includes('"sv"'))).toEqual([
    'the kit has no words of the language "sv", so the parts wrote these captions in English: need items yes=, no=, addText=; need t restText=, workText=, start=, pause=, reset=, done=. Give each one in the language of the app']);
  // A language of the table, or the author's own caption for each option, gives no note.
  expect((await built('app("Handla", lang="sv")', 'need items = entries("Varor", fields={name: "text"}, addText="Lägg till")')).notes).toEqual([]);
});

test("words: each language of Alphy has each word of the table, and the run-time text holds none of them", async () => {
  expect(Object.keys(WORDS).sort()).toEqual(["de", "es", "fr", "it", "ja", "ko", "pl", "pt", "ru", "tr", "uk", "zh"]);
  const english = Object.keys(WORDS.ru);
  for (const [language, words] of Object.entries(WORDS)) expect([language, Object.keys(words)]).toEqual([language, english]);
  const text = await runtimeText();
  for (const word of ["Добавить", "Aggiungi", "Hinzufügen", "Rimuovi", "Zurücksetzen", "ポイント"]) expect(text.includes(word)).toBe(false);
});
