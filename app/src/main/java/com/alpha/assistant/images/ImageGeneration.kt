package com.alpha.assistant.images

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.alpha.assistant.R
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.Capability
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudHttp
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.cloud.Endpoints
import com.alpha.assistant.cloud.bearerHeaders
import com.alpha.assistant.cloud.checked
import com.alpha.assistant.cloud.reply
import com.alpha.assistant.cloud.utf8
import com.alpha.assistant.data.SecureKeyStore
import com.alpha.assistant.tools.three.ThreeSceneAssetCleanup
import com.alpha.assistant.tools.three.ThreeSceneAssetRef
import com.alpha.assistant.tools.three.ThreeSceneAssets
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

enum class ImageAspect(val wire: String, val ratio: Double) {
    SQUARE("1:1", 1.0), LANDSCAPE("3:2", 1.5), EQUIRECTANGULAR("2:1", 2.0);
    companion object { fun read(wire: String) = entries.single { it.wire == wire } }
}

data class ImageIntent(val slot: String, val prompt: String, val alt: String, val aspect: ImageAspect) {
    init {
        require(slot.matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,39}")))
        require(prompt.length in 1..1500 && prompt.isNotBlank() && prompt.none { it == '\u0000' })
        require(alt.length in 1..160 && alt.isNotBlank() && alt.none(Char::isISOControl))
    }
    fun json() = JSONObject().put("slot", slot).put("prompt", prompt).put("alt", alt).put("aspect", aspect.wire)

    companion object {
        fun read(value: JSONObject): ImageIntent {
            require(value.keys().asSequence().toSet() == setOf("slot", "prompt", "alt", "aspect"))
            return ImageIntent(value.get("slot") as String, value.get("prompt") as String,
                value.get("alt") as String, ImageAspect.read(value.get("aspect") as String))
        }

        fun list(raw: Any?, limit: Int): List<ImageIntent> {
            if (raw == null || raw == JSONObject.NULL) return emptyList()
            val values = raw as? JSONArray ?: error("Expected an image request array")
            require(values.length() <= limit)
            return List(values.length()) { read(values.getJSONObject(it)) }.also { intents ->
                require(intents.distinctBy { it.slot }.size == intents.size)
            }
        }

        const val GUIDANCE = """Each image request is {"slot":"uniqueId","prompt":"visual description only","alt":"short accessible description","aspect":"1:1"}. Use 1:1 or 3:2 for illustrations and 2:1 for an equirectangular texture. Use the user's requested visual content only. Do not include conversation history, personal data, source text, links, or attachments in the image prompt."""
    }
}

internal data class ImagePlan(val requests: List<ImageIntent> = emptyList(), val remove: Set<String> = emptySet(),
    val requestExcerpt: String? = null) {
    companion object {
        fun threeScene(root: JSONObject, originalRequest: String, existing: Set<String>): ImagePlan {
            require(root.keys().asSequence().toSet() == setOf("disposition", "excerpt", "imageRequests", "removeImageSlots"))
            require(root.get("imageRequests") is JSONArray && root.get("removeImageSlots") is JSONArray)
            val disposition = root.remove("disposition") as String
            val excerpt = root.remove("excerpt") as String
            val plan = take(root, 4, existing)
            if (disposition == "unchanged") {
                require(excerpt.isEmpty() && plan.requests.isEmpty() && plan.remove.isEmpty())
            } else {
                fun normalize(text: String) = text.replace(Regex("""[ \t\r\n]+"""), " ").trim(' ')
                require(excerpt.length in 1..600 && excerpt.isNotBlank() && normalize(excerpt) in normalize(originalRequest))
                when (disposition) {
                    "generate" -> require(plan.requests.isNotEmpty())
                    "remove" -> require(plan.requests.isEmpty() && plan.remove.isNotEmpty())
                    else -> error("Unknown Three.js texture disposition.")
                }
            }
            return plan.copy(requestExcerpt = excerpt.takeIf(String::isNotEmpty))
        }

        fun take(root: JSONObject, limit: Int, existing: Set<String>): ImagePlan {
            val requests = ImageIntent.list(root.remove("imageRequests"), limit)
            val raw = root.remove("removeImageSlots")
            val remove = if (raw == null) emptySet() else {
                require(limit > 0 && raw is JSONArray && raw.length() <= existing.size)
                List(raw.length()) { raw.get(it) as String }.toSet().also { require(it.size == raw.length()) }
            }
            require(existing.containsAll(remove) && requests.none { it.slot in remove })
            return ImagePlan(requests, remove)
        }
    }
}

