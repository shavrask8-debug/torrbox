package com.example.torrentstreamer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.ScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.example.torrentstreamer.data.AppDatabase
import com.example.torrentstreamer.data.WatchHistory
import com.example.torrentstreamer.ui.theme.SquircleShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FileSelectionScreen(
    torrent: Torrent,
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onFileSelect: (String, String) -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    val files by viewModel.files.collectAsState()
    val isLoadingFiles by viewModel.isLoadingFiles.collectAsState()
    val watchHistory by viewModel.watchHistory.collectAsState()

    // ОНОВЛЕНО: Додано підписку на потік сесії для розрахунку відступів
    val latestSession by viewModel.latestSession.collectAsState()
    val density = LocalDensity.current

    val dao = remember { AppDatabase.getDatabase(context.applicationContext).watchHistoryDao() }
    var activeActionFile by remember { mutableStateOf<TorrentFile?>(null) }

    val indicatorHeightPx = remember(density) { with(density) { 56.dp.toPx() } }

    var showLoader by remember { mutableStateOf(false) }
    LaunchedEffect(isLoadingFiles) {
        if (isLoadingFiles) {
            delay(200)
            showLoader = true
        } else {
            showLoader = false
        }
    }

    LaunchedEffect(torrent.hash) {
        viewModel.loadFiles(torrent.hash)
    }

    LaunchedEffect(isLoadingFiles) {
        if (isLoadingFiles) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    DisposableEffect(torrent.hash) {
        onDispose {
            viewModel.clearFiles()
        }
    }

    val promptVlc = remember {
        val sharedPrefs = context.getSharedPreferences("vibe_prefs", Context.MODE_PRIVATE)
        sharedPrefs.getBoolean("prompt_vlc", false)
    }

    val pullState = rememberPullToRefreshState()
    val refreshTargetOffsetPx = remember(density) { with(density) { 80.dp.toPx() } }

    val pullProgress by animateFloatAsState(
        targetValue = pullState.distanceFraction.coerceIn(0f, 1f),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "pull_progress_smooth"
    )

    val loaderAlphaAndScale by animateFloatAsState(
        targetValue = if (isLoadingFiles) 1f else pullProgress,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "loader_alpha_scale"
    )

    val currentPullOffsetPx = with(density) { (80.dp * pullProgress).toPx() }

    val contentTargetOffset = if (isLoadingFiles) {
        refreshTargetOffsetPx
    } else if (pullState.distanceFraction > 0f) {
        currentPullOffsetPx
    } else {
        0f
    }

    val animatedContentOffset by animateFloatAsState(
        targetValue = contentTargetOffset,
        animationSpec = if (isLoadingFiles) {
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
        } else {
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
        },
        label = "elastic_files_offset"
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    FilledTonalIconButton(
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            onBack()
                        },
                        shape = SquircleShape(cornerRadiusRatio = 0.3f, smoothing = 0.6f),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                modifier = Modifier.statusBarsPadding()
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .pullToRefresh(
                    isRefreshing = isLoadingFiles,
                    state = pullState,
                    onRefresh = {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        viewModel.loadFiles(torrent.hash, force = true)
                    }
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                // 1. СТАТИЧНА КАРТКА ТОРРЕНТА (Залізно зафіксована у верхній частині)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .zIndex(2f)
                ) {
                    TorrentHeroHeader(
                        torrent = torrent,
                        filesCount = files.size,
                        watchHistory = watchHistory
                    )
                }

                if (files.isEmpty() && !showLoader) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Файли завантажуються або відсутні.\nСпробуйте оновити список через Pull-to-Refresh.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(32.dp)
                        )
                    }
                } else {
                    // 2. ЗОНА ФАЙЛІВ (Має нижчий Z-Index, що дозволяє індикатору висуватись з-під картки)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clipToBounds()
                            .zIndex(1f)
                    ) {
                        val currentUrl by viewModel.currentPlayingUrl.collectAsState()
                        val hasMiniPlayer = latestSession != null || currentUrl != null

                        // Рухомий LazyColumn
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    translationY = animatedContentOffset
                                }
                        ) {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .overscroll(rememberOverscrollEffect()),
                                contentPadding = PaddingValues(
                                    start = 16.dp,
                                    end = 16.dp,
                                    bottom = if (hasMiniPlayer) 100.dp else 24.dp
                                ),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(
                                    items = files,
                                    key = { file: TorrentFile -> file.index }
                                ) { file: TorrentFile ->
                                    val cleanFileName = file.path.substringAfterLast("/")
                                    val streamUrl = "http://127.0.0.1:8090/play/${torrent.hash}/${file.index}"
                                    val historyItem = watchHistory.find { it.videoUrl == streamUrl }

                                    TorrentFileExpressiveCard(
                                        fileName = cleanFileName,
                                        fileSize = file.size,
                                        historyItem = historyItem,
                                        promptVlcEnabled = promptVlc,
                                        onClick = {
                                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                            onFileSelect(streamUrl, cleanFileName)
                                        },
                                        onLongClick = {
                                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                            activeActionFile = file
                                        },
                                        onExternalClick = {
                                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                            openInVlc(context, streamUrl, cleanFileName)
                                        }
                                    )
                                }
                            }
                        }

                        if (loaderAlphaAndScale > 0.01f || isLoadingFiles) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .graphicsLayer {
                                        translationY = animatedContentOffset * ratioCalculate(targetGapPx = 12.dp.toPx(), indicatorHeightPx = indicatorHeightPx.toFloat(), refreshTargetOffsetPx = refreshTargetOffsetPx) - indicatorHeightPx
                                        scaleX = loaderAlphaAndScale
                                        scaleY = loaderAlphaAndScale
                                        alpha = loaderAlphaAndScale
                                    }
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .padding(8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isLoadingFiles) {
                                    CircularWavyProgressIndicator(
                                        modifier = Modifier.size(40.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                    )
                                } else {
                                    CircularWavyProgressIndicator(
                                        modifier = Modifier.size(40.dp),
                                        progress = { pullProgress },
                                        color = MaterialTheme.colorScheme.primary,
                                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    activeActionFile?.let { file ->
        val cleanFileName = file.path.substringAfterLast("/")
        val streamUrl = "http://127.0.0.1:8090/play/${torrent.hash}/${file.index}"
        val historyItem = watchHistory.find { it.videoUrl == streamUrl }
        val isFinished = historyItem?.isFinished == true

        FileActionsBottomSheet(
            fileName = cleanFileName,
            fileSize = file.size,
            isWatched = isFinished,
            promptVlcEnabled = promptVlc,
            onDismiss = { activeActionFile = null },
            onToggleWatched = {
                scope.launch(Dispatchers.IO) {
                    if (isFinished) {
                        dao.saveProgress(
                            WatchHistory(
                                videoUrl = streamUrl,
                                title = cleanFileName,
                                lastPosition = 0L,
                                duration = 1000L,
                                isFinished = false,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    } else {
                        dao.saveProgress(
                            WatchHistory(
                                videoUrl = streamUrl,
                                title = cleanFileName,
                                lastPosition = 1000L,
                                duration = 1000L,
                                isFinished = true,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    }
                }
            },
            onOpenInExternal = {
                openInVlc(context, streamUrl, cleanFileName)
            },
            onCopyLink = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Streaming Link", streamUrl))
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            }
        )
    }
}

private fun ratioCalculate(targetGapPx: Float, indicatorHeightPx: Float, refreshTargetOffsetPx: Float): Float {
    return (targetGapPx + indicatorHeightPx) / refreshTargetOffsetPx
}

@Composable
fun TorrentHeroHeader(
    torrent: Torrent,
    filesCount: Int,
    watchHistory: List<WatchHistory>
) {
    val totalFiles = filesCount
    val watchedCount = watchHistory.count { it.videoUrl.contains(torrent.hash) && it.isFinished }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = SquircleShape(cornerRadiusRatio = 0.16f, smoothing = 0.6f),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(width = 90.dp, height = 135.dp)
                    .clip(SquircleShape(cornerRadiusRatio = 0.16f, smoothing = 0.6f))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (!torrent.poster.isNullOrBlank()) {
                    AsyncImage(
                        model = torrent.poster,
                        contentDescription = "Обкладинка",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Movie,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = SquircleShape(cornerRadiusRatio = 0.3f, smoothing = 0.6f),
                    modifier = Modifier.wrapContentSize()
                ) {
                    Text(
                        text = torrent.type ?: "Фільм",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                Text(
                    text = torrent.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Всього серій: $totalFiles",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (watchedCount > 0) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Переглянуто: $watchedCount / $totalFiles",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TorrentFileExpressiveCard(
    fileName: String,
    fileSize: Long,
    historyItem: WatchHistory?,
    promptVlcEnabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onExternalClick: () -> Unit
) {
    val view = LocalView.current
    val cardShape = SquircleShape(cornerRadiusRatio = 0.16f, smoothing = 0.6f)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "file_card_scale"
    )

    val isFinished = historyItem?.isFinished == true
    val progress = if (historyItem != null && historyItem.duration > 0) {
        historyItem.lastPosition.toFloat() / historyItem.duration.toFloat()
    } else 0f

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(cardShape)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
                onLongClick = onLongClick
            ),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = cardShape
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = if (isFinished) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer,
                    shape = SquircleShape(cornerRadiusRatio = 0.3f, smoothing = 0.6f),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isFinished) Icons.Default.CheckCircle else Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = if (isFinished) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = fileName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = formatFileSize(fileSize),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (historyItem != null && !isFinished && historyItem.duration > 0) {
                            val remainingMs = historyItem.duration - historyItem.lastPosition
                            val remainingMin = (remainingMs / 1000 / 60).toInt()
                            if (remainingMin > 0) {
                                Text(
                                    text = "· залишок $remainingMin хв",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                if (promptVlcEnabled) {
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            onExternalClick()
                        },
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.secondaryContainer,
                                SquircleShape(cornerRadiusRatio = 0.3f, smoothing = 0.6f)
                            )
                            .size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.OpenInNew,
                            contentDescription = "Відкрити у VLC",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            if (progress > 0f && !isFinished) {
                LinearWavyProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FileActionsBottomSheet(
    fileName: String,
    fileSize: Long,
    isWatched: Boolean,
    promptVlcEnabled: Boolean,
    onDismiss: () -> Unit,
    onToggleWatched: () -> Unit,
    onOpenInExternal: () -> Unit,
    onCopyLink: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val view = LocalView.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(32.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        sheetGesturesEnabled = false,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        modifier = Modifier
            .widthIn(max = 560.dp)
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = SquircleShape(cornerRadiusRatio = 0.25f, smoothing = 0.6f),
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Movie,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = fileName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = formatFileSize(fileSize),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            ListItem(
                headlineContent = {
                    Text(
                        text = if (isWatched) "Позначити як непереглянуте" else "Позначити як переглянуте",
                        fontWeight = FontWeight.Bold
                    )
                },
                supportingContent = {
                    Text(text = if (isWatched) "Скинути позначку завершення" else "Встановити прапорець завершення")
                },
                leadingContent = {
                    Icon(
                        imageVector = if (isWatched) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .clickable {
                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        onToggleWatched()
                        onDismiss()
                    }
            )

            if (promptVlcEnabled) {
                ListItem(
                    headlineContent = { Text("Відкрити у зовнішньому плеєрі", fontWeight = FontWeight.Bold) },
                    supportingContent = { Text("Запустити відтворення через VLC або інший плеєр") },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Default.OpenInNew,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .clickable {
                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            onOpenInExternal()
                            onDismiss()
                        }
                )
            }

            ListItem(
                headlineContent = { Text("Копіювати посилання", fontWeight = FontWeight.Bold) },
                supportingContent = { Text("Зберегти пряму адресу потоку в буфер обміну") },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .clickable {
                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        onCopyLink()
                        onDismiss()
                    }
            )
        }
    }
}

private fun openInVlc(context: Context, videoUrl: String, title: String) {
    try {
        val uri = Uri.parse(videoUrl)
        val vlcIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            setPackage("org.videolan.vlc")
            putExtra("title", title)
            putExtra("from_start", false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(vlcIntent)
    } catch (e: Exception) {
        val genericIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(videoUrl), "video/*")
            putExtra("title", title)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(genericIntent, "Оберіть плеєр для відтворення"))
    }
}

private fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 Б"
    val units = arrayOf("Б", "КБ", "МБ", "ГБ", "ТБ")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.toDouble())).toInt()
    return String.format(Locale.US, "%.2f %s", size / Math.pow(1024.toDouble(), digitGroups.toDouble()), units[digitGroups])
}