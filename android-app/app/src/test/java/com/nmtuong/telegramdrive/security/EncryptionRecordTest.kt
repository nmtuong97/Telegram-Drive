package com.nmtuong.telegramdrive.security

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [EncryptionRecord] & [EncryptionStorageResult].
 */
class EncryptionRecordTest {

    @Test
    fun `serialize and parse round-trip preserves all fields`() {
        val record = EncryptionRecord(
            schemaVersion = 1,
            ciphertextBase64 = "Y2lwaGVydGV4dA==",
            ivBase64 = "aXZkYXRh",
            keyAlias = "TestAlias",
        )
        val serialized = record.serialize()
        val parsedResult = EncryptionRecord.parse(serialized)

        assertTrue(parsedResult is EncryptionStorageResult.Valid)
        val parsed = (parsedResult as EncryptionStorageResult.Valid).record

        assertEquals(record.schemaVersion, parsed.schemaVersion)
        assertEquals(record.ciphertextBase64, parsed.ciphertextBase64)
        assertEquals(record.ivBase64, parsed.ivBase64)
        assertEquals(record.keyAlias, parsed.keyAlias)
    }

    @Test
    fun `parse returns Corrupt for empty string`() {
        assertTrue(EncryptionRecord.parse("") is EncryptionStorageResult.Corrupt)
    }

    @Test
    fun `parse returns Corrupt for too few segments`() {
        val result = EncryptionRecord.parse("1|ciphertext|iv")
        assertTrue(result is EncryptionStorageResult.Corrupt)
    }

    @Test
    fun `parse returns Corrupt for non-integer version`() {
        val result = EncryptionRecord.parse("notanint|cipher|iv|alias")
        assertTrue(result is EncryptionStorageResult.Corrupt)
    }

    @Test
    fun `parse returns Corrupt for too many segments`() {
        val result = EncryptionRecord.parse("1|cipher|iv|alias|extra")
        assertTrue(result is EncryptionStorageResult.Corrupt)
    }

    @Test
    fun `parse returns UnsupportedVersion for future schema version`() {
        val result = EncryptionRecord.parse("999|cipher|iv|alias")
        assertTrue(result is EncryptionStorageResult.UnsupportedVersion)
        assertEquals(999, (result as EncryptionStorageResult.UnsupportedVersion).version)
    }

    @Test
    fun `parse returns LegacyDetected for schema version zero`() {
        val result = EncryptionRecord.parse("0|cipher|iv|alias")
        assertTrue(result is EncryptionStorageResult.LegacyDetected)
    }

    @Test
    fun `serialize output does not contain plaintext key material`() {
        val record = EncryptionRecord(
            schemaVersion = 1,
            ciphertextBase64 = "ENCRYPTEDDATA",
            ivBase64 = "IVDATA",
            keyAlias = "Alias",
        )
        val serialized = record.serialize()
        assertFalse(serialized.contains("secret"))
        assertFalse(serialized.contains("password"))
    }

    @Test
    fun `DatabaseKeyException message does not contain key material`() {
        val exception = DatabaseKeyException("Decryption failed. Account reset required.")
        assertFalse(exception.message!!.contains("key="))
        assertFalse(exception.message!!.contains("cipher"))
        assertTrue(exception.message!!.contains("reset") || exception.message!!.contains("required"))
    }
}
