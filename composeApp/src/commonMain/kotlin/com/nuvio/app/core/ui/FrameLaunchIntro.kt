package com.nuvio.app.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.app_logo_wordmark
import org.jetbrains.compose.resources.painterResource

// Shown once per app process: a cold start opens on the FRAME logo, returning to the app does not.
private var launchIntroShown = false

/**
 * FRAME's opening: the logo settles in on black, holds briefly, then the black curtain
 * fades away to reveal the app (which keeps loading underneath the whole time).
 */
@Composable
fun FrameLaunchIntro() {
    if (!useAppleTvDesign) return
    var visible by remember { mutableStateOf(!launchIntroShown) }
    if (!visible) return

    val logo = remember { Animatable(0f) }
    val curtain = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        launchIntroShown = true
        launch { logo.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessVeryLow)) }
        delay(1_100)
        curtain.animateTo(0f, tween(durationMillis = 380, easing = FastOutSlowInEasing))
        visible = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = curtain.value }
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(Res.drawable.app_logo_wordmark),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth(0.62f)
                .graphicsLayer {
                    alpha = logo.value
                    val scale = 0.9f + 0.1f * logo.value
                    scaleX = scale
                    scaleY = scale
                },
        )
    }
}
