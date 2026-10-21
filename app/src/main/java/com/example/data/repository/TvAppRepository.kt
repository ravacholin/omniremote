package com.example.data.repository

import com.example.data.local.TvAppDao
import com.example.data.model.DeviceType
import com.example.data.model.TvAppEntity
import kotlinx.coroutines.flow.Flow

class TvAppRepository(private val dao: TvAppDao) {

    fun getAppsForDevice(deviceId: Long): Flow<List<TvAppEntity>> =
        dao.getAppsForDevice(deviceId)

    fun getFavoriteAppsForDevice(deviceId: Long): Flow<List<TvAppEntity>> =
        dao.getFavoriteAppsForDevice(deviceId)

    suspend fun ensureDefaultAppsForDevice(deviceId: Long, deviceType: DeviceType) {
        val count = dao.getAppCountForDevice(deviceId)
        if (count == 0) {
            val defaultApps = when (deviceType) {
                DeviceType.SAMSUNG_TIZEN -> getSamsungDefaultApps(deviceId)
                DeviceType.ANDROID_TV -> getAndroidTvDefaultApps(deviceId)
            }
            dao.insertAll(defaultApps)
        }
    }

    suspend fun saveApp(app: TvAppEntity): Long {
        return if (app.id == 0L) {
            dao.insert(app)
        } else {
            dao.update(app)
            app.id
        }
    }

    suspend fun toggleFavorite(id: Long, currentFavorite: Boolean) {
        dao.updateFavorite(id, !currentFavorite)
    }

    suspend fun recordLaunch(id: Long) {
        dao.recordLaunch(id, System.currentTimeMillis())
    }

    suspend fun deleteApp(app: TvAppEntity) {
        dao.delete(app)
    }

    suspend fun deleteAppById(id: Long) {
        dao.deleteById(id)
    }

    private fun getSamsungDefaultApps(deviceId: Long): List<TvAppEntity> {
        return listOf(
            TvAppEntity(
                deviceId = deviceId,
                appName = "Netflix",
                appIdentifier = "3201907018807",
                category = "Movies & TV",
                colorHex = 0xFFE50914,
                iconKey = "movie",
                deepLinkUrl = "netflix://",
                isFavorite = true,
                sortOrder = 1
            ),
            TvAppEntity(
                deviceId = deviceId,
                appName = "YouTube",
                appIdentifier = "111299001912",
                category = "Streaming",
                colorHex = 0xFFFF0000,
                iconKey = "youtube",
                deepLinkUrl = "https://www.youtube.com",
                isFavorite = true,
                sortOrder = 2
            ),
            TvAppEntity(
                deviceId = deviceId,
                appName = "Prime Video",
                appIdentifier = "3201512006785",
                category = "Movies & TV",
                colorHex = 0xFF00A8E1,
                iconKey = "movie",
                deepLinkUrl = "https://www.amazon.com/video",
                isFavorite = true,
                sortOrder = 3
            ),
            TvAppEntity(
                deviceId = deviceId,
                appName = "Disney+",
                appIdentifier = "3201901017640",
                category = "Movies & TV",
                colorHex = 0xFF113CCF,
                iconKey = "movie",
                deepLinkUrl = "disneyplus://",
                isFavorite = true,
                sortOrder = 4
            ),
            TvAppEntity(
                deviceId = deviceId,
                appName = "Spotify",
                appIdentifier = "3201606009684",
                category = "Music & Audio",
                colorHex = 0xFF1DB954,
                iconKey = "music",
                deepLinkUrl = "spotify://",
                isFavorite = true,
                sortOrder = 5
            ),
            TvAppEntity(
                deviceId = deviceId,
                appName = "Twitch",
                appIdentifier = "3201710015037",
                category = "Streaming",
                colorHex = 0xFF9146FF,
                iconKey = "tv",
                deepLinkUrl = "twitch://",
                isFavorite = false,
                sortOrder = 6
            ),
            TvAppEntity(
                deviceId = deviceId,
                appName = "Plex",
                appIdentifier = "3201512006963",
                category = "Movies & TV",
                colorHex = 0xFFE5A00D,
                iconKey = "movie",
                deepLinkUrl = "plex://",
                isFavorite = false,
                sortOrder = 7
            ),
            TvAppEntity(
                deviceId = deviceId,
                appName = "Apple TV",
                appIdentifier = "3201807016597",
                category = "Movies & TV",
                colorHex = 0xFF2D3748,
                iconKey = "tv",
                isFavorite = false,
                sortOrder = 8
            ),
            TvAppEntity(
                deviceId = deviceId,
                appName = "Web Browser",
                appIdentifier = "org.tizen.browser",
                category = "Tools",
                colorHex = 0xFF38BDF8,
                iconKey = "browser",
                deepLinkUrl = "https://www.google.com",
                isFavorite = false,
                sortOrder = 9
            )
        )
    }

