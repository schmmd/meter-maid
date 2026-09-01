package com.schmmd.drumroll.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingDao {

    @Query("SELECT * FROM readings ORDER BY readingDate DESC, capturedAtUtc DESC")
    fun observeAll(): Flow<List<Reading>>

    @Query("SELECT * FROM readings WHERE id = :id")
    suspend fun byId(id: String): Reading?

    /** Most recent reading on the same meter — the baseline for every validation rule. */
    @Query("""
        SELECT * FROM readings
        WHERE meterId = :meterId AND readingDate <= :onOrBefore
        ORDER BY readingDate DESC, capturedAtUtc DESC
        LIMIT 1
    """)
    suspend fun latestBefore(meterId: String, onOrBefore: String): Reading?

    @Query("SELECT * FROM readings WHERE readingDate = :date AND meterId = :meterId LIMIT 1")
    suspend fun onDate(date: String, meterId: String): Reading?

    @Query("SELECT * FROM readings WHERE sync IN ('QUEUED', 'FAILED') ORDER BY readingDate ASC")
    suspend fun pending(): List<Reading>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reading: Reading)

    @Update
    suspend fun update(reading: Reading)

    @Query("DELETE FROM readings WHERE id = :id")
    suspend fun delete(id: String)
}
