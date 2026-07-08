package com.example.torrentstreamer.tmdb

import com.google.gson.annotations.SerializedName

data class CinemetaResponse(
    @SerializedName("metas") val metas: List<CinemetaMeta>? = emptyList()
)

data class CinemetaMeta(
    @SerializedName("imdb_id") val imdbId: String?,
    @SerializedName("name") val name: String,
    @SerializedName("poster") val poster: String?,
    @SerializedName("year") val year: String?,
    @SerializedName("type") val type: String?,
    @SerializedName("genres") val genres: List<String>? = emptyList(),
    @SerializedName("runtime") val runtime: String?
)

// Чиста модель для використання всередині нашого UI
data class PosterInfo(
    val posterUrl: String,
    val year: String,
    val isAnime: Boolean,
    val runtime: String?,
    val director: String? = null
)