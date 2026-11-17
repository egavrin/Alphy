# Cloud integration

Alphy connects to one AI service. The user enters one API key from OpenAI, Anthropic, OpenRouter, or DeepSeek, and Alphy does not ask for a second key. That key runs chat, web search, image input, image generation, voice input, and voice output. The spec is `docs/superpowers/specs/2026-11-16-unified-ai-provider-design.md`.

## Account and key

`data/SecureKeyStore.kt` holds the account in the preference file `alpha_ai_credentials`, with the Android Keystore alias `alpha.ai.v1`. One commit writes the key, the provider ID (`openai`, `anthropic`, `openrouter`, or `deepseek`), and the consent record. The consent identity is the IV of the key. Alphy never reads the old stores (`alpha_credentials` and `alpha_images_credentials`) for requests. `data/LegacyKeys.kt` only reports an old key. «Сервис ИИ» then shows a card with «Использовать» and «Удалить».

`cloud/AiProvider.kt` detects the provider from the key locally:

- `sk-ant-` gives Anthropic.
- `sk-or-` gives OpenRouter.
- `sk-proj-` and `sk-svcacct-` give OpenAI.
- `sk-` followed by exactly 32 lowercase hexadecimal characters gives DeepSeek.
- Any other `sk-` gives OpenAI. Any other key gives no provider, and the user picks one.

The user can change the detected provider in the provider menu. The key goes only to the selected provider. An Anthropic admin key (`sk-ant-admin`) is refused.

«Согласиться и сохранить» first checks the key with `cloud/KeyCheck.kt`, and saves it afterwards:

| Result | Action |
| --- | --- |
| HTTP 200 | Save. A DeepSeek key with an empty balance saves and shows that the balance is empty. |
| HTTP 401 | Do not save. «<provider> отклонил ключ». |
| HTTP 402 | Save (`NO_CREDIT`), and show that the account has no credit. |
| HTTP 403 | Save, and show that the key has limited permissions. |
| Any other status | Do not save (`UNREACHABLE`). «Не удалось проверить ключ». The typed key stays in the field. |
| Network failure | Do not save. «Не удалось проверить ключ». The typed key stays in the field. |

The consent text names the provider and lists the data that Alphy sends. With OpenRouter, it adds that OpenRouter passes the request to the provider of the selected model. The OpenRouter consent names the reply text for cloud voice. The OpenAI, OpenRouter, and DeepSeek consent names the voice recording. `cloudKey()` refuses a request without a key, a valid format, or consent. The chat and the mini-app page then show «Открыть настройки».

A key from a different provider replaces the account in one commit. The model choices reset to the defaults of the new provider. Conversations, mini-apps, and memory stay. Agent automations and scheduled refresh stop, because the consent identity changes. «Удалить ключ» clears the account and stops all cloud requests.

## Providers and capabilities

`cloud/ProviderProfile.kt` holds the pinned data of each provider: base URL, paths, curated chat models, speech models, transcription request, output token limit, and reasoning controls.

| | OpenRouter | OpenAI | Anthropic | DeepSeek |
| --- | --- | --- | --- | --- |
| Wire format | Chat Completions | Responses API | Messages API | Chat Completions |
| Chat models | `openai/gpt-6.1-terra` | `gpt-6.1-terra`, `gpt-6.1-sol` | `claude-sonnet-5-5`, `claude-opus-5-5` | `deepseek-flash` |
| Web search | yes | yes | yes | yes |
| Image input | yes | yes | yes | yes |
| Image generation | yes | yes | no | no |
| Cloud voice | yes | no | no | no |
| Voice input | provider | provider | phone | provider |

An error text names the active provider, for example «OpenRouter: недостаточно средств». `cloud/ProviderErrors.kt` maps the HTTP statuses of each provider to the codes `auth`, `quota`, `rate_limit`, `unavailable`, and `refusal`.

## Provider contract

`ProviderProfile` (`cloud/ProviderProfile.kt`) owns each provider fact that a request reads. A capability flag implies a request path for that function: `CLOUD_VOICE` is set exactly when `speechWire(provider)` is not null, and `IMAGES` exactly when `imageProviders` has an entry. `AiProviderTest.capabilityFlagsHaveRequestPaths` checks both.

`cloud/CloudProvider.kt` defines `ChatProvider`, `CloudRequest`, `CloudTurn`, `CloudEvent`, and `CloudSession`. `ShellViewModel` accepts the provider, database, and encrypted credential store through its constructor. `AssistantSession` builds the production `ShellViewModel`, and tests pass isolated stores. This seam permits a complete device workflow with an HTTP server on loopback, separate SQLite data, and separate Keystore aliases.

