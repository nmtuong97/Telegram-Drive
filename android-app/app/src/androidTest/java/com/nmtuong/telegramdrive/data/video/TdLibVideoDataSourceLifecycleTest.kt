package com.nmtuong.telegramdrive.data.video

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nmtuong.telegramdrive.domain.ActionResult
import com.nmtuong.telegramdrive.domain.HistoryPage
import com.nmtuong.telegramdrive.domain.SavedMediaGateway
import com.nmtuong.telegramdrive.domain.SavedMessageUpdate
import com.nmtuong.telegramdrive.domain.TdLibFileSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TdLibVideoDataSourceLifecycleTest {
  @Test
  fun closingOneOfTwoSharedDataSourcesDoesNotCloseTransfer() {
    val gateway = CloseTrackingGateway()
    val coordinator = VideoStreamingCoordinator(
      gateway = gateway,
      fileId = 77,
      stableFileIdentity = "remote-unique:shared-video",
    )
    var references = 0
    val factory = TdLibVideoDataSource.Factory(
      coordinatorFactory = {
        references++
        coordinator
      },
      releaseFactory = { _, _ ->
        {
          references--
          if (references == 0) coordinator.close()
        }
      },
    )
    val first = factory.createDataSource()
    val second = factory.createDataSource()
    val dataSpec = DataSpec(Uri.parse("tdlib://shared-video"))

    first.open(dataSpec)
    second.open(dataSpec)
    first.close()

    assertNotEquals(VideoStreamingState.CLOSED, coordinator.status.value.state)
    assertEquals(0, gateway.deleteCalls)

    second.close()
    assertEquals(VideoStreamingState.CLOSED, coordinator.status.value.state)
    assertEquals(1, gateway.deleteCalls)
  }
}

private class CloseTrackingGateway : SavedMediaGateway {
  var deleteCalls = 0

  override val savedMessageUpdates: Flow<SavedMessageUpdate> = emptyFlow()
  override val fileUpdates: Flow<TdLibFileSnapshot> = emptyFlow()
  override suspend fun getSavedMessagesChatId(): Long? = null
  override suspend fun loadHistoryPage(chatId: Long, fromMessageId: Long, limit: Int): HistoryPage = HistoryPage.empty()
  override fun cancelFileRange(fileId: Int): ActionResult = ActionResult.ACCEPTED
  override fun deleteTemporaryFile(fileId: Int): ActionResult {
    deleteCalls++
    return ActionResult.ACCEPTED
  }
}
