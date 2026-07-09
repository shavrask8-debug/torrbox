@file:Suppress("UnstableApiUsage", "DEPRECATION", "deprecation")

package com.example.torrentstreamer

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SubtitlesOff
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.torrentstreamer.R
import com.example.torrentstreamer.ui.theme.SquircleShape
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@androidx.media3.common.util.UnstableApi
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@SuppressLint("SourceLockedOrientationActivity", "NewApi", "InflateParams")
@Composable
fun PlayerScreen(
    videoUrl: String,
    title: String,
    viewModel: MainViewModel,
    isInPipMode: Boolean = false,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    val playerInstance by viewModel.playerInstance.collectAsState()

    val isPlaying by viewModel.isPlayerPlaying.collectAsState()
    val position by viewModel.playerPosition.collectAsState()
    val duration by viewModel.playerDuration.collectAsState()
    val audioTracks by viewModel.availableAudioTracks.collectAsState()
    val subtitleTracks by viewModel.availableSubtitleTracks.collectAsState()

    val videoSize by viewModel.videoSize.collectAsState()

    val isBuffering by viewModel.isBuffering.collectAsState()
    val files by viewModel.files.collectAsState()

    var areControlsVisible by remember { mutableStateOf(true) }
    var showAudioSheet by remember { mutableStateOf(false) }

    // Стейт Slider прогресу
    var isDragging by remember { mutableStateOf(false) }
    var localSliderValue by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(position, isDragging) {
        if (!isDragging) {
            localSliderValue = position.toFloat()
        }
    }

    // YouTube-style Pinch-to-Zoom стани
    var currentPinchScale by remember { mutableFloatStateOf(1f) }
    var resizeModeState by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }

    // 2X прискорення на лонг-прес
    var isSpeedingUp by remember { mutableStateOf(false) }
    var pressJob by remember { mutableStateOf<Job?>(null) }

    // Стейт тригера та автоприховування
    var userInteractionTrigger by remember { mutableLongStateOf(0L) }
    val resetAutohideTimer = { userInteractionTrigger = System.currentTimeMillis() }

    // Імерсивний режим (приховування статус-барів, годинника, батареї)
    val window = activity?.window
    DisposableEffect(Unit) {
        val insetsController = window?.let { WindowCompat.getInsetsController(it, view) }
        insetsController?.let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Читаємо єдиний прапорець дозволу автоповороту за сенсором
    val isAutoRotationEnabled by viewModel.isAutoRotationEnabled.collectAsState()

    // Застосовуємо орієнтацію залежно від прапорця автоповороту
    LaunchedEffect(videoUrl, isAutoRotationEnabled) {
        if (!isInPipMode) {
            activity?.requestedOrientation = if (isAutoRotationEnabled) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR
            } else {
                ActivityInfo.SCREEN_ORIENTATION_LOCKED
            }
        }
        viewModel.playVideo(videoUrl, title)

        val urlParts = videoUrl.split("/")
        if (urlParts.size >= 5) {
            val hash = urlParts[urlParts.size - 2]
            viewModel.loadFiles(hash)
        }
    }

    // Синхронізатор оверлеїв при зміні стану PiP: завжди приховуємо оверлей для імерсивності
    LaunchedEffect(isInPipMode) {
        areControlsVisible = false
    }

    // Розраховуємо наявність наступної серії
    val nextFile = remember(videoUrl, files) {
        val urlParts = videoUrl.split("/")
        if (urlParts.size >= 5) {
            val fileIndex = urlParts.last().toIntOrNull() ?: return@remember null
            val currentFileIdx = files.indexOfFirst { it.index == fileIndex }
            if (currentFileIdx != -1 && currentFileIdx < files.lastIndex) {
                files[currentFileIdx + 1]
            } else null
        } else null
    }

    // Сувора YouTube-поведінка автоприховування органів керування плеєром
    LaunchedEffect(areControlsVisible, isPlaying, userInteractionTrigger) {
        if (areControlsVisible && isPlaying && !isSpeedingUp && !isInPipMode) {
            delay(3500)
            areControlsVisible = false
        }
    }

    BackHandler {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onBack()
    }

    // Пружинні параметри для плавного ковзання елементів керування у стилі M3 Expressive
    val slideSpringSpec = spring<IntOffset>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessLow
    )
    val fadeSpringSpec = spring<Float>(
        stiffness = Spring.StiffnessLow
    )

    // МОНОЛІТНА ОДНОВІКОННА АРХІТЕКТУРА: Батьківський контейнер жестів кліку
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(
                if (!isInPipMode) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                areControlsVisible = !areControlsVisible
                            },
                            onPress = {
                                val job = scope.launch {
                                    delay(400)
                                    isSpeedingUp = true
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    viewModel.playerInstance.value?.let { player ->
                                        player.playbackParameters = androidx.media3.common.PlaybackParameters(2.0f)
                                    }
                                }
                                pressJob = job
                                try {
                                    awaitRelease()
                                } catch (_: Exception) {
                                    // Очікуване переривання
                                }
                                job.cancel()
                                if (isSpeedingUp) {
                                    isSpeedingUp = false
                                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                    viewModel.playerInstance.value?.let { player ->
                                        player.playbackParameters = androidx.media3.common.PlaybackParameters(1.0f)
                                    }
                                }
                            }
                        )
                    }
                } else Modifier
            )
    ) {
        // Вкладений контейнер Pinch-to-Zoom жестів [1]
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (!isInPipMode) {
                        Modifier.pointerInput(Unit) {
                            detectTransformGestures { _, _, zoom, _ ->
                                currentPinchScale = (currentPinchScale * zoom).coerceIn(1.0f, 2.0f)

                                if (currentPinchScale > 1.2f && resizeModeState != AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
                                    resizeModeState = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                } else if (currentPinchScale <= 1.2f && resizeModeState != AspectRatioFrameLayout.RESIZE_MODE_FIT) {
                                    resizeModeState = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                }
                            }
                        }
                    } else Modifier
                )
                .graphicsLayer {
                    // Якщо ми в режимі PiP, примусово скидаємо масштаб до 1.0f для уникнення обрізок [IX]
                    val visualScale = if (isInPipMode) {
                        1.0f
                    } else if (resizeModeState == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
                        1.08f
                    } else {
                        currentPinchScale.coerceIn(1.0f, 1.15f)
                    }
                    scaleX = visualScale
                    scaleY = visualScale
                }
        ) {
            AndroidView(
                factory = { ctx ->
                    val viewLayout = LayoutInflater.from(ctx).inflate(R.layout.texture_player_view, null, false)
                    val playerView = viewLayout as PlayerView
                    playerView.apply {
                        useController = false
                        keepScreenOn = true
                        resizeMode = resizeModeState

                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setBackgroundColor(android.graphics.Color.BLACK)

                        // Налаштовуємо нативний SubtitleView у фірмовому стилі M3 Expressive
                        subtitleView?.apply {
                            setStyle(androidx.media3.ui.CaptionStyleCompat.DEFAULT)
                            setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.20f) // Збільшуємо шрифт на 20%
                        }

                        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                            override fun onViewAttachedToWindow(v: View) {
                                val activePlayer = PlaybackService.playerInstance.value
                                player = null
                                player = activePlayer

                                post {
                                    activePlayer?.let { p ->
                                        if (p.isPlaying) {
                                            p.seekTo(p.currentPosition)
                                        }
                                    }
                                }
                            }

                            override fun onViewDetachedFromWindow(v: View) {
                                player = null
                            }
                        })
                    }
                },
                update = { playerView ->
                    if (playerView.player != playerInstance) {
                        playerView.player = playerInstance
                    }
                    playerView.resizeMode = resizeModeState
                },
                onRelease = { playerView ->
                    playerView.player = null
                },
                modifier = Modifier
                    .fillMaxSize()
                    // КРИТИЧНО: Вираховуємо точні координати відеокадру БЕЗ чорних смуг для безшовного згортання! [IX]
                    .onGloballyPositioned { layoutCoordinates ->
                        if (!isInPipMode) {
                            val bounds = layoutCoordinates.boundsInWindow()
                            val containerRect = android.graphics.Rect(
                                bounds.left.toInt(),
                                bounds.top.toInt(),
                                bounds.right.toInt(),
                                bounds.bottom.toInt()
                            )

                            val size = videoSize
                            val preciseRect = if (size != null && size.width > 0 && size.height > 0) {
                                calculateVideoRect(containerRect, size.width, size.height)
                            } else {
                                containerRect
                            }
                            viewModel.updateVideoBounds(preciseRect)
                        }
                    }
            )
        }

        // ІНТЕРФЕЙСНІ ОВЕРЛЕЇ: повністю приховуються у PiP-режимі [IX]
        AnimatedVisibility(
            visible = !isInPipMode && areControlsVisible,
            enter = fadeIn(animationSpec = fadeSpringSpec),
            exit = fadeOut(animationSpec = fadeSpringSpec),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                )

                // 1. Upper Panel (Top Bar)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { resetAutohideTimer() }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalIconButton(
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                            onBack()
                        },
                        shape = SquircleShape(cornerRadiusRatio = 0.3f, smoothing = 0.6f),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = Color.White.copy(alpha = 0.15f),
                            contentColor = Color.White
                        ),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    val isAutoPipEnabled by viewModel.isAutoPipEnabled.collectAsState()

                    // Кнопка-перемикач дозволу Picture-in-Picture
                    FilledTonalIconButton(
                        onClick = {
                            resetAutohideTimer()
                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            viewModel.setAutoPipEnabled(!isAutoPipEnabled)
                        },
                        shape = SquircleShape(cornerRadiusRatio = 0.3f, smoothing = 0.6f),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = if (isAutoPipEnabled) MaterialTheme.colorScheme.primaryContainer else Color.White.copy(alpha = 0.15f),
                            contentColor = if (isAutoPipEnabled) MaterialTheme.colorScheme.onPrimaryContainer else Color.White
                        ),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PictureInPicture,
                            contentDescription = "Дозволити картинку в картинці при згортанні додатка",
                            modifier = Modifier
                                .size(20.dp)
                                .graphicsLayer {
                                    alpha = if (isAutoPipEnabled) 1.0f else 0.5f
                                }
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    if (audioTracks.isNotEmpty() || subtitleTracks.isNotEmpty()) {
                        FilledTonalIconButton(
                            onClick = {
                                resetAutohideTimer()
                                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                showAudioSheet = true
                            },
                            shape = SquircleShape(cornerRadiusRatio = 0.3f, smoothing = 0.6f),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = Color.White.copy(alpha = 0.15f),
                                contentColor = Color.White
                            ),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(Icons.Default.Audiotrack, contentDescription = "Аудіо та субтитри")
                        }
                    }
                }

                // 2. Center Panel (Play/Pause/Buffer) - ОНОВЛЕНО: БЕЗШОВНЕ МОРФУВАННЯ ТА ЖОРСТКІ МЕЖІ [IX]
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .align(Alignment.Center)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { resetAutohideTimer() },
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedContent(
                        targetState = isBuffering,
                        transitionSpec = {
                            // Елегантне поєднання зустрічного масштабування та згасання за 220мс без геометричних зсувів [1]
                            (scaleIn(animationSpec = spring(stiffness = Spring.StiffnessMediumLow), initialScale = 0.8f) +
                                    fadeIn(animationSpec = tween(220)))
                                .togetherWith(
                                    scaleOut(animationSpec = spring(stiffness = Spring.StiffnessMediumLow), targetScale = 0.8f) +
                                            fadeOut(animationSpec = tween(220))
                                )
                        },
                        label = "play_pause_buffer_transition",
                        modifier = Modifier.size(76.dp) // ЖОРСТКА ФІКСАЦІЯ: повністю запобігає будь-яким зсувам убік [IX]
                    ) { buffering ->
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            if (buffering) {
                                // А. Під час прогрузки показуємо ТІЛЬКИ значок завантаження [IX]
                                CircularWavyProgressIndicator(
                                    modifier = Modifier.size(72.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                )
                            } else {
                                // Б. Після завантаження показуємо стабільну, нерухому кнопку Play/Pause [IX]
                                val playScale by animateFloatAsState(
                                    targetValue = if (isPlaying) 1.0f else 1.06f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    ),
                                    label = "play_scale"
                                )

                                FilledTonalIconButton(
                                    onClick = {
                                        resetAutohideTimer()
                                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                        viewModel.togglePlayback()
                                    },
                                    shape = SquircleShape(cornerRadiusRatio = 0.3f, smoothing = 0.6f),
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    ),
                                    modifier = Modifier
                                        .size(72.dp)
                                        .scale(playScale)
                                ) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = "Відтворення",
                                        modifier = Modifier.size(36.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Швидкі перемотки (ліва та права), розміщені стабільно на відстані від центру
                Row(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(0.6f),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalIconButton(
                        onClick = {
                            resetAutohideTimer()
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            viewModel.seekToPosition((position - 10000L).coerceAtLeast(0L))
                        },
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = Color.White.copy(alpha = 0.12f),
                            contentColor = Color.White
                        ),
                        modifier = Modifier.size(54.dp)
                    ) {
                        Icon(Icons.Default.Replay10, null, modifier = Modifier.size(26.dp))
                    }

                    Spacer(modifier = Modifier.width(96.dp)) // Простір для нашої стабільної центральної кнопки

                    FilledTonalIconButton(
                        onClick = {
                            resetAutohideTimer()
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            viewModel.seekToPosition((position + 10000L).coerceAtMost(duration))
                        },
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = Color.White.copy(alpha = 0.12f),
                            contentColor = Color.White
                        ),
                        modifier = Modifier.size(54.dp)
                    ) {
                        Icon(Icons.Default.Forward10, null, modifier = Modifier.size(26.dp))
                    }
                }

                // 3. Lower Panel (Timeline)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { resetAutohideTimer() }
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val displayPosition = if (isDragging) localSliderValue.toLong() else position

                            Box(
                                modifier = Modifier
                                    .background(
                                        color = Color.Black.copy(alpha = 0.5f),
                                        shape = SquircleShape(cornerRadiusRatio = 0.3f, smoothing = 0.6f)
                                    )
                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "${formatTime(displayPosition)} / ${formatTime(duration)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Black,
                                    color = Color.White
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                FilledTonalIconButton(
                                    onClick = {
                                        resetAutohideTimer()
                                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                        resizeModeState = if (resizeModeState == AspectRatioFrameLayout.RESIZE_MODE_FIT) {
                                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                        } else {
                                            AspectRatioFrameLayout.RESIZE_MODE_FIT
                                        }
                                    },
                                    shape = SquircleShape(cornerRadiusRatio = 0.3f, smoothing = 0.6f),
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = Color.White.copy(alpha = 0.15f),
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        imageVector = if (resizeModeState == AspectRatioFrameLayout.RESIZE_MODE_FIT) Icons.Default.Fullscreen else Icons.Default.FullscreenExit,
                                        contentDescription = "Масштабування"
                                    )
                                }

                                // ОДИНАРНА КНОПКА СЕНСОРНОГО АВТОПОВОРОТУ
                                FilledTonalIconButton(
                                    onClick = {
                                        resetAutohideTimer()
                                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                        viewModel.setAutoRotationEnabled(!isAutoRotationEnabled)
                                    },
                                    shape = SquircleShape(cornerRadiusRatio = 0.3f, smoothing = 0.6f),
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = if (isAutoRotationEnabled) MaterialTheme.colorScheme.primaryContainer else Color.White.copy(alpha = 0.15f),
                                        contentColor = if (isAutoRotationEnabled) MaterialTheme.colorScheme.onPrimaryContainer else Color.White
                                    ),
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ScreenRotation,
                                        contentDescription = "Автоматичний поворот екрана за сенсором",
                                        modifier = Modifier.alpha(if (isAutoRotationEnabled) 1.0f else 0.5f)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Slider(
                            value = localSliderValue,
                            onValueChange = { newValue ->
                                isDragging = true
                                resetAutohideTimer()
                                localSliderValue = newValue
                            },
                            onValueChangeFinished = {
                                viewModel.seekToPosition(localSliderValue.toLong())
                                scope.launch {
                                    delay(500)
                                    isDragging = false
                                }
                            },
                            valueRange = 0f..(duration.toFloat().coerceAtLeast(1f)),
                            colors = SliderDefaults.colors(
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = Color.White.copy(alpha = 0.24f),
                                thumbColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // ШТОРКА ВИБОРУ АУДІО ТА СУБТИТРИ (DУАЛ-ТАБ ІНТЕРФЕЙС)
        if (!isInPipMode && showAudioSheet) {
            ModalBottomSheet(
                onDismissRequest = { showAudioSheet = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                shape = RoundedCornerShape(32.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                sheetGesturesEnabled = false,
                dragHandle = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
                            .background(
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                shape = SquircleShape(cornerRadiusRatio = 0.35f, smoothing = 0.6f)
                            )
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Потоки відтворення",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.CenterStart)
                        )

                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .width(36.dp)
                                .height(4.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                                    shape = CircleShape
                                )
                        )

                        Spacer(modifier = Modifier.size(24.dp).align(Alignment.CenterEnd))
                    }
                },
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp)
            ) {
                var activeTab by remember { mutableIntStateOf(0) }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SegmentedButton(
                            selected = activeTab == 0,
                            onClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                activeTab = 0
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.Audiotrack, null, modifier = Modifier.size(16.dp))
                                Text("Звук", fontWeight = FontWeight.Bold)
                            }
                        }
                        SegmentedButton(
                            selected = activeTab == 1,
                            onClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                activeTab = 1
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.Subtitles, null, modifier = Modifier.size(16.dp))
                                Text("Субтитри", fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    AnimatedContent(
                        targetState = activeTab,
                        transitionSpec = {
                            (fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) +
                                    scaleIn(initialScale = 0.96f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)))
                                .togetherWith(fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)))
                        },
                        label = "tab_switch"
                    ) { tab ->
                        when (tab) {
                            0 -> {
                                LazyColumn(
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.heightIn(max = 280.dp)
                                ) {
                                    items(audioTracks) { track: AudioTrackInfo ->
                                        val isSelected = track.isSelected
                                        val isSupported = track.isSupported

                                        Surface(
                                            onClick = {
                                                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                                viewModel.changeAudioTrack(track.groupIndex, track.trackIndex)
                                                showAudioSheet = false
                                            },
                                            shape = SquircleShape(cornerRadiusRatio = 0.25f, smoothing = 0.6f),
                                            color = if (isSelected) {
                                                MaterialTheme.colorScheme.primaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.surfaceContainerLow
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(16.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Column {
                                                    Text(
                                                        text = if (isSupported) track.label else "${track.label} (можливі проблеми з відтворенням)",
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                                    )
                                                    Text(
                                                        text = "Мова: ${track.language.uppercase(Locale.getDefault())}",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                if (isSelected) {
                                                    Icon(
                                                        imageVector = Icons.Default.CheckCircle,
                                                        contentDescription = "Обрано",
                                                        tint = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            1 -> {
                                val isAnySubtitleActive = subtitleTracks.any { it.isSelected }

                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Surface(
                                        onClick = {
                                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                            viewModel.disableSubtitles()
                                            showAudioSheet = false
                                        },
                                        shape = SquircleShape(cornerRadiusRatio = 0.25f, smoothing = 0.6f),
                                        color = if (!isAnySubtitleActive) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(14.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.SubtitlesOff,
                                                contentDescription = null,
                                                tint = if (!isAnySubtitleActive) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.error
                                            )
                                            Column {
                                                Text(
                                                    text = "Вимкнути субтитри",
                                                    fontWeight = FontWeight.Black,
                                                    color = if (!isAnySubtitleActive) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
                                                )
                                                Text(
                                                    text = "Запобігає зависанню відео на повільних пірах",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (!isAnySubtitleActive) MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }

                                    if (subtitleTracks.isEmpty()) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 32.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "Субтитри відсутні у цьому відеофайлі",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    } else {
                                        LazyColumn(
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.heightIn(max = 220.dp)
                                        ) {
                                            items(subtitleTracks) { track: SubtitleTrackInfo ->
                                                val isSelected = track.isSelected
                                                val isSupported = track.isSupported

                                                Surface(
                                                    onClick = {
                                                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                                        viewModel.changeSubtitleTrack(track.groupIndex, track.trackIndex)
                                                        showAudioSheet = false
                                                    },
                                                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(16.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Column {
                                                            Text(
                                                                text = if (isSupported) track.label else "${track.label} (можливі проблеми)",
                                                                fontWeight = FontWeight.Bold,
                                                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                                            )
                                                            Text(
                                                                text = "Мова: ${track.language.uppercase(Locale.getDefault())}",
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                        }
                                                        if (isSelected) {
                                                            Icon(
                                                                imageVector = Icons.Default.CheckCircle,
                                                                contentDescription = "Обрано",
                                                                tint = MaterialTheme.colorScheme.primary
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// Математичний алгоритм розрахунку граней відеокадру без чорних letterbox-полів [IX]
private fun calculateVideoRect(
    containerRect: android.graphics.Rect,
    videoWidth: Int,
    videoHeight: Int
): android.graphics.Rect {
    if (videoWidth <= 0 || videoHeight <= 0) return containerRect

    val containerWidth = containerRect.width()
    val containerHeight = containerRect.height()
    if (containerWidth <= 0 || containerHeight <= 0) return containerRect

    val videoRatio = videoWidth.toFloat() / videoHeight
    val containerRatio = containerWidth.toFloat() / containerHeight

    return if (videoRatio > containerRatio) {
        val displayedHeight = (containerWidth / videoRatio).toInt()
        val yPadding = (containerHeight - displayedHeight) / 2
        android.graphics.Rect(
            containerRect.left,
            containerRect.top + yPadding,
            containerRect.right,
            containerRect.bottom - yPadding
        )
    } else {
        val displayedWidth = (containerHeight * videoRatio).toInt()
        val xPadding = (containerWidth - displayedWidth) / 2
        android.graphics.Rect(
            containerRect.left + xPadding,
            containerRect.top,
            containerRect.right - xPadding,
            containerRect.bottom
        )
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}