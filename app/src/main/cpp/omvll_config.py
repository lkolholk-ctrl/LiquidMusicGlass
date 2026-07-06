# omvll_config.py — конфиг обфускатора O-MVLL для liblmg_resolve.so (этап 2, #83).
#
# Применяется ТОЛЬКО к нативному резолверу плейлистов (resolve.cpp): весь этот
# .so — логика скрейпа Spotify/Яндекса, которую мы прячем от «любопытных».
# Подключается флагом clang -fpass-plugin=<omvll.so>; O-MVLL вызывает
# omvll_get_config() и спрашивает по каждой функции, что обфусцировать.
#
# Набор пассов — СТАБИЛЬНОЕ ядро O-MVLL (максимум из того, что реально
# проходит сборку под NDK r26d / clang 17):
#   • obfuscate_string        — шифрование строковых литералов (2-й слой поверх
#                               нашего XOR-ключа RK) — во ВСЁМ модуле;
#   • obfuscate_constants     — опаковые константы — во всём модуле;
#   • flatten_cfg             — control-flow flattening на функциях резолвера;
#   • obfuscate_arithmetic    — MBA (mixed boolean-arithmetic) там же.
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
# Тяжёлые пассы вешаем на функции резолвера (имя содержит "ResolveNative" —
# JNI-входы; статические хелперы инлайнятся в них при -Oz). Строки/константы —
# на весь модуль. Если какой-то пасс сломает резолвер в рантайме (импорт
# перестанет возвращать треки) — отключаем этот метод точечно.
#
# Безопасность СБОРКИ: плагин в CI сначала smoke-тестируется; при провале
# O-MVLL отключается и .so собирается как обычно (APK всегда зелёный).

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

    # ── Строки и константы: весь модуль ──
    def obfuscate_string(self, mod, func, string):
        return True

    def obfuscate_constants(self, mod, func):
        return True

    # ── Тяжёлые пассы: функции резолвера (стабильные) ──
    def flatten_cfg(self, mod, func):
        return _is_resolver(func)

    def obfuscate_arithmetic(self, mod, func):
        return _is_resolver(func)


def omvll_get_config() -> omvll.ObfuscationConfig:
    return ResolverConfig()