Alphy logic is separate from the wire format. The Alphy logic covers rounds, the decision step, search rounds, grounding, citation checks, correction retries, memory injection, and events. A wire format interface covers the request body, the streaming parser, tool call accumulation, JSON output, reasoning control and replay, token limits, and error mapping. The production `ChatProvider` routes by the stored account.

- OpenRouter and DeepSeek use `cloud/OpenAiCompatibleWire.kt`. DeepSeek uses `/beta/chat/completions` for strict tools and replays `reasoning_content`. OpenRouter replays `reasoning_details` and sends `provider: {"require_parameters": true, "data_collection": "deny"}` with every chat and search request.
- OpenAI uses `cloud/OpenAiResponsesWire.kt`: `POST /v1/responses` with `stream: true` and `store: false`, so OpenAI keeps no copy of the conversation.
- Anthropic uses `cloud/AnthropicWire.kt`: `POST /v1/messages` with streamed events. Thinking is adaptive, and effort is the only control.

Every wire format keeps the same checks. A response needs a terminal stream marker and a successful finish reason. Empty, malformed, truncated, and token-limited responses produce explicit errors. A refusal gives the `refusal` code. The provider does not log requests, responses, keys, or image data. It rejects redirects. Network operations use IO dispatchers, connection timeouts, and read timeouts.

## Structured decision

The decision step and the search plan need a forced tool call. OpenAI, OpenRouter, and DeepSeek keep forced strict tools. Anthropic cannot force a tool, so its wire converts the forced tools into one JSON schema and sends it as `output_config.format`. The wire turns the JSON reply into tool calls. Alphy logic validates the calls as before, with the limit of one decision and two searches.

Each step of the app agent asks for one JSON object without tools. OpenRouter and DeepSeek send the `json_object` response format. OpenAI sends `text.format` with `json_object`. Anthropic gets one system instruction. The current validation and repair stay.

The history, message, and attachment limits are the same for every provider. The app sends at most 20 completed question-and-answer pairs, within a 48,000-character history budget, and the current message separately. Previous image bytes and document URIs never enter later requests automatically. Current messages have a 16,000-character limit.

A saved mini-app can call `ai.ask` from an explicit action in a visible, focused window. The active provider receives the text supplied by that action. The host uses the existing key consent, independently of the mini-app network grant. The host cancels the request when the last eligible window loses focus. A widget layout request sends the user's instruction and allowed binding and action names. The host reads saved values locally to show the preview.

Reference contracts of each provider:

