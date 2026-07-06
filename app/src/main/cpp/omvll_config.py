# omvll_config.py — конфиг обфускатора O-MVLL для liblmg_resolve.so (этап 2, #83).
#
# Применяется ТОЛЬКО к нативному резолверу плейлистов (resolve.cpp): весь этот
# .so — логика скрейпа Spotify/Яндекса, которую мы прячем от «любопытных».
# Подключается флагом clang -fpass-plugin=<omvll.so>; O-MVLL вызывает
# omvll_get_config() и спрашивает по каждой функции, что обфусцировать.
#
# Набор пассов — СТАБИЛЬНОЕ ядро O-MVLL (максимум из того, что реально
# проходит сборку под NDK r26d / clang 17), ВСЕ — только на функциях резолвера:
#   • obfuscate_string        — шифрование строковых литералов (2-й слой поверх
#                               нашего XOR-ключа RK);
#   • obfuscate_constants     — опаковые константы (в т.ч. наши XOR-массивы);
#   • flatten_cfg             — control-flow flattening;
#   • obfuscate_arithmetic    — MBA (mixed boolean-arithmetic).
#
# ВАЖНО про охват: resolve.cpp тянет header-only nlohmann/json (десятки тысяч
# строк/констант). Если гонять obfuscate_string/constants по ВСЕМУ модулю,
# O-MVLL шифрует каждую строку json.hpp через встроенный CPython — сборка
# native-части раздувается с ~2 мин до 20+ мин (проверено на CI). Нам это не
# нужно: секреты — в НАШИХ функциях (JNI-входы ResolveNative_* + инлайн
# хелперов при -Oz), json — просто парсер. Поэтому ВСЕ пассы скоупим на
# _is_resolver — и защита там, где надо, и сборка быстрая («не перегибаем»).
#
# ЯВНО ВЫКЛЮЧЕН anti_hooking. Он у O-MVLL включён ПО УМОЛЧАНИЮ: если метод
# не переопределить, база возвращает True, и пасс AntiHook::run для каждой
# функции лезет в JIT (jitAsm) собирать anti-hook пролог — на CI-хосте это
# падало segfault'ом (exit 139). В исходнике O-MVLL видно: jitAsm вызывается
# только внутри runOnFunction(), а туда попадают лишь функции, для которых
# antiHooking() вернул True. Значит достаточно вернуть False — и краш уходит,
# APK собирается с обфускацией. Просто «убрать» метод мало: срабатывал
# дефолт. (Другие тяжёлые пассы — indirect_call/branch, break_control_flow —
# по умолчанию OFF, поэтому их не трогаем.)
#
# Все пассы вешаем на функции резолвера (имя содержит "ResolveNative" —
# JNI-входы; статические хелперы инлайнятся в них при -Oz). Если какой-то
# пасс сломает резолвер в рантайме (импорт перестанет возвращать треки) —
# отключаем этот метод точечно.
#
# Безопасность СБОРКИ: smoke в CI остался как ДИАГНОСТИКА (печатает результат),
# но больше не гейтит — он давал ложный негатив (падал AntiHook на синтетике,
# хотя реальная сборка резолвера проходит). O-MVLL подключается к настоящему
# таргету lmg_resolve; если пасс сломает компайл — джоба честно покраснеет.

import omvll


# Тяжёлые пассы — только на функциях резолвера (JNI-входы ResolveNative_*).
def _is_resolver(func) -> bool:
    return "ResolveNative" in func.name


class ResolverConfig(omvll.ObfuscationConfig):
    def __init__(self):
        super().__init__()

    # ── Anti-hooking: ЯВНО OFF (дефолт True → segfault в JIT на CI) ──
    def anti_hooking(self, mod, func):
        return False

    # ── Строки/константы: ВЫКЛ. Проверено на реальной сборке — O-MVLL реально
    # обфусцирует resolve.cpp (краш AntiHook был только у синтетического smoke,
    # не у настоящего компайла). НО obfuscate_string/constants делают вызов во
    # встроенный CPython на КАЖДЫЙ строковый литерал, а при -Oz в функции
    # резолвера инлайнится весь nlohmann/json (десятки тысяч строк) → нативная
    # сборка пухнет до 30+ мин. Пользы мало: наши секреты и так лежат
    # XOR-массивами (этап 1), а не голыми литералами. Поэтому эти два пасса OFF.
    def obfuscate_string(self, mod, func, string):
        return False

    def obfuscate_constants(self, mod, func):
        return False

    # ── Логика резолвера: CFG-flattening + MBA. Чистый LLVM, без CPython —
    # быстро и это как раз главное: прячем АЛГОРИТМ скрейпа, а не строки. ──
    def flatten_cfg(self, mod, func):
        return _is_resolver(func)

    def obfuscate_arithmetic(self, mod, func):
        return _is_resolver(func)


def omvll_get_config() -> omvll.ObfuscationConfig:
    return ResolverConfig()
