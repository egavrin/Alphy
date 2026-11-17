# App look and self-contained games Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generated apps look like the user's mocks, and generated games are full-screen, self-drawn, and use touch and tilt, with every measured metric better.

**Architecture:** New and restyled native components in `ToolUiCatalog` and `NativeToolUi`, a new agent guide and examples, immersive game surfaces that the game draws completely, drawn text visible to the agent checks, and a host-managed tilt stream for immersive games. A measurement script scores saved apps from the live corpus runs.

**Tech Stack:** Kotlin, Jetpack Compose, Android instrumentation tests, Python 3 tooling.

**Spec:** `docs/superpowers/specs/2026-11-17-app-look-and-games-design.md`

## Global Constraints

- Every metric gets better: visual criteria none worse and total better; commit count not lower; median seconds and median steps better, or within 10% as noise (spec, Builder check answer 4).
- Lists that the user fills start empty; items that the request names are content.
- No new generation mechanism, no screenshot review by default, no fixed templates.
- AGENTS.md: no legacy compatibility; saved apps in a removed format stay in the library and do not open.
- Public repository: no private prompts or mock images.
- Write the failing test first; prefer black-box tests.
- Before each commit: Slopo review and `architecture-scan check` (exit 0). No attribution lines.
- Devices: emulator-5570 and emulator-5572 only.
- `holdout.json` runs only for the baseline and the final measurement.

## Review Focus

1. A large ring or level gauge with long text inside (German, 200% font scale) must not overflow the circle or tank: the text shrinks or wraps inside it. Test in Task 3.
2. An emoji that the font cannot draw, or an empty bound emoji string, must not crash or show a blank tile without a description. Test in Task 3.
3. A drag step that leaves the surface (to x 1.2) must clamp, not throw. Test in Task 6.
4. Tilt must stop when the window loses focus or the menu opens, even if the game keeps frames running. Test in Task 8.
5. Drawn text from a game with many frames must not flood the agent's screen text: the screen keeps at most the last frame's text, and a long text list is cut. Test in Task 6.

---

### Task 1: Measurement script and baseline scores

**Files:**
- Create: `tools/agent_corpus/visual_score.py`
- Add (already written): `tools/agent_corpus/holdout.json`, the spec, this plan

**Interfaces:**
- Produces: `python3 tools/agent_corpus/visual_score.py RUN_DIR [RUN_DIR...] --corpus CORPUS.json...` prints one row per case and one total row per run directory; `--self-test` runs built-in assertions.

Criteria per saved app (`RUN_DIR/<case>/artifact.json`, the `spec` object or the root):
- `hero`: one of the first two children of the root column (inside `theme`) is, or contains at depth ≤ 2, a `card` with `variant:"hero"`, a `progressRing` with `size:"large"`, a `progress` with `variant:"level"` or `"dots"`, an `image`, an `emoji` with `size:"large"`, a metric or text with emphasis `display`, or a `canvas`; or the app is immersive.
- `fields`: count of `input`, `numberInput`, `timeInput`, `dateInput`, `dateRangeInput` outside `dialog` and `bottomSheet` (lower is better).
- `rowFields`: the same types inside any `rowTemplate` (lower is better).
- `picture`: for cases tagged `image`, an `image` node exists.
- Games (tag `game`): `immersive` (presentation mode immersive or 3D runtime), `viewport` (a surface with `sizing:"viewport"`), `nativeGameButtons` (count of `button`/`icon` nodes whose text or action matches left|right|up|down|restart|new game|play again|start, any language list in the script; lower is better), `tilt` (capabilities contain `sensor.accelerometer.stream`, scored only for tag `tilt`).
- Run metrics from `summary-*.jsonl`: committed count, median and total seconds, median steps.