    private fun getAndroidTvDefaultApps(deviceId: Long): List<TvAppEntity> {
        return listOf(
            TvAppEntity(
                deviceId = deviceId,
                appName = "Netflix",
                appIdentifier = "com.netflix.ninja",
                category = "Movies & TV",
                colorHex = 0xFFE50914,
                iconKey = "movie",
                deepLinkUrl = "netflix://",
                isFavorite = true,
                sortOrder = 1
            ),
            TvAppEntity(
                deviceId = deviceId,
                appName = "YouTube",
                appIdentifier = "com.google.android.youtube.tv",
                category = "Streaming",
                colorHex = 0xFFFF0000,
                iconKey = "youtube",
                deepLinkUrl = "https://www.youtube.com",
                isFavorite = true,
                sortOrder = 2
            ),
            TvAppEntity(
                deviceId = deviceId,
                appName = "Prime Video",
                appIdentifier = "com.amazon.amazonvideo.livingroom",
                category = "Movies & TV",
                colorHex = 0xFF00A8E1,
                iconKey = "movie",
                deepLinkUrl = "https://www.amazon.com/video",
                isFavorite = true,
                sortOrder = 3
            ),
            TvAppEntity(
                deviceId = deviceId,
                appName = "Disney+",
                appIdentifier = "com.disney.disneyplus",
                category = "Movies & TV",
                colorHex = 0xFF113CCF,
                iconKey = "movie",
                deepLinkUrl = "disneyplus://",
                isFavorite = true,
                sortOrder = 4
            ),
            TvAppEntity(
                deviceId = deviceId,
                appName = "Spotify",
                appIdentifier = "com.spotify.tv.android",
                category = "Music & Audio",
                colorHex = 0xFF1DB954,
                iconKey = "music",
                deepLinkUrl = "spotify://",
                isFavorite = true,
                sortOrder = 5
            ),
            TvAppEntity(
                deviceId = deviceId,
                appName = "Twitch",
                appIdentifier = "tv.twitch.android.app",
                category = "Streaming",
                colorHex = 0xFF9146FF,
                iconKey = "tv",
                deepLinkUrl = "twitch://",
                isFavorite = false,
                sortOrder = 6
            ),
            TvAppEntity(
                deviceId = deviceId,
                appName = "Plex",
                appIdentifier = "com.plexapp.android",
                category = "Movies & TV",
                colorHex = 0xFFE5A00D,
                iconKey = "movie",
                deepLinkUrl = "plex://",
                isFavorite = false,
                sortOrder = 7
            ),
            TvAppEntity(
                deviceId = deviceId,
                appName = "Kodi",
                appIdentifier = "org.xbmc.kodi",
                category = "Tools",
                colorHex = 0xFF13B5EA,
                iconKey = "tv",
                isFavorite = false,
                sortOrder = 8
            ),
            TvAppEntity(
                deviceId = deviceId,
                appName = "VLC Player",
                appIdentifier = "org.videolan.vlc",
                category = "Tools",
                colorHex = 0xFFFF8800,
                iconKey = "movie",
                isFavorite = false,
                sortOrder = 9
            )
        )
    }
}
