package com.example.torrentstreamer

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.util.Rational
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.torrentstreamer.ui.theme.TorrentStreamerTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private var currentTorrent by mutableStateOf<Torrent?>(null)
    private var currentStreamUrl by mutableStateOf<String?>(null)
    private var currentStreamTitle by mutableStateOf<String?>(null)

    // Стейт Picture-in-Picture режиму
    private var isPipModeActive by mutableStateOf(false)

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        TorrServerManager.start(this)

        // Конфігурація Auto-PiP для Android 12+ (API 31+) з динамічним усуненням чорних смуг [IX]
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    combine(
                        viewModel.currentPlayingUrl, // Зчитуємо фоновий стан відтворення у сервісі
                        viewModel.isAutoPipEnabled,  // Дозвіл користувача
                        viewModel.videoBounds,       // Фізичні межі плеєра у Compose (обрізані від чорних смуг)
                        viewModel.videoSize          // Фізичний розмір відеопотоку від декодера ExoPlayer
                    ) { activeUrl, autoPipEnabled, bounds, vSize ->
                        DataPipPackage(activeUrl != null && autoPipEnabled, bounds, vSize)
                    }.collect { data ->
                        val paramsBuilder = PictureInPictureParams.Builder()

                        paramsBuilder.setAutoEnterEnabled(data.shouldAutoPip)

                        if (data.shouldAutoPip && data.bounds != null) {
                            paramsBuilder.setSourceRectHint(data.bounds)
                        }

                        // УСУНЕННЯ ЧОРНИХ СМУГ: пропорції вікна PiP під реальний формат медіафайлу [IX]
                        val rat = when {
                            data.vSize != null && data.vSize.width > 0 && data.vSize.height > 0 -> {
                                Rational(data.vSize.width, data.vSize.height)
                            }
                            data.bounds != null && data.bounds.width() > 0 && data.bounds.height() > 0 -> {
                                Rational(data.bounds.width(), data.bounds.height())
                            }
                            else -> null
                        }

                        rat?.let { r ->
                            val floatRatio = r.numerator.toFloat() / r.denominator
                            if (floatRatio in 0.4184f..2.39f) { // Обмеження Android OS для аспектів PiP
                                try {
                                    paramsBuilder.setAspectRatio(r)
                                } catch (_: Exception) {}
                            }
                        }

                        setPictureInPictureParams(paramsBuilder.build())
                    }
                }
            }
        }

        setContent {
            TorrentStreamerTheme {
                var showWebAdmin by remember { mutableStateOf(false) }
                var showNativeSettings by remember { mutableStateOf(false) }

                var backProgress by remember { mutableFloatStateOf(0f) }
                val screenScale by animateFloatAsState(targetValue = 1f - (backProgress * 0.08f), label = "")
                val corners by animateDpAsState(targetValue = if (backProgress > 0f) 32.dp else 0.dp, label = "")

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
                        val activePlaybackUrl by viewModel.currentPlayingUrl.collectAsState()
                        val activePlaybackTitle by viewModel.currentPlayingTitle.collectAsState()

                        when {
                            // КРИТИЧНО: Якщо PiP-режим активний і у фоні працює відтворення —
                            // примусово підміняємо розмітку вікна на чистий PlayerScreen, ховаючи налаштування чи списки торентів [IX]!
                            isPipModeActive && currentStreamUrl == null && activePlaybackUrl != null -> {
                                PlayerScreen(
                                    videoUrl = activePlaybackUrl!!,
                                    title = activePlaybackTitle ?: "Відтворення",
                                    viewModel = viewModel,
                                    isInPipMode = true,
                                    onBack = {}
                                )
                            }

                            // Стандартна логіка навігації у повному екрані
                            showWebAdmin -> TorrWebScreen { showWebAdmin = false }
                            showNativeSettings -> VibeSettingsScreen(viewModel, { showNativeSettings = false }, { showWebAdmin = true })

                            currentStreamUrl != null && currentStreamTitle != null -> {
                                PlayerScreen(
                                    videoUrl = currentStreamUrl!!,
                                    title = currentStreamTitle!!,
                                    viewModel = viewModel,
                                    isInPipMode = isPipModeActive, // Повний екран
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

                    // Міні-плеєр приховується, якщо відкритий повний плеєр, веб-панель АБО якщо ми у PiP-режимі!
                    val isFullScreenActive = currentStreamUrl != null && currentStreamTitle != null
                    val shouldShowMiniPlayer = !isFullScreenActive && !showWebAdmin && !isPipModeActive

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

    // Резервний PiP-запуск для Android 8-11 (API 26-30) при згортанні додатка користувачем [IX]
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val isPlaying = viewModel.currentPlayingUrl.value != null
        val isAutoPipEnabled = viewModel.isAutoPipEnabled.value

        if (isPlaying && isAutoPipEnabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val paramsBuilder = PictureInPictureParams.Builder()
                val activeSize = viewModel.videoSize.value
                val bounds = viewModel.videoBounds.value

                val rat = when {
                    activeSize != null && activeSize.width > 0 && activeSize.height > 0 -> {
                        Rational(activeSize.width, activeSize.height)
                    }
                    bounds != null && bounds.width() > 0 && bounds.height() > 0 -> {
                        Rational(bounds.width(), bounds.height())
                    }
                    else -> null
                }

                bounds?.let { paramsBuilder.setSourceRectHint(it) }
                rat?.let { r ->
                    val floatRatio = r.numerator.toFloat() / r.denominator
                    if (floatRatio in 0.4184f..2.39f) {
                        try {
                            paramsBuilder.setAspectRatio(r)
                        } catch (_: Exception) {}
                    }
                }
                enterPictureInPictureMode(paramsBuilder.build())
            }
        }
    }

    // Слухач апаратної зміни стану PiP-режиму
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isPipModeActive = isInPictureInPictureMode

        // КРИТИЧНО: ВИРІШЕННЯ БАГУ ПОРТРЕТНОЇ ОРІЄНТАЦІЇ ПРИ РОЗГОРТАННІ PiP [IX]
        if (!isInPictureInPictureMode) {
            if (currentStreamUrl != null) {
                // Відновлюємо орієнтацію на базі нових прапорців у MainViewModel [IX]
                val autoRotate = viewModel.isAutoRotationEnabled.value
                val lockedPortrait = viewModel.isLockedPortrait.value

                requestedOrientation = when {
                    autoRotate -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
                    lockedPortrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
            } else {
                // Якщо користувач згорнув плеєр і переглядав інші розділи програми —
                // примусово повертаємо ПОРТРЕТНИЙ РЕЖИМ замість Unspecified, що гарантує вертикальний інтерфейс [IX]!
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
    }
}

// Допоміжний контейнер передачі параметрів PiP для Flow-злиття
private data class DataPipPackage(
    val shouldAutoPip: Boolean,
    val bounds: android.graphics.Rect?,
    val vSize: android.util.Size?
)