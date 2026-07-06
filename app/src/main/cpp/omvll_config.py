# omvll_config.py — конфиг обфускатора O-MVLL для liblmg_resolve.so (этап 2, #83).
#
# Применяется ТОЛЬКО к нативному резолверу плейлистов (resolve.cpp): весь этот
# .so — логика скрейпа Spotify/Яндекса, которую мы прячем от «любопытных».
# Подключается флагом clang -fpass-plugin=<omvll.so>; O-MVLL вызывает
# omvll_get_config() и спрашивает по каждой функции, что обфусцировать.
#
# Слои защиты здесь:
#   • obfuscate_string — шифрование строковых литералов (второй слой ПОВЕРХ
#     нашего XOR-ключа RK; это и есть «O-MVLL с xor key»). Дёшево и стабильно.
#   • obfuscate_constants — опаковые константы (маскируем числовые литералы).
#   • flatten_cfg — control-flow flattening на JNI-входах резолвера
#     (Java_..._ResolveNative_*): граф исполнения становится нечитаемым.
#
# Безопасность сборки: плагин в CI сначала проходит smoke-тест на тривиальном
# файле; если он падает — O-MVLL отключается и .so собирается как обычно, APK
# всегда собирается (см. шаг «Prepare O-MVLL» в ci.yml).

import omvll


class ResolverConfig(omvll.ObfuscationConfig):
    def __init__(self):
        super().__init__()

    # Строки шифруем во ВСЕХ функциях модуля (эндпоинты/UA/маркеры и так уже
    # XOR-зашифрованы у нас — это добавляет второй независимый слой).
    def obfuscate_string(self, mod, func, string):
        return True

    # Опаковые константы — тоже во всём модуле, риск минимальный.
    def obfuscate_constants(self, mod, func):
        return True

    # Плоский граф — на JNI-входах резолвера (основная точка интереса в jadx →
    # native). Имена JNI: Java_com_liquidmusicglass_security_ResolveNative_*.
    def flatten_cfg(self, mod, func):
        return "ResolveNative" in func.name


def omvll_get_config() -> omvll.ObfuscationConfig:
    return ResolverConfig()
