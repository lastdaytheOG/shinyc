package com.amar.vault.ui.components.folder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amar.vault.ui.theme.CharcoalSoft
import com.amar.vault.ui.theme.Spacing
import com.amar.vault.ui.theme.WarmBrownDark

@Composable
fun EmptyFolderState(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.L),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(Spacing.XL))
        
        // Premium Ghost Graphic
        Box(
            modifier = Modifier
                .width(140.dp)
                .aspectRatio(9f / 16f)
                .clip(RoundedCornerShape(16.dp))
                .border(
                    1.dp,
                    Brush.sweepGradient(listOf(Color(0xFFE0E0E0), Color(0xFFF5F5F5))),
                    RoundedCornerShape(16.dp)
                )
                .background(Color(0xFFFAFAFA)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFF0F0F0)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✨", fontSize = 20.sp)
                }
                Spacer(Modifier.height(Spacing.M))
                Text(
                    text = "Awaiting Memories",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Gray.copy(alpha = 0.5f)
                )
            }
        }

        Spacer(Modifier.height(Spacing.XL))

        Text(
            text = "Nothing saved here yet.",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = CharcoalSoft,
            textAlign = TextAlign.Center
        )
        
        Spacer(Modifier.height(Spacing.S))
        
        Text(
            text = "Share reels, products, articles, or screenshots to this folder to start building your visual collection.",
            fontSize = 14.sp,
            color = WarmBrownDark,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center
        )
    }
}
