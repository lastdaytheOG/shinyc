package com.amar.vault.ui.components.folder

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.amar.vault.ui.theme.CreamLight
import com.amar.vault.ui.theme.Spacing

@Composable
fun BaseContentCard(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    aspectRatio: Float?,
    imageContent: @Composable BoxScope.() -> Unit,
    footerContent: (@Composable ColumnScope.() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "PressScale"
    )

    Column(
        modifier = modifier
            .scale(scale)
            .shadow(
                elevation = 6.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = Color.Black.copy(alpha = 0.05f),
                spotColor = Color.Black.copy(alpha = 0.1f)
            )
            .clip(RoundedCornerShape(16.dp))
            .background(CreamLight)
            .pointerInput(Unit) {
                while (true) {
                    awaitPointerEventScope {
                        awaitFirstDown(requireUnconsumed = false)
                        isPressed = true
                        waitForUpOrCancellation()
                        isPressed = false
                    }
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null, // Disable Material ripple
                onClick = onClick
            )
            .wrapContentHeight()
    ) {
        // Image / Media section
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (aspectRatio != null) Modifier.aspectRatio(aspectRatio)
                    else Modifier.wrapContentHeight()
                )
        ) {
            imageContent()
        }

        // Footer / Metadata section
        if (footerContent != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.S)
            ) {
                footerContent()
            }
        }
    }
}
