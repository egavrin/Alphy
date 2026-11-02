package com.alpha.assistant.surfaces

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import org.json.JSONArray
import org.json.JSONObject

data class ReviewedCalendarEvent(val key: String, val title: String, val begin: Long, val end: Long, val location: String)

internal object ReviewedCalendar {
    fun calendars(context: Context): List<Pair<Long, String>> {
        require(context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED)
        return context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME),
            null, null, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)?.use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getLong(0) to cursor.getString(1).orEmpty()) }
        } ?: error("Календари недоступны")
    }
    fun instances(context: Context, calendar: Long, from: Long, days: Int): Pair<List<ReviewedCalendarEvent>, Boolean> {
        require(context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED)
        val range = AssistContext.calendarWindow(from, days)
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().appendPath(range.first.toString())
            .appendPath((range.last + 1).toString()).build()
        return context.contentResolver.query(uri,
            arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END, CalendarContract.Instances.EVENT_LOCATION),
            "${CalendarContract.Instances.CALENDAR_ID}=?", arrayOf(calendar.toString()), "${CalendarContract.Instances.BEGIN} ASC")?.use { cursor ->
            var shortened = false
            val events = buildList {
                while (size < 51 && cursor.moveToNext()) {
                    val begin = cursor.getLong(2)
                    val title = cursor.getString(1).orEmpty().ifBlank { "Без названия" }
                    val location = cursor.getString(4).orEmpty()
                    if (title.length > 1000 || location.length > 1000) shortened = true
                    add(ReviewedCalendarEvent("${cursor.getLong(0)}:$begin", title.take(1000), begin, cursor.getLong(3), location.take(1000)))
                }
            }
            events.take(50) to (events.size > 50 || shortened)
        } ?: error("События недоступны")
    }
    fun encode(events: List<ReviewedCalendarEvent>) = JSONArray().apply { events.forEach {
        put(JSONObject().put("key", it.key).put("title", it.title).put("begin", it.begin).put("end", it.end).put("location", it.location))
    } }.toString()
    fun decode(json: String): List<ReviewedCalendarEvent> = JSONArray(json).let { array ->
        List(array.length()) { index -> array.getJSONObject(index).let {
            ReviewedCalendarEvent(it.getString("key"), it.getString("title"), it.getLong("begin"), it.getLong("end"), it.getString("location"))
        } }
    }
}

class ChatCameraProvider : androidx.core.content.FileProvider()
