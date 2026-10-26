package com.example.network

import com.example.data.model.RemoteKey

/** Current macOS output volume as reported by `get volume settings`. */
data class MacVolume(val level: Int, val muted: Boolean)

/** A script for the Mac's built-in osascript, fed through stdin so nothing needs quoting. */
data class MacScript(val language: Language, val source: String) {
    enum class Language(val osascriptArgs: String) {
        APPLESCRIPT("-"),
        JAVASCRIPT("-l JavaScript -")
    }

    val command: String get() = "/usr/bin/osascript ${language.osascriptArgs}"
}

/**
 * Remote keys mapped to scripts that run on a stock macOS install.
 * Volume uses AppleScript's standard additions. Media first targets whichever of Spotify or
 * Music is playing, then the system media key (routed to the Now Playing app, so browsers
 * work too), then any open player.
 */
object MacScripts {
    const val VOLUME_STEP = 6 // macOS volume keys move in 1/16 steps (6.25%)

    private const val REPORT_VOLUME = """
set s to get volume settings
return ((output volume of s) as text) & "," & ((output muted of s) as text)"""

    val readVolume = MacScript(MacScript.Language.APPLESCRIPT, REPORT_VOLUME.trimStart())

    fun setVolume(level: Int): MacScript = MacScript(
        MacScript.Language.APPLESCRIPT,
        "set volume output volume ${level.coerceIn(0, 100)}\nset volume output muted false$REPORT_VOLUME"
    )

    private fun stepVolume(delta: Int) = MacScript(
        MacScript.Language.APPLESCRIPT,
        """
set v to output volume of (get volume settings)
set volume output volume (v + $delta)
set volume output muted false$REPORT_VOLUME""".trimStart()
    )

    private val toggleMute = MacScript(
        MacScript.Language.APPLESCRIPT,
        "set volume output muted (not (output muted of (get volume settings)))$REPORT_VOLUME"
    )

    // Turns the display off; the Mac stays awake so the session survives.
    private val displaySleep = MacScript(MacScript.Language.APPLESCRIPT, "do shell script \"pmset displaysleepnow\"\nreturn \"ok\"")

    /** Player command (Spotify and Music share these AppleScript verbs) and NX_KEYTYPE media key. */
    private enum class Media(val playerCommand: String, val nxKey: Int) {
        PLAY_PAUSE("playpause", 16),
        PLAY("play", 16),
        PAUSE("pause", 16),
        NEXT("next track", 17),
        PREVIOUS("previous track", 18)
    }

    fun forKey(key: RemoteKey): MacScript? = when (key) {
        RemoteKey.VOL_UP -> stepVolume(VOLUME_STEP)
        RemoteKey.VOL_DOWN -> stepVolume(-VOLUME_STEP)
        RemoteKey.MUTE -> toggleMute
        RemoteKey.PLAY_PAUSE -> media(Media.PLAY_PAUSE)
        RemoteKey.PLAY -> media(Media.PLAY)
        RemoteKey.PAUSE, RemoteKey.STOP -> media(Media.PAUSE)
        RemoteKey.FAST_FORWARD -> media(Media.NEXT)
        RemoteKey.REWIND -> media(Media.PREVIOUS)
        RemoteKey.POWER, RemoteKey.POWER_OFF -> displaySleep
        else -> null
    }

    /** True when [key] changes the volume, so its output carries the new [MacVolume]. */
    fun reportsVolume(key: RemoteKey): Boolean = when (key) {
        RemoteKey.VOL_UP, RemoteKey.VOL_DOWN, RemoteKey.MUTE -> true
        else -> false
    }

    /** Parses "50,false". Digital outputs without volume control report "missing value". */
    fun parseVolume(output: String): MacVolume? {
        val parts = output.trim().split(",")
        if (parts.size != 2) return null
        val level = parts[0].trim().toDoubleOrNull()?.toInt() ?: return null
        val muted = when (parts[1].trim()) {
            "true" -> true
            "false" -> false
            else -> return null
        }
        return MacVolume(level.coerceIn(0, 100), muted)
    }

    // The media key only works when "sshd-keygen-wrapper" has Accessibility access; without it
    // macOS drops the event silently, so check trust first and fall back to an open player.
    private fun media(action: Media) = MacScript(
        MacScript.Language.JAVASCRIPT,
        """
ObjC.import('Cocoa');
function player(name) {
  try {
    var app = Application(name);
    return app.running() ? app : null;
  } catch (e) { return null; }
}
function trusted() {
  try { return $.AXIsProcessTrusted(); } catch (e) { return true; }
}
function mediaKey(code) {
  [true, false].forEach(function (down) {
    var flags = down ? 0xa00 : 0xb00;
    var data1 = (code << 16) | ((down ? 0xa : 0xb) << 8);
    var ev = $.NSEvent.otherEventWithTypeLocationModifierFlagsTimestampWindowNumberContextSubtypeData1Data2(
      14, { x: 0, y: 0 }, flags, 0, 0, 0, 8, data1, -1);
    $.CGEventPost(0, ev.CGEvent);
  });
}
function run() {
  var players = ['Spotify', 'Music'].map(player).filter(function (p) { return p; });
  for (var i = 0; i < players.length; i++) {
    try {
      if (players[i].playerState() === 'playing') { players[i].${jxaVerb(action.playerCommand)}(); return players[i].name(); }
    } catch (e) {}
  }
  if (trusted()) { mediaKey(${action.nxKey}); return 'media-key'; }
  if (players.length > 0) { players[0].${jxaVerb(action.playerCommand)}(); return players[0].name(); }
  return 'no-player';
}""".trimStart()
    )

    /** JXA names AppleScript commands in camelCase: "next track" -> nextTrack. */
    private fun jxaVerb(command: String): String = command.split(" ").let { words ->
        words.first() + words.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
    }

    const val NO_PLAYER = "no-player"
}
