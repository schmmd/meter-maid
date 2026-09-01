package com.schmitztech.metermaid.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingDao {

    @Query("SELECT * FROM readings ORDER BY readingDate DESC, capturedAtUtc DESC")
    fun observeAll(): Flow<List<Reading>>

    @Query("SELECT * FROM readings WHERE id = :id")
    suspend fun byId(id: String): Reading?

    /**
     * Most recent reading on the same meter — the baseline for every validation rule.
     * [excluding], when set, ignores one row: the one currently being edited.
     */
    @Query("""
        SELECT * FROM readings
        WHERE meterId = :meterId AND readingDate <= :onOrBefore
            AND (:excluding IS NULL OR id != :excluding)
        ORDER BY readingDate DESC, capturedAtUtc DESC
        LIMIT 1
    """)
    suspend fun latestBefore(meterId: String, onOrBefore: String, excluding: String?): Reading?

    @Query("SELECT * FROM readings")
    suspend fun allOnce(): List<Reading>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reading: Reading)

    @Query("DELETE FROM readings WHERE id = :id")
    suspend fun delete(id: String)
}
