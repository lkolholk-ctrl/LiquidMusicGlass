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
 *   0 = NORMAL:    Shared    + LowLatency — дефолт. Обходит exclusive-MMAP,
 *                  оставаясь на быстром пути микшера (тот же маршрут, что у
 *                  обычного AudioTrack/ExoPlayer, который на этих девайсах играет).
 *   1 = SAFE:      Shared    + None       — полностью legacy-путь, максимальная
 *                  совместимость (для устройств, где кривой и fast-path).
 *   2 = EXCLUSIVE: Exclusive + LowLatency — стоковое поведение JUCE, оставлено
 *                  для A/B-сравнения при отладке.
 */
namespace automix
{
    /** Сохранить режим совместимости (клампится в 0..2). Возвращает true, если
     *  значение изменилось (вызывающий решает, переоткрывать ли устройство). */
    bool setOboeCompatMode (int mode);
    int  getOboeCompatMode();

    /** Человекочитаемый отчёт: текущий режим + последние открытые потоки
     *  (API/sharing/perf/format/rate/buffer/burst). Для DebugOverlay/логов. */
    std::string getAudioDiagnostics();
}
