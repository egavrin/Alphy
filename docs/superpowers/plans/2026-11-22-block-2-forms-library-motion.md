# Block 2 plan: data forms, the library first set, motion and diagrams (Alpha 21 to 23)

**Goal:** three finished components after Alpha 20, each with its own push and prerelease: data in a form that fits it (stage 5), pictures from ready parts (stage 6), and a picture that moves by itself with a diagram form (stage 7).

**Spec:** `docs/superpowers/specs/2026-11-22-voice-scene-and-forms-design.md`, sections 7.4, 9.3, 9.4, 11, 12, 14, 15, 16, and 17. The spec is the authority. This plan adds the exact values that section 18 leaves to the plan.

**Base:** `origin/main` at `d662897` (Alpha 20). Records of block 1: `docs/superpowers/plans/2026-11-22-one-scene.md` and the ledger `.superpowers/sdd/2026-11-22-one-scene/ledger.md` (not in Git).

**Form of this plan:** one requirement list with its tests for each stage, for one build job. It has no task steps. The job reads the code and chooses the files.

## The owner's order

Requirement, his words of 2026-11-22: «давай начнем тогда с первой, второй, третьей. Потом пятый, шестой, седьмой. И потом все остальное ... После каждой стадии делай следующий релиз. Планируй блоками по 3 ... Ты их один за одним делаешь, выпускаешь, пушишь и делаешь релиз. Как заканчиваешь, планируешь следующие 3 стадии. И точно в таком же порядке все выпускаешь.»

- The push and the release of each stage have his word when every gate of the stage holds. A gate that misses its bar goes to him first.
- His phone check comes after each release. A failed check becomes the first fix of the next stage.

## How each stage runs

Requirement: the delivery rules of 2026-11-22 (`~/.claude/CLAUDE.md`, "Delivery speed").

1. One build job for the stage (Opus, effort xhigh), in the session worktree, under `.superpowers/sdd/2026-11-22-one-scene/job-rules.md`. The job writes the tests of a requirement before its code, builds, runs its tests one time at the end, and hands back one tested result with a report of at most 40 lines.
2. The controller reads the whole product diff and sends one fix list to the same job.
3. The controller runs the commit gate (Slopo, then the architecture scan, then the disposition file) for each commit.
4. One set of paid checks for the release, with its budget stated before the first call and its spend after the last one. Each model check has a tuning set and a final held-out set, and no tuning runs on the final set.
5. The version commit, the release candidate (a Sonnet job: release build type, signed with the debug keystore, `aapt dump badging` exit 0, no `application-debuggable`, the update check over the release before), the untangle review, the push, and the prerelease.
6. The full device suite one time for the release, beside other work, with the error-level device log saved.

Time target: about 1 hour for 1000 changed lines. A stage that takes more than three times its target stops, and the cause goes to the owner.

## Global constraints

From the spec (sections 5, 11.1, 14) and `AGENTS.md`. Each requirement below includes them.

- Speed comes first. The first voice word, the first line of a first screen, and its controls do not get later (G9). The free stream gate (median at most 0.3 s) stays binding.
- Strict text caps. A text outside the picture has a hard cap, and a long card text is cut at a sentence end.
- The page of a template screen does not scroll at the phone size and at the fold size.
- The app draws at font scale 1.0.
- The model decides whether a turn gets a screen. No rule by keyword decides it.
- A form never shows a value that no source has.
- The voice never waits in silence for a screen.
- Each form is drawn line by line while its call writes it. A fault leaves one line out, and one repair call writes only the faulty lines. A change by lines and the merge by name work for each form.
- No compatibility with files of earlier development versions.
- Expected results and acceptance decisions of a generated check stay in the host.
- A published APK is a signed APK of the Gradle `release` build type.

## Review focus

The five conditions that the spec implies, that are most likely to reach a person, and that each need a test in the stage that owns the code:

1. A searched data form with a number that no source excerpt holds (stage 5, requirement D6).
2. A table with more rows than fit on a phone, and a row label longer than its cap (stage 5, D3).
3. A request for a thing that the catalog does not have, and a search that is late or has no model loaded (stage 6, L5 and S4).
4. A spoken command while a `play` scene runs, and a scene change while the glide of the change before it still runs (stage 7, M4 and M6).
5. A new stepped control with more options than the classifier choices (stage 5, V4): today `LiveProtocol.MAX_STEPS = 6` copies `BUDGET.options` of the kit by hand.

