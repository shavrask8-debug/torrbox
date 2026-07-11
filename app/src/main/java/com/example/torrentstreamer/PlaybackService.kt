@file:Suppress("UnstableApiUsage", "DEPRECATION", "deprecation")

package com.example.torrentstreamer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Size
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaStyleNotificationHelper
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.SessionResult
import com.example.torrentstreamer.data.AppDatabase
import com.example.torrentstreamer.data.WatchHistory
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var progressJob: Job? = null

    private var retryAttempts = 0

    // Поточний список серій активного торента для розрахунку команд перемикання у швидких налаштуваннях
    private var currentFilesList: List<TorrentFile> = emptyList()

    // Кеш байтів поточної обкладинки для миттєвої й безпечної передачі у шторку без блокування головного потоку
    private var currentArtworkBytes: ByteArray? = null

    override fun onCreate() {
        super.onCreate()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "vibe_playback_channel",
                "Відтворення Vibe",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                5000,
                30000,
                800,
                1200
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderersFactory = DefaultRenderersFactory(this).apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        }

        // КРИТИЧНИЙ ПАТЧ ДЛЯ .AVI ФАЙЛІВ: Створюємо фабрику екстракторів через делегування.
        val extractorsFactory = androidx.media3.extractor.ExtractorsFactory {
            val defaultExtractors = androidx.media3.extractor.DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
                .setAdtsExtractorFlags(androidx.media3.extractor.ts.AdtsExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING)
                .createExtractors()

            val aviIdx = defaultExtractors.indexOfFirst { it is androidx.media3.extractor.avi.AviExtractor }
            if (aviIdx > 0) {
                val temp = defaultExtractors[0]
                defaultExtractors[0] = defaultExtractors[aviIdx]
                defaultExtractors[aviIdx] = temp
            }
            defaultExtractors
        }

        val httpDataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
            .setAllowCrossProtocolRedirects(true)

        val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(this, httpDataSourceFactory)

        val player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)
            )
            .setLoadControl(loadControl)
            .build().apply {
                repeatMode = Player.REPEAT_MODE_OFF
                playWhenReady = true
            }
        exoPlayer = player

        // Обгортаємо плеєр у ForwardingPlayer для декларування доступності кнопок перемикання серій у системній шторці
        val forwardingPlayer = object : androidx.media3.common.ForwardingPlayer(player) {
            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands().buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()
            }

            // Динамічно робимо кнопки активними або сірими залежно від того, чи є куди перемикатись у списку файлів
            override fun isCommandAvailable(command: Int): Boolean {
                return when (command) {
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                        val currentUrl = _currentPlayingUrl.value
                        val fileList = currentFilesList
                        if (currentUrl != null && fileList.isNotEmpty()) {
                            val currentIndex = currentUrl.split("/").last().toIntOrNull() ?: -1
                            val idx = fileList.indexOfFirst { it.index == currentIndex }
                            idx != -1 && idx < fileList.lastIndex
                        } else false
                    }
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                        val currentUrl = _currentPlayingUrl.value
                        val fileList = currentFilesList
                        if (currentUrl != null && fileList.isNotEmpty()) {
                            val currentIndex = currentUrl.split("/").last().toIntOrNull() ?: -1
                            val idx = fileList.indexOfFirst { it.index == currentIndex }
                            idx > 0
                        } else false
                    }
                    else -> super.isCommandAvailable(command)
                }
            }

            override fun seekToNext() { playNextEpisode() }
            override fun seekToNextMediaItem() { playNextEpisode() }
            override fun seekToPrevious() { playPreviousEpisode() }
            override fun seekToPreviousMediaItem() { playPreviousEpisode() }

            // КРИТИЧНИЙ ПАТЧ БАГУ #2675: Реєструємо системних слухачів MediaSession НАПРЯМУ на сирому exoPlayer.
            override fun addListener(listener: Player.Listener) {
                player.addListener(listener)
            }

            override fun removeListener(listener: Player.Listener) {
                player.removeListener(listener)
            }

            // Перехоплюємо отримання поточного елемента медіасесією.
            // Ми миттєво віддаємо кешовані байти currentArtworkBytes з оперативної пам'яті за 0 мілісекунд,
            // повністю запобігаючи помилкам NetworkOnMainThreadException та зависанням інтерфейсу.
            override fun getCurrentMediaItem(): MediaItem? {
                val item = player.currentMediaItem ?: return null
                val customMetadata = MediaMetadata.Builder()
                    .setTitle(_currentTitle.value ?: "Vibe")
                    .setArtist("Vibe Player")
                    .apply {
                        val artworkBytes = currentArtworkBytes
                        if (artworkBytes != null) {
                            setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                        }
                    }
                    .build()
                return item.buildUpon().setMediaMetadata(customMetadata).build()
            }

            override fun getMediaMetadata(): MediaMetadata {
                val item = getCurrentMediaItem()
                return item?.mediaMetadata ?: super.getMediaMetadata()
            }

            override fun getPlaylistMetadata(): MediaMetadata {
                val item = getCurrentMediaItem()
                return item?.mediaMetadata ?: super.getPlaylistMetadata()
            }
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            action = "com.example.torrentstreamer.action.OPEN_PLAYER"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val optionsBundle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            android.app.ActivityOptions.makeBasic().apply {
                setPendingIntentCreatorBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }.toBundle()
        } else {
            null
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            optionsBundle
        )

        // Перехоплюємо системні кліки по кнопках шторки та перенаправляємо їх у наші методи швидкої зміни серій
        val sessionCallback = object : MediaSession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                val playerCommands = Player.Commands.Builder()
                    .addAllCommands()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()

                return MediaSession.ConnectionResult.accept(
                    androidx.media3.session.SessionCommands.EMPTY,
                    playerCommands
                )
            }

            override fun onPlayerCommandRequest(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                playerCommand: Int
            ): Int {
                if (playerCommand == Player.COMMAND_SEEK_TO_NEXT || playerCommand == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) {
                    playNextEpisode()
                    return SessionResult.RESULT_SUCCESS
                }
                if (playerCommand == Player.COMMAND_SEEK_TO_PREVIOUS || playerCommand == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) {
                    playPreviousEpisode()
                    return SessionResult.RESULT_SUCCESS
                }
                return super.onPlayerCommandRequest(session, controller, playerCommand)
            }
        }

        mediaSession = MediaSession.Builder(this, forwardingPlayer)
            .setSessionActivity(pendingIntent)
            .setCallback(sessionCallback)
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId("vibe_playback_channel")
                .build()
        )

        val mediaStyle = MediaStyleNotificationHelper.MediaStyle(mediaSession!!)

        val initialNotification = NotificationCompat.Builder(this, "vibe_playback_channel")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Запуск відтворення")
            .setContentText("Завантаження торент-потоку...")
            .setStyle(mediaStyle)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        startForeground(1001, initialNotification)

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

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                retryAttempts = 0
                mediaItem?.let { item ->
                    val newUrl = item.mediaId
                    val newTitle = item.mediaMetadata.title?.toString() ?: "Vibe"

                    _currentPlayingUrl.value = newUrl
                    _currentTitle.value = newTitle

                    startProgressTracker()
                    triggerNotificationUpdate()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                _isPlayerPlaying.value = false
                _isBuffering.value = false

                val cause = error.cause
                val isDecoderError = cause?.javaClass?.name?.contains("DecoderInitializationException") == true
                val isSampleQueueNpe = cause is java.lang.NullPointerException ||
                        (error.errorCode == PlaybackException.ERROR_CODE_UNSPECIFIED &&
                                error.message?.contains("SampleQueue") == true)

                val isIoError = error.errorCode in PlaybackException.ERROR_CODE_IO_UNSPECIFIED..PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE

                if (isDecoderError || isSampleQueueNpe) {
                    retryAttempts = 0
                    android.util.Log.w("PlaybackService", "Виявлено критичну помилку (декодер/черга). Спроба відновлення...")

                    serviceScope.launch {
                        exoPlayer?.let { p ->
                            val currentPos = p.currentPosition
                            val wasPlaying = p.playWhenReady
                            val currentMediaItems = mutableListOf<MediaItem>()
                            for (i in 0 until p.mediaItemCount) {
                                currentMediaItems.add(p.getMediaItemAt(i))
                            }
                            val currentIndex = p.currentMediaItemIndex

                            p.stop()
                            p.clearMediaItems()
                            delay(200)

                            val parameters = p.trackSelectionParameters
                                .buildUpon()
                                .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_AUDIO)
                                .build()
                            p.trackSelectionParameters = parameters

                            p.setMediaItems(currentMediaItems, currentIndex, currentPos)
                            p.prepare()
                            p.playWhenReady = wasPlaying
                        }
                    }
                } else if (isIoError && retryAttempts < 3) {
                    retryAttempts++
                    val delayMs = 1500L * retryAttempts
                    android.util.Log.w("PlaybackService", "Мережева помилка (спроба $retryAttempts/3). Повтор через ${delayMs}мс...")

                    serviceScope.launch {
                        delay(delayMs.milliseconds)
                        exoPlayer?.let { p ->
                            val currentPos = p.currentPosition
                            val wasPlaying = p.playWhenReady
                            p.prepare()
                            p.seekTo(currentPos)
                            p.playWhenReady = wasPlaying
                        }
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
                    val trackIdx = intent.getIntExtra(EXTRA_TRACK_INDEX, -1)
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

    // ТВОЯ «ЯДЕРНА КНОПКА» (ПРАЦЮЄ НА 100%): Метод примусового перезапуску шторки.
    // Ми повністю гасимо стару нотифікацію з її завислим кешем, видаляємо її з SystemUI
    // і миттєво запускаємо абсолютно чисту нову нотифікацію з правильними даними.
    private fun forceRefreshNotification(title: String, artworkBytes: ByteArray?) {
        val session = mediaSession ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(1001)

        val mediaStyle = MediaStyleNotificationHelper.MediaStyle(session)

        val intent = Intent(this, MainActivity::class.java).apply {
            action = "com.example.torrentstreamer.action.OPEN_PLAYER"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val optionsBundle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            android.app.ActivityOptions.makeBasic().apply {
                setPendingIntentCreatorBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }.toBundle()
        } else {
            null
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            optionsBundle
        )

        val notification = NotificationCompat.Builder(this, "vibe_playback_channel")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText("Завантаження торент-потоку...")
            .setStyle(mediaStyle)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        startForeground(1001, notification)
    }

    private fun loadArtworkData(posterUrl: String?): ByteArray? {
        if (posterUrl.isNullOrBlank()) return null
        return try {
            if (posterUrl.startsWith("http://") || posterUrl.startsWith("https://")) {
                val url = java.net.URL(posterUrl)
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.doInput = true

                val bytes = connection.inputStream.use { it.readBytes() }
                connection.disconnect()

                compressBitmapBytes(bytes)
            } else {
                val cleanPath = posterUrl.removePrefix("file://").substringBefore("?")
                val file = java.io.File(cleanPath)
                if (file.exists()) {
                    compressBitmapBytes(file.readBytes())
                } else null
            }
        } catch (e: Exception) {
            android.util.Log.w("PlaybackService", "Помилка завантаження обкладинки: ${e.message}")
            null
        }
    }

    private fun compressBitmapBytes(bytes: ByteArray): ByteArray? {
        return try {
            val options = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

            var scale = 1
            while (options.outWidth / scale / 2 >= 256 && options.outHeight / scale / 2 >= 256) {
                scale *= 2
            }

            val decodeOptions = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = scale
            }
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions) ?: return null

            val stream = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, stream)
            val compressedBytes = stream.toByteArray()
            bitmap.recycle()
            compressedBytes
        } catch (_: Exception) {
            null
        }
    }

    private fun initAndPlay(videoUrl: String, title: String) {
        exoPlayer?.let { p ->
            p.stop()
            p.clearMediaItems()
        }

        _currentPosition.value = 0L
        _duration.value = 0L

        val oldUrl = _currentPlayingUrl.value
        val oldHash = if (oldUrl != null) {
            val parts = oldUrl.split("/")
            if (parts.size >= 5) parts[parts.size - 2] else null
        } else null

        if (_currentPlayingUrl.value == videoUrl && exoPlayer?.playbackState != Player.STATE_IDLE) {
            return
        }

        val parts = videoUrl.split("/")
        if (parts.size < 5) return
        val hash = parts[parts.size - 2]

        val database = AppDatabase.getDatabase(applicationContext).watchHistoryDao()

        serviceScope.launch(Dispatchers.IO) {
            // Очищуємо попередній торент в Go-рушії ТІЛЬКИ якщо ми дійсно перемикаємось на ІНШИЙ торент
            if (oldHash != null && oldHash != hash) {
                try {
                    val api = TorrServerApi.create()
                    api.actionPost(TorrentAction(action = "drop", hash = oldHash))
                } catch (_: Exception) {}
            }

            // Відновлюємо отримання історії перегляду з Room у фоновому IO-потоці
            val historyList = database.getAllHistory().first()
            val savedSession = historyList.find { it.videoUrl == videoUrl }
            val savedPos = if (savedSession != null && !savedSession.isFinished) savedSession.lastPosition else 0L

            // Завантажуємо список серій торента для коректного визначення доступності стрілок
            var filesList = emptyList<TorrentFile>()
            try {
                val api = TorrServerApi.create()
                val gson = Gson()
                val res = api.actionPost(TorrentAction(action = "get", hash = hash))
                val torrent = gson.fromJson(res.string(), Torrent::class.java)
                filesList = torrent.allFiles
            } catch (_: Exception) {}

            val posterPrefs = getSharedPreferences("torrent_posters", Context.MODE_PRIVATE)
            val posterUrl = posterPrefs.getString(hash, null)

            // Завантажуємо та стискаємо постер ПОВНІСТЮ у фоновому потоці Dispatchers.IO
            val artworkBytes = loadArtworkData(posterUrl)

            withContext(Dispatchers.Main) {
                // Атомарно оновлюємо всі змінні прогресу та постерів
                _currentPlayingUrl.value = videoUrl
                _currentTitle.value = title
                currentArtworkBytes = artworkBytes

                if (filesList.isNotEmpty()) {
                    currentFilesList = filesList
                }

                exoPlayer?.let { p ->
                    val mediaMetadata = MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist("Vibe Player")
                        .apply {
                            if (artworkBytes != null) {
                                setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                            }
                        }
                        .build()

                    val mediaItem = MediaItem.Builder()
                        .setMediaId(videoUrl)
                        .setUri(videoUrl)
                        .setMediaMetadata(mediaMetadata)
                        .build()

                    p.setPlaylistMetadata(mediaMetadata)

                    // Використовуємо replaceMediaItem замість setMediaItem, якщо у плеєрі вже є активний медіа-елемент
                    if (p.mediaItemCount > 0) {
                        p.replaceMediaItem(0, mediaItem)
                        p.seekTo(savedPos)
                    } else {
                        p.setMediaItem(mediaItem, savedPos)
                    }

                    // Перезапускаємо шторку швидких налаштувань для миттєвого оновлення назви та обкладинки
                    forceRefreshNotification(title, artworkBytes)

                    p.prepare()
                    p.play()
                }
            }
        }
    }

    private fun playNextEpisode() {
        val currentUrl = _currentPlayingUrl.value ?: return
        val urlParts = currentUrl.split("/")
        if (urlParts.size < 5) return
        val hash = urlParts[urlParts.size - 2]
        val currentIndex = urlParts.last().toIntOrNull() ?: return

        val fileList = currentFilesList
        if (fileList.isEmpty()) return

        val currentFileIdx = fileList.indexOfFirst { it.index == currentIndex }
        if (currentFileIdx != -1 && currentFileIdx < fileList.lastIndex) {
            val nextFile = fileList[currentFileIdx + 1]
            val nextUrl = "http://127.0.0.1:8090/play/$hash/${nextFile.index}"
            val cleanTitle = nextFile.path.substringAfterLast("/")
            initAndPlay(nextUrl, cleanTitle)
        }
    }

    private fun playPreviousEpisode() {
        val currentUrl = _currentPlayingUrl.value ?: return
        val urlParts = currentUrl.split("/")
        if (urlParts.size < 5) return
        val hash = urlParts[urlParts.size - 2]
        val currentIndex = urlParts.last().toIntOrNull() ?: return

        val fileList = currentFilesList
        if (fileList.isEmpty()) return

        val currentFileIdx = fileList.indexOfFirst { it.index == currentIndex }
        if (currentFileIdx > 0) {
            val prevFile = fileList[currentFileIdx - 1]
            val prevUrl = "http://127.0.0.1:8090/play/$hash/${prevFile.index}"
            val cleanTitle = prevFile.path.substringAfterLast("/")
            initAndPlay(prevUrl, cleanTitle)
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
                delay(500.milliseconds)
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

        for ((groupIndex, group) in tracks.groups.withIndex()) {
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

                val parameters = player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
                    .setSelectUndeterminedTextLanguage(true)
                    .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_TEXT)
                    .addOverride(TrackSelectionOverride(mediaTrackGroup, listOf(trackIndex)))
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

    // ВІДНОВЛЕНО: Повертаємо на місце метод вимкнення субтитрів
    private fun disableSubtitles() {
        exoPlayer?.let { player ->
            val wasPlaying = player.playWhenReady
            val currentPos = player.currentPosition

            player.playWhenReady = false

            val parameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
                .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_TEXT)
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