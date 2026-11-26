package com.example.network

import com.example.data.model.RemoteKey

internal data class AndroidTvVolume(val max: Int, val level: Int, val muted: Boolean) {
    val percent: Int get() = ((100L * level + max / 2) / max).toInt()
    fun levelFor(percent: Int): Int = ((percent.toLong() * max + 50) / 100).toInt()
}

/** Remote v2 reports absolute volume. Seek using only a fresh observation after each key. */
internal object AndroidTvVolumeControl {
    data class Result(val verified: Boolean, val volume: AndroidTvVolume?)

    suspend fun adjust(
        initial: AndroidTvVolume,
        percent: Int?,
        muted: Boolean?,
        press: suspend (AndroidTvVolume, RemoteKey) -> AndroidTvVolume?
    ): Result {
        if (muted != null) {
            if (initial.muted == muted) return Result(true, initial)
            val observed = press(initial, RemoteKey.MUTE)
            return Result(observed?.max == initial.max && observed.muted == muted, observed)
        }
        val level = initial.levelFor(checkNotNull(percent))
        var observed = initial
        repeat(100) {
            if (observed.level == level) return Result(true, observed)
            val up = observed.level < level
            val next = press(observed, if (up) RemoteKey.VOL_UP else RemoteKey.VOL_DOWN)
                ?: return Result(false, null)
            // A mute-only update, route change or overshoot may describe a competing
            // command or a coarser sound system. Never reverse direction automatically.
            val expectedRange = if (up) (observed.level + 1)..level else level until observed.level
            if (next.max != initial.max || next.level !in expectedRange) return Result(false, next)
            observed = next
        }
        return Result(observed.level == level, observed)
    }
}
