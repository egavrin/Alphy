# Lessons on the Agent Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate every course slide with `AppAgent`, let the player rebuild a slide that does not load, and delete the staged builder and the composition runtime.

**Architecture:** `AppAgentRequest` gets an optional lesson target that the course code builds. The target carries the course policy, the slide check, the permitted source IDs, and a language hint. `AppAgent` runs these checks in its `validate` step and turns failures into model feedback. `finish` returns the cited sources and an optional illustration intent. The course store gets a replace path for a slide that does not load. After that, the staged builder and the composition code are deleted, and the compiler decides the final set.

**Tech Stack:** Android, Kotlin, Compose, SQLite (`SQLiteOpenHelper`), instrumentation tests (`androidTest`) on emulators.

**Spec:** `docs/superpowers/specs/2026-11-16-lessons-on-agent-loop-design.md`

## Global Constraints

- Worktree `/Users/egavrin/Documents/alpha/alphy-agent-generation`, branch `claude/agent-generation`, base dc58bee. Do not change the main checkout `/Users/egavrin/Documents/alphy`.
- AGENTS.md: no compatibility layers or migrations for earlier versions. Do not silently delete saved user data.
- Course contract stays: `CourseSlideKind`, `courseExerciseV1`, host-owned answer controls (no `actionId` in a saved slide), `CourseSlideContent`, source references, `lessonImageIntent`.
- Lessons: no capabilities, no collections, no immersive presentation, no 3D scene, no `generate_image`, no host images.
- Each task ends with one commit. Before each commit, run the Slopo review (`~/.codex/skills/slopo-review/SKILL.md`) and the architecture scan (`cd ~/Documents/architecture-scan-rs && ./target/release/architecture-scan check <worktree> --policy pilot/alphy/policy.json --baseline pilot/alphy/baseline.json`, exit 0). No attribution lines.
- Emulators: emulator-5570 and emulator-5572 only. Run tests with `$SP/runfull.sh SERIAL LIST OUTDIR` (`SP` is the session scratchpad). `export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`.
- Write each test first and see it fail.

## Review Focus

1. The model puts an answer control or an `actionId` on an exercise slide. Expected: rejected with a reason, then accepted after a fix (Task 1).
2. The model cites a source when the course has no sources, or cites no source when it has sources. Expected: rejected with a reason (Task 1).
3. The model never reaches a passing `finish` on a slide. Expected: the course shows a retryable failure, keeps earlier work, and saves no slide (Task 2).
4. A rebuild runs for a slide that has answers, or the course changes during the rebuild. Expected: rejected, the old slide stays (Task 3).
5. A Russian learner in a Spanish course gets a Spanish slide. Expected: accepted, because the language hint is not a forced tag (Task 1, and the live check in Task 6).

---

### Task 0: Timing baseline of the staged lesson path

- [ ] **Step 1:** On dc58bee, before any code change, run `courses.LiveCourseWorkflowTest` with the real DeepSeek key on one emulator. Use the same slide set as Task 6 Step 2. Record model calls and seconds for each slide. Task 6 Step 2 compares the loop against these numbers.

### Task 1: Lesson target in the agent loop

**Files:**
- Create: `app/src/main/java/com/alpha/assistant/tools/agent/AppLessonTarget.kt`
- Create: `app/src/main/java/com/alpha/assistant/courses/CourseLessonTarget.kt`
- Modify: `app/src/main/java/com/alpha/assistant/tools/agent/AppAgent.kt` (request, `run`, `validate`, `finish`, `taskMessage`)
- Modify: `app/src/main/java/com/alpha/assistant/tools/agent/AppAgentGuide.kt` (`system` gets the lesson section)
- Modify: `app/src/main/java/com/alpha/assistant/tools/ToolEngine.kt:429-432` (`generateLesson`)
- Test: `app/src/androidTest/java/com/alpha/assistant/courses/LessonAgentWorkflowTest.kt`

