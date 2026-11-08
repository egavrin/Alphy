package com.alpha.assistant.data

import org.json.JSONObject

/** An invocation snapshot contains only fields that can be previewed and sent. */
data class ScreenContext(
    val sourcePackage: String? = null,
    val appLabel: String? = null,
    val windowTitle: String? = null,
    val visibleText: String? = null,
    val selection: String? = null,
    val pageUrl: String? = null,
    val pageUrlSource: String? = null,
    val contentType: String? = null,
    val contentTitle: String? = null,
    val contentDescription: String? = null,
    val pageText: String? = null,
    val truncated: Boolean = false,
    val pageTruncated: Boolean = false,
) {
    internal fun json(): JSONObject = JSONObject()
        .put("sourcePackage", sourcePackage).put("appLabel", appLabel).put("windowTitle", windowTitle)
        .put("visibleText", visibleText).put("selection", selection)
        .put("pageUrl", pageUrl).put("pageUrlSource", pageUrlSource)
        .put("contentType", contentType).put("contentTitle", contentTitle)
        .put("contentDescription", contentDescription).put("pageText", pageText)
        .put("truncated", truncated).put("pageTruncated", pageTruncated)

    internal val screenDetails: List<String> get() =
        listOfNotNull(appLabel, sourcePackage, windowTitle, contentType, contentTitle, contentDescription, pageUrl)

    internal fun withoutPage() = copy(pageText = null, pageTruncated = false)

    internal fun onlyPage() = ScreenContext(pageText = pageText, pageTruncated = pageTruncated)

    internal fun nonempty(): ScreenContext? = takeUnless { it == ScreenContext() }

    internal companion object {
        fun fromJson(json: JSONObject): ScreenContext {
            fun value(key: String) = json.opt(key) as? String
            return ScreenContext(
                sourcePackage = value("sourcePackage"), appLabel = value("appLabel"),
                windowTitle = value("windowTitle"), visibleText = value("visibleText"), selection = value("selection"),
                pageUrl = value("pageUrl"), pageUrlSource = value("pageUrlSource"), contentType = value("contentType"),
                contentTitle = value("contentTitle"), contentDescription = value("contentDescription"),
                pageText = value("pageText"), truncated = json.optBoolean("truncated"),
                pageTruncated = json.optBoolean("pageTruncated"),
            )
        }
    }
}

internal fun String.screenPrefix(limit: Int): String {
    val end = length.coerceAtMost(limit)
    return substring(0, if (end > 0 && this[end - 1].isHighSurrogate()) end - 1 else end)
}
