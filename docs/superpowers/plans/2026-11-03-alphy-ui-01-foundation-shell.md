# Alphy UI plan 1: foundation and shell — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the page-and-sheet shell of Alphy with a Russian Material 3 Expressive shell: a route back stack, a left drawer, a desktop home screen, consent at key entry, search, and a plain access page.

**Architecture:** `AlphaShell` makes one back stack, `[Desktop, Chat?, destination?]`, from the selected chat and one saved destination. Navigation 3 `NavDisplay` shows it. A `ModalNavigationDrawer`, one top bar, and one composer frame the routes. A focused mini-app keeps its own full screen. `SecureKeyStore` writes the consent record in the same commit as the key, and `cloudKey()` guards every DeepSeek request.

**Tech Stack:** Kotlin 2.4.0, Compose BOM 2026.09.00 (Compose 1.12.1), Material 3 1.5.0-beta02, Navigation 3 1.2.0, activity-compose 1.13.0, AGP 9.2.1, JDK 17. Device tests use UiAutomation on `emulator-5556`. Host tests use Python 3.

**Spec:** [docs/superpowers/specs/2026-11-03-alphy-ui-design.md](../specs/2026-11-03-alphy-ui-design.md). The [roadmap](2026-11-03-alphy-ui-roadmap.md) puts this plan among the 10 plans.

## Global Constraints

Each quoted line is verbatim from the spec, with its section. Every task must obey this section.

- 5.1 "(User) The home screen is the interactive desktop."
- 5.1 "(User) A ChatGPT-like shell frames it: a menu button, a new-chat button, and one composer at the bottom."
- 5.1 "(User) The top of the desktop is the «Сейчас» strip, with one or two suggestions from Alphy."
- 5.1 "(Claude) A sent message opens the chat over the desktop. «Новый чат» and Back return to the desktop."
- 5.1 "(Claude, new) The «Сейчас» strip uses local data only and sends nothing to DeepSeek."
- 5.1 "(Claude, new) The greeting can use the user's name, for example «Доброе утро, Евгений». The name comes from «Память» or from «Настройки»."
- 5.9 "(User) The user gives consent one time, at DeepSeek key entry. No message asks again."
- 5.9 "(Claude) The consent text names messages, search queries, and chip content."
- 5.9 "(Claude, new) «Настройки» show the consent text again. «Удалить ключ» stops all cloud functions."
- 5.9 "(Claude, new) The README and `docs/cloud-integration.md` change to describe this consent."
- 5.11 "(User) The menu opens from the left, as in ChatGPT. It has «Поиск», «Новый чат», and the three extensions «Мини-приложения», «Обучение», and «Автоматизации». The recent chats come below them. The user profile is at the bottom."
- 5.11 "(Claude, new) The menu also has «Память», «Доступ и функции», and «Настройки»."
- 5.11 "(Claude, new) «Доступ и функции» shows Android access in plain words, with no capability IDs or API levels."
- 5.11 "(Claude, new) «Настройки»: the key with the consent text, the assistant settings link, voice, and the user's name. There is no theme switch, because light and dark follow the system."
- 5.11 "(User) The UI uses the polite form, as the chosen samples show («Спросите что-нибудь», «Отмечайте стаканы прямо здесь»)."
- 5.11 "(Claude, new) All UI text is Russian and lives in Android string resources."
- 5.11 "(Claude, new) Alphy sets its app locale to Russian, so that Material component labels are Russian too."
- 5.12 "(User) Style C: Material 3 Expressive, colors from the wallpaper, tinted tiles, rounder shapes, and bolder headings."
- 5.12 "(Claude) Alphy uses material3 1.5.0-beta02 for `MaterialExpressiveTheme`, `MotionScheme`, and the Expressive components."
- 5.12 "`LoadingIndicator` and `MaterialShapes` are still experimental in 1.5.0-beta02. Use them only with the opt-in annotation."
- 5.12 "(Claude) On API 29 and 30, Alphy uses a fixed palette from the mock purple `#7652df`."
- 5.12 "(Builder) One icon set for the whole app. Text glyphs such as «↑», «■», and «⋯» go. The 21 glyph names in saved mini-apps are part of saved data."
- 5.12 "One primary action for each state." "Lists are rows with dividers." "A status shows with color, icon, and text together."
- 5.12 "One title for each screen. The renderer hides a card heading that repeats the app title."
- 3 "Dynamic color needs API 31 or later." "Edge-to-edge and predictive back are on for targetSdk 36 and later."
- 3 "AOSP marks the assistant role as not requestable (`requestable="false"`). An app cannot show a role dialog. It can only open the system settings."
- 2.2 "(User) A confirmation before each cloud message. The user chose one consent at key entry."
- 2.3 "(Claude, new) Technical data on user screens: IDs, URIs, schema keys, raw coordinates, ISO times, or English diagnostics."
- 2.3 "(Claude, new) A dead end: a "could not build" screen with no next step."
- 9 "(Claude, new) New tests find nodes through string resources or test tags, not through literal text."
- 9 "Behavior changes get black-box tests of the user flows."
- 9 "Each test run records the revision, the command, the inputs, the expected result, and the observed result."
- 9 "`FLAG_SECURE` blocks screenshots of «Настройки» and the half sheet. (Builder) A method for visual checks of these screens."
- 9 "Touch targets of 48 dp or more, TalkBack labels, reduced motion, and text sizes in sp."
- 7 "| Navigation | Navigation 3 1.2.0 | (Claude, new) |" and "| compileSdk | Stays 37. No move to 37.1, so no `material3-a2ui` | (Claude, new) |"
- 7 "| Key storage | The `EncryptedApiKeyStore` pattern: AES/GCM, a key in AndroidKeyStore, private SharedPreferences | (User, brief) |"
- `AGENTS.md`: "Do not add compatibility layers or migrations solely to support earlier development versions. Do not silently delete existing user data or credentials."

## Spec lines for later plans

The roadmap requires a recorded reason for each line of the plan's spec sections that has no test. These lines go to later plans:

- 5.11 «Обучение» and «Память» in the menu: plans 8 and 7 add each row with its page, because a row without a page is a dead end (spec 2.3).
- 5.1 "The name comes from «Память»": plan 7 adds this name source to the greeting and to the drawer profile. Plan 1 uses the name from «Настройки».
- 5.11 "The library lists mini-apps with search. Each row shows a live thumbnail, the version, and where the item is placed.": plan 3 builds these rows. Plan 1 only opens the current library from the drawer.
- 5.11 "All UI text is Russian and lives in Android string resources": plan 1 applies this line to the screens that it rewrites (roadmap, done criteria). These screens keep English or informal text until their owner plan rewrites them:
  - the message cards and the tool rows in the chat (plan 2)
  - `tools/ToolUi.kt` and the mini-app pages (plan 3)
  - `automations/AutomationUi.kt` (plan 6)
  - `CompactAssistant` in `surfaces/AssistantActivity.kt` (plan 9)
- 9 "`FLAG_SECURE` blocks screenshots of «Настройки» and the half sheet.": plan 1 makes the visual check method and applies it to «Настройки» (Task 10). Plan 9 applies it to the half sheet. A device test of `AssistantActivity` needs a debug-only model factory for `AssistantChat`, because `AssistantChat` reads the production key store.

## Review Focus

These five inputs are the most probable to break the shell for a person. Each line names the test that pins it.

1. A key that the current version saved has no consent record. The first cloud request from the chat or from a mini-app must stop with «Открыть настройки». «Согласиться» must enable cloud without a new key entry, and later requests must not ask again. Tests: Task 6, `storedKeyWithoutConsentAsksOnce` and `settingsEntryShowsConsentForStoredKey`.
2. A continuous voice session sends the first message of a new chat from the Desktop. The session must stay active while the Chat route replaces the Desktop. Test: Task 4, `continuousVoiceSurvivesDesktopSend`.
3. The device rotates while «Настройки» or the drawer is open. «Настройки» must come back with `FLAG_SECURE`, Back must clear the flag, and the drawer must keep «Настройки» on the screen in landscape. Tests: Task 4, `backLeavesSettingsThenChatThenApp`, and Task 5, `drawerKeepsSettingsReachableInLandscape`.
4. The font scale is 200%, with a long user name and a long chat title. The top bar, the drawer, the greeting, and the composer must stay usable. Test: Task 7, `largeTextKeepsShellUsable`.
5. The device locale is not Russian (the test emulator uses en-US). Material labels and dates must be Russian. Tests: Task 2, `activitiesUseRussianOnAnotherDeviceLocale`, and Task 8, `searchShowsRussianDatesAndPoliteTexts`.

## Unresolved decisions

1. Another session has uncommitted work in this checkout (Appendix B). Plan 1 builds on that work, and the Slopo review compares the full working tree with its base. The user must choose one action before Task 0 continues:
   - The other session commits its work on `main`.
   - The user permits this executor to commit that work as one commit, after a Slopo review (Task 0 Step 2).

   A worktree from `62179af` is not a third option, because it lacks that work and the git-ignored Silero models.

   Answer (user, 2026-11-03): the executor commits that work. Commit `cd29ed9` holds it on `main`, and the branch starts at `cd29ed9`.
2. `test_harness.py:219-230` compares the SHA-256 values in `docs/parity/compose-coverage.json` with the current files. Tasks 1 and 2 change two pinned files, `app/build.gradle.kts` and `capabilities/MedicineCapabilities.kt`. `compose-coverage.md:12` records an earlier update of these values. But `compose-installed-inventory.md:238` says that `compose-coverage.json` keeps its existing content. The user must choose one action before the Task 1 commit:
   - Option A: Tasks 1 and 2 write the new value of the changed file into `compose-coverage.json`. Task 1 also changes the two dependency sentences at `compose-coverage.md:95-96`.
   - Option B: the record stays unchanged. From Task 1, the host tests expect exactly one failure, `test_coverage_preserves_pinned_reference_and_current_sources`.

   Answer (user, 2026-11-03): Option A.

## Implementation choices

Each choice stays inside the spec. Other valid choices stay permitted.

- One composer belongs to the shell and shows under the Desktop and Chat routes (spec 5.1 "one composer"). The keyboard stays open when a desktop message opens the chat.
- The back stack comes from `snapshot.selected` and one saved `ShellDestination`. Launch restores the selected chat. Plan 1 adds no deep links.
- `SecureKeyStore` keeps the consent record (spec 8 "the consent record") in its private preferences, in the same commit as the key.
- The consent text names every item of spec 5.9 now, with the automation and memory items. So plans 6 and 7 do not change the consent, and no message asks again.
- `AlphyActivity` gives all six activities Russian resources. `AppCompatDelegate.setApplicationLocales` needs AppCompat, and `LocaleManager` needs API 33.
- The `Glyph` path icons stay the one icon set. Plan 1 adds four glyphs and no icon dependency.
- The system font stays. The theme makes the display, headline, and title styles SemiBold.
- The drawer opens with a swipe only on the Desktop and Chat routes. Mini-app pages can have horizontal gestures.
- The drawer sheet is at most the window width minus 56 dp. On a 320 dp phone, a strip of the scrim stays visible. The scrim closes the drawer, and its label tells the tests that the drawer is open.
- A voice session stops when the shell shows neither a composer nor `VoiceControls` (Task 4). A session started on one of these surfaces survives a route change to another one.
- The assistant half sheet opens «Настройки» through a Settings entry of `MainActivity` (Task 6).
- The four new deprecation warnings stay (Task 1). The tasks that rewrite those screens replace the APIs.
- The device runner is a new `suite` command in `tools/compose_acceptance/run.py`. A `host` command records the host tests. Both use the runner's `adb_command`, `child_environment`, and `identity`.

## Execution rules

- Work in `/Users/egavrin/Documents/alpha` on the branch `alphy-ui/01-foundation-shell`. A worktree lacks the git-ignored Silero models in `app/src/main/assets/voice/silero/`.
- Run Gradle tasks with this environment prefix:
  `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`
- Compile without a device with `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --console=plain`.
- Run every device test through the runner from Task 0:
  `python3 tools/compose_acceptance/run.py suite --label LABEL [--expect passed|failed|build-failed] [--baseline RUN_JSON] [TARGET ...]`
- A TARGET is `com.alpha.assistant.<package>.<Class>` or `...<Class>#<method>`. With no TARGET, the runner runs the full suite.
- Run the host tests through the runner, except the red run of Task 0 Step 7:
  `python3 tools/compose_acceptance/run.py host --label LABEL [--expect passed|failed]`
- Use labels of the form `p1-t<task>-<name>`. The runner refuses a label that it used before. When a step runs again, add `-r2`, `-r3`, and so on to the label of that step.
- Each task ends with a full suite run against the Task 0 baseline:
  `python3 tools/compose_acceptance/run.py suite --label p1-t<task>-full --baseline evidence/alphy-ui/p1-t0-baseline/run.json`
  Expected: exit 0, so no failure is outside the baseline.
- Never run `connectedDebugAndroidTest`. It uninstalls the app with its data and its key.
- Send ADB commands only to `emulator-5556`. Do not touch `emulator-5554`.
- Put new test classes in `app/src/androidTest/java/com/alpha/assistant/ui/` with the package `com.alpha.assistant.ui`.
- New tests find nodes through `R.string` resources. Material labels come from the activity resources.
- Before each commit, follow `~/.codex/skills/slopo-review/SKILL.md`. Use the commit at the start of the task as the base. Resolve blocking findings first.
- Slopo can report the helper bodies in `ShellRobot.kt` as copies of private helpers in older test classes. Disposition: the copies existed before plan 1, and plan 1 does not migrate the older classes.
- Commit only after the user approves this plan for execution. Commit messages have no attribution lines.
- A line reference such as `ui/AlphaShell.kt:281` points to the base state of Appendix B. After an earlier task moves that code, find it by its content.
- In `tools/ToolUi.kt`, Task 0a (commit `ed96b2a`, a fix that the user requested before Task 0) changed the line numbers after base line 138. Base lines 147-224 are now 7 lines earlier, for example base line 153 is now line 146.
- When a task moves a literal into a resource with no text change, choose the resource name. Appendix C lists every new or changed text and every name that a test or another task uses.

## File map

| File | Responsibility | Tasks |
| --- | --- | --- |
| `tools/compose_acceptance/run.py`, `test_harness.py` | `suite` and `host` commands and their host tests | 0 |
| `app/build.gradle.kts` | Dependency versions | 1 |
| `docs/parity/compose-coverage.json`, `compose-coverage.md` | Pinned source hashes (Unresolved decision 2) | 1, 2 |
| `ui/AlphyActivity.kt` (new) | Russian resources for every activity | 2 |
| `res/values/strings.xml` (new) | All plan 1 UI text | 2 to 9 |
| `ui/AlphaTheme.kt` | Expressive theme and the fixed palette | 3 |
| `ui/ShellRoute.kt` (new) | Route types | 4, 5 |
| `ui/AlphaShell.kt` | Shell frame, `NavDisplay`, drawer wiring, entries, `ChatPane` | 4 to 9 |
| `ui/ShellChrome.kt` (new) | `ShellTopBar`, `ShellDrawerSheet` | 5 |
| `ui/AlphaIcons.kt` | `Glyph`, `AlphaIcon` color, `StatusLine` | 5 to 9 |
| `data/SecureKeyStore.kt`, `cloud/CloudKey.kt` (new) | Consent record and the guarded key read | 6 |
| `tools/ToolController.kt`, `tools/ToolUi.kt` | Key error code and «Открыть настройки» on the mini-app pages | 6 |
| `ui/SettingsPane.kt` (new) | «Настройки» | 6 |
| `ui/Composer.kt`, `ui/DesktopPane.kt` (new) | Shared composer, greeting, «Сейчас» | 7 |
| `ui/SearchPane.kt` (new) | «Поиск» | 8 |
| `capabilities/AndroidAccessUi.kt` | «Доступ и функции», access rows, `AssistantSettingsRow` | 6, 9 |
| `MainActivity.kt`, `surfaces/AssistantActivity.kt` | Entry intents and the assistant half sheet | 2 to 7, 9 |
| `androidTest/.../ui/ShellRobot.kt` (new) | Shared test fixture and helpers | 2, 4 to 7, 9 |
| `androidTest/.../ui/ShellVoiceWorkflowTest.kt` (new) | Voice session across routes | 4 |

Paths under `ui/`, `data/`, `cloud/`, `capabilities/`, `surfaces/`, and `tools/` (in Kotlin) are relative to `app/src/main/java/com/alpha/assistant/`. `T/` means `app/src/androidTest/java/com/alpha/assistant/`.

---

### Task 0: Gate, device runner, and baseline

**Files:**
- Modify: `tools/compose_acceptance/run.py` (new `suite` and `host` commands, an `apks` keyword for `identity`)
- Test: `tools/compose_acceptance/test_harness.py`
- Modify: `README.md:44-50` (Tests section)
- Modify: `T/ui/ReviewedContextUiWorkflowTest.kt:75`, `T/weather/WeatherWorkflowTest.kt:133` (evidence text)

**Interfaces:**
- Produces: the `suite` and `host` commands in Execution rules.
- Produces: `suite_outcome(stdout: str, known: set, known_skipped: set = frozenset()) -> dict` with the keys `observed` (`"passed"` or `"failed"`), `tests` (int), `failures`, and `skipped`. The two lists are sorted and hold `Class#method` values.
- Produces: `suite_instrument_command(adb: str, targets: list, revision: str) -> list`.
- Produces: `suite(args) -> int` and `host(args) -> int`. Each returns the exit code of its command.
- Produces: `evidence/alphy-ui/<label>/run.json` of a device run with `revision`, `dirty`, `sourceSha256`, `apks`, `installedApkSha256`, `utc`, `serial`, `commands`, `targets`, `expected`, `baseline`, `observed`, `tests`, `failures`, `skipped`, and `elapsedSeconds`. `baseline` is the `--baseline` path, or `null`. A build-failed run writes `null` for `apks` and `installedApkSha256`. The directory also holds `build.log`, `install.log`, and `stdout.log`.
- Produces: `evidence/alphy-ui/<label>/run.json` of a host run with `revision`, `dirty`, `sourceSha256`, `utc`, `commands`, `expected`, `observed`, and `elapsedSeconds`, and its `stdout.log`.

