package com.liquidmusicglass.data.local

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64

/**
 * Local authentication manager — stores credentials securely using Android Keystore.
 * This is a temporary solution until a backend is available.
 *
 * Passwords are encrypted with AES-256-GCM via hardware-backed keystore when possible.
 */
object LocalAuthManager {

    private const val PREFS_NAME = "local_auth"
    private const val KEY_ALIAS = "liquid_auth_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128

    private const val KEY_EMAIL = "auth_email"
    private const val KEY_PASSWORD_ENC = "auth_password_enc"
    private const val KEY_IV = "auth_iv"
    private const val KEY_IS_VERIFIED = "auth_verified"
    private const val KEY_CREATED_AT = "auth_created_at"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        ensureKeyExists()
    }

    private fun ensureKeyExists() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            keyGenerator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
            keyGenerator.generateKey()
        }
    }

    private fun getSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }

    private fun encrypt(plaintext: String): Pair<String, String> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val encrypted = Base64.encodeToString(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        return iv to encrypted
    }

    private fun decrypt(iv: String, encrypted: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val ivBytes = Base64.decode(iv, Base64.NO_WRAP)
        cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), GCMParameterSpec(GCM_TAG_LENGTH, ivBytes))
        val encryptedBytes = Base64.decode(encrypted, Base64.NO_WRAP)
        return String(cipher.doFinal(encryptedBytes), Charsets.UTF_8)
    }

    /**
     * Register a new account. Returns false if email already exists.
     */
    fun register(email: String, password: String): Boolean {
        val p = prefs ?: return false
        val normalizedEmail = email.trim().lowercase()
        if (p.getString(KEY_EMAIL, null) == normalizedEmail) {
            return false // Already exists
        }
        val (iv, enc) = encrypt(password)
        p.edit {
            putString(KEY_EMAIL, normalizedEmail)
            putString(KEY_PASSWORD_ENC, enc)
            putString(KEY_IV, iv)
            putBoolean(KEY_IS_VERIFIED, false)
            putLong(KEY_CREATED_AT, System.currentTimeMillis())
        }
        return true
    }

    /**
     * Login with email and password. Returns true if credentials match.
     */
    fun login(email: String, password: String): Boolean {
        val p = prefs ?: return false
        val normalizedEmail = email.trim().lowercase()
        val storedEmail = p.getString(KEY_EMAIL, null) ?: return false
        if (storedEmail != normalizedEmail) return false

        val enc = p.getString(KEY_PASSWORD_ENC, null) ?: return false
        val iv = p.getString(KEY_IV, null) ?: return false
        return try {
            val decrypted = decrypt(iv, enc)
            decrypted == password
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Check if user is logged in.
     */
    fun isLoggedIn(): Boolean {
        return prefs?.getString(KEY_EMAIL, null) != null
    }

    /**
     * Get current user email.
     */
    fun getEmail(): String? {
        return prefs?.getString(KEY_EMAIL, null)
    }

    /**
     * Check if email is verified.
     */
    fun isVerified(): Boolean {
        return prefs?.getBoolean(KEY_IS_VERIFIED, false) ?: false
    }

    /**
     * Mark email as verified.
     */
    fun verifyEmail() {
        prefs?.edit { putBoolean(KEY_IS_VERIFIED, true) }
    }

    /**
     * Reset password. Returns true if email exists.
     */
    fun resetPassword(email: String, newPassword: String): Boolean {
        val p = prefs ?: return false
        val normalizedEmail = email.trim().lowercase()
        if (p.getString(KEY_EMAIL, null) != normalizedEmail) return false
        val (iv, enc) = encrypt(newPassword)
        p.edit {
            putString(KEY_PASSWORD_ENC, enc)
            putString(KEY_IV, iv)
        }
        return true
    }

    /**
     * Logout — clear all auth data.
     */
    fun logout() {
        prefs?.edit { clear() }
    }
}
