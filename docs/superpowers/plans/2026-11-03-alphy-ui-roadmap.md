# Alphy UI roadmap

Status: written on 2026-11-03 for the approved design, revision 2.

The [Alphy UI design](../specs/2026-11-03-alphy-ui-design.md) is too large for one implementation plan.
This roadmap divides it into 10 plans. Each plan delivers software that its own device tests prove.
[Plan 1](2026-11-03-alphy-ui-01-foundation-shell.md) is complete.
Write each later plan after the previous plan merges, from the code at that time.

## Plans

| Plan | Scope | Spec | Starts after |
| --- | --- | --- | --- |
| 1. Foundation and shell | Device test runner, dependencies, Russian locale and string resources, Expressive theme, route back stack, top bar and drawer, desktop with greeting and start suggestions, settings with consent at key entry, search, access page | 3, 5.1 (frame), 5.9 (key entry), 5.11, 5.12 (theme, shell icons), 7, 9 | Unresolved decision 1 in plan 1 |
| 2. Chat | Message cards, answer text in parts, cancel and retry texts, search results, chips as the explicit step, user text at 4.5:1 | 5.2, 5.9 (chips) | 1 |
| 3. Mini-apps in the chat | Cards in the chat, library rows with search, thumbnail, version, and place, color roles in place of `palette` and `mode`, revision label and time, delete, change queue, confirmation sheet, ToolUi strings, gallery debug activity | 5.3, 5.11 (library), 5.12 (generated items), 8 | 2 |
| 4. Desktop tiles and widgets | «Мой стол» grid, «Изменить», live tiles, Glance widgets, local «Сейчас» items such as the next event and a stopped automation | 5.1 (grid), 5.4 | 3 |
| 5. Generation route | The section 6 route with DeepSeek in the Jev role, validation, measurement, the debug TypeSafe slot | 6 | 1 |
| 6. Automations | Editable automation card, «Включить», the DeepSeek step | 5.6 | 3 |
| 7. Memory and local agent | «Память» page and drawer row, memory facts, the name from «Память» in the greeting and the drawer profile, agent plan card and functions | 5.1 (name source), 5.7, 5.10 | 2, 6 |
| 8. Courses and lessons | «Обучение» page and drawer row, course outline, lesson player, generated slides, lesson items in «Сейчас» | 5.5 | 3, 5 |
| 9. Assistant half sheet | The assistant button sheet and the CompactAssistant texts, a debug-only model factory for `AssistantChat`, device tests of the half sheet and its `FLAG_SECURE` visual check | 5.8, 9 (half-sheet visual check) | 2 |
| 10. Large screens and Fold | Two-pane layouts and Fold postures | 5.13 | 4 |

Plan 5 can run in parallel with plans 2 to 4, because it changes the generation service and not the shell.

## Done criteria for every plan

- Each (User), (Claude), and (Claude, new) line of the plan's spec sections has a device test, a host test, or a recorded reason in the plan.
- The full device suite has no failure outside the baseline that the plan records at its start.
- The plan moves the UI text of each screen that it rewrites into string resources.
- `:app:lintDebug` reports 0 errors.
- Evidence follows the execution rules of plan 1.

## What plan 1 gives to later plans

| Item | Later use |
| --- | --- |
| `ShellRoute` and `ShellDestination` | Plan 7 adds the «Память» destination. Plan 8 adds «Обучение». |
| `ShellDrawerSheet` | Plans 7 and 8 add their rows. A row comes only with its page, because a row without a page is a dead end (spec 2.3). |
| `cloudKey(context)` | Every DeepSeek request in plans 2 to 9 reads the key through this function. It enforces the consent. |
| `KeyStatus` and the `consent_text` resource | Plans 6 and 7 build on the consent text. Plan 1 writes the full list from spec 5.9, so no later plan changes the consent. |
| `DesktopPane` | Plan 4 adds «Мой стол» and the local «Сейчас» items. Plan 8 adds lesson items. |
| `Composer` | Plan 2 changes the cards and the chips, not the composer frame. |
| `AlphaTheme`, `alphaColorScheme`, `Glyph` | Plan 3 maps mini-app color roles to the scheme. Plans 3 to 10 use one icon set. |
| `StatusLine` and the `color` parameter of `AlphaIcon` | Each status in plans 2 to 10 shows an icon, a color, and text (spec 5.12). |
| `KEY_ERRORS` and «Открыть настройки» | Each new surface of a DeepSeek error in plans 2 to 9 gives the same next step. |
| `ShellRobot`, `ShellFixture`, `FixtureProvider` | All later device tests. |
| `run.py suite` and `run.py host` | All later device runs, host runs, and evidence. |

