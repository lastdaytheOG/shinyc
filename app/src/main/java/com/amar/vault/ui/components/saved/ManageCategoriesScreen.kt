package com.amar.vault.ui.components.saved

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amar.vault.CategoryCount
import com.amar.vault.bounceClick
import com.amar.vault.ui.theme.*
import kotlin.math.roundToInt

private val RowHeight = 68.dp

/**
 * Standalone management surface for categories: reorder (long-press drag),
 * pin, rename, merge, and delete. This is the only place reordering happens.
 */
@Composable
fun ManageCategoriesScreen(
    categories: List<CategoryCount>,
    pinned: Set<String>,
    onBack: () -> Unit,
    onRename: (String, String) -> Unit,
    onMerge: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onTogglePin: (String) -> Unit,
    onReorder: (List<String>) -> Unit
) {
    // Local mutable order of non-blank category names, seeded from the incoming order.
    val order = remember(categories.map { it.category }) {
        mutableStateListOf<String>().apply { addAll(categories.map { it.category }.filter { it.isNotBlank() }) }
    }
    val countOf = remember(categories) { categories.associate { it.category to it.count } }

    var renameTarget by remember { mutableStateOf<String?>(null) }
    var mergeTarget by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val rowPx = with(density) { RowHeight.toPx() }

    var draggingIndex by remember { mutableStateOf(-1) }
    var dragOffset by remember { mutableStateOf(0f) }

    Column(Modifier.fillMaxSize().background(Cream)) {
        Spacer(Modifier.height(54.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.L, vertical = Spacing.S),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.bounceClick { onBack() }.height(36.dp),
                shape = RoundedCornerShape(18.dp),
                color = CreamLight
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp)) {
                    Text("←", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = WarmBrownDark)
                    Spacer(Modifier.width(6.dp))
                    Text("Done", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = WarmBrownDark)
                }
            }
        }

        Text(
            "Manage Categories",
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = CharcoalSoft,
            letterSpacing = (-1).sp,
            modifier = Modifier.padding(horizontal = Spacing.L)
        )
        Text(
            "Long-press a row to reorder",
            fontSize = 13.sp,
            color = WarmBrownDark,
            modifier = Modifier.padding(horizontal = Spacing.L, vertical = 4.dp)
        )
        Spacer(Modifier.height(12.dp))

        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.L)
        ) {
            order.forEachIndexed { index, name ->
                val isDragging = index == draggingIndex
                CategoryManageRow(
                    name = name,
                    count = countOf[name] ?: 0,
                    isPinned = name in pinned,
                    isDragging = isDragging,
                    dragOffset = if (isDragging) dragOffset else 0f,
                    onTogglePin = { onTogglePin(name) },
                    onRename = { renameTarget = name },
                    onMerge = { mergeTarget = name },
                    onDelete = { deleteTarget = name },
                    dragHandleModifier = Modifier.pointerInput(order.size) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggingIndex = index
                                dragOffset = 0f
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDragEnd = {
                                draggingIndex = -1
                                dragOffset = 0f
                                onReorder(order.toList())
                            },
                            onDragCancel = { draggingIndex = -1; dragOffset = 0f },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragOffset += dragAmount.y
                                val cur = draggingIndex
                                if (cur in order.indices) {
                                    val target = (cur + (dragOffset / rowPx).roundToInt())
                                        .coerceIn(0, order.lastIndex)
                                    if (target != cur) {
                                        order.move(cur, target)
                                        draggingIndex = target
                                        dragOffset -= (target - cur) * rowPx
                                    }
                                }
                            }
                        )
                    }
                )
            }
            if (order.isEmpty()) {
                Text(
                    "No categories yet. Save something and assign it a category.",
                    fontSize = 14.sp,
                    color = WarmBrownDark,
                    modifier = Modifier.padding(vertical = 40.dp)
                )
            }
        }
    }

    // ── Dialogs ────────────────────────────────────────────────────────────────
    renameTarget?.let { target ->
        TextInputDialog(
            title = "Rename category",
            initial = target,
            confirmLabel = "Rename",
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                onRename(target, newName)
                renameTarget = null
            }
        )
    }

    mergeTarget?.let { target ->
        val others = order.filter { it != target }
        AlertDialog(
            onDismissRequest = { mergeTarget = null },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { mergeTarget = null }) { Text("Cancel") } },
            title = { Text("Merge \"$target\" into…") },
            text = {
                Column {
                    if (others.isEmpty()) Text("No other categories to merge into.")
                    others.forEach { dest ->
                        Text(
                            dest,
                            fontSize = 15.sp,
                            color = CharcoalSoft,
                            modifier = Modifier
                                .fillMaxWidth()
                                .bounceClick { onMerge(target, dest); mergeTarget = null }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            confirmButton = {
                TextButton(onClick = { onDelete(target); deleteTarget = null }) {
                    Text("Delete", color = androidx.compose.ui.graphics.Color(0xFFD64541))
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
            title = { Text("Delete \"$target\"?") },
            text = { Text("Items in this category will move to Uncategorized. Nothing is deleted.") }
        )
    }
}

@Composable
private fun CategoryManageRow(
    name: String,
    count: Int,
    isPinned: Boolean,
    isDragging: Boolean,
    dragOffset: Float,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onMerge: () -> Unit,
    onDelete: () -> Unit,
    dragHandleModifier: Modifier
) {
    var menu by remember { mutableStateOf(false) }
    val style = FolderVisuals.getStyle(name)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(RowHeight)
            .graphicsLayer {
                translationY = dragOffset
                shadowElevation = if (isDragging) 16f else 0f
            }
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (isDragging) CreamLight else CreamLight.copy(alpha = 0.7f))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Drag handle
        Box(modifier = dragHandleModifier.padding(end = 6.dp)) {
            Text("⋮⋮", fontSize = 18.sp, color = ChevronGray, fontWeight = FontWeight.Bold)
        }
        Box(
            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(IconBgLight),
            contentAlignment = Alignment.Center
        ) { Text(style.icon, fontSize = 18.sp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = CharcoalSoft, maxLines = 1)
            Text("$count ${if (count == 1) "item" else "items"}", fontSize = 12.sp, color = WarmBrownDark)
        }
        // Pin toggle
        Box(
            modifier = Modifier.size(34.dp).clip(CircleShape).bounceClick { onTogglePin() },
            contentAlignment = Alignment.Center
        ) {
            Text(if (isPinned) "★" else "☆", fontSize = 17.sp, color = if (isPinned) style.accent else ChevronGray)
        }
        // Overflow
        Box {
            Box(
                modifier = Modifier.size(34.dp).clip(CircleShape).bounceClick { menu = true },
                contentAlignment = Alignment.Center
            ) { Text("⋯", fontSize = 18.sp, color = WarmBrownDark, fontWeight = FontWeight.Bold) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Merge into…") }, onClick = { menu = false; onMerge() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun TextInputDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
            )
        }
    )
}

/** Moves an element within a MutableList, shifting the others. */
private fun <T> SnapshotStateList<T>.move(from: Int, to: Int) {
    if (from == to) return
    val item = removeAt(from)
    add(to, item)
}
