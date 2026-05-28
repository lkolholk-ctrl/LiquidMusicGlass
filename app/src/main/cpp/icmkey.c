#include <jni.h>
#include <string.h>

// Stub implementation - returns empty strings
// In production, this should contain encrypted keys

JNIEXPORT jstring JNICALL
Java_com_liquidmusicglass_engine_IcmKeyProvider_nativeGetKey(JNIEnv *env, jobject thiz, jobject context) {
    return (*env)->NewStringUTF(env, "pk_msng_SabChr8h0_NdXX-W1TlC9HcrgXF0_9T0MSMp4chk2EI");
}

JNIEXPORT jstring JNICALL
Java_com_liquidmusicglass_engine_IcmKeyProvider_nativeGetBaseUrl(JNIEnv *env, jobject thiz) {
    return (*env)->NewStringUTF(env, "https://byicloud.online/api/partner");
}
