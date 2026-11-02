# Structured artifact data

Artifacts retain `formatVersion: 2`. The native host validates all schemas, state, requests, and results.

State supports scalar `number`, `string`, and `boolean` types, plus these definitions:

```json
{
  "trip": {"type":"object","fields":{"destination":"string","at":"number"}},
  "board": {"type":"array","maxItems":9,"items":"number"},
  "tasks": {"type":"list","maxItems":100,"fields":{"id":"string","text":"string"}}
}
```

Objects contain exactly their declared fields. Arrays preserve order and have no stable record IDs.
Existing list definitions retain their unique, nonempty stable string IDs. Only those lists bind native list controls.
Automation calendar imports also require that list contract. Scalar controls and automation writes retain their existing contracts.

The host permits 40 root fields, 32 fields per nested object, and schema depth 6.
New structured schemas and collection schemas share a 256-node budget.
Existing root scalar and flat-list schemas retain their original field limits, with a 1024-node total traversal limit.
Arrays contain at most 512 values. Each record permits at most 4096 values. Structured state has a separate 4096-value budget.
Top-level flat-list entries retain their original bounds and do not consume the structured-value budget.
The host limits total state traversal to 60000 values and preserves the 48 KiB byte limit.
These rules preserve previously valid scalar and flat-list state without a format-specific conversion.
Strings contain at most 2048 characters. Numbers must be finite with magnitude at most `9007199254740991`.
This range supports epoch milliseconds. Integer-specific native contracts still require exact integers.
Schema names use `[A-Za-z][A-Za-z0-9_]{0,47}` and exclude `__proto__`, `prototype`, and `constructor` at every depth.
The host accepts strict JSON only. It rejects comments, duplicate keys, and more than 24 container levels before Android parses values. State stays within 48 KiB, artifacts within 64 KiB, and JavaScript source within 32 KiB.

Optional `collections` maps up to eight names to record schemas. Each collection starts empty:

```json
{"collections":{"history":{"type":"object","fields":{"at":"number","dose":"string"}}}}
```

Only explicit action events may return `records`. Each action permits eight operations and one page request.
The action must return no device effects when it requests records.
Each operation declares a unique `id` and an artifact-owned `collection`:

```json
[
  {"id":"save","collection":"history","op":"put","key":"dose1","expectedToken":null,"value":{"at":1793577600000,"dose":"5mg"}},
  {"id":"read","collection":"history","op":"get","key":"dose1"},
  {"id":"page","collection":"history","op":"page","order":"asc","limit":50,"cursor":null}
]
```

Create requires a null `expectedToken`. Update requires the exact native token from a previous result.
Delete uses `op: "delete"`, an exact `expectedToken`, and no `value` field.
The host rejects stale, forged, or cross-artifact tokens. Record keys contain 1 to 128 characters.
Records include `{key,token,value}` and stay within 8 KiB, including that envelope.
Each collection permits 10000 records. An artifact permits 20000 records and 20 MiB of stored record envelopes.

Pages use binary key order, either `asc` or `desc`. They return at most 50 complete records and an 8 KiB page envelope.
A smaller page returns an opaque continuation cursor when more records remain. The host never cuts a record.
A record that cannot fit in a page causes an explicit failure. A null cursor marks the last page.
Cursors bind the artifact, collection, order, position, and collection generation.
A mutation invalidates earlier cursors. Invalid cursors return `staleCursor`.
The host retains at most 256 recent cursors per artifact. An expired cursor also returns `staleCursor`.
No request exposes SQL, filesystem paths, native ownership fields, filters, or joins.

The host commits state, UI, all record mutations, query results, and one delivery receipt in one SQLite transaction.
Revision and state-version checks reject stale actions. Any validation, quota, cancellation, or transport failure before commit rolls back the whole action.
The complete state, result event, and input envelope must fit within 64 KiB. The result event also has a 15 KiB limit.
Overflow causes an explicit failure before the action commits.

The native host constructs `recordResults` events:

```json
{"type":"recordResults","deliveryId":"opaque UUID","entries":[{"requestId":"save","collection":"history","op":"put","status":"succeeded","record":{"key":"dose1","token":"opaque token","value":{"at":1793577600000,"dose":"5mg"}}}]}
```

Get returns a record or `notFound`. Page returns `records` and `cursor`, or `staleCursor`.
Delete returns `succeeded`. Result handlers may update state and UI. They cannot request records or device effects.
Preview and render events also cannot request records or effects, and must preserve state.

Result evaluation occurs outside SQLite. A second transaction commits the validated state, UI, and receipt under the original revision checks.
The host permits at most two local delivery attempts. Process restart resumes only the pending receipt.
It never repeats committed record mutations. Terminal receipts clear their record payloads atomically.
The host retains at most 64 terminal receipt IDs and statuses per artifact, plus one pending receipt.
Revision checks reject stale action replay after older receipt metadata expires. Collection mutations also remove invalid cursor metadata.
Pending receipts prevent concurrent actions or revisions from replacing their state.
After two failed attempts, the host rejects the receipt and retains the committed records.

Collection owners remain private. Cloud refinement receives the schema and source program, with initial state, live state, and UI omitted.
Record contents, counts, cursors, and callback events never enter automatic refinement requests.
The user can attach data explicitly through the existing input flow.

Refinement preserves every existing state and collection schema. It may add a root field with a validated initial default or a new empty collection.
Rollback uses current state and current records. It requires the target contract to accept all existing data and never restores an earlier record database.

Database version 5 adds collection, record, cursor, and delivery tables. Version 4 upgrades without changes to existing rows.
Version 3 retains its prior capability-result upgrade before the additive tables. Other versions fail without a reset or deletion.
