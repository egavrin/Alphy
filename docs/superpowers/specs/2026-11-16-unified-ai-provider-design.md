# Unified AI provider design

Status: draft for review, 2026-11-16. The builder check is complete. Revised the same day: DeepSeek stays (1.4), and the selector in the Jev role uses native structured output where the provider has it (5.6, 8.8). The user approved the onboarding design (14.5, 14.6, 14.10) the same day. The user chose the OpenAI Responses wire (15.3, 8.9) and approved the models (7.4). The user dropped the Anthropic SDK (5.5). The user replaced local speech recognition with voice input through the provider, and the phone recognizer for Anthropic (1.6, 12A). The user pointed to the OpenAI Decisions API, and the OpenAI selector uses it (5.6 (d), 8.8).

Labels show the source of each statement:

- (User): the user stated it in the design conversation.
- (Claude): Claude proposed it in the conversation, and the user did not reject it.
- (Claude, new): Claude added it in this document. The user can change it.
- (Claude, builder check): Claude decided it after the builder check. The user can change it.
- (Builder): the builder decides it during implementation.

## 1. Intent

1.1 (User, revised) The user enters one API key from OpenAI, Anthropic, OpenRouter, or DeepSeek. Alphy does not ask for a second key.

1.2 (User) Chat, web search, picture understanding (vision), image generation, and voice output go through the API of that one key.

1.3 (User) The settings must be convenient. The user must not need to know which provider supports which function.

1.4 (User, revised; voice output withdrawn for DeepSeek by 12.7) Keep DeepSeek when its capabilities reach Anthropic's level. (Claude, checked 2026-11-16 in the DeepSeek API documentation) DeepSeek has chat, web search, vision on `deepseek-flash`, and voice output. That is more than Anthropic has, so DeepSeek stays as the fourth provider. The earlier decision to remove DeepSeek is withdrawn.

1.5 (Claude) Why now: Alphy has two keys and two consent texts today. The DeepSeek key runs chat and search. A separate OpenRouter key runs image generation. Voice output does not exist.

1.6 (User, 2026-11-16) Make the app lighter: remove the local model for Russian and English speech recognition, and replace it with a shared path. (User, chosen the same day) Speech goes through the user's key for OpenAI, OpenRouter, and DeepSeek. Anthropic uses the phone recognizer. Text appears only after the user stops speaking.

## 2. Goals, non-goals, anti-goals

Goals:

2.1 One key, one consent, one settings section, one onboarding step.

2.2 Alphy detects the provider from the key and shows which functions work before the user saves the key.

2.3 Chat, web search, and vision work with all four providers. Image generation works with OpenAI and OpenRouter. Cloud voice works with OpenRouter only (12.7). Voice input works with all four: through the provider for OpenAI, OpenRouter, and DeepSeek, and through the phone recognizer for Anthropic (12A).

2.4 Mini-app and game generation keep their current reliability on each supported chat model.

Non-goals:

2.5 (Claude, new) No second, optional key for missing functions. With Anthropic or DeepSeek, image generation is unavailable.

2.6 (User, revised 2026-11-16) No local speech recognition model in the app. The T-One model (144 MB in the APK), the unused Whisper file, and the sherpa-onnx library go (12A.9).

2.7 (Claude, new) No full model catalogs. Alphy offers a short, tested list of models for each provider.

2.8 (Claude, new) No OpenAI cloud voice in this project. The OpenAI `/v1/audio/speech` models shut down on 2027-01-06. The replacement needs the Realtime API over WebSocket.

Anti-goals (a failure even if the code works):

2.9 (Claude, new) A provider change silently breaks mini-app generation. A model that did not pass the live acceptance run (16.6) must not appear in the model list.

2.10 (Claude, new) Alphy deletes a user's old key without an explicit user action. AGENTS.md: "Do not silently delete existing user data or credentials."

2.11 (Claude, new; revised) Alphy sends a key to a provider other than the one shown in the provider control. OpenAI and DeepSeek keys both start with `sk-`.

## 3. Constraints

3.1 AGENTS.md: "Do not preserve compatibility with legacy Alpha versions, formats, interfaces, or protocols." Alphy does not read the old key stores for requests (6.2). The DeepSeek client stays as the DeepSeek profile of the OpenAI-compatible wire (5.1). Strings that name DeepSeek for every provider change to name the active provider.

3.2 AGENTS.md: "Do not silently delete existing user data or credentials."

3.3 AGENTS.md: "Use one executable behavior checker for generated apps and games." and "Test the shared generation path with an app workflow and a game workflow."

3.4 The current cloud contract stays: no logs of keys, requests, responses, or image bytes; redirects refused; bounded reads; connection and read timeouts; cancellation that disconnects only the canceled request; the loopback base URL as the only test seam (`docs/cloud-integration.md`).

3.5 The consent identity (`SecureKeyStore.consentIdentity()`, the IV of the stored key) changes on every key write. Agent automations and scheduled source refresh stop when it changes. This property stays.

3.6 `minSdk = 36`, OkHttp 5.5.1, kotlinx-serialization 1.9.0 are in the build today.

## 4. Provider facts (checked 2026-11-16)

| Function | OpenRouter | OpenAI | Anthropic | DeepSeek |
|---|---|---|---|---|
| Chat | `POST /api/v1/chat/completions` | `POST /v1/responses` (Chat Completions has no tool calls with reasoning on, 4.5) | `POST /v1/messages` | `POST /chat/completions`. Strict tools only on `/beta/chat/completions`. |
| Web search | `openrouter:web_search` server tool (beta). Exa: $0.007 per search, up to 10 results. Results reach the caller as `url_citation` annotations. | Responses API `web_search` tool. `include: ["web_search_call.action.sources"]`. `url_citation` annotations carry URL, title, and location. | `web_search_20260209` server tool. `web_search_tool_result` lists URL, title, `page_age`. Citations carry `cited_text`. | `POST /v1/tools/search`, no model call (Alphy uses it today). Results carry `url`, `title`, `snippet`, `summary`, and `date`. $0.75 per 1,000 searches. |
| Vision | `image_url` data URL. Only models whose `input_modalities` include `image`. | `image_url` data URL, `detail`. Rejects images over 30,000 patches of 32×32 px. | `image` block, `source.type = "base64"`. 10 MB base64 per image, 8000×8000 px maximum. | `image_url` data URL, only on `deepseek-flash`. 32 MiB per image, 8192 px per side. `deepseek-v4-pro` is text only. |
| Image generation | `POST /api/v1/images` (Alphy uses it today) | `POST /v1/images/generations`, `gpt-image-2.6-flare` and `gpt-image-2.6-sunburst`, `b64_json` | none | none |
| Voice output | `POST /api/v1/audio/speech`, OpenAI-compatible, `mp3` or `pcm` | `/v1/audio/speech` shuts down 2027-01-06 | none | Not used by Alphy (12.7). `POST /v1/tools/tts`, `wav` or `pcm`, voices `stella` and `tide`, 10,000 characters per request. Languages are not documented. |
| Voice input (checked 2026-11-16) | `POST /api/v1/audio/transcriptions`, multipart `model` and `file`, or JSON with base64 `input_audio`. 25 MB. Upstream timeout 60 s. Response `text`. | `POST /v1/audio/transcriptions`, multipart `file` and `model` (`gpt-transcribe`). `language` optional (detected when absent). 25 MB. Response `text`. `whisper-1`, `gpt-4o-transcribe`, and `gpt-4o-mini-transcribe` shut down on 2027-02-26. | none (no audio input) | `POST /v1/tools/asr`, multipart `file`, no model or language field. 16-bit PCM WAV or Ogg Opus, at most 8 MiB and 120 s. Response `text`. Lists `ru` and `en`. $0.116 per hour. |
| JSON schema output | `response_format` `json_schema`. Host support needs `provider.require_parameters: true`. | `text.format` `json_schema`, `strict: true` | `output_config.format` `json_schema` | none. Only `json_object`. |
| Typed decisions (checked 2026-11-16) | none | `POST /v1/decisions`, only `gpt-6-luna`. Question types `predicate`, `choice` (2 to 255 values, a probability for each), and `score`. At most 400 questions. Answers in question order, a `refusal` for each refused question. $0.10 per 1M input tokens, output not charged. About 10 times faster than the Responses API (OpenAI Decisions guide). | none | none |
| Key check | `GET /api/v1/key` | `GET /v1/models` | `GET /v1/models` | `GET /user/balance` |
| Key prefix | `sk-or-` | `sk-proj-`, `sk-svcacct-`, `sk-` | `sk-ant-` | `sk-` (format not documented) |

