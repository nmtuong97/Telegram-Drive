package com.nmtuong.telegramdrive.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import android.util.Base64

class DatabaseEncryptionManager(private val context: Context) {

    companion object {
        private const val PREF_FILE_NAME = "tdlib_encryption_prefs"
        private const val KEY_DATABASE_ENCRYPTION_KEY = "database_encryption_key"
    }

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val sharedPreferences: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            context,
            PREF_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Gets the database encryption key. If it doesn't exist, generates a new one securely.
     */
    fun getOrGenerateKey(): String {
        var key = sharedPreferences.getString(KEY_DATABASE_ENCRYPTION_KEY, null)
        if (key.isNullOrEmpty()) {
            key = generateSecureKey()
            sharedPreferences.edit().putString(KEY_DATABASE_ENCRYPTION_KEY, key).apply()
        }
        return key
    }

    /**
     * Deletes the encryption key completely (e.g., during account reset).
     */
    fun clearKey() {
        sharedPreferences.edit().remove(KEY_DATABASE_ENCRYPTION_KEY).apply()
    }

    private fun generateSecureKey(): String {
        val bytes = ByteArray(32) // 256-bit key
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
    }
}
