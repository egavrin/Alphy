# Alphy Android chat capture

The adapter captures one frozen Alphy slot through the chat composer on the
disposable compose acceptance emulator (`emulator-5556`). It does not build or
install APKs. Use it only for a live run that the comparison operator authorizes.
The adapter does not accept the reference product.

1. Build and install the debug and Android test APKs from the comparison source snapshot.
2. Run `android_capture.py identity` and copy the reported source, build, and device identity into the candidate execution input.
3. Add the network and test conditions to the candidate device `config` field.
4. Freeze the plan with `compare.py prepare`.
5. Capture one candidate slot from the frozen plan.

```sh
python3 tools/compose_acceptance/comparison/adapters/android_capture.py capture \
  --plan comparison-plan/final.json --plan-sha256 "$FROZEN_PLAN_SHA256" \
  --slot fresh:unit-1:candidate:1 --output /path/to/new-capture
```

Record `alphy-android-chat` as the candidate route. Copy the provider fields from
the identity output. The source hash uses the compose acceptance runner's
source digest. The build hash covers both installed APK hashes. The device
report records the build fingerprint and the host records size and density.
Keep plan and capture directories outside the source directories in that digest.
Use the tuning plan for tuning. The adapter rejects a held-out slot in that plan.

The instrumentation test starts a fresh chat with test-owned databases.
It uses the composer field and Send action. The report records the actual
chat outcome and the stage of each provider invocation. The real credential
stays in memory and uses the existing one-use ADB peer. The adapter omits
raw provider exceptions, response bodies, and instrumentation stdout.

If the app exposes a provisional preview label, the test attempts a screenshot.
It captures the terminal chat and attempts to open a saved artifact through
its card. The host records the review window when `screenrecord` is available.
Use this window for the frozen interaction steps on the disposable device.
The report distinguishes a committed artifact, a failed request, and a
completed chat without an artifact. A completed chat without an artifact
requires review before it can count as a refusal.

`capture.json` binds the command, slot, execution identity, and evidence
hashes. `report.json` records Android checkpoints. A `device-complete`
capture can contain a failed request or missing review evidence. It means the
instrumentation finished. Do not submit these files as an audit record.

The current route leaves these measurements open:

| Criterion | Required production change |
| --- | --- |
| Send, first UI, and completed UI | Record a monotonic timestamp at the accepted composer action. Associate the operation ID with the first presented preview or final-content frame. Emit completed UI after validation and the usable host frame for that same operation. The provisional label and artifact card do not identify those frames. |
| Route stages, parts-only result, and correction | Connect the existing `ToolGenerationService` attempt observer through `ToolController`. Add timed stage boundaries and the parts-only result at the generation service. Keep the original operation ID and explicit attempt number on every event. |
| Provider calls | The capture counts calls at the DeepSeek HTTP boundary. Bind each completion or search request to its chat operation and generation attempt. One `respond` invocation can contain multiple HTTP requests. |
| Comparison evidence | Record frame traces, check the frozen interactions and recovery, and collect blind ratings. Add the reference adapter, approved supplemental inputs, and the full real matrix. |

The offline protocol check does not contact a device or provider:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 tools/compose_acceptance/comparison/adapters/test_android_capture.py
```
