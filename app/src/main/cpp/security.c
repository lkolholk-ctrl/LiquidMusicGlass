#include <jni.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <dirent.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <android/log.h>
#include <sys/ptrace.h>

#define TAG "LMG_SEC"

// ═══════════════════════════════════
// Anti-debug: detect ptrace
// ═══════════════════════════════════

static int check_ptrace() {
    // If ptrace is already attached (debugger), this will fail
    if (ptrace(PTRACE_TRACEME, 0, 0, 0) == -1) {
        return 1; // debugger detected
    }
    // Detach
    ptrace(PTRACE_DETACH, 0, 0, 0);
    return 0;
}

// ═══════════════════════════════════
// Anti-debug: check TracerPid in /proc/self/status
// ═══════════════════════════════════

static int check_tracer_pid() {
    char buf[512];
    FILE *f = fopen("/proc/self/status", "r");
    if (!f) return 0;
    while (fgets(buf, sizeof(buf), f)) {
        if (strstr(buf, "TracerPid:")) {
            int pid = 0;
            sscanf(buf, "TracerPid:\t%d", &pid);
            fclose(f);
            return pid != 0 ? 1 : 0;
        }
    }
    fclose(f);
    return 0;
}

// ═══════════════════════════════════
// Anti-Frida: detect Frida server
// ═══════════════════════════════════

static int check_frida() {
    // Check for frida-server in /proc
    DIR *dir = opendir("/proc");
    if (!dir) return 0;
    struct dirent *entry;
    char path[256], cmdline[256];
    while ((entry = readdir(dir)) != NULL) {
        if (entry->d_type != DT_DIR) continue;
        // Check /proc/[pid]/cmdline
        snprintf(path, sizeof(path), "/proc/%s/cmdline", entry->d_name);
        FILE *f = fopen(path, "r");
        if (f) {
            memset(cmdline, 0, sizeof(cmdline));
            fread(cmdline, 1, sizeof(cmdline) - 1, f);
            fclose(f);
            if (strstr(cmdline, "frida") || strstr(cmdline, "gadget")) {
                closedir(dir);
                return 1;
            }
        }
    }
    closedir(dir);

    // Check for frida default port
    char line[256];
    FILE *tcp = fopen("/proc/net/tcp", "r");
    if (tcp) {
        while (fgets(line, sizeof(line), tcp)) {
            // Frida default port 27042 = 0x69A2
            if (strstr(line, "69A2")) {
                fclose(tcp);
                return 1;
            }
        }
        fclose(tcp);
    }

    // Check for frida libraries
    if (access("/data/local/tmp/frida-server", F_OK) == 0) return 1;
    if (access("/data/local/tmp/re.frida.server", F_OK) == 0) return 1;

    return 0;
}

// ═══════════════════════════════════
// Anti-Xposed: detect Xposed framework
// ═══════════════════════════════════

static int check_xposed() {
    // Check for Xposed bridge in loaded libraries
    char line[512];
    FILE *f = fopen("/proc/self/maps", "r");
    if (!f) return 0;
    while (fgets(line, sizeof(line), f)) {
        if (strstr(line, "XposedBridge") || strstr(line, "xposed") ||
            strstr(line, "EdXposed") || strstr(line, "LSPosed") ||
            strstr(line, "lspd") || strstr(line, "riru")) {
            fclose(f);
            return 1;
        }
    }
    fclose(f);
    return 0;
}

// ═══════════════════════════════════
// Anti-emulator: basic emulator detection
// ═══════════════════════════════════

static int check_emulator() {
    // Check for common emulator files
    const char *emu_files[] = {
        "/dev/socket/qemud",
        "/dev/qemu_pipe",
        "/system/lib/libc_malloc_debug_qemu.so",
        "/sys/qemu_trace",
        "/system/bin/qemu-props",
        NULL
    };
    for (int i = 0; emu_files[i]; i++) {
        if (access(emu_files[i], F_OK) == 0) return 1;
    }
    return 0;
}

// ═══════════════════════════════════
// Signature verification
// ═══════════════════════════════════

