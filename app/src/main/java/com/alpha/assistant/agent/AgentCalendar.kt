package com.alpha.assistant.agent

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import com.alpha.assistant.R
import com.alpha.assistant.capabilities.AndroidOwnedCalendarProvider
import com.alpha.assistant.capabilities.CapabilityResult
import com.alpha.assistant.capabilities.NativeOwnedEvent
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Calendar targets and attendee information never enter a provider prompt. */
internal class AgentCalendar(private val context: Context, name: String) {
    private val resolver get() = context.contentResolver
    private val owned = AndroidOwnedCalendarProvider(context)
    private val selection = context.getSharedPreferences("$name-calendar", Context.MODE_PRIVATE)

    private data class Calendar(val id: Long, val name: String, val account: String, val owner: String, val access: Int,
        val reminderMethods: String, val maximumReminders: Int) {
        val writable get() = access >= Calendars.CAL_ACCESS_CONTRIBUTOR
        fun snapshot() = ContentValues().apply {
            put(Calendars._ID, id); put(Calendars.CALENDAR_DISPLAY_NAME, name); put(Calendars.ACCOUNT_NAME, account)
            put(Calendars.OWNER_ACCOUNT, owner); put(Calendars.CALENDAR_ACCESS_LEVEL, access)
            put(Calendars.ALLOWED_REMINDERS, reminderMethods); put(Calendars.MAX_REMINDERS, maximumReminders)
        }
    }

    private data class Instance(val event: Long, val calendar: Long, val title: String, val begin: Long, val end: Long) {
        fun description() = "$title · ${time(begin)} – ${time(end)}"
    }

    private data class Event(val id: Long, val calendar: Long, val title: String, val start: Long, val end: Long?,
        val zone: String?, val organizer: String?, val allDay: Int, val rule: String?, val dates: String?,
        val duration: String?, val originalId: Long?, val originalTime: Long?) {
        val recurring get() = !rule.isNullOrBlank() || !dates.isNullOrBlank()
        fun snapshot() = ContentValues().apply {
            put(Events._ID, id); put(Events.CALENDAR_ID, calendar); put(Events.TITLE, title); put(Events.DTSTART, start)
            put(Events.DTEND, end); put(Events.EVENT_TIMEZONE, zone); put(Events.ORGANIZER, organizer); put(Events.ALL_DAY, allDay)
            put(Events.RRULE, rule); put(Events.RDATE, dates); put(Events.DURATION, duration); put(Events.ORIGINAL_ID, originalId)
            put(Events.ORIGINAL_INSTANCE_TIME, originalTime); put(Events.DELETED, 0)
        }
    }

    private data class Attendee(val id: Long, val name: String?, val address: String?)
    private data class Reminder(val id: Long, val minutes: Int, val method: Int)
    private data class Move(val instance: Instance, val event: Event, val calendar: Calendar,
        val attendees: List<Attendee>, val reminders: List<Reminder>, val unavailable: Int? = null) {
        fun identity() = JSONObject().put("event", event.id).put("begin", instance.begin).put("end", instance.end)
            .put("snapshot", JSONArray(listOf(event.toString(), calendar.toString(), attendees.toString(), reminders.toString()))).toString()
    }