- [ ] **Step 1: Write `--self-test` assertions first**: a synthetic hero app scores `hero=1, fields=1, rowFields=0`; a synthetic form app with a text field in a row template scores `hero=0, rowFields=1`; a synthetic game with a native "Left" button scores `nativeGameButtons=1, immersive=0`.
- [ ] **Step 2:** Run `python3 tools/agent_corpus/visual_score.py --self-test`. Expected: fails (no implementation).
- [ ] **Step 3:** Implement the script.
- [ ] **Step 4:** Run the self-test. Expected: `self-test ok`. Run it on `alphy-agent-evidence-2026-11-17/baseline-bdd7e3d` (showcase corpus) and `baseline2-bdd7e3d` (showcase + holdout corpora). Record the totals in the ledger.
- [ ] **Step 5:** Commit the spec, the plan, `holdout.json`, and the script: "Add the app look and games design, plan, and measurement".

### Task 2: Value scale and restyles

**Files:**
- Modify: `app/src/main/java/com/alpha/assistant/tools/ToolUiCatalog.kt` (emphasis values ~253, `input`-family properties ~87, guidance ~1110-1139)
- Modify: `app/src/main/java/com/alpha/assistant/tools/NativeToolUi.kt` (`textStyle`/`metricStyle` 544-554, metric 982-995, progress 1166-1177, slider 1115-1134, rangeSlider 1075-1086, `NativeInput` 1415-1485, divider 1202, `NativeRecordRow` 1574-1587, `NativePreset` technical font 1749)
- Test: `app/src/androidTest/java/com/alpha/assistant/tools/NativeLookWorkflowTest.kt` (new; helpers as in `NativeIconWorkflowTest`)

**Interfaces:**
- Produces: emphasis value `"display"` for `text` and `metric`; metric value sizes default `headlineSmall`, `number` `displaySmall`, `display` `displayMedium` (shrink to fit, one line); `icon` accepted on `input`, `numberInput`, `select`, `dateInput`, `timeInput`; a `divider` that is a direct child of a `row` with `wrap:false` draws vertically.

- [ ] **Step 1: Write the failing tests** in `NativeLookWorkflowTest`:
  - `metricValuesFollowTheEmphasisScale`: three bound metrics with values "111", "222", "333" and emphasis none, `number`, `display`; assert `height("111") < height("222") < height("333")`.
  - `displayTextValidates`: `{"type":"text","text":"Kanji","style":{"emphasis":"display"}}` validates.
  - `aBoundProgressBarIsThick`: progress "Packing progress" at 40; the bar's semantics bounds height ≥ 8 dp.
  - `technicalMoneyUsesProportionalDigits`: metric value "0,00 €" in `technical` and in `clean`; widths differ by less than 15%, and the technical text has no space between "0" and ",".
  - `aDividerInAFixedRowIsVertical`: row `wrap:false` with "Left side", divider, "Right side"; both texts have the same center y and "Right side" starts right of "Left side".
  - `inputFieldsTakeALeadingIcon`: an `input` with `icon:"Search"` validates (fails today) and draws a colored pixel left of its label inside the field.
  - `slidersShowValueLabelsAtTheThumbs`: slider at 30 shows a visible "30" above the track; rangeSlider 400..3000 shows "400" and "3000".
  - `aSteppedNumberInputIsOneRow`: numberInput with step: the minus and plus buttons and the value share one center y.
  - `templateRowsAreAtLeast56dpTall`: a list of 2 rows with one text each; each row surface ≥ 56 dp.
- [ ] **Step 2:** Build and run `NativeLookWorkflowTest` on emulator-5572. Expected: the new assertions fail for the reasons named.
- [ ] **Step 3:** Implement the scale and restyles. Use tabular figures (`fontFeatureSettings = "tnum"`) in the body font for `technical`. Value labels: a small `labelMedium` pill above each thumb.
- [ ] **Step 4:** Run `NativeLookWorkflowTest`, `NativeComponentStyleWorkflowTest`, `NativeIconWorkflowTest`, `NativeNumberStepWorkflowTest`, `NativeLayoutTextWorkflowTest`, `NativeComposeContractTest`, `BoundValueTextWorkflowTest`, `GenerationContractGuidanceTest`. Expected: all pass, or only baseline failures.
- [ ] **Step 5:** Commit "Scale metric values and restyle inputs, progress, and rows".

### Task 3: Hero components

