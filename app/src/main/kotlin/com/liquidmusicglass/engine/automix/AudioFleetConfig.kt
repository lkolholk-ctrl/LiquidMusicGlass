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

    /** Прямая ссылка на quirks.json. Пусто = удалённая карта выключена.
     *  Gist «lkolholk-ctrl/quirks.json»; /raw без имени файла и ревизии —
     *  всегда последняя версия единственного файла гиста. */
    const val QUIRKS_URL =
        "https://gist.githubusercontent.com/lkolholk-ctrl/b679e26d8b6fe008b2d887513070ef4b/raw"

    /** Не чаще одного сетевого обновления карты в этот интервал. */
    const val QUIRKS_REFRESH_MS = 6 * 60 * 60 * 1000L   // 6 часов

    /** Эндпоинт отчётов (Google Form formResponse). Пусто = телеметрия выключена. */
    const val REPORT_URL =
        "https://docs.google.com/forms/d/e/1FAIpQLSckq8g2PgK4h0ccdy_Xj0lf_1ir4t1MMJFgqMmF-Ffreyre8w/formResponse"

    /** Логическое поле → имя параметра формы (entry.NNNNNNNN). */
    val REPORT_FIELDS: Map<String, String> = mapOf(
        "device" to "entry.712918050",    // MANUFACTURER/BRAND MODEL (DEVICE)
        "android" to "entry.580788740",   // версия Android + SDK
        "mode" to "entry.1090594903",     // рабочий режим выхода (0..6)
        "watchdog" to "entry.154011527",  // срабатываний watchdog за сессию
        "app" to "entry.571254747",       // versionName приложения
    )
}
