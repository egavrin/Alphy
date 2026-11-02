package com.alpha.assistant.tools

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.capabilities.CapabilityResource
import com.alpha.assistant.capabilities.CapabilityResources
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Regression workflow: cached native pixels must disappear on pause, URI denial, expiry, and resource removal.
 * LifecycleRegistry isolates these transitions without timing a permission dialog or reading selected content.
 */
@RunWith(AndroidJUnit4::class)
class NativeImageLifecycleTest {
    @Test fun unchangedHandleRechecksUriAccessExpiryRemovalAndResume() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val base=instrumentation.targetContext
        val granted=AtomicBoolean(true)
        val context=object: ContextWrapper(base) {
            override fun checkUriPermission(uri: Uri, pid: Int, uid: Int, flags: Int): Int =
                if(uri.toString() == "content://native-image-fixture/photo" && flags == Intent.FLAG_GRANT_READ_URI_PERMISSION && granted.get()) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        }
        val name="native-image-${UUID.randomUUID()}.db"
        val bitmap=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888)
        val shown=AtomicReference<Bitmap?>(null)
        val checks=AtomicInteger(); val loads=AtomicInteger(); val decodes=AtomicBoolean(true)
        val owner=object: LifecycleOwner {val registry=LifecycleRegistry(this); override val lifecycle get()=registry}
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        try {ToolStore(context,name).use {store ->
            val spec=ToolSpec.parse(JSONObject().put("formatVersion",2).put("title","Image lifecycle").put("description","")
                .put("capabilities",JSONArray().put("photo.pick")).put("stateSchema",JSONObject().put("photo","string"))
                .put("initialState",JSONObject().put("photo","a".repeat(64)))
                .put("ui",JSONObject("""{"type":"image","text":"Photo","bind":"photo"}"""))
                .put("source","return {state:input.state};").toString())
            val tool=ToolRecord(UUID.randomUUID().toString(),1,1,spec.json,spec.initialState,spec.ui.toString(),null,true)
            store.saveTool(tool,true); store.grant(tool.id,"photo.pick",true)
            val resource=CapabilityResource("a".repeat(64),tool.id,"photo","content://native-image-fixture/photo","image/png",12,false,false,ToolStore.processSession,Long.MAX_VALUE)
            store.insertResource(resource)
            val manager=CapabilityResources(context,store,permissionCheck={true})
            fun waitFor(message: String, predicate: () -> Boolean) {
                val until=SystemClock.uptimeMillis()+4000
                while(!predicate() && SystemClock.uptimeMillis()<until) SystemClock.sleep(20)
                assertTrue(message,predicate())
            }
            instrumentation.runOnMainSync {
                owner.registry.currentState=Lifecycle.State.RESUMED
                scope.launch {observeNativeImage(owner.lifecycle,
                    available={withContext(Dispatchers.IO) {checks.incrementAndGet(); runCatching {manager.resolve(tool.id,resource.id)}.isSuccess}},
                    load={loads.incrementAndGet(); if(decodes.get()) bitmap else null},display={shown.set(it)})}
            }
            waitFor("Initially authorized image",{shown.get() === bitmap})
            val initialLoads=loads.get()
            instrumentation.runOnMainSync {owner.registry.currentState=Lifecycle.State.STARTED}
            waitFor("Pause clears cached pixels",{shown.get() == null})
            granted.set(false)
            instrumentation.runOnMainSync {owner.registry.currentState=Lifecycle.State.RESUMED}
            val before=checks.get(); waitFor("Resume rechecks unchanged handle",{checks.get()>before})
            assertNull(shown.get()); assertEquals(initialLoads,loads.get())
            granted.set(true); waitFor("Restored URI access reloads",{shown.get() === bitmap && loads.get()>initialLoads})
            granted.set(false); waitFor("Visible URI revocation clears pixels",{shown.get() == null})
            granted.set(true); waitFor("Visible grant restoration reloads",{shown.get() === bitmap})
            store.deleteResource(resource.id); store.insertResource(resource.copy(expiresAt=System.currentTimeMillis()-1))
            waitFor("Visible expiry clears pixels",{shown.get() == null})
            store.deleteResource(resource.id); store.insertResource(resource)
            waitFor("Available replacement reloads",{shown.get() === bitmap})
            store.deleteResource(resource.id); waitFor("Visible removal clears pixels",{shown.get() == null})
            decodes.set(false); store.insertResource(resource)
            val beforeFailure=loads.get(); waitFor("Authorized decode failure attempts once",{loads.get()>beforeFailure})
            val failedLoads=loads.get(); val failedChecks=checks.get()
            waitFor("Failed decode still checks access",{checks.get() >= failedChecks+2})
            assertNull(shown.get()); assertEquals("Decode failure must not reread content every poll",failedLoads,loads.get())
            instrumentation.runOnMainSync {owner.registry.currentState=Lifecycle.State.STARTED; owner.registry.currentState=Lifecycle.State.RESUMED}
            waitFor("Resume permits a fresh decode attempt",{loads.get()>failedLoads})
            instrumentation.runOnMainSync {owner.registry.currentState=Lifecycle.State.DESTROYED; scope.cancel()}
        }} finally {
            instrumentation.runOnMainSync {scope.cancel(); owner.registry.currentState=Lifecycle.State.DESTROYED}
            context.deleteDatabase(name); bitmap.recycle()
        }
    }
}