**Files:**
- Modify: `ToolUiCatalog.kt` (components 61-107, row template allow list 207, per-type checks 299-580, `optionalBindings` 31, `unlabeledLeaves` 44 if needed, guidance), `NativeToolUi.kt` (new branches), `tools/agent/AppScreen.kt` (96-187), `tools/ToolGenerationSmoke.kt` (336-351 if an action is added)
- Test: `app/src/androidTest/java/com/alpha/assistant/tools/NativeHeroWorkflowTest.kt` (new)

**Interfaces:**
- Produces:
  - `progressRing` property `size: "small"|"large"`; with `large` it is a container and its children render centered inside a ring of about 200 dp with a 14 dp rounded stroke. Children on a small ring fail validation: "Only a large progressRing holds children."
  - `progress` property `variant: "bar"|"dots"|"level"`; `dots` requires `max` ≤ 12 and `min` 0 ("Dots show at most 12 steps."), state description "3 of 5"; `level` is a container with children centered in a tank about 220 dp tall.
  - `card` property `variant: "hero"`: primary container color, 20 dp padding, 48 dp icon tile.
  - New leaf `emoji`: properties `text` (required, accessible description), `value` (static emoji) or `bind` (string), `size: "small"|"medium"|"large"` (40/64/112 dp tile). Permitted in row templates. An empty value draws the tile with no glyph and keeps the description.
  - `checkbox` property `variant: "circle"`: label above a 36 dp round check; same action and value as a checkbox.
- `AppScreen` lines: `progressRing large "Label" 62% { children }`, `progress dots "Label" 3 of 5`, `emoji "Milk" 🥛`.

- [ ] **Step 1: Write the failing tests** in `NativeHeroWorkflowTest`:
  - `aLargeRingCentersItsChildren`: ring at 25 with child text "00:40"; ring bounds width ≥ 180 dp; the text center is within 12 dp of the ring center.
  - `aSmallRingRejectsChildren`: validation message contains "Only a large progressRing holds children."
  - `longTextStaysInsideALargeRing` (Review Focus 1): child text "Belastungsphase läuft" at font scale 2.0; the text bounds lie inside the ring bounds.
  - `dotsShowStepsAndRejectMoreThanTwelve`: dots max 5 value 3 has state description "3 of 5"; max 13 fails with "Dots show at most 12 steps."
  - `aLevelGaugeFillsFromTheBottom`: level at 50 with child "1.25 L": a pixel at 85% height is the fill color, a pixel at 15% height is the track color, and "1.25 L" is inside the tank.
  - `aHeroCardIsTinted`: the background saturation inside a hero card is higher than inside a plain card.
  - `emojiDrawsInPagesAndRows` (Review Focus 2): a large static emoji "🥛" with text "Milk"; a list whose row template has a bound emoji; one row value is ""; validation passes, "Milk" is the content description, and rendering does not fail.
  - `circleCheckboxesShowTheLabelAbove`: seven circle checkboxes in a grid of 7 with labels "Mo".."Su"; "Mo" is above its check; a tap on the check sends the checkbox action with value true.
  - `theAgentScreenNamesTheNewNodes`: `AppScreen.render` of a ring, dots, and an emoji contains `progressRing large`, `3 of 5`, and `emoji "Milk"`.
- [ ] **Step 2:** Run `NativeHeroWorkflowTest`. Expected: fails (unknown types and properties).
- [ ] **Step 3:** Implement catalog entries, checks, renderers, accessibility (`stateDescription`, `contentDescription`), `AppScreen` lines, and guidance lines.
- [ ] **Step 4:** Run `NativeHeroWorkflowTest` plus the Task 2 class list and `AgentGenerationWorkflowTest`. Expected: pass, or only baseline failures.
- [ ] **Step 5:** Commit "Add hero components: large ring, dots and level, hero card, emoji, round checks".

### Task 4: Canvas in its container

**Files:**
- Modify: `tools/NativeToolSurface.kt` (236-240 color scheme and tones, 268-269 clip and background), `tools/NativeToolUi.kt` (canvas branch 753-756, provide the container color), `tools/ToolUi.kt` (226 corner radius)
- Test: `CanvasGenerationWorkflowTest` (new test methods)

**Interfaces:**
- Produces: an inline canvas paints the background of its nearest container (the color `ToolRoleSurface`/card or the page provides) and clips to the theme corner radius; an immersive surface paints the theme background. Drawing tones are contrast-checked against that color.

