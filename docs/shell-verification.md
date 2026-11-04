# Shell verification

Failure cases recorded before implementation:

- A new-chat draft must remain separate from every conversation draft.
- A conversation ID, messages, attachment metadata, and selected conversation must survive a database reopen.
- A database write must preserve other conversations and drafts.
- A blank credential must fail without replacing an existing credential.
- A saved credential must round-trip through Android Keystore encryption. Preferences must not contain plaintext.
- A corrupt encrypted credential must return an error and retain the ciphertext.
- Database and credential errors must reach the UI. The app must not delete stored data as recovery.
- Unavailable cloud, speech, tools, and automation must not appear operational.
- Bottom menu, history, and input must remain accessible with Android system insets and the keyboard.

The instrumentation checks below use the real Android database and Keystore. The shell workflow uses Compose semantics on the connected device. Device API, commands, inputs, expected results, source hashes, and observed results belong in `docs/evidence/shell/`.