internal enum class ImageModel(val wire: String, val title: String, val provider: AiProvider, val route: String?) {
    QWEN("qwen/qwen-image-2.1-turbo", "Qwen Image 2.1 Turbo", AiProvider.OPENROUTER, "alibaba"),
    SEEDREAM("bytedance-seed/seedream-4.5", "Seedream 4.5", AiProvider.OPENROUTER, "seed"),
    GPT_IMAGE_FLARE("gpt-image-2.6-flare", "GPT Image 2.6 Flare", AiProvider.OPENAI, null),
    GPT_IMAGE_SUNBURST("gpt-image-2.6-sunburst", "GPT Image 2.6 Sunburst", AiProvider.OPENAI, null);
}

/** The image settings of the stored AI account. Only a provider with the images capability can generate (spec 11.1, 11.5). */
internal class ImageSettings(context: Context, private val keys: SecureKeyStore = SecureKeyStore(context), name: String = STORE) {
    companion object {
        /** The preference file of the app. */
        const val STORE = "alpha_images"
    }
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    data class Selection(val model: ImageModel, val consent: String, val access: Long)
    private val provider: AiProvider?
        get() = keys.provider()?.takeIf { Capability.IMAGES in it.profile.capabilities }
    val available: Boolean get() = provider != null
    /** The models of [provider], with no key read. The list is empty when [provider] cannot generate images. */
    fun models(provider: AiProvider?): List<ImageModel> = ImageModel.entries.filter { it.provider == provider }
    private val stored: ImageModel? get() = prefs.getString("model", null)?.let { name -> ImageModel.entries.firstOrNull { it.name == name } }
    /** The stored model when it belongs to the account provider. Otherwise the first model of that provider. */
    val model: ImageModel? get() = model(provider)
    /** The stored model when it belongs to [provider], otherwise the first model of [provider]. No key is read. */
    fun model(provider: AiProvider?): ImageModel? = models(provider).let { list -> stored?.takeIf { it in list } ?: list.firstOrNull() }
    val enabled get() = prefs.getBoolean("enabled", false)
    private val access get() = prefs.getLong("access", 0)
    fun selection(): Selection? = if (enabled) model?.let { chosen -> keys.consentIdentity()?.let { Selection(chosen, it, access) } } else null
    fun isCurrent(selection: Selection) = enabled && access == selection.access && keys.consentIdentity() == selection.consent
    fun key(selection: Selection): String {
        check(isCurrent(selection))
        val account = checkNotNull(keys.account().getOrThrow())
        check(account.provider == selection.model.provider)
        return account.key.also { require(it.none { c -> c.isWhitespace() || c.isISOControl() }) }
    }
    fun setEnabled(value: Boolean): Result<Unit> = runCatching {
        check(!value || available)
        check(prefs.edit().putBoolean("enabled", value).putLong("access", access + 1).commit())
    }
    fun select(value: ImageModel) { prefs.edit().putString("model", value.name).apply() }
    /** Keeps the model choice made for [provider]. A stored model names its provider, and a choice for a different one resets (spec 6.7). */
    fun keepFor(provider: AiProvider) { if (stored?.provider != provider) prefs.edit().remove("model").apply() }
    fun observeAccess(change: () -> Unit): java.io.Closeable {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, field ->
            if (field == null || field == "access") change()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        val credential = keys.observeConsent(change)
        return java.io.Closeable {
            credential.close()
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
}

internal data class GeneratedImage(
    val slot: String, val alt: String, val aspect: ImageAspect, val asset: ThreeSceneAssetRef,
    val width: Int, val height: Int, val model: String, val cost: Double? = null,
    val provider: String? = null, val receipt: String? = null,
) {
    fun json() = asset.json().put("slot", slot).put("alt", alt).put("aspect", aspect.wire)
        .put("width", width).put("height", height).put("model", model).put("cost", cost)
        .put("provider", provider).put("receipt", receipt)

    companion object {
        const val ATTACHMENT = "application/vnd.alphy.generated-image"
        const val FIELD = "generatedImages"
        fun read(value: JSONObject): GeneratedImage {
            val asset = ThreeSceneAssetRef(value.getString("id"), value.getString("name"),
                value.getString("mime"), value.getLong("size"))
            require(asset.id.matches(Regex("[0-9a-f]{64}")) && asset.size in 1..MAX_IMAGE_BYTES &&
                asset.mime in imageTypes && asset.name.length in 1..120)
            val slot = value.getString("slot")
            val alt = value.getString("alt")
            val aspect = ImageAspect.read(value.getString("aspect"))
            ImageIntent(slot, "Stored image", alt, aspect)
            val width = value.getInt("width")
            val height = value.getInt("height")
            val model = value.getString("model")
            require(width in 1..MAX_IMAGE_EDGE && height in 1..MAX_IMAGE_EDGE && model.length in 1..160)
            return GeneratedImage(slot, alt, aspect, asset, width, height, model,
                value.optDouble("cost").takeIf(Double::isFinite), value.optString("provider").takeIf(String::isNotEmpty),
                value.optString("receipt").takeIf(String::isNotEmpty))
        }
        fun list(value: JSONArray?): List<GeneratedImage> {
            if (value == null) return emptyList()
            require(value.length() <= 32)
            return List(value.length()) { read(value.getJSONObject(it)) }.also {
                require(it.distinctBy { image -> image.slot }.size == it.size)
            }
        }
    }
}

private const val RATIO_TOLERANCE = .08
private const val MAX_IMAGE_BYTES = 10_485_760
private const val MAX_IMAGE_EDGE = 4096
private val imageTypes = setOf("image/png", "image/jpeg", "image/webp")
internal data class ProviderImage(val bytes: ByteArray, val mime: String? = null, val cost: Double? = null, val receipt: String? = null)
internal fun interface ImageProvider {
    suspend fun generate(key: String, model: ImageModel, intent: ImageIntent): ProviderImage
}

internal class ImageGeneration(
    private val context: Context,
    val settings: ImageSettings = ImageSettings(context),
    private val providers: Map<String, ImageProvider> = imageProviders(Endpoints.PRODUCTION),
) {
    private val assets = ThreeSceneAssets(context)
    private val cleanup = ThreeSceneAssetCleanup(context)
    fun lease(): java.io.Closeable {
        val lease = cleanup.lease()
        return java.io.Closeable { cleanup.request(); lease.close() }
    }

    fun ensureCurrent(selection: ImageSettings.Selection) {
        if (!settings.isCurrent(selection)) throw ImageFailure(context.getString(R.string.image_disabled))
    }

    suspend fun generate(operation: String, intents: List<ImageIntent>, selection: ImageSettings.Selection, limit: Int): List<GeneratedImage> =
        coroutineScope {
            require(intents.size in 1..limit && limit in 1..4 && intents.distinctBy { it.slot }.size == intents.size)
            ensureCurrent(selection)
            val work = async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                withTimeoutOrNull(if (limit == 1) 125_000L else 240_000L) {
                    val provider = providers.getValue(selection.model.provider.id)
                    val key = settings.key(selection)
                    ensureCurrent(selection)
                    currentCoroutineContext().ensureActive()
                    reserve(operation, intents)
                    intents.map { intent ->
                        ensureCurrent(selection)
                        currentCoroutineContext().ensureActive()
                        val output = try {
                            withTimeoutOrNull(120_000L) { provider.generate(key, selection.model, intent) }
                                ?: throw unknownOutcome()
                        }
                        catch (failure: CloudFailure) {
                            // The provider answered with an error, so the outcome is known.
                            currentCoroutineContext().ensureActive()
                            throw ImageFailure(failure.message)
                        } catch (failure: IOException) {
                            currentCoroutineContext().ensureActive()
                            throw unknownOutcome()
                        } catch (failure: IllegalArgumentException) {
                            throw ImageFailure(context.getString(R.string.image_invalid))
                        }
                        currentCoroutineContext().ensureActive()
                        ensureCurrent(selection)
                        if (output.bytes.size !in 1..MAX_IMAGE_BYTES) throw ImageFailure(context.getString(R.string.image_invalid))
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(output.bytes, 0, output.bytes.size, bounds)
                        val width = bounds.outWidth
                        val height = bounds.outHeight
                        val mime = bounds.outMimeType
                        if (width !in 1..MAX_IMAGE_EDGE || height !in 1..MAX_IMAGE_EDGE ||
                            mime !in imageTypes || output.mime != null && output.mime != mime ||
                            kotlin.math.abs(width.toDouble() / height - intent.aspect.ratio) > RATIO_TOLERANCE) {
                            throw ImageFailure(context.getString(R.string.image_invalid))
                        }
                        val decoded = BitmapFactory.decodeByteArray(output.bytes, 0, output.bytes.size,
                            BitmapFactory.Options().apply { inSampleSize = 4 })
                            ?: throw ImageFailure(context.getString(R.string.image_invalid))
                        decoded.recycle()
                        val asset = assets.importGenerated(output.bytes, intent.slot, checkNotNull(mime))
                        currentCoroutineContext().ensureActive()
                        ensureCurrent(selection)
                        GeneratedImage(intent.slot, intent.alt, intent.aspect, asset, width, height, selection.model.wire,
                            output.cost, selection.model.provider.id, output.receipt)
                    }
                } ?: throw unknownOutcome()
            }
            settings.observeAccess { if (!settings.isCurrent(selection)) work.cancel() }.use {
                try { ensureCurrent(selection); work.await() }
                catch (cancel: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    ensureCurrent(selection)
                    throw cancel
                } finally { work.cancel() }
            }
        }

    private fun unknownOutcome() = ImageFailure(context.getString(R.string.image_unknown), unknownOutcome = true)

    private fun reserve(operation: String, intents: List<ImageIntent>) {
        require(operation.length in 1..300)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("alpha-image-operations.db"), null).use { database ->
            database.execSQL("CREATE TABLE IF NOT EXISTS submissions(operation TEXT, slot TEXT, submitted INTEGER, PRIMARY KEY(operation, slot))")
            database.beginTransaction()
            try {
                val duplicate = database.rawQuery("SELECT 1 FROM submissions WHERE operation=? LIMIT 1", arrayOf(operation))
                    .use { it.moveToFirst() }
                if (duplicate) throw ImageFailure(context.getString(R.string.image_duplicate))
                val now = System.currentTimeMillis()
                for (intent in intents) database.insertOrThrow("submissions", null, android.content.ContentValues().apply {
                    put("operation", operation)
                    put("slot", intent.slot)
                    put("submitted", now)
                })
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }
    }
}

internal class ImageFailure(message: String, val unknownOutcome: Boolean = false) : RuntimeException(message)

internal fun imageProviders(endpoints: Endpoints): Map<String, ImageProvider> =
    mapOf(AiProvider.OPENROUTER.id to OpenRouterImages(endpoints), AiProvider.OPENAI.id to OpenAiImages(endpoints))

private const val MAX_IMAGE_RESPONSE = 16 * 1024 * 1024

/** The read timeout stays below the 120 second limit of one image in [ImageGeneration]. */
private val imageHttp = CloudHttp(null, 110_000)

/**
 * Sends one image request and gives [read] the JSON reply and a reader of the response headers. A canceled
 * coroutine disconnects only this request. An HTTP error becomes the [providerFailure] of [provider]. An invalid
 * reply becomes an [IllegalArgumentException].
 */
private suspend fun <T> imageReply(provider: AiProvider, endpoints: Endpoints, path: String, key: String, body: JSONObject,
    read: (JSONObject, (String) -> String?) -> T): T =
    imageHttp.reply(provider, endpoints.checked(provider) + path, bearerHeaders(key) + ("Accept" to "application/json"),
        "application/json", body.utf8(), MAX_IMAGE_RESPONSE, CloudSession()) { bytes, header ->
        require(bytes != null)
        val reply = try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (_: org.json.JSONException) { throw IllegalArgumentException("Invalid image response") }
        try { read(reply, header) } catch (_: org.json.JSONException) { throw IllegalArgumentException("Invalid image response") }
    }

/** The one image of `data[0].b64_json` in [reply]. */
private fun JSONObject.firstImage(): ByteArray {
    val data = getJSONArray("data")
    require(data.length() == 1)
    return Base64.decode(data.getJSONObject(0).getString("b64_json"), Base64.DEFAULT)
}

private val RECEIPT = Regex("[A-Za-z0-9_.:-]{1,160}")

internal class OpenRouterImages(private val endpoints: Endpoints) : ImageProvider {
    override suspend fun generate(key: String, model: ImageModel, intent: ImageIntent): ProviderImage {
        val body = JSONObject().put("model", model.wire).put("prompt", intent.prompt)
            .put("resolution", if (model == ImageModel.SEEDREAM) "2K" else "1K").put("aspect_ratio", intent.aspect.wire).put("n", 1)
            .put("provider", JSONObject().put("only", JSONArray().put(checkNotNull(model.route)))
                .put("allow_fallbacks", false))
        return imageReply(AiProvider.OPENROUTER, endpoints, "/api/v1/images", key, body) { json, header ->
            ProviderImage(json.firstImage(), json.getJSONArray("data").getJSONObject(0).optString("media_type").takeIf(String::isNotBlank),
                json.optJSONObject("usage")?.optDouble("cost")?.takeIf(Double::isFinite),
                sequenceOf(json.optString("id"), header("x-generation-id"), header("x-request-id"))
                    .filterNotNull().firstOrNull { RECEIPT.matches(it) }
                    ?: json.optLong("created").takeIf { time -> time > 0 }?.toString())
        }
    }
}

/**
 * OpenAI Images API. The size list comes from the OpenAI image generation guide, checked on 2026-11-16
 * (https://developers.openai.com/api/docs/guides/image-generation): the recommended sizes are 1024x1024, 1536x1024,
 * and 1024x1536. The guide gives no 2:1 size for the 2.6 models, so a 2:1 image is a 1536x1024 image with its center
 * cut to 1536x768 (spec 11.6). shortcut: the guide allows custom sizes such as 1536x864 for the 2.5 models only,
 * so request 1536x768 directly when the guide lists custom sizes for the 2.6 models.
 */
internal class OpenAiImages(private val endpoints: Endpoints) : ImageProvider {
    override suspend fun generate(key: String, model: ImageModel, intent: ImageIntent): ProviderImage {
        val size = if (intent.aspect == ImageAspect.SQUARE) "1024x1024" else "1536x1024"
        val body = JSONObject().put("model", model.wire).put("prompt", intent.prompt).put("size", size).put("n", 1)
        return imageReply(AiProvider.OPENAI, endpoints, AI_IMAGES_PATH, key, body) { json, header ->
            ProviderImage(cropToAspect(json.firstImage(), intent.aspect), receipt = header("x-request-id")?.takeIf { RECEIPT.matches(it) }
                ?: json.optLong("created").takeIf { time -> time > 0 }?.toString())
        }
    }