- [ ] **Step 1: Get the answers to the Unresolved decisions**

Ask the user both Unresolved decisions above. Do not continue until the answers arrive.

- [ ] **Step 2: Commit the work of the other session (option 2 only)**

If the user chose option 1 of Unresolved decision 1, skip this step. Otherwise, do these operations on `main`:
1. Run the Slopo review with the base `62179af`. Report blocking findings to the user before the commit.
2. Run `git add --all -- . ':(exclude)docs/superpowers'`.
3. Run `git commit -m "Record the Compose work of the other session"`.

- [ ] **Step 3: Check the gate**

Run:
```bash
git -C /Users/egavrin/Documents/alpha status --short
git -C /Users/egavrin/Documents/alpha log --oneline -3
git -C /Users/egavrin/Documents/alpha diff --name-status 62179af HEAD
ps -axo pid,command | grep -E 'GradleWrapperMain|am instrument' | grep -v grep
```
Expected:
- No status line other than `?? docs/superpowers/`.
- Each entry of the Appendix B status list, except `docs/superpowers/`, matches a path in the diff. A directory entry matches the files under it.
- No process lines.

- [ ] **Step 4: Compare the sources with this plan**

Run the hash command in Appendix B. If the hash differs, report the hash and the changed paths to the user before Task 1. Do not edit the plan. The plan owner corrects the line references of Tasks 1 to 10.

- [ ] **Step 5: Create the branch**

Run: `git switch -c alphy-ui/01-foundation-shell`
Expected: `Switched to a new branch 'alphy-ui/01-foundation-shell'`

- [ ] **Step 6: Write the failing host tests**

Add to `HarnessBoundaries` in `test_harness.py`:

```python
    def test_suite_outcome_reads_raw_instrumentation(self):
        passed = "INSTRUMENTATION_RESULT: stream=\n\nTime: 1.2\n\nOK (2 tests)\n\n\nINSTRUMENTATION_CODE: -1\n"
        self.assertEqual(harness.suite_outcome(passed, set()), {"observed": "passed", "tests": 2, "failures": [], "skipped": []})
        failed = ("INSTRUMENTATION_STATUS: class=a.B\nINSTRUMENTATION_STATUS: test=x\nINSTRUMENTATION_STATUS_CODE: 1\n"
                  "INSTRUMENTATION_STATUS: class=a.B\nINSTRUMENTATION_STATUS: stack=java.lang.AssertionError: no\n\tat a.B.x(B.kt:1)\n"
                  "INSTRUMENTATION_STATUS: test=x\nINSTRUMENTATION_STATUS_CODE: -2\n"
                  "INSTRUMENTATION_RESULT: stream=\n\nFAILURES!!!\nTests run: 2,  Failures: 1\n\n\nINSTRUMENTATION_CODE: -1\n")
        self.assertEqual(harness.suite_outcome(failed, set()), {"observed": "failed", "tests": 2, "failures": ["a.B#x"], "skipped": []})
        self.assertEqual(harness.suite_outcome(failed, {"a.B#x"})["observed"], "passed")
        assumed = ("INSTRUMENTATION_STATUS: class=a.B\nINSTRUMENTATION_STATUS: test=y\nINSTRUMENTATION_STATUS_CODE: 1\n"
                   "INSTRUMENTATION_STATUS: class=a.B\nINSTRUMENTATION_STATUS: test=y\nINSTRUMENTATION_STATUS_CODE: -4\n"
                   "INSTRUMENTATION_RESULT: stream=\n\nOK (1 test)\n\n\nINSTRUMENTATION_CODE: -1\n")
        self.assertEqual(harness.suite_outcome(assumed, set()), {"observed": "failed", "tests": 1, "failures": [], "skipped": ["a.B#y"]})
        self.assertEqual(harness.suite_outcome(assumed, set(), {"a.B#y"})["observed"], "passed")
        crashed = "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n"
        self.assertEqual(harness.suite_outcome(crashed, set())["observed"], "failed")

    def test_suite_command_uses_only_the_test_emulator(self):
        command = harness.suite_instrument_command("adb", ["a.B#x", "c.D"], "abc123")
        self.assertEqual(command[:3], ["adb", "-s", "emulator-5556"])
        self.assertEqual(command[command.index("class") + 1], "a.B#x,c.D")
        self.assertEqual(command[-1], "com.alpha.assistant.test/androidx.test.runner.AndroidJUnitRunner")
        self.assertNotIn("class", harness.suite_instrument_command("adb", [], "abc123"))

    def test_suite_build_failure_records_no_apk_hashes(self):
        with tempfile.TemporaryDirectory() as temporary:
            args = harness.argparse.Namespace(label="red", expect="build-failed", baseline=None, targets=[],
                                              adb="adb", output=Path(temporary))
            def execute(command, **options):
                return Mock(returncode=1 if command[0] == "./gradlew" else 0, stdout="")
            with patch.object(harness.subprocess, "run", side_effect=execute), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, harness.suite(args))
            record = json.loads((Path(temporary) / "red/run.json").read_text())
            self.assertEqual("build-failed", record["observed"])
            self.assertIsNone(record["apks"])
            self.assertIsNone(record["installedApkSha256"])
```

- [ ] **Step 7: Run the host tests to see them fail**

Run:
```bash
mkdir -p evidence/alphy-ui
{ git rev-parse HEAD; echo "python3 tools/compose_acceptance/test_harness.py"; python3 tools/compose_acceptance/test_harness.py; echo "exit $?"; } > evidence/alphy-ui/p1-t0-host-red.log 2>&1
```
Expected: the log names `AttributeError: module 'harness' has no attribute 'suite_outcome'` and ends with `exit 1`.

- [ ] **Step 8: Implement the `suite` and `host` commands in `run.py`**

- `suite_outcome`: a test fails when its status block ends with code `-2` or `-1`. A test is skipped when its block ends with code `-3` or `-4`. The run passes only when these conditions are all true:
  - The output has `INSTRUMENTATION_CODE: -1`.
  - The output has an `OK (` line or a `FAILURES!!!` line.
  - Each failure is in `known`, and each skipped test is in `known_skipped`.
- `tests` comes from `OK (N test)`, `OK (N tests)`, or `Tests run: N`.
- `suite_instrument_command` returns `adb_command(adb, "shell", "am", "instrument", "-w", "-r", ...)`. It adds `-e class <targets joined by commas>` only when targets exist. Then it adds `-e testedRevision <revision>` and the runner component.
- `identity(apks=True)`: with `apks=False`, it does not read the APK files. `run()` keeps the default.
- `suite(args)` does these operations in sequence:
  1. Refuse an evidence directory `<output>/<label>` that exists.
  2. Check `adb_command(args.adb, "get-state")`, as `run()` does.
  3. Record `identity(apks=False)`. `dirty` is true when `git status --porcelain` has output. The recorded `revision` is the `identity()` revision, with `+dirty` when `dirty` is true. `identity()` itself keeps the plain `git rev-parse HEAD` value, because `run()` sends it as `composeRevision`, and a live run requires 40 hex digits.
  4. Build with `["./gradlew", ":app:assembleDebug", ":app:assembleDebugAndroidTest", "--console=plain"]` in `ROOT` into `build.log`. Add `JAVA_HOME` and `ANDROID_HOME` from Execution rules to `child_environment()`. A nonzero exit gives `observed = "build-failed"`, with `null` for `apks` and `installedApkSha256`. It skips steps 5 to 7.
  5. Hash the two built APKs with `digest`. Install both with `install -r` into `install.log`. Read the installed APK hash with `pm path com.alpha.assistant` and `sha256sum`. A hash that differs from the built app APK gives `observed = "failed"`.
  6. Reset the device with `settings put system font_scale 1.0` and `cmd uimode night no`, so a crashed earlier test leaves no state.
  7. Run `suite_instrument_command` into `stdout.log`.
  8. Write `run.json` with the fields in Interfaces. `known` and `known_skipped` are the `failures` and `skipped` lists of the `--baseline` file, or empty.
  9. Return 0 only when `observed` equals `--expect` (default `passed`). Print the observed result and the evidence path.
- `host(args)` records `revision`, `dirty`, and `sourceSha256` as `suite` step 3 does. Then it runs `["python3", "tools/compose_acceptance/test_harness.py"]` in `ROOT` with `child_environment()` into `stdout.log`. Exit 0 gives `observed = "passed"`, and other exits give `"failed"`. It writes `run.json` with the host fields in Interfaces. It returns 0 only when `observed` equals `--expect` (default `passed`).
- Register both commands in `main()`, and exit with their return value:
  - `suite`: `--label` (required), `--expect`, `--baseline`, `--adb` (the default of `run`), `--output` (default `ROOT / "evidence/alphy-ui"`), and the positional targets.
  - `host`: `--label` (required), `--expect`, and `--output` (the same default).

- [ ] **Step 9: Run the host tests to see them pass**

Run: `python3 tools/compose_acceptance/run.py host --label p1-t0-host`
Expected: exit 0, and `observed` is `passed` in `run.json`.

- [ ] **Step 10: Change the docs and the evidence texts**

- `README.md:44-50`: replace `./gradlew :app:connectedDebugAndroidTest` with the `suite` command and a note. The note states that the command builds both APKs and installs them with `install -r` on `emulator-5556` only, so the app keeps its data. It also names the `JAVA_HOME` and `ANDROID_HOME` paths that the build uses (Execution rules) and the `--adb` option for another adb path. State that `connectedDebugAndroidTest` uninstalls the app with its data and key, so the project does not use it.
- `ReviewedContextUiWorkflowTest.kt:75` and `WeatherWorkflowTest.kt:133`: replace `Command: connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=` with `Command: python3 tools/compose_acceptance/run.py suite --label LABEL `. Keep the class name that follows.

- [ ] **Step 11: Record the baseline**

Run: `python3 tools/compose_acceptance/run.py suite --label p1-t0-baseline --expect failed`
Expected: exit 0. This run builds and runs the test files of Step 10. If one of these checks fails, report it to the user before Task 1:
- `stdout.log` has a `FAILURES!!!` line and `INSTRUMENTATION_CODE: -1`, so the instrumentation finished.
- `installedApkSha256` in `run.json` equals the `app-debug.apk` hash in `apks`.
- `failures` in `run.json` lists exactly the two methods in Appendix B.

Also report the `skipped` list. If the user asks for a new baseline, record it with the label `p1-t0-baseline-r2`. Then, before the Step 12 commit, replace `p1-t0-baseline/run.json` with `p1-t0-baseline-r2/run.json` in Execution rules, Task 4 Step 6, and Task 5 Step 7.

- [ ] **Step 12: Review and commit**

Run the Slopo review. Then run:
```bash
git add tools/compose_acceptance/run.py tools/compose_acceptance/test_harness.py README.md app/src/androidTest/java/com/alpha/assistant/ui/ReviewedContextUiWorkflowTest.kt app/src/androidTest/java/com/alpha/assistant/weather/WeatherWorkflowTest.kt docs/superpowers
git commit -m "Add the Alphy UI design, plans, and device suite runner"
```

---

### Task 1: Dependencies

**Files:**
- Modify: `app/build.gradle.kts:36-38`, and add two lines after line 38
- Modify (Option A of Unresolved decision 2): `docs/parity/compose-coverage.json` (`source_sha256`), `docs/parity/compose-coverage.md:95-96`

**Interfaces:**
- Produces: `NavDisplay`, `entryProvider`, `MaterialExpressiveTheme`, and `MotionScheme` on the compile classpath.

- [ ] **Step 1: Change the dependency lines**

```kotlin
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3:1.5.0-beta02")
    implementation("androidx.navigation3:navigation3-runtime:1.2.0")
    implementation("androidx.navigation3:navigation3-ui:1.2.0")
```

The first build needs network access, because the Gradle cache has none of these versions.

- [ ] **Step 2: Run the build checks**

Run: `./gradlew :app:checkDebugAarMetadata :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --console=plain` (with the environment prefix)
Expected: `BUILD SUCCESSFUL`. The base already has one deprecation warning, at `tools/NativeToolUi.kt:454` (`ScrollableTabRow`). It stays. The new deprecation warnings are only at these four places:
- `tools/NativeToolUi.kt:416` (`RangeSlider`) and `:444` (`Slider`)
- `ui/AlphaShell.kt:137` and `ui/ReviewedContextUi.kt:84` (`rememberModalBottomSheetState`)

This run tests the spec 7 Assumption that material3 1.5.0-beta02 builds with compileSdk 37 and AGP 9.2.1.

- [ ] **Step 3: Record the dependency report**

Run: `./gradlew :app:dependencies --configuration debugRuntimeClasspath --console=plain > evidence/alphy-ui/p1-t1-dependencies.txt`
Expected: the report resolves these versions.

| Module | Version |
| --- | --- |
| `androidx.compose.material3:material3` | 1.5.0-beta02 |
| `androidx.compose.ui:ui` | 1.12.1 |
| `androidx.navigation3:navigation3-ui` and `navigation3-runtime` | 1.2.0 |
| `androidx.activity:activity-compose` | 1.13.0 |
| `androidx.lifecycle:lifecycle-runtime` | 2.10.0 |
| `androidx.navigationevent:navigationevent` | 1.1.1 |
| `androidx.savedstate:savedstate` | 1.4.0 |
| `androidx.core:core` | 1.18.0 |
| `org.jetbrains.kotlinx:kotlinx-serialization-core` | 1.9.0 |

Do not edit `docs/parity/compose-installed-inventory.md`. It is a dated record of the earlier configuration, and it tells later packages to record their own evidence.

- [ ] **Step 4: Apply Unresolved decision 2 and run the host tests**

- Option A: write the new `harness.digest` value of `app/build.gradle.kts` into `source_sha256` in `compose-coverage.json`. At `compose-coverage.md:95-96`, replace the old versions with the five dependency lines of Step 1. State that Material 3 has its own version. Keep the closed-catalog sentence at `:97`.
- Run: `python3 tools/compose_acceptance/run.py host --label p1-t1-host`
- Expected with Option A: exit 0.
- With Option B, add `--expect failed`. Expected: exit 0, and `stdout.log` names only `test_coverage_preserves_pinned_reference_and_current_sources` for `app/build.gradle.kts`.

- [ ] **Step 5: Run the full suite**

Use the full suite command in Execution rules with the label `p1-t1-full`.
Expected: exit 0. This run covers the changed default sheet state at `tools/NativeToolUi.kt:204`, `tools/ToolUi.kt:137`, and `surfaces/AssistantActivity.kt:153`.

- [ ] **Step 6: Review and commit**

```bash
git add app/build.gradle.kts docs/parity/compose-coverage.json docs/parity/compose-coverage.md
git commit -m "Upgrade Compose and Material 3, and add Navigation 3"
```
With Option B, the two `docs/parity` paths have no changes, so `git add` records nothing for them.

---

### Task 2: Russian locale, string resources, and test helpers

**Files:**
- Create: `ui/AlphyActivity.kt`
- Create: `app/src/main/res/values/strings.xml`
- Modify: the superclass of `MainActivity.kt:17`, `capabilities/MedicineCapabilities.kt:344`, `surfaces/AssistantActivity.kt:34`, `surfaces/WidgetConfigurationActivity.kt:20`, `app/src/debug/java/com/alpha/assistant/CloudTestActivity.kt:15`, and `app/src/debug/java/com/alpha/assistant/VoiceTestActivity.kt:12`
- Modify: `app/src/main/AndroidManifest.xml` (`android:label="@string/app_name"` on `<application>`)
- Modify (Option A of Unresolved decision 2): `docs/parity/compose-coverage.json` (`source_sha256`)
- Create: `T/ui/ShellRobot.kt`
- Test: `T/ui/AppLocaleWorkflowTest.kt`

**Interfaces:**
- Produces: `abstract class AlphyActivity : ComponentActivity()`. All six activities extend it.
- Produces: `R.string.app_name`.
- Produces in `ShellRobot.kt`:

```kotlin
internal typealias VoiceFactory = (Application, CoroutineScope, (String) -> Unit) -> VoiceSession
internal class FixtureProvider(private val reply: String = "Fixture response") : ChatProvider {
    @Volatile var calls = 0
    @Volatile var failure: Exception? = null              // thrown after the count
    @Volatile var gate: CompletableDeferred<Unit>? = null // awaited before the answer
}
internal class ShellFixture(prefix: String) {
    val context: Context
    val name: String
    val store: ShellStore
    val keys: SecureKeyStore
    val provider: FixtureProvider
    @Volatile var model: ShellViewModel?
    fun install(saveKey: Boolean = true, voice: VoiceFactory? = null)
    fun seedChat(id: String, title: String, message: String, selected: Boolean = false, updatedAt: Long = 1L)
    fun launch(): ActivityScenario<CloudTestActivity>
    fun remove()
}
internal class ShellRobot(fixture: String) {
    val instrumentation: Instrumentation
    val automation: UiAutomation
    val diagnostics: UiFailureDiagnostics
    fun text(@StringRes id: Int, vararg args: Any): String
    fun find(label: String): AccessibilityNodeInfo?
    fun waitFor(label: String): AccessibilityNodeInfo
    fun waitGone(label: String)
    fun waitUntil(condition: () -> Boolean)
    fun field(label: String): AccessibilityNodeInfo
    fun click(label: String)
    fun edit(label: String, value: String)
    fun shell(command: String): String
}
```

- [ ] **Step 1: Create the shared test helpers**

