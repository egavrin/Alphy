package com.alpha.assistant.network.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alpha.assistant.R
import com.alpha.assistant.ui.PoliteLiveRegion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The grant state comes from the last confirmed write, not from a live session. */
internal data class MiniAppNetworkAccessApp(val id: String, val title: String, val granted: Boolean)

internal enum class NetworkGrantWrite { PENDING, ALLOW_FAILED, REVOKE_FAILED }

/**
 * Retain this host with its main scope for the lifetime of the store.
 * Give each app a unique, stable ID within the store.
 * Publish the saved grant in [apps] before [writeGrant] returns success. Keep the saved state on failure.
 * Return a failed result for a failed write, even when revocation stops current requests.
 */
internal class MiniAppNetworkAccessHost(
    val apps: StateFlow<List<MiniAppNetworkAccessApp>>,
    private val scope: CoroutineScope,
    private val writeGrant: suspend (appId: String, granted: Boolean) -> Result<Unit>,
) {
    private val writes = mutableStateMapOf<String, NetworkGrantWrite>()

    fun writeState(appId: String): NetworkGrantWrite? = writes[appId]

    fun setGranted(appId: String, granted: Boolean) {
        scope.launch {
            val app = apps.value.firstOrNull { it.id == appId } ?: return@launch
            if (app.granted == granted || writes[appId] == NetworkGrantWrite.PENDING) return@launch
            writes[appId] = NetworkGrantWrite.PENDING
            try {
                if (writeGrant(appId, granted).isFailure) {
                    writes[appId] = if (granted) NetworkGrantWrite.ALLOW_FAILED else NetworkGrantWrite.REVOKE_FAILED
                }
            } finally {
                if (writes[appId] == NetworkGrantWrite.PENDING) writes.remove(appId)
            }
        }
    }
}

/** Place this section inside the caller's scroll container. Pass null if the host cannot manage grants. */
@Composable
internal fun MiniAppNetworkAccessSection(host: MiniAppNetworkAccessHost?) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.mini_app_network_title),
            Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
        )
        Text(stringResource(R.string.mini_app_network_explain_grant))
        Text(stringResource(R.string.mini_app_network_explain_scope))
        Text(stringResource(R.string.mini_app_network_explain_data))
        Text(stringResource(R.string.mini_app_network_explain_android))
        Text(stringResource(R.string.mini_app_network_explain_ai))
        if (host == null) {
            Text(stringResource(R.string.mini_app_network_unavailable), PoliteLiveRegion)
        } else {
            val apps by host.apps.collectAsStateWithLifecycle()
            if (apps.isEmpty()) Text(stringResource(R.string.mini_app_network_empty))
            for (app in apps) key(app.id) {
                HorizontalDivider()
                MiniAppNetworkAccessRow(app, host.writeState(app.id)) { host.setGranted(app.id, !app.granted) }
            }
        }
    }
}

@Composable
private fun MiniAppNetworkAccessRow(app: MiniAppNetworkAccessApp, write: NetworkGrantWrite?, change: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(app.title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(if (app.granted) R.string.mini_app_network_granted else R.string.mini_app_network_not_granted),
            PoliteLiveRegion,
        )
        when (write) {
            NetworkGrantWrite.PENDING -> Text(stringResource(R.string.mini_app_network_pending), PoliteLiveRegion)
            NetworkGrantWrite.ALLOW_FAILED, NetworkGrantWrite.REVOKE_FAILED -> Text(
                stringResource(if (write == NetworkGrantWrite.ALLOW_FAILED) R.string.mini_app_network_allow_failed else R.string.mini_app_network_revoke_failed),
                PoliteLiveRegion,
                color = MaterialTheme.colorScheme.error,
            )
            null -> Unit
        }
        OutlinedButton(
            onClick = change,
            modifier = Modifier.heightIn(min = 48.dp),
            enabled = write != NetworkGrantWrite.PENDING,
        ) {
            Text(stringResource(if (app.granted) R.string.mini_app_network_revoke else R.string.mini_app_network_allow, app.title))
        }
    }
}
