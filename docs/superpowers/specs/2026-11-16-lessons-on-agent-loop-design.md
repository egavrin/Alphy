# Lessons on the agent loop

Date: 2026-11-16. Status: approved by the user on 2026-11-16 with option A.

## Intent

Alphy must have one reliable generation mechanism, not several. The user
stated this on 2026-11-16: "все нужно перевести на агентский цикл. У нас
должен быть один надежный механизм, а не много разных."

Mini-apps already use the agent loop (`tools/agent/AppAgent.kt`, from the
2026-11-13 agent generation design). Course lesson slides still use the
staged builder. That design said: "A later change can move lessons to the
agent loop and then delete that code." This document is that change.

## Goals, non-goals, anti-goals

Goals:

- Generate every course slide with `AppAgent`.
- Delete the staged builder: the needs plan, the parts catalog, the parts
  assembly, the selector, the composition runtime, and the writer repair
  that only the staged builder uses.
- Retire saved mixed mini-apps from earlier versions. The user approved this
  on 2026-11-16: "старые приложения миксованные можно удалять."
- Keep the course contract unchanged: slide kinds, the exercise field
  `courseExerciseV1`, host-owned answer controls, source references, the
  lesson illustration intent, and the local-only output rules.

Non-goals:

- No change to course outlines, the course store schema, or the player UI,
  except the old-slide behavior in "Saved lesson slides" below.
- No new lesson features.
- No compatibility layer for earlier formats (AGENTS.md).

Anti-goals:

- A second lesson-only loop or a lesson-only copy of `AppAgent`.
- Silent deletion of saved courses, slides, or mini-apps (AGENTS.md).
- Loss of a lesson requirement that only the deleted parts tests check.

## Current state (facts from the code at dc58bee)

- `ToolGenerationService.generateLesson` calls `buildCandidate`
  (`ToolEngine.kt` 434-983). It requests a needs plan (`NeedsPlan`), makes
  part requests (`ToolParts.requests`), and assembles them
  (`PartsGeneration.lesson`). Then it takes one of three routes:
  - NATIVE: parts only. One model call (the plan).
  - WHOLE_SCREEN: no parts. A second call writes the whole slide.
  - MIXED: parts and a generated fill. The saved definition has a
    `composition` key.
- `CourseGeneration.generate` validates the result with
  `CourseExecutionChecks` (a `ToolExecutionPolicy`), `CourseExercise.read`,
  and `checkedCourseUi`. It maps failure codes to `CourseWorkflowError`.
- NATIVE and WHOLE_SCREEN slides save a plain app definition with no
  `composition` key. They do not need the staged code to open.
- MIXED slides save a `composition` key. `CoursePresentation.checkedSlide`
  permits that only for a scene slide (line 297).
- A slide that fails to load becomes `CoursePage.Unavailable` with
  `CourseSlideIssue.INVALID`. Its `generationRequest` is null, so the player
  offers no new generation. If that slide is the current slide, the learner
  cannot advance.
- `AppAgent` needs at least two model calls: `write_app` and `finish`. The
  optional screenshot review adds one call. `finish` needs 1 to 12 checks,
  and each check must end with an expectation. A static slide can pass with
  a text expectation only.
- `AppAgent` has no policy hook. It names the app language from the prompt
  text after "User request: ". A lesson prompt starts with English
  instructions and JSON, so the detected language can be wrong.
- `AppAgent` failures use the code `widget_agent_incomplete`.
  `CourseGeneration` maps that code to UNAVAILABLE.

## Design

### Lesson request

`AppAgentRequest` gets one optional lesson target. Implementation choice:
a `LessonTarget` value with these fields:

- The slide kind (`CourseSlideKind`).
- The permitted source IDs.
- The course execution policy (`CourseExecutionChecks.policy`).
- A language hint, detected from the reached slide goal, not from the
  whole prompt.

Implementation choice (the language rule): the loop keeps the rule of the
staged builder. The model uses the language that the course requests, or
else the language of the reached slide goal. The hint is not a forced tag.
The existing `GenerationLanguage` check compares the visible text with the
tag that the model declares. A forced tag from the course topic could reject
slides in a language-learning course, for example a Russian learner with a
Spanish topic. The live check includes one such course.

`generateLesson` calls `appAgent(key, session).run(...)` with this target.
It returns `LessonToolPreview(preview, sourceIds, illustration)` as today, so
`CourseGeneration` keeps its contract.

### Instructions

`AppAgentGuide` adds a lesson section when the target is present. It states
the rules that the host checks:

- No capabilities, no collections, no immersive presentation, no device
  controls, and no actions except live scene inputs.
- A scene (canvas surfaces) only on EXPLANATION and EXAMPLE slides.
- CHOICE, FILL_IN, and ORDER slides put the exercise in `initialState` as
  the string field `courseExerciseV1`, with the fields that
  `CourseExercise.read` requires. The host supplies the answer controls, so
  the app has no answer control and no `actionId`.
- Cite only the permitted source IDs.

`CourseGeneration.SLIDE_INSTRUCTIONS` changes its first line. It must not
mention lesson parts.

### Host checks in the loop

When the target is present, `AppAgent.validate` also runs the course policy
`validateDefinition`, `CourseExercise.read`, and the initial-state UI check.
A failure becomes `Feedback`, so the model sees the reason and can fix the
slide in the next step. This replaces the plan corrections of the staged
builder.

