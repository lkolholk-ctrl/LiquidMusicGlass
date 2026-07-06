// resolve.cpp — нативные резолверы плейлистов (батч 16, защита #82).
//
// Смысл: убрать логику скрейпа из Kotlin (jadx декомпилит его почти в исходник)
// в нативный .so. Секретные строки (эндпоинты, User-Agent, маркеры JSON) лежат
// XOR-зашифрованными и расшифровываются в RAM только на момент использования
// (как в lcm_urls.c / icmkey.c). HTTP делает Kotlin (тонкий fetch), сюда
// приходит только тело; вся логика (построение URL, парсинг) — здесь.
//
// Этап 1 — Spotify (один запрос: embed-страница → "trackList":[{title,subtitle}]).
// Парсим через nlohmann/json, чтобы корректно разэкранировать \uXXXX (кириллица,
// эмодзи). HTML принимаем и результат отдаём как UTF-8 byte[] — минуя modified-
// UTF-8 ловушки JNI NewStringUTF на суррогатных парах.

#include <jni.h>
#include <string>
#include <vector>
#include <cstdlib>
#include <nlohmann/json.hpp>

using json = nlohmann::json;

// ── XOR-ключ и зашифрованные строки (сгенерены оффлайн) ──
static const unsigned char RK[8] = { 0x3F,0xA6,0x1D,0xC8,0x74,0x92,0xEB,0x50 };

// "https://open.spotify.com/embed/playlist/"
static const unsigned char E_SPOTIFY_EMBED[] = { 0x57, 0xD2, 0x69, 0xB8, 0x07, 0xA8, 0xC4, 0x7F, 0x50, 0xD6, 0x78, 0xA6, 0x5A, 0xE1, 0x9B, 0x3F, 0x4B, 0xCF, 0x7B, 0xB1, 0x5A, 0xF1, 0x84, 0x3D, 0x10, 0xC3, 0x70, 0xAA, 0x11, 0xF6, 0xC4, 0x20, 0x53, 0xC7, 0x64, 0xA4, 0x1D, 0xE1, 0x9F, 0x7F, 0x00 };
// User-Agent (Chrome desktop)
static const unsigned char E_UA[] = { 0x72, 0xC9, 0x67, 0xA1, 0x18, 0xFE, 0x8A, 0x7F, 0x0A, 0x88, 0x2D, 0xE8, 0x5C, 0xC5, 0x82, 0x3E, 0x5B, 0xC9, 0x6A, 0xBB, 0x54, 0xDC, 0xBF, 0x70, 0x0E, 0x96, 0x33, 0xF8, 0x4F, 0xB2, 0xBC, 0x39, 0x51, 0x90, 0x29, 0xF3, 0x54, 0xEA, 0xDD, 0x64, 0x16, 0x86, 0x5C, 0xB8, 0x04, 0xFE, 0x8E, 0x07, 0x5A, 0xC4, 0x56, 0xA1, 0x00, 0xBD, 0xDE, 0x63, 0x08, 0x88, 0x2E, 0xFE, 0x54, 0xBA, 0xA0, 0x18, 0x6B, 0xEB, 0x51, 0xE4, 0x54, 0xFE, 0x82, 0x3B, 0x5A, 0x86, 0x5A, 0xAD, 0x17, 0xF9, 0x84, 0x79, 0x1F, 0xE5, 0x75, 0xBA, 0x1B, 0xFF, 0x8E, 0x7F, 0x0E, 0x94, 0x2B, 0xE6, 0x44, 0xBC, 0xDB, 0x7E, 0x0F, 0x86, 0x4E, 0xA9, 0x12, 0xF3, 0x99, 0x39, 0x10, 0x93, 0x2E, 0xFF, 0x5A, 0xA1, 0xDD, 0x00 };
// "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
static const unsigned char E_ACCEPT[] = { 0x4B, 0xC3, 0x65, 0xBC, 0x5B, 0xFA, 0x9F, 0x3D, 0x53, 0x8A, 0x7C, 0xB8, 0x04, 0xFE, 0x82, 0x33, 0x5E, 0xD2, 0x74, 0xA7, 0x1A, 0xBD, 0x93, 0x38, 0x4B, 0xCB, 0x71, 0xE3, 0x0C, 0xFF, 0x87, 0x7C, 0x5E, 0xD6, 0x6D, 0xA4, 0x1D, 0xF1, 0x8A, 0x24, 0x56, 0xC9, 0x73, 0xE7, 0x0C, 0xFF, 0x87, 0x6B, 0x4E, 0x9B, 0x2D, 0xE6, 0x4D, 0xBE, 0xC1, 0x7F, 0x15, 0x9D, 0x6C, 0xF5, 0x44, 0xBC, 0xD3, 0x00 };
// "en-US,en;q=0.9"
static const unsigned char E_ACCEPT_LANG[] = { 0x5A, 0xC8, 0x30, 0x9D, 0x27, 0xBE, 0x8E, 0x3E, 0x04, 0xD7, 0x20, 0xF8, 0x5A, 0xAB, 0x00 };
// "\"trackList\":"
static const unsigned char E_MARK_TRACKLIST[] = { 0x1D, 0xD2, 0x6F, 0xA9, 0x17, 0xF9, 0xA7, 0x39, 0x4C, 0xD2, 0x3F, 0xF2, 0x00 };
// "title"
static const unsigned char E_F_TITLE[] = { 0x4B, 0xCF, 0x69, 0xA4, 0x11, 0x00 };
// "subtitle"
static const unsigned char E_F_SUBTITLE[] = { 0x4C, 0xD3, 0x7F, 0xBC, 0x1D, 0xE6, 0x87, 0x35, 0x00 };

