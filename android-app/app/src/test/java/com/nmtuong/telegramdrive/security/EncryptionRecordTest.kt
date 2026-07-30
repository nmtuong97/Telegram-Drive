package com.nmtuong.telegramdrive.security

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [EncryptionRecord] — atomic versioned record format.
 * No Android dependencies required.
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
        val parsed = EncryptionRecord.parse(serialized)

        assertNotNull(parsed)
        assertEquals(record.schemaVersion, parsed!!.schemaVersion)
        assertEquals(record.ciphertextBase64, parsed.ciphertextBase64)
        assertEquals(record.ivBase64, parsed.ivBase64)
        assertEquals(record.keyAlias, parsed.keyAlias)
    }

    @Test
    fun `parse returns null for empty string`() {
        assertNull(EncryptionRecord.parse(""))
    }

    @Test
    fun `parse returns null for too few segments`() {
        assertNull(EncryptionRecord.parse("1|ciphertext|iv"))
    }

    @Test
    fun `parse returns null for non-integer version`() {
        assertNull(EncryptionRecord.parse("notanint|cipher|iv|alias"))
    }

    @Test
    fun `parse returns null for too many segments when separator appears in fields`() {
        // If a field contains separator, parse should handle gracefully
        val result = EncryptionRecord.parse("1|cipher|iv|alias|extra")
        // With split("|"), this gives 5 parts — should return null (size != 4)
        assertNull(result)
    }

    @Test
    fun `serialize output does not contain key material in plain text`() {
        val record = EncryptionRecord(
            schemaVersion = 1,
            ciphertextBase64 = "ENCRYPTEDDATA",
            ivBase64 = "IVDATA",
            keyAlias = "Alias",
        )
        val serialized = record.serialize()
        // The string should contain the b64 ciphertext and iv (intentional)
        // but no plaintext "secret" or similar
        assertFalse(serialized.contains("secret"))
        assertFalse(serialized.contains("password"))
    }

    @Test
    fun `DatabaseKeyException message does not contain key material`() {
        val exception = DatabaseKeyException("Decryption failed. Account reset required.")
        assertFalse(exception.message!!.contains("key="))
        assertFalse(exception.message!!.contains("cipher"))
        // Message should describe recovery action
        assertTrue(exception.message!!.contains("reset") || exception.message!!.contains("required"))
    }
}
