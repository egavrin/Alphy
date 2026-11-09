# Native capabilities and typed tool data

Alphy accepts artifacts with `formatVersion: 3`. If a saved artifact uses an unsupported format or control, Alphy keeps its data and displays it as unavailable.

## Typed state and records

A state field can use a scalar type (`string`, `number`, or `boolean`) or this list definition:

```json
{"type":"list","maxItems":100,"fields":{"id":"string","text":"string","done":"boolean"}}
```

Each record contains exactly the declared fields. Its `id` must be nonempty, unique, and at most 128 characters.
A list contains at most 100 records. Each record has at most 12 scalar fields.
The complete state has at most 40 fields and 48 KiB. Strings have at most 2048 characters.

A `list` native control requires `text` and `bind`. Its optional `action` receives the selected record ID in `event.value`.
The generated action can add, remove, or change records. The host validates the complete result before a database commit.
Widgets show a collection count and open Alphy for record interaction. Existing widget revision and state tokens still reject stale callbacks.

A current-format refinement retains existing values and record IDs. It can add fields with explicit initial defaults.
It cannot remove or change existing field contracts. Alphy rejects incompatible refinement without replacing stored data.
Rollback also rejects a schema that cannot validate current data.

Format 3 also supports private durable `collections`. The host owns record keys, revisions, paging, and commits.
The artifact declares the collection schema and sends typed operations to the host. [Record contract](../app/src/main/java/com/alpha/assistant/tools/ToolRecords.kt)

## Effect contract

Only an explicit supported action can return an effect:

```json
{"state":{"items":[]},"effects":[{"id":"notice","capability":"notification.post","args":{"title":"List","text":"Updated"}}]}
```

The tool must declare every capability ID in its artifact. An action can request at most three effects, with unique IDs.
Preview and render reject all effects. They must also preserve state.
The isolated JavaScript process has no credentials, network, private file access, or Android APIs.
Only the native executor can interpret effects.

State changes and queued effects share one SQLite transaction. The effect key includes tool ID, revision, state version, and effect ID.
The native broker checks the current revision and state token before execution. Duplicate confirmation returns the saved result without another execution.

Alphy checks Android permissions, provider ownership, and foreground access when it executes a request.
Native controls authorize only the operation shown to the user. Other effects use their applicable review or picker.
The user can grant or revoke `host.network` consent for each mini-app. Generated code cannot grant consent.
A stale effect is cancelled.

The broker persists `unknownOutcome` before it calls an adapter. A process failure cannot authorize an automatic retry.
An external picker persists `awaitingUser` with a launch marker. Repeated confirmation cannot launch that picker again.
An unresolved picker can close with `unknownOutcome`, without automatic execution.

`CapabilityBroker.execute` is the native dispatch interface. Its adapter receives a validated `CapabilityRequest` and returns `CapabilityResult`.
Approved automation must use an explicit native approval path. A model cannot supply its own approval.
The automation editor shows the action and its arguments before approval. A scheduled rule must still have current approval when it runs.
Standalone calendar reads remain available to authorized rules. Alphy disables existing rules that synchronized calendars into mini-app lists and retains their definitions, history, and saved records.

## Implemented catalog

The catalog defines the current capability IDs, argument schemas, eligibility, and foreground requirements.
Generation receives a request-specific set of eligible capabilities. [Capability catalog](../app/src/main/java/com/alpha/assistant/capabilities/CapabilityContract.kt)

External activities launch only from an unlocked, resumed Alphy UI. Background requests return `awaitingUser`.
A missing public intent handler returns `unavailable`. Activity launch returns `launched`, not success.
The system picker returns `cancelled`, `succeeded` for selected content, or `unknownOutcome` when its result cannot be verified.
Camera capture stores a private thumbnail, not a full-resolution photo.

The result vocabulary is `succeeded`, `denied`, `revoked`, `unavailable`, `failed`, `cancelled`, `awaitingUser`, `unknownOutcome`, and `launched`.
Permission and hardware failure do not reset tool data.
Native calendar results and selected content remain in the local effect history. Alphy does not automatically send them to DeepSeek.
Refinement still requires the existing explicit confirmation before it sends current tool source and state.

