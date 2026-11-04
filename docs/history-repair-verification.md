# Conversation management repair

Failure cases recorded before the repair:

- A blank rename must fail and preserve the title, ID, messages, and draft.
- A valid rename must survive database reopen and must not change another conversation.
- Deletion must remove only the selected conversation row and its own draft metadata.
- Deletion of the current conversation must return selection to the independent new-chat draft.
- Deletion of another conversation must preserve the current selection and draft.
- Clear-history must require explicit confirmation and identify the exact scope.
- Clear-history must preserve the new-chat draft, credentials, tools, tool data, automation rules, and attachment source files.
- An active cloud request must be cancelled before its conversation is removed. A late response must not recreate it.
- A failed database deletion must remain visible as an unsaved change. Retry must repeat the deletion rather than resurrect the conversation.

`HistoryManagementWorkflowTest` uses a random database name, random credential alias, isolated tool and automation stores, and a fixture attachment file. It never reads production credentials or opens the production conversation database. `HistoryStorageTest` covers transactional selected-ID handling and a forced deletion failure with isolated SQLite data.

The device is reserved by the root. Only compilation is authorized until the root supplies `device-ready`.

## Compile evidence

The following commands passed from `/Users/egavrin/Documents/alpha`:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ./gradlew :app:compileDebugAndroidTestKotlin
```

Logs and changed-source hashes are in `docs/evidence/history-repair/`. Device tests were not run because the root reserved the device. The root owns installation and execution after review. The focused test classes are `com.alpha.assistant.data.HistoryStorageTest` and `com.alpha.assistant.ui.HistoryManagementWorkflowTest`.

The repair adds transactional rename, delete-one, and clear-history operations to `ShellStore`. It does not open tool, automation, or credential stores. The ViewModel cancels a deleted conversation’s active request, preserves the separate new-chat draft, and retains deletion IDs for save retry. The history UI confirms deletion scope. Clear-history applies to all conversations even when search filters the list.