- [ ] **Step 1: Write the failing tests**: `anInlineCanvasTakesItsCardColor` (an empty canvas inside a card: the pixel at the canvas center equals the card color within 3 per channel) and `anInlineCanvasUsesTheThemeRadius` (a canvas in a `playful` theme: the corner pixel at 8 dp from the corner is the outer color).
- [ ] **Step 2:** Run them. Expected: fail (the canvas is `scheme.surface` with a 16 dp radius).
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run `CanvasGenerationWorkflowTest`, `MiniAppPresentationWorkflowTest`, `GraphicsWorkflowTest`, `GameGenerationWorkflowTest`. Expected: pass, or only baseline failures.
- [ ] **Step 5:** Commit "Paint inline canvases in their container color and theme radius".

### Task 5: Agent guide for app look

**Files:**
- Modify: `tools/agent/AppAgentGuide.kt` (design 53-74, examples 76-119, pictures 154-160), `tools/agent/AppAgent.kt` (design hints 729-735)
- Test: `GenerationContractGuidanceTest`, `AgentGenerationWorkflowTest` (the example checks at ~439)

**Interfaces:**
- Consumes: Task 2 and Task 3 components.
- Produces: `AppAgentGuide.exampleConverter`/`exampleConverterCheck` and `AppAgentGuide.exampleTracker`/`exampleTrackerCheck` replace the calculator and list examples. The design hint for more than 3 metrics in a card stays.

Guide rules (from the spec): one main element at the top; the main task first, settings and edit forms after it or in a dialog; at most 2 typed fields above the main element; request-named items are initial content and user-filled lists start empty; an emoji illustration for utilities; when pictures are available and the request asks for a picture or has a visual topic, call `generate_image`; keep the guide length within 10% of today's (`AppAgentGuide.system(...)` length for the same arguments).

- [ ] **Step 1: Write the failing tests**: the example checks for both new examples pass through `check(example, steps)`; `GenerationContractGuidanceTest.theGuideAsksForOneMainElementAndEmoji` asserts the system prompt contains "one main element", `"type":"emoji"`, and `"variant":"hero"`, and that its length is at most 1.1 × the length at bdd7e3d (store the number in the test).
- [ ] **Step 2:** Run. Expected: fail (old examples, old rules).
- [ ] **Step 3:** Write the new rules and examples.
- [ ] **Step 4:** Run the tests and `AgentGenerationWorkflowTest`, `LessonAgentWorkflowTest`. Expected: pass.
- [ ] **Step 5:** Commit "Teach the app agent the main element, emoji, and the new examples".
- [ ] **Step 6:** Live: install on both emulators; run the 15 mock cases (as at baseline: `--images` for 3d-dna, image-menu, image-plant, image-recipe, image-flashcards); run `visual_score.py`; build mock sheets. Expected: visual criteria better; run metrics within the rule. If not, change the guide or examples once and rerun only the failing cases, then the full set. At most 2 rounds; ledger each round.

### Task 6: Drawn text, drag, and tilt steps for checks

**Files:**
- Modify: `tools/agent/AppScreen.kt` (canvas 141-150; immersive surfaces), `tools/agent/AppSteps.kt` (39-57, 74-106, 156-179, 287-296), `tools/ToolGenerationSmoke.kt` (`AppProbe` 217-304, smoke 109-148), `tools/agent/AppThreeScene.kt` (`ThreeSceneTarget`: drag), `tools/agent/AppAgentGuide.kt` (step list 28-40)
- Test: `AgentGenerationWorkflowTest` (new methods)

**Interfaces:**
- Produces:
  - `AppScreen` adds each surface's drawn text (`surfaceLabels` of the last frame) as visible text, cut to 400 characters for each surface, and lists immersive surfaces with their size and drawn text.
  - Step `{"drag":"surfaceId","from":[x,y],"to":[x,y]}`: pointer down at `from`, 5 moves, up at `to`; coordinates clamp to 0..1.
  - Step `{"tilt":[x,y]}`: one `{type:'sensor',sensor:'accelerometer',sequence,timestampMillis,x,y,z:9.8}` event.
  - The smoke test sends one tilt sample to apps that declare `sensor.accelerometer.stream`.

