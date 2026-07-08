package com.example.torrentstreamer.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "watch_history")
data class WatchHistory(
    @PrimaryKey val videoUrl: String,
    val title: String,
    val lastPosition: Long,
    val duration: Long,
    val isFinished: Boolean,
    val timestamp: Long
)