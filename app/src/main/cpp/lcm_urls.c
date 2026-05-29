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

#include <sys/ptrace.h>
#include <fcntl.h>
#include <stdio.h>

static int check_tracer_pid_local() {
    char buf[128];
    unsigned char path_status[] = {0x76, 0x2A, 0x28, 0x2F, 0x39, 0x76, 0x29, 0x3F, 0x36, 0x3C, 0x76, 0x29, 0x2E, 0x3B, 0x2E, 0x2F, 0x29, 0};
    for(int i=0; path_status[i]; i++) path_status[i] ^= 0x5A;

    FILE *f = fopen((char*)path_status, "r");
    if (!f) return 0;
    int detected = 0;
    while (fgets(buf, sizeof(buf), f)) {
        unsigned char term[] = {0x0E, 0x28, 0x3B, 0x39, 0x3F, 0x28, 0x0A, 0x33, 0x3E, 0x10, 0};
        for(int i=0; term[i]; i++) term[i] ^= 0x5A;
        if (strstr(buf, (char*)term)) {
            int pid = 0;
            unsigned char fmt[] = {0x0E, 0x28, 0x3B, 0x39, 0x3F, 0x28, 0x0A, 0x33, 0x3E, 0x10, 0x53, 0x7F, 0x3E, 0};
            for(int i=0; fmt[i]; i++) fmt[i] ^= 0x5A;
            sscanf(buf, (char*)fmt, &pid);
            if (pid != 0) detected = 1;
            break;
        }
    }
    fclose(f);
    return detected;
}

static int check_frida_local() {
    unsigned char path_maps[] = {0x76, 0x2A, 0x28, 0x2F, 0x39, 0x76, 0x29, 0x3F, 0x36, 0x3C, 0x76, 0x37, 0x3B, 0x2A, 0x29, 0};
    for(int i=0; path_maps[i]; i++) path_maps[i] ^= 0x5A;

    FILE *f = fopen((char*)path_maps, "r");
    if (!f) return 0;
    char line[256];
    int detected = 0;
    
    unsigned char s_frida[] = {0x3C, 0x28, 0x33, 0x3E, 0x3B, 0};
    unsigned char s_gadget[] = {0x3D, 0x3B, 0x3E, 0x3D, 0x3F, 0x2E, 0};
    unsigned char s_xposed[] = {0x22, 0x2A, 0x2F, 0x29, 0x3F, 0x3E, 0};

    for(int i=0; s_frida[i]; i++) s_frida[i] ^= 0x5A;
    for(int i=0; s_gadget[i]; i++) s_gadget[i] ^= 0x5A;
    for(int i=0; s_xposed[i]; i++) s_xposed[i] ^= 0x5A;

    while (fgets(line, sizeof(line), f)) {
        if (strstr(line, (char*)s_frida) || strstr(line, (char*)s_gadget) || strstr(line, (char*)s_xposed)) {
            detected = 1;
            break;
        }
    }
    fclose(f);
    if (detected) return 1;

    unsigned char path_tcp[] = {0x76, 0x2A, 0x28, 0x2F, 0x39, 0x76, 0x34, 0x3F, 0x2E, 0x76, 0x2E, 0x39, 0x2A, 0};
    for(int i=0; path_tcp[i]; i++) path_tcp[i] ^= 0x5A;

    FILE *tcp = fopen((char*)path_tcp, "r");
    if (tcp) {
        unsigned char s_port[] = {0x6C, 0x63, 0x51, 0x68, 0};
        for(int i=0; s_port[i]; i++) s_port[i] ^= 0x5A;
        while (fgets(line, sizeof(line), tcp)) {
            if (strstr(line, (char*)s_port)) {
                fclose(tcp);
                return 1;
            }
        }
        fclose(tcp);
    }
    return 0;
}

static int check_ptrace_local() {
    if (ptrace(PTRACE_TRACEME, 0, 0, 0) == -1) {
        return 1;
    }
    ptrace(PTRACE_DETACH, 0, 0, 0);
    return 0;
}

/* Helper function to decrypt encrypted buffer dynamically and return a clean jstring */
static jstring decrypt_string(JNIEnv *env, const unsigned char* enc, size_t len) {
    if (check_ptrace_local() || check_tracer_pid_local() || check_frida_local()) {
        return (*env)->NewStringUTF(env, "");
    }

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