Provider references cover [Calendar Provider](https://developer.android.com/identity/providers/calendar-provider), [document selection](https://developer.android.com/training/data-storage/shared/documents-files), and [notification permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission).

## Owned calendar operations

The dedicated `calendar` native control contains `type`, `action`, `capability`, and `args`, plus optional common flags and style.
It has no generated text. Native code displays the operation, exact event details, and timezone information.
Generated code must echo `input.event.calendar` into exactly one matching effect.
The host freezes owner, revision, state version, capability, and arguments before generated code runs.
A current native click authorizes the matching action when the Android permissions and ownership checks pass.
Generic buttons, inputs, preview, widgets, background tasks, and substituted or additional effects cannot provide that authorization.
Android permission requests and the native calendar picker remain explicit user actions.

Both `READ_CALENDAR` and `WRITE_CALENDAR` are checked for protected operations.
The native picker exposes only writable, visible calendars that support one alert reminder.
Provider IDs, account details, ownership markers, native URIs, and request fingerprints stay in native storage.
Only opaque references and bounded user event data enter local result events.
Calendar results and controls remain private during model refinement.

One event supports an explicit title, start, end, IANA timezone, optional location, and one alert reminder from 0 to 10080 minutes before.
The event is timed and nonrecurring, with a maximum duration of 31 days.
Epoch instants distinguish repeated hours during daylight-saving transitions. The host never guesses an offset for a local-time gap.
Creation checks the device timezone against the displayed `deviceTimeZone` before the provider write.
Deletion displays the original event timezone and the current native device timezone.
The host does not infer dates, times, medicine schedules, or doses.
A saved reminder does not prove that Android will deliver an alert.

The ledger stores one durable prepared record per owner and stable `eventKey` before an event/reminder batch starts.
The batch uses an event insert and a reminder insert with a provider backreference.
The native executor verifies the exact provider event and the single alert reminder before success.
The ownership marker uses the ordinary writable `CUSTOM_APP_PACKAGE` and `CUSTOM_APP_URI` fields.
Production code never requests sync-adapter privileges.

SQLite and the Calendar Provider cannot share a transaction.
An interrupted or partial provider operation returns `unknownOutcome`, and the prepared event key remains consumed.
A repeated create request with the same event key only searches the exact ownership marker and reads the original result.
It never repeats an uncertain insert. A different request with that key is denied.
When the provider removed or changed the ownership marker, the host reports an unknown outcome and preserves the ledger.
Revocation, archival, or app data changes do not delete provider events or their native ownership records.

Deletion uses `Events.CONTENT_URI` with exact ID, calendar, package, marker, and displayed-event predicates.
A provider assertion verifies the one expected reminder in the same batch.
The executor reads the provider again before it reports deletion success.
A repeated verified deletion returns its saved owned-event result and preserves neighboring events.
The host retains uncertain outcomes when access changes after a provider operation starts.

`OwnedCalendarWorkflowTest` contains deterministic failure checks for interrupted inserts, duplicate event keys, readback mismatch, foreign owners, changed markers, lifecycle, permissions, and frozen control arguments.
`OwnedCalendarProviderWorkflowTest` exercises real event/reminder batches and guarded deletion in an isolated synthetic calendar.
`OwnedCalendarAuthorizationWorkflowTest` drives native clicks through the isolated JavaScript engine and tests Android permissions and malicious substitutions.
Only test setup and cleanup use sync-adapter access to create and remove that synthetic calendar.
The provider and UI fixtures save JSON evidence and screenshots under the app external-files directory in `owned-calendar-evidence`.


The `calendar.deleteAllOwned` control shows every selected event from the native owner ledger before its button becomes available.
The displayed snapshot includes uncertain ownership records, so the host can report unresolved provider outcomes honestly.
The button freezes the complete native snapshot before JavaScript runs. Model arguments contain only `version: 1`.
No generated ID list can extend that snapshot. Events added after the explicit click remain separate.
A durable native journal records every selected event and each completed, uncertain, or remaining deletion.
Each provider batch deletes one exact owned event and yields between events for lifecycle checks.
One native click can therefore remove more than three events without an extra confirmation per event.
If permissions, lifecycle, or ownership checks fail, the operation stops or records the affected event as uncertain.
Partial results contain bounded `total`, `deleted`, `unknown`, and `remaining` counts.
Interrupted journals remain saved. Duplicate effect delivery does not repeat completed or uncertain provider operations.
No background recovery resumes deletion. A later explicit native action can review the current remaining snapshot.
`OwnedCalendarBulkWorkflowTest` verifies one frozen snapshot of five owned events, preserved neighboring events, and duplicate delivery.


The delete-all display refreshes after native effect or ledger changes, including approval of a pending create without a state change.
The host checks snapshot completeness before JavaScript and again before it starts deletion.
An incomplete or stale display is rejected. The host never adds undisplayed targets to that action.
When the journal exists, cancellation, recovery, revocation, and archival retain bounded progress counts.
These counts come only from the native journal. Recovery does not resume deletion or query the Calendar Provider.
A repeated uncertain create can recover the original event after the device timezone changes.
The original request fingerprint remains fixed. Only a new creation requires the currently displayed device timezone.
