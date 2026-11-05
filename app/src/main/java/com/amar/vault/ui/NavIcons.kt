package com.amar.vault.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Clean Material Document icon with a folded corner. */
val DocumentNavIcon = ImageVector.Builder(
    name = "DocumentNav",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).path(
    fill = SolidColor(Color.Black)
) {
    moveTo(14f, 2f)
    lineTo(6f, 2f)
    curveTo(4.9f, 2f, 4f, 2.9f, 4f, 4f)
    lineTo(4f, 20f)
    curveTo(4.9f, 2f, 6f, 22f, 6f, 22f)
    lineTo(18f, 22f)
    curveTo(19.1f, 22f, 20f, 21.1f, 20f, 20f)
    lineTo(20f, 8f)
    close()
    moveTo(16f, 18f)
    lineTo(8f, 18f)
    lineTo(8f, 16f)
    lineTo(16f, 16f)
    close()
    moveTo(16f, 14f)
    lineTo(8f, 14f)
    lineTo(8f, 12f)
    lineTo(16f, 12f)
    close()
    moveTo(13f, 9f)
    lineTo(13f, 3.5f)
    lineTo(18.5f, 9f)
    close()
}.build()

/** Clean Material Ask / Sparkle Q&A icon. */
val AskNavIcon = ImageVector.Builder(
    name = "AskNav",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).path(
    fill = SolidColor(Color.Black)
) {
    moveTo(20f, 2f)
    lineTo(4f, 2f)
    curveTo(2.9f, 2f, 2f, 2.9f, 2f, 4f)
    lineTo(2f, 22f)
    lineTo(6f, 18f)
    lineTo(20f, 18f)
    curveTo(21.1f, 18f, 22f, 17.1f, 22f, 16f)
    lineTo(22f, 4f)
    curveTo(22f, 2.9f, 21.1f, 2f, 20f, 2f)
    close()
    moveTo(12f, 14f)
    curveTo(11.45f, 14f, 11f, 13.55f, 11f, 13f)
    curveTo(11f, 12.45f, 11.45f, 12f, 12f, 12f)
    curveTo(12.55f, 12f, 13f, 12.45f, 13f, 13f)
    curveTo(13f, 13.55f, 12.55f, 14f, 12f, 14f)
    close()
    moveTo(13f, 10.5f)
    lineTo(11f, 10.5f)
    lineTo(11f, 9.5f)
    curveTo(11f, 8.4f, 11.9f, 7.5f, 13f, 7.5f)
    curveTo(14.1f, 7.5f, 15f, 8.4f, 15f, 9.5f)
    curveTo(15f, 10.6f, 13f, 10f, 13f, 10.5f)
    close()
}.build()
