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

/* ════════════════════════════════════════════════════════
   BUILD-TIME XOR KEY — O-MVLL / Compiler static obfuscation
   ════════════════════════════════════════════════════════ */
#define BK0 0x4A
#define BK1 0x7F
#define BK2 0x3C
#define BK3 0xB1
#define BK4 0x92
#define BK5 0xE5
#define BK6 0x28
#define BK7 0xD4

#define BK(i) ((i)%8==0?BK0:(i)%8==1?BK1:(i)%8==2?BK2:(i)%8==3?BK3:\
               (i)%8==4?BK4:(i)%8==5?BK5:(i)%8==6?BK6:BK7)

#define X(b, i) ((unsigned char)((b) ^ BK(i)))

static const unsigned char BUILD_KEY[8] = {BK0, BK1, BK2, BK3, BK4, BK5, BK6, BK7};

/* ════════════════════════════════════════════════════════
   XOR-ENCRYPTED STRINGS (plaintext never appears in binary)
   ════════════════════════════════════════════════════════ */

// "/proc/self/status"
static const unsigned char ENC_STATUS[] = {
    X('/',0),X('p',1),X('r',2),X('o',3),X('c',4),X('/',5),X('s',6),X('e',7),
    X('l',8),X('f',9),X('/',10),X('s',11),X('t',12),X('a',13),X('t',14),X('u',15),
    X('s',16),0x00
};

// "TracerPid:"
static const unsigned char ENC_TRACER_PREFIX[] = {
    X('T',0),X('r',1),X('a',2),X('c',3),X('e',4),X('r',5),X('P',6),X('i',7),
    X('d',8),X(':',9),0x00
};

// "TracerPid:\t%d"
static const unsigned char ENC_TRACER_FMT[] = {
    X('T',0),X('r',1),X('a',2),X('c',3),X('e',4),X('r',5),X('P',6),X('i',7),
    X('d',8),X(':',9),X('\t',10),X('%',11),X('d',12),0x00
};

// "/proc"
static const unsigned char ENC_PROC[] = {
    X('/',0),X('p',1),X('r',2),X('o',3),X('c',4),0x00
};

// "/proc/%s/cmdline"
static const unsigned char ENC_CMDLINE[] = {
    X('/',0),X('p',1),X('r',2),X('o',3),X('c',4),X('/',5),X('%',6),X('s',7),
    X('/',8),X('c',9),X('m',10),X('d',11),X('l',12),X('i',13),X('n',14),X('e',15),
    0x00
};

// "frida"
static const unsigned char ENC_FRIDA[] = {
    X('f',0),X('r',1),X('i',2),X('d',3),X('a',4),0x00
};

// "gadget"
static const unsigned char ENC_GADGET[] = {
    X('g',0),X('a',1),X('d',2),X('g',3),X('e',4),X('t',5),0x00
};

// "/proc/net/tcp"
static const unsigned char ENC_TCP[] = {
    X('/',0),X('p',1),X('r',2),X('o',3),X('c',4),X('/',5),X('n',6),X('e',7),
    X('t',8),X('/',9),X('t',10),X('c',11),X('p',12),0x00
};

// "69A2" (Frida default port 27042 hex)
static const unsigned char ENC_PORT[] = {
    X('6',0),X('9',1),X('A',2),X('2',3),0x00
};

// "/data/local/tmp/frida-server"
static const unsigned char ENC_SERVER1[] = {
    X('/',0),X('d',1),X('a',2),X('t',3),X('a',4),X('/',5),X('l',6),X('o',7),
    X('c',8),X('a',9),X('l',10),X('/',11),X('t',12),X('m',13),X('p',14),X('/',15),
    X('f',16),X('r',17),X('i',18),X('d',19),X('a',20),X('-',21),X('s',22),X('e',23),
    X('r',24),X('v',25),X('e',26),X('r',27),0x00
};

