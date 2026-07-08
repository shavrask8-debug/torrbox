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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveMetadata(metadata: TorrentMetadata)

    @Query("SELECT * FROM torrent_metadata WHERE hash = :hash LIMIT 1")
    fun getMetadata(hash: String): Flow<TorrentMetadata?>
}