## Rules that cross plans

1. The «Сейчас» strip uses local data only (spec 5.1 and section 10, item 4). Plans 4 and 8 keep this rule.
2. Plan 3 creates the gallery debug activity for the mini-app color roles. Plans 5 and 8 add their generated screens to it. Spec section 9 requires 4.5:1 body text contrast "in every gallery scheme".
3. Plan 3 checks again what a mini-app change request sends to DeepSeek. If the request sends data that the consent text does not name, plan 3 stops and reports it before any code change.
4. A plan that adds a data class outside the consent text must ask the user how Alphy gets consent for it. Spec 5.9 says "No message asks again."

## Deviations from the design

1. Debug key bootstrap (spec section 7, row "Debug key bootstrap"). The roadmap drops this row. A BuildConfig field puts the key in a generated file, and `docs/parity/compose-acceptance.md:29` forbids that ("The runner never executes the dotenv file or puts a key in arguments, logs, generated files, or evidence."). Tests use fixture keys. Real runs use the one-use channel of the acceptance runner. Unresolved decision: the user must confirm this drop before plan 5. It blocks nothing in plan 1.
2. Work state (spec section 3). The spec says "Its repair run at 12:02 failed 3 of 3." A later full run at 13:02:52 had 289 tests and 2 failures:
   - `MiniAppPresentationWorkflowTest#cancelThenErrorThenRetryPreservesAppAndComposer`
   - `MiniAppPresentationWorkflowTest#savedGeneratedArkanoidTouchMovesVisiblePaddle`

   Plan 1 Task 0 records a new baseline on the base commit.
3. Drawer rows. Spec 5.11 lists «Обучение» and «Память» in the menu. Plan 1 omits both rows until plans 8 and 7 build their pages (rule 1 in the table above).

## Coverage of the design

| Spec section | Plans |
| --- | --- |
| 1 Intent, 2 Goals, 4 Product shape | All |
| 3 Constraints | 1 (platform facts and the device rules), all |
| 5.1 Shell and desktop | 1 (frame, greeting, start suggestions), 4 (grid, tiles, local items), 7 (name from «Память»), 8 (lesson items) |
| 5.2 Chat | 2 |
| 5.3 Mini-apps | 3 |
| 5.4 Tiles and widgets | 4 |
| 5.5 Courses | 8 |
| 5.6 Automations | 6 |
| 5.7 Local agent | 7 |
| 5.8 Assistant button | 9 |
| 5.9 Consent | 1 (key entry, consent text, delete), 2 (chips), 6 («Включить»), 7 (memory steps) |
| 5.10 Memory | 7 |
| 5.11 Menu and settings | 1, 3 (library rows), 7 («Память» row), 8 («Обучение» row) |
| 5.12 Visual style | 1 (theme, fixed palette, shell icons), 3 (generated items, color roles, canvas colors, card frame) |
| 5.13 Large screens | 10 |
| 6 Generation route | 5 |
| 7 Platform | 1 (Material 3, Navigation 3, Back, compileSdk, key storage), 4 (Glance), 5 (TypeSafe slot), 10 (adaptive layout, WindowManager) |
| 8 Data | 1 (consent record, user name), 3 (revision label and time, tool delete), 4 (desktop layout), 5 (part IDs), 6 (automation delete), 7 (memory facts), 8 (courses) |
| 9 Quality | 1 (runner, resource-based tests, theme contrast, touch targets, `FLAG_SECURE` method), 2 (no live region per part), 3 (gallery), 9 (half-sheet visual check) |
| 10 Choices | 1: items 4 and 13. 3: items 3, 6, 8, and 9. 6: items 2 and 11. 7: items 1, 5, and 7. 8: item 10. Item 12 needs no work. |
| 12 Risks | Plan 1 Task 0 handles the risks of the other session. Plan 5 handles the route risks. Plan 3 handles the beta Slider risk. |
