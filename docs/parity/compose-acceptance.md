# Compose acceptance procedure

The runner tests one exact original request per run.
Each request needs three independent real generations. Each generation uses a fresh draft, artifact, session, and test database.
The normal service permits at most one correction. The runner does not replace a failed generation with another run.
Failed runs retain evidence and do not count as accepted artifacts.

The canonical fixture contains the exact requests and observable interaction requirements.
[Scenario manifest](../../app/src/androidTest/assets/compose-scenarios.json)
Synthetic mode always returns the offline Arkanoid fixture. It tests the harness only, regardless of selected scenario.
Real mode calls the existing DeepSeek provider with the original request unchanged.
It does not add a medical diagnosis, medicine, dose, date, or claimed source retrieval.

## Device and data boundaries

The runner fixes every ADB command to `emulator-5556`.
The runner has no clear, uninstall, unqualified ADB, or connected-device Gradle command.
The pre-existing emulator `emulator-5554` must remain untouched.
Build and explicitly replace-install the APKs before a run:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
/opt/homebrew/share/android-commandlinetools/platform-tools/adb -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk
/opt/homebrew/share/android-commandlinetools/platform-tools/adb -s emulator-5556 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

Real mode reads nonempty `DEEPSEEK_API_KEY` from the host environment and preserves that value.
If it is absent or empty, the runner reads only that variable from `~/.env` as dotenv data.
The runner never executes the dotenv file or puts a key in arguments, logs, generated files, or evidence.
Child processes receive an environment without `DEEPSEEK_API_KEY`.
If both sources lack the key, the run reports its name and examined sources without a provider call.