**Interfaces:**
- Produces, in `tools/agent/AppLessonTarget.kt`:
  ```kotlin
  internal class AppLessonTarget(
      val guide: String,                       // lesson rules for this slide kind
      val policy: ToolExecutionPolicy,         // course policy, also given to ToolGenerationSmoke
      val checkSlide: (ToolSpec, ToolPreview) -> Unit, // throws with a model-readable message
      val allowedSourceIds: Set<String>,
      val scene: Boolean,                      // the kind permits a scene
      val languageHint: String?,               // a language tag, not a forced tag
  )
  internal class AppLessonResult(val sourceIds: List<String>, val illustration: ImageIntent?)
  ```
- `AppAgentRequest` gets `val lesson: AppLessonTarget? = null`. `AppAgentResult` gets `val lesson: AppLessonResult? = null`.
- `ToolGenerationService.generateLesson(key: String, prompt: String, session: CloudSession, artifactId: String, lesson: AppLessonTarget): LessonToolPreview` calls `generateWithAgent` and maps `result.lesson` into `LessonToolPreview(preview, sourceIds, illustration)`.
- Produces, in `courses/CourseLessonTarget.kt`: `internal fun courseLessonTarget(kind: CourseSlideKind, sourceIds: Set<String>, policy: ToolExecutionPolicy, languageHint: String?): AppLessonTarget`. Its `checkSlide` runs `CourseExercise.read(spec, kind)`, requires `actionId == null`, and runs `checkedCourseUi(spec, preview.ui, preview.state, exercise, kind)`.

- [ ] **Step 1: Write the failing tests in `LessonAgentWorkflowTest`**

Use `AgentScriptProvider` and `writeAppReply` (`tools/AgentScript.kt`). Call `ToolGenerationService(provider, SandboxClient(context)).generateLesson(...)` with `courseLessonTarget(...)`. A finish reply is `{"tool":"finish","summary":"Done.","sources":[...],"checks":[{"name":"shows","steps":[{"expect":"text","contains":"<slide text>"}]}]}`.

- `staticSlideFinishesWithItsSources`: EXPLANATION, allowed `s1,s2`, finish `sources:["s2","s1"]`. Assert `sourceIds == listOf("s2","s1")`, `provider.calls.size == 2`, and neither `spec.json`, `state`, nor `ui` contains `sourceIds`.
- `exerciseKindsKeepTheCourseContract`: one case each for CHOICE, FILL_IN, ORDER with a valid `courseExerciseV1` string and no answer control. Assert `CourseExercise.read(result.preview.spec, kind)` is not null and its `actionId` is null.
- `answerControlIsRejectedThenFixed` (Review Focus 1): first app has `courseExerciseV1` with `"actionId":"answer"` and a `singleChoice` with `action:"answer"`. Second app is valid. Assert `provider.feedback(1)` starts with `write_app: REJECTED` and the result passes.
- `capabilityIsRejectedByTheCoursePolicy`: first app has `capabilities:["notification"]`. Assert feedback contains "REJECTED", and the second, clean app passes.
- `sourcesMustMatchTheCourse` (Review Focus 2): allowed `s1`: finish with `sources:["s9"]`, then `[]`, then `["s1","s1"]`, then `["s1"]`. Assert the first three feedback texts contain `finish: REJECTED` and the result has `["s1"]`. Allowed empty: finish with `sources:["s1"]` is rejected, then `[]` passes.
- `blankSceneIsRejected`: EXPLANATION with a canvas surface whose preview draws nothing, then a version that draws a filled rect. Assert the first feedback is a rejection and the second passes.
- `languageHintIsNotForced` (Review Focus 5): `languageHint = "ru"`, app `language:"es"` with Spanish text. Assert it passes with `spec.appLanguage == "es"`.
- `cancelStopsTheLoop`: cancel the `CloudSession` in the first provider call. Assert `CloudFailure.code == "cancelled"` and `provider.calls.size == 1`.
- `illustrationNeedsPictures`: pictures off, finish with `illustration` is rejected, then finish without it passes with `illustration == null`. Pictures on (use the image settings setup of `tools/AgentImageWorkflowTest`): finish with one valid intent passes, and `result.illustration` has its slot and alt text.