// ── Яндекс Музыка (батч 16, вторая половина #82) ──
// UA переиспользуем из Spotify (E_UA — тот же десктоп-Chrome). Ниже — только
// специфика Яндекса: ru-локаль (без неё 403/451), эндпоинт официального API,
// маркеры HTML/JSON. Всё XOR-зашифровано тем же ключом RK.
// "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
static const unsigned char E_Y_ACCEPT[] = { 0x4B, 0xC3, 0x65, 0xBC, 0x5B, 0xFA, 0x9F, 0x3D, 0x53, 0x8A, 0x7C, 0xB8, 0x04, 0xFE, 0x82, 0x33, 0x5E, 0xD2, 0x74, 0xA7, 0x1A, 0xBD, 0x93, 0x38, 0x4B, 0xCB, 0x71, 0xE3, 0x0C, 0xFF, 0x87, 0x7C, 0x5E, 0xD6, 0x6D, 0xA4, 0x1D, 0xF1, 0x8A, 0x24, 0x56, 0xC9, 0x73, 0xE7, 0x0C, 0xFF, 0x87, 0x6B, 0x4E, 0x9B, 0x2D, 0xE6, 0x4D, 0xBE, 0x82, 0x3D, 0x5E, 0xC1, 0x78, 0xE7, 0x15, 0xE4, 0x82, 0x36, 0x13, 0xCF, 0x70, 0xA9, 0x13, 0xF7, 0xC4, 0x27, 0x5A, 0xC4, 0x6D, 0xE4, 0x1D, 0xFF, 0x8A, 0x37, 0x5A, 0x89, 0x7C, 0xB8, 0x1A, 0xF5, 0xC7, 0x7A, 0x10, 0x8C, 0x26, 0xB9, 0x49, 0xA2, 0xC5, 0x68, 0x00 };
// "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7"
static const unsigned char E_Y_ACCEPT_LANG[] = { 0x4D, 0xD3, 0x30, 0x9A, 0x21, 0xBE, 0x99, 0x25, 0x04, 0xD7, 0x20, 0xF8, 0x5A, 0xAB, 0xC7, 0x35, 0x51, 0x8B, 0x48, 0x9B, 0x4F, 0xE3, 0xD6, 0x60, 0x11, 0x9E, 0x31, 0xAD, 0x1A, 0xA9, 0x9A, 0x6D, 0x0F, 0x88, 0x2A, 0x00 };
// "https://music.yandex.ru/"
static const unsigned char E_Y_REFERER[] = { 0x57, 0xD2, 0x69, 0xB8, 0x07, 0xA8, 0xC4, 0x7F, 0x52, 0xD3, 0x6E, 0xA1, 0x17, 0xBC, 0x92, 0x31, 0x51, 0xC2, 0x78, 0xB0, 0x5A, 0xE0, 0x9E, 0x7F, 0x00 };
// "application/json"
static const unsigned char E_Y_ACCEPT_JSON[] = { 0x5E, 0xD6, 0x6D, 0xA4, 0x1D, 0xF1, 0x8A, 0x24, 0x56, 0xC9, 0x73, 0xE7, 0x1E, 0xE1, 0x84, 0x3E, 0x00 };
// "https://api.music.yandex.net/users/"
static const unsigned char E_Y_API_USERS[] = { 0x57, 0xD2, 0x69, 0xB8, 0x07, 0xA8, 0xC4, 0x7F, 0x5E, 0xD6, 0x74, 0xE6, 0x19, 0xE7, 0x98, 0x39, 0x5C, 0x88, 0x64, 0xA9, 0x1A, 0xF6, 0x8E, 0x28, 0x11, 0xC8, 0x78, 0xBC, 0x5B, 0xE7, 0x98, 0x35, 0x4D, 0xD5, 0x32, 0x00 };
// "/playlists/"
static const unsigned char E_Y_API_PLAYLISTS[] = { 0x10, 0xD6, 0x71, 0xA9, 0x0D, 0xFE, 0x82, 0x23, 0x4B, 0xD5, 0x32, 0x00 };
// "uid"
static const unsigned char E_Y_UID[] = { 0x4A, 0xCF, 0x79, 0x00 };
// "kind"
static const unsigned char E_Y_KIND[] = { 0x54, 0xCF, 0x73, 0xAC, 0x00 };
// "__STATE_SNAPSHOT__"
static const unsigned char E_Y_SNAPSHOT[] = { 0x60, 0xF9, 0x4E, 0x9C, 0x35, 0xC6, 0xAE, 0x0F, 0x6C, 0xE8, 0x5C, 0x98, 0x27, 0xDA, 0xA4, 0x04, 0x60, 0xF9, 0x00 };
// ".push("
static const unsigned char E_Y_PUSH[] = { 0x11, 0xD6, 0x68, 0xBB, 0x1C, 0xBA, 0x00 };
// "result"
static const unsigned char E_Y_RESULT[] = { 0x4D, 0xC3, 0x6E, 0xBD, 0x18, 0xE6, 0x00 };
// "tracks"
static const unsigned char E_Y_TRACKS[] = { 0x4B, 0xD4, 0x7C, 0xAB, 0x1F, 0xE1, 0x00 };
// "track"
static const unsigned char E_Y_TRACK[] = { 0x4B, 0xD4, 0x7C, 0xAB, 0x1F, 0x00 };
// "artists"
static const unsigned char E_Y_ARTISTS[] = { 0x5E, 0xD4, 0x69, 0xA1, 0x07, 0xE6, 0x98, 0x00 };
// "name"
static const unsigned char E_Y_NAME[] = { 0x51, 0xC7, 0x70, 0xAD, 0x00 };
// "major"
static const unsigned char E_Y_MAJOR[] = { 0x52, 0xC7, 0x77, 0xA7, 0x06, 0x00 };
// "playlist"
static const unsigned char E_Y_PLAYLIST[] = { 0x4F, 0xCA, 0x7C, 0xB1, 0x18, 0xFB, 0x98, 0x24, 0x00 };
// "items"
static const unsigned char E_Y_ITEMS[] = { 0x56, 0xD2, 0x78, 0xA5, 0x07, 0x00 };
// "data"
static const unsigned char E_Y_DATA[] = { 0x5B, 0xC7, 0x69, 0xA9, 0x00 };