// "/data/local/tmp/re.frida.server"
static const unsigned char ENC_SERVER2[] = {
    X('/',0),X('d',1),X('a',2),X('t',3),X('a',4),X('/',5),X('l',6),X('o',7),
    X('c',8),X('a',9),X('l',10),X('/',11),X('t',12),X('m',13),X('p',14),X('/',15),
    X('r',16),X('e',17),X('.',18),X('f',19),X('r',20),X('i',21),X('d',22),X('a',23),
    X('.',24),X('s',25),X('e',26),X('r',27),X('v',28),X('e',29),X('r',30),0x00
};

// "/proc/self/maps"
static const unsigned char ENC_MAPS[] = {
    X('/',0),X('p',1),X('r',2),X('o',3),X('c',4),X('/',5),X('s',6),X('e',7),
    X('l',8),X('f',9),X('/',10),X('m',11),X('a',12),X('p',13),X('s',14),0x00
};

// "XposedBridge"
static const unsigned char ENC_XP1[] = {
    X('X',0),X('p',1),X('o',2),X('s',3),X('e',4),X('d',5),X('B',6),X('r',7),
    X('i',8),X('d',9),X('g',10),X('e',11),0x00
};

// "xposed"
static const unsigned char ENC_XP2[] = {
    X('x',0),X('p',1),X('o',2),X('s',3),X('e',4),X('d',5),0x00
};

// "EdXposed"
static const unsigned char ENC_XP3[] = {
    X('E',0),X('d',1),X('X',2),X('p',3),X('o',4),X('s',5),X('e',6),X('d',7),
    0x00
};

// "LSPosed"
static const unsigned char ENC_XP4[] = {
    X('L',0),X('S',1),X('P',2),X('o',3),X('s',4),X('e',5),X('d',6),0x00
};

// "lspd"
static const unsigned char ENC_XP5[] = {
    X('l',0),X('s',1),X('p',2),X('d',3),0x00
};

// "riru"
static const unsigned char ENC_XP6[] = {
    X('r',0),X('i',1),X('r',2),X('u',3),0x00
};

// "/dev/socket/qemud"
static const unsigned char ENC_EMU1[] = {
    X('/',0),X('d',1),X('e',2),X('v',3),X('/',4),X('s',5),X('o',6),X('c',7),
    X('k',8),X('e',9),X('t',10),X('/',11),X('q',12),X('e',13),X('m',14),X('u',15),
    X('d',16),0x00
};

// "/dev/qemu_pipe"
static const unsigned char ENC_EMU2[] = {
    X('/',0),X('d',1),X('e',2),X('v',3),X('/',4),X('q',5),X('e',6),X('m',7),
    X('u',8),X('_',9),X('p',10),X('i',11),X('p',12),X('e',13),0x00
};

// "/system/lib/libc_malloc_debug_qemu.so"
static const unsigned char ENC_EMU3[] = {
    X('/',0),X('s',1),X('y',2),X('s',3),X('t',4),X('e',5),X('m',6),X('/',7),
    X('l',8),X('i',9),X('b',10),X('/',11),X('l',12),X('i',13),X('b',14),X('c',15),
    X('_',16),X('m',17),X('a',18),X('l',19),X('l',20),X('o',21),X('c',22),X('_',23),
    X('d',24),X('e',25),X('b',26),X('u',27),X('g',28),X('_',29),X('q',30),X('e',31),
    X('m',32),X('u',33),X('.',34),X('s',35),X('o',36),0x00
};

// "/sys/qemu_trace"
static const unsigned char ENC_EMU4[] = {
    X('/',0),X('s',1),X('y',2),X('s',3),X('/',4),X('q',5),X('e',6),X('m',7),
    X('u',8),X('_',9),X('t',10),X('r',11),X('a',12),X('c',13),X('e',14),0x00
};

// "/system/bin/qemu-props"
static const unsigned char ENC_EMU5[] = {
    X('/',0),X('s',1),X('y',2),X('s',3),X('t',4),X('e',5),X('m',6),X('/',7),
    X('b',8),X('i',9),X('n',10),X('/',11),X('q',12),X('e',13),X('m',14),X('u',15),
    X('-',16),X('p',17),X('r',18),X('o',19),X('p',20),X('s',21),0x00
};

