package com.alpha.assistant.cloud

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alpha.assistant.images.imageProviders
import com.alpha.assistant.voice.speechWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiProviderTest {
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
        // Spec 6.5 refuses an admin key for every selected provider, so a manual choice cannot send it elsewhere.
        for (provider in AiProvider.entries) assertFalse(provider.accepts("sk-ant-admin01-abc"))
        assertTrue(AiProvider.ANTHROPIC.accepts("sk-ant-api03-abc"))
    }

    // Failure case: a test failure message or a log line shows the key of an account.
    @Test fun accountTextHidesTheKey() {
        val account = AiAccount(AiProvider.OPENAI, "sk-proj-alpha-test-only")
        assertFalse(account.key in account.toString())
        assertTrue(AiProvider.OPENAI.name in account.toString())
        assertEquals(AiAccount(AiProvider.OPENAI, "sk-proj-alpha-test-only"), account)
    }

    @Test fun acceptsRejectsBlankAndControlCharacters() {
        assertFalse(AiProvider.OPENAI.accepts(""))
        assertFalse(AiProvider.OPENAI.accepts("   "))
        assertFalse(AiProvider.DEEPSEEK.accepts("sk-ab c"))
        // Failure case: OkHttp refuses a header with a non-ASCII character and puts the x-api-key value in the error.
        assertFalse(AiProvider.ANTHROPIC.accepts("sk-ant-api03-ключ"))
        assertTrue(AiProvider.OPENROUTER.accepts("sk-ant-api03-abc"))
    }

    @Test fun capabilitiesMatchSpec() {
        assertEquals(setOf(Capability.CHAT, Capability.SEARCH, Capability.VISION), AiProvider.ANTHROPIC.profile.capabilities)
        assertFalse(Capability.CLOUD_VOICE in AiProvider.OPENAI.profile.capabilities)
        assertTrue(Capability.IMAGES in AiProvider.OPENAI.profile.capabilities)
        assertEquals(Capability.entries.toSet(), AiProvider.OPENROUTER.profile.capabilities)
        // User decision 2026-11-17: DeepSeek has no cloud voice.
        assertEquals(Capability.entries.toSet() - Capability.IMAGES - Capability.CLOUD_VOICE, AiProvider.DEEPSEEK.profile.capabilities)
        assertTrue(AiProvider.DEEPSEEK.profile.speechModels.isEmpty())
    }

    // A capability flag implies a request path for that function (arch review, proposed contract).
    @Test fun capabilityFlagsHaveRequestPaths() {
        val pictureProviders = imageProviders(Endpoints.PRODUCTION).keys
        for (provider in AiProvider.entries) {
            val capabilities = provider.profile.capabilities
            assertEquals(provider.name, Capability.CLOUD_VOICE in capabilities, speechWire(provider) != null)
            assertEquals(provider.name, Capability.CLOUD_VOICE in capabilities, provider.profile.speechModels.isNotEmpty())
            assertEquals(provider.name, Capability.IMAGES in capabilities, provider.id in pictureProviders)
        }
    }
}
