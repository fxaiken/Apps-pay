package com.nuvio.app.core.ui

import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

/** Capsule/circle shape the lens effect understands (it reads the corner radii). */
val GlassCapsule: Shape = AbsoluteRoundedCornerShape(50)

/**
 * Real liquid glass over [backdrop] (content recorded with `layerBackdrop`): the artwork
 * behind is refracted at the rim (Android 13+), softly blurred (Android 12+) and kept
 * vivid, with a faint white body that brightens while pressed. Without a backdrop, or
 * outside the FRAME look, it falls back to [liquidGlass].
 */
fun Modifier.glassOver(
    backdrop: Backdrop?,
    shape: Shape = GlassCapsule,
    highlight: Float = 0f,
): Modifier =
    if (backdrop == null || !useAppleTvDesign) {
        liquidGlass(shape, highlight = highlight)
    } else {
        drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(4.dp.toPx())
                lens(14.dp.toPx(), 28.dp.toPx())
            },
            onDrawSurface = {
                drawRect(Color.White.copy(alpha = 0.10f + 0.14f * highlight))
            },
        )
    }
