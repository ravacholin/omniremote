package com.example.external

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RemoteCommandExecutorTest {
    private val mute = RemoteRequest(7, Command.KEY, listOf("MUTE"))
    private val id = "execution-0001"

    @Test fun duplicateDoesNotResendAndAnotherDeviceCanRun() = runTest {
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<Long>()
        val executor = executor { request, _ ->
            order.add(request.deviceId)
            if (request.deviceId == 7L) release.await()
            RemoteReply.sent
        }
        assertEquals("PENDING", status(executor.execute(id, mute) { true }))
        assertEquals("PENDING", status(executor.execute(id, mute) { true }))
        assertEquals("DEVICE_BUSY", code(executor.execute("execution-0002", mute) { true }))
        assertEquals("EXECUTION_ID_CONFLICT", code(executor.execute(id, mute.copy(deviceId = 8)) { true }))
        executor.execute("execution-0003", mute.copy(deviceId = 8)) { true }
        runCurrent()
        assertEquals(listOf(7L, 8L), order)
        assertEquals("SENT", status(executor.getExecution("execution-0003")))
        release.complete(Unit)
        runCurrent()
        assertEquals("SENT", status(executor.execute(id, mute) { true }))
        assertEquals(listOf(7L, 8L), order)
    }

    @Test fun pendingRecordFromAnotherProcessIsNeverReplayed() = runTest {
        val release = CompletableDeferred<Unit>()
        val original = executor { _, _ -> release.await(); RemoteReply.sent }
        original.execute(id, mute) { true }
        runCurrent()
        val restarted = executor { _, _ -> error("Must not dispatch again") }
        assertEquals("INTERRUPTED", code(restarted.getExecution(id)))
        assertEquals("INTERRUPTED", code(restarted.execute(id, mute) { true }))
        release.complete(Unit)
        runCurrent()
    }

    @Test fun deadlineIsTerminalAndAuthorizationIsCheckedAgainBeforeDispatch() = runTest {
        var calls = 0
        val executor = executor { _, _ -> calls++; CompletableDeferred<RemoteReply>().await() }
        executor.execute(id, mute) { true }
        executor.execute("execution-0002", mute.copy(deviceId = 8)) { false }
        runCurrent()
        advanceTimeBy(RemoteCommandExecutor.TIMEOUT_MS)
        runCurrent()
        assertEquals("TIMEOUT", code(executor.execute(id, mute) { true }))
        assertEquals("CALLER_NO_LONGER_AUTHORIZED", code(executor.getExecution("execution-0002")))
        assertEquals(1, calls)
    }

    private fun TestScope.executor(dispatch: suspend (RemoteRequest, () -> Boolean) -> RemoteReply) =
        RemoteCommandExecutor(this, RuntimeEnvironment.getApplication().getSharedPreferences("execution-test", Context.MODE_PRIVATE),
            "test:", elapsed = { testScheduler.currentTime }, dispatch = dispatch)

    private fun code(encoded: String) = JSONObject(encoded).getString("code")
    private fun status(encoded: String) = JSONObject(encoded).getString("status")
}
