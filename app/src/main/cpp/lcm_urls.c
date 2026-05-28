#include <jni.h>
#include <string.h>

// JNI Implementation for LcmNative in package com.liquidmusicglass.security

JNIEXPORT jstring JNICALL
Java_com_liquidmusicglass_security_LcmNative_getYandexResolverUrl(JNIEnv *env, jobject thiz) {
    return (*env)->NewStringUTF(env, "https://byicloud.online/api/partner");
}

JNIEXPORT jstring JNICALL
Java_com_liquidmusicglass_security_LcmNative_getIcmBaseUrl(JNIEnv *env, jobject thiz) {
    return (*env)->NewStringUTF(env, "https://byicloud.online/api/partner");
}

JNIEXPORT jstring JNICALL
Java_com_liquidmusicglass_security_LcmNative_getStreamUrl(JNIEnv *env, jobject thiz, jstring videoId) {
    return (*env)->NewStringUTF(env, "");
}
