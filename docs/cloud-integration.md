# Cloud chat integration

Package 3A connects the native shell to DeepSeek. It keeps the native sandbox unchanged. Local speech, generated tools, widgets, calendar access, and automation remain unavailable.

## Provider contract

`cloud/CloudProvider.kt` defines `ChatProvider`, `CloudRequest`, `CloudTurn`, `CloudEvent`, and `CloudSession`. `ShellViewModel` accepts the provider, database, and encrypted credential store through its constructor. The default constructor uses production services. This seam permits a complete device workflow with an HTTP server on loopback, separate SQLite data, and separate Keystore aliases.

`DeepSeekProvider` uses `POST https://api.deepseek.com/chat/completions`, model `deepseek-flash`, non-thinking mode, and streamed SSE responses. It sends the user key as a bearer header. It does not log requests, responses, keys, or image data. It rejects redirects. The implementation requires a terminal stream marker and a successful finish reason. Empty, malformed, truncated, and token-limited responses produce explicit errors. Network operations use IO dispatchers, connection timeouts, and read timeouts.

The composer shows a cloud-processing indicator. Each send or retry requires a confirmation. That confirmation identifies the selected chat, text history scope, image scope, optional search scope, and the user's provider tariff. The app sends at most 20 completed question-and-answer pairs, within a 48,000-character history budget. It sends the current message separately. Previous image bytes and document URIs never enter later requests automatically. Current messages have a 16,000-character limit.

Reference contracts checked for this package:

- [Vision guide](https://api-docs.deepseek.com/guides/vision/)
- [Search guide](https://api-docs.deepseek.com/guides/search/)
- [Chat completion API](https://api-docs.deepseek.com/api/create-chat-completion/)

## Search permission and results

The user enables the DeepSeek web-search checkbox and confirms its scope for the current request. The model can call only the typed `web_search` function. The first model round must request that function. The provider validates the function name, call ID, argument shape, and query length. It permits at most two searches and three model rounds. It does not execute arbitrary model commands or infer device permissions.

The provider sends each search to `POST /v1/tools/search` with `count = 5` and `need_content = false`. It accepts only valid HTTP or HTTPS result URLs with a host and no embedded credentials. It sends bounded result summaries to the model as tool results. Result URLs and titles come from the actual search response. The UI shows those sources as buttons. It reports search progress, empty results, and errors separately. Authentication, balance, request-limit, server, malformed-response, and network failures do not become empty search results.

## Image scope and validation

The system picker accepts JPEG, PNG, GIF, and WebP. The app retains the Android document read grant and stores URI metadata. Selection checks the URI scheme, declared MIME type, file signature, decoded image, byte count, and dimensions. The application limit is four images per request, 5 MiB per image, and 8192 pixels per side. These application limits keep base64 requests below the documented provider body limit.

The composer shows a sampled image preview and a removal action. The app reads and validates the bytes again immediately before submission. It sends the original bytes as a base64 `image_url` data URL in the current user message. Revoked or missing content produces an actionable error. A retry requires confirmation and reads the same selected images again. Later text turns do not reuse their bytes. A stored image reference is not a copy of the source file.

## Durable state and cancellation

The SQLite schema remains version 1. Existing conversation rows remain readable. New JSON message fields are optional when decoded: `replyTo`, `search`, `searchStatus`, `sources`, `error`, and `errorCode`. A source contains `title`, `url`, and bounded `summary`. No credential enters this database. Stale processing-mode rows can stay in the `settings` table, and the app ignores them.

A submission atomically stores the new conversation and consumes its draft, or replaces the selected conversation row. It records the user message and a stable assistant placeholder before a network call. A failed initial write blocks transmission. Assistant statuses are `pending`, `streaming`, `complete`, `error`, `cancelled`, and `interrupted`. The user status distinguishes an unsent message from a submitted request. Existing `not_sent` messages remain local history.

The UI updates for each content delta. It queues progress snapshots at most once per 500 ms and always queues terminal state. Normal serial writes preserve draft changes. A process restart converts pending or streaming assistant messages to `interrupted`, retains their saved partial text, and offers retry. The final fraction of a second of streamed text can remain unsaved if the process stops abruptly.

Each active request owns a connection, coroutine, conversation ID, and assistant message ID. Callbacks check request identity. Cancellation stores a terminal state before it permits another request. It disconnects only the canceled request's connection. A late completion cannot change a new conversation or request. Cancellation cannot undo provider work or charges already incurred.

## Device verification

`CloudWorkflowTest` uses the real HTTP implementation, SQLite, Android Keystore, image decoder, and native Compose UI on the authorized OPPO device. Only synthetic messages and `alpha-test-only` credentials enter the loopback fixture. The fixture uses unique database, preference, and Keystore names. It deletes only its own generated data. `CloudTestActivity` exists only in debug builds and is not exported. The release app does not permit cleartext traffic.

`ShellWorkflowTest` now uses the debug test activity and isolated storage. Its provider is a fixed navigation fixture. This prevents the existing navigation check from transmitting data through the user's production key.

The focused device run covers consent before transmission, fragmented streamed tool calls, search endpoint requests, real fixture URLs, result display, image byte equality, one-time image scope, image MIME mismatch, the size limit, revoked content, cancellation isolation, authentication errors, retry ownership, missing credentials, unreadable credentials, empty search results, search balance errors, and interrupted-request recovery. Evidence resides in `docs/evidence/cloud/`.

No live DeepSeek request was made. Actual provider account access and billing remain unverified. API 29 hardware, injected disk outages, and every supported image format remain outside this package's device check. The coordinating root owns the required read-only review and final acceptance.
