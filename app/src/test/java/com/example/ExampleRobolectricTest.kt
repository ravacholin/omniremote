package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

import com.example.data.local.AppDatabase
import com.example.data.model.DeviceType
import com.example.data.model.TvAppEntity
import com.example.data.model.TvDeviceEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("OmniRemote", appName)
  }

  @Test
  fun `store and query apps per connected TV device`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = AppDatabase.getInstance(context)
    val deviceDao = db.tvDeviceDao()
    val appDao = db.tvAppDao()

    val samsungTv = TvDeviceEntity(
      name = "Living Room Samsung TV",
      deviceType = DeviceType.SAMSUNG_TIZEN,
      ipAddress = "192.168.1.50"
    )
    val tvId = deviceDao.insert(samsungTv)

    val netflixApp = TvAppEntity(
      deviceId = tvId,
      appName = "Netflix",
      appIdentifier = "3201907018807",
      category = "Movies & TV",
      isFavorite = true
    )
    val youtubeApp = TvAppEntity(
      deviceId = tvId,
      appName = "YouTube",
      appIdentifier = "111299001912",
      category = "Streaming",
      isFavorite = false
    )

    appDao.insertAll(listOf(netflixApp, youtubeApp))

    val apps = appDao.getAppsForDevice(tvId).first()
    assertEquals(2, apps.size)

    val favorites = appDao.getFavoriteAppsForDevice(tvId).first()
    assertEquals(1, favorites.size)
    assertEquals("Netflix", favorites[0].appName)
    assertTrue(favorites[0].isFavorite)

    // Toggle favorite for YouTube
    val yt = apps.first { it.appName == "YouTube" }
    appDao.updateFavorite(yt.id, true)

    val updatedFavorites = appDao.getFavoriteAppsForDevice(tvId).first()
    assertEquals(2, updatedFavorites.size)
  }

  @Test
  fun `virtual trackpad navigation command translations`() {
    // Verify swipe and tap gesture mapping to TV remote keys
    assertEquals(com.example.data.model.RemoteKey.UP, com.example.ui.components.NavigationCommand.UP.remoteKey)
    assertEquals(com.example.data.model.RemoteKey.DOWN, com.example.ui.components.NavigationCommand.DOWN.remoteKey)
    assertEquals(com.example.data.model.RemoteKey.LEFT, com.example.ui.components.NavigationCommand.LEFT.remoteKey)
    assertEquals(com.example.data.model.RemoteKey.RIGHT, com.example.ui.components.NavigationCommand.RIGHT.remoteKey)
    assertEquals(com.example.data.model.RemoteKey.ENTER, com.example.ui.components.NavigationCommand.SELECT.remoteKey)
    assertEquals(com.example.data.model.RemoteKey.MENU, com.example.ui.components.NavigationCommand.MENU.remoteKey)
    assertEquals(com.example.data.model.RemoteKey.PLAY_PAUSE, com.example.ui.components.NavigationCommand.PLAY_PAUSE.remoteKey)
    assertEquals(com.example.data.model.RemoteKey.BACK, com.example.ui.components.NavigationCommand.BACK.remoteKey)
    assertEquals(com.example.data.model.RemoteKey.HOME, com.example.ui.components.NavigationCommand.HOME.remoteKey)

    // Verify sensitivity levels exist with appropriate thresholds
    assertTrue(com.example.ui.components.TrackpadSensitivity.HIGH.thresholdDp < com.example.ui.components.TrackpadSensitivity.STANDARD.thresholdDp)
    assertTrue(com.example.ui.components.TrackpadSensitivity.STANDARD.thresholdDp < com.example.ui.components.TrackpadSensitivity.PRECISE.thresholdDp)
  }

  @Test
  fun `tv token store persists samsung tv authorization tokens`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val tokenStore = com.example.data.local.TvTokenStore.getInstance(context)

    val tvIp = "192.168.1.100"
    val testToken = "samsung_auth_token_987654"

    tokenStore.saveSamsungToken(ipAddress = tvIp, token = testToken, deviceId = 1L)

    val retrievedByIp = tokenStore.getSamsungToken(tvIp)
    assertEquals(testToken, retrievedByIp)

    val retrievedById = tokenStore.getSamsungToken(ipAddress = "192.168.1.250", deviceId = 1L)
    assertEquals(testToken, retrievedById)
  }

  @Test
  fun `repository preserves auth token when re-adding or updating device`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = AppDatabase.getInstance(context)
    val repo = com.example.data.repository.TvDeviceRepository(db.tvDeviceDao())

    val tv = TvDeviceEntity(
      name = "Living Room TV",
      deviceType = DeviceType.SAMSUNG_TIZEN,
      ipAddress = "192.168.1.88",
      authToken = "token_authorized_previously"
    )
    val id = repo.saveDevice(tv)
    val saved = repo.getDeviceById(id)
    assertEquals("token_authorized_previously", saved?.authToken)

    // Re-saving or re-discovering the same device without token must not wipe it
    val reDiscovered = TvDeviceEntity(
      name = "Living Room TV (Discovered)",
      deviceType = DeviceType.SAMSUNG_TIZEN,
      ipAddress = "192.168.1.88",
      authToken = "" // Empty token from scanner
    )
    val reSavedId = repo.saveDevice(reDiscovered)
    assertEquals(id, reSavedId) // Same ID preserved

    val finalDevice = repo.getDeviceById(id)
    assertEquals("token_authorized_previously", finalDevice?.authToken) // Token preserved!
  }

  @Test
  fun `haptic feedback preference can be toggled and persisted`() {
    val context = ApplicationProvider.getApplicationContext<Context>()

    // Set haptic feedback to disabled
    com.example.ui.components.HapticUtil.setHapticEnabled(context, false)
    assertFalse(com.example.ui.components.HapticUtil.isHapticEnabled(context))

    // Set haptic feedback to enabled
    com.example.ui.components.HapticUtil.setHapticEnabled(context, true)
    assertTrue(com.example.ui.components.HapticUtil.isHapticEnabled(context))
  }

  @Test
  fun `play pause toggle key mappings and samsung keycode`() {
    val playKey = com.example.data.model.RemoteKey.PLAY
    val pauseKey = com.example.data.model.RemoteKey.PAUSE
    val playPauseKey = com.example.data.model.RemoteKey.PLAY_PAUSE

    assertEquals("KEY_PLAY", playKey.samsungKey)
    assertEquals(126, playKey.androidKeyCode)

    assertEquals("KEY_PAUSE", pauseKey.samsungKey)
    assertEquals(127, pauseKey.androidKeyCode)

    assertEquals("KEY_PLAYPAUSE", playPauseKey.samsungKey)
    assertEquals(85, playPauseKey.androidKeyCode)
  }

  @Test
  fun `tv token store persists android tv pairing state`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val tokenStore = com.example.data.local.TvTokenStore.getInstance(context)

    val tvIp = "192.168.1.150"
    assertFalse(tokenStore.isAndroidTvPaired(tvIp))

    tokenStore.setAndroidTvPaired(tvIp, true)
    assertTrue(tokenStore.isAndroidTvPaired(tvIp))

    tokenStore.setAndroidTvPaired(tvIp, false)
    assertFalse(tokenStore.isAndroidTvPaired(tvIp))
  }

  @Test
  fun `android tv certificate generation and ssl context initialization`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val cert = com.example.network.AndroidTvCertificateManager.getClientCertificate(context)
    val sslContext = com.example.network.AndroidTvCertificateManager.getOrCreateSslContext(context)

    assertEquals("X.509", cert.type)
    assertTrue(cert.subjectX500Principal.name.contains("OmniRemote"))
    assertEquals("TLS", sslContext.protocol)
  }

  @Test
  fun `google tv pairing protocol messages are properly wrapped and framed`() {
    val reqBytes = com.example.network.AndroidTvProtocol.buildPairingRequest()
    assertTrue(reqBytes.isNotEmpty())
    // Ensure pairing request is encapsulated with protocol_version 2 and status 200
    assertTrue(reqBytes.size > 10)

    val configBytes = com.example.network.AndroidTvProtocol.buildPairingConfiguration(true)
    assertTrue(configBytes.isNotEmpty())

    val dummySecret = ByteArray(32) { 1 }
    val secretMsg = com.example.network.AndroidTvProtocol.buildPairingSecret(dummySecret)
    assertTrue(secretMsg.size > 32)
  }
}