- Copy the bodies of `find`, `waitFor`, `field`, `click`, `edit`, and `waitUntil` from `T/ui/ShellWorkflowTest.kt:193-241`. `find` starts from `diagnostics.freshRoot()`, so a poll never reads stale cached nodes.
- `FixtureProvider.respond` adds 1 to `calls` and throws `failure` when it is set. Then it awaits `gate` when it is set. Then it emits the answer JSON of `ShellWorkflowTest.kt:39` with `reply` as its text.
- `ShellFixture` sets `name = "$prefix-${UUID.randomUUID()}"`, `store = ShellStore(context, "$name.db")`, and `keys = SecureKeyStore(context, name, name)`, as `ShellWorkflowTest.kt:31-36` does. So the key preferences file is `name` (Task 6 reads it).
- `install` saves the key `alpha-test-only` when `saveKey` is true. It sets `CloudTestActivity.factory` to make `ShellViewModel(app, provider, store, keys, voiceFactory = voice)` and keeps it in `model`.
- `seedChat` saves `Conversation(id, title, updatedAt, updatedAt, messages = listOf(Message("$id-u", "user", message, updatedAt, "sent")))`. When `selected` is true, it also saves the setting `selected`.
- `remove` copies `ShellWorkflowTest.kt:42-47`. It also deletes the database `$name.db-tools.db-automations.db`.
- `text` calls `targetContext.getString`. The default resources are Russian, so it gives Russian on every device locale.
- `shell` runs `automation.executeShellCommand` and returns the trimmed output.

- [ ] **Step 2: Write the failing test**

```kotlin
class AppLocaleWorkflowTest {
    private val fixture = ShellFixture("app-locale")
    @After fun remove() = fixture.remove()

    @Test fun activitiesUseRussianOnAnotherDeviceLocale() {
        assumeFalse("The device locale is Russian", Resources.getSystem().configuration.locales[0].language == "ru")
        fixture.install()
        fixture.launch().use { scenario ->
            scenario.onActivity {
                assertEquals("ru", it.resources.configuration.locales[0].language)
                assertEquals("Закрыть меню навигации", it.getString(androidx.compose.ui.R.string.close_drawer))
                assertEquals("Alphy", it.getString(R.string.app_name))
            }
        }
        for (type in listOf(MainActivity::class, AssistantActivity::class, WidgetConfigurationActivity::class,
                MedicineReportActivity::class, CloudTestActivity::class, VoiceTestActivity::class))
            assertTrue("${type.simpleName} extends AlphyActivity", AlphyActivity::class.java.isAssignableFrom(type.java))
    }
}
```

- [ ] **Step 3: Run the test to see it fail**

Run: `python3 tools/compose_acceptance/run.py suite --label p1-t2-locale-red --expect build-failed com.alpha.assistant.ui.AppLocaleWorkflowTest`
Expected: exit 0, and `build.log` names `Unresolved reference 'AlphyActivity'`.

- [ ] **Step 4: Implement the Russian locale**

- `AlphyActivity.attachBaseContext(base)` calls `super.attachBaseContext(base.createConfigurationContext(Configuration().apply { setLocales(LocaleList(Locale.forLanguageTag("ru"))) }))`. The override holds only the locale. Do not copy `base.resources.configuration`. A full copy keeps the screen size and the window bounds of attach time after a resize without recreation.
- Do not call `Locale.setDefault`. UI code that formats dates uses `LocalConfiguration.current.locales[0]` (Task 8).
- Create `strings.xml` with `xmlns:tools` and `tools:locale="ru"` on `<resources>`, and `app_name`.
- Change the six superclasses and the manifest label.

- [ ] **Step 5: Run the test to see it pass**

Run: `python3 tools/compose_acceptance/run.py suite --label p1-t2-locale com.alpha.assistant.ui.AppLocaleWorkflowTest`
Expected: exit 0 and `OK (1 test)` in `stdout.log`.

- [ ] **Step 6: Apply Unresolved decision 2 and run the host tests**

- Option A: write the new `harness.digest` value of `capabilities/MedicineCapabilities.kt` into `source_sha256` in `compose-coverage.json`.
- Run: `python3 tools/compose_acceptance/run.py host --label p1-t2-host`, with `--expect failed` for Option B.
- Expected: exit 0. With Option B, `stdout.log` names only `test_coverage_preserves_pinned_reference_and_current_sources`.

- [ ] **Step 7: Run the full suite**

Label: `p1-t2-full`. Expected: exit 0.

- [ ] **Step 8: Review and commit**

```bash
git add app/src/main/java/com/alpha/assistant app/src/debug app/src/main/res/values/strings.xml app/src/main/AndroidManifest.xml app/src/androidTest/java/com/alpha/assistant/ui docs/parity/compose-coverage.json
git commit -m "Give Alphy a Russian app locale and string resources"
```

---

### Task 3: Expressive theme with system light and dark

**Files:**
- Modify: `ui/AlphaTheme.kt` (the full file)
- Modify: `MainActivity.kt:38`, `CloudTestActivity.kt:24`, `surfaces/AssistantActivity.kt:52`, `surfaces/WidgetConfigurationActivity.kt:28` (call `AlphaTheme { … }`)
- Modify: `data/ShellStore.kt` (`ShellSnapshot` fields, `load()` at :58-59)
- Modify: `ui/ShellViewModel.kt:197-198` (`retrySave`), `:469-472` (delete `setting`)
- Modify: `ui/AlphaShell.kt:398-401` (delete the «Оформление» group)
- Modify: `T/data/ShellStorageTest.kt:31-32`, `T/data/HistoryStorageTest.kt:38`
- Test: `T/ui/AlphaThemeContrastTest.kt`
- Docs: `docs/shell-integration.md:9, 23, 25, 29`, `docs/cloud-integration.md:33`, `docs/shell-verification.md:6`, `docs/history-repair-verification.md:11`

**Interfaces:**
- Produces: `internal val AlphaLightColors: ColorScheme`, `internal val AlphaDarkColors: ColorScheme`, `internal fun alphaColorScheme(context: Context, dark: Boolean): ColorScheme`, and `@Composable fun AlphaTheme(content: @Composable () -> Unit)`.
- Produces: `ShellSnapshot` without `theme` and `mode`.

- [ ] **Step 1: Write the failing contrast test**

```kotlin
class AlphaThemeContrastTest {
    @Test fun everySchemeKeepsBodyContrast() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val schemes = mapOf("fixed light" to AlphaLightColors, "fixed dark" to AlphaDarkColors,
            "device light" to alphaColorScheme(context, false), "device dark" to alphaColorScheme(context, true))
        for ((name, s) in schemes) for ((pair, colors) in mapOf(
            "onSurface/surface" to (s.onSurface to s.surface),
            "onSurfaceVariant/surface" to (s.onSurfaceVariant to s.surface),
            "onSurfaceVariant/surfaceContainer" to (s.onSurfaceVariant to s.surfaceContainer),
            "onSurfaceVariant/surfaceContainerHigh" to (s.onSurfaceVariant to s.surfaceContainerHigh),
            "onPrimary/primary" to (s.onPrimary to s.primary),
            "onPrimaryContainer/primaryContainer" to (s.onPrimaryContainer to s.primaryContainer),
            "onSecondaryContainer/secondaryContainer" to (s.onSecondaryContainer to s.secondaryContainer),
            "onTertiaryContainer/tertiaryContainer" to (s.onTertiaryContainer to s.tertiaryContainer),
            "onError/error" to (s.onError to s.error),
            "onErrorContainer/errorContainer" to (s.onErrorContainer to s.errorContainer),
            "primary/surface" to (s.primary to s.surface),
            "inverseOnSurface/inverseSurface" to (s.inverseOnSurface to s.inverseSurface))) {
            val ratio = ColorUtils.calculateContrast(colors.first.toArgb(), colors.second.toArgb())
            assertTrue("$name $pair is $ratio:1", ratio >= 4.5)
        }
        assertEquals(Color(0xFF63568F), AlphaLightColors.primary)
        assertEquals(Color(0xFFCDBDFF), AlphaDarkColors.primary)
    }
}
```

- [ ] **Step 2: Change the storage tests**

- `ShellStorageTest.kt:22-23` and `HistoryStorageTest.kt:18` keep their `saveSetting("theme", …)` and `saveSetting("mode", …)` calls. They are now stale rows.
- Delete the `theme` and `mode` asserts at `ShellStorageTest.kt:31-32` and `HistoryStorageTest.kt:38`. Keep the other asserts on those lines.
- Add the comment `// Stale theme and mode rows from earlier versions must not block load.` above each stale-row seed.

- [ ] **Step 3: Run the tests to see them fail**

Run: `python3 tools/compose_acceptance/run.py suite --label p1-t3-theme-red --expect build-failed com.alpha.assistant.ui.AlphaThemeContrastTest`
Expected: exit 0, and `build.log` names `Unresolved reference 'AlphaLightColors'`.

- [ ] **Step 4: Implement the theme**

- `AlphaLightColors` and `AlphaDarkColors` use `lightColorScheme(...)` and `darkColorScheme(...)` with all 48 roles of Appendix A.
- `alphaColorScheme` returns `dynamicDarkColorScheme(context)` or `dynamicLightColorScheme(context)` on API 31 and later. Otherwise, it returns the fixed scheme.
- `AlphaTheme` calls `MaterialExpressiveTheme` with these arguments:
  - `colorScheme = alphaColorScheme(LocalContext.current, isSystemInDarkTheme())`
  - `motionScheme = MotionScheme.expressive()`
  - `shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(32.dp))`
  - `typography`: the default `Typography()` with `FontWeight.SemiBold` on the display, headline, and title styles (9 styles)
- If the compiler requires an opt-in for `MaterialExpressiveTheme` or `MotionScheme`, stop and report it. The spec permits an opt-in only for `LoadingIndicator` and `MaterialShapes`.

- [ ] **Step 5: Remove the theme and mode settings**

- Delete `theme` and `mode` from `ShellSnapshot` and from the `load()` arguments.
- Delete the `theme` and `mode` writes in `retrySave` and the function `setting`.
- Delete the «Оформление» radio group in `SettingsPane`.
- Change the four `AlphaTheme(...)` calls to `AlphaTheme { … }`.
- Do not delete the stale rows. The database version stays 1.

- [ ] **Step 6: Run the tests to see them pass**

Run: `python3 tools/compose_acceptance/run.py suite --label p1-t3-theme com.alpha.assistant.ui.AlphaThemeContrastTest com.alpha.assistant.data.ShellStorageTest com.alpha.assistant.data.HistoryStorageTest`
Expected: exit 0.

- [ ] **Step 7: Change the docs**

- `shell-integration.md:9`: the `settings` table stores `selected` and `newDraft`. Stale `theme` and `mode` rows can stay, and the app ignores them.
- `shell-integration.md:23`: replace the theme sentence. Light and dark follow the system. `AlphaTheme` uses dynamic color on API 31 and later and a fixed palette from `#7652DF` on API 29 and 30.
- `shell-integration.md:25`: delete the sentence about legacy processing-mode values.
- `shell-integration.md:29`: replace "theme, mode," with "stale theme and mode rows,".
- `cloud-integration.md:33`: replace the last sentence. Stale processing-mode rows can stay in the `settings` table, and the app ignores them.
- `shell-verification.md:6`: delete "mode, and theme".
- `history-repair-verification.md:11`: delete "themes, modes,".

- [ ] **Step 8: Run the full suite**

Label: `p1-t3-full`. Expected: exit 0.

- [ ] **Step 9: Review and commit**

```bash
git add app/src docs/shell-integration.md docs/cloud-integration.md docs/shell-verification.md docs/history-repair-verification.md
git commit -m "Use Material 3 Expressive and follow the system light and dark"
```

---

### Task 4: Route back stack

**Files:**
- Create: `ui/ShellRoute.kt`
- Modify: `ui/AlphaShell.kt:55-170` (state, entry effect, Back, `FLAG_SECURE`, layout, voice stop), `:187` (`ChatPane` signature), and `:194` (delete the voice stop of `ChatPane`)
- Modify: `surfaces/AssistantActivity.kt:121`
- Modify: `app/src/main/AndroidManifest.xml` (`android:enableOnBackInvokedCallback`)
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `T/ui/ShellRobot.kt`
- Modify: `T/tools/MiniAppPresentationWorkflowTest.kt:474` (library check after Back)
- Test: `T/ui/ShellNavigationWorkflowTest.kt`, `T/ui/ShellVoiceWorkflowTest.kt`

**Interfaces:**
- Consumes: Navigation 3 (Task 1). `ShellFixture` and `ShellRobot` (Task 2).
- Produces:

```kotlin
internal sealed interface ShellRoute {
    data object Desktop : ShellRoute
    data class Chat(val id: String) : ShellRoute
}
internal enum class ShellDestination : ShellRoute { Search, MiniApps, Automations, Access, Settings }
@Composable internal fun ChatPane(model: ShellViewModel, conversation: Conversation?, tools: () -> Unit, context: () -> Unit, automation: (String) -> Unit)
```

- Produces: `R.string.menu` and `R.string.settings`.
- Produces in `ShellRobot`: `fun back()`, `fun secure(scenario: ActivityScenario<*>): Boolean`, and `fun allowMicrophone(scenario: ActivityScenario<*>)`.

- [ ] **Step 1: Write the failing test and the helpers**

- `back()` performs `GLOBAL_ACTION_BACK` and waits for idle as `click` does.
- `secure(scenario)` reads `window.attributes.flags` in `scenario.onActivity` and tests `FLAG_SECURE`.
- `allowMicrophone(scenario)` returns when `RECORD_AUDIO` is granted. Otherwise, it calls `requestPermissions` in `scenario.onActivity`. Then it clicks the allow button and waits for the grant, as `T/voice/InlineVoiceWorkflowTest.kt:184-196` does. The OPPO device blocks shell grants (spec 9), so the helper uses the dialog.

```kotlin
class ShellNavigationWorkflowTest {
    private val fixture = ShellFixture("shell-navigation")
    private val robot = ShellRobot("shell-navigation")
    @Before fun install() = fixture.install()
    @After fun remove() = fixture.remove()

    private fun openSettings(scenario: ActivityScenario<*>) {
        robot.click(robot.text(R.string.menu))
        robot.click(robot.text(R.string.settings))
        robot.waitUntil { robot.secure(scenario) }
    }

    @Test fun backLeavesSettingsThenChatThenApp() {
        fixture.seedChat("nav", "Nav fixture", "Nav fixture message", selected = true)
        fixture.launch().use { scenario ->
            robot.waitFor("Nav fixture message")
            openSettings(scenario)
            scenario.recreate()
            robot.waitUntil { robot.secure(scenario) }
            robot.back()
            robot.waitFor("Nav fixture message")
            robot.waitUntil { !robot.secure(scenario) }
            robot.back()
            robot.waitGone("Nav fixture message")
            robot.waitUntil { fixture.store.load().selectedId == null }
            robot.back()
            robot.waitUntil { scenario.state == Lifecycle.State.DESTROYED }
        }
    }

    @Test fun entryReplacesDestination() {
        fixture.launch().use { scenario ->
            openSettings(scenario)
            robot.instrumentation.runOnMainSync { fixture.model!!.openSurface(null) }
            robot.waitUntil { !robot.secure(scenario) }
            robot.back()
            robot.waitUntil { scenario.state == Lifecycle.State.DESTROYED }
        }
    }
}

class ShellVoiceWorkflowTest {
    private val fixture = ShellFixture("shell-voice")
    private val robot = ShellRobot("shell-voice")
    @After fun remove() = fixture.remove()

    @Test fun continuousVoiceSurvivesDesktopSend() {
        fixture.provider.gate = CompletableDeferred()
        fixture.install(voice = { app, scope, transcript ->
            VoiceSession(app, scope, transcript, prepareModels = {}, captureAudio = { AudioTranscription("Голосовой вопрос", null, 0) })
        })
        fixture.launch().use { scenario ->
            robot.allowMicrophone(scenario)
            val model = fixture.model!!
            robot.waitUntil { !model.loading }
            robot.instrumentation.runOnMainSync { model.startVoice(oneShot = false) }
            robot.waitUntil { model.voice.phase == "review" && model.snapshot.draft.text == "Голосовой вопрос" }
            robot.instrumentation.runOnMainSync { model.send() }
            robot.automation.waitForIdle(500, 5000)
            robot.waitFor("Голосовой вопрос")
            assertTrue(model.voice.active)
            assertEquals("waiting", model.voice.phase)
            fixture.provider.gate!!.complete(Unit)
            // Only an active session reaches the speech step, and the fake models have no synthesizer.
            robot.waitUntil { model.voice.status == "Не удалось озвучить ответ. Текст ответа сохранён в чате." }
        }
    }
}
```

`ShellVoiceWorkflowTest` passes before Step 5 too, because one `ChatPane` serves the chat page today. It guards the change to two route entries.

Also add `shown("Mini-app library content")` after `waitUntil {surface()==null}` at `MiniAppPresentationWorkflowTest.kt:474`. The Back handler of `FocusedToolPane` (`tools/ToolUi.kt:153`) shows the library. A `NavDisplay` pop shows the chat, so the check then fails. The check passes before Step 5 too.

- [ ] **Step 2: Add the two strings**

Add `menu` and `settings` (Appendix C). Use them in the «Меню» button of `ShellHeader` and in the «Настройки» button of the menu sheet. This is the smallest change that compiles.

- [ ] **Step 3: Run the test to see it fail**

Run: `python3 tools/compose_acceptance/run.py suite --label p1-t4-nav-red --expect failed com.alpha.assistant.ui.ShellNavigationWorkflowTest com.alpha.assistant.ui.ShellVoiceWorkflowTest`
Expected: exit 0. `failures` lists only `backLeavesSettingsThenChatThenApp`. Today Back on the chat finishes the activity, so `selectedId` stays `nav`.

- [ ] **Step 4: Create `ShellRoute.kt`**

