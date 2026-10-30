# Native capabilities and typed tool data

The current artifact format is `formatVersion: 2`. Alpha does not implement a legacy artifact parser.
The current tool database schema is version 2. An earlier database remains intact, but Alpha refuses an automatic upgrade.
Conversations and encrypted credentials use separate storage and remain unchanged.

## Typed collections

A state field can use a scalar type (`string`, `number`, or `boolean`) or this list definition:

```json
{"type":"list","maxItems":100,"fields":{"id":"string","text":"string","done":"boolean"}}
```

Each record contains exactly the declared fields. Its `id` must be nonempty, unique, and at most 128 characters.
A list contains at most 100 records. Each record has at most 12 scalar fields.
The complete state has at most 40 fields and 48 KiB. Strings have at most 2048 characters.

A `list` native control requires `text` and `bind`. Its optional `action` receives the selected record ID in `event.value`.
The generated action can add, remove, or change records. The host validates the complete result before a database commit.
Widgets show a collection count and open Alpha for record interaction. Existing widget revision and state tokens still reject stale callbacks.

A current-format refinement retains existing values and record IDs. It can add fields with explicit initial defaults.
It cannot remove or change existing field contracts. Alpha rejects incompatible refinement without replacing stored data.
Rollback also rejects a schema that cannot validate current data.

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

The user enables each tool grant in Alpha. Model output cannot enable grants.
The user then reviews each effect's exact arguments and confirms that action.
Android permissions remain separate and are checked for each execution.
A revoked tool grant prevents pending actions. A stale effect becomes cancelled.

The broker persists `unknownOutcome` before it calls an adapter. A process failure cannot authorize an automatic retry.
An external picker persists `awaitingUser` with a launch marker. Repeated confirmation cannot launch that picker again.
An unresolved picker can close with `unknownOutcome`, without automatic execution.

`CapabilityBroker.execute` is the native dispatch interface. Its adapter receives a validated `CapabilityRequest` and returns `CapabilityResult`.
Approved automation must use an explicit native approval path. A model cannot supply its own approval.
Automation scheduling and approval policy are outside this package.

## Implemented catalog

| ID | Arguments | Native operation and result |
| --- | --- | --- |
| `notification.post` | `title`, `text` | Own channel. Requires notification permission on API 33+. Success requires an active notification. Otherwise, returns unknown outcome. |
| `calendar.read` | integer `calendarId`, `from`, `to` in epoch milliseconds | READ_CALENDAR. Explicit calendar and range, at most 31 days and 50 event instances. Results stay local. |
| `calendar.create` | `title`, `start`, `end`, optional `location` | ACTION_INSERT opens the calendar form. Returns `launched`. It does not claim an event was saved. |
| `link.open` | `url` | Public HTTPS link without user credentials, private literal addresses, or local host names. Opens the system handler. |
| `app.open` | `app` | Public app allowlist: `settings`, `browser`, `calendar`, `camera`. No arbitrary package or private component access. |
| `maps.open` | `query` | Encoded geo search opens a map handler. No location permission or automatic location read. |
| `vibration.play` | integer `milliseconds`, 1–2000 | Public vibrator API. Returns unavailable without hardware. Accepted requests return unknown outcome because hardware execution is unverified. |
| `flashlight.set` | boolean `enabled` | CAMERA permission and a camera with flash. Busy hardware returns unavailable. Accepted requests return unknown outcome until hardware state can be verified. |
| `photo.pick` | none | System photo picker on API 33+, document picker otherwise. Only selected content URI access. |
| `file.pick` | `mime` | ACTION_OPEN_DOCUMENT. Only the chosen content URI, with persistable read access where the provider allows it. |
| `camera.capture` | none | System camera thumbnail. CAMERA permission applies because Alpha declares it for flashlight access. The user reviews the thumbnail before private storage. |

External activities launch only from an unlocked, resumed Alpha UI. Background requests return `awaitingUser`.
A missing public intent handler returns `unavailable`. Activity launch returns `launched`, not success.
The system picker returns `cancelled`, `succeeded` for selected content, or `unknownOutcome` when its result cannot be verified.
Camera capture stores a private thumbnail, not a full-resolution photo.

The result vocabulary is `succeeded`, `denied`, `revoked`, `unavailable`, `failed`, `cancelled`, `awaitingUser`, `unknownOutcome`, and `launched`.
Permission and hardware failure do not reset tool data.
Native calendar results and selected content remain in the local effect history. Alpha does not automatically send them to DeepSeek.
Refinement still requires the existing explicit confirmation before it sends current tool source and state.

## Explicitly unavailable

`screen.read` requires a supported assist-session integration. Manual text, screenshot, and file selection remain the alternatives.
`location.once` is not implemented. Map search accepts an explicit place instead.
`notification.read` requires a separate notification listener integration.
`accessibility.control` and private app storage access are not implemented.
`calendar.write` is not implemented. The system event form remains the supported creation path.
The generator receives only the implemented catalog and these unavailable IDs.

## Verification

Before production edits, `CapabilityWorkflowTest` defined collection retention, incompatible refinement, preview purity, stale effects, duplicate execution, cancellation, grants, and invalid argument cases.
The existing sandbox tests also retain network, private storage, host API, timeout, and memory isolation cases.
The existing tool and surface fixtures now use the current artifact version.

The phone is disconnected. No device command, device data access, credential access, or live model call occurred.
The prepared instrumentation checks did not execute on Android in this session.
Build commands and source hashes appear in `docs/evidence/capability-build.txt`. Build output appears in `docs/evidence/capability-build.log`.

Platform references: [Calendar Provider](https://developer.android.com/identity/providers/calendar-provider), [document picker](https://developer.android.com/training/data-storage/shared/documents-files), and [notification permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission).
