package com.amar.vault.ui.components.saved

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amar.vault.SavedUndo
import com.amar.vault.bounceClick
import com.amar.vault.ui.theme.CharcoalSoft

/**
 * Floating undo pill shown after a destructive bulk action (delete / archive).
 * Auto-dismissal is driven by the caller.
 */
@Composable
fun SavedUndoSnackbar(
    undo: SavedUndo?,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = undo != null,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth()
                .shadow(12.dp, RoundedCornerShape(16.dp), clip = false)
                .clip(RoundedCornerShape(16.dp))
                .background(CharcoalSoft)
                .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = undo?.message ?: "",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "UNDO",
                color = Color(0xFFFFD08A),
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .bounceClick { onUndo() }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }
    }
}
