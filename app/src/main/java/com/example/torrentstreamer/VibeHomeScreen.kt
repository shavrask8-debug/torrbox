package com.example.torrentstreamer

import android.content.Context
import android.net.Uri
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.ScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.input.pointer.pointerInput
import coil3.compose.AsyncImage
import com.example.torrentstreamer.ui.theme.SquircleShape
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

@OptIn(
    ExperimentalFoundationApi::class,
    ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class,
    ExperimentalMaterial3ExpressiveApi::class
)
@Composable
fun VibeHomeScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    onTorrentClick: (Torrent) -> Unit,
    onOpenAdmin: () -> Unit,
    onResumeClick: (com.example.torrentstreamer.data.WatchHistory) -> Unit
) {
    val torrents by viewModel.torrents.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val latestSession by viewModel.latestSession.collectAsState()

    var editingTorrent by remember { mutableStateOf<Torrent?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var isFabMenuOpen by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val view = LocalView.current
    val appName = remember { context.getString(R.string.app_name) }
    val density = LocalDensity.current

    val torrentFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            viewModel.addTorrentFromFile(uri)
        }
    }

    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    val filteredTorrents = remember(torrents, searchQuery) {
        if (searchQuery.isBlank()) {
            torrents
        } else {
            torrents.filter { torrent: Torrent ->
                torrent.title.contains(searchQuery, ignoreCase = true) ||
                        (torrent.type ?: "Фільм").contains(searchQuery, ignoreCase = true)
            }
        }
    }

    val editableList = remember { mutableStateListOf<Torrent>() }
    var draggedKey by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(filteredTorrents) {
        if (draggedKey == null) {
            val needsUpdate = editableList.size != filteredTorrents.size ||
                    !editableList.zip(filteredTorrents).all { (a, b) ->
                        a.hash == b.hash && a.title == b.title && a.poster == b.poster && a.type == b.type
                    }
            if (needsUpdate) {
                editableList.clear()
                editableList.addAll(filteredTorrents)
            }
        }
    }

    val gridState = rememberLazyStaggeredGridState()
    var initiallyDraggedItemOffset by remember { mutableStateOf<IntOffset?>(null) }
    var draggedSize by remember { mutableStateOf<IntSize?>(null) }
    var dragOffset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var lastSwapTime by remember { mutableLongStateOf(0L) }

    var settingsRotationTrigger by remember { mutableFloatStateOf(0f) }
    val settingsRotationAngle by animateFloatAsState(
        targetValue = settingsRotationTrigger,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "settings_rotation"
    )

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

    val currentPullOffsetPx = with(density) { (80.dp * pullProgress).toPx() }

    val contentTargetOffset = if (isRefreshing) {
        refreshTargetOffsetPx
    } else if (pullState.distanceFraction > 0f) {
        currentPullOffsetPx
    } else {
        0f
    }

    val animatedContentOffset by animateFloatAsState(
        targetValue = contentTargetOffset,
        animationSpec = if (isRefreshing) {
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
        } else {
            snap()
        },
        label = "elastic_content_offset"
    )

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LargeTopAppBar(
                title = { Text(text = appName, fontWeight = FontWeight.Black) },
                actions = {
                    IconButton(
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            settingsRotationTrigger += 90f
                            onOpenAdmin()
                        },
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .background(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = SquircleShape(cornerRadiusRatio = 0.3f, smoothing = 0.6f)
                            )
                            .size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Налаштування",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier
                                .size(22.dp)
                                .graphicsLayer(rotationZ = settingsRotationAngle)
                        )
                    }
                },
                modifier = Modifier.statusBarsPadding()
            )
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val currentUrl by viewModel.currentPlayingUrl.collectAsState()
                val isMiniPlayerActive = latestSession != null || currentUrl != null

                FloatingActionButtonMenu(
                    expanded = isFabMenuOpen,
                    modifier = Modifier
                        .padding(bottom = if (isMiniPlayerActive) 80.dp else 0.dp)
                        // ОНОВЛЕНО: Зсуваємо кнопку плюс по осі X праворуч для ідеального вертикального вирівнювання з кутом мініплеєра
                        .offset(x = 6.dp),
                    button = {
                        ToggleFloatingActionButton(
                            checked = isFabMenuOpen,
                            onCheckedChange = {
                                isFabMenuOpen = it
                                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            }
                        ) {
                            Icon(
                                imageVector = if (isFabMenuOpen) Icons.Default.Close else Icons.Default.Add,
                                contentDescription = "Додати торрент",
                                modifier = Modifier.graphicsLayer {
                                    rotationZ = if (isFabMenuOpen) 90f else 0f
                                }
                            )
                        }
                    }
                ) {
                    FloatingActionButtonMenuItem(
                        onClick = {
                            isFabMenuOpen = false
                            showAddDialog = true
                        },
                        text = { Text("Додати Magnet / Hash") },
                        icon = { Icon(Icons.Default.Link, contentDescription = null) }
                    )
                    FloatingActionButtonMenuItem(
                        onClick = {
                            isFabMenuOpen = false
                            torrentFileLauncher.launch("*/*")
                        },
                        text = { Text("Завантажити .torrent файл") },
                        icon = { Icon(Icons.Default.UploadFile, contentDescription = null) }
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .pullToRefresh(
                    isRefreshing = isRefreshing,
                    state = pullState,
                    onRefresh = {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        viewModel.refreshTorrents()
                    }
                )
        ) {
            if (pullProgress > 0f || isRefreshing) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp)
                        .graphicsLayer {
                            scaleX = pullProgress
                            scaleY = pullProgress
                            alpha = pullProgress
                        }
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isRefreshing) {
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

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = animatedContentOffset
                    }
                    .background(MaterialTheme.colorScheme.background)
            ) {
                var isSearchFocused by remember { mutableStateOf(false) }

                val searchCornerRadius by animateDpAsState(
                    targetValue = if (isSearchFocused) 16.dp else 28.dp,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                )

                val searchBgColor by animateColorAsState(
                    targetValue = if (isSearchFocused) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLow,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                )

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Пошук у медіатеці...") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Пошук",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                searchQuery = ""
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Очистити",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(searchCornerRadius),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = searchBgColor,
                        unfocusedContainerColor = searchBgColor,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .onFocusChanged { isSearchFocused = it.isFocused }
                        .clearFocusOnKeyboardDismiss()
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { offset ->
                                    val item = gridState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                                        offset.x.toInt() in info.offset.x..(info.offset.x + info.size.width) &&
                                                offset.y.toInt() in info.offset.y..(info.offset.y + info.size.height)
                                    }
                                    if (item != null) {
                                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                        draggedKey = item.key as String
                                        initiallyDraggedItemOffset = item.offset
                                        draggedSize = item.size
                                        dragOffset = androidx.compose.ui.geometry.Offset.Zero
                                    }
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragOffset += dragAmount

                                    val currentKey = draggedKey ?: return@detectDragGesturesAfterLongPress
                                    val originalOffset = initiallyDraggedItemOffset ?: return@detectDragGesturesAfterLongPress

                                    val currentDraggedLayout = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == currentKey } ?: return@detectDragGesturesAfterLongPress

                                    val currentCenterX = originalOffset.x + currentDraggedLayout.size.width / 2 + dragOffset.x
                                    val currentCenterY = originalOffset.y + currentDraggedLayout.size.height / 2 + dragOffset.y

                                    val hoverItem = gridState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                                        currentCenterX.toInt() in info.offset.x..(info.offset.x + info.size.width) &&
                                                currentCenterY.toInt() in info.offset.y..(info.offset.y + info.size.height)
                                    }

                                    val currentTime = System.currentTimeMillis()
                                    if (hoverItem != null && hoverItem.key != currentKey && (currentTime - lastSwapTime > 180L)) {
                                        val fromIndex = editableList.indexOfFirst { t: Torrent -> t.hash == currentKey }
                                        val toIndex = editableList.indexOfFirst { t: Torrent -> t.hash == hoverItem.key }

                                        if (fromIndex != -1 && toIndex != -1 && fromIndex != toIndex) {
                                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                            editableList.add(toIndex, editableList.removeAt(fromIndex))
                                            lastSwapTime = currentTime
                                        }
                                    }
                                },
                                onDragEnd = {
                                    draggedKey = null
                                    initiallyDraggedItemOffset = null
                                    draggedSize = null
                                    dragOffset = androidx.compose.ui.geometry.Offset.Zero
                                    viewModel.saveTorrentOrder(editableList)
                                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                },
                                onDragCancel = {
                                    draggedKey = null
                                    initiallyDraggedItemOffset = null
                                    draggedSize = null
                                    dragOffset = androidx.compose.ui.geometry.Offset.Zero
                                }
                            )
                        }
                ) {
                    LazyVerticalStaggeredGrid(
                        state = gridState,
                        columns = StaggeredGridCells.Fixed(2),
                        modifier = Modifier
                            .fillMaxSize()
                            .overscroll(rememberOverscrollEffect()),
                        contentPadding = PaddingValues(
                            start = 12.dp,
                            end = 12.dp,
                            top = 12.dp,
                            bottom = if (latestSession != null) 100.dp else 12.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalItemSpacing = 8.dp
                    ) {
                        itemsIndexed(
                            items = editableList,
                            key = { _: Int, t: Torrent -> t.hash }
                        ) { index: Int, torrent: Torrent ->
                            val isCurrentDragged = draggedKey == torrent.hash

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateItem(
                                        placementSpec = if (isCurrentDragged) snap() else spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMediumLow
                                        )
                                    )
                                    .zIndex(if (isCurrentDragged) 10f else 1f)
                                    .graphicsLayer {
                                        alpha = if (isCurrentDragged) 0f else 1f
                                    }
                            ) {
                                TorrentExpressiveCard(
                                    torrent = torrent,
                                    isDragged = isCurrentDragged,
                                    onClick = { onTorrentClick(torrent) },
                                    onMenuClick = { editingTorrent = torrent }
                                )
                            }
                        }
                    }

                    val currentKey = draggedKey
                    val originalOffset = initiallyDraggedItemOffset
                    val size = draggedSize
                    if (currentKey != null && originalOffset != null && size != null) {
                        val draggedTorrent = editableList.find { t: Torrent -> t.hash == currentKey }
                        if (draggedTorrent != null) {
                            Box(
                                modifier = Modifier
                                    .size(
                                        width = with(density) { size.width.toDp() },
                                        height = with(density) { size.height.toDp() }
                                    )
                                    .graphicsLayer {
                                        translationX = originalOffset.x + dragOffset.x
                                        translationY = originalOffset.y + dragOffset.y
                                        scaleX = 1.05f
                                        scaleY = 1.05f
                                        // ОНОВЛЕНО: Прибрано зайву тінь під час перетягування для плаского M3E стилю
                                        shadowElevation = 0f
                                    }
                            ) {
                                TorrentExpressiveCard(
                                    torrent = draggedTorrent,
                                    isDragged = true,
                                    onClick = {},
                                    onMenuClick = {}
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    editingTorrent?.let { torrent ->
        EditTorrentBottomSheet(
            torrent = torrent,
            viewModel = viewModel,
            onDismiss = { editingTorrent = null },
            onConfirm = { title, poster, category ->
                viewModel.updateTorrent(torrent.hash, title, poster, category)
            },
            onRemove = {
                viewModel.removeTorrent(torrent.hash)
            }
        )
    }

    if (showAddDialog) {
        AddTorrentDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { link -> viewModel.addTorrent(link) }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TorrentExpressiveCard(
    torrent: Torrent,
    isDragged: Boolean,
    onClick: () -> Unit,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = when {
            isDragged -> 1.04f
            isPressed -> 0.94f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "card_press_scale"
    )

    val cardShape = SquircleShape(cornerRadiusRatio = 0.16f, smoothing = 0.6f)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(cardShape)
            .combinedClickable(
                interactionSource = interactionSource,
                // ОНОВЛЕНО: Прибрано сіре напівпрозоре виділення при затисканні картки для переміщення
                indication = null,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onClick()
                }
            ),
        shape = cardShape,
        // ОНОВЛЕНО: Прибрано тіні картки для плаского, монолітного тонального вигляду
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .clip(cardShape)
            ) {
                if (!torrent.poster.isNullOrBlank()) {
                    AsyncImage(
                        model = torrent.poster,
                        contentDescription = torrent.title,
                        modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                        contentScale = ContentScale.FillWidth
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Movie,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }

                FilledTonalIconButton(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onMenuClick()
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(36.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = Color.Black.copy(alpha = 0.5f),
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Керування торрентом",
                        modifier = Modifier.size(20.dp)
                    )
                }

                if (torrent.downloadSpeed > 0) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.6f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                    ) {
                        Text(
                            text = formatDownloadSpeed(torrent.downloadSpeed),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                            color = Color.White
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Text(
                    text = torrent.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = torrent.type ?: "Фільм",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    if (torrent.seeds > 0) {
                        Text(
                            text = "S: ${torrent.seeds} / P: ${torrent.peers}",
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun EditTorrentBottomSheet(
    torrent: Torrent,
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit,
    onRemove: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val view = LocalView.current
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var title by remember { mutableStateOf(torrent.title) }
    var poster by remember { mutableStateOf(torrent.poster ?: "") }
    val posterHeight = if (poster.isNotBlank()) 96.dp else 0.dp

    val categoriesList = listOf("Фільм", "Серіал", "Аніме", "Музика", "Інше")
    var selectedCategory by remember { mutableStateOf(torrent.type ?: "Фільм") }

    var newMagnetLink by remember { mutableStateOf("") }
    var priorityDownload by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val postersDir = File(context.filesDir, "posters")
                if (!postersDir.exists()) {
                    postersDir.mkdirs()
                }
                val targetFile = File(postersDir, "${torrent.hash}_poster.jpg")
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    FileOutputStream(targetFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                poster = "file://${targetFile.absolutePath}?t=${System.currentTimeMillis()}"
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    val replacementTorrentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            val clonedTitle = title
            val clonedPoster = poster
            val clonedCategory = selectedCategory
            viewModel.replaceTorrentFromFile(torrent.hash, uri, clonedTitle, clonedPoster, clonedCategory)
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
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
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                TextButton(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                        onRemove()
                        onDismiss()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    shape = SquircleShape(cornerRadiusRatio = 0.3f, smoothing = 0.6f),
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Вилучити", fontWeight = FontWeight.Bold)
                }

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

                Button(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        onConfirm(title, poster, selectedCategory)
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    shape = SquircleShape(cornerRadiusRatio = 0.3f, smoothing = 0.6f),
                    modifier = Modifier.align(Alignment.CenterEnd),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Зберегти", fontWeight = FontWeight.Bold)
                }
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
                .heightIn(max = 440.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = if (poster.isNotBlank()) 64.dp else 0.dp, height = posterHeight)
                            .clip(SquircleShape(cornerRadiusRatio = 0.16f, smoothing = 0.6f))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        if (poster.isNotBlank()) {
                            AsyncImage(
                                model = poster,
                                contentDescription = "Прев'ю обкладинки",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(Icons.Default.Image, null, modifier = Modifier.size(20.dp))
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Налаштування контенту",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text("Назва") },
                            modifier = Modifier.fillMaxWidth().clearFocusOnKeyboardDismiss(),
                            shape = MaterialTheme.shapes.medium,
                            singleLine = true
                        )
                    }
                }

                Text(
                    text = "Тип контенту",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    categoriesList.forEach { cat: String ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                selectedCategory = cat
                            },
                            label = { Text(cat, fontWeight = FontWeight.Bold) },
                            shape = SquircleShape(cornerRadiusRatio = 0.3f, smoothing = 0.6f),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }

                ListItem(
                    headlineContent = { Text("Пріоритет завантаження", fontWeight = FontWeight.Bold) },
                    supportingContent = { Text("Задіяти maximalний DHT пошук пірів") },
                    trailingContent = {
                        Switch(
                            checked = priorityDownload,
                            onCheckedChange = {
                                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                priorityDownload = it
                            },
                            thumbContent = if (priorityDownload) {
                                { Icon(Icons.Default.Check, null, modifier = Modifier.size(12.dp)) }
                            } else null
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                )

                OutlinedTextField(
                    value = poster,
                    onValueChange = { poster = it },
                    label = { Text("URL обкладинки") },
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clipData = clipboard.primaryClip
                                if (clipData != null && clipData.itemCount > 0) {
                                    val text = clipData.getItemAt(0).text?.toString() ?: ""
                                    if (text.isNotBlank()) {
                                        poster = text
                                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = "Вставити з буфера", modifier = Modifier.size(20.dp))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().clearFocusOnKeyboardDismiss(),
                    shape = MaterialTheme.shapes.medium
                )

                Button(
                    onClick = { filePickerLauncher.launch("image/*") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Icon(Icons.Default.Image, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Вибрати обкладинку із пристрою")
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Text(
                    text = "Швидка заміна торрента",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                OutlinedTextField(
                    value = newMagnetLink,
                    onValueChange = { newMagnetLink = it },
                    label = { Text("Новий Magnet Link / Hash") },
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clipData = clipboard.primaryClip
                                if (clipData != null && clipData.itemCount > 0) {
                                    val text = clipData.getItemAt(0).text?.toString() ?: ""
                                    if (text.isNotBlank()) {
                                        newMagnetLink = text
                                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = "Вставити з буфера", modifier = Modifier.size(20.dp))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().clearFocusOnKeyboardDismiss(),
                    shape = MaterialTheme.shapes.medium,
                    maxLines = 3
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            replacementTorrentLauncher.launch("*/*")
                        },
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Icon(Icons.Default.UploadFile, null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Файл .torrent")
                    }

                    Button(
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            val clonedTitle = title
                            val clonedPoster = poster
                            val clonedCategory = selectedCategory
                            viewModel.replaceTorrentWithMagnet(torrent.hash, newMagnetLink, clonedTitle, clonedPoster, clonedCategory)
                            onDismiss()
                        },
                        enabled = newMagnetLink.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Icon(Icons.Default.Link, null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Замінити Magnet")
                    }
                }
            }
        }
    }
}

@Composable
fun AddTorrentDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val view = LocalView.current
    var link by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Додати новий торрент", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    label = { Text("Магнет-посилання або хеш") },
                    modifier = Modifier.fillMaxWidth().clearFocusOnKeyboardDismiss(),
                    shape = MaterialTheme.shapes.medium,
                    maxLines = 4
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (link.isNotBlank()) {
                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        onConfirm(link)
                        onDismiss()
                    }
                },
                enabled = link.isNotBlank(),
                shape = MaterialTheme.shapes.medium
            ) {
                Text("Додати")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onDismiss()
            }) {
                Text("Скасувати")
            }
        }
    )
}

private fun formatDownloadSpeed(speed: Long): String {
    if (speed <= 0) return "0 Б/с"
    val units = arrayOf("Б/с", "КБ/с", "МБ/с", "ГБ/с")
    val digitGroups = (Math.log10(speed.toDouble()) / Math.log10(1024.toDouble())).toInt()
    return String.format(Locale.US, "%.1f %s", speed / Math.pow(1024.toDouble(), digitGroups.toDouble()), units[digitGroups])
}