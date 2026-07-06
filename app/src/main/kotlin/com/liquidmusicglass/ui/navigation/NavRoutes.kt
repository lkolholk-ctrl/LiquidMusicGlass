package com.liquidmusicglass.ui.navigation

/**
 * Маршруты навигации (батч 15). Каждая нижняя вкладка — это ВЛОЖЕННЫЙ граф со
 * своим стартовым экраном и своими экранами-деталями. За счёт вложенных графов
 * получаем ПЕР-ТАБ бэкстек: переключение вкладки сохраняет её стек и позицию, а
 * не откатывает на старт (цель #81).
 *
 * Общие детали (альбом/артист/плейлист) регистрируются ВНУТРИ каждого графа,
 * где они достижимы, поэтому маршрут префиксуется именем вкладки — иначе одни и
 * те же route столкнутся между графами. Хелперы ниже собирают такие route.
 */
object NavRoutes {

    // ── Графы вкладок (route самого графа) ──
    const val GRAPH_WAVE = "graph_wave"
    const val GRAPH_LIBRARY = "graph_library"
    const val GRAPH_NEW = "graph_new"
    const val GRAPH_SETTINGS = "graph_settings"

    // ── Стартовые экраны вкладок ──
    const val WAVE_HOME = "wave/home"
    const val LIBRARY_HOME = "library/home"
    const val NEW_HOME = "new/home"
    const val SETTINGS_HOME = "settings/home"

    // ── Экран поиска (достижим только с Волны) ──
    const val WAVE_SEARCH = "wave/search"

    // ── Аргументы ──
    const val ARG_ID = "id"
    const val ARG_NAME = "name"

    /** Роут детали альбома внутри вкладки [tab] (tab = "wave"/"library"/"new"). */
    fun albumRoute(tab: String) = "$tab/album/{$ARG_ID}"
    fun album(tab: String, id: String) = "$tab/album/$id"

    fun artistRoute(tab: String) = "$tab/artist/{$ARG_ID}"
    fun artist(tab: String, id: String) = "$tab/artist/$id"

    fun playlistRoute(tab: String) = "$tab/playlist/{$ARG_ID}"
    fun playlist(tab: String, id: String) = "$tab/playlist/$id"

    // ── Локальная медиатека (только внутри Библиотеки) ──
    const val LOCAL_LIBRARY = "library/local"
    const val LOCAL_ARTIST_ROUTE = "library/local/artist/{$ARG_NAME}"
    fun localArtist(name: String) = "library/local/artist/$name"
    const val LOCAL_ALBUM_ROUTE = "library/local/album/{$ARG_ID}/{$ARG_NAME}"
    fun localAlbum(id: Long, name: String) = "library/local/album/$id/$name"

    /** Короткий тег вкладки для построения префиксных route. */
    const val TAB_WAVE = "wave"
    const val TAB_LIBRARY = "library"
    const val TAB_NEW = "new"

    /** По любому текущему route определить, какому графу-вкладке он принадлежит —
     *  для подсветки бара и «вкладочной» логики (дым Волны, мини-плеер). */
    fun graphOf(route: String?): String = when {
        route == null -> GRAPH_WAVE
        route.startsWith("wave") -> GRAPH_WAVE
        route.startsWith("library") -> GRAPH_LIBRARY
        route.startsWith("new") -> GRAPH_NEW
        route.startsWith("settings") -> GRAPH_SETTINGS
        else -> GRAPH_WAVE
    }

    /** Является ли текущий route стартовым экраном своей вкладки (деталей нет
     *  сверху) — нужно, чтобы понимать, показывать ли тяжёлый дым Волны. */
    fun isTabHome(route: String?): Boolean =
        route == WAVE_HOME || route == LIBRARY_HOME || route == NEW_HOME || route == SETTINGS_HOME
}
