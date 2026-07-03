package com.liquidmusicglass.engine.automix

/**
 * Конфигурация «флотской» адаптации аудио (этап 2 телеметрии).
 *
 * Обе части выключены, пока URL пустые — приложение работает как раньше
 * (встроенная таблица AudioQuirks + watchdog). Заполняются один раз:
 *
 *  • [QUIRKS_URL] — прямая ссылка на публичный quirks.json (raw GitHub /
 *    gist / любой статический хостинг). Формат:
 *      { "version": 1,
 *        "models":   { "v2205": 6 },
 *        "families": { "vivo": 6, "xiaomi": 6 } }
 *    Ключи — подстроки (lowercase) MODEL+DEVICE / MANUFACTURER+BRAND,
 *    значения — режимы 0..6 (см. OboeRuntime.h). Удалённые правила сильнее
 *    встроенных: правка файла чинит вендора у всех без релиза приложения.
 *
 *  • [REPORT_URL] + [REPORT_FIELDS] — приём анонимных отчётов. Заточено под
 *    Google Form (бесплатно, без сервера): REPORT_URL — .../formResponse,
 *    REPORT_FIELDS — соответствие логических полей entry-идентификаторам
 *    формы. Подходит и любой другой эндпоинт, принимающий form-urlencoded.
 */
object AudioFleetConfig {

    /** Прямая ссылка на quirks.json. Пусто = удалённая карта выключена. */
    const val QUIRKS_URL = ""

    /** Не чаще одного сетевого обновления карты в этот интервал. */
    const val QUIRKS_REFRESH_MS = 6 * 60 * 60 * 1000L   // 6 часов

    /** Эндпоинт отчётов (Google Form formResponse). Пусто = телеметрия выключена. */
    const val REPORT_URL = ""

    /** Логическое поле → имя параметра формы (entry.NNNNNNNN). */
    val REPORT_FIELDS: Map<String, String> = mapOf(
        "device" to "",      // entry.… — MANUFACTURER/BRAND MODEL (DEVICE)
        "android" to "",     // entry.… — версия Android + SDK
        "mode" to "",        // entry.… — рабочий режим выхода (0..6)
        "watchdog" to "",    // entry.… — срабатываний watchdog за сессию
        "app" to "",         // entry.… — versionName приложения
    )
}