- [ ] **Step 2: Run the tests and see them fail**

Run: `$SP/runfull.sh emulator-5570 "courses.LessonAgentWorkflowTest" $SP/out-t1-red`
Expected: compile failure (`courseLessonTarget` and the `generateLesson` overload do not exist).

- [ ] **Step 3: Implement the target and the checks**

- `AppAgent.run`: for a lesson, skip `capabilityGuidance` (pass ""), set `threeD = false`, and pass `request.lesson?.guide` to `AppAgentGuide.system`.
- `taskMessage`: for a lesson, replace the "Build a new app" language lines with one line: use the language that the course requests, else the language of the reached slide goal, which is `languageHint` when present.
- `validate`: after the `ToolSpec` parse, run `lesson.policy.validateDefinition(spec.json)`. Give `lesson.policy` to `ToolGenerationSmoke` (constructor parameter `policy`) in `validate` and in `smokeFor`. After the smoke, run `lesson.checkSlide(spec, preview)`. When `lesson.scene` and `canvasSurfaceIds(ui)` is not empty, require `observeSurfaceGeometry(...).hasInk` for each listed surface of the first preview. Map each failure to `Feedback(message)`.
- `finish`: for a lesson, read `sources` (array of strings) and the optional `illustration` (one `ImageIntent`, accepted only when `imageGeneration.settings.selection() != null`, read with `ImageIntent.list`). Reject an unknown ID, a duplicate, an empty list when `allowedSourceIds` is not empty, and any ID when it is empty. Put the values in `Finished` and then in `AppAgentResult.lesson`.
- Screenshot review: off for lessons. Implementation choice: it adds one model call to every slide.
- `generateLesson`: replace the `buildCandidate` call. `buildCandidate` stays until Task 5.

- [ ] **Step 4: Run the tests and see them pass**

Run: `$SP/runfull.sh emulator-5570 "courses.LessonAgentWorkflowTest tools.AgentGenerationWorkflowTest" $SP/out-t1`
Expected: `OK` for both classes.

- [ ] **Step 5: No commit.** The course tests that fake a needs plan fail until Task 2. Tasks 1 and 2 are one commit (Task 2 Step 5).

---

### Task 2: Course generation on the loop

**Files:**
- Modify: `app/src/main/java/com/alpha/assistant/courses/CourseGeneration.kt` (`Input`, default `generateTool`, `SLIDE_INSTRUCTIONS`, failure mapping, `CourseExecutionChecks`)
- Create test fixture: `app/src/androidTest/java/com/alpha/assistant/courses/LessonAgentFixture.kt` with `internal class LessonAgentProvider(definitionFor: (CourseSlideKind) -> JSONObject, sources: List<String>) : ChatProvider` (Task 3 also uses it)
- Modify tests: `courses/CourseGenerationWorkflowTest.kt`, `courses/CourseCanvasWorkflowTest.kt`, `courses/ShellCourseCreationWorkflowTest.kt`, `courses/ShellCourseHostWorkflowTest.kt`
- Delete tests: `tools/generation/LessonPartsWorkflowTest.kt`, `LessonRouteWorkflowTest.kt`, `LessonSourceRouteWorkflowTest.kt`, `LessonSourcePlanWorkflowTest.kt`

**Interfaces:**
- Consumes: `courseLessonTarget(...)`, `generateLesson(key, prompt, session, artifactId, lesson)` from Task 1.
- `CourseGeneration.Input(prompt: String, sources: Map<String, CourseSourceReference>, slideGoal: String)`. The `generateTool` type stays `suspend (String, Input, CloudSession, String, ToolExecutionPolicy, CourseSlideKind) -> LessonToolPreview`, so `FixtureRuntime` tests stay.
- The default `generateTool` computes `GenerationLanguage.identifyText(input.slideGoal)` and calls `tools.generateLesson(key, input.prompt, session, id, courseLessonTarget(kind, input.sources.keys, policy, hint))`.

- [ ] **Step 1: Port the lesson requirements into failing course tests**

