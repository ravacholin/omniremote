package com.example.network

import com.example.data.model.RemoteKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SamsungBluetoothAudioTest {
    @Test
    fun `failed transmission stops navigation immediately`() = runTest {
        var attempts = 0
        val result = SamsungBluetoothAudio.open {
            attempts++
            attempts < 3
        }
        assertFalse(result)
        assertEquals(3, attempts)
        advanceUntilIdle()
        assertEquals(3, attempts)
    }

    @Test
    fun `cancelling navigation sends no more menu commands`() = runTest {
        val sent = mutableListOf<RemoteKey>()
        val navigation = launch {
            SamsungBluetoothAudio.open { sent.add(it); true }
        }
        runCurrent()
        assertTrue(sent.isNotEmpty())
        val beforeCancel = sent.toList()
        navigation.cancelAndJoin()
        advanceUntilIdle()
        assertTrue(navigation.isCancelled)
        assertEquals(beforeCancel, sent)
    }
}