4.1 Only DeepSeek has a search endpoint without a model call.

4.2 Anthropic Claude Opus 5.5 and Claude Sonnet 5.5 reject a forced `tool_choice` (`any` or `tool`). Claude Opus 5.5 cannot turn thinking off. Assistant prefill returns 400 on current Claude models.

4.3 DeepSeek (api-docs.deepseek.com): strict tools need `/beta/chat/completions`, and then every tool must be strict. A forced `tool_choice` (a named function or `"required"`) returns 400 in thinking mode. When a request has tools, the `reasoning_content` of every earlier assistant turn must go back. `deepseek-chat` and `deepseek-reasoner` were shut down on 2026-07-24.

4.4 JSON schema limits:

- OpenAI: the root must be an object, not `anyOf`. Nested `anyOf` is permitted. Every property must be in `required`, and every object needs `additionalProperties: false`. `minItems` and `maxItems` are supported. At most 5,000 properties and 10 nesting levels.
- Anthropic: `anyOf` is supported, and every object needs `additionalProperties: false`. `minItems` accepts only 0 or 1, and `maxItems` is not supported. At most 16 parameters with `anyOf` or type arrays. No beta header.
- OpenRouter: the limits of the host apply. OpenRouter adds the Anthropic structured-output header for Claude models.

4.5 OpenAI GPT-6 models and Chat Completions (developers.openai.com, «Using GPT-6» guide and model pages, checked 2026-11-16):

- GPT-6 Astra, GPT-6.1 Sol, and GPT-6.1 Terra accept Chat Completions, but tool calling requires the Responses API.
- GPT-6 Sol and GPT-6 Luna accept function calls in Chat Completions only with `reasoning_effort: "none"`.
- GPT-6 Astra and GPT-6.1 Sol do not accept effort `none`. GPT-6.1 Terra, GPT-6 Sol, and GPT-6 Luna accept it.
- In the Responses API, the reasoning items of a tool turn must go back with the tool outputs.
- With `store: false`, each reasoning item carries `encrypted_content`. OpenAI stores Responses by default.

So the OpenAI profile cannot use the OpenAI-compatible wire for tool calls with reasoning on. (User) The OpenAI profile uses the Responses API (15.3, 8.9).

2026-11-17, merge of upstream main 892efa0: DeepSeek sends no reasoning replay in discovery, as upstream main does. DeepSeek discovery and search run with thinking off, so the replay sends nothing. The other providers keep the replay rule above. No production request now has thinking together with tools.

## 5. Approaches

5.1 Chat wire format (Claude). Considered:

- (a) One OpenAI-compatible client for all providers, with Anthropic's OpenAI compatibility endpoint. Rejected: Anthropic documents that endpoint as not for production, and it drops strict tools and structured output.
- (b) Everything through OpenRouter. Rejected: the user wants OpenAI and Anthropic keys to work directly.
- (c) Three wire formats (revised 2026-11-16 after 4.5): OpenAI-compatible Chat Completions for OpenRouter and DeepSeek, OpenAI Responses for OpenAI, and Anthropic Messages. **Picked.** OpenRouter and DeepSeek share one client, which is today's DeepSeek client. Each provider has its own profile.

5.2 Search (Claude). Considered:

- (a) A `search(query)` primitive in each provider: one model call with the provider's built-in search tool, mapped to `List<Source>`. **Picked.** It keeps the current flow: decision, search, grounded answer, citation checks. `SourceRefreshJobService` also needs a primitive.
- (b) Built-in search inside the answer call. Fewer calls and better reading of pages. Rejected for now: the citation parsing differs for each provider, and scheduled refresh still needs (a).

5.3 Picture size (Claude). Considered:

- (a) Shrink every outgoing picture on the device to a long edge of 2048 px. **Picked.** One rule fits every provider. Uploads and token costs go down.
- (b) Send the original bytes and shrink only above each provider's limit. Rejected: it needs a limit table for every provider and model.

5.4 Account plumbing (Claude, new). Considered:

- (a) Change `ChatProvider.respond(key: String, …)` to take an account object. Rejected: about 80 test fakes implement `ChatProvider`.
- (b) Keep every provider interface unchanged. One production `ChatProvider` reads the stored account at call time, checks that its key equals the `key` argument, and routes to the wire format of the account's provider. A mismatch is a stale request. **Picked.**

5.5 Anthropic client (Claude, new; revised by the user 2026-11-16). Considered:

- (a) The official Java SDK `com.anthropic:anthropic-java`. Rejected by the user. Task 2 showed that it works, but it added about 53 MiB to the debug APK (426783057 to 482529767 bytes). Alphy needs only one streaming Messages request and one models request.
- (b) Raw HTTP with the current `open()` transport and a new SSE parser. **Picked** (user, 2026-11-16). The other three providers use the same transport, so the rules of 3.4 (redirects refused, bounded reads, timeouts, cancellation) apply without a second HTTP stack.

5.6 Selector in the Jev role (User proposed native output for OpenAI and OpenRouter. Claude checked Anthropic.) The selector picks UI parts from closed lists of choices. It does the job of Jev, the System One model of the TypeSafe API (`2026-11-03-alphy-ui-design.md`, 6.5). The approach is like the typed component catalog of Vercel json-render. Considered:

- (a) Today's DeepSeek call: `json_object`, the answer shape in the prompt, and self-reported probabilities that `JevSelector` checks. DeepSeek has only this (4.3).
- (b) Native JSON schema output, built from the questions. The answer shape is type-safe, as with the real Jev. OpenAI, OpenRouter, and Anthropic have it. **Picked** for these three. DeepSeek keeps (a). Section 8.8 gives the details.
- (c) Token log probabilities for each letter (pattern (a) in the UI design 6.5). Not picked: Anthropic gives no log probabilities. A later measurement can add it for the other providers.
- (d) (User pointed to it, 2026-11-16) The OpenAI Decisions API (`POST /v1/decisions`). It returns typed `predicate` and `choice` answers with a probability for each option: the shape of the Jev answer. **Picked** for OpenAI in place of (b). It is a typed-answer service, not a schema on generated text. OpenAI states that it is about 10 times faster than the Responses API, and output tokens are not charged. (b) stays for OpenRouter and Anthropic. Section 8.8 gives the mapping.

2026-11-17, merge of upstream main 892efa0: the selector has no caller. Upstream main deleted `JevSelector` and the `SELECTOR` stage, and the merge removed the selector path (`OpenAiDecisions`, `responseSchema`, `selectorModel`, `selectorReasoning`, and `jsonSchema`). App generation uses the agent loop (`AppAgent`, stage `AGENT`, JSON object, no tools). Section 5.6 is history.

