package com.alpha.assistant.capabilities

import com.alpha.assistant.media.MAX_MEDIA_BYTES
import org.json.JSONArray
import org.json.JSONObject

/** Only these typed values cross from native results into artifact JavaScript. */
object CapabilityResultEvent {
    const val MAX_DATA_BYTES = 8192
    // Reserve 48 KiB for state and 1 KiB for the envelope inside the 64 KiB sandbox input.
    const val MAX_EVENT_BYTES = 15360
    const val BATCH_TOO_LARGE = "resultBatchTooLarge"
    private fun keys(json: JSONObject) = json.keys().asSequence().toSet()
    fun data(capability: String, result: CapabilityResult): JSONObject {
        require(capability in CapabilityCatalog.ids && result.status != "awaitingUser")
        if(capability in DeviceReadContract.ids) require(result.status in DeviceReadContract.statuses)
        if(capability in AudioContract.playbackIds) require(result.status in AudioContract.playbackStatuses)
        if(capability==VideoContract.PLAY) require(result.status in VideoContract.statuses)
        val data = JSONObject(result.data.toString())
        require(data.toString().toByteArray().size <= MAX_DATA_BYTES)
        if(capability==TorchSequence.ID) {
            TorchSequence.validateReceipt(result)
            return data
        }
        if(capability==NetworkEffectContract.ID) {
            NetworkEffectContract.validateResult(result.status,data)
            return data
        }
        if(capability in MedicineContract.ids && !(result.status=="failed" && data.has("code"))) {
            MedicineContract.validateResult(capability,result)
            return data
        }
        if(capability in OwnedCalendarContract.ids && !(result.status=="failed" && data.has("code"))) {
            OwnedCalendarContract.validateResult(capability,result)
            return data
        }
        if(capability in NavigationContract.ids && result.status == (if(capability == "maps.route") "launched" else "succeeded")) {
            NavigationContract.validateResult(capability,data)
            return data
        }
        if(capability in NavigationContract.ids) require(result.status !in setOf("succeeded","launched"))
        if(result.status != "succeeded") {
            if(data.length() != 0) {
                require(result.status == "failed" && keys(data) == setOf("code","nativeStatus"))
                require(data.getString("code") == BATCH_TOO_LARGE && data.getString("nativeStatus") in CapabilityResult.statuses - "awaitingUser")
            }
            return data
        }
        when(capability) {
            TiltContract.ID -> require(keys(data) == setOf("active") && data.get("active") is Boolean)
            in DeviceReadContract.ids -> DeviceReadContract.validate(capability,data)
            MiniAppAi.ID -> {
                require(keys(data) == setOf("text"))
                val answer = data.get("text")
                require(answer is String && answer.isNotBlank() && answer.length <= MiniAppAi.MAX_ANSWER && '\u0000' !in answer)
            }
            "calendar.read" -> {
                require(keys(data) == setOf("events","limit") && data.get("limit") == 50)
                val events = data.getJSONArray("events")
                require(events.length() <= 50)
                repeat(events.length()) {
                    val event=events.getJSONObject(it)
                    require(keys(event) == setOf("title","start","end"))
                    require(event.get("title") is String && event.getString("title").length <= 500)
                    for(key in listOf("start","end")) {
                        val value=event.get(key)
                        require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toLong().toDouble() && value.toLong() in 0..4102444800000L)
                    }
                    require(event.getLong("end") >= event.getLong("start"))
                }
            }
            "photo.pick", "file.pick", "camera.capture", AudioContract.PICK, VideoContract.PICK, VideoContract.CAPTURE -> {
                require(keys(data) == setOf("resourceId","kind","mimeType","sizeBytes"))
                require(data.getString("resourceId").matches(Regex("[0-9a-f]{64}")))
                val kind=checkNotNull(CapabilityResources.resourceKind(capability))
                require(data.getString("kind") == kind)
                require(data.get("mimeType") is String && data.getString("mimeType").matches(Regex("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+")) && data.getString("mimeType").length <= 100)
                val size=data.get("sizeBytes")
                val sizes = when(capability) {
                    AudioContract.PICK -> {require(AudioContract.isAudioMime(data.getString("mimeType"))); 1..MAX_MEDIA_BYTES}
                    VideoContract.PICK, VideoContract.CAPTURE -> {require(VideoContract.isVideoMime(data.getString("mimeType"))); 1..MAX_MEDIA_BYTES}
                    else -> 0..CapabilityResources.MAX_FILE_BYTES
                }
                require(size is Number && size.toDouble() == size.toLong().toDouble() && size.toLong() in sizes)
            }
            else -> { require(capability in CapabilityCatalog.ids); require(data.length() == 0) }
        }
        return data
    }
    fun event(id: String, effects: List<CapabilityEffect>): JSONObject {
        require(effects.size in 1..3)
        val event=JSONObject().put("type","capabilityResults").put("deliveryId",id).put("entries",JSONArray().apply {
            effects.forEach { effect -> put(JSONObject().put("requestId",effect.request.id).put("capability",effect.request.capability)
                .put("status",effect.result.status).put("data",data(effect.request.capability,effect.result))) }
        })
        if(event.toString().toByteArray().size > MAX_EVENT_BYTES) {
            // Keep the original native outcomes in SQLite. Report the delivery failure without authorizing effect replay.
            event.put("entries",JSONArray().apply {
                effects.forEach { effect -> put(JSONObject().put("requestId",effect.request.id).put("capability",effect.request.capability)
                    .put("status","failed").put("data",JSONObject().put("code",BATCH_TOO_LARGE).put("nativeStatus",effect.result.status))) }
            })
        }
        require(event.toString().toByteArray().size <= MAX_EVENT_BYTES)
        return event
    }
}

data class CapabilityDelivery(val id: String, val owner: String, val revision: Int, val stateVersion: Int,
    val phase: String, val attempts: Int, val event: String?)

/** Locators and grant ownership exist only in native SQLite records. */
data class CapabilityResource(val id: String, val owner: String, val kind: String, val locator: String,
    val mimeType: String, val sizeBytes: Long, val persistent: Boolean, val ownsGrant: Boolean,
    val session: String, val expiresAt: Long) {
    fun metadata() = JSONObject().put("resourceId",id).put("kind",kind).put("mimeType",mimeType).put("sizeBytes",sizeBytes)
}
