package com.alpha.assistant.capabilities

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.alpha.assistant.surfaces.ToolWidgets
import com.alpha.assistant.tools.ToolStore
import org.json.JSONObject
import java.math.BigDecimal
import java.security.SecureRandom

/** Versioned public data contains no native owner, effect key, URI, or account information. */
internal object NavigationContract {
    val ids = setOf("maps.markReached","maps.route")
    private const val stopProperties = """{"id":{"type":"string","maxLength":64},"label":{"type":"string","maxLength":160},"latE6":{"type":"integer","minimum":-90000000,"maximum":90000000},"lonE6":{"type":"integer","minimum":-180000000,"maximum":180000000},"coordinateSystem":{"type":"string","enum":["wgs84","gcj02"]}}"""
    private val stopSchema get() = JSONObject().put("type","object").put("additionalProperties",false)
        .put("required",org.json.JSONArray(listOf("id","label","latE6","lonE6","coordinateSystem"))).put("properties",JSONObject(stopProperties))
    val markResultSchema get() = JSONObject().put("version",JSONObject("{\"type\":\"integer\",\"const\":1}"))
        .put("reachedRef",JSONObject("{\"type\":\"string\",\"format\":\"opaque-owner-handle\",\"maxLength\":64}"))
        .put("stop",stopSchema).toString()
    val routeResultSchema get() = JSONObject().put("version",JSONObject("{\"type\":\"integer\",\"const\":1}"))
        .put("origin",stopSchema).put("destination",stopSchema).put("routeVerified",JSONObject("{\"type\":\"boolean\",\"const\":false}"))
        .put("mode",JSONObject("{\"type\":\"string\",\"enum\":[\"walking\",\"driving\",\"transit\",\"bicycling\"]}"))
        .put("mapApp",JSONObject("{\"type\":\"string\",\"enum\":[\"googleMaps\",\"amap\"]}")).toString()
    fun request(node: JSONObject): CapabilityRequest {
        require(node.getString("type") == "navigation" && node.getString("capability") in ids)
        return CapabilityCatalog.request(JSONObject().put("id","selected").put("capability",node.getString("capability")).put("args",node.getJSONObject("args")))
    }
    private fun decimal(args: JSONObject, key: String) = BigDecimal.valueOf(args.getLong(key),6).setScale(6).toPlainString()
    fun label(request: CapabilityRequest): String {
        val args=request.args
        val operation=if(request.capability == "maps.markReached") "Mark reached" else "Route to"
        return "$operation: ${args.getString("label")} [${args.getString("stopId")}] · ${decimal(args,"latE6")},${decimal(args,"lonE6")} ${args.getString("coordinateSystem")}" +
            if(request.capability == "maps.route") " · ${args.getString("mode")}" else ""
    }
    fun stop(args: JSONObject) = JSONObject().put("id",args.getString("stopId")).put("label",args.getString("label"))
        .put("latE6",args.getInt("latE6")).put("lonE6",args.getInt("lonE6")).put("coordinateSystem",args.getString("coordinateSystem"))
    fun validateStop(stop: JSONObject) {
        require(stop.keys().asSequence().toSet() == setOf("id","label","latE6","lonE6","coordinateSystem"))
        val args=JSONObject(stop.toString()).apply { put("version",1); put("stopId",remove("id")) }
        CapabilityCatalog.definition("maps.markReached").validate(args)
    }
    fun validateResult(capability: String, data: JSONObject) {
        require(data.get("version") is Number && data.getDouble("version") == 1.0)
        if(capability == "maps.markReached") {
            require(data.keys().asSequence().toSet() == setOf("version","reachedRef","stop"))
            require(data.get("reachedRef") is String && data.getString("reachedRef").matches(Regex("[0-9a-f]{64}")))
            validateStop(data.getJSONObject("stop"))
        } else {
            require(capability == "maps.route")
            require(data.keys().asSequence().toSet() == setOf("version","origin","destination","mode","mapApp","routeVerified"))
            validateStop(data.getJSONObject("origin")); validateStop(data.getJSONObject("destination"))
            require(data.get("routeVerified") == false)
            require(data.getString("mode") in setOf("walking","driving","transit","bicycling"))
            val system=data.getJSONObject("origin").getString("coordinateSystem")
            require(system == data.getJSONObject("destination").getString("coordinateSystem"))
            require(data.getString("mapApp") == if(system == "wgs84") "googleMaps" else "amap")
        }
    }
}

