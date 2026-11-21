# Stage templates and a screen that draws while its plan arrives: design

Date: 2026-11-21. Status: for the owner's approval.

This design changes how the voice mode makes a screen. It builds on `2026-11-20-dialogue-stage-design.md` and changes two of its anti-goals (section 6). It builds on `2026-11-18-app-file-parts-design.md`: a template is a part in the terms of that design.

## Intent

A new screen of the voice mode comes 8.4 to 12.3 s after the person stops speaking, and nothing shows before it is complete. The owner wants a talk that feels live: the picture draws under the voice, from its first line.

The owner's words of 2026-11-21:

- Templates: "заготовим несколько стандартных темплейтов, которые можно использовать в интерактивном режиме или, например, в будущем в уроках. И тогда модели ... нужно будет просто этот темплейт заполнить с помощью кита нашего." The screen must fit: "чтобы оно всегда попадало в экран, не скроллилось".
- The stream: "Один из поинтов перехода к плану была возможность рендерить по мере формирования этого плана ... Нам не нужно ... генерировать всю картинку целиком, чтобы отрендерить ее."
- When it shows: "первый экран имеет смысл показывать, когда первая строчка плана зафиксирована, и уже можно начинать ... строчку рендерить."
- A fault: "максимум мы можем какую-нибудь одну компоненту перерисовать ... это не перерисовка всего темплейта, это просто там одной строки."
- The voice: "то, что картинка будет генериться под голос. Нормально." and "спеку нужно поменять с учетом стриминга."
- A spoken change: "тоже нужно поддержать."
- Scope: "меня пока в первую очередь интересует просто обычный голосовой чат. Потом ... распространим их ... на уроки. То есть каждый отдельный слайд будет таким темплейтом."

One sentence of the dictation did not come through and has no reading.

## Goals, non-goals, anti-goals

Goals:

1. A screen that explains or shows a thing in the voice mode is a filled template. It fits the stage with no scroll at the phone and the fold size, and it has one control.
2. The screen shows at its first complete line. Each later line draws when it is complete: the control, each part of the picture, the card.
3. A fault belongs to one line. A repair draws that line again. The other lines stay on the screen.
4. A spoken change of a template screen draws the same way: each changed line when it is complete.
5. Four times of each screen are measured on one clock and reported: first line visible (the frame), first part of the picture visible, picture complete, controls on.

Non-goals of this design:

- Lessons and course slides as templates. They come later, one template for each slide. The template form must not depend on the stage.
- Typed chat. It keeps today's plan and its whole screen.
- A stream for the free plan (a request to learn with a check, a miss of the templates) and for a change of a free-plan app.
- Motion that runs by itself. It has its own design.
- A faster routing call, more template kinds than three, other providers.

Anti-goals (a result that works by its letter and fails the owner):

- A picture whose earlier parts move or change while it grows.
- A screen that starts again because one line had a fault.
- A final screen that comes later than today's because of the stream.
- A control that the person can press before the app is live.
- A screen that stays unfinished with no end: each screen completes, or it goes away.

## Decisions of the owner

Each is a Requirement. The source is his message of 2026-11-21.

