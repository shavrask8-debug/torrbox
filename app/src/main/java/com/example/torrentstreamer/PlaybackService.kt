@file:Suppress("UnstableApiUsage", "DEPRECATION")

package com.example.torrentstreamer

import android.content.Intent
import android.util.Size
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.torrentstreamer.data.AppDatabase
import com.example.torrentstreamer.data.WatchHistory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class PlaybackService : MediaSessionService() {

    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var progressJob: Job? = null

    override fun onCreate() {
        super.onCreate()

        // Адаптивний LoadControl для P2P-стрімінгу
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15000,
                50000,
                1000,
                1500
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderersFactory = DefaultRenderersFactory(this).apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        }

        // Будуємо плеєр з підтримкою FFmpeg
        val player = ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .build().apply {
                repeatMode = Player.REPEAT_MODE_OFF
                playWhenReady = true
            }
        exoPlayer = player
        mediaSession = MediaSession.Builder(this, player).build()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlayerPlaying.value = isPlaying
                if (isPlaying) {
                    startProgressTracker()
                } else {
                    progressJob?.cancel()
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                updateTracks(tracks)
            }

            override fun onPlaybackStateChanged(state: Int) {
                _isBuffering.value = (state == Player.STATE_BUFFERING)

                if (state == Player.STATE_ENDED) {
                    saveFinishedState()
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    _videoSize.value = Size(videoSize.width, videoSize.height)
                }
            }

            // Самолікування при крашах кодеків (DTS/AC3)
            override fun onPlayerError(error: PlaybackException) {
                _isPlayerPlaying.value = false
                _isBuffering.value = false

                val cause = error.cause
                if (cause != null && cause.javaClass.name.contains("DecoderInitializationException")) {
                    android.util.Log.w("PlaybackService", "Виявлено несумісний апаратний кодек. Запуск самовідновлення...")

                    exoPlayer?.let { p ->
                        val currentPos = p.currentPosition
                        val wasPlaying = p.playWhenReady

                        // Очищуємо проблемні оверрайти
                        val parameters = p.trackSelectionParameters
                            .buildUpon()
                            .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_AUDIO)
                            .build()
                        p.trackSelectionParameters = parameters

                        p.prepare()
                        p.seekTo(currentPos)
                        p.playWhenReady = wasPlaying
                    }
                } else {
                    android.util.Log.e("PlaybackService", "Критична помилка відтворення: ${error.message}", error)
                }
            }
        })

        _playerInstance.value = player
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            when (intent.action) {
                ACTION_PLAY -> {
                    val url = intent.getStringExtra(EXTRA_URL)
                    val title = intent.getStringExtra(EXTRA_TITLE)
                    if (url != null && title != null) {
                        initAndPlay(url, title)
                    }
                }
                ACTION_TOGGLE_PLAY -> {
                    exoPlayer?.let {
                        if (it.isPlaying) it.pause() else it.play()
                    }
                }
                ACTION_SEEK -> {
                    val pos = intent.getLongExtra(EXTRA_POSITION, 0L)
                    exoPlayer?.seekTo(pos)
                    _currentPosition.value = pos
                }
                ACTION_SELECT_TRACK -> {
                    val groupIdx = intent.getIntExtra(EXTRA_GROUP_INDEX, -1)
                    val trackIdx = intent.getIntExtra(EXTRA_TRACK_INDEX, -1)
                    if (groupIdx != -1 && trackIdx != -1) {
                        applyAudioTrack(groupIdx, trackIdx)
                    }
                }
                ACTION_SELECT_SUBTITLE -> {
                    val groupIdx = intent.getIntExtra(EXTRA_SUBTITLE_GROUP_INDEX, -1)
                    val trackIdx = intent.getIntExtra(EXTRA_SUBTITLE_TRACK_INDEX, -1)
                    if (groupIdx != -1 && trackIdx != -1) {
                        applySubtitleTrack(groupIdx, trackIdx)
                    }
                }
                ACTION_DISABLE_SUBTITLES -> {
                    disableSubtitles()
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    private fun initAndPlay(videoUrl: String, title: String) {
        if (_currentPlayingUrl.value == videoUrl && exoPlayer?.playbackState != Player.STATE_IDLE) {
            return
        }

        _currentPlayingUrl.value = videoUrl
        _currentTitle.value = title

        exoPlayer?.let { p ->
            p.stop()
            p.clearMediaItems()
        }

        _videoSize.value = null

        val database = AppDatabase.getDatabase(applicationContext).watchHistoryDao()

        serviceScope.launch(Dispatchers.IO) {
            val historyList = database.getAllHistory().first()
            val savedSession = historyList.find { it.videoUrl == videoUrl }
            val savedPos = if (savedSession != null && !savedSession.isFinished) savedSession.lastPosition else 0L

            withContext(Dispatchers.Main) {
                exoPlayer?.let { p ->
                    p.setMediaItem(MediaItem.fromUri(videoUrl))
                    p.prepare()
                    if (savedPos > 0L) {
                        p.seekTo(savedPos)
                    }
                    p.play()
                }
            }
        }
    }

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = serviceScope.launch {
            val database = AppDatabase.getDatabase(applicationContext).watchHistoryDao()
            while (isActive) {
                exoPlayer?.let { p ->
                    val currentPos = p.currentPosition
                    val duration = p.duration
                    val url = _currentPlayingUrl.value
                    val title = _currentTitle.value

                    if (duration > 0 && currentPos < duration && url != null && title != null) {
                        withContext(Dispatchers.IO) {
                            database.saveProgress(
                                WatchHistory(
                                    videoUrl = url,
                                    title = title,
                                    lastPosition = currentPos,
                                    duration = duration,
                                    isFinished = (duration - currentPos) < 20000L,
                                    timestamp = System.currentTimeMillis()
                                )
                            )
                        }
                        _currentPosition.value = currentPos
                        _duration.value = duration
                    }
                }
                delay(500)
            }
        }
    }

    private fun saveFinishedState() {
        val url = _currentPlayingUrl.value
        val title = _currentTitle.value
        val dur = exoPlayer?.duration ?: 0L
        if (url != null && title != null && dur > 0L) {
            serviceScope.launch(Dispatchers.IO) {
                val database = AppDatabase.getDatabase(applicationContext).watchHistoryDao()
                database.saveProgress(
                    WatchHistory(url, title, dur, dur, true, System.currentTimeMillis())
                )
            }
        }
    }

    private fun updateTracks(tracks: Tracks) {
        val audioList = mutableListOf<AudioTrackInfo>()
        val subtitleList = mutableListOf<SubtitleTrackInfo>()

        var audioIdx = 0
        var subIdx = 0

        for (groupIndex in 0 until tracks.groups.size) {
            val group = tracks.groups[groupIndex]
            if (group.type == androidx.media3.common.C.TRACK_TYPE_AUDIO) {
                for (trackIndex in 0 until group.length) {
                    val format = group.getTrackFormat(trackIndex)
                    val isSelected = group.isTrackSelected(trackIndex)
                    val isSupported = group.getTrackSupport(trackIndex) >= 3
                    val lang = format.language ?: "und"
                    val label = format.label ?: "Звук #${audioIdx + 1}"

                    audioList.add(
                        AudioTrackInfo(
                            groupIndex = groupIndex,
                            trackIndex = trackIndex,
                            language = lang,
                            label = label,
                            isSelected = isSelected,
                            isSupported = isSupported
                        )
                    )
                    audioIdx++
                }
            } else if (group.type == androidx.media3.common.C.TRACK_TYPE_TEXT) {
                for (trackIndex in 0 until group.length) {
                    val format = group.getTrackFormat(trackIndex)
                    val isSelected = group.isTrackSelected(trackIndex)
                    val isSupported = group.getTrackSupport(trackIndex) >= 3

                    val lang = format.language ?: "und"
                    val label = format.label ?: "Субтитри #${subIdx + 1}"

                    subtitleList.add(
                        SubtitleTrackInfo(
                            groupIndex = groupIndex,
                            trackIndex = trackIndex,
                            language = lang,
                            label = label,
                            isSelected = isSelected,
                            isSupported = isSupported
                        )
                    )
                    subIdx++
                }
            }
        }
        _audioTracks.value = audioList
        _subtitleTracks.value = subtitleList
    }

    private fun applyAudioTrack(groupIndex: Int, trackIndex: Int) {
        exoPlayer?.let { player ->
            if (groupIndex < player.currentTracks.groups.size) {
                val wasPlaying = player.playWhenReady
                val currentPos = player.currentPosition

                player.playWhenReady = false

                val mediaTrackGroup = player.currentTracks.groups[groupIndex].mediaTrackGroup
                val format = mediaTrackGroup.getFormat(trackIndex)
                val lang = format.language

                // Накладаємо пряме перекриття типу AUDIO
                val parameters = player.trackSelectionParameters
                    .buildUpon()
                    .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_AUDIO)
                    .addOverride(TrackSelectionOverride(mediaTrackGroup, listOf(trackIndex)))
                    .apply {
                        if (lang != null) setPreferredAudioLanguage(lang)
                    }
                    .build()
                player.trackSelectionParameters = parameters

                if (player.playbackState == Player.STATE_IDLE) {
                    player.prepare()
                }

                player.seekTo(currentPos)
                player.playWhenReady = wasPlaying
            }
        }
    }

    private fun applySubtitleTrack(groupIndex: Int, trackIndex: Int) {
        exoPlayer?.let { player ->
            if (groupIndex < player.currentTracks.groups.size) {
                val wasPlaying = player.playWhenReady
                val currentPos = player.currentPosition

                player.playWhenReady = false

                val mediaTrackGroup = player.currentTracks.groups[groupIndex].mediaTrackGroup
                val format = mediaTrackGroup.getFormat(trackIndex)
                val lang = format.language

                // КРИТИЧНИЙ ФІКС: дозволяємо невизначені мови [IX] + застосовуємо точковий addOverride
                val parameters = player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false) // Вмикаємо текст
                    .setSelectUndeterminedTextLanguage(true)                              // ДОЗВОЛЯЄМО БЕЗ МОВНИХ ТЕГІВ! [IX]
                    .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_TEXT)       // Чистимо старі вибори
                    .addOverride(TrackSelectionOverride(mediaTrackGroup, listOf(trackIndex))) // Форсуємо вибір списковим конструктором [1]
                    .apply {
                        if (lang != null) setPreferredTextLanguage(lang)
                    }
                    .build()
                player.trackSelectionParameters = parameters

                if (player.playbackState == Player.STATE_IDLE) {
                    player.prepare()
                }

                player.seekTo(currentPos)
                player.playWhenReady = wasPlaying
            }
        }
    }

    private fun disableSubtitles() {
        exoPlayer?.let { player ->
            val wasPlaying = player.playWhenReady
            val currentPos = player.currentPosition

            player.playWhenReady = false

            val parameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true) // Вимикаємо рендерер тексту
                .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_TEXT)       // Повністю чистимо оверрайти
                .build()
            player.trackSelectionParameters = parameters

            if (player.playbackState == Player.STATE_IDLE) {
                player.prepare()
            }

            player.seekTo(currentPos)
            player.playWhenReady = wasPlaying
        }
    }

    override fun onDestroy() {
        progressJob?.cancel()
        serviceScope.cancel()
        mediaSession?.release()
        mediaSession = null
        exoPlayer?.release()
        exoPlayer = null
        _playerInstance.value = null
        _currentPlayingUrl.value = null
        _isPlayerPlaying.value = false
        _videoSize.value = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_PLAY = "com.example.torrentstreamer.action.PLAY"
        const val ACTION_TOGGLE_PLAY = "com.example.torrentstreamer.action.TOGGLE_PLAY"
        const val ACTION_SEEK = "com.example.torrentstreamer.action.SEEK"
        const val ACTION_SELECT_TRACK = "com.example.torrentstreamer.action.SELECT_TRACK"
        const val ACTION_SELECT_SUBTITLE = "com.example.torrentstreamer.action.SELECT_SUBTITLE"
        const val ACTION_DISABLE_SUBTITLES = "com.example.torrentstreamer.action.DISABLE_SUBTITLES"

        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_POSITION = "extra_position"
        const val EXTRA_GROUP_INDEX = "extra_group_index"
        const val EXTRA_TRACK_INDEX = "extra_track_index"
        const val EXTRA_SUBTITLE_GROUP_INDEX = "extra_subtitle_group_index"
        const val EXTRA_SUBTITLE_TRACK_INDEX = "extra_subtitle_track_index"

        private val _playerInstance = MutableStateFlow<ExoPlayer?>(null)
        val playerInstance: StateFlow<ExoPlayer?> = _playerInstance.asStateFlow()

        private val _currentPlayingUrl = MutableStateFlow<String?>(null)
        val currentPlayingUrl: StateFlow<String?> = _currentPlayingUrl.asStateFlow()

        private val _currentTitle = MutableStateFlow<String?>(null)
        val currentTitle: StateFlow<String?> = _currentTitle.asStateFlow()

        private val _isPlayerPlaying = MutableStateFlow(false)
        val isPlayerPlaying: StateFlow<Boolean> = _isPlayerPlaying.asStateFlow()

        private val _currentPosition = MutableStateFlow(0L)
        val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

        private val _duration = MutableStateFlow(0L)
        val duration: StateFlow<Long> = _duration.asStateFlow()

        private val _audioTracks = MutableStateFlow<List<AudioTrackInfo>>(emptyList())
        val audioTracks: StateFlow<List<AudioTrackInfo>> = _audioTracks.asStateFlow()

        private val _subtitleTracks = MutableStateFlow<List<SubtitleTrackInfo>>(emptyList())
        val subtitleTracks: StateFlow<List<SubtitleTrackInfo>> = _subtitleTracks.asStateFlow()

        private val _isBuffering = MutableStateFlow(false)
        val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

        private val _videoSize = MutableStateFlow<Size?>(null)
        val videoSize: StateFlow<Size?> = _videoSize.asStateFlow()

        fun updateCurrentPosition(pos: Long) {
            _currentPosition.value = pos
        }
    }
}