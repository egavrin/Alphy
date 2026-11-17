package com.alpha.assistant.cloud

import java.net.URI

/** Base URL of each provider. Tests replace it with a loopback server. */
internal fun interface Endpoints {
    fun base(provider: AiProvider): String

    companion object {
        val PRODUCTION = Endpoints { it.profile.baseUrl }
    }
}

/** Returns the base URL only when it is the provider production URL or plain HTTP on loopback. */
internal fun Endpoints.checked(provider: AiProvider): String {
    val base = base(provider)
    val uri = URI(base)
    require(uri.userInfo == null && uri.query == null && uri.fragment == null)
    require(base == provider.profile.baseUrl || (uri.scheme == "http" && uri.host in listOf("127.0.0.1", "localhost")))
    return base
}
