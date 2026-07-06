# omvll_config.py — конфиг обфускатора O-MVLL для liblmg_resolve.so (этап 2, #83).
#
# Применяется ТОЛЬКО к нативному резолверу плейлистов (resolve.cpp): весь этот
# .so — логика скрейпа Spotify/Яндекса, которую мы прячем от «любопытных».
# Подключается флагом clang -fpass-plugin=<omvll.so>; O-MVLL вызывает
# omvll_get_config() и спрашивает по каждой функции, что обфусцировать.
#
# МАКСИМАЛЬНЫЙ набор пассов (пользователь выбрал «O-MVLL с максимальными
# пассами» вместо недоступной на этом стеке настоящей VM-виртуализации):
#   • obfuscate_string        — шифрование строковых литералов (2-й слой поверх
#                               нашего XOR-ключа RK) — во ВСЁМ модуле;
#   • obfuscate_constants     — опаковые константы — во всём модуле;
#   • flatten_cfg             — control-flow flattening на функциях резолвера;
#   • break_control_flow      — разрыв графа (jump-threading-стайл) там же;
#   • anti_hooking            — анти-хук (Frida/PLT) на JNI-входах;
#   • obfuscate_arithmetic    — MBA (mixed boolean-arithmetic) там же;
#   • indirect_call / branch  — косвенные переходы/вызовы там же.
#
# Тяжёлые пассы вешаем на функции резолвера (имя содержит "ResolveNative" —
# JNI-входы; статические хелперы инлайнятся в них при -Oz). Строки/константы —
# на весь модуль (дёшево и стабильно). Неизвестные методы O-MVLL просто не
# вызовет (для сборки безопасно). Если какой-то пасс сломает резолвер в рантайме
# (импорт перестанет возвращать треки) — отключаем этот метод точечно.
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

    # ── Строки и константы: весь модуль ──
    def obfuscate_string(self, mod, func, string):
        return True

    def obfuscate_constants(self, mod, func):
        return True

    # ── Тяжёлые пассы: функции резолвера ──
    def flatten_cfg(self, mod, func):
        return _is_resolver(func)

    def break_control_flow(self, mod, func):
        return _is_resolver(func)

    def anti_hooking(self, mod, func):
        return _is_resolver(func)

    def obfuscate_arithmetic(self, mod, func):
        return _is_resolver(func)

    def indirect_call(self, mod, func):
        return _is_resolver(func)

    def indirect_branch(self, mod, func):
        return _is_resolver(func)


def omvll_get_config() -> omvll.ObfuscationConfig:
    return ResolverConfig()