## Decisions of this plan

Each is an Implementation choice inside the spec, unless it names a Requirement.

1. **Families (spec 7.4, Requirement).** The routing decision of a `screen` names a family: `explain` or `data`. The early template call of a first screen stays the explain family. For `data`, the host stops the early call and starts the data template call with the brief of the routing call, and with the source excerpts when a search ran. The practice family comes with stage 9.
2. **The mark of a data form is state.** A marked row, cell, or point is a value of the app, as the option of a choice is. A tap on a row or a point moves the mark. A spoken request moves it by the press path that exists (a routing press, or a follow `mark`). So the rows and the points are options in the line of the shown screen.
3. **No invented data, checked by the host.** For a searched data form, the host compares each number of a data cell or of a point with the source excerpts. The host already holds them: each source has its title and its summary (`Source.summary`), and the generation prompt carries them as excerpts (`ChatToolReply.generationPrompt`). A number that no excerpt holds turns the form into `nodata` with its reason. A searched form holds no computed value. The spec gives the rule; the host check is the method.
   - The comparison is by digits: a decimal comma equals a decimal point, a space or a comma inside a number is dropped, and a score such as «2:1» is its two numbers. A number that only counts the rows (a rank column that the model adds) is not a data value and is not checked.
   - The check can reject true data. The model check counts each false reject: a form that became `nodata` although the excerpts hold its values, by the controller's reading.
4. **A data form has one narration line `say`**: the point of the data. It has no steps, so it gets no scene item of the classifier.
5. **The scene text of the classifier** holds only the title, the step labels, and the step sentences of a stepped scene, as in Alpha 20. No fetched data goes to the Decisions model in block 2.
6. **The first areas of the library** are Human body (11 objects), Space (9), and Math figures (7): 27 objects. The spec says "about 30 objects of two areas", and no two areas of its list hold 30. Basis of the choice: the paid dialogues use these subjects (the heart, the Moon, the orbit, the theorem of Pythagoras), so the paired check has requests.
7. **The search accuracy gate runs against the whole name list of spec 12.5** (about 150 names). Names with no drawing are entries of the test only. With 27 real entries and 24 candidates the gate would pass by count alone.
8. **The review sheet of new objects** is accepted by the controller before the release, and the sheet goes to the owner with the release. He can reject an entry after it.
9. **The spoken commands of a `play` scene** («быстрее», «медленнее», «стоп», «дальше») go by the routing press, as spec 9.4 says. The classifier keeps its commands for stepped scenes.

## Decisions of the owner on four questions (2026-11-22, after the first version of this plan)

Each is a Requirement. His words are quoted.

- **U1. The cap of the model check: up to 2,000 calls for each stage.** «используй, бюджет для вызовов может еще сделать, сколько тебе нужно ... Мы можем просто лимит сделать, там, не знаю, 2000 на стадию, например.» Before his word, `kit/modelcheck.js` held `LIMIT = 689` and `TEMPLATE_LIMIT = 150`, with 651 calls used, 126 of them template calls. Stage 5 raises both by 2,000. Each later stage raises them again by what it needs, inside 2,000 for the stage. The budget of each stage in this plan stays the working budget: the cap is not a target.
- **U2. The classifier cost: no limit.** «Лимит для классифайер нормально. Потом, я думаю, там, типа, пару баксов за 30 минут достаточно.» The controller reads this as: the present cost is fine, about two dollars for 30 minutes of one talk is acceptable, so this block adds no limit. The reading went to him in the answer to his message.
- **U3. The search time: no gate.** «если у нас есть эмбеддинги, мы это используем для поиска, нам, я думаю, не нужно проверять время. При небольшом количестве объектов, там до 1000 или пара тысяч, нам особо не нужно об этом думать. Да даже десятков тысяч, на самом деле.» Stage 6 keeps requirement S4 (the search never delays the template call) and writes the measured time to the ledger. It has no time bar and no run on a phone.
- **U4. The speed gate: no paired run against Alpha 17.** «Не нужно сравнивать там спидгейт, у нас и так со скоростью пока нормально, мы просто не должны сделать хуже ... Но ты можешь прогнать, там, например, по сравнению там, с 20-й версией.» G9 is judged from the live check of each stage against the same steps of the Alpha 20 record (`evidence/one-scene/live-3/`, `uxHeart`: first line 4.8 s, controls 8.7 s). A first line or controls later by more than 0.5 s goes to him. The free stream gate stays binding.

