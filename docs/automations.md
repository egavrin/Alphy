# Local automations

Alpha stores automation rules and history in `alpha-automations.db`. This database is separate from conversations, encrypted credentials, and tool records. This package does not read credentials or call a model.

Each saved edit creates a new revision and removes approval. The user reviews the trigger, conditions, target, parameters, and local data access before enablement. Pause stops subsequent effects. Android permission loss or a changed target tool disables the rule and shows the required action. Resume requires a current review. No automation can request cloud access.

The editor supports individual actions and the «Мой день» workflow. The workflow has two approved actions: a rolling calendar read and calendar-record synchronization into a selected tool list. It can add a third action with an explicit constant notification. An external app action must be a separate rule. Other supported actions are a fixed calendar read, a scalar tool-field update, and supported public app, link, or map actions. Fixed calendar reads cover at most 31 days. Rolling reads cover 1–30 local days from the current day, in an approved time zone. Each read returns at most 50 events.

Tool updates validate the complete proposed state. The isolated sandbox pure-renders that state and validates its UI before one atomic state/UI commit. Rendering cannot change state or request effects. Current SQLite revision and state tokens reject concurrent changes. The sandbox has no credentials or network.

The current automation schema is version 2. Alpha refuses automatic upgrades of an earlier development database. Existing data remains intact. This package adds no migration or compatibility parser.

Manual execution uses the approved rule. Before approval, a dry run displays only the explicit plan and reads no personal data. After approval, a dry run can read the approved local calendar and compute proposed tool state and UI. It displays prospective records without tool writes, notifications, or app launches. It records the check in local history. Own notifications, calendar results, and tool values remain local. Notification triggers never read the notification title, text, or extras. They use only an approved source package and a hashed occurrence token. Alpha notifications and group summaries cannot trigger a notification rule.

## Triggers and platform limits

- Schedules support one-time, daily, selected weekdays, and intervals of 15–10080 minutes. Interval work uses approximate alarms. A schedule uses the device time zone or an explicit IANA zone. A daylight-saving gap moves the local time forward. An overlap uses the earlier offset once per local date.
- Exact schedules use `setExactAndAllowWhileIdle` and require Android special access. They permit only an Alpha notification. Android and manufacturer restrictions can delay delivery. All other schedules use approximate background alarms. No cloud scheduler exists.
- Reboot, app replacement, time changes, time-zone changes, exact-alarm grants, location-mode changes, and a resumed Alpha screen reconcile schedules and geofences. Permission loss pauses affected rules. A missed restored occurrence receives a `missed` history record. Alpha does not replay expired work. All delivered and restored alarms have a maximum lateness of 60 minutes. An older occurrence records `missed` and performs no action. The same limit applies to exact reminders and approximate work.
- Charging connection and disconnection require a user-started foreground monitor and an ongoing notification. The monitor does not silently restart after process loss or reboot. The user must start it and enable affected rules again. Events during gaps are unavailable and do not replay.
- Geofence entry and exit require working Google Play services, precise location, and background location access. The radius is 100–10000 meters. Registration has a bounded wait. Alpha registers both transitions, filters the approved transition, and suppresses repeated transitions. Initial presence inside a zone does not create an event. Google background delivery can take several minutes. Devices without Google services show an unavailable state.
- Notification triggers require notification-listener access and one to ten user-selected launcher applications. Android grants the listener broad system access. Alpha filters package IDs before rule execution and does not inspect content. Reconnection does not replay active notifications. Applications without a launcher entry are not selectable in this editor.

## Durable execution

A run has a unique rule and occurrence token across revisions. The executing approved revision remains history metadata. SQLite stores an unknown-outcome claim before the first native effect. Each effect rechecks enablement, revision, scope, and Android access. Duplicate occurrences return the stored result. A process interruption never authorizes another effect attempt.

Only a calendar read with a definitive `failed` or `unavailable` result can retry once. Reads with unknown outcomes do not retry. All other effects have one attempt. These limits also apply across duplicate deliveries.

Background app actions return `awaitingUser`. The history provides a continuation button for the exact approved target. The continuation requires an unlocked, resumed Alpha screen, rechecks the active revision, and persists a launch claim before it opens Android. A duplicate button action cannot launch again. The result is `launched`, not confirmation that an external operation completed.

History retains 30 days. A process restart retains rules, approvals, occurrence claims, and results. History cleanup does not reset rules or tool state.

## Calendar workflow and chat proposals

A user can open «Мой день» from the chat start card, a local draft, or a user message. The local parser recognizes this calendar recipe and an optional `HH:mm` daily time or a numeric minute interval. It does not implement general natural-language automation generation. Unsupported requests show a limit message. Parsing never sends a request to a model.

The proposal selects no calendar or tool. The user chooses both, reviews the rolling range and target list, saves the structured rule, and approves its exact revision. The editor provides an explicit button to create a local «Мой день» tool. Creating that tool alone enables no rule.

The calendar-to-list action references an earlier read by its approved action ID. Only that read result can supply records. The target collection must contain `id:string` and `text:string`. It can also contain `done:boolean`, `start:number`, and `end:number`, with no other fields. Imported `start` and `end` values use epoch seconds. Display text includes the event time and title. A reserved record ID prefix identifies the approved source calendar. Synchronization replaces only records with that prefix, keeps manual records and other calendars, and preserves matching completion values. Review states that old imported records are replaced. A full list or invalid render stops the update without a partial tool commit. No calendar result enters an automatic notification or cloud request.

## Verification and references

`AutomationWorkflowTest` uses unique database names and synthetic adapters. Its failure cases were recorded before production code. It covers revision approval, pause, per-effect revocation, duplicate delivery, dry runs, unknown outcomes, local read retry limits, deferred launch claims, database reopen, retention, strict sources, and daylight-saving schedules.

`AutomationRepairWorkflowTest` covers fingerprint invalidation and retry, the 60-minute delivery limit, occurrence deduplication across revisions, rolling calendar dataflow, prospective results without writes, rendered UI commits, rejection of render effects, retained manual records, interval limits, and local chat proposals.

Initial compilation evidence appears in `docs/evidence/automation-build.txt`. Repair compilation and source hashes appear in `docs/evidence/automation-repair-build.txt`. Android execution remains pending explicit device-ready authorization from the coordinating root. No production database, credential, or conversation was accessed.

Platform references: [alarms](https://developer.android.com/develop/background-work/services/alarms), [geofences](https://developer.android.com/develop/sensors-and-location/location/geofencing), [GeofencingClient](https://developers.google.com/android/reference/com/google/android/gms/location/GeofencingClient), and [foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types).
