package com.alpha.assistant.capabilities

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import com.alpha.assistant.tools.ToolStore
import java.io.File
import java.security.SecureRandom

/** Selected content remains native. No API reads arbitrary files for generated code. */
class CapabilityResources(private val context: Context, private val store: ToolStore,
    private val permissionCheck: (String)->Boolean = store::permissionGranted) {
    companion object {
        const val MAX_FILE_BYTES = 10L * 1024 * 1024
        const val MAX_RESOURCES = 32
        private val lock = Any()
        private val random = SecureRandom()
    }
    data class Acquired(val result: CapabilityResult, val resource: CapabilityResource?)
    private fun token() = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun resource(owner: String, kind: String, locator: String, ownsGrant: Boolean) = CapabilityResource(
        token(),owner,kind,locator,"application/octet-stream",0,false,ownsGrant,ToolStore.processSession,System.currentTimeMillis()+30L*24*60*60*1000)
    private fun cameraFile(name: String): File {
        require(name.matches(Regex("capture-[0-9a-fA-F-]{36}\\.jpg")))
        return File(context.filesDir,name).canonicalFile.also { require(it.parentFile == context.filesDir.canonicalFile) }
    }
    /** Journal the private copy before the first write, so an interrupted review cannot leave an untracked file. */
    fun saveCameraPreview(key: String, bitmap: Bitmap): CapabilityResult = synchronized(lock) {
        val effect=store.effect(key) ?: return@synchronized CapabilityResult("unavailable","The camera request is unavailable.")
        if(effect.phase != "waiting" || effect.request.capability != "camera.capture" || !store.effectTargetValid(effect) || !permissionCheck("camera.capture"))
            return@synchronized CapabilityResult("unavailable","The camera request is no longer available.")
        reclaimUnavailable(effect.owner)
        val file=cameraFile("capture-${java.util.UUID.randomUUID()}.jpg")
        val journal=resource(effect.owner,"cameraPhoto",file.path,false)
        try {
            require(bitmap.width in 1..2048 && bitmap.height in 1..2048)
            store.journalResource(journal)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG,90,it)) }
            require(file.length() in 1..MAX_FILE_BYTES)
            CapabilityResult("succeeded","The reviewed camera thumbnail is saved privately.",org.json.JSONObject().put("localPhoto",file.name))
        } catch(_: Exception) {
            cleanupAcquisition(journal)
            CapabilityResult("failed","The reviewed camera thumbnail could not be saved.")
        }
    }
    fun acquire(owner: String, capability: String, result: CapabilityResult): Acquired {
        if(result.status != "succeeded") return Acquired(result,null)
        reclaimUnavailable(owner)
        val kind=when(capability) { "photo.pick" -> "photo"; "file.pick" -> "document"; "camera.capture" -> "cameraPhoto"; else -> error("Not a selected resource") }
        var journal: CapabilityResource?=null
        try {
            val mime: String
            val size: Long
            val persistent: Boolean
            val ownsGrant: Boolean
            val locator: String
            if(kind == "cameraPhoto") {
                val file=cameraFile(result.data.getString("localPhoto"))
                require(file.isFile)
                locator=file.path; mime="image/jpeg"; size=file.length(); persistent=true; ownsGrant=false
                journal=store.acquisitions().find { it.owner == owner && it.kind == kind && it.locator == locator && it.session == ToolStore.processSession }
                    ?: resource(owner,kind,locator,false).also(store::journalResource)
            } else {
                val uri=Uri.parse(result.data.getString("uri"))
                require(uri.scheme == "content" && !uri.authority.isNullOrBlank() && uri.toString().length <= 4096)
                locator=uri.toString()
                val before=context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
                journal=resource(owner,kind,locator,!before).also(store::journalResource)
                if(!before) runCatching { context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                persistent=context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
                ownsGrant=!before && persistent
                require(context.checkUriPermission(uri,android.os.Process.myPid(),android.os.Process.myUid(),Intent.FLAG_GRANT_READ_URI_PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                mime=context.contentResolver.getType(uri) ?: error("Unknown MIME")
                size=context.contentResolver.query(uri,arrayOf(OpenableColumns.SIZE),null,null,null)?.use { c ->
                    if(c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
                } ?: -1L
                if(kind == "photo") require(mime.startsWith("image/"))
            }
            require(mime.length <= 100 && mime.matches(Regex("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+")))
            require(size in 0..MAX_FILE_BYTES)
            val selected=checkNotNull(journal).copy(mimeType=mime,sizeBytes=size,persistent=persistent,ownsGrant=ownsGrant)
            val safe=CapabilityResult("succeeded",if(persistent) "Selected content is available on this device." else "Selected content is available only in this app session. Select it again after restart.",selected.metadata())
            CapabilityResultEvent.data(capability,safe)
            return Acquired(safe,selected)
        } catch(failure: Exception) {
            journal?.let(::cleanupAcquisition)
            if(kind == "cameraPhoto") discardCamera(result)
            throw failure
        }
    }
    fun finishSelection(key: String, result: CapabilityResult): Boolean = synchronized(lock) {
        val effect=store.effect(key) ?: run { discardCamera(result); return@synchronized false }
        if(effect.phase != "waiting") { discardCamera(result); return@synchronized false }
        if(!store.effectTargetValid(effect) || !permissionCheck(effect.request.capability)) {
            store.finishUserEffect(key,CapabilityResult("unavailable","Selected content is no longer available."))
            discardCamera(result)
            return@synchronized false
        }
        val acquired=try { acquire(effect.owner,effect.request.capability,result) }
        catch(_: Exception) { Acquired(CapabilityResult("unavailable","Selected content access or bounded metadata is unavailable."),null) }
        var accepted=false
        try {
            accepted=store.finishUserEffect(key,acquired.result,acquired.resource)
            return@synchronized accepted
        } finally { if(!accepted && acquired.resource != null) release(acquired.resource) }
    }
    private fun discardCamera(result: CapabilityResult) {
        val file=runCatching { cameraFile(result.data.getString("localPhoto")) }.getOrNull() ?: return
        if(store.resourcesForLocator(file.path).isEmpty()) {
            val journals=store.acquisitions().filter { it.locator == file.path && it.kind == "cameraPhoto" }
            if(journals.isEmpty()) file.delete() else journals.forEach(::cleanupAcquisition)
        }
    }
    fun resolve(owner: String, id: String): CapabilityResource {
        val selected=checkNotNull(store.resource(id)) { "Resource unavailable" }
        val tool=checkNotNull(store.tool(owner)) { "Resource unavailable" }
        check(selected.owner == owner && !tool.archived && selected.expiresAt > System.currentTimeMillis()) { "Resource unavailable" }
        val capability=when(selected.kind) { "photo" -> "photo.pick"; "document" -> "file.pick"; "cameraPhoto" -> "camera.capture"; else -> error("Resource unavailable") }
        check(capability in com.alpha.assistant.tools.ToolSpec.parse(tool.spec).capabilities && store.granted(owner,capability) && permissionCheck(capability)) { "Resource unavailable" }
        check(permissionAvailable(selected)) { "Resource access unavailable" }
        if(selected.kind == "cameraPhoto") check(cameraFile(File(selected.locator).name).path == selected.locator && File(selected.locator).isFile && File(selected.locator).length() == selected.sizeBytes)
        return selected
    }
    /** This reads Android grant metadata only. It does not query a content provider or read a file. */
    fun permissionAvailable(selected: CapabilityResource): Boolean = runCatching {
        if(selected.expiresAt <= System.currentTimeMillis() || (!selected.persistent && selected.session != ToolStore.processSession)) return@runCatching false
        val capability=when(selected.kind) { "photo" -> "photo.pick"; "document" -> "file.pick"; "cameraPhoto" -> "camera.capture"; else -> return@runCatching false }
        if(!permissionCheck(capability)) return@runCatching false
        if(selected.kind == "cameraPhoto") true else {
            val uri=Uri.parse(selected.locator)
            uri.scheme == "content" && context.checkUriPermission(uri,android.os.Process.myPid(),android.os.Process.myUid(),Intent.FLAG_GRANT_READ_URI_PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                (!selected.persistent || context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission })
        }
    }.getOrDefault(false)
    /** Expiry and prior-process temporary access cannot recover. Revoked tool or runtime grants can recover, so retain them. */
    fun reclaimUnavailable(owner: String) = synchronized(lock) {
        val now=System.currentTimeMillis()
        store.resources(owner).filter { it.expiresAt <= now || (!it.persistent && it.session != ToolStore.processSession) }.forEach(::release)
    }
    fun release(selected: CapabilityResource) = synchronized(lock) {
        var cleanup=false
        store.transaction {
            val siblings=store.resourcesForLocator(selected.locator).filter { it.id != selected.id }
            if(siblings.isEmpty()) { store.deleteResource(selected.id); store.journalResource(selected); cleanup=true }
            else {
                if(selected.ownsGrant) store.transferResourceGrant(siblings.first().id)
                store.deleteAcquisition(selected.id)
            }
            store.deleteResource(selected.id)
        }
        if(cleanup) cleanupAcquisition(selected)
    }
    fun recoverAcquisitions(force: Boolean = false) = synchronized(lock) {
        store.acquisitions().filter { force || it.session != ToolStore.processSession }.forEach(::cleanupAcquisition)
    }
    private fun cleanupAcquisition(journal: CapabilityResource) {
        val siblings=store.resourcesForLocator(journal.locator)
        if(journal.kind == "cameraPhoto") {
            if(siblings.isEmpty()) {
                val file=runCatching { cameraFile(File(journal.locator).name).takeIf { it.path == journal.locator } }.getOrNull()
                if(file != null && file.exists() && !file.delete()) return
            }
        } else if(journal.ownsGrant) {
            if(siblings.isEmpty()) {
                val uri=Uri.parse(journal.locator)
                runCatching { context.contentResolver.releasePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                if(context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }) return
            } else store.transferResourceGrant(siblings.first().id)
        }
        store.deleteAcquisition(journal.id)
    }
}