- [ ] **Step 1: Write the failing tests**:
  - `drawnTextIsVisibleToChecks`: a canvas app draws text "Score 3" after one tap; the check `[{"pointer":"board","x":0.5,"y":0.5},{"expect":"text","contains":"Score 3"}]` passes.
  - `immersiveSurfacesAppearInTheScreen`: an immersive viewport app; the write feedback contains the surface ID and its drawn "Tap to start".
  - `aDragStepSendsDownMovesAndUp` (Review Focus 3): source counts phases; `{"drag":"main","from":[0.5,0.5],"to":[1.2,0.5]}` then the drawn text shows "down 1 move 5 up 1" and the last x is 1.0 × width.
  - `aTiltStepSendsOneSample`: source draws "tilt 2.0"; `{"tilt":[2,0]}` then expect text "tilt 2.0".
  - `drawnTextStaysShort` (Review Focus 5): a frame draws 300 texts; the screen text for that surface is at most 400 characters.
  - `theSmokeTestSendsATiltSample`: an app that declares tilt and throws on a sensor event fails the smoke test.
- [ ] **Step 2:** Run. Expected: fail.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run `AgentGenerationWorkflowTest`, `GameGenerationWorkflowTest`, `AgentThreeSceneWorkflowTest`, `GraphicsBoundariesTest`. Expected: pass, or only baseline failures.
- [ ] **Step 5:** Commit "Let checks read drawn text and send drag and tilt steps".

### Task 7: Remove host movement controls

**Files:**
- Modify: `tools/ArtifactProtocol.kt` (77-93 `ArtifactHostControls`, guidance text at 60), `tools/ToolSpec.kt` (232-236), `tools/NativeToolSurface.kt` (322-325, 335-384), `tools/ToolGenerationSmoke.kt` (132-138), `tools/agent/AppSteps.kt` (`move`), `tools/agent/AppAgentGuide.kt` (step list)
- Test: tests that use `movement` or `move` (find with `git grep -n '"movement"\|"move"' app/src/androidTest`), `LibraryManagementWorkflowTest` (a retired-app case)

**Interfaces:**
- Produces: `hostControls` accepts only `playback`. A spec with `movement` fails parse, so a saved app with it stays in the library and does not open.

- [ ] **Step 1: Write the failing test**: `LibraryManagementWorkflowTest.savedAppWithHostMovementStaysButDoesNotOpen` with asset `legacy/host-movement-app.json` (a valid bdd7e3d app with `hostControls:{"movement":["main"]}`), using the existing `retiredAppStaysButDoesNotOpen` helper.
- [ ] **Step 2:** Run. Expected: fail (the app opens).
- [ ] **Step 3:** Delete movement from the protocol, spec, surface UI, smoke test, steps, and guide. Change or delete the tests that cover only movement; keep their pointer and playback cases.
- [ ] **Step 4:** Run the changed test classes, `LibraryManagementWorkflowTest`, `MiniAppPresentationWorkflowTest`. Expected: pass, or only baseline failures.
- [ ] **Step 5:** Commit "Remove host movement controls; games draw their own".

### Task 8: Host-managed tilt for immersive games

**Files:**
- Modify: `tools/ToolController.kt` (`setTilt` 796-838, stop paths 768-788), `tools/ToolUi.kt` (immersive branch 662-698; device host at 982-1000), `capabilities/TiltContract.kt` (guidance text), `tools/ToolEngine.kt` (679-680 surface rule stays)
- Test: `DeviceCapabilityUiWorkflowTest` (new methods; reuse the fake sample source of `tiltChangesTheSavedGameStateAndNativeStopEndsDelivery`)

**Interfaces:**
- Produces: for an immersive app that declares `sensor.accelerometer.stream`, the host streams samples while playback is `playing`, the App controls sheet is closed, and the window has focus. It stops on pause, sheet open, exit, and focus loss. Inline apps keep the explicit Start rule.

