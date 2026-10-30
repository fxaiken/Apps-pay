package com.nuvio.app.core.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import org.jetbrains.compose.resources.painterResource
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.glass.GlassBarSurface
import com.nuvio.app.core.ui.jelly.JellyMotion
import com.nuvio.app.core.ui.jelly.JellyTabRow
import com.nuvio.app.core.ui.jelly.JellyTabTargets
import com.nuvio.app.core.ui.jelly.drawJellyGlow
import com.nuvio.app.core.ui.jelly.drawJellyPill
import com.nuvio.app.core.ui.jelly.jellyPillPath
import dev.chrisbanes.haze.HazeState
import kotlin.math.abs
import kotlin.math.max

internal actual val floatingNavigationGlowSupported: Boolean
    get() = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU

@Composable
internal actual fun FloatingNavigationBar(
    items: List<FloatingNavigationItem>,
    modifier: Modifier,
    scrollState: NuvioNavBarScrollState?,
    hazeState: HazeState?,
    contentPadding: PaddingValues,
    compactSize: Boolean,
    glowEnabled: Boolean,
) {
    if (items.isEmpty()) return
    val showGlow = !floatingNavigationGlowSupported || glowEnabled
    val glowStrength by animateFloatAsState(
        targetValue = if (showGlow) 1f else 0f,
        animationSpec = tween(420, easing = NuvioTokens.Motion.standard),
        label = "nav_glow_strength",
    )
    val tokens = MaterialTheme.nuvio
    // Apple TV: the selected tab is lit white glass whatever the theme accent is.
    val accentColor = if (useAppleTvDesign) Color.White else tokens.colors.accent
    val selectedSurface = accentColor.copy(alpha = NuvioTokens.Opacity.selected)
    val labelFraction by animateFloatAsState(
        targetValue = scrollState?.labelVisibility ?: 1f,
        animationSpec = tween(NuvioTokens.Motion.sheetEnterMillis, easing = NuvioTokens.Motion.standard),
        label = "jelly_labels",
    )
    val layoutDirection = LocalLayoutDirection.current
    val isRtl = layoutDirection == LayoutDirection.Rtl
    val selectedIndex = items.indexOfFirst { it.selected }
    val visualSelectedIndex = visualNavIndex(selectedIndex, items.size, isRtl)
    val motion = remember(items.size, isRtl) { JellyMotion(visualSelectedIndex, items.size) }
    val currentItems by rememberUpdatedState(items)
    val currentIsRtl by rememberUpdatedState(isRtl)
    val density = LocalDensity.current
    val trackHeight = 48.dp + (if (compactSize) 8.dp else 16.dp) * labelFraction
    val horizontalPadding = 58.dp - 30.dp * labelFraction
    // Apple TV (iOS 26): scrolling down folds the bar into one glass circle holding the
    // current tab; scrolling back up, or tapping the circle, unfolds it.
    val minimize by animateFloatAsState(
        targetValue = if (useAppleTvDesign && !compactSize && scrollState != null && scrollState.followsScroll && scrollState.labelVisibility < 0.5f) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow),
        label = "tab_bar_minimize",
    )

    LaunchedEffect(visualSelectedIndex, items.size) {
        motion.select(visualSelectedIndex)
    }
    LaunchedEffect(motion.running) {
        if (!motion.running) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (motion.running) {
            withFrameNanos { now ->
                motion.advance((now - previous) / 1_000_000_000.0)
                previous = now
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(contentPadding)
            .padding(horizontal = horizontalPadding),
        contentAlignment = Alignment.BottomCenter,
    ) {
        if (minimize > 0.02f) {
            MinimizedTabButton(
                item = items.firstOrNull { it.selected } ?: items.first(),
                hazeState = hazeState,
                onExpand = { scrollState?.expand() },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = 18.dp - horizontalPadding)
                    .graphicsLayer {
                        alpha = minimize
                        val scale = 0.7f + 0.3f * minimize
                        scaleX = scale
                        scaleY = scale
                    },
            )
        }
        if (minimize < 0.98f) Box(
            modifier = Modifier
                .graphicsLayer {
                    alpha = 1f - minimize
                    transformOrigin = TransformOrigin(0f, 0.5f)
                    scaleX = 1f - 0.78f * minimize
                    scaleY = 1f - 0.1f * minimize
                }
                .widthIn(max = 400.dp)
                .fillMaxWidth()
                .height(trackHeight)
                .onSizeChanged {
                    motion.resize(it.width / density.density, it.height / density.density, items.size)
                }
                .pointerInput(motion, density, items.size, isRtl) {
                    detectJellyTabGestures(motion, density.density, { currentItems }, { currentIsRtl })
                },
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        val frame = motion.frame
                        scaleX = frame.trackScale
                        scaleY = frame.trackScale
                    },
            ) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            val frame = motion.frame
                            transformOrigin = TransformOrigin(
                                if (size.width > 0) frame.originX * density.density / size.width else 0.5f,
                                0.5f,
                            )
                            scaleX = frame.trackScaleX
                            translationY = frame.trackOffsetY * density.density
                        },
                ) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .graphicsLayer { translationX = motion.frame.panelOffset * density.density },
                    ) {
                        Box(
                            Modifier.matchParentSize()
                                .clip(RoundedCornerShape(50))
                                .drawWithContent {
                                    drawContent()
                                    drawJellyGlow(motion.frame, accentColor.copy(alpha = accentColor.alpha * glowStrength))
                                },
                        ) {
                            GlassBarSurface(hazeState, Modifier.matchParentSize(), glowStrength)
                        }
                        Box(
                            Modifier.matchParentSize().drawWithContent {
                                if (selectedIndex >= 0) {
                                    clipPath(jellyPillPath(motion.frame, items.size), ClipOp.Difference) {
                                        this@drawWithContent.drawContent()
                                    }
                                } else {
                                    drawContent()
                                }
                            },
                        ) {
                            JellyTabRow(items, labelFraction, motion, active = false, compactSize = compactSize, modifier = Modifier.matchParentSize())
                        }
                        if (selectedIndex >= 0) {
                            Box(
                                Modifier.matchParentSize()
                                    .clearAndSetSemantics {}
                                    .drawWithContent {
                                        drawJellyPill(
                                            motion.frame,
                                            items.size,
                                            selectedSurface,
                                            accentColor.copy(alpha = accentColor.alpha * glowStrength),
                                        ) { drawContent() }
                                    },
                            ) {
                                JellyTabRow(items, labelFraction, motion, active = true, compactSize = compactSize, modifier = Modifier.matchParentSize())
                            }
                        }
                        JellyTabTargets(items, labelFraction, motion, compactSize, Modifier.matchParentSize())
                    }
                }
            }
        }
    }
}

