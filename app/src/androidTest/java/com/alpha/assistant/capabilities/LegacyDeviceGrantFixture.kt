package com.alpha.assistant.capabilities

import com.alpha.assistant.tools.ToolStore

internal fun ToolStore.seedLegacyDeviceGrant(owner: String, capability: String, granted: Boolean) {
    writableDatabase.execSQL("INSERT OR REPLACE INTO capability_grants(owner,capability,granted) VALUES(?,?,?)",
        arrayOf<Any>(owner, capability, if (granted) 1 else 0))
}

internal fun ToolStore.legacyDeviceGrant(owner: String, capability: String): Boolean? =
    readableDatabase.rawQuery("SELECT granted FROM capability_grants WHERE owner=? AND capability=?", arrayOf(owner, capability))
        .use { if (it.moveToFirst()) it.getInt(0) != 0 else null }
