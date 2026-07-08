package com.example.torrentstreamer

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import com.example.torrentstreamer.ui.theme.TorrentStreamerTheme
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private var currentTorrent by mutableStateOf<Torrent?>(null)
    private var currentStreamUrl by mutableStateOf<String?>(null)
    private var currentStreamTitle by mutableStateOf<String?>(null)

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        TorrServerManager.start(this)

        setContent {
            TorrentStreamerTheme {
                var showWebAdmin by remember { mutableStateOf(false) }
                var showNativeSettings by remember { mutableStateOf(false) }

                var backProgress by remember { mutableFloatStateOf(0f) }
                val screenScale by animateFloatAsState(targetValue = 1f - (backProgress * 0.08f), label = "")
                val corners by animateDpAsState(targetValue = if (backProgress > 0f) 32.dp else 0.dp, label = "")

                val watchHistory by viewModel.watchHistory.collectAsState()

                LaunchedEffect(showWebAdmin, showNativeSettings) {
                    if (!showWebAdmin && !showNativeSettings) {
                        viewModel.refreshTorrents(showSpinner = false)
                        viewModel.loadSettings()
                    }
                }

                PredictiveBackHandler(enabled = showWebAdmin || showNativeSettings || currentStreamUrl != null || currentTorrent != null) { progress ->
                    try {
                        progress.collect { event -> backProgress = event.progress }
                        if (currentStreamUrl != null) {
                            currentStreamUrl = null
                            currentStreamTitle = null
                        } else if (currentTorrent != null) {
                            currentTorrent = null
                        }
                        showWebAdmin = false
                        showNativeSettings = false
                    } catch (e: CancellationException) {
                        // Очікувано
                    } finally {
                        backProgress = 0f
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = screenScale
                                scaleY = screenScale
                                shadowElevation = if (backProgress > 0f) 24f * backProgress else 0f
                                shape = RoundedCornerShape(corners.coerceAtLeast(0.dp))
                                clip = true
                            },
                        color = androidx.compose.material3.MaterialTheme.colorScheme.background
                    ) {
                        when {
                            showWebAdmin -> TorrWebScreen { showWebAdmin = false }
                            showNativeSettings -> VibeSettingsScreen(viewModel, { showNativeSettings = false }, { showWebAdmin = true })

                            currentStreamUrl != null && currentStreamTitle != null -> {
                                PlayerScreen(
                                    videoUrl = currentStreamUrl!!,
                                    title = currentStreamTitle!!,
                                    viewModel = viewModel,
                                    onBack = {
                                        currentStreamUrl = null
                                        currentStreamTitle = null
                                    }
                                )
                            }

                            currentTorrent != null -> {
                                FileSelectionScreen(
                                    torrent = currentTorrent!!,
                                    viewModel = viewModel,
                                    onBack = { currentTorrent = null },
                                    onFileSelect = { url, title ->
                                        currentStreamUrl = url
                                        currentStreamTitle = title
                                    }
                                )
                            }

                            else -> VibeHomeScreen(
                                viewModel = viewModel,
                                onTorrentClick = { torrent ->
                                    viewModel.loadFiles(torrent.hash, force = false)
                                    currentTorrent = torrent
                                },
                                onOpenAdmin = { showNativeSettings = true },
                                onResumeClick = { historyItem ->
                                    currentStreamUrl = historyItem.videoUrl
                                    currentStreamTitle = historyItem.title
                                }
                            )
                        }
                    }

                    // ОНОВЛЕНО: Міні-плеєр приховується, якщо відкритий повний плеєр АБО веб-панель адміна!
                    val isFullScreenActive = currentStreamUrl != null && currentStreamTitle != null
                    val shouldShowMiniPlayer = !isFullScreenActive && !showWebAdmin

                    if (shouldShowMiniPlayer) {
                        val latestSession by viewModel.latestSession.collectAsState()
                        val currentUrl by viewModel.currentPlayingUrl.collectAsState()

                        if (latestSession != null || currentUrl != null) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .navigationBarsPadding()
                                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                                    .zIndex(10f)
                            ) {
                                MiniPlayer(
                                    viewModel = viewModel,
                                    onClick = {
                                        val activeUrl = currentUrl ?: latestSession?.videoUrl
                                        val activeTitle = viewModel.currentPlayingTitle.value ?: latestSession?.title
                                        if (activeUrl != null && activeTitle != null) {
                                            currentStreamUrl = activeUrl
                                            currentStreamTitle = activeTitle
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}