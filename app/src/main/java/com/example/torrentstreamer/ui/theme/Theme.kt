package com.example.torrentstreamer.ui.theme

import android.app.Activity
import android.os.Build
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PredictiveBackContainer(
    enabled: Boolean = true,
    onBack: () -> Unit,
    onProgress: (Float, Boolean) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit
) {
    val backProgress = remember { Animatable(0f) }
    var swipeEdgeFromLeft by remember { mutableStateOf(true) }
    val density = LocalDensity.current
    val motionScheme = MaterialTheme.motionScheme
    val translationMaxPx = with(density) { 80.dp.toPx() }

    PredictiveBackHandler(enabled = enabled) { backEvents ->
        try {
            backEvents.collectLatest { event ->
                swipeEdgeFromLeft = event.swipeEdge == BackEventCompat.EDGE_LEFT
                backProgress.snapTo(event.progress)
                onProgress(event.progress, swipeEdgeFromLeft)
            }
            backProgress.animateTo(1f, tween(150))
            onBack()
        } catch (e: CancellationException) {
            backProgress.animateTo(0f, motionScheme.fastSpatialSpec())
            onProgress(0f, swipeEdgeFromLeft)
        }
    }

    val backScale = 1f - (backProgress.value * 0.08f)
    val backCornerRadius = 32.dp * backProgress.value
    val backTranslateX = if (swipeEdgeFromLeft) {
        backProgress.value * translationMaxPx
    } else {
        -backProgress.value * translationMaxPx
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = backScale
                scaleY = backScale
                translationX = backTranslateX
                shadowElevation = 24f * backProgress.value
                shape = RoundedCornerShape(backCornerRadius)
                clip = true
            },
        content = content
    )
}

/**
 * Головна тема TorrentStreamer на базі розширення MaterialExpressiveTheme.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TorrentStreamerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = appColorScheme(darkTheme = darkTheme, dynamicColor = dynamicColor)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        shapes = ExpressiveShapes,
        motionScheme = MotionScheme.expressive(),
        typography = AppTypography,
        content = content
    )
}