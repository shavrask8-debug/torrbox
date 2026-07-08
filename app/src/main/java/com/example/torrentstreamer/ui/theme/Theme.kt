package com.example.torrentstreamer.ui.theme

import android.app.Activity
import android.os.Build
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.BackEventCompat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.rectangle
import androidx.graphics.shapes.toPath
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.CancellationException

// Математично точне заокруглення Squircle
class SquircleShape(
    private val cornerRadiusRatio: Float = 0.22f,
    private val smoothing: Float = 0.6f
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        // ВИПРАВЛЕНО: Запобіжник (Layout Guard) повністю захищає додаток від крашу Google-бібліотеки на нульових розмірах при відкритті клавіатури!
        if (size.width <= 1f || size.height <= 1f) {
            return Outline.Rectangle(Rect(0f, 0f, size.width, size.height))
        }

        val roundedPolygon = RoundedPolygon.rectangle(
            width = size.width,
            height = size.height,
            rounding = CornerRounding(
                radius = size.minDimension * cornerRadiusRatio,
                smoothing = smoothing
            )
        )
        val path = roundedPolygon.toPath().asComposePath()
        val matrix = Matrix().apply {
            translate(size.width / 2f, size.height / 2f)
        }
        path.transform(matrix)
        return Outline.Generic(path)
    }
}

// Універсальний Predictive Back контейнер з плавним відсіканням прямокутних тіней
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PredictiveBackContainer(
    enabled: Boolean = true,
    onBack: () -> Unit,
    onProgress: (Float, Boolean) -> Unit = { _, _ -> }, // Звітує про прогрес свайпу батьківському вікну
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
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
                onProgress(event.progress, swipeEdgeFromLeft) // Звітуємо батькові в реальному часі
            }
            backProgress.animateTo(1f, tween(150))
            onBack()
        } catch (e: CancellationException) {
            backProgress.animateTo(0f, motionScheme.fastSpatialSpec())
            onProgress(0f, swipeEdgeFromLeft) // Скидаємо прогрес
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

private val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TorrentStreamerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = androidx.compose.ui.platform.LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }

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

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = ExpressiveShapes,
        motionScheme = MotionScheme.expressive(),
        content = content
    )
}