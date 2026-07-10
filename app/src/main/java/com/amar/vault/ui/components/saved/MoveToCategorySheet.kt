package com.amar.vault.ui.components.saved

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amar.vault.bounceClick
import com.amar.vault.ui.theme.*

/**
 * Bottom sheet for choosing a destination category for the selected items,
 * including creating a brand-new category inline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveToCategorySheet(
    categories: List<String>,
    onDismiss: () -> Unit,
    onMove: (String) -> Unit
) {
    var newName by remember { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = CreamLight,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Move to", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = CharcoalSoft)
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                placeholder = { Text("New category…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (newName.isNotBlank()) onMove(newName.trim())
                }),
                trailingIcon = {
                    if (newName.isNotBlank()) {
                        Text(
                            "Create",
                            color = AgenticBlue,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .bounceClick { onMove(newName.trim()) }
                                .padding(end = 12.dp)
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            LazyColumn(Modifier.heightIn(max = 340.dp)) {
                item {
                    CategoryRow("📥", "Uncategorized") { onMove("") }
                }
                items(categories.filter { it.isNotBlank() }) { cat ->
                    val style = FolderVisuals.getStyle(cat)
                    CategoryRow(style.icon, cat) { onMove(cat) }
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(icon: String, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .bounceClick { onClick() }
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(IconBgLight),
            contentAlignment = Alignment.Center
        ) { Text(icon, fontSize = 16.sp) }
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 15.sp, color = CharcoalSoft, fontWeight = FontWeight.Medium)
    }
}
