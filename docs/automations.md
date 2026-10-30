# Local automations

Alpha stores automation rules and history in `alpha-automations.db`. This database is separate from conversations, encrypted credentials, and tool records. This package does not read credentials or call a model.

Each saved edit creates a new revision and removes approval. The user reviews the trigger, conditions, target, parameters, and local data access before enablement. Pause stops subsequent effects. Android permission loss or a changed target tool disables the rule and shows the required action. Resume requires a current review. No automation can request cloud access.

The editor supports one action per rule. The native executor also supports up to three local effects. An external app action must be a separate rule. Supported actions are an Alpha notification, a bounded calendar read, an explicit scalar tool-field update, and supported public app, link, or map actions. Calendar reads use a fixed approved calendar and period, with a maximum of 31 days and 50 events. A tool update validates the complete existing state and uses current SQLite revision and state tokens. It does not run generated code.

Manual execution uses the approved rule. A dry run checks conditions and access, records its result, and applies no effects. Own notifications, calendar results, and tool values remain local. Notification triggers never read the notification title, text, or extras. They use only an approved source package and a hashed occurrence token. Alpha notifications and group summaries cannot trigger a notification rule.

## Triggers and platform limits

- Schedules support one-time, daily, and selected weekdays. A schedule uses the device time zone or an explicit IANA zone. A daylight-saving gap moves the local time forward. An overlap uses the earlier offset once per local date.
- Exact schedules use `setExactAndAllowWhileIdle` and require Android special access. They permit only an Alpha notification. Android and manufacturer restrictions can delay delivery. All other schedules use approximate background alarms. No cloud scheduler exists.
- Reboot, app replacement, time changes, time-zone changes, exact-alarm grants, location-mode changes, and a resumed Alpha screen reconcile schedules and geofences. Permission loss pauses affected rules. A missed restored occurrence receives a `missed` history record. Alpha does not replay expired work. Ordinary late delivery remains eligible for 15 minutes for exact reminders and 24 hours for approximate work.
- Charging connection and disconnection require a user-started foreground monitor and an ongoing notification. The monitor does not silently restart after process loss or reboot. The user must start it and enable affected rules again. Events during gaps are unavailable and do not replay.
- Geofence entry and exit require working Google Play services, precise location, and background location access. The radius is 100–10000 meters. Registration has a bounded wait. Alpha registers both transitions, filters the approved transition, and suppresses repeated transitions. Initial presence inside a zone does not create an event. Google background delivery can take several minutes. Devices without Google services show an unavailable state.
- Notification triggers require notification-listener access and one to ten user-selected launcher applications. Android grants the listener broad system access. Alpha filters package IDs before rule execution and does not inspect content. Reconnection does not replay active notifications. Applications without a launcher entry are not selectable in this editor.

## Durable execution

A run has a unique rule, revision, and occurrence token. SQLite stores an unknown-outcome claim before the first native effect. Each effect rechecks enablement, revision, scope, and Android access. Duplicate occurrences return the stored result. A process interruption never authorizes another effect attempt.

Only a calendar read with a definitive `failed` or `unavailable` result can retry once. Reads with unknown outcomes do not retry. All other effects have one attempt. These limits also apply across duplicate deliveries.

Background app actions return `awaitingUser`. The history provides a continuation button for the exact approved target. The continuation requires an unlocked, resumed Alpha screen, rechecks the active revision, and persists a launch claim before it opens Android. A duplicate button action cannot launch again. The result is `launched`, not confirmation that an external operation completed.

History retains 30 days. A process restart retains rules, approvals, occurrence claims, and results. History cleanup does not reset rules or tool state.

## Verification and references

`AutomationWorkflowTest` uses unique database names and synthetic adapters. Its failure cases were recorded before production code. It covers revision approval, pause, per-effect revocation, duplicate delivery, dry runs, unknown outcomes, local read retry limits, deferred launch claims, database reopen, retention, strict sources, and daylight-saving schedules.

Compilation and source hashes appear in `docs/evidence/automation-build.txt`. Android execution remains pending explicit device-ready authorization from the coordinating root. No production database, credential, or conversation was accessed.

Platform references: [alarms](https://developer.android.com/develop/background-work/services/alarms), [geofences](https://developer.android.com/develop/sensors-and-location/location/geofencing), [GeofencingClient](https://developers.google.com/android/reference/com/google/android/gms/location/GeofencingClient), and [foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types).