---

# Stage 5: Comparison, table, chart (Alpha 21)

**What the owner gets (spec 15):** data in a form that fits it. An honest card when data is missing.

## Requirements

Data family (spec 7.4, 11):

- **D1.** The routing decision of a screen names its family (decision 1). The data family has its own prompt section and its own forms: `compare`, `table`, `chart`, and `nodata`. The explain prompt does not grow.
- **D2. `compare`:** two or three things side by side, with a sign or a name for each, and up to 6 rows with one value for each thing. One row can be marked. A card gives the point. Caps: 3 things, 6 rows.
- **D3. `table`:** a header and rows, a caption, and one marked row or cell. Caps: 4 columns, 12 rows, 8 rows visible on a phone. More rows scroll inside the box of the table, and the page does not scroll.
- **D4. `chart`:** bars or a line, with a label and a value at each point, and one marked point. Cap: 8 points.
- **D5. `nodata`:** a short card with what is missing and what the person can say next. Cap: 2 sentences. The voice says the same.
- **D6. Sources (spec 11.3).** Known facts: the data call alone. A fresh fact: the web search as today, then the data call with the source excerpts. The host check of decision 3 runs on each searched form. A form with fetched data shows the source as a site name and the time of the data.
- **D7. The mark (decision 2).** A tap and a spoken request move it with no template call. The line of the shown screen names the options and the marked one.
- **D8. Native parts (spec 11.4).** The native table gets a mark for a row and for a cell. The native chart gets a label at each point when it has 8 points or fewer.
- **D9.** The rules of spec 11.1 hold for each new form: the stream, the one repair call, the change by lines, the merge by name, and the caps.
- **D10.** The stage lifetime of Alpha 20 holds: a data form replaces a scene of an earlier subject, and the work card shows during its search and its build.

Voice riders (the open list of Alpha 20):

- **V1. «Продолжай» as a command.** The input question gets the choice `continue`. At a confidence of at least 0.75 the host walks the rest of the steps as the routing press does. A paid wording check comes first: text calls of the Decisions API on «Дальше», «Продолжай», «Давай дальше», «Продолжи», «Верни назад», «Повтори», «Покажи второй шаг», and two questions, each two times (at most 40 calls, about $0.01). The text calls only screen the wording: they are not the native trigger. The deciding check is the live `uxSteps` run: «Дальше» gives `next` and «Продолжай» gives `continue`. If not, the choice leaves the build before the release.
- **V2. The start of the follow call.** It starts at the first sentence end of the voice, or 2,500 ms after the voice began its reply, the earlier of the two. In Alpha 20 one long sentence delayed a scene to 19 s after the speech. At 2,500 ms the call can see a cut sentence. Words that begin an answer count as an answer (`answered` true), so the new scene does not narrate over the voice: the follow instructions get one sentence for it, and `LiveFollowCheckTest` gets a case with a cut answer (expected `screen`, `answered` true).
- **V3. The command bar.** The live check records the confidence of each command. No change of the 0.75 bar in this stage: two «Дальше» of three were below it in the Alpha 20 check, and three samples are too few. The new choice of V1 changes the question, so the samples start again with this stage.
- **V4.** One test fails when `LiveProtocol.MAX_STEPS` and the option cap of the kit differ.
- **V5. The locked tool database (last, time box 30 minutes).** Read `os3/suite-20/errors-*.txt` of the Alpha 20 suite and the log of the `replay` failure (`os3/job/end-fa/`). Find the cause. Correct a fault of the test setup. Report a product fault with its evidence, and correct it only when the change is small and has a test. At the end of the time box, report what the logs show.
- **V6 (last, time box 30 minutes).** The rig helpers of `ClassifierRehearsalTest`, `FollowTurnRehearsalTest`, and `StageTapRehearsalTest` move to one shared place.

## Tests, one behavior each

