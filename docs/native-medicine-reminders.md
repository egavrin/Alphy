# Native medicine records and reminders

Generated tools use the closed `medicine.*` capability catalog and the native `medicine` control.
The sandbox has no alarm, database, notification, or share API.

A control has `type`, `action`, `capability`, and `args`.
Common flags and style remain available.
Generated control text is prohibited.
Native code displays the selected operation and exact arguments.
An explicit foreground click freezes those arguments before JavaScript executes.
The script must echo `input.event.medicine` in exactly one effect.
A scoped grant permits that selected operation without another confirmation dialog.
Android permissions remain separate.

All arguments require `version: 1`.

| Capability | Other required arguments | Result |
| --- | --- | --- |
| `medicine.recordMeal` | `recordKey`, `at`, `timeZone` | The user's explicit meal record |
| `medicine.recordIntake` | `recordKey`, `at`, `timeZone`, `medicine` | The user's explicit actual intake record. Optional `mealKey` references this tool's meal. |
| `medicine.remindMeal` | `reminderKey`, `medicine`, `timeZone`, `mealKey`, `offsetMinutes` | Native reminder state |
| `medicine.remindDaily` | `reminderKey`, `medicine`, `timeZone`, `minuteOfDay` | Native reminder state |
| `medicine.cancelReminder` | `reminderKey` | Native cancellation state |
| `medicine.history` | `from`, `to`, `cursor` | Up to five records and `nextCursor` |
| `medicine.report` | `from`, `to` | Launch of the native report preview |

Times use epoch milliseconds.
Zones must be explicit IANA zone IDs.
Records cannot claim future meals or intake.
The contract has no dose argument and does not prescribe treatment.
The native record identifies intake as within 30 minutes only when the explicit timestamps have a difference from zero through 30 minutes.
A missing meal anchor remains `noMealAnchor`.
A missing intake record does not prove missed intake.

`alpha-medicine.db` stores private records independently of existing tool and automation databases.
Stable keys reject conflicting replay and preserve original records.
History uses saved sequence order with a cursor.
The range includes `from` and excludes `to`, with a maximum of 31 days.
No history is silently removed to fit generated state.

Meal reminders require an explicit saved meal and a selected offset from zero through 30 minutes.
The selected zone must match that meal.
For an explicit zero offset, a meal time that already passed requests delivery now while its 30-minute window remains open.
The selected offset remains zero.
The native result preserves the original `selectedAt` and the actual `requestedAt`.
The native control and notification disclose immediate delivery after the selected meal time.
The reminder history records the request time, not a fabricated delivery at the meal time.
A nonzero target that already passed is unavailable.
An expired meal window is unavailable and posts no notification.
A received alarm after the meal's 30-minute deadline records `missed` and posts no notification.
Daily reminders retain the selected zone after device zone changes.
A nonexistent DST time is skipped.
An overlapping DST time uses the earlier instant once.
Daily alarms delayed by more than one minute record `missed`.

The scheduler reuses the automation schedule calculator and its serialized restore path.
Android must permit exact alarms and notifications, including the medicine channel.
There is no inexact fallback.
Discovery reports exact-alarm access, notification permission, notification enablement, and medicine channel state for reminder scheduling.
These reminder prerequisites do not block meal records, intake records, history, reports, or cancellation.
Owner changes, archive, grant revocation, or Android permission loss stop the affected schedule.
A stopped or canceled key remains saved.
A new explicit schedule needs a new key.
Boot, clock, zone, package update, foreground resume, and exact-alarm permission broadcasts reconcile saved schedules.
Restore retains a due occurrence while the delivery window remains open.
Restore and direct alarm delivery use the same deadline and durable occurrence key.
Restore marks an occurrence missed only after that deadline.
Android force-stop prevents delivery until the user opens the app.
Android can delay exact alarms or suppress notification display.
The app does not claim guaranteed delivery.

The scheduler persists an occurrence claim before a notification request.
Duplicate broadcasts cannot post that occurrence twice or create intake records.
An interruption retains `unknownOutcome` without an automatic repeat.
`activeNotification` means Android reports an active notification.
It does not prove display, reading, or intake.

Reports use a frozen native snapshot in chronological order.
They contain only explicit records and reminder attempts.
The report limit is 1000 records and 200000 UTF-8 bytes.
An oversized range is rejected without truncation.
The user reviews the report, then selects Share to open Android's chooser.
Opening the preview exports nothing.
A chooser launch does not prove delivery to a recipient.