- [OpenAI API reference](https://platform.openai.com/docs/api-reference)
- [Anthropic API reference](https://docs.anthropic.com/en/api)
- [OpenRouter API reference](https://openrouter.ai/docs/api-reference/overview)
- [DeepSeek API documentation](https://api-docs.deepseek.com/)

## Search permission and results

`ShellViewModel.send()` permits web search by default. The consent at key entry covers search queries. The provider first validates a complete typed decision. If the decision permits search, a separate round requests typed `web_search` calls. The provider validates each call's name, ID, argument shape, and query length before it runs either search. A request permits at most two searches. Only the latest user request and approved weather context supply search terms.

Each provider runs a search as follows (`cloud/WebSearch.kt`):

- OpenRouter: Chat Completions with the `openrouter:web_search` tool (engine `exa`, five results). The excerpt comes from the annotation content.
- OpenAI: the Responses API with the `web_search` tool and `include: ["web_search_call.action.sources"]`. The excerpt comes from the answer text that cites the URL.
- Anthropic: Messages with the `web_search_20260209` tool and `max_uses: 1`. The excerpt comes from the `cited_text` values of the URL. The date comes from `page_age` when Alphy can parse it.
- DeepSeek: `POST /v1/tools/search` with `count = 5` and `need_content = true`, without a model call. The date comes from `date`.

A search returns at most five sources, each with a valid URL and a title, and an excerpt of at most 6,000 characters. Only HTTP or HTTPS result URLs with a host and no embedded credentials pass. Site names and icons appear only when the provider supplies them. A scheduled agent search needs a publication date, so a source without one does not pass a dated query. The model of the other three providers returns an ISO date for each source. The last round receives bounded source excerpts in search order. The UI reports search progress, empty results, and errors separately. The provider keeps HTTP and network errors distinct from empty search results.

The chat shows source chips in citation order. Each chip opens the result in the browser. The app requests a provider icon only from a public HTTPS address on port 443. It sends no credentials and follows no redirects. Each request has a four-second limit, and the app cancels the request when its chip leaves the composition. It uses a local icon when the response is absent, invalid, larger than 64 KiB, or more than 256 pixels on either side.

An answer uses a tool-free text completion, including when it needs no search. The text can appear in parts. The original decision fixes the operation and search policy. Before the provider accepts a searched reply, it checks cited URLs against the allowed sources and checks that citation numbers refer to supplied results. The reply includes the source limitation notice. App creation, app refinement, and clarification decisions stay buffered.

## Image scope and validation

The system picker accepts JPEG, PNG, GIF, and WebP. The app retains the Android document read grant and stores URI metadata. Selection checks the URI scheme, declared MIME type, file signature, decoded image, byte count, and dimensions. The application limit is four images per request, 5 MiB per image, and 8192 pixels per side. These application limits keep base64 requests below the documented provider body limit.

The composer shows a sampled image preview and a removal action. The app reads and validates the bytes again immediately before submission. Before it sends a picture, the app shrinks the long edge to 2048 pixels or less. A PNG stays PNG, and other formats become JPEG. A picture that fits is sent unchanged. The wire format of the provider carries the picture: an `image_url` data URL on OpenRouter and DeepSeek, an `input_image` item on OpenAI, and an `image` block on Anthropic. The optional screenshot review of the agent loop (`GenerationSettings.screenshotReview`, off by default) is the vision step of mini-app generation. All four providers accept images. Revoked or missing content produces an actionable error. A retry reads the same selected images again. Later text turns do not reuse their bytes. A stored image reference is not a copy of the source file.

## Image generation

Only OpenRouter and OpenAI keys generate images. With an Anthropic or DeepSeek key, the switch «Разрешить генерацию изображений» is off and the planner gets no image tool. The switch lives in «Сервис ИИ». The image model choice lives in «Модели» and lists only the models of the active provider.

- OpenRouter: Qwen Image 2.1 Turbo and Seedream 4.5, each with its fixed host.
- OpenAI: `gpt-image-2.6-flare` and `gpt-image-2.6-sunburst` through `POST /v1/images/generations`. Alphy reads `data[0].b64_json`.

When a provider has no size for an aspect, Alphy requests the nearest wider size and cuts the center to the target ratio on the device. The per-request paid confirmation, the reservation rules, and the four-image limit stay.

## Durable state and cancellation

The SQLite schema remains version 1. A source record contains `title`, `url`, and bounded `summary`. When the search response supplies them, the provider also stores a bounded `siteName` and an HTTPS `siteIcon` URL. Older rows load with empty site metadata. Credentials stay in encrypted storage.

A submission atomically stores the new conversation and consumes its draft, or replaces the selected conversation row. It records the user message and a stable assistant placeholder before a network call. A failed initial write blocks transmission. Assistant statuses are `pending`, `streaming`, `complete`, `error`, `cancelled`, and `interrupted`. The user status distinguishes an unsent message from a submitted request. Existing `not_sent` messages remain local history.

Live answer fragments stay in memory as incomplete plain text. TalkBack reads the accepted answer when it arrives. Cancellation saves any received text with an incomplete label. If validation fails, the app discards the answer text and keeps the sources and error. Only completed replies enter model history. A restart marks pending or streaming replies as interrupted and offers retry. An abrupt exit loses the live text held in memory.

Each active request owns a connection, coroutine, conversation ID, and assistant message ID. Callbacks check request identity. Cancellation stores a terminal state before it permits another request. It disconnects only the canceled request's connection. A late completion cannot change a new conversation or request. Cancellation cannot undo provider work or charges already incurred.

## Device verification

`CloudWorkflowTest` uses the real HTTP implementation, SQLite, Android Keystore, image decoder, and native Compose UI on the authorized OPPO device. Only synthetic messages and `alpha-test-only` credentials enter the loopback fixture. The fixture uses unique database, preference, and Keystore names. It deletes only its own generated data. `CloudTestActivity` exists only in debug builds and is not exported. The release app does not permit cleartext traffic.

`ShellWorkflowTest` now uses the debug test activity and isolated storage. Its provider is a fixed navigation fixture. This prevents the existing navigation check from transmitting data through the user's production key.

The focused device run covers consent at key entry, fragmented streamed tool calls, search endpoint requests, real fixture URLs, result display, image byte equality, one-time image scope, image MIME mismatch, the size limit, revoked content, cancellation isolation, authentication errors, retry ownership, missing credentials, unreadable credentials, empty search results, search balance errors, and interrupted-request recovery. Evidence resides in `docs/evidence/cloud/`.

No live provider request was made by this document's device checks. The fixtures answer in the wire format of each provider. Actual provider account access and billing remain unverified. API 29 hardware, injected disk outages, and every supported image format remain outside this package's device check. The coordinating root owns the required read-only review and final acceptance.