Write the types in Interfaces.

- [ ] **Step 5: Implement the back stack in `AlphaShell`**

- Replace `page` with `var destination by rememberSaveable { mutableStateOf<ShellDestination?>(null) }`. An enum value is `Serializable`, so the saved state holds it.
- `val backStack = listOfNotNull(ShellRoute.Desktop, snapshot.selected?.let { ShellRoute.Chat(it.id) }, destination)`.
- `fun navigate(target: ShellDestination?)` sets `sheet = null` and `destination = target`, then clears the focus.
- `fun back()` calls `navigate(null)` when `destination != null`. Otherwise, it calls `model.select(null)`.
- Delete the shell `BackHandler` at line 76. `NavDisplay(backStack, Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter, onBack = ::back, entryProvider = entryProvider { … })` replaces the `if (split) … else when (page)` block at lines 112-125. The `BoxWithConstraints` keeps `Modifier.fillMaxWidth().weight(1f)`, because `ColumnScope.weight` is not available inside its lambda. Keep the default entry decorators, which keep the saved state of each entry.
- Entries:
  - `entry<ShellRoute.Desktop> { ChatPane(model, null, …) }`
  - `entry<ShellRoute.Chat> { route -> ChatPane(model, snapshot.conversations.find { it.id == route.id }, …) }`. The route gives the conversation, so a chat keeps its messages during its exit animation.
  - `entry<ShellDestination> { when (it) { … } }` with `HistoryPane`, the mini-app split or `ToolsPane`, `AutomationsPane`, `CapabilitiesPane`, and `SecureWindow { SettingsPane(model) }`.
- Add `@Composable private fun SecureWindow(content: @Composable () -> Unit)`. Its `DisposableEffect(Unit)` adds `FLAG_SECURE` and clears it in `onDispose`. It replaces `DisposableEffect(page)` at lines 80-83, so the flag follows the Settings entry.
- The `BoxWithConstraints` at lines 110-126 now holds `NavDisplay`. It computes `split = maxWidth >= 640.dp && destination == ShellDestination.MiniApps && !immersive`. The `MiniApps` branch shows the split when `split` is true. `focusedMiniApp`, `immersive`, and the key of `immersiveExited` use `destination == ShellDestination.MiniApps` in place of `page == "tools"`.
- Delete the voice stop of `ChatPane` at line 194. In the `BoxWithConstraints`, after `split`, add `val voiceShown = backStack.last() !is ShellDestination || split || sheet == "voice"` and `LaunchedEffect(voiceShown) { if (!voiceShown) model.voice.stop() }`. The rule stays in the box, because `split` reads `maxWidth`, and `immersive` is a local of the loaded branch (lines 93-129). `VoiceVisibility` (`ui/VoiceControls.kt:33-45`) still stops the session when the shell leaves composition.
- Map each `navigate("…")` argument: `"chat"` to `null`, `"tools"` to `MiniApps`, `"automation"` to `Automations`, `"capabilities"` to `Access`, `"history"` to `Search`, and `"settings"` to `Settings`.
- The entry effect at lines 62-68 sets `destination = if (model.surfaceToolId != null) ShellDestination.MiniApps else null`. Keep `surfaceSequence` and `consumedSurface`.
- `ChatPane` reads `conversation` in place of `snapshot.selected`. The composer still edits `model.snapshot.draft`. `AssistantActivity.kt:121` passes `model.snapshot.selected`.
- Add `android:enableOnBackInvokedCallback="true"` to `<application>`. The targetSdk default turns predictive back on only on Android 16 and later. Android 13 to 15 need the attribute. No code overrides `onBackPressed`.

- [ ] **Step 6: Run the tests to see them pass**

Run: `python3 tools/compose_acceptance/run.py suite --label p1-t4-nav --baseline evidence/alphy-ui/p1-t0-baseline/run.json com.alpha.assistant.ui.ShellNavigationWorkflowTest com.alpha.assistant.ui.ShellVoiceWorkflowTest com.alpha.assistant.tools.MiniAppPresentationWorkflowTest`
Expected: exit 0. The library check after `MiniAppPresentationWorkflowTest.kt:474` shows that the Back handler of a focused mini-app still wins over `NavDisplay`.

- [ ] **Step 7: Run the full suite**

Label: `p1-t4-full`. Expected: exit 0.

- [ ] **Step 8: Review and commit**

```bash
git add app/src
git commit -m "Show the shell routes with a Navigation 3 back stack"
```

---

### Task 5: Top bar and drawer

**Files:**
- Create: `ui/ShellChrome.kt`
- Modify: `ui/ShellRoute.kt` (destination titles)
- Modify: `ui/AlphaShell.kt` (drawer, top bar, and the shell texts in resources)
- Delete: `ShellHeader` at `ui/AlphaShell.kt:173-185`, `MenuRow` at `:317`, and the menu and voice sheet at `:136-170`
- Modify: `ui/AlphaIcons.kt` (glyphs `Compose` and `Shield`)
- Modify: `tools/ToolUi.kt:72-75` and `automations/AutomationUi.kt:141` (delete the page headings)
- Modify: `surfaces/AssistantActivity.kt:100`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `T/ui/ShellRobot.kt`
- Modify: `T/ui/ShellWorkflowTest.kt:52, 177-189`, `T/ui/HistoryManagementWorkflowTest.kt:47, 55, 63, 100, 104, 106`, `T/ui/ReviewedContextUiWorkflowTest.kt:66-67`, `T/tools/MiniAppPresentationWorkflowTest.kt:694`, `T/ui/ShellNavigationWorkflowTest.kt` (`openSettings`)
- Test: `T/ui/DrawerWorkflowTest.kt`
- Docs: `docs/shell-integration.md:23`, `docs/shell-verification.md:13`, `docs/voice-integration.md:23`

**Interfaces:**
- Consumes: `ShellRoute`, `ShellDestination`, `navigate`, and `back` (Task 4).
- Produces:

```kotlin
internal enum class ShellDestination(@StringRes val title: Int) : ShellRoute
@Composable internal fun ShellTopBar(title: String, onMenu: () -> Unit, onNewChat: () -> Unit)
@Composable internal fun ShellDrawerSheet(drawerState: DrawerState, conversations: List<Conversation>, selectedId: String?,
    destination: ShellDestination?, profileName: String, open: (ShellDestination) -> Unit, openChat: (String) -> Unit, newChat: () -> Unit)
```

- Produces: the strings `new_chat`, `search`, `mini_apps`, `automations`, `recent`, `access`, and `profile`.
- Produces in `ShellRobot`: `fun openDrawer(scenario: ActivityScenario<*>)`, `fun awaitDrawerClosed(scenario: ActivityScenario<*>)`, `fun assertTarget(label: String)`, `fun bounds(label: String): Rect`, and `fun rotate(scenario: ActivityScenario<*>, landscape: Boolean)`.

- [ ] **Step 1: Add the helpers**

- `openDrawer` clicks `R.string.menu`. Then it waits for the scrim label `androidx.compose.ui.R.string.close_drawer`, read through `scenario.onActivity`. The scrim has this label only after the drawer settles open.
- `awaitDrawerClosed` waits until that scrim label is gone. The label goes only after the drawer settles closed.
- `assertTarget` finds the clickable ancestor of the label. It must be visible, inside the screen, and at least 48 dp wide and high, as `ShellWorkflowTest.kt:52-60` measures.
- `bounds` calls `waitFor(label)` and returns `getBoundsInScreen`.
- `rotate` sets `requestedOrientation` to `SCREEN_ORIENTATION_LANDSCAPE` or `SCREEN_ORIENTATION_PORTRAIT` in `scenario.onActivity`. Then it waits until `resources.configuration.orientation` of the activity matches.

- [ ] **Step 2: Write the failing test**

```kotlin
class DrawerWorkflowTest {
    private val fixture = ShellFixture("drawer")
    private val robot = ShellRobot("drawer")
    @Before fun install() = fixture.install()
    @After fun remove() = fixture.remove()

    @Test fun drawerShowsRowsAndBackClosesItFirst() {
        fixture.seedChat("alpha", "Alpha chat", "Alpha message", selected = true)
        fixture.seedChat("beta", "Beta chat", "Beta message")
        fixture.launch().use { scenario ->
            robot.waitFor("Alpha message")
            robot.openDrawer(scenario)
            val rows = listOf(R.string.search, R.string.new_chat, R.string.mini_apps, R.string.automations,
                R.string.recent, R.string.access).map { robot.text(it) } + "Beta chat"
            val profile = robot.bounds(robot.text(R.string.profile))
            assertTrue(robot.bounds(robot.text(R.string.automations)).bottom <= robot.bounds("Beta chat").top)
            for (row in rows) assertTrue("$row is above the profile", robot.bounds(row).bottom <= profile.top)
            robot.assertTarget(robot.text(R.string.settings))
            robot.back()
            robot.awaitDrawerClosed(scenario)
            robot.waitFor("Alpha message")
            robot.openDrawer(scenario)
            robot.click("Beta chat")
            robot.awaitDrawerClosed(scenario)
            robot.waitFor("Beta message")
            robot.waitUntil { fixture.store.load().selectedId == "beta" }
            robot.openDrawer(scenario)
            robot.click(robot.text(R.string.new_chat))
            robot.awaitDrawerClosed(scenario)
            robot.waitGone("Beta message")
            robot.waitUntil { fixture.store.load().selectedId == null }
        }
    }

    @Test fun entryClosesOpenDrawer() {
        fixture.launch().use { scenario ->
            robot.openDrawer(scenario)
            robot.click(robot.text(R.string.settings))
            robot.awaitDrawerClosed(scenario)
            robot.waitUntil { robot.secure(scenario) }
            robot.openDrawer(scenario)
            robot.instrumentation.runOnMainSync { fixture.model!!.openSurface(null) }
            robot.awaitDrawerClosed(scenario)
            robot.waitUntil { !robot.secure(scenario) }
            robot.assertTarget(robot.text(R.string.menu))
        }
    }

    @Test fun drawerKeepsSettingsReachableInLandscape() {
        fixture.seedChat("alpha", "Alpha chat", "Alpha message")
        fixture.launch().use { scenario ->
            try {
                robot.openDrawer(scenario)
                robot.rotate(scenario, landscape = true)
                for (id in listOf(R.string.search, R.string.access, R.string.settings)) {
                    robot.waitFor(robot.text(id))
                    robot.assertTarget(robot.text(id))
                }
            } finally { robot.rotate(scenario, landscape = false) }
        }
    }

    @Test fun topBarHasMenuTitleAndNewChat() {
        fixture.seedChat("alpha", "Alpha chat", "Alpha message", selected = true)
        fixture.launch().use {
            robot.waitFor("Alpha message")
            robot.assertTarget(robot.text(R.string.menu))
            robot.assertTarget(robot.text(R.string.new_chat))
            robot.waitFor(robot.text(R.string.app_name))
            robot.click(robot.text(R.string.new_chat))
            robot.waitGone("Alpha message")
            robot.waitUntil { fixture.store.load().selectedId == null }
        }
    }
}
```

- [ ] **Step 3: Change the older tests**

- `ShellWorkflowTest.kt:52`: check «Меню» and «Новый чат» from `R.string` in place of «Меню» and «История». Match on `text` or `contentDescription`, and expect each label one time.
- `ShellWorkflowTest.kt:177-178` and `:187-188`: replace `click("Меню"); click("Новый чат")` with one click on `R.string.new_chat` (the top bar button).
- `ShellWorkflowTest.kt:181`: replace `click("История")` with `ShellRobot("shell").openDrawer(scenario)`, a click on `R.string.search`, and `awaitDrawerClosed`.
- `HistoryManagementWorkflowTest.kt:47`, `:55`, `:63`, `:106`: replace `click("История")` with the same three calls. Change `:100` to `.use { scenario ->`, because its lambda has no `scenario` today.
- `HistoryManagementWorkflowTest.kt:104`: replace `click("Меню"); click("Новый чат")` with one click on `R.string.new_chat`. The clicks on «Новый чат» at `:62`, `:67`, and `:113` stay, because the top bar button has that label.
- `ReviewedContextUiWorkflowTest.kt:66-67`: open the drawer, click `R.string.access`, await the closed drawer, and wait for `R.string.access` (the top bar title).
- `MiniAppPresentationWorkflowTest.kt:694`: assert that `R.string.new_chat` is absent, with the message "Focused app has no shell new-chat action".
- `ShellNavigationWorkflowTest.openSettings`: use `openDrawer`, the click on `R.string.settings`, and `awaitDrawerClosed`.

- [ ] **Step 4: Run the tests to see them fail**

Run: `python3 tools/compose_acceptance/run.py suite --label p1-t5-drawer-red --expect build-failed com.alpha.assistant.ui.DrawerWorkflowTest`
Expected: exit 0, and `build.log` names `Unresolved reference 'new_chat'`.

- [ ] **Step 5: Implement `ShellChrome.kt`**

- `ShellTopBar` is a 64 dp row with three parts:
  - An `IconButton` with `Glyph.Menu` and the label «Меню».
  - The title in `titleLarge`, on one line with an ellipsis, with `Modifier.weight(1f)`.
  - An `IconButton` with `Glyph.Compose` and the label «Новый чат».
  - Each `IconButton` has `Modifier.size(48.dp)` and its label as `contentDescription`.
- `ShellDrawerSheet` is a `ModalDrawerSheet(drawerState, Modifier.widthIn(max = LocalConfiguration.current.screenWidthDp.dp - 56.dp))`. A full-width sheet would cover the scrim, and Compose then gives the scrim no accessibility node. The sheet has these parts in sequence:
  1. One `LazyColumn(Modifier.weight(1f))` with these items:
     - The `NavigationDrawerItem` rows «Поиск» (`Glyph.Search`), «Новый чат» (`Glyph.Compose`), «Мини-приложения» (`Glyph.Grid`), and «Автоматизации» (`Glyph.Repeat`).
     - The heading «Недавние», and one row for each conversation, in the order of `conversations`. A title has one line with an ellipsis.
  2. A divider and the row «Доступ и функции» (`Glyph.Shield`).
  3. The profile row: a 40 dp circle with the first letter of `profileName`, the name, and an `IconButton` with `Glyph.Settings` and the label «Настройки». The name has `Modifier.weight(1f)` and one line with an ellipsis.
- Parts 2 and 3 have no weight, so the column measures them first. A short window clips only the list.
- A destination row is selected when it equals `destination`. The chat row of `selectedId` is selected when `destination` is null.
- Add `Compose` (a pencil) and `Shield` to `Glyph`. Their path data is an Implementation choice in the 24-unit grid of the other glyphs.

- [ ] **Step 6: Implement the drawer in `AlphaShell`**

- Give `ShellDestination` the titles `search`, `mini_apps`, `automations`, `access`, and `settings`.
- `val drawerState = rememberDrawerState(DrawerValue.Closed)`.
- Wrap the loaded content in `ModalNavigationDrawer(drawerContent = { ShellDrawerSheet(…) }, drawerState = drawerState, gesturesEnabled = drawerState.isOpen || backStack.last() !is ShellDestination)`.
- Drawer handlers:
  - `open` calls `navigate(it)` and closes the drawer.
  - `openChat` calls `model.select(it)` and `navigate(null)`, and closes the drawer.
  - `newChat` calls `model.select(null)` and `navigate(null)`, and closes the drawer.
  - `profileName` is `stringResource(R.string.profile)` (Task 6 adds the user name).
- `if (!focusedMiniApp) ShellTopBar(…)` replaces `ShellHeader`:
  - The title is the destination title, or `app_name` on the Desktop and Chat routes.
  - `onMenu` clears the focus and opens the drawer.
  - `onNewChat` calls `model.select(null)` and `navigate(null)`.
- When the entry effect consumes a new entry (inside its `if`), it also runs `drawerState.snapTo(DrawerValue.Closed)`. A recreation consumes no entry, because `consumedSurface` is saved, so a rotation keeps an open drawer open.
- Delete `ShellHeader`, `MenuRow`, and the `ModalBottomSheet` with the menu and voice sheets. Replace `sheet` with `var contextSheet by rememberSaveable { mutableStateOf(false) }`.
- Add `VoiceControls(model)` as the last child of the scrollable column in `SettingsPane` (`ui/AlphaShell.kt:396-413`). A second child of the Settings entry draws over the page, because `NavDisplay` places all children of an entry at one origin. Task 6 replaces this `SettingsPane` and gives `VoiceControls` its own section.
- In the voice rule of Task 4, replace `sheet == "voice"` with `backStack.last() == ShellDestination.Settings`.
- Delete the heading row at `tools/ToolUi.kt:72-75`, with its «Chat» button, and the heading at `automations/AutomationUi.kt:141`. The top bar shows these titles (spec 5.12 "One title for each screen."). `FocusedToolPane` still uses the `close` parameter of `ToolsPane`.
- Move the load failure, save banner, and send failure texts to resources, with no text change.
- `AssistantActivity.kt:100`: replace `ShellHeader(…)` with `ShellTopBar(stringResource(R.string.app_name), { sheet = "menu" }) { model.select(null) }`.
- Assumption: while the drawer is open, the drawer gets Back first.
  - Basis: `ModalDrawerSheet` registers its `PredictiveBackHandler` after `NavDisplay` composes.
  - Test: `drawerShowsRowsAndBackClosesItFirst`.
  - Fallback: if the test fails, pass `onBack = { if (drawerState.isOpen) closeDrawer() else back() }` to `NavDisplay`. Record the result in the commit message.

- [ ] **Step 7: Run the tests to see them pass**

Run: `python3 tools/compose_acceptance/run.py suite --label p1-t5-drawer --baseline evidence/alphy-ui/p1-t0-baseline/run.json com.alpha.assistant.ui.DrawerWorkflowTest com.alpha.assistant.ui.ShellNavigationWorkflowTest com.alpha.assistant.ui.ShellWorkflowTest com.alpha.assistant.ui.HistoryManagementWorkflowTest com.alpha.assistant.ui.ReviewedContextUiWorkflowTest com.alpha.assistant.tools.MiniAppPresentationWorkflowTest`
Expected: exit 0.

