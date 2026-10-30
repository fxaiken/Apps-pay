package com.nuvio.app.core.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * FRAME's glass control surface: a translucent tinted fill, a soft top sheen and a
 * specular rim that is brighter on the upper-left edge, so buttons read as glass
 * floating over artwork. Pure Compose drawing; no blur source is required.
 *
 * [highlight] (0..1) brightens the glass while it is touched, the way glass catches
 * more light when pressed.
 */
fun Modifier.liquidGlass(
    shape: Shape,
    tint: Color = Color.White,
    fillAlpha: Float = 0.14f,
    rimWidth: Dp = 1.dp,
    highlight: Float = 0f,
): Modifier = this
    .clip(shape)
    .background(tint.copy(alpha = fillAlpha + 0.14f * highlight), shape)
    .drawWithContent {
        // Sheen: light falling on the top half of the glass.
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.16f + 0.14f * highlight),
                    Color.White.copy(alpha = 0.02f),
                    Color.Transparent,
                ),
                startY = 0f,
                endY = size.height * 0.75f,
            ),
        )
        drawContent()
    }
    .border(
        width = rimWidth,
        brush = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.55f + 0.3f * highlight),
                Color.White.copy(alpha = 0.10f),
                Color.White.copy(alpha = 0.04f),
                Color.White.copy(alpha = 0.22f + 0.2f * highlight),
            ),
            start = Offset.Zero,
            end = Offset.Infinite,
        ),
        shape = shape,
    )

/**
 * Press progress (0 = idle, 1 = held) on a quick, slightly bouncy spring, so a control
 * reacts the moment it is touched and settles back without blocking the next tap.
 */
@Composable
fun rememberPressProgress(interactionSource: InteractionSource): State<Float> {
    val pressed by interactionSource.collectIsPressedAsState()
    return animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
        label = "press_progress",
    )
}

/** Sinks a control slightly while pressed; [progress] comes from [rememberPressProgress]. */
fun Modifier.pressScale(progress: () -> Float, pressedScale: Float = 0.94f): Modifier =
    graphicsLayer {
        val scale = 1f - (1f - pressedScale) * progress()
        scaleX = scale
        scaleY = scale
    }
