package com.nmtuong.telegramdrive.data

import com.nmtuong.telegramdrive.domain.AccountSessionIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Single owner of the active application account identity and database generation.
 * Prevents hardcoding accountId=1 and databaseGeneration=1 across the app.
 */
class AccountSessionIdentityProvider(
    initialAccountId: Long = 1L,
    initialGeneration: Long = 1L,
) {
    private val lock = Any()
    private val _currentIdentity = MutableStateFlow<AccountSessionIdentity?>(
        AccountSessionIdentity(initialAccountId, initialGeneration)
    )
    val currentIdentity: StateFlow<AccountSessionIdentity?> = _currentIdentity.asStateFlow()

    fun updateAccount(accountId: Long) {
        synchronized(lock) {
            val gen = _currentIdentity.value?.databaseGeneration ?: 1L
            _currentIdentity.value = AccountSessionIdentity(accountId, gen)
        }
    }

    fun invalidateGeneration() {
        synchronized(lock) {
            val current = _currentIdentity.value ?: return
            _currentIdentity.value = current.copy(databaseGeneration = current.databaseGeneration + 1L)
        }
    }

    fun clear() {
        synchronized(lock) {
            _currentIdentity.value = null
        }
    }
}
