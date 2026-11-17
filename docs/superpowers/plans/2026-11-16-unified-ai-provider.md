# Unified AI provider Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the DeepSeek key and the separate OpenRouter image key with one OpenAI, Anthropic, OpenRouter, or DeepSeek key. That key runs chat, search, vision, image generation, voice input, and voice output where its provider has them. The local speech model goes: voice input uses the provider, or the phone recognizer with Anthropic.

**Architecture:** One encrypted account (key, provider, consent) replaces both key stores. Alphy's cloud logic keeps building Chat Completions-shaped JSON. A `ChatWire` for each wire format sends it: `OpenAiCompatibleWire` for OpenRouter and DeepSeek, `OpenAiResponsesWire` for OpenAI (the Responses API, because GPT-6 models have no tool calls with reasoning in Chat Completions), and `AnthropicWire` (raw HTTP over `CloudHttp.open` with its own SSE parser, spec 5.5) for Anthropic. The selector in the Jev role uses native JSON schema output on OpenAI, OpenRouter, and Anthropic. Anthropic also uses it in place of forced tools. DeepSeek keeps `json_object` and forced strict tools. `CloudChatProvider` reads the stored account at call time and routes to its wire, search, image, and speech implementations. Every existing provider interface keeps its signature. «Настройки» and onboarding share one key panel with a provider guide and the old-key card.

**Tech Stack:** Kotlin, Jetpack Compose (BOM 2026.09.00), Material 3 1.5.0-beta02, OkHttp 5.5.1, `org.json`, kotlinx-serialization 1.9.0, Android `TextToSpeech`, `MediaPlayer`. Device tests (`app/src/androidTest`) on `emulator-5564`. Python host tests in `tools/compose_acceptance`.

**Spec:** [docs/superpowers/specs/2026-11-16-unified-ai-provider-design.md](../specs/2026-11-16-unified-ai-provider-design.md)

## Global Constraints

Each quoted line is verbatim from the spec, with its section. Every task obeys this section.

- 1.1 "(User, revised) The user enters one API key from OpenAI, Anthropic, OpenRouter, or DeepSeek. Alphy does not ask for a second key."
- 1.4 "(User, revised) Keep DeepSeek when its capabilities reach Anthropic's level." DeepSeek passed this check, so it stays as the fourth provider.
- 2.10 "Alphy deletes a user's old key without an explicit user action." This is an anti-goal.
- 2.11 "Alphy sends a key to a provider other than the one shown in the provider control. OpenAI and DeepSeek keys both start with `sk-`." This is an anti-goal.
- 3.4 "no logs of keys, requests, responses, or image bytes; redirects refused; bounded reads; connection and read timeouts; cancellation that disconnects only the canceled request; the loopback base URL as the only test seam"
- 3.5 "The consent identity (`SecureKeyStore.consentIdentity()`, the IV of the stored key) changes on every key write."
- 5.4 "Keep every provider interface unchanged." `ChatProvider`, `CloudSearchProvider`, and `ImageProvider` keep their signatures.
- 6.1 "preference file `alpha_ai_credentials`, Keystore alias `alpha.ai.v1`. One commit writes the key, the provider ID (`openai`, `anthropic`, `openrouter`, or `deepseek`), and the consent record."
- 7.3 "Each chat model in a curated list must support streaming, strict tool calls, JSON output, and image input."
- 8.4 "`max_tokens` is limited by the profile's output limit."
- 10.2 "Before Alphy sends a picture, it shrinks the long edge to 2048 px or less. A PNG stays PNG. Other formats become JPEG."
- 14.9 "Every UI string in section 14 lives in Android string resources." UI text is Russian and uses the polite form.
- 16.1 "Write each test before the code that it checks."
- 16.2 "All tests are device tests in `app/src/androidTest`, plus Python host tests in `tools/compose_acceptance`."
- 16.6 "Live runs cost money. One batch is the 6 runs of one model. Each batch needs the user's approval, with a cost estimate."
- 8.7 "OpenAI, OpenRouter, and DeepSeek keep today's forced strict tools. OpenAI sends them through the Responses API (8.9)." Only the Anthropic wire converts forced tools to structured output.
- 8.8 "OpenAI sends the selector to `POST /v1/decisions` with `gpt-6-luna` (5.6 (d)), not to the Responses API." "OpenRouter sends it as `response_format` `json_schema` with `strict: true`. Anthropic sends it as `output_config.format`. DeepSeek keeps `json_object` and today's prompt." This is the selector schema in the Jev role.
- 8.9 "`POST /v1/responses` with `stream: true` and `store: false`, so OpenAI keeps no copy of the conversation (3.4)."
- 8.6 "OpenRouter chat and search requests send `provider: {\"require_parameters\": true, \"data_collection\": \"deny\"}`."
- 12A.1 "(User, 2026-11-16) Speech goes through the user's key for OpenAI, OpenRouter, and DeepSeek. Anthropic uses the phone recognizer. Text appears only after the user stops speaking"
- CLAUDE.md: before each `git commit`, run the `slopo-review` skill on the staged change and resolve blocking findings.

Test commands:

- Red step: `python3 tools/compose_acceptance/run.py suite --label <label> --serial emulator-5564 --expect failed <targets>`
- Green step: `python3 tools/compose_acceptance/run.py suite --label <label> --serial emulator-5564 <targets>`
- Host tests: `python3 tools/compose_acceptance/run.py host --label <label>`

A target is `com.alpha.assistant.<package>.<Class>[#<method>]`.

## Review Focus

1. A key pasted with spaces or a line break around it (clipboard), or a bare `sk-` key that can belong to OpenAI or DeepSeek. Detection and storage use the trimmed key. The key check goes only to the provider in the control. Test: Task 3 `detectsTrimmedKey` and `detectsDeepSeekHexKey`, Task 4 `saveStoresTrimmedKey`, Task 5 `checkSendsOneRequestToSelectedProvider`.
2. The user replaces the key with a key from another provider while a chat request or a generation runs. The running request must not reach the new provider. Test: Task 9 `staleAccountStopsRequest`.
3. No network during onboarding. The check fails, the typed key stays, nothing is saved, and «Позже» still works. Test: Task 16 `offlineCheckKeepsTypedKey`, Task 17 `failedCheckKeepsOldKey`.
4. A search call that returns citations without usable URLs. Alphy reports `search_no_usable_results`, not an empty success. Test: Task 11 `unusableCitationsFail`.
5. A reply longer than the speech engine's input limit. Alphy speaks it in sentence chunks, in order, and Stop ends all chunks. Test: Task 19 `longReplySpeaksInOrderedChunks`.

---

## Phase 0. Baseline, decisions, and the Anthropic transport

### Task 0: Record the DeepSeek live baseline

**Gate:** the user approves this batch of 6 live DeepSeek runs and its cost. This task must finish before Task 6 changes the wire.

**Files:**
- Modify: `docs/superpowers/specs/2026-11-16-unified-ai-provider-design.md` (section 16.6, a result table)
- Modify: `tools/compose_acceptance/run.py` (`run` gets a required `--serial`, as `suite` and `course` have; `manifest.serial`, `adb_command`, and `transfer_key` use it), `tools/compose_acceptance/test_harness.py`

- [ ] **Step 1: Run the 6 baseline runs with today's code and `DEEPSEEK_API_KEY`.**

First install the current debug and test APKs on `emulator-5564` (the `suite` command does this). Then run, for each `<s>` in 3 and 4 and each `<r>` in 1, 2, and 3: `python3 tools/compose_acceptance/run.py run --serial emulator-5564 --scenario <s> --repetition <r> --live`
Expected: 6 evidence directories under `evidence/compose-execution/scenarios`. A failed run is a result, not a stop.

- [ ] **Step 2: Verify each directory.** Run `python3 tools/compose_acceptance/run.py verify <dir>` for a passed run and `verify-failed <dir>` for a failed run. Expected: each command succeeds.
- [ ] **Step 3: Write the passed-run count for each scenario, the revision, and the date into spec 16.6.** The evidence directory is not in Git, so this table is the committed baseline.
- [ ] **Step 4: Commit.** Message: "Record the DeepSeek live generation baseline".

### Task 1: Pin the curated models

**Gate:** met. The user approved the spec 7.4 models on 2026-11-16.

**Files:**
- Create: `app/src/main/java/com/alpha/assistant/cloud/ProviderProfile.kt`
- Modify: `docs/superpowers/specs/2026-11-16-unified-ai-provider-design.md` (section 7.4)

**Interfaces:**
- Produces: `enum class Capability { CHAT, SEARCH, VISION, IMAGES, CLOUD_VOICE }`; `data class SpeechModel(val wire: String, val title: String, val voice: String)`; `data class ProviderProfile(val baseUrl: String, val chatPath: String, val strictChatPath: String, val jsonSchema: Boolean, val selectorModel: String, val capabilities: Set<Capability>, val chatModels: List<String>, val speechModels: List<SpeechModel>, val maxOutputTokens: Int, val reasoningOn: JSONObject, val reasoningOff: JSONObject, val selectorReasoning: JSONObject, val keyPage: String)`.

- [ ] **Step 1: List each provider's models with the user's keys.** The user runs these or approves them. They are free requests.

```bash
curl -s https://openrouter.ai/api/v1/models -H "Authorization: Bearer $OPENROUTER_KEY" | jq '.data[] | select(.architecture.input_modalities|index("image")) | select(.supported_parameters|index("tools")) | .id'
curl -s https://api.openai.com/v1/models -H "Authorization: Bearer $OPENAI_KEY" | jq '.data[].id'
curl -s https://api.anthropic.com/v1/models -H "x-api-key: $ANTHROPIC_KEY" -H "anthropic-version: 2023-06-01" | jq '.data[].id'
curl -s https://api.deepseek.com/models -H "Authorization: Bearer $DEEPSEEK_KEY" | jq '.data[].id'
```

Expected: the model IDs of spec 7.4 appear in the lists. The Anthropic list shows `structured_outputs` supported for `claude-haiku-5-5`. If a model is missing, stop and report it.

- [ ] **Step 2: Write `ProviderProfile.kt` with four profiles.** Use these values. Fill the model lists and reasoning objects from spec 7.4. Obey these rules:
  - One chat model runs every stage, including search (spec 7.5).
  - Behavior rules stay in the profile. Do not add flags for each model (spec 7.6).
  - The OpenRouter speech list has no `openai/*` model (spec 12.5).

| Field | OpenAI | Anthropic | OpenRouter | DeepSeek |
|---|---|---|---|---|
| `baseUrl` | `https://api.openai.com` | `https://api.anthropic.com` | `https://openrouter.ai` | `https://api.deepseek.com` |
| `chatPath` | `/v1/responses` | `/v1/messages` | `/api/v1/chat/completions` | `/chat/completions` |
| `strictChatPath` | same as `chatPath` | same as `chatPath` | same as `chatPath` | `/beta/chat/completions` |
| `jsonSchema` | `true` | `true` | `true` | `false` |
| `selectorModel` | `gpt-6-luna` | `claude-haiku-5-5` | `openai/gpt-6-luna` | `deepseek-flash` |
| `capabilities` | all except `CLOUD_VOICE` | `CHAT, SEARCH, VISION` | all | all except `IMAGES` |
| `chatModels` | `gpt-6.1-terra`, `gpt-6.1-sol` | `claude-sonnet-5-5`, `claude-opus-5-5` | `openai/gpt-6.1-terra`, `google/gemini-4-carbon` | `deepseek-flash` |
| `speechModels` | none | none | `google/gemini-3.8-flash-tts`, `elevenlabs/eleven-v4-turbo`, each with a voice from its voice list | `SpeechModel("tts", "Stella", "stella")` |
| `reasoningOn` / `reasoningOff` | `{"reasoning": {"effort": "medium"}}` / effort `low` | `{"output_config": {"effort": "high"}}` / effort `low` | `{"reasoning": {"effort": "medium"}}` / effort `low` | `{"thinking": {"type": "enabled"}}` / `{"thinking": {"type": "disabled"}}` |
| `selectorReasoning` | `{"reasoning": {"effort": "none"}}` | `{"thinking": {"type": "disabled"}}` | `{"reasoning": {"effort": "none"}}` | `{"thinking": {"type": "disabled"}}` |
| `maxOutputTokens` | from the model page | `128000` | from the model page | `393216` |
| `keyPage` | `https://platform.openai.com/api-keys` | `https://platform.claude.com/settings/keys` | `https://openrouter.ai/settings/keys` | `https://platform.deepseek.com/api_keys` |

Open each `keyPage` in a browser before the commit. It must show the key list after sign-in (spec 18).

- [ ] **Step 3: Commit.**

```bash
git add app/src/main/java/com/alpha/assistant/cloud/ProviderProfile.kt docs/superpowers/specs/2026-11-16-unified-ai-provider-design.md
git commit -m "Pin curated models for each AI provider"
```

### Task 2: Anthropic SDK compatibility check

**Files:**
- Modify: `app/build.gradle.kts` (dependencies)
- Create: `app/src/androidTest/java/com/alpha/assistant/cloud/LoopbackServer.kt`
- Test: `app/src/androidTest/java/com/alpha/assistant/cloud/AnthropicSdkCompatibilityTest.kt`

**Interfaces:**
- Produces: `class LoopbackServer(handler: (LoopbackRequest, OutputStream) -> Unit) : Closeable` with `val url: String` and `val requests: List<LoopbackRequest>`; `data class LoopbackRequest(val method: String, val path: String, val headers: Map<String, String>, val body: String)`. Lower-case header names. Extract it from the `Server` class in `CloudWorkflowTest.kt:83-116`. Do not check authorization in the helper. Each test asserts its own headers.

- [ ] **Step 1: Write the failing test.**

