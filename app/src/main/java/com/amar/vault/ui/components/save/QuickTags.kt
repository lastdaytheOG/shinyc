package com.amar.vault.ui.components.save

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.amar.vault.ui.theme.Spacing

@Composable
fun QuickTags(
    tags: List<String>,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.L),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        tags.forEach { tag ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFF7F7F7))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "#$tag",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        
        // Add tag button (UI only placeholder)
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFFF7F7F7))
                .bounceClick { /* Placeholder */ }
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = "+ Tag",
                color = CharcoalSoft,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun SaveActions(
    onSave: () -> Unit,
    onCancel: () -> Unit,
    isSaving: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.L, vertical = Spacing.M),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .bounceClick { if (!isSaving) onCancel() }
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFFF7F7F7))
                .height(56.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Cancel",
                color = CharcoalSoft,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
        
        Spacer(modifier = Modifier.width(Spacing.M))
        
        Box(
            modifier = Modifier
                .weight(2f)
                .bounceClick { if (!isSaving) onSave() }
                .clip(RoundedCornerShape(16.dp))
                .background(CharcoalSoft)
                .height(56.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isSaving) "Saving..." else "Save to Vault",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
