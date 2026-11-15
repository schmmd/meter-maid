package com.schmitztech.metermaid.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 2 dropped the per-row sync columns; version 3 dropped ocrRaw and minAgreement,
 * which nothing ever read back. Migrations are carried from 2 onward: the exported CSV
 * is the durable copy, but the photos behind each row live only here, and an upgrade
 * must not throw them away.
 */
@Database(entities = [Reading::class], version = 3, exportSchema = false)
abstract class MeterMaidDatabase : RoomDatabase() {
    abstract fun readings(): ReadingDao

    companion object {
        @Volatile private var instance: MeterMaidDatabase? = null

        /** minSdk 26 ships a SQLite without DROP COLUMN, so the table is rebuilt. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE readings_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        readingDate TEXT NOT NULL,
                        capturedAtUtc INTEGER NOT NULL,
                        value INTEGER NOT NULL,
                        meterId TEXT NOT NULL,
                        corrected INTEGER NOT NULL,
                        photoPath TEXT NOT NULL,
                        note TEXT NOT NULL)""",
                )
                db.execSQL(
                    """INSERT INTO readings_new
                        SELECT id, readingDate, capturedAtUtc, value, meterId, corrected,
                               photoPath, note
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
                ).addMigrations(MIGRATION_2_3).build().also { instance = it }
            }
    }
}
