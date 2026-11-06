# Plan 3. Mini-app lifecycle

The [Alphy UI design](../specs/2026-11-03-alphy-ui-design.md) defines the mini-app lifecycle in section 5.3. The roadmap also assigns cards, library controls, and generated color roles to Plan 3.

## Storage and shared state

- Store the change label and time with each revision.
- Store the last chat separately from the original chat. Update the last chat when the host places a card there.
- Store desktop placement per mini-app.
- Resolve card references to the current mini-app. Use the same artifact coordinator for actions and state across hosts.
- Give one host control of frames. A host closes only its own session.
- Draw the shared viewport on each host. Map input from the host dimensions. Restore the successor viewport when the frame host closes.
- Open schema version 8. Reject an unsupported schema and preserve its data.

## Refinement and restore

- Exclude live state, rendered UI, and collection data from each provider request.
- Keep the existing stricter filter for private mini-apps.
- Restore source and declared UI from the selected revision. Keep the current state schema, defaults, collections, and live data.
- Validate a render before commit. Reject state changes, effects, record operations, and stale tokens.
- Stop dependent automations when a revision changes. Record the automation IDs and names with the new revision.
- Record the pause before the tool database commits. Record the operation outcome after that commit.

## Deletion

- Capture the mini-app and its dependent records, bindings, and automations before confirmation.
- Require a new confirmation if the scope changes.
- Order deletion through the automation gate, artifact coordinator, and effect synchronization.
- Delete only owner-scoped local records. Keep external documents, calendar events, shared access, chats, credentials, and automation definitions and history.
- Store a cleanup journal in the deletion transaction. Retry external cleanup until it succeeds.
- Disable supported launcher shortcuts and replace retained widgets with a deleted placeholder.

## Verification

Run the structured-state and library workflows through the Compose acceptance runner on `emulator-5556`.
Check restore with new fields and existing collection tokens. Check invalid renders, stale commands, provider input, and unsupported schemas.
Check confirmation without mutation, shared resource access, stale callbacks, and recovery after interrupted cleanup.
Use the synthetic Android calendar fixture to check that deletion preserves an external event.
Grant the test app Android calendar access before this workflow.
