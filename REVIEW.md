# LiquidMusicGlass — обзор репозитория

_Дата обзора: 2026-06-22 · ветка `claude/repository-review-x12i2y`_

## 1. Что это за проект

Android-музыкальный плеер на **Kotlin + Jetpack Compose** с фирменным
визуальным стилем «liquid glass». Воспроизводит музыку через партнёрский
стриминговый API **ICM** (`byicloud.online`) и имеет вторичную интеграцию с
**YouTube Music**.

| Параметр | Значение |
|----------|----------|
| `applicationId` | `com.liquidmusicglass` |
| `compileSdk` / `targetSdk` | 36 |
| `minSdk` | 29 (Android 10) |
| `versionName` | `2026.05.30 pre-release1 gsm` |
| Язык / UI | Kotlin, Jetpack Compose |
| Нативный код | C (JNI): `security.c`, `icmkey.c`, `lcm_urls.c` |
| Тесты | 16 файлов, ~101 метод `@Test` (unit + instrumented) |

## 2. Архитектура

```
app/src/main/kotlin/com/liquidmusicglass/
├── api/
│   ├── icm/          ← клиент партнёрского API ICM (основной источник музыки)
│   │   ├── IcmApi.kt            — HTTP-клиент (OkHttp), все эндпоинты
│   │   ├── IcmRepository.kt     — слой репозитория
│   │   ├── IcmAuthRepository.kt — хранение ключа/сессии
│   │   └── IcmModels.kt         — DTO/сериализация
│   └── youtube/      ← клиент YouTube Music (поиск/плеер)
├── engine/           ← ядро воспроизведения
│   ├── PlayerController, AudioService, EndlessPlaybackEngine
│   ├── MediaCacheManager, AudioDownloadManager, PlaylistDownloadService
│   ├── IcmKeyProvider.kt        — достаёт API-ключ из нативной .so
│   ├── SecurityUtils.kt         — проверки root/эмулятора
│   └── Appupdater.kt            — самообновление через APK из Downloads
├── ui/               ← Compose-экраны
│   ├── glass/, liquid/          — кастомные «стеклянные» компоненты
│   ├── player/, lyrics/, screens/, viewmodel/
└── camp/             ← FeatureAccessManager (управление доступом к фичам)

app/src/main/cpp/     ← нативный слой
├── security.c        — анти-отладка / анти-Frida / анти-Xposed / подпись
├── icmkey.c          — XOR-«шифрование» API-ключа и base URL
└── lcm_urls.c
```

Поток воспроизведения: поиск (`/search`) → получение stream-URL
(`POST /track`) → отдача в плеер. Есть «волна» (рекомендации,
`/library/wave/*`), импорт плейлистов, лайки, личный кабинет (`/me/*`).

## 3. Находки

Отсортировано по серьёзности. Пункты, отмеченные ✅, исправлены в этой ветке.

### 🔴 Критично

1. **API-ключ ICM закоммичен в открытом виде.**
   В `app/src/main/cpp/icmkey.c` ключ лежит в комментарии прямо над
   «зашифрованным» массивом:
   `pk_msng_SabChr8h0_NdXX-W1TlC9HcrgXF0_9T0MSMp4chk2EI`.
   XOR-«шифрование» использует константы (`BK0..BK7`), вшитые в тот же
   файл, поэтому ключ тривиально восстанавливается из `.so` —
   это security-through-obscurity, а сам ключ всё равно утёк в исходник.
   **Рекомендация:** считать ключ скомпрометированным и ротировать на
   стороне ICM; ключ передавать только через `local.properties`/CI-секрет
   (как уже сделано для `ICM_API_KEY` в `build.gradle.kts`), убрать
   plaintext из комментария.

2. ✅ **Захардкоженный пароль release-keystore.**
   В `app/build.gradle.kts` пароль подписи был fallback-значением
   (`St@skrasikov1`). **Исправлено:** пароли теперь читаются из переменных
   окружения или `local.properties`, без хардкода. CI-воркфлоу
   (`build-release.yml`) обновлён, чтобы пробрасывать `KEYSTORE_PASSWORD`,
   `KEY_PASSWORD`, `KEY_ALIAS`, `KEYSTORE_PATH` из GitHub Secrets.
   ⚠️ **Действие от вас:** добавьте эти секреты в настройках репозитория,
   иначе release-сборка в CI не подпишется. Пароль `St@skrasikov1` остаётся
   в истории git — желательно сменить пароль keystore.

