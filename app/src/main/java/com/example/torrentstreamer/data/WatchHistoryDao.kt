package com.example.torrentstreamer.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchHistoryDao {
    @Query("SELECT * FROM watch_history ORDER BY timestamp DESC")
    fun getAllHistory(): Flow<List<WatchHistory>>

    @Query("SELECT * FROM watch_history ORDER BY timestamp DESC LIMIT 1")
    fun getLatestSession(): Flow<WatchHistory?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProgress(history: WatchHistory)

    @Query("SELECT lastPosition FROM watch_history WHERE videoUrl = :url LIMIT 1")
    suspend fun getProgressByUrl(url: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveMetadata(metadata: TorrentMetadata)

    @Query("SELECT * FROM torrent_metadata WHERE hash = :hash LIMIT 1")
    fun getMetadata(hash: String): Flow<TorrentMetadata?>
}
