package com.alpha.assistant.automations

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.alpha.assistant.R
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable internal fun AutomationChoice(value: String, options: Map<String,String>, choose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick={expanded=true},enabled=options.isNotEmpty(),modifier=Modifier.heightIn(min=48.dp)) {
            Text(options[value] ?: value.ifBlank { stringResource(R.string.automation_editor_pick_option) })
        }
        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
            options.forEach { (id,label) -> DropdownMenuItem(text={Text(label)},onClick={expanded=false;choose(id)}) }
        }
    }
}

@Composable internal fun AutomationField(label: String, value: String, change: (String) -> Unit) {
    OutlinedTextField(value=value,onValueChange=change,label={Text(label)},modifier=Modifier.fillMaxWidth(),singleLine=true)
}

private val pickerTimeFormat=DateTimeFormatter.ofPattern("HH:mm",Locale.ROOT)

@Composable internal fun AutomationTime(label: String, time: LocalTime, change: (LocalTime) -> Unit) {
    val context=LocalContext.current
    OutlinedButton(onClick={
        TimePickerDialog(context,R.style.Theme_Alpha_CompactTimePicker,{_,hour,minute -> change(LocalTime.of(hour,minute))},
            time.hour,time.minute,DateFormat.is24HourFormat(context)).show()
    },modifier=Modifier.heightIn(min=48.dp)) {
        Text(stringResource(R.string.automation_editor_picker_value,label,time.format(pickerTimeFormat)))
    }
}

@Composable internal fun AutomationDate(label: String, date: LocalDate, change: (LocalDate) -> Unit) {
    val context=LocalContext.current
    OutlinedButton(onClick={
        DatePickerDialog(context,{_,year,month,day -> change(LocalDate.of(year,month+1,day))},date.year,date.monthValue-1,date.dayOfMonth).show()
    },modifier=Modifier.heightIn(min=48.dp)) {
        Text(stringResource(R.string.automation_editor_picker_value,label,date.toString()))
    }
}

@Composable internal fun AutomationZone(zone: String, change: (String) -> Unit) {
    val deviceZone=stringResource(R.string.automation_editor_device_zone,ZoneId.systemDefault().id)
    OutlinedTextField(value=zone.takeUnless { it=="device" }.orEmpty(),onValueChange={change(it.ifBlank { "device" })},
        label={Text(stringResource(R.string.automation_editor_zone))},placeholder={Text(deviceZone)},
        supportingText={if(zone=="device") Text(deviceZone)},modifier=Modifier.fillMaxWidth(),singleLine=true)
}
