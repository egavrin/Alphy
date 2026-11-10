package com.alpha.assistant.images

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.alpha.assistant.R
import com.alpha.assistant.data.SecureKeyStore
import com.alpha.assistant.tools.three.ThreeSceneAssetCleanup
import com.alpha.assistant.tools.three.ThreeSceneAssetRef
import com.alpha.assistant.tools.three.ThreeSceneAssets
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

enum class ImageAspect(val wire: String) {
    SQUARE("1:1"), LANDSCAPE("3:2"), EQUIRECTANGULAR("2:1");
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
                val normalize = com.alpha.assistant.tools.generation.SceneCheckEvidence.Companion::normalizedExcerpt
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

internal enum class ImageModel(val wire: String, val title: String, val providerId: String, val route: String) {
    QWEN("qwen/qwen-image-2.1-turbo", "Qwen Image 2.1 Turbo", "openrouter", "alibaba"),
    SEEDREAM("bytedance-seed/seedream-4.5", "Seedream 4.5", "openrouter", "seed");
}

internal class ImageSettings(context: Context, name: String = "alpha_images") {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val keys = SecureKeyStore(context, "${name}_credentials", "alpha.${name}.openrouter.v1")
    data class Selection(val model: ImageModel, val consent: String, val access: Long)
    val model: ImageModel get() = ImageModel.entries.firstOrNull { it.name == prefs.getString("model", null) } ?: ImageModel.QWEN
    val enabled get() = prefs.getBoolean("enabled", false)
    val hasKey get() = keys.consented()
    private val access get() = prefs.getLong("access", 0)
    fun selection(): Selection? = if (enabled) keys.consentIdentity()?.let { Selection(model, it, access) } else null
    fun isCurrent(selection: Selection) = enabled && access == selection.access && keys.consentIdentity() == selection.consent
    fun key(selection: Selection): String {
        check(isCurrent(selection))
        return checkNotNull(keys.read().getOrThrow()).also { require(it.none { c -> c.isWhitespace() || c.isISOControl() }) }
    }
    fun saveKey(value: String): Result<Unit> = runCatching {
        val key = value.trim()
        require(key.length in 1..8192 && key.none { it.isWhitespace() || it.isISOControl() })
        keys.save(key).getOrThrow()
    }
    fun clearKey() = runCatching {
        setEnabled(false).getOrThrow()
        keys.clear().getOrThrow()
    }
    fun setEnabled(value: Boolean): Result<Unit> = runCatching {
        check(!value || hasKey)
        check(prefs.edit().putBoolean("enabled", value).putLong("access", access + 1).commit())
    }
    fun select(value: ImageModel) { prefs.edit().putString("model", value.name).apply() }
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
            require(asset.id.matches(Regex("[0-9a-f]{64}")) && asset.size in 1..10_485_760 &&
                asset.mime in imageTypes && asset.name.length in 1..120)
            val slot = value.getString("slot")
            val alt = value.getString("alt")
            val aspect = ImageAspect.read(value.getString("aspect"))
            ImageIntent(slot, "Stored image", alt, aspect)
            val width = value.getInt("width")
            val height = value.getInt("height")
            val model = value.getString("model")
            require(width in 1..4096 && height in 1..4096 && model.length in 1..160)
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

private val imageTypes = setOf("image/png", "image/jpeg", "image/webp")
internal data class ProviderImage(val bytes: ByteArray, val mime: String? = null, val cost: Double? = null, val receipt: String? = null)
internal fun interface ImageProvider {
    suspend fun generate(key: String, model: ImageModel, intent: ImageIntent): ProviderImage
}

internal class ImageGeneration(
    private val context: Context,
    val settings: ImageSettings = ImageSettings(context),
    private val providers: Map<String, ImageProvider> = mapOf("openrouter" to OpenRouterImages),
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
                    val provider = providers.getValue(selection.model.providerId)
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
                        catch (failure: IOException) {
                            currentCoroutineContext().ensureActive()
                            throw unknownOutcome()
                        } catch (failure: IllegalArgumentException) {
                            throw ImageFailure(context.getString(R.string.image_invalid))
                        }
                        currentCoroutineContext().ensureActive()
                        ensureCurrent(selection)
                        if (output.bytes.size !in 1..10_485_760) throw ImageFailure(context.getString(R.string.image_invalid))
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(output.bytes, 0, output.bytes.size, bounds)
                        val width = bounds.outWidth
                        val height = bounds.outHeight
                        val mime = bounds.outMimeType
                        val ratio = when (intent.aspect) {
                            ImageAspect.SQUARE -> 1.0
                            ImageAspect.LANDSCAPE -> 1.5
                            ImageAspect.EQUIRECTANGULAR -> 2.0
                        }
                        if (width !in 1..4096 || height !in 1..4096 ||
                            mime !in imageTypes || output.mime != null && output.mime != mime ||
                            kotlin.math.abs(width.toDouble() / height - ratio) > .08) {
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
                            output.cost, selection.model.providerId, output.receipt)
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

private object OpenRouterImages : ImageProvider {
    private val client = OkHttpClient.Builder().callTimeout(120, TimeUnit.SECONDS).readTimeout(110, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()

    override suspend fun generate(key: String, model: ImageModel, intent: ImageIntent): ProviderImage =
        suspendCancellableCoroutine { continuation ->
            val body = JSONObject().put("model", model.wire).put("prompt", intent.prompt)
                .put("resolution", if (model == ImageModel.SEEDREAM) "2K" else "1K").put("aspect_ratio", intent.aspect.wire).put("n", 1)
                .put("provider", JSONObject().put("only", JSONArray().put(model.route)).put("allow_fallbacks", false))
                .toString()
            val call = client.newCall(Request.Builder().url("https://openrouter.ai/api/v1/images")
                .header("Authorization", "Bearer $key").post(body.toRequestBody("application/json".toMediaType())).build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = continuation.resumeWith(Result.failure(e))
                override fun onResponse(call: Call, response: Response) {
                    continuation.resumeWith(runCatching {
                        response.use {
                            if (!it.isSuccessful) throw ImageFailure("OpenRouter returned HTTP ${it.code}.")
                            val source = checkNotNull(it.body).source()
                            require(!source.request(16L * 1024 * 1024 + 1))
                            val json = JSONObject(source.readUtf8())
                            val data = json.getJSONArray("data")
                            require(data.length() == 1)
                            val item = data.getJSONObject(0)
                            ProviderImage(Base64.decode(item.getString("b64_json"), Base64.DEFAULT),
                                item.optString("media_type").takeIf(String::isNotBlank),
                                json.optJSONObject("usage")?.optDouble("cost")?.takeIf(Double::isFinite),
                                sequenceOf(json.optString("id"), it.header("x-generation-id"), it.header("x-request-id"))
                                    .filterNotNull().firstOrNull { id -> id.matches(Regex("[A-Za-z0-9_.:-]{1,160}")) }
                                    ?: json.optLong("created").takeIf { time -> time > 0 }?.toString())
                        }
                    }.recoverCatching { failure ->
                        if (failure is org.json.JSONException) throw IllegalArgumentException("Invalid image response")
                        throw failure
                    })
                }
            })
        }
}