## 6. Account and key

6.1 (Claude, new) A new encrypted store holds the account: preference file `alpha_ai_credentials`, Keystore alias `alpha.ai.v1`. One commit writes the key, the provider ID (`openai`, `anthropic`, `openrouter`, or `deepseek`), and the consent record. The provider ID is stored as plain text next to the ciphertext. The consent identity stays the IV.

6.2 (Claude, new) Alphy never reads the old stores for requests: `alpha_credentials` with alias `alpha.deepseek.v1` (DeepSeek), and `alpha_images_credentials` with alias `alpha.alpha_images.openrouter.v1` (OpenRouter images). This prevents rule 2.11.

6.3 (Claude) Provider detection is local and immediate:

- `sk-ant-` gives Anthropic.
- `sk-or-` gives OpenRouter.
- `sk-proj-` and `sk-svcacct-` give OpenAI.
- (Claude, revised) `sk-` followed by exactly 32 lowercase hexadecimal characters gives DeepSeek. Assumption: DeepSeek keys have this form. Basis: the keys seen in use, because DeepSeek does not document the format. Test: the user's DeepSeek key and its key check.
- Any other `sk-` gives OpenAI.
- Any other key gives no provider. The user must pick one.
- The key goes only to the provider in the control. Alphy never tries a second provider with the same key (2.11).

6.4 (Claude, new) A segmented control with four options («OpenAI», «Anthropic», «OpenRouter», «DeepSeek») shows the detected provider. The user can change it.

6.5 (Claude, new) «Согласиться и сохранить» first checks the key with the provider's key-check request (section 4). Then it saves the key.

- HTTP 200: save.
- HTTP 401: do not save. Show «Сервис отклонил ключ».
- HTTP 403: save, and show that the key has limited permissions.
- A network failure: do not save. Show «Не удалось проверить ключ» and keep the typed key in the field.
- An Anthropic admin key (`sk-ant-admin`) is rejected before the check.
- DeepSeek: a 200 with `is_available: false` saves the key and shows that the balance is empty.

6.6 (Claude, new) `cloudKey()` keeps its name and its failure order: read failure, missing key, key format, consent. It now reads the new store.

6.7 (Claude, new) A key from a different provider replaces the account in one commit. Model choices reset to the new provider's defaults. Conversations, mini-apps, and memory stay. Agent automations and scheduled refresh stop, because the consent identity changes (3.5).

6.8 (Claude, new) «Удалить ключ» clears the account. It keeps the current confirmation dialog.

## 7. Provider profiles and capabilities

7.1 (Claude, new) A `ProviderProfile` for each provider holds: base URL, wire format, key-check request, transcription request (12A.2), capability flags, the curated model lists, the default models, the output token limit, and the error mapping.

7.2 (Claude) Capability flags for each provider:

| Capability | OpenRouter | OpenAI | Anthropic | DeepSeek |
|---|---|---|---|---|
| Chat and generation | yes | yes | yes | yes |
| Web search | yes | yes | yes | yes |
| Vision | yes (only vision models are offered) | yes | yes | yes (only `deepseek-flash`) |
| Image generation | yes | yes | no | no |
| Cloud voice | yes | no (2.8) | no | no (12.7) |
| Phone voice | yes | yes | yes | yes |
| Voice input | provider | provider | phone (12A.4) | provider |

7.3 (Claude, new) Each chat model in a curated list must support streaming, strict tool calls, JSON output, and image input. It must pass the live acceptance run (16.6) before it enters the list. So `deepseek-v4-pro` (text only) stays out.

7.4 (Claude proposed; the user approved 2026-11-16) Default models and curated lists. Prices are USD for 1M input / 1M output tokens. Anthropic prices come from the Anthropic model catalog. OpenAI and OpenRouter prices come from the OpenRouter model list.

| Role | OpenAI | Anthropic | OpenRouter | DeepSeek (settled) |
|---|---|---|---|---|
| Chat, default | `gpt-6.1-terra` ($1 / $5) | `claude-sonnet-5-5` ($2 / $10) | `openai/gpt-6.1-terra` ($1 / $5) | `deepseek-flash` |
| Chat, second choice | `gpt-6.1-sol` ($2 / $10) | `claude-opus-5-5` ($4 / $20) | none | none |
| Reasoning on / off | effort `medium` / `low` | effort `high` / `low`, adaptive thinking | `reasoning.effort` `medium` / `low` | thinking on / off |
| Image generation | 11.2 | none | 11.2 | none |
| Cloud voice | none | none | `elevenlabs/eleven-v4-turbo` | none (12.7) |
| Voice input | `gpt-transcribe` | phone recognizer | `openai/whisper-large-v3` | `/v1/tools/asr` (no model field) |

Basis:

- `gpt-6.1-terra` is OpenAI's mid tier at half the `gpt-6.1-sol` price. Its OpenRouter description names strong screenshot understanding, which the 3D-scene check uses. Its tool calls need the Responses API (4.5, 15.3).
- 2026-11-17, merge of upstream main 892efa0: the 3D-scene check of this basis is removed. Main's screenshot review (`GenerationSettings.screenshotReview`, off by default) is the vision step of the agent loop now.
- `claude-sonnet-5-5` is the balanced Claude model. `claude-opus-5-5` is the stronger choice for hard mini-apps.
- The OpenRouter default is the OpenAI default, so both keys give the same behavior. `google/gemini-4-carbon` is the non-OpenAI choice. Claude models stay out of the OpenRouter list (7.6).
- 2026-11-17, Task 23 live run: `google/gemini-4-carbon` failed its first run and the user stopped its batch, so it left the OpenRouter list. OpenRouter keeps `openai/gpt-6.1-terra` only.
- Each selector model is the fast, low-cost tier of its family with JSON schema output. `gpt-6-luna` accepts effort `none`. `claude-haiku-5-5` accepts thinking off at effort `high` or lower.
- Reasoning off is effort `low`, not off, because `claude-sonnet-5-5` and `claude-opus-5-5` reject thinking off, and `gpt-6.1-sol` rejects effort `none`. This is an Implementation choice. It keeps one rule for each profile (7.6).
- Assumption: both OpenRouter voice models speak Russian. Test: the live voice check (16.6). Task 1 picks the voice name from each model's voice list.
- 2026-11-17: OpenRouter has one voice model now. The live voice check (16.6) passed with `elevenlabs/eleven-v4-turbo` on a Russian sentence.
- Check date 2026-11-16: every model ID in the table appears in its provider's model list. The Anthropic list shows `structured_outputs` supported for `claude-haiku-5-5`.
- `maxOutputTokens` is 128000 for OpenAI, Anthropic, and OpenRouter, and 393216 for DeepSeek. The OpenAI value is the OpenRouter `top_provider.max_completion_tokens` of `openai/gpt-6.1-terra` and `openai/gpt-6.1-sol` (both 128000). The OpenRouter value is the smaller limit of its two chat models: `openai/gpt-6.1-terra` 128000 and `google/gemini-4-carbon` 943718. The Anthropic value is the `max_tokens` of `claude-sonnet-5-5` and `claude-opus-5-5` (both 128000) in the Anthropic model list.
- 2026-11-17: the OpenRouter value is the limit of `openai/gpt-6.1-terra` (128000), its only chat model.
- Chosen voices: `Kore` for `google/gemini-3.8-flash-tts` and `george` for `elevenlabs/eleven-v4-turbo`. Both come from the `supported_voices` of the model in the OpenRouter speech model list.
- 2026-11-17, Task 23 live finding and user decision: `google/gemini-3.8-flash-tts` accepts only `response_format: "pcm"`, so it rejected the app request (HTTP 400). The user chose ElevenLabs only. OpenRouter cloud voice is `elevenlabs/eleven-v4-turbo`, voice `george`, `mp3`.
- (Claude, 2026-11-16) Voice input: `gpt-transcribe` is the current OpenAI file model. `openai/whisper-large-v3` is the lowest-price OpenRouter model, about $0.0005 to $0.0019 per minute without markup. The OpenRouter `openai/gpt-4o-transcribe` uses an OpenAI model that shuts down on 2027-02-26. Assumption: `gpt-transcribe` and `openai/whisper-large-v3` recognize Russian. No checked page lists their languages. Test: the live voice-input check (12A.10).
- Every chat model must pass the acceptance bar (16.6) before it enters a list. A model that fails leaves the list. With 6 proposed chat models, live acceptance is 36 runs plus the 6 baseline runs.