static const size_t MAX_TRACKS = 5000;

// Расшифровать XOR-строку в std::string (плейнтекст живёт только в этом буфере).
static std::string dec(const unsigned char* enc, size_t len) {
    std::string out;
    out.resize(len);
    for (size_t i = 0; i < len; i++) out[i] = (char)(enc[i] ^ RK[i % 8]);
    return out;
}
#define DEC(arr) dec((arr), sizeof(arr) - 1)

// Линейный скан массива [ ... ] с учётом строк/экранирования (как в Kotlin).
static long matchBracket(const std::string& s, long start) {
    int depth = 0; bool inStr = false;
    long n = (long)s.size();
    for (long i = start; i < n; i++) {
        char c = s[i];
        if (inStr) {
            if (c == '\\') { i++; }
            else if (c == '"') inStr = false;
        } else {
            if (c == '"') inStr = true;
            else if (c == '[') depth++;
            else if (c == ']') { if (--depth == 0) return i; }
        }
    }
    return -1;
}

// Извлечь id плейлиста Spotify из вставленной ссылки: ищем "playlist", затем
// разделитель ':' или '/', затем >=16 символов [A-Za-z0-9].
static std::string spotifyId(const std::string& url) {
    const std::string key = "playlist";
    size_t p = url.find(key);
    while (p != std::string::npos) {
        size_t j = p + key.size();
        if (j < url.size() && (url[j] == ':' || url[j] == '/')) {
            size_t k = j + 1, start = k;
            while (k < url.size()) {
                char c = url[k];
                bool ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
                if (!ok) break;
                k++;
            }
            if (k - start >= 16) return url.substr(start, k - start);
        }
        p = url.find(key, p + 1);
    }
    return std::string();
}