- Kit: each form compiles from a file at its caps, a line over a cap is a fault with its name, a repair replaces only the faulty lines, a change by lines keeps the other lines, and the stream of each form draws line by line.
- Kit: a table of 12 rows gives a box that scrolls and a page that does not. A long row label is cut at its cap.
- Host: the routing schema holds the family. A `data` decision stops the early explain call and starts one data call. An `explain` decision keeps the early call.
- Host: a searched form whose numbers are all in the excerpts stays. One number that no excerpt holds gives `nodata` with the reason. A known-facts form runs no check.
- Stage: a tap on a row marks it, the shown screen names it, a routing press of a row moves the mark, and a follow `mark` moves it with no words.
- Stage: a data form after a stepped scene deletes the scene item of the classifier.
- Native: a marked row and a marked cell show, and a chart of 8 points shows its labels.
- Voice riders: V1 (a `continue` command walks the rest and starts no routing call), V2 (a reply with no sentence end starts the follow call at 2,500 ms), V4.

## Gates

| Gate | Bar |
| --- | --- |
| Kit run and `--check` | 0 failures |
| Fit gate (`StageTemplateFitTest`) for each new form at its caps, phone and fold | OK |
| Stream gates 1a and 1b with a data form | 1b median at most 0.3 s |
| Model check of the data family. A tuning set of 10 requests and a final set of 20 held-out requests, known facts and searched. A searched request uses recorded source excerpts as a fixture. No tuning on the final set. | final set: clean files at the first try at least 16 of 20; no invented value in a searched form; at most 1 false reject of the host check; the call time reported |
| G7 for this stage: a comparison, a table, and a chart each show in their own form in the live check, on known facts (the new dialogue `uxData`) | 3 of 3 |
| G9 (U4): the first line and the controls of the first screen of `uxHeart` and `uxSteps` in the live check | no later than the Alpha 20 record by more than 0.5 s |

## Paid budget

- Model check: at most 100 template calls (60 for tuning, 40 for the one final run), 4 chat calls for the new case of `LiveFollowCheckTest`, and 40 Decisions text calls for V1.
- Live check: at most 600 s of voice, 60 chat calls, and $0.10 of Decisions calls. Dialogues: the new `uxData` (known facts: «Сравни Землю и Марс», «Покажи планеты таблицей», «Покажи это графиком», «А у какой самый длинный год?», a mark by voice), `uxCompare`, `uxFootball` (a searched table, which can end in `nodata`), `uxHeart`, and `uxSteps`.
---

# Stage 6: The library, first set (Alpha 22)

**What the owner gets (spec 15):** pictures from ready parts, with a measured effect on speed and look.

## Requirements

Registries and drawing (spec 12.5, 12.6):

- **L1.** Three registries: shape kinds, generic elements, and ready objects. The grammar, the fault texts, the prompt text, the tests, and the review sheet come from the registries. No list of them is written by hand in the engine, the grammar, or the prompt.
- **L2. New shape kinds:** `path` (through points, straight or smooth, open or closed), `arrow`, and `arc`. New properties: a gradient fill, a glow, an opacity, and a turn by an angle.
- **L3.** A scene holds up to 20 shapes. A ready object or a generic element counts as one shape. On a phone the picture grows into the empty zone under the app. The fold layout keeps its two columns.
- **L4. The generic elements of spec 12.5** (about 40, in 8 groups), each one entry with one draw rule and its numbers.
- **L5. The ready objects of decision 6** (27). One data file for each object, in a directory of its area, with no code. An entry holds its id, its names and other words in Russian and English, one line on what it shows, its box, its layers of paths with a color role each, and its named parts with a label point. Limits: at most 40 path segments, color roles only, readable at the smallest size that a scene draws.
- **L6.** A template line places an object or an element: its name, its place, its size, an optional label, an optional marked part, and for an element its numbers. An unknown name is a fault with that name, the screen still comes with an emoji or free shapes in the same call path, and the host writes the name to the release log line (the miss log of spec 12.8).
- **L7. The build** makes the catalog, the vectors of the search, a review sheet with each entry in light and dark theme, and the catalog tests (unique names, the segment limit, roles only, a label point for each part).

Search (spec 12.2, 12.3):