### 🟠 Существенно

3. ✅ **Запись логов в публичное хранилище (утечка данных).**
   - `IcmApi.kt` дублировал тела ответов API (включая подписанные
     stream-URL и токены сессий) в `/storage/emulated/0/Download/icm_api_log.txt`.
   - `MainActivity.kt` писал результаты security-проверок в
     `/storage/emulated/0/Download/security_log.txt`.
   Это мир-читаемое расположение; явно временная диагностика OEM-проблем.
   **Исправлено:** обе записи удалены, остался только приватный лог в
   app-specific каталоге (`getExternalFilesDir`).

4. ✅ **Незавершённый merge-конфликт в `.gitignore`.**
   Файл содержал маркеры `<<<<<<< HEAD` / `=======` / `>>>>>>> incoming/main`,
   закоммиченные в репозиторий (git трактовал бы их как паттерны).
   **Исправлено:** конфликт разрешён в пользу Android-набора с добавлением
   `.env*`.

### 🟡 Стоит знать

5. **Нативная анти-тампер защита фактически отключена.**
   В `security.c`:
   - `nativeVerifySignature()` всегда возвращает `JNI_TRUE` (проверка
     подписи — заглушка);
   - `check_ptrace()` всегда возвращает `0`.
   Это сделано осознанно (коммит `a6c2dea`) ради совместимости с
   OEM-песочницами Honor/Xiaomi, но по факту слой проверки подписи сейчас
   не работает. Анти-Frida/Xposed/эмулятор остаются активны. Если защита
   нужна — стоит вернуть реальную проверку с белым списком проблемных OEM,
   а не глобально отключать.

6. **Самообновление через APK в Downloads.**
   `Appupdater.kt` скачивает и ставит APK из публичной папки Downloads с
   `REQUEST_INSTALL_PACKAGES`. Это рабочий паттерн для sideload-приложения,
   но убедитесь, что URL обновления берётся по HTTPS и проверяется подпись
   скачанного APK перед установкой.

7. **Эхо тел ответов в системный лог.**
   `IcmApiFileLogger.log(...)` пишет `bodyText.take(500)` в logcat на уровне
   `D`. В release-сборке стоит отключать (например, гейтить по
   `BuildConfig.DEBUG`), чтобы не светить данные в logcat.

## 4. Сильные стороны

- Аккуратно структурированный код: чёткое разделение `api` / `engine` / `ui`.
- Хорошее покрытие тестами клиента ICM (парсинг, нормализация длительности,
  репозитории, авторизация) — ~101 тест.
- В сетевом клиенте есть ретраи, пул соединений, обработка `Retry-After`
  и rate-limiting для «волны».
- Подробная документация API (`docs/`, `ICM_INTEGRATION_GUIDE.md`).
- ProGuard/R8 включён для release (`isMinifyEnabled = true`).

## 5. Рекомендованные следующие шаги

1. **Ротировать** API-ключ ICM и пароль release-keystore (оба утекли в
   историю git).
2. Добавить GitHub Secrets для подписи (см. п.2), иначе CI-release не
   подпишется после правок.
3. Убрать plaintext-ключ из комментария в `icmkey.c`.
4. Решить судьбу анти-тампер слоя (п.5): вернуть реальную проверку подписи
   или явно задокументировать, что она отключена намеренно.
5. Гейтить отладочное логирование по `BuildConfig.DEBUG`.

---

### Изменения, внесённые в этой ветке

| Файл | Изменение |
|------|-----------|
| `app/build.gradle.kts` | Убран захардкоженный пароль keystore; чтение из env/`local.properties` |
| `.github/workflows/build-release.yml` | Проброс секретов подписи в `assembleRelease` |
| `app/src/main/kotlin/.../api/icm/IcmApi.kt` | Удалена запись логов в публичный Downloads |
| `app/src/main/kotlin/.../MainActivity.kt` | Удалена запись security-логов в публичный Downloads |
| `.gitignore` | Разрешён закоммиченный merge-конфликт |
