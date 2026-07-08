package com.example.torrentstreamer

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@SuppressLint("SourceLockedOrientationActivity")
@Composable
fun PlayerScreen(
    videoUrl: String,
    title: String,
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    val isPlaying by viewModel.isPlayerPlaying.collectAsState()
    val position by viewModel.playerPosition.collectAsState()
    val duration by viewModel.playerDuration.collectAsState()
    val audioTracks by viewModel.availableAudioTracks.collectAsState()
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

    // 1. YouTube-style Pinch-to-Zoom стани
    var currentPinchScale by remember { mutableFloatStateOf(1f) }
    var resizeModeState by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }

    // 2. 2X прискорення на лонг-прес
    var isSpeedingUp by remember { mutableStateOf(false) }
    var pressJob by remember { mutableStateOf<Job?>(null) }

    // Тригер взаємодії користувача для скидання таймера приховування
    var userInteractionTrigger by remember { mutableLongStateOf(0L) }
    val resetAutohideTimer = { userInteractionTrigger = System.currentTimeMillis() }

    // 3. Імерсивний режим (приховування статус-барів, годинника, батареї)
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

    LaunchedEffect(videoUrl) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        viewModel.playVideo(videoUrl, title)

        // Фонове завантаження серій для надійної роботи Next/Prev кнопок
        val urlParts = videoUrl.split("/")
        if (urlParts.size >= 5) {
            val hash = urlParts[urlParts.size - 2]
            viewModel.loadFiles(hash)
        }
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
        if (areControlsVisible && isPlaying && !isSpeedingUp) {
            delay(3500)
            areControlsVisible = false
        }
    }

    BackHandler {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onBack()
    }

    // Пружинні спеки для плавного ковзання елементів керування у стилі M3 Expressive
    val slideSpringSpec = spring<IntOffset>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessLow
    )
    val fadeSpringSpec = spring<Float>(
        stiffness = Spring.StiffnessLow
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black) // Чистий, глибокий чорний фон без зайвого навантаження!
            .pointerInput(Unit) {
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
                        } catch (e: Exception) {
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
    ) {
        // АПАРАТНА ВІДЕО ПОВЕРХНЯ З PINCH-TO-ZOOM
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
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
                .graphicsLayer {
                    val visualScale = if (resizeModeState == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
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
                        player = PlaybackService.playerInstance.value
                        useController = false
                        keepScreenOn = true
                        resizeMode = resizeModeState

                        // Апаратна підкладка TextureView (тепер повністю оптимізована під чистий чорний колір)
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        setBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                update = { playerView ->
                    playerView.player = PlaybackService.playerInstance.value
                    playerView.resizeMode = resizeModeState
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // КІНЕМАТОГРАФІЧНИЙ ІНДИКАТОР 2Х ШВИДКОСТІ
        AnimatedVisibility(
            visible = isSpeedingUp,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 70.dp)
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                shape = SquircleShape(cornerRadiusRatio = 0.3f, smoothing = 0.6f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.FastForward, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Text(
                        text = "2.0x  Швидкість",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }

        // Чорна задня підкладка-затемнення згасає незалежно від руху панелей
        AnimatedVisibility(
            visible = areControlsVisible,
            enter = fadeIn(animationSpec = fadeSpringSpec),
            exit = fadeOut(animationSpec = fadeSpringSpec),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
            )
        }

        // 1. Upper Panel (Top Bar)
        AnimatedVisibility(
            visible = areControlsVisible,
            enter = slideInVertically(animationSpec = slideSpringSpec) { -it } + fadeIn(animationSpec = fadeSpringSpec),
            exit = slideOutVertically(animationSpec = slideSpringSpec) { -it } + fadeOut(animationSpec = fadeSpringSpec),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
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

                if (audioTracks.isNotEmpty()) {
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
                        Icon(Icons.Default.Audiotrack, contentDescription = "Аудіодоріжки")
                    }
                }
            }
        }

        // 2. Center Panel (Play/Pause/Buffer)
        AnimatedVisibility(
            visible = areControlsVisible,
            enter = fadeIn(animationSpec = fadeSpringSpec),
            exit = fadeOut(animationSpec = fadeSpringSpec),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Row(
                modifier = Modifier
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { resetAutohideTimer() }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(40.dp)
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

                AnimatedContent(
                    targetState = isBuffering,
                    transitionSpec = {
                        (scaleIn(animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow)) + fadeIn())
                            .togetherWith(scaleOut(animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow)) + fadeOut())
                    },
                    label = "morph"
                ) { buffering ->
                    if (buffering) {
                        CircularWavyProgressIndicator(
                            modifier = Modifier.size(76.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        )
                    } else {
                        val playScale by animateFloatAsState(
                            targetValue = if (isPlaying) 1f else 1.12f,
                            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
                            label = "play_pulse"
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
                                .size(76.dp)
                                .scale(playScale)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Відтворення",
                                modifier = Modifier.size(38.dp)
                            )
                        }
                    }
                }

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
        }

        // Спливаюча інтелектуальна кнопка «Наступна серія» за 2 хвилини до кінця серіалу
        val isNearEnd = duration > 0 && position >= (duration - 120_000L)
        val showNextEpisodeButton = nextFile != null && isNearEnd

        AnimatedVisibility(
            visible = showNextEpisodeButton,
            enter = slideInHorizontally(animationSpec = spring(stiffness = Spring.StiffnessLow)) { it } + fadeIn(),
            exit = slideOutHorizontally(animationSpec = spring(stiffness = Spring.StiffnessLow)) { it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 160.dp, end = 24.dp)
                .zIndex(5f)
        ) {
            Button(
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    viewModel.playNextEpisode()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                shape = SquircleShape(cornerRadiusRatio = 0.3f, smoothing = 0.6f),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp)
            ) {
                Icon(Icons.Default.SkipNext, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Наступна серія", fontWeight = FontWeight.Bold)
            }
        }

        // 3. Lower Panel (Timeline)
        AnimatedVisibility(
            visible = areControlsVisible,
            enter = slideInVertically(animationSpec = slideSpringSpec) { it } + fadeIn(animationSpec = fadeSpringSpec),
            exit = slideOutVertically(animationSpec = slideSpringSpec) { it } + fadeOut(animationSpec = fadeSpringSpec),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
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

                            FilledTonalIconButton(
                                onClick = {
                                    resetAutohideTimer()
                                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                    val currentOrient = activity?.requestedOrientation
                                    activity?.requestedOrientation = if (currentOrient == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                    } else {
                                        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                                    }
                                },
                                shape = SquircleShape(cornerRadiusRatio = 0.3f, smoothing = 0.6f),
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = Color.White.copy(alpha = 0.15f),
                                    contentColor = Color.White
                                ),
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(Icons.Default.ScreenRotation, contentDescription = "Повернути екран")
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

    // ШТОРКА ВИБОРУ АУДІОДОРІЖОК
    if (showAudioSheet) {
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
                        text = "Оберіть аудіодоріжку",
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp)
            ) {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(audioTracks) { track: AudioTrackInfo ->
                        val isSelected = track.isSelected
                        Surface(
                            onClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                viewModel.changeAudioTrack(track.groupIndex, track.trackIndex)
                                showAudioSheet = false
                            },
                            shape = SquircleShape(cornerRadiusRatio = 0.25f, smoothing = 0.6f),
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
                                        text = track.label,
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
        }
    }
}

private fun primaryColorHarmonized(primaryColor: Color): Color {
    return primaryColor.copy(alpha = 0.35f)
}

private fun glowPulseEnabled(glowStep: Int, isPlaying: Boolean): Boolean {
    return isPlaying && (glowStep == 1)
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