/** A frozen native control selection. Generated events and labels cannot create this authorization. */
internal class NavigationInteraction private constructor(private val owner: String, private val revision: Int,
    private val stateVersion: Int, private val selected: CapabilityRequest) {
    companion object {
        fun from(tool: com.alpha.assistant.tools.ToolRecord, action: String, value: Any?): NavigationInteraction? {
            val node=com.alpha.assistant.tools.ToolUiCatalog.validateAction(JSONObject(tool.ui),action,value,JSONObject(tool.state))
            if(node.getString("type") != "navigation") return null
            return NavigationInteraction(tool.id,tool.revision,tool.stateVersion,NavigationContract.request(node))
        }
    }
    fun matches(effect: CapabilityEffect): Boolean {
        if(effect.owner != owner || effect.revision != revision || effect.stateVersion != stateVersion+1 || effect.request.capability != selected.capability) return false
        val actual=effect.request.args; val expected=selected.args
        return actual.keys().asSequence().toSet() == expected.keys().asSequence().toSet() && expected.keys().asSequence().all { key ->
            val a=actual.get(key); val b=expected.get(key)
            if(a is Number && b is Number) a.toDouble() == b.toDouble() else a == b
        }
    }
}

/** SQLite stores only the latest reached marker and a bounded registry of stable visited-stop IDs. */
internal class NavigationLedger(private val store: ToolStore) {
    companion object {
        const val MAX_STOPS_PER_OWNER = 128
        fun createTables(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE maps_stops(owner TEXT NOT NULL,revision INTEGER NOT NULL,stop_id TEXT NOT NULL,stop TEXT NOT NULL,PRIMARY KEY(owner,revision,stop_id))")
            db.execSQL("CREATE TABLE maps_reached(owner TEXT PRIMARY KEY,revision INTEGER NOT NULL,reached_ref TEXT NOT NULL,stop_id TEXT NOT NULL,effect_key TEXT NOT NULL,marked_at INTEGER NOT NULL)")
        }
    }
    fun revoke(owner: String) { store.writableDatabase.delete("maps_reached","owner=?",arrayOf(owner)) }
    private fun known(owner: String, revision: Int, id: String): String? = store.readableDatabase.rawQuery(
        "SELECT stop FROM maps_stops WHERE owner=? AND revision=? AND stop_id=?",arrayOf(owner,revision.toString(),id)).use { if(it.moveToFirst()) it.getString(0) else null }
    fun consistent(effect: CapabilityEffect, stop: JSONObject): Boolean {
        val old=known(effect.owner,effect.revision,stop.getString("id")) ?: return true
        val previous=JSONObject(old)
        return stop.keys().asSequence().all { stop.get(it) == previous.get(it) }
    }
    /** Compare the full native record so a caller cannot substitute ownership or a script-supplied token. */
    fun valid(effect: CapabilityEffect): Boolean {
        val current=store.effect(effect.key) ?: return false
        return current.phase == "running" && current.owner == effect.owner && current.revision == effect.revision &&
            current.stateVersion == effect.stateVersion && current.request.json().toString() == effect.request.json().toString() && store.effectTargetValid(current)
    }
    fun mark(effect: CapabilityEffect, stop: JSONObject, foreground: ()->Boolean): CapabilityResult {
        var result=CapabilityResult("denied","The native navigation context is no longer valid.")
        store.transaction {
            // The broker has already waited for its mutex and Main dispatch. Check again inside the commit.
            if(!foreground()) {result=CapabilityResult("awaitingUser","Open and unlock Alphy to mark this stop.");return@transaction}
            if(!valid(effect) || effect.request.capability != "maps.markReached") return@transaction
            if(!consistent(effect,stop)) {
                result=CapabilityResult("denied","This stop ID already identifies another stop. Use a distinct stable ID."); return@transaction
            }
            val old=known(effect.owner,effect.revision,stop.getString("id"))
            if(old == null) {
                val count=store.readableDatabase.rawQuery("SELECT COUNT(*) FROM maps_stops WHERE owner=?",arrayOf(effect.owner)).use { it.moveToFirst(); it.getInt(0) }
                if(count >= MAX_STOPS_PER_OWNER) { result=CapabilityResult("unavailable","This app reached its native stop limit. Existing stops remain saved."); return@transaction }
                store.writableDatabase.insertOrThrow("maps_stops",null,ContentValues().apply {
                    put("owner",effect.owner); put("revision",effect.revision); put("stop_id",stop.getString("id")); put("stop",stop.toString())
                })
            }
            val ref=ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
            check(store.writableDatabase.insertWithOnConflict("maps_reached",null,ContentValues().apply {
                put("owner",effect.owner); put("revision",effect.revision); put("reached_ref",ref); put("stop_id",stop.getString("id"))
                put("effect_key",effect.key); put("marked_at",System.currentTimeMillis())
            },SQLiteDatabase.CONFLICT_REPLACE) != -1L)
            result=CapabilityResult("succeeded","The stop was explicitly marked reached. Device arrival remains unverified.",
                JSONObject().put("version",1).put("reachedRef",ref).put("stop",stop))
        }
        return result
    }
    fun resolve(effect: CapabilityEffect, ref: String): JSONObject? {
        if(!valid(effect) || !store.granted(effect.owner,"maps.markReached")) return null
        return store.readableDatabase.rawQuery("SELECT s.stop FROM maps_reached r JOIN maps_stops s ON s.owner=r.owner AND s.revision=r.revision AND s.stop_id=r.stop_id WHERE r.owner=? AND r.revision=? AND r.reached_ref=?",
            arrayOf(effect.owner,effect.revision.toString(),ref)).use { if(it.moveToFirst()) JSONObject(it.getString(0)).also(NavigationContract::validateStop) else null }
    }
}