7.5 (Claude, builder check; revised) One chat model for each provider runs every stage except the selector, including search. This is the current DeepSeek behavior. Reasoning stays on only for the PLAN and COMPLETE stages, as today. The selector (8.8) uses a fast selector model for each provider. For DeepSeek it is `deepseek-flash` with thinking off. Section 15.1 asks for the others.

2026-11-17, merge of upstream main 892efa0: the selector part of 7.5 has no caller. One chat model runs every stage. Reasoning is on only for the `AGENT` stage of the agent loop (`AppAgent`, JSON object, no tools). The Selector row was removed from the 7.4 table.

7.6 (Claude, builder check) Behavior rules stay in the profile, not in each model. An OpenRouter chat model enters the list only when it passes on the OpenAI-compatible path. Example: Claude models through OpenRouter reject a forced `tool_choice` (4.2), so they fail and stay out.

## 8. Chat

8.1 (Claude, new) Split `DeepSeekProvider` into two layers:

- Alphy logic, without wire details: rounds, the decision step, search rounds, grounding, citation checks, correction retries, memory injection, screen context fitting, events.
- A wire format interface: request body, streaming parser, tool call accumulation, JSON output, reasoning control, reasoning replay, token limits, error mapping.

2026-11-17, merge of upstream main 892efa0: `AppAgent.nativeCallReply` reads DeepSeek call markup as a fallback parse of the agent reply. It stays in the agent code, not in a wire (controller ruling). It is the one piece of DeepSeek-specific parsing outside the wires and the profiles.

8.2 (Claude, new) The OpenAI-compatible wire format is today's DeepSeek code with these changes:

- The base URL, the path, and the model come from the profile.
- `thinking` becomes the provider's reasoning control (OpenRouter `reasoning`). DeepSeek keeps `thinking`.
- Only the DeepSeek profile uses `/beta/chat/completions` for strict tools. OpenRouter uses the standard path.
- DeepSeek keeps `reasoning_content` replay. OpenRouter replays `reasoning_details`.
- The parser keeps every current error rule: `[DONE]`, finish reasons, size limits, tool call indexes, and `stream_invalid`.

8.3 (Claude, new) The Anthropic wire format maps the same operations:

- The system text goes to the top-level `system`.
- Images become `image` blocks (section 10).
- Strict tools use `strict: true`.
- The decision step and the search plan cannot force a tool (4.2). They use structured output (8.7).
- Thinking stays adaptive. Effort is the only control. Alphy sends thinking blocks back unchanged in tool rounds.
- The parser handles `message_start`, `content_block_*`, `message_delta`, `message_stop`, and the `stop_reason` values `end_turn`, `tool_use`, `max_tokens`, and `refusal`. `refusal` is a new failure code.
- (Claude, builder check) Requests that use `response_format: json_object` without a schema get a system instruction to return one JSON object. Examples: generation PLAN, COMPLETE, and REFINE, courses, agent plans, and widget layouts. The current validation and repair stay. No prefill (4.2). JSON schemas for each stage are not in this project.
- (Claude, builder check) Anthropic ships only when one of its chat models passes the live acceptance run (16.6). If no model passes, the release ships OpenAI and OpenRouter, and Anthropic waits.

2026-11-17, merge of upstream main 892efa0: DeepSeek sends no reasoning replay in discovery, as upstream main does. The Anthropic replay rule stays. No production request now has thinking together with tools. Only the DeepSeek replay needs thinking. Anthropic, OpenAI, and OpenRouter replay their reasoning in every search and discovery round.

8.4 (Claude, new) `max_tokens` is limited by the profile's output limit. Today Alphy can ask for 131,072 tokens. Current Claude models allow 128K.

8.5 (Claude, new) The production `ChatProvider` routes by account (5.4). `supportsSceneVision` returns the vision flag of the stored account's profile.

2026-11-17, merge of upstream main 892efa0: `supportsSceneVision` is removed. All four profiles have `Capability.VISION`, so no provider gate is necessary.

8.6 (Claude, builder check) OpenRouter chat and search requests send `provider: {"require_parameters": true, "data_collection": "deny"}`. The first value sends a request only to hosts that support every parameter, such as strict tools and JSON output. Then, live acceptance results stay valid in production. The second value refuses hosts that store prompts. Image generation keeps its fixed host.

8.7 (Claude, new; revised) On Anthropic, two steps cannot force a tool: the decision step (a named tool or `"required"`) and the search plan (a named `web_search`). The Anthropic wire uses structured output for them:

- The wire converts the forced tools into one JSON schema. The root is an object with one `calls` array. Each item is `anyOf` the forced tools, each as `{name, arguments}`, with `name` fixed by a one-value `enum`.
- The wire sends it as `output_config.format`. It turns the JSON reply into tool calls with new IDs. Alphy logic does not change: `decodeClassification` and `validateCalls` check the calls as today, with the one-decision and two-search limits. Anthropic has no `maxItems`, so the count check stays in Alphy.
- OpenAI, OpenRouter, and DeepSeek keep today's forced strict tools. OpenAI sends them through the Responses API (8.9). OpenRouter uses the standard path. DeepSeek uses `/beta/chat/completions` with thinking off (4.3).

8.8 (User, revised; Claude checked Anthropic) The selector in the Jev role (5.6) uses native structured output on OpenAI, OpenRouter, and Anthropic:

- `CloudRequest` gets an optional `responseSchema`. `JevSelector` builds it from the questions of the call.
- The schema root is an object with one `answers` array. Each item is `anyOf` one branch for each question, with `id` fixed by a one-value `enum`. A `choice` branch has `choice` (an `enum` of the offered letters), `confidence`, and `probabilities` (an object that requires every offered letter). A `noul` branch has `probability`.
- OpenRouter sends it as `response_format` `json_schema` with `strict: true`. Anthropic sends it as `output_config.format`. DeepSeek keeps `json_object` and today's prompt.
- (User pointed to it, 2026-11-16; Claude) OpenAI sends the selector to `POST /v1/decisions` with `gpt-6-luna` (5.6 (d)), not to the Responses API. Each `noul` question becomes a `predicate`. Each `choice` question becomes a `choice` with the letters as values and the option texts as descriptions. The plan `stage`, `title`, and `description` go in `input` as one `input_json` part. The answers convert back to the Jev answer JSON without change of values, and `JevSelector` checks them as before. A refused question has no answer.
- `JevSelector` keeps the checks that a schema cannot express: the 0 to 1 range, the sum of 1, the sole highest choice, and one answer for each question.
- Selector calls use the selector model (7.5), with reasoning off or at its lowest level, as today.
- The KEEP_ABOVE and PICK_ABOVE thresholds stay. The live acceptance run (16.6) measures the selector on each provider, because the parts of an app or a game come from it.
- Risk: a large selector call (many questions, up to 26 letters each) can exceed a provider's schema limits (4.4). If a provider rejects the schema, that provider uses (a) for the selector.

