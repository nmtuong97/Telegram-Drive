package com.nmtuong.telegramdrive.feature.library

import com.nmtuong.telegramdrive.data.FakeTelegramRepository
import com.nmtuong.telegramdrive.data.fake.FakeTelegramCatalog
import com.nmtuong.telegramdrive.domain.AuthorizationAction
import com.nmtuong.telegramdrive.domain.AuthorizationState
import com.nmtuong.telegramdrive.domain.FileSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * LibraryViewModel tests — updated for CP2 (authorization-driven source loading).
 *
 * FakeTelegramRepository starts at WaitingForPhoneNumber.
 * Sources are only loaded after AuthorizationState.Ready.
 * Tests drive the fake through authorization before checking sources.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Helper: drive FakeTelegramRepository to Ready via phone/code/password flow */
    private fun FakeTelegramRepository.reachReady() {
        submit(AuthorizationAction.SubmitPhone("+1234567890"))
        submit(AuthorizationAction.SubmitCode("12345"))
        submit(AuthorizationAction.SubmitPassword("password"))
        check(authorization.value.state == AuthorizationState.Ready) {
            "Expected Ready but was ${authorization.value.state}"
        }
    }

    @Test
    fun `loadSources loads available sources and defaults to Saved Messages after Ready`() = runTest {
        val catalog = FakeTelegramCatalog.stable()
        val repo = FakeTelegramRepository(catalog)
        val viewModel = LibraryViewModel(repo)

        // CP2: Sources NOT loaded yet — not Ready
        runCurrent()
        assertEquals(0, viewModel.sources.value.size)
        assertNull(viewModel.selectedSourceId.value)

        // Drive to Ready — ViewModel observes and loads sources
        repo.reachReady()
        runCurrent()

        assertEquals(3, viewModel.sources.value.size)
        assertEquals(10L, viewModel.selectedSourceId.value) // Saved Messages has ID 10
    }

    @Test
    fun `selectSource updates selectedSourceId`() = runTest {
        val catalog = FakeTelegramCatalog.stable()
        val repo = FakeTelegramRepository(catalog)
        val viewModel = LibraryViewModel(repo)

        repo.reachReady()
        runCurrent()

        viewModel.selectSource(11L) // Design Assets
        runCurrent()

        assertEquals(11L, viewModel.selectedSourceId.value)
    }

    @Test
    fun `download and cancel delegate to coordinator`() = runTest {
        val catalog = FakeTelegramCatalog.stable()
        val repo = FakeTelegramRepository(catalog)
        val viewModel = LibraryViewModel(repo)

        repo.reachReady()
        runCurrent()

        viewModel.download(100)
        runCurrent()

        val stateAfterStart = viewModel.transferStates.value[100]
        assertNotNull(stateAfterStart)

        viewModel.cancel(100)
        runCurrent()

        val stateAfterCancel = viewModel.transferStates.value[100]
        assertTrue(stateAfterCancel == null || stateAfterCancel.isTerminal)
    }

    @Test
    fun `sources cleared on logout`() = runTest {
        val catalog = FakeTelegramCatalog.stable()
        val repo = FakeTelegramRepository(catalog)
        val viewModel = LibraryViewModel(repo)

        repo.reachReady()
        runCurrent()

        assertEquals(3, viewModel.sources.value.size)

        // Logout — sources should be cleared
        viewModel.logout()
        runCurrent()

        assertEquals(0, viewModel.sources.value.size)
        assertNull(viewModel.selectedSourceId.value)
    }
}
