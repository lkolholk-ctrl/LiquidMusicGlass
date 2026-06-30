package com.liquidmusicglass.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
        ListeningHistory::class,
        TrackStatsEntity::class,
        PlaybackHistoryEntity::class,
        ListenHistoryEntity::class,
        LocalTrackEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun waveDao(): WaveDao
    abstract fun playbackHistoryDao(): PlaybackHistoryDao
    abstract fun listenHistoryDao(): ListenHistoryDao
    abstract fun localTracksDao(): LocalTracksDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private const val DB_NAME = "liquid_music_glass.db"

        // v3→v4: ДОБАВЛЯЕМ таблицу local_tracks без сноса остальных (история и т.п.
        // сохраняются — НЕ destructive). SQL даёт ту же схему, что генерит Room.
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `local_tracks` (" +
                        "`id` TEXT NOT NULL, `mediaStoreId` INTEGER NOT NULL, `title` TEXT NOT NULL, " +
                        "`artist` TEXT NOT NULL, `albumName` TEXT NOT NULL, `albumId` INTEGER NOT NULL, " +
                        "`durationMs` INTEGER NOT NULL, `trackNumber` INTEGER NOT NULL, `year` INTEGER NOT NULL, " +
                        "`dateAddedSec` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_tracks_artist` ON `local_tracks` (`artist`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_tracks_albumId` ON `local_tracks` (`albumId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_tracks_title` ON `local_tracks` (`title`)")
            }
        }

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
                .openHelperFactory(RequerySQLiteOpenHelperFactory())
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_3_4)
                .fallbackToDestructiveMigration()
                .build()
        }

        fun destroyInstance() {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}
