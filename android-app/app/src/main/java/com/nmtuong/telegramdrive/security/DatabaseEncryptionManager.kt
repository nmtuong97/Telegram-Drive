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

private const val SCHEMA_VERSION = 1
private const val PREF_FILE_NAME = "tdlib_encryption_v3"
private const val KEY_RECORD = "encryption_record_v1"
private const val KEY_CLEANUP_MARKER = "cleanup_in_progress_v1"
private const val SEPARATOR = "|"

/**
 * App-owned database state abstraction.
 */
data class DatabaseState(
    val exists: Boolean,
    val hasMeaningfulTdLibData: Boolean = false,
    val generation: Long = 1L,
)

/**
 * Explicit storage read result for the encryption record.
 * Avoids nullable records representing missing and corrupt states ambiguously.
 */
sealed interface EncryptionStorageResult {
    object Missing : EncryptionStorageResult
    data class Valid(val record: EncryptionRecord) : EncryptionStorageResult
    data class Corrupt(val reason: String) : EncryptionStorageResult
    data class UnsupportedVersion(val version: Int) : EncryptionStorageResult
    object LegacyDetected : EncryptionStorageResult
    data class StorageFailure(val reason: String) : EncryptionStorageResult
}

/**
 * Result of explicit key cleanup operation.
 */