```kotlin
@Test fun sdkUsesLoopbackAndRefusesRedirects() {
    LoopbackServer { _, out -> out.write("HTTP/1.1 302 Found\r\nLocation: https://example.com/\r\nContent-Length: 0\r\n\r\n".toByteArray()) }.use { server ->
        val client = anthropicClient(server.url, "alpha-test-only")
        assertThrows(Exception::class.java) { client.messages().create(minimalParams()) }
        assertEquals(1, server.requests.size)
        assertEquals("alpha-test-only", server.requests[0].headers["x-api-key"])
        assertTrue(server.requests[0].headers.containsKey("anthropic-version"))
    }
}
@Test fun closingStreamDisconnects() // server sends one event, then waits; closing the SDK stream closes the socket within 2 s
```

- [ ] **Step 2: Run it.** Expected: FAIL to compile, `anthropicClient` is not defined.

- [ ] **Step 3: Add `implementation("com.anthropic:anthropic-java:<current>")`.** Write `internal fun anthropicClient(baseUrl: String, key: String): AnthropicClient` in `app/src/main/java/com/alpha/assistant/cloud/AnthropicWire.kt`. Turn off redirects and retries. Use the same timeouts as `CloudChatProvider` (20 s connect, 60 s read).

- [ ] **Step 4: Run the test and the debug build.**

Run: `./gradlew :app:assembleDebug` and the green test command for `com.alpha.assistant.cloud.AnthropicSdkCompatibilityTest`.
Expected: both pass. Record the APK size change in the commit message.

**Stop condition:** if the SDK conflicts with OkHttp 5.5.1, cannot refuse redirects, or does not close the socket, stop. Report to the user. Spec 5.5 (b) applies only after the user agrees.

- [ ] **Step 5: Commit.**

```bash
git add app/build.gradle.kts app/src/main/java/com/alpha/assistant/cloud/AnthropicWire.kt app/src/androidTest/java/com/alpha/assistant/cloud/LoopbackServer.kt app/src/androidTest/java/com/alpha/assistant/cloud/AnthropicSdkCompatibilityTest.kt
git commit -m "Add Anthropic Java SDK behind a loopback compatibility check"
```

### Task 2b: Remove the Anthropic SDK

**Why:** the user decided on 2026-11-16 that the SDK is not necessary. Task 2 measured +55746710 bytes on the debug APK. Spec 5.5 now picks (b): raw HTTP over `CloudHttp.open`.

**Files:**
- Modify: `app/build.gradle.kts` (remove `com.anthropic:anthropic-java`)
- Delete: `app/src/main/java/com/alpha/assistant/cloud/AnthropicWire.kt`, `app/src/androidTest/java/com/alpha/assistant/cloud/AnthropicSdkCompatibilityTest.kt`
- Keep: `LoopbackServer.kt` and the `Server` wrapper in `CloudWorkflowTest.kt`. Tasks 5 to 14 use them.

**Interfaces:**
- Removes: `anthropicClient`. Task 5 and Task 13 use OkHttp.

- [ ] **Step 1: Remove the dependency and the two files.** Search `app/src` and `app/build.gradle.kts` for `com.anthropic` and `anthropicClient`. Expected: no match.
- [ ] **Step 2: Build.** Run `./gradlew :app:assembleDebug`. Expected: success, and the debug APK size is within 1 MiB of 426783057 bytes (the size before Task 2).
- [ ] **Step 3: Run** `com.alpha.assistant.cloud.CloudWorkflowTest`. Expected: PASS, 76 tests.
- [ ] **Step 4: Commit.** Message: "Remove the Anthropic Java SDK". Record the APK size in the message.

## Phase 1. Account

### Task 3: Provider detection

**Files:**
- Create: `app/src/main/java/com/alpha/assistant/cloud/AiProvider.kt`
- Test: `app/src/androidTest/java/com/alpha/assistant/cloud/AiProviderTest.kt`

**Interfaces:**
- Consumes: `ProviderProfile` (Task 1).
- Produces: `enum class AiProvider(val id: String, val displayName: String) { OPENAI("openai", "OpenAI"), ANTHROPIC("anthropic", "Anthropic"), OPENROUTER("openrouter", "OpenRouter"), DEEPSEEK("deepseek", "DeepSeek") }` with `val profile: ProviderProfile`; `fun detectProvider(key: String): AiProvider?`; `fun AiProvider.accepts(key: String): Boolean`; `data class AiAccount(val provider: AiProvider, val key: String)`.

- [ ] **Step 1: Write the failing test.**

```kotlin
@Test fun detectsProviderFromPrefix() {
    assertEquals(AiProvider.ANTHROPIC, detectProvider("sk-ant-api03-abc"))
    assertEquals(AiProvider.OPENROUTER, detectProvider("sk-or-v1-abc"))
    assertEquals(AiProvider.OPENAI, detectProvider("sk-proj-abc"))
    assertEquals(AiProvider.OPENAI, detectProvider("sk-svcacct-abc"))
    assertEquals(AiProvider.OPENAI, detectProvider("sk-abc"))
    assertNull(detectProvider("abc"))
    assertNull(detectProvider(""))
}
@Test fun detectsTrimmedKey() = assertEquals(AiProvider.OPENROUTER, detectProvider("  sk-or-v1-abc\n"))
@Test fun detectsDeepSeekHexKey() {
    assertEquals(AiProvider.DEEPSEEK, detectProvider("sk-" + "0123456789abcdef".repeat(2)))
    assertEquals(AiProvider.OPENAI, detectProvider("sk-" + "0123456789ABCDEF".repeat(2)))
    assertEquals(AiProvider.OPENAI, detectProvider("sk-" + "0123456789abcdef".repeat(3)))
}
@Test fun rejectsAnthropicAdminKey() {
    assertFalse(AiProvider.ANTHROPIC.accepts("sk-ant-admin01-abc"))
    assertTrue(AiProvider.ANTHROPIC.accepts("sk-ant-api03-abc"))
}
@Test fun capabilitiesMatchSpec() {
    assertEquals(setOf(Capability.CHAT, Capability.SEARCH, Capability.VISION), AiProvider.ANTHROPIC.profile.capabilities)
    assertFalse(Capability.CLOUD_VOICE in AiProvider.OPENAI.profile.capabilities)
    assertTrue(Capability.IMAGES in AiProvider.OPENAI.profile.capabilities)
    assertEquals(Capability.entries.toSet(), AiProvider.OPENROUTER.profile.capabilities)
    assertEquals(Capability.entries.toSet() - Capability.IMAGES, AiProvider.DEEPSEEK.profile.capabilities)
}
```

- [ ] **Step 2: Run it.** Expected: FAIL to compile.
- [ ] **Step 3: Implement `AiProvider.kt`.** Check in this order: `sk-ant-`, `sk-or-`, `sk-proj-` and `sk-svcacct-`, the DeepSeek form `^sk-[0-9a-f]{32}$`, then any other `sk-`.
- [ ] **Step 4: Run it.** Expected: PASS.
- [ ] **Step 5: Commit.** Message: "Detect the AI provider from the key".

### Task 4: Account store

**Files:**
- Modify: `app/src/main/java/com/alpha/assistant/data/SecureKeyStore.kt`
- Modify: `app/src/main/java/com/alpha/assistant/cloud/CloudKey.kt`
- Create: `app/src/main/java/com/alpha/assistant/data/LegacyKeys.kt`
- Modify (call sites of `save(`): `app/src/androidTest/java/com/alpha/assistant/ui/ShellRobot.kt:128`, `CloudWorkflowTest.kt:124`, and every other test that calls `SecureKeyStore.save`
- Test: `app/src/androidTest/java/com/alpha/assistant/data/ShellStorageTest.kt`, `app/src/androidTest/java/com/alpha/assistant/data/LegacyKeysTest.kt`

**Interfaces:**
- Consumes: `AiProvider`, `AiAccount` (Task 3).
- Produces:
  - `SecureKeyStore(context, name = "alpha_ai_credentials", alias = "alpha.ai.v1")`
  - `fun save(value: String, provider: AiProvider): Result<Unit>`: one commit writes `ciphertext`, `iv`, `provider`, and `consent`.
  - `fun account(): Result<AiAccount?>`: a stored key without a provider is a failure.
  - `clear()` also removes `provider`.
  - `fun SecureKeyStore.cloudAccount(context: Context): AiAccount` in `CloudKey.kt`, with the failure order of `cloudKey()`. `cloudKey(context)` returns `cloudAccount(context).key`.
  - `class LegacyKeys(context: Context, deepSeekName: String = "alpha_credentials", deepSeekAlias: String = "alpha.deepseek.v1", imageName: String = "alpha_images_credentials", imageAlias: String = "alpha.alpha_images.openrouter.v1")` with `fun deepSeekKey(): Result<String?>`, `fun hasDeepSeekKey(): Boolean`, `fun clearDeepSeekKey(): Result<Unit>`, `fun openRouterImageKey(): Result<String?>`, `fun hasOpenRouterImageKey(): Boolean`, `fun clearOpenRouterImageKey(): Result<Unit>`. It wraps one `SecureKeyStore` for each old store and reads only the key, because the old stores have no provider field. A clear also deletes the Keystore alias.

- [ ] **Step 1: Write the failing tests.**

```kotlin
@Test fun saveWritesProviderWithKeyAndConsent() {
    assertTrue(keys.save("sk-or-v1-abc", AiProvider.OPENROUTER).isSuccess)
    assertEquals(AiAccount(AiProvider.OPENROUTER, "sk-or-v1-abc"), keys.account().getOrThrow())
    assertTrue(keys.consented())
}
@Test fun saveStoresTrimmedKey() {
    keys.save("  sk-proj-abc\n", AiProvider.OPENAI)
    assertEquals("sk-proj-abc", keys.account().getOrThrow()!!.key)
}
@Test fun providerChangeChangesConsentIdentity() {
    keys.save("sk-proj-abc", AiProvider.OPENAI); val first = keys.consentIdentity()
    keys.save("sk-ant-api03-abc", AiProvider.ANTHROPIC)
    assertNotEquals(first, keys.consentIdentity())
}
@Test fun newStoreNeverReadsDeepSeekStore() { // LegacyKeysTest
    SecureKeyStore(context, legacyName, legacyAlias).save("sk-deepseek", AiProvider.DEEPSEEK)
    assertNull(SecureKeyStore(context, newName, newAlias).account().getOrThrow())
    assertTrue(LegacyKeys(context, legacyName, legacyAlias, imageName, imageAlias).hasDeepSeekKey())
}
@Test fun legacyKeysStayUntilCleared() // hasDeepSeekKey() stays true after a new account save; false only after clearDeepSeekKey()
```

The test passes isolated store names to `LegacyKeys`.

- [ ] **Step 2: Run** `com.alpha.assistant.data.ShellStorageTest,com.alpha.assistant.data.LegacyKeysTest`. Expected: FAIL to compile.
- [ ] **Step 3: Implement.** Update every test call site to `save("alpha-test-only", AiProvider.OPENAI)`.
- [ ] **Step 4: Run both classes and `com.alpha.assistant.ui.SettingsConsentWorkflowTest`.** Expected: PASS.
- [ ] **Step 5: Commit.** Message: "Store one AI account with its provider".

### Task 5: Key check

**Files:**
- Create: `app/src/main/java/com/alpha/assistant/cloud/KeyCheck.kt`
- Create: `app/src/main/java/com/alpha/assistant/cloud/Endpoints.kt`
- Test: `app/src/androidTest/java/com/alpha/assistant/cloud/KeyCheckTest.kt`

**Interfaces:**
- Consumes: `AiAccount`, `ProviderProfile`, `LoopbackServer`.
- Produces:
  - `fun interface Endpoints { fun base(provider: AiProvider): String }` with `Endpoints.PRODUCTION` (the profile `baseUrl`).
  - `fun Endpoints.checked(provider: AiProvider): String`: it accepts the production URL or `http` on `127.0.0.1` or `localhost`, and nothing else. This moves the rule from `CloudProvider.kt:229-233`.
  - `enum class KeyCheck { ACCEPTED, LIMITED, NO_CREDIT, REJECTED, UNREACHABLE }`
  - `suspend fun checkKey(account: AiAccount, endpoints: Endpoints = Endpoints.PRODUCTION): KeyCheck`

Requests: OpenRouter `GET /api/v1/key` with `Authorization: Bearer`. OpenAI `GET /v1/models` with `Authorization: Bearer`. Anthropic `GET /v1/models` with `x-api-key` and `anthropic-version` (the value from the current Anthropic API documentation), and no `authorization` header. DeepSeek `GET /user/balance` with `Authorization: Bearer`. A DeepSeek 200 with `is_available: false` gives `NO_CREDIT`. Otherwise HTTP 200 gives `ACCEPTED`, 403 gives `LIMITED`, 401 gives `REJECTED`. A network failure or 5xx gives `UNREACHABLE`. Timeout: 10 s. Redirects are refused.

- [ ] **Step 1: Write the failing test.** One test for each provider and status:

```kotlin
@Test fun openRouterCheckUsesKeyEndpoint() = loopback(200) { url ->
    assertEquals(KeyCheck.ACCEPTED, runBlocking { checkKey(AiAccount(AiProvider.OPENROUTER, "alpha-test-only"), Endpoints { url }) })
    assertEquals("GET /api/v1/key", it.requests.single().let { r -> "${r.method} ${r.path}" })
    assertEquals("Bearer alpha-test-only", it.requests.single().headers["authorization"])
}
@Test fun rejectedAndLimitedKeys() // 401 gives REJECTED and 403 gives LIMITED, for all four providers
@Test fun deepSeekEmptyBalanceIsNoCredit() // GET /user/balance answers 200 with {"is_available": false, "balance_infos": []}: NO_CREDIT
@Test fun checkSendsOneRequestToSelectedProvider() // a DeepSeek-form key that DeepSeek rejects: exactly one request, to /user/balance, and no request to another provider
@Test fun unreachableWhenServerClosed() // a closed port gives UNREACHABLE
@Test fun redirectIsNotFollowed() // 302 gives UNREACHABLE and one request
@Test fun anthropicCheckUsesApiKeyHeader() // GET /v1/models: x-api-key is the key, anthropic-version is set, no authorization header
```

