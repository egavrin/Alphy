package com.alpha.assistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalView
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.findViewTreeNavigationEventDispatcherOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun SheetBackHandler(
    sheet: SheetState,
    scope: CoroutineScope,
    canClose: () -> Boolean = { true },
    close: () -> Unit,
) {
    // Android Back goes to the dialog window's dispatcher.
    val dialogOwner = checkNotNull(LocalView.current.findViewTreeNavigationEventDispatcherOwner())
    CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides dialogOwner) {
        BackHandler {
            if (canClose()) scope.launch {
                sheet.hide()
                if (!sheet.isVisible) close()
            }
        }
    }
}