    fun resolve(function: String, args: JSONObject): List<AgentTarget> = when (function) {
        "calendar.read" -> calendars().filter { it.name.contains(args.optString("calendar"), true) }.map { calendar ->
            val items = instances(args, calendar.id).map(Instance::description)
            AgentTarget(calendar.name, "", items.joinToString("\n").ifEmpty { text(R.string.agent_empty) },
                JSONArray(listOf(calendar.toString(), items)).toString())
        }
        "calendar.updateEvent" -> moves(args).map { move ->
            val attendeeText = move.attendees.filterNot { it.address.equals(move.event.organizer, true) }
                .mapNotNull { it.name?.takeIf(String::isNotBlank) ?: it.address }
            val proposed = buildList {
                add("${time(args.getLong("start"))} – ${time(newEnd(move.instance, args))}")
                if (args.has("reminderMinutes")) add(context.getString(R.string.agent_calendar_alert, args.getInt("reminderMinutes")))
                if (attendeeText.isNotEmpty()) add(context.getString(R.string.agent_calendar_attendees, attendeeText.joinToString()))
            }.joinToString("\n")
            AgentTarget("${move.event.title} · ${move.calendar.name}", move.instance.description(),
                move.unavailable?.let(::text) ?: proposed, move.identity(), available = move.unavailable == null)
        }
        "calendar.select", "calendar.createOwned" -> owned.calendars().filter {
            val match = if (args.has("calendar")) it.name.contains(args.getString("calendar"), true)
                else selection.getString("calendar", null) == JSONArray(listOf(it.id, it.identity)).toString()
            match && (function == "calendar.select" || it.supportsReminder)
        }.map { calendar ->
            val description = if (function == "calendar.select") calendar.name else ownedDescription(args)
            AgentTarget(calendar.name, "", description, JSONArray(listOf(calendar.id, calendar.identity)).toString())
        }
        "calendar.readOwned", "calendar.deleteOwned" -> ownedEvents(args.getString("title")).map { item ->
            AgentTarget(item.details.getString("title"), ownedDescription(item),
                if (function == "calendar.deleteOwned") text(R.string.agent_deleted) else ownedDescription(item), ownedIdentity(item))
        }
        "calendar.deleteAllOwned" -> ownedEvents("").takeIf { it.isNotEmpty() }?.let { items ->
            listOf(AgentTarget(text(R.string.agent_owned_calendar), items.joinToString("\n", transform = ::ownedDescription),
                text(R.string.agent_deleted), JSONArray(items.map(::ownedIdentity)).toString()))
        }.orEmpty()
        else -> emptyList()
    }

    suspend fun apply(function: String, args: JSONObject, target: AgentTarget, active: suspend () -> Boolean): CapabilityResult {
        return when (function) {
            "calendar.read", "calendar.readOwned" -> result("succeeded", target.after)
            "calendar.select" -> {
                if (!active()) return result("cancelled", text(R.string.agent_step_stopped))
                if (selection.edit().putString("calendar", target.identity).commit()) result("succeeded", target.after)
                else result("unknownOutcome", text(R.string.agent_step_unknown))
            }
            "calendar.updateEvent" -> {
                val expected = moves(args).singleOrNull { it.unavailable == null && it.identity() == target.identity }
                if (expected == null) result("cancelled", text(R.string.agent_target_changed)) else move(expected, args, active)
            }
            "calendar.createOwned" -> {
                val key = JSONArray(target.identity)
                val current = owned.calendars().singleOrNull { it.id == key.getLong(0) && it.identity == key.getString(1) && it.supportsReminder }
                if (current == null) result("cancelled", text(R.string.agent_target_changed)) else {
                    val marker = OWNED_PREFIX + UUID.randomUUID()
                    val values = JSONObject(args.toString()).put("location", args.optString("location"))
                    if (!active()) return result("cancelled", text(R.string.agent_step_stopped))
                    val id = owned.insert(current.id, context.packageName, marker, values)
                    val saved = owned.find(current.id, context.packageName, marker).singleOrNull { it.id == id && !it.deleted }
                    val expected = listOf("title", "start", "end", "timeZone", "location", "reminderMinutes")
                    if (saved != null && !saved.details.optBoolean("unsupported") && expected.all { saved.details.get(it).toString() == values.get(it).toString() } &&
                        saved.details.optBoolean("repeatDaily") == values.optBoolean("repeatDaily")) result("succeeded", text(R.string.agent_calendar_saved))
                    else result("unknownOutcome", text(R.string.agent_step_unknown))
                }
            }
            "calendar.deleteOwned", "calendar.deleteAllOwned" -> {
                val items = ownedEvents(args.optString("title"))
                val expected = if (function == "calendar.deleteOwned") items.filter { ownedIdentity(it) == target.identity }
                    else items.takeIf { JSONArray(it.map(::ownedIdentity)).toString() == target.identity }.orEmpty()
                if (expected.isEmpty()) result("cancelled", text(R.string.agent_target_changed)) else deleteOwned(expected, active)
            }
            else -> result("unavailable", text(R.string.agent_unavailable))
        }
    }

