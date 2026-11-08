package org.wolpmessenger.p1

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentLinkedDeque

/** Protocol log: logcat tag "P1" (collect with `adb logcat -s P1`) + ring buffer for the probe page. */
object P1Log {
    private val ring = ConcurrentLinkedDeque<String>()
    fun i(msg: String) {
        Log.i("P1", msg)
        ring.addLast("${SystemClock.elapsedRealtime()} $msg")
        while (ring.size > 500) ring.pollFirst()
    }
    fun w(msg: String, t: Throwable? = null) {
        Log.w("P1", msg, t)
        ring.addLast("${SystemClock.elapsedRealtime()} WARN $msg ${t ?: ""}")
    }
    fun tail(n: Int = 100): List<String> = ring.toList().takeLast(n)
}

/** P1-f: cold-start milestones, elapsedRealtime ms relative to process start. */
object Timings {
    private val processStart = android.os.Process.getStartElapsedRealtime()
    private val marks = LinkedHashMap<String, Long>()
    @Synchronized fun mark(name: String) {
        if (marks.containsKey(name)) return
        val t = SystemClock.elapsedRealtime() - processStart
        marks[name] = t
        P1Log.i("timing $name = ${t} ms")
    }
    @Synchronized fun snapshot(): Map<String, Long> = LinkedHashMap(marks)
}
