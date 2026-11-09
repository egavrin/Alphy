# Cloud chat integration

Package 3A connects the native shell to DeepSeek. It keeps the native sandbox unchanged. Local speech, generated tools, widgets, calendar access, and automation remain unavailable.

## Provider contract

`cloud/CloudProvider.kt` defines `ChatProvider`, `CloudRequest`, `CloudTurn`, `CloudEvent`, and `CloudSession`. `ShellViewModel` accepts the provider, database, and encrypted credential store through its constructor. The default constructor uses production services. This seam permits a complete device workflow with an HTTP server on loopback, separate SQLite data, and separate Keystore aliases.

`DeepSeekProvider` uses `POST https://api.deepseek.com/chat/completions`, model `deepseek-flash`, non-thinking mode, and streamed SSE responses. It sends the user key as a bearer header. It does not log requests, responses, keys, or image data. It rejects redirects. The implementation requires a terminal stream marker and a successful finish reason. Empty, malformed, truncated, and token-limited responses produce explicit errors. Network operations use IO dispatchers, connection timeouts, and read timeouts.

The user gives consent one time, at key entry. `cloudKey()` refuses a request without that consent. The chat and the mini-app page then show «Открыть настройки». The app sends at most 20 completed question-and-answer pairs, within a 48,000-character history budget. It sends the current message separately. Previous image bytes and document URIs never enter later requests automatically. Current messages have a 16,000-character limit.

A saved mini-app can call `ai.ask` from an explicit action in a visible, focused window. DeepSeek receives the text supplied by that action. The host uses the existing key consent, independently of the mini-app network grant. The host cancels the request when the last eligible window loses focus. A widget layout request sends the user's instruction and allowed binding and action names. The host reads saved values locally to show the preview.

Reference contracts checked for this package:

- [Vision guide](https://api-docs.deepseek.com/guides/vision/)
- [Search guide](https://api-docs.deepseek.com/guides/search/)
- [Chat completion API](https://api-docs.deepseek.com/api/create-chat-completion/)

## Search permission and results

`ShellViewModel.send()` permits web search by default. The consent at key entry covers search queries. The provider first validates a complete typed decision. If the decision permits search, a separate round requests typed `web_search` calls. The provider validates each call's name, ID, argument shape, and query length before it runs either search. A request permits at most two searches. Only the latest user request and approved weather context supply search terms.

The provider sends each search to `POST /v1/tools/search` with `count = 5` and `need_content = true`. It accepts HTTP or HTTPS result URLs with a host and no embedded credentials. The last round receives bounded source excerpts in search order. The UI reports search progress, empty results, and errors separately. The provider keeps HTTP and network errors distinct from empty search results.

The chat shows source chips in citation order. Each chip opens the result in the browser. The app requests a provider icon only from a public HTTPS address on port 443. It sends no credentials and follows no redirects. Each request has a four-second limit, and the app cancels the request when its chip leaves the composition. It uses a local icon when the response is absent, invalid, larger than 64 KiB, or more than 256 pixels on either side.

An answer uses a tool-free text completion, including when it needs no search. The text can appear in parts. The original decision fixes the operation and search policy. Before the provider accepts a searched reply, it checks cited URLs against the allowed sources and checks that citation numbers refer to supplied results. The reply includes the source limitation notice. App creation, app refinement, and clarification decisions stay buffered.

## Image scope and validation

The system picker accepts JPEG, PNG, GIF, and WebP. The app retains the Android document read grant and stores URI metadata. Selection checks the URI scheme, declared MIME type, file signature, decoded image, byte count, and dimensions. The application limit is four images per request, 5 MiB per image, and 8192 pixels per side. These application limits keep base64 requests below the documented provider body limit.

The composer shows a sampled image preview and a removal action. The app reads and validates the bytes again immediately before submission. It sends the original bytes as a base64 `image_url` data URL in the current user message. Revoked or missing content produces an actionable error. A retry reads the same selected images again. Later text turns do not reuse their bytes. A stored image reference is not a copy of the source file.

## Durable state and cancellation

The SQLite schema remains version 1. A source record contains `title`, `url`, and bounded `summary`. When the search response supplies them, the provider also stores a bounded `siteName` and an HTTPS `siteIcon` URL. Older rows load with empty site metadata. Credentials stay in encrypted storage.

A submission atomically stores the new conversation and consumes its draft, or replaces the selected conversation row. It records the user message and a stable assistant placeholder before a network call. A failed initial write blocks transmission. Assistant statuses are `pending`, `streaming`, `complete`, `error`, `cancelled`, and `interrupted`. The user status distinguishes an unsent message from a submitted request. Existing `not_sent` messages remain local history.

Live answer fragments stay in memory as incomplete plain text. TalkBack reads the accepted answer when it arrives. Cancellation saves any received text with an incomplete label. If validation fails, the app discards the answer text and keeps the sources and error. Only completed replies enter model history. A restart marks pending or streaming replies as interrupted and offers retry. An abrupt exit loses the live text held in memory.

Each active request owns a connection, coroutine, conversation ID, and assistant message ID. Callbacks check request identity. Cancellation stores a terminal state before it permits another request. It disconnects only the canceled request's connection. A late completion cannot change a new conversation or request. Cancellation cannot undo provider work or charges already incurred.

## Device verification

`CloudWorkflowTest` uses the real HTTP implementation, SQLite, Android Keystore, image decoder, and native Compose UI on the authorized OPPO device. Only synthetic messages and `alpha-test-only` credentials enter the loopback fixture. The fixture uses unique database, preference, and Keystore names. It deletes only its own generated data. `CloudTestActivity` exists only in debug builds and is not exported. The release app does not permit cleartext traffic.

`ShellWorkflowTest` now uses the debug test activity and isolated storage. Its provider is a fixed navigation fixture. This prevents the existing navigation check from transmitting data through the user's production key.

The focused device run covers consent at key entry, fragmented streamed tool calls, search endpoint requests, real fixture URLs, result display, image byte equality, one-time image scope, image MIME mismatch, the size limit, revoked content, cancellation isolation, authentication errors, retry ownership, missing credentials, unreadable credentials, empty search results, search balance errors, and interrupted-request recovery. Evidence resides in `docs/evidence/cloud/`.

No live DeepSeek request was made. Actual provider account access and billing remain unverified. API 29 hardware, injected disk outages, and every supported image format remain outside this package's device check. The coordinating root owns the required read-only review and final acceptance.
