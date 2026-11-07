package com.alpha.assistant.tools

import android.content.Context
import android.content.Intent
import android.database.DatabaseErrorHandler
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

internal class ToolLibraryUnavailable(cause: Throwable? = null) :
    IllegalStateException("The selected mini-app storage is unavailable. Existing files are preserved.", cause)

internal data class ToolLibrarySelection(val logical: String,val physical: String) {
    val original get()=logical==physical
    val primary get()=logical==ToolLibraryStorage.DEFAULT
    val automation get()=if(primary && original) "alpha-automations.db" else "$physical-automations.db"
    val medicine get()=if(primary && original) "alpha-medicine.db" else "$physical-medicine.db"
    val generation get()=if(original) ToolLibraryStorage.ORIGINAL else physical.substringAfterLast(".library-").removeSuffix(".db")
}

/** A selection names the private files that may receive current actions. */
internal object ToolLibraryStorage {
    const val DEFAULT="alpha-tools.db"
    const val GENERATION_EXTRA="mini_app_library_generation"
    const val ORIGINAL="original"
    private val gate=Any()
    private val generated=Regex("(.+)\\.library-([0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})\\.db")
    private val sidecars=listOf("-wal","-shm","-journal")
    val preserveOnCorruption=DatabaseErrorHandler { }

    fun intentGeneration(intent: Intent): String = intent.getStringExtra(GENERATION_EXTRA) ?: ORIGINAL

    fun logicalName(name: String): String = generated.matchEntire(name)?.groupValues?.get(1) ?: name

    fun selectionFile(context: Context,logical: String): File {
        require(logical.isNotBlank() && logical.length<=180 && File(logical).name==logical && logical !in setOf(".",".."))
        val key=MessageDigest.getInstance("SHA-256").digest(logical.toByteArray()).toHexString()
        return File(context.noBackupFilesDir,"mini-app-library-$key")
    }

    fun current(context: Context,logical: String=DEFAULT): ToolLibrarySelection = synchronized(gate) {
        readSelection(selectionFile(context, logical), logical)
    }

    private fun readSelection(marker: File,logical: String): ToolLibrarySelection {
        if(!marker.exists() && !File(marker.path+".bak").exists()) return ToolLibrarySelection(logical,logical)
        return try {
            AtomicFile(marker).openRead().use {input ->
                check(input.channel.size() in 1..512)
                val record=JSONObject(input.bufferedReader().readText())
                check(record.keys().asSequence().toSet()==setOf("active","preserved") && record.get("active") is String && record.get("preserved") is String)
                val active=record.getString("active")
                val preserved=record.getString("preserved")
                check(active!=preserved && generated.matches(active) && logicalName(active)==logical && logicalName(preserved)==logical)
                ToolLibrarySelection(logical,active)
            }
        } catch(_: Exception) {throw ToolLibraryUnavailable()}
    }

    fun bind(context: Context,name: String): Result<ToolLibrarySelection> = runCatching {
        val logical=logicalName(name)
        current(context,logical).also {if(name!=logical && name!=it.physical) throw ToolLibraryUnavailable()}
    }

    fun <T> open(context: Context,selection: ToolLibrarySelection,block: ()->T): T = synchronized(gate) {
        requireUsable(context,selection)
        block()
    }

    fun requireCurrent(context: Context, selection: ToolLibrarySelection) {
        if (current(context, selection.logical) != selection) throw ToolLibraryUnavailable()
    }

    fun requireUsable(context: Context,selection: ToolLibrarySelection) = synchronized(gate) {
        requireCurrent(context, selection)
        val source=context.getDatabasePath(selection.physical)
        if(!source.exists()) {
            if(!selection.original || sidecars.any {File(source.path+it).exists()}) throw ToolLibraryUnavailable()
            return@synchronized
        }
        try {
            val header = ByteArray(16)
            RandomAccessFile(source, "r").use { it.readFully(header) }
            check(header.contentEquals("SQLite format 3\u0000".toByteArray()))
        } catch (failure: Exception) {
            throw ToolLibraryUnavailable(failure)
        }
    }

    /** Existing related stores keep their physical identity and cannot reopen after a selection changes. */
    fun requireRelated(context: Context,name: String,suffix: String) {
        relatedSelection(context,name,suffix)?.let {requireUsable(context,it)}
    }

    fun relatedSelection(context: Context,name: String,suffix: String): ToolLibrarySelection? {
        val physical=when {
            name=="alpha-$suffix.db" -> DEFAULT
            name.endsWith(".db-$suffix.db") -> name.removeSuffix("-$suffix.db")
            else -> return null
        }
        val selection=current(context,logicalName(physical))
        if(selection.physical!=physical) throw ToolLibraryUnavailable()
        return selection
    }

    fun production(context: Context): ToolLibrarySelection = current(context).also {requireUsable(context,it)}

}