    private companion object { const val AI_IMAGES_PATH = "/v1/images/generations" }
}

/**
 * Cuts the center of [bytes] to the ratio of [aspect] when the image is off that ratio by more than the check in
 * [ImageGeneration] allows. The output keeps the format of the input. Bytes that are not an image stay unchanged.
 */
internal fun cropToAspect(bytes: ByteArray, aspect: ImageAspect): ByteArray {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val target = aspect.ratio
    // An image over a limit stays unchanged, so the check in generate rejects it before any full decode.
    if (bytes.size > MAX_IMAGE_BYTES || bounds.outWidth !in 1..MAX_IMAGE_EDGE || bounds.outHeight !in 1..MAX_IMAGE_EDGE ||
        kotlin.math.abs(bounds.outWidth.toDouble() / bounds.outHeight - target) <= RATIO_TOLERANCE) return bytes
    val format = when (bounds.outMimeType) {
        "image/png" -> Bitmap.CompressFormat.PNG
        "image/jpeg" -> Bitmap.CompressFormat.JPEG
        "image/webp" -> Bitmap.CompressFormat.WEBP_LOSSLESS
        else -> return bytes
    }
    val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
    var cut: Bitmap? = null
    try {
        val width = if (source.width.toDouble() / source.height > target) (source.height * target).toInt() else source.width
        val height = if (source.width.toDouble() / source.height > target) source.height else (source.width / target).toInt()
        cut = Bitmap.createBitmap(source, (source.width - width) / 2, (source.height - height) / 2, width, height)
        return java.io.ByteArrayOutputStream().also { check(cut.compress(format, 95, it)) }.toByteArray()
    } finally {
        if (cut !== source) cut?.recycle()
        source.recycle()
    }
}
