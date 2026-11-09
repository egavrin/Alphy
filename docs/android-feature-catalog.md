# Alphy Android feature catalog

The catalog describes the implemented Alphy phone commands and generated Android mini-app effects.
`CapabilityCatalog` is the authoritative native catalog. Reference platform inventories do not establish Android implementation or device availability.

Chat guidance, generator guidance, the argument validator, and executor bindings consume this catalog.
`CapabilityCatalog` provides caller-specific metadata. `AndroidCapabilities.discover()` adds current permission, API, notification, and hardware availability for the read-only `discover_capabilities` tool.
Discovery never launches activities, requests permission, posts notifications, or reads calendar events or selected content.
The phone agent requires approval of the current plan. Mini-apps require a declared capability and the appropriate native foreground action. The native host checks Android permissions, activity handlers, ownership, and any capability-specific grant at execution.
Planned entries have no executable argument schema and cannot appear in a tool's capability declaration.

## Selected Android SDK mappings

The catalog records bounded Alphy operations. The parity audit keeps reference provenance and does not determine caller authority.

| Alphy operation | Public Android API | Scope |
| --- | --- | --- |
| `calendar.read`, `calendar.create` | `CalendarContract.Instances`, `ACTION_INSERT` | Query the approved range and calendar or open an event form. |
| `maps.open`, `maps.route` | `ACTION_VIEW` | Hand off to a maps handler. The mini-app contract uses its approved reached stop. |
| `flashlight.set`, `audio.tone` | `CameraManager.setTorchMode`, `ToneGenerator.startTone` | Submit a bounded native operation. |
| `device.context` | `ConnectivityManager.activeNetwork` | Report default-network visibility without a reachability test. |
| `device.formatPreferences.read` | `DateFormat.is24HourFormat`, `Calendar.getFirstDayOfWeek` | Report the Android time setting and locale-derived first weekday. |
| `music.playSearch`, `video.capture` | `INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH`, `ACTION_VIDEO_CAPTURE` | Hand off to an installed player or capture one foreground clip into a private file. |
| `sensor.accelerometer.stream` | `SensorManager`, `TYPE_ACCELEROMETER` | Deliver bounded input while the declaring mini-app has foreground focus. |

