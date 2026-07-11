package com.example.torrentstreamer

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Size
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.torrentstreamer.data.AppDatabase
import com.example.torrentstreamer.data.WatchHistory
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

class MainViewModel(application: Application) : AndroidViewModel(application) {
    // Ініціалізуємо системні SharedPreferences на найпершому рядку в тілі класу.
    // Це на 100% захищає компілятор від помилок передчасного зчитування стейтів у наступних змінних!
    private val vibePrefs = application.getSharedPreferences("vibe_prefs", Context.MODE_PRIVATE)

    private val dao = AppDatabase.getDatabase(application).watchHistoryDao()
    private val api = TorrServerApi.create()
    private val gson = Gson()

    private val _torrents = MutableStateFlow<List<Torrent>>(emptyList())
    val torrents: StateFlow<List<Torrent>> = _torrents.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val _settings = MutableStateFlow(TorrSettings())
    val settings: StateFlow<TorrSettings> = _settings.asStateFlow()

    val watchHistory = dao.getAllHistory().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val latestSession = dao.getLatestSession().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _isAutoPipEnabled = MutableStateFlow(vibePrefs.getBoolean("auto_pip_enabled", true))
    val isAutoPipEnabled: StateFlow<Boolean> = _isAutoPipEnabled.asStateFlow()

    private val _isAutoRotationEnabled = MutableStateFlow(vibePrefs.getBoolean("is_auto_rotation_enabled", true))
    val isAutoRotationEnabled: StateFlow<Boolean> = _isAutoRotationEnabled.asStateFlow()

