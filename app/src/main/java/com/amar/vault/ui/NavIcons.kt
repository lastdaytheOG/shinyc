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
