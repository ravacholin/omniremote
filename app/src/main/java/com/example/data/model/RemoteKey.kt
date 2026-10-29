package com.example.data.model

enum class RemoteKey(
    val label: String,
    val samsungKey: String,
    val androidKeyCode: Int
) {
    // Power
    POWER("Power", "KEY_POWER", 26),
    POWER_OFF("Power Off", "KEY_POWEROFF", 223), // KEYCODE_SLEEP never wakes an already sleeping TV.

    // Volume & Sound
    VOL_UP("Vol +", "KEY_VOLUP", 24),
    VOL_DOWN("Vol -", "KEY_VOLDOWN", 25),
    MUTE("Mute", "KEY_MUTE", 164),

    // Channels
    CH_UP("CH +", "KEY_CHUP", 166),
    CH_DOWN("CH -", "KEY_CHDOWN", 167),

    // Navigation D-Pad
    UP("Up", "KEY_UP", 19),
    DOWN("Down", "KEY_DOWN", 20),
    LEFT("Left", "KEY_LEFT", 21),
    RIGHT("Right", "KEY_RIGHT", 22),
    ENTER("Select", "KEY_ENTER", 23),

    // System Navigation
    BACK("Back", "KEY_RETURN", 4),
    HOME("Home", "KEY_HOME", 3),
    MENU("Menu", "KEY_MENU", 82),
    INFO("Info", "KEY_INFO", 165),
    SOURCE("Source", "KEY_SOURCE", 178),
    SETTINGS("Settings", "KEY_TOOLS", 176),
    EXIT("Exit", "KEY_EXIT", 111),

    // Media Playback
    PLAY("Play", "KEY_PLAY", 126),
    PAUSE("Pause", "KEY_PAUSE", 127),
    PLAY_PAUSE("Play/Pause", "KEY_PLAYPAUSE", 85),
    STOP("Stop", "KEY_STOP", 86),
    REWIND("Rewind", "KEY_REWIND", 89),
    FAST_FORWARD("Fast Forward", "KEY_FF", 90),

    // Numeric
    NUM_0("0", "KEY_0", 7),
    NUM_1("1", "KEY_1", 8),
    NUM_2("2", "KEY_2", 9),
    NUM_3("3", "KEY_3", 10),
    NUM_4("4", "KEY_4", 11),
    NUM_5("5", "KEY_5", 12),
    NUM_6("6", "KEY_6", 13),
    NUM_7("7", "KEY_7", 14),
    NUM_8("8", "KEY_8", 15),
    NUM_9("9", "KEY_9", 16),
    NUM_PRE_CH("Prev CH", "KEY_PRECH", 229),

    // Colors
    COLOR_RED("Red", "KEY_RED", 183),
    COLOR_GREEN("Green", "KEY_GREEN", 184),
    COLOR_YELLOW("Yellow", "KEY_YELLOW", 185),
    COLOR_BLUE("Blue", "KEY_BLUE", 186);

    companion object {
        fun fromNumber(digit: Int): RemoteKey = when (digit) {
            0 -> NUM_0
            1 -> NUM_1
            2 -> NUM_2
            3 -> NUM_3
            4 -> NUM_4
            5 -> NUM_5
            6 -> NUM_6
            7 -> NUM_7
            8 -> NUM_8
            9 -> NUM_9
            else -> NUM_0
        }
    }
}

enum class TvAppShortcut(
    val title: String,
    val iconName: String,
    val samsungAppId: String,
    val androidPackageName: String,
    val deepLinkUrl: String
) {
    NETFLIX(
        title = "Netflix",
        iconName = "netflix",
        samsungAppId = "3201907018807", // Samsung Tizen Netflix App ID
        androidPackageName = "com.netflix.ninja",
        deepLinkUrl = "netflix://"
    ),
    YOUTUBE(
        title = "YouTube",
        iconName = "youtube",
        samsungAppId = "111299001912", // Samsung Tizen YouTube App ID
        androidPackageName = "com.google.android.youtube.tv",
        deepLinkUrl = "https://www.youtube.com"
    ),
    PRIME_VIDEO(
        title = "Prime Video",
        iconName = "prime",
        samsungAppId = "3201512006785",
        androidPackageName = "com.amazon.amazonvideo.livingroom",
        deepLinkUrl = "https://www.amazon.com/video"
    ),
    DISNEY_PLUS(
        title = "Disney+",
        iconName = "disney",
        samsungAppId = "3201901017640",
        androidPackageName = "com.disney.disneyplus",
        deepLinkUrl = "disneyplus://"
    ),
    SPOTIFY(
        title = "Spotify",
        iconName = "spotify",
        samsungAppId = "3201606009684",
        androidPackageName = "com.spotify.tv.android",
        deepLinkUrl = "spotify://"
    ),
    TWITCH(
        title = "Twitch",
        iconName = "twitch",
        samsungAppId = "3201710015037",
        androidPackageName = "tv.twitch.android.app",
        deepLinkUrl = "twitch://"
    ),
    BROWSER(
        title = "Web Browser",
        iconName = "browser",
        samsungAppId = "org.tizen.browser",
        androidPackageName = "com.android.chrome",
        deepLinkUrl = "https://www.google.com"
    ),
    PLEX(
        title = "Plex",
        iconName = "plex",
        samsungAppId = "3201512006963",
        androidPackageName = "com.plexapp.android",
        deepLinkUrl = "plex://"
    )
}