    val isLockedPortrait = MutableStateFlow(false).asStateFlow()
    val playerOrientationMode: StateFlow<Int> = _isAutoRotationEnabled.map { if (it) 0 else 1 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _videoBounds = MutableStateFlow<android.graphics.Rect?>(null)
    val videoBounds: StateFlow<android.graphics.Rect?> = _videoBounds.asStateFlow()

    private var currentFilesHash: String? = null

    private var loadFilesJob: Job? = null

    // Потокобезпечний кеш списків серій в оперативній пам'яті
    private val torrentFilesCache = ConcurrentHashMap<String, List<TorrentFile>>()

    // Стейт-флоу та метод для перемикання діагностичного HUD у плеєрі
    private val _isDiagnosticsHudEnabled = MutableStateFlow(vibePrefs.getBoolean("is_diagnostics_hud_enabled", false))
    val isDiagnosticsHudEnabled: StateFlow<Boolean> = _isDiagnosticsHudEnabled.asStateFlow()

    // НОВЕ: Виділений стейт-потік для ОДНОГО активно програваного торента.
    // Сюди миттєво публікується свіжа швидкість та піри безпосередньо під час плеєра!
    private val _activeTorrentState = MutableStateFlow<Torrent?>(null)
    val activeTorrentState: StateFlow<Torrent?> = _activeTorrentState.asStateFlow()

    fun setDiagnosticsHudEnabled(enabled: Boolean) {
        _isDiagnosticsHudEnabled.value = enabled
        vibePrefs.edit().putBoolean("is_diagnostics_hud_enabled", enabled).apply()
    }

    init {
        refreshTorrents()
        loadSettings()
        startRealtimePolling()
    }

    fun setAutoPipEnabled(enabled: Boolean) {
        _isAutoPipEnabled.value = enabled
        vibePrefs.edit().putBoolean("auto_pip_enabled", enabled).apply()
    }

    fun setAutoRotationEnabled(enabled: Boolean) {
        _isAutoRotationEnabled.value = enabled
        vibePrefs.edit().putBoolean("is_auto_rotation_enabled", enabled).apply()
    }

    fun setLockedPortrait(isPortrait: Boolean) {
        // Заглушка сумісності
    }

    fun setPlayerOrientationMode(mode: Int) {
        // Заглушка сумісності
        setAutoRotationEnabled(mode == 0)
    }

    fun updateVideoBounds(rect: android.graphics.Rect?) {
        _videoBounds.value = rect
    }

    private fun sortTorrents(list: List<Torrent>): List<Torrent> {
        val orderPrefs = getApplication<Application>().getSharedPreferences("torrent_order_prefs", android.content.Context.MODE_PRIVATE)
        val orderString = orderPrefs.getString("hash_order", null) ?: return list
        val orderList = orderString.split(",")
        if (orderList.isEmpty()) return list

        return list.sortedBy { torrent ->
            val index = orderList.indexOf(torrent.hash)
            if (index != -1) index else Int.MAX_VALUE
        }
    }

    fun saveTorrentOrder(orderedList: List<Torrent>) {
        val hashes = orderedList.joinToString(",") { it.hash }
        val orderPrefs = getApplication<Application>().getSharedPreferences("torrent_order_prefs", android.content.Context.MODE_PRIVATE)
        orderPrefs.edit().putString("hash_order", hashes).apply()
        _torrents.value = orderedList
    }

    private fun startRealtimePolling() {
        viewModelScope.launch {
            while (true) {
                try {
                    val playingUrl = currentPlayingUrl.value
                    val activeHash = if (playingUrl != null) {
                        val parts = playingUrl.split("/")
                        if (parts.size >= 5) parts[parts.size - 2] else null
                    } else null

                    if (activeHash != null) {
                        // Опитуємо ТІЛЬКИ активний торент (поодинокий get-запит повертає один об'єкт Torrent)
                        val res = api.actionPost(TorrentAction(action = "get", hash = activeHash))
                        val jsonString = res.string()
                        val torrent = gson.fromJson(jsonString, Torrent::class.java)

                        _activeTorrentState.value = torrent
                    } else {
                        _activeTorrentState.value = null

                        // Звичайне легке опитування всього списку для головного екрана
                        val res = api.actionPost(TorrentAction(action = "list"))
                        val jsonString = res.string()
                        val list: List<Torrent> = gson.fromJson(jsonString, object : TypeToken<List<Torrent>>() {}.type)

                        val categoryPrefs = getApplication<Application>().getSharedPreferences("torrent_categories", android.content.Context.MODE_PRIVATE)
                        val posterPrefs = getApplication<Application>().getSharedPreferences("torrent_posters", android.content.Context.MODE_PRIVATE)

                        val mappedList = list.map { torrent ->
                            val localPoster = posterPrefs.getString(torrent.hash, null)
                            torrent.copy(
                                type = categoryPrefs.getString(torrent.hash, "Фільм") ?: "Фільм",
                                poster = localPoster ?: torrent.poster
                            )
                        }
                        _torrents.value = sortTorrents(mappedList)
                    }
                } catch (_: Exception) { }
                delay(2500) // Опитування раз на 2.5 секунди
            }
        }
    }

    fun refreshTorrents(showSpinner: Boolean = true) {
        viewModelScope.launch {
            if (showSpinner) {
                _isRefreshing.value = true
            }
            var success = false
            var retries = 3

            while (!success && retries > 0) {
                try {
                    val res = api.actionPost(TorrentAction(action = "list"))
                    val jsonString = res.string()
                    val list: List<Torrent> = gson.fromJson(jsonString, object : TypeToken<List<Torrent>>() {}.type)

                    val categoryPrefs = getApplication<Application>().getSharedPreferences("torrent_categories", android.content.Context.MODE_PRIVATE)
                    val posterPrefs = getApplication<Application>().getSharedPreferences("torrent_posters", android.content.Context.MODE_PRIVATE)

                    val mappedList = list.map { torrent ->
                        val localPoster = posterPrefs.getString(torrent.hash, null)
                        torrent.copy(
                            type = categoryPrefs.getString(torrent.hash, "Фільм") ?: "Фільм",
                            poster = localPoster ?: torrent.poster
                        )
                    }
                    _torrents.value = sortTorrents(mappedList)
                    success = true
                } catch (e: Exception) {
                    retries--
                    if (retries > 0) {
                        delay(1000)
                    }
                }
            }
            if (showSpinner) {
                _isRefreshing.value = false
            }
        }
    }

    fun replaceTorrentWithMagnet(oldHash: String, magnet: String, title: String, poster: String, category: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val beforeRes = api.actionPost(TorrentAction(action = "list"))
                val beforeList: List<Torrent> = gson.fromJson(beforeRes.string(), object : TypeToken<List<Torrent>>() {}.type)
                val beforeHashes = beforeList.map { it.hash }.toSet()

                api.actionPost(TorrentAction(action = "rem", hash = oldHash))
                api.actionPost(TorrentAction(action = "add", link = magnet, saveToDb = true))

                val afterRes = api.actionPost(TorrentAction(action = "list"))
                val afterList: List<Torrent> = gson.fromJson(afterRes.string(), object : TypeToken<List<Torrent>>() {}.type)

                val newHash = afterList.map { it.hash }.firstOrNull { it !in beforeHashes }

                if (newHash != null) {
                    val categoryPrefs = getApplication<Application>().getSharedPreferences("torrent_categories", android.content.Context.MODE_PRIVATE)
                    categoryPrefs.edit().putString(newHash, category).apply()

                    val posterPrefs = getApplication<Application>().getSharedPreferences("torrent_posters", android.content.Context.MODE_PRIVATE)
                    posterPrefs.edit().putString(newHash, poster).apply()

                    api.actionPost(TorrentAction(action = "set", hash = newHash, title = title, poster = ""))
                }
                refreshTorrents()
            } catch (_: Exception) { }
        }
    }

    fun replaceTorrentFromFile(oldHash: String, uri: Uri, title: String, poster: String, category: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val beforeRes = api.actionPost(TorrentAction(action = "list"))
                val beforeList: List<Torrent> = gson.fromJson(beforeRes.string(), object : TypeToken<List<Torrent>>() {}.type)
                val beforeHashes = beforeList.map { it.hash }.toSet()

                api.actionPost(TorrentAction(action = "rem", hash = oldHash))

                val context = getApplication<Application>().applicationContext
                val contentResolver = context.contentResolver
                var fileName = "file.torrent"
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        fileName = cursor.getString(nameIndex)
                    }
                }

                val inputStream = contentResolver.openInputStream(uri)
                if (inputStream != null) {
                    val bytes = inputStream.readBytes()
                    inputStream.close()

                    val requestFile = bytes.toRequestBody("application/x-bittorrent".toMediaTypeOrNull())
                    val body = MultipartBody.Part.createFormData("file", fileName, requestFile)

                    val titleBody = "".toRequestBody("text/plain".toMediaTypeOrNull())
                    val posterBody = "".toRequestBody("text/plain".toMediaTypeOrNull())
                    val dataBody = "".toRequestBody("text/plain".toMediaTypeOrNull())
                    val saveBody = "true".toRequestBody("text/plain".toMediaTypeOrNull())

                    api.uploadTorrent(
                        file = body,
                        title = titleBody,
                        poster = posterBody,
                        data = dataBody,
                        save = saveBody
                    )

                    val afterRes = api.actionPost(TorrentAction(action = "list"))
                    val afterList: List<Torrent> = gson.fromJson(afterRes.string(), object : TypeToken<List<Torrent>>() {}.type)
                    val newHash = afterList.map { it.hash }.firstOrNull { it !in beforeHashes }

                    if (newHash != null) {
                        val categoryPrefs = getApplication<Application>().getSharedPreferences("torrent_categories", android.content.Context.MODE_PRIVATE)
                        categoryPrefs.edit().putString(newHash, category).apply()

                        val posterPrefs = getApplication<Application>().getSharedPreferences("torrent_posters", android.content.Context.MODE_PRIVATE)
                        posterPrefs.edit().putString(newHash, poster).apply()

                        api.actionPost(TorrentAction(action = "set", hash = newHash, title = title, poster = ""))
                    }
                    refreshTorrents()
                }
            } catch (_: Exception) { }
        }
    }

    fun markAsLastUsed(url: String, title: String) {
        viewModelScope.launch {
            val historyList = dao.getAllHistory().first()
            val existing = historyList.find { it.videoUrl == url }
            dao.saveProgress(
                WatchHistory(
                    videoUrl = url,
                    title = title,
                    lastPosition = existing?.lastPosition ?: 0L,
                    duration = existing?.duration ?: 0L,
                    isFinished = false,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    fun addTorrent(link: String) {
        viewModelScope.launch {
            try {
                api.actionPost(TorrentAction(action = "add", link = link, saveToDb = true))
                refreshTorrents()
            } catch (_: Exception) { }
        }
    }

    fun addTorrentFromFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _isRefreshing.value = true
            try {
                val context = getApplication<Application>().applicationContext
                val contentResolver = context.contentResolver

                var fileName = "file.torrent"
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        fileName = cursor.getString(nameIndex)
                    }
                }

                val inputStream = contentResolver.openInputStream(uri)
                if (inputStream != null) {
                    val bytes = inputStream.readBytes()
                    inputStream.close()

                    val requestFile = bytes.toRequestBody("application/x-bittorrent".toMediaTypeOrNull())
                    val body = MultipartBody.Part.createFormData("file", fileName, requestFile)

                    val titleBody = "".toRequestBody("text/plain".toMediaTypeOrNull())
                    val posterBody = "".toRequestBody("text/plain".toMediaTypeOrNull())
                    val dataBody = "".toRequestBody("text/plain".toMediaTypeOrNull())
                    val saveBody = "true".toRequestBody("text/plain".toMediaTypeOrNull())

                    api.uploadTorrent(
                        file = body,
                        title = titleBody,
                        poster = posterBody,
                        data = dataBody,
                        save = saveBody
                    )

                    refreshTorrents()
                }
            } catch (e: Exception) {
                _errorMessage.value = "Помилка завантаження файлу"
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun removeTorrent(hash: String) {
        viewModelScope.launch {
            try {
                api.actionPost(TorrentAction(action = "rem", hash = hash))
                refreshTorrents()
            } catch (_: Exception) { }
        }
    }

    fun updateTorrent(hash: String, title: String, posterInput: String, category: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val categoryPrefs = getApplication<Application>().getSharedPreferences("torrent_categories", android.content.Context.MODE_PRIVATE)
                categoryPrefs.edit().putString(hash, category).apply()

                val posterPrefs = getApplication<Application>().getSharedPreferences("torrent_posters", android.content.Context.MODE_PRIVATE)
                var finalPosterPath = posterInput

                if (posterInput.startsWith("content://")) {
                    val context = getApplication<Application>().applicationContext
                    val uri = Uri.parse(posterInput)
                    val postersDir = File(context.filesDir, "posters")
                    if (!postersDir.exists()) {
                        postersDir.mkdirs()
                    }

                    val targetFile = File(postersDir, "${hash}_poster.jpg")
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        FileOutputStream(targetFile).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    finalPosterPath = "file://${targetFile.absolutePath}"
                }

                if (finalPosterPath.isNotBlank()) {
                    posterPrefs.edit().putString(hash, finalPosterPath).apply()
                } else {
                    posterPrefs.edit().remove(hash).apply()
                }

                api.actionPost(TorrentAction(action = "set", hash = hash, title = title, poster = ""))
                refreshTorrents(showSpinner = false)
            } catch (e: Exception) {
                _errorMessage.value = "Помилка оновлення торрента"
            }
        }
    }

    fun loadSettings() {
        viewModelScope.launch {
            try {
                val serverSettings = api.getSettings(SettingsPayload(action = "get"))
                _settings.value = serverSettings
            } catch (_: Exception) { }
        }
    }

    fun saveSettings(newSettings: TorrSettings) {
        _settings.value = newSettings

        viewModelScope.launch(Dispatchers.IO) {
            try {
                api.saveSettings(SettingsPayload(action = "set", sets = newSettings))
            } catch (_: Exception) { }
        }
    }

    fun resetSettingsToDefault(): TorrSettings {
        val defaultSettings = TorrSettings().copy(
            tmdbSettings = TMDBConfig(
                apiKey = "",
                apiUrl = "https://api.themoviedb.org",
                imageUrl = "https://image.tmdb.org",
                imageUrlRu = "https://imagetmdb.com"
            )
        )
        _settings.value = defaultSettings

        viewModelScope.launch(Dispatchers.IO) {
            try {
                api.saveSettings(SettingsPayload(action = "def"))
                api.saveSettings(SettingsPayload(action = "set", sets = defaultSettings))
            } catch (_: Exception) { }
        }

        return defaultSettings
    }

    private val _files = MutableStateFlow<List<TorrentFile>>(emptyList())
    val files: StateFlow<List<TorrentFile>> = _files.asStateFlow()
    private val _isLoadingFiles = MutableStateFlow(false)
    val isLoadingFiles: StateFlow<Boolean> = _isLoadingFiles.asStateFlow()

    fun loadFiles(hash: String, force: Boolean = false) {
        if (!force && torrentFilesCache.containsKey(hash)) {
            val cachedFiles = torrentFilesCache[hash]
            if (cachedFiles != null && cachedFiles.isNotEmpty()) {
                _files.value = cachedFiles
                _isLoadingFiles.value = false
                currentFilesHash = hash
                return
            }
        }

        loadFilesJob?.cancel()
        loadFilesJob = viewModelScope.launch {
            if (!force && currentFilesHash == hash && _files.value.isNotEmpty()) {
                return@launch
            }

            if (currentFilesHash != hash) {
                _files.value = emptyList()
            }
            currentFilesHash = hash
            _isLoadingFiles.value = true
            var success = false
            var retries = 35

            while (!success && retries > 0) {
                try {
                    val res = api.actionPost(TorrentAction(action = "get", hash = hash))
                    val jsonString = res.string()
                    val torrent: Torrent = gson.fromJson(jsonString, Torrent::class.java)
                    val fileList = torrent.allFiles

                    if (fileList.isNotEmpty()) {
                        _files.value = fileList

                        torrentFilesCache[hash] = fileList
                        success = true
                    } else {
                        retries--
                        if (retries > 0) {
                            delay(1500)
                        }
                    }
                } catch (e: Exception) {
                    retries--
                    if (retries > 0) {
                        delay(1500)
                    }
                }
            }
            _isLoadingFiles.value = false
        }
    }

    fun clearFiles() {
        _files.value = emptyList()
        _isLoadingFiles.value = false
        currentFilesHash = null
    }

    fun clearError() { _errorMessage.value = null }

    fun updateProgress(url: String, title: String, pos: Long, dur: Long) {
        if (pos < 500) return
        viewModelScope.launch {
            val isFinished = dur > 0 && pos >= (dur - 20000)
            dao.saveProgress(WatchHistory(url, title, pos, dur, isFinished, System.currentTimeMillis()))
        }
    }

    val playerInstance: StateFlow<androidx.media3.exoplayer.ExoPlayer?> = PlaybackService.playerInstance
    val currentPlayingUrl: StateFlow<String?> = PlaybackService.currentPlayingUrl
    val currentPlayingTitle: StateFlow<String?> = PlaybackService.currentTitle
    val isPlayerPlaying: StateFlow<Boolean> = PlaybackService.isPlayerPlaying
    val playerPosition: StateFlow<Long> = PlaybackService.currentPosition
    val playerDuration: StateFlow<Long> = PlaybackService.duration
    val availableAudioTracks: StateFlow<List<AudioTrackInfo>> = PlaybackService.audioTracks
    val availableSubtitleTracks: StateFlow<List<SubtitleTrackInfo>> = PlaybackService.subtitleTracks
    val videoSize: StateFlow<Size?> = PlaybackService.videoSize

    val isBuffering: StateFlow<Boolean> = PlaybackService.isBuffering

    fun playVideo(url: String, title: String) {
        viewModelScope.launch {
            markAsLastUsed(url, title)

            if (PlaybackService.currentPlayingUrl.value == url) {
                return@launch
            }

            val intent = Intent(getApplication(), PlaybackService::class.java).apply {
                action = PlaybackService.ACTION_PLAY
                putExtra(PlaybackService.EXTRA_URL, url)
                putExtra(PlaybackService.EXTRA_TITLE, title)
            }
            androidx.core.content.ContextCompat.startForegroundService(getApplication(), intent)
        }
    }

    fun togglePlayback() {
        val playPauseIntent = Intent(getApplication(), PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_TOGGLE_PLAY
        }
        getApplication<Application>().startService(playPauseIntent)
    }

    fun seekToPosition(positionMs: Long) {
        PlaybackService.playerInstance.value?.seekTo(positionMs)
        PlaybackService.updateCurrentPosition(positionMs)

        val serviceIntent = Intent(getApplication(), PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_SEEK
            putExtra(PlaybackService.EXTRA_POSITION, positionMs)
        }
        getApplication<Application>().startService(serviceIntent)
    }

    fun changeAudioTrack(groupIndex: Int, trackIndex: Int) {
        val intent = Intent(getApplication(), PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_SELECT_TRACK
            putExtra(PlaybackService.EXTRA_GROUP_INDEX, groupIndex)
            putExtra(PlaybackService.EXTRA_TRACK_INDEX, trackIndex)
        }
        getApplication<Application>().startService(intent)
    }

    fun changeSubtitleTrack(groupIndex: Int, trackIndex: Int) {
        val intent = Intent(getApplication(), PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_SELECT_SUBTITLE
            val urlParts = currentPlayingUrl.value?.split("/")
            val fileIndex = if (urlParts != null && urlParts.size >= 5) urlParts.last().toIntOrNull() ?: -1 else -1
            putExtra(PlaybackService.EXTRA_SUBTITLE_GROUP_INDEX, groupIndex)
            putExtra(PlaybackService.EXTRA_SUBTITLE_TRACK_INDEX, trackIndex)
        }
        getApplication<Application>().startService(intent)
    }

    fun disableSubtitles() {
        val intent = Intent(getApplication(), PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_DISABLE_SUBTITLES
        }
        getApplication<Application>().startService(intent)
    }

    fun playNextEpisode() {
        val currentUrl = currentPlayingUrl.value ?: latestSession.value?.videoUrl ?: return
        val urlParts = currentUrl.split("/")
        if (urlParts.size < 5) return
        val hash = urlParts[urlParts.size - 2]
        val currentIndex = urlParts.last().toIntOrNull() ?: return

        viewModelScope.launch {
            if (_files.value.isEmpty()) {
                loadFiles(hash)
            }
            val fileList = _files.value
            val currentFileIdx = fileList.indexOfFirst { it.index == currentIndex }
            if (currentFileIdx != -1 && currentFileIdx < fileList.lastIndex) {
                val nextFile = fileList[currentFileIdx + 1]
                val nextUrl = "http://127.0.0.1:8090/play/$hash/${nextFile.index}"
                val cleanTitle = nextFile.path.substringAfterLast("/")
                playVideo(nextUrl, cleanTitle)
            }
        }
    }

    fun playPreviousEpisode() {
        val currentUrl = currentPlayingUrl.value ?: latestSession.value?.videoUrl ?: return
        val urlParts = currentUrl.split("/")
        if (urlParts.size < 5) return
        val hash = urlParts[urlParts.size - 2]
        val currentIndex = urlParts.last().toIntOrNull() ?: return

        viewModelScope.launch {
            if (_files.value.isEmpty()) {
                loadFiles(hash)
            }
            val fileList = _files.value
            val currentFileIdx = fileList.indexOfFirst { it.index == currentIndex }
            if (currentFileIdx > 0) {
                val prevFile = fileList[currentFileIdx - 1]
                val prevUrl = "http://127.0.0.1:8090/play/$hash/${prevFile.index}"
                val cleanTitle = prevFile.path.substringAfterLast("/")
                playVideo(prevUrl, cleanTitle)
            }
        }
    }
}