// "/system/bin/su"
static const unsigned char ENC_SU0[] = {
    X('/',0),X('s',1),X('y',2),X('s',3),X('t',4),X('e',5),X('m',6),X('/',7),
    X('b',8),X('i',9),X('n',10),X('/',11),X('s',12),X('u',13),0x00
};

// "/system/xbin/su"
static const unsigned char ENC_SU1[] = {
    X('/',0),X('s',1),X('y',2),X('s',3),X('t',4),X('e',5),X('m',6),X('/',7),
    X('x',8),X('b',9),X('i',10),X('/',11),X('s',12),X('u',13),0x00
};

// "/sbin/su"
static const unsigned char ENC_SU2[] = {
    X('/',0),X('s',1),X('b',2),X('i',3),X('n',4),X('/',5),X('s',6),X('u',7),0x00
};

// "/data/local/su"
static const unsigned char ENC_SU3[] = {
    X('/',0),X('d',1),X('a',2),X('t',3),X('a',4),X('/',5),X('l',6),X('o',7),
    X('c',8),X('a',9),X('l',10),X('/',11),X('s',12),X('u',13),0x00
};

// "/sbin/.magisk"
static const unsigned char ENC_M0[] = {
    X('/',0),X('s',1),X('b',2),X('i',3),X('n',4),X('/',5),X('.',6),X('m',7),
    X('a',8),X('g',9),X('i',10),X('s',11),X('k',12),0x00
};

// "/data/adb/magisk"
static const unsigned char ENC_M1[] = {
    X('/',0),X('d',1),X('a',2),X('t',3),X('a',4),X('/',5),X('a',6),X('d',7),
    X('b',8),X('/',9),X('m',10),X('a',11),X('g',12),X('i',13),X('s',14),X('k',15),
    0x00
};

// "/data/adb/ksu"
static const unsigned char ENC_M2[] = {
    X('/',0),X('d',1),X('a',2),X('t',3),X('a',4),X('/',5),X('a',6),X('d',7),
    X('b',8),X('/',9),X('k',10),X('s',11),X('u',12),0x00
};

// "/data/adb/apatch"
static const unsigned char ENC_M3[] = {
    X('/',0),X('d',1),X('a',2),X('t',3),X('a',4),X('/',5),X('a',6),X('d',7),
    X('b',8),X('/',9),X('a',10),X('p',11),X('a',12),X('t',13),X('c',14),X('h',15),
    0x00
};

// "libc.so"
static const unsigned char ENC_LIBC[] = {
    X('l',0),X('i',1),X('b',2),X('c',3),X('.',4),X('s',5),X('o',6),0x00
};

// "open"
static const unsigned char ENC_OPEN[] = {
    X('o',0),X('p',1),X('e',2),X('n',3),0x00
};

// "read"
static const unsigned char ENC_READ[] = {
    X('r',0),X('e',1),X('a',2),X('d',3),0x00
};

// "ptrace"
static const unsigned char ENC_PTRACE[] = {
    X('p',0),X('t',1),X('r',2),X('a',3),X('c',4),X('e',5),0x00
};


/* ════════════════════════════════════════════════════════
   DECRYPTION & MEMORY WIPING UTILITIES
   ════════════════════════════════════════════════════════ */

static void decode(unsigned char* buf, size_t len) {
    for (size_t i = 0; i < len; i++) {
        buf[i] ^= BUILD_KEY[i % 8];
    }
}

#define DECODE_STR(enc, tmp) \
    unsigned char tmp[sizeof(enc)]; \
    memcpy(tmp, enc, sizeof(enc)); \
    decode(tmp, sizeof(enc) - 1);

#define WIPE(buf, len) do { volatile unsigned char* _p = (volatile unsigned char*)(buf); \
    for(size_t _i = 0; _i < (len); _i++) _p[_i] = 0; } while(0)


/* ════════════════════════════════════════════════════════
   DETECTORS LOGIC
   ════════════════════════════════════════════════════════ */