2026-11-17, merge of upstream main 892efa0: the selector (8.8) has no caller. Upstream main deleted `JevSelector` and the `SELECTOR` stage, and the merge removed `responseSchema` and `OpenAiDecisions`. App generation uses the agent loop (`AppAgent`, stage `AGENT`, JSON object, no tools). Sections 5.6 and 8.8 are history.

8.9 (User chose 15.3 (a); Claude) The OpenAI Responses wire format maps the same operations:

- `POST /v1/responses` with `stream: true` and `store: false`, so OpenAI keeps no copy of the conversation (3.4).
- The system text goes to `instructions`. Turns become `input` items. Images become `input_image` items with the data URL.
- Strict tools are flat: `{"type": "function", "name", "description", "parameters", "strict": true}`. A forced tool is `tool_choice: {"type": "function", "name": …}`. `"required"`, `"auto"`, and `"none"` stay.
- `response_format: json_object` becomes `text.format: {"type": "json_object"}`. The selector schema uses `text.format` (8.8).
- 2026-11-17, merge of upstream main 892efa0: the selector schema (8.8) is removed. Only the JSON object format uses `text.format`.
- Reasoning control is `reasoning.effort`. In a tool round, the wire sends each reasoning item back unchanged, with its `encrypted_content`, before its `function_call` items. Tool results become `function_call_output` items with the same `call_id`.
- `max_tokens` becomes `max_output_tokens`, limited by the profile (8.4).
- The parser reads `response.output_text.delta`, `response.output_item.added`, `response.function_call_arguments.delta`, `response.output_item.done`, `response.completed`, `response.incomplete`, `response.failed`, and `error`. A `refusal` content part gives the `refusal` failure code. The current size limits, call limits, and `stream_invalid` rules stay.
- Assumption: a forced `tool_choice` works with reasoning on for the GPT-6.1 models in the Responses API. The OpenAI documentation lists no such limit. Test: the live acceptance run (16.6).

2026-11-17, merge of upstream main 892efa0: DeepSeek sends no reasoning replay in discovery, as upstream main does. The OpenAI reasoning-item replay rule stays. No production request now has thinking together with tools.

2026-11-17, Task 23 live finding: the Responses API needs the word json in an input message for `json_object`; the wire adds a developer message «Return one JSON object.»

## 9. Search

9.1 (Claude) `CloudSearchProvider.search(key, query, session)` keeps its signature. OpenRouter, OpenAI, and Anthropic implement it as one model call with the built-in search tool. DeepSeek keeps its search endpoint:

- OpenRouter: Chat Completions with `{"type": "openrouter:web_search", "parameters": {"engine": "exa", "max_results": 5}}`. `Source.excerpt` comes from the annotation `content`.
- OpenAI: the Responses API with the `web_search` tool and `include: ["web_search_call.action.sources"]`. `Source.excerpt` comes from the answer text that cites the URL.
- DeepSeek: today's `POST /v1/tools/search`. `publishedAt` comes from `date`.
- Anthropic: Messages with `web_search_20260209` and `max_uses: 1`. `Source.excerpt` comes from the `cited_text` values for the URL. `publishedAt` comes from `page_age` when Alphy can parse it.

9.2 (Claude, new) The current output contract stays: at most 5 sources, a valid URL for each, a title, an excerpt of at most 6,000 characters, and `search_no_usable_results` when every result is unusable.

9.3 (Claude, new) Site names and site icons are absent unless the provider gives them. The UI already uses the local icon in that case.

9.4 (Claude, new) Dated searches for scheduled agents keep their filter: a source without `publishedAt` does not pass a dated query. The search call asks the model for an ISO publication date for each source. This gives fewer results than DeepSeek search, which returns a date. Section 14 lists it as a risk.

9.5 (Claude, new) The Alphy logic around search does not change: query validation, the limit of 2 searches, de-duplication, excerpt fitting, and the citation checks in `ChatToolReply.grounded`.

## 10. Vision

10.1 (Claude) Every path that sends pictures keeps using `CloudTurn.images`: chat photos and screen images, courses, mini-app generation, and the 3D scene check.

2026-11-17, merge of upstream main 892efa0: the 3D scene check is removed (7.4 basis). The other paths keep using `CloudTurn.images`.

10.2 (Claude) Before Alphy sends a picture, it shrinks the long edge to 2048 px or less. A PNG stays PNG. Other formats become JPEG. A picture that already fits is sent unchanged. The current input checks (type, signature, size, 8192 px) stay at selection.

10.3 (Claude) The production `ChatProvider` reports `supportsSceneVision = true` for all four providers. The built 3D scene check (`reviewThreeFrames`) then runs in production. Each check adds one paid vision call.

2026-11-17, merge of upstream main 892efa0: `supportsSceneVision` and the built 3D scene check (`reviewThreeFrames`) are removed. Main's screenshot review (off by default) is the vision step now. Its picture goes through the 2048 px limit (10.2).

10.4 (Claude, new) The decision step still gets the text without pictures. This does not change.

## 11. Image generation

11.1 (Claude) `ImageSettings` loses its own key store. A `Selection` uses the account's consent identity.

11.2 (Claude, new) `ImageModel` entries carry their provider ID. The model list shows only the active provider's models. OpenRouter keeps Qwen Image 2.1 Turbo and Seedream 4.5. OpenAI adds `gpt-image-2.6-flare` and `gpt-image-2.6-sunburst`.

11.3 (Claude, new) A new `OpenAiImages` provider calls `POST /v1/images/generations` and reads `data[0].b64_json`. The builder maps `ImageAspect` to OpenAI sizes after a check of the current size list (Builder).

11.4 (Claude, new) The «Разрешить генерацию изображений» switch, the per-request paid confirmation, the reservation rules, and the 4-image limit stay.

11.5 (Claude, new) With an Anthropic or DeepSeek key, image generation is off. The planner gets no image tool, as it does today when the switch is off.

11.6 (Claude, builder check) When a provider has no size for an `ImageAspect`, Alphy requests the nearest wider size and cuts the center to the target ratio on the device. Then, the current ratio check runs. Example: OpenAI has no 2:1 size for `EQUIRECTANGULAR` 3D textures.

## 12. Voice output

12.1 (Claude) Voice output means: in the continuous voice session, Alphy reads each accepted reply aloud. Then it listens for the next utterance. One-shot recording does not read replies aloud.

12.2 (Claude, new) «Голос» gets a switch «Читать ответы вслух» (off by default) and a source choice: «Телефон» (Android `TextToSpeech`, default) or «Облако» (only with OpenRouter).

12.3 (Claude, new) Alphy reads the reply text without Markdown marks, source chips, and code blocks. Stop ends speech and the session.

12.4 (Claude, new) Phone voice uses the Russian voice of the system engine. If no Russian voice is installed, «Голос» shows a status and a link to the system speech settings.

12.5 (Claude, new) Cloud voice calls OpenRouter `POST /api/v1/audio/speech` with `response_format: "mp3"`, plays the file, and deletes it after playback. A failure falls back to phone voice for that reply and shows a status. (Claude, builder check) The curated voice models exclude OpenAI speech models, because OpenAI shuts them down on 2027-01-06 (2.8).

2026-11-17, user decision: OpenRouter cloud voice is `elevenlabs/eleven-v4-turbo` only (7.4).

12.6 (Claude, new) Voice output takes audio focus and releases it before recognition starts again.

