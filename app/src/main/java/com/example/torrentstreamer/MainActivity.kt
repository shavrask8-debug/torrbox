package com.example.torrentstreamer

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape // Системний імпорт форми
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer // Додано для апаратного розмиття меж кутів
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
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

        // Запуск нашого вбудованого TorrServer Matrix
        TorrServerManager.start(this)

        setContent {
            TorrentStreamerTheme {
                var showWebAdmin by remember { mutableStateOf(false) }
                var showNativeSettings by remember { mutableStateOf(false) }

                var backProgress by remember { mutableFloatStateOf(0f) }
                val screenScale by animateFloatAsState(targetValue = 1f - (backProgress * 0.08f), label = "")
                val corners by animateDpAsState(targetValue = if (backProgress > 0f) 32.dp else 0.dp, label = "")

                // Отримуємо стейт історії перегляду з ViewModel
                val watchHistory by viewModel.watchHistory.collectAsState()

                // 🔄 Авто-оновлення списку торрентів при виході з налаштувань чи Web UI
                LaunchedEffect(showWebAdmin, showNativeSettings) {
                    if (!showWebAdmin && !showNativeSettings) {
                        viewModel.refreshTorrents(showSpinner = false)
                        viewModel.loadSettings() // Надійна синхронізація з Web UI на льоту
                    }
                }

                // Дефолтний обробник системного жесту "Назад" (Predictive Back)
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
                        // Очікувано при скасуванні жесту користувачем
                    } finally {
                        backProgress = 0f
                    }
                }

                // РЕЗОЛВ: Рендеримо красиве розмите апаратне затінення навколо кутів при Predictive Back
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = screenScale
                            scaleY = screenScale
                            // Апаратна розмита тінь навколо кутів екрана для ефекту м'яких меж
                            shadowElevation = if (backProgress > 0f) 24f * backProgress else 0f
                            shape = RoundedCornerShape(corners.coerceAtLeast(0.dp))
                            clip = true
                        },
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background
                ) {
                    when {
                        showWebAdmin -> TorrWebScreen { showWebAdmin = false }
                        showNativeSettings -> VibeSettingsScreen(viewModel, { showNativeSettings = false }, { showWebAdmin = true })

                        // 1. Екран Плеєра
                        currentStreamUrl != null && currentStreamTitle != null -> {
                            PlayerScreen(
                                videoUrl = currentStreamUrl!!,
                                title = currentStreamTitle!!,
                                onBack = {
                                    currentStreamUrl = null
                                    currentStreamTitle = null
                                }
                            )
                        }

                        // 2. Екран вибору серій
                        currentTorrent != null -> {
                            FileSelectionScreen(
                                torrent = currentTorrent!!,
                                viewModel = viewModel, // Передаємо ViewModel
                                onBack = { currentTorrent = null },
                                onFileSelect = { url, title ->
                                    currentStreamUrl = url
                                    currentStreamTitle = title
                                }
                            )
                        }

                        // 3. Головна вітрина додатку
                        else -> VibeHomeScreen(
                            viewModel = viewModel,
                            onTorrentClick = { torrent ->
                                // Миттєво та синхронно ініціюємо завантаження та очищення серій при кліку на картку!
                                viewModel.loadFiles(torrent.hash, force = false) // ОНОВЛЕНО: Передаємо force = false за замовчуванням
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
            }
        }
    }
}