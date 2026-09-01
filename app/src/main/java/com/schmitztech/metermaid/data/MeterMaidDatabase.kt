package com.schmitztech.metermaid.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Version 2 dropped the per-row sync columns. The only phone that ever held a version 1
 * database has been migrated, so the migration itself is gone rather than carried
 * forever — a fresh install creates version 2 directly.
 */
@Database(entities = [Reading::class], version = 2, exportSchema = false)
abstract class MeterMaidDatabase : RoomDatabase() {
    abstract fun readings(): ReadingDao

    companion object {
        @Volatile private var instance: MeterMaidDatabase? = null

        fun get(context: Context): MeterMaidDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MeterMaidDatabase::class.java,
                    "metermaid.db"
                ).build().also { instance = it }
            }
    }
}
