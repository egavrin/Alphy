package com.alpha.assistant.capabilities

import android.content.ActivityNotFoundException
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.tools.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Offline SQLite/intent workflow fixtures precede production changes. Cases: skipped stops, stale/foreign refs,
 * revision/grant/archive invalidation, spoofed fields, duplicate IDs, invalid coordinates/version, restart,
 * background/lock gates, missing adapters, URI encoding, interrupted launches, bounded results, unsupported schema retention.
 * Intent interception avoids network and external-app state. Real map visibility remains device acceptance work.
 */
@RunWith(AndroidJUnit4::class)
class NavigationWorkflowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun args(id: String = "A", system: String = "wgs84") = JSONObject().put("version",1)
        .put("stopId",id).put("label","上海 & $id ? #").put("latE6",31230000 + (id.first().code-65)*10000).put("lonE6",121473000 + (id.first().code-65)*20000).put("coordinateSystem",system)
    private fun request(capability: String, args: JSONObject, id: String = "run") = CapabilityCatalog.request(
        JSONObject().put("id",id).put("capability",capability).put("args",args))
    private fun tool() = ToolSpec.parse(JSONObject().put("formatVersion",3).put("title","Offline navigation").put("description","")
        .put("capabilities",JSONArray().put("maps.markReached").put("maps.route")).put("capabilityResults",true)
        .put("stateSchema",JSONObject("{\"ref\":\"string\"}")).put("initialState",JSONObject("{\"ref\":\"\"}"))
        .put("ui",JSONObject("{\"type\":\"button\",\"text\":\"Mark reached A\",\"action\":\"mark\"}"))
        .put("source","return {state:input.state};").toString()).let {
            ToolRecord(UUID.randomUUID().toString(),1,1,it.json,it.initialState,it.ui.toString(),null,true)
        }
    private suspend fun fixture(block: suspend (ToolStore,ToolRecord) -> Unit) {
        val name="navigation-${UUID.randomUUID()}.db"
        try { ToolStore(context,name).use { store ->
            val tool=tool(); store.commitTool(tool,null,true)
            block(store,tool)
        } } finally { context.deleteDatabase(name) }
    }
    private fun enqueue(store: ToolStore, owner: String, request: CapabilityRequest): CapabilityEffect {
        val old=store.tool(owner)!!
        store.commitTool(old.copy(stateVersion=old.stateVersion+1),old,false,effects=listOf(request))
        return store.effects(owner).last()
    }
    private suspend fun run(store: ToolStore, effect: CapabilityEffect, foreground: Boolean = true,
        installed: Boolean = true, launch: (Intent)->Unit = {}): CapabilityResult =
        CapabilityBroker(context,store).executeOwned(effect.key) { claimed ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { NavigationCapabilities(context,store) { installed }.execute(claimed,{foreground}) { launch(it) } }
        }
    @Test fun skipBRouteCUsesAThenNewMarkInvalidatesOldReference(): Unit = runBlocking { fixture { store, tool ->
        val mark=enqueue(store,tool.id,request("maps.markReached",args()))
        val result=run(store,mark); assertEquals("succeeded",result.status)
        val ref=result.data.getString("reachedRef")
        var intent: Intent?=null
        val route=enqueue(store,tool.id,request("maps.route",args("C").put("reachedRef",ref).put("mode","walking")))
        val launched=run(store,route) { intent=it }
        assertEquals("launched",launched.status); assertFalse(launched.data.getBoolean("routeVerified"))
        assertEquals("A",launched.data.getJSONObject("origin").getString("id"))
        assertEquals("C",launched.data.getJSONObject("destination").getString("id"))
        assertEquals("com.google.android.apps.maps",intent!!.getPackage())
        assertEquals("31.230000,121.473000",intent!!.data!!.getQueryParameter("origin"))
        assertEquals("31.250000,121.513000",intent!!.data!!.getQueryParameter("destination"))
        assertEquals("walking",intent!!.data!!.getQueryParameter("travelmode"))
        assertEquals("1",intent!!.data!!.getQueryParameter("api"))
        assertEquals(launched.data.toString(),CapabilityResultEvent.data("maps.route",launched).toString())
        CapabilityBroker(context,store).executeOwned(route.key) { error("Duplicate launch") }
        run(store,enqueue(store,tool.id,request("maps.markReached",args("B"))))
        assertEquals("denied",run(store,enqueue(store,tool.id,request("maps.route",args("C").put("reachedRef",ref).put("mode","walking")))).status)
    } }
    @Test fun foreignArchivedRevisionAndBackgroundRequestsNeverLaunch(): Unit = runBlocking { fixture { store, tool ->
        val ref=run(store,enqueue(store,tool.id,request("maps.markReached",args()))).data.getString("reachedRef")
        val foreign=tool(); store.commitTool(foreign,null,true)
        val foreignRoute=enqueue(store,foreign.id,request("maps.route",args("C").put("reachedRef",ref).put("mode","walking")))
        assertEquals("denied",run(store,foreignRoute) { error("Foreign launch") }.status)
        val bg=enqueue(store,tool.id,request("maps.markReached",args("B")))
        assertEquals("awaitingUser",run(store,bg,false) { error("Background launch") }.status)
        store.setArchived(store.tool(tool.id)!!,true); store.setArchived(store.tool(tool.id)!!,false)
        assertEquals("denied",run(store,enqueue(store,tool.id,request("maps.route",args("C").put("reachedRef",ref).put("mode","walking")))).status)
        val fresh=run(store,enqueue(store,tool.id,request("maps.markReached",args("B")))).data.getString("reachedRef")
        val old=store.tool(tool.id)!!; store.commitTool(old.copy(revision=2,stateVersion=old.stateVersion+1),old,true)
        assertEquals("denied",run(store,enqueue(store,tool.id,request("maps.route",args("C").put("reachedRef",fresh).put("mode","walking")))).status)
    } }
    @Test fun restartMissingAdapterAndAmapEncoding(): Unit = runBlocking { fixture { store, tool ->
        val marked=run(store,enqueue(store,tool.id,request("maps.markReached",args(system="gcj02"))))
        val ref=marked.data.getString("reachedRef")
        ToolStore(context,store.databaseName).use { reopened ->
            reopened.recoverCapabilityWork(force=true)
            val missing=enqueue(reopened,tool.id,request("maps.route",args("C","gcj02").put("reachedRef",ref).put("mode","transit")))
            assertEquals("unavailable",run(reopened,missing,installed=false) { error("Missing handler launched") }.status)
            val route=enqueue(reopened,tool.id,request("maps.route",args("C","gcj02").put("reachedRef",ref).put("mode","transit")))
            val result=run(reopened,route) { intent ->
                assertEquals("com.autonavi.minimap",intent.getPackage())
                assertEquals("上海 & A ? #",intent.data!!.getQueryParameter("sname"))
                assertEquals("上海 & C ? #",intent.data!!.getQueryParameter("dname"))
                assertEquals("31.230000",intent.data!!.getQueryParameter("slat"))
                assertEquals("0",intent.data!!.getQueryParameter("dev")); assertEquals("1",intent.data!!.getQueryParameter("t"))
            }
            assertEquals("launched",result.status)
            val vanished=enqueue(reopened,tool.id,request("maps.route",args("C","gcj02").put("reachedRef",ref).put("mode","walking")))
            assertEquals("unavailable",run(reopened,vanished) { throw ActivityNotFoundException() }.status)
            val mixed=enqueue(reopened,tool.id,request("maps.route",args("C").put("reachedRef",ref).put("mode","walking")))
            assertEquals("unavailable",run(reopened,mixed) { error("Mixed coordinates") }.status)
            assertEquals(store.tool(tool.id),reopened.tool(tool.id))
        }
    } }
    @Test fun strictArgumentsAndDuplicateStopIdsFailClosed(): Unit = runBlocking {
        for((key,value) in listOf("version" to 2,"version" to 1.5,"latE6" to 90000001,"lonE6" to -180000001,
            "latE6" to 1.2,"latE6" to "31","coordinateSystem" to "automatic","stopId" to "","label" to " ","owner" to "forged","effectKey" to "forged")) {
            assertTrue(key,runCatching { request("maps.markReached",args().put(key,value)) }.isFailure)
        }
        assertTrue(runCatching { CapabilityCatalog.parseEffects(JSONArray().put(request("maps.markReached",args()).json()).put(request("maps.markReached",args("B")).json()),setOf("maps.markReached"),"action") }.isFailure)
        fixture { store, tool ->
            run(store,enqueue(store,tool.id,request("maps.markReached",args())))
            assertEquals("denied",run(store,enqueue(store,tool.id,request("maps.markReached",args().put("latE6",30000000)))).status)
            val effect=enqueue(store,tool.id,request("maps.markReached",args("B")))
            assertEquals("cancelled",CapabilityBroker(context,store).cancel(effect.key)?.status)
            assertEquals("cancelled",run(store,effect).status)
        }
    }

    @Test fun foregroundIsReadAfterMutexAndMainWaits(): Unit = runBlocking {
        fixture { store, tool ->
            val effect=enqueue(store,tool.id,request("maps.markReached",args()))
            var foreground=true
            ToolStore.effectMutex.lock()
            val work=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).async {
                CapabilityBroker(context,store).executeNative(effect.key,{foreground}) { _, _ -> error("Mark launched an activity") }
            }
            foreground=false
            ToolStore.effectMutex.unlock()
            assertEquals("awaitingUser",work.await().status)
            assertEquals(0,store.readableDatabase.rawQuery("SELECT COUNT(*) FROM maps_reached",null).use { it.moveToFirst();it.getInt(0) })
        }
        for(change in listOf("background","state","archive","revision","marker")) fixture { store, tool ->
            val ref=run(store,enqueue(store,tool.id,request("maps.markReached",args()))).data.getString("reachedRef")
            val effect=enqueue(store,tool.id,request("maps.route",args("C").put("reachedRef",ref).put("mode","walking")))
            val entered=java.util.concurrent.CountDownLatch(1); val release=java.util.concurrent.CountDownLatch(1)
            var foreground=true
            android.os.Handler(android.os.Looper.getMainLooper()).post {entered.countDown();check(release.await(10,java.util.concurrent.TimeUnit.SECONDS))}
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
            var launches=0
            val work=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).async {
                CapabilityBroker(context,store).executeNative(effect.key,{foreground}) { _, _ -> launches++ }
            }
            try {
                val end=android.os.SystemClock.uptimeMillis()+5000
                while(store.effect(effect.key)!!.phase != "running" && android.os.SystemClock.uptimeMillis()<end) kotlinx.coroutines.delay(10)
                assertEquals("running",store.effect(effect.key)!!.phase)
                when(change) {
                    "background" -> foreground=false
                    "state" -> {val old=store.tool(tool.id)!!;store.commitTool(old.copy(stateVersion=old.stateVersion+1),old,false)}
                    "archive" -> store.setArchived(store.tool(tool.id)!!,true)
                    "revision" -> {val old=store.tool(tool.id)!!;store.commitTool(old.copy(revision=2,stateVersion=old.stateVersion+1),old,true)}
                    "marker" -> store.writableDatabase.execSQL("UPDATE maps_reached SET reached_ref=? WHERE owner=?",arrayOf("f".repeat(64),tool.id))
                }
            } finally {release.countDown()}
            assertNotEquals(change,"launched",work.await().status); assertEquals(change,0,launches)
        }
    }
    @Test fun storedFileMetadataDoesNotHideGrants(): Unit = runBlocking { fixture { store, tool ->
        store.seedLegacyDeviceGrant(tool.id,"maps.markReached",true)
        store.writableDatabase.version=5
        ToolStore(context,store.databaseName).use { reopened ->
            assertNotNull(reopened.tool(tool.id))
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(store.databaseName).path,null,SQLiteDatabase.OPEN_READONLY).use { database ->
            assertEquals(5,database.version)
            database.rawQuery("SELECT granted FROM capability_grants WHERE owner=? AND capability='maps.markReached'",arrayOf(tool.id)).use {
                assertTrue(it.moveToFirst());assertEquals(1,it.getInt(0))
            }
        }
    } }
}
