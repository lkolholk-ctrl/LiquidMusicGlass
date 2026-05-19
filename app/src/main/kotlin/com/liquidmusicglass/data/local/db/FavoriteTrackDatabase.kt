package com.liquidmusicglass.data.local.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * SQLite-backed database for storing favorite tracks locally.
 * Uses raw SQLite instead of Room to avoid KSP/kapt annotation processor issues.
 */
class FavoriteTrackDatabase private constructor(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DB_NAME,
    null,
    DB_VERSION
) {

    private val _favoritesFlow = MutableStateFlow<List<FavoriteTrackEntity>>(emptyList())
    val favoritesFlow: Flow<List<FavoriteTrackEntity>> = _favoritesFlow

    private val _favoriteIdsFlow = MutableStateFlow<Set<String>>(emptySet())
    val favoriteIdsFlow: Flow<Set<String>> = _favoriteIdsFlow

    private val _favoriteStatusFlows = mutableMapOf<String, MutableStateFlow<Boolean>>()

    init {
        // Initial load
        reloadFavorites()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS favorite_tracks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                trackId TEXT NOT NULL UNIQUE,
                title TEXT NOT NULL,
                artistName TEXT,
                albumTitle TEXT,
                durationMs INTEGER DEFAULT 0,
                genre TEXT,
                imageUrl TEXT,
                streamUrl TEXT,
                artistId TEXT,
                collectionId TEXT,
                isExplicit INTEGER DEFAULT 0,
                source TEXT,
                likedAt INTEGER DEFAULT 0,
                isSynced INTEGER DEFAULT 0,
                pendingDelete INTEGER DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_track_id ON favorite_tracks(trackId)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS favorite_tracks")
        onCreate(db)
    }

    private fun reloadFavorites() {
        val list = readableDatabase.rawQuery(
            "SELECT * FROM favorite_tracks WHERE pendingDelete = 0 ORDER BY likedAt DESC",
            null
        ).use { cursor ->
            val result = mutableListOf<FavoriteTrackEntity>()
            while (cursor.moveToNext()) {
                result.add(cursorToEntity(cursor))
            }
            result
        }
        _favoritesFlow.value = list
        val ids = list.map { it.trackId }.toSet()
        _favoriteIdsFlow.value = ids
        // Update individual status flows
        _favoriteStatusFlows.forEach { (trackId, flow) ->
            flow.value = trackId in ids
        }
    }

    fun getAllFavorites(): List<FavoriteTrackEntity> {
        return readableDatabase.rawQuery(
            "SELECT * FROM favorite_tracks WHERE pendingDelete = 0 ORDER BY likedAt DESC",
            null
        ).use { cursor ->
            val result = mutableListOf<FavoriteTrackEntity>()
            while (cursor.moveToNext()) {
                result.add(cursorToEntity(cursor))
            }
            result
        }
    }

    fun getFavoriteTrackIds(): Set<String> {
        return readableDatabase.rawQuery(
            "SELECT trackId FROM favorite_tracks WHERE pendingDelete = 0",
            null
        ).use { cursor ->
            val result = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                result.add(cursor.getString(0))
            }
            result
        }
    }

    fun getByTrackId(trackId: String): FavoriteTrackEntity? {
        return readableDatabase.rawQuery(
            "SELECT * FROM favorite_tracks WHERE trackId = ? LIMIT 1",
            arrayOf(trackId)
        ).use { cursor ->
            if (cursor.moveToFirst()) cursorToEntity(cursor) else null
        }
    }

    fun isFavorite(trackId: String): Boolean {
        return readableDatabase.rawQuery(
            "SELECT 1 FROM favorite_tracks WHERE trackId = ? AND pendingDelete = 0 LIMIT 1",
            arrayOf(trackId)
        ).use { it.moveToFirst() }
    }

    fun isFavoriteFlow(trackId: String): Flow<Boolean> {
        return _favoriteStatusFlows.getOrPut(trackId) {
            MutableStateFlow(isFavorite(trackId))
        }
    }

    fun insert(entity: FavoriteTrackEntity) {
        val values = android.content.ContentValues().apply {
            put("trackId", entity.trackId)
            put("title", entity.title)
            put("artistName", entity.artistName)
            put("albumTitle", entity.albumTitle)
            put("durationMs", entity.durationMs)
            put("genre", entity.genre)
            put("imageUrl", entity.imageUrl)
            put("streamUrl", entity.streamUrl)
            put("artistId", entity.artistId)
            put("collectionId", entity.collectionId)
            put("isExplicit", if (entity.isExplicit) 1 else 0)
            put("source", entity.source)
            put("likedAt", entity.likedAt)
            put("isSynced", if (entity.isSynced) 1 else 0)
            put("pendingDelete", if (entity.pendingDelete) 1 else 0)
        }
        writableDatabase.insertWithOnConflict(
            "favorite_tracks", null, values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        reloadFavorites()
    }

    fun insertAll(entities: List<FavoriteTrackEntity>) {
        writableDatabase.beginTransaction()
        try {
            for (entity in entities) {
                insert(entity)
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun update(entity: FavoriteTrackEntity) {
        val values = android.content.ContentValues().apply {
            put("title", entity.title)
            put("artistName", entity.artistName)
            put("albumTitle", entity.albumTitle)
            put("durationMs", entity.durationMs)
            put("genre", entity.genre)
            put("imageUrl", entity.imageUrl)
            put("streamUrl", entity.streamUrl)
            put("artistId", entity.artistId)
            put("collectionId", entity.collectionId)
            put("isExplicit", if (entity.isExplicit) 1 else 0)
            put("source", entity.source)
            put("likedAt", entity.likedAt)
            put("isSynced", if (entity.isSynced) 1 else 0)
            put("pendingDelete", if (entity.pendingDelete) 1 else 0)
        }
        writableDatabase.update(
            "favorite_tracks", values, "trackId = ?",
            arrayOf(entity.trackId)
        )
        reloadFavorites()
    }

    fun deleteByTrackId(trackId: String) {
        writableDatabase.execSQL(
            "DELETE FROM favorite_tracks WHERE trackId = ?",
            arrayOf(trackId)
        )
        reloadFavorites()
    }

    fun clearAll() {
        writableDatabase.execSQL("DELETE FROM favorite_tracks")
        reloadFavorites()
    }

    fun getPendingInserts(): List<FavoriteTrackEntity> {
        return readableDatabase.rawQuery(
            "SELECT * FROM favorite_tracks WHERE isSynced = 0 AND pendingDelete = 0",
            null
        ).use { cursor ->
            val result = mutableListOf<FavoriteTrackEntity>()
            while (cursor.moveToNext()) {
                result.add(cursorToEntity(cursor))
            }
            result
        }
    }

    fun getPendingDeletes(): List<FavoriteTrackEntity> {
        return readableDatabase.rawQuery(
            "SELECT * FROM favorite_tracks WHERE pendingDelete = 1",
            null
        ).use { cursor ->
            val result = mutableListOf<FavoriteTrackEntity>()
            while (cursor.moveToNext()) {
                result.add(cursorToEntity(cursor))
            }
            result
        }
    }

    fun markSynced(trackId: String) {
        writableDatabase.execSQL(
            "UPDATE favorite_tracks SET isSynced = 1 WHERE trackId = ?",
            arrayOf(trackId)
        )
    }

    fun clearPendingDelete(trackId: String) {
        writableDatabase.execSQL(
            "UPDATE favorite_tracks SET pendingDelete = 0 WHERE trackId = ?",
            arrayOf(trackId)
        )
    }

    fun getCount(): Int {
        return readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM favorite_tracks WHERE pendingDelete = 0",
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun cursorToEntity(cursor: android.database.Cursor): FavoriteTrackEntity {
        return FavoriteTrackEntity(
            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
            trackId = cursor.getString(cursor.getColumnIndexOrThrow("trackId")),
            title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
            artistName = cursor.getString(cursor.getColumnIndexOrThrow("artistName")),
            albumTitle = cursor.getString(cursor.getColumnIndexOrThrow("albumTitle")),
            durationMs = cursor.getLong(cursor.getColumnIndexOrThrow("durationMs")),
            genre = cursor.getString(cursor.getColumnIndexOrThrow("genre")),
            imageUrl = cursor.getString(cursor.getColumnIndexOrThrow("imageUrl")),
            streamUrl = cursor.getString(cursor.getColumnIndexOrThrow("streamUrl")),
            artistId = cursor.getString(cursor.getColumnIndexOrThrow("artistId")),
            collectionId = cursor.getString(cursor.getColumnIndexOrThrow("collectionId")),
            isExplicit = cursor.getInt(cursor.getColumnIndexOrThrow("isExplicit")) == 1,
            source = cursor.getString(cursor.getColumnIndexOrThrow("source")),
            likedAt = cursor.getLong(cursor.getColumnIndexOrThrow("likedAt")),
            isSynced = cursor.getInt(cursor.getColumnIndexOrThrow("isSynced")) == 1,
            pendingDelete = cursor.getInt(cursor.getColumnIndexOrThrow("pendingDelete")) == 1
        )
    }

    companion object {
        private const val DB_NAME = "favorite_tracks.db"
        private const val DB_VERSION = 1

        @Volatile
        private var INSTANCE: FavoriteTrackDatabase? = null

        fun getInstance(context: Context): FavoriteTrackDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FavoriteTrackDatabase(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
}
