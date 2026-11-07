package com.example.sleep

import java.util.Calendar
import java.util.TimeZone

/** Resolve a local wall-clock choice once; the actual alarm remains monotonic. */
fun nextSleepOccurrence(
    hour: Int,
    minute: Int,
    nowMillis: Long = System.currentTimeMillis(),
    timeZone: TimeZone = TimeZone.getDefault()
): Long {
    require(hour in 0..23 && minute in 0..59) { "Ingresá una hora válida" }
    val localNow = Calendar.getInstance(timeZone).apply { timeInMillis = nowMillis }
    // Represent the requested civil date/time in UTC, then resolve every nearby offset.
    // This avoids Calendar choosing the later occurrence or normalizing a DST gap.
    val civil = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(localNow.get(Calendar.YEAR), localNow.get(Calendar.MONTH),
            localNow.get(Calendar.DAY_OF_MONTH), hour, minute, 0)
    }
    while (true) {
        val wall = civil.timeInMillis
        val offsets = (-48..48 step 6).map { timeZone.getOffset(wall + it * 3_600_000L) }.distinct()
        val next = offsets.map { wall - it }.filter { candidate ->
            candidate > nowMillis && candidate + timeZone.getOffset(candidate) == wall
        }.minOrNull()
        if (next != null) return next
        civil.add(Calendar.DAY_OF_MONTH, 1)
    }
}