- 2026-11-17 (Task 19 decision 4): speech runs under the one audio focus request of the voice session and requests no focus of its own. A second request stops the session.

12.7 (User, 2026-11-17) 2026-11-17, user decision: DeepSeek has no cloud voice. The DeepSeek profile has no `CLOUD_VOICE` flag and no speech model. The DeepSeek `POST /v1/tools/tts` request is not in the app, and no paid check of it runs. The earlier rule (voice `stella`, `wav` output) is withdrawn. A stored «Облако» choice stays stored, and an account without cloud voice reads replies with the phone voice (12.2). «Что доступно» shows «на телефоне» for DeepSeek, as for OpenAI and Anthropic.

## 12A. Voice input

12A.1 (User, 2026-11-16) Speech goes through the user's key for OpenAI, OpenRouter, and DeepSeek. Anthropic uses the phone recognizer. Text appears only after the user stops speaking, so no partial text shows while the user speaks.

12A.2 (Claude, new) Cloud path. Alphy records with `AudioRecord` at 16 kHz, mono, 16-bit PCM, as today. It keeps the samples in memory, at most 60 s. Then it sends one WAV file in one multipart request: part `file` (file name `voice.wav`, type `audio/wav`), and part `model` for OpenAI and OpenRouter. It sends no `language` part, so the provider detects Russian or English. The `text` field of the JSON response is the transcript. 60 s of audio is 1,920,044 bytes as WAV. That is below the DeepSeek limits (8 MiB, 120 s) and the 25 MB limit of OpenAI and OpenRouter.

12A.3 (Claude, new) End of speech:

- One-shot recording ends at «Завершить запись», at «Отправить голосовой текст», or at 60 s. Only the limit changes: it was 25 s.
- In the continuous session, the utterance ends after 1.2 s of silence that follows speech. The silence threshold adapts to the background noise. Without speech in the first 8 s, the session stops with «Не удалось распознать речь», as today.
- Then the status changes to recognition, and the request starts.

12A.4 (Claude, new) Phone path (Anthropic): `SpeechRecognizer.createOnDeviceSpeechRecognizer`, language `ru-RU`, no partial results. The recognizer owns the microphone. The level bar uses `onRmsChanged`. «Завершить запись» calls `stopListening()`. The recognizer's own end of speech ends the utterance in both modes. Reasons for the on-device recognizer:

- The audio stays on the phone, so the Anthropic consent needs no voice line (14.7).
- Alphy registers `AlphaRecognitionService` for the assistant role, and that service refuses every request. Assumption: when Alphy is the default assistant, Android makes this service the default recognizer, so `createSpeechRecognizer` would fail. Test: on a phone with Alphy as the default assistant, read `Settings.Secure` `voice_recognition_service`.

12A.5 (Claude, new) Phone recognizer states:

- `isOnDeviceRecognitionAvailable` is false, or `checkRecognitionSupport` does not list `ru-RU`: the status says that this phone cannot recognize Russian speech on the device. The microphone does not start.
- `ru-RU` is supported but not installed: the status offers «Скачать русский язык». The button calls `triggerModelDownload`, and the status says that the phone downloads the language.

12A.6 (Claude, new) Without an account, voice input does not start. The status asks for a key and shows «Открыть настройки», which opens «Сервис ИИ» (14.6).

12A.7 (Claude, new) A failed transcription stops the voice session with «Не удалось распознать речь:» and the provider error text (13.1, 13.2). Alphy does not keep the recording. Reason: a retry needs the recording in memory after the failure, and no requirement asks for a retry. The rules of 3.4 apply: stop during the upload disconnects only that request.

12A.8 (Claude, new) The microphone note names the path: «Запись уходит в %1$s для распознавания · проверяйте текст перед отправкой» (cloud) or «Речь распознаёт телефон · проверяйте текст перед отправкой» (phone). These replace «Звук остаётся на устройстве» and the T-One texts.

12A.9 (Claude, new) Removal: `VoiceModels`, `TOneStreamingTranscriber`, `AudioTranscriber`, `ModelReadiness` (the `com.offlineassistant` package), `assets/voice` (T-One and Whisper), the voice license notices in `assets/licenses`, the sherpa-onnx AAR and its LFS rule, the `onnx` `noCompress` entry, the Whisper and Silero asset patterns, and the T-One strings. The debug APK gets at least 144,000,000 bytes smaller. Alphy does not delete the model copy that earlier builds put in `noBackupFilesDir/voice/alpha-voice-1`, because AGENTS.md forbids migrations only for earlier development versions. A reinstall frees that space.

12A.10 (Claude, new) Cost: OpenRouter `openai/whisper-large-v3` costs about $0.0005 to $0.0019 per minute, and DeepSeek $0.116 per hour. The checked OpenAI pages give no price for `gpt-transcribe`. The live check sends the `russian.wav` test fixture once to each cloud provider. Expected: the text contains «таймер» and «пять» or «5».

## 13. Errors

13.1 (Claude) Error texts name the active provider. For example, «OpenRouter: недостаточно средств».

13.2 (Claude, new) Mapping:

| Condition | OpenRouter | OpenAI | Anthropic | DeepSeek | Code |
|---|---|---|---|---|---|
| Key rejected | 401 | 401 | 401 | 401 | `auth` |
| No permission | — | 403 | 403 | 403 | `auth` |
| Input flagged by moderation | 403 | — | — | — | `refusal` |
| No credit | 402 | 429 with `insufficient_quota` | 400 with a low credit balance message | 402 | `quota` |
| Rate limit | 429 | 429 | 429 | 429 | `rate_limit` |
| Unavailable | 5xx | 5xx | 5xx, 529 | 5xx | `unavailable` |
| Refusal | finish reason `content_filter` | `refusal` content | `stop_reason: "refusal"` | finish reason `content_filter` | `refusal` |

13.3 (Builder) The exact Anthropic low-credit response and the OpenRouter refusal shape are checked during implementation.

## 14. UI and UX

14.1 (Claude) «Сервис ИИ» replaces «DeepSeek» in «Настройки». The «Изображения» section goes. Its switch moves into «Сервис ИИ», and its model choice moves into «Модели».

```
Сервис ИИ
[ API-ключ ••••••••••••••              ]
  Ключ OpenAI, Anthropic, OpenRouter или DeepSeek
  [ OpenAI | Anthropic | OpenRouter | DeepSeek ]   ← detected, can be changed
  Где взять ключ                           ← provider guide (14.10), only while no key is saved

▸ Что получает OpenRouter                  ← consent text, open until Ready
[Согласиться и сохранить]

Что доступно
  Ответы и мини-приложения        ✓
  Понимание изображений           ✓  фото, снимки экрана, проверка 3D-сцен
  Поиск в интернете               ✓
  Создание изображений            [вкл]  платно, с подтверждением
  Озвучка ответов                 телефон или облако, раздел «Голос»
  Голосовой ввод                  ✓  распознаёт OpenRouter

[Удалить ключ]
▸ Модели                                   ← «Настройки» only, not onboarding
```

- 2026-11-17, merge of upstream main 892efa0: the vision line reads «фото и снимки экрана». The 3D scene check is removed (10.1).

14.2 (Claude) With an Anthropic key, the rows show:

- «Создание изображений — недоступно с Anthropic. Нужен ключ OpenAI или OpenRouter.»
- «Озвучка ответов — на телефоне».
- «Голосовой ввод — на телефоне» (12A.4).

With an OpenAI key, «Озвучка ответов» shows «на телефоне».

With a DeepSeek key, «Создание изображений» shows «недоступно с DeepSeek. Нужен ключ OpenAI или OpenRouter.», and «Озвучка ответов» shows «на телефоне». (2026-11-17, user decision: DeepSeek has no cloud voice.)

