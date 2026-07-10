package com.amar.vault

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.background
import androidx.glance.action.clickable

class VaultWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Load latest 3 screenshots for widget preview
        val dao   = VaultDatabase.get(context).vaultDao()
        val items = dao.getAll().take(3)

        provideContent {
            WidgetContent(context = context, items = items)
        }
    }
}

@Composable
private fun WidgetContent(context: Context, items: List<VaultItem>) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(Color(0xFF1C1B1F)))
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>())
    ) {
        // Header
        Row(
            modifier              = GlanceModifier.fillMaxWidth(),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalAlignment   = Alignment.Start
        ) {
            Text(
                text  = "⚡ Amar Vault",
                style = TextStyle(
                    color    = ColorProvider(Color(0xFFD0BCFF)),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }

        Spacer(modifier = GlanceModifier.height(8.dp))

        if (items.isEmpty()) {
            Text(
                text  = "No screenshots yet.\nTake one to get started.",
                style = TextStyle(
                    color    = ColorProvider(Color(0xFF9E9E9E)),
                    fontSize = 12.sp
                )
            )
        } else {
            items.forEach { item ->
                WidgetItemRow(item = item)
                Spacer(modifier = GlanceModifier.height(6.dp))
            }
        }

        Spacer(modifier = GlanceModifier.defaultWeight())

        // Footer — tap to open
        Text(
            text  = "Tap to search vault →",
            style = TextStyle(
                color    = ColorProvider(Color(0xFF6650A4)),
                fontSize = 10.sp
            )
        )
    }
}

@Composable
private fun WidgetItemRow(item: VaultItem) {
    val cleanText = item.ocrText
        .substringBefore("\n[")
        .trim()
        .lines()
        .firstOrNull { it.isNotBlank() }
        ?.take(50) ?: "Screenshot"

    Row(
        modifier          = GlanceModifier
            .fillMaxWidth()
            .background(ColorProvider(Color(0xFF2D2B32)))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text  = cleanText,
            style = TextStyle(
                color    = ColorProvider(Color(0xFFE6E1E5)),
                fontSize = 11.sp
            ),
            maxLines = 1
        )
    }
}

class VaultWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = VaultWidget()
}