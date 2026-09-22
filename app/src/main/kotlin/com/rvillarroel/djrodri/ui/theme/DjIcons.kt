package com.rvillarroel.djrodri.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * One icon family, drawn for this app on a 24-unit grid: thin round strokes for everything, solid shapes only for
 * transport (play, pause, previous, next), where they must read at a glance. Tinted by `Icon`.
 */
object DjIcons {
    val Home = line("Home", "M4 10.5 12 4l8 6.5V19a1 1 0 0 1-1 1h-4v-6H9v6H5a1 1 0 0 1-1-1z")
    val Search = line("Search", "M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14z", "M16.2 16.2 20 20")
    val Library = line("Library", "M5 4v16", "M9.5 4v16", "M14 5.2l4.6 14.4")
    val Party = line("Party", "M13.2 3 5 13.5h6.2L10.3 21 18.8 10.5h-6.1z")
    val Settings = line(
        "Settings",
        "M4 7h4", "M12 7h8", "M10 5a2 2 0 1 0 0 4a2 2 0 1 0 0-4z",
        "M4 17h8", "M16 17h4", "M14 15a2 2 0 1 0 0 4a2 2 0 1 0 0-4z",
    )
    val Back = line("Back", "M15 5 8 12l7 7")
    val ChevronRight = line("ChevronRight", "M9.5 5.5 16 12l-6.5 6.5")
    val Close = line("Close", "M6 6l12 12", "M18 6 6 18")
    val Plus = line("Plus", "M12 5v14", "M5 12h14")
    val Mic = line("Mic", "M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3z", "M5.5 11a6.5 6.5 0 0 0 13 0", "M12 17.5V21")
    val Heart = line("Heart", "M12 20s-7.5-4.6-7.5-10.2A4.3 4.3 0 0 1 12 7.2a4.3 4.3 0 0 1 7.5 2.6C19.5 15.4 12 20 12 20z")
    val Clock = line("Clock", "M12 3.5a8.5 8.5 0 1 0 0 17a8.5 8.5 0 1 0 0-17z", "M12 7.5V12l3 2")
    val Refresh = line("Refresh", "M19.5 12a7.5 7.5 0 1 1-2.2-5.3", "M19.5 4v4h-4")
    val Music = line("Music", "M9 17.5V5.8L19 4v11", "M6.5 15a2.5 2.5 0 1 0 0 5a2.5 2.5 0 1 0 0-5z", "M16.5 12.5a2.5 2.5 0 1 0 0 5a2.5 2.5 0 1 0 0-5z")
    val Playlist = line("Playlist", "M4 6h11", "M4 11h11", "M4 16h7", "M18 8v9.5", "M16 15.5a2 2 0 1 0 0 4a2 2 0 1 0 0-4z")

    /** 💥 Drop swap: one song's drop traded for the next one's. */
    val DropSwap = line("DropSwap", "M4 8h13", "M13.5 4.5 17 8l-3.5 3.5", "M20 16H7", "M10.5 12.5 7 16l3.5 3.5")

    /** 🌊 Fade: one song going down while the other comes up. */
    val Fade = line("Fade", "M3 7c6 0 12 10 18 10", "M3 17c6 0 12-10 18-10")

    /** 🔁 Once more: back to the drop that just played. */
    val PullUp = line("PullUp", "M4.5 12a7.5 7.5 0 1 0 2.4-5.5", "M4.5 4v4h4")
    val Dislike = line("Dislike", "M12 3.5a8.5 8.5 0 1 0 0 17a8.5 8.5 0 1 0 0-17z", "M6 6l12 12")
    val KeepFull = line("KeepFull", "M12 3.5a8.5 8.5 0 1 0 0 17a8.5 8.5 0 1 0 0-17z", "M8 12.4l2.8 2.8 5.2-5.4")
    val Flame = line("Flame", "M12 3c.6 4 5 5.8 5 11a5 5 0 0 1-10 0c0-2.6 1.6-3.8 2.6-5.6.4 1.6 1.2 2.4 2 2.6C11.8 8.6 11.6 5.6 12 3z")
    val Calm = line("Calm", "M19.5 14.5A8 8 0 1 1 9.5 4.5a6.5 6.5 0 0 0 10 10z")
    val Flag = line("Flag", "M6 21V4", "M6 4.5h11l-2 3.8 2 3.7H6")
    val Sun = line(
        "Sun", "M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8z",
        "M12 2.5v2", "M12 19.5v2", "M2.5 12h2", "M19.5 12h2", "M5.3 5.3l1.4 1.4", "M17.3 17.3l1.4 1.4", "M5.3 18.7l1.4-1.4", "M17.3 6.7l1.4-1.4",
    )

