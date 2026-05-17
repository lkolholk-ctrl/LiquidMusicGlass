package com.liquidmusicglass.data.local

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64

/**
 * Local authentication manager — stores multiple user credentials securely using Android Keystore.
 *
 * Passwords are encrypted with AES-256-GCM via hardware-backed keystore when possible.
 */
object LocalAuthManager {

    private const val PREFS_NAME = "local_auth"
    private const val KEY_ALIAS = "liquid_auth_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128

    private const val KEY_USERS = "auth_users"
    private const val KEY_CURRENT_USER = "auth_current_user"

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

    private fun getUsers(): MutableList<UserData> {
        val p = prefs ?: return mutableListOf()
        val json = p.getString(KEY_USERS, "[]") ?: "[]"
        val array = JSONArray(json)
        val users = mutableListOf<UserData>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            users.add(UserData(
                email = obj.getString("email"),
                passwordEnc = obj.getString("password_enc"),
                iv = obj.getString("iv"),
                isVerified = obj.getBoolean("is_verified"),
                createdAt = obj.getLong("created_at")
            ))
        }
        return users
    }

    private fun saveUsers(users: List<UserData>) {
        val array = JSONArray()
        users.forEach { user ->
            val obj = JSONObject()
            obj.put("email", user.email)
            obj.put("password_enc", user.passwordEnc)
            obj.put("iv", user.iv)
            obj.put("is_verified", user.isVerified)
            obj.put("created_at", user.createdAt)
            array.put(obj)
        }
        prefs?.edit { putString(KEY_USERS, array.toString()) }
    }

    /**
     * Register a new account. Returns false if email already exists.
     */
    fun register(email: String, password: String): Boolean {
        val normalizedEmail = email.trim().lowercase()
        val users = getUsers()
        
        if (users.any { it.email == normalizedEmail }) {
            return false // Already exists
        }
        
        val (iv, enc) = encrypt(password)
        users.add(UserData(
            email = normalizedEmail,
            passwordEnc = enc,
            iv = iv,
            isVerified = false,
            createdAt = System.currentTimeMillis()
        ))
        saveUsers(users)
        return true
    }

    /**
     * Login with email and password. Returns true if credentials match.
     */
    fun login(email: String, password: String): Boolean {
        val normalizedEmail = email.trim().lowercase()
        val users = getUsers()
        val user = users.find { it.email == normalizedEmail } ?: return false

        return try {
            val decrypted = decrypt(user.iv, user.passwordEnc)
            if (decrypted == password) {
                prefs?.edit { putString(KEY_CURRENT_USER, normalizedEmail) }
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Check if user is logged in.
     */
    fun isLoggedIn(): Boolean {
        return prefs?.getString(KEY_CURRENT_USER, null) != null
    }

    /**
     * Get current user email.
     */
    fun getEmail(): String? {
        return prefs?.getString(KEY_CURRENT_USER, null)
    }

    /**
     * Check if email is verified.
     */
    fun isVerified(): Boolean {
        val email = getEmail() ?: return false
        val users = getUsers()
        return users.find { it.email == email }?.isVerified ?: false
    }

    /**
     * Mark email as verified.
     */
    fun verifyEmail() {
        val email = getEmail() ?: return
        val users = getUsers()
        val user = users.find { it.email == email } ?: return
        user.isVerified = true
        saveUsers(users)
    }

    /**
     * Reset password. Returns true if email exists.
     */
    fun resetPassword(email: String, newPassword: String): Boolean {
        val normalizedEmail = email.trim().lowercase()
        val users = getUsers()
        val user = users.find { it.email == normalizedEmail } ?: return false
        
        val (iv, enc) = encrypt(newPassword)
        user.passwordEnc = enc
        user.iv = iv
        saveUsers(users)
        return true
    }

    /**
     * Logout — clear current user.
     */
    fun logout() {
        prefs?.edit { remove(KEY_CURRENT_USER) }
    }

    private data class UserData(
        val email: String,
        var passwordEnc: String,
        var iv: String,
        var isVerified: Boolean,
        val createdAt: Long
    )
}
