package com.alpha.assistant.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.InetAddresses
import android.os.Build
import android.security.NetworkSecurityPolicy
import com.alpha.assistant.network.NetworkAccessDenial
import com.alpha.assistant.network.NetworkDestinationAccess
import com.alpha.assistant.network.NetworkTarget
import java.net.InetAddress
import java.net.InetSocketAddress

/** Android remains the authority for both cleartext and socket-level LAN enforcement. */
internal class AndroidMiniAppNetworkAccess(
    private val context: Context,
    private val sdk: Int = Build.VERSION.SDK_INT,
    private val cleartext: (String) -> Boolean = { NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(it) },
    private val onLink: (InetAddress) -> Boolean = { address ->
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager?.activeNetwork
        network != null && manager.getLinkProperties(network)?.routes
            ?.filter {it.matches(address)}?.maxByOrNull {it.destination.prefixLength}
            ?.let {!it.isDefaultRoute && !it.hasGateway()} == true
    },
) : NetworkDestinationAccess {
    override fun checkTarget(target: NetworkTarget): NetworkAccessDenial? = platform {
        if (target.scheme == "http" && !cleartext(target.host)) return@platform NetworkAccessDenial.CLEARTEXT_POLICY
        if (!requiresLocalPermission()) return@platform null
        val host=target.host.trimEnd('.')
        if (host.endsWith(".local",ignoreCase=true)) return@platform NetworkAccessDenial.LOCAL_NETWORK_PERMISSION
        val address=host.trim('[',']')
        if (InetAddresses.isNumericAddress(address)) localDenial(InetAddresses.parseNumericAddress(address)) else null
    }

    override fun checkDestination(target: NetworkTarget, peer: InetSocketAddress): NetworkAccessDenial? = platform {
        val address=peer.address ?: return@platform NetworkAccessDenial.POLICY_UNAVAILABLE
        if (requiresLocalPermission()) localDenial(address) else null
    }

    private inline fun platform(check: () -> NetworkAccessDenial?): NetworkAccessDenial? =
        try { check() } catch (_: RuntimeException) { NetworkAccessDenial.POLICY_UNAVAILABLE }

    private fun requiresLocalPermission() = sdk >= Build.VERSION_CODES.CINNAMON_BUN &&
        context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK)!=PackageManager.PERMISSION_GRANTED

    private fun localDenial(address: InetAddress): NetworkAccessDenial? {
        if (address.isLoopbackAddress) return null
        val bytes=address.address
        val uniqueLocalV6=bytes.size==16 && (bytes[0].toInt() and 0xfe)==0xfc
        val broadcastV4=bytes.size==4 && bytes.all {it.toInt()==-1}
        return if(address.isAnyLocalAddress || address.isLinkLocalAddress || address.isSiteLocalAddress ||
            address.isMulticastAddress || uniqueLocalV6 || broadcastV4 || onLink(address)) NetworkAccessDenial.LOCAL_NETWORK_PERMISSION else null
    }
}
