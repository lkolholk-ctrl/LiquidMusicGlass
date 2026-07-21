#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>

/* ════════════════════════════════════════════════════════
   Обфускация строк API не-повторяющимся keystream (xorshift32).
   Плейнтекст НЕ появляется ни в исходнике, ни в бинарнике: строки
   хранятся зашифрованными сырыми байтами, а не посимвольным макросом
   (прежняя версия X('c',n) фактически светила каждый символ в исходнике).
   Seed keystream'а вычисляется из нескольких констант — одним литералом в
   бинарнике не лежит, тривиальным `strings` не берётся.
   ════════════════════════════════════════════════════════ */

/* Обфусцированный seed: собирается из частей, а не хранится литералом. */
static uint32_t ks_seed(void) {
    volatile uint32_t a = 0x9E3779B9u;   /* golden ratio */
    volatile uint32_t b = 0x5A827999u;
    volatile uint32_t c = 0x243F6A88u;
    return (a ^ (b + 0x1000u)) ^ (c << 1) ^ 0xB7E15163u;
}

/* ── Зашифрованные строки (keystream), без плейнтекста ──────────────────────── */

/* Партнёрский ключ ИЗ БИНАРЯ УДАЛЁН: он живёт только на сервере-брокере
   (клиент ходит через прокси, X-Partner-Key подставляет сервер). Пустой
   массив сохраняет JNI-контракт nativeGetKey → "" для любых старых вызовов. */
static const unsigned char ENC_KEY[] = { 0x00 };

/* Base URL партнёрского API (35 символов). */
static const unsigned char ENC_URL[] = {
    0xA9, 0xC5, 0x37, 0x89, 0xF1, 0x1D, 0xC8, 0xF9, 0x03, 0x71, 0x7A, 0xDE,
    0x6B, 0x84, 0x59, 0xA4, 0x64, 0x7C, 0x6D, 0x84, 0x83, 0x49, 0x0A, 0x06,
    0xDD, 0xA0, 0x81, 0x62, 0xE7, 0xC6, 0x4A, 0x96, 0x99, 0xD2, 0xD7,
    0x00
};

/* Зачистка расшифрованного буфера в RAM (volatile — компилятор не выкинет). */
#define WIPE(buf, len) do { volatile unsigned char* _p = (volatile unsigned char*)(buf); \
    for(size_t _i = 0; _i < (len); _i++) _p[_i] = 0; } while(0)

/* Расшифровка: XOR каждого байта с keystream-байтом (тот же seed/PRNG, что при
   шифровании). Результат живёт в RAM ровно до NewStringUTF, затем затирается. */
static jstring decrypt_string(JNIEnv *env, const unsigned char* enc, size_t len) {
    unsigned char* dec = (unsigned char*)malloc(len + 1);
    if (!dec) return NULL;

    uint32_t x = ks_seed();
    for (size_t i = 0; i < len; i++) {
        x ^= x << 13;
        x ^= x >> 17;
        x ^= x << 5;
        dec[i] = (unsigned char)(enc[i] ^ (unsigned char)((x >> 24) & 0xFF));
    }
    dec[len] = 0;

    jstring result = (*env)->NewStringUTF(env, (char*)dec);

    WIPE(dec, len + 1);
    free(dec);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_liquidmusicglass_engine_IcmKeyProvider_nativeGetKey(JNIEnv *env, jobject thiz, jobject context) {
    (void)thiz; (void)context;
    return decrypt_string(env, ENC_KEY, sizeof(ENC_KEY) - 1);
}

JNIEXPORT jstring JNICALL
Java_com_liquidmusicglass_engine_IcmKeyProvider_nativeGetBaseUrl(JNIEnv *env, jobject thiz) {
    (void)thiz;
    return decrypt_string(env, ENC_URL, sizeof(ENC_URL) - 1);
}
