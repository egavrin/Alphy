package com.alpha.assistant.capabilities

import android.Manifest
import org.json.JSONArray
import org.json.JSONObject

internal data class PhoneCapability(
    val id: String,
    val description: String,
    val parameters: Map<String, CapabilityArgument>,
    val permissions: List<String> = emptyList(),
    val handoff: Boolean = false,
) {
    val argumentSchema get() = capabilityArgumentSchema(parameters)

    fun validate(args: JSONObject) {
        validateCapabilityArguments(parameters, args)
        for ((name, parameter) in parameters) {
            if (parameter.type == "integer" && args.has(name)) require(args.get(name) is Int || args.get(name) is Long)
        }
        when (id) {
            "calendar.read", "calendar.updateEvent" -> require(args.getLong("to") - args.getLong("from") in 1..MAX_RANGE)
            "calendar.createOwned" -> {
                java.time.ZoneId.of(args.getString("timeZone"))
                require(args.getLong("end") - args.getLong("start") in 1..MAX_RANGE)
            }
        }
    }

    fun json() = JSONObject().put("id", id).put("title", id).put("description", description)
        .put("implementation", "implemented").put("argumentSchema", argumentSchema)
        .put("access", JSONObject().put("manifestPermissions", JSONArray(permissions)).put("foreground", true)
            .put("confirmation", true).put("activityHandler", if (handoff) "checkedAtExecution" else "notRequired"))
        .put("result", JSONObject().put("primaryStatus", if (handoff) "launched" else "succeeded")
            .put("description", "Local phone-plan receipt."))

    private companion object { const val MAX_RANGE = 31L * 86_400_000 }
}

/** Phone contracts whose arguments or authority differ from the mini-app effect contract. */
internal object PhoneCapabilities {
    private val text = CapabilityArgument("string", maxLength = 200, format = "control-free")
    private val optionalText = text.copy(optional = true)
    private fun number(minimum: Long, maximum: Long) = CapabilityArgument("integer", minimum = minimum, maximum = maximum)
    private val date = number(1, 253402300799000L)
    private val reminder = number(0, 10080)
    private val calendarRead = listOf(Manifest.permission.READ_CALENDAR)
    private val calendarWrite = calendarRead + Manifest.permission.WRITE_CALENDAR

    val definitions = listOf(
        PhoneCapability("calendar.read", "Show the appointments, meetings, or events already on the user’s calendar for a day or date range. Use from and to epoch milliseconds, optional calendar name. Reads stay local.",
            mapOf("from" to date, "to" to date, "calendar" to optionalText), permissions = calendarRead),
        PhoneCapability("calendar.updateEvent", "Reschedule a calendar event or change its reminder. Use event title, from and to epoch milliseconds, new start epoch milliseconds, optional reminderMinutes (0 to 10080). Preserve duration.",
            mapOf("title" to text, "from" to date, "to" to date, "start" to date, "reminderMinutes" to reminder.copy(optional = true)), permissions = calendarWrite),
        PhoneCapability("calendar.select", "Select the named writable calendar for a later operation. Use calendar name.",
            mapOf("calendar" to text), permissions = calendarWrite),
        PhoneCapability("calendar.createOwned", "Add a one-time calendar event, appointment, or visit to the user’s schedule. Use title, start and end epoch milliseconds, timeZone, reminderMinutes, optional calendar name, location and repeatDaily. Omit calendar only to use the previously selected calendar.",
            mapOf("title" to text, "start" to date, "end" to date, "timeZone" to text, "reminderMinutes" to reminder, "calendar" to optionalText, "location" to optionalText, "repeatDaily" to CapabilityArgument("boolean", optional = true)), permissions = calendarWrite),
        PhoneCapability("calendar.readOwned", "Read an event previously created by this agent. Use event title.",
            mapOf("title" to text), permissions = calendarRead),
        PhoneCapability("calendar.deleteOwned", "Delete one event previously created by this agent. Use event title.",
            mapOf("title" to text), permissions = calendarWrite),
        PhoneCapability("calendar.deleteAllOwned", "No arguments. Delete the exact reviewed set of events previously created by this agent.",
            mapOf(), permissions = calendarWrite),
        PhoneCapability("calendar.open", "No arguments. Open Android Calendar.",
            mapOf(), handoff = true),
        PhoneCapability("maps.route", "Open navigation or directions between two places. Use from and to strings supplied by the user.",
            mapOf("from" to text, "to" to text), handoff = true),
        PhoneCapability("clock.setAlarm", "Set an alarm in the Android clock at a specified time of day. Use hour (0 to 23), minute (0 to 59), optional label.",
            mapOf("hour" to number(0, 23), "minute" to number(0, 59), "label" to optionalText), handoff = true),
        PhoneCapability("clock.setTimer", "Start a countdown timer in the Android clock. Use seconds (1 to 86400), optional label.",
            mapOf("seconds" to number(1, 86400), "label" to optionalText), handoff = true),
        PhoneCapability("memory.read", "Read or recall a saved preference, memory, or personal fact from Alphy. Use match as a description of the fact.",
            mapOf("match" to text)),
        PhoneCapability("memory.write", "Update a saved memory entry, preference, home address, or personal detail. Remember a new fact on request. Use text and optional match as a description of the existing fact.",
            mapOf("text" to text.copy(maxLength = 1000), "match" to optionalText)),
        PhoneCapability("artifact.setState", "Change one saved field in a named Alphy mini-app. Use app title, field name, and value.",
            mapOf("app" to text, "field" to text.copy(maxLength = 64), "value" to CapabilityArgument("scalar"))),
        PhoneCapability("automation.create", "Create a new automation rule and save it disabled for review. Use a definition object with name, trigger, conditions and actions.",
            mapOf("definition" to CapabilityArgument("object"))),
        PhoneCapability("automation.pause", "Pause or disable an existing automation rule. Use its name.",
            mapOf("name" to text)),
        PhoneCapability("automation.resume", "Resume a paused automation, re-enable a recurring scenario, or turn an existing automatic workflow back on. Use its name.",
            mapOf("name" to text)),
        PhoneCapability("music.playSearch", "Ask a compatible Android music player to play a song, artist, or album from an explicit search query. Use query. Playback is unverified.",
            mapOf("query" to text), handoff = true),
    ).associateBy(PhoneCapability::id)
}
