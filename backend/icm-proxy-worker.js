/**
 * LiquidMusicGlass — ICM Partner API proxy (Cloudflare Worker).
 *
 * Задача: убрать партнёрский ключ `pk_` из APK. Ключ живёт СЕКРЕТОМ Cloudflare
 * (`ICM_PARTNER_KEY`), а приложение ходит на этот воркер вообще без ключа.
 * Воркер сам подставляет `X-Partner-Key` в исходящий запрос к byicloud.online.
 *
 * Поток:
 *   APK → https://lmg-api.<домен>/api/partner/<endpoint>   (без pk_, с X-App-Key)
 *       → воркер добавляет X-Partner-Key из секрета
 *       → https://byicloud.online/api/partner/<endpoint>
 *       → ответ ICM пробрасывается назад как есть
 *
 * ПОЧЕМУ ЭТО ЛУЧШЕ, ЧЕМ pk_ В APK, ЕСЛИ X-App-Key ВСЁ РАВНО В APK:
 *   X-App-Key тоже едет в бинаре и извлекаем — но, в отличие от pk_, он твой,
 *   не партнёрский: ротируется ОДНОЙ командой (wrangler secret put) без участия
 *   ICM, не даёт прямого доступа к партнёрскому API, и его можно гасить своими
 *   средствами (WAF/rate-limit/бан IP в дашборде CF). pk_ же — мастер-доступ
 *   партнёра; его утечка бьёт по VVS и требует его перевыпуска. Воркер переносит
 *   контроль над злоупотреблением на ТВОЮ сторону — в этом весь смысл.
 *
 * СЕКРЕТЫ (задать через wrangler, НЕ в код и НЕ в репо):
 *   wrangler secret put ICM_PARTNER_KEY   # pk_...  (сам ключ ICM)
 *   wrangler secret put APP_ACCESS_KEY    # общий токен приложения (любая длинная строка)
 *
 * ДЕПЛОЙ:
 *   1. cd backend && wrangler deploy --config wrangler-icm-proxy.toml
 *   2. привязать custom domain (напр. lmg-api.<домен>) — Workers → Settings →
 *      Domains & Routes → Add Custom Domain. SSL выпишется автоматически.
 *   3. в дашборде добавить Rate Limiting rule на маршрут (напр. 20 req/10s на IP)
 *      и, по желанию, WAF-правило «блокировать без валидного X-App-Key».
 *   4. в клиенте: BASE_URL → https://lmg-api.<домен>/api/partner, убрать pk_ из
 *      icmkey.c, слать X-App-Key вместо X-Partner-Key (см. блок в конце файла).
 *
 * Совместимость с существующим telegram-redirect-worker.js: это ОТДЕЛЬНЫЙ воркер,
 * они не пересекаются.
 */

// Куда проксируем. Партнёрский префикс — единственный разрешённый путь: воркер
// не должен работать как открытый прокси на произвольные хосты/пути.
const UPSTREAM_ORIGIN = "https://byicloud.online";
const ALLOWED_PREFIX = "/api/partner/";

// Методы, которые вообще имеет смысл проксировать в партнёрское API.
const ALLOWED_METHODS = new Set(["GET", "POST", "PUT", "DELETE", "PATCH"]);

// Заголовки от клиента, которые пробрасываем наверх. Всё остальное отбрасываем —
// в частности, клиентский X-Partner-Key игнорируется: ключ берётся ТОЛЬКО из
// секрета, подсунуть свой снаружи нельзя.
const FORWARD_REQ_HEADERS = [
  "authorization",        // Bearer <session-token> для user-scoped эндпоинтов
  "x-partner-user-id",    // аналитика/пер-юзер настройки
  "x-lmg-fast",           // быстрый путь (короткий таймаут на стороне клиента)
  "content-type",
  "accept",
  "user-agent",
  "x-request-id",         // если клиент задаёт свой trace id
];

// Заголовки ответа ICM, которые важно донести до клиента.
const FORWARD_RESP_HEADERS = [
  "content-type",
  "x-request-id",         // rid для матчинга в саппорте ICM
  "retry-after",          // клиент чтит backoff на 429/502
  "cache-control",
];

