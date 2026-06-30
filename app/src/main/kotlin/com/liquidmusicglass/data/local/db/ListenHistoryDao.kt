package com.liquidmusicglass.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ListenHistoryDao {

    /** Записать/обновить прослушивание (по trackId, обновляя playedAt). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: ListenHistoryEntity)

    /** Реактивный список истории (свежие сверху). */
    @Query("SELECT * FROM listen_history ORDER BY playedAt DESC LIMIT :limit")
    fun observe(limit: Int = 300): Flow<List<ListenHistoryEntity>>

    /** Очистить всю историю. */
    @Query("DELETE FROM listen_history")
    suspend fun clear()
}
