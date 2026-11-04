# Native shell integration

Package 2 implements the native Russian shell. Package 3A adds cloud chat, image input, and web search. See [cloud integration](cloud-integration.md) for current provider behavior. Local speech, generated tools, widgets, and automation remain unavailable. The existing sandbox source and tests remain unchanged.

## Persistent state

`data/ShellStore.kt` owns `alpha.db`, schema version 1. It uses the Android SQLite implementation. The `conversations` table has a stable string ID, update time, and one JSON body per conversation. The body contains the title, timestamps, draft, messages, and attachment metadata. A single row replacement is atomic. `createFromNewDraft()` uses one transaction for the conversation, selected ID, and removal of the separate new-chat draft.

The `settings` table stores `selected` and `newDraft`. Stale `theme` and `mode` rows can stay, and the app ignores them. A new-chat draft has no conversation ID. `ShellSnapshot.selected` and `ShellSnapshot.draft` resolve the active conversation and its own draft. History search includes titles, messages, and drafts. The app preserves unknown storage failures and displays an error. It does not delete or reset a failed database. A future database version requires an explicit migration.

`Attachment` contains a document URI, display name, and MIME type. The picker retains the Android read grant. The app does not copy document contents into the database. A source provider can remove or revoke the document. Later content readers must handle that failure and must not treat URI metadata as document contents. Removal from a draft removes its metadata. It does not delete the source document.

`ui/ShellViewModel.kt` owns the current snapshot and serializes database writes through one IO queue. Draft changes update the UI immediately. Write failures display a persistent error and an explicit retry action. A retry writes the current snapshot in one transaction. `send()` now creates a stable conversation ID on the first confirmed submission. Existing `not_sent` messages remain local history. Cloud integration preserves conversation IDs and draft ownership.

## Credentials

`data/SecureKeyStore.kt` stores the DeepSeek key in private preferences named `alpha_credentials`. The values contain AES-GCM ciphertext and its IV. Android Keystore alias `alpha.deepseek.v1` holds the AES key. `save()`, `read()`, and `clear()` return `Result`. `read()` distinguishes absent credentials from decryption failures. A failed read does not create a replacement key, delete ciphertext, or log the credential. A blank save fails before encryption.

Settings accepts a replacement key without displaying the stored key. A successful save clears the entry field. The entry field is not saved through activity recreation. Settings uses `FLAG_SECURE`. Existing `allowBackup="false"` excludes the application data from Android backup. Provider integration can call `SecureKeyStore(context).read()` in an IO context. It must handle both an absent key and a failed decryption. Key storage does not validate the provider credential.

## UI boundaries

`ui/AlphaShell.kt` owns navigation and the Compose surfaces. The top bar with «Меню» and «Новый чат» and the composer stay visible without chat scrolling. «Меню» opens a drawer from the left. Back closes the drawer, then a page, then the chat. The app uses safe drawing and IME insets. Light and dark follow the system. `AlphaTheme` uses dynamic color on API 31 and later and a fixed palette from `#7652DF` on API 29 and 30. The layout uses available window width. An open tools surface shares the window with chat at 640 dp or greater. A smaller window uses one pane. Navigation and draft state survive activity recreation through saved UI state and the ViewModel.

The tools surface is an empty library until a later package supplies real tools. It currently demonstrates the adaptive pane structure without fake artifacts or calendar entries. Future tool rendering must retain the 640 dp split behavior and document any hinge avoidance requirements. Context, speech, and automation surfaces state their actual status.

## Focused verification

`ShellStorageTest` verifies database reopen, independent drafts, message persistence, attachment metadata, selected conversation, stale theme and mode rows, encrypted key round-trip, blank-key rejection, plaintext exclusion, and ciphertext retention after corruption. `ShellWorkflowTest` uses Android UiAutomation for a black-box workflow. It verifies local save, draft separation, history search, selection, and activity recreation. `SandboxIsolationTest` remains unchanged.

The first Compose-test harness attempt failed before interaction because Android 17 removed `InputManager.getInstance`. The final test uses Android UiAutomation and adds no testing dependency. The retained harness failure is evidence of an environment limitation. It is not the final test result.

Commands, results, source and APK hashes, screenshots, and the process-restart proof are in `docs/evidence/shell/`. Root integration owns the full acceptance matrix. Android 10, large font settings, physical hinge avoidance, document-provider revocation, and injected disk or Keystore outages remain unchecked on hardware.