In `CourseGenerationWorkflowTest`, replace `LessonProvider` and `lessonPlan` with `LessonAgentProvider` from `LessonAgentFixture.kt`. It replies `write_app` on the first call and `finish` with `sources` after `agentAppAccepted`. Set `lessonStages = listOf(CloudGenerationStage.AGENT, CloudGenerationStage.AGENT)`. Keep the assertions of these tests: `buildsAllSevenKindsFromTheReachedHostTargetAndGradesTrustedExercises`, `localizedChoiceSlidesGenerateOnceAndKeepGradesAfterReopen`, `generatesAndReopensFeedbackForFourOrderedOptions`, `keepsCompletedWorkWhenTheLessonRouteRejectsOrCancelsTheNextSlide`, `savesOnlyTheSelectedTrustedSourceAndKeepsLinksOutOfThePlannerPrompt`. Add `aSlideThatNeverFinishesIsRetryableAndSavesNothing` (Review Focus 3): the provider always replies `agentStall`. Assert `CourseWorkflowError.UNSUPPORTED_LESSON`, no saved slide, and `course.generationFailure == INVALID_OUTPUT`. Port the needs-plan fakes in the three other course tests the same way.

- [ ] **Step 2: Run them and see them fail**

Run: `$SP/runfull.sh emulator-5570 "courses.CourseGenerationWorkflowTest courses.CourseCanvasWorkflowTest courses.ShellCourseCreationWorkflowTest courses.ShellCourseHostWorkflowTest" $SP/out-t2-red`
Expected: failures where the default route still sends PLAN requests, and in the stall case.

- [ ] **Step 3: Implement the course side**

- `SLIDE_INSTRUCTIONS` first line: "Build only the reached slide. Use reachedSlideGoal as the current task." Remove every mention of lesson parts.
- Map `widget_agent_incomplete` to `UNSUPPORTED_LESSON`. New `UNSUPPORTED_LESSON` text: "Alphy could not build this slide. Simplify the slide goal and retry."
- `CourseExecutionChecks`: set `rejection = null` at the start of each `validateDefinition`, so a fixed version does not keep an earlier reason.
- Delete the four `Lesson*` parts tests. Their lesson requirements are now in `LessonAgentWorkflowTest` and the course tests.

- [ ] **Step 4: Run them and see them pass**

Run the Step 2 command with `$SP/out-t2`. Expected: `OK` for all four classes. Also run `courses.CourseStorageWorkflowTest courses.CourseTutorUiWorkflowTest courses.CoursePlayerGenerationUiWorkflowTest`. Expected: `OK`.

- [ ] **Step 5: Commit Tasks 1 and 2 together**

Message: "Build lesson slides with the agent loop". Slopo review, architecture scan, then `git add -A` and `git commit`.

---

### Task 3: Rebuild a slide that does not load

**Files:**
- Modify: `app/src/main/java/com/alpha/assistant/courses/CoursePresentation.kt:31-41` (`generationRequest`), `:289-305` (extract the load check)
- Modify: `app/src/main/java/com/alpha/assistant/courses/CourseController.kt:31-58` (`generate`)
- Modify: `app/src/main/java/com/alpha/assistant/courses/CourseStore.kt:143-158` (`beginGeneration`, `commitGeneration`)
- Modify: `app/src/main/java/com/alpha/assistant/courses/CourseContract.kt:52-57` (`CourseGenerationOperation`)
- Modify: `app/src/main/res/values/course_strings.xml` (`course_slide_invalid`, new `course_slide_rebuild`)
- Modify: `app/src/main/java/com/alpha/assistant/courses/CourseUi.kt:1260-1290` (button text for INVALID)
- Test: `courses/CourseGenerationWorkflowTest.kt` (it has `FixtureRuntime`), `courses/CoursePlayerGenerationUiWorkflowTest.kt`

