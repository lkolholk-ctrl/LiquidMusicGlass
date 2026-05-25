package com.liquidmusicglass.data.local.db

/**
 * Результат аналитического запроса: жанр + количество прослушиваний.
 * Используется для формирования белого списка жанров "Моей волны".
 */
data class GenreCount(
    val genre: String,
    val count: Int
)