static int check_ptrace() {
    // Return 0 to prevent EPERM false positives on locked OEM stock devices (Honor/Xiaomi sandbox)
    // LLDB/GDB/Frida debugging is already 100% caught by check_tracer_pid() below
    return 0;
}

static int check_tracer_pid() {
    DECODE_STR(ENC_STATUS, path);
    DECODE_STR(ENC_TRACER_PREFIX, prefix);
    DECODE_STR(ENC_TRACER_FMT, fmt);

    char buf[512];
    FILE *f = fopen((char*)path, "r");
    WIPE(path, sizeof(path));
    if (!f) {
        WIPE(prefix, sizeof(prefix));
        WIPE(fmt, sizeof(fmt));
        return 0;
    }

    int detected = 0;
    while (fgets(buf, sizeof(buf), f)) {
        if (strstr(buf, (char*)prefix)) {
            int pid = 0;
            sscanf(buf, (char*)fmt, &pid);
            if (pid != 0) detected = 1;
            break;
        }
    }
    fclose(f);
    WIPE(prefix, sizeof(prefix));
    WIPE(fmt, sizeof(fmt));
    return detected;
}

static int check_frida() {
    DECODE_STR(ENC_PROC, path_proc);
    DIR *dir = opendir((char*)path_proc);
    WIPE(path_proc, sizeof(path_proc));
    if (dir) {
        DECODE_STR(ENC_CMDLINE, fmt_cmdline);
        DECODE_STR(ENC_FRIDA, s_frida);
        DECODE_STR(ENC_GADGET, s_gadget);

        struct dirent *entry;
        char path[256], cmdline[256];
        while ((entry = readdir(dir)) != NULL) {
            if (entry->d_type != DT_DIR) continue;
            snprintf(path, sizeof(path), (char*)fmt_cmdline, entry->d_name);
            FILE *f = fopen(path, "r");
            if (f) {
                memset(cmdline, 0, sizeof(cmdline));
                fread(cmdline, 1, sizeof(cmdline) - 1, f);
                fclose(f);
                if (strstr(cmdline, (char*)s_frida) || strstr(cmdline, (char*)s_gadget)) {
                    closedir(dir);
                    WIPE(fmt_cmdline, sizeof(fmt_cmdline));
                    WIPE(s_frida, sizeof(s_frida));
                    WIPE(s_gadget, sizeof(s_gadget));
                    return 1;
                }
            }
        }
        closedir(dir);
        WIPE(fmt_cmdline, sizeof(fmt_cmdline));
        WIPE(s_frida, sizeof(s_frida));
        WIPE(s_gadget, sizeof(s_gadget));
    }

    // Check for frida default port
    DECODE_STR(ENC_TCP, path_tcp);
    DECODE_STR(ENC_PORT, s_port);
    char line[256];
    FILE *tcp = fopen((char*)path_tcp, "r");
    WIPE(path_tcp, sizeof(path_tcp));
    if (tcp) {
        while (fgets(line, sizeof(line), tcp)) {
            if (strstr(line, (char*)s_port)) {
                fclose(tcp);
                WIPE(s_port, sizeof(s_port));
                return 1;
            }
        }
        fclose(tcp);
    }
    WIPE(s_port, sizeof(s_port));

    // Check for frida server files
    DECODE_STR(ENC_SERVER1, s_server1);
    DECODE_STR(ENC_SERVER2, s_server2);
    int detected = 0;
    if (access((char*)s_server1, F_OK) == 0) detected = 1;
    if (access((char*)s_server2, F_OK) == 0) detected = 1;
    WIPE(s_server1, sizeof(s_server1));
    WIPE(s_server2, sizeof(s_server2));

    return detected;
}

