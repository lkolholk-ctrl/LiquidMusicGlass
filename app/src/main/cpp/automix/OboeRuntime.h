#pragma once

#include <string>

/**
 * Runtime-настройки Oboe-потока + он-девайс диагностика открытых потоков.
 *
 * JUCE жёстко открывал потоки в Exclusive+LowLatency (AAudio MMAP-exclusive) —
 * путь, сломанный на части прошивок (Xiaomi/MIUI: вместо звука белый шум).
 * cmake/PatchJuceOboeCompat.cmake правит juce_Oboe_android.cpp так, что sharing
 * и performance mode берутся из extern "C" хуков ниже, а каждое открытие потока
 * репортится сюда. Сам выбор режима хранится тут (атомик, любой поток).
 *
 * Режимы (значения совпадают с AutoMixNativeEngine.kt / AppSettings):
 *   0 = NORMAL:      Shared    + LowLatency + Float — ДЕФОЛТ. Быстрый путь микшера
 *                    без exclusive-MMAP (минимальное отклонение от стока JUCE).
 *   1 = SAFE:        Shared    + None       + Float — legacy-путь. Тумблер «Safe
 *                    Audio Output». ВНИМАНИЕ: на части устройств (Honor владельца)
 *                    None сам даёт тишину — поэтому НЕ дефолт.
 *   2 = EXCLUSIVE:   Exclusive + LowLatency + Float — сток JUCE, для A/B-отладки.
 *                    Часть HAL (vivo Y35) молча выдаёт Shared вместо Exclusive.
 *   3 = NORMAL_I16:  Shared    + LowLatency + int16 — для HAL, которые float-поток
 *                    «успешно» открывают, но портят в микшере (vivo: fast-путь →
 *                    шум, deep-путь → тишина). Обычные плееры шлют 16-бит — этот
 *                    режим повторяет их путь.
 *   4 = SAFE_I16:    Shared    + None       + int16 — комбинация 1 и 3.
 *   5 = OPENSLES_I16: OpenSL ES + None      + int16 — запасной БЭКЕНД: полностью
 *                    другой вход в систему, минуя AAudio целиком. Последний рубеж
 *                    для устройств, где кривой весь AAudio.
 */
namespace automix
{
    /** Сохранить режим совместимости (клампится в 0..5). Возвращает true, если
     *  значение изменилось (вызывающий решает, переоткрывать ли устройство). */
    bool setOboeCompatMode (int mode);
    int  getOboeCompatMode();

    /** Счётчик не-конечных сэмплов (NaN/Inf), вычищенных из выхода аудио-колбэка.
     *  Ненулевое значение в дампе = DSP-цепочка отравляется — отдельный баг. */
    void addNanScrubbed (int count);

    /** AudioTrack-sink активен (Java-выход вместо Oboe). Виден в дампе. */
    void setSinkActive (bool active);
    bool isSinkActive();

    /** Пульс аудио-колбэка: тик на каждый вызов (+ размер блока). Из дампа видно,
     *  крутится ли колбэк вообще. RT-safe (relaxed-атомики). */
    void noteAudioCallback (int numSamples);
    /** Число вызовов аудио-колбэка с запуска — для watchdog'а (замерший счётчик
     *  при «играем» = выход мёртв → автоэскалация режима). */
    long getCallbackCount();
    /** Уровень выхода движка: средний |сэмпл| последнего смикшированного блока.
     *  В дампе level>0 при «тишине из динамика» = звук портит система, не мы. */
    void noteAudioLevel (float meanAbs);

    /** Haptic Music: двухполосный детектор ударов. Кормится из аудио-колбэка
     *  (RT-safe, только audio thread пишет): [bassMeanAbs] — средний |сэмпл|
     *  блока после LP ~110 Гц (кик), [midMeanAbs] — полоса ~200..1800 Гц
     *  (снейр/клэп), [numSamples] — размер блока. Kotlin-опрос ~40 Гц читает
     *  огибающую и счётчики/силы ударов обеих полос (0..1). */
    void noteBassLevel (float bassMeanAbs, float midMeanAbs, int numSamples);
    float getHapticEnv();
    long getHapticBeatCount();
    float getHapticBeatStrength();
    long getHapticMidBeatCount();
    float getHapticMidBeatStrength();

    /** Человекочитаемый отчёт: текущий режим + формат декодера + последние
     *  открытые потоки (API/sharing/perf/format/rate/buffer/burst). */
    std::string getAudioDiagnostics();

    /** Фактический выходной формат MediaCodec-декодера (enc/rate/ch) — зовут
     *  MediaCodec-источники; строка попадает в getAudioDiagnostics() и logcat.
     *  Нужен для девайсов, где кодек отдаёт не 16-бит (vivo: 24/32-бит). */
    void setLastCodecInfo (const char* info);
}
