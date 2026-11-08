package com.alpha.assistant.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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
        if (onNewChat != null) GlyphButton(Glyph.NewChat, stringResource(R.string.new_chat), onNewChat)
    }
}

@Composable internal fun ShellModalDrawer(drawerContent: @Composable () -> Unit, drawerState: DrawerState,
    gesturesEnabled: Boolean, content: @Composable () -> Unit) {
    val inheritedMotion = MaterialTheme.motionScheme
    val drawerMotion = remember(inheritedMotion) {
        object : MotionScheme by inheritedMotion {
            override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> =
                tween(205, easing = CubicBezierEasing(0.2f, 0.75f, 0.25f, 1f))

            override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> =
                tween(120, easing = FastOutSlowInEasing)
        }
    }
    MaterialTheme(motionScheme = drawerMotion) {
        ModalNavigationDrawer(
            drawerContent = { MaterialTheme(motionScheme = inheritedMotion, content = drawerContent) },
            drawerState = drawerState,
            gesturesEnabled = gesturesEnabled,
            content = { MaterialTheme(motionScheme = inheritedMotion, content = content) },
        )
    }
}

@Composable internal fun ShellDrawerSheet(drawerState: DrawerState, conversations: List<Conversation>, selectedId: String?,
    destination: ShellDestination?, profileName: String, open: (ShellDestination) -> Unit, openChat: (String) -> Unit, newChat: () -> Unit) {
    BoxWithConstraints {
        // Leave room for the scrim's tap target and accessibility action.
        val sheetWidth = (maxWidth * 0.75f).coerceIn(360.dp, 560.dp)
            .coerceAtMost((maxWidth - 56.dp).coerceAtLeast(0.dp))
        ModalDrawerSheet(drawerState, Modifier.width(sheetWidth)) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 4.dp, bottom = 4.dp)
                .heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.app_name), Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall)
                val searchLabel = stringResource(R.string.search)
                FilledTonalIconButton(
                    onClick = { open(ShellDestination.Search) },
                    modifier = Modifier.size(48.dp).semantics { contentDescription = searchLabel },
                ) { AlphaIcon(Glyph.Search, color = LocalContentColor.current) }
            }
            @Composable fun Destination(target: ShellDestination, glyph: Glyph) {
                NavigationDrawerItem(
                    label = { Text(stringResource(target.title), style = MaterialTheme.typography.labelLarge.merge(RussianHyphenation)) },
                    selected = target == destination,
                    onClick = { open(target) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    icon = { AlphaIcon(glyph) },
                )
            }
            LazyColumn(Modifier.weight(1f)) {
                item { Destination(ShellDestination.MiniApps, Glyph.Grid) }
                item { Destination(ShellDestination.Automations, Glyph.Repeat) }
                item { Destination(ShellDestination.Courses, Glyph.File) }
                item {
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    Text(stringResource(R.string.recent), Modifier.padding(start = 16.dp, bottom = 4.dp).semantics { heading() },
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(conversations, key = { it.id }) { chat ->
                    NavigationDrawerItem(
                        label = { Text(chat.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge) },
                        selected = destination == null && chat.id == selectedId,
                        onClick = { openChat(chat.id) },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                        shape = RoundedCornerShape(12.dp),
                    )
                }
            }
            HorizontalDivider()
            DrawerFooter(profileName, newChat) { open(ShellDestination.Settings) }
        }
    }
}

@Composable private fun DrawerFooter(profileName: String, newChat: () -> Unit, settings: () -> Unit) {
    val shape = RoundedCornerShape(13.dp)
    val padding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
    val settingsLabel = stringResource(R.string.settings)
    val accent = if (isSystemInDarkTheme()) AlphaDarkColors else AlphaLightColors
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        val fontScale = LocalDensity.current.fontScale
        val separateRows = maxWidth <= 280.dp && fontScale >= 1.8f
        val showProfile = maxWidth > 260.dp && fontScale < 1.3f
        val stackIcons = !separateRows && !showProfile
        @Composable fun Actions(action: Modifier) {
            FilledTonalButton(
                newChat, action, shape = shape, contentPadding = padding,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = accent.primaryContainer,
                    contentColor = accent.onPrimaryContainer,
                ),
            ) {
                DrawerActionContent(stackIcons, icon = { AlphaIcon(Glyph.NewChat, color = LocalContentColor.current) }) {
                    Text(stringResource(R.string.new_chat))
                }
            }
            OutlinedButton(settings, action.semantics { contentDescription = settingsLabel }, shape = shape, contentPadding = padding) {
                DrawerActionContent(stackIcons, icon = {
                    Box(
                        Modifier.size(if (showProfile) 28.dp else 24.dp)
                            .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                            .clearAndSetSemantics { hideFromAccessibility() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(profileName.firstOrNull(Char::isLetter)?.uppercase().orEmpty(),
                            color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.labelSmall)
                    }
                }) {
                    if (!showProfile) Text(settingsLabel)
                    else Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f, fill = false)) {
                            Text(settingsLabel)
                            Text(profileName, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        AlphaIcon(Glyph.Chevron, Modifier.size(14.dp))
                    }
                }
            }
        }
        if (separateRows) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Actions(Modifier.fillMaxWidth().heightIn(min = 48.dp))
            }
        } else {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Actions(Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp))
            }
        }
    }
}

@Composable private fun DrawerActionContent(stacked: Boolean, icon: @Composable () -> Unit, label: @Composable () -> Unit) {
    if (stacked) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        icon()
        label()
    } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        icon()
        label()
    }
}

@Composable internal fun GlyphButton(glyph: Glyph, label: String, onClick: () -> Unit) =
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp).semantics { contentDescription = label }) { AlphaIcon(glyph) }
