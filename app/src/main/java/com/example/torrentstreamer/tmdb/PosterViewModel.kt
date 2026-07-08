package com.example.torrentstreamer.tmdb

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PosterUiState {
    object Loading : PosterUiState
    data class Success(val poster: PosterInfo) : PosterUiState
    object Error : PosterUiState
}

class PosterViewModel(
    private val rawTitle: String,
    private val customPoster: String? = null
) : ViewModel() {
    private val _uiState = MutableStateFlow<PosterUiState>(PosterUiState.Loading)
    // Виправили тип на StateFlow
    val uiState: StateFlow<PosterUiState> = _uiState.asStateFlow()

    private val api = CinemetaApi.create()

    init {
        checkCustomOrFetch(customPoster, rawTitle)
    }

    fun checkCustomOrFetch(customPoster: String?, rawTitle: String) {
        if (!customPoster.isNullOrBlank()) {
            // Якщо користувач додав свій постер/файл — миттєво повертаємо його, ігноруючи Cinemeta
            _uiState.value = PosterUiState.Success(
                PosterInfo(
                    posterUrl = customPoster,
                    year = "Кастомний",
                    isAnime = false,
                    runtime = null,
                    director = null
                )
            )
        } else {
            // Якщо кастомного немає — запускаємо стандартний пошук Cinemeta
            fetchMetadata()
        }
    }

    private fun fetchMetadata() {
        viewModelScope.launch {
            try {
                val cleanedQuery = cleanTorrentTitle(rawTitle)

                // Спочатку шукаємо серед фільмів
                var response = api.searchMeta("movie", cleanedQuery)
                var meta = response.metas?.firstOrNull()

                // Якщо фільм не знайдено, шукаємо в базі серіалів
                if (meta == null) {
                    response = api.searchMeta("series", cleanedQuery)
                    meta = response.metas?.firstOrNull()
                }

                // Зберігаємо постер у локальну змінну для успішного Smart Cast
                val posterUrl = meta?.poster
                if (meta != null && !posterUrl.isNullOrBlank()) {
                    val isAnime = meta.genres?.any { it.contains("Anime", true) } == true
                    _uiState.value = PosterUiState.Success(
                        PosterInfo(
                            posterUrl = posterUrl,
                            year = meta.year ?: "Невідомо",
                            isAnime = isAnime,
                            runtime = meta.runtime,
                            director = meta.genres?.firstOrNull()
                        )
                    )
                } else {
                    _uiState.value = PosterUiState.Error
                }
            } catch (e: Exception) {
                _uiState.value = PosterUiState.Error
            }
        }
    }

    private fun cleanTorrentTitle(title: String): String {
        return title
            .replace(Regex("\\[.*?\\]"), "") // Видаляємо квадрати [1080p]
            .replace(Regex("\\(.*?\\)"), "") // Видаляємо дужки (2024)
            .split(Regex("(?i)\\b(1080p|720p|4k|2160p|bluray|web-dl|h264|x264|hevc|h265|rip|bdr構造)\\b"))[0]
            .trim()
    }

    class Factory(
        private val title: String,
        private val customPoster: String? = null
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return PosterViewModel(title, customPoster) as T
        }
    }
}