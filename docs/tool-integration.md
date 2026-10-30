# Native tool workflow

`ToolGenerationService` is the shared cloud boundary for the chat and library entry points. It uses the injected `ChatProvider` and a request-specific `CloudSession`. Generation sends the request and, for refinement, the current source and state. It sends selected image bytes only for the current confirmed request. Retry omits images. Generation does not reuse chat history or old image references.

`ToolSpec` defines format version 1. The host accepts column, row, text, button, input, and toggle components. It rejects unknown properties and capabilities. State contains at most 40 named scalar fields. The permitted types are string, number, and boolean. State has a 16 KiB limit. The layout has an 80-node limit and an eight-level depth limit. Source has a 32 KiB limit. The enclosing specification retains the sandbox JSON size and depth limits.

Every control action has a unique identifier. The typed executor checks the identifier against the current validated layout. Button events have no value. Input events contain a bounded string. Toggle events contain a boolean. Device actions are unavailable. A generated capability request fails validation.

Generated JavaScript remains a function body that runs only through `SandboxClient` in the existing isolated process. The input contains `artifactId`, `revision`, `state`, and `event`. Supported event types are `preview`, `render`, and `action`. Source returns `state` and an optional native `ui` object. The host validates every returned state and layout before persistence. Preview and rollback render must preserve state. Native preview controls are disabled.

`ToolEngine` owns transitions. Each new draft has a stable identifier. That identifier becomes the accepted artifact identifier. Refinement retains the artifact identifier. The revision increases at acceptance or rollback. The state version increases at each accepted transition or local interaction. Stale revision or state tokens fail before execution or acceptance. The engine serializes transitions with a mutex.

`ToolStore` uses a separate `alpha-tools.db` database. Existing conversations and credentials do not require a migration. Accepted tools, revision snapshots, and pending drafts are durable. A failed generation leaves the draft and working artifact available. Acceptance atomically saves the revision and removes its draft. Rejection removes only the selected draft. Runtime failures do not persist a new state. Rollback appends a revision and preserves current typed state. If the earlier schema cannot accept current state, rollback fails rather than discarding data.

`ToolController` exposes the native workflow to Compose. Chat messages can supply a request without replacing the chat draft. Accepted artifacts linked to the selected conversation appear as chat cards. The same controller serves the full tool surface and library. A tool remains available before the user explicitly saves it to the library.

Current limits: state is flat and scalar. A checklist can use named boolean fields within the state limit. Generated rows use vertical flow for legibility on narrow displays. Device capabilities, widgets, automations, arbitrary WebViews, and generated Java or Kotlin execution are unavailable. Invalid generations require an explicit retry or a new request. The service does not make an automatic repair call.
