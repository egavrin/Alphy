package com.alpha.assistant.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
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

internal fun drawerSelectionMark(dark: Boolean): Color = if (dark) Color(0xFFFFAE77) else Color(0xFFA34418)

@Composable internal fun ShellDrawerSheet(drawerState: DrawerState, conversations: List<Conversation>, selectedId: String?,
    destination: ShellDestination?, profileName: String, open: (ShellDestination) -> Unit, openChat: (String) -> Unit, newChat: () -> Unit) {
    BoxWithConstraints {
        // Leave room for the scrim's tap target and accessibility action.
        val sheetWidth = (maxWidth * 0.75f).coerceIn(360.dp, 560.dp)
            .coerceAtMost((maxWidth - 56.dp).coerceAtLeast(0.dp))
        val dark = isSystemInDarkTheme()
        val colors = MaterialTheme.colorScheme
        val historyLabel = stringResource(R.string.chat_history)
        ModalDrawerSheet(drawerState, Modifier.width(sheetWidth),
            drawerContainerColor = if (dark) Color(0xFF1D1D1D) else Color.White,
            drawerContentColor = colors.onSurface,
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 4.dp, bottom = 4.dp)
                .heightIn(min = 52.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.alphy_drawer_mark), contentDescription = null,
                    modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)))
                Text(stringResource(R.string.app_name), Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall)
                GlyphButton(Glyph.Search, stringResource(R.string.search)) { open(ShellDestination.Search) }
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
                if (conversations.isNotEmpty()) {
                    item {
                        Column(Modifier.padding(horizontal = 16.dp).semantics {
                            contentDescription = historyLabel
                            heading()
                        }) {
                            HorizontalDivider(Modifier.padding(top = 8.dp))
                            Spacer(Modifier.height(18.dp))
                        }
                    }
                }
                items(conversations, key = { it.id }) { chat ->
                    val selected = destination == null && chat.id == selectedId
                    NavigationDrawerItem(
                        label = { Text(chat.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge) },
                        selected = selected,
                        onClick = { openChat(chat.id) },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                        badge = if (selected) {
                            { AlphaIcon(Glyph.Check, Modifier.size(18.dp), color = drawerSelectionMark(dark)) }
                        } else null,
                        shape = RoundedCornerShape(12.dp),
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = colors.primaryContainer, selectedTextColor = colors.onPrimaryContainer,
                        ),
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
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        val fontScale = LocalDensity.current.fontScale
        val separateRows = maxWidth <= 280.dp && fontScale >= 1.8f
        val showProfile = maxWidth > 260.dp && fontScale < 1.3f
        val stackIcons = !separateRows && !showProfile
        @Composable fun Actions(action: Modifier) {
            Button(newChat, action, shape = shape, contentPadding = padding) {
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