14.3 (Claude, new) «Что доступно» appears as soon as the provider is detected, before the key check. The user sees the functions before saving.

14.4 (Claude, new) Panel states: `Checking` (startup), `Missing`, `Verifying` (key check in progress), `NeedsConsent`, `Ready`, `Unreadable`. A rejected key or a failed check is a message in `Missing` or `Ready`, not a state.

- 2026-11-17, user decision: `NeedsConsent` is removed. The key store saves the key with its consent record in one commit, so a stored key without consent cannot occur. The request-side consent check (`CloudKey`) stays as a defensive check.

14.5 (Claude, new; revised for onboarding review) Onboarding keeps its 3 steps (`OnboardingPane.kt`):

- Step 1 adds two rows to the function list: «Понимаю фото и снимки экрана» and «Читаю ответы вслух в голосовом режиме». Image generation is not in the list, because it depends on the provider.
- Step 2 title: «Подключите сервис ИИ». Text: «Вставьте ключ OpenAI, Anthropic, OpenRouter или DeepSeek. С ключом OpenRouter работают все функции.» Then, the same panel as «Сервис ИИ» (14.1), without «Модели»: the found-key card (14.6), the key field, the provider control, the provider guide (14.10), consent, and «Что доступно». «Далее» needs `Ready`. «Позже» stays while the key is not `Ready`, also after a failed check.
- Step 3 adds one line when the provider has no image generation: «Создание изображений недоступно с %1$s. Ключ можно сменить в настройках, раздел «Сервис ИИ».» It adds a second line for every provider: «Ответы можно слушать: включите «Читать ответы вслух» в разделе «Голос».»

```
Шаг 2 из 3
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Подключите сервис ИИ
Вставьте ключ OpenAI, Anthropic, OpenRouter или DeepSeek.
С ключом OpenRouter работают все функции.

┌ Найден ключ DeepSeek ───────────────────────┐   ← only with an old key and no account
│ [Использовать]  [Удалить]                    │
└──────────────────────────────────────────────┘
[ API-ключ                                  ]
[ OpenAI | Anthropic | OpenRouter | DeepSeek ]   ← after detection
Где взять ключ                                    ← while no key is saved
  OpenRouter — все функции
  OpenAI — без облачной озвучки
  DeepSeek — без создания изображений и облачной озвучки
  Anthropic — без создания изображений и облачной озвучки
▸ Что получает DeepSeek
[Согласиться и сохранить]
Что доступно …                                    ← after detection

[Далее]                                           ← enabled at Ready
 Позже
```

14.6 (Claude, new) Old keys:

- (Revised) An old DeepSeek key, when no account exists, shows «Найден ключ DeepSeek» with «Использовать» and «Удалить», in «Сервис ИИ» and in onboarding step 2. «Использовать» runs the same flow as the old OpenRouter key below.
- An old OpenRouter image key, when no account exists, shows «Найден ключ OpenRouter» with «Использовать для всех функций» and «Удалить». «Использовать» shows the consent and runs the normal check and save. After a successful save, Alphy clears the old store.
- (Claude, onboarding review) While an account exists, the card stays with «Удалить» only. So an old key never stays on the device out of sight.
- (Revised after reading `ShellViewModel.kt:882-885`) Onboarding opens only when the `onboarded` setting is not set. So a returning user does not see it again. (Claude) A returning user gets no separate screen, because AGENTS.md forbids additions only for earlier versions. A cloud request without an account shows «Открыть настройки». It opens «Сервис ИИ», where the found-key card is.

14.7 (Claude) Consent text names the provider and lists the same data as today. With OpenRouter it adds: «OpenRouter передаёт запрос поставщику выбранной модели.» and «Текст ответа для озвучки». (Claude, builder check) The voice line is always in the OpenRouter consent. (2026-11-17, user decision: DeepSeek has no cloud voice, so its consent has no voice line.) Then, the user can select «Облако» later without a second consent. A second consent through the account would change the consent identity and stop automations (3.5). (Claude, 2026-11-16) The OpenAI, OpenRouter, and DeepSeek consent also adds «Записи голоса для распознавания речи». The line is always there, because voice input with these providers always sends the recording (12A.2). The Anthropic consent gets no voice line, because the phone recognizes the speech (12A.4).

14.8 (Claude, new) «Модели» lists the curated models of the active provider for chat, image generation, and cloud voice. It is closed by default.

14.9 (Claude, new) Every UI string in section 14 lives in Android string resources. Provider error texts (section 13) keep today's pattern: `CloudFailure` messages built in the cloud code. The UI uses the polite form, as the app does today.

14.10 (Claude, new) The provider guide «Где взять ключ» has one row for each provider: the name, what it lacks, and a link to its key page. The order is OpenRouter, OpenAI, DeepSeek, Anthropic, from the most functions to the fewest. The «lacks» text comes from the profile capabilities: «все функции», or «без …» with «создания изображений» and «облачной озвучки». So the guide changes when a capability flag changes (12.7). The guide replaces today's single «Где взять ключ» link under the key field. It shows while the key status is `Missing`, in onboarding and in «Сервис ИИ». Each provider name is the link.

## 15. Questions for the user

15.1 Default models and curated lists (7.4), and the fast selector model for OpenAI, Anthropic, and OpenRouter (7.5). (User) Resolved 2026-11-16: the user approved the 7.4 proposal.