    private suspend fun move(expected: Move, args: JSONObject, active: suspend () -> Boolean): CapabilityResult {
        val event = expected.event
        val calendar = expected.calendar
        val start = args.getLong("start")
        val end = newEnd(expected.instance, args)
        val operations = arrayListOf(
            assertRow(Calendars.CONTENT_URI, Calendars._ID, calendar.id, calendar.snapshot()),
            assertRow(Events.CONTENT_URI, Events._ID, event.id, event.snapshot()),
            assertCount(Attendees.CONTENT_URI, Attendees.EVENT_ID, event.id, expected.attendees.size),
            assertCount(Reminders.CONTENT_URI, Reminders.EVENT_ID, event.id, expected.reminders.size),
        )
        for (attendee in expected.attendees) operations += assertRow(Attendees.CONTENT_URI, Attendees._ID, attendee.id,
            ContentValues().apply { put(Attendees.ATTENDEE_NAME, attendee.name); put(Attendees.ATTENDEE_EMAIL, attendee.address) })
        for (reminder in expected.reminders) operations += assertRow(Reminders.CONTENT_URI, Reminders._ID, reminder.id,
            ContentValues().apply { put(Reminders.MINUTES, reminder.minutes); put(Reminders.METHOD, reminder.method) })
        val minutes = args.optInt("reminderMinutes", -1)
        val addAlert = minutes >= 0 && expected.reminders.none { it.minutes == minutes && it.method == Reminders.METHOD_ALERT }
        val moveIndex = operations.size
        val values = ContentValues().apply {
            put(Events.DTSTART, start)
            // The exception URI derives its end from the parent event duration.
            if (!event.recurring) put(Events.DTEND, end)
        }
        if (event.recurring) {
            values.put(Events.ORIGINAL_INSTANCE_TIME, expected.instance.begin)
            operations += ContentProviderOperation.newInsert(ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, event.id))
                .withValues(values).build()
        } else operations += ContentProviderOperation.newUpdate(ContentUris.withAppendedId(Events.CONTENT_URI, event.id))
            .withValues(values).withExpectedCount(1).build()
        if (addAlert) operations += ContentProviderOperation.newInsert(Reminders.CONTENT_URI).apply {
            if (event.recurring) withValueBackReference(Reminders.EVENT_ID, moveIndex) else withValue(Reminders.EVENT_ID, event.id)
        }.withValue(Reminders.MINUTES, minutes).withValue(Reminders.METHOD, Reminders.METHOD_ALERT).build()
        if (!active()) return result("cancelled", text(R.string.agent_step_stopped))
        val applied = resolver.applyBatch(CalendarContract.AUTHORITY, operations)
        val id = if (event.recurring) ContentUris.parseId(checkNotNull(applied[moveIndex].uri)) else event.id
        val changed = event(id)
        val savedAlerts = reminders(id).map { it.minutes to it.method }.sortedBy { it.first * 10 + it.second }
        val expectedAlerts = (expected.reminders.map { it.minutes to it.method } +
            listOfNotNull((minutes to Reminders.METHOD_ALERT).takeIf { addAlert })).sortedBy { it.first * 10 + it.second }
        val retainedAttendees = attendees(id).map { it.name to it.address }.toSet()
        if (changed?.start != start || changed.end != end || changed.calendar != event.calendar || changed.title != event.title ||
            changed.organizer != event.organizer || (event.recurring && (changed.originalId != event.id || changed.originalTime != expected.instance.begin)) ||
            savedAlerts != expectedAlerts || retainedAttendees != expected.attendees.map { it.name to it.address }.toSet())
            return result("unknownOutcome", text(R.string.agent_step_unknown))
        return result("succeeded", text(R.string.agent_calendar_saved))
    }

    private suspend fun deleteOwned(items: List<NativeOwnedEvent>, active: suspend () -> Boolean): CapabilityResult {
        var completed = 0
        for (item in items) {
            if (!active()) return result("cancelled", context.getString(R.string.agent_calendar_delete_result, completed, items.size))
            try {
                if (owned.delete(item.id, item.calendarId, context.packageName, item.marker, item.details) != 1 ||
                    owned.find(item.calendarId, context.packageName, item.marker).any { !it.deleted }) break
                completed++
            } catch (_: RuntimeException) { break }
        }
        return result(if (completed == items.size) "succeeded" else "unknownOutcome",
            context.getString(R.string.agent_calendar_delete_result, completed, items.size))
    }

    private fun moves(args: JSONObject): List<Move> {
        val calendars = calendars().associateBy(Calendar::id)
        return instances(args, title = args.getString("title")).mapNotNull { instance ->
            val calendar = calendars[instance.calendar] ?: return@mapNotNull null
            val event = event(instance.event) ?: return@mapNotNull null
            val readOnly = !calendar.writable || event.organizer.isNullOrBlank() || !event.organizer.equals(calendar.account, true)
            if (readOnly) return@mapNotNull Move(instance, event, calendar, emptyList(), emptyList(), R.string.agent_calendar_read_only)
            if (event.allDay != 0 || instance.end <= instance.begin)
                return@mapNotNull Move(instance, event, calendar, emptyList(), emptyList(), R.string.agent_calendar_all_day)
            val reminders = reminders(event.id)
            val alertUnavailable = args.has("reminderMinutes") && reminders.none {
                it.minutes == args.getInt("reminderMinutes") && it.method == Reminders.METHOD_ALERT
            } && (calendar.maximumReminders <= reminders.size || Reminders.METHOD_ALERT.toString() !in calendar.reminderMethods.split(','))
            Move(instance, event, calendar, attendees(event.id), reminders, R.string.agent_calendar_alert_limit.takeIf { alertUnavailable })
        }
    }

    private fun calendars(): List<Calendar> = resolver.query(Calendars.CONTENT_URI, arrayOf(Calendars._ID,
        Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.OWNER_ACCOUNT, Calendars.CALENDAR_ACCESS_LEVEL,
        Calendars.ALLOWED_REMINDERS, Calendars.MAX_REMINDERS), "${Calendars.VISIBLE}=1", null, Calendars._ID)?.use { cursor ->
        buildList { while (size < 50 && cursor.moveToNext()) add(Calendar(cursor.getLong(0), cursor.getString(1).orEmpty(),
            cursor.getString(2).orEmpty(), cursor.getString(3).orEmpty(), cursor.getInt(4), cursor.getString(5).orEmpty(), cursor.getInt(6))) }
    } ?: error("Calendar provider unavailable")

    private fun instances(args: JSONObject, calendar: Long? = null, title: String? = null): List<Instance> {
        val from = args.getLong("from")
        val to = args.getLong("to")
        val uri = Instances.CONTENT_URI.buildUpon().appendPath(from.toString()).appendPath(to.toString()).build()
        return resolver.query(uri, arrayOf(Instances.EVENT_ID, Instances.CALENDAR_ID, Instances.TITLE, Instances.BEGIN, Instances.END),
            calendar?.let { "${Instances.CALENDAR_ID}=?" }, calendar?.let { arrayOf(it.toString()) }, "${Instances.BEGIN} ASC")?.use { cursor ->
            buildList {
                var scanned = 0
                while (size < 50 && scanned++ < 500 && cursor.moveToNext()) {
                    val name = cursor.getString(2).orEmpty()
                    if (title != null && (cursor.getLong(3) !in from until to || !name.contains(title, true))) continue
                    add(Instance(cursor.getLong(0), cursor.getLong(1), name.take(500), cursor.getLong(3), cursor.getLong(4)))
                }
            }
        } ?: error("Calendar instances unavailable")
    }

    private fun event(id: Long): Event? = resolver.query(ContentUris.withAppendedId(Events.CONTENT_URI, id), arrayOf(
        Events._ID, Events.CALENDAR_ID, Events.TITLE, Events.DTSTART, Events.DTEND, Events.EVENT_TIMEZONE, Events.ORGANIZER,
        Events.ALL_DAY, Events.RRULE, Events.RDATE, Events.DURATION, Events.ORIGINAL_ID, Events.ORIGINAL_INSTANCE_TIME, Events.DELETED,
    ), null, null, null)?.use { row ->
        if (!row.moveToFirst() || row.getInt(13) != 0) null else Event(row.getLong(0), row.getLong(1), row.getString(2).orEmpty(),
            row.getLong(3), row.getLong(4).takeUnless { row.isNull(4) }, row.getString(5), row.getString(6), row.getInt(7),
            row.getString(8), row.getString(9), row.getString(10), row.getLong(11).takeUnless { row.isNull(11) },
            row.getLong(12).takeUnless { row.isNull(12) })
    }

    private fun attendees(id: Long): List<Attendee> = resolver.query(Attendees.CONTENT_URI,
        arrayOf(Attendees._ID, Attendees.ATTENDEE_NAME, Attendees.ATTENDEE_EMAIL), "${Attendees.EVENT_ID}=?",
        arrayOf(id.toString()), Attendees._ID)?.use { cursor ->
        buildList { while (cursor.moveToNext()) { require(size < 100); add(Attendee(cursor.getLong(0), cursor.getString(1), cursor.getString(2))) } }
    } ?: error("Attendees unavailable")

    private fun reminders(id: Long): List<Reminder> = resolver.query(Reminders.CONTENT_URI,
        arrayOf(Reminders._ID, Reminders.MINUTES, Reminders.METHOD), "${Reminders.EVENT_ID}=?",
        arrayOf(id.toString()), Reminders._ID)?.use { cursor ->
        buildList { while (cursor.moveToNext()) { require(size < 50); add(Reminder(cursor.getLong(0), cursor.getInt(1), cursor.getInt(2))) } }
    } ?: error("Reminders unavailable")

    private fun ownedEvents(title: String): List<NativeOwnedEvent> {
        val calendars = owned.calendars().associateBy { it.id }
        val events: List<Pair<Long, String>> = resolver.query(Events.CONTENT_URI, arrayOf(Events.CALENDAR_ID, Events.CUSTOM_APP_URI, Events.TITLE),
            "${Events.CUSTOM_APP_PACKAGE}=? AND ${Events.CUSTOM_APP_URI} LIKE ? AND ${Events.DELETED}=0",
            arrayOf(context.packageName, "$OWNED_PREFIX%"), Events._ID)?.use { cursor ->
            buildList { while (cursor.moveToNext()) {
                if (!cursor.getString(2).orEmpty().contains(title, true)) continue
                require(size < 50)
                add(cursor.getLong(0) to checkNotNull(cursor.getString(1)))
            } }
        } ?: error("Owned events unavailable")
        return events.mapNotNull { (id, marker) ->
            if (id !in calendars) null else owned.find(id, context.packageName, marker).singleOrNull {
                !it.deleted && !it.details.optBoolean("unsupported") && it.details.has("reminderMinutes")
            }
        }
    }

    private fun ownedIdentity(event: NativeOwnedEvent) = JSONArray(listOf(event.id, event.calendarId, event.marker, event.details.toString())).toString()
    private fun ownedDescription(event: NativeOwnedEvent) = ownedDescription(event.details)
    private fun ownedDescription(details: JSONObject) = buildList {
        val zone = ZoneId.of(details.getString("timeZone"))
        add("${details.getString("title")} · ${time(details.getLong("start"), zone)} – ${time(details.getLong("end"), zone)}")
        add(context.getString(R.string.agent_calendar_zone, zone.id))
        if (details.optString("location").isNotEmpty()) add(context.getString(R.string.agent_calendar_location, details.getString("location")))
        add(text(if (details.optBoolean("repeatDaily")) R.string.agent_calendar_daily else R.string.agent_calendar_once))
        add(context.getString(R.string.agent_calendar_alert, details.getInt("reminderMinutes")))
    }.joinToString("\n")
    private fun text(id: Int) = context.getString(id)
    private fun result(status: String, message: String) = CapabilityResult(status, message)

    companion object {
        private const val OWNED_PREFIX = "alphy-agent:"
        private fun newEnd(instance: Instance, args: JSONObject) = Math.addExact(args.getLong("start"), instance.end - instance.begin)
        private fun assertRow(uri: android.net.Uri, key: String, id: Long, values: ContentValues) =
            ContentProviderOperation.newAssertQuery(uri).withSelection("$key=?", arrayOf(id.toString()))
                .withValues(values).withExpectedCount(1).build()
        private fun assertCount(uri: android.net.Uri, key: String, id: Long, count: Int) =
            ContentProviderOperation.newAssertQuery(uri).withSelection("$key=?", arrayOf(id.toString())).withExpectedCount(count).build()
        private fun time(epoch: Long, zone: ZoneId = ZoneId.systemDefault()): String = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(zone)
            .format(Instant.ofEpochMilli(epoch))
    }
}
