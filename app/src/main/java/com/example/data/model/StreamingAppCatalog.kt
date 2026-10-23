package com.example.data.model

/** TV packages (not mobile apps), with Argentina/international Paramount+ defaults. */
data class StreamingAppDefinition(
    val name: String,
    val samsungId: String,
    val androidPackage: String,
    val category: String,
    val colorHex: Long,
    val iconKey: String,
    val androidLink: String,
    val oldSamsungIds: Set<String> = emptySet(),
    val oldAndroidIds: Set<String> = emptySet()
) {
    fun forDevice(deviceId: Long, type: DeviceType, order: Int) = TvAppEntity(
        deviceId = deviceId, appName = name,
        appIdentifier = if (type == DeviceType.ANDROID_TV) androidPackage else samsungId,
        category = category, colorHex = colorHex, iconKey = iconKey,
        deepLinkUrl = androidLink, isFavorite = true, sortOrder = order
    )
}

object StreamingAppCatalog {
    val apps = listOf(
        // Flow's Tizen ID is resolved from the paired TV's installed app list.
        StreamingAppDefinition("Flow", "name:Flow", "ar.com.flow.androidtv", "Live TV", 0xFF00E6C3, "flow",
            "https://play.google.com/store/apps/details?id=ar.com.flow.androidtv"),
        StreamingAppDefinition("HBO Max", "3202301029760", "com.wbd.stream", "Movies & TV", 0xFF527BFF, "max",
            "https://play.hbomax.com", oldSamsungIds = setOf("3201601007230", "3201601007250"),
            oldAndroidIds = setOf("com.hbo.hbonow")),
        StreamingAppDefinition("Paramount+", "3202110025305", "com.cbs.ca", "Movies & TV", 0xFF2684FF, "paramount",
            "https://www.paramountplus.com/", oldSamsungIds = setOf("3201908019033", "3201710014981"),
            oldAndroidIds = setOf("com.cbs.app", "com.cbs.ott")),
        StreamingAppDefinition("Disney+", "3201901017640", "com.disney.disneyplus", "Movies & TV", 0xFF60DFFF, "disney",
            "https://www.disneyplus.com", oldSamsungIds = setOf("3202009021709", "3202204027038")),
        StreamingAppDefinition("Apple TV", "3201807016597", "com.apple.atve.androidtv.appletv", "Movies & TV", 0xFFFFFFFF, "apple",
            "https://tv.apple.com")
    )

    fun androidLaunchLink(packageName: String): String =
        apps.firstOrNull { it.androidPackage == packageName }?.androidLink ?: when (packageName) {
            "com.google.android.youtube.tv" -> "https://www.youtube.com"
            "com.netflix.ninja" -> "netflix://"
            "com.amazon.amazonvideo.livingroom" -> "https://app.primevideo.com"
            "com.plexapp.android" -> "plex://"
            "tv.twitch.android.app" -> "twitch://home"
            "com.tribalapps.tvdimmer" -> "https://play.google.com/store/apps/details?id=$packageName"
            else -> "market://launch?id=$packageName"
        }

    fun androidLaunchLinks(packageName: String, savedLink: String = ""): List<String> {
        val canonical = androidLaunchLink(packageName)
        val legacy = "market://launch?id=$packageName"
        // Repair links already stored by older APKs, even before the library refreshes.
        val obsoleteDimmerLink = packageName == "com.tribalapps.tvdimmer" && savedLink == "android-app://$packageName"
        val preferred = if (savedLink.isBlank() || savedLink == legacy || obsoleteDimmerLink) canonical else savedLink
        if (apps.none { it.androidPackage == packageName } || preferred != canonical) return listOf(preferred)
        return buildList {
            add(preferred)
            if (packageName == "com.wbd.stream") add("https://play.max.com")
            // Some TVs cannot open a package directly. Show its real store page so the
            // user can choose Open; never send Enter blindly to a possible Install button.
            add("https://play.google.com/store/apps/details?id=$packageName")
        }.distinct()
    }

}
