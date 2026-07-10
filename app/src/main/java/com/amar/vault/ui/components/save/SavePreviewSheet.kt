package com.amar.vault.ui.components.save

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.amar.vault.IngestionAttachment
import com.amar.vault.ui.theme.Spacing

@Composable
fun SavePreviewSheet(
    isLoading: Boolean,
    attachments: List<IngestionAttachment>?,
    initialText: String?,
    categories: List<String>,
    selectedCategory: String,
    onCategorySelected: (String) -> Unit,
    note: String,
    onNoteChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    isSaving: Boolean,
    modifier: Modifier = Modifier,
    getEmojiForCategory: (String) -> String = { "📁" },
    getColorForCategory: (String) -> Color = { Color.Gray },
    onCreateFolder: (() -> Unit)? = null
) {
    val scrollState = rememberScrollState()

    AnimatedContent(
        targetState = isLoading,
        transitionSpec = {
            fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
        },
        label = "SavePreviewTransition"
    ) { loading ->
        if (loading) {
            LoadingState(modifier = modifier)
        } else {
            val primaryAttachment = attachments?.firstOrNull()
            val domain = primaryAttachment?.domain
            val title = primaryAttachment?.previewTitle ?: initialText?.take(50)
            val previewUri = primaryAttachment?.thumbnailPath ?: primaryAttachment?.localPath ?: primaryAttachment?.originalUri

            Column(
                modifier = modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
            ) {
                // Drag handle placeholder area (handled by ModalBottomSheet, but add some space)
                Spacer(modifier = Modifier.height(Spacing.M))

                // Hero Image
                PreviewHero(
                    previewUri = previewUri,
                    title = title,
                    domain = domain,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f) // Takes up majority of available space
                        .padding(horizontal = Spacing.L)
                        .clip(RoundedCornerShape(24.dp))
                )

                Spacer(modifier = Modifier.height(Spacing.L))

                // Folder Picker — real per-folder identity + inline create.
                FolderPicker(
                    categories = categories,
                    selectedCategory = selectedCategory,
                    onCategorySelected = onCategorySelected,
                    getEmojiForCategory = getEmojiForCategory,
                    getColorForCategory = getColorForCategory,
                    onCreateFolder = onCreateFolder
                )

                Spacer(modifier = Modifier.height(Spacing.L))

                // Quick Note
                QuickNote(
                    note = note,
                    onNoteChange = onNoteChange,
                    modifier = Modifier.padding(horizontal = Spacing.L)
                )

                Spacer(modifier = Modifier.height(Spacing.M))

                // Action Buttons
                SaveActions(
                    onSave = onSave,
                    onCancel = onCancel,
                    isSaving = isSaving
                )

                Spacer(modifier = Modifier.height(Spacing.S))
            }
        }
    }
}