- [ ] **Step 8: Change the docs**

- `shell-integration.md:23`: the top bar with «Меню» and «Новый чат» and the composer stay visible without chat scrolling. «Меню» opens a drawer from the left. Back closes the drawer, then a page, then the chat.
- `shell-verification.md:13`: the top bar, the drawer, and the composer must stay accessible with Android system insets and the keyboard.
- `voice-integration.md:23`: «Настройки» also give an explicit continuous voice session.

- [ ] **Step 9: Run the full suite**

Label: `p1-t5-full`. Expected: exit 0.

- [ ] **Step 10: Review and commit**

```bash
git add app/src docs/shell-integration.md docs/shell-verification.md docs/voice-integration.md
git commit -m "Frame the shell with a top bar and a left drawer"
```

---

### Task 6: Settings and consent at key entry

**Files:**
- Modify: `data/SecureKeyStore.kt` (consent record)
- Create: `cloud/CloudKey.kt`
- Modify: `ui/ShellViewModel.kt:61-65` (`openSurface` and `surfaceSettings`), `:66-74` (key state), `:186-203` (`retrySave` writes `userName`), `:335-341` (key read), `:476-498` (key actions)
- Modify: `tools/ToolController.kt:35, 45, 72, 91-92, 106, 266-276, 292` (`errorCode`)
- Modify: `tools/ToolUi.kt:48-49, 86, 115, 180-187` (`settings` action and «Открыть настройки»)
- Modify: `data/ShellStore.kt` (`userName`)
- Create: `ui/SettingsPane.kt` (replaces `SettingsPane` at `ui/AlphaShell.kt:393-415`)
- Modify: `ui/AlphaIcons.kt` (`color` parameter of `AlphaIcon`, new `StatusLine`)
- Modify: `capabilities/AndroidAccessUi.kt` (new `AssistantSettingsRow`)
- Delete: `SystemEntrySettings` in `surfaces/SurfaceUi.kt:79`, and `requestAssistant` at `surfaces/SurfaceEntry.kt:24-27`
- Modify: `ui/AlphaShell.kt` (settings actions of `ChatPane` and `ToolsPane`, composer label and send resources, drawer profile name, Settings entry)
- Modify: `MainActivity.kt:26` (the extra `settings`)
- Modify: `surfaces/AssistantActivity.kt:69, 86, 121` (the `settings` action of `CompactAssistant`)
- Modify: `ui/VoiceControls.kt:123-134` (texts to resources)
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `T/ui/ShellRobot.kt`
- Test: `T/ui/SettingsConsentWorkflowTest.kt`
- Docs: `README.md:7, 13`, `docs/cloud-integration.md:11, 21, 29, 47`, `docs/shell-integration.md:9, 13, 19`

**Interfaces:**
- Consumes: `ShellDestination.Settings`, `SecureWindow` (Task 4), and the `profileName` parameter (Task 5).
- Produces:

```kotlin
// data/SecureKeyStore.kt
@Synchronized fun save(value: String): Result<Unit>   // ciphertext, iv, and consent = "1" in one commit
@Synchronized fun recordConsent(): Result<Unit>       // fails when no key is stored
@Synchronized fun consented(): Boolean
@Synchronized fun clear(): Result<Unit>               // removes ciphertext, iv, and consent in one commit
// cloud/CloudKey.kt
internal fun SecureKeyStore.cloudKey(context: Context): String  // CloudFailure codes: key_read, key_missing, key_format, consent
internal val KEY_ERRORS = setOf("key_read", "key_missing", "key_format", "consent", "auth")  // «Открыть настройки» fixes these
// ui/ShellViewModel.kt
enum class KeyStatus { Checking, Missing, NeedsConsent, Ready, Unreadable }
var keyStatus: KeyStatus; private set
var keyFailure: Int?; private set                     // @StringRes of the last failed key action
var surfaceSettings: Boolean; private set
fun openSurface(toolId: String?, settings: Boolean = false)
fun saveKey(value: String, done: (Boolean) -> Unit)
fun acceptConsent()
fun clearKey()                                        // cancels running cloud work, then deletes the key
fun setUserName(value: String)                        // keeps at most 40 characters
// tools/ToolController.kt
var errorCode: String?; private set                   // the CloudFailure code of error
// data/ShellStore.kt
ShellSnapshot.userName: String = ""                   // settings row "userName"
// ui/AlphaIcons.kt
@Composable internal fun AlphaIcon(glyph: Glyph, modifier: Modifier = Modifier, accent: Boolean = false,
    color: Color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
@Composable internal fun StatusLine(glyph: Glyph, color: Color, text: String, modifier: Modifier = Modifier)
// ui/SettingsPane.kt and capabilities/AndroidAccessUi.kt
@Composable internal fun SettingsPane(model: ShellViewModel)
@Composable fun AssistantSettingsRow()
// ui/AlphaShell.kt, tools/ToolUi.kt, and surfaces/AssistantActivity.kt
ChatPane(model, conversation, tools, context, automation, settings: () -> Unit)
ToolsPane(controller, conversationId, chatText, images, immersive, exitImmersive, settings: () -> Unit, close: () -> Unit)
CompactAssistant(model, invocation, full, settings: () -> Unit, dismiss)
```

- Produces in `ShellRobot`: `fun scrollForward(): Boolean`, `fun reveal(label: String): AccessibilityNodeInfo`, and `fun enter(label: String, value: String)`.

- [ ] **Step 1: Add the helpers**

- `scrollForward` performs `ACTION_SCROLL_FORWARD` on the first scrollable node of `diagnostics.freshRoot()`. Then it calls `automation.waitForIdle(100, 3000)`, because the scroll animates. It returns the action result.
- `reveal` calls `scrollForward` until `find(label)` gives a node, at most 10 times. Then it performs `ACTION_SHOW_ON_SCREEN` on the node, waits for idle, and returns `waitFor(label)`. So the full node is on the screen.
- `enter` sets the text with `ACTION_SET_TEXT` and waits for idle. It does not compare the text, because a password field shows dots.
- Assumption: Compose 1.12 scrolls the nearest scrollable ancestor for `ACTION_SHOW_ON_SCREEN`. Basis: `T/tools/ToolWorkflowTest.kt:263` uses this action already. Test: `settingsShowVoiceStart`.

- [ ] **Step 2: Write the failing test**

```kotlin
class SettingsConsentWorkflowTest {
    private val fixture = ShellFixture("settings-consent")
    private val robot = ShellRobot("settings-consent")
    private val preferences get() = fixture.context.getSharedPreferences(fixture.name, Context.MODE_PRIVATE)
    @After fun remove() = fixture.remove()

    private fun send(text: String) {
        robot.edit(robot.text(R.string.composer_label), text)
        robot.click(robot.text(R.string.composer_send))
    }
    private fun openSettings(scenario: ActivityScenario<*>) {
        robot.openDrawer(scenario)
        robot.click(robot.text(R.string.settings))
        robot.awaitDrawerClosed(scenario)
        robot.waitUntil { robot.secure(scenario) }
    }
    private fun saveKey() {
        robot.reveal(robot.text(R.string.key_label))
        robot.enter(robot.text(R.string.key_label), "alpha-test-only")
        robot.reveal(robot.text(R.string.consent_and_save))
        robot.click(robot.text(R.string.consent_and_save))
        robot.waitFor(robot.text(R.string.key_ready))
    }
    private fun dropConsent() = check(preferences.edit().remove("consent").commit())

    @Test fun keyEntryRecordsConsentAndEnablesCloud() {
        fixture.install(saveKey = false)
        fixture.launch().use { scenario ->
            send("Before key")
            robot.click(robot.text(R.string.open_settings))
            robot.waitUntil { robot.secure(scenario) }
            robot.waitFor(robot.text(R.string.key_missing))
            robot.waitFor(robot.text(R.string.consent_text))
            saveKey()
            assertTrue(fixture.keys.consented())
            assertEquals("alpha-test-only", fixture.keys.read().getOrThrow())
            assertEquals(0, fixture.provider.calls)
            robot.back()
            send("After key")
            robot.waitFor("Fixture response")
            assertEquals(1, fixture.provider.calls)
        }
    }

    @Test fun storedKeyWithoutConsentAsksOnce() {
        fixture.install()
        dropConsent()
        val ciphertext = preferences.getString("ciphertext", null)
        fixture.launch().use { scenario ->
            send("Stored key")
            robot.waitFor(robot.text(R.string.cloud_consent))
            robot.openDrawer(scenario)
            robot.click(robot.text(R.string.mini_apps))
            robot.awaitDrawerClosed(scenario)
            val tools = fixture.model!!.tools
            robot.waitUntil {
                var started = false
                // run() ignores a call while the controller is busy, so check and start in one main-thread step.
                robot.instrumentation.runOnMainSync { if (!tools.busy) { tools.generate("Счётчик", null, null, emptyList()); started = true } }
                started
            }
            robot.waitUntil { tools.errorCode == "consent" }
            assertEquals(0, fixture.provider.calls)
            robot.click(robot.text(R.string.open_settings))
            robot.waitUntil { robot.secure(scenario) }
            robot.waitFor(robot.text(R.string.key_needs_consent))
            robot.waitFor(robot.text(R.string.consent_text))
            robot.reveal(robot.text(R.string.consent_accept))
            robot.click(robot.text(R.string.consent_accept))
            robot.waitFor(robot.text(R.string.key_ready))
            assertEquals(ciphertext, preferences.getString("ciphertext", null))
            assertTrue(fixture.keys.consented())
            robot.back()
            send("After consent")
            robot.waitFor("Fixture response")
            assertEquals(1, fixture.provider.calls)
            send("Second request")
            robot.waitUntil { fixture.provider.calls == 2 }
        }
    }

    @Test fun settingsEntryShowsConsentForStoredKey() {
        fixture.install()
        dropConsent()
        fixture.launch().use { scenario ->
            robot.waitFor(robot.text(R.string.menu))
            robot.instrumentation.runOnMainSync { fixture.model!!.openSurface(null, settings = true) }
            robot.waitUntil { robot.secure(scenario) }
            robot.waitFor(robot.text(R.string.key_needs_consent))
            assertEquals(0, fixture.provider.calls)
        }
    }

    @Test fun rejectedKeyOpensSettings() {
        fixture.install()
        fixture.provider.failure = CloudFailure("auth", "Fixture rejected key")
        fixture.launch().use { scenario ->
            send("Rejected key")
            robot.waitFor("Fixture rejected key")
            robot.click(robot.text(R.string.open_settings))
            robot.waitUntil { robot.secure(scenario) }
            robot.waitFor(robot.text(R.string.key_ready))
        }
    }

    @Test fun deleteKeyStopsCloudUntilNewConsent() {
        fixture.install()
        fixture.launch().use { scenario ->
            openSettings(scenario)
            robot.waitFor(robot.text(R.string.key_ready))
            robot.waitFor(robot.text(R.string.consent_text))
            robot.reveal(robot.text(R.string.key_delete))
            robot.click(robot.text(R.string.key_delete))
            robot.waitFor(robot.text(R.string.key_delete_text))
            robot.click(robot.text(R.string.delete))
            robot.waitFor(robot.text(R.string.key_missing))
            assertNull(fixture.keys.read().getOrThrow())
            assertFalse(fixture.keys.consented())
            robot.back()
            send("Without key")
            robot.waitFor(robot.text(R.string.cloud_key_missing))
            assertEquals(0, fixture.provider.calls)
            robot.click(robot.text(R.string.open_settings))
            saveKey()
            robot.back()
            send("With key again")
            robot.waitFor("Fixture response")
        }
    }

    @Test fun deleteKeyStopsRunningRequest() {
        fixture.provider.gate = CompletableDeferred()
        fixture.install()
        fixture.launch().use { scenario ->
            send("Long request")
            robot.waitUntil { fixture.provider.calls == 1 }
            openSettings(scenario)
            robot.reveal(robot.text(R.string.key_delete))
            robot.click(robot.text(R.string.key_delete))
            robot.click(robot.text(R.string.delete))
            robot.waitFor(robot.text(R.string.key_missing))
            fixture.provider.gate!!.complete(Unit)
            robot.waitUntil { fixture.model!!.snapshot.selected?.messages?.lastOrNull()?.status == "cancelled" }
            robot.instrumentation.waitForIdleSync()
            assertNull(fixture.model!!.snapshot.selected?.messages?.find { it.text == "Fixture response" })
            assertEquals(1, fixture.provider.calls)
        }
    }

    @Test fun assistantLinkOpensSystemSettings() {
        fixture.install()
        val monitor = robot.instrumentation.addMonitor(IntentFilter(Settings.ACTION_VOICE_INPUT_SETTINGS), null, true)
        try {
            fixture.launch().use { scenario ->
                openSettings(scenario)
                robot.reveal(robot.text(R.string.assistant_pick_hint))
                robot.reveal(robot.text(R.string.assistant_open))
                robot.click(robot.text(R.string.assistant_open))
                robot.waitUntil { monitor.hits == 1 }
            }
        } finally { robot.instrumentation.removeMonitor(monitor) }
    }

    @Test fun nameSurvivesRecreation() {
        fixture.install()
        fixture.launch().use { scenario ->
            openSettings(scenario)
            robot.reveal(robot.text(R.string.settings_name_label))
            robot.edit(robot.text(R.string.settings_name_label), "Евгений")
            robot.waitUntil { fixture.store.load().userName == "Евгений" }
            scenario.recreate()
            robot.waitUntil { robot.secure(scenario) }
            robot.reveal(robot.text(R.string.settings_name_label))
            robot.waitUntil { robot.field(robot.text(R.string.settings_name_label)).text?.toString() == "Евгений" }
            robot.back()
            robot.waitUntil { !robot.secure(scenario) }
            robot.openDrawer(scenario)
            robot.waitFor("Евгений")
        }
    }

    @Test fun settingsShowVoiceStart() {
        fixture.install()
        fixture.launch().use { scenario ->
            openSettings(scenario)
            robot.reveal(robot.text(R.string.voice_start))
            robot.assertTarget(robot.text(R.string.voice_start))
        }
    }
}
```

The drawer check in `nameSurvivesRecreation` runs after Back, so the Settings name field cannot give the match.

- [ ] **Step 3: Run the test to see it fail**

Run: `python3 tools/compose_acceptance/run.py suite --label p1-t6-consent-red --expect build-failed com.alpha.assistant.ui.SettingsConsentWorkflowTest`
Expected: exit 0, and `build.log` names `Unresolved reference 'consented'`.

- [ ] **Step 4: Add the consent record to `SecureKeyStore`**

- `save` writes `consent = "1"` in the same `commit()` as `ciphertext` and `iv`. So the current `save` callers in tests stay unchanged.
- `recordConsent` fails when `ciphertext` or `iv` is absent.
- `clear` removes `ciphertext`, `iv`, and `consent` in one `commit()`.

- [ ] **Step 5: Guard every key read for a request**

- `cloudKey` throws `CloudFailure` with the text of Appendix C. It checks in this sequence:
  1. A failed `read()` gives `key_read`.
  2. No key gives `key_missing`.
  3. A whitespace or control character gives `key_format`.
  4. `!consented()` gives `consent`.
- `ShellViewModel.kt:335-341` becomes `val key = withContext(Dispatchers.IO) { keys.cloudKey(getApplication()) }`.
- `ToolController.kt:106` becomes `val key = keys.cloudKey(app)`. The `run` block then shows the `CloudFailure` text in `error`.

- [ ] **Step 6: Implement the key state and the user name in `ShellViewModel`**

- `refreshKey` maps a failed read to `Unreadable`, no key to `Missing`, a key with no consent to `NeedsConsent`, and other keys to `Ready`.
- `saveKey`, `acceptConsent`, and `clearKey` set `keyFailure = null` first. On failure, they set `key_save_failed`, `consent_failed`, or `key_delete_failed`. On success, they call `refreshKey`.
- `clearKey` calls `cancelRequest()` and `tools.cancel()` before `keys.clear()`. A running request keeps its key in memory, and spec 5.9 says "«Удалить ключ» stops all cloud functions."
- `setUserName` keeps the first 40 characters, updates `snapshot`, and writes the row `userName` through `writes`, as `select` does. `retrySave` also writes `userName`. `ShellStore.load` reads `settings["userName"] ?: ""`.
- The drawer gets `profileName = snapshot.userName.ifBlank { stringResource(R.string.profile) }`.

- [ ] **Step 7: Implement `SettingsPane`**

- Give `AlphaIcon` the `color` parameter of Interfaces. Add `StatusLine`: a `Row` with `AlphaIcon(glyph, color = color)` and `Text(text, color = color)`. The icon then keeps the contrast of its text on every container.
- The save banner at `ui/AlphaShell.kt:103-105` shows its text as a `StatusLine` with `Glyph.Close` and `onErrorContainer`.

The page has no heading, because the top bar shows «Настройки». It is one scrollable column with these parts in sequence:
1. The heading «DeepSeek» (`titleMedium`) and the status. The status is a `StatusLine`, so it shows an icon, a color, and text (DEAL rule): `Ready` uses `Glyph.Check` and `primary`, `Unreadable` uses `Glyph.Close` and `error`, and the other states use `Glyph.Cloud` and `onSurfaceVariant`.
2. The consent text (`bodyMedium`). It shows in every state.
3. For `Missing` and `Ready`: the key field. It is an `OutlinedTextField` with `PasswordVisualTransformation`, the label, the placeholder, and the supporting text of Appendix C. Its value uses `remember`, so recreation clears it.
4. The buttons. All buttons are disabled while `keyBusy`.

   | State | Buttons |
   | --- | --- |
   | `Missing` | `Button` «Согласиться и сохранить», enabled when the field is not blank |
   | `NeedsConsent` | `Button` «Согласиться», `TextButton` «Удалить ключ» |
   | `Ready` | `OutlinedButton` «Согласиться и сохранить», enabled when the field is not blank, and `TextButton` «Удалить ключ» |
   | `Unreadable` | `Button` «Удалить ключ» |
   | `Checking` | none |