The reference mappings came from the [pinned Android dispositions](parity/android-dispositions.json).
Use the [parity reproduction instructions](parity/README.md#files-and-reproduction) to inspect the reference inventory.

## Implemented actions

Mini-app effects use a declared capability and the current artifact revision. The native navigation control authorizes only the displayed operation. Network requests require the mini-app grant, and text sharing requires a native preview.
Android permissions remain separate. External activities require an unlocked, resumed Alphy UI.
The minimum supported app version is Android API 29. Permission and hardware checks repeat during execution.

| ID | Input and Android access | Observable result and lifecycle limit |
| --- | --- | --- |
| `notification.post` | Bounded title and text. Notification permission on API 33+. Own notification channel. | `succeeded` confirms an active notification. Display and reading remain unverified. Disabled permission or channel returns `denied`. |
| `calendar.read` | Explicit calendar ID and epoch range. `READ_CALENDAR`. Maximum 31 days and 50 instances. | `succeeded` returns local event instances. Discovery does not read calendars. |
| `calendar.create` | Title, start, end, optional location. Calendar form through `ACTION_INSERT`. No direct write permission. | `launched` confirms the form opened. The user saves the event. No event ID or saved-event confirmation. |
| `link.open` | Public HTTPS URL with no credentials, literal IP address, local host, or nonstandard port. | `launched` confirms a public activity opened. No page content or completed interaction. |
| `app.open` | Only settings, browser, calendar, or camera through public actions. | `launched` confirms activity launch. No arbitrary package control. |
| `maps.open` | Explicit place query. No location read or location permission. | `launched` confirms map search launch. No coordinate route or origin selection. |
| `maps.markReached` | Strict version 1, stable stop ID, label, integer coordinates, and explicit coordinate system. No location permission. | `succeeded` records the user-marked reached stop and returns an opaque owner-scoped `reachedRef`. Device arrival remains unverified. |
| `maps.route` | Strict version 1, latest native `reachedRef`, destination stop, and travel mode. No location permission. | `launched` returns explicit origin, destination, mode, map app, and `routeVerified:false`. Calculation, navigation, and arrival remain unverified. |
| `vibration.play` | Integer duration from 1 to 2000 milliseconds. Vibrator hardware and manifest permission. | `unknownOutcome` means Android accepted the request. Physical vibration remains unverified. |
| `flashlight.set` | Boolean state. `CAMERA` permission and flash hardware. | `unknownOutcome` means Android accepted the request. Busy or missing hardware returns `unavailable`. |
| `photo.pick` | No arguments. System photo picker on API 33+, document picker on API 29–32. | `awaitingUser` after launch. `succeeded` requires a selected content URI. Only selected content is accessible. |
| `file.pick` | One MIME type. System document picker. | `awaitingUser` after launch. `succeeded` requires a selected content URI. Persistent read access depends on the provider. |
| `camera.capture` | Phone agent or mini-app. Optional `lens` is `front` or `rear`. The default is `rear`. Android camera permission applies. | The native preview shows the lens and waits for shutter and Keep. A kept photo stays private. Mini-apps receive an owner-scoped handle. Phone photos appear in the originating chat. |
| `video.capture` | Mini-app. `CAMERA` permission and camera hardware. One foreground recording up to 30 seconds and 32 MiB. | After user acceptance, the requesting app receives a private handle for its native `video.play` control. |
| `sensor.accelerometer.stream` | Mini-app. Explicit foreground action and accelerometer hardware. | Delivers display-aligned samples while the host is active. Native Stop ends delivery. |
| `device.context`, `device.formatPreferences.read` | Mini-app. Bounded status and format reads from an explicit foreground action. | Returns battery, locale and default-network visibility, or the 12/24-hour setting and locale first weekday. |
| `location.once`, `sensor.sample` | Mini-app. One foreground location fix or sensor sample. Location requires Android permission. | Returns a bounded measurement when available. |
| `phone.dial`, `share.text` | Phone agent or mini-app. Explicit number or reviewed text. | Opens the dialer or Sharesheet. The user places the call or chooses the share destination. |
| `music.playSearch` | Phone agent. Explicit song, artist, or album query and a compatible player. | Android receives the search handoff. Playback remains unverified. |

Picker cancellation returns `cancelled`. Missing or unverifiable results return `unknownOutcome`.
Revocation or a stale artifact token prevents a returned picker result from changing current app data.
Results remain in native effect history. Generated JavaScript does not receive Android APIs, credentials, or implicit permission.

For a phone plan with a timer followed by a photo, Alphy opens Clock first. After returning, the user taps Continue to open the selected camera. The handoff confirms that Clock opened.

## Explicit-origin navigation

Both navigation actions require `version:1`. Stop arguments are `stopId`, `label`, `latE6`, `lonE6`, and `coordinateSystem`.
Stop IDs contain 1–64 ASCII letters, digits, underscores, dots, colons, or hyphens. The first character is a letter or digit.
Labels contain 1–160 nonblank characters. Latitude ranges from -90000000 to 90000000. Longitude ranges from -180000000 to 180000000.
Coordinates must be integers. The coordinate system is exactly `wgs84` or `gcj02`.
`maps.route` also requires `reachedRef` and `mode`. Modes are `walking`, `driving`, `transit`, and `bicycling`.
Extra fields, script-supplied ownership, unsupported versions, and missing origins fail validation.

A reached marker means the user explicitly marked a stop. It does not mean Android detected arrival.
Generated apps use the dedicated native `navigation` control for direct actions. Its fields are `type`, `action`, `capability`, and `args`.
`capability` is `maps.markReached` or `maps.route`. `args` follows that capability's strict argument schema.
The control has no generated `text` field. Native code displays the operation, stop label, stable ID, coordinates, coordinate system, and route mode.
The host freezes this selection before JavaScript runs. Only one exactly matching navigation effect can execute directly.
JavaScript receives the selection as `input.event.navigation` with `capability` and `args`. It can echo these fields into its effect.
Generic buttons, inputs, and row actions cannot authorize automatic navigation. Mismatched effects remain pending for an explicit native action.
The grant text explains that these foreground buttons execute directly. Alphy does not display another confirmation dialog for each click.
Only a selected dedicated native control receives this behavior. Widget and background actions cannot mark stops automatically.
Pending actions retain direct native buttons with the stop label. Other capabilities retain their existing confirmation flow.

The native broker supplies effect key, owner, revision, and state version from a persistent claimed effect.
After mutex and Main-thread waits, the executor reads the current lifecycle and keyguard state.
The marker commit rechecks these conditions and current ownership inside its SQLite transaction.
The route executor rechecks ownership, grants, revision, current reached reference, lifecycle, and keyguard inside the final launch transaction.
The native Main-thread callback calls `startActivity` without another dispatch or suspension after these checks.
Android can still change system state during the final platform call. A launch result confirms only that Android accepted the activity launch.
The native ledger stores only the latest marker and up to 128 visited-stop definitions per app, across revisions.
Existing stop IDs cannot change their labels or coordinates within one revision. The limit returns `unavailable` without deleting saved data.
A new marker invalidates earlier references. Grant revocation and archive invalidate the current marker.
References from another owner or revision are rejected. State changes within the same revision preserve a valid reached reference.
Database schema 6 adds two tables. The upgrade preserves existing tools, revisions, drafts, grants, results, resources, and credentials.
Reached markers and live route state remain local and do not enter model refinement automatically.
JavaScript receives only the bounded result contract when the app opts into capability results.

Example: mark A reached, skip planned B, and route to C. The route origin remains A.
After the user marks B reached, the previous A reference fails. The next route must use B's new native reference.
Neither destination selection nor a route launch changes the reached marker.

Google Maps handles WGS84 pairs through an explicit-origin [Maps directions URL](https://developers.google.com/maps/documentation/urls/get-started).
AMap handles GCJ-02 pairs through its documented [Android route intent](https://developer.amap.com/api/amap-mobile/guide/android/route).
The AMap intent supplies both origin coordinates and `dev=0`. Alphy performs no coordinate conversion.
Mixed coordinate systems return `unavailable`. Each coordinate system has one supported adapter, so no app chooser is necessary.
The executor resolves the installed package before launch. Missing handlers return `unavailable` without a browser or search fallback.
A launch result confirms that Android accepted the actual map activity launch. It does not confirm route calculation or navigation.

## Planned native groups

These groups are unavailable to generated apps. Their IDs describe future scope and cannot authorize execution.

| Planned group | Required success contract | Permission and lifecycle constraints | Verification plan |
| --- | --- | --- | --- |
| `calendar.owned` | Create, read back, remind, and delete only Alphy-owned events with stable native IDs. | Calendar read/write permissions, explicit confirmation, and ownership checks. | Verify read-back, reminders, deletion, revocation, duplicates, and restart recovery. |
| `reminder.meal` | Schedule an explicit offset relative to an explicit meal anchor. | Preserve time zone and user time. Do not infer a meal time. | Verify offsets, day boundaries, time-zone changes, denial, and cancellation. |
| `source.searchFetch` | Return bounded source text with source attribution and honest network failures. | Native network boundary. Treat fetched content as untrusted data. Chat search is separate. | Verify URL validation, redirects, size limits, offline failures, and malicious source text. |
| `document.readShare` | Read selected documents and share explicit content. | Selected URI grants, MIME checks, size limits, and share confirmation. | Verify revocation, process restart, provider failures, bounded reads, and cancelled sharing. |
| `media.control` | Operate explicitly selected media with honest playback state. | Selected content access, lifecycle controls, and explicit external actions. | Verify pause/resume, cancellation, absent handlers, and lost URI grants. |
| `artifact.data` | Access declared local artifact data through explicit contracts and revision checks. | Explicit access, local ownership, and stale-token rejection. | Verify isolation, schema mismatch, revocation, revision changes, and restart recovery. |

Other unavailable actions include arbitrary app control, generated-app screen capture, notification reads, and direct calendar writes.
An explicit automation integration does not automatically expose its access to generated apps.

## Verification evidence

`CapabilityWorkflowTest` covers catalog discovery, both model prompts, callable bindings, planned-entry rejection, grants, invalid arguments, and stale results.
The catalog tests were added before production changes. Existing workflows also cover preview purity, duplicate execution, and cancellation.
`NavigationAuthorizationWorkflowTest` drives native controls through ToolController and isolated JavaScript. It checks unrelated actions, stop substitution, route substitution, and a correct single-click action.
`NavigationWorkflowTest` adds offline SQLite and intercepted-intent checks for skipped stops, stale and foreign references, revocation, restart, malformed arguments, adapters, encoding, and additive upgrades.
These navigation fixtures were written before production changes.
The build check is `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`.
The saved build evidence identifies the revision and source hashes. Instrumentation execution requires an authorized Android device and remains pending.

Android documentation confirms separate provider permissions and calendar-form intents in the [Calendar Provider guide](https://developer.android.com/identity/providers/calendar-provider).
Selected document access follows the [Storage Access Framework guide](https://developer.android.com/training/data-storage/shared/documents-files).
The installed API 37 SDK supplied the compile-time public API contracts.
