/**
 * LiquidMusicGlass — Telegram auth redirect bridge (Cloudflare Worker).
 *
 * Замена server.js с liquid.glassfiles.ru. Задача — единственная: принять
 * https-редирект от ICM после входа через Telegram и перебросить пользователя
 * обратно в приложение по кастомной схеме (deep link). Состояния нет, токенов
 * нет — приложение выпускает session-токен само.
 *
 * Поток:
 *   ICM → GET https://<этот-домен>/auth/telegram?linked=1&icm_user_id=..&state=..
 *       → 302 Location: liquidmusicglass://oauth/icm?linked=1&icm_user_id=..&state=..
 *
 * Контракт параметров задан приложением:
 *   - AuthScreen.kt      строит redirect_uri = https://<домен>/auth/telegram
 *   - MainActivity.kt    ждёт liquidmusicglass://oauth/icm с linked/icm_user_id/state/error
 *
 * ВАЖНО: домен этого воркера ICM должен добавить в белый список redirect_uri,
 * иначе ICM сюда не редиректит. Запросить у менеджера ICM.
 *
 * Деплой:
 *   1. npm i -g wrangler && wrangler login
 *   2. wrangler deploy   (см. wrangler.toml рядом)
 *   3. привязать свой домен в дашборде Cloudflare (Workers Routes)
 *   4. обновить redirect_uri в AuthScreen.kt на новый домен
 */

const APP_SCHEME = "liquidmusicglass://oauth/icm";
// Параметры, которые пробрасываем как есть (только известные — не тащим мусор).
const PASSTHROUGH = ["linked", "icm_user_id", "state", "error"];

export default {
  async fetch(request) {
    const url = new URL(request.url);

    // Принимаем только путь моста; всё остальное — 404 (не открытый прокси).
    if (url.pathname !== "/auth/telegram") {
      return new Response("Not found", { status: 404 });
    }

    const out = new URLSearchParams();
    for (const key of PASSTHROUGH) {
      const v = url.searchParams.get(key);
      if (v !== null) out.set(key, v);
    }

    const deepLink = `${APP_SCHEME}?${out.toString()}`;

    // Мгновенный редирект в приложение + запасная кнопка, если авто-переход
    // заблокирован браузером (некоторые Custom Tabs требуют жеста юзера).
    const html = `<!doctype html>
<html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="refresh" content="0;url=${escapeHtml(deepLink)}">
<title>LiquidMusicGlass</title>
<style>
  body{margin:0;height:100vh;display:flex;align-items:center;justify-content:center;
       background:#0d0d0f;color:#eee;font-family:system-ui,sans-serif;text-align:center}
  a{display:inline-block;margin-top:16px;padding:12px 24px;border-radius:24px;
    background:#7FB77E;color:#000;text-decoration:none;font-weight:600}
</style></head>
<body><div>
  <p>Возвращаемся в приложение…</p>
  <a href="${escapeHtml(deepLink)}">Открыть LiquidMusicGlass</a>
</div>
<script>location.replace(${JSON.stringify(deepLink)});</script>
</body></html>`;

    return new Response(html, {
      status: 200,
      headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" },
    });
  },
};

function escapeHtml(s) {
  return s.replace(/[&<>"']/g, (c) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}
