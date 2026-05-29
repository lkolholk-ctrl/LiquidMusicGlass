package com.liquidmusicglass.engine

import android.os.Build
import com.liquidmusicglass.security.NativeSecurity
import java.io.File

/**
 * Utility class for advanced native and Java security checks.
 */
object SecurityUtils {

    /**
     * Check for root access. Combines native JNI checks with Java path checks.
     */
    fun isDeviceRooted(): Boolean {
        // 1. Native C++ check (checks for SU binaries, Magisk, KernelSU, APatch)
        val nativeRoot = try {
            val threats = NativeSecurity.nativeSecurityCheck()
            (threats and 0x01) != 0 || (threats and 0x04) != 0
        } catch (_: Throwable) {
            false
        }

        if (nativeRoot) return true

        // 2. Fallback basic check for root access paths
        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su"
        )
        for (path in paths) {
            if (File(path).exists()) return true
        }
        
        val buildTags = Build.TAGS
        if (buildTags != null && buildTags.contains("test-keys")) return true
        
        return false
    }

    /**
     * Check for emulator detection. Combines native hardware scans with build properties.
     */
    fun isEmulator(): Boolean {
        // 1. Native C++ Emulator device scan
        val nativeEmu = try {
            val threats = NativeSecurity.nativeSecurityCheck()
            (threats and 0x08) != 0
        } catch (_: Throwable) {
            false
        }
        if (nativeEmu) return true

        // 2. Fallback basic check for emulator detection properties
        return (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                || Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.HARDWARE.contains("goldfish")
                || Build.HARDWARE.contains("ranchu")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || Build.PRODUCT.contains("sdk_google")
                || Build.PRODUCT.contains("google_sdk")
                || Build.PRODUCT.contains("sdk")
                || Build.PRODUCT.contains("sdk_x86")
                || Build.PRODUCT.contains("vbox86p")
                || Build.PRODUCT.contains("emulator")
                || Build.PRODUCT.contains("simulator")
    }

    /**
     * Checks if the environment is fully safe (no Frida, no Xposed, no hooks, no debuggers, valid signature).
     * Wrapped in robust try-catch blocks to guarantee the app keeps working in case of NDK/JNI errors.
     */
    fun isEnvironmentSafe(context: android.content.Context): Boolean {
        return try {
            // A. Check for system function hooks (Frida dynamic hooking)
            if (!NativeSecurity.nativeCheckHooks()) return false

            // B. Check for combined threats (Frida, Xposed/LSPosed, Debuggers, Emulators)
            val threats = NativeSecurity.nativeSecurityCheck()
            if (threats != 0) return false

            // C. Check signature validity against expected hash
            val pm = context.packageManager
            val info = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNATURES)
            }
            val signatures = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                info.signatures
            }
            if (signatures != null && signatures.isNotEmpty()) {
                val sigBytes = signatures[0].toByteArray()
                if (!NativeSecurity.nativeVerifySignature(sigBytes)) return false
            }

            // D. Check APK file size integrity
            val apkPath = context.packageCodePath
            if (apkPath != null) {
                if (!NativeSecurity.nativeCheckIntegrity(apkPath)) return false
            }

            true
        } catch (_: Throwable) {
            // Keep application functional if native library failed to load or execution encountered JNI issues
            true
        }
    }
}