**Interfaces:**
- `internal fun checkSavedSlide(step: CourseStep, content: CourseSlideContent)`: the validation part of `checkedSlide`. It throws `CourseWorkflowException` when the slide does not load. `checkedSlide` calls it.
- `CoursePage.Unavailable.generationRequest`: non-null also for INVALID when the slide is the current slide.
- `CourseGenerationOperation` gets `val replaces: Boolean = false`.
- `CourseStore.beginGeneration(courseId: String, expectedRevision: Long, slideId: String, replace: Boolean = false)`. With `replace`, the slide must exist and have no answers, else `SLIDE_ALREADY_GENERATED`. Without it, behavior is unchanged.
- `CourseStore.commitGeneration`: when `operation.replaces`, update the `slides` row (definition, ui, state, sources, adaptation_note, outline_revision) in place. Otherwise insert as today.
- `CourseController.generate`: when a slide row exists, call `checkSavedSlide`. If it loads, return as today. If not, begin with `replace = true`.
- Strings: `course_slide_invalid` = "Этот слайд сохранён в старом формате. Его можно подготовить заново." `course_slide_rebuild` = "Подготовить заново".

- [ ] **Step 1: Write the failing tests**

- `CourseGenerationWorkflowTest.invalidCurrentSlideIsReplacedOnlyAfterANewSlidePasses`: save a slide, then raw-update its `definition` to a JSON object with an unknown top-level key `"composition":{}`. Assert the player page is `Unavailable(INVALID)` with a non-null `generationRequest`. Run `CourseController.generate` with a `FixtureRuntime` that fails once: assert the old definition is still saved. Run it again with a valid slide: assert the saved definition is the new one and the page is `CoursePage.Slide`.
- `CourseGenerationWorkflowTest.rebuildRejectsAnsweredOrChangedSlides` (Review Focus 4): `beginGeneration(..., replace = true)` on a slide with an answer gives `SLIDE_ALREADY_GENERATED`. A course revision change between begin and commit gives `STALE_GENERATION`. The old row stays in both cases.
- `CoursePlayerGenerationUiWorkflowTest.invalidSlideShowsRebuildAndReplacesIt`: open a course whose current slide is broken as above. Assert the text `course_slide_invalid` and the button `course_slide_rebuild`. Tap it with a `LessonAgentProvider`. Assert the new slide text shows.

- [ ] **Step 2: Run them and see them fail**

Run: `$SP/runfull.sh emulator-5570 "courses.CourseGenerationWorkflowTest courses.CoursePlayerGenerationUiWorkflowTest" $SP/out-t3-red`
Expected: the new tests fail (no generation request for INVALID).

- [ ] **Step 3: Implement the interfaces above.**

- [ ] **Step 4: Run them and see them pass**

Run the Step 2 command with `$SP/out-t3`, plus `courses.CourseStorageWorkflowTest courses.CourseEntryUiWorkflowTest`. Expected: `OK`.

- [ ] **Step 5: Commit** "Rebuild course slides that no longer load".

---

### Task 4: Fixtures from the current format

Capture saved data while the composition code still exists.

**Files:**
- Create: `app/src/androidTest/assets/legacy/mixed-app.json` (`{spec, state, ui}` of a mixed mini-app: a `composition` key with a non-empty `fillPositions`, title "Mixed workshop")
- Create: `app/src/androidTest/assets/legacy/mixed-lesson-slide.json` (`{definition, ui, state}` of an EXPLANATION scene slide with a `composition` key)

- [ ] **Step 1:** Make the two files from the current code. Use an existing mixed test (`tools/generation/MixedRuntimeWorkflowTest`) and a mixed scene lesson fixture, and write their saved JSON. Do not keep a dump helper.
- [ ] **Step 2:** Check that `ToolSpec.parsePersisted` accepts both at this revision. Run a temporary assertion in an existing test; do not commit it.
- [ ] **Step 3:** No separate commit. The files go with Task 5.

---

### Task 5: Delete the staged builder and the composition runtime

