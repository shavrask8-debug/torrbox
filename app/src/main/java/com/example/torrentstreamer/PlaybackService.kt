package com.example.torrentstreamer

import android.app.Application
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.example.torrentstreamer.data.AppDatabase
import com.example.torrentstreamer.data.WatchHistory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@OptIn(UnstableApi::class)
object PlaybackService {

    private var player: ExoPlayer? = null
    private var scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var progressJob: Job? = null
    private var currentTitle: String = "Відео потік"

    private val _currentPlayingUrl = MutableStateFlow<String?>(null)
    val currentPlayingUrl: StateFlow<String?> = _currentPlayingUrl.asStateFlow()

    private val _isPlayerPlaying = MutableStateFlow(false)
    val isPlayerPlaying: StateFlow<Boolean> = _isPlayerPlaying.asStateFlow()

    val playerInstance: ExoPlayer? get() = player

    fun initPlayer(application: Application, videoUrl: String, title: String) {
        currentTitle = title
        val database = AppDatabase.getDatabase(application).watchHistoryDao()

        if (player == null) {
            player = ExoPlayer.Builder(application).build().apply {
                repeatMode = Player.REPEAT_MODE_OFF
                playWhenReady = true

                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _isPlayerPlaying.value = isPlaying
                        if (isPlaying) {
                            startProgressTracker(database, videoUrl)
                        } else {
                            progressJob?.cancel()
                        }
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) {
                            scope.launch(Dispatchers.IO) {
                                database.saveProgress(
                                    WatchHistory(videoUrl, currentTitle, duration, duration, true, System.currentTimeMillis())
                                )
                            }
                        }
                    }
                })
            }
        }

        _currentPlayingUrl.value = videoUrl

        scope.launch(Dispatchers.IO) {
            database.getAllHistory().collect { list ->
                val item = list.find { it.videoUrl == videoUrl }
                var savedPos: Long = 0
                if (item != null && !item.isFinished) {
                    savedPos = item.lastPosition
                }

                withContext(Dispatchers.Main) {
                    player?.let { p ->
                        p.setMediaItem(MediaItem.fromUri(videoUrl))
                        p.prepare()
                        if (savedPos > 0) p.seekTo(savedPos)
                        p.play()
                    }
                }
                this@launch.cancel()
            }
        }
    }

    private fun startProgressTracker(database: com.example.torrentstreamer.data.WatchHistoryDao, url: String) {
        progressJob?.cancel()
        progressJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(3000)
                player?.let { p ->
                    val currentPos = p.currentPosition
                    val duration = p.duration
                    if (duration > 0 && currentPos < duration) {
                        database.saveProgress(
                            WatchHistory(
                                videoUrl = url,
                                title = currentTitle,
                                lastPosition = currentPos,
                                duration = duration,
                                isFinished = (duration - currentPos) < 10000,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    }
                }
            }
        }
    }

    fun releasePlayer() {
        progressJob?.cancel()
        player?.stop()
        player?.release()
        player = null
        _currentPlayingUrl.value = null
        _isPlayerPlaying.value = false
    }
}