package com.alpha.assistant.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal enum class Glyph(val path: String) {
    Menu("M4 6H20M4 12H20M4 18H20"),
    History("M3 11A9 9 0 1 1 5.5 18M3 4V11H10M12 7V12L15 14"),
    Plus("M12 5V19M5 12H19"),
    Arrow("M12 19V5M5 12L12 5L19 12"),
    Close("M6 6L18 18M18 6L6 18"),
    Back("M19 12H5M12 5L5 12L12 19"),
    Cloud("M7 18H18A4 4 0 0 0 18 10A6 6 0 0 0 6 9A4.5 4.5 0 0 0 7 18"),
    Mic("M9 5A3 3 0 0 1 15 5V11A3 3 0 0 1 9 11ZM5 10V11A7 7 0 0 0 19 11V10M12 18V22M8 22H16"),
    Grid("M3 3H10V10H3ZM14 3H21V10H14ZM3 14H10V21H3ZM14 14H21V21H14Z"),
    Spark("M12 3L14.5 9.5L21 12L14.5 14.5L12 21L9.5 14.5L3 12L9.5 9.5ZM20 2V6M18 4H22"),
    Calendar("M5 4H19Q21 4 21 6V19Q21 21 19 21H5Q3 21 3 19V6Q3 4 5 4ZM3 10H21M8 2V6M16 2V6M8 14H10M14 14H16M8 18H10"),
    Repeat("M3 7H17L14 4M17 7L14 10M21 17H7L10 14M7 17L10 20M3 7V13M21 17V11"),
    Settings("M4 6H20M4 12H20M4 18H20M8 3V9M16 9V15M9 15V21"),
    File("M6 3H14L19 8V21H6ZM14 3V8H19M9 12H16M9 16H16"),
    Link("M10 14L14 10M8 16L6 18A4 4 0 0 1 0.5 12.5L5 8A4 4 0 0 1 10.5 8M13.5 16A4 4 0 0 0 19 16L23.5 11.5A4 4 0 0 0 18 6L16 8"),
    Profile("M12 10A3 3 0 1 0 12 4A3 3 0 1 0 12 10ZM4 21V19A5 5 0 0 1 9 14H15A5 5 0 0 1 20 19V21"),
    Notebook("M7 3H18Q20 3 20 5V19Q20 21 18 21H7Q5 21 5 19V5Q5 3 7 3ZM9 3V21M12 8H17M12 12H17"),
    Refresh("M3 12A9 9 0 0 1 18.4 5.6L21 8M21 3V8H16M21 12A9 9 0 0 1 5.6 18.4L3 16M3 21V16H8"),
    Chevron("M9 5L16 12L9 19"),
    Search("M15 15L21 21M17 10A7 7 0 1 1 3 10A7 7 0 1 1 17 10"),
    Chat("M5 4H19Q21 4 21 6V15Q21 17 19 17H11L6 21V17H5Q3 17 3 15V6Q3 4 5 4Z"),
    Screen("M3 4H21V17H3ZM8 21H16M12 17V21"),
    Fullscreen("M4 9V4H9M15 4H20V9M20 15V20H15M9 20H4V15"),
    Camera("M3 6H7L9 3H15L17 6H21V20H3ZM16 13A4 4 0 1 1 8 13A4 4 0 1 1 16 13"),
    Photo("M5 3H19Q21 3 21 5V19Q21 21 19 21H5Q3 21 3 19V5Q3 3 5 3ZM3 17L9 11L13 15L16 12L21 17M15 8A1 1 0 1 1 17 8A1 1 0 1 1 15 8"),
    Clipboard("M8 5H5Q4 5 4 6V20Q4 21 5 21H19Q20 21 20 20V6Q20 5 19 5H16M8 3H16V7H8Z"),
    Bell("M6 9A6 6 0 0 1 18 9V15L21 18H3L6 15ZM10 21H14"),
    Check("M5 12L10 17L20 7"),
    Compose("M4 20L5 15L16 4L20 8L9 19ZM14 6L18 10M5 15L9 19"),
    NewChat("M11 4H6Q4 4 4 6V18Q4 20 6 20H18Q20 20 20 18V13M9 15L9.8 11.7L18.4 3.1Q19.7 1.8 20.9 3.1Q22.2 4.4 20.9 5.6L12.3 14.2Z"),
    Shield("M12 3L19 6V11C19 15.5 16 19.5 12 21C8 19.5 5 15.5 5 11V6Z"),
    Stop("M6 6H18V18H6Z"),
    Alarm("M20 13A8 8 0 1 1 4 13A8 8 0 1 1 20 13ZM12 8V13L15 15M3 6L6 3M18 3L21 6M6 19L4 21M18 19L20 21"),
    Clock("M21 12A9 9 0 1 1 3 12A9 9 0 1 1 21 12ZM12 7V12L16 14"),
    Filter("M3 4H21L15 12V19L9 21V12Z"),
    Bolt("M13 2L4 14H11L10 22L20 10H13Z"),
    Pin("M20 10C20 15 12 22 12 22S4 15 4 10A8 8 0 0 1 20 10ZM15 10A3 3 0 1 1 9 10A3 3 0 1 1 15 10Z"),
    Pause("M9 5V19M15 5V19"),
    Play("M7 4L20 12L7 20Z"),
    Flask("M9 3H15M10 3V10L4 20H20L14 10V3M7 15H17"),
    Trash("M3 6H21M9 6V3H15V6M5 6L6 21H18L19 6M10 10V17M14 10V17"),
    Down("M6 9L12 15L18 9"),
    Calculator("M6 3H18Q20 3 20 5V19Q20 21 18 21H6Q4 21 4 19V5Q4 3 6 3ZM7 6H17V10H7ZM8 14H9M12 14H13M16 14H17M8 18H9M12 18H13M16 18H17"),
    Cart("M2 4H5L7.8 15H19L22 7H6M9 18A1.5 1.5 0 1 0 9 21A1.5 1.5 0 1 0 9 18ZM18 18A1.5 1.5 0 1 0 18 21A1.5 1.5 0 1 0 18 18Z"),
    Suitcase("M5 7H19Q21 7 21 9V19Q21 21 19 21H5Q3 21 3 19V9Q3 7 5 7ZM9 7V5Q9 3 11 3H13Q15 3 15 5V7M8 7V21M16 7V21"),
    Timer("M9 2H15M12 2V5M18 5L20 7M12 8V12M12 5A8 8 0 1 1 12 21A8 8 0 1 1 12 5Z"),
    Info("M12 3A9 9 0 1 1 12 21A9 9 0 1 1 12 3ZM12 11V16M12 8H12.01"),
    Layers("M12 3L21 8L12 13L3 8ZM3 12L12 17L21 12M3 16L12 21L21 16"),
    Export("M12 15V3M8 7L12 3L16 7M8 11H5V21H19V11H16"),
    Archive("M4 4H20V8H4ZM6 8V20H18V8M10 12H14"),
    More("M5.85 12A0.85 0.85 0 1 1 4.15 12A0.85 0.85 0 1 1 5.85 12M12.85 12A0.85 0.85 0 1 1 11.15 12A0.85 0.85 0 1 1 12.85 12M19.85 12A0.85 0.85 0 1 1 18.15 12A0.85 0.85 0 1 1 19.85 12"),
    MoreVertical("M12.85 5A0.85 0.85 0 1 1 11.15 5A0.85 0.85 0 1 1 12.85 5M12.85 12A0.85 0.85 0 1 1 11.15 12A0.85 0.85 0 1 1 12.85 12M12.85 19A0.85 0.85 0 1 1 11.15 19A0.85 0.85 0 1 1 12.85 19")
}

@Composable internal fun AlphaIcon(glyph: Glyph, modifier: Modifier = Modifier, accent: Boolean = false,
    color: Color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) {
    Canvas(modifier.size(21.dp)) {
        val path = PathParser().parsePathString(glyph.path).toPath()
        scale(size.width / 24f, size.height / 24f, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            drawPath(path, color, style = Stroke(1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** TalkBack announces a [StatusLine] with this modifier when it shows or changes. A voice line gets none, because TalkBack speech can go into the recognizer. */
internal val PoliteLiveRegion = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }

@Composable internal fun StatusLine(glyph: Glyph, color: Color, text: String, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AlphaIcon(glyph, color = color)
        Text(text, color = color)
    }
}
