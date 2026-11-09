package com.alpha.assistant.capabilities

import android.Manifest
import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.alpha.assistant.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class ContactNumber(val name: String, val number: String)

private fun ContentResolver.contactNumbers(uri: Uri, limit: Int): List<ContactNumber> =
    query(uri, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER), null, null, null)?.use { rows ->
        buildList {
            while (size < limit && rows.moveToNext()) {
                val number = rows.getString(1)?.takeIf(String::isNotBlank) ?: continue
                add(ContactNumber(rows.getString(0).orEmpty(), number))
            }
        }
    }.orEmpty()

@Composable internal fun ContactActions() {
    val context = LocalContext.current
    val revision = platformAccessRevision()
    var permissionResult by remember { mutableIntStateOf(0) }
    val canSearch = remember(revision, permissionResult) {
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedNumber by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedName by rememberSaveable { mutableStateOf("") }
    var fromAddressBook by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var pickerPending by rememberSaveable { mutableStateOf(false) }
    var pickerUri by rememberSaveable { mutableStateOf<String?>(null) }
    val normalizedQuery = query.trim()
    var matches by remember(normalizedQuery, canSearch, revision) { mutableStateOf<List<ContactNumber>?>(null) }
    LaunchedEffect(searchOpen, canSearch, revision, normalizedQuery) {
        if (!canSearch && fromAddressBook) {
            selectedNumber = null
            selectedName = ""
            fromAddressBook = false
        }
        if (!searchOpen || !canSearch || normalizedQuery.isEmpty()) return@LaunchedEffect
        val uri = Uri.withAppendedPath(Phone.CONTENT_FILTER_URI, Uri.encode(normalizedQuery))
        val result = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.contactNumbers(uri, 40).distinct() }
        }
        matches = result.getOrNull()
        if (result.isFailure) error = R.string.contact_search_error
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pickerUri = result.data?.data?.takeIf { it.scheme == "content" }?.toString()
            if (pickerUri == null) error = R.string.contact_picker_error
        }
        if (pickerUri == null) pickerPending = false
    }
    LaunchedEffect(pickerUri) {
        val uri = pickerUri?.let(Uri::parse) ?: return@LaunchedEffect
        val contact = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.contactNumbers(uri, 1).singleOrNull() }.getOrNull()
        }
        if (contact == null) error = R.string.contact_picker_error
        else {
            selectedNumber = contact.number
            selectedName = contact.name
            fromAddressBook = false
        }
        pickerUri = null
        pickerPending = false
    }
    Button(enabled = !pickerPending, onClick = {
        error = null
        selectedNumber = null
        searchOpen = false
        query = ""
        pickerPending = true
        runCatching { picker.launch(Intent(Intent.ACTION_PICK).setType(Phone.CONTENT_TYPE)) }.onFailure {
            pickerPending = false
            error = R.string.contact_picker_error
        }
    }) { Text(stringResource(R.string.contact_choose)) }
    TextButton(enabled = !pickerPending, onClick = { searchOpen = !searchOpen; error = null }) {
        Text(stringResource(if (searchOpen) R.string.contact_search_close else R.string.contact_search_open))
    }
    if (searchOpen) {
        if (!canSearch) {
            AndroidPermissionControl(stringResource(R.string.contact_search_open), listOf(Manifest.permission.READ_CONTACTS),
                onChanged = { permissionResult++ }, divider = false)
        } else {
            OutlinedTextField(query, { query = it; error = null }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.contact_search_hint)) })
            matches?.let { contacts ->
                if (contacts.isEmpty()) Text(stringResource(R.string.contact_search_empty))
                for (contact in contacts) {
                    TextButton(onClick = {
                        selectedName = contact.name
                        selectedNumber = contact.number
                        fromAddressBook = true
                        searchOpen = false
                        query = ""
                    }) { Text(listOf(contact.name, contact.number).filter(String::isNotBlank).joinToString(" · ")) }
                }
            }
        }
    }
    val number = selectedNumber?.takeIf { !fromAddressBook || canSearch }
    if (number != null) {
        if (selectedName.isNotBlank()) Text(selectedName)
        Text(number)
        fun handoff(action: String, scheme: String) {
            error = null
            if (fromAddressBook && context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                permissionResult++
                return
            }
            runCatching { context.startActivity(Intent(action, Uri.fromParts(scheme, number, null))) }
                .onFailure { error = R.string.contact_handoff_error }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { handoff(Intent.ACTION_DIAL, "tel") }) { Text(stringResource(R.string.contact_dial)) }
            TextButton(onClick = { handoff(Intent.ACTION_SENDTO, "smsto") }) { Text(stringResource(R.string.contact_sms)) }
        }
    }
    error?.let { Text(stringResource(it)) }
}
