package com.schmitztech.metermaid.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Version 2 dropped the per-row sync columns; version 3 dropped ocrRaw and minAgreement,
 * which nothing ever read back. Migrations are not carried: the log's durable home is
 * the exported CSV, so an upgrade rebuilds the database and the user re-imports.
 */
@Database(entities = [Reading::class], version = 3, exportSchema = false)
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
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
