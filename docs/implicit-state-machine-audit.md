# Alphy state machine audit

Alphy is an Android assistant with chat, courses, generated mini-apps, and device automations. One user can have concurrent cloud, UI, and background work on one device. The audit uses the local working tree at HEAD `289ab4a`, including local edits and untracked app source.

Risk rates the consequence of a broken transition. **High** means a possible privacy breach, unauthorized effect, loss of saved data, or missed scheduled action. **Medium** means a blocked workflow, lost progress, or incorrect recovery. **Low** means a local presentation or input error with a direct recovery path.

## Initial findings

### 1. Widget restore can lose or misassign a binding

**Impact.** Medium. A restored widget can lose its app or display the data and controls of another app. The regression tests call the production restore callback directly.

**Expected.** Each destination receives the original owner's binding and preferences. An unchanged ID keeps its binding.

**Observed.** For `[101] → [101]`, the callback deletes the binding at `101`. For `[101, 102] → [102, 103]`, it clears `102` and assigns the first owner to `103`. The loop overwrites a source before reading it and can remove a destination during cleanup. The disjoint mapping passes. [Restore callback](../app/src/main/java/com/alpha/assistant/surfaces/ToolWidgets.kt#L166) · [Regression and control](../app/src/androidTest/java/com/alpha/assistant/surfaces/WidgetRestoreStateWorkflowTest.kt)

**Proposed fix.** Read and validate all source bindings before changing any. Apply the remaps and cleanup in one preferences commit under the existing binding lock. Keep every successful destination, including unchanged IDs.

**Reachability.** The manifest sets `android:allowBackup="false"`. Android documents that some devices can still support device-to-device transfer with this setting. A real device transfer was outside this reproduction. [Manifest](../app/src/main/AndroidManifest.xml#L31) · [Android restore callback](https://developer.android.com/reference/android/appwidget/AppWidgetProvider#onRestored(android.content.Context,%20int[],%20int[])) · [Android backup behavior](https://developer.android.com/identity/data/autobackup#enable-disable)

### 2. Alarm completion can clear a replacement occurrence

**Impact.** Medium in the reproduced path. The next alarm time can disappear from storage. A lasting missed alarm through the Android receiver remains unverified.

**Expected.** Completing occurrence A clears its own due time only. A later occurrence B on the same rule revision remains pending.

**Observed.** The test blocks the executor during A and installs B. A completes with one effect and one run, then changes `nextAt` from `1793433600000` to null. The completion update checks the revision and approval but does not compare the due time. [Delivery](../app/src/main/java/com/alpha/assistant/automations/AutomationSchedule.kt#L5) · [Database update](../app/src/main/java/com/alpha/assistant/automations/AutomationStore.kt#L114) · [Regression](../app/src/androidTest/java/com/alpha/assistant/automations/AutomationScheduleStateWorkflowTest.kt)

**Proposed fix.** Clear the row's pending time only if `next_at` still equals the delivered occurrence. Keep the revision and approval checks in the same update.

**Reachability.** The usual receiver path holds a mutex and reconciles again after delivery. The editor also has a direct reconcile path. The test isolates the storage transition and controls the interleaving. [Runtime serialization](../app/src/main/java/com/alpha/assistant/automations/AutomationRuntime.kt#L40) · [Receiver reconcile](../app/src/main/java/com/alpha/assistant/automations/AutomationRuntime.kt#L603) · [Editor reconcile](../app/src/main/java/com/alpha/assistant/automations/AutomationUi.kt#L354)

## Invariants and available tests

An invariant states the condition the workflow needs to preserve. The test column links existing coverage by workflow. A gap names a case without a targeted check in that coverage. Test links alone do not imply a passing run.

### Shell, chat, and voice

| Machine and owner | Risk | Invariants | Existing tests and gaps |
| --- | --- | --- | --- |
| Shell navigation and external entry, [AlphaShell:118] and [MainActivity:26] | Medium. A duplicate entry can replace the open task. | Consume each pending entry once. Back closes the top overlay before changing the route. A stale library intent cannot open the app it names. | [ShellNavigationWorkflowTest], [DrawerWorkflowTest], [RouteStackWorkflowTest], [AssistantSheetWorkflowTest]. |
| Chat storage and recovery, [ShellViewModel:572] | High. A failed write can lose chat or hide a committed app. | Save a request before its cloud call. Replay pending writes in order. On reload, reconcile generation receipts and expose interrupted requests for retry. | [HistoryStorageTest], [ShellStorageTest], [AutomaticToolWorkflowTest], [HistoryManagementWorkflowTest]. Gap for a failed retry followed by process death. |
| API key and consent, [SecureKeyStore:15] and [ShellViewModel:1212] | High. A stale grant can authorize cloud work. | Require a readable key and recorded consent. Keep the consent record and key changes atomic. Clear active and queued cloud work before deleting the key. | [SettingsConsentWorkflowTest], [CloudWorkflowTest], [RecurringSearchWorkflowTest]. |
| Chat request queue and refinement choice, [ShellViewModel:853] | High. A duplicate or stale job can send a request or publish the wrong reply. | Use one active request. Bind updates to that request and its message IDs. A retry reuses the original user message. A refinement choice must name an app in the conversation. | [CloudWorkflowTest], [AutomaticToolWorkflowTest], [StandaloneRefinementShellWorkflowTest]. |
| Provider decision, search, and stream, [CloudProvider:249] and [CloudProvider:559] | High. A bad decision can disclose context or start unauthorized work. | Authorize tools and search from clean input. Keep provenance and source attribution through streaming. Require `[DONE]` and a matching finish reason. | [CloudWorkflowTest], [MessageProvenanceWorkflowTest]. |
| Chat text, file, image, and camera review, [ReviewedContextUi:39] | High. A stale picker can disclose private content to another draft. | Bind callbacks to the original draft. Validate size and type before attach. Delete abandoned camera captures. Send only reviewed context. | [ReviewedContextUiWorkflowTest], [ReviewedContextWorkflowTest], [DraftIdentityWorkflowTest], [CloudWorkflowTest]. |
| Chat calendar review, [ReviewedContextUi:232] | High. An unreviewed event can disclose private calendar data. | Require calendar read permission when reading and attaching. Attach only the text from the reviewed selection. Bind it to the original draft. | [ReviewedContextUiWorkflowTest], [ReviewedContextWorkflowTest], [ReviewedContextLearningWorkflowTest]. |
| Weather location, [ShellViewModel:506] and [WeatherLocationAccess:15] | High. A stale grant can send location-derived context. | Match permission to the active reply and draft. Require a visible host and a fresh place. Give the provider the resolved place with no coordinates. | [WeatherWorkflowTest], [CloudWorkflowTest]. Gap for callback delivery across activity recreation. |
| Voice and speech stream, [VoiceSession:48] and [TOneStreamingTranscriber:110] | High. Capture can continue after focus loss or enter the wrong draft. | Bind work to the active generation and draft. Stop on lifecycle or audio-focus loss. Accept one terminal recognizer result and release the recording resources. | [InlineVoiceWorkflowTest], [LocalVoiceWorkflowTest], [ShellVoiceWorkflowTest]. Local model and microphone coverage depend on the fixture. |
| Desktop placement and reorder, [DesktopLayout:64] | Medium. A failed write can lose the order. | Preserve each placed item once. Save the order before displaying a successful reorder. Filter items whose placements are no longer valid. | [DesktopGridWorkflowTest], [DesktopWorkflowTest], [DesktopCourseWorkflowTest]. |
| History rename, delete, and clear, [SearchPane:64] and [ShellViewModel:666] | High. A stale selection can delete the wrong conversation. | Bind confirmation to the selected conversation ID. Cancel that conversation's work on delete. Clear all queued requests when clearing history. | [SearchWorkflowTest], [HistoryManagementWorkflowTest], [HistoryStorageTest]. |

### Mini-app authoring and runtime

| Machine and owner | Risk | Invariants | Existing tests and gaps |
| --- | --- | --- | --- |
| Draft and publication, [ToolEngine:796] | High. A late draft can replace a newer saved app. | Validate the candidate and compare its base revision and state version at commit. Publish the app and its recovery receipt together. A rejected draft cannot publish. | [ToolWorkflowTest], [ToolCancellationTest], [AutomaticToolWorkflowTest]. |
| Generation stages and correction, [ToolEngine:258] | High. Invalid generated behavior can reach saved state. | Use one correction budget per cloud session. Pass validation and required behavior checks before publication. Cancellation prevents a later write. | [GenerationRouteWorkflowTest], [GenerationAttemptDiagnosticTest], [GenerationSmokeWorkflowTest], [GenerationBehaviorWorkflowTest], [GameGenerationWorkflowTest]. |
| Draft preview, [ToolController:1086] | Medium. An old attempt can display a misleading preview. | Key previews by draft, request, and attempt. Clear the current preview on cancellation, supersession, or a different persisted result. | [ToolControllerDraftPreviewWorkflowTest], [StaticDraftPreviewUiWorkflowTest]. |
| Library availability and generation binding, [ToolLibraryStorage:40] | High. Stale stores can mutate a different library. | Resolve the active physical generation before opening related stores. Fail closed on an unreadable marker or database. Preserve unreadable files. | [ToolStorageRecoveryWorkflowTest], [LibraryManagementWorkflowTest]. |
| Archive and version restore, [ToolStore:658] and [ToolEngine:1265] | High. An old command can run against archived or revised data. | Archive changes availability and invalidates old commands. Restore validates the older definition against current data and makes a new revision. Keep old versions and their data intact. | [LibraryManagementWorkflowTest], [ToolWorkflowTest], [SharedArtifactWorkflowTest]. |
| Mini-app deletion, [ToolUi:444] and [ToolStore:628] | High. A stale deletion can remove changed data or miss dependent resources. | Confirm the current deletion scope. Persist a tombstone before external cleanup. Repeat only unfinished cleanup after interruption. | [LibraryManagementWorkflowTest], [NativeImageLifecycleTest], [ToolStorageRecoveryWorkflowTest]. |
| Portability and manual definition editing, [ToolPortabilityDialog:37] and [ToolEngine:885] | High. A stale import or save can overwrite current data. | Bound and validate the archive before creating its record. Export only the selected app and requested state. Compare the expected app version before saving a definition. | [MiniAppPortabilityWorkflowTest], [ZipTransportWorkflowTest], [ToolWorkflowTest]. |
| Standalone native edit review, [NativeToolEdit:25] | High. A stale approval can replace a changed app or affect unreviewed rules. | Prepare one proof per request. Confirm it once against the current app and dependent rules. Honor cancellation before admission. | [StandaloneNativeEditWorkflowTest], [StandaloneNativeEditHostWorkflowTest], [StandaloneRefinementShellWorkflowTest]. |
| Artifact host and persistence, [ArtifactCoordinator:315] and [ArtifactCoordinator:506] | High. A retired host can overwrite saved state. | Give frame ownership to the newest live host. Serialize mutations. Compare the durable base before writing. Close or invalidate old hosts on revision change. | [SharedArtifactWorkflowTest], [GraphicsWorkflowTest], [MixedRetainedArtifactWorkflowTest], [LiveInputHostWorkflowTest]. |
| Graphics playback and pointer routing, [ArtifactProtocol:75] and [SurfaceInput:19] | Medium. A gesture can move to the wrong surface or remain pressed. | Follow explicit playback after the script supplies it. Capture a surface on pointer-down. Cancel ownership on pause, stop, restart, or viewport change as applicable. | [GraphicsWorkflowTest], [GraphicsBoundariesTest], [RequestGameplayWorkflowTest]. |
| Live input and final edit, [ArtifactLiveInput:32] and [NativeToolUi:221] | High. A late edit can overwrite a newer value. | Admit only a current lease and version. Coalesce only pending values. Protect the final receipt and surface unsaved input on failure. | [LiveInputDeliveryWorkflowTest], [LiveInputHostWorkflowTest], [NativeLiveInputWorkflowTest], [LiveInputEngineWorkflowTest]. |
| Structured record result, [ToolRecords:207] | High. A retry can duplicate a record change or block its owner. | Commit the record action and receipt together. Retry result delivery within two attempts. Check the receipt and state version before applying the result. | [StructuredStateWorkflowTest], [SharedArtifactWorkflowTest]. |
| JavaScript sandbox, retained graphics, and isolate slots, [AndroidJsRuntime:26] and [GraphicsSession:38] | High. A stale evaluation can publish or retain a dead resource. | Allow one evaluation per isolate. Release leases before a new sandbox generation can connect. Reject late results after deadline, close, or process death. | [SandboxIsolationTest], [RuntimeConnectionRetryTest], [AndroidXRuntimeProbeTest], [GraphicsBoundariesTest]. |
| Native record reorder, [NativeRecordReorder:41] | Medium. A stale drop can move the wrong record. | One source owns the gesture. Use a current eligible target. Clear ownership on drop or cancel. | [RecordReorderWorkflowTest]. |
| Drawing bitmap reservation and cache, [SurfaceDrawing:221] | Medium. A late decode can retain memory or show a removed image. | Reserve capacity before decode. Install only a current handle. Release reservation and bitmap on discard or close. | [SurfaceBitmapWorkflowTest], [NativeImageLifecycleTest]. |

### Device effects and network

| Machine and owner | Risk | Invariants | Existing tests and gaps |
| --- | --- | --- | --- |
| Native effect execution, [CapabilityBroker:16] and [ToolStore:399] | High. A retry can duplicate a device effect. | Claim `pending` before calling Android. Require a current owner and session. On interruption, record the supported outcome and prevent automatic replay. | [CapabilityWorkflowTest], [CapabilityOutcomeWorkflowTest], [CapabilityResultsWorkflowTest]. |
| Native result delivery, [ToolStore:517] | High. A late delivery can overwrite new app state. | Deliver only a terminal batch for its revision and state version. Retry result evaluation within two attempts. Commit result and receipt together. | [CapabilityResultsWorkflowTest], [CapabilityOutcomeWorkflowTest], [SharedArtifactWorkflowTest]. |
| Android picker and permission prompts, [CapabilitySelection:16] and [ToolController:650] | High. A duplicate callback can read or retain private data. | Admit one active picker. Consume its exact token before reading. Check the host and effect again after return. Retire a dismissed prompt. | [CapabilityWorkflowTest], [DeviceAuthorizationWorkflowTest], [NavigationAuthorizationWorkflowTest], [RealVideoPickerWorkflowTest]. |
| Private resource acquisition and cleanup, [CapabilityResources:36] | High. An interrupted operation can leak files or URI grants. | Journal before acquiring. Commit the handle with its selection result. Release rejected work. Retain shared access until the last owner releases it. | [NativeImageLifecycleTest], [CapabilityOutcomeWorkflowTest], [LibraryManagementWorkflowTest]. |
| Foreground and device-read authority, [DeviceReadHosts:18] and [CapabilityBroker:57] | High. Background work can access a device reading. | A current host owns initiation. Resume and focus gates apply when the capability requires them. Cancel work when it loses required eligibility. | [DeviceAuthorizationWorkflowTest], [DeviceReadWorkflowTest], [DeviceInputSourceWorkflowTest], [MiniAppAiWorkflowTest]. |
| Media and video surface lease, [DeviceMediaPlayback:88] and [NativeVideoSurface:76] | High. Playback can start after revocation or on a stale surface. | Recheck authority before start. Permit one active operation per player. Revoke the surface lease on detach. Close playback before releasing the surface. | [AudioCapabilityWorkflowTest], [DeviceMediaPlaybackWorkflowTest], [NativeVideoSurfaceWorkflowTest], [ToolVideoPlaybackWorkflowTest]. |
| Owned calendar write and bulk deletion, [CalendarCapabilities:265] | High. A stale request can affect calendar data or repeat an uncertain write. | Persist an insert marker before the provider call. Verify ownership and identity on readback. Bind bulk deletion to the reviewed snapshot and record each outcome. | [OwnedCalendarWorkflowTest], [OwnedCalendarAuthorizationWorkflowTest], [OwnedCalendarProviderWorkflowTest], [OwnedCalendarBulkWorkflowTest]. |
| Navigation reached reference, [NavigationCapabilities:85] | High. A stale reference can authorize a route with an invalid origin. | Issue an opaque reference only on an authorized foreground mark. Replace it on a new mark. Accept only the current owner and revision on route. | [NavigationWorkflowTest], [NavigationAuthorizationWorkflowTest]. |
| Network grant and transport, [ToolNetworkGrants:104] and [MiniAppNetwork:109] | High. Revoked access can transmit data or misreport a write outcome. | Require a persisted permission and a current scoped lease. Cancel or fence active requests on invalidation. Record uncertainty after a write could have started. | [MiniAppNetworkTest], [ToolNetworkGrantWorkflowTest], [ToolNetworkEffectWorkflowTest], [MiniAppNetworkAccessWorkflowTest]. |

### Automations and background work

| Machine and owner | Risk | Invariants | Existing tests and gaps |
| --- | --- | --- | --- |
| Rule editor, approval, pause, and resume, [AutomationStore:34] and [AutomationRuntime:94] | High. A stale approval can enable a revised or revoked rule. | Require current approval for each enabled revision. Editing invalidates approval. Resume requires the same reviewed definition and current access. | [AutomationActivationWorkflowTest], [AutomationCardEditorWorkflowTest], [AutomationWorkflowTest], [LibraryManagementWorkflowTest]. |
| Occurrence and ordered action execution, [AutomationStore:122] and [AutomationExecutor:15] | High. An interrupted rule can repeat an external effect. | Claim a unique event before executing. Recheck access before each action. Persist uncertain writes and stop before later actions after a terminal failure. | [AutomationWorkflowTest], [ScheduledCalendarWorkflowTest], [AutomationOneTimeWorkflowTest]. |
| One-time automation review and run, [AutomationOneTime:36] | High. A cancelled or outdated proposal can still mutate data. | Prepare one immutable proposal for a disabled rule. Check identity, conditions, and foreground access before claim. Commit only while claimed. | [AutomationOneTimeWorkflowTest]. |
| Alarm occurrence, [AutomationSchedule:5] and [AutomationRuntime:563] | High. Clearing the wrong occurrence can miss a scheduled action. | Accept the current due time and revision within the delivery policy. Record an expired occurrence as missed. Reconcile the next alarm. | [AutomationRepairWorkflowTest], [ScheduledCalendarWorkflowTest]. Gap for a stale callback with the same rule revision and a superseded due time. |
| Charging and notification triggers, [AutomationRuntime:613] and [AutomationComponents:27] | High. Duplicate or revoked triggers can run device actions. | Run charging rules on state change. Deduplicate a notification by key and post time. Stop rules when the listener loses access. | [AutomationWorkflowTest], [AutomationActivationWorkflowTest]. Gap for real listener reconnect. |
| Geofence registration and delivery, [AutomationRuntime:637] | High. An old location transition can run an action. | Match the library generation and rule revision. Reject old timestamps and repeated edges. Replace the registration fingerprint only after successful registration. | [AutomationRepairWorkflowTest], [AutomationWorkflowTest]. Gap for a real device transition across registration reset. |
| Medicine reminder, [MedicineCapabilities:358] | High. A reminder can be missed or posted twice. | Match the approved owner, revision, time, and permissions. Claim before posting. Treat uncertainty as an outcome and advance a daily reminder once. | [MedicineWorkflowTest]. Platform adapters support controlled posting and permission failure. |
| Live-source approval, sample, and schedule, [SourceRefresh:48] and [ScheduleAuthority:103] | High. A stale source can publish after revocation. | Match the reviewed source, app revision, and network grant. Publish only the current write token. Keep a prior sample visible with a failure marker. Claim scheduled occurrences once. | [SourceRefreshWorkflowTest], [ScheduleAuthorityTest], [JsonLiveSourceTest]. |
| Recurring search and job ownership, [RecurringSearch:50] and [SourceRefreshJobService:47] | High. Duplicate work can incur cloud charges or publish stale data. | Match the ticket, consent identity, time window, and app revision. Debit the quota with the claim. Stop work on cancellation or authorization loss. | [RecurringSearchWorkflowTest], [ScheduleAuthorityTest]. Gap for operating system job stop and replacement at the same time. |
| Foreground timer and alert, [ForegroundTimerDriver:43] and [ForegroundTimerAuthority:89] | High. A stale driver can post an alert after pause or reset. | Run only under an eligible driver. Pause on eligibility loss. Commit admission before the alert claim. Enforce the alert rate limits. | [ForegroundTimerAuthorityTest], [ForegroundTimerWorkflowTest], [NativeTimerWorkflowTest]. |
| App update, transfer, and installer, [AppUpdater:88] and [AppUpdateJobService:24] | High. An unverified or stale transfer can reach installer handoff. | Match the saved transfer identity. Validate size, digest, app ID, and signing certificate before installer handoff. Reconstruct a verified or resumable state after restart. | [AppUpdateWorkflowTest]. The full installer replacement case needs a signed fixture and a disposable device. |

### Courses, memory, and Android surfaces

| Machine and owner | Risk | Invariants | Existing tests and gaps |
| --- | --- | --- | --- |
| Course setup and outline, [ShellViewModel:194] | Medium. A late outline can replace a new setup. | Match the active setup and originating conversation. Save only the reviewed outline. Cancel the old request on dismissal. | [ShellCourseCreationWorkflowTest], [ShellCourseHostWorkflowTest], [CourseOutlineWorkflowTest]. |
| Slide generation, [CourseStore:93] and [CourseController:29] | Medium. Stale generation can block progress or replace a slide. | Generate only the reached slide. Require the current operation and revision to commit or fail. Reopening a saved slide does not start a cloud request. | [CourseGenerationWorkflowTest], [CoursePlayerGenerationUiWorkflowTest], [CourseStorageWorkflowTest]. |
| Course progress and answer attempts, [CourseStore:129] | Medium. A duplicate answer can advance progress twice. | Deduplicate answer operation IDs. Require the reached slide and correct answer type. Advance only on a correct answer or an explicit reading-slide action. | [CourseStorageWorkflowTest], [CoursePresentationWorkflowTest], [CourseEntryUiWorkflowTest]. |
| Sourced course preparation, [CourseUi:569] | Medium. A late response can attach sources to a different slide. | Bind search results to the reached slide and its revision. Check again on commit. Cancel the request when the review closes. | [CoursePlayerGenerationUiWorkflowTest], [CourseGenerationWorkflowTest], [LessonSourceRouteWorkflowTest]. |
| Outline refinement, [CourseUi:701] and [CourseStore:167] | Medium. An edit can invalidate completed work. | Protect completed and attempted steps. Apply only the proposal for the current revision. Retire obsolete generation when the outline changes. | [CourseOutlineRefinementUiWorkflowTest], [CourseOutlineWorkflowTest], [CourseStorageWorkflowTest]. |
| Course tutor, [CourseUi:1011] | Medium. A late answer can enter the wrong slide's tutor session. | Accept one question at a time. Bind context to the current slide. Closing cancels its request. Publish only a current response. | [CourseTutorUiWorkflowTest]. |
| Memory fact editing and deletion, [MemoryPageState:18] | High. A stale editor can overwrite or delete the wrong fact. | Save or delete the expected revision. Expose a conflict and retain the draft. Clear the chat draft only if the saved fact came from its current contents. | [MemoryUiWorkflowTest], [ExplicitChatMemoryWorkflowTest], [MemoryFoundationWorkflowTest]. |
| Memory suggestion review, [MemorySuggestionReview:17] | Low in this checkout. The review has no production caller. | Accept or decline from pending or failed. Allow one active save. Use the acceptance ID to prevent duplicate facts. | [MemorySuggestionWorkflowTest]. This test calls the review directly. |
| System assistant capture and handoff, [AssistantSession:48] and [AssistantActivity:123] | High. A stale capture can disclose screen content. | Accept callbacks only for the current unlocked invocation. Require review before attaching content. Clear capture on close, hide, or loss of access. | [AssistantContextWorkflowTest], [AssistantSheetWorkflowTest], [ReviewedContextWorkflowTest]. |
| Pin request, binding, and restoration, [SurfaceEntry:56] and [ToolWidgets:127] | Medium. Restore can lose a binding or preferences. A stale widget action can mutate an unintended app. | Consume only the current pending launcher proposal. Keep owner and preferences together across ID remapping. Reject action delivery for an old binding or state version. | [SurfaceWorkflowTest], [WidgetLayoutWorkflowTest], [LibraryManagementWorkflowTest]. Gap for remapped IDs during restore. |
| Widget layout authoring, [WidgetAuthoring:95] | Medium. An old proposal can replace a newer widget layout. | Recheck the app snapshot and allowed bindings before preview and save. Accept one save proposal. Close a cancelled review. | [WidgetAuthoringTest], [WidgetLayoutWorkflowTest]. |

### Built-in mini-app parts

These transitions run inside app-owned part handlers. User-generated JavaScript can define additional machines.

| Machine and owner | Risk | Invariants | Existing tests and gaps |
| --- | --- | --- | --- |
| Basic input, counter, and entry list, [ToolParts:367] | Low. An invalid event can show the wrong local value. | Validate action payloads. Keep counter bounds and list limits. Remove entries by stable identity. | [PartsGenerationWorkflowTest], [LiveInputEngineWorkflowTest], [LocalCalculatorWorkflowTest]. |
| Quick log, [ToolParts:451] | Medium. A correction can remove the wrong history entry. | Respect the history cap. Remove the last entry that matches the requested action. Keep the rest of the history intact. | [QuickLogGenerationWorkflowTest]. |
| Points ledger, [ToolParts:567] | Medium. An invalid post can create an incorrect balance. | Bind posting to the selected ledger. Respect limits. Derive balance and undo availability from the committed entries. | [PointsLedgerGenerationWorkflowTest]. |
| Itinerary and editable itinerary, [ToolParts:681] and [ToolParts:753] | Medium. An edit can corrupt visit order or the time budget. | Retain stable stop IDs. Recompute times after supported edits. Keep visit and duration state consistent with the itinerary. | [ItineraryGenerationWorkflowTest], [EditableItineraryGenerationWorkflowTest]. |
| Reading history and status, [ToolParts:909] | Medium. A removal can leave stale charts or status. | Derive the last reading, status, and charts from current valid history. Bound history and reject invalid input. | [ReadingStatusGenerationWorkflowTest]. |
| Due-item completion, [ToolParts:1031] | Medium. Completing an item can miss or repeat a due date. | Complete one-time items once. Advance recurring items from the supported clock and repeat rule. Recompute projected counts. | [DueItemsProjectionWorkflowTest], [MixedClockHostWorkflowTest]. |
| Idea deck, [ToolParts:1101] | Medium. A stale selection can complete the wrong idea. | Match current selection and filters. Respect per-day completion and bounded history. Advance the deck after completion. | [IdeaDeckProjectionWorkflowTest], [StandaloneClockedHostWorkflowTest]. |
| Exercise answer feedback, [ToolParts:1213] | Low. A stale selection can display incorrect feedback. | Grade against the saved answer. Replace the previous selection and its feedback together. | [LessonPartsWorkflowTest], [PartsGenerationWorkflowTest], [CourseStorageWorkflowTest]. |

## Verification scope

Graphify could not open a read-only graph. The audit uses direct source inspection and the existing Android instrumentation harness. The scope includes app-owned transitions and built-in parts. Live provider calls, arbitrary generated scripts, and device migration remain unverified.

The focused Android API 36 run built and installed both APKs. The three regression assertions failed as described, and the disjoint restore control passed. An earlier control run passed 32 existing checks across automation repair, schedule authority, course storage, and timer authority. Both runs record the revision, source digest, commands, and installed APK hashes. Focused run record (`evidence/alphy-ui/state-machine-audit-final-20261107/run.json`) · Assertion output (`evidence/alphy-ui/state-machine-audit-final-20261107/stdout.log`) · Control run record (`evidence/alphy-ui/state-machine-audit-controls-20261107/run.json`)

## Regression sweep and repairs

The counts in this section describe the original audit checkout. The evidence paths refer to files in that checkout.

The follow-up sweep ran the tests cited by all 65 inventory rows, plus the two regression classes. The API 36 baseline covered 125 test classes and 741 methods. It recorded 697 passes, 40 failures, and four skips. All 44 baseline failures and skips passed in targeted reruns after repairs, fixture corrections, or setup changes. The reruns use several source snapshots. The 741 methods did not run together again after the fixes. Baseline run (`evidence/alphy-ui/state-machine-regression-baseline-20261107/run.json`) · Coverage by machine (`evidence/alphy-ui/state-machine-regression-baseline-20261107/inventory-coverage.csv`) · Reruns by method (`evidence/alphy-ui/state-machine-regression-baseline-20261107/baseline-reruns.csv`)

| Confirmed regression | Risk and invariant | Repair and verification |
| --- | --- | --- |
| Widget ID restore | Medium. Every restored ID must receive its original owner's binding and preferences. | Snapshot valid source bindings under the binding lock. Commit remaps and source cleanup together. Regression cases cover unchanged, overlapping, cyclic, and disjoint IDs, plus rejected mappings and destination deletion markers. [Restore code](../app/src/main/java/com/alpha/assistant/surfaces/ToolWidgets.kt#L57) · [Tests](../app/src/androidTest/java/com/alpha/assistant/surfaces/WidgetRestoreStateWorkflowTest.kt) |
| Alarm completion and stale delivery | High for the workflow. A due time identifies the occurrence that can start, produce history, and clear its pending time. | Check the stored due time when claiming a scheduled run. Clear only the matching occurrence. Tests reproduce loss of a replacement, execution of a superseded occurrence, and a false missed entry. They also check that the replacement runs and clears its own time. [Claim and completion](../app/src/main/java/com/alpha/assistant/automations/AutomationStore.kt#L114) · [Tests](../app/src/androidTest/java/com/alpha/assistant/automations/AutomationScheduleStateWorkflowTest.kt) |
| Mini-app details Back | Medium. Back must close details before it changes the mini-app route. | The audit fix removed the closed popup from composition. The current implementation uses a sheet that leaves composition when it closes. The existing workflow failed twice at this transition. The fixed workflow closes details and then pops one route on the next Back. [Options sheet](../app/src/main/java/com/alpha/assistant/tools/ToolUi.kt#L677) · [Tests][RouteStackWorkflowTest] |

The widget and alarm run reported six intended failures and two passing controls before the repairs. The focused run passed 26 tests after those repairs. The final combined regression run passed 16 tests. The library suite passed all 12 tests, including the owned-calendar deletion control. Pre-fix run (`evidence/alphy-ui/state-machine-regression-focused-red-20261107/run.json`) · Focused run (`evidence/alphy-ui/state-machine-regression-focused-fixed-20261107/run.json`) · Final regression run (`evidence/alphy-ui/state-machine-regression-final-20261107/run.json`) · Library suite (`evidence/alphy-ui/state-machine-regression-library-20261107/run.json`)

Calendar reruns used Android calendar permissions on a disposable emulator. The course fold test used a window at least 900 dp wide. Update tests used a signed synthetic APK and exercised download recovery, permission return, and installer handoff. The existing coverage gaps in the inventory remain open. Real device transfer and APK replacement remain unverified. Calendar suite (`evidence/alphy-ui/state-machine-regression-calendar-20261107/run.json`) · Tablet run (`evidence/alphy-ui/state-machine-regression-tablet-20261107/run.json`) · Update run (`evidence/alphy-ui/state-machine-regression-update-verified-20261107/run.json`) · Installer run (`evidence/alphy-ui/state-machine-regression-installer-20261107/run.json`)

## Verification on the delivery base

The delivery started from `b6ccdda`. That revision already had the widget restore and alarm completion fixes, plus the options sheet. The 11-test pre-fix run reproduced two remaining failures. A superseded occurrence could execute or create a missed history entry. The widget, completion, and Back controls passed.

The delivered alarm fix checks the pending due time inside the claim transaction. The updated widget tests also verify source cleanup, rejected mappings, and removal of a destination deletion marker.

The final Android API 36 run passed all 32 selected tests. It covered the repaired alarms, widget restore, Back, and the affected test fixtures. The local run record is `evidence/alphy-ui/state-machine-main-delivery-fixed-20261107/run.json`.

[AlphaShell:118]: ../app/src/main/java/com/alpha/assistant/ui/AlphaShell.kt#L118
[AndroidJsRuntime:26]: ../app/src/main/java/com/alpha/assistant/sandbox/AndroidJsRuntime.kt#L26
[AndroidXRuntimeProbeTest]: ../app/src/androidTest/java/com/alpha/assistant/sandbox/AndroidXRuntimeProbeTest.kt
[AppUpdateJobService:24]: ../app/src/main/java/com/alpha/assistant/updates/AppUpdateJobService.kt#L24
[AppUpdateWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/ui/AppUpdateWorkflowTest.kt
[AppUpdater:88]: ../app/src/main/java/com/alpha/assistant/updates/AppUpdater.kt#L88
[ArtifactCoordinator:315]: ../app/src/main/java/com/alpha/assistant/tools/ArtifactCoordinator.kt#L315
[ArtifactCoordinator:506]: ../app/src/main/java/com/alpha/assistant/tools/ArtifactCoordinator.kt#L506
[ArtifactLiveInput:32]: ../app/src/main/java/com/alpha/assistant/tools/ArtifactLiveInput.kt#L32
[ArtifactProtocol:75]: ../app/src/main/java/com/alpha/assistant/tools/ArtifactProtocol.kt#L75
[AssistantActivity:123]: ../app/src/main/java/com/alpha/assistant/surfaces/AssistantActivity.kt#L123
[AssistantContextWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/surfaces/AssistantContextWorkflowTest.kt
[AssistantSession:48]: ../app/src/main/java/com/alpha/assistant/surfaces/AssistantSession.kt#L48
[AssistantSheetWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/surfaces/AssistantSheetWorkflowTest.kt
[AudioCapabilityWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/capabilities/AudioCapabilityWorkflowTest.kt
[AutomaticToolWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/chat/AutomaticToolWorkflowTest.kt
[AutomationActivationWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/automations/AutomationActivationWorkflowTest.kt
[AutomationCardEditorWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/automations/AutomationCardEditorWorkflowTest.kt
[AutomationComponents:27]: ../app/src/main/java/com/alpha/assistant/automations/AutomationComponents.kt#L27
[AutomationExecutor:15]: ../app/src/main/java/com/alpha/assistant/automations/AutomationExecutor.kt#L15
[AutomationOneTime:36]: ../app/src/main/java/com/alpha/assistant/automations/AutomationOneTime.kt#L36
[AutomationOneTimeWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/automations/AutomationOneTimeWorkflowTest.kt
[AutomationRepairWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/automations/AutomationRepairWorkflowTest.kt
[AutomationRuntime:563]: ../app/src/main/java/com/alpha/assistant/automations/AutomationRuntime.kt#L563
[AutomationRuntime:613]: ../app/src/main/java/com/alpha/assistant/automations/AutomationRuntime.kt#L613
[AutomationRuntime:637]: ../app/src/main/java/com/alpha/assistant/automations/AutomationRuntime.kt#L637
[AutomationRuntime:94]: ../app/src/main/java/com/alpha/assistant/automations/AutomationRuntime.kt#L94
[AutomationSchedule:5]: ../app/src/main/java/com/alpha/assistant/automations/AutomationSchedule.kt#L5
[AutomationStore:122]: ../app/src/main/java/com/alpha/assistant/automations/AutomationStore.kt#L122
[AutomationStore:34]: ../app/src/main/java/com/alpha/assistant/automations/AutomationStore.kt#L34
[AutomationWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/automations/AutomationWorkflowTest.kt
[CalendarCapabilities:265]: ../app/src/main/java/com/alpha/assistant/capabilities/CalendarCapabilities.kt#L265
[CapabilityBroker:16]: ../app/src/main/java/com/alpha/assistant/capabilities/CapabilityBroker.kt#L16
[CapabilityBroker:57]: ../app/src/main/java/com/alpha/assistant/capabilities/CapabilityBroker.kt#L57
[CapabilityOutcomeWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/capabilities/CapabilityOutcomeWorkflowTest.kt
[CapabilityResources:36]: ../app/src/main/java/com/alpha/assistant/capabilities/CapabilityResources.kt#L36
[CapabilityResultsWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/capabilities/CapabilityResultsWorkflowTest.kt
[CapabilitySelection:16]: ../app/src/main/java/com/alpha/assistant/capabilities/CapabilitySelection.kt#L16
[CapabilityWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/capabilities/CapabilityWorkflowTest.kt
[CloudProvider:249]: ../app/src/main/java/com/alpha/assistant/cloud/CloudProvider.kt#L249
[CloudProvider:559]: ../app/src/main/java/com/alpha/assistant/cloud/CloudProvider.kt#L559
[CloudWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/cloud/CloudWorkflowTest.kt
[CourseController:29]: ../app/src/main/java/com/alpha/assistant/courses/CourseController.kt#L29
[CourseEntryUiWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/courses/CourseEntryUiWorkflowTest.kt
[CourseGenerationWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/courses/CourseGenerationWorkflowTest.kt
[CourseOutlineRefinementUiWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/courses/CourseOutlineRefinementUiWorkflowTest.kt
[CourseOutlineWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/courses/CourseOutlineWorkflowTest.kt
[CoursePlayerGenerationUiWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/courses/CoursePlayerGenerationUiWorkflowTest.kt
[CoursePresentationWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/courses/CoursePresentationWorkflowTest.kt
[CourseStorageWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/courses/CourseStorageWorkflowTest.kt
[CourseStore:129]: ../app/src/main/java/com/alpha/assistant/courses/CourseStore.kt#L129
[CourseStore:167]: ../app/src/main/java/com/alpha/assistant/courses/CourseStore.kt#L167
[CourseStore:93]: ../app/src/main/java/com/alpha/assistant/courses/CourseStore.kt#L93
[CourseTutorUiWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/courses/CourseTutorUiWorkflowTest.kt
[CourseUi:1011]: ../app/src/main/java/com/alpha/assistant/courses/CourseUi.kt#L1011
[CourseUi:569]: ../app/src/main/java/com/alpha/assistant/courses/CourseUi.kt#L569
[CourseUi:701]: ../app/src/main/java/com/alpha/assistant/courses/CourseUi.kt#L701
[DesktopCourseWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/ui/DesktopCourseWorkflowTest.kt
[DesktopGridWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/ui/DesktopGridWorkflowTest.kt
[DesktopLayout:64]: ../app/src/main/java/com/alpha/assistant/ui/DesktopLayout.kt#L64
[DesktopWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/ui/DesktopWorkflowTest.kt
[DeviceAuthorizationWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/capabilities/DeviceAuthorizationWorkflowTest.kt
[DeviceInputSourceWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/device/DeviceInputSourceWorkflowTest.kt
[DeviceMediaPlayback:88]: ../app/src/main/java/com/alpha/assistant/media/DeviceMediaPlayback.kt#L88
[DeviceMediaPlaybackWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/media/DeviceMediaPlaybackWorkflowTest.kt
[DeviceReadHosts:18]: ../app/src/main/java/com/alpha/assistant/capabilities/DeviceReadHosts.kt#L18
[DeviceReadWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/capabilities/DeviceReadWorkflowTest.kt
[DraftIdentityWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/data/DraftIdentityWorkflowTest.kt
[DrawerWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/ui/DrawerWorkflowTest.kt
[DueItemsProjectionWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/tools/generation/DueItemsProjectionWorkflowTest.kt
[EditableItineraryGenerationWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/tools/generation/EditableItineraryGenerationWorkflowTest.kt
[ExplicitChatMemoryWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/memory/ExplicitChatMemoryWorkflowTest.kt
[ForegroundTimerAuthority:89]: ../app/src/main/java/com/alpha/assistant/timer/ForegroundTimerAuthority.kt#L89
[ForegroundTimerAuthorityTest]: ../app/src/androidTest/java/com/alpha/assistant/timer/ForegroundTimerAuthorityTest.kt
[ForegroundTimerDriver:43]: ../app/src/main/java/com/alpha/assistant/timer/ForegroundTimerDriver.kt#L43
[ForegroundTimerWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/timer/ForegroundTimerWorkflowTest.kt
[GameGenerationWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/tools/GameGenerationWorkflowTest.kt
[GenerationAttemptDiagnosticTest]: ../app/src/androidTest/java/com/alpha/assistant/tools/GenerationAttemptDiagnosticTest.kt
[GenerationBehaviorWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/tools/GenerationBehaviorWorkflowTest.kt
[GenerationRouteWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/tools/generation/GenerationRouteWorkflowTest.kt
[GenerationSmokeWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/tools/GenerationSmokeWorkflowTest.kt
[GraphicsBoundariesTest]: ../app/src/androidTest/java/com/alpha/assistant/tools/GraphicsBoundariesTest.kt
[GraphicsSession:38]: ../app/src/main/java/com/alpha/assistant/sandbox/GraphicsSession.kt#L38
[GraphicsWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/tools/GraphicsWorkflowTest.kt
[HistoryManagementWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/ui/HistoryManagementWorkflowTest.kt
[HistoryStorageTest]: ../app/src/androidTest/java/com/alpha/assistant/data/HistoryStorageTest.kt
[IdeaDeckProjectionWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/tools/generation/IdeaDeckProjectionWorkflowTest.kt
[InlineVoiceWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/voice/InlineVoiceWorkflowTest.kt
[ItineraryGenerationWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/tools/generation/ItineraryGenerationWorkflowTest.kt
[JsonLiveSourceTest]: ../app/src/androidTest/java/com/alpha/assistant/network/livesource/JsonLiveSourceTest.kt
[LessonPartsWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/tools/generation/LessonPartsWorkflowTest.kt
[LessonSourceRouteWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/tools/generation/LessonSourceRouteWorkflowTest.kt
[LibraryManagementWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/tools/LibraryManagementWorkflowTest.kt
[LiveInputDeliveryWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/tools/LiveInputDeliveryWorkflowTest.kt
[LiveInputEngineWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/tools/generation/LiveInputEngineWorkflowTest.kt
[LiveInputHostWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/tools/LiveInputHostWorkflowTest.kt
[LocalCalculatorWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/tools/LocalCalculatorWorkflowTest.kt
[LocalVoiceWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/voice/LocalVoiceWorkflowTest.kt
[MainActivity:26]: ../app/src/main/java/com/alpha/assistant/MainActivity.kt#L26
[MedicineCapabilities:358]: ../app/src/main/java/com/alpha/assistant/capabilities/MedicineCapabilities.kt#L358
[MedicineWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/capabilities/MedicineWorkflowTest.kt
[MemoryFoundationWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/memory/MemoryFoundationWorkflowTest.kt
[MemoryPageState:18]: ../app/src/main/java/com/alpha/assistant/memory/MemoryPageState.kt#L18
[MemorySuggestionReview:17]: ../app/src/main/java/com/alpha/assistant/memory/MemorySuggestionReview.kt#L17
[MemorySuggestionWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/memory/MemorySuggestionWorkflowTest.kt
[MemoryUiWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/memory/MemoryUiWorkflowTest.kt
[MessageProvenanceWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/data/MessageProvenanceWorkflowTest.kt
[MiniAppAiWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/capabilities/MiniAppAiWorkflowTest.kt
[MiniAppNetwork:109]: ../app/src/main/java/com/alpha/assistant/network/MiniAppNetwork.kt#L109
[MiniAppNetworkAccessWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/network/ui/MiniAppNetworkAccessWorkflowTest.kt
[MiniAppNetworkTest]: ../app/src/androidTest/java/com/alpha/assistant/network/MiniAppNetworkTest.kt
[MiniAppPortabilityWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/portability/MiniAppPortabilityWorkflowTest.kt
[MixedClockHostWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/tools/generation/MixedClockHostWorkflowTest.kt
[MixedRetainedArtifactWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/tools/generation/MixedRetainedArtifactWorkflowTest.kt
[NativeImageLifecycleTest]: ../app/src/androidTest/java/com/alpha/assistant/tools/NativeImageLifecycleTest.kt
[NativeLiveInputWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/tools/NativeLiveInputWorkflowTest.kt
[NativeRecordReorder:41]: ../app/src/main/java/com/alpha/assistant/tools/NativeRecordReorder.kt#L41
[NativeTimerWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/tools/NativeTimerWorkflowTest.kt
[NativeToolEdit:25]: ../app/src/main/java/com/alpha/assistant/tools/NativeToolEdit.kt#L25
[NativeToolUi:221]: ../app/src/main/java/com/alpha/assistant/tools/NativeToolUi.kt#L221
[NativeVideoSurface:76]: ../app/src/main/java/com/alpha/assistant/tools/NativeVideoSurface.kt#L76
[NativeVideoSurfaceWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/tools/NativeVideoSurfaceWorkflowTest.kt
[NavigationAuthorizationWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/capabilities/NavigationAuthorizationWorkflowTest.kt
[NavigationCapabilities:85]: ../app/src/main/java/com/alpha/assistant/capabilities/NavigationCapabilities.kt#L85
[NavigationWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/capabilities/NavigationWorkflowTest.kt
[OwnedCalendarAuthorizationWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/capabilities/OwnedCalendarAuthorizationWorkflowTest.kt
[OwnedCalendarBulkWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/capabilities/OwnedCalendarBulkWorkflowTest.kt
[OwnedCalendarProviderWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/capabilities/OwnedCalendarProviderWorkflowTest.kt
[OwnedCalendarWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/capabilities/OwnedCalendarWorkflowTest.kt
[PartsGenerationWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/tools/generation/PartsGenerationWorkflowTest.kt
[PointsLedgerGenerationWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/tools/generation/PointsLedgerGenerationWorkflowTest.kt
[QuickLogGenerationWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/tools/generation/QuickLogGenerationWorkflowTest.kt
[ReadingStatusGenerationWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/tools/generation/ReadingStatusGenerationWorkflowTest.kt
[RealVideoPickerWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/media/RealVideoPickerWorkflowTest.kt
[RecordReorderWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/tools/RecordReorderWorkflowTest.kt
[RecurringSearch:50]: ../app/src/main/java/com/alpha/assistant/scheduling/RecurringSearch.kt#L50
[RecurringSearchWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/scheduling/RecurringSearchWorkflowTest.kt
[RequestGameplayWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/tools/RequestGameplayWorkflowTest.kt
[ReviewedContextLearningWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/ui/ReviewedContextLearningWorkflowTest.kt
[ReviewedContextUi:232]: ../app/src/main/java/com/alpha/assistant/ui/ReviewedContextUi.kt#L232
[ReviewedContextUi:39]: ../app/src/main/java/com/alpha/assistant/ui/ReviewedContextUi.kt#L39
[ReviewedContextUiWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/ui/ReviewedContextUiWorkflowTest.kt
[ReviewedContextWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/surfaces/ReviewedContextWorkflowTest.kt
[RouteStackWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/tools/RouteStackWorkflowTest.kt
[RuntimeConnectionRetryTest]: ../app/src/androidTest/java/com/alpha/assistant/sandbox/RuntimeConnectionRetryTest.kt
[SandboxIsolationTest]: ../app/src/androidTest/java/com/alpha/assistant/sandbox/SandboxIsolationTest.kt
[ScheduleAuthority:103]: ../app/src/main/java/com/alpha/assistant/scheduling/ScheduleAuthority.kt#L103
[ScheduleAuthorityTest]: ../app/src/androidTest/java/com/alpha/assistant/scheduling/ScheduleAuthorityTest.kt
[ScheduledCalendarWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/automations/ScheduledCalendarWorkflowTest.kt
[SearchPane:64]: ../app/src/main/java/com/alpha/assistant/ui/SearchPane.kt#L64
[SearchWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/ui/SearchWorkflowTest.kt
[SecureKeyStore:15]: ../app/src/main/java/com/alpha/assistant/data/SecureKeyStore.kt#L15
[SettingsConsentWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/ui/SettingsConsentWorkflowTest.kt
[SharedArtifactWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/tools/SharedArtifactWorkflowTest.kt
[ShellCourseCreationWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/courses/ShellCourseCreationWorkflowTest.kt
[ShellCourseHostWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/courses/ShellCourseHostWorkflowTest.kt
[ShellNavigationWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/ui/ShellNavigationWorkflowTest.kt
[ShellStorageTest]: ../app/src/androidTest/java/com/alpha/assistant/data/ShellStorageTest.kt
[ShellViewModel:1212]: ../app/src/main/java/com/alpha/assistant/ui/ShellViewModel.kt#L1212
[ShellViewModel:194]: ../app/src/main/java/com/alpha/assistant/ui/ShellViewModel.kt#L194
[ShellViewModel:506]: ../app/src/main/java/com/alpha/assistant/ui/ShellViewModel.kt#L506
[ShellViewModel:572]: ../app/src/main/java/com/alpha/assistant/ui/ShellViewModel.kt#L572
[ShellViewModel:666]: ../app/src/main/java/com/alpha/assistant/ui/ShellViewModel.kt#L666
[ShellViewModel:853]: ../app/src/main/java/com/alpha/assistant/ui/ShellViewModel.kt#L853
[ShellVoiceWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/ui/ShellVoiceWorkflowTest.kt
[SourceRefresh:48]: ../app/src/main/java/com/alpha/assistant/scheduling/SourceRefresh.kt#L48
[SourceRefreshJobService:47]: ../app/src/main/java/com/alpha/assistant/scheduling/SourceRefreshJobService.kt#L47
[SourceRefreshWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/scheduling/SourceRefreshWorkflowTest.kt
[StandaloneClockedHostWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/tools/generation/StandaloneClockedHostWorkflowTest.kt
[StandaloneNativeEditHostWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/tools/generation/StandaloneNativeEditHostWorkflowTest.kt
[StandaloneNativeEditWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/tools/generation/StandaloneNativeEditWorkflowTest.kt
[StandaloneRefinementShellWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/ui/StandaloneRefinementShellWorkflowTest.kt
[StaticDraftPreviewUiWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/tools/StaticDraftPreviewUiWorkflowTest.kt
[StructuredStateWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/tools/StructuredStateWorkflowTest.kt
[SurfaceBitmapWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/tools/SurfaceBitmapWorkflowTest.kt
[SurfaceDrawing:221]: ../app/src/main/java/com/alpha/assistant/tools/SurfaceDrawing.kt#L221
[SurfaceEntry:56]: ../app/src/main/java/com/alpha/assistant/surfaces/SurfaceEntry.kt#L56
[SurfaceInput:19]: ../app/src/main/java/com/alpha/assistant/tools/SurfaceInput.kt#L19
[SurfaceWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/surfaces/SurfaceWorkflowTest.kt
[TOneStreamingTranscriber:110]: ../app/src/main/java/com/offlineassistant/app/asr/TOneStreamingTranscriber.kt#L110
[ToolCancellationTest]: ../app/src/androidTest/java/com/alpha/assistant/tools/ToolCancellationTest.kt
[ToolController:1086]: ../app/src/main/java/com/alpha/assistant/tools/ToolController.kt#L1086
[ToolController:650]: ../app/src/main/java/com/alpha/assistant/tools/ToolController.kt#L650
[ToolControllerDraftPreviewWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/tools/ToolControllerDraftPreviewWorkflowTest.kt
[ToolEngine:1265]: ../app/src/main/java/com/alpha/assistant/tools/ToolEngine.kt#L1265
[ToolEngine:258]: ../app/src/main/java/com/alpha/assistant/tools/ToolEngine.kt#L258
[ToolEngine:796]: ../app/src/main/java/com/alpha/assistant/tools/ToolEngine.kt#L796
[ToolEngine:885]: ../app/src/main/java/com/alpha/assistant/tools/ToolEngine.kt#L885
[ToolLibraryStorage:40]: ../app/src/main/java/com/alpha/assistant/tools/ToolLibraryStorage.kt#L40
[ToolNetworkEffectWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/tools/ToolNetworkEffectWorkflowTest.kt
[ToolNetworkGrantWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/tools/ToolNetworkGrantWorkflowTest.kt
[ToolNetworkGrants:104]: ../app/src/main/java/com/alpha/assistant/tools/ToolNetworkGrants.kt#L104
[ToolParts:1031]: ../app/src/main/java/com/alpha/assistant/tools/generation/ToolParts.kt#L1031
[ToolParts:1101]: ../app/src/main/java/com/alpha/assistant/tools/generation/ToolParts.kt#L1101
[ToolParts:1213]: ../app/src/main/java/com/alpha/assistant/tools/generation/ToolParts.kt#L1213
[ToolParts:367]: ../app/src/main/java/com/alpha/assistant/tools/generation/ToolParts.kt#L367
[ToolParts:451]: ../app/src/main/java/com/alpha/assistant/tools/generation/ToolParts.kt#L451
[ToolParts:567]: ../app/src/main/java/com/alpha/assistant/tools/generation/ToolParts.kt#L567
[ToolParts:681]: ../app/src/main/java/com/alpha/assistant/tools/generation/ToolParts.kt#L681
[ToolParts:753]: ../app/src/main/java/com/alpha/assistant/tools/generation/ToolParts.kt#L753
[ToolParts:909]: ../app/src/main/java/com/alpha/assistant/tools/generation/ToolParts.kt#L909
[ToolPortabilityDialog:37]: ../app/src/main/java/com/alpha/assistant/tools/ToolPortabilityDialog.kt#L37
[ToolRecords:207]: ../app/src/main/java/com/alpha/assistant/tools/ToolRecords.kt#L207
[ToolStorageRecoveryWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/tools/ToolStorageRecoveryWorkflowTest.kt
[ToolStore:399]: ../app/src/main/java/com/alpha/assistant/tools/ToolStore.kt#L399
[ToolStore:517]: ../app/src/main/java/com/alpha/assistant/tools/ToolStore.kt#L517
[ToolStore:628]: ../app/src/main/java/com/alpha/assistant/tools/ToolStore.kt#L628
[ToolStore:658]: ../app/src/main/java/com/alpha/assistant/tools/ToolStore.kt#L658
[ToolUi:444]: ../app/src/main/java/com/alpha/assistant/tools/ToolUi.kt#L444
[ToolVideoPlaybackWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/tools/ToolVideoPlaybackWorkflowTest.kt
[ToolWidgets:127]: ../app/src/main/java/com/alpha/assistant/surfaces/ToolWidgets.kt#L57
[ToolWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/tools/ToolWorkflowTest.kt
[VoiceSession:48]: ../app/src/main/java/com/alpha/assistant/voice/VoiceSession.kt#L48
[WeatherLocationAccess:15]: ../app/src/main/java/com/alpha/assistant/ui/WeatherLocationAccess.kt#L15
[WeatherWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/weather/WeatherWorkflowTest.kt
[WidgetAuthoring:95]: ../app/src/main/java/com/alpha/assistant/surfaces/widgetauthoring/WidgetAuthoring.kt#L95
[WidgetAuthoringTest]: ../app/src/androidTest/java/com/alpha/assistant/surfaces/widgetauthoring/WidgetAuthoringTest.kt
[WidgetLayoutWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/surfaces/WidgetLayoutWorkflowTest.kt
[ZipTransportWorkflowTest]: ../app/src/androidTest/java/com/alpha/assistant/portability/ZipTransportWorkflowTest.kt