Implementation choice (scene slides): the staged builder required a visible
drawing on a scene slide (`requireVisibleDrawing`). The loop keeps this as a
host check. Each declared surface of a lesson scene slide must draw at least
one visible command in the first preview. Without it, a blank scene can pass
the model's own `finish` checks. No current test asserts this rule, so step
1 adds one.

### Sources and illustration

`finish` takes two lesson arguments. Implementation choice: arguments of
`finish`, not new definition keys, because `ToolSpec` has a key allowlist.

- `sources`: the cited source IDs. The host rejects an unknown ID, a
  duplicate, and an empty list when the course has sources.
- `illustration`: one optional `ImageIntent` when pictures are on. The host
  stores it with `withLessonImage`, as today. The player makes the picture
  later, as today. `generate_image` stays off for lessons.

### Failure mapping

`CourseGeneration` maps `widget_agent_incomplete` to a lesson failure that
the learner can retry. The text of `UNSUPPORTED_LESSON` changes, because it
names lesson parts.

### Cost

Slides that take one model call today take at least two. Slide generation
becomes slower. This is the cost of one mechanism.

## Removal

Delete `buildCandidate` and the lesson route in `ToolEngine`. Delete the
`composition` key from `ToolSpec.read`. Then delete each file and member
that stops compiling or has no reference. The compiler decides the final
set. The expected set:

- `NeedsPlan`, `ToolParts`, `PartsGeneration`, `JevSelector`,
  `ToolComposition`, `ClockParts`, `RecordParts`, `PartClockSample`,
  `NumberExpression`, `NativeBehaviorCatalog`, `SceneRequirement`.
- `GenerationRepair`, if only the staged builder and its diagnostic use it.
- The composition branches in `ToolSpec`, `ToolUi`, `ArtifactCoordinator`,
  `ToolClockProjection`, and the event path of `ToolEngine.evaluate`.
- The NATIVE and MIXED values of `ToolGenerationRoute`, if nothing else
  uses them.
- The tests of the deleted code and the shared fixture
  `GenerationFixturePlan.kt`, after their lesson requirements move (see
  Tests).

## Saved data

- Mini-apps with a `composition` key: `ToolSpec.availablePersisted` returns
  null. The library, chat card, open pane, and desktop tile show the
  existing "Формат мини-приложения недоступен" state. The data stays. The
  user can delete the app. This is the same behavior as the standalone
  retirement in 025413b.
- Lesson slides without a `composition` key: no change. They open as today.
- Lesson slides with a `composition` key (old MIXED scene slides): see
  Decision 1.

## Tests

Write each test first and see it fail.

- Before a parts test is deleted, list each lesson requirement it checks
  (source admission, kind rules, exercise contract, scene rules). Each
  requirement must have a test on the loop. Read `LessonPartsWorkflowTest`,
  `LessonRouteWorkflowTest`, `LessonSourceRouteWorkflowTest`,
  `LessonSourcePlanWorkflowTest`, and the four course tests that fake a
  needs plan.
- Course slide on the loop: a fake provider returns `write_app` and
  `finish` replies. One case for a static slide, one for each exercise kind,
  and one for a scene slide. Check the saved content, the sources, and the
  exercise.
- Policy feedback: a first app with a capability is rejected with a reason.
  The corrected app passes.
- Source admission: an unknown source ID and an empty list are rejected.
- Language: a Russian course gives a Russian app language.
- Mixed mini-app retirement: a fixture from an earlier version stays saved,
  shows the unavailable state, and the user can delete it.
- Live check: `LiveCourseWorkflowTest` with the real model on an emulator
  for each slide kind group.

## Order of work

1. Lessons on the loop, with their tests. The staged code stays for now.
2. Old lesson slides: regeneration of an INVALID slide (Decision 1).
3. Delete the staged builder and the composition runtime. Retire mixed
   mini-apps.

Step 3 makes old MIXED lesson slides INVALID, so step 2 must ship first.
Each step is one commit with its own Slopo review and architecture scan.

## Decisions

1. Old MIXED lesson slides. Requirement (the user selected option A on
   2026-11-16): the player offers a new generation for an INVALID current
   slide. Released builds alpha.11 and alpha.12 can save MIXED slides, and
   after step 3 they do not load.
   - The new slide replaces the old one only after it passes. The old slide
     stays saved until then.
   - This needs a replace operation in `CourseStore` (today a second save
     fails with `SLIDE_ALREADY_GENERATED`), a change in
     `CourseController.generate` (it stops when a slide row exists), and a
     presenter change (`CoursePage.Unavailable.generationRequest` is null
     for INVALID).
   - Old MIXED slides are scene slides, never answered exercises, so
     earlier answers stay readable.
   - Rejected options: B (keep them unavailable, the course can stop) and
     C (delete them after a confirmation).

## Left to the builder

- The exact `LessonTarget` shape and where the language detection lives.
- The wording of the lesson section in `AppAgentGuide`.
- The new `UNSUPPORTED_LESSON` text.
- Whether the screenshot review runs for lessons.
- Whether `INVALID_SCENE` and its learner text stay after the scene check
  moves into the loop.
- 3D scenes stay off for lessons. The player has no 3D renderer.
