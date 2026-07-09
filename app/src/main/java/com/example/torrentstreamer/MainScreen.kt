package com.example.torrentstreamer

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

sealed interface Screen {
    object Home : Screen
    object Settings : Screen
    object WebAdmin : Screen
    data class FileSelection(val torrent: Torrent) : Screen
    data class Player(val streamUrl: String, val title: String) : Screen
}

@OptIn(UnstableApi::class)
@Composable
fun MainScreen() {
    val viewModel: MainViewModel = viewModel()
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }

    val watchHistory by viewModel.watchHistory.collectAsState()

    AnimatedContent(
        targetState = currentScreen,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "M3E_Navigation"
    ) { screen ->
        when (screen) {
            is Screen.Home -> {
                VibeHomeScreen(
                    viewModel = viewModel,
                    onTorrentClick = { torrent ->
                        viewModel.loadFiles(torrent.hash)
                        currentScreen = Screen.FileSelection(torrent)
                    },
                    onOpenAdmin = { currentScreen = Screen.Settings },
                    onResumeClick = { history ->
                        currentScreen = Screen.Player(history.videoUrl, history.title)
                    }
                )
            }
            is Screen.Settings -> {
                VibeSettingsScreen(
                    viewModel = viewModel,
                    onBack = { currentScreen = Screen.Home },
                    onOpenWebAdmin = { currentScreen = Screen.WebAdmin }
                )
            }
            is Screen.WebAdmin -> {
                TorrWebScreen(
                    onClose = { currentScreen = Screen.Settings }
                )
            }
            is Screen.FileSelection -> {
                FileSelectionScreen(
                    torrent = screen.torrent,
                    viewModel = viewModel,
                    onBack = { currentScreen = Screen.Home },
                    onFileSelect = { fileUrl, fileTitle ->
                        currentScreen = Screen.Player(fileUrl, fileTitle)
                    }
                )
            }
            is Screen.Player -> {
                // Виклик оригінального PlayerScreen (параметр isInPipMode підхопиться за замовчуванням = false)
                PlayerScreen(
                    videoUrl = screen.streamUrl,
                    title = screen.title,
                    viewModel = viewModel,
                    onBack = { currentScreen = Screen.Home }
                )
            }
        }
    }
}