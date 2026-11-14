package org.wolpmessenger.push

import android.content.Context
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

    companion object {
        @Volatile private var shared: EventDedup? = null

        /**
         * The one list of the process. A push and a socket frame of the same message are told apart from a
         * new message by it (spec 002 §3.3), so both paths must look at the same list.
         */
        fun shared(context: Context): EventDedup = shared ?: synchronized(this) {
            shared ?: EventDedup(File(context.applicationContext.noBackupFilesDir, "push-events.txt")).also { shared = it }
        }

        /** The key of a chat message in the list: the server's message id, the same in a push and in a socket frame. */
        fun messageKey(messageId: String) = "m:$messageId"
    }
}