@Composable
private fun MinimizedTabButton(
    item: FloatingNavigationItem,
    hazeState: HazeState?,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val press by rememberPressProgress(interaction)
    Box(
        modifier = modifier
            .size(52.dp)
            .pressScale({ press })
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClickLabel = item.label, onClick = onExpand),
        contentAlignment = Alignment.Center,
    ) {
        GlassBarSurface(hazeState, Modifier.matchParentSize())
        val icon = item.icon
        val drawable = item.drawable
        val content = item.content
        when {
            icon != null -> Icon(imageVector = icon, contentDescription = item.label, tint = Color.White, modifier = Modifier.size(24.dp))
            drawable != null -> Icon(painter = painterResource(drawable), contentDescription = item.label, tint = Color.White, modifier = Modifier.size(24.dp))
            content != null -> content(onExpand)
        }
    }
}

internal fun visualNavIndex(logicalIndex: Int, count: Int, isRtl: Boolean): Int =
    if (logicalIndex in 0 until count && isRtl) count - 1 - logicalIndex else logicalIndex

internal fun logicalNavIndex(visualIndex: Int, count: Int, isRtl: Boolean): Int =
    if (visualIndex in 0 until count && isRtl) count - 1 - visualIndex else visualIndex

internal suspend fun PointerInputScope.detectJellyTabGestures(
    motion: JellyMotion,
    density: Float,
    currentItems: () -> List<FloatingNavigationItem>,
    isRtl: () -> Boolean,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        motion.begin(down.position.x / density, down.position.y / density)
        var claimed = false
        var finished = false
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (!motion.dragging) {
                    finished = true
                    break
                }
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (event.changes.count { it.pressed } > 1) break
                val delta = change.position - down.position
                if (max(abs(delta.x), abs(delta.y)) > viewConfiguration.touchSlop) claimed = true
                if (claimed) change.consume()
                awaitPointerEvent(PointerEventPass.Main)
                if (!motion.dragging) {
                    finished = true
                    break
                }
                if (change.isConsumed && !claimed) break
                motion.drag(delta.x / density, delta.y / density)
                if (!change.pressed) {
                    val visualIndex = motion.finish()
                    val items = currentItems()
                    val logicalIndex = logicalNavIndex(visualIndex, items.size, isRtl())
                    finished = true
                    items.getOrNull(logicalIndex)?.onClick?.invoke()
                    change.consume()
                    break
                }
            }
        } finally {
            if (!finished) {
                val items = currentItems()
                val selectedIndex = items.indexOfFirst { it.selected }
                motion.cancel(visualNavIndex(selectedIndex, items.size, isRtl()))
            }
        }
    }
}
