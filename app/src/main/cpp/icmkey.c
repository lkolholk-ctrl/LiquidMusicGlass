#include <jni.h>
#include <string.h>

// Stub implementation - returns empty strings
// In production, this should contain encrypted keys

JNIEXPORT jstring JNICALL
Java_com_liquidmusicglass_engine_IcmKeyProvider_nativeGetKey(JNIEnv *env, jobject thiz, jobject context) {
    return (*env)->NewStringUTF(env, "");
}

JNIEXPORT jstring JNICALL
Java_com_liquidmusicglass_engine_IcmKeyProvider_nativeGetBaseUrl(JNIEnv *env, jobject thiz) {
    return (*env)->NewStringUTF(env, "");
}
