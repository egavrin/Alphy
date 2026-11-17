# App look and self-contained games

Date: 2026-11-17. Status: approved on 2026-11-17 by the agent, under the
approval permission that the user delegated ("I delegate you approval
permissions").

## Intent

Generated mini-apps must look as good as the user's 15 mock screens, and
generated games must feel like real phone games. The user stated on
2026-11-17: "Сейчас сгенерированные приложения выглядят не так как на этих
моках, что нужно сделать, чтобы приложения выглядели настолько красиво".
On the same day the user added: introduce the missing UI components, blend
native UI, 2D canvas, and 3D well, and stop games from using Left/Right
buttons and texts instead of touch, the accelerometer, or other native
input. "2d/3d games should be self contained."

An earlier rule from the user (2026-11-16) still applies: every metric must
get better, not trade one for another.

## Decisions by the user

- Utilities get an emoji illustration in the main card. Generated pictures
  appear only when the request asks for a picture, or for a visual topic
  (recipe, plant, animal, menu, catalog). (2026-11-17, "Agree with your
  recommendation about emoji".)
- A game draws everything itself: score, lives, start screen, game over,
  and play again. ("Game draws everythjng".)
- Apps first, then games, with the approaches below. ("Yes, go with your
  picks, and start implementation right away.")
- Measure quality with additional new prompts, not only the tuned cases.
- Lists that the user fills stay empty at the start (2026-11-16). Items
  that the request names are content, not sample data.

## Evidence (baseline on main bdd7e3d)

The 15 mock cases (`tools/agent_corpus/showcase.json`, review off): 15 of
15 committed, median 88 s, total 1511 s, median 4 steps. Evidence:
`alphy-agent-evidence-2026-11-17/baseline-bdd7e3d` (outside the
repository). The comparison of each mock with the generated screen found:

1. No strong main element. `progressRing` is a fixed 56 dp spinner with
   its label above it, so a ring with the value inside is impossible. The
   workout agent drew one on a canvas and showed an empty grey disk. The
   largest value style is `displaySmall` (36 sp). Progress bars are thin.
2. Forms on the first screen: add fields, rows of text fields for saved
   rows, two rows of unit choices.
3. 10 of 15 mocks have a picture. The generated apps had 3. A requested
   flashcard drawing was replaced by an emoji in a text.
4. Small labels, dense rows, a monospace font for money in the
   `technical` preset, a range slider without value labels.
5. Games (earlier showcase runs): 2048, snake, and the 3D maze built rows
   of Left/Up/Down/Right buttons and hint texts. Arkanoid used drag but
   kept native Launch and Restart buttons and hint lines. No game used the
   existing immersive presentation or tilt.

## Goals, non-goals, anti-goals

Goals:

- Add the components that the mocks need and the catalog cannot express.
- Restyle existing components to the mock look.
- Make the canvas and the 3D view take the color and corner radius of
  their container.
- Change the agent guide and its examples so that apps have one main
  element, the main task first, and few fields on the first screen.
- Make 2D and 3D games full screen and self-contained: the game draws its
  own score, buttons, and messages, and reads tap, drag, swipe, and tilt.
- Keep the agent's checks able to test games.
- Measure every change with the 15 mock cases, the game cases, and a
  held-out set (`tools/agent_corpus/holdout.json`).

Non-goals:

- No new generation mechanism. The agent loop stays the only one.
- No screenshot review by default.
- No fixed screen templates.
- No sample rows in user-filled lists.

Anti-goals (a failure even if it works):

- Slower generation or more steps than the baseline.
- A lower commit rate.
- Games that pass checks without a test of their score or state.
- Drawn text that a screen reader cannot read.

## Constraints

- AGENTS.md: no legacy compatibility, one behavior checker, release rules.
- The repository is public: no private prompts or mock images in it.
- Write the failing test first. Prefer black-box tests.
- Slopo review and the architecture scan before each commit.
- Use only emulator-5570 and emulator-5572.

## Approaches

Part 1, app look:

1. New components and new prompt examples (chosen). No extra model calls.
   The prompt grows a little.
2. Screenshot review for every app. Rejected: about 12 s more for each app
   (median), and more steps.
3. Fixed screen templates that the model fills. Rejected: a second
   mechanism, like the deleted parts builder.

Part 2, games:

1. Full screen, the game draws everything (chosen by the user). The host
   gives the checks the drawn text and state, and adds swipe and tilt
   steps.
2. Native layers over the game for score and buttons. Rejected by the
   user.

## Design, part 1: app look

### New components and variants

All of them go through `ToolUiCatalog` (registration, validation,
`modelGuidance()`), `NativeToolUi` (rendering), `AppScreen` (the text
screen that the model reads), and `ToolGenerationSmoke` when they take an
action.

1. `progressRing` with `"size":"large"`: a ring about 200 dp wide with a
   thick rounded stroke. It becomes a container: its children (text,
   metric, badge) are centered inside the ring. The small ring stays as it
   is.
2. `progress` with `"variant"`:
   - `"bar"` (default): a thick rounded bar (about 10 dp) on a tinted
     track.
   - `"dots"`: one dot for each step, filled up to the value, for a
     maximum of at most 12 ("3 of 5").
   - `"level"`: a tall rounded tank that fills from the bottom, with its
     children centered inside (water, fuel, battery).
3. Emphasis `"display"` for `text` and `metric`: about 45 sp, one line,
   shrinks to fit. Metric value sizes become a scale: default about 24 sp,
   `"number"` 36 sp, `"display"` 45 sp. Today every bound metric value is
   36 sp, and the emphasis changes nothing.
4. `card` with `"variant":"hero"`: a tinted container (primary container
   color), more padding, a larger icon tile. It holds the main result.
5. New leaf `emoji`: `{"type":"emoji","text":"description","value":"🥛",
   "size":"small"|"medium"|"large"}` or with `bind` to a string field. It
   draws the emoji in a soft tinted circle (about 40, 64, and 112 dp). The
   description is its accessible text. It is permitted in row templates,
   so a list row can show a thumbnail (a drink, a bike).
6. `checkbox` with `"variant":"circle"`: a round check with its label
   above it, for week grids.
7. `slider` and `rangeSlider` show their values as small labels at the
   thumbs.
8. `icon` on `input`, `numberInput`, `select`, `dateInput`, and
   `timeInput`: a leading icon.
9. A `divider` inside a `row` with `"wrap":false` draws a vertical line.

Restyles of existing components:

- List rows from a row template: more padding and a minimum height of
  about 56 dp.
- `numberInput` with `step`: one row with the label, the value, and round
  minus and plus buttons.
- The `technical` preset uses tabular figures in the body font, not a
  monospace font.

### Canvas and 3D in the page

- An inline canvas takes the color of the surface that contains it (page,
  card, or section) and the theme corner radius. Drawing tones are
  contrast-checked against that color.
- An immersive surface takes the theme background color.
- The inline 3D viewport takes the theme corner radius.

### Agent guide

- One main element at the top of each app: a hero card with a display
  value and an emoji, a large ring, a level gauge, a picture, or a drawing.
- The main task comes first. Settings and edit forms come after it, or in
  a dialog. At most 2 typed fields above the main element.
- Items that the request names (three doses, twelve bikes, the cards) are
  initial content. Lists that the user fills start empty.
- Utilities use an emoji illustration. When pictures are available and
  the request asks for a picture or has a visual topic, make the pictures
  with `generate_image`.
- Replace the two examples (hike time, packing list) with two short
  examples in the new style: a converter with a hero card, and a tracker
  with a ring or dots and emoji rows.
- Keep the guide length close to today's: remove rules that the new
  examples show.

## Design, part 2: self-contained games

### 2D games

- A game uses `presentation:{mode:'immersive'}` and one surface with
  `sizing:'viewport'`. The game fills the window. The existing menu
  button stays (exit, App controls).
- The game draws its score, lives, start screen, game over, play again,
  and any on-screen buttons that the request asks for. The generated `ui`
  holds only settings, which appear in the App controls sheet.
- Input is tap, drag, and swipe through the existing pointer events. The
  game finds a swipe from the down and up positions.
- `hostControls.movement` (the host Left and Right buttons) and the `move`
  step are deleted. Saved apps that declare movement no longer open, as
  with other retired formats. Playback host controls stay.

### Tilt

- A pointer tap on the game surface may start tilt
  (`sensor.accelerometer.stream` with `enabled:true`). Today only a
  native action may. Tilt stays a foreground capability: it stops on focus
  loss, pause, and exit.
- The device host must live with the immersive surface, not only inside
  the App controls sheet, so that tilt does not stop when the sheet
  closes.

### 3D games

- The host honors `presentation:{mode:'immersive'}` for 3D apps: the
  viewport fills the window, with the same menu button.
- `snapshot()` may also return `hud`: drawing commands in the existing 2D
  command format, in viewport units. The host draws them over the 3D view.
  The game draws its score and messages this way. Pointer events still go
  to the scene.
- Tilt for 3D is not in this change.

### Checks and accessibility

- The text screen that the model reads (`AppScreen`) includes the drawn
  text of every surface and of the 3D `hud`, so `expect text` can test a
  drawn score. Immersive apps show their surfaces in the text screen.
- New steps: `{"drag":"surfaceId","from":[x,y],"to":[x,y]}` (a press, a
  move, and a release; a quick drag is a swipe) and `{"tilt":[x,y]}` (one
  accelerometer sample).
- The smoke test sends one tilt sample to apps that declare tilt.
- Drawn text is already part of the canvas content description. The 3D
  `hud` text joins the viewport description in the same way.

### Agent guide for games

- Replace the game rule (metrics or a banner, a board, a Restart button)
  with: immersive and a viewport surface, everything drawn, tap, drag,
  and swipe input, tilt when the request names tilting, and no hint
  texts.
- Drawn text is permitted in games. Other drawings keep numbers and words
  in native nodes.

## Measurement

- A new script `tools/agent_corpus/visual_score.py` reads each saved app
  and reports:
  - a main element at the top;
  - typed fields on the first screen, and typed fields in list rows;
  - a picture when the case asks for one;
  - for games: immersive, a viewport surface, no native direction or
    restart buttons, tilt when asked.
- Run sets: the 15 mock cases, 6 game cases from `showcase.json` (snake,
  2048, memory, drawing, 3D maze, 3D dice), and the 13 held-out cases. The
  held-out cases are run only for the baseline and the final measurement.
- Run metrics: commit rate, median and total seconds, median steps.
- The change passes when every visual score improves, and the commit rate,
  median seconds, and median steps are not worse than the baseline.

## Left to the builder

- Exact sizes, colors, and spacing inside the stated ranges.
- The wording of guide rules and examples.
- The order of the tasks inside each part.

## Builder check answers (the agent's calls, 2026-11-17)

1. 3D on the permitted emulators: emulator-5570 and emulator-5572 run
   WebView 133.0.6943.137, and `ThreeSceneWebView.available()` accepts
   only the qualified builds 145.0.7632.218 and 156.0.8078.47. The "3D"
   showcase cases therefore ran as 2D canvas apps. The qualification list
   is a product decision, so this change does not widen it. The 3D tasks
   (immersive 3D, `hud`) come last. Their host code gets instrumented
   tests that do not need a qualified WebView. A live 3D check needs a
   device with a qualified WebView: the user decides which device.
2. A game is a request to play something with a score, a win, or a loss.
   In the corpus, the tag `game` marks it, and `visual_score.py` uses that
   tag. The drawing board is a tool, not a game: it keeps an inline canvas
   with native controls. Memory, 2048, dice, snake, and the 3D maze are
   games.
3. Tilt in an immersive game: the host starts the accelerometer stream
   itself for an app that declares `sensor.accelerometer.stream`, while
   the immersive surface plays and the window has focus. It stops on
   pause, when the menu opens, on exit, and on focus loss. No native Stop
   button appears in immersive mode: the menu pauses the game. Inline
   (non-immersive) apps keep the explicit Start button rule. The `tilt`
   step sends one sample without a start; the smoke test does the same.
4. Pass rule, for each set (mock, game, held-out), one run of each case
   at the baseline and one at the end:
   - visual criteria: none worse, and the total better;
   - commit count: not lower;
   - median seconds and median steps: the target is better; a result
     within 10% of the baseline counts as noise, and the report gives the
     raw numbers.
   Held-out cases that are missing from the baseline run again at
   bdd7e3d before any change.
5. No host gate for game checks. The guide asks for checks that read the
   drawn score, and `AppScreen` makes that possible.