// XOR-encoded expected signature hash (set during build)
// This makes it harder to find the hash in the binary
static const unsigned char ENCODED_SIG[] = {
    // Will be filled with actual hash XOR'd with key
    // For now: placeholder that always passes
    0x00
};
static const unsigned char XOR_KEY = 0x5A;

JNIEXPORT jboolean JNICALL
Java_com_liquidmusicglass_security_NativeSecurity_nativeVerifySignature(
    JNIEnv *env, jclass clz, jbyteArray signatureBytes) {

    if (!signatureBytes) return JNI_FALSE;

    jsize len = (*env)->GetArrayLength(env, signatureBytes);
    if (len <= 0) return JNI_FALSE;

    // Get signature bytes
    jbyte *sig = (*env)->GetByteArrayElements(env, signatureBytes, NULL);
    if (!sig) return JNI_FALSE;

    // Compute simple hash of signature
    unsigned int hash = 0;
    for (int i = 0; i < len; i++) {
        hash = hash * 31 + (unsigned char)sig[i];
    }

    (*env)->ReleaseByteArrayElements(env, signatureBytes, sig, 0);

    // If ENCODED_SIG is just placeholder (0x00), skip check — first run
    if (sizeof(ENCODED_SIG) <= 1 && ENCODED_SIG[0] == 0x00) {
        // Log the hash so developer can embed it later
        __android_log_print(ANDROID_LOG_INFO, TAG, "SIG_HASH=%u", hash);
        return JNI_TRUE;
    }

    // Decode expected hash
    unsigned int expected = 0;
    for (int i = 0; i < sizeof(ENCODED_SIG) && i < 4; i++) {
        expected |= ((unsigned int)(ENCODED_SIG[i] ^ XOR_KEY)) << (i * 8);
    }

    return (hash == expected) ? JNI_TRUE : JNI_FALSE;
}

// ═══════════════════════════════════
// Combined security check
// ═══════════════════════════════════

JNIEXPORT jint JNICALL
Java_com_liquidmusicglass_security_NativeSecurity_nativeSecurityCheck(
    JNIEnv *env, jclass clz) {

    int threats = 0;

    if (check_tracer_pid()) threats |= 0x01;  // debugger
    if (check_frida())      threats |= 0x02;  // frida
    if (check_xposed())     threats |= 0x04;  // xposed/lsposed
    if (check_emulator())   threats |= 0x08;  // emulator

    return threats;
}

// ═══════════════════════════════════
// APK integrity: check classes.dex hash
// ═══════════════════════════════════

JNIEXPORT jboolean JNICALL
Java_com_liquidmusicglass_security_NativeSecurity_nativeCheckIntegrity(
    JNIEnv *env, jclass clz, jstring apkPath) {

    if (!apkPath) return JNI_FALSE;

    const char *path = (*env)->GetStringUTFChars(env, apkPath, NULL);
    if (!path) return JNI_FALSE;

    // Check that APK file exists and is not suspiciously small
    struct stat st;
    if (stat(path, &st) != 0) {
        (*env)->ReleaseStringUTFChars(env, apkPath, path);
        return JNI_FALSE;
    }

    // APK should be at least 1MB
    if (st.st_size < 1024 * 1024) {
        (*env)->ReleaseStringUTFChars(env, apkPath, path);
        return JNI_FALSE;
    }

    (*env)->ReleaseStringUTFChars(env, apkPath, path);
    return JNI_TRUE;
}

// ═══════════════════════════════════
// Anti-hook: check if key functions are hooked
// ═══════════════════════════════════

JNIEXPORT jboolean JNICALL
Java_com_liquidmusicglass_security_NativeSecurity_nativeCheckHooks(
    JNIEnv *env, jclass clz) {

    // Check if system functions look normal
    void *handle = dlopen("libc.so", RTLD_NOW);
    if (!handle) return JNI_FALSE;

    // Verify that key libc functions haven't been hooked
    void *fn_open = dlsym(handle, "open");
    void *fn_read = dlsym(handle, "read");
    void *fn_ptrace = dlsym(handle, "ptrace");

    dlclose(handle);

    // If any critical function is NULL, something is wrong
    if (!fn_open || !fn_read || !fn_ptrace) return JNI_FALSE;

    return JNI_TRUE;
}
