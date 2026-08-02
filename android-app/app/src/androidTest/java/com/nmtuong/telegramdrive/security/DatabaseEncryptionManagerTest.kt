package com.nmtuong.telegramdrive.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [DatabaseEncryptionManager] — requires Android Keystore.
 * Run with: ./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class DatabaseEncryptionManagerTest {

    private lateinit var manager: DatabaseEncryptionManager

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        manager = DatabaseEncryptionManager(context)
        // Clean slate for each test
        runCatching { manager.clearKey() }
    }

    @After
    fun tearDown() {
        runCatching { manager.clearKey() }
    }

    // ── First creation ────────────────────────────────────────────────────────

    @Test
    fun `first getOrGenerateKey creates and persists a non-empty key`() {
        val key = manager.getOrGenerateKey()
        assertNotNull(key)
        assertTrue(key.isNotEmpty())
    }

    // ── Reuse ─────────────────────────────────────────────────────────────────

    @Test
    fun `subsequent getOrGenerateKey returns same key`() {
        val key1 = manager.getOrGenerateKey()
        val key2 = manager.getOrGenerateKey()
        assertEquals("Key should be stable across calls", key1, key2)
    }

    // ── Clear and regenerate ──────────────────────────────────────────────────

    @Test
    fun `clearKey then getOrGenerateKey produces different key`() {
        val key1 = manager.getOrGenerateKey()
        manager.clearKey()
        val key2 = manager.getOrGenerateKey()
        assertNotEquals("New key must differ after clear", key1, key2)
    }

    // ── Missing Keystore alias ────────────────────────────────────────────────

    @Test
    fun `missing Keystore alias throws DatabaseKeyException`() {
        manager.getOrGenerateKey() // Generate record

        // Manually delete Keystore entry
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        if (ks.containsAlias(DatabaseEncryptionManager.DEFAULT_KEY_ALIAS)) {
            ks.deleteEntry(DatabaseEncryptionManager.DEFAULT_KEY_ALIAS)
        }

        try {
            manager.getOrGenerateKey()
            fail("Expected DatabaseKeyException")
        } catch (e: DatabaseKeyException) {
            // Expected — message should not contain key material
            assertFalse(e.message.orEmpty().contains("key="))
            assertFalse(e.message.orEmpty().contains("cipher"))
            assertTrue(e.message.orEmpty().isNotBlank())
        }
    }

    // ── Clear clears both record and Keystore ─────────────────────────────────

    @Test
    fun `clearKey removes Keystore alias`() {
        manager.getOrGenerateKey()
        manager.clearKey()

        val ks = java.security.KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        assertFalse(
            "Keystore alias should be removed after clear",
            ks.containsAlias(DatabaseEncryptionManager.DEFAULT_KEY_ALIAS)
        )
    }

    // ── Corrupt record ────────────────────────────────────────────────────────

    @Test
    fun `corrupt ciphertext record throws DatabaseKeyException`() {
        manager.getOrGenerateKey() // Generate valid record

        // Corrupt the stored record
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("tdlib_encryption_v3", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("encryption_record_v1", "1|CORRUPTCIPHERTEXT|CORRUPTIV|${DatabaseEncryptionManager.DEFAULT_KEY_ALIAS}").commit()

        try {
            manager.getOrGenerateKey()
            fail("Expected DatabaseKeyException for corrupt ciphertext")
        } catch (e: DatabaseKeyException) {
            assertTrue(e.message.orEmpty().isNotBlank())
            // Message should not leak key material
            assertFalse(e.message.orEmpty().contains("key="))
        }
    }

    // ── Manager recreation (process restart simulation) ────────────────────────

    @Test
    fun `manager recreated after persist returns same key (process restart simulation)`() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key1 = manager.getOrGenerateKey()

        // Simulate process restart by creating new manager instance
        val manager2 = DatabaseEncryptionManager(context)
        val key2 = manager2.getOrGenerateKey()

        assertEquals("Key should persist across manager recreations", key1, key2)
    }

    // ── Error message safety ──────────────────────────────────────────────────

    @Test
    fun `DatabaseKeyException message does not expose key material or ciphertext`() {
        manager.getOrGenerateKey()
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        ks.deleteEntry(DatabaseEncryptionManager.DEFAULT_KEY_ALIAS)

        val e = try {
            manager.getOrGenerateKey()
            null
        } catch (e: DatabaseKeyException) {
            e
        }

        assertNotNull(e)
        // Must not contain raw key material indicators
        assertFalse(e!!.message.orEmpty().contains("key="))
        assertFalse(e.message.orEmpty().contains("cipher"))
        assertFalse(e.message.orEmpty().contains("iv="))
    }
}
