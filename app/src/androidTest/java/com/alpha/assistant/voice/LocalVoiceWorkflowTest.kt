package com.alpha.assistant.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalVoiceWorkflowTest {
    private fun report(value: String) {
        android.util.Log.i("AlphaVoiceFixture", value)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("alpha_voice_result", value) })
    }
    @Test fun microphoneStopsWhenActivityPauses() = microphoneStopsOnPause(oneShot = false)
    @Test fun oneShotMicrophoneStopsWhenActivityPauses() = microphoneStopsOnPause(oneShot = true)

    private fun microphoneStopsOnPause(oneShot: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        var voice: VoiceSession? = null
        androidx.test.core.app.ActivityScenario.launch(com.alpha.assistant.VoiceTestActivity::class.java).use { scenario ->
            val permissionDeadline = android.os.SystemClock.uptimeMillis() + 15000
            fun allow(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
                if (node == null) return false
                if (node.viewIdResourceName?.endsWith(":id/permission_allow_foreground_only_button") == true) return node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                for (i in 0 until node.childCount) if (allow(node.getChild(i))) return true
                return false
            }
            while (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED && android.os.SystemClock.uptimeMillis() < permissionDeadline) {
                allow(instrumentation.uiAutomation.rootInActiveWindow)
                android.os.SystemClock.sleep(100)
            }
            assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO))
            scenario.onActivity { activity -> voice = activity.voice; activity.voice.start(true, false, oneShot) }
            val deadline = android.os.SystemClock.uptimeMillis() + 20000
            var listening = false
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync { listening = voice?.phase == "listening" && context.getSystemService(android.media.AudioManager::class.java).activeRecordingConfigurations.isNotEmpty() }
                if (listening) break
                android.os.SystemClock.sleep(50)
            }
            assertTrue("Voice never reached listening: ${voice?.status}", listening)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            instrumentation.runOnMainSync { assertFalse(voice!!.active); assertEquals("idle", voice!!.phase) }
            android.os.SystemClock.sleep(300)
            val manager = context.getSystemService(android.media.AudioManager::class.java)
            assertTrue("Microphone remains active", manager.activeRecordingConfigurations.isEmpty())
            report("Activity pause: oneShot=$oneShot, idle, microphone released")
        }
    }

    @Test fun productionApkContainsOnlyToneRecognition() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        java.util.zip.ZipFile(context.applicationInfo.sourceDir).use { apk ->
            val names = apk.entries().asSequence().map { it.name }.toList()
            assertTrue(names.any { it == "assets/voice/tone/model.onnx" })
            assertFalse(names.any { it.contains("whisper", ignoreCase = true) })
        }
        val manifest = context.assets.open("voice/manifest.json").bufferedReader().use { it.readText() }
        assertFalse(manifest.contains("whisper", ignoreCase = true))
        assertTrue(runCatching { Class.forName("com.offlineassistant.app.asr.WhisperTranscriber") }.isFailure)
    }

    @Test fun recordedRussianRecognition(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val models = VoiceModels(context)
        models.prepare()
        val wav = File(context.cacheDir, "voice-fixture.wav")
        instrumentation.context.assets.open("voice/russian.wav").use { input -> wav.outputStream().use(input::copyTo) }
        try {
            val recognizer = models.recognizer()
            try {
                val wave = com.k2fsa.sherpa.onnx.WaveReader.readWave(wav.absolutePath)
                val stream = checkNotNull(recognizer.startStreaming({}, {}))
                val result = try {
                    wave.samples.asList().chunked(1600).forEach { block -> stream.acceptPcm16(ShortArray(block.size) { (block[it] * 32767f).toInt().toShort() }, wave.sampleRate) }
                    stream.finish()
                } finally { stream.cancel() }
                assertNull("T-one: ${result.error}", result.error)
                val text = result.text.orEmpty().lowercase()
                report("T-one: $text")
                assertTrue("T-one: $text", text.contains("таймер") && (text.contains("пять") || text.contains("5")))
            } finally { recognizer.release() }
        } finally { wav.delete() }
    }
}