1. The screen shows at its first complete line, with no wait for the routing call. (This replaces the controller's default.)
2. An explain screen is a template: picture, control, card. A request to learn with a check keeps today's plan with its lesson and question. Lessons as templates come later.
3. A picture that draws while the voice speaks is permitted. The stage spec changes.
4. A fault draws one line again, never the whole template.
5. A spoken change is in this build.

The ruling of 2026-11-18 "one prompt for both modes" stays for the free plan. The template prompt is a second, short prompt for the voice screen only.

## Facts that limit the design

Each fact has its source.

- **Time of today** (4 paid new screens, 2026-11-21): hand-off 1.0 to 1.3 s, plan call 6.0 to 9.2 s, compile 0.05 to 0.3 s, host check 0.4 to 1.8 s, open 0.2 to 0.3 s. The routing call ends 3.1 to 5.5 s after speech end.
- **Template check** (paid, 45 calls, 12 held-out requests, `evidence/templatecheck/`, outside Git): plan call 7.5 s today, 4.25 s for a fill of one line (318 output tokens). 10 of 12 fills were clean at the first try. Both faults were mechanical: a color name that the kit does not have, and a text 2 characters past its cap.
- **Fit** (app pictures in the saved-app pane, not the stage): today's screens scroll in 11 of 11 cases, by 210 to 597 dp at the phone size. The fills fit in 12 of 12 at the phone and the fold width.
- **Stream of the reply** (code): each wire passes each text delta up. `ToolGenerationService.response` keeps only the first (`tools/ToolEngine.kt:205-223`), and `appFileCall` returns the whole text (`:406`). The read thread runs the delta callback, under the limits of 20 s to the first text and 45 s in all.
- **Estimate of the stream** (36 records, one rate between first text and end; not measured piece by piece): first text 1.7 s, first shape 2.35 s, picture complete 3.8 s, end 4.5 s after the call starts.
- **A preview path exists** (code, read by the controller): `GeneratedToolPreview` draws an app that is not stored, from a definition, a UI tree, a state, and drawing commands, with the control off (`tools/ToolUi.kt:279-330`, `tools/StaticDraftDrawing.kt`). It shows only in a chat card, and nothing feeds it during a plan call. Its publisher needs a draft row (`tools/ToolController.kt:1529-1534`).
- **The stage** shows only a stored app of a complete message (`ui/DialogueStage.kt:63-64`, `:89`, `ui/LiveVoiceBridge.kt:219`). A new stored revision costs a session restart, a store row, and a base check, and the store takes one accept for each draft.
- **The early plan** of a first screen starts at the hand-off, before the routing call ends, and has no draft row yet (`ui/ShellViewModel.kt:1740-1744`, `tools/ToolController.kt:469-478`). A later new screen and a change start their call after the routing call.
- **The sandbox** has one transient slot that answers BUSY with no wait (`sandbox/AndroidJsRuntime.kt:81-93`). Each compile takes a new isolate and the whole kit.
- **The kit** (free experiment, `scratchpad tpl1/prefix`): no unfinished line parses, the title line alone gives no screen, and a plan with a fault gives no definition. A drawing with an empty shape list compiles (34 of 35), so the picture card can have its final size before the first shape. With placeholders, 176 of 178 cut points of a fill give a clean screen. A free plan of today gives 203 of 413, and 373 with each value line before its reader. A value that the person moved stays through a compile with the earlier state (28 of 28). One warm compile of a prefix takes 0.3 ms by the median in Bun. The cost on the device is not measured.
- **The voice** says the answer of the routing call when that call ends, before the screen is built (`ui/LiveVoiceBridge.kt:134-138`). For a change it says the answer when the change is complete (`:227-235`).

## Approaches

1. **A preview stream of template lines** (picked). The model writes a template as lines, one part on each line. The app reads the reply as it arrives, and the stage draws each complete line through the preview path. At the end the normal path stores the app. Nothing unfinished is stored.
2. **The whole plan, a little earlier.** The stage shows the compiled plan as a preview while the host check runs. It saves 0.4 to 1.8 s for each plan on the stage. It is the first step of approach 1, and it also serves a free plan.
3. **A stored revision for each part.** Rejected: one accept for each draft, a store row and a session restart for each part, and a later change would merge into an unfinished plan.
4. **A stream of today's free plan.** Not now: 49% of its cut points compile. The rule "each value before its reader" gives 90%, but the model must obey it (not measured), and the control moves down when the picture comes (29 of 29).
5. **The fill of one line, read inside the line** (the form of the template check). Not picked: the owner's unit is a line, and the kit already merges a repair and a change by line name. Its numbers (4.25 s, 318 tokens, 176 of 178) stay as evidence for the size and the order, not for the lines form.

## Design

### 1. The template file

A template file is what the model writes for a voice screen. One unit serves the draw, the repair, and the change: **a line**. A line is complete by itself and has a name.

```
scene("Почему корабль не тонет?", lang="ru", palette="blue")
control = choice("Предмет", ["Корабль", "Гвоздь"])
s1 = rect(0, 110, 300, 110, "water", 0)
s2 = polygon([45, 75, 255, 75, 225, 140, 75, 140], "ink", visible=$/v == "Корабль")
s3 = text(150, 50, "Воздух внутри", "ink", 14, visible=$/v == "Корабль")
card = card("Вытеснение воды", "Полый корабль вытесняет достаточно воды, чтобы удержаться на плаву.")
```

- **The order is fixed**: the first line (the template, the title, the language, the palette), then the control, then the parts of the picture, then the card. So each line can draw when it comes: the frame, the control, each shape, the card.
- **Three templates**, as in the template check: `scene` (shapes that one control changes), `items` (a choice of things, a large sign of the chosen one, its text), `cells` (a whole of equal cells and a slider). The check used `scene` 10 times, `items` 2 times, `cells` 0 times of 12.
- **The content budget** is the one of the template check: one main visual, one control, one card, title 30, control label 28, card title 32, card text 140, option 12, sign 14, shape text 28 characters, 6 options, 6 items, 12 shapes, 100 cells.
- **The kit expands** a template file into an app file of today's kinds and compiles it. The run-time, the saved state, and the host check stay as they are.
- **The app stores the template file**, not the expansion. The change call sends it.
- The exact words of each line are left to the builder, inside these rules.

### 2. A fault of one line

A fault belongs to its line (Requirement 4). The screen keeps each other line.

- **Fixed with no call** (Implementation choice, each from a measured fault): the kit takes the common color names and gives its own color. The kit takes a text up to 1.25 times its cap.
- **One repair call** names each line with a fault. The reply holds only these lines, and the kit puts each in its place by its name. A line with a fault is not drawn before its repair.
- **Left out** (Implementation choice): a shape, an item, or the card that still has a fault after the repair. A control that still has a fault, or that changes nothing on the screen, is left out too, and the picture is then still.
- A repair of single lines is new. The template check asked for the whole fill again. So its time is not measured.

**What can still fail as a whole.** The template model did not make the screen, so no line can be drawn again:

1. The model replies that no template fits (`outside`). Nothing was drawn yet. Today's path follows: the free plan, then the full generation.
2. The first line has a fault and its repair fails, or no shape and no item is valid after the repair. A `cells` screen has no part lines, so it fails as a whole when its control has a fault.
3. The call stops early (a time limit, the network). One more call asks for the lines that are missing. Case 3 is a whole failure only when that call fails too. This call needs the text that arrived, so it comes with the stream. Before the stream is built, a call that stops early is a whole failure.
4. The host check of the complete screen finds a fault that no line owns, for example the language of the texts.

In cases 2 to 4 the unfinished screen goes away, and the turn goes on as today: the voice says the wait line, and the full generation builds the screen. For a change, the app stays as it was, and the voice says the reason.

### 3. The screen that draws

- **The unit is a complete line.** The app does not use an unfinished last line.
- **The hand-off of the text.** The plan call hands each delta to a buffer and returns at once. It never waits for the screen, because it runs on the read thread under the call limits.
- **The preview loop** takes the newest complete lines when it is free. A slow device then gets fewer steps, not a late screen.
- **The kit's preview step** takes the lines so far. It gives the frame from the first line, with the picture card at its final size. A part that is not there yet is not shown. It runs no check run, and it leaves out a line with a fault. It gives what the stage needs: the UI tree, the state, and the drawing commands.
- **The stage** gets a preview branch. It draws the preview in the stage layout, with the control off, and the strip keeps its working status. The preview is in memory only: no store write, no draft row.
- **The first screen of a talk** can show before the routing call ends (Requirement 1). When that call does not class the turn as a screen, the preview goes away, with no error and no spoken line. One setting in the code holds this gate, so a test can compare both values.
- **A later new screen** replaces the shown app at its first line.
- **The end.** When the reply is complete, the normal path runs: the compile with the check run, the host check, the store. The stage then changes to the live app. The canvas keeps the last preview drawing until the live app draws, so no empty frame shows. "Controls on" is this moment.
- **Each plan on the stage** gets the last step of this path, also a free plan: the stage shows the compiled plan as a preview while the host check runs (approach 2).
- **The sandbox** (Implementation choice after the device measure): a new isolate for each update, or one isolate for the stream. The preview loop must never make the final compile, the host check, or a press of the person fail with BUSY.

### 4. A spoken change

- The change call of a template screen uses the template prompt and its change rule. Its input is the request and the stored template file. Its reply holds only the lines that change, each with its name, and `delete NAME`.
- The kit merges each line by its name, as it merges the lines of a free plan today.
- Each changed line draws when it is complete, over the shown screen. Each other line stays. The preview is compiled with the saved state, so a value that the person moved stays.
- The control is off while the change runs, as today. The voice says the answer when the change is complete, as today.
- A change that a template cannot hold gets `outside`. Today's change by lines then runs on the expanded app file, whole and with no stream, and the app is a free plan after it.
- No evidence exists yet for a change by template lines. The model check of section 7 is its gate.

### 5. Which prompt a spoken request gets

- A spoken screen request gets the template prompt first. Its `outside` reply costs about 1.3 s (measured for a refusal of one line).
- The input of a template call is the task text of today's plan call: the request, the brief of the routing call, and the excerpts. So a screen with search results works as today. The early plan of a first screen has no brief, as today.
- The template prompt tells the model to refuse a request to learn or to practice with a check, a timer, a list, a form, a game (Requirement 2). In the template check, the prompt told the model to fill these, and 2 of 3 lost their check. So this rule is new, and only a paid check shows that it holds.
- After `outside`, the free plan runs as today, with its Dialogue line unchanged.

### 6. The voice, and the change of the stage spec

What the voice says, and when, does not change. Two anti-goals of `2026-11-20-dialogue-stage-design.md` get a new text:

- "A spoken answer that does not agree with the picture beside it." New: this holds for a complete picture. A picture that draws while the voice speaks is normal (owner, 2026-11-21).
- "A screen for each question. A short fact gets a spoken answer and nothing else." New: this holds for the result of the turn. A frame can show while the routing call runs, and it goes away when that call gives no screen. This follows from Requirement 1. The owner did not name it, so the live check counts each such turn and reports the count.

### 7. Gates and measures

Each gate is a task of the plan. A gate that fails goes to the owner before the work that depends on it.

1. **Device cost of one preview update**, in two parts, because the second part needs the stream:
   - 1a, as early as the kit can make a preview, beside the template phase: the time of one update on an emulator at the phone and the fold size, with the compile, the drawing, and the redraw. One update above 1 s goes to the owner before more stream work.
   - 1b, after the stream of a new screen exists: the stream never makes "controls on" later than a run with the stream off by more than 0.3 s (paired, scripted deltas).
2. **Model check of the template prompt** (paid). Tuning uses the 14 requests of the template check. The final run uses a new held-out set from an agent that does not see the prompt: 24 screen requests (16 to explain or show, 8 that the model must refuse) and 16 change requests on 8 template files. Bars: 15 of 16 explain requests clean after at most one repair; 7 of 8 refusals; at most 2 of 16 explain requests refused; the median first call not above 5.75 s (4.25 s plus the noise of 1.5 s); 13 of 16 changes right by the controller's reading, and 12 of 16 with only changed lines. This check runs outside the app, so it shows only the faults of the kit. A device test then runs the host check of the app over the template files that the model made, with no paid call: at most 2 of 24 miss.
3. **Fit on the stage**: each template at its caps, and at 1.25 times its text caps, shows with no scroll on the stage at the phone and the fold size (a device test).
4. **The picture only grows**: for each saved template file, each longer prefix keeps the drawing commands of the shorter one (a kit test).
5. **Live check** (the method of 2026-11-21): a free rehearsal with a scripted stream of deltas, then paid dialogues on new subjects, then a paid probe of first turns that are no screen. The recorder gets the marks `first_line_visible`, `first_part_visible`, `picture_complete`, `controls_on`, the count of preview updates, and the count of previews that went away.

Estimates for the first screen of a talk, after speech end (from fills of one line and one rate, not a promise): frame about 3.1 s, first shape about 3.5 s, picture complete about 5.0 s, controls on 6.4 to 7.8 s. Today each of the three is 8.4 to 12.3 s. No figure exists for a change.

**Paid budgets that this design asks for.** The approval of this design approves them. The 15 calls that are left of the template check stay with that check.

- Model check: at most 150 calls of `gpt-6.1-terra` at the effort low.
- Live check: at most 480 s of voice and 160 chat calls.

### 8. Order of the build

Two phases. Each ends in a state that can go to `main` after the owner's yes.

1. **Templates**: the template file in the kit (the expansion, the faults of a line, the two fixes with no call), the template prompt, the plan step with the fall to the free plan, the repair by lines, the change by template lines, gates 2 and 3, a live check.
2. **The stream**: the preview step of the kit and gate 1a run beside phase 1, so the device cost is known early. Then the hand-off of the text, the preview on the stage (also for a free plan before its host check), the stream of a new screen and of a change, gates 1b, 4, and 5.

The plan is `docs/superpowers/plans/2026-11-21-stage-templates-stream.md`.

## Assumptions

- The model writes the lines form as well as the fill of one line. Basis: it writes today's plan as lines. Test: gate 2.
- A screen with a text at 1.25 times its cap still fits the stage. Basis: the fills had free space in the saved-app pane. Test: gate 3.
- The deltas of the wire are a few characters each, so a line is complete soon after its last character. Basis: the wire test helpers. Test: the marks of the live check.
- A first turn that is no screen rarely gets a frame, because the template model refuses it in about 1.3 s. Basis: none measured. Test: the probe of gate 5.
- The two fixes with no call would have removed both repairs of the template check. Basis: the fault texts of the two records. Test: gate 2.

## Open points

- The device cost of one preview update (gate 1).
- How the lines form changes the time and the clean rate against the fill of one line (gate 2).
- How a model changes a template file by lines (gate 2). No record exists.
- How often a frame shows and goes away (gate 5).

## Left to the builder

- The exact words of each line of a template file, and of the template prompt.
- The names in the code, and where the preview state lives.
- The sandbox form of the preview loop, after gate 1.
- The test names, and the fixtures of the scripted stream.