- [ ] **Step 1: Write the failing tests**: `immersiveTiltStreamsWhilePlaying` (samples change saved state with no native action) and `immersiveTiltStopsOnMenuAndFocusLoss` (Review Focus 4: after the menu opens, and after focus loss, no new sample reaches the state while frames continue).
- [ ] **Step 2:** Run. Expected: fail.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run `DeviceCapabilityUiWorkflowTest`, `CapabilityWorkflowTest`, `MiniAppPresentationWorkflowTest`, `LiveInputHostWorkflowTest`. Expected: pass, or only baseline failures.
- [ ] **Step 5:** Commit "Stream tilt to immersive games while they play".

### Task 9: Agent guide for games

**Files:**
- Modify: `tools/agent/AppAgentGuide.kt` (Games 72, Drawings 71), `tools/ArtifactProtocol.kt` (guidance text), `capabilities/TiltContract.kt` (guidance)
- Test: `GenerationContractGuidanceTest`, `GameGenerationWorkflowTest`

**Interfaces:**
- Produces: the game rule: a game is something the user plays for a score, a win, or a loss; use immersive presentation and one viewport surface; draw the score, lives, start screen, game over, play again, and any requested on-screen buttons; read tap, drag, and swipe from pointer events; declare tilt when the request names tilting; no native game controls and no hint texts; `ui` holds only settings; checks read the drawn score with `expect text`. Drawn text is permitted in games only.

- [ ] **Step 1: Write the failing test**: `GenerationContractGuidanceTest.gamesAreImmersiveAndSelfDrawn` asserts the guide contains "immersive", "viewport", and "draw the score", and no longer contains "a Restart button".
- [ ] **Step 2:** Run. Expected: fail.
- [ ] **Step 3:** Write the rule.
- [ ] **Step 4:** Run the guidance and game tests. Expected: pass.
- [ ] **Step 5:** Commit "Teach the agent self-contained games".
- [ ] **Step 6:** Live: run the 6 game cases and Arkanoid (compose scenario 4); score them. Expected: immersive and no native game buttons in each game; run metrics within the rule. At most 2 rounds.

### Task 10: Immersive 3D with a drawn layer

**Files:**
- Modify: `tools/ToolUi.kt` (645-662 immersive branch for 3D), `tools/three/ThreeScenePane.kt` (immersive layout, `hud` overlay), `tools/three/ThreeSceneSession.kt` (snapshot 145-153: optional `hud`), `app/src/main/assets/threejs/worker.js` (pass `hud`), `tools/agent/AppThreeScene.kt` and `AppScreen.kt` (hud text), `AppAgentGuide.kt` (3D section)
- Test: `ThreeSceneWorkflowTest` or a new `ThreeSceneHudWorkflowTest` for the parts that do not need a qualified WebView (snapshot validation, overlay drawing from a fixed snapshot, screen text)

**Interfaces:**
- Produces: `snapshot()` may return `hud: {width, height, commands:[...]}` in the 2D command format, validated by the existing surface command validator; the host draws it over the viewport; its text joins the viewport description and the agent screen. A 3D app with `presentation:{mode:'immersive'}` fills the window with the menu button.

- [ ] **Step 1: Write the failing tests** that run without a qualified WebView: an invalid `hud` command is rejected with the drawing error; a valid `hud` with text "Score 2" appears in `AppScreen` text and in the viewport content description.
- [ ] **Step 2:** Run. Expected: fail.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run the new tests and `AgentThreeSceneWorkflowTest`. Expected: pass, or only baseline failures (the qualified-WebView tests stay as baseline failures on these emulators).
- [ ] **Step 5:** Commit "Let 3D games fill the screen and draw a layer over the scene".
- [ ] **Step 6:** Ledger that a live 3D check needs a qualified-WebView device (the user's decision).

### Task 11: Final measurement, review, and push

- [ ] **Step 1:** Run the full instrumentation suite on both emulators; compare with the baseline failure list (rerun new failures alone).
- [ ] **Step 2:** Run all three sets live (mock 15, game 6 + Arkanoid, held-out 13) on the final build; score; build sheets for the mock and game sets.
- [ ] **Step 3:** Final whole-branch review by a fresh reviewer on the most capable model; fix Critical and Important findings test-first.
- [ ] **Step 4:** Verify `origin/main` is bdd7e3d (or rebase if it moved), push `HEAD:main` as a fast-forward.
