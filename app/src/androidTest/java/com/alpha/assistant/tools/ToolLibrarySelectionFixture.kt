package com.alpha.assistant.tools

import android.content.Context
import org.json.JSONObject
import java.util.UUID

/** Recreates a library selection already written by an earlier build. */
internal fun selectOtherLibraryForTest(context: Context, old: ToolLibrarySelection) {
    val active = "${old.logical}.library-${UUID.randomUUID()}.db"
    val temporary = "${old.logical}.empty"
    ToolStore(context, temporary).use { check(it.tools().isEmpty()) }
    check(context.getDatabasePath(temporary).renameTo(context.getDatabasePath(active)))
    ToolLibraryStorage.selectionFile(context, old.logical)
        .writeText(JSONObject().put("active", active).put("preserved", old.physical).toString())
    ToolNetworkGrants.retireLibrary(context, old)
}
