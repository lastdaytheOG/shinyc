package com.amar.vault.ui.components.save

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amar.vault.ui.theme.CharcoalSoft
import com.amar.vault.ui.theme.Spacing

@Composable
fun QuickNote(
    note: String,
    onNoteChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFFF7F7F7))
            .padding(Spacing.M)
    ) {
        BasicTextField(
            value = note,
            onValueChange = onNoteChange,
            textStyle = TextStyle(
                color = CharcoalSoft,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 22.sp
            ),
            cursorBrush = SolidColor(CharcoalSoft),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { innerTextField ->
                if (note.isEmpty()) {
                    Text(
                        text = "Add a note...",
                        color = Color.Gray.copy(alpha = 0.7f),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                innerTextField()
            }
        )
    }
}
