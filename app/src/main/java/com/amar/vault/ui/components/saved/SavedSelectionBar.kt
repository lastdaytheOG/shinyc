package com.amar.vault.ui.components.saved

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amar.vault.bounceClick
import com.amar.vault.ui.theme.CharcoalSoft
import com.amar.vault.ui.theme.CreamLight
import com.amar.vault.ui.theme.WarmBrownDark

/**
 * Contextual action bar shown while multi-selecting Saved items.
 * In the Archive context the archive action becomes "restore".
 */
@Composable
fun SavedSelectionBar(
    count: Int,
    isArchiveContext: Boolean,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onMove: () -> Unit,
    onFavorite: () -> Unit,
    onArchiveToggle: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(CreamLight)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircleIcon("✕", onClick = onClose)
            Spacer(Modifier.width(12.dp))
            Text(
                text = if (count == 0) "Select items" else "$count selected",
                color = CharcoalSoft,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "Select all",
                color = WarmBrownDark,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .bounceClick { onSelectAll() }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val enabled = count > 0
            ActionChip("📁", "Move", enabled, onMove, Modifier.weight(1f))
            if (!isArchiveContext) {
                ActionChip("❤", "Favorite", enabled, onFavorite, Modifier.weight(1f))
                ActionChip("📦", "Archive", enabled, onArchiveToggle, Modifier.weight(1f))
            } else {
                ActionChip("♻", "Restore", enabled, onArchiveToggle, Modifier.weight(1f))
            }
            ActionChip("🗑", "Delete", enabled, onDelete, Modifier.weight(1f), destructive = true)
        }
    }
}

@Composable
private fun CircleIcon(glyph: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.05f))
            .bounceClick { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(glyph, fontSize = 14.sp, color = CharcoalSoft, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ActionChip(
    icon: String,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false
) {
    val tint = when {
        !enabled -> WarmBrownDark.copy(alpha = 0.35f)
        destructive -> Color(0xFFD64541)
        else -> CharcoalSoft
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.04f))
            .then(if (enabled) Modifier.bounceClick { onClick() } else Modifier)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(icon, fontSize = 17.sp)
        Spacer(Modifier.height(3.dp))
        Text(label, fontSize = 11.sp, color = tint, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