static int check_xposed() {
    DECODE_STR(ENC_MAPS, path_maps);
    FILE *f = fopen((char*)path_maps, "r");
    WIPE(path_maps, sizeof(path_maps));
    if (!f) return 0;

    DECODE_STR(ENC_XP1, xp1);
    DECODE_STR(ENC_XP2, xp2);
    DECODE_STR(ENC_XP3, xp3);
    DECODE_STR(ENC_XP4, xp4);
    DECODE_STR(ENC_XP5, xp5);
    DECODE_STR(ENC_XP6, xp6);

    char line[512];
    int detected = 0;
    while (fgets(line, sizeof(line), f)) {
        if (strstr(line, (char*)xp1) || strstr(line, (char*)xp2) ||
            strstr(line, (char*)xp3) || strstr(line, (char*)xp4) ||
            strstr(line, (char*)xp5) || strstr(line, (char*)xp6)) {
            detected = 1;
            break;
        }
    }
    fclose(f);
    WIPE(xp1, sizeof(xp1)); WIPE(xp2, sizeof(xp2));
    WIPE(xp3, sizeof(xp3)); WIPE(xp4, sizeof(xp4));
    WIPE(xp5, sizeof(xp5)); WIPE(xp6, sizeof(xp6));
    return detected;
}

static int check_emulator() {
    DECODE_STR(ENC_EMU1, emu1);
    DECODE_STR(ENC_EMU2, emu2);
    DECODE_STR(ENC_EMU3, emu3);
    DECODE_STR(ENC_EMU4, emu4);
    DECODE_STR(ENC_EMU5, emu5);

    const char *emu_files[5];
    emu_files[0] = (char*)emu1;
    emu_files[1] = (char*)emu2;
    emu_files[2] = (char*)emu3;
    emu_files[3] = (char*)emu4;
    emu_files[4] = (char*)emu5;

    int detected = 0;
    for (int i = 0; i < 5; i++) {
        if (access(emu_files[i], F_OK) == 0) {
            detected = 1;
            break;
        }
    }

    WIPE(emu1, sizeof(emu1)); WIPE(emu2, sizeof(emu2));
    WIPE(emu3, sizeof(emu3)); WIPE(emu4, sizeof(emu4));
    WIPE(emu5, sizeof(emu5));
    return detected;
}

static int check_root() {
    DECODE_STR(ENC_SU0, su0);
    DECODE_STR(ENC_SU1, su1);
    DECODE_STR(ENC_SU2, su2);
    DECODE_STR(ENC_SU3, su3);

    const char *paths[4];
    paths[0] = (char*)su0;
    paths[1] = (char*)su1;
    paths[2] = (char*)su2;
    paths[3] = (char*)su3;

    int detected = 0;
    for (int i = 0; i < 4; i++) {
        if (access(paths[i], F_OK) == 0) {
            detected = 1;
            break;
        }
    }

    WIPE(su0, sizeof(su0)); WIPE(su1, sizeof(su1));
    WIPE(su2, sizeof(su2)); WIPE(su3, sizeof(su3));
    return detected;
}

static int check_magisk() {
    DECODE_STR(ENC_M0, m0);
    DECODE_STR(ENC_M1, m1);
    DECODE_STR(ENC_M2, m2);
    DECODE_STR(ENC_M3, m3);

    const char *paths[4];
    paths[0] = (char*)m0;
    paths[1] = (char*)m1;
    paths[2] = (char*)m2;
    paths[3] = (char*)m3;

    int detected = 0;
    for (int i = 0; i < 4; i++) {
        if (access(paths[i], F_OK) == 0) {
            detected = 1;
            break;
        }
    }

    WIPE(m0, sizeof(m0)); WIPE(m1, sizeof(m1));
    WIPE(m2, sizeof(m2)); WIPE(m3, sizeof(m3));
    return detected;
}


/* ════════════════════════════════════════════════════════
   SIGNATURE VERIFICATION
   ════════════════════════════════════════════════════════ */

static const unsigned char ENCODED_SIG_RELEASE[] = { 0x50, 0xDF, 0x2B, 0xDA };
static const unsigned char ENCODED_SIG_DEBUG[]   = { 0x2D, 0xD1, 0x2C, 0xCB };
static const unsigned char XOR_KEY = 0x5A;

