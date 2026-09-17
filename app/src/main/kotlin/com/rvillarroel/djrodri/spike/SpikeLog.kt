package com.rvillarroel.djrodri.spike

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Everything the spike observes, stamped with the monotonic clock so latencies can be read straight off the log.
 * The same lines go into the report pulled with adb.
 */
class SpikeLog {
    private val start = SystemClock.elapsedRealtime()
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun add(message: String) {
        val t = SystemClock.elapsedRealtime() - start
        val line = "[%6d.%03d] %s".format(t / 1000, t % 1000, message)
        android.util.Log.d("DJRodriSpike", line)
        _lines.update { it + line }
    }
}
