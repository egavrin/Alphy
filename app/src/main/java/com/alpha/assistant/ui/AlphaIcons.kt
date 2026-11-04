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
    Chevron("M9 5L16 12L9 19"),
    Search("M15 15L21 21M17 10A7 7 0 1 1 3 10A7 7 0 1 1 17 10"),
    Screen("M3 4H21V17H3ZM8 21H16M12 17V21"),
    Camera("M3 6H7L9 3H15L17 6H21V20H3ZM16 13A4 4 0 1 1 8 13A4 4 0 1 1 16 13"),
    Bell("M6 9A6 6 0 0 1 18 9V15L21 18H3L6 15ZM10 21H14"),
    Check("M5 12L10 17L20 7"),
    Compose("M4 20L5 15L16 4L20 8L9 19ZM14 6L18 10M5 15L9 19"),
    Shield("M12 3L19 6V11C19 15.5 16 19.5 12 21C8 19.5 5 15.5 5 11V6Z"),
    Stop("M6 6H18V18H6Z"),
    More("M6 12A1 1 0 1 1 4 12A1 1 0 1 1 6 12M13 12A1 1 0 1 1 11 12A1 1 0 1 1 13 12M20 12A1 1 0 1 1 18 12A1 1 0 1 1 20 12")
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

/** A status shows with an icon, a color, and text together. The icon takes the text color, so it keeps the contrast of the text on any container. */
@Composable internal fun StatusLine(glyph: Glyph, color: Color, text: String, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AlphaIcon(glyph, color = color)
        Text(text, color = color)
    }
}
