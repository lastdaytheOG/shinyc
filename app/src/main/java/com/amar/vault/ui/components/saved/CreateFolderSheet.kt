package com.amar.vault.ui.components.saved

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amar.vault.FolderMeta
import com.amar.vault.FolderTheme
import com.amar.vault.bounceClick
import com.amar.vault.ui.components.home.FolderCard
import com.amar.vault.ui.theme.*

private val FOLDER_ICONS = listOf(
    "📁", "🍳", "🛍️", "✈️", "💡", "📚", "🧠", "💻",
    "🎬", "🎵", "📍", "🎮", "🤖", "📰", "💪", "🎨", "⭐", "❤️"
)

private val FOLDER_ACCENTS = listOf(
    0xFFAF52DE, 0xFFFF2D55, 0xFFFF9500, 0xFFFFCC00, 0xFF34C759,
    0xFF00C7BE, 0xFF007AFF, 0xFF5856D6, 0xFFFF7F50, 0xFF8E8E93, 0xFF1C1A18
)

/**
 * Delightful folder creation (#3): name, icon picker, color picker, theme/cover
 * style, optional description — with a **live preview** that updates as you tweak,
 * so creating a folder feels intentional. Produces a [FolderMeta] persisted to
 * DataStore; no DB/repository change.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateFolderSheet(
    existingNames: List<String>,
    onDismiss: () -> Unit,
    onCreate: (String, FolderMeta) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf("📁") }
    var accent by remember { mutableStateOf(FOLDER_ACCENTS.first()) }
    var theme by remember { mutableStateOf(FolderTheme.COLOR_COVER) }
    var description by remember { mutableStateOf("") }

    val trimmed = name.trim()
    val duplicate = existingNames.any { it.equals(trimmed, ignoreCase = true) }
    val canCreate = trimmed.isNotBlank() && !duplicate

    val meta = FolderMeta(
        theme = theme,
        accent = accent,
        icon = icon,
        description = description.trim().ifBlank { null }
    )
    val previewStyle = FolderVisuals.styleFor(trimmed.ifBlank { "New Folder" }, meta)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CreamLight,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.L)
                .padding(bottom = Spacing.XL)
        ) {
            Text("New Folder", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = CharcoalSoft)
            Spacer(Modifier.height(Spacing.M))

            // ── Live preview ──────────────────────────────────────────────────
            FolderCard(
                style = previewStyle,
                title = trimmed.ifBlank { "New Folder" },
                count = 0,
                onClick = {},
                modifier = Modifier.fillMaxWidth(0.62f)
            )

            Spacer(Modifier.height(Spacing.L))

            // ── Name ─────────────────────────────────────────────────────────
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                isError = duplicate,
                placeholder = { Text("Folder name") },
                supportingText = if (duplicate) { { Text("A folder with this name already exists") } } else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(accent),
                    unfocusedBorderColor = CreamDark,
                    focusedTextColor = CharcoalSoft,
                    unfocusedTextColor = CharcoalSoft
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(Spacing.M))

            // ── Icon picker ──────────────────────────────────────────────────
            SectionLabel("Icon")
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Spacing.S)
            ) {
                FOLDER_ICONS.forEach { glyph ->
                    val selected = glyph == icon
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) Color(accent).copy(alpha = 0.18f) else Cream)
                            .border(
                                if (selected) 1.5.dp else 0.5.dp,
                                if (selected) Color(accent) else CreamDark,
                                RoundedCornerShape(12.dp)
                            )
                            .bounceClick { icon = glyph },
                        contentAlignment = Alignment.Center
                    ) { Text(glyph, fontSize = 20.sp) }
                }
            }

            Spacer(Modifier.height(Spacing.M))

            // ── Color picker ─────────────────────────────────────────────────
            SectionLabel("Color")
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Spacing.S)
            ) {
                FOLDER_ACCENTS.forEach { c ->
                    val selected = c == accent
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color(c))
                            .border(
                                if (selected) 2.5.dp else 0.dp,
                                if (selected) CharcoalSoft else Color.Transparent,
                                CircleShape
                            )
                            .bounceClick { accent = c }
                    )
                }
            }

            Spacer(Modifier.height(Spacing.M))

            // ── Theme / cover style ──────────────────────────────────────────
            SectionLabel("Style")
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Spacing.S)
            ) {
                FolderTheme.values().forEach { t ->
                    val selected = t == theme
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (selected) CharcoalSoft else Cream)
                            .border(0.5.dp, CreamDark, RoundedCornerShape(50))
                            .bounceClick { theme = t }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(t.icon, fontSize = 13.sp)
                        Spacer(Modifier.width(5.dp))
                        Text(
                            t.label,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (selected) Color.White else CharcoalSoft
                        )
                    }
                }
            }

            Spacer(Modifier.height(Spacing.M))

            // ── Description (optional) ───────────────────────────────────────
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                placeholder = { Text("Description (optional)") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(accent),
                    unfocusedBorderColor = CreamDark,
                    focusedTextColor = CharcoalSoft,
                    unfocusedTextColor = CharcoalSoft
                ),
                shape = RoundedCornerShape(14.dp),
                maxLines = 2,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(Spacing.L))

            // ── Create ───────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(accent))
                    .alpha(if (canCreate) 1f else 0.4f)
                    .bounceClick { if (canCreate) onCreate(trimmed, meta) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Create Folder",
                    color = contrastOn(Color(accent)),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = WarmBrownDark,
        modifier = Modifier.padding(bottom = Spacing.S)
    )
}
