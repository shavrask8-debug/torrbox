package com.example.torrentstreamer

import android.content.Context
import android.view.HapticFeedbackConstants
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.example.torrentstreamer.data.WatchHistory
import com.example.torrentstreamer.ui.theme.SquircleShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MiniPlayer(
    viewModel: MainViewModel,
    onClick: () -> Unit
) {
    val view = LocalView.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val currentUrl by viewModel.currentPlayingUrl.collectAsState()
    val currentTitle by viewModel.currentPlayingTitle.collectAsState()
    val isPlaying by viewModel.isPlayerPlaying.collectAsState()
    val position by viewModel.playerPosition.collectAsState()
    val duration by viewModel.playerDuration.collectAsState()
    val isBuffering by viewModel.isBuffering.collectAsState()

    val latestSession by viewModel.latestSession.collectAsState()
    val filesList by viewModel.files.collectAsState()

    // Автоматичне зчитування обкладинки торента із SharedPreferences за хешем URL
    val activeTitle = currentTitle ?: latestSession?.title ?: return
    val activeUrl = currentUrl ?: latestSession?.videoUrl ?: return
    val activePos = if (currentUrl != null) position else (latestSession?.lastPosition ?: 0L)
    val activeDur = if (currentUrl != null) duration else (latestSession?.duration ?: 0L)

    val torrentHash = remember(activeUrl) {
        val parts = activeUrl.split("/")
        if (parts.size >= 5) parts[parts.size - 2] else null
    }

    // Запускаємо фонове завантаження серій для надійної роботи Next/Prev кнопок
    LaunchedEffect(torrentHash) {
        torrentHash?.let { viewModel.loadFiles(it) }
    }

    val posterPrefs = remember(context) { context.getSharedPreferences("torrent_posters", Context.MODE_PRIVATE) }
    val posterUrl = remember(torrentHash) {
        torrentHash?.let { posterPrefs.getString(it, null) }
    }

    // Розрахунок активності кнопок Попередня / Наступна серія
    val hasNext = remember(activeUrl, filesList) {
        val parts = activeUrl.split("/")
        if (parts.size >= 5) {
            val fileIndex = parts.last().toIntOrNull() ?: return@remember false
            val currentFileIdx = filesList.indexOfFirst { it.index == fileIndex }
            currentFileIdx != -1 && currentFileIdx < filesList.lastIndex
        } else false
    }

    val hasPrev = remember(activeUrl, filesList) {
        val parts = activeUrl.split("/")
        if (parts.size >= 5) {
            val fileIndex = parts.last().toIntOrNull() ?: return@remember false
            val currentFileIdx = filesList.indexOfFirst { it.index == fileIndex }
            currentFileIdx > 0
        } else false
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val cardScale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "miniplayer_press"
    )

    // Стейт скраббінгу Slider
    var isDragging by remember { mutableStateOf(false) }
    var localSliderValue by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(activePos, isDragging) {
        if (!isDragging) {
            localSliderValue = activePos.toFloat()
        }
    }

    // Розрахунок часу відтворення в реальному часі під час скролу
    val displayPos = if (isDragging) localSliderValue.toLong() else activePos
    val timeText = remember(displayPos, activeDur) {
        "${formatTimeShort(displayPos)} / ${formatTimeShort(activeDur)}"
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp)
            .scale(cardScale)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onClick()
                }
            ),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.85f),
        shape = SquircleShape(cornerRadiusRatio = 0.25f, smoothing = 0.6f),
        tonalElevation = 8.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // А. Рендеринг обкладинки торента у формі Squircle
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(SquircleShape(cornerRadiusRatio = 0.25f, smoothing = 0.6f))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (!posterUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = posterUrl,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(Icons.Default.Movie, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                ) {
                    Text(
                        text = if (currentUrl != null) "ЗАРАЗ ГРАЄ · $timeText" else "ПРОДОВЖИТИ · $timeText",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Black,
                        fontSize = 9.sp
                    )
                    Text(
                        text = activeTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // В. Кнопка Play/Pause інтегрована строго ПОСЕРЕДИНІ між SkipPrevious та SkipNext
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            viewModel.playPreviousEpisode()
                        },
                        enabled = hasPrev,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "Попередня серія",
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Box(
                        modifier = Modifier.size(36.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        AnimatedContent(
                            targetState = isBuffering,
                            transitionSpec = {
                                (scaleIn(animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow)) + fadeIn())
                                    .togetherWith(scaleOut(animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow)) + fadeOut())
                            },
                            label = "mini_morph"
                        ) { buffering ->
                            if (buffering && currentUrl != null) {
                                CircularWavyProgressIndicator(
                                    modifier = Modifier.size(30.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                )
                            } else {
                                IconButton(
                                    onClick = {
                                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                        if (currentUrl == null) {
                                            viewModel.playVideo(activeUrl, activeTitle)
                                        } else {
                                            viewModel.togglePlayback()
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    Icon(
                                        imageVector = if (isPlaying && currentUrl != null) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = "Відтворення",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                        }
                    }

                    IconButton(
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            viewModel.playNextEpisode()
                        },
                        enabled = hasNext,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Наступна серія",
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            // Г. Слайдер прогресу відтворення
            val sliderValue = if (activeDur > 0f) localSliderValue else 0f
            Slider(
                value = sliderValue,
                onValueChange = { newValue ->
                    if (currentUrl != null) {
                        isDragging = true
                        localSliderValue = newValue
                    }
                },
                onValueChangeFinished = {
                    if (currentUrl != null) {
                        // ОНОВЛЕНО: Амортизаційна затримка усуває гліч зворотної синхронізації при відпусканні Slider в MiniPlayer!
                        viewModel.seekToPosition(localSliderValue.toLong())
                        scope.launch {
                            delay(500)
                            isDragging = false
                        }
                    }
                },
                valueRange = 0f..(activeDur.toFloat().coerceAtLeast(1f)),
                colors = SliderDefaults.colors(
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    thumbColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 6.dp)
                    .height(12.dp)
            )
        }
    }
}

private fun formatTimeShort(ms: Long): String {
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