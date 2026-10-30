package com.nuvio.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * FRAME's glass control surface: a translucent tinted fill, a soft top sheen and a
 * specular rim that is brighter on the upper-left edge, so buttons read as glass
 * floating over artwork. Pure Compose drawing; no blur source is required.
 */
fun Modifier.liquidGlass(
    shape: Shape,
    tint: Color = Color.White,
    fillAlpha: Float = 0.14f,
    rimWidth: Dp = 1.dp,
): Modifier = this
    .clip(shape)
    .background(tint.copy(alpha = fillAlpha), shape)
    .drawWithContent {
        // Sheen: light falling on the top half of the glass.
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.02f), Color.Transparent),
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
                Color.White.copy(alpha = 0.55f),
                Color.White.copy(alpha = 0.10f),
                Color.White.copy(alpha = 0.04f),
                Color.White.copy(alpha = 0.22f),
            ),
            start = Offset.Zero,
            end = Offset.Infinite,
        ),
        shape = shape,
    )
