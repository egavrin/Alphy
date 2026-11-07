package com.alpha.assistant.timer

import com.alpha.assistant.capabilities.CapabilityRequest
import org.json.JSONObject

internal data class ForegroundTimerSpec(val id: String, val durationMillis: Long, val alert: ForegroundTimerAlert) {
    fun requests(): List<CapabilityRequest> = buildList {
        alert.vibrationMillis?.let {
            add(CapabilityRequest(id, "vibration.play", JSONObject().put("milliseconds", it)))
        }
        alert.notification?.let {
            add(CapabilityRequest(id, "notification.post", JSONObject().put("title", it.title).put("text", it.text)))
        }
        alert.toneMillis?.let {
            add(CapabilityRequest(id, "audio.tone", JSONObject().put("tone", "beep").put("durationMillis", it)))
        }
    }

    companion object {
        fun parse(node: JSONObject): ForegroundTimerSpec {
            require(node.getString("type") == "timer" && node.getString("mode") == "countdown" && !node.has("bind"))
            val id = requireNotNull(node.get("action") as? String)
            require(id.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,47}")))
            val input = node.getJSONObject("foreground")
            require(input.keys().asSequence().all { it in setOf("durationMs", "vibrationMs", "notification", "toneMs") })
            val duration = input.integer("durationMs").also { require(it in 1_000L..86_400_000L) }
            val vibration = if (input.has("vibrationMs")) input.integer("vibrationMs") else null
            val notification = if (input.has("notification")) input.getJSONObject("notification").let {
                require(it.keys().asSequence().toSet() == setOf("title", "text"))
                ForegroundTimerAlert.Notification(requireNotNull(it.get("title") as? String), requireNotNull(it.get("text") as? String))
            } else null
            val tone = if (input.has("toneMs")) input.integer("toneMs").also { require(it in 1..1000L) }.toInt() else null
            return ForegroundTimerSpec(id, duration, ForegroundTimerAlert(vibration, notification, tone))
        }

        private fun JSONObject.integer(key: String): Long {
            val value = get(key)
            require(value is Int || value is Long)
            return (value as Number).toLong()
        }
    }
}
