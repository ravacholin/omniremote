package com.example

import com.example.controller.AndroidTvDimmerLaunch
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidTvDimmerLaunchTest {
    @Test fun `opens store then waits before sending exactly one OK and ignores duplicate taps`() = runTest {
        val events = mutableListOf<String>()
        val launcher = AndroidTvDimmerLaunch(this, { pkg, allowed -> assertTrue(allowed()); events += pkg; true }, { allowed ->
            if (allowed()) { events += "OK"; true } else false
        })
        launcher.open("com.tribalapps.tvdimmer", { true }) {}
        launcher.open("com.tribalapps.tvdimmer", { true }) {}
        runCurrent()
        assertEquals(listOf("com.tribalapps.tvdimmer"), events)
        advanceTimeBy(1999); runCurrent()
        assertEquals(1, events.size)
        advanceUntilIdle()
        assertEquals(listOf("com.tribalapps.tvdimmer", "OK"), events)
    }

    @Test fun `cancel or changed target prevents the delayed OK`() = runTest {
        for (cancel in listOf(true, false)) {
            var current = true
            var confirms = 0
            val launcher = AndroidTvDimmerLaunch(this, { _, _ -> true }, { allowed ->
                if (allowed()) confirms++
                true
            })
            launcher.open("com.tribalapps.tvdimmer", { current }) {}
            runCurrent(); advanceTimeBy(1000)
            if (cancel) launcher.cancel() else current = false
            advanceUntilIdle()
            assertEquals(0, confirms)
        }
    }

    @Test fun `failed store request never sends OK and reports failure`() = runTest {
        var result: Boolean? = null
        val launcher = AndroidTvDimmerLaunch(this, { _, _ -> false }, { fail("Must not confirm a failed store request"); false })
        launcher.open("com.tribalapps.tvdimmer", { true }) { result = it }
        advanceUntilIdle()
        assertEquals(false, result)
    }

    @Test fun `both writes receive a guard that becomes false on cancellation`() = runTest {
        var storeGuard: (() -> Boolean)? = null
        var attempted = false
        lateinit var launcher: AndroidTvDimmerLaunch
        launcher = AndroidTvDimmerLaunch(this, { _, allowed -> storeGuard = allowed; true }, { allowed ->
            assertTrue(allowed())
            assertTrue(storeGuard!!())
            launcher.cancel()
            assertFalse(allowed())
            assertFalse(storeGuard!!())
            attempted = true
            false
        })
        launcher.open("com.tribalapps.tvdimmer", { true }) { fail("Cancelled operation must not report success") }
        advanceUntilIdle()
        assertTrue(attempted)
    }
}
