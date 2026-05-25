package com.liquidmusicglass.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory

/**
 * Room Database с Requery SQLite движком и WAL режимом.
 *
 * Requery SQLite обходит стандартный SQLite Android, предоставляя
 * свежую версию SQLite3 с полной поддержкой WAL и расширений.
 */
@Database(
    entities = [
        CachedTrack::class,
        ListeningHistory::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun waveDao(): WaveDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private const val DB_NAME = "liquid_music_glass.db"

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DB_NAME
            )
                // Принудительно используем Requery SQLite вместо системного
                .openHelperFactory(RequerySQLiteOpenHelperFactory())
                // WAL режим — чтение и запись не блокируют друг друга
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .build()
        }

        fun destroyInstance() {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}
