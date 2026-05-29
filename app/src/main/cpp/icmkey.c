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

/* ════════════════════════════════════════════════════════
   XOR-ENCRYPTED STRINGS (plaintext never appears in binary)
   ════════════════════════════════════════════════════════ */

// "pk_msng_SabChr8h0_NdXX-W1TlC9HcrgXF0_9T0MSMp4chk2EI"
static const unsigned char ENC_KEY[] = {
    X('p',0), X('k',1), X('_',2), X('m',3), X('s',4), X('n',5), X('g',6), X('_',7),
    X('S',8), X('a',9), X('b',10), X('C',11), X('h',12), X('r',13), X('8',14), X('h',15),
    X('0',16), X('_',17), X('N',18), X('d',19), X('X',20), X('X',21), X('-',22), X('W',23),
    X('1',24), X('T',25), X('l',26), X('C',27), X('9',28), X('H',29), X('c',30), X('r',31),
    X('g',32), X('X',33), X('F',34), X('0',35), X('_',36), X('9',37), X('T',38), X('0',39),
    X('M',40), X('S',41), X('M',42), X('p',43), X('4',44), X('c',45), X('h',46), X('k',47),
    X('2',48), X('E',49), X('I',50), 0x00
};

// "https://byicloud.online/api/partner"
static const unsigned char ENC_URL[] = {
    X('h',0), X('t',1), X('t',2), X('p',3), X('s',4), X(':',5), X('/',6), X('/',7),
    X('b',8), X('y',9), X('i',10), X('c',11), X('l',12), X('o',13), X('u',14), X('d',15),
    X('.',16), X('o',17), X('n',18), X('l',19), X('i',20), X('n',21), X('e',22), X('/',23),
    X('a',24), X('p',25), X('i',26), X('/',27), X('p',28), X('a',29), X('r',30), X('t',31),
    X('n',32), X('e',33), X('r',34), 0x00
};

/* Safe memory cleaning macro using volatile pointer to bypass compiler optimizations */
#define WIPE(buf, len) do { volatile unsigned char* _p = (volatile unsigned char*)(buf); \
    for(size_t _i = 0; _i < (len); _i++) _p[_p - _p + _i] = 0; } while(0)

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
Java_com_liquidmusicglass_engine_IcmKeyProvider_nativeGetKey(JNIEnv *env, jobject thiz, jobject context) {
    (void)thiz; (void)context;
    return decrypt_string(env, ENC_KEY, sizeof(ENC_KEY) - 1);
}

JNIEXPORT jstring JNICALL
Java_com_liquidmusicglass_engine_IcmKeyProvider_nativeGetBaseUrl(JNIEnv *env, jobject thiz) {
    (void)thiz;
    return decrypt_string(env, ENC_URL, sizeof(ENC_URL) - 1);
}
