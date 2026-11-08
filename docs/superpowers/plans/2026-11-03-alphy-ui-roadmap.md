# Alphy UI roadmap

This roadmap assigns the approved [Alphy UI design](../specs/2026-11-03-alphy-ui-design.md) to 15 plans.
Each plan delivers software that its tests prove. The plan numbers identify scope. The dependencies determine execution order.

[Plan 1](2026-11-03-alphy-ui-01-foundation-shell.md) is complete. GitHub published [prerelease v0.1.0-alpha.2](https://github.com/egavrin/Alphy/releases/tag/v0.1.0-alpha.2) for revision `688ea62`.
The downloaded APK passed all four `DesktopWorkflowTest` cases on `emulator-5556`. Workspace evidence is in `evidence/alphy-ui/alpha2-release/device-run.json`.

The full device run on `688ea62` recorded 328 passing tests and two skipped diagnostics. Workspace evidence is in `evidence/alphy-ui/p1-release-full/run.json`.
The release and device test complete the prerequisite for the saved mini-app gap work.

## Acceptance status on 2026-11-06

Plans 1 and 2 have passed acceptance. The other plans have work in the local checkout. Their completion criteria and the release gate remain open.

The local Android build passed four focused workflows on [API 36](../../../evidence/alphy-ui/finish-widget-timer-last/run.json) and [API 29 with WebView 133](../../../evidence/alphy-ui/finish-api29-compact-final/run.json). These workflows cover foreground timer alerts, mini-app AI, widget layout editing, and manual live JSON refresh. [Lint](../../../evidence/alphy-ui/finish-widget-timer-last/lint.log) reported zero errors. The earlier [47-case run](../../../evidence/alphy-ui/finish-android-acceptance-recheck/run.json) passed all targeted workflows, including the five cases that failed in the [initial full run](../../../evidence/alphy-ui/finish-roadmap-baseline/run.json).

The [full API 36 baseline](../../../evidence/alphy-ui/alpha3-baseline-api36/run.json) reached all 883 cases. It recorded 832 passes, 48 failures, and three skips. Seventeen failures occurred before calendar fixtures could start because the fresh emulator lacked calendar permissions. A [focused API 36 run](../../../evidence/alphy-ui/alpha3-network-trace-recovery-api36/run.json) passed 34 of 35 checks after the calendar grants and three saved-draft fixture updates. The remaining [network settings remount check](../../../evidence/alphy-ui/alpha3-network-remount-api36/run.json) passed after the fixture waited for the new screen. A new full run must still pass on the final candidate.

The [focused API 29 suite](../../../evidence/alphy-ui/alpha3-focused-api29/run.json) passed all 30 checks. The [immersive and schedule authority suite](../../../evidence/alphy-ui/alpha3-immersive-authority-api29/run.json) passed another 16 checks. The [Gecko API 29 probe](../../../evidence/alphy-ui/alpha3-gecko-accepted-focus-api29/instrumentation.txt) passed the automated accessibility checks. After the host stopped forwarding delayed focus-clear events for a previous node, [TalkBack traversed the remote page and activated its button](../../../evidence/alphy-ui/alpha3-gecko-accepted-focus-talkback-api29/events-verified.txt). The combined browser still needs keyboard, accessibility, and isolation checks with two sessions. Live source discovery and scheduled refresh also remain open.

Android 16 (API 36) is the minimum supported version for the prerelease. Set `minSdk` to 36 on the release candidate. Run the full emulator suite and the alpha.2 upgrade check on API 36. Embedded browsing is deferred. Run the combined two-session browser workflow on API 36 before enabling it. The earlier API 29 results remain historical evidence.

The live functional comparison with the Deal Studio host runtime is deferred from the prerelease. The pinned reference `main` is `26eaacccc8ec4ce5376fecedb0a6f821ccefd561`. The frozen English matrix requires 726 real generations across both products, plus the Russian and capability cases. Three consecutive live calculator runs passed on the same APK pair. Native HarmonyOS parity and physical-device checks remain separate. [Calculator run evidence](../../../evidence/alphy-ui/live-tip-controller-proof-1/sequence.json)

The [acceptance inventory](../../../evidence/alphy-ui/alpha3-roadmap-status/criteria.md) records the current checks by plan. The [Deal Studio pilot](../../../evidence/alphy-ui/alpha3-reference-tip-pilot/functional-proof.json) passed the tip-calculator interaction in the reference host runtime. The Alphy pilot still needs a successful generated app for that request.

## Emulator acceptance on 2026-11-08

Plans 6–9 are closed for Android 16 emulator acceptance. Physical-device verification is separate.

| Plan | Verified behavior |
| --- | --- |
| 6. Automations | The live 07:00 request opens a review before activation. The exact alarm delivers a prepared result once while the emulator is offline. |
| 7. Memory and local agent | The name can be edited in Memory. Phone actions show their effects before approval. The emulator checks calendar permissions, one recurring occurrence, stale targets, Stop, interrupted execution, and the Clock handoff. |
| 8. Courses and lessons | The live Russian alphabet lesson completed all five slides. It preserved progress on reopen and graded the second quiz choice correctly. The final course change passed 76 course cases and five wide-window cases. |
| 9. Assistant half sheet | The Android Assistant role and Quick Settings tile open the sheet. A fresh screenshot waits for Send. Reviewed text survives typing, and the full app can continue the same chat. |

The broad run accounted for 1,040 cases, including 19 skips. Pinned `main` revision `75cb9b7` reproduced 21 of the 24 initial failures. The large gallery case hit the same date-range and canvas failures reproduced in its baseline subset. The two remaining desktop cases passed on the final build. The offline partition passed the exact alarm that requires an offline device.

The final application source SHA-256 is `d5740fa3ed7ba35686fe38ebe964245233a5bc4dab6295ac889a58bbeff0049e`. The last code change affected only answer submission in the course player. The targeted course suite, wide-window suite, and live lesson ran after that change. Lint reported zero errors. The host suite passed 26 of 27 cases. Its catalog checksum failure also occurs on `75cb9b7`. Workspace evidence is in `alphy-plans-6-9-closure/acceptance-summary.json`.

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
| 9. Assistant half sheet | Assistant and Quick Settings entry, fresh screenshot review with explicit Send, shared chat and full-app handoff, and device tests of the half sheet | 5.8, 9 (half-sheet visual check) | 2, 3 |
| 10. Large screens and Fold | Two-pane layouts and Fold postures | 5.13 | 4 |
| 11. Runtime capacity and interaction | Several active mini-apps, measured runtime and content limits, bitmap drawing, live input, live drawing preview, drag reorder, route stacks, more chart types | 3, 5.3, 6.6, 9 | 3, 5 |
| 12. Network, files, and web | One revocable network grant per mini-app, public and local requests, redirects and subresources, file content and transfers, web content, URL images, Markdown, access page | 3, 5.3, 5.9, 5.11, 7–9 | 11 |
| 13. Device, media, and schedules | Device status, location, sensors, audio and video, timer effects, Android permission and picker flows, background suspension, explicitly activated scheduled actions | 3, 5.3, 7–9 | 6, 12 |
| 14. Mini-app AI | Host AI through existing provider key consent, data transfer from Android-accessible sources, operation independent of the network grant, credential isolation | 5.3, 5.9, 7–9 | 13 |
| 15. Portability and manual editing | Explicit export with data selection, Android share, validated import, manual editing, preservation of existing data | 5.3, 8, 9 | 14 |

Write each detailed implementation plan against the code after its prerequisites merge. Plans 4 and 5 can start after Plan 3.
Plan 5 uses Plan 3's gallery. Plans 6 and 8 use the generation route. Plan 9 uses the shared chat card.
Plan 11 waits for the chat mini-apps and the generation route. Plans 12–15 follow the shared host interfaces in order.
When the deferred live host comparison resumes, keep the native comparison separate and apply all its evidence requirements. [Design comparison gate](../specs/2026-11-03-alphy-ui-design.md#68-measurement)

Plan 6's scheduled calendar import uses a separate local read with event IDs. `ScheduledCalendarWorkflowTest` passed with provider-shaped calendar results and kept distinct recurring instances and existing completion state. The normal `calendar.read` result still omits event IDs.

Plan 12 sends foreground mini-app requests through the host network authority. The transport cancels a pending Android DNS query when the grant stops or the request deadline expires. It keeps `OPTIONS` on 307 and 308 redirects and changes it to `GET` on 303. The focused runs on API 29 and API 36 verified these paths.

## Deferred Plan 12 scenario for repair booking

A repair company accepts bookings through a form on its website. The mini-app keeps the user's repair notes next to the «Записаться» action.

1. The user opens the company's website inside the mini-app.
2. The user signs in, chooses an available time on the site's calendar, and submits the site's booking form.
3. The user closes the page and returns to their repair notes.

The website session belongs to the mini-app. A second mini-app can open the same site with a separate session and separate cookies and storage. Alphy applies the app's network grant to page requests and subresources. Web content stays isolated from Android APIs, Alphy's provider credentials, and other mini-apps' state.

Before enabling the feature, verify both sessions together on Android 16. Exercise keyboard input, TalkBack, storage isolation, network mediation and revocation, and renderer or broker failure. [Web access design](../specs/2026-11-03-alphy-ui-design.md#53-mini-apps-in-the-chat)

## Generated-app visual quality and reference updates

The generated mini-apps and lesson slides must look designed and must meet the visual no-regression gate against `deal-studio-ohos`. Add this work to Plans 3, 5, 8, and 11. Improve the application shell where it affects the presentation of generated work, including chat cards, previews, desktop tiles, widgets, and the full-screen host.

Mock fidelity is a separate release requirement alongside functional comparison. Compare the actual Alphy shell with the Alphy concept and compare generated screens with the applicable mini-app references. Use native Android 16 captures for the shell and representative live-generated calculator, timer, budget, and reading screens. Check clear hierarchy, compact input controls, consistent typography and spacing, and localized labels. Include the default viewport, input editing, and results. Check the existing light, dark, narrow, wide, and enlarged-text conditions. [Alphy concept](../specs/assets/alphy-character-concept/home-screen.png) · [Mini-app reference comparison](../../../evidence/alphy-ui/reference-android-fixtures/updated-side-by-side.png)

1. **Record the reference.** Before each comparison, record the local and remote reference revisions, comparison date, source status, and applicable features. Compare the previously pinned revision with the latest available reference revision. Inventory new or changed generation, component, interaction, and visual capabilities. Keep the reference worktree and its uncommitted changes intact; use a pinned, isolated source snapshot for repeatable runs. If the remote reference cannot be checked, record that limit and do not claim comparison with the latest version.
2. **Close the composition gap.** Make parts-first and model-filled screens use a coherent visual hierarchy: a clear focal point, title and supporting text, appropriate sections and spacing, distinct primary and secondary actions, readable lists, and useful empty and status states. Carry the intended theme and layout through preview, saved state, refinement, and supported compact surfaces. Do not add duplicate titles or overwrite user data. Preserve the host's validation, accessibility, Android theme and wallpaper colors, and permission boundaries.
3. **Compare actual screens.** Use matching counter or timer, list or budget, and reading or habit workflows. Capture the production renderer before and after changes in full-screen and embedded views, at narrow and wide sizes, in light and dark modes, and with enlarged text. Check interaction results and text visibility as well as screenshots. Label hand-authored fixtures, generated outputs, simulated layouts, and native device captures separately.
4. **Update the gap list.** Classify reference changes as already supported, requiring Alphy work, or needing a product, security, or platform decision. Add accepted missing features and their tests to the owning plan before implementation. Do not silently change approved access rules or treat a moving reference as an excuse to change the target during a measured run.
5. **Run the final gate.** Pin both products and provider conditions for each run. Use the approved corpus and independent generations in section 6.8, including the held-out set. Compare visual quality and function by category, retain failures, and report the reference revision and any newer changes not yet incorporated. A polished fixture or isolated screenshot does not establish generated UI parity.

The current source comparison used `deal-studio-ohos` revision `7ee06af1941729b00a110493ad9d1d9b5b127d17`. Its existing visual sheet uses hand-authored fixtures and an earlier renderer with estimated SVG layout; it is useful design evidence, not a native capture of that revision or proof of generated-app quality. Recheck the reference before the final gate.

On 2026-11-05, remote `main` had advanced to `fe33246e604327c14f8e24a98e34d2f9f03060f5`. An isolated source comparison identified these additional items for detailed planning and verification:

| Owner | Reference change | Alphy work or decision |
| --- | --- | --- |
| 5, 12 | Generate an app around a discoverable live data source and refresh the displayed value with its source and time. | Plan a complete source-to-generated-app workflow, including stale and unavailable states. Verify that a changed response updates the value and time, and that failure preserves the last valid value. Apply Alphy's approved network grant and existing access rules. Decide separately under Plan 14 whether recurring search and model extraction are supported. |
| 4 | Generate or refine a widget presentation for an existing app and preview the widget separately. | Compare this with Alphy's shared-state Glance projection. Decide how to support app-specific widget composition without duplicating state or widening widget action authority. Verify preview, stale-base rejection, and preservation of app state on save. |
| 4, 12, 13 | Refresh a live source or widget on a schedule. | Decide whether a widget shows cached data or can refresh in the background. Define an explicitly activated, Android-compatible schedule and test cancellation, battery limits, grant revocation, stale replies, concurrent app writes, and duplicate updates. General generated code remains suspended in the background. |
| 3, 5, 11 | Cancel overlapping work and ignore stale preview, effect, frame, and navigation results after replacement or closure. | Verify these lifecycle boundaries across chat, generation, previews, multiple hosts, and background transitions. Preserve the last good app and do not replay uncertain effects. |
| 4 | Open a mini-app from a launcher shortcut, including cold, warm, missing-app, and replacement launches. | Compare the existing Android shortcut implementation with the reference and define the intended navigation and Back behavior. Keep Android launcher confirmation and deletion cleanup. |

The reference reports device evidence for a live counter widget and two live-source examples, one observed periodic refresh, and additional host checks with documented limitations. These are reference claims, not Alphy results or proof of complete parity. The reference revision adds no native screenshot artifacts. The comparison did not identify a new reference export/import feature; Plan 15 remains independently required.

Later on 2026-11-05, remote `main` advanced to `26eaacccc8ec4ce5376fecedb0a6f821ccefd561`. An isolated source comparison adds these cases:

- **Plan 5 — Generated workflows:** Compare itinerary, quick log, dose schedule, due items, points ledger, idea deck, interval timer, and reading status. Verify each workflow's requested behavior before choosing reusable parts. A configurable control alone does not prove the generated app works.
- **Plan 5 — Functional acceptance:** Use the Alphy Android emulator and the Deal Studio OHOS host runner for generation and workflow checks. The functional audit is the app-generation gate. Hardware, signing, and native trace evidence belong to a separate device-effect audit. [Comparison runbook](../../../tools/compose_acceptance/comparison/README.md#app-generation-functional-audit)
- **Plans 3, 5, and 11 — Screen quality:** Check named screen order, settings below the primary task, user-supplied initial readings, form-local errors, aligned icon rows, stepped number limits, and readable content at narrow widths and enlarged text.
- **Plan 5 — Generation recovery:** Test long multi-section requests, section failures, bounded correction, and cancellation before fallback. If an incomplete request produces a usable subset, disclose the missing behavior and count it as a comparison miss. Preserve the last good version.
- **Plan 13 — Timer effects:** Verify one requested foreground terminal alert, Android denial, reset, and suspension while hidden. Background work still requires explicit schedule activation.
- **Plan 14 — Runtime AI:** Verify that changed app inputs produce a new requested result. Cover missing key, cancellation, repeat actions, and operation with the mini-app network grant off. Use existing provider consent and keep credentials in the host.
- **Plans 5 and 12 — Source selection:** A local itinerary that names a city but requests no current information must not trigger live-source discovery or fabricate a weather request.

The [source reconstruction](../../../evidence/alphy-ui/reference-source-26eaacc/run.json) uses the compiler, runtime, and visual proof tool from this reference revision. The [before and after sheet](../../../evidence/alphy-ui/reference-source-26eaacc/before-after.png) shows five fixture workflows. The SVG proofs estimate layout. The final comparison still requires native ArkUI captures and matched real generations.

The Android fixture comparison covers the interval timer, budget, and reading screens. Removing the idle edit form increased their content area from 524 to 708 pixels at 360 dp. The explicit edit action and pending draft controls remain available. See the [comparison sheet](../../../evidence/alphy-ui/reference-android-fixtures/updated-side-by-side.png) and [run record](../../../evidence/alphy-ui/reference-android-fixtures/run.json).

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

## Phone-test problems to triage

A separate phone test of Alphy `0.1.0-alpha.2` reported the problems below. The phone was unfolded, and the comparison used ChatGPT `1.2026.300` and Alice AI `26.27.0`. This was one device and a small set of requests, not a performance benchmark. These are observations on the released version; verify each against the current build before changing its status.

### P1 — Fix first

1. **Mini-app storage and recovery:** The phone reported an unsupported storage version. Creating a preview remained blocked, and chat disabled automatic mini-apps. Diagnose the schema and provide a clear, usable recovery path that preserves existing data. Do not reset or delete data silently.
2. **Intermittent empty follow-ups:** Three requests in two chats returned «DeepSeek вернул пустой ответ», while later text-file and image requests succeeded. Capture the provider and client outcomes, reproduce the failure, and fix its cause. The cause is not yet established.
3. **Latest chat content is hard to find:** New messages and errors appeared below the visible chat area. Verify that sending reveals the latest message and provide a visible jump-to-latest action when the user has scrolled away. Preserve deliberate manual scrolling.

Read-only follow-up: the storage screenshot shows error wording that differs from the source at release revision `688ea62`. The displayed app version does not identify the installed APK or the phone's database schema. Preserve the phone data and establish those facts before selecting recovery. The empty-response message identifies a completed stream with no parsed text, but the report contains no provider stream or request-stage record. Do not infer its cause from that message alone.

### P2 — Usability

4. **Retry action:** The offline and empty-response errors had no visible retry action. Offer a retry beside a failed request and verify that it uses the original input without duplicating a completed operation.
5. **Reminder time:** A request for tomorrow at 15:00 produced a preview with a 15:00 event and a 14:50 notification. Nothing was saved. Diagnose the interpretation and show the requested alert time and any assumptions before confirmation.
6. **Reviewed file context:** The assistant used reviewed text extracted from a file but also said it could not see an attached file and asked for it again. Explain to the model that the user supplied reviewed text extracted from a file, without sending unreviewed content.
7. **Markdown tables:** Two requested tables appeared as pipe-separated text. Determine whether the model output lacked valid Markdown table syntax or the renderer failed on valid syntax before assigning the fix.
8. **Russian host text:** The mini-app screen showed English labels, including “Create preview” and “Library”, and chat showed “Tool storage is unavailable”. Localize the affected host controls and explain unavailable storage in user-facing language.
9. **Keyboard and reading space:** The keyboard stayed open after sending, and the composer and short-message bubbles reduced reading space. Assess the layout and the user's preference before forcing the keyboard closed.

### P3 — Polish

10. **Copy discoverability:** Copy was available through a long press, while the compared apps displayed a copy action below the answer. Assess a visible copy action without removing text selection.
11. **Automation time-zone fields:** The scheduled-trigger form exposed values such as `device` and `Europe/Moscow`. Use a readable device-local default for the common case while keeping the selected time zone clear.

Read-only follow-up: the current table renderer displays even valid parsed tables as styled text with literal pipes, rather than laying out cells. The exact Markdown source of the phone replies is still unknown. The reviewed-file path sends extracted text with a trust label but no file-origin label; that is consistent with the observed ambiguity, but does not prove what caused the model's answer. The phone exercised file review introduced after `688ea62`, so its version label alone does not identify the tested revision.

The saved reminder screenshot shows chat prose, not a native calendar confirmation or a completed calendar write. The source does not establish which model step chose the ten-minute offset. The chat scroll code can miss a send when it compares the new message count with the previous visible layout, and it has no jump-to-latest control. The phone screenshot does not establish the exact timing of that failure.

The storage issue blocked mini-app creation, preview, and library scenarios. Folded-screen behavior, TalkBack, speech recognition, and a denied-calendar-permission path remain untested. Recheck those paths on an appropriate device; do not treat partial tests as passes.

## Done criteria for every plan

- Each applicable design requirement or choice has a device test, a host test, or a reason recorded in the plan.
- The full device suite has no failure outside the baseline that the plan records at its start.
- The plan moves the UI text of each screen that it rewrites into string resources.
- `:app:lintDebug` reports 0 errors.
- Evidence follows the execution rules of Plan 1. Each run records the revision, command, inputs, expected result, and observed result.
- Behavior checks use complete workflows. Preserve the last good app and user data on failure or cancellation.
- The final comparison pins both sources and uses three independent real generations per request and product. See design section 6.8 for the corpus, metrics, and gate.

The current `tools/compose_acceptance/run.py matrix` covers six Android scenarios with three repetitions each. It does not run the 121-request reference corpus or a second product. Extend the comparison workflow before using it to claim parity. The pinned corpus contains 45 held-out test requests; keep them out of tuning and report them separately.

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
