package com.alpha.assistant.voice

import android.content.Context
import android.os.Build
import com.offlineassistant.app.asr.TOneStreamingTranscriber
import com.offlineassistant.app.models.ModelReadiness
import com.offlineassistant.app.voice.AudioTranscriber
import com.offlineassistant.app.speech.*
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Models are data from this APK. No runtime executable download is permitted. */
class VoiceModels(private val context: Context) {
    private val directory = File(context.noBackupFilesDir, "voice/alpha-voice-1")
    fun prepare(includeSpeech: Boolean = true) {
        check(Build.SUPPORTED_ABIS.contains("arm64-v8a")) { "Local voice requires ARM64. This ABI is unsupported." }
        val manifest = context.assets.open("voice/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        val files = manifest.getJSONArray("files")
        for (i in 0 until files.length()) {
            val entry = files.getJSONObject(i)
            val name = entry.getString("path")
            if (!includeSpeech && !name.startsWith("tone/")) continue
            val output = File(directory, name)
            check(output.canonicalPath.startsWith(directory.canonicalPath + File.separator)) { "Invalid model path" }
            fun valid(file: File) = file.isFile && file.length() == entry.getLong("bytes") && hash(file) == entry.getString("sha256")
            if (valid(output)) continue
            output.parentFile!!.mkdirs()
            val temporary = File(output.parentFile, output.name + ".partial")
            try {
                context.assets.open("voice/$name").use { input -> temporary.outputStream().use(input::copyTo) }
                check(valid(temporary)) { "Model checksum mismatch: $name" }
                check(temporary.renameTo(output)) { "Cannot install model: $name" }
            } finally { temporary.delete() }
        }
    }
    fun recognizer(): AudioTranscriber = TOneStreamingTranscriber(ModelReadiness(true, File(directory, "tone").absolutePath))
    fun synthesizer(): SileroSpeechSynthesizer {
        val folder = File(directory, "silero")
        return SileroSpeechSynthesizer.fromBundle(SileroModelBundle.fromDirectory(folder), SileroFrontendBundle.fromDirectory(folder))
    }
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