sealed interface KeyClearResult {
    object Success : KeyClearResult
    data class PartialFailure(
        val recordDeleted: Boolean,
        val aliasDeleted: Boolean,
        val reason: String,
    ) : KeyClearResult
}

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
 * Matrix semantics:
 * 1. Missing record + no database -> Generate wrapping & data key, persist atomically. Plaintext only after durable success.
 * 2. Valid record + database -> Decrypt & reuse.
 * 3. Corrupt/partial record -> Do NOT generate key, require explicit reset.
 * 4. Database exists + record missing -> Do NOT generate key, require explicit reset.
 * 5. Record exists + Keystore alias missing -> Do NOT generate alias, require explicit reset.
 * 6. Record exists + database missing -> Decrypt or perform documented stale-state cleanup.
 * 7. Legacy format -> Detect, do NOT silently migrate with new key, require explicit reset.
 *
 * Security:
 * - No key material, ciphertext, or IV logged or exposed in exceptions.
 * - Error messages describe recovery action only.
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
     * @param databaseState App-owned abstraction of current database existence and state.
     * @throws DatabaseKeyException with safe recovery description on any failure.
     */
    @Throws(DatabaseKeyException::class)
    fun getOrGenerateKey(databaseState: DatabaseState = DatabaseState(exists = false)): String {
        synchronized(mutex) {
            checkCleanupMarker()

            return when (val storageResult = readStorageResult()) {
                is EncryptionStorageResult.Missing -> {
                    if (databaseState.exists || databaseState.hasMeaningfulTdLibData) {
                        throw DatabaseKeyException(
                            "Database exists but encryption record is missing. Explicit reset/recovery is required."
                        )
                    }
                    generateAndPersist()
                }
                is EncryptionStorageResult.Valid -> {
                    decryptRecord(storageResult.record)
                }
                is EncryptionStorageResult.Corrupt -> {
                    throw DatabaseKeyException(
                        "Encryption record is corrupt (${storageResult.reason}). Account reset is required."
                    )
                }
                is EncryptionStorageResult.UnsupportedVersion -> {
                    throw DatabaseKeyException(
                        "Unsupported encryption record schema version ${storageResult.version}. Account reset is required."
                    )
                }
                is EncryptionStorageResult.LegacyDetected -> {
                    throw DatabaseKeyException(
                        "Legacy encryption record format detected. Explicit reset is required."
                    )
                }
                is EncryptionStorageResult.StorageFailure -> {
                    throw DatabaseKeyException(
                        "Failed to read encryption storage: ${storageResult.reason}"
                    )
                }
            }
        }
    }

    /**
     * Deletes the wrapped key record and Keystore alias.
     * Idempotent cleanup with explicit result tracking.
     */
    @Throws(DatabaseKeyException::class)
    fun clearKey(): KeyClearResult {
        synchronized(mutex) {
            // Set cleanup marker first for idempotent startup resume
            prefs.edit().putBoolean(KEY_CLEANUP_MARKER, true).commit()

            var recordDeleted = false
            var aliasDeleted = false

            val removeCommitted = prefs.edit().remove(KEY_RECORD).commit()
            if (removeCommitted || !prefs.contains(KEY_RECORD)) {
                recordDeleted = true
            }

            try {
                deleteKeystoreAlias()
                aliasDeleted = true
            } catch (e: Exception) {
                aliasDeleted = !keyStore.containsAlias(keystoreAlias)
            }

            if (recordDeleted && aliasDeleted) {
                prefs.edit().remove(KEY_CLEANUP_MARKER).commit()
                return KeyClearResult.Success
            }

            val reason = when {
                !recordDeleted && !aliasDeleted -> "Failed to delete record and Keystore alias."
                !recordDeleted -> "Failed to delete encryption record from storage."
                else -> "Failed to delete Keystore alias."
            }

            throw DatabaseKeyException(
                "Incomplete key cleanup: $reason Account reset must be retried."
            )
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun checkCleanupMarker() {
        if (prefs.getBoolean(KEY_CLEANUP_MARKER, false)) {
            // Unfinished cleanup detected (e.g. process death mid-clear)
            throw DatabaseKeyException(
                "Unfinished key cleanup detected from previous session. Account reset is required."
            )
        }
    }

    private fun readStorageResult(): EncryptionStorageResult {
        return try {
            val raw = prefs.getString(KEY_RECORD, null)
            if (raw == null) {
                // Check if legacy pref keys exist
                if (prefs.contains("encryption_key_v1") || prefs.contains("encryption_record_v0")) {
                    EncryptionStorageResult.LegacyDetected
                } else {
                    EncryptionStorageResult.Missing
                }
            } else {
                EncryptionRecord.parse(raw)
            }
        } catch (e: Exception) {
            EncryptionStorageResult.StorageFailure(e.message ?: "Storage read error")
        }
    }

    private fun decryptRecord(record: EncryptionRecord): String {
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
        if (keyStore.containsAlias(keystoreAlias)) {
            keyStore.deleteEntry(keystoreAlias)
        }
    }

    private fun sanitizeAlias(alias: String): String =
        alias.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(40)
}

/**
 * Atomic versioned encryption record.
 * Serialized as a single string so it can be written in one SharedPreferences transaction.
 */
data class EncryptionRecord(
    val schemaVersion: Int,
    val ciphertextBase64: String,
    val ivBase64: String,
    val keyAlias: String,
) {
    fun serialize(): String =
        "$schemaVersion$SEPARATOR$ciphertextBase64$SEPARATOR$ivBase64$SEPARATOR$keyAlias"

    companion object {
        fun parse(raw: String): EncryptionStorageResult {
            if (raw.isBlank()) return EncryptionStorageResult.Corrupt("Blank record value")
            val parts = raw.split(SEPARATOR)
            if (parts.size != 4) return EncryptionStorageResult.Corrupt("Invalid segment count ${parts.size}")
            val version = parts[0].toIntOrNull() ?: return EncryptionStorageResult.Corrupt("Invalid version string ${parts[0]}")
            if (version > SCHEMA_VERSION) return EncryptionStorageResult.UnsupportedVersion(version)
            if (version < 1) return EncryptionStorageResult.LegacyDetected
            if (parts[1].isBlank() || parts[2].isBlank() || parts[3].isBlank()) {
                return EncryptionStorageResult.Corrupt("Record contains empty fields")
            }
            return EncryptionStorageResult.Valid(
                EncryptionRecord(
                    schemaVersion = version,
                    ciphertextBase64 = parts[1],
                    ivBase64 = parts[2],
                    keyAlias = parts[3],
                )
            )
        }
    }
}

/**
 * Exception from [DatabaseEncryptionManager] with a safe, non-sensitive description.
 * Message never contains key material, ciphertext, IV, or decrypted values.
 */
class DatabaseKeyException(message: String) : Exception(message)