**Files:**
- Modify: `app/src/main/java/com/alpha/assistant/tools/ToolEngine.kt` (delete `buildCandidate`, the composition event path in `executeEvent`, `MixedFillIncomplete`, `incompleteMixed`, `repairResponse` if unused, `partClock` and `samplePartClock`)
- Modify: `app/src/main/java/com/alpha/assistant/tools/ToolSpec.kt` (delete `composition`, its validation category, and `"composition"` from the persisted key list at line 239)
- Modify: `ToolUi.kt`, `ArtifactCoordinator.kt`, `ToolClockProjection.kt`, `ToolGenerationSmoke.kt`, `ToolGenerationDiagnostic.kt`, `CoursePresentation.kt:297`
- Delete: `tools/generation/NeedsPlan.kt`, `ToolParts.kt`, `PartsGeneration.kt`, `JevSelector.kt`, `ToolComposition.kt`, `ClockParts.kt`, `RecordParts.kt`, `PartClockSample.kt`, `NumberExpression.kt`, `NativeBehaviorCatalog.kt`, `SceneRequirement.kt`, and `GenerationRepair.kt` if unreferenced
- Delete tests: every test that only tests deleted code (the `Mixed*`, `*Projection*`, `*Generation*` parts tests under `tools/generation/`, `ToolCompositionWorkflowTest`, `PartsGenerationWorkflowTest`, and `GenerationFixturePlan.kt` members that only those tests use)
- Test: `tools/LibraryManagementWorkflowTest.kt`, `courses/CoursePlayerGenerationUiWorkflowTest.kt`

**Interfaces:**
- `ToolSpec.availablePersisted(json)` returns null for any spec with a `composition` key.
- The compiler decides the final deletion set. Keep `NativePresentation`, `GenerationBehavior`, `SceneCheckEvidence`, and `SurfaceDrawing` if the agent loop or saved apps still use them.

- [ ] **Step 1: Write the failing tests**

- `LibraryManagementWorkflowTest.savedMixedAppFromAnOlderVersionStaysButDoesNotOpen`: the same flow as `savedStandaloneAppFromAnOlderVersionStaysButDoesNotOpen`, with `legacy/mixed-app.json` and the title "Mixed workshop". Extract the shared body into `private fun retiredAppStaysButDoesNotOpen(asset: String, title: String)`.
- `CoursePlayerGenerationUiWorkflowTest.oldMixedSceneSlideOffersRebuild`: save `legacy/mixed-lesson-slide.json` as the current slide. Assert `course_slide_invalid` and `course_slide_rebuild` show.

- [ ] **Step 2: Run them and see them fail**

Run: `$SP/runfull.sh emulator-5570 "tools.LibraryManagementWorkflowTest courses.CoursePlayerGenerationUiWorkflowTest" $SP/out-t5-red`
Expected: both new tests fail, because the mixed app opens and the slide loads.

- [ ] **Step 3: Delete the code**

Delete `buildCandidate` and the `composition` key first. Then build with `./gradlew compileDebugKotlin compileDebugAndroidTestKotlin` and delete what the errors and the unused declarations show. Repeat until the build passes and no deleted-area declaration has zero references.

- [ ] **Step 4: Run the tests**

Run the Step 2 command with `$SP/out-t5`. Expected: `OK`. Also run `chat.AutomaticToolWorkflowTest tools.AgentGenerationWorkflowTest tools.NativePresentationWorkflowTest courses.CourseGenerationWorkflowTest courses.CourseCanvasWorkflowTest`. Expected: `OK`.

- [ ] **Step 5: Commit** "Remove the staged builder and the composition runtime", with the two legacy assets.

---

### Task 6: Full verification and push

- [ ] **Step 1:** Run the full instrumentation suite, split across emulator-5570 and emulator-5572. Expected: no new failures against the dc58bee baseline. Record each failure with its class, test, and cause.
- [ ] **Step 2:** Run `courses.LiveCourseWorkflowTest` with the real DeepSeek key on one emulator. Cover one slide of each kind group (static, CHOICE, FILL_IN, ORDER, scene), one Russian course, and one Russian learner in a Spanish course. Record the revision, command, inputs, expected and observed results, and model calls and seconds per slide. Compare them with the Task 0 baseline.
- [ ] **Step 3:** Run `git fetch origin main`, check that `origin/main` is still dc58bee, then `git push origin HEAD:main`.