5. The text of `keyFailure`, when it is not null, as a `StatusLine` with `Glyph.Close` and `error`.
6. A divider and the field «Ваше имя» (one line, supporting text «Alphy использует имя в приветствии.»). It shows `snapshot.userName` and calls `model.setUserName` on each change.
7. A divider, the heading «Голос», and `VoiceControls(model)`.
8. A divider, the heading «Ассистент», and `AssistantSettingsRow()`.
9. «Alphy · %1$s» with the `versionName` from `PackageManager`.

- Each text field has its label as `contentDescription`, as the search field does at `ui/AlphaShell.kt:346`.
- The delete dialog has the title «Удалить API-ключ?», the text of `key_delete_text`, «Удалить», and «Отмена».
- Delete the old notice about a confirmation before each send.
- `AssistantSettingsRow` is a `Column` with no card frame, so it fits the rows of the Access page (Task 9). It shows these items:
  - The title «Системный ассистент».
  - The status from `RoleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)`, as a `StatusLine`. Selected uses `Glyph.Check` and `primary`. Not selected uses `Glyph.Shield` and `onSurfaceVariant`. Read the status again on resume, as `AndroidAccessUi.kt:39` does.
  - The hint `assistant_pick_hint`, because spec 5.8 says "The text tells the user to pick Alphy there."
  - The `TextButton` «Открыть настройки ассистента», which starts `Settings.ACTION_VOICE_INPUT_SETTINGS`. On `ActivityNotFoundException`, show `assistant_open_failed` as a `StatusLine` with `Glyph.Close` and `error`.
  - The tile hint.
- Delete `SystemEntrySettings` and `requestAssistant`. The assistant role is not requestable (spec 3).
- Move the texts at `VoiceControls.kt:123-134` to resources, with no text change. The start button text gets the name `voice_start`.
- `VoiceControls` shows `voice.status` (`:131`) as a `StatusLine` with `Glyph.Mic` and `onSurfaceVariant`. Its buttons at `:133-134` become `FilledTonalButton`, so the key action stays the one primary action (spec 5.12).

- [ ] **Step 8: Give key errors a next step in the chat and the mini-apps**

- Use `composer_label` and `composer_send` in the composer of `ChatPane`.
- An assistant message with `errorCode` in `KEY_ERRORS` shows a `TextButton` «Открыть настройки». It calls `settings()`.
- `ToolController.run` sets `errorCode = (failure as? CloudFailure)?.code` in its `catch`. The start of `run` and `clearError` set `errorCode = null`. Each other assignment of `error` also sets `errorCode = null`: `ToolController.kt:72`, `:91-92`, and `:292`.
- The error rows at `ToolUi.kt:86` and `:184` show a `TextButton` «Открыть настройки» when `errorCode` is in `KEY_ERRORS`. It calls `settings()`. `ToolsPane` passes `settings` to `FocusedToolPane`.
- The shell passes `{ navigate(ShellDestination.Settings) }` to `ChatPane` and to both `ToolsPane` calls.
- The Settings entry for the half sheet:
  - `openSurface` sets `surfaceSettings = settings` before `surfaceSequence++`.
  - The entry effect sets `destination = when { model.surfaceSettings -> Settings; model.surfaceToolId != null -> MiniApps; else -> null }`.
  - `MainActivity.receiveEntry` calls `model.openSurface(intent.getStringExtra("tool_id"), intent.getBooleanExtra("settings", false))`.
  - `AssistantActivity.kt:69` passes `settings = { startActivity(SurfaceEntry.chatIntent(this@AssistantActivity).putExtra("settings", true)); finish() }`.
  - `AssistantActivity.kt:121` calls `ChatPane` with named arguments and passes `settings`. A trailing lambda would bind to `settings`, the last parameter.

- [ ] **Step 9: Run the tests to see them pass**

Run: `python3 tools/compose_acceptance/run.py suite --label p1-t6-consent com.alpha.assistant.ui.SettingsConsentWorkflowTest com.alpha.assistant.cloud.CloudWorkflowTest com.alpha.assistant.data.ShellStorageTest com.alpha.assistant.tools.ToolWorkflowTest`
Expected: exit 0. `CloudWorkflowTest.kt:805-808` still gets `key_missing` and `key_read`. `storedKeyWithoutConsentAsksOnce` checks the error row of the focused page at `tools/ToolUi.kt:184`, because `generate` selects its draft before the key read. No test shows a key error in the library row at `tools/ToolUi.kt:86`.

- [ ] **Step 10: Change the docs**

- `README.md:7`: the user gives consent one time, at key entry. «Настройки» show the consent text again.
- `README.md:13`: cloud requests send the content that the consent text names. «Удалить ключ» stops all cloud requests.
- `cloud-integration.md:11`: replace the two sentences about a confirmation before each send. Consent comes one time, at key entry. `cloudKey()` refuses a request without consent. The chat and the mini-app page then show «Открыть настройки».
- `cloud-integration.md:21`: replace the first sentence about the web-search checkbox. State the current search trigger in `ShellViewModel.send`, and state that the consent covers search queries.
- `cloud-integration.md:29`: delete "requires confirmation and" from the retry sentence.
- `cloud-integration.md:47`: replace "consent before transmission" with "consent at key entry".
- `shell-integration.md:9`: the `settings` table also stores `userName`.
- `shell-integration.md:13`: replace "the first confirmed submission" with "the first submission".
- `shell-integration.md:19`: a saved key writes the consent record in the same commit. A key without that record shows «Согласиться». «Удалить ключ» removes the key and the consent. `cloudKey()` is the only key read for requests.

- [ ] **Step 11: Run the full suite**

Label: `p1-t6-full`. Expected: exit 0.

- [ ] **Step 12: Review and commit**

```bash
git add app/src README.md docs/cloud-integration.md docs/shell-integration.md
git commit -m "Ask for DeepSeek consent once at key entry"
```

---

### Task 7: Desktop and shared composer

**Files:**
- Create: `ui/Composer.kt` (from `ui/AlphaShell.kt:261-296` and `RoundAction` at `:311-315`)
- Create: `ui/DesktopPane.kt`
- Modify: `ui/AlphaShell.kt` (Desktop entry, shell composer, `ChatPane` without its composer and empty state)
- Delete: `StartCard` at `ui/AlphaShell.kt:301`
- Modify: `ui/VoiceControls.kt:41` and `:79-118` (texts, and the glyphs at `:116` and `:118`), `ui/ShellViewModel.kt:41`, and `voice/VoiceSession.kt` (status texts)
- Modify: `ui/AlphaIcons.kt` (glyph `Stop`)
- Modify: `surfaces/AssistantActivity.kt:121`
- Modify: `T/tools/LibraryManagementWorkflowTest.kt:146, 156, 158`, `T/tools/ToolWorkflowTest.kt:293`
- Test: `T/ui/DesktopWorkflowTest.kt`
- Docs: `docs/shell-integration.md:23`

**Interfaces:**
- Consumes: `KeyStatus` (Task 6). `ShellDestination` and `navigate` (Task 4).
- Produces:

```kotlin
@Composable internal fun Composer(model: ShellViewModel, context: () -> Unit, automation: (String) -> Unit, modifier: Modifier = Modifier)
@Composable internal fun DesktopPane(model: ShellViewModel, open: (ShellDestination) -> Unit, automation: (String) -> Unit)
@StringRes internal fun greetingRes(hour: Int): Int
internal const val MY_DAY_REQUEST = "Обновляй Мой день из календаря каждый день в 08:00"
@Composable internal fun ChatPane(model: ShellViewModel, conversation: Conversation?, tools: () -> Unit, automation: (String) -> Unit,
    settings: () -> Unit, modifier: Modifier = Modifier)
```

- [ ] **Step 1: Write the failing test**

```kotlin
class DesktopWorkflowTest {
    private val fixture = ShellFixture("desktop")
    private val robot = ShellRobot("desktop")
    @After fun remove() = fixture.remove()

    // The hour can change between composition and the check.
    private fun greetings(name: String) = (-1..1)
        .map { greetingRes((LocalTime.now().hour + it + 24) % 24) }
        .map { robot.text(R.string.greeting_named, robot.text(it), name) }

    @Test fun desktopGreetsByNameAndSuggestsLocally() {
        fixture.install(saveKey = false)
        fixture.store.saveSetting("userName", "Евгений")
        fixture.launch().use { scenario ->
            robot.waitUntil { greetings("Евгений").any { robot.find(it) != null } }
            robot.waitFor(robot.text(R.string.desktop_prompt))
            robot.waitFor(robot.text(R.string.now_title))
            robot.click(robot.text(R.string.now_connect_title))
            robot.waitUntil { robot.secure(scenario) }
            robot.back()
            robot.click(robot.text(R.string.now_day_title))
            robot.waitFor(robot.text(R.string.automations))
            assertEquals(0, fixture.provider.calls)
        }
    }

    @Test fun desktopSendOpensChatAndBackReturns() {
        fixture.install()
        fixture.launch().use {
            robot.waitFor(robot.text(R.string.now_mini_app_title))
            robot.assertTarget(robot.text(R.string.composer_label))
            robot.instrumentation.waitForIdleSync()
            assertEquals(0, fixture.provider.calls)
            robot.click(robot.text(R.string.now_day_title))
            robot.waitFor(robot.text(R.string.automations))
            robot.back()
            robot.waitFor(robot.text(R.string.desktop_prompt))
            assertEquals(0, fixture.provider.calls)
            robot.edit(robot.text(R.string.composer_label), "Desktop question")
            robot.click(robot.text(R.string.composer_send))
            robot.waitFor("Fixture response")
            assertEquals(1, fixture.provider.calls)
            robot.back()
            robot.waitGone("Fixture response")
            robot.waitFor(robot.text(R.string.desktop_prompt))
        }
    }

    @Test fun largeTextKeepsShellUsable() {
        val scale = robot.shell("settings get system font_scale")
        try {
            robot.shell("settings put system font_scale 2.0")
            robot.waitUntil { Resources.getSystem().configuration.fontScale == 2f }
            fixture.install()
            val name = "Александра-Валентина Константиновна"
            fixture.store.saveSetting("userName", name)
            val title = "Очень длинное название разговора, которое не помещается в одну строку меню"
            fixture.seedChat("long", title, "Long chat message")
            fixture.launch().use { scenario ->
                scenario.onActivity { assertEquals(2f, it.resources.configuration.fontScale) }
                for (id in listOf(R.string.menu, R.string.new_chat, R.string.composer_label, R.string.composer_send))
                    robot.assertTarget(robot.text(id))
                robot.waitUntil { greetings(name).any { robot.find(it) != null } }
                robot.openDrawer(scenario)
                robot.assertTarget(robot.text(R.string.settings))
                robot.click(title)
                robot.awaitDrawerClosed(scenario)
                robot.waitFor("Long chat message")
                try {
                    robot.rotate(scenario, landscape = true)
                    robot.openDrawer(scenario)
                    robot.assertTarget(robot.text(R.string.settings))
                } finally { robot.rotate(scenario, landscape = false) }
            }
        } finally {
            // "settings get" prints "null" when the value is unset, and "settings put … null" does not restore that state.
            robot.shell(if (scale.toFloatOrNull() == null) "settings delete system font_scale" else "settings put system font_scale $scale")
            assertEquals(scale, robot.shell("settings get system font_scale"))
        }
    }
}
```

- [ ] **Step 2: Change the older tests**

`LibraryManagementWorkflowTest.kt:146` and `:158`, and `ToolWorkflowTest.kt:293`: replace `click("Создать")` with these calls:
- `ShellRobot("<the fixture name of the class>").openDrawer(scenario)`
- the class's own `click` on `app.getString(R.string.mini_apps)`
- `awaitDrawerClosed(scenario)` of the same robot

Also make these changes:
- Add `import com.alpha.assistant.R` and `import com.alpha.assistant.ui.ShellRobot` to both classes.
- Change `LibraryManagementWorkflowTest.kt:156` to `.use { scenario ->`, because its lambda has no `scenario` today. `ToolWorkflowTest.kt:239` already has `scenario`.

- [ ] **Step 3: Run the test to see it fail**

Run: `python3 tools/compose_acceptance/run.py suite --label p1-t7-desktop-red --expect build-failed com.alpha.assistant.ui.DesktopWorkflowTest`
Expected: exit 0, and `build.log` names `Unresolved reference 'greetingRes'`.

- [ ] **Step 4: Extract the composer**

- Move lines 261-296 into `Composer`, with the same behavior. Keep `rememberVoiceStart(model, oneShot = true)` in `Composer`.
- Use the placeholder «Спросите что-нибудь».
- `RoundAction` draws its glyph in every case, so the send button shows `Glyph.Arrow` in place of `Text("↑")`. `RoundAction` uses `Modifier.size(48.dp)`.
- The glyph uses `AlphaIcon(glyph, color = LocalContentColor.current)`. The default `onSurfaceVariant` has a contrast of 1.46:1 on `primary` (Appendix A).
- The text field node is at least 48 dp high (spec 9). Today `:281` puts the vertical padding before the semantics, so a one-line field gives a node of 33 dp. Use `heightIn(min = 48.dp, max = 150.dp)` before the semantics, and put the vertical space in the `decorationBox`.
- Move the composer texts to resources (Appendix C). The automation action at `:275` changes to `composer_automation`, because the spec glossary gives the UI text «автоматизация».
- Move the two `ImagePreview` texts at `ui/AlphaShell.kt:439-440` to resources, with no text change. The composer shows them.
- Show `voice.status` at `:263` and `:274` as a `StatusLine` with `Glyph.Mic` and `onSurfaceVariant`, as `VoiceControls` does since Task 6. Show «Проверка изображения…» at `:276` as a `StatusLine` with `Glyph.File` and `onSurfaceVariant`. `StatusLine` has no style parameter, so keep the current style of each line with `ProvideTextStyle`.

- [ ] **Step 5: Implement `DesktopPane`**

- One scrollable column with a maximum width of 620 dp, centered.
- `greetingRes`: 5 to 11 gives `greeting_morning`, 12 to 17 gives `greeting_day`, 18 to 22 gives `greeting_evening`, and other hours give `greeting_night`.
- The greeting uses `headlineMedium`. With a name that is not blank, it is `greeting_named` with the greeting and `userName.trim()`. Otherwise, it is the greeting alone. The hour comes from `LocalTime.now().hour`.
- Below it: «Чем помочь?» in `onSurfaceVariant`.
- The heading «Сейчас» (`titleMedium`) and two cards. Each card has these properties:
  - A `Card` with the `secondaryContainer` color, an `AlphaIcon`, a title (`titleMedium`), and one line of text (`bodyMedium`).
  - The full card is one click target with a minimum height of 72 dp.
- The cards:

  | `keyStatus` | First card | Second card |
  | --- | --- | --- |
  | Not `Ready` | «Подключите DeepSeek» (`Glyph.Cloud`), opens `Settings` | «Мой день» (`Glyph.Calendar`), calls `automation(MY_DAY_REQUEST)` |
  | `Ready` | «Мини-приложение» (`Glyph.Grid`), opens `MiniApps` | «Мой день», the same action |

- The strip reads only `keyStatus`. It never calls the provider.

- [ ] **Step 6: Put one composer in the shell**

- The shell column is the top bar, the save banner, the `BoxWithConstraints(Modifier.fillMaxWidth().weight(1f))` of Task 4, and `Composer` when the last route is `Desktop` or `Chat`. The box keeps `split`, the voice rule, and `NavDisplay`, because `split` reads `maxWidth`.
- The `Composer` callbacks: `context` sets `contextSheet = true`. `automation` sets `automationRequest` and calls `navigate(ShellDestination.Automations)`.
- The Desktop entry shows `DesktopPane`. The Chat entry shows `ChatPane`, which now shows the messages only.
- In the 640 dp mini-app split, the chat column is `ChatPane(…, Modifier.weight(1f))` with a `Composer` below it.
- Keep the voice rule of Task 4. The Desktop and Chat routes both show the shell composer, so a continuous voice session survives the change from Desktop to Chat.
- Delete `StartCard` and the empty state of `ChatPane`.
- `AssistantActivity.kt:121` shows `ChatPane(model, model.snapshot.selected, tools = full, automation = { full() }, settings = settings, modifier = Modifier.weight(1f))`. Below it, add `Composer(model, context = { sheet = "context" }, automation = { full() })`. `VoiceVisibility` at `AssistantActivity.kt:96` still stops a session when the half sheet closes.

- [ ] **Step 7: Change the voice texts and glyphs**

- `VoiceControls.kt:100`: «Только текст и выбранные вложения отправятся после кнопки «Отправить».»
- `:116`: `AlphaIcon(Glyph.Stop, color = LocalContentColor.current)` in place of `Text("■")`, so the disabled `IconButton` keeps its disabled color. `:118`: `AlphaIcon(Glyph.Arrow, color = LocalContentColor.current)` in place of `Text("↑")`, because the `FilledIconButton` has the `primary` container.
- `:93`: show `voice.status` as a `StatusLine` with `Glyph.Mic` and `onSurfaceVariant`. Keep `labelSmall` with `ProvideTextStyle`.
- Move the other texts at `:79-118` to resources, with no text change.
- Move the voice status texts to resources, with no text change: the Russian texts of `voice/VoiceSession.kt`, `ui/ShellViewModel.kt:41`, and `ui/VoiceControls.kt:41`. `VoiceSession` reads them through its `context`, and `ShellViewModel` through its `application`. The composer and «Настройки» show them as `voice.status`.
- Add `Stop` (a square) to `Glyph`.

