# Alphy UI roadmap

This roadmap assigns the approved [Alphy UI design](../specs/2026-11-03-alphy-ui-design.md) to 15 plans.
Each plan delivers software that its tests prove. The plan numbers identify scope. The dependencies determine execution order.

[Plan 1](2026-11-03-alphy-ui-01-foundation-shell.md) is complete. GitHub published [prerelease v0.1.0-alpha.2](https://github.com/egavrin/Alphy/releases/tag/v0.1.0-alpha.2) for revision `688ea62`.
The downloaded APK passed all four `DesktopWorkflowTest` cases on `emulator-5556`. Workspace evidence is in `evidence/alphy-ui/alpha2-release/device-run.json`.

The full device run on `688ea62` recorded 328 passing tests and two skipped diagnostics. Workspace evidence is in `evidence/alphy-ui/p1-release-full/run.json`.
The release and device test complete the prerequisite for the saved mini-app gap work.

## Plans

| Plan | Scope | Spec | Starts after |
| --- | --- | --- | --- |
| 1. Foundation and shell | Device test runner, dependencies, Russian locale and string resources, Expressive theme, route back stack, top bar and drawer, desktop with greeting and start suggestions, settings with consent at key entry, search, access page | 3, 5.1 (frame), 5.9 (key entry), 5.11, 5.12 (theme, shell icons), 7, 9 | Complete |
| 2. Chat | Message cards, answer text in parts, cancel and retry texts, search results, chips as the explicit step, user text at 4.5:1 | 5.2, 5.9 (chips) | 1 |
| 3. Mini-apps in the chat | Cards in the chat, library rows with search, thumbnail, version, and place, color roles in place of `palette` and `mode`, revision label and time, delete, change queue, confirmation sheet for deletion, ToolUi strings, gallery debug activity | 5.3, 5.11 (library), 5.12 (generated items), 8 | 2 |
| 4. Desktop tiles and widgets | «Мой стол» grid, «Изменить», live tiles, Glance widgets, local «Сейчас» items such as the next event and a stopped automation | 5.1 (grid), 5.4 | 3 |
| 5. Generation route | Parts-first generation with DeepSeek in the Jev role, validated previews, measured budgets, correction for truncated output, game and embedded scene checks, Russian output, measurement, the debug TypeSafe slot | 6, 7 (debug credentials), 9 | 3 |
| 6. Automations | Editable automation card, «Включить», the DeepSeek step | 5.6 | 3, 5 |
| 7. Memory and local agent | «Память» page and drawer row, memory facts, the name from «Память» in the greeting and the drawer profile, agent plan card and functions | 5.1 (name source), 5.7, 5.10 | 2, 6 |
| 8. Courses and lessons | «Обучение» page and drawer row, course outline, lesson player, generated slides, lesson items in «Сейчас» | 5.5 | 3, 5 |
| 9. Assistant half sheet | The assistant button sheet and the CompactAssistant texts, a debug-only model factory for `AssistantChat`, device tests of the half sheet and its `FLAG_SECURE` visual check | 5.8, 9 (half-sheet visual check) | 2, 3 |
| 10. Large screens and Fold | Two-pane layouts and Fold postures | 5.13 | 4 |
| 11. Runtime capacity and interaction | Several active mini-apps, measured runtime and content limits, bitmap drawing, live input, live drawing preview, drag reorder, route stacks, more chart types | 3, 5.3, 6.6, 9 | 3, 5 |
| 12. Network, files, and web | One revocable network grant per mini-app, public and local requests, redirects and subresources, file content and transfers, web content, URL images, Markdown, access page | 3, 5.3, 5.9, 5.11, 7–9 | 11 |
| 13. Device, media, and schedules | Device status, location, sensors, audio and video, timer effects, Android permission and picker flows, background suspension, explicitly activated scheduled actions | 3, 5.3, 7–9 | 6, 12 |
| 14. Mini-app AI | Host AI through existing provider key consent, data transfer from Android-accessible sources, operation independent of the network grant, credential isolation | 5.3, 5.9, 7–9 | 13 |
| 15. Portability and manual editing | Explicit export with data selection, Android share, validated import, manual editing, preservation of existing data | 5.3, 8, 9 | 14 |

Write each detailed implementation plan against the code after its prerequisites merge. Plans 4 and 5 can start after Plan 3.
Plan 5 uses Plan 3's gallery. Plans 6 and 8 use the generation route. Plan 9 uses the shared chat card.
Plan 11 waits for the chat mini-apps and the generation route. Plans 12–15 follow the shared host interfaces in order.
Run the final comparison after Plans 2–15 meet their completion criteria. [Design comparison gate](../specs/2026-11-03-alphy-ui-design.md#68-measurement)

## Saved mini-app gaps and checks

This table assigns each gap from the 2026-11-04 comparison and the user's added list. The comparison read source at Alpha `7ae425a` and deal-studio-ohos `7ee06af1`.
It ran no generations. The earlier numeric limits describe that baseline. Plans 5 and 11 must measure the new bounds.

| Owner | Gap | Required evidence |
| --- | --- | --- |
| 3 | Library deletion | Confirm deletion, remove only the selected app and its listed data and bindings, and stop dependent automations. Preserve data on cancel. |
| 5 | Small output budget and uncorrected `length` result | Record the measured token and byte budgets. Show a bounded correction, rejection of invalid output, and preservation of the last good version. |
| 5 | Games and embedded scenes without a gameplay check, and English text in generated mini-apps | Run frames and inputs through the generation smoke check. Verify request-specific game rules, including applicable scoring, collision or other result logic, terminal conditions, and restart. Include a non-Arkanoid game, an interactive embedded scene, and Russian output for Russian requests. Count gameplay checks in the comparison's functional no-regression gate. |
| 11 | One open mini-app, 256 nodes, depth 8, 4 images, 512 drawing commands per frame, no bitmaps, and 48 KiB state | Measure supported bounds for concurrent apps, nodes, depth, images, drawing, source, UI, and state. Verify isolation, bounded overload, state consistency, and frame behavior. |
| 11 | Text fields and sliders apply only on demand, drawing area waits for «Apply changes», and line and bar charts only | Show bounded live input, a visible drawing area before Apply, additional charts, drag reorder, and route-stack navigation. Verify Russian text in the host controls. |
| 12 | No network, file content, web content, URL images, or Markdown | Test the single grant and revocation during use. Cover public and local requests, redirects, subresources, picker-scoped files, data transfer, and bounded web content. |
| 13 | No device status, location, sensors, audio, video, or timer effects | Demonstrate the device and media interfaces. Test Android denial and recovery, bounded audio and video, and rate-limited timer vibration and notifications. |
| 13 | No background execution | Verify suspension of general code and execution of explicitly activated scheduled actions within Android limits. Verify schedule changes require reactivation. |
| 14 | No mini-app AI | Exercise host AI with the network grant off and on. Verify use of Android-accessible data, operation under existing provider consent, and key removal. |
| 15 | No share, export, import, or manual edit | Test export data selection, share, a valid import, invalid input, conflicts, and manual edit. Verify no grants or credentials travel and existing data stays intact. |

## Done criteria for every plan

- Each applicable design requirement or choice has a device test, a host test, or a reason recorded in the plan.
- The full device suite has no failure outside the baseline that the plan records at its start.
- The plan moves the UI text of each screen that it rewrites into string resources.
- `:app:lintDebug` reports 0 errors.
- Evidence follows the execution rules of Plan 1. Each run records the revision, command, inputs, expected result, and observed result.
- Behavior checks use complete workflows. Preserve the last good app and user data on failure or cancellation.
- The final comparison pins both sources and uses three independent real generations per request and product. See design section 6.8 for the corpus, metrics, and gate.

## What plan 1 gives to later plans

| Item | Later use |
| --- | --- |
| `ShellRoute` and `ShellDestination` | Plan 7 adds the «Память» destination. Plan 8 adds «Обучение». |
| `ShellDrawerSheet` | Plans 7 and 8 add their rows. A row comes only with its page, because a row without a page is a dead end (spec 2.3). |
| `cloudKey(context)` | The host enforces provider key consent through this function. Plan 14 uses the existing consent for mini-app AI. |
| `KeyStatus` and the `consent_text` resource | Plans 6 and 7 build on the consent text. Plan 14 explains the scope of host AI under existing provider key consent. |
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
3. Chat, local agent, and automation requests keep their explicit data steps. A generated refinement sends the declared spec and initial values (spec 5.9 and 6.3).
4. A mini-app's one network grant covers public and local destinations, redirects, and subresources. It permits transfer of Android-accessible device data. «Доступ и функции» explains that scope and offers revocation.
5. Mini-app device and AI operations require no Alphy grant or confirmation. Android permissions and pickers apply. Host AI works independently of the network grant under existing provider key consent.
6. General mini-app code stops in the background. Only explicitly activated scheduled actions can run there under Android limits. Changed schedules require reactivation.
7. The host mediates operations and validates inputs, work limits, and results. Delete confirmation stays. Export requires data selection and excludes credentials and grants.
8. BuildConfig contains no provider key. Tests use fixtures. Real runs use the one-use channel in the [Compose acceptance procedure](../../parity/compose-acceptance.md).

## Baseline changes

1. Plan 3 retains the confirmation sheet for deletion. The original mini-app operation confirmation sheet is obsolete under the approved access rules.
2. Plan 1 omits «Обучение» and «Память» until Plans 8 and 7 supply their pages (spec 5.11).
3. The earlier work-in-progress failures were historical. The release run above supplies the current baseline.

## Coverage of the design

| Spec section | Plans |
| --- | --- |
| 1 Intent, 2 Goals, 4 Product shape | All |
| 3 Constraints | 1 (platform facts and the device rules), all |
| 5.1 Shell and desktop | 1 (frame, greeting, start suggestions), 4 (grid, tiles, local items), 7 (name from «Память»), 8 (lesson items) |
| 5.2 Chat | 2 |
| 5.3 Mini-apps | 3 (chat, versions, library and delete), 11–15 (runtime, access, AI, and portability) |
| 5.4 Tiles and widgets | 4 |
| 5.5 Courses | 8 |
| 5.6 Automations | 6 |
| 5.7 Local agent | 7 |
| 5.8 Assistant button | 9 |
| 5.9 Consent | 1 (key entry, consent text, key deletion), 2 (chips), 6 («Включить»), 7 (memory steps), 12 (network grant), 14 (host AI) |
| 5.10 Memory | 7 |
| 5.11 Menu and settings | 1, 3 (library rows), 7 («Память» row), 8 («Обучение» row), 12 (network grant controls), 14 (host AI information) |
| 5.12 Visual style | 1 (theme, fixed palette, shell icons), 3 (generated items, color roles, canvas colors, card frame) |
| 5.13 Large screens | 10 |
| 6 Generation route | 5 (route and measurement), 11 (measured runtime limits), all applicable plans (final comparison) |
| 7 Platform | 1 (Material 3, Navigation 3, Back, compileSdk, key storage), 4 (Glance), 5 (TypeSafe slot), 10 (adaptive layout, WindowManager), 11–14 (runtime and host interfaces) |
| 8 Data | 1 (consent record, user name), 3 (revisions, delete), 4 (desktop layout), 5 (part IDs), 6 (automation delete), 7 (memory facts), 8 (courses), 12 (network grant), 13 (schedule activation), 15 (imports and edits) |
| 9 Quality | 1 (runner, selectors, contrast, touch targets, `FLAG_SECURE` method), 2 (live regions), 3 (gallery), 9 (half-sheet visual check), 5 and 11–15 (gap checks and comparison) |
| 10 Choices | 1 (4, 13), 3 (3, 6, 8, 9, 19), 5 (18), 6 (2, 11), 7 (1, 5, 7), 8 (10), 11 (19), 12 (14), 13 (15, 16), 14 (15), 15 (17). Item 12 retains current voice. |
| 12 Risks | 1 (historical work state), 3 (beta Slider), 5 (route and comparison), 11 (capacity), 12 (revocation), 13 (permissions and schedules), 15 (stored data) |
