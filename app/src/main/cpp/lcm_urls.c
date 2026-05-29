#include <jni.h>
#include <string.h>
#include <stdlib.h>

/* ════════════════════════════════════════════════════════
   BUILD-TIME XOR KEY FOR API STRINGS
   ════════════════════════════════════════════════════════ */
#define BK0 0x5A
#define BK1 0xBD
#define BK2 0xE2
#define BK3 0x47
#define BK4 0x91
#define BK5 0xC3
#define BK6 0x6E
#define BK7 0x88

#define BK(i) ((i)%8==0?BK0:(i)%8==1?BK1:(i)%8==2?BK2:(i)%8==3?BK3:\
               (i)%8==4?BK4:(i)%8==5?BK5:(i)%8==6?BK6:BK7)

#define X(b, i) ((unsigned char)((b) ^ BK(i)))

static const unsigned char BUILD_KEY[8] = {BK0, BK1, BK2, BK3, BK4, BK5, BK6, BK7};

// "https://byicloud.online/api/partner"
static const unsigned char ENC_URL[] = {
    X('h',0), X('t',1), X('t',2), X('p',3), X('s',4), X(':',5), X('/',6), X('/',7),
    X('b',8), X('y',9), X('i',10), X('c',11), X('l',12), X('o',13), X('u',14), X('d',15),
    X('.',16), X('o',17), X('n',18), X('l',19), X('i',20), X('n',21), X('e',22), X('/',23),
    X('a',24), X('p',25), X('i',26), X('/',27), X('p',28), X('a',29), X('r',30), X('t',31),
    X('n',32), X('e',33), X('r',34), 0x00
};

/* Safe memory cleaning macro using volatile pointer */
#define WIPE(buf, len) do { volatile unsigned char* _p = (volatile unsigned char*)(buf); \
    for(size_t _i = 0; _i < (len); _i++) _p[_i] = 0; } while(0)

/* Helper function to decrypt encrypted buffer dynamically and return a clean jstring */
static jstring decrypt_string(JNIEnv *env, const unsigned char* enc, size_t len) {
    unsigned char* dec = (unsigned char*)malloc(len + 1);
    if (!dec) return NULL;

    for (size_t i = 0; i < len; i++) {
        dec[i] = enc[i] ^ BUILD_KEY[i % 8];
    }
    dec[len] = 0;

    jstring result = (*env)->NewStringUTF(env, (char*)dec);
    
    // Aggressive zero-wipe and clean-up of decrypted string in RAM
    WIPE(dec, len + 1);
    free(dec);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_liquidmusicglass_security_LcmNative_getYandexResolverUrl(JNIEnv *env, jobject thiz) {
    (void)thiz;
    return decrypt_string(env, ENC_URL, sizeof(ENC_URL) - 1);
}

JNIEXPORT jstring JNICALL
Java_com_liquidmusicglass_security_LcmNative_getIcmBaseUrl(JNIEnv *env, jobject thiz) {
    (void)thiz;
    return decrypt_string(env, ENC_URL, sizeof(ENC_URL) - 1);
}

JNIEXPORT jstring JNICALL
Java_com_liquidmusicglass_security_LcmNative_getStreamUrl(JNIEnv *env, jobject thiz, jstring videoId) {
    (void)thiz; (void)videoId;
    return (*env)->NewStringUTF(env, "");
}