/** Сравнение секрета в ~постоянное время (сетевой джиттер и так доминирует). */
function safeEqual(a, b) {
  if (typeof a !== "string" || typeof b !== "string") return false;
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

function json(status, obj) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "content-type": "application/json" },
  });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    // 1) Метод из белого списка.
    if (!ALLOWED_METHODS.has(request.method)) {
      return json(405, { error: "method_not_allowed" });
    }

    // 2) Только партнёрский префикс — не открытый прокси.
    if (!url.pathname.startsWith(ALLOWED_PREFIX)) {
      return json(404, { error: "not_found" });
    }

    // 3) Барьер приложения. Секрет обязателен на стороне воркера; без него
    //    считаем прокси неправильно сконфигуренным и ничего не проксируем.
    if (!env.APP_ACCESS_KEY || !env.ICM_PARTNER_KEY) {
      return json(500, { error: "proxy_misconfigured" });
    }
    const appKey = request.headers.get("x-app-key");
    if (!appKey || !safeEqual(appKey, env.APP_ACCESS_KEY)) {
      return json(401, { error: "app_key_invalid" });
    }

    // 4) Собираем исходящий запрос: тот же путь+query, наш ключ, безопасный
    //    набор заголовков клиента.
    const upstreamUrl = UPSTREAM_ORIGIN + url.pathname + url.search;

    const headers = new Headers();
    for (const name of FORWARD_REQ_HEADERS) {
      const v = request.headers.get(name);
      if (v != null) headers.set(name, v);
    }
    // Ключ — только из секрета. Любой клиентский X-Partner-Key проигнорирован
    // (мы его не копировали) и здесь перезаписан доверенным.
    headers.set("X-Partner-Key", env.ICM_PARTNER_KEY);

    // Тело: тела запросов к ICM крошечные (JSON на пару полей), читаем целиком —
    // без стриминга/duplex, так устойчивее.
    const hasBody = request.method !== "GET" && request.method !== "DELETE";
    const init = {
      method: request.method,
      headers,
      body: hasBody ? await request.arrayBuffer() : undefined,
      redirect: "manual",
    };

    let upstreamResp;
    try {
      upstreamResp = await fetch(upstreamUrl, init);
    } catch (e) {
      // Апстрим недоступен/таймаут — отдаём 502, клиент уже умеет ретраить.
      return json(502, { error: "upstream_unreachable" });
    }

    // 5) Пробрасываем ответ: статус, тело (стримом), важные заголовки.
    const outHeaders = new Headers();
    for (const name of FORWARD_RESP_HEADERS) {
      const v = upstreamResp.headers.get(name);
      if (v != null) outHeaders.set(name, v);
    }
    return new Response(upstreamResp.body, {
      status: upstreamResp.status,
      headers: outHeaders,
    });
  },
};

/* ═══════════════════════════════════════════════════════════════════════════
   КОНТРАКТ КЛИЕНТА (что поменять в приложении при переходе на воркер)

   Файлы: IcmApi.kt (BASE_URL, buildRequest), icmkey.c (убрать pk_).

   1. BASE_URL:
        "https://byicloud.online/api/partner"
      →  "https://lmg-api.<домен>/api/partner"
      Пути и query не меняются — воркер сохраняет /api/partner/<...>?<...>.

   2. buildRequest():
      - УБРАТЬ отправку X-Partner-Key (клиент его больше не знает).
      - ДОБАВИТЬ заголовок  X-App-Key: <APP_ACCESS_KEY>.
      - Bearer <session>, X-Partner-User-Id, X-LMG-Fast, тело — БЕЗ изменений,
        воркер их пробрасывает.

   3. icmkey.c:
      - Обфусцированным значением сделать APP_ACCESS_KEY вместо pk_ (тот же
        механизм). pk_ из клиента исчезает полностью — извлекать нечего.
      - ENC_URL → базовый URL воркера (не секрет, но пусть лежит там же).

   Итог: pk_ не попадает в APK. Утечка X-App-Key чинится тобой в одиночку
   (wrangler secret put APP_ACCESS_KEY + перевыпуск сборки), без участия ICM.
   ═══════════════════════════════════════════════════════════════════════════ */
