# Current Compose coverage

This record describes protocol 3 above baseline `62179af217478075a1d7a10960c949440dcc4ad8`.
The pinned reference is `deal-studio-ohos@7ee06af1941729b00a110493ad9d1d9b5b127d17`.
The reference working tree is not an input. [Extracted source provenance](inventory.json)

The machine record contains all 62 reference UI entries and all 49 current native catalog types.
It also records current source hashes. [Coverage data](compose-coverage.json)
A mapping identifies a native composition approach. It does not certify identical reference properties, behavior, style, or performance.
The historical inventory and baseline keep their earlier status. This record supplies the current mapping.

The current-source hashes include the recovery changes. They do not replace historical run identities or artifact hashes.
The host harness passed all 15 checks. [Host check evidence](../../evidence/compose-execution/recovery-diagnostics-host.log)
Navigation diagnostics now capture the full tree before editable-node selection and after the text action, including failure.
The repaired navigation workflow still requires device verification. No new real-scenario acceptance result accompanies this source refresh.
Physical displayed frame rate and touch-to-display latency still require a suitable physical device.

## Reference UI families

Each source locator below refers to `entry/src/main/ets/vera/VeraUiCatalog.ets` at the pinned commit.
Tests reside in `app/src/androidTest/java/com/alpha/assistant/tools/`.
The verification record contains actual commands, results, screenshots, and failures. [Current evidence](../../evidence/compose-execution/coverage-verification.md)

| Reference family | Native contract | Contract/workflow tests | Pinned source line |
| --- | --- | --- | ---: |
| ActionBar | `row button icon` | NativeUiWorkflowTest | 419 |
| AdaptiveColumns | `grid` | NativeUiWorkflowTest | 304 |
| AppTheme | `theme` | NativeUiWorkflowTest | 293 |
| BackHandler | `backHandler` | NativeUiWorkflowTest | 257 |
| Badge | `badge` | NativeUiWorkflowTest | 469 |
| Button | `button` | NativeUiWorkflowTest | 172 |
| Canvas | `surfaces` | GraphicsBoundariesTest; GraphicsWorkflowTest | 215 |
| Card | `card` | NativeUiWorkflowTest | 158 |
| Cell | `listItem` | NativeUiWorkflowTest | 175 |
| Checkbox | `checkbox` | NativeUiWorkflowTest | 206 |
| ClockText | `text` | NativeUiWorkflowTest | 380 |
| Column | `column` | NativeUiWorkflowTest | 154 |
| DateField | `dateInput` | NativeUiWorkflowTest | 405 |
| Divider | `divider` | NativeUiWorkflowTest | 256 |
| EmptyState | `emptyState` | NativeComposeContractTest; MiniAppPresentationWorkflowTest | 339 |
| Grid | `grid` | NativeUiWorkflowTest | 219 |
| Header | `section text` | NativeUiWorkflowTest | 309 |
| Hero | `section text` | NativeUiWorkflowTest | 298 |
| Icon | `icon` | NativeUiWorkflowTest | 458 |
| IconButton | `icon button` | NativeUiWorkflowTest | 462 |
| Image | `image` | NativeUiWorkflowTest | 166 |
| InsetBanner | `banner` | NativeComposeContractTest; MiniAppPresentationWorkflowTest | 332 |
| KeyValueGroup | `facts` | NativeComposeContractTest; MiniAppPresentationWorkflowTest | 445 |
| KeyValueItem | `fact` | NativeComposeContractTest; MiniAppPresentationWorkflowTest | 475 |
| ListGroup | `listGroup groupedList` | NativeComposeContractTest; MiniAppPresentationWorkflowTest | 327 |
| ListItem | `listItem` | NativeUiWorkflowTest | 245 |
| MetricGroup | `grid metric` | NativeUiWorkflowTest | 352 |
| Motion | `motion` | NativeComposeContractTest; MiniAppPresentationWorkflowTest | 223 |
| NumberField | `numberInput` | NativeUiWorkflowTest | 394 |
| NumberListItem | `listItem metric` | NativeUiWorkflowTest | 410 |
| NumberStat | `metric` | NativeUiWorkflowTest | 386 |
| NumberText | `text` | NativeUiWorkflowTest | 373 |
| Path | `path drawing command` | GraphicsBoundariesTest; GraphicsWorkflowTest | 168 |
| Progress | `progress` | NativeUiWorkflowTest | 261 |
| ProgressRing | `progressRing` | NativeUiWorkflowTest | 264 |
| Row | `row` | NativeUiWorkflowTest | 156 |
| Scroll | `scroll` | NativeUiWorkflowTest | 160 |
| Section | `section` | NativeUiWorkflowTest | 315 |
| SectionHeader | `section text` | NativeUiWorkflowTest | 321 |
| Select | `select` | NativeUiWorkflowTest | 270 |
| Skeleton | `skeleton` | NativeUiWorkflowTest | 500 |
| Slider | `slider` | NativeUiWorkflowTest | 210 |
| Snackbar | `snackbar` | NativeUiWorkflowTest | 511 |
| Spacer | `spacer` | NativeUiWorkflowTest | 164 |
| Sparkline | `chart` | GraphicsBoundariesTest; GraphicsWorkflowTest | 521 |
| Spinner | `spinner` | NativeUiWorkflowTest | 506 |
| Stage | `stage transform` | NativeComposeContractTest; MiniAppPresentationWorkflowTest | 234 |
| Stat | `metric` | NativeUiWorkflowTest | 346 |
| Surface | `surfaces` | GraphicsBoundariesTest; GraphicsWorkflowTest | 239 |
| Table | `table` | NativeUiWorkflowTest | 485 |
| TableRow | `table bound record` | NativeUiWorkflowTest | 491 |
| Tabs | `tabs` | NativeUiWorkflowTest | 274 |
| Text | `text` | NativeUiWorkflowTest | 162 |
| TextField | `input` | NativeUiWorkflowTest | 181 |
| Ticker | `timer subscription text` | GraphicsBoundariesTest; GraphicsWorkflowTest | 251 |
| Tile | `card` | NativeUiWorkflowTest | 363 |
| TileGrid | `grid card` | NativeUiWorkflowTest | 358 |
| TimeField | `timeInput` | NativeUiWorkflowTest | 400 |
| Timeline | `timeline` | NativeComposeContractTest; MiniAppPresentationWorkflowTest | 431 |
| TimelineItem | `timelineItem` | NativeComposeContractTest; MiniAppPresentationWorkflowTest | 436 |
| Toggle | `toggle` | NativeUiWorkflowTest | 202 |
| Transform | `transform` | NativeComposeContractTest; MiniAppPresentationWorkflowTest | 228 |