// ── Общие хелперы Яндекса ──

static std::string bytesToString(JNIEnv* env, jbyteArray arr) {
    jsize len = env->GetArrayLength(arr);
    std::string s;
    s.resize((size_t)len);
    if (len > 0) env->GetByteArrayRegion(arr, 0, len, reinterpret_cast<jbyte*>(&s[0]));
    return s;
}

static jbyteArray stringToBytes(JNIEnv* env, const std::string& s) {
    jbyteArray out = env->NewByteArray((jsize)s.size());
    if (out && !s.empty())
        env->SetByteArrayRegion(out, 0, (jsize)s.size(), reinterpret_cast<const jbyte*>(s.data()));
    return out;
}

// Скобочный скан { ... } с учётом строк/экранирования (аналог matchBracket для []).
static long matchBrace(const std::string& s, long start) {
    int depth = 0; bool inStr = false;
    long n = (long)s.size();
    for (long i = start; i < n; i++) {
        char c = s[i];
        if (inStr) {
            if (c == '\\') { i++; }
            else if (c == '"') inStr = false;
        } else {
            if (c == '"') inStr = true;
            else if (c == '{') depth++;
            else if (c == '}') { if (--depth == 0) return i; }
        }
    }
    return -1;
}

// Число после ключа: ищем "<key>", пропускаем пробелы/двоеточие, читаем цифры.
static std::string yFindNum(const std::string& s, const std::string& key) {
    std::string q; q.push_back('"'); q += key; q.push_back('"');
    size_t p = s.find(q);
    while (p != std::string::npos) {
        size_t j = p + q.size();
        while (j < s.size() && (s[j] == ' ' || s[j] == ':')) j++;
        size_t start = j;
        while (j < s.size() && s[j] >= '0' && s[j] <= '9') j++;
        if (j > start) return s.substr(start, j - start);
        p = s.find(q, p + 1);
    }
    return std::string();
}

static void appendRec(std::string& result, const std::string& title, const std::string& artist) {
    if (!result.empty()) result.push_back('\x1E');
    result += title;
    result.push_back('\x1F');
    result += artist;
}

// track-объект {title, artists:[{name}], major:{name}} → title/artist.
// artist пустым остаётся, если ни исполнителей, ни лейбла — Kotlin подставит
// "Unknown Artist" (как в Spotify-пути).
static bool yExtractTrack(const json& t, std::string& title, std::string& artist) {
    if (!t.is_object()) return false;
    std::string tf = DEC(E_F_TITLE);
    if (t.contains(tf) && t[tf].is_string()) title = t[tf].get<std::string>();
    if (title.empty()) return false;

    std::string af = DEC(E_Y_ARTISTS), nf = DEC(E_Y_NAME), mf = DEC(E_Y_MAJOR);
    std::string joined;
    if (t.contains(af) && t[af].is_array()) {
        for (auto& a : t[af]) {
            if (a.is_object() && a.contains(nf) && a[nf].is_string()) {
                std::string n = a[nf].get<std::string>();
                if (!n.empty()) { if (!joined.empty()) joined += ", "; joined += n; }
            }
        }
    }
    if (!joined.empty()) { artist = joined; return true; }

    if (t.contains(mf) && t[mf].is_object() && t[mf].contains(nf) && t[mf][nf].is_string()) {
        std::string mn = t[mf][nf].get<std::string>();
        if (!mn.empty() && mn != "-") { artist = mn; return true; }
    }
    artist.clear();
    return true;
}

