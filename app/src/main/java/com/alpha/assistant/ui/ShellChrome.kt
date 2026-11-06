package com.alpha.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DrawerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alpha.assistant.R
import com.alpha.assistant.data.Conversation

@Composable internal fun ShellTopBar(title: String?, onMenu: () -> Unit, onNewChat: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        GlyphButton(Glyph.Menu, stringResource(R.string.menu), onMenu)
        if (title == null) Spacer(Modifier.weight(1f))
        else Text(title, Modifier.weight(1f).padding(horizontal = 8.dp).semantics { heading() }, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (onNewChat != null) GlyphButton(Glyph.Compose, stringResource(R.string.new_chat), onNewChat)
    }
}

@Composable internal fun ShellDrawerSheet(drawerState: DrawerState, conversations: List<Conversation>, selectedId: String?,
    destination: ShellDestination?, profileName: String, open: (ShellDestination) -> Unit, openChat: (String) -> Unit, newChat: () -> Unit) {
    // A full-width sheet would cover the scrim, and Compose then gives the scrim no accessibility node.
    ModalDrawerSheet(drawerState, Modifier.widthIn(max = LocalConfiguration.current.screenWidthDp.dp - 56.dp)) {
        @Composable fun Item(label: String, glyph: Glyph, selected: Boolean, onClick: () -> Unit) = NavigationDrawerItem({ Text(label, style = MaterialTheme.typography.labelLarge.merge(RussianHyphenation)) }, selected, onClick,
            Modifier.padding(NavigationDrawerItemDefaults.ItemPadding), icon = { AlphaIcon(glyph) })
        @Composable fun Destination(target: ShellDestination, glyph: Glyph) = Item(stringResource(target.title), glyph, target == destination) { open(target) }
        // The parts below the list have no weight, so the column measures them first and a short window clips only the list.
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 12.dp)) {
            item { Destination(ShellDestination.Search, Glyph.Search) }
            item { Item(stringResource(R.string.new_chat), Glyph.Compose, false, newChat) }
            item { Destination(ShellDestination.MiniApps, Glyph.Grid) }
            item { Destination(ShellDestination.Automations, Glyph.Repeat) }
            item { Destination(ShellDestination.Courses, Glyph.File) }
            item {
                Text(stringResource(R.string.recent), Modifier.padding(start = 28.dp, top = 16.dp, bottom = 8.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(conversations, key = { it.id }) { chat ->
                NavigationDrawerItem({ Text(chat.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge) }, destination == null && chat.id == selectedId,
                    { openChat(chat.id) }, Modifier.padding(NavigationDrawerItemDefaults.ItemPadding))
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            // The name follows, so the initial is decorative for TalkBack.
            Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape).clearAndSetSemantics { hideFromAccessibility() }, contentAlignment = Alignment.Center) {
                Text(profileName.firstOrNull(Char::isLetter)?.uppercase().orEmpty(), color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.titleMedium)
            }
            Text(profileName, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            GlyphButton(Glyph.Settings, stringResource(R.string.settings)) { open(ShellDestination.Settings) }
        }
    }
}

@Composable internal fun GlyphButton(glyph: Glyph, label: String, onClick: () -> Unit) =
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp).semantics { contentDescription = label }) { AlphaIcon(glyph) }
