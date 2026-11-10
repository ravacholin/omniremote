package com.example.controller

import kotlinx.coroutines.*

/** FlowBox sequence verified by the user: the installed app's store page focuses Open. */
class AndroidTvDimmerLaunch(
    private val scope: CoroutineScope,
    private val openStore: suspend (String, () -> Boolean) -> Boolean,
    private val confirm: suspend (() -> Boolean) -> Boolean
) {
    private var job: Job? = null
    val isRunning: Boolean get() = job?.isActive == true

    fun open(packageName: String, isCurrent: () -> Boolean, onResult: (Boolean) -> Unit) {
        if (isRunning) return
        job = scope.launch {
            val operation = currentCoroutineContext().job
            val allowed = { operation.isActive && isCurrent() }
            if (!allowed()) return@launch
            if (!openStore(packageName, allowed)) {
                if (allowed()) onResult(false)
                return@launch
            }
            // Remote v2 cannot observe Play Store focus. Give the verified Open page time to settle.
            delay(2000)
            if (!allowed()) return@launch
            val sent = confirm(allowed)
            if (allowed()) onResult(sent)
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}