// Официальное API: result.tracks[].track.{...}.
static std::string yParseApi(const std::string& body) {
    std::string result;
    try {
        json root = json::parse(body);
        std::string rf = DEC(E_Y_RESULT), tks = DEC(E_Y_TRACKS), tk = DEC(E_Y_TRACK);
        if (root.contains(rf) && root[rf].is_object()) {
            const json& res = root[rf];
            if (res.contains(tks) && res[tks].is_array()) {
                size_t count = 0;
                for (auto& item : res[tks]) {
                    if (count >= MAX_TRACKS) break;
                    if (!item.is_object() || !item.contains(tk)) continue;
                    std::string title, artist;
                    if (yExtractTrack(item[tk], title, artist)) { appendRec(result, title, artist); count++; }
                }
            }
        }
    } catch (...) {}
    return result;
}

// Старые ссылки: блоки (window.__STATE_SNAPSHOT__ = ...).push({playlist:{items:[{data:{...}}]}}).
// Маркеры ищем линейным find + скобочным скан (без .*?-регэкспов — бэктрекинг-бомба).
static std::string yParseSnapshot(const std::string& html) {
    std::string result;
    std::string snap = DEC(E_Y_SNAPSHOT), push = DEC(E_Y_PUSH);
    std::string pf = DEC(E_Y_PLAYLIST), itf = DEC(E_Y_ITEMS), df = DEC(E_Y_DATA);
    size_t from = 0;
    while (true) {
        size_t marker = html.find(snap, from);
        if (marker == std::string::npos) break;
        size_t p = html.find(push, marker);
        if (p == std::string::npos) break;
        long bStart = (long)html.find('{', p);
        if (bStart < 0) break;
        long bEnd = matchBrace(html, bStart);
        if (bEnd > bStart) {
            std::string block = html.substr((size_t)bStart, (size_t)(bEnd - bStart + 1));
            try {
                json j = json::parse(block);
                if (j.contains(pf) && j[pf].is_object()) {
                    const json& pl = j[pf];
                    if (pl.contains(itf) && pl[itf].is_array()) {
                        size_t count = 0;
                        for (auto& it : pl[itf]) {
                            if (count >= MAX_TRACKS) break;
                            if (it.is_object() && it.contains(df)) {
                                std::string title, artist;
                                if (yExtractTrack(it[df], title, artist)) { appendRec(result, title, artist); count++; }
                            }
                        }
                    }
                }
            } catch (...) {}
            if (!result.empty()) return result;
            from = (size_t)(bEnd + 1);
        } else {
            from = p + push.size();
        }
    }
    return result;
}