- [ ] **Step 2: Run it.** Expected: FAIL to compile.
- [ ] **Step 3: Implement with OkHttp** for all four providers (as `OpenRouterImages` does).
- [ ] **Step 4: Run it.** Expected: PASS.
- [ ] **Step 5: Commit.** Message: "Check an AI key with its provider before saving".

## Phase 2. Chat

### Task 6: Wire seam and OpenAI-compatible wire

**Files:**
- Rename: `app/src/main/java/com/alpha/assistant/cloud/CloudProvider.kt` class `DeepSeekProvider` to `CloudChatProvider`. Update every reference (main: `ShellViewModel.kt:100`, `AgentAutomationRuntime.kt:195`, `SourceRefreshJobService.kt:139`; tests: about 80 references in 10 files).
- Create: `app/src/main/java/com/alpha/assistant/cloud/ChatWire.kt`
- Create: `app/src/main/java/com/alpha/assistant/cloud/OpenAiCompatibleWire.kt` (move `stream` and `checkStatus` here from `CloudProvider.kt:1124-1281`)
- Create: `app/src/main/java/com/alpha/assistant/cloud/CloudHttp.kt` (move `open` here)
- Modify: `app/src/androidTest/java/com/alpha/assistant/cloud/CloudWorkflowTest.kt` (the `Fixture` only), `app/src/androidTest/java/com/alpha/assistant/tools/ToolCancellationTest.kt`
- Test: `app/src/androidTest/java/com/alpha/assistant/cloud/OpenAiCompatibleProfileTest.kt`

**Interfaces:**
- Consumes: `AiAccount`, `Endpoints`, `ProviderProfile`, `SecureKeyStore.account()`.
- Produces:
  - `internal data class ToolCall(val id: String, val name: String, val arguments: String)`
  - `internal data class Completion(val text: String, val calls: List<ToolCall>, val replay: JSONArray? = null)`
  - `internal enum class CompletionStage` (moved from `CloudProvider.kt:243`)
  - `internal data class StreamLimits(val maxText: Int, val allowTools: Boolean, val reportLength: Boolean, val stage: CompletionStage, val maxWireChars: Int, val retainReasoning: Boolean)`
  - `internal interface ChatWire { suspend fun complete(key: String, body: JSONObject, limits: StreamLimits, session: CloudSession, onText: suspend (String) -> Unit): Completion }`
  - `class CloudChatProvider internal constructor(accounts: () -> AiAccount?, endpoints: Endpoints, chatModel: (AiProvider) -> String, onHttpCall: (() -> Unit)?, responseReadTimeoutMillis: Int, recordEmptyCompletion: (String) -> Unit) : ChatProvider, CloudSearchProvider`, plus `constructor(keys: SecureKeyStore)`. That constructor uses the first model in `profile.chatModels`. Task 18 changes it to `constructor(keys: SecureKeyStore, models: AiModels)`.

The canonical request: Alphy logic keeps building Chat Completions JSON. It stops setting `model`. It keeps `thinking` (`enabled` or `disabled`) as a canonical marker. It attaches `Completion.replay` to an assistant `tool_calls` message as `reasoning_replay`. `OpenAiCompatibleWire` maps them:

