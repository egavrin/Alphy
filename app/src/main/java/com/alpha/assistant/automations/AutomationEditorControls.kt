package com.alpha.assistant.automations

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.alpha.assistant.R
import com.alpha.assistant.ui.AlphaIcon
import com.alpha.assistant.ui.Glyph
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable internal fun AutomationChoice(
    value: String, options: Map<String, String>, label: String? = null, choose: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    AutomationControlRow(label) { modifier ->
        Box(modifier) {
            AutomationControlButton(
                options[value] ?: value.ifBlank { stringResource(R.string.automation_editor_pick_option) },
                Modifier.fillMaxWidth(), enabled = options.isNotEmpty(),
            ) { expanded = true }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (id, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; choose(id) })
                }
            }
        }
    }
}

@Composable internal fun AutomationField(label: String, value: String, change: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, Modifier.semantics { hideFromAccessibility() }, style = MaterialTheme.typography.labelLarge)
        AutomationInput(label, value, Modifier.fillMaxWidth(), change = change)
    }
}

@Composable private fun AutomationInput(
    label: String, value: String, modifier: Modifier, placeholder: String? = null, change: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = change,
        modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = label },
        placeholder = if (placeholder != null) ({ Text(placeholder) }) else null,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        shape = MaterialTheme.shapes.small,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        ),
    )
}

private val pickerTimeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

@Composable internal fun AutomationTime(label: String, time: LocalTime, change: (LocalTime) -> Unit) {
    val context = LocalContext.current
    AutomationPicker(label, time.format(pickerTimeFormat)) {
        TimePickerDialog(context, R.style.Theme_Alpha_CompactTimePicker,
            { _, hour, minute -> change(LocalTime.of(hour, minute)) }, time.hour, time.minute,
            DateFormat.is24HourFormat(context)).show()
    }
}

@Composable internal fun AutomationDate(label: String, date: LocalDate, change: (LocalDate) -> Unit) {
    val context = LocalContext.current
    AutomationPicker(label, date.toString()) {
        DatePickerDialog(context, { _, year, month, day -> change(LocalDate.of(year, month + 1, day)) },
            date.year, date.monthValue - 1, date.dayOfMonth).show()
    }
}

@Composable internal fun AutomationZone(zone: String, change: (String) -> Unit) {
    val label = stringResource(R.string.automation_editor_zone)
    val deviceZone = stringResource(R.string.automation_editor_device_zone, ZoneId.systemDefault().id)
    AutomationControlRow(label) { modifier ->
        AutomationInput(label, zone.takeUnless { it == "device" }.orEmpty(), modifier, placeholder = deviceZone) {
            change(it.ifBlank { "device" })
        }
    }
}

@Composable private fun AutomationPicker(label: String, value: String, open: () -> Unit) {
    val description = stringResource(R.string.automation_editor_picker_value, label, value)
    AutomationControlRow(label) { modifier ->
        AutomationControlButton(value, modifier.semantics { contentDescription = description }, open = open)
    }
}

@Composable private fun AutomationControlRow(label: String?, content: @Composable (Modifier) -> Unit) {
    if (label == null) {
        content(Modifier.fillMaxWidth())
    } else {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, Modifier.weight(0.40f), style = MaterialTheme.typography.labelLarge)
            content(Modifier.weight(0.60f))
        }
    }
}

@Composable private fun AutomationControlButton(value: String, modifier: Modifier, enabled: Boolean = true, open: () -> Unit) {
    OutlinedButton(
        onClick = open,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = 12.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(6.dp))
        AlphaIcon(Glyph.Down, Modifier.size(16.dp), color = LocalContentColor.current)
    }
}
