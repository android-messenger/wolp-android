package org.wolpmessenger.push

import java.io.File

/** Last [capacity] push event ids (S1 §7.1), one per line in noBackupFilesDir; survives process death. */
class EventDedup(private val file: File, private val capacity: Int = 500) {
    private var ids: ArrayDeque<String>? = null

    @Synchronized
    fun firstSeen(id: String): Boolean {
        val q = ids ?: ArrayDeque(runCatching { file.readLines() }.getOrDefault(emptyList()).filter { it.isNotBlank() }).also { ids = it }
        if (id in q) return false
        q.addLast(id)
        while (q.size > capacity) q.removeFirst()
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(q.joinToString("\n"))
            tmp.renameTo(file)
        }
        return true
    }

    @Synchronized
    fun clear() {
        ids = null
        file.delete()
    }
}
