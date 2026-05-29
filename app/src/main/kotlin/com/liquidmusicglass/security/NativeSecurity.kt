package com.liquidmusicglass.security

object NativeSecurity {
    init {
        System.loadLibrary("lmg_security")
    }

    /**
     * Runs combined native security checks.
     * Returns a bitmask of detected threats:
     * 0x00 = safe
     * 0x01 = debugger/ptrace active
     * 0x02 = Frida active (port/proc scan)
     * 0x04 = Xposed/LSPosed active
     * 0x08 = Emulator detected
     */
    external fun nativeSecurityCheck(): Int

    /**
     * Verifies that the app signature matches the expected hash.
     */
    external fun nativeVerifySignature(signatureBytes: ByteArray): Boolean

    /**
     * Checks the integrity of the APK file.
     */
    external fun nativeCheckIntegrity(apkPath: String): Boolean

    /**
     * Checks if libc functions have been hooked.
     */
    external fun nativeCheckHooks(): Boolean
}
