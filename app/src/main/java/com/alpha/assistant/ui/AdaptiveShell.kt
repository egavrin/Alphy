package com.alpha.assistant.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.Posture
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberSupportingPaneSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import kotlin.math.roundToInt

internal data class ShellPaneLayout(
    val wide: Boolean = false,
    val verticalHinge: Rect? = null,
    val tabletopGap: IntRange? = null,
    val singlePanePadding: PaddingValues = PaddingValues(),
) {
    val paired: Boolean get() = wide || tabletopGap != null
}

@Composable internal fun AdaptiveShellLayout(
    immersive: Boolean,
    pairApp: Boolean,
    systemInsets: WindowInsets,
    content: @Composable (ShellPaneLayout) -> Unit,
) {
    val posture = currentWindowAdaptiveInfoV2().windowPosture
    val hinges = posture.hingeList.filter { it.isSeparating || it.isOccluding }
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    var viewport by remember { mutableStateOf<IntRect?>(null) }
    val foldInsets = if (hinges.isNotEmpty() && !immersive) Modifier.windowInsetsPadding(systemInsets) else Modifier
    BoxWithConstraints(Modifier.fillMaxSize().then(foldInsets)) {
        val safeWidth = if (hinges.isNotEmpty() || immersive) maxWidth else with(density) {
            maxWidth - (systemInsets.getLeft(density, layoutDirection) + systemInsets.getRight(density, layoutDirection)).toDp()
        }
        val placement = density.shellPaneLayout(safeWidth, viewport, posture, pairApp)
        val padding = placement.singlePanePadding
        Box(Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
            viewport = IntRect(coordinates.positionInWindow().round(), coordinates.size)
        }) {
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) { content(placement) }
        }
    }
}

private fun Density.shellPaneLayout(width: Dp, viewport: IntRect?, posture: Posture, pairApp: Boolean): ShellPaneLayout {
    val hinges = posture.hingeList.filter { it.isSeparating || it.isOccluding }
    if (hinges.isEmpty() || viewport == null) return ShellPaneLayout(wide = pairApp && width >= 840.dp)

    val (vertical, horizontal) = hinges.partition { it.isVertical }
    val columns = visibleSpans(viewport.left, viewport.right, vertical.map { it.bounds.left to it.bounds.right })
    val rows = visibleSpans(viewport.top, viewport.bottom, horizontal.map { it.bounds.top to it.bounds.bottom })
    if (pairApp && horizontal.isEmpty() && vertical.size == 1 && columns.size == 2 &&
        columns.all { it.length >= 360.dp.roundToPx() }
    ) return ShellPaneLayout(wide = true, verticalHinge = vertical.single().bounds)
    if (pairApp && posture.isTabletop && vertical.isEmpty() && rows.size == 2 &&
        rows.all { it.length >= 260.dp.roundToPx() }
    ) return ShellPaneLayout(tabletopGap = (rows.first().end - viewport.top)..(rows.last().first - viewport.top))

    val column = columns.maxByOrNull { it.length } ?: return ShellPaneLayout()
    val row = rows.maxByOrNull { it.length } ?: return ShellPaneLayout()
    return ShellPaneLayout(singlePanePadding = PaddingValues.Absolute(
        left = (column.first - viewport.left).toDp(),
        top = (row.first - viewport.top).toDp(),
        right = (viewport.right - column.end).toDp(),
        bottom = (viewport.bottom - row.end).toDp(),
    ))
}

private val IntRange.end: Int get() = last + 1
private val IntRange.length: Int get() = end - first

private fun visibleSpans(start: Int, end: Int, obstructions: List<Pair<Float, Float>>): List<IntRange> = buildList {
    var next = start
    for ((before, after) in obstructions.sortedBy { it.first }) {
        val gapStart = before.roundToInt().coerceIn(start, end)
        if (next < gapStart) add(next until gapStart)
        next = maxOf(next, after.roundToInt().coerceIn(start, end))
    }
    if (next < end) add(next until end)
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable internal fun shellSceneStrategies(layout: ShellPaneLayout): List<SceneStrategy<ShellRoute>> {
    val sceneBack = remember {
        object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = NavigationEventDispatcher()
        }
    }
    DisposableEffect(sceneBack) { onDispose { sceneBack.navigationEventDispatcher.dispose() } }
    if (!layout.paired) return emptyList()
    val directive = PaneScaffoldDirective(
        maxHorizontalPartitions = 2,
        horizontalPartitionSpacerSize = 16.dp,
        maxVerticalPartitions = 1,
        verticalPartitionSpacerSize = 0.dp,
        defaultPanePreferredWidth = 360.dp,
        defaultPanePreferredHeight = 320.dp,
        excludedBounds = listOfNotNull(layout.verticalHinge),
        shouldAutoFocusCurrentDestination = false,
    )
    val listDetail = rememberListDetailSceneStrategy<ShellRoute>(directive = directive, backNavigationBehavior = BackNavigationBehavior.PopLatest)
    val supporting = rememberSupportingPaneSceneStrategy<ShellRoute>(directive = directive, backNavigationBehavior = BackNavigationBehavior.PopLatest)
    return remember(layout.tabletopGap, listDetail, supporting) {
        val tabletop = layout.tabletopGap
        listOf(SceneStrategy { entries ->
            if (entries.last().contentKey != ShellDestination.MiniApps.toString() || entries.size < 3) {
                return@SceneStrategy null
            }
            if (tabletop != null) {
                TabletopScene(entries.takeLast(2), entries.dropLast(1), tabletop)
            } else {
                val strategy = if (entries[entries.lastIndex - 1].contentKey == ShellRoute.NewChat.toString()) supporting else listDetail
                with(strategy) { calculateScene(entries) }?.let { ShellMaterialScene(it, sceneBack) }
            }
        })
    }
}

// Material registers a Back handler inside each scene. The shell keeps Back with NavDisplay and the app entries.
private data class ShellMaterialScene(val scene: Scene<ShellRoute>, val owner: NavigationEventDispatcherOwner) : Scene<ShellRoute> by scene {
    override val content: @Composable () -> Unit = {
        CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) { scene.content() }
    }
}

// Material Adaptive 1.3.0 uses a fixed reflow gap. Tabletop needs the physical hinge's position and size.
private data class TabletopScene(
    override val entries: List<NavEntry<ShellRoute>>,
    override val previousEntries: List<NavEntry<ShellRoute>>,
    val gap: IntRange,
) : Scene<ShellRoute> {
    override val key: Any = "shell-tabletop"
    override val content: @Composable () -> Unit = {
        Layout(content = {
            Box(Modifier.clipToBounds()) { entries.last().Content() }
            Box(Modifier.clipToBounds()) { entries.first().Content() }
        }) { panes, constraints ->
            val topEnd = gap.first.coerceIn(0, constraints.maxHeight)
            val bottomStart = gap.last.coerceIn(topEnd, constraints.maxHeight)
            val top = panes[0].measure(Constraints.fixed(constraints.maxWidth, topEnd))
            val bottom = panes[1].measure(Constraints.fixed(constraints.maxWidth, constraints.maxHeight - bottomStart))
            layout(constraints.maxWidth, constraints.maxHeight) {
                top.place(0, 0)
                bottom.place(0, bottomStart)
            }
        }
    }
}