- **S1.** The app picks 24 candidate objects for a request with no model call: the vector of the request against the vector of each entry (EmbeddingGemma, `semantic/SemanticRuntime.kt`), and a word of the request that equals a name or another word of an entry.
- **S2.** The vectors of the entries come from the same model that the phone runs. The catalog keeps the signature of the model, and a new model makes new vectors.
- **S3.** The prompt gets the 24 candidates (name, one line, parts) and all generic elements. Its size does not depend on the size of the catalog.
- **S4.** The candidates are ready at most 150 ms after the request text is known. A late search, or a model that is not loaded, never delays the template call: the call starts with the generic elements only.
- **S5.** The voice mode loads the embedding model when the mode starts.

## Tests, one behavior each

- Kit: each shape kind, each element, and each object draws from a template line. A line over a limit is a fault. An unknown name is a fault with the name, and the file still compiles to a screen.
- Kit: the prompt text, the grammar, and the fault texts change when an entry is added to a registry, with no other edit.
- Catalog tests of L7.
- Host: a request that names an object in Russian or in English gets it among the candidates. A request with no fitting entry gets a screen with no library object.
- Host: a search of 300 ms does not delay the template call start. With no embedding model, the word match alone gives candidates.
- Host: a new model signature makes the old vectors unused.

## Gates (spec 12.4)

A gate that misses its bar goes to the owner first: the library does not ship then.

| Gate | Bar |
| --- | --- |
| Search time and load time of the embedding model, on the emulator (U3) | reported in the ledger, no bar |
| Search accuracy (decision 7): 100 held-out requests, each with the objects that fit it | a fitting object among the 24 candidates for at least 90 |
| Effect on the picture: a paired model check on 20 held-out requests, free shapes against the library | the pictures with the library judged better by the controller, pair by pair; clean files at the first try not fewer; the call time not longer |
| A thing with no entry: 10 requests | a screen each time, in the same time |
| The review sheet of the 27 objects, both themes (decision 8) | each entry reads at the smallest size, or it is drawn again or left out |
| Fit gate, stream gates, G9 | as in stage 5 |

## Paid budget

- Model check: at most 120 template calls.
- Live check: at most 600 s of voice, 60 chat calls, and $0.10 of Decisions calls. Dialogues: `uxHeart`, `uxOrbit`, `uxPythagoras`, `uxWhy`, and `uxSteps`.

## Size

This stage is the largest of the block: the registries, about 40 elements, 27 objects, the build, and the search. The job fixes the entry format and the catalog format first. Then it may start one helper job for the object drawings, which share no file with the search code.

---

# Stage 7: Motion and diagrams (Alpha 23)

**What the owner gets (spec 15):** a picture that moves by itself on the fast path. A diagram with all its parts named.

## Requirements

Motion (spec 9.3, 9.4):

- **M1. The `play` control of `scene`:** a value that runs from its start to its end in a named count of seconds, again and again, with pause and play, and three speeds: slow, normal, and fast.
- **M2.** The kit `animate` gets a loop and a pause. The host playback control that exists is reused.
- **M3.** The template prompt no longer sends "a thing that moves by itself" to the full generation.
- **M4.** A spoken «быстрее», «медленнее», «стоп», or «дальше» is a press of that control: one routing call, no template call (decision 9). The line of the shown screen holds the state of the control. A tap on the control is heard, as each tap is.
- **M5. The smooth change (spec 9.3).** When a control changes, the numbers of the shapes glide to their new values in about 250 ms when both states have the same shapes. In each other case the two states cross-fade. The host does this between two frames, with no extra call into the app.
- **M6.** A second change during a glide starts from the picture that shows at that moment. The last frame of a glide equals the new state.
- **M7.** A walk and a `play` control in one scene: the narrator moves the steps, and the motion runs inside each step.

Diagram (spec 11.2):

- **G1. The `diagram` form:** one picture with all its parts named at once, up to 8 labels with leader lines. One part is marked. A card gives the fact of the marked part. The control is `steps` over the parts, with `say1..N`.
- **G2.** A diagram uses the label element and the named parts of a ready object of stage 6. A diagram of a thing with no entry draws free shapes with labels.
- **G3.** The walk, the spoken commands, the classifier, and the follow call of Alpha 20 work on a diagram as on each stepped scene.

## Tests, one behavior each

