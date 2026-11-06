# Reference host evidence

This adapter uses `tools/vera-harness/agent-run.js -r` for one frozen reference slot.
It produces a host evidence packet. The packet is not a `compare.py audit` record.
The host harness cannot supply the native evidence required by the final comparison.

Use an isolated local Git snapshot with the runnable reference commit.
Use a trusted Node executable and a local TypeScript `lib/typescript.js` file.
The adapter reads Git objects and runs from a private archive extraction.
It does not run from the snapshot worktree.

## Freeze the host identity

```sh
python3 tools/compose_acceptance/comparison/reference_adapter/reference_capture.py identity \
  --snapshot "$REFERENCE_SNAPSHOT" --revision "$REFERENCE_REVISION" \
  --node "$NODE" --typescript "$TYPESCRIPT_JS"
```

Copy the reported fields into `execution.products.reference` for a host plan.
Add the required `source_status`, `features`, and host `device` fields before freezing the plan.
Keep native execution identities in a separate plan.

`source_sha256` identifies the Git archive for the full pinned commit.
`build_sha256` identifies the Node executable and TypeScript entry file by hash.
This identity does not cover system libraries or an installed native build.
The provider configuration describes the harness contract at `7ee06af1941729b00a110493ad9d1d9b5b127d17`.
Confirm that contract at any later runnable revision before using the live mode.
Use `--exa` for both commands if the frozen provider configuration includes Exa.

## Validate or capture one slot

```sh
python3 tools/compose_acceptance/comparison/reference_adapter/reference_capture.py capture \
  --snapshot "$REFERENCE_SNAPSHOT" --revision "$REFERENCE_REVISION" \
  --node "$NODE" --typescript "$TYPESCRIPT_JS" \
  --plan "$FROZEN_PLAN" --plan-sha256 "$FROZEN_PLAN_SHA256" \
  --slot "$REFERENCE_SLOT" --output "$NEW_SLOT_DIRECTORY"
```

The default mode validates the frozen identities without starting Node or creating the output directory.
Use only approved comparison requests that contain no private data or credentials.
The harness TSV format cannot preserve a tab, line break, or NUL inside a request.
The adapter rejects these inputs before it starts the harness.

For a later authorized provider run, add `--mode live`.
The Node process receives only the required provider variables and private runtime paths.
Supply `ARKESTR_API_KEY` and `TYPESAFE_API_KEY` in the process environment.
Supply `EXA_API_KEY` only when you use `--exa`.
The adapter does not read dotenv files.

The adapter uses a new process and private scratch for each attempt.
It creates `attempt-1/started.json` and `attempt-1/result.json` in a new slot directory.
Use `--correction` with the same slot directory after a terminal host failure.
The correction sends the same request in a new host process.
It retains the generation ID and records the first result hash in `attempt-2/result.json`.
The adapter rejects a second correction and does not replace previous attempts.
The harness can retry internally. The adapter does not count those retries as comparison corrections or provider calls.

The result identifies the frozen slot, source, runtime, and provider conditions.
`built` means the harness reported a served program. `not-built` leaves the cause unclassified.
`failed` includes caught request errors, even if Node exits with status zero.
The command exits with status 0 for a dry run or a complete host result.
It exits with status 1 for a retained failure and status 2 for an input or identity error.

The adapter discards child output and publishes only fixed result fields and artifact hashes and sizes.
It inventories `result.json`, `program.vera`, and `holes.jsonl` when present.
Raw artifacts can contain provider text. The default mode retains only their metadata.
For the functional audit, add `--retain-program` on an approved frozen request.
The adapter then keeps the generated `program.vera` in the private attempt directory
with its verified SHA-256. Use that exact program for the host interaction review.
The adapter does not retain a native screenshot, interaction result, latency measurement, or provider call count.

## Offline check

```sh
python3 tools/compose_acceptance/comparison/reference_adapter/self_check.py \
  --node "$NODE" --output "$NEW_CHECK_DIRECTORY"
```

The check commits the adapter's synthetic harness to a temporary Git repository inside the check directory.
It runs no provider and writes `check.json` with the fixture revision, adapter hash, and observed exit results.
