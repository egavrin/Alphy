package com.alpha.assistant.ui

import android.Manifest
import android.app.KeyguardManager
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** A permission result belongs to one active reply, even if Android returns it after cancellation. */
@Composable internal fun WeatherLocationAccess(model: ShellViewModel) {
    val context = LocalContext.current
    val owner = context as? ComponentActivity
    val surface = owner ?: context
    var resumed by remember { mutableStateOf(owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true) }
    var launchedId by remember { mutableStateOf<String?>(null) }
    var grantedId by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        val id = launchedId
        launchedId = null
        if(id != null) {
            if(allowed) grantedId = id else model.weatherPermissionResult(id, false)
        }
    }
    DisposableEffect(owner, model) {
        model.weatherVisibilityChanged(resumed, surface)
        val observer = LifecycleEventObserver { _, event ->
            when(event) {
                Lifecycle.Event.ON_RESUME -> { resumed = true; model.weatherVisibilityChanged(true, surface) }
                Lifecycle.Event.ON_PAUSE -> { resumed = false; model.weatherVisibilityChanged(false, surface) }
                Lifecycle.Event.ON_STOP -> { resumed = false; model.weatherHidden(surface) }
                else -> Unit
            }
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer); model.weatherHidden(surface) }
    }
    LaunchedEffect(model.weatherPermissionId, resumed, launchedId, grantedId) {
        if(!resumed || !context.getSystemService(PowerManager::class.java).isInteractive || context.getSystemService(KeyguardManager::class.java).isDeviceLocked) return@LaunchedEffect
        grantedId?.let { id -> model.weatherPermissionResult(id, true); grantedId = null }
        val id = model.weatherPermissionId
        if(id != null && launchedId == null && grantedId == null) {
            launchedId = id
            permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }
}
