# Alphy comparison preparation and audit

This offline tool implements the record and audit boundary for the
[matched comparison](../../../docs/superpowers/specs/2026-11-03-alphy-ui-design.md#68-measurement).
It requires Python 3.9 or later and local Git objects. It reads no credentials.
It does not generate mini-apps, control devices, or check the remote reference.

The English input is the approved reference corpus at
`7ee06af1941729b00a110493ad9d1d9b5b127d17`. The runnable reference is a
separate revision. Use the latest observed reference for the final gate.
Keep the original reference worktree intact. Use an isolated source snapshot
for builds.

## Prepare the comparison

1. Record the remote observation and both execution identities in `execution.json`.
2. Freeze the workflow criteria and visual rubric in `inputs.json`.
3. Run the preparation command with a new output directory.
4. Save the reported `plan_sha256` separately before generation.
5. Give only `tuning.json` to the team that tunes the products.
6. Give `final.json` only to the comparison operators after tuning stops.

```sh
python3 tools/compose_acceptance/comparison/compare.py prepare \
  --reference-repo /path/to/reference-snapshot \
  --corpus-revision 7ee06af1941729b00a110493ad9d1d9b5b127d17 \
  --execution execution.json --inputs inputs.json --out comparison-plan
```

The repository must contain both the approved corpus revision and the runnable
reference commit. The tool reads the corpus from Git objects, even if the
working files differ. It preserves each request, the source file SHA-256,
line number, and historical expectation. It reports 726 English generation
slots, of which 270 use held-out requests. Each supplemental case adds six slots.
The plan directory is private to the current user. The tool does not overwrite
an existing directory. Access to the original corpus is outside this tool.

## Android prerelease functional audit

Use a separately frozen plan with `alphy-android-chat` as the candidate route
and `vera-reference-host` as the reference route. Record the device and
provider conditions for each product. Use the same frozen interactions for
both routes. The functional audit covers those interactions and recovery.
Native rendering and performance have their own audit.

```sh
python3 tools/compose_acceptance/comparison/compare.py audit-functional \
  --plan comparison-plan/final.json --plan-sha256 "$PLAN_SHA256" \
  --capture-dir evidence/captures --evidence-dir evidence \
  --results functional-reviews.jsonl
```

Each review line includes `slot_id`, `plan_sha256`, `request_sha256`, and
`execution_sha256`. Add `capture_sha256` for the Android manifest or last
reference attempt, and the reviewed `outcome`. Use `refusal_confirmed: true`
only when the review established an intentional refusal. The `review`
object points to the review JSON file by relative `path` and `sha256`.

The review file repeats the slot and capture hashes. Its `artifact` is the
captured Android artifact ID, the host program SHA-256, or `null` when the
capture has no artifact. Include `critical_defects` and a `checks` map.
For each frozen check, record a `status` of `pass`, `fail`, or
`unavailable`, and the `observed` interaction result. For a failed run,
record `failure` and use an empty check map. Record `recovery` for a
failure, refusal, or correction.

Keep the interaction evidence from the exact captured artifact. A successful
build or a generated card alone supplies no interaction result. Each request
requires at least one passing repeat from each product. The audit also checks
complete terminal evidence, category regressions, and critical defects.

### Plan 5 capture queue

Use the [Plan 5 workflows](plan5-workflows.json) and the nine proposed reference
cases to prepare the inputs. The preparation tool carries forward the original
observable checks for generated fresh-corpus cases. For a historical refusal,
verify the current request and the current capability boundary. Review the
criteria and freeze them before the final run.

```sh
jq -s '.[0] as $base | .[1] as $plan |
  {approved_capabilities: $base.approved_capabilities, use_corpus_checks: $plan.use_corpus_checks,
   interactions: $plan.interactions,
   cases: (($base.cases | map(. * ($plan.reference_case_overrides[.id] // {}))) + $plan.cases)}' \
  tools/compose_acceptance/comparison/reference-26eaacc-supplemental.proposed.json \
  tools/compose_acceptance/comparison/plan5-workflows.json > functional-inputs.json
```

The full functional plan has 792 slots. Use the tuning plan during repairs and
keep the final plan private to the comparison operator. Calculate the hash of
the exact plan file passed to the runner.

```sh
python3 tools/compose_acceptance/comparison/run_functional.py \
  --plan comparison-plan/tuning.json --plan-sha256 "$TUNING_SHA256" \
  --captures evidence/captures
```

This command lists pending, complete, and interrupted captures. Add `--live`
and `--slot SLOT_ID` for a reviewed capture. The default live run takes one
pending slot. Use `--limit N` only when an operator can review each Android
app during its review window. Set `--review-seconds` from 5 to 180 as needed.
For a reference slot, pass `--reference-snapshot`, `--node`, and `--typescript`.
Use `--exa` when the frozen reference identity enables Exa. Supply reference
credentials as documented in the [reference adapter](reference_adapter/README.md).

The runner preserves interrupted captures and terminal failures. Inspect a
nonzero exit before starting more slots. A complete capture requires a separate
functional review. The audit reports pass, fail, or missing for each repeat.

Use `compare.py coverage` with the final plan and its hash to list all workflow
families, check IDs, and slots. This inventory keeps functional status unverified
until the live audit passes. It does not expose prompt text.

### Physical workflow gate

Keep the frozen host plan and its 792 slots unchanged. Use a separate device
manifest for the required physical workflows. The
[device criteria](device-workflows.json) bind each measurement to an existing
case and check. Review and freeze these criteria before device execution.

Record `candidate` and `reference` identities in `device-products.json`.
Each identity requires `revision`, `source_sha256`, `build_sha256`, `signing`,
and `route`. The revision must match the corresponding host revision.
Its `device` object requires `kind` equal to `physical`, plus `model`, `os`,
and `config`. Record provider configuration and measurement conditions there.

```sh
python3 tools/compose_acceptance/comparison/device_compare.py prepare \
  --plan comparison-plan/final.json --plan-sha256 "$PLAN_SHA256" \
  --criteria tools/compose_acceptance/comparison/device-workflows.json \
  --products device-products.json --out device-plan
```

Retain the reported manifest hash separately. Run all device slots on the
signed builds and physical devices from that manifest. The operator supplies
native traces, provider dispatch evidence, readback, and physical recordings.
The capture runner does not automate HarmonyOS installation or measurement.

Each result repeats every field from its device slot. Add
`device_manifest_sha256`, `host_plan_sha256`, `mode` equal to `live`, a unique
32-character hexadecimal `run_id`, the exact `device`, and terminal `status`
equal to `complete`. Record `outcome` as `generated`, `failed`, or `refused`.
Each generated artifact has an `artifact` object with relative `path` and
`sha256`. Each frozen check has `status`, `observed`, and a nonempty `evidence`
array of file references with relative `path` and `sha256`.

The `generation` object records `kind` as `fresh` or `deployed`, and a `trace`
file reference. The trace JSON repeats `slot_id`, `run_id`, `request_sha256`,
`execution_sha256`, and `device_manifest_sha256`. It records `artifact_sha256`
and an `events` array. Each event has a `kind`.

For fresh reference generation, retain one `provider-dispatch` event per
measured `provider_http_calls`. This is a separate execution. For deployment,
retain `host_artifact_sha256` and a `host_capture` file reference. Use the exact
terminal Android `capture.json` or reference `attempt-N/result.json`. The audit
compares the deployed artifact with the captured Android `artifact.json` or
reference `program.vera`. Use the retained artifact for Android workflows.
For a failed deployment, set the trace artifact hash to the attempted host
artifact hash. Keep the same host capture reference.

Keep distinct measurement files for each execution. Include its run identity
in provider readback evidence. A failure or refusal has an empty check map and
a `failure` description. It supplies terminal evidence for the audit.

```sh
python3 tools/compose_acceptance/comparison/device_compare.py audit \
  --plan comparison-plan/final.json --plan-sha256 "$PLAN_SHA256" \
  --manifest device-plan/device-manifest.json --manifest-sha256 "$DEVICE_SHA256" \
  --results device-results.jsonl --evidence-dir evidence
```

The device audit requires terminal evidence for every slot and at least one
passing repeat per product and workflow. Missing, altered, duplicate, synthetic,
or emulator evidence blocks the gate. The operator must review the recordings
against the frozen measurements. File hashes alone cannot prove an effect.
Main requires both the host gate and the device gate to pass.

### Execution input

`execution.json` contains:

| Key | Required contents |
| --- | --- |
| `products.candidate`, `products.reference` | Full Git `revision`, `source_sha256`, `build_sha256`, and text fields `source_status`, `route`, and `features`. |
| Product `provider` | Text fields `name`, `model`, and `config`. Add fields for each provider when a route uses more than one. |
| Product `device` | Text fields `model`, `os`, and `config`. Record conditions that affect the measurement. |
| `reference_observation` | `remote`, `ref`, `local_revision`, `remote_revision`, `checked_at` with a UTC offset, `local_source_status`, and `reference_changes`. |
| `performance` | `latency` and `frames`, each `comparable` or `incomparable`, and a `reason` for those decisions. |
| `held_out_used_for_tuning` | `false`. The operator attests that tuning did not use the held-out requests or results. |

Use `null` for an unavailable `remote_revision`. The final gate then remains
blocked. In `reference_changes`, record features added since the previous
reference and any that Alphy does not cover. Record provider and device
differences in `performance.reason`. Do not put credentials in these files.

### Frozen workflow input

`inputs.json` contains:

| Key | Required contents |
| --- | --- |
| `approved_capabilities` | The approved capability IDs for this comparison. Supply a workflow for each ID. |
| `visual_rubric` | A map from each visual dimension to its fixed rating criteria. |
| `interactions` | A map from English case IDs, such as `fresh:unit-1`, to the criteria objects below. |
| `cases` | Supplemental cases with the criteria fields below, plus `id`, exact `request`, `language` (`ru` or `en`), `category`, and `capabilities`. Add `game: true` for a game workflow. |

Each criteria object has an `expected_outcome` of `generated`, `refused`,
or `either`, and an `interactions` map from check IDs to observable
requirements. If the outcome permits a game, use `game_rules` to identify its
applicable scoring, collision or result logic, terminal, and restart checks.
Use `change_interaction` to identify a check with a timed change.
At least one Russian workflow and one timed change are required for a pass.

The historical checks describe the earlier reference route. Freeze shared
criteria for current capabilities. For example, an earlier expected refusal
does not require a refusal from both products after they support the action.
When `either` applies, specify what to check for each outcome.

The 1–5 integer scale is a tool choice. Define the rating anchors before testing.
Use the same dimensions, rating anchors, and capture procedure for both
products. Hide product labels and use neutral review IDs. Keep the map from
review IDs to slots with the operator. This tool records the blind review
attestation. It does not remove labels from capture files.

## Record and audit results

Run each planned slot with the exact request and fresh draft, session, and
store. Use a distinct generation ID. Keep all terminal records, including
failures. A correction stays within the same slot and generation ID.
Each slot permits one initial attempt and at most one correction.

Append one JSON object per line to `records.jsonl`. Preserve the original log.
The audit rejects a second record for the same slot.

| Field | Contents |
| --- | --- |
| `slot_id` | The planned slot's `id`. |
| `plan_sha256`, `request_sha256`, `execution_sha256` | The frozen plan hash and the request and execution hashes from the slot. |
| `mode`, `generation_id`, `fresh_state` | `real` for production generation. Record IDs for `draft`, `session`, and `store` in `fresh_state`. |
| `outcome` | `generated`, `refused`, or `failed`. A correct refusal is a reviewed result. A provider error is a failure. |
| `attempts` | Objects with `number`, `generation_id`, `outcome`, integer `provider_calls`, and a `stages` map of measured milliseconds by route stage. |
| `critical_defects` | List of observed critical interaction, data loss, or permissions defects. Use an empty list only when none occur. |
| `failure` | Failure reason for a failed generation. |
| `recovery` | `pass`, `fail`, or `unavailable` after a failure, refusal, or correction. |
| `artifact_id`, `parts_only` | Unique artifact ID and Boolean parts-only status for a generated result. |
| `timings` | `first_ui_ms` and `completed_ui_ms` from send. Include `frame_p95_us` for comparable frame conditions. Include `change_ms` for the frozen change interaction. |
| `interactions` | Every frozen check ID with `pass`, `fail`, or `unavailable` for a generated or refused result. |
| `visual` | `blind: true`, a neutral `review_id`, and a `ratings` map for each frozen dimension. Required for generated and refused results. |
| `evidence` | A map of evidence roles to objects with relative `path`, `sha256`, and `origin`. |

Measure first UI at the first visible generated UI. Measure completed UI when
validation finishes and the mini-app is fully usable. A refusal has no
generated UI time.

The `generation` role requires a real run log with origin `real-run`.
Include the actual capture command, product build, and conditions in that log.
Generated and refused outcomes need `screen` and `motion` with origin
`native-device`, and `interactions` and `visual` with origin `human-review`.
A measured frame value needs a native `frame_trace`. A pass or fail recovery
result needs `recovery` evidence with origin `human-review`.
The audit verifies that evidence files exist and match their hashes. Capture
operators must attest to their origins. Synthetic failure fixtures use
`mode: synthetic`, origin `synthetic`, and zero provider calls.

```sh
python3 tools/compose_acceptance/comparison/compare.py audit \
  --plan comparison-plan/final.json --plan-sha256 "$FROZEN_PLAN_SHA256" \
  --results records.jsonl --evidence-dir captures > comparison-report.json
```

The report separates results by category and split. It counts provider calls
separately from slots. It compares visual scores by dimension and uses matched
request and repetition pairs for latency and frame comparisons. It also
reports route stages, parts-only and failure rates, change time, and recovery.
Missing, invalid, synthetic, or failed records block the gate. Incomparable
performance conditions block a performance pass. A blocked report exits with
status 1. Invalid command inputs exit with status 2.

### Ingest adapter packets

Place each unmodified adapter output directory directly under one capture
parent. Keep that parent inside the evidence directory. Pass
`--capture-dir captures/slots` with `--evidence-dir captures` to the audit.
Use an empty results file until supplements are available.

The audit checks each packet against its frozen slot and execution identity.
It reports adapter observations under `captures`. An incomplete capture
counts separately from a slot with no evidence. Failed capture outcomes and
reference attempts stay in the report. A host correction keeps the original
generation ID and the hash of its first result.

The [reference host adapter](reference_adapter/README.md) records host
execution only. A frozen `vera-reference-host` route always blocks the final
gate. Use a separate plan with a native reference identity for native
comparison. Host attempts remain evidence for their original plan.

The [Android adapter](adapters/README.md) records chat and capture checkpoints.
For the frozen `alphy-android-chat` route, supply one supplement per slot in
`records.jsonl`. Keep the slot's three frozen hashes, `mode: real`, and the
SHA-256 of `capture.json` in `capture_sha256`. Supply the reviewed fields
`critical_defects`, `interactions`, `visual`, and `recovery` where applicable.
Use `refusal_confirmed: true` only after review confirms an actual refusal.
Include frame p95 and its trace when the plan requires them.

Put the operation trace in a separate JSON file and reference it as
`evidence.telemetry` with origin `real-run`. Its format is:

| Field | Contents |
| --- | --- |
| `format` | `1`. |
| `slot_id`, `plan_sha256`, `request_sha256`, `execution_sha256`, `capture_sha256` | The supplement identities. |
| `generation_id` | The captured operation ID. For a result with no operation, use the capture run ID. |
| `attempts` | The ordered attempt objects from the record schema. Use the same generation ID for a correction. |
| `provider_count_source` | `http-boundary`. Count HTTP calls in each attempt. |
| `clock`, `events` | For a generated result, use `monotonic-ms` and the event times `send_accepted`, `first_presented_ui`, and `validated_usable_ui`. |
| `parts_only` | The measured parts-only status for a generated result. |
| `change_events` | When applicable, record `accepted` and `usable` times on the same clock for the frozen change. |

Use measured HTTP events, stage durations, and frame presentation events.
The adapter's provider invocations and provisional label do not measure those
values. The audit derives first UI, completed UI, and change durations from
the trace. It takes fresh state and artifact IDs from the device report.
It uses the adapter's recorded screen and motion files for the native
evidence roles. The supplement supplies the review and frame trace files.

The capture report counts HTTP calls as the provider starts request I/O.
The Android production route still needs to emit the complete operation trace,
including attempt lineage and presentation times.
The reference host route still needs a native capture path. The final
comparison also needs the approved supplemental inputs and blind review.
Run the offline contract check with a local reference clone:

```sh
python3 tools/compose_acceptance/comparison/self_check.py /path/to/reference-snapshot
```