- [ ] **Step 8: Run the tests to see them pass**

Run: `python3 tools/compose_acceptance/run.py suite --label p1-t7-desktop com.alpha.assistant.ui.DesktopWorkflowTest com.alpha.assistant.ui.ShellWorkflowTest com.alpha.assistant.ui.ShellVoiceWorkflowTest com.alpha.assistant.voice.InlineVoiceWorkflowTest com.alpha.assistant.voice.LocalVoiceWorkflowTest com.alpha.assistant.tools.LibraryManagementWorkflowTest com.alpha.assistant.tools.ToolWorkflowTest`
Expected: exit 0. `ShellWorkflowTest` checks the keyboard and the composer insets.

- [ ] **Step 9: Change the docs**

`shell-integration.md:23`: the desktop is the home screen. It shows a greeting and «Сейчас» with local start suggestions. A sent message opens the chat. Back and «Новый чат» return to the desktop.

- [ ] **Step 10: Run the full suite**

Label: `p1-t7-full`. Expected: exit 0.

- [ ] **Step 11: Review and commit**

```bash
git add app/src docs/shell-integration.md
git commit -m "Add the desktop home screen and one shell composer"
```

---

### Task 8: Search page

**Files:**
- Create: `ui/SearchPane.kt` (from `HistoryPane` at `ui/AlphaShell.kt:337-383`)
- Modify: `ui/AlphaShell.kt` (Search entry)
- Delete: `HistoryPane` at `ui/AlphaShell.kt:337-383`
- Modify: `ui/AlphaIcons.kt` (glyph `More`)
- Modify: `app/src/main/res/values/strings.xml`
- Test: `T/ui/SearchWorkflowTest.kt`

**Interfaces:**
- Produces: `@Composable internal fun SearchPane(conversations: List<Conversation>, search: String, edit: (String) -> Unit, select: (String) -> Unit, rename: (String, String) -> Unit, delete: (String) -> Unit, clear: () -> Unit)`

- [ ] **Step 1: Write the failing test**

```kotlin
class SearchWorkflowTest {
    private val fixture = ShellFixture("search")
    private val robot = ShellRobot("search")
    @After fun remove() = fixture.remove()

    @Test fun searchShowsRussianDatesAndPoliteTexts() {
        fixture.install()
        val updated = 1_793_000_000_000L
        fixture.seedChat("dated", "Dated chat", "Dated message", updatedAt = updated)
        fixture.launch().use { scenario ->
            robot.openDrawer(scenario)
            robot.click(robot.text(R.string.search))
            robot.awaitDrawerClosed(scenario)
            val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.forLanguageTag("ru")).format(Date(updated))
            robot.waitFor(robot.text(R.string.search_row_saved, date))
            robot.assertTarget(robot.text(R.string.conversation_actions, "Dated chat"))
            assertNull(robot.find("⋯"))
            robot.edit(robot.text(R.string.search_field), "нет такого")
            robot.waitFor(robot.text(R.string.search_none))
        }
    }
}
```

The test and `SearchPane` both use `import java.text.DateFormat`, as `ui/AlphaShell.kt:52` does. `android.icu.text.DateFormat` puts a comma between the Russian date and time, so a mix of the two classes fails the match.

- [ ] **Step 2: Run the test to see it fail**

Run: `python3 tools/compose_acceptance/run.py suite --label p1-t8-search-red --expect build-failed com.alpha.assistant.ui.SearchWorkflowTest`
Expected: exit 0, and `build.log` names `Unresolved reference 'search_row_saved'`.

- [ ] **Step 3: Implement `SearchPane`**

It starts from `HistoryPane`, with these changes:
- Delete the heading row «Разговоры» and its «Новый чат» button. The top bar has both.
- Dates use `java.text.DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, LocalConfiguration.current.locales[0])`.
- The row subtitle uses `search_row_draft` or `search_row_saved`.
- The action button draws `Glyph.More` in place of `Text("⋯")`. It has `Modifier.size(48.dp)` and the label `conversation_actions`.
- Use the polite texts of Appendix C. Move the other texts to resources, with no text change.
- `rename_placeholder` replaces the supporting text «Введи название» that the rename field shows for a blank title (`ui/AlphaShell.kt:371`). The rename field gets no placeholder.
- Rows keep their dividers (DEAL rule).
- Add `More` (three dots) to `Glyph`.

- [ ] **Step 4: Run the tests to see them pass**

Run: `python3 tools/compose_acceptance/run.py suite --label p1-t8-search com.alpha.assistant.ui.SearchWorkflowTest com.alpha.assistant.ui.HistoryManagementWorkflowTest`
Expected: exit 0. `HistoryManagementWorkflowTest.kt:65` still finds the unchanged clear-history text.

- [ ] **Step 5: Run the full suite**

Label: `p1-t8-full`. Expected: exit 0.

- [ ] **Step 6: Review and commit**

```bash
git add app/src
git commit -m "Move history into the search page"
```

---

### Task 9: Access page

**Files:**
- Modify: `capabilities/AndroidAccessUi.kt` (new `AccessPane`, rows and status lines at `:57-79`, `:107-119`, and `:140-145`, the overview heading at `:103`, the assistant card at `:133-135`, texts in resources)
- Delete: `CapabilitySettings` at `capabilities/CapabilityUi.kt:201` and the functions that only it calls
- Delete: `CapabilitiesPane` at `ui/AlphaShell.kt:385-391`
- Modify: `capabilities/AndroidCapabilities.kt:74`
- Modify: `ui/AlphaShell.kt` (Access entry)
- Modify: `surfaces/AssistantActivity.kt:157` (heading), `:172` (the label of the «Разрешения Android» row)
- Modify: `T/ui/ShellRobot.kt`
- Test: `T/ui/AccessPageWorkflowTest.kt`

**Interfaces:**
- Consumes: `AssistantSettingsRow` (Task 6).
- Produces: `@Composable fun AccessPane()`.
- Produces in `ShellRobot`: `fun texts(): List<String>`, which gives the `text` and `contentDescription` values of all nodes in the active window. It starts from `diagnostics.freshRoot()`.

- [ ] **Step 1: Write the failing test and the helper**

- Add `texts()` to `ShellRobot`, as Interfaces states.

```kotlin
class AccessPageWorkflowTest {
    private val fixture = ShellFixture("access")
    private val robot = ShellRobot("access")
    @After fun remove() = fixture.remove()

    @Test fun accessPageShowsPlainWordsOnly() {
        fixture.install()
        val filter = IntentFilter(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { addDataScheme("package") }
        val monitor = robot.instrumentation.addMonitor(filter, null, true)
        try {
            fixture.launch().use { scenario ->
                robot.openDrawer(scenario)
                robot.click(robot.text(R.string.access))
                robot.awaitDrawerClosed(scenario)
                val shown = mutableSetOf<String>()
                var end = false
                repeat(15) { if (!end) { shown += robot.texts(); end = !robot.scrollForward() } }
                assertTrue("The access page ends within 15 scrolls", end)
                assertTrue(robot.text(R.string.access_note) in shown)
                val technical = Regex("""[a-z]+\.[a-z_.]+|Android API|API \d+|android\.permission|Реализовано|Запланировано""")
                assertEquals(emptyList<String>(), shown.filter { technical.containsMatchIn(it) })
                robot.click(robot.text(R.string.access_system_settings))
                robot.waitUntil { monitor.hits == 1 }
            }
        } finally { robot.instrumentation.removeMonitor(monitor) }
    }
}
```

- [ ] **Step 2: Run the test to see it fail**

Run: `python3 tools/compose_acceptance/run.py suite --label p1-t9-access-red --expect build-failed com.alpha.assistant.ui.AccessPageWorkflowTest`
Expected: exit 0, and `build.log` names `Unresolved reference 'access_note'`.

- [ ] **Step 3: Implement `AccessPane`**

- One scrollable column with these parts:
  - `AndroidAccessOverview()`
  - the note `access_note`
  - an `OutlinedButton` «Открыть системные настройки Alphy» that starts `Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))`
- `AndroidAccessOverview` loses its heading at `:103`, because the top bar shows the title. Its assistant card at `:133-135` becomes `AssistantSettingsRow()`, so no code requests the role.
- Each access item is a row, because spec 5.12 says "Lists are rows with dividers." `AndroidPermissionControl`, the background location card at `:107`, and `AccessSettingCard` replace `OutlinedCard` with a `Column` and a `HorizontalDivider` below it. Rename `AccessSettingCard` to `AccessSettingRow`.
- Each status is a `StatusLine` (Task 6), because spec 5.12 says "A status shows with color, icon, and text together.":
  - Granted uses `Glyph.Check` and `primary`.
  - Denied or blocked uses `Glyph.Close` and `error`.
  - Each other state uses `Glyph.Shield` and `onSurfaceVariant`, for example partial access, no hardware, or not asked yet.
  - `AccessSettingRow` gets the parameter `granted: Boolean` for this mapping. Its items have no denied state.
- `AndroidPermissionControl` also serves `ui/ReviewedContextUi.kt:98` and `:144`, the camera and calendar pages of «Добавить контекст». No device test opens these two pages. `AccessPageWorkflowTest` reads the same control in the «Камера» row of the Access page. Task 10 Step 8 reports the two pages as unchecked.
- Move all overview texts to resources. Only `access_listener_title` (`:130`) and `access_scope_note` (`:136`) change, because the spec glossary gives the UI text «автоматизация».
- The Access entry shows `AccessPane()`.
- Delete `CapabilitySettings`, the functions that only it calls, and `CapabilitiesPane`. The overview keeps these settings: the microphone, the location, the calendar read, the camera, the notifications, the exact alarms, the notification listener, and the assistant. Each mini-app keeps its own grants (`tools/ToolUi.kt:215`).
- `AndroidCapabilities.kt:74` uses the resource `capability_permission_required`.
- `AssistantActivity.kt:157`: put a heading `R.string.access` (`headlineSmall`) above `AndroidAccessOverview()`. `:172`: the «Разрешения Android» row, which opens the `"permissions"` sheet, gets the label `R.string.access`.

- [ ] **Step 4: Run the tests to see them pass**

Run: `python3 tools/compose_acceptance/run.py suite --label p1-t9-access com.alpha.assistant.ui.AccessPageWorkflowTest com.alpha.assistant.ui.ReviewedContextUiWorkflowTest com.alpha.assistant.capabilities.CapabilityWorkflowTest`
Expected: exit 0.

- [ ] **Step 5: Run the full suite**

Label: `p1-t9-full`. Expected: exit 0.

- [ ] **Step 6: Review and commit**

```bash
git add app/src
git commit -m "Show Android access in plain words"
```

---

### Task 10: Screens, lint, and acceptance

**Files:**
- Test: `T/ui/ShellScreenshotTest.kt`
- Modify: only the files that a found defect needs

**Interfaces:**
- Consumes: all earlier tasks.
- Produces: the evidence directory of the last screen run (`p1-t10-screens`, or `p1-t10-screens-r<n>` after a fix) with the screenshots, the node lists, and `review.md`.

- [ ] **Step 1: Write the screen test**

`captureShellScreens()` is the visual check method for `FLAG_SECURE` screens (spec 9):
- First, read the night mode (the "Night mode: X" line of `cmd uimode night`) and the font scale with `robot.shell`.
- For each night mode (`cmd uimode night no` and `yes`) and each font scale (1.0 and 2.0), set the device state with `robot.shell`. Wait for the font scale as `largeTextKeepsShellUsable` does (Task 7).
- Then launch with a seeded chat that is not selected. Before each launch, call `fixture.store.saveSetting("selected", "")`, because the Chat capture selects the chat and the next launch restores it. Capture six screens: Desktop, the open drawer, Chat, Search, Access, and Settings. Wait for `R.string.desktop_prompt` before the Desktop capture.
- Each capture saves `automation.takeScreenshot()` as a PNG and the list from `robot.texts()`. The location is `getExternalFilesDir("alphy-ui-screens")/<mode>-<scale>/<screen>`. The test deletes `alphy-ui-screens` before the first capture, so a pull never holds an image of an earlier run.
- Assert that the center pixel of each Settings screenshot is black (`FLAG_SECURE`). Assert that the center pixel of each Desktop screenshot is not black.
- For Settings, also draw `window.decorView` into a `Bitmap` with `View.draw(Canvas(bitmap))` in `scenario.onActivity`. Draw the top of the page, then draw again after each `scrollForward()` until it returns false.
- Save each drawn image with its node list as `settings-<n>`. Assert that each drawn image has at least 16 distinct pixel colors. The window background alone gives one or two colors. The images hold only fixture data.
- Assumption: `View.draw` on a software canvas draws the Compose content, and `FLAG_SECURE` does not block it. Basis: the flag protects the window surface from capture, and the drawn bitmap stays in the app process. Test: the distinct-color assertion.
- In `finally`, restore the night mode with `cmd uimode night X`. Restore the font scale with the rule of `largeTextKeepsShellUsable`.

- [ ] **Step 2: Run the screen test**

Run: `python3 tools/compose_acceptance/run.py suite --label p1-t10-screens com.alpha.assistant.ui.ShellScreenshotTest`
Expected: exit 0.

- [ ] **Step 3: Pull and examine the screens**

Run: `/opt/homebrew/share/android-commandlinetools/platform-tools/adb -s emulator-5556 pull /sdcard/Android/data/com.alpha.assistant/files/alphy-ui-screens evidence/alphy-ui/p1-t10-screens/`
Examine each image and each node list. For Settings, examine the drawn `settings-<n>` images and their node lists, because the screenshot is black. Record each defect in `evidence/alphy-ui/p1-t10-screens/review.md`. Fix each defect in the code of the task that owns it, with a test when the defect is behavior. After a fix, run Steps 2 and 3 again with the label `p1-t10-screens-r2`, then `-r3`, and so on. Pull into the directory of that label, and record the check of each fix in its `review.md`. Step 8 names the last screen label.

- [ ] **Step 4: Run lint**

Run: `./gradlew :app:lintDebug --console=plain > evidence/alphy-ui/p1-t10-lint.txt 2>&1` (with the environment prefix)
Expected: exit 0 and 0 errors. Record the warning count at the end of `p1-t10-lint.txt`. The last count before plan 1 was 81.

- [ ] **Step 5: Run the host tests**

Run: `python3 tools/compose_acceptance/run.py host --label p1-t10-host`, with `--expect failed` for Option B of Unresolved decision 2.
Expected: exit 0. With Option B, `stdout.log` names only `test_coverage_preserves_pinned_reference_and_current_sources`.

- [ ] **Step 6: Run the final full suite**

Label: `p1-t10-full`. Expected: exit 0.

- [ ] **Step 7: Review and commit**

```bash
git add app/src
git commit -m "Capture the shell screens in light, dark, and large text"
```

- [ ] **Step 8: Report to the user**

Give the evidence paths, the failures that stay from the baseline, and the lint counts. Also give the checks that plan 1 did not run:
- the fixed palette on an API 29 or 30 device
- reduced motion
- the project device OPPO CPH2765
- predictive back on Android 13 to 15 (API 33 to 35)
- the «Открыть настройки» path of the half sheet through `MainActivity`. A device test needs a debug-only model factory for `AssistantChat`, because `AssistantChat` reads the production key store.
- the visual check of the half sheet, for the same reason (plan 9)
- Silero speech and the microphone that opens again after a reply. The fake models in `ShellVoiceWorkflowTest` have no synthesizer.
- the camera and calendar pages of «Добавить контекст», which show the `AndroidPermissionControl` rows of Task 9. No device test opens them.

---

## Appendix A: Fixed palette

The TonalSpot scheme from the seed `#7652DF` (SPEC_2021, contrast 0). The roles are in the parameter order of `lightColorScheme` and `darkColorScheme`. Each value is an `0xAARRGGBB` color.

| Role | Light | Dark |
| --- | --- | --- |
| primary | FF63568F | FFCDBDFF |
| onPrimary | FFFFFFFF | FF34275E |
| primaryContainer | FFE8DEFF | FF4B3E76 |
| onPrimaryContainer | FF4B3E76 | FFE8DEFF |
| inversePrimary | FFCDBDFF | FF63568F |
| secondary | FF615B71 | FFCBC3DC |
| onSecondary | FFFFFFFF | FF322E41 |
| secondaryContainer | FFE7DEF8 | FF494458 |
| onSecondaryContainer | FF494458 | FFE7DEF8 |
| tertiary | FF7D5261 | FFEEB8C9 |
| onTertiary | FFFFFFFF | FF492533 |
| tertiaryContainer | FFFFD9E4 | FF633B49 |
| onTertiaryContainer | FF633B49 | FFFFD9E4 |
| background | FFFDF7FF | FF141318 |
| onBackground | FF1C1B20 | FFE6E1E9 |
| surface | FFFDF7FF | FF141318 |
| onSurface | FF1C1B20 | FFE6E1E9 |
| surfaceVariant | FFE6E0EC | FF48454E |
| onSurfaceVariant | FF48454E | FFCAC4CF |
| surfaceTint | FF63568F | FFCDBDFF |
| inverseSurface | FF322F35 | FFE6E1E9 |
| inverseOnSurface | FFF4EFF7 | FF322F35 |
| error | FFBA1A1A | FFFFB4AB |
| onError | FFFFFFFF | FF690005 |
| errorContainer | FFFFDAD6 | FF93000A |
| onErrorContainer | FF93000A | FFFFDAD6 |
| outline | FF79757F | FF938F99 |
| outlineVariant | FFCAC4CF | FF48454E |
| scrim | FF000000 | FF000000 |
| surfaceBright | FFFDF7FF | FF3A383E |
| surfaceContainer | FFF2ECF4 | FF201F24 |
| surfaceContainerHigh | FFECE6EE | FF2B292F |
| surfaceContainerHighest | FFE6E1E9 | FF36343A |
| surfaceContainerLow | FFF7F2FA | FF1C1B20 |
| surfaceContainerLowest | FFFFFFFF | FF0F0D13 |
| surfaceDim | FFDDD8E0 | FF141318 |
| primaryFixed | FFE8DEFF | FFE8DEFF |
| primaryFixedDim | FFCDBDFF | FFCDBDFF |
| onPrimaryFixed | FF1F1048 | FF1F1048 |
| onPrimaryFixedVariant | FF4B3E76 | FF4B3E76 |
| secondaryFixed | FFE7DEF8 | FFE7DEF8 |
| secondaryFixedDim | FFCBC3DC | FFCBC3DC |
| onSecondaryFixed | FF1D192B | FF1D192B |
| onSecondaryFixedVariant | FF494458 | FF494458 |
| tertiaryFixed | FFFFD9E4 | FFFFD9E4 |
| tertiaryFixedDim | FFEEB8C9 | FFEEB8C9 |
| onTertiaryFixed | FF31111E | FF31111E |
| onTertiaryFixedVariant | FF633B49 | FF633B49 |

