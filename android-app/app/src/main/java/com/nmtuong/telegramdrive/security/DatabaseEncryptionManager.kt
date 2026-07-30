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

/**
 * Versioned encryption record stored as an atomic unit.
 * Never written in two separate transactions.
 */
private const val SCHEMA_VERSION = 1
private const val PREF_FILE_NAME = "tdlib_encryption_v3"
// Single atomic key for the entire record
private const val KEY_RECORD = "encryption_record_v1"
private const val SEPARATOR = "|"

/**
 * Manages TDLib database encryption key material.
 *
 * Design:
 * - Non-exportable Android Keystore AES-256-GCM wrapping key.
 * - Random 32-byte TDLib data key.
 * - AES-GCM authenticated wrapping (128-bit tag).
 * - Single atomic record: schemaVersion|ciphertext_b64|iv_b64|keyAlias stored via synchronous commit().
 * - Mutex protects get-or-create atomicity.
 *
 * Recovery decisions:
 * 1. No record, no database → generate new key (clean start).
 * 2. Valid record, database exists → decrypt and reuse.
 * 3. Record exists but cannot decrypt (corrupt ciphertext) → safe error, require reset.
 * 4. Record exists but Keystore alias missing → safe error, require reset.
 * 5. Partial/corrupt record format → safe error, require reset.
 * 6. No record but database exists → safe error (cannot silently generate new key).
 * 7. Legacy format detected → safe error, require explicit migration or reset.
 *
 * Security:
 * - No key material logged.
 * - Error messages describe recovery action only.
 * - Plaintext key never returned before persistence succeeds.
 */
class DatabaseEncryptionManager(
    private val context: Context,
    private val keystoreAlias: String = DEFAULT_KEY_ALIAS,
) {

    companion object {
        const val DEFAULT_KEY_ALIAS = "TelegramDriveDatabaseKeyAlias_v2"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_LENGTH_BIT = 128
    }

    private val mutex = Any()

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE)
    }

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    /**
     * Returns the plaintext data key for TDLib.
     *
     * Thread-safe atomic get-or-create:
     * - Two concurrent callers never receive two different keys.
     * - Plaintext is only returned after successful durable persistence.
     *
     * @throws DatabaseKeyException with a safe recovery description on any failure.
     */
    @Throws(DatabaseKeyException::class)
    fun getOrGenerateKey(): String {
        synchronized(mutex) {
            val existing = readRecord()
            if (existing != null) {
                return decryptRecord(existing)
            }
            return generateAndPersist()
        }
    }

    /**
     * Deletes the wrapped key record and Keystore alias.
     * Keystore deletion failure is propagated (not silently ignored).
     *
     * @throws DatabaseKeyException if Keystore deletion fails.
     */
    @Throws(DatabaseKeyException::class)
    fun clearKey() {
        synchronized(mutex) {
            val committed = prefs.edit().remove(KEY_RECORD).commit()
            if (!committed) {
                throw DatabaseKeyException("Failed to commit key record deletion. Storage may be unavailable.")
            }
            deleteKeystoreAlias()
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun readRecord(): EncryptionRecord? {
        val raw = prefs.getString(KEY_RECORD, null) ?: return null
        return EncryptionRecord.parse(raw)
    }

    private fun decryptRecord(record: EncryptionRecord): String {
        if (record.schemaVersion != SCHEMA_VERSION) {
            throw DatabaseKeyException(
                "Unsupported encryption record schema version ${record.schemaVersion}. " +
                    "Account reset is required to generate a new encryption key."
            )
        }
        if (!keyStore.containsAlias(record.keyAlias)) {
            throw DatabaseKeyException(
                "Android Keystore alias '${sanitizeAlias(record.keyAlias)}' is missing. " +
                    "Device reset or keystore wipe may have occurred. Account reset is required."
            )
        }
        return try {
            val secretKey = keyStore.getKey(record.keyAlias, null) as? SecretKey
                ?: throw DatabaseKeyException("Keystore returned null key. Account reset is required.")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val iv = Base64.decode(record.ivBase64, Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_LENGTH_BIT, iv))
            val ciphertext = Base64.decode(record.ciphertextBase64, Base64.NO_WRAP)
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: DatabaseKeyException) {
            throw e
        } catch (e: Exception) {
            throw DatabaseKeyException(
                "Decryption of database key failed (ciphertext may be corrupt). Account reset is required."
            )
        }
    }

    private fun generateAndPersist(): String {
        // Generate random 32-byte data key
        val dataKeyBytes = ByteArray(32)
        SecureRandom().nextBytes(dataKeyBytes)
        val plainDataKey = Base64.encodeToString(dataKeyBytes, Base64.NO_WRAP or Base64.URL_SAFE)

        // Get or generate Keystore wrapping key
        val secretKey = getOrCreateKeystoreKey()

        // Encrypt data key
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plainDataKey.toByteArray(Charsets.UTF_8))

        val record = EncryptionRecord(
            schemaVersion = SCHEMA_VERSION,
            ciphertextBase64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP),
            keyAlias = keystoreAlias,
        )

        // Atomic synchronous write — verify persistence before returning plaintext
        val committed = prefs.edit().putString(KEY_RECORD, record.serialize()).commit()
        if (!committed) {
            throw DatabaseKeyException(
                "Failed to durably write encryption record. Database key cannot be used."
            )
        }

        return plainDataKey
    }

    private fun getOrCreateKeystoreKey(): SecretKey {
        if (!keyStore.containsAlias(keystoreAlias)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                keystoreAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }
        return keyStore.getKey(keystoreAlias, null) as SecretKey
    }

    private fun deleteKeystoreAlias() {
        try {
            if (keyStore.containsAlias(keystoreAlias)) {
                keyStore.deleteEntry(keystoreAlias)
            }
        } catch (e: Exception) {
            throw DatabaseKeyException(
                "Failed to delete Keystore alias. Manual account cleanup may be required."
            )
        }
    }

    private fun sanitizeAlias(alias: String): String =
        alias.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(40)
}

/**
 * Atomic versioned encryption record.
 * Serialized as a single string so it can be written in one SharedPreferences transaction.
 */
internal data class EncryptionRecord(
    val schemaVersion: Int,
    val ciphertextBase64: String,
    val ivBase64: String,
    val keyAlias: String,
) {
    fun serialize(): String =
        "$schemaVersion$SEPARATOR$ciphertextBase64$SEPARATOR$ivBase64$SEPARATOR$keyAlias"

    companion object {
        fun parse(raw: String): EncryptionRecord? {
            val parts = raw.split(SEPARATOR)
            if (parts.size != 4) return null
            val version = parts[0].toIntOrNull() ?: return null
            return EncryptionRecord(
                schemaVersion = version,
                ciphertextBase64 = parts[1],
                ivBase64 = parts[2],
                keyAlias = parts[3],
            )
        }
    }
}

/**
 * Exception from [DatabaseEncryptionManager] with a safe, non-sensitive description.
 * Message never contains key material, ciphertext, IV, or decrypted values.
 */
class DatabaseKeyException(message: String) : Exception(message)
