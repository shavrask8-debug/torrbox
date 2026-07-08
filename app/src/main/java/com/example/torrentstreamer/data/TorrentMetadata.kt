package com.example.torrentstreamer.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "torrent_metadata")
data class TorrentMetadata(
    @PrimaryKey val hash: String,
    val title: String,
    val posterUrl: String,
    val year: String,
    val director: String? = null,
    val runtime: String? = null,
    val isSeries: Boolean = false,
    val isAnime: Boolean = false
)