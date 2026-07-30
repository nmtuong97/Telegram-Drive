package com.nmtuong.telegramdrive.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class DatabaseEncryptionManager(private val context: Context) {

    companion object {
        private const val PREF_FILE_NAME = "tdlib_encryption_prefs_v2"
        private const val KEY_CIPHERTEXT = "database_encryption_key_ciphertext"
        private const val KEY_IV = "database_encryption_key_iv"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "TelegramDriveDatabaseKeyAlias"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_LENGTH_BIT = 128
    }

    private val sharedPreferences: SharedPreferences by lazy {
        context.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE)
    }

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    fun getOrGenerateKey(): String {
        val cipherTextB64 = sharedPreferences.getString(KEY_CIPHERTEXT, null)
        val ivB64 = sharedPreferences.getString(KEY_IV, null)

        if (cipherTextB64 != null && ivB64 != null) {
            val key = decryptKey(cipherTextB64, ivB64)
            if (key != null) return key
            throw IllegalStateException("Failed to decrypt existing database key. Account must be reset.")
        }

        // Generate new random data key
        val dataKeyBytes = ByteArray(32)
        SecureRandom().nextBytes(dataKeyBytes)
        val plainDataKeyB64 = Base64.encodeToString(dataKeyBytes, Base64.NO_WRAP or Base64.URL_SAFE)

        // Encrypt and persist
        encryptAndSaveKey(plainDataKeyB64)

        return plainDataKeyB64
    }

    fun clearKey() {
        sharedPreferences.edit()
            .remove(KEY_CIPHERTEXT)
            .remove(KEY_IV)
            .apply()
        
        try {
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
            }
        } catch (e: Exception) {
            // Ignore keystore deletion errors on reset
        }
    }

    private fun getKeystoreSecretKey(): SecretKey {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(keyGenParameterSpec)
            keyGenerator.generateKey()
        }
        return keyStore.getKey(KEY_ALIAS, null) as SecretKey
    }

    private fun encryptAndSaveKey(plainDataKey: String) {
        val secretKey = getKeystoreSecretKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plainDataKey.toByteArray(Charsets.UTF_8))

        sharedPreferences.edit()
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
            .apply()
    }

    private fun decryptKey(cipherTextB64: String, ivB64: String): String? {
        return try {
            if (!keyStore.containsAlias(KEY_ALIAS)) return null
            val secretKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey ?: return null
            
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            val spec = GCMParameterSpec(TAG_LENGTH_BIT, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            
            val ciphertext = Base64.decode(cipherTextB64, Base64.NO_WRAP)
            val plaintextBytes = cipher.doFinal(ciphertext)
            String(plaintextBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
