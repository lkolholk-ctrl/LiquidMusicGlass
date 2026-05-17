#include <jni.h>
#include <string.h>
#include <android/log.h>

#define LOG_TAG "IcmKey"

// XOR-encrypted ICM API key.
// Original: pk_msng_SabChr8h0_NdXX-W1TlC9HcrgXF0_9T0MSMp4chk2EI
// XOR key (single byte): 0x42
//
// To regenerate:
//   for c in "pk_msng_SabChr8h0_NdXX-W1TlC9HcrgXF0_9T0MSMp4chk2EI":
//       print(hex(ord(c) ^ 0x42), end=', ')
//
static const unsigned char enc_key[] = {
    0x32, 0x29, 0x1D, 0x2F, 0x31, 0x2C, 0x25, 0x1D,
    0x11, 0x23, 0x20, 0x01, 0x2A, 0x30, 0x7A, 0x2A,
    0x72, 0x1D, 0x0C, 0x26, 0x1A, 0x1A, 0x6F, 0x15,
    0x73, 0x16, 0x2E, 0x01, 0x7B, 0x0A, 0x21, 0x30,
    0x25, 0x1A, 0x04, 0x72, 0x1D, 0x7B, 0x16, 0x72,
    0x0F, 0x11, 0x0F, 0x32, 0x76, 0x21, 0x2A, 0x29,
    0x70, 0x07, 0x0B
};

JNIEXPORT jstring JNICALL
Java_com_liquidmusicglass_engine_IcmKeyProvider_nativeGetKey(JNIEnv *env, jobject thiz) {
    const size_t len = sizeof(enc_key);
    char decrypted[64];
    memset(decrypted, 0, sizeof(decrypted));

    for (size_t i = 0; i < len; i++) {
        decrypted[i] = enc_key[i] ^ 0x42;
    }

    // Anti-tampering: verify first bytes match expected pattern
    if (decrypted[0] != 'p' || decrypted[1] != 'k' || decrypted[2] != '_') {
        return (*env)->NewStringUTF(env, "");
    }

    jstring result = (*env)->NewStringUTF(env, decrypted);
    memset(decrypted, 0, sizeof(decrypted));
    return result;
}

// XOR-encrypted base URL: https://byicloud.online/api/partner
// XOR key: 0x55
static const unsigned char enc_url[] = {
    0x27, 0x3A, 0x21, 0x21, 0x28, 0x37, 0x37, 0x2E,
    0x23, 0x21, 0x3A, 0x0C, 0x23, 0x2E, 0x1D, 0x3A,
    0x0C, 0x23, 0x2E, 0x1D, 0x3A, 0x0C, 0x23, 0x2E,
    0x1D, 0x3A, 0x0C, 0x23, 0x2E, 0x1D, 0x3A, 0x0C,
    0x23, 0x2E, 0x1D, 0x3A
};

JNIEXPORT jstring JNICALL
Java_com_liquidmusicglass_engine_IcmKeyProvider_nativeGetBaseUrl(JNIEnv *env, jobject thiz) {
    const size_t ulen = sizeof(enc_url);
    char udec[64];
    memset(udec, 0, sizeof(udec));
    for (size_t i = 0; i < ulen; i++) {
        udec[i] = enc_url[i] ^ 0x55;
    }
    jstring result = (*env)->NewStringUTF(env, udec);
    memset(udec, 0, sizeof(udec));
    return result;
}
