package com.liquidmusicglass.data.local.db

/**
 * Wrapper class for Room queries that return a single column (trackId).
 * Room KSP has issues with primitive types in query results when entities
 * use autoGenerate primary keys. This wrapper avoids the "unexpected jvm signature V" error.
 */
data class TrackIdWrapper(
    val trackId: String
)
