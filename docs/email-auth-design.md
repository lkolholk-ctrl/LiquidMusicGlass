# Проект: регистрация/вход по Email + восстановление пароля (ICM)

Статус: спроектировано, ждёт серверную документацию (ключ `pk_` переезжает на
сервер — все email-эндпоинты S2S и идут ТОЛЬКО через него).

## Факты из доки ICM (byicloud.online/partners/api-docs)

1. **Входа по паролю у партнёров НЕТ.** Вход/регистрация — единый passwordless
   флоу по OTP-коду из письма; ICM сам создаёт аккаунт, если email новый
   (авто-регистрация). Пароль — атрибут ICM-аккаунта (выдаётся при
   авто-регистрации, уходит на почту, `password_issued=true` в ответе verify);
   партнёр может его только сменить или сбросить.
2. **Все 4 эндпоинта — S2S only** (`X-Partner-Key`): из приложения напрямую не
   вызываются, только через наш сервер-прокси.

| Шаг | Эндпоинт | Body | Ответ | Лимиты/ошибки |
|---|---|---|---|---|
| Запрос кода | `POST /api/partner/link/email/request` | `partner_user_id`, `email`, `state?` | `sent`, `nonce`, `expires_in` (~600-900с) | 1 запрос/мин на email → `429 rate_limited` (+Retry-After); `400 invalid_email` |
| Подтверждение | `POST /api/partner/link/email/verify` | `nonce`, `otp` (6 цифр) | `linked`, `icm_user_id`, `state` (эхо), `password_issued` | 5 попыток на nonce; `400 invalid_otp` (+`attempts_left`), `400 invalid_nonce`, `400 nonce_locked`, `403 nonce_belongs_to_another_partner` |
| Смена пароля | `POST /api/partner/link/email/password/change` | `partner_user_id`, `current_password`, `new_password` (мин 8) | `changed` | `404 user_not_linked`, `401 invalid_current_password`, `400 new_password_must_differ` |
| Сброс пароля | `POST /api/partner/link/email/password/reset` | `partner_user_id` | `reset` — временный пароль уходит на почту, старый гаснет | 60с/email → `429`; `404 user_not_linked`, `404 email_account_missing` |

## Пользовательские сценарии

- **Регистрация = Вход**: email → «Отправить код» → 6-значный код из письма →
  готово. Если email новый — ICM молча создаёт аккаунт (показываем нотис
  «Аккаунт создан, пароль отправлен на почту», если `password_issued=true`).
- **«Забыл пароль»**: пароль для входа В ПРИЛОЖЕНИЕ не нужен (вход по коду),
  поэтому сброс — сервисная функция ДЛЯ ЗАЛОГИНЕННОГО (reset требует
  слинкованный `partner_user_id`). Забыл пароль → войди по коду → Профиль →
  «Сбросить пароль» → временный пароль на почту.
- **Смена пароля**: Профиль → «Пароль ICM» → текущий + новый (+подтверждение).

## Архитектура (с учётом переезда ключа на сервер)

```
Приложение ──X-App-Key──▶ наш сервер ──X-Partner-Key──▶ byicloud.online
   /link/email/request        (подставляет ключ,          S2S-эндпоинты
   /link/email/verify          пробрасывает Retry-After)
   /password/change|reset
   /session/issue (уже есть в контракте прокси)
```

Пути под прокси не меняются (`/api/partner/...` как в icm-proxy-worker.js) —
серверная дока владельца определит финальный BASE_URL и заголовок app-ключа.

## Изменения в приложении

### IcmAuthRepository (новые методы; паролей НЕ храним нигде)

- `requestEmailOtp(email): Result<EmailOtpSession>` —
  `partner_user_id = ensurePartnerUserId()` (тот же стабильный id, что и для
  Telegram-линка), `state = UUID` (сверяем эхо в verify), email нормализуем
  (trim + lowercase). `EmailOtpSession(nonce, email, expiresAtMs)` — держим В
  ПАМЯТИ (не в prefs: короткоживущий секрет).
- `verifyEmailOtp(session, otp): Result<Unit>` — на `linked=true`:
  `KEY_EMAIL=email`, `KEY_AUTH_METHOD="email"`, `KEY_USER_ID` НЕ трогаем
  (тот же инвариант, что в setTelegramAuth — id, ушедший в link, менять
  нельзя) → выпуск session-токена через сервер → `fetchUserData()`.
- `changeIcmPassword(current, new): Result<Unit>`
- `resetIcmPassword(): Result<Unit>` — кулдаун 60с держим и на клиенте
  (кнопка с таймером), Retry-After уважаем.

Старый `loginWithEmail(email, apiKey)` (SHA-256 от email как partner_user_id,
БЕЗ подтверждения владения почтой) — **удалить**: это фейковый вход, почта не
проверялась; заменяется OTP-флоу. `generateUserIdFromEmail` — удалить с ним.

### UI

- **AuthScreen**: под «Continue with Telegram» — «Continue with Email»
  (та же карточная стилистика). Тап → поле email → «Отправить код».
- **EmailOtpSheet/Screen**: 6 ячеек кода; таймер повторной отправки 60с
  (rate limit); показываем `attempts_left` после неверного кода;
  `nonce_locked`/истёк → состояние «запросить новый код»; авто-сабмит на
  6-й цифре.
- **Профиль → «Пароль ICM»** (видно только при `auth_method == "email"` или
  при наличии email у профиля): «Сменить пароль» (3 поля, валидация ≥8 и
  «должен отличаться»), «Сбросить пароль» (подтверждающий диалог + кулдаун).
- Нотис после авто-регистрации (`password_issued=true`).

### Ошибки → IcmErrorMessages (человеческие тексты)

`invalid_email`, `invalid_otp` (+N попыток), `invalid_nonce` («код истёк —
запросите новый»), `nonce_locked`, `rate_limited` (+таймер из Retry-After),
`invalid_current_password`, `new_password_must_differ`, `email_account_missing`.

### Безопасность

- Паролей и OTP в prefs/логах НЕТ. `IcmApiFileLogger` обязан маскировать тела
  email-эндпоинтов (или не логировать их вовсе) — сейчас он эхает тела ответов
  в logcat, это уже в TODO (гейт по BuildConfig.DEBUG) — для email-флоу
  сделать безусловно.
- `state` сверяем (эхо в verify), email в UI показываем как ввели, шлём
  нормализованный.
- Конфликт привязок: если юзер раньше входил через Telegram с тем же
  `partner_user_id`, verify перепривяжет/дополнит аккаунт на стороне ICM —
  поведение сервера ICM; после logout prefs чистятся → новый `lg_` id →
  свежая привязка (ровно как сейчас у Telegram-флоу).

## Порядок реализации (после серверной доки)

1. Серверные маршруты-прокси для 4 эндпоинтов (+session/issue уже в плане).
2. IcmAuthRepository: 4 метода + удаление старого loginWithEmail/SHA-256-пути.
3. UI: кнопка Email на AuthScreen → OTP-шит → секция «Пароль ICM» в Профиле.
4. Маппинг ошибок + маскировка логов.
5. Ручная проверка матрицы: новый email (авто-рег + password_issued), повторный
   вход, неверный код ×5 → lock, истёкший nonce, rate limit 1/мин, смена
   пароля (все 3 ошибки), сброс (кулдаун), вход после сброса не ломается
   (вход паролем не пользуется).
