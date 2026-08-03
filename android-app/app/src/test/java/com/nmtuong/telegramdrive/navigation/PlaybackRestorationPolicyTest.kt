package com.nmtuong.telegramdrive.navigation

import com.nmtuong.telegramdrive.domain.AccountSessionIdentity
import com.nmtuong.telegramdrive.domain.AuthorizationState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRestorationPolicyTest {
  private val accountA = AccountSessionIdentity(accountId = 10L, databaseGeneration = 1L)
  private val accountB = AccountSessionIdentity(accountId = 20L, databaseGeneration = 2L)

  @Test
  fun readyMatchingIdentityRestoresTheRequest() {
    assertFalse(shouldDiscardPlaybackRequest(AuthorizationState.Ready, accountA, accountA))
    assertTrue(canRestorePlaybackRequest(AuthorizationState.Ready, accountA, accountA))
  }

  @Test
  fun readyTemporarilyUnresolvedIdentityRetainsButDoesNotRestoreTheRequest() {
    assertFalse(shouldDiscardPlaybackRequest(AuthorizationState.Ready, accountA, null))
    assertFalse(canRestorePlaybackRequest(AuthorizationState.Ready, accountA, null))
  }

  @Test
  fun resolvedDifferentIdentityDiscardsTheRequest() {
    assertTrue(shouldDiscardPlaybackRequest(AuthorizationState.Ready, accountA, accountB))
  }

  @Test
  fun nonReadyAuthorizationAlwaysDiscardsTheRequest() {
    assertTrue(shouldDiscardPlaybackRequest(AuthorizationState.WaitingForCode, accountA, accountA))
  }
}
