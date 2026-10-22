package com.example.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Remote symbols on a 24-unit grid with square terminals and restrained pixel steps. */
object RemoteIcons {
    private fun icon(name: String, draw: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Square, strokeLineJoin = StrokeJoin.Miter,
                pathBuilder = draw)
        }.build()

    val Source = icon("Source") {
        moveTo(10f, 4f); lineTo(21f, 4f); lineTo(21f, 20f); lineTo(10f, 20f)
        moveTo(3f, 12f); lineTo(15f, 12f)
        moveTo(11f, 8f); lineTo(15f, 12f); lineTo(11f, 16f)
    }
    val Back = icon("Back") {
        moveTo(20f, 12f); lineTo(4f, 12f)
        moveTo(10f, 6f); lineTo(7f, 6f); lineTo(7f, 9f); lineTo(4f, 9f)
        lineTo(4f, 15f); lineTo(7f, 15f); lineTo(7f, 18f); lineTo(10f, 18f)
    }
    val Home = icon("Home") {
        moveTo(3f, 11f); lineTo(6f, 11f); lineTo(6f, 8f); lineTo(9f, 8f)
        lineTo(9f, 5f); lineTo(15f, 5f); lineTo(15f, 8f); lineTo(18f, 8f)
        lineTo(18f, 11f); lineTo(21f, 11f)
        moveTo(6f, 12f); lineTo(6f, 20f); lineTo(10f, 20f); lineTo(10f, 15f)
        lineTo(14f, 15f); lineTo(14f, 20f); lineTo(18f, 20f); lineTo(18f, 12f)
    }
    val Menu = icon("Menu") {
        moveTo(4f, 6f); lineTo(20f, 6f); moveTo(4f, 12f); lineTo(20f, 12f)
        moveTo(4f, 18f); lineTo(20f, 18f)
    }
    val Up = icon("Up") { moveTo(6f, 15f); lineTo(12f, 9f); lineTo(18f, 15f) }
    val Down = icon("Down") { moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f) }
    val Left = icon("Left") { moveTo(15f, 6f); lineTo(9f, 12f); lineTo(15f, 18f) }
    val Right = icon("Right") { moveTo(9f, 6f); lineTo(15f, 12f); lineTo(9f, 18f) }
    val Play = icon("Play") { moveTo(8f, 5f); lineTo(8f, 19f); lineTo(19f, 12f); close() }
    val Pause = icon("Pause") { moveTo(8f, 5f); lineTo(8f, 19f); moveTo(16f, 5f); lineTo(16f, 19f) }
    val Stop = icon("Stop") { moveTo(6f, 6f); lineTo(18f, 6f); lineTo(18f, 18f); lineTo(6f, 18f); close() }
    val Forward = icon("Forward") {
        moveTo(4f, 6f); lineTo(11f, 12f); lineTo(4f, 18f)
        moveTo(13f, 6f); lineTo(20f, 12f); lineTo(13f, 18f)
    }
    val Rewind = icon("Rewind") {
        moveTo(11f, 6f); lineTo(4f, 12f); lineTo(11f, 18f)
        moveTo(20f, 6f); lineTo(13f, 12f); lineTo(20f, 18f)
    }
    private fun PathBuilder.speaker() {
        moveTo(3f, 9f); lineTo(7f, 9f); lineTo(12f, 5f); lineTo(12f, 19f)
        lineTo(7f, 15f); lineTo(3f, 15f); close()
    }
    val VolumeUp = icon("VolumeUp") {
        speaker(); moveTo(16f, 12f); lineTo(22f, 12f); moveTo(19f, 9f); lineTo(19f, 15f)
    }
    val VolumeDown = icon("VolumeDown") { speaker(); moveTo(16f, 12f); lineTo(22f, 12f) }
    val Mute = icon("Mute") {
        speaker(); moveTo(17f, 10f); lineTo(21f, 14f); moveTo(21f, 10f); lineTo(17f, 14f)
    }
    val Dpad = icon("Dpad") {
        moveTo(9f, 3f); lineTo(15f, 3f); lineTo(15f, 9f); lineTo(21f, 9f)
        lineTo(21f, 15f); lineTo(15f, 15f); lineTo(15f, 21f); lineTo(9f, 21f)
        lineTo(9f, 15f); lineTo(3f, 15f); lineTo(3f, 9f); lineTo(9f, 9f); close()
    }
    val Touchpad = icon("Touchpad") {
        moveTo(4f, 3f); lineTo(20f, 3f); lineTo(20f, 21f); lineTo(4f, 21f); close()
        moveTo(10f, 10f); lineTo(14f, 10f); lineTo(14f, 14f); lineTo(10f, 14f); close()
    }
    val Apps = icon("Apps") {
        for (x in listOf(4f, 14f)) for (y in listOf(4f, 14f)) {
            moveTo(x, y); lineTo(x + 6f, y); lineTo(x + 6f, y + 6f); lineTo(x, y + 6f); close()
        }
    }
    val Keyboard = icon("Keyboard") {
        moveTo(2f, 5f); lineTo(22f, 5f); lineTo(22f, 19f); lineTo(2f, 19f); close()
        for (x in listOf(6f, 12f, 18f)) { moveTo(x, 9f); lineTo(x, 10f) }
        moveTo(7f, 15f); lineTo(17f, 15f)
    }
    val Power = icon("Power") {
        moveTo(12f, 3f); lineTo(12f, 11f)
        moveTo(7f, 5f); lineTo(4f, 8f); lineTo(4f, 17f); lineTo(7f, 20f)
        lineTo(17f, 20f); lineTo(20f, 17f); lineTo(20f, 8f); lineTo(17f, 5f)
    }
    val Search = icon("Search") {
        moveTo(4f, 4f); lineTo(14f, 4f); lineTo(14f, 14f); lineTo(4f, 14f); close()
        moveTo(15f, 15f); lineTo(21f, 21f)
    }
    val Settings = icon("Settings") {
        moveTo(4f, 7f); lineTo(20f, 7f); moveTo(4f, 17f); lineTo(20f, 17f)
        moveTo(9f, 4f); lineTo(9f, 10f); moveTo(15f, 14f); lineTo(15f, 20f)
    }
    val Vibration = icon("Vibration") {
        moveTo(8f, 4f); lineTo(16f, 4f); lineTo(16f, 20f); lineTo(8f, 20f); close()
        moveTo(3f, 8f); lineTo(3f, 16f); moveTo(21f, 8f); lineTo(21f, 16f)
    }
}
