package io.github.tuthan.paddock.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The design's line icons, drawn from the same 24-unit paths as the mockups (1.8 stroke, round caps and joins), so no
 * icon library is needed. Tint them with `Icon(…, tint = …)`; every use gives a content description or sits beside
 * a word that says the same.
 */
object PaddockIcons {
    val Settings = icon("settings", circle(12f, 12f, 3.2f), "M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M4.9 19.1L7 17M17 7l2.1-2.1")
    val Back = icon("back", "M15 18l-6-6 6-6")
    val Chevron = icon("chevron", "M9 6l6 6-6 6")
    val Herd = icon("herd", "M12 3l9 5-9 5-9-5 9-5z", "M3 13l9 5 9-5")
    val Activity = icon("activity", "M3 12h4l3-8 4 16 3-8h4")
    val Warning = icon("warning", "M12 3l10 18H2L12 3z", "M12 10v4M12 17.5v.5")
    val Copy = icon("copy", rect(9f, 9f, 11f, 11f, 2f), "M5 15V5h10")
    val Qr = icon("qr", rect(4f, 4f, 6f, 6f), rect(14f, 4f, 6f, 6f), rect(4f, 14f, 6f, 6f), "M14 14h3v3M20 14v6h-6")
    val Key = icon("key", circle(8f, 15f, 4f), "M11 12l9-9M15 5l3 3")
    val Plus = icon("plus", "M12 5v14M5 12h14")
    val Eye = icon("eye", "M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z", circle(12f, 12f, 3f))
    val Pause = icon("pause", "M9 6v12M15 6v12")
    val Send = icon("send", "M12 19V5M5 12l7-7 7 7")
    val Machine = icon("machine", rect(3f, 4f, 18f, 12f, 2f), "M8 20h8M12 16v4")
    val File = icon("file", "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z", "M14 3v5h5")

    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

    private fun rect(x: Float, y: Float, w: Float, h: Float, rx: Float = 0f): String =
        if (rx == 0f) "M$x ${y}h${w}v${h}h${-w}z"
        else "M${x + rx} ${y}h${w - 2 * rx}a$rx $rx 0 0 1 $rx ${rx}v${h - 2 * rx}a$rx $rx 0 0 1 ${-rx} ${rx}h${-(w - 2 * rx)}a$rx $rx 0 0 1 ${-rx} ${-rx}v${-(h - 2 * rx)}a$rx $rx 0 0 1 $rx ${-rx}z"

    private fun icon(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (d in paths) {
            b.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }
}