All rows have status `bounded_native_mapping` and device status `partial_or_not_run`.
The native rendered workflow has an open failure. Initial and changed metric screenshots do not prove all families passed.
The columns identify test ownership, not a passing result for every row.

## Installed Compose features exposed by the catalog

The application installs Compose BOM `2025.12.00` and `activity-compose:1.12.2`.
The build resolves Material 3 and Compose UI through that BOM. [Dependency source](../../app/build.gradle.kts)
Generated artifacts use the closed native catalog. They cannot invoke arbitrary Compose APIs or generate Kotlin.

| Feature family | Exposed catalog types | Limits and evidence |
| --- | --- | --- |
| Layout and theme | column, row, grid, scroll, theme, card, section, listItem, spacer, divider | Bounded native composition, grid columns 1–4, theme presets, closed styles. NativeUiWorkflowTest. |
| Semantic composition | form, listGroup, facts, fact, timeline, timelineItem, banner, emptyState | Native labels and grouped content. NativeComposeContractTest. Rendered acceptance remains partial. |
| Text and status | text, metric, badge, progress, progressRing, skeleton, spinner, snackbar | Scalar binding, bounded number/clock formats, compact widget projection. NativeUiWorkflowTest. |
| Actions and typed controls | button, icon, input, numberInput, timeInput, dateInput, toggle, checkbox, slider, select, tabs, backHandler | Typed actions, current state, hidden/disabled rejection, labels, dates, and bounds. NativeUiWorkflowTest. |
| Collections and media | list, table, groupedList, chart, image | Stable keys, relative row templates, aggregate work, private image handles, bounded charts. NativeComposeContractTest; NativeImageLifecycleTest. |
| Motion and layers | motion, transform, stage | Triggered presets, bounded poses, parent transforms, one camera, reduced-motion handling. NativeComposeContractTest. Visual acceptance remains open. |
| Native capabilities | calendar, medicine, navigation | Explicit native descriptors, current grants, authority checks, and confirmation. These are not generic platform calls. |

