package com.nmtuong.telegramdrive.bootstrap

import android.content.Context
import com.nmtuong.telegramdrive.BuildConfig
import com.nmtuong.telegramdrive.data.AccountSessionIdentityProvider
import com.nmtuong.telegramdrive.data.FakeTelegramRepository
import com.nmtuong.telegramdrive.data.MediaAccessCoordinator
import com.nmtuong.telegramdrive.data.RealTelegramRepository
import com.nmtuong.telegramdrive.data.SavedMediaRepository
import com.nmtuong.telegramdrive.data.TelegramRepository
import com.nmtuong.telegramdrive.data.fake.FakeSavedMediaGateway
import com.nmtuong.telegramdrive.data.fake.FakeTelegramCatalog
import com.nmtuong.telegramdrive.data.local.MediaDatabase
import com.nmtuong.telegramdrive.domain.DataSourceMode
import com.nmtuong.telegramdrive.domain.ActionResult
import com.nmtuong.telegramdrive.domain.AuthorizationAction
import com.nmtuong.telegramdrive.telegram.TdLibJsonGateway
import com.nmtuong.telegramdrive.domain.SavedMediaGateway
import com.nmtuong.telegramdrive.security.TelegramApiConfiguration
import java.io.Closeable

/**
 * CP7+CP8: AppContainer owns AccountSessionIdentityProvider.
 * - Provider starts with null identity.
 * - Gateway receives provider lambdas for current accountId/generation.
 * - ViewModel receives provider for coordinator/pager identity.
 * - Provider updated via authorization observation in MainActivity/Application.
 */
class AppContainer private constructor(
  val telegramRepository: TelegramRepository,
  val savedMediaRepository: SavedMediaRepository,
  val mediaAccessCoordinator: MediaAccessCoordinator,
  val sampleCatalog: FakeTelegramCatalog,
  /** CP7: Shared identity provider — owned here, used by repo, coordinator, ViewModel. */
  val identityProvider: AccountSessionIdentityProvider,
) : Closeable {
  fun start() = telegramRepository.start()
  fun logout(): ActionResult {
    savedMediaRepository.cancelCurrentAccountWork()
    mediaAccessCoordinator.cancelForAccount()
    savedMediaRepository.clearCurrentAccount()
    return telegramRepository.submit(AuthorizationAction.Logout)
  }
  fun resetAccount(): ActionResult {
    savedMediaRepository.cancelCurrentAccountWork()
    mediaAccessCoordinator.cancelForAccount()
    savedMediaRepository.clearCurrentAccount()
    return telegramRepository.submit(AuthorizationAction.Reset)
  }
  override fun close() {
    mediaAccessCoordinator.close()
    savedMediaRepository.close()
    telegramRepository.close()
  }

  companion object {
    fun create(context: Context): AppContainer {
      val catalog = FakeTelegramCatalog.stable()
      // CP7: Provider starts null — no hardcoded (1L,1L)
      val identityProvider = AccountSessionIdentityProvider()

      val savedMediaGateway: SavedMediaGateway
      val repository: TelegramRepository = if (BuildConfig.TELEGRAM_DATA_SOURCE == DataSourceMode.FAKE.id) {
        // CP7: Fake mode — initialize with catalog account ID explicitly
        identityProvider.initializeFake(catalog.account.id)
        savedMediaGateway = FakeSavedMediaGateway(
          catalog = catalog,
          cacheDirectory = context.cacheDir.resolve("fake-media"),
          videoBytes = { context.assets.open("fake-video.mp4").use { it.readBytes() } },
        )
        FakeTelegramRepository(
          catalog,
          context.cacheDir.resolve("fake-media"),
          videoBytes = { context.assets.open("fake-video.mp4").use { it.readBytes() } },
          identityProvider = identityProvider,
        )
      } else {
        // CP7: Real mode — gateway receives identity provider lambdas
        // Account ID is set after authorization Ready + getMe; generation from provider
        val gateway = TdLibJsonGateway(
          context.applicationContext,
          TelegramApiConfiguration(BuildConfig.TELEGRAM_API_ID, BuildConfig.TELEGRAM_API_HASH),
          identityProvider = identityProvider,
          currentAccountId = { identityProvider.accountId ?: 0L },
          currentDatabaseGeneration = { identityProvider.databaseGeneration ?: 1L },
        )
        savedMediaGateway = gateway
        RealTelegramRepository(
          gateway,
        )
      }
      val mediaDatabase = MediaDatabase.create(context.applicationContext)
      val savedMediaRepository = SavedMediaRepository(
        database = mediaDatabase,
        gateway = savedMediaGateway,
        identityProvider = identityProvider,
      )
      savedMediaRepository.start()
      val mediaAccessCoordinator = MediaAccessCoordinator(
        database = mediaDatabase,
        gateway = savedMediaGateway,
        identityProvider = identityProvider,
      )
      return AppContainer(repository, savedMediaRepository, mediaAccessCoordinator, catalog, identityProvider)
    }
  }
}
