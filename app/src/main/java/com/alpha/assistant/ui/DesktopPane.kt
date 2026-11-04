package com.alpha.assistant.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.alpha.assistant.R
import java.time.LocalTime

internal const val MY_DAY_REQUEST = "Обновляй Мой день из календаря каждый день в 08:00"

@StringRes internal fun greetingRes(hour: Int): Int = when (hour) {
    in 5..11 -> R.string.greeting_morning
    in 12..17 -> R.string.greeting_day
    in 18..22 -> R.string.greeting_evening
    else -> R.string.greeting_night
}

/** The home screen. The «Сейчас» strip reads only the key status, so it sends nothing to DeepSeek. */
@Composable internal fun DesktopPane(model: ShellViewModel, open: (ShellDestination) -> Unit, automation: (String) -> Unit) {
    val greeting = stringResource(greetingRes(LocalTime.now().hour))
    val name = model.snapshot.userName.trim()
    Column(Modifier.widthIn(max = 620.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(if (name.isEmpty()) greeting else stringResource(R.string.greeting_named, greeting, name), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.desktop_prompt), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.now_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        if (model.keyStatus == KeyStatus.Ready) NowCard(Glyph.Grid, R.string.now_mini_app_title, R.string.now_mini_app_text) { open(ShellDestination.MiniApps) }
        else NowCard(Glyph.Cloud, R.string.now_connect_title, R.string.now_connect_text) { open(ShellDestination.Settings) }
        NowCard(Glyph.Calendar, R.string.now_day_title, R.string.now_day_text) { automation(MY_DAY_REQUEST) }
    }
}

@Composable private fun NowCard(glyph: Glyph, @StringRes title: Int, @StringRes text: Int, onClick: () -> Unit) =
    Card(onClick, Modifier.fillMaxWidth().heightIn(min = 72.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AlphaIcon(glyph, color = LocalContentColor.current)
            Column {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