All 49 types appear exactly once in the machine catalog list.
Surfaces, paths, gradients, frame subscriptions, and timers belong to the artifact protocol rather than native catalog types.
GraphicsBoundariesTest and GraphicsWorkflowTest cover these contracts. [Graphics contract](../../evidence/compose-execution/graphics-contract.md)
The installed Compose library contains APIs that this catalog does not expose.
Arbitrary composables, imports, Android objects, unrestricted modifiers, and direct host calls remain unavailable to generated code.

## Android capabilities and authority

The table records the current application boundary. Source presence and intent resolution do not prove successful device execution.
Each result must retain its actual status. Requests, launches, provider readback, physical results, and human actions remain distinct.

| Capability IDs | Authority and native boundary | Actual observation in package 5 | Remaining or unavailable result |
| --- | --- | --- | --- |
| notification.post | App grant; POST_NOTIFICATIONS on API 33+; app channel | Not executed | Physical display or reading unverified |
| calendar.read | READ_CALENDAR; explicit calendar and bounded range | Not executed | Current provider result unverified |
| calendar.create | Foreground ACTION_INSERT form; user saves | Not executed | Event ID and saved event unverified |
| calendar.select, calendar.createOwned, calendar.readOwned, calendar.deleteOwned, calendar.deleteAllOwned | READ_CALENDAR and WRITE_CALENDAR; selected writable calendar; app-owned refs; native review; provider verification | Migration preservation passes; no provider write in package 5 | Real create/read/delete, reminder and unrelated-event preservation remain open |
| calendar.open | Foreground native control and installed calendar handler | Calendar handler resolves on emulator-5556 | Actual launch not executed |
| link.open, app.open, maps.open | Explicit public intent; HTTPS validation or fixed app choice | Geo handler resolves to Google Maps | Visible destination and further actions unverified |
| maps.markReached, maps.route | Foreground scoped grant; explicit stop; native latest reachedRef; adapter coordinate-system rules | Not executed | Real origin/destination checks remain open; routeVerified stays false |
| vibration.play | VIBRATE and vibrator hardware | Not executed | Physical vibration unavailable as evidence |
| flashlight.set | CAMERA; flash hardware; native boolean request | Not executed | Physical torch state unavailable as evidence |
| photo.pick, file.pick, camera.capture | Explicit system picker or camera; selected URI access; camera permission where required; native review | Not executed | Selection, cancellation, and external app results remain open |
| medicine.recordMeal, medicine.recordIntake, medicine.history, medicine.report | Scoped native medicine controls; explicit records; local report review and share | Not executed | A report preview does not prove doctor delivery |
| medicine.remindDaily, medicine.remindMeal, medicine.cancelReminder | Scoped native controls; exact-alarm access; notification access; explicit time zone and schedule | Not executed | Alarm scheduling does not prove timely display or intake |
| calendar.updateOwned | No catalog entry | Unavailable in current generated-artifact API | Test external edits and stale ownership rejection; do not claim native update support |
| source.searchFetch, document.readShare, media.control, location.once, device.context, artifact.data | Planned catalog entries only | Unavailable to generated artifacts | A chat service or Android public API does not create an artifact adapter |
| screen.read, notification.read, accessibility.control | Restricted host surfaces outside generated artifact access | Unavailable to generated artifacts | No arbitrary app control or screen capture |
| Other OHOS SDK and intent targets | Target-specific disposition in android-dispositions.json | Not executed in package 5 | Candidate signatures do not establish implemented adapters |

Android requires calendar permissions for provider access, while common calendar intents use the external app's UI.
[Android Calendar Provider](https://developer.android.com/identity/providers/calendar-provider)
An installed intent handler must exist before an external launch.
[Android common intents](https://developer.android.com/guide/components/intents-common)
Exact-alarm access and notification access remain separate checks in MedicineCapabilities.kt.
The native source defines unavailable results instead of an inexact fallback.

The public Android SDK does not grant application authority by API presence alone.
The older target-specific table retains 554 SDK/calendar/intent dispositions and official signature evidence. [Android dispositions](android-dispositions.json)
This package does not promote those candidates to implemented support.

## Scenario workflows

The canonical manifest preserves the six exact requests and names all observable checks.
[Scenario fixtures](../../app/src/androidTest/assets/compose-scenarios.json)
The safe runner produces separate real and synthetic directories. [Harness procedure](compose-acceptance.md)
The infant-care workflow has source-linked safety criteria. [Medical guidance](compose-medical-safety.md)
