package com.example.external

import com.example.data.model.RemoteKey
import com.example.network.AndroidTvProtocol
import com.example.network.AndroidTvVolume
import com.example.network.AndroidTvVolumeControl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AndroidVolumeControlTest {
    @Test fun readsReportedVolumeAndProtobufDefaults() {
        // Field 50 (length-delimited), fields 6=max, 7=level and 8=mute.
        assertEquals(AndroidTvVolume(15, 5, true),
            AndroidTvProtocol.readVolumeReport(byteArrayOf(0x92.toByte(), 3, 6, 48, 15, 56, 5, 64, 1)))
        assertEquals(AndroidTvVolume(15, 0, false),
            AndroidTvProtocol.readVolumeReport(byteArrayOf(0x92.toByte(), 3, 2, 48, 15)))
        assertNull(AndroidTvProtocol.readVolumeReport(byteArrayOf(0x92.toByte(), 3, 4, 48, 15, 56, 16)))
        assertNull(AndroidTvProtocol.readVolumeReport(byteArrayOf(0x92.toByte(), 3, 3, 48, 15, 56)))
    }

    @Test fun volumeUsesOnlyStepsConfirmedByTheTv() = runBlocking {
        val sent = mutableListOf<RemoteKey>()
        val result = AndroidTvVolumeControl.adjust(AndroidTvVolume(15, 5, false), 50, null) { before, key ->
            sent += key
            before.copy(level = before.level + 1)
        }
        assertEquals(listOf(RemoteKey.VOL_UP, RemoteKey.VOL_UP, RemoteKey.VOL_UP), sent)
        assertTrue(result.verified)
        assertEquals(53, result.volume?.percent)
    }

    @Test fun muteNeverResendsAfterAnAmbiguousWrite() = runBlocking {
        val sent = mutableListOf<RemoteKey>()
        val initial = AndroidTvVolume(15, 9, false)
        val unconfirmed = AndroidTvVolumeControl.adjust(initial, null, true) { _, key -> sent += key; null }
        assertFalse(unconfirmed.verified)
        assertNull(unconfirmed.volume)
        assertEquals(listOf(RemoteKey.MUTE), sent)
        val alreadySet = AndroidTvVolumeControl.adjust(initial, null, false) { _, _ -> error("Unexpected send") }
        assertTrue(alreadySet.verified)
        assertEquals(initial, alreadySet.volume)
    }

    @Test fun muteCannotBeVerifiedAcrossAnAudioScaleChange() = runBlocking {
        val result = AndroidTvVolumeControl.adjust(AndroidTvVolume(15, 5, false), null, true) { _, _ ->
            AndroidTvVolume(30, 5, true)
        }
        assertFalse(result.verified)
        assertEquals(30, result.volume?.max)
    }

    @Test fun unexpectedVolumeChangeAbortsWithoutSendingAnotherStep() = runBlocking {
        var attempts = 0
        val result = AndroidTvVolumeControl.adjust(AndroidTvVolume(15, 5, false), 20, null) { before, _ ->
            attempts++
            before.copy(level = 7)
        }
        assertEquals(1, attempts)
        assertFalse(result.verified)
        assertEquals(7, result.volume?.level)
    }
}