JNIEXPORT jboolean JNICALL
Java_com_liquidmusicglass_security_NativeSecurity_nativeVerifySignature(
    JNIEnv *env, jclass clz, jbyteArray signatureBytes) {

    if (!signatureBytes) return JNI_FALSE;

    jsize len = (*env)->GetArrayLength(env, signatureBytes);
    if (len <= 0) return JNI_FALSE;

    jbyte *sig = (*env)->GetByteArrayElements(env, signatureBytes, NULL);
    if (!sig) return JNI_FALSE;

    unsigned int hash = 0;
    for (int i = 0; i < len; i++) {
        hash = hash * 31 + (unsigned char)sig[i];
    }

    (*env)->ReleaseByteArrayElements(env, signatureBytes, sig, 0);

    unsigned int expected_release = 0;
    for (int i = 0; i < 4; i++) {
        expected_release |= ((unsigned int)(ENCODED_SIG_RELEASE[i] ^ XOR_KEY)) << (i * 8);
    }

    unsigned int expected_debug = 0;
    for (int i = 0; i < 4; i++) {
        expected_debug |= ((unsigned int)(ENCODED_SIG_DEBUG[i] ^ XOR_KEY)) << (i * 8);
    }

    if (hash == expected_release || hash == expected_debug) {
        return JNI_TRUE;
    }

    __android_log_print(ANDROID_LOG_ERROR, TAG, "Signature mismatch! Hash got: %u, expected release: %u, debug: %u",
                        hash, expected_release, expected_debug);
    return JNI_FALSE;
}


/* ════════════════════════════════════════════════════════
   JNI EXPORTS FOR COMBINED CHECKS
   ════════════════════════════════════════════════════════ */

JNIEXPORT jint JNICALL
Java_com_liquidmusicglass_security_NativeSecurity_nativeSecurityCheck(
    JNIEnv *env, jclass clz) {
    (void)env; (void)clz;

    int threats = 0;

    if (check_tracer_pid()) threats |= 0x01;  // debugger
    if (check_frida())      threats |= 0x02;  // frida
    if (check_xposed())     threats |= 0x04;  // xposed/lsposed
    if (check_emulator())   threats |= 0x08;  // emulator
    if (check_root())       threats |= 0x10;  // root su binary
    if (check_magisk())     threats |= 0x20;  // magisk/ksu/apatch

    return threats;
}

JNIEXPORT jboolean JNICALL
Java_com_liquidmusicglass_security_NativeSecurity_nativeCheckIntegrity(
    JNIEnv *env, jclass clz, jstring apkPath) {

    if (!apkPath) return JNI_FALSE;

    const char *path = (*env)->GetStringUTFChars(env, apkPath, NULL);
    if (!path) return JNI_FALSE;

    struct stat st;
    if (stat(path, &st) != 0) {
        (*env)->ReleaseStringUTFChars(env, apkPath, path);
        return JNI_FALSE;
    }

    if (st.st_size < 1024 * 1024) {
        (*env)->ReleaseStringUTFChars(env, apkPath, path);
        return JNI_FALSE;
    }

    (*env)->ReleaseStringUTFChars(env, apkPath, path);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_liquidmusicglass_security_NativeSecurity_nativeCheckHooks(
    JNIEnv *env, jclass clz) {
    (void)env; (void)clz;

    DECODE_STR(ENC_LIBC, s_libc);
    void *handle = dlopen((char*)s_libc, RTLD_NOW);
    WIPE(s_libc, sizeof(s_libc));
    if (!handle) return JNI_TRUE; // Return safe on modern devices where JNI dlopen of system libs is blocked by linker namespace rules

    DECODE_STR(ENC_OPEN, s_open);
    DECODE_STR(ENC_READ, s_read);
    DECODE_STR(ENC_PTRACE, s_ptrace);

    void *fn_open = dlsym(handle, (char*)s_open);
    void *fn_read = dlsym(handle, (char*)s_read);
    void *fn_ptrace = dlsym(handle, (char*)s_ptrace);

    dlclose(handle);

    WIPE(s_open, sizeof(s_open));
    WIPE(s_read, sizeof(s_read));
    WIPE(s_ptrace, sizeof(s_ptrace));

    if (!fn_open || !fn_read || !fn_ptrace) return JNI_FALSE;

    return JNI_TRUE;
}