    /** Two platters and a fader: the DJ tab. */
    val Mixer = line("Mixer", "M7.5 5a4.5 4.5 0 1 0 0 9a4.5 4.5 0 1 0 0-9z", "M7.5 8.5a1 1 0 1 0 0 2a1 1 0 1 0 0-2z", "M16.5 5a4.5 4.5 0 1 0 0 9a4.5 4.5 0 1 0 0-9z", "M16.5 8.5a1 1 0 1 0 0 2a1 1 0 1 0 0-2z", "M5 19h14", "M12 17v4")
    val Loop = line("Loop", "M17 3l3 3-3 3", "M4 11V9a3 3 0 0 1 3-3h13", "M7 21l-3-3 3-3", "M20 13v2a3 3 0 0 1-3 3H4")
    val Echo = line("Echo", "M5 12h1", "M9 8.5a5 5 0 0 1 0 7", "M12.5 5.5a9.5 9.5 0 0 1 0 13", "M16 3a13 13 0 0 1 0 18")
    val Stop = line("Stop", "M7 7h10v10H7z")
    val Pads = line("Pads", "M4 4h7v7H4z", "M13 4h7v7h-7z", "M4 13h7v7H4z", "M13 13h7v7h-7z")
    val JumpBack = line("JumpBack", "M11 6 5 12l6 6", "M19 6l-6 6 6 6")
    val JumpForward = line("JumpForward", "M13 6l6 6-6 6", "M5 6l6 6-6 6")
    val Sync = line("Sync", "M4 9h13l-3-3", "M20 15H7l3 3")

    val Play = solid("Play", "M7.5 4.8v14.4a.8.8 0 0 0 1.2.7l11-7.2a.8.8 0 0 0 0-1.4l-11-7.2a.8.8 0 0 0-1.2.7z")
    val Pause = solid("Pause", "M6.5 4.5h3.5v15H6.5z", "M14 4.5h3.5v15H14z")
    val Next = solid("Next", "M5 5.3v13.4a.7.7 0 0 0 1.1.6l9.4-6.7a.7.7 0 0 0 0-1.2L6.1 4.7a.7.7 0 0 0-1.1.6z", "M17 5h2.5v14H17z")
    val Previous = solid("Previous", "M19 5.3v13.4a.7.7 0 0 1-1.1.6l-9.4-6.7a.7.7 0 0 1 0-1.2l9.4-6.7a.7.7 0 0 1 1.1.6z", "M4.5 5H7v14H4.5z")
    val More = solid("More", "M6 10.5a1.5 1.5 0 1 0 0 3a1.5 1.5 0 1 0 0-3z", "M12 10.5a1.5 1.5 0 1 0 0 3a1.5 1.5 0 1 0 0-3z", "M18 10.5a1.5 1.5 0 1 0 0 3a1.5 1.5 0 1 0 0-3z")

    private fun line(name: String, vararg paths: String) = icon(name, paths, stroke = true)

    private fun solid(name: String, vararg paths: String) = icon(name, paths, stroke = false)

    private fun icon(name: String, paths: Array<out String>, stroke: Boolean): ImageVector {
        val builder = ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        paths.forEach { d ->
            if (stroke) {
                builder.addPath(
                    pathData = addPathNodes(d),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.8f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            } else {
                builder.addPath(pathData = addPathNodes(d), fill = SolidColor(Color.Black))
            }
        }
        return builder.build()
    }
}
