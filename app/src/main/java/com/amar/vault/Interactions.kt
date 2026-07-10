package com.amar.vault

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Premium iOS-style spring press feedback.
 * Scales down slightly and drops opacity for a satisfying touch experience.
 * Removes default Android Material ripples entirely.
 */
fun Modifier.bounceClick(
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) = composed {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = 400f
        ),
        label = "scale"
    )
    
    val alpha by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = 300f
        ),
        label = "alpha"
    )
    
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            this.alpha = alpha
        }
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    isPressed = true
                    try {
                        tryAwaitRelease()
                    } finally {
                        isPressed = false
                    }
                },
                onTap = { onClick() },
                onLongPress = { onLongClick?.invoke() }
            )
        }
}

/**
 * Strips default Material ripple for simple clickable elements where bounceClick
 * is too heavy but we still want a premium feel without an ink splash.
 */
fun Modifier.noRippleClickable(
    onClick: () -> Unit
): Modifier = composed {
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick
    )
}
