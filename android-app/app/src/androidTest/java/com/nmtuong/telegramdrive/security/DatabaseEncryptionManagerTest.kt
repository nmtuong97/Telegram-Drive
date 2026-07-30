package com.nmtuong.telegramdrive.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseEncryptionManagerTest {

    private lateinit var encryptionManager: DatabaseEncryptionManager

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        encryptionManager = DatabaseEncryptionManager(context)
        encryptionManager.clearKey()
    }

    @After
    fun tearDown() {
        encryptionManager.clearKey()
    }

    @Test
    fun testGenerateAndRetrieveKey() {
        // Generate for the first time
        val key1 = encryptionManager.getOrGenerateKey()
        assertNotNull(key1)
        assertTrue(key1.isNotEmpty())

        // Retrieve again should return the same key
        val key2 = encryptionManager.getOrGenerateKey()
        assertEquals("Key should remain identical upon subsequent fetches", key1, key2)
    }

    @Test
    fun testClearKey() {
        val key1 = encryptionManager.getOrGenerateKey()
        
        encryptionManager.clearKey()

        val key2 = encryptionManager.getOrGenerateKey()
        assertNotEquals("A new key should be generated after clearing", key1, key2)
    }

    @Test
    fun testMissingKeyStoreKey() {
        val key1 = encryptionManager.getOrGenerateKey()
        
        // Simulate KeyStore deletion
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        keyStore.deleteEntry("TelegramDriveDatabaseKeyAlias")

        try {
            encryptionManager.getOrGenerateKey()
            fail("Expected IllegalStateException due to missing keystore key")
        } catch (e: IllegalStateException) {
            // Expected
        }
    }
}