| Canonical | OpenRouter | DeepSeek |
|---|---|---|
| no `model` | `chatModel(OPENROUTER)` | `chatModel(DEEPSEEK)` |
| `thinking` | `profile.reasoningOn` or `reasoningOff`, merged into the body | same (today's `thinking` object) |
| `reasoning_replay` | `reasoning_details` | `reasoning_content`, as today |
| path | `profile.chatPath` | `strictChatPath` for a strict decision stage, as today |
| `max_tokens` | `min(value, profile.maxOutputTokens)` | same |
| (added) | `provider: {"require_parameters": true, "data_collection": "deny"}` | nothing |

OpenAI gets its own wire in Task 7, and Anthropic in Task 13. Until then, an account of those providers fails with `CloudFailure("provider_unsupported", …)` before any HTTP call.

- [ ] **Step 1: Keep the DeepSeek fixture.** In `CloudWorkflowTest`, `Fixture` saves `AiAccount(DEEPSEEK, "alpha-test-only")` and builds `CloudChatProvider(accounts = { AiAccount(DEEPSEEK, "alpha-test-only") }, endpoints = Endpoints { server.url }, …)`. Change no assertion: the 76 tests now check the DeepSeek profile.
- [ ] **Step 2: Write `OpenAiCompatibleProfileTest`** with a loopback for each profile difference:
  - `openRouterUsesStandardPathAndReasoningEffort`: every path is `/api/v1/chat/completions`, never `/beta`. `model` is the first OpenRouter curated model. No `thinking` key. The decision request has the `reasoningOff` fields, and a PLAN request has the `reasoningOn` fields.
  - `openRouterReplaysReasoningDetails`: a tool round where the first response streams `reasoning_details` deltas, and the second request carries them in the assistant message as `reasoning_details`.
  - `openRouterRequestsRequireParametersAndNoDataCollection`: every request body has `provider.require_parameters == true` and `provider.data_collection == "deny"`.
- [ ] **Step 3: Run** `com.alpha.assistant.cloud.CloudWorkflowTest,com.alpha.assistant.cloud.OpenAiCompatibleProfileTest` with `--expect failed`. Expected: FAIL (no `CloudChatProvider`).
- [ ] **Step 4: Implement the seam and `OpenAiCompatibleWire`.** Put the shared HTTP transport (`open`, with redirect refusal, timeouts, and cancellation) in `CloudHttp.kt`, so Task 7 can use it. Keep every parser rule from `CloudProvider.kt:1129-1245`: `[DONE]`, finish reasons, tool-call indexes 0..1, size limits, `stream_invalid`, `empty_response`, and the `pre_headers_*` and `stream_*` IO codes. Keep the DeepSeek `reasoning_content` replay of every earlier turn (spec 4.3). Read OpenRouter `reasoning_details` into `Completion.replay` when `retainReasoning` is set.
- [ ] **Step 5: Run** `com.alpha.assistant.cloud.CloudWorkflowTest,com.alpha.assistant.cloud.OpenAiCompatibleProfileTest,com.alpha.assistant.tools.ToolCancellationTest`. Expected: PASS, with all 76 `CloudWorkflowTest` assertions unchanged.
- [ ] **Step 6: Commit.** Message: "Split the cloud client into Alphy logic and an OpenAI-compatible wire".

### Task 7: OpenAI Responses wire

**Files:**
- Create: `app/src/main/java/com/alpha/assistant/cloud/OpenAiResponsesWire.kt`
- Modify: `CloudProvider.kt` (`CloudChatProvider` uses the new wire for `AiProvider.OPENAI`)
- Create: `app/src/androidTest/java/com/alpha/assistant/cloud/OpenAiResponsesWorkflowTest.kt`

**Interfaces:**
- Consumes: `ChatWire`, `Completion`, `StreamLimits`, `CompletionStage`, `open` in `CloudHttp.kt` (Task 6), `ProviderProfile` (Task 1).
- Produces: `internal class OpenAiResponsesWire(endpoints: Endpoints, model: () -> String) : ChatWire`. `CloudChatProvider` uses it for `OPENAI`, and `OpenAiCompatibleWire` for `OPENROUTER` and `DEEPSEEK`. `provider_unsupported` from Task 6 stays only for `ANTHROPIC`, until Task 13.

Translation of the canonical body (spec 8.9):

| Canonical | OpenAI Responses |
|---|---|
| `messages[role=system]` | top-level `instructions`, joined with blank lines |
| user and assistant text | `input` message items, with `input_text` or `output_text` content |
| `image_url` data URL | `input_image` content with the same data URL in `image_url` |
| `tools[].function` with `strict` | flat `tools[]`: `{"type": "function", "name", "description", "parameters", "strict": true}` |
| `tool_choice` named function | `{"type": "function", "name": <name>}` |
| `tool_choice` `"required"`, `"auto"`, `"none"` | same |
| `response_format: json_object` | `text.format: {"type": "json_object"}` |
| `thinking` | `profile.reasoningOn` or `reasoningOff`, merged into the body |
| assistant `tool_calls` | `function_call` items with `call_id`, `name`, and `arguments` |
| `reasoning_replay` | the reasoning items, unchanged, with `encrypted_content`, before their `function_call` items |
| tool messages | `function_call_output` items with the same `call_id` |
| `max_tokens` | `max_output_tokens = min(value, profile.maxOutputTokens)` |
| (added) | `stream: true`, `store: false` |

Stream events map to `Completion`:

- `response.output_text.delta` gives text.
- `response.output_item.added` with type `function_call` opens a call with its `call_id` and `name`. `response.function_call_arguments.delta` adds arguments. A third call breaks the call limit, as a third tool-call index does today.
- `response.output_item.done` with type `reasoning` adds the item to `replay` when `retainReasoning` is set.
- `response.completed` ends the stream: finish `tool_calls` when calls exist, else `stop`. `response.incomplete` with reason `max_output_tokens` maps to `length`.
- A `refusal` content part gives `CloudFailure("refusal", …)`, and Alphy drops the partial text. `response.failed`, `error`, and a stream without `response.completed` give `stream_invalid`.

- [ ] **Step 1: Write the failing tests** against a loopback Responses fixture (SSE with `event:` and `data:` lines):

```kotlin
@Test fun usesResponsesPathWithoutStorage() {
    val request = fixture.firstRequest(textRequest("Hi"))
    assertEquals("/v1/responses", request.path)
    assertFalse(request.json.getBoolean("store"))
    assertTrue(request.json.getBoolean("stream"))
    assertEquals("gpt-6.1-terra", request.json.getString("model"))
    assertFalse(request.json.has("messages"))
}
@Test fun systemBecomesInstructions() // the system text is in instructions, and input has no system item
@Test fun strictToolsAreFlat() // tools[0] has type function, name, parameters, and strict true, and no function key
@Test fun decisionForcesNamedTool() // tool_choice == {"type":"function","name":"submit_chat_decision"}, and reasoning.effort is low
@Test fun planUsesReasoningOn() // a PLAN request has reasoning.effort medium and text.format.type json_object
@Test fun toolRoundReplaysReasoningItems() // the first response streams a reasoning item with encrypted_content and a function_call. The second request input repeats the reasoning item unchanged before the function_call item, then a function_call_output with the same call_id
@Test fun imagesBecomeInputImage() // a data:image/png;base64 turn gives an input_image with the same data URL
@Test fun answerStreamsText() // three output_text.delta events give the full answer and RoundComplete
@Test fun incompleteIsLength() // response.incomplete with reason max_output_tokens gives the length outcome of today's parser
@Test fun refusalStopsWithRefusalCode() // a refusal content part gives code "refusal", and no AnswerFragment is stored
@Test fun missingCompletedIsStreamInvalid() // the stream ends after a text delta with no response.completed: code stream_invalid
@Test fun maxTokensIsClamped() // canonical 131072 gives max_output_tokens == profile.maxOutputTokens
```

- [ ] **Step 2: Run** `com.alpha.assistant.cloud.OpenAiResponsesWorkflowTest` with `--expect failed`. Expected: FAIL.
- [ ] **Step 3: Implement it.** Close the stream when `session.cancel()` runs. Count wire characters against `StreamLimits.maxWireChars`.
- [ ] **Step 4: Run it, plus `CloudWorkflowTest#recordedPromptReplay*` with an OpenAI account and `OpenAiCompatibleProfileTest`.** Expected: PASS.
- [ ] **Step 5: Commit.** Message: "Add the OpenAI Responses wire".

### Task 8: Selector in the Jev role with native structured output

**Files:**
- Create: `app/src/main/java/com/alpha/assistant/cloud/OpenAiDecisions.kt`
- Modify: `app/src/main/java/com/alpha/assistant/cloud/CloudProvider.kt` (`CloudRequest`)
- Modify: `app/src/main/java/com/alpha/assistant/tools/generation/JevSelector.kt`
- Modify: `app/src/main/java/com/alpha/assistant/tools/ToolEngine.kt:631-643` (the selector lambda)
- Modify: `app/src/main/java/com/alpha/assistant/cloud/OpenAiCompatibleWire.kt`, `OpenAiResponsesWire.kt`
- Test: `app/src/androidTest/java/com/alpha/assistant/tools/generation/JevSchemaTest.kt`, `app/src/androidTest/java/com/alpha/assistant/cloud/SelectorWireTest.kt`

**Interfaces:**
- Consumes: `ProviderProfile.jsonSchema`, `selectorModel`, and `selectorReasoning` (Task 1), `OpenAiCompatibleWire` (Task 6), `OpenAiResponsesWire` (Task 7), and today's `JevQuestion` and `JevSelector`.
- Produces:
  - `CloudRequest.responseSchema: JSONObject? = null`. The default keeps every `ChatProvider` fake unchanged.
  - `internal fun JevSelector.answerSchema(body: String): JSONObject`. It reads the `questions` of a selector body and builds the spec 8.8 schema, with no `minItems` or `maxItems`. The ToolEngine selector lambda passes it as `responseSchema`.
  - Wire rule: for a request with `responseSchema` and a profile with `jsonSchema = true`, `OpenAiCompatibleWire` sends `response_format: {"type": "json_schema", "json_schema": {"name": "selector_answers", "strict": true, "schema": responseSchema}}` in place of `json_object`. `OpenAiResponsesWire` sends a SELECTOR request to `POST /v1/decisions` instead, with no schema, no reasoning, and no streaming (spec 5.6 (d), 8.8). On the other wires, a SELECTOR request uses `profile.selectorModel` and `profile.selectorReasoning`.
  - `internal fun decisionsRequest(selectorBody: String, model: String): JSONObject` and `internal fun selectorAnswers(decisions: JSONObject): String` in `OpenAiDecisions.kt`. Out: `noul` gives `{"type": "predicate", "name": id, "instructions": prompt}`. `choice` gives `{"type": "choice", "name": id, "instructions": prompt, "choices": [{"value": "A", "description": <option text>}, …]}`. `input` is one `user` message with an `input_json` part that holds `stage`, `title`, and `description`. Back: `predicate` gives `{"id", "probability"}`. `choice` gives `{"id", "choice", "confidence", "probabilities": {letter: probability}}`. `refusal` gives no answer for that ID. Values pass through unchanged. A profile with `jsonSchema = false` (DeepSeek) keeps today's request.
  - Task 13 sends the same schema through `output_config.format`.
- `JevAsk`, `JevSelector.ask`, and `JevSelector.parse` do not change.

- [ ] **Step 1: Write the failing tests.**

```kotlin
// JevSchemaTest
@Test fun schemaMatchesQuestions() {
    val body = selectorBody(JevQuestion("s_a", "Section?", listOf("List", "Form", "Chart")), JevQuestion("q_b", "Blocks?"))
    val schema = JevSelector.answerSchema(body)
    val branches = schema.at("properties.answers.items.anyOf")
    assertEquals(listOf("s_a"), branches.getJSONObject(0).enumOf("id"))
    assertEquals(listOf("A", "B", "C"), branches.getJSONObject(0).enumOf("choice"))
    assertEquals(listOf("A", "B", "C"), branches.getJSONObject(0).at("properties.probabilities.required").toStrings())
    assertEquals(listOf("q_b"), branches.getJSONObject(1).enumOf("id"))
    assertTrue(everyObjectClosed(schema))
}
@Test fun largestSchemaFitsLimits() // 20 questions with 26 letters each: at most 16 properties use anyOf or type arrays, and no maxItems
@Test fun schemaValidReplyStillNeedsSumOfOne() // a reply that fits the schema, with probabilities that sum to 0.8, still gives a PartsContractFailure

// SelectorWireTest
@Test fun openAiSelectorUsesDecisions() // the SELECTOR request is POST /v1/decisions with model gpt-6-luna; a noul question is a predicate with name and instructions; a choice question is a choice with choices [{value A, description List}, …]; input holds stage, title, and description as input_json; the body has no text, reasoning, stream, or tools
@Test fun decisionsAnswersBecomeSelectorAnswers() // a predicate 0.8 and a choice B (A 0.1, B 0.85, C 0.05, confidence 0.8) give JevResult probability 0.8 and choice index 1 through JevSelector.ask
@Test fun decisionsRefusalIsAMissingAnswer() // a refusal for a presentation question keeps the functional answers and gives a presentation failure, as JevSelector.parse does today
@Test fun openRouterSelectorSendsJsonSchemaAndRequiresParameters() // response_format.type json_schema, strict true, model == selectorModel, reasoning.effort none, and provider.require_parameters true
@Test fun deepSeekSelectorKeepsJsonObject() // response_format.type json_object, model deepseek-flash, thinking disabled
@Test fun otherStagesKeepJsonObject() // a PLAN request on OpenAI still sends text.format json_object and the chat model
```

- [ ] **Step 2: Run** `com.alpha.assistant.tools.generation.JevSchemaTest,com.alpha.assistant.cloud.SelectorWireTest` with `--expect failed`. Expected: FAIL.
- [ ] **Step 3: Implement.** Build the schema from the body, not from new arguments, so `JevAsk` keeps its signature. The Decisions call is one non-streaming POST through `open` (Task 6) with `Authorization: Bearer` and a read bounded by `StreamLimits.maxWireChars`. The wire passes the converted answers to `onText` once and returns them as `Completion.text`.
- [ ] **Step 4: Run** both classes, plus `PartsGenerationWorkflowTest`, `NativePresentationWorkflowTest`, `LessonPartsWorkflowTest`, `QuickLogGenerationWorkflowTest` (package `com.alpha.assistant.tools.generation`), and `com.alpha.assistant.cloud.CloudWorkflowTest`. Expected: PASS.
- [ ] **Step 5: Commit.** Message: "Use native structured output for the Jev selector".

### Task 9: Route by account

**Files:**
- Modify: `app/src/main/java/com/alpha/assistant/cloud/CloudProvider.kt`
- Modify: `app/src/main/java/com/alpha/assistant/ui/ShellViewModel.kt:100-102`, `app/src/main/java/com/alpha/assistant/automations/AgentAutomationRuntime.kt:195`, `app/src/main/java/com/alpha/assistant/scheduling/SourceRefreshJobService.kt:139`
- Test: `app/src/androidTest/java/com/alpha/assistant/cloud/AccountRoutingTest.kt`

**Interfaces:**
- Consumes: `ChatWire`, `SecureKeyStore.account()`.
- Produces: in `respond` and `search`, `CloudChatProvider` reads `accounts()`. If the account is null or `account.key != key`, it throws `CloudFailure("stale_account", …)` before any HTTP call. Otherwise it picks the wire for `account.provider`. `supportsSceneVision` returns `Capability.VISION in account.provider.profile.capabilities` (false without an account).

- [ ] **Step 1: Write the failing tests.**

```kotlin
@Test fun staleAccountStopsRequest() {
    var account = AiAccount(AiProvider.OPENAI, "alpha-test-only")
    LoopbackServer { _, _ -> fail("no HTTP call expected") }.use { server ->
        val provider = cloudChatProvider({ account }, server.url)
        account = AiAccount(AiProvider.ANTHROPIC, "other-key")
        val failure = assertThrows(CloudFailure::class.java) { runBlocking { provider.respond("alpha-test-only", textRequest("Hi"), CloudSession()) {} } }
        assertEquals("stale_account", failure.code)
        assertTrue(server.requests.isEmpty())
    }
}
@Test fun openRouterAccountUsesOpenRouterPath() // the request path is /api/v1/chat/completions
@Test fun openAiAccountUsesResponsesPath() // the request path is /v1/responses
@Test fun sceneVisionFollowsAccount() // true for each provider; false with no account
```

- [ ] **Step 2: Run** `com.alpha.assistant.cloud.AccountRoutingTest`. Expected: FAIL.
- [ ] **Step 3: Implement.** In `ShellViewModel`, move `keys` before `provider` in the constructor, so the default can be `CloudChatProvider(keys)`.
- [ ] **Step 4: Run** `AccountRoutingTest` and `com.alpha.assistant.tools.ThreeSceneWorkflowTest`. Expected: PASS.
- [ ] **Step 5: Commit.** Message: "Route cloud requests by the stored AI account".

### Task 10: Provider errors

**Files:**
- Create: `app/src/main/java/com/alpha/assistant/cloud/ProviderErrors.kt`
- Modify: `OpenAiCompatibleWire.kt`, `OpenAiResponsesWire.kt`, `AnthropicWire.kt` (in Task 13), `ImageGeneration.kt` (in Task 15)
- Test: `app/src/androidTest/java/com/alpha/assistant/cloud/ProviderErrorsTest.kt`

**Interfaces:**
- Produces: `internal fun providerFailure(provider: AiProvider, status: Int, body: String?, search: Boolean): CloudFailure`. It maps statuses by spec 13.2. Codes: `auth`, `quota`, `rate_limit`, `unavailable`, `refusal`, `http_<status>`. Messages start with `provider.displayName`. With `search`, they start with «Поиск, <provider>: ».

- [ ] **Step 1: Write the failing test.**

```kotlin
@Test fun mapsQuotaForEachProvider() {
    assertEquals("quota", providerFailure(AiProvider.OPENROUTER, 402, null, false).code)
    assertEquals("quota", providerFailure(AiProvider.OPENAI, 429, """{"error":{"code":"insufficient_quota"}}""", false).code)
    assertEquals("rate_limit", providerFailure(AiProvider.OPENAI, 429, """{"error":{"code":"rate_limit_exceeded"}}""", false).code)
    assertEquals("unavailable", providerFailure(AiProvider.ANTHROPIC, 529, null, false).code)
    assertEquals("quota", providerFailure(AiProvider.DEEPSEEK, 402, null, false).code)
    assertTrue(providerFailure(AiProvider.OPENROUTER, 402, null, false).message.startsWith("OpenRouter"))
}
@Test fun anthropicLowCreditIsQuota() // the 400 body that the builder captures from the real API (spec 13.3) gives "quota"
@Test fun authErrorsOpenSettings() = assertTrue(providerFailure(AiProvider.OPENAI, 401, null, false).code in KEY_ERRORS)
@Test fun streamRefusalsUseRefusalCode() // loopback: OpenRouter finish_reason "content_filter" and an OpenAI Responses refusal content part both give code "refusal", and no AnswerFragment is stored
```

- [ ] **Step 2: Run it.** Expected: FAIL.
- [ ] **Step 3: Implement it, and replace `checkStatus`.** The wire reads at most 16 KiB of an error body. It never logs the body. Update the `CloudWorkflowTest` error-text assertions to the texts that start with «DeepSeek».
- [ ] **Step 4: Run it.** Expected: PASS.
- [ ] **Step 5: Commit.** Message: "Map provider errors with the provider name".

## Phase 3. Search

### Task 11: Search primitive for OpenRouter, OpenAI, and DeepSeek

**Files:**
- Create: `app/src/main/java/com/alpha/assistant/cloud/WebSearch.kt`
- Modify: `CloudProvider.kt` (`search` at L1090-1121 routes by account)
- Test: `app/src/androidTest/java/com/alpha/assistant/cloud/WebSearchTest.kt`

**Interfaces:**
- Consumes: `Endpoints`, `providerFailure`, `validSourceUrl`, `requireWebSearchQuery`.
- Produces:
  - `internal interface SearchWire { suspend fun search(key: String, query: String, session: CloudSession): List<Source> }`
  - `OpenRouterSearch(endpoints, model)`: `POST /api/v1/chat/completions`, `stream: false`, `tools: [{"type": "openrouter:web_search", "parameters": {"engine": "exa", "max_results": 5}}]`, and the `provider` object of Task 6.
  - `DeepSeekSearch(endpoints)`: today's `/v1/tools/search` code from `CloudProvider.kt:1090-1121`, moved without a change. The about 20 `CloudWorkflowTest` search tests stay unchanged and now check it.
  - `OpenAiSearch(endpoints, model)`: `POST /v1/responses`, `store: false`, `tools: [{"type": "web_search"}]`, `include: ["web_search_call.action.sources"]`.
  - `OpenRouterSearch` and `OpenAiSearch` ask the model for a JSON array `[{"url", "title", "publishedAt"}]` (`publishedAt` is ISO 8601 or null). Alphy keeps only entries whose URL is also in the provider's own sources: annotations for OpenRouter, and `sources` plus annotations for OpenAI. The excerpt comes from the annotation `content` (OpenRouter), or from the cited answer text (OpenAI).
  - The output contract stays: at most 5 sources, `validSourceUrl`, title of at most 300 characters, excerpt of at most 6,000 characters, response body of at most 1 MiB, and `search_no_usable_results` when citations exist but none is usable.

- [ ] **Step 1: Write the failing tests.**

```kotlin
@Test fun openRouterSearchMapsAnnotations() // the loopback returns 2 url_citation annotations with content, plus a JSON list with the same URLs. The result is 2 sources with the excerpts from content, and the request carries the openrouter:web_search tool with engine exa and max_results 5.
@Test fun openAiSearchUsesResponsesApi() // path /v1/responses, store false, tools[0].type web_search, include contains web_search_call.action.sources
@Test fun dropsUrlsThatProviderDidNotCite() // a JSON entry whose URL is in no annotation is dropped
@Test fun unusableCitationsFail() {
    // annotations contain only "javascript:alert(1)" and "ftp://x"
    val failure = assertThrows(CloudFailure::class.java) { runBlocking { search("Fact") } }
    assertEquals("search_no_usable_results", failure.code)
}
@Test fun publishedAtFromModelJson() // "2026-11-01T00:00:00Z" is kept, and "yesterday" becomes null
```

- [ ] **Step 2: Run** `com.alpha.assistant.cloud.WebSearchTest` with `--expect failed`. Expected: FAIL.
- [ ] **Step 3: Implement.** `CloudChatProvider.search` routes by account. `SourceRefreshJobService` keeps calling `CloudChatProvider.search`.
- [ ] **Step 4: Run** `WebSearchTest`, `CloudWorkflowTest`, and `com.alpha.assistant.courses.ShellCourseHostWorkflowTest`. Expected: PASS, with the DeepSeek search tests unchanged.
- [ ] **Step 5: Commit.** Message: "Search the web through the account's provider".

## Phase 4. Vision

### Task 12: Picture shrinking

**Files:**
- Modify: `app/src/main/java/com/alpha/assistant/cloud/CloudProvider.kt` (`ImageInput`, L185-216)
- Modify: `CloudWorkflowTest.kt` (the image byte-equality test)
- Test: `app/src/androidTest/java/com/alpha/assistant/cloud/ImageShrinkTest.kt`

**Interfaces:**
- Produces: in the `ImageInput` companion object, `const val MAX_SEND_EDGE = 2048` and `internal fun forSending(bytes: ByteArray, mime: String): Pair<ByteArray, String>`. `dataUrl` uses it. A picture whose long edge is 2048 px or less keeps its bytes. A PNG keeps `image/png`. JPEG, GIF, and WebP above the limit become `image/jpeg` at quality 90.

- [ ] **Step 1: Write the failing test.**

```kotlin
@Test fun shrinksLargeJpeg() {
    val (bytes, mime) = ImageInput.forSending(jpeg(4000, 3000), "image/jpeg")
    assertEquals("image/jpeg", mime); assertEquals(2048 to 1536, bounds(bytes))
}
@Test fun keepsPngAsPng() = assertEquals("image/png", ImageInput.forSending(png(3000, 1000), "image/png").second)
@Test fun smallImageKeepsBytes() { val input = png(1000, 800); assertArrayEquals(input, ImageInput.forSending(input, "image/png").first) }
@Test fun fiftyMegapixelPhotoFitsOpenAiPatchLimit() // 8160x6120 gives a long edge of 2048, which is under 30,000 32x32 patches
```

- [ ] **Step 2: Change the `CloudWorkflowTest` image test.** It asserts that a picture over 2048 px arrives shrunk, and that a small picture arrives with equal bytes.
- [ ] **Step 3: Run both classes.** Expected: FAIL.
- [ ] **Step 4: Implement it with `BitmapFactory` and `Bitmap.createScaledBitmap`.** Recycle the bitmaps.
- [ ] **Step 5: Run both classes.** Expected: PASS.
- [ ] **Step 6: Commit.** Message: "Shrink pictures to 2048 px before sending".

## Phase 5. Anthropic

### Task 13: Anthropic wire

**Files:**
- Create: `app/src/main/java/com/alpha/assistant/cloud/AnthropicWire.kt`
- Create: `app/src/androidTest/java/com/alpha/assistant/cloud/AnthropicWorkflowTest.kt`

**Interfaces:**
- Consumes: `ChatWire`, `Completion`, `StreamLimits`, `CloudRequest.responseSchema` (Task 8), `open` in `CloudHttp.kt` (Task 6), `providerFailure`.
- Produces: `internal class AnthropicWire(endpoints: Endpoints, model: () -> String) : ChatWire`. `CloudChatProvider` uses it for `ANTHROPIC`, and `provider_unsupported` goes. Also `internal object StructuredCalls` (spec 8.7) with:
  - `fun forced(body: JSONObject): JSONArray?`: the tool definitions that `tool_choice` forces (one named function, or every tool for `"required"`). It returns null for `"auto"`, `"none"`, or no tools.
  - `fun schema(tools: JSONArray): JSONObject`: a root object with one required `calls` array. Each item is `anyOf` one branch for each tool: `{"name": {"type": "string", "enum": [<tool name>]}, "arguments": <tool parameters>}`. Every object has `additionalProperties: false`. No `minItems` or `maxItems`.
  - `fun calls(json: String, tools: JSONArray): List<ToolCall>`: it gives each call a new ID `call_<uuid>` and serializes `arguments`. Invalid JSON or an unknown name gives `CloudFailure("tool_invalid", …)`.

Translation of the canonical body:

| Canonical | Anthropic |
|---|---|
| `messages[role=system]` | top-level `system` |
| `image_url` data URL | `image` block, `source.type = "base64"`, the media type from the data URL |
| `tools[].function` with `strict` | `tools[]` with `input_schema` and `strict: true` |
| `tool_choice` that forces tools (a named function or `"required"`) | no tools. `output_config.format` = `{"type": "json_schema", "schema": StructuredCalls.schema(tools)}`. `StructuredCalls.calls` turns the reply into tool calls. |
| `responseSchema` (the selector) | `output_config.format` = `{"type": "json_schema", "schema": responseSchema}`. With `jsonSchema = false`, the same as `response_format: json_object`. |
| `tool_choice: "auto"` or `"none"` | same |
| `response_format: json_object` | the system line «Return one JSON object.» No prefill. Alphy logic validates and repairs the JSON as today (spec 8.3). |
| `thinking` | adaptive thinking. `output_config.effort` from `profile.reasoningOn` or `reasoningOff`. |
| `reasoning_replay` | the thinking blocks, unchanged, before the `tool_use` blocks |
| tool messages | `tool_result` blocks, all in one user message |
| `max_tokens` | `min(value, 128000)` |

Stream events map to `Completion`: `text_delta` gives text, `input_json_delta` gives tool arguments, and thinking blocks give `replay` when `retainReasoning` is set. `stop_reason` `end_turn` maps to finish `stop`. `tool_use` maps to `tool_calls`, and `max_tokens` maps to `length`. `refusal` gives `CloudFailure("refusal", …)`, and Alphy drops the partial text.

- [ ] **Step 1: Write the failing tests** against a loopback Anthropic fixture (SSE with `event:` and `data:` lines):

```kotlin
@Test fun decisionUsesStructuredOutputNotForcedTool() // the request has no tool_choice "any"/"tool", output_config.format.type == "json_schema", and the reply becomes a submit_chat_decision ToolCall
@Test fun searchPlanUsesStructuredOutput() // the forced web_search becomes output_config.format, and 2 calls give 2 searches
@Test fun requiredChoiceBecomesUnion() // memory and course allowed: calls.items.anyOf has 3 branches, named submit_chat_decision, prepare_memory_fact, and prepare_course
@Test fun badStructuredReplyIsToolInvalid() // "not json" and an unknown name both give code tool_invalid
@Test fun decisionSchemaFitsAnthropicLimits() // the decision with every tool allowed: at most 16 properties use anyOf or type arrays, and no maxItems
@Test fun selectorUsesOutputConfigFormat() // a SELECTOR request sends responseSchema as output_config.format, uses the selector model, and has thinking disabled
@Test fun imagesBecomeBase64Blocks() // a data:image/png;base64 turn gives {"type":"image","source":{"type":"base64","media_type":"image/png"}}
@Test fun toolRoundReplaysThinkingBlocks() // the second request repeats the thinking block with its signature before tool_use
@Test fun refusalStopsWithRefusalCode() // stop_reason "refusal" gives code "refusal" and no AnswerFragment is stored
@Test fun maxTokensIsClamped() // canonical 131072 gives 128000
@Test fun answerStreamsText() // three text_delta events give the full answer and RoundComplete
@Test fun overloadedIsUnavailable() // HTTP 529 gives "unavailable"
@Test fun eventsSplitAcrossReadsParse() // one SSE event split over three socket writes gives the same Completion
@Test fun pingAndUnknownEventsAreIgnored() // ping and an unknown event type change nothing
@Test fun errorEventMidStreamIsFailure() // event: error with overloaded_error after one text_delta gives "unavailable" and no stored partial text
@Test fun cancelClosesTheConnection() // session.cancel() during the stream closes the socket within 2 s
@Test fun redirectIsRefused() // 302 gives a failure and exactly one request
@Test fun sendsApiKeyHeaderOnly() // x-api-key is the key and anthropic-version is set; no authorization header
```

- [ ] **Step 2: Run** `com.alpha.assistant.cloud.AnthropicWorkflowTest`. Expected: FAIL.
- [ ] **Step 3: Implement it over `CloudHttp.open` (Task 6).** Send `POST /v1/messages` with `stream: true`, `x-api-key`, and `anthropic-version` (the value from the current Anthropic API documentation). Parse the SSE stream (`event:` and `data:` lines, spec 5.5 (b)). Close the connection when `session.cancel()` runs. Count wire characters against `StreamLimits.maxWireChars`.
- [ ] **Step 4: Run it, plus `CloudWorkflowTest#recordedPromptReplay*` with an Anthropic account.** Expected: PASS.
- [ ] **Step 5: Commit.** Message: "Add the Anthropic Messages wire".

### Task 14: Anthropic search

**Files:**
- Modify: `app/src/main/java/com/alpha/assistant/cloud/WebSearch.kt`
- Test: `WebSearchTest.kt`

**Interfaces:**
- Produces: `AnthropicSearch(endpoints, model) : SearchWire`, using Messages with `{"type": "web_search_20260209", "name": "web_search", "max_uses": 1}`. Sources come from `web_search_tool_result.content[]` (`url`, `title`, `page_age`). The excerpt joins the `cited_text` of the citations for that URL. `publishedAt` comes from `page_age` when it parses as a date, and otherwise from the model JSON (as in Task 11). An error object in `content` maps to `search_unavailable`.

- [ ] **Step 1: Write the failing tests.**

```kotlin
@Test fun anthropicSearchMapsResultsAndCitations() // 2 results with 2 citations give 2 sources with excerpts from cited_text
@Test fun anthropicSearchToolError() // content {"type":"web_search_tool_result_error","error_code":"max_uses_exceeded"} gives search_unavailable
@Test fun pageAgeBecomesPublishedAt() // "2026-11-01" gives "2026-11-01T00:00:00Z"
```

- [ ] **Step 2: Run** `WebSearchTest`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `WebSearchTest` and `AnthropicWorkflowTest`. Expected: PASS.
- [ ] **Step 5: Commit.** Message: "Search the web through Anthropic".

## Phase 6. Image generation

### Task 15: Images on the account

**Files:**
- Modify: `app/src/main/java/com/alpha/assistant/images/ImageGeneration.kt` (`ImageModel` L103-106, `ImageSettings` L108-147, `ImageGeneration` L194-198, `OpenRouterImages` L301-340)
- Modify: `app/src/androidTest/java/com/alpha/assistant/images/ImageGenerationWorkflowTest.kt`, `ThreeSceneWorkflowTest.kt:220-337`, `GenerationRouteWorkflowTest.kt:175-218`, `LiveSharedImageWorkflowTest.kt:56-71`
- Test: `app/src/androidTest/java/com/alpha/assistant/images/OpenAiImagesTest.kt`

**Interfaces:**
- Consumes: `SecureKeyStore.account()`, `Endpoints`, `providerFailure`.
- Produces:
  - `ImageModel(val wire: String, val title: String, val provider: AiProvider, val route: String?)` with `QWEN`, `SEEDREAM` (OpenRouter), `GPT_IMAGE_FLARE("gpt-image-2.6-flare", …, OPENAI, null)`, `GPT_IMAGE_SUNBURST("gpt-image-2.6-sunburst", …, OPENAI, null)`.
  - `ImageSettings(context, keys: SecureKeyStore = SecureKeyStore(context))` with no `saveKey` or `clearKey`.
    - `val available: Boolean`: the account's provider has `IMAGES`.
    - `model` returns the stored model when it belongs to the account's provider. Otherwise it returns that provider's first model.
    - `Selection.consent` is `keys.consentIdentity()`.
    - `key(selection)` also checks `account.provider == selection.model.provider`.
  - `OpenAiImages(endpoints)`: `POST /v1/images/generations` with `{"model", "prompt", "size", "n": 1}`. It reads `data[0].b64_json`. Use the sizes that the builder confirms for `ImageAspect` (spec 18).
  - `internal fun cropToAspect(bytes: ByteArray, aspect: ImageAspect): ByteArray` in `ImageGeneration.kt`. When a provider has no size for an aspect, request the nearest wider size, and cut the center to the target ratio before the ratio check (spec 11.6).
  - `OpenRouterImages` takes `endpoints`, so tests can use the loopback.

- [ ] **Step 1: Write the failing tests.**

```kotlin
@Test fun openAiImagesRequestShape() // POST /v1/images/generations with model gpt-image-2.6-flare and n 1; b64_json PNG bytes become ProviderImage
@Test fun anthropicAndDeepSeekAccountsHaveNoImages() {
    for (provider in listOf(AiProvider.ANTHROPIC, AiProvider.DEEPSEEK)) { saveAccount(provider); assertFalse(settings.available); assertNull(settings.selection()) }
}
@Test fun providerChangeInvalidatesSelection() // save OpenRouter, take selection, save OpenAI: isCurrent(selection) is false
@Test fun modelFallsBackToProviderDefault() // stored SEEDREAM with an OpenAI account gives GPT_IMAGE_FLARE
@Test fun equirectangularIsCroppedToTwoToOne() // OpenAI returns 1536x1024 for EQUIRECTANGULAR: the result is 1536x768, and the ratio check passes
```

- [ ] **Step 2: Run** `OpenAiImagesTest` and `ImageGenerationWorkflowTest`. Expected: FAIL.
- [ ] **Step 3: Implement.** Update the four tests that called `ImageSettings.saveKey` to save an account.
- [ ] **Step 4: Run** the six classes named in Files. Expected: PASS.
- [ ] **Step 5: Commit.** Message: "Generate images through the account's provider".

## Phase 7. Settings and onboarding

### Task 16: «Сервис ИИ» key panel

**Files:**
- Modify: `app/src/main/java/com/alpha/assistant/ui/SettingsPane.kt` (L88-103 sections, L306-447 panels)
- Modify: `app/src/main/java/com/alpha/assistant/ui/ShellViewModel.kt` (`saveKey` L1956, `refreshKey` L1989-1999, `KeyStatus` L2002)
- Modify: `app/src/main/java/com/alpha/assistant/ui/AlphaShell.kt:163,383,464`
- Modify: `app/src/main/res/values/strings.xml`, `settings_strings.xml`, `onboarding_strings.xml` (`key_help_failed`)
- Modify: `app/src/androidTest/java/com/alpha/assistant/ui/ShellRobot.kt:123-139` (`ShellFixture`)
- Test: `app/src/androidTest/java/com/alpha/assistant/ui/SettingsConsentWorkflowTest.kt`, `OnboardingWorkflowTest.kt`

**Interfaces:**
- Consumes: `detectProvider`, `AiProvider.accepts`, `AiProvider.profile` (`capabilities`, `keyPage`), `checkKey`, `SecureKeyStore.save(value, provider)`, `ImageSettings.available`.
- Produces:
  - `SettingsSection.Ai` (label `R.string.settings_ai` «Сервис ИИ»). It replaces `SettingsSection.DeepSeek` and `SettingsSection.Images`.
  - `enum class KeyStatus { Checking, Missing, Verifying, NeedsConsent, Ready, Unreadable }`
  - `ShellViewModel.saveKey(value: String, provider: AiProvider, done: (Boolean) -> Unit)`: it runs `checkKey` (state `Verifying`), then `keys.save`. On `REJECTED` or `UNREACHABLE` it does not save and sets `keyFailure`.
  - `ShellViewModel.accountProvider: AiProvider?`
  - `@Composable internal fun AiKeyPanel(model: ShellViewModel, scroll: ScrollState)`. It replaces `DeepSeekKeyPanel`. Onboarding uses it in Task 17.
  - `ShellViewModel` takes `keyCheck: suspend (AiAccount) -> KeyCheck = ::checkKey` as a constructor parameter, so tests can inject results. `ShellFixture` gets `var keyCheck: suspend (AiAccount) -> KeyCheck = { KeyCheck.ACCEPTED }` and passes it, so no device test calls a real provider.
  - `@Composable internal fun KeyGuide(onOpenFailed: (url: String) -> Unit)`: the provider guide (spec 14.10). `AiKeyPanel` shows it under the key field while `keyStatus == KeyStatus.Missing`. It replaces the single link and `DEEPSEEK_KEYS_URL`. Row order: OPENROUTER, OPENAI, DEEPSEEK, ANTHROPIC. Each row is `displayName` as a link to `profile.keyPage`, then « — », then the gap text.
  - `internal fun keyGuideGaps(profile: ProviderProfile): List<Int>`: the string IDs of the missing functions, in this order: `key_guide_images` if `IMAGES` is missing, `key_guide_voice` if `CLOUD_VOICE` is missing. An empty list shows `key_guide_all`. One item uses `key_guide_without_one`, two items use `key_guide_without_two`.

Strings (new):

| Name | Text |
|---|---|
| `settings_ai` | Сервис ИИ |
| `key_label` | API-ключ |
| `key_supporting` | Ключ OpenAI, Anthropic, OpenRouter или DeepSeek. Ключ хранится на устройстве в зашифрованном виде. |
| `key_provider` | Сервис |
| `key_verifying` | Проверка ключа… |
| `key_rejected` | %1$s отклонил ключ. Проверьте ключ и повторите. |
| `key_unreachable` | Не удалось проверить ключ. Проверьте подключение и повторите. |
| `key_limited` | Ключ принят, но у него ограниченные права. Некоторые функции могут не работать. |
| `key_no_credit` | Ключ принят, но на балансе нет средств. Пополните счёт у %1$s. |
| `key_admin` | Это административный ключ Anthropic. Введите обычный API-ключ. |
| `ai_disclosure` | Что получает %1$s |
| `consent_text` | today's text, with «DeepSeek» replaced by `%1$s` |
| `consent_openrouter` | OpenRouter передаёт запрос поставщику выбранной модели. |
| `consent_voice` | Текст ответа для озвучки |
| `ai_available` | Что доступно |
| `ai_feature_chat` | Ответы и мини-приложения |
| `ai_feature_vision` | Понимание изображений |
| `ai_feature_vision_detail` | фото, снимки экрана, проверка 3D-сцен |
| `ai_feature_search` | Поиск в интернете |
| `ai_feature_images` | Создание изображений |
| `ai_feature_images_unavailable` | Недоступно с %1$s. Нужен ключ OpenAI или OpenRouter. |
| `ai_feature_voice` | Озвучка ответов |
| `ai_feature_voice_phone` | на телефоне, раздел «Голос» |
| `ai_feature_voice_any` | телефон или облако, раздел «Голос» |
| `key_guide_all` | все функции |
| `key_guide_without_one` | без %1$s |
| `key_guide_without_two` | без %1$s и %2$s |
| `key_guide_images` | создания изображений |
| `key_guide_voice` | облачной озвучки |

Keep `key_help` «Где взять ключ» as the guide heading. Change `key_help_failed` to «Не удалось открыть браузер. Адрес: %1$s», with the URL of the tapped row.

Remove: `settings_deepseek`, `settings_deepseek_disclosure`, `settings_images`, `image_consent`, `image_key`, `image_key_ready`, `image_key_missing`, `image_key_remove`, and the DeepSeek wording in `cloud_key_missing` and `cloud_consent`.

- [ ] **Step 1: Write the failing tests** in `SettingsConsentWorkflowTest` (rewrite the 13 tests for the new panel and add these):

```kotlin
@Test fun detectsProviderAndShowsCapabilities() {
    typeKey("sk-ant-api03-abc")
    robot.waitFor(robot.selected("Anthropic"))
    robot.waitFor(robot.text(R.string.ai_feature_images_unavailable, "Anthropic"))
    robot.waitFor(robot.text(R.string.ai_feature_voice_phone))
}
@Test fun unknownPrefixNeedsProviderChoice() // "abc123": «Согласиться и сохранить» stays disabled until a segment is selected
@Test fun rejectedKeyIsNotSaved() // keyCheck gives REJECTED: «OpenAI отклонил ключ…» appears, and keys.account() is null
@Test fun offlineCheckKeepsTypedKey() // keyCheck gives UNREACHABLE: the key_unreachable text appears, the field still holds the key, and nothing is saved
@Test fun limitedKeyIsSavedWithNotice() // LIMITED: the account is saved and key_limited appears
@Test fun noCreditKeyIsSavedWithNotice() // NO_CREDIT: the account is saved and key_no_credit appears with "DeepSeek"
@Test fun deepSeekKeyShowsCloudVoice() // "sk-" plus 32 hex characters: «DeepSeek» is selected, ai_feature_images_unavailable shows "DeepSeek", and ai_feature_voice_any appears
@Test fun adminKeyBlockedBeforeCheck() // "sk-ant-admin01-x": key_admin appears and keyCheck is not called
@Test fun replacingProviderKeepsConversations() // save OpenAI, seed a chat, save OpenRouter: the chat remains and consentIdentity changed
@Test fun guideListsProvidersByCapability() {
    fixture.install(saveKey = false)
    fixture.launch().use { scenario ->
    openSettings(scenario, R.string.settings_ai)
    robot.waitFor(robot.text(R.string.key_help))
    for (p in listOf(AiProvider.OPENROUTER, AiProvider.OPENAI, AiProvider.DEEPSEEK, AiProvider.ANTHROPIC))
        assertTrue(robot.composedLink(scenario, p.displayName, p.profile.keyPage))
    robot.waitFor(robot.text(R.string.key_guide_all))
    robot.waitFor(robot.text(R.string.key_guide_without_one, robot.text(R.string.key_guide_voice)))
    robot.waitFor(robot.text(R.string.key_guide_without_one, robot.text(R.string.key_guide_images)))
    robot.waitFor(robot.text(R.string.key_guide_without_two, robot.text(R.string.key_guide_images), robot.text(R.string.key_guide_voice)))
    }
}
@Test fun guideHiddenOnceKeySaved() // save an OpenRouter key: key_help is gone; delete the key: key_help returns
```

- [ ] **Step 2: Run** `com.alpha.assistant.ui.SettingsConsentWorkflowTest`. Expected: FAIL.
- [ ] **Step 3: Implement the panel by the spec 14.1 sketch.** Use `SingleChoiceSegmentedButtonRow` with four options for the provider. If the four labels do not fit at 360 dp, use an exposed dropdown menu (spec 18). Keep the polite live region on the status line, the delete dialog, and the 48 dp touch targets. Each provider's key help link opens its key page (spec 18).
- [ ] **Step 4: Update `OnboardingWorkflowTest`.** At L33, assert the four guide links instead of the DeepSeek URL. At L36, enter `sk-or-v1-alpha-test-only`, so detection selects OpenRouter.
- [ ] **Step 5: Run it, plus** `OnboardingWorkflowTest`, `ShellWorkflowTest`, `AdaptiveShellWorkflowTest`, `MemoryShellWorkflowTest`, `SavedCourseShellWorkflowTest`, `ShellNavigationWorkflowTest`, `ChatPresentationWorkflowTest`, and `ShellScreenshotTest`. Update their DeepSeek strings (`ShellWorkflowTest.kt:76-77` hard-codes «Веб-поиск DeepSeek»). Expected: PASS.
- [ ] **Step 6: Commit.** Message: "Replace the DeepSeek and image key screens with Сервис ИИ".

### Task 17: Onboarding and old keys

**Files:**
- Modify: `app/src/main/java/com/alpha/assistant/ui/OnboardingPane.kt` (step 1 rows, step 2 L80-90, step 3, the doc comment)
- Modify: `SettingsPane.kt` (`AiKeyPanel` top), `ShellViewModel.kt`, `ShellRobot.kt` (`ShellFixture`), `onboarding_strings.xml`
- Test: `app/src/androidTest/java/com/alpha/assistant/ui/OnboardingWorkflowTest.kt`, create `app/src/androidTest/java/com/alpha/assistant/ui/LegacyKeyNoticeWorkflowTest.kt`

**Interfaces:**
- Consumes: `LegacyKeys` (Task 4), `AiKeyPanel`, `KeyGuide`, `saveKey` (Task 16), `AiProvider.profile.capabilities`.
- Produces:
  - `ShellViewModel` takes `legacy: LegacyKeys = LegacyKeys(app)`. `ShellFixture` passes a `LegacyKeys` with isolated store names and aliases, and exposes it as `fixture.legacy`.
  - `ShellViewModel.legacyKeys: LegacyKeyState` with `data class LegacyKeyState(val deepSeek: Boolean, val openRouterImages: Boolean)`. It mirrors the old stores.
  - `fun useLegacyKey(provider: AiProvider)` and `fun clearLegacyKey(provider: AiProvider)`. Both take `DEEPSEEK` or `OPENROUTER`. `useLegacyKey` reads the old key and puts it in the panel with that provider. The user then accepts the consent, and `saveKey` runs the check. Alphy clears that old store only after a successful save.
  - `@Composable internal fun LegacyKeyCard(model: ShellViewModel)` at the top of `AiKeyPanel`. So onboarding and «Сервис ИИ» show the same card. With both old keys, the DeepSeek card comes first. The use button shows only while `accountProvider == null`. «Удалить» always shows, so an old key never stays on the device out of sight.
  - `ShellFixture.seedLegacyDeepSeek(key: String = "sk-" + "0123456789abcdef".repeat(2))` and `seedLegacyOpenRouter(key: String = "sk-or-v1-legacy")` write the isolated old stores.
  - Onboarding step 3 reads `model.accountProvider` for its lines.

Strings:

| Name | Text |
|---|---|
| `onboarding_key_title` | Подключите сервис ИИ |
| `onboarding_key_text` | Вставьте ключ OpenAI, Anthropic, OpenRouter или DeepSeek. С ключом OpenRouter работают все функции. |
| `onboarding_feature_vision` | Понимаю фото и снимки экрана |
| `onboarding_feature_voice` | Читаю ответы вслух в голосовом режиме |
| `onboarding_ready_no_images` | Создание изображений недоступно с %1$s. Ключ можно сменить в настройках, раздел «Сервис ИИ». |
| `onboarding_ready_voice` | Ответы можно слушать: включите «Читать ответы вслух» в разделе «Голос». |
| `legacy_deepseek` | Найден ключ DeepSeek. |
| `legacy_deepseek_use` | Использовать |
| `legacy_openrouter` | Найден ключ OpenRouter. |
| `legacy_openrouter_use` | Использовать для всех функций |
| `legacy_delete` | Удалить |

Step 1 rows: `onboarding_feature_vision` uses `Glyph.Photo`, and `onboarding_feature_voice` uses `Glyph.Mic`. They come after `onboarding_feature_apps`. `onboarding_ready_no_images` appears only when `IMAGES` is not in the account capabilities. `onboarding_ready_voice` always appears.

- [ ] **Step 1: Write the failing tests.** `LegacyKeyNoticeWorkflowTest` copies the `openSettings` helper from `SettingsConsentWorkflowTest`.

```kotlin
// LegacyKeyNoticeWorkflowTest
@Test fun deepSeekNoticeDeletesOnlyOnTap() {
    fixture.install(saveKey = false)
    fixture.seedLegacyDeepSeek()
    fixture.launch().use { scenario ->
        openSettings(scenario, R.string.settings_ai)
        robot.waitFor(robot.text(R.string.legacy_deepseek))
        assertTrue(fixture.legacy.hasDeepSeekKey())
        robot.click(robot.text(R.string.legacy_delete))
        robot.waitUntil { !fixture.legacy.hasDeepSeekKey() }
        assertNull(robot.find(robot.text(R.string.legacy_deepseek)))
    }
}
@Test fun deepSeekKeyBecomesAccountAfterConsent() // the card, then «Использовать», then the consent: the account is DEEPSEEK with the old key, and the old DeepSeek store is empty
@Test fun openRouterImageKeyBecomesAccountAfterConsent() // the card, then «Использовать для всех функций», then the consent: the account is OPENROUTER with the old key, and the old store is empty
@Test fun failedCheckKeepsOldKey() // keyCheck gives UNREACHABLE: key_unreachable appears, and hasOpenRouterImageKey() stays true
@Test fun accountHidesOnlyUseButton() // old DeepSeek key and a saved OpenAI account: legacy_deepseek and legacy_delete show, legacy_deepseek_use does not
@Test fun bothOldKeysShowDeepSeekFirst() // both stores seeded: legacy_deepseek is above legacy_openrouter

// OnboardingWorkflowTest
@Test fun welcomeListsVisionAndVoice() // step 1 shows onboarding_feature_vision and onboarding_feature_voice
@Test fun firstRunShowsOldKeyCard() // onboarding = true and an old DeepSeek key: step 2 shows legacy_deepseek above key_label; «Использовать» and the consent reach Ready, and «Далее» opens step 3
@Test fun readyStepNamesMissingImages() {
    fixture.install(saveKey = false, onboarding = true)
    fixture.launch().use {
        robot.click(robot.text(R.string.onboarding_next))
        robot.reveal(robot.text(R.string.key_label))
        robot.enter(robot.text(R.string.key_label), "sk-ant-api03-alpha-test-only")
        robot.reveal(robot.text(R.string.consent_and_save))
        robot.click(robot.text(R.string.consent_and_save))
        robot.waitFor(robot.text(R.string.key_ready))
        robot.reveal(robot.text(R.string.onboarding_next))
        robot.click(robot.text(R.string.onboarding_next))
        robot.waitFor(robot.text(R.string.onboarding_ready_no_images, "Anthropic"))
        robot.waitFor(robot.text(R.string.onboarding_ready_voice))
    }
}
@Test fun readyStepWithOpenRouterHasNoImageLine() // the first-run test key (OpenRouter): step 3 has no onboarding_ready_no_images text
@Test fun returningUserSeesNoOnboarding() // install(onboarding = true), the onboarded setting saved, and an old DeepSeek key: the desktop opens without onboarding_welcome_title, and «Настройки», «Сервис ИИ» shows legacy_deepseek
```

- [ ] **Step 2: Run both classes.** Expected: FAIL.
- [ ] **Step 3: Implement.** Change the `OnboardingPane` doc comment to "the AI key" in place of "the DeepSeek key".
- [ ] **Step 4: Run both classes, plus `SettingsConsentWorkflowTest`.** Expected: PASS.
- [ ] **Step 5: Commit.** Message: "Show the AI service and old keys in onboarding".

### Task 18: «Модели»

**Files:**
- Create: `app/src/main/java/com/alpha/assistant/cloud/AiModels.kt`
- Modify: `SettingsPane.kt`, `ImageSettings.model`, and the `CloudChatProvider` default constructor, which becomes `constructor(keys: SecureKeyStore, models: AiModels)`
- Test: `app/src/androidTest/java/com/alpha/assistant/ui/AiModelsWorkflowTest.kt`

**Interfaces:**
- Produces: `class AiModels(context: Context, name: String = "alpha_ai_models")` with `fun chat(provider: AiProvider): String`, `fun selectChat(provider: AiProvider, model: String)`, `fun speech(): SpeechModel`, `fun selectSpeech(model: SpeechModel)`. An unknown stored value returns the provider's first curated model. Image models stay in `ImageSettings`.

- [ ] **Step 1: Write the failing tests.**

```kotlin
@Test fun listsOnlyActiveProviderModels() // OpenAI account: «Модели» shows OpenAI chat and image models only, and no voice row. DeepSeek account: deepseek-flash and the Stella voice row, and no image row
@Test fun selectionPersistsPerProvider() // select the second OpenRouter chat model, switch to OpenAI and back: the OpenRouter selection is kept
@Test fun requestUsesSelectedModel() // loopback: the next chat request carries the selected model
```

- [ ] **Step 2: Run it.** Expected: FAIL.
- [ ] **Step 3: Implement.** «Модели» is a `SettingsDisclosure`, closed by default. Put it in the «Сервис ИИ» detail below `AiKeyPanel`, not inside it, so onboarding does not show it (spec 14.1).
- [ ] **Step 4: Run it.** Expected: PASS.
- [ ] **Step 5: Commit.** Message: "Choose curated models for the active provider".

## Phase 7b. Voice input

### Task 18b: Voice input through the account

**Why:** spec 1.6 and 12A. The user replaced local T-One recognition with the provider of the key, and the phone recognizer for Anthropic.

**Files:**
- Create: `app/src/main/java/com/alpha/assistant/voice/SpeechInput.kt`, `PcmCapture.kt`, `CloudSpeechInput.kt`, `PhoneSpeechInput.kt` (all in `voice/`)
- Modify: `app/src/main/java/com/alpha/assistant/cloud/ProviderProfile.kt` (transcription request), `CloudHttp.kt` (`open` gets a content type)
- Modify: `app/src/main/java/com/alpha/assistant/voice/VoiceSession.kt` (the engine only, the phases stay), `ui/ShellViewModel.kt:120-121`, `ui/VoiceControls.kt`, `ui/Composer.kt:122`, `app/src/debug/java/com/alpha/assistant/VoiceTestActivity.kt`
- Modify: the key panel of Task 16 (the «Голосовой ввод» row and the consent line), `app/src/main/res/values/strings.xml`
- Modify: the voice fixtures of `ui/ShellVoiceWorkflowTest.kt:103-106`, `voice/InlineVoiceWorkflowTest.kt:62,181`, `surfaces/AssistantSheetWorkflowTest.kt:426`
- Rename: `voice/LocalVoiceWorkflowTest.kt` to `voice/VoiceInputWorkflowTest.kt`
- Test: `app/src/androidTest/java/com/alpha/assistant/voice/CloudSpeechInputTest.kt`, `PhoneSpeechInputTest.kt`, and a `FakeSpeechInput` in androidTest `voice/`

**Interfaces:**
- Consumes: `AiAccount`, `AiProvider.profile`, `SecureKeyStore.account()`, `Endpoints`, `open` in `CloudHttp.kt` (Task 6), `providerFailure` (Task 10).
- Produces:
  - `internal data class Transcription(val path: String, val model: String?)` and `ProviderProfile.transcription: Transcription?`. Values: OpenAI `("/v1/audio/transcriptions", "gpt-transcribe")`, OpenRouter `("/api/v1/audio/transcriptions", "openai/whisper-large-v3")`, DeepSeek `("/v1/tools/asr", null)`, Anthropic `null` (phone path).
  - `internal interface SpeechInput { suspend fun capture(oneShot: Boolean, onLevel: (Float) -> Unit, onRecognizing: () -> Unit): String?; fun finish(); fun cancel() }`. `capture` returns the transcript, or `null` for no speech. It throws `CloudFailure` or `SpeechUnavailable`.
  - `internal fun speechInputFor(account: AiAccount, context: Context, endpoints: Endpoints): SpeechInput`: `CloudSpeechInput` when `profile.transcription != null`, else `PhoneSpeechInput`.
  - `internal fun wav(samples: ShortArray, sampleRate: Int = 16000): ByteArray`: a 44-byte PCM header and little-endian samples.
  - `internal fun interface PcmSource { fun open(): PcmStream }`, where `PcmStream` has `read(buffer: ShortArray): Int` and `close()`. `PcmCapture(source)` holds the recording loop of `VoiceSession.recordPcm` today, the 60 s limit, and the end-of-speech detector (spec 12A.3).
  - `internal interface PhoneRecognizer` (available check, `ru-RU` support check, start with a `RecognitionListener`, stop, cancel, model download, destroy), with `AndroidPhoneRecognizer(context)` over `SpeechRecognizer.createOnDeviceSpeechRecognizer`. Tests use a fake.
  - `VoiceSession(context, scope, transcript, inputs: () -> SpeechInput?)`. `inputs` returns `null` when no account exists. It replaces `prepareModels`, `captureAudio`, and `capturePcmForTest`. The phases and the public methods stay. `partial` gets only the final text.

Strings: `voice_note_cloud` «Запись уходит в %1$s для распознавания · проверяйте текст перед отправкой»; `voice_note_phone` «Речь распознаёт телефон · проверяйте текст перед отправкой»; `voice_recognizing_cloud` «Микрофон выключен · %1$s распознаёт речь»; `voice_recognizing_phone` «Микрофон выключен · телефон распознаёт речь»; `voice_preparing` «Включаю микрофон…»; `voice_no_account` «Для голосового ввода нужен ключ сервиса ИИ. Добавьте его в настройках, раздел «Сервис ИИ».»; `voice_phone_unavailable` «Этот телефон не распознаёт русскую речь на устройстве. С ключом Anthropic голосовой ввод недоступен.»; `voice_phone_download` «Скачать русский язык»; `voice_phone_downloading` «Телефон скачивает русский язык. Повторите запись после загрузки.»; `voice_transcription_failed` «Не удалось распознать речь: %1$s»; `voice_session_text` «Скажите вопрос и сделайте паузу. Текст появится после паузы. Проверьте его и нажмите «Отправить». Ответ появится в чате, затем микрофон включится снова. Кнопка «Остановить сеанс» завершает работу.»; `capability_voice_input` «Голосовой ввод»; `capability_voice_input_cloud` «распознаёт %1$s»; `capability_voice_input_phone` «на телефоне»; `consent_voice_input` «Записи голоса для распознавания речи». Delete `voice_local_note`, `voice_models`, and `voice_licenses`. `VoiceModelDetails` shows the note of the active path.

- [ ] **Step 1: Move the existing fixtures to `FakeSpeechInput`.** It returns scripted results, like the `captureAudio` lambdas today. Keep every assertion of `ShellVoiceWorkflowTest`, `InlineVoiceWorkflowTest`, and `AssistantSheetWorkflowTest`. Exception: `InlineVoiceWorkflowTest.stopKeepsSpeechCapturedBeforeModelPreparation` checks model preparation, which spec 2.6 removes. Keep its user-visible assertion (speech recorded before «Завершить запись» reaches the draft) without the preparation part, and record spec 2.6 as the reason in the report.
- [ ] **Step 2: Write the failing tests.**

```kotlin
// CloudSpeechInputTest: loopback, synthetic PcmSource, response {"text":"Поставь таймер на пять минут"}
@Test fun openAiRequestShape() // POST /v1/audio/transcriptions, Authorization "Bearer <key>", part model = gpt-transcribe, part file: filename voice.wav, Content-Type audio/wav; no language part; capture returns the text
@Test fun openRouterRequestShape() // POST /api/v1/audio/transcriptions, part model = openai/whisper-large-v3
@Test fun deepSeekRequestShape() // POST /v1/tools/asr, only the part file, no model part
@Test fun wavHeaderIsPcm16Mono16k() // file starts with "RIFF" and "WAVE"; format 1, 1 channel, 16000 Hz, 16 bits; data size = 2 × samples
@Test fun recordingStopsAtSixtySeconds() // 70 s of speech in one-shot mode: the file part is 1,920,044 bytes
@Test fun endOfSpeechAfterSilence() // continuous: 0.5 s noise at amplitude 0.01, 1 s tone at 0.2, then noise at 0.01: capture stops after 1.1 to 1.6 s of samples past the tone
@Test fun noSpeechReturnsNullAfterEightSeconds() // continuous: noise at 0.01 only: null after 8 s of samples, no HTTP request
@Test fun failureNamesTheProvider() // DeepSeek HTTP 402: CloudFailure code "quota", and its message names DeepSeek
@Test fun redirectIsRefused() // 302: a failure and exactly one request
@Test fun stopDuringUploadDisconnects() // VoiceSession.stop() while the loopback holds the response: the socket closes within 2 s, and no transcript reaches the callback
@Test fun noAccountShowsSettingsStatus() // inputs = { null }: start() shows voice_no_account, active stays false, no microphone opens
@Test fun anthropicUsesThePhonePath() // speechInputFor(AiAccount(ANTHROPIC, …)) is PhoneSpeechInput, and a capture with the fake recognizer sends no HTTP request

// PhoneSpeechInputTest: fake PhoneRecognizer
@Test fun resultArrivesAfterEndOfSpeech() // the intent has EXTRA_LANGUAGE "ru-RU" and EXTRA_PARTIAL_RESULTS false; onPartialResults changes nothing; onResults gives the transcript
@Test fun finishCallsStopListening()
@Test fun unavailableShowsStatus() // available = false: voice_phone_unavailable, start is never called
@Test fun missingRussianOffersDownload() // ru-RU supported, not installed: voice_phone_download is shown, and a tap calls the model download once
@Test fun noMatchIsNotRecognized() // onError(ERROR_NO_MATCH): voice_not_recognized
```

- [ ] **Step 3: Run** `com.alpha.assistant.voice.CloudSpeechInputTest,com.alpha.assistant.voice.PhoneSpeechInputTest` with `--expect failed`. Expected: FAIL.
- [ ] **Step 4: Implement.** `CloudSpeechInput` builds the multipart body itself and sends it through `open` with `multipart/form-data; boundary=…` and a 60 s read timeout. It parses `text` with a bounded read. `VoiceSession` takes audio focus as today. The phone path takes no `AudioRecord`, because the recognizer owns the microphone. Add `consent_voice_input` to the OpenAI, OpenRouter, and DeepSeek consent, and the «Голосовой ввод» row to «Что доступно» (spec 14.1, 14.2, 14.7).
- [ ] **Step 5: Run** both new tests, `com.alpha.assistant.voice.VoiceInputWorkflowTest`, `com.alpha.assistant.voice.InlineVoiceWorkflowTest`, `com.alpha.assistant.ui.ShellVoiceWorkflowTest`, `com.alpha.assistant.surfaces.AssistantSheetWorkflowTest`, and the Task 16 key panel test. Expected: PASS.
- [ ] **Step 6: Commit.** Message: "Recognize speech through the AI provider or the phone".

### Task 18c: Remove the local speech model

**Why:** spec 2.6 and 12A.9. After Task 18b, nothing uses T-One.

**Files:**
- Delete: `app/src/main/java/com/alpha/assistant/voice/VoiceModels.kt`, the `app/src/main/java/com/offlineassistant` directory, `app/src/main/assets/voice/`, `app/libs/sherpa-onnx-static-link-onnxruntime-1.13.4.aar`, and the voice notices in `app/src/main/assets/licenses/` (`VOICE-NOTICE.txt`, `sherpa-onnx.txt`, `onnxruntime.txt`, `onnxruntime-ThirdPartyNotices.txt`, `whisper-MIT.txt`)
- Modify: `app/build.gradle.kts` (the AAR line; `onnx` in `noCompress`; `whisper:whisper-MIT.txt:silero` in `ignoreAssetsPattern`), `.gitattributes` (the LFS rules for the deleted files), `app/src/main/res/values/strings.xml` (T-One strings that remain)
- Keep: `app/src/androidTest/assets/voice/russian.wav` (Task 22 live check)
- Test: `app/src/androidTest/java/com/alpha/assistant/voice/VoiceInputWorkflowTest.kt`

- [ ] **Step 1: Write the failing test.** Replace `productionApkContainsOnlyToneRecognition` and `recordedRussianRecognition` with:

```kotlin
@Test fun apkHasNoLocalSpeechModel() // no APK entry starts with "assets/voice/"; no entry contains "sherpa" or "onnxruntime"; Class.forName("com.offlineassistant.app.asr.TOneStreamingTranscriber") and Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizer") both fail
```

- [ ] **Step 2: Run** `com.alpha.assistant.voice.VoiceInputWorkflowTest#apkHasNoLocalSpeechModel` with `--expect failed`. Expected: FAIL.
- [ ] **Step 3: Delete the files and the build lines.** Remove `onnx` from `noCompress` only if no other `.onnx` file remains in `app/src`. Search `app/src`, `app/build.gradle.kts`, `tools`, and `README.md` for `T-one`, `TOne`, `sherpa`, `onnxruntime`, `VoiceModels`, `voice/manifest`, and `alpha-voice-1`. Expected: no match, except in docs that Task 21 changes.
- [ ] **Step 4: Build and measure.** Run `./gradlew :app:assembleDebug`. Expected: success, and the debug APK is at most 292815477 bytes (436815477 after Task 2b minus 144,000,000, spec 12A.9). Record the size.
- [ ] **Step 5: Run** `com.alpha.assistant.voice.VoiceInputWorkflowTest`, `com.alpha.assistant.voice.InlineVoiceWorkflowTest`, and `com.alpha.assistant.ui.ShellVoiceWorkflowTest`. Expected: PASS.
- [ ] **Step 6: Commit.** Message: "Remove the local T-One speech model". Record the APK size in the message.

## Phase 8. Voice output

### Task 19: Read replies aloud on the phone

**Files:**
- Create: `app/src/main/java/com/alpha/assistant/voice/SpeechOutput.kt`
- Modify: `app/src/main/java/com/alpha/assistant/voice/VoiceSession.kt` (`replyCompleted` and `stop`; Task 18b moved the line numbers)
- Modify: `app/src/main/java/com/alpha/assistant/ui/ShellViewModel.kt:1853`, `app/src/main/java/com/alpha/assistant/ui/VoiceControls.kt`
- Test: `app/src/androidTest/java/com/alpha/assistant/voice/VoiceOutputWorkflowTest.kt`

**Interfaces:**
- Produces:
  - `interface SpeechOutput { suspend fun speak(text: String); fun stop() }`
  - `class DeviceSpeech(context: Context) : SpeechOutput`: Android `TextToSpeech` with `Locale("ru")`. It reports `LANG_MISSING_DATA` and `LANG_NOT_SUPPORTED` as `SpeechUnavailable`.
  - `fun speakableText(markdown: String): String`: it drops code blocks, Markdown marks, link URLs, and the source-limitation note.
  - `fun speechChunks(text: String, max: Int): List<String>`: it splits at sentence ends, and at spaces when a sentence is longer than `max`.
  - `class VoiceSettings(context: Context)` with `var readAloud: Boolean` (default false) and `var source: SpeechSource` (`PHONE` default, `CLOUD`).
  - `VoiceSession.replyCompleted(reply: String)`: in a continuous session with `readAloud`, phase `"speaking"` runs before `listen`. `stop()` stops speech.

Strings: `voice_read_aloud` «Читать ответы вслух»; `voice_source` «Источник голоса»; `voice_source_phone` «Телефон»; `voice_source_cloud` «Облако»; `voice_speaking` «Читаю ответ…»; `voice_no_russian` «На телефоне нет русского голоса. Установите его в настройках синтеза речи.»

- [ ] **Step 1: Write the failing tests** with a fake `SpeechOutput` that records calls:

```kotlin
@Test fun continuousSessionSpeaksThenListens() // reply "**Привет**. Как дела?": speak("Привет. Как дела?"), then the phase becomes "listening"
@Test fun oneShotDoesNotSpeak()
@Test fun readAloudOffDoesNotSpeak()
@Test fun longReplySpeaksInOrderedChunks() {
    val reply = List(30) { "Предложение номер $it." }.joinToString(" ")
    finishReply(reply)
    assertEquals(speechChunks(reply, 3000), fake.spoken)
    session.stop(); assertTrue(fake.stopped)
}
@Test fun missingRussianVoiceShowsStatus() // the fake throws SpeechUnavailable: the voice_no_russian text appears and listening still starts
```

- [ ] **Step 2: Run** `com.alpha.assistant.voice.VoiceOutputWorkflowTest`. Expected: FAIL.
- [ ] **Step 3: Implement.** Take audio focus for speech, and release it before `listen`. Use `TextToSpeech.getMaxSpeechInputLength()` as the chunk limit for `DeviceSpeech`. Next to the `voice_no_russian` status, add a button that opens the `com.android.settings.TTS_SETTINGS` intent (spec 12.4).
- [ ] **Step 4: Run it, plus the existing voice workflow tests.** Expected: PASS.
- [ ] **Step 5: Commit.** Message: "Read replies aloud in the continuous voice session".

### Task 20: Cloud voice through OpenRouter and DeepSeek

**Files:**
- Modify: `app/src/main/java/com/alpha/assistant/voice/SpeechOutput.kt`
- Test: `app/src/androidTest/java/com/alpha/assistant/voice/CloudSpeechTest.kt`

**Interfaces:**
- Consumes: `SecureKeyStore.account()`, `AiModels.speech()`, `Endpoints`, `providerFailure`.
- Produces: `class CloudSpeech(context: Context, account: () -> AiAccount?, model: () -> SpeechModel, endpoints: Endpoints) : SpeechOutput`. It routes by the account's provider:
  - OpenRouter: `POST /api/v1/audio/speech` with `{"model", "input", "voice", "response_format": "mp3"}`. Chunk limit: 4000 characters.
  - DeepSeek: `POST /v1/tools/tts` with the `SpeechModel` voice (`stella`) and `wav` output. Take the body field names from the DeepSeek TTS guide. Chunk limit: 10,000 characters.
  - It writes the audio to `cacheDir/speech/<uuid>.<mp3|wav>` (at most 10 MiB), plays it with `MediaPlayer`, and deletes the file after playback or Stop. `VoiceSession` uses it when `source == CLOUD` and the account has `CLOUD_VOICE`. On failure it falls back to `DeviceSpeech` for that reply and shows `voice_cloud_failed` «Облачная озвучка не удалась. Ответ прочитан голосом телефона.»

- [ ] **Step 1: Write the failing tests.**

```kotlin
@Test fun openRouterRequestShape() // POST /api/v1/audio/speech, Bearer key, response_format mp3, voice from the selected SpeechModel
@Test fun deepSeekRequestShape() // POST /v1/tools/tts, Bearer key, voice stella, wav output
@Test fun fileDeletedAfterPlayback() // cacheDir/speech is empty after speak() returns
@Test fun failureFallsBackToPhone() // HTTP 402: the fallback fake speaks the text, and voice_cloud_failed is shown
@Test fun cloudSourceHiddenWithoutCapability() // OpenAI and Anthropic accounts: the «Облако» choice is not shown
```

- [ ] **Step 2: Run** `com.alpha.assistant.voice.CloudSpeechTest`. Expected: FAIL.
- [ ] **Step 3: Implement.** Add `consent_voice` to the consent text of every OpenRouter and DeepSeek account, whatever the voice source (spec 14.7). Selecting «Облако» asks for no second consent.
- [ ] **Step 4: Run it.** Expected: PASS.
- [ ] **Step 5: Commit.** Message: "Speak replies through cloud voice".

## Phase 9. Clean-up, tooling, and acceptance

### Task 21: Provider-neutral wording and documentation

**Files:**
- Modify: `assistant_strings.xml`, `automation_editor_strings.xml`, `live_source.xml`, `shell_course_creation_strings.xml`, `widget_layout_strings.xml`, `strings.xml`
- Modify: `app/src/main/java/com/alpha/assistant/capabilities/MiniAppAi.kt:26-60`, `ToolEngine.kt:2614`, `CapabilityContract.kt`, `AndroidCapabilities.kt`, `AgentAutomation.kt`
- Modify: `README.md`, `docs/cloud-integration.md`, `docs/voice-integration.md`, `docs/automations.md`, `docs/shell-integration.md`, `docs/native-capabilities.md`
- Modify: `app/src/androidTest/java/com/alpha/assistant/portability/MiniAppPortabilityWorkflowTest.kt:63,67`

- [ ] **Step 1: Find every remaining reference.**

Run: `grep -rni deepseek app/src/main README.md docs/*.md`
Expected: only the DeepSeek profile, `DeepSeekSearch`, the DeepSeek speech path, the `legacy_deepseek*` strings, `LegacyKeys`, and the DeepSeek key-help URL.

- [ ] **Step 2: Where «DeepSeek» names the service for every provider, use the active provider name** (format argument) or «сервис ИИ». Update the docs for the four providers: account, wire formats, structured decision, search, vision, images, voice input (spec 12A), and voice output. `docs/voice-integration.md` and `docs/voice-reuse.md` lose T-One and sherpa-onnx.
- [ ] **Step 3: Run Step 1 again.** Expected: the same short list.
- [ ] **Step 4: Run the full device suite against the known-failures baseline.**

Run: `python3 tools/compose_acceptance/run.py suite --label unified-ai --serial emulator-5564 --baseline <latest main run.json>`
Expected: no new failures.

- [ ] **Step 5: Commit.** Message: "Name the active AI provider and document the unified account".

### Task 22: Live tooling

**Files:**
- Modify: `tools/compose_acceptance/run.py:42-89` (key source), `tools/compose_acceptance/test_harness.py:209-237,448-451`
- Modify: `tools/compose_acceptance/comparison/adapters/android_capture.py:17-23,67`, `reference_capture.py:79`
- Modify: the live tests `LiveCourseWorkflowTest.kt:80`, `ComparisonChatCaptureTest.kt:222`, `LivePhoneSelectionWorkflow.kt:74`, `ComposeScenarioAcceptanceTest.kt:110`, `ThreeSceneLiveWorkflowTest.kt:70`, `LiveSharedImageWorkflowTest.kt:69-71`, `SyntheticSchemaDiagnosticTest.kt`

**Interfaces:**
- Produces: `run.py` reads `ALPHY_AI_PROVIDER` (`openai`, `anthropic`, `openrouter`, `deepseek`), then that provider's key variable (`OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `OPENROUTER_API_KEY`, or `DEEPSEEK_API_KEY`, spec 15.2) from the environment or `~/.env`. It sends both through the existing one-use adb transport as `<provider>\n<key>`. Live tests build `CloudChatProvider` from that account. `LiveSharedImageWorkflowTest` needs one credential, not two. A new `app/src/androidTest/java/com/alpha/assistant/voice/LiveVoiceInputTest.kt` sends `voice/russian.wav` through `speechInputFor` with the live account and asserts that the text contains «таймер» and «пять» or «5» (spec 12A.10).

- [ ] **Step 1: Change the host tests first.** `test_harness.py` asserts that both variables are read, that the key never reaches a child process environment, and that a provider other than the four values is rejected.
- [ ] **Step 2: Run** `python3 tools/compose_acceptance/run.py host --label unified-ai-tooling --expect failed`. Expected: FAIL.
- [ ] **Step 3: Implement it in `run.py` and the live tests.** Comparison captures record the provider and the model.
- [ ] **Step 4: Run the host tests, and `run.py run --credential-check` (no provider call).** Expected: PASS.
- [ ] **Step 5: Commit.** Message: "Pass the AI provider and key to live runs".

### Task 23: Live acceptance for each provider

**Gate:** the user approves each batch (the 6 runs of one model) and its cost.

- [ ] **Step 1: Run the live acceptance for each curated chat model.** Run scenario 3 (app) and scenario 4 (game), 3 repetitions each, with the shared behavior checker (AGENTS.md).

Run, for each `<s>` in 3 and 4 and each `<r>` in 1, 2, and 3: `ALPHY_AI_PROVIDER=<id> python3 tools/compose_acceptance/run.py run --serial emulator-5564 --scenario <s> --repetition <r> --live`
Expected: for each scenario, at least as many passed runs as the DeepSeek baseline in spec 16.6 (Task 0). `deepseek-flash` is the baseline, so it needs no new batch.

- [ ] **Step 2: Run one live check of each function on each provider.** Check chat, search with sources, a photo question, image generation (OpenAI, OpenRouter), cloud voice (OpenRouter, and DeepSeek in Russian), and voice input with `LiveVoiceInputTest` (OpenAI, OpenRouter, DeepSeek; three requests, under $0.01 in total). If a voice-input model does not return the Russian text, report it to the user before release (spec 12A.10). If the DeepSeek voice does not speak Russian, remove `CLOUD_VOICE` from the DeepSeek profile (spec 12.7). If a provider rejects a selector schema, set its `jsonSchema` to `false` (spec 8.8). Record the evidence in `evidence/unified-ai/`.
- [ ] **Step 3: Remove from `ProviderProfile` every model that failed Step 1.** Commit the change. If no Anthropic model passed, stop and report to the user before any release (spec 8.3).
- [ ] **Step 4: Verify the release build** by AGENTS.md before any GitHub release: `aapt dump badging` succeeds and has no `application-debuggable` entry.
