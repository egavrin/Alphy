// The held-out sets of the template check (stage templates plan, Task 7): their shape, not their answers.

import { expect, test } from "bun:test";
import { existsSync, readdirSync, readFileSync } from "node:fs";

const read = (name) => JSON.parse(readFileSync(new URL(`./fixtures/${name}`, import.meta.url), "utf8"));
const BASES = new URL("./fixtures/template-bases/", import.meta.url);
const words = (text) => text.trim().split(/\s+/).length;

test("theHoldoutSetHas16ExplainAnd8LearnSpokenRequestsWithNewTexts", () => {
  const items = read("template-holdout.json"), tuning = read("template-tuning.json").map(({ request }) => request.trim());
  expect(items.length).toBe(24);
  expect(items.filter(({ type }) => type === "explain").length).toBe(16);
  expect(items.filter(({ type }) => type === "learn").length).toBe(8);
  expect(new Set(items.map(({ id }) => id)).size).toBe(24);
  for (const { id, request } of items) {
    expect([id, /^u-[a-z]+$/.test(id)]).toEqual([id, true]);
    expect([id, tuning.includes(request.trim())]).toEqual([id, false]);
    expect([id, words(request) >= 5 && words(request) <= 20]).toEqual([id, true]);
  }
  // A request with no Cyrillic letter is in English.
  expect(items.filter(({ request }) => !/[а-яё]/i.test(request)).length).toBeGreaterThanOrEqual(4);
});

test("theChangeSetHas2RequestsOnEachOfThe8BasesAndAtLeast13FitInLines", () => {
  const items = read("template-changes.json"), bases = readdirSync(BASES).filter((name) => name.endsWith(".plan")).sort();
  expect(bases.length).toBe(8);
  expect(items.length).toBe(16);
  expect(new Set(items.map(({ id }) => id)).size).toBe(16);
  for (const { id, base, request, expect: kind } of items) {
    expect([id, existsSync(new URL(base, BASES))]).toEqual([id, true]);
    expect([id, id.startsWith(`v-${base.replace(/\.plan$/, "")}-`)]).toEqual([id, true]);
    expect([id, ["lines", "outside"].includes(kind)]).toEqual([id, true]);
    expect([id, request.trim().length > 0]).toEqual([id, true]);
  }
  for (const base of bases) expect([base, items.filter((item) => item.base === base).length]).toEqual([base, 2]);
  expect(items.filter((item) => item.expect === "lines").length).toBeGreaterThanOrEqual(13);
});
