package com.schmitztech.metermaid.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter fun toSync(v: String): SyncState = SyncState.valueOf(v)
    @TypeConverter fun fromSync(v: SyncState): String = v.name
}

@Database(entities = [Reading::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
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