The instrumentation opens one abstract local socket after its identity and empty-storage checks.
The host reaches it through an explicitly device-qualified ADB forward on host loopback.
Android checks the connected peer credentials and accepts only the ADB shell or root UID.
The listener closes after one connection, before the bounded key payload enters the test's in-memory provider input.
The host removes the forward immediately after acknowledgment, including transfer failures.
An absent peer times out after 30 seconds. Payload reads have a five-second timeout and an 8 KiB limit.
The channel trusts the current authorized ADB host and rejects ordinary Android application UIDs.
[Android peer credentials](https://developer.android.com/reference/android/net/LocalSocket#getPeerCredentials()) and [ADB forwarding](https://developer.android.com/tools/adb#forwardports)

The runner neither reads nor changes an existing application credential.
The review activity uses a separate dummy credential and a provider that refuses further requests.
The runner removes only its dummy credential after review. It retains each test database for evidence.

The runner grants no native capability automatically and clicks no generated controls automatically.
A reviewer can operate the test-owned app during the explicit review window.
Native access, Android permissions, native selections, and confirmation remain required for external effects.
Use only test-owned records, calendar entries, medicine labels, and synthetic medical observations.
Preserve unrelated data and a foreign calendar sentinel event throughout the calendar workflow.
Do not treat any requested effect or intent launch as a completed external action.

## Repeatable commands

Run the small host boundary check and inspect the exact 18-slot matrix:

```sh
python3 tools/compose_acceptance/test_harness.py
python3 tools/compose_acceptance/run.py matrix
```

Exercise the harness without a provider request:

```sh
python3 tools/compose_acceptance/run.py run --scenario 4 --repetition 1 --seconds 5
```

Test the credential channel with a random synthetic sentinel:

```sh
python3 tools/compose_acceptance/run.py run --scenario 4 --repetition 1 --credential-check --seconds 1
```

This command does not read a real key, generate an artifact, or call a provider.
It scans captured files for the sentinel and retains only fixed transport and cleanup results.
Its evidence stays under `scenarios/credential-check/` and cannot complete the real-generation matrix.

Run one real generation with a 180-second review window:

```sh
python3 tools/compose_acceptance/run.py run --scenario 1 --repetition 1 --live --seconds 180
```

Repeat the real command for scenario IDs 1 through 6 and repetition IDs 1 through 3.
Do not reuse a run directory or use an accepted artifact as another generation's base.
The runner creates a unique directory and records the exact instrumentation command and source identity.
Every run needs its own generation result and interaction status.
Accepted generations also need screenshots, motion video, and reviewed interaction results.
Rejected generations have no artifact or review window. Their failure evidence does not require screenshots or video.
The reviewer must retain rejected runs as failures, even if a later independent generation succeeds.

The runner reports when the native review window starts.
Use the canonical scenario checks as the procedure during that window.
For insufficient review time, record unavailable checks or a failure. Do not invent an observation.
The retained database identifies the artifact for a separate, explicitly documented continuation.
A continuation must retain the original artifact identity and record its own command and before/after evidence.

## Evidence and review

Default output is `evidence/compose-execution/scenarios/{real,synthetic}/<unique-run-id>/`.
The run creates these records:

| File | Meaning |
| --- | --- |
| identity.json | Git base revision, source SHA-256 map and aggregate hash, and application/test APK hashes |
| manifest.json | Exact request, scenario/repetition, command, device, evidence hashes, and required interaction results |
| report.json | Terminal generation diagnostics, response counts, first-attempt/correction outcomes, accepted artifact identity/hash, final state hash, safe effect statuses, and failure type |
| artifact.json | The accepted generated specification for this test-owned artifact |
| before.png, after.png | Native screen before and after the review window |
| motion.mp4 | Continuous screen recording during the review window |
| instrumentation.log, capture.log | Repeatable runner output and artifact-copy result |

The response record contains counts and fixed outcomes. It contains no raw provider response or exception text.
The `generationAttempts` array records each terminal attempt with its number, outcome, stage, failure code, and actual validation category.
Outcomes are `accepted`, `rejected`, `providerFailure`, `runtimeUnavailable`, or `canceled`.
Stages are `availability`, `provider`, `definition`, `state`, or `smoke`.
Attempt 1 also records an availability or saved-state failure before a provider request. Response counts distinguish those cases.
Only rejected model output can request attempt 2. A generation never has more than one correction.
The observer cannot change validation, cancellation, or retries, including when the observer throws.

UI failures retain the existing safe rule, structural path, expected type or limit, observed type, and count.
Schema failures retain an allowlisted rule. Their path is `unknown` because existing schema paths can contain private field names.
Smoke failures retain a fixed event kind, structural UI/surface/timer location, and allowlisted runtime code when available.
Unknown categories and codes remain `unknown`. The runner does not infer validation categories from response text or separate probes.
Diagnostic records exclude raw responses, exception text, private state, generated IDs, and arbitrary property names.
Generation failures preserve a sanitized report. A failed or incomplete native review never proves acceptance.
All interaction checks initially have status `UNAVAILABLE` and observation `Not reviewed`.
The reviewer must fill each manifest check with `PASS`, `FAIL`, or `UNAVAILABLE`.
Each check needs an expected result, actual observation, and an evidence path within that run directory.
Screenshots alone do not prove saved state, native calendar ownership, collision behavior, or reminder delivery.
Add explicit event, state, provider, or interaction evidence when the check needs it.
Do not change artifact hashes to hide changes. Preserve the original captured files.

Verify the reviewed directory:

```sh
python3 tools/compose_acceptance/run.py verify evidence/compose-execution/scenarios/real/<run-id>
```

The verifier checks evidence hashes, artifact identity, source identity, required screenshots/video, and complete interaction results.
Only a real run with every required check marked `PASS` returns `PASS`.
A synthetic run remains `INCOMPLETE_OR_SYNTHETIC` even if its harness checks pass.
Manual observations remain the reviewer's responsibility. The verifier does not infer behavior from a report field.

Validate a complete generation-failure record separately:

```sh
python3 tools/compose_acceptance/run.py verify-failed evidence/compose-execution/scenarios/real/<run-id>
```

This command checks canonical request identity, hashed report/source/log records, terminal attempts, and unavailable or failed interactions.
It requires no screenshot, video, or accepted artifact. It returns the fixed result `FAILED`, never workflow `PASS`.
A zero command exit status means the failure evidence is complete. The generation and workflow remain failed.
The normal `verify` command remains strict and rejects these directories.
Reports captured before terminal diagnostics existed remain preserved. They do not satisfy this diagnostic evidence contract.

Before acceptance, confirm exactly one accepted real artifact for every matrix slot and 18 distinct artifact identities and databases.
Retain every failed attempt separately. A normal model correction belongs to its original independent generation.
Run the aggregate check against the 18 selected real run directories:

```sh
python3 tools/compose_acceptance/run.py audit evidence/compose-execution/scenarios/real
```

The audit rejects missing slots, repeated slots, shared artifact identities, shared databases, and synthetic reports.
If extra failed runs exist, preserve them and prepare a separate directory of the 18 reviewed run directories.
The acceptance report must disclose those excluded failures and explain the selection.
The root owns real provider runs, cross-package acceptance, and the required Astra High review.

## Scenario-specific criteria

| Scenario | Required observations |
| --- | --- |
| West Lake | Clarifications, source limits, stable stop selection, reopen, navigation, and honest external results |
| Infant care | Explicit observations, age questions, measurement context, source URLs, urgent guidance, no invented diagnosis or dose |
| Daily medicine | Explicit medicine/time/zone, native scheduling and denial, separate intake, reopen |
| Arkanoid | Touch and accessible controls, motion, wall/paddle/brick collisions, score, loss, game over, restart, lifecycle |
| Meal medicine | Separate meal/intake, inclusive 0–30-minute boundaries, retained history, doctor-review report, native reminder limits |
| Pudong | Hourly plan, coordinates, explicit buffers, last-reached origin, skip behavior, owned calendar and reminders, foreign sentinel, edit guards, delete-all, calendar launch |

The offline `GameGenerationWorkflowTest` executes the exact game example from generation guidance through the production pipeline.
It checks motion, collision state, loss, restart, controls, and pause/close/reopen without a provider or native effects.
Its two synthetic negative fixtures preserve the failure mechanisms of `s4-r3-e206aebb8c8f` and `s4-r1-6d3526377e81`.
Both pass preview validation but fail the Arkanoid workflow because no board or frame subscription exists.
A defined simulation that never executes does not satisfy motion.
A declared surface, frame subscription, status change, or screenshot alone does not prove gameplay.
These offline fixtures do not replace independent real generation or native interaction review.

The calendar API has no `calendar.updateOwned` action.
The calendar edit check must modify only a test-owned event through the calendar app and verify stale-detail/ownership behavior.
Report the absent generated update API explicitly. Do not claim it exists.
Platform frame-buffer commits do not prove panel presentation or physical input-to-panel latency.
No physical-device performance claim follows from emulator screenshots or video.
