package com.schmitztech.metermaid.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Reading::class], version = 2, exportSchema = false)
abstract class MeterMaidDatabase : RoomDatabase() {
    abstract fun readings(): ReadingDao

    companion object {
        @Volatile private var instance: MeterMaidDatabase? = null

        /**
         * Drops the per-row sync bookkeeping. The export rewrites the whole file from
         * the whole table, so "is the file current" was never a per-row fact — the rows
         * only ever moved together, and the coordinator now tracks the one state itself.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE readings_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        readingDate TEXT NOT NULL,
                        capturedAtUtc INTEGER NOT NULL,
                        value INTEGER NOT NULL,
                        meterId TEXT NOT NULL,
                        ocrRaw TEXT,
                        minAgreement REAL,
                        corrected INTEGER NOT NULL,
                        photoPath TEXT NOT NULL,
                        note TEXT NOT NULL)""",
                )
                db.execSQL(
                    """INSERT INTO readings_new
                        SELECT id, readingDate, capturedAtUtc, value, meterId, ocrRaw,
                               minAgreement, corrected, photoPath, note
                        FROM readings""",
                )
                db.execSQL("DROP TABLE readings")
                db.execSQL("ALTER TABLE readings_new RENAME TO readings")
            }
        }

        fun get(context: Context): MeterMaidDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MeterMaidDatabase::class.java,
                    "metermaid.db"
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
