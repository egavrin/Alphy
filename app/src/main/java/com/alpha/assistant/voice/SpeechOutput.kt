package com.alpha.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.alpha.assistant.R
import com.alpha.assistant.cloud.AiAccount
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.ChatToolReply
import com.alpha.assistant.cloud.CloudHttp
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.cloud.Endpoints
import com.alpha.assistant.cloud.SpeechModel
import com.alpha.assistant.cloud.bearerHeaders
import com.alpha.assistant.cloud.checked
import com.alpha.assistant.cloud.markdownParser
import com.alpha.assistant.cloud.reply
import com.alpha.assistant.cloud.sourceReference
import com.alpha.assistant.cloud.sourceUrls
import com.alpha.assistant.cloud.utf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.node.Block
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Node
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One way to read a reply aloud (spec 12.1). [speak] returns after the speech ends. It throws [SpeechUnavailable] when the
 * phone has no Russian voice, and a different exception for any other engine error.
 */
internal interface SpeechOutput {
    suspend fun speak(text: String)
    /** Ends the speech at once and releases the engine. A pending [speak] ends only when the caller also cancels its coroutine. */
    fun stop()
}

/** The audio of the voice session: the focus request of the session and the speech use it (spec 12.6). */
internal val ASSISTANT_SPEECH: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

/**
 * The phone engine reads the reply with its Russian voice (spec 12.4). Every call runs on the main thread. The engine
 * starts at the first [speak] and shuts down at [stop]. The text never reaches a log (spec 3.4).
 */
internal class DeviceSpeech(private val context: Context) : SpeechOutput {
    private var engine: TextToSpeech? = null
    private var batches = 0

    override suspend fun speak(text: String) = withContext(Dispatchers.Main.immediate) {
        val tts = engine ?: open().also { engine = it }
        val chunks = speechChunks(text, TextToSpeech.getMaxSpeechInputLength())
        if (chunks.isEmpty()) return@withContext
        // Utterance ids name the batch, so a late event of an earlier batch does not end this one.
        val batch = "${++batches}:"
        val done = CompletableDeferred<Unit>()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { if (utteranceId == batch + chunks.lastIndex) done.complete(Unit) }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { if (utteranceId?.startsWith(batch) == true) done.complete(Unit) }
            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId?.startsWith(batch) == true) done.completeExceptionally(speechFailure(context, errorCode))
            }
            // The engine calls it only when the override above is missing, but Java still requires it.
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId?.startsWith(batch) == true) done.completeExceptionally(IllegalStateException("The phone engine did not read the reply"))
            }
        })
        try {
            chunks.forEachIndexed { index, chunk ->
                check(tts.speak(chunk, TextToSpeech.QUEUE_ADD, null, batch + index) == TextToSpeech.SUCCESS) { "The phone engine refused the reply" }
            }
            done.await()
        } catch (failure: Throwable) {
            // Stop, an error, or a refused chunk: the rest of the queue must not play into the microphone that listens next.
            engine?.stop()
            throw failure
        }
    }

    private suspend fun open(): TextToSpeech {
        lateinit var tts: TextToSpeech
        val status = suspendCancellableCoroutine { continuation ->
            tts = TextToSpeech(context) { if (continuation.isActive) continuation.resume(it) }
            continuation.invokeOnCancellation { tts.shutdown() }
        }
        // An engine that does not start has no Russian voice either: the fix is the same system setting.
        val language = if (status == TextToSpeech.SUCCESS) tts.setLanguage(Locale.forLanguageTag("ru")) else TextToSpeech.LANG_NOT_SUPPORTED
        if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.shutdown()
            throw SpeechUnavailable(context.getString(R.string.voice_no_russian))
        }
        tts.setAudioAttributes(ASSISTANT_SPEECH)
        return tts
    }

    override fun stop() {
        engine?.run { stop(); shutdown() }
        engine = null
    }
}

/** The cloud voice failed, and the phone read the rest of the reply (spec 12.5). [message] is the status. */
internal class CloudVoiceFailed(message: String) : Exception(message)

