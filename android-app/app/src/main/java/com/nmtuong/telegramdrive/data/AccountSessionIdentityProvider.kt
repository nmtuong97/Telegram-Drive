package com.nmtuong.telegramdrive.data

import com.nmtuong.telegramdrive.domain.AccountSessionIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * CP7: Single owner of the active application account identity and database generation.
 *
 * Rules:
 * - Initial identity is NULL before authorization — no hardcoded (1,1) defaults.
 * - Real account ID is set after TDLib getMe returns (post-Ready).
 * - Fake account ID is set explicitly from fake catalog (e.g. Account.id).
 * - Provider is owned by AppContainer, shared by repository, coordinator, source loading.
 * - Generation increments on reset or database replacement.
 * - Late updates from stale generation are ignored by callers checking identity equality.
 *
 * Decision record:
 * Generation is process-local (not persisted). On process restart, the database may
 * still exist from a previous process, so generation starts at 1. Reset increments
 * generation to invalidate in-flight transfers from the same process. A new process
 * naturally starts a new generation context since coordinators are recreated.
 */
class AccountSessionIdentityProvider {
    private val lock = Any()
    private val _currentIdentity = MutableStateFlow<AccountSessionIdentity?>(null)
    val currentIdentity: StateFlow<AccountSessionIdentity?> = _currentIdentity.asStateFlow()
    private var nextGeneration = 1L

    /**
     * Called after authorization Ready + getMe response.
     * Preserves current generation unless explicitly reset.
     */
    fun updateAccount(accountId: Long) {
        synchronized(lock) {
            val current = _currentIdentity.value
            // Account ID is already part of every Room/cache key. Preserve the
            // active generation for the existing Phase 2 account-switch path;
            // clear()/reset is what advances the generation boundary.
            val gen = current?.databaseGeneration ?: nextGeneration
            _currentIdentity.value = AccountSessionIdentity(accountId, gen)
        }
    }

    /**
     * CP7: Initialize with an explicit fake account identity (for test/fake mode).
     * Never call with hardcoded (1L, 1L) in production path.
     */
    fun initializeFake(accountId: Long, generation: Long = 1L) {
        synchronized(lock) {
            nextGeneration = generation.coerceAtLeast(1L)
            _currentIdentity.value = AccountSessionIdentity(accountId, generation)
        }
    }

    /**
     * Increment generation to invalidate all in-flight transfers.
     * Called on reset or database replacement.
     */
    fun invalidateGeneration() {
        synchronized(lock) {
            val current = _currentIdentity.value ?: return
            nextGeneration = maxOf(nextGeneration, current.databaseGeneration + 1L)
            _currentIdentity.value = current.copy(databaseGeneration = nextGeneration)
        }
    }

    /** Called on logout or reset completion — identity becomes null until next login. */
    fun clear() {
        synchronized(lock) {
            _currentIdentity.value?.let { current ->
                nextGeneration = maxOf(nextGeneration, current.databaseGeneration + 1L)
            }
            _currentIdentity.value = null
        }
    }

    val accountId: Long? get() = _currentIdentity.value?.accountId
    val databaseGeneration: Long? get() = _currentIdentity.value?.databaseGeneration
}
