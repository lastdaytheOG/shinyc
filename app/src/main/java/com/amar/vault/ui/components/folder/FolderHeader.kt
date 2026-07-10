package com.amar.vault.ui.components.folder

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.amar.vault.ui.theme.CharcoalSoft
import com.amar.vault.ui.theme.Spacing
import com.amar.vault.ui.theme.WarmBrownDark

@Composable
fun FolderHeader(
    title: String,
    saveCount: Int,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.L, vertical = Spacing.M)
    ) {
        Text(
            text = title,
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
            color = CharcoalSoft,
            letterSpacing = (-1.5).sp
        )
        
        Spacer(modifier = Modifier.height(Spacing.XS))
        
        val countText = if (saveCount == 1) "1 save" else "$saveCount saves"
        
        Text(
            text = countText,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = WarmBrownDark
        )
    }
}