- Kit: a `play` scene compiles, loops, pauses, and runs at each speed. A file with `play` and `steps` compiles.
- Kit: a `diagram` at its caps compiles, a ninth label is a fault, and the marked part follows the step.
- Stage: a `play` scene runs with no press. A press of pause stops it, and the shown screen names the state. A routing press of «быстрее» changes the speed with no template call.
- Stage: a change of a slider glides, and the last frame equals the new state. A change between different shape lists cross-fades. A second change during a glide ends in the second state.
- Stage: a walk on a diagram marks each part with its sentence, and «Покажи третий шаг» marks part 3.
- The model check asks for motion in 6 requests: each gives a template file, not the full generation.

## Gates

| Gate | Bar |
| --- | --- |
| Kit run and `--check`, fit gate, stream gates | as in stage 5 |
| Model check of the explain family with `play` and `diagram`: 20 held-out requests | clean files at the first try at least 16 of 20; each motion request is a template file; a picture of each first state shows the point for at least 5 of 6 (G3 of the spec) |
| G8 of the spec: motion on the fast path, in the live check | controls on at most 12 s after the end of the speech |
| The glide of M5 on the emulator | at most 300 ms from the press to the last frame |
| G9 | as in stage 5 |

## Paid budget

- Model check: at most 80 template calls.
- Live check: at most 600 s of voice, 60 chat calls, and $0.10 of Decisions calls. Dialogues: `uxOrbit`, `uxTap`, `uxLesson`, `uxHeart`, and one new dialogue of a diagram (the parts of the eye).

## Changes of 2026-11-23, after Alpha 22

Each is a Requirement. The owner saw them with a moving sample of the look and answered «Yes, built it». The sample is not in Git: `evidence/design-target-2026-11-22/stage-7-sample/`. Where a line here differs from the lists above, this line holds.

- **C1. Smooth motion.** Before this stage, the kit moves a value with a timer of 50 ms, which is 20 pictures each second. A `play` scene with image objects shows at least 30 different pictures each second. New gate: the host counts the pictures of a `play` scene with two images for 5 s on the emulator.
- **C2. The diagram reuses Alpha 22.** Alpha 22 names each part of a ready object and marks one part. Stage 7 adds the walk over the parts and a control for it.
  - The control is a number stepper: a button for the part before, the count («2 из 7»), and a button for the next part. It shows no part name.
  - The name of a step is the name of the part in the entry, in the language of the screen. The model writes no label and no condition for the mark of a part of an entry. Basis: a part name can be longer than a label cap («правое предсердие» has 17 characters), and the live check of stage 6 showed a failed repair of such labels.
  - A diagram has up to 8 steps. Each other stepped scene keeps 6. The classifier and its scene text accept 8 steps for a diagram.
  - The card shows the name of the marked part as its title and the fact of that part as its text.
- **C3. Motion as a change.** A spoken request for motion on a still scene that shows gives changed lines of the same scene, not `outside`. Basis: «Пусть она движется сама, без остановки» in the live check of stage 6 built a full app in 43 s and in 54 s. The model check has 3 of its 6 motion requests as such changes.
- **C4. The size of the run-time.** Each app source holds the kit run-time: 56,297 of 65,536 bytes at Alpha 22. The run-time of this stage has at most 57,800 bytes. The test of six formula lines for one element keeps 24 of 33 elements or more.
- **C5. Gates with motion.** The stream gate gets a file with an image object. The fit gate gets a `play` scene and a diagram with 8 parts, each with the longest permitted texts.
- **C6. «дальше».** In a scene with steps, «дальше» moves to the next step, as in Alpha 20. In a `play` scene with no steps, it starts the motion again after «стоп».
- **C7. A scene that no person sees does not run.** The motion stops when the stage leaves the screen, and it runs again when the stage is back.
- **G2, as built (fix list 2).** The free diagram is not built in this stage: a diagram walks the parts of a ready object, and a thing with no ready object gets steps, as in Alpha 22. The play row shows only in a scene with no control line, so in a scene with steps «стоп» and «быстрее» get a spoken answer and no press.
- **Known limit.** A command spoken while the voice narrates can get no reaction from the voice model (a fault carried from Alpha 21). This stage does not correct it. A tap on the play row works. A scene with one more control has no play row (fix list 2), so there the motion has no stop.

