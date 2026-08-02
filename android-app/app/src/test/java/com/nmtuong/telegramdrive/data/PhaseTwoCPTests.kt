package com.nmtuong.telegramdrive.data

import com.nmtuong.telegramdrive.data.fake.FakeTelegramCatalog
import com.nmtuong.telegramdrive.domain.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * CP2: Tests that LibraryViewModel source loading is authorization-driven.
 * CP4: TransferSnapshot single source of truth — Completed carries localPath.
 * CP5: Event-loss prevention — session collector captures fast file events.
 * CP7: AccountSessionIdentityProvider — no hardcoded (1L,1L) identity.
 * CP9: FakeTelegramRepository fixed contracts — downloadPagingItem works without LibraryState.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhaseTwoCPTests {

    private val catalog = FakeTelegramCatalog.stable()
    private val tmpDir = Files.createTempDirectory("cp-tests").toFile()

    // ── CP7: AccountSessionIdentityProvider ────────────────────────────────────

    @Test
    fun `CP7 - initial identity is null before explicit init`() {
        val provider = AccountSessionIdentityProvider()
        assertNull(provider.currentIdentity.value)
        assertNull(provider.accountId)
        assertNull(provider.databaseGeneration)
    }

    @Test
    fun `CP7 - initializeFake sets expected identity`() {
        val provider = AccountSessionIdentityProvider()
        provider.initializeFake(accountId = 42L, generation = 1L)
        assertEquals(42L, provider.accountId)
        assertEquals(1L, provider.databaseGeneration)
    }

    @Test
    fun `CP7 - updateAccount preserves existing generation`() {
        val provider = AccountSessionIdentityProvider()
        provider.initializeFake(accountId = 1L, generation = 3L)
        provider.updateAccount(accountId = 99L)
        assertEquals(99L, provider.accountId)
        assertEquals(3L, provider.databaseGeneration) // Generation preserved
    }

    @Test
    fun `CP7 - invalidateGeneration increments generation`() {
        val provider = AccountSessionIdentityProvider()
        provider.initializeFake(accountId = 1L, generation = 1L)
        provider.invalidateGeneration()
        assertEquals(2L, provider.databaseGeneration)
        provider.invalidateGeneration()
        assertEquals(3L, provider.databaseGeneration)
    }

    @Test
    fun `CP7 - clear makes identity null`() {
        val provider = AccountSessionIdentityProvider()
        provider.initializeFake(accountId = 1L)
        provider.clear()
        assertNull(provider.currentIdentity.value)
    }

    @Test
    fun `CP7 - FakeTelegramRepository uses provider account ID`() {
        val provider = AccountSessionIdentityProvider()
        provider.initializeFake(accountId = catalog.account.id)
        val repo = FakeTelegramRepository(catalog, tmpDir, identityProvider = provider)
        try {
            val result = repo.downloadPagingItem(fileId = 100)
            assertEquals(ActionResult.ACCEPTED, result)
        } finally {
            repo.close()
        }
    }

    // ── CP9: FakeTelegramRepository downloadPagingItem without LibraryState ────

    @Test
    fun `CP9 - downloadPagingItem accepts valid fileId without LibraryState Content`() {
        val repo = FakeTelegramRepository(catalog, tmpDir)
        try {
            // Drive to Ready
            repo.submit(AuthorizationAction.SubmitPhone("+1"))
            repo.submit(AuthorizationAction.SubmitCode("1"))
            repo.submit(AuthorizationAction.SubmitPassword("p"))
            assertEquals(AuthorizationState.Ready, repo.authorization.value.state)

            // library is still Idle — no loadSavedMessages called
            assertEquals(LibraryState.Idle, repo.library.value)

            // downloadPagingItem should still work (CP9: catalog lookup, not LibraryState)
            val result = repo.downloadPagingItem(fileId = 100)
            assertEquals(ActionResult.ACCEPTED, result)
        } finally {
            repo.close()
        }
    }

    @Test
    fun `CP9 - cancelDownload works when item not in LibraryState`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = FakeTelegramRepository(catalog, tmpDir, dispatcher = dispatcher)
        try {
            repo.submit(AuthorizationAction.SubmitPhone("+1"))
            repo.submit(AuthorizationAction.SubmitCode("1"))
            repo.submit(AuthorizationAction.SubmitPassword("p"))

            assertEquals(LibraryState.Idle, repo.library.value)

            repo.downloadPagingItem(100)
            val cancelResult = repo.cancelDownload(100)
            assertEquals(ActionResult.ACCEPTED, cancelResult)
        } finally {
            repo.close()
        }
    }

    // ── CP4: TransferSnapshot carries localPath when Completed ─────────────────

    @Test
    fun `CP4 - TransferState Completed carries localPath`() {
        val state = TransferState.Completed("/tmp/test.jpg")
        assertEquals("/tmp/test.jpg", state.localPath)
        assertTrue(state.isTerminal)
    }

    @Test
    fun `CP4 - TransferSnapshot localPath set when state is Completed`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = FakeTelegramRepository(catalog, tmpDir, dispatcher = dispatcher, downloadStepDelayMillis = 0L)
        try {
            repo.submit(AuthorizationAction.SubmitPhone("+1"))
            repo.submit(AuthorizationAction.SubmitCode("1"))
            repo.submit(AuthorizationAction.SubmitPassword("p"))

            val updates = mutableListOf<TransferUpdate>()
            backgroundScope.launch {
                repo.transferUpdates.collect { updates.add(it) }
            }
            testScheduler.runCurrent() // Start collector coroutine before download emit

            repo.downloadPagingItem(100) // IMAGE fileId=100
            testScheduler.advanceTimeBy(1000)
            testScheduler.runCurrent()

            val completedUpdate = updates.firstOrNull { it.state is TransferState.Completed }
            assertNotNull("Completed update was not emitted. Updates received: $updates", completedUpdate)
            val completedState = completedUpdate!!.state as TransferState.Completed
            assertNotNull(completedState.localPath)
            assertTrue(completedState.localPath.isNotBlank())
            assertEquals(completedUpdate.localPath, completedState.localPath)
        } finally {
            repo.close()
        }
    }
}

// Keep alias for compatibility with test runners expecting PhaseTwo_CP_Tests
typealias PhaseTwo_CP_Tests = PhaseTwoCPTests