private val speechHttp = CloudHttp(null, 60_000)
private const val MAX_AUDIO = 10 * 1024 * 1024

/** The cloud voice request of one provider: the path, the audio format, and the most characters in one request. */
internal class SpeechWire(val path: String, val format: String, val accept: String, val limit: Int, val body: (SpeechModel, String) -> JSONObject)

/** The cloud voice wire of [provider], or null when the provider has no cloud voice (user decision 2026-11-17: DeepSeek has none, spec 12.7). */
internal fun speechWire(provider: AiProvider): SpeechWire? = when (provider) {
    AiProvider.OPENROUTER -> SpeechWire("/api/v1/audio/speech", "mp3", "audio/mpeg", 4000) { model, text ->
        JSONObject().put("model", model.wire).put("input", text).put("voice", model.voice).put("response_format", "mp3")
    }
    AiProvider.OPENAI, AiProvider.ANTHROPIC, AiProvider.DEEPSEEK -> null
}

/** The audio file of one chunk of text in the [voice] of the account provider (spec 12.7). The live voice check uses it too. */
internal suspend fun cloudSpeechAudio(account: AiAccount, endpoints: Endpoints, voice: SpeechModel, text: String): ByteArray {
    val wire = checkNotNull(speechWire(account.provider)) { "${account.provider.displayName} has no cloud voice" }
    return speechHttp.reply(account.provider, endpoints.checked(account.provider) + wire.path,
        bearerHeaders(account.key) + ("Accept" to wire.accept), "application/json", wire.body(voice, text).utf8(),
        MAX_AUDIO, CloudSession()) { bytes, _ -> checkNotNull(bytes) { "The audio is larger than 10 MiB" } }
}

/**
 * Reads each reply with the cloud voice of the account when «Источник голоса» is «Облако» and the provider has cloud voice
 * (spec 12.5, 12.7). Otherwise [phone] reads it. Each chunk goes to `cacheDir/speech` as one file, which is deleted after its
 * playback, a Stop, or a failure. A failure lets [phone] read the rest of the reply, then throws [CloudVoiceFailed]. The
 * playback runs under the audio focus of the session and requests none. Nothing logs the key, the text, or the audio (spec 3.4).
 */
internal class CloudSpeech(
    private val context: Context,
    private val settings: VoiceSettings,
    private val account: () -> AiAccount?,
    private val model: (AiProvider) -> SpeechModel,
    private val endpoints: Endpoints,
    private val phone: SpeechOutput,
) : SpeechOutput {
    // Only the main thread uses the player.
    private var player: MediaPlayer? = null

    override suspend fun speak(text: String) {
        if (settings.source != SpeechSource.CLOUD) return phone.speak(text)
        var chunks = listOf(text)
        var played = 0
        try {
            // The account and its model are read once for each reply, so a new key never gets the text of the old provider (spec 2.11).
            val cloud = withContext(Dispatchers.IO) { account() }?.takeIf { settings.sourceFor(it.provider) == SpeechSource.CLOUD }
            val wire = cloud?.let { speechWire(it.provider) }
            if (cloud != null && wire != null) {
                val voice = model(cloud.provider)
                chunks = speechChunks(text, wire.limit)
                for (chunk in chunks) { play(cloud, wire, voice, chunk); played++ }
                return
            }
        } catch (stop: CancellationException) { throw stop }
        catch (_: Exception) {
            // A failed account read, request, or playback: the phone reads the chunks that did not play (spec 12.5).
            currentCoroutineContext().ensureActive()
            phone.speak(chunks.drop(played).joinToString(" "))
            throw CloudVoiceFailed(context.getString(R.string.voice_cloud_failed))
        }
        phone.speak(text)
    }

    private suspend fun play(account: AiAccount, wire: SpeechWire, voice: SpeechModel, text: String) = withContext(Dispatchers.IO) {
        val audio = cloudSpeechAudio(account, endpoints, voice, text)
        val folder = File(context.cacheDir, "speech")
        // A crash can leave a file, so each new file deletes the old ones first.
        folder.listFiles()?.forEach(File::delete)
        val file = File(folder.apply { mkdirs() }, "${UUID.randomUUID()}.${wire.format}")
        try {
            file.writeBytes(audio)
            withContext(Dispatchers.Main.immediate) { playFile(file) }
        } finally { file.delete() }
    }

    private suspend fun playFile(file: File) {
        val media = MediaPlayer().also { player = it }
        try {
            suspendCancellableCoroutine { done ->
                media.setAudioAttributes(ASSISTANT_SPEECH)
                media.setOnPreparedListener { it.start() }
                media.setOnCompletionListener { if (done.isActive) done.resume(Unit) }
                media.setOnErrorListener { _, what, extra ->
                    if (done.isActive) done.resumeWithException(IOException("The audio did not play ($what, $extra)"))
                    true
                }
                media.setDataSource(file.path)
                media.prepareAsync()
            }
        } finally {
            // stop() can release the player first. Each player is released once.
            if (player === media) { media.release(); player = null }
        }
    }

    /** Ends the playback at once. The canceled job of the session ends the request and deletes the file. */
    override fun stop() {
        player?.release()
        player = null
        phone.stop()
    }
}