/** Only the broker calls this executor with a persistent claimed effect. No device location is read. */
internal class NavigationCapabilities(private val context: Context, private val store: ToolStore,
    private val installed: (Intent)->Boolean = { intent -> intent.resolveActivity(context.packageManager) != null }) {
    fun execute(effect: CapabilityEffect, foreground: ()->Boolean, launch: (Intent)->Unit): CapabilityResult {
        val request=CapabilityCatalog.request(effect.request.json())
        require(request.capability in NavigationContract.ids)
        val ledger=NavigationLedger(store)
        if(!ledger.valid(effect)) return CapabilityResult("denied","Navigation requires a current native owner context.")
        val active = { foreground() && !ToolWidgets.isLocked(context) }
        if(!active()) return CapabilityResult("awaitingUser","Open and unlock Alphy to use this navigation action.")
        val stop=NavigationContract.stop(request.args)
        if(request.capability == "maps.markReached") return ledger.mark(effect,stop,active)
        val ref=request.args.getString("reachedRef")
        val origin=ledger.resolve(effect,ref) ?: return CapabilityResult("denied","Mark the current reached stop again. This reference is missing, stale, revoked, or belongs to another app.")
        if(!ledger.consistent(effect,stop)) return CapabilityResult("denied","This destination ID already identifies another stop.")
        if(origin.getString("coordinateSystem") != stop.getString("coordinateSystem")) return CapabilityResult("unavailable","Origin and destination use different coordinate systems. No conversion is assumed.")
        val mode=request.args.getString("mode")
        val google=origin.getString("coordinateSystem") == "wgs84"
        val intent=if(google) google(origin,stop,mode) else amap(origin,stop,mode)
        if(!installed(intent)) return CapabilityResult("unavailable",if(google) "Install or enable Google Maps for WGS84 routes." else "Install or enable AMap for GCJ-02 routes.")
        // No suspension follows this check: Main executes the final transaction and startActivity together.
        var result=CapabilityResult("denied","Navigation access or the reached stop changed before launch.")
        store.transaction {
            if(!active()) {result=CapabilityResult("awaitingUser","Open and unlock Alphy to use this route.");return@transaction}
            if(ledger.resolve(effect,ref)?.toString() != origin.toString()) return@transaction
            result=try {
                launch(intent)
                CapabilityResult("launched","The map app opened with explicit origin and destination. Route calculation, navigation, and arrival remain unverified.",
                    JSONObject().put("version",1).put("origin",origin).put("destination",stop).put("mode",mode)
                        .put("mapApp",if(google) "googleMaps" else "amap").put("routeVerified",false))
            } catch(_: android.content.ActivityNotFoundException) {CapabilityResult("unavailable","The supported map app could not open.")}
        }
        return result
    }

    private fun coordinate(stop: JSONObject, key: String) = BigDecimal.valueOf(stop.getLong(key),6).setScale(6).toPlainString()
    private fun pair(stop: JSONObject) = "${coordinate(stop,"latE6")},${coordinate(stop,"lonE6")}"
    private fun google(origin: JSONObject, destination: JSONObject, mode: String) = Intent(Intent.ACTION_VIEW,
        Uri.parse("https://www.google.com/maps/dir/").buildUpon().appendQueryParameter("api","1")
            .appendQueryParameter("origin",pair(origin)).appendQueryParameter("destination",pair(destination))
            .appendQueryParameter("travelmode",mode).build()).setPackage("com.google.android.apps.maps")
    private fun amap(origin: JSONObject, destination: JSONObject, mode: String) = Intent(Intent.ACTION_VIEW,
        Uri.parse("amapuri://route/plan/").buildUpon().appendQueryParameter("sourceApplication","Alphy")
            .appendQueryParameter("slat",coordinate(origin,"latE6")).appendQueryParameter("slon",coordinate(origin,"lonE6"))
            .appendQueryParameter("sname",origin.getString("label")).appendQueryParameter("dlat",coordinate(destination,"latE6"))
            .appendQueryParameter("dlon",coordinate(destination,"lonE6")).appendQueryParameter("dname",destination.getString("label"))
            .appendQueryParameter("dev","0").appendQueryParameter("t",when(mode) { "driving" -> "0"; "transit" -> "1"; "walking" -> "2"; else -> "3" })
            .appendQueryParameter("m","0").build()).setPackage("com.autonavi.minimap").addCategory(Intent.CATEGORY_DEFAULT)
}