extern "C" {

// Построить embed-URL по вставленной ссылке; "" если это не плейлист Spotify.
JNIEXPORT jstring JNICALL
Java_com_liquidmusicglass_security_ResolveNative_spotifyEmbedUrl(JNIEnv* env, jobject, jstring pasted) {
    const char* c = env->GetStringUTFChars(pasted, nullptr);
    std::string url = c ? c : "";
    if (c) env->ReleaseStringUTFChars(pasted, c);
    std::string id = spotifyId(url);
    if (id.empty()) return env->NewStringUTF("");
    std::string full = DEC(E_SPOTIFY_EMBED) + id;
    return env->NewStringUTF(full.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_liquidmusicglass_security_ResolveNative_spotifyUa(JNIEnv* env, jobject) {
    return env->NewStringUTF(DEC(E_UA).c_str());
}
JNIEXPORT jstring JNICALL
Java_com_liquidmusicglass_security_ResolveNative_spotifyAccept(JNIEnv* env, jobject) {
    return env->NewStringUTF(DEC(E_ACCEPT).c_str());
}
JNIEXPORT jstring JNICALL
Java_com_liquidmusicglass_security_ResolveNative_spotifyAcceptLang(JNIEnv* env, jobject) {
    return env->NewStringUTF(DEC(E_ACCEPT_LANG).c_str());
}

// Разобрать HTML embed-страницы (UTF-8 byte[]) → UTF-8 byte[]:
//   запись = title \x1F artist, записи разделены \x1E. Пусто = не распарсили.
JNIEXPORT jbyteArray JNICALL
Java_com_liquidmusicglass_security_ResolveNative_spotifyParse(JNIEnv* env, jobject, jbyteArray htmlBytes) {
    jsize len = env->GetArrayLength(htmlBytes);
    std::string html;
    html.resize((size_t)len);
    env->GetByteArrayRegion(htmlBytes, 0, len, reinterpret_cast<jbyte*>(&html[0]));

    std::string result;
    std::string mark = DEC(E_MARK_TRACKLIST);          // "trackList":
    size_t m = html.find(mark);
    if (m != std::string::npos) {
        long arrStart = (long)html.find('[', m);
        if (arrStart >= 0) {
            long arrEnd = matchBracket(html, arrStart);
            if (arrEnd > arrStart) {
                std::string sub = html.substr(arrStart, arrEnd - arrStart + 1);
                std::string tf = DEC(E_F_TITLE);
                std::string sf = DEC(E_F_SUBTITLE);
                try {
                    json arr = json::parse(sub);
                    if (arr.is_array()) {
                        size_t count = 0;
                        for (auto& el : arr) {
                            if (count >= MAX_TRACKS) break;
                            if (!el.is_object()) continue;
                            std::string title, artist;
                            if (el.contains(tf) && el[tf].is_string()) title = el[tf].get<std::string>();
                            if (el.contains(sf) && el[sf].is_string()) artist = el[sf].get<std::string>();
                            if (title.empty()) continue;
                            if (!result.empty()) result.push_back('\x1E');
                            result += title;
                            result.push_back('\x1F');
                            result += artist;
                            count++;
                        }
                    }
                } catch (...) {
                    // битый JSON — вернём пусто, Kotlin покажет «формат изменился»
                }
            }
        }
    }

    jbyteArray out = env->NewByteArray((jsize)result.size());
    if (out && !result.empty())
        env->SetByteArrayRegion(out, 0, (jsize)result.size(), reinterpret_cast<const jbyte*>(result.data()));
    return out;
}

// ── Яндекс: заголовки браузера/API ──
JNIEXPORT jstring JNICALL
Java_com_liquidmusicglass_security_ResolveNative_yandexUa(JNIEnv* env, jobject) {
    return env->NewStringUTF(DEC(E_UA).c_str());
}
JNIEXPORT jstring JNICALL
Java_com_liquidmusicglass_security_ResolveNative_yandexAccept(JNIEnv* env, jobject) {
    return env->NewStringUTF(DEC(E_Y_ACCEPT).c_str());
}
JNIEXPORT jstring JNICALL
Java_com_liquidmusicglass_security_ResolveNative_yandexAcceptLang(JNIEnv* env, jobject) {
    return env->NewStringUTF(DEC(E_Y_ACCEPT_LANG).c_str());
}
JNIEXPORT jstring JNICALL
Java_com_liquidmusicglass_security_ResolveNative_yandexReferer(JNIEnv* env, jobject) {
    return env->NewStringUTF(DEC(E_Y_REFERER).c_str());
}
JNIEXPORT jstring JNICALL
Java_com_liquidmusicglass_security_ResolveNative_yandexApiAccept(JNIEnv* env, jobject) {
    return env->NewStringUTF(DEC(E_Y_ACCEPT_JSON).c_str());
}

// Новая ссылка: из HTML достать uid/kind → официальный api-URL; "" если не нашлись.
JNIEXPORT jstring JNICALL
Java_com_liquidmusicglass_security_ResolveNative_yandexApiUrl(JNIEnv* env, jobject, jbyteArray htmlBytes) {
    std::string html = bytesToString(env, htmlBytes);
    std::string uid = yFindNum(html, DEC(E_Y_UID));
    std::string kind = yFindNum(html, DEC(E_Y_KIND));
    if (uid.empty() || kind.empty()) return env->NewStringUTF("");
    std::string url = DEC(E_Y_API_USERS) + uid + DEC(E_Y_API_PLAYLISTS) + kind;
    return env->NewStringUTF(url.c_str());
}

// Тело официального API (UTF-8 byte[]) → записи title \x1F artist, разделены \x1E.
JNIEXPORT jbyteArray JNICALL
Java_com_liquidmusicglass_security_ResolveNative_yandexApiParse(JNIEnv* env, jobject, jbyteArray bodyBytes) {
    std::string body = bytesToString(env, bodyBytes);
    return stringToBytes(env, yParseApi(body));
}

// HTML старой ссылки (UTF-8 byte[]) → те же записи из __STATE_SNAPSHOT__.
JNIEXPORT jbyteArray JNICALL
Java_com_liquidmusicglass_security_ResolveNative_yandexSnapshotParse(JNIEnv* env, jobject, jbyteArray htmlBytes) {
    std::string html = bytesToString(env, htmlBytes);
    return stringToBytes(env, yParseSnapshot(html));
}

} // extern "C"