/** The failure of an utterance with the [errorCode] of the engine. Only a voice that is not installed yet has a fix in the settings. */
internal fun speechFailure(context: Context, errorCode: Int): Exception =
    if (errorCode == TextToSpeech.ERROR_NOT_INSTALLED_YET) SpeechUnavailable(context.getString(R.string.voice_no_russian))
    else IllegalStateException("The phone engine did not read the reply ($errorCode)")

private val citation = Regex("""\s*""" + sourceReference.pattern)

/** The words of a reply that the voice reads: no code blocks, Markdown marks, link URLs, source chips, or source note (spec 12.3). */
internal fun speakableText(markdown: String, parse: (String) -> Node = markdownParser::parse): String {
    val text = listOf(ChatToolReply.SOURCE_LIMITATION, ChatToolReply.NO_SOURCES).fold(markdown) { rest, note -> rest.replace(note, "") }
    val words = StringBuilder()
    fun visit(node: Node) {
        when (node) {
            is FencedCodeBlock, is IndentedCodeBlock, is HtmlBlock, is HtmlInline -> return
            is Text -> words.append(node.literal.replace(citation, ""))
            is Code -> words.append(node.literal)
            is SoftLineBreak, is HardLineBreak -> words.append(' ')
            else -> generateSequence(node.firstChild) { it.next }.forEach(::visit)
        }
        if (node is Block || node is TableRow) words.append('\n') else if (node is TableCell) words.append(' ')
    }
    // A reply that does not parse is not read: the voice would read its Markdown marks.
    visit(runCatching { parse(text) }.getOrElse { return "" })
    return sourceUrls.replace(words, "").lines().map { it.replace(spaces, " ").trim() }.filter(String::isNotEmpty).joinToString("\n")
}

private val spaces = Regex("""\s+""")
private val sentenceEnd = Regex("""(?<=[.!?…])\s+""")

/** Splits [text] into chunks of at most [max] characters: at sentence ends, and at spaces in a longer sentence. */
internal fun speechChunks(text: String, max: Int): List<String> {
    require(max > 0)
    val chunks = mutableListOf<String>()
    var chunk = ""
    // Pieces fill a chunk up to the limit, so an engine or a request gets as few chunks as it can.
    fun add(piece: String) {
        chunk = when {
            chunk.isEmpty() -> piece
            chunk.length + 1 + piece.length <= max -> "$chunk $piece"
            else -> { chunks += chunk; piece }
        }
    }
    for (sentence in text.trim().split(sentenceEnd)) {
        if (sentence.length <= max) add(sentence) else sentence.split(spaces).flatMap { it.chunked(max) }.forEach(::add)
    }
    if (chunk.isNotEmpty()) chunks += chunk
    return chunks
}