The fixed schemes give this contrast for the 12 test pairs:

| Pair | Light | Dark |
| --- | --- | --- |
| onSurface / surface | 16.24 | 14.36 |
| onSurfaceVariant / surface | 8.91 | 10.83 |
| onSurfaceVariant / surfaceContainer | 8.08 | 9.59 |
| onSurfaceVariant / surfaceContainerHigh | 7.65 | 8.42 |
| onPrimary / primary | 6.45 | 7.71 |
| onPrimaryContainer / primaryContainer | 7.26 | 7.26 |
| onSecondaryContainer / secondaryContainer | 7.20 | 7.20 |
| onTertiaryContainer / tertiaryContainer | 7.24 | 7.24 |
| onError / error | 6.46 | 7.72 |
| onErrorContainer / errorContainer | 7.24 | 7.24 |
| primary / surface | 6.12 | 10.84 |
| inverseOnSurface / inverseSurface | 11.63 | 10.24 |

## Appendix B: Base state when this plan was written

- Time: 2026-11-03 15:13:16 MSK.
- `HEAD`: `62179af217478075a1d7a10960c949440dcc4ad8` on `main`.
- Working tree: 55 modified paths and 37 untracked paths from the other session, and `?? docs/superpowers/` (this design and its plans). Plan 1 builds on them, for example the immersive hunks in `ui/AlphaShell.kt` and `tools/compose_acceptance/`. The output of `git status --short` at that time:

```text
 M app/src/androidTest/java/com/alpha/assistant/automations/AutomationRepairWorkflowTest.kt
 M app/src/androidTest/java/com/alpha/assistant/capabilities/CapabilityResultsWorkflowTest.kt
 M app/src/androidTest/java/com/alpha/assistant/capabilities/CapabilityWorkflowTest.kt
 M app/src/androidTest/java/com/alpha/assistant/capabilities/MedicineWorkflowTest.kt
 M app/src/androidTest/java/com/alpha/assistant/capabilities/NavigationAuthorizationWorkflowTest.kt
 M app/src/androidTest/java/com/alpha/assistant/capabilities/NavigationWorkflowTest.kt
 M app/src/androidTest/java/com/alpha/assistant/capabilities/OwnedCalendarAuthorizationWorkflowTest.kt
 M app/src/androidTest/java/com/alpha/assistant/capabilities/OwnedCalendarBulkWorkflowTest.kt
 M app/src/androidTest/java/com/alpha/assistant/capabilities/OwnedCalendarProviderWorkflowTest.kt
 M app/src/androidTest/java/com/alpha/assistant/capabilities/OwnedCalendarWorkflowTest.kt
 M app/src/androidTest/java/com/alpha/assistant/chat/AutomaticToolWorkflowTest.kt
 M app/src/androidTest/java/com/alpha/assistant/cloud/CloudWorkflowTest.kt
 M app/src/androidTest/java/com/alpha/assistant/sandbox/AndroidXRuntimeProbeTest.kt
 M app/src/androidTest/java/com/alpha/assistant/sandbox/SandboxIsolationTest.kt
 M app/src/androidTest/java/com/alpha/assistant/surfaces/SurfaceWorkflowTest.kt
 M app/src/androidTest/java/com/alpha/assistant/tools/GraphicsWorkflowTest.kt
 M app/src/androidTest/java/com/alpha/assistant/tools/LibraryManagementWorkflowTest.kt
 M app/src/androidTest/java/com/alpha/assistant/tools/MiniAppPresentationWorkflowTest.kt
 M app/src/androidTest/java/com/alpha/assistant/tools/NativeImageLifecycleTest.kt
 M app/src/androidTest/java/com/alpha/assistant/tools/NativeUiWorkflowTest.kt
 M app/src/androidTest/java/com/alpha/assistant/tools/StructuredStateWorkflowTest.kt
 M app/src/androidTest/java/com/alpha/assistant/tools/SyntheticSchemaDiagnosticTest.kt
 M app/src/androidTest/java/com/alpha/assistant/tools/ToolWorkflowTest.kt
 M app/src/androidTest/java/com/alpha/assistant/ui/ReviewedContextUiWorkflowTest.kt
 M app/src/androidTest/java/com/alpha/assistant/ui/ShellWorkflowTest.kt
 M app/src/androidTest/java/com/alpha/assistant/voice/InlineVoiceWorkflowTest.kt
 M app/src/androidTest/java/com/alpha/assistant/weather/WeatherWorkflowTest.kt
 M app/src/main/java/com/alpha/assistant/automations/AutomationRuntime.kt
 M app/src/main/java/com/alpha/assistant/automations/AutomationTools.kt
 M app/src/main/java/com/alpha/assistant/automations/AutomationUi.kt
 M app/src/main/java/com/alpha/assistant/capabilities/CapabilityBroker.kt
 M app/src/main/java/com/alpha/assistant/capabilities/CapabilityUi.kt
 M app/src/main/java/com/alpha/assistant/cloud/ChatToolReply.kt
 M app/src/main/java/com/alpha/assistant/sandbox/AndroidJsRuntime.kt
 M app/src/main/java/com/alpha/assistant/sandbox/GraphicsSession.kt
 M app/src/main/java/com/alpha/assistant/sandbox/SandboxClient.kt
 M app/src/main/java/com/alpha/assistant/sandbox/SandboxContract.kt
 M app/src/main/java/com/alpha/assistant/sandbox/SurfaceFrameMetrics.kt
 M app/src/main/java/com/alpha/assistant/surfaces/SurfaceEntry.kt
 M app/src/main/java/com/alpha/assistant/surfaces/SurfaceUi.kt
 M app/src/main/java/com/alpha/assistant/surfaces/ToolWidgets.kt
 M app/src/main/java/com/alpha/assistant/surfaces/WidgetConfigurationActivity.kt
 M app/src/main/java/com/alpha/assistant/tools/GraphicsContract.kt
 M app/src/main/java/com/alpha/assistant/tools/NativeToolSurface.kt
 M app/src/main/java/com/alpha/assistant/tools/NativeToolUi.kt
 M app/src/main/java/com/alpha/assistant/tools/ToolController.kt
 M app/src/main/java/com/alpha/assistant/tools/ToolEngine.kt
 M app/src/main/java/com/alpha/assistant/tools/ToolSpec.kt
 M app/src/main/java/com/alpha/assistant/tools/ToolStore.kt
 M app/src/main/java/com/alpha/assistant/tools/ToolUi.kt
 M app/src/main/java/com/alpha/assistant/tools/ToolUiCatalog.kt
 M app/src/main/java/com/alpha/assistant/ui/AlphaShell.kt
 M app/src/main/java/com/alpha/assistant/weather/WeatherLocation.kt
 M docs/parity/README.md
 M docs/tool-integration.md
?? app/src/androidTest/assets/compose-scenarios.json
?? app/src/androidTest/assets/runtime-replay/
?? app/src/androidTest/java/com/alpha/assistant/capabilities/CapabilityOutcomeWorkflowTest.kt
?? app/src/androidTest/java/com/alpha/assistant/diagnostics/
?? app/src/androidTest/java/com/alpha/assistant/sandbox/SavedGameRuntimeDiagnosticTest.kt
?? app/src/androidTest/java/com/alpha/assistant/tools/ComposeScenarioAcceptanceTest.kt
?? app/src/androidTest/java/com/alpha/assistant/tools/GameGenerationWorkflowTest.kt
?? app/src/androidTest/java/com/alpha/assistant/tools/GenerationAttemptDiagnosticTest.kt
?? app/src/androidTest/java/com/alpha/assistant/tools/GenerationContractGuidanceTest.kt
?? app/src/androidTest/java/com/alpha/assistant/tools/GenerationSmokeWorkflowTest.kt
?? app/src/androidTest/java/com/alpha/assistant/tools/GraphicsBoundariesTest.kt
?? app/src/androidTest/java/com/alpha/assistant/tools/NativeComposeContractTest.kt
?? app/src/androidTest/java/com/alpha/assistant/tools/NativeLayoutTextWorkflowTest.kt
?? app/src/androidTest/java/com/alpha/assistant/tools/NativeMaterialWorkflowTest.kt
?? app/src/androidTest/java/com/alpha/assistant/tools/OneUseCredential.kt
?? app/src/androidTest/java/com/alpha/assistant/tools/OneUseCredentialTest.kt
?? app/src/androidTest/java/com/alpha/assistant/tools/SharedArtifactWorkflowTest.kt
?? app/src/main/java/com/alpha/assistant/tools/ArtifactCoordinator.kt
?? app/src/main/java/com/alpha/assistant/tools/ArtifactProtocol.kt
?? app/src/main/java/com/alpha/assistant/tools/GameGenerationExample.kt
?? app/src/main/java/com/alpha/assistant/tools/NativeUiMotion.kt
?? app/src/main/java/com/alpha/assistant/tools/SurfaceDrawing.kt
?? app/src/main/java/com/alpha/assistant/tools/SurfaceInput.kt
?? app/src/main/java/com/alpha/assistant/tools/ToolGenerationDiagnostic.kt
?? app/src/main/java/com/alpha/assistant/tools/ToolGenerationSmoke.kt
?? docs/parity/compose-acceptance.md
?? docs/parity/compose-coverage.json
?? docs/parity/compose-coverage.md
?? docs/parity/compose-dependencies-20261103.txt
?? docs/parity/compose-installed-inventory.json
?? docs/parity/compose-installed-inventory.md
?? docs/parity/compose-inventory-verification.txt
?? docs/parity/compose-medical-safety.md
?? docs/parity/compose-native-extensions-20261103.json
?? docs/parity/compose-native-extensions-20261103.md
?? docs/parity/compose-native-extensions-check.txt
?? docs/superpowers/
?? tools/
```

- The last full run before this plan, at 13:02:52, had 289 tests and 2 failures:
  - `com.alpha.assistant.tools.MiniAppPresentationWorkflowTest#cancelThenErrorThenRetryPreservesAppAndComposer` (the fixture at `:749`)
  - `com.alpha.assistant.tools.MiniAppPresentationWorkflowTest#savedGeneratedArkanoidTouchMovesVisiblePaddle` ("Missing ready action: Pause" at `:347`)
- Source hash: `178429f925abf47c7f7584568e9a0ff1ecc70b60e5d5079f6831dcfa117f0e19`. The command, from the repository root:

```bash
find app/src tools README.md docs/*.md -type f -not -path '*/__pycache__/*' | LC_ALL=C sort | xargs shasum -a 256 | shasum -a 256
```

## Appendix C: Strings

All texts use the polite form. The table lists each new or changed text and each name that a test or another task uses. A moved text with no change keeps its current text, and the implementer chooses its name.

| Name | Text | Task |
| --- | --- | --- |
| `app_name` | Alphy | 2 |
| `menu` | Меню | 4 |
| `settings` | Настройки | 4 |
| `new_chat` | Новый чат | 5 |
| `search` | Поиск | 5 |
| `mini_apps` | Мини-приложения | 5 |
| `automations` | Автоматизации | 5 |
| `recent` | Недавние | 5 |
| `access` | Доступ и функции | 5 |
| `profile` | Профиль | 5 |
| `composer_label` | Сообщение ассистенту | 6 |
| `composer_send` | Отправить сообщение | 6 |
| `open_settings` | Открыть настройки | 6 |
| `cloud_key_read` | Ключ недоступен. Введите новый ключ в настройках. Сохранённые данные не удалены. | 6 |
| `cloud_key_missing` | Добавьте API-ключ DeepSeek в настройках и повторите запрос. | 6 |
| `cloud_key_format` | API-ключ содержит пробелы или переносы строк. Замените ключ в настройках. | 6 |
| `cloud_consent` | Подтвердите согласие в настройках, чтобы отправлять запросы в DeepSeek. | 6 |
| `settings_deepseek` | DeepSeek | 6 |
| `key_checking` | Проверка ключа… | 6 |
| `key_missing` | Ключ не задан | 6 |
| `key_needs_consent` | Ключ сохранён. Подтвердите согласие, чтобы включить облачные функции. | 6 |
| `key_ready` | Ключ сохранён на устройстве | 6 |
| `key_unreadable` | Ключ недоступен. Удалите ключ и введите его снова. Разговоры и черновики сохранятся. | 6 |
| `key_label` | API-ключ DeepSeek | 6 |
| `key_placeholder` | Введите новый ключ | 6 |
| `key_supporting` | Ключ хранится на устройстве в зашифрованном виде. | 6 |
| `consent_text` | DeepSeek получает ваши сообщения, историю текущего чата и названия его мини-приложений, поисковые запросы и содержимое, которое вы добавляете сами: экран, вставленный текст, фото, файлы и события календаря. Для вопросов о погоде DeepSeek получает примерное местоположение. Когда вы просите изменить мини-приложение, DeepSeek получает его код, интерфейс и сохранённые данные. Включённая автоматизация отправляет данные, которые перечислены в её карточке. Запрос также получает сохранённые вами факты из памяти, если они относятся к запросу. Ключ хранится на устройстве в зашифрованном виде. DeepSeek может взимать плату по вашему тарифу. | 6 |
| `consent_and_save` | Согласиться и сохранить | 6 |
| `consent_accept` | Согласиться | 6 |
| `key_delete` | Удалить ключ | 6 |
| `key_delete_title` | Удалить API-ключ? | 6 |
| `key_delete_text` | Облачные функции перестанут работать. Разговоры и черновики сохранятся. | 6 |
| `delete` | Удалить | 6 |
| `cancel` | Отмена | 6 |
| `key_save_failed` | Не удалось сохранить ключ. Сохранённые данные не удалены. | 6 |
| `key_delete_failed` | Не удалось удалить ключ | 6 |
| `consent_failed` | Не удалось сохранить согласие. Повторите попытку. | 6 |
| `settings_name_label` | Ваше имя | 6 |
| `settings_name_hint` | Alphy использует имя в приветствии. | 6 |
| `settings_voice` | Голос | 6 |
| `settings_assistant` | Ассистент | 6 |
| `app_version` | Alphy · %1$s | 6 |
| `assistant_title` | Системный ассистент | 6 |
| `assistant_selected` | Alphy выбран системным ассистентом. | 6 |
| `assistant_not_selected` | Alphy не выбран системным ассистентом. | 6 |
| `assistant_open` | Открыть настройки ассистента | 6 |
| `assistant_tile_hint` | Плитку Alphy можно добавить в быстрые настройки Android. | 6 |
| `assistant_open_failed` | Не удалось открыть настройки ассистента на этом устройстве. | 6 |
| `assistant_pick_hint` | В настройках Android выберите Alphy системным ассистентом. | 6 |
| `voice_start` | Начать непрерывный сеанс | 6 |
| `greeting_morning` | Доброе утро | 7 |
| `greeting_day` | Добрый день | 7 |
| `greeting_evening` | Добрый вечер | 7 |
| `greeting_night` | Доброй ночи | 7 |
| `greeting_named` | %1$s, %2$s | 7 |
| `desktop_prompt` | Чем помочь? | 7 |
| `now_title` | Сейчас | 7 |
| `now_connect_title` | Подключите DeepSeek | 7 |
| `now_connect_text` | Добавьте ключ, чтобы задавать вопросы и создавать мини-приложения. | 7 |
| `now_day_title` | Мой день | 7 |
| `now_day_text` | Сводка из календаря каждое утро в 08:00. | 7 |
| `now_mini_app_title` | Мини-приложение | 7 |
| `now_mini_app_text` | Опишите задачу, и Alphy соберёт приложение. | 7 |
| `composer_placeholder` | Спросите что-нибудь | 7 |
| `composer_automation` | Автоматизация из запроса · локальная проверка | 7 |
| `voice_send_hint` | Только текст и выбранные вложения отправятся после кнопки «Отправить». | 7 |
| `search_field` | Поиск разговоров | 8 |
| `search_empty` | Здесь появятся ваши разговоры. | 8 |
| `search_none` | Ничего не найдено. Попробуйте другой запрос. | 8 |
| `search_row_draft` | %1$s · черновик | 8 |
| `search_row_saved` | %1$s · на устройстве | 8 |
| `conversation_actions` | Действия разговора %1$s | 8 |
| `rename_placeholder` | Введите название | 8 |
| `access_note` | Доступ для мини-приложений настраивается на странице каждого мини-приложения. | 9 |
| `access_system_settings` | Открыть системные настройки Alphy | 9 |
| `access_listener_title` | Чтение уведомлений для автоматизаций | 9 |
| `access_scope_note` | Выдача доступа не запускает микрофон, камеру, чтение календаря или автоматизацию. Файлы и фотографии выбираются в системном окне без доступа ко всей медиатеке. | 9 |
| `capability_permission_required` | Требуется разрешение Android. Выдайте его в разделе «Доступ и функции». | 9 |
