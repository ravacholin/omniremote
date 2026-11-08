package com.example.share

import com.example.data.local.MacLinkDao
import com.example.data.model.MacLinkEntity
import com.example.network.MacResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** One coordinator per application; reject repeated taps rather than queuing more opens. */
class MacLinkQueue(private val dao: MacLinkDao) {
    private val opening = Mutex()

    suspend fun enqueue(deviceId: Long, link: SharedMacLink): Long =
        dao.insert(MacLinkEntity(deviceId = deviceId, url = link.url, title = link.title))

    suspend fun openNext(deviceId: Long, opener: suspend (Long, String) -> MacResult): MacResult =
        dispatch(deviceId, { dao.first(deviceId) }, opener)

    suspend fun open(deviceId: Long, id: Long, opener: suspend (Long, String) -> MacResult): MacResult =
        dispatch(deviceId, { dao.get(deviceId, id) }, opener)

    private suspend fun dispatch(deviceId: Long, find: suspend () -> MacLinkEntity?,
        opener: suspend (Long, String) -> MacResult): MacResult {
        if (!opening.tryLock()) return MacResult.Failed("Ya se está abriendo un enlace.")
        try {
            val head = find() ?: return MacResult.Failed("No hay enlaces pendientes.")
            // Once the Mac accepts an open, finish recording it even if the screen closes.
            return withContext(NonCancellable) {
                val result = opener(deviceId, head.url)
                if (result is MacResult.Ok) dao.delete(deviceId, head.id)
                result
            }
        } finally { opening.unlock() }
    }
}