## Stage 7b: the faults that stage 7 left (Alpha 24)

Requirement, the owner's words of 2026-11-23 after Alpha 23: «релизные прогоны можешь остановить, лучше исправить ошибки в 7-й стадии и сделать еще один релиз». The four faults are those of the option that he chose. The push and the release have his word when every gate below holds.

Requirements:

- **R1. The lock of the app store.** A tap must not wait for a lock cycle. In Alpha 23, `ToolNetworkAuthority.refreshAccess` opens a second `ToolStore`. The open holds the library gate (`ToolLibraryStorage.open`) while it waits for the database. A press holds a transaction and waits for the same gate in the `writableDatabase` getter (`requireCurrent`). The cycle ends after the busy wait of the platform (about 2.5 s).
  - The invariant: no thread waits for the library gate inside a database transaction, or no thread waits for the database while it holds the gate.
  - Three guards stay, each with a test: no commit goes to a library file that is no longer current, no open creates a file for a replaced selection, and a revoked network grant shows at the next read.
  - The schema, the file names, and the open flags of the store do not change.
- **R2. A request that the screen already meets.** The change call can reply that the screen already shows what the person asks. Then the stage keeps its screen, the app makes no further call, and the voice says so in one sentence. In Alpha 23 such a reply changed nothing, and the app built the screen again as a full app (42 s in the live check). A reply that changes nothing and does not say so keeps the behavior of Alpha 23.
- **R3. A part name that the model gives.** One mechanism serves two faults.
  - A diagram of a thing with no ready object: the model gives each part its name and one point of the picture. The kit draws the names in the side columns with leader lines, the ring on the marked point, and the accent on the marked name, with the label layout of a ready object. The model writes no text shape for a part.
  - A diagram of a ready object in a language that the entry has no names for: the model gives the name of each part in the language of the request, and the picture, the stepper, and the card show these names. The language check of the host does not change.
- **R4.** The documents say what ships.

Gates:

| Gate | Bar |
| --- | --- |
| R1: a test with two threads (one holds a transaction and reads through the getter, one opens a second store) | both end within 0.5 s after the hold, and no locked database error comes out |
| R1: 20 taps on a moving scene, 5 runs | no tap waits more than 1 s |
| R1: the full device suite before the publish | no new failure in a store, library, or grant test |
| R2: a device test and the model check of changes | the screen stays, no app call follows, the voice gets its sentence; the change set keeps 16 of 16; requests that the screen meets give the new reply in at least 3 of 4 |
| R3: model check on 12 new held-out requests (6 things with no ready object, 4 ready objects in German, Spanish, French, and Italian, 2 in Russian and English) | at least 10 of 12 clean at the first try, each one a diagram with parts |
| R3: the model's own files on a device | each of the 12 shows, with no fall back |
| R3: the fit gate with 8 part names of the longest permitted length, on the phone and on the fold | no scroll, and no label over a shape |
| R3: the look | phone pictures in both themes that the owner sees before the release |
| Carried | the picture rate alone (30), the heart walk 5 of 5, the kit tests, the fit and stream gates of stage 7 |

Paid budget: at most 120 template calls, 600 s of voice, and 80 chat calls.

Not in this stage: a pause beside another control, the icon-only pause button, the fast small part, the fold at density 520.
- **Working budget**, from the spend of stage 6: at most 160 template calls, 1,200 s of voice, and 120 chat calls.

---

## After the block

- The plan of block 3 (stages 4, 8, 9: weather, photos, practice) is written when Alpha 23 is released. The terms of use of Open-Meteo and of Wikimedia Commons are read before it (spec 17).
- Each stage ends with its open list in the ledger. The first rider of the next stage is a failed phone check of the owner.

## Self-check

- Spec coverage: section 15 rows 5, 6, and 7 map to the requirement lists D, L and S, and M and G. Spec 11.1 is in the global constraints. Spec 12.4 is the gate table of stage 6. Spec 12.7 (other sources of entries) and 12.8 (rounds) need no code in this block beyond the miss log of L6.
- Not in this block, by the spec: `fact`, `forecast`, `photo`, `check`, `task`, packs that load with no release, and entries that the device makes.
- Each paid set has its working budget, inside the owner's cap of 2,000 model check calls for each stage (U1).