15.2 Which provider keys are available for the live acceptance runs (16.6). (User) Resolved 2026-11-16: all four. The live runs read `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `OPENROUTER_API_KEY`, and `DEEPSEEK_API_KEY` from `~/.env`.

15.3 (Resolved 2026-11-16: the user chose (a), see 8.9.) How OpenAI makes tool calls (found 2026-11-16, 4.5).

- (a) Picked: add an `OpenAiResponsesWire` for the OpenAI key on `POST /v1/responses`. It sends reasoning items back with tool outputs, as the DeepSeek wire sends `reasoning_content`. The selector uses `text.format` `json_schema`. OpenAI search already uses the Responses API (4). Cost: a third wire and its loopback fixture, about the size of the Anthropic wire task.
- (b) Keep Chat Completions and use only `gpt-6-sol` and `gpt-6-luna` with effort `none` in every stage that has tools. This adds no code, but OpenAI loses reasoning in PLAN and COMPLETE (7.5), and mini-app quality can drop.

OpenRouter is not affected: it lists tools and `tool_choice` for the OpenAI models, and the acceptance runs (16.6) check this.

## 16. Testing

16.1 (Claude, new) Write each test before the code that it checks.

16.2 (Claude, new) The repository has no JVM unit tests. All tests are device tests in `app/src/androidTest`, plus Python host tests in `tools/compose_acceptance`. New tests follow this convention. Wire tests use a loopback server on 127.0.0.1, as `CloudWorkflowTest` does.

16.3 (Claude, new; revised) `CloudWorkflowTest` (76 tests) keeps its DeepSeek fixture and becomes the DeepSeek profile test. The wire split must keep it green. New OpenAI and OpenRouter tests cover the differences of each profile: path, reasoning control, replay, selector schema, and search. A new OpenAI Responses fixture covers the Responses wire format: tool rounds with encrypted reasoning replay, forced tools, `text.format`, images, `incomplete`, and refusal. A new, smaller Anthropic fixture covers the Anthropic wire format: decision, search, answer, tool rounds, thinking replay, refusal, and errors. Provider detection, key checks, search mapping for each provider, and picture shrinking get their own loopback device tests.

16.4 (Claude, new) Settings and onboarding: device workflows for detection, the override control, check failures, save, replacement, deletion, the Anthropic capability rows, and both old-key notices. `SettingsConsentWorkflowTest` and `OnboardingWorkflowTest` change with the UI. `OnboardingWorkflowTest` also covers the new step 1 rows, the guide links, the found-key card, and the step 3 lines.

16.5 (Claude, new) The recorded replay fixtures in `app/src/androidTest/assets/generation-reliability` are provider-neutral (`stage`, `content`). They stay. Only `CloudWorkflowTest.recordedPromptReplay` re-encodes them into the wire format, so it also runs on the Anthropic fixture.

16.6 (Claude, new; pass bar and order from the builder check) Live acceptance runs (`tools/compose_acceptance/run.py run --live`) measure each curated chat model with the shared behavior checker (AGENTS.md):

- The app workflow is scenario 3 (`daily-medication`). The game workflow is scenario 4 (`arkanoid`). Each scenario runs 3 repetitions, so one model needs 6 runs.
- Before the DeepSeek client changes, the same 6 runs record the DeepSeek baseline. The baseline below was recorded on 2026-11-16.
- A run passes when the in-app executable behavior checker validates the generated app or game (generation stage `behavior`, outcome `validated`). Generation must be accepted, and the render evidence (`before.png`, `after.png`, `motion.mp4`) must exist.
- The manifest interaction rows stay `UNAVAILABLE`. They need a manual review that the harness does not do. So `run.py verify` prints `INCOMPLETE_OR_SYNTHETIC` for every run after its file, hash, and identity checks pass.

| Scenario | Passed runs of 3 | Revision | Date |
| --- | --- | --- | --- |
| 3 `daily-medication` | 3 (1 on the first attempt, 2 after repair) | `da150d9` | 2026-11-16 |
| 4 `arkanoid` | 0 (all 3 ended with `CloudFailure`) | `da150d9` | 2026-11-16 |

- Scenario 4 run facts. In s4-r1, the behavior checker rejected the game twice (`widget_behavior`, rule `surface_unavailable`), and two definition repairs failed (`widget_spec`). In s4-r2, the plan stage failed twice, the behavior checker rejected the game once, and the provider repair failed (`widget_patch`). In s4-r3, the behavior checker rejected the game once, and the provider repair failed (`widget_patch`).
- Assumption: the generated games lacked the surface or control that their own behavior step needs. Basis: all four branches of the rule in `ToolGenerationSmoke.kt` (lines 257, 432 to 448) test the generated spec. The sanitized report does not say which branch fired, and s4-r2 and s4-r3 carry no binding rule. Test: log the failure reason for a `surface_unavailable` rejection, then repeat scenario 4 once.
- Unresolved decision: the user decides the scenario 4 pass bar for new models. The baseline is 0 of 3, so the rule "at least as many runs as `deepseek-flash`" gives no bar. This decision blocks acceptance of the scenario 4 results in Task 23. The controller asks it with the first Task 23 batch approval.
- Evidence directories (not in Git), under `evidence/compose-execution/scenarios/real`: `s3-r1-81106c283046`, `s3-r2-c63bb8c342b2`, `s3-r3-2d71933e23af`, `s4-r1-ea0749c6ff5c`, `s4-r2-b36e83dbfcce`, `s4-r3-a38af8f4ba72`.
- A model enters a curated list only when it passes at least as many runs of each scenario as `deepseek-flash` did. `deepseek-flash` itself is the baseline.
- One live check of each function on each provider runs after the model runs, including OpenRouter cloud voice in Russian (12.5) and voice input from the `russian.wav` fixture for OpenAI, OpenRouter, and DeepSeek (12A.10).
- Live runs cost money. One batch is the 6 runs of one model. Each batch needs the user's approval, with a cost estimate.
- 2026-11-17, merge of upstream main 892efa0: the agent loop replaced staged generation, so the `behavior` stage and the `validated` outcome no longer exist. A run passes when `run.py run --live` exits 0, the phase is `complete`, generation is `accepted`, and `before.png`, `after.png`, and `motion.mp4` exist.
- 2026-11-17, user decision on the Unresolved decision above: `deepseek-flash` is the main model and passes scenario 4 on upstream main. The bar for each curated model is 3 of 3 runs in each scenario.
- 2026-11-17, Task 23 results (6 runs for each model, all with the pass rule of the first line above):

| Model | Scenario 3 | Scenario 4 | Revision |
| --- | --- | --- | --- |
| `deepseek-flash` (pure upstream main) | 3 of 3 | 3 of 3 | `892efa0` |
| `deepseek-flash` | 3 of 3 | 3 of 3 | `c6b8a4c` |
| `gpt-6.1-terra` | 3 of 3 | 3 of 3 | `b4f3de0` |
| `gpt-6.1-sol` | 3 of 3 | 3 of 3 | `b4f3de0` |
| `claude-sonnet-5-5` | 3 of 3 | 3 of 3 | `c6b8a4c` |
| `claude-opus-5-5` | 3 of 3 | 3 of 3 | `c6b8a4c` |
| `openai/gpt-6.1-terra` (OpenRouter) | 3 of 3 | 3 of 3 | `c6b8a4c` |
| `google/gemini-4-carbon` (OpenRouter) | 0 of 1, batch stopped by the user | not run | `c6b8a4c` |

- The first 12 OpenAI runs on `c6b8a4c` failed at the first request with HTTP 400 (JSON mode, 8.9 note). They do not count. The OpenAI rows above ran after that fix.
- 2026-11-17, function checks (`run.py live-check`) on `e6af9ad`: 16 of 17 passed (chat, search, photo, image generation, and voice input on each provider that has the function). The OpenRouter cloud voice check failed with HTTP 400 (7.4 note) and passed on `8605e2e` with `elevenlabs/eleven-v4-turbo`.
- 2026-11-17, after the merge of upstream main 6250b23 (`37f2d60`): one run of each scenario for the default model of each provider passed, 8 of 8 (`deepseek-flash`, `gpt-6.1-terra`, `claude-sonnet-5-5`, OpenRouter `openai/gpt-6.1-terra`).
- Evidence directories (not in Git): `evidence/compose-execution/scenarios/real` for the model runs and `evidence/live-checks/t23-*` for the function checks.

16.7 (Claude, new) The live tooling changes from `DEEPSEEK_API_KEY` to a provider and a key for each run. The one-use adb credential transport (`OneUseCredential`) stays. The Python host tests in `test_harness.py` change with it.

16.8 (Claude, new) Voice input tests: a loopback request test for each cloud provider (path, auth, multipart parts, WAV header), redirect refusal, cancellation, the 60 s limit, and the end-of-speech detector on synthetic PCM. A fake recognizer seam covers the phone path, because the emulator can lack an on-device recognizer. The existing voice workflow tests keep their assertions through a fake speech input. An APK check asserts that no `assets/voice/` entry and no sherpa-onnx library remain.

## 17. Documentation

17.1 (Claude, new) Update `README.md`, `docs/cloud-integration.md`, `docs/voice-integration.md`, `docs/automations.md`, `docs/shell-integration.md`, and `docs/native-capabilities.md`.

## 18. Left to the builder

- Class and file names.
- The JSON schema form of the decision for Anthropic `output_config.format`.
- How excerpts are cut from cited text.
- The playback component for cloud voice.
- The end-of-speech detector, inside the bounds of 12A.3 and its test.
- The exact key-help URLs for each provider.
- OpenAI image sizes for each `ImageAspect`.
- Whether four provider labels fit one segmented row at 360 dp, or the control becomes a menu.
- A check that the Anthropic decision schema and the largest selector schema fit the schema limits (4.